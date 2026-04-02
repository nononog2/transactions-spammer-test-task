package com.example.platformcore.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app")
data class AppProperties(
    val processing: Processing,
    val sla: Sla,
    val metrics: Metrics,
) {
    data class Processing(
        val workerCount: Int,
        val batchSize: Int,
        val pollIntervalMs: Long,
        val maxInProgress: Long,
    )

    data class Sla(
        val timeoutMs: Long,
        val sweeperIntervalMs: Long,
    )

    data class Metrics(
        val refreshIntervalMs: Long,
    )
}
