package com.lavasec.app.reactnative

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lavasec.app.R
import com.lavasec.app.data.ActivityDatabase
import com.lavasec.app.data.ActivityRepository
import com.lavasec.app.data.ActivityRevisionBus
import com.lavasec.app.data.CredentialActivityWriter
import com.lavasec.app.foundation.dns.DnsDecisionEvent
import com.lavasec.app.foundation.filter.DeviceQaProbeSet
import com.lavasec.app.foundation.filter.FilterAction
import com.lavasec.app.foundation.filter.FilterDecision
import com.lavasec.app.foundation.filter.FilterDecisionReason
import com.lavasec.app.foundation.resolver.ResolverTierChoice
import com.lavasec.app.foundation.resolver.toSelection
import com.lavasec.app.foundation.subscription.FeatureLimits
import com.lavasec.app.product.QaCompositeConfigurationResult
import com.lavasec.app.storage.CredentialFilterConfigurationRuntimeRead
import com.lavasec.app.storage.CredentialFilterConfigurationStore
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual native QA controls and one reopened compiled generation; no physical-network PASS. */
@RunWith(AndroidJUnit4::class)
class LavaReactDeviceQaCompositeJourneyTest {
    @Test
    fun shippingQaCompositeCancelsCommitsOneGenerationRefusesFreeCustomAndResetsRetainedData() {
        ReactEmulatorJourney().use { journey ->
            journey.launch(); journey.ensureConfigured()
            journey.settleCatalogBeforeQaReview()
            val product = journey.application.foregroundProduct
            val original = checkNotNull(product.currentFilterLibrary())
            val logging = checkNotNull(product.currentQaLogging())
            val plus = journey.snapshot().getJSONObject("plus").getBoolean("enabled")
            assertFalse("Controlled qualification uses the actual Free installation", plus)
            val database = ActivityDatabase(journey.context)
            try {
                journey.click("Settings", prefix = true, scroll = false)
                journey.scrollToTop(); journey.click("Device QA"); journey.waitLabel("Search Device QA")
                val controls = journey.scrollRegionContaining("Search Device QA")
                openRow(journey, controls, "Cellular Handover", R.string.device_qa_move_between_towers_or_radio_bands_while_the_tunnel_is_connected)
                applyTestSettings(journey)
                journey.click("Cancel", scroll = false)
                assertEquals(original, product.currentFilterLibrary())
                applyTestSettings(journey)
                journey.click("Apply", scroll = false); journey.waitQaSaved(); journey.click("Close", scroll = false)
                journey.await("condition-only QA uses the real exact overlay") {
                    product.currentFilterLibrary()?.qaProbeSet == DeviceQaProbeSet.HOSTED
                }
                assertEquals(original, product.currentFilterLibrary()!!.withQaProbeSet(original.qaProbeSet))
                // The fixture performs no physical positive-control check. Passing stays disabled.
                assertDisabledButton(journey, "Passed")
                journey.click("Close", scroll = false)

                openRow(journey, controls, "Full Network Sweep", R.string.device_qa_guided_network_checks_across_compatible_dns_setups_and_blocklist_loads)
                val row = "Cellular Handover / Plain DNS No Device Fallback / Minimal"
                val firstRow = listOf(R.string.device_qa_cellular_handover, R.string.device_qa_device_dns, R.string.device_qa_minimal)
                    .joinToString(" / ") { journey.context.getString(it) }
                val notRun = journey.context.getString(R.string.device_qa_not_run)
                journey.waitLabel("$firstRow, $notRun")
                journey.scrollRegionContaining("$firstRow, $notRun").reveal("$row, $notRun")
                journey.click(row, prefix = true, scroll = false, excludeEditable = true)
                val before = product.currentFilterLibrary()!!
                val generation = reopenedGeneration(journey)
                applyTestSettings(journey); journey.click("Cancel", scroll = false)
                assertEquals(before, product.currentFilterLibrary()); assertEquals(generation, reopenedGeneration(journey))
                applyTestSettings(journey); journey.click("Apply", scroll = false)
                journey.waitQaSaved(); journey.click("Close", scroll = false)
                journey.await("one composite native generation contains Google Minimal and Hosted") {
                    product.currentFilterLibrary()?.let { library ->
                        library.qaProbeSet == DeviceQaProbeSet.HOSTED && library.profile(library.activeProfileId)?.enabledSourceIds?.isEmpty() == true &&
                            library.resolverTarget.toSelection().primary.providerId.rawId == "google-public-dns"
                    } == true
                }
                assertEquals(generation + 1, reopenedGeneration(journey))
                assertDisabledButton(journey, "Passed")
                journey.click("Close", scroll = false) // selected suite row already dismissed its suite

                val prepared = product.currentFilterLibrary()!!
                openRow(journey, controls, "Custom DoQ No Device Fallback", R.string.device_qa_custom_doq_endpoint_with_device_dns_fallback_off)
                journey.click("Apply", scroll = false); journey.waitLabel(journey.context.getString(R.string.device_qa_filtering_could_not_update))
                journey.click("Close", scroll = false)
                assertEquals(prepared, product.currentFilterLibrary())
                assertFalse(journey.snapshot().getJSONObject("plus").getBoolean("enabled"))

                openRow(journey, controls, "Enable Local History", R.string.device_qa_turn_on_local_only_domain_history)
                journey.click("Apply", scroll = false); journey.waitQaSaved(); journey.click("Close", scroll = false)
                journey.await("actual retained history admission") { product.currentQaLogging()?.keepHistory == true }
                val repository = ActivityRepository(database)
                val domain = "qa-reset-${UUID.randomUUID()}.invalid"
                val writer = CredentialActivityWriter(repository, ActivityRevisionBus())
                assertTrue("Producer fixture uses the actual persisted retention policy", writer.record(DnsDecisionEvent(
                    id = System.currentTimeMillis(), timestampMillis = System.currentTimeMillis(), normalizedDomain = domain,
                    decision = FilterDecision(FilterAction.BLOCK, FilterDecisionReason.BLOCKLIST),
                )))
                assertTrue(repository.history(searchText = domain).isNotEmpty())
                openRow(journey, controls, "Clear QA State", R.string.device_qa_reset_summary)
                journey.click("Cancel", scroll = false)
                assertEquals(prepared, product.currentFilterLibrary()); assertTrue(repository.history(searchText = domain).isNotEmpty())
                controls.reveal("Clear QA State, ${journey.context.getString(R.string.device_qa_reset_summary)}")
                journey.click("Clear QA State", prefix = true, scroll = false, excludeEditable = true); journey.click("Apply", scroll = false)
                journey.waitQaSaved(); journey.click("Close", scroll = false)
                journey.await("real QA reset removes probes and clears retained rows") {
                    product.currentFilterLibrary()?.qaProbeSet == null && product.currentQaLogging()?.keepHistory == false && repository.history(searchText = domain).isEmpty()
                }
                val reset = product.currentFilterLibrary()!!
                assertEquals(prepared.profiles, reset.profiles)
                assertEquals(prepared.resolverTarget.toSelection(), reset.resolverTarget.toSelection())
                assertFalse(journey.snapshot().getJSONObject("plus").getBoolean("enabled"))
                reopenedGeneration(journey)
                journey.back(); journey.scrollToTop(); journey.click("Device QA")
                journey.waitLabel("Search Device QA")
                val reopenedControls = journey.scrollRegionContaining("Search Device QA")
                search(journey, reopenedControls, "Probe Domains")
                DeviceQaProbeSet.HOSTED.allDomains.forEach { domain ->
                    reopenedControls.reveal("$domain, ${journey.context.getString(R.string.device_qa_copy_domain)}")
                    journey.waitLabel(domain, prefix = true)
                }
                assertNull(product.currentFilterLibrary()?.qaProbeSet)
                assertFalse(product.currentQaLogging()!!.keepHistory)
                println("LAVA_UI_DEVICE_QA_COMPOSITE condition=overlay-only scenario=cancel-and-single-generation free-custom=refused reset=cancel-and-real-retention-clear entitlement=unchanged evidence=compiled-not-physical")
                journey.assertNoFallback()
            } finally {
                database.close()
                // Native fixture cleanup restores ordinary profile/DNS semantics and retention;
                // destructive reset receipts are synthetic and are not recreated as fake history.
                runBlocking {
                    val current = checkNotNull(product.currentFilterLibrary())
                    val graph = checkNotNull(product.state.value.graphGeneration)
                    val caller = currentCoroutineContext()[Job]
                    val admitted = { caller?.isActive != false && product.state.value.graphGeneration == graph }
                    val originalTiers = original.resolverTarget.toSelection().tiers.map { tier ->
                        val entry = tier.entry
                        ResolverTierChoice(entry.providerId.rawId, entry.transport.token, tier.retainedName ?: entry.customName ?: "",
                            entry.customDohUrl ?: entry.customDotEndpoint ?: entry.customDoqEndpoint ?: entry.customPlainEndpoint ?: "",
                            entry.customSecondaryEndpoint ?: "", tier.isEnabled)
                    }
                    assertTrue("Fixture restoration uses exact native policy and library CAS",
                        product.applyQaCompositeConfiguration(original, originalTiers, current, graph, admitted) is QaCompositeConfigurationResult.Applied)
                    assertTrue(product.setLoggingForRestore(logging, graph, admitted))
                    assertEquals(original.profiles, product.currentFilterLibrary()!!.profiles)
                    assertEquals(original.qaProbeSet, product.currentFilterLibrary()!!.qaProbeSet)
                    assertEquals(original.resolverTarget.toSelection(), product.currentFilterLibrary()!!.resolverTarget.toSelection())
                    assertEquals(logging, product.currentQaLogging())
                    assertEquals(plus, journey.snapshot().getJSONObject("plus").getBoolean("enabled"))
                }
            }
        }
    }

