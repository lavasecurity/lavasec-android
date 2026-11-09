package com.lavasec.app.vpn.chained

import com.lavasec.app.vpn.engine.LavaWgResponse
import com.lavasec.app.vpn.engine.LavaWgSession
import com.lavasec.app.vpn.engine.LavaWgStats

/**
 * The chained engine owner's view of one WireGuard session.
 *
 * The JNI handle ([LavaWgSession]) is a concrete class whose construction loads
 * `liblavasec_wireguard_core.so`, so anything that depends on the class directly is
 * unreachable in a host JVM test. This interface keeps the owner's orchestration — the part
 * with the expensive failure modes — executable against a fake, and leaves the ABI call as
 * one adapter ([JniWireGuardSession]).
 *
 * **Confinement:** exactly like [LavaWgSession], no method here is thread-safe. Every call for
 * one instance must happen on that session's single serial dispatcher (see
 * [ChainedEngineOwner]); the owner's `INV-CHAIN-QUEUE-1` note states the contract.
 *
 * Deliberately minimal: only the operations the owner actually drives. `create`/`sessionNew`
 * is a factory concern owned by the configuration lifecycle, not by the data path, so the
 * interface begins after a session exists.
 */
internal interface WireGuardSession : AutoCloseable {

    /** Whether the underlying handle is still live. */
    val isOpen: Boolean

    /** Encrypts one outbound IP packet, or emits a keepalive when [packet] is empty. */
    fun encapsulate(packet: ByteArray): LavaWgResponse

    /**
     * Decrypts one inbound UDP datagram, or drains queued packets/control replies with an
     * empty [packet]. [sourceAddress] is the peer the datagram arrived from (0, 4, or 16
     * bytes); supplying it enables the engine's cookie DoS mitigation.
     */
    fun decapsulate(packet: ByteArray, sourceAddress: ByteArray): LavaWgResponse

    /** Advances engine timers; may produce a datagram to send. */
    fun tick(): LavaWgResponse

    /** Forces a handshake; may produce a datagram to send. */
    fun forceHandshake(): LavaWgResponse

    /** Already-produced adapter output, without another engine/timer call. The two-row adapter
     * can produce several responses for one operation; the single JNI adapter has none here. */
    fun takePendingResponse(): LavaWgResponse = LavaWgResponse.None

    /** Session health, or `null` when the handle is closed or the engine returned an error. */
    fun stats(): LavaWgStats?

    /** Authenticated transport-data activity; stack adapters require evidence from each branch. */
    fun provesPeerActivity(packet: ByteArray, response: LavaWgResponse): Boolean =
        packet.isNotEmpty() && (packet[0].toInt() and 0x1f) == 4 && response !is LavaWgResponse.Failure

    /** A physical-channel replacement invalidates evidence from the retired channels. */
    fun transportChanged() = Unit

    /** Releases the engine handle. Idempotent. */
    override fun close()
}

/**
 * The one production adapter: forwards to the JNI-backed [LavaWgSession].
 *
 * The adapter is intentionally a field copy with no logic of its own. Any policy the owner
 * needs (allowed-IPs enforcement, reconnect classification) lives in the owner or in the
 * foundation, so a second adapter for a test engine cannot silently diverge from the shipped
 * behaviour.
 */
internal class JniWireGuardSession(
    private val delegate: LavaWgSession,
) : WireGuardSession {

    override val isOpen: Boolean
        get() = delegate.isOpen

    override fun encapsulate(packet: ByteArray): LavaWgResponse =
        delegate.encapsulate(packet)

    override fun decapsulate(packet: ByteArray, sourceAddress: ByteArray): LavaWgResponse =
        delegate.decapsulate(packet, sourceAddress)

    override fun tick(): LavaWgResponse = delegate.tick()

    override fun forceHandshake(): LavaWgResponse = delegate.forceHandshake()

    override fun stats(): LavaWgStats? = delegate.stats()

    override fun close() = delegate.close()
}
