# Project Structure

## Modules

### `platform-core` — Spring Boot transaction API

Spring Boot 3.5 / Kotlin / JVM 21 service that accepts transaction requests, finalizes them asynchronously, and enforces SLA.

```
platform-core/src/main/kotlin/com/example/platformcore/
├── api/
│   ├── TransactionController.kt       — POST /api/v1/transactions, GET /api/v1/transactions/{id}
│   ├── TransactionDtos.kt             — request/response DTOs + mapping extensions
│   └── ApiExceptionHandler.kt        — global @RestControllerAdvice (400/404/429)
│
├── config/
│   ├── AppProperties.kt               — @ConfigurationProperties: processing + sla settings
│   ├── ProcessingHealthIndicator.kt   — /actuator/health: exposes claim/sweeper lag
│   └── SchedulingConfig.kt            — 4-thread scheduled executor pool
│
├── domain/
│   ├── Transaction.kt                 — immutable domain entity
│   └── TransactionStatus.kt          — IN_PROGRESS | SUCCEED | FAILED
│
├── exception/
│   └── ApiExceptions.kt               — NotFoundException, OverloadedException, InvalidRequestException
│
├── persistence/
│   └── TransactionRepository.kt       — JDBC repository; UNNEST batch UPDATE, crash-recovery claimBatch
│
└── service/
    ├── TransactionService.kt           — create + getById business logic
    ├── TransactionDispatchQueue.kt     — in-memory LinkedBlockingQueue (HTTP → processing bridge)
    ├── TransactionProcessingService.kt — @Scheduled drain + UNNEST batch finalize + startup recovery
    ├── SlaGuardService.kt              — @Scheduled sweeper: marks overdue rows FAILED/SLA_TIMEOUT
    ├── InProgressCounter.kt            — AtomicLong counter, initialized from DB on startup
    ├── OperationalMetricsService.kt    — Micrometer gauges: in_progress, queue_size
    └── ProcessingHeartbeat.kt          — tracks last claim/sweep timestamps for health check
```

Migrations: `platform-core/src/main/resources/db/migration/V1__create_transactions.sql`

### `transaction-generator` — Load generator

Single-file Kotlin coroutines application (`TransactionGeneratorApplication.kt`). Fires configurable
TPS at `platform-core`, optionally polls for final statuses, and prints a latency/SLA percentile
report (`p50 / p95 / p99`) at the end.

Two instances run in parallel (each 500 TPS) in separate Docker containers to avoid OS ephemeral
port exhaustion that would occur with a single container at 1000 TPS.

### `monitoring/`

```
monitoring/
├── prometheus/prometheus.yml           — scrapes /actuator/prometheus every 5 s
└── grafana/
    ├── provisioning/
    │   ├── datasources/datasource.yml  — auto-provisions Prometheus datasource
    │   └── dashboards/dashboards.yml   — auto-provisions dashboard from file
    └── dashboards/
        └── platform-core-overview.json — 8-panel dashboard (auto-loaded at startup)
```

## Performance characteristics

| Optimization | Effect |
|---|---|
| In-memory dispatch queue | Eliminates claim UPDATE round-trip → 2 DB writes/tx instead of 3 |
| UNNEST batch UPDATE | N rows finalized in 1 SQL round-trip |
| AtomicLong in-progress counter | O(1) overload check — no `COUNT(*)` on hot path |
| `synchronous_commit=off` | 5-10× PostgreSQL write throughput |
| Two generator containers | Separate Linux network namespaces → no port exhaustion |
| G1GC + 2 GB heap | GC pauses < 50 ms, never visible in SLA window |
