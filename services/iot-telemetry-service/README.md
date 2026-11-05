# IoT Telemetry Service

Industrial asset-monitoring vertical slice built to generate real engineering evidence for interview preparation.

## Milestone 1

- REST telemetry ingestion with structural and business validation.
- Kafka topic `iot.telemetry.v1`, keyed by `deviceId` to preserve per-device partition ordering.
- PostgreSQL persistence.
- Deterministic threshold alerts for temperature, vibration, pressure, and device error codes.
- Event-id idempotency so repeated Kafka delivery does not create duplicate readings or alerts.
- Read APIs for latest device state and recent alerts.
- Micrometer/Prometheus counters for accepted, persisted, duplicate, failed-publish, and alert events.

## Architecture

```text
IoT gateway / simulator
        |
        | POST /api/v1/telemetry
        v
Spring Boot ingestion
  - request validation
  - timestamp/business validation
  - Kafka broker acknowledgement
        |
        v
Kafka: iot.telemetry.v1
  key = deviceId
        |
        v
Kafka consumer
  - eventId deduplication
  - PostgreSQL persistence
  - threshold evaluation
        |
        +----> iot.telemetry_reading
        |
        +----> iot.alert
```

This milestone intentionally keeps alerting deterministic. Redis current-state caching, load testing, late-event handling, and predictive-maintenance ML come later.

## Run

From the repository root:

```sh
docker compose -f infra/docker-compose.yml up -d postgres kafka
./mvnw -pl services/iot-telemetry-service -am spring-boot:run
```

Service: `http://localhost:8086`

## Send telemetry

Use a fresh timestamp close to the current time:

```sh
curl -i http://localhost:8086/api/v1/telemetry \
  -H 'Content-Type: application/json' \
  -d '{
    "eventId": "8f7f71f3-1aa6-4b7d-b0c7-e93bd79c2d57",
    "tenantId": "plant-a",
    "deviceId": "pump-17",
    "recordedAt": "2026-11-05T20:30:00Z",
    "temperatureC": 91.2,
    "pressureBar": 12.4,
    "vibrationMmS": 14.5,
    "rpm": 1800,
    "voltage": 480,
    "currentAmps": 12.0,
    "runtimeHours": 3200
  }'
```

Expected response: HTTP `202 Accepted`.

After the consumer processes the event:

```sh
curl http://localhost:8086/api/v1/devices/pump-17/latest -H 'X-Tenant-Id: plant-a'
curl http://localhost:8086/api/v1/devices/pump-17/alerts -H 'X-Tenant-Id: plant-a'
```

The sample should create HIGH_TEMPERATURE and HIGH_VIBRATION alerts.

## Test

```sh
./mvnw -pl services/iot-telemetry-service -am test
```

Tests currently cover:
- threshold evaluation,
- empty telemetry rejection,
- future timestamp rejection,
- deviceId Kafka partition key,
- duplicate-event suppression,
- persistence plus alert generation.

## Metrics

Actuator/Prometheus exposes custom counters:

- `iot.telemetry.accepted`
- `iot.telemetry.publish.failures`
- `iot.telemetry.persisted`
- `iot.telemetry.duplicates`
- `iot.alerts.created`

## Next milestone: create a real LP incident

1. Add a load generator and send at least 10k events.
2. Measure API p95 latency, broker publish failures, consumer lag, DB write throughput, duplicate count, and alert volume.
3. Add Redis for latest-state reads and compare read latency against PostgreSQL.
4. Replay late/out-of-order events and define the correct latest-state semantics.
5. Use the real bottleneck or failure we observe as the Amazon Dive Deep / Deliver Results story rather than inventing one.
