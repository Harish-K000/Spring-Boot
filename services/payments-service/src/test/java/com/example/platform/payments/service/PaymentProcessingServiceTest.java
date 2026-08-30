package com.example.platform.payments.service;

import com.example.platform.payments.client.OrderClient;
import com.example.platform.payments.client.OrderClient.OrderSnapshot;
import com.example.platform.payments.dto.PaymentResponse;
import com.example.platform.payments.dto.ProcessPaymentRequest;
import com.example.platform.payments.exception.PaymentDependencyException;
import com.example.platform.payments.exception.PaymentIdempotencyException;
import com.example.platform.payments.exception.PaymentNotFoundException;
import com.example.platform.payments.exception.PaymentRejectedException;
import com.example.platform.payments.mapper.PaymentMapper;
import com.example.platform.payments.model.PaymentStatus;
import com.example.platform.payments.model.PaymentTransaction;
import com.example.platform.payments.provider.PaymentDeclinedException;
import com.example.platform.payments.provider.PaymentProvider;
import com.example.platform.payments.repository.PaymentTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentProcessingServiceTest {

    @Mock private PaymentTransactionRepository paymentRepository;
    @Mock private OrderClient orderClient;
    @Mock private PaymentProvider paymentProvider;
    @Mock private PaymentCheckpointService checkpointService;

    private PaymentProcessingService service;

    @BeforeEach
    void setUp() {
        service = new PaymentProcessingService(
                paymentRepository, new PaymentMapper(), orderClient, paymentProvider,
                checkpointService);
        lenient().when(paymentProvider.name()).thenReturn("provider");
        lenient().when(checkpointService.saveTerminal(any(PaymentTransaction.class), anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void processUsesOrderAmountThenAuthorizesCapturesAndMarksPaid() {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        given(paymentRepository.findByIdempotencyKey("key")).willReturn(Optional.empty());
        given(paymentRepository.existsByOrderIdAndStatusIn(eq(orderId), anyList())).willReturn(false);
        given(orderClient.findOrder(orderId)).willReturn(order(orderId, userId));
        given(paymentRepository.saveAndFlush(any(PaymentTransaction.class)))
                .willAnswer(inv -> inv.getArgument(0));
        given(paymentProvider.authorize(anyString(), eq(orderId),
                eq(new BigDecimal("25.00")), eq("USD"), eq("tok")))
                .willReturn("provider-ref");

        PaymentResponse response = service.process(userId, "key", request(orderId, "tok"));

        assertThat(response.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(response.amount()).isEqualByComparingTo("25.00");
        assertThat(response.providerReference()).isEqualTo("provider-ref");
        InOrder flow = inOrder(paymentProvider, orderClient);
        flow.verify(paymentProvider).authorize(anyString(), eq(orderId), any(), eq("USD"), eq("tok"));
        flow.verify(paymentProvider).capture(anyString(), eq("provider-ref"));
        flow.verify(orderClient).markPaid(orderId, response.id());
    }

    @Test
    void declineIsPersistedAndClearsActiveOrder() {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        stubNewPayment(orderId, userId);
        given(paymentProvider.authorize(anyString(), any(), any(), anyString(), anyString()))
                .willThrow(new PaymentDeclinedException("CARD_DECLINED", "declined"));

        PaymentResponse response = service.process(userId, "key", request(orderId, "tok"));

        assertThat(response.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(response.failureCode()).isEqualTo("CARD_DECLINED");
        verify(paymentProvider, never()).capture(anyString(), anyString());
    }

    @Test
    void idempotentCapturedReplayDoesNotCallDependencies() {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        PaymentTransaction existing = processedPayment(userId, orderId, PaymentStatus.CAPTURED);
        // Generate the fingerprint by completing one request first, then replay its stored record.
        stubNewPayment(orderId, userId);
        given(paymentProvider.authorize(anyString(), any(), any(), anyString(), anyString()))
                .willReturn("ref");
        service.process(userId, "key", request(orderId, "tok"));
        PaymentTransaction saved = mockingDetails(paymentRepository).getInvocations().stream()
                .filter(inv -> inv.getMethod().getName().equals("saveAndFlush"))
                .map(inv -> (PaymentTransaction) inv.getArgument(0))
                .findFirst().orElseThrow();
        existing.setRequestFingerprint(saved.getRequestFingerprint());
        clearInvocations(orderClient, paymentProvider, paymentRepository);
        given(paymentRepository.findByIdempotencyKey("key")).willReturn(Optional.of(existing));

        PaymentResponse replay = service.process(userId, "key", request(orderId, "tok"));

        assertThat(replay.status()).isEqualTo(PaymentStatus.CAPTURED);
        verifyNoInteractions(orderClient, paymentProvider);
    }

    @Test
    void changedTokenWithSameKeyIsRejected() {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        PaymentTransaction existing = processedPayment(userId, orderId, PaymentStatus.CAPTURED);
        existing.setRequestFingerprint("different");
        given(paymentRepository.findByIdempotencyKey("key")).willReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.process(userId, "key", request(orderId, "other")))
                .isInstanceOf(PaymentIdempotencyException.class);
    }

    @Test
    void cannotPayAnotherUsersOrder() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        given(paymentRepository.findByIdempotencyKey("key")).willReturn(Optional.empty());
        given(orderClient.findOrder(orderId)).willReturn(order(orderId, UUID.randomUUID()));

        assertThatThrownBy(() -> service.process(userId, "key", request(orderId, "tok")))
                .isInstanceOf(PaymentRejectedException.class);

        verify(paymentRepository, never()).saveAndFlush(any());
    }

    @Test
    void failedOrderLinkRefundsCapturedProviderPayment() {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        stubNewPayment(orderId, userId);
        given(paymentProvider.authorize(anyString(), any(), any(), anyString(), anyString()))
                .willReturn("ref");
        doThrow(new PaymentDependencyException("orders down", new RuntimeException()))
                .when(orderClient).markPaid(eq(orderId), any(UUID.class));

        assertThatThrownBy(() -> service.process(userId, "key", request(orderId, "tok")))
                .isInstanceOf(PaymentDependencyException.class);

        verify(paymentProvider).refund(contains("compensating-refund"), eq("ref"));
        verify(orderClient).markRefunded(eq(orderId), any(UUID.class));
    }

    @Test
    void refundCallsProviderAndCancelsOrder() {
        UUID userId = UUID.randomUUID();
        PaymentTransaction payment = processedPayment(userId, UUID.randomUUID(), PaymentStatus.CAPTURED);
        given(paymentRepository.findById(payment.getId())).willReturn(Optional.of(payment));

        PaymentResponse response = service.refund(payment.getId(), userId);

        assertThat(response.status()).isEqualTo(PaymentStatus.REFUNDED);
        verify(paymentProvider).refund("refund:" + payment.getId(), "ref");
        verify(orderClient).markRefunded(payment.getOrderId(), payment.getId());
    }

    @Test
    void refundConcealsLegacyPaymentWithoutAnOwner() {
        UUID userId = UUID.randomUUID();
        PaymentTransaction payment = processedPayment(null, UUID.randomUUID(), PaymentStatus.CAPTURED);
        given(paymentRepository.findById(payment.getId())).willReturn(Optional.of(payment));

        assertThatThrownBy(() -> service.refund(payment.getId(), userId))
                .isInstanceOf(PaymentNotFoundException.class);

        verifyNoInteractions(paymentProvider, orderClient);
    }

    @Test
    void refundedReplayRetriesPendingOrderSync() {
        UUID userId = UUID.randomUUID();
        PaymentTransaction payment = processedPayment(userId, UUID.randomUUID(), PaymentStatus.REFUNDED);
        payment.setFailureCode(PaymentProcessingService.ORDER_SYNC_PENDING);
        given(paymentRepository.findById(payment.getId())).willReturn(Optional.of(payment));
        given(paymentRepository.saveAndFlush(payment)).willReturn(payment);

        PaymentResponse response = service.refund(payment.getId(), userId);

        assertThat(response.failureCode()).isNull();
        verify(orderClient).markRefunded(payment.getOrderId(), payment.getId());
        verify(paymentProvider, never()).refund(anyString(), anyString());
    }

    @Test
    void capturedReplayRetriesPendingOrderSyncWithoutChargingAgain() {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        PaymentTransaction payment = processedPayment(userId, orderId, PaymentStatus.CAPTURED);
        payment.setFailureCode(PaymentProcessingService.ORDER_SYNC_PENDING);

        // Obtain the exact opaque fingerprint generated for this request.
        stubNewPayment(orderId, userId);
        given(paymentProvider.authorize(anyString(), any(), any(), anyString(), anyString()))
                .willReturn("ref");
        service.process(userId, "key", request(orderId, "tok"));
        PaymentTransaction saved = mockingDetails(paymentRepository).getInvocations().stream()
                .filter(inv -> inv.getMethod().getName().equals("saveAndFlush"))
                .map(inv -> (PaymentTransaction) inv.getArgument(0))
                .findFirst().orElseThrow();
        payment.setRequestFingerprint(saved.getRequestFingerprint());

        clearInvocations(orderClient, paymentProvider, paymentRepository);
        given(paymentRepository.findByIdempotencyKey("key")).willReturn(Optional.of(payment));
        given(paymentRepository.saveAndFlush(payment)).willReturn(payment);

        PaymentResponse response = service.process(userId, "key", request(orderId, "tok"));

        assertThat(response.failureCode()).isNull();
        verify(orderClient).markPaid(orderId, payment.getId());
        verifyNoInteractions(paymentProvider);
    }

    @Test
    void identicalRequestThatLosesInsertRaceResumesExistingPendingPayment() {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        // First obtain the opaque fingerprint used by the service.
        stubNewPayment(orderId, userId);
        given(paymentProvider.authorize(anyString(), any(), any(), anyString(), anyString()))
                .willReturn("ref");
        service.process(userId, "key", request(orderId, "tok"));
        PaymentTransaction first = mockingDetails(paymentRepository).getInvocations().stream()
                .filter(inv -> inv.getMethod().getName().equals("saveAndFlush"))
                .map(inv -> (PaymentTransaction) inv.getArgument(0))
                .findFirst().orElseThrow();

        PaymentTransaction raced = processedPayment(userId, orderId, PaymentStatus.PENDING);
        raced.setRequestFingerprint(first.getRequestFingerprint());
        clearInvocations(orderClient, paymentProvider, paymentRepository);
        given(paymentRepository.findByIdempotencyKey("key"))
                .willReturn(Optional.empty(), Optional.of(raced));
        given(paymentRepository.existsByOrderIdAndStatusIn(eq(orderId), anyList())).willReturn(false);
        given(orderClient.findOrder(orderId)).willReturn(order(orderId, userId));
        given(paymentRepository.saveAndFlush(any(PaymentTransaction.class)))
                .willThrow(new DataIntegrityViolationException("idempotency race"))
                .willAnswer(inv -> inv.getArgument(0));
        given(paymentProvider.authorize(anyString(), any(), any(), anyString(), anyString()))
                .willReturn("ref");

        PaymentResponse response = service.process(userId, "key", request(orderId, "tok"));

        assertThat(response.id()).isEqualTo(raced.getId());
        assertThat(response.status()).isEqualTo(PaymentStatus.CAPTURED);
        verify(paymentProvider).capture(anyString(), eq("ref"));
        verify(orderClient).markPaid(orderId, raced.getId());
    }

    private void stubNewPayment(UUID orderId, UUID userId) {
        given(paymentRepository.findByIdempotencyKey("key")).willReturn(Optional.empty());
        given(paymentRepository.existsByOrderIdAndStatusIn(eq(orderId), anyList())).willReturn(false);
        given(orderClient.findOrder(orderId)).willReturn(order(orderId, userId));
        given(paymentRepository.saveAndFlush(any(PaymentTransaction.class)))
                .willAnswer(inv -> inv.getArgument(0));
    }

    private OrderSnapshot order(UUID orderId, UUID userId) {
        return new OrderSnapshot(orderId, userId, "PENDING", new BigDecimal("25.00"), "USD");
    }

    private ProcessPaymentRequest request(UUID orderId, String token) {
        return new ProcessPaymentRequest(orderId, token);
    }

    private PaymentTransaction processedPayment(UUID userId, UUID orderId, PaymentStatus status) {
        PaymentTransaction payment = new PaymentTransaction();
        payment.setUserId(userId);
        payment.setOrderId(orderId);
        payment.setActiveOrderId(status == PaymentStatus.CAPTURED ? orderId : null);
        payment.setAmount(new BigDecimal("25.00"));
        payment.setCurrency("USD");
        payment.setProvider("provider");
        payment.setProviderReference("ref");
        payment.setIdempotencyKey("key");
        payment.setStatus(status);
        return payment;
    }
}
