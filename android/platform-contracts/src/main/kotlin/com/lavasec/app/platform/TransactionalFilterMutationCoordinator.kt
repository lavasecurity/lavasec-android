package com.lavasec.app.platform

import com.lavasec.app.foundation.subscription.FeatureLimitAdmission

import com.lavasec.app.foundation.filter.ArtifactConfigurationIdentity
import com.lavasec.app.foundation.filter.ArtifactSourceIdentity
import com.lavasec.app.foundation.filter.BlocklistFormat
import com.lavasec.app.foundation.filter.CanonicalSha256Encoder
import com.lavasec.app.foundation.filter.CustomBlocklistSourceAcquisitionException
import com.lavasec.app.foundation.filter.CustomBlocklistSourceFailure
import com.lavasec.app.foundation.filter.DomainRule
import com.lavasec.app.foundation.filter.FilterCompileRequest
import com.lavasec.app.foundation.filter.FilterCompilationReceipt
import com.lavasec.app.foundation.filter.FilterCompiler
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.FilterProfileDraft
import com.lavasec.app.foundation.filter.FilterSourcePayload
import com.lavasec.app.foundation.filter.PreparedFilterArtifact
import com.lavasec.app.foundation.filter.canonicalFilterCompileInputsSha256
import com.lavasec.app.foundation.filter.canonicalSha256
import com.lavasec.app.foundation.resolver.toSelection
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class CatalogCompileSourceIdentity(
    val id: String,
    val format: BlocklistFormat,
    /**
     * A user-supplied custom blocklist's URL. Null for a curated catalog source. A custom source
     * has no signed catalog revision, so it is keyed by URL + format through the source cache.
     */
    val sourceUrl: String? = null,
) {
    init {
        require(id.isNotBlank()) { "catalog compile source id must not be blank" }
    }

    val isCustom: Boolean get() = sourceUrl != null
}

/**
 * Immutable, verified catalog receipt and compile policy injected independently of the artifact
 * preparer. The production adapter must construct this from the pinned signed catalog.
 */
class FilterCatalogCompileAuthority(
    val revision: String,
    sha256: String,
    sources: List<CatalogCompileSourceIdentity>,
    guardrailRules: List<DomainRule> = emptyList(),
    val maxRules: Int,
) {
    val sha256: String = canonicalSha256Hex(sha256)
    val sources: List<CatalogCompileSourceIdentity> =
        Collections.unmodifiableList(sources.toList())
    val guardrailRules: List<DomainRule> =
        Collections.unmodifiableList(guardrailRules.toList())

    init {
        require(revision.isNotBlank()) { "catalog revision must not be blank" }
        require(this.sources.map(CatalogCompileSourceIdentity::id).distinct().size == this.sources.size) {
            "catalog compile source ids must be unique"
        }
        require(maxRules in 0..FilterCompiler.MAX_SOURCE_RULES) { "catalog compile rule limit is invalid" }
    }

    /**
     * The compile sources for [profile]: curated catalog sources in catalog order, then the
     * profile's enabled custom blocklists in definition order. Every enabled id must resolve to
     * either the verified catalog or the profile's own custom definitions, so an unknown id is
     * still refused.
     */
    internal fun selectedSources(profile: FilterProfileDraft): List<CatalogCompileSourceIdentity> {
        val catalogSelected = sources.filter { it.id in profile.enabledSourceIds }
        val customSelected = profile.customBlocklists
            .filter { it.id in profile.enabledSourceIds }
            .map { CatalogCompileSourceIdentity(it.id, it.parseFormat, sourceUrl = it.sourceUrl) }
        val selected = catalogSelected + customSelected
        require(selected.map(CatalogCompileSourceIdentity::id).distinct().size == selected.size) {
            "desired profile references a source more than once"
        }
        require(selected.map(CatalogCompileSourceIdentity::id).toSet() == profile.enabledSourceIds) {
            "desired profile references a source outside the verified catalog or its custom sources"
        }
        return Collections.unmodifiableList(selected)
    }
}

/**
 * Trusted boundary that supplies the exact accepted bytes and digests for verified catalog
 * descriptors. Returned streams remain digest-checked by [FilterCompiler].
 */
