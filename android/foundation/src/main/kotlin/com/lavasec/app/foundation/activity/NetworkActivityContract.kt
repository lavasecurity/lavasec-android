package com.lavasec.app.foundation.activity

import com.lavasec.app.foundation.dns.DnsHealthEvent
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.health.ProtectionFallback
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.foundation.resolver.BuiltInDohResolverIds

enum class NetworkActivityNetworkKind {
    UNKNOWN,
    WIFI,
    CELLULAR,
    WIRED,
    OTHER,
}

enum class NetworkActivityUserAction {
    TURN_PROTECTION_ON,
    TURN_PROTECTION_OFF,
    RECONNECT_PROTECTION,
    CHANGE_RESOLVER,
    TOGGLE_DEVICE_DNS_FALLBACK,
    CHANGE_FILTERS,
    CLEAR_ACTIVITY,
}

enum class NetworkActivityDohHttpVersion {
    HTTP_1_1,
    HTTP_2,
    HTTP_3,
}

enum class NetworkActivityResolverIdentity {
    NONE,
    DEVICE_DNS,
    MULLVAD,
    CLOUDFLARE,
    QUAD9_SECURE,
    HAGEZI_ROOT,
    GOOGLE_PUBLIC_DNS,
    ENCRYPTED_OTHER,
    WIREGUARD_T0,
    QUAD9_UNFILTERED,
    PLAIN_OTHER,
    ;

    companion object {
        fun fromBuiltInDohResolverId(resolverId: String): NetworkActivityResolverIdentity =
            when (resolverId) {
                BuiltInDohResolverIds.MULLVAD -> MULLVAD
                BuiltInDohResolverIds.CLOUDFLARE -> CLOUDFLARE
                BuiltInDohResolverIds.QUAD9_SECURE -> QUAD9_SECURE
                BuiltInDohResolverIds.QUAD9_UNFILTERED -> QUAD9_UNFILTERED
                BuiltInDohResolverIds.HAGEZI_ROOT -> HAGEZI_ROOT
                BuiltInDohResolverIds.GOOGLE_PUBLIC_DNS -> GOOGLE_PUBLIC_DNS
                else -> ENCRYPTED_OTHER
            }
    }
}

enum class NetworkActivityResolverTransport {
    DEVICE_UDP,
    DEVICE_TCP,
    DNS_OVER_HTTPS,
    DNS_OVER_QUIC,
    WIREGUARD_UDP,
    DNS_OVER_TLS,
    PLAIN_UDP,
    PLAIN_TCP,
}

sealed interface NetworkActivityProbeFailure {
    data class ResolverFailure(
        val reason: ResolverFailureReason,
    ) : NetworkActivityProbeFailure

    data object RejectedResponse : NetworkActivityProbeFailure
}

sealed interface NetworkActivityFallbackActivationCause {
    data object ProducerUnspecified : NetworkActivityFallbackActivationCause

    data object QueryFallback : NetworkActivityFallbackActivationCause

    data object FallbackCheck : NetworkActivityFallbackActivationCause

    data class PrimaryFailure(
        val reason: ResolverFailureReason,
    ) : NetworkActivityFallbackActivationCause
}

sealed interface NetworkActivityReconnectReason {
    data class ResolverFailure(
        val reason: ResolverFailureReason,
    ) : NetworkActivityReconnectReason

    data object RejectedResponse : NetworkActivityReconnectReason

    data object DeviceDnsRecapture : NetworkActivityReconnectReason

    data object RuntimeUnavailable : NetworkActivityReconnectReason
}

enum class NetworkActivityRecoveryReason {
    PRIMARY_RESOLVER,
    DEVICE_DNS_FALLBACK,
    ENCRYPTED_RESOLVER,
}

enum class NetworkActivitySettingsFailureReason {
    PLATFORM_REJECTED,
    STALE_SESSION,
    RUNTIME_UNAVAILABLE,
}

/** Closed, domain-free event values whose complete equality defines duplicate identity. */
sealed interface NetworkActivityEvent {
    data class NetworkChanged(
        val from: NetworkActivityNetworkKind?,
        val to: NetworkActivityNetworkKind,
        val pathSatisfied: Boolean,
    ) : NetworkActivityEvent

