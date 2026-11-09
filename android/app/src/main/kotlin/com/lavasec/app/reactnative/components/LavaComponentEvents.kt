package com.lavasec.app.reactnative.components

import android.view.View
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.events.Event

/**
 * The event channel shared by every generated Lava control.
 *
 * The components are registered as legacy view managers (the stock React Native Gradle plugin is not
 * applied, so no Fabric component descriptors are generated for `codegenNativeComponent`). Event
 * names therefore follow the legacy `topX` convention: a manager exports
 * `topValueChange -> registrationName "onValueChange"` and the view emits `topValueChange`. The
 * spec's `DirectEventHandler` props (`onValueChange`, `onSizeChange`, ...) resolve to those
 * registration names, exactly like the built-in `ReactSwitchManager`.
 */
internal class LavaComponentEvent(
    surfaceId: Int,
    viewTag: Int,
    private val name: String,
    private val data: WritableMap,
) : Event<LavaComponentEvent>(surfaceId, viewTag) {
    override fun getEventName(): String = name

    override fun getEventData(): WritableMap = data
}

/** Dispatches a control event for [view] through the Fabric/bridged event dispatcher. */
internal fun View.dispatchLavaEvent(name: String, payload: WritableMap) {
    val reactContext = context as? ReactContext ?: return
    val dispatcher = UIManagerHelper.getEventDispatcher(reactContext) ?: return
    dispatcher.dispatchEvent(LavaComponentEvent(UIManagerHelper.getSurfaceId(this), id, name, payload))
}

/** Registration-name maps shared by the managers, kept in one place so native and JS cannot drift. */
internal object LavaEventNames {
    const val VALUE_CHANGE = "topValueChange"
    const val TEXT_CHANGE = "topTextChange"
    const val SUBMIT = "topSubmit"
    const val SIZE_CHANGE = "topSizeChange"
    const val TRACKING_CHANGE = "topTrackingChange"
    const val ACTION = "topAction"
    const val GUARDIAN_GESTURE = "topGuardianGesture"

    fun direct(constant: String, registrationName: String): Map<String, Any> =
        mapOf(constant to mapOf("registrationName" to registrationName))
}
