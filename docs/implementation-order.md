# Architecture and Performance Design

## Transaction lifecycle

```
Client POST
    │
    ▼
TransactionController
    │  validate request (Bean Validation + accountFrom≠accountTo)
    │  check overload (InProgressCounter.get() >= maxInProgress → 429)
    │
    ▼
TransactionService.create()
    │  INSERT into transactions (status=IN_PROGRESS, deadline_at=now+3s)
    │  inProgressCounter.increment()
    │  dispatchQueue.offer(tx)          ← non-blocking, O(1)
    │
    └──► return 202 Accepted

────────────── async, every 10 ms ────────────────────────────────────────────

TransactionProcessingService.drainAndBatchFinalize()
    │  dispatchQueue.drainBatch(500)   ← drain up to 500 items
    │  for each tx: decide SUCCEED (95%) or FAILED (5%) in memory
    │
    ▼
TransactionRepository.batchFinalizeTransactions()
    │  single UPDATE … FROM (SELECT UNNEST(?::uuid[]) …) WHERE status='IN_PROGRESS'
    │  inProgressCounter.decrementBy(updated)
    │  record metrics: counter + timer

────────────── async, every 100 ms ───────────────────────────────────────────

SlaGuardService.failExpiredTransactions()
    │  UPDATE … SET status='FAILED', failure_reason='SLA_TIMEOUT'
    │  WHERE status='IN_PROGRESS' AND deadline_at <= now()
    │  inProgressCounter.decrementBy(failed)
```

## Database write budget per transaction

| Write | Who | Round-trips |
|---|---|---|
| INSERT (create) | HTTP thread | 1 |
| batch UPDATE (finalize) | Scheduler thread | 1 shared across batch |
| **Total** | | **2 per transaction** |

Previous approach had 3 (INSERT + claim UPDATE + finalize UPDATE).

## Key indexes

```sql
-- Used by: SlaGuardService (deadline sweep), bulk overdue check
CREATE INDEX idx_transactions_deadline ON transactions (deadline_at);

-- Used by: claimBatch crash recovery scan (startup only)
CREATE INDEX idx_transactions_in_progress_created ON transactions (created_at)
    WHERE status = 'IN_PROGRESS';

-- Used by: findById, batchFinalizeTransactions (WHERE t.id = v.id AND t.status='IN_PROGRESS')
CREATE INDEX idx_transactions_id_status ON transactions (id, status);
```

## Throughput math

At **1000 TPS** with `poll-interval-ms=10`:
- Each drain cycle processes ~10 transactions
- Each cycle = 1 SQL round-trip (UNNEST UPDATE)
- → 100 UPDATE round-trips/s to PostgreSQL (vs 1000 individual UPDATEs with the old approach)

With `synchronous_commit=off`, PostgreSQL acknowledges writes before WAL flush,
enabling ~5-10× higher INSERT/UPDATE throughput on a local SSD or Docker volume.

## Monitoring stack

```
platform-core /actuator/prometheus
        │  (scraped every 5 s)
        ▼
   Prometheus
        │  (queried by)
        ▼
    Grafana — Platform Core Overview dashboard
```

Custom metrics emitted by platform-core:

| Metric name | Type | Description |
|---|---|---|
| `transactions_in_progress` | Gauge | Current open transactions (AtomicLong) |
| `transactions_queue_size` | Gauge | Current dispatch queue depth |
| `transactions_claimed_total` | Counter | Transactions drained from queue |
| `transactions_finalized_total{status}` | Counter | Finalized by SUCCEED/FAILED |
| `transactions_finalize_latency_seconds` | Timer | End-to-end create→finalize latency |
| `transactions_sla_timeout_total` | Counter | Rows expired by SlaGuardService |

Spring Boot auto-metrics also available:
- `http_server_requests_seconds_*` — HTTP latency histograms
- `jvm_memory_*`, `jvm_gc_pause_seconds_*` — JVM internals
- `hikaricp_connections_*` — DB pool utilization
