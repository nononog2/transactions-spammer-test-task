package com.example.platformcore.domain

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class Transaction(
    val id: UUID,
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
