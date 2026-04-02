package com.example.platformcore.service

import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

@Component
class ProcessingHeartbeat {
    private val lastClaimEpochMs = AtomicLong(0)
    private val lastSlaSweepEpochMs = AtomicLong(0)

    fun touchClaim() {
        lastClaimEpochMs.set(Instant.now().toEpochMilli())
    }

    fun touchSlaSweep() {
        lastSlaSweepEpochMs.set(Instant.now().toEpochMilli())
    }

    fun lastClaimEpochMs(): Long = lastClaimEpochMs.get()
    fun lastSlaSweepEpochMs(): Long = lastSlaSweepEpochMs.get()
}
