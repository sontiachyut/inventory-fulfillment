# Current status

Last updated: 2026-09-08

## Completed

- P0: product specification, invariants, APIs, target data model, security gates, capacity methodology and acceptance-gated roadmap.
- P1: Java 21/Spring Boot 4.1.1 local-demo application, bounded reference model, HTTP endpoints, validation, Maven wrapper, CI and one-command demonstration.
- 27 unit/HTTP tests pass locally with zero failures/errors/skips. `make demo` passes and cleans up its own child process. See validation/P1.md.
- Public repository created; spec, scaffold and tested implementation are separate actual milestones. Inspect main and its CI run for the latest published revision.

## Exact next task: P2 PostgreSQL

Write ADR 0002 for idempotency retention, transaction boundaries, deadline authority and lock order. Introduce PostgreSQL 17 + Flyway + Spring JDBC and a required Testcontainers CI job. Test independent-connection stock contention, duplicate-key races, rollback, expiry/confirmation races and restart durability before replacing the reference adapter.

## Explicit limits / open decisions

- All current state is volatile and bounded. No database transactions, Kafka delivery, payment simulator or compensation yet. P1 expire scans bounded history on commands; not a scalable scheduler.
- Local profile is unauthenticated and bound to loopback. Do not expose publicly or put real data in it.
- Docker daemon is unavailable on the current workstation; no container/database/message integration tested locally.
- PostgreSQL adapter, dependency image digests and integration-test provisioning must be settled in P2.
- Cloud sizing/cost, public deployment and authentication provider remain future decisions. No resources created.
- No measured performance, uptime or multi-instance correctness claims. Seven-session sprint is a planning aid, not fabricated history or a completeness promise.
- CI results should be checked against the exact pushed main commit, not Dependabot branch runs.
- Future work has not been scheduled automatically. Resume by reading this file and ROADMAP.md.
