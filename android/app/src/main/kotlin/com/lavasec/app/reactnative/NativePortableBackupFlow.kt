package com.lavasec.app.reactnative

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.lavasec.app.R
import com.lavasec.app.backup.presentBackupRestorePhrase
import com.lavasec.app.backup.presentBackupSetupPhrase
import com.lavasec.app.backup.withBackupPromptHost
import com.lavasec.app.foundation.backup.BackupConfigurationPayload
import com.lavasec.app.foundation.backup.BackupPasswordPolicy
import com.lavasec.app.foundation.backup.BackupRecoveryMethod
import com.lavasec.app.foundation.backup.BackupRestoreApplyOutcome
import com.lavasec.app.foundation.backup.BackupRestoreBasis
import com.lavasec.app.foundation.backup.BackupRestoreOwner
import com.lavasec.app.foundation.backup.BackupRestorePreparation
import com.lavasec.app.foundation.backup.BackupRetainedPreferences
import com.lavasec.app.foundation.backup.PortableBackupFile
import com.lavasec.app.foundation.backup.PortableBackupVersionException
import com.lavasec.app.foundation.backup.PortableBackupSizeException
import com.lavasec.app.security.SecurityAuthorization
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** A snapshot of the real local authorities; no account session or device key is required. */
internal data class PortableBackupSnapshot(
    val payload: BackupConfigurationPayload,
    val library: FilterLibraryDraft,
    val preferences: BackupRetainedPreferences,
    val basis: BackupRestoreBasis,
)

/** Bridge supplies the existing generation-fenced restore publisher, with no cloud custody. */
internal interface PortableBackupSource {
    val restoreOwner: BackupRestoreOwner
    suspend fun capture(): PortableBackupSnapshot?
    /** Reauthorize app settings and filters at the final export/restore edge. */
    suspend fun authorize(): SecurityAuthorization
    /** Synchronous native mutation fence, rechecked immediately before the publisher is driven. */
    fun publicationStillAuthorized(): Boolean
}

internal data class PortableBackupResult(val status: String, val reason: String? = null, val applied: Boolean = false)

/**
 * Offline SAF export, authenticated review and ciphertext retention. Applying a restore uses the
 * normal source acquisition and edition admission authorities. Secrets and the before/after review stay in native secure
 * windows; only the non-secret completion reason returns to React. Decryption and KDFs run on IO.
 */
