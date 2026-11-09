package com.lavasec.app.reactnative

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.widget.FrameLayout
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.facebook.react.ReactApplication
import com.facebook.react.interfaces.fabric.ReactSurface
import com.lavasec.app.settings.ProductSettingsStore
import java.util.UUID

/** Dialog roots do not inherit the Activity decor's owners. Fabric can attach a
 * Compose-backed symbol after show(), so install owners before any child mounts. */
internal fun bindNativePasscodeViewTree(view: View, activity: ComponentActivity) {
    view.setViewTreeLifecycleOwner(activity)
    view.setViewTreeViewModelStoreOwner(activity)
    view.setViewTreeSavedStateRegistryOwner(activity)
}

/** ReactSurfaceView sizes AT_MOST from its previous Fabric children. Give it the
 * actual bounded dialog body so stale tall content cannot push native buttons
 * below the visible window when the IME or window size changes. */
internal class NativePasscodeViewport(context: Context, surface: View) : FrameLayout(context) {
    init { addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        fun bounded(spec: Int) = if (MeasureSpec.getMode(spec) == MeasureSpec.AT_MOST)
            MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(spec), MeasureSpec.EXACTLY) else spec
        super.onMeasure(bounded(widthMeasureSpec), bounded(heightMeasureSpec))
    }
}

