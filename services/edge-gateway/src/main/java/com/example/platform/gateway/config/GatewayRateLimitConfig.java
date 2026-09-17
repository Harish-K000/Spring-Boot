package com.example.platform.gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the validated rate-limit settings. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayRateLimitProperties.class)
public class GatewayRateLimitConfig {
}
