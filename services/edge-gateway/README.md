# Edge Gateway

Phase 3 makes port `8080` the platform's external authentication boundary. It routes requests,
validates Auth Service access tokens, removes spoofed identity headers, and propagates trusted
identity and request-correlation metadata downstream.

## Public and protected paths

The following endpoints are public:

- `/api/v1/auth/**`
- `/actuator/health`
- `/actuator/health/**`
- `/livez` and `/readyz`
- `/actuator/info`

Every other path requires a valid `Authorization: Bearer <access-token>` header. Tokens must use
issuer `backend-platform-auth`, audience `backend-platform-api`, and a nonblank subject. This includes
orders, products, payments, Auth Service protected endpoints, metrics, Prometheus, and gateway
route inspection.

## Downstream headers

After JWT validation, the gateway supplies:

- `X-User-Id`: JWT subject
- `X-User-Email`: authenticated email claim, when present
- `X-User-Roles`: sorted comma-separated roles without the `ROLE_` prefix
- `X-Request-Id`: a validated caller ID or a generated UUID

Incoming `X-User-*` values are always removed before trusted values are added. Downstream service
ports must remain private to the deployment network; otherwise a caller could bypass the gateway
and supply its own headers directly.

## Configuration and run

`JWT_SECRET` is mandatory, must contain at least 32 UTF-8 bytes, and must exactly match the Auth
Service secret.

```sh
export JWT_SECRET="$(openssl rand -base64 48)"
./mvnw -f services/edge-gateway/pom.xml spring-boot:run
```

Service destinations can be overridden with `AUTH_SERVICE_URI`, `ORDERS_SERVICE_URI`,
`INVENTORY_SERVICE_URI`, and `PAYMENTS_SERVICE_URI`.

Each downstream route has an independent circuit breaker. Only failed `GET` requests are retried,
once, and only for `502`, `503`, or `504`; mutation requests are never retried. The gateway emits
structured access logs and propagates both `X-Request-Id` and W3C trace context.

## Verify

```sh
./mvnw -pl services/edge-gateway -am test
```
