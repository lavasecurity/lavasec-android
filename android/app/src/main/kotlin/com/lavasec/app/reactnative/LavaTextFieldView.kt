package com.lavasec.app.reactnative

import android.content.Context
import android.graphics.Color
import android.icu.text.BreakIterator
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.FrameLayout
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.ReactContext
import com.facebook.react.common.LifecycleState
import com.facebook.react.views.textinput.ReactEditText
import java.util.Locale

/** A bounded native editor leaf; RN composes labels, validation, sheets and actions. */
internal class LavaTextFieldView(context: Context) : FrameLayout(context), LifecycleEventListener {
    var inputLabel = ""
    var placeholder = ""
    var kind = ""
    var ownerID = ""
    var resetRevision = 0
    var seedValue = ""
    var editable = true
    var autoFocus = false
    var autoFocusReady = true
    var fontPointSize = 0f
    var lineHeight = 0f
    var keyboardType = ""
    var autoCapitalize = "none"
    var autoCorrect = false
    var spellCheck = false
    var smartInsertDelete = false
    var characterLimit = 0
    var maxLength = -1
    var monospace = false
    var clearButtonMode = "never"
    var textColor = Color.BLACK
    var placeholderTextColor = Color.GRAY
    var selectionColor = Color.rgb(52, 199, 89)
    var emit: (String, Map<String, Any>) -> Unit = { _, _ -> }
    internal var suppliedPort: LavaWireGuardInputPort? = null
    internal var suppliedPresentationAuthority: (() -> Boolean)? = null
    private var nativeHost: LavaReactActivity? = null
    private var stopPresentationObserving: (() -> Unit)? = null
    private val reactContext = context as? ReactContext
    private var hostActive = reactContext?.lifecycleState == LifecycleState.RESUMED
    private var seeded = false
    private var previousOwner = ""
    private var previousKind = ""
    private var previousReset = 0
    private var observedPort: LavaWireGuardInputPort? = null
    private var observedOwner = ""
    private var stopObserving: (() -> Unit)? = null
    private var painting = false
    private var acceptedConfidentialOwner = ""
    private var acceptedConfidentialGeneration: Long? = null
    private var lastHeight = -1f
    private var nameWasSuspended = false
    private var autoFocusPending = false
    private val confidential get() = kind == "wireGuard"
    private val nameField get() = kind == "wireGuardName"
    private fun port(): LavaWireGuardInputPort? = suppliedPort ?: nativeHost?.wireGuardInputPort
    private fun presentationAuthorized(): Boolean = hostActive &&
        (suppliedPresentationAuthority?.invoke() ?: (nativeHost?.admitsNativeFieldInput() == true))
    private fun admitsEdits(): Boolean = editable && presentationAuthorized() &&
        (!confidential && !nameField || when {
            confidential -> port()?.snapshot(ownerID)?.let { it.authorized && it.revealed } == true
            else -> port()?.canEditName(ownerID) == true
        })

    // A successor route's Ready cannot revive a connection belonging to an
    // underlying field that lost focus during the concealment-only transition.
    // The same retained field may acquire input again after its user refocuses it.
    private fun admitsInputConnection(): Boolean = admitsEdits() && editor.hasFocus()

