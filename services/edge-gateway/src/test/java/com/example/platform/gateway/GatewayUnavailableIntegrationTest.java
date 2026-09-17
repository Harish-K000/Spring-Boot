package com.example.platform.gateway;

import com.example.platform.observability.CorrelationIds;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "security.jwt.secret=gateway-test-secret-with-at-least-32-bytes")
@AutoConfigureWebTestClient
class GatewayUnavailableIntegrationTest {

    private static final String SECRET = "gateway-test-secret-with-at-least-32-bytes";
    private static final int CLOSED_PORT = findClosedLocalPort();

    @Autowired private WebTestClient client;

    @DynamicPropertySource
    static void unavailableOrdersDestination(DynamicPropertyRegistry properties) {
        properties.add("ORDERS_SERVICE_URI", () -> "http://127.0.0.1:" + CLOSED_PORT);
    }

    @Test
    void unavailableDownstreamReturnsSanitizedCorrelated503() throws Exception {
        String token = adminToken();

        client.get().uri("/api/v1/orders/123")
                .headers(headers -> headers.setBearerAuth(token))
                .header(CorrelationIds.HEADER, "orders-offline-test")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().contentType("application/json")
                .expectHeader().valueEquals(CorrelationIds.HEADER, "orders-offline-test")
                .expectBody()
                .jsonPath("$.timestamp").exists()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE")
                .jsonPath("$.message").isEqualTo(
                        "The requested service is temporarily unavailable.")
                .jsonPath("$.path").isEqualTo("/api/v1/orders/123")
                .jsonPath("$.correlationId").isEqualTo("orders-offline-test")
                .consumeWith(result -> assertThat(new String(
                        result.getResponseBody(), StandardCharsets.UTF_8))
                        .doesNotContain("127.0.0.1", Integer.toString(CLOSED_PORT),
                                "ConnectException", "java."));
    }

    private static int findClosedLocalPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception exception) {
            throw new IllegalStateException("Could not reserve a local test port", exception);
        }
    }

    private static String adminToken() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("gateway-test-admin")
                .issuer("backend-platform-auth")
                .audience("backend-platform-api")
                .claim("roles", "ADMIN")
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(120)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