/** Owns an existing native form. A React leaf can mount it once, never read its contents. */
internal class NativePasscodeFormVisit(
    val activity: Activity,
    val form: View,
    private val admitted: () -> Boolean,
    private val retired: () -> Unit,
) : AutoCloseable {
    val id = UUID.randomUUID().toString()
    private var closed = false
    private var claimed: ViewGroup? = null
    var busy = false; private set
    var message = ""; private set
    var changed: (() -> Unit)? = null
    var ready: (() -> Unit)? = null
    fun current() = !closed && !activity.isDestroyed && !activity.isFinishing && admitted()
    fun attach(target: ViewGroup): Boolean {
        if (!current() || claimed != null && claimed !== target || form.parent != null && form.parent !== target) return false
        val firstAttachment = claimed == null
        claimed = target
        if (form.parent == null) target.addView(form, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (firstAttachment) ready?.invoke()
        return true
    }
    fun focus() {
        if (current() && !busy) {
            val inputs = fields(form)
            if (inputs.none { it.hasFocus() }) inputs.firstOrNull()?.requestFocus()
        }
    }
    fun update(working: Boolean, error: String = "") {
        if (!current()) return
        busy = working; message = error
        fields(form).forEach { field ->
            val wasEnabled = field.isEnabled
            field.isEnabled = !working
            if (!working && !wasEnabled && field.hasFocus()) {
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .restartInput(field)
            }
        }
        changed?.invoke()
    }
    fun detached(target: ViewGroup) {
        if (claimed !== target || closed) return
        close() // Revoke fields immediately, including during window removal.
        // Dialog clears its showing flag after removeViewImmediate returns. Avoid
        // re-entering that removal from this view's onDetachedFromWindow callback.
        android.os.Handler(android.os.Looper.getMainLooper()).post { retired() }
    }
    override fun close() {
        if (closed) return
        closed = true; changed = null; ready = null
        fields(form).forEach { it.text.clear(); it.clearFocus(); it.isEnabled = false }
        (form.parent as? ViewGroup)?.removeView(form)
        NativePasscodeForms.remove(this)
    }
    companion object {
        fun fields(view: View): List<EditText> = when (view) {
            is EditText -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { fields(view.getChildAt(it)) }
            else -> emptyList()
        }
    }
}

internal object NativePasscodeForms {
    private val visits = mutableMapOf<String, NativePasscodeFormVisit>()
    fun register(visit: NativePasscodeFormVisit) { visits[visit.id] = visit }
    fun find(id: String, activity: Activity?): NativePasscodeFormVisit? =
        visits[id]?.takeIf { it.activity === activity && it.current() }
    fun remove(visit: NativePasscodeFormVisit) { if (visits[visit.id] === visit) visits.remove(visit.id) }
}

/**
 * Shared layout in a secure native Dialog, with the original native fields/buttons/verifier.
 * The legacy non-React activity keeps its native presentation.
 */
internal class NativePasscodeComposition private constructor(
    private val activity: LavaReactActivity,
    private val visit: NativePasscodeFormVisit,
    private val surface: ReactSurface,
) : AutoCloseable {
    val view: View = NativePasscodeViewport(activity, checkNotNull(surface.view))
    private var dialog: AlertDialog? = null
    private var closed = false
    private var dialogWidth = 0
    private val resized = View.OnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
        resize(right - left)
    }
    private fun resize(availableWidth: Int) {
        if (closed || availableWidth <= 0) return
        val width = minOf(availableWidth, (560 * activity.resources.displayMetrics.density).toInt())
        if (width == dialogWidth) return
        dialogWidth = width
        dialog?.window?.setLayout(width, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityPaused(activity: Activity) { if (activity === this@NativePasscodeComposition.activity) dialog?.dismiss() }
        override fun onActivityDestroyed(activity: Activity) { if (activity === this@NativePasscodeComposition.activity) { dialog?.dismiss(); close() } }
        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    }
    init { activity.application.registerActivityLifecycleCallbacks(lifecycle) }
    fun show(dialog: AlertDialog) {
        this.dialog = dialog
        val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
        positive.isEnabled = false; neutral?.isEnabled = false
        visit.ready = {
            if (!closed && visit.current()) {
                positive.isEnabled = !visit.busy; neutral?.isEnabled = !visit.busy; visit.focus()
                visit.form.post {
                    if (!closed && visit.current() && dialog.isShowing && !visit.busy) {
                        NativePasscodeFormVisit.fields(visit.form).firstOrNull { it.hasFocus() }?.let { field ->
                            (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                                .showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                        }
                    }
                }
            }
        }
        dialog.window?.apply {
            // WindowRecomposer resolves the dialog content root above the React
            // surface. Owners on the surface alone cannot satisfy that lookup.
            bindNativePasscodeViewTree(decorView, activity)
            addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            // AlertDialog inspected an empty React root before Fabric mounted the native form.
            // Explicitly allow its later native EditText to own the IME.
            clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        activity.window.decorView.addOnLayoutChangeListener(resized)
        resize(activity.window.decorView.width.takeIf { it > 0 }
            ?: (activity.resources.configuration.screenWidthDp * activity.resources.displayMetrics.density).toInt())
        surface.start()
    }
    fun update(busy: Boolean, message: String = "") {
        if (closed || !visit.current()) return
        visit.update(busy, message)
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !busy
        dialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = !busy
    }
    override fun close() {
        if (closed) return
        closed = true
        activity.application.unregisterActivityLifecycleCallbacks(lifecycle)
        activity.window.decorView.removeOnLayoutChangeListener(resized)
        visit.close()
        surface.stop()
        dialog = null
    }
    companion object {
        fun create(activity: Activity, title: String, subtitle: String, form: View,
            admitted: () -> Boolean, dismiss: () -> Unit): NativePasscodeComposition? {
            if (activity !is LavaReactActivity) return null
            val hostID = activity.nativeHostID
            val visit = NativePasscodeFormVisit(activity, form,
                { activity.nativeHostID == hostID && activity.nativeHostAlive && activity.foreground && admitted() }, dismiss)
            val preferences = ProductSettingsStore(activity).loadPreferences()
            val props = Bundle().apply {
                putString("ownerID", visit.id); putString("title", title); putString("subtitle", subtitle)
                if (!preferences.matchTextSize) putDouble("textScale", TEXT_SIZE_BODY_SCALES[preferences.textSize.index])
            }
            val surface = (activity.application as ReactApplication).reactHost?.createSurface(activity, "LavaPasscode", props)
                ?: return null
            if (surface.view == null) { surface.stop(); return null }
            NativePasscodeForms.register(visit)
            return NativePasscodeComposition(activity, visit, surface)
        }
    }
}
