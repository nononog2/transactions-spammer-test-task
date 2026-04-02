package com.example.platformcore.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor

@Configuration
@EnableAsync
class AsyncConfig {

    @Bean(name = ["transactionWorkerExecutor"])
    fun transactionWorkerExecutor(appProperties: AppProperties): Executor {
        val poolSize = appProperties.processing.workerCount
        return ThreadPoolTaskExecutor().apply {
            corePoolSize = poolSize
            maxPoolSize = poolSize
            setQueueCapacity(poolSize * 20)
            setThreadNamePrefix("tx-worker-")
            initialize()
        }
    }
}