internal class NativePortableBackupFlow(
    private val activity: LavaReactActivity,
    private val source: PortableBackupSource,
    private val pending: AndroidPendingPortableBackupStore,
) {
    suspend fun export(): PortableBackupResult { return try {
        authorizationRefusal()?.let { return it }
        if (!prompt { activity.presentPortableBackupExportConsent() }) return cancelled()
        val kind = chooseKind(listOf("password", "recoveryPhrase"), export = true) ?: return cancelled()
        val secret = if (kind == "recoveryPhrase") prompt { activity.presentBackupSetupPhrase(retireOnPause = true)?.phrase }
            else prompt { activity.presentPortableBackupPassword(export = true) }
        if (secret == null) return cancelled()
        val bytes = try {
            authorizationRefusal()?.let { return it }
            val snapshot = source.capture() ?: return rejected("backup-export-unavailable")
            withContext(Dispatchers.IO) {
                PortableBackupFile.seal(snapshot.payload, kind, secret, Instant.now().epochSecond)
            }
        } finally { secret.fill('\u0000') }
        try {
            val uri = activity.presentDocument(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = PortableBackupFile.MIME_TYPE
                putExtra(Intent.EXTRA_TITLE, "Lava-Android-${java.time.LocalDate.now()}.lava-backup")
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }) ?: return cancelled()
            if (!activity.awaitForeground()) return rejected("unauthorized")
            authorizationRefusal()?.let { return it }
            withContext(Dispatchers.IO) {
                activity.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    output.write(bytes)
                    output.flush()
                } ?: throw IllegalStateException("Document unavailable")
            }
            PortableBackupResult("completed")
        } finally { bytes.fill(0) }
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: PortableBackupSizeException) { rejected("backup-export-too-large") }
      catch (_: Exception) { rejected("backup-export-failed") }
    }

    suspend fun restore(): PortableBackupResult = runImport {
        authorizationRefusal()?.let { return@runImport it }
        val uri = activity.presentDocument(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }) ?: return@runImport cancelled()
        if (!activity.awaitForeground()) return@runImport rejected("unauthorized")
        authorizationRefusal()?.let { return@runImport it }
        val bytes = withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            activity.contentResolver.openInputStream(uri)?.use { input -> readPortableBackupBytes(input) { context.ensureActive() } }
                ?: throw IllegalStateException("Document unavailable")
        }
        try { restoreBytes(bytes, pendingToken = null) } finally { bytes.fill(0) }
    }

    suspend fun resume(): PortableBackupResult = runImport {
        authorizationRefusal()?.let { return@runImport it }
        val captured = withContext(Dispatchers.IO) { pending.capture() }
            ?: return@runImport rejected("backup-pending-missing")
        val bytes = captured.bytes ?: return@runImport rejected("backup-pending-unreadable")
        try { restoreBytes(bytes, pendingToken = captured.token) } finally { bytes.fill(0) }
    }

    suspend fun discard(): PortableBackupResult = runImport {
        authorizationRefusal()?.let { return@runImport it }
        val captured = withContext(Dispatchers.IO) { pending.capture() }
            ?: return@runImport rejected("backup-pending-missing")
        captured.bytes?.fill(0)
        if (!prompt { activity.confirmPortableBackupAction(R.string.portable_backup_pending_discard_title,
                R.string.portable_backup_pending_discard_message) }) return@runImport cancelled()
        authorizationRefusal()?.let { return@runImport it }
        val cleared = withPortableBackupCommitReceipt { callerActive ->
            source.publicationStillAuthorized() && pending.clearIfMatches(captured.token) {
                callerActive() && source.publicationStillAuthorized()
            }
        }
        if (cleared) PortableBackupResult("completed") else rejected("backup-pending-discard-failed")
    }

    private suspend fun runImport(action: suspend () -> PortableBackupResult): PortableBackupResult = try {
        action()
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: PendingPortableBackupSaveUnverifiedException) { rejected("backup-pending-save-unverified") }
      catch (_: PortableBackupVersionException) { rejected("backup-restore-unsupported-schema") }
      catch (_: Exception) { rejected("backup-restore-corrupt-payload") }

    private suspend fun restoreBytes(bytes: ByteArray, pendingToken: String?): PortableBackupResult {
        val envelope = withContext(Dispatchers.IO) { PortableBackupFile.decode(bytes) }
        authorizationRefusal()?.let { return it }
        val kind = chooseKind(envelope.keySlots.map { it.kind }, export = false) ?: return cancelled()
        val secret = if (kind == "recoveryPhrase") prompt { activity.presentBackupRestorePhrase(retireOnPause = true) }
            else prompt { activity.presentPortableBackupPassword(export = false) }
        if (secret == null) return cancelled()
        val unlock = try { withContext(Dispatchers.IO) { PortableBackupFile.unlock(envelope, kind, secret) } }
            finally { secret.fill('\u0000') }
        authorizationRefusal()?.let { return it }
        val before = source.capture() ?: return rejected("backup-restore-unavailable")
        val preparation = withContext(Dispatchers.IO) {
            source.restoreOwner.prepare(unlock, BackupRecoveryMethod.RECOVERY_CODE,
                before.library, before.preferences, before.basis)
        }
        when (preparation) {
            is BackupRestorePreparation.Refused -> return rejected(refusal(preparation.refusal))
            is BackupRestorePreparation.Prepared -> try {
                authorizationRefusal()?.let { return it }
                val sections = NativeBackupRestoreReview.project(preparation.plan)
                val summary = buildString {
                    append(activity.getString(R.string.portable_backup_restore_summary)).append("\n\n")
                    for (index in 0 until sections.length()) {
                        val rows = sections.getJSONObject(index).getJSONArray("rows")
                        for (rowIndex in 0 until rows.length()) {
                            val row = rows.getJSONObject(rowIndex)
                            if (row.getString("before") != row.getString("after")) {
                                append(row.getString("title")).append("\n")
                                append(row.getString("before")).append(" → ").append(row.getString("after")).append("\n\n")
                            }
                        }
                    }
                }
                val action = prompt { activity.presentPortableBackupRestoreReview(summary, preparation.plan.requiresResolverConfirmation) }
                    ?: return cancelled()
                if (action == PortableBackupReviewAction.SAVE_FOR_LATER) {
                    val preceding = if (pendingToken == null) withContext(Dispatchers.IO) { pending.capture() } else null
                    val precedingToken = preceding?.token
                    preceding?.bytes?.fill(0)
                    if (preceding != null &&
                        !prompt { activity.confirmPortableBackupAction(R.string.portable_backup_pending_replace_title,
                            R.string.portable_backup_pending_replace_message) }) return cancelled()
                    authorizationRefusal()?.let { return it }
                    val saved = withPortableBackupCommitReceipt { callerActive ->
                        if (!callerActive() || !source.publicationStillAuthorized()) return@withPortableBackupCommitReceipt false
                        if (pendingToken == null) pending.saveIfUnchanged(bytes, precedingToken) {
                            callerActive() && source.publicationStillAuthorized()
                        } else {
                            val retained = pending.capture()
                            try { retained?.token == pendingToken && callerActive() && source.publicationStillAuthorized() }
                            finally { retained?.bytes?.fill(0) }
                        }
                    }
                    if (!saved) return rejected("backup-pending-save-failed")
                    return PortableBackupResult("completed", "backup-import-pending")
                }
                authorizationRefusal()?.let { return it }
                val current = source.capture() ?: return rejected("backup-restore-unavailable")
                val receipt = withPortableBackupCommitReceipt { callerActive ->
                    if (!callerActive() || !source.publicationStillAuthorized()) return@withPortableBackupCommitReceipt null
                    val outcome = source.restoreOwner.confirm(preparation.reviewID, resolverChangeConfirmed = true,
                        currentBasis = current.basis, currentLibrary = current.library)
                    // Cleanup follows durable success even if the initiating host retired just
                    // after commit. Its exact-record CAS cannot erase a replacement import.
                    val cleared = outcome !is BackupRestoreApplyOutcome.Applied || pendingToken == null ||
                        runCatching { pending.clearIfMatches(pendingToken) }.getOrDefault(false)
                    outcome to cleared
                } ?: return rejected("unauthorized")
                return when (val outcome = receipt.first) {
                    is BackupRestoreApplyOutcome.Applied -> {
                        PortableBackupResult("completed", if (receipt.second) null else "backup-pending-clear-failed", applied = true)
                    }
                    is BackupRestoreApplyOutcome.Refused -> rejected(refusal(outcome.refusal))
                }
            } finally { source.restoreOwner.discard(preparation.reviewID) }
        }
    }

    private suspend fun chooseKind(kinds: List<String>, export: Boolean): String? = prompt {
        withContext(Dispatchers.Main.immediate) {
            activity.withBackupPromptHost(retireOnPause = true) {
                suspendCancellableCoroutine { continuation ->
                    val labels = kinds.map { activity.getString(if (it == "password")
                        R.string.portable_backup_password else R.string.backup_restore_method_recovery) }.toTypedArray()
                    var result: String? = null
                    val dialog = AlertDialog.Builder(activity)
                        .setTitle(if (export) R.string.portable_backup_export_title else R.string.portable_backup_import_title)
                        .setSingleChoiceItems(labels, -1) { dialog, position -> result = kinds[position]; dialog.dismiss() }
                        .setNegativeButton(android.R.string.cancel, null).create()
                    dialog.setOnDismissListener { if (continuation.isActive) continuation.resume(result) }
                    continuation.invokeOnCancellation { activity.runOnUiThread { dialog.dismiss() } }
                    if (continuation.isActive) { dialog.show(); dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
                }
            }
        }

    }

    private suspend fun <T> prompt(present: suspend () -> T): T = withPortableBackupPromptAuthority(
        isCurrent = source::publicationStillAuthorized,
        observeAuthority = activity::observeNativeFieldAuthority,
        present = present,
    )

    private suspend fun authorizationRefusal(): PortableBackupResult? = when (source.authorize()) {
        SecurityAuthorization.AUTHORIZED -> null
        SecurityAuthorization.CANCELLED -> cancelled()
        SecurityAuthorization.DENIED -> rejected("unauthorized")
    }

    private fun cancelled() = PortableBackupResult("cancelled", "dismissed")
    private fun rejected(reason: String) = PortableBackupResult("rejected", reason)
    private fun refusal(value: com.lavasec.app.foundation.backup.BackupRestoreRefusal): String = when (value) {
        com.lavasec.app.foundation.backup.BackupRestoreRefusal.WrongSecret -> "backup-restore-wrong-secret"
        com.lavasec.app.foundation.backup.BackupRestoreRefusal.CorruptPayload -> "backup-restore-corrupt-payload"
        is com.lavasec.app.foundation.backup.BackupRestoreRefusal.UnsupportedSchema -> "backup-restore-unsupported-schema"
        com.lavasec.app.foundation.backup.BackupRestoreRefusal.RollbackFailed -> "backup-restore-rollback-failed"
        com.lavasec.app.foundation.backup.BackupRestoreRefusal.PublishInterrupted -> "backup-restore-publish-interrupted"
        com.lavasec.app.foundation.backup.BackupRestoreRefusal.ResolverConfirmationRequired -> "backup-restore-resolver-confirmation-required"
        com.lavasec.app.foundation.backup.BackupRestoreRefusal.StaleReview -> "backup-restore-stale-review"
        else -> "backup-restore-unavailable"
    }
}

