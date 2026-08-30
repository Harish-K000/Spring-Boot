package com.example.platform.payments.service;

import com.example.platform.payments.client.OrderClient;
import com.example.platform.payments.client.OrderClient.OrderSnapshot;
import com.example.platform.payments.dto.PaymentResponse;
import com.example.platform.payments.dto.ProcessPaymentRequest;
import com.example.platform.payments.exception.*;
import com.example.platform.payments.mapper.PaymentMapper;
import com.example.platform.payments.model.PaymentStatus;
import com.example.platform.payments.model.PaymentTransaction;
import com.example.platform.payments.provider.PaymentDeclinedException;
import com.example.platform.payments.provider.PaymentProvider;
import com.example.platform.payments.repository.PaymentTransactionRepository;
import io.micrometer.observation.annotation.Observed;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Synchronous payment coordinator with durable local checkpoints between external operations. */
@Service
public class PaymentProcessingService {

    static final String ORDER_SYNC_PENDING = "ORDER_SYNC_PENDING";

    private final PaymentTransactionRepository paymentRepository;
    private final PaymentMapper paymentMapper;
    private final OrderClient orderClient;
    private final PaymentProvider paymentProvider;
    private final PaymentCheckpointService checkpointService;

    public PaymentProcessingService(PaymentTransactionRepository paymentRepository,
                                    PaymentMapper paymentMapper,
                                    OrderClient orderClient,
                                    PaymentProvider paymentProvider,
                                    PaymentCheckpointService checkpointService) {
        this.paymentRepository = paymentRepository;
        this.paymentMapper = paymentMapper;
        this.orderClient = orderClient;
        this.paymentProvider = paymentProvider;
        this.checkpointService = checkpointService;
    }

    @Observed(name = "platform.payments.process", contextualName = "process-payment")
    public PaymentResponse process(UUID userId, String idempotencyKey, ProcessPaymentRequest request) {
        String fingerprint = fingerprint(userId, request);
        PaymentTransaction payment = paymentRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (payment != null) {
            validateReplay(payment, userId, fingerprint);
            PaymentResponse terminal = terminalReplay(payment);
            if (terminal != null) {
                return terminal;
            }
        } else {
            OrderSnapshot order = orderClient.findOrder(request.orderId());
            validateOrder(order, userId, request.orderId());
            if (paymentRepository.existsByOrderIdAndStatusIn(request.orderId(),
                    java.util.List.of(PaymentStatus.PENDING, PaymentStatus.AUTHORIZED, PaymentStatus.CAPTURED))) {
                throw new DuplicatePaymentException(request.orderId());
            }
            payment = new PaymentTransaction();
            payment.setOrderId(order.id());
            payment.setActiveOrderId(order.id());
            payment.setUserId(userId);
            payment.setAmount(order.totalAmount());
            payment.setCurrency(order.currency());
            payment.setProvider(paymentProvider.name());
            payment.setStatus(PaymentStatus.PENDING);
            payment.setIdempotencyKey(idempotencyKey);
            payment.setRequestFingerprint(fingerprint);
            try {
                payment = paymentRepository.saveAndFlush(payment);
            } catch (DataIntegrityViolationException ex) {
                PaymentTransaction raced = paymentRepository.findByIdempotencyKey(idempotencyKey)
                        .orElseThrow(() -> new DuplicatePaymentException(request.orderId()));
                validateReplay(raced, userId, fingerprint);
                PaymentResponse terminal = terminalReplay(raced);
                if (terminal != null) {
                    return terminal;
                }
                payment = raced;
            }
        }

        if (payment.getStatus() == PaymentStatus.PENDING) {
            try {
                String reference = paymentProvider.authorize(
                        idempotencyKey + ":authorize", payment.getOrderId(), payment.getAmount(),
                        payment.getCurrency(), request.paymentMethodToken());
                payment.setProviderReference(reference);
                payment.setStatus(PaymentStatus.AUTHORIZED);
                payment.setFailureCode(null);
                payment = paymentRepository.saveAndFlush(payment);
            } catch (PaymentDeclinedException ex) {
                payment.setStatus(PaymentStatus.FAILED);
                payment.setActiveOrderId(null);
                payment.setFailureCode(ex.getCode());
                return paymentMapper.toResponse(
                        checkpointService.saveTerminal(payment, "PAYMENT_FAILED"));
            } catch (RuntimeException ex) {
                payment.setFailureCode("AUTHORIZATION_UNKNOWN");
                paymentRepository.saveAndFlush(payment);
                throw new PaymentDependencyException("Payment authorization could not be confirmed", ex);
            }
        }

        try {
            paymentProvider.capture(idempotencyKey + ":capture", payment.getProviderReference());
        } catch (RuntimeException ex) {
            payment.setFailureCode("CAPTURE_UNKNOWN");
            paymentRepository.saveAndFlush(payment);
            throw new PaymentDependencyException("Payment capture could not be confirmed", ex);
        }

        try {
            orderClient.markPaid(payment.getOrderId(), payment.getId());
        } catch (RuntimeException orderFailure) {
            compensateCapturedPayment(payment, idempotencyKey, orderFailure);
            throw new PaymentDependencyException("Captured payment could not be linked to its order", orderFailure);
        }

        payment.setStatus(PaymentStatus.CAPTURED);
        payment.setFailureCode(null);
        return paymentMapper.toResponse(
                checkpointService.saveTerminal(payment, "PAYMENT_CAPTURED"));
    }

