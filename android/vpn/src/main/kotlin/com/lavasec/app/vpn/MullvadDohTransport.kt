package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverTransport
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

internal object MullvadDohRuntimeLimits {
    const val MAX_REQUESTS = 16
    const val MAX_REQUESTS_PER_HOST = 8
    const val MAX_IDLE_CONNECTIONS = 5
    const val KEEP_ALIVE_MINUTES = 5L
    const val WHOLE_CALL_TIMEOUT_SECONDS = 5L
    const val CLOSE_DRAIN_TIMEOUT_SECONDS = 10L
}

/** Test-only trust injection. Production keeps OkHttp's platform TLS and hostname verifier. */
internal data class MullvadDohTlsConfiguration(
    val socketFactory: SSLSocketFactory,
    val trustManager: X509TrustManager,
)

/** One explicitly bounded HTTP runtime captured by one complete engine epoch and network. */
internal class MullvadDohRuntime(
    epoch: EngineEpoch,
    networkContext: NetworkContext,
    registry: ResolverResourceRegistry,
    protector: DohSocketProtector,
    binder: DohSocketBinder,
    endpoint: HttpUrl = MullvadDohEndpoint.url,
    dns: Dns = MullvadDohEndpoint,
    tls: MullvadDohTlsConfiguration? = null,
    callFactory: (OkHttpClient) -> Call.Factory = { it },
    clock: MonotonicClock = SystemMonotonicClock,
) : AutoCloseable {
    init {
        require(endpoint.isHttps) { "DNS-over-HTTPS endpoint must use HTTPS" }
        require(networkContext.networkGeneration == epoch.networkGeneration) {
            "network context generation must match epoch network generation"
        }
    }

    private val closeStarted = AtomicBoolean(false)
    private val closeCompleted = CountDownLatch(1)
    private val closeFailure = AtomicReference<Throwable?>()
    private val activeCalls = RuntimeCallRegistry()
    private val dispatcher = Dispatcher().apply {
        maxRequests = MullvadDohRuntimeLimits.MAX_REQUESTS
        maxRequestsPerHost = MullvadDohRuntimeLimits.MAX_REQUESTS_PER_HOST
    }
    private val connectionPool = ConnectionPool(
        MullvadDohRuntimeLimits.MAX_IDLE_CONNECTIONS,
        MullvadDohRuntimeLimits.KEEP_ALIVE_MINUTES,
        TimeUnit.MINUTES,
    )

    private val client = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectionPool(connectionPool)
        .dns(dns)
        .socketFactory(
            ProtectedDohSocketFactory(
                epoch = epoch,
                network = networkContext.network,
                registry = registry,
                protector = protector,
                binder = binder,
            ),
        )
        .callTimeout(MullvadDohRuntimeLimits.WHOLE_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .apply {
            if (tls != null) {
                sslSocketFactory(tls.socketFactory, tls.trustManager)
            }
        }
        .build()

    val resolver: DnsResolver = MullvadDohResolver(
        endpoint = endpoint,
        callFactory = callFactory(client),
        registry = registry,
        runtimeCalls = activeCalls,
        connectionPool = connectionPool,
        epoch = epoch,
        networkContext = networkContext,
        clock = clock,
    )

    /**
     * Closes this runtime within [MullvadDohRuntimeLimits.CLOSE_DRAIN_TIMEOUT_SECONDS].
     *
     * A successful return means callback ownership, dispatcher work, pooled/raw connections, and
     * the owned dispatcher executor have drained. Failure to prove that state throws explicitly;
     * concurrent and repeated callers observe the same completed outcome.
     */
    override fun close() {
        if (closeStarted.compareAndSet(false, true)) {
            try {
                drain()
            } catch (failure: Throwable) {
                closeFailure.compareAndSet(null, failure)
                throw failure
            } finally {
                closeCompleted.countDown()
            }
        } else {
            val completed = try {
                closeCompleted.await(
                    MullvadDohRuntimeLimits.CLOSE_DRAIN_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                )
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Interrupted while awaiting Mullvad DoH close", failure)
            }
            check(completed) { "Timed out awaiting concurrent Mullvad DoH close" }
            closeFailure.get()?.let { throw it }
        }
    }

    internal val clientForTesting: OkHttpClient
        get() = client

    internal val connectionPoolForTesting: ConnectionPool
        get() = connectionPool

    private fun drain() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(
            MullvadDohRuntimeLimits.CLOSE_DRAIN_TIMEOUT_SECONDS,
        )
        val retiredCalls = activeCalls.beginClose()
        val cancellationComplete = CountDownLatch(1)
        Thread(
            {
                try {
                    retiredCalls.forEach { runCatching { it.close() } }
                } finally {
                    cancellationComplete.countDown()
                }
            },
            "lavasec-mullvad-doh-close",
        ).apply {
            isDaemon = true
            start()
        }
        dispatcher.executorService.shutdown()
        check(
            cancellationComplete.await(
                remainingNanos(deadline),
                TimeUnit.NANOSECONDS,
            ),
        ) {
            "Timed out cancelling Mullvad DoH calls"
        }
        check(activeCalls.awaitEmpty(remainingNanos(deadline))) {
            "Timed out draining Mullvad DoH callback ownership"
        }
        val executorTerminated = try {
            dispatcher.executorService.awaitTermination(
                remainingNanos(deadline),
                TimeUnit.NANOSECONDS,
            )
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while draining Mullvad DoH executor", failure)
        }
        check(executorTerminated) { "Timed out draining Mullvad DoH executor" }
        connectionPool.evictAll()
        check(dispatcher.runningCallsCount() == 0 && dispatcher.queuedCallsCount() == 0) {
            "Mullvad DoH dispatcher did not drain"
        }
        check(
            connectionPool.connectionCount() == 0 &&
                connectionPool.idleConnectionCount() == 0,
        ) {
            "Mullvad DoH connection pool did not drain"
        }
    }

    private fun remainingNanos(deadline: Long): Long =
        (deadline - System.nanoTime()).coerceAtLeast(0L)
}

