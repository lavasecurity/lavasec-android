package com.lavasec.app.reactnative

/** Native-only custody for one standalone Domain History review. The original host is
 * registered before authentication suspends. Retirement has no read/authentication port;
 * an attached callback schedules exact draft cleanup in the surviving product scope.
 * Removed visits are not kept as tombstones. A suspended caller retains only its own handle.
 */
internal class NativeDomainReviewHostOwner(
    private val currentGeneration: () -> Long?,
    private val currentHost: () -> NativeVisitHost?,
) {
    internal class Visit internal constructor(
        val token: String, val generation: Long, val host: NativeVisitHost,
    ) {
        internal var retired = false
        internal var completed = false
        internal var attached = false
        internal var callback: (() -> Unit)? = null
    }
    private val lock = Any()
    private val visits = linkedMapOf<String, Visit>()
    private var closed = false

    fun begin(token: String, generation: Long, host: NativeVisitHost): Visit? {
        if (currentGeneration() != generation || !host.matches(currentHost())) return null
        val visit = synchronized(lock) {
            if (closed || visits.containsKey(token)) return null
            Visit(token, generation, host).also { visits[token] = it }
        }
        // Host destruction can precede acquisition of the registry lock.
        if (!isCurrent(visit)) { retire(visit); return null }
        return visit
    }

    fun isCurrent(visit: Visit): Boolean {
        val retained = synchronized(lock) {
            !closed && !visit.retired && !visit.completed && visits[visit.token] === visit
        }
        return retained && currentGeneration() == visit.generation && visit.host.matches(currentHost())
    }

    /** A late staging result still disposes its own lease after host/module retirement. */
    fun attach(visit: Visit, retireDraft: () -> Unit): Boolean {
        var immediate: (() -> Unit)? = null
        val retained = synchronized(lock) {
            if (visit.attached || visit.completed) return false
            visit.attached = true
            if (closed || visit.retired || visits[visit.token] !== visit) {
                visit.retired = true
                immediate = retireDraft
                false
            } else { visit.callback = retireDraft; true }
        }
        immediate?.invoke()
        return retained
    }

    private fun removeLocked(visit: Visit): (() -> Unit)? {
        if (visits[visit.token] === visit) visits.remove(visit.token)
        visit.retired = true
        return visit.callback.also { visit.callback = null }
    }

    /** Exact native handle; a same-token or same-filter successor is not adopted. */
    fun retire(visit: Visit): Boolean {
        val callback = synchronized(lock) {
            if (visit.retired || visit.completed) return false
            removeLocked(visit)
        }
        callback?.invoke()
        return true
    }

    /** Successful Apply releases custody without calling any draft-discard callback. */
    fun release(visit: Visit): Boolean = synchronized(lock) {
        if (visit.retired || visit.completed || visits[visit.token] !== visit) return false
        visits.remove(visit.token)
        visit.completed = true
        visit.callback = null
        true
    }

    fun retireHost(owner: Any, nativeHostID: String) {
        val callbacks = synchronized(lock) {
            visits.values.filter { it.host.owner === owner && it.host.nativeHostID == nativeHostID }
                .mapNotNull { removeLocked(it) }
        }
        callbacks.forEach { it() }
    }

    fun close() {
        val callbacks = synchronized(lock) {
            closed = true
            visits.values.toList().mapNotNull { removeLocked(it) }
        }
        callbacks.forEach { it() }
    }
}
