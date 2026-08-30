package com.example.platform.orders.client;

import com.example.platform.orders.exception.CheckoutDependencyException;
import com.example.platform.orders.exception.CheckoutRejectedException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
public class InventoryReliabilityGuard {

    private final CircuitBreakerFactory<?, ?> circuitBreakers;
    private final RetryTemplate retry;

    public InventoryReliabilityGuard(
            CircuitBreakerFactory<?, ?> circuitBreakers,
            @Qualifier("inventoryRetryTemplate") RetryTemplate retry) {
        this.circuitBreakers = circuitBreakers;
        this.retry = retry;
    }

    public <T> T execute(boolean retryable, Supplier<T> call) {
        try {
            return circuitBreakers.create("inventory").run(() -> retryable
                    ? retry.execute(context -> call.get()) : call.get());
        } catch (CheckoutRejectedException | CheckoutDependencyException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new CheckoutDependencyException("Inventory circuit breaker is open", ex);
        }
    }
}