private class RuntimeCallRegistry : AutoCloseable {
    private val lock = ReentrantLock()
    private val drained = lock.newCondition()
    private val calls =
        Collections.newSetFromMap(IdentityHashMap<AutoCloseable, Boolean>())
    private var closed = false

    fun isOpen(): Boolean = lock.withLock { !closed }

    fun register(call: AutoCloseable): Boolean = lock.withLock {
        if (closed) false else calls.add(call)
    }

    fun complete(call: AutoCloseable, completion: () -> Boolean): Boolean = lock.withLock {
        if (closed || !calls.contains(call)) return@withLock false
        if (!completion()) return@withLock false
        calls.remove(call)
        if (calls.isEmpty()) drained.signalAll()
        true
    }

    fun discard(call: AutoCloseable) {
        lock.withLock {
            calls.remove(call)
            if (calls.isEmpty()) drained.signalAll()
        }
    }

    fun beginClose(): List<AutoCloseable> = lock.withLock {
        if (!closed) {
            closed = true
        }
        calls.toList()
    }

    fun awaitEmpty(timeoutNanos: Long): Boolean = lock.withLock {
        var remaining = timeoutNanos
        while (calls.isNotEmpty() && remaining > 0L) {
            remaining = drained.awaitNanos(remaining)
        }
        calls.isEmpty()
    }

    override fun close() {
        beginClose().forEach { runCatching { it.close() } }
    }
}

