package com.lavasec.app.vpn

import com.lavasec.app.platform.ProtectionFailure
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BoundedTunRuntimeLifecycleTest {
    @Test
    fun `concurrent close has one owner and joins every child`() = runTest {
        val fixture = ReplacementFixture(this)
        fixture.start()
        fixture.enqueue(1)
        runCurrent()
        assertEquals(10, fixture.runtime.jobTopologyForTest().childCount)

        coroutineScope {
            repeat(16) {
                launch { fixture.runtime.closeAndJoin() }
            }
        }

        assertEquals(1, fixture.tun.closeCalls.get())
        assertEquals(1, fixture.initialEngine.closeCalls)
        assertEquals(0, fixture.runtime.snapshotForTest().liveRecords)
        assertEquals(false, fixture.runtime.snapshotForTest().readerPermitHeld)
        assertEquals(0, fixture.runtime.jobTopologyForTest().childCount)
    }

    @Test
    fun `close wakes blocked read resolution and writable wait without events`() = runTest {
        val resolving = ReplacementFixture(this)
        resolving.start()
        resolving.enqueue(1)
        runCurrent()
        assertEquals("Resolving", resolving.runtime.snapshotForTest().states[TunQueryId(1)])
        resolving.runtime.closeAndJoin()

        val writable = ReplacementFixture(this)
        writable.mode(1, PlanMode.Immediate)
        writable.start()
        writable.enqueue(1)
        runCurrent()
        assertEquals(1, writable.tun.writableCalls.get())
        writable.runtime.closeAndJoin()

        listOf(resolving, writable).forEach { fixture ->
            assertTrue(fixture.observer.failures.isEmpty())
            assertTrue(fixture.observer.health.isEmpty())
            assertEquals(0, fixture.runtime.snapshotForTest().liveRecords)
            assertEquals(1, fixture.tun.closeCalls.get())
        }
    }

    @Test
    fun `close after committed write waits for evidence and releases record`() = runTest {
        val fixture = ReplacementFixture(this)
        fixture.mode(1, PlanMode.Immediate)
        val closeEntered = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        fixture.observer.onEvidence = {
            Thread {
                try {
                    fixture.runtime.requestClose()
                    closeEntered.countDown()
                    runBlocking { fixture.runtime.join() }
                } finally {
                    closeReturned.countDown()
                }
            }.start()
            closeEntered.await()
            assertEquals(1L, closeReturned.count)
        }
        fixture.scriptWritten()
        fixture.start()
        fixture.enqueue(1)
        runCurrent()
        closeReturned.await()

        assertEquals(1, fixture.observer.evidence.size)
        assertEquals(0, fixture.runtime.snapshotForTest().liveRecords)
    }

    @Test
    fun `close and join waits for pre seal retirement drain ownership`() = runTest {
        val fixture = ReplacementFixture(this)
        val prior = LifecycleCloseBlockingEngine(fixture.engine(30))
        val successor = fixture.engine(31)
        val replacementResult = AtomicReference<Boolean>()
        val replacementDone = CountDownLatch(1)
        fixture.start()
        assertTrue(fixture.runtime.replaceEngine(prior))

        Thread {
            try {
                replacementResult.set(
                    runBlocking { fixture.runtime.replaceEngine(successor) },
                )
            } finally {
                replacementDone.countDown()
            }
        }.start()
        prior.awaitClose()

        val closing = async { fixture.runtime.closeAndJoin() }
        runCurrent()

        assertFalse(closing.isCompleted)
        assertEquals(1L, replacementDone.count)

        prior.allowClose()
        replacementDone.await()
        runCurrent()
        closing.await()

        assertTrue(replacementResult.get())
        assertEquals(1, prior.closeCalls.get())
        assertEquals(1, successor.closeCalls)
        assertEquals(1, fixture.tun.closeCalls.get())
    }

    @Test
    fun `close and join waits for another close winner current engine drain`() = runTest {
        val fixture = ReplacementFixture(this)
        val current = LifecycleCloseBlockingEngine(fixture.initialEngine)
        val closeOwnerDone = CountDownLatch(1)
        fixture.runtime.start(current)

        Thread {
            try {
                fixture.runtime.requestClose()
            } finally {
                closeOwnerDone.countDown()
            }
        }.start()
        current.awaitClose()

        val closing = async { fixture.runtime.closeAndJoin() }
        runCurrent()

        assertFalse(closing.isCompleted)
        assertEquals(1L, closeOwnerDone.count)

        current.allowClose()
        closeOwnerDone.await()
        runCurrent()
        closing.await()

        assertEquals(1, current.closeCalls.get())
        assertEquals(1, fixture.initialEngine.closeCalls)
        assertEquals(1, fixture.tun.closeCalls.get())
    }

    @Test
    fun `close and join waits for pre seal proposal validation and loser close`() = runTest {
        val fixture = ReplacementFixture(this)
        val proposal = LifecycleValidationBlockingEngine(fixture.engine(30))
        val replacementResult = AtomicReference<Boolean>()
        val replacementDone = CountDownLatch(1)
        fixture.start()

        Thread {
            try {
                replacementResult.set(
                    runBlocking { fixture.runtime.replaceEngine(proposal) },
                )
            } finally {
                replacementDone.countDown()
            }
        }.start()
        proposal.awaitValidation()

        val closing = async { fixture.runtime.closeAndJoin() }
        runCurrent()

        assertFalse(closing.isCompleted)
        assertEquals(1L, replacementDone.count)

        proposal.allowValidation()
        replacementDone.await()
        runCurrent()
        closing.await()

        assertFalse(replacementResult.get())
        assertEquals(1, proposal.closeCalls.get())
        assertEquals(1, fixture.initialEngine.closeCalls)
        assertEquals(1, fixture.tun.closeCalls.get())
    }

    @Test
    fun `post seal proposal cannot reopen completed close ownership`() = runTest {
        val fixture = ReplacementFixture(this)
        val proposal = LifecycleValidationBlockingEngine(fixture.engine(30))
        val replacementResult = AtomicReference<Boolean>()
        val replacementDone = CountDownLatch(1)
        fixture.start()
        fixture.runtime.closeAndJoin()

        Thread {
            try {
                replacementResult.set(
                    runBlocking { fixture.runtime.replaceEngine(proposal) },
                )
            } finally {
                replacementDone.countDown()
            }
        }.start()
        proposal.awaitValidation()

        val repeatedClose = async { fixture.runtime.closeAndJoin() }
        runCurrent()
        assertTrue(repeatedClose.isCompleted)

        proposal.allowValidation()
        replacementDone.await()
        repeatedClose.await()

        assertFalse(replacementResult.get())
        assertEquals(1, proposal.closeCalls.get())
        assertEquals(1, fixture.initialEngine.closeCalls)
        assertEquals(1, fixture.tun.closeCalls.get())
    }

    @Test
    fun `intentional close is silent`() = runTest {
        val fixture = ReplacementFixture(this)
        fixture.start()

        fixture.runtime.closeAndJoin()

        assertTrue(fixture.observer.failures.isEmpty())
        assertTrue(fixture.observer.health.isEmpty())
    }

    @Test
    fun `unexpected EOF emits TunnelEnded exactly once`() = runTest {
        val fixture = ReplacementFixture(this)
        fixture.tun.enqueue(TunReadScript.EndOfStream)
        fixture.start()

        fixture.runtime.join()

        assertEquals(listOf(ProtectionFailure.TunnelEnded), fixture.observer.failures)
        assertEquals(1, fixture.tun.closeCalls.get())
        assertEquals(0, fixture.runtime.jobTopologyForTest().childCount)
    }

    @Test
    fun `unexpected read failure emits TunnelReadFailed exactly once`() = runTest {
        val fixture = ReplacementFixture(this)
        fixture.tun.enqueue(TunReadScript.Failed(IllegalStateException("read")))
        fixture.start()

        fixture.runtime.join()

        assertEquals(
            listOf(ProtectionFailure.TunnelReadFailed),
            fixture.observer.failures,
        )
        assertEquals(1, fixture.tun.closeCalls.get())
    }

    @Test
    fun `late old completion after close is silent and releases permits`() = runTest {
        val fixture = ReplacementFixture(this)
        fixture.start()
        fixture.enqueue(1)
        runCurrent()

        fixture.runtime.requestClose()
        fixture.completeResolution(1)
        fixture.runtime.join()

        assertTrue(fixture.tun.recordedWrites.isEmpty())
        assertTrue(fixture.observer.evidence.isEmpty())
        assertEquals(0, fixture.runtime.snapshotForTest().liveRecords)
    }
}

