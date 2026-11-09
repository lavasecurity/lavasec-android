package com.lavasec.app.foundation.dns

import com.lavasec.app.foundation.domain.DomainName
import com.lavasec.app.foundation.filter.FilterArtifactUnavailableReason
import com.lavasec.app.foundation.filter.FilterAction
import com.lavasec.app.foundation.filter.FilterDecision

enum class ResolverTransport { DEVICE_UDP, DEVICE_TCP, MULLVAD_DOH, WIREGUARD_UDP, DOQ, DOT, PLAIN_UDP, PLAIN_TCP }

enum class ResolverKind { DEVICE, MULLVAD_DOH, WIREGUARD_T0, PLAIN_DNS }

enum class ResolverFailureReason {
    TIMEOUT,
    PROTECT_FAILED,
    BIND_FAILED,
    SOCKET_FAILED,
    SEND_FAILED,
    RECEIVE_FAILED,
    MISMATCH_LIMIT,
    INVALID_RESPONSE,
    OVERSIZED_RESPONSE,
    HTTP_STATUS,
    HTTP_CONTENT_TYPE,
    TLS_FAILED,
    BACKED_OFF,
    CANCELLED,
    STALE_EPOCH,
    OVERLOADED,
}

enum class ResolverPlanUnavailableReason {
    NO_USABLE_RESOLVER,
    NO_RESOLVER_ENABLED,
    UNSUPPORTED_ENCRYPTED_RESOLVER,
}

sealed interface ProtectionUnavailableReason {
    data class Artifact(val reason: FilterArtifactUnavailableReason) : ProtectionUnavailableReason
    data class ResolverPlan(val reason: ResolverPlanUnavailableReason) : ProtectionUnavailableReason
    data object NetworkUnavailable : ProtectionUnavailableReason
    data object RuntimeUnavailable : ProtectionUnavailableReason
}

enum class TunWriteFailureReason { DESCRIPTOR_CLOSED, WRITE_FAILED, STALE_SESSION }

sealed interface DnsHealthEvent {
    data class ResolverAttemptFailed(
        val resolver: ResolverKind,
        val reason: ResolverFailureReason,
    ) : DnsHealthEvent

    data class FallbackActivated(val resolver: ResolverKind) : DnsHealthEvent
    data class ResolverRecovered(val resolver: ResolverKind) : DnsHealthEvent
    data class ProtectionUnavailable(val reason: ProtectionUnavailableReason) : DnsHealthEvent
    data class TunWriteFailed(val reason: TunWriteFailureReason) : DnsHealthEvent
}

data class DnsDecisionEvent(
    val id: Long,
    val timestampMillis: Long,
    val normalizedDomain: String,
    val decision: FilterDecision,
    /** Terminal resolver evidence is absent for local responses. */
    val resolver: ResolverKind? = null,
    val transport: ResolverTransport? = null,
    val latencyMillis: Long? = null,
    val error: ResolverFailureReason? = null,
) {
    init {
        require(DomainName.parse(normalizedDomain).value == normalizedDomain) {
            "normalizedDomain must be a canonical domain"
        }
        require(latencyMillis == null || latencyMillis >= 0) {
            "latencyMillis must be non-negative"
        }
        val hasResolverEvidence = transport != null || latencyMillis != null || error != null
        require(resolver != null || !hasResolverEvidence) {
            "resolver evidence requires a resolver"
        }
        require(
            transport == null || when (resolver) {
                ResolverKind.DEVICE -> transport == ResolverTransport.DEVICE_UDP ||
                    transport == ResolverTransport.DEVICE_TCP
                ResolverKind.MULLVAD_DOH -> transport == ResolverTransport.MULLVAD_DOH ||
                    transport == ResolverTransport.DOQ || transport == ResolverTransport.DOT
                ResolverKind.PLAIN_DNS -> transport == ResolverTransport.PLAIN_UDP || transport == ResolverTransport.PLAIN_TCP
                ResolverKind.WIREGUARD_T0 -> transport == ResolverTransport.WIREGUARD_UDP
                null -> false
            },
        ) {
            "transport must match its resolver"
        }
        require(decision.action != FilterAction.BLOCK || resolver == null && !hasResolverEvidence) {
            "local blocked decisions cannot contain resolver evidence"
        }
    }
}
