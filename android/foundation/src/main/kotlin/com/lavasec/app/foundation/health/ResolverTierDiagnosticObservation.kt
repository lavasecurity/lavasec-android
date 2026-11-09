package com.lavasec.app.foundation.health

import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.dns.ResolverTransport

/** Domain-free route evidence. Persisted history accepts only these finite protocol facts. */
data class ResolverTierDiagnosticObservation(
    val tier: Int,
    val resolver: ResolverKind,
    val outcome: ResolverTierDiagnosticOutcome,
    val transport: ResolverTransport? = null,
    val failure: ResolverFailureReason? = null,
    val peerLivenessOnly: Boolean = false,
    val responseClass: DnsFallbackResponseClass? = null,
    val responseCode: Int? = null,
    val queryType: Int? = null,
    val terminalAddressPresent: Boolean? = null,
    /** Null means the transport did not establish whether an attempt reached the wire. */
    val packetSent: Boolean? = null,
) {
    init {
        require(tier in 0..2)
        require(responseCode == null || responseCode in 0..4095)
        require(queryType == null || queryType in 0..65535)
    }
}
enum class ResolverTierDiagnosticOutcome { SERVED, ANSWERED, FAILED, NOT_ATTEMPTED }
enum class DnsFallbackResponseClass { POSITIVE, NEGATIVE, DNS_ERROR, MALFORMED, MISMATCHED, TRUNCATED }
enum class DnsHttpProtocol { HTTP_1_1, HTTP_2 }
