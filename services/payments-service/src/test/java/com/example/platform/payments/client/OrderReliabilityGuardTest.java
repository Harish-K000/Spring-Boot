package com.example.platform.payments.client;

import com.example.platform.payments.exception.PaymentDependencyException;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.retry.support.RetryTemplate;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class OrderReliabilityGuardTest {

    @Test
    void retriesIdempotentOrderOperationsWithABound() {
        AtomicInteger attempts = new AtomicInteger();
        CircuitBreakerFactory<?, ?> factory = mock(CircuitBreakerFactory.class);
        given(factory.create("orders")).willReturn(directCircuitBreaker());
        RetryTemplate retry = RetryTemplate.builder().maxAttempts(3).noBackoff()
                .retryOn(PaymentDependencyException.class).build();
        OrderReliabilityGuard guard = new OrderReliabilityGuard(factory, retry);

        String result = guard.execute(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new PaymentDependencyException("temporary", null);
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(3);
    }

    private CircuitBreaker directCircuitBreaker() {
        return new CircuitBreaker() {
            @Override
            public <T> T run(Supplier<T> supplier, Function<Throwable, T> fallback) {
                try {
                    return supplier.get();
                } catch (Throwable failure) {
                    return fallback.apply(failure);
                }
            }
        };
    }
}
