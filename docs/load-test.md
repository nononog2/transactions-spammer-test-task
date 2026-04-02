# Load test and SLA verification (steps 7-10)

## Goal
Verify that `platform-core` finalizes transactions within SLA (`<= 3000 ms`) while `transaction-generator` drives up to 1000 TPS.

## Run
```bash
docker compose up --build
```

Generator defaults:
- `TARGET_TPS=1000`
- `DURATION_SEC=30`
- `CONCURRENCY=200`
- `POLL_FINAL_STATUSES=true`
- `SLA_MS=3000`

## Metrics to inspect
- `transactions.in_progress`
- `transactions.claimed.total`
- `transactions.finalized.total{status=...}`
- `transactions.finalize.latency`
- `transactions.sla.timeout.total`

Actuator endpoints:
- `GET /actuator/health`
- `GET /actuator/metrics`
- `GET /actuator/prometheus`

## Acceptance checks
1. Generator realized TPS is close to 1000 (>= 90% target on local machine).
2. Finalization p99 <= 3000 ms.
3. `transactions.sla.timeout.total` stays near zero under nominal load.
4. No long-lived growth in `transactions.in_progress` after load completion.

## Useful SQL checks
```sql
SELECT status, count(*) FROM transactions GROUP BY status;

SELECT count(*)
FROM transactions
WHERE status = 'IN_PROGRESS'
  AND now() - created_at > interval '3 seconds';
```
