package com.example.platform.payments.repository;

import com.example.platform.payments.model.PaymentStatus;
import com.example.platform.payments.model.PaymentTransaction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class PaymentTransactionRepositoryTest {

    @Autowired
    private PaymentTransactionRepository paymentRepository;

    private PaymentTransaction persist(UUID orderId, PaymentStatus status) {
        PaymentTransaction payment = new PaymentTransaction();
        payment.setOrderId(orderId);
        payment.setAmount(new BigDecimal("15.50"));
        payment.setCurrency("EUR");
        payment.setProvider("adyen");
        payment.setStatus(status);
        return paymentRepository.saveAndFlush(payment);
    }

    @Test
    void savedPaymentRoundTrips() {
        UUID orderId = UUID.randomUUID();
        PaymentTransaction saved = persist(orderId, PaymentStatus.PENDING);

        PaymentTransaction found = paymentRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getOrderId()).isEqualTo(orderId);
        assertThat(found.getAmount()).isEqualByComparingTo("15.50");
        assertThat(found.getCurrency()).isEqualTo("EUR");
        assertThat(found.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void findByOrderIdReturnsOnlyThatOrdersPayments() {
        UUID orderId = UUID.randomUUID();
        persist(orderId, PaymentStatus.FAILED);
        persist(orderId, PaymentStatus.CAPTURED);
        persist(UUID.randomUUID(), PaymentStatus.CAPTURED);

        assertThat(paymentRepository.findByOrderId(orderId, PageRequest.of(0, 10)).getTotalElements())
                .isEqualTo(2);
    }

    @Test
    void findByOrderIdAndStatusCombinesFilters() {
        UUID orderId = UUID.randomUUID();
        persist(orderId, PaymentStatus.FAILED);
        persist(orderId, PaymentStatus.CAPTURED);

        assertThat(paymentRepository.findByOrderIdAndStatus(
                orderId, PaymentStatus.CAPTURED, PageRequest.of(0, 10)).getTotalElements())
                .isEqualTo(1);
    }

    @Test
    void existsByOrderIdAndStatusInIgnoresTerminalAttempts() {
        UUID orderId = UUID.randomUUID();
        persist(orderId, PaymentStatus.FAILED);

        List<PaymentStatus> active =
                List.of(PaymentStatus.PENDING, PaymentStatus.AUTHORIZED, PaymentStatus.CAPTURED);

        // A failed attempt must not block a retry for the same order.
        assertThat(paymentRepository.existsByOrderIdAndStatusIn(orderId, active)).isFalse();

        persist(orderId, PaymentStatus.PENDING);
        assertThat(paymentRepository.existsByOrderIdAndStatusIn(orderId, active)).isTrue();
    }

    @Test
    void findByStatusFilters() {
        persist(UUID.randomUUID(), PaymentStatus.REFUNDED);
        persist(UUID.randomUUID(), PaymentStatus.PENDING);

        assertThat(paymentRepository.findByStatus(PaymentStatus.REFUNDED, PageRequest.of(0, 10))
                .getTotalElements()).isEqualTo(1);
    }
}
