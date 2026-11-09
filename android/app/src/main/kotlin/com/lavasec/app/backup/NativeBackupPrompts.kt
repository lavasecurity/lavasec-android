package com.lavasec.app.backup

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.os.Bundle
import android.text.InputType
import android.text.InputFilter
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.lavasec.app.R
import com.lavasec.app.foundation.backup.BackupRecoveryMethod
import com.lavasec.app.foundation.backup.BackupRecoveryPhrase
import com.lavasec.app.foundation.backup.BackupSetupConsent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Minimal native presentation for the encrypted-backup setup/restore secrets.
 *
 * This is the "no secret crosses into JavaScript" boundary: the recovery phrase is generated,
 * displayed and typed back entirely inside the native dialog, and only the resulting secret
 * [CharArray] reaches the owner. Every field clears on dismissal/validation and the window carries
 * `FLAG_SECURE` so the phrase never enters a screenshot or the recents thumbnail.
 *
 * The copy is minimal and localized through the app string resources (`backup_*`), matching the
 * app's locale inventory, including the capability-driven method chooser and explicit passkey
 * consent. Provider admission does not replace the actual authenticator PRF ceremony.
 */

/** The collected setup secret plus explicit acknowledgments; the phrase is owned by the caller. */
internal data class BackupSetupPrompt(
    val phrase: CharArray,
    val consent: BackupSetupConsent,
)

/** Recovery-phrase generator seam; injectable so a test can pin a deterministic phrase. */
internal fun interface BackupRecoveryPhraseGenerator {
    fun generate(): String
}

/** Production generator over the ported pronounceable-word generator. */
internal object SecureBackupRecoveryPhraseGenerator : BackupRecoveryPhraseGenerator {
    override fun generate(): String = BackupRecoveryPhrase.generate()
}

/**
 * Presents the setup recovery phrase and collects explicit consent. Returns `null` when dismissed
 * without a completed acknowledgment; the returned phrase is already normalized.
 */
internal suspend fun Activity.presentBackupSetupPhrase(
    generator: BackupRecoveryPhraseGenerator = SecureBackupRecoveryPhraseGenerator,
    retireOnPause: Boolean = false,
): BackupSetupPrompt? = withContext(Dispatchers.Main.immediate) {
    if (isFinishing || isDestroyed) return@withContext null
    val phrase = generator.generate()
    val title = getString(R.string.backup_setup_title)
    val confirmHint = getString(R.string.backup_setup_confirm_hint)
    val savedAck = getString(R.string.backup_setup_saved_ack)
    val noRecoveryAck = getString(R.string.backup_setup_no_recovery_ack)
    val confirmBoth = getString(R.string.backup_setup_confirm_both)
    val mismatch = getString(R.string.backup_setup_phrase_mismatch)
    val saveLabel = getString(R.string.backup_save)
    withBackupPromptHost(retireOnPause) {
        suspendCancellableCoroutine<BackupSetupPrompt?> { continuation ->
            var result: BackupSetupPrompt? = null
            val density = resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            val phraseView = TextView(this@presentBackupSetupPhrase).apply {
                text = phrase
                textSize = 18f
                setPadding(0, dp(8), 0, dp(12))
            }
            val confirmField = EditText(this@presentBackupSetupPhrase).apply {
                hint = confirmHint
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                filters = arrayOf(InputFilter.LengthFilter(4096))
                isSaveEnabled = false
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            }
            val saved = CheckBox(this@presentBackupSetupPhrase).apply {
                text = savedAck
            }
            val noRecovery = CheckBox(this@presentBackupSetupPhrase).apply {
                text = noRecoveryAck
            }
            val error = TextView(this@presentBackupSetupPhrase).apply {
                setTextColor(0xFFB00020.toInt())
                visibility = View.GONE
            }
            val column = LinearLayout(this@presentBackupSetupPhrase).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(20), dp(20), dp(20))
                addView(phraseView)
                addView(confirmField)
                addView(saved)
                addView(noRecovery)
                addView(error)
            }
            val dialog = AlertDialog.Builder(this@presentBackupSetupPhrase)
                .setTitle(title)
                .setView(ScrollView(this@presentBackupSetupPhrase).apply { addView(column) })
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(saveLabel, null)
                .create()
            fun clearFields() {
                confirmField.text.clear()
                phraseView.text = ""
            }
            dialog.setOnDismissListener {
                clearFields()
                if (continuation.isActive) continuation.resume(result) { _, abandoned, _ ->
                    abandoned?.phrase?.fill('\u0000')
                }
            }
            continuation.invokeOnCancellation {
                runOnUiThread { result?.phrase?.fill('\u0000'); clearFields(); dialog.dismiss() }
            }
            if (!continuation.isActive) return@suspendCancellableCoroutine
            dialog.show()
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!saved.isChecked || !noRecovery.isChecked) {
                    error.text = confirmBoth
                    error.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                val typed = BackupRecoveryPhrase.phrase(BackupRecoveryPhrase.words(confirmField.text.toString()))
                if (typed.isBlank() || typed != phrase) {
                    error.text = mismatch
                    error.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                result = BackupSetupPrompt(
                    phrase = typed.toCharArray(),
                    consent = BackupSetupConsent(
                        savedRecoveryPhrase = true,
                        understandsNoRecovery = true,
                    ),
                )
                dialog.dismiss()
            }
        }
    }
}

