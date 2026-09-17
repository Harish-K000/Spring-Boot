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

## 4.3 Public and protected paths

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

## 4.8 Structured request logging

The gateway emits one INFO completion event for every request, including responses produced by
Spring Security. Spring Boot's Logstash console format serializes the event as JSON with the
standard timestamp plus these bounded fields:

| Field | Meaning |
| --- | --- |
| `event` | Stable event name `http_request_completed` |
| `service` | Application name, normally `edge-gateway` |
| `correlationId` | The validated or generated request correlation ID |
| `httpMethod` | HTTP method |
| `httpPath` | Path only; query parameters are excluded |
| `httpStatus` | Final response status |
| `durationMs` | Total gateway processing time in milliseconds |
| `routeId` | Matched downstream route, or `unmatched` |
| `outcome` | Reactor completion signal |

The filter never reads or logs request bodies, query strings, `Authorization`, JWTs, cookies,
passwords, refresh tokens, or payment data. Set `LOG_FORMAT=ecs` for ECS JSON instead of the
default Logstash JSON when integrating with an ECS-compatible log pipeline.

## 4.9 Standard gateway errors

Errors generated at the edge use one JSON contract with `timestamp`, numeric `status`, stable
machine-readable `code`, safe `message`, request `path`, and `correlationId`. The correlation ID is
also returned in the `X-Correlation-ID` response header.

| Situation | Status | Code |
| --- | ---: | --- |
| Authentication is missing | 401 | `AUTHENTICATION_REQUIRED` |
| Bearer token is invalid or expired | 401 | `INVALID_ACCESS_TOKEN` |
| Authenticated principal lacks a required role | 403 | `ACCESS_DENIED` |
| No gateway route matches | 404 | `ROUTE_NOT_FOUND` |
| Sensitive auth request exceeds its limit | 429 | `RATE_LIMIT_EXCEEDED` |
| Downstream connection or discovery fails | 503 | `SERVICE_UNAVAILABLE` |
| Downstream request times out | 504 | `GATEWAY_TIMEOUT` |
| Circuit-breaker downstream failure | 502 | `DOWNSTREAM_FAILURE` |
| Unexpected gateway failure | 500 | `INTERNAL_GATEWAY_ERROR` |

Responses never include stack traces, exception class names, token-validation details, downstream
hostnames, or internal exception messages. Error codes allow clients to branch on a stable value;
the correlation ID lets an operator find the corresponding structured completion log.

## 4.10 CORS

Browser access is configured once at the gateway. The default development allowlist contains only
`http://localhost:3000`. Set `GATEWAY_CORS_ALLOWED_ORIGINS` to a comma-separated list of exact
HTTP(S) origins for other environments, for example:

```sh
export GATEWAY_CORS_ALLOWED_ORIGINS="http://localhost:3000,https://shop.example.com"
```

Wildcard and path-bearing origins fail startup. Configured origins may use credentials and the
`GET`, `POST`, `PUT`, `PATCH`, `DELETE`, and `OPTIONS` methods. Allowed request headers are
`Authorization`, `Content-Type`, `Accept`, and `X-Correlation-ID`; browsers may read the exposed
correlation and rate-limit response headers. Successful preflight requests run before JWT
authentication, while an origin outside the allowlist receives 403 without an
`Access-Control-Allow-Origin` header.

CORS is a browser policy rather than an authentication mechanism. JWT validation and RBAC still
apply to the actual protected request after preflight succeeds.

## 4.11 Timeouts

Every downstream call has bounded waiting times. The gateway allows 2 seconds to establish a TCP
connection and 10 seconds to receive a downstream response. Its circuit-breaker time limiter covers
the complete downstream operation and expires after 8 seconds, before the lower-level HTTP response
limit. A timeout returns the standard `504 GATEWAY_TIMEOUT` JSON response with the request's
correlation ID.

Override the defaults per environment:

```sh
export GATEWAY_CONNECT_TIMEOUT_MS=2000
export GATEWAY_RESPONSE_TIMEOUT=10s
export GATEWAY_CIRCUIT_BREAKER_TIMEOUT=8s
```

`GATEWAY_CONNECT_TIMEOUT_MS` is an integer number of milliseconds. The other two settings accept
Spring duration values such as `500ms`, `10s`, or `1m`. Keep the circuit-breaker timeout shorter
than the HTTP response timeout so the gateway ends the higher-level operation first. Choose values
from measured service latency rather than increasing them without a limit.

