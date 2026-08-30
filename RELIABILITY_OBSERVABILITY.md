# Reliability and Observability

Phase 8 standardizes failure handling and telemetry across every service.

## Runtime signals

Every service exposes:

- `/livez` and `/actuator/health/liveness` for process liveness;
- `/readyz` and `/actuator/health/readiness` for traffic readiness;
- `/actuator/metrics` for diagnostic meter inspection;
- `/actuator/prometheus` for Prometheus scraping.

Liveness intentionally excludes PostgreSQL, Redis, and downstream HTTP services so an external
outage does not trigger a restart cascade. Readiness includes the local database for stateful
services and both PostgreSQL and Redis for the worker. Health details remain hidden.

Prometheus meters carry an `application` tag. Important platform meters include:

| Meter | Meaning |
| --- | --- |
| `platform.outbox.pending` | Unpublished events by source |
| `platform.outbox.oldest.age` | Age of the oldest unpublished event |
| `platform.outbox.relay` | Published and failed relay attempts |
| `platform.events.consumed` | Processed, duplicate, failed, and dead-lettered events |
| `platform.orders.checkout` | Checkout latency and errors |
| `platform.payments.process` | Payment coordination latency and errors |
| `resilience4j.circuitbreaker.*` | Downstream circuit state and call outcomes |
| `http.server.requests` / `http.client.requests` | HTTP latency and status outcomes |

## Correlation, logs, and traces

The gateway validates or creates `X-Request-Id`; servlet services return it, place it in MDC, and
propagate it on `RestClient` calls. Access logs contain request ID, method, path, status, and elapsed
milliseconds without logging request bodies, credentials, or query strings.

Production console logs default to Logstash JSON. `LOG_FORMAT` can select another Spring Boot
structured format such as `ecs` or `gelf`. Micrometer OpenTelemetry tracing emits W3C trace context across
the gateway and auto-configured HTTP clients. Ten percent is sampled by default. OTLP export is
disabled until a collector is configured:

```text
TRACE_SAMPLING_PROBABILITY=0.1
OTEL_EXPORT_ENABLED=true
OTEL_EXPORT_ENDPOINT=http://otel-collector:4318/v1/traces
LOG_FORMAT=logstash
```

## Failure policy

All synchronous service calls have one-second connection and two-second response timeouts.
Inventory catalog reads retry once with exponential backoff. Inventory reserve/release writes do
not retry because the current stock contract has no request idempotency key. Orders reads and
payment callbacks retry once because those callback contracts are idempotent. Circuit breakers use
a 20-call window, open at 50% failures after at least 10 calls, wait 10 seconds, then admit three
half-open probes.

The gateway uses an eight-second circuit timeout and retries only `GET` requests once for
`502`, `503`, or `504`. It never automatically retries mutations or payment requests.

The worker reclaims Redis pending entries from any consumer after the configured idle interval.
A processing failure remains pending for retry; after five deliveries it is copied to
`platform:domain-events:dead-letter` before the original entry is acknowledged. Event IDs and the
worker database ledger preserve idempotency.

All HTTP services use graceful shutdown with a 20-second drain window. Database connection
acquisition fails within three seconds, preventing request threads from waiting indefinitely on an
exhausted or unavailable pool.

## Suggested alerts

- readiness is down for five minutes;
- HTTP 5xx ratio exceeds 5% for five minutes;
- p95 HTTP latency exceeds the service budget;
- a circuit breaker remains open for two minutes;
- `platform.outbox.oldest.age` exceeds 60 seconds;
- any increase in `platform.events.consumed{outcome="dead_lettered"}`;
- database pool pending connections remain nonzero;
- worker Redis or database readiness is down.

## Verification

```sh
./mvnw clean test
curl http://localhost:8080/livez
curl http://localhost:8085/readyz
curl http://localhost:8085/actuator/prometheus
```

Gateway and Auth management endpoints other than health/info remain protected by their security
chains. Scrape those endpoints with a valid bearer token or expose them only through a separately
secured management network in production. Never publish internal service or worker ports directly.
