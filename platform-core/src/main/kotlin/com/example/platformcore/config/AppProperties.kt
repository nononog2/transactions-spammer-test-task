package com.example.platformcore.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app")
data class AppProperties(
    val processing: Processing,
    val sla: Sla,
    val metrics: Metrics,
) {
    data class Processing(
        /** How many transactions to drain from the in-memory queue per processing cycle. */
        val batchSize: Int,
        /** Delay between drain+finalize cycles (ms). Lower = less latency, more DB pressure. */
        val pollIntervalMs: Long,
        /** Max allowed IN_PROGRESS count; HTTP returns 429 above this. */
        val maxInProgress: Long,
        /**
         * Recovery: reclaim IN_PROGRESS transactions that have been stuck longer than
         * this many ms (picked up by the @PostConstruct recovery scan on startup).
         */
        val reclaimTimeoutMs: Long,
        /**
         * Capacity of the in-memory dispatch queue.
         * Should be >> maxInProgress so offer() never fails under normal load.
         */
        val queueCapacity: Int,
    )

    data class Sla(
        val timeoutMs: Long,
        val sweeperIntervalMs: Long,
    )

    data class Metrics(
        val refreshIntervalMs: Long,
    )
}
