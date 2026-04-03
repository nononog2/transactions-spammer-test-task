# Load Test — 1000 TPS / 3 s SLA

## Goal

Verify that `platform-core` sustains **1000 TPS** and finalizes every transaction within the **3000 ms SLA**.

## Run

```bash
docker compose up --build
```

This starts:
- **platform-core** — waits for Postgres to be healthy, then starts accepting traffic
- **transaction-generator-1** + **transaction-generator-2** — each fires 500 TPS (1000 TPS combined); they wait for platform-core's `/actuator/health` to return `UP` before sending the first request
- **Prometheus** + **Grafana** — metrics collection and visualization

> Grafana dashboard auto-provisions at http://localhost:3000 (admin / admin) →
> **Platform Core Overview**

## Generator configuration (per instance)

| Env var | Value | Description |
|---|---|---|
| `TARGET_TPS` | 500 | Requests per second per instance (1000 total) |
| `DURATION_SEC` | 300 | Run duration in seconds |
| `CONCURRENCY` | 200 | Max concurrent in-flight requests |
| `POLL_FINAL_STATUSES` | true | Poll GET until SUCCEED/FAILED |
| `POLL_TIMEOUT_MS` | 3500 | Abort poll after this many ms |
| `SLA_MS` | 3000 | SLA threshold for the end-of-run report |

At the end of each run the generator prints a report:
```
requestsSent=15000 accepted=14998 overloaded=2
realizedTps=998.7
createP50=1ms createP95=3ms createP99=5ms
finalizedSucceeded=14248 finalizedFailed=750 finalizationTimeouts=0
finalizationP50=12ms finalizationP95=28ms finalizationP99=45ms
```

## Metrics to verify in Grafana

| Panel | What to look for |
|---|---|
| **HTTP Throughput** | ~1000 req/s on `202 accepted`; `429 overloaded` near zero |
| **Finalized/s by Status** | ~950 SUCCEED + ~50 FAILED (5% random failure rate by design) |
| **Latency** | avg end-to-end well below 3000 ms; p99 HTTP POST < 10 ms |
| **SLA Timeouts/s** | must stay at **0** under nominal load |
| **Transactions In Progress** | stable, does not grow unboundedly after load ends |
| **Dispatch Queue Depth** | stays near 0 — queue drains faster than it fills |

## Acceptance criteria

1. Realized TPS ≥ 900 (≥ 90% of 1000 target).
2. Generator finalization p99 ≤ 3000 ms.
3. `transactions_sla_timeout_total` = 0 during the run.
4. `transactions_in_progress` returns to 0 after generators stop.

## Useful SQL checks (connect to postgres container)

```bash
docker exec -it tst-postgres psql -U postgres -d transactions
```

```sql
-- Status breakdown
SELECT status, count(*) FROM transactions GROUP BY status;

-- Any transactions that exceeded SLA
SELECT count(*)
FROM transactions
WHERE failure_reason = 'SLA_TIMEOUT';

-- Avg finalization time
SELECT avg(extract(epoch FROM (finalized_at - created_at)) * 1000) AS avg_ms
FROM transactions
WHERE status IN ('SUCCEED', 'FAILED');
```