private class LifecycleCloseBlockingEngine(
    private val delegate: ReadyDnsEngine,
) : ReadyDnsEngine by delegate {
    val closeCalls = AtomicInteger()
    private val closeEntered = CountDownLatch(1)
    private val allowClose = CountDownLatch(1)

    override fun close() {
        if (closeCalls.incrementAndGet() == 1) {
            closeEntered.countDown()
            allowClose.await()
        }
        delegate.close()
    }

    fun awaitClose() {
        closeEntered.await()
    }

    fun allowClose() {
        allowClose.countDown()
    }
}

private class LifecycleValidationBlockingEngine(
    private val delegate: ReadyDnsEngine,
) : ReadyDnsEngine by delegate {
    val closeCalls = AtomicInteger()
    private val validationEntered = CountDownLatch(1)
    private val allowValidation = CountDownLatch(1)
    private val blockValidation = AtomicBoolean(true)

    override val policyIdentity: ProxyPolicyIdentity
        get() {
            if (blockValidation.compareAndSet(true, false)) {
                validationEntered.countDown()
                allowValidation.await()
            }
            return delegate.policyIdentity
        }

    override fun close() {
        closeCalls.incrementAndGet()
        delegate.close()
    }

    fun awaitValidation() {
        validationEntered.await()
    }

    fun allowValidation() {
        allowValidation.countDown()
    }
}
