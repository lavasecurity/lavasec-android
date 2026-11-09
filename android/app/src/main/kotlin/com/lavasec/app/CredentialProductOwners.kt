package com.lavasec.app

import com.lavasec.app.foundation.subscription.FeatureLimits
import kotlinx.coroutines.flow.combine

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.lavasec.app.data.ActivityRepository
import com.lavasec.app.data.ActivityRevisionBus
import com.lavasec.app.data.CredentialActivityWriter
import com.lavasec.app.data.CredentialConfigurationRepository
import com.lavasec.app.data.InitialFilterProvisionResult
import com.lavasec.app.data.SeededSourceCacheFailure
import com.lavasec.app.customization.CustomizationOwner
import com.lavasec.app.customization.CustomizationPreferenceStore
import com.lavasec.app.diagnostics.CredentialOperationalDiagnosticsStore
import com.lavasec.app.diagnostics.DiagnosticsFeedbackOwner
import com.lavasec.app.foundation.resolver.FilterResolverPlan
import com.lavasec.app.onboarding.OnboardingOwner
import com.lavasec.app.platform.ConfirmedFilterState
import com.lavasec.app.platform.ProtectionIntentWriteResult
import com.lavasec.app.product.DomainActionOwner
import com.lavasec.app.product.FilterLibraryOwner
import com.lavasec.app.product.FilterProfileSwitchOwner
import com.lavasec.app.product.ResolverPlanSwitchOwner
import com.lavasec.app.security.AndroidKeystorePasscodeVerifier
import com.lavasec.app.security.SecurityOwner
import com.lavasec.app.security.SecurityPreferenceStore
import com.lavasec.app.product.activity.ActivityDateLens
import com.lavasec.app.product.activity.ActivityOwner
import com.lavasec.app.product.activity.AndroidDefaultNetworkMonitor
import com.lavasec.app.product.activity.AndroidProductActivityPort
import com.lavasec.app.product.activity.CredentialNetworkActivityRecorder
import com.lavasec.app.product.activity.CredentialNetworkActivityRuntimeStore
import com.lavasec.app.product.privacy.AndroidLocalExportArtifactStore
import com.lavasec.app.product.privacy.AndroidLocalExportShareIntentFactory
import com.lavasec.app.product.privacy.CredentialActivityPrivacyController
import com.lavasec.app.product.privacy.CredentialPrivacyFence
import com.lavasec.app.product.privacy.LocalActivityExportService
import com.lavasec.app.product.privacy.LocalFeedbackComposer
import com.lavasec.app.product.privacy.LocalExportHandle
import com.lavasec.app.product.privacy.PrivacyAuthorization
import com.lavasec.app.product.privacy.PrivacyAuthorizer
import com.lavasec.app.product.privacy.PrivacyOwner
import com.lavasec.app.presentation.filters.FilterLibraryMapper
import com.lavasec.app.presentation.filters.FilterLibraryState
import com.lavasec.app.presentation.filters.FilterSourceCatalogEntry
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.protection.ProtectionIntentCommit
import com.lavasec.app.protection.ProtectionOwner
import com.lavasec.app.vpn.LavaVpnRuntimeBridge
import com.lavasec.app.vpn.LavaVpnServiceClient
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal interface CredentialProductOwnerFactory : AutoCloseable {
    fun create(scope: CoroutineScope): CredentialProductOwners
}

