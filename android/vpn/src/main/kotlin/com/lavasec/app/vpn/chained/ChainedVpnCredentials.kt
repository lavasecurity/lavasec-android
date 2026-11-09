package com.lavasec.app.vpn.chained

import com.lavasec.app.foundation.chained.ChainedUpstreamConfiguration
import com.lavasec.app.platform.ChainedAvailabilityAuthority
import com.lavasec.app.platform.ChainedUpstreamSecretMaterial

/**
 * The session-lifecycle half of the device-eligibility store, narrowed to what the chained
 * runtime is allowed to do.
 *
 * The runtime records that a chained session started (so a later low-memory kill can be
 * attributed to it) and that it stopped through its own funnel (so the streak resets and the
 * witness retires). It must never be able to read the memory facts or trip the exclusion: the
 * readiness gate owns those, and handing the runtime the whole store would widen the surface for
 * no reason.
 */
interface ChainedSessionEligibilityLifecycle {
    /**
     * Persists the durable in-progress witness.
     *
     * @return `true` only when the witness was persisted. The caller MUST NOT allocate the engine
     *   on `false`: a kill during allocation would otherwise be unattributable, defeating the
     *   allocation-time crash-loop protection the witness provides.
     */
    fun markChainedSessionStarted(nowEpochMillis: Long): Boolean

    /**
     * Retires the witness THIS runtime recorded, identified by the start epoch it captured when it
     * called [markChainedSessionStarted].
     *
     * A make-before-break reload lets a candidate record a witness while the predecessor is still
     * alive, and a candidate that later fails establishment marks then clears its own. An
     * unconditional clear would retire the LIVE runtime's witness in both cases, leaving it
     * unattributable and unnoticed by the low-memory exclusion breaker. [owningStartEpochMillis]
     * lets the store clear ONLY the exact witness this runtime wrote and preserve any other — the
     * Android form of the iOS `settleTermination(owningLifecycleID:)` fence, matched by identity
     * rather than wall-clock order so a backward clock move cannot drop the live candidate's
     * witness.
     */
    fun markChainedSessionStoppedCleanly(nowEpochMillis: Long, owningStartEpochMillis: Long)
}

/**
 * Everything the chained data path needs to build its engine, handed from the credential graph
 * to the VPN runtime for exactly one session generation.
 *
 * This is the cross-module carrier for the secret half. It exists because the chained data path
 * lives in `:vpn` and the credential repository lives in `:app`; the app reads the protected
 * material and passes it here, and the runtime never re-reads ambient storage (mirroring the iOS
 * `ChainedSessionCredentialReader`/`ChainedUpstreamKeychainSecretStore` split).
 *
 * The key bytes are held as owned arrays and zeroized on [close]; the runtime must close this
 * once the session is gone. [availability] is the concrete authority so the session factory can
 * mint a fresh one-shot permit per engine attempt — a lapsed entitlement is observed at the next
 * create.
 */
