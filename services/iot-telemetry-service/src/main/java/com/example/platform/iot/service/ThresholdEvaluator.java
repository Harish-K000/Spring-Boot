package com.example.platform.iot.service;

import com.example.platform.iot.event.TelemetryEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ThresholdEvaluator {
    public record AlertSignal(String severity, String type, String message) {}

    public List<AlertSignal> evaluate(TelemetryEvent event) {
        List<AlertSignal> signals = new ArrayList<>();

        if (event.temperatureC() != null && event.temperatureC() > 85.0) {
            signals.add(new AlertSignal("HIGH", "HIGH_TEMPERATURE",
                    "Temperature " + event.temperatureC() + "C exceeded 85C"));
        }
        if (event.vibrationMmS() != null && event.vibrationMmS() > 12.0) {
            signals.add(new AlertSignal("CRITICAL", "HIGH_VIBRATION",
                    "Vibration " + event.vibrationMmS() + " mm/s exceeded 12 mm/s"));
        }
        if (event.pressureBar() != null && event.pressureBar() > 20.0) {
            signals.add(new AlertSignal("HIGH", "HIGH_PRESSURE",
                    "Pressure " + event.pressureBar() + " bar exceeded 20 bar"));
        }
        if (event.errorCode() != null && !event.errorCode().isBlank()) {
            signals.add(new AlertSignal("CRITICAL", "DEVICE_ERROR",
                    "Device reported error code " + event.errorCode()));
        }

        return List.copyOf(signals);
    }
}
