package com.lavasec.app.reactnative

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.system.Os
import android.view.WindowManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.facebook.react.bridge.Callback
import com.facebook.react.bridge.PromiseImpl
import com.lavasec.app.LavasecApplication
import com.lavasec.app.BuildConfig
import com.lavasec.app.foundation.security.ProtectedSurface
import com.lavasec.app.security.SecurityPrivacyPolicy
import com.lavasec.app.foundation.onboarding.OnboardingPage
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * Drives the packaged production React surface and Android's accessibility tree. This uses no
 * preview snapshot, test entitlement, replacement application graph, Metro, or live account.
 * Bridge helpers are for owner assertions/fixtures; [click] and [back] are real UI operations.
 */
internal class ReactEmulatorJourney(private val uiAutomationFlags: Int = 0) : AutoCloseable {
    val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val application get() = context.applicationContext as LavasecApplication
    private var activity: LavaReactActivity? = null
    private var lastClick: Pair<String, Boolean>? = null
    private var lastClickTarget: String? = null
    private val publicScreenshotFrames = JSONArray()
    private var publicScreenshotSerial: Long? = null

    init {
        // After a system consent dialog, API 28 can retain the removed dialog as the active
        // accessibility root. Interactive windows let us read the actual focused app window.
        val automation = instrumentation.getUiAutomation(uiAutomationFlags)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    fun launch(deepLink: String? = null) {
        check(activity == null)
        if (deepLink == null) {
            activity = instrumentation.startActivitySync(
                Intent(context, LavaReactActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            ) as LavaReactActivity
        } else openDeepLink(deepLink)
        await("production React module") {
            application.reactHost.currentReactContext?.getNativeModule(LavaAppModule::class.java) != null
        }
        await("credential graph and React foreground", 60_000) {
            val current = snapshot()
            !current.isNull("generation") && current.optBoolean("foreground") &&
                (current.optBoolean("available") || current.optBoolean("onboardingRequired"))
        }
        if (deepLink != null && snapshot().optBoolean("onboardingRequired")) waitLabel("The internet is lava")
        else if (deepLink == null) {
            waitLabel("Guard"); waitLabel("Settings")
            await("the revealed native root boundaries fill the current React pane") {
                val fits = AtomicBoolean(false)
                instrumentation.runOnMainSync {
                    val host = activity ?: return@runOnMainSync
                    val surface = host.reactDelegate?.reactRootView ?: return@runOnMainSync
                    fun find(view: android.view.View, id: String): android.view.View? {
                        if (view.getTag(com.facebook.react.R.id.react_test_id) == id) return view
                        val group = view as? android.view.ViewGroup ?: return null
                        for (index in 0 until group.childCount) find(group.getChildAt(index), id)?.let { return it }
                        return null
                    }
                    val viewport = find(surface, "lava-window-viewport") ?: return@runOnMainSync
                    val frame = find(viewport, "lava-render-frame") ?: return@runOnMainSync
                    val safeArea = (frame as? android.view.ViewGroup)?.let { group ->
                        (0 until group.childCount).map(group::getChildAt)
                            .singleOrNull { it is com.th3rdwave.safeareacontext.SafeAreaProvider }
                    } ?: return@runOnMainSync
                    fits.set(surface.width > 0 && surface.height > 0 && frame.parent === viewport &&
                        listOf(viewport, frame, safeArea).all {
                            it.isAttachedToWindow && it.width == surface.width && it.height == surface.height
                        })
                }
                fits.get()
            }
        }
    }

    /** Actual exported validator launches its independent RN child, never a command navigation. */
    fun openDeepLink(uri: String): LavaReactActivity {
        val original = activity
        val launch = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri), context,
            com.lavasec.app.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val source = AtomicReference<com.lavasec.app.MainActivity?>(null)
        val sourceFinished = AtomicBoolean(false)
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        // Fixed scalar diagnostics only. No intent, URI, ticket, or host nonce is retained.
        val stages = arrayOf(Stage.PRE_ON_CREATE, Stage.CREATED, Stage.STARTED, Stage.RESUMED,
            Stage.PAUSED, Stage.STOPPED, Stage.RESTARTED, Stage.DESTROYED)
        val validatorStages = IntArray(stages.size)
        val matchingStages = IntArray(stages.size)
        val observer = ActivityLifecycleCallback { candidate, stage ->
            if (candidate is com.lavasec.app.MainActivity) {
                val index = stages.indexOf(stage)
                if (index >= 0 && validatorStages[index] < 32) validatorStages[index]++
                if (candidate.intent?.action == launch.action && candidate.intent?.data == launch.data &&
                    index >= 0 && matchingStages[index] < 32) matchingStages[index]++
            }
            // singleTop can deliver a validated URI through onNewIntent, without
            // another CREATED callback. Observe the exact intent on its real
            // subsequent lifecycle boundary as well as a newly created source.
            if (candidate is com.lavasec.app.MainActivity &&
                candidate.intent?.action == launch.action && candidate.intent?.data == launch.data) {
                source.compareAndSet(null, candidate)
            }
            // Read lifecycle flags on Main and publish an exact source witness.
            if (source.get() === candidate && (candidate.isFinishing || candidate.isDestroyed)) {
                sourceFinished.set(true)
            }
        }
        try {
            instrumentation.runOnMainSync {
                monitor.addLifecycleCallback(observer)
                // This validator finishes in onCreate; it has no persistent input
                // window for startActivitySync's animation/idle barrier to observe.
                context.startActivity(launch)
            }
            await("real exported validator launch and native handoff", 45_000) {
                source.get() != null && sourceFinished.get()
            }
            await("exact exported entry React child", 60_000) {
                val child = application.reactHost.currentReactContext?.currentActivity as? LavaReactActivity
                if (child != null && child !== original && child.nativeDeepLinkEntryID != null && child.foreground &&
                    !child.isFinishing && !child.isDestroyed) { activity = child; true } else false
            }
            return requireActivity()
        } catch (failure: Throwable) {
            // Preserve the original failure. Observe the exact current native window
            // on Main, without substituting route visibility for required source finish.
            runCatching {
                instrumentation.runOnMainSync {
                    val observed = source.get()
                    val child = application.reactHost.currentReactContext?.currentActivity as? LavaReactActivity
                    val generation = application.foregroundProduct.state.value.graphGeneration
                    val receipt = JSONObject().put("schema", 1)
                        .put("sourceSeen", observed != null).put("finishSeen", sourceFinished.get())
                        .put("sourceIdentity", observed?.let(System::identityHashCode) ?: 0)
                        .put("sourceFinishing", observed?.isFinishing == true)
                        .put("sourceDestroyed", observed?.isDestroyed == true)
                        .put("childSeen", child != null).put("childIsOriginal", child != null && child === original)
                        .put("childIsNew", child != null && child !== original)
                        .put("childHasEntry", child?.nativeDeepLinkEntryID != null)
                        .put("childIsCurrentHost", child != null && application.nativeVisitHosts.current()?.owner === child)
                        .put("childAlive", child?.nativeHostAlive == true)
                        .put("childForeground", child?.foreground == true)
                        .put("childFinishing", child?.isFinishing == true)
                        .put("childDestroyed", child?.isDestroyed == true)
                        .put("childReady", child != null && generation != null && child.isPresentationReady(generation))
                        .put("graphPresent", generation != null).put("generation", generation ?: -1)
                        .put("validatorStages", JSONObject().apply {
                            stages.indices.forEach { put(stages[it].name, validatorStages[it]) }
                        }).put("matchingStages", JSONObject().apply {
                            stages.indices.forEach { put(stages[it].name, matchingStages[it]) }
                        })
                    val diagnostic = "LAVA_UI_EXPORTED_ENTRY_LIFECYCLE $receipt"
                    println(diagnostic)
                    // Retain these same fixed scalars in the immutable failed-phase
                    // transcript even if later processes evict the System.out buffer.
                    failure.addSuppressed(AssertionError(diagnostic).apply { stackTrace = emptyArray() })
                }
            }
            throw failure
        } finally {
            instrumentation.runOnMainSync { monitor.removeLifecycleCallback(observer) }
        }
    }

    fun reopen() {
        finishActivity()
        launch()
    }

    /**
     * Drives a genuine task background (the system calls `onPause`/`onStop` on the shipping
     * Activity). The same task is later returned to the foreground by [resume]; this is the real
     * launcher/recents path, not a replacement Activity.
     */
    fun background() {
        val current = requireActivity()
        instrumentation.runOnMainSync {
            assertTrue("The shipping task refused to move to the background", current.moveTaskToBack(true))
        }
        await("the shipping React host reached onStop in the background") {
            var backgrounded = false
            instrumentation.runOnMainSync {
                // onPause/focus loss alone is a transient interruption: resuming there can
                // legitimately restore the current unlock session before onStop revokes it.
                backgrounded = ActivityLifecycleMonitorRegistry.getInstance()
                    .getLifecycleStageOf(current) == Stage.STOPPED &&
                    !current.foreground && !current.hasWindowFocus()
            }
            backgrounded
        }
    }

    /** Returns the backgrounded shipping task to the foreground, resuming the same Activity. */
    fun resume() {
        val current = requireActivity()
        instrumentation.runOnMainSync {
            current.startActivity(
                Intent(current, LavaReactActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
        await("the shipping React host resumed") {
            var resumed = false
            instrumentation.runOnMainSync {
                resumed = current.foreground && current.hasWindowFocus() && !current.isFinishing && !current.isDestroyed
            }
            resumed
        }
        instrumentation.waitForIdleSync()
    }

    fun snapshot(): JSONObject = call(module()::getSnapshot)

    /** Finish the real catalog refresh before a fixture captures a reviewed filter basis.
     * The scheduled 15-minute cache pass can otherwise discover the bundled catalog and
     * replace its generation in the middle of a deliberately single-attempt QA write.
     * This joins the process owner; it does not disable workers, seed a result or retry UI.
     */
    fun settleCatalogBeforeQaReview() {
        val owner = checkNotNull(application.catalogMaintenance.sync)
        // Budget for each production endpoint's whole-call timeout, then retain
        // the existing minute for local installation/settlement. A timed-out join
        // remains a failure; it never manufactures a stable review basis.
        val endpoints = com.lavasec.app.data.CatalogSyncEndpoints.catalogUrls(BuildConfig.BACKEND_URL).size
        val timeoutMillis = (endpoints * com.lavasec.app.data.OkHttpCatalogManifestFetcher.WHOLE_CALL_TIMEOUT_SECONDS + 60) * 1_000
        val result = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) { owner.refresh() }
        }
        assertNotNull("The actual catalog refresh did not settle within ${timeoutMillis}ms", result)
        val status = checkNotNull(result).status
        // Superseded work and source failures needing foreground repair are complete.
        // Transient transport/persist/generic install failures cannot attest this baseline.
        assertTrue("The actual catalog must settle before capturing a QA review: $status",
            com.lavasec.app.data.catalogRefreshWorkComplete(result))
        assertFalse("A joined catalog refresh must have finished", owner.snapshot().refreshing)
    }

    /** Failure-only native scalar facts; no QA inputs, domains, saved rules or account data. */
    fun waitQaSaved() {
        try { waitLabel("Saved") }
        catch (failure: AssertionError) {
            var facts = "unavailable"
            instrumentation.runOnMainSync {
                val owners = mutableListOf<com.lavasec.app.reactnative.components.DeviceQaPageOwner>()
                val field = com.lavasec.app.reactnative.components.LavaNativePageView::class.java
                    .getDeclaredField("sharedQa").apply { isAccessible = true }
                fun walk(view: android.view.View) {
                    if (view is com.lavasec.app.reactnative.components.LavaNativePageView && view.isAttachedToWindow && view.isShown) {
                        (field.get(view) as? com.lavasec.app.reactnative.components.NativeQaComposition)?.let { owners += it.owner }
                    }
                    if (view is android.view.ViewGroup) repeat(view.childCount) { walk(view.getChildAt(it)) }
                }
                walk(requireActivity().window.decorView)
                val product = application.foregroundProduct
                val graph = product.state.value.graphGeneration
                val drafts = if (graph == null) null else product.currentFilterLibrary()?.profiles?.count {
                    product.sharedFilterDraft(graph, it.id) != null
                }
                val results = owners.map { owner ->
                    val state = owner.state.value
                    val reason = state.result?.reason?.takeIf { it.matches(Regex("[A-Za-z0-9-]{1,80}")) }
                    "outcome=${state.result?.outcome}, reason=$reason, busy=${state.busy}, current=${state.visit?.let(owner::isCurrent)}"
                }
                facts = "owners=${owners.size}, results=$results, drafts=$drafts, " +
                    "creating=${graph?.let { product.sharedNewFilter(it) != null }}, preparation=${product.state.value.filterPreparation.javaClass.simpleName}"
            }
            failure.addSuppressed(AssertionError("Native QA failure facts: $facts"))
            throw failure
        }
    }

    /** Test-only probes reuse the same current-window and refreshed-descendant rules as actions. */
    fun accessibilityNodes(): List<AccessibilityNodeInfo> = nodes()
    fun requireActivity(): LavaReactActivity = checkNotNull(activity)
    fun scrollPage(forward: Boolean = true): Boolean = scroll(forward)

    private data class PublicScreenshotReceipt(val serial: Long, val authenticationRevision: Long,
        val windowID: Int, val bounds: Rect)

    /** Actual pixels of three public, signed-out shipping pages; never a private/synthetic view. */
    fun capturePublicPage(tag: String) {
        val anchor = when (tag) {
            "shipping-guard" -> "Protection status"
            "shipping-settings" -> "Your connection"
            "shipping-upgrade" -> "More ways to make Lava your own"
            else -> error("Unknown public screenshot tag")
        }
        assertTrue("Public captures are restricted to the controlled debug candidate", BuildConfig.DEBUG)
        val directory = File(context.cacheDir, "predevice-public-screenshots")
        assertEquals("Screenshot directory cannot redirect outside its app cache", context.cacheDir.canonicalFile, directory.canonicalFile.parentFile)
        assertEquals("Screenshot directory cannot be a different cache entry", directory.name, directory.canonicalFile.name)
        assertTrue("Cannot create public screenshot directory", directory.isDirectory || directory.mkdir())
        Os.chmod(directory.path, 0x1c0)
        assertEquals(0x1c0, Os.stat(directory.path).st_mode and 0x1ff)
        val knownFiles = setOf("shipping-guard.png", "shipping-settings.png", "shipping-upgrade.png", "manifest.json")
        assertTrue("Screenshot directory contains an unexpected entry", directory.listFiles()?.all {
            it.name in knownFiles && it.isFile && it.canonicalFile == File(directory.canonicalFile, it.name)
        } == true)
        if (publicScreenshotFrames.length() == 0) {
            assertEquals("Guard starts this finite capture packet", "shipping-guard", tag)
            directory.listFiles()!!.forEach { assertTrue("Cannot retire previous controlled screenshot", it.delete()) }
        }
        assertFalse("A public frame must be captured once", (0 until publicScreenshotFrames.length()).any {
            publicScreenshotFrames.getJSONObject(it).getString("tag") == tag
        })
        val host = requireActivity()
        val product = application.foregroundProduct
        val generation = checkNotNull(product.state.value.graphGeneration)
        val serialField = LavaReactActivity::class.java.getDeclaredField("presentationSerial").apply { isAccessible = true }
        val surfaceField = LavaReactActivity::class.java.getDeclaredField("protectedPresentation").apply { isAccessible = true }
        val ownerField = LavaReactActivity::class.java.getDeclaredField("presentationOwnerID").apply { isAccessible = true }
        val credentialsField = LavaReactActivity::class.java.getDeclaredField("credentialPresentation").apply { isAccessible = true }
        val expectedSurface = if (tag == "shipping-guard") null else ProtectedSurface.APP_SETTINGS
        val automation = instrumentation.getUiAutomation(uiAutomationFlags)
        fun receipt(): PublicScreenshotReceipt? {
            val current = snapshot()
            if (current.optString("nativeHostID") != host.nativeHostID ||
                current.optString("generation").toLongOrNull() != generation ||
                !current.optBoolean("available") || !current.isNull("foregroundFlow") ||
                !current.isNull("onboardingSetup") ||
                application.accountBackend.value?.sessions?.state?.value?.userId != null) return null
            val windows = automation.windows
            if (windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                    it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
                    it.type != AccessibilityWindowInfo.TYPE_APPLICATION && (it.isActive || it.isFocused) }) return null
            val window = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                (it.isActive || it.isFocused) }.singleOrNull() ?: return null
            if (window.root?.packageName?.toString() != context.packageName) return null
            if (nodes().none { it.windowId == window.id && it.packageName?.toString() == context.packageName &&
                    matches(it, anchor, prefix = tag == "shipping-guard") }) return null
            var result: PublicScreenshotReceipt? = null
            instrumentation.runOnMainSync {
                val security = product.securityStateForSnapshot(generation) ?: return@runOnMainSync
                val bounds = Rect()
                val serial = serialField.getLong(host)
                if ((publicScreenshotSerial == null || serial > checkNotNull(publicScreenshotSerial)) &&
                    activity === host && application.foregroundProduct === product &&
                    application.reactHost.currentReactContext?.currentActivity === host &&
                    application.nativeVisitHosts.current()?.owner === host &&
                    host.nativeHostAlive && host.foreground && !host.isFinishing && !host.isDestroyed &&
                    host.hasWindowFocus() && host.isPresentationReady(generation) && host.admitsNativeFieldInput() &&
                    product.state.value.graphGeneration == generation &&
                    !SecurityPrivacyPolicy.requiresBackgroundCover(security) &&
                    security.isAuthorized(ProtectedSurface.APP_UNLOCK) &&
                    (expectedSurface == null || security.isAuthorized(expectedSurface)) &&
                    surfaceField.get(host) == expectedSurface && ownerField.get(host) == null &&
                    !credentialsField.getBoolean(host) &&
                    host.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0 &&
                    host.window.decorView.isShown && host.window.decorView.getGlobalVisibleRect(bounds) &&
                    bounds.width() > 0 && bounds.height() > 0) {
                    result = PublicScreenshotReceipt(serial, security.authenticationRevision, window.id, bounds)
                }
            }
            return result
        }
        // Observe the real receipt without logging a snapshot or private visible tree on failure.
        val deadline = SystemClock.elapsedRealtime() + 20_000L
        var accepted: PublicScreenshotReceipt? = null
        while (accepted == null && SystemClock.elapsedRealtime() < deadline) {
            accepted = receipt()
            if (accepted == null) SystemClock.sleep(100)
        }
        val before = checkNotNull(accepted) { "Public screenshot $tag lacks exact public native authority/visible Ready" }
        instrumentation.waitForIdleSync()
        assertEquals("Public screenshot authority changed before pixel capture", before, receipt())
        val image = File(directory, "$tag.png")
        val manifest = File(directory, "manifest.json")
        val temporary = File(directory, "manifest.tmp")
        val bitmap = checkNotNull(automation.takeScreenshot()) { "UiAutomation refused actual public pixels for $tag" }
        var retained = false
        try {
            assertEquals("Public screenshot authority changed during pixel capture", before, receipt())
            assertTrue("Screenshot dimensions must contain the actual native window", bitmap.width >= before.bounds.right &&
                bitmap.height >= before.bounds.bottom && before.bounds.left >= 0 && before.bounds.top >= 0)
            assertTrue("Public screenshot exceeds the bounded pixel budget", bitmap.width in 1..4096 &&
                bitmap.height in 1..4096 && bitmap.width.toLong() * bitmap.height <= 16_777_216L)
            FileOutputStream(image).use { stream ->
                Os.chmod(image.path, 0x180)
                assertTrue("PNG encoding of actual public pixels failed", bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                stream.fd.sync()
            }
            assertTrue("Public PNG must be nonempty and bounded", image.length() in 1L..8_388_608L)
            assertEquals(0x180, Os.stat(image.path).st_mode and 0x1ff)
            assertEquals("Public screenshot authority changed before retention", before, receipt())
            val digest = MessageDigest.getInstance("SHA-256").digest(image.readBytes()).joinToString("") { "%02x".format(it) }
            publicScreenshotFrames.put(JSONObject().put("tag", tag).put("file", image.name).put("page", tag)
                .put("width", bitmap.width).put("height", bitmap.height).put("sha256", digest))
            val json = JSONObject().put("schema", 1).put("api", Build.VERSION.SDK_INT).put("frames", publicScreenshotFrames)
                .toString().toByteArray(Charsets.UTF_8)
            assertTrue("Public screenshot manifest must remain compact", json.size <= 4096)
            FileOutputStream(temporary).use { stream -> Os.chmod(temporary.path, 0x180); stream.write(json); stream.fd.sync() }
            Os.rename(temporary.path, manifest.path)
            assertEquals(0x180, Os.stat(manifest.path).st_mode and 0x1ff)
            retained = true
            publicScreenshotSerial = before.serial
            println("LAVA_UI_PUBLIC_SCREENSHOT tag=" + tag + " width=" + bitmap.width + " height=" + bitmap.height + " sha256=" + digest)
        } finally {
            bitmap.recycle()
            temporary.delete()
            if (!retained) image.delete()
        }
    }


    fun command(type: String, payload: JSONObject = JSONObject(), expectedGeneration: Long? = null,
        expectedReadRevision: Long? = null): JSONObject {
        val normalPresentation = type == "navigation.presentation" || type == "navigation.presentationReady"
        // Capture once before dispatch. Explicit old graph/turn fixtures retain their witness;
        // never resample after a native suspension or overwrite an explicit stale revision.
        val origin = if (expectedGeneration == null || normalPresentation && !payload.has("readRevision") && expectedReadRevision == null)
            snapshot() else null
        val generation = expectedGeneration?.toString() ?: requireNotNull(origin).getString("generation")
        val request = payload.put("schema", 1).put("generation", generation).put("type", type)
        if (normalPresentation && !request.has("readRevision")) {
            val captured = expectedReadRevision ?: origin?.takeIf { it.optString("generation") == generation }
                ?.optJSONObject("security")?.takeIf { it.has("readRevision") }?.getLong("readRevision")
            captured?.let { request.put("readRevision", it.toString()) }
        }
        if (!request.has("nativeHostID")) {
            // The shipping facade binds commands to its originating React root.
            // Fixtures target the actual current window, retaining explicit wrong-
            // host values used by cross-window rejection assertions.
            val host = application.reactHost.currentReactContext?.currentActivity as? LavaReactActivity ?: requireActivity()
            request.put("nativeHostID", host.nativeHostID)
        }
        // Legacy apply/switch waits for the real catalog compile; its fixture already allows
        // ninety seconds for publication. Ordinary reads/actions keep the short bridge bound.
        return call({ module().command(request.toString(), it) }, "Native command $type",
            timeoutSeconds = if (type == "filter.apply" || type == "filter.switch") 90 else 20)
    }

    fun completed(type: String, payload: JSONObject = JSONObject(), expectedGeneration: Long? = null,
        expectedReadRevision: Long? = null): JSONObject =
        command(type, payload, expectedGeneration = expectedGeneration, expectedReadRevision = expectedReadRevision).also {
            assertEquals("$type refused: $it", "completed", it.optString("status"))
        }

    /**
     * Creates the fixture using the supported first-run UI and actual Android consent result.
     * A previously configured emulator is also supported for standalone owner tests. The fresh
     * first-run journey explicitly passes requireFirstRun and cannot silently skip that evidence.
     */
    fun ensureConfigured(requireFirstRun: Boolean = false, cancelFirstAttempt: Boolean = false) {
        val needsSetup = snapshot().optBoolean("onboardingRequired")
        if (requireFirstRun) assertTrue("Run this journey on freshly cleared app data", needsSetup)
        if (!needsSetup) {
            await("configured product") { snapshot().optBoolean("available") }
            return
        }
        if (requireActivity().nativeDeepLinkEntryID == null) {
            waitLabel("Set up your protection")
            click("Continue setup")
        }
        waitLabel("The internet is lava")
        await("shared real onboarding visit") { snapshot().optJSONObject("onboardingSetup")?.optInt("page") == 0 }
        val visit = snapshot().getJSONObject("onboardingSetup").getString("id")
        val onboardingHost = requireActivity()
        val onboardingGeneration = snapshot().getString("generation").toLong()
        assertFalse("First-run must use the real shared owner", snapshot().getJSONObject("onboardingSetup").getBoolean("mock"))
        if (cancelFirstAttempt) {
            // Real first-run is mandatory. Back cancels a page advance, retaining
            // the same uncommitted visit instead of dismissing setup altogether.
            click("Meet Lava", scroll = false)
            await("shared feature page") { snapshot().optJSONObject("onboardingSetup")?.optInt("page") == 1 }
            waitLabel("Lava stands guard here")
            back()
            await("Back returned the same shared welcome visit") { snapshot().optJSONObject("onboardingSetup")?.optInt("page") == 0 }
            waitLabel("The internet is lava")
            assertEquals(visit, snapshot().getJSONObject("onboardingSetup").getString("id"))
            assertTrue("Cancelling setup navigation must preserve the incomplete owner", snapshot().getBoolean("onboardingRequired"))
            assertFalse("Cancelling setup navigation must not publish a configured product", snapshot().getBoolean("available"))
            assertEquals(OnboardingPage.LAVA, onboardingPage())
        }
        click("Meet Lava", scroll = false)
        await("shared feature page") { snapshot().optJSONObject("onboardingSetup")?.optInt("page") == 1 }
        waitLabel("Lava stands guard here")
        click("Set Up Protection", scroll = false)
        await("shared permission page") { snapshot().optJSONObject("onboardingSetup")?.optInt("page") == 2 }
        val permissionHost = requireActivity()
        val permissionGeneration = snapshot().getString("generation").toLong()
        fun exactPermissionViewportReady(current: JSONObject): Boolean {
            if (current.optString("nativeHostID") != permissionHost.nativeHostID ||
                current.optString("generation").toLongOrNull() != permissionGeneration ||
                current.optJSONObject("onboardingSetup")?.optString("id") != visit) return false
            var ready = false
            instrumentation.runOnMainSync {
                ready = activity === permissionHost &&
                    application.reactHost.currentReactContext?.currentActivity === permissionHost &&
                    !permissionHost.isFinishing && !permissionHost.isDestroyed &&
                    permissionHost.isPresentationReady(permissionGeneration, "onboarding:$visit")
            }
            return ready
        }
        waitLabel("Install local VPN")
        clickOnboardingChoice(onboardingHost, onboardingGeneration, visit, 2,
            "First, let’s get Lava ready to help.", "Install local VPN")
        // The real native owner prepares Android consent. Grant only its actual
        // OS dialog; Android may already retain consent after app-data reset.
        await("VPN consent dialog or actual installed consent", 20_000) {
            if (snapshot().optJSONObject("onboardingSetup")?.optBoolean("vpnInstalled") == true) true
            else { clickIfPresent("OK"); false }
        }
        assertEquals("The OS must recognize Lava's VPN consent", null, VpnService.prepare(context))
        if (!snapshot().getJSONObject("onboardingSetup").getBoolean("notifications")) {
            // A completed VPN result precedes the resumed React viewport receipt. The
            // visible permission row alone must not admit the next system request.
            await("exact shared onboarding viewport ready for optional permission") {
                val current = snapshot()
                exactPermissionViewportReady(current) &&
                    current.getJSONObject("onboardingSetup").optString("busy").isEmpty()
            }
            clickOnboardingChoice(onboardingHost, onboardingGeneration, visit, 2,
                "First, let’s get Lava ready to help.", "Enable notifications (optional)")
            await("optional native notification permission settled") {
                clickIfPresent("Don't allow") || clickIfPresent("Don’t allow")
                val current = snapshot()
                val (generation, owner) = application.foregroundProduct.sharedOnboardingAuthority()
                // Initial idle or a refused request is not a settled permission result.
                // Use the real owner's decision and the exact resumed host/visit layout.
                generation == permissionGeneration && owner != null && owner.onboarding?.notificationHandled == true &&
                    owner.notificationPermissionGranted != null &&
                    current.getJSONObject("onboardingSetup").optString("busy").isEmpty() &&
                    exactPermissionViewportReady(current)
            }
        }
        // Already-granted notifications (including API28) still follow a VPN
        // result that can precede this exact resumed layout acknowledgement.
        await("exact shared onboarding viewport ready to advance") {
            val current = snapshot()
            exactPermissionViewportReady(current) && current.getJSONObject("onboardingSetup").optString("busy").isEmpty()
        }
        click("Next step", scroll = false)
        await("shared protection page") { snapshot().optJSONObject("onboardingSetup")?.optInt("page") == 3 }
        waitLabel("Pick how much Lava blocks")
        clickOnboardingChoice(onboardingHost, onboardingGeneration, visit, 3,
            "Pick how much Lava blocks", "Core", prefix = true)
        await("Core choice accepted by the native owner") {
            snapshot().getJSONObject("onboardingSetup").getString("level") == "essential"
        }
        click("Next step", scroll = false)
        await("shared DNS choice page") { snapshot().optJSONObject("onboardingSetup")?.optInt("page") == 4 }
        waitLabel("Keep connections working", prefix = true)
        assertEquals("Shared Quad9 copy must describe the actual staged resolver",
            com.lavasec.app.foundation.resolver.BuiltInDohResolverIds.QUAD9_UNFILTERED,
            application.foregroundProduct.sharedOnboardingAuthority().second?.onboarding?.draft?.resolver?.encryptedFallbackId)
        click("Next step", scroll = false)
        await("measured shared Guard destination is ready", 20_000) {
            snapshot().optJSONObject("onboardingSetup")?.optString("phase") == "ready"
        }
        // Android combines the shared accessibility label and value on this
        // single modal status node. Assert both, not an obsolete standalone title.
        waitLabel("Ready, Your next step to a safer internet.")
        waitLabel("Open Guard")
        assertTrue("Ready remains uncommitted until Open Guard", snapshot().getBoolean("onboardingRequired"))
        assertFalse("Arrival geometry alone cannot configure the product", snapshot().getBoolean("available"))
        assertEquals(OnboardingPage.CONNECTION_QUALITY, onboardingPage())
        clickOnboardingPrimary(onboardingHost, onboardingGeneration, visit, mock = false)
        await("durable shared onboarding completion and Guard return", 60_000) {
            val current = snapshot()
            val owner = application.foregroundProduct.sharedOnboardingAuthority().second
            if (owner?.reconstructionRequired == true) {
                fail("Onboarding preparation failed: ${owner.error}. " +
                    "Owner: ${ownerMetadata()}. Windows: ${windowMetadata()}. " +
                    "Visible tree: ${labels()}. Native snapshot: $current")
            }
            current.optBoolean("available") && current.optBoolean("foreground") &&
                !current.optBoolean("onboardingRequired") && current.optJSONObject("onboardingSetup") == null
        }
        assertFalse("The fixture must remain a free account", snapshot().getJSONObject("plus").getBoolean("enabled"))
        if (requireActivity().nativeDeepLinkEntryID == null) waitProtectionOff()
    }

    /** The real modal primary owns Open Guard; its measured Guard paint does not. */
    fun clickOnboardingPrimary(host: LavaReactActivity, generation: Long, visit: String, mock: Boolean) {
        val label = "Open Guard"
        val capturedHost = checkNotNull(application.nativeVisitHosts.current())
        assertTrue(capturedHost.owner === host)
        fun currentOwner(): Boolean {
            val current = snapshot()
            assertEquals(generation.toString(), current.getString("generation"))
            assertEquals(host.nativeHostID, current.optString("nativeHostID"))
            val setup = current.optJSONObject("onboardingSetup") ?: return false
            assertEquals(visit, setup.getString("id"))
            assertEquals(mock, setup.getBoolean("mock"))
            assertEquals(5, setup.getInt("page"))
            var ready = false
            instrumentation.runOnMainSync {
                assertTrue(capturedHost.matches(application.nativeVisitHosts.current()))
                assertTrue(application.reactHost.currentReactContext?.currentActivity === host)
                ready = host.isOnboardingPreview == mock && host.nativeHostAlive && host.foreground &&
                    host.hasWindowFocus() && !host.isFinishing && !host.isDestroyed &&
                    application.foregroundProduct.state.value.graphGeneration == generation &&
                    host.isPresentationReady(generation, "onboarding:$visit")
            }
            return ready && setup.getString("phase") == "ready" && setup.optString("busy").isEmpty()
        }
        fun exactNativePrimary(target: AccessibilityNodeInfo): Boolean {
            var owned = false
            instrumentation.runOnMainSync {
                fun visitNative(view: android.view.View, inModal: Boolean) {
                    val modal = inModal || view.getTag(com.facebook.react.R.id.react_test_id) == "onboarding.surface"
                    if (modal && view.getTag(com.facebook.react.R.id.react_test_id) == "onboarding.primary" &&
                        view.isAttachedToWindow && view.isShown && view.createAccessibilityNodeInfo() == target) owned = true
                    if (view is android.view.ViewGroup) for (index in 0 until view.childCount) visitNative(view.getChildAt(index), modal)
                }
                visitNative(host.window.decorView, false)
            }
            return owned
        }
        var primary: AccessibilityNodeInfo? = null
        await("the exact admitted modal Open Guard action", 20_000) {
            if (!currentOwner()) return@await false
            val candidates = nodes().filter { it.viewIdResourceName == "onboarding.primary" &&
                it.packageName?.toString() == context.packageName && matches(it, label, prefix = false) }
            assertTrue("The visible primary must have one actual native source", candidates.size <= 1)
            primary = candidates.singleOrNull()?.takeIf { it.refresh() && it.isEnabled && it.isClickable &&
                it.className?.toString() == "android.widget.Button" &&
                it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_CLICK } && exactNativePrimary(it) }
            primary != null
        }
        assertTrue(currentOwner())
        val target = checkNotNull(primary)
        assertTrue(target.refresh() && target.isVisibleToUser && target.isEnabled && target.isClickable &&
            target.viewIdResourceName == "onboarding.primary" && matches(target, label, prefix = false) && exactNativePrimary(target))
        instrumentation.runOnMainSync {
            assertTrue(capturedHost.matches(application.nativeVisitHosts.current()))
            assertTrue(application.reactHost.currentReactContext?.currentActivity === host)
            assertEquals(generation, application.foregroundProduct.state.value.graphGeneration)
            assertTrue(host.foreground && host.hasWindowFocus() && host.isPresentationReady(generation, "onboarding:$visit"))
            val nativeViews = mutableListOf<android.view.View>()
            fun collect(view: android.view.View) {
                nativeViews += view
                if (view is android.view.ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
            }
            collect(host.window.decorView)
            fun tag(view: android.view.View) = view.getTag(com.facebook.react.R.id.react_test_id)
            fun beneath(view: android.view.View, ancestor: android.view.View): Boolean {
                var current: android.view.View? = view
                while (current != null) {
                    if (current === ancestor) return true
                    current = current.parent as? android.view.View
                }
                return false
            }
            val runtime = nativeViews.filter { tag(it) == "onboarding.runtime" && it.isAttachedToWindow && it.isShown }.single()
            val modal = nativeViews.filter { tag(it) == "onboarding.surface" && it.isAttachedToWindow && it.isShown }.single()
            assertEquals("The retained Runtime must enforce the real native accessibility fence",
                android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, runtime.importantForAccessibility)
            val menu = nativeViews.filter { tag(it) == "guard.pause-menu" && beneath(it, runtime) &&
                it.isAttachedToWindow && it.isShown }.single()
            val paint = nativeViews.filter { beneath(it, menu) && it.isAttachedToWindow && it.isShown &&
                it.createAccessibilityNodeInfo().className?.toString() == "android.widget.Button" }.single()
            assertFalse("Inclusive UiAutomation must not imply ordinary-service access to the measured Guard paint",
                paint.isImportantForAccessibility)
            val primaryView = nativeViews.filter { tag(it) == "onboarding.primary" && beneath(it, modal) &&
                it.isAttachedToWindow && it.isShown && it.createAccessibilityNodeInfo() == target }.single()
            assertTrue("The real modal primary remains exposed to ordinary accessibility services",
                primaryView.isImportantForAccessibility)
        }
        lastClick = label to false
        val metadata = nodeMetadata(target)
        val accepted = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        lastClickTarget = "$label accepted=$accepted $metadata"
        println("LAVA_JOURNEY_CLICK_RESULT $lastClickTarget")
        assertTrue("The exact modal primary accepts its single Open Guard action", accepted)
        SystemClock.sleep(250)
    }

    /** A choice can reveal ONLY its captured shared modal page, never the retained Guard. */
    fun clickOnboardingChoice(host: LavaReactActivity, generation: Long, visit: String, page: Int,
        heading: String, label: String, prefix: Boolean = false) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        fun remaining() = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        fun currentOwner(): Boolean {
            val current = snapshot()
            val setup = current.optJSONObject("onboardingSetup")
            if (current.optString("nativeHostID") != host.nativeHostID ||
                current.optString("generation").toLongOrNull() != generation ||
                setup == null || setup.optString("id") != visit || setup.optInt("page", -1) != page) return false
            var ready = false
            instrumentation.runOnMainSync {
                ready = application.reactHost.currentReactContext?.currentActivity === host &&
                    !host.isFinishing && !host.isDestroyed && host.foreground && host.hasWindowFocus() &&
                    application.foregroundProduct.state.value.graphGeneration == generation &&
                    host.isPresentationReady(generation, "onboarding:$visit")
            }
            return ready
        }
        await("the exact shared onboarding choice owner and page heading", remaining()) { currentOwner() && visible(heading) }
        fun visibleChoice(): AccessibilityNodeInfo? {
            for (named in nodes().filter { matches(it, label, prefix) }) {
                if (!named.refresh() || !matches(named, label, prefix)) continue
                var target = named
                var depth = 0
                while (!target.isClickable && target.parent != null && depth++ < 32) target = target.parent
                if (target.refresh() && target.isClickable && target.isVisibleToUser &&
                    target.windowId == named.windowId && target.packageName?.toString() == context.packageName &&
                    isOnboardingModalTarget(target, host, scrollViewOnly = false)) return target
            }
            return null
        }
        // A clipped text descendant can be visible while its control is not actionable.
        // A fitted page need not expose a scroll ancestor. Reveal only when its actual
        // clickable control is not already visible in the exact native modal.
        if (visibleChoice() == null) {
            val region = scrollRegionContaining(heading, ::currentOwner, host)
            region.reveal(label, expectedClickTarget = true, prefix = prefix, timeoutMillis = remaining())
        }
        while (SystemClock.elapsedRealtime() < deadline) {
            assertTrue("The shared choice must remain in its original modal owner", currentOwner())
            val target = visibleChoice()?.takeIf { it.isEnabled }
            if (target != null) {
                assertTrue("The exact modal owner must survive target acquisition", currentOwner())
                lastClick = label to prefix
                val metadata = nodeMetadata(target)
                println("LAVA_JOURNEY_CLICK label=$label $metadata")
                val accepted = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                lastClickTarget = "$label accepted=$accepted $metadata"
                println("LAVA_JOURNEY_CLICK_RESULT $lastClickTarget")
                assertTrue("The exact modal choice must accept its single click: $metadata", accepted)
                SystemClock.sleep(250)
                return
            }
            // A disabled/pending choice waits; it must not scroll a backdrop.
            SystemClock.sleep(100)
        }
        fail("The owned shared onboarding choice '$label' did not become actionable")
    }

