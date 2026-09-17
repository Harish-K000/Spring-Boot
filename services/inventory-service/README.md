# Inventory Service

Phase 4 owns the product catalog and stock lifecycle on port `8083`. External clients reach it
through the Edge Gateway at `/api/v1/products`; the service port must remain private to the
deployment network.

## API

| Method | Path | Behavior |
| --- | --- | --- |
| `POST` | `/api/v1/products` | Create a product with an initial physical quantity |
| `GET` | `/api/v1/products/{id}` | Get a product by ID |
| `GET` | `/api/v1/products/by-sku/{sku}` | Get a product by canonical SKU |
| `GET` | `/api/v1/products?name=&page=&size=&sort=` | Search and page products |
| `PUT` | `/api/v1/products/{id}` | Update its name and physical quantity |
| `POST` | `/api/v1/products/{id}/reserve` | Hold available units for a pending order |
| `POST` | `/api/v1/products/{id}/release` | Return reserved units after cancellation |
| `POST` | `/api/v1/products/{id}/fulfill` | Consume reserved and physical units after fulfillment |
| `DELETE` | `/api/v1/products/{id}` | Delete a product only when nothing is reserved |

Stock-changing requests use this body:

```json
{"quantity": 2}
```

SKUs are trimmed and uppercased. Catalog pricing is stored as `unitPrice` plus a three-letter
currency and is used by the Orders checkout flow. `availableQuantity` is derived as
`quantityOnHand - reservedQuantity`; callers cannot set reserved or available quantities directly.
List responses use a stable pagination envelope and cap the requested page size at 100.

Products migrated from the pre-pricing schema receive a `0.00 USD` placeholder and must be updated
with a positive `unitPrice` before they can be checked out.

## Consistency guarantees

Every operation that can conflict with a reservation takes a pessimistic database row lock. This
serializes reserve, release, fulfill, physical-count updates, and deletion for a product, preventing
overselling and lost updates. A unique SKU constraint and database checks additionally guarantee:

- physical quantity is nonnegative;
- reserved quantity is nonnegative;
- reserved quantity never exceeds physical quantity.

Conflicts return `409`, malformed or invalid requests return `400`, and missing products return
`404` using the platform error envelope.

## Run and verify

Start PostgreSQL from the repository root and run the service:

```sh
docker compose -f infra/docker-compose.yml up -d postgres
./mvnw -f services/inventory-service/pom.xml spring-boot:run
```

Database settings use `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, and `DB_PASS`.
`JWT_SECRET` is mandatory and must match Auth Service, the Edge Gateway, Orders, and Payments.
Inventory validates the forwarded bearer token itself; health probes are the only public routes.

Verify Phase 4 with:

```sh
./mvnw -pl services/inventory-service -am clean test
```

The regular suite uses H2 and always validates the real Flyway migrations. A PostgreSQL 15
Testcontainers migration test also runs automatically when Docker is available.
