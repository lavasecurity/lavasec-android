package com.lavasec.app.foundation.diagnostics

import com.lavasec.app.foundation.domain.DomainName
import com.lavasec.app.foundation.domain.DomainValidationException
import com.lavasec.app.foundation.filter.DomainFilterDecisionSource
import com.lavasec.app.foundation.filter.FilterAction
import com.lavasec.app.foundation.filter.FilterDecisionReason
import com.lavasec.app.foundation.health.ProtectionHealthEvidence
import com.lavasec.app.foundation.health.ProtectionHealthPolicy
import com.lavasec.app.foundation.health.ProtectionHealthSeverity

/**
 * Canonical copy keys for the pre-submission diagnostic decision.
 *
 * The identifiers are the canonical preview item/section ids from
 * `Sources/LavaSecAppServices/BugReportBundle.swift` (`previewSections` and their item `id`s,
 * pinned at iOS `c4658d973bae05dd3b7aa84c014cb2f76ff61a0b`). They are locale-independent wire
 * identifiers, never rendered copy; the surface resolves each one to localized text. Reproducing
 * them verbatim is what keeps an Android decision tied to the same copy contract as iOS.
 */
enum class DiagnosticCopyKey(val id: String) {
    /** `filterSnapshotSection` item `affected_site_filter`. */
    AFFECTED_SITE_FILTER("affected_site_filter"),

    /** `incidentSummarySection` section id `incident_summary`. */
    INCIDENT_SUMMARY("incident_summary"),

    /** `networkResolverSection` section id `network_resolver`. */
    NETWORK_RESOLVER("network_resolver"),
}

/**
 * Decision for the user's affected-site input.
 *
 * Ports `BugReportBundle.BugReportAffectedSiteFilterDecision.make(rawAffectedSite:snapshot:)`:
 * a URL or bare host is reduced to its host, normalized through the pinned domain boundary, and
 * evaluated against the resident filter snapshot. Absence, malformed input, and a missing rule
 * snapshot are kept distinct so the surface can report the real reason instead of guessing.
 */
sealed interface AffectedSiteDiagnosticDecision {
    /** Canonical preview item id that carries this decision. */
    val copyKey: DiagnosticCopyKey get() = DiagnosticCopyKey.AFFECTED_SITE_FILTER

    /** The user did not provide an affected site (empty or whitespace-only). */
    data object Absent : AffectedSiteDiagnosticDecision

    /** The user provided input that is not a valid DNS hostname. */
    data object Malformed : AffectedSiteDiagnosticDecision

    /**
     * A valid hostname was provided but no filter decision source is resident.
     *
     * This is the omit-never-approximate boundary: without the actual rules the model must not
     * return a `DEFAULT_ALLOW` (or any) decision it did not measure.
     */
    data object Unavailable : AffectedSiteDiagnosticDecision

    /** The affected site was normalized and evaluated against the resident rule snapshot. */
    data class Decided(
        /** The single normalized affected-site domain the decision carries. */
        val domain: String,
        val action: FilterAction,
        val reason: FilterDecisionReason,
    ) : AffectedSiteDiagnosticDecision {
        /** Exact canonical iOS wire category (`FilterDecisionReason.rawValue`). */
        val category: String get() = reason.canonicalWireValue()
    }

