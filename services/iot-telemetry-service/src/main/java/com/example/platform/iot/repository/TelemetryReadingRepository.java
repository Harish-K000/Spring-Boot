package com.example.platform.iot.repository;

import com.example.platform.iot.model.TelemetryReading;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface TelemetryReadingRepository extends JpaRepository<TelemetryReading, UUID> {

    @Modifying
    @Query(value = """
            INSERT INTO iot.telemetry_reading (
                id, event_id, tenant_id, device_id, recorded_at,
                temperature_c, pressure_bar, vibration_mm_s, rpm,
                voltage, current_amps, runtime_hours, error_code
            ) VALUES (
                :id, :eventId, :tenantId, :deviceId, :recordedAt,
                :temperatureC, :pressureBar, :vibrationMmS, :rpm,
                :voltage, :currentAmps, :runtimeHours, :errorCode
            )
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("eventId") UUID eventId,
            @Param("tenantId") String tenantId,
            @Param("deviceId") String deviceId,
            @Param("recordedAt") Instant recordedAt,
            @Param("temperatureC") Double temperatureC,
            @Param("pressureBar") Double pressureBar,
            @Param("vibrationMmS") Double vibrationMmS,
            @Param("rpm") Double rpm,
            @Param("voltage") Double voltage,
            @Param("currentAmps") Double currentAmps,
            @Param("runtimeHours") Double runtimeHours,
            @Param("errorCode") String errorCode);

    Optional<TelemetryReading> findFirstByTenantIdAndDeviceIdOrderByRecordedAtDesc(
            String tenantId, String deviceId);
}
