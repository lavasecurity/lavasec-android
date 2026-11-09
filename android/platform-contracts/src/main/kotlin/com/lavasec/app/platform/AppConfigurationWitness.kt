package com.lavasec.app.platform

import com.lavasec.app.foundation.config.AppConfiguration
import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.filter.CanonicalSha256Encoder
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.FilterProfileDraft
import com.lavasec.app.foundation.filter.canonicalSha256
import com.lavasec.app.foundation.resolver.FilterResolverPlan
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicReference

/**
 * The subscription tier a configuration was admitted under.
 *
 * Deliberately coarser than the store's `FeatureLimits`: a review invalidator only needs to know
 * which entitlement fact the configuration was captured against, not the numeric feature ceilings
 * (those are policy and can move without the user's configuration changing).
 */
enum class SubscriptionTier {
    FREE,
    PLUS,
}

/**
 * The entitlement fact captured by a witness.
 *
 * [Unknown] is first-class, NOT `Known(FREE)`: an absent/unread entitlement is a different fact
 * from a confirmed free tier, and collapsing the two would let a review survive an entitlement
 * change that was never actually observed.
 */
sealed interface EntitlementTierFact {
    data object Unknown : EntitlementTierFact

    data class Known(val tier: SubscriptionTier) : EntitlementTierFact
}

/**
 * The four retention/privacy toggles a configuration carries.
 *
 * [Unknown] is first-class, NOT the `LoggingSettings` defaults. The Android logging store defaults
 * every toggle to `true`, so a "not read yet" placeholder and a genuine all-on choice are
 * indistinguishable at the source; representing absence explicitly stops a placeholder from
 * masquerading as a canonical choice in the witness.
 */
sealed interface PrivacyRetentionChoices {
    data object Unknown : PrivacyRetentionChoices

    data class Known(
        val keepCounts: Boolean,
        val keepHistory: Boolean,
        val keepNetworkActivity: Boolean,
        val keepLavaGuardProgress: Boolean,
    ) : PrivacyRetentionChoices
}

/**
 * The complete set of Android-available review invalidators that compose one witness.
 *
 * [library] is the authoritative filter record and already carries the resolver plan
 * ([FilterLibraryDraft.resolverPlan]); the witness encodes that plan from the library so the two can
 * never disagree. [protectionLevel] is the onboarding level, which is device-global and survives a
 * switch to a user-created filter.
 */
data class AppConfigurationWitnessFacts(
    val library: FilterLibraryDraft,
    val protectionLevel: ProtectionLevel,
    val privacyRetention: PrivacyRetentionChoices,
    val entitlement: EntitlementTierFact,
) {
    companion object {
        /**
         * Captures the production configuration facts from their authoritative owners.
         *
         * [state] is the persisted product configuration, [library] the authoritative filter
         * library, and [entitlement] the current entitlement fact. The privacy choices come from
         * the logging store (`PersistedAppState.logging`), the production owner of the four
         * retention toggles; the protection level comes from the persisted [AppConfiguration].
         *
         * The persisted pointer and the authoritative library are committed under separate
         * locks, so they CAN be read mid-write. A witness composed from such a torn read would
         * silently describe a configuration that never existed, so this factory fails closed:
         * it refuses to compose facts at all when the persisted active filter or resolver no
         * longer agrees with the library. The caller captures only a coherent state, and the
         * slot publication ([AppConfigurationWitnessSlot]) then exposes it atomically.
         */
        fun fromPersisted(
            state: PersistedAppState,
            library: FilterLibraryDraft,
            entitlement: EntitlementTierFact,
        ): AppConfigurationWitnessFacts {
            val configuration = requireNotNull(state.configuration) {
                "a configuration witness requires a completed onboarding configuration"
            }
            require(configuration.activeFilterId == library.activeProfileId) {
                "persisted active filter does not match the authoritative library (interrupted write)"
            }
            val persistedPlan = requireNotNull(FilterResolverPlan.from(configuration.resolver)) {
                "persisted resolver does not correspond to a supported resolver plan"
            }
            require(persistedPlan == library.resolverTarget) {
                "persisted resolver does not match the authoritative library (interrupted write)"
            }
            return AppConfigurationWitnessFacts(
                library = library,
                protectionLevel = configuration.protectionLevel,
                privacyRetention = PrivacyRetentionChoices.Known(
                    keepCounts = state.logging.keepCounts,
                    keepHistory = state.logging.keepHistory,
                    keepNetworkActivity = state.logging.keepNetworkActivity,
                    keepLavaGuardProgress = state.logging.keepLavaGuardProgress,
                ),
                entitlement = entitlement,
            )
        }
    }
}

