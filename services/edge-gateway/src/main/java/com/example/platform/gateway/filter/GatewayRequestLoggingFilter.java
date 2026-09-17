package com.example.platform.gateway.filter;

import com.example.platform.observability.CorrelationIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/** Emits one bounded structured completion event for every gateway request. */
@Component
public class GatewayRequestLoggingFilter implements WebFilter, Ordered {

    static final String UNMATCHED_ROUTE = "unmatched";
    private static final Logger log = LoggerFactory.getLogger(GatewayRequestLoggingFilter.class);
    private final String serviceName;

    public GatewayRequestLoggingFilter(
            @Value("${spring.application.name:edge-gateway}") String serviceName) {
        this.serviceName = serviceName;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long started = System.nanoTime();
        return chain.filter(exchange).doFinally(signal -> logCompletion(exchange, signal, started));
    }

    private void logCompletion(ServerWebExchange exchange, SignalType signal, long started) {
        String correlationId = exchange.getAttributeOrDefault(
                RequestCorrelationFilter.CORRELATION_ID_ATTRIBUTE, "unknown");
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        int status = responseStatus(exchange, signal);

        log.atInfo()
                .addKeyValue("event", "http_request_completed")
                .addKeyValue("service", serviceName)
                .addKeyValue(CorrelationIds.MDC_KEY, correlationId)
                .addKeyValue("httpMethod", exchange.getRequest().getMethod().name())
                .addKeyValue("httpPath", exchange.getRequest().getPath().value())
                .addKeyValue("httpStatus", status)
                .addKeyValue("durationMs", (System.nanoTime() - started) / 1_000_000)
                .addKeyValue("routeId", route == null ? UNMATCHED_ROUTE : route.getId())
                .addKeyValue("outcome", signal.name())
                .log("Gateway request completed");
    }

    private int responseStatus(ServerWebExchange exchange, SignalType signal) {
        if (exchange.getResponse().getStatusCode() != null) {
            return exchange.getResponse().getStatusCode().value();
        }
        if (signal == SignalType.ON_ERROR) {
            return HttpStatus.INTERNAL_SERVER_ERROR.value();
        }
        // 499 is a conventional access-log value for a client-cancelled exchange.
        if (signal == SignalType.CANCEL) {
            return 499;
        }
        return HttpStatus.OK.value();
    }

    /** Correlation runs first; this filter still wraps security-generated 401/403 responses. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
