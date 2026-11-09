package com.lavasec.app.reactnative.components

import com.lavasec.app.reactnative.LavaTextFieldBufferPolicy
import android.app.Application
import com.lavasec.app.foundation.filter.FilterCatalogSnapshot
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.platform.LoggingSettings
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NativeQaPresentationTest {
    private class Port : DeviceQaOwnerPort {
        var authority = DeviceQaAuthority(7, 3, 11, "account-native-only")
        var witness = DeviceQaConfigurationWitness("native-dns-context", FilterLibraryDraft.seededDefaults(FilterCatalogSnapshot(emptyList())), LoggingSettings())
        var calls = 0
        override fun authority() = authority
        override fun configurationWitness() = witness
        override suspend fun perform(action: DeviceQaAction, visit: DeviceQaVisit, witness: DeviceQaConfigurationWitness?, isCurrent: () -> Boolean): DeviceQaResult {
            calls++; return DeviceQaResult(if (isCurrent()) DeviceQaOutcome.APPLIED else DeviceQaOutcome.REFUSED)
        }
    }
    private fun state(ui: NativeQaPresentation) = checkNotNull(ui.snapshot()).getJSONObject("state")
    private fun command(ui: NativeQaPresentation, action: String, row: String? = null, value: Any? = null): String {
        val state = state(ui)
        return JSONObject().put("id", state.getString("id")).put("action", action).apply {
            state.optJSONObject("detail")?.let { put("detail", it.getString("id")) }
            row?.let { put("row", it) }; value?.let { put("value", it) }
        }.toString()
    }
    private fun ready(ui: NativeQaPresentation) { ui.refresh(); assertTrue(ui.layout(state(ui).getString("id"), 360.0, 720.0)) }

    @Test fun `fresh measured admission is required and old visits cannot drive a resumed presentation`() = runTest {
        val port = Port(); val owner = DeviceQaPageOwner(port, backgroundScope)
        val ui = NativeQaPresentation(RuntimeEnvironment.getApplication(), owner, { true }, { true }, { true })
        owner.activate(DeviceQaVisit(1, 1, port.authority)); ui.refresh()
        val old = command(ui, "row", "hosted-probes")
        assertFalse(ui.send(old)); assertNull(owner.state.value.pending)
        ready(ui); assertTrue(ui.send(old)); assertNotNull(owner.state.value.pending)
        owner.activate(null); ui.refresh(); assertNull(ui.snapshot()); assertFalse(ui.send(old))
        owner.activate(DeviceQaVisit(1, 2, port.authority)); ui.refresh()
        val next = state(ui).getString("id")
        assertFalse(ui.layout(JSONObject(old).getString("id"), 360.0, 720.0))
        assertFalse(ui.layout(next, Double.NaN, 720.0)); assertTrue(ui.layout(next, 360.0, 720.0))
        assertFalse(ui.send(old)); assertNull(owner.state.value.pending)
        assertTrue(ui.send(command(ui, "row", "hosted-probes")))
        ui.close(); assertNull(ui.snapshot()); owner.close()
    }
    @Test fun `guided result requires native confirmation current preparation and a reported positive control`() = runTest {
        val port = Port(); val owner = DeviceQaPageOwner(port, backgroundScope)
        val ui = NativeQaPresentation(RuntimeEnvironment.getApplication(), owner, { true }, { true }, { true })
        owner.activate(DeviceQaVisit(1, 1, port.authority)); ready(ui)
        assertTrue(ui.send(command(ui, "row", "cellularHandover")))
        val oldEvidence = command(ui, "evidence", value = "queued before preparation")
        assertFalse(ui.send(command(ui, "verified", value = true)))
        assertFalse(ui.send(command(ui, "record", value = "Passed")))
        assertTrue(ui.send(command(ui, "prepare"))); assertEquals(0, port.calls)
        assertFalse(ui.send(oldEvidence))
        owner.confirm(); runCurrent(); ui.refresh(); assertEquals(1, port.calls)
        assertEquals("result", state(ui).getJSONObject("detail").getString("kind"))
        assertTrue(ui.send(command(ui, "detail.close")))
        assertTrue(ui.send(command(ui, "evidence", value = "Operator observed allowed and blocked controls")))
        assertFalse(ui.send(command(ui, "record", value = "Passed")))
        assertTrue(ui.send(command(ui, "verified", value = true)))
        assertTrue(ui.send(command(ui, "record", value = "Passed")))
        assertTrue(state(ui).getJSONObject("detail").getString("result").contains("tester", true))
        port.witness = port.witness.copy(dnsContext = "changed")
        assertFalse(ui.send(command(ui, "record", value = "Failed")))
        ui.close(); owner.close()
    }
    @Test fun `system UI return restores the public check but discards verification and input`() = runTest {
        val port = Port(); val owner = DeviceQaPageOwner(port, backgroundScope)
        val ui = NativeQaPresentation(RuntimeEnvironment.getApplication(), owner, { true }, { true }, { true })
        owner.activate(DeviceQaVisit(1, 1, port.authority)); ready(ui)
        ui.send(command(ui, "row", "cellularHandover")); ui.send(command(ui, "evidence", value = "old evidence"))
        owner.activate(null); ui.refresh(); owner.activate(DeviceQaVisit(1, 2, port.authority)); ready(ui)
        val detail = state(ui).getJSONObject("detail")
        assertEquals("check", detail.getString("kind")); assertEquals("", detail.getString("evidence")); assertFalse(detail.getBoolean("canRecord"))
        port.authority = port.authority.copy(accountGeneration = 12)
        owner.activate(DeviceQaVisit(1, 3, port.authority)); ready(ui)
        assertFalse(state(ui).has("detail")); ui.close(); owner.close()
    }
    @Test fun `projection omits native witnesses credentials and ineligible iOS checks and bounds public inputs`() = runTest {
        val port = Port(); val owner = DeviceQaPageOwner(port, backgroundScope)
        val ui = NativeQaPresentation(RuntimeEnvironment.getApplication(), owner, { true }, { it == "VPNChaining" }, { true })
        owner.activate(DeviceQaVisit(1, 1, port.authority)); ready(ui)
        val snapshot = ui.snapshot().toString()
        for (privateValue in listOf("native-dns-context", "account-native-only", "lowDataModeConstrained", "PrivateKey", "configurationWitness")) assertFalse(snapshot.contains(privateValue))
        assertFalse(ui.send(command(ui, "suffix", value = "a".repeat(254))))
        assertFalse(ui.send(command(ui, "row", "arbitrary-settings")))
        assertFalse(ui.send("{}")); assertFalse(ui.send("x".repeat(32769)))
        assertTrue(ui.send(command(ui, "row", "chained-editor")))
        ui.close(); owner.close()
    }
    @Test fun `committed editor values and native QA limits agree for composed Unicode and escaped evidence`() = runTest {
        val port = Port(); val owner = DeviceQaPageOwner(port, backgroundScope)
        val ui = NativeQaPresentation(RuntimeEnvironment.getApplication(), owner, { true }, { true }, { true })
        owner.activate(DeviceQaVisit(1, 1, port.authority)); ready(ui)
        for ((action, key, limit) in listOf(Triple("search", "search", 200), Triple("suffix", "probeSuffix", 253), Triple("canaryIP", "canaryIP", 64))) {
            for (cluster in listOf("😀", "é", "👩🏽‍💻")) {
                val committed = LavaTextFieldBufferPolicy.bound(cluster.repeat(limit), 0, limit)
                assertTrue(ui.send(command(ui, action, value = committed)))
                assertEquals(committed, state(ui).getString(key))
                assertFalse(ui.send(command(ui, action, value = "x".repeat(limit + 1))))
                assertEquals(committed, state(ui).getString(key))
            }
        }
        assertTrue(ui.send(command(ui, "search", value = "")))
        assertTrue(ui.send(command(ui, "row", "cellularHandover")))
        for (cluster in listOf("😀", "é", "👩🏽‍💻", "\u0001")) {
            val committed = LavaTextFieldBufferPolicy.bound(cluster.repeat(4096), 0, 4096)
            assertTrue(ui.send(command(ui, "evidence", value = committed)))
            assertEquals(committed, state(ui).getJSONObject("detail").getString("evidence"))
        }
        assertFalse(ui.send(command(ui, "evidence", value = "x".repeat(4097))))
        ui.close(); owner.close()
    }

    @Test fun `guided settings opens wireless settings only for the current admitted detail`() = runTest {
        val port = Port(); val owner = DeviceQaPageOwner(port, backgroundScope)
        val context = RuntimeEnvironment.getApplication()
        val ui = NativeQaPresentation(context, owner, { true }, { true }, { true })
        owner.activate(DeviceQaVisit(1, 1, port.authority)); ready(ui)
        assertFalse(ui.send(command(ui, "settings")))
        assertTrue(ui.send(command(ui, "row", "cellularHandover")))
        val settings = command(ui, "settings")
        assertTrue(ui.send(settings))
        assertEquals(android.provider.Settings.ACTION_WIRELESS_SETTINGS,
            org.robolectric.Shadows.shadowOf(context).nextStartedActivity.action)
        owner.activate(null); ui.refresh()
        assertFalse(ui.send(settings))
        ui.close(); owner.close()
    }
    @Test fun `localized form labels remain discoverable through search`() = runTest {
        val port = Port(); val owner = DeviceQaPageOwner(port, backgroundScope)
        val context = RuntimeEnvironment.getApplication()
        val ui = NativeQaPresentation(context, owner, { true }, { true }, { true })
        owner.activate(DeviceQaVisit(1, 1, port.authority)); ready(ui)
        for (label in listOf(com.lavasec.app.R.string.device_qa_resolver_ip,
            com.lavasec.app.R.string.device_qa_apply_custom_qa_probes,
            com.lavasec.app.R.string.device_qa_vpn_chaining,
            com.lavasec.app.R.string.device_qa_arm_leak_canary,
            com.lavasec.app.R.string.device_qa_disarm)) {
            assertTrue(ui.send(command(ui, "search", value = context.getString(label))))
            assertTrue(context.getString(label), state(ui).getBoolean("formsVisible"))
        }
        assertTrue(ui.send(command(ui, "search", value = "no-such-qa-control")))
        assertFalse(state(ui).getBoolean("formsVisible"))
        ui.close(); owner.close()
    }
    @Test fun `a stale host or authority cannot read a projection or confirm a new action`() = runTest {
        val port = Port(); val owner = DeviceQaPageOwner(port, backgroundScope); var host = true
        val ui = NativeQaPresentation(RuntimeEnvironment.getApplication(), owner, { host }, { true }, { true })
        owner.activate(DeviceQaVisit(1, 1, port.authority)); ready(ui)
        val old = command(ui, "row", "history-on")
        host = false; assertNull(ui.snapshot()); assertFalse(ui.send(old))
        host = true; port.authority = port.authority.copy(graph = 8)
        assertNull(ui.snapshot()); assertFalse(ui.send(old)); assertNull(owner.state.value.pending)
        ui.close(); owner.close()
    }
}