    data object ProtectionConnected : NetworkActivityEvent

    data class UserAction(
        val action: NetworkActivityUserAction,
    ) : NetworkActivityEvent

    data class DnsSmokeProbeSucceeded(
        val resolver: NetworkActivityResolverIdentity,
        val transport: NetworkActivityResolverTransport,
        val dohHttpVersion: NetworkActivityDohHttpVersion?,
    ) : NetworkActivityEvent {
        init {
            requireCoherentResolverTransport(resolver, transport)
            require(
                transport == NetworkActivityResolverTransport.DNS_OVER_HTTPS ||
                    dohHttpVersion == null,
            ) {
                "DoH version requires an encrypted resolver"
            }
        }
    }

    data class DnsSmokeProbeFailed(
        val reason: NetworkActivityProbeFailure,
    ) : NetworkActivityEvent

    data class FallbackActivated(
        val fallback: ProtectionFallback,
        val cause: NetworkActivityFallbackActivationCause,
    ) : NetworkActivityEvent {
        init {
            require(fallback != ProtectionFallback.NONE) {
                "A fallback activation must identify an active fallback"
            }
        }
    }

    data object DeviceDnsFallbackRecovered : NetworkActivityEvent

    data class ReconnectNeeded(
        val reason: NetworkActivityReconnectReason,
    ) : NetworkActivityEvent

    data class ConnectivityRecovered(
        val reason: NetworkActivityRecoveryReason,
    ) : NetworkActivityEvent

    data class NetworkSettingsReapplyFailed(
        val reason: NetworkActivitySettingsFailureReason,
    ) : NetworkActivityEvent
}

fun DnsHealthEvent.FallbackActivated.toNetworkActivityFallbackEvent():
    NetworkActivityEvent.FallbackActivated =
    NetworkActivityEvent.FallbackActivated(
        fallback = when (resolver) {
            ResolverKind.PLAIN_DNS -> ProtectionFallback.PLAIN_DNS
            ResolverKind.DEVICE -> ProtectionFallback.DEVICE_DNS
            ResolverKind.MULLVAD_DOH -> ProtectionFallback.ENCRYPTED_DNS
            ResolverKind.WIREGUARD_T0 -> error("WireGuard T0 is a primary resolver, never a fallback")
        },
        cause = NetworkActivityFallbackActivationCause.ProducerUnspecified,
    )

enum class NetworkActivityProtectionState {
    OFF,
    STARTING,
    PROTECTED,
    PAUSED,
    STOPPING,
}

enum class NetworkActivityConnectivityState {
    HEALTHY,
    DEVICE_DNS_FALLBACK,
    ENCRYPTED_FALLBACK,
    RECOVERING,
    DNS_SLOW,
    NEEDS_RECONNECT,
    NETWORK_LOST,
    PLAIN_DNS_FALLBACK,
}

