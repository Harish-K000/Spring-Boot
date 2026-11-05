package com.example.platform.iot.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "alert", schema = "iot",
        indexes = @Index(name = "idx_alert_device_created", columnList = "tenant_id,device_id,created_at"))
public class Alert {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "tenant_id", nullable = false, length = 80)
    private String tenantId;

    @Column(name = "device_id", nullable = false, length = 120)
    private String deviceId;

    @Column(nullable = false, length = 20)
    private String severity;

    @Column(nullable = false, length = 80)
    private String type;

    @Column(nullable = false, length = 500)
    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Alert() {}

    public Alert(UUID eventId, String tenantId, String deviceId, String severity,
                 String type, String message, Instant createdAt) {
        this.eventId = eventId;
        this.tenantId = tenantId;
        this.deviceId = deviceId;
        this.severity = severity;
        this.type = type;
        this.message = message;
        this.createdAt = createdAt;
    }

    public UUID getEventId() { return eventId; }
    public String getTenantId() { return tenantId; }
    public String getDeviceId() { return deviceId; }
    public String getSeverity() { return severity; }
    public String getType() { return type; }
    public String getMessage() { return message; }
    public Instant getCreatedAt() { return createdAt; }
}
