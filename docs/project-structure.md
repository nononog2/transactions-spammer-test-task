# Project structure (Step 1)

This repository is organized into two main Kotlin modules and infrastructure/docs folders:

- `platform-core` — main API/service with PostgreSQL
- `transaction-generator` — load generator for TPS tests
- `infra` — local infra assets (DB init/scripts)
- `docs` — architecture and operational docs

Planned next steps:
1. bootstrap Kotlin builds for both modules;
2. add database migrations;
3. implement API and processing pipeline.
