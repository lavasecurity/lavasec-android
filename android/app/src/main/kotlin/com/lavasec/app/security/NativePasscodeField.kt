package com.lavasec.app.security

import android.content.Context
import android.os.Build
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.appcompat.widget.AppCompatEditText

/** Native-only password field. A stale IME cannot borrow a later prompt or retry's authority. */
internal class NativePasscodeField(context: Context, private val permitted: () -> Boolean) : AppCompatEditText(context) {
    private var inputEpoch = 0L
    private fun inputAdmitted() = isEnabled && permitted()
    override fun setEnabled(enabled: Boolean) { if (!enabled) ++inputEpoch; super.setEnabled(enabled) }
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previous: android.graphics.Rect?) {
        if (!gainFocus) ++inputEpoch
        super.onFocusChanged(gainFocus, direction, previous)
    }
    override fun onDetachedFromWindow() { ++inputEpoch; text?.clear(); super.onDetachedFromWindow() }
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!inputAdmitted() || !hasFocus()) return null
        val connection = super.onCreateInputConnection(outAttrs) ?: return null
        val epoch = inputEpoch
        fun current() = epoch == inputEpoch && inputAdmitted() && hasFocus()
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
        if (!inputAdmitted()) { info.text = null; info.hintText = null; info.contentDescription = null }
        if (!inputAdmitted()) {
            info.isEditable = false
            info.removeAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)
            info.removeAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_SELECTION)
        }
    }
}
