package com.example.transactiongenerator

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.jackson.jackson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

private val log = LoggerFactory.getLogger("transaction-generator")
private val responseMapper = ObjectMapper().registerModule(JavaTimeModule())

fun main() = runBlocking {
    val cfg = GeneratorConfig.fromEnv()
    log.info("Starting generator: targetTps={}, durationSec={}, concurrency={}, pollFinalStatuses={}", cfg.targetTps, cfg.durationSec, cfg.concurrency, cfg.pollFinalStatuses)

    HttpClient(CIO) {
        install(ContentNegotiation) {
            jackson {
                registerModule(JavaTimeModule())
            }
        }
        engine {
            // Global connection pool.  Sized at concurrency * 2 so there is always a
            // free slot for both creates (semaphore-gated) and concurrent polls.
            maxConnectionsCount = cfg.concurrency * 2

            endpoint {
                // Per-route (host:port) connection limit — must equal the global limit
                // for a single-target test so the per-route cap never fires first.
                maxConnectionsPerRoute = cfg.concurrency * 2

                // Keep idle connections alive for 30 s.  This is the primary lever
                // against BindException: as long as a connection is reused the port
                // stays open and never enters TIME_WAIT.
                keepAliveTime = 30_000

                // How many pipelined requests a single TCP connection may carry.
                // 20 in-flight requests per connection → at 1000 TPS only ~2 ms per
                // request, so ≈ 2 concurrent; with pipelining one connection suffices
                // for the entire load and new TCP connections are rarely opened.
                pipelineMaxSize = 20

                connectTimeout = 5_000
                connectRetryAttempts = 0
            }

            requestTimeout = 5_000
        }
    }.use { client ->
        val runner = GeneratorRunner(client, cfg)
        val report = runner.run()
        log.info("Load report: {}", report)

        if (report.requestsSent == 0L) {
            error("No requests were sent")
        }

        if (report.realizedTps < cfg.targetTps * 0.9) {
            log.warn("Realized TPS is below target: {} < {}", report.realizedTps, cfg.targetTps)
        }

        if (report.finalizationP99Ms != null && report.finalizationP99Ms > cfg.slaMs) {
            log.warn("Finalization p99 breached SLA: {}ms > {}ms", report.finalizationP99Ms, cfg.slaMs)
        }
    }
}

data class GeneratorConfig(
    val baseUrl: String,
    val targetTps: Int,
    val durationSec: Int,
    val concurrency: Int,
    val pollFinalStatuses: Boolean,
    val pollTimeoutMs: Long,
    val pollIntervalMs: Long,
    val slaMs: Long,
) {
    companion object {
        fun fromEnv(): GeneratorConfig = GeneratorConfig(
            baseUrl = System.getenv("TARGET_URL") ?: "http://localhost:8080",
            targetTps = (System.getenv("TARGET_TPS") ?: "1000").toInt(),
            durationSec = (System.getenv("DURATION_SEC") ?: "30").toInt(),
            concurrency = (System.getenv("CONCURRENCY") ?: "200").toInt(),
            pollFinalStatuses = (System.getenv("POLL_FINAL_STATUSES") ?: "true").toBoolean(),
            pollTimeoutMs = (System.getenv("POLL_TIMEOUT_MS") ?: "3500").toLong(),
            pollIntervalMs = (System.getenv("POLL_INTERVAL_MS") ?: "50").toLong(),
            slaMs = (System.getenv("SLA_MS") ?: "3000").toLong(),
        )
    }
}

