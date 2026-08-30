package com.example.platform.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Establishes trusted downstream identity headers. Client-supplied values are always removed;
 * values are added back only from a JWT that the security chain has already authenticated.
 */
@Component
public class IdentityPropagationFilter implements GlobalFilter, Ordered {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_EMAIL_HEADER = "X-User-Email";
    public static final String USER_ROLES_HEADER = "X-User-Roles";

    private static final List<String> TRUSTED_HEADERS =
            List.of(USER_ID_HEADER, USER_EMAIL_HEADER, USER_ROLES_HEADER);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest sanitizedRequest = exchange.getRequest().mutate()
                .headers(this::removeTrustedHeaders)
                .build();
        ServerWebExchange sanitizedExchange = exchange.mutate().request(sanitizedRequest).build();

        return sanitizedExchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(authentication -> withAuthenticatedIdentity(sanitizedExchange, authentication))
                .defaultIfEmpty(sanitizedExchange)
                .flatMap(chain::filter);
    }

    private void removeTrustedHeaders(HttpHeaders headers) {
        TRUSTED_HEADERS.forEach(headers::remove);
    }

    private ServerWebExchange withAuthenticatedIdentity(
            ServerWebExchange exchange, JwtAuthenticationToken authentication) {
        ServerHttpRequest.Builder request = exchange.getRequest().mutate()
                .header(USER_ID_HEADER, authentication.getToken().getSubject());

        String email = authentication.getToken().getClaimAsString("email");
        if (email != null && !email.isBlank()) {
            request.header(USER_EMAIL_HEADER, email);
        }

        String roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .filter(role -> !role.isBlank())
                .distinct()
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        if (!roles.isBlank()) {
            request.header(USER_ROLES_HEADER, roles);
        }

        return exchange.mutate().request(request.build()).build();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
