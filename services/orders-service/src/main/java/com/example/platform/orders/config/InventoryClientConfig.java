package com.example.platform.orders.config;

import com.example.platform.security.PlatformJwtSecurity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class InventoryClientConfig {

    @Bean
    public RestClient inventoryRestClient(
            RestClient.Builder builder,
            @Value("${clients.inventory.base-url:http://localhost:8083}") String baseUrl,
            @Value("${clients.inventory.connect-timeout:1s}") Duration connectTimeout,
            @Value("${clients.inventory.read-timeout:2s}") Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return builder.baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .requestInterceptor(PlatformJwtSecurity.bearerTokenForwardingInterceptor())
                .build();
    }

    @Bean
    public RetryTemplate inventoryRetryTemplate(
            @Value("${clients.inventory.retry.max-attempts:2}") int maxAttempts,
            @Value("${clients.inventory.retry.initial-backoff:100ms}") Duration initialBackoff,
            @Value("${clients.inventory.retry.max-backoff:500ms}") Duration maxBackoff) {
        return RetryTemplate.builder()
                .maxAttempts(maxAttempts)
                .exponentialBackoff(initialBackoff, 2.0, maxBackoff)
                .retryOn(com.example.platform.orders.exception.CheckoutDependencyException.class)
                .build();
    }
}
