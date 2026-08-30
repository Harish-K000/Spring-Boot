package com.example.platform.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityPropagationFilterTest {

    private final IdentityPropagationFilter filter = new IdentityPropagationFilter();

    @Test
    void replacesSpoofedHeadersWithAuthenticatedJwtIdentity() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                        .get("/api/v1/orders")
                        .header(IdentityPropagationFilter.USER_ID_HEADER, "attacker")
                        .header(IdentityPropagationFilter.USER_EMAIL_HEADER, "attacker@example.com")
                        .header(IdentityPropagationFilter.USER_ROLES_HEADER, "ADMIN")
                        .build())
                .mutate()
                .principal(Mono.just(authentication()))
                .build();
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        GatewayFilterChain chain = filtered -> {
            captured.set(filtered);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        ServerHttpRequest request = captured.get().getRequest();
        assertThat(request.getHeaders().getFirst(IdentityPropagationFilter.USER_ID_HEADER))
                .isEqualTo("user-123");
        assertThat(request.getHeaders().getFirst(IdentityPropagationFilter.USER_EMAIL_HEADER))
                .isEqualTo("user@example.com");
        assertThat(request.getHeaders().getFirst(IdentityPropagationFilter.USER_ROLES_HEADER))
                .isEqualTo("ADMIN,USER");
    }

    @Test
    void removesSpoofedHeadersFromAnonymousPublicRequest() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/v1/auth/login")
                .header(IdentityPropagationFilter.USER_ID_HEADER, "attacker")
                .header(IdentityPropagationFilter.USER_ROLES_HEADER, "ADMIN")
                .build());
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();

        StepVerifier.create(filter.filter(exchange, filtered -> {
                    captured.set(filtered);
                    return Mono.empty();
                }))
                .verifyComplete();

        assertThat(captured.get().getRequest().getHeaders()
                .containsKey(IdentityPropagationFilter.USER_ID_HEADER)).isFalse();
        assertThat(captured.get().getRequest().getHeaders()
                .containsKey(IdentityPropagationFilter.USER_ROLES_HEADER)).isFalse();
    }

    private JwtAuthenticationToken authentication() {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .subject("user-123")
                .claim("email", "user@example.com")
                .claim("roles", "USER,ADMIN")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(60))
                .build();
        return new JwtAuthenticationToken(jwt, List.of(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("ROLE_ADMIN")));
    }
}
