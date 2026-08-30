package com.example.platform.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

/** Adds one safe request identifier to both the downstream request and gateway response. */
@Component
public class RequestCorrelationFilter implements GlobalFilter, Ordered {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String REQUEST_ID_ATTRIBUTE = RequestCorrelationFilter.class.getName() + ".requestId";
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Logger log = LoggerFactory.getLogger(RequestCorrelationFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String supplied = exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER);
        String requestId = supplied != null && SAFE_REQUEST_ID.matcher(supplied).matches()
                ? supplied
                : UUID.randomUUID().toString();

        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(REQUEST_ID_HEADER, requestId))
                .build();
        exchange.getResponse().getHeaders().set(REQUEST_ID_HEADER, requestId);
        ServerWebExchange correlated = exchange.mutate().request(request).build();
        correlated.getAttributes().put(REQUEST_ID_ATTRIBUTE, requestId);
        long started = System.nanoTime();
        return chain.filter(correlated).doFinally(signal -> {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            int status = exchange.getResponse().getStatusCode() == null
                    ? 200 : exchange.getResponse().getStatusCode().value();
            log.atInfo()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("httpMethod", request.getMethod().name())
                    .addKeyValue("httpPath", request.getPath().value())
                    .addKeyValue("httpStatus", status)
                    .addKeyValue("durationMs", durationMs)
                    .log("Gateway request completed");
        });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
