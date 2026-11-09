package com.lavasec.app.vpn.chained

import com.lavasec.app.foundation.chained.ChainedPacketQueueLimits
import com.lavasec.app.vpn.ChainedEngineQueue
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The production [ChainedDriverTimers]: one-shot deadlines, the per-attempt watchdog, the retry
 * backoff, and the repeating engine pump, all delivered on the engine queue.
 *
 * [arm] schedules a one-shot task that hops to the engine queue and runs [fire] there; the
 * driver's serial guard is what makes a cancellation that lost the race inert (a cancelled
 * handler already handed to the queue cannot be retracted). [scheduleTick] turns the repeating
 * engine pump on/off; the tick handler is invoked on the engine queue. A single scheduler thread
 * means no two engine-queue hops overlap, so a slow engine tick delays the next one rather than
 * stacking them.
 */
internal class EngineQueueChainedDriverTimers(
    private val engineQueue: ChainedEngineQueue,
    private val onTick: () -> Unit,
    /**
     * A thrown engine call during the tick is terminal for the tunnel lifecycle, exactly as it was
     * when [ChainedTunnelRuntime] owned the tick loop: the repeating task must not die silently
     * (the scheduler suppresses a throwing fixed-delay task), so the failure is reported here and
     * the pump stays alive.
     */
    private val onTickFailure: (Throwable) -> Unit = {},
    private val scheduler: ScheduledExecutorService = newScheduler(),
) : ChainedDriverTimers {

    private var tickTask: ScheduledFuture<*>? = null

    override fun arm(afterMillis: Long, fire: () -> Unit): ChainedArmedTimer {
        val future = scheduler.schedule(
            { engineQueue.runSerial(fire) },
            afterMillis.coerceAtLeast(0L),
            TimeUnit.MILLISECONDS,
        )
        return object : ChainedArmedTimer {
            override fun cancel() {
                future.cancel(false)
            }
        }
    }

    override fun scheduleTick(intervalMillis: Long?) {
        tickTask?.cancel(false)
        tickTask = null
        if (intervalMillis == null) return
        val interval = intervalMillis.coerceAtLeast(1L)
        tickTask = scheduler.scheduleWithFixedDelay(
            {
                try {
                    engineQueue.runSerial(onTick)
                } catch (failure: Throwable) {
                    runCatching { onTickFailure(failure) }
                }
            },
            interval,
            interval,
            TimeUnit.MILLISECONDS,
        )
    }

    /**
     * Stops the repeating pump and the scheduler thread. Called when the runtime retires, so a
     * stop/reload/fallback/terminal failure does not leave the `lavasec-chained-timers` thread
     * alive for the rest of the process (one leaked thread per lifecycle).
     */
    override fun shutdown() {
        tickTask?.cancel(false)
        tickTask = null
        scheduler.shutdownNow()
    }

    private companion object {
        fun newScheduler(): ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "lavasec-chained-timers").apply { isDaemon = true }
            }
    }
}

/**
 * The production [ChainedEngineDispatcher] that confines the chained engine to the SAME serial
 * owner as the outage driver — the Android form of iOS's one `ChainedEngineQueue` shared by the
 * driver and every runner it builds.
 *
 * Android used a separate per-session `ExecutorChainedEngineDispatcher` before this. That split
 * is fatal once the driver is wired: the driver calls `runner.tick()` synchronously from the
 * engine queue, and the session would report its end back through the driver from the dispatcher
 * thread. Two serializations that each block on the other is the AB-BA deadlock. Routing the
 * engine onto the lifecycle queue through `runSerial` (non-superseding, re-entrant) makes a
 * driver→runner call inline and a runner→driver session-end report inline, so one thread owns
 * both and the deadlock cannot form. The engine is never a lifecycle operation, so `runSerial`
 * never invalidates an in-flight establishment.
 */
internal class EngineQueueChainedDispatcher(
    private val queue: ChainedEngineQueue,
) : ChainedEngineDispatcher {
    override fun <T> run(block: () -> T): T = queue.runSerial(block)
}

/**
 * A [ChainedPeerSocket] over an already-protected, underlying-`Network`-bound [DatagramSocket].
 *
 * Binding and `VpnService.protect` are the caller's responsibility and must have happened before
 * construction — this adapter only moves bytes, so the same socket-ordering rules the resolver
 * path uses are not re-implemented here. `send` reports failure as `false` (a transport error is
 * the data path's terminal signal, not an exception to unwind through the engine); `receive`
 * returns `null` when the socket closes, which is how [ChainedTunnelRuntime.close] unblocks its
 * receive loop.
 */
internal class ProtectedUnderlyingPeerSocket(
    private val socket: DatagramSocket,
    private val peerAddress: InetSocketAddress,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxDatagramBytes: Int = ChainedPacketQueueLimits.MAXIMUM_DATAGRAM_BYTE_COUNT,
) : ChainedPeerSocket {

    init {
        require(maxDatagramBytes > 0) { "datagram buffer must be positive" }
    }

    override fun send(datagram: ByteArray): Boolean = try {
        socket.send(DatagramPacket(datagram, datagram.size, peerAddress))
        true
    } catch (_: IOException) {
        false
    }

    override suspend fun receive(): ChainedPeerDatagram? = withContext(ioDispatcher) {
        val buffer = ByteArray(maxDatagramBytes)
        val packet = DatagramPacket(buffer, buffer.size)
        try {
            socket.receive(packet)
        } catch (_: SocketException) {
            return@withContext null
        } catch (_: IOException) {
            return@withContext null
        }
        val source = packet.address?.address ?: return@withContext null
        ChainedPeerDatagram(
            packet = packet.data.copyOfRange(packet.offset, packet.offset + packet.length),
            sourceAddress = source,
        )
    }

    override fun close() {
        socket.close()
    }
}
