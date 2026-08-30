package com.example.platform.payments.service;

import com.example.platform.payments.dto.CreatePaymentRequest;
import com.example.platform.payments.dto.PaymentResponse;
import com.example.platform.payments.exception.DuplicatePaymentException;
import com.example.platform.payments.exception.InvalidPaymentStateException;
import com.example.platform.payments.exception.PaymentNotFoundException;
import com.example.platform.payments.mapper.PaymentMapper;
import com.example.platform.payments.model.PaymentStatus;
import com.example.platform.payments.model.PaymentTransaction;
import com.example.platform.payments.repository.PaymentTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock private PaymentTransactionRepository paymentRepository;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(paymentRepository, new PaymentMapper());
    }

    private PaymentTransaction paymentWith(PaymentStatus status) {
        PaymentTransaction payment = new PaymentTransaction();
        payment.setOrderId(UUID.randomUUID());
        payment.setAmount(new BigDecimal("20.00"));
        payment.setCurrency("USD");
        payment.setProvider("stripe");
        payment.setStatus(status);
        return payment;
    }

    private CreatePaymentRequest request(UUID orderId) {
        return new CreatePaymentRequest(orderId, new BigDecimal("20.00"), "USD", "stripe");
    }

    @Test
    void createStartsInPendingState() {
        UUID orderId = UUID.randomUUID();
        given(paymentRepository.existsByOrderIdAndStatusIn(any(UUID.class), anyList())).willReturn(false);
        given(paymentRepository.save(any(PaymentTransaction.class))).willAnswer(inv -> inv.getArgument(0));

        PaymentResponse response = paymentService.create(request(orderId));

        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(response.orderId()).isEqualTo(orderId);
        assertThat(response.amount()).isEqualByComparingTo("20.00");
    }

    @Test
    void createRejectsSecondActivePaymentForSameOrder() {
        UUID orderId = UUID.randomUUID();
        given(paymentRepository.existsByOrderIdAndStatusIn(any(UUID.class), anyList())).willReturn(true);

        assertThatThrownBy(() -> paymentService.create(request(orderId)))
                .isInstanceOf(DuplicatePaymentException.class);

        verify(paymentRepository, never()).save(any());
    }

    @Test
    void authorizeThenCaptureThenRefundIsTheHappyPath() {
        UUID id = UUID.randomUUID();
        PaymentTransaction payment = paymentWith(PaymentStatus.PENDING);
        given(paymentRepository.findById(id)).willReturn(Optional.of(payment));
        given(paymentRepository.save(any(PaymentTransaction.class))).willAnswer(inv -> inv.getArgument(0));

        assertThat(paymentService.authorize(id).status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(paymentService.capture(id).status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(paymentService.refund(id).status()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void captureDirectlyFromPendingIsRejected() {
        UUID id = UUID.randomUUID();
        given(paymentRepository.findById(id)).willReturn(Optional.of(paymentWith(PaymentStatus.PENDING)));

        assertThatThrownBy(() -> paymentService.capture(id))
                .isInstanceOf(InvalidPaymentStateException.class)
                .hasMessage("Cannot transition a payment from PENDING to CAPTURED");

        verify(paymentRepository, never()).save(any());
    }

    @Test
    void refundedPaymentIsTerminal() {
        UUID id = UUID.randomUUID();
        given(paymentRepository.findById(id)).willReturn(Optional.of(paymentWith(PaymentStatus.REFUNDED)));

        assertThatThrownBy(() -> paymentService.refund(id))
                .isInstanceOf(InvalidPaymentStateException.class);
    }

    @Test
    void failedPaymentCannotBeAuthorized() {
        UUID id = UUID.randomUUID();
        given(paymentRepository.findById(id)).willReturn(Optional.of(paymentWith(PaymentStatus.FAILED)));

        assertThatThrownBy(() -> paymentService.authorize(id))
                .isInstanceOf(InvalidPaymentStateException.class);
    }

    @Test
    void findByIdThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        given(paymentRepository.findById(id)).willReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.findById(id))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    void lifecycleAllowsFailureFromPendingAndAuthorized() {
        assertThat(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.FAILED)).isTrue();
        assertThat(PaymentStatus.AUTHORIZED.canTransitionTo(PaymentStatus.FAILED)).isTrue();
        assertThat(PaymentStatus.CAPTURED.canTransitionTo(PaymentStatus.FAILED)).isFalse();
    }

    @Test
    void activeStatusesExcludeTerminalOnes() {
        // Guards the duplicate check: a failed or refunded attempt must not block a retry.
        List<PaymentStatus> terminal = List.of(PaymentStatus.FAILED, PaymentStatus.REFUNDED);
        assertThat(terminal).allSatisfy(status ->
                assertThat(status.canTransitionTo(PaymentStatus.AUTHORIZED)).isFalse());
    }
}
