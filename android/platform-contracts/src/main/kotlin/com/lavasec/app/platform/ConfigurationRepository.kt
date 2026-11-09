package com.lavasec.app.platform

import com.lavasec.app.foundation.subscription.FeatureLimitAdmission
import com.lavasec.app.foundation.subscription.FeatureLimits

import com.lavasec.app.foundation.config.AppConfiguration
import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.filter.CustomBlocklistSourceFailure
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.FilterProfile
import com.lavasec.app.foundation.pause.PauseState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.Collections
import java.util.LinkedHashSet

data class LoggingSettings(
    val keepCounts: Boolean = true,
    val keepHistory: Boolean = true,
    val keepNetworkActivity: Boolean = true,
    val keepLavaGuardProgress: Boolean = true,
)

data class ConfigurationIdentity(
    val generation: Long,
    val fingerprint: String,
)

class FilterMetadata(
    val id: String,
    val name: String,
    /**
     * The onboarding protection level for a seeded filter id, or null for a user-created
     * filter. The persisted product configuration records the active filter as an opaque
     * id + name pair — the filter library itself is the authoritative record's truth — so
     * this value is display/derivation metadata, never a capability.
     */
    val protectionLevel: ProtectionLevel?,
    enabledSourceIds: Set<String> = emptySet(),
) {
    val enabledSourceIds: Set<String> =
        Collections.unmodifiableSet(LinkedHashSet(enabledSourceIds))

    override fun equals(other: Any?): Boolean =
        other is FilterMetadata &&
            id == other.id &&
            name == other.name &&
            protectionLevel == other.protectionLevel &&
            enabledSourceIds == other.enabledSourceIds

    override fun hashCode(): Int =
        listOf(id, name, protectionLevel, enabledSourceIds).hashCode()

    companion object {
        fun seeded(): List<FilterMetadata> = ProtectionLevel.entries.map { level ->
            val profile = FilterProfile.forLevel(level)
            FilterMetadata(
                id = profile.id,
                name = profile.name,
                protectionLevel = profile.protectionLevel,
                enabledSourceIds = profile.enabledSourceIds,
            )
        }

        /** Resolves the three seeded filter ids only; user-created filters are opaque. */
        fun forId(id: String): FilterMetadata? = seeded().firstOrNull { it.id == id }
    }
}

data class PersistedAppState(
    val onboardingCompleted: Boolean,
    val configuration: AppConfiguration?,
    val protectionIntent: Boolean,
    val activeFilter: FilterMetadata,
    val logging: LoggingSettings,
    /** Monotonic logical protection session; advances only on an authoritative OFF -> ON. */
    val protectionSessionId: Long = 0L,
    val pauseDeadlineEpochMillis: Long? = null,
    /** The exact logical protection session to which [pauseDeadlineEpochMillis] belongs. */
    val pauseSessionId: Long? = null,
    val configurationIdentity: ConfigurationIdentity? = null,
) {
    init {
        require(protectionSessionId >= 0L) { "protection session must not be negative" }
        require((pauseDeadlineEpochMillis == null) == (pauseSessionId == null)) {
            "pause deadline and session must be absent or present together"
        }
        if (pauseDeadlineEpochMillis != null) {
            require(protectionIntent) { "pause requires protection intent" }
            require(protectionSessionId > 0L) { "pause requires a positive protection session" }
            require(pauseSessionId == protectionSessionId) {
                "pause must belong to the current protection session"
            }
        }
    }

    val pause: PauseState? = pauseDeadlineEpochMillis?.let { deadlineMillis ->
        PauseState(deadlineMillis, checkNotNull(pauseSessionId))
    }

    companion object {
        fun initial(): PersistedAppState = PersistedAppState(
            onboardingCompleted = false,
            configuration = null,
            protectionIntent = false,
            activeFilter = FilterMetadata.forId(AppConfiguration.default().activeFilterId)!!,
            logging = LoggingSettings(),
            protectionSessionId = 0L,
            pauseDeadlineEpochMillis = null,
            pauseSessionId = null,
            configurationIdentity = null,
        )
    }
}

