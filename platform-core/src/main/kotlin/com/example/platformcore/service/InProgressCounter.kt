package com.example.platformcore.service

import com.example.platformcore.persistence.TransactionRepository
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory counter for IN_PROGRESS transactions.
 * Avoids expensive COUNT(*) DB queries on every create request.
 * Initialized from DB on startup to survive restarts correctly.
 */
@Component
class InProgressCounter(private val transactionRepository: TransactionRepository) {

    private val count = AtomicLong(0)

    @PostConstruct
    fun init() {
        count.set(transactionRepository.countInProgress())
    }

    fun get(): Long = count.get()

    fun increment(): Long = count.incrementAndGet()

    fun decrement(): Long = count.decrementAndGet()

    fun decrementBy(n: Long) {
        if (n > 0) count.addAndGet(-n)
    }
}
