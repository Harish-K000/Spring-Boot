package com.example.platform.payments.controller;

import com.example.platform.payments.dto.PageResponse;
import com.example.platform.payments.dto.PaymentResponse;
import com.example.platform.payments.exception.PaymentNotFoundException;
import com.example.platform.payments.model.PaymentStatus;
import com.example.platform.payments.service.PaymentService;
import com.example.platform.payments.service.PaymentProcessingService;
import com.example.platform.payments.dto.ProcessPaymentRequest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PaymentController.class)
@AutoConfigureMockMvc(addFilters = false)
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    @MockitoBean
    private PaymentProcessingService paymentProcessingService;

    private PaymentResponse sample(UUID id, PaymentStatus status) {
        return new PaymentResponse(id, UUID.randomUUID(), new BigDecimal("20.00"), "USD",
                "stripe", status, Instant.now(), Instant.now());
    }

    @Test
    void processUsesTrustedIdentityAndReturnsCapturedPayment() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        given(paymentProcessingService.process(eq(userId), eq("payment-key"),
                any(ProcessPaymentRequest.class)))
                .willReturn(sample(paymentId, PaymentStatus.CAPTURED));

        mockMvc.perform(post("/api/v1/payments/process")
                        .header("X-User-Id", userId)
                        .header("Idempotency-Key", "payment-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + orderId
                                + "\",\"paymentMethodToken\":\"tok_success\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", Matchers.endsWith("/api/v1/payments/" + paymentId)))
                .andExpect(jsonPath("$.status").value("CAPTURED"));
    }

    @Test
    void declinedProcessReturns402WithPersistedPayment() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        given(paymentProcessingService.process(eq(userId), eq("decline-key"),
                any(ProcessPaymentRequest.class)))
                .willReturn(sample(UUID.randomUUID(), PaymentStatus.FAILED));

        mockMvc.perform(post("/api/v1/payments/process")
                        .header("X-User-Id", userId)
                        .header("Idempotency-Key", "decline-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + orderId
                                + "\",\"paymentMethodToken\":\"tok_declined\"}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void processRequiresGatewayIdentityAndIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/payments/process")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + UUID.randomUUID()
                                + "\",\"paymentMethodToken\":\"tok_success\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void processWithMissingTokenReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/payments/process")
                        .header("X-User-Id", UUID.randomUUID())
                        .header("Idempotency-Key", "validation-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'paymentMethodToken')]").exists());
    }

    @Test
    void findByIdReturns404WhenMissing() throws Exception {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        given(paymentService.findById(id, userId)).willThrow(new PaymentNotFoundException(id));

        mockMvc.perform(get("/api/v1/payments/{id}", id)
                        .header("X-User-Id", userId))
                .andExpect(status().isNotFound());
    }

    @Test
    void findAllFiltersByOrderIdAndStatus() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        given(paymentService.findAllForUser(eq(userId), eq(orderId), eq(PaymentStatus.CAPTURED),
                any(Pageable.class)))
                .willReturn(new PageResponse<>(
                        List.of(sample(UUID.randomUUID(), PaymentStatus.CAPTURED)), 0, 20, 1, 1, true));

        mockMvc.perform(get("/api/v1/payments")
                        .header("X-User-Id", userId)
                        .param("orderId", orderId.toString())
                        .param("status", "CAPTURED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.content[0].status").value("CAPTURED"));
    }

    @Test
    void refundReturns200() throws Exception {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        given(paymentProcessingService.refund(id, userId)).willReturn(sample(id, PaymentStatus.REFUNDED));

        mockMvc.perform(post("/api/v1/payments/{id}/refund", id)
                        .header("X-User-Id", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));
    }

    @Test
    void unknownStatusFilterReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/payments")
                        .header("X-User-Id", UUID.randomUUID())
                        .param("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest());
    }
}