/**
 * Collects the recovery phrase for a restore. Returns `null` on dismissal; on success the caller
 * owns the returned normalized secret and must clear it after use.
 */
internal suspend fun Activity.presentBackupRestorePhrase(retireOnPause: Boolean = false): CharArray? =
    withContext(Dispatchers.Main.immediate) {
        if (isFinishing || isDestroyed) return@withContext null
        val title = getString(R.string.backup_restore_title)
        val phraseHint = getString(R.string.backup_restore_hint)
        val restoreLabel = getString(R.string.backup_restore)
        withBackupPromptHost(retireOnPause) {
            suspendCancellableCoroutine<CharArray?> { continuation ->
                var result: CharArray? = null
                val input = EditText(this@presentBackupRestorePhrase).apply {
                    hint = phraseHint
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    filters = arrayOf(InputFilter.LengthFilter(4096))
                    isSaveEnabled = false
                    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                }
                val column = LinearLayout(this@presentBackupRestorePhrase).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(input)
                }
                val dialog = AlertDialog.Builder(this@presentBackupRestorePhrase)
                    .setTitle(title)
                    .setView(column)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(restoreLabel, null)
                    .create()
                fun clearFields() {
                    input.text.clear()
                }
                dialog.setOnDismissListener {
                    clearFields()
                    if (continuation.isActive) continuation.resume(result) { _, abandoned, _ ->
                        abandoned?.fill('\u0000')
                    }
                }
                continuation.invokeOnCancellation {
                    runOnUiThread { result?.fill('\u0000'); clearFields(); dialog.dismiss() }
                }
                if (!continuation.isActive) return@suspendCancellableCoroutine
                dialog.show()
                dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val normalized = BackupRecoveryPhrase.phrase(
                        BackupRecoveryPhrase.words(input.text.toString()),
                    )
                    if (normalized.isBlank()) return@setOnClickListener
                    result = normalized.toCharArray()
                    dialog.dismiss()
                }
            }
        }
    }

/**
 * The native unlock-method chooser. Returns the selected method, or `null` when the user dismissed
 * it. The caller has already checked local device-key custody and passkey provider admission;
 * the selected method must still complete its actual unlock ceremony.
 */
