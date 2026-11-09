package com.lavasec.app.reactnative

import com.lavasec.app.R
import com.lavasec.app.foundation.chained.ChainedConfigurationIssue
import com.lavasec.app.foundation.chained.ChainedUpstreamConfiguration
import com.lavasec.app.platform.ChainedUpstreamIdentity
import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.platform.ProtectionState
import com.lavasec.app.platform.StartOrigin
import com.lavasec.app.platform.StoredChainedUpstreamConfiguration
import com.lavasec.app.platform.VpnOwnership
import com.lavasec.app.vpn.chained.ChainedActivitySignal
import com.lavasec.app.vpn.chained.ChainedRuntimeMode
import java.io.File
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Metadata and actual installed-runtime publications, without a second state owner or store. */
@OptIn(ExperimentalCoroutinesApi::class)
class VpnRotationProjectionTest {
    private val protectedState = ProtectionState.Protected(ConfigurationIdentity(5L, "c".repeat(64)), VpnOwnership.User)

    private fun stored(generation: Long, pending: String? = null) = StoredChainedUpstreamConfiguration(
        configuration = ChainedUpstreamConfiguration.create(
            endpointHost = "vpn.example.com", endpointPort = 51820,
            peerPublicKey = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() }),
            clientAddress = "10.7.0.2", allowedIPs = listOf("0.0.0.0/0"),
            persistentKeepaliveSeconds = 0, interfaceMTU = null, dnsAddresses = listOf("10.7.0.1"),
        ),
        identity = ChainedUpstreamIdentity(generation, "b".repeat(64)),
        savedAtEpochMillis = null,
        pendingImportId = pending,
    )

    private fun runtime(
        generation: Long = 7L,
        active: Boolean = true,
        mode: ChainedRuntimeMode = ChainedRuntimeMode.CHAINED,
        state: ProtectionState = protectedState,
    ) = VpnRuntimeRotationWitness(ChainedActivitySignal(active, mode, 1L, generation), state)

    @Test fun `readable metadata wins over key failures and generation inequality does not mean newer`() {
        for (issue in listOf(null, ChainedConfigurationIssue.MISSING_PRIVATE_KEY, ChainedConfigurationIssue.UNAVAILABLE)) {
            assertEquals(R.string.vpn_rotation_awaiting_restart, vpnRotationNoteResource(runtime(), stored(8L), issue))
            assertEquals(R.string.vpn_rotation_awaiting_restart, vpnRotationNoteResource(runtime(), stored(6L), issue))
            assertNull(vpnRotationNoteResource(runtime(), stored(7L), issue))
        }
    }

    @Test fun `only confirmed absence describes removal and an unaccepted first import cannot prompt adoption`() {
        assertEquals(R.string.vpn_rotation_running_removed,
            vpnRotationNoteResource(runtime(), null, ChainedConfigurationIssue.MISSING_CONFIGURATION))
        for (issue in listOf(null, ChainedConfigurationIssue.UNAVAILABLE, ChainedConfigurationIssue.MISSING_PRIVATE_KEY)) {
            assertNull(vpnRotationNoteResource(runtime(), null, issue))
        }
        assertNull(vpnRotationNoteResource(runtime(), stored(8L, pending = "unaccepted-editor"), null))
    }

    @Test fun `only actual service replacement suppresses an actionable difference and absent runtime is silent`() {
        for (state in listOf(ProtectionState.Starting(StartOrigin.Reload, 11L),
            ProtectionState.Reconnecting(StartOrigin.Reload, 11L))) {
            assertNull(vpnRotationNoteResource(runtime(state = state), stored(8L), null))
            assertNull(vpnRotationNoteResource(runtime(state = state), null, ChainedConfigurationIssue.MISSING_CONFIGURATION))
        }
        // Network waiting and a retained paused runtime are not proof of configuration adoption.
        for (state in listOf(ProtectionState.AwaitingNetwork(StartOrigin.Reload, 11L), ProtectionState.Paused(1000L))) {
            assertEquals(R.string.vpn_rotation_awaiting_restart,
                vpnRotationNoteResource(runtime(state = state), stored(8L), null))
        }
        for (signal in listOf(runtime(generation = 0L), runtime(active = false),
            runtime(mode = ChainedRuntimeMode.DNS_ONLY), runtime(mode = ChainedRuntimeMode.NONE))) {
            assertNull(vpnRotationNoteResource(signal, stored(8L), null))
        }
    }

    @Test fun `an open family observes successful and failed replacement without another durable write`() = runTest {
        val saved = MutableStateFlow<StoredChainedUpstreamConfiguration?>(stored(8L))
        val enabled = MutableStateFlow(true)
        val fallback = MutableStateFlow(false)
        val activity = MutableStateFlow(runtime().activity)
        val state = MutableStateFlow<ProtectionState>(protectedState)
        val notes = mutableListOf<Int?>()
        backgroundScope.launch {
            vpnFamilyUpdates(saved, enabled, fallback, activity, state).collect {
                notes += vpnRotationNoteResource(it, saved.value, ChainedConfigurationIssue.MISSING_CONFIGURATION)
            }
        }
        runCurrent()
        assertEquals(listOf(R.string.vpn_rotation_awaiting_restart), notes)
        // Equal runtime witnesses still invalidate on stored/preference changes.
        saved.value = stored(9L)
        runCurrent()
        enabled.value = false
        runCurrent()
        fallback.value = true
        runCurrent()
        assertEquals(4, notes.size)
        assertTrue(notes.all { it == R.string.vpn_rotation_awaiting_restart })
        state.value = ProtectionState.Reconnecting(StartOrigin.Reload, 11L)
        runCurrent()
        assertNull(notes.last())
        // The attempted replacement failed; the old owner remains installed and the note returns.
        state.value = protectedState
        runCurrent()
        assertEquals(R.string.vpn_rotation_awaiting_restart, notes.last())
        activity.value = activity.value.copy(sequence = 2L, runningConfigurationGeneration = 9L)
        runCurrent()
        assertNull(notes.last())
        assertEquals(9L, saved.value?.identity?.generation)
        saved.value = null
        runCurrent()
        assertEquals(R.string.vpn_rotation_running_removed, notes.last())
        activity.value = activity.value.copy(active = false, mode = ChainedRuntimeMode.DNS_ONLY,
            sequence = 3L, runningConfigurationGeneration = 0L)
        runCurrent()
        assertNull(notes.last())
    }

    @Test fun `both actionable notes have localized resources and removed copy never tells the user to restart`() {
        val directories = listOf("values", "values-de", "values-es", "values-fr", "values-it", "values-ja",
            "values-ko", "values-pt-rBR", "values-zh-rCN", "values-zh-rTW")
        val translations = directories.map { directory ->
            val file = File("src/main/res/$directory/vpn_rotation_strings.xml")
            assertTrue("Missing rotation copy for $directory", file.isFile)
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
            (0 until nodes.length).associate { index ->
                val node = nodes.item(index)
                node.attributes.getNamedItem("name").nodeValue to node.textContent
            }.also {
                assertEquals(setOf("vpn_rotation_awaiting_restart", "vpn_rotation_running_removed"), it.keys)
                assertTrue(it.values.all { copy -> copy.length > 30 })
            }
        }
        assertEquals(translations.size, translations.toSet().size)
        val english = translations.first()
        assertEquals("Your VPN connection is still running the configuration it started with. Turn protection off and on to switch to the one you just saved.",
            english["vpn_rotation_awaiting_restart"])
        assertEquals("Your VPN connection is still running the configuration you removed, and will until protection stops. Turn protection off, or import a configuration to replace it.",
            english["vpn_rotation_running_removed"])
    }

    @Test fun `an entitlement change re-emits so the vpn family can rebuild needsPlus`() = runTest {
        val saved = MutableStateFlow<StoredChainedUpstreamConfiguration?>(stored(8L))
        val enabled = MutableStateFlow(true)
        val fallback = MutableStateFlow(false)
        val activity = MutableStateFlow(runtime().activity)
        val state = MutableStateFlow<ProtectionState>(protectedState)
        val entitlement = MutableStateFlow(0)
        var emissions = 0
        backgroundScope.launch {
            vpnFamilyUpdates(saved, enabled, fallback, activity, state, entitlement).collect { emissions++ }
        }
        runCurrent()
        val before = emissions
        entitlement.value = 1
        runCurrent()
        assertTrue("an entitlement change must rebuild the family", emissions > before)
    }

    @Test fun `a suspended retired composition cannot publish into its successor even if the read finishes`() = runTest {
        val a = Any()
        val b = Any()
        val compositions = MutableStateFlow<Any?>(a)
        val updates = MutableStateFlow(1)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val oldReadFinished = CompletableDeferred<Unit>()
        val publications = mutableListOf<NativeVpnFamilyProjection<Any, String>?>()
        backgroundScope.launch {
            observeNativeVpnFamily(compositions, { compositions.value }, { updates }, { owner, _ ->
                if (owner === a) {
                    entered.complete(Unit)
                    // Model a real blocking IO read which completes after retirement.
                    withContext(NonCancellable) { release.await(); oldReadFinished.complete(Unit) }
                    "retired"
                } else "successor"
            }, { publications += it })
        }
        entered.await()
        try {
            compositions.value = b
            runCurrent()
            assertTrue(publications.isEmpty())
        } finally {
            // Failure must not strand the simulated blocking IO child.
            release.complete(Unit)
        }
        runCurrent()
        assertTrue("the original IO read really finished after retirement", oldReadFinished.isCompleted)
        assertEquals(1, publications.size)
        val accepted = requireNotNull(publications.single())
        assertTrue(accepted.composition === b)
        assertEquals("successor", accepted.valueFor(b))
        assertNull("cached successor metadata cannot be borrowed by predecessor", accepted.valueFor(a))
    }

    @Test fun `a source invalidation cancels older same composition metadata before newer publication`() = runTest {
        val owner = Any()
        val compositions = MutableStateFlow<Any?>(owner)
        val updates = MutableStateFlow(1)
        val entered = CompletableDeferred<Unit>()
        val staleReadCancelled = CompletableDeferred<Unit>()
        val publications = mutableListOf<Int>()
        backgroundScope.launch {
            observeNativeVpnFamily(compositions, { compositions.value }, { updates }, { _, version ->
                if (version == 1) {
                    entered.complete(Unit)
                    try { CompletableDeferred<Unit>().await() }
                    catch (failure: CancellationException) { staleReadCancelled.complete(Unit); throw failure }
                }
                version
            }, { it?.let { accepted -> publications += accepted.value } })
        }
        entered.await()
        updates.value = 2
        runCurrent()
        assertTrue(staleReadCancelled.isCompleted)
        assertEquals(listOf(2), publications)
        updates.value = 3
        runCurrent()
        assertEquals(listOf(2, 3), publications)
    }

    @Test fun `a final live owner refusal drops old metadata and a removed composition clears its cache`() = runTest {
        val owner = Any()
        val successor = Any()
        val compositions = MutableStateFlow<Any?>(owner)
        val updates = MutableStateFlow(1)
        // The atomic current slot may retire before its observer processes the flow change.
        var live: Any? = owner
        val publications = mutableListOf<NativeVpnFamilyProjection<Any, Int>?>()
        backgroundScope.launch {
            observeNativeVpnFamily(compositions, { live }, { updates }, { _, version ->
                live = successor
                version
            }, { publications += it })
        }
        runCurrent()
        assertTrue(publications.isEmpty())
        val oldCache = NativeVpnFamilyProjection(owner, 1)
        assertNull(oldCache.valueFor(live))
        live = null
        compositions.value = null
        runCurrent()
        assertEquals(listOf<NativeVpnFamilyProjection<Any, Int>?>(null), publications)
    }
}
