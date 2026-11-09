package com.lavasec.app.reactnative

import com.lavasec.app.product.FilterPreparationProgress.Phase
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import com.lavasec.app.platform.FilterMutationPublishResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NativePreparationOwnerTest {
    @Test fun `generation rotation during admission cannot tag retired work with successor authority`() {
        var generation = 7L
        val host = NativeVisitHost(Any(), "host") { true }
        var rotate = true
        val owner = NativePreparationOwner({ host }, {
            if (rotate) { generation = 8L; rotate = false }; generation
        }, { true }, {}, {})
        assertNull(owner.begin("old-private-filter", "filters") { generation == 7L })
        assertNull(owner.projection(8L)); assertFalse(owner.presentedFor(8L))
        owner.close()
    }

    private class Fixture(clock: () -> Long = { 0L }) {
        val activity = Any()
        var alive = true
        var host: NativeVisitHost? = NativeVisitHost(activity, "original") { alive }
        var generation: Long? = 7L
        var granted = true
        var authorizationRevision = 0L
        var publishes = 0
        val announcements = mutableListOf<Boolean>()
        val owner = NativePreparationOwner({ host }, { generation }, { granted },
            { publishes++ }, { announcements += it }, clock)
        fun begin(replacing: NativePreparationOwner.Attempt? = null): NativePreparationOwner.Attempt {
            val revision = authorizationRevision
            return checkNotNull(owner.begin("filter", "filters", replacing) { granted && authorizationRevision == revision })
        }
    }

    @Test fun `a slow committed operation does not add a fresh missing renderer deadline`() = runTest {
        val f = Fixture { testScheduler.currentTime }; val attempt = f.begin()
        advanceTimeBy(15_000)
        f.owner.finishOperation(attempt, true)
        assertEquals(NativePreparationOwner.Decision.CANCEL, f.owner.awaitDecision(attempt))
        assertEquals(15_000L, testScheduler.currentTime)
        f.owner.release(attempt); assertFalse(f.owner.presented)
    }

    @Test fun `a measured slow commit retains its success acknowledgment budget`() = runTest {
        val f = Fixture { testScheduler.currentTime }; val attempt = f.begin()
        assertTrue(f.owner.command("preparation.enter", attempt.id))
        advanceTimeBy(15_000); f.owner.finishOperation(attempt, true)
        val decision = async { f.owner.awaitDecision(attempt) }; runCurrent()
        advanceTimeBy(1_200); runCurrent(); assertFalse(decision.isCompleted)
        assertTrue(f.owner.command("preparation.rendered", attempt.id))
        assertTrue(f.owner.command("preparation.presented", attempt.id))
        assertEquals(NativePreparationOwner.Decision.PRESENTED, decision.await())
        f.owner.release(attempt)
    }

    @Test fun `background time pauses the measured success deadline and exact resume can acknowledge`() = runTest {
        val f = Fixture { testScheduler.currentTime }; val attempt = f.begin()
        assertTrue(f.owner.command("preparation.enter", attempt.id))
        f.owner.finishOperation(attempt, true)
        val decision = async { f.owner.awaitDecision(attempt) }; runCurrent()
        advanceTimeBy(2_000); f.granted = false; f.owner.presentationChanged(); runCurrent()
        advanceTimeBy(60_000); runCurrent(); assertFalse(decision.isCompleted)
        assertFalse(f.owner.command("preparation.presented", attempt.id))
        f.granted = true; f.owner.presentationChanged(); runCurrent()
        advanceTimeBy(7_999); runCurrent(); assertFalse(decision.isCompleted)
        assertTrue(f.owner.command("preparation.rendered", attempt.id))
        assertTrue(f.owner.command("preparation.presented", attempt.id))
        assertEquals(NativePreparationOwner.Decision.PRESENTED, decision.await())
        f.owner.release(attempt)
    }

    @Test fun `measured success still times out after ten visible seconds across pauses`() = runTest {
        val f = Fixture { testScheduler.currentTime }; val attempt = f.begin()
        f.owner.command("preparation.enter", attempt.id); f.owner.finishOperation(attempt, true)
        val decision = async { f.owner.awaitDecision(attempt) }; runCurrent()
        advanceTimeBy(4_000); f.granted = false; f.owner.presentationChanged(); runCurrent()
        advanceTimeBy(20_000); f.granted = true; f.owner.presentationChanged(); runCurrent()
        advanceTimeBy(5_999); runCurrent(); assertFalse(decision.isCompleted)
        advanceTimeBy(1); runCurrent()
        assertEquals(NativePreparationOwner.Decision.CANCEL, decision.await()); f.owner.release(attempt)
    }

    @Test fun `close wakes measured success immediately while backgrounded`() = runTest {
        val f = Fixture { testScheduler.currentTime }; val attempt = f.begin()
        f.owner.command("preparation.enter", attempt.id); f.owner.finishOperation(attempt, true)
        val decision = async { f.owner.awaitDecision(attempt) }; runCurrent()
        f.granted = false; f.owner.presentationChanged(); runCurrent()
        f.owner.cancel(attempt.id); runCurrent()
        assertEquals(NativePreparationOwner.Decision.CANCEL, decision.await())
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun `a failed operation without a measured renderer has a bounded decision wait`() = runTest {
        val f = Fixture(); val attempt = f.begin()
        f.owner.finishOperation(attempt, false, retryable = true)
        val decision = async { f.owner.awaitDecision(attempt) }
        runCurrent(); advanceTimeBy(9_999); runCurrent(); assertFalse(decision.isCompleted)
        advanceTimeBy(1); runCurrent()
        assertEquals(NativePreparationOwner.Decision.CANCEL, decision.await())
        f.owner.release(attempt); assertFalse(f.owner.presented)
    }

    @Test fun `a measured recovery screen retains user choice past the missing renderer deadline`() = runTest {
        val f = Fixture(); val attempt = f.begin()
        f.owner.finishOperation(attempt, false, retryable = true)
        val decision = async { f.owner.awaitDecision(attempt) }
        runCurrent(); advanceTimeBy(100)
        assertFalse(f.owner.command("preparation.enter", "stale"))
        assertTrue(f.owner.command("preparation.enter", attempt.id))
        runCurrent(); advanceTimeBy(60_000); runCurrent(); assertFalse(decision.isCompleted)
        assertTrue(f.owner.command("preparation.retry", attempt.id))
        assertEquals(NativePreparationOwner.Decision.RETRY, decision.await())
        f.owner.release(attempt)
    }

    @Test fun `close releases a missing renderer immediately without waiting for its deadline`() = runTest {
        val f = Fixture(); val attempt = f.begin()
        f.owner.finishOperation(attempt, false)
        val decision = async { f.owner.awaitDecision(attempt) }; runCurrent()
        f.owner.cancel(attempt.id); runCurrent()
        assertEquals(NativePreparationOwner.Decision.CANCEL, decision.await())
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun `quarters cannot invent success and late phases cannot regress a terminal outcome`() {
        val f = Fixture(); val attempt = f.begin()
        assertEquals(0.0, f.owner.projection()!!.getDouble("progress"), 0.0)
        f.owner.advance(attempt, Phase.VERIFIED)
        f.owner.advance(attempt, Phase.COMPILING)
        assertEquals(.75, f.owner.projection()!!.getDouble("progress"), 0.0)
        assertEquals("preparing", f.owner.projection()!!.getString("state"))
        assertFalse(f.owner.command("preparation.presented", attempt.id))
        assertFalse(f.owner.command("preparation.rendered", attempt.id))
        f.owner.finishOperation(attempt, true)
        f.owner.advance(attempt, Phase.DOWNLOADING)
        f.owner.finishOperation(attempt, false)
        assertEquals("success", f.owner.projection()!!.getString("state"))
        assertEquals(1.0, f.owner.projection()!!.getDouble("progress"), 0.0)
    }

    @Test fun `success requires exact rendered acknowledgment and announces once`() = runTest {
        val f = Fixture(); val attempt = f.begin()
        assertFalse(f.owner.command("preparation.announce", attempt.id))
        f.owner.finishOperation(attempt, true)
        assertFalse(f.owner.command("preparation.presented", attempt.id))
        assertFalse(f.owner.command("preparation.rendered", "obsolete"))
        assertTrue(f.owner.command("preparation.rendered", attempt.id))
        repeat(2) { assertTrue(f.owner.command("preparation.announce", attempt.id)) }
        assertEquals(listOf(true), f.announcements)
        assertTrue(f.owner.command("preparation.presented", attempt.id))
        assertEquals(NativePreparationOwner.Decision.PRESENTED, f.owner.awaitDecision(attempt))
        f.owner.release(attempt)
        assertFalse(f.owner.presented)
    }

    @Test fun `read free close cancels exact native job despite revoked grants`() = runTest {
        val f = Fixture(); val attempt = f.begin(); val worker = Job()
        f.owner.attach(attempt, worker); f.granted = false
        assertNull(f.owner.projection()); assertTrue(f.owner.presented)
        assertFalse(f.owner.isCurrent(attempt))
        assertFalse(f.owner.command("preparation.retry", attempt.id))
        assertTrue(f.owner.cancel(attempt.id))
        assertTrue(worker.isCancelled)
        assertEquals(NativePreparationOwner.Decision.CANCEL, attempt.decision.await())
        assertFalse(f.owner.presented)
    }

    @Test fun `retry replaces one failed attempt without dropping its presentation reservation`() = runTest {
        val f = Fixture(); val old = f.begin()
        assertNull(f.owner.begin("filter", "filters") { true })
        assertFalse(f.owner.command("preparation.retry", old.id))
        f.owner.finishOperation(old, false, retryable = true)
        assertTrue(f.owner.command("preparation.retry", old.id))
        assertTrue(f.owner.presented)
        val next = f.begin(old)
        assertNotEquals(old.id, next.id); assertTrue(f.owner.presented)
        assertFalse(f.owner.cancel(old.id))
        assertFalse(f.owner.command("preparation.retry", old.id))
        f.owner.release(old)
        assertTrue(f.owner.isCurrent(next))
        assertEquals(0.0, f.owner.projection()!!.getDouble("progress"), 0.0)
        f.owner.finishOperation(old, true)
        assertEquals("preparing", f.owner.projection()!!.getString("state"))
    }

    @Test fun `recovery cannot act from another Activity or credential generation`() {
        val f = Fixture(); val old = f.begin(); val job = Job(); f.owner.attach(old, job)
        f.host = NativeVisitHost(Any(), "original") { true }
        assertNull(f.owner.projection()); assertFalse(f.owner.isCurrent(old))
        f.owner.authorityChanged()
        assertTrue(job.isCancelled)
        val next = f.begin()
        f.owner.retireHost(f.activity, "original")
        assertTrue(f.owner.isCurrent(next))
        f.generation = 8
        f.owner.authorityChanged(); assertFalse(f.owner.presented)
        assertFalse(f.owner.command("preparation.enter", next.id))
    }

    @Test fun `temporary missing host retains reservation but never private reads or writes`() {
        val f = Fixture(); val attempt = f.begin(); val host = f.host
        f.host = null
        assertTrue(f.owner.presented); assertNull(f.owner.projection()); assertFalse(f.owner.isCurrent(attempt))
        f.host = host
        assertTrue(f.owner.isCurrent(attempt)); assertNotNull(f.owner.projection())
        f.alive = false; f.owner.authorityChanged()
        assertFalse(f.owner.presented)
    }

    @Test fun `a captured older snapshot cannot adopt the current preparation generation`() {
        val f = Fixture(); val attempt = f.begin()
        assertNull(f.owner.projection(6L)); assertFalse(f.owner.presentedFor(6L))
        assertNull(f.owner.projection(null)); assertFalse(f.owner.presentedFor(null))
        assertNotNull(f.owner.projection(7L)); assertTrue(f.owner.presentedFor(7L))
        assertTrue(f.owner.isCurrent(attempt))
    }

    @Test fun `closed owner refuses new attempts and cancels a late attached worker`() {
        val f = Fixture(); val attempt = f.begin()
        f.owner.close()
        val worker = Job(); f.owner.attach(attempt, worker)
        assertTrue(worker.isCancelled)
        assertNull(f.owner.begin("filter", "filters") { true })
    }

    @Test fun `a resumed grant cannot authorize an old operation publication`() = runTest {
        val f = Fixture(); val attempt = f.begin()
        f.granted = false; f.authorizationRevision++; f.granted = true
        assertFalse(f.owner.isCurrent(attempt))
        val result = f.owner.publish(attempt) { error("Old operation borrowed a resumed grant") }
        assertTrue(result is FilterMutationPublishResult.Failed)
        f.owner.cancel(attempt.id)
    }

    @Test fun `Close before final publication refuses it without invoking storage`() = runTest {
        val f = Fixture(); val attempt = f.begin()
        f.owner.cancel(attempt.id)
        assertTrue(f.owner.publish(attempt) { error("Retired operation reached storage") } is FilterMutationPublishResult.Failed)
    }

    @Test fun `Close during admitted publication preserves native result settlement and cannot affect a successor`() = runTest {
        val f = Fixture(); val attempt = f.begin(); val job = Job()
        f.owner.attach(attempt, job)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val publishing = async {
            f.owner.publish(attempt) {
                entered.complete(Unit); release.await()
                FilterMutationPublishResult.Committed
            }
        }
        entered.await(); f.owner.cancel(attempt.id)
        assertFalse(job.isCancelled)
        val lateAttachment = Job(); f.owner.attach(attempt, lateAttachment)
        assertFalse(lateAttachment.isCancelled)
        val next = f.begin()
        release.complete(Unit)
        assertSame(FilterMutationPublishResult.Committed, publishing.await())
        f.owner.finishOperation(attempt, true); f.owner.release(attempt)
        assertTrue(f.owner.isCurrent(next)); assertEquals("preparing", f.owner.projection()!!.getString("state"))
        job.complete(); lateAttachment.complete()
    }
}
