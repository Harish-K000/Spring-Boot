package com.example.platform.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** Exact browser origins allowed to call the public gateway. */
@ConfigurationProperties(prefix = "gateway.cors")
public record GatewayCorsProperties(List<String> allowedOrigins) {
}