/** Protection state captured with an occurrence rather than inferred when the row is read. */
data class NetworkActivityCapturedState(
    val protection: NetworkActivityProtectionState,
    val connectivity: NetworkActivityConnectivityState,
    val networkKind: NetworkActivityNetworkKind,
    val networkPathSatisfied: Boolean,
    val resolver: NetworkActivityResolverIdentity,
    val resolverTransport: NetworkActivityResolverTransport?,
    val fallbackCoverage: ResolverFallbackCoverage,
    val activeFallback: ProtectionFallback,
) {
    init {
        requireCoherentResolverTransport(resolver, resolverTransport)
        require(
            activeFallback == ProtectionFallback.NONE ||
                (resolver == NetworkActivityResolverIdentity.WIREGUARD_T0 &&
                    fallbackCoverage != ResolverFallbackCoverage.NONE) ||
                when (fallbackCoverage) {
                    ResolverFallbackCoverage.PLAIN_DNS -> activeFallback == ProtectionFallback.PLAIN_DNS
                    ResolverFallbackCoverage.NONE -> false
                    ResolverFallbackCoverage.DEVICE_DNS ->
                        activeFallback == ProtectionFallback.DEVICE_DNS
                    ResolverFallbackCoverage.ENCRYPTED_DNS ->
                        activeFallback == ProtectionFallback.ENCRYPTED_DNS
                },
        ) {
            "An active fallback must exactly match the configured fallback coverage"
        }
        require(
            when (resolver) {
                NetworkActivityResolverIdentity.NONE -> fallbackCoverage == ResolverFallbackCoverage.NONE
                // Only consented split-chain production composition supplies non-NONE coverage.
                // The actual saved route may use a different category after its own failover.
                NetworkActivityResolverIdentity.WIREGUARD_T0 -> true
                NetworkActivityResolverIdentity.DEVICE_DNS ->
                    fallbackCoverage != ResolverFallbackCoverage.DEVICE_DNS
                NetworkActivityResolverIdentity.MULLVAD,
                NetworkActivityResolverIdentity.CLOUDFLARE,
                NetworkActivityResolverIdentity.QUAD9_SECURE,
                NetworkActivityResolverIdentity.QUAD9_UNFILTERED,
                NetworkActivityResolverIdentity.HAGEZI_ROOT,
                NetworkActivityResolverIdentity.GOOGLE_PUBLIC_DNS,
                NetworkActivityResolverIdentity.ENCRYPTED_OTHER,
                NetworkActivityResolverIdentity.PLAIN_OTHER,
                -> true // Ordered ladders retain the exact primary and fallback transport categories.
            },
        ) {
            "A resolver without a DNS route cannot advertise fallback coverage"
        }
    }
}

/**
 * A domain-free Network Activity row.
 *
 * [id] remains stable when matching occurrences roll up. Retention is anchored to
 * [firstOccurrenceEpochMillis], while [latestOccurrenceEpochMillis] advances to the newest
 * occurrence represented by the row.
 */
data class NetworkActivityEntry(
    val id: Long,
    val latestOccurrenceEpochMillis: Long,
    val firstOccurrenceEpochMillis: Long = latestOccurrenceEpochMillis,
    val event: NetworkActivityEvent,
    val capturedState: NetworkActivityCapturedState,
    val occurrenceCount: Int = 1,
) {
    init {
        require(firstOccurrenceEpochMillis <= latestOccurrenceEpochMillis)
        require(occurrenceCount > 0)
    }
}

/** Pure value policy; collection, persistence, paging, clear/export, and presentation live later. */
object NetworkActivityContract {
    const val MAX_ROWS = 300
    const val ROLL_UP_WINDOW_MILLIS = 120_000L
    const val RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1_000L
    const val FUTURE_TIMESTAMP_TOLERANCE_MILLIS = 5L * 60L * 1_000L

    fun isRetainable(
        entry: NetworkActivityEntry,
        nowEpochMillis: Long,
    ): Boolean {
        val cutoff = saturatingSubtract(nowEpochMillis, RETENTION_MILLIS)
        val futureBound = saturatingAdd(nowEpochMillis, FUTURE_TIMESTAMP_TOLERANCE_MILLIS)
        return entry.firstOccurrenceEpochMillis in cutoff..futureBound &&
            entry.latestOccurrenceEpochMillis in entry.firstOccurrenceEpochMillis..futureBound
    }

    fun normalize(
        entries: Iterable<NetworkActivityEntry>,
        nowEpochMillis: Long,
    ): List<NetworkActivityEntry> {
        val retained = entries.filter { isRetainable(it, nowEpochMillis) }
        require(retained.map { it.id }.distinct().size == retained.size) {
            "Network Activity row identities must be unique"
        }
        return retained.sortedWith(
            compareByDescending<NetworkActivityEntry> { it.latestOccurrenceEpochMillis }
                .thenByDescending { it.id },
        )
            .take(MAX_ROWS)
    }