/**
 * A durable native commit has an immutable receipt. Cancellation before the queued IO begins
 * still refuses the write; the supplied caller witness also reaches final pending-file writes.
 * Once committed, dispatch back to the host must not discard that fact through prompt cancellation.
 */
internal suspend fun <T> withPortableBackupCommitReceipt(
    writeDispatcher: CoroutineDispatcher = Dispatchers.IO,
    commit: (callerActive: () -> Boolean) -> T,
): T {
    val caller = currentCoroutineContext()
    caller.ensureActive()
    return withContext(NonCancellable) {
        withContext(writeDispatcher) {
            caller.ensureActive()
            commit { caller.isActive }
        }
    }
}

internal suspend fun Activity.presentPortableBackupPassword(export: Boolean): CharArray? = withContext(Dispatchers.Main.immediate) {
    withBackupPromptHost(retireOnPause = true) {
        suspendCancellableCoroutine { continuation ->
            var result: CharArray? = null
            fun field(label: Int) = EditText(this@presentPortableBackupPassword).apply {
                setHint(label)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                filters = arrayOf(InputFilter.LengthFilter(4096))
                isSaveEnabled = false
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            }
            val input = field(R.string.portable_backup_password)
            val confirmation = if (export) field(R.string.portable_backup_password_confirm) else null
            val message = TextView(this@presentPortableBackupPassword).apply { setText(if (export) R.string.portable_backup_export_summary else R.string.portable_backup_import_password) }
            val error = TextView(this@presentPortableBackupPassword).apply { visibility = View.GONE; setText(if (export) R.string.portable_backup_password_invalid else R.string.portable_backup_import_password_required) }
            val column = LinearLayout(this@presentPortableBackupPassword).apply {
                orientation = LinearLayout.VERTICAL
                val padding = (20 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, padding)
                addView(message); addView(input); confirmation?.let(::addView); addView(error)
            }
            val dialog = AlertDialog.Builder(this@presentPortableBackupPassword)
                .setTitle(if (export) R.string.portable_backup_export_title else R.string.portable_backup_import_title)
                .setView(ScrollView(this@presentPortableBackupPassword).apply { addView(column) })
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(if (export) R.string.backup_save else R.string.backup_restore, null).create()
            fun clear() { input.text.clear(); confirmation?.text?.clear() }
            dialog.setOnDismissListener {
                clear()
                if (continuation.isActive) continuation.resume(result) { _, abandoned, _ -> abandoned?.fill('\u0000') }
            }
            continuation.invokeOnCancellation { runOnUiThread { result?.fill('\u0000'); clear(); dialog.dismiss() } }
            if (!continuation.isActive) return@suspendCancellableCoroutine
            dialog.show()
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val valid = if (export) BackupPasswordPolicy.validate(input.text.toString(), confirmation!!.text.toString()).isValid
                    else input.text.isNotEmpty()
                if (!valid) { error.visibility = View.VISIBLE; return@setOnClickListener }
                result = CharArray(input.text.length) { input.text[it] }
                dialog.dismiss()
            }
        }
    }
}


