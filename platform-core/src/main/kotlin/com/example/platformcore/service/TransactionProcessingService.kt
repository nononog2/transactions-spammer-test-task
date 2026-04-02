package com.example.platformcore.service

import com.example.platformcore.config.AppProperties
import com.example.platformcore.domain.Transaction
import com.example.platformcore.domain.TransactionStatus
import com.example.platformcore.persistence.TransactionRepository
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant
import kotlin.random.Random

@Service
class TransactionProcessingService(
    private val transactionRepository: TransactionRepository,
    private val appProperties: AppProperties,
    private val meterRegistry: MeterRegistry,
) {

    @Scheduled(fixedDelayString = "\${app.processing.poll-interval-ms:50}")
    fun claimAndDispatch() {
        val batch = transactionRepository.claimBatch(appProperties.processing.batchSize)
        if (batch.isEmpty()) {
            return
        }

        meterRegistry.counter("transactions.claimed.total").increment(batch.size.toDouble())
        batch.forEach { processAsync(it) }
    }

    @Async("transactionWorkerExecutor")
    fun processAsync(tx: Transaction) {
        val finalStatus = if (Random.nextInt(100) < 95) TransactionStatus.SUCCEED else TransactionStatus.FAILED
        val reason = if (finalStatus == TransactionStatus.FAILED) "PROCESSING_FAILED" else null
        val updated = transactionRepository.finalizeTransaction(tx.id, finalStatus, reason)

        if (updated > 0) {
            meterRegistry.counter("transactions.finalized.total", "status", finalStatus.name).increment()
            val durationMs = Instant.now().toEpochMilli() - tx.createdAt.toEpochMilli()
            meterRegistry.timer("transactions.finalize.latency").record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }
}