fun interface AcceptedFilterSourceAuthority {
    suspend fun acquire(
        sources: List<CatalogCompileSourceIdentity>,
        forceRefresh: Boolean,
    ): List<FilterSourcePayload>
}

/**
 * Coordinator-minted immutable capability binding a mutation to exact trusted compile inputs.
 *
 * App-layer preparers can consume this plan but cannot construct one or replace its expectations.
 */
class AuthorizedFilterCompilePlan private constructor(
    val mutationRequest: FilterMutationRequest,
    val compileRequest: FilterCompileRequest,
    val expectedConfiguration: ArtifactConfigurationIdentity,
    expectedSources: List<ArtifactSourceIdentity>,
    val expectedCatalogRevision: String,
    val expectedCatalogSha256: String,
    val expectedCompileInputsSha256: String,
    val expectedIntentionalEmpty: Boolean,
    val expectedMaxRules: Int,
) {
    val expectedSources: List<ArtifactSourceIdentity> =
        Collections.unmodifiableList(expectedSources.toList())

    internal companion object {
        fun create(
            mutationRequest: FilterMutationRequest,
            catalog: FilterCatalogCompileAuthority,
            acceptedSources: List<FilterSourcePayload>,
            maxRules: Int = catalog.maxRules,
            allowQaProbeSets: Boolean = false,
        ): AuthorizedFilterCompilePlan {
            require(maxRules in 0..catalog.maxRules) { "tier budget exceeds structural compile ceiling" }
            val probes = mutationRequest.desired.qaProbeSet
            require(probes == null || allowQaProbeSets) { "QA probe compilation is not admitted" }
            val activeProfile = requireNotNull(
                mutationRequest.desired.profile(mutationRequest.desired.activeProfileId),
            ) {
                "desired configuration must contain its active profile"
            }
            // Every profile's enabled sources must resolve inside the verified catalog, not just
            // the active profile's: a user-created (or future imported) filter could otherwise
            // carry an unknown source id in a non-active profile that the compile would never
            // touch, and the durable adoption would silently pin it. The compile plan is the one
            // coordinator-minted capability, so it is the honest gate for the whole library.
            require(
                mutationRequest.desired.profiles.all { profile ->
                    profile.enabledSourceIds.all { sourceId ->
                        catalog.sources.any { it.id == sourceId } ||
                            profile.customBlocklists.any { it.id == sourceId }
                    }
                },
            ) {
                "a profile references a source outside the verified catalog or its custom sources"
            }
            val catalogSources = catalog.selectedSources(activeProfile)
            val acceptedSnapshot = acceptedSources.mapIndexed { index, source ->
                source.copy(
                    acceptedSha256 = canonicalSha256Hex(source.acceptedSha256),
                    isCustom = catalogSources.getOrNull(index)?.isCustom == true,
                )
            }
            require(
                acceptedSnapshot.map { it.id to it.format } ==
                    catalogSources.map { it.id to it.format },
            ) {
                "accepted source authority must return exact ordered catalog/custom descriptors"
            }
            val expectedSources = acceptedSnapshot.map { source ->
                ArtifactSourceIdentity(source.id, source.acceptedSha256, source.format)
            }
            val manualAllowRules =
                activeProfile.manualRules.allowedDomains.sorted().map(DomainRule::parse) + probes?.allowRules.orEmpty()
            val manualBlockRules =
                activeProfile.manualRules.blockedDomains.sorted().map(DomainRule::parse) + probes?.blockRules.orEmpty()
            val guardrailRules = catalog.guardrailRules.toList() + probes?.guardrailRules.orEmpty()
            val intentionalEmpty =
                expectedSources.isEmpty() &&
                    manualAllowRules.isEmpty() &&
                    manualBlockRules.isEmpty() &&
                    guardrailRules.isEmpty()
            val generation = Math.addExact(mutationRequest.expectedGeneration, 1L)
            val configuration = ArtifactConfigurationIdentity(
                generation = generation,
                fingerprint = canonicalFilterConfigurationFingerprint(
                    configuration = mutationRequest.desired,
                    catalogRevision = catalog.revision,
                    catalogSha256 = catalog.sha256,
                ),
            )
            val compileRequest = FilterCompileRequest(
                configurationIdentity = configuration,
                selectedSources = Collections.unmodifiableList(acceptedSnapshot),
                catalogRevision = catalog.revision,
                catalogSha256 = catalog.sha256,
                maxRules = maxRules,
                intentionalEmpty = intentionalEmpty,
                manualAllowRules = Collections.unmodifiableList(manualAllowRules),
                manualBlockRules = Collections.unmodifiableList(manualBlockRules),
                guardrailRules = Collections.unmodifiableList(guardrailRules),
            )
            val compileInputsSha256 = canonicalFilterCompileInputsSha256(
                configurationIdentity = configuration,
                selectedSources = expectedSources,
                catalogRevision = catalog.revision,
                catalogSha256 = catalog.sha256,
                intentionalEmpty = intentionalEmpty,
                manualAllowRules = manualAllowRules,
                manualBlockRules = manualBlockRules,
                guardrailRules = guardrailRules,
            )
            return AuthorizedFilterCompilePlan(
                mutationRequest = mutationRequest,
                compileRequest = compileRequest,
                expectedConfiguration = configuration,
                expectedSources = expectedSources,
                expectedCatalogRevision = catalog.revision,
                expectedCatalogSha256 = catalog.sha256,
                expectedCompileInputsSha256 = compileInputsSha256,
                expectedIntentionalEmpty = intentionalEmpty,
                expectedMaxRules = maxRules,
            )
        }
    }
}

