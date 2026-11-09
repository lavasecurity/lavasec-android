package com.lavasec.app.reactnative.components

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.ReactContext
import com.facebook.react.common.LifecycleState
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.annotations.ReactProp
import com.lavasec.app.LavasecApplication
import com.lavasec.app.BuildConfig
import com.lavasec.app.designsystem.LavaTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Event constants for the `LavaNativePage` spec; kept local to F07. */
private const val PAGE_BACK_EVENT = "topBack"
private const val PAGE_NAVIGATE_EVENT = "topNavigate"

/**
 * `LavaNativePage` — the Android containment host for an embedded native feature page.
 *
 * Props/events from `LavaNativePageNativeComponent.ts`: `page`, `focused`, `onBack`, `onNavigate`.
 *
 * The React navigation stack owns the one header, the transition and destination admission; this
 * view never draws a second header/back. It owns the *interaction* boundary: native controls and
 * private drafts stay interactive only while the page is focused, foreground and admitted by the
 * `SecurityOwner`, and interaction is revoked synchronously on blur, on background or when the
 * credential revision the grant was bound to changes.
 */
internal class LavaNativePageView(context: Context) : FrameLayout(context) {
    private val content = FrameLayout(context)
    private val blocker = View(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var port: LavaNativePageAdmissionPort? = null
    private var activityProvider: (() -> Activity?)? = null
    private var revisionJob: Job? = null
    private var authorizationJob: Job? = null
    private var qaAuthorityJob: Job? = null
    private var qaPort: DeviceQaOwnerPort? = null
    private var qaOwner by mutableStateOf<DeviceQaPageOwner?>(null)
    private var qaPage by mutableStateOf(false)
    private var sharedQa: NativeQaComposition? = null
    private var requestedFocused = false
    private var disposed = false
    private var nativeLayoutPosted = false
    private var nativeLayoutInProgress = false
    private val nativeLayout = Runnable {
        nativeLayoutPosted = false
        // Read the current page and Yoga footprint, never the visit/size that queued this work.
        if (!disposed && isAttachedToWindow && qaPage && state.active && width > 0 && height > 0) {
            layoutNativeContent(width, height)
            // Complete this host's outstanding request without changing its Yoga frame. Leaving
            // it layout-requested would stop later descendant requests before our override.
            layout(left, top, right, bottom)
        }
    }
    private val reactContext = context as? ReactContext
    private val lifecycleListener = object : LifecycleEventListener {
        override fun onHostResume() { if (isAttachedToWindow) applyActive(true) }
        override fun onHostPause() { if (state.page == "phoneQA") applyActive(false) }
        override fun onHostDestroy() { if (state.page == "phoneQA") applyActive(false) }
    }
    private val qaContent = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
        setContent {
            val owner = qaOwner
            if (BuildConfig.DEBUG && qaPage && owner != null) LavaTheme {
                DeviceQaPage(owner) { destination -> requestNavigate(destination) }
            }
        }
    }
    private var state = LavaNativePageState()

