package com.lavasec.app.reactnative

/** Module-local custom-entry custody. No retired-host tombstones or raw draft fields are kept.
 * Bridge creation and Activity retirement run on Main. The lock also serializes module close.
 * Callbacks run outside this lock and must fence their original record by identity.
 * Retirement does not consult private read or authorization. */
internal class NativeCustomEntryHostOwner(
    private val currentGeneration: () -> Long?,
    private val currentHost: () -> NativeVisitHost?,
) {
    private class Visit(val id: String, val generation: Long, val host: NativeVisitHost, val retire: () -> Unit)
    private val lock = Any()
    private val visits = linkedMapOf<String, Visit>()
    private var closed = false

    /** A late registration tears down only its own already-created token, never a new host. */
    fun register(id: String, generation: Long, host: NativeVisitHost, retire: () -> Unit): Boolean {
        val visit = Visit(id, generation, host, retire)
        val retained = synchronized(lock) {
            // Tokens are minted natively as fresh UUIDs. A collision cannot replace existing custody.
            if (visits.containsKey(id)) return false
            if (closed || currentGeneration() != generation || !host.retains(currentHost())) false
            else { visits[id] = visit; true }
        }
        if (!retained) visit.retire()
        return retained
    }

    /** Capture one registered visit before a suspended authorization; never adopt its successor. */
    fun captureAdmission(id: String, generation: Long, host: NativeVisitHost): (() -> Boolean)? {
        val original = synchronized(lock) {
            visits[id]?.takeIf { !closed && it.generation == generation && it.host.sameHost(host) &&
                currentGeneration() == generation && it.host.matches(currentHost()) }
        } ?: return null
        return {
            synchronized(lock) {
                !closed && visits[id] === original && currentGeneration() == generation &&
                    original.host.matches(currentHost())
            }
        }
    }

    /** Existing read-free token/G route cleanup, with no successor snapshot or host lookup. */
    fun retire(id: String, generation: Long): Boolean {
        val visit = synchronized(lock) {
            visits[id]?.takeIf { it.generation == generation }?.also { visits.remove(id) }
        } ?: return false
        visit.retire()
        return true
    }

    /** Uses the original Activity identity and host nonce even after the atomic host is replaced. */
    fun retireHost(owner: Any, nativeHostID: String) {
        val retiring = synchronized(lock) {
            visits.values.filter { it.host.owner === owner && it.host.nativeHostID == nativeHostID }
                .also { removed -> removed.forEach { visits.remove(it.id) } }
        }
        retiring.forEach { it.retire() }
    }

    /** Module disposal is independent from React unmount; a late result cannot reopen this module. */
    fun close() {
        val retiring = synchronized(lock) {
            closed = true
            visits.values.toList().also { visits.clear() }
        }
        retiring.forEach { it.retire() }
    }
}
