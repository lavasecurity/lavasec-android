package com.lavasec.app.reactnative

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lavasec.app.R
import com.lavasec.app.foundation.filter.DeviceQaProbeSet
import com.lavasec.app.foundation.filter.FilterAction
import com.lavasec.app.foundation.filter.FilterDecision
import com.lavasec.app.foundation.filter.FilterDecisionReason
import com.lavasec.app.foundation.subscription.FeatureLimits
import com.lavasec.app.platform.FilterRuntimeLeaseRead
import com.lavasec.app.storage.CredentialFilterConfigurationRuntimeRead
import com.lavasec.app.storage.CredentialFilterConfigurationStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real QA UI writes and credential artifact reads. This is not public/off-device tunnel proof. */
@RunWith(AndroidJUnit4::class)
class LavaReactDeviceQaProbeJourneyTest {
    @Test
    fun shippingDeviceQaProbesCancelInstallCustomReopenAndClearUseExactCompiledAuthority() {
        ReactEmulatorJourney().use { journey ->
            journey.launch()
            journey.ensureConfigured()
            journey.settleCatalogBeforeQaReview()
            val product = journey.application.foregroundProduct
            val original = checkNotNull(product.currentFilterLibrary())
            val logging = product.state.value.privacy?.logging
            val plus = journey.snapshot().getJSONObject("plus").getBoolean("enabled")
            val limits = product.currentFeatureLimits()
            var primary: Throwable? = null
            try {
                journey.click("Settings", prefix = true, scroll = false)
                journey.scrollToTop()
                journey.click("Device QA")
                journey.waitLabel("Search Device QA")
                val controls = journey.scrollRegionContaining("Search Device QA")
                search(journey, controls, "Apply Hosted QA Probes")
                clickRow(journey, controls, "Apply Hosted QA Probes", R.string.device_qa_install_hosted_probe_rules_for_the_device_qa_page)
                journey.waitLabel("Apply test settings")
                journey.waitLabel("Applying settings does not verify protection or complete a network check.")
                journey.click("Cancel", scroll = false)
                assertEquals(original, product.currentFilterLibrary())

                clickRow(journey, controls, "Apply Hosted QA Probes", R.string.device_qa_install_hosted_probe_rules_for_the_device_qa_page)
                journey.click("Apply", scroll = false)
                journey.waitQaSaved()
                journey.click("Close", scroll = false)
                journey.await("actual hosted QA overlay adopted") {
                    product.currentFilterLibrary()?.qaProbeSet == DeviceQaProbeSet.HOSTED
                }
                assertEquals(original, product.currentFilterLibrary()!!.withQaProbeSet(original.qaProbeSet))
                assertCompiledProbes(journey, DeviceQaProbeSet.HOSTED)

                // Keep the search input distinct from the real Material suffix field's label.
                search(journey, controls, "Custom Probe")
                setSuffix(journey, controls, "localhost")
                journey.click("Apply Custom QA Probes", scroll = false, excludeEditable = true)
                journey.waitLabel("Invalid domain")
                journey.click("Close", scroll = false)
                assertEquals(DeviceQaProbeSet.HOSTED, product.currentFilterLibrary()?.qaProbeSet)
                setSuffix(journey, controls, "qualification.example")
                journey.click("Apply Custom QA Probes", scroll = false, excludeEditable = true)
                journey.click("Cancel", scroll = false)
                assertEquals(DeviceQaProbeSet.HOSTED, product.currentFilterLibrary()?.qaProbeSet)

                controls.reveal("Apply Custom QA Probes")
                journey.click("Apply Custom QA Probes", scroll = false, excludeEditable = true)
                journey.click("Apply", scroll = false)
                journey.waitQaSaved()
                journey.click("Close", scroll = false)
                val custom = DeviceQaProbeSet.fromSuffix("qualification.example")
                journey.await("actual custom QA overlay adopted") { product.currentFilterLibrary()?.qaProbeSet == custom }
                assertCompiledProbes(journey, custom)
                assertEquals(original, product.currentFilterLibrary()!!.withQaProbeSet(original.qaProbeSet))

                journey.back()
                journey.scrollToTop()
                journey.click("Device QA")
                journey.waitLabel("Search Device QA")
                val reopenedControls = journey.scrollRegionContaining("Search Device QA")
                search(journey, reopenedControls, "Probe Domains")
                custom.allDomains.forEach { domain ->
                    reopenedControls.reveal("$domain, ${journey.context.getString(R.string.device_qa_copy_domain)}")
                    journey.waitLabel(domain, prefix = true)
                }
                // A new store wrapper reopens the durable record and its exact immutable artifact.
                assertCompiledProbes(journey, custom)
                // Keep the entered query distinct from the exact row/summary label.
                search(journey, reopenedControls, "Clear QA Probe")
                clickRow(journey, reopenedControls, "Clear QA Probes", R.string.device_qa_clear_qa_probes)
                journey.click("Apply", scroll = false)
                journey.waitQaSaved()
                journey.click("Close", scroll = false)
                journey.await("QA overlay cleared by actual native write") { product.currentFilterLibrary()?.qaProbeSet == null }
                assertCompiledProbes(journey, null)
                assertEquals(original.withQaProbeSet(null), product.currentFilterLibrary())
                assertEquals(logging, product.state.value.privacy?.logging)
                assertEquals(limits, product.currentFeatureLimits())
                assertEquals(plus, journey.snapshot().getJSONObject("plus").getBoolean("enabled"))
                println("LAVA_UI_DEVICE_QA_PROBES hosted=cancel-and-save custom=invalid-cancel-save reopen=exact-durable-artifact clear=ordinary-artifact ordinary-settings=retained evidence=compiled-not-off-device")
                journey.assertNoFallback()
            } catch (failure: Throwable) {
                primary = failure
                throw failure
            } finally {
                // Controlled fixture cleanup, distinct from the UI evidence above. Restore only
                // this test's device-local overlay through the actual owner CAS; preserve any
                // newer ordinary library/DNS facts and never overwrite a replacement graph.
                try {
                    val current = product.currentFilterLibrary()
                    val graph = product.state.value.graphGeneration
                    if (current != null && graph != null && current.qaProbeSet != original.qaProbeSet) {
                        runBlocking {
                            val callerJob = currentCoroutineContext()[Job]
                            assertTrue("QA fixture cleanup must use native final-write admission", product.replaceFilterLibraryForQa(
                                desired = current.withQaProbeSet(original.qaProbeSet), expectedLibrary = current,
                                expectedGraph = graph,
                                isCurrent = { callerJob?.isActive != false && product.state.value.graphGeneration == graph },
                            ))
                        }
                    }
                } catch (cleanup: Throwable) {
                    // Preserve the first failed UI/authority assertion. A refused cleanup
                    // must not hide the operation that made the fixture unavailable.
                    if (primary != null) primary.addSuppressed(cleanup) else throw cleanup
                }
            }
        }
    }

