package com.lavasec.app.security

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import com.lavasec.app.presentation.R
import com.lavasec.app.reactnative.NativePasscodeComposition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

internal typealias NativeSecurityPresenter = suspend (
    SecurityAuthenticationRequest, suspend (String) -> Boolean, () -> Unit,
) -> Unit

/** Android presentation for the canonical native-only passcode request. */
internal suspend fun Activity.presentPasscodeAuthentication(
    verify: suspend (String) -> Boolean,
    cancel: () -> Unit,
) = withContext(Dispatchers.Main.immediate) {
    if (isFinishing || isDestroyed) return@withContext
    coroutineScope {
        suspendCancellableCoroutine<Unit> { continuation ->
            val input = NativePasscodeField(this@presentPasscodeAuthentication) {
                continuation.isActive && !isFinishing && !isDestroyed &&
                    (this@presentPasscodeAuthentication as? com.lavasec.app.reactnative.LavaReactActivity)?.foreground != false
            }.apply {
                id = android.R.id.edit
                // setSingleLine replaces the transformation; install password input mode afterwards.
                setSingleLine()
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                hint = getString(R.string.security_enter_passcode)
                isSaveEnabled = false
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                imeOptions = imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            }
            lateinit var dialog: AlertDialog
            val composition = NativePasscodeComposition.create(this@presentPasscodeAuthentication,
                getString(R.string.security_enter_passcode), getString(R.string.security_unlock_hint), input,
                { continuation.isActive }, { dialog.dismiss() })
            dialog = AlertDialog.Builder(this@presentPasscodeAuthentication)
                .apply { if (composition == null) {
                    setTitle(R.string.security_enter_passcode); setMessage(R.string.security_unlock_hint)
                } }
                .setView(composition?.view ?: input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.security_unlock_action, null)
                .create()
            dialog.setOnDismissListener {
                composition?.close()
                // Only a live presentation dismissed by the user cancels authentication.
                // The owner also dismisses this dialog by cancelling its coroutine after success
                // or retirement; that cleanup must not hide a later publication refusal.
                // User dismissal still revokes before a suspended verification child completes.
                if (continuation.isActive) cancel()
                input.text?.clear()
                if (continuation.isActive) continuation.resume(Unit)
            }
            continuation.invokeOnCancellation {
                runOnUiThread { composition?.close(); input.text?.clear(); dialog.dismiss() }
            }
            if (!continuation.isActive) return@suspendCancellableCoroutine
            dialog.show()
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!continuation.isActive || !dialog.isShowing) return@setOnClickListener
                val code = input.text.toString()
                input.text?.clear()
                input.error = null
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                composition?.update(true)
                launch {
                    // Success resumes the owner's exact request, which cancels this presentation.
                    // Wrong codes leave that same request visible for another attempt.
                    val accepted = verify(code)
                    if (!accepted && continuation.isActive) {
                        if (composition == null) input.error = getString(R.string.security_wrong_passcode)
                        else composition.update(false, getString(R.string.security_wrong_passcode))
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    }
                }
            }
            if (composition == null) input.requestFocus() else composition.show(dialog)
        }
    }
}