## 4.12 Basic rate limiting

The gateway limits `POST` requests to `/api/v1/auth/login`, `/api/v1/auth/register`, and
`/api/v1/auth/refresh`. Each direct client address and endpoint gets an independent fixed-window
counter. The default permits 10 requests per minute; the next request receives the standard
`429 RATE_LIMIT_EXCEEDED` JSON response.

Every limited response includes `X-RateLimit-Limit` and `X-RateLimit-Remaining`. Rejected requests
also include `Retry-After`, rounded up to whole seconds. These headers and `X-Correlation-ID` are
exposed through CORS so browser clients can handle the response.

Override the defaults per environment:

```sh
export GATEWAY_RATE_LIMIT_ENABLED=true
export GATEWAY_RATE_LIMIT_REQUESTS=10
export GATEWAY_RATE_LIMIT_WINDOW=1m
export GATEWAY_RATE_LIMIT_MAX_TRACKED_KEYS=10000
```

The limiter uses the direct TCP peer address and deliberately ignores client-supplied forwarding
headers, so changing `X-Forwarded-For` cannot create a new bucket. If the gateway is placed behind
a reverse proxy, all traffic from that proxy shares its address until trusted-proxy resolution is
configured. The in-memory store is bounded by `GATEWAY_RATE_LIMIT_MAX_TRACKED_KEYS` and is local to
one gateway process. A multi-instance production deployment should replace it with a shared Redis
counter so every instance enforces the same limit.

## 4.13 Actuator and health

The gateway publishes unauthenticated health probes on its application port:

| Endpoint | Purpose |
| --- | --- |
| `/actuator/health` | Overall gateway process health |
| `/actuator/health/liveness` | Spring Boot liveness state |
| `/actuator/health/readiness` | Spring Boot readiness state |
| `/livez` | Short liveness path for container platforms |
| `/readyz` | Short readiness path for container platforms |

A healthy probe returns HTTP 200 with `"status":"UP"`. A gateway that is refusing traffic
returns HTTP 503 with `"status":"OUT_OF_SERVICE"` from its readiness endpoints. Component names
and health details are hidden from every caller. Each response still carries an
`X-Correlation-ID` and produces the standard structured request log.

Use `/livez` to decide when the process should be restarted and `/readyz` to decide when it should
receive traffic. Readiness currently measures the gateway process itself; temporary failure of one
downstream service is handled by the route timeout and standard gateway error instead of removing
the entire gateway from traffic.

The health URLs above are public so container probes do not need a JWT. Other exposed Actuator
endpoints, including `/actuator/info`, `/actuator/metrics`, `/actuator/prometheus`, and
`/actuator/gateway`, require an ADMIN token.

```sh
curl http://127.0.0.1:8080/actuator/health
curl http://127.0.0.1:8080/livez
curl http://127.0.0.1:8080/readyz
```

## 4.14 Gateway tests

The automated suite exercises the gateway as a running HTTP server and uses lightweight local
Reactor Netty services as downstream destinations. This verifies proxy behavior without requiring
the platform databases or long-running service processes.

| Area | Verified behavior |
| --- | --- |
| Routing | Auth, Orders, Inventory, and Payments destinations; inventory path rewriting |
| Authentication | Missing, malformed, expired, tampered, wrongly signed, and invalid-claim JWTs |
| Authorization | USER and ADMIN route rules, roleless tokens, and unknown roles |
| Identity | Original bearer scheme forwarded; spoofed identity headers replaced from JWT claims |
| Correlation | Safe IDs preserved, unsafe IDs replaced, same ID reaches downstream and response |
| Browser access | Allowed and rejected CORS preflights and exposed response headers |
| Reliability | Slow service produces 504; offline service produces sanitized correlated 503 |
| Rate limiting | Per-client and per-endpoint counters, spoofed forwarding-header resistance, 429 body |
| Operations | Public sanitized health probes, readiness 503 state, protected Actuator endpoints |
| Safety | Bounded structured logs and standardized errors without internal connection details |

