# Orders and Checkout Service

Phase 5 owns order records and coordinates checkout on port `8082`. External traffic reaches it
through the Edge Gateway at `/api/v1/orders`; the direct service port and Inventory port must stay
private to the deployment network.

## Checkout

Create a checkout with the authenticated user identity supplied by the gateway and a caller-stable
idempotency key:

```http
POST /api/v1/orders/checkout
Authorization: Bearer <access-token>
Idempotency-Key: cart-2026-08-29-001
Content-Type: application/json

{"items":[{"productId":"00000000-0000-0000-0000-000000000001","quantity":2}]}
```

The gateway replaces `X-User-Id` from the validated JWT. Orders does not accept a checkout user ID
from the request body.

Checkout performs the following operation:

1. Rejects duplicate products and sorts product IDs to keep lock acquisition deterministic.
2. Reads authoritative product names, SKUs, prices, and currencies from Inventory.
3. Reserves each requested quantity.
4. Computes line totals and the order total server-side.
5. Stores an immutable item/pricing snapshot with a `PENDING` order.

Repeating the same `Idempotency-Key`, user, and item set returns the original order without making
another reservation. Reusing the key for different contents returns `409 Conflict`.

If a later product cannot be reserved or the order cannot be persisted, Orders attempts to release
earlier reservations in reverse order. `POST /api/v1/orders/{id}/cancel` is idempotent, verifies
ownership from `X-User-Id`, releases every reservation, and then changes the order to `CANCELLED`.

Payments uses the private `/internal/v1/orders/{id}` contract to load the authoritative payable
amount and to report paid or refunded outcomes. Paid callbacks link a unique payment ID and move the
order to `PAID`. Refunded callbacks are idempotent and release reserved stock before cancellation,
including compensation when a capture succeeded but the paid callback did not arrive. This internal
prefix is intentionally absent from the Edge Gateway routes.

Each committed checkout lifecycle change also records an outbox event in the same transaction:
`ORDER_CREATED`, `ORDER_PAID`, `ORDER_CANCELLED`, or `ORDER_REFUNDED`. Phase 7's worker publishes
these records to Redis; replaying an idempotent request does not create another event.

## Data and API behavior

Order responses include item snapshots with `productId`, SKU, product name, quantity, unit price,
and line total. Database constraints enforce positive quantities and prices, nonnegative totals,
one product line per order, and unique idempotency keys.

Existing order read/list and administrative lifecycle endpoints remain under `/api/v1/orders`.
Checkout-created orders must use checkout-specific lifecycle endpoints so inventory compensation
cannot be bypassed.

Inventory dependency configuration:

```text
INVENTORY_SERVICE_URI=http://localhost:8083
```

The client uses a one-second connection timeout and two-second response timeout. Catalog reads may
retry once with exponential backoff; reserve and release writes are never automatically retried.
An `inventory` circuit breaker prevents a sustained Inventory outage from consuming request threads.

## Verify

```sh
./mvnw -pl services/orders-service,services/inventory-service -am clean test
```

The always-on H2 tests execute the production Flyway migrations. PostgreSQL 15 Testcontainers
migration tests run as well when Docker is available.
