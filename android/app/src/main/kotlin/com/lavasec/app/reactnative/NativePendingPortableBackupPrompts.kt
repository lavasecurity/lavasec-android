package com.lavasec.app.reactnative

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.lavasec.app.R
import com.lavasec.app.backup.withBackupPromptHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

internal enum class PortableBackupReviewAction { RESTORE_NOW, SAVE_FOR_LATER }

/** Saving ciphertext needs no resolver acknowledgment, source download or entitlement. */
internal suspend fun Activity.presentPortableBackupRestoreReview(
    summary: String,
    requiresResolverConfirmation: Boolean,
): PortableBackupReviewAction? = withContext(Dispatchers.Main.immediate) {
    withBackupPromptHost(retireOnPause = true) {
        suspendCancellableCoroutine { continuation ->
            var result: PortableBackupReviewAction? = null
            val body = TextView(this@presentPortableBackupRestoreReview).apply { text = summary }
            val error = TextView(this@presentPortableBackupRestoreReview).apply {
                setText(R.string.backup_restore_confirm_resolver)
                visibility = View.GONE
            }
            val column = LinearLayout(this@presentPortableBackupRestoreReview).apply {
                orientation = LinearLayout.VERTICAL
                val padding = (20 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, padding)
                addView(body)
            }
            val resolver = if (requiresResolverConfirmation) {
                CheckBox(this@presentPortableBackupRestoreReview).apply {
                    setText(R.string.backup_restore_resolver_confirm)
                }.also(column::addView)
            } else null
            column.addView(error)
            val dialog = AlertDialog.Builder(this@presentPortableBackupRestoreReview)
                .setTitle(R.string.backup_restore_review_title)
                .setView(ScrollView(this@presentPortableBackupRestoreReview).apply { addView(column) })
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.portable_backup_save_for_later) { _, _ ->
                    result = PortableBackupReviewAction.SAVE_FOR_LATER
                }
                .setPositiveButton(R.string.portable_backup_restore_now, null)
                .create()
            dialog.setOnDismissListener {
                body.text = ""
                if (continuation.isActive) continuation.resume(result)
            }
            continuation.invokeOnCancellation { runOnUiThread { body.text = ""; dialog.dismiss() } }
            if (!continuation.isActive) return@suspendCancellableCoroutine
            dialog.show()
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (resolver != null && !resolver.isChecked) {
                    error.visibility = View.VISIBLE
                } else {
                    result = PortableBackupReviewAction.RESTORE_NOW
                    dialog.dismiss()
                }
            }
        }
    }
}

internal suspend fun Activity.confirmPortableBackupAction(title: Int, message: Int): Boolean =
    withContext(Dispatchers.Main.immediate) {
        withBackupPromptHost(retireOnPause = true) {
            suspendCancellableCoroutine { continuation ->
                var accepted = false
                val dialog = AlertDialog.Builder(this@confirmPortableBackupAction)
                    .setTitle(title).setMessage(message)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ -> accepted = true }.create()
                dialog.setOnDismissListener { if (continuation.isActive) continuation.resume(accepted) }
                continuation.invokeOnCancellation { runOnUiThread { dialog.dismiss() } }
                if (continuation.isActive) {
                    dialog.show()
                    dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
    }
