package com.example.platform.observability;

import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class PlatformObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "requestIdRestClientCustomizer")
    RestClientCustomizer requestIdRestClientCustomizer() {
        return builder -> builder.requestInterceptor((request, body, execution) -> {
            String requestId = MDC.get(RequestCorrelationFilter.REQUEST_ID_MDC_KEY);
            if (requestId != null) {
                request.getHeaders().set(RequestCorrelationFilter.REQUEST_ID_HEADER, requestId);
            }
            return execution.execute(request, body);
        });
    }
}
