package com.lavasec.app.platform

import com.lavasec.app.foundation.chained.ChainedReadiness
import com.lavasec.app.foundation.chained.ChainedConfigurationIssue
import com.lavasec.app.foundation.chained.ChainedReadinessRefusal
import com.lavasec.app.foundation.chained.ChainedUpstreamConfiguration
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composition root's cheap-gate behaviour: status and activation never touch the credential
 * store's secret half until the whole readiness gate has cleared.
 */
class ChainedCompositionTest {

    private fun configuration(): ChainedUpstreamConfiguration = ChainedUpstreamConfiguration.create(
        endpointHost = "vpn.example.com",
        endpointPort = 51820,
        peerPublicKey = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() }),
        clientAddress = "10.7.0.2",
        allowedIPs = listOf("0.0.0.0/0"),
        persistentKeepaliveSeconds = 0,
        interfaceMTU = null,
        dnsAddresses = listOf("10.7.0.1"),
    )

    private fun stored(): StoredChainedUpstreamConfiguration =
        StoredChainedUpstreamConfiguration(
            configuration = configuration(),
            identity = ChainedUpstreamIdentity(1L, "b".repeat(64)),
            savedAtEpochMillis = null,
        )

    private fun secrets(generation: Long = 1L): ChainedUpstreamSecretMaterial = checkNotNull(
        ChainedUpstreamSecretMaterial.create(generation, ByteArray(32) { 0x2A }, null),
    )

    private class FakeEligibilityStore : ChainedDeviceEligibilityStore {
        override fun read(): ChainedDeviceEligibilityRead = eligibleRead()
        override fun markChainedSessionStarted(nowEpochMillis: Long) = eligibleRead()
        override fun markChainedSessionStoppedCleanly(nowEpochMillis: Long) = eligibleRead()
        override fun recordMemoryKill(nowEpochMillis: Long) = eligibleRead()
        override fun clearMemoryKillBackoff(nowEpochMillis: Long) = eligibleRead()

        private fun eligibleRead(): ChainedDeviceEligibilityRead.Available =
            ChainedDeviceEligibilityRead.Available(
                memory = ChainedDeviceMemoryReading(
                    totalMemoryBytes = 4_000_000_000L,
                    memoryClassMegabytes = 256,
                    isLowRamDevice = false,
                ),
                backoff = ChainedMemoryKillBackoffPolicy.clean,
                apiLevel = 36,
            )
    }

    private class FakePreferenceStore(var enabled: Boolean) : ChainedPreferenceStore {
        override val chainedEnabled: Flow<Boolean> = MutableStateFlow(enabled)
        var fallback = false
        var fallbackReadFailure = false
        override suspend fun loadDnsFallbackEnabled(): Boolean { check(!fallbackReadFailure); return fallback }
        var failRead = false
        override suspend fun loadEnabled(): Boolean {
            check(!failRead)
            return enabled
        }
        override suspend fun setChainedEnabled(enabled: Boolean): Boolean {
            this.enabled = enabled
            return true
        }
    }

    private class FakeConfigurationRepository(
        private val stored: StoredChainedUpstreamConfiguration?,
        private val material: ChainedUpstreamSecretMaterial?,
        private val usableSecrets: Boolean = material != null,
        scriptedSecrets: List<ChainedUpstreamSecretMaterial?> = emptyList(),
        scriptedSecretGenerations: List<Long?> = emptyList(),
        scriptedConfigurations: List<StoredChainedUpstreamConfiguration?> = emptyList(),
    ) : ChainedUpstreamConfigurationRepository {
        var probeIssue: ChainedConfigurationIssue? = null
        var probes = 0
        override suspend fun configurationIssue(): ChainedConfigurationIssue? {
            probes++
            return probeIssue
        }
        var secretsReads = 0
            private set
        var secretGenerationReads = 0
            private set
        var configurationReads = 0
            private set
        private val script = scriptedSecrets.toMutableList()
        private val generationScript = scriptedSecretGenerations.toMutableList()
        private val configurationScript = scriptedConfigurations.toMutableList()

        override val storedConfiguration: Flow<StoredChainedUpstreamConfiguration?> =
            MutableStateFlow(stored)

        override suspend fun loadConfiguration(): StoredChainedUpstreamConfiguration? {
            configurationReads++
            return if (configurationScript.isNotEmpty()) {
                configurationScript.removeAt(0)
            } else {
                stored
            }
        }

        override suspend fun usableSecretsGeneration(): Long? {
            secretGenerationReads++
            return if (generationScript.isNotEmpty()) {
                generationScript.removeAt(0)
            } else if (usableSecrets) {
                material?.generation
            } else {
                null
            }
        }

        override suspend fun loadSecrets(): ChainedUpstreamSecretMaterial? {
            secretsReads++
            return if (script.isNotEmpty()) script.removeAt(0) else material
        }

        override suspend fun save(
            rotation: com.lavasec.app.foundation.chained.ChainedUpstreamRotation,
            expectation: ChainedUpstreamSaveExpectation,
        ): ChainedUpstreamSaveResult = error("save is not part of this composition test")

        override suspend fun deleteSecrets() = Unit

        override suspend fun removeAll() = Unit
    }

    private fun composition(
        hasEntitlement: Boolean = true,
        repository: FakeConfigurationRepository,
        preferences: FakePreferenceStore,
        clientDnsOverTunnelAvailable: Boolean = true,
    ): ChainedComposition = ChainedComposition.production(
        entitlement = ChainedEntitlementPort { hasEntitlement },
        eligibilityStore = FakeEligibilityStore(),
        configurationRepository = repository,
        preferenceStore = preferences,
        clientDnsOverTunnelAvailable = clientDnsOverTunnelAvailable,
    )

    @Test
    fun `disabled rows are metadata only and a coherent active subset supplies the matching key count`() = runBlocking {
        val row = configuration()
        val disabled = stored().copy(configuration = row.enabled(false))
        val repository = FakeConfigurationRepository(disabled, secrets())
        val subject = composition(repository = repository, preferences = FakePreferenceStore(true))
        assertNull(subject.resolveActivation())
        assertEquals(0, repository.secretsReads)
        val entry = ChainedUpstreamConfiguration.create("192.0.2.1", 51820, row.peerPublicKey, "10.1.0.2", listOf("0.0.0.0/0"), 0, 1420, row.dnsAddresses)
        val exit = ChainedUpstreamConfiguration.create("192.0.2.2", 51820, row.peerPublicKey, "10.2.0.2", listOf("0.0.0.0/0"), 0, 1420, row.dnsAddresses)
        val pair = stored().copy(configuration = exit.withEntryHop(entry))
        val torn = secrets()
        assertNull(composition(repository = FakeConfigurationRepository(pair, torn), preferences = FakePreferenceStore(true)).resolveActivation())
        assertArrayEquals(ByteArray(32), torn.privateKeyCopy())
        val material = checkNotNull(ChainedUpstreamSecretMaterial.createRows(1, listOf(ByteArray(32) { 0x11 }, ByteArray(32) { 0x22 }), listOf(null, null)))
        val activated = composition(repository = FakeConfigurationRepository(pair, material), preferences = FakePreferenceStore(true)).resolveActivation()!!
        assertEquals(2, activated.configuration.orderedHops.size)
        assertEquals(2, activated.secrets.rowCount)
        activated.secrets.close()
    }

    @Test
    fun `explicit start keeps ON preference on every setup refusal and probes only when requested`() = runBlocking {
        val repository = FakeConfigurationRepository(stored(), secrets())
        val preferences = FakePreferenceStore(false)
        val subject = composition(repository = repository, preferences = preferences)
        assertNull(subject.configurationIssueForStart())
        assertEquals(0, repository.probes)
        preferences.enabled = true
        for (issue in ChainedConfigurationIssue.entries) {
            repository.probeIssue = issue
            assertEquals(issue, subject.configurationIssueForStart())
            assertTrue(preferences.enabled)
        }
        repository.probeIssue = null
        assertNull(subject.configurationIssueForStart())
        assertTrue(preferences.enabled)
    }

    @Test
    fun `an unreadable saved choice is unavailable rather than implicit OFF`() = runBlocking {
        val repository = FakeConfigurationRepository(stored(), secrets())
        val preferences = FakePreferenceStore(true).apply { failRead = true }
        val subject = composition(repository = repository, preferences = preferences)
        assertEquals(ChainedConfigurationIssue.UNAVAILABLE, subject.configurationIssueForStart())
        assertTrue(preferences.enabled)
        assertEquals(0, repository.probes)
    }

    @Test
    fun `a disabled upstream resolves DNS-only without reading the key half`() = runBlocking {
        val repository = FakeConfigurationRepository(stored(), secrets())
        val underTest = composition(repository = repository, preferences = FakePreferenceStore(false))

        assertNull(underTest.resolveActivation())
        assertEquals(0, repository.secretsReads)

        val status = underTest.status()
        assertFalse(status.ready)
        assertTrue(status.configured)
        assertTrue(status.canEnable)
        assertEquals(0, repository.secretsReads)
    }

    @Test
    fun `an unentitled account resolves DNS-only without reading the key half`() = runBlocking {
        val repository = FakeConfigurationRepository(stored(), secrets())
        val underTest = composition(
            hasEntitlement = false,
            repository = repository,
            preferences = FakePreferenceStore(true),
        )

        assertNull(underTest.resolveActivation())
        assertEquals(0, repository.secretsReads)
        assertFalse(underTest.status().ready)
        assertFalse(underTest.status().canEnable)
    }

    @Test
    fun `an eligible enabled configured upstream resolves and reads the key half once`() =
        runBlocking {
            val repository = FakeConfigurationRepository(stored(), secrets())
            val underTest = composition(
                repository = repository,
                preferences = FakePreferenceStore(true),
            )

            val resolved = underTest.resolveActivation()
            assertNotNull(resolved)
            assertEquals(1, repository.secretsReads)
            assertEquals(stored().identity, resolved!!.identity)

            val status = underTest.status()
            assertTrue(status.ready)
            assertTrue(status.eligible)
            assertTrue(status.enabled)
            assertTrue(status.canEnable)
        }

    @Test
    fun `only split activation captures explicit chain fallback and failed consent reads close keys`() = runBlocking {
        val split = ChainedUpstreamConfiguration.create(
            "192.0.2.1", 51820, Base64.getEncoder().encodeToString(ByteArray(32) { 0x42 }),
            "10.7.0.2", listOf("10.64.0.0/16"), 0, 1420, listOf("10.64.0.1"),
        )
        val splitStored = stored().copy(configuration = split)
        val preferences = FakePreferenceStore(true).apply { fallback = true }
        val material = secrets()
        val resolved = composition(repository = FakeConfigurationRepository(splitStored, material), preferences = preferences).resolveActivation()!!
        assertTrue(resolved.dnsFallbackEnabled)
        preferences.fallback = false
        assertTrue(resolved.dnsFallbackEnabled) // captured; a preference change is a later runtime generation
        resolved.secrets.close()
        val full = composition(repository = FakeConfigurationRepository(stored(), secrets()), preferences = preferences.apply { fallback = true }).resolveActivation()!!
        assertFalse(full.dnsFallbackEnabled)
        full.secrets.close()
        val rejected = secrets()
        preferences.fallbackReadFailure = true
        try {
            composition(repository = FakeConfigurationRepository(splitStored, rejected), preferences = preferences).resolveActivation()
            org.junit.Assert.fail("failed consent read must not return a runnable activation")
        } catch (_: IllegalStateException) { }
        assertTrue(rejected.privateKeyCopy().all { it == 0.toByte() })
    }

    @Test
    fun `a configured upstream whose key half is gone resolves DNS-only`() = runBlocking {
        val repository = FakeConfigurationRepository(stored(), material = null)
        val underTest = composition(repository = repository, preferences = FakePreferenceStore(true))

        assertNull(underTest.resolveActivation())
        // The gate cleared, so the secret half is read once and found absent (the honest
        // "re-enter the key" state); it is not paired into credentials.
        assertEquals(1, repository.secretsReads)
        assertFalse(underTest.status().ready)
        assertFalse(underTest.status().canEnable)
    }

    @Test
    fun `an undecryptable key half is not ready even though ciphertext is present`() = runBlocking {
        // `hasUsableSecrets` is false while `loadSecrets` returns null — exactly the restored-device
        // state a presence-only account check used to misreport as ready.
        val repository = FakeConfigurationRepository(
            stored = stored(),
            material = null,
            usableSecrets = false,
        )
        val underTest = composition(repository = repository, preferences = FakePreferenceStore(true))

        val status = underTest.status()
        assertFalse(status.ready)
        assertFalse(status.canEnable)
        assertFalse(status.secretsAvailable)
        assertNull(underTest.resolveActivation())
    }

    @Test
    fun `a rotation between the configuration and secret reads is retried, not paired`() =
        runBlocking {
            // First secret read is the NEW generation while the configuration read is the OLD one;
            // the composition must re-read instead of installing the mismatched pair.
            val rejected = secrets(generation = 2L)
            val matched = secrets(generation = 1L)
            val repository = FakeConfigurationRepository(
                stored = stored(),
                material = null,
                scriptedSecrets = listOf(rejected, matched),
            )
            val underTest = composition(
                repository = repository,
                preferences = FakePreferenceStore(true),
            )

            val resolved = underTest.resolveActivation()
            assertNotNull(resolved)
            assertEquals(1L, resolved!!.identity.generation)
            assertEquals(1L, resolved.secrets.generation)
            assertEquals(2, repository.secretsReads)
            // The rejected material is zeroized; the transferred one is left for the caller.
            assertArrayEquals(ByteArray(32), rejected.privateKeyCopy())
            assertArrayEquals(ByteArray(32) { 0x2A }, matched.privateKeyCopy())
        }

    @Test
    fun `a persistently mismatched generation pair fails closed to DNS-only`() = runBlocking {
        val rejected = List(3) { secrets(generation = 2L) }
        val repository = FakeConfigurationRepository(
            stored = stored(),
            material = null,
            scriptedSecrets = rejected,
        )
        val underTest = composition(repository = repository, preferences = FakePreferenceStore(true))

        assertNull(underTest.resolveActivation())
        assertEquals(3, repository.secretsReads)
        rejected.forEach { assertArrayEquals(ByteArray(32), it.privateKeyCopy()) }
    }

    @Test
    fun `an otherwise-ready upstream resolves DNS-only when client DNS over the tunnel is unavailable`() =
        runBlocking {
            val repository = FakeConfigurationRepository(stored(), secrets())
            val underTest = composition(
                repository = repository,
                preferences = FakePreferenceStore(true),
                clientDnsOverTunnelAvailable = false,
            )

            assertNull(underTest.resolveActivation())
            val status = underTest.status()
            assertFalse(status.ready)
            assertFalse(status.canEnable)
            assertEquals(
                ChainedReadinessRefusal.CLIENT_DNS_OVER_TUNNEL_UNAVAILABLE,
                (status.readiness as ChainedReadiness.NotReady).reason,
            )
        }

    @Test
    fun `status retries a rotation that raced the configuration and secret generation reads`() =
        runBlocking {
            // The secret check reports the NEW generation while the configuration read is the OLD
            // one; status must re-read the pair rather than evaluate the old configuration against
            // the new key's availability.
            val repository = FakeConfigurationRepository(
                stored = stored(),
                material = null,
                scriptedSecretGenerations = listOf(2L, 1L),
            )
            val underTest = composition(
                repository = repository,
                preferences = FakePreferenceStore(true),
            )

            val status = underTest.status()

            assertTrue(status.ready)
            assertTrue(status.secretsAvailable)
            assertEquals(2, repository.secretGenerationReads)
        }

    @Test
    fun `status fails closed on a persistently mismatched generation pair`() = runBlocking {
        val repository = FakeConfigurationRepository(
            stored = stored(),
            material = null,
            scriptedSecretGenerations = listOf(2L, 2L, 2L),
        )
        val underTest = composition(
            repository = repository,
            preferences = FakePreferenceStore(true),
        )

        val status = underTest.status()

        assertFalse(status.ready)
        assertFalse(status.secretsAvailable)
        assertEquals(3, repository.secretGenerationReads)
    }

    @Test
    fun `status treats a removeAll race as not configured, not a missing key`() = runBlocking {
        // The configuration read returns the old record, then `removeAll()` clears it before the
        // secret-generation check, which therefore reports null. Evaluating the removed
        // configuration with secretsAvailable = false would report "re-enter the key"; status must
        // re-read the configuration, see it is gone, and report "not configured".
        val repository = FakeConfigurationRepository(
            stored = stored(),
            material = null,
            scriptedConfigurations = listOf(stored(), null),
            scriptedSecretGenerations = listOf(null),
        )
        val underTest = composition(
            repository = repository,
            preferences = FakePreferenceStore(true),
        )

        val status = underTest.status()

        assertFalse(status.configured)
        assertFalse(status.ready)
        assertEquals(2, repository.configurationReads)
    }

    @Test
    fun `preference mutations run in submission order so the last action wins`() = runBlocking {
        val serializer = ChainedPreferenceMutationSerializer(this)
        val events = mutableListOf<String>()
        val releaseFirst = CompletableDeferred<Unit>()
        serializer.submit {
            events += "enable:start"
            releaseFirst.await()
            events += "enable:commit"
        }
        // A rapid disable arrives while the enable is still committing. It must not start until the
        // enable finished, so the commit order cannot reverse and leave the durable preference
        // contrary to the last action.
        serializer.submit {
            events += "disable:start"
            events += "disable:commit"
        }
        yield()
        assertEquals(listOf("enable:start"), events)
        releaseFirst.complete(Unit)
        while (events.size < 4) yield()
        assertEquals(
            listOf("enable:start", "enable:commit", "disable:start", "disable:commit"),
            events,
        )
    }

    @Test
    fun `a committed preference reload is retried after a failed dispatch`() = runBlocking {
        val reconciliation = ChainedPreferenceReloadReconciliation()
        val attempts = mutableListOf<Long>()

        // Nothing is dispatched until a commit arms the requirement.
        assertTrue(reconciliation.reconcileOnce(7L) { attempts += it })
        assertTrue(attempts.isEmpty())

        // A fresh commit arms it; a failed dispatch (service teardown / OEM refusal) stays armed so
        // the live runtime does not keep routing a preference the user has changed.
        reconciliation.request()
        assertTrue(reconciliation.isRequired)
        assertFalse(
            reconciliation.reconcileOnce(7L) {
                attempts += it
                error("service start refused")
            },
        )
        assertEquals(listOf(7L), attempts)
        assertTrue("a failed dispatch must stay armed", reconciliation.isRequired)

        // A later path retries against the live generation and discharges on success.
        assertTrue(reconciliation.reconcileOnce(9L) { attempts += it })
        assertEquals(listOf(7L, 9L), attempts)
        assertFalse(reconciliation.isRequired)

        // With no live runtime the requirement is discharged: the next start reads the committed
        // preference, so there is nothing to reload.
        reconciliation.request()
        assertTrue(reconciliation.reconcileOnce(null) { attempts += it })
        assertEquals(listOf(7L, 9L), attempts)
        assertFalse(reconciliation.isRequired)
    }

    @Test
    fun `a commit during a suspended reload is not erased by the older dispatch`() = runBlocking {
        val reconciliation = ChainedPreferenceReloadReconciliation()
        val dispatchStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        reconciliation.request()
        val older = launch {
            reconciliation.reconcileOnce(7L) {
                dispatchStarted.complete(Unit)
                release.await()
            }
        }
        dispatchStarted.await()

        // A new toggle commits and arms a fresh requirement while the older reload is suspended; the
        // older dispatch succeeding must NOT clear the newer requirement (which would leave the
        // durable preference and the UI disagreeing with the installed tunnel).
        reconciliation.request()
        release.complete(Unit)
        older.join()

        assertTrue("the newer requirement must survive", reconciliation.isRequired)
        val attempts = mutableListOf<Long>()
        assertTrue(reconciliation.reconcileOnce(9L) { attempts += it })
        assertEquals(listOf(9L), attempts)
        assertFalse(reconciliation.isRequired)
    }

    @Test
    fun `a detach during resolve closes the secret half and refuses the handoff`() = runBlocking {
        val resolved = ResolvedChainedActivation(
            configuration = configuration(),
            identity = ChainedUpstreamIdentity(1L, "b".repeat(64)),
            secrets = secrets(generation = 1L),
        )

        // The rotation detached the graph while the slow resolve ran: the resolved secret half must
        // be zeroized and the handoff refused.
        val refused = resolveChainedHandoff(
            isDetached = { true },
            resolve = { resolved },
        )
        assertNull(refused)
        assertArrayEquals(ByteArray(32), resolved.secrets.privateKeyCopy())

        // Still attached: the resolved activation is handed through unchanged.
        val attached = ResolvedChainedActivation(
            configuration = configuration(),
            identity = ChainedUpstreamIdentity(1L, "b".repeat(64)),
            secrets = secrets(generation = 1L),
        )
        val handedOff = resolveChainedHandoff(
            isDetached = { false },
            resolve = { attached },
        )
        assertTrue(handedOff === attached)
    }
}
