# ADR 0002: transactional reservations and deadline authority

Status: accepted for P2 local persistence. Date: 2026-09-08.

Use PostgreSQL READ COMMITTED with database constraints and explicit transactions; no Java lock or cache participates in stock allocation.

Reserve: claim (tenant, operation, key) using INSERT ON CONFLICT DO NOTHING. On replay, verify normalized request hash and read the original reservation. Successful intent creates a reservation, conditional stock update and outbox atomically. Failed allocation rolls back the idempotency claim, so a rejected request may be retried after stock is available. Keep successful idempotency records indefinitely in this milestone; no expiry/key reuse until an explicit retention/tombstone migration.

For an existing reservation, lock reservation first, then its stock row. Read clock_timestamp() AFTER acquiring both locks, not transaction-start now(). This prevents a transaction waiting on a hot stock row from confirming after its deadline. All transition paths follow this order.
If expired, commit release/state/outbox and only then return the conflict to the caller. Throwing inside that transaction would roll back the expiry release. Return a result sentinel from the transaction to separate committed expiry from HTTP conflict.
Reserve inserts only its new reservation after locking stock; it never locks an existing reservation while holding stock. Idempotency replays do not acquire stock first.

P2 expires the requested reservation on reads/commands and exposes a local-only bounded sweep (100 candidates, each in its own transaction with SKIP LOCKED). No global O(history) scan on each operation. Stock reads report materialized balances and may temporarily include expired holds pending a sweep; reserve can conservatively reject during that interval. This is a documented difference from P1, not overselling.
An autonomous configurable expiry scheduler and its operational ownership remain P3. Sweep batch size is fixed/bounded; repeated invocations drain a backlog.

A reservation version increases on each state change. Its outbox event shares the transaction and is unique by aggregate/version. Stock creation itself is not an integration event yet. Payment callbacks/refunds are P4.
Failed SQL statements roll back stock, reservation and outbox together. Lock/statement timeouts are bounded; return retryable 503 for transient DB contention, never masquerade as insufficient stock. Automatic transaction retries are deferred; clients reuse idempotency keys.
The postgres-local profile is durable but unauthenticated and loopback-only. Auth-derived tenant scope remains a predeployment security gate. No real payment or customer data.

Use real PostgreSQL/Testcontainers tests for first-write races, independent adapter/connection contention, idempotency, deadlines after waiting, rollback and persistence. No in-memory tests are presented as database evidence.
