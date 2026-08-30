package com.example.platform.orders.service;

import com.example.platform.orders.dto.CreateOrderRequest;
import com.example.platform.orders.dto.OrderResponse;
import com.example.platform.orders.dto.PageResponse;
import com.example.platform.orders.dto.UpdateOrderRequest;
import com.example.platform.orders.exception.InvalidOrderStateException;
import com.example.platform.orders.exception.CheckoutManagedOrderException;
import com.example.platform.orders.exception.OrderNotFoundException;
import com.example.platform.orders.mapper.OrderMapper;
import com.example.platform.orders.model.Order;
import com.example.platform.orders.model.OrderStatus;
import com.example.platform.orders.repository.OrderRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Business rules and transaction boundaries for orders. Controllers never touch the repository. */
@Service
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;

    public OrderService(OrderRepository orderRepository, OrderMapper orderMapper) {
        this.orderRepository = orderRepository;
        this.orderMapper = orderMapper;
    }

    @Transactional
    public OrderResponse create(CreateOrderRequest req) {
        Order order = orderMapper.toEntity(req);
        order.setStatus(OrderStatus.PENDING);
        return orderMapper.toResponse(orderRepository.save(order));
    }

    public OrderResponse findById(UUID id) {
        return orderMapper.toResponse(getOrThrow(id));
    }

    public PageResponse<OrderResponse> findAll(UUID userId, OrderStatus status, Pageable pageable) {
        Page<Order> page;
        if (userId != null && status != null) {
            page = orderRepository.findByUserIdAndStatus(userId, status, pageable);
        } else if (userId != null) {
            page = orderRepository.findByUserId(userId, pageable);
        } else if (status != null) {
            page = orderRepository.findByStatus(status, pageable);
        } else {
            page = orderRepository.findAll(pageable);
        }
        return PageResponse.from(page, orderMapper::toResponse);
    }

    @Transactional
    public OrderResponse update(UUID id, UpdateOrderRequest req) {
        Order order = getForUpdateOrThrow(id);

        if (order.getCheckoutFingerprint() != null) {
            throw new CheckoutManagedOrderException(
                    "Checkout orders must use their lifecycle-specific endpoints");
        }

        if (order.getStatus() != req.status() && !order.getStatus().canTransitionTo(req.status())) {
            throw new InvalidOrderStateException(order.getStatus(), req.status());
        }
        order.setStatus(req.status());
        if (req.totalAmount() != null) {
            order.setTotalAmount(req.totalAmount());
        }
        return orderMapper.toResponse(orderRepository.save(order));
    }

    @Transactional
    public void delete(UUID id) {
        Order order = getForUpdateOrThrow(id);
        if (order.getCheckoutFingerprint() != null) {
            throw new CheckoutManagedOrderException("Checkout orders are retained as audit records");
        }
        orderRepository.delete(order);
    }

    private Order getOrThrow(UUID id) {
        return orderRepository.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
    }

    private Order getForUpdateOrThrow(UUID id) {
        return orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }
}
