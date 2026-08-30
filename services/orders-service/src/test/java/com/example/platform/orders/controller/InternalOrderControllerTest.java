package com.example.platform.orders.controller;

import com.example.platform.orders.dto.OrderResponse;
import com.example.platform.orders.model.OrderStatus;
import com.example.platform.orders.service.CheckoutService;
import com.example.platform.orders.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InternalOrderController.class)
@AutoConfigureMockMvc(addFilters = false)
class InternalOrderControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private OrderService orderService;
    @MockitoBean private CheckoutService checkoutService;

    @Test
    void exposesOrderPaymentSnapshotOnlyOnInternalPrefix() throws Exception {
        UUID orderId = UUID.randomUUID();
        given(orderService.findById(orderId)).willReturn(new OrderResponse(
                orderId, UUID.randomUUID(), OrderStatus.PENDING, new BigDecimal("25.00"),
                "USD", Instant.now(), Instant.now()));

        mockMvc.perform(get("/internal/v1/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmount").value(25.00))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void paidCallbackPassesPaymentIdentity() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        mockMvc.perform(post("/internal/v1/orders/{id}/paid", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentId\":\"" + paymentId + "\"}"))
                .andExpect(status().isNoContent());

        verify(checkoutService).markPaid(orderId, paymentId);
    }
}
