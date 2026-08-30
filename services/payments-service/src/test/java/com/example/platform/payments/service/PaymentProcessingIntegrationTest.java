package com.example.platform.payments.service;

import com.example.platform.payments.client.OrderClient;
import com.example.platform.payments.client.OrderClient.OrderSnapshot;
import com.example.platform.payments.dto.PaymentResponse;
import com.example.platform.payments.dto.ProcessPaymentRequest;
import com.example.platform.payments.model.PaymentStatus;
import com.example.platform.payments.model.PaymentTransaction;
import com.example.platform.payments.provider.PaymentProvider;
import com.example.platform.payments.repository.PaymentTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
class PaymentProcessingIntegrationTest {

    @Autowired private PaymentProcessingService paymentProcessingService;
    @Autowired private PaymentTransactionRepository paymentRepository;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private OrderClient orderClient;
    @MockitoBean private PaymentProvider paymentProvider;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM outbox_events");
        paymentRepository.deleteAll();
    }

    @Test
    void persistsOneCapturedPaymentAndReplaysWithoutChargingAgain() {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String token = "tok_one_time_credential";
        ProcessPaymentRequest request = new ProcessPaymentRequest(orderId, token);

        given(paymentProvider.name()).willReturn("provider");
        given(orderClient.findOrder(orderId)).willReturn(new OrderSnapshot(
                orderId, userId, "PENDING", new BigDecimal("47.25"), "CAD"));
        given(paymentProvider.authorize(anyString(), eq(orderId), eq(new BigDecimal("47.25")),
                eq("CAD"), eq(token))).willReturn("provider-reference");

        PaymentResponse first = paymentProcessingService.process(userId, "checkout-payment-1", request);
        PaymentResponse replay = paymentProcessingService.process(userId, "checkout-payment-1", request);

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(paymentRepository.count()).isEqualTo(1);

        PaymentTransaction stored = paymentRepository.findById(first.id()).orElseThrow();
        assertThat(stored.getAmount()).isEqualByComparingTo("47.25");
        assertThat(stored.getCurrency()).isEqualTo("CAD");
        assertThat(stored.getRequestFingerprint()).hasSize(64).doesNotContain(token);
        assertThat(stored.getProviderReference()).isEqualTo("provider-reference");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events "
                        + "WHERE aggregate_id = ? AND event_type = 'PAYMENT_CAPTURED'",
                Long.class, first.id())).isEqualTo(1);

        verify(paymentProvider, times(1)).authorize(anyString(), eq(orderId), any(), eq("CAD"), eq(token));
        verify(paymentProvider, times(1)).capture(anyString(), eq("provider-reference"));
        verify(orderClient, times(1)).markPaid(orderId, first.id());
    }
}
