package com.lavasec.app.platform

import kotlinx.coroutines.CancellationException
import com.lavasec.app.foundation.chained.ChainedConfigurationIssue
import com.lavasec.app.foundation.chained.ChainedUpstreamConfiguration

/**
 * A gate-cleared chained upstream together with the secret half the engine needs.
 *
 * Produced only by [ChainedComposition.resolveActivation] after the whole readiness gate and the
 * credential store both pass, so holding one is proof the engine may be constructed. The caller
 * owns [secrets] and must not retain it beyond the engine's lifetime.
 */
class ResolvedChainedActivation(
    val configuration: ChainedUpstreamConfiguration,
    val identity: ChainedUpstreamIdentity,
    val secrets: ChainedUpstreamSecretMaterial,
    val dnsFallbackEnabled: Boolean = false,
)

/**
 * The per-credential-generation chained-upstream composition root.
 *
 * It owns exactly the four durable inputs the chained gate is built from — the entitlement port,
 * the device-eligibility store, the credential-protected configuration repository, and the
 * user's preference store — and derives the availability authority from the first two through
 * the reviewed [ChainedAvailabilityAuthority.production] seam. Nothing else may build an
 * authority (its constructor is `internal`), so this class is the one auditable place where the
 * account/device gate is wired to real sources.
 *
 * ## Nothing constructs the engine until the gate clears
 *
 * [resolveActivation] evaluates the cheap facts first and only then reads the key material. In
 * the DNS-only case it never touches the credential store's secret half, so a disabled,
 * ineligible, or unconfigured device does no KeyStore work and no ~50 MB engine work. The
 * engine-permit revalidation inside the session factory is the structural backstop behind this;
 * this method is the ordinary path that keeps the backstop from ever being reached.
 *
 * ## One instance per credential generation
 *
 * Built by the application graph factory for the unlocked credential generation and retired with
 * it. The backing DataStores are process-wide (the factory owns them) so a graph rotation
 * replaces the composition without racing a second DataStore on the same file.
 */
