package com.lavasec.app.vpn.engine

import com.lavasec.app.platform.ChainedEnginePermit

/**
 * Owns one WireGuard engine session handle and exposes the typed operations the
 * chained data path needs. Not thread-safe by design: the engine's caller contract
 * is one serial queue per session, so callers must confine every call here to a
 * single dispatcher (the tunnel's WireGuard executor, never the DNS state path).
 *
 * Create with [create] (null when the engine rejects the configuration) and release
 * with [close]. After [close], [isOpen] is false and every operation is a no-op
 * failure rather than touching a freed handle.
 */
class LavaWgSession private constructor(private var handle: Long) : AutoCloseable {

    val isOpen: Boolean get() = handle != 0L

    /** Encrypts one outbound IP packet, or emits a keepalive when [packet] is empty. */
    fun encapsulate(packet: ByteArray): LavaWgResponse = operate {
        NativeLavaWgCore.encapsulate(it, packet)
    }

    /**
     * Decrypts one inbound UDP datagram, or drains queued packets/control replies
     * with an empty [packet]. [sourceAddress] is the peer the datagram arrived from
     * (0, 4, or 16 bytes); supplying it enables the engine's cookie DoS mitigation.
     */
    fun decapsulate(packet: ByteArray, sourceAddress: ByteArray = EMPTY): LavaWgResponse {
        LavaWgCodec.requireValidPeerAddress(sourceAddress)
        return operate { NativeLavaWgCore.decapsulate(it, packet, sourceAddress) }
    }

    fun tick(): LavaWgResponse = operate { NativeLavaWgCore.tick(it) }

    fun forceHandshake(): LavaWgResponse = operate { NativeLavaWgCore.forceHandshake(it) }

    /** Session health, or null when the handle is closed or the engine returned an error. */
    fun stats(): LavaWgStats? {
        if (handle == 0L) return null
        return LavaWgCodec.decodeStats(NativeLavaWgCore.sessionStats(handle))
    }

    override fun close() {
        if (handle != 0L) {
            NativeLavaWgCore.sessionFree(handle)
            handle = 0L
        }
    }

    private inline fun operate(call: (Long) -> ByteArray?): LavaWgResponse {
        if (handle == 0L) return LavaWgResponse.Failure(LavaWgError.INVALID_ARGUMENT)
        return LavaWgCodec.decode(call(handle))
    }

    companion object {
        private val EMPTY = ByteArray(0)

        /**
         * Creates the ~50 MB engine session; [permit] is a REQUIRED, one-shot gate.
         *
         * [ChainedEnginePermit] can only be minted from the concrete, store-backed
         * `com.lavasec.app.platform.ChainedAvailabilityAuthority`, and
         * `ChainedAvailability.Eligible` cannot be constructed outside `:platform-contracts`, so
         * an ineligible verdict (not entitled, sub-floor memory, memory-kill-excluded, or
         * unreadable device state) yields no permit and this factory cannot be called at all.
         *
         * The permit is CONSUMED here, and consumption re-reads the bound authority: a permit
         * retained after entitlement lapses cannot construct, and a spent permit cannot construct
         * a second session. This closes the path a caller would otherwise have to reuse a cached
         * permit while bypassing the revalidating session factory.
         *
         * @param presharedKey `null` or empty when the configuration has none.
         * @return a session, or `null` when the permit is spent/ineligible or the engine rejects
         *   the keys.
         */
        fun create(
            permit: ChainedEnginePermit,
            privateKey: ByteArray,
            peerPublicKey: ByteArray,
            presharedKey: ByteArray? = null,
            keepaliveSeconds: Int = 0,
            index: Int = 0,
        ): LavaWgSession? {
            if (!permit.consumeForConstruction()) return null
            val handle = NativeLavaWgCore.sessionNew(
                privateKey = privateKey,
                peerPublicKey = peerPublicKey,
                presharedKey = presharedKey ?: EMPTY,
                keepaliveSeconds = keepaliveSeconds,
                index = index,
            )
            return if (handle == 0L) null else LavaWgSession(handle)
        }
    }
}
