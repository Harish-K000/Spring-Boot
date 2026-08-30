package com.example.platform.orders.service;

import com.example.platform.orders.client.InventoryClient;
import com.example.platform.orders.dto.CheckoutItemRequest;
import com.example.platform.orders.dto.CheckoutRequest;
import com.example.platform.orders.dto.OrderResponse;
import com.example.platform.orders.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
class CheckoutIntegrationTest {

    @Autowired private CheckoutService checkoutService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private InventoryClient inventoryClient;

    @Test
    void checkoutPersistsItemSnapshotAndReplayDoesNotReserveTwice() {
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        CheckoutRequest request = new CheckoutRequest(
                List.of(new CheckoutItemRequest(productId, 2)));
        given(inventoryClient.findProduct(productId)).willReturn(
                new InventoryClient.ProductSnapshot(productId, "SKU-1", "Widget", 7,
                        new BigDecimal("12.50"), "USD"));

        OrderResponse created = checkoutService.checkout(userId, "integration-key", request);
        OrderResponse replay = checkoutService.checkout(userId, "integration-key", request);

        assertThat(created.totalAmount()).isEqualByComparingTo("25.00");
        assertThat(created.items()).singleElement().satisfies(item -> {
            assertThat(item.productId()).isEqualTo(productId);
            assertThat(item.quantity()).isEqualTo(2);
            assertThat(item.lineTotal()).isEqualByComparingTo("25.00");
        });
        assertThat(replay.id()).isEqualTo(created.id());
        assertThat(orderRepository.findById(created.id())).isPresent();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events "
                        + "WHERE aggregate_id = ? AND event_type = 'ORDER_CREATED'",
                Long.class, created.id())).isEqualTo(1);
        verify(inventoryClient, times(1)).reserve(productId, 2);
    }
}
