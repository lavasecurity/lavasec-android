package com.lavasec.app.vpn.chained

/**
 * Why the chained data path stopped. Log/health identifiers, never user copy.
 *
 * A terminal reason is reported to the caller, which owns the rebuild-to-DNS-only decision. The
 * per-attempt outage/reconnect policy is the [ChainedOutageDriver]'s, not this data path's; a
 * session that merely ends reports a [com.lavasec.app.foundation.chained.ChainedSessionEndCause]
 * to the driver, and only a data-path failure (a socket/TUN fault) travels here.
 */
internal enum class ChainedRuntimeFailure {
    /** The engine refused the configuration, or the JNI session could not be created. */
    ENGINE_UNAVAILABLE,

    /**
     * The session factory refused to create a session because authorization lapsed.
     *
     * A retry mints a fresh permit per attempt; a revoked entitlement or an ineligible device
     * makes the factory return `null` (or the JNI session constructor refuse the keys). Unlike the
     * initial build's [ENGINE_UNAVAILABLE] — where no session exists to fall back from — a
     * scheduled retry has a live full-route tunnel, so the control plane surrenders to the working
     * DNS-only runtime instead of terminating protection.
     */
    AUTHORIZATION_REFUSED,

    /** An engine or sink call threw while processing traffic. */
    ENGINE_FAILED,

    /** The TUN read side ended or failed. */
    TUN_READ_FAILED,

    /** A decrypted packet could not be written back to the TUN. */
    TUN_WRITE_FAILED,

    /** The protected peer socket failed to send or ended while receiving. */
    PEER_SOCKET_FAILED,
}

/** Thrown when the composition cannot build its engine session. */
internal class ChainedRuntimeStartException(
    val failure: ChainedRuntimeFailure,
) : IllegalStateException(failure.name)
