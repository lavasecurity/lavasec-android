package com.lavasec.app.reactnative

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableMap
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.lavasec.app.LavasecApplication
import com.lavasec.app.platform.ChainedComposition
import com.lavasec.app.platform.ChainedPreferenceMutationSerializer
import com.lavasec.app.platform.ChainedPreferenceReloadReconciliation
import com.lavasec.app.platform.ChainedStatus
import com.lavasec.app.platform.blockingReadinessRefusal
import com.lavasec.app.platform.userFacingReasonCode
import com.lavasec.app.vpn.LavaVpnRuntimeBridge
import com.lavasec.app.vpn.LavaVpnServiceClient
import com.lavasec.app.vpn.chained.ChainedRuntimeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * JS-facing command/query surface for the chained-upstream feature.
 *
 * The real implementation of the typed contract in `android/react-native/src/NativeLavaApp.ts`.
 * `getChainedStatus` reads the live per-credential-generation [ChainedComposition] and reports
 * the SAME [ChainedStatus] the VPN runtime itself will decide from — the whole readiness gate
 * (entitlement, device, preference, configuration, T0 routes) plus the credential store — so the
 * screen cannot show a "ready" the runtime would not honor. It also reports `canEnable`, the
 * preference-independent "may this user turn chaining on", because a disabled-by-user status has
 * `ready = false` and must not be mistaken for a surface that cannot be toggled.
 * `setChainedEnabled` commits the preference durably and then, when protection is live, asks the
 * service to reload so the running tunnel switches mode; it reports `accepted` (the preference
 * commit) rather than the post-command readiness, so a successful disable is not presented as a
 * failure. It also forwards the runtime's live chained-activity flag to JS as
 * [CHAINED_ACTIVE_EVENT], its protection-mode code as [CHAINED_PROTECTION_EVENT], and whether the
 * per-generation composition is installed as [CHAINED_COMPOSITION_EVENT], so a screen opened during
 * the async reload/handshake or before the unlocked graph is built can refresh as those change.
 *
 * The module holds no engine state and never constructs the chained engine: the no-instantiation
 * guarantee lives in [com.lavasec.app.platform.ChainedEnginePermit] behind the composition, and
 * this surface only queries the gate, forwards the preference, and relays runtime activity.
 */