class ChainedComposition private constructor(
    val entitlement: ChainedEntitlementPort,
    val eligibilityStore: ChainedDeviceEligibilityStore,
    val availability: ChainedAvailabilityAuthority,
    val configurationRepository: ChainedUpstreamConfigurationRepository,
    val preferenceStore: ChainedPreferenceStore,
    /**
     * Whether the platform can serve intercepted client DNS through the configured T0 resolver.
     * Production wires `ChainedT0ResolverEgress` and passes `true`; a caller that has not wired an
     * egress leaves it `false`, so activation fails closed to the working DNS-only tunnel.
     */
    private val clientDnsOverTunnelAvailable: Boolean,
) {

    /**
     * The honest status for the React Native surface.
     *
     * The secret half is read with its generation so the configuration and the key cannot be
     * paired across a rotation: a concurrent rotation can commit between the configuration read
     * and an independent key check, and evaluating the OLD configuration against the NEW key's
     * availability would misreport readiness (for example, report ready for an old routable
     * configuration after an unroutable replacement committed). The pair is retried a bounded
     * number of times, matching [resolveActivation], and a persistently mismatched pair fails
     * closed with no usable key.
     *
     * The secret check is a real usability probe (it attempts the decrypt), so a
     * present-but-undecryptable key half is not reported ready or enableable; the verified material
     * is discarded rather than returned.
     */
    suspend fun status(): ChainedStatus {
        val enabled = preferenceStore.loadEnabled()
        var lastStored: StoredChainedUpstreamConfiguration? = null
        repeat(MAX_ACTIVATION_READ_ATTEMPTS) {
            val stored = configurationRepository.loadConfiguration()
            lastStored = stored
            if (stored == null) {
                return statusFor(stored = null, userEnabled = enabled, secretsAvailable = false)
            }
            if (stored.pendingImportId != null) {
                return statusFor(stored = stored, userEnabled = enabled, secretsAvailable = false)
            }
            val secretsGeneration = configurationRepository.usableSecretsGeneration()
            if (secretsGeneration == stored.identity.generation) {
                return statusFor(stored = stored, userEnabled = enabled, secretsAvailable = true)
            }
            if (secretsGeneration != null) {
                // A rotation landed between the reads; re-read the pair instead of pairing the old
                // configuration with the new key.
                return@repeat
            }
            // No usable key was reported for the configuration just read. That is a stable
            // "re-enter the key" ONLY when the configuration has not changed under us: a concurrent
            // `removeAll()` clears the configuration too, so re-read it and retry with the new
            // value rather than evaluating the removed configuration against a missing key (which
            // would report "re-enter the key" instead of "not configured").
            val reread = configurationRepository.loadConfiguration()
            lastStored = reread
            if (reread == null) {
                return statusFor(stored = null, userEnabled = enabled, secretsAvailable = false)
            }
            if (reread == stored) {
                return statusFor(stored = stored, userEnabled = enabled, secretsAvailable = false)
            }
            // A replacement configuration committed; retry with the new pair.
        }
        // Persistent mismatch: fail closed on the last-read configuration with no usable key.
        return statusFor(stored = lastStored, userEnabled = enabled, secretsAvailable = false)
    }

    private fun statusFor(
        stored: StoredChainedUpstreamConfiguration?,
        userEnabled: Boolean,
        secretsAvailable: Boolean,
    ): ChainedStatus = ChainedStatus.evaluate(
        stored = stored,
        hasEntitlement = entitlement.hasEntitlement(),
        availability = availability.current(),
        userEnabled = userEnabled,
        secretsAvailable = secretsAvailable,
        clientDnsOverTunnelAvailable = clientDnsOverTunnelAvailable,
    )

    /**
     * The runtime activation for the current durable facts, or `null` for the DNS-only path.
     *
     * Reads the key material only after the whole gate has cleared, and returns `null` if the
     * committed configuration's key half is gone or undecryptable (the honest "re-enter the key"
     * state, which is [ChainedActivation.DnsOnly] for the runtime).
     *
     * ## Generation coherence
     *
     * The non-secret configuration and the key half are two records addressed by one generation,
     * read through two repository calls, so a rotation can commit between them: the configuration
     * read returns the OLD generation while [ChainedUpstreamConfigurationRepository.loadSecrets]
     * returns the NEW key. Installing that pair would give the engine a peer key from one rotation
     * and a private key from another — a handshake that cannot succeed, on a full-route tunnel.
     * The read is therefore retried a bounded number of times until the generations agree, and a
     * persistently mismatched pair fails closed to DNS-only.
     */
    suspend fun resolveActivation(): ResolvedChainedActivation? {
        // The cheap preference is checked first: a user who never enabled chaining pays one
        // SharedPreferences read on VPN start and the DNS-only path stays effectively untouched.
        if (!preferenceStore.loadEnabled()) return null
        repeat(MAX_ACTIVATION_READ_ATTEMPTS) {
            val stored = configurationRepository.loadConfiguration() ?: return null
            // The first import is persisted only to back the editor. It cannot install an engine
            // until Save publishes a successor without the atomic pending transaction tag.
            if (stored.pendingImportId != null) return null
            val activation = ChainedActivationPolicy.decide(
                stored = stored,
                hasEntitlement = entitlement.hasEntitlement(),
                availability = availability.current(),
                userEnabled = true,
                // The secret half is read and generation-checked below; the gate here evaluates
                // every non-secret term so a refused configuration is never decrypted for.
                secretsAvailable = true,
                clientDnsOverTunnelAvailable = clientDnsOverTunnelAvailable,
            )
            if (activation !is ChainedActivation.Chained) return null
            val secrets = configurationRepository.loadSecrets() ?: return null
            if (secrets.generation == activation.identity.generation) {
                val fallbackEnabled = try {
                    activation.configuration.effectiveRoutingPolicy == com.lavasec.app.foundation.chained.ChainedRoutingPolicy.SPLIT_TUNNEL &&
                        preferenceStore.loadDnsFallbackEnabled()
                } catch (failure: Throwable) { secrets.close(); throw failure }
                if (secrets.rowCount != activation.configuration.orderedHops.size) { secrets.close(); return null }
                return ResolvedChainedActivation(
                    configuration = activation.configuration,
                    identity = activation.identity,
                    secrets = secrets,
                    dnsFallbackEnabled = fallbackEnabled,
                )
            }
            // A rotation landed between the reads; zeroize this rejected material and re-read the
            // pair instead of pairing the old configuration with the new key.
            secrets.close()
        }
        return null
    }

    /** An explicit start must never reinterpret an unreadable ON request as DNS-only consent. */
    suspend fun configurationIssueForStart(): ChainedConfigurationIssue? = try {
        if (!preferenceStore.loadEnabled()) null else configurationRepository.configurationIssue()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ChainedConfigurationIssue.UNAVAILABLE
    }

    /** Commits the user's preference; returns `false` when the store refused the write. */
    suspend fun setEnabled(enabled: Boolean): Boolean =
        preferenceStore.setChainedEnabled(enabled)

    companion object {
        /**
         * The trusted production wiring seam. Both of the authority's inputs are named here and
         * nowhere else, mirroring [ChainedAvailabilityAuthority.production].
         */
        fun production(
            entitlement: ChainedEntitlementPort,
            eligibilityStore: ChainedDeviceEligibilityStore,
            configurationRepository: ChainedUpstreamConfigurationRepository,
            preferenceStore: ChainedPreferenceStore,
            clientDnsOverTunnelAvailable: Boolean = false,
        ): ChainedComposition = ChainedComposition(
            entitlement = entitlement,
            eligibilityStore = eligibilityStore,
            availability = ChainedAvailabilityAuthority.production(entitlement, eligibilityStore),
            configurationRepository = configurationRepository,
            preferenceStore = preferenceStore,
            clientDnsOverTunnelAvailable = clientDnsOverTunnelAvailable,
        )

        /**
         * How many times a mismatched configuration/key generation pair is re-read before failing
         * closed. A rotation is a rare, brief race; a pair that still disagrees after this many
         * attempts is treated as unusable rather than risked on a full-route tunnel.
         */
        private const val MAX_ACTIVATION_READ_ATTEMPTS = 3
    }
}

/**
 * Resolves a chained activation for a still-attached credential graph, refusing the handoff when a
 * detach won the race.
 *
 * [ChainedComposition.resolveActivation] performs DataStore and Keystore reads. A credential-graph
 * rotation can detach the attachment while those are in flight — after the caller's pre-resolve
 * check has already passed — and returning the resolved material would let the VPN construct a
 * chained engine for a retired generation. The post-resolution check closes the resolved secret
 * half and returns `null` instead, mirroring how the filter-runtime lease open path rechecks
 * detachment after its own slow authoritative read.
 */
suspend fun resolveChainedHandoff(
    isDetached: () -> Boolean,
    resolve: suspend () -> ResolvedChainedActivation?,
): ResolvedChainedActivation? {
    val resolved = resolve() ?: return null
    if (isDetached()) {
        resolved.secrets.close()
        return null
    }
    return resolved
}
