package com.lavasec.app.reactnative

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.view.WindowManager
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.DialogFragment
import com.lavasec.app.MainActivity
import com.lavasec.app.foundation.security.ProtectedSurface
import com.lavasec.app.foundation.preferences.AppearancePreference
import com.lavasec.app.security.SecurityPrivacyPolicy
import com.lavasec.app.security.SecurityPresentationVisit
import com.lavasec.app.security.presentPasscodeAuthentication
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultReactActivityDelegate
import com.lavasec.app.LavasecApplication
import com.lavasec.app.settings.ProductSettingsStore
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Shipping RN foreground. Sensitive/native flows are awaited with explicit cancellation. */
class LavaReactActivity : ReactActivity() {
    // React roots can share one ReactContext while separate Activities are alive.
    // This identity belongs to the instance that physically presents its viewport.
    internal val nativeHostID: String = java.util.UUID.randomUUID().toString()
    internal fun belongsToNativeHost(id: String?): Boolean = id != null && id == nativeHostID
    private var pendingFlow: CancellableContinuation<Int>? = null
    private var pendingDocument: CancellableContinuation<Uri?>? = null
    private var pendingDocumentRequestCode: Int? = null
    private var nextDocumentRequestCode = DOCUMENT_FLOW_REQUEST
    private var foregroundBackHandler: (() -> Boolean)? = null
    private var nativeVisitRetirementHandler: (() -> Unit)? = null
    internal val isOnboardingPreview: Boolean
        get() = com.lavasec.app.BuildConfig.DEBUG && intent?.getBooleanExtra(ONBOARDING_PREVIEW_EXTRA, false) == true
    internal val nativeDeepLinkEntryID: String?
        get() = intent?.getStringExtra(DEEP_LINK_ENTRY_EXTRA)
    private var pendingNotifications: CancellableContinuation<Boolean>? = null
    private var pendingNotificationResult: Boolean? = null
    private val foregroundWaiters = mutableListOf<CancellableContinuation<Boolean>>()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (foreground) finishNotificationRequest(granted)
        else pendingNotificationResult = granted
    }
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var lockCover: ReactAppLockView
    private var unlockJob: Job? = null
    private var presentationGeneration: Long? = null
    private var protectedPresentation: ProtectedSurface? = null
    private var credentialPresentation = false
    private var presentationOwnerID: String? = null
    private var presentationSerial: Long = 0
    private data class SecurityVisitKey(val generation: Long, val surface: ProtectedSurface?,
        val credentials: Boolean, val ownerID: String?)
    private var securityVisitKey: SecurityVisitKey? = null
    @Volatile private var securityVisit: SecurityPresentationVisit? = null
    private val presentationFrames = NativePresentationFrames()
    private val appPrivacyBoundary = NativeAppPrivacyBoundary()
    internal val appPrivacyCoverRequired get() = appPrivacyBoundary.required
    private var interruptedPresentation = false
    private var windowViewport: NativeWindowViewport? = null
    private val presentationReady get() = presentationFrames.normalReady(presentationGeneration,
        product.securityStateForSnapshot(presentationGeneration)?.authenticationRevision)
    private val nativeFieldObservers = mutableSetOf<() -> Unit>()
    internal var wireGuardInputPort: LavaWireGuardInputPort? = null
        private set
    private val product get() = (application as LavasecApplication).foregroundProduct
    // These facts are read by suspended repository publication guards on IO.
    // A lifecycle callback must publish retirement before those guards proceed.
    @Volatile internal var foreground = false
        private set
    @Volatile internal var nativeHostAlive = true
        private set
    private val nativeVisitHost by lazy { NativeVisitHost(this, nativeHostID) { nativeHostAlive } }
    private val nativeVisitHosts get() = (application as? LavasecApplication)?.nativeVisitHosts

    override fun getMainComponentName(): String = COMPONENT_NAME
    override fun createReactActivityDelegate(): ReactActivityDelegate =
        // Seed the stored appearance preference into the first React frame. The
        // preference is ordinary app storage (not credential evidence), so it can
        // be read synchronously before the unlocked product graph exists; the
        // shared AppearanceStore then reconciles it with the live native revision.
        object : DefaultReactActivityDelegate(this, mainComponentName) {
            private var pendingSurfaceStart: NativeMeasuredSurfaceStart? = null

            override fun loadApp(appKey: String?) {
                pendingSurfaceStart?.close()
                pendingSurfaceStart = NativeMeasuredSurfaceStart.install(
                    this@LavaReactActivity, checkNotNull(reactHost), checkNotNull(appKey), composeLaunchOptions(),
                    registerSurface = ::setReactSurface,
                    isCurrent = { nativeHostAlive && !isFinishing && !isDestroyed },
                    onStarted = { pendingSurfaceStart = null },
                )
            }

            override fun onDestroy() {
                pendingSurfaceStart?.close(); pendingSurfaceStart = null
                super.onDestroy()
            }

            // ReactActivity's legacy hardware entry also calls this delegate.
            // Keep the same native-owner priority on API 28 and predictive Back.
            override fun onBackPressed(): Boolean = routeReactBack { super.onBackPressed() }
            override fun getLaunchOptions(): Bundle =
                Bundle().apply {
                    putString(NATIVE_HOST_ID_KEY, nativeHostID)
                    putString(
                        INITIAL_APPEARANCE_KEY,
                        ProductSettingsStore(this@LavaReactActivity).loadPreferences().appearance.wire,
                    )
                }
        }

    override fun attachBaseContext(base: Context) {
        // AppCompat chooses its resource override during attachment, before onCreate.
        // Select the durable mode before that context is created, not by updating a
        // context whose override was already keyed to the previous process mode.
        // Share RN AppearanceModule's process-wide authority so a local Activity override cannot
        // prevent later owner-confirmed changes. This reads presentation storage, mutating no owner.
        AppCompatDelegate.setDefaultNightMode(when (ProductSettingsStore(base).loadPreferences().appearance) {
            AppearancePreference.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            AppearancePreference.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            AppearancePreference.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        })
        super.attachBaseContext(base)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        NativeDeepLinkEntryRegistry.process.attach(nativeDeepLinkEntryID, this, nativeHostID)
        // ReactActivityDelegate reads bridgeless C++ flags before resolving reactHost. Bootstrap
        // their native loader here, while keeping service-only/native processes lazy.
        (application as LavasecApplication).initializeReactNativeRuntime()
        // NOTE: FLAG_SECURE is NOT set unconditionally here. Background concealment follows
        // the explicit Open Lava choice and is applied by renderAppLock() from the SAME
        // SecurityPrivacyPolicy that governs the native cover. Confirmed OFF clears the flag
        // even when scoped gates or credential failures deny reads; unknown stays conservative.
        supportFragmentManager.fragmentFactory = com.swmansion.rnscreens.fragment.restoration.RNScreensFragmentFactory()
        super.onCreate(savedInstanceState)
        applyLavaSystemBars()
        registerForegroundBackCallback()
        val content = findViewById<ViewGroup>(android.R.id.content)
        // ReactActivity installs its root before this point. Constrain that root only;
        // the privacy cover below still spans the complete current window.
        if (content is FrameLayout && content.childCount > 0) {
            val viewport = NativeWindowViewport(content, content.getChildAt(0)) { pane ->
                if (::lockCover.isInitialized) lockCover.usePane(pane, content.width, content.height)
            }
            windowViewport = viewport
            activityScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    WindowInfoTracker.getOrCreate(this@LavaReactActivity).windowLayoutInfo(this@LavaReactActivity).collect { info ->
                        viewport.update(info.displayFeatures.filterIsInstance<FoldingFeature>().map { fold ->
                            NativeWindowFold(android.graphics.Rect(fold.bounds), fold.orientation == FoldingFeature.Orientation.VERTICAL,
                                fold.isSeparating, fold.occlusionType == FoldingFeature.OcclusionType.FULL)
                        })
                    }
                }
            }
        }
        lockCover = ReactAppLockView(this, content, ::unlockApp) {
            activityScope.launch {
                presentNative(Intent(this@LavaReactActivity, MainActivity::class.java)
                    .putExtra(MainActivity.NATIVE_FLOW_ROUTE, "security"))
            }
        }
        content.addView(lockCover, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        renderAppLock()
        activityScope.launch { product.state.collect { renderAppLock() } }
    }

    override fun onResume() {
        // ReactActivity's super callback can resume native consumers. Publish this
        // exact host first; its foreground authority stays false until super returns.
        nativeVisitHosts?.publish(nativeVisitHost)
        super.onResume()
        applyLavaSystemBars()
        foreground = nativeHostAlive
        product.state.value.graphGeneration?.let { generation ->
            product.securityEnterForeground(this, generation,
                securityVisit.takeIf { securityVisitKey?.generation == generation })
        }
        pendingNotificationResult?.let { finishNotificationRequest(it) }
        foregroundWaiters.toList().also { foregroundWaiters.clear() }.forEach { if (it.isActive) it.resume(true) }
        renderAppLock()
        activityScope.launch {
            (application as LavasecApplication).foregroundProduct.refreshCustomizationNotifications()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLavaSystemBars()
    }

    override fun onPause() {
        interruptedPresentation = presentationReady && !appPrivacyCoverRequired
        foreground = false
        presentationFrames.pause()
        unlockJob?.cancel()
        product.state.value.graphGeneration?.let { generation ->
            product.securityPauseForeground(this, generation,
                securityVisit.takeIf { securityVisitKey?.generation == generation })
        }
        renderAppLock()
        super.onPause()
    }

    override fun onStop() {
        interruptedPresentation = false
        val generation = product.state.value.graphGeneration
        appPrivacyBoundary.background(product.securityStateForSnapshot(generation))
        generation?.let { currentGeneration -> product.securitySuspendForeground(this, currentGeneration,
            securityVisit.takeIf { securityVisitKey?.generation == currentGeneration }) }
        renderAppLock()
        super.onStop()
    }

    private fun renderAppLock() {
        if (!::lockCover.isInitialized) return
        val generation = product.state.value.graphGeneration
        val security = product.securityStateForSnapshot(generation)
        appPrivacyBoundary.classify(generation, security)
        val coverRequired = SecurityPrivacyPolicy.requiresBackgroundCover(security)
        // Recents/screenshot concealment shares the explicit Open Lava choice.
        // Scoped protection and credential failures cannot invent display consent.
        if (coverRequired || accountBackupFlow != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        val currentPresentation = presentationGeneration == generation
        val ready = currentPresentation && presentationFrames.paintReady(generation, security?.authenticationRevision)
        appPrivacyBoundary.acknowledge(foreground && ready && security?.isAuthorized(ProtectedSurface.APP_UNLOCK) == true)
        if (ready) interruptedPresentation = false
        lockCover.render(foreground, security, unlockJob?.isActive == true, protectedPresentation, ready, credentialPresentation,
            appCoverRequired = appPrivacyCoverRequired, retainSessionFrame = interruptedPresentation)
        // Retained native editors must react to a cover/ready transition even
        // when Fabric props remain identical. Layout/seeding still happen covered.
        nativeFieldObservers.toList().forEach { it() }
        // RN Alert dialogs use separate fragment windows, outside the covered React root.
        // Only a policy that requires concealment needs to dispose them.
        if (!ReactAppLockView.admits(foreground, security, protectedPresentation, ready, credentialPresentation)) {
            supportFragmentManager.fragments.filterIsInstance<DialogFragment>().forEach {
                it.dismissAllowingStateLoss()
            }
        }
    }

    private fun unlockApp() {
        if (!foreground || unlockJob?.isActive == true) return
        val generation = product.state.value.graphGeneration ?: return
        val visit = securityVisit
        val originalHost = nativeVisitHost
        unlockJob = activityScope.launch {
            try {
                val security = product.securityStateForSnapshot(generation)
                if (security?.availability != com.lavasec.app.security.SecurityAvailability.Available || security.recoveryRequired) {
                    product.securityRetryAvailability(this@LavaReactActivity, generation)
                    return@launch
                }
                product.securityAuthorize(generation, ProtectedSurface.APP_UNLOCK) { _, verify, cancel ->
                    presentPasscodeAuthentication(verify, cancel)
                }
                // The displayed page still owns its original route and draft. Only
                // its current native grant may uncover that retained content.
                if (foreground && originalHost.matches(nativeVisitHosts?.current()) && securityVisit === visit &&
                    presentationGeneration == generation && product.state.value.graphGeneration == generation &&
                    product.securityStateForSnapshot(generation)?.isAuthorized(ProtectedSurface.APP_UNLOCK) == true) {
                    if (credentialPresentation) {
                        product.securityAuthorize(generation, null) { _, verify, cancel -> presentPasscodeAuthentication(verify, cancel) }
                    } else protectedPresentation?.let { surface ->
                        product.securityAuthorize(generation, surface) { _, verify, cancel ->
                            presentPasscodeAuthentication(verify, cancel)
                        }
                    }
                }
            } finally {
                if (unlockJob === coroutineContext[Job]) unlockJob = null
                renderAppLock()
            }
        }
        renderAppLock()
    }

    /** Concealment-only route update, fenced to the live native graph. */
    internal fun setProtectedPresentation(generation: Long, serial: Long, surface: ProtectedSurface?, credentials: Boolean = false, ownerID: String? = null, readRevision: Long? = null): Boolean {
        if (!nativeHostAlive || !foreground || product.state.value.graphGeneration != generation ||
            !presentationFrames.publish(generation, serial, publicRetry = false, foreground = foreground,
                readRevision = readRevision, currentReadRevision = product.securityStateForSnapshot(generation)?.authenticationRevision)) return false
        presentationGeneration = generation
        presentationSerial = serial
        protectedPresentation = surface
        credentialPresentation = credentials
        presentationOwnerID = ownerID
        val key = SecurityVisitKey(generation, surface, credentials, ownerID)
        if (securityVisitKey != key) {
            securityVisit?.let(product::securityRetirePresentationVisit)
            securityVisitKey = key
            lateinit var captured: SecurityPresentationVisit
            captured = SecurityPresentationVisit {
                nativeHostAlive && securityVisit === captured &&
                    nativeVisitHost.matches(nativeVisitHosts?.current()) &&
                    product.state.value.graphGeneration == generation
            }
            securityVisit = captured
        }
        product.securityEnterForeground(this, generation, securityVisit)
        renderAppLock()
        return true
    }

    /** JS has accepted and laid out this exact presentation; this never grants access. */
    internal fun setPresentationReady(generation: Long, serial: Long, ownerID: String? = null, readRevision: Long? = null): Boolean {
        if (!nativeHostAlive || product.state.value.graphGeneration != generation || presentationGeneration != generation ||
            ownerID != null && presentationOwnerID != ownerID ||
            !presentationFrames.acknowledge(generation, serial, publicRetry = false, foreground = foreground,
                readRevision = readRevision, currentReadRevision = product.securityStateForSnapshot(generation)?.authenticationRevision)) return false
        renderAppLock()
        return true
    }

    /** Public-only paint custody. It grants no private/native-field/entry readiness. */
    internal fun prepareRuntimeRetry(generation: Long, serial: Long): Boolean {
        if (!nativeHostAlive || !foreground || product.state.value.graphGeneration != generation ||
            !presentationFrames.publish(generation, serial, publicRetry = true, foreground = foreground)) return false
        presentationGeneration = generation
        presentationSerial = serial
        protectedPresentation = null
        credentialPresentation = false
        presentationOwnerID = null
        securityVisit?.let(product::securityRetirePresentationVisit)
        securityVisit = null
        securityVisitKey = null
        renderAppLock()
        return true
    }

    internal fun acknowledgeRuntimeRetry(generation: Long, serial: Long): Boolean {
        if (!nativeHostAlive || product.state.value.graphGeneration != generation ||
            !presentationFrames.acknowledge(generation, serial, publicRetry = true, foreground = foreground)) return false
        renderAppLock()
        return true
    }

    internal fun isPresentationReady(generation: Long, ownerID: String? = null): Boolean = foreground &&
        product.state.value.graphGeneration == generation && presentationGeneration == generation && presentationReady &&
        (ownerID == null || presentationOwnerID == ownerID)

    internal fun hasPresentationReceipt(generation: Long, serial: Long): Boolean =
        nativeHostAlive && presentationSerial == serial && isPresentationReady(generation)

    /** Active native owner receives Back before a retained RN navigation stack can pop. */
    internal fun setForegroundBackHandler(handler: (() -> Boolean)?) {
        foregroundBackHandler = handler
    }

    /** The captured host retires its own native visits even after another Activity resumes. */
    internal fun setNativeVisitRetirementHandler(handler: (() -> Unit)?) {
        nativeVisitRetirementHandler = handler
    }

    private var dispatchingReactBack = false

    internal fun routeReactBack(reactBack: () -> Boolean): Boolean {
        if (!dispatchingReactBack) {
            // Legacy hardware Back enters through ReactActivity's delegate.
            // Route that same action through AndroidX once, then stop React's
            // legacy superclass from dispatching the action a second time.
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        if (foreground && foregroundBackHandler?.invoke() == true) return true
        return reactBack()
    }

    private val foregroundBackCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val previous = dispatchingReactBack
            dispatchingReactBack = true
            try {
                // Preserve React's actual Back event. Its delegate can asynchronously
                // ask invokeDefaultOnBackPressed to continue the original action.
                if (!reactActivityDelegate.onBackPressed()) invokeDefaultOnBackPressed()
            } finally {
                dispatchingReactBack = previous
            }
        }
    }

    private fun registerForegroundBackCallback() {
        // Register after ReactActivity's own target-36 compatibility callback,
        // so the live native owner receives both hardware and predictive Back.
        onBackPressedDispatcher.addCallback(this, foregroundBackCallback)
    }

    override fun invokeDefaultOnBackPressed() {
        val wasEnabled = foregroundBackCallback.isEnabled
        foregroundBackCallback.isEnabled = false
        try {
            // React disables its own compatibility callback while the real
            // dispatcher continues to another child or the Activity fallback.
            super.invokeDefaultOnBackPressed()
        } finally {
            foregroundBackCallback.isEnabled = wasEnabled
        }
    }

    internal fun admitsNativeFieldInput(): Boolean {
        val generation = product.state.value.graphGeneration ?: return false
        if (!isPresentationReady(generation)) return false
        return ReactAppLockView.admits(foreground, product.securityStateForSnapshot(generation),
            protectedPresentation, presentationReady, credentialPresentation)
    }
    internal fun observeNativeFieldAuthority(onChanged: () -> Unit): () -> Unit {
        nativeFieldObservers.add(onChanged)
        return { nativeFieldObservers.remove(onChanged); Unit }
    }

    internal fun setWireGuardInputPort(port: LavaWireGuardInputPort) {
        wireGuardInputPort = port
    }

    internal var accountBackupFlow: NativeAccountBackupFlow? = null
        set(value) { field = value; renderAppLock() }

    @Suppress("DEPRECATION")
    internal suspend fun presentNative(intent: Intent): Int = suspendCancellableCoroutine { continuation ->
        if (!foreground || pendingFlow != null || pendingDocument != null || pendingNotifications != null || isFinishing) {
            continuation.resume(Activity.RESULT_CANCELED)
        } else {
            pendingFlow = continuation
            continuation.invokeOnCancellation { if (pendingFlow === continuation) pendingFlow = null }
            try {
                startActivityForResult(intent, NATIVE_FLOW_REQUEST)
            } catch (_: Exception) {
                pendingFlow = null
                continuation.resume(Activity.RESULT_CANCELED)
            }
        }
    }

    /** User-selected document grants only; portable backup never accesses broad storage. */
    @Suppress("DEPRECATION")
    internal suspend fun presentDocument(intent: Intent): Uri? = suspendCancellableCoroutine { continuation ->
        if (!foreground || pendingFlow != null || pendingDocument != null || pendingNotifications != null || isFinishing) {
            continuation.resume(null)
        } else {
            val requestCode = nextDocumentRequestCode
            nextDocumentRequestCode = if (requestCode == 65535) DOCUMENT_FLOW_REQUEST else requestCode + 1
            pendingDocument = continuation
            pendingDocumentRequestCode = requestCode
            continuation.invokeOnCancellation {
                if (pendingDocument === continuation) { pendingDocument = null; pendingDocumentRequestCode = null }
            }
            try { startActivityForResult(intent, requestCode) }
            catch (_: Exception) { pendingDocument = null; pendingDocumentRequestCode = null; continuation.resume(null) }
        }
    }

    internal fun notificationsGranted(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    internal suspend fun requestNotifications(): Boolean {
        if (notificationsGranted()) return true
        return suspendCancellableCoroutine { continuation ->
            if (!foreground || pendingFlow != null || pendingDocument != null || pendingNotifications != null || isFinishing) {
                continuation.resume(false)
                return@suspendCancellableCoroutine
            }
            pendingNotifications = continuation
            continuation.invokeOnCancellation {
                if (pendingNotifications === continuation) {
                    pendingNotifications = null
                    pendingNotificationResult = null
                }
            }
            try { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
            catch (_: Exception) { finishNotificationRequest(false) }
        }
    }

    private fun finishNotificationRequest(granted: Boolean) {
        val continuation = pendingNotifications
        pendingNotifications = null
        pendingNotificationResult = null
        if (continuation?.isActive == true) continuation.resume(granted)
    }

    internal suspend fun awaitForeground(): Boolean = suspendCancellableCoroutine { continuation ->
        if (foreground && !isFinishing) continuation.resume(true)
        else if (isFinishing || isDestroyed) continuation.resume(false)
        else {
            foregroundWaiters += continuation
            continuation.invokeOnCancellation { foregroundWaiters.remove(continuation) }
        }
    }

    @Deprecated("Android activity result compatibility for ReactActivity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pendingDocumentRequestCode) {
            val continuation = pendingDocument
            pendingDocument = null
            pendingDocumentRequestCode = null
            val uri = data?.data?.takeIf { resultCode == Activity.RESULT_OK && it.scheme == "content" }
            if (continuation?.isActive == true) continuation.resume(uri)
        }
        if (requestCode == NATIVE_FLOW_REQUEST) {
            val continuation = pendingFlow
            pendingFlow = null
            if (continuation?.isActive == true) continuation.resume(resultCode)
        }
    }

    private fun retireNativeHost() {
        nativeHostAlive = false
        foreground = false
        nativeVisitHosts?.retire(this, nativeHostID)
        securityVisit = null
        securityVisitKey = null
        (application as? LavasecApplication)?.foregroundProduct?.securityRetireForeground(this)
    }

    override fun finish() {
        super.finish()
        // Android can reject a finish request. Retire only after the framework
        // confirms it; later Resume cannot revive an accepted finish.
        if (isFinishing) retireNativeHost()
    }

    override fun finishAndRemoveTask() {
        super.finishAndRemoveTask()
        if (isFinishing) retireNativeHost()
    }

    override fun onDestroy() {
        windowViewport?.close(); windowViewport = null
        NativeDeepLinkEntryRegistry.process.release(nativeDeepLinkEntryID, this, nativeHostID, isChangingConfigurations)
        retireNativeHost()
        nativeVisitRetirementHandler?.invoke()
        nativeVisitRetirementHandler = null
        foreground = false
        presentationFrames.pause()
        foregroundBackHandler = null
        nativeFieldObservers.toList().forEach { it() }
        nativeFieldObservers.clear()
        pendingFlow?.let { if (it.isActive) it.resume(Activity.RESULT_CANCELED) }
        pendingFlow = null
        pendingDocument?.let { if (it.isActive) it.resume(null) }
        pendingDocument = null
        pendingDocumentRequestCode = null
        finishNotificationRequest(false)
        foregroundWaiters.toList().also { foregroundWaiters.clear() }.forEach { if (it.isActive) it.resume(false) }
        activityScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val COMPONENT_NAME = "LavasecApp"
        internal const val DEEP_LINK_ENTRY_EXTRA = "com.lavasec.app.reactnative.NATIVE_DEEP_LINK_ENTRY"
        const val ONBOARDING_PREVIEW_EXTRA = "com.lavasec.app.reactnative.ONBOARDING_PREVIEW"

        /** Initial prop key the JS `CanonicalApp` reads to seed the appearance store. */
        const val INITIAL_APPEARANCE_KEY = "initialAppearance"
        const val NATIVE_HOST_ID_KEY = "nativeHostID"
        private const val NATIVE_FLOW_REQUEST = 811
        private const val DOCUMENT_FLOW_REQUEST = 812
    }
}

/** Main-thread paint receipts. Public error paint never becomes normal write readiness. */
internal class NativePresentationFrames {
    private data class Receipt(val generation: Long, val serial: Long, val publicRetry: Boolean, val readRevision: Long?)
    private var current: Receipt? = null
    private var ready = false
    private var paused: Receipt? = null

    fun publish(generation: Long, serial: Long, publicRetry: Boolean, foreground: Boolean,
        readRevision: Long? = null, currentReadRevision: Long? = null): Boolean {
        if (!foreground || serial < 1 || !publicRetry && readRevision != currentReadRevision) return false
        val old = current
        if (old?.generation == generation && (serial < old.serial || publicRetry && serial <= old.serial)) return false
        // A queued pre-pause Ready cannot resurrect the old serial after resume.
        if (paused?.let { it.generation == generation && serial <= it.serial } == true) return false
        current = Receipt(generation, serial, publicRetry, if (publicRetry) null else readRevision)
        ready = false
        return true
    }
    fun acknowledge(generation: Long, serial: Long, publicRetry: Boolean, foreground: Boolean,
        readRevision: Long? = null, currentReadRevision: Long? = null): Boolean {
        if (!publicRetry && readRevision != currentReadRevision) return false
        val receipt = Receipt(generation, serial, publicRetry, if (publicRetry) null else readRevision)
        if (!foreground || current != receipt || paused?.let { it.generation == generation && serial <= it.serial } == true) return false
        ready = true
        return true
    }
    fun pause() { paused = current; ready = false }
    fun normalReady(generation: Long?, currentReadRevision: Long? = null): Boolean = ready && current?.generation == generation &&
        current?.publicRetry == false && current?.readRevision == currentReadRevision
    fun paintReady(generation: Long?, currentReadRevision: Long? = null): Boolean = ready && current?.generation == generation &&
        (current?.publicRetry == true || current?.readRevision == currentReadRevision)
}
