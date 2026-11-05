CREATE SCHEMA IF NOT EXISTS iot;

CREATE TABLE IF NOT EXISTS iot.telemetry_reading (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    tenant_id VARCHAR(80) NOT NULL,
    device_id VARCHAR(120) NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    temperature_c DOUBLE PRECISION,
    pressure_bar DOUBLE PRECISION,
    vibration_mm_s DOUBLE PRECISION,
    rpm DOUBLE PRECISION,
    voltage DOUBLE PRECISION,
    current_amps DOUBLE PRECISION,
    runtime_hours DOUBLE PRECISION,
    error_code VARCHAR(80),
    CONSTRAINT uk_telemetry_event_id UNIQUE (event_id)
);

CREATE INDEX IF NOT EXISTS idx_telemetry_device_recorded
    ON iot.telemetry_reading (tenant_id, device_id, recorded_at DESC);

CREATE TABLE IF NOT EXISTS iot.alert (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    tenant_id VARCHAR(80) NOT NULL,
    device_id VARCHAR(120) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    type VARCHAR(80) NOT NULL,
    message VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_alert_device_created
    ON iot.alert (tenant_id, device_id, created_at DESC);