Run the gateway and shared observability tests from the repository root:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./mvnw -pl services/edge-gateway -am test
```

The suite signs local test JWTs directly. Step 4.15 adds the separate system test that
obtains a token from the real Auth Service and sends it through the gateway.

## 4.15 End-to-end Auth to Gateway test

The `tests/platform-e2e` module starts the real Auth Service and Edge Gateway on random local
ports in the same test process. Both applications load their normal production YAML, share one
test JWT secret, and use an isolated in-memory H2 database for Auth data. No manually started
server or database is required.

The test proves this complete flow:

```text
Register through Gateway
        ↓
Auth Service creates the user
        ↓
Login through Gateway
        ↓
Auth Service signs access and refresh tokens
        ↓
Call /api/v1/protected/me through Gateway with the access token
        ↓
Gateway validates the JWT and routes it
        ↓
Auth Service validates the forwarded JWT again
        ↓
Response contains the registered user ID, ROLE_USER, and the same correlation ID
```

This complements the gateway integration suite: Step 4.14 uses locally signed tokens and stub
downstreams to test edge behavior in detail, while this test checks that the actual Auth Service
and Gateway agree on the JWT contract.

Run it from the repository root:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./mvnw -pl tests/platform-e2e -am test \
  -Dtest=AuthGatewayEndToEndTest -Dsurefire.failIfNoSpecifiedTests=false
```

See [`tests/platform-e2e/README.md`](../../tests/platform-e2e/README.md) for the test setup and
file layout.

## 4.16 AI/MCP engineering-agent review

The local Spring AI agent and the `backend-engineering-tools` MCP server reviewed the Phase 4
gateway changes at commit `500bc7e`. Because the feature work had already been committed and the
agent deliberately inspects working-tree changes against `HEAD`, the review used an isolated
worktree based on the pre-Phase-4 commit. The gateway patch, current parent POM, and end-to-end
module were applied there without changing the feature branch.

The final structured evidence was:

| Check | Result |
| --- | --- |
| Changed files | `SUCCESS`; 25 approved paths; complete inventory |
| Bounded Git diff | `SUCCESS`; redacted and truncated at the configured limit |
| Gateway compile | `PASS`; exit code 0 |
| Gateway tests | `PASS`; 57 passed, 0 failed, 0 errors, 0 skipped |
| Semgrep starter rules | `PASS`; 0 matches |
| Gitleaks current-file scan | `PASS`; 0 matches |
| OSV dependency scan | `PASS`; 0 matches across 183 resolved packages |
| Local Ollama analysis | `AVAILABLE`; 0 accepted findings |

The model proposed three findings, but the agent discarded all three because their quoted line
evidence did not satisfy the deterministic source/diff validator. This is expected safety
behavior: unsupported model claims do not become review findings.

The generated report was `WARN`, with `risk=UNASSESSED`, because the Git preview was truncated,
the parent POM is a shared change, and only one untracked source file is included in the model
preview. These are evidence-coverage warnings rather than failed checks. The local approval
workflow recorded `APPROVED` after the bounded-preview limitations were reviewed. Its
`reviewerIdentityStatus` is `NOT_AUTHENTICATED`, as expected for the V1 local token workflow.

An earlier scan against the old pre-Phase-4 parent versions reported dependency advisories. That
result did not represent the merge candidate. Repeating the scan with the feature branch's Spring
Boot 3.5.16 and Spring Cloud 2025.0.3 dependency management produced the final zero-finding result
above.

For future changes, run the review before committing so the normal working tree contains the diff:

```sh
curl --max-time 600 http://127.0.0.1:8090/api/agent/review \
  -H 'Content-Type: application/json' \
  -d '{"service":"edge-gateway"}'
```

Read the structured `build`, `tests`, `security`, `report`, and `approval` fields as evidence. The
model's `analysis` field is advisory and cannot override those results.

## Dependency security review

The platform parent manages Spring Boot 3.5.16 and Spring Cloud 2025.0.3. It also imports explicit
patched BOMs for Netty 4.1.138.Final, Jackson 2.21.5, Log4j 2.25.5, and OpenTelemetry 1.62.0, plus
Bouncy Castle 1.84. These overrides cover advisories published after the final Spring Boot 3.5 OSS
dependency baseline. Because the versions live in the parent POM, changes require the complete
repository test reactor as well as the gateway security scan.

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
/opt/homebrew/opt/openjdk@25/bin/java -jar services/edge-gateway/target/edge-gateway-0.0.1-SNAPSHOT-exec.jar
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
