package com.example.platform.payments.provider;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Deterministic local provider. A token equal to {@code tok_declined} simulates a decline. */
@Component
@ConditionalOnProperty(name = "payments.provider", havingValue = "sandbox", matchIfMissing = true)
public class SandboxPaymentProvider implements PaymentProvider {

    @Override
    public String name() {
        return "sandbox";
    }

    @Override
    public String authorize(String idempotencyKey, UUID orderId, BigDecimal amount,
                            String currency, String paymentMethodToken) {
        if ("tok_declined".equals(paymentMethodToken)) {
            throw new PaymentDeclinedException("CARD_DECLINED", "Payment method was declined");
        }
        UUID reference = UUID.nameUUIDFromBytes(
                (idempotencyKey + ":" + orderId).getBytes(StandardCharsets.UTF_8));
        return "sandbox_" + reference;
    }

    @Override
    public void capture(String idempotencyKey, String providerReference) {
        // Deterministic no-op in the sandbox adapter.
    }

    @Override
    public void refund(String idempotencyKey, String providerReference) {
        // Deterministic no-op in the sandbox adapter.
    }
}
