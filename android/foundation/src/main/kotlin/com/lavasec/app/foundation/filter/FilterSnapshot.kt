package com.lavasec.app.foundation.filter

import com.lavasec.app.foundation.domain.DomainName
import com.lavasec.app.foundation.domain.DomainValidationException

enum class FilterAction { ALLOW, BLOCK }

enum class FilterDecisionReason {
    DEFAULT_ALLOW,
    PAUSED_ALLOW,
    LOCAL_ALLOWLIST,
    BLOCKLIST,
    THREAT_GUARDRAIL,
    INVALID_DOMAIN,
    PROTECTION_UNAVAILABLE,
}

data class FilterDecision(val action: FilterAction, val reason: FilterDecisionReason) {
    companion object {
        val DEFAULT_ALLOW = FilterDecision(FilterAction.ALLOW, FilterDecisionReason.DEFAULT_ALLOW)
    }
}

/** Immutable decision snapshot; precedence matches the pinned iOS filter boundary. */
class FilterSnapshot(
    val blockRules: DomainRuleSet,
    val allowRules: DomainRuleSet = DomainRuleSet.EMPTY,
    val nonAllowableThreatRules: DomainRuleSet = DomainRuleSet.EMPTY,
) : DomainFilterDecisionSource {
    override val blockRuleCount: Int get() = blockRules.count
    override val allowRuleCount: Int get() = allowRules.count
    override val guardrailRuleCount: Int get() = nonAllowableThreatRules.count

    override fun decide(rawDomain: String): FilterDecision {
        val normalized = try {
            DomainName.parse(rawDomain).value
        } catch (_: DomainValidationException) {
            return FilterDecision(FilterAction.BLOCK, FilterDecisionReason.INVALID_DOMAIN)
        }
        return decideNormalized(normalized)
    }

    override fun decideNormalized(normalizedDomain: String): FilterDecision = when {
        nonAllowableThreatRules.containsNormalized(normalizedDomain) ->
            FilterDecision(FilterAction.BLOCK, FilterDecisionReason.THREAT_GUARDRAIL)
        allowRules.containsNormalized(normalizedDomain) ->
            FilterDecision(FilterAction.ALLOW, FilterDecisionReason.LOCAL_ALLOWLIST)
        blockRules.containsNormalized(normalizedDomain) ->
            FilterDecision(FilterAction.BLOCK, FilterDecisionReason.BLOCKLIST)
        else -> FilterDecision.DEFAULT_ALLOW
    }
}