/**
 * Owns a prepared candidate and permits exactly one staging-cleanup attempt after publication
 * resolves. Cleanup failures are reported separately from the authoritative outcome.
 */
/** Native-only authority held solely at the final write, after compilation and Plus admission. */
fun interface NativeFilterPublicationGuard {
    suspend fun publish(publication: suspend () -> FilterMutationPublishResult): FilterMutationPublishResult
}

class PreparedFilterMutation(
    val candidate: ConfirmedFilterState,
    val compilationReceipt: FilterCompilationReceipt,
    val publicationArtifact: PreparedFilterArtifact? = null,
    val admission: FeatureLimitAdmission? = null,
    val publicationAdmission: (() -> Boolean)? = null,
    val publicationGuard: NativeFilterPublicationGuard? = null,
    private val cleanup: () -> Unit,
) : AutoCloseable {
    constructor(
        candidate: ConfirmedFilterState,
        compilationReceipt: FilterCompilationReceipt,
        cleanup: () -> Unit,
    ) : this(candidate, compilationReceipt, null, null, null, null, cleanup)

    constructor(
        candidate: ConfirmedFilterState,
        compilationReceipt: FilterCompilationReceipt,
        publicationArtifact: PreparedFilterArtifact?,
        admission: FeatureLimitAdmission?,
        publicationAdmission: (() -> Boolean)?,
        cleanup: () -> Unit,
    ) : this(candidate, compilationReceipt, publicationArtifact, admission, publicationAdmission, null, cleanup)

    private val closed = AtomicBoolean(false)

    init {
        publicationArtifact?.let { artifact ->
            require(
                artifact.compilationReceipt.artifactSha256 ==
                    compilationReceipt.artifactSha256 &&
                    artifact.compilationReceipt.artifactIdentity ==
                    compilationReceipt.artifactIdentity,
            ) {
                "publication artifact must match the mutation compiler receipt"
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) cleanup()
    }
}

sealed interface FilterMutationPreparationResult {
    data class Prepared(val mutation: PreparedFilterMutation) : FilterMutationPreparationResult
    data class Rejected(val reason: FilterMutationRejection) : FilterMutationPreparationResult
    data class Failed(val failure: FilterMutationFailure) : FilterMutationPreparationResult
    data object ProtectionUnavailable : FilterMutationPreparationResult
}

fun interface FilterMutationPreparer {
    suspend fun prepare(plan: AuthorizedFilterCompilePlan): FilterMutationPreparationResult
}

sealed interface FilterMutationPublishResult {
    data object Committed : FilterMutationPublishResult
    data class Superseded(
        val authoritative: ConfirmedFilterState,
    ) : FilterMutationPublishResult
    data class Failed(val failure: FilterMutationFailure) : FilterMutationPublishResult
    data object ProtectionUnavailable : FilterMutationPublishResult
}

/**
 * Durable generation-CAS publication of the exact owned candidate.
 *
 * Implementations remain cancellable while preparing and waiting to enter their durable commit
 * authority. After admission, they complete storage and result recovery non-cancellably so every
 * admitted call returns an unambiguous authoritative outcome.
 *
 * [FilterMutationPublishResult.Committed] means the candidate is authoritative.
 * [FilterMutationPublishResult.Superseded] must carry the exact verified authoritative winner.
 * [FilterMutationPublishResult.Failed] guarantees that the candidate never became authoritative.
 * [FilterMutationPublishResult.ProtectionUnavailable] may instead mean that an ambiguous durable
 * write could not be reconciled; callers must fail closed and reload authority before any runtime
 * activation rather than treating the retained in-memory state as current. Once a durable commit
 * point is crossed, implementations must reconcile ambiguous storage errors to Committed or
 * Superseded whenever exact durable state remains readable, and must never throw an ordinary
 * storage exception after an ambiguous write.
 */
fun interface FilterMutationPublisher {
    suspend fun commit(
        expectedGeneration: Long,
        mutation: PreparedFilterMutation,
    ): FilterMutationPublishResult
}

/**
 * Runs expensive preparation concurrently, then serializes the generation recheck, durable
 * commit, and in-memory publication. Staging cleanup is attempted exactly once on every exit;
 * cleanup failures are reported without changing a committed result or masking cancellation.
 */
class TransactionalFilterMutationCoordinator(
    initialState: ConfirmedFilterState,
    private val catalogCompileAuthority: FilterCatalogCompileAuthority,
    private val acceptedSourceAuthority: AcceptedFilterSourceAuthority,
    private val preparer: FilterMutationPreparer,
    private val publisher: FilterMutationPublisher,
    private val reportCleanupFailure: (Exception) -> Unit = {},
    /** Native admission, sampled per operation; publisher must recheck after preparation. */
    private val currentRuleBudget: () -> Int = { catalogCompileAuthority.maxRules },
    /** Trusted build authority, never persisted or supplied by the presentation layer. */
    private val allowQaProbeSets: Boolean = false,
) : FilterMutationCoordinator {
    private val mutableConfirmedState = MutableStateFlow(initialState)
    private val mutableAuthorityState = MutableStateFlow<FilterMutationAuthorityState>(
        FilterMutationAuthorityState.Ready(initialState),
    )
    private val publication = Mutex()
    override val confirmedState: StateFlow<ConfirmedFilterState> = mutableConfirmedState.asStateFlow()
    override val authorityState: StateFlow<FilterMutationAuthorityState> =
        mutableAuthorityState.asStateFlow()

    /** Cache-only speculative preparation. It never calls the preparer or publisher, and a
     * caller must still apply a fresh ordinary mutation before consuming any compiled bytes. */
    suspend fun warmPlan(request: FilterMutationRequest, cachedSources: AcceptedFilterSourceAuthority): AuthorizedFilterCompilePlan? {
        val captured = publication.withLock {
            mutableConfirmedState.value.takeIf {
                mutableAuthorityState.value is FilterMutationAuthorityState.Ready &&
                    it.configurationIdentity.generation == request.expectedGeneration &&
                    it.configuration.activeProfileId != request.desired.activeProfileId && request.desired.qaProbeSet == null
            }
        } ?: return null
        val profile = request.desired.profile(request.desired.activeProfileId) ?: return null
        val plan = try {
            val accepted = cachedSources.acquire(catalogCompileAuthority.selectedSources(profile), false)
            AuthorizedFilterCompilePlan.create(request, catalogCompileAuthority, accepted,
                request.admission?.limits?.maxFilterRules ?: currentRuleBudget(), allowQaProbeSets)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        return publication.withLock { plan.takeIf { mutableConfirmedState.value === captured &&
            mutableAuthorityState.value is FilterMutationAuthorityState.Ready } }
    }

    override suspend fun apply(request: FilterMutationRequest): FilterMutationResult {
        val earlyResult: FilterMutationResult? = publication.withLock {
            val current = mutableConfirmedState.value
            when {
                mutableAuthorityState.value is FilterMutationAuthorityState.ReloadRequired ->
                    FilterMutationResult.ProtectionUnavailable(current)
                current.configurationIdentity.generation != request.expectedGeneration ->
                    FilterMutationResult.Superseded(current)
                current.configuration == request.desired && !request.forceRecompile ->
                    FilterMutationResult.Rejected(current, FilterMutationRejection.NO_CHANGE)
                else -> null
            }
        }
        if (earlyResult != null) return earlyResult

        if (request.desired.qaProbeSet != null && !allowQaProbeSets) {
            return retainCurrent { FilterMutationResult.Rejected(it, FilterMutationRejection.INVALID_DRAFT) }
        }
        val catalogSources = try {
            val activeProfile = requireNotNull(request.desired.profile(request.desired.activeProfileId))
            catalogCompileAuthority.selectedSources(activeProfile)
        } catch (_: IllegalArgumentException) {
            return retainCurrent { FilterMutationResult.Rejected(it, FilterMutationRejection.INVALID_DRAFT) }
        }
        val plan = try {
            val acceptedSources = acceptedSourceAuthority.acquire(catalogSources, request.forceRecompile)
            AuthorizedFilterCompilePlan.create(
                request, catalogCompileAuthority, acceptedSources,
                request.admission?.limits?.maxFilterRules ?: currentRuleBudget(),
                allowQaProbeSets,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: CustomBlocklistSourceAcquisitionException) {
            return sourceUnavailable(request, error)
        } catch (_: Exception) {
            return retainCurrent {
                FilterMutationResult.FailedKeepingPrevious(it, FilterMutationFailure.STAGING_FAILED)
            }
        }

        val prepared = try {
            preparer.prepare(plan)
        } catch (error: CustomBlocklistSourceAcquisitionException) {
            return sourceUnavailable(request, error)
        }
        return when (prepared) {
            is FilterMutationPreparationResult.Prepared -> publishOwned(plan, prepared.mutation)
            is FilterMutationPreparationResult.Rejected -> retainCurrent {
                FilterMutationResult.Rejected(it, prepared.reason)
            }
            is FilterMutationPreparationResult.Failed -> retainCurrent {
                FilterMutationResult.FailedKeepingPrevious(it, prepared.failure)
            }
            FilterMutationPreparationResult.ProtectionUnavailable -> retainCurrent {
                FilterMutationResult.ProtectionUnavailable(it)
            }
        }
    }

    private suspend fun sourceUnavailable(
        request: FilterMutationRequest,
        error: CustomBlocklistSourceAcquisitionException,
    ): FilterMutationResult {
        val sourceName = request.desired.profile(request.desired.activeProfileId)
            ?.customBlocklists?.firstOrNull { it.id == error.sourceId }?.displayName
        return retainCurrent {
            FilterMutationResult.SourceUnavailable(it, error.sourceId, sourceName, error.failure)
        }
    }

    private suspend fun publishOwned(
        plan: AuthorizedFilterCompilePlan,
        mutation: PreparedFilterMutation,
    ): FilterMutationResult = try {
        val request = plan.mutationRequest
        publication.withLock {
            val current = mutableConfirmedState.value
            if (mutableAuthorityState.value is FilterMutationAuthorityState.ReloadRequired) {
                return@withLock FilterMutationResult.ProtectionUnavailable(current)
            }
            if (current.configurationIdentity.generation != request.expectedGeneration) {
                return@withLock FilterMutationResult.Superseded(current)
            }
            if (!isExactNextCandidate(plan, mutation)) {
                return@withLock FilterMutationResult.FailedKeepingPrevious(
                    current,
                    FilterMutationFailure.IDENTITY_MISMATCH,
                )
            }
            if (request.publicationAdmission?.invoke() == false) {
                return@withLock FilterMutationResult.FailedKeepingPrevious(current, FilterMutationFailure.COMMIT_FAILED)
            }

            val published = try {
                publisher.commit(request.expectedGeneration, mutation)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@withLock FilterMutationResult.FailedKeepingPrevious(
                    current,
                    FilterMutationFailure.COMMIT_FAILED,
                )
            }
            val result = withContext(NonCancellable) {
                when (published) {
                    FilterMutationPublishResult.Committed -> {
                        mutableAuthorityState.value =
                            FilterMutationAuthorityState.Ready(mutation.candidate)
                        mutableConfirmedState.value = mutation.candidate
                        FilterMutationResult.Applied(mutation.candidate)
                    }
                    is FilterMutationPublishResult.Superseded -> {
                        val authoritative = published.authoritative
                        if (
                            authoritative.configurationIdentity.generation <=
                            request.expectedGeneration
                        ) {
                            FilterMutationResult.FailedKeepingPrevious(
                                current,
                                FilterMutationFailure.COMMIT_FAILED,
                            )
                        } else {
                            mutableAuthorityState.value =
                                FilterMutationAuthorityState.Ready(authoritative)
                            mutableConfirmedState.value = authoritative
                            FilterMutationResult.Superseded(authoritative)
                        }
                    }
                    is FilterMutationPublishResult.Failed ->
                        FilterMutationResult.FailedKeepingPrevious(current, published.failure)
                    FilterMutationPublishResult.ProtectionUnavailable -> {
                        mutableAuthorityState.value =
                            FilterMutationAuthorityState.ReloadRequired(current)
                        FilterMutationResult.ProtectionUnavailable(current)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            result
        }
    } finally {
        closeAndReport(mutation)
    }

    private fun isExactNextCandidate(
        plan: AuthorizedFilterCompilePlan,
        mutation: PreparedFilterMutation,
    ): Boolean {
        val request = plan.mutationRequest
        val candidate = mutation.candidate
        val verifiedArtifact = candidate.verifiedArtifact
        val artifactIdentity = verifiedArtifact.artifactIdentity
        val artifactConfiguration = artifactIdentity.configuration
        return mutation.admission === request.admission &&
            mutation.publicationAdmission === request.publicationAdmission &&
            mutation.publicationGuard === request.publicationGuard &&
            mutation.compilationReceipt.artifactSha256 == verifiedArtifact.sha256 &&
            mutation.compilationReceipt.artifactIdentity == artifactIdentity &&
            candidate.configuration == request.desired &&
            candidate.configurationIdentity.generation == plan.expectedConfiguration.generation &&
            candidate.configurationIdentity.fingerprint == plan.expectedConfiguration.fingerprint &&
            artifactConfiguration == plan.expectedConfiguration &&
            artifactIdentity.selectedSources == plan.expectedSources &&
            artifactIdentity.catalogRevision == plan.expectedCatalogRevision &&
            artifactIdentity.catalogSha256 == plan.expectedCatalogSha256 &&
            artifactIdentity.intentionalEmpty == plan.expectedIntentionalEmpty &&
            artifactIdentity.compileInputsSha256 == plan.expectedCompileInputsSha256 &&
            artifactIdentity.ruleCounts.total <= plan.expectedMaxRules
    }

    private suspend fun retainCurrent(
        result: (ConfirmedFilterState) -> FilterMutationResult,
    ): FilterMutationResult = publication.withLock {
        val current = mutableConfirmedState.value
        if (mutableAuthorityState.value is FilterMutationAuthorityState.ReloadRequired) {
            FilterMutationResult.ProtectionUnavailable(current)
        } else {
            result(current)
        }
    }

    private fun closeAndReport(mutation: PreparedFilterMutation) {
        try {
            mutation.close()
        } catch (cleanupFailure: Exception) {
            try {
                reportCleanupFailure(cleanupFailure)
            } catch (_: Exception) {
                // Cleanup reporting is diagnostic and must never replace the authoritative result.
            }
        }
    }
}

/**
 * Canonical cross-module fingerprint used before compilation and again at publication admission.
 */
fun canonicalFilterConfigurationFingerprint(
    configuration: FilterLibraryDraft,
    catalogRevision: String,
    catalogSha256: String,
): String {
    require(catalogRevision.isNotBlank())
    val normalizedCatalogSha256 = canonicalSha256Hex(catalogSha256)
    return canonicalSha256("filter-mutation-v3") {
        writeString(catalogRevision)
        writeString(normalizedCatalogSha256)
        writeString(configuration.activeProfileId)
        writeString(configuration.resolverPlan.name)
        // A custom DoH URL or DoT endpoint is appended only when present, so every built-in
        // library keeps its exact pre-existing fingerprint while a custom endpoint can never
        // collide with the pinned Mullvad-only plan it shares its enum name with.
        configuration.customDohUrl?.let { writeString(it) }
        configuration.customDotEndpoint?.let { writeString(it) }
        configuration.customDoqEndpoint?.let { writeString("custom-doq"); writeString(it) }
        // A committed custom name or secondary endpoint is appended only when present, so every
        // built-in and legacy custom library keeps its exact pre-existing fingerprint.
        configuration.customName?.let { writeString(it) }
        configuration.customSecondaryEndpoint?.let { writeString(it) }
        configuration.savedTiers?.let { tiers ->
            val resolver = configuration.resolverTarget.configuration
            // Previously accepted legacy orders keep their established fingerprint. Newly
            // served orders commit every retained row, so a reordered or replaced fallback
            // cannot reuse the artifact identity of a different route.
            if (resolver.toSelection().activeTiers.map { it.entry } !=
                resolver.copy(savedTiers = null).toSelection().activeTiers.map { it.entry } ||
                tiers.any { it.retainedName != null }
            ) {
                writeString("ordered-resolver-tiers-v1")
                writeInt(tiers.size)
                tiers.forEach { tier ->
                    writeString(tier.entry.providerId.rawId)
                    writeString(tier.entry.transport.name)
                    writeString(tier.entry.customDohUrl ?: "")
                    writeString(tier.entry.customDotEndpoint ?: "")
                    writeString(tier.entry.customDoqEndpoint ?: "")
                    writeString(tier.entry.customName ?: "")
                    writeString(tier.entry.customSecondaryEndpoint ?: "")
                    writeBoolean(tier.isEnabled)
                    writeString(tier.retainedName ?: "")
                    tier.entry.customPlainEndpoint?.let { writeString("custom-plain"); writeString(it) }
                }
            }
        }
        val orderedProfiles = configuration.profiles.sortedWith(
            // Seeded profiles order by their onboarding level; user-created filters (null
            // level) sort after them by id, so the canonical order stays deterministic for
            // every library shape.
            compareBy({ it.protectionLevel?.ordinal ?: Int.MAX_VALUE }, { it.id }),
        )
        writeInt(orderedProfiles.size)
        orderedProfiles.forEach { profile ->
            writeString(profile.id)
            writeString(profile.name)
            writeString(profile.protectionLevel?.name ?: "")
            writeCanonicalStrings(profile.enabledSourceIds)
            writeCanonicalStrings(profile.manualRules.allowedDomains)
            writeCanonicalStrings(profile.manualRules.blockedDomains)
            // Custom blocklist definitions are appended only when present, so every library
            // without custom sources keeps its exact pre-existing fingerprint. The definition
            // is included (not just the enabled id) so a URL/format change always re-fingerprints.
            if (profile.customBlocklists.isNotEmpty()) {
                writeInt(profile.customBlocklists.size)
                profile.customBlocklists.sortedBy { it.id }.forEach { source ->
                    writeString(source.id)
                    writeString(source.displayName)
                    writeString(source.sourceUrl)
                    writeString(source.parseFormat.name)
                    writeString(source.lastAcceptedHash ?: "")
                }
            }
        }
        configuration.qaProbeSet?.let { probes ->
            writeString("device-qa-exact-probes-v1")
            probes.allDomains.forEach(::writeString)
        }
    }
}

private fun CanonicalSha256Encoder.writeCanonicalStrings(values: Set<String>) {
    val ordered = values.sorted()
    writeInt(ordered.size)
    ordered.forEach(::writeString)
}

private fun canonicalSha256Hex(value: String): String {
    val normalized = value.lowercase(Locale.ROOT)
    require(normalized.length == 64 && normalized.all { it in '0'..'9' || it in 'a'..'f' }) {
        "SHA-256 must contain exactly 64 hexadecimal characters"
    }
    return normalized
}
