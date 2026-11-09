package com.lavasec.app

import com.lavasec.app.billing.NativePlusEntitlementOwner
import com.lavasec.app.foundation.subscription.FeatureLimitAdmission
import com.lavasec.app.foundation.subscription.FeatureLimits

import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.lavasec.app.data.AcceptedSeededSource
import com.lavasec.app.data.BundledBlocklistCatalog
import com.lavasec.app.data.CachedCatalogSourceAcquirer
import com.lavasec.app.data.CatalogFilterCompileRequestFactory
import com.lavasec.app.data.CatalogRepository
import com.lavasec.app.data.CatalogRuntimeCatalog
import com.lavasec.app.data.InitialFilterProvisionResult
import com.lavasec.app.data.InitialFilterProvisioner
import com.lavasec.app.data.NetworkBoundSeededSourceTransport
import com.lavasec.app.data.SeededFilterConfiguration
import com.lavasec.app.data.SeededSourceCache
import com.lavasec.app.data.SeededSourceDescriptor
import com.lavasec.app.data.SeededSourceTransport
import com.lavasec.app.data.descriptor
import com.lavasec.app.data.reconcileLibraryForCatalog
import com.lavasec.app.data.withReleaseCatalogUpgradeCleanup
import com.lavasec.app.data.withRetiredCatalogSelectionsRemoved
import com.lavasec.app.directboot.DeviceProtectedArtifactPublisher
import com.lavasec.app.foundation.catalog.CatalogArtifact
import com.lavasec.app.foundation.filter.CustomBlocklistSourceAcquisitionException
import com.lavasec.app.foundation.config.AppConfiguration
import com.lavasec.app.foundation.filter.FilterCompiler
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.ManualDomainRules
import com.lavasec.app.foundation.resolver.FilterResolverPlan
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.platform.AcceptedFilterSourceAuthority
import com.lavasec.app.platform.CatalogCompileSourceIdentity
import com.lavasec.app.platform.ConfirmedFilterState
import com.lavasec.app.platform.FilterMutationAuthorityState
import com.lavasec.app.platform.CredentialFilterArtifactStagingResult
import com.lavasec.app.platform.CredentialFilterArtifactStagingVerifier
import com.lavasec.app.platform.FilterCatalogCompileAuthority
import com.lavasec.app.platform.FilterMutationFailure
import com.lavasec.app.platform.FilterMutationPreparationResult
import com.lavasec.app.platform.FilterMutationPreparer
import com.lavasec.app.platform.FilterMutationPublishResult
import com.lavasec.app.platform.FilterMutationPublisher
import com.lavasec.app.platform.FilterMutationRequest
import com.lavasec.app.platform.FilterMutationResult
import com.lavasec.app.platform.PreparedFilterMutation
import com.lavasec.app.platform.ProtectionIntentWriteResult
import com.lavasec.app.platform.ProtectionState
import com.lavasec.app.platform.TransactionalFilterMutationCoordinator
import com.lavasec.app.product.NativeDomainActionHost
import com.lavasec.app.product.FilterLibraryMutationResult
import com.lavasec.app.product.FilterLibraryOwner
import com.lavasec.app.product.FilterLibraryReviewBasis
import com.lavasec.app.product.FilterLibraryPort
import com.lavasec.app.product.domain.ActiveFilterAuthority
import com.lavasec.app.product.domain.ActiveFilterAuthorityPort
import com.lavasec.app.product.domain.DomainActionApplyResult
import com.lavasec.app.product.domain.DomainActionAuthenticationPolicy
import com.lavasec.app.product.domain.DomainActionAuthenticationResult
import com.lavasec.app.product.domain.DomainActionAuthenticator
import com.lavasec.app.product.domain.DomainActionMutationPort
import com.lavasec.app.product.FilterProfileSwitchPort
import com.lavasec.app.product.FilterProfileSwitchRejection
import com.lavasec.app.product.FilterProfileSwitchResult
import com.lavasec.app.product.ResolverPlanSwitchPort
import com.lavasec.app.product.ResolverPlanSwitchRejection
import com.lavasec.app.product.ResolverPlanSwitchResult
import com.lavasec.app.foundation.filter.FilterLibraryEditor
import com.lavasec.app.foundation.filter.FilterLibraryPolicy
import com.lavasec.app.storage.CredentialFilterConfigurationStore
import com.lavasec.app.vpn.LavaVpnRuntimeBridge
import com.lavasec.app.vpn.LavaVpnServiceClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Credential-only exact catalog, compiler, artifact, and filter mutation composition. */
internal class ProductionFilterAuthority(
    private val context: Context,
    initialCatalog: BundledBlocklistCatalog,
    private val configurationRepository: com.lavasec.app.data.CredentialConfigurationRepository,
    private val filterStore: CredentialFilterConfigurationStore,
    private val devicePublisher: DeviceProtectedArtifactPublisher,
    private val protectionState: () -> ProtectionState = { LavaVpnRuntimeBridge.state.value },
    private val reloadProtection: suspend () -> Unit = { LavaVpnServiceClient(context).reload() },
    // A missing native owner is a Free-only composition, used by isolated hosts/tests.
    private val plusOwner: NativePlusEntitlementOwner? = null,
    private val allowQaProbeSets: Boolean = BuildConfig.DEBUG,
    private val compiler: FilterCompiler = FilterCompiler(File(context.cacheDir, COMPILER_DIRECTORY)),
    private val sourceTransportFactory: suspend () -> SeededSourceTransport = {
        NetworkBoundSeededSourceTransport(selectOrdinaryNetwork(context))
    },
) {
    /**
     * The effective runtime catalog. It starts as the pinned bundle and is replaced only by
     * [installCatalog] after a validated remote selection has been recompiled and committed. A
     * volatile field (never a local capture) so the ports built by [createMutationHosts] and the
     * request factory always read the installed catalog, not the one present at construction.
     */
    @Volatile private var catalog: BundledBlocklistCatalog = initialCatalog
    private val freeAdmission = FeatureLimitAdmission(FeatureLimits.FREE)
    private fun captureAdmission(): FeatureLimitAdmission? =
        if (plusOwner == null) freeAdmission else plusOwner.captureAdmission()
    private fun currentLimits(): FeatureLimits = plusOwner?.state?.value?.limits ?: FeatureLimits.FREE

    private val sourceCache = SeededSourceCache(
        File(context.noBackupFilesDir, SOURCE_CACHE_DIRECTORY),
    )
    private val sourceAcquirer = CachedCatalogSourceAcquirer(sourceCache, sourceTransportFactory)
    private val compileMutex = Mutex()
    private val preparedCache = com.lavasec.app.data.PreparedFilterCache()
    private val runtimeReload = RuntimeReloadReconciliation(
        protection = protectionState,
        reload = reloadProtection,
    )

    // The single live mutation coordinator. It is created with the catalog effective at graph
    // creation and replaced atomically by installCatalog after a remote catalog is committed.
    @Volatile private var activeCoordinator: TransactionalFilterMutationCoordinator? = null
    private val coordinatorAttachLock = Any()
    private var coordinatorCollectors: List<Job> = emptyList()
    private var coordinatorScope: CoroutineScope? = null

    // Serializes catalog installation and every live mutation that uses its coordinator.
    // The repository temporarily reads a candidate catalog during install. An ordinary write
    // must not commit an old-catalog artifact in that interval and then fail pointer adoption.
    // Capture the active coordinator inside this coroutine lock; a waiter cannot reuse a
    // coordinator retired by the preceding install.
    private val installMutex = Mutex()

    /**
     * True while a committed filter generation has not been reloaded into the live runtime:
     * armed when a post-commit reload dispatch failed, discharged by a later successful
     * dispatch or by a terminal protection state (a fresh runtime attaches to the current
     * durable filter anyway). The protection owner collects this and retries the attachment.
     */
    val runtimeReloadRequired: StateFlow<Boolean> = runtimeReload.required

    /** Sticky within this authority: durable catalog publication needs a fresh graph/pointer. */
    private val mutableCatalogReconstructionRequired = MutableStateFlow(false)
    val catalogReconstructionRequired: StateFlow<Boolean> = mutableCatalogReconstructionRequired.asStateFlow()

    /** Retries the deferred runtime attachment once; true when the requirement is discharged. */
    suspend fun reconcileRuntimeReload(): Boolean = runtimeReload.reconcileOnce()

    // Read the coordinator directly: the presentation StateFlow is an asynchronous mirror.
    private fun domainCheckState(): ConfirmedFilterState? = synchronized(coordinatorAttachLock) {
        val coordinator = activeCoordinator ?: return@synchronized null
        if (mutableCatalogReconstructionRequired.value || coordinator.authorityState.value is FilterMutationAuthorityState.ReloadRequired) null
        else coordinator.confirmedState.value
    }

    fun domainCheckRevision(): String? = domainCheckState()?.configurationIdentity?.let { "${it.generation}:${it.fingerprint}" }

    /** Read the saved filter through the same validated artifact loader used by the VPN.
     * No DNS request or history write occurs, and no mapping escapes this owned lease. */
    suspend fun checkDomain(domain: String): com.lavasec.app.product.LocalDomainCheckEvidence? = withContext(Dispatchers.IO) {
        installMutex.withLock {
            val expected = domainCheckState() ?: return@withContext null
            val opened = filterStore.readAuthoritativeRuntime() as? com.lavasec.app.storage.CredentialFilterConfigurationRuntimeRead.Available
                ?: return@withContext null
            opened.use {
                if (it.state.configurationIdentity != expected.configurationIdentity) return@withContext null
                it.takeLease().use { lease ->
                    val read = lease.withDecisions { source -> source.decideNormalized(domain) }
                        as? com.lavasec.app.platform.FilterRuntimeLeaseRead.Available<com.lavasec.app.foundation.filter.FilterDecision> ?: return@withContext null
                    if (domainCheckState()?.configurationIdentity != expected.configurationIdentity) return@withContext null
                    com.lavasec.app.product.LocalDomainCheckEvidence(domain, read.value, it.state)
                }
            }
        }
    }

    suspend fun provisionInitial(
        configuration: AppConfiguration,
        identity: com.lavasec.app.platform.ConfigurationIdentity,
    ): InitialFilterProvisionResult = InitialFilterProvisioner(
        catalog = catalog,
        configurationStore = filterStore,
        // Built from the catalog effective at provisioning time, not the construction-time one:
        // a remote catalog installed before this call must be the one the request pins.
        requestFactory = CatalogFilterCompileRequestFactory(
            catalog = catalog,
            sourceAcquirer = sourceAcquirer,
            maxRules = currentLimits().maxFilterRules,
            allowQaProbeSets = allowQaProbeSets,
        ),
        compile = compiler::compile,
        stagePublicArtifact = { devicePublisher.stage(it) != null },
        historicalCatalogs = { CatalogRepository(context.assets).loadHistoricalBundled() },
    ).provision(configuration, identity)

    /** Release upgrade cleanup uses a fresh ordinary artifact and the existing generation CAS.
     * Failure retains the debug record for recovery; the store independently refuses its lease.
     */
    suspend fun removeRetainedQaForRelease(initial: ConfirmedFilterState): Boolean {
        if (allowQaProbeSets || initial.configuration.qaProbeSet == null) return true
        val admission = captureAdmission() ?: return false
        val result = mutationCoordinator(initial).apply(
            FilterMutationRequest(
                expectedGeneration = initial.configurationIdentity.generation,
                desired = initial.configuration.withReleaseCatalogUpgradeCleanup(),
                admission = admission,
            ),
        )
        return result is FilterMutationResult.Applied
    }

    /** Retire historical selections using the same compile/CAS/mirror transaction as user edits. */
    suspend fun migrateRetiredCatalogSelections(initial: ConfirmedFilterState): Boolean {
        val desired = initial.configuration.withRetiredCatalogSelectionsRemoved()
        if (desired == initial.configuration) return true
        val admission = captureAdmission() ?: return false
        // A failed download/compile/commit retains the prior library and artifact. A later
        // credential graph retries; never rewrite saved IDs independently of their artifact.
        return applyLibraryMutation(mutationCoordinator(initial), desired, admission) is
            FilterLibraryMutationResult.Applied
    }

    // The latest confirmed state, including in-generation domain-mutation commits.
    // The Filters library derives its active rule count from this flow so the
    // surface stays synchronized without a graph rotation. When the mutation
    // authority requires reconstruction (ReloadRequired), facts are withheld even
    // though the coordinator retains its last confirmed state.
    private val confirmedStateFlow = MutableStateFlow<ConfirmedFilterState?>(null)
    private val authorityStateFlow = MutableStateFlow<FilterMutationAuthorityState?>(null)
    val confirmedState: StateFlow<ConfirmedFilterState?> = combine(
        confirmedStateFlow,
        authorityStateFlow,
    ) { confirmed, authority ->
        if (authority is FilterMutationAuthorityState.ReloadRequired) null else confirmed
    }.stateIn(
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        SharingStarted.Eagerly,
        null,
    )

    /** Observe retained runtime facts without exposing incompatible editing ports. */
    fun observeConfirmed(initial: ConfirmedFilterState, scope: CoroutineScope) {
        attachCoordinator(mutationCoordinator(initial), scope)
    }

    /**
     * Creates the exact mutation ports of this generation around one coordinator.
     *
     * The coordinator must be single per credential generation: its publication mutex and
     * generation CAS serialize every mutation (domain reviews and profile switches alike), and
     * a second coordinator would fork the generation counter. Both ports therefore share the
     * same coordinator and its confirmed/authority state collectors.
     *
     * [onLibraryApplied] observes the exact before/after library of every Applied library
     * mutation (draft commit, create, rename, staged delete, restore defaults) so the Network
     * Activity producer can decide whether the filter in effect actually changed.
     */
    fun createMutationHosts(
        initial: ConfirmedFilterState,
        scope: CoroutineScope,
        onLibraryApplied: (FilterLibraryDraft, FilterLibraryDraft) -> Unit = { _, _ -> },
    ): ProductionFilterMutationHosts {
        val coordinator = mutationCoordinator(initial)
        attachCoordinator(coordinator, scope)
        val libraryOwner = FilterLibraryOwner(
            port = object : FilterLibraryPort {
                override fun currentLibrary(): FilterLibraryDraft =
                    activeMutationCoordinator().confirmedState.value.configuration

                override fun reviewBasis(): FilterLibraryReviewBasis {
                    val current = activeMutationCoordinator().confirmedState.value
                    return FilterLibraryReviewBasis(current.configuration, current.configurationIdentity.generation)
                }

                override suspend fun apply(
                    desired: FilterLibraryDraft,
                    admission: FeatureLimitAdmission,
                ): FilterLibraryMutationResult = installMutex.withLock {
                    val coordinator = activeMutationCoordinator()
                    val before = coordinator.confirmedState.value.configuration
                    val result = applyLibraryMutation(coordinator, desired, admission)
                    if (result is FilterLibraryMutationResult.Applied) {
                        // The Applied result means exactly [desired] became authoritative;
                        // re-reading the shared coordinator after the suspension could see a
                        // newer mutation and report a spurious filter-change pair.
                        onLibraryApplied(before, desired)
                    }
                    result
                }

                override suspend fun applyReviewed(
                    desired: FilterLibraryDraft,
                    expected: FilterLibraryReviewBasis,
                    admission: FeatureLimitAdmission,
                ): FilterLibraryMutationResult = installMutex.withLock {
                    val result = applyLibraryMutation(activeMutationCoordinator(), desired, admission, expected)
                    if (result is FilterLibraryMutationResult.Applied) onLibraryApplied(expected.library, desired)
                    result
                }

                override suspend fun applyRetainedReplacement(
                    desired: FilterLibraryDraft,
                    expected: FilterLibraryReviewBasis,
                    admission: FeatureLimitAdmission,
                ): FilterLibraryMutationResult = installMutex.withLock {
                    val result = applyLibraryMutation(activeMutationCoordinator(), desired, admission, expected,
                        retainedManualQuota = true)
                    if (result is FilterLibraryMutationResult.Applied) onLibraryApplied(expected.library, desired)
                    result
                }

                override suspend fun applyReviewedWhileCurrent(
                    desired: FilterLibraryDraft,
                    expected: FilterLibraryReviewBasis,
                    admission: FeatureLimitAdmission,
                    isCurrent: () -> Boolean,
                ): FilterLibraryMutationResult = installMutex.withLock {
                    val result = applyLibraryMutation(activeMutationCoordinator(), desired, admission, expected, isCurrent)
                    if (result is FilterLibraryMutationResult.Applied) onLibraryApplied(expected.library, desired)
                    result
                }

                override suspend fun applyReviewedForQaComposite(
                    desired: FilterLibraryDraft,
                    expected: FilterLibraryReviewBasis,
                    admission: FeatureLimitAdmission,
                    isCurrent: () -> Boolean,
                    publicationGuard: com.lavasec.app.platform.NativeFilterPublicationGuard,
                ): FilterLibraryMutationResult = installMutex.withLock {
                    val result = applyLibraryMutation(activeMutationCoordinator(), desired, admission, expected, isCurrent, publicationGuard)
                    if (result is FilterLibraryMutationResult.Applied) onLibraryApplied(expected.library, desired)
                    result
                }

                override suspend fun restoreDefaults(admission: FeatureLimitAdmission): FilterLibraryMutationResult = installMutex.withLock {
                    val coordinator = activeMutationCoordinator()
                    val current = coordinator.confirmedState.value.configuration
                    val desired = FilterLibraryEditor.restoreDefaults(
                        library = current,
                        catalog = catalog.filterSnapshot,
                    )
                    val result = applyLibraryMutation(coordinator, desired, admission)
                    if (result is FilterLibraryMutationResult.Applied) {
                        onLibraryApplied(current, desired)
                    }
                    result
                }
            },
            scope = scope,
            limits = ::currentLimits,
            captureAdmission = ::captureAdmission,
            creationName = { context.getString(R.string.filter_identity_untitled) },
        )
        val domainHost = NativeDomainActionHost(
            authenticationPolicy = DomainActionAuthenticationPolicy.SKIPPED,
            authenticator = DomainActionAuthenticator {
                DomainActionAuthenticationResult.Authorized
            },
            authority = ActiveFilterAuthorityPort {
                val current = activeMutationCoordinator().confirmedState.value
                ActiveFilterAuthority(
                    generation = current.configurationIdentity.generation,
                    activeProfile = requireNotNull(
                        current.configuration.profile(current.configuration.activeProfileId),
                    ),
                    // A draft on the active filter blocks domain actions (iOS parity: "Save or
                    // discard them in Filters before changing domains here").
                    hasUnsavedChanges = libraryOwner.hasUnsavedActiveFilterDraft(
                        current.configuration.activeProfileId,
                    ),
                )
            },
            mutation = DomainActionMutationPort { review, admission ->
                installMutex.withLock {
                    val coordinator = activeMutationCoordinator()
                    val current = coordinator.confirmedState.value
                    val active = requireNotNull(
                        current.configuration.profile(current.configuration.activeProfileId),
                    )
                    if (
                        review.expectedGeneration != current.configurationIdentity.generation ||
                        review.sourceProfile != active ||
                        review.activeProfileId != current.configuration.activeProfileId
                    ) {
                        DomainActionApplyResult.Superseded
                    } else {
                        val result = coordinator.apply(
                            FilterMutationRequest(
                                expectedGeneration = review.expectedGeneration,
                                desired = current.configuration.withProfile(review.desiredProfile),
                                admission = admission,
                            ),
                        ).toDomainApplyResult()
                        reloadCommittedFilterIfRunning(
                            result = result,
                            applied = result is DomainActionApplyResult.Applied,
                            protection = protectionState(),
                            reload = reloadProtection,
                        )
                    }
                }
            },
            limits = ::currentLimits,
            captureAdmission = ::captureAdmission,
        )
        return ProductionFilterMutationHosts(
            domainActions = domainHost,
            profileSwitch = FilterProfileSwitchPort { targetProfileId ->
                installMutex.withLock { switchActiveProfile(activeMutationCoordinator(), targetProfileId) }
            },
            resolverSwitch = ResolverPlanSwitchPort { plan ->
                installMutex.withLock { switchResolverPlan(activeMutationCoordinator(), plan) }
            },
            library = libraryOwner,
        )
    }

    /**
     * (Re)attaches one coordinator as the live mutation authority and mirrors its flows into the
     * stable [confirmedStateFlow]/[authorityStateFlow] the product graph observes. Replacing the
     * coordinator is only done by [installCatalog], under the same lock that serializes the swap.
     */
    private fun attachCoordinator(
        coordinator: TransactionalFilterMutationCoordinator,
        scope: CoroutineScope,
    ) {
        synchronized(coordinatorAttachLock) {
            coordinatorCollectors.forEach { it.cancel() }
            activeCoordinator = coordinator
            coordinatorScope = scope
            confirmedStateFlow.value = coordinator.confirmedState.value
            authorityStateFlow.value = coordinator.authorityState.value
            coordinatorCollectors = listOf(
                scope.launch { coordinator.confirmedState.collect { confirmedStateFlow.value = it } },
                scope.launch { coordinator.authorityState.collect { authorityStateFlow.value = it } },
            )
        }
    }

    private fun activeMutationCoordinator(): TransactionalFilterMutationCoordinator =
        checkNotNull(activeCoordinator) { "filter mutation authority is not attached" }

    /**
     * One saved-filter-library mutation over the shared coordinator. The desired library is the
     * exact current configuration transformed by the pure editor (create/rename/delete/restore
     * or a per-filter draft commit), so the coordinator recompiles the ACTIVE filter and adopts
     * the whole library in one verified generation: the artifact identity commits the complete
     * library, and any failure keeps the previous active artifact authoritative. A no-op desired
     * (the edit produced the current library) is idempotent success.
     */
    private suspend fun applyLibraryMutation(
        coordinator: TransactionalFilterMutationCoordinator,
        desired: FilterLibraryDraft,
        admission: FeatureLimitAdmission,
        expected: FilterLibraryReviewBasis? = null,
        publicationAdmission: (() -> Boolean)? = null,
        publicationGuard: com.lavasec.app.platform.NativeFilterPublicationGuard? = null,
        retainedManualQuota: Boolean = false,
    ): FilterLibraryMutationResult {
        val preparation = kotlinx.coroutines.currentCoroutineContext()[com.lavasec.app.product.FilterPreparationProgress]
        val admitted = {
            publicationAdmission?.invoke() != false && preparation?.isCurrent?.invoke() != false
        }
        val guard = preparation?.publicationGuard?.let { foreground ->
            com.lavasec.app.platform.NativeFilterPublicationGuard { publication ->
                foreground.publish { publicationGuard?.publish(publication) ?: publication() }
            }
        } ?: publicationGuard
        if (!admitted()) return FilterLibraryMutationResult.Superseded
        val current = coordinator.confirmedState.value
        if (expected != null && (current.configuration != expected.library ||
                current.configurationIdentity.generation != expected.generation)) return FilterLibraryMutationResult.Superseded
        // The expected generation below comes from this SAME read. A concurrent publisher
        // after review comparison is therefore rejected by the coordinator's generation CAS.
        if (current.configuration == desired) {
            return FilterLibraryMutationResult.Applied(
                current.configurationIdentity.generation,
            )
        }
        if (!retainedManualQuota) {
            activeManualRuleRejection(current.configuration, desired, admission.limits)?.let {
                return FilterLibraryMutationResult.Rejected(it)
            }
        }
        val result = coordinator.apply(
            FilterMutationRequest(
                expectedGeneration = current.configurationIdentity.generation,
                desired = desired,
                admission = admission,
                publicationAdmission = admitted,
                publicationGuard = guard,
            ),
        )
        return reloadCommittedFilterBestEffort(
            result = result,
            applied = result is FilterMutationResult.Applied,
            protection = protectionState(),
            reload = reloadProtection,
            onDispatchSucceeded = runtimeReload::onDispatchSucceeded,
            onDispatchFailed = runtimeReload::onDispatchFailed,
        ).toLibraryResult()
    }

    /**
     * Whole-profile switch over the shared coordinator: the desired draft is the exact current
     * configuration with only its active profile id replaced (`withActiveProfile`), so the saved
     * filter's own blocklists and manual rules load untouched — matching the iOS switch
     * semantics where applying a saved filter loads that filter, it does not edit one
     * (FilterLibraryView.swift `applyFilter`, AppViewModel.swift `switchToFilter`).
     *
     * The pre-check refuses unknown, no-op, and frozen targets up front; the coordinator's
     * generation CAS still re-verifies at publication, so a user-facing rejection can never
     * escalate into an authority reconstruction.
     */
    private suspend fun switchActiveProfile(
        coordinator: TransactionalFilterMutationCoordinator,
        targetProfileId: String,
    ): FilterProfileSwitchResult {
        val current = coordinator.confirmedState.value
        val currentConfiguration = current.configuration
        val admission = captureAdmission() ?: return FilterProfileSwitchResult.Failed(
            currentConfiguration.activeProfileId, FilterMutationFailure.COMMIT_FAILED,
        )
        val rejection = currentConfiguration.profileSwitchRejection(targetProfileId, admission.limits)
        if (rejection != null) {
            return FilterProfileSwitchResult.Rejected(currentConfiguration.activeProfileId, rejection)
        }
        val desired = currentConfiguration.withActiveProfile(targetProfileId)
        if (activeManualRuleRejection(currentConfiguration, desired, admission.limits) != null) {
            return FilterProfileSwitchResult.Rejected(currentConfiguration.activeProfileId,
                FilterProfileSwitchRejection.INVALID_DRAFT)
        }
        val result = coordinator.apply(
            FilterMutationRequest(
                expectedGeneration = current.configurationIdentity.generation,
                desired = desired,
                admission = admission,
            ),
        )
        return reloadCommittedFilterBestEffort(
            result = result,
            applied = result is FilterMutationResult.Applied,
            protection = protectionState(),
            reload = reloadProtection,
            onDispatchSucceeded = runtimeReload::onDispatchSucceeded,
            onDispatchFailed = runtimeReload::onDispatchFailed,
        ).toProfileSwitchResult()
    }

    /**
     * Resolver-plan switch over the shared coordinator: the desired draft is the exact
     * current configuration with only its resolver plan replaced (`withResolverPlan`), so
     * seeded profiles, blocklist sources, and the active profile's manual rules are all
     * preserved — the switch changes only how lookups resolve. This matches iOS
     * `setResolver` (AppViewModel.swift), where the resolver preset moves while the loaded
     * filter stays put.
     *
     * The durable adoption must not run through the whole-profile commit path: that
     * adoption validates the resolver authority unchanged, so the shared publisher routes
     * a plan-changing candidate to `CredentialConfigurationRepository
     * .commitResolverConfiguration` instead (see [compositePublisher]). The recompile is
     * source-cache-backed (no network acquisition unless the cache needs a refresh), and a
     * committed switch reloads the live runtime exactly like [switchActiveProfile], with a
     * failed reload dispatch arming the same runtime-reload reconciliation.
     */
    private suspend fun switchResolverPlan(
        coordinator: TransactionalFilterMutationCoordinator,
        target: FilterResolverPlan,
    ): ResolverPlanSwitchResult {
        val admission = captureAdmission() ?: return ResolverPlanSwitchResult.Failed(
            target.plan, FilterMutationFailure.COMMIT_FAILED,
        )
        val current = coordinator.confirmedState.value
        if (current.configuration.resolverTarget == target) {
            return ResolverPlanSwitchResult.Rejected(
                target.plan,
                ResolverPlanSwitchRejection.NO_CHANGE,
            )
        }
        val result = coordinator.apply(
            FilterMutationRequest(
                expectedGeneration = current.configurationIdentity.generation,
                desired = current.configuration.withResolverPlan(target),
                admission = admission,
            ),
        )
        return reloadCommittedFilterBestEffort(
            result = result,
            applied = result is FilterMutationResult.Applied,
            protection = protectionState(),
            reload = reloadProtection,
            onDispatchSucceeded = runtimeReload::onDispatchSucceeded,
            onDispatchFailed = runtimeReload::onDispatchFailed,
        ).toResolverPlanSwitchResult(target.plan)
    }

    /**
     * Installs one validated remote catalog into the live filter authority, atomically and
     * generation-fenced, and reloads the active filter if protection is running.
     *
     * The sequence is exactly: project the selection into a runtime catalog, reconcile the saved
     * library against the new source set, hand the whole library to a fresh
     * [TransactionalFilterMutationCoordinator] built over the new catalog (which recompiles the
     * ACTIVE filter and commits through the same durable publisher/generation CAS), and only then
     * adopt the new catalog/coordinator. Pre-commit failures keep the resident artifact and
     * catalog; post-commit cancellation keeps the committed catalog and propagates its outcome.
     * The durable commit is either the new candidate or untouched
     * ([FilterMutationResult.FailedKeepingPrevious] guarantees the candidate never became
     * authoritative), and the repository's catalog is restored before returning.
     *
     * A process restart resolves the same catalog from the durable catalog cache in
     * [ProductionApplicationGraphFactory], so the install survives graph rotations.
     * [onCatalogAdopted] synchronously publishes the graph's in-memory catalog projection;
     * it runs under install serialization, before exposing the new confirmed state.
     */
    suspend fun installCatalog(
        artifact: CatalogArtifact,
        isCurrent: () -> Boolean,
        refreshExistingSources: Boolean = false,
        onCatalogAdopted: (BundledBlocklistCatalog) -> Unit,
    ): Boolean {
        // Explicit local refresh may recompile the resident bundle, but cannot install remote
        // metadata through that path. Automatic metadata updates retain their no-change guard.
        if (refreshExistingSources && artifact.provenance !=
            com.lavasec.app.foundation.catalog.CatalogProvenance.BUNDLED) return false
        val installed = prepareAndInstallCatalog(artifact, onCatalogAdopted, isCurrent, refreshExistingSources)
        if (!installed) return false
        if (protectionState().requiresFilterReload()) {
            try {
                reloadProtection()
                runtimeReload.onDispatchSucceeded()
            } catch (cancelled: CancellationException) {
                runtimeReload.onDispatchFailed()
                throw com.lavasec.app.data.CatalogInstallCommittedCancellation(cancelled)
            } catch (_: Exception) {
                // The durable commit is authoritative; arm the reconciliation exactly like any
                // other best-effort post-commit reload.
                runtimeReload.onDispatchFailed()
            }
        }
        return true
    }

    /** Prepare against one captured catalog without holding foreground mutation admission.
     * The publisher reacquires admission only after source acquisition and compilation, then
     * retains it through durable publication and coordinator adoption. A foreground winner
     * invalidates the prepared candidate instead of waiting behind background downloads. */
    private suspend fun prepareAndInstallCatalog(
        artifact: CatalogArtifact,
        onCatalogAdopted: (BundledBlocklistCatalog) -> Unit,
        isCurrent: () -> Boolean,
        refreshExistingSources: Boolean,
    ): Boolean {
        data class Captured(
            val previousCatalog: BundledBlocklistCatalog,
            val newCatalog: BundledBlocklistCatalog,
            val coordinator: TransactionalFilterMutationCoordinator,
            val current: ConfirmedFilterState,
            val admission: FeatureLimitAdmission,
        )
        val captured = installMutex.withLock {
            if (!isCurrent()) return false
            val previous = catalog
            val next = CatalogRuntimeCatalog.fromArtifact(artifact, bundled = previous)
            if (!refreshExistingSources && next.revision == previous.revision && next.sha256 == previous.sha256) return false
            val coordinator = activeCoordinator ?: return false
            if (coordinator.authorityState.value is FilterMutationAuthorityState.ReloadRequired) return false
            Captured(previous, next, coordinator, coordinator.confirmedState.value, captureAdmission() ?: return false)
        }
        val (previousCatalog, newCatalog, coordinator, current, admission) = captured
        val desired = reconcileLibraryForCatalog(current.configuration, CatalogRuntimeCatalog.selectableSourceIds(newCatalog))
        val catalogPublicationPending = java.util.concurrent.atomic.AtomicReference<Captured?>(captured)
        var publicationHeld = false
        var repositoryChanged = false
        var durablyCommitted: ConfirmedFilterState? = null
        val candidateCoordinator = mutationCoordinator(current, newCatalog,
            publicationBoundary = { publisher -> FilterMutationPublisher { expectedGeneration, mutation ->
                // After adoption this coordinator serves normal mutations whose entry already
                // owns installMutex. The catalog boundary applies to its first publication only.
                val basis = catalogPublicationPending.get()
                    ?: return@FilterMutationPublisher publisher.commit(expectedGeneration, mutation)
                // prepare() has released the compiler before taking this mutex. Foreground
                // work keeps its existing install -> compiler order without inversion.
                installMutex.lock()
                publicationHeld = true
                if (!isCurrent() || catalog !== basis.previousCatalog || activeCoordinator !== basis.coordinator ||
                    basis.coordinator.confirmedState.value !== basis.current ||
                    basis.coordinator.authorityState.value is FilterMutationAuthorityState.ReloadRequired) {
                    return@FilterMutationPublisher FilterMutationPublishResult.Failed(FilterMutationFailure.COMMIT_FAILED)
                }
                preparedCache.clear()
                // Only this short commit interval may expose the candidate to pointer adoption.
                configurationRepository.installCatalog(basis.newCatalog)
                repositoryChanged = true
                publisher.commit(expectedGeneration, mutation)
            } },
        ) { durablyCommitted = it }
        fun hasCommittedCandidate(): Boolean {
            val committed = durablyCommitted ?: return false
            return committed.configuration == desired &&
                committed.configurationIdentity.generation == current.configurationIdentity.generation + 1 &&
                committed.configurationIdentity.fingerprint ==
                    com.lavasec.app.platform.canonicalFilterConfigurationFingerprint(
                        desired, newCatalog.revision, newCatalog.sha256)
        }
        fun restoreUncommittedRepository() {
            // A candidate rejected after preparation never changed the repository; restoring
            // its captured catalog would overwrite the foreground winner's newer authority.
            if (repositoryChanged) configurationRepository.installCatalog(previousCatalog)
        }
        fun adoptCommitted() {
            configurationRepository.installCatalog(newCatalog)
            catalog = newCatalog
            onCatalogAdopted(newCatalog)
            // Release the old coordinator/state as well as the one-install admission phase.
            catalogPublicationPending.set(null)
            coordinatorScope?.let { attachCoordinator(candidateCoordinator, it) }
            if (candidateCoordinator.authorityState.value is FilterMutationAuthorityState.ReloadRequired) {
                mutableCatalogReconstructionRequired.value = true
            }
        }
        try {
            val result = try {
                suspend fun applyCandidate() = candidateCoordinator.apply(
                    FilterMutationRequest(
                        expectedGeneration = current.configurationIdentity.generation,
                        desired = desired,
                        forceRecompile = true,
                        admission = admission,
                        // Recheck the graph again under the final durable store lock.
                        publicationAdmission = isCurrent,
                    ),
                )
                // A user-requested refresh reacquires every selected upstream, including custom
                // lists. Background metadata repair keeps custom content in exact cache custody.
                if (refreshExistingSources) applyCandidate()
                else withContext(CacheOnlyCatalogCustomSources) { applyCandidate() }
            } catch (cancelled: CancellationException) {
                if (hasCommittedCandidate()) {
                    adoptCommitted()
                    if (protectionState().requiresFilterReload()) runtimeReload.onDispatchFailed()
                    throw com.lavasec.app.data.CatalogInstallCommittedCancellation(cancelled)
                }
                restoreUncommittedRepository()
                throw cancelled
            } catch (_: Exception) {
                if (hasCommittedCandidate()) {
                    adoptCommitted()
                    runtimeReload.onDispatchFailed()
                    return true
                }
                restoreUncommittedRepository()
                return false
            }
            if (result !is FilterMutationResult.Applied && hasCommittedCandidate()) {
                adoptCommitted()
                runtimeReload.onDispatchFailed()
                return true
            }
            if (result !is FilterMutationResult.Applied) {
                restoreUncommittedRepository()
                if (result is FilterMutationResult.SourceUnavailable) {
                    throw CustomBlocklistSourceAcquisitionException(result.sourceId, result.failure, result.sourceName)
                }
                return false
            }
            adoptCommitted()
            return true
        } finally {
            // The candidate coordinator finishes its publication result before the active
            // coordinator changes; no foreground writer can enter the adoption gap.
            if (publicationHeld) installMutex.unlock()
        }
    }

    private fun mutationCoordinator(
        initial: ConfirmedFilterState,
        effectiveCatalog: BundledBlocklistCatalog = catalog,
        publicationBoundary: (FilterMutationPublisher) -> FilterMutationPublisher = { it },
        onDurableCommit: (ConfirmedFilterState) -> Unit = {},
    ): TransactionalFilterMutationCoordinator {
        val catalogAuthority = FilterCatalogCompileAuthority(
            revision = effectiveCatalog.revision,
            sha256 = effectiveCatalog.sha256,
            sources = effectiveCatalog.sources.map { source ->
                CatalogCompileSourceIdentity(source.id, source.blocklistFormat)
            },
            maxRules = com.lavasec.app.foundation.filter.FilterCompiler.MAX_SOURCE_RULES,
        )
        val acceptedAuthority = AcceptedFilterSourceAuthority { selected, refresh ->
            val descriptors = selected.map { selectedSource ->
                if (selectedSource.isCustom) {
                    // A custom blocklist has no catalog identity; its URL + format are the cache
                    // key, so it is fetched through the same validated cache as a curated source.
                    SeededSourceDescriptor(
                        id = selectedSource.id,
                        sourceUrl = requireNotNull(selectedSource.sourceUrl),
                        format = selectedSource.format,
                    )
                } else {
                    val metadata = requireNotNull(effectiveCatalog.sources.singleOrNull { source ->
                        source.id == selectedSource.id &&
                            source.blocklistFormat == selectedSource.format
                    })
                    metadata.descriptor(effectiveCatalog)
                }
            }
            val accepted = if (kotlinx.coroutines.currentCoroutineContext()[CacheOnlyCatalogCustomSources.Key] != null)
                sourceAcquirer.acquireCatalogRefresh(descriptors) else sourceAcquirer.acquire(descriptors, refresh)
            accepted.map(AcceptedSeededSource::asFilterPayload).also {
                    kotlinx.coroutines.currentCoroutineContext()[com.lavasec.app.product.FilterPreparationProgress]
                        ?.advance(com.lavasec.app.product.FilterPreparationProgress.Phase.COMPILING)
                }
        }
        return TransactionalFilterMutationCoordinator(
            initialState = initial,
            catalogCompileAuthority = catalogAuthority,
            acceptedSourceAuthority = acceptedAuthority,
            preparer = FilterMutationPreparer { plan -> prepare(plan) },
            publisher = publicationBoundary(compositePublisher(onDurableCommit)),
            currentRuleBudget = { error("native mutations require an admission witness") },
            allowQaProbeSets = allowQaProbeSets,
        )
    }

    private fun warmKey(plan: com.lavasec.app.platform.AuthorizedFilterCompilePlan) =
        "${plan.expectedCompileInputsSha256}:${plan.expectedMaxRules}"

    fun catalogForQueries(): BundledBlocklistCatalog = catalog

    private val localRuleCounts by lazy { com.lavasec.app.data.LocalBlocklistRuleCounts(sourceCache) }

    suspend fun catalogEntriesForPresentation(confirmed: ConfirmedFilterState): List<com.lavasec.app.presentation.filters.FilterSourceCatalogEntry>? = withContext(Dispatchers.IO) {
        val heldCatalog = catalog
        val selected = confirmed.configuration.profiles.flatMap { it.enabledSourceIds }.toSet()
        val entries = heldCatalog.sources.map { source ->
            val count = source.entryCount?.takeIf { it >= 0 }
                ?: if (source.id in selected) localRuleCounts.count(source.descriptor(heldCatalog)) else null
            com.lavasec.app.presentation.filters.FilterSourceCatalogEntry(source.id, source.name, source.category, source.sourceUrl, count)
        }
        // The owner may install a new catalog while parsing; never overwrite that new projection.
        entries.takeIf { catalog === heldCatalog && confirmedState.value === confirmed }
    }

    fun closePreparedCache() = preparedCache.close()

    /** At most two small inactive filters, one compiler and no network or configuration writes.
     * Missing/unknown source sizes are skipped before compilation, never treated as zero. */
    suspend fun topUpPrepared(expiresAt: Long): Int {
        data class Snapshot(
            val coordinator: TransactionalFilterMutationCoordinator,
            val state: ConfirmedFilterState,
            val catalog: BundledBlocklistCatalog,
            val admission: FeatureLimitAdmission,
        )
        if (!installMutex.tryLock()) return 0
        val snapshot = try {
            val coordinator = activeCoordinator ?: return 0
            Snapshot(coordinator, coordinator.confirmedState.value, catalog, captureAdmission() ?: return 0)
        } finally { installMutex.unlock() }
        val (coordinator, base, heldCatalog, admission) = snapshot
        return kotlinx.coroutines.withTimeoutOrNull(10_000L) {
            var warmed = 0
            var ruleBudget = 50_000L
            for (profile in base.configuration.profiles) {
                if (warmed >= 2 || System.currentTimeMillis() >= expiresAt) break
                if (profile.id == base.configuration.activeProfileId || profile.customBlocklists.isNotEmpty()) continue
                val selected = heldCatalog.sources.filter { it.id in profile.enabledSourceIds }
                if (selected.size != profile.enabledSourceIds.size || selected.any { it.entryCount == null || it.entryCount < 0 }) continue
                val estimate = selected.sumOf { it.entryCount!!.toLong() } +
                    profile.manualRules.allowedDomains.size + profile.manualRules.blockedDomains.size
                if (estimate > ruleBudget || estimate > admission.limits.maxFilterRules) continue
                val descriptors = selected.map { it.descriptor(heldCatalog) }
                val cached = descriptors.map(sourceCache::exactOrNull)
                if (cached.any { it == null }) continue
                val desired = base.configuration.withActiveProfile(profile.id)
                val plan = coordinator.warmPlan(FilterMutationRequest(base.configurationIdentity.generation, desired, admission = admission),
                    AcceptedFilterSourceAuthority { sources, refresh ->
                        check(!refresh && sources.map { it.id } == descriptors.map { it.id })
                        cached.map { it!!.asFilterPayload() }
                    }) ?: continue
                val key = warmKey(plan)
                if (preparedCache.contains(key)) continue
                if (!compileMutex.tryLock()) break // Foreground work can now reach this mutex.
                val artifact = try {
                    // Charge input work, including failures and duplicates, against the pass.
                    val compileLimit = minOf(plan.expectedMaxRules, ruleBudget.toInt())
                    ruleBudget -= estimate
                    compiler.compile(plan.compileRequest.copy(maxRules = compileLimit))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    continue
                } finally { compileMutex.unlock() }
                var transferred = false
                try {
                    // Never wait for installation admission while holding the compiler: a
                    // foreground mutation acquires those locks in the opposite order.
                    if (!installMutex.tryLock()) break
                    try {
                        if (activeCoordinator !== coordinator || coordinator.confirmedState.value !== base || catalog !== heldCatalog) break
                        val accepted = preparedCache.put(key, artifact, expiresAt)
                        transferred = true // put owns it even when it rejects/closes the entry.
                        if (accepted) warmed++
                    } finally { installMutex.unlock() }
                } finally {
                    if (!transferred) artifact.close()
                }
            }
            warmed
        } ?: 0
    }

    private suspend fun prepare(
        plan: com.lavasec.app.platform.AuthorizedFilterCompilePlan,
    ): FilterMutationPreparationResult {
        val progress = kotlinx.coroutines.currentCoroutineContext()[com.lavasec.app.product.FilterPreparationProgress]
        progress?.advance(com.lavasec.app.product.FilterPreparationProgress.Phase.COMPILING)
        val prepared = try {
            compileMutex.withLock { preparedCache.take(warmKey(plan)) ?: compiler.compile(plan.compileRequest) { phase ->
                if (phase == com.lavasec.app.foundation.filter.FilterCompilePhase.ARTIFACT_WRITE)
                    progress?.advance(com.lavasec.app.product.FilterPreparationProgress.Phase.SAVING)
            } }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: CustomBlocklistSourceAcquisitionException) {
            throw failure
        } catch (_: Exception) {
            return FilterMutationPreparationResult.Failed(FilterMutationFailure.COMPILE_FAILED)
        }
        return when (
            val verified = CredentialFilterArtifactStagingVerifier.verify(
                prepared.file,
                prepared.compilationReceipt,
            )
        ) {
            is CredentialFilterArtifactStagingResult.Unavailable -> {
                prepared.close()
                FilterMutationPreparationResult.Failed(FilterMutationFailure.REOPEN_FAILED)
            }
            is CredentialFilterArtifactStagingResult.Verified -> {
                progress?.advance(com.lavasec.app.product.FilterPreparationProgress.Phase.VERIFIED)
                val desired = plan.mutationRequest.desired
                val candidate = ConfirmedFilterState(
                    configuration = desired,
                    configurationIdentity = com.lavasec.app.platform.ConfigurationIdentity(
                        plan.expectedConfiguration.generation,
                        plan.expectedConfiguration.fingerprint,
                    ),
                    credentialUnlockRequired = com.lavasec.app.platform.filterRequiresCredentialUnlock(
                        desired, verified.verifiedArtifact.artifactIdentity.ruleCounts.total,
                    ),
                    verifiedArtifact = verified.verifiedArtifact,
                )
                FilterMutationPreparationResult.Prepared(
                    PreparedFilterMutation(
                        candidate = candidate,
                        compilationReceipt = prepared.compilationReceipt,
                        publicationArtifact = prepared,
                        admission = plan.mutationRequest.admission,
                        publicationAdmission = plan.mutationRequest.publicationAdmission,
                        publicationGuard = plan.mutationRequest.publicationGuard,
                    ) {
                        verified.close()
                        prepared.close()
                    },
                )
            }
        }
    }

    private fun compositePublisher(onDurableCommit: (ConfirmedFilterState) -> Unit): FilterMutationPublisher {
        val durablePublisher = durablePublisher(onDurableCommit)
        return FilterMutationPublisher { expectedGeneration, mutation ->
            suspend fun publishGuarded(): FilterMutationPublishResult = mutation.publicationGuard?.publish {
                durablePublisher.commit(expectedGeneration, mutation)
            } ?: durablePublisher.commit(expectedGeneration, mutation)
            val admission = mutation.admission
                ?: return@FilterMutationPublisher FilterMutationPublishResult.Failed(FilterMutationFailure.IDENTITY_MISMATCH)
            if (plusOwner == null) {
                if (admission !== freeAdmission) FilterMutationPublishResult.Failed(FilterMutationFailure.COMMIT_FAILED)
                else publishGuarded()
            } else {
                plusOwner.withAdmission(admission) { publishGuarded() }
                    ?: FilterMutationPublishResult.Failed(FilterMutationFailure.COMMIT_FAILED)
            }
        }
    }

    private fun durablePublisher(onDurableCommit: (ConfirmedFilterState) -> Unit): FilterMutationPublisher =
        FilterMutationPublisher { expectedGeneration, mutation ->
            val candidate = mutation.candidate
            if (!candidate.credentialUnlockRequired) {
                val prepared = mutation.publicationArtifact
                    ?: return@FilterMutationPublisher FilterMutationPublishResult.Failed(
                        FilterMutationFailure.IDENTITY_MISMATCH,
                    )
                if (devicePublisher.stage(prepared) == null) {
                    return@FilterMutationPublisher FilterMutationPublishResult.Failed(
                        FilterMutationFailure.PUBLISH_FAILED,
                    )
                }
            }
            when (val stored = filterStore.commit(expectedGeneration, mutation)) {
                FilterMutationPublishResult.Committed -> withContext(NonCancellable) {
                    onDurableCommit(candidate)
                    // The filter record is already durable. Complete its pointer adoption
                    // before cancellation can reach the caller/coordinator.
                    val adopted = try {
                        // One coordinator serves domain reviews, whole-profile switches,
                        // and resolver switches, so the shared publisher routes the durable
                        // credential adoption by comparing the candidate's plan to the plan
                        // still persisted at commit time: a plan change goes to the resolver
                        // adoption (`commitResolverConfiguration`), everything else keeps the
                        // filter adoption whose validation pins the resolver authority
                        // unchanged (`commitFilterConfiguration`). The repository's
                        // expected-generation CAS re-fences both paths after this read.
                        val persistedPlan = configurationRepository.state.first()
                            .configuration?.resolver?.let(FilterResolverPlan::from)
                        if (persistedPlan != null && persistedPlan != candidate.configuration.resolverTarget) {
                            configurationRepository.commitResolverConfiguration(
                                expectedGeneration = expectedGeneration,
                                plan = candidate.configuration.resolverPlan,
                                configuration = candidate.configuration,
                                identity = candidate.configurationIdentity,
                                credentialUnlockRequired = candidate.credentialUnlockRequired,
                                customDohUrl = candidate.configuration.customDohUrl,
                                customDotEndpoint = candidate.configuration.customDotEndpoint,
                                customDoqEndpoint = candidate.configuration.customDoqEndpoint,
                                customName = candidate.configuration.customName,
                                customSecondaryEndpoint = candidate.configuration.customSecondaryEndpoint,
                                savedTiers = candidate.configuration.savedTiers,
                            )
                        } else {
                            configurationRepository.commitFilterConfiguration(
                                expectedGeneration = expectedGeneration,
                                configuration = candidate.configuration,
                                identity = candidate.configurationIdentity,
                                credentialUnlockRequired = candidate.credentialUnlockRequired,
                            )
                        }
                    } catch (_: Exception) {
                        null
                    }
                    // After the durable commit even an adoption/readback exception must
                    // reach the coordinator as ReloadRequired, never a pre-commit rollback.
                    val exact = try {
                        configurationRepository.state.first().configurationIdentity ==
                            candidate.configurationIdentity
                    } catch (_: Exception) {
                        false
                    }
                    if (
                        exact &&
                        (adopted == null ||
                            adopted is ProtectionIntentWriteResult.AuthoritativeCommitted)
                    ) {
                        FilterMutationPublishResult.Committed
                    } else {
                        FilterMutationPublishResult.ProtectionUnavailable
                    }
                }
                else -> stored
            }
        }

    private companion object {
        const val MAX_FILTER_RULES = 500_000
        const val SOURCE_CACHE_DIRECTORY = "seeded-source-cache"
        const val COMPILER_DIRECTORY = "filter-compiler"
    }
}