data class DirectBootAppState(
    val onboardingCompleted: Boolean,
    val protectionIntent: Boolean,
    val useDeviceDns: Boolean,
    val useEncryptedFallback: Boolean,
    val encryptedFallbackId: String?,
    /**
     * The normalized custom DoH endpoint carried across Direct Boot, or null for every built-in
     * resolver. Additive: a null value is every existing mirror, and a non-null value lets the
     * pre-unlock runtime serve the exact endpoint the witness/authority committed.
     */
    val customDohUrl: String? = null,
    /**
     * The normalized custom DoT endpoint (`host:port`) carried across Direct Boot, or null for
     * every built-in/custom-DoH resolver. Additive: a null value is every existing mirror.
     */
    val customDotEndpoint: String? = null,
    val activeProfileId: String?,
    val configurationIdentity: ConfigurationIdentity?,
    val credentialUnlockRequired: Boolean = false,
    val customDoqEndpoint: String? = null,
    val customName: String? = null,
    val customSecondaryEndpoint: String? = null,
    /** Exact retained DNS rows, including disabled positions. Null decodes the legacy fields. */
    val savedTiers: List<com.lavasec.app.foundation.resolver.ResolverTier>? = null,
) {
    companion object {
        fun safeDefault(): DirectBootAppState = DirectBootAppState(
            onboardingCompleted = false,
            protectionIntent = false,
            useDeviceDns = true,
            useEncryptedFallback = true,
            encryptedFallbackId = null,
            customDohUrl = null,
            customDotEndpoint = null,
            activeProfileId = null,
            configurationIdentity = null,
            credentialUnlockRequired = false,
        )
    }
}

data class ConfirmedFilterState(
    val configuration: FilterLibraryDraft,
    val configurationIdentity: ConfigurationIdentity,
    val credentialUnlockRequired: Boolean,
    val verifiedArtifact: VerifiedFilterArtifact,
) {
    init {
        val artifactIdentity = verifiedArtifact.artifactIdentity
        val artifactConfiguration = artifactIdentity.configuration
        requireNotNull(configuration.profile(configuration.activeProfileId)) {
            "confirmed configuration must contain its active profile"
        }
        val derivedFingerprint = canonicalFilterConfigurationFingerprint(
            configuration = configuration,
            catalogRevision = artifactIdentity.catalogRevision,
            catalogSha256 = artifactIdentity.catalogSha256,
        )
        require(artifactConfiguration.generation == configurationIdentity.generation) {
            "artifact generation must match confirmed configuration"
        }
        require(configurationIdentity.fingerprint == derivedFingerprint) {
            "confirmed fingerprint must be derived from the complete configuration"
        }
        require(artifactConfiguration.fingerprint == configurationIdentity.fingerprint) {
            "artifact fingerprint must match confirmed configuration"
        }
        require(credentialUnlockRequired == filterRequiresCredentialUnlock(configuration, artifactIdentity.ruleCounts.total)) {
            "credential unlock requirement must match private rules or a Plus-only artifact"
        }
    }
}

/** Pre-unlock cannot read the device plan. Only Free-sized public artifacts can serve there. */
fun filterRequiresCredentialUnlock(configuration: FilterLibraryDraft, compiledRuleCount: Int): Boolean {
    require(compiledRuleCount >= 0)
    return configuration.qaProbeSet != null || compiledRuleCount > FeatureLimits.FREE.maxFilterRules || configuration.profiles.any {
        it.manualRules.allowedDomains.isNotEmpty() || it.manualRules.blockedDomains.isNotEmpty()
    }
}