class ChainedVpnCredentials private constructor(
    val configuration: ChainedUpstreamConfiguration,
    private val privateKeyBytes: ByteArray,
    private val peerPublicKeyBytes: ByteArray,
    private val presharedKeyBytes: ByteArray?,
    val availability: ChainedAvailabilityAuthority,
    val lifecycle: ChainedSessionEligibilityLifecycle,
    internal val entry: ChainedVpnCredentials? = null,
    val generation: Long = 0L,
    val dnsFallbackEnabled: Boolean = false,
) : AutoCloseable {

    /** The keepalive the session factory arms from the configuration. */
    val keepaliveSeconds: Int get() = configuration.persistentKeepaliveSeconds

    internal fun privateKeyForSession(): ByteArray = privateKeyBytes

    internal fun peerPublicKeyForSession(): ByteArray = peerPublicKeyBytes

    internal fun presharedKeyForSession(): ByteArray? = presharedKeyBytes

    override fun close() {
        entry?.close()
        privateKeyBytes.fill(0)
        peerPublicKeyBytes.fill(0)
        presharedKeyBytes?.fill(0)
    }

    companion object {
        /** The WireGuard key width, matching the engine ABI. */
        private const val KEY_BYTE_COUNT = ChainedUpstreamConfiguration.KEY_BYTE_COUNT

        /**
         * Builds one credential set. Throws when a key has the wrong width: a caller that got
         * this far has already read the credential store, and a malformed key is a programming
         * error that must stop before the engine rather than be handed to BoringTun.
         */
        fun create(
            configuration: ChainedUpstreamConfiguration,
            privateKey: ByteArray,
            peerPublicKey: ByteArray,
            presharedKey: ByteArray?,
            availability: ChainedAvailabilityAuthority,
            lifecycle: ChainedSessionEligibilityLifecycle,
            dnsFallbackEnabled: Boolean = false,
        ): ChainedVpnCredentials {
            require(configuration.orderedHops.size == 1) { "use coherent material transfer for ordered credentials" }
            require(privateKey.size == KEY_BYTE_COUNT) { "private key must be 32 bytes" }
            require(peerPublicKey.size == KEY_BYTE_COUNT) { "peer public key must be 32 bytes" }
            require(presharedKey == null || presharedKey.size == KEY_BYTE_COUNT) {
                "preshared key must be 32 bytes"
            }
            return ChainedVpnCredentials(
                configuration = configuration,
                privateKeyBytes = privateKey.copyOf(),
                peerPublicKeyBytes = peerPublicKey.copyOf(),
                presharedKeyBytes = presharedKey?.copyOf(),
                availability = availability,
                lifecycle = lifecycle,
                dnsFallbackEnabled = dnsFallbackEnabled && configuration.effectiveRoutingPolicy == com.lavasec.app.foundation.chained.ChainedRoutingPolicy.SPLIT_TUNNEL,
            )
        }

        /**
         * Builds one credential set from the credential store's [material], zeroizing every
         * intermediate it creates.
         *
         * [create] defensively copies each key, so the copies taken here — and the store's own
         * arrays — would otherwise linger until the collector runs. This entry point transfers the
         * copies into the credentials and then clears all of them in a `finally`: the private and
         * pre-shared copies, the caller-supplied [peerPublicKey] buffer, and [material] itself.
         * [material] must not be used after this call.
         */
        fun createFromMaterial(
            configuration: ChainedUpstreamConfiguration,
            material: ChainedUpstreamSecretMaterial,
            peerPublicKey: ByteArray,
            availability: ChainedAvailabilityAuthority,
            lifecycle: ChainedSessionEligibilityLifecycle,
            dnsFallbackEnabled: Boolean = false,
        ): ChainedVpnCredentials {
            var entryCredentials: ChainedVpnCredentials? = null
            val privateKey = material.privateKeyCopy()
            val presharedKey = material.presharedKeyCopy()
            try {
                require(peerPublicKey.size == KEY_BYTE_COUNT) { "peer key must be 32 bytes" }
                require(material.rowCount == configuration.orderedHops.size) { "ordered metadata and credentials must agree" }
                configuration.precedingHops.singleOrNull()?.let { entryConfiguration ->
                    val entryPrivate = material.rowPrivateKeyCopy(0)
                    val entryPSK = material.rowPresharedKeyCopy(0)
                    val entryPublic = requireNotNull(com.lavasec.app.foundation.chained.ChainedUpstreamConfigurationValidator.decodedKey(entryConfiguration.peerPublicKey))
                    try { entryCredentials = create(entryConfiguration, entryPrivate, entryPublic, entryPSK, availability, lifecycle) }
                    finally { entryPrivate.fill(0); entryPSK?.fill(0); entryPublic.fill(0) }
                }
                return ChainedVpnCredentials(configuration, privateKey.copyOf(), peerPublicKey.copyOf(), presharedKey?.copyOf(), availability, lifecycle, entryCredentials, material.generation, dnsFallbackEnabled && configuration.effectiveRoutingPolicy == com.lavasec.app.foundation.chained.ChainedRoutingPolicy.SPLIT_TUNNEL)
            } catch (failure: Throwable) {
                entryCredentials?.close()
                throw failure
            } finally {
                privateKey.fill(0)
                presharedKey?.fill(0)
                peerPublicKey.fill(0)
                material.close()
            }
        }
    }
}
