package com.lavasec.app.vpn.chained

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The activity-collector publication fence: a retired owner whose `isCurrent` check passed must not
 * be able to publish after teardown cleared the slot and published `NONE`. The check and the
 * publication are one atomic step under the lock the teardown takes.
 */
class ChainedStatePublicationFenceTest {

    @Test
    fun `rotation identity comes from the installed owner atomically and retired owner cannot restore it`() {
        class Owner(val generation: Long)
        val predecessor = Owner(7L)
        val replacement = Owner(3L)
        val lock = ReentrantLock()
        val slot = ChainedOwnerSlot<Owner>().apply { install(predecessor) }
        val publications = mutableListOf<ChainedActivitySignal>()
        var previous = ChainedActivitySignal(false, ChainedRuntimeMode.NONE, 0L)
        val fence = ChainedStatePublicationFence(
            lock = lock,
            slot = slot,
            isClosed = { false },
            publish = { active, mode ->
                assertTrue(lock.isHeldByCurrentThread)
                val latched = if (mode == ChainedRuntimeMode.CHAINED) slot.currentOrNull()?.generation ?: 0L else 0L
                previous = nextChainedActivitySignal(previous, active, mode, latched)
                publications += previous
            },
        )
        assertTrue(fence.publishIfCurrent(predecessor, true, ChainedRuntimeMode.CHAINED))
        lock.withLock { slot.install(replacement) }
        assertFalse(fence.publishIfCurrent(predecessor, true, ChainedRuntimeMode.CHAINED))
        assertTrue(fence.publishIfCurrent(replacement, true, ChainedRuntimeMode.CHAINED))
        assertEquals(listOf(7L, 3L), publications.map { it.runningConfigurationGeneration })
        assertEquals(listOf(1L, 2L), publications.map { it.sequence })
        fence.publishComputed {
            slot.clear()
            false to ChainedRuntimeMode.NONE
        }
        assertFalse(fence.publishIfCurrent(replacement, true, ChainedRuntimeMode.CHAINED))
        assertEquals(0L, publications.last().runningConfigurationGeneration)
        assertEquals(3L, publications.last().sequence)
    }

    private fun recordingFence(
        lock: ReentrantLock,
        slot: ChainedOwnerSlot<String>,
        isClosed: () -> Boolean,
        published: MutableList<String>,
    ): ChainedStatePublicationFence<String> = ChainedStatePublicationFence(
        lock = lock,
        slot = slot,
        isClosed = isClosed,
        publish = { active, mode -> published += "$active/$mode" },
    )

    @Test
    fun `the installed owner publishes its current state`() {
        val lock = ReentrantLock()
        val slot = ChainedOwnerSlot<String>().apply { install("owner-a") }
        val published = mutableListOf<String>()
        val fence = recordingFence(lock, slot, { false }, published)

        fence.publishIfCurrent("owner-a", active = true, mode = ChainedRuntimeMode.CHAINED)

        assertEquals(listOf("true/CHAINED"), published)
    }

    @Test
    fun `a retired owner publishes nothing after teardown`() {
        val lock = ReentrantLock()
        val slot = ChainedOwnerSlot<String>().apply { install("owner-a") }
        val published = mutableListOf<String>()
        val fence = recordingFence(lock, slot, { false }, published)

        // Teardown clears the slot; the surface has already published NONE.
        lock.withLock { slot.clear() }

        // The descheduled collector resumes and tries to publish the retired owner's Established.
        fence.publishIfCurrent("owner-a", active = true, mode = ChainedRuntimeMode.CHAINED)

        assertTrue(published.isEmpty())
    }

    @Test
    fun `a closed runtime publishes nothing even for the installed owner`() {
        val lock = ReentrantLock()
        val slot = ChainedOwnerSlot<String>().apply { install("owner-a") }
        val published = mutableListOf<String>()
        var closed = false
        val fence = recordingFence(lock, slot, { closed }, published)

        closed = true
        fence.publishIfCurrent("owner-a", active = true, mode = ChainedRuntimeMode.CHAINED)

        assertTrue(published.isEmpty())
    }

