package com.example.platform.orders.client;

import com.example.platform.orders.exception.CheckoutRejectedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class HttpInventoryClientTest {

    private MockRestServiceServer server;
    private HttpInventoryClient inventoryClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://inventory");
        server = MockRestServiceServer.bindTo(builder).build();
        InventoryReliabilityGuard reliability = mock(InventoryReliabilityGuard.class);
        given(reliability.execute(anyBoolean(), any())).willAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(1)).get());
        inventoryClient = new HttpInventoryClient(builder.build(), reliability);
    }

    @Test
    void readsTheCatalogPricingContract() {
        UUID productId = UUID.randomUUID();
        server.expect(once(), requestTo("http://inventory/api/v1/products/" + productId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id":"%s","sku":"SKU-1","name":"Widget","availableQuantity":8,
                         "unitPrice":12.50,"currency":"USD"}
                        """.formatted(productId), MediaType.APPLICATION_JSON));

        InventoryClient.ProductSnapshot product = inventoryClient.findProduct(productId);

        assertThat(product.unitPrice()).isEqualByComparingTo("12.50");
        assertThat(product.availableQuantity()).isEqualTo(8);
        server.verify();
    }

    @Test
    void sendsTheInventoryReservationContract() {
        UUID productId = UUID.randomUUID();
        server.expect(once(), requestTo("http://inventory/api/v1/products/" + productId + "/reserve"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"quantity\":3}"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        inventoryClient.reserve(productId, 3);

        server.verify();
    }

    @Test
    void translatesInventoryConflictsToCheckoutRejection() {
        UUID productId = UUID.randomUUID();
        server.expect(requestTo("http://inventory/api/v1/products/" + productId + "/reserve"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.CONFLICT));

        assertThatThrownBy(() -> inventoryClient.reserve(productId, 99))
                .isInstanceOf(CheckoutRejectedException.class);
    }
}
