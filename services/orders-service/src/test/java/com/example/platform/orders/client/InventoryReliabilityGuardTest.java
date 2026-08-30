package com.example.platform.orders.client;

import com.example.platform.orders.exception.CheckoutDependencyException;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.retry.support.RetryTemplate;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class InventoryReliabilityGuardTest {

    @Test
    void retriesRetryableReadsWithABound() {
        AtomicInteger attempts = new AtomicInteger();
        InventoryReliabilityGuard guard = guard(3);

        String result = guard.execute(true, () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new CheckoutDependencyException("temporary", null);
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void doesNotRetryInventoryWrites() {
        AtomicInteger attempts = new AtomicInteger();
        InventoryReliabilityGuard guard = guard(3);

        assertThatThrownBy(() -> guard.execute(false, () -> {
            attempts.incrementAndGet();
            throw new CheckoutDependencyException("failed", null);
        })).isInstanceOf(CheckoutDependencyException.class);
        assertThat(attempts).hasValue(1);
    }

    private InventoryReliabilityGuard guard(int maxAttempts) {
        CircuitBreakerFactory<?, ?> factory = mock(CircuitBreakerFactory.class);
        given(factory.create("inventory")).willReturn(directCircuitBreaker());
        RetryTemplate retry = RetryTemplate.builder().maxAttempts(maxAttempts).noBackoff()
                .retryOn(CheckoutDependencyException.class).build();
        return new InventoryReliabilityGuard(factory, retry);
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