/**
 * Exactly one tunnel reload after a committed filter mutation while the runtime is live.
 * Mirrors iOS `switchToFilter` capturing whether protection should be re-established before
 * the reload (AppViewModel.swift); a non-committed mutation and every terminal protection
 * state never reload. The result is returned unchanged so callers keep one authoritative
 * outcome through the reload.
 */
internal suspend fun <T> reloadCommittedFilterIfRunning(
    result: T,
    applied: Boolean,
    protection: ProtectionState,
    reload: suspend () -> Unit,
): T {
    if (applied && protection.requiresFilterReload()) {
        reload()
    }
    return result
}

/**
 * Switch-surface reload of a durable Applied commit: the committed outcome is authoritative,
 * so a reload-dispatch failure (e.g. Android rejecting the service-start command after the
 * user backgrounded the app during a long compile) is swallowed and the Applied result is
 * still reported — the durable commit succeeded and only the runtime attachment lags. The
 * failure instead arms the runtime-reload reconciliation via [onDispatchFailed], so a later
 * protection-owner path retries the attachment instead of the UI claiming a runtime state
 * it does not have; a successful dispatch discharges any pending requirement. Cancellation
 * still propagates. The domain-review surface keeps [reloadCommittedFilterIfRunning]'s
 * strict semantics.
 */
