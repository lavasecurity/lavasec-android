package com.lavasec.app.security

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.lavasec.app.presentation.R
import com.lavasec.app.reactnative.NativePasscodeComposition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Android presentation for the canonical `native.flow passcode` management.
 *
 * The [SecurityOwner] stays the only credential authority: this dialog collects the passcode and
 * hands it to the owner, which owns verification, persistence and publication. No passcode ever
 * crosses into JavaScript. When no credential exists the dialog sets one; when a credential exists
 * it changes or removes it. A wrong current passcode keeps the dialog open with the inline error,
 * matching the native Security screen's own dialogs.
 *
 * Returns `true` only when an owner mutation completed; a dismissal or a refused mutation resolves
 * `false`.
 */
internal suspend fun Activity.presentPasscodeManagement(
    enabled: Boolean,
    setPasscode: suspend (String) -> SecurityCredentialMutationResult,
    changePasscode: suspend (String, String) -> SecurityCredentialMutationResult,
    removePasscode: suspend (String) -> SecurityCredentialMutationResult,
): Boolean = withContext(Dispatchers.Main.immediate) {
    if (isFinishing || isDestroyed) return@withContext false
    withPasscodeManagementHost {
        suspendCancellableCoroutine<Boolean> { continuation ->
            var accepted = false
            val admitted = { continuation.isActive && !isFinishing && !isDestroyed &&
                (this@presentPasscodeManagement as? com.lavasec.app.reactnative.LavaReactActivity)?.foreground != false }
            val currentField = passcodeField(R.string.security_enter_passcode, admitted)
            val newField = passcodeField(R.string.security_new_passcode, admitted)
            val confirmField = passcodeField(R.string.security_confirm_passcode, admitted)
            val error = TextView(this@presentPasscodeManagement).apply {
                setTextColor(0xFFB00020.toInt())
                visibility = View.GONE
            }
            val density = resources.displayMetrics.density
            val padding = (16 * density).toInt()
            val column = LinearLayout(this@presentPasscodeManagement).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding, padding, padding)
                if (enabled) addView(currentField)
                addView(newField)
                addView(confirmField)
                addView(error)
            }
            val titleRes = if (enabled) R.string.security_change_passcode else R.string.security_set_passcode
            lateinit var dialog: AlertDialog
            val composition = NativePasscodeComposition.create(this@presentPasscodeManagement,
                getString(titleRes), "", column, { continuation.isActive }, { dialog.dismiss() })
            dialog = AlertDialog.Builder(this@presentPasscodeManagement)
                .apply { if (composition == null) setTitle(titleRes) }
                .setView(composition?.view ?: column)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(titleRes, null)
                .apply { if (enabled) setNeutralButton(R.string.security_remove_passcode, null) }
                .create()
            fun clearFields() {
                currentField.text.clear()
                newField.text.clear()
                confirmField.text.clear()
            }
            fun showError(messageRes: Int) {
                error.text = getString(messageRes)
                error.visibility = if (composition == null) View.VISIBLE else View.GONE
                composition?.update(false, getString(messageRes))
            }
            fun showRefusal(result: SecurityCredentialMutationResult) {
                showError(when (result) {
                    SecurityCredentialMutationResult.WRONG_PASSCODE -> R.string.security_wrong_passcode
                    SecurityCredentialMutationResult.SECURE_STORAGE -> R.string.security_unavailable_title
                    SecurityCredentialMutationResult.REFUSED -> R.string.security_operation_failed
                    SecurityCredentialMutationResult.COMPLETED -> throw IllegalStateException("Completed credential mutation cannot be refused")
                })
            }
            dialog.setOnDismissListener {
                composition?.close()
                clearFields()
                if (continuation.isActive) continuation.resume(accepted)
            }
            continuation.invokeOnCancellation { runOnUiThread { composition?.close(); clearFields(); dialog.dismiss() } }
            if (!continuation.isActive) return@suspendCancellableCoroutine
            dialog.show()
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!continuation.isActive || !dialog.isShowing) return@setOnClickListener
                val next = newField.text.toString()
                val confirmation = confirmField.text.toString()
                val current = currentField.text.toString()
                error.visibility = View.GONE
                if (next.isBlank() || next != confirmation) {
                    showError(R.string.security_passcode_mismatch)
                    return@setOnClickListener
                }
                if (enabled && current.isBlank()) {
                    showError(R.string.security_wrong_passcode)
                    return@setOnClickListener
                }
                clearFields()
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                composition?.update(true)
                launch {
                    val applied = try {
                        if (enabled) changePasscode(current, next) else setPasscode(next)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        SecurityCredentialMutationResult.SECURE_STORAGE
                    }
                    if (!continuation.isActive) return@launch
                    if (applied == SecurityCredentialMutationResult.COMPLETED) {
                        accepted = true
                        dialog.dismiss()
                    } else {
                        showRefusal(applied)
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    }
                }
            }
            if (enabled) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    if (!continuation.isActive || !dialog.isShowing) return@setOnClickListener
                    val current = currentField.text.toString()
                    error.visibility = View.GONE
                    if (current.isBlank()) {
                        showError(R.string.security_wrong_passcode)
                        return@setOnClickListener
                    }
                    clearFields()
                    composition?.update(true)
                    launch {
                        val removed = try {
                            removePasscode(current)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            SecurityCredentialMutationResult.SECURE_STORAGE
                        }
                        if (!continuation.isActive) return@launch
                        if (removed == SecurityCredentialMutationResult.COMPLETED) {
                            accepted = true
                            dialog.dismiss()
                        } else {
                            showRefusal(removed)
                        }
                    }
                }
            }
            composition?.show(dialog)
        }
    }
}

private suspend fun Activity.withPasscodeManagementHost(
    present: suspend CoroutineScope.() -> Boolean,
): Boolean = coroutineScope {
    // The bridge command belongs to the retained React module, not the Activity scope.
    // Android removes a raw Dialog's window on host destruction without calling onDismiss.
    // Retire only this presentation (and its mutation children), leaving that module alive.
    val presentationJob = coroutineContext.job
    val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityDestroyed(activity: Activity) {
            if (activity === this@withPasscodeManagementHost) {
                presentationJob.cancel(CancellationException("Passcode presentation host closed"))
            }
        }
        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) {
            if (activity === this@withPasscodeManagementHost) {
                presentationJob.cancel(CancellationException("Passcode presentation host closed"))
            }
        }
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    }
    application.registerActivityLifecycleCallbacks(lifecycle)
    try {
        present()
    } finally {
        application.unregisterActivityLifecycleCallbacks(lifecycle)
    }
}

private fun Activity.passcodeField(labelRes: Int, admitted: () -> Boolean): EditText = NativePasscodeField(this, admitted).apply {
    // setSingleLine replaces the transformation; install password input mode afterwards.
    setSingleLine()
    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    hint = getString(labelRes)
    isSaveEnabled = false
    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    imeOptions = imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
}
