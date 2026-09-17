package com.example.platform.gateway.error;

import com.example.platform.gateway.filter.RequestCorrelationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.circuitbreaker.NoFallbackAvailableException;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayErrorWebExceptionHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final GatewayErrorWebExceptionHandler handler = new GatewayErrorWebExceptionHandler(
            new GatewayErrorResponseWriter(objectMapper));

    @Test
    void returnsStandardRouteNotFoundError() throws Exception {
        GatewayErrorResponse response = handle(
                new ResponseStatusException(HttpStatus.NOT_FOUND), "/missing");

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.code()).isEqualTo("ROUTE_NOT_FOUND");
        assertThat(response.message()).isEqualTo("No gateway route matches the request.");
        assertThat(response.path()).isEqualTo("/missing");
        assertThat(response.correlationId()).isEqualTo("error-test-id");
    }

    @Test
    void hidesDownstreamDiscoveryDetailsBehindServiceUnavailable() throws Exception {
        GatewayErrorResponse response = handle(
                new NotFoundException("No servers available for service: private-orders-host"),
                "/api/v1/orders/123");

        assertThat(response.status()).isEqualTo(503);
        assertThat(response.code()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(response.message()).doesNotContain("private-orders-host");
    }

    @Test
    void mapsNestedTimeoutToGatewayTimeout() throws Exception {
        GatewayErrorResponse response = handle(new NoFallbackAvailableException(
                "No fallback available", new TimeoutException("internal timeout detail")),
                "/api/v1/payments/123");

        assertThat(response.status()).isEqualTo(504);
        assertThat(response.code()).isEqualTo("GATEWAY_TIMEOUT");
        assertThat(response.message()).doesNotContain("internal timeout detail");
    }

    private GatewayErrorResponse handle(Throwable failure, String path) throws Exception {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get(path).build());
        exchange.getAttributes().put(
                RequestCorrelationFilter.CORRELATION_ID_ATTRIBUTE, "error-test-id");

        StepVerifier.create(handler.handle(exchange, failure)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNotNull();
        byte[] body = exchange.getResponse().getBodyAsString().block()
                .getBytes(StandardCharsets.UTF_8);
        return objectMapper.readValue(body, GatewayErrorResponse.class);
    }
}
