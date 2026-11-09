package com.lavasec.app.reactnative

import com.lavasec.app.foundation.filter.*
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.platform.*
import com.lavasec.app.product.LocalDomainCheckEvidence
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.*
import org.junit.Test

class NativeDomainCheckOwnerTest {
    @Test fun `native proof admits only its exact visit window generation and compiled rules`() = withEvidence { evidence ->
        val owner = NativeDomainCheckOwner()
        val ticket = owner.begin("visit", "window", 7)
        val grant = checkNotNull(owner.accept(ticket, evidence))
        assertSame(grant, owner.checked("visit", grant.proof, "window", 7, evidence.revision))
        assertNull(owner.checked("other", grant.proof, "window", 7, evidence.revision))
        assertNull(owner.checked("visit", "invented", "window", 7, evidence.revision))
        assertNull(owner.checked("visit", grant.proof, "replacement-window", 7, evidence.revision))
        assertNull(owner.checked("visit", grant.proof, "window", 8, evidence.revision))
        assertNull(owner.checked("visit", grant.proof, "window", 7, "changed-rules"))
        assertFalse(owner.isCurrent(grant, "changed-rules"))
    }

    @Test fun `retired and replaced queries cannot publish or cancel a newer result`() = withEvidence { evidence ->
        val owner = NativeDomainCheckOwner()
        val old = owner.begin("old", "window", 7)
        val next = owner.begin("next", "window", 7)
        owner.cancel("old", "window")
        assertNull(owner.accept(old, evidence))
        val grant = checkNotNull(owner.accept(next, evidence))
        owner.cancel("next", "foreign-window")
        assertTrue(owner.isCurrent(grant, evidence.revision))
        owner.cancel("next", "window")
        assertNull(owner.checked("next", grant.proof, "window", 7, evidence.revision))
        assertNull(owner.accept(next, evidence))
        val resumed = owner.begin("next", "window", 7)
        val fresh = checkNotNull(owner.accept(resumed, evidence))
        assertNotEquals(grant.proof, fresh.proof)
        owner.retire()
        assertFalse(owner.isCurrent(fresh, evidence.revision))
    }

    @Test fun `checker evidence uses real compiled suffix allow and threat precedence`() = withEvidence { evidence ->
        assertEquals(FilterAction.BLOCK, evidence.decision.action)
        assertEquals(FilterDecisionReason.BLOCKLIST, evidence.decision.reason)
        assertEquals("shop.blocked.example", evidence.domain)
        assertEquals("Active", evidence.confirmed.configuration.profile("active")!!.name)
    }

    private fun withEvidence(test: (LocalDomainCheckEvidence) -> Unit) = kotlinx.coroutines.runBlocking {
        val root = createTempDirectory("native-domain-check").toFile()
        try {
            val profile = FilterProfileDraft("active", "Active", null, emptySet(),
                ManualDomainRules(setOf("allowed.blocked.example"), setOf("blocked.example")))
            val library = FilterLibraryDraft(listOf(profile), "active", MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK)
            val hash = "a".repeat(64)
            val identity = ArtifactConfigurationIdentity(1, canonicalFilterConfigurationFingerprint(library, "fixture", hash))
            FilterCompiler(File(root, "compile")).compile(FilterCompileRequest(identity, emptyList(), "fixture", hash, 100,
                manualAllowRules = listOf(DomainRule.parse("allowed.blocked.example", matchesSubdomains = true)),
                manualBlockRules = listOf(DomainRule.parse("blocked.example", matchesSubdomains = true)),
                guardrailRules = listOf(DomainRule.parse("danger.allowed.blocked.example", matchesSubdomains = true)),
            )).use { prepared ->
                (CredentialFilterArtifactStagingVerifier.verify(prepared.file, prepared.compilationReceipt)
                    as CredentialFilterArtifactStagingResult.Verified).use { verified ->
                    val state = ConfirmedFilterState(library, ConfigurationIdentity(1, identity.fingerprint), true, verified.verifiedArtifact)
                    FilterArtifact.open(prepared.file, prepared.sha256).use { artifact ->
                        assertEquals(FilterDecisionReason.LOCAL_ALLOWLIST, artifact.snapshot.decide("allowed.blocked.example").reason)
                        assertEquals(FilterDecisionReason.THREAT_GUARDRAIL, artifact.snapshot.decide("danger.allowed.blocked.example").reason)
                        test(LocalDomainCheckEvidence("shop.blocked.example", artifact.snapshot.decide("shop.blocked.example"), state))
                    }
                }
            }
        } finally { root.deleteRecursively() }
    }
}
