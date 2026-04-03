package com.example.platformcore.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.config.ScheduledTaskRegistrar
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = ["app.scheduling.enabled"], havingValue = "true", matchIfMissing = true)
class SchedulingConfig : SchedulingConfigurer {

    /**
     * Replace the default single-threaded TaskScheduler with a pool so that
     * claimAndDispatch, failExpiredTransactions, and refreshGauges run in parallel
     * and do not block each other under load.
     */
    override fun configureTasks(taskRegistrar: ScheduledTaskRegistrar) {
        val counter = AtomicInteger(0)
        taskRegistrar.setScheduler(
            Executors.newScheduledThreadPool(4) { r ->
                Thread(r, "scheduler-${counter.incrementAndGet()}").also { it.isDaemon = true }
            }
        )
    }
}
