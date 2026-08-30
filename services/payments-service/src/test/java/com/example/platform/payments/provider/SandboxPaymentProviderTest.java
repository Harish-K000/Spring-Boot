package com.example.platform.payments.provider;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxPaymentProviderTest {

    private final SandboxPaymentProvider provider = new SandboxPaymentProvider();

    @Test
    void authorizationReferenceIsDeterministicForIdempotentRetries() {
        UUID orderId = UUID.randomUUID();

        String first = provider.authorize("key", orderId, BigDecimal.TEN, "USD", "tok_success");
        String replay = provider.authorize("key", orderId, BigDecimal.TEN, "USD", "tok_success");

        assertThat(replay).isEqualTo(first).startsWith("sandbox_");
    }

    @Test
    void declineTokenProducesSafeFailureCode() {
        assertThatThrownBy(() -> provider.authorize(
                "key", UUID.randomUUID(), BigDecimal.TEN, "USD", "tok_declined"))
                .isInstanceOf(PaymentDeclinedException.class)
                .extracting("code").isEqualTo("CARD_DECLINED");
    }
}
