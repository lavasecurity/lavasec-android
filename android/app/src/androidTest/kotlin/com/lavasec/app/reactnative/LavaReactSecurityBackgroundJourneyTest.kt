package com.lavasec.app.reactnative

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lavasec.app.security.AndroidKeystorePasscodeVerifier
import com.lavasec.app.foundation.security.ProtectedSurface
import com.lavasec.app.presentation.R
import com.lavasec.app.security.PasscodeCredentialState
import com.lavasec.app.security.SecurityCredentialMutationResult
import com.lavasec.app.security.SecurityPreferenceStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/** Installed background/resume proof: only Open Lava selects the global cover;
 * scoped grants still revoke independently and retained native drafts keep their owner.
 * Fixture credentials are created and removed through the production security owner.
 */
@RunWith(AndroidJUnit4::class)
class LavaReactSecurityBackgroundJourneyTest {
    private data class FilterDraftReceipt(val host: NativeVisitHost, val generation: Long, val id: String, val lease: String)
    private var draftReceipt: FilterDraftReceipt? = null

    @Test
    fun securityOffContentAndEditDraftSurviveBackgroundResumeAndProtectedSurfaceConceals() {
        ReactEmulatorJourney().use { journey ->
            journey.launch()
            journey.ensureConfigured()
            val product = journey.application.foregroundProduct
            val generation = checkNotNull(product.state.value.graphGeneration)
            val verifier = AndroidKeystorePasscodeVerifier(journey.context)
            val preferences = SecurityPreferenceStore(journey.context)
            assertEquals("Fixture requires no pre-existing credential", PasscodeCredentialState.Absent,
                verifier.credentialState())
            assertTrue("Fixture requires no pre-existing protected surface",
                preferences.load().protectedSurfaces.isEmpty())

            var ownsCredential = false
            var primary: Throwable? = null
            val draftDomain = "bg-draft-${UUID.randomUUID()}.invalid"
            try {
                // --- Security off: an in-progress filter edit draft survives a permitted background.
                openActiveFilterDetail(journey)
                startEditing(journey)
                addBlockedDomainViaUi(journey, draftDomain)
                journey.background()
                journey.resume()
                journey.waitLabel("Cancel editing")
                assertTrue("An edit draft must survive an ordinary background", isEditing(journey))
                assertTrue("The staged draft rule must survive an ordinary background",
                    draftRules(journey, "blocked").contains(draftDomain))

                // --- Security on: an opted-in protected surface must conceal the still-mounted
                // workflow while backgrounded, and the draft/navigation must survive the concealment.
                ownsCredential = true
                assertEquals("The production security owner must accept the fixture passcode",
                    SecurityCredentialMutationResult.COMPLETED,
                    runBlocking { product.securitySetPasscodeResult(generation, CODE) })
                journey.await("fixture credential projected") {
                    journey.snapshot().getJSONObject("security").getBoolean("hasAuthenticationMethod")
                }
                // A scoped action remains independent of the whole-app display choice.
                journey.completed(
                    "settings.set",
                    JSONObject().put("key", "protectedActions.View Activities").put("value", true),
                )
                journey.await("fixture protected surface committed") {
                    val surfaces = journey.snapshot().getJSONObject("security").getJSONArray("protectedSurfaces")
                    (0 until surfaces.length()).any { surfaces.getString(it) == "activityViewing" }
                }
                assertTrue("The protected workflow must stay mounted before backgrounding", isEditing(journey))
                assertTrue("The staged draft rule must stay mounted before backgrounding",
                    draftRules(journey, "blocked").contains(draftDomain))

                // A scoped-only choice must not select an app-wide cover or prompt on a public page.
                journey.background()
                assertNativeCover(journey, concealed = false)
                journey.resume()
                journey.waitLabel("Cancel editing")
                assertFalse(journey.snapshot().getBoolean("backgroundPrivacyCoverRequired"))
                assertFalse(journey.snapshot().getBoolean("appPrivacyCoverRequired"))
                journey.completed("settings.set",
                    JSONObject().put("key", "protectedActions.App Unlock").put("value", true))
                journey.await("explicit Open Lava choice committed") {
                    product.securityStateForSnapshot(generation)?.appUnlockCoverChoice == true
                }
                val mountedActivity = journey.requireActivity()
                val mountedHost = checkNotNull(journey.application.nativeVisitHosts.current())
                assertSame(mountedActivity, mountedHost.owner)
                journey.background()
                assertNativeCover(journey, concealed = true)
                assertFalse("Actual background must revoke the previous Open Lava session",
                    checkNotNull(product.securityStateForSnapshot(generation))
                        .isAuthorized(ProtectedSurface.APP_UNLOCK))
                journey.resume()
                // Open Lava requires its one explicit authentication before current layout can reveal.
                unlockDisplayedSurface(journey)
                journey.await("the concealment preserved the mounted workflow", 20_000) {
                    var admitted = false
                    journey.instrumentation.runOnMainSync {
                        assertSame(mountedActivity, journey.requireActivity())
                        assertSame(mountedActivity, journey.application.reactHost.currentReactContext?.currentActivity)
                        assertTrue(mountedHost.matches(journey.application.nativeVisitHosts.current()))
                        assertEquals(generation, product.state.value.graphGeneration)
                        val security = product.securityStateForSnapshot(generation)
                        admitted = mountedActivity.foreground && mountedActivity.hasWindowFocus() &&
                            mountedActivity.isPresentationReady(generation) &&
                            security?.isAuthorized(ProtectedSurface.APP_UNLOCK) == true &&
                            security.isAuthorized(ProtectedSurface.FILTERS)
                    }
                    admitted && isEditing(journey)
                }
                assertNativeCover(journey, concealed = false)
                assertTrue("The concealment must not destroy the mounted workflow", isEditing(journey))
                assertTrue("The staged draft rule must survive the native-cover concealment",
                    draftRules(journey, "blocked").contains(draftDomain))

                // Discard the still-unsaved draft through the real editing toolbar.
                discardDraft(journey)
                journey.assertNoFallback()
            } catch (failure: Throwable) {
                primary = failure
                throw failure
            } finally {
                runCatching { journey.resume() }
                try {
                    if (isEditing(journey)) discardDraft(journey)
                } catch (cleanup: Throwable) {
                    if (primary != null) primary.addSuppressed(cleanup) else throw cleanup
                }
                if (ownsCredential) {
                    try {
                        // Removing the credential clears every protected surface through the same
                        // owner, so no later journey inherits the concealment opt-in.
                        assertEquals(SecurityCredentialMutationResult.COMPLETED,
                            runBlocking { product.securityRemovePasscodeResult(generation, CODE) })
                        journey.await("fixture credential and surfaces removed") {
                            val current = journey.snapshot().getJSONObject("security")
                            !current.getBoolean("hasAuthenticationMethod") &&
                                current.getJSONArray("protectedSurfaces").length() == 0
                        }
                        assertEquals(PasscodeCredentialState.Absent, verifier.credentialState())
                        assertFalse("The fixture credential preference must be restored", preferences.load().enabled)
                        assertTrue("The concealment opt-in must be restored",
                            preferences.load().protectedSurfaces.isEmpty())
                    } catch (cleanup: Throwable) {
                        if (primary != null) primary.addSuppressed(cleanup) else throw cleanup
                    }
                }
            }
        }
    }

