package com.example.platform.orders.client;

import com.example.platform.orders.exception.CheckoutDependencyException;
import com.example.platform.orders.exception.CheckoutRejectedException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.UUID;

@Component
public class HttpInventoryClient implements InventoryClient {

    private final RestClient restClient;
    private final InventoryReliabilityGuard reliability;

    public HttpInventoryClient(@Qualifier("inventoryRestClient") RestClient restClient,
                               InventoryReliabilityGuard reliability) {
        this.restClient = restClient;
        this.reliability = reliability;
    }

    @Override
    public ProductSnapshot findProduct(UUID productId) {
        return execute(true, () -> restClient.get()
                .uri("/api/v1/products/{id}", productId)
                .retrieve()
                .body(ProductSnapshot.class));
    }

    @Override
    public void reserve(UUID productId, int quantity) {
        changeStock(productId, quantity, "reserve");
    }

    @Override
    public void release(UUID productId, int quantity) {
        changeStock(productId, quantity, "release");
    }

    private void changeStock(UUID productId, int quantity, String operation) {
        execute(false, () -> {
            restClient.post()
                    .uri("/api/v1/products/{id}/" + operation, productId)
                    .body(new StockChange(quantity))
                    .retrieve()
                    .toBodilessEntity();
            return null;
        });
    }

    private <T> T execute(boolean retryable, InventoryCall<T> call) {
        return reliability.execute(retryable, () -> invoke(call));
    }

    private <T> T invoke(InventoryCall<T> call) {
        try {
            return call.execute();
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().is4xxClientError()) {
                String detail = ex.getStatusCode() == HttpStatus.NOT_FOUND
                        ? "A checkout product does not exist"
                        : "Inventory rejected the checkout request";
                throw new CheckoutRejectedException(detail, ex);
            }
            throw new CheckoutDependencyException("Inventory service failed", ex);
        } catch (RestClientException ex) {
            throw new CheckoutDependencyException("Inventory service is unavailable", ex);
        }
    }

    private record StockChange(int quantity) {}

    @FunctionalInterface
    private interface InventoryCall<T> {
        T execute();
    }
}
