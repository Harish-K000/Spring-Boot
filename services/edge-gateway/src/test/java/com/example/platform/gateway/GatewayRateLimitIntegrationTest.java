package com.example.platform.gateway;

import com.example.platform.gateway.filter.SensitiveEndpointRateLimitFilter;
import com.example.platform.observability.CorrelationIds;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "security.jwt.secret=gateway-test-secret-with-at-least-32-bytes",
        "AUTH_SERVICE_URI=http://127.0.0.1:1",
        "gateway.rate-limit.requests-per-window=2",
        "gateway.rate-limit.window=1m"
})
@AutoConfigureWebTestClient
class GatewayRateLimitIntegrationTest {

    @Autowired private WebTestClient client;
    @LocalServerPort private int port;

    @Test
    void limitsSensitiveEndpointByDirectClientAndReturnsStandard429() {
        attemptLogin("198.51.100.1").expectStatus().value(status ->
                org.assertj.core.api.Assertions.assertThat(status).isNotEqualTo(429));
        attemptLogin("198.51.100.2").expectStatus().value(status ->
                org.assertj.core.api.Assertions.assertThat(status).isNotEqualTo(429));

        attemptLogin("198.51.100.3")
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000")
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                        value -> org.assertj.core.api.Assertions.assertThat(value)
                                .contains(CorrelationIds.HEADER,
                                        SensitiveEndpointRateLimitFilter.LIMIT_HEADER,
                                        SensitiveEndpointRateLimitFilter.REMAINING_HEADER,
                                        HttpHeaders.RETRY_AFTER))
                .expectHeader().valueEquals(
                        SensitiveEndpointRateLimitFilter.LIMIT_HEADER, "2")
                .expectHeader().valueEquals(
                        SensitiveEndpointRateLimitFilter.REMAINING_HEADER, "0")
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "60")
                .expectHeader().exists(CorrelationIds.HEADER)
                .expectBody()
                .jsonPath("$.status").isEqualTo(429)
                .jsonPath("$.code").isEqualTo("RATE_LIMIT_EXCEEDED")
                .jsonPath("$.message").isEqualTo("Too many requests. Try again later.")
                .jsonPath("$.path").isEqualTo("/api/v1/auth/login")
                .jsonPath("$.correlationId").exists();

        // Each sensitive endpoint has its own bucket for the same direct client.
        client.post().uri(gatewayUrl("/api/v1/auth/register"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().value(status ->
                        org.assertj.core.api.Assertions.assertThat(status).isNotEqualTo(429));
    }

    private WebTestClient.ResponseSpec attemptLogin(String spoofedForwardedAddress) {
        return client.post().uri(gatewayUrl("/api/v1/auth/login"))
                .header("X-Forwarded-For", spoofedForwardedAddress)
                .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange();
    }

    private String gatewayUrl(String path) {
        return "http://127.0.0.1:" + port + path;
    }
}