    /** One real Guard summary action waits for its original native Ready and window. */
    fun clickGuardFiltering(host: LavaReactActivity, generation: Long) =
        clickGuardSummary(host, generation, "guard.filter", "Now filtering")

    fun clickGuardToday(host: LavaReactActivity, generation: Long) =
        clickGuardSummary(host, generation, "guard.today", "Today")

    private fun clickGuardSummary(host: LavaReactActivity, generation: Long, testID: String, label: String) {
        val product = application.foregroundProduct
        val visit = checkNotNull(application.nativeVisitHosts.current())
        val reactContext = checkNotNull(application.reactHost.currentReactContext)
        val surfaceField = LavaReactActivity::class.java.getDeclaredField("protectedPresentation").apply { isAccessible = true }
        val ownerField = LavaReactActivity::class.java.getDeclaredField("presentationOwnerID").apply { isAccessible = true }
        val credentialsField = LavaReactActivity::class.java.getDeclaredField("credentialPresentation").apply { isAccessible = true }
        val deadline = SystemClock.elapsedRealtime() + 20_000L
        var nativeTarget: android.view.View? = null
        fun currentOnMain(): Boolean {
            assertTrue("The Guard action must retain the captured Activity and product owner",
                requireActivity() === host && application.foregroundProduct === product &&
                    visit.owner === host && visit.matches(application.nativeVisitHosts.current()) &&
                    application.reactHost.currentReactContext === reactContext && reactContext.currentActivity === host &&
                    host.nativeHostAlive && !host.isDestroyed && !host.isFinishing)
            assertEquals("The Guard action cannot adopt another credential graph", generation,
                product.state.value.graphGeneration)
            // The route sends normal Ready only after its actual AppStore readers,
            // publication and hydration settle. Painted labels alone precede that ACK.
            return host.foreground && host.hasWindowFocus() && host.isPresentationReady(generation) &&
                surfaceField.get(host) == null && ownerField.get(host) == null && !credentialsField.getBoolean(host) &&
                product.securityStateForSnapshot(generation)?.isAuthorized(ProtectedSurface.APP_UNLOCK) == true
        }
        while (SystemClock.elapsedRealtime() < deadline) {
            val candidates = nodes()
            var target: AccessibilityNodeInfo? = null
            var ready = false
            instrumentation.runOnMainSync {
                ready = currentOnMain()
                if (!ready) return@runOnMainSync
                val views = mutableListOf<android.view.View>()
                fun collect(view: android.view.View) {
                    views += view
                    if (view is android.view.ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
                }
                collect(host.window.decorView)
                val buttons = views.filter { view ->
                    view.getTag(com.facebook.react.R.id.react_test_id) == testID &&
                        view.isAttachedToWindow && view.isShown && view.isEnabled && view.isImportantForAccessibility &&
                        view.createAccessibilityNodeInfo().className?.toString() == "android.widget.Button"
                }
                assertTrue("The captured Guard must expose at most one actual summary for $testID", buttons.size <= 1)
                val button = buttons.singleOrNull() ?: return@runOnMainSync
                val source = button.createAccessibilityNodeInfo()
                val window = host.window.decorView.createAccessibilityNodeInfo().windowId
                val connected = candidates.filter { node ->
                    node == source && node.refresh() && node.windowId == window && window >= 0 &&
                        node.packageName?.toString() == context.packageName && node.isVisibleToUser &&
                        node.isEnabled && node.isClickable && matches(node, label, prefix = true)
                }
                assertTrue("The actual summary button has one connected accessibility source", connected.size <= 1)
                target = connected.singleOrNull()
                if (target != null) {
                    assertTrue("The admitted Guard action cannot adopt a replacement native button",
                        nativeTarget == null || nativeTarget === button)
                    nativeTarget = button
                }
            }
            target?.let { button ->
                var admitted = false
                instrumentation.runOnMainSync {
                    val view = checkNotNull(nativeTarget)
                    admitted = SystemClock.elapsedRealtime() < deadline && currentOnMain() &&
                        view.rootView === host.window.decorView && view.isAttachedToWindow && view.isShown && view.isEnabled &&
                        view.isImportantForAccessibility && button.refresh() &&
                        button == view.createAccessibilityNodeInfo() && button.isVisibleToUser && button.isEnabled &&
                        button.isClickable && button.packageName?.toString() == context.packageName &&
                        button.className?.toString() == "android.widget.Button" && matches(button, label, prefix = true) &&
                        button.windowId == host.window.decorView.createAccessibilityNodeInfo().windowId
                }
                if (admitted) {
                    lastClick = label to true
                    val metadata = nodeMetadata(button)
                    println("LAVA_JOURNEY_CLICK label=$label $metadata")
                    val accepted = button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    lastClickTarget = "$label accepted=$accepted $metadata"
                    println("LAVA_JOURNEY_CLICK_RESULT $lastClickTarget")
                    assertTrue("The original admitted Guard summary button must accept its single action", accepted)
                    SystemClock.sleep(250)
                    return
                }
            }
            // Preserve the original click's forward reveal within this same budget.
            if (ready && target == null) scroll(forward = true)
            SystemClock.sleep(100)
        }
        fail("The original Guard summary button did not become Ready and actionable within twenty seconds")
    }

    fun click(label: String, prefix: Boolean = false, scroll: Boolean = true, excludeEditable: Boolean = false) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (clickIfPresent(label, prefix, excludeEditable)) {
                SystemClock.sleep(250)
                return
            }
            if (scroll) this.scroll(forward = true)
            SystemClock.sleep(100)
        }
        fail("UI action '$label' was not reachable. Visible tree: ${labels()}. " +
            "Targets: ${freshActionMetadata(label, prefix)}. Owner: ${ownerMetadata()}. " +
            "Windows: ${windowMetadata()}. Native snapshot: ${runCatching { snapshot() }.getOrNull()}")
    }

    fun clickNativeDialogButton(
        label: String, requireCurrent: () -> Unit = {},
        beforeClick: () -> Unit = {},
    ) {
        fun dialogButton(label: String, visibleOnly: Boolean = true): AccessibilityNodeInfo? = nodes().singleOrNull {
            it.packageName?.toString() == context.packageName && (!visibleOnly || it.isVisibleToUser) && it.isEnabled &&
                it.className?.toString() == "android.widget.Button" && it.text?.toString()?.equals(label, ignoreCase = true) == true &&
                it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_CLICK }
        }
        fun awaitDialog(stage: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 20_000
            while (SystemClock.elapsedRealtime() < deadline) {
                if (condition()) return
                SystemClock.sleep(100)
            }
            // A credential dialog must never dump arbitrary field text on failure.
            fail("Native dialog '$label' did not reach $stage; action=" +
                dialogButton(label, visibleOnly = false)?.let(::nodeMetadata))
        }
        requireCurrent()
        var target: AccessibilityNodeInfo? = null
        awaitDialog("enabled native dialog button") {
            requireCurrent()
            target = dialogButton(label, visibleOnly = false)
            target != null
        }
        requireCurrent()
        val button = checkNotNull(target)
        assertTrue(button.refresh())
        val neededReveal = !button.isVisibleToUser
        if (neededReveal && instrumentation.getUiAutomation(uiAutomationFlags).windows.any {
                it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
            }) {
            // A normal Back dismisses the shown IME before the dialog action is revealed.
            // Keep the captured button/window: if Back dismisses the dialog instead, this
            // assertion fails rather than reacquiring another prompt or submitting a code.
            requireCurrent()
            back()
            requireCurrent()
            assertTrue("The original native dialog action must survive keyboard dismissal", button.refresh())
        }
        if (neededReveal) {
            // Android deliberately places long stacked AlertDialog actions inside
            // a native ScrollView. Reveal this exact button through public AX;
            // never click offscreen, scroll the RN backdrop, or adopt a new dialog.
            val reveal = AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id
            assertTrue("The exact native dialog action supports accessible reveal: ${nodeMetadata(button)}", button.actionList.any { it.id == reveal })
            requireCurrent()
            // A concurrent IME resize can make SHOW_ON_SCREEN a no-op (false).
            // Its return alone is not the visibility oracle: keep this exact button
            // and require fresh, settled visible bounds before its single click.
            val revealAccepted = button.performAction(reveal)
            var previousBounds: Rect? = null
            awaitDialog("same native dialog button revealed and settled (revealAccepted=$revealAccepted)") {
                requireCurrent()
                assertTrue("The captured native dialog action retired during reveal", button.refresh())
                val visible = dialogButton(label)
                val bounds = Rect().also(button::getBoundsInScreen)
                val settled = visible == button && button.isVisibleToUser && !bounds.isEmpty && previousBounds == bounds
                previousBounds = bounds
                settled
            }
        }
        assertTrue("The refreshed exact native button remains enabled", dialogButton(label) == button)
        requireCurrent()
        beforeClick()
        assertTrue("The actual dialog button accepts its one native click", button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        requireCurrent()
    }

    fun waitLabel(label: String, prefix: Boolean = false, excludeEditable: Boolean = false) {
        await("UI label '$label'") { findLabel(label, prefix, excludeEditable) != null }
    }

    /** Invoke one existing named action on its real labelled control, without navigating natively. */
    fun namedAccessibilityAction(label: String, actionLabel: String, prefix: Boolean = false) {
        var target: AccessibilityNodeInfo? = null
        var actionId: Int? = null
        await("the existing '$actionLabel' accessibility action on '$label'") {
            val candidates = nodes().filter { node ->
                node.packageName?.toString() == context.packageName && node.isEnabled &&
                    matches(node, label, prefix) && node.actionList.any { it.label?.toString() == actionLabel }
            }
            assertTrue("Ambiguous existing named action '$actionLabel' on '$label'", candidates.size <= 1)
            val live = candidates.singleOrNull() ?: return@await false
            if (!live.refresh() || !matches(live, label, prefix) || !live.isEnabled) return@await false
            val action = live.actionList.singleOrNull { it.label?.toString() == actionLabel } ?: return@await false
            target = live
            actionId = action.id
            true
        }
        val liveTarget = checkNotNull(target)
        val accepted = liveTarget.performAction(checkNotNull(actionId))
        println("LAVA_UI_NAMED_ACTION label=$label action=$actionLabel window=${liveTarget.windowId} identity=${liveTarget.hashCode()} accepted=$accepted")
        assertTrue("The real control refused its one '$actionLabel' accessibility action", accepted)
        SystemClock.sleep(250)
    }

    fun waitSelection(label: String, selected: Boolean, prefix: Boolean = false) {
        try {
            await("UI selection '$label' = $selected") {
                val row = findLabel(label, prefix, excludeEditable = true) ?: return@await false
                // API 28 can keep the pre-action checkbox state in its accessibility cache.
                // Read the live row for both the baseline and outcome; a retired node is
                // reacquired by this wait without sending another accessibility action.
                if (!row.refresh() || !matches(row, label, prefix) || row.isEditable) return@await false
                row.isSelected == selected
            }
        } catch (failure: AssertionError) {
            throw AssertionError("${failure.message}. Targets: ${actionMetadata(label, prefix)}", failure)
        }
    }

    fun waitEnabled(label: String, enabled: Boolean, prefix: Boolean = false): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        await("UI enabled '$label' = $enabled") {
            val row = findLabel(label, prefix, excludeEditable = true) ?: return@await false
            if (!row.refresh() || !matches(row, label, prefix) || row.isEditable) return@await false
            if (row.isEnabled != enabled) return@await false
            result = row
            true
        }
        return checkNotNull(result)
    }

    fun waitProtectionOff() {
        // Back restores the Guard scroll position; bring its hero back into the visible tree.
        scrollToTop()
        // The shared hero merges its children into one semantic node. React Native appends
        // accessibilityValue.text after "Protection status" in Android's content description.
        await("the shared Guard hero reporting protection off") {
            nodes().any { node ->
                node.isVisibleToUser && node.contentDescription?.toString()?.let { description ->
                    description.startsWith("Protection status,", ignoreCase = true) &&
                        (description.contains("Protection is off", ignoreCase = true) ||
                            description.contains("Protection off", ignoreCase = true))
                } == true
            }
        }
    }

    fun visible(label: String, prefix: Boolean = false): Boolean = findLabel(label, prefix) != null

    /** Capture the nearest real scroll ancestor, so native pages cannot scroll the RN backdrop. */
    fun scrollRegionContaining(label: String, isCurrent: (() -> Boolean)? = null,
        onboardingHost: LavaReactActivity? = null): AccessibilityScrollRegion {
        assertTrue("The captured scroll owner must be current", isCurrent?.invoke() != false)
        val candidates = nodes().filter { matches(it, label, prefix = false) &&
            it.packageName?.toString() == context.packageName }.mapNotNull { named ->
            var parent = named.parent
            while (parent != null) {
                val current = parent
                if (!current.refresh() || current.packageName?.toString() != context.packageName) break
                // A fitted onboarding page still has its native modal ScrollView, but
                // Android advertises no scroll actions when its content fits. Capture
                // that exact physical owner; reveal returns without scrolling when the
                // actual clickable target is already visible inside its viewport.
                val scrollOwner = if (onboardingHost != null)
                    isOnboardingModalScrollTarget(current, onboardingHost) else current.isScrollable
                if (scrollOwner && current.isVisibleToUser) return@mapNotNull current
                parent = current.parent
            }
            null
        }.distinct().filter { onboardingHost == null || isOnboardingModalScrollTarget(it, onboardingHost) }
        assertEquals("'$label' must identify one actual visible scroll region", 1, candidates.size)
        val target = candidates.single()
        if (onboardingHost != null && isCurrent != null) {
            val bounds = Rect().also(target::getBoundsInScreen)
            println("LAVA_ONBOARDING_MEASUREMENT scrollTarget=${target.hashCode()} window=${target.windowId} " +
                "ownedModal=true viewport=$bounds")
        }
        return AccessibilityScrollRegion(target, label, isCurrent, onboardingHost)
    }

    /** Match the AX source to a real native ScrollView INSIDE this host's actual modal leaf. */
    private fun isOnboardingModalScrollTarget(target: AccessibilityNodeInfo, host: LavaReactActivity): Boolean =
        isOnboardingModalTarget(target, host, scrollViewOnly = true)

    private fun isOnboardingModalTarget(target: AccessibilityNodeInfo, host: LavaReactActivity,
        scrollViewOnly: Boolean): Boolean {
        var owned = false
        instrumentation.runOnMainSync {
            if (application.reactHost.currentReactContext?.currentActivity !== host ||
                host.isFinishing || host.isDestroyed || !host.foreground || !host.hasWindowFocus()) return@runOnMainSync
            val surfaces = mutableListOf<android.view.View>()
            fun surfacesIn(view: android.view.View) {
                if (view.getTag(com.facebook.react.R.id.react_test_id) == "onboarding.surface" &&
                    view.isAttachedToWindow && view.isShown) surfaces += view
                if (view is android.view.ViewGroup) for (index in 0 until view.childCount) surfacesIn(view.getChildAt(index))
            }
            surfacesIn(host.window.decorView)
            if (surfaces.size != 1) return@runOnMainSync
            fun matchingTarget(view: android.view.View): Boolean {
                if ((!scrollViewOnly || view is android.widget.ScrollView) && view.isAttachedToWindow && view.isShown &&
                    view.createAccessibilityNodeInfo() == target && view.getGlobalVisibleRect(Rect())) return true
                if (view is android.view.ViewGroup) for (index in 0 until view.childCount) {
                    if (matchingTarget(view.getChildAt(index))) return true
                }
                return false
            }
            owned = matchingTarget(surfaces.single())
        }
        return owned
    }

    inner class AccessibilityScrollRegion internal constructor(
        private val target: AccessibilityNodeInfo,
        private val anchor: String,
        private val isCurrent: (() -> Boolean)? = null,
        private val onboardingHost: LavaReactActivity? = null,
    ) {
        private val scrollHost = onboardingHost ?: requireActivity()
        private var nativeScrollView: android.widget.ScrollView? = null
        /** Readiness only: retain this exact AX/physical owner without submitting a scroll. */
        fun isVisibleOnCurrentHost(): Boolean {
            assertTrue("The captured scroll owner retired", isCurrent?.invoke() != false)
            if (!target.refresh() || !target.isVisibleToUser) return false
            return nativeOffset() != null
        }

        /** Match the public AX source to the same physical native scroller; never move it directly. */
        private fun nativeOffset(): Int? {
            var offset: Int? = null
            instrumentation.runOnMainSync {
                assertTrue("The captured native scroll host retired",
                    (onboardingHost != null || requireActivity() === scrollHost) &&
                    application.reactHost.currentReactContext?.currentActivity === scrollHost &&
                    scrollHost.nativeHostAlive && scrollHost.foreground && !scrollHost.isDestroyed && !scrollHost.isFinishing)
                val matches = mutableListOf<android.widget.ScrollView>()
                fun walk(view: android.view.View) {
                    if (view is android.widget.ScrollView && view.isAttachedToWindow && view.isShown &&
                        view.createAccessibilityNodeInfo() == target) matches += view
                    if (view is android.view.ViewGroup) repeat(view.childCount) { walk(view.getChildAt(it)) }
                }
                walk(scrollHost.window.decorView)
                assertTrue("The native scroll source must be unique", matches.size <= 1)
                val current = matches.singleOrNull()
                if (nativeScrollView != null) assertTrue("The captured physical scroll source retired", current === nativeScrollView)
                if (current != null) { nativeScrollView = current; offset = current.scrollY }
            }
            return offset
        }

        /** One real AX scroll per step, followed by measured progress in this exact container. */
        fun reveal(label: String, forward: Boolean = true, expectedButtonRole: Boolean = false,
            prefix: Boolean = false, timeoutMillis: Long = 20_000, expectedClickTarget: Boolean = false,
            absoluteDeadlineMillis: Long? = null, ready: (() -> Boolean)? = null) {
            val revealDeadline = absoluteDeadlineMillis ?: (SystemClock.elapsedRealtime() + timeoutMillis)
            fun withinDeadline() = absoluteDeadlineMillis == null || SystemClock.elapsedRealtime() < revealDeadline
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            var submitted = 0
            // Compose exposes clipped virtual descendants and can accept ScrollBy without
            // delivering TYPE_VIEW_SCROLLED to UiAutomation. Measure those real descendants;
            // acceptance alone never permits the next action.
            data class Measurement(
                val viewport: Rect,
                val contents: Map<AccessibilityNodeInfo, Rect>,
                val nativeOffset: Int?,
            )
            fun measure(): Measurement {
                assertTrue("The captured scroll owner retired", isCurrent?.invoke() != false)
                assertTrue("The captured onboarding scroll target left its original modal",
                    onboardingHost == null || isOnboardingModalScrollTarget(target, onboardingHost))
                assertTrue("The captured native '$anchor' scroll region retired", target.refresh() && target.isVisibleToUser)
                val offset = nativeOffset()
                val currentNodes = if (offset != null) {
                    // Native suites can expose hundreds of rows. Search their actual AX
                    // subtree in the app process, then refresh only matching candidates.
                    // The exact physical source above proves this captured target is live;
                    // refreshing every node in every window costs the reveal's whole budget.
                    target.findAccessibilityNodeInfosByText(label).filter { it.refresh() }
                } else {
                    // Virtual scrollers have no physical offset: retain full descendant
                    // geometry as their independent progress oracle.
                    nodes().also { current ->
                        assertTrue("The captured virtual '$anchor' scroll region retired", current.any { it == target })
                    }
                }
                val viewport = Rect().also(target::getBoundsInScreen)
                val contents = currentNodes.mapNotNull { node ->
                    if (node == target || !node.isVisibleToUser || node.windowId != target.windowId ||
                        node.packageName?.toString() != context.packageName ||
                        (node.text.isNullOrEmpty() && node.contentDescription.isNullOrEmpty() &&
                            !node.isEditable && !node.isClickable && !node.isCheckable)) return@mapNotNull null
                    var ancestor = node.parent
                    var depth = 0
                    while (ancestor != null && ancestor != target && depth < 32) {
                        ancestor = ancestor.parent
                        depth += 1
                    }
                    if (ancestor != target) return@mapNotNull null
                    val bounds = Rect().also(node::getBoundsInScreen)
                    if (!bounds.intersect(viewport) || bounds.isEmpty) return@mapNotNull null
                    node to bounds
                }.toMap()
                return Measurement(viewport, contents, offset)
            }
            fun geometry(contents: Map<AccessibilityNodeInfo, Rect>): String = contents.entries
                .sortedWith(compareBy({ it.value.top }, { it.value.left }))
                .take(12).joinToString(";") { (node, bounds) -> "${node.hashCode()}@$bounds" }
            fun progress(before: Map<AccessibilityNodeInfo, Rect>, after: Map<AccessibilityNodeInfo, Rect>, viewport: Rect): String? {
                val shared = before.keys.intersect(after.keys)
                val deltas = shared.map { before.getValue(it).centerY() - after.getValue(it).centerY() }
                val directional = deltas.any { if (forward) it > 0 else it < 0 }
                val contrary = deltas.any { if (forward) it < 0 else it > 0 }
                if (directional && !contrary) return "directional-geometry"
                // A full-page Compose scroll can remove clipped virtual nodes altogether.
                // Require content to leave the departing edge AND enter the arriving edge,
                // with no surviving descendant moving against the requested direction.
                val left = before.filterKeys { it !in after }.values
                val entered = after.filterKeys { it !in before }.values
                val departedEdge = left.any { if (forward) it.centerY() < viewport.centerY() else it.centerY() > viewport.centerY() }
                val arrivedEdge = entered.any { if (forward) it.centerY() > viewport.centerY() else it.centerY() < viewport.centerY() }
                return if (!contrary && departedEdge && arrivedEdge) "edge-semantics" else null
            }
            var previousBefore: Measurement? = null
            fun visibleClickTarget(named: AccessibilityNodeInfo, viewport: Rect): Boolean {
                var candidate: AccessibilityNodeInfo? = named
                var depth = 0
                // Match the click helper's nearest clickable ancestor, but keep it inside
                // the captured scroller. A visible label alone cannot prove its control.
                while (candidate != null && candidate != target && depth < 32) {
                    val current = candidate
                    if (!current.refresh() || current.windowId != target.windowId ||
                        current.packageName?.toString() != context.packageName) return false
                    if (current.isClickable) {
                        val bounds = Rect().also(current::getBoundsInScreen)
                        return current.isVisibleToUser && bounds.intersect(viewport) && !bounds.isEmpty
                    }
                    candidate = current.parent
                    depth += 1
                }
                return false
            }
            await("the native '$anchor' scroll region reveals the exact requested label",
                minOf(timeoutMillis, revealDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)) {
                if (ready?.invoke() == false) return@await false
                val before = measure()
                val visible = before.contents.keys.any { matches(it, label, prefix = prefix) &&
                    (!expectedButtonRole || it.className?.toString() == "android.widget.Button") &&
                    (!expectedClickTarget || visibleClickTarget(it, before.viewport)) }
                if (visible) ready?.invoke() != false && withinDeadline() else if (target.actionList.none { it.id == action }) {
                    // At a real boundary, allow normal AX hydration without another action.
                    false
                } else if (previousBefore != before) {
                    // Search/IME hydration must settle before its geometry and exact native offset become a baseline.
                    previousBefore = before
                    false
                } else {
                    assertTrue("Native '$anchor' navigation exceeded twelve actual scroll steps", submitted < 12)
                    println("LAVA_UI_QA_SCROLL_BEGIN anchor=$anchor window=${target.windowId} identity=${target.hashCode()} class=${target.className} forward=$forward submitted=$submitted bounds=${before.viewport} count=${before.contents.size} geometry=${geometry(before.contents)}")
                    val beforeNativeOffset = before.nativeOffset
                    if (ready?.invoke() == false || !withinDeadline()) return@await false
                    assertTrue("Native '$anchor' refused its one scroll action", target.performAction(action))
                    submitted += 1
                    val progressDeadline = SystemClock.elapsedRealtime() + 1_500
                    val deadline = if (onboardingHost == null && absoluteDeadlineMillis == null) progressDeadline
                        else minOf(progressDeadline, revealDeadline)
                    var previousAfter: Measurement? = null
                    var previousNativeOffset: Int? = null
                    var after = before
                    var proof: String? = null
                    while (SystemClock.elapsedRealtime() < deadline) {
                        val afterNativeOffset = nativeOffset()
                        val nativeDelta = if (beforeNativeOffset != null && afterNativeOffset != null) afterNativeOffset - beforeNativeOffset else null
                        if (nativeDelta != null && (if (forward) nativeDelta > 0 else nativeDelta < 0)) {
                            // Large QA suites expose hundreds of AX descendants. Observe the same
                            // physical scroller settling before one fresh AX read, instead of spending
                            // the animation deadline repeatedly traversing the entire remote tree.
                            if (previousNativeOffset == afterNativeOffset) {
                                after = measure()
                                assertEquals("Native '$anchor' changed viewport during its scroll", before.viewport, after.viewport)
                                if (nativeOffset() == afterNativeOffset) { proof = "exact-native-offset"; break }
                            }
                        } else if (beforeNativeOffset == null) {
                            // Only virtual scrollers need remote descendant sampling while
                            // animating. A large native suite can take the entire animation
                            // budget to traverse; reading it before the first native frame
                            // would miss the offset change that happens during that traversal.
                            after = measure()
                            assertEquals("Native '$anchor' changed viewport during its scroll", before.viewport, after.viewport)
                            proof = progress(before.contents, after.contents, before.viewport)
                            if (proof != null && previousAfter == after && previousNativeOffset == afterNativeOffset) break
                            proof = null
                            previousAfter = after
                        }
                        previousNativeOffset = afterNativeOffset
                        SystemClock.sleep(100)
                    }
                    println("LAVA_UI_QA_SCROLL_PROGRESS anchor=$anchor window=${target.windowId} identity=${target.hashCode()} forward=$forward submitted=$submitted proof=${proof ?: "none"} count=${after.contents.size} geometry=${geometry(after.contents)}")
                    assertNotNull("Native '$anchor' accepted a scroll without settled measured progress; nativeBefore=$beforeNativeOffset nativeAfter=$previousNativeOffset before=${geometry(before.contents)} after=${geometry(after.contents)}", proof)
                    previousBefore = null
                    false
                }
            }
        }
    }

    fun setText(label: String, value: String) {
        var field: AccessibilityNodeInfo? = null
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val candidates = editableFields(label)
            assertTrue("Ambiguous actual editable field '$label': ${candidates.joinToString { editableMetadata(it, label) }}", candidates.size <= 1)
            val candidate = candidates.singleOrNull()
            // Revalidate the exact label association and action after refreshing the target.
            if (candidate != null && candidate.refresh() && editableFields(label).singleOrNull() == candidate) {
                field = candidate
                break
            }
            SystemClock.sleep(100)
        }
        if (field == null) {
            dumpEditableFieldDiagnostics(label, "lookup-timeout")
            fail("Timed out waiting for the exact editable UI field '$label'. ${windowMetadata()}")
        }
        val target = checkNotNull(field)
        println("LAVA_JOURNEY_SET_TEXT_TARGET label=$label ${editableMetadata(target, label)}")
        val accepted = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        })
        println("LAVA_JOURNEY_SET_TEXT_RESULT label=$label accepted=$accepted")
        if (!accepted) dumpEditableFieldDiagnostics(label, "action-refused")
        assertTrue("Actual editable UI field '$label' refused its one ACTION_SET_TEXT", accepted)
        // The native control emits its change event to its actual UI owner asynchronously.
        SystemClock.sleep(200)
    }

    /** Static-label metadata only: never print entered text, arbitrary hint/description or payload. */
    fun dumpEditableFieldDiagnostics(label: String, stage: String) {
        val current = nodes().filter { it.packageName?.toString() == context.packageName && it.isVisibleToUser }
        val relevant = current.filter { it.isEditable || hasSetText(it) || editableLabelMatches(it, label) }.take(20)
        println("LAVA_JOURNEY_EDITABLE_FIELDS stage=$stage label=$label relevant=${relevant.size}")
        relevant.forEachIndexed { index, node ->
            println("LAVA_JOURNEY_EDITABLE_FIELD stage=$stage index=$index ${editableMetadata(node, label)}")
            if (editableLabelMatches(node, label) && !node.isEditable) {
                var parent = node.parent
                var depth = 0
                while (parent != null && depth < 6) {
                    val ancestor = parent
                    if (!ancestor.refresh()) break
                    println("LAVA_JOURNEY_EDITABLE_ANCESTOR stage=$stage index=$index depth=$depth ${editableMetadata(ancestor, label)}")
                    if (ancestor.isEditable) break
                    parent = ancestor.parent
                    depth += 1
                }
            }
        }
    }

    private fun editableFields(label: String): List<AccessibilityNodeInfo> {
        val current = nodes().filter { it.packageName?.toString() == context.packageName && it.isVisibleToUser }
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        for (named in current.filter { editableLabelMatches(it, label) }) {
            var target: AccessibilityNodeInfo? = named
            var depth = 0
            // Material's label is a separate semantic child; only its own nearest editable
            // ancestor may receive text. Never infer an unrelated field from sibling/geometry.
            while (target != null && depth <= 6) {
                val candidate = target
                if (!candidate.refresh() || !candidate.isVisibleToUser || candidate.packageName?.toString() != context.packageName) break
                if (candidate.isEditable) {
                    if (candidate.isEnabled && hasSetText(candidate)) candidates += candidate
                    break
                }
                target = candidate.parent
                depth += 1
            }
        }
        return candidates.distinct()
    }

    private fun hasSetText(node: AccessibilityNodeInfo): Boolean =
        node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }

    private fun editableLabelMatches(node: AccessibilityNodeInfo, label: String): Boolean =
        listOf(node.text, node.contentDescription, if (Build.VERSION.SDK_INT >= 26) node.hintText else null)
            .any { it?.toString()?.equals(label, ignoreCase = true) == true }

    private fun editableMetadata(node: AccessibilityNodeInfo, label: String): String {
        fun safeLabel(value: CharSequence?): String = when {
            value.isNullOrEmpty() -> "<empty>"
            value.toString().equals(label, ignoreCase = true) -> label
            else -> "<other-redacted>"
        }
        val bounds = Rect().also(node::getBoundsInScreen)
        val hint = if (Build.VERSION.SDK_INT >= 26) safeLabel(node.hintText) else "<unsupported>"
        return "identity=${node.hashCode()}, window=${node.windowId}, class=${node.className}, " +
            "editable=${node.isEditable}, enabled=${node.isEnabled}, visible=${node.isVisibleToUser}, " +
            "hint=$hint, contentDesc=${safeLabel(node.contentDescription)}, textLabelMatch=${node.text?.toString()?.equals(label, ignoreCase = true) == true}, " +
            "resourceId=${node.viewIdResourceName}, setText=${hasSetText(node)}, bounds=$bounds, " +
            "actions=${node.actionList.joinToString { it.id.toString() }}"
    }

    fun back() {
        val automation = instrumentation.getUiAutomation(uiAutomationFlags)
        fun permitted(root: AccessibilityNodeInfo): Boolean = root.refresh() &&
            (isJourneyWindow(root) || root.packageName?.toString() in setOf(
                // These real external windows are explicitly acquired by the Guard and card tests.
                "com.android.settings", "com.android.intentresolver",
            ))
        val applications = automation.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        // Prefer actual focus to API 28's cached active root; never target a permitted backdrop
        // while a different application owns the foreground window.
        val foreground = applications.filter { it.isFocused }
            .ifEmpty { applications.filter { it.isActive } }
        val inScope = if (foreground.isNotEmpty()) foreground.all { window ->
            window.root?.let(::permitted) == true
        } else automation.rootInActiveWindow?.let(::permitted) == true
        assertTrue("Back requires an owned app or permitted system window. ${windowMetadata()}", inScope)

        val downTime = SystemClock.uptimeMillis()
        fun event(action: Int) = KeyEvent(downTime, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_BACK, 0)
            .apply { source = InputDevice.SOURCE_KEYBOARD }
        // UiAutomation's public API can return from the OS chooser/Settings without granting
        // INJECT_EVENTS to the app. Submit exactly one pair and release before checking acceptance.
        var upAccepted = false
        val downAccepted = try {
            automation.injectInputEvent(event(KeyEvent.ACTION_DOWN), true)
        } finally {
            upAccepted = automation.injectInputEvent(event(KeyEvent.ACTION_UP), true)
        }
        assertTrue("The scoped Back key-down must be accepted", downAccepted)
        assertTrue("The scoped Back key-up must be accepted", upAccepted)
        SystemClock.sleep(350)
    }

    fun scrollToTop() {
        repeat(12) { if (!scroll(forward = false)) return }
    }

    fun assertNoFallback() {
        val text = labels()
        assertFalse("A production route reached an unavailable placeholder: $text", text.contains("Coming to Android"))
        assertFalse("The shipping surface used a UI preview: $text", text.contains("UI review build"))
        assertFalse("The Hermes surface failed: $text", text.contains("The full Lava runtime is missing"))
    }

    /** Failure-only, text-free physical evidence from this journey's original Activity. */
    private fun nativeFailureFrameMetadata(): String {
        // Capture this journey's Activity before posting; a late scan cannot borrow a reopened host.
        val host = activity
        val result = AtomicReference<String?>(null)
        val active = AtomicBoolean(true)
        val done = CountDownLatch(1)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val capture = Runnable {
            if (!active.get()) return@Runnable
            try {
                val data = JSONObject().put("schema", 1).put("originalActivityPresent", host != null)
                if (host != null) {
                    val context = application.reactHost.currentReactContext
                    val current = application.nativeVisitHosts.current()
                    val delegate = host.reactDelegate
                    val root = delegate?.reactRootView
                    val content = host.findViewById<android.view.ViewGroup>(android.R.id.content)
                    data.put("originalNativeHostID", host.nativeHostID)
                        .put("currentNativeHostID", current?.nativeHostID ?: JSONObject.NULL)
                        .put("currentNativeOwnerIsOriginal", current?.owner === host)
                        .put("reactActivityIsOriginal", context?.currentActivity === host)
                        .put("originalResumed", ActivityLifecycleMonitorRegistry.getInstance()
                            .getActivitiesInStage(Stage.RESUMED).any { it === host })
                        .put("nativeHostAlive", host.nativeHostAlive).put("foreground", host.foreground)
                        .put("finishing", host.isFinishing).put("destroyed", host.isDestroyed)
                        .put("windowFocused", host.hasWindowFocus())
                        .put("reactContextPresent", context != null)
                        .put("reactLifecycle", context?.lifecycleState?.name ?: JSONObject.NULL)
                        .put("delegatePresent", delegate != null).put("delegateRootPresent", root != null)
                        .put("contentPresent", content != null)
                    root?.let {
                        // Public RootView attachment/instance facts, not a Surface.start completion claim.
                        data.put("delegateRootAttachedToReactInstance", it.isViewAttachedToReactInstance())
                            .put("delegateRootHasActiveReactContext", it.hasActiveReactContext())
                            .put("delegateRootHasActiveReactInstance", it.hasActiveReactInstance())
                            .put("delegateRootContextIsCurrent", it.currentReactContext === context)
                    }
                    val rows = JSONArray()
                    var truncated = false
                    fun visit(view: android.view.View, depth: Int, path: String) {
                        if (rows.length() >= 40) { truncated = true; return }
                        val group = view as? android.view.ViewGroup
                        val marker = view.getTag(com.facebook.react.R.id.react_test_id)
                        val rootBoundary = marker?.takeIf { it == "lava-window-viewport" || it == "lava-render-frame" }
                        rows.put(JSONObject().put("path", path).put("class", view.javaClass.name.take(160))
                            .put("rootBoundary", rootBoundary ?: JSONObject.NULL)
                            .put("attached", view.isAttachedToWindow).put("width", view.width)
                            .put("height", view.height).put("measuredWidth", view.measuredWidth).put("measuredHeight", view.measuredHeight)
                            .put("left", view.left).put("top", view.top).put("layoutRequested", view.isLayoutRequested)
                            .put("layoutWidth", view.layoutParams?.width ?: JSONObject.NULL)
                            .put("layoutHeight", view.layoutParams?.height ?: JSONObject.NULL)
                            .put("measurementTrace", (view as? NativeMeasuredReactView)?.measurementTrace() ?: JSONObject.NULL)
                            .put("visibility", view.visibility).put("alpha", view.alpha)
                            .put("importantForAccessibility", view.importantForAccessibility)
                            .put("children", group?.childCount ?: 0).put("isDelegateRoot", view === root))
                        if (group != null) {
                            if (depth >= 4) { if (group.childCount > 0) truncated = true; return }
                            for (index in 0 until group.childCount) {
                                if (rows.length() >= 40) { truncated = true; break }
                                visit(group.getChildAt(index), depth + 1, "$path/$index")
                            }
                        }
                    }
                    content?.let { visit(it, 0, "content") }
                    data.put("viewRows", rows).put("viewRowsTruncated", truncated)
                        .put("maxDepth", 4).put("maxRows", 40)
                }
                if (active.get()) result.compareAndSet(null, data.toString())
            } catch (failure: Throwable) {
                // Preserve the original assertion; exception messages can contain private data.
                if (active.get()) result.compareAndSet(null,
                    "capture-failed:${failure.javaClass.simpleName.take(80)}")
            } finally {
                done.countDown()
            }
        }
        try {
            if (!main.post(capture)) result.compareAndSet(null, "capture-post-refused")
            else if (!done.await(250, TimeUnit.MILLISECONDS)) {
                result.compareAndSet(null, "capture-timeout")
            }
        } catch (failure: Throwable) {
            result.compareAndSet(null, "capture-wait-failed:${failure.javaClass.simpleName.take(80)}")
        } finally {
            active.set(false)
            main.removeCallbacks(capture)
        }
        // A timed-out/cancelled runnable cannot replace the terminal scalar or touch a new Activity.
        return result.get() ?: "capture-not-completed"
    }

    fun await(message: String, timeoutMillis: Long = 20_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        fail("Timed out waiting for $message. Visible tree: ${labels()}. " +
            "Onboarding error: ${application.foregroundProduct.state.value.onboarding?.error}. " +
            "Owner: ${ownerMetadata()}. Windows: ${windowMetadata()}. " +
            "Last click: $lastClickTarget. Fresh targets: ${lastClick?.let { (label, prefix) -> freshActionMetadata(label, prefix) }}. " +
            "Native frame: ${nativeFailureFrameMetadata()}. " +
            "Native snapshot: ${runCatching { snapshot() }.getOrNull()}")
    }

    override fun close() = finishActivity()

    private fun finishActivity() {
        val current = activity ?: return
        instrumentation.runOnMainSync { current.finish() }
        // Main-queue idle can precede the asynchronous stop/destroy transaction. Retain this
        // exact instance until destruction, so reopen cannot race its finishing task record.
        await("shipping React host ${System.identityHashCode(current)} destroyed") {
            var destroyed = false
            instrumentation.runOnMainSync { destroyed = current.isDestroyed }
            destroyed
        }
        instrumentation.waitForIdleSync()
        activity = null
    }

    private fun module(): LavaAppModule = checkNotNull(
        application.reactHost.currentReactContext?.getNativeModule(LavaAppModule::class.java),
    )

    private fun call(action: (PromiseImpl) -> Unit, description: String = "Native bridge", timeoutSeconds: Long = 20): JSONObject {
        val done = CountDownLatch(1)
        var response: String? = null
        var rejection: String? = null
        action(PromiseImpl(
            Callback { args -> response = args.firstOrNull() as? String; done.countDown() },
            Callback { args -> rejection = args.joinToString(); done.countDown() },
        ))
        if (!done.await(timeoutSeconds, TimeUnit.SECONDS)) {
            // Failure-only public scalar facts: never log command payloads or draft content.
            val state = application.foregroundProduct.state.value
            fail("$description timed out: graph=${state.graphGeneration}, available=${state.productAvailable}, " +
                "preparation=${state.filterPreparation.javaClass.simpleName}")
        }
        assertEquals("Native bridge rejected the request: $rejection", null, rejection)
        assertNotNull("Native bridge returned no JSON", response)
        return JSONObject(checkNotNull(response))
    }

    private fun onboardingPage(): OnboardingPage? =
        application.foregroundProduct.state.value.onboarding?.onboarding?.page

    private fun awaitOnboarding(page: OnboardingPage) {
        await("onboarding owner page $page") { onboardingPage() == page }
        waitLabel(when (page) {
            OnboardingPage.LAVA -> "Meet Lava"
            OnboardingPage.GUARD_INTRO -> "Your local Guard"
            OnboardingPage.FEATURES -> "Private by design"
            OnboardingPage.VPN -> "Allow local VPN configuration"
            OnboardingPage.NOTIFICATIONS -> "Protection notifications"
            OnboardingPage.PROTECTION_LEVEL -> "Choose protection"
            OnboardingPage.CONNECTION_QUALITY -> "Choose DNS behavior"
            OnboardingPage.DONE -> "Ready to protect"
        })
    }

    private fun findLabel(label: String, prefix: Boolean = false, excludeEditable: Boolean = false): AccessibilityNodeInfo? =
        nodes().firstOrNull { matches(it, label, prefix) && (!excludeEditable || !it.isEditable) }

    private fun matches(node: AccessibilityNodeInfo, label: String, prefix: Boolean): Boolean =
        node.isVisibleToUser && listOf(node.text, node.contentDescription).any { value ->
            val actual = value?.toString().orEmpty()
            if (prefix) actual.equals(label, ignoreCase = true) || actual.startsWith("$label,", ignoreCase = true) ||
                actual.startsWith("$label\n", ignoreCase = true)
            else actual.equals(label, ignoreCase = true)
        }

    private fun clickIfPresent(label: String, prefix: Boolean = false, excludeEditable: Boolean = false): Boolean {
        for (node in nodes().filter { matches(it, label, prefix) && (!excludeEditable || !it.isEditable) }) {
            // Reopened API 28 sheets can leave a cached row in the accessibility tree.
            // Refresh the named row before resolving its actual clickable target. A removed
            // node is reacquired by the existing click wait; an accepted action is never retried.
            if (!node.refresh() || !matches(node, label, prefix) || excludeEditable && node.isEditable) continue
            var target = node
            while (!target.isClickable && target.parent != null) target = target.parent
            if (target != node && !target.refresh()) continue
            if ((!excludeEditable || !target.isEditable) && target.isVisibleToUser && target.isEnabled && target.isClickable) {
                lastClick = label to prefix
                val metadata = nodeMetadata(target)
                println("LAVA_JOURNEY_CLICK label=$label $metadata")
                val accepted = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                lastClickTarget = "$label accepted=$accepted $metadata"
                println("LAVA_JOURNEY_CLICK_RESULT $lastClickTarget")
                assertTrue("UI ACTION_CLICK '$label' was refused. Target: $metadata. " +
                    "Fresh targets: ${if (accepted) "not needed" else freshActionMetadata(label, prefix)}", accepted)
                return true
            }
        }
        return false
    }

    private fun scroll(forward: Boolean): Boolean {
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val scrollables = nodes().filter { it.isVisibleToUser && it.isScrollable }
        for (node in scrollables) {
            if (node.performAction(action)) {
                SystemClock.sleep(150)
                return true
            }
        }
        return false
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val walkedRoots = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo): Boolean {
            // Refresh descendants as well as the window root. A Compose page can
            // reuse virtual node IDs while its old text remains cached on API36.
            // A retired child is discarded; no additional click is submitted.
            if (!node.refresh()) return false
            result.add(node)
            repeat(node.childCount) { index -> node.getChild(index)?.let(::visit) }
            return true
        }
        fun visitRoot(root: AccessibilityNodeInfo) {
            // The selected window and rootInActiveWindow normally expose the same public
            // source. Deduplicate before traversal, not after refreshing every child twice.
            // This list is local to one scan; future scans still refresh every descendant.
            if (walkedRoots.any { it.windowId == root.windowId && it == root }) return
            // A failed selected-root refresh must not suppress a genuinely available active
            // fallback. Record only a root whose current subtree was actually visited.
            if (root.refresh() && visit(root)) walkedRoots.add(root)
        }
        val automation = instrumentation.getUiAutomation(uiAutomationFlags)
        val windows = automation.windows.mapNotNull { window ->
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@mapNotNull null
            val root = window.root ?: return@mapNotNull null
            if (!isJourneyWindow(root)) return@mapNotNull null
            window to root
        }
        // Restrict fallback to the active/focused app or system permission window. A paused RN
        // activity behind the native flow must never supply a matching button or route label.
        val selected = windows.filter { (window, _) -> window.isActive || window.isFocused }
            .ifEmpty { windows.sortedByDescending { (window, _) -> window.layer }.take(1) }
        selected.forEach { (_, root) -> visitRoot(root) }
        automation.rootInActiveWindow?.let { active ->
            if (isJourneyWindow(active) &&
                (selected.isEmpty() || selected.any { (window, _) -> window.id == active.windowId })
            ) visitRoot(active)
        }
        return result.distinct()
    }

    private fun isJourneyWindow(root: AccessibilityNodeInfo): Boolean =
        root.packageName?.toString() in setOf(
            context.packageName,
            "android",
            "com.android.vpndialogs",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
        )

    private fun ownerMetadata(): String {
        val state = application.foregroundProduct.state.value
        val owner = state.onboarding
        return "generation=${state.graphGeneration}, page=${owner?.onboarding?.page}, " +
            "completed=${owner?.completed}, commit=${owner?.commitInFlight}, " +
            "reconstruction=${owner?.reconstructionRequired}, " +
            "vpnConsent=${owner?.pendingVpnConsent?.id}, notifications=${owner?.notificationPermissionGranted}"
    }

    private fun actionMetadata(label: String, prefix: Boolean): String =
        nodes().filter { matches(it, label, prefix) }.joinToString(" | ") { node ->
            var target = node
            while (!target.isClickable && target.parent != null) target = target.parent
            "class=${node.className}, editable=${node.isEditable}, selected=${node.isSelected}, " +
                "target=${target.className}, enabled=${target.isEnabled}, " +
                "clickable=${target.isClickable}, selected=${target.isSelected}"
        }

    private fun nodeMetadata(node: AccessibilityNodeInfo): String {
        val bounds = Rect().also(node::getBoundsInScreen)
        return "identity=${node.hashCode()}, window=${node.windowId}, package=${node.packageName}, " +
            "class=${node.className}, viewId=${node.viewIdResourceName}, bounds=$bounds, " +
            "visible=${node.isVisibleToUser}, editable=${node.isEditable}, " +
            "enabled=${node.isEnabled}, clickable=${node.isClickable}, selected=${node.isSelected}, " +
            "actions=${node.actionList.joinToString { action -> "${action.id}:${action.label}" }}"
    }

    private fun freshActionMetadata(label: String, prefix: Boolean): String =
        nodes().filter { matches(it, label, prefix) && !it.isEditable }.joinToString(" | ") { node ->
            val cached = nodeMetadata(node)
            val refreshed = node.refresh()
            "cached=[$cached], refreshed=$refreshed, current=[${nodeMetadata(node)}]"
        }.also { metadata -> println("LAVA_JOURNEY_FRESH_TARGETS label=$label $metadata") }

    private fun windowMetadata(): String {
        val automation = instrumentation.getUiAutomation(uiAutomationFlags)
        val active = automation.rootInActiveWindow
        val activeDescription = "activeRoot=${active?.packageName}/${active?.className}, " +
            "id=${active?.windowId}, children=${active?.childCount}"
        return activeDescription + "; " + automation.windows.joinToString(" | ") { window ->
            val root = window.root
            "id=${window.id}, type=${window.type}, layer=${window.layer}, " +
                "active=${window.isActive}, focused=${window.isFocused}, " +
                "root=${root?.packageName}/${root?.className}, children=${root?.childCount}"
        }
    }

    private fun labels(): String = nodes().filter { it.isVisibleToUser }
        .flatMap { listOfNotNull(it.text?.toString(), it.contentDescription?.toString()) }
        .distinct().joinToString(" | ").take(6_000)
}