    @Observed(name = "platform.payments.refund", contextualName = "refund-payment")
    public PaymentResponse refund(UUID paymentId, UUID userId) {
        PaymentTransaction payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        if (!userId.equals(payment.getUserId())) {
            throw new PaymentNotFoundException(paymentId);
        }
        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            if (ORDER_SYNC_PENDING.equals(payment.getFailureCode())) {
                syncRefundedOrder(payment);
            }
            return paymentMapper.toResponse(payment);
        }
        if (payment.getStatus() != PaymentStatus.CAPTURED || payment.getProviderReference() == null) {
            throw new InvalidPaymentStateException(payment.getStatus(), PaymentStatus.REFUNDED);
        }

        try {
            paymentProvider.refund("refund:" + payment.getId(), payment.getProviderReference());
        } catch (RuntimeException ex) {
            payment.setFailureCode("REFUND_UNKNOWN");
            paymentRepository.saveAndFlush(payment);
            throw new PaymentDependencyException("Payment refund could not be confirmed", ex);
        }

        payment.setStatus(PaymentStatus.REFUNDED);
        payment.setActiveOrderId(null);
        RuntimeException syncFailure = null;
        try {
            orderClient.markRefunded(payment.getOrderId(), payment.getId());
            payment.setFailureCode(null);
        } catch (RuntimeException ex) {
            payment.setFailureCode(ORDER_SYNC_PENDING);
            syncFailure = ex;
        }
        PaymentTransaction saved = checkpointService.saveTerminal(payment, "PAYMENT_REFUNDED");
        if (syncFailure != null) {
            throw new PaymentDependencyException(
                    "Refunded payment could not update its order", syncFailure);
        }
        return paymentMapper.toResponse(saved);
    }

    private void syncRefundedOrder(PaymentTransaction payment) {
        try {
            orderClient.markRefunded(payment.getOrderId(), payment.getId());
            payment.setFailureCode(null);
            paymentRepository.saveAndFlush(payment);
        } catch (RuntimeException ex) {
            throw new PaymentDependencyException("Refunded payment could not update its order", ex);
        }
    }

    private PaymentResponse terminalReplay(PaymentTransaction payment) {
        if (payment.getStatus() == PaymentStatus.CAPTURED
                && ORDER_SYNC_PENDING.equals(payment.getFailureCode())) {
            syncCapturedOrder(payment);
        } else if (payment.getStatus() == PaymentStatus.REFUNDED
                && ORDER_SYNC_PENDING.equals(payment.getFailureCode())) {
            syncRefundedOrder(payment);
        }
        if (payment.getStatus() == PaymentStatus.CAPTURED
                || payment.getStatus() == PaymentStatus.FAILED
                || payment.getStatus() == PaymentStatus.REFUNDED) {
            return paymentMapper.toResponse(payment);
        }
        return null;
    }

    private void syncCapturedOrder(PaymentTransaction payment) {
        try {
            orderClient.markPaid(payment.getOrderId(), payment.getId());
            payment.setFailureCode(null);
            paymentRepository.saveAndFlush(payment);
        } catch (RuntimeException ex) {
            throw new PaymentDependencyException("Captured payment could not update its order", ex);
        }
    }

    private void compensateCapturedPayment(PaymentTransaction payment,
                                           String idempotencyKey,
                                           RuntimeException original) {
        try {
            paymentProvider.refund(idempotencyKey + ":compensating-refund",
                    payment.getProviderReference());
            payment.setStatus(PaymentStatus.REFUNDED);
            payment.setActiveOrderId(null);
            try {
                orderClient.markRefunded(payment.getOrderId(), payment.getId());
                payment.setFailureCode(null);
            } catch (RuntimeException syncFailure) {
                payment.setFailureCode(ORDER_SYNC_PENDING);
                original.addSuppressed(syncFailure);
            }
        } catch (RuntimeException refundFailure) {
            payment.setStatus(PaymentStatus.CAPTURED);
            payment.setFailureCode(ORDER_SYNC_PENDING);
            original.addSuppressed(refundFailure);
        }
        String eventType = payment.getStatus() == PaymentStatus.REFUNDED
                ? "PAYMENT_REFUNDED" : "PAYMENT_CAPTURED";
        checkpointService.saveTerminal(payment, eventType);
    }

    private void validateReplay(PaymentTransaction payment, UUID userId, String fingerprint) {
        if (!userId.equals(payment.getUserId()) || !fingerprint.equals(payment.getRequestFingerprint())) {
            throw new PaymentIdempotencyException();
        }
    }

    private void validateOrder(OrderSnapshot order, UUID userId, UUID requestedOrderId) {
        if (order == null || !requestedOrderId.equals(order.id()) || !userId.equals(order.userId())) {
            throw new PaymentRejectedException("Order does not belong to the authenticated user");
        }
        if (!"PENDING".equals(order.status())) {
            throw new PaymentRejectedException("Only a pending order can be paid");
        }
        if (order.totalAmount() == null || order.totalAmount().signum() <= 0
                || order.currency() == null || !order.currency().matches("[A-Z]{3}")) {
            throw new PaymentRejectedException("Order has invalid payment details");
        }
    }

    private String fingerprint(UUID userId, ProcessPaymentRequest request) {
        return sha256(userId + ":" + request.orderId() + ":" + sha256(request.paymentMethodToken()));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