class GeneratorRunner(
    private val client: HttpClient,
    private val cfg: GeneratorConfig,
) {
    suspend fun run(): LoadReport = coroutineScope {
        val stats = StatsCollector()
        val semaphore = Semaphore(max(1, cfg.concurrency))
        val start = Instant.now()
        val totalRequests = cfg.targetTps.toLong() * cfg.durationSec
        val spacingNanos = 1_000_000_000L / max(1, cfg.targetTps)
        val jobs = ArrayList<kotlinx.coroutines.Deferred<Unit>>(totalRequests.toInt())

        repeat(totalRequests.toInt()) { idx ->
            val scheduledAtNanos = idx * spacingNanos
            val elapsedNanos = Duration.between(start, Instant.now()).toNanos()
            val delayNanos = scheduledAtNanos - elapsedNanos
            if (delayNanos > 0) {
                delay(delayNanos / 1_000_000)
            }

            jobs += async(Dispatchers.IO) {
                // Semaphore gates ONLY the create request (~1-2 ms).
                // Polling must run OUTSIDE the permit: if polls (up to pollTimeoutMs=3500ms)
                // were inside, each of the 200 slots would be held for seconds, capping
                // throughput to ~200/3500ms ≈ 57 TPS and causing a total stall once the
                // scheduler exhausts all 30 000 slots waiting for semaphore.
                val response = semaphore.withPermit {
                    val reqStart = Instant.now()
                    val r = sendCreateRequest()
                    stats.onCreate(r.status, Duration.between(reqStart, Instant.now()).toMillis())
                    r
                }
                if (cfg.pollFinalStatuses && response.status == HttpStatusCode.Accepted && response.body != null) {
                    pollUntilFinal(response.body.transactionId, response.body.createdAtInstantOrNow(), stats)
                }
            }
        }

        jobs.awaitAll()
        val totalDurationMs = Duration.between(start, Instant.now()).toMillis().coerceAtLeast(1)
        stats.buildReport(totalDurationMs)
    }

    private suspend fun sendCreateRequest(): CreateCallResult {
        val payload = CreateTransactionRequest(
            requestId = UUID.randomUUID(),
            accountFrom = "A-${UUID.randomUUID().toString().take(8)}",
            accountTo = "B-${UUID.randomUUID().toString().take(8)}",
            amount = BigDecimal("100.00"),
            currency = "USD",
        )

        return runCatching {
            val response = client.post("${cfg.baseUrl}/api/v1/transactions") {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            if (response.status == HttpStatusCode.Accepted) {
                val payload = response.bodyAsText()
                CreateCallResult(response.status, parseCreateTransactionResponse(payload))
            } else {
                CreateCallResult(response.status, null)
            }
        }.getOrElse {
            log.debug("create request failed", it)
            CreateCallResult(HttpStatusCode.ServiceUnavailable, null)
        }
    }

    private fun parseCreateTransactionResponse(payload: String): CreateTransactionResponse = runCatching {
        responseMapper.readValue(payload, CreateTransactionResponse::class.java)
    }.getOrElse {
        val node = responseMapper.readTree(payload)
        CreateTransactionResponse(
            transactionId = UUID.fromString(node.requiredText("transactionId")),
            status = node.requiredText("status"),
            createdAt = node.requiredText("createdAt"),
            deadlineAt = node.requiredText("deadlineAt"),
        )
    }

    private suspend fun pollUntilFinal(transactionId: UUID, createdAt: Instant, stats: StatsCollector) {
        val started = Instant.now()
        while (Duration.between(started, Instant.now()).toMillis() <= cfg.pollTimeoutMs) {
            val response = runCatching {
                client.get("${cfg.baseUrl}/api/v1/transactions/$transactionId")
            }.getOrNull() ?: return

            if (response.status != HttpStatusCode.OK) {
                return
            }

            val tx = runCatching { response.body<TransactionResponse>() }.getOrNull() ?: return
            if (tx.status == "SUCCEED" || tx.status == "FAILED") {
                val finMs = Duration.between(createdAt, Instant.now()).toMillis()
                stats.onFinalized(finMs, tx.status)
                return
            }
            delay(cfg.pollIntervalMs)
        }
        stats.onFinalizationTimeout()
    }
}

class StatsCollector {
    private val sent = AtomicLong(0)
    private val accepted = AtomicLong(0)
    private val overloaded = AtomicLong(0)
    private val failed = AtomicLong(0)
    private val finalizedSucceeded = AtomicLong(0)
    private val finalizedFailed = AtomicLong(0)
    private val finalizationTimeouts = AtomicLong(0)
    private val createLatencies = ConcurrentLinkedQueue<Long>()
    private val finalizationLatencies = ConcurrentLinkedQueue<Long>()

    fun onCreate(status: HttpStatusCode, latencyMs: Long) {
        sent.incrementAndGet()
        createLatencies.add(latencyMs)
        when (status) {
            HttpStatusCode.Accepted -> accepted.incrementAndGet()
            HttpStatusCode.TooManyRequests -> overloaded.incrementAndGet()
            else -> failed.incrementAndGet()
        }
    }

    fun onFinalized(latencyMs: Long, status: String) {
        finalizationLatencies.add(latencyMs)
        if (status == "SUCCEED") finalizedSucceeded.incrementAndGet() else finalizedFailed.incrementAndGet()
    }

    fun onFinalizationTimeout() {
        finalizationTimeouts.incrementAndGet()
    }

    fun buildReport(totalDurationMs: Long): LoadReport = LoadReport(
        requestsSent = sent.get(),
        accepted = accepted.get(),
        overloaded = overloaded.get(),
        failed = failed.get(),
        realizedTps = (sent.get() * 1000.0) / totalDurationMs,
        createP50Ms = percentile(createLatencies, 50.0),
        createP95Ms = percentile(createLatencies, 95.0),
        createP99Ms = percentile(createLatencies, 99.0),
        finalizedSucceeded = finalizedSucceeded.get(),
        finalizedFailed = finalizedFailed.get(),
        finalizationTimeouts = finalizationTimeouts.get(),
        finalizationP50Ms = percentile(finalizationLatencies, 50.0),
        finalizationP95Ms = percentile(finalizationLatencies, 95.0),
        finalizationP99Ms = percentile(finalizationLatencies, 99.0),
    )

    private fun percentile(values: ConcurrentLinkedQueue<Long>, p: Double): Long? {
        if (values.isEmpty()) return null
        val sorted = values.toMutableList().sorted()
        val idx = ((p / 100.0) * (sorted.size - 1)).toInt()
        return sorted[idx]
    }
}

data class LoadReport(
    val requestsSent: Long,
    val accepted: Long,
    val overloaded: Long,
    val failed: Long,
    val realizedTps: Double,
    val createP50Ms: Long?,
    val createP95Ms: Long?,
    val createP99Ms: Long?,
    val finalizedSucceeded: Long,
    val finalizedFailed: Long,
    val finalizationTimeouts: Long,
    val finalizationP50Ms: Long?,
    val finalizationP95Ms: Long?,
    val finalizationP99Ms: Long?,
)

data class CreateTransactionRequest(
    val requestId: UUID,
    val accountFrom: String,
    val accountTo: String,
    val amount: BigDecimal,
    val currency: String,
)

data class CreateTransactionResponse(
    val transactionId: UUID,
    val status: String,
    val createdAt: String,
    val deadlineAt: String,
) {
    fun createdAtInstantOrNow(): Instant = runCatching { Instant.parse(createdAt) }.getOrElse { Instant.now() }
}

data class TransactionResponse(
    val transactionId: UUID,
    val status: String,
)

data class CreateCallResult(
    val status: HttpStatusCode,
    val body: CreateTransactionResponse?,
)


private fun com.fasterxml.jackson.databind.JsonNode.requiredText(field: String): String = this.get(field)?.asText()
    ?: error("Missing field '$field' in response payload")