private class MullvadDohResolver(
    private val endpoint: HttpUrl,
    private val callFactory: Call.Factory,
    private val registry: ResolverResourceRegistry,
    private val runtimeCalls: RuntimeCallRegistry,
    private val connectionPool: ConnectionPool,
    private val epoch: EngineEpoch,
    private val networkContext: NetworkContext,
    private val clock: MonotonicClock,
) : DnsResolver {
    override suspend fun resolve(request: ResolverRequest): ResolverResult {
        if (!isCurrentContext(request)) {
            return ResolverResult.Failure(ResolverFailureReason.STALE_EPOCH)
        }
        val deadline = request.absoluteDeviceDeadlineNanos
        if (deadline != null && clock.nowNanos() >= deadline) return ResolverResult.Failure(
            ResolverFailureReason.TIMEOUT, local = true,
            terminal = request.wholeQueryDeadlineNanos?.let { clock.nowNanos() >= it } == true)
        val originalQuery = request.query
        if (originalQuery.size < DNS_HEADER_BYTES) {
            return ResolverResult.Failure(ResolverFailureReason.INVALID_RESPONSE)
        }
        val callerIdHigh = originalQuery[0]
        val callerIdLow = originalQuery[1]
        val postedQuery = originalQuery.copyOf().also {
            it[0] = 0
            it[1] = 0
        }
        val httpRequest = Request.Builder()
            .url(endpoint)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header(ACCEPT_HEADER, DNS_MEDIA_TYPE_VALUE)
            .post(postedQuery.toRequestBody(DNS_MEDIA_TYPE))
            .build()
        val call = callFactory.newCall(httpRequest)
        if (deadline != null) {
            val remaining = deadline - clock.nowNanos()
            if (remaining <= 0L) return ResolverResult.Failure(ResolverFailureReason.TIMEOUT, local = true)
            call.timeout().timeout(remaining, TimeUnit.NANOSECONDS)
        }
        val handle = RegistryOwnedCall(call)
        val registered = try {
            registry.registerIfCurrent(handle, epoch)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            return ResolverResult.Failure(ResolverFailureReason.SOCKET_FAILED, local = true)
        }
        if (!registered) {
            return ResolverResult.Failure(ResolverFailureReason.STALE_EPOCH)
        }
        if (!runtimeCalls.register(handle)) {
            if (registry.unregister(handle)) handle.close()
            return ResolverResult.Failure(ResolverFailureReason.STALE_EPOCH)
        }
        if (!isCurrentContext(request)) {
            cleanupCancelled(handle)
            return ResolverResult.Failure(ResolverFailureReason.STALE_EPOCH)
        }

        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cleanupCancelled(handle) }
            try {
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        publishTerminal(
                            handle,
                            failureResult(call, e),
                            request,
                            continuation,
                        )
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = try {
                            response.use {
                                parseResponse(
                                    callerIdHigh = callerIdHigh,
                                    callerIdLow = callerIdLow,
                                    postedQuery = postedQuery,
                                    response = it,
                                )
                            }
                        } catch (failure: IOException) {
                            failureResult(call, failure)
                        } catch (_: Throwable) {
                            ResolverResult.Failure(ResolverFailureReason.INVALID_RESPONSE)
                        }
                        val admittedResult = if (result is ResolverResult.Answer && deadline != null && clock.nowNanos() >= deadline)
                            ResolverResult.Failure(ResolverFailureReason.TIMEOUT, terminal = true, peerAnswered = true)
                        else result
                        publishTerminal(handle, admittedResult, request, continuation)
                    }
                })
            } catch (failure: CancellationException) {
                cleanupCancelled(handle)
                throw failure
            } catch (failure: Throwable) {
                val result = if (failure is IOException) {
                    failureResult(call, failure)
                } else {
                    ResolverResult.Failure(ResolverFailureReason.SEND_FAILED)
                }
                publishTerminal(
                    handle = handle,
                    result = result,
                    request = request,
                    continuation = continuation,
                    closeAfterOwnershipTransfer = true,
                )
            }
        }
    }

    private fun parseResponse(
        callerIdHigh: Byte,
        callerIdLow: Byte,
        postedQuery: ByteArray,
        response: Response,
    ): ResolverResult {
        if (response.code !in SUCCESS_STATUS_RANGE) {
            return ResolverResult.Failure(ResolverFailureReason.HTTP_STATUS)
        }
        if (!hasDnsMediaType(response.header(CONTENT_TYPE_HEADER))) {
            return ResolverResult.Failure(ResolverFailureReason.HTTP_CONTENT_TYPE)
        }
        val body = response.body
            ?: return ResolverResult.Failure(ResolverFailureReason.INVALID_RESPONSE)
        if (body.contentLength() > MAX_DNS_MESSAGE_BYTES) {
            return ResolverResult.Failure(ResolverFailureReason.OVERSIZED_RESPONSE)
        }
        val responseBytes = readBounded(body.byteStream())
            ?: return ResolverResult.Failure(ResolverFailureReason.OVERSIZED_RESPONSE)
        return when (
            val validation = ResponseValidation.validate(
                query = postedQuery,
                response = responseBytes,
                transport = ResolverTransport.MULLVAD_DOH,
            )
        ) {
            is ResponseValidationResult.Invalid -> ResolverResult.Failure(validation.reason, terminal = validation.terminal, peerAnswered = validation.peerAnswered)
            is ResponseValidationResult.Valid -> {
                val restored = validation.dnsMessage
                restored[0] = callerIdHigh
                restored[1] = callerIdLow
                ResolverResult.Answer(restored, ResolverTransport.MULLVAD_DOH, httpProtocol = when (response.protocol) {
                    Protocol.HTTP_2 -> com.lavasec.app.foundation.health.DnsHttpProtocol.HTTP_2
                    Protocol.HTTP_1_1 -> com.lavasec.app.foundation.health.DnsHttpProtocol.HTTP_1_1
                    else -> null
                })
            }
        }
    }

    private fun readBounded(input: InputStream): ByteArray? {
        input.use {
            val sentinel = ByteArray(MAX_DNS_MESSAGE_BYTES + 1)
            var count = 0
            while (count < sentinel.size) {
                val read = it.read(sentinel, count, sentinel.size - count)
                if (read < 0) break
                if (read == 0) {
                    val oneByte = it.read()
                    if (oneByte < 0) break
                    sentinel[count++] = oneByte.toByte()
                } else {
                    count += read
                }
            }
            if (count > MAX_DNS_MESSAGE_BYTES) return null
            return sentinel.copyOf(count)
        }
    }

    private fun hasDnsMediaType(value: String?): Boolean =
        value
            ?.substringBefore(';')
            ?.trim()
            ?.equals(DNS_MEDIA_TYPE_VALUE, ignoreCase = true)
            ?: false

    @OptIn(InternalCoroutinesApi::class)
    private fun publishTerminal(
        handle: RegistryOwnedCall,
        result: ResolverResult,
        request: ResolverRequest,
        continuation: CancellableContinuation<ResolverResult>,
        closeAfterOwnershipTransfer: Boolean = false,
    ) {
        val published = registry.completeIfCurrent(handle, epoch) {
            isMatchingRequestContext(request) &&
                runtimeCalls.complete(handle) {
                    if (
                        result is ResolverResult.Failure &&
                        result.reason == ResolverFailureReason.TIMEOUT && !result.peerAnswered && !result.local
                    ) {
                        connectionPool.evictAll()
                    }
                    val token = continuation.tryResume(result) ?: return@complete false
                    val terminalized = if (closeAfterOwnershipTransfer) {
                        handle.closeAfterOwnershipTransfer()
                    } else {
                        handle.release()
                    }
                    if (!terminalized) {
                        false
                    } else {
                        continuation.completeResume(token)
                        true
                    }
                }
        }
        if (published) return
        runtimeCalls.discard(handle)
        connectionPool.evictAll()
        continuation.tryResume(
            ResolverResult.Failure(ResolverFailureReason.STALE_EPOCH),
        )?.let(continuation::completeResume)
    }

    private fun cleanupCancelled(handle: RegistryOwnedCall) {
        val released = registry.unregister(handle)
        runtimeCalls.discard(handle)
        if (released) handle.close()
    }

    private fun isCurrentContext(request: ResolverRequest): Boolean =
        runtimeCalls.isOpen() &&
            registry.isCurrent(epoch) &&
            isMatchingRequestContext(request)

    private fun isMatchingRequestContext(request: ResolverRequest): Boolean =
            request.epoch == epoch &&
            request.networkContext.networkGeneration == networkContext.networkGeneration &&
            request.networkContext.network == networkContext.network

    private fun failureResult(call: Call, failure: IOException): ResolverResult.Failure {
        val reason = mapFailure(call, failure)
        return ResolverResult.Failure(reason, local = failure.findCause<ProtectedDohSocketException>()?.let {
            it.reason != ResolverFailureReason.STALE_EPOCH
        } == true)
    }

    private fun mapFailure(call: Call, failure: IOException): ResolverFailureReason {
        failure.findCause<ProtectedDohSocketException>()?.let { return it.reason }
        return when {
            failure.hasCause<SSLException>() -> ResolverFailureReason.TLS_FAILED
            failure.hasCause<SocketTimeoutException>() ||
                failure.hasCause<InterruptedIOException>() -> ResolverFailureReason.TIMEOUT
            call.isCanceled() -> ResolverFailureReason.CANCELLED
            failure.hasCause<UnknownHostException>() -> ResolverFailureReason.SOCKET_FAILED
            else -> ResolverFailureReason.RECEIVE_FAILED
        }
    }

    private class RegistryOwnedCall(
        private val call: Call,
    ) : AutoCloseable {
        private val terminal = AtomicBoolean(false)

        fun release(): Boolean = terminal.compareAndSet(false, true)

        override fun close() {
            closeAfterOwnershipTransfer()
        }

        fun closeAfterOwnershipTransfer(): Boolean {
            if (!terminal.compareAndSet(false, true)) return false
            runCatching { call.cancel() }
            return true
        }
    }

    private companion object {
        const val DNS_HEADER_BYTES = 12
        const val MAX_DNS_MESSAGE_BYTES = 4_096
        const val DNS_MEDIA_TYPE_VALUE = "application/dns-message"
        const val ACCEPT_HEADER = "Accept"
        const val CONTENT_TYPE_HEADER = "Content-Type"
        val SUCCESS_STATUS_RANGE = 200..299
        val DNS_MEDIA_TYPE = DNS_MEDIA_TYPE_VALUE.toMediaType()
    }
}

private inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) return current
        current = current.cause
    }
    return null
}

private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
    findCause<T>() != null