/** One ViewModel-scoped owner set bound to exactly one credential graph generation. */
internal class CredentialProductOwners(
    val graphGeneration: Long,
    val onboarding: OnboardingOwner,
    val protection: ProtectionOwner?,
    val domainActions: DomainActionOwner?,
    val activity: ActivityOwner?,
    val privacy: PrivacyOwner?,
    val diagnostics: DiagnosticsFeedbackOwner?,
    val reports: com.lavasec.app.diagnostics.NativeBugReportOwner? = null,
    /** A coherent retained filter may keep protection usable while catalog edits are withheld. */
    val filterMutationsAvailable: Boolean = true,
    /**
     * Lifecycle-fenced customization owner of this exact graph generation. The
     * preferences are app-level (not credential-scoped), but every write still
     * passes through this generation's admission fence so a retired generation
     * can never mutate a preference out from under the live one.
     */
    val customization: CustomizationOwner? = null,
    /**
     * Lifecycle-fenced Keystore-backed app-unlock owner of this exact graph
     * generation. RN and native handles share one process-owned credential state;
     * retiring a handle revokes its commands without closing another live screen's owner.
     */
    val security: SecurityOwner? = null,
    /**
     * Lifecycle-fenced whole-profile switch capability of this exact graph generation, or
     * null when the product cannot supply mutations (pre-onboarding or an unavailable
     * confirmed filter). The Filters screen drops its Apply controls in that case.
     */
    val filterProfileSwitch: FilterProfileSwitchOwner? = null,
    /**
     * Lifecycle-fenced saved-filter-library owner of this exact graph generation: per-filter
     * drafts plus create/rename/delete/restore/defaults and the review/commit path, all
     * through the shared generation-fenced coordinator. Null when the product cannot supply
     * mutations; the Filters surfaces then drop every edit control.
     */
    val filterLibrary: FilterLibraryOwner? = null,
    /**
     * The ambient protection notification owner of this exact graph generation
     * (null before the ambient surface was constructed). The VM reaches it only
     * to retry a dropped post after notification settings change; it never
     * publishes product state itself.
     */
    val protectionAmbient: com.lavasec.app.notification.ProtectionAmbientNotificationOwner? = null,
    /**
     * Lifecycle-fenced resolver-plan switch capability of this exact graph generation, or
     * null when the product cannot supply mutations (pre-onboarding or an unavailable
     * confirmed filter). The DNS Resolver settings surface drops its rows' actions in that
     * case instead of rendering inert selection.
     */
    val resolverPlanSwitch: ResolverPlanSwitchOwner? = null,
    /**
     * Current authoritative resolver plan derived from this generation's live confirmed
     * filter authority, or null when the product cannot supply it. The settings surface
     * treats null as its honest unavailable state. A committed switch updates this flow
     * without a graph rotation because the coordinator adopts the new draft in place.
     */
    val resolverPlan: StateFlow<FilterResolverPlan?> = MutableStateFlow(null),
    private val onClose: (CredentialProductOwners) -> Unit,
    private val generationScope: CoroutineScope,
    val createExportShareIntent: ((LocalExportHandle) -> Intent)? = null,
    /**
     * Read-only Filters library facts derived from this generation's live confirmed filter
     * authority, or null when the product cannot supply them (pre-onboarding or an
     * unavailable confirmed filter). The screen treats null as its honest unavailable state.
     */
    val filterLibraryState: StateFlow<FilterLibraryState?> = MutableStateFlow(null),
    /**
     * The authoritative filter library draft of this generation, or null when unavailable.
     * The ViewModel derives the detail/review surfaces from this flow combined with the
     * per-filter drafts, so a committed mutation renders without a graph rotation.
     */
    val filterDraft: StateFlow<FilterLibraryDraft?> = MutableStateFlow(null),
    /**
     * The exact persisted product configuration and logging store of this generation. Read only
     * to compose the configuration review witness; the filter library stays the authoritative
     * filter record.
     */
    val configurationState: StateFlow<com.lavasec.app.platform.PersistedAppState> =
        MutableStateFlow(com.lavasec.app.platform.PersistedAppState.initial()),
    val featureLimits: StateFlow<FeatureLimits> = MutableStateFlow(FeatureLimits.FREE),
    /**
     * The compiled block-rule count of the active filter (block exact + suffix), or null.
     * Non-active filters have no honest count on Android; the surfaces say so.
     */
    val filterActiveRuleCount: StateFlow<Int?> = MutableStateFlow(null),
    /** Exact compiled total, including allow rules; only this count can explain a tier refusal. */
    val filterTotalRuleCount: StateFlow<Int?> = MutableStateFlow(null),
    /**
     * Stable bundled-catalog naming facts carried from graph creation. Third-party source
     * names never change within a process.
     */
    val filterCatalog: StateFlow<List<FilterSourceCatalogEntry>?> = MutableStateFlow(null),
    /**
     * Lifecycle-fenced Network Activity producer of this exact graph generation, or null
     * when the product cannot supply it (pre-onboarding or an unavailable confirmed
     * filter). The owner exists for recording + close semantics; the VM never commands it.
     */
    private val networkActivityRecorder: AutoCloseable? = null,
    /**
     * Installs a validated remote catalog into this generation's live filter authority through
     * the shared generation-fenced coordinator, or null when the product cannot supply one
     * (pre-onboarding). Exposed so the RN bridge's `filter.refresh` is an adapter, never a
     * parallel write path.
     */
    val catalogInstaller: com.lavasec.app.data.CatalogInstaller? = null,
    val catalogReconstructionRequired: StateFlow<Boolean> = MutableStateFlow(false),
    val preparedTopUp: (suspend (Long) -> Int)? = null,
    val catalogForQueries: (() -> com.lavasec.app.data.BundledBlocklistCatalog?)? = null,
    val domainChecker: (suspend (String) -> com.lavasec.app.product.LocalDomainCheckEvidence?)? = null,
    val domainCheckRevision: (() -> String?)? = null,
    val sampleNerdStats: (suspend () -> com.lavasec.app.diagnostics.LiveNerdStatsSample?)? = null,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        generationScope.cancel()
        onboarding.close()
        activity?.close()
        domainActions?.close()
        filterProfileSwitch?.close()
        filterLibrary?.close()
        resolverPlanSwitch?.close()
        protection?.close()
        privacy?.close()
        diagnostics?.close()
        reports?.close()
        customization?.close()
        security?.close()
        networkActivityRecorder?.let { recorder -> runCatching(recorder::close) }
        onClose(this)
    }
}

