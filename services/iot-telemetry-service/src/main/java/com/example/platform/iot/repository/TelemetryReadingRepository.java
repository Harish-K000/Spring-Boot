package com.example.platform.iot.repository;

import com.example.platform.iot.model.TelemetryReading;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TelemetryReadingRepository extends JpaRepository<TelemetryReading, UUID> {
    boolean existsByEventId(UUID eventId);

    Optional<TelemetryReading> findFirstByTenantIdAndDeviceIdOrderByRecordedAtDesc(
            String tenantId, String deviceId);
}
