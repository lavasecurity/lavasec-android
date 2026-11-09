package com.lavasec.app.reactnative

import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.onboarding.OnboardingPage
import com.lavasec.app.onboarding.OnboardingOwnerState
import com.lavasec.app.onboarding.PendingVpnConsent
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Shared six-page presentation over the existing Android configuration/permission owner. */
internal class NativeSharedOnboardingOwner(
    @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
    private val read: () -> Pair<Long?, OnboardingOwnerState?>,
    private val port: Port,
    private val foreground: () -> Boolean,
    private val readyForOnboarding: (Long, String) -> Boolean,
    private val clockMillis: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val onChanged: () -> Unit = {},
    private val allowMock: Boolean = com.lavasec.app.BuildConfig.DEBUG,
) : AutoCloseable {
    internal interface Port {
        suspend fun forward(generation: Long): PendingVpnConsent?
        suspend fun back(generation: Long)
        suspend fun selectProtectionLevel(generation: Long, level: ProtectionLevel)
        suspend fun selectFallback(generation: Long, enabled: Boolean)
        suspend fun completeVpnConsent(generation: Long, id: Long, granted: Boolean): Boolean
        /** Exact retirement remains safe after presentation/host authority is lost. */
        suspend fun cancelVpnConsent(generation: Long, id: Long): Boolean = false
        suspend fun completeNotifications(generation: Long, granted: Boolean): Boolean
        suspend fun presentVpn(pending: PendingVpnConsent): Boolean
        suspend fun requestNotifications(): Boolean
        fun notificationsGranted(): Boolean
        fun vpnInstalled(): Boolean
        /** Returns proof from the captured native owner, even if its successful commit rotates graph. */
        suspend fun finish(generation: Long): Boolean
        suspend fun finish(generation: Long, visitID: String): Boolean = finish(generation)
    }
    private data class Visit(
        val id: String,
        val generation: Long,
        val mock: Boolean,
        val page: Int,
        val history: List<Int> = emptyList(),
        val visited: Set<Int>,
        val phase: String = "setup",
        val phaseStarted: Long = 0,
        val openStarted: Long? = null,
        val busy: String = "",
        val error: String = "",
        val finishing: Boolean = false,
        val mockLevel: String = "balanced",
        val mockFallback: Boolean = true,
        val mockVpn: Boolean = false,
        val mockNotifications: Boolean = false,
        val completion: CompletableDeferred<Boolean> = CompletableDeferred(),
    )
    private val lock = Any()
    private val commands = Mutex()
    @Volatile private var visit: Visit? = null
    private var closed = false
    // Bounded receipt prevents an immediate Close/Complete from outrunning a flow waiter.
    private var lastCompletion: Pair<String, Boolean>? = null

    fun enter(generation: Long, mock: Boolean = false): String? {
        var retired: CompletableDeferred<Boolean>? = null
        var changed = false
        val id = synchronized(lock) {
            if (closed) return@synchronized null
            val (currentGeneration, state) = read()
            if (generation != currentGeneration || state == null || mock && !allowMock || !mock && (state.completed || state.reconstructionRequired)) return@synchronized null
            visit?.takeIf { it.generation == generation && it.mock == mock }?.let { return@synchronized it.id }
            if (mock && visit?.mock == false) return@synchronized null
            retired = retireLocked()
            val page = if (mock) 0 else sharedPage(checkNotNull(state.onboarding).page)
            Visit(UUID.randomUUID().toString(), generation, mock, page, history = (0 until page).toList(),
                visited = (0..page).toSet(), phaseStarted = clockMillis()).also { visit = it; changed = true }.id
        }
        retired?.complete(false)
        if (changed) onChanged()
        return id
    }

    /** Snapshot construction never advances a permission, choice or completion marker. */
    fun snapshot(): JSONObject? {
        var retired: CompletableDeferred<Boolean>? = null
        val result = synchronized(lock) {
        val current = visit ?: return@synchronized null
        val (generation, state) = read()
        if (generation != current.generation || state == null || !current.mock && (state.completed || state.reconstructionRequired)) {
            if (!current.finishing) retired = retireLocked()
            return@synchronized null
        }
        val native = state.onboarding
        if (!current.mock && native == null) return@synchronized null
        JSONObject().put("id", current.id).put("mock", current.mock).put("page", current.page)
            .put("history", JSONArray(current.history)).put("visited", JSONArray(current.visited.sorted()))
            .put("level", if (current.mock) current.mockLevel else when (checkNotNull(native).draft.protectionLevel) {
                ProtectionLevel.CORE -> "essential"
                ProtectionLevel.BALANCED -> "balanced"
                ProtectionLevel.EXTRA -> "comprehensive"
            }).put("fallback", if (current.mock) current.mockFallback else checkNotNull(native).draft.resolver.useEncryptedFallback)
            .put("dnsProfile", false).put("supportsDNSProfile", false)
            .put("vpnInstalled", if (current.mock) current.mockVpn else checkNotNull(native).vpnConfigured && port.vpnInstalled())
            .put("notifications", if (current.mock) current.mockNotifications else port.notificationsGranted())
            .put("busy", current.busy.ifEmpty { if (!current.mock && state.commitInFlight) "complete" else "" })
            .put("error", current.error.ifEmpty { if (current.mock) "" else state.error.orEmpty() })
            .put("phase", current.phase)
        }
        retired?.complete(false)
        return result
    }

    suspend fun awaitCompletion(id: String): Boolean {
        val completion = synchronized(lock) {
            visit?.takeIf { it.id == id }?.completion ?: return lastCompletion?.takeIf { it.first == id }?.second ?: false
        }
        return completion.await()
    }

    suspend fun execute(generation: Long, request: JSONObject): JSONObject = commands.withLock {
        val current = visit
        val id = request.optString("id")
        if (current == null || current.id != id || current.generation != generation || !isCurrent(current)) {
            return@withLock refusal("stale-onboarding")
        }
        if (!foreground()) return@withLock refusal("app-background")
        val type = request.optString("type")
        // Layout acknowledgment belongs to this exact setup visit. Enter keeps
        // metadata hydration possible under the native cover; local mock Close
        // is read-free. Queued user choices and permission requests never inherit
        // only a fresh grant or another presentation's measured viewport.
        if (type in USER_MUTATIONS && !readyForOnboarding(generation, id)) {
            return@withLock refusal("onboarding-not-ready")
        }
        try {
            when (type) {
                "onboarding.enter" -> accepted()
                "onboarding.dismiss" -> if (current.mock) finishMock(current, completed = false) else refusal("onboarding-required")
                "onboarding.back" -> {
                    if (current.phase == "opening" || current.phase == "released" || current.history.isEmpty()) refusal("onboarding-navigation")
                    else navigate(current, current.history.last(), back = true)
                }
                "onboarding.navigate" -> navigate(current, request.optInt("page", -1), back = false,
                    revisit = request.optBoolean("revisit", false))
                "onboarding.choice" -> choice(current, request)
                "onboarding.vpn" -> vpn(current)
                "onboarding.notifications" -> notifications(current)
                "onboarding.ready" -> {
                    if (current.page != 5 || current.phase != "arriving" || !readyForOnboarding(generation, id) ||
                        clockMillis() - current.phaseStarted < ARRIVAL_MILLIS) refusal("onboarding-not-ready")
                    else { replace(current.copy(phase = "ready")); accepted() }
                }
                "onboarding.open" -> {
                    if (current.page != 5 || current.phase != "ready" || !readyForOnboarding(generation, id)) refusal("onboarding-not-ready")
                    else { replace(current.copy(phase = "opening", openStarted = clockMillis())); accepted() }
                }
                "onboarding.release" -> {
                    if (current.phase != "opening" || current.openStarted == null ||
                        clockMillis() - current.openStarted < RELEASE_MILLIS || !readyForOnboarding(generation, id)) refusal("onboarding-not-ready")
                    else { replace(current.copy(phase = "released")); accepted() }
                }
                "onboarding.complete" -> complete(current)
                else -> refusal("invalid-command")
            }
        } catch (cancelled: CancellationException) {
            update(current.id) { it.copy(busy = "", error = "") }
            throw cancelled
        } catch (failure: Exception) {
            update(current.id) { it.copy(busy = "", error = failure.message ?: "Setup is unavailable.") }
            refusal("onboarding-unavailable")
        }
    }

    private suspend fun navigate(current: Visit, page: Int, back: Boolean, revisit: Boolean = false): JSONObject {
        if (page !in 0..5 || current.phase == "opening" || current.phase == "released") return refusal("onboarding-navigation")
        if (page == current.page) return accepted()
        if (!back && (page != current.page + 1 && page !in current.visited || revisit && page !in current.visited)) return refusal("onboarding-navigation")
        if (page >= 3 && !(if (current.mock) current.mockVpn else port.vpnInstalled())) return refusal("vpn-required")
        if (!current.mock && !moveNative(current, page.coerceAtMost(4))) return refusal("onboarding-navigation")
        if (!current.mock && page == 4) {
            val state = native(current) ?: return refusal("stale-onboarding")
            if (!mayInteract(current)) return refusal("onboarding-not-ready")
            port.selectFallback(current.generation, checkNotNull(state.onboarding).draft.resolver.useEncryptedFallback)
            if (!isCurrent(current)) return refusal("stale-onboarding")
        }
        val history = if (back) current.history.dropLast(1) else current.history + current.page
        replace(current.copy(page = page, history = history, visited = current.visited + page,
            phase = if (page == 5) "arriving" else "setup", phaseStarted = clockMillis(), openStarted = null, error = ""))
        return accepted()
    }

    /** Bridge the combined shared permission page without skipping native owner admissions. */
    private suspend fun moveNative(current: Visit, sharedPage: Int): Boolean {
        val desired = when (sharedPage) {
            0 -> OnboardingPage.LAVA
            1 -> OnboardingPage.GUARD_INTRO
            2 -> OnboardingPage.FEATURES
            3 -> OnboardingPage.PROTECTION_LEVEL
            else -> OnboardingPage.CONNECTION_QUALITY
        }
        repeat(24) {
            if (!mayInteract(current)) return false
            val state = native(current) ?: return false
            val page = state.onboarding?.page ?: return false
            if (page == desired) return true
            if (page.ordinal > desired.ordinal) {
                port.back(current.generation)
            } else when (page) {
                OnboardingPage.DONE -> return false
                OnboardingPage.VPN -> {
                    if (!port.vpnInstalled()) return false
                    // Prepared is decided by VpnService.prepare, never RN or a fabricated result.
                    if (!withPreparedVpn(current) { it == null }) return false
                }
                OnboardingPage.NOTIFICATIONS -> {
                    if (state.onboarding.notificationHandled) port.forward(current.generation)
                    else {
                        if (!port.completeNotifications(current.generation, port.notificationsGranted())) return false
                        if (!port.notificationsGranted()) port.forward(current.generation)
                    }
                }
                else -> port.forward(current.generation)
            }
            delay(1) // Allow the native StateFlow projection to publish; never invent its next page.
        }
        return false
    }

    private suspend fun choice(current: Visit, request: JSONObject): JSONObject {
        if (current.phase != "setup") return refusal("onboarding-navigation")
        if (request.has("dnsProfile")) return refusal("unsupported-platform")
        if (current.mock) {
            var next = current
            if (request.has("level")) {
                val level = request.optString("level")
                if (current.page != 3 || level !in listOf("essential", "balanced", "comprehensive")) return refusal("invalid-choice")
                next = next.copy(mockLevel = level)
            }
            if (request.has("fallback")) {
                if (current.page != 4 || request.opt("fallback") !is Boolean) return refusal("invalid-choice")
                next = next.copy(mockFallback = request.getBoolean("fallback"))
            }
            replace(next)
            return accepted()
        }
        if (request.has("level")) {
            if (current.page != 3) return refusal("onboarding-navigation")
            val level = when (request.optString("level")) {
                "essential" -> ProtectionLevel.CORE
                "balanced" -> ProtectionLevel.BALANCED
                "comprehensive" -> ProtectionLevel.EXTRA
                else -> return refusal("invalid-choice")
            }
            if (!moveNative(current, 3)) return refusal("onboarding-navigation")
            if (!mayInteract(current)) return refusal("onboarding-not-ready")
            port.selectProtectionLevel(current.generation, level)
        }
        if (request.has("fallback")) {
            if (current.page != 4 || request.opt("fallback") !is Boolean) return refusal("invalid-choice")
            if (!moveNative(current, 4)) return refusal("onboarding-navigation")
            if (!mayInteract(current)) return refusal("onboarding-not-ready")
            port.selectFallback(current.generation, request.getBoolean("fallback"))
        }
        return if (isCurrent(current)) accepted() else refusal("stale-onboarding")
    }

    private suspend fun vpn(current: Visit): JSONObject {
        if (current.page != 2 || current.phase != "setup") return refusal("onboarding-navigation")
        update(current.id) { it.copy(busy = "vpn", error = "") }
        try {
            if (current.mock) {
                delay(500)
                if (!mayInteract(current)) return refusal("onboarding-not-ready")
                update(current.id) { it.copy(mockVpn = true) }
                return accepted()
            }
            if (!moveNative(current, 2)) return refusal("onboarding-navigation")
            if (!mayInteract(current)) return refusal("onboarding-not-ready")
            port.forward(current.generation) // FEATURES -> VPN
            delay(1)
            if (native(current)?.onboarding?.page != OnboardingPage.VPN) return refusal("onboarding-navigation")
            if (!mayInteract(current)) return refusal("onboarding-not-ready")
            return withPreparedVpn(current) { pending ->
                if (!isCurrent(current)) return@withPreparedVpn refusal("stale-onboarding")
                if (pending != null) {
                    if (!mayInteract(current)) return@withPreparedVpn refusal("onboarding-not-ready")
                    val granted = port.presentVpn(pending)
                    if (!isCurrent(current) || !foreground()) return@withPreparedVpn refusal("stale-onboarding")
                    if (!port.completeVpnConsent(current.generation, pending.id, granted)) return@withPreparedVpn refusal("stale-onboarding")
                    if (!granted) {
                        update(current.id) { it.copy(error = "Allow the local VPN to continue setup.") }
                        return@withPreparedVpn refusal("vpn-denied")
                    }
                }
                delay(1)
                if (native(current)?.onboarding?.vpnConfigured == true && port.vpnInstalled()) accepted() else refusal("vpn-required")
            }
        } finally { update(current.id) { it.copy(busy = "") } }
    }

    /** Capture the native request before caller cancellation can cross the return boundary. */
    private suspend fun <T> withPreparedVpn(current: Visit, operation: suspend (PendingVpnConsent?) -> T): T {
        var pending: PendingVpnConsent? = null
        try {
            withContext(NonCancellable) { pending = port.forward(current.generation) }
            currentCoroutineContext().ensureActive()
            return operation(pending)
        } finally {
            pending?.let { owned ->
                // Completion already consumes this ID. The CAS is then a no-op;
                // it cannot reset a genuine denial or erase a successor request.
                withContext(NonCancellable) { port.cancelVpnConsent(current.generation, owned.id) }
            }
        }
    }

    private suspend fun notifications(current: Visit): JSONObject {
        if (current.page != 2 || current.phase != "setup") return refusal("onboarding-navigation")
        update(current.id) { it.copy(busy = "notifications", error = "") }
        try {
            if (current.mock) {
                delay(500)
                if (!mayInteract(current)) return refusal("onboarding-not-ready")
                update(current.id) { it.copy(mockNotifications = true) }
                return accepted()
            }
            val granted = port.requestNotifications()
            if (!isCurrent(current) || !foreground()) return refusal("stale-onboarding")
            // Optional notification permission can be requested before VPN. Its
            // current OS outcome is recorded by the native owner when reached.
            if (native(current)?.onboarding?.page == OnboardingPage.NOTIFICATIONS) {
                if (!port.completeNotifications(current.generation, granted)) return refusal("stale-onboarding")
            }
            return accepted()
        } finally { update(current.id) { it.copy(busy = "") } }
    }

    private suspend fun complete(current: Visit): JSONObject {
        val opened = current.openStarted ?: return refusal("onboarding-not-ready")
        if (current.page != 5 || current.phase != "released" || clockMillis() - opened < (if (current.mock) MOCK_COMPLETE_MILLIS else COMPLETE_MILLIS) ||
            !readyForOnboarding(current.generation, current.id) || !(if (current.mock) current.mockVpn else port.vpnInstalled())) return refusal("onboarding-not-ready")
        if (current.mock) return finishMock(current, completed = true)
        val source = native(current) ?: return refusal("stale-onboarding")
        if (source.onboarding?.page != OnboardingPage.CONNECTION_QUALITY ||
            !source.onboarding.vpnConfigured || !source.onboarding.notificationHandled) return refusal("onboarding-not-ready")
        update(current.id) { it.copy(finishing = true, busy = "complete") }
        val committed = port.finish(current.generation, current.id)
        var completion: CompletableDeferred<Boolean>? = null
        synchronized(lock) {
            val owned = visit?.takeIf { it.id == current.id }
            if (committed) {
                completion = owned?.completion
                if (owned != null) { visit = null; lastCompletion = owned.id to true }
            } else if (owned != null) {
                visit = owned.copy(finishing = false, busy = "", error = "Setup could not be saved. Please retry.")
            }
        }
        completion?.complete(true)
        onChanged()
        return if (committed) accepted() else refusal("onboarding-commit-failed")
    }

    private fun finishMock(current: Visit, completed: Boolean): JSONObject {
        val completion = synchronized(lock) { visit?.takeIf { it.id == current.id && it.mock }?.let { visit = null; lastCompletion = it.id to completed; it.completion } }
            ?: return refusal("stale-onboarding")
        completion.complete(completed)
        onChanged()
        return accepted()
    }

    private fun native(current: Visit): OnboardingOwnerState? = read().let { (generation, state) ->
        state?.takeIf { generation == current.generation && !it.completed && !it.reconstructionRequired && visit?.id == current.id }
    }
    private fun mayInteract(current: Visit) = isCurrent(current) && foreground() && readyForOnboarding(current.generation, current.id)
    private fun isCurrent(current: Visit) = if (current.mock) !closed && read().first == current.generation && visit?.id == current.id else native(current) != null
    private fun replace(next: Visit) {
        val changed = synchronized(lock) { if (visit?.id == next.id) { visit = next; true } else false }
        if (changed) onChanged()
    }
    private fun update(id: String, change: (Visit) -> Visit) {
        val changed = synchronized(lock) { visit?.takeIf { it.id == id }?.let { visit = change(it); true } ?: false }
        if (changed) onChanged()
    }
    private fun retireLocked(): CompletableDeferred<Boolean>? {
        val current = visit ?: return null
        lastCompletion = current.id to false
        visit = null
        return current.completion
    }
    override fun close() {
        val completion = synchronized(lock) { closed = true; retireLocked() }
        completion?.complete(false)
        onChanged()
    }

    companion object {
        private val USER_MUTATIONS = setOf("onboarding.back", "onboarding.navigate", "onboarding.choice", "onboarding.vpn",
            "onboarding.notifications", "onboarding.ready", "onboarding.open", "onboarding.release", "onboarding.complete")
        internal const val ARRIVAL_MILLIS = 1740L
        internal const val RELEASE_MILLIS = 840L
        internal const val COMPLETE_MILLIS = 920L
        internal const val MOCK_COMPLETE_MILLIS = 2920L
        private fun sharedPage(page: OnboardingPage): Int = when (page) {
            OnboardingPage.LAVA -> 0
            OnboardingPage.GUARD_INTRO -> 1
            OnboardingPage.FEATURES, OnboardingPage.VPN, OnboardingPage.NOTIFICATIONS -> 2
            OnboardingPage.PROTECTION_LEVEL -> 3
            OnboardingPage.CONNECTION_QUALITY, OnboardingPage.DONE -> 4
        }
        private fun accepted() = JSONObject().put("status", "completed")
        private fun refusal(code: String) = JSONObject().put("status", "rejected").put("code", code)
    }
}
