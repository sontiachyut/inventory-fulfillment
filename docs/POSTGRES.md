# PostgreSQL local profile

The postgres-local profile persists state across application restarts. It is still unauthenticated and loopback-only, with a migration-capable local database role. Do not expose it to the internet or use real data.

## Start a development database

Java 21 and Docker with Compose are required. Choose credentials for this disposable local database (not credentials from any other system):

```sh
export APP_DATABASE_USER=commerce_local
read -s APP_DATABASE_PASSWORD
export APP_DATABASE_PASSWORD
export APP_DATABASE_URL=jdbc:postgresql://127.0.0.1:5542/inventory
docker compose up -d
./mvnw spring-boot:run -Dspring-boot.run.profiles=postgres-local
```

The password prompt is intentionally silent; type a new local-only value and press Enter. Credentials are environment variables, never committed files. The PostgreSQL port is bound to loopback. Flyway applies the schema on startup; do not edit an applied migration.

Stop the application with Ctrl-C and stop the database with `docker compose stop`. Data remains in the project-specific named volume. Removing that volume would destroy local data; no cleanup command does that automatically.

The two projects use separate databases/volumes/ports. Do not point both at the same schema. The schema role is intentionally not a hardened deployment identity.

## Test modes

- `./mvnw test`: unit and in-memory HTTP tests only. Not the full acceptance gate.
- `./mvnw verify`: unit tests, package, real PostgreSQL/Testcontainers tests, and packaged-process tests. Docker is required; unavailable Docker fails, not skips.
- `make demo`: full verification followed by the original local-demo walkthrough.
- `make postgres-demo`: full verification including PostgreSQL process-restart assertions; prints the database test summaries.

Testcontainers starts isolated databases on dynamic ports and removes its test containers at completion; it does not touch Compose data. The tests deliberately inject failures and truncate only their isolated test database.

## Important semantics

Reservation updates lock reservation then stock and use PostgreSQL clock time after acquiring locks. Late confirmation commits expiry release before returning a conflict. The bounded expiry sweep processes at most 100 candidates; no automatic scheduler runs yet. Stock reads report materialized balances, which can conservatively include expired holds until a sweep. Successful idempotency keys are retained indefinitely in P2.

Outbox publication is NOT implemented. Rows accumulate locally; there are no Kafka delivery guarantees yet. Backup/restore, failover, auth and production role separation remain deployment gates. Read ADR 0002 for transaction and failure decisions.