/**
 * A coherent, content-addressed witness of the full configuration state a review must be invalidated
 * against.
 *
 * ## Relationship to the existing identities
 *
 * This is a SEPARATE composed witness and deliberately does not derive from
 * [canonicalFilterConfigurationFingerprint]. That fingerprint remains the compile/artifact identity
 * over the filter library plus the verified catalog revision/SHA; it says nothing about the resolver
 * plan (except via profile membership), the app-level protection level, the privacy retention toggles,
 * or the entitlement fact. The witness adds exactly those canonical review invalidators and leaves
 * the compile identity and every durable format untouched.
 *
 * [canonicalChainedUpstreamConfigurationFingerprint] is likewise untouched: the chained upstream has
 * its own secret split and its own generation.
 *
 * ## Identity
 *
 * [sha256] is a canonical SHA-256 over a versioned, deterministic encoding:
 *  - the witness [version] is the first encoded integer, so a future encoding change produces a
 *    disjoint identity space and can never be accepted by an older version;
 *  - the filter library is encoded with profiles sorted by `(protectionLevel?.ordinal, id)` and every
 *    string set sorted, matching the ordering convention of [canonicalFilterConfigurationFingerprint];
 *  - a filter name and emoji are encoded in their canonical NFC spelling, so a canonically
 *    equivalent re-save (iOS `String ==` canonical equivalence) does NOT move the identity, while a
 *    real change — including a trailing space — does;
 *  - absent facts (no protection level on a user-created profile, [PrivacyRetentionChoices.Unknown],
 *    [EntitlementTierFact.Unknown]) are encoded with an explicit presence tag, so they are never
 *    silently equal to a default.
 *
 * [generation] is carried alongside the content hash, NOT folded into it. A caller that has a durable
 * monotonic generation can express "a new edit happened even though the content compares equal" —
 * e.g. a rename followed by a rename back — by capturing at the new generation. Use [contentMatches]
 * for content-only equality and [matches] / [unchangedSince] for full review invalidation.
 */
class AppConfigurationWitness private constructor(
    val version: Int,
    val generation: Long,
    val sha256: String,
) {
    /** Full identity: version, generation and content all agree. */
    fun matches(other: AppConfigurationWitness): Boolean =
        version == other.version && generation == other.generation && sha256 == other.sha256

    /** Content identity only: same encoding version and same encoded content, any generation. */
    fun contentMatches(other: AppConfigurationWitness): Boolean =
        version == other.version && sha256 == other.sha256

    /** True when [snapshot] is a full-identity match for this witness. */
    fun unchangedSince(snapshot: AppConfigurationWitness): Boolean = matches(snapshot)

    override fun equals(other: Any?): Boolean = other is AppConfigurationWitness && matches(other)

    override fun hashCode(): Int = listOf(version, generation, sha256).hashCode()

    override fun toString(): String =
        "AppConfigurationWitness(version=$version, generation=$generation, sha256=$sha256)"

    companion object {
        /**
         * Encoding version. Bump only with a deliberate, incompatible encoding change.
         *
         * v2 NFC-composes each filter name and emoji before encoding so the identity matches the
         * canonical string equality of iOS `FilterIdentityPolicy` (a canonically equivalent re-save
         * must not invalidate a review). v1 captures are deliberately NOT accepted: the version is
         * the first encoded integer, so a v1 identity is disjoint and is rejected by every
         * [matches]/[contentMatches]/[unchangedSince] comparison.
         */
        const val CURRENT_VERSION: Int = 2

        private const val HASH_DOMAIN = "app-configuration-witness"

        /** Captures [facts] under the current encoding version at the caller's durable [generation]. */
        fun capture(
            facts: AppConfigurationWitnessFacts,
            generation: Long = 0L,
        ): AppConfigurationWitness = captureAtVersion(facts, generation, CURRENT_VERSION)

        /**
         * Test/upgrade seam for an explicit encoding version. Production callers use [capture].
         */
        internal fun captureAtVersion(
            facts: AppConfigurationWitnessFacts,
            generation: Long,
            version: Int,
        ): AppConfigurationWitness {
            require(version > 0) { "witness encoding version must be positive" }
            val sha256 = canonicalSha256(HASH_DOMAIN) {
                writeInt(version)
                encodeLibrary(facts.library)
                writeString(facts.protectionLevel.name)
                encodePrivacyRetention(facts.privacyRetention)
                encodeEntitlement(facts.entitlement)
            }
            return AppConfigurationWitness(version, generation, sha256)
        }
    }
}

