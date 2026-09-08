# Roadmap

Phases are acceptance-gated. Sessions can span multiple days; dates follow actual work.

| Phase | Deliverable | Exit evidence |
|---|---|---|
| P0 | Spec, ADRs, roadmap, continuity files | State/invariant/API/security/scale decisions recorded |
| P1 | Local stock/reservation reference model and REST | State, expiry, idempotency, conservation, thread and HTTP tests; demo |
| P2 | PostgreSQL/Flyway transactional implementation | Real 1,000-request contention, restart, rollback and duplicate-key-race tests |
| P3 | Outbox/Kafka + multiworker expiry | Crash-point replay, no double release, stale-worker/failure tests |
| P4 | Payment simulator + compensation + timeline | Late/duplicate/conflicting callbacks and exactly-once business effects in DB tests |
| P5 | React console, tenant auth and admission limits | User journey, permission matrix, accessible views and bounded-load behavior |
| P6 | Load/chaos/restore evidence and packaging | Published workload scripts/results, restore drill, runbooks and deployment gates |

Initial seven-session sprint: 1 specs/P1; 2 database schema + reserve; 3 transition/expiry concurrency; 4 outbox/Kafka; 5 payment simulator; 6 thin UI; 7 failure demos/docs. Auth hardening and P6 may extend beyond the sprint. Running both projects in parallel doubles effort; do one milestone at a time.

P2 functional gate completed: see [transaction decisions](adr/0002-postgres-transactions.md) and [validation](validation/P2.md). Deployment security remains open.

Next P3 task: define publisher claim/recovery and duplicate-event semantics in an ADR, then write crash-after-publish/before-marking-delivered tests. Add autonomous expiry worker recovery without double stock release.
