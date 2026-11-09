package com.lavasec.app.backup

import com.lavasec.app.foundation.backup.BackupPayloadMapper
import com.lavasec.app.foundation.backup.BackupRecoveryAvailability
import com.lavasec.app.foundation.backup.BackupRecoveryMethod
import com.lavasec.app.foundation.backup.BackupRestoreBasis
import com.lavasec.app.foundation.backup.BackupRestoreCheckpoint
import com.lavasec.app.foundation.backup.BackupRestoreOwner
import com.lavasec.app.foundation.backup.BackupRestorePreparation
import com.lavasec.app.foundation.backup.BackupRestoreAdoption
import com.lavasec.app.foundation.backup.BackupRestorePlan
import com.lavasec.app.foundation.backup.BackupRestorePublisher
import com.lavasec.app.foundation.backup.BackupRestoreRefusal
import com.lavasec.app.foundation.backup.BackupRestoreUnlock
import com.lavasec.app.foundation.backup.BackupRetainedPreferences
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.FilterProfileDraft
import com.lavasec.app.foundation.filter.ManualDomainRules
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the passkey-PRF capability is a real platform decision, not a constant: it requires both a
 * credential provider and a configured relying-party origin, fails closed when either is missing,
 * and gates the restore owner's passkey method in both directions. Plain JVM.
 */
class AndroidBackupPasskeyPrfCapabilityTest {

    private val basis = BackupRestoreBasis(accountID = "account-A", configurationGeneration = 7)

    private val preferences = BackupRetainedPreferences(
        keepFilteringCounts = true,
        keepDomainDiagnostics = false,
        keepNetworkActivity = true,
        keepLavaGuardProgress = true,
        protectionEnabledHint = true,
    )

    private fun library(): FilterLibraryDraft = FilterLibraryDraft(
        profiles = listOf(
            FilterProfileDraft(
                id = "filter-balanced",
                name = "Balanced",
                protectionLevel = null,
                enabledSourceIds = setOf("blocklistproject-basic"),
                manualRules = ManualDomainRules(
                    allowedDomains = emptySet(),
                    blockedDomains = setOf("casino.example"),
                ),
            ),
        ),
        activeProfileId = "filter-balanced",
        resolverPlan = MilestoneResolverPlan.DEVICE_DNS_ONLY,
    )

    private fun probe(provider: Boolean, relyingParty: Boolean) = object : BackupPasskeyPlatformProbe {
        override fun credentialProviderPresent(): Boolean = provider
        override fun relyingPartyConfigured(): Boolean = relyingParty
    }

    private class ProviderFacts : BackupPasskeyProviderFacts {
        var frameworkResult: ((Boolean) -> Unit)? = null
        var googleResult: ((Boolean) -> Unit)? = null
        var frameworkCalls = 0
        var googleCalls = 0
        override fun probeFrameworkProviderService(onResult: (Boolean) -> Unit) {
            frameworkCalls++
            frameworkResult = onResult
        }
        override fun probeGooglePlatformAuthenticator(onResult: (Boolean) -> Unit) {
            googleCalls++
            googleResult = onResult
        }
    }

    @Test
    fun `production admission stays unavailable until an actual asynchronous provider probe returns`() {
        val facts = ProviderFacts()
        val platform = AndroidBackupPasskeyPlatformProbe(facts, "https://backup.example")
        val capability = AndroidBackupPasskeyPrfCapability(platform)
        repeat(3) { assertFalse(capability.isAvailable()) }
        assertEquals(1, facts.frameworkCalls)
        assertEquals(1, facts.googleCalls)
        facts.frameworkResult!!(false)
        facts.googleResult!!(false)
        assertFalse(capability.isAvailable())
        facts.googleResult!!(true)
        assertTrue(capability.isAvailable())
        repeat(3) { assertTrue(capability.isAvailable()) }
        assertEquals(1, facts.frameworkCalls)
        assertEquals(1, facts.googleCalls)
    }

    @Test
    fun `framework provider evidence also permits a ceremony and invalid origins do not start probes`() {
        val facts = ProviderFacts()
        val capability = AndroidBackupPasskeyPrfCapability(
            AndroidBackupPasskeyPlatformProbe(facts, "https://backup.example"),
        )
        facts.frameworkResult!!(true)
        assertTrue(capability.isAvailable())
        for (origin in listOf("", "not a url", "https://backup.example/path", "http://backup.example")) {
            val invalid = ProviderFacts()
            assertFalse(AndroidBackupPasskeyPrfCapability(AndroidBackupPasskeyPlatformProbe(invalid, origin)).isAvailable())
            assertEquals(0, invalid.frameworkCalls)
            assertEquals(0, invalid.googleCalls)
        }
    }

