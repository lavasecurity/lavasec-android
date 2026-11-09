package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverTransport
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

/**
 * Custom DNS-over-TLS (RFC 7858) runtime for one complete engine epoch and network.
 *
 * It reuses the exact guarded socket path the custom DoH route uses: the raw TCP socket is
 * created by [ProtectedDohSocketFactory], so it is registered against the epoch, protected from
 * VPN recursion, and bound to the captured underlying network before any byte is written. The
 * endpoint hostname is resolved through that same [Network], never the process resolver. The
 * exchange is the standard length-prefixed DNS-over-TCP framing.
 *
 * The route publishes [ResolverTransport.DOT], preserving the actual TLS transport in query and
 * activity evidence while sharing the engine's encrypted resolver kind.
 */
internal class CustomDotRuntime(
    private val epoch: EngineEpoch,
    private val networkContext: NetworkContext,
    registry: ResolverResourceRegistry,
    protector: DohSocketProtector,
    binder: DohSocketBinder,
    private val endpoint: CustomDotEndpoint,
    tls: MullvadDohTlsConfiguration? = null,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    bootstrapAddresses: List<InetAddress> = emptyList(),
    clock: MonotonicClock = SystemMonotonicClock,
) : ReadyDnsMullvadRuntime {
    private val socketFactory = ProtectedDohSocketFactory(
        epoch = epoch,
        network = networkContext.network,
        registry = registry,
        protector = protector,
        binder = binder,
    )

    private val delegate: DnsResolver = CustomDotResolver(
        epoch = epoch,
        networkContext = networkContext,
        registry = registry,
        socketFactory = socketFactory,
        tlsSocketFactory = tls?.socketFactory ?: (SSLSocketFactory.getDefault() as SSLSocketFactory),
        endpoint = endpoint,
        ioDispatcher = ioDispatcher,
        bootstrapAddresses = bootstrapAddresses.toList(),
        clock = clock,
    )

    override val resolver: DnsResolver get() = delegate

    override fun close() {
        // Each query owns and closes its own socket; retiring the registry tears down any in-flight
        // socket. There are no other long-lived resources to release.
    }
}

