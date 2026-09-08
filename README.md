# Inventory Reservation & Fulfillment

Reserve limited stock without losing track of quantity, expiry or repeated requests.

[![verify](https://github.com/sontiachyut/inventory-fulfillment/actions/workflows/ci.yml/badge.svg)](https://github.com/sontiachyut/inventory-fulfillment/actions/workflows/ci.yml)

An incremental backend engineering project. **P2 adds PostgreSQL persistence and transactional integration tests.** This is a local development system, not a production deployment or a large-scale performance claim.

## Run it

Requirements: Java 21 and a running Docker daemon. Node.js 22+ and make are needed for the scripted HTTP demo. The Maven wrapper downloads a checksum-pinned distribution.

```sh
./mvnw verify
make demo
```

Verification starts isolated PostgreSQL containers and tests the packaged API. The final HTTP walkthrough uses fresh in-memory state on a dynamic loopback port. Tests clean up their own containers/processes; no paid services or external merchant data are used.

For persistent exploration, follow the [PostgreSQL setup](docs/POSTGRES.md). For the volatile reference implementation:

```sh
make run
# localhost:8082; local-demo profile, state lost at shutdown
```

Without Docker, `./mvnw test` runs only unit/in-memory HTTP tests—not the full acceptance gate.

## What works today

- Stock reservations with durable idempotency and confirm/cancel/expire transitions.
- PostgreSQL row locks, conditional stock updates and database-enforced quantity conservation.
- Transactional outbox: stock changes, reservation state and event insertion commit together.
- 1,000 competing database reservation attempts, duplicate-key races, expiry races and rollback tests.
- Packaged API restart recovery and contention between two independent API processes sharing one database.

Expiry sweeps are bounded and manually triggered in P2. Stock reads can conservatively include expired holds until a sweep. Tenant IDs are not authentication.

## Next phases — not yet implemented

Kafka outbox delivery, autonomous expiry/fulfillment workers, simulated payment callbacks and compensation, then a React operator timeline.

The outbox is persisted but **not published yet**. Authentication, operational hardening, backup/restore and representative load measurements remain open. No cloud resources have been provisioned. The pinned database image has [known security findings](docs/validation/IMAGE-SECURITY.md); public deployment is not approved.

## Engineering documents

- [Specification: invariants, API, data model, security and scale targets](docs/SPEC.md)
- [Phased roadmap and acceptance gates](docs/ROADMAP.md)
- [Current status and precise next task](docs/STATUS.md)
- [Architecture boundaries](docs/adr/0001-boundaries-and-proof.md) and [PostgreSQL transaction decisions](docs/adr/0002-postgres-transactions.md)
- [Reference demo](docs/DEMO.md) and [persistent local profile](docs/POSTGRES.md)
- [P2 validation evidence](docs/validation/P2.md) and [historical P1 record](docs/validation/P1.md)
- [Optional companion-project integration](docs/INTEGRATION.md)

The companion project is [Verified Offers](https://github.com/sontiachyut/verified-offers). Each repository runs independently.

## Evidence before claims

Database contention and restart tests establish specific correctness properties under synthetic fixtures. They do not establish throughput, latency SLOs, database disaster recovery or production availability. Capacity numbers in the spec are proposed workloads, not achieved performance.

See [CONTRIBUTING](CONTRIBUTING.md) for the AI-assisted development/review workflow and [SECURITY](SECURITY.md) for deployment restrictions.
