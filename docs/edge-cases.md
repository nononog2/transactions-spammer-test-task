# Edge Cases and Failure Scenarios

## Covered in current implementation

### 1. Idempotency on client retries

- `requestId` (UUID) is stored in a unique partial index (`uq_transactions_request_id WHERE request_id IS NOT NULL`).
- A duplicate `POST` with the same `requestId` catches `DataIntegrityViolationException`, looks up the existing row, and returns it — no duplicate transaction is created.
- `requestId` is optional; omitting it disables idempotency for that request.

### 2. Invalid transfer request

- `accountFrom == accountTo` is rejected immediately with `400 INVALID_REQUEST` before touching the DB.
- Bean Validation (`@NotBlank`, `@DecimalMin`, `@Pattern`) rejects malformed payloads with `400 VALIDATION_ERROR`.

### 3. Overload protection / backpressure

- `InProgressCounter` (AtomicLong) tracks open transactions in memory — O(1) per request.
- If the count reaches `app.processing.max-in-progress` (default 25 000), `POST` returns `429 OVERLOADED`.
- Counter is initialized from `SELECT count(*)` on startup so it survives restarts correctly.

### 4. Crash recovery / stale transaction handling

- `TransactionProcessingService.recoverStaleTransactions()` runs at `@PostConstruct`.
- It claims any `IN_PROGRESS` rows whose `processing_started_at < now() - reclaimTimeoutMs` (default 500 ms) and pushes them back into the in-memory dispatch queue.
- `SlaGuardService` sweeps every 100 ms and forcefully marks overdue rows `FAILED/SLA_TIMEOUT` so they never stay open past their deadline regardless of worker state.

### 5. Double-finalization safety

- Every finalize `UPDATE` includes `WHERE status = 'IN_PROGRESS'` — a row already finalized is silently skipped.
- DB-level constraint `chk_transactions_finalized_consistency` ensures `finalized_at` is set iff status is SUCCEED/FAILED.

### 6. Observability

- `ProcessingHealthIndicator` exposes `claimLagMs` and `sweeperLagMs` at `/actuator/health`.
  Health turns `DOWN` if either scheduler falls more than 20× its configured interval behind.
- Grafana dashboard includes SLA timeout rate and in-progress count to surface issues in real time.

### 7. Port exhaustion under sustained load

- A single container at 1000 TPS creates ~1000 TCP connections/s. With the OS default `TIME_WAIT` of 60 s, this exhausts ~28 k ephemeral ports in ~28 s.
- Mitigation: two generator containers (each 500 TPS) in separate Linux network namespaces, plus:
  - `tcp_tw_reuse=1` — immediate reuse of TIME_WAIT sockets
  - `ip_local_port_range=1024 65535` — expanded port range (~64 k)
  - `tcp_fin_timeout=15` — shorter TIME_WAIT recycle time
  - Ktor `keepAliveTime=30s` — connection reuse avoids TIME_WAIT entirely for most requests

## Known limitations

| Limitation | Impact | Mitigation in place |
|---|---|---|
| Time-based reclaim (no lease token) | Two workers could theoretically double-finalize the same row | `WHERE status = 'IN_PROGRESS'` guard prevents double-write |
| DB-only idempotency | No in-memory dedup cache; concurrent duplicate requests hit DB | Unique constraint + exception handling |
| No dead-letter queue | Permanently stuck rows are failed by SlaGuard at deadline | SLA sweeper runs every 100 ms |
| Single platform-core instance | No horizontal scaling in this setup | Architecture supports stateless scale-out; queue would need to be distributed |