data class FilterMutationRequest(
    val expectedGeneration: Long,
    val desired: FilterLibraryDraft,
    /** Rebuilds exact source bytes even when the immutable configuration is unchanged. */
    val forceRecompile: Boolean = false,
    /** Exact native tier witness captured before edit validation, never supplied by RN. */
    val admission: FeatureLimitAdmission? = null,
    /** Native view/graph admission, rechecked immediately before durable publication. */
    val publicationAdmission: (() -> Boolean)? = null,
    /** Exact native short final-write authority, never a presentation callback. */
    val publicationGuard: NativeFilterPublicationGuard? = null,
) {
    init {
        require(expectedGeneration > 0L) { "expected generation must be positive" }
    }
}

enum class FilterMutationRejection {
    INVALID_DRAFT,
    DEVICE_LOCKED,
    NO_CHANGE,
}

enum class FilterMutationFailure {
    STAGING_FAILED,
    COMPILE_FAILED,
    REOPEN_FAILED,
    IDENTITY_MISMATCH,
    PUBLISH_FAILED,
    COMMIT_FAILED,
    ROLLBACK_FAILED,
}

sealed interface FilterMutationResult {
    val confirmedState: ConfirmedFilterState

    data class Applied(
        override val confirmedState: ConfirmedFilterState,
    ) : FilterMutationResult

    data class Rejected(
        override val confirmedState: ConfirmedFilterState,
        val reason: FilterMutationRejection,
    ) : FilterMutationResult

    data class FailedKeepingPrevious(
        override val confirmedState: ConfirmedFilterState,
        val failure: FilterMutationFailure,
    ) : FilterMutationResult

    /**
     * One custom blocklist could not be fetched. The previous filter stays authoritative; the
     * stable [failure] plus [sourceName] name which list failed without carrying its URL.
     */
    data class SourceUnavailable(
        override val confirmedState: ConfirmedFilterState,
        val sourceId: String,
        val sourceName: String?,
        val failure: CustomBlocklistSourceFailure,
    ) : FilterMutationResult

    /**
     * Protection must remain unavailable and durable authority must be reloaded before activation.
     *
     * [confirmedState] is the coordinator's last verified in-memory value; after an ambiguous
     * storage outcome it is not a claim that this value still matches the authoritative record.
     */
    data class ProtectionUnavailable(
        override val confirmedState: ConfirmedFilterState,
    ) : FilterMutationResult

    data class Superseded(
        override val confirmedState: ConfirmedFilterState,
    ) : FilterMutationResult
}

/**
 * Whether the coordinator's last verified state may still be treated as authoritative.
 *
 * [ReloadRequired] is sticky for the lifetime of a coordinator. Its [lastVerifiedState] is useful
 * only for diagnostics and display; it must not be activated. Recovery reconstructs a new
 * coordinator from an exact authoritative-store read.
 */
sealed interface FilterMutationAuthorityState {
    val lastVerifiedState: ConfirmedFilterState

    data class Ready(
        override val lastVerifiedState: ConfirmedFilterState,
    ) : FilterMutationAuthorityState

    data class ReloadRequired(
        override val lastVerifiedState: ConfirmedFilterState,
    ) : FilterMutationAuthorityState
}

interface FilterMutationCoordinator {
    /**
     * Last exactly verified state for compatibility, diagnostics, and display.
     *
     * Runtime activation must consult [authorityState]; this flow alone does not prove that the
     * value remains authoritative after an ambiguous durable outcome.
     */
    val confirmedState: Flow<ConfirmedFilterState>

    /** The fail-closed authority gate that every future runtime activation must consume. */
    val authorityState: Flow<FilterMutationAuthorityState>

    suspend fun apply(request: FilterMutationRequest): FilterMutationResult
}

sealed interface DirectBootMirrorResult {
    val verifiedArtifact: VerifiedFilterArtifact?

    /** Exact identity-coherent state and artifact are durable in device-protected storage. */
    class MirrorPublished private constructor(
        override val verifiedArtifact: VerifiedFilterArtifact,
    ) : DirectBootMirrorResult {
        internal companion object {
            fun create(verifiedArtifact: VerifiedFilterArtifact): MirrorPublished =
                MirrorPublished(verifiedArtifact)
        }
    }