private fun CanonicalSha256Encoder.encodeLibrary(library: FilterLibraryDraft) {
    writeString(library.activeProfileId)
    encodeResolverPlan(library.resolverTarget)
    val ordered = library.profiles.sortedWith(
        compareBy({ it.protectionLevel?.ordinal ?: Int.MAX_VALUE }, { it.id }),
    )
    writeInt(ordered.size)
    ordered.forEach(::encodeProfile)
}

private fun CanonicalSha256Encoder.encodeProfile(profile: FilterProfileDraft) {
    writeString(profile.id)
    writeString(canonicalIdentity(profile.name))
    // Explicit presence: a user-created filter (null level) is a different fact from a seeded one.
    writeBoolean(profile.protectionLevel != null)
    profile.protectionLevel?.let { writeString(it.name) }
    writeCanonicalStrings(profile.enabledSourceIds)
    writeCanonicalStrings(profile.manualRules.allowedDomains)
    writeCanonicalStrings(profile.manualRules.blockedDomains)
    writeString(canonicalIdentity(profile.emoji))
}

/**
 * The canonical spelling of a filter identity string.
 *
 * iOS compares `String`s for canonical equivalence (`FilterIdentityPolicy.hasUnsavedChanges`), so
 * `Café` and `Cafe\u0301` are the same identity. Reproduced here with NFC composition, the same
 * normalization the ported Android [com.lavasec.app.foundation.filter.FilterIdentityPolicy] uses.
 * Trimming is deliberately NOT applied: the Swift fixtures prove a trailing space is a real change
 * (`"Café "` is unsaved against `"Café"`), and a stored legacy name must keep its exact bytes.
 */
private fun canonicalIdentity(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFC)

/**
 * Encodes the value-carrying resolver plan AND its underlying transport selection. The enum name
 * alone is enough for every built-in plan because [MilestoneResolverPlan] pins one
 * [com.lavasec.app.foundation.resolver.ResolverConfiguration] per constant, but the fields are
 * written too so a future plan whose configuration varies cannot share a witness by name collision.
 * A custom DoH URL or DoT endpoint is appended only when present, so every built-in plan keeps its
 * exact pre-existing witness hash while a custom endpoint is a distinct identity.
 */
