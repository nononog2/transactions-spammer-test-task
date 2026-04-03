package com.example.platformcore.persistence

import com.example.platformcore.domain.Transaction
import com.example.platformcore.domain.TransactionStatus
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.PreparedStatementCallback
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Carries all data needed to finalize one transaction in the batch UPDATE. */
data class BatchFinalizeCommand(
    val id: UUID,
    val status: TransactionStatus,
    val failureReason: String?,
    val createdAt: Instant,
)

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

    /**
     * Used for crash-recovery on startup: atomically claims a batch of un-started
     * (or stale) IN_PROGRESS transactions so they can be re-queued into the
     * in-memory dispatch queue.
     */
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
                .addValue("reclaimBefore", reclaimBefore.toSqlTimestamp()),
            rowMapper,
        )
    }

    /**
     * Finalizes a batch of transactions in a **single round-trip** using UNNEST.
     *
     * At 1000 TPS with a 10 ms drain cycle, a batch is ~10 rows — one UPDATE instead
     * of 10 individual statements, reducing per-batch DB overhead dramatically.
     *
     * `NULLIF(failure_reason, '')` converts the empty-string sentinel (used for NULL
     * failure_reason in the array) back to SQL NULL.
     */
    fun batchFinalizeTransactions(commands: List<BatchFinalizeCommand>): Int {
        if (commands.isEmpty()) return 0

        val sql = """
            UPDATE transactions t
            SET
                status         = v.status,
                failure_reason = NULLIF(v.failure_reason, ''),
                finalized_at   = NOW(),
                updated_at     = NOW(),
                version        = version + 1
            FROM (
                SELECT
                    UNNEST(:ids::uuid[])      AS id,
                    UNNEST(:statuses::text[]) AS status,
                    UNNEST(:reasons::text[])  AS failure_reason
            ) v
            WHERE t.id = v.id
              AND t.status = 'IN_PROGRESS'
        """.trimIndent()

        // Explicit types are required to resolve ambiguity between
        // PreparedStatementCreator/Callback and CallableStatementCreator/Callback overloads.
        return jdbc.jdbcTemplate.execute(
            PreparedStatementCreator { con -> con.prepareStatement(sql) },
            PreparedStatementCallback<Int> { ps ->
                val idsArray      = ps.connection.createArrayOf("uuid",
                    commands.map { it.id.toString() }.toTypedArray())
                val statusesArray = ps.connection.createArrayOf("text",
                    commands.map { it.status.name }.toTypedArray())
                val reasonsArray  = ps.connection.createArrayOf("text",
                    commands.map { it.failureReason ?: "" }.toTypedArray())
                ps.setArray(1, idsArray)
                ps.setArray(2, statusesArray)
                ps.setArray(3, reasonsArray)
                ps.executeUpdate()
            }
        ) ?: 0
    }

    /** Legacy single-row finalize — kept for SlaGuardService compatibility if needed. */
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

        return jdbc.update(sql, MapSqlParameterSource("deadline", deadline.toSqlTimestamp()))
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
        .addValue("createdAt", createdAt.toSqlTimestamp())
        .addValue("updatedAt", updatedAt.toSqlTimestamp())
        .addValue("deadlineAt", deadlineAt.toSqlTimestamp())
        .addValue("processingStartedAt", processingStartedAt?.toSqlTimestamp())
        .addValue("finalizedAt", finalizedAt?.toSqlTimestamp())
        .addValue("failureReason", failureReason)

    private fun Instant.toSqlTimestamp(): Timestamp = Timestamp.from(this)

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
