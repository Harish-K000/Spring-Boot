package com.example.platform.payments.mapper;

import com.example.platform.payments.dto.CreatePaymentRequest;
import com.example.platform.payments.dto.PaymentResponse;
import com.example.platform.payments.model.PaymentTransaction;
import org.springframework.stereotype.Component;

@Component
public class PaymentMapper {

    public PaymentTransaction toEntity(CreatePaymentRequest req) {
        PaymentTransaction payment = new PaymentTransaction();
        payment.setOrderId(req.orderId());
        payment.setAmount(req.amount());
        payment.setCurrency(req.currency());
        payment.setProvider(req.provider());
        return payment;
    }

    public PaymentResponse toResponse(PaymentTransaction payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getOrderId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getProvider(),
                payment.getStatus(),
                payment.getProviderReference(),
                payment.getFailureCode(),
                payment.getCreatedAt(),
                payment.getUpdatedAt());
    }
}
