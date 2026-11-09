package com.lavasec.app.foundation.filter

import com.lavasec.app.foundation.domain.DomainName
import com.lavasec.app.foundation.domain.DomainValidationException
import java.util.Collections

@ConsistentCopyVisibility
data class DomainRule private constructor(
    val domain: String,
    val matchesSubdomains: Boolean,
) {
    companion object {
        fun parse(domain: String, matchesSubdomains: Boolean = true): DomainRule =
            DomainRule(DomainName.parse(domain).value, matchesSubdomains)
    }
}

/** Immutable exact-host and host-plus-subdomain rule collection. */
class DomainRuleSet private constructor(
    exactDomains: Set<String>,
    suffixDomains: Set<String>,
) : DomainRuleLookup {
    private val exactDomains = exactDomains.toSet()
    private val suffixDomains = suffixDomains.toSet()

    val isEmpty: Boolean get() = exactDomains.isEmpty() && suffixDomains.isEmpty()
    override val count: Int get() = exactDomains.size + suffixDomains.size
    val allDomains: Set<String> get() = immutableSet(exactDomains + suffixDomains)
    val exactDomainList: List<String> get() = exactDomains.sorted()
    val suffixDomainList: List<String> get() = suffixDomains.sorted()

    fun adding(rule: DomainRule): DomainRuleSet = if (rule.matchesSubdomains) {
        DomainRuleSet(exactDomains, suffixDomains + rule.domain)
    } else {
        DomainRuleSet(exactDomains + rule.domain, suffixDomains)
    }

    fun union(other: DomainRuleSet): DomainRuleSet =
        DomainRuleSet(exactDomains + other.exactDomains, suffixDomains + other.suffixDomains)

    fun filteringOutRules(matchedBy: DomainRuleSet): DomainRuleSet = DomainRuleSet(
        exactDomains.filterNotTo(linkedSetOf(), matchedBy::containsNormalized),
        suffixDomains.filterNotTo(linkedSetOf(), matchedBy::containsNormalized),
    )

    fun contains(rawDomain: String): Boolean {
        val normalized = try {
            DomainName.parse(rawDomain).value
        } catch (_: DomainValidationException) {
            return false
        }
        return containsNormalized(normalized)
    }

    override fun containsNormalized(normalizedDomain: String): Boolean {
        if (normalizedDomain in exactDomains || normalizedDomain in suffixDomains) return true
        var dotIndex = normalizedDomain.indexOf('.')
        while (dotIndex >= 0) {
            if (normalizedDomain.substring(dotIndex + 1) in suffixDomains) return true
            dotIndex = normalizedDomain.indexOf('.', startIndex = dotIndex + 1)
        }
        return false
    }

    fun effectiveBlockedDomainRuleCount(
        allowRules: DomainRuleSet,
        nonAllowableThreatRules: DomainRuleSet = EMPTY,
    ): Int = maxOf(
        0,
        count - allowRules.protectionReducingRuleCount(this, nonAllowableThreatRules),
    )

    private fun protectionReducingRuleCount(
        blockRules: DomainRuleSet,
        guardrailRules: DomainRuleSet,
    ): Int = exactDomains.count { domain ->
        allowedRuleReducesProtection(domain, false, blockRules, guardrailRules)
    } + suffixDomains.count { domain ->
        allowedRuleReducesProtection(domain, true, blockRules, guardrailRules)
    }

    private fun hasRuleAtOrBelow(parentDomain: String): Boolean =
        exactDomains.any { it == parentDomain || it.endsWith(".$parentDomain") } ||
            suffixDomains.any { it == parentDomain || it.endsWith(".$parentDomain") }

    override fun equals(other: Any?): Boolean =
        other is DomainRuleSet && exactDomains == other.exactDomains && suffixDomains == other.suffixDomains

    override fun hashCode(): Int = 31 * exactDomains.hashCode() + suffixDomains.hashCode()

    companion object {
        val EMPTY = DomainRuleSet(emptySet(), emptySet())

        fun of(vararg rules: DomainRule): DomainRuleSet = from(rules.asIterable())

        fun from(rules: Iterable<DomainRule>): DomainRuleSet {
            var result = EMPTY
            for (rule in rules) result = result.adding(rule)
            return result
        }

        private fun allowedRuleReducesProtection(
            domain: String,
            matchesSubdomains: Boolean,
            blockRules: DomainRuleSet,
            guardrailRules: DomainRuleSet,
        ): Boolean {
            if (guardrailRules.containsNormalized(domain)) return false
            if (matchesSubdomains && guardrailRules.hasRuleAtOrBelow(domain)) return false
            if (blockRules.containsNormalized(domain)) return true
            return matchesSubdomains && blockRules.hasRuleAtOrBelow(domain)
        }

        private fun immutableSet(values: Set<String>): Set<String> =
            Collections.unmodifiableSet(LinkedHashSet(values))
    }
}
