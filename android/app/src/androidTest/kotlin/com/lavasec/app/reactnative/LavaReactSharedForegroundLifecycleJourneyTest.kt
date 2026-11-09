package com.lavasec.app.reactnative

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facebook.react.bridge.Callback
import com.facebook.react.bridge.PromiseImpl
import com.lavasec.app.security.AndroidKeystorePasscodeVerifier
import com.lavasec.app.security.SecurityPreferenceStore
import com.lavasec.app.settings.ProductSettingsStore
import com.lavasec.app.storage.CredentialFilterConfigurationRead
import com.lavasec.app.storage.CredentialFilterConfigurationStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real native visit lifetime with the installed RN renderer and surviving React module. */
@RunWith(AndroidJUnit4::class)
class LavaReactSharedForegroundLifecycleJourneyTest {
    @Test
    fun destroyedSharedForegroundHostCancelsPendingVisitAndReopenedContextRunsFreshLicenses() {
        ReactEmulatorJourney().use { journey ->
            journey.launch(); journey.ensureConfigured()
            val product = journey.application.foregroundProduct
            val generation = checkNotNull(product.state.value.graphGeneration)
            val library = checkNotNull(product.currentFilterLibrary())
            val savedLibrary = durableLibrary(journey)
            val settings = ProductSettingsStore(journey.context)
            val savedPreferences = settings.loadPreferences()
            val security = SecurityPreferenceStore(journey.context)
            val savedSecurity = security.load()
            val verifier = AndroidKeystorePasscodeVerifier(journey.context)
            val savedCredential = verifier.credentialState()
            assertFalse("This fixture cannot authenticate or alter an existing Security policy", savedSecurity.enabled)
            assertNull("Do not adopt an unrelated foreground visit", journey.snapshot().optJSONObject("foregroundFlow"))
            val oldHost = journey.requireActivity()
            val reactContext = checkNotNull(journey.application.reactHost.currentReactContext)
            val module = checkNotNull(reactContext.getNativeModule(LavaAppModule::class.java))
            assertTrue(reactContext.currentActivity === oldHost)
            val registry = journey.application.nativeVisitHosts
            val oldWitness = checkNotNull(registry.current())
            assertTrue("Actual Resume publishes A to the application registry", oldWitness.owner === oldHost)
            assertEquals(oldHost.nativeHostID, oldWitness.nativeHostID)
            assertTrue(oldHost.nativeHostAlive)
            assertTrue(oldWitness.matches(registry.current()))
            val oldCommand = startLicenses(module, oldHost, generation)
            val oldVisit = awaitLicenses(journey, oldHost, generation)
            assertEquals("The real licenses parent must stay suspended until dismissal", 1L, oldCommand.settled.count)

            // Real pause/resume retains this live Activity and its own pending visit.
            val paused = observeFromRunningIo({ oldHost.foreground }) { journey.background() }
            assertFalse("An already running IO reader sees the real onPause revoke writes", paused)
            assertFalse(oldHost.isDestroyed)
            assertTrue(oldHost.nativeHostAlive)
            assertSame("OS background retains A for the same visit", oldWitness, registry.current())
            assertTrue(oldWitness.matches(registry.current()))
            assertEquals(1L, oldCommand.settled.count)
            journey.resume()
            assertTrue(journey.requireActivity() === oldHost)
            assertTrue(reactContext.currentActivity === oldHost)
            assertSame("Same Activity Resume republishes its original immutable witness", oldWitness, registry.current())
            assertTrue(oldHost.foreground)
            assertTrue(oldWitness.matches(registry.current()))
            assertEquals(oldVisit, awaitLicenses(journey, oldHost, generation))
            assertEquals(1L, oldCommand.settled.count)
            assertEquals(savedLibrary, durableLibrary(journey))

            // Finish/reopen destroys A while preserving the real module and credential graph.
            val retired = observeFromRunningIo({ oldHost.nativeHostAlive }) { journey.reopen() }
            val nextHost = journey.requireActivity()
            assertFalse("The original lifetime is retired for an already running IO reader", retired)
            assertTrue(oldHost.isDestroyed)
            assertFalse(oldHost.nativeHostAlive)
            assertFalse(oldHost.foreground)
            assertFalse(oldWitness.retains(null))
            assertFalse(nextHost === oldHost)
            val nextWitness = checkNotNull(registry.current())
            assertTrue("Actual Resume publishes B to the application registry", nextWitness.owner === nextHost)
            assertEquals(nextHost.nativeHostID, nextWitness.nativeHostID)
            assertTrue(nextHost.nativeHostAlive)
            assertTrue(nextWitness.matches(registry.current()))
            assertFalse(oldWitness.retains(registry.current()))
            assertFalse("Late exact A retirement cannot clear B", registry.retire(oldHost, oldHost.nativeHostID))
            assertSame(nextWitness, registry.current())
            assertNotEquals(oldHost.nativeHostID, nextHost.nativeHostID)
            assertTrue("The regression requires the actual surviving ReactContext",
                journey.application.reactHost.currentReactContext === reactContext)
            assertTrue("Activity B must use the same LavaAppModule instance",
                reactContext.getNativeModule(LavaAppModule::class.java) === module)
            assertTrue(reactContext.currentActivity === nextHost)
            assertTrue(journey.application.foregroundProduct === product)
            assertEquals(generation, product.state.value.graphGeneration)
            journey.await("the destroyed host's shared parent is cancelled") { oldCommand.settled.count == 0L }
            assertCancelled(oldCommand)
            journey.await("B has no adopted foreground visit and its real root is ready") {
                journey.snapshot().optJSONObject("foregroundFlow") == null && onMain(journey) {
                    nextHost.isPresentationReady(generation) && !nextHost.isPresentationReady(generation, "foreground:$oldVisit")
                }
            }
            assertFalse("A retired licenses frame cannot remain on B", journey.visible("Full License Texts"))
            assertFalse("No old native editable field is exposed on B", journey.accessibilityNodes().any {
                it.packageName?.toString() == journey.context.packageName && it.isVisibleToUser &&
                    it.className?.toString() == "android.widget.EditText"
            })

            assertEquals(library, product.currentFilterLibrary())
            assertEquals(savedLibrary, durableLibrary(journey))
            assertEquals(savedPreferences, settings.loadPreferences())
            assertEquals(savedSecurity, security.load())
            assertEquals(savedCredential, verifier.credentialState())

            // Exactly one fresh B start; its packaged shared reader and Close are actual UI.
            val nextCommand = startLicenses(module, nextHost, generation)
            val nextVisit = awaitLicenses(journey, nextHost, generation)
            assertNotEquals(oldVisit, nextVisit)
            assertEquals(1L, nextCommand.settled.count)
            val stale = journey.command("foreground.dismiss", JSONObject().put("id", oldVisit)
                .put("nativeHostID", oldHost.nativeHostID))
            assertEquals("rejected", stale.getString("status"))
            assertEquals("foreground-stale", stale.getString("reason"))
            assertEquals(nextVisit, journey.snapshot().getJSONObject("foregroundFlow").getString("id"))
            assertEquals(1L, nextCommand.settled.count)
            journey.waitEnabled("Close", true)
            journey.click("Close", scroll = false)
            journey.await("the real shared Close settles B's parent and retires only B's visit") {
                nextCommand.settled.count == 0L && journey.snapshot().optJSONObject("foregroundFlow") == null
            }
            assertCancelled(nextCommand)
            journey.await("the shared reader has actually dismissed") { !journey.visible("Full License Texts") }
            assertTrue(journey.application.reactHost.currentReactContext === reactContext)
            assertTrue(reactContext.getNativeModule(LavaAppModule::class.java) === module)
            assertTrue(reactContext.currentActivity === nextHost)
            assertTrue(journey.application.foregroundProduct === product)
            assertEquals(generation, product.state.value.graphGeneration)
            assertEquals(library, product.currentFilterLibrary())
            assertEquals(savedLibrary, durableLibrary(journey))
            assertEquals(savedPreferences, settings.loadPreferences())
            assertEquals(savedSecurity, security.load())
            assertEquals(savedCredential, verifier.credentialState())
            journey.assertNoFallback()
            println("LAVA_UI_SHARED_FOREGROUND_LIFETIME pause=retained host=destroyed context=retained parent=cancelled successor=usable storage=unchanged")
        }
    }

