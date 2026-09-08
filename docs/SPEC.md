# Inventory Reservation & Fulfillment — product and engineering specification

Status: approved project direction; implementation progresses only through acceptance gates.
Primary question: how do we prevent overselling and duplicate business effects when requests, workers and payment callbacks race?

## Product and boundaries

A merchant has stock. A shopper reserves a quantity for a bounded time. Reservations may confirm, expire or cancel. A simulated payment provider sends duplicate/delayed callbacks. An operator can explain every transition.
Initial domain: one warehouse and one SKU per reservation. All quantities positive integers. Synthetic payments only.
Non-goals: real payment processing, PCI claims, tax/shipping/fare calculation, multiwarehouse optimization, financial settlement, a complete marketplace or new consensus algorithm.
Companion Verified Offers owns discovery and fact verification; this service independently owns authoritative stock allocation. Their integration is optional and later.

## Functional requirements and invariants

IF-01: Stock identity is (tenantId,sku). For each stock record: available >=0, reserved >=0, sold >=0 and available+reserved+sold = initialStock + acceptedAdjustments. P1 adjustments are unsupported; create stock once.
IF-02: Reserve must atomically verify available >= requested quantity, decrement available, increment reserved, create a reservation and (P2) outbox event.
IF-03: Idempotency key scoped to tenant + operation. Same key and normalized intent (sku,quantity,ttl) returns the same reservation; changed intent returns 409. Replays do not restart TTL or reserve stock again.
IF-04: Reservation states are ACTIVE, CONFIRMED, CANCELLED, EXPIRED. Legal transitions: ACTIVE->CONFIRMED/CANCELLED/EXPIRED. Terminal states cannot switch. Identical terminal command may return current state without effects.
IF-05: Expiry uses server time; now >= expiresAt means expired. Confirmation at or after expiry releases stock and fails with a stable conflict, regardless of callback arrival order. There is no silent resurrection.
IF-06: Confirm moves quantity from reserved to sold exactly once; cancel/expire moves reserved to available exactly once.
IF-07: Concurrent confirm/cancel/expire is serialized by the database transaction/locks at P2. In-memory locks are only a single-process reference model, not distributed proof.
IF-08: Payment outcome and reservation outcome are distinct. Successful simulated payment after expiration creates compensationRequired and a refund-simulator job; it cannot confirm expired stock.
IF-09: Callback event IDs and signatures are validated at P4; duplicates do not repeat transitions/refunds. Payload conflict for an existing provider event is quarantined.
IF-10: Transactional outbox and idempotent consumers prevent missing committed business events or duplicated downstream effects; delivery can repeat.
IF-11: Tenant identity comes from verified auth before a shared deployment. No cross-tenant access by guessed reservation IDs.
IF-12: Never use Redis cache or search-index quantity to authorize a reservation. PostgreSQL is authoritative.

## State semantics

ACTIVE -- confirm before deadline --> CONFIRMED
ACTIVE -- cancel before deadline --> CANCELLED
ACTIVE -- deadline reached --> EXPIRED

A read/command in P1 may materialize expiry; a clock-driven explicit sweep also exists. P2 uses a worker. Deadline correctness must not rely solely on worker timeliness.
Terminal response retry: repeated confirm of CONFIRMED and cancel of CANCELLED is safe. Confirm of CANCELLED/EXPIRED is conflict. Cancel of CONFIRMED/EXPIRED is conflict.
TTL range: 1–900 seconds in P1. Inject Clock for unit tests; use database time/consistent server policy at P2.
A request replay may return the reservation's current state, not necessarily its creation response bytes; document this explicitly.

## Architecture

Target flow:
Client -> reservation API -> PostgreSQL stock/reservations/idempotency/outbox
Outbox publisher -> Kafka -> fulfillment/payment-simulator workers -> authenticated callbacks -> transactional transitions
Expiry worker -> batched expired reservations with locking -> released stock + events
React operator console -> stock, reservation timeline, retries, compensation state

Modules: inventory (stock), reservations (state machine), payments (simulation only), delivery (outbox/consumers), API.
P1: bounded volatile reference model + local REST; no DB, Kafka, payment flow or public auth.
P2: explicit PostgreSQL transactions and real concurrent integration tests.
P3: outbox, events and expiry workers; P4: payment simulator, compensation and timeline.

## API contract

