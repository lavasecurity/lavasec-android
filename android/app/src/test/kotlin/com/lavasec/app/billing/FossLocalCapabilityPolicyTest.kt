package com.lavasec.app.billing

import com.lavasec.app.foundation.subscription.EntitlementReadingConfidence
import com.lavasec.app.foundation.subscription.FeatureLimits
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FossLocalCapabilityPolicyTest {
    @Test fun `local capability authority never reads writes or accepts a commercial entitlement`() = runBlocking {
        val owner = NativePlusEntitlementOwner(object : PlusPlanPersistence {
            override suspend fun load(): Boolean = error("FOSS must not load a paid plan")
            override suspend fun save(hasPlus: Boolean): Unit = error("FOSS must not write a paid plan")
        }, localCapabilities = true)
        assertTrue(owner.initialize())
        val current = owner.state.value
        assertTrue(current.available)
        assertTrue(current.localFeaturesAvailable)
        assertFalse(current.hasPlus)
        assertNull(current.expiresAt)
        assertEquals(FeatureLimits.FOSS, current.limits)
        assertFalse(owner.apply(VerifiedPlusReading(true, confidence = EntitlementReadingConfidence.CONFIRMED)))
        assertSame(current, owner.state.value)
    }

    @Test fun `native FOSS admission edits manual domains beyond the Play Plus quota`() = runBlocking {
        val owner = NativePlusEntitlementOwner(object : PlusPlanPersistence {
            override suspend fun load(): Boolean = error("FOSS must not consult commercial state")
            override suspend fun save(hasPlus: Boolean): Unit = error("FOSS must not write commercial state")
        }, localCapabilities = true)
        assertTrue(owner.initialize())
        val witness = requireNotNull(owner.captureAdmission())
        val profile = com.lavasec.app.foundation.filter.FilterProfileDraft("local", "Local", null, emptySet(),
            com.lavasec.app.foundation.filter.ManualDomainRules(
                allowedDomains = (0 until 1_000).mapTo(linkedSetOf()) { "allow-$it.example" },
                blockedDomains = (0 until 1_000).mapTo(linkedSetOf()) { "block-$it.example" },
            ))
        for (target in com.lavasec.app.foundation.filter.ManualRuleTarget.entries) {
            val result = owner.withAvailableLimits { limits ->
                com.lavasec.app.foundation.filter.ManualRuleEditor.add("next.example", target, profile, limits)
            } as com.lavasec.app.foundation.filter.ManualRuleEditResult.Applied
            assertSame(result.profile, owner.withAdmission(witness) { result.profile })
            assertEquals(1_001, if (target == com.lavasec.app.foundation.filter.ManualRuleTarget.ALLOWED)
                result.profile.manualRules.allowedDomains.size else result.profile.manualRules.blockedDomains.size)
        }
        assertFalse(owner.state.value.hasPlus)
    }

    @Test fun `local features retain the exact admission witness and engine rule safety limit`() = runBlocking {
        val owner = NativePlusEntitlementOwner(object : PlusPlanPersistence {
            override suspend fun load() = false
            override suspend fun save(hasPlus: Boolean) = Unit
        }, localCapabilities = true)
        owner.initialize()
        val witness = requireNotNull(owner.captureAdmission())
        assertEquals("published", owner.withAdmission(witness) { "published" })
        owner.initialize()
        assertSame(witness, owner.captureAdmission())
        assertNull(owner.withAdmission(com.lavasec.app.foundation.subscription.FeatureLimitAdmission(FeatureLimits.FOSS)) { "stale" })
        assertEquals(FeatureLimits.PLUS.maxFilterRules, owner.withAvailableLimits { it.maxFilterRules })
        assertTrue(FeatureLimits.FOSS.hasUnlimitedFilters)
        assertTrue(FeatureLimits.FOSS.allowsCustomDNS)
        assertTrue(FeatureLimits.FOSS.allowsCustomBlocklists)
    }
}
