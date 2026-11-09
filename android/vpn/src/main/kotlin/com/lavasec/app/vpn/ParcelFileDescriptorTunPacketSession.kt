package com.lavasec.app.vpn

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileDescriptor
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlin.coroutines.coroutineContext

/**
 * Owns one already-established, nonblocking TUN descriptor.
 *
 * The future [android.net.VpnService.Builder] owner is responsible for requesting
 * `setBlocking(false)` before transferring the descriptor here. This adapter does not establish
 * or start a VPN.
 */
internal class ParcelFileDescriptorTunPacketSession private constructor(
    private val backend: PollingTunDescriptorBackend,
) : TunPacketSession {
    constructor(descriptor: ParcelFileDescriptor) : this(
        PollingTunDescriptorBackend.create(descriptor),
    )

    internal constructor(
        descriptor: FileDescriptor,
        owner: AutoCloseable,
        syscalls: TunDescriptorSyscalls,
        @Suppress("UNUSED_PARAMETER") testing: Unit,
    ) : this(
        PollingTunDescriptorBackend.create(
            descriptor = descriptor,
            owner = owner,
            syscalls = syscalls,
        ),
    )

    /** Device-test seam for a descriptor opened with the production nonblocking contract. */
    internal constructor(
        descriptor: FileDescriptor,
        owner: AutoCloseable,
        @Suppress("UNUSED_PARAMETER") testing: Unit,
    ) : this(
        PollingTunDescriptorBackend.create(
            descriptor = descriptor,
            owner = owner,
            syscalls = PRODUCTION_ANDROID_TUN_DESCRIPTOR_SYSCALLS,
        ),
    )

    override suspend fun readPacket(into: ByteArray): TunRead {
        require(into.isNotEmpty()) { "TUN read buffer must not be empty" }
        val callerContext = coroutineContext
        return runInterruptible(Dispatchers.IO) {
            backend.readPacket(into, callerContext::ensureActive)
        }
    }

    override fun tryWritePacket(packet: ByteArray): TunWriteAttempt {
        require(packet.isNotEmpty()) { "TUN packet must not be empty" }
        return backend.tryWritePacket(packet)
    }

    override suspend fun awaitWritable(): TunWritable {
        val callerContext = coroutineContext
        return runInterruptible(Dispatchers.IO) {
            backend.awaitWritable(callerContext::ensureActive)
        }
    }

    override fun close() = backend.close()
}

internal data class TunWakePipe(
    val read: FileDescriptor,
    val write: FileDescriptor,
)

internal enum class TunPollInterest {
    Readable,
    Writable,
}

internal sealed interface TunDescriptorPoll {
    data object Ready : TunDescriptorPoll
    data object Woken : TunDescriptorPoll
    data object TimedOut : TunDescriptorPoll
    data class DescriptorTerminated(val cause: Throwable) : TunDescriptorPoll
    data class WakeTerminated(val cause: Throwable) : TunDescriptorPoll
    data class Interrupted(val cause: Throwable) : TunDescriptorPoll
    data class Failed(val cause: Throwable) : TunDescriptorPoll
}

internal sealed interface TunDescriptorIo {
    data class Count(val byteCount: Int) : TunDescriptorIo
    data object WouldBlock : TunDescriptorIo
    data class Interrupted(val cause: Throwable) : TunDescriptorIo
    data class Failed(val cause: Throwable) : TunDescriptorIo
}

internal sealed interface TunDescriptorSignal {
    data object Signalled : TunDescriptorSignal
    data class Interrupted(val cause: Throwable) : TunDescriptorSignal
    data class Failed(val cause: Throwable) : TunDescriptorSignal
}

internal interface TunDescriptorSyscalls {
    fun createWakePipe(): TunWakePipe

    fun poll(
        descriptor: FileDescriptor,
        wakeReadDescriptor: FileDescriptor,
        interest: TunPollInterest,
    ): TunDescriptorPoll

    fun read(
        descriptor: FileDescriptor,
        into: ByteArray,
        offset: Int,
        byteCount: Int,
    ): TunDescriptorIo

    fun write(
        descriptor: FileDescriptor,
        packet: ByteArray,
        offset: Int,
        byteCount: Int,
    ): TunDescriptorIo

    fun signal(wakeWriteDescriptor: FileDescriptor): TunDescriptorSignal

    fun close(descriptor: FileDescriptor)
}

