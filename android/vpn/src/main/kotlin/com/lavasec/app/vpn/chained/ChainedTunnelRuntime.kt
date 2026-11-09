package com.lavasec.app.vpn.chained

import com.lavasec.app.foundation.chained.ChainedPacketQueueLimits
import com.lavasec.app.vpn.TunRead
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.coroutineContext

/**
 * Drives the TUN read loop and the peer-socket receive loop of a [ChainedDataPath] into the
 * outage driver.
 *
 * This is deliberately a thin driver, not a second runtime. It owns no VPN lifecycle, no
 * foreground state, no protection publication, no engine tick, and no retry policy: the control
 * plane keeps the lifecycle and the [ChainedOutageDriver] owns the attempts (its `ChainedDriverTimers`
 * arm the engine pump and the one-shot deadlines). It exists so production gets the concurrent
 * loops that feed the driver's synchronous core, mirroring the iOS runtime's role.
 *
 * ## Threading
 *
 * [scopeDispatcher] runs the I/O loops; the driver and every engine call it makes run on the one
 * serial `ChainedEngineQueue` (`INV-CHAIN-QUEUE-1` is now one serialization for the chained path).
 * [readTunPacket] and the socket receive are the only suspending work, so a blocking TUN API
 * cannot stall the engine queue.
 *
 * A terminal failure cancels the loops and invokes `onTerminalFailure`. Releasing the owned
 * resources is [close], which the caller runs after observing the failure: freeing the engine
 * from inside an engine callback (the failure may be detected on the engine queue) would re-enter
 * the owner mid-operation.
 */
internal class ChainedTunnelRuntime(
    private val driver: ChainedOutageDriver,
    private val dataPath: ChainedDataPath,
    private val scopeDispatcher: CoroutineDispatcher,
    private val onTerminalFailure: (ChainedRuntimeFailure) -> Unit = {},
    private val readBufferBytes: Int = ChainedPacketQueueLimits.MAXIMUM_IP_PACKET_BYTE_COUNT,
) : AutoCloseable {

    init {
        require(readBufferBytes > 0) { "TUN read buffer must be non-empty" }
    }

    private val scope = CoroutineScope(SupervisorJob() + scopeDispatcher)
    private val started = AtomicBoolean(false)
    private val terminal = AtomicBoolean(false)

    private var readerJob: Job? = null
    private var receiverJob: Job? = null

    /**
     * The engine lifecycle, so the control plane can distinguish "a chained session exists" from
     * "the chained path is actually established and carrying traffic".
     */
    val state: StateFlow<ChainedEngineState> get() = dataPath.state

    /** Starts the two packet-path loops. May be called once. */
    fun start() {
        check(started.compareAndSet(false, true)) { "chained runtime can only start once" }
        readerJob = scope.launch(CoroutineName("chained-tun-reader")) { readerLoop() }
        receiverJob = scope.launch(CoroutineName("chained-peer-receiver")) { receiverLoop() }
    }

    /** Reports an underlay transition to the outage driver on the engine queue. */
    fun pathChanged(satisfied: Boolean, interfacesChanged: Boolean) {
        driver.pathChanged(satisfied, interfacesChanged)
    }

    private suspend fun readerLoop() {
        val buffer = ByteArray(readBufferBytes)
        while (coroutineContext.isActive && !terminal.get()) {
            val read = try {
                dataPath.readTunPacket(buffer)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                fail(ChainedRuntimeFailure.TUN_READ_FAILED)
                return
            }
            when (read) {
                is TunRead.Packet -> {
                    val packet = buffer.copyOf(read.byteCount)
                    val accepted = runEngineStep { driver.handleOutboundBatch(listOf(packet)) }
                    if (!accepted) return
                }
                TunRead.EndOfStream,
                TunRead.Closed,
                is TunRead.Failed,
                -> {
                    fail(ChainedRuntimeFailure.TUN_READ_FAILED)
                    return
                }
            }
        }
    }

    private suspend fun receiverLoop() {
        while (coroutineContext.isActive && !terminal.get()) {
            val datagram = try {
                dataPath.receivePeerDatagram()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                fail(ChainedRuntimeFailure.PEER_SOCKET_FAILED)
                return
            }
            if (datagram == null) {
                fail(ChainedRuntimeFailure.PEER_SOCKET_FAILED)
                return
            }
            if (!runEngineStepUnit {
                    dataPath.onPeerDatagram(datagram.packet, datagram.sourceAddress)
                }
            ) {
                return
            }
        }
    }

    /**
     * Runs one engine/sink step, converting any thrown exception into a terminal failure.
     *
     * @return whether the step completed; `false` means the runtime has failed (or the driver was
     *   retired) and the caller must stop its loop.
     */
    private fun runEngineStep(step: () -> Boolean): Boolean = try {
        step()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        fail(ChainedRuntimeFailure.ENGINE_FAILED)
        false
    }

    /** A [runEngineStep] for a step whose value is not a stop signal (the inbound path). */
    private fun runEngineStepUnit(step: () -> Unit): Boolean = try {
        step()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        fail(ChainedRuntimeFailure.ENGINE_FAILED)
        false
    }

    private fun fail(failure: ChainedRuntimeFailure) {
        if (terminal.compareAndSet(false, true)) {
            scope.cancel()
            onTerminalFailure(failure)
        }
    }

    /**
     * Reports a terminal engine failure from outside the packet loops (for example a replacement
     * session install that failed while this owner was still live), reusing the one-shot terminal
     * transition so the control plane handles it deliberately instead of the failure escaping a
     * coroutine.
     */
    fun terminate(failure: ChainedRuntimeFailure) = fail(failure)

    override fun close() {
        terminal.set(true)
        scope.cancel()
        runCatching { driver.retire() }
        dataPath.close()
    }
}
