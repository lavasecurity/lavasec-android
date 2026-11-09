package com.lavasec.app.reactnative

import android.app.Activity
import android.content.Context
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.FrameLayout
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.annotations.ReactProp

/** No text props or text events: this mounts the original native credential form. */
internal class LavaPasscodeFormView(context: Context) : FrameLayout(context) {
    var ownerID = ""
    var fontScale = 1f
    internal var suppliedActivity: Activity? = null
    private val activity get() = suppliedActivity ?: (context as? ReactContext)?.currentActivity
    private var owner: NativePasscodeFormVisit? = null
    private var lastState: Triple<Double, Boolean, String>? = null
    private var naturalHeight = 48.0
    var stateChanged: (Double, Boolean, String) -> Unit = { _, _, _ -> }
    fun bind() {
        val next = NativePasscodeForms.find(ownerID, activity)
        if (next !== owner) {
            owner?.detached(this); owner = next; lastState = null
        }
        val current = owner ?: return
        if (!current.attach(this)) return
        NativePasscodeFormVisit.fields(current.form).forEach {
            it.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f * fontScale)
            it.minimumHeight = (48 * resources.displayMetrics.density).toInt()
        }
        current.changed = { requestLayout(); emitState() }
        requestLayout()
        post { if (owner === current && current.current()) { current.focus(); emitState() } }
    }
    fun focus() { owner?.takeIf { it.id == ownerID }?.focus() }
    private fun emitState() {
        val current = owner?.takeIf { it.current() } ?: return
        emit(naturalHeight, current)
    }
    private fun emit(height: Double, current: NativePasscodeFormVisit) {
        val state = Triple(height, current.busy, current.message)
        if (state == lastState) return
        lastState = state; stateChanged(state.first, state.second, state.third)
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val desired = measuredHeight
        naturalHeight = maxOf(48.0, desired / resources.displayMetrics.density.toDouble())
        setMeasuredDimension(measuredWidth, resolveSize(desired, heightMeasureSpec))
        post {
            val current = owner?.takeIf { it.current() } ?: return@post
            emit(naturalHeight, current)
        }
    }
    fun retire() { val previous = owner; owner = null; previous?.detached(this); removeAllViews() }
    override fun onDetachedFromWindow() { retire(); super.onDetachedFromWindow() }
}

internal class LavaPasscodeFormManager : SimpleViewManager<LavaPasscodeFormView>() {
    override fun getName() = "LavaPasscodeForm"
    override fun createViewInstance(context: ThemedReactContext) = LavaPasscodeFormView(context).apply {
        stateChanged = { height, busy, message ->
            @Suppress("DEPRECATION")
            context.getJSModule(com.facebook.react.uimanager.events.RCTEventEmitter::class.java).receiveEvent(id, "topStateChange",
                Arguments.createMap().apply { putDouble("height", height); putBoolean("authenticating", busy); putString("message", message) })
        }
    }
    @ReactProp(name = "ownerID") fun owner(view: LavaPasscodeFormView, value: String?) { view.ownerID = value.orEmpty() }
    @ReactProp(name = "fontScale", defaultFloat = 1f) fun scale(view: LavaPasscodeFormView, value: Float) { view.fontScale = value.coerceIn(0.5f, 4f) }
    @ReactProp(name = "focusRequest") fun focus(view: LavaPasscodeFormView, value: Int) { view.focus() }
    override fun onAfterUpdateTransaction(view: LavaPasscodeFormView) { super.onAfterUpdateTransaction(view); view.bind() }
    override fun onDropViewInstance(view: LavaPasscodeFormView) { view.retire(); super.onDropViewInstance(view) }
    override fun getExportedCustomDirectEventTypeConstants(): Map<String, Any> =
        mapOf("topStateChange" to mapOf("registrationName" to "onStateChange"))
}
