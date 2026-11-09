package com.lavasec.app.vpn.engine

/**
 * The op codes the WireGuard engine C ABI returns. Mirrors
 * `include/lavasec_wireguard_core.h` (`LAVA_WG_OP_*`) and the iOS
 * `WireGuardABI` Swift constants.
 */
enum class LavaWgOp(val code: Int) {
    /** Nothing produced; state advanced (also: drain complete). */
    NONE(0),

    /** The payload is a datagram to send to the peer. */
    WRITE_TO_NETWORK(1),

    /** The payload is a decrypted IPv4 packet for the tunnel. */
    WRITE_TO_TUNNEL_V4(2),

    /** The payload is a decrypted IPv6 packet for the tunnel (claim-and-drop policy). */
    WRITE_TO_TUNNEL_V6(3),
    ;

    companion object {
        fun fromCode(code: Int): LavaWgOp? = entries.firstOrNull { it.code == code }
    }
}

/** The typed errors the C ABI returns (`LAVA_WG_ERR_*`). */
enum class LavaWgError(val code: Int) {
    INVALID_ARGUMENT(-1),
    DESTINATION_BUFFER_TOO_SMALL(-2),
    NO_CURRENT_SESSION(-3),
    UNDER_LOAD(-4),

    /** One malformed/hostile datagram; drop it and continue. */
    PROTOCOL(-5),

    /** The session expired; reconnect logic escalates on this. */
    CONNECTION_EXPIRED(-6),
    INTERNAL(-7),
    PACKET_TOO_LARGE(-8),
    ;

    companion object {
        fun fromCode(code: Int): LavaWgError? = entries.firstOrNull { it.code == code }
    }
}

/** One engine call outcome: produced bytes, nothing, or a typed failure. */
sealed interface LavaWgResponse {
    /**
     * @param sourceAddress the decrypted packet's inner source, when the engine
     *   surfaced one (decapsulation) — used for uplink allowed-IPs enforcement;
     *   `null` for encapsulate/tick/force-handshake and unknown addresses.
     */
    data class Produced(
        val op: LavaWgOp,
        val packet: ByteArray,
        val sourceAddress: ByteArray? = null,
    ) : LavaWgResponse {
        // A data class compares byte arrays by reference; compare contents instead so two
        // decoded outcomes with the same bytes are equal (and usable as test expectations).
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Produced &&
                    op == other.op &&
                    packet.contentEquals(other.packet) &&
                    sourceAddress.contentEquals(other.sourceAddress))

        override fun hashCode(): Int {
            var result = op.hashCode()
            result = 31 * result + packet.contentHashCode()
            result = 31 * result + (sourceAddress?.contentHashCode() ?: 0)
            return result
        }
    }

    data object None : LavaWgResponse
    data class Failure(val error: LavaWgError) : LavaWgResponse
}

/** A decoded session-health snapshot (`lava_wg_session_stats`). */
data class LavaWgStats(
    /** Milliseconds since the last completed handshake; -1 when no session is established. */
    val timeSinceLastHandshakeMs: Long,
    val txBytes: Long,
    val rxBytes: Long,
    /** Engine RTT estimate in ms; -1 when unknown. */
    val estimatedRoundTripMs: Int,
    /** Engine loss estimate in `[0, 1]`. */
    val estimatedLoss: Float,
)

/**
 * Pure decoding of the JNI tagged-buffer result (`[tag] ++ payload`) into the typed
 * model. Kept free of any JNI/Android reference so it is unit-testable on the JVM
 * (the JNI object itself cannot load its `.so` in a host test).
 */
object LavaWgCodec {
    /**
     * @param bytes the raw `byte[]` returned by a native op call, or `null` when the
     *   JNI layer could not allocate the result (an internal failure).
     *
     * Wire shape: `[tag][addrLen][addr...][payload...]`, where `addrLen` is 0, 4, or 16.
     */
    fun decode(bytes: ByteArray?): LavaWgResponse {
        if (bytes == null || bytes.isEmpty()) return LavaWgResponse.Failure(LavaWgError.INTERNAL)
        val tag = bytes[0].toInt()
        if (tag < 0) {
            return LavaWgResponse.Failure(LavaWgError.fromCode(tag) ?: LavaWgError.INTERNAL)
        }
        val op = LavaWgOp.fromCode(tag) ?: return LavaWgResponse.Failure(LavaWgError.INTERNAL)
        if (bytes.size < 2) return LavaWgResponse.Failure(LavaWgError.INTERNAL)
        val addressLength = bytes[1].toInt() and 0xFF
        if (addressLength != 0 && addressLength != 4 && addressLength != 16) {
            return LavaWgResponse.Failure(LavaWgError.INTERNAL)
        }
        val payloadStart = 2 + addressLength
        if (payloadStart > bytes.size) return LavaWgResponse.Failure(LavaWgError.INTERNAL)
        if (op == LavaWgOp.NONE) return LavaWgResponse.None
        val sourceAddress = if (addressLength == 0) null else bytes.copyOfRange(2, payloadStart)
        return LavaWgResponse.Produced(op, bytes.copyOfRange(payloadStart, bytes.size), sourceAddress)
    }

    /**
     * Decodes `lava_wg_session_stats`: five longs, or a single negative error code.
     */
    fun decodeStats(values: LongArray?): LavaWgStats? {
        if (values == null) return null
        if (values.size == 1) return null // error code
        if (values.size < 5) return null
        return LavaWgStats(
            timeSinceLastHandshakeMs = values[0],
            txBytes = values[1],
            rxBytes = values[2],
            estimatedRoundTripMs = values[3].toInt(),
            estimatedLoss = Float.fromBits(values[4].toInt()),
        )
    }

    /** Encodes the `sourceAddress` argument: empty for unknown, else 4 or 16 bytes. */
    fun requireValidPeerAddress(address: ByteArray): ByteArray {
        require(address.size == 0 || address.size == 4 || address.size == 16) {
            "peer address must be 0, 4, or 16 bytes"
        }
        return address
    }
}
