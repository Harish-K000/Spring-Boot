package com.example.platform.payments.controller;

import com.example.platform.payments.dto.PageResponse;
import com.example.platform.payments.dto.PaymentResponse;
import com.example.platform.payments.dto.ProcessPaymentRequest;
import com.example.platform.payments.model.PaymentStatus;
import com.example.platform.payments.service.PaymentService;
import com.example.platform.payments.service.PaymentProcessingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.util.UUID;

/**
 * Payments are never deleted: a transaction is an audit record, so the lifecycle ends in a
 * terminal status (FAILED or REFUNDED) rather than a DELETE.
 */
@RestController
@RequestMapping("/api/v1/payments")
@Validated
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentProcessingService paymentProcessingService;

    public PaymentController(PaymentService paymentService,
                             PaymentProcessingService paymentProcessingService) {
        this.paymentService = paymentService;
        this.paymentProcessingService = paymentProcessingService;
    }

    @PostMapping("/process")
    public ResponseEntity<PaymentResponse> process(
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("Idempotency-Key")
            @Size(max = 100, message = "Idempotency-Key must be at most 100 characters")
            @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "Idempotency-Key contains invalid characters")
            String idempotencyKey,
            @Valid @RequestBody ProcessPaymentRequest request,
            UriComponentsBuilder uriBuilder) {
        PaymentResponse payment = paymentProcessingService.process(userId, idempotencyKey, request);
        URI location = uriBuilder.path("/api/v1/payments/{id}").buildAndExpand(payment.id()).toUri();
        HttpStatus status = payment.status() == PaymentStatus.FAILED
                ? HttpStatus.PAYMENT_REQUIRED : HttpStatus.CREATED;
        return ResponseEntity.status(status).location(location).body(payment);
    }

    @GetMapping("/{id}")
    public ResponseEntity<PaymentResponse> findById(@PathVariable UUID id,
                                                    @RequestHeader("X-User-Id") UUID userId) {
        return ResponseEntity.ok(paymentService.findById(id, userId));
    }

    @GetMapping
    public ResponseEntity<PageResponse<PaymentResponse>> findAll(
            @RequestHeader("X-User-Id") UUID userId,
            @RequestParam(required = false) UUID orderId,
            @RequestParam(required = false) PaymentStatus status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(paymentService.findAllForUser(userId, orderId, status, pageable));
    }

    @PostMapping("/{id}/refund")
    public ResponseEntity<PaymentResponse> refund(@PathVariable UUID id,
                                                  @RequestHeader("X-User-Id") UUID userId) {
        return ResponseEntity.ok(paymentProcessingService.refund(id, userId));
    }

}
