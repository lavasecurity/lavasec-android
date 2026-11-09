package com.lavasec.app.reactnative

import android.content.Context
import android.os.Build
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.widget.AppCompatEditText
import android.widget.LinearLayout
import android.widget.TextView
import com.facebook.react.bridge.ReactContext
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.annotations.ReactProp
import com.lavasec.app.R

/** Secret text never becomes a Fabric prop/event. The enclosing native visit secures the window. */
internal class LavaBackupCredentialView(context: Context) : LinearLayout(context) {
    var ownerID = ""
    var fontScale = 1f
    var sizeChanged: (Double) -> Unit = {}
    private var lastHeight = -1
    private val host get() = (context as? ReactContext)?.currentActivity as? LavaReactActivity
    internal var suppliedFlow: NativeAccountBackupFlow? = null
    internal var suppliedAuthority: (() -> Boolean)? = null
    private val flow get() = (suppliedFlow ?: host?.accountBackupFlow)?.takeIf { it.id == ownerID }
    private var inputEpoch = 0L
    private var boundOwner = ""
    private fun nativeAuthority() = suppliedAuthority?.invoke() ?: (host?.admitsNativeFieldInput() == true)
    private fun inputAdmitted() = nativeAuthority() && flow?.canEditCredential() == true
    private val phrase = TextView(context)
    internal val copy = android.widget.Button(context).apply {
        setText(android.R.string.copy)
        isAllCaps = false
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.GradientDrawable().apply {
                setColor(0x0f000000); cornerRadius = 16 * resources.displayMetrics.density
            })
            addState(intArrayOf(), android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
        minHeight = (48 * resources.displayMetrics.density).toInt()
        setOnClickListener {
            if (nativeAuthority()) flow?.copyRecoveryPhrase { NativeRecoveryClipboard.copy(context, it) }
        }
    }
    internal val input = object : AppCompatEditText(context) {
        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
            if (!inputAdmitted() || !hasFocus()) return null
            val connection = super.onCreateInputConnection(outAttrs) ?: return null
            val epoch = inputEpoch
            val owner = flow
            fun current() = epoch == inputEpoch && flow === owner && inputAdmitted() && hasFocus()
            return object : InputConnectionWrapper(connection, false) {
                // A retained private frame is paint, never permission for a stale
                // IME connection to acquire a new copy or change its selection.
                override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence? =
                    if (current()) super.getTextBeforeCursor(length, flags) else ""
                override fun getTextAfterCursor(length: Int, flags: Int): CharSequence? =
                    if (current()) super.getTextAfterCursor(length, flags) else ""
                override fun getSelectedText(flags: Int): CharSequence? =
                    if (current()) super.getSelectedText(flags) else null
                override fun getExtractedText(request: android.view.inputmethod.ExtractedTextRequest?, flags: Int): android.view.inputmethod.ExtractedText? =
                    if (current()) super.getExtractedText(request, flags) else null
                override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): android.view.inputmethod.SurroundingText? =
                    if (current()) super.getSurroundingText(beforeLength, afterLength, flags) else null
                override fun getCursorCapsMode(reqModes: Int): Int =
                    if (current()) super.getCursorCapsMode(reqModes) else 0
                override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean =
                    current() && super.requestCursorUpdates(cursorUpdateMode)
                override fun requestCursorUpdates(cursorUpdateMode: Int, cursorUpdateFilter: Int): Boolean =
                    current() && super.requestCursorUpdates(cursorUpdateMode, cursorUpdateFilter)
                override fun takeSnapshot(): android.view.inputmethod.TextSnapshot? =
                    if (current()) super.takeSnapshot() else null
                override fun commitText(text: CharSequence, newCursorPosition: Int, textAttribute: android.view.inputmethod.TextAttribute?): Boolean =
                    current() && super.commitText(text, newCursorPosition, textAttribute)
                override fun setComposingText(text: CharSequence, newCursorPosition: Int, textAttribute: android.view.inputmethod.TextAttribute?): Boolean =
                    current() && super.setComposingText(text, newCursorPosition, textAttribute)
                override fun setComposingRegion(start: Int, end: Int, textAttribute: android.view.inputmethod.TextAttribute?): Boolean =
                    current() && super.setComposingRegion(start, end, textAttribute)
                override fun replaceText(start: Int, end: Int, text: CharSequence, newCursorPosition: Int, textAttribute: android.view.inputmethod.TextAttribute?): Boolean =
                    current() && super.replaceText(start, end, text, newCursorPosition, textAttribute)
                override fun beginBatchEdit(): Boolean = current() && super.beginBatchEdit()
                override fun endBatchEdit(): Boolean = current() && super.endBatchEdit()
                override fun clearMetaKeyStates(states: Int): Boolean = current() && super.clearMetaKeyStates(states)
                override fun reportFullscreenMode(enabled: Boolean): Boolean = current() && super.reportFullscreenMode(enabled)
                override fun performSpellCheck(): Boolean = current() && super.performSpellCheck()
                override fun performPrivateCommand(action: String?, data: android.os.Bundle?): Boolean =
                    current() && super.performPrivateCommand(action, data)
                override fun commitContent(inputContentInfo: android.view.inputmethod.InputContentInfo, flags: Int, opts: android.os.Bundle?): Boolean =
                    current() && super.commitContent(inputContentInfo, flags, opts)
                override fun setImeConsumesInput(imeConsumesInput: Boolean): Boolean =
                    current() && super.setImeConsumesInput(imeConsumesInput)
                override fun performHandwritingGesture(gesture: android.view.inputmethod.HandwritingGesture, executor: java.util.concurrent.Executor?, consumer: java.util.function.IntConsumer?) {
                    if (current()) super.performHandwritingGesture(gesture, executor, consumer)
                    else if (consumer != null) {
                        val refusal = Runnable { consumer.accept(InputConnection.HANDWRITING_GESTURE_RESULT_CANCELLED) }
                        if (executor != null) executor.execute(refusal) else refusal.run()
                    }
                }
                override fun previewHandwritingGesture(gesture: android.view.inputmethod.PreviewableHandwritingGesture, cancellationSignal: android.os.CancellationSignal?): Boolean =
                    current() && super.previewHandwritingGesture(gesture, cancellationSignal)
                override fun requestTextBoundsInfo(bounds: android.graphics.RectF, executor: java.util.concurrent.Executor, consumer: java.util.function.Consumer<android.view.inputmethod.TextBoundsInfoResult>) {
                    // This callback and its result type exist only on Android 14+.
                    // A stale/reflection caller on an older host cannot enter it.
                    if (Build.VERSION.SDK_INT < 34) return
                    if (current()) super.requestTextBoundsInfo(bounds, executor, consumer)
                    else executor.execute { consumer.accept(android.view.inputmethod.TextBoundsInfoResult(android.view.inputmethod.TextBoundsInfoResult.CODE_FAILED)) }
                }
                override fun setSelection(start: Int, end: Int): Boolean =
                    current() && super.setSelection(start, end)
                override fun performEditorAction(editorAction: Int): Boolean =
                    current() && super.performEditorAction(editorAction)
                override fun commitCompletion(text: android.view.inputmethod.CompletionInfo?): Boolean =
                    current() && super.commitCompletion(text)
                override fun commitCorrection(correctionInfo: android.view.inputmethod.CorrectionInfo?): Boolean =
                    current() && super.commitCorrection(correctionInfo)
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
                    current() && super.commitText(text, newCursorPosition)
                override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
                    current() && super.setComposingText(text, newCursorPosition)
                override fun setComposingRegion(start: Int, end: Int): Boolean =
                    current() && super.setComposingRegion(start, end)
                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
                    current() && super.deleteSurroundingText(beforeLength, afterLength)
                override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
                    current() && super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
                override fun performContextMenuAction(id: Int): Boolean =
                    current() && super.performContextMenuAction(id)
                override fun sendKeyEvent(event: KeyEvent): Boolean = current() && super.sendKeyEvent(event)
                override fun finishComposingText(): Boolean {
                    if (!current()) return false
                    val result = super.finishComposingText()
                    return result
                }
            }
        }
        override fun onTextContextMenuItem(id: Int): Boolean = inputAdmitted() && super.onTextContextMenuItem(id)
        override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean =
            inputAdmitted() && super.performAccessibilityAction(action, arguments)
        override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            if (!nativeAuthority() || flow?.readable() != true) { info.text = null; info.hintText = null; info.contentDescription = null }
            if (!inputAdmitted()) {
                info.isEditable = false
                info.removeAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)
                info.removeAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_SELECTION)
            }
        }
    }
    private var rendering = false
    private var stopFlow: (() -> Unit)? = null
    private var stopAuthority: (() -> Unit)? = null
    init {
        orientation = VERTICAL
        isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        phrase.textSize = 18f
        input.textSize = 16f
        input.setOnFocusChangeListener { _, focused -> if (!focused) ++inputEpoch }
        input.isSaveEnabled = false
        input.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        input.filters = arrayOf(android.text.InputFilter.LengthFilter(1024))
        input.imeOptions = input.imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        addView(phrase, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(copy, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(input, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (rendering) return
                val chars = s?.toString()?.toCharArray() ?: CharArray(0)
                try { if (!nativeAuthority() || flow?.replaceCredential(chars) != true) refresh() }
                finally { chars.fill('\u0000') }
            }
        })
    }
    fun bind() {
        if (boundOwner != ownerID) { ++inputEpoch; boundOwner = ownerID }
        stopFlow?.invoke(); stopAuthority?.invoke()
        stopFlow = flow?.observe {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) refresh() else post { refresh() }
        }
        stopAuthority = host?.observeNativeFieldAuthority { refresh() }
        phrase.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 18f * fontScale)
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16f * fontScale)
        copy.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16f * fontScale)
        refresh()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val desired = maxOf(measuredHeight, (48 * resources.displayMetrics.density).toInt())
        setMeasuredDimension(measuredWidth, resolveSize(desired, heightMeasureSpec))
        if (desired != lastHeight) {
            lastHeight = desired
            post { sizeChanged(desired / resources.displayMetrics.density.toDouble()) }
        }
    }
    private fun refresh() {
        val owner = flow
        val value = if (owner?.readable() == true && nativeAuthority()) owner.credential() else null
        rendering = true
        try {
            phrase.text = value?.first.orEmpty()
            phrase.visibility = if (value?.first.isNullOrEmpty()) GONE else VISIBLE
            copy.visibility = if (owner?.kind == "backupSetup" && !value?.first.isNullOrEmpty()) VISIBLE else GONE
            copy.isEnabled = value != null && owner?.active() == true && nativeAuthority()
            val text = value?.second.orEmpty()
            if (input.text.toString() != text) input.setText(text)
            input.hint = context.getString(if (owner?.kind == "backupSetup")
                R.string.backup_setup_confirm_hint else R.string.backup_restore_hint)
            input.visibility = if (owner?.kind == "account") GONE else VISIBLE
            val editable = value != null && inputAdmitted()
            if (input.isEnabled && !editable) ++inputEpoch
            input.isEnabled = editable
            if (!input.isEnabled) input.clearFocus()
            visibility = if (value == null) INVISIBLE else VISIBLE
        } finally { rendering = false }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); bind() }
    override fun onDetachedFromWindow() {
        ++inputEpoch
        stopFlow?.invoke(); stopFlow = null; stopAuthority?.invoke(); stopAuthority = null
        rendering = true; phrase.text = ""; input.text?.clear(); rendering = false
        super.onDetachedFromWindow()
    }
}

internal class LavaBackupCredentialManager : SimpleViewManager<LavaBackupCredentialView>() {
    override fun getName() = "LavaBackupCredential"
    override fun createViewInstance(context: ThemedReactContext) = LavaBackupCredentialView(context).apply {
        sizeChanged = { height ->
            @Suppress("DEPRECATION")
            context.getJSModule(com.facebook.react.uimanager.events.RCTEventEmitter::class.java).receiveEvent(id, "topSizeChange",
                com.facebook.react.bridge.Arguments.createMap().apply { putDouble("height", height) })
        }
    }
    @ReactProp(name = "ownerID") fun owner(view: LavaBackupCredentialView, value: String?) { view.ownerID = value.orEmpty() }
    @ReactProp(name = "fontScale", defaultFloat = 1f) fun scale(view: LavaBackupCredentialView, value: Float) { view.fontScale = value.coerceIn(0.5f, 4f) }
    override fun getExportedCustomDirectEventTypeConstants(): Map<String, Any> =
        mapOf("topSizeChange" to mapOf("registrationName" to "onSizeChange"))
    override fun onAfterUpdateTransaction(view: LavaBackupCredentialView) { super.onAfterUpdateTransaction(view); view.bind() }
}
