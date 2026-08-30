package com.example.platform.payments.provider;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentProvider {

    String name();

    String authorize(String idempotencyKey, UUID orderId, BigDecimal amount,
                     String currency, String paymentMethodToken);

    void capture(String idempotencyKey, String providerReference);

    void refund(String idempotencyKey, String providerReference);
}
