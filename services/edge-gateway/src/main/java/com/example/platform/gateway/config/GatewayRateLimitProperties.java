package com.example.platform.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Basic per-instance limits for sensitive public authentication endpoints. */
@ConfigurationProperties(prefix = "gateway.rate-limit")
public record GatewayRateLimitProperties(
        boolean enabled,
        int requestsPerWindow,
        Duration window,
        int maxTrackedKeys) {

    public GatewayRateLimitProperties {
        if (requestsPerWindow < 1) {
            throw new IllegalArgumentException("gateway.rate-limit.requests-per-window must be positive");
        }
        if (window == null || window.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("gateway.rate-limit.window must be at least one second");
        }
        if (maxTrackedKeys < 100) {
            throw new IllegalArgumentException("gateway.rate-limit.max-tracked-keys must be at least 100");
        }
    }
}
