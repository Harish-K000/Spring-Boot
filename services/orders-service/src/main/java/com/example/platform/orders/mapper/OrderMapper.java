package com.example.platform.orders.mapper;

import com.example.platform.orders.dto.CreateOrderRequest;
import com.example.platform.orders.dto.OrderResponse;
import com.example.platform.orders.dto.OrderItemResponse;
import com.example.platform.orders.model.Order;
import org.springframework.stereotype.Component;

@Component
public class OrderMapper {

    public Order toEntity(CreateOrderRequest req) {
        Order order = new Order();
        order.setUserId(req.userId());
        order.setTotalAmount(req.totalAmount());
        order.setCurrency(req.currency());
        return order;
    }

    public OrderResponse toResponse(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getUserId(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getCurrency(),
                order.getItems().stream()
                        .map(item -> new OrderItemResponse(
                                item.getProductId(), item.getSku(), item.getProductName(),
                                item.getQuantity(), item.getUnitPrice(), item.getLineTotal()))
                        .toList(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }
}