internal suspend fun Activity.presentBackupRestoreMethod(
    deviceKeyAvailable: Boolean,
    passkeyAvailable: Boolean,
): BackupRecoveryMethod? =
    withContext(Dispatchers.Main.immediate) {
        if (isFinishing || isDestroyed) return@withContext null
        var result: BackupRecoveryMethod? = null
        val methods = buildList {
            if (deviceKeyAvailable) add(BackupRecoveryMethod.DEVICE_KEY)
            add(BackupRecoveryMethod.RECOVERY_CODE)
            if (passkeyAvailable) add(BackupRecoveryMethod.PASSKEY)
        }
        val labels = methods.map { method -> getString(when (method) {
            BackupRecoveryMethod.DEVICE_KEY -> R.string.backup_restore_method_device
            BackupRecoveryMethod.RECOVERY_CODE -> R.string.backup_restore_method_recovery
            BackupRecoveryMethod.PASSKEY -> R.string.backup_restore_method_passkey
        }) }.toTypedArray()
        withBackupPromptHost {
            suspendCancellableCoroutine<Unit> { continuation ->
                val dialog = AlertDialog.Builder(this@presentBackupRestoreMethod)
                    .setTitle(R.string.backup_restore_method_title)
                    .setItems(labels) { _, which ->
                        result = methods[which]
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                    .setOnCancelListener { if (continuation.isActive) continuation.resume(Unit) }
                    .create()
                dialog.setOnDismissListener { if (continuation.isActive) continuation.resume(Unit) }
                continuation.invokeOnCancellation { runOnUiThread { dialog.dismiss() } }
                if (!continuation.isActive) return@suspendCancellableCoroutine
                dialog.show()
                dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        result
    }

/**
 * Explicit passkey consent: true opts in, false explicitly continues without a passkey, and null
 * cancels setup. The ceremony remains the authority on whether an authenticator PRF is produced.
 */
internal suspend fun Activity.presentBackupPasskeyConsent(): Boolean? =
    withContext(Dispatchers.Main.immediate) {
        if (isFinishing || isDestroyed) return@withContext null
        withBackupPromptHost {
            suspendCancellableCoroutine<Boolean?> { continuation ->
                val dialog = AlertDialog.Builder(this@presentBackupPasskeyConsent)
                    .setTitle(R.string.backup_passkey_consent_title)
                    .setMessage(R.string.backup_passkey_consent_message)
                    .setNegativeButton(R.string.backup_passkey_consent_skip) { _, _ ->
                        if (continuation.isActive) continuation.resume(false)
                    }
                    .setPositiveButton(R.string.backup_passkey_consent_confirm) { _, _ ->
                        if (continuation.isActive) continuation.resume(true)
                    }
                    .setNeutralButton(android.R.string.cancel) { _, _ ->
                        if (continuation.isActive) continuation.resume(null)
                    }
                    .setOnCancelListener { if (continuation.isActive) continuation.resume(null) }
                    .create()
                dialog.setOnDismissListener { if (continuation.isActive) continuation.resume(null) }
                continuation.invokeOnCancellation { runOnUiThread { dialog.dismiss() } }
                if (!continuation.isActive) return@suspendCancellableCoroutine
                dialog.show()
                dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

/**
 * The explicit "apply this reviewed restore" confirmation. [summary] is built by the bridge from
 * the prepared plan (change counts only). Returns `true` only on an explicit confirm.
 */
internal suspend fun Activity.presentBackupRestoreReview(
    summary: String,
    requiresResolverConfirmation: Boolean,
    retireOnPause: Boolean = false,
): Boolean = withContext(Dispatchers.Main.immediate) {
    if (isFinishing || isDestroyed) return@withContext false
    val title = getString(R.string.backup_restore_review_title)
    val resolverLabel = getString(R.string.backup_restore_resolver_confirm)
    val restoreLabel = getString(R.string.backup_restore)
    val confirmResolver = getString(R.string.backup_restore_confirm_resolver)
    withBackupPromptHost(retireOnPause) {
        suspendCancellableCoroutine<Boolean> { continuation ->
            var accepted = false
            val body = TextView(this@presentBackupRestoreReview).apply { text = summary }
            val error = TextView(this@presentBackupRestoreReview).apply {
                text = confirmResolver
                visibility = View.GONE
            }
            val column = LinearLayout(this@presentBackupRestoreReview).apply {
                orientation = LinearLayout.VERTICAL
                addView(body)
            }
            val resolver = if (requiresResolverConfirmation) {
                CheckBox(this@presentBackupRestoreReview).apply {
                    text = resolverLabel
                }.also { column.addView(it) }
            } else {
                null
            }
            column.addView(error)
            val dialog = AlertDialog.Builder(this@presentBackupRestoreReview)
                .setTitle(title)
                .setView(ScrollView(this@presentBackupRestoreReview).apply { addView(column) })
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(restoreLabel, null)
                .create()
            dialog.setOnDismissListener {
                body.text = ""
                if (continuation.isActive) continuation.resume(accepted)
            }
            continuation.invokeOnCancellation { runOnUiThread { dialog.dismiss() } }
            if (!continuation.isActive) return@suspendCancellableCoroutine
            dialog.show()
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (resolver != null && !resolver.isChecked) {
                    error.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                accepted = true
                dialog.dismiss()
            }
        }
    }
}

internal suspend fun <T> Activity.withBackupPromptHost(
    retireOnPause: Boolean = false,
    present: suspend CoroutineScope.() -> T,
): T = coroutineScope {
    // Commands belong to the retained React module. Android removes a raw Dialog window on
    // Activity destruction without promising onDismiss; retire this request, not that module.
    val presentationJob = coroutineContext.job
    val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityDestroyed(activity: Activity) {
            if (activity === this@withBackupPromptHost) {
                presentationJob.cancel(CancellationException("Backup presentation host closed"))
            }
        }
        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) {
            if (retireOnPause && activity === this@withBackupPromptHost) {
                presentationJob.cancel(CancellationException("Backup presentation left foreground"))
            }
        }
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    }
    application.registerActivityLifecycleCallbacks(lifecycle)
    try {
        if (isFinishing || isDestroyed) throw CancellationException("Backup presentation host closed")
        present()
    } finally {
        application.unregisterActivityLifecycleCallbacks(lifecycle)
    }
}
