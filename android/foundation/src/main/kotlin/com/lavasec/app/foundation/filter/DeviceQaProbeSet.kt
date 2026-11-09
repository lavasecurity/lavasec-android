package com.lavasec.app.foundation.filter

import com.lavasec.app.foundation.domain.DomainName
import java.util.Collections

/**
 * Frozen QADomainProbeSet identities. This value never grants debug-tool admission: the native
 * configuration authority must explicitly permit it before compilation/publication/runtime use.
 * Ordinary manual rules and the signed catalog remain independent of this global debug overlay.
 */
class DeviceQaProbeSet private constructor(
    val allowedDomain: String,
    val blockedDomain: String,
    val exceptionDomain: String,
    val guardrailDomain: String,
) {
    val allDomains: List<String> = Collections.unmodifiableList(
        listOf(allowedDomain, blockedDomain, exceptionDomain, guardrailDomain),
    )
    // The positive control deliberately receives no synthetic allow rule. An ordinary source
    // can block it, and the operator must then report that failed positive control honestly.
    val blockRules: List<DomainRule> = exactRules(blockedDomain, exceptionDomain, guardrailDomain)
    val allowRules: List<DomainRule> = exactRules(exceptionDomain, guardrailDomain)
    val guardrailRules: List<DomainRule> = exactRules(guardrailDomain)

    fun applyingTo(snapshot: FilterSnapshot): FilterSnapshot = FilterSnapshot(
        blockRules = snapshot.blockRules.union(DomainRuleSet.from(blockRules)),
        allowRules = snapshot.allowRules.union(DomainRuleSet.from(allowRules)),
        nonAllowableThreatRules = snapshot.nonAllowableThreatRules.union(DomainRuleSet.from(guardrailRules)),
    )

    override fun equals(other: Any?): Boolean = other is DeviceQaProbeSet && allDomains == other.allDomains
    override fun hashCode(): Int = allDomains.hashCode()

    companion object {
        const val HOSTED_PAGE_URL = "https://lavasecurity.app/qa/"
        val HOSTED: DeviceQaProbeSet = fromDomains(
            "allowed.qa-probe.lavasecurity.app", "blocked.qa-probe.lavasecurity.app",
            "exception.qa-probe.lavasecurity.app", "guardrail.qa-probe.lavasecurity.app",
        )

        fun fromSuffix(suffix: String): DeviceQaProbeSet {
            val normalized = DomainName.parse(suffix).value
            // Validate each completed hostname, including the prefix's contribution to the
            // 253-byte hostname bound; a valid suffix alone does not prove its probes fit.
            return fromDomains("allowed.$normalized", "blocked.$normalized", "exception.$normalized", "guardrail.$normalized")
        }

        fun fromDomains(allowed: String, blocked: String, exception: String, guardrail: String): DeviceQaProbeSet =
            DeviceQaProbeSet(DomainName.parse(allowed).value, DomainName.parse(blocked).value,
                DomainName.parse(exception).value, DomainName.parse(guardrail).value)

        private fun exactRules(vararg domains: String): List<DomainRule> = Collections.unmodifiableList(
            domains.map { DomainRule.parse(it, matchesSubdomains = false) },
        )
    }
}