    @Test
    fun displayedProtectedFilterDraftRequiresFreshGrantAndExactMeasuredResumeBeforeReveal() {
        ReactEmulatorJourney().use { journey ->
            journey.launch(); journey.ensureConfigured()
            val product = journey.application.foregroundProduct
            val generation = checkNotNull(product.state.value.graphGeneration)
            val verifier = AndroidKeystorePasscodeVerifier(journey.context)
            val preferences = SecurityPreferenceStore(journey.context)
            assertEquals(PasscodeCredentialState.Absent, verifier.credentialState())
            assertTrue(preferences.load().protectedSurfaces.isEmpty())
            var ownsCredential = false
            val domain = "protected-draft-${UUID.randomUUID()}.invalid"
            val activity = journey.requireActivity()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            try {
                openActiveFilterDetail(journey); startEditing(journey); addBlockedDomainViaUi(journey, domain)
                val filterID = checkNotNull(product.state.value.filterDetail).filterId
                val retainedRoot = reactRoots(journey)
                ownsCredential = true
                assertEquals(SecurityCredentialMutationResult.COMPLETED,
                    runBlocking { product.securitySetPasscodeResult(generation, CODE) })
                val revision = checkNotNull(product.securityStateForSnapshot(generation)).authenticationRevision
                assertTrue(runBlocking { product.securitySetProtectedSurface(ProtectedSurface.FILTERS, true, revision) })
                journey.await("the displayed Filters surface is protected") {
                    product.securityStateForSnapshot(generation)?.protectedSurfaces?.contains(ProtectedSurface.FILTERS) == true
                }
                unlockDisplayedSurface(journey)
                journey.await("actual Filters grant and scoped layout are ready") {
                    product.securityStateForSnapshot(generation)?.isAuthorized(ProtectedSurface.FILTERS) == true &&
                        activity.isPresentationReady(generation)
                }
                assertNativeCover(journey, concealed = false)
                assertTrue(draftRules(journey, "blocked").contains(domain))
                val pausedSerial = presentationSerial(journey, activity)
                val pausedReadRevision = checkNotNull(product.securityStateForSnapshot(generation)).authenticationRevision
                val originalHost = checkNotNull(journey.application.nativeVisitHosts.current())
                assertSame(activity, originalHost.owner)
                journey.background()
                assertNativeCover(journey, concealed = true)
                journey.resume()
                journey.await("background revoked the exact displayed Filter grant") {
                    product.securityStateForSnapshot(generation)?.isAuthorized(ProtectedSurface.FILTERS) == false &&
                        product.state.value.security?.authenticatedSurfaces?.contains(ProtectedSurface.FILTERS) != true
                }
                assertNativeCover(journey, concealed = true)
                assertEquals("A revoked JS read must not erase the native draft", filterID, product.state.value.filterDetail?.filterId)
                assertTrue(product.state.value.filterDetail?.editing == true)
                assertTrue(product.state.value.filterDetail?.blockedDomains?.contains(domain) == true)
                assertSameRoots(retainedRoot, reactRoots(journey))
                // Neither a queued pre-pause publication nor its Ready may borrow
                // the resumed authentication turn. The real RN route must publish
                // a successor serial and attest its actual measured viewport.
                assertEquals("A pre-pause publication must remain retired", "rejected",
                    journey.command("navigation.presentation", JSONObject().put("surface", "filterEditing")
                        .put("serial", pausedSerial).put("nativeHostID", activity.nativeHostID),
                        expectedGeneration = generation, expectedReadRevision = pausedReadRevision).getString("status"))
                assertFalse(activity.isPresentationReady(generation))
                assertEquals("A pre-pause Ready cannot uncover the retained page", "rejected",
                    journey.command("navigation.presentationReady", JSONObject().put("serial", pausedSerial)
                        .put("nativeHostID", activity.nativeHostID), expectedGeneration = generation,
                        expectedReadRevision = pausedReadRevision).getString("status"))
                assertEquals("A retired route receipt cannot uncover the retained page", "rejected",
                    journey.command("navigation.presentationReady", JSONObject().put("serial", pausedSerial - 1)
                        .put("nativeHostID", activity.nativeHostID), expectedGeneration = generation,
                        expectedReadRevision = pausedReadRevision).getString("status"))
                val resumedReadRevision = checkNotNull(product.securityStateForSnapshot(generation)).authenticationRevision
                assertTrue("The real background must retire the native authentication epoch", resumedReadRevision > pausedReadRevision)
                assertEquals("A fresh epoch alone cannot reuse the paused serial", "rejected",
                    journey.command("navigation.presentation", JSONObject().put("surface", "filterEditing")
                        .put("serial", pausedSerial).put("nativeHostID", activity.nativeHostID),
                        expectedGeneration = generation, expectedReadRevision = resumedReadRevision).getString("status"))
                assertNativeCover(journey, concealed = true)
                val earlyGrant = scope.async(start = CoroutineStart.UNDISPATCHED) {
                    product.state.first { it.security?.authenticatedSurfaces?.contains(ProtectedSurface.FILTERS) == true }
                    assertFalse("Fresh native credentials cannot stand in for scoped resumed layout", activity.isPresentationReady(generation))
                    assertCoverHierarchy(activity, concealed = true)
                }
                unlockDisplayedSurface(journey)
                runBlocking { withTimeout(20_000) { earlyGrant.await() } }
                journey.await("RN settled and attested the exact resumed protected viewport", 20_000) {
                    filterReady(journey, originalHost, generation) &&
                        presentationSerial(journey, activity) > pausedSerial
                }
                assertNativeCover(journey, concealed = false)
                assertSameRoots(retainedRoot, reactRoots(journey))
                journey.waitLabel("Cancel editing")
                assertEquals(filterID, product.state.value.filterDetail?.filterId)
                assertTrue(draftRules(journey, "blocked").contains(domain))
                discardDraft(journey)
                journey.assertNoFallback()
            } finally {
                scope.cancel()
                runCatching { journey.resume() }
                if (ownsCredential) {
                    assertEquals(SecurityCredentialMutationResult.COMPLETED,
                        runBlocking { product.securityRemovePasscodeResult(generation, CODE) })
                    journey.await("protected Filter fixture retired") { product.securityStateForSnapshot(generation)?.protectedSurfaces?.isEmpty() == true }
                    assertEquals(PasscodeCredentialState.Absent, verifier.credentialState())
                }
                if (draftReceipt != null) discardDraft(journey)
            }
        }
    }

