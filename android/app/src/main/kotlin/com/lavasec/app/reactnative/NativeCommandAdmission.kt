package com.lavasec.app.reactnative

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Slow publication must not keep Stop/Pause from reaching the protection owner. */
internal class NativeCommandAdmission {
    private val mutations = Mutex()
    private val protection = Mutex()
    private val queries = Mutex()

    suspend fun <T : Any> run(type: String, operation: suspend () -> T): T? {
        // Shared pages can overlap polls with a newly settled search. Serialize the
        // read owners' mutable lenses without refusing that current search as busy
        // or making a suspended configuration mutation hold the page's reads.
        if (type.endsWith(".query")) return queries.withLock { operation() }
        val lane = if (type == "protection.toggle" || type == "protection.pause") protection else mutations
        if (!lane.tryLock()) return null
        return try { operation() } finally { lane.unlock() }
    }

    /** Recovery waits for foreground mutations, so it cannot remove an import still opening. */
    suspend fun recover(operation: suspend () -> Unit) = mutations.withLock { operation() }
}
