package com.example.platformcore.persistence

import com.example.platformcore.domain.Transaction
import com.example.platformcore.domain.TransactionStatus
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

@Repository
class TransactionRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {

    fun insert(tx: Transaction) {
        val sql = """
            INSERT INTO transactions (
                id, request_id, status, account_from, account_to, amount, currency,
                created_at, updated_at, deadline_at, processing_started_at, finalized_at, failure_reason, version
            ) VALUES (
                :id, :requestId, :status, :accountFrom, :accountTo, :amount, :currency,
                :createdAt, :updatedAt, :deadlineAt, :processingStartedAt, :finalizedAt, :failureReason, 0
            )
        """.trimIndent()

        jdbc.update(sql, tx.params())
    }

    fun findById(id: UUID): Transaction? {
        val sql = "SELECT * FROM transactions WHERE id = :id"
        return jdbc.query(sql, MapSqlParameterSource("id", id), rowMapper).firstOrNull()
    }

    fun findByRequestId(requestId: UUID): Transaction? {
        val sql = "SELECT * FROM transactions WHERE request_id = :requestId"
        return jdbc.query(sql, MapSqlParameterSource("requestId", requestId), rowMapper).firstOrNull()
    }

    fun countInProgress(): Long {
        val sql = "SELECT count(*) FROM transactions WHERE status = 'IN_PROGRESS'"
        return jdbc.queryForObject(sql, emptyMap<String, Any>(), Long::class.java) ?: 0L
    }

    fun claimBatch(limit: Int, reclaimBefore: Instant): List<Transaction> {
        val sql = """
            WITH claimable AS (
                SELECT id
                FROM transactions
                WHERE status = 'IN_PROGRESS'
                  AND (processing_started_at IS NULL OR processing_started_at < :reclaimBefore)
                ORDER BY created_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
            )
            UPDATE transactions t
            SET processing_started_at = NOW(),
                updated_at = NOW()
            FROM claimable
            WHERE t.id = claimable.id
            RETURNING t.*
        """.trimIndent()

        return jdbc.query(
            sql,
            MapSqlParameterSource()
                .addValue("limit", limit)
                .addValue("reclaimBefore", reclaimBefore),
            rowMapper,
        )
    }

    fun finalizeTransaction(id: UUID, status: TransactionStatus, failureReason: String? = null): Int {
        val sql = """
            UPDATE transactions
            SET status = :status,
                failure_reason = :failureReason,
                finalized_at = NOW(),
                updated_at = NOW(),
                version = version + 1
            WHERE id = :id
              AND status = 'IN_PROGRESS'
        """.trimIndent()

        return jdbc.update(
            sql,
            MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", status.name)
                .addValue("failureReason", failureReason),
        )
    }

    fun failExpired(deadline: Instant): Int {
        val sql = """
            UPDATE transactions
            SET status = 'FAILED',
                failure_reason = 'SLA_TIMEOUT',
                finalized_at = NOW(),
                updated_at = NOW(),
                version = version + 1
            WHERE status = 'IN_PROGRESS'
              AND deadline_at <= :deadline
        """.trimIndent()

        return jdbc.update(sql, MapSqlParameterSource("deadline", deadline))
    }

    fun isUniqueViolation(ex: DataIntegrityViolationException): Boolean {
        val message = ex.rootCause?.message ?: ex.message ?: ""
        return message.contains("uq_transactions_request_id", ignoreCase = true)
    }

    private fun Transaction.params(): MapSqlParameterSource = MapSqlParameterSource()
        .addValue("id", id)
        .addValue("requestId", requestId)
        .addValue("status", status.name)
        .addValue("accountFrom", accountFrom)
        .addValue("accountTo", accountTo)
        .addValue("amount", amount)
        .addValue("currency", currency)
        .addValue("createdAt", createdAt)
        .addValue("updatedAt", updatedAt)
        .addValue("deadlineAt", deadlineAt)
        .addValue("processingStartedAt", processingStartedAt)
        .addValue("finalizedAt", finalizedAt)
        .addValue("failureReason", failureReason)

    private val rowMapper = RowMapper<Transaction> { rs, _ ->
        rs.toTransaction()
    }

    private fun ResultSet.toTransaction(): Transaction = Transaction(
        id = getObject("id", UUID::class.java),
        requestId = getObject("request_id", UUID::class.java),
        status = TransactionStatus.valueOf(getString("status")),
        accountFrom = getString("account_from"),
        accountTo = getString("account_to"),
        amount = getBigDecimal("amount"),
        currency = getString("currency"),
        createdAt = getTimestamp("created_at").toInstant(),
        updatedAt = getTimestamp("updated_at").toInstant(),
        deadlineAt = getTimestamp("deadline_at").toInstant(),
        processingStartedAt = getTimestamp("processing_started_at")?.toInstant(),
        finalizedAt = getTimestamp("finalized_at")?.toInstant(),
        failureReason = getString("failure_reason"),
    )
}
