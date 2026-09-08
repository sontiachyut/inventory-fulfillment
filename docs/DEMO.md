# Local reference demonstration

Prerequisites: Java 21, Node.js 22+, make and a running Docker daemon. Maven is downloaded by the checked-in wrapper. Since P2, the full verification gate requires Docker for real PostgreSQL tests.

Run `make demo` from the repository root. It runs unit tests, packages the application and executes PostgreSQL/process integration tests using isolated disposable databases. It then launches a fresh in-memory JVM on a dynamic loopback port, exercises HTTP assertions and terminates only that child process. No Compose data or external merchants/providers are touched.

For persisted state and restart exploration, use [the PostgreSQL profile](POSTGRES.md). `make postgres-demo` runs the full gate and prints the database test summaries.

For manual exploration: `make run` starts the local-demo profile on port 8082. State is lost at shutdown. API details and field constraints are in SPEC.md.

The in-memory walkthrough reserves stock, repeats the request, rejects over-reservation, confirms twice, rejects a contradictory terminal transition and cancels a second reservation. The preceding PostgreSQL suite separately exercises 1,000 competing reservation attempts, expiry/transition races and process restart. Payment callbacks remain future work.

This is synthetic evidence of the current reference behavior, not a performance benchmark or deployment claim.
