package com.example.platform.iot.model;

import com.example.platform.iot.event.TelemetryEvent;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "telemetry_reading", schema = "iot",
        uniqueConstraints = @UniqueConstraint(name = "uk_telemetry_event_id", columnNames = "event_id"),
        indexes = @Index(name = "idx_telemetry_device_recorded", columnList = "tenant_id,device_id,recorded_at"))
public class TelemetryReading {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "tenant_id", nullable = false, length = 80)
    private String tenantId;

    @Column(name = "device_id", nullable = false, length = 120)
    private String deviceId;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "temperature_c")
    private Double temperatureC;

    @Column(name = "pressure_bar")
    private Double pressureBar;

    @Column(name = "vibration_mm_s")
    private Double vibrationMmS;

    private Double rpm;
    private Double voltage;

    @Column(name = "current_amps")
    private Double currentAmps;

    @Column(name = "runtime_hours")
    private Double runtimeHours;

    @Column(name = "error_code", length = 80)
    private String errorCode;

    protected TelemetryReading() {}

    private TelemetryReading(TelemetryEvent e) {
        this.eventId = e.eventId();
        this.tenantId = e.tenantId();
        this.deviceId = e.deviceId();
        this.recordedAt = e.recordedAt();
        this.temperatureC = e.temperatureC();
        this.pressureBar = e.pressureBar();
        this.vibrationMmS = e.vibrationMmS();
        this.rpm = e.rpm();
        this.voltage = e.voltage();
        this.currentAmps = e.currentAmps();
        this.runtimeHours = e.runtimeHours();
        this.errorCode = e.errorCode();
    }

    public static TelemetryReading from(TelemetryEvent e) { return new TelemetryReading(e); }

    public UUID getEventId() { return eventId; }
    public String getTenantId() { return tenantId; }
    public String getDeviceId() { return deviceId; }
    public Instant getRecordedAt() { return recordedAt; }
    public Double getTemperatureC() { return temperatureC; }
    public Double getPressureBar() { return pressureBar; }
    public Double getVibrationMmS() { return vibrationMmS; }
    public Double getRpm() { return rpm; }
    public Double getVoltage() { return voltage; }
    public Double getCurrentAmps() { return currentAmps; }
    public Double getRuntimeHours() { return runtimeHours; }
    public String getErrorCode() { return errorCode; }
}
