package com.example.platform.orders.config;

import com.example.platform.orders.controller.InternalOrderController;
import com.example.platform.orders.controller.OrderController;
import com.example.platform.orders.service.CheckoutService;
import com.example.platform.orders.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({OrderController.class, InternalOrderController.class})
@Import(SecurityConfig.class)
class OrderSecurityIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private OrderService orderService;
    @MockitoBean private CheckoutService checkoutService;

    @Test
    void directCheckoutWithoutTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/orders/checkout"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedBearerTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/orders/" + UUID.randomUUID())
                        .header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userCannotBypassAdminOrderReadByCallingServiceDirectly() throws Exception {
        mockMvc.perform(get("/api/v1/orders/" + UUID.randomUUID())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void internalOrdersContractAlsoRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/internal/v1/orders/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