@ReactModule(name = LavaAppModule.NAME)
class LavaAppModule(
    private val reactContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val productBridge = NativeProductBridge(reactContext, scope)
    private val reviewBridge = NativeReviewBridge(reactContext)

    /**
     * Serializes `setChainedEnabled` submissions so a rapid enable-then-disable cannot commit out
     * of order (and leave the durable preference / running mode contrary to the last action).
     */
    private val preferenceMutations = ChainedPreferenceMutationSerializer(scope)

    /**
     * Retries a committed chained-preference reload whose dispatch failed.
     *
     * A failed `reload` (service teardown, OEM background-start refusal) must not be swallowed: in
     * the disable case it would leave the durable preference/UI saying chaining is off while the
     * installed chained TUN keeps routing. The requirement is armed per commit and retried on a
     * later status read or toggle, mirroring the filter-mutation path.
     */
    private val preferenceReload = ChainedPreferenceReloadReconciliation()

    private var chainedActiveJob: Job? = null
    private var chainedProtectionJob: Job? = null
    private var chainedCompositionJob: Job? = null

    override fun getName(): String = NAME

    /**
     * Starts observing the VPN runtime's live chained-activity flag, protection mode, and the
     * application's chained composition and forwards each change to JS.
     *
     * The screen opens during an async reload/handshake and cannot know when the engine establishes
     * by polling `getChainedStatus` alone; this push lets it refresh the moment the runtime reports
     * the chained path active (or falls back).
     *
     * The protection-mode push is separate: the runtime can change `Off`→`AwaitingNetwork` (or pause
     * a DNS-only fallback) without publishing chained activity, so a screen that renders from
     * `status.protection` would otherwise stay stale indefinitely.
     *
     * The composition push covers the startup race where the screen's first `getChainedStatus`
     * observes a still-null composition (the unlocked graph is built asynchronously by
     * `LavasecApplication.onCreate`): installing it while protection stays off emits neither
     * activity nor protection changes, so the screen would stay on "native module unavailable".
     */
    override fun initialize() {
        super.initialize()
        productBridge.initialize()
        reviewBridge.initialize()
        chainedActiveJob = scope.launch {
            LavaVpnRuntimeBridge.chainedActivity.collect { signal ->
                emitChainedActive(signal.active)
            }
        }
        chainedProtectionJob = scope.launch {
            LavaVpnRuntimeBridge.state
                .map { state -> state.runtimeModeCode }
                .distinctUntilChanged()
                .collect { mode -> emitChainedProtection(mode) }
        }
        val application = reactContext.applicationContext as? LavasecApplication
        chainedCompositionJob = application?.let { app ->
            scope.launch {
                // `chainedComposition` is a StateFlow, which already conflates equal values;
                // `distinctUntilChanged()` on it is deprecated (no effect) and is a
                // warnings-as-errors build failure.
                app.chainedComposition
                    .collect { composition -> emitChainedComposition(composition != null) }
            }
        }
    }

    @ReactMethod
    fun getQaPresentation(ownerID: String, promise: Promise) = reactContext.runOnUiQueueThread {
        promise.resolve(com.lavasec.app.reactnative.components.NativeQaSurfaces.find(ownerID, reactContext.currentActivity)?.read() ?: "null")
    }

    @ReactMethod
    fun qaPresentationCommand(ownerID: String, request: String, promise: Promise) = reactContext.runOnUiQueueThread {
        promise.resolve(com.lavasec.app.reactnative.components.NativeQaSurfaces.find(ownerID, reactContext.currentActivity)?.command(request) ?: "null")
    }

    @ReactMethod
    fun qaPresentationLayout(ownerID: String, visitID: String, width: Double, height: Double, promise: Promise) = reactContext.runOnUiQueueThread {
        promise.resolve(com.lavasec.app.reactnative.components.NativeQaSurfaces.find(ownerID, reactContext.currentActivity)?.layout(visitID, width, height) ?: false)
    }

    @ReactMethod
    fun getSnapshot(promise: Promise) = productBridge.snapshot(promise)

    @ReactMethod
    fun getSnapshotForHost(hostID: String, promise: Promise) = productBridge.snapshotForHost(hostID, promise)

    @ReactMethod
    fun command(request: String, promise: Promise) = productBridge.command(request, promise)

    @ReactMethod
    fun getChainedStatus(promise: Promise) {
        val composition = compositionOrNull()
        if (composition == null) {
            promise.resolve(unavailableStatus())
            return
        }
        scope.launch {
            val status = runCatching { composition.status() }
            // Read the activity publication ONCE and derive both fields from that single snapshot:
            // reading `active` and the mode through separate accessors could straddle a
            // chained/DNS-only transition and report `active = true` with a DNS-only mode (or the
            // reverse), so the UI would claim traffic is still chained.
            val activity = LavaVpnRuntimeBridge.chainedActivity.value
            val active = activity.active
            // The installed runtime mode distinguishes a settled DNS-only fallback from an
            // in-progress chained startup that is merely not established yet.
            val installedMode = activity.mode.code
            // The protection mode lets the screen avoid rendering an intentionally off/paused
            // protection as a recovering chained session.
            val protection = LavaVpnRuntimeBridge.state.value.runtimeModeCode
            // A status read is a retry point for a committed preference whose reload dispatch
            // failed earlier, so a toggle that could not reach the live runtime converges without
            // requiring another toggle.
            reconcileChainedPreferenceReload()
            promise.resolve(
                status.fold(
                    onSuccess = { current -> statusMap(current, active, protection, installedMode) },
                    onFailure = { unavailableStatus() },
                ),
            )
        }
    }

    @ReactMethod
    fun setChainedEnabled(enabled: Boolean, promise: Promise) {
        val composition = compositionOrNull()
        if (composition == null) {
            promise.resolve(toggleResult(accepted = false, reason = REASON_UNAVAILABLE))
            return
        }
        // Queue the commit behind any in-flight toggle: two rapid requests would otherwise commit
        // concurrently on the multithreaded IO scope and could land out of order, leaving the
        // durable preference and the running mode contrary to the last action. A failed preference
        // write is reported, never swallowed into a success.
        preferenceMutations.submit {
            val committed = runCatching { composition.setEnabled(enabled) }.getOrDefault(false)
            if (!committed) {
                promise.resolve(toggleResult(accepted = false, reason = REASON_PREFERENCE_FAILED))
                return@submit
            }
            // Bind the reload to the generation observed at dispatch time. A stop queued behind
            // this read retires that generation, so the service no-ops the reload instead of
            // restarting protection the user just turned off. A failed dispatch is retained (not
            // swallowed) and retried by a later status read or toggle, so the installed chained TUN
            // cannot keep routing a preference the user has changed.
            preferenceReload.request()
            reconcileChainedPreferenceReload()
            // A committed preference IS the success of this command. It is deliberately
            // independent of `ready`: a successful disable leaves the upstream legitimately
            // disabled-by-user (`ready` false), and presenting that as a failure was the bug.
            // The screen re-reads the authoritative status for the toggle's on/off state.
            promise.resolve(toggleResult(accepted = true, reason = null))
        }
    }

    /**
     * Dispatches the committed chained preference to the live runtime, retaining a failed dispatch
     * for a later retry.
     *
     * The reload is bound to the service generation observed at dispatch time; a stop queued behind
     * the read retires that generation and the service no-ops the reload instead of restarting
     * protection. A `null` generation (no live runtime) discharges the requirement, because the
     * next start reads the committed preference itself. Dispatch failures (service teardown, OEM
     * background-start refusal) stay armed and are retried by the next status read or toggle.
     */
    private suspend fun reconcileChainedPreferenceReload() {
        preferenceReload.reconcileOnce(LavaVpnRuntimeBridge.serviceGeneration) { generation ->
            LavaVpnServiceClient(reactContext).reload(generation)
        }
    }

    override fun invalidate() {
        productBridge.invalidate()
        reviewBridge.invalidate()
        chainedActiveJob?.cancel()
        chainedActiveJob = null
        chainedProtectionJob?.cancel()
        chainedProtectionJob = null
        chainedCompositionJob?.cancel()
        chainedCompositionJob = null
        preferenceMutations.cancel()
        scope.cancel()
        super.invalidate()
    }

    @ReactMethod(isBlockingSynchronousMethod = true)
    fun getLegalNotices(): String = reviewBridge.legalNotices()

    @ReactMethod
    fun chooseActivityExport(canShareChart: Boolean, title: String, chart: String, diagnostics: String, logs: String, promise: Promise) =
        reviewBridge.chooseActivityExport(canShareChart, title, chart, diagnostics, logs, promise)

    @ReactMethod
    fun pickActivityDates(start: Double, end: Double, promise: Promise) =
        reviewBridge.pickDates(start, end, promise)

    @ReactMethod
    fun speakDemo(text: String, locale: String, promise: Promise) =
        reviewBridge.speakDemo(text, locale, promise)

    @ReactMethod
    fun stopDemo() = reviewBridge.stopDemo()

    /** Emits the live chained-activity flag to JS; a missing bridge/JS module is simply a no-op. */
    private fun emitChainedActive(active: Boolean) {
        runCatching {
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(CHAINED_ACTIVE_EVENT, active)
        }
    }

    /**
     * Emits the live protection-mode code (`off`/`paused`/`running`) to JS; a missing bridge/JS
     * module is simply a no-op.
     */
    private fun emitChainedProtection(mode: String) {
        runCatching {
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(CHAINED_PROTECTION_EVENT, mode)
        }
    }

    /**
     * Emits whether the per-generation chained composition is currently installed; a missing
     * bridge/JS module is simply a no-op.
     */
    private fun emitChainedComposition(available: Boolean) {
        runCatching {
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(CHAINED_COMPOSITION_EVENT, available)
        }
    }

    private fun compositionOrNull(): ChainedComposition? =
        (reactContext.applicationContext as? LavasecApplication)?.chainedComposition?.value

    private fun statusMap(
        status: ChainedStatus,
        chainedActive: Boolean,
        protection: String,
        installedMode: String,
    ): WritableMap {
        val map: WritableMap = Arguments.createMap()
        map.putBoolean("ready", status.ready)
        map.putString("reason", reasonFor(status))
        map.putBoolean("enabled", status.enabled)
        map.putBoolean("canEnable", status.canEnable)
        map.putBoolean("active", chainedActive)
        map.putString("protection", protection)
        map.putString("installedMode", installedMode)
        map.putBoolean("configured", status.configured)
        map.putBoolean("eligible", status.eligible)
        return map
    }

    /**
     * The honest operational reason code, or `null` when the runtime can honor a toggle.
     *
     * Returns a stable user-facing code (mapped to localized copy by the JS layer), never a
     * readiness log identifier. [ChainedStatus.blockingReadinessRefusal] lets a downstream
     * configuration/platform refusal outrank `disabled-by-user` when enabling is impossible, so a
     * user whose switch is off still learns the real blocker; a genuinely-off-and-fine upstream
     * still reports disabled-by-user.
     */
    private fun reasonFor(status: ChainedStatus): String? {
        if (status.ready) return null
        if (!status.configured) return REASON_NOT_CONFIGURED
        val refusal = status.blockingReadinessRefusal()
        if (refusal != null) return refusal.userFacingReasonCode()
        if (!status.secretsAvailable) return REASON_KEY_UNAVAILABLE
        return null
    }

    private fun unavailableStatus(): WritableMap {
        val map: WritableMap = Arguments.createMap()
        map.putBoolean("ready", false)
        map.putString("reason", REASON_UNAVAILABLE)
        map.putBoolean("enabled", false)
        map.putBoolean("canEnable", false)
        map.putBoolean("active", false)
        map.putString("protection", PROTECTION_OFF)
        map.putString("installedMode", ChainedRuntimeMode.NONE.code)
        map.putBoolean("configured", false)
        map.putBoolean("eligible", false)
        return map
    }

    private fun toggleResult(accepted: Boolean, reason: String?): WritableMap {
        val map: WritableMap = Arguments.createMap()
        map.putBoolean("accepted", accepted)
        map.putString("reason", reason)
        return map
    }

    companion object {
        const val NAME = "NativeLavaApp"

        /** JS event carrying the live chained-activity flag; see [subscribeChainedActive]. */
        const val CHAINED_ACTIVE_EVENT = "chainedActiveChanged"

        /**
         * JS event carrying the live protection-mode code (`off`/`paused`/`running`); lets a screen
         * that renders from `protection` refresh when the runtime starts/awaits/pauses without a
         * chained-activity publication.
         */
        const val CHAINED_PROTECTION_EVENT = "chainedProtectionChanged"

        /**
         * JS event carrying whether the per-generation chained composition is installed; lets a
         * screen opened before the async unlocked graph is ready refresh when it becomes available
         * (or rotates).
         */
        const val CHAINED_COMPOSITION_EVENT = "chainedCompositionChanged"

        /** Reported protection mode when no live runtime exists (matches `runtimeModeCode`). */
        const val PROTECTION_OFF = "off"

        /** No upstream is stored; the remedy is "add one", not "your device is unsupported". */
        const val REASON_NOT_CONFIGURED = "not configured"

        /** No unlocked credential graph / composition is live (pre-unlock or retired). */
        const val REASON_UNAVAILABLE = "native module unavailable"

        /** The committed upstream's key half is missing or unreadable; re-enter the key. */
        const val REASON_KEY_UNAVAILABLE = "chained-upstream-key-unavailable"

        /** The durable preference write failed; the previous value is still authoritative. */
        const val REASON_PREFERENCE_FAILED = "chained-preference-write-failed"
    }
}
