package com.lavasec.app.reactnative

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facebook.react.bridge.Callback
import com.facebook.react.bridge.PromiseImpl
import com.lavasec.app.presentation.R
import com.lavasec.app.security.AndroidKeystorePasscodeVerifier
import com.lavasec.app.security.PasscodeCredentialState
import com.lavasec.app.security.SecurityPreferenceStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real installed module/dialog/Activity lifecycle, with the production React context retained.
 * The bridge starts the ordinary management operation so its terminal promise is observable;
 * this is lifetime evidence, separate from the shipping Security-toggle UI journey. No code is
 * entered, credential is created, dialog is manually dismissed, or module/context is replaced.
 */
@RunWith(AndroidJUnit4::class)
class LavaReactPasscodeLifecycleJourneyTest {
    @Test
    fun destroyedPasscodeHostCancelsItsCommandAndReopenedReactContextAcceptsRealShareQuery() {
        ReactEmulatorJourney().use { journey ->
            journey.launch()
            journey.ensureConfigured()
            val product = journey.application.foregroundProduct
            val generation = checkNotNull(product.state.value.graphGeneration)
            val library = checkNotNull(product.currentFilterLibrary())
            val verifier = AndroidKeystorePasscodeVerifier(journey.context)
            val preferences = SecurityPreferenceStore(journey.context)
            val originalPreferences = preferences.load()
            assertEquals("Never replace an existing credential for this lifecycle fixture",
                PasscodeCredentialState.Absent, verifier.credentialState())
            assertFalse(originalPreferences.enabled)
            assertFalse(journey.snapshot().getJSONObject("plus").getBoolean("enabled"))

            journey.click("Settings", prefix = true, scroll = false)
            journey.waitLabel("Your Lava")
            journey.scrollToTop()
            journey.click("Security")
            journey.waitLabel("Authentication method")
            val oldActivity = journey.requireActivity()
            val reactContext = checkNotNull(journey.application.reactHost.currentReactContext)
            val module = checkNotNull(reactContext.getNativeModule(LavaAppModule::class.java))
            assertTrue(reactContext.currentActivity === oldActivity)
            assertTrue(journey.application.foregroundProduct === product)
            assertEquals(generation, product.state.value.graphGeneration)
            val settled = CountDownLatch(1)
            val response = AtomicReference<String?>()
            val rejected = AtomicBoolean(false)
            module.command(JSONObject().put("schema", 1).put("type", "native.flow")
                .put("generation", generation.toString()).put("flow", "passcode").toString(), PromiseImpl(
                Callback { arguments -> response.set(arguments.firstOrNull() as? String); settled.countDown() },
                Callback { rejected.set(true); settled.countDown() },
            ))
            val hints = setOf(journey.context.getString(R.string.security_new_passcode),
                journey.context.getString(R.string.security_confirm_passcode))
            journey.await("the actual unsubmitted management dialog owns its native password fields") {
                val fields = journey.accessibilityNodes().filter { node ->
                    node.packageName?.toString() == journey.context.packageName && node.isVisibleToUser &&
                        node.className?.toString() == "android.widget.EditText" && node.isEditable && node.isPassword
                }
                fields.size == 2 && fields.all { it.isEnabled } &&
                    fields.map { it.hintText?.toString() }.toSet() == hints
            }
            assertEquals("An untouched native management command remains suspended", 1L, settled.count)

            // finish/reopen destroys the exact host while leaving its app and React context alive.
            journey.reopen()
            assertTrue(oldActivity.isDestroyed)
            assertTrue("The regression requires the same surviving React context",
                journey.application.reactHost.currentReactContext === reactContext)
            assertTrue("The same module must release its original mutation admission",
                reactContext.getNativeModule(LavaAppModule::class.java) === module)
            assertTrue(reactContext.currentActivity === journey.requireActivity())
            assertTrue(journey.application.foregroundProduct === product)
            assertEquals(generation, product.state.value.graphGeneration)
            journey.await("the destroyed host's native command settles as cancellation") { settled.count == 0L }
            assertFalse("Cancellation is a typed result, not a rejected JS promise", rejected.get())
            val terminal = JSONObject(checkNotNull(response.get()) { "Native command returned no JSON" })
            assertEquals("cancelled", terminal.getString("status"))
            assertEquals("host-closed", terminal.getString("reason"))
            assertFalse("A retired credential dialog cannot appear in the reopened host",
                journey.accessibilityNodes().any { it.isVisibleToUser && it.isEditable && it.isPassword &&
                    it.packageName?.toString() == journey.context.packageName })
            assertEquals(PasscodeCredentialState.Absent, verifier.credentialState())
            assertEquals(originalPreferences, preferences.load())
            assertEquals(library, product.currentFilterLibrary())

            val rows = journey.snapshot().getJSONObject("filters").getJSONArray("rows")
            val selected = (0 until rows.length()).map(rows::getJSONObject).single { it.getBoolean("active") }
            assertTrue(journey.application.foregroundProduct === product)
            assertEquals(generation, product.state.value.graphGeneration)
            // Exactly one genuine next mutation-lane command: no busy retry or reset can mask a leak.
            val share = journey.completed("share.query", JSONObject().put("id", selected.getString("id")))
                .getJSONObject("value")
            assertNotNull("The real saved filter must produce its native card content", share.optJSONObject("card"))
            assertTrue(share.getString("url").isNotBlank())
            assertEquals(library, product.currentFilterLibrary())
            assertEquals(PasscodeCredentialState.Absent, verifier.credentialState())
            assertEquals(originalPreferences, preferences.load())
            journey.assertNoFallback()
            println("LAVA_UI_PASSCODE_LIFETIME host=destroyed context=retained command=cancelled credentials=unchanged share-query=accepted-once")
        }
    }
}
