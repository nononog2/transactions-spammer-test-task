package com.example.platformcore.service

import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Service

/**
 * Registers operational gauges.
 *
 * transactions.in_progress is backed by the in-memory [InProgressCounter] (O(1))
 * rather than a periodic COUNT(*) DB query, so the gauge stays accurate in real-time
 * without any scheduler overhead.
 */
@Service
class OperationalMetricsService(
    private val inProgressCounter: InProgressCounter,
    private val meterRegistry: MeterRegistry,
) {
    @PostConstruct
    fun register() {
        meterRegistry.gauge("transactions.in_progress", inProgressCounter) { it.get().toDouble() }
    }
}
