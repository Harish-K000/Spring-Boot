package com.example.platform.payments.service;

import com.example.platform.payments.model.PaymentTransaction;
import com.example.platform.payments.outbox.OutboxEventRecorder;
import com.example.platform.payments.repository.PaymentTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/** Commits a terminal payment checkpoint and its domain event atomically. */
@Service
public class PaymentCheckpointService {

    private final PaymentTransactionRepository paymentRepository;
    private final OutboxEventRecorder outbox;

    public PaymentCheckpointService(
            PaymentTransactionRepository paymentRepository, OutboxEventRecorder outbox) {
        this.paymentRepository = paymentRepository;
        this.outbox = outbox;
    }

    @Transactional
    public PaymentTransaction saveTerminal(PaymentTransaction payment, String eventType) {
        PaymentTransaction saved = paymentRepository.saveAndFlush(payment);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentId", saved.getId());
        payload.put("orderId", saved.getOrderId());
        payload.put("userId", saved.getUserId());
        payload.put("status", saved.getStatus().name());
        payload.put("amount", saved.getAmount());
        payload.put("currency", saved.getCurrency());
        payload.put("provider", saved.getProvider());
        if (saved.getProviderReference() != null) {
            payload.put("providerReference", saved.getProviderReference());
        }
        if (saved.getFailureCode() != null) {
            payload.put("failureCode", saved.getFailureCode());
        }
        outbox.record(saved.getId(), eventType, payload);
        return saved;
    }
}
