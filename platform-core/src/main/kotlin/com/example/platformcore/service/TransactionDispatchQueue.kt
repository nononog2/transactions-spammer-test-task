package com.example.platformcore.service

import com.example.platformcore.config.AppProperties
import com.example.platformcore.domain.Transaction
import org.springframework.stereotype.Component
import java.util.concurrent.LinkedBlockingQueue

/**
 * In-memory dispatch queue that decouples HTTP acceptance from transaction processing.
 *
 * When a transaction is created via HTTP, it is immediately pushed here so the worker
 * can finalize it without waiting for the next claimBatch DB poll cycle.
 * This eliminates the "claim UPDATE" round-trip entirely from the steady-state hot path,
 * reducing per-transaction DB writes from 3 to 2 (INSERT + finalize UPDATE).
 *
 * Capacity is set well above maxInProgress so that offer() never blocks under normal load.
 */
@Component
class TransactionDispatchQueue(appProperties: AppProperties) {

    private val queue = LinkedBlockingQueue<Transaction>(appProperties.processing.queueCapacity)

    /**
     * Non-blocking enqueue. Returns false only if the queue is full (capacity exceeded).
     */
    fun offer(tx: Transaction): Boolean = queue.offer(tx)

    /**
     * Drain up to [maxElements] transactions into a new list without blocking.
     */
    fun drainBatch(maxElements: Int): List<Transaction> {
        val batch = ArrayList<Transaction>(maxElements)
        queue.drainTo(batch, maxElements)
        return batch
    }

    fun size(): Int = queue.size
}
