package com.lavasec.app.vpn

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.FileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ParcelFileDescriptorTunPacketSessionInstrumentationTest {
    @Test
    fun parcelDescriptorPreservesOneDatagramPacketBoundary() {
        val pair = parcelSessionPair()
        try {
            val inbound = byteArrayOf(1, 2, 3, 4, 5)
            assertEquals(inbound.size, Os.write(pair.peer, inbound, 0, inbound.size))

            val readBuffer = ByteArray(32)
            val read = runBlocking { pair.session.readPacket(readBuffer) }
            assertEquals(TunRead.Packet(inbound.size), read)
            assertArrayEquals(inbound, readBuffer.copyOf(inbound.size))

            val outbound = byteArrayOf(9, 8, 7, 6)
            assertSame(TunWriteAttempt.Written, pair.session.tryWritePacket(outbound))
            val peerBuffer = ByteArray(32)
            val peerRead = Os.read(pair.peer, peerBuffer, 0, peerBuffer.size)
            assertEquals(outbound.size, peerRead)
            assertArrayEquals(outbound, peerBuffer.copyOf(peerRead))
        } finally {
            pair.close()
        }
    }

    @Test
    fun cancellingBlockedDescriptorReadCompletesWithoutClosingSession() {
        val pollEntered = CountDownLatch(1)
        val cancellationCompleted = CountDownLatch(1)
        val sawCancellation = AtomicBoolean(false)
        val pair = sessionPair(pollEntered)
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit {
                runBlocking {
                    val read = launch(Dispatchers.IO) {
                        try {
                            pair.session.readPacket(ByteArray(32))
                        } catch (_: CancellationException) {
                            sawCancellation.set(true)
                        }
                    }
                    assertTrue(pollEntered.await(2, TimeUnit.SECONDS))
                    read.cancelAndJoin()
                    cancellationCompleted.countDown()
                }
            }

            assertTrue(pollEntered.await(2, TimeUnit.SECONDS))
            assertTrue(cancellationCompleted.await(2, TimeUnit.SECONDS))
            assertTrue(sawCancellation.get())
        } finally {
            pair.close()
            executor.closeAndJoin()
        }
    }

    @Test
    fun cancellingBlockedWritablePollCompletesWithoutClosingSession() {
        val pollEntered = CountDownLatch(1)
        val cancellationCompleted = CountDownLatch(1)
        val sawCancellation = AtomicBoolean(false)
        val pair = sessionPair(pollEntered)
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertTrue(saturate(pair.session) > 0)
            executor.submit {
                runBlocking {
                    val writable = launch(Dispatchers.IO) {
                        try {
                            pair.session.awaitWritable()
                        } catch (_: CancellationException) {
                            sawCancellation.set(true)
                        }
                    }
                    assertTrue(pollEntered.await(2, TimeUnit.SECONDS))
                    writable.cancelAndJoin()
                    cancellationCompleted.countDown()
                }
            }

            assertTrue(pollEntered.await(2, TimeUnit.SECONDS))
            assertTrue(cancellationCompleted.await(2, TimeUnit.SECONDS))
            assertTrue(sawCancellation.get())
        } finally {
            pair.close()
            executor.closeAndJoin()
        }
    }

    @Test
    fun fullPeerBufferReturnsWouldBlockWithoutPolling() {
        val pair = sessionPair()
        try {
            assertTrue(saturate(pair.session) > 0)
            assertSame(
                TunWriteAttempt.WouldBlock,
                pair.session.tryWritePacket(SATURATION_PACKET),
            )
        } finally {
            pair.close()
        }
    }

    @Test
    fun awaitWritableResumesAfterPeerDrain() {
        val pollEntered = CountDownLatch(1)
        val pair = sessionPair(pollEntered)
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertTrue(saturate(pair.session) > 0)
            val writable = executor.submit<TunWritable> {
                runBlocking { pair.session.awaitWritable() }
            }
            assertTrue(pollEntered.await(2, TimeUnit.SECONDS))
            assertBlocked(writable)

            drainPeerUntilComplete(pair.peer, writable)

            assertSame(TunWritable.Writable, writable.get(2, TimeUnit.SECONDS))
        } finally {
            pair.close()
            executor.closeAndJoin()
        }
    }

    @Test
    fun closeWakesBlockedDescriptorRead() {
        val pollEntered = CountDownLatch(1)
        val pair = sessionPair(pollEntered)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val read = executor.submit<TunRead> {
                runBlocking { pair.session.readPacket(ByteArray(32)) }
            }
            assertTrue(pollEntered.await(2, TimeUnit.SECONDS))
            assertBlocked(read)

            pair.session.close()

            assertSame(TunRead.Closed, read.get(2, TimeUnit.SECONDS))
        } finally {
            pair.close()
            executor.closeAndJoin()
        }
    }

    @Test
    fun closeWakesBlockedWritablePoll() {
        val pollEntered = CountDownLatch(1)
        val pair = sessionPair(pollEntered)
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertTrue(saturate(pair.session) > 0)
            val writable = executor.submit<TunWritable> {
                runBlocking { pair.session.awaitWritable() }
            }
            assertTrue(pollEntered.await(2, TimeUnit.SECONDS))
            assertBlocked(writable)

            pair.session.close()

            assertSame(TunWritable.Closed, writable.get(2, TimeUnit.SECONDS))
        } finally {
            pair.close()
            executor.closeAndJoin()
        }
    }

    @Test
    fun oneCloseWakesConcurrentReadAndWritablePolls() {
        val pollEntered = CountDownLatch(2)
        val pair = sessionPair(pollEntered)
        val executor = Executors.newFixedThreadPool(2)
        try {
            assertTrue(saturate(pair.session) > 0)
            val read = executor.submit<TunRead> {
                runBlocking { pair.session.readPacket(ByteArray(32)) }
            }
            val writable = executor.submit<TunWritable> {
                runBlocking { pair.session.awaitWritable() }
            }
            assertTrue(pollEntered.await(2, TimeUnit.SECONDS))
            assertBlocked(read)
            assertBlocked(writable)

            pair.session.close()

            assertSame(TunRead.Closed, read.get(2, TimeUnit.SECONDS))
            assertSame(TunWritable.Closed, writable.get(2, TimeUnit.SECONDS))
        } finally {
            pair.close()
            executor.closeAndJoin()
        }
    }

    @Test
    fun retiringPollerCannotObserveRapidDescriptorReuse() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(32) {
                val pollEntered = CountDownLatch(1)
                val ownerCloseStarted = CountDownLatch(1)
                val releaseOwnerClose = CountDownLatch(1)
                val pair = sessionPair(
                    pollEntered = pollEntered,
                    ownerCloseStarted = ownerCloseStarted,
                    releaseOwnerClose = releaseOwnerClose,
                )
                try {
                    val read = executor.submit<TunRead> {
                        runBlocking { pair.session.readPacket(ByteArray(32)) }
                    }
                    assertTrue(pollEntered.await(2, TimeUnit.SECONDS))
                    assertBlocked(read)
                    val close = executor.submit { pair.session.close() }
                    assertTrue(ownerCloseStarted.await(2, TimeUnit.SECONDS))

                    val replacement = rawDatagramPair()
                    try {
                        val marker = byteArrayOf(0x5a)
                        Os.write(replacement.second, marker, 0, marker.size)
                    } finally {
                        closeQuietly(replacement.first)
                        closeQuietly(replacement.second)
                    }

                    assertSame(TunRead.Closed, read.get(2, TimeUnit.SECONDS))
                    releaseOwnerClose.countDown()
                    close.get(2, TimeUnit.SECONDS)
                } finally {
                    releaseOwnerClose.countDown()
                    pair.close()
                }
            }
        } finally {
            executor.closeAndJoin()
        }
    }

    private fun parcelSessionPair(): SessionPair {
        val (owned, peer) = rawDatagramPair()
        val parcelDescriptor = try {
            ParcelFileDescriptor.dup(owned)
        } finally {
            closeQuietly(owned)
        }
        return try {
            SessionPair(
                session = ParcelFileDescriptorTunPacketSession(parcelDescriptor),
                peer = peer,
            )
        } catch (failure: Throwable) {
            closeQuietly(peer)
            throw failure
        }
    }

    private fun sessionPair(
        pollEntered: CountDownLatch? = null,
        ownerCloseStarted: CountDownLatch? = null,
        releaseOwnerClose: CountDownLatch? = null,
    ): SessionPair {
        val (owned, peer) = rawDatagramPair()
        return try {
            SessionPair(
                session = ParcelFileDescriptorTunPacketSession(
                    descriptor = owned,
                    owner = AutoCloseable {
                        Os.close(owned)
                        ownerCloseStarted?.countDown()
                        releaseOwnerClose?.await()
                    },
                    syscalls = if (pollEntered == null) {
                        AndroidTunDescriptorSyscalls()
                    } else {
                        AndroidTunDescriptorSyscalls(pollEntered::countDown)
                    },
                    testing = Unit,
                ),
                peer = peer,
            )
        } catch (failure: Throwable) {
            closeQuietly(owned)
            closeQuietly(peer)
            throw failure
        }
    }

    private fun rawDatagramPair(): Pair<FileDescriptor, FileDescriptor> {
        val owned = FileDescriptor()
        val peer = FileDescriptor()
        Os.socketpair(
            OsConstants.AF_UNIX,
            OsConstants.SOCK_DGRAM or LINUX_SOCK_NONBLOCK,
            0,
            owned,
            peer,
        )
        return owned to peer
    }

    private fun saturate(session: ParcelFileDescriptorTunPacketSession): Int {
        var written = 0
        repeat(MAX_SATURATION_WRITES) {
            when (val result = session.tryWritePacket(SATURATION_PACKET)) {
                TunWriteAttempt.Written -> written += 1
                TunWriteAttempt.WouldBlock -> return written
                else -> fail("unexpected saturation result: $result")
            }
        }
        fail("peer buffer did not saturate after $MAX_SATURATION_WRITES packets")
        return written
    }

    private fun drainPeerUntilComplete(
        peer: FileDescriptor,
        writable: Future<TunWritable>,
    ) {
        val buffer = ByteArray(SATURATION_PACKET.size)
        repeat(MAX_SATURATION_WRITES) {
            if (writable.isDone) return
            try {
                Os.read(peer, buffer, 0, buffer.size)
            } catch (failure: ErrnoException) {
                if (failure.errno != OsConstants.EAGAIN) throw failure
            }
            Thread.yield()
        }
    }

    private fun assertBlocked(future: Future<*>) {
        try {
            future.get(BLOCK_ASSERT_MILLIS, TimeUnit.MILLISECONDS)
            fail("descriptor operation completed before its wake condition")
        } catch (_: TimeoutException) {
            // Expected: the peer is deliberately idle or saturated.
        }
    }

    private fun ExecutorService.closeAndJoin() {
        shutdownNow()
        assertTrue(awaitTermination(2, TimeUnit.SECONDS))
    }

    private fun closeQuietly(descriptor: FileDescriptor) {
        runCatching { Os.close(descriptor) }
    }

    private data class SessionPair(
        val session: ParcelFileDescriptorTunPacketSession,
        val peer: FileDescriptor,
    ) : AutoCloseable {
        override fun close() {
            session.close()
            runCatching { Os.close(peer) }
        }
    }

    private companion object {
        // API 28 omits SOCK_NONBLOCK from the Java facade even though socketpair accepts the
        // stable Linux UAPI flag.
        const val LINUX_SOCK_NONBLOCK = 0x800
        const val MAX_SATURATION_WRITES = 8_192
        const val BLOCK_ASSERT_MILLIS = 150L
        val SATURATION_PACKET = ByteArray(4 * 1_024) { 0x5a.toByte() }
    }
}
