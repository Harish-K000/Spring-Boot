package com.example.platform.gateway;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "security.jwt.secret=gateway-test-secret-with-at-least-32-bytes")
@AutoConfigureWebTestClient
@Import(GatewaySecurityIntegrationTest.TestRoutes.class)
class GatewaySecurityIntegrationTest {

    private static final String SECRET = "gateway-test-secret-with-at-least-32-bytes";

    @Autowired private WebTestClient webTestClient;

    @Test
    void healthEndpointIsPublic() {
        webTestClient.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void protectedRequestWithoutTokenReturnsUniform401() {
        webTestClient.get().uri("/__test/principal")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType("application/json")
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.message").isEqualTo("Authentication required")
                .jsonPath("$.path").isEqualTo("/__test/principal");
    }

    @Test
    void invalidTokenReturnsUniform401() {
        webTestClient.get().uri("/__test/principal")
                .header(HttpHeaders.AUTHORIZATION, "Bearer definitely-not-a-jwt")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.message").isEqualTo("Invalid or expired access token");
    }

    @Test
    void validTokenAuthenticatesSubjectAndCommaSeparatedRoles() throws Exception {
        webTestClient.get().uri("/__test/principal")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(Instant.now().plusSeconds(60)))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.subject").isEqualTo("user-123")
                .jsonPath("$.authorities").isEqualTo("ROLE_ADMIN,ROLE_USER");
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        webTestClient.get().uri("/__test/principal")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(Instant.now().minusSeconds(1)))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void tokenForAnotherAudienceIsRejected() throws Exception {
        webTestClient.get().uri("/__test/principal")
                .header(HttpHeaders.AUTHORIZATION, "Bearer "
                        + token(Instant.now().plusSeconds(60), "some-other-api"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void sensitiveActuatorEndpointsRequireAuthentication() {
        webTestClient.get().uri("/actuator/metrics")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    private String token(Instant expiresAt) throws Exception {
        return token(expiresAt, "backend-platform-api");
    }

    private String token(Instant expiresAt, String audience) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("user-123")
                .issuer("backend-platform-auth")
                .audience(audience)
                .claim("email", "user@example.com")
                .claim("roles", "USER,ADMIN")
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(expiresAt))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestRoutes {

        @Bean
        RouterFunction<ServerResponse> authenticatedPrincipalRoute() {
            return route(GET("/__test/principal"), request -> request.principal()
                    .cast(Authentication.class)
                    .flatMap(authentication -> {
                        Map<String, Object> body = new LinkedHashMap<>();
                        body.put("subject", authentication.getName());
                        body.put("authorities", authentication.getAuthorities().stream()
                                .map(authority -> authority.getAuthority())
                                .sorted()
                                .reduce((left, right) -> left + "," + right)
                                .orElse(""));
                        return ServerResponse.ok().bodyValue(body);
                    }));
        }
    }
}
