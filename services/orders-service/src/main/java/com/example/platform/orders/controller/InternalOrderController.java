package com.example.platform.orders.controller;

import com.example.platform.orders.dto.OrderResponse;
import com.example.platform.orders.dto.PaymentEventRequest;
import com.example.platform.orders.service.CheckoutService;
import com.example.platform.orders.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Private-network contract used by Payments; the gateway does not route this prefix. */
@RestController
@RequestMapping("/internal/v1/orders")
public class InternalOrderController {

    private final OrderService orderService;
    private final CheckoutService checkoutService;

    public InternalOrderController(OrderService orderService, CheckoutService checkoutService) {
        this.orderService = orderService;
        this.checkoutService = checkoutService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> find(@PathVariable UUID id) {
        return ResponseEntity.ok(orderService.findById(id));
    }

    @PostMapping("/{id}/paid")
    public ResponseEntity<Void> paid(@PathVariable UUID id,
                                     @Valid @RequestBody PaymentEventRequest request) {
        checkoutService.markPaid(id, request.paymentId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/refunded")
    public ResponseEntity<Void> refunded(@PathVariable UUID id,
                                         @Valid @RequestBody PaymentEventRequest request) {
        checkoutService.markRefunded(id, request.paymentId());
        return ResponseEntity.noContent().build();
    }
}
