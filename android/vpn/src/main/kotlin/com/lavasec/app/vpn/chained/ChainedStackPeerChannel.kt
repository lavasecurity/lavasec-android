package com.lavasec.app.vpn.chained

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** One or two protected physical channels. The row tag is process-local and removed before UDP
 * send; it never appears on the WireGuard wire. Nested stacks own only the entry channel.
 * The receive queue is count bounded; each reader applies back-pressure to its own socket.
 */
internal class ChainedStackPeerChannel(private val channels: List<ChainedUpstreamChannel>, dispatcher: CoroutineDispatcher = Dispatchers.IO) : ChainedUpstreamChannel {
    init { require(channels.size in 1..2) }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val received = Channel<ChainedPeerDatagram?>(32)
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    override fun send(datagram: ByteArray): Boolean {
        val tagged = ChainedRowDatagram.decode(datagram) ?: return false
        return !closed.get() && channels.getOrNull(tagged.first)?.send(tagged.second) == true
    }

    override suspend fun receive(): ChainedPeerDatagram? {
        if (started.compareAndSet(false, true)) channels.forEachIndexed { index, channel ->
            scope.launch {
                while (!closed.get()) {
                    val packet = try { channel.receive() } catch (failure: kotlinx.coroutines.CancellationException) { throw failure } catch (_: Exception) { null }
                    received.send(packet?.let { ChainedPeerDatagram(ChainedRowDatagram.encode(index, it.packet), it.sourceAddress) })
                    if (packet == null) return@launch
                }
            }
        }
        return received.receiveCatching().getOrNull()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        channels.forEach { runCatching { it.close() } }
        scope.cancel()
        received.close()
    }
}

/** Private native routing envelope. It is not a network packet and cannot be emitted to UDP. */
internal object ChainedRowDatagram {
    fun encode(index: Int, packet: ByteArray): ByteArray { require(index in 0..1); return byteArrayOf(0x4c, index.toByte()) + packet }
    fun decode(packet: ByteArray): Pair<Int, ByteArray>? = if (packet.size > 2 && packet[0] == 0x4c.toByte() && packet[1].toInt() in 0..1)
        packet[1].toInt() to packet.copyOfRange(2, packet.size) else null
}