    internal val editor: ReactEditText = object : ReactEditText(context) {
        override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
            if (!admitsEdits()) return false
            if (action != android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT) {
                return super.performAccessibilityAction(action, arguments)
            }
            val originalOwner = ownerID
            val originalKind = kind
            val originalReset = resetRevision
            val previousPainting = painting
            // One accessibility replacement owns one edit publication. Native
            // text/focus/IME callbacks can run inside that same framework call;
            // publish the final buffer only after it returns successfully.
            painting = true
            val applied = try {
                super.performAccessibilityAction(action, arguments)
            } finally {
                painting = previousPainting
            }
            if (applied && !previousPainting && ownerID == originalOwner &&
                kind == originalKind && resetRevision == originalReset) publishEdit()
            return applied
        }
        override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            if (!presentationAuthorized()) {
                // A cached node can ask again after native revocation even though
                // the enclosing RN subtree has already been removed from AT.
                info.text = null
                info.contentDescription = null
                info.hintText = null
            }
            // A native Ready can precede React's exact interaction receipt or
            // a local read-only prop. Advertise only the write actions this
            // same editor actually admits; keep its authorized display buffer.
            if (!admitsEdits()) {
                info.isEditable = false
                info.removeAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)
                info.removeAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_SELECTION)
            }
        }
        override fun onTextContextMenuItem(id: Int): Boolean =
            if (admitsEdits()) super.onTextContextMenuItem(id) else false
        override fun onTouchEvent(event: MotionEvent): Boolean =
            if (admitsEdits()) super.onTouchEvent(event) else false
        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
            if (admitsEdits()) super.onKeyDown(keyCode, event) else true
        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
            if (!admitsInputConnection()) return null
            val connection = super.onCreateInputConnection(outAttrs) ?: return null
            return object : InputConnectionWrapper(connection, false) {
                // A retained private frame is paint, never permission for a stale
                // IME connection to acquire a new copy or change its selection.
                override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence? =
                    if (admitsInputConnection()) super.getTextBeforeCursor(length, flags) else ""
                override fun getTextAfterCursor(length: Int, flags: Int): CharSequence? =
                    if (admitsInputConnection()) super.getTextAfterCursor(length, flags) else ""
                override fun getSelectedText(flags: Int): CharSequence? =
                    if (admitsInputConnection()) super.getSelectedText(flags) else null
                override fun getExtractedText(request: android.view.inputmethod.ExtractedTextRequest?, flags: Int): android.view.inputmethod.ExtractedText? =
                    if (admitsInputConnection()) super.getExtractedText(request, flags) else null
                override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): android.view.inputmethod.SurroundingText? =
                    if (admitsInputConnection()) super.getSurroundingText(beforeLength, afterLength, flags) else null
                override fun getCursorCapsMode(reqModes: Int): Int =
                    if (admitsInputConnection()) super.getCursorCapsMode(reqModes) else 0
                override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean =
                    admitsInputConnection() && super.requestCursorUpdates(cursorUpdateMode)
                override fun requestCursorUpdates(cursorUpdateMode: Int, cursorUpdateFilter: Int): Boolean =
                    admitsInputConnection() && super.requestCursorUpdates(cursorUpdateMode, cursorUpdateFilter)
                override fun takeSnapshot(): android.view.inputmethod.TextSnapshot? =
                    if (admitsInputConnection()) super.takeSnapshot() else null
                override fun commitText(text: CharSequence, newCursorPosition: Int, textAttribute: android.view.inputmethod.TextAttribute?): Boolean =
                    admitsInputConnection() && super.commitText(text, newCursorPosition, textAttribute)
                override fun setComposingText(text: CharSequence, newCursorPosition: Int, textAttribute: android.view.inputmethod.TextAttribute?): Boolean =
                    admitsInputConnection() && super.setComposingText(text, newCursorPosition, textAttribute)
                override fun setComposingRegion(start: Int, end: Int, textAttribute: android.view.inputmethod.TextAttribute?): Boolean =
                    admitsInputConnection() && super.setComposingRegion(start, end, textAttribute)
                override fun replaceText(start: Int, end: Int, text: CharSequence, newCursorPosition: Int, textAttribute: android.view.inputmethod.TextAttribute?): Boolean =
                    admitsInputConnection() && super.replaceText(start, end, text, newCursorPosition, textAttribute)
                override fun beginBatchEdit(): Boolean = admitsInputConnection() && super.beginBatchEdit()
                override fun endBatchEdit(): Boolean = admitsInputConnection() && super.endBatchEdit()
                override fun clearMetaKeyStates(states: Int): Boolean = admitsInputConnection() && super.clearMetaKeyStates(states)
                override fun reportFullscreenMode(enabled: Boolean): Boolean = admitsInputConnection() && super.reportFullscreenMode(enabled)
                override fun performSpellCheck(): Boolean = admitsInputConnection() && super.performSpellCheck()
                override fun performPrivateCommand(action: String?, data: android.os.Bundle?): Boolean =
                    admitsInputConnection() && super.performPrivateCommand(action, data)
                override fun commitContent(inputContentInfo: android.view.inputmethod.InputContentInfo, flags: Int, opts: android.os.Bundle?): Boolean =
                    admitsInputConnection() && super.commitContent(inputContentInfo, flags, opts)
                override fun setImeConsumesInput(imeConsumesInput: Boolean): Boolean =
                    admitsInputConnection() && super.setImeConsumesInput(imeConsumesInput)
                override fun performHandwritingGesture(gesture: android.view.inputmethod.HandwritingGesture, executor: java.util.concurrent.Executor?, consumer: java.util.function.IntConsumer?) {
                    if (admitsInputConnection()) super.performHandwritingGesture(gesture, executor, consumer)
                    else if (consumer != null) {
                        val refusal = Runnable { consumer.accept(InputConnection.HANDWRITING_GESTURE_RESULT_CANCELLED) }
                        if (executor != null) executor.execute(refusal) else refusal.run()
                    }
                }
                override fun previewHandwritingGesture(gesture: android.view.inputmethod.PreviewableHandwritingGesture, cancellationSignal: android.os.CancellationSignal?): Boolean =
                    admitsInputConnection() && super.previewHandwritingGesture(gesture, cancellationSignal)
                override fun requestTextBoundsInfo(bounds: android.graphics.RectF, executor: java.util.concurrent.Executor, consumer: java.util.function.Consumer<android.view.inputmethod.TextBoundsInfoResult>) {
                    // This callback and its result type exist only on Android 14+.
                    // A stale/reflection caller on an older host cannot enter it.
                    if (Build.VERSION.SDK_INT < 34) return
                    if (admitsInputConnection()) super.requestTextBoundsInfo(bounds, executor, consumer)
                    else executor.execute { consumer.accept(android.view.inputmethod.TextBoundsInfoResult(android.view.inputmethod.TextBoundsInfoResult.CODE_FAILED)) }
                }
                override fun setSelection(start: Int, end: Int): Boolean =
                    admitsInputConnection() && super.setSelection(start, end)
                override fun performEditorAction(editorAction: Int): Boolean =
                    admitsInputConnection() && super.performEditorAction(editorAction)
                override fun commitCompletion(text: android.view.inputmethod.CompletionInfo?): Boolean =
                    admitsInputConnection() && super.commitCompletion(text)
                override fun commitCorrection(correctionInfo: android.view.inputmethod.CorrectionInfo?): Boolean =
                    admitsInputConnection() && super.commitCorrection(correctionInfo)
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
                    admitsInputConnection() && super.commitText(text, newCursorPosition)
                override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
                    admitsInputConnection() && super.setComposingText(text, newCursorPosition)
                override fun setComposingRegion(start: Int, end: Int): Boolean =
                    admitsInputConnection() && super.setComposingRegion(start, end)
                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
                    admitsInputConnection() && super.deleteSurroundingText(beforeLength, afterLength)
                override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
                    admitsInputConnection() && super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
                override fun performContextMenuAction(id: Int): Boolean =
                    admitsInputConnection() && super.performContextMenuAction(id)
                override fun sendKeyEvent(event: KeyEvent): Boolean = admitsInputConnection() && super.sendKeyEvent(event)
                override fun finishComposingText(): Boolean {
                    if (!admitsInputConnection()) return false
                    val result = super.finishComposingText()
                    publishEdit()
                    return result
                }
            }
        }
    }.apply {
        background = null
        setPadding(0, 0, 0, 0)
        setSingleLine(true)
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE && admitsEdits()) {
                val buffer = text ?: return@setOnEditorActionListener false
                BaseInputConnection.removeComposingSpans(buffer)
                publishEdit()
                if (!confidential) emit("topSubmit", mapOf("text" to text.toString()))
                true
            } else false
        }
        onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
            // User focus also consumes the one opening request. A later
            // transition receipt must not refocus after that user blurs.
            if (focused) autoFocusPending = false
            if (focused && kind == "emoji") text?.let(android.text.Selection::selectAll)
            if (!focused && !painting) {
                text?.let(BaseInputConnection::removeComposingSpans)
                publishEdit()
            }
            updateClearButton()
            emit("topFocusChange", mapOf("focused" to focused))
        }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { if (!painting) publishEdit() }
        })
    }

    private val clearButton = android.widget.ImageButton(context).apply {
        setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        background = null
        contentDescription = resources.getString(com.lavasec.app.presentation.R.string.privacy_data_clear_section)
        visibility = GONE
        setOnClickListener { if (admitsEdits()) editor.setText("") }
    }
    private val revealButton = android.widget.Button(context).apply {
        setText(com.lavasec.app.R.string.react_native_show_configuration)
        visibility = GONE
        setOnClickListener {
            if (presentationAuthorized() && port()?.snapshot(ownerID)?.authorized == true && port()?.reveal(ownerID) == true) reconcileAuthority()
        }
    }
    init {
        addView(editor, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val size = (44 * resources.displayMetrics.density).toInt()
        addView(clearButton, LayoutParams(size, size, android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL))
        addView(revealButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
    }
    private fun updateClearButton() {
        val shown = !confidential && clearButtonMode == "while-editing" && editor.hasFocus() && editor.length() > 0 && admitsEdits()
        clearButton.visibility = if (shown) VISIBLE else GONE
        editor.setPadding(editor.paddingLeft, editor.paddingTop, if (shown) (44 * resources.displayMetrics.density).toInt() else 0, editor.paddingBottom)
    }

    internal fun commitProps() {
        val previousPainting = painting
        painting = true
        try {
        val ownerChanged = !seeded || previousOwner != ownerID || previousKind != kind
        val resetChanged = previousReset != resetRevision
        if (!autoFocus) autoFocusPending = false
        else if (ownerChanged) autoFocusPending = true
        if (ownerChanged) {
            stopObserving?.invoke(); stopObserving = null
            observedPort = null; observedOwner = ""
            acceptedConfidentialOwner = ""; acceptedConfidentialGeneration = null
            if (confidential || previousKind == "wireGuard") paint("")
        }
        editor.isSaveEnabled = !confidential && kind != "passcode"
        editor.importantForAutofill = if (confidential) View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS else View.IMPORTANT_FOR_AUTOFILL_AUTO
        editor.imeOptions = (if (kind == "multiline" || kind == "prose" || confidential) EditorInfo.IME_ACTION_NONE else EditorInfo.IME_ACTION_DONE) or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or (if (confidential || kind == "passcode") EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING else 0)
        editor.contentDescription = inputLabel
        editor.hint = placeholder
        editor.setTextColor(textColor)
        editor.setHintTextColor(placeholderTextColor)
        editor.highlightColor = selectionColor
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            editor.textCursorDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(selectionColor); setSize((2 * resources.displayMetrics.density).toInt().coerceAtLeast(1), editor.lineHeight)
            }
        }
        editor.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (fontPointSize > 0) fontPointSize / resources.configuration.fontScale else 17f)
        val multiline = confidential || kind == "multiline" || kind == "prose"
        var traits = when {
            kind == "passcode" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            keyboardType == "email-address" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            keyboardType == "url" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            else -> InputType.TYPE_CLASS_TEXT
        }
        if (multiline) traits = traits or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        traits = traits or when (autoCapitalize) {
            "sentences" -> InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            "words" -> InputType.TYPE_TEXT_FLAG_CAP_WORDS
            "characters" -> InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            else -> 0
        }
        if (autoCorrect) traits = traits or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        if (!spellCheck && !autoCorrect) traits = traits or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        if (editor.inputType != traits) editor.inputType = traits
        editor.typeface = if (monospace) android.graphics.Typeface.MONOSPACE else android.graphics.Typeface.DEFAULT
        editor.setSingleLine(!multiline)
        editor.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        val prosePadding = if (kind == "prose") (8 * resources.displayMetrics.density).toInt() else 0
        editor.setPadding(0, prosePadding, 0, prosePadding)
        editor.setLineSpacing(if (lineHeight > 0) (lineHeight * resources.displayMetrics.density - editor.paint.fontSpacing).coerceAtLeast(0f) else 0f, 1f)
        if (!confidential && (ownerChanged || resetChanged)) paint(seedValue)
        seeded = true; previousOwner = ownerID; previousKind = kind; previousReset = resetRevision
        observeOwner()
        reconcileAuthority()
        reportHeight()
        } finally { painting = previousPainting }
    }

    private fun observeOwner() {
        if (!confidential && !nameField) return
        val current = port()
        if (current === observedPort && observedOwner == ownerID) return
        stopObserving?.invoke()
        observedPort = current; observedOwner = ownerID
        stopObserving = current?.observe(ownerID) { if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) reconcileAuthority() else post { reconcileAuthority() } }
    }

    internal fun reconcileAuthority() {
        if (confidential) {
            val state = port()?.snapshot(ownerID)
            val currentAccepted = acceptedConfidentialOwner == ownerID && acceptedConfidentialGeneration == state?.generation
            val authorized = presentationAuthorized() && state?.authorized == true && state.revealed
            if (authorized) {
                // Native owner contains unsaved text only. Saved key custody is never queried.
                state?.text?.let { if (editor.text.toString() != it) paint(it) }
                acceptedConfidentialOwner = ownerID; acceptedConfidentialGeneration = state?.generation
            } else if (!(state?.retainAcceptedDisplay == true && state.revealed && currentAccepted)) {
                paint(""); editor.clearFocus()
                acceptedConfidentialOwner = ""; acceptedConfidentialGeneration = null
            }
        }
        val state = if (confidential) port()?.snapshot(ownerID) else null
        val showReveal = confidential && presentationAuthorized() && state?.authorized == true && !state.revealed
        revealButton.visibility = if (showReveal) VISIBLE else GONE
        revealButton.isEnabled = showReveal
        editor.visibility = if (showReveal) INVISIBLE else VISIBLE
        val admitted = admitsEdits()
        if (nameField && !admitted) nameWasSuspended = true
        else if (nameField && nameWasSuspended) {
            nameWasSuspended = false
            // A native blur can commit marked text while JS read authority is
            // revoked. Reconcile the retained buffer only after its owner returns.
            emit("topChange", mapOf("text" to editor.text.toString()))
        }
        editor.isFocusable = admitted || confidential && editor.hasFocus()
        editor.isFocusableInTouchMode = editor.isFocusable
        editor.importantForAccessibility = if (admitted || presentationAuthorized() && !confidential && !nameField) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        // Retaining an already painted Security-off confidential frame keeps the
        // responder. InputConnection/edit-menu/native callbacks independently deny edits.
        if (!admitted && !confidential) editor.clearFocus()
        // Native paint/read admission may settle during a modal animation.
        // Only its actual opening transition receipt admits automatic focus.
        if (autoFocusPending && autoFocusReady && admitted) { autoFocusPending = false; editor.requestFocusFromJS() }
        updateClearButton()
    }

    private fun publishEdit() {
        if (painting) return
        if (!admitsEdits()) { if (confidential) reconcileAuthority(); return }
        val buffer = editor.text ?: return
        if (BaseInputConnection.getComposingSpanStart(buffer) < 0) {
            val limit = if (kind == "emoji") 1 else characterLimit
            val bounded = LavaTextFieldBufferPolicy.bound(buffer.toString(), limit, maxLength)
            if (bounded != buffer.toString()) paint(bounded)
        }
        val value = editor.text?.toString() ?: return
        if (confidential) {
            if (port()?.replace(ownerID, value) != true) reconcileAuthority()
        } else emit("topChange", mapOf("text" to value))
        updateClearButton()
        reportHeight()
    }

    private fun paint(value: String) {
        if (editor.text.toString() == value) return
        val previousPainting = painting
        painting = true
        try { editor.setText(value); editor.setSelection(editor.length()) } finally { painting = previousPainting }
    }

    private fun reportHeight() {
        val density = resources.displayMetrics.density
        val natural = (editor.layout?.height ?: editor.lineHeight) + editor.totalPaddingTop + editor.totalPaddingBottom
        val height = (natural / density).coerceAtLeast(if (kind == "prose") 96f else 44f)
        if (height != lastHeight) { lastHeight = height; emit("topSizeChange", mapOf("height" to height)) }
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) { super.onLayout(changed, l, t, r, b); reportHeight() }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        nativeHost = reactContext?.currentActivity as? LavaReactActivity
        stopPresentationObserving?.invoke()
        stopPresentationObserving = nativeHost?.observeNativeFieldAuthority(::reconcileAuthority)
        reactContext?.addLifecycleEventListener(this)
        observeOwner(); reconcileAuthority()
    }
    override fun onDetachedFromWindow() {
        stopObserving?.invoke(); stopObserving = null; observedPort = null
        stopPresentationObserving?.invoke(); stopPresentationObserving = null; nativeHost = null
        if (confidential) { paint(""); editor.clearFocus(); acceptedConfidentialOwner = ""; acceptedConfidentialGeneration = null }
        reactContext?.removeLifecycleEventListener(this)
        super.onDetachedFromWindow()
    }
    override fun onHostResume() { hostActive = true; observeOwner(); reconcileAuthority() }
    override fun onHostPause() { hostActive = false; reconcileAuthority() }
    override fun onHostDestroy() { hostActive = false; if (confidential) paint(""); reconcileAuthority() }
}

internal object LavaTextFieldBufferPolicy {
    /** Explicit character limits count graphemes; maxLength counts UTF-16 without splitting one. */
    fun bound(value: String, limit: Int, maxLength: Int = -1): String {
        if (limit <= 0 && (maxLength < 0 || value.length <= maxLength)) return value
        val boundaries = BreakIterator.getCharacterInstance(Locale.ROOT)
        boundaries.setText(value)
        var end = boundaries.first()
        var characters = 0
        while (true) {
            val next = boundaries.next()
            if (next == BreakIterator.DONE) return value
            if ((limit > 0 && characters == limit) || (maxLength >= 0 && next > maxLength))
                return value.substring(0, end)
            end = next
            characters += 1
        }
    }
}
