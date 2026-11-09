package com.lavasec.app.reactnative

import com.ibm.icu.text.BreakIterator
import com.lavasec.app.foundation.chained.ChainedUpstreamConfParser
import com.lavasec.app.platform.VpnConfigurationEditorRequest
import com.lavasec.app.platform.VpnConfigurationPick
import com.lavasec.app.platform.VpnConfigurationPickSource
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * One shared RN configuration sheet owns only newly supplied native content. Saved credentials
 * are never hydrated. Its suspended parent pick is independent of immediate sheet commands.
 */
internal class NativeSharedVpnEditorOwner(
    currentGeneration: () -> Long,
    private val canRead: () -> Boolean,
    private val reauthorize: suspend () -> Boolean,
    private val privacyCoverRequired: () -> Boolean,
    private val pickDocument: suspend () -> VpnConfigurationPick,
    private val publish: () -> Unit,
    private val authorityRevision: () -> Long,
    private val currentHost: () -> NativeVisitHost?,
    private val editorIdentity: () -> Any? = { null },
    coroutineScope: CoroutineScope? = null,
    private val canInteract: () -> Boolean = canRead,
    private val requiredMessage: () -> String = { "Choose or paste a WireGuard configuration." },
    private val invalidMessage: () -> String = { "This WireGuard configuration is not supported." },
    private val importFailedMessage: () -> String = { "The configuration file could not be read." },
    private val importFailureMessage: (VpnConfigurationPick.ImportFailureReason) -> String = { reason -> when (reason) {
        VpnConfigurationPick.ImportFailureReason.TOO_LARGE -> "This file is larger than 64 KB — far too big for a WireGuard config. Wrong file?"
        VpnConfigurationPick.ImportFailureReason.NOT_TEXT -> "That file isn't text, so it isn't a WireGuard config."
    } },
) : VpnConfigurationPickSource, LavaWireGuardInputPort, AutoCloseable {
    private val generationSource = currentGeneration
    private val scope = CoroutineScope(
        (coroutineScope?.coroutineContext ?: Dispatchers.Main.immediate) +
            SupervisorJob(coroutineScope?.coroutineContext?.get(Job)),
    )
    private class Visit(
        val request: VpnConfigurationEditorRequest,
        val generation: Long,
        val editorIdentity: Any?,
        val host: NativeVisitHost,
        var readRevision: Long,
    ) {
        val id = UUID.randomUUID().toString()
        val completion = CompletableDeferred<VpnConfigurationPick>()
        var name = request.name
        var nameResetRevision = 0
        var raw = ""
        var revealed = true
        var reading = false
        var error = ""
        var dismissAttempt = 0
        var importToken = UUID.randomUUID()
        var importJob: Job? = null
        // Exact native result identity reserves this sheet through import/staging publication.
        var pendingPick: VpnConfigurationPick.Edited? = null
        val dirty: Boolean get() = name != request.name || raw.isNotEmpty()
    }
    private val lock = Any()
    private var visit: Visit? = null
    private var closed = false
    private val observers = mutableMapOf<String, MutableSet<() -> Unit>>()

    val currentId: String? get() = synchronized(lock) { retainedVisitLocked()?.id }
    val currentGeneration: Long? get() = synchronized(lock) { retainedVisitLocked()?.generation }

    override suspend fun pick(): VpnConfigurationPick = edit(VpnConfigurationEditorRequest("", false))

    override suspend fun edit(request: VpnConfigurationEditorRequest): VpnConfigurationPick {
        val owned = synchronized(lock) {
            retainedVisitLocked()
            val host = currentHost() ?: return VpnConfigurationPick.Unavailable
            if (closed || visit != null || !host.matches(currentHost()) || !canRead()) return VpnConfigurationPick.Unavailable
            Visit(request, generationSource(), editorIdentity(), host, authorityRevision()).also { visit = it }
        }
        changed()
        return try {
            val result = owned.completion.await()
            if (result is VpnConfigurationPick.Read || result is VpnConfigurationPick.Edited) synchronized(lock) {
                // Save transfers newly supplied text only to this native parent. The original
                // host/baseline still owns admission before the coordinator starts processing it.
                if (closed || visit !== owned || owned.pendingPick !== result ||
                    owned.generation != generationSource() || owned.editorIdentity != editorIdentity() ||
                    !owned.host.matches(currentHost())) {
                    retireLocked(owned, VpnConfigurationPick.Unavailable)
                    VpnConfigurationPick.Unavailable
                } else result
            } else result
        } catch (cancelled: CancellationException) {
            synchronized(lock) { retireLocked(owned, VpnConfigurationPick.Cancelled) }
            changed()
            throw cancelled
        }
    }

    /**
     * Read-free acknowledgement of one exact native selection. No grant is borrowed to close a
     * paused/retired visit, and a late parent cannot close a successor with an equal-valued pick.
     */
    override fun finishPick(pick: VpnConfigurationPick) {
        val retired = synchronized(lock) {
            val owned = visit ?: return@synchronized false
            if (owned.pendingPick !== pick) return@synchronized false
            retireLocked(owned, VpnConfigurationPick.Cancelled)
            true
        }
        if (retired) changed()
    }

    /** Public metadata only; confidential text exists exclusively behind the native input port. */
    fun projection(): JSONObject? = synchronized(lock) {
        val owned = retainedVisitLocked() ?: return null
        if (!owned.host.matches(currentHost()) || !canRead()) return null
        reconcilePrivacyLocked(owned)
        val editable = mayEdit(owned)
        val state = JSONObject().put("name", owned.name)
            .put("nameResetRevision", owned.nameResetRevision).put("dirty", owned.dirty)
            .put("hasContent", owned.raw.isNotEmpty())
            .put("concealed", owned.raw.isNotEmpty() && !owned.revealed)
            .put("reading", owned.reading).put("canEdit", editable)
            .put("canSave", editable && canSave(owned)).put("error", owned.error)
        JSONObject().put("id", owned.id).put("kind", "vpnConfiguration")
            .put("name", owned.request.name).put("emoji", "").put("dismissAttempt", owned.dismissAttempt)
            .put("canCreate", false).put("templates", JSONArray()).put("vpnEditor", state)
    }

    override fun snapshot(ownerID: String): LavaWireGuardInputState? = synchronized(lock) {
        val owned = retainedVisitLocked()?.takeIf { it.id == ownerID } ?: return null
        if (!owned.host.matches(currentHost())) return null
        reconcilePrivacyLocked(owned)
        val current = isCurrent(owned)
        val authorized = current && mayEdit(owned)
        LavaWireGuardInputState(
            generation = owned.generation,
            authorized = authorized,
            revealed = owned.revealed,
            // Security-off background retention permits only an already accepted display frame.
            retainAcceptedDisplay = current && owned.revealed && !privacyCoverRequired(),
            text = if (authorized && owned.revealed) owned.raw else null,
        )
    }

    override fun observe(ownerID: String, onChanged: () -> Unit): () -> Unit {
        synchronized(lock) { observers.getOrPut(ownerID) { mutableSetOf() }.add(onChanged) }
        return { synchronized(lock) { observers[ownerID]?.let { callbacks ->
            callbacks.remove(onChanged)
            if (callbacks.isEmpty()) observers.remove(ownerID)
        }; Unit } }
    }

    override fun replace(ownerID: String, text: String): Boolean {
        synchronized(lock) {
            val owned = retainedVisitLocked()?.takeIf { it.id == ownerID } ?: return false
            if (!mayEdit(owned) || !owned.revealed) return false
            if (owned.raw == text) return true
            owned.raw = text
            owned.error = ""
        }
        changed()
        return true
    }

    override fun canEditName(ownerID: String): Boolean = synchronized(lock) {
        retainedVisitLocked()?.takeIf { it.id == ownerID }?.let(::mayEdit) == true
    }

    /** Used only by the confidential native leaf; no text or reveal event crosses to JS. */
    override fun reveal(ownerID: String): Boolean {
        synchronized(lock) {
            val owned = retainedVisitLocked()?.takeIf { it.id == ownerID } ?: return false
            if (!mayEdit(owned)) return false
            owned.revealed = true
        }
        changed()
        return true
    }

    fun requestDismiss(): Boolean {
        synchronized(lock) {
            val owned = retainedVisitLocked() ?: return false
            if (!mayEdit(owned)) return false
            ++owned.dismissAttempt
        }
        changed()
        return true
    }

    suspend fun execute(input: JSONObject): NativeSharedForegroundResult {
        val type = input.optString("type")
        val owned = synchronized(lock) {
            val current = visit ?: return rejected("vpn-editor-closed")
            if (input.optString("id") != current.id || !retireStaleLocked(current)) return rejected("vpn-editor-stale")
            if (!current.host.matches(currentHost())) return rejected("vpn-editor-locked")
            current
        }
        if (type == "vpnEditor.enter") {
            if (!refreshAuthority(owned)) return rejected("vpn-editor-locked")
            changed()
            return NativeSharedForegroundResult()
        }
        synchronized(lock) {
            if (!mayEdit(owned)) return rejected("vpn-editor-locked")
            when (type) {
                "foreground.dismiss" -> {
                    // Native text can be newer than RN's last dirty projection.
                    if (owned.dirty && !input.optBoolean("discardConfirmed")) return NativeSharedForegroundResult(value = false)
                    retireLocked(owned, VpnConfigurationPick.Cancelled)
                }
                "vpnEditor.name" -> { owned.name = boundName(input.optString("name")); owned.error = "" }
                "vpnEditor.file" -> startFileLocked(owned)
                "vpnEditor.save" -> {
                    val raw = owned.raw.trim().takeIf(String::isNotEmpty)
                    if (raw == null && !owned.request.exists) {
                        owned.error = requiredMessage()
                    } else if (raw != null && !validConfiguration(raw)) {
                        owned.error = invalidMessage()
                    } else if (!canSave(owned)) return rejected("vpn-editor-unchanged")
                    else {
                        // Parsing cannot confer authority on a replaced or destroyed native host.
                        if (!mayEdit(owned)) return rejected("vpn-editor-locked")
                        val picked = VpnConfigurationPick.Edited(boundName(owned.name.trim()), raw)
                        owned.pendingPick = picked
                        owned.raw = ""
                        owned.revealed = false
                        owned.error = ""
                        owned.completion.complete(picked)
                    }
                }
                else -> return rejected("vpn-editor-invalid-command")
            }
        }
        changed()
        return NativeSharedForegroundResult(value = true)
    }

    /** Lifecycle authority changes must notify native fields even if their JS props are retained. */
    fun authorityChanged() {
        synchronized(lock) {
            // Without an editor there is no presentation to invalidate. Publishing here used
            // to multiply every protection-state update into redundant full RN renders.
            if (visit == null) return
            visit?.let { owned ->
                if (!isCurrent(owned)) retireLocked(owned, VpnConfigurationPick.Unavailable)
                else reconcilePrivacyLocked(owned)
            }
        }
        changed()
    }

    /** Destroying an old Activity cannot cancel a successor's same-graph visit. */
    fun retireHost(owner: Any, nativeHostID: String) {
        val retired = synchronized(lock) {
            val owned = visit ?: return@synchronized false
            if (owned.host.owner !== owner || owned.host.nativeHostID != nativeHostID) return@synchronized false
            retireLocked(owned, VpnConfigurationPick.Unavailable)
            true
        }
        if (retired) changed()
    }

    private suspend fun refreshAuthority(owned: Visit): Boolean {
        if (!synchronized(lock) { retireStaleLocked(owned) && owned.host.matches(currentHost()) }) return false
        val granted = reauthorize()
        return synchronized(lock) {
            if (!retireStaleLocked(owned) || !granted || !owned.host.matches(currentHost()) || !canRead()) false
            else { owned.readRevision = authorityRevision(); true }
        }
    }

    private fun startFileLocked(owned: Visit) {
        owned.reading = true
        owned.error = ""
        val token = UUID.randomUUID().also { owned.importToken = it }
        owned.importJob = scope.launch {
            try {
                val picked = pickDocument()
                // A document picker can pause the host and expire APP_SETTINGS authority.
                val granted = when (picked) {
                    is VpnConfigurationPick.Read, is VpnConfigurationPick.ImportFailed,
                    VpnConfigurationPick.Unavailable -> refreshAuthority(owned)
                    else -> true
                }
                synchronized(lock) {
                    if (!retireStaleLocked(owned) || owned.importToken != token) return@launch
                    owned.reading = false
                    owned.importJob = null
                    if (!owned.host.matches(currentHost())) return@synchronized
                    when (picked) {
                        is VpnConfigurationPick.Read -> if (granted && canRead() && owned.readRevision == authorityRevision()) {
                            owned.raw = picked.rawConfiguration
                            owned.revealed = false
                            owned.error = if (validConfiguration(owned.raw.trim())) "" else invalidMessage()
                        } else owned.error = ""
                        is VpnConfigurationPick.ImportFailed -> if (granted && canRead() && owned.readRevision == authorityRevision()) {
                            owned.error = importFailureMessage(picked.reason)
                        }
                        VpnConfigurationPick.Unavailable -> if (granted && canRead() && owned.readRevision == authorityRevision()) {
                            owned.error = importFailedMessage()
                        }
                        else -> Unit
                    }
                }
                changed()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                synchronized(lock) {
                    if (!retireStaleLocked(owned) || owned.importToken != token) return@launch
                    owned.reading = false
                    owned.importJob = null
                    if (owned.host.matches(currentHost()) && canRead()) owned.error = importFailedMessage()
                }
                changed()
            }
        }
    }

    private fun isCurrent(owned: Visit): Boolean = !closed && visit === owned &&
        owned.generation == generationSource() &&
        (owned.pendingPick != null || owned.editorIdentity == editorIdentity()) &&
        owned.host.retains(currentHost())
    private fun retainedVisitLocked(): Visit? = visit?.takeIf(::retireStaleLocked)
    private fun retireStaleLocked(owned: Visit): Boolean {
        if (isCurrent(owned)) return true
        retireLocked(owned, VpnConfigurationPick.Unavailable)
        return false
    }
    private fun mayEdit(owned: Visit): Boolean = retireStaleLocked(owned) && owned.host.matches(currentHost()) &&
        owned.pendingPick == null && !owned.reading && canRead() && canInteract() &&
        owned.readRevision == authorityRevision()
    private fun reconcilePrivacyLocked(owned: Visit) {
        // This flag describes the user's background-cover policy, not an active foreground
        // cover. Protected foreground typing/reveal remains available to its authorized visit.
        if (privacyCoverRequired() && (!canRead() || owned.readRevision != authorityRevision())) owned.revealed = false
    }
    private fun canSave(owned: Visit): Boolean = owned.raw.isNotBlank() ||
        owned.request.exists && boundName(owned.name.trim()) != owned.request.name
    private fun validConfiguration(raw: String): Boolean = runCatching { ChainedUpstreamConfParser.rotation(raw) }.isSuccess

    private fun retireLocked(owned: Visit, result: VpnConfigurationPick) {
        if (visit !== owned) return
        visit = null
        owned.importToken = UUID.randomUUID()
        owned.importJob?.cancel()
        owned.importJob = null
        owned.raw = ""
        owned.revealed = false
        owned.reading = false
        owned.pendingPick = null
        owned.completion.complete(result)
    }

    private fun changed() {
        publish()
        val callbacks = synchronized(lock) { observers.values.flatMap { it.toList() } }
        callbacks.forEach { it() }
    }

    override fun close() {
        synchronized(lock) {
            visit?.let { retireLocked(it, VpnConfigurationPick.Cancelled) }
            closed = true
        }
        scope.cancel()
        changed()
        synchronized(lock) { observers.clear() }
    }

    private fun boundName(value: String): String {
        val boundaries = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(value) }
        var end = boundaries.first()
        repeat(80) { val next = boundaries.next(); if (next == BreakIterator.DONE) return value; end = next }
        return value.substring(0, end)
    }
    private fun rejected(reason: String) = NativeSharedForegroundResult(status = "rejected", reason = reason)
}
