package com.lavasec.app.vpn

import android.net.Network
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverTransport
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun interface DoqAddressLookup {
    suspend fun resolve(network: Network, host: String): List<InetAddress>
}

/** Cancellation releases the caller even if Android's blocking bootstrap DNS is delayed. */
internal object NetworkDoqAddressLookup : DoqAddressLookup {
    private val executor = Executors.newFixedThreadPool(2) { work ->
        Thread(work, "lavasec-doq-bootstrap").apply { isDaemon = true }
    }
    override suspend fun resolve(network: Network, host: String): List<InetAddress> =
        suspendCancellableCoroutine { continuation ->
            val future = executor.submit {
                try {
                    val addresses = network.getAllByName(host).toList().distinct()
                    if (addresses.isEmpty()) throw IOException("DoQ bootstrap returned no address")
                    if (continuation.isActive) continuation.resume(addresses)
                } catch (failure: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
            continuation.invokeOnCancellation { future.cancel(true) }
        }
}

internal class CustomDoqRuntime(
    epoch: EngineEpoch,
    networkContext: NetworkContext,
    registry: ResolverResourceRegistry,
    socketPlatform: DeviceDnsSocketPlatform,
    endpoint: CustomDoqEndpoint,
    sessions: DoqSessionFactory = NativeDoqSessionFactory(),
    addressLookup: DoqAddressLookup = NetworkDoqAddressLookup,
    clock: MonotonicClock = SystemMonotonicClock,
    watchdog: DeviceDnsWatchdog = ScheduledDeviceDnsWatchdog,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    bootstrapAddresses: List<InetAddress> = emptyList(),
) : ReadyDnsMullvadRuntime {
    override val resolver: DnsResolver = CustomDoqResolver(
        epoch, networkContext, registry, socketPlatform, endpoint, sessions,
        addressLookup, clock, watchdog, ioDispatcher, bootstrapAddresses,
    )
    override fun close() = Unit // Each in-flight socket is owned by the generation registry.
}

/** RFC 9250 transaction; caller IDs never enter the stream and are restored on return. */
internal class CustomDoqResolver(
    private val epoch: EngineEpoch,
    private val networkContext: NetworkContext,
    private val registry: ResolverResourceRegistry,
    private val socketPlatform: DeviceDnsSocketPlatform,
    private val endpoint: CustomDoqEndpoint,
    private val sessions: DoqSessionFactory,
    private val addressLookup: DoqAddressLookup,
    private val clock: MonotonicClock,
    private val watchdog: DeviceDnsWatchdog,
    private val ioDispatcher: CoroutineDispatcher,
    bootstrapAddresses: List<InetAddress> = emptyList(),
) : DnsResolver {
    private val bootstrapAddresses = bootstrapAddresses.toList()
    override suspend fun resolve(request: ResolverRequest): ResolverResult {
        currentCoroutineContext().ensureActive()
        if (!isCurrent(request)) return failure(ResolverFailureReason.STALE_EPOCH)
        val query = request.query
        if (query.size !in 12..4096) return failure(ResolverFailureReason.INVALID_RESPONSE)
        val posted = query.copyOf().apply { this[0] = 0; this[1] = 0 }
        val deadline = minOf(clock.nowNanos() + BUDGET_NANOS, request.absoluteDeviceDeadlineNanos ?: Long.MAX_VALUE)
        if (clock.nowNanos() >= deadline) return ResolverResult.Failure(ResolverFailureReason.TIMEOUT, local = true)
        val peers = try {
            bootstrapAddresses.ifEmpty {
                (withTimeoutOrNull(((deadline - clock.nowNanos()) / 1_000_000L).coerceAtLeast(1)) { addressLookup.resolve(networkContext.network, endpoint.host) }
                    ?: return failure(if (isCurrent(request)) ResolverFailureReason.TIMEOUT else ResolverFailureReason.STALE_EPOCH, local = true))
                    .toList().distinct().ifEmpty { throw IOException("DoQ bootstrap returned no address") }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failure(if (isCurrent(request)) ResolverFailureReason.RECEIVE_FAILED else ResolverFailureReason.STALE_EPOCH, local = true)
        }
        currentCoroutineContext().ensureActive()
        if (!isCurrent(request)) return failure(ResolverFailureReason.STALE_EPOCH)
        if (clock.nowNanos() >= deadline) return failure(ResolverFailureReason.TIMEOUT, local = true)
        return withContext(ioDispatcher) {
            var result: ResolverResult = failure(ResolverFailureReason.TIMEOUT)
            for ((index, peer) in peers.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (!isCurrent(request)) return@withContext failure(ResolverFailureReason.STALE_EPOCH)
                val now = clock.nowNanos()
                if (now >= deadline) return@withContext failure(ResolverFailureReason.TIMEOUT)
                // Resolved and retained peers share ordered bounded attempts within one query deadline.
                val attemptDeadline = now + (deadline - now) / (peers.size - index)
                result = exchange(request, query, posted, peer, attemptDeadline)
                if (result is ResolverResult.Answer || (result as? ResolverResult.Failure)?.reason in
                    setOf(ResolverFailureReason.STALE_EPOCH, ResolverFailureReason.CANCELLED, ResolverFailureReason.OVERLOADED, ResolverFailureReason.MISMATCH_LIMIT) || (result as? ResolverResult.Failure)?.terminal == true) {
                    return@withContext result
                }
            }
            result
        }
    }

    private suspend fun exchange(
        request: ResolverRequest,
        query: ByteArray,
        posted: ByteArray,
        peer: InetAddress,
        deadline: Long,
    ): ResolverResult {
        val socket = try { socketPlatform.createUdp() } catch (_: Exception) {
            return failure(ResolverFailureReason.SOCKET_FAILED, local = true)
        }
        val resource = DoqSocketResource(socket)
        var registered = false
        var consumed = false
        var session: DoqSession? = null
        var timer: AutoCloseable? = null
        var completed = false
        try {
            // registerIfCurrent consumes ownership even when it rejects or throws.
            consumed = true
            registered = registry.registerIfCurrent(resource, epoch)
            if (!registered) return failure(ResolverFailureReason.STALE_EPOCH)
            currentCoroutineContext().ensureActive()
            if (!isCurrent(request)) return failure(ResolverFailureReason.STALE_EPOCH)
            if (!socketPlatform.protect(socket)) return failure(ResolverFailureReason.PROTECT_FAILED)
            try { socketPlatform.bind(socket, networkContext.network) } catch (_: Exception) {
                return failure(ResolverFailureReason.BIND_FAILED)
            }
            currentCoroutineContext().ensureActive()
            if (!isCurrent(request)) return failure(ResolverFailureReason.STALE_EPOCH)
            timer = watchdog.arm((deadline - clock.nowNanos()).coerceAtLeast(1)) { resource.close() }
            val engine = try { sessions.create(endpoint, peer, posted) } catch (_: Exception) {
                return failure(ResolverFailureReason.TLS_FAILED, local = true)
            }
            session = engine
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!isCurrent(request)) return failure(ResolverFailureReason.STALE_EPOCH)
                val event = engine.nextEvent()
                if (clock.nowNanos() >= deadline && (event.firstOrNull()?.toInt()?.and(255)) != 2) return failure(ResolverFailureReason.TIMEOUT)
                if (event.isEmpty()) return failure(ResolverFailureReason.INVALID_RESPONSE)
                when (event[0].toInt() and 0xff) {
                    0 -> {
                        val remaining = ((deadline - clock.nowNanos()) / 1_000_000).coerceAtLeast(1)
                        val wait = minOf(100L, remaining, engine.timeoutMillis().coerceAtLeast(1)).toInt()
                        val datagram = try { socket.receive(MAX_UDP_BYTES, wait) } catch (_: SocketTimeoutException) {
                            engine.onTimeout()
                            continue
                        }
                        // Java's UDP socket is not connected; authenticate the source before
                        // passing it to QUIC, never substituting a received peer address.
                        if (datagram.source.port != endpoint.port ||
                            !datagram.source.address.address.contentEquals(peer.address)) continue
                        if (engine.receive(datagram.body) == -2) return failure(ResolverFailureReason.TLS_FAILED)
                    }
                    1 -> {
                        if (event.size !in 2..1351) return failure(ResolverFailureReason.INVALID_RESPONSE)
                        try { socket.send(event.copyOfRange(1, event.size), peer, endpoint.port, 100) }
                        catch (_: IOException) { return failure(ResolverFailureReason.SEND_FAILED) }
                    }
                    2 -> {
                        val body = event.copyOfRange(1, event.size)
                        // Unlike DoH, DoQ requires zero IDs on the wire in both directions.
                        if (body.size < 12 || body[0] != 0.toByte() || body[1] != 0.toByte()) {
                            return failure(ResolverFailureReason.INVALID_RESPONSE)
                        }
                        when (val validation = ResponseValidation.validate(posted, body, ResolverTransport.DOQ)) {
                            is ResponseValidationResult.Invalid -> return ResolverResult.Failure(validation.reason, terminal = validation.terminal, peerAnswered = validation.peerAnswered)
                            is ResponseValidationResult.Valid -> {
                                currentCoroutineContext().ensureActive()
                                if (!isCurrent(request)) return failure(ResolverFailureReason.STALE_EPOCH)
                                if (clock.nowNanos() >= deadline) return ResolverResult.Failure(ResolverFailureReason.TIMEOUT, terminal = true, peerAnswered = true)
                                val answer = validation.dnsMessage.apply { this[0] = query[0]; this[1] = query[1] }
                                completed = true
                                return ResolverResult.Answer(answer, ResolverTransport.DOQ)
                            }
                        }
                    }
                    4 -> {
                        if (!DoqIpIdentity.matches(endpoint.host, event.copyOfRange(1, event.size))) {
                            return failure(ResolverFailureReason.TLS_FAILED)
                        }
                        engine.approveIpIdentity()
                    }
                    254 -> return failure(ResolverFailureReason.TLS_FAILED)
                    else -> return failure(ResolverFailureReason.INVALID_RESPONSE)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            return failure(when {
                !isCurrent(request) -> ResolverFailureReason.STALE_EPOCH
                clock.nowNanos() >= deadline -> ResolverFailureReason.TIMEOUT
                else -> ResolverFailureReason.RECEIVE_FAILED
            })
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            return failure(if (isCurrent(request)) ResolverFailureReason.SOCKET_FAILED else ResolverFailureReason.STALE_EPOCH)
        } finally {
            val abandoned = session
            if (!completed && abandoned != null && registered && registry.isCurrent(epoch) && clock.nowNanos() < deadline) {
                // Local cancellation sends STOP_SENDING(DOQ_REQUEST_CANCELLED) before closing.
                // A retired generation or expired watchdog never sends additional traffic.
                runCatching {
                    abandoned.cancel()
                    repeat(8) {
                        val event = abandoned.nextEvent()
                        if (event.firstOrNull() == 1.toByte()) {
                            socket.send(event.copyOfRange(1, event.size), peer, endpoint.port, 100)
                        } else return@runCatching
                    }
                }
            }
            timer?.close()
            session?.close()
            if (registered) {
                if (registry.unregister(resource)) resource.close()
            } else if (!consumed) resource.close()
        }
    }

    private fun isCurrent(request: ResolverRequest): Boolean = registry.isCurrent(epoch) &&
        request.epoch == epoch && request.networkContext.network === networkContext.network &&
        request.networkContext.networkGeneration == networkContext.networkGeneration
    private fun failure(reason: ResolverFailureReason, local: Boolean = reason == ResolverFailureReason.PROTECT_FAILED || reason == ResolverFailureReason.BIND_FAILED): ResolverResult.Failure = ResolverResult.Failure(
        if (registry.isCurrent(epoch)) reason else ResolverFailureReason.STALE_EPOCH, local = local,
    )
    private companion object {
        const val BUDGET_MILLIS = 5000L
        const val BUDGET_NANOS = BUDGET_MILLIS * 1_000_000
        const val MAX_UDP_BYTES = 65535
    }
}

private class DoqSocketResource(private val socket: DeviceUdpSocket) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    override fun close() { if (closed.compareAndSet(false, true)) socket.close() }
}

/** Native chain verification is required first; this only adds literal IP identity checking. */
internal object DoqIpIdentity {
    fun matches(host: String, der: ByteArray): Boolean = runCatching {
        // Only an IP literal can enter this path; never resolve an attacker-controlled SAN.
        val literal = host.contains(':') || host.matches(Regex("[0-9]+(\\.[0-9]+){3}"))
        if (!literal) return false
        val expected = InetAddress.getByName(host).address
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        certificate.subjectAlternativeNames.orEmpty().any { san ->
            san.getOrNull(0) == 7 && when (val value = san.getOrNull(1)) {
                is ByteArray -> expected.contentEquals(value)
                is String -> (value.contains(':') || value.matches(Regex("[0-9]+(\\.[0-9]+){3}"))) &&
                    expected.contentEquals(InetAddress.getByName(value).address)
                else -> false
            }
        }
    }.getOrDefault(false)
}
