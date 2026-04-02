package com.example.platformcore

import com.example.platformcore.config.AppProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(AppProperties::class)
class PlatformCoreApplication

fun main(args: Array<String>) {
    runApplication<PlatformCoreApplication>(*args)
}