internal suspend fun Activity.presentPortableBackupExportConsent(): Boolean = withContext(Dispatchers.Main.immediate) {
    withBackupPromptHost(retireOnPause = true) {
        suspendCancellableCoroutine { continuation ->
            var accepted = false
            val dialog = AlertDialog.Builder(this@presentPortableBackupExportConsent)
                .setTitle(R.string.portable_backup_export_title)
                .setMessage(R.string.portable_backup_export_summary)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.backup_save) { _, _ -> accepted = true }.create()
            dialog.setOnDismissListener { if (continuation.isActive) continuation.resume(accepted) }
            continuation.invokeOnCancellation { runOnUiThread { dialog.dismiss() } }
            if (continuation.isActive) { dialog.show(); dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
    }
}

/** The native prompt cannot outlive its exact host/security grant, even while still foreground. */
internal suspend fun <T> withPortableBackupPromptAuthority(
    isCurrent: () -> Boolean,
    observeAuthority: (() -> Unit) -> (() -> Unit),
    present: suspend () -> T,
): T = coroutineScope {
    val presentationJob = coroutineContext.job
    val retireIfStale = {
        if (!isCurrent()) presentationJob.cancel(CancellationException("Portable backup authority retired"))
    }
    val stopObserving = observeAuthority(retireIfStale)
    try {
        retireIfStale()
        currentCoroutineContext().ensureActive()
        present()
    } finally { stopObserving() }
}
