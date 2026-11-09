package com.lavasec.app.foundation.chained

/**
 * Compares the rotation an installed chained owner latched with the current saved authority.
 *
 * This is computed from two current identities, never a tracked restart obligation. A process
 * restart, a failed reload, removal, and a successful replacement cannot leave a flag uncleared.
 * Generation identifies the whole rotation, including its endpoint and keys; routing selection
 * fingerprints deliberately do not distinguish those changes.
 */
object ChainedUpstreamRotationFreshness {
    sealed interface StoredRotation {
        data class Present(val generation: Long) : StoredRotation {
            init { require(generation > 0L) { "stored chained rotation generation must be positive" } }
        }

        /** The authoritative store confirms that no rotation remains. */
        data object Absent : StoredRotation

        /** A locked, corrupt, or otherwise unreadable store establishes no change. */
        data object Unreadable : StoredRotation
    }

    sealed interface Verdict {
        data object NoChainedSession : Verdict
        data object Current : Verdict
        data class AwaitingRestart(val latched: Long, val stored: Long) : Verdict
        data class RunningRemovedRotation(val latched: Long) : Verdict
        data object Unknown : Verdict

        /** Silent when current, absent from the runtime, or unable to establish a change. */
        val deservesSurfacing: Boolean
            get() = this is AwaitingRestart || this is RunningRemovedRotation
    }

    /**
     * Zero means no live chained rotation or an older publisher that cannot identify it. These
     * identities are compared for inequality, never ordering: a restored rotation may be smaller.
     * Consumers pass zero whenever their actual installed activity signal is not chained-active.
     */
    fun verdict(runningGeneration: Long, storedRotation: StoredRotation): Verdict {
        if (runningGeneration <= 0L) return Verdict.NoChainedSession
        return when (storedRotation) {
            StoredRotation.Unreadable -> Verdict.Unknown
            StoredRotation.Absent -> Verdict.RunningRemovedRotation(runningGeneration)
            is StoredRotation.Present -> if (storedRotation.generation == runningGeneration) {
                Verdict.Current
            } else {
                Verdict.AwaitingRestart(runningGeneration, storedRotation.generation)
            }
        }
    }
}
