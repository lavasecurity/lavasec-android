package com.lavasec.app.reactnative

import android.os.SystemClock
import com.lavasec.app.product.FilterPreparationProgress
import com.lavasec.app.platform.FilterMutationFailure
import com.lavasec.app.platform.FilterMutationPublishResult
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** An admitted native operation owns progress, recovery and success acknowledgement. */
internal class NativePreparationOwner(
    private val currentHost: () -> NativeVisitHost?,
    private val currentGeneration: () -> Long?,
    private val canRead: () -> Boolean,
    private val publish: () -> Unit,
    private val announce: (Boolean) -> Unit,
    private val uptimeMillis: () -> Long = SystemClock::uptimeMillis,
) : AutoCloseable {
    enum class Decision { PRESENTED, RETRY, EDIT, KEEP_CURRENT, CANCEL }
    enum class State { PREPARING, SUCCESS, FAILED }
    internal data class PresentationClock(val active: Boolean, val elapsed: Long, val changedAt: Long)
    class Attempt internal constructor(
        val filterId: String,
        val origin: String,
        val host: NativeVisitHost,
        val generation: Long,
        internal val canPublish: () -> Boolean,
        internal val startedAt: Long,
    ) {
        val id: String = UUID.randomUUID().toString()
        internal var state = State.PREPARING
        internal var phase = FilterPreparationProgress.Phase.DOWNLOADING
        internal var message = ""
        internal var failureReason: String? = null
        internal var failureSource: String? = null
        internal var retryable = false
        internal var rendered = false
        internal var announced = false
        internal var retired = false
        internal var worker: Job? = null
        internal var publishing = false
        val decision = CompletableDeferred<Decision>()
        internal val rendererEntered = CompletableDeferred<Unit>()
        internal val presentation = MutableStateFlow(PresentationClock(true, 0L, startedAt))
    }

    private val lock = Any()
    private var current: Attempt? = null
    private var closed = false

    fun begin(filterId: String, origin: String, replacing: Attempt? = null, canPublish: () -> Boolean): Attempt? {
        val attempt = synchronized(lock) {
            if (closed || current !== replacing || !canRead()) return null
            if (replacing != null && (!retained(replacing) || replacing.state != State.FAILED ||
                    !replacing.retryable || !replacing.decision.isCompleted)) return null
            val host = currentHost() ?: return null
            val generation = currentGeneration() ?: return null
            if (!canPublish() || generation != currentGeneration() || !host.matches(currentHost())) return null
            replacing?.retired = true
            Attempt(filterId, origin, host, generation, canPublish, uptimeMillis()).also { current = it }
        }
        publish()
        return attempt
    }

    private fun retained(attempt: Attempt): Boolean = !closed && current === attempt && !attempt.retired &&
        attempt.generation == currentGeneration() && attempt.host.retains(currentHost())

    fun isCurrent(attempt: Attempt): Boolean = synchronized(lock) {
        retained(attempt) && attempt.host.matches(currentHost()) && attempt.canPublish()
    }

    fun attach(attempt: Attempt, worker: Job) {
        synchronized(lock) {
            if (!retained(attempt)) { if (!attempt.publishing) worker.cancel() }
            else attempt.worker = worker
        }
    }

    /** Close cancels preparation, but an admitted atomic write must settle its real
     * result in the library owner. The durable store still checks isCurrent at its
     * final write admission, so Close before that boundary refuses publication. */
    suspend fun publish(
        attempt: Attempt, publication: suspend () -> FilterMutationPublishResult,
    ): FilterMutationPublishResult {
        val admitted = synchronized(lock) {
            if (!isCurrent(attempt) || attempt.publishing || attempt.state != State.PREPARING) false
            else { attempt.publishing = true; true }
        }
        return if (admitted) publication()
            else FilterMutationPublishResult.Failed(FilterMutationFailure.COMMIT_FAILED)
    }

    fun advance(attempt: Attempt, phase: FilterPreparationProgress.Phase) {
        val changed = synchronized(lock) {
            if (!retained(attempt) || attempt.state != State.PREPARING || phase.ordinal <= attempt.phase.ordinal) false
            else { attempt.phase = phase; true }
        }
        if (changed) publish()
    }

    fun finishOperation(attempt: Attempt, committed: Boolean, message: String = "", retryable: Boolean = false,
        failureReason: String? = null, failureSource: String? = null) {
        val changed = synchronized(lock) {
            if (!retained(attempt) || attempt.state != State.PREPARING) false
            else {
                attempt.worker = null
                attempt.state = if (committed) State.SUCCESS else State.FAILED
                attempt.message = message
                attempt.failureReason = failureReason
                attempt.failureSource = failureSource
                attempt.retryable = retryable
                true
            }
        }
        if (changed) publish()
    }

    /** Public reservation prevents the review route from closing before native success is shown. */
    val presented: Boolean get() = presentedFor(currentGeneration())
    fun presentedFor(generation: Long?): Boolean = synchronized(lock) {
        current?.let { retained(it) && it.generation == generation } == true
    }
    val currentId: String? get() = synchronized(lock) { current?.takeIf(::retained)?.id }

    fun projection(expectedGeneration: Long? = currentGeneration()): JSONObject? = synchronized(lock) {
        val attempt = current ?: return null
        updatePresentation(attempt)
        if (!retained(attempt) || attempt.generation != expectedGeneration ||
            !attempt.host.matches(currentHost()) || !canRead()) return null
        JSONObject().put("id", attempt.id).put("filterId", attempt.filterId).put("origin", attempt.origin)
            .put("state", attempt.state.name.lowercase())
            .put("progress", if (attempt.state == State.SUCCESS) 1.0 else attempt.phase.progress)
            .put("title", when (attempt.state) {
                State.SUCCESS -> "Success"
                State.FAILED -> "We couldn't update your filter"
                State.PREPARING -> attempt.phase.title
            }).put("message", attempt.message).put("busy", attempt.decision.isCompleted)
            .put("failureReason", attempt.failureReason ?: JSONObject.NULL)
            .put("failureSource", attempt.failureSource ?: JSONObject.NULL)
            .put("recovery", JSONObject().put("retry", attempt.state == State.FAILED && attempt.retryable)
                .put("editReturn", attempt.state == State.FAILED).put("keepCurrent", attempt.state == State.FAILED)
                .put("upgrade", JSONObject.NULL))
    }

    /** Cancellation is read-free. It can retire only the supplied opaque attempt. */
    fun cancel(id: String): Boolean {
        val attempt = synchronized(lock) { current?.takeIf { it.id == id } } ?: return false
        return retire(attempt, Decision.CANCEL)
    }

    /** Renderer admission has one deadline from attempt creation, including native work.
     * A slow commit must not acquire another ten seconds of unrendered UI waiting.
     * Once measured, recovery belongs to the user and success keeps its animation budget. */
    suspend fun awaitDecision(attempt: Attempt): Decision {
        if (attempt.decision.isCompleted) return attempt.decision.await()
        if (!attempt.rendererEntered.isCompleted) {
            val remaining = (10_000L - (uptimeMillis() - attempt.startedAt)).coerceIn(0L, 10_000L)
            val reachedOwner = if (remaining == 0L) false else withTimeoutOrNull(remaining) {
                select {
                    attempt.decision.onAwait { true }
                    attempt.rendererEntered.onAwait { true }
                }
            } ?: false
            if (!reachedOwner) return Decision.CANCEL
        }
        if (synchronized(lock) { attempt.state == State.FAILED }) return attempt.decision.await()
        return awaitVisibleSuccess(attempt)
    }

    /** The measured success budget counts readable foreground time only. Lifecycle
     * transitions wake the waiter; background retention needs no polling timer. */
    private suspend fun awaitVisibleSuccess(attempt: Attempt): Decision {
        val started = synchronized(lock) { updatePresentation(attempt); activeMillis(attempt.presentation.value) }
        while (true) {
            if (attempt.decision.isCompleted) return attempt.decision.await()
            val clock = synchronized(lock) { updatePresentation(attempt); attempt.presentation.value }
            val remaining = (10_000L - (activeMillis(clock) - started)).coerceIn(0L, 10_000L)
            if (remaining == 0L) return Decision.CANCEL
            val decision = coroutineScope {
                val changed = async { attempt.presentation.first { it != clock } }
                try {
                    suspend fun wait(): Decision? = select {
                        attempt.decision.onAwait { it }
                        changed.onAwait { null }
                    }
                    if (clock.active) withTimeoutOrNull(remaining) { wait() } else wait()
                } finally { changed.cancel() }
            }
            if (decision != null) return decision
        }
    }

    private fun activeMillis(clock: PresentationClock, now: Long = uptimeMillis()): Long =
        clock.elapsed + if (clock.active) (now - clock.changedAt).coerceAtLeast(0L) else 0L

    private fun updatePresentation(attempt: Attempt) {
        val active = retained(attempt) && attempt.host.matches(currentHost()) && canRead()
        val previous = attempt.presentation.value
        if (active != previous.active) {
            val now = uptimeMillis()
            attempt.presentation.value = PresentationClock(active, activeMillis(previous, now), now)
        }
    }

    fun presentationChanged() = synchronized(lock) { current?.let(::updatePresentation); Unit }

    fun command(type: String, id: String): Boolean {
        var announcement: Boolean? = null
        val accepted = synchronized(lock) {
            val attempt = current ?: return false
            if (attempt.id != id || !retained(attempt) || !attempt.host.matches(currentHost()) || !canRead()) return false
            when (type) {
                "preparation.enter" -> { attempt.rendererEntered.complete(Unit); true }
                "preparation.rendered" -> if (attempt.state == State.SUCCESS) { attempt.rendered = true; true } else false
                "preparation.presented" -> attempt.state == State.SUCCESS && attempt.rendered &&
                    attempt.decision.complete(Decision.PRESENTED)
                "preparation.announce" -> if (attempt.state == State.PREPARING) false else {
                    if (!attempt.announced) { attempt.announced = true; announcement = attempt.state == State.SUCCESS }
                    true
                }
                "preparation.retry" -> attempt.state == State.FAILED && attempt.retryable &&
                    attempt.decision.complete(Decision.RETRY)
                "preparation.editReturn" -> attempt.state == State.FAILED && attempt.decision.complete(Decision.EDIT)
                "preparation.keepCurrent" -> attempt.state == State.FAILED && attempt.decision.complete(Decision.KEEP_CURRENT)
                else -> false
            }
        }
        announcement?.let(announce)
        return accepted
    }

    fun release(attempt: Attempt) = retire(attempt, Decision.CANCEL)

    private fun retire(attempt: Attempt, decision: Decision): Boolean {
        val worker = synchronized(lock) {
            if (current !== attempt) return false
            current = null
            attempt.retired = true
            attempt.decision.complete(decision)
            attempt.worker.takeUnless { attempt.publishing }.also { attempt.worker = null }
        }
        worker?.cancel()
        publish()
        return true
    }

    fun retireHost(owner: Any, nativeHostID: String) {
        val attempt = synchronized(lock) {
            current?.takeIf { it.host.owner === owner && it.host.nativeHostID == nativeHostID }
        } ?: return
        release(attempt)
    }

    fun authorityChanged() {
        presentationChanged()
        val obsolete = synchronized(lock) { current?.takeIf { !retained(it) } } ?: return
        release(obsolete)
    }

    override fun close() {
        val attempt = synchronized(lock) { closed = true; current }
        attempt?.let(::release)
    }
}
