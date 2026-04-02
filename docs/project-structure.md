# Project structure

Repository contains two Kotlin modules and infra/docs folders:

- `platform-core` — Spring Boot API core with PostgreSQL, Flyway migrations, transaction API, async processing workers, SLA timeout guard, and operational metrics/health checks.
- `transaction-generator` — Kotlin load-generator with target TPS control, concurrency, and finalization SLA polling.
- `infra` — local infra assets (DB init/scripts).
- `docs` — architecture and operational docs.

## Implemented in steps 2-10

- Bootstrapped Gradle multi-module project (`settings.gradle.kts`, root `build.gradle.kts`).
- Added `platform-core` app skeleton, DB schema migration, and runtime config.
- Implemented API contract endpoints:
  - `POST /api/v1/transactions` (202 Accepted)
  - `GET /api/v1/transactions/{transactionId}`
- Implemented async processor (claim batches + finalize statuses), SLA sweeper (`SLA_TIMEOUT`), and overload protection (`max-in-progress`).
- Added metrics and health visibility (`transactions.in_progress`, processing heartbeat health indicator).
- Implemented generator with configurable TPS/concurrency and latency/SLA report.
- Added load-test guide and acceptance checks (`docs/load-test.md`).
