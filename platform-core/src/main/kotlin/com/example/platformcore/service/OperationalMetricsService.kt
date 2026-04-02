package com.example.platformcore.service

import com.example.platformcore.persistence.TransactionRepository
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicLong

@Service
class OperationalMetricsService(
    private val transactionRepository: TransactionRepository,
    private val meterRegistry: MeterRegistry,
) {
    private val inProgressGauge = AtomicLong(0)

    @PostConstruct
    fun register() {
        meterRegistry.gauge("transactions.in_progress", inProgressGauge)
    }

    @Scheduled(fixedDelayString = "\${app.metrics.refresh-interval-ms:1000}")
    fun refreshGauges() {
        inProgressGauge.set(transactionRepository.countInProgress())
    }
}