internal suspend fun <T> reloadCommittedFilterBestEffort(
    result: T,
    applied: Boolean,
    protection: ProtectionState,
    reload: suspend () -> Unit,
    onDispatchSucceeded: () -> Unit = {},
    onDispatchFailed: () -> Unit = {},
): T = reloadCommittedFilterIfRunning(
    result = result,
    applied = applied,
    protection = protection,
    reload = {
        try {
            reload()
            onDispatchSucceeded()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            onDispatchFailed()
        }
    },
)

/**
 * Deferred runtime-attachment latch for committed filter generations.
 *
 * A post-commit reload dispatch can fail while protection is live (Android background
 * execution limits reject the service-start command), leaving the running VPN attached to
 * the previous artifact even though the durable commit — and the switch result — are
 * authoritative. This latch records that mismatch without lying to the UI: the switch still
 * reports Applied, and the armed requirement makes a later owner/runtime path retry the
 * reload dispatch. A successful dispatch, or a terminal protection state (no runtime
 * attachment exists; the next start attaches to the current durable filter), discharges it.
 */
internal class RuntimeReloadReconciliation(
    private val protection: () -> ProtectionState,
    private val reload: suspend () -> Unit,
) {
    private val mutableRequired = MutableStateFlow(false)

    val required: StateFlow<Boolean> = mutableRequired.asStateFlow()

    fun onDispatchSucceeded() {
        mutableRequired.value = false
    }

    fun onDispatchFailed() {
        mutableRequired.value = true
    }

    /**
     * Attempts one reconciliation. True means the requirement is discharged: it was not armed,
     * no runtime attachment can exist, or the reload dispatch succeeded. False means the
     * dispatch failed again and the requirement stays armed for the next runtime path.
     * Cancellation propagates without discharging.
     */
    suspend fun reconcileOnce(): Boolean {
        if (!mutableRequired.value) return true
        if (!protection().requiresFilterReload()) {
            mutableRequired.value = false
            return true
        }
        return try {
            reload()
            mutableRequired.value = false
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }
}

/** Both mutation surfaces of one credential generation, sharing the single coordinator. */
internal data class ProductionFilterMutationHosts(
    val domainActions: NativeDomainActionHost,
    val profileSwitch: FilterProfileSwitchPort,
    val resolverSwitch: ResolverPlanSwitchPort,
    val library: FilterLibraryOwner,
)

/**
 * Pure up-front gate for a whole-profile switch: unknown targets, a no-op, and frozen
 * (over-cap, read-only) targets are refused as honest user-facing rejections. Applying a
 * saved filter loads that filter — its own blocklists and manual rules travel with it — so
 * a non-active filter carrying manual rules is legal now and no longer blocks the switch
 * (iOS `switchToFilter` semantics). The coordinator's generation CAS still re-verifies
 * everything at publication for anything that raced past this gate.
 */
internal fun FilterLibraryDraft.profileSwitchRejection(
    targetProfileId: String,
    limits: FeatureLimits = FeatureLimits.FREE,
): FilterProfileSwitchRejection? {
    if (profile(targetProfileId) == null) return FilterProfileSwitchRejection.UNKNOWN_TARGET
    if (targetProfileId == activeProfileId) return FilterProfileSwitchRejection.NO_CHANGE
    if (FilterLibraryPolicy.isFrozen(this, targetProfileId, limits.maxFilters)) {
        return FilterProfileSwitchRejection.FROZEN_TARGET
    }
    return null
}

private fun ProtectionState.requiresFilterReload(): Boolean = when (this) {
    is ProtectionState.Starting,
    is ProtectionState.AwaitingNetwork,
    is ProtectionState.Reconnecting,
    is ProtectionState.Protected,
    is ProtectionState.Paused,
    -> true

    ProtectionState.Off,
    ProtectionState.SetupRequired,
    ProtectionState.Stopping,
    is ProtectionState.Failed,
    -> false
}

private fun FilterMutationResult.toProfileSwitchResult(): FilterProfileSwitchResult = when (this) {
    is FilterMutationResult.Applied -> FilterProfileSwitchResult.Applied(
        activeProfileId = confirmedState.configuration.activeProfileId,
        generation = confirmedState.configurationIdentity.generation,
    )
    is FilterMutationResult.Superseded -> FilterProfileSwitchResult.Superseded(
        confirmedState.configuration.activeProfileId,
    )
    is FilterMutationResult.Rejected -> FilterProfileSwitchResult.Rejected(
        activeProfileId = confirmedState.configuration.activeProfileId,
        reason = when (reason) {
            com.lavasec.app.platform.FilterMutationRejection.INVALID_DRAFT ->
                FilterProfileSwitchRejection.INVALID_DRAFT
            com.lavasec.app.platform.FilterMutationRejection.DEVICE_LOCKED ->
                FilterProfileSwitchRejection.DEVICE_LOCKED
            com.lavasec.app.platform.FilterMutationRejection.NO_CHANGE ->
                FilterProfileSwitchRejection.NO_CHANGE
        },
    )
    is FilterMutationResult.FailedKeepingPrevious -> FilterProfileSwitchResult.Failed(
        activeProfileId = confirmedState.configuration.activeProfileId,
        failure = failure,
    )
    is FilterMutationResult.SourceUnavailable -> FilterProfileSwitchResult.Failed(
        activeProfileId = confirmedState.configuration.activeProfileId,
        failure = FilterMutationFailure.STAGING_FAILED,
    )
    is FilterMutationResult.ProtectionUnavailable -> FilterProfileSwitchResult.ProtectionUnavailable(
        confirmedState.configuration.activeProfileId,
    )
}

internal fun FilterMutationResult.toLibraryResult(): FilterLibraryMutationResult = when (this) {
    is FilterMutationResult.Applied -> FilterLibraryMutationResult.Applied(
        confirmedState.configurationIdentity.generation,
    )
    is FilterMutationResult.Superseded -> FilterLibraryMutationResult.Superseded
    is FilterMutationResult.Rejected -> when (reason) {
        // The no-change refusal only escapes the owner's idempotence short-circuit when a
        // concurrent mutation produced the same library first; the desired state is
        // authoritative either way.
        com.lavasec.app.platform.FilterMutationRejection.NO_CHANGE ->
            FilterLibraryMutationResult.Applied(confirmedState.configurationIdentity.generation)
        else -> FilterLibraryMutationResult.Failed(
            FilterMutationFailure.IDENTITY_MISMATCH,
        )
    }
    is FilterMutationResult.FailedKeepingPrevious ->
        FilterLibraryMutationResult.Failed(failure)
    is FilterMutationResult.SourceUnavailable ->
        FilterLibraryMutationResult.FailedSource(sourceId, sourceName, failure)
    is FilterMutationResult.ProtectionUnavailable ->
        FilterLibraryMutationResult.ProtectionUnavailable
}

private fun FilterMutationResult.toResolverPlanSwitchResult(
    plan: MilestoneResolverPlan,
): ResolverPlanSwitchResult = when (this) {
    is FilterMutationResult.Applied -> ResolverPlanSwitchResult.Applied(
        plan = plan,
        generation = confirmedState.configurationIdentity.generation,
    )
    is FilterMutationResult.Superseded -> ResolverPlanSwitchResult.Superseded(plan)
    is FilterMutationResult.Rejected -> ResolverPlanSwitchResult.Rejected(
        plan,
        when (reason) {
            com.lavasec.app.platform.FilterMutationRejection.INVALID_DRAFT ->
                ResolverPlanSwitchRejection.INVALID_DRAFT
            com.lavasec.app.platform.FilterMutationRejection.DEVICE_LOCKED ->
                ResolverPlanSwitchRejection.DEVICE_LOCKED
            com.lavasec.app.platform.FilterMutationRejection.NO_CHANGE ->
                ResolverPlanSwitchRejection.NO_CHANGE
        },
    )
    is FilterMutationResult.FailedKeepingPrevious -> ResolverPlanSwitchResult.Failed(plan, failure)
    is FilterMutationResult.SourceUnavailable ->
        ResolverPlanSwitchResult.Failed(plan, FilterMutationFailure.STAGING_FAILED)
    is FilterMutationResult.ProtectionUnavailable -> ResolverPlanSwitchResult.ProtectionUnavailable(
        plan,
    )
}

private fun FilterMutationResult.toDomainApplyResult(): DomainActionApplyResult = when (this) {
    is FilterMutationResult.Applied -> DomainActionApplyResult.Applied(
        confirmedState.configurationIdentity.generation,
    )
    is FilterMutationResult.Superseded -> DomainActionApplyResult.Superseded
    is FilterMutationResult.Rejected -> DomainActionApplyResult.Rejected(
        when (reason) {
            com.lavasec.app.platform.FilterMutationRejection.INVALID_DRAFT ->
                "The filter change is invalid."
            com.lavasec.app.platform.FilterMutationRejection.DEVICE_LOCKED ->
                "Unlock the device to change this filter."
            com.lavasec.app.platform.FilterMutationRejection.NO_CHANGE ->
                "The filter already contains this change."
        },
    )
    is FilterMutationResult.FailedKeepingPrevious ->
        DomainActionApplyResult.Failed("The filter change could not be committed.")
    is FilterMutationResult.SourceUnavailable ->
        DomainActionApplyResult.Failed("The filter change could not be committed.")
    is FilterMutationResult.ProtectionUnavailable ->
        DomainActionApplyResult.Failed("Filter authority requires reconstruction.")
}

@Suppress("DEPRECATION")
private fun selectOrdinaryNetwork(context: Context): Network {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val activeDefault = manager.activeNetwork
    // Include the captured default even if a capability update races allNetworks enumeration.
    val candidates = (listOfNotNull(activeDefault) + manager.allNetworks.toList()).distinct()
        .mapNotNull { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
            OrdinarySourceNetworkCandidate(
                network = network,
                notVpn = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
                internet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            )
        }
    return selectOrdinarySourceNetwork(candidates, activeDefault)
        ?: error("No ordinary Android network is available for pinned source acquisition")
}

/** One read-only capability snapshot for the source transport's existing ordinary-network gate. */
internal data class OrdinarySourceNetworkCandidate<T>(
    val network: T,
    val notVpn: Boolean,
    val internet: Boolean,
    val validated: Boolean,
)

/** Use Android's selected route when eligible; prefer validated ordinary routes for fallback. */
internal fun <T> selectOrdinarySourceNetwork(
    candidates: List<OrdinarySourceNetworkCandidate<T>>,
    activeDefault: T?,
): T? {
    val eligible = candidates.filter { it.notVpn && it.internet }
    return (eligible.firstOrNull { it.network == activeDefault }
        ?: eligible.firstOrNull { it.validated }
        ?: eligible.firstOrNull())?.network
}

/** Catalog maintenance may refresh curated inputs; it never silently fetches custom lists. */
private object CacheOnlyCatalogCustomSources : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    object Key : kotlin.coroutines.CoroutineContext.Key<CacheOnlyCatalogCustomSources>
}

/** Manual quotas govern newly activated content; saved active rules may be retained or reduced
 * after a lapse. Both shelves must be subsets: a mixed removal/addition is still a new edit.
 * Inactive profiles retain their complete data. */
internal fun activeManualRuleRejection(
    current: FilterLibraryDraft,
    desired: FilterLibraryDraft,
    limits: FeatureLimits,
): com.lavasec.app.foundation.filter.FilterLibraryRejection? {
    val active = desired.profile(desired.activeProfileId)
        ?: return com.lavasec.app.foundation.filter.FilterLibraryRejection.UNKNOWN_FILTER
    val saved = current.profile(current.activeProfileId)
    if (active.id == saved?.id && active.manualRules.isSubsetOf(saved.manualRules)) return null
    return when {
        active.manualRules.allowedDomains.size > limits.maxAllowedDomains ->
            com.lavasec.app.foundation.filter.FilterLibraryRejection.ALLOWED_DOMAIN_LIMIT
        active.manualRules.blockedDomains.size > limits.maxBlockedDomains ->
            com.lavasec.app.foundation.filter.FilterLibraryRejection.BLOCKED_DOMAIN_LIMIT
        else -> null
    }
}
