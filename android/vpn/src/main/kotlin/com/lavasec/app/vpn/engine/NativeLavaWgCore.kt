package com.lavasec.app.vpn.engine

/**
 * Thin JNI binding to the vendored WireGuard engine (`liblavasec_wireguard_core.so`,
 * built by `scripts/build-wireguard-core-android.sh` into `:vpn` jniLibs).
 *
 * These functions are raw: they copy byte arrays across the boundary and call the
 * proven `lava_wg_*` C ABI. Callers should prefer [LavaWgSession], which owns the
 * handle lifetime and decodes the tagged results via [LavaWgCodec].
 *
 * **Queue confinement:** the engine has no internal concurrency design, so every
 * call for one [handle] must happen on one serial dispatcher.
 */
internal object NativeLavaWgCore {
    init {
        System.loadLibrary("lavasec_wireguard_core")
    }

    /**
     * Creates a session. Returns 0 when the keys are the wrong width or the engine
     * refuses the input. [presharedKey] is empty when the configuration carries none.
     */
    external fun sessionNew(
        privateKey: ByteArray,
        peerPublicKey: ByteArray,
        presharedKey: ByteArray,
        keepaliveSeconds: Int,
        index: Int,
    ): Long

    external fun sessionFree(handle: Long)

    /** `[tag] ++ payload`; see [LavaWgCodec.decode]. `packet` may be empty (keepalive). */
    external fun encapsulate(handle: Long, packet: ByteArray): ByteArray?

    external fun decapsulate(handle: Long, packet: ByteArray, sourceAddress: ByteArray): ByteArray?

    /** Advances timers; may produce a datagram to send. */
    external fun tick(handle: Long): ByteArray?

    external fun forceHandshake(handle: Long): ByteArray?

    /** Five longs, or a single negative error code; see [LavaWgCodec.decodeStats]. */
    external fun sessionStats(handle: Long): LongArray?
}