    private fun search(journey: ReactEmulatorJourney, controls: ReactEmulatorJourney.AccessibilityScrollRegion, text: String) {
        controls.reveal("Search Device QA", forward = false)
        journey.setText("Search Device QA", text)
    }

    private fun openRow(journey: ReactEmulatorJourney, controls: ReactEmulatorJourney.AccessibilityScrollRegion, title: String, summary: Int) {
        search(journey, controls, title)
        controls.reveal("$title, ${journey.context.getString(summary)}")
        journey.waitLabel(title, prefix = true, excludeEditable = true)
        journey.click(title, prefix = true, scroll = false, excludeEditable = true)
    }

    private fun guidedControls(journey: ReactEmulatorJourney): ReactEmulatorJourney.AccessibilityScrollRegion {
        val guidance = journey.context.getString(R.string.device_qa_guided_network_check) + ", " +
            journey.context.getString(R.string.device_qa_lava_prepares_probes_and_settings_you_must_create_the_network_condition_and_verify_the_active_tunnel_downloads_and_probes)
        journey.waitLabel(guidance)
        // Pending/result dialogs unmount the guide. Capture its actual replacement
        // container each time instead of acting on a retired dialog's virtual node.
        return journey.scrollRegionContaining(guidance)
    }

    private fun applyTestSettings(journey: ReactEmulatorJourney) {
        guidedControls(journey).reveal("Apply test settings")
        journey.click("Apply test settings", scroll = false, excludeEditable = true)
        journey.waitLabel(journey.context.getString(R.string.device_qa_applying_settings_does_not_verify_protection_or_complete_a_network_check))
    }

