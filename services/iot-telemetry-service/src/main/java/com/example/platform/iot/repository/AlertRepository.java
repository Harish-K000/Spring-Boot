package com.example.platform.iot.repository;

import com.example.platform.iot.model.Alert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AlertRepository extends JpaRepository<Alert, UUID> {
    List<Alert> findTop50ByTenantIdAndDeviceIdOrderByCreatedAtDesc(String tenantId, String deviceId);
}