    /** Exact credential-protected runtime is verified for an unlocked, visible user session. */
    class CredentialRuntimePublished private constructor(
        override val verifiedArtifact: VerifiedFilterArtifact,
    ) : DirectBootMirrorResult {
        internal companion object {
            fun create(verifiedArtifact: VerifiedFilterArtifact): CredentialRuntimePublished =
                CredentialRuntimePublished(verifiedArtifact)
        }
    }

    /** No exact runtime publication exists; legacy/configuration-only mirrors do not qualify. */
    data object MirrorUnavailable : DirectBootMirrorResult {
        override val verifiedArtifact: VerifiedFilterArtifact? = null
    }

    /** True only when the exact immutable runtime is available to the admitted session. */
    val isExactRuntimePublished: Boolean
        get() = verifiedArtifact != null

    companion object {
        fun deviceProtectedPublished(state: ConfirmedFilterState): MirrorPublished {
            require(!state.credentialUnlockRequired) {
                "credential-unlock-required state cannot be published for Direct Boot"
            }
            return MirrorPublished.create(state.verifiedArtifact)
        }

        fun credentialRuntimePublished(state: ConfirmedFilterState): CredentialRuntimePublished =
            CredentialRuntimePublished.create(state.verifiedArtifact)
    }
}

sealed interface ProtectionIntentWriteResult {
    val desired: Boolean

    data class RejectedBeforeAuthoritativeCommit(
        override val desired: Boolean,
    ) : ProtectionIntentWriteResult

    /**
     * The authoritative value definitely did not change, but the old mirror key was invalidated.
     *
     * Direct Boot remains safely unavailable until an unlocked authoritative republish succeeds.
     */
    data class AuthoritativeUnchangedMirrorUnavailable(
        override val desired: Boolean,
    ) : ProtectionIntentWriteResult

    /**
     * The desired value is durable only after the prior Direct Boot mirror key was invalidated.
     *
     * A failed or unavailable mirror after this point is safe but unavailable: an older envelope
     * cannot be restored. Failure before invalidation must instead return
     * [RejectedBeforeAuthoritativeCommit] and leave the authoritative value unchanged.
     */
    class AuthoritativeCommitted private constructor(
        override val desired: Boolean,
        val mirror: DirectBootMirrorResult,
    ) : ProtectionIntentWriteResult {
        internal companion object {
            fun create(
                desired: Boolean,
                mirror: DirectBootMirrorResult,
            ): AuthoritativeCommitted = AuthoritativeCommitted(desired, mirror)
        }
    }
}

/**
 * Enforces invalidate -> authoritative commit -> bound seal/publish ordering for intent changes.
 *
 * The shared freshness authority serializes every coordinator through final mirror publication.
 * An older or superseded epoch can at worst make the mirror unavailable, never publish a behavior
 * state different from the exact authoritative read-back.
 *
 * Subclasses are trusted platform adapters. [commitAndReadBack] must return only after durable
 * persistence and exact authoritative read-back; null means the adapter proved no commit occurred.
 * Throwing means the outcome is uncertain and callers must reconcile from the authoritative store.
 * [publishMirror] must atomically replace the device-protected mirror and must not retain or expose
 * its one-shot publication capability. Final sequencing remains non-overridable.
 */
