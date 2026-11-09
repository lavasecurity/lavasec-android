package com.lavasec.app.foundation.filter

import com.lavasec.app.foundation.domain.DomainName
import com.lavasec.app.foundation.subscription.FeatureLimits

enum class DomainCheckRuleAction(val wire: String) {
    ALLOWED("allowed"), BLOCKED("blocked"), REMOVE_ALLOWED("removeAllowedException"), REMOVE_BLOCKED("removeBlockedDomain");
}

fun ManualDomainRules.allowedExceptions(domain: String): Set<String> =
    allowedDomains.filterTo(linkedSetOf()) { domain == it || domain.endsWith(".$it") }

/** One atomic draft proposal, matching the shared checker's review semantics. */
object DomainCheckRuleEditor {
    fun apply(domain: String, action: DomainCheckRuleAction, profile: FilterProfileDraft, limits: FeatureLimits): ManualRuleEditResult {
        val normalized = try { DomainName.parse(domain).value } catch (_: Exception) {
            return ManualRuleEditResult.Rejected(profile, ManualRuleRejection.INVALID_DOMAIN)
        }
        val rules = profile.manualRules
        val removed = if (action == DomainCheckRuleAction.BLOCKED || action == DomainCheckRuleAction.REMOVE_ALLOWED)
            rules.allowedExceptions(normalized) else emptySet()
        val allowed = rules.allowedDomains - removed
        val target = if (action == DomainCheckRuleAction.ALLOWED || action == DomainCheckRuleAction.REMOVE_ALLOWED) ManualRuleTarget.ALLOWED else ManualRuleTarget.BLOCKED
        fun applied(next: FilterProfileDraft) = ManualRuleEditResult.Applied(profile, next, normalized, target)
        if (action == DomainCheckRuleAction.REMOVE_ALLOWED) return applied(profile.withManualRules(ManualDomainRules(allowed, rules.blockedDomains)))
        if (action == DomainCheckRuleAction.REMOVE_BLOCKED) return applied(ManualRuleEditor.remove(normalized, target, profile))
        if (action == DomainCheckRuleAction.BLOCKED && (normalized in rules.blockedDomains || removed.isNotEmpty() && rules.blockedDomains.any { normalized.endsWith(".$it") }))
            return applied(profile.withManualRules(ManualDomainRules(allowed, rules.blockedDomains)))
        val stripped = profile.withManualRules(ManualDomainRules(allowed, rules.blockedDomains))
        return when (val result = ManualRuleEditor.add(normalized, target, stripped, limits)) {
            is ManualRuleEditResult.Applied -> applied(result.profile)
            is ManualRuleEditResult.Rejected -> ManualRuleEditResult.Rejected(profile, result.reason)
        }
    }
}