    companion object {
        /**
         * Normalizes [rawAffectedSite] and evaluates it against [filterDecisions].
         *
         * Mirrors the canonical `normalizedDomain(from:)` fallback order: a parseable URL host
         * wins, otherwise the value is treated as a bare host. A missing [filterDecisions] yields
         * [Unavailable] rather than an approximated decision.
         */
        fun make(
            rawAffectedSite: String?,
            filterDecisions: DomainFilterDecisionSource?,
        ): AffectedSiteDiagnosticDecision {
            if (rawAffectedSite == null || rawAffectedSite.isBlank()) return Absent

            val domain = normalizeDomain(rawAffectedSite) ?: return Malformed
            val source = filterDecisions ?: return Unavailable
            val decision = source.decideNormalized(domain)
            return Decided(domain = domain, action = decision.action, reason = decision.reason)
        }

        /**
         * Reduces a URL or bare host to a normalized DNS hostname, or `null` when it is not one.
         *
         * The host extraction reproduces the canonical `URLComponents(...)?.host` /
         * `https://`-prefixed fallback without a platform URL parser: userinfo (which could carry
         * credentials), the port, and the path/query/fragment are dropped before the pinned
         * [DomainName] boundary runs. Only the resulting hostname can leave this function.
         */
        internal fun normalizeDomain(rawAffectedSite: String): String? {
            val host = extractHost(rawAffectedSite) ?: return null
            return try {
                DomainName.parse(host).value
            } catch (_: DomainValidationException) {
                null
            }
        }

        private fun extractHost(raw: String): String? {
            var candidate = raw.trim()
            if (candidate.isEmpty()) return null

            val schemeSeparator = candidate.indexOf("://")
            if (schemeSeparator >= 0) {
                candidate = candidate.substring(schemeSeparator + 3)
            }

            val pathStart = candidate.indexOfFirst { it == '/' || it == '?' || it == '#' }
            if (pathStart >= 0) {
                candidate = candidate.substring(0, pathStart)
            }

            val userInfoEnd = candidate.lastIndexOf('@')
            if (userInfoEnd >= 0) {
                candidate = candidate.substring(userInfoEnd + 1)
            }

            // A bracketed IPv6 literal is not a DNS hostname; leave it intact so the pinned
            // domain boundary rejects it rather than us stripping a meaningful colon.
            if (!candidate.startsWith("[")) {
                val portSeparator = candidate.indexOf(':')
                if (portSeparator >= 0) {
                    candidate = candidate.substring(0, portSeparator)
                }
            }

            return candidate.ifEmpty { null }
        }
    }
}

/**
 * Canonical `BugReportIncidentSummary` evidence keys.
 *
 * Each value is the exact dictionary key emitted by the iOS incident envelope
 * (`Sources/LavaSecAppServices/BugReportBundle.swift`). The key set is what makes
 * omit-never-approximate inspectable: evidence the platform does not measure is reported as an
 * omitted key rather than a fabricated zero.
 */
enum class IncidentEvidenceKey(val wireKey: String) {
    SELF_RECONNECT("self_reconnect_count"),
    LAST_FAILURE_REASON("last_failure_reason"),
    CONSECUTIVE_UPSTREAM_FAILURE("consecutive_upstream_failure_count"),
    CONSECUTIVE_DNS_SMOKE_PROBE_FAILURE("consecutive_dns_smoke_probe_failure_count"),
    CONSECUTIVE_REJECTED_SMOKE_RESPONSE("consecutive_rejected_smoke_response_count"),
    ENCRYPTED_FALLBACK_SUCCESS("last_encrypted_fallback_success_at"),
    RESOLVER_RUNTIME_RESET("resolver_runtime_reset_count"),
    FAIL_CLOSED_SERVED_QUERY("fail_closed_served_query_count"),
    SELF_RECONNECT_GAP("last_self_reconnect_gap_started_at"),
    FOCUS_SWITCH("focus_last_switch"),
    FOCUS_FAILURE("focus_last_failure"),
    RECENT_LEDGER_INCIDENT("recent_incidents"),
}

/**
 * Actual recovery evidence available to the incident decision.
 *
 * Every field is nullable on purpose. `null` means "this platform did not measure it" and the
 * key is omitted from the decision; a present zero (`0` / `false`) is real evidence that the
 * condition is not occurring. Counts are presence evidence only — the retrieval reason is
 * reduced to a boolean because the decision needs to know the condition happened, not its text.
 */
