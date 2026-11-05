# Spec 001 — Industrial IoT Telemetry V1

## Problem

Industrial devices emit temperature, pressure, vibration, RPM, voltage, current, runtime-hour and error-code telemetry. The platform needs to accept those readings without coupling device ingestion to downstream processing, preserve enough ordering for per-device analysis, prevent duplicate delivery from creating duplicate state, and generate deterministic alerts for known safety thresholds.

## V1 objective

Deliver one production-style vertical slice:

1. accept telemetry over REST;
2. validate structural and business invariants;
3. publish accepted readings to Kafka;
4. persist telemetry in PostgreSQL;
5. suppress duplicate events using a stable event ID;
6. generate deterministic threshold alerts;
7. expose latest device state and recent alerts;
8. expose metrics that let us measure the system rather than infer behavior.

## Non-goals

V1 does **not** include predictive-maintenance ML, Redis current-state caching, fleet dashboards, device provisioning, MQTT, multi-region deployment, or production AWS infrastructure. Those are later phases.

## Contract

### POST /api/v1/telemetry

Required:
- `eventId`: UUID supplied by the producer; stable across retries.
- `tenantId`: non-blank.
- `deviceId`: non-blank.
- `recordedAt`: device observation time.

Optional measurements:
- `temperatureC`
- `pressureBar`
- `vibrationMmS`
- `rpm`
- `voltage`
- `currentAmps`
- `runtimeHours`
- `errorCode`

At least one measurement or error code must be present.

### Success

The API returns HTTP 202 only after Kafka acknowledges the publish. HTTP 202 means the event was accepted into the asynchronous processing pipeline; it does not mean PostgreSQL persistence or alert generation has completed.

### Failure semantics

- invalid request or business invariant: 400, non-retryable until corrected;
- Kafka unavailable/publish acknowledgement missing: 503; caller retries using the **same eventId**;
- duplicate Kafka delivery: consumer must not create another reading or another alert.

## Ordering

Kafka messages are keyed by `deviceId`. This preserves partition ordering for a device while allowing different devices to process in parallel. V1 does not yet claim correct latest-state behavior for late/out-of-order device timestamps; that is an explicit phase-2 test.

## Idempotency invariant

`eventId` is the identity of a telemetry observation.

Exactly one persisted reading may exist for an event ID. Duplicate transport delivery must be safe. The database uniqueness constraint is the authoritative concurrency boundary; an in-memory existence check alone is insufficient.

## Alert rules

V1 thresholds are intentionally deterministic:
- temperature > 85 C → HIGH_TEMPERATURE / HIGH
- vibration > 12 mm/s → HIGH_VIBRATION / CRITICAL
- pressure > 20 bar → HIGH_PRESSURE / HIGH
- non-empty device error code → DEVICE_ERROR / CRITICAL

These values are demo policy, not claimed industrial safety standards.

## Observability

Required counters:
- accepted telemetry;
- Kafka publish failure;
- persisted telemetry;
- duplicate telemetry;
- alerts created.

Phase 2 adds:
- HTTP p50/p95/p99;
- producer acknowledgement latency;
- Kafka consumer lag;
- DB write throughput;
- latest-state read latency.

## Acceptance criteria

AC1. A structurally valid reading with at least one measurement publishes to Kafka using `deviceId` as key.

AC2. A payload with no measurements/error code is rejected before Kafka.

AC3. A timestamp more than five minutes in the future is rejected before Kafka.

AC4. Kafka publish failure does not return 202.

AC5. A unique event is persisted once.

AC6. Replaying the same `eventId` does not create a second reading or duplicate alerts, including concurrent/retried delivery.

AC7. Temperature/vibration/pressure/error rules generate the documented alerts.

AC8. The latest-state API is tenant-scoped and returns the most recent stored device timestamp.

AC9. Metrics expose accepted, failed-publish, persisted, duplicate and alert counts.

AC10. Maven tests provide repeatable evidence for the implemented invariants.

## Phase 2 experiment

Generate at least 10,000 events and record:
- throughput;
- API p95;
- producer acknowledgement latency;
- Kafka lag;
- DB write rate;
- duplicate count;
- alert count.

Then introduce a concrete stress/failure case and use the measured evidence to decide the next design change.

## Phase 3

Add Redis only if the read workload demonstrates a reason for a current-state cache. Compare PostgreSQL and Redis latest-state latency before keeping the additional component.

## Phase 4 — AWS

Deploy only after local correctness and load evidence are established. Keep cloud infrastructure separable from application semantics.
