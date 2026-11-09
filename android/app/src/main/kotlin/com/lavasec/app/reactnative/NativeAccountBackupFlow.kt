package com.lavasec.app.reactnative

import com.lavasec.app.foundation.backup.BackupRecoveryPhrase
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject

/**
 * A presentation visit, never an account or encryption authority. The existing owners do all
 * provider, sealing, upload and restore work. Secrets are borrowed only by native credential views
 * and native operations; projection contains presentation values and explicit acknowledgments.
 */
internal class NativeAccountBackupFlow(
    val kind: String,
    val host: NativeVisitHost,
    val generation: Long,
    private val currentHost: () -> NativeVisitHost?,
    private val currentGeneration: () -> Long?,
    private val accountCurrent: () -> Boolean,
    private val canRead: () -> Boolean,
    private val canInteract: () -> Boolean,
    private val changed: () -> Unit,
    private val authorizationRevision: () -> Long = { 0L },
) : AutoCloseable {
    val id = UUID.randomUUID().toString()
    private val actions = Channel<JSONObject>(1)
    private val closed = CompletableDeferred<Unit>()
    private val listeners = linkedSetOf<() -> Unit>()
    private var retired = false
    private var userDismissed = false
    private var pendingAction: String? = null
    private var expectedAction: Set<String> = emptySet()
    private var claimedAuthorization: Long? = null
    private var phrase = CharArray(0)
    private var input = CharArray(0)
    private var confirmedPhrase = false
    private var connections = JSONArray()
    private var review: JSONArray? = null
    private var modes = JSONArray()
    private var error = ""
    private var step = "overview"
    private var mode = "recoveryCode"
    private var busy = false
    private var success = false
    private var supportsPasskey = false
    private var selectedPasskey = false
    private var saved = false
    private var understands = false
    private var resolverRequired = false
    private var resolverConfirmed = false
    private var uploadError = ""
    private var publicationAdmitted = false
    private var accountLabels = ""

    @Synchronized fun retained(): Boolean = !retired && host.matches(currentHost()) &&
        currentGeneration() == generation && (success || accountCurrent())
    @Synchronized fun dismissedByUser(): Boolean = userDismissed
    @Synchronized fun readable(): Boolean = retained() && canRead()
    @Synchronized fun active(): Boolean = readable() && canInteract()
    @Synchronized fun canEditCredential(): Boolean = active() && !busy && !success && review == null &&
        (kind == "backupSetup" && step == "recoveryPhrase" || kind == "backupRestore" && mode == "recoveryCode")
    @Synchronized fun admitPublication(fence: () -> Boolean): Boolean {
        if (publicationAdmitted || !readable() || !fence() || !canContinue()) return false
        publicationAdmitted = true
        return true
    }
    @Synchronized fun projection(): JSONObject? {
        if (!readable()) return null
        return JSONObject().put("id", id).put("kind", "accountBackup").put("name", "")
            .put("emoji", "").put("dismissAttempt", 0).put("canCreate", false).put("templates", JSONArray())
            .put("accountBackup", JSONObject()
                .put("kind", kind).put("title", when (kind) {
                    "account" -> "Account"; "backupSetup" -> "Set up backup"; else -> "Restore Backup"
                }).put("busy", busy).put("canBack", false).put("canClose", true).put("success", success)
                .put("connections", JSONArray(connections.toString())).put("deleting", busy && pendingAction == "account.delete")
                .put("step", step).put("error", error).put("copied", false).put("canContinue", canContinue())
                .put("supportsPasskey", supportsPasskey).put("selectedPasskey", selectedPasskey)
                .put("saved", saved).put("understands", understands).put("uploading", step == "upload" && busy)
                .put("uploadError", uploadError).put("mode", mode).put("modes", JSONArray(modes.toString()))
                .put("status", JSONObject.NULL).put("review", review?.let { JSONArray(it.toString()) } ?: JSONObject.NULL)
                .put("resolverConfirmationRequired", resolverRequired).put("resolverConfirmed", resolverConfirmed))
    }

    @Synchronized private fun canContinue() = when (kind) {
        "backupSetup" -> step == "upload" || saved && understands && confirmedPhrase
        "backupRestore" -> if (review != null) !resolverRequired || resolverConfirmed
            else mode != "recoveryCode" || input.isNotEmpty()
        else -> true
    }

    /** Exact cancellation never reads labels, secrets, a current grant or a successor. */
    fun cancel(requestID: String): Boolean {
        if (requestID != id) return false
        retire(dismissed = true); return true
    }

    fun command(request: JSONObject): Boolean {
        if (request.optString("id") != id) return false
        val action = request.optString("action")
        if (action == "close" || action == "back") return cancel(id)
        val accepted = synchronized(this) {
            if (!active() || busy) return@synchronized false
            when (action) {
                "setup.consent" -> {
                    if (kind != "backupSetup" || step != "recoveryPhrase") return@synchronized false
                    saved = request.optBoolean("saved"); understands = request.optBoolean("understands"); true
                }
                "restore.consent" -> {
                    if (kind != "backupRestore" || review == null) return@synchronized false
                    resolverConfirmed = request.optBoolean("value"); true
                }
                "restore.mode" -> {
                    val next = request.optString("mode")
                    if (kind != "backupRestore" || review != null ||
                        (0 until modes.length()).none { modes.getJSONObject(it).let { row ->
                            row.optString("id") == next && !row.optBoolean("unavailable")
                        } }) return@synchronized false
                    mode = next; input.fill('\u0000'); input = CharArray(0); error = ""; true
                }
                else -> {
                    if (action !in expectedAction ||
                        action in setOf("setup.finish", "setup.retry", "restore.prepare", "restore.confirm") && !canContinue() ||
                        action == "setup.begin" && request.optBoolean("passkey") && !supportsPasskey) return@synchronized false
                    // Claim before delivery: a repeated tap cannot be queued for a subsequent phase.
                    val allowed = expectedAction
                    expectedAction = emptySet(); claimedAuthorization = authorizationRevision(); busy = true
                    actions.trySend(JSONObject(request.toString())).isSuccess.also { sent ->
                        if (!sent) { expectedAction = allowed; claimedAuthorization = null; busy = false }
                        else { pendingAction = action; error = ""; uploadError = "" }
                    }
                }
            }
        }
        if (accepted) notifyChanged()
        return accepted
    }

    suspend fun awaitAction(vararg allowed: String): JSONObject {
        synchronized(this) {
            if (!retained()) throw CancellationException("Account/backup visit retired")
            expectedAction = allowed.toSet(); busy = false; pendingAction = null
        }
        notifyChanged()
        val action = actions.receive()
        if (!readable() || claimedAuthorization != authorizationRevision())
            throw CancellationException("Account/backup authority changed")
        return action
    }
    suspend fun awaitClosed() = closed.await()
    fun configureSetup(passkey: Boolean) { synchronized(this) { supportsPasskey = passkey }; notifyChanged() }
    fun configureAccount(rows: List<NativeLinkedAccountConnection>) {
        synchronized(this) {
            connections = JSONArray(rows.filter { it.provider in setOf("apple", "google") }.map {
                JSONObject().put("provider", it.provider).put("title", if (it.provider == "apple") "Apple" else "Google")
            })
            accountLabels = rows.joinToString("\n\n") { listOfNotNull(it.provider, it.email).joinToString("\n") }
        }; notifyChanged()
    }
    fun configureRestore(device: Boolean, passkey: Boolean) {
        synchronized(this) {
            modes = JSONArray().put(JSONObject().put("id", "deviceKey").put("title", "This device").put("unavailable", !device))
                .put(JSONObject().put("id", "passkey").put("title", "Passkey").put("unavailable", !passkey))
                .put(JSONObject().put("id", "recoveryCode").put("title", "Recovery phrase").put("unavailable", false))
            mode = if (device) "deviceKey" else if (passkey) "passkey" else "recoveryCode"
        }; notifyChanged()
    }
    @Synchronized fun method() = mode
    fun phase(value: String, working: Boolean = false, passkey: Boolean = selectedPasskey) {
        synchronized(this) { step = value; busy = working; selectedPasskey = passkey; error = "" }; notifyChanged()
    }
    fun working(value: Boolean) {
        synchronized(this) { busy = value; if (value) { error = ""; uploadError = "" } }; notifyChanged()
    }
    fun failure(message: String, upload: Boolean = false) {
        synchronized(this) { busy = false; if (upload) uploadError = message else error = message }; notifyChanged()
    }
    fun review(value: JSONArray, requiresResolver: Boolean) {
        synchronized(this) { review = value; resolverRequired = requiresResolver; resolverConfirmed = false; busy = false; error = "" }; notifyChanged()
    }
    fun complete() {
        synchronized(this) { success = true; busy = false; step = "complete"; clearCredentials() }; notifyChanged()
    }
    fun createPhrase(withPasskey: Boolean = false) {
        synchronized(this) { clearCredentials(); phrase = BackupRecoveryPhrase.generate().toCharArray(); step = "recoveryPhrase"; busy = false; error = ""; selectedPasskey = withPasskey }
        notifyChanged()
    }
    /** Called only by the native input leaf. No React event carries text. */
    fun replaceCredential(value: CharArray): Boolean {
        val accepted = synchronized(this) {
            if (!canEditCredential() || value.size > 1024) return@synchronized false
            input.fill('\u0000'); input = value.copyOf()
            confirmedPhrase = kind == "backupSetup" &&
                BackupRecoveryPhrase.phrase(BackupRecoveryPhrase.words(String(value))).toCharArray().contentEquals(phrase)
            true
        }
        if (accepted) notifyChanged()
        return accepted
    }
    @Synchronized fun credential(): Pair<String, String>? {
        if (!readable() || success || review != null ||
            kind == "backupSetup" && step !in setOf("recoveryPhrase", "upload") ||
            kind == "backupRestore" && mode != "recoveryCode") return null
        if (kind == "account") return accountLabels to ""
        return String(phrase) to String(input)
    }
    @Synchronized fun borrowSecret(): CharArray {
        check(readable() && canContinue())
        return if (kind == "backupSetup") phrase.copyOf()
            else BackupRecoveryPhrase.phrase(BackupRecoveryPhrase.words(String(input))).toCharArray()
    }
    @Synchronized fun copyRecoveryPhrase(copy: (String) -> Unit): Boolean {
        if (!active() || kind != "backupSetup" || step !in setOf("recoveryPhrase", "upload") || phrase.isEmpty()) return false
        copy(String(phrase))
        return true
    }
    fun observe(callback: () -> Unit): () -> Unit {
        synchronized(this) { listeners += callback }
        return { synchronized(this) { listeners -= callback } }
    }
    fun authorityChanged() { if (!retained()) close() else notifyChanged() }
    private fun notifyChanged() {
        val callbacks = synchronized(this) { listeners.toList() }
        callbacks.forEach { it() }; changed()
    }
    @Synchronized private fun clearCredentials() {
        phrase.fill('\u0000'); input.fill('\u0000'); phrase = CharArray(0); input = CharArray(0); confirmedPhrase = false; accountLabels = ""
    }
    override fun close() = retire(dismissed = false)
    private fun retire(dismissed: Boolean) {
        synchronized(this) { if (retired) return; userDismissed = dismissed; retired = true; clearCredentials(); actions.cancel(); closed.complete(Unit) }
        notifyChanged()
    }
}
