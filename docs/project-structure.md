# Project structure

Repository contains two Kotlin modules and infra/docs folders:

- `platform-core` — Spring Boot API core with PostgreSQL, Flyway migrations, transaction API, async processing workers, and SLA timeout guard.
- `transaction-generator` — Kotlin CLI skeleton for load generation.
- `infra` — local infra assets (DB init/scripts).
- `docs` — architecture and operational docs.

## Implemented in steps 2-6

- Bootstrapped Gradle multi-module project (`settings.gradle.kts`, root `build.gradle.kts`).
- Added `platform-core` app skeleton and runtime config (`application.yml`).
- Added initial DB migration with `transactions` schema and performance-oriented indexes.
- Implemented API contract endpoints:
  - `POST /api/v1/transactions` (202 Accepted)
  - `GET /api/v1/transactions/{transactionId}`
- Implemented async processor (claim batches + finalize statuses) and SLA sweeper (`SLA_TIMEOUT`).
- Added overload protection via `max-in-progress` threshold.
