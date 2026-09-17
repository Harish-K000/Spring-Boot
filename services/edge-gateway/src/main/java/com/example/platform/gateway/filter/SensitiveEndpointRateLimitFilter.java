package com.example.platform.gateway.filter;

import com.example.platform.gateway.config.GatewayRateLimitProperties;
import com.example.platform.gateway.error.GatewayErrorResponseWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Fixed-window, per-instance protection for authentication endpoints.
 * Direct peer addresses are used; untrusted forwarding headers cannot select a new bucket.
 */
@Component
public class SensitiveEndpointRateLimitFilter implements WebFilter, Ordered {

    public static final String LIMIT_HEADER = "X-RateLimit-Limit";
    public static final String REMAINING_HEADER = "X-RateLimit-Remaining";
    private static final Set<String> LIMITED_PATHS = Set.of(
            "/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/auth/refresh");

    private final GatewayRateLimitProperties properties;
    private final GatewayErrorResponseWriter errorWriter;
    private final Clock clock;
    private final Map<ClientEndpointKey, WindowCounter> counters = new HashMap<>();

    @Autowired
    public SensitiveEndpointRateLimitFilter(
            GatewayRateLimitProperties properties,
            GatewayErrorResponseWriter errorWriter) {
        this(properties, errorWriter, Clock.systemUTC());
    }

    SensitiveEndpointRateLimitFilter(
            GatewayRateLimitProperties properties,
            GatewayErrorResponseWriter errorWriter,
            Clock clock) {
        this.properties = properties;
        this.errorWriter = errorWriter;
        this.clock = clock;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!properties.enabled()
                || exchange.getRequest().getMethod() != HttpMethod.POST
                || !LIMITED_PATHS.contains(path)) {
            return chain.filter(exchange);
        }

        Decision decision = acquire(new ClientEndpointKey(clientAddress(exchange), path));
        exchange.getResponse().getHeaders().set(
                LIMIT_HEADER, Integer.toString(properties.requestsPerWindow()));
        exchange.getResponse().getHeaders().set(
                REMAINING_HEADER, Integer.toString(decision.remaining()));

        if (decision.allowed()) {
            return chain.filter(exchange);
        }

        exchange.getResponse().getHeaders().set(
                "Retry-After", Long.toString(decision.retryAfterSeconds()));
        return errorWriter.write(exchange, HttpStatus.TOO_MANY_REQUESTS,
                "RATE_LIMIT_EXCEEDED", "Too many requests. Try again later.");
    }

    private Decision acquire(ClientEndpointKey key) {
        long now = clock.millis();
        long windowMillis = properties.window().toMillis();
        synchronized (counters) {
            WindowCounter current = counters.get(key);
            if (current == null || now >= current.resetAtMillis()) {
                if (current == null && counters.size() >= properties.maxTrackedKeys()) {
                    removeExpired(now);
                    if (counters.size() >= properties.maxTrackedKeys()) {
                        return new Decision(false, 0, seconds(windowMillis));
                    }
                }
                counters.put(key, new WindowCounter(1, now + windowMillis));
                return new Decision(true, properties.requestsPerWindow() - 1, 0);
            }
            if (current.requests() >= properties.requestsPerWindow()) {
                return new Decision(false, 0, seconds(current.resetAtMillis() - now));
            }
            int updated = current.requests() + 1;
            counters.put(key, new WindowCounter(updated, current.resetAtMillis()));
            return new Decision(true, properties.requestsPerWindow() - updated, 0);
        }
    }

    private void removeExpired(long now) {
        Iterator<WindowCounter> iterator = counters.values().iterator();
        while (iterator.hasNext()) {
            if (now >= iterator.next().resetAtMillis()) {
                iterator.remove();
            }
        }
    }

    private long seconds(long milliseconds) {
        return Math.max(1, Math.ceilDiv(milliseconds, 1_000));
    }

    private String clientAddress(ServerWebExchange exchange) {
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            return "unknown";
        }
        return remoteAddress.getAddress().getHostAddress();
    }

    /** Runs inside correlation/logging and immediately after Spring Security's CORS processing. */
    @Override
    public int getOrder() {
        return SecurityProperties.DEFAULT_FILTER_ORDER + 1;
    }

    private record ClientEndpointKey(String clientAddress, String path) {
    }

    private record WindowCounter(int requests, long resetAtMillis) {
    }

    private record Decision(boolean allowed, int remaining, long retryAfterSeconds) {
    }
}
