package com.lavasec.app.backup

import com.lavasec.app.foundation.backup.BackupEnvelopeCrypto
import com.lavasec.app.foundation.backup.BackupEnvelope
import com.lavasec.app.foundation.backup.BackupConfigurationPayload
import com.lavasec.app.foundation.backup.BackupPayloadJson
import com.lavasec.app.foundation.backup.BackupUnlockOutcome
import com.lavasec.app.reactnative.DomainDecision
import com.lavasec.app.reactnative.ProductFilterCommand
import com.lavasec.app.reactnative.commitsDurableConfiguration
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves automatic synchronization coalesces configuration changes through the backup owner and
 * only ever runs when a local envelope exists and the durable preference is on. Plain JVM.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupAutomaticUploadCoordinatorTest {

    private fun envelope() = BackupEnvelopeCrypto.seal(
        payload = BackupPayloadJson.encode(BackupConfigurationPayload(resolverPresetID = "device-dns",
            blockedDomains = setOf("before.example"))).toByteArray(),
        secrets = mapOf("keychain" to "fixture-device-key".toCharArray()),
        createdAtEpochSeconds = 1_000,
        iterations = 8,
    )

    private class Fixture(
        private val scope: kotlinx.coroutines.test.TestScope,
        schedulingScope: CoroutineScope = scope,
    ) {
        val accounts = FakeBackupAccounts()
        val cloud = FakeBackupCloud()
        var lastUploaded: BackupEnvelope? = null
        private val cloudPort = object : BackupCloudClient by cloud {
            override suspend fun upload(expectedGeneration: Long, envelope: BackupEnvelope) {
                cloud.upload(expectedGeneration, envelope)
                lastUploaded = envelope
            }
        }
        val store = FakeBackupLocalStore()
        val preference = FakeAutomaticPreference(initial = true)
        /** Live configuration write generation; a change supersedes a pending attempt. */
        var configurationGeneration = 7L
        var deletionBlocks = false
        val sampledGenerations = mutableListOf<Long>()
        val owner = BackupOwner(
            cloud = { cloudPort },
            accounts = accounts,
            store = store,
            deletionStore = FakeBackupDeletionStore(),
            automaticPreference = preference,
            payloadSource = BackupPayloadSource {
                BackupConfigurationPayload(resolverPresetID = "device-dns",
                    blockedDomains = setOf("generation-$configurationGeneration.example"))
            },
            sealingKeys = InMemoryBackupSealingKeyStore("fixture-device-key".toCharArray()),
            nowEpochSeconds = { 5_000L },
            // The re-seal runs off the caller's dispatcher; use the virtual scheduler so the
            // debounced upload is deterministic under advanceUntilIdle.
            ioDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher(scope.testScheduler),
        )
        val coordinator = BackupAutomaticUploadCoordinator(
            owner = owner,
            accounts = accounts,
            store = store,
            automaticPreference = preference,
            deletionFenceBlocks = { deletionBlocks },
            scope = schedulingScope,
            currentConfigurationGeneration = { configurationGeneration.also { sampledGenerations += it } },
            debounceMillis = 100L,
        )
    }

    private fun assertUploadedGeneration(fixture: Fixture, generation: Long) {
        val key = "fixture-device-key".toCharArray()
        try {
            val envelope = checkNotNull(fixture.lastUploaded)
            assertEquals(envelope, fixture.store.envelope)
            val opened = BackupEnvelopeCrypto.unlockOutcome(envelope, "keychain", key) as BackupUnlockOutcome.Unlocked
            try {
                assertEquals(setOf("generation-$generation.example"),
                    BackupPayloadJson.decode(opened.payload.toString(Charsets.UTF_8)).blockedDomains)
            } finally { opened.payload.fill(0) }
        } finally { key.fill('\u0000') }
    }

    @Test
    fun `a configuration change schedules one upload when configured and enabled`() = runTest {
        val fixture = Fixture(this)
        fixture.store.envelope = envelope()

        fixture.coordinator.onConfigurationChanged()
        advanceUntilIdle()

        assertEquals(1, fixture.cloud.uploadCalls)
        assertUploadedGeneration(fixture, 7L)
    }

    @Test
    fun `a rapid burst of changes coalesces into the newest upload`() = runTest {
        val fixture = Fixture(this)
        fixture.store.envelope = envelope()

        fixture.coordinator.onConfigurationChanged()
        fixture.configurationGeneration = 8
        fixture.coordinator.onConfigurationChanged()
        fixture.configurationGeneration = 9
        fixture.coordinator.onConfigurationChanged()
        advanceUntilIdle()

        assertEquals("only the newest scheduled attempt runs", 1, fixture.cloud.uploadCalls)
        assertUploadedGeneration(fixture, 9L)
    }

    /** Queues real launch callbacks while delegating all delays to the same virtual scheduler. */
    @OptIn(InternalCoroutinesApi::class)
    private class HeldCallbacks(private val delegate: TestDispatcher) : CoroutineDispatcher(), Delay by delegate {
        private val held = mutableListOf<Pair<CoroutineContext, Runnable>>()
        private var holding = true
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (holding) held += context to block else delegate.dispatch(context, block)
        }
        fun releaseAt(index: Int) {
            val (context, block) = held.removeAt(index)
            delegate.dispatch(context, block)
        }
        fun releaseAll() {
            holding = false
            val remaining = held.toList()
            held.clear()
            remaining.forEach { (context, block) -> delegate.dispatch(context, block) }
        }
    }

    @Test
    fun `reversed IO callbacks retain the newest live key and one full debounced upload`() = runTest {
        val callbacks = HeldCallbacks(StandardTestDispatcher(testScheduler))
        val fixture = Fixture(this, CoroutineScope(coroutineContext + callbacks))
        fixture.store.envelope = envelope()
        fixture.store.envelopeAccountID = TEST_ACCOUNT

        fixture.coordinator.onConfigurationChanged() // Old callback is queued at generation 7.
        fixture.configurationGeneration = 8L
        fixture.coordinator.onConfigurationChanged()
        callbacks.releaseAt(1) // The newer IO callback reaches the coordinator first.
        runCurrent()
        callbacks.releaseAt(0) // The older callback must resample 8 rather than overwrite it with 7.
        runCurrent()
        callbacks.releaseAll()
        runCurrent()
        advanceTimeBy(99L)
        runCurrent()
        assertEquals("the unchanged 100ms debounce has not elapsed", 0, fixture.cloud.uploadCalls)
        advanceTimeBy(1L)
        runCurrent()

        assertEquals("an older callback must not cancel and lose the newest upload", 1, fixture.cloud.uploadCalls)
        assertUploadedGeneration(fixture, 8L)
        assertEquals(100L, testScheduler.currentTime)
        assertTrue(fixture.sampledGenerations.isNotEmpty())
        assertTrue("all serialized schedule/admission edges sample the live generation",
            fixture.sampledGenerations.all { it == 8L })
        assertEquals(5_000L, fixture.store.uploadedAt)
    }

    @Test
    fun `delayed callbacks respect current automatic deletion and account guards`() = runTest {
        for (suppression in listOf("automatic-off", "deletion", "account-switch")) {
            val callbacks = HeldCallbacks(StandardTestDispatcher(testScheduler))
            val fixture = Fixture(this, CoroutineScope(coroutineContext + callbacks))
            fixture.store.envelope = envelope()
            fixture.store.envelopeAccountID = TEST_ACCOUNT
            fixture.coordinator.onConfigurationChanged()
            fixture.configurationGeneration = 8L
            when (suppression) {
                "automatic-off" -> assertTrue(fixture.owner.setAutomaticEnabled(false))
                "deletion" -> fixture.deletionBlocks = true
                "account-switch" -> fixture.accounts.switchTo("account-B")
            }
            callbacks.releaseAll()
            advanceUntilIdle()

            assertEquals(suppression, 0, fixture.cloud.uploadCalls)
            assertEquals(suppression, null, fixture.store.uploadedAt)
            assertTrue(suppression, fixture.sampledGenerations.isNotEmpty())
            assertTrue(suppression, fixture.sampledGenerations.all { it == 8L })
        }
    }

    @Test
    fun `automatic disabled suppresses the upload`() = runTest {
        val fixture = Fixture(this)
        fixture.store.envelope = envelope()
        fixture.preference.saveAutomaticEnabled(false)

        fixture.coordinator.onConfigurationChanged()
        advanceUntilIdle()

        assertEquals(0, fixture.cloud.uploadCalls)
    }

    @Test
    fun `an unconfigured device schedules nothing`() = runTest {
        val fixture = Fixture(this)
        fixture.store.envelope = null

        fixture.coordinator.onConfigurationChanged()
        advanceUntilIdle()

        assertEquals(0, fixture.cloud.uploadCalls)
    }

    @Test
    fun `a configuration change before the debounce elapses supersedes the scheduled attempt`() = runTest {
        val fixture = Fixture(this)
        fixture.store.envelope = envelope()

        fixture.coordinator.onConfigurationChanged()
        runCurrent()
        fixture.configurationGeneration = 8
        advanceUntilIdle()

        assertEquals(0, fixture.cloud.uploadCalls)
    }

    @Test
    fun `a committed mutation arms one debounced upload while a draft stage does not`() = runTest {
        val fixture = Fixture(this)
        fixture.store.envelope = envelope()

        // A draft-only domain stage never commits, so it must not arm; the applying commit does.
        val draft = ProductFilterCommand.Domain("filter", "example.com", DomainDecision.BLOCKED, remove = false)
        val commit = ProductFilterCommand.Apply("filter", "review-token")
        if (draft.commitsDurableConfiguration()) fixture.coordinator.onConfigurationChanged()
        if (commit.commitsDurableConfiguration()) fixture.coordinator.onConfigurationChanged()
        advanceUntilIdle()

        assertEquals(1, fixture.cloud.uploadCalls)
        assertUploadedGeneration(fixture, 7L)
    }

    @Test
    fun `a credential rotation before the debounce suppresses the armed upload`() = runTest {
        val fixture = Fixture(this)
        fixture.store.envelope = envelope()

        fixture.coordinator.onConfigurationChanged()
        runCurrent()
        // The credential graph rotates; the pending attempt's configuration generation is now stale.
        fixture.configurationGeneration = 99L
        advanceUntilIdle()

        assertEquals(0, fixture.cloud.uploadCalls)
    }
}
