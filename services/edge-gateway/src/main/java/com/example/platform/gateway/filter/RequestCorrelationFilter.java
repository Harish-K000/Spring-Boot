package com.example.platform.gateway.filter;

import com.example.platform.observability.CorrelationIds;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Establishes one safe correlation ID before security and routing run. */
@Component
public class RequestCorrelationFilter implements WebFilter, Ordered {

    public static final String CORRELATION_ID_HEADER = CorrelationIds.HEADER;
    public static final String CORRELATION_ID_ATTRIBUTE =
            RequestCorrelationFilter.class.getName() + ".correlationId";
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String correlationId = CorrelationIds.resolve(
                exchange.getRequest().getHeaders().getFirst(CORRELATION_ID_HEADER),
                exchange.getRequest().getHeaders().getFirst(CorrelationIds.LEGACY_HEADER));

        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(CorrelationIds.LEGACY_HEADER);
                    headers.set(CORRELATION_ID_HEADER, correlationId);
                })
                .build();
        ServerWebExchange correlated = exchange.mutate().request(request).build();
        correlated.getAttributes().put(CORRELATION_ID_ATTRIBUTE, correlationId);
        correlated.getResponse().getHeaders().set(CORRELATION_ID_HEADER, correlationId);
        correlated.getResponse().beforeCommit(() -> {
            correlated.getResponse().getHeaders().set(CORRELATION_ID_HEADER, correlationId);
            return Mono.empty();
        });

        return chain.filter(correlated);
    }

    /** Runs before Spring Security's WebFilterChainProxy (-100), including for 401/403 responses. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