    @Test
    fun `teardown cannot clear the slot between the identity check and the publication`() {
        val lock = ReentrantLock()
        val slot = ChainedOwnerSlot<String>().apply { install("owner-a") }
        val published = CopyOnWriteArrayList<String>()
        val publishing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fence = ChainedStatePublicationFence(
            lock = lock,
            slot = slot,
            isClosed = { false },
            publish = { active, mode ->
                published += "$active/$mode"
                publishing.countDown()
                release.await()
            },
        )

        // The collector passes the identity check and enters the publish while holding the lock.
        val collector = thread(name = "collector") {
            fence.publishIfCurrent("owner-a", active = true, mode = ChainedRuntimeMode.CHAINED)
        }
        assertTrue(publishing.await(2, TimeUnit.SECONDS))

        // Teardown must block on the lock: the slot cannot be cleared between check and publish.
        val cleared = CountDownLatch(1)
        val teardown = thread(name = "teardown") {
            lock.withLock { slot.clear() }
            cleared.countDown()
        }
        assertFalse(cleared.await(200, TimeUnit.MILLISECONDS))

        release.countDown()
        collector.join()
        teardown.join()

        assertEquals(listOf("true/CHAINED"), published)
        assertFalse(slot.isCurrent("owner-a"))
    }

    @Test
    fun `publishIfCurrent reports whether it published`() {
        val lock = ReentrantLock()
        val slot = ChainedOwnerSlot<String>().apply { install("owner-a") }
        val published = mutableListOf<String>()
        val fence = recordingFence(lock, slot, { false }, published)

        assertTrue(
            fence.publishIfCurrent("owner-a", active = true, mode = ChainedRuntimeMode.CHAINED),
        )
        assertFalse(
            fence.publishIfCurrent("stale", active = true, mode = ChainedRuntimeMode.CHAINED),
        )
        assertEquals(listOf("true/CHAINED"), published)
    }

    @Test
    fun `publishComputed publishes the computed mode`() {
        val lock = ReentrantLock()
        val slot = ChainedOwnerSlot<String>()
        val published = mutableListOf<String>()
        val fence = recordingFence(lock, slot, { false }, published)

        fence.publishComputed { true to ChainedRuntimeMode.DNS_ONLY }

        assertEquals(listOf("true/DNS_ONLY"), published)
    }

    @Test
    fun `a computed snapshot cannot overwrite teardown's NONE`() {
        val lock = ReentrantLock()
        val slot = ChainedOwnerSlot<String>().apply { install("owner-a") }
        val published = CopyOnWriteArrayList<String>()
        val computing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fence = ChainedStatePublicationFence(
            lock = lock,
            slot = slot,
            isClosed = { false },
            publish = { active, mode -> published += "$active/$mode" },
        )

        // The pause-expiry path snapshots the established owner and publishes under the lock.
        val expiry = thread(name = "expiry") {
            fence.publishComputed {
                computing.countDown()
                release.await()
                true to ChainedRuntimeMode.CHAINED
            }
        }
        assertTrue(computing.await(2, TimeUnit.SECONDS))

        // Teardown must block on the lock: it cannot clear the owner and publish NONE between the
        // snapshot and the publication, so the stale chained mode cannot overwrite it.
        val cleared = CountDownLatch(1)
        val teardown = thread(name = "teardown") {
            lock.withLock { slot.clear() }
            published += "false/${ChainedRuntimeMode.NONE}"
            cleared.countDown()
        }
        assertFalse(cleared.await(200, TimeUnit.MILLISECONDS))

        release.countDown()
        expiry.join()
        teardown.join()

        assertEquals(
            listOf("true/${ChainedRuntimeMode.CHAINED}", "false/${ChainedRuntimeMode.NONE}"),
            published,
        )
        assertFalse(slot.isCurrent("owner-a"))
    }

