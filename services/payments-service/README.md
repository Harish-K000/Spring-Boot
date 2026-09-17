# Payments Service

Phase 6 owns payment attempts and refund coordination on port `8084`. Clients reach it through the
Edge Gateway at `/api/v1/payments`; the Payments port and the Orders internal API must remain private
to the deployment network.

## Process a payment

```http
POST /api/v1/payments/process
Authorization: Bearer <access-token>
Idempotency-Key: payment-2026-08-29-001
Content-Type: application/json

{
  "orderId": "00000000-0000-0000-0000-000000000001",
  "paymentMethodToken": "tok_success"
}
```

Payments validates the bearer token itself and derives the user from its UUID subject. It ignores
`X-User-Id` for authorization. Payments then loads the order from Orders and uses its authoritative
owner, status, total, and currency; callers cannot submit an amount or currency. Payment method
tokens are passed to the provider and are never stored.

The synchronous coordinator writes durable checkpoints around provider authorization and capture,
then links the captured payment to the order through Orders' private API. Both provider calls and the
request itself use stable idempotency keys. Replaying the same key and request returns the existing
terminal payment; changing the user, order, or token returns `409 Conflict`.

If capture succeeds but Orders cannot be updated, Payments attempts an idempotent compensating refund
and tells Orders to cancel and release the reservation. Unknown provider outcomes and incomplete
cross-service synchronization are retained as retryable failure codes instead of being discarded.

Every terminal payment checkpoint atomically records one of `PAYMENT_CAPTURED`, `PAYMENT_FAILED`,
or `PAYMENT_REFUNDED` in the Payments outbox. Phase 7's worker publishes those events to Redis;
idempotent request replays do not create duplicate events.

## API

| Method | Path | Behavior |
| --- | --- | --- |
| `POST` | `/api/v1/payments/process` | Authorize and capture a pending order |
| `GET` | `/api/v1/payments/{id}` | Read an owned payment |
| `GET` | `/api/v1/payments?orderId=&status=&page=&size=` | List the authenticated user's payments |
| `POST` | `/api/v1/payments/{id}/refund` | Refund an owned captured payment and cancel the order |

Provider declines return `402`; validation failures return `400`; ownership is concealed as `404`;
idempotency and lifecycle conflicts return `409`; temporarily unknown provider or Orders outcomes
return `503`. Payment records are audit data and cannot be deleted.

## Provider and service configuration

```text
ORDERS_SERVICE_URI=http://localhost:8082
PAYMENT_PROVIDER=sandbox
JWT_SECRET=<same secret used by Auth Service, Gateway, Orders, and Inventory>
```

The built-in sandbox adapter is deterministic and exists for local development and contract tests.
`tok_declined` simulates a card decline; any other nonblank token succeeds. A real deployment must
provide a production `PaymentProvider` adapter and must not expose the direct service ports.

The Orders client uses one-second connection and two-second response timeouts. Reads and payment
callbacks may retry once because Orders makes those callbacks idempotent. An `orders` circuit
breaker fails fast during a sustained Orders outage; payment-provider operations are not blindly
retried and retain their existing durable unknown-outcome checkpoints.

## Verify

```sh
./mvnw -pl services/payments-service,services/orders-service -am clean test
```

The always-on H2 tests execute the production Flyway migrations. PostgreSQL 15 Testcontainers
migration tests run as well when Docker is available.
