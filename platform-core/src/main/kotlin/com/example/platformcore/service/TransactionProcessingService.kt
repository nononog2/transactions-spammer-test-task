package com.example.platformcore.service

import com.example.platformcore.config.AppProperties
import com.example.platformcore.domain.TransactionStatus
import com.example.platformcore.persistence.BatchFinalizeCommand
import com.example.platformcore.persistence.TransactionRepository
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Drains the in-memory [TransactionDispatchQueue] every [pollIntervalMs] ms and
 * finalizes a whole batch in a single SQL UPDATE (UNNEST-based).
 *
 * Hot-path DB writes per transaction: INSERT (HTTP) + batch UPDATE (here) = 2 total,
 * down from the previous 3 (INSERT + claim UPDATE + finalize UPDATE).
 *
 * On startup, any IN_PROGRESS transactions left by a previous process instance are
 * re-queued via [recoverStaleTransactions] so they are processed promptly rather
 * than waiting for the SlaGuard sweeper.
 */
@Service
class TransactionProcessingService(
    private val transactionRepository: TransactionRepository,
    private val appProperties: AppProperties,
    private val meterRegistry: MeterRegistry,
    private val heartbeat: ProcessingHeartbeat,
    private val inProgressCounter: InProgressCounter,
    private val dispatchQueue: TransactionDispatchQueue,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * On startup, claim any IN_PROGRESS transactions that were never picked up
     * (e.g. left by a previous crashed instance) and push them into the queue
     * so they are processed immediately rather than timing out.
     */
    @PostConstruct
    fun recoverStaleTransactions() {
        val reclaimBefore = Instant.now().minusMillis(appProperties.processing.reclaimTimeoutMs)
        val stale = transactionRepository.claimBatch(appProperties.processing.batchSize, reclaimBefore)
        if (stale.isNotEmpty()) {
            stale.forEach { dispatchQueue.offer(it) }
            log.info("recovered {} stale IN_PROGRESS transactions into dispatch queue", stale.size)
        }
    }

    /**
     * Runs every [pollIntervalMs] ms.
     *
     * Drains up to [batchSize] transactions, decides SUCCEED/FAILED for each in memory
     * (zero DB reads), then issues ONE batched UPDATE for the whole set.
     * At 1000 TPS with pollIntervalMs=10ms: ~10 rows per batch → single round-trip.
     */
    @Scheduled(fixedDelayString = "\${app.processing.poll-interval-ms:10}")
    fun drainAndBatchFinalize() {
        val batch = dispatchQueue.drainBatch(appProperties.processing.batchSize)
        heartbeat.touchClaim()

        if (batch.isEmpty()) return

        val now = Instant.now()

        // Determine outcomes in memory — no DB reads needed
        val commands = batch.map { tx ->
            val (status, reason) = when {
                now.isAfter(tx.deadlineAt) -> TransactionStatus.FAILED to "SLA_TIMEOUT"
                Random.nextInt(100) < 95   -> TransactionStatus.SUCCEED to null
                else                       -> TransactionStatus.FAILED to "PROCESSING_FAILED"
            }
            BatchFinalizeCommand(
                id            = tx.id,
                status        = status,
                failureReason = reason,
                createdAt     = tx.createdAt,
            )
        }

        // Single UPDATE for the entire batch (UNNEST)
        val updated = transactionRepository.batchFinalizeTransactions(commands)

        if (updated > 0) {
            inProgressCounter.decrementBy(updated.toLong())
            meterRegistry.counter("transactions.claimed.total").increment(batch.size.toDouble())

            val finishedAt = Instant.now()
            commands.forEach { cmd ->
                meterRegistry.counter("transactions.finalized.total", "status", cmd.status.name).increment()
                val durationMs = finishedAt.toEpochMilli() - cmd.createdAt.toEpochMilli()
                meterRegistry.timer("transactions.finalize.latency").record(durationMs, TimeUnit.MILLISECONDS)
            }

            log.debug("batch finalized {} transactions ({} updated in DB)", batch.size, updated)
        }
    }
}