    private fun unlockDisplayedSurface(journey: ReactEmulatorJourney) {
        val prompt = journey.context.getString(R.string.security_enter_passcode)
        if (!journey.visible(prompt)) journey.click(journey.context.getString(R.string.security_unlock_action), scroll = false)
        journey.waitLabel(prompt)
        journey.setText(prompt, CODE)
        journey.clickNativeDialogButton(journey.context.getString(R.string.security_unlock_action))
    }
    private fun reactRoots(journey: ReactEmulatorJourney): List<View> {
        var roots = emptyList<View>()
        journey.instrumentation.runOnMainSync {
            val content = journey.requireActivity().window.decorView.findViewById<ViewGroup>(android.R.id.content)
            roots = (0 until content.childCount).map(content::getChildAt).filter { it.javaClass.name != COVER_CLASS }
        }
        return roots
    }
    private fun assertSameRoots(before: List<View>, after: List<View>) {
        assertEquals(before.size, after.size)
        before.indices.forEach { assertSame("Covering/resuming must retain the actual React child", before[it], after[it]) }
    }
    private fun presentationSerial(journey: ReactEmulatorJourney, activity: LavaReactActivity): Long {
        var serial = 0L
        journey.instrumentation.runOnMainSync {
            assertSame(activity, journey.requireActivity())
            serial = LavaReactActivity::class.java.getDeclaredField("presentationSerial")
                .apply { isAccessible = true }.getLong(activity)
        }
        return serial
    }

