package com.example.platformcore.service

import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Service

/**
 * Registers operational gauges on application startup.
 *
 * All gauges are O(1) — backed by in-memory counters/queues rather than DB queries,
 * so they add zero overhead to the hot path.
 *
 * Exposed metrics:
 *   - transactions.in_progress   — current number of unfinalized transactions
 *   - transactions.queue.size    — current depth of the in-memory dispatch queue
 */
@Service
class OperationalMetricsService(
    private val inProgressCounter: InProgressCounter,
    private val dispatchQueue: TransactionDispatchQueue,
    private val meterRegistry: MeterRegistry,
) {
    @PostConstruct
    fun register() {
        meterRegistry.gauge("transactions.in_progress", inProgressCounter) { it.get().toDouble() }
        meterRegistry.gauge("transactions.queue.size", dispatchQueue) { it.size().toDouble() }
    }
}
