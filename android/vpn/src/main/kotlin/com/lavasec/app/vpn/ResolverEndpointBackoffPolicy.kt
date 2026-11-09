package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.ResolverFailureReason
import java.net.InetAddress

internal sealed interface ResolverEndpointKey {
    sealed interface Address : ResolverEndpointKey { val address: InetAddress }

    data class DeviceAddress(override val address: InetAddress) : Address

    data class FixedAddress(val identity: com.lavasec.app.foundation.health.PrimaryResolverIdentity, override val address: InetAddress) : Address

    data object MullvadDoh : ResolverEndpointKey

    data class Encrypted(val identity: com.lavasec.app.foundation.health.PrimaryResolverIdentity) : ResolverEndpointKey
}

internal class ResolverEndpointBackoffPolicy(
    private val clock: MonotonicClock = SystemMonotonicClock,
) {
    private val expiryByEndpoint = mutableMapOf<ResolverEndpointKey, Long>()
    private var active = true

    @Synchronized
    internal fun isBackedOff(endpoint: ResolverEndpointKey): Boolean {
        if (!active) return false
        val expiry = expiryByEndpoint[endpoint] ?: return false
        if (clock.nowNanos() - expiry >= 0L) {
            expiryByEndpoint.remove(endpoint)
            return false
        }
        return true
    }

    @Synchronized
    internal fun recordSuccess(endpoint: ResolverEndpointKey) {
        if (active) expiryByEndpoint.remove(endpoint)
    }

    @Synchronized
    internal fun recordFailure(
        endpoint: ResolverEndpointKey,
        reason: ResolverFailureReason,
    ) {
        if (!active || !reason.isRetryWorthy()) return
        expiryByEndpoint[endpoint] = clock.nowNanos() + BACKOFF_INTERVAL_NANOS
    }

    @Synchronized
    internal fun retire() {
        if (!active) return
        active = false
        expiryByEndpoint.clear()
    }

    @Synchronized
    internal fun reset() {
        if (active) expiryByEndpoint.clear()
    }

    private fun ResolverFailureReason.isRetryWorthy(): Boolean = when (this) {
        ResolverFailureReason.TIMEOUT,
        ResolverFailureReason.PROTECT_FAILED,
        ResolverFailureReason.BIND_FAILED,
        ResolverFailureReason.SOCKET_FAILED,
        ResolverFailureReason.SEND_FAILED,
        ResolverFailureReason.RECEIVE_FAILED,
        ResolverFailureReason.MISMATCH_LIMIT,
        ResolverFailureReason.INVALID_RESPONSE,
        ResolverFailureReason.OVERSIZED_RESPONSE,
        ResolverFailureReason.HTTP_STATUS,
        ResolverFailureReason.HTTP_CONTENT_TYPE,
        ResolverFailureReason.TLS_FAILED,
        -> true

        ResolverFailureReason.BACKED_OFF,
        ResolverFailureReason.CANCELLED,
        ResolverFailureReason.STALE_EPOCH,
        ResolverFailureReason.OVERLOADED,
        -> false
    }

    private companion object {
        const val BACKOFF_INTERVAL_NANOS = 30_000_000_000L
    }
}