    // ---- Proven UI navigation --------------------------------------------------------------

    private fun openActiveFilterDetail(journey: ReactEmulatorJourney) {
        journey.scrollToTop()
        journey.click("Now filtering", prefix = true)
        journey.waitLabel("Switch or manage filters")
        journey.click("Now filtering", prefix = true)
        journey.waitLabel("Lava blocks these")
    }

    private fun startEditing(journey: ReactEmulatorJourney) {
        journey.click("Edit", scroll = false)
        journey.await("filter edit mode") { isEditing(journey) }
        val product = journey.application.foregroundProduct
        val generation = checkNotNull(product.state.value.graphGeneration)
        val id = checkNotNull(product.state.value.filterDetail).filterId
        val host = checkNotNull(journey.application.nativeVisitHosts.current())
        assertSame(journey.requireActivity(), host.owner)
        draftReceipt = FilterDraftReceipt(host, generation, id, checkNotNull(product.sharedFilterDraftLease(generation, id)))
    }

    private fun addBlockedDomainViaUi(journey: ReactEmulatorJourney, domain: String) {
        journey.scrollToTop()
        journey.click("Block a domain")
        journey.waitLabel("Add blocked domain")
        journey.setText("Domain to block", domain)
        journey.click("Add domain", scroll = false)
        journey.await("draft rule staged") { draftRules(journey, "blocked").contains(domain) }
        journey.waitLabel("Cancel editing")
    }