abstract class ProtectionIntentWriteCoordinator protected constructor(
    private val freshnessAuthority: DirectBootSnapshotFreshnessAuthority,
) {
    protected abstract suspend fun commitAndReadBack(desired: Boolean): DirectBootAppState?

    protected abstract suspend fun publishMirror(
        state: DirectBootAppState,
        committedPublication: DirectBootCommittedSnapshotPublication,
    ): DirectBootMirrorResult

    final suspend fun write(desired: Boolean): ProtectionIntentWriteResult =
        freshnessAuthority.serializePublicationWrite<ProtectionIntentWriteResult> {
            val epoch = when (val start = freshnessAuthority.beginPublication()) {
                DirectBootSnapshotPublicationStart.RejectedBeforeInvalidation ->
                    return@serializePublicationWrite ProtectionIntentWriteResult
                        .RejectedBeforeAuthoritativeCommit(desired)
                DirectBootSnapshotPublicationStart.InvalidatedWithoutReplacement ->
                    return@serializePublicationWrite ProtectionIntentWriteResult
                        .AuthoritativeUnchangedMirrorUnavailable(desired)
                is DirectBootSnapshotPublicationStart.Ready -> start.epoch
            }
            check(freshnessAuthority.claimAuthoritativeCommit(epoch, desired)) {
                "fresh Direct Boot publication epoch could not claim authoritative commit"
            }

            val committedState = commitAndReadBack(desired)
                ?: return@serializePublicationWrite ProtectionIntentWriteResult
                    .AuthoritativeUnchangedMirrorUnavailable(desired)
            check(committedState.protectionIntent == desired) {
                "authoritative protection intent read-back does not match requested value"
            }

            val committedPublication =
                freshnessAuthority.confirmAuthoritativeCommit(epoch, committedState)
            val mirror = if (committedPublication == null) {
                DirectBootMirrorResult.MirrorUnavailable
            } else {
                try {
                    publishMirror(committedState, committedPublication)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    DirectBootMirrorResult.MirrorUnavailable
                }
            }
            ProtectionIntentWriteResult.AuthoritativeCommitted.create(desired, mirror)
        }
}

enum class PauseMutationRejection {
    PROTECTION_OFF,
    SESSION_MISMATCH,
    EXPECTED_PAUSE_MISMATCH,
    UNSUPPORTED,
}

/** Result of one atomic, logical-protection-session-bound pause mutation. */
sealed interface PauseMutationResult {
    val authoritative: PersistedAppState

    data class Applied(
        override val authoritative: PersistedAppState,
    ) : PauseMutationResult

    data class Rejected(
        override val authoritative: PersistedAppState,
        val reason: PauseMutationRejection,
    ) : PauseMutationResult
}

interface ConfigurationRepository {
    val state: Flow<PersistedAppState>

    suspend fun completeOnboarding(configuration: AppConfiguration)

    /**
     * Implementations delegate committed writes to [ProtectionIntentWriteCoordinator]. The
     * committed result cannot be constructed outside this contracts module without that ordered
     * coordinator; a pre-invalidation rejection leaves the authoritative value unchanged.
     */
    suspend fun setProtectionIntent(enabled: Boolean): ProtectionIntentWriteResult

    suspend fun setFilter(filterId: String)

    suspend fun setLogging(
        keepCounts: Boolean,
        keepHistory: Boolean,
        keepNetworkActivity: Boolean,
    )

    /**
     * Bridges to the original mutation only when doing so cannot discard a changed setting.
     */
    suspend fun setLogging(
        keepCounts: Boolean,
        keepHistory: Boolean,
        keepNetworkActivity: Boolean,
        keepLavaGuardProgress: Boolean,
    ) {
        val authoritativeLavaGuardProgress = state.first().logging.keepLavaGuardProgress
        check(keepLavaGuardProgress == authoritativeLavaGuardProgress) {
            "repository does not implement Lava Guard progress mutation"
        }
        setLogging(keepCounts, keepHistory, keepNetworkActivity)
    }

    /**
     * Atomically changes pause authority only for the observed logical protection session.
     * The fail-closed default keeps legacy/in-memory adapters source compatible without
     * pretending that a read followed by an unrestricted write is a transaction.
     */
    suspend fun compareAndSetPause(
        expectedProtectionSessionId: Long,
        expectedPause: PauseState?,
        replacement: PauseState?,
    ): PauseMutationResult = PauseMutationResult.Rejected(
        authoritative = state.first(),
        reason = PauseMutationRejection.UNSUPPORTED,
    )
}