private fun CanonicalSha256Encoder.encodeResolverPlan(target: FilterResolverPlan) {
    writeString(target.plan.name)
    val configuration = target.plan.configuration
    writeBoolean(configuration.useDeviceDns)
    writeBoolean(configuration.useEncryptedFallback)
    writeString(configuration.encryptedFallbackId)
    target.customDohUrl?.let { writeString(it) }
    target.customDotEndpoint?.let { writeString(it) }
    target.customDoqEndpoint?.let { writeString("custom-doq"); writeString(it) }
    // The custom name and secondary endpoint are appended only when present, so every built-in and
    // legacy custom plan keeps its exact pre-existing witness hash while a committed change to
    // either invalidates a pending review.
    target.customName?.let { writeString(it) }
    target.customSecondaryEndpoint?.let { writeString(it) }
    // The retained ladder is appended only when present, so a legacy two-slot plan keeps its exact
    // pre-existing witness hash; any row identity, value or enablement change invalidates a review.
    target.savedTiers?.let { tiers ->
        writeBoolean(true)
        writeInt(tiers.size)
        tiers.forEach { tier ->
            writeString(tier.entry.providerId.rawId)
            writeString(tier.entry.transport.token)
            writeString(tier.entry.customName ?: "")
            writeString(tier.entry.customDohUrl ?: "")
            writeString(tier.entry.customDotEndpoint ?: "")
            tier.entry.customDoqEndpoint?.let { writeString("custom-doq"); writeString(it) }
            writeString(tier.entry.customSecondaryEndpoint ?: "")
            writeBoolean(tier.isEnabled)
            tier.retainedName?.let { writeString("retained-name"); writeString(it) }
            tier.entry.customPlainEndpoint?.let { writeString("custom-plain"); writeString(it) }
        }
    }
}

private fun CanonicalSha256Encoder.encodePrivacyRetention(choices: PrivacyRetentionChoices) {
    when (choices) {
        PrivacyRetentionChoices.Unknown -> writeString(UNKNOWN_TAG)
        is PrivacyRetentionChoices.Known -> {
            writeString(KNOWN_TAG)
            writeBoolean(choices.keepCounts)
            writeBoolean(choices.keepHistory)
            writeBoolean(choices.keepNetworkActivity)
            writeBoolean(choices.keepLavaGuardProgress)
        }
    }
}

private fun CanonicalSha256Encoder.encodeEntitlement(fact: EntitlementTierFact) {
    when (fact) {
        EntitlementTierFact.Unknown -> writeString(UNKNOWN_TAG)
        is EntitlementTierFact.Known -> {
            writeString(KNOWN_TAG)
            writeString(fact.tier.name)
        }
    }
}

private fun CanonicalSha256Encoder.writeCanonicalStrings(values: Set<String>) {
    val ordered = values.sorted()
    writeInt(ordered.size)
    ordered.forEach(::writeString)
}

private const val KNOWN_TAG = "known"
private const val UNKNOWN_TAG = "unknown"

/**
 * A single-slot, atomically published [AppConfigurationWitness].
 *
 * The witness is composed from several independent owners, so a reader must never observe a
 * partially composed value. This holder stores exactly one immutable witness and swaps it in one
 * [AtomicReference] operation: a reader sees either the whole previous witness or the whole new
 * one, and a refused/incoherent capture leaves the previous witness visible.
 *
 * It holds only the identity witness, never a copy of the configuration itself, so it is not a
 * second configuration authority — it is the publication seam for the one witness type.
 * [publishIfCurrent] is the compare-and-set form a writer composing against an observed witness
 * uses so a superseded write cannot clobber a newer one.
 */
class AppConfigurationWitnessSlot(
    initial: AppConfigurationWitness? = null,
) {
    private val published = AtomicReference(initial)

    /** The complete witness currently visible to readers, or null before the first publication. */
    val current: AppConfigurationWitness?
        get() = published.get()

    /** Captures [facts] at [generation] and publishes the result in one atomic swap. */
    fun publish(facts: AppConfigurationWitnessFacts, generation: Long): AppConfigurationWitness {
        val captured = AppConfigurationWitness.capture(facts, generation)
        published.set(captured)
        return captured
    }

    /** Publishes an already-captured [witness] in one atomic swap and returns it. */
    fun publish(witness: AppConfigurationWitness): AppConfigurationWitness {
        published.set(witness)
        return witness
    }

    /** Removes the visible witness (a consumed review), so no stale identity remains routed. */
    fun clear() {
        published.set(null)
    }

    /**
     * Publishes only while the visible witness is still [expected] (compare-and-set). Returns true
     * when [facts] was published; false when a newer witness superseded [expected] first.
     */
    fun publishIfCurrent(
        expected: AppConfigurationWitness?,
        facts: AppConfigurationWitnessFacts,
        generation: Long,
    ): Boolean {
        val captured = AppConfigurationWitness.capture(facts, generation)
        return published.compareAndSet(expected, captured)
    }
}
