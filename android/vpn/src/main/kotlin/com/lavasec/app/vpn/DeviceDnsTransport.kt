package com.lavasec.app.vpn

import android.net.Network
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverTransport
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal fun interface DeviceDnsWatchdog {
    fun arm(delayNanos: Long, onExpire: () -> Unit): AutoCloseable
}

internal object ScheduledDeviceDnsWatchdog : DeviceDnsWatchdog {
    private val executor = ScheduledThreadPoolExecutor(
        1,
    ) { runnable ->
        Thread(runnable, "lavasec-device-dns-watchdog").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
        executeExistingDelayedTasksAfterShutdownPolicy = false
    }

    override fun arm(delayNanos: Long, onExpire: () -> Unit): AutoCloseable {
        val future = executor.schedule(onExpire, delayNanos, TimeUnit.NANOSECONDS)
        return AutoCloseable { future.cancel(false) }
    }
}

/**
 * Dormant underlying-network DNS transport. The request owns the exact selected Network and
 * server snapshot; this class is the sole owner of iterating that snapshot.
 */
internal class DeviceDnsTransport(
    private val platform: DeviceDnsSocketPlatform,
    private val registry: ResolverResourceRegistry,
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val watchdog: DeviceDnsWatchdog = ScheduledDeviceDnsWatchdog,
    private val socketIoDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val dnsPort: Int = DNS_PORT,
    fixedDnsServers: List<InetAddress>? = null,
    private val fixedResolverIdentity: com.lavasec.app.foundation.health.PrimaryResolverIdentity? = null,
) : DnsResolver, DeviceDnsRoute {
    private val fixedDnsServers = fixedDnsServers?.let { java.util.Collections.unmodifiableList(it.toList()) }
    private val udpTransport = if (fixedDnsServers == null) ResolverTransport.DEVICE_UDP else ResolverTransport.PLAIN_UDP
    private val tcpTransport = if (fixedDnsServers == null) ResolverTransport.DEVICE_TCP else ResolverTransport.PLAIN_TCP

    init {
        require((fixedDnsServers == null) == (fixedResolverIdentity == null))
        require(fixedDnsServers == null || fixedDnsServers.isNotEmpty())
        require(dnsPort in 1..65_535) { "DNS port must be valid" }
    }

    override suspend fun resolve(request: ResolverRequest): ResolverResult =
        resolveInternal(request, endpointBackoff = null).result

    override suspend fun resolve(
        request: ResolverRequest,
        endpointBackoff: ResolverEndpointBackoffPolicy,
    ): DeviceDnsRouteResult = resolveInternal(request, endpointBackoff)

    private suspend fun resolveInternal(
        request: ResolverRequest,
        endpointBackoff: ResolverEndpointBackoffPolicy?,
    ): DeviceDnsRouteResult {
        currentCoroutineContext().ensureActive()
        val query = request.query
        val network = request.networkContext.network
        val servers = DeviceDnsAddressPolicy.select(
            fixedDnsServers ?: request.networkContext.dnsServers,
            if (fixedDnsServers == null) request.networkContext.isCellular else false,
        )
        if (servers.isEmpty()) return routeFailure(ResolverFailureReason.INVALID_RESPONSE)
        val wholeDeadline =
            request.absoluteDeviceDeadlineNanos
                ?: clock.nowNanos() + WHOLE_BUDGET_NANOS
        var lastFailure = ResolverFailureReason.INVALID_RESPONSE
        var attemptedAddress = false

        for (server in servers) {
            currentCoroutineContext().ensureActive()
            val endpoint: ResolverEndpointKey.Address = fixedResolverIdentity?.let { ResolverEndpointKey.FixedAddress(it, server) }
                ?: ResolverEndpointKey.DeviceAddress(server)
            if (endpointBackoff?.isBackedOff(endpoint) == true) continue
            if (expired(wholeDeadline)) return routeFailure(ResolverFailureReason.TIMEOUT)
            attemptedAddress = true
            val udp = resolveUdp(request, query, network, server, wholeDeadline)
            currentCoroutineContext().ensureActive()
            finalGateReason(request, wholeDeadline)?.let { reason ->
                if (reason == ResolverFailureReason.TIMEOUT && registry.isCurrent(request.epoch) &&
                    (udp is AttemptOutcome.Answer && isValidatedReply(query, udp.result.dnsMessage) ||
                        udp is AttemptOutcome.Failure && udp.peerAnswered)) {
                    endpointBackoff?.recordSuccess(endpoint)
                    return routeFailure(reason, terminal = true, peerAnswered = true)
                }
                endpointBackoff?.recordFailure(endpoint, reason)
                return routeFailure(reason)
            }
            when (udp) {
                is AttemptOutcome.Answer -> {
                    endpointBackoff?.recordSuccess(endpoint)
                    return routeAnswer(udp.result, endpoint)
                }
                is AttemptOutcome.Failure -> {
                    lastFailure = udp.reason
                    if (udp.peerAnswered) endpointBackoff?.recordSuccess(endpoint)
                    else endpointBackoff?.recordFailure(endpoint, udp.reason)
                    if (udp.terminal) return routeFailure(udp.reason, terminal = true, peerAnswered = udp.peerAnswered)
                    if (udp.reason == ResolverFailureReason.STALE_EPOCH) {
                        return routeFailure(
                            finalGateReason(request, wholeDeadline) ?: udp.reason,
                        )
                    }
                }
                AttemptOutcome.RetryTcp -> {
                    currentCoroutineContext().ensureActive()
                    if (expired(wholeDeadline)) {
                        endpointBackoff?.recordFailure(
                            endpoint,
                            ResolverFailureReason.TIMEOUT,
                        )
                        return routeFailure(ResolverFailureReason.TIMEOUT)
                    }
                    val tcp = resolveTcp(request, query, network, server, wholeDeadline)
                    currentCoroutineContext().ensureActive()
                    finalGateReason(request, wholeDeadline)?.let { reason ->
                        if (reason == ResolverFailureReason.TIMEOUT && registry.isCurrent(request.epoch) &&
                            (tcp is AttemptOutcome.Answer && isValidatedReply(query, tcp.result.dnsMessage) ||
                                tcp is AttemptOutcome.Failure && tcp.peerAnswered)) {
                            endpointBackoff?.recordSuccess(endpoint)
                            return routeFailure(reason, terminal = true, peerAnswered = true)
                        }
                        endpointBackoff?.recordFailure(endpoint, reason)
                        return routeFailure(reason)
                    }
                    when (tcp) {
                        is AttemptOutcome.Answer -> {
                            endpointBackoff?.recordSuccess(endpoint)
                            return routeAnswer(tcp.result, endpoint)
                        }
                        is AttemptOutcome.Failure -> {
                            lastFailure = tcp.reason
                            if (tcp.peerAnswered) endpointBackoff?.recordSuccess(endpoint)
                            else endpointBackoff?.recordFailure(endpoint, tcp.reason)
                            if (tcp.terminal) return routeFailure(tcp.reason, terminal = true, peerAnswered = tcp.peerAnswered)
                            if (tcp.reason == ResolverFailureReason.STALE_EPOCH) {
                                return routeFailure(
                                    finalGateReason(request, wholeDeadline) ?: tcp.reason,
                                )
                            }
                        }
                        AttemptOutcome.RetryTcp -> error("TCP cannot request TCP fallback")
                    }
                }
            }
        }

        currentCoroutineContext().ensureActive()
        return if (!attemptedAddress) {
            routeFailure(ResolverFailureReason.BACKED_OFF)
        } else if (expired(wholeDeadline)) {
            routeFailure(ResolverFailureReason.TIMEOUT)
        } else {
            routeFailure(lastFailure)
        }
    }

    private suspend fun resolveUdp(
        request: ResolverRequest,
        query: ByteArray,
        network: Network,
        server: InetAddress,
        wholeDeadline: Long,
    ): AttemptOutcome {
        val socket = try {
            platform.createUdp()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return AttemptOutcome.Failure(ResolverFailureReason.SOCKET_FAILED)
        }
        val resource = CloseOnce(socket)
        var registered = false
        try {
            registered = try {
                registry.registerIfCurrent(resource, request.epoch)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return AttemptOutcome.Failure(ResolverFailureReason.SOCKET_FAILED)
            }
            if (!registered) {
                return AttemptOutcome.Failure(ResolverFailureReason.STALE_EPOCH)
            }
            val protected = try {
                platform.protect(socket)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return AttemptOutcome.Failure(ResolverFailureReason.PROTECT_FAILED)
            }
            if (!protected) {
                return AttemptOutcome.Failure(ResolverFailureReason.PROTECT_FAILED)
            }
            try {
                platform.bind(socket, network)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return AttemptOutcome.Failure(ResolverFailureReason.BIND_FAILED)
            }

            val attemptDeadline = clippedDeadline(wholeDeadline, UDP_BUDGET_NANOS)
                ?: return AttemptOutcome.Failure(ResolverFailureReason.TIMEOUT)
            when (
                val send = performIo(
                    request = request,
                    wholeDeadline = wholeDeadline,
                    attemptDeadline = attemptDeadline,
                    resource = resource,
                    failureReason = ResolverFailureReason.SEND_FAILED,
                ) { timeoutMillis ->
                    socket.send(query.copyOf(), server, dnsPort, timeoutMillis)
                }
            ) {
                is IoResult.Failure -> return AttemptOutcome.Failure(send.reason)
                is IoResult.Value -> Unit
            }

            var mismatches = 0
            while (mismatches < MAX_MISMATCHES) {
                val receive = performIo(
                    request = request,
                    wholeDeadline = wholeDeadline,
                    attemptDeadline = attemptDeadline,
                    resource = resource,
                    failureReason = ResolverFailureReason.RECEIVE_FAILED,
                    retainLateReply = true,
                ) { timeoutMillis ->
                    socket.receive(UDP_SENTINEL_BYTES, timeoutMillis)
                }
                when (receive) {
                    is IoResult.Failure -> {
                        return if (
                            receive.reason == ResolverFailureReason.TIMEOUT &&
                            remainingNanos(wholeDeadline) > 0L &&
                            registry.isCurrent(request.epoch)
                        ) {
                            AttemptOutcome.RetryTcp
                        } else {
                            AttemptOutcome.Failure(
                                finalGateReason(request, wholeDeadline) ?: receive.reason,
                            )
                        }
                    }
                    is IoResult.Value -> {
                        val datagram = receive.value
                        when (
                            val validation = ResponseValidation.validate(
                                query = query,
                                response = datagram.body,
                                transport = udpTransport,
                                datagramSource = datagram.source,
                                expectedServer = server,
                                expectedPort = dnsPort,
                            )
                        ) {
                            is ResponseValidationResult.Valid -> {
                                if (receive.lateReply) return lateReply(query, validation.dnsMessage)
                                if (validation.truncated) return AttemptOutcome.RetryTcp
                                finalGateReason(request, wholeDeadline)?.let {
                                    return if (it == ResolverFailureReason.TIMEOUT && registry.isCurrent(request.epoch))
                                        lateReply(query, validation.dnsMessage) else AttemptOutcome.Failure(it)
                                }
                                return AttemptOutcome.Answer(
                                    ResolverResult.Answer(
                                        validation.dnsMessage,
                                        udpTransport,
                                    ),
                                )
                            }
                            is ResponseValidationResult.Invalid -> {
                                if (validation.terminal) return AttemptOutcome.Failure(validation.reason,
                                    terminal = true, peerAnswered = validation.peerAnswered)
                                mismatches += 1
                            }
                        }
                    }
                }
            }
            return AttemptOutcome.Failure(ResolverFailureReason.MISMATCH_LIMIT)
        } finally {
            release(resource, registered)
        }
    }

    private suspend fun resolveTcp(
        request: ResolverRequest,
        query: ByteArray,
        network: Network,
        server: InetAddress,
        wholeDeadline: Long,
    ): AttemptOutcome {
        val socket = try {
            platform.createTcp()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return AttemptOutcome.Failure(ResolverFailureReason.SOCKET_FAILED)
        }
        val resource = CloseOnce(socket)
        var registered = false
        try {
            registered = try {
                registry.registerIfCurrent(resource, request.epoch)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return AttemptOutcome.Failure(ResolverFailureReason.SOCKET_FAILED)
            }
            if (!registered) {
                return AttemptOutcome.Failure(ResolverFailureReason.STALE_EPOCH)
            }
            val protected = try {
                platform.protect(socket)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return AttemptOutcome.Failure(ResolverFailureReason.PROTECT_FAILED)
            }
            if (!protected) {
                return AttemptOutcome.Failure(ResolverFailureReason.PROTECT_FAILED)
            }
            try {
                platform.bind(socket, network)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return AttemptOutcome.Failure(ResolverFailureReason.BIND_FAILED)
            }

            val attemptDeadline = clippedDeadline(wholeDeadline, TCP_BUDGET_NANOS)
                ?: return AttemptOutcome.Failure(ResolverFailureReason.TIMEOUT)
            performIo(
                request = request,
                wholeDeadline = wholeDeadline,
                attemptDeadline = attemptDeadline,
                resource = resource,
                failureReason = ResolverFailureReason.SOCKET_FAILED,
            ) { timeoutMillis ->
                socket.connect(server, dnsPort, timeoutMillis)
            }.failureOrNull()?.let { return AttemptOutcome.Failure(it) }

            val frame = tcpFrame(query)
                ?: return AttemptOutcome.Failure(ResolverFailureReason.INVALID_RESPONSE)
            performIo(
                request = request,
                wholeDeadline = wholeDeadline,
                attemptDeadline = attemptDeadline,
                resource = resource,
                failureReason = ResolverFailureReason.SEND_FAILED,
            ) { timeoutMillis ->
                socket.writeFrame(frame, timeoutMillis)
            }.failureOrNull()?.let { return AttemptOutcome.Failure(it) }

            val lengthResult = performIo(
                request = request,
                wholeDeadline = wholeDeadline,
                attemptDeadline = attemptDeadline,
                resource = resource,
                failureReason = ResolverFailureReason.RECEIVE_FAILED,
            ) { timeoutMillis ->
                socket.readLength(timeoutMillis)
            }
            val length = when (lengthResult) {
                is IoResult.Failure -> return AttemptOutcome.Failure(lengthResult.reason)
                is IoResult.Value -> lengthResult.value
                    ?: return AttemptOutcome.Failure(ResolverFailureReason.RECEIVE_FAILED)
            }
            if (length == 0) {
                return AttemptOutcome.Failure(ResolverFailureReason.INVALID_RESPONSE)
            }
            if (length !in 1..MAX_MESSAGE_BYTES) {
                return AttemptOutcome.Failure(
                    if (length > MAX_MESSAGE_BYTES) {
                        ResolverFailureReason.OVERSIZED_RESPONSE
                    } else {
                        ResolverFailureReason.INVALID_RESPONSE
                    },
                )
            }

            val bodyResult = performIo(
                request = request,
                wholeDeadline = wholeDeadline,
                attemptDeadline = attemptDeadline,
                resource = resource,
                failureReason = ResolverFailureReason.RECEIVE_FAILED,
                retainLateReply = true,
            ) { timeoutMillis ->
                socket.readBody(length, timeoutMillis)
            }
            val body = when (bodyResult) {
                is IoResult.Failure -> return AttemptOutcome.Failure(bodyResult.reason)
                is IoResult.Value -> bodyResult.value
                    ?: return AttemptOutcome.Failure(ResolverFailureReason.RECEIVE_FAILED)
            }
            if (body.size != length) {
                return AttemptOutcome.Failure(ResolverFailureReason.RECEIVE_FAILED)
            }

            return when (
                val validation = ResponseValidation.validate(
                    query = query,
                    response = body,
                    transport = tcpTransport,
                )
            ) {
                is ResponseValidationResult.Invalid -> AttemptOutcome.Failure(validation.reason,
                    terminal = validation.terminal, peerAnswered = validation.peerAnswered)
                is ResponseValidationResult.Valid -> {
                    if (bodyResult.lateReply) return lateReply(query, validation.dnsMessage)
                    finalGateReason(request, wholeDeadline)?.let {
                        return if (it == ResolverFailureReason.TIMEOUT && registry.isCurrent(request.epoch))
                            lateReply(query, validation.dnsMessage) else AttemptOutcome.Failure(it)
                    }
                    AttemptOutcome.Answer(
                        ResolverResult.Answer(
                            validation.dnsMessage,
                            tcpTransport,
                        ),
                    )
                }
            }
        } finally {
            release(resource, registered)
        }
    }

    private suspend fun <T> performIo(
        request: ResolverRequest,
        wholeDeadline: Long,
        attemptDeadline: Long,
        resource: CloseOnce,
        failureReason: ResolverFailureReason,
        retainLateReply: Boolean = false,
        block: (timeoutMillis: Int) -> T,
    ): IoResult<T> {
        currentCoroutineContext().ensureActive()
        finalGateReason(request, wholeDeadline)?.let { return IoResult.Failure(it) }
        val beforeArm = remainingNanos(attemptDeadline)
        if (beforeArm <= 0L) return IoResult.Failure(ResolverFailureReason.TIMEOUT)
        val watchdogState = AtomicInteger(WATCHDOG_ACTIVE)
        val arm = try {
            watchdog.arm(beforeArm) {
                if (watchdogState.compareAndSet(WATCHDOG_ACTIVE, WATCHDOG_EXPIRED)) {
                    resource.close()
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return IoResult.Failure(ResolverFailureReason.SOCKET_FAILED)
        }
        return try {
            val value = when (
                val io = runCancellableIo(
                    request = request,
                    wholeDeadline = wholeDeadline,
                    attemptDeadline = attemptDeadline,
                    watchdogState = watchdogState,
                    resource = resource,
                    block = block,
                )
            ) {
                is IoResult.Failure -> return io
                is IoResult.Value -> io.value
            }
            val watchdogExpired = !watchdogState.compareAndSet(
                WATCHDOG_ACTIVE,
                WATCHDOG_DISARMED,
            )
            val postReason = postIoReason(
                request,
                wholeDeadline,
                attemptDeadline,
                watchdogExpired,
            )
            if (postReason == null) IoResult.Value(value)
            // A returned packet may contain a terminal policy/negative verdict. Keep its bounded
            // bytes for identity/structure validation before withholding it as a late peer reply.
            // An expired subattempt also cannot erase a received negative/policy verdict. A
            // timeout without returned bytes retains the bounded same-resolver TCP retry.
            else if (retainLateReply && postReason == ResolverFailureReason.TIMEOUT &&
                registry.isCurrent(request.epoch)) IoResult.Value(value, lateReply = true)
            else IoResult.Failure(postReason)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            val watchdogExpired = !watchdogState.compareAndSet(
                WATCHDOG_ACTIVE,
                WATCHDOG_DISARMED,
            )
            val postReason = postIoReason(
                request,
                wholeDeadline,
                attemptDeadline,
                watchdogExpired,
            )
            IoResult.Failure(
                postReason
                    ?: if (failure is SocketTimeoutException) {
                        ResolverFailureReason.TIMEOUT
                    } else {
                        failureReason
                    },
            )
        } finally {
            watchdogState.compareAndSet(WATCHDOG_ACTIVE, WATCHDOG_DISARMED)
            closeQuietly(arm)
        }
    }

    private suspend fun <T> runCancellableIo(
        request: ResolverRequest,
        wholeDeadline: Long,
        attemptDeadline: Long,
        watchdogState: AtomicInteger,
        resource: CloseOnce,
        block: (timeoutMillis: Int) -> T,
    ): IoResult<T> = withContext(socketIoDispatcher) {
        currentCoroutineContext().ensureActive()
        suspendCancellableCoroutine<IoResult<T>> { continuation ->
            continuation.invokeOnCancellation {
                resource.requestClose()
            }
            if (!continuation.isActive) return@suspendCancellableCoroutine
            val gateReason = finalGateReason(request, wholeDeadline)
            if (gateReason != null) {
                continuation.resumeWith(Result.success(IoResult.Failure(gateReason)))
                return@suspendCancellableCoroutine
            }
            if (watchdogState.get() != WATCHDOG_ACTIVE) {
                continuation.resumeWith(
                    Result.success(IoResult.Failure(ResolverFailureReason.TIMEOUT)),
                )
                return@suspendCancellableCoroutine
            }
            val remaining = remainingNanos(attemptDeadline)
            if (remaining <= 0L) {
                continuation.resumeWith(
                    Result.success(IoResult.Failure(ResolverFailureReason.TIMEOUT)),
                )
                return@suspendCancellableCoroutine
            }
            try {
                continuation.resumeWith(
                    Result.success(IoResult.Value(block(timeoutMillis(remaining)))),
                )
            } catch (failure: Throwable) {
                continuation.resumeWith(Result.failure(failure))
            }
        }
    }

    private fun postIoReason(
        request: ResolverRequest,
        wholeDeadline: Long,
        attemptDeadline: Long,
        watchdogExpired: Boolean,
    ): ResolverFailureReason? =
        when {
            request.fallbackContext.requiresPortalAdmission && !request.fallbackContext.portalNetworkIsCurrent() ->
                ResolverFailureReason.STALE_EPOCH
            expired(wholeDeadline) -> ResolverFailureReason.TIMEOUT
            !registry.isCurrent(request.epoch) -> ResolverFailureReason.STALE_EPOCH
            watchdogExpired || expired(attemptDeadline) -> ResolverFailureReason.TIMEOUT
            else -> null
        }

    private fun finalGateReason(
        request: ResolverRequest,
        wholeDeadline: Long,
    ): ResolverFailureReason? =
        when {
            request.fallbackContext.requiresPortalAdmission && !request.fallbackContext.portalNetworkIsCurrent() ->
                ResolverFailureReason.STALE_EPOCH
            expired(wholeDeadline) -> ResolverFailureReason.TIMEOUT
            !registry.isCurrent(request.epoch) -> ResolverFailureReason.STALE_EPOCH
            else -> null
        }

    private fun release(resource: CloseOnce, registered: Boolean) {
        if (registered) {
            try {
                registry.unregister(resource)
            } catch (_: Throwable) {
                // Physical closure never depends on an adversarial registry's return or throw.
            }
        }
        resource.close()
    }

    private fun clippedDeadline(wholeDeadline: Long, budgetNanos: Long): Long? {
        val now = clock.nowNanos()
        val wholeRemaining = wholeDeadline - now
        if (wholeRemaining <= 0L) return null
        return now + min(budgetNanos, wholeRemaining)
    }

    private fun expired(deadline: Long): Boolean = remainingNanos(deadline) <= 0L

    private fun remainingNanos(deadline: Long): Long = deadline - clock.nowNanos()

    private fun timeoutMillis(remainingNanos: Long): Int =
        ((remainingNanos - 1L) / NANOS_PER_MILLI + 1L)
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

    private fun tcpFrame(query: ByteArray): ByteArray? {
        if (query.size !in 1..MAX_MESSAGE_BYTES) return null
        return ByteArray(query.size + 2).also { frame ->
            frame[0] = (query.size ushr 8).toByte()
            frame[1] = query.size.toByte()
            query.copyInto(frame, destinationOffset = 2)
        }
    }

    private fun routeFailure(reason: ResolverFailureReason, terminal: Boolean = false, peerAnswered: Boolean = false) =
        DeviceDnsRouteResult(
            result = ResolverResult.Failure(reason, terminal = terminal, peerAnswered = peerAnswered),
            answeredEndpoint = null,
        )

    private fun isValidatedReply(query: ByteArray, response: ByteArray): Boolean =
        DnsFallbackResponse.classify(query, response).kind !in setOf(
            DnsFallbackResponse.Kind.MALFORMED, DnsFallbackResponse.Kind.MISMATCHED,
        )

    private fun lateReply(query: ByteArray, response: ByteArray): AttemptOutcome.Failure =
        if (isValidatedReply(query, response)) AttemptOutcome.Failure(
            ResolverFailureReason.TIMEOUT, terminal = true, peerAnswered = true,
        ) else AttemptOutcome.Failure(ResolverFailureReason.INVALID_RESPONSE)

    private fun routeAnswer(
        answer: ResolverResult.Answer,
        endpoint: ResolverEndpointKey.Address,
    ) = DeviceDnsRouteResult(
        result = answer,
        answeredEndpoint = endpoint,
    )

    private fun closeQuietly(closeable: AutoCloseable) {
        try {
            closeable.close()
        } catch (_: Throwable) {
            // Cancellation and close reporting cannot change the already selected typed outcome.
        }
    }

    private class CloseOnce(
        private val socket: DeviceDnsSocket,
    ) : AutoCloseable {
        private val closeStarted = AtomicBoolean(false)
        private val closeFinished = CountDownLatch(1)

        override fun close() {
            requestClose()
            awaitCloseUninterruptibly()
        }

        fun requestClose() {
            if (closeStarted.compareAndSet(false, true)) {
                try {
                    socket.close()
                } catch (_: Throwable) {
                    // Ownership is consumed after the one physical close attempt.
                } finally {
                    closeFinished.countDown()
                }
            }
        }

        private fun awaitCloseUninterruptibly() {
            var interrupted = false
            while (true) {
                try {
                    closeFinished.await()
                    break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private sealed interface AttemptOutcome {
        data class Answer(val result: ResolverResult.Answer) : AttemptOutcome
        data class Failure(val reason: ResolverFailureReason, val terminal: Boolean = false,
            val peerAnswered: Boolean = false) : AttemptOutcome
        data object RetryTcp : AttemptOutcome
    }

    private sealed interface IoResult<out T> {
        data class Value<T>(val value: T, val lateReply: Boolean = false) : IoResult<T>
        data class Failure(val reason: ResolverFailureReason) : IoResult<Nothing>
    }

    private fun IoResult<*>.failureOrNull(): ResolverFailureReason? =
        (this as? IoResult.Failure)?.reason

    private companion object {
        const val DNS_PORT = 53
        const val MAX_MESSAGE_BYTES = 4_096
        const val UDP_SENTINEL_BYTES = MAX_MESSAGE_BYTES + 1
        const val MAX_MISMATCHES = 8
        const val NANOS_PER_MILLI = 1_000_000L
        const val UDP_BUDGET_NANOS = 1_000_000_000L
        const val TCP_BUDGET_NANOS = 2_000_000_000L
        const val WHOLE_BUDGET_NANOS = 8_000_000_000L
        const val WATCHDOG_ACTIVE = 0
        const val WATCHDOG_EXPIRED = 1
        const val WATCHDOG_DISARMED = 2
    }
}
