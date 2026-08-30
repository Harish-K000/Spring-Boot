package com.example.platform.orders.service;

import com.example.platform.orders.client.InventoryClient;
import com.example.platform.orders.dto.CheckoutItemRequest;
import com.example.platform.orders.dto.CheckoutRequest;
import com.example.platform.orders.dto.OrderResponse;
import com.example.platform.orders.exception.CheckoutRejectedException;
import com.example.platform.orders.exception.IdempotencyConflictException;
import com.example.platform.orders.mapper.OrderMapper;
import com.example.platform.orders.model.Order;
import com.example.platform.orders.model.OrderItem;
import com.example.platform.orders.model.OrderStatus;
import com.example.platform.orders.outbox.OutboxEventRecorder;
import com.example.platform.orders.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CheckoutServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private InventoryClient inventoryClient;
    @Mock private OutboxEventRecorder outbox;

    private CheckoutService checkoutService;

    @BeforeEach
    void setUp() {
        checkoutService = new CheckoutService(
                orderRepository, new OrderMapper(), inventoryClient, outbox);
    }

    @Test
    void checkoutPricesCatalogItemsAndReservesInStableOrder() {
        UUID userId = UUID.randomUUID();
        UUID firstId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID secondId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        given(orderRepository.findByIdempotencyKey("key-1")).willReturn(Optional.empty());
        given(inventoryClient.findProduct(firstId)).willReturn(product(firstId, "A", "3.25"));
        given(inventoryClient.findProduct(secondId)).willReturn(product(secondId, "B", "10.00"));
        given(orderRepository.saveAndFlush(any(Order.class))).willAnswer(inv -> inv.getArgument(0));

        // Deliberately reversed: checkout sorts IDs before taking remote row locks.
        CheckoutRequest request = request(new CheckoutItemRequest(secondId, 2),
                new CheckoutItemRequest(firstId, 3));
        OrderResponse response = checkoutService.checkout(userId, "key-1", request);

        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.totalAmount()).isEqualByComparingTo("29.75");
        assertThat(response.currency()).isEqualTo("USD");
        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).lineTotal()).isEqualByComparingTo("9.75");

        InOrder order = inOrder(inventoryClient);
        order.verify(inventoryClient).findProduct(firstId);
        order.verify(inventoryClient).reserve(firstId, 3);
        order.verify(inventoryClient).findProduct(secondId);
        order.verify(inventoryClient).reserve(secondId, 2);
    }

    @Test
    void idempotentReplayReturnsExistingOrderWithoutInventoryCalls() {
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        CheckoutRequest request = request(new CheckoutItemRequest(productId, 2));
        given(orderRepository.findByIdempotencyKey("replay")).willReturn(Optional.empty());
        given(inventoryClient.findProduct(productId)).willReturn(product(productId, "SKU", "5.00"));
        given(orderRepository.saveAndFlush(any(Order.class))).willAnswer(inv -> inv.getArgument(0));

        checkoutService.checkout(userId, "replay", request);
        ArgumentCaptor<Order> savedOrder = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).saveAndFlush(savedOrder.capture());
        Order saved = savedOrder.getValue();
        clearInvocations(inventoryClient);
        given(orderRepository.findByIdempotencyKey("replay")).willReturn(Optional.of(saved));

        OrderResponse replay = checkoutService.checkout(userId, "replay", request);

        assertThat(replay.id()).isEqualTo(saved.getId());
        verifyNoInteractions(inventoryClient);
    }

    @Test
    void reuseOfKeyForDifferentRequestIsRejected() {
        Order existing = new Order();
        existing.setUserId(UUID.randomUUID());
        existing.setCheckoutFingerprint("different");
        given(orderRepository.findByIdempotencyKey("same-key")).willReturn(Optional.of(existing));

        assertThatThrownBy(() -> checkoutService.checkout(existing.getUserId(), "same-key",
                request(new CheckoutItemRequest(UUID.randomUUID(), 1))))
                .isInstanceOf(IdempotencyConflictException.class);

        verifyNoInteractions(inventoryClient);
    }

    @Test
    void duplicateProductsAreRejectedBeforeInventoryCalls() {
        UUID productId = UUID.randomUUID();

        assertThatThrownBy(() -> checkoutService.checkout(UUID.randomUUID(), "duplicates",
                request(new CheckoutItemRequest(productId, 1),
                        new CheckoutItemRequest(productId, 2))))
                .isInstanceOf(CheckoutRejectedException.class)
                .hasMessageContaining("only once");

        verifyNoInteractions(inventoryClient);
    }

    @Test
    void monetaryOverflowIsRejectedBeforeReservation() {
        UUID productId = UUID.randomUUID();
        given(orderRepository.findByIdempotencyKey("large")).willReturn(Optional.empty());
        given(inventoryClient.findProduct(productId)).willReturn(
                product(productId, "LARGE", "9999999999.99"));

        assertThatThrownBy(() -> checkoutService.checkout(UUID.randomUUID(), "large",
                request(new CheckoutItemRequest(productId, 2))))
                .isInstanceOf(CheckoutRejectedException.class)
                .hasMessageContaining("total is too large");

        verify(inventoryClient, never()).reserve(any(), anyInt());
    }

    @Test
    void failedSecondReservationReleasesFirstReservation() {
        UUID firstId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID secondId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        given(orderRepository.findByIdempotencyKey("failure")).willReturn(Optional.empty());
        given(inventoryClient.findProduct(firstId)).willReturn(product(firstId, "A", "3.00"));
        given(inventoryClient.findProduct(secondId)).willReturn(product(secondId, "B", "4.00"));
        doAnswer(invocation -> {
            if (secondId.equals(invocation.getArgument(0))) {
                throw new CheckoutRejectedException("out of stock");
            }
            return null;
        }).when(inventoryClient).reserve(any(UUID.class), anyInt());

        assertThatThrownBy(() -> checkoutService.checkout(UUID.randomUUID(), "failure",
                request(new CheckoutItemRequest(firstId, 1), new CheckoutItemRequest(secondId, 1))))
                .isInstanceOf(CheckoutRejectedException.class);

        verify(inventoryClient).release(firstId, 1);
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    void persistenceFailureReleasesEveryReservation() {
        UUID productId = UUID.randomUUID();
        given(orderRepository.findByIdempotencyKey("race")).willReturn(Optional.empty());
        given(inventoryClient.findProduct(productId)).willReturn(product(productId, "A", "3.00"));
        given(orderRepository.saveAndFlush(any(Order.class)))
                .willThrow(new DataIntegrityViolationException("unique key"));

        assertThatThrownBy(() -> checkoutService.checkout(UUID.randomUUID(), "race",
                request(new CheckoutItemRequest(productId, 1))))
                .isInstanceOf(IdempotencyConflictException.class);

        verify(inventoryClient).release(productId, 1);
    }

    @Test
    void cancelReleasesItemsAndIsIdempotent() {
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        Order order = checkoutOrder(userId, productId, 2);
        given(orderRepository.findByIdForUpdate(order.getId())).willReturn(Optional.of(order));
        given(orderRepository.save(order)).willReturn(order);

        OrderResponse cancelled = checkoutService.cancel(order.getId(), userId);
        OrderResponse replay = checkoutService.cancel(order.getId(), userId);

        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(replay.status()).isEqualTo(OrderStatus.CANCELLED);
        verify(inventoryClient, times(1)).release(productId, 2);
    }

    @Test
    void cancellationFailureRestoresAlreadyReleasedReservations() {
        UUID userId = UUID.randomUUID();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        Order order = checkoutOrder(userId, firstId, 1);
        addItem(order, secondId, 2);
        given(orderRepository.findByIdForUpdate(order.getId())).willReturn(Optional.of(order));
        doAnswer(invocation -> {
            if (secondId.equals(invocation.getArgument(0))) {
                throw new CheckoutRejectedException("release failed");
            }
            return null;
        }).when(inventoryClient).release(any(UUID.class), anyInt());

        assertThatThrownBy(() -> checkoutService.cancel(order.getId(), userId))
                .isInstanceOf(CheckoutRejectedException.class);

        verify(inventoryClient).reserve(firstId, 1);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void paymentCallbackMarksPendingOrderPaidAndIsIdempotent() {
        UUID userId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Order order = checkoutOrder(userId, UUID.randomUUID(), 1);
        given(orderRepository.findByIdForUpdate(order.getId())).willReturn(Optional.of(order));

        checkoutService.markPaid(order.getId(), paymentId);
        checkoutService.markPaid(order.getId(), paymentId);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getPaymentId()).isEqualTo(paymentId);
        verify(orderRepository, times(1)).save(order);
    }

    @Test
    void refundedPaymentReleasesPaidOrderInventory() {
        UUID userId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        Order order = checkoutOrder(userId, productId, 2);
        order.setPaymentId(paymentId);
        order.setStatus(OrderStatus.PAID);
        given(orderRepository.findByIdForUpdate(order.getId())).willReturn(Optional.of(order));
        given(orderRepository.save(order)).willReturn(order);

        checkoutService.markRefunded(order.getId(), paymentId);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(inventoryClient).release(productId, 2);
    }

    @Test
    void compensatingRefundCanCancelOrderWhenPaidCallbackNeverLanded() {
        UUID userId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        Order order = checkoutOrder(userId, productId, 1);
        given(orderRepository.findByIdForUpdate(order.getId())).willReturn(Optional.of(order));
        given(orderRepository.save(order)).willReturn(order);

        checkoutService.markRefunded(order.getId(), paymentId);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getPaymentId()).isEqualTo(paymentId);
        verify(inventoryClient).release(productId, 1);
    }

    private InventoryClient.ProductSnapshot product(UUID id, String sku, String price) {
        return new InventoryClient.ProductSnapshot(id, sku, "Product " + sku, 10,
                new BigDecimal(price), "USD");
    }

    private CheckoutRequest request(CheckoutItemRequest... items) {
        return new CheckoutRequest(List.of(items));
    }

    private Order checkoutOrder(UUID userId, UUID productId, int quantity) {
        Order order = new Order();
        order.setUserId(userId);
        order.setStatus(OrderStatus.PENDING);
        order.setCurrency("USD");
        order.setTotalAmount(new BigDecimal("5.00"));
        order.setCheckoutFingerprint("fingerprint");
        addItem(order, productId, quantity);
        return order;
    }

    private void addItem(Order order, UUID productId, int quantity) {
        OrderItem item = new OrderItem();
        item.setProductId(productId);
        item.setSku(productId.toString());
        item.setProductName("Product");
        item.setQuantity(quantity);
        item.setUnitPrice(new BigDecimal("5.00"));
        item.setLineTotal(new BigDecimal("5.00").multiply(BigDecimal.valueOf(quantity)));
        order.addItem(item);
    }
}