    /** Start before the lifecycle change; the volatile fact, not a release latch, ends this read. */
    private fun observeFromRunningIo(authority: () -> Boolean, lifecycle: () -> Unit): Boolean {
        val started = CountDownLatch(1)
        val stop = AtomicBoolean(false)
        val io = Executors.newSingleThreadExecutor()
        try {
            val observed = io.submit<Boolean> {
                assertTrue("The captured host starts with live authority", authority())
                started.countDown()
                while (authority() && !stop.get()) Thread.yield()
                authority()
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            lifecycle()
            return observed.get(5, TimeUnit.SECONDS)
        } finally {
            stop.set(true)
            io.shutdownNow()
        }
    }

    private class PendingCommand {
        val settled = CountDownLatch(1)
        val response = AtomicReference<String?>()
        val rejected = AtomicBoolean(false)
        fun promise() = PromiseImpl(
            Callback { arguments -> response.set(arguments.firstOrNull() as? String); settled.countDown() },
            Callback { rejected.set(true); settled.countDown() },
        )
    }

    private fun startLicenses(module: LavaAppModule, host: LavaReactActivity, generation: Long): PendingCommand =
        PendingCommand().also { command ->
            module.command(JSONObject().put("schema", 1).put("type", "native.flow")
                .put("generation", generation.toString()).put("nativeHostID", host.nativeHostID)
                .put("flow", "licenses").toString(), command.promise())
        }

    private fun assertCancelled(command: PendingCommand) {
        assertFalse("A cancelled owner is a typed result, never a rejected JS promise", command.rejected.get())
        val result = JSONObject(checkNotNull(command.response.get()) { "The real native command returned no JSON" })
        assertEquals("cancelled", result.getString("status"))
        assertTrue("Retired visits return no private field payload", result.isNull("value"))
    }

    private fun awaitLicenses(journey: ReactEmulatorJourney, host: LavaReactActivity, generation: Long): String {
        journey.await("the current native host owns a real shared licenses visit") {
            val frame = journey.snapshot()
            frame.optString("nativeHostID") == host.nativeHostID &&
                frame.optJSONObject("foregroundFlow")?.optString("kind") == "licenses"
        }
        val flow = journey.snapshot().getJSONObject("foregroundFlow")
        val id = flow.getString("id")
        val notices = flow.getString("notices")
        assertTrue("The reader must contain actual bundled legal text", notices.isNotBlank())
        val firstHeading = notices.lineSequence().first { it.isNotBlank() }
        journey.await("the original host has the exact licenses layout acknowledgment") {
            val current = journey.snapshot()
            current.optString("nativeHostID") == host.nativeHostID &&
                current.optJSONObject("foregroundFlow")?.optString("id") == id && onMain(journey) {
                    journey.application.reactHost.currentReactContext?.currentActivity === host &&
                        host.isPresentationReady(generation, "foreground:$id")
                }
        }
        journey.waitLabel("Full License Texts")
        journey.waitLabel(firstHeading, prefix = true, excludeEditable = true)
        return id
    }

    private fun durableLibrary(journey: ReactEmulatorJourney) = runBlocking {
        val limit = journey.application.foregroundProduct.state.value.featureLimits.maxFilterRules
        val reopened = CredentialFilterConfigurationStore.credentialProtected(journey.context, limit).readAuthoritative()
        assertTrue("The saved library must independently reopen", reopened is CredentialFilterConfigurationRead.Available)
        (reopened as CredentialFilterConfigurationRead.Available).state.configuration
    }

    private fun <T> onMain(journey: ReactEmulatorJourney, operation: () -> T): T {
        val result = AtomicReference<T>()
        journey.instrumentation.runOnMainSync { result.set(operation()) }
        return result.get()
    }
}
