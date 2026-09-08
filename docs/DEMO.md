# Local reference demonstration

Prerequisites: Java 21, Node.js 22+ and make. Maven is downloaded by the checked-in wrapper. No Docker required for P1.

Run `make demo` from the repository root. It executes the verification suite, packages the application, launches a fresh JVM on a dynamic loopback port, exercises HTTP assertions and terminates only that child process. It does not write persistent data or call external merchants/providers.

For manual exploration: `make run` starts the local-demo profile on port 8082. State is lost at shutdown. API details and field constraints are in SPEC.md.

The demonstration reserves stock, repeats the request, rejects over-reservation, confirms twice, rejects a contradictory terminal transition and cancels a second reservation. Expiry boundaries and a 1,000-task contention scenario are unit tests; payment callbacks and PostgreSQL races are not implemented yet.

This is synthetic evidence of the current reference behavior, not a performance benchmark or deployment claim.
