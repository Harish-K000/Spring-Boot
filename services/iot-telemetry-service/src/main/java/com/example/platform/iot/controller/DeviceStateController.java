package com.example.platform.iot.controller;

import com.example.platform.iot.model.Alert;
import com.example.platform.iot.model.TelemetryReading;
import com.example.platform.iot.repository.AlertRepository;
import com.example.platform.iot.repository.TelemetryReadingRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v1/devices")
public class DeviceStateController {
    private final TelemetryReadingRepository telemetryRepository;
    private final AlertRepository alertRepository;

    public DeviceStateController(
            TelemetryReadingRepository telemetryRepository,
            AlertRepository alertRepository) {
        this.telemetryRepository = telemetryRepository;
        this.alertRepository = alertRepository;
    }

    @GetMapping("/{deviceId}/latest")
    public TelemetryReading latest(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable String deviceId) {
        return telemetryRepository
                .findFirstByTenantIdAndDeviceIdOrderByRecordedAtDesc(tenantId, deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No telemetry found"));
    }

    @GetMapping("/{deviceId}/alerts")
    public List<Alert> alerts(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable String deviceId) {
        return alertRepository.findTop50ByTenantIdAndDeviceIdOrderByCreatedAtDesc(tenantId, deviceId);
    }
}
