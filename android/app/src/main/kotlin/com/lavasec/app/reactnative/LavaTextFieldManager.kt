package com.lavasec.app.reactnative

import com.facebook.react.bridge.Arguments
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.annotations.ReactProp
import com.facebook.react.uimanager.events.RCTEventEmitter

/** Fabric-compatible legacy manager; only ordinary fields emit text events. */
internal class LavaTextFieldManager : SimpleViewManager<LavaTextFieldView>() {
    override fun getName() = "LavaTextField"
    override fun createViewInstance(context: ThemedReactContext): LavaTextFieldView = LavaTextFieldView(context).apply {
        emit = { name, payload ->
            val map = Arguments.createMap()
            payload.forEach { (key, value) -> when (value) {
                is String -> map.putString(key, value)
                is Boolean -> map.putBoolean(key, value)
                is Number -> map.putDouble(key, value.toDouble())
            } }
            @Suppress("DEPRECATION")
            context.getJSModule(RCTEventEmitter::class.java).receiveEvent(id, name, map)
        }
    }
    override fun onAfterUpdateTransaction(view: LavaTextFieldView) { super.onAfterUpdateTransaction(view); view.commitProps() }
    @ReactProp(name = "inputLabel") fun label(v: LavaTextFieldView, value: String?) { v.inputLabel = value.orEmpty() }
    @ReactProp(name = "placeholder") fun placeholder(v: LavaTextFieldView, value: String?) { v.placeholder = value.orEmpty() }
    @ReactProp(name = "kind") fun kind(v: LavaTextFieldView, value: String?) { v.kind = value.orEmpty() }
    @ReactProp(name = "ownerID") fun owner(v: LavaTextFieldView, value: String?) { v.ownerID = value.orEmpty() }
    @ReactProp(name = "value") fun value(v: LavaTextFieldView, value: String?) { v.seedValue = value.orEmpty() }
    @ReactProp(name = "resetRevision", defaultInt = 0) fun reset(v: LavaTextFieldView, value: Int) { v.resetRevision = value }
    @ReactProp(name = "editable", defaultBoolean = true) fun editable(v: LavaTextFieldView, value: Boolean) { v.editable = value }
    @ReactProp(name = "autoFocus", defaultBoolean = false) fun focus(v: LavaTextFieldView, value: Boolean) { v.autoFocus = value }
    @ReactProp(name = "autoFocusReady", defaultBoolean = true) fun focusReady(v: LavaTextFieldView, value: Boolean) { v.autoFocusReady = value }
    @ReactProp(name = "fontPointSize", defaultFloat = 0f) fun font(v: LavaTextFieldView, value: Float) { v.fontPointSize = value }
    @ReactProp(name = "lineHeight", defaultFloat = 0f) fun line(v: LavaTextFieldView, value: Float) { v.lineHeight = value }
    @ReactProp(name = "keyboardType") fun keyboard(v: LavaTextFieldView, value: String?) { v.keyboardType = value.orEmpty() }
    @ReactProp(name = "autoCapitalize") fun capitalization(v: LavaTextFieldView, value: String?) { v.autoCapitalize = value.orEmpty() }
    @ReactProp(name = "autoCorrect", defaultBoolean = false) fun correction(v: LavaTextFieldView, value: Boolean) { v.autoCorrect = value }
    @ReactProp(name = "spellCheck", defaultBoolean = false) fun spelling(v: LavaTextFieldView, value: Boolean) { v.spellCheck = value }
    @ReactProp(name = "smartInsertDelete", defaultBoolean = false) fun smartInsertion(v: LavaTextFieldView, value: Boolean) { v.smartInsertDelete = value }
    @ReactProp(name = "characterLimit", defaultInt = 0) fun limit(v: LavaTextFieldView, value: Int) { v.characterLimit = value }
    @ReactProp(name = "maxLength", defaultInt = -1) fun maxLength(v: LavaTextFieldView, value: Int) { v.maxLength = value }
    @ReactProp(name = "monospace", defaultBoolean = false) fun monospace(v: LavaTextFieldView, value: Boolean) { v.monospace = value }
    @ReactProp(name = "textColor", customType = "Color") fun color(v: LavaTextFieldView, value: Int?) { if (value != null) v.textColor = value }
    @ReactProp(name = "placeholderTextColor", customType = "Color") fun hintColor(v: LavaTextFieldView, value: Int?) { if (value != null) v.placeholderTextColor = value }
    @ReactProp(name = "selectionColor", customType = "Color") fun selectionColor(v: LavaTextFieldView, value: Int?) { if (value != null) v.selectionColor = value }
    @ReactProp(name = "clearButtonMode") fun clearButton(v: LavaTextFieldView, value: String?) { v.clearButtonMode = value.orEmpty() }
    override fun getExportedCustomDirectEventTypeConstants(): Map<String, Any> = mapOf(
        "topChange" to mapOf("registrationName" to "onChange"),
        "topSubmit" to mapOf("registrationName" to "onSubmit"),
        "topFocusChange" to mapOf("registrationName" to "onFocusChange"),
        "topSizeChange" to mapOf("registrationName" to "onSizeChange"),
    )
}
