# Outbox, Redis, and Worker

Phase 7 provides reliable asynchronous delivery for Orders and Payments. Business services write a
domain event to their own `outbox_events` table in the same database transaction as the state
change. The worker claims ready rows, publishes them to the `platform:domain-events` Redis Stream,
and marks each source row published only after Redis accepts it.

## Delivery flow

1. Orders or Payments commits its aggregate and outbox event atomically.
2. The worker claims rows from `orders.outbox_events` and `payments.outbox_events` with
   `FOR UPDATE SKIP LOCKED`. Stale claims are recoverable after the configured timeout.
3. Successful publications receive `published_at`; failures release the claim and use exponential
   backoff, capped at five minutes, while retaining the last error.
4. The Redis consumer group delivers each record to a worker consumer.
5. The worker commits the event ID and audit row in the `worker` schema, then acknowledges Redis.
   A unique event ID makes re-delivery idempotent. Failed processing is not acknowledged and is
   retried after a pending-idle delay. Any worker instance can reclaim entries left by a crashed
   consumer. After the configured delivery limit, poison events are copied to the dead-letter
   stream before the original entry is acknowledged.

This is at-least-once delivery. Consumers must continue to treat event IDs as idempotency keys.
The source outbox remains the durable recovery record if Redis is unavailable.

## Event contract

Every stream record contains `id`, `source`, `aggregateType`, `aggregateId`, `eventType`, `payload`,
and `occurredAt`. The JSON payload is an event-specific snapshot; consumers should use the envelope
for routing and tolerate additive payload fields.

| Source | Events |
| --- | --- |
| Orders | `ORDER_CREATED`, `ORDER_PAID`, `ORDER_CANCELLED`, `ORDER_REFUNDED` |
| Payments | `PAYMENT_CAPTURED`, `PAYMENT_FAILED`, `PAYMENT_REFUNDED` |

## Configuration

```text
REDIS_HOST=localhost
REDIS_PORT=6379
DOMAIN_EVENT_STREAM=platform:domain-events
DOMAIN_EVENT_GROUP=platform-workers
DOMAIN_EVENT_CONSUMER=<stable-instance-name>
OUTBOX_BATCH_SIZE=50
OUTBOX_RELAY_DELAY_MS=1000
OUTBOX_CLAIM_TIMEOUT_SECONDS=60
DOMAIN_EVENT_BATCH_SIZE=25
DOMAIN_EVENT_POLL_DELAY_MS=500
DOMAIN_EVENT_PENDING_IDLE_SECONDS=10
DOMAIN_EVENT_MAX_DELIVERIES=5
DOMAIN_EVENT_DEAD_LETTER_STREAM=platform:domain-events:dead-letter
WORKER_PORT=8085
```

The worker database role needs `SELECT` and `UPDATE` on both source outbox tables and normal DML on
the `worker` schema. It does not need access to Orders or Payments business tables. Use a stable,
unique `DOMAIN_EVENT_CONSUMER` per instance. Stale entries are reclaimed across consumer names, so
an instance name does not need to be reused after a crash.

Worker health and Prometheus endpoints are available on port `8085`. Useful meters are
`platform.outbox.pending`, `platform.outbox.oldest.age`, `platform.outbox.relay`, and
`platform.events.consumed`.

## Run and verify

```sh
docker compose -f infra/docker-compose.yml up -d
```

Then run Orders, Payments, and Worker in separate terminals:

```sh
./mvnw -pl services/orders-service spring-boot:run
./mvnw -pl services/payments-service spring-boot:run
./mvnw -pl services/worker spring-boot:run
```

Run the automated checks with:

```sh
./mvnw -pl services/orders-service,services/payments-service,services/worker -am clean test
```

H2 exercises migrations, relay state, idempotency, and acknowledgement ordering without Docker.
PostgreSQL Testcontainers tests run additionally when Docker is available.
