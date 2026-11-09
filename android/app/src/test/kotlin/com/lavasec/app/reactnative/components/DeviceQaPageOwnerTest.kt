package com.lavasec.app.reactnative.components

import com.lavasec.app.foundation.filter.FilterCatalogSnapshot
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.onboarding.OnboardingAction
import com.lavasec.app.foundation.onboarding.OnboardingPage
import com.lavasec.app.foundation.resolver.ResolverTierChoice
import com.lavasec.app.foundation.subscription.FeatureLimits
import com.lavasec.app.platform.LoggingSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DeviceQaPageOwnerTest {
    @Test fun `scenario cancellation never prepares and only confirmed matching configuration permits manual recording`() = runTest {
        val port = Port(); val subject = DeviceQaPageOwner(port, backgroundScope); subject.activate(visit(port))
        val action = DeviceQaAction.Scenario("controlled-case", listOf(ResolverTierChoice("google-public-dns", "IP")), DeviceQaBlocklistLoad.MINIMAL)
        assertTrue(subject.prepare(action)); subject.cancelPending()
        assertFalse(subject.preparedConfigurationMatches(action.id)); assertEquals(0, port.calls)
        assertTrue(subject.prepare(action)); assertTrue(subject.confirm()); runCurrent()
        assertEquals(1, port.calls); assertTrue(subject.preparedConfigurationMatches(action.id))
        assertFalse(subject.record(action.id, "passed", false, "No real positive-control check was performed."))
        port.witness = port.witness.copy(logging = LoggingSettings(keepHistory = false))
        assertFalse(subject.preparedConfigurationMatches(action.id))
        assertFalse(subject.record(action.id, "passed", true, "stale reported setup"))
        assertTrue(subject.record(action.id, "blocked", false, "No physical check performed in this controlled fixture."))
        assertEquals(DeviceQaResultMessage.RECONCILIATION_REQUIRED,
            DeviceQaResult(DeviceQaOutcome.COMMITTED_RECONCILIATION_REQUIRED).message())
        subject.close()
    }

    @Test fun `canary controls require confirmation and never prepare a network check or manual pass`() = runTest {
        val port = Port(); val subject = DeviceQaPageOwner(port, backgroundScope); subject.activate(visit(port))
        val arm = DeviceQaAction.ArmCanary("192.0.2.53")
        assertTrue(subject.prepare(arm)); subject.cancelPending(); assertEquals(0, port.calls)
        assertTrue(subject.prepare(arm)); assertTrue(subject.confirm()); runCurrent()
        assertEquals(1, port.calls); assertNull(subject.state.value.preparedWitness)
        assertNull(subject.state.value.preparedScenarioId)
        assertFalse(subject.record("canary", "passed", true, "An arm alone is not physical evidence."))
        assertTrue(subject.prepare(DeviceQaAction.DisarmCanary)); subject.cancelPending(); assertEquals(1, port.calls)
        subject.close()
        Unit
    }

    @Test fun `custom probe validation and confirmation retain the saved set until actual native admission`() = runTest {
        val port = Port()
        val hosted = com.lavasec.app.foundation.filter.DeviceQaProbeSet.HOSTED
        port.witness = port.witness.copy(library = port.witness.library.withQaProbeSet(hosted))
        val owner = DeviceQaPageOwner(port, backgroundScope)
        val admitted = visit(port)
        owner.activate(admitted)
        assertFalse(owner.prepareCustomProbes("localhost", admitted))
        assertEquals("invalid-probe-suffix", owner.state.value.result?.reason)
        assertEquals(DeviceQaResultMessage.INVALID_DOMAIN, owner.state.value.result.message())
        assertEquals(hosted.allDomains, owner.probeDomains())
        assertEquals(0, port.calls)
        assertTrue(owner.prepareCustomProbes(" .LAN.EXAMPLE. ", admitted))
        val staged = owner.state.value.pending!!.action as DeviceQaAction.Probes
        assertEquals(com.lavasec.app.foundation.filter.DeviceQaProbeSet.fromSuffix("lan.example"), staged.value)
        assertEquals(hosted, port.witness.library.qaProbeSet)
        owner.cancelPending()
        assertFalse(owner.confirm())
        assertEquals(0, port.calls)
        assertTrue(owner.prepareCustomProbes("lan.example", admitted))
        port.witness = port.witness.copy(library = port.witness.library.withQaProbeSet(null))
        assertFalse(owner.confirm())
        assertEquals("configuration-changed", owner.state.value.result?.reason)
        assertEquals(0, port.calls)
        owner.activate(visit(port, 2))
        assertFalse(owner.prepareCustomProbes("lan.example", admitted))
        assertNull(owner.state.value.pending)
        owner.close()
        Unit
    }

    @Test fun `the original caller cancellation fences final publication even while the same visit remains admitted`() = runTest {
        val parent = kotlinx.coroutines.Job()
        val scope = kotlinx.coroutines.CoroutineScope(parent + kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val port = Port().apply { ready = CompletableDeferred() }
        val owner = DeviceQaPageOwner(port, scope)
        owner.activate(visit(port))
        assertTrue(owner.perform(DeviceQaAction.Probes(com.lavasec.app.foundation.filter.DeviceQaProbeSet.HOSTED)))
        runCurrent()
        assertTrue(owner.isCurrent(visit(port)))
        parent.cancel()
        assertFalse(port.capture!!.invoke())
        port.ready!!.complete(Unit)
        runCurrent()
        assertEquals(0, port.writes)
        assertNull(owner.state.value.result)
        owner.close()
        Unit
    }

    @Test fun `refusal copy preserves native diagnostics without rendering an implementation code`() {
        val samples = mapOf(
            "protected-qa-probe-owner-unavailable" to DeviceQaResultMessage.CHECK_UNAVAILABLE,
            "ios-constrained-path-check-unavailable" to DeviceQaResultMessage.CHECK_UNAVAILABLE,
            "browser-unavailable" to DeviceQaResultMessage.HOSTED_QA_UNAVAILABLE,
            "system-settings-unavailable" to DeviceQaResultMessage.SETTINGS_UNAVAILABLE,
            "authorization-required" to DeviceQaResultMessage.COULD_NOT_UPDATE,
            "configuration-changed" to DeviceQaResultMessage.SETTINGS_CHANGED,
            "StaleEditor" to DeviceQaResultMessage.SETTINGS_CHANGED,
            "filter-unavailable" to DeviceQaResultMessage.NOT_CONFIGURED,
            "CustomRequiresPlus" to DeviceQaResultMessage.COULD_NOT_UPDATE,
            "TransportUnavailable" to DeviceQaResultMessage.DNS_UNAVAILABLE,
            "a-new-native-code-with-sensitive-detail" to DeviceQaResultMessage.COULD_NOT_UPDATE,
        )
        for ((code, expected) in samples) {
            val result = DeviceQaResult(DeviceQaOutcome.REFUSED, code)
            assertEquals(expected, result.message())
            assertEquals(code, result.reason)
        }
        assertEquals(DeviceQaResultMessage.CANCELLED, DeviceQaResult(DeviceQaOutcome.CANCELLED).message())
        assertEquals(DeviceQaResultMessage.SAVED, DeviceQaResult(DeviceQaOutcome.APPLIED).message())
        assertEquals(DeviceQaResultMessage.NO_RESULT, (null as DeviceQaResult?).message())
    }
    private class Port : DeviceQaOwnerPort {
        var current = DeviceQaAuthority(7, 3, 11, "account")
        var witness = DeviceQaConfigurationWitness("device-dns|IP|1",
            FilterLibraryDraft.seededDefaults(FilterCatalogSnapshot(emptyList())), LoggingSettings())
        var calls = 0
        var writes = 0
        var ready: CompletableDeferred<Unit>? = null
        var capture: (() -> Boolean)? = null
        override fun authority() = current
        override fun configurationWitness() = witness
        override suspend fun perform(action: DeviceQaAction, visit: DeviceQaVisit, witness: DeviceQaConfigurationWitness?, isCurrent: () -> Boolean): DeviceQaResult {
            calls++
            capture = isCurrent
            // A retired native callback may complete despite cancellation; its final edge must
            // still consult the supplied authority, and the page must not publish its result.
            ready?.let { withContext(NonCancellable) { it.await() } }
            return if (isCurrent() && witness == this.witness) {
                writes++
                DeviceQaResult(DeviceQaOutcome.APPLIED)
            } else DeviceQaResult(DeviceQaOutcome.REFUSED, "superseded")
        }
    }
    private fun visit(port: Port, activation: Long = 1) = DeviceQaVisit(1, activation, port.current)
    private val dns = DeviceQaAction.Dns(listOf(ResolverTierChoice("device-dns", "IP")))

    @Test fun `mock onboarding rehearses every permission and final page without a product command`() = runTest {
        val port = Port()
        val owner = DeviceQaPageOwner(port, backgroundScope)
        owner.activate(visit(port))
        val original = port.witness
        owner.openMockOnboarding()
        val seen = mutableSetOf<OnboardingPage>()
        while (owner.state.value.mockOnboarding != null) {
            seen += owner.state.value.mockOnboarding!!.page
            owner.mock(OnboardingAction.Forward)
        }
        assertEquals(OnboardingPage.entries.toSet(), seen)
        assertEquals(original, port.witness)
        assertEquals(0, port.calls)
        owner.openMockOnboarding()
        owner.mock(OnboardingAction.Forward)
        owner.cancelMockOnboarding()
        assertNull(owner.state.value.mockOnboarding)
        assertEquals(0, port.calls)
        owner.close()
        Unit
    }

    @Test fun `paid and free limits are isolated previews and never change authority or call a product port`() = runTest {
        val port = Port()
        val owner = DeviceQaPageOwner(port, backgroundScope)
        owner.activate(visit(port))
        val authority = port.current
        val facts = port.witness
        owner.preview(true)
        assertEquals(FeatureLimits.PAID, owner.state.value.previewLimits)
        owner.preview(false)
        assertEquals(FeatureLimits.FREE, owner.state.value.previewLimits)
        owner.closePreview()
        assertNull(owner.state.value.previewLimits)
        assertEquals(authority, port.current)
        assertEquals(facts, port.witness)
        assertEquals(0, port.calls)
        owner.close()
        Unit
    }

    @Test fun `cancel confirmation performs no mutation and changed configuration refuses before the port`() = runTest {
        val port = Port()
        val owner = DeviceQaPageOwner(port, backgroundScope)
        owner.activate(visit(port))
        assertTrue(owner.prepare(dns))
        owner.cancelPending()
        assertFalse(owner.confirm())
        assertEquals(0, port.calls)
        assertTrue(owner.prepare(dns))
        port.witness = port.witness.copy(dnsContext = "cloudflare-1111|DoH|1")
        assertFalse(owner.confirm())
        assertEquals(DeviceQaOutcome.REFUSED, owner.state.value.result?.outcome)
        assertEquals("configuration-changed", owner.state.value.result?.reason)
        assertEquals(0, port.calls)
        owner.close()
        Unit
    }

    @Test fun `one admitted action owns the page and late completion cannot write or populate a reopened visit`() = runTest {
        val port = Port().apply { ready = CompletableDeferred() }
        val owner = DeviceQaPageOwner(port, backgroundScope)
        val old = visit(port)
        owner.activate(old)
        assertTrue(owner.perform(dns, old))
        runCurrent()
        assertFalse(owner.perform(dns, old))
        assertEquals(1, port.calls)
        owner.activate(null)
        assertFalse(port.capture!!.invoke())
        owner.activate(visit(port, 2))
        assertFalse(owner.perform(dns, old))
        port.ready!!.complete(Unit)
        runCurrent()
        assertEquals(0, port.writes)
        assertNull(owner.state.value.result)
        assertFalse(owner.state.value.busy)
        owner.close()
        Unit
    }

    @Test fun `graph security and same-account session replacement each fence a pending final edge`() = runTest {
        val mutations: List<(DeviceQaAuthority) -> DeviceQaAuthority> = listOf(
            { it.copy(graph = it.graph + 1) }, { it.copy(securityRevision = it.securityRevision + 1) },
            { it.copy(accountGeneration = it.accountGeneration!! + 1) }, { it.copy(accountId = "other") },
        )
        for (change in mutations) {
            val port = Port().apply { ready = CompletableDeferred() }
            val owner = DeviceQaPageOwner(port, backgroundScope)
            owner.activate(visit(port))
            assertTrue(owner.perform(DeviceQaAction.History(false)))
            runCurrent()
            port.current = change(port.current)
            assertFalse(port.capture!!.invoke())
            port.ready!!.complete(Unit)
            runCurrent()
            assertEquals(0, port.writes)
            assertNull(owner.state.value.result)
            owner.close()
        }
        Unit
    }

    @Test fun `blur and close discard private mock preview confirmation and manually reported results`() = runTest {
        val port = Port()
        val owner = DeviceQaPageOwner(port, backgroundScope)
        owner.activate(visit(port))
        owner.openMockOnboarding()
        owner.preview(true)
        owner.prepare(dns)
        assertTrue(owner.record("manual-network", "blocked", false, "No test network available"))
        owner.activate(null)
        assertEquals(DeviceQaPageState(), owner.state.value)
        owner.activate(visit(port, 2))
        assertEquals(emptyMap<String, String>(), owner.state.value.reportedResults)
        owner.close()
        assertFalse(owner.perform(dns))
        assertEquals(DeviceQaPageState(), owner.state.value)
        assertEquals(0, port.calls)
        Unit
    }

    @Test fun `settings applied alone never records success and reported results require exact scenario positive control and evidence`() = runTest {
        val port = Port()
        val owner = DeviceQaPageOwner(port, backgroundScope)
        owner.activate(visit(port))
        assertTrue(owner.perform(dns))
        runCurrent()
        assertFalse(owner.record("handover", "passed", true, "Observed allow and block"))
        val scenario = DeviceQaAction.Scenario("handover", dns.tiers, DeviceQaBlocklistLoad.MINIMAL)
        assertTrue(owner.perform(scenario))
        runCurrent()
        assertEquals(emptyMap<String, String>(), owner.state.value.reportedResults)
        assertFalse(owner.record("handover", "passed", false, "Observed"))
        assertFalse(owner.record("handover", "passed", true, " "))
        assertFalse(owner.record("another-check", "passed", true, "Observed"))
        assertTrue(owner.record("handover", "passed", true, "Allow resolved; deny refused across actual handover"))
        assertTrue(owner.state.value.reportedResults.getValue("handover").startsWith("passed\n"))
        port.witness = port.witness.copy(dnsContext = "changed")
        assertFalse(owner.preparedConfigurationMatches("handover"))
        assertFalse(owner.record("handover", "failed", true, "Observed"))
        assertTrue(owner.record("handover", "blocked", false, "Configuration changed; check was not completed"))
        owner.close()
        Unit
    }
}
