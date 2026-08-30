package com.example.platform.payments.client;

import com.example.platform.payments.exception.PaymentDependencyException;
import com.example.platform.payments.exception.PaymentRejectedException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
public class OrderReliabilityGuard {

    private final CircuitBreakerFactory<?, ?> circuitBreakers;
    private final RetryTemplate retry;

    public OrderReliabilityGuard(
            CircuitBreakerFactory<?, ?> circuitBreakers,
            @Qualifier("ordersRetryTemplate") RetryTemplate retry) {
        this.circuitBreakers = circuitBreakers;
        this.retry = retry;
    }

    public <T> T execute(Supplier<T> call) {
        try {
            return circuitBreakers.create("orders").run(
                    () -> retry.execute(context -> call.get()));
        } catch (PaymentRejectedException | PaymentDependencyException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PaymentDependencyException("Orders circuit breaker is open", ex);
        }
    }
}
