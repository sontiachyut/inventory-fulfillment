# ADR 0001: explicit boundaries and evidence before distribution

Date: 2026-09-08. Status: accepted for P1.

Use Java 21/Spring Boot 4.1.1 and a modular application. Start with an explicitly named bounded in-memory demo adapter, not hidden production-like persistence. Require the local-demo profile, bind loopback, and fail startup without the profile until production storage/auth configuration exists.

The reference model makes state transitions inspectable and tests deterministic. It does not prove cross-process concurrency, durability, restart safety or large-scale behavior. PostgreSQL integration tests are a hard P2 gate. Do not substitute H2 or a synchronized collection for database evidence.

Do not split microservices merely for appearance. API/worker separation follows independently testable failure and scaling boundaries. The companion repository is independently runnable; integrate only with a versioned contract once both local products work.

Alternatives rejected: starting with Kubernetes and many services (operational overhead before correctness); claiming scale from synthetic unit tests (invalid evidence); mixing both products into a single giant initial release (delays useful demonstrations).
