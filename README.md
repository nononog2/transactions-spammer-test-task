# Transaction Processing System

A high-throughput transaction processing service built with Kotlin and Spring Boot, capable of handling **1000 TPS** with an end-to-end finalization SLA of **< 3 seconds**.

## Architecture

```
┌─────────────────────┐      POST /api/v1/transactions
│ transaction-        │ ─────────────────────────────────────────────────────────┐
│ generator-1 (500TPS)│                                                           │
└─────────────────────┘                                                           ▼
                                                                      ┌─────────────────────┐
┌─────────────────────┐                                               │    platform-core    │
│ transaction-        │ ─────────────────────────────────────────────▶│   (Spring Boot)     │
│ generator-2 (500TPS)│                                               │                     │
└─────────────────────┘                                               │  HTTP → INSERT      │
                                                                      │       ↓             │
                                                                      │  LinkedBlockingQueue│
                                                                      │       ↓             │
                                                                      │  Drain + UNNEST     │
                                                                      │  batch UPDATE       │
                                                                      └──────┬──────────────┘
                                                                             │
                                                                             ▼
                                                                      ┌─────────────┐
                                                                      │ PostgreSQL  │
                                                                      └─────────────┘
```

### Key design decisions

| Decision | Why |
|----------|-----|
| **In-memory dispatch queue** (`LinkedBlockingQueue`) | Decouples HTTP acceptance from DB finalization. Eliminates the `claim UPDATE` round-trip from the hot path → 2 DB writes per transaction instead of 3. |
| **UNNEST-based batch UPDATE** | Finalizes an entire drain batch in a single SQL round-trip instead of N individual statements. |
| **AtomicLong in-progress counter** | Replaces `COUNT(*) FROM transactions WHERE status='IN_PROGRESS'` on every request with an O(1) in-memory read. |
| **`synchronous_commit=off`** | PostgreSQL writes return before WAL flush → 5-10x write throughput. Acceptable for a load-test environment. |
| **Two generator containers** | Each container gets its own Linux network namespace with ~64k ephemeral ports, preventing `BindException` under sustained 1000 TPS load. |
| **G1GC + 2 GB heap** | Keeps GC pause times under 50 ms so they never show up as latency spikes in the SLA window. |

## Modules

```
├── platform-core/          — Spring Boot API service
│   ├── api/                — REST controllers + DTOs + exception handler
│   ├── config/             — AppProperties, SchedulingConfig, HealthIndicator
│   ├── domain/             — Transaction, TransactionStatus
│   ├── exception/          — NotFoundException, OverloadedException, InvalidRequestException
│   ├── persistence/        — TransactionRepository (JDBC, UNNEST batch)
│   └── service/            — TransactionService, TransactionProcessingService,
│                             SlaGuardService, InProgressCounter,
│                             TransactionDispatchQueue, OperationalMetricsService,
│                             ProcessingHeartbeat
│
├── transaction-generator/  — Kotlin coroutines load generator
│
└── monitoring/
    ├── prometheus/         — scrape config (5 s interval)
    └── grafana/
        ├── provisioning/   — auto-provisioned datasource + dashboard
        └── dashboards/     — platform-core-overview.json (8 panels)
```

## API

### Create transaction
```
POST /api/v1/transactions
Content-Type: application/json

{
  "requestId": "550e8400-e29b-41d4-a716-446655440000",  // optional, for idempotency
  "accountFrom": "ACC-001",
  "accountTo":   "ACC-002",
  "amount":      "100.00",
  "currency":    "USD"
}
```

Response `202 Accepted`:
```json
{
  "transactionId": "...",
  "status": "IN_PROGRESS",
  "createdAt": "2025-01-01T00:00:00Z",
  "deadlineAt": "2025-01-01T00:00:03Z"
}
```

Error responses: `400` (validation / same-account), `429` (overloaded > 25k in-progress).

### Get transaction
```
GET /api/v1/transactions/{transactionId}
```
Returns full transaction object including `status` (`IN_PROGRESS` | `SUCCEED` | `FAILED`) and `failureReason`.

## Processing flow

1. `POST /api/v1/transactions` → validates → `INSERT` into DB → pushes to `TransactionDispatchQueue` → returns `202`.
2. `TransactionProcessingService` drains up to 500 items every **10 ms**, decides outcome in memory (95% SUCCEED / 5% FAILED), issues a single `UNNEST` batch `UPDATE`.
3. `SlaGuardService` sweeps every **100 ms** and marks overdue `IN_PROGRESS` rows as `FAILED/SLA_TIMEOUT`.
4. On startup, `recoverStaleTransactions()` re-queues any `IN_PROGRESS` rows left by a previous crashed instance.

## Running

```bash
docker compose up --build
```

Services started:
| Service | URL |
|---------|-----|
| platform-core API | http://localhost:8080 |
| Prometheus | http://localhost:9090 |
| Grafana (admin/admin) | http://localhost:3000 |
| PostgreSQL | localhost:5432 |

## Observability

### Grafana dashboard (auto-provisioned)

Open http://localhost:3000 → **Platform Core Overview**. Contains 8 panels:

| Panel | Metric |
|-------|--------|
| Transactions In Progress | `transactions_in_progress` |
| Dispatch Queue Depth | `transactions_queue_size` |
| HTTP Throughput | `http_server_requests_seconds_count` (202 vs 429) |
| Finalized/s by Status | `transactions_finalized_total` |
| Latency (avg end-to-end + p99 HTTP) | `transactions_finalize_latency_seconds_*` |
| SLA Timeouts/s | `transactions_sla_timeout_total` |
| JVM Heap Memory | `jvm_memory_used_bytes` |
| HikariCP Connection Pool | `hikaricp_connections_active/pending` |

### Actuator endpoints
```
GET /actuator/health       — includes ProcessingHealthIndicator (claim/sweeper lag)
GET /actuator/prometheus   — all metrics for Prometheus scraping
```

### Acceptance criteria

After `docker compose up --build`, verify in Grafana:

1. **HTTP Throughput** panel shows ~1000 req/s sustained.
2. **Finalized/s** panel shows ~950 SUCCEED + ~50 FAILED (5% random failure rate).
3. **Latency** avg end-to-end stays well below 3000 ms.
4. **SLA Timeouts/s** stays at 0.
5. **Transactions In Progress** does not grow unboundedly.

## Configuration reference

All values are overridable via environment variables (see `docker-compose.yml`):

| Env var | Default | Description |
|---------|---------|-------------|
| `APP_SLA_TIMEOUT_MS` | 3000 | Transaction deadline from creation time |
| `APP_PROCESSING_BATCH_SIZE` | 500 | Max items per drain cycle |
| `APP_PROCESSING_POLL_INTERVAL_MS` | 10 | Drain cycle interval (ms) |
| `APP_PROCESSING_MAX_IN_PROGRESS` | 25000 | Overload threshold → 429 |
| `APP_PROCESSING_QUEUE_CAPACITY` | 50000 | In-memory queue capacity |
| `DB_POOL_MAX_SIZE` | 30 | HikariCP max connections |
| `SERVER_TOMCAT_MAX_THREADS` | 400 | Tomcat thread pool |
