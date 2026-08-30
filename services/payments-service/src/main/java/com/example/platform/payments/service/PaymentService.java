package com.example.platform.payments.service;

import com.example.platform.payments.dto.CreatePaymentRequest;
import com.example.platform.payments.dto.PageResponse;
import com.example.platform.payments.dto.PaymentResponse;
import com.example.platform.payments.exception.DuplicatePaymentException;
import com.example.platform.payments.exception.InvalidPaymentStateException;
import com.example.platform.payments.exception.ManagedPaymentException;
import com.example.platform.payments.exception.PaymentNotFoundException;
import com.example.platform.payments.mapper.PaymentMapper;
import com.example.platform.payments.model.PaymentStatus;
import com.example.platform.payments.model.PaymentTransaction;
import com.example.platform.payments.repository.PaymentTransactionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class PaymentService {

    /** A second payment is only allowed once every earlier attempt has failed or been refunded. */
    private static final List<PaymentStatus> ACTIVE_STATUSES =
            List.of(PaymentStatus.PENDING, PaymentStatus.AUTHORIZED, PaymentStatus.CAPTURED);

    private final PaymentTransactionRepository paymentRepository;
    private final PaymentMapper paymentMapper;

    public PaymentService(PaymentTransactionRepository paymentRepository, PaymentMapper paymentMapper) {
        this.paymentRepository = paymentRepository;
        this.paymentMapper = paymentMapper;
    }

    @Transactional
    public PaymentResponse create(CreatePaymentRequest req) {
        if (paymentRepository.existsByOrderIdAndStatusIn(req.orderId(), ACTIVE_STATUSES)) {
            throw new DuplicatePaymentException(req.orderId());
        }
        PaymentTransaction payment = paymentMapper.toEntity(req);
        payment.setActiveOrderId(req.orderId());
        payment.setStatus(PaymentStatus.PENDING);
        return paymentMapper.toResponse(paymentRepository.save(payment));
    }

    public PaymentResponse findById(UUID id) {
        return paymentMapper.toResponse(getOrThrow(id));
    }

    public PaymentResponse findById(UUID id, UUID userId) {
        PaymentTransaction payment = getOrThrow(id);
        if (payment.getUserId() == null || !payment.getUserId().equals(userId)) {
            throw new PaymentNotFoundException(id);
        }
        return paymentMapper.toResponse(payment);
    }

    public PageResponse<PaymentResponse> findAllForUser(
            UUID userId, UUID orderId, PaymentStatus status, Pageable pageable) {
        Page<PaymentTransaction> page;
        if (orderId != null && status != null) {
            page = paymentRepository.findByUserIdAndOrderIdAndStatus(userId, orderId, status, pageable);
        } else if (orderId != null) {
            page = paymentRepository.findByUserIdAndOrderId(userId, orderId, pageable);
        } else if (status != null) {
            page = paymentRepository.findByUserIdAndStatus(userId, status, pageable);
        } else {
            page = paymentRepository.findByUserId(userId, pageable);
        }
        return PageResponse.from(page, paymentMapper::toResponse);
    }

    public PageResponse<PaymentResponse> findAll(UUID orderId, PaymentStatus status, Pageable pageable) {
        Page<PaymentTransaction> page;
        if (orderId != null && status != null) {
            page = paymentRepository.findByOrderIdAndStatus(orderId, status, pageable);
        } else if (orderId != null) {
            page = paymentRepository.findByOrderId(orderId, pageable);
        } else if (status != null) {
            page = paymentRepository.findByStatus(status, pageable);
        } else {
            page = paymentRepository.findAll(pageable);
        }
        return PageResponse.from(page, paymentMapper::toResponse);
    }

    @Transactional
    public PaymentResponse authorize(UUID id) {
        return transition(id, PaymentStatus.AUTHORIZED);
    }

    @Transactional
    public PaymentResponse capture(UUID id) {
        return transition(id, PaymentStatus.CAPTURED);
    }

    @Transactional
    public PaymentResponse refund(UUID id) {
        return transition(id, PaymentStatus.REFUNDED);
    }

    @Transactional
    public PaymentResponse fail(UUID id) {
        return transition(id, PaymentStatus.FAILED);
    }

    /**
     * Applies a status change if the lifecycle permits it. In a real integration this is also
     * where the provider would be called; the result of that call decides the target status.
     */
    private PaymentResponse transition(UUID id, PaymentStatus target) {
        PaymentTransaction payment = getOrThrow(id);
        if (payment.getIdempotencyKey() != null) {
            throw new ManagedPaymentException(
                    "Processed payments must use provider-backed lifecycle endpoints");
        }
        if (!payment.getStatus().canTransitionTo(target)) {
            throw new InvalidPaymentStateException(payment.getStatus(), target);
        }
        payment.setStatus(target);
        if (target == PaymentStatus.FAILED || target == PaymentStatus.REFUNDED) {
            payment.setActiveOrderId(null);
        }
        return paymentMapper.toResponse(paymentRepository.save(payment));
    }

    private PaymentTransaction getOrThrow(UUID id) {
        return paymentRepository.findById(id).orElseThrow(() -> new PaymentNotFoundException(id));
    }
}