P1:
- POST /api/v1/stock — tenantId, sku, quantity; creates stock once. Duplicate identity is 409.
- GET /api/v1/stock/{tenantId}/{sku} — balances; may first expire due reservations.
- POST /api/v1/reservations — tenantId, sku, quantity, ttlSeconds, idempotencyKey.
- GET /api/v1/reservations/{tenantId}/{reservationId} — current state.
- POST /api/v1/reservations/{tenantId}/{reservationId}/confirm
- POST /api/v1/reservations/{tenantId}/{reservationId}/cancel
- POST /api/v1/demo/expire — explicit local-only sweep; no public production counterpart.
- GET /actuator/health

Responses: stock snapshot; reservationId, tenantId, sku, quantity, expiresAt, status.
400 invalid input, 404 unknown stock/reservation (including another tenant's ID), 409 insufficient stock/idempotency conflict/illegal transition, 503 bounded demo capacity.
P2+ use verified tenant scope; accept idempotency header with documented normalization. API versioning/migration is explicit.
P4 callback endpoint carries providerEventId, reservationId, outcome, timestamp and signature. Only simulated provider secrets stored outside repository; no real account integrations.

## Persistent model and transaction design (P2)

stock PK(tenant_id,sku): initial_qty, available_qty, reserved_qty, sold_qty, version; CHECK nonnegative and conservation.
reservation PK(id): tenant_id,sku,quantity,state,created_at,expires_at,version. Indexed active expiry. Foreign key to tenant stock.
idempotency PK(tenant_id,operation,key): request_hash, reservation_id, recorded_at; insert and business mutation in one transaction.
outbox: immutable event ID, aggregate/version, payload, publication/lease metadata.
P4 payment_event unique(provider,event_id) with payload_hash; compensation unique(payment_event_id,kind).

Reserve transaction: claim/check idempotency key, atomically allocate stock with conditional UPDATE, insert reservation + outbox, commit. Duplicate key race re-reads winning committed record; failures roll back allocation.
Transition transaction: lock reservation then stock consistently, check time/state, update balances/state/version and outbox together. Same lock order in worker and API. Reserve does not lock an existing reservation after stock.
Expiry worker: bounded SELECT ... FOR UPDATE SKIP LOCKED batches; stock update and state transition in same transaction. Multiple workers must not double-release.
Idempotency retention must exceed supported retry horizon (initial proposal 7 days); after expiry do not silently reuse keys while old business records exist. P2 ADR settles retention/tombstones.
Use deadlock/serialization retry only around the full transaction, with bounded attempts and preserved idempotency.

## Quality and workload targets

Mandatory P2 correctness experiment: 100 units, 1,000 competing reservation attempts from independent database connections and later independent API instances. Exactly 100 accepted one-unit reservations, no negative balances; all rejected requests accounted for.
P5 baseline: 10k SKUs, 100 offered mutations/s for 15 minutes; aim p95 <150 ms on declared hardware. Separately test one hot SKU; do not hide contention in uniform averages.
P6 stretch: increase stock/key cardinality, API instances and load until a measured saturation point; publish lock wait and successful throughput rather than an arbitrary million-TPS claim.
Failure matrix: worker dies before/after Kafka ack; API dies after commit before response; simultaneous confirm/cancel/expiry; callback after expiry; duplicate callbacks; DB reconnect/deadlocks; tenant isolation; replay after terminal states.
Property/random-sequence tests assert conservation after every operation. P1 thread tests are single-process reference checks, not PostgreSQL/load tests.

## Delivery principles

- A production-oriented reference implementation, not a claim of production readiness or Amazon affiliation.
- Build a modular service before introducing independently deployed components. Separate ownership by module and explicit contracts.
- Ship actual vertical slices, tests, failure experiments and decision records. Commit at genuine milestones with actual timestamps.
- Synthetic merchants, users, inventory and payments only. No employer code, application data, scraped private records or credentials.
- The owner should review the model, explain its tradeoffs, run the demo and make design decisions. AI-assisted scaffolding is documented in CONTRIBUTING.
- No real payments, production customer traffic or billable cloud resources without a separate deployment decision.
- Seven working sessions are an initial sprint estimate, not a promise that the full system is complete in a week. Do not reduce acceptance gates to meet a date.

## Technology decisions

| Component | Decision | Reason / adoption gate |
|---|---|---|
| Backend | Java 21, Spring Boot 4.1.1, Maven 3.9.16 wrapper | Existing Java experience, explicit domain types, mature HTTP/testing tooling; versions verified against official documentation and Maven Central |
| Persistence (P2) | PostgreSQL 17, Flyway, Spring JDBC | Explicit transactions, constraints and concurrency behavior; avoid hiding critical SQL behind ORM behavior |
| Messaging (P3) | Apache Kafka, official Java client through Spring Kafka | Ordered per-aggregate events and replay; transactional outbox bridges database commits to at-least-once publication |
| UI (P4+) | React, TypeScript, Vite | Small inspectable product console; not another business logic authority |
| Tests | JUnit, Spring HTTP integration tests; Testcontainers/PostgreSQL and Kafka at their adoption phases | Test business invariants and real storage behavior; never use H2 as proof of PostgreSQL locking |
| Operations (P5+) | Micrometer/OpenTelemetry, Prometheus/Grafana, structured logs, k6 | Trace actual bottlenecks and reproduce measured results |
| Packaging | Nonroot container, Compose for local dependencies; Terraform/AWS later | Reproducible runtime before cloud complexity |
| AWS option | ECS Fargate, RDS PostgreSQL, MSK, S3, ALB, Secrets Manager | Architectural candidate only; estimate costs and obtain approval before provisioning |

PostgreSQL/Kafka/container patch versions and image digests must be pinned and scanned when introduced. These planned components are not installed by the initial domain milestone. No Redis, Kubernetes, service mesh or custom consensus unless a measured need warrants them.

## Security and public-demo gate

The initial profile is local-only, unauthenticated and volatile. Bind to loopback; startup requires an explicit demo profile. It must not be deployed publicly. Do not load actual user data.

Before a shared environment: OIDC JWT verification (issuer/audience/expiry), tenant identity derived from verified claims, server-side authorization on every object, input limits, request quotas, TLS, secret management, least-privilege roles, audit events, dependency/container scans and tenant-isolation tests. Arbitrary tenant IDs in demo bodies are not authentication.

CORS is closed by default. Public responses exclude secrets and stack traces. Structured logs avoid tokens and personal data. Synthetic fixture generators have fixed seeds. Deletion/retention applies to artifacts, logs and backups as well as tables. Define restore verification and rotation procedures before deployment.

## Event contract and delivery

Envelope: eventId (UUID), eventType, schemaVersion, tenantId, aggregateId, aggregateVersion, occurredAt (UTC), correlationId and typed payload. No secrets or personal data in events.
Commit domain state and the outbox record in the same database transaction. Publish keyed by tenant + aggregate. Mark publication only after acknowledgement; a crash may duplicate delivery.
Consumers commit their business side effect and eventId deduplication in one database transaction. Never claim universal exactly-once delivery across PostgreSQL, Kafka and external systems.
Version payloads additively; breaking changes require a new schema/topic migration. Poison events go to bounded quarantine with reason, attempts and an audited replay procedure. Include retention/deduplication-horizon behavior in tests.
P3 ADR must select polling versus CDC, retry caps, partition count, retention and event payload compatibility before implementation.

## Scale, performance and reliability evidence

Capacity stages are proposed workloads, not achieved claims:
1. Laptop correctness: bounded fixtures and deterministic concurrency/failure tests.
2. Single-node integration: realistic database/index/message adapters; baseline throughput and latency.
3. Controlled load: ramp, burst, sustained load and recovery on documented hardware.
4. Optional multi-instance deployment: verify cross-process correctness, load distribution and resource saturation.

Record commit, configuration, CPU/RAM, JVM settings, data cardinality/skew, payload sizes, warmup, run duration, concurrency, offered versus successful throughput, p50/p95/p99, errors/timeouts and cost. Include hot keys/tenants, not just uniform traffic.
No uptime claim can be inferred from a short test. An SLO is a target, and a measured result must link to raw artifacts.
Backpressure: bounded queues and batches, admission limits, connection pool budgets, timeouts and bounded retries with jitter. Inspect deadlocks, lock waits, index lag and retry storms.
Require backup + restore drills and process-kill recovery before calling a release deployable. Single-region writer first; multi-region routing/replication is a documented extension, not an invented implementation.

## Continuity and engineering workflow

Read AGENTS.md, docs/STATUS.md, this spec, docs/ROADMAP.md and relevant ADRs before work. Update STATUS with completed scope, test commands/results, open risks and the precise next task at each milestone. Update the spec before behavior changes; add ADRs for meaningful tradeoffs.
CI runs verification on pushes/PRs with read-only token permissions. Future integration jobs must require dependencies and fail rather than silently skip.
Every phase ends with acceptance evidence, a truthful README and a real commit. Do not pad commits, backdate them or report a future benchmark as completed.
No automatic scheduling of future work is configured by this repository.

## References

- [Spring Boot requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [Maven Wrapper](https://maven.apache.org/tools/wrapper/)
- [PostgreSQL locking](https://www.postgresql.org/docs/17/explicit-locking.html)
- [Kafka delivery semantics](https://kafka.apache.org/40/design/design/)
