# Project structure

Repository contains two Kotlin modules and infra/docs folders:

- `platform-core` — Spring Boot API core with PostgreSQL, Flyway migrations, and health/metrics wiring.
- `transaction-generator` — Kotlin CLI skeleton for load generation.
- `infra` — local infra assets (DB init/scripts).
- `docs` — architecture and operational docs.

## Implemented in steps 2 + 3

- Bootstrapped Gradle multi-module project (`settings.gradle.kts`, root `build.gradle.kts`).
- Added `platform-core` app skeleton and runtime config (`application.yml`).
- Added initial DB migration with `transactions` schema and performance-oriented indexes.
- Added `transaction-generator` executable skeleton module.
