package com.example.platform.iot.controller;

import com.example.platform.iot.dto.TelemetryAcceptedResponse;
import com.example.platform.iot.dto.TelemetryRequest;
import com.example.platform.iot.service.TelemetryIngestionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/telemetry")
public class TelemetryController {
    private final TelemetryIngestionService ingestionService;

    public TelemetryController(TelemetryIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping
    public ResponseEntity<TelemetryAcceptedResponse> ingest(@Valid @RequestBody TelemetryRequest request) {
        var eventId = ingestionService.ingest(request);
        return ResponseEntity.accepted().body(TelemetryAcceptedResponse.accepted(eventId));
    }
}
