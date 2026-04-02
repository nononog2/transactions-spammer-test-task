# Implementation order (step 12)

This is the exact build order to keep risk low and continuously runnable.

1. **Foundation**
   - Create multi-module build and compose skeleton.
   - Verify both modules compile.

2. **Core service skeleton**
   - Boot Spring app + DB connectivity + migrations.
   - Expose health endpoint.

3. **Schema and indexes**
   - Create `transactions` table.
   - Add hot-path indexes (`IN_PROGRESS`, `deadline_at`, transition updates).

4. **API contract**
   - Implement create/get endpoints.
   - Add request validation and API error model.

5. **Processing engine**
   - Claim-in-batch loop.
   - Async workers finalize to `SUCCEED|FAILED`.

6. **SLA enforcement**
   - Deadline per transaction.
   - Sweeper for overdue transactions.
   - Backpressure threshold.

7. **Generator**
   - TPS pacing + concurrency controls.
   - Optional polling for finalization and percentile report.

8. **Observability**
   - Metrics, processing health indicator, structured logs.

9. **Edge-case hardening**
   - Idempotency, stale-claim reclaim, processing error metrics.

10. **Acceptance run**
   - Execute load run, validate p99 and timeout conditions.
   - Capture commands and thresholds in docs.
