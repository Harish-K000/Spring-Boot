package com.example.platform.payments.repository;

import com.example.platform.payments.model.PaymentStatus;
import com.example.platform.payments.model.PaymentTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;
import java.util.Optional;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {

    Page<PaymentTransaction> findByOrderId(UUID orderId, Pageable pageable);

    Page<PaymentTransaction> findByStatus(PaymentStatus status, Pageable pageable);

    Page<PaymentTransaction> findByOrderIdAndStatus(UUID orderId, PaymentStatus status, Pageable pageable);

    Page<PaymentTransaction> findByUserId(UUID userId, Pageable pageable);

    Page<PaymentTransaction> findByUserIdAndOrderId(UUID userId, UUID orderId, Pageable pageable);

    Page<PaymentTransaction> findByUserIdAndStatus(UUID userId, PaymentStatus status, Pageable pageable);

    Page<PaymentTransaction> findByUserIdAndOrderIdAndStatus(
            UUID userId, UUID orderId, PaymentStatus status, Pageable pageable);

    boolean existsByOrderIdAndStatusIn(UUID orderId, List<PaymentStatus> statuses);

    Optional<PaymentTransaction> findByIdempotencyKey(String idempotencyKey);
}
