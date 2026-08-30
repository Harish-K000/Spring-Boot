package com.example.platform.observability;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = PlatformObservabilityAutoConfiguration.class)
@ConditionalOnClass(name = "jakarta.servlet.Filter")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ServletObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    RequestCorrelationFilter requestCorrelationFilter() {
        return new RequestCorrelationFilter();
    }
}
