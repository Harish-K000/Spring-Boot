package com.example.platform.orders.service;

import com.example.platform.orders.client.InventoryClient;
import com.example.platform.orders.client.InventoryClient.ProductSnapshot;
import com.example.platform.orders.dto.CheckoutItemRequest;
import com.example.platform.orders.dto.CheckoutRequest;
import com.example.platform.orders.dto.OrderResponse;
import com.example.platform.orders.exception.CheckoutManagedOrderException;
import com.example.platform.orders.exception.CheckoutRejectedException;
import com.example.platform.orders.exception.IdempotencyConflictException;
import com.example.platform.orders.exception.InvalidOrderStateException;
import com.example.platform.orders.exception.OrderNotFoundException;
import com.example.platform.orders.mapper.OrderMapper;
import com.example.platform.orders.model.Order;
import com.example.platform.orders.model.OrderItem;
import com.example.platform.orders.model.OrderStatus;
import com.example.platform.orders.outbox.OutboxEventRecorder;
import com.example.platform.orders.repository.OrderRepository;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Coordinates the local order transaction with compensating Inventory operations. */
@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;
    private final InventoryClient inventoryClient;
    private final OutboxEventRecorder outbox;

    public CheckoutService(OrderRepository orderRepository,
                           OrderMapper orderMapper,
                           InventoryClient inventoryClient,
                           OutboxEventRecorder outbox) {
        this.orderRepository = orderRepository;
        this.orderMapper = orderMapper;
        this.inventoryClient = inventoryClient;
        this.outbox = outbox;
    }

    @Transactional
    @Observed(name = "platform.orders.checkout", contextualName = "checkout-order")
    public OrderResponse checkout(UUID userId, String idempotencyKey, CheckoutRequest request) {
        List<CheckoutItemRequest> items = validatedAndSortedItems(request.items());
        String fingerprint = fingerprint(items);

        Order existing = orderRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null) {
            if (!existing.getUserId().equals(userId)
                    || !fingerprint.equals(existing.getCheckoutFingerprint())) {
                throw new IdempotencyConflictException();
            }
            return orderMapper.toResponse(existing);
        }

        List<ReservedItem> reserved = new ArrayList<>();
        try {
            List<CheckoutLine> lines = new ArrayList<>();
            String currency = null;
            BigDecimal total = BigDecimal.ZERO;

            for (CheckoutItemRequest item : items) {
                ProductSnapshot product = inventoryClient.findProduct(item.productId());
                validateCatalogProduct(product, item.productId());
                if (currency == null) {
                    currency = product.currency();
                } else if (!currency.equals(product.currency())) {
                    throw new CheckoutRejectedException(
                            "All checkout products must use the same currency");
                }

                BigDecimal lineTotal = product.unitPrice()
                        .multiply(BigDecimal.valueOf(item.quantity()))
                        .setScale(2);
                if (!fitsMoneyColumn(lineTotal)) {
                    throw new CheckoutRejectedException("A checkout line total is too large");
                }
                inventoryClient.reserve(item.productId(), item.quantity());
                reserved.add(new ReservedItem(item.productId(), item.quantity()));
                lines.add(new CheckoutLine(product, item.quantity(), lineTotal));
                total = total.add(lineTotal);
                if (!fitsMoneyColumn(total)) {
                    throw new CheckoutRejectedException("Checkout total is too large");
                }
            }

            Order order = new Order();
            order.setUserId(userId);
            order.setStatus(OrderStatus.PENDING);
            order.setCurrency(currency);
            order.setTotalAmount(total.setScale(2));
            order.setIdempotencyKey(idempotencyKey);
            order.setCheckoutFingerprint(fingerprint);
            for (CheckoutLine line : lines) {
                OrderItem orderItem = new OrderItem();
                orderItem.setProductId(line.product().id());
                orderItem.setSku(line.product().sku());
                orderItem.setProductName(line.product().name());
                orderItem.setQuantity(line.quantity());
                orderItem.setUnitPrice(line.product().unitPrice());
                orderItem.setLineTotal(line.lineTotal());
                order.addItem(orderItem);
            }
            Order saved = orderRepository.saveAndFlush(order);
            outbox.record(saved.getId(), "ORDER_CREATED", orderPayload(saved));
            return orderMapper.toResponse(saved);
        } catch (DataIntegrityViolationException ex) {
            compensateRelease(reserved, ex);
            throw new IdempotencyConflictException();
        } catch (RuntimeException ex) {
            compensateRelease(reserved, ex);
            throw ex;
        }
    }

    /** Cancellation is idempotent and returns every reservation before changing local state. */
    @Transactional
    @Observed(name = "platform.orders.cancel", contextualName = "cancel-order")
    public OrderResponse cancel(UUID orderId, UUID userId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        if (!order.getUserId().equals(userId)) {
            throw new OrderNotFoundException(orderId);
        }
        return cancelLockedOrder(order, OrderStatus.PENDING, "ORDER_CANCELLED");
    }

    /** Private Payments callback. Repeating the same payment is safe. */
    @Transactional
    @Observed(name = "platform.orders.payment-callback", contextualName = "mark-order-paid")
    public void markPaid(UUID orderId, UUID paymentId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        if (order.getStatus() == OrderStatus.PAID && paymentId.equals(order.getPaymentId())) {
            return;
        }
        if (order.getStatus() != OrderStatus.PENDING || order.getPaymentId() != null) {
            throw new InvalidOrderStateException(order.getStatus(), OrderStatus.PAID);
        }
        order.setPaymentId(paymentId);
        order.setStatus(OrderStatus.PAID);
        orderRepository.save(order);
        outbox.record(order.getId(), "ORDER_PAID", orderPayload(order));
    }

    /** Private Payments callback. A refund restores stock and cancels the paid order. */
    @Transactional
    @Observed(name = "platform.orders.payment-callback", contextualName = "mark-order-refunded")
    public void markRefunded(UUID orderId, UUID paymentId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        if (order.getStatus() == OrderStatus.CANCELLED && paymentId.equals(order.getPaymentId())) {
            return;
        }
        if (order.getStatus() == OrderStatus.PENDING && order.getPaymentId() == null) {
            // A capture was refunded after the paid callback failed before updating this order.
            order.setPaymentId(paymentId);
            cancelLockedOrder(order, OrderStatus.PENDING, "ORDER_REFUNDED");
            return;
        }
        if (!paymentId.equals(order.getPaymentId())) {
            throw new CheckoutManagedOrderException("Payment does not belong to this order");
        }
        cancelLockedOrder(order, OrderStatus.PAID, "ORDER_REFUNDED");
    }

    private OrderResponse cancelLockedOrder(
            Order order, OrderStatus requiredStatus, String eventType) {
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return orderMapper.toResponse(order);
        }
        if (order.getStatus() != requiredStatus) {
            throw new InvalidOrderStateException(order.getStatus(), OrderStatus.CANCELLED);
        }

        List<ReservedItem> released = new ArrayList<>();
        try {
            for (OrderItem item : order.getItems()) {
                inventoryClient.release(item.getProductId(), item.getQuantity());
                released.add(new ReservedItem(item.getProductId(), item.getQuantity()));
            }
        } catch (RuntimeException ex) {
            compensateReserve(released, ex);
            throw ex;
        }
        order.setStatus(OrderStatus.CANCELLED);
        Order saved = orderRepository.save(order);
        outbox.record(saved.getId(), eventType, orderPayload(saved));
        return orderMapper.toResponse(saved);
    }

    private Map<String, Object> orderPayload(Order order) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order.getId());
        payload.put("userId", order.getUserId());
        payload.put("status", order.getStatus().name());
        payload.put("totalAmount", order.getTotalAmount());
        payload.put("currency", order.getCurrency());
        if (order.getPaymentId() != null) {
            payload.put("paymentId", order.getPaymentId());
        }
        return payload;
    }

    private List<CheckoutItemRequest> validatedAndSortedItems(List<CheckoutItemRequest> requested) {
        if (requested == null || requested.isEmpty() || requested.size() > 50) {
            throw new CheckoutRejectedException("Checkout must contain between 1 and 50 products");
        }
        Set<UUID> productIds = new HashSet<>();
        for (CheckoutItemRequest item : requested) {
            if (item == null || item.productId() == null || item.quantity() < 1) {
                throw new CheckoutRejectedException("Every checkout item needs a product and positive quantity");
            }
            if (!productIds.add(item.productId())) {
                throw new CheckoutRejectedException(
                        "Each product may appear only once in a checkout request");
            }
        }
        return requested.stream()
                .sorted(Comparator.comparing(item -> item.productId().toString()))
                .toList();
    }

    private void validateCatalogProduct(ProductSnapshot product, UUID requestedId) {
        if (product == null || product.id() == null || !requestedId.equals(product.id())) {
            throw new CheckoutRejectedException("Inventory returned an invalid product");
        }
        if (product.unitPrice() == null || product.unitPrice().signum() <= 0
                || product.currency() == null || !product.currency().matches("[A-Z]{3}")) {
            throw new CheckoutRejectedException("Product " + product.sku() + " has invalid catalog pricing");
        }
        if (product.unitPrice().stripTrailingZeros().scale() > 2
                || !fitsMoneyColumn(product.unitPrice())) {
            throw new CheckoutRejectedException("Product " + product.sku() + " has invalid catalog pricing");
        }
    }

    private boolean fitsMoneyColumn(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        int fractionalDigits = Math.max(normalized.scale(), 0);
        int integerDigits = Math.max(normalized.precision() - normalized.scale(), 0);
        return fractionalDigits <= 2 && integerDigits <= 10;
    }

    private String fingerprint(List<CheckoutItemRequest> items) {
        String canonical = items.stream()
                .map(item -> item.productId() + ":" + item.quantity())
                .reduce((left, right) -> left + "|" + right)
                .orElseThrow(() -> new CheckoutManagedOrderException("Checkout has no items"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private void compensateRelease(List<ReservedItem> reserved, RuntimeException original) {
        for (int index = reserved.size() - 1; index >= 0; index--) {
            ReservedItem item = reserved.get(index);
            try {
                inventoryClient.release(item.productId(), item.quantity());
            } catch (RuntimeException compensationFailure) {
                log.error("Failed to release product {} while compensating checkout",
                        item.productId(), compensationFailure);
                original.addSuppressed(compensationFailure);
            }
        }
    }

    private void compensateReserve(List<ReservedItem> released, RuntimeException original) {
        for (int index = released.size() - 1; index >= 0; index--) {
            ReservedItem item = released.get(index);
            try {
                inventoryClient.reserve(item.productId(), item.quantity());
            } catch (RuntimeException compensationFailure) {
                log.error("Failed to restore product {} while compensating cancellation",
                        item.productId(), compensationFailure);
                original.addSuppressed(compensationFailure);
            }
        }
    }

    private record CheckoutLine(ProductSnapshot product, int quantity, BigDecimal lineTotal) {}

    private record ReservedItem(UUID productId, int quantity) {}
}