data class IncidentEvidence(
    val selfReconnectCount: Int? = null,
    val hasLastFailureReason: Boolean? = null,
    val consecutiveUpstreamFailureCount: Int? = null,
    val consecutiveDNSSmokeProbeFailureCount: Int? = null,
    val consecutiveRejectedSmokeResponseCount: Int? = null,
    val hasEncryptedFallbackSuccess: Boolean? = null,
    val resolverRuntimeResetCount: Int? = null,
    val failClosedServedQueryCount: Int? = null,
    val hasRecentSelfReconnectGap: Boolean? = null,
    val hasRecentFocusSwitch: Boolean? = null,
    val hasRecentFocusFailure: Boolean? = null,
    val hasRecentLedgerIncident: Boolean? = null,
) {
    init {
        require(selfReconnectCount == null || selfReconnectCount >= 0)
        require(consecutiveUpstreamFailureCount == null || consecutiveUpstreamFailureCount >= 0)
        require(consecutiveDNSSmokeProbeFailureCount == null || consecutiveDNSSmokeProbeFailureCount >= 0)
        require(consecutiveRejectedSmokeResponseCount == null || consecutiveRejectedSmokeResponseCount >= 0)
        require(resolverRuntimeResetCount == null || resolverRuntimeResetCount >= 0)
        require(failClosedServedQueryCount == null || failClosedServedQueryCount >= 0)
    }

    /** Canonical keys this platform measured, in canonical order. */
    fun presentKeys(): List<IncidentEvidenceKey> =
        IncidentEvidenceKey.entries.filter { isPresent(it) }

    /** Canonical keys this platform did not measure; they are omitted, never defaulted. */
    fun omittedKeys(): List<IncidentEvidenceKey> =
        IncidentEvidenceKey.entries.filterNot { isPresent(it) }

    /**
     * Whether the measured evidence for [key] is real incident content, reproducing the
     * `BugReportIncidentSummary.hasContent` disjuncts. An omitted key contributes nothing.
     */
    fun contributesContent(key: IncidentEvidenceKey): Boolean = when (key) {
        IncidentEvidenceKey.SELF_RECONNECT -> (selfReconnectCount ?: 0) > 0
        IncidentEvidenceKey.LAST_FAILURE_REASON -> hasLastFailureReason == true
        IncidentEvidenceKey.CONSECUTIVE_UPSTREAM_FAILURE -> (consecutiveUpstreamFailureCount ?: 0) > 0
        IncidentEvidenceKey.CONSECUTIVE_DNS_SMOKE_PROBE_FAILURE ->
            (consecutiveDNSSmokeProbeFailureCount ?: 0) > 0
        IncidentEvidenceKey.CONSECUTIVE_REJECTED_SMOKE_RESPONSE ->
            (consecutiveRejectedSmokeResponseCount ?: 0) > 0
        IncidentEvidenceKey.ENCRYPTED_FALLBACK_SUCCESS -> hasEncryptedFallbackSuccess == true
        IncidentEvidenceKey.RESOLVER_RUNTIME_RESET -> (resolverRuntimeResetCount ?: 0) > 0
        IncidentEvidenceKey.FAIL_CLOSED_SERVED_QUERY -> (failClosedServedQueryCount ?: 0) > 0
        IncidentEvidenceKey.SELF_RECONNECT_GAP -> hasRecentSelfReconnectGap == true
        IncidentEvidenceKey.FOCUS_SWITCH -> hasRecentFocusSwitch == true
        IncidentEvidenceKey.FOCUS_FAILURE -> hasRecentFocusFailure == true
        IncidentEvidenceKey.RECENT_LEDGER_INCIDENT -> hasRecentLedgerIncident == true
    }

    private fun isPresent(key: IncidentEvidenceKey): Boolean = when (key) {
        IncidentEvidenceKey.SELF_RECONNECT -> selfReconnectCount != null
        IncidentEvidenceKey.LAST_FAILURE_REASON -> hasLastFailureReason != null
        IncidentEvidenceKey.CONSECUTIVE_UPSTREAM_FAILURE -> consecutiveUpstreamFailureCount != null
        IncidentEvidenceKey.CONSECUTIVE_DNS_SMOKE_PROBE_FAILURE ->
            consecutiveDNSSmokeProbeFailureCount != null
        IncidentEvidenceKey.CONSECUTIVE_REJECTED_SMOKE_RESPONSE ->
            consecutiveRejectedSmokeResponseCount != null
        IncidentEvidenceKey.ENCRYPTED_FALLBACK_SUCCESS -> hasEncryptedFallbackSuccess != null
        IncidentEvidenceKey.RESOLVER_RUNTIME_RESET -> resolverRuntimeResetCount != null
        IncidentEvidenceKey.FAIL_CLOSED_SERVED_QUERY -> failClosedServedQueryCount != null
        IncidentEvidenceKey.SELF_RECONNECT_GAP -> hasRecentSelfReconnectGap != null
        IncidentEvidenceKey.FOCUS_SWITCH -> hasRecentFocusSwitch != null
        IncidentEvidenceKey.FOCUS_FAILURE -> hasRecentFocusFailure != null
        IncidentEvidenceKey.RECENT_LEDGER_INCIDENT -> hasRecentLedgerIncident != null
    }
}

