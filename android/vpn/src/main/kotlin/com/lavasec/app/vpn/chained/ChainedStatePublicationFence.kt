package com.lavasec.app.vpn.chained

import java.util.concurrent.locks.Lock
import kotlin.concurrent.withLock

/**
 * Publishes a chained owner's engine state behind an identity fence that is atomic with the
 * publication.
 *
 * Cancelling a retired owner's state collector does not wait for an in-flight emission, so the
 * collector can pass an `isCurrent` check and then be descheduled while a stop/reconnect/DNS-only
 * fallback clears the slot and publishes `NONE`. If the check released the lock before publishing,
 * the retired owner would republish `chained` after teardown, and the process bridge could report
 * `active = true` with no later event to correct it. This fence holds the SAME lock the teardown
 * takes to clear the slot across both the check and the publication, so a retired owner can never
 * publish after teardown.
 *
 * The owner slot and the clock-free predicates keep this pure and JVM-testable without the engine's
 * owner type.
 */
internal class ChainedStatePublicationFence<T : Any>(
    private val lock: Lock,
    private val slot: ChainedOwnerSlot<T>,
    private val isClosed: () -> Boolean,
    private val publish: (Boolean, ChainedRuntimeMode) -> Unit,
) {

    /**
     * Publishes [active]/[mode] for [owner] only while [owner] is still the installed owner of a
     * non-closed runtime, with the identity check and the publication performed under [lock].
     *
     * [onPublished] runs inside the same critical section, so a side effect that must belong to the
     * installed owner — reporting that owner's session health — cannot be preempted by a replacement
     * install between the check and the side effect.
     *
     * @return `true` when the publication happened.
     */
    fun publishIfCurrent(
        owner: T,
        active: Boolean,
        mode: ChainedRuntimeMode,
        onPublished: () -> Unit = {},
    ): Boolean =
        lock.withLock {
            if (!isClosed() && slot.isCurrent(owner)) {
                publish(active, mode)
                onPublished()
                true
            } else {
                false
            }
        }

    /**
     * Publishes the mode computed by [current] while [lock] is held, so the snapshot and the
     * publication are one critical section.
     *
     * The mode-derivation path reads the runtime (installed owner / DNS-only runtime / neither)
     * and can run on the pause-expiry coroutine concurrently with Stop/Reload. If it released the
     * lock between the snapshot and the publication, teardown could clear the owner and publish
     * [ChainedRuntimeMode.NONE] in that window, and the stale `active = true`/CHAINED would
     * overwrite it — the bridge would then permanently report chained traffic after protection
     * stopped. Holding [lock] across both keeps teardown's `NONE` the last word.
     */
    fun publishComputed(current: () -> Pair<Boolean, ChainedRuntimeMode>) {
        lock.withLock {
            val (active, mode) = current()
            publish(active, mode)
        }
    }

    /**
     * Publishes [active]/[mode] only while [predicate] holds, evaluated under [lock].
     *
     * Used by the DNS runtime's completion to clear a stale installed-mode signal only while that
     * runtime is still the installed one: a newer install's publication wins, and a completion
     * that loses the race does not clobber it.
     */
    fun publishIf(predicate: () -> Boolean, active: Boolean, mode: ChainedRuntimeMode) {
        lock.withLock {
            if (predicate()) publish(active, mode)
        }
    }
}