/** Exact production adapters over one unlocked credential graph. */
internal class ProductionCredentialProductOwnerFactory(
    private val context: Context,
    private val graphGeneration: Long,
    private val initialState: com.lavasec.app.platform.PersistedAppState,
    private val initialConfirmedFilter: ConfirmedFilterState?,
    private val initialFilterCatalogEntries: List<FilterSourceCatalogEntry>?,
    private val configurationRepository: CredentialConfigurationRepository,
    private val filterAuthority: ProductionFilterAuthority,
    private val appStarted: Flow<Boolean>,
    private val activityRepository: ActivityRepository,
    private val activityWriter: CredentialActivityWriter,
    private val activityRevisionBus: ActivityRevisionBus,
    private val networkActivity: CredentialNetworkActivityRuntimeStore,
    private val privacyFence: CredentialPrivacyFence,
    private val diagnosticsStore: CredentialOperationalDiagnosticsStore,
    private val dnsFallbackHistory: com.lavasec.app.diagnostics.CredentialDnsFallbackHistoryStore? = null,
    private val ambientNotificationOwner: com.lavasec.app.notification.ProtectionAmbientNotificationOwner?,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val reportService: com.lavasec.app.diagnostics.NativeBugReportService? = null,
    private val validateChainedConfigurationForStart: suspend () ->
        com.lavasec.app.foundation.chained.ChainedConfigurationIssue? = { null },
    private val chainedComposition: com.lavasec.app.platform.ChainedComposition? = null,
    private val processSecurity: SecurityOwner? = null,
    private val featureLimits: StateFlow<FeatureLimits> = MutableStateFlow(FeatureLimits.FREE),
    private val initialFilterMutationsAvailable: Boolean = true,
) : CredentialProductOwnerFactory {
    private val closed = AtomicBoolean(false)
    private val owners = CopyOnWriteArraySet<CredentialProductOwners>()
    private val securityAuthority by lazy {
        processSecurity ?: SecurityOwner(
            AndroidKeystorePasscodeVerifier(context), SecurityPreferenceStore(context),
            CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.IO), nowMillis,
        )
    }

    private suspend fun loadChainedDnsEdit(composition: com.lavasec.app.platform.ChainedComposition):
        com.lavasec.app.foundation.resolver.ChainedDnsEditState {
        val enabled = composition.preferenceStore.loadEnabled()
        val fallback = composition.preferenceStore.loadDnsFallbackEnabled()
        val stored = composition.configurationRepository.loadConfiguration()
        return com.lavasec.app.foundation.resolver.ChainedDnsEditState(
            chainingEnabled = enabled && stored?.pendingImportId == null && stored?.configuration?.activeConfiguration != null,
            fallbackPreference = fallback,
            storedIsSplitTunnel = stored?.configuration?.activeConfiguration?.let {
                it.effectiveRoutingPolicy == com.lavasec.app.foundation.chained.ChainedRoutingPolicy.SPLIT_TUNNEL
            },
        )
    }

    override fun create(scope: CoroutineScope): CredentialProductOwners {
        check(!closed.get()) { "credential product owner factory is closed" }
        // A generation-scoped coroutine scope: every flow collector bound to this
        // owner set dies with it on graph rotation instead of outliving the
        // generation on the ViewModel-wide ownerScope (Codex: retired generations
        // must not retain the authority/coordinator until the ViewModel dies).
        val generationScope = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]))
        val productReady = initialState.onboardingCompleted && initialConfirmedFilter != null
        val onboarding = OnboardingOwner(
            onboardingCompleted = productReady,
            reconstructionRequired = initialState.onboardingCompleted && !productReady,
            vpnPreparation = AndroidVpnPreparation(context),
            commit = ::commitOnboarding,
            scope = scope,
        )
        val confirmed = initialConfirmedFilter.takeIf { productReady }
        // The current resolver plan follows the same live confirmed state as the Filters
        // library: a committed resolver switch adopts the new draft inside the coordinator,
        // so this flow emits the new plan without a graph rotation. It is built before the
        // owners because the Network Activity producer reads it at switch time to derive the
        // exact user-action category (resolver change vs fallback toggle) from the prior plan.
        val resolverPlan = filterAuthority.confirmedState
            .map { confirmed -> confirmed?.configuration?.resolverTarget }
            .stateIn(generationScope, SharingStarted.Eagerly, null)
        // The bounded Network Activity producer records only events it can truthfully derive:
        // admitted protection commands through the decorated controller below, exact health
        // transitions through the process runtime bridge, default-network changes through
        // the app-side monitor, and committed resolver/filter changes through the decorated
        // mutation ports. Retention still gates at record time so disabled retention
        // never accumulates (iOS appendNetworkActivity guard parity).
        val networkActivityRecorder = confirmed?.let {
            val monitor = AndroidDefaultNetworkMonitor(context)
            CredentialNetworkActivityRecorder(
                store = networkActivity,
                protection = LavaVpnRuntimeBridge.state,
                health = LavaVpnRuntimeBridge.health,
                networkFacts = monitor.facts,
                networkSource = monitor,
                retentionEnabled = {
                    activityRepository.loggingPolicy().settings.keepNetworkActivity
                },
                onRecorded = activityWriter::publishCommittedMutation,
                privacyFence = privacyFence,
                scope = generationScope,
                nowMillis = nowMillis,
            )
        }
        if (confirmed != null && !initialFilterMutationsAvailable) {
            filterAuthority.observeConfirmed(confirmed, generationScope)
        }
        val mutationHosts = confirmed?.takeIf { initialFilterMutationsAvailable }?.let {
            filterAuthority.createMutationHosts(it, generationScope) { before, after ->
                networkActivityRecorder?.recordLibraryApplied(before, after)
            }
        }
        val domainActions = mutationHosts?.let {
            DomainActionOwner(it.domainActions, scope)
        }
        val filterProfileSwitch = mutationHosts?.let {
            FilterProfileSwitchOwner(
                port = networkActivityRecorder?.recordingProfileSwitchPort(it.profileSwitch)
                    ?: it.profileSwitch,
                scope,
            )
        }
        val filterLibrary = mutationHosts?.library
        val chainedDnsEdit = chainedComposition?.let { composition ->
            val initial = runCatching { kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                loadChainedDnsEdit(composition)
            } }.getOrDefault(com.lavasec.app.foundation.resolver.ChainedDnsEditState(true, false, null))
            combine(
                composition.preferenceStore.chainedEnabled,
                composition.preferenceStore.chainedDnsFallbackEnabled,
                composition.configurationRepository.storedConfiguration,
            ) { enabled, fallback, stored ->
                com.lavasec.app.foundation.resolver.ChainedDnsEditState(
                    chainingEnabled = enabled && stored?.pendingImportId == null && stored?.configuration?.activeConfiguration != null,
                    fallbackPreference = fallback,
                    storedIsSplitTunnel = stored?.configuration?.activeConfiguration?.let {
                        it.effectiveRoutingPolicy == com.lavasec.app.foundation.chained.ChainedRoutingPolicy.SPLIT_TUNNEL
                    },
                )
            }.stateIn(generationScope, SharingStarted.Eagerly, initial)
        } ?: MutableStateFlow(com.lavasec.app.foundation.resolver.ChainedDnsEditState.editable)
        val qaDnsAuthority = chainedComposition?.let { composition ->
            val preferences = composition.preferenceStore as? com.lavasec.app.billing.AndroidPlusPlanPersistence
            val repository = composition.configurationRepository as? com.lavasec.app.data.CredentialChainedUpstreamConfigurationRepository
            if (preferences != null && repository != null) com.lavasec.app.product.ChainedQaDnsPublicationAuthority(preferences, repository) else null
        }
        val resolverPlanSwitch = mutationHosts?.let {
            ResolverPlanSwitchOwner(
                port = networkActivityRecorder?.recordingResolverSwitchPort(
                    it.resolverSwitch,
                    currentPlan = { resolverPlan.value?.plan },
                ) ?: it.resolverSwitch,
                scope,
                chainedDnsEdit = chainedDnsEdit,
                loadChainedDnsEdit = chainedComposition?.let { composition ->
                    { loadChainedDnsEdit(composition) }
                },
                captureQaDnsAdmission = qaDnsAuthority?.let { native -> { native.capture() } },
            )
        }
        val protection = confirmed?.let {
            ProtectionOwner(
                controller = networkActivityRecorder?.recordingController(
                    LavaVpnServiceClient(context),
                ) ?: LavaVpnServiceClient(context),
                health = LavaVpnRuntimeBridge.health,
                commitProtectionIntent = ::commitProtectionIntent,
                scope = scope,
                // A failed post-commit reload dispatch arms the authority's requirement;
                // this owner retries the runtime attachment on later runtime transitions
                // and on each entry into the foreground.
                runtimeReloadRequired = filterAuthority.runtimeReloadRequired,
                appStarted = appStarted,
                reconcileRuntimeReload = filterAuthority::reconcileRuntimeReload,
                validateChainedConfigurationForStart = validateChainedConfigurationForStart,
            )
        }
        val activity = domainActions?.let { domain ->
            ActivityOwner(
                port = AndroidProductActivityPort(
                    activityRepository = activityRepository,
                    networkActivityStore = networkActivity,
                    signals = activityRevisionBus.current,
                ),
                initialDateLens = todayLens(),
                domainActions = domain,
                scope = scope,
            )
        }
        val privacyAuthorizer = PrivacyAuthorizer { PrivacyAuthorization.Authorized }
        val privacyController = confirmed?.let {
            CredentialActivityPrivacyController(
                repository = activityRepository,
                writer = activityWriter,
                fence = privacyFence,
                authorizer = privacyAuthorizer,
                persistConfiguration = { settings ->
                    configurationRepository.setLogging(
                        keepCounts = settings.keepCounts,
                        keepHistory = settings.keepHistory,
                        keepNetworkActivity = settings.keepNetworkActivity,
                        keepLavaGuardProgress = settings.keepLavaGuardProgress,
                    )
                },
                clearGuardProgress = {
                    check(com.lavasec.app.data.GuardianProgressStore.get(context).clearUsage()) {
                        "Guardian usage could not be cleared"
                    }
                    com.lavasec.app.sudoku.SharedPreferencesSudokuGameStore(context).clear()
                },
                persistRestoreConfiguration = configurationRepository::setLoggingForRestore,
                persistQaConfiguration = configurationRepository::compareAndSetQaLogging,
                clearQaDiagnostics = { check(diagnosticsStore.clear()) { "QA diagnostics could not be cleared" } },
                clearDnsFallbackHistory = { dnsFallbackHistory?.clear() },
                clearNetworkActivity = {
                    // The retained store is credential-protected; clearing it through the
                    // privacy boundary keeps every destructive local-log mutation on the
                    // same fence (CredentialPrivacyFence.withClear).
                    networkActivity.clear()
                },
            )
        }
        val artifactStore = confirmed?.let { AndroidLocalExportArtifactStore(context) }
        val exportService = artifactStore?.let { store ->
            LocalActivityExportService(
                repository = activityRepository,
                fence = privacyFence,
                artifactStore = store,
                networkActivityExport = networkActivity::exportSnapshot,
                dnsFallbackHistoryExport = dnsFallbackHistory?.let { it::exportSnapshot },
                authorizer = privacyAuthorizer,
                nowMillis = nowMillis,
                zoneId = zoneId,
            )
        }
        val privacy = if (privacyController != null && exportService != null) {
            PrivacyOwner(
                initialLogging = initialState.logging,
                setLogging = privacyController::setLogging,
                restoreLogging = privacyController::setLoggingForRestore,
                resetQa = privacyController::resetForQa,
                clear = privacyController::clear,
                export = exportService::export,
                clearNetworkActivity = privacyController::clearNetworkActivityEntries,
                clearGuardProgress = privacyController::clearGuardProgressEntries,
                scope = scope,
            )
        } else {
            null
        }
        val diagnostics = confirmed?.let {
            DiagnosticsFeedbackOwner(
                load = { diagnosticsStore.load(graphGeneration, nowMillis()) },
                clear = diagnosticsStore::clear,
                feedback = LocalFeedbackComposer(
                    appVersion = { BuildConfig.VERSION_NAME },
                    deviceSummary = { "Android ${Build.VERSION.RELEASE} (${Build.MODEL})" },
                    diagnostics = { diagnosticText() },
                ),
                scope = scope,
            )
        }
        // The customization and security owners are app-level preferences, not
        // credential evidence, so they exist in every generation (pre- and
        // post-onboarding). The security owner degrades to its honest
        // unavailable state when this device's Keystore cannot be opened.
        val customizationStore = CustomizationPreferenceStore(context)
        val customization = CustomizationOwner(
            initial = customizationStore.load(),
            persist = { desired -> customizationStore.persist(desired) },
            notificationChannelAllowed = { customizationStore.notificationChannelAllowed() },
            scope = scope,
        )
        val security = securityAuthority.openSession(scope)
        lateinit var created: CredentialProductOwners
        // The Filters library derives from the authority's live confirmed state so the
        // active filter and rule count stay synchronized after in-generation commits
        // (a domain review, a library edit, or a whole-filter switch commits a new artifact
        // without rotating the graph). Catalog names and selectable IDs move with the
        // installed authority so editing/import validation uses the same live catalog.
        val filterCatalog = MutableStateFlow(initialFilterCatalogEntries)
        generationScope.launch {
            filterAuthority.confirmedState.collectLatest { confirmed ->
                if (confirmed == null) return@collectLatest
                val baseline = filterCatalog.value
                val entries = filterAuthority.catalogEntriesForPresentation(confirmed)
                if (isActive && !closed.get() && entries != null) filterCatalog.compareAndSet(baseline, entries)
            }
        }
        val filterLibraryState = combine(filterAuthority.confirmedState, filterCatalog, featureLimits) { confirmed, catalogEntries, limits ->
            filterLibraryUiStateFor(confirmed, catalogEntries, limits)
        }
            .stateIn(generationScope, SharingStarted.Eagerly, null)
        val filterDraft = filterAuthority.confirmedState
            .map { confirmed -> confirmed?.configuration }
            .stateIn(generationScope, SharingStarted.Eagerly, null)
        val filterActiveRuleCount = filterAuthority.confirmedState
            .map { confirmed ->
                confirmed?.verifiedArtifact?.artifactIdentity?.ruleCounts
                    ?.let { counts -> counts.blockExact + counts.blockSuffix }
            }
            .stateIn(generationScope, SharingStarted.Eagerly, null)
        created = CredentialProductOwners(
            graphGeneration = graphGeneration,
            onboarding = onboarding,
            filterMutationsAvailable = initialFilterMutationsAvailable,
            generationScope = generationScope,
            protection = protection,
            domainActions = domainActions,
            activity = activity,
            privacy = privacy,
            diagnostics = diagnostics,
            sampleNerdStats = { com.lavasec.app.diagnostics.sampleLiveNerdStats(context, chainedComposition) },
            reports = reportService?.let { service ->
                com.lavasec.app.diagnostics.NativeBugReportOwner(
                    capture = {
                        val health = LavaVpnRuntimeBridge.health.value.health
                        com.lavasec.app.diagnostics.BugReportEnvironment(
                            version = BuildConfig.VERSION_NAME,
                            build = BuildConfig.VERSION_CODE.toString(),
                            androidVersion = Build.VERSION.RELEASE,
                            apiLevel = Build.VERSION.SDK_INT,
                            vpnStatus = when (LavaVpnRuntimeBridge.state.value) {
                                com.lavasec.app.platform.ProtectionState.Off,
                                com.lavasec.app.platform.ProtectionState.SetupRequired -> "off"
                                is com.lavasec.app.platform.ProtectionState.Starting -> "starting"
                                is com.lavasec.app.platform.ProtectionState.AwaitingNetwork -> "waitingForNetwork"
                                is com.lavasec.app.platform.ProtectionState.Reconnecting -> "reconnecting"
                                is com.lavasec.app.platform.ProtectionState.Protected -> "connected"
                                is com.lavasec.app.platform.ProtectionState.Paused -> "paused"
                                com.lavasec.app.platform.ProtectionState.Stopping -> "stopping"
                                is com.lavasec.app.platform.ProtectionState.Failed -> "failed"
                            },
                            networkAvailable = health.networkAvailable.takeIf { health.context != null },
                            compiledRuleCount = filterAuthority.confirmedState.value
                                ?.verifiedArtifact?.artifactIdentity?.ruleCounts?.total,
                        )
                    },
                    send = service::submit,
                    scope = generationScope,
                )
            },
            customization = customization,
            security = security,
            filterProfileSwitch = filterProfileSwitch,
            filterLibraryState = filterLibraryState,
            filterTotalRuleCount = filterAuthority.confirmedState
                .map { it?.verifiedArtifact?.artifactIdentity?.ruleCounts?.total }
                .stateIn(generationScope, SharingStarted.Eagerly, null),
            filterDraft = filterDraft,
            configurationState = configurationRepository.state
                .stateIn(generationScope, SharingStarted.Eagerly, initialState),
            featureLimits = featureLimits,
            filterActiveRuleCount = filterActiveRuleCount,
            filterCatalog = filterCatalog,
            resolverPlanSwitch = resolverPlanSwitch,
            onClose = owners::remove,
            createExportShareIntent = artifactStore?.let { store ->
                AndroidLocalExportShareIntentFactory(context, store)::create
            },
            filterLibrary = filterLibrary,
            catalogReconstructionRequired = filterAuthority.catalogReconstructionRequired,
            resolverPlan = resolverPlan,
            networkActivityRecorder = networkActivityRecorder,
            protectionAmbient = ambientNotificationOwner,
            catalogForQueries = { if (closed.get()) null else filterAuthority.catalogForQueries() },
            domainChecker = confirmed?.let { { domain -> if (closed.get() || !generationScope.isActive) null else filterAuthority.checkDomain(domain) } },
            domainCheckRevision = confirmed?.let { { if (closed.get() || !generationScope.isActive) null else filterAuthority.domainCheckRevision() } },
            preparedTopUp = confirmed?.let { { expiresAt -> if (closed.get()) 0 else filterAuthority.topUpPrepared(expiresAt) } },
            catalogInstaller = confirmed?.let {
                com.lavasec.app.data.CatalogInstaller { artifact ->
                    installAndPublishFilterCatalog(filterAuthority, artifact, filterCatalog) {
                        !closed.get() && generationScope.isActive
                    }
                }
            },
        )
        owners += created
        if (closed.get()) {
            created.close()
            error("credential product owner factory closed during creation")
        }
        return created
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        owners.toList().forEach(CredentialProductOwners::close)
        owners.clear()
        filterAuthority.closePreparedCache()
        if (processSecurity == null) securityAuthority.close()
    }

    private suspend fun commitOnboarding(
        configuration: com.lavasec.app.foundation.config.AppConfiguration,
    ) {
        configurationRepository.completeOnboarding(configuration)
        val persisted = configurationRepository.state.first()
        check(persisted.onboardingCompleted && persisted.configuration == configuration) {
            "Onboarding configuration did not become authoritative."
        }
        val identity = checkNotNull(persisted.configurationIdentity) {
            "Onboarding configuration identity is unavailable."
        }
        val provisioned = filterAuthority.provisionInitial(configuration, identity)
        if (provisioned is InitialFilterProvisionResult.Unavailable) {
            // Log only bounded typed classifications. A source exception can carry response
            // content or request details, neither of which belongs in onboarding diagnostics.
            Log.w("LavaOnboarding", "Initial filter provisioning failed: reason=${provisioned.reason}; " +
                "sourceFailure=${provisioned.sourceFailure}; compileFailure=${provisioned.compileFailure}")
            error(when (provisioned.reason) {
                InitialFilterProvisionResult.Reason.AUTHORITY_INCOHERENT ->
                    "Lava could not verify the saved filter settings. Try again to recheck your saved setup."
                InitialFilterProvisionResult.Reason.SOURCE_OR_COMPILE_FAILED -> when {
                    provisioned.sourceFailure == SeededSourceCacheFailure.STORAGE_IO ->
                        "Lava could not save the default blocklists. Try again to recheck your saved setup."
                    provisioned.sourceFailure != null ->
                        "Lava could not download the default blocklists. Check your connection and try again."
                    provisioned.compileFailure != null ->
                        "Lava could not compile the default blocklists. Try again to recheck your saved setup."
                    else -> "Lava could not prepare the default blocklists. Try again to recheck your saved setup."
                }
                InitialFilterProvisionResult.Reason.ARTIFACT_VERIFICATION_FAILED ->
                    "Lava could not verify the prepared filter. Try again to recheck your saved setup."
                InitialFilterProvisionResult.Reason.PUBLIC_STAGING_FAILED ->
                    "Lava could not prepare the filter for protection. Try again to recheck your saved setup."
                InitialFilterProvisionResult.Reason.AUTHORITY_COMMIT_FAILED ->
                    "Lava could not save the prepared filter. Try again to recheck your saved setup."
            })
        }
        check(provisioned.state?.configurationIdentity == identity) {
            "Initial filter authority could not be established."
        }
        val published = configurationRepository.setProtectionIntent(false)
        check(
            published is ProtectionIntentWriteResult.AuthoritativeCommitted &&
                published.mirror.isExactRuntimePublished,
        ) { "Initial Direct Boot runtime could not be published." }
    }

    private suspend fun commitProtectionIntent(enabled: Boolean): ProtectionIntentCommit =
        when (val result = configurationRepository.setProtectionIntent(enabled)) {
            is ProtectionIntentWriteResult.AuthoritativeCommitted ->
                ProtectionIntentCommit.Committed
            is ProtectionIntentWriteResult.RejectedBeforeAuthoritativeCommit ->
                ProtectionIntentCommit.Rejected("Protection intent was not committed.")
            is ProtectionIntentWriteResult.AuthoritativeUnchangedMirrorUnavailable ->
                ProtectionIntentCommit.ReconstructionRequired(
                    "Protection authority requires reconstruction.",
                )
        }

    private fun todayLens(): ActivityDateLens {
        val now = nowMillis().also { require(it >= 0L) }
        val today = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
        val start = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
        return ActivityDateLens(start, start, zoneId.id, today)
    }

    private fun diagnosticText(): String {
        val record = diagnosticsStore.load(graphGeneration, nowMillis())
            ?: return "No current operational diagnostic sample."
        val snapshot = record.snapshot
        return buildString {
            appendLine("service=${snapshot.serviceState}")
            appendLine("tunnel=${snapshot.tunnelState}")
            appendLine("session_generation=${snapshot.healthSessionGeneration}")
            appendLine("engine_generation=${snapshot.healthEngineGeneration}")
            append("dropped_private_telemetry=${record.droppedPrivateTelemetryCount}")
        }
    }
}

