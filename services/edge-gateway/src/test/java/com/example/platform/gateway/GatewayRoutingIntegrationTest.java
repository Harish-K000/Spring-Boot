package com.example.platform.gateway;

import com.example.platform.observability.CorrelationIds;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/** Real HTTP proxy checks with local downstream stubs, without service databases. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "security.jwt.secret=gateway-test-secret-with-at-least-32-bytes",
                "spring.cloud.gateway.server.webflux.httpclient.response-timeout=1s",
                "resilience4j.timelimiter.configs.default.timeout-duration=5s"
        })
@AutoConfigureWebTestClient
class GatewayRoutingIntegrationTest {
    private static final String SECRET = "gateway-test-secret-with-at-least-32-bytes";
    private static final DisposableServer AUTH = downstream("auth-service");
    private static final DisposableServer ORDERS = downstream("orders-service");
    private static final DisposableServer INVENTORY = downstream("inventory-service");
    private static final DisposableServer PAYMENTS = downstream("payments-service");

    @Autowired private WebTestClient client;

    @DynamicPropertySource
    static void destinations(DynamicPropertyRegistry properties) {
        properties.add("AUTH_SERVICE_URI", () -> uri(AUTH));
        properties.add("ORDERS_SERVICE_URI", () -> uri(ORDERS));
        properties.add("INVENTORY_SERVICE_URI", () -> uri(INVENTORY));
        properties.add("PAYMENTS_SERVICE_URI", () -> uri(PAYMENTS));
    }

    @AfterAll
    static void stopDownstreams() {
        AUTH.disposeNow();
        ORDERS.disposeNow();
        INVENTORY.disposeNow();
        PAYMENTS.disposeNow();
    }

    @Test
    void routesPublicAuthRequestWithoutChangingItsPath() {
        for (String path : new String[]{"/api/v1/auth/register", "/api/v1/auth/login",
                "/api/v1/auth/refresh"}) {
            client.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                    .exchange().expectStatus().isOk()
                    .expectHeader().valueEquals("X-Downstream-Service", "auth-service")
                    .expectHeader().valueEquals("X-Downstream-Uri", path);
        }
    }

    @Test
    void preservesSafeCorrelationIdAcrossGatewayAndDownstream() {
        client.post().uri("/api/v1/auth/login")
                .header(CorrelationIds.HEADER, "checkout-abc-123")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals(CorrelationIds.HEADER, "checkout-abc-123")
                .expectHeader().valueEquals(
                        "X-Downstream-Correlation-ID", "checkout-abc-123");
    }

    @Test
    void forwardsBearerTokenAndReplacesSpoofedIdentityHeaders() throws Exception {
        String bearer = token("USER");

        client.get().uri("/api/v1/payments/456")
                .headers(headers -> headers.setBearerAuth(bearer))
                .header("X-User-Id", "attacker")
                .header("X-User-Email", "attacker@example.test")
                .header("X-User-Roles", "ADMIN")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Downstream-Authorization-Scheme", "Bearer")
                .expectHeader().valueEquals("X-Downstream-User-Id", "routing-test-user")
                .expectHeader().valueEquals(
                        "X-Downstream-User-Email", "routing-user@example.test")
                .expectHeader().valueEquals("X-Downstream-User-Roles", "USER");
    }

    @Test
    void replacesUnsafeCorrelationIdAndUsesOneValueEverywhere() {
        EntityExchangeResult<byte[]> result = client.post().uri("/api/v1/auth/login")
                .header(CorrelationIds.HEADER, "x".repeat(129))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isOk()
                .expectBody().returnResult();

        String responseId = result.getResponseHeaders().getFirst(CorrelationIds.HEADER);
        String downstreamId = result.getResponseHeaders()
                .getFirst("X-Downstream-Correlation-ID");
        org.assertj.core.api.Assertions.assertThat(responseId)
                .isNotBlank().isEqualTo(downstreamId);
        org.assertj.core.api.Assertions.assertThatCode(() -> java.util.UUID.fromString(responseId))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsProtectedRoutesBeforeRoutingWhenTokenIsMissing() {
        for (String path : new String[]{"/api/v1/protected/me", "/api/v1/orders/123",
                "/api/v1/products/abc", "/api/v1/inventory/abc", "/api/v1/payments/456",
                "/api/v1/auth/logout", "/api/v1/auth/not-a-public-operation"}) {
            client.get().uri(path).exchange().expectStatus().isUnauthorized();
        }
    }

    @Test
    void routesOrdersAndPaymentsToTheirOwnServices() throws Exception {
        String admin = token("ADMIN");
        String user = token("USER");
        client.get().uri("/api/v1/orders/123").headers(h -> h.setBearerAuth(admin))
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Downstream-Service", "orders-service")
                .expectHeader().valueEquals("X-Downstream-Uri", "/api/v1/orders/123");
        client.get().uri("/api/v1/payments/456").headers(h -> h.setBearerAuth(user))
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Downstream-Service", "payments-service")
                .expectHeader().valueEquals("X-Downstream-Uri", "/api/v1/payments/456");
    }

    @Test
    void keepsProductsPathAndRewritesInventoryAlias() throws Exception {
        String bearer = token("USER");
        client.get().uri("/api/v1/products/abc").headers(h -> h.setBearerAuth(bearer))
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Downstream-Service", "inventory-service")
                .expectHeader().valueEquals("X-Downstream-Uri", "/api/v1/products/abc");
        client.get().uri("/api/v1/inventory/abc").headers(h -> h.setBearerAuth(bearer))
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Downstream-Service", "inventory-service")
                .expectHeader().valueEquals("X-Downstream-Uri", "/api/v1/products/abc");
        client.get().uri("/api/v1/inventory?name=shirt").headers(h -> h.setBearerAuth(bearer))
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Downstream-Service", "inventory-service")
                .expectHeader().valueEquals("X-Downstream-Uri", "/api/v1/products?name=shirt");
    }

    @Test
    void enforcesUserAndAdminRouteRoles() throws Exception {
        String user = token("USER");
        String admin = token("ADMIN");

        client.post().uri("/api/v1/orders/checkout").headers(h -> h.setBearerAuth(user))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Downstream-Service", "orders-service");
        client.get().uri("/api/v1/orders/123").headers(h -> h.setBearerAuth(user))
                .exchange().expectStatus().isForbidden()
                .expectHeader().exists(CorrelationIds.HEADER);
        client.post().uri("/api/v1/products").headers(h -> h.setBearerAuth(user))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("ACCESS_DENIED")
                .jsonPath("$.message")
                .isEqualTo("You do not have permission to access this resource.");
        client.post().uri("/api/v1/inventory").headers(h -> h.setBearerAuth(admin))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Downstream-Service", "inventory-service")
                .expectHeader().valueEquals("X-Downstream-Uri", "/api/v1/products");
        client.get().uri("/actuator/info").headers(h -> h.setBearerAuth(user))
                .exchange().expectStatus().isForbidden();
        client.get().uri("/actuator/info").headers(h -> h.setBearerAuth(admin))
                .exchange().expectStatus().isOk();
    }

    @Test
    void rejectsRolelessAndUnknownRolesOnServiceRoutes() throws Exception {
        for (String roles : new String[]{"", "SUPPORT"}) {
            String bearer = token(roles);
            client.get().uri("/api/v1/payments/456")
                    .headers(h -> h.setBearerAuth(bearer))
                    .exchange().expectStatus().isForbidden();
        }
    }

    @Test
    void returnsStandardGatewayTimeoutWhenDownstreamRespondsTooSlowly() throws Exception {
        String admin = token("ADMIN");
        client.get().uri("/api/v1/orders/slow")
                .headers(headers -> headers.setBearerAuth(admin))
                .header(CorrelationIds.HEADER, "slow-order-test")
                .exchange()
                .expectStatus().isEqualTo(504)
                .expectHeader().valueEquals(CorrelationIds.HEADER, "slow-order-test")
                .expectBody()
                .jsonPath("$.status").isEqualTo(504)
                .jsonPath("$.code").isEqualTo("GATEWAY_TIMEOUT")
                .jsonPath("$.message").isEqualTo(
                        "The requested service did not respond in time.")
                .jsonPath("$.path").isEqualTo("/api/v1/orders/slow")
                .jsonPath("$.correlationId").isEqualTo("slow-order-test");
    }

    private static DisposableServer downstream(String service) {
        return HttpServer.create().host("127.0.0.1").port(0)
                .handle((request, response) -> {
                    Mono<String> body = Mono.just(service + ":" + request.uri());
                    if ("/api/v1/orders/slow".equals(request.uri())) {
                        body = body.delayElement(java.time.Duration.ofMillis(1500));
                    }
                    String authorization = request.requestHeaders()
                            .get(org.springframework.http.HttpHeaders.AUTHORIZATION);
                    response.status(200)
                        .header("X-Downstream-Service", service)
                        .header("X-Downstream-Uri", request.uri())
                        .header("X-Downstream-Correlation-ID",
                                request.requestHeaders().get(CorrelationIds.HEADER));
                    if (authorization != null) {
                        response.header("X-Downstream-Authorization-Scheme",
                                authorization.startsWith("Bearer ") ? "Bearer" : "Other");
                    }
                    copyHeader(request, response, "X-User-Id", "X-Downstream-User-Id");
                    copyHeader(request, response, "X-User-Email", "X-Downstream-User-Email");
                    copyHeader(request, response, "X-User-Roles", "X-Downstream-User-Roles");
                    return response.sendString(body).then();
                })
                .bindNow();
    }

    private static void copyHeader(reactor.netty.http.server.HttpServerRequest request,
                                   reactor.netty.http.server.HttpServerResponse response,
                                   String source, String destination) {
        String value = request.requestHeaders().get(source);
        if (value != null) {
            response.header(destination, value);
        }
    }

    private static String uri(DisposableServer server) { return "http://127.0.0.1:" + server.port(); }

    private static String token(String roles) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("routing-test-user")
                .issuer("backend-platform-auth")
                .audience("backend-platform-api")
                .claim("email", "routing-user@example.test")
                .claim("roles", roles)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(120)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