    @Test
    fun `capability requires both a credential provider and a configured relying party`() {
        assertFalse(AndroidBackupPasskeyPrfCapability(probe(provider = false, relyingParty = false)).isAvailable())
        assertFalse(AndroidBackupPasskeyPrfCapability(probe(provider = true, relyingParty = false)).isAvailable())
        assertFalse(AndroidBackupPasskeyPrfCapability(probe(provider = false, relyingParty = true)).isAvailable())
        assertTrue(AndroidBackupPasskeyPrfCapability(probe(provider = true, relyingParty = true)).isAvailable())
    }

    @Test
    fun `a blank or unparseable relying-party origin maps to no relying party`() {
        assertNull(BackupPasskeyRelyingParty.fromOrigin("", "user", "Lava backup"))
        assertNull(BackupPasskeyRelyingParty.fromOrigin("not a url", "user", "Lava backup"))
        assertEquals(
            "backup.example",
            BackupPasskeyRelyingParty.fromOrigin("https://backup.example", "user", "Lava backup")?.rpId,
        )
        assertNull(BackupPasskeyRelyingParty.fromOrigin("https://backup.example/app", "user", "Lava backup"))
    }

    @Test
    fun `a platform probe that reports support promotes the restore owner's passkey method`() {
        val publisher = RecordingPublisher()
        val owner = BackupRestoreOwner(
            publisher,
            AndroidBackupPasskeyPrfCapability(probe(provider = true, relyingParty = true)),
        )

        val passkey = owner.capabilities().single { it.method == BackupRecoveryMethod.PASSKEY }
        assertEquals(BackupRecoveryAvailability.AVAILABLE, passkey.availability)
        assertTrue(owner.selectMethod(BackupRecoveryMethod.PASSKEY).isUsable)

        val current = library()
        val payload = BackupPayloadMapper.toPayload(current, preferences)
        val preparation = owner.prepare(
            unlock = BackupRestoreUnlock.Unlocked(payload),
            method = BackupRecoveryMethod.PASSKEY,
            currentLibrary = current,
            currentPreferences = preferences,
            basis = basis,
        )
        assertTrue(preparation is BackupRestorePreparation.Prepared)
        assertEquals(0, publisher.publishCalls)
    }

    @Test
    fun `a fail-closed capability never offers passkey and refuses it, publishing nothing`() {
        val publisher = RecordingPublisher()
        val owner = BackupRestoreOwner(
            publisher,
            AndroidBackupPasskeyPrfCapability(probe(provider = true, relyingParty = false)),
        )

        val passkey = owner.capabilities().single { it.method == BackupRecoveryMethod.PASSKEY }
        assertEquals(BackupRecoveryAvailability.UNAVAILABLE, passkey.availability)
        assertFalse(owner.selectMethod(BackupRecoveryMethod.PASSKEY).isUsable)

        val current = library()
        val payload = BackupPayloadMapper.toPayload(current, preferences)
        val preparation = owner.prepare(
            unlock = BackupRestoreUnlock.Unlocked(payload),
            method = BackupRecoveryMethod.PASSKEY,
            currentLibrary = current,
            currentPreferences = preferences,
            basis = basis,
        )

        assertEquals(
            BackupRestoreRefusal.PasskeyUnavailable,
            (preparation as BackupRestorePreparation.Refused).refusal,
        )
        assertEquals(0, publisher.publishCalls)
    }

    @Test
    fun `device key is not declared available without a production device-key unlock`() {
        val owner = BackupRestoreOwner(RecordingPublisher())
        val deviceKey = owner.capabilities().single { it.method == BackupRecoveryMethod.DEVICE_KEY }
        assertEquals(BackupRecoveryAvailability.UNAVAILABLE, deviceKey.availability)
        assertFalse(owner.selectMethod(BackupRecoveryMethod.DEVICE_KEY).isUsable)
    }

    private class RecordingPublisher : BackupRestorePublisher {
        var publishCalls = 0

        override fun checkpoint(): BackupRestoreCheckpoint = object : BackupRestoreCheckpoint {}

        override fun publish(plan: BackupRestorePlan, adoption: BackupRestoreAdoption?): Boolean {
            publishCalls++
            return true
        }

        override fun restore(checkpoint: BackupRestoreCheckpoint): Boolean = true
    }
}