    init {
        addView(content, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        blocker.isClickable = true
        blocker.isFocusable = true
        blocker.setOnClickListener { }
        addView(blocker, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        applyInteraction()
    }

    /** Wires the live admission authority and how to reach the presentation activity. */
    fun bind(port: LavaNativePageAdmissionPort, activityProvider: () -> Activity?) {
        this.port = port
        this.activityProvider = activityProvider
        removeSharedQa()
        qaOwner?.close()
        qaPort = if (BuildConfig.DEBUG) port.deviceQaPort(activityProvider) else null
        qaOwner = qaPort?.let { DeviceQaPageOwner(it, scope) }
        qaAuthorityJob?.cancel()
        qaAuthorityJob = scope.launch {
            port.deviceQaAuthorityChanges.collect { authority ->
                if (state.page == "phoneQA" && qaOwner?.state?.value?.visit?.authority != authority) {
                    update(LavaNativePagePolicy.revoke(state))
                    authorizeIfNeeded()
                }
            }
        }
        revisionJob?.cancel()
        revisionJob = scope.launch {
            port.securityRevisions.collect { revision -> onSecurityRevision(revision) }
        }
    }

    fun applyPage(page: String) {
        update(LavaNativePagePolicy.open(state, page))
        // Fabric does not promise prop application order. Preserve the actual focused prop
        // when phoneQA arrives after focused=true, while still giving this page a new grant.
        if (page == "phoneQA") update(LavaNativePagePolicy.setFocused(state, requestedFocused))
        authorizeIfNeeded()
    }

    fun applyFocused(focused: Boolean) {
        requestedFocused = focused
        update(LavaNativePagePolicy.setFocused(state, focused))
        authorizeIfNeeded()
    }

    fun applyActive(active: Boolean) {
        update(LavaNativePagePolicy.setActive(state, active))
        authorizeIfNeeded()
    }

    /**
     * A credential-revision change revokes synchronously when the active grant no longer matches.
     * A view that is blurred already has no grant, so this is a cheap no-op.
     */
    fun onSecurityRevision(revision: Long) {
        val admission = state.admission ?: return
        if (admission.securityRevision != revision) update(LavaNativePagePolicy.revoke(state))
    }

    /** Publishes `onBack` only while the current visit is admitted (dirty-dismissal is host-driven). */
    fun requestBack(): Boolean {
        if (!state.interactionAllowed) return false
        dispatchLavaEvent(PAGE_BACK_EVENT, Arguments.createMap())
        return true
    }

    /** Publishes `onNavigate` only for an admitted current visit and an allowlisted destination. */
    fun requestNavigate(destination: String): Boolean {
        val allowed = LavaNativePagePolicy.allowedDestinations(state.page)
        if (!LavaNativePagePolicy.canNavigate(state, state.visit, destination, allowed)) return false
        dispatchLavaEvent(
            PAGE_NAVIGATE_EVENT,
            Arguments.createMap().apply { putString("destination", destination) },
        )
        return true
    }

    private fun authorizeIfNeeded() {
        val port = port ?: return
        if (!state.focused || !state.active || state.admission != null) return
        if (state.page == "phoneQA" && (!BuildConfig.DEBUG || qaPort == null)) return
        if (!port.requiresAuthentication(state.page)) {
            val revision = port.currentSecurityRevision()
            update(LavaNativePagePolicy.grant(state, revision, revision))
            return
        }
        val visit = state.visit
        val activation = state.activation
        val page = state.page
        val qaAuthority = if (page == "phoneQA") qaPort?.authority() else null
        authorizationJob?.cancel()
        authorizationJob = scope.launch {
            val activity = activityProvider?.invoke() ?: return@launch
            val accepted = port.authorize(activity, page)
            // Supersede anything that blurred, recycled or changed page while the prompt was open.
            if (accepted && state.visit == visit && state.activation == activation && state.page == page &&
                state.focused && state.active && (page != "phoneQA" || qaAuthority != null && qaPort?.authority() == qaAuthority)
            ) {
                // Bind to the revision the accepted turn left in force; a later change revokes.
                val revision = port.currentSecurityRevision()
                update(LavaNativePagePolicy.grant(state, revision, revision))
            }
        }
    }

    private fun update(next: LavaNativePageState) {
        if (next == state) return
        state = next
        applyInteraction()
    }

    private fun applyInteraction() {
        LavaNativeInteractionGate.setOpen(content, blocker, state.interactionAllowed)
        qaPage = state.page == "phoneQA"
        if (qaPage) {
            if (BuildConfig.DEBUG && sharedQa == null) {
                val owner = qaOwner
                if (owner != null) sharedQa = NativeQaComposition.create(activityProvider?.invoke(), owner, scope,
                    { !disposed && state.interactionAllowed && qaOwner === owner }, ::requestNavigate, ::requestBack)
                sharedQa?.let { composition ->
                    if (qaContent.parent === content) content.removeView(qaContent)
                    content.addView(composition.view, LayoutParams(MATCH_PARENT, MATCH_PARENT)); composition.start()
                }
            }
            // Legacy non-React hosts keep their existing native body.
            if (BuildConfig.DEBUG && sharedQa == null && qaContent.parent !== content) content.addView(qaContent, LayoutParams(MATCH_PARENT, MATCH_PARENT))
            val authority = qaPort?.authority()
            qaOwner?.activate(if (state.interactionAllowed && authority != null) DeviceQaVisit(state.visit, state.activation, authority) else null)
            // Revoke private rendered content synchronously before the next Compose frame.
            content.visibility = if (state.interactionAllowed) View.VISIBLE else View.INVISIBLE
            scheduleNativeLayout()
        } else {
            cancelNativeLayout()
            removeSharedQa()
            if (qaContent.parent === content) content.removeView(qaContent)
            qaOwner?.activate(null)
            content.visibility = View.VISIBLE
        }
    }

    private fun removeSharedQa() {
        sharedQa?.let { composition -> val view = composition.view; composition.close(); content.removeView(view) }
        sharedQa = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        reactContext?.addLifecycleEventListener(lifecycleListener)
        applyActive(reactContext == null || reactContext.lifecycleState == LifecycleState.RESUMED)
        requestLayout()
    }

    override fun onDetachedFromWindow() {
        applyActive(false)
        cancelNativeLayout()
        reactContext?.removeLifecycleEventListener(lifecycleListener)
        super.onDetachedFromWindow()
    }

    /** Fabric reuse for another visit: cancel pending auth, reset identity, close interaction. */
    fun resetForRecycle() {
        cancelNativeLayout()
        authorizationJob?.cancel()
        authorizationJob = null
        qaOwner?.activate(null)
        requestedFocused = false
        state = LavaNativePagePolicy.resetForRecycle(state)
        applyInteraction()
    }

    /** Drop the view for good: no listener, draft or pending callback may survive. */
    fun dispose() {
        disposed = true
        cancelNativeLayout()
        authorizationJob?.cancel()
        authorizationJob = null
        revisionJob?.cancel()
        revisionJob = null
        qaAuthorityJob?.cancel()
        qaAuthorityJob = null
        removeSharedQa()
        qaOwner?.close()
        qaOwner = null
        qaPort = null
        reactContext?.removeLifecycleEventListener(lifecycleListener)
        qaContent.disposeComposition()
        scope.cancel()
        port = null
        activityProvider = null
    }

    override fun requestLayout() {
        super.requestLayout()
        // ReactViewGroup terminates child requestLayout; Fabric only lays out this host.
        // Our native-only descendants still need a pass after attachment/Compose changes.
        scheduleNativeLayout()
    }

    private fun scheduleNativeLayout() {
        // The attachment check also makes requestLayout safe during the View constructor.
        if (!isAttachedToWindow || disposed || !qaPage || !state.active || nativeLayoutPosted || nativeLayoutInProgress) return
        nativeLayoutPosted = post(nativeLayout)
    }

    private fun cancelNativeLayout() {
        removeCallbacks(nativeLayout)
        nativeLayoutPosted = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!qaPage) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        // Fabric may measure before a window recomposer exists. Yoga owns the host footprint.
        setMeasuredDimension(resolveSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(suggestedMinimumHeight, heightMeasureSpec))
        if (isAttachedToWindow) measureContent(measuredWidth, measuredHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (!qaPage) {
            super.onLayout(changed, left, top, right, bottom)
            return
        }
        if (!isAttachedToWindow) return
        layoutNativeContent(right - left, bottom - top)
    }

    private fun layoutNativeContent(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || disposed) return
        nativeLayoutInProgress = true
        try {
            measureContent(width, height)
            content.layout(0, 0, width, height)
            blocker.layout(0, 0, width, height)
        } finally { nativeLayoutInProgress = false }
    }

    private fun measureContent(width: Int, height: Int) {
        val widthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        content.measure(widthSpec, heightSpec)
        blocker.measure(widthSpec, heightSpec)
    }
}

/**
 * The synchronous interaction boundary. Closing revokes hit testing and accessibility in the same
 * call (never a deferred post), so no native control stays interactive after a blur or revocation.
 */
internal object LavaNativeInteractionGate {
    fun setOpen(content: View, blocker: View, open: Boolean) {
        blocker.visibility = if (open) View.GONE else View.VISIBLE
        blocker.isEnabled = !open
        content.importantForAccessibility = if (open) {
            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        } else {
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        content.isEnabled = open
    }
}

internal class LavaNativePageManager : SimpleViewManager<LavaNativePageView>() {
    override fun getName(): String = NAME

