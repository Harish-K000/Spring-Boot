package com.example.platform.payments.client;

import com.example.platform.payments.exception.PaymentRejectedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class HttpOrderClientTest {

    private MockRestServiceServer server;
    private HttpOrderClient orderClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://orders");
        server = MockRestServiceServer.bindTo(builder).build();
        OrderReliabilityGuard reliability = mock(OrderReliabilityGuard.class);
        given(reliability.execute(any())).willAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        orderClient = new HttpOrderClient(builder.build(), reliability);
    }

    @Test
    void readsAuthoritativeOrderPaymentDetails() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        server.expect(requestTo("http://orders/internal/v1/orders/" + orderId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id":"%s","userId":"%s","status":"PENDING",
                         "totalAmount":42.50,"currency":"CAD","items":[]}
                        """.formatted(orderId, userId), MediaType.APPLICATION_JSON));

        OrderClient.OrderSnapshot order = orderClient.findOrder(orderId);

        assertThat(order.userId()).isEqualTo(userId);
        assertThat(order.totalAmount()).isEqualByComparingTo("42.50");
    }

    @Test
    void sendsPaidCallbackContract() {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        server.expect(requestTo("http://orders/internal/v1/orders/" + orderId + "/paid"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"paymentId\":\"" + paymentId + "\"}"))
                .andRespond(withNoContent());

        orderClient.markPaid(orderId, paymentId);

        server.verify();
    }

    @Test
    void translatesRejectedOrderCallback() {
        UUID orderId = UUID.randomUUID();
        server.expect(requestTo("http://orders/internal/v1/orders/" + orderId + "/refunded"))
                .andRespond(withBadRequest());

        assertThatThrownBy(() -> orderClient.markRefunded(orderId, UUID.randomUUID()))
                .isInstanceOf(PaymentRejectedException.class);
    }
}
