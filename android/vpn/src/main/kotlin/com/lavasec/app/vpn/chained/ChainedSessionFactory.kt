package com.lavasec.app.vpn.chained

import com.lavasec.app.platform.ChainedAvailabilityAuthority
import com.lavasec.app.platform.ChainedEnginePermit
import com.lavasec.app.vpn.engine.LavaWgSession

/**
 * Builds the one WireGuard session the data path drives.
 *
 * The JNI handle cannot exist in a host JVM test, so the composition depends on this factory and
 * the tests supply a fake. A `null` return means the engine refused the keys (the JNI
 * `sessionNew` contract) or the current availability verdict forbids the engine.
 */
internal fun interface ChainedWireGuardSessionFactory {
    fun create(): WireGuardSession?
}

/**
 * The production [ChainedWireGuardSessionFactory], wrapping the JNI-backed engine handle in the
 * owner's [JniWireGuardSession] adapter.
 *
 * **Key ownership is the caller's.** This factory holds the byte arrays it is given for the life
 * of the factory, because a reconnect rebuilds the session from the same material. Producing,
 * protecting, and zeroizing that material is the credential-lifecycle slice's job; this type
 * deliberately does not read ambient storage.
 *
 * **The permit is per-attempt, not per-factory.** A chained session can be rebuilt many times
 * (reconnect after outage, network handoff), and the account/device verdict that justified the
 * first session can lapse while the tunnel stays up. Holding one permit would let a downgraded
 * account reinstantiate the ~50 MB engine on the next attempt, so [create] mints a fresh
 * [ChainedEnginePermit] from the injected [ChainedAvailabilityAuthority] every time, and the
 * permit is additionally authority-bound and one-shot at the `LavaWgSession.create` boundary. A
 * lapsed entitlement or an ineligible device returns `null` before any engine work happens. This
 * is why the factory depends on the concrete authority (the live store-backed source), not on a
 * permit value or a synthetic provider.
 *
 * [sessionCreator] is the JNI seam: production leaves the default, tests inject a fake so the
 * per-attempt gate is exercised without the native library.
 */
internal class JniChainedWireGuardSessionFactory(
    private val availability: ChainedAvailabilityAuthority,
    private val privateKey: ByteArray,
    private val peerPublicKey: ByteArray,
    private val presharedKey: ByteArray? = null,
    private val keepaliveSeconds: Int = 0,
    private val sessionIndex: () -> Int = { 0 },
    private val sessionCreator: (
        permit: ChainedEnginePermit,
        privateKey: ByteArray,
        peerPublicKey: ByteArray,
        presharedKey: ByteArray?,
        keepaliveSeconds: Int,
        index: Int,
    ) -> WireGuardSession? = { permit, privateKey, peerPublicKey, presharedKey, keepaliveSeconds, index ->
        LavaWgSession.create(
            permit = permit,
            privateKey = privateKey,
            peerPublicKey = peerPublicKey,
            presharedKey = presharedKey,
            keepaliveSeconds = keepaliveSeconds,
            index = index,
        )?.let(::JniWireGuardSession)
    },
) : ChainedWireGuardSessionFactory {

    override fun create(): WireGuardSession? {
        val permit = ChainedEnginePermit.issue(availability) ?: return null
        // PER-BUILD COPIES, so the credential arrays this factory retains for the life of the
        // factory are never the arrays a build reads. The owner's clean stop zeroizes
        // `ChainedVpnCredentials` (which handed these arrays over by reference), and the
        // synchronous, non-cancellable ~50 MB allocation can outlive that stop on the off-queue
        // `sessionBuilds` scope; without a copy the zeroization mutates the key bytes mid-build.
        // The copies are scrubbed once `LavaWgSession.create` has returned, because the JNI ABI
        // copies them into Rust during `sessionNew` (`ThirdParty/wireguard-core/src/lib.rs`), so
        // nothing downstream holds a pointer into them afterwards — the same lifetime rule the iOS
        // `ChainedSessionCredentials.scrubSecrets()` states. The factory's own originals stay
        // intact for the next attempt.
        val privateKeyCopy = privateKey.copyOf()
        val peerPublicKeyCopy = peerPublicKey.copyOf()
        val presharedKeyCopy = presharedKey?.copyOf()
        return try {
            sessionCreator(
                permit,
                privateKeyCopy,
                peerPublicKeyCopy,
                presharedKeyCopy,
                keepaliveSeconds,
                sessionIndex(),
            )
        } finally {
            privateKeyCopy.fill(0)
            peerPublicKeyCopy.fill(0)
            presharedKeyCopy?.fill(0)
        }
    }
}

/** Reuses the same authority-bound allocation seam for each actual selected engine handle. */
internal class OrderedChainedWireGuardSessionFactory(
    private val credentials: ChainedVpnCredentials,
    private val factoryForRow: (ChainedVpnCredentials, Int) -> ChainedWireGuardSessionFactory = { row, index ->
        JniChainedWireGuardSessionFactory(row.availability, row.privateKeyForSession(), row.peerPublicKeyForSession(),
            row.presharedKeyForSession(), row.keepaliveSeconds, sessionIndex = { index })
    },
) : ChainedWireGuardSessionFactory {
    override fun create(): WireGuardSession? {
        val rows = listOfNotNull(credentials.entry, credentials)
        val sessions = mutableListOf<WireGuardSession>()
        var completed = false
        try {
            rows.forEachIndexed { index, row -> sessions += factoryForRow(row, index).create() ?: return null }
            val result = if (sessions.size == 1) sessions.single() else ChainedStackWireGuardSession(credentials.configuration, sessions.toList())
            completed = true
            return result
        } finally {
            if (!completed) sessions.forEach { runCatching { it.close() } }
        }
    }
}