    private fun search(journey: ReactEmulatorJourney, controls: ReactEmulatorJourney.AccessibilityScrollRegion, value: String) {
        controls.reveal("Search Device QA", forward = false)
        journey.setText("Search Device QA", value)
    }

    private fun clickRow(journey: ReactEmulatorJourney, controls: ReactEmulatorJourney.AccessibilityScrollRegion, title: String, summary: Int) {
        controls.reveal("$title, ${journey.context.getString(summary)}")
        journey.waitLabel(title, prefix = true, excludeEditable = true)
        journey.click(title, prefix = true, scroll = false, excludeEditable = true)
    }

    private fun setSuffix(journey: ReactEmulatorJourney, controls: ReactEmulatorJourney.AccessibilityScrollRegion, value: String) {
        // Revealing the real adjacent submit button brings this compact field into
        // view. The shared editable helper follows its actual Material label child.
        controls.reveal("Apply Custom QA Probes")
        journey.setText("Custom Probe Suffix", value)
        journey.await("real suffix field reflects its accepted native edit") {
            journey.accessibilityNodes().any { it.isEditable && it.isVisibleToUser && it.text?.toString()?.contains(value) == true }
        }
    }

    private fun assertCompiledProbes(journey: ReactEmulatorJourney, probes: DeviceQaProbeSet?): Unit = runBlocking {
        val store = CredentialFilterConfigurationStore.credentialProtected(journey.context, FeatureLimits.PLUS.maxFilterRules)
        val carrier = store.readAuthoritativeRuntime() as? CredentialFilterConfigurationRuntimeRead.Available
            ?: throw AssertionError("The committed exact native artifact must reopen")
        carrier.use {
            assertEquals(journey.application.foregroundProduct.currentFilterLibrary(), it.state.configuration)
            assertEquals(probes, it.state.configuration.qaProbeSet)
            it.takeLease().use { lease ->
                val identities = probes ?: DeviceQaProbeSet.fromSuffix("qualification.example")
                val expected = if (probes == null) List(4) { FilterDecision.DEFAULT_ALLOW } else listOf(
                    FilterDecision.DEFAULT_ALLOW,
                    FilterDecision(FilterAction.BLOCK, FilterDecisionReason.BLOCKLIST),
                    FilterDecision(FilterAction.ALLOW, FilterDecisionReason.LOCAL_ALLOWLIST),
                    FilterDecision(FilterAction.BLOCK, FilterDecisionReason.THREAT_GUARDRAIL),
                )
                identities.allDomains.forEachIndexed { index, domain ->
                    val read = lease.withDecisions { decisions -> decisions.decide(domain) }
                    assertTrue("Exact credential decisions must remain admitted", read is FilterRuntimeLeaseRead.Available<*>)
                    assertEquals(expected[index], (read as FilterRuntimeLeaseRead.Available<FilterDecision>).value)
                }
            }
        }
    }
}
