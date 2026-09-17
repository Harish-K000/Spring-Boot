package com.example.platform.orders.controller;

import com.example.platform.orders.dto.CreateOrderRequest;
import com.example.platform.orders.dto.OrderResponse;
import com.example.platform.orders.dto.PageResponse;
import com.example.platform.orders.dto.UpdateOrderRequest;
import com.example.platform.orders.exception.InvalidOrderStateException;
import com.example.platform.orders.exception.OrderNotFoundException;
import com.example.platform.orders.model.OrderStatus;
import com.example.platform.orders.service.OrderService;
import com.example.platform.orders.service.CheckoutService;
import com.example.platform.orders.dto.CheckoutRequest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrderController.class)
@AutoConfigureMockMvc(addFilters = false)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private CheckoutService checkoutService;

    private OrderResponse sampleResponse(UUID id, OrderStatus status) {
        return new OrderResponse(id, UUID.randomUUID(), status,
                new BigDecimal("49.99"), "USD", Instant.now(), Instant.now());
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(UUID userId) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(userId.toString())
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @Test
    void createReturns201WithLocation() throws Exception {
        UUID id = UUID.randomUUID();
        given(orderService.create(any(CreateOrderRequest.class)))
                .willReturn(sampleResponse(id, OrderStatus.PENDING));

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + UUID.randomUUID()
                                + "\",\"totalAmount\":49.99,\"currency\":\"USD\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", Matchers.endsWith("/api/v1/orders/" + id)))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void checkoutUsesVerifiedJwtIdentityAndIgnoresSpoofedHeader() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        given(checkoutService.checkout(eq(userId), eq("checkout-123"), any(CheckoutRequest.class)))
                .willReturn(sampleResponse(orderId, OrderStatus.PENDING));
        authenticate(userId);

        mockMvc.perform(post("/api/v1/orders/checkout")
                        .header("X-User-Id", UUID.randomUUID())
                        .header("Idempotency-Key", "checkout-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":\"" + productId
                                + "\",\"quantity\":2}]}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", Matchers.endsWith("/api/v1/orders/" + orderId)));
    }

    @Test
    void checkoutRejectsMissingIdentityAndInvalidItems() throws Exception {
        mockMvc.perform(post("/api/v1/orders/checkout")
                        .header("Idempotency-Key", "checkout-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cancelUsesGatewayIdentity() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        given(checkoutService.cancel(orderId, userId))
                .willReturn(sampleResponse(orderId, OrderStatus.CANCELLED));
        authenticate(userId);

        mockMvc.perform(post("/api/v1/orders/{id}/cancel", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void createWithInvalidPayloadReturns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"totalAmount\":-5,\"currency\":\"usd\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'userId')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'totalAmount')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'currency')]").exists());
    }

    @Test
    void findByIdReturns404WhenMissing() throws Exception {
        UUID id = UUID.randomUUID();
        given(orderService.findById(id)).willThrow(new OrderNotFoundException(id));

        mockMvc.perform(get("/api/v1/orders/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("No order found with id " + id));
    }

    @Test
    void findByIdWithMalformedUuidReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/orders/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void findAllReturnsPageEnvelopeAndPassesFilters() throws Exception {
        UUID userId = UUID.randomUUID();
        given(orderService.findAll(eq(userId), eq(OrderStatus.PAID), any(Pageable.class)))
                .willReturn(new PageResponse<>(
                        List.of(sampleResponse(UUID.randomUUID(), OrderStatus.PAID)), 0, 20, 1, 1, true));

        mockMvc.perform(get("/api/v1/orders")
                        .param("userId", userId.toString())
                        .param("status", "PAID"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    void updateReturns200() throws Exception {
        UUID id = UUID.randomUUID();
        given(orderService.update(eq(id), any(UpdateOrderRequest.class)))
                .willReturn(sampleResponse(id, OrderStatus.PAID));

        mockMvc.perform(put("/api/v1/orders/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAID\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    void updateWithIllegalTransitionReturns409() throws Exception {
        UUID id = UUID.randomUUID();
        willThrow(new InvalidOrderStateException(OrderStatus.DELIVERED, OrderStatus.PENDING))
                .given(orderService).update(eq(id), any(UpdateOrderRequest.class));

        mockMvc.perform(put("/api/v1/orders/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PENDING\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Cannot transition an order from DELIVERED to PENDING"));
    }

    @Test
    void updateWithUnknownStatusReturns400() throws Exception {
        mockMvc.perform(put("/api/v1/orders/{id}", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"TELEPORTED\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteReturns204() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/orders/{id}", id))
                .andExpect(status().isNoContent());

        verify(orderService).delete(id);
    }
}