private class CustomDotResolver(
    private val epoch: EngineEpoch,
    private val networkContext: NetworkContext,
    private val registry: ResolverResourceRegistry,
    private val socketFactory: ProtectedDohSocketFactory,
    private val endpoint: CustomDotEndpoint,
    private val tlsSocketFactory: SSLSocketFactory,
    private val ioDispatcher: CoroutineDispatcher,
    private val bootstrapAddresses: List<InetAddress>,
    private val clock: MonotonicClock,
) : DnsResolver {
    @OptIn(InternalCoroutinesApi::class)
    override suspend fun resolve(request: ResolverRequest): ResolverResult {
        if (!isCurrentContext(request)) {
            return ResolverResult.Failure(ResolverFailureReason.STALE_EPOCH)
        }
        val deadline = request.absoluteDeviceDeadlineNanos ?: (clock.nowNanos() + CONNECT_TIMEOUT_MILLIS * 1_000_000L)
        if (clock.nowNanos() >= deadline) return ResolverResult.Failure(ResolverFailureReason.TIMEOUT, local = true)
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

        return withContext(ioDispatcher) {
            var socket: Socket? = null
            val ownedSocket = AtomicReference<Socket?>()
            // A Java/Conscrypt read is blocking: cancelling the IO coroutine alone does not wake
            // it. Close the same epoch-owned raw socket on cancellation, as registry retirement
            // does, so cancellation promptly releases the transport instead of waiting for its
            // read timeout. The activity check after publication covers cancellation racing the
            // socket assignment.
            val cancellation = currentCoroutineContext().job.invokeOnCompletion(
                onCancelling = true,
                invokeImmediately = true,
            ) { cause ->
                if (cause is CancellationException) {
                    runCatching { ownedSocket.get()?.close() }
                }
            }
            try {
                currentCoroutineContext().ensureActive()
                val addresses = bootstrapAddresses.ifEmpty { NetworkDoqAddressLookup.resolve(networkContext.network, endpoint.host) }
                if (addresses.isEmpty()) return@withContext ResolverResult.Failure(ResolverFailureReason.SOCKET_FAILED, local = true)
                val connectDeadline = minOf(deadline, clock.nowNanos() + CONNECT_TIMEOUT_MILLIS * 1_000_000L)
                var lastFailure: IOException? = null
                for (address in addresses) {
                    currentCoroutineContext().ensureActive()
                    if (!isCurrentContext(request)) return@withContext ResolverResult.Failure(ResolverFailureReason.STALE_EPOCH)
                    val remaining = ((connectDeadline - clock.nowNanos()) / 1_000_000L).coerceAtMost(CONNECT_TIMEOUT_MILLIS.toLong()).toInt()
                    if (remaining <= 0) throw SocketTimeoutException("DoT connection deadline")
                    val candidate = socketFactory.createSocket()
                    ownedSocket.set(candidate)
                    currentCoroutineContext().ensureActive()
                    try {
                        candidate.connect(InetSocketAddress(address, endpoint.port), remaining)
                        socket = candidate
                        lastFailure = null
                        break
                    } catch (failure: IOException) {
                        runCatching { candidate.close() }
                        lastFailure = failure
                    }
                }
                lastFailure?.let { throw it }
                if (socket == null) return@withContext ResolverResult.Failure(ResolverFailureReason.SOCKET_FAILED)
                val tls = tlsSocketFactory
                    .createSocket(socket, endpoint.host, endpoint.port, true) as SSLSocket
                tls.soTimeout = ((deadline - clock.nowNanos()) / 1_000_000L).coerceIn(1, READ_TIMEOUT_MILLIS.toLong()).toInt()
                tls.sslParameters = tls.sslParameters.apply {
                    endpointIdentificationAlgorithm = ENDPOINT_IDENTIFICATION
                }
                if (clock.nowNanos() >= deadline) throw SocketTimeoutException("DoT tier deadline")
                tls.startHandshake()
                if (clock.nowNanos() >= deadline) throw SocketTimeoutException("DoT tier deadline")
                val output = tls.outputStream
                output.write(lengthPrefixed(postedQuery))
                output.flush()
                val result = exchange(tls.inputStream, postedQuery, callerIdHigh, callerIdLow, request)
                if (result is ResolverResult.Answer && clock.nowNanos() >= deadline) ResolverResult.Failure(ResolverFailureReason.TIMEOUT, terminal = true, peerAnswered = true)
                else if (isCurrentContext(request)) result
                else ResolverResult.Failure(ResolverFailureReason.STALE_EPOCH)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: ProtectedDohSocketException) {
                ResolverResult.Failure(failure.reason, local = true)
            } catch (_: SSLException) {
                ResolverResult.Failure(currentFailure(ResolverFailureReason.TLS_FAILED))
            } catch (_: SocketTimeoutException) {
                ResolverResult.Failure(currentFailure(ResolverFailureReason.TIMEOUT), local = ownedSocket.get() == null)
            } catch (_: IOException) {
                ResolverResult.Failure(
                    if (registry.isCurrent(epoch)) {
                        ResolverFailureReason.RECEIVE_FAILED
                    } else {
                        ResolverFailureReason.STALE_EPOCH
                    }, local = ownedSocket.get() == null,
                )
            } catch (_: Throwable) {
                ResolverResult.Failure(ResolverFailureReason.SOCKET_FAILED, local = ownedSocket.get() == null)
            } finally {
                cancellation.dispose()
                runCatching { ownedSocket.get()?.close() }
            }
        }
    }

    private fun exchange(
        input: InputStream,
        postedQuery: ByteArray,
        callerIdHigh: Byte,
        callerIdLow: Byte,
        request: ResolverRequest,
    ): ResolverResult {
        val length = readLength(input) ?: return ResolverResult.Failure(
            ResolverFailureReason.RECEIVE_FAILED,
        )
        if (length == 0) return ResolverResult.Failure(ResolverFailureReason.INVALID_RESPONSE)
        if (length !in 1..MAX_DNS_MESSAGE_BYTES) {
            return ResolverResult.Failure(
                if (length > MAX_DNS_MESSAGE_BYTES) {
                    ResolverFailureReason.OVERSIZED_RESPONSE
                } else {
                    ResolverFailureReason.INVALID_RESPONSE
                },
            )
        }
        val body = readExactly(input, length)
            ?: return ResolverResult.Failure(ResolverFailureReason.RECEIVE_FAILED)
        if (body.size != length) {
            return ResolverResult.Failure(ResolverFailureReason.RECEIVE_FAILED)
        }
        return when (
            val validation = ResponseValidation.validate(
                query = postedQuery,
                response = body,
                transport = ResolverTransport.DOT,
            )
        ) {
            is ResponseValidationResult.Invalid -> ResolverResult.Failure(validation.reason, terminal = validation.terminal, peerAnswered = validation.peerAnswered)
            is ResponseValidationResult.Valid -> {
                if (!isCurrentContext(request)) {
                    return ResolverResult.Failure(ResolverFailureReason.STALE_EPOCH)
                }
                val restored = validation.dnsMessage
                restored[0] = callerIdHigh
                restored[1] = callerIdLow
                ResolverResult.Answer(restored, ResolverTransport.DOT)
            }
        }
    }

    private fun readLength(input: InputStream): Int? {
        val prefix = readExactly(input, LENGTH_PREFIX_BYTES) ?: return null
        return ((prefix[0].toInt() and 0xff) shl 8) or (prefix[1].toInt() and 0xff)
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray? {
        if (length < 0) return null
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(body, offset, length - offset)
            if (read < 0) return null
            if (read > 0) offset += read
        }
        return body
    }

    private fun lengthPrefixed(query: ByteArray): ByteArray =
        ByteArray(query.size + LENGTH_PREFIX_BYTES).also { frame ->
            frame[0] = (query.size ushr 8).toByte()
            frame[1] = query.size.toByte()
            query.copyInto(frame, destinationOffset = LENGTH_PREFIX_BYTES)
        }

    private fun isCurrentContext(request: ResolverRequest): Boolean =
        registry.isCurrent(epoch) &&
            request.epoch == epoch &&
            request.networkContext.networkGeneration == networkContext.networkGeneration &&
            request.networkContext.network == networkContext.network

    private fun currentFailure(reason: ResolverFailureReason): ResolverFailureReason =
        if (registry.isCurrent(epoch)) reason else ResolverFailureReason.STALE_EPOCH

    private companion object {
        const val DNS_HEADER_BYTES = 12
        const val LENGTH_PREFIX_BYTES = 2
        const val MAX_DNS_MESSAGE_BYTES = 4_096
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val READ_TIMEOUT_MILLIS = 5_000
        const val ENDPOINT_IDENTIFICATION = "HTTPS"
    }
}
