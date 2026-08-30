package com.example.platform.orders.service;

import com.example.platform.orders.dto.CreateOrderRequest;
import com.example.platform.orders.dto.OrderResponse;
import com.example.platform.orders.dto.UpdateOrderRequest;
import com.example.platform.orders.exception.InvalidOrderStateException;
import com.example.platform.orders.exception.OrderNotFoundException;
import com.example.platform.orders.mapper.OrderMapper;
import com.example.platform.orders.model.Order;
import com.example.platform.orders.model.OrderStatus;
import com.example.platform.orders.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock private OrderRepository orderRepository;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, new OrderMapper());
    }

    private Order orderWithStatus(OrderStatus status) {
        Order order = new Order();
        order.setUserId(UUID.randomUUID());
        order.setCurrency("USD");
        order.setTotalAmount(new BigDecimal("10.00"));
        order.setStatus(status);
        return order;
    }

    @Test
    void createStartsOrderInPendingState() {
        given(orderRepository.save(any(Order.class))).willAnswer(inv -> inv.getArgument(0));

        OrderResponse response = orderService.create(
                new CreateOrderRequest(UUID.randomUUID(), new BigDecimal("49.99"), "USD"));

        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.totalAmount()).isEqualByComparingTo("49.99");
        assertThat(response.currency()).isEqualTo("USD");
    }

    @Test
    void findByIdThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        given(orderRepository.findById(id)).willReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.findById(id))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void updateAppliesLegalTransition() {
        UUID id = UUID.randomUUID();
        Order order = orderWithStatus(OrderStatus.PENDING);
        given(orderRepository.findByIdForUpdate(id)).willReturn(Optional.of(order));
        given(orderRepository.save(any(Order.class))).willAnswer(inv -> inv.getArgument(0));

        OrderResponse response = orderService.update(id, new UpdateOrderRequest(OrderStatus.PAID, null));

        assertThat(response.status()).isEqualTo(OrderStatus.PAID);
        // totalAmount omitted from the request, so the stored value is preserved.
        assertThat(response.totalAmount()).isEqualByComparingTo("10.00");
    }

    @Test
    void updateOverwritesAmountWhenProvided() {
        UUID id = UUID.randomUUID();
        given(orderRepository.findByIdForUpdate(id)).willReturn(Optional.of(orderWithStatus(OrderStatus.PENDING)));
        given(orderRepository.save(any(Order.class))).willAnswer(inv -> inv.getArgument(0));

        OrderResponse response = orderService.update(
                id, new UpdateOrderRequest(OrderStatus.PAID, new BigDecimal("99.50")));

        assertThat(response.totalAmount()).isEqualByComparingTo("99.50");
    }

    @Test
    void updateRejectsIllegalTransition() {
        UUID id = UUID.randomUUID();
        given(orderRepository.findByIdForUpdate(id)).willReturn(Optional.of(orderWithStatus(OrderStatus.DELIVERED)));

        assertThatThrownBy(() -> orderService.update(id, new UpdateOrderRequest(OrderStatus.PENDING, null)))
                .isInstanceOf(InvalidOrderStateException.class);

        verify(orderRepository, never()).save(any());
    }

    @Test
    void updateToSameStatusIsAllowed() {
        UUID id = UUID.randomUUID();
        given(orderRepository.findByIdForUpdate(id)).willReturn(Optional.of(orderWithStatus(OrderStatus.DELIVERED)));
        given(orderRepository.save(any(Order.class))).willAnswer(inv -> inv.getArgument(0));

        assertThat(orderService.update(id, new UpdateOrderRequest(OrderStatus.DELIVERED, null)).status())
                .isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    void deleteThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        given(orderRepository.findByIdForUpdate(id)).willReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.delete(id)).isInstanceOf(OrderNotFoundException.class);
        verify(orderRepository, never()).delete(any());
    }

    @Test
    void cancellationIsAllowedFromPendingAndPaidOnly() {
        assertThat(OrderStatus.PENDING.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
        assertThat(OrderStatus.SHIPPED.canTransitionTo(OrderStatus.CANCELLED)).isFalse();
        assertThat(OrderStatus.DELIVERED.canTransitionTo(OrderStatus.CANCELLED)).isFalse();
    }
}
