# Edge Gateway

Phase 4 makes port `8080` the platform's external authentication boundary. It routes requests,
validates Auth Service access tokens, removes spoofed identity headers, and propagates trusted
identity and request-correlation metadata downstream.

## 4.1 Gateway foundation

The module already uses Spring Cloud Gateway Server WebFlux rather than a
servlet Web application. Its Spring Cloud BOM comes from the repository parent;
the WebFlux gateway starter brings in Reactor Netty. It has no order,
inventory, payment or user-database business logic. The context test checks
that Spring Boot starts a reactive web server, while the route tests check that
gateway route definitions bind. Routing behavior is addressed in Step 4.2.

## 4.2 Basic service routing

The gateway listens on `:8080` and routes these paths without requiring
clients to know the downstream ports:

| Gateway path | Destination | Downstream path |
| --- | --- | --- |
| `/api/v1/auth/**` and `/api/v1/protected/**` | Auth Service `:8081` | unchanged |
| `/api/v1/orders/**` | Orders Service `:8082` | unchanged |
| `/api/v1/products/**` | Inventory Service `:8083` | unchanged |
| `/api/v1/inventory/**` | Inventory Service `:8083` | rewritten to `/api/v1/products/**` |
| `/api/v1/payments/**` | Payments Service `:8084` | unchanged |

Inventory's controller already serves `/api/v1/products`, so the inventory
path from the Phase 4 brief is an alias. The rewrite changes only the path
prefix and preserves nested segments and query parameters. The local route
integration test runs four HTTP stubs behind a real gateway and checks the
service and path each one receives. Its signed test token allows requests to
the protected routes; the actual service databases are not involved.

## Public and protected paths

The following endpoints are public:

- `POST /api/v1/auth/register`
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh`
- `/actuator/health`
- `/actuator/health/**`
- `/livez` and `/readyz`

Every other method and path, including logout, unknown authentication
operations and non-health Actuator endpoints, requires a valid
`Authorization: Bearer <access-token>` header. OAuth login routes can be added
to the exact public allowlist when an OAuth controller is implemented; no such
gateway route exists today. Tokens must use
issuer `backend-platform-auth`, audience `backend-platform-api`, and a nonblank subject. This includes
orders, products, payments, Auth Service protected endpoints, metrics, Prometheus, and gateway
route inspection.

## 4.4 JWT validation

The gateway validates access tokens locally with Spring Security's reactive
resource server. It accepts only HS256 tokens signed with `JWT_SECRET` and
requires the Auth Service contract: issuer `backend-platform-auth`, audience
`backend-platform-api`, a nonblank subject and a non-expired `exp` claim.
Malformed, tampered, expired, wrongly signed or claim-incompatible tokens are
rejected with 401 before routing. Auth Service is not called to validate each
request. This shared-secret design matches the current Auth Service; asymmetric
signing remains a later hardening option.

## 4.5 Role-based access control

JWT roles become Spring authorities with the `ROLE_` prefix. The gateway uses
the following edge policy:

| Operations | Required role |
| --- | --- |
| Order checkout and cancellation | `USER` or `ADMIN` |
| Payment operations | `USER` or `ADMIN` |
| Product/inventory reads | `USER` or `ADMIN` |
| Auth protected endpoint and logout | `USER` or `ADMIN` |
| Generic order CRUD | `ADMIN` |
| Product/inventory mutations | `ADMIN` |
| Non-health Actuator endpoints | `ADMIN` |

A missing or invalid token returns 401. A valid token without the required
role returns 403. Generic order reads are currently ADMIN-only because those
controller methods do not establish ownership from the authenticated subject; customer
checkout and cancellation use the user-aware paths.

## 4.6 Defense-in-depth validation

The gateway forwards the original `Authorization: Bearer <access-token>` header. Orders,
Inventory, and Payments are also Spring Security resource servers and independently verify the
HS256 signature, issuer, audience, expiration, UUID subject, and roles with the same mandatory
`JWT_SECRET`. A request sent directly to one of those service ports is rejected when the token is
missing, malformed, invalid, or lacks the locally required role.

Orders and Payments derive the user UUID from the locally verified JWT subject for owned
operations. They do not use `X-User-Id` as an authorization input. Their synchronous HTTP clients
forward only the token already present in Spring Security's authenticated context, allowing
Orders-to-Inventory and Payments-to-Orders calls to pass the same validation again.

## 4.7 Correlation ID

Every request receives one `X-Correlation-ID`. Values supplied by a client are retained only when
they contain 1–128 letters, digits, dots, underscores, or hyphens; otherwise the gateway generates
a UUID. The gateway installs this filter before Spring Security, so successful responses and early
401/403 responses all return the ID. The same value is forwarded through the gateway and propagated
by servlet `RestClient` calls. `X-Request-Id` is accepted as an input alias for compatibility, but
outbound requests and responses use the canonical `X-Correlation-ID` header.

## Downstream identity headers

After JWT validation, the gateway supplies:

- `X-User-Id`: JWT subject
- `X-User-Email`: authenticated email claim, when present
- `X-User-Roles`: sorted comma-separated roles without the `ROLE_` prefix

Incoming `X-User-*` values are always removed before trusted values are added. Downstream service
ports should still remain private to reduce attack surface. These headers are request metadata;
services derive security decisions from the independently verified JWT.

## Configuration and run

`JWT_SECRET` is mandatory, must contain at least 32 UTF-8 bytes, and must exactly match the Auth
Service secret.

```sh
export JWT_SECRET="$(openssl rand -base64 48)" # For a standalone foundation check.
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./mvnw -pl services/edge-gateway -am package
/opt/homebrew/opt/openjdk@25/bin/java -jar services/edge-gateway/target/edge-gateway-0.0.1-SNAPSHOT.jar
```

When Auth Service is running, configure the gateway, Orders, Inventory, and Payments with the same
`JWT_SECRET`. The standalone command above checks only gateway startup and
health; it does not validate end-to-end login or routing.

Service destinations can be overridden with `AUTH_SERVICE_URI`, `ORDERS_SERVICE_URI`,
`INVENTORY_SERVICE_URI`, and `PAYMENTS_SERVICE_URI`.

Each downstream route has an independent circuit breaker. Only failed `GET` requests are retried,
once, and only for `502`, `503`, or `504`; mutation requests are never retried. The gateway emits
structured access logs and propagates both `X-Correlation-ID` and W3C trace context.

## Verify

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./mvnw -pl services/edge-gateway -am test
```