private class PollingTunDescriptorBackend private constructor(
    private val descriptor: FileDescriptor,
    private val owner: AutoCloseable,
    private val syscalls: TunDescriptorSyscalls,
    private val wakePipe: TunWakePipe,
) : AutoCloseable {
    /*
     * The high bit seals the descriptor against new operations. The remaining bits count native
     * operations that already own a descriptor lease. This keeps tryWritePacket lock-free: close
     * waits for leases on its own path instead of making a nonblocking write wait behind read or
     * descriptor retirement.
     */
    private val operationState = AtomicLong(0)
    private val closeLock = ReentrantLock()
    private val operationsRetired = closeLock.newCondition()
    private val closeCompleted = closeLock.newCondition()

    private var closeFinished = false
    private var completedCloseFailure: Throwable? = null
    private var wakeWriteOpen = true

    fun readPacket(
        into: ByteArray,
        checkCancellation: () -> Unit,
    ): TunRead {
        if (!beginOperation()) return TunRead.Closed
        try {
            var interruptedCount = 0
            while (true) {
                checkCancellation()
                if (isSealed()) return TunRead.Closed

                when (
                    val readiness = syscalls.poll(
                        descriptor = descriptor,
                        wakeReadDescriptor = wakePipe.read,
                        interest = TunPollInterest.Readable,
                    )
                ) {
                    TunDescriptorPoll.Ready -> Unit
                    TunDescriptorPoll.TimedOut -> continue
                    TunDescriptorPoll.Woken -> {
                        return if (isSealed()) {
                            TunRead.Closed
                        } else {
                            TunRead.Failed(IOException("TUN wake descriptor signalled unexpectedly"))
                        }
                    }

                    is TunDescriptorPoll.DescriptorTerminated -> {
                        return closedOrReadFailure(readiness.cause)
                    }

                    is TunDescriptorPoll.WakeTerminated -> {
                        return closedOrReadFailure(readiness.cause)
                    }

                    is TunDescriptorPoll.Failed -> {
                        return closedOrReadFailure(readiness.cause)
                    }

                    is TunDescriptorPoll.Interrupted -> {
                        if (isSealed()) return TunRead.Closed
                        checkCancellation()
                        interruptedCount += 1
                        if (interruptedCount > MAX_INTERRUPTED_RETRIES) {
                            return TunRead.Failed(readiness.cause)
                        }
                        continue
                    }
                }

                checkCancellation()
                if (isSealed()) return TunRead.Closed
                val read = syscalls.read(descriptor, into, 0, into.size)

                when (read) {
                    is TunDescriptorIo.Count -> {
                        return when {
                            read.byteCount == 0 -> TunRead.EndOfStream
                            read.byteCount in 1..into.size -> TunRead.Packet(read.byteCount)
                            else -> TunRead.Failed(
                                IOException(
                                    "Invalid TUN read count ${read.byteCount} for ${into.size}-byte buffer",
                                ),
                            )
                        }
                    }

                    TunDescriptorIo.WouldBlock -> continue
                    is TunDescriptorIo.Failed -> return closedOrReadFailure(read.cause)
                    is TunDescriptorIo.Interrupted -> {
                        if (isSealed()) return TunRead.Closed
                        checkCancellation()
                        interruptedCount += 1
                        if (interruptedCount > MAX_INTERRUPTED_RETRIES) {
                            return TunRead.Failed(read.cause)
                        }
                    }
                }
            }
        } finally {
            endOperation()
        }
    }

    fun tryWritePacket(packet: ByteArray): TunWriteAttempt {
        if (!beginOperation()) return TunWriteAttempt.Closed
        try {
            var interruptedCount = 0
            while (true) {
                if (isSealed()) return TunWriteAttempt.Closed
                val write = syscalls.write(descriptor, packet, 0, packet.size)

                when (write) {
                    is TunDescriptorIo.Count -> {
                        return when {
                            write.byteCount == packet.size -> TunWriteAttempt.Written
                            write.byteCount in 0 until packet.size -> {
                                TunWriteAttempt.ShortWrite(write.byteCount)
                            }

                            else -> TunWriteAttempt.Failed(
                                IOException(
                                    "Invalid TUN write count ${write.byteCount} for " +
                                        "${packet.size}-byte packet",
                                ),
                            )
                        }
                    }

                    TunDescriptorIo.WouldBlock -> {
                        return if (isSealed()) {
                            TunWriteAttempt.Closed
                        } else {
                            TunWriteAttempt.WouldBlock
                        }
                    }

                    is TunDescriptorIo.Failed -> {
                        return closedOrWriteFailure(write.cause)
                    }

                    is TunDescriptorIo.Interrupted -> {
                        if (isSealed()) return TunWriteAttempt.Closed
                        interruptedCount += 1
                        if (interruptedCount > MAX_INTERRUPTED_RETRIES) {
                            return TunWriteAttempt.Failed(write.cause)
                        }
                    }
                }
            }
        } finally {
            endOperation()
        }
    }

    fun awaitWritable(checkCancellation: () -> Unit): TunWritable {
        if (!beginOperation()) return TunWritable.Closed
        try {
            var interruptedCount = 0
            while (true) {
                checkCancellation()
                if (isSealed()) return TunWritable.Closed

                when (
                    val readiness = syscalls.poll(
                        descriptor = descriptor,
                        wakeReadDescriptor = wakePipe.read,
                        interest = TunPollInterest.Writable,
                    )
                ) {
                    TunDescriptorPoll.Ready -> {
                        checkCancellation()
                        return if (isSealed()) TunWritable.Closed else TunWritable.Writable
                    }

                    TunDescriptorPoll.TimedOut -> continue

                    TunDescriptorPoll.Woken -> {
                        return if (isSealed()) {
                            TunWritable.Closed
                        } else {
                            TunWritable.Failed(
                                IOException("TUN wake descriptor signalled unexpectedly"),
                            )
                        }
                    }

                    is TunDescriptorPoll.DescriptorTerminated -> {
                        return closedOrWritableFailure(readiness.cause)
                    }

                    is TunDescriptorPoll.WakeTerminated -> {
                        return closedOrWritableFailure(readiness.cause)
                    }

                    is TunDescriptorPoll.Failed -> {
                        return closedOrWritableFailure(readiness.cause)
                    }

                    is TunDescriptorPoll.Interrupted -> {
                        if (isSealed()) return TunWritable.Closed
                        checkCancellation()
                        interruptedCount += 1
                        if (interruptedCount > MAX_INTERRUPTED_RETRIES) {
                            return TunWritable.Failed(readiness.cause)
                        }
                    }
                }
            }
        } finally {
            endOperation()
        }
    }

    override fun close() {
        if (!sealOperations()) {
            awaitCloseCompletion()?.let { throw it }
            return
        }

        var failure: Throwable? = signalCloseWaiters()
        awaitOperationRetirement()
        try {
            owner.close()
        } catch (ownerFailure: Throwable) {
            failure = failure.append(ownerFailure)
        }
        failure = closeWakeDescriptors(failure)

        closeLock.withLock {
            completedCloseFailure = failure
            closeFinished = true
            closeCompleted.signalAll()
        }
        failure?.let { throw it }
    }

    private fun beginOperation(): Boolean {
        while (true) {
            val state = operationState.get()
            if (state.isSealed()) return false
            check(state < OPERATION_COUNT_MASK) { "TUN descriptor operation count overflow" }
            if (operationState.compareAndSet(state, state + 1)) return true
        }
    }

    private fun endOperation() {
        while (true) {
            val state = operationState.get()
            val activeOperations = state and OPERATION_COUNT_MASK
            check(activeOperations > 0) { "TUN descriptor operation accounting underflow" }
            if (!operationState.compareAndSet(state, state - 1)) continue
            if (activeOperations == 1L && state.isSealed()) {
                closeLock.withLock {
                    operationsRetired.signalAll()
                }
            }
            return
        }
    }

    private fun sealOperations(): Boolean {
        while (true) {
            val state = operationState.get()
            if (state.isSealed()) return false
            if (operationState.compareAndSet(state, state or OPERATIONS_SEALED)) return true
        }
    }

    private fun awaitOperationRetirement() {
        closeLock.withLock {
            while (operationState.get().activeOperationCount() != 0L) {
                operationsRetired.awaitUninterruptibly()
            }
        }
    }

    private fun awaitCloseCompletion(): Throwable? = closeLock.withLock {
        while (!closeFinished) {
            closeCompleted.awaitUninterruptibly()
        }
        completedCloseFailure
    }

    private fun signalCloseWaiters(): Throwable? {
        var interruptedCount = 0
        while (true) {
            val signal = try {
                syscalls.signal(wakePipe.write)
            } catch (failure: Throwable) {
                return closeWakeWriterForFallback(failure)
            }
            when (signal) {
                TunDescriptorSignal.Signalled -> return null
                is TunDescriptorSignal.Failed -> {
                    return closeWakeWriterForFallback(signal.cause)
                }

                is TunDescriptorSignal.Interrupted -> {
                    interruptedCount += 1
                    if (interruptedCount > MAX_INTERRUPTED_RETRIES) {
                        return closeWakeWriterForFallback(signal.cause)
                    }
                }
            }
        }
    }

    private fun closeWakeWriterForFallback(signalFailure: Throwable): Throwable? {
        if (!wakeWriteOpen) return null
        wakeWriteOpen = false
        return try {
            syscalls.close(wakePipe.write)
            null
        } catch (closeFailure: Throwable) {
            closeFailure.appendSuppressed(signalFailure)
            closeFailure
        }
    }

    private fun closeWakeDescriptors(initialFailure: Throwable?): Throwable? {
        var failure = initialFailure
        try {
            syscalls.close(wakePipe.read)
        } catch (closeFailure: Throwable) {
            failure = failure.append(closeFailure)
        }
        if (wakeWriteOpen) {
            wakeWriteOpen = false
            try {
                syscalls.close(wakePipe.write)
            } catch (closeFailure: Throwable) {
                failure = failure.append(closeFailure)
            }
        }
        return failure
    }

    private fun closedOrReadFailure(cause: Throwable): TunRead =
        if (isSealed()) TunRead.Closed else TunRead.Failed(cause)

    private fun closedOrWriteFailure(cause: Throwable): TunWriteAttempt =
        if (isSealed()) TunWriteAttempt.Closed else TunWriteAttempt.Failed(cause)

    private fun closedOrWritableFailure(cause: Throwable): TunWritable =
        if (isSealed()) TunWritable.Closed else TunWritable.Failed(cause)

    private fun isSealed(): Boolean = operationState.get().isSealed()

    private fun Long.isSealed(): Boolean = this and OPERATIONS_SEALED != 0L

    private fun Long.activeOperationCount(): Long = this and OPERATION_COUNT_MASK

    private fun Throwable?.append(next: Throwable): Throwable {
        if (this == null) return next
        appendSuppressed(next)
        return this
    }

    private fun Throwable.appendSuppressed(next: Throwable) {
        if (this !== next) addSuppressed(next)
    }

    companion object {
        fun create(descriptor: ParcelFileDescriptor): PollingTunDescriptorBackend {
            val fileDescriptor = try {
                descriptor.fileDescriptor
            } catch (failure: Throwable) {
                closeAfterFailure(descriptor, failure)
                throw failure
            }
            return create(
                descriptor = fileDescriptor,
                owner = descriptor,
                syscalls = PRODUCTION_ANDROID_TUN_DESCRIPTOR_SYSCALLS,
            )
        }

        fun create(
            descriptor: FileDescriptor,
            owner: AutoCloseable,
            syscalls: TunDescriptorSyscalls,
        ): PollingTunDescriptorBackend {
            val wakePipe = try {
                syscalls.createWakePipe()
            } catch (failure: Throwable) {
                closeAfterFailure(owner, failure)
                throw failure
            }
            return PollingTunDescriptorBackend(
                descriptor = descriptor,
                owner = owner,
                syscalls = syscalls,
                wakePipe = wakePipe,
            )
        }

        private fun closeAfterFailure(owner: AutoCloseable, failure: Throwable) {
            try {
                owner.close()
            } catch (cleanupFailure: Throwable) {
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            }
        }

        private const val MAX_INTERRUPTED_RETRIES = 3
        private const val OPERATIONS_SEALED = Long.MIN_VALUE
        private const val OPERATION_COUNT_MASK = Long.MAX_VALUE
    }
}

