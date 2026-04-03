package com.example.platformcore.service

import com.example.platformcore.persistence.TransactionRepository
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class SlaGuardService(
    private val transactionRepository: TransactionRepository,
    private val meterRegistry: MeterRegistry,
    private val heartbeat: ProcessingHeartbeat,
    private val inProgressCounter: InProgressCounter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.sla.sweeper-interval-ms:100}")
    fun failExpiredTransactions() {
        val failed = transactionRepository.failExpired(Instant.now())
        heartbeat.touchSlaSweep()

        if (failed > 0) {
            inProgressCounter.decrementBy(failed.toLong())
            meterRegistry.counter("transactions.sla.timeout.total").increment(failed.toDouble())
            log.warn("sla sweeper failed {} expired transactions", failed)
        }
    }
}
