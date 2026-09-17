package com.example.platform.payments.config;

import com.example.platform.payments.controller.PaymentController;
import com.example.platform.payments.service.PaymentProcessingService;
import com.example.platform.payments.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
@Import(SecurityConfig.class)
class PaymentSecurityIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private PaymentService paymentService;
    @MockitoBean private PaymentProcessingService paymentProcessingService;

    @Test
    void directPaymentReadWithoutTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/payments"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedBearerTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/payments")
                        .header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenWithoutPlatformRoleIsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/payments").with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void userRolePassesSecurityBeforeRequestValidation() throws Exception {
        mockMvc.perform(get("/api/v1/payments/not-a-uuid")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isBadRequest());
    }
}
