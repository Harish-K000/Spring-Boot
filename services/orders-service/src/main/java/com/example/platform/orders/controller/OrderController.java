package com.example.platform.orders.controller;

import com.example.platform.orders.dto.CreateOrderRequest;
import com.example.platform.orders.dto.OrderResponse;
import com.example.platform.orders.dto.PageResponse;
import com.example.platform.orders.dto.UpdateOrderRequest;
import com.example.platform.orders.dto.CheckoutRequest;
import com.example.platform.orders.model.OrderStatus;
import com.example.platform.orders.service.CheckoutService;
import com.example.platform.orders.service.OrderService;
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

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
@Validated
public class OrderController {

    private final OrderService orderService;
    private final CheckoutService checkoutService;

    public OrderController(OrderService orderService, CheckoutService checkoutService) {
        this.orderService = orderService;
        this.checkoutService = checkoutService;
    }

    @PostMapping("/checkout")
    public ResponseEntity<OrderResponse> checkout(
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("Idempotency-Key")
            @Size(max = 100, message = "Idempotency-Key must be at most 100 characters")
            @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "Idempotency-Key contains invalid characters")
            String idempotencyKey,
            @Valid @RequestBody CheckoutRequest request,
            UriComponentsBuilder uriBuilder) {
        OrderResponse created = checkoutService.checkout(userId, idempotencyKey, request);
        URI location = uriBuilder.path("/api/v1/orders/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<OrderResponse> cancel(@PathVariable UUID id,
                                                @RequestHeader("X-User-Id") UUID userId) {
        return ResponseEntity.ok(checkoutService.cancel(id, userId));
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest req,
                                                UriComponentsBuilder uriBuilder) {
        OrderResponse created = orderService.create(req);
        URI location = uriBuilder.path("/api/v1/orders/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(orderService.findById(id));
    }

    @GetMapping
    public ResponseEntity<PageResponse<OrderResponse>> findAll(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) OrderStatus status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(orderService.findAll(userId, status, pageable));
    }

    @PutMapping("/{id}")
    public ResponseEntity<OrderResponse> update(@PathVariable UUID id,
                                                @Valid @RequestBody UpdateOrderRequest req) {
        return ResponseEntity.ok(orderService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        orderService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