private val PRODUCTION_ANDROID_TUN_DESCRIPTOR_SYSCALLS = AndroidTunDescriptorSyscalls()

internal class AndroidTunDescriptorSyscalls(
    private val onBlockingPollEntry: () -> Unit = {},
) : TunDescriptorSyscalls {
    override fun createWakePipe(): TunWakePipe {
        /*
         * Android's public Os API does not expose pipe2. A Unix socket pair provides the same
         * pollable one-byte wake channel while applying close-on-exec and nonblocking flags
         * atomically at creation.
         */
        val read = FileDescriptor()
        val write = FileDescriptor()
        Os.socketpair(
            OsConstants.AF_UNIX,
            OsConstants.SOCK_STREAM or LINUX_SOCK_CLOEXEC or LINUX_SOCK_NONBLOCK,
            0,
            read,
            write,
        )
        return TunWakePipe(read = read, write = write)
    }

    override fun poll(
        descriptor: FileDescriptor,
        wakeReadDescriptor: FileDescriptor,
        interest: TunPollInterest,
    ): TunDescriptorPoll {
        val requestedEvent = when (interest) {
            TunPollInterest.Readable -> OsConstants.POLLIN
            TunPollInterest.Writable -> OsConstants.POLLOUT
        }
        val tun = StructPollfd().apply {
            fd = descriptor
            events = requestedEvent.toShort()
        }
        val wake = StructPollfd().apply {
            fd = wakeReadDescriptor
            events = OsConstants.POLLIN.toShort()
        }

        try {
            onBlockingPollEntry()
            val readyCount = Os.poll(
                arrayOf(tun, wake),
                POLL_CANCELLATION_CHECK_MILLIS,
            )
            if (readyCount == 0) return TunDescriptorPoll.TimedOut
        } catch (failure: ErrnoException) {
            return if (failure.errno == OsConstants.EINTR) {
                TunDescriptorPoll.Interrupted(failure)
            } else {
                TunDescriptorPoll.Failed(nativeFailure("poll", failure))
            }
        } catch (failure: InterruptedIOException) {
            return TunDescriptorPoll.Interrupted(failure)
        }

        val wakeEvents = wake.revents.toInt()
        val tunEvents = tun.revents.toInt()
        if (wakeEvents and POLL_TERMINAL_EVENTS != 0) {
            return TunDescriptorPoll.WakeTerminated(
                IOException("TUN wake descriptor terminated while polling: $wakeEvents"),
            )
        }
        if (wakeEvents and OsConstants.POLLIN != 0) {
            return TunDescriptorPoll.Woken
        }
        if (tunEvents and POLL_TERMINAL_EVENTS != 0) {
            return TunDescriptorPoll.DescriptorTerminated(
                IOException("TUN descriptor terminated while polling: $tunEvents"),
            )
        }
        if (tunEvents and requestedEvent != 0) {
            return TunDescriptorPoll.Ready
        }
        return TunDescriptorPoll.Failed(
            IOException(
                "TUN poll returned without readiness: tun=$tunEvents wake=$wakeEvents",
            ),
        )
    }

    override fun read(
        descriptor: FileDescriptor,
        into: ByteArray,
        offset: Int,
        byteCount: Int,
    ): TunDescriptorIo = nativeIo("read") {
        Os.read(descriptor, into, offset, byteCount)
    }

    override fun write(
        descriptor: FileDescriptor,
        packet: ByteArray,
        offset: Int,
        byteCount: Int,
    ): TunDescriptorIo = nativeIo("write") {
        Os.write(descriptor, packet, offset, byteCount)
    }

    override fun signal(wakeWriteDescriptor: FileDescriptor): TunDescriptorSignal {
        return when (
            val write = nativeIo("wake") {
                Os.write(wakeWriteDescriptor, CLOSE_SIGNAL, 0, CLOSE_SIGNAL.size)
            }
        ) {
            is TunDescriptorIo.Count -> {
                if (write.byteCount == CLOSE_SIGNAL.size) {
                    TunDescriptorSignal.Signalled
                } else {
                    TunDescriptorSignal.Failed(
                        IOException("Short TUN wake write: ${write.byteCount}"),
                    )
                }
            }

            TunDescriptorIo.WouldBlock -> TunDescriptorSignal.Signalled
            is TunDescriptorIo.Interrupted -> TunDescriptorSignal.Interrupted(write.cause)
            is TunDescriptorIo.Failed -> TunDescriptorSignal.Failed(write.cause)
        }
    }

    override fun close(descriptor: FileDescriptor) {
        Os.close(descriptor)
    }

    private inline fun nativeIo(
        operation: String,
        syscall: () -> Int,
    ): TunDescriptorIo {
        return try {
            TunDescriptorIo.Count(syscall())
        } catch (failure: ErrnoException) {
            when {
                // Android's API facade exposes EAGAIN; Linux defines EWOULDBLOCK to the same
                // numeric value for the descriptor families used here.
                failure.errno == OsConstants.EAGAIN -> TunDescriptorIo.WouldBlock

                failure.errno == OsConstants.EINTR -> TunDescriptorIo.Interrupted(failure)
                else -> TunDescriptorIo.Failed(nativeFailure(operation, failure))
            }
        } catch (failure: InterruptedIOException) {
            TunDescriptorIo.Interrupted(failure)
        }
    }

    private fun nativeFailure(operation: String, failure: ErrnoException): IOException =
        IOException("TUN $operation failed with errno ${failure.errno}", failure)

    private val CLOSE_SIGNAL = byteArrayOf(1)
    private val POLL_TERMINAL_EVENTS =
        OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL

    private companion object {
        /*
         * API 28 omits these names from OsConstants, but socketpair accepts the stable Linux UAPI
         * values. Defining them locally preserves atomic nonblocking/close-on-exec creation at the
         * declared platform floor.
         */
        const val LINUX_SOCK_NONBLOCK = 0x800
        const val LINUX_SOCK_CLOEXEC = 0x80000
        const val POLL_CANCELLATION_CHECK_MILLIS = 250
    }
}
