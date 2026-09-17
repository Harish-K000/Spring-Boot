package com.example.platform.payments.config;

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
public class OrderClientConfig {

    @Bean
    public RestClient ordersRestClient(
            RestClient.Builder builder,
            @Value("${clients.orders.base-url:http://localhost:8082}") String baseUrl,
            @Value("${clients.orders.connect-timeout:1s}") Duration connectTimeout,
            @Value("${clients.orders.read-timeout:2s}") Duration readTimeout) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(readTimeout);
        return builder.baseUrl(baseUrl)
                .requestFactory(factory)
                .requestInterceptor(PlatformJwtSecurity.bearerTokenForwardingInterceptor())
                .build();
    }

    @Bean
    public RetryTemplate ordersRetryTemplate(
            @Value("${clients.orders.retry.max-attempts:2}") int maxAttempts,
            @Value("${clients.orders.retry.initial-backoff:100ms}") Duration initialBackoff,
            @Value("${clients.orders.retry.max-backoff:500ms}") Duration maxBackoff) {
        return RetryTemplate.builder()
                .maxAttempts(maxAttempts)
                .exponentialBackoff(initialBackoff, 2.0, maxBackoff)
                .retryOn(com.example.platform.payments.exception.PaymentDependencyException.class)
                .build();
    }
}
