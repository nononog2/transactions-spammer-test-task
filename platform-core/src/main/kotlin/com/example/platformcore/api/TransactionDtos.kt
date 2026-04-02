package com.example.platformcore.api

import com.example.platformcore.domain.Transaction
import com.example.platformcore.domain.TransactionStatus
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class CreateTransactionRequest(
    val requestId: UUID? = null,
    @field:NotBlank
    val accountFrom: String,
    @field:NotBlank
    val accountTo: String,
    @field:NotNull
    @field:DecimalMin("0.01")
    val amount: BigDecimal,
    @field:Pattern(regexp = "^[A-Z]{3}$")
    val currency: String,
)

data class CreateTransactionResponse(
    val transactionId: UUID,
    val status: TransactionStatus,
    val createdAt: Instant,
    val deadlineAt: Instant,
)

data class TransactionResponse(
    val transactionId: UUID,
    val requestId: UUID?,
    val status: TransactionStatus,
    val accountFrom: String,
    val accountTo: String,
    val amount: BigDecimal,
    val currency: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deadlineAt: Instant,
    val processingStartedAt: Instant?,
    val finalizedAt: Instant?,
    val failureReason: String?,
)

fun Transaction.toCreateResponse(): CreateTransactionResponse = CreateTransactionResponse(
    transactionId = id,
    status = status,
    createdAt = createdAt,
    deadlineAt = deadlineAt,
)

fun Transaction.toResponse(): TransactionResponse = TransactionResponse(
    transactionId = id,
    requestId = requestId,
    status = status,
    accountFrom = accountFrom,
    accountTo = accountTo,
    amount = amount,
    currency = currency,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deadlineAt = deadlineAt,
    processingStartedAt = processingStartedAt,
    finalizedAt = finalizedAt,
    failureReason = failureReason,
)
