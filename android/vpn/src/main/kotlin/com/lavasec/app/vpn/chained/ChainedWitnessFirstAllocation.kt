package com.lavasec.app.vpn.chained

/**
 * Persists the active-session witness BEFORE allocating the chained engine.
 *
 * The ~50 MB JNI session is the one allocation whose failure mode is exactly the event the
 * witness exists to detect: a low-memory kill during construction. A killed process cannot write
 * a post-mortem, so if the witness is only recorded after the engine exists, allocation-time kills
 * are unattributable and a deterministic allocation-time crash loop never advances the exclusion
 * breaker. [allocate] therefore runs only after [markWitness] reports the witness durable, and
 * when the witness cannot be persisted the engine is never allocated (the caller falls back to
 * DNS-only).
 *
 * If [allocate] throws, [retireWitness] clears the witness before the failure propagates, so a
 * failed allocation cannot leave a phantom session for the next eligibility read to correlate.
 *
 * Pure and dependency-free so the ordering is executable on the JVM harness with fakes.
 */
internal object ChainedWitnessFirstAllocation {

    /**
     * @param markWitness records the durable witness; `false` means it could not be persisted.
     * @param retireWitness clears a witness recorded by [markWitness] when [allocate] fails.
     * @param allocate constructs the engine.
     * @return the allocated value, or `null` when the witness could not be persisted (the caller
     *   must not allocate and must fall back to DNS-only).
     */
    fun <T> run(
        markWitness: () -> Boolean,
        retireWitness: () -> Unit,
        allocate: () -> T,
    ): T? {
        if (!markWitness()) return null
        return try {
            allocate()
        } catch (failure: Throwable) {
            runCatching { retireWitness() }
            throw failure
        }
    }
}

/**
 * Settles a chained owner's build window before it retires its witness and releases its keys.
 *
 * The mirror of the iOS credential lifetime, where the engine is built INLINE on the engine queue
 * and `ChainedSessionCredentials.scrubSecrets()` runs in a `defer` before `makeSession` returns:
 * the witness and the key material outlive the construction, never the other way around. Android's
 * off-queue `sessionBuilds` scope (deviation 11) decouples the two, so an owner closed while a
 * build is inside the synchronous, non-cancellable `sessionFactory.create()` would otherwise
 * retire the witness and zeroize the key arrays while that allocation still runs. [awaitBuilds]
 * closes the gap: only after every started build has returned does [retireWitness] clear the
 * session's kill-attribution record and [releaseSecrets] zeroize the key material, so a low-memory
 * kill from the build's remaining work is still attributable and the keys are not mutated under a
 * live build.
 *
 * [releaseSecrets] runs even when waiting or retiring throws: the key material must always be
 * zeroized. Pure over its callbacks so the ordering is executable on the JVM harness.
 */
internal object ChainedBuildSettlement {
    suspend fun run(
        awaitBuilds: suspend () -> Unit,
        retireWitness: () -> Unit,
        releaseSecrets: () -> Unit,
    ) {
        runCatching { awaitBuilds() }
        runCatching { retireWitness() }
        releaseSecrets()
    }
}