    override fun createViewInstance(reactContext: ThemedReactContext): LavaNativePageView {
        val view = LavaNativePageView(reactContext)
        val application = reactContext.applicationContext as? LavasecApplication
        val port = application?.let { ForegroundProductAdmissionPort(it.foregroundProduct, it) }
            ?: DeniedAdmissionPort
        view.bind(port) { reactContext.currentActivity }
        return view
    }

    @ReactProp(name = "page")
    fun setPage(view: LavaNativePageView, value: String?) {
        view.applyPage(value.orEmpty())
    }

    @ReactProp(name = "focused", defaultBoolean = false)
    fun setFocused(view: LavaNativePageView, value: Boolean) {
        view.applyFocused(value)
    }

    override fun getExportedCustomDirectEventTypeConstants(): MutableMap<String, Any> = mutableMapOf(
        PAGE_BACK_EVENT to mapOf("registrationName" to "onBack"),
        PAGE_NAVIGATE_EVENT to mapOf("registrationName" to "onNavigate"),
    )

    override fun prepareToRecycleView(
        reactContext: ThemedReactContext,
        view: LavaNativePageView,
    ): LavaNativePageView {
        view.resetForRecycle()
        return view
    }

    override fun onDropViewInstance(view: LavaNativePageView) {
        view.dispose()
        super.onDropViewInstance(view)
    }

    companion object {
        const val NAME = "LavaNativePage"
    }
}

/** Fails closed when the host application is not available: the page is never interactive. */
private object DeniedAdmissionPort : LavaNativePageAdmissionPort {
    override val securityRevisions: kotlinx.coroutines.flow.Flow<Long> =
        kotlinx.coroutines.flow.flowOf(0L)

    override fun currentSecurityRevision(): Long = 0L
    override fun requiresAuthentication(page: String): Boolean = true
    override suspend fun authorize(activity: Activity, page: String): Boolean = false
}
