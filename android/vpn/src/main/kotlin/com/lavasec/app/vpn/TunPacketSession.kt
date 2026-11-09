package com.lavasec.app.vpn

import kotlinx.coroutines.CancellationException

internal interface TunPacketSession : AutoCloseable {
    suspend fun readPacket(into: ByteArray): TunRead
    fun tryWritePacket(packet: ByteArray): TunWriteAttempt
    suspend fun awaitWritable(): TunWritable
    override fun close()
}

internal sealed interface TunRead {
    data class Packet(val byteCount: Int) : TunRead {
        init {
            require(byteCount > 0) { "packet byte count must be positive" }
        }
    }

    data object EndOfStream : TunRead
    data object Closed : TunRead
    data class Failed(val cause: Throwable) : TunRead {
        init {
            require(cause !is CancellationException) { "cancellation must be thrown" }
        }
    }
}

internal sealed interface TunWriteAttempt {
    data object Written : TunWriteAttempt
    data object WouldBlock : TunWriteAttempt
    data object Closed : TunWriteAttempt
    data class Failed(val cause: Throwable) : TunWriteAttempt {
        init {
            require(cause !is CancellationException) { "cancellation must be thrown" }
        }
    }

    data class ShortWrite(val actualByteCount: Int) : TunWriteAttempt {
        init {
            require(actualByteCount >= 0) { "short write byte count must be non-negative" }
        }
    }
}

internal sealed interface TunWritable {
    data object Writable : TunWritable
    data object Closed : TunWritable
    data class Failed(val cause: Throwable) : TunWritable {
        init {
            require(cause !is CancellationException) { "cancellation must be thrown" }
        }
    }
}