/** Result of the canonical incident decision over the measured [IncidentEvidence]. */
data class IncidentDiagnosticDecision(
    /** Whether any measured evidence is real incident content (`has_incident_summary`). */
    val hasContent: Boolean,
    val presentKeys: List<IncidentEvidenceKey>,
    val omittedKeys: List<IncidentEvidenceKey>,
) {
    /** Canonical preview section id that carries this decision. */
    val copyKey: DiagnosticCopyKey get() = DiagnosticCopyKey.INCIDENT_SUMMARY

    companion object {
        fun from(evidence: IncidentEvidence): IncidentDiagnosticDecision = IncidentDiagnosticDecision(
            hasContent = evidence.presentKeys().any(evidence::contributesContent),
            presentKeys = evidence.presentKeys(),
            omittedKeys = evidence.omittedKeys(),
        )
    }
}

/**
 * The canonical pre-submission diagnostic decision.
 *
 * Carries the affected-site decision, the incident decision, and the resolver/connection
 * category. It holds no history, resolver address, credential, or account identity; the only
 * domain value is the single normalized affected-site domain inside [AffectedSiteDiagnosticDecision.Decided].
 */
data class DiagnosticDecision(
    val affectedSite: AffectedSiteDiagnosticDecision,
    val incident: IncidentDiagnosticDecision,
    /** Canonical resolver/connection category, or `null` when that evidence is absent. */
    val resolverConnectionSeverity: ProtectionHealthSeverity?,
) {
    /** Canonical copy keys present in this decision, in canonical section order. */
    val copyKeys: List<DiagnosticCopyKey>
        get() = buildList {
            if (affectedSite is AffectedSiteDiagnosticDecision.Decided) {
                add(DiagnosticCopyKey.AFFECTED_SITE_FILTER)
            }
            if (incident.hasContent) add(DiagnosticCopyKey.INCIDENT_SUMMARY)
            if (resolverConnectionSeverity != null) add(DiagnosticCopyKey.NETWORK_RESOLVER)
        }

    /** Exact canonical categories: the affected-site reason wire value and the severity name. */
    val canonicalCategories: List<String>
        get() = buildList {
            (affectedSite as? AffectedSiteDiagnosticDecision.Decided)?.let { add(it.category) }
            resolverConnectionSeverity?.let { add(it.name) }
        }
}

/**
 * Pure entry point that builds a [DiagnosticDecision] from actual evidence.
 *
 * [filterDecisions] is the resident rule snapshot used for the queried affected-site domain.
 * [resolverConnection] is the measured resolver/connection evidence assessed by the pinned
 * [ProtectionHealthPolicy]; a `null` value means the platform has no such evidence and the
 * category is omitted rather than assumed healthy.
 */
object DiagnosticDecisionModel {
    fun evaluate(
        affectedSite: String?,
        filterDecisions: DomainFilterDecisionSource?,
        incident: IncidentEvidence = IncidentEvidence(),
        resolverConnection: ProtectionHealthEvidence? = null,
    ): DiagnosticDecision = DiagnosticDecision(
        affectedSite = AffectedSiteDiagnosticDecision.make(affectedSite, filterDecisions),
        incident = IncidentDiagnosticDecision.from(incident),
        resolverConnectionSeverity = resolverConnection
            ?.let { ProtectionHealthPolicy.assess(it).severity },
    )
}

/** Exact canonical `FilterAction.rawValue`. */
internal fun FilterAction.canonicalWireValue(): String = when (this) {
    FilterAction.ALLOW -> "allow"
    FilterAction.BLOCK -> "block"
}

/** Exact canonical `FilterDecisionReason.rawValue`. */
internal fun FilterDecisionReason.canonicalWireValue(): String = when (this) {
    FilterDecisionReason.DEFAULT_ALLOW -> "defaultAllow"
    FilterDecisionReason.PAUSED_ALLOW -> "pausedAllow"
    FilterDecisionReason.LOCAL_ALLOWLIST -> "localAllowlist"
    FilterDecisionReason.BLOCKLIST -> "blocklist"
    FilterDecisionReason.THREAT_GUARDRAIL -> "threatGuardrail"
    FilterDecisionReason.INVALID_DOMAIN -> "invalidDomain"
    FilterDecisionReason.PROTECTION_UNAVAILABLE -> "protectionUnavailable"
}
