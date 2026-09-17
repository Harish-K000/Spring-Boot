package com.example.platform.observability;

import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class PlatformObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "correlationIdRestClientCustomizer")
    RestClientCustomizer correlationIdRestClientCustomizer() {
        return builder -> builder.requestInterceptor((request, body, execution) -> {
            String correlationId = MDC.get(CorrelationIds.MDC_KEY);
            if (correlationId != null) {
                request.getHeaders().remove(CorrelationIds.LEGACY_HEADER);
                request.getHeaders().set(CorrelationIds.HEADER, correlationId);
            }
            return execution.execute(request, body);
        });
    }
}