    private fun assertDisabledButton(journey: ReactEmulatorJourney, label: String) {
        guidedControls(journey).reveal(label)
        fun labels() = journey.accessibilityNodes().filter { node ->
            node.refresh() && node.isVisibleToUser && !node.isEditable &&
                node.packageName?.toString() == journey.context.packageName &&
                listOf(node.text, node.contentDescription).any { it?.toString() == label }
        }
        fun controls() = labels().mapNotNull { named ->
            // Compose may expose the text/role on a child and OnClick/Disabled on its parent.
            // Resolve the nearest actual clickable control; an arbitrary disabled label is not proof.
            var candidate: AccessibilityNodeInfo? = named
            var depth = 0
            while (candidate != null && depth < 8) {
                val current = candidate
                if (!current.refresh() || !current.isVisibleToUser || current.isEditable ||
                    current.windowId != named.windowId || current.packageName?.toString() != journey.context.packageName) break
                if (current.isClickable) return@mapNotNull current
                candidate = current.parent
                depth += 1
            }
            null
        }.distinct()
        try {
            var target: AccessibilityNodeInfo? = null
            journey.await("actual disabled native $label button") {
                val candidates = controls()
                assertTrue("The exact native $label label must identify at most one clickable control", candidates.size <= 1)
                target = candidates.singleOrNull()?.takeIf { !it.isEnabled }
                target != null
            }
            val button = checkNotNull(target)
            assertTrue("The actual disabled control must remain live", button.refresh())
            assertEquals("The refreshed exact label must still own this control", button, controls().singleOrNull())
            assertTrue("The refusal is exercised on the actual clickable control", button.isClickable)
            assertFalse(button.isEnabled)
            assertFalse("An unverified physical check refuses a real native activation", button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertFalse(journey.visible("Passed — tester reported"))
        } catch (failure: AssertionError) {
            labels().take(4).forEach { named ->
                var candidate: AccessibilityNodeInfo? = named
                var depth = 0
                while (candidate != null && depth < 8) {
                    val current = candidate
                    if (current.windowId != named.windowId || current.packageName?.toString() != journey.context.packageName) break
                    println("LAVA_UI_QA_DISABLED_CONTROL label=$label depth=$depth identity=${current.hashCode()} " +
                        "window=${current.windowId} class=${current.className} refreshed=${current.refresh()} " +
                        "visible=${current.isVisibleToUser} enabled=${current.isEnabled} editable=${current.isEditable} " +
                        "clickable=${current.isClickable} actions=${current.actionList.map { it.id }}")
                    candidate = current.parent
                    depth += 1
                }
            }
            throw failure
        }
    }

    private fun reopenedGeneration(journey: ReactEmulatorJourney): Long = runBlocking {
        val store = CredentialFilterConfigurationStore.credentialProtected(journey.context, FeatureLimits.PLUS.maxFilterRules)
        val carrier = store.readAuthoritativeRuntime() as? CredentialFilterConfigurationRuntimeRead.Available
            ?: throw AssertionError("Exact compiled QA authority must reopen")
        carrier.use {
            val current = checkNotNull(journey.application.foregroundProduct.currentFilterLibrary())
            assertEquals(current, it.state.configuration)
            assertEquals(current.profile(current.activeProfileId)!!.enabledSourceIds,
                it.state.verifiedArtifact.artifactIdentity.selectedSources.map { source -> source.id }.toSet())
            it.state.configurationIdentity.generation
        }
    }
}
