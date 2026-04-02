# Edge cases and failure scenarios (step 11)

## Covered in current implementation

1. **Idempotency on client retries**
   - `requestId` is unique (`uq_transactions_request_id`).
   - Duplicate create with same `requestId` returns existing transaction instead of creating a duplicate.

2. **Invalid transfer request**
   - `accountFrom == accountTo` is rejected with `400 INVALID_REQUEST`.

3. **Overload protection / backpressure**
   - If `IN_PROGRESS` reaches `max-in-progress`, create API returns `429`.

4. **Worker crash / stalled claim handling**
   - Claiming supports reclaiming stale `processing_started_at` via configurable timeout.
   - SLA sweeper marks overdue `IN_PROGRESS` as `FAILED/SLA_TIMEOUT`.

5. **Double-finalization safety**
   - Final status update only applies when current status is still `IN_PROGRESS`.

6. **Observability for incidents**
   - Processing errors increment `transactions.processing.errors.total`.
   - Health indicator exposes claim/sweeper lag.

## Known limitations (explicit)

- Reclaim is time-based and simple; no lease token/version fencing yet.
- No distributed idempotency cache (DB uniqueness only).
- No dead-letter queue because architecture intentionally avoids extra components.
