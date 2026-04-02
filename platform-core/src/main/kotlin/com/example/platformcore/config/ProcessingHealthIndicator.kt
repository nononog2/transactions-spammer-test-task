package com.example.platformcore.config

import com.example.platformcore.service.ProcessingHeartbeat
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component
import java.time.Instant

@Component("processing")
class ProcessingHealthIndicator(
    private val heartbeat: ProcessingHeartbeat,
    private val appProperties: AppProperties,
) : HealthIndicator {

    override fun health(): Health {
        val now = Instant.now().toEpochMilli()
        val claimLag = now - heartbeat.lastClaimEpochMs()
        val sweepLag = now - heartbeat.lastSlaSweepEpochMs()

        val claimThreshold = appProperties.processing.pollIntervalMs * 20
        val sweepThreshold = appProperties.sla.sweeperIntervalMs * 20

        val up = (heartbeat.lastClaimEpochMs() == 0L || claimLag <= claimThreshold) &&
            (heartbeat.lastSlaSweepEpochMs() == 0L || sweepLag <= sweepThreshold)

        return if (up) {
            Health.up()
                .withDetail("claimLagMs", claimLag)
                .withDetail("sweeperLagMs", sweepLag)
                .build()
        } else {
            Health.down()
                .withDetail("claimLagMs", claimLag)
                .withDetail("claimThresholdMs", claimThreshold)
                .withDetail("sweeperLagMs", sweepLag)
                .withDetail("sweeperThresholdMs", sweepThreshold)
                .build()
        }
    }
}
