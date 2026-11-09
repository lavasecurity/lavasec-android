package com.lavasec.app.vpn

import java.io.FileDescriptor
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ParcelFileDescriptorTunPacketSessionTest {
    @Test
    fun `full read and write preserve one complete packet`() = runBlocking {
        val fixture = DescriptorFixture()
        fixture.syscalls.pollResults += TunDescriptorPoll.Ready
        fixture.syscalls.readResults += TunDescriptorIo.Count(3)
        fixture.syscalls.writeResults += TunDescriptorIo.Count(4)

        try {
            val readBuffer = ByteArray(16)
            assertEquals(TunRead.Packet(3), fixture.session.readPacket(readBuffer))
            assertSame(TunWriteAttempt.Written, fixture.session.tryWritePacket(ByteArray(4)))
            assertEquals(1, fixture.syscalls.pollCalls.get())
            assertEquals(1, fixture.syscalls.readCalls.get())
            assertEquals(1, fixture.syscalls.writeCalls.get())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `would block is immediate and never enters poll`() {
        val fixture = DescriptorFixture()
        fixture.syscalls.writeResults += TunDescriptorIo.WouldBlock

        try {
            assertSame(
                TunWriteAttempt.WouldBlock,
                fixture.session.tryWritePacket(ByteArray(32)),
            )
            assertEquals(1, fixture.syscalls.writeCalls.get())
            assertEquals(0, fixture.syscalls.pollCalls.get())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `zero and positive partial writes preserve the exact count`() {
        val fixture = DescriptorFixture()
        fixture.syscalls.writeResults += TunDescriptorIo.Count(0)
        fixture.syscalls.writeResults += TunDescriptorIo.Count(7)

        try {
            assertEquals(
                TunWriteAttempt.ShortWrite(0),
                fixture.session.tryWritePacket(ByteArray(16)),
            )
            assertEquals(
                TunWriteAttempt.ShortWrite(7),
                fixture.session.tryWritePacket(ByteArray(16)),
            )
            assertEquals(0, fixture.syscalls.pollCalls.get())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a fourth interrupted write fails after exactly three retries`() {
        val fixture = DescriptorFixture()
        val interruptions = List(4) { index -> IOException("interrupt-$index") }
        interruptions.forEach { failure ->
            fixture.syscalls.writeResults += TunDescriptorIo.Interrupted(failure)
        }

        try {
            val result = fixture.session.tryWritePacket(ByteArray(8))
            assertTrue(result is TunWriteAttempt.Failed)
            assertSame(interruptions.last(), (result as TunWriteAttempt.Failed).cause)
            assertEquals(4, fixture.syscalls.writeCalls.get())
            assertEquals(0, fixture.syscalls.pollCalls.get())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a full write linearized before concurrent close remains written`() {
        val fixture = DescriptorFixture()
        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val closeSignalled = CountDownLatch(1)
        fixture.syscalls.writeAction = {
            writeStarted.countDown()
            releaseWrite.await()
            TunDescriptorIo.Count(4)
        }
        fixture.syscalls.signalAction = {
            closeSignalled.countDown()
            TunDescriptorSignal.Signalled
        }
        val executor = Executors.newFixedThreadPool(2)

        try {
            val write = executor.submit<TunWriteAttempt> {
                fixture.session.tryWritePacket(ByteArray(4))
            }
            assertTrue(writeStarted.await(2, TimeUnit.SECONDS))
            val close = executor.submit { fixture.session.close() }
            assertTrue(closeSignalled.await(2, TimeUnit.SECONDS))

            releaseWrite.countDown()

            assertSame(TunWriteAttempt.Written, write.get(2, TimeUnit.SECONDS))
            close.get(2, TimeUnit.SECONDS)
        } finally {
            releaseWrite.countDown()
            fixture.session.close()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `a packet read linearized before concurrent close remains readable`() {
        val fixture = DescriptorFixture()
        val readStarted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val closeSignalled = CountDownLatch(1)
        fixture.syscalls.pollResults += TunDescriptorPoll.Ready
        fixture.syscalls.readAction = {
            readStarted.countDown()
            releaseRead.await()
            TunDescriptorIo.Count(4)
        }
        fixture.syscalls.signalAction = {
            closeSignalled.countDown()
            TunDescriptorSignal.Signalled
        }
        val executor = Executors.newFixedThreadPool(2)

        try {
            val read = executor.submit<TunRead> {
                runBlocking { fixture.session.readPacket(ByteArray(8)) }
            }
            assertTrue(readStarted.await(2, TimeUnit.SECONDS))
            val close = executor.submit { fixture.session.close() }
            assertTrue(closeSignalled.await(2, TimeUnit.SECONDS))

            releaseRead.countDown()

            assertEquals(TunRead.Packet(4), read.get(2, TimeUnit.SECONDS))
            close.get(2, TimeUnit.SECONDS)
        } finally {
            releaseRead.countDown()
            fixture.session.close()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `try write never waits behind an active native read`() {
        val fixture = DescriptorFixture()
        val readStarted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        fixture.syscalls.pollResults += TunDescriptorPoll.Ready
        fixture.syscalls.readAction = {
            readStarted.countDown()
            releaseRead.await()
            TunDescriptorIo.Count(1)
        }
        fixture.syscalls.writeResults += TunDescriptorIo.Count(4)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val read = executor.submit<TunRead> {
                runBlocking { fixture.session.readPacket(ByteArray(8)) }
            }
            assertTrue(readStarted.await(2, TimeUnit.SECONDS))

            val write = executor.submit<TunWriteAttempt> {
                fixture.session.tryWritePacket(ByteArray(4))
            }
            assertSame(TunWriteAttempt.Written, write.get(500, TimeUnit.MILLISECONDS))

            releaseRead.countDown()
            assertEquals(TunRead.Packet(1), read.get(2, TimeUnit.SECONDS))
        } finally {
            releaseRead.countDown()
            fixture.session.close()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `read and writable waits share the exact interruption boundary`() = runBlocking {
        val readFixture = DescriptorFixture()
        repeat(3) { index ->
            readFixture.syscalls.pollResults +=
                TunDescriptorPoll.Interrupted(IOException("read-interrupt-$index"))
        }
        readFixture.syscalls.pollResults += TunDescriptorPoll.Ready
        readFixture.syscalls.readResults += TunDescriptorIo.Count(1)

        try {
            assertEquals(TunRead.Packet(1), readFixture.session.readPacket(ByteArray(8)))
            assertEquals(4, readFixture.syscalls.pollCalls.get())
        } finally {
            readFixture.close()
        }

        val writableFixture = DescriptorFixture()
        val interruptions = List(4) { index -> IOException("write-poll-interrupt-$index") }
        interruptions.forEach { failure ->
            writableFixture.syscalls.pollResults += TunDescriptorPoll.Interrupted(failure)
        }

        try {
            val result = writableFixture.session.awaitWritable()
            assertTrue(result is TunWritable.Failed)
            assertSame(interruptions.last(), (result as TunWritable.Failed).cause)
            assertEquals(4, writableFixture.syscalls.pollCalls.get())
        } finally {
            writableFixture.close()
        }
    }

    @Test
    fun `poll timeout checks cancellation boundary and retries without becoming a failure`() =
        runBlocking {
            val readFixture = DescriptorFixture()
            readFixture.syscalls.pollResults += TunDescriptorPoll.TimedOut
            readFixture.syscalls.pollResults += TunDescriptorPoll.Ready
            readFixture.syscalls.readResults += TunDescriptorIo.Count(1)

            try {
                assertEquals(TunRead.Packet(1), readFixture.session.readPacket(ByteArray(8)))
                assertEquals(2, readFixture.syscalls.pollCalls.get())
            } finally {
                readFixture.close()
            }

            val writableFixture = DescriptorFixture()
            writableFixture.syscalls.pollResults += TunDescriptorPoll.TimedOut
            writableFixture.syscalls.pollResults += TunDescriptorPoll.Ready

            try {
                assertSame(TunWritable.Writable, writableFixture.session.awaitWritable())
                assertEquals(2, writableFixture.syscalls.pollCalls.get())
            } finally {
                writableFixture.close()
            }
        }

    @Test
    fun `zero read is unexpected end of stream`() = runBlocking {
        val fixture = DescriptorFixture()
        fixture.syscalls.pollResults += TunDescriptorPoll.Ready
        fixture.syscalls.readResults += TunDescriptorIo.Count(0)

        try {
            assertSame(TunRead.EndOfStream, fixture.session.readPacket(ByteArray(8)))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `unexpected terminal descriptor poll is a typed failure`() = runBlocking {
        val fixture = DescriptorFixture()
        val failure = IOException("descriptor terminated")
        fixture.syscalls.pollResults += TunDescriptorPoll.DescriptorTerminated(failure)

        try {
            val result = fixture.session.readPacket(ByteArray(8))
            assertTrue(result is TunRead.Failed)
            assertSame(failure, (result as TunRead.Failed).cause)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `local close seals every operation without touching native IO again`() = runBlocking {
        val fixture = DescriptorFixture()
        fixture.session.close()

        assertSame(TunRead.Closed, fixture.session.readPacket(ByteArray(8)))
        assertSame(TunWriteAttempt.Closed, fixture.session.tryWritePacket(ByteArray(8)))
        assertSame(TunWritable.Closed, fixture.session.awaitWritable())
        assertEquals(0, fixture.syscalls.pollCalls.get())
        assertEquals(0, fixture.syscalls.readCalls.get())
        assertEquals(0, fixture.syscalls.writeCalls.get())
        assertEquals(1, fixture.owner.closeCalls.get())
        assertEquals(2, fixture.syscalls.closedDescriptors.size)
    }

    @Test
    fun `constructor failure closes the transferred descriptor`() {
        val descriptor = FileDescriptor()
        val owner = CountingOwner()
        val syscalls = FakeTunDescriptorSyscalls().apply {
            createWakeFailure = IOException("pipe unavailable")
        }

        val thrown = assertThrows(IOException::class.java) {
            ParcelFileDescriptorTunPacketSession(
                descriptor = descriptor,
                owner = owner,
                syscalls = syscalls,
                testing = Unit,
            )
        }

        assertEquals("pipe unavailable", thrown.message)
        assertEquals(1, owner.closeCalls.get())
        assertTrue(syscalls.closedDescriptors.isEmpty())
    }

    @Test
    fun `concurrent close owns every descriptor exactly once`() {
        val fixture = DescriptorFixture()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val closes = List(32) {
                executor.submit { fixture.session.close() }
            }
            closes.forEach { it.get(2, TimeUnit.SECONDS) }

            assertEquals(1, fixture.owner.closeCalls.get())
            assertEquals(1, fixture.syscalls.signalCalls.get())
            assertEquals(
                setOf(fixture.syscalls.wakePipe.read, fixture.syscalls.wakePipe.write),
                fixture.syscalls.closedDescriptors.toSet(),
            )
            assertEquals(2, fixture.syscalls.closedDescriptors.size)
        } finally {
            fixture.session.close()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `concurrent close callers wait for synchronous owner retirement`() {
        val ownerCloseStarted = CountDownLatch(1)
        val releaseOwnerClose = CountDownLatch(1)
        val owner = CountingOwner(
            closeAction = {
                ownerCloseStarted.countDown()
                releaseOwnerClose.await()
            },
        )
        val fixture = DescriptorFixture(owner)
        val secondCloseStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val firstClose = executor.submit { fixture.session.close() }
            assertTrue(ownerCloseStarted.await(2, TimeUnit.SECONDS))
            val secondClose = executor.submit {
                secondCloseStarted.countDown()
                fixture.session.close()
            }
            assertTrue(secondCloseStarted.await(2, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) {
                secondClose.get(200, TimeUnit.MILLISECONDS)
            }

            releaseOwnerClose.countDown()
            firstClose.get(2, TimeUnit.SECONDS)
            secondClose.get(2, TimeUnit.SECONDS)
            assertEquals(1, owner.closeCalls.get())
        } finally {
            releaseOwnerClose.countDown()
            runCatching { fixture.session.close() }
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `owner close failure is reported consistently after resources retire`() {
        val failure = IOException("owner close failed")
        val owner = CountingOwner(closeFailure = failure)
        val fixture = DescriptorFixture(owner)

        val first = assertThrows(IOException::class.java) {
            fixture.session.close()
        }
        val second = assertThrows(IOException::class.java) {
            fixture.session.close()
        }

        assertSame(failure, first)
        assertSame(failure, second)
        assertEquals(1, owner.closeCalls.get())
        assertEquals(2, fixture.syscalls.closedDescriptors.size)
    }

    @Test
    fun `constructor failure retains an owner cleanup failure as suppressed`() {
        val descriptor = FileDescriptor()
        val cleanupFailure = IOException("owner cleanup failed")
        val owner = CountingOwner(closeFailure = cleanupFailure)
        val syscalls = FakeTunDescriptorSyscalls().apply {
            createWakeFailure = IOException("pipe unavailable")
        }

        val thrown = assertThrows(IOException::class.java) {
            ParcelFileDescriptorTunPacketSession(
                descriptor = descriptor,
                owner = owner,
                syscalls = syscalls,
                testing = Unit,
            )
        }

        assertEquals("pipe unavailable", thrown.message)
        assertEquals(listOf(cleanupFailure), thrown.suppressed.toList())
        assertEquals(1, owner.closeCalls.get())
    }

    @Test
    fun `wake descriptors remain alive until the last retiring poll exits`() {
        val fixture = DescriptorFixture()
        val pollStarted = CountDownLatch(1)
        val releasePoll = CountDownLatch(1)
        val closeSignalled = CountDownLatch(1)
        fixture.syscalls.pollAction = {
            pollStarted.countDown()
            releasePoll.await()
            TunDescriptorPoll.Woken
        }
        fixture.syscalls.signalAction = {
            closeSignalled.countDown()
            TunDescriptorSignal.Signalled
        }
        val executor = Executors.newFixedThreadPool(2)

        try {
            val blockedRead = executor.submit<TunRead> {
                runBlocking { fixture.session.readPacket(ByteArray(8)) }
            }
            assertTrue(pollStarted.await(2, TimeUnit.SECONDS))

            val close = executor.submit { fixture.session.close() }
            assertTrue(closeSignalled.await(2, TimeUnit.SECONDS))

            assertEquals(0, fixture.owner.closeCalls.get())
            assertTrue(fixture.syscalls.closedDescriptors.isEmpty())
            assertFalse(blockedRead.isDone)
            assertFalse(close.isDone)

            releasePoll.countDown()
            assertSame(TunRead.Closed, blockedRead.get(2, TimeUnit.SECONDS))
            close.get(2, TimeUnit.SECONDS)
            assertEquals(1, fixture.owner.closeCalls.get())
            assertEquals(2, fixture.syscalls.closedDescriptors.size)
        } finally {
            releasePoll.countDown()
            fixture.session.close()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `coroutine cancellation remains cancellation and retires the operation`() = runBlocking {
        val fixture = DescriptorFixture()
        val pollStarted = CountDownLatch(1)
        val neverReleased = CountDownLatch(1)
        val completion = AtomicReference<Throwable?>()
        fixture.syscalls.pollAction = {
            pollStarted.countDown()
            neverReleased.await()
            TunDescriptorPoll.Ready
        }

        val read = launch(Dispatchers.Default) {
            try {
                fixture.session.readPacket(ByteArray(8))
            } catch (failure: Throwable) {
                completion.set(failure)
            }
        }
        assertTrue(pollStarted.await(2, TimeUnit.SECONDS))

        read.cancelAndJoin()
        assertTrue(completion.get() is CancellationException)

        fixture.session.close()
        assertEquals(1, fixture.owner.closeCalls.get())
        assertEquals(2, fixture.syscalls.closedDescriptors.size)
    }
}

private class DescriptorFixture(
    val owner: CountingOwner = CountingOwner(),
) {
    val descriptor = FileDescriptor()
    val syscalls = FakeTunDescriptorSyscalls()
    val session = ParcelFileDescriptorTunPacketSession(
        descriptor = descriptor,
        owner = owner,
        syscalls = syscalls,
        testing = Unit,
    )

    fun close() {
        session.close()
        check(owner.closeCalls.get() == 1)
    }
}

private class CountingOwner(
    private val closeAction: (() -> Unit)? = null,
    private val closeFailure: Throwable? = null,
) : AutoCloseable {
    val closeCalls = AtomicInteger()

    override fun close() {
        closeCalls.incrementAndGet()
        closeAction?.invoke()
        closeFailure?.let { throw it }
    }
}

private class FakeTunDescriptorSyscalls : TunDescriptorSyscalls {
    val wakePipe = TunWakePipe(FileDescriptor(), FileDescriptor())
    val pollResults = ConcurrentLinkedQueue<TunDescriptorPoll>()
    val readResults = ConcurrentLinkedQueue<TunDescriptorIo>()
    val writeResults = ConcurrentLinkedQueue<TunDescriptorIo>()
    val pollCalls = AtomicInteger()
    val readCalls = AtomicInteger()
    val writeCalls = AtomicInteger()
    val signalCalls = AtomicInteger()
    val closedDescriptors = mutableListOf<FileDescriptor>()
    var createWakeFailure: Throwable? = null
    var pollAction: (() -> TunDescriptorPoll)? = null
    var readAction: (() -> TunDescriptorIo)? = null
    var writeAction: (() -> TunDescriptorIo)? = null
    var signalAction: (() -> TunDescriptorSignal)? = null

    override fun createWakePipe(): TunWakePipe {
        createWakeFailure?.let { throw it }
        return wakePipe
    }

    override fun poll(
        descriptor: FileDescriptor,
        wakeReadDescriptor: FileDescriptor,
        interest: TunPollInterest,
    ): TunDescriptorPoll {
        pollCalls.incrementAndGet()
        return pollAction?.invoke()
            ?: pollResults.poll()
            ?: error("No scripted poll result for $interest")
    }

    override fun read(
        descriptor: FileDescriptor,
        into: ByteArray,
        offset: Int,
        byteCount: Int,
    ): TunDescriptorIo {
        readCalls.incrementAndGet()
        return readAction?.invoke()
            ?: readResults.poll()
            ?: error("No scripted read result")
    }

    override fun write(
        descriptor: FileDescriptor,
        packet: ByteArray,
        offset: Int,
        byteCount: Int,
    ): TunDescriptorIo {
        writeCalls.incrementAndGet()
        return writeAction?.invoke()
            ?: writeResults.poll()
            ?: error("No scripted write result")
    }

    override fun signal(wakeWriteDescriptor: FileDescriptor): TunDescriptorSignal {
        signalCalls.incrementAndGet()
        return signalAction?.invoke() ?: TunDescriptorSignal.Signalled
    }

    override fun close(descriptor: FileDescriptor) {
        synchronized(closedDescriptors) {
            closedDescriptors += descriptor
        }
    }
}
