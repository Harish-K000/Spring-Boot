package com.example.platform.orders.repository;

import com.example.platform.orders.model.Order;
import com.example.platform.orders.model.OrderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class OrderRepositoryTest {

    @Autowired
    private OrderRepository orderRepository;

    private Order persist(UUID userId, OrderStatus status) {
        Order order = new Order();
        order.setUserId(userId);
        order.setStatus(status);
        order.setCurrency("USD");
        order.setTotalAmount(new BigDecimal("25.00"));
        return orderRepository.saveAndFlush(order);
    }

    @Test
    void savedOrderRoundTripsWithDefaults() {
        UUID userId = UUID.randomUUID();
        Order saved = persist(userId, OrderStatus.PENDING);

        Order found = orderRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getUserId()).isEqualTo(userId);
        assertThat(found.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(found.getTotalAmount()).isEqualByComparingTo("25.00");
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void findByUserIdReturnsOnlyThatUsersOrders() {
        UUID userId = UUID.randomUUID();
        persist(userId, OrderStatus.PENDING);
        persist(userId, OrderStatus.PAID);
        persist(UUID.randomUUID(), OrderStatus.PENDING);

        Page<Order> page = orderRepository.findByUserId(userId, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).allMatch(o -> o.getUserId().equals(userId));
    }

    @Test
    void findByStatusFilters() {
        persist(UUID.randomUUID(), OrderStatus.SHIPPED);
        persist(UUID.randomUUID(), OrderStatus.PENDING);

        assertThat(orderRepository.findByStatus(OrderStatus.SHIPPED, PageRequest.of(0, 10)).getTotalElements())
                .isEqualTo(1);
    }

    @Test
    void findByUserIdAndStatusCombinesBothFilters() {
        UUID userId = UUID.randomUUID();
        persist(userId, OrderStatus.PAID);
        persist(userId, OrderStatus.PENDING);
        persist(UUID.randomUUID(), OrderStatus.PAID);

        Page<Order> page = orderRepository.findByUserIdAndStatus(
                userId, OrderStatus.PAID, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void pagingLimitsResults() {
        UUID userId = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            persist(userId, OrderStatus.PENDING);
        }

        Page<Order> firstPage = orderRepository.findByUserId(userId, PageRequest.of(0, 2));

        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(firstPage.getTotalElements()).isEqualTo(5);
        assertThat(firstPage.getTotalPages()).isEqualTo(3);
        assertThat(firstPage.isLast()).isFalse();
    }
}
