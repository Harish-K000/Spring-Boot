# Platform end-to-end tests

This Maven module verifies contracts that cross real service boundaries. It is separate from a
service module so neither Auth Service nor Edge Gateway owns the system-level test.

## Auth to Gateway scenario

`AuthGatewayEndToEndTest` performs these steps:

1. Start Auth Service as a servlet application on a random port.
2. Load Auth Service's normal `application.yaml`, replacing PostgreSQL with an isolated H2
   database and disabling Flyway for the test.
3. Start Edge Gateway as a reactive application on another random port.
4. Load the gateway's normal `application.yaml`, point its Auth route at the running Auth Service,
   and give both applications the same test JWT secret.
5. Register a USER through `POST /api/v1/auth/register` on the gateway.
6. Log in through `POST /api/v1/auth/login` and retain the access token issued by Auth Service.
7. Send that exact token through the gateway to `GET /api/v1/protected/me`.
8. Verify the final response has the registered user's ID, `ROLE_USER`, and the caller's
   `X-Correlation-ID`.
9. Stop both application contexts after the test.

The request passes two security boundaries. Edge Gateway validates the token before routing, and
Auth Service independently validates the forwarded bearer token before returning the protected
response.

## Run

From the repository root:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./mvnw -pl tests/platform-e2e -am test \
  -Dtest=AuthGatewayEndToEndTest -Dsurefire.failIfNoSpecifiedTests=false
```

`-am` builds the Auth Service, Edge Gateway, and shared observability library required by the test.
`surefire.failIfNoSpecifiedTests=false` lets the upstream modules participate even though the
named test exists only in `platform-e2e`.

## Files

| File | Purpose |
| --- | --- |
| `pom.xml` | Declares test dependencies on Auth Service, Edge Gateway, and Spring Boot Test |
| `src/test/java/com/example/platform/e2e/AuthGatewayEndToEndTest.java` | Starts both applications and executes the HTTP flow |
