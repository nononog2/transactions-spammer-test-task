package com.example.platformcore.service

import com.example.platformcore.config.AppProperties
import com.example.platformcore.domain.Transaction
import com.example.platformcore.domain.TransactionStatus
import com.example.platformcore.persistence.TransactionRepository
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@Service
class TransactionProcessingService(
    private val transactionRepository: TransactionRepository,
    private val appProperties: AppProperties,
    private val meterRegistry: MeterRegistry,
    private val heartbeat: ProcessingHeartbeat,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.processing.poll-interval-ms:50}")
    fun claimAndDispatch() {
        val batch = transactionRepository.claimBatch(appProperties.processing.batchSize)
        heartbeat.touchClaim()

        if (batch.isEmpty()) {
            return
        }

        meterRegistry.counter("transactions.claimed.total").increment(batch.size.toDouble())
        batch.forEach { processAsync(it) }
    }

    @Async("transactionWorkerExecutor")
    fun processAsync(tx: Transaction) {
        val now = Instant.now()
        val (finalStatus, reason) = when {
            now.isAfter(tx.deadlineAt) -> TransactionStatus.FAILED to "SLA_TIMEOUT"
            Random.nextInt(100) < 95 -> TransactionStatus.SUCCEED to null
            else -> TransactionStatus.FAILED to "PROCESSING_FAILED"
        }

        val updated = transactionRepository.finalizeTransaction(tx.id, finalStatus, reason)

        if (updated > 0) {
            meterRegistry.counter("transactions.finalized.total", "status", finalStatus.name).increment()
            val durationMs = Instant.now().toEpochMilli() - tx.createdAt.toEpochMilli()
            meterRegistry.timer("transactions.finalize.latency").record(durationMs, TimeUnit.MILLISECONDS)

            log.info(
                "transaction finalized id={} status={} durationMs={} reason={}",
                tx.id,
                finalStatus,
                durationMs,
                reason,
            )
        }
    }
}
