package com.lavasec.app.reactnative

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import com.lavasec.app.R
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Localized native copy supplied by the app; no provider/session data enters JavaScript. */
internal data class NativeLinkedAccountCopy(
    val title: String,
    val signOut: String,
    val delete: String,
    val close: String,
    val confirmTitle: String,
    val confirmBody: String,
    val confirmDelete: String,
    val cancel: String,
    val failure: String,
    val providerName: (String) -> String,
)

internal fun Context.linkedAccountCopy() = NativeLinkedAccountCopy(
    title = getString(R.string.linked_account_title),
    signOut = getString(R.string.linked_account_sign_out),
    delete = getString(R.string.linked_account_delete),
    close = getString(R.string.linked_account_close),
    confirmTitle = getString(R.string.linked_account_confirm_title),
    confirmBody = getString(R.string.linked_account_confirm_body),
    confirmDelete = getString(R.string.linked_account_confirm_delete),
    cancel = getString(R.string.linked_account_cancel),
    failure = getString(R.string.linked_account_failure),
    providerName = { provider -> when (provider) {
        "apple" -> "Apple"
        "google" -> "Google"
        else -> getString(R.string.linked_account_provider, provider)
    } },
)

/** The linked-account leaf matches frozen iOS AccountSheet, not its Account & Backup page. */
internal suspend fun Activity.presentLinkedAccountSheet(
    visit: NativeLinkedAccountVisit,
    copy: NativeLinkedAccountCopy,
    authorityChanges: Flow<*>,
): NativeLinkedAccountOutcome = withContext(Dispatchers.Main.immediate) {
    if (isFinishing || isDestroyed || !visit.readable()) return@withContext NativeLinkedAccountOutcome.STALE
    try {
        withLinkedAccountHost(visit) {
            suspendCancellableCoroutine<NativeLinkedAccountOutcome> { continuation ->
                val connections = visit.connections()
                if (connections == null) {
                    continuation.resume(NativeLinkedAccountOutcome.STALE)
                    return@suspendCancellableCoroutine
                }
                var outcome = NativeLinkedAccountOutcome.CANCELLED
                var confirmation: AlertDialog? = null
                var pending = false
                var observation: Job? = null
                var operation: Job? = null
                val padding = (16 * resources.displayMetrics.density).toInt()
                val labels = mutableListOf<TextView>()
                val error = TextView(this@presentLinkedAccountSheet).apply { visibility = View.GONE; setTextColor(0xFFB00020.toInt()) }
                val signOut = Button(this@presentLinkedAccountSheet).apply { text = copy.signOut }
                val delete = Button(this@presentLinkedAccountSheet).apply { text = copy.delete }
                val column = LinearLayout(this@presentLinkedAccountSheet).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(padding, padding, padding, padding)
                    for (connection in connections) {
                        val label = TextView(this@presentLinkedAccountSheet).apply {
                            text = listOfNotNull(copy.providerName(connection.provider), connection.email).joinToString("\n")
                            setPadding(0, 0, 0, padding)
                            isSaveEnabled = false
                            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                        }
                        labels += label; addView(label)
                    }
                    addView(signOut); addView(delete); addView(error)
                }
                val dialog = AlertDialog.Builder(this@presentLinkedAccountSheet).setTitle(copy.title)
                    .setView(column).setNegativeButton(copy.close, null).create()
                fun clear() { labels.forEach { it.text = "" }; error.text = "" }
                fun retire(result: NativeLinkedAccountOutcome) {
                    outcome = result; confirmation?.dismiss(); confirmation = null; clear(); dialog.dismiss()
                }
                fun perform(action: NativeLinkedAccountAction, confirmed: Boolean = false) {
                    if (pending || !continuation.isActive) return
                    if (!visit.readable()) { retire(NativeLinkedAccountOutcome.STALE); return }
                    pending = true; signOut.isEnabled = false; delete.isEnabled = false; error.visibility = View.GONE
                    operation = launch {
                        val result = try { visit.perform(action, confirmed) }
                        catch (cancelled: CancellationException) {
                            if (continuation.isActive) retire(NativeLinkedAccountOutcome.CANCELLED)
                            throw cancelled
                        }
                        if (!continuation.isActive) return@launch
                        when (result) {
                            NativeLinkedAccountOutcome.COMPLETED, NativeLinkedAccountOutcome.STALE -> retire(result)
                            NativeLinkedAccountOutcome.FAILED, NativeLinkedAccountOutcome.REFUSED -> {
                                pending = false; error.text = copy.failure; error.visibility = View.VISIBLE
                                signOut.isEnabled = true; delete.isEnabled = true
                            }
                            NativeLinkedAccountOutcome.CANCELLED, NativeLinkedAccountOutcome.BUSY -> {
                                pending = false; signOut.isEnabled = true; delete.isEnabled = true
                            }
                        }
                    }
                }
                dialog.setOnDismissListener {
                    visit.cancel(); observation?.cancel(); operation?.cancel(); confirmation?.dismiss(); confirmation = null; clear()
                    if (continuation.isActive) continuation.resume(outcome)
                }
                continuation.invokeOnCancellation { runOnUiThread { visit.cancel(); clear(); confirmation?.dismiss(); dialog.dismiss() } }
                if (!continuation.isActive) return@suspendCancellableCoroutine
                dialog.show(); dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                observation = launch {
                    authorityChanges.collect {
                        if (!visit.readable()) {
                            if (visit.completingRevocation()) clear() else retire(NativeLinkedAccountOutcome.STALE)
                        }
                    }
                }
                signOut.setOnClickListener { perform(NativeLinkedAccountAction.SIGN_OUT) }
                delete.setOnClickListener {
                    if (pending || confirmation != null) return@setOnClickListener
                    if (!visit.readable()) { retire(NativeLinkedAccountOutcome.STALE); return@setOnClickListener }
                    val prompt = AlertDialog.Builder(this@presentLinkedAccountSheet).setTitle(copy.confirmTitle)
                        .setMessage(copy.confirmBody).setNegativeButton(copy.cancel, null)
                        .setPositiveButton(copy.confirmDelete) { _, _ ->
                            confirmation = null; perform(NativeLinkedAccountAction.DELETE, confirmed = true)
                        }.create()
                    confirmation = prompt
                    prompt.setOnDismissListener { if (confirmation === prompt) confirmation = null }
                    prompt.show(); prompt.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
    } finally { visit.cancel() }
}

private suspend fun Activity.withLinkedAccountHost(
    visit: NativeLinkedAccountVisit,
    present: suspend CoroutineScope.() -> NativeLinkedAccountOutcome,
): NativeLinkedAccountOutcome = coroutineScope {
    val presentationJob = coroutineContext.job
    val lifecycle = object : Application.ActivityLifecycleCallbacks {
        private fun retire(activity: Activity) {
            if (activity === this@withLinkedAccountHost) {
                visit.cancel()
                presentationJob.cancel(CancellationException("Linked account presentation host closed"))
            }
        }
        override fun onActivityPaused(activity: Activity) = retire(activity)
        override fun onActivityDestroyed(activity: Activity) = retire(activity)
        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    }
    application.registerActivityLifecycleCallbacks(lifecycle)
    try { present() } finally { application.unregisterActivityLifecycleCallbacks(lifecycle) }
}
