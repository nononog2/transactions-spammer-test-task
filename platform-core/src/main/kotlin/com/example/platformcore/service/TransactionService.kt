package com.example.platformcore.service

import com.example.platformcore.config.AppProperties
import com.example.platformcore.domain.Transaction
import com.example.platformcore.domain.TransactionStatus
import com.example.platformcore.exception.InvalidRequestException
import com.example.platformcore.exception.NotFoundException
import com.example.platformcore.exception.OverloadedException
import com.example.platformcore.persistence.TransactionRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
class TransactionService(
    private val transactionRepository: TransactionRepository,
    private val appProperties: AppProperties,
    private val inProgressCounter: InProgressCounter,
) {

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
