# Current status

Last updated: 2026-09-08

## Completed

- P0: specification, invariants, APIs, security gates, capacity methodology and acceptance-gated roadmap.
- P1: Java 21/Spring Boot 4.1.1 local reference model, HTTP API, Maven wrapper, CI and synthetic demonstration.
- P2: PostgreSQL 17.11, Flyway migrations, JDBC adapter and atomic transactional outbox. The in-memory adapter remains separately available.
- 27 unit/HTTP plus 13 PostgreSQL/process integration tests pass locally with zero failures/errors/skips. Docker is now running and database tests actually executed. See [P2 evidence](validation/P2.md).
- Independent-connection contention, durable replay, rollback, deadline/transition races, forced-process restart and two-API-process contention tested.
- CI runs the same full Maven acceptance gate, then the HTTP walkthrough. Check its result against the exact pushed main revision, not Dependabot branches.

## Exact next task: P3 event delivery and autonomous expiry

Write the worker lease/replay ADR and failure-oriented Kafka tests before implementing the outbox publisher and autonomous expiry workers. Define at-least-once publication, duplicate handling and claim recovery; test crash-after-publish/before-marking-delivered and concurrent expiry worker recovery without double stock release.

## Explicit limits / open decisions

- postgres-local persists state; local-demo remains volatile. Both profiles are unauthenticated and loopback-only. No real data or public exposure.
- Outbox rows accumulate but are not published. No scheduled expiry, payment simulator, compensation or fulfillment worker yet. Successful idempotency records have no cleanup policy in P2.
- The database image has known vulnerability findings: see [image security record](validation/IMAGE-SECURITY.md). Remediation/re-scan, production database roles, auth, restore drills and cloud sizing/cost remain deployment gates.
- Correctness tests are not throughput, uptime, failover or representative scale measurements.
- No billable cloud resources were created. The seven-session sprint is a planning aid, not a completeness promise.
- Future work is not automatically scheduled. Resume from this file, ROADMAP.md and ADR 0002.
