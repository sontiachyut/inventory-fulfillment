# Inventory Reservation & Fulfillment

Reserve limited stock without losing track of quantity, expiry or repeated requests.

[![verify](https://github.com/sontiachyut/inventory-fulfillment/actions/workflows/ci.yml/badge.svg)](https://github.com/sontiachyut/inventory-fulfillment/actions/workflows/ci.yml)

This is an incremental backend engineering project. **P1 is a runnable, local-only reference implementation.** It uses bounded in-memory state: restarting loses all data. It is not production-ready and has no measured distributed-scale results.

## Run it

Requirements: Java 21. Node.js 22+ and make are needed for the scripted demo. The Maven wrapper downloads a checksum-pinned Maven distribution.

```sh
./mvnw verify
make demo
```

The demo packages and starts a fresh application on a dynamic loopback port, runs HTTP assertions, then stops that process. No Docker, paid services, external merchant data or credentials are needed.

For manual API exploration:

```sh
make run
# localhost:8082; local-demo profile, volatile state
```

## What works today

- Stock creation and balances; positive, bounded quantities.
- Idempotent reservations with payload-conflict detection.
- Confirm/cancel/expire transitions with terminal-state protection.
- Exact expiry-boundary handling, conservation checks and tenant-key separation.
- REST endpoints, HTTP validation and deterministic state/concurrency tests.

Tenant IDs in the demo are not authentication. The 1,000-task contention test runs in one process on 16 executor threads; it does not prove database or multi-instance correctness.

## Planned architecture — not yet implemented

Java/Spring Boot API → PostgreSQL transactional stock/reservations/outbox → Kafka and expiry/fulfillment workers. A simulated payment flow exercises late callbacks and compensation. React provides the operator timeline.

PostgreSQL persistence, outbox delivery, Kafka, payment callbacks/compensation and the UI are future phases.
No cloud resources have been provisioned.

## Engineering documents

- [Specification: invariants, API, data model, security and scale targets](docs/SPEC.md)
- [Phased roadmap and acceptance gates](docs/ROADMAP.md)
- [Current status and precise next task](docs/STATUS.md)
- [Architecture decision](docs/adr/0001-boundaries-and-proof.md)
- [Demo walkthrough](docs/DEMO.md)
- [Optional companion-project integration](docs/INTEGRATION.md)
- [Validation record](docs/validation/P1.md)

The companion project is [Verified Offers](https://github.com/sontiachyut/verified-offers). Each repository runs independently.

## Evidence before claims

The roadmap includes database concurrency tests, event replay, failure injection, load tests and restore drills. Capacity numbers in the spec are **proposed workloads**, not achieved performance. Results will report hardware, configuration, errors and limitations.

See [CONTRIBUTING](CONTRIBUTING.md) for the AI-assisted development/review workflow and [SECURITY](SECURITY.md) for deployment restrictions.
