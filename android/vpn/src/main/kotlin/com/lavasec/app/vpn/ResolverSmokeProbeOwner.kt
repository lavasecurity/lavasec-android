package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.DnsMessage
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverSmokeProbeCadence
import com.lavasec.app.foundation.dns.ResolverTransport
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.TimeUnit
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Exact frozen canaries and the stricter health bar; organic negative answers stay authoritative. */
internal object DnsResolverSmokeProbe {
    private val domains = listOf("example.com", "apple.com", "cloudflare.com")
    enum class Reply { ACCEPTED_ANSWER, REJECTED_OR_ANSWERLESS, INVALID }

    fun domain(sequence: Long): String = domains[Math.floorMod(sequence, domains.size.toLong()).toInt()]

    fun query(sequence: Long, transactionId: Int): ByteArray {
        require(transactionId in 0..65535)
        return ByteArrayOutputStream().apply {
            write(transactionId ushr 8); write(transactionId)
            write(byteArrayOf(1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            domain(sequence).split('.').forEach { label ->
                val bytes = label.toByteArray(Charsets.US_ASCII)
                write(bytes.size); write(bytes)
            }
            write(byteArrayOf(0, 0, 1, 0, 1))
        }.toByteArray().also { DnsMessage.parseQuestion(it) }
    }

    fun classify(query: ByteArray, response: ByteArray): Reply {
        // Transport already proved its peer; the neutral validator here repeats exact query
        // identity/structure without inventing a UDP source tuple or relaxing an encrypted leg.
        val valid = ResponseValidation.validate(query, response, ResolverTransport.WIREGUARD_UDP)
            as? ResponseValidationResult.Valid ?: return Reply.INVALID
        if (valid.truncated) return Reply.INVALID
        val walk = DnsResponseWireWalker.walk(response) ?: return Reply.INVALID
        val opt = walk.records.firstOrNull { it.type == 41 }
        val extendedRcode = opt?.let { response[it.ttlOffset].toInt() and 255 } ?: 0
        val rcode = (response[3].toInt() and 15) or (extendedRcode shl 4)
        val answerCount = ((response[6].toInt() and 255) shl 8) or (response[7].toInt() and 255)
        return if (rcode == 0 && answerCount > 0) Reply.ACCEPTED_ANSWER else Reply.REJECTED_OR_ANSWERLESS
    }
}

/**
 * Transient query scheduling for the existing runtime heartbeat; owns no timer, engine or store.
 * A borrowed installed engine owns all sockets/query leases. Only its primary is contacted, and
 * completion must pass both its publication fence and the service owner's current epoch fence.
 */
internal class ResolverSmokeProbeOwner(
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val queryIds: () -> Long,
    private val isRuntimeCurrent: (ReadyDnsEngine) -> Boolean,
    private val health: () -> ExactSessionHealthOwner?,
    private val timeoutMillis: Long = 8_000L,
    private val nowNanos: () -> Long = SystemMonotonicClock::nowNanos,
) : AutoCloseable {
    private val lock = ReentrantLock()
    private var installed: ReadyDnsEngine? = null
    private var running: Job? = null
    private var closed = false
    private var sequence = 0L
    private var lastStartedAtMillis: Long? = null

    init { require(timeoutMillis > 0L) }

    /** Called only after the real ready engine has been adopted, never for chained mode. */
    fun install(engine: ReadyDnsEngine) {
        val previous = lock.withLock {
            if (closed) return
            installed = engine
            lastStartedAtMillis = null
            running.also { running = null }
        }
        previous?.cancel()
        tick(force = true)
    }

    /** Initial/rebind events force a probe; routine heartbeat alone uses bounded chronic cadence. */
    fun tick(force: Boolean = false) {
        val job = lock.withLock {
            if (closed || running != null) return
            val engine = installed ?: return
            if (!engine.isCurrent() || !isRuntimeCurrent(engine)) return
            val exactHealth = health() ?: return
            if (!exactHealth.primaryProbeIsCurrent(engine.epoch)) return
            val now = nowMillis()
            val elapsed = lastStartedAtMillis?.let { now - it }
            val interval = ResolverSmokeProbeCadence.intervalMillis(exactHealth.current().smokeProbeFailureCount)
            if (!force && exactHealth.hasRecentOrganicPrimaryAnswer(engine.epoch, now,
                    ResolverSmokeProbeCadence.BASE_INTERVAL_MILLIS)) return
            if (!force && elapsed != null && elapsed >= 0L && elapsed < interval) return
            val token = sequence
            sequence = Math.addExact(sequence, 1L)
            val queryId = queryIds()
            val query = DnsResolverSmokeProbe.query(token, (queryId and 0xffff).toInt())
            lastStartedAtMillis = now
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (!engine.isCurrent() || !isRuntimeCurrent(engine) ||
                        !exactHealth.primaryProbeIsCurrent(engine.epoch)) return@launch
                    val request = ResolverRequest(query, DnsMessage.parseQuestion(query), engine.epoch, engine.networkContext, queryId)
                    val startedAtNanos = nowNanos()
                    val result = try {
                        withTimeout(timeoutMillis) { engine.resolvePrimaryIfCurrent(request) }
                    } catch (_: TimeoutCancellationException) {
                        ResolverResult.Failure(ResolverFailureReason.TIMEOUT)
                    } catch (_: IOException) {
                        ResolverResult.Failure(ResolverFailureReason.SOCKET_FAILED)
                    }
                    if (result is ResolverResult.Failure && result.reason.isProbeAbandonment()) return@launch
                    engine.completeIfCurrent {
                        if (!isRuntimeCurrent(engine)) false else {
                            val completedAtNanos = nowNanos()
                            val latencyMillis = if (completedAtNanos >= startedAtNanos) {
                                TimeUnit.NANOSECONDS.toMillis((completedAtNanos - startedAtNanos).coerceAtLeast(0L))
                            } else 0L
                            exactHealth.recordSmokeProbe(engine.epoch, token, queryIds(), query, result, latencyMillis)
                            true
                        }
                    }
                } catch (_: CancellationException) {
                    // Retirement/explicit cancellation did not establish a resolver failure.
                } finally {
                    lock.withLock { if (installed === engine && sequence == token + 1L) running = null }
                }
            }.also { running = it }
        }
        job.start()
    }

    override fun close() {
        val previous = lock.withLock {
            if (closed) return
            closed = true
            installed = null
            running.also { running = null }
        }
        previous?.cancel()
    }
}

internal fun ResolverFailureReason.isProbeAbandonment(): Boolean = this == ResolverFailureReason.CANCELLED ||
    this == ResolverFailureReason.STALE_EPOCH || this == ResolverFailureReason.OVERLOADED || this == ResolverFailureReason.BACKED_OFF
