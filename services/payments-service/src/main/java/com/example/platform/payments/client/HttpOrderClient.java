package com.example.platform.payments.client;

import com.example.platform.payments.exception.PaymentDependencyException;
import com.example.platform.payments.exception.PaymentRejectedException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.UUID;

@Component
public class HttpOrderClient implements OrderClient {

    private final RestClient restClient;
    private final OrderReliabilityGuard reliability;

    public HttpOrderClient(@Qualifier("ordersRestClient") RestClient restClient,
                           OrderReliabilityGuard reliability) {
        this.restClient = restClient;
        this.reliability = reliability;
    }

    @Override
    public OrderSnapshot findOrder(UUID orderId) {
        return execute(() -> restClient.get()
                .uri("/internal/v1/orders/{id}", orderId)
                .retrieve()
                .body(OrderSnapshot.class));
    }

    @Override
    public void markPaid(UUID orderId, UUID paymentId) {
        sendEvent(orderId, paymentId, "paid");
    }

    @Override
    public void markRefunded(UUID orderId, UUID paymentId) {
        sendEvent(orderId, paymentId, "refunded");
    }

    private void sendEvent(UUID orderId, UUID paymentId, String event) {
        execute(() -> {
            restClient.post()
                    .uri("/internal/v1/orders/{id}/" + event, orderId)
                    .body(new PaymentEvent(paymentId))
                    .retrieve()
                    .toBodilessEntity();
            return null;
        });
    }

    private <T> T execute(OrderCall<T> call) {
        return reliability.execute(() -> invoke(call));
    }

    private <T> T invoke(OrderCall<T> call) {
        try {
            return call.execute();
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().is4xxClientError()) {
                throw new PaymentRejectedException("Order rejected the payment operation", ex);
            }
            throw new PaymentDependencyException("Orders service failed", ex);
        } catch (RestClientException ex) {
            throw new PaymentDependencyException("Orders service is unavailable", ex);
        }
    }

    private record PaymentEvent(UUID paymentId) {}

    @FunctionalInterface
    private interface OrderCall<T> {
        T execute();
    }
}
