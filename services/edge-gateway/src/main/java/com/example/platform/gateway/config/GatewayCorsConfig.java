package com.example.platform.gateway.config;

import com.example.platform.observability.CorrelationIds;
import com.example.platform.gateway.filter.SensitiveEndpointRateLimitFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/** Central browser policy for every externally routed API. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayCorsProperties.class)
public class GatewayCorsConfig {

    @Bean
    CorsConfigurationSource gatewayCorsConfigurationSource(GatewayCorsProperties properties) {
        List<String> origins = validateOrigins(properties.allowedOrigins());

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of(
                HttpMethod.GET.name(), HttpMethod.POST.name(), HttpMethod.PUT.name(),
                HttpMethod.PATCH.name(), HttpMethod.DELETE.name(), HttpMethod.OPTIONS.name()));
        configuration.setAllowedHeaders(List.of(
                HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE,
                HttpHeaders.ACCEPT, CorrelationIds.HEADER));
        configuration.setExposedHeaders(List.of(
                CorrelationIds.HEADER,
                SensitiveEndpointRateLimitFilter.LIMIT_HEADER,
                SensitiveEndpointRateLimitFilter.REMAINING_HEADER,
                HttpHeaders.RETRY_AFTER));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private List<String> validateOrigins(List<String> configuredOrigins) {
        if (configuredOrigins == null || configuredOrigins.isEmpty()) {
            throw new IllegalStateException("gateway.cors.allowed-origins must not be empty");
        }
        return configuredOrigins.stream().map(String::trim).map(origin -> {
            if (origin.isBlank() || origin.equals("*") || origin.contains("*")) {
                throw new IllegalStateException("CORS origins must be explicit HTTP(S) origins");
            }
            URI uri;
            try {
                uri = URI.create(origin);
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("Invalid CORS origin: " + origin, ex);
            }
            boolean validScheme = "http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme());
            boolean originOnly = uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null
                    && (uri.getPath() == null || uri.getPath().isEmpty());
            if (!validScheme || !originOnly) {
                throw new IllegalStateException("Invalid CORS origin: " + origin);
            }
            return origin;
        }).distinct().toList();
    }
}
