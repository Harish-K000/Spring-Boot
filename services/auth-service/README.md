# Auth Service

Phase 2 provides local account registration, password login, signed access tokens, rotating
one-time refresh tokens, replay detection, logout, and bearer-token protection.

## API

| Method | Path | Authentication | Result |
|---|---|---|---|
| `POST` | `/api/v1/auth/register` | Public | Creates a local account |
| `POST` | `/api/v1/auth/login` | Public | Returns access and refresh tokens |
| `POST` | `/api/v1/auth/refresh` | Refresh token in body | Rotates the refresh token |
| `POST` | `/api/v1/auth/logout` | Refresh token in body | Revokes its complete token family |
| `GET` | `/api/v1/protected/me` | Bearer access token | Returns the token subject and roles |

Access tokens expire after 15 minutes and use issuer `backend-platform-auth` and audience
`backend-platform-api`, which the edge gateway enforces. Refresh tokens expire after 30 days, are
stored only as SHA-256 hashes, and can be used once. Reuse of a rotated token revokes its complete
family.

## Run locally

Start PostgreSQL, generate a signing secret, then run the module from the repository root:

```sh
docker compose -f infra/docker-compose.yml up -d postgres
export JWT_SECRET="$(openssl rand -base64 48)"
./mvnw -f services/auth-service/pom.xml spring-boot:run
```

`JWT_SECRET` is mandatory and must contain at least 32 UTF-8 bytes. The service intentionally
fails during startup when it is absent or weak.

## Verify

```sh
./mvnw -pl services/auth-service -am test
```

The normal suite uses H2 and includes security-filter, transaction, replay, and concurrent refresh
coverage. A Testcontainers test additionally runs Flyway followed by Hibernate validation against
PostgreSQL 15 when Docker is available; it is skipped when Docker is unavailable.