    private fun discardDraft(journey: ReactEmulatorJourney) {
        val product = journey.application.foregroundProduct
        val receipt = draftReceipt ?: return
        val (host, generation, id, lease) = receipt
        val deadline = SystemClock.elapsedRealtime() + 20_000
        fun remaining() = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1)
        // A protected/redacted wire intentionally omits filterSession. Only the
        // originally captured native lease establishes whether this draft retired.
        assertTrue(host.matches(journey.application.nativeVisitHosts.current()))
        assertEquals(generation, product.state.value.graphGeneration)
        val currentLease = product.sharedFilterDraftLease(generation, id)
        assertTrue("Cleanup cannot adopt a successor draft", currentLease == null || currentLease == lease)
        if (currentLease != null) {
            journey.await("the actual draft toolbar is admitted", remaining()) {
                assertTrue(host.matches(journey.application.nativeVisitHosts.current()))
                assertEquals(generation, product.state.value.graphGeneration)
                assertEquals(lease, product.sharedFilterDraftLease(generation, id))
                filterReady(journey, host, generation) && journey.visible("Cancel editing")
            }
            journey.click("Cancel editing", scroll = false)
            journey.waitLabel("Discard changes?")
            journey.click("Discard", scroll = false)
        }
        journey.await("the exact native draft and discard dialog retired", remaining()) {
            assertTrue(host.matches(journey.application.nativeVisitHosts.current()))
            assertEquals(generation, product.state.value.graphGeneration)
            val currentLease = product.sharedFilterDraftLease(generation, id)
            assertTrue("Discard cannot adopt a successor draft", currentLease == null || currentLease == lease)
            val detail = product.state.value.filterDetail
            currentLease == null && detail?.filterId == id && !detail.editing &&
                filterReady(journey, host, generation) && !journey.visible("Discard changes?") && journey.visible("Edit")
        }
        draftReceipt = null
    }

    private fun filterReady(journey: ReactEmulatorJourney, host: NativeVisitHost, generation: Long): Boolean {
        var ready = false
        journey.instrumentation.runOnMainSync {
            val activity = journey.requireActivity()
            val product = journey.application.foregroundProduct
            val security = product.securityStateForSnapshot(generation)
            ready = host.owner === activity && host.matches(journey.application.nativeVisitHosts.current()) &&
                journey.application.reactHost.currentReactContext?.currentActivity === activity &&
                product.state.value.graphGeneration == generation && activity.foreground && activity.hasWindowFocus() &&
                activity.isPresentationReady(generation) && security?.isAuthorized(ProtectedSurface.APP_UNLOCK) == true &&
                security.isAuthorized(ProtectedSurface.FILTERS)
        }
        return ready
    }

    private fun isEditing(journey: ReactEmulatorJourney): Boolean {
        val product = journey.application.foregroundProduct
        draftReceipt?.let { receipt ->
            assertTrue(receipt.host.matches(journey.application.nativeVisitHosts.current()))
            assertEquals(receipt.generation, product.state.value.graphGeneration)
            val lease = product.sharedFilterDraftLease(receipt.generation, receipt.id)
            assertTrue("An edit observation cannot adopt a successor draft", lease == null || lease == receipt.lease)
            return lease != null
        }
        val generation = product.state.value.graphGeneration ?: return false
        val id = product.state.value.filterDetail?.filterId ?: return false
        return product.sharedFilterDraftLease(generation, id) != null
    }

    private fun draftRules(journey: ReactEmulatorJourney, decision: String): List<String> {
        val product = journey.application.foregroundProduct
        val receipt = checkNotNull(draftReceipt)
        assertTrue(receipt.host.matches(journey.application.nativeVisitHosts.current()))
        assertEquals(receipt.generation, product.state.value.graphGeneration)
        assertEquals(receipt.lease, product.sharedFilterDraftLease(receipt.generation, receipt.id))
        val draft = checkNotNull(product.sharedFilterDraft(receipt.generation, receipt.id))
        return when (decision) {
            "blocked" -> draft.manualRules.blockedDomains.toList()
            "allowed" -> draft.manualRules.allowedDomains.toList()
            else -> error("Unknown draft decision")
        }
    }

    /**
     * The shipping `ReactAppLockView` is the concealment surface. While it is required and the app
     * is not foreground it must cover the still-mounted React root (which stays a child of the
     * content view, only `INVISIBLE`), and an admitted foreground must remove it. This inspects the
     * real view hierarchy, not the accessibility tree, because a backgrounded window is not the
     * active accessibility root.
     */
    private fun assertNativeCover(journey: ReactEmulatorJourney, concealed: Boolean) {
        journey.instrumentation.runOnMainSync { assertCoverHierarchy(journey.requireActivity(), concealed) }
    }
    private fun assertCoverHierarchy(activity: LavaReactActivity, concealed: Boolean) {
            val content = activity.window.decorView
                .findViewById<ViewGroup>(android.R.id.content)
            assertNotNull("The hosting content view must be present", content)
            val children = (0 until content!!.childCount).map(content::getChildAt)
            val cover = children.singleOrNull { it.javaClass.name == COVER_CLASS }
            assertNotNull("The shipping native app-lock cover must be mounted", cover)
            val reactContent = children.filter { it !== cover }
            assertTrue("The React content must stay mounted beside the cover", reactContent.isNotEmpty())
            if (concealed) {
                assertEquals("The native cover must conceal the backgrounded app", View.VISIBLE, cover!!.visibility)
                assertTrue("Concealed React content must not be visible",
                    reactContent.all { it.visibility == View.INVISIBLE })
                assertTrue("Concealed React content must not enter accessibility",
                    reactContent.all { it.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS })
            } else {
                assertEquals("An admitted foreground must remove the native cover", View.GONE, cover!!.visibility)
                assertTrue("Admitted React content must be visible",
                    reactContent.all { it.visibility == View.VISIBLE })
            }
    }

    private companion object {
        // Public synthetic fixture code; never logged and never enters the JS snapshot.
        const val CODE = "91827364"
        const val COVER_CLASS = "com.lavasec.app.reactnative.ReactAppLockView"
    }
}