/**
 * Read-only Filters UI facts for one live confirmed-state emission.
 *
 * The draft is the CURRENT `confirmed.configuration` — after a coordinator commit (a domain
 * review, a library edit, or a whole-filter switch) the authoritative library lives here, not
 * in any configuration captured at graph creation, so a committed change renders immediately
 * without a graph rotation. The catalog naming entries are stable bundled-catalog facts
 * carried from graph creation.
 */
internal fun filterLibraryUiStateFor(
    confirmed: ConfirmedFilterState?,
    catalogEntries: List<FilterSourceCatalogEntry>?,
    limits: FeatureLimits = FeatureLimits.FREE,
): FilterLibraryState? {
    if (confirmed == null || catalogEntries == null) return null
    return FilterLibraryMapper.library(
        draft = confirmed.configuration,
        catalog = catalogEntries,
        activeBlockRuleCount = confirmed.verifiedArtifact.artifactIdentity.ruleCounts
            .let { counts -> counts.blockExact + counts.blockSuffix },
        maxFilters = limits.maxFilters,
    )
}

/** Publish selectable IDs/names at adoption, before the committed state and runtime reload.
 * The generation fence travels with compilation to the final durable publication edge. */
internal suspend fun installAndPublishFilterCatalog(
    authority: ProductionFilterAuthority,
    artifact: com.lavasec.app.foundation.catalog.CatalogArtifact,
    entries: MutableStateFlow<List<FilterSourceCatalogEntry>?>,
    isCurrent: () -> Boolean,
): Boolean = if (!isCurrent()) false else authority.installCatalog(
    artifact,
    isCurrent,
    refreshExistingSources = !com.lavasec.app.edition.AppEdition.current.dynamicCatalog &&
        artifact.provenance == com.lavasec.app.foundation.catalog.CatalogProvenance.BUNDLED,
) { installed ->
    entries.value = installed.sources.map { source ->
        FilterSourceCatalogEntry(source.id, source.name, source.category, source.sourceUrl, source.entryCount)
    }
}
