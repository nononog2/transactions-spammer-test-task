package com.example.platformcore.service

import com.example.platformcore.config.AppProperties
import com.example.platformcore.domain.Transaction
import com.example.platformcore.domain.TransactionStatus
import com.example.platformcore.exception.InvalidRequestException
import com.example.platformcore.exception.NotFoundException
import com.example.platformcore.exception.OverloadedException
import com.example.platformcore.persistence.TransactionRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
class TransactionService(
    private val transactionRepository: TransactionRepository,
    private val appProperties: AppProperties,
    private val inProgressCounter: InProgressCounter,
    private val dispatchQueue: TransactionDispatchQueue,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun create(command: CreateTransactionCommand): Transaction {
        if (command.accountFrom == command.accountTo) {
            throw InvalidRequestException("accountFrom and accountTo must be different")
        }

        // Check overload using in-memory counter — avoids COUNT(*) DB query on every request
        val inProgress = inProgressCounter.get()
        if (inProgress >= appProperties.processing.maxInProgress) {
            throw OverloadedException("Too many IN_PROGRESS transactions: $inProgress")
        }

        val now = Instant.now()
        val tx = Transaction(
            id = UUID.randomUUID(),
            requestId = command.requestId,
            status = TransactionStatus.IN_PROGRESS,
            accountFrom = command.accountFrom,
            accountTo = command.accountTo,
            amount = command.amount,
            currency = command.currency,
            createdAt = now,
            updatedAt = now,
            deadlineAt = now.plusMillis(appProperties.sla.timeoutMs),
            processingStartedAt = null,
            finalizedAt = null,
            failureReason = null,
        )

        return try {
            transactionRepository.insert(tx)
            inProgressCounter.increment()
            // Immediately dispatch to the in-memory queue so the processing cycle
            // can finalize this transaction without waiting for the next claimBatch
            // DB poll — eliminating one entire DB round-trip per transaction.
            if (!dispatchQueue.offer(tx)) {
                // Queue full (should not happen if queueCapacity >> maxInProgress).
                // SlaGuardService will fail it at deadline; log for visibility.
                log.warn("dispatch queue full, transaction {} will be recovered by SlaGuard", tx.id)
            }
            tx
        } catch (ex: DataIntegrityViolationException) {
            // Idempotency: duplicate requestId — return existing transaction
            if (command.requestId != null && transactionRepository.isUniqueViolation(ex)) {
                transactionRepository.findByRequestId(command.requestId)
                    ?: throw ex
            } else {
                throw ex
            }
        }
    }

    fun getById(id: UUID): Transaction =
        transactionRepository.findById(id) ?: throw NotFoundException("Transaction $id not found")
}

data class CreateTransactionCommand(
    val requestId: UUID?,
    val accountFrom: String,
    val accountTo: String,
    val amount: java.math.BigDecimal,
    val currency: String,
)