    fun record(
        entries: Iterable<NetworkActivityEntry>,
        incoming: NetworkActivityEntry,
        nowEpochMillis: Long,
    ): List<NetworkActivityEntry> {
        val retained = normalize(entries, nowEpochMillis).toMutableList()
        if (!isRetainable(incoming, nowEpochMillis)) return retained
        require(retained.none { it.id == incoming.id }) {
            "Network Activity row identities must be unique"
        }

        val matchingIndex = retained.indexOfFirst { existing ->
            existing.event == incoming.event &&
                existing.capturedState == incoming.capturedState &&
                isWithinRollingWindow(
                    existing.latestOccurrenceEpochMillis,
                    incoming.latestOccurrenceEpochMillis,
                ) &&
                incoming.firstOccurrenceEpochMillis >= existing.firstOccurrenceEpochMillis &&
                incoming.latestOccurrenceEpochMillis <=
                saturatingAdd(existing.firstOccurrenceEpochMillis, RETENTION_MILLIS)
        }
        if (matchingIndex < 0) return normalize(retained + incoming, nowEpochMillis)

        val existing = retained[matchingIndex]
        retained[matchingIndex] = existing.copy(
            latestOccurrenceEpochMillis = maxOf(
                existing.latestOccurrenceEpochMillis,
                incoming.latestOccurrenceEpochMillis,
            ),
            firstOccurrenceEpochMillis = minOf(
                existing.firstOccurrenceEpochMillis,
                incoming.firstOccurrenceEpochMillis,
            ),
            occurrenceCount = minOf(
                Int.MAX_VALUE.toLong(),
                existing.occurrenceCount.toLong() + incoming.occurrenceCount.toLong(),
            ).toInt(),
        )
        return normalize(retained, nowEpochMillis)
    }

    private fun isWithinRollingWindow(left: Long, right: Long): Boolean =
        if (left >= right) {
            right >= saturatingSubtract(left, ROLL_UP_WINDOW_MILLIS)
        } else {
            left >= saturatingSubtract(right, ROLL_UP_WINDOW_MILLIS)
        }

    private fun saturatingSubtract(value: Long, delta: Long): Long =
        if (value < Long.MIN_VALUE + delta) Long.MIN_VALUE else value - delta

    private fun saturatingAdd(value: Long, delta: Long): Long =
        if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta
}

private fun requireCoherentResolverTransport(
    resolver: NetworkActivityResolverIdentity,
    transport: NetworkActivityResolverTransport?,
) {
    require(
        when (resolver) {
            NetworkActivityResolverIdentity.NONE -> transport == null
            NetworkActivityResolverIdentity.WIREGUARD_T0 -> transport == NetworkActivityResolverTransport.WIREGUARD_UDP
            NetworkActivityResolverIdentity.DEVICE_DNS ->
                transport == NetworkActivityResolverTransport.DEVICE_UDP ||
                    transport == NetworkActivityResolverTransport.DEVICE_TCP
            NetworkActivityResolverIdentity.PLAIN_OTHER -> transport == NetworkActivityResolverTransport.PLAIN_UDP || transport == NetworkActivityResolverTransport.PLAIN_TCP
            NetworkActivityResolverIdentity.MULLVAD,
            NetworkActivityResolverIdentity.CLOUDFLARE,
            NetworkActivityResolverIdentity.QUAD9_SECURE,
            NetworkActivityResolverIdentity.QUAD9_UNFILTERED,
            NetworkActivityResolverIdentity.HAGEZI_ROOT,
            NetworkActivityResolverIdentity.GOOGLE_PUBLIC_DNS,
            -> transport == NetworkActivityResolverTransport.DNS_OVER_HTTPS ||
                transport == NetworkActivityResolverTransport.DNS_OVER_QUIC ||
                transport == NetworkActivityResolverTransport.DNS_OVER_TLS ||
                transport == NetworkActivityResolverTransport.PLAIN_UDP || transport == NetworkActivityResolverTransport.PLAIN_TCP
            NetworkActivityResolverIdentity.ENCRYPTED_OTHER ->
                transport == NetworkActivityResolverTransport.DNS_OVER_HTTPS ||
                    transport == NetworkActivityResolverTransport.DNS_OVER_QUIC ||
                    transport == NetworkActivityResolverTransport.DNS_OVER_TLS
        },
    ) {
        "Transport must match its resolver"
    }
}
