package com.example.platform.gateway.filter;

import com.example.platform.observability.CorrelationIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(RequestCorrelationFilter.class);

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

        long started = System.nanoTime();
        return chain.filter(correlated).doFinally(signal -> {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            int status = correlated.getResponse().getStatusCode() == null
                    ? 200 : correlated.getResponse().getStatusCode().value();
            log.atInfo()
                    .addKeyValue("correlationId", correlationId)
                    .addKeyValue("httpMethod", request.getMethod().name())
                    .addKeyValue("httpPath", request.getPath().value())
                    .addKeyValue("httpStatus", status)
                    .addKeyValue("durationMs", durationMs)
                    .log("Gateway request completed");
        });
    }

    /** Runs before Spring Security's WebFilterChainProxy (-100), including for 401/403 responses. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