    @Test
    fun `publishIf publishes only while its predicate holds`() {
        val lock = ReentrantLock()
        val slot = ChainedOwnerSlot<String>()
        val published = mutableListOf<String>()
        val fence = recordingFence(lock, slot, { false }, published)

        // Predicate false (a newer install already owns the mode): nothing is published.
        fence.publishIf(predicate = { false }, active = false, mode = ChainedRuntimeMode.NONE)
        assertTrue(published.isEmpty())

        // Predicate true (this runtime is still the installed one): the clear is published.
        fence.publishIf(predicate = { true }, active = false, mode = ChainedRuntimeMode.NONE)
        assertEquals(listOf("false/NONE"), published)
    }

    @Test
    fun `publishIf evaluates its predicate and publishes under one lock`() {
        val lock = ReentrantLock()
        val slot = ChainedOwnerSlot<String>()
        val published = CopyOnWriteArrayList<String>()
        val checked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fence = ChainedStatePublicationFence(
            lock = lock,
            slot = slot,
            isClosed = { false },
            publish = { active, mode ->
                published += "$active/$mode"
                release.await()
            },
        )

        val completion = thread(name = "completion") {
            fence.publishIf(
                predicate = {
                    checked.countDown()
                    true
                },
                active = false,
                mode = ChainedRuntimeMode.NONE,
            )
        }
        assertTrue(checked.await(2, TimeUnit.SECONDS))

        // A newer install must wait for the lock: it cannot interleave between the predicate and
        // the publication.
        val installed = CountDownLatch(1)
        val install = thread(name = "install") {
            lock.withLock { published += "true/${ChainedRuntimeMode.CHAINED}" }
            installed.countDown()
        }
        assertFalse(installed.await(200, TimeUnit.MILLISECONDS))

        release.countDown()
        completion.join()
        install.join()

        assertEquals(
            listOf("false/${ChainedRuntimeMode.NONE}", "true/${ChainedRuntimeMode.CHAINED}"),
            published,
        )
    }

    @Test
    fun `onPublished runs only when the publication happens`() {
        val lock = ReentrantLock()
        val slot = ChainedOwnerSlot<String>().apply { install("owner-a") }
        val published = mutableListOf<String>()
        val effects = mutableListOf<String>()
        val fence = recordingFence(lock, slot, { false }, published)

        assertTrue(
            fence.publishIfCurrent("owner-a", active = true, mode = ChainedRuntimeMode.CHAINED) {
                effects += "owner-a"
            },
        )
        // A stale owner neither publishes activity nor runs the fenced side effect.
        assertFalse(
            fence.publishIfCurrent("stale", active = true, mode = ChainedRuntimeMode.CHAINED) {
                effects += "stale"
            },
        )
        assertEquals(listOf("owner-a"), effects)
    }

    @Test
    fun `a fenced side effect cannot be preempted by a replacement install`() {
        val lock = ReentrantLock()
        val slot = ChainedOwnerSlot<String>().apply { install("owner-a") }
        val effects = CopyOnWriteArrayList<String>()
        val inEffect = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fence = ChainedStatePublicationFence(
            lock = lock,
            slot = slot,
            isClosed = { false },
            publish = { _, _ -> },
        )

        // The collector publishes the owner's state and runs the health side effect under the lock;
        // the side effect blocks to model a descheduled update.
        val collector = thread(name = "collector") {
            fence.publishIfCurrent("owner-a", active = true, mode = ChainedRuntimeMode.CHAINED) {
                effects += "owner-a"
                inEffect.countDown()
                release.await()
            }
        }
        assertTrue(inEffect.await(2, TimeUnit.SECONDS))

        // A replacement install must wait: it cannot slip between the owner check and the health
        // update, so the stale owner's report was already applied to its own session.
        val installed = CountDownLatch(1)
        val install = thread(name = "install") {
            lock.withLock {
                slot.clear()
                slot.install("owner-b")
            }
            installed.countDown()
        }
        assertFalse(installed.await(200, TimeUnit.MILLISECONDS))

        release.countDown()
        collector.join()
        install.join()

        assertEquals(listOf("owner-a"), effects)
        assertTrue(slot.isCurrent("owner-b"))
    }
}
