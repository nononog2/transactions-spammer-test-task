package com.example.platformcore.service

import com.example.platformcore.persistence.TransactionRepository
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class SlaGuardService(
    private val transactionRepository: TransactionRepository,
    private val meterRegistry: MeterRegistry,
) {

    @Scheduled(fixedDelayString = "\${app.sla.sweeper-interval-ms:100}")
    fun failExpiredTransactions() {
        val failed = transactionRepository.failExpired(Instant.now())
        if (failed > 0) {
            meterRegistry.counter("transactions.sla.timeout.total").increment(failed.toDouble())
        }
    }
}
