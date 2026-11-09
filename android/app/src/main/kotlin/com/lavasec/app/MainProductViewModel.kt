package com.lavasec.app

import android.content.Intent
import androidx.lifecycle.ViewModel
import com.lavasec.app.customization.CustomizationOwnerState
import com.lavasec.app.diagnostics.DiagnosticsFeedbackOwnerState
import com.lavasec.app.foundation.biometrics.BiometricAvailability
import com.lavasec.app.foundation.onboarding.OnboardingPhase
import com.lavasec.app.foundation.resolver.CatalogResolverTransport
import com.lavasec.app.foundation.resolver.ChainedDnsEditState
import com.lavasec.app.foundation.resolver.CustomResolverDraft
import com.lavasec.app.foundation.resolver.CustomResolverDraftSession
import com.lavasec.app.foundation.resolver.CustomResolverValidator
import com.lavasec.app.foundation.resolver.FilterResolverPlan
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.foundation.resolver.ResolverEditRejection
import com.lavasec.app.foundation.resolver.ResolverEditResult
import com.lavasec.app.foundation.resolver.ResolverProviderId
import com.lavasec.app.foundation.resolver.ResolverSelection
import com.lavasec.app.foundation.resolver.ResolverTierChoice
import com.lavasec.app.foundation.resolver.SaveResolverTiers
import com.lavasec.app.foundation.resolver.SetResolverFallbackEnabled
import com.lavasec.app.foundation.resolver.SetResolverTierEnabled
import com.lavasec.app.foundation.resolver.toCatalogTransport
import com.lavasec.app.foundation.resolver.toSelection
import com.lavasec.app.foundation.security.LockTimeoutOption
import com.lavasec.app.foundation.security.ProtectedSurface
import com.lavasec.app.onboarding.OnboardingOwnerState
import com.lavasec.app.onboarding.PendingVpnConsent
import com.lavasec.app.platform.LoggingSettings
import com.lavasec.app.platform.SettingsRoute
import com.lavasec.app.platform.AppConfigurationWitness
import com.lavasec.app.platform.AppConfigurationWitnessFacts
import com.lavasec.app.platform.AppConfigurationWitnessSlot
import com.lavasec.app.platform.EntitlementTierFact
import com.lavasec.app.presentation.GuardPrimaryAction
import com.lavasec.app.presentation.ProductNavigation
import com.lavasec.app.security.SecurityAuthorization
import com.lavasec.app.security.SecurityOwnerState
import com.lavasec.app.security.NativeSecurityPresenter
import com.lavasec.app.presentation.activity.ActivityContentState
import com.lavasec.app.presentation.activity.ActivityDecisionUi
import com.lavasec.app.presentation.activity.ActivityUiState
import com.lavasec.app.presentation.activity.DomainActionUi
import com.lavasec.app.presentation.filters.FilterDetailState
import com.lavasec.app.presentation.filters.FilterLibraryMapper
import com.lavasec.app.presentation.filters.FilterLibraryMutationKindUi
import com.lavasec.app.presentation.filters.FilterLibraryMutationUiState
import com.lavasec.app.presentation.filters.FilterLibraryRejectionUi
import com.lavasec.app.presentation.filters.FilterLibraryState
import com.lavasec.app.presentation.filters.FilterPreparationUiState
import com.lavasec.app.presentation.filters.FilterProfileSwitchFailureUi
import com.lavasec.app.presentation.filters.FilterProfileSwitchUiState
import com.lavasec.app.presentation.filters.FilterReviewState
import com.lavasec.app.presentation.filters.FilterSourceCatalogEntry
import com.lavasec.app.presentation.settings.BugReportFailureUi
import com.lavasec.app.presentation.settings.BugReportUiState
import com.lavasec.app.presentation.settings.ResolverPlanSwitchUiState
import com.lavasec.app.presentation.settings.advance
import com.lavasec.app.presentation.settings.backStep
import com.lavasec.app.presentation.settings.discard
import com.lavasec.app.presentation.settings.markFailed
import com.lavasec.app.presentation.settings.markSubmitted
import com.lavasec.app.presentation.settings.markSubmitting
import com.lavasec.app.presentation.settings.selectTopic
import com.lavasec.app.presentation.settings.setAffectedSite
import com.lavasec.app.presentation.settings.setContactEmail
import com.lavasec.app.presentation.settings.setConsentGranted
import com.lavasec.app.presentation.settings.setDescription
import com.lavasec.app.presentation.settings.setDiagnosticsConsent
import com.lavasec.app.foundation.filter.BlocklistFormat
import com.lavasec.app.foundation.filter.CustomBlocklistEditResult
import com.lavasec.app.foundation.filter.CustomBlocklistSourceFailure
import com.lavasec.app.foundation.filter.FilterLibraryEditSession
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.FilterProfileDraft
import com.lavasec.app.foundation.filter.ManualRuleEditResult
import com.lavasec.app.foundation.filter.ManualRuleRejection
import com.lavasec.app.foundation.filter.ManualRuleTarget
import com.lavasec.app.foundation.share.ShareableBlocklistParseFormat
import com.lavasec.app.foundation.share.ShareableCustomBlocklistSource
import com.lavasec.app.product.FilterLibraryEditOutcome
import com.lavasec.app.product.FilterLibraryMutationResult
import com.lavasec.app.product.FilterLibraryOperation
import com.lavasec.app.product.FilterLibraryOwner
import com.lavasec.app.product.FilterProfileSwitchOwner
import com.lavasec.app.product.ResolverDnsContext
import com.lavasec.app.product.ResolverDnsEditContext
import com.lavasec.app.product.ResolverDnsEditOutcome
import com.lavasec.app.product.ResolverPlanSwitchOwner
import com.lavasec.app.product.activity.ActivityDateLens
import com.lavasec.app.product.activity.DomainDecision
import com.lavasec.app.product.domain.DomainAction
import com.lavasec.app.product.privacy.LocalActivityClearSelection
import com.lavasec.app.product.privacy.LocalActivityExportOptions
import com.lavasec.app.product.privacy.PrivacyOwnerState
import com.lavasec.app.protection.ProtectionOwnerState
import java.time.LocalDate
import java.time.ZoneId
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal sealed interface ProtectionCommandResult {
    data class Completed(val settings: SettingsRoute? = null) : ProtectionCommandResult
    data object Cancelled : ProtectionCommandResult
    data object Rejected : ProtectionCommandResult
}

internal data class MainProductUiState(
    val graphGeneration: Long? = null,
    val onboarding: OnboardingOwnerState? = null,
    val onboardingReconstructionInFlight: Boolean = false,
    val protection: ProtectionOwnerState? = null,
    /**
     * The first-run default-filter preparation phase derived from the onboarding authority
     * (B18). The owner's own lifecycle is authoritative once it exists; this is the honest
     * phase for the pre-owner window (fresh install, preparation in flight, or a failed
     * commit requiring reconstruction), never a fabricated "ready".
     */
    val protectionOnboardingPhase: OnboardingPhase? = null,
    val activity: ActivityUiState = ActivityUiState.unavailable,
    val privacy: PrivacyOwnerState? = null,
    val diagnostics: DiagnosticsFeedbackOwnerState? = null,
    val customization: CustomizationOwnerState? = null,
    val security: SecurityOwnerState? = null,
    /**
     * The typed Bug Report route state. Owned here (never by the screen) so
     * the submit lifecycle and the consent fence survive recomposition and
     * navigation; the screen only renders and forwards callbacks.
     */
    val bugReport: BugReportUiState = BugReportUiState(),
    val filterLibrary: FilterLibraryState? = null,
    val filterCompiledRuleCount: Int? = null,
    val featureLimits: com.lavasec.app.foundation.subscription.FeatureLimits =
        com.lavasec.app.foundation.subscription.FeatureLimits.FREE,
    /**
     * The filter whose detail/review the Filters surfaces are currently showing (nil = the
     * overview's "Now filtering" opens the active filter). Owned here so the state projections
     * always derive from one id, never from composable-local state.
     */
    val selectedFilterId: String? = null,
    val filterDetail: FilterDetailState? = null,
    val filterReview: FilterReviewState? = null,
    val newFilter: FilterProfileDraft? = null,
    val filterProfileSwitch: FilterProfileSwitchUiState = FilterProfileSwitchUiState.Idle,
    val filterLibraryMutation: FilterLibraryMutationUiState = FilterLibraryMutationUiState.Idle,
    val filterPreparation: FilterPreparationUiState = FilterPreparationUiState.Idle,
    /**
     * Current authoritative resolver plan from the owner generation's live confirmed filter
     * authority, or null when the product cannot supply it (pre-onboarding or an unavailable
     * confirmed filter). The DNS Resolver settings surface treats null as its honest
     * unavailable state.
     */
    val resolverPlan: MilestoneResolverPlan? = null,
    val resolverPlanSwitch: ResolverPlanSwitchUiState = ResolverPlanSwitchUiState.Idle,
    /**
     * The CONFIRMED typed resolver selection derived from the value-carrying owner plan, or null
     * when the product cannot supply it. The compatibility [resolverPlan] enum can be absent. The `dns` snapshot family and every `dns.*` policy decision
     * read this (never the requested ladder) so confirmed and requested values stay distinct.
     */
    val resolverSelection: ResolverSelection? = null,
    /**
     * The chained-mode DNS edit gate for the current generation. Chaining is latent on
     * Android today, so this stays [ChainedDnsEditState.editable] until the chained
     * composition is threaded into the switch owner.
     */
    val chainedDnsEdit: ChainedDnsEditState = ChainedDnsEditState.editable,
    /**
     * Typed live resolver health derived from the runtime assessment (slice 05),
     * or null when no confirmed assessment exists yet. Carried next to the
     * provider plan so the DNS Resolver surface can show health without ever
     * mixing it into the selection.
     */
    val resolverHealth: com.lavasec.app.presentation.resolver.ResolverHealthUi? = null,
    val productAvailable: Boolean = false,
    val unavailableReason: String? = null,
)

/**
 * A failed custom-blocklist fetch, recovered from the owner's settled failure state. [sourceName]
 * is the user's own label and is used only for copy; the URL never crosses the bridge.
 */
internal data class FilterSourceFailureInfo(
    val sourceName: String?,
    val failure: CustomBlocklistSourceFailure,
    val sourceId: String,
)

/**
 * The outcome of canonical `domains.stage`. It carries the exact refusal so the shared Domain
 * History screen renders the real reason (already blocked, plan limit, protected, unsaved active
 * draft) instead of a single blanket message.
 */
internal sealed interface DomainStageOutcome {
    /** The rule was staged onto [filterId]'s draft and its review witness captured. */
    data class Staged(val filterId: String) : DomainStageOutcome

    /** The active filter already has unsaved edits; the caller must surface that, not stage. */
    data object UnsavedActiveDraft : DomainStageOutcome

    /** The manual-rule editor refused the domain for a typed reason. */
    data class RuleRejected(val reason: ManualRuleRejection) : DomainStageOutcome

    /** No owner, no active filter, a stale generation, or a failed review capture. */
    data object Unavailable : DomainStageOutcome
}

/**
 * A durable mutation can refresh readers and preserve committed settings even when additional
 * cleanup requires reconciliation. This fact alone does not establish complete command success.
 */
private fun com.lavasec.app.product.privacy.PrivacyMutationResult?.isDurableCommit(): Boolean =
    this == com.lavasec.app.product.privacy.PrivacyMutationResult.Completed ||
        this is com.lavasec.app.product.privacy.PrivacyMutationResult.CommittedReconciliationRequired

/** Owns exactly one credential-generation product composition across Activity recreation. */
internal class MainProductViewModel(
    private val factories: StateFlow<CredentialProductOwnerFactory?>,
    private val rotateCredentialGraph: (Long) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val rotateSharedOnboardingGraph: (suspend (
        Long, CredentialProductOwnerFactory, (CredentialGraphTransition) -> Unit,
    ) -> CredentialGraphTransition?)? = null,
) : ViewModel(), AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val closedState = MutableStateFlow(false)
    private val ownerScope = CoroutineScope(SupervisorJob() + dispatcher)
    internal val nativeVpnDraftHosts = com.lavasec.app.reactnative.NativeVpnDraftHostOwner(ownerScope)
    private val owners = AtomicReference<CredentialProductOwners?>()
    private val ownerFactories = java.util.concurrent.ConcurrentHashMap<CredentialProductOwners, CredentialProductOwnerFactory>()
    private data class OwnerAttachment(val factory: CredentialProductOwnerFactory, val owner: CredentialProductOwners?)
    // A null owner here is an actual failure from this exact factory, never a
    // prior/source UI projection's unavailableReason while a successor opens.
    private val ownerAttachment = MutableStateFlow<OwnerAttachment?>(null)
    private val navigationMutex = Mutex()
    private val pendingNavigations = ArrayDeque<ProductNavigation>()
    private var stagedNavigationOwner: CredentialProductOwners? = null
    private val lastRotatedGeneration = AtomicLong(NO_GENERATION)
    private val lastOnboardingReconstructionGeneration = AtomicLong(NO_GENERATION)
    private val lastPrivacyReconciliationGeneration = AtomicLong(NO_GENERATION)
    private val lastFilterSwitchReconstructionGeneration = AtomicLong(NO_GENERATION)
    private val mutableState = MutableStateFlow(MainProductUiState())
    private val selectedFilterId = MutableStateFlow<String?>(null)
    private val filterEditAuthenticationInFlight = AtomicBoolean(false)
    private val filterPresentationEpoch = AtomicLong()
    private val reviewedFilter = AtomicReference<FilterLibraryOwner.ReviewedDraft?>()
    /**
     * The tri-state outcome of the most recent [securityAuthorizeResult]. Owner-backed commands
     * collapse it to a Boolean; [consumeAuthenticationCancellation] lets the bridge recover the
     * user-dismissed-prompt case without changing every owner method's signature.
     */
    private val lastSecurityAuthorization = AtomicReference(SecurityAuthorization.DENIED)
    /**
     * The custom-blocklist fetch failure of the most recent [commitFilterReview], or null.
     * [consumeFilterSourceFailure] lets the bridge name the exact source and reason after the
     * owner's Boolean-collapsed commit instead of a blanket refusal.
     */
    private val lastFilterSourceFailure = AtomicReference<FilterSourceFailureInfo?>(null)
    /** The complete configuration witness published at the last `filter.review`, or null. */
    private val configurationWitnessSlot = AppConfigurationWitnessSlot()
    val configurationWitness: AppConfigurationWitness?
        get() = configurationWitnessSlot.current
    private val bugReportDraft = MutableStateFlow(BugReportUiState())
    /**
     * The live custom-resolver draft editor session (the canonical `dns.customDraft` /
     * `dns.custom` / `customEntry.dismiss` flow). Owned here, never by the screen, so the
     * editor identity survives recomposition. The saved draft is a projection of the confirmed
     * value-carrying resolver plan; optional presentation fields live only for this graph.
     */
    private val customDraftSession = MutableStateFlow<CustomResolverDraftSession?>(null)
    private val customDraftToken = MutableStateFlow<String?>(null)
    private val customSavedDraft = MutableStateFlow(CustomResolverDraft())
    private val customDraftLock = Any()
    private var customSavedOwner: CredentialProductOwners? = null
    private var customSavedPlan: FilterResolverPlan? = null
    private val reportLock = Any()
    private var reportReview: com.lavasec.app.diagnostics.BugReportReview? = null
    private var reportReviewOwner: com.lavasec.app.diagnostics.NativeBugReportOwner? = null
    // A preparation Success is sticky on the owner (Succeeded) so a detached settle still
    // publishes it; this flag tracks whether the surface has consumed the current one.
    private val preparationSuccessConsumed = AtomicBoolean(true)

    val state: StateFlow<MainProductUiState> = mutableState.asStateFlow()

    /** The live custom-resolver draft session, or null when no editor is open. */
    val dnsCustomDraftSession: StateFlow<CustomResolverDraftSession?> = customDraftSession.asStateFlow()

    /** The opaque token identifying the open custom-resolver editor, or null when closed. */
    val dnsCustomDraftToken: StateFlow<String?> = customDraftToken.asStateFlow()

    /** Confirmed custom endpoint, with graph-local display name/secondary when available. */
    val dnsCustomSavedDraft: StateFlow<CustomResolverDraft> = customSavedDraft.asStateFlow()

    init {
        ownerScope.launch {
            factories.collectLatest { factory ->
                if (factory == null) {
                    ownerAttachment.value = null
                    resetCustomDns()
                    mutableState.value = MainProductUiState()
                    return@collectLatest
                }
                val created = try {
                    factory.create(ownerScope)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    ownerAttachment.value = OwnerAttachment(factory, null)
                    mutableState.value = MainProductUiState(
                        unavailableReason = "The credential product graph could not be opened.",
                    )
                    return@collectLatest
                }
                ownerFactories[created] = factory
                check(owners.compareAndSet(null, created)) {
                    "a credential product owner generation is already attached"
                }
                ownerAttachment.value = OwnerAttachment(factory, created)
                try {
                    drainNavigation(created)
                    observe(created)
                } finally {
                    owners.compareAndSet(created, null)
                    ownerFactories.remove(created)
                    ownerAttachment.update { if (it?.owner === created) null else it }
                    resetCustomDns(created)
                    reviewedFilter.get()?.takeIf { it.owner === created.filterLibrary }?.let {
                        reviewedFilter.compareAndSet(it, null)
                    }
                    navigationMutex.withLock {
                        if (stagedNavigationOwner === created) stagedNavigationOwner = null
                    }
                    created.close()
                    synchronized(reportLock) {
                        reportReview = null
                        reportReviewOwner = null
                        bugReportDraft.value = bugReportDraft.value.let { draft ->
                        if (draft.step == com.lavasec.app.presentation.settings.BugReportStepUi.SUBMITTED) draft
                        else draft.copy(
                            reviewedPreview = null, consentGranted = false, submitting = false,
                            step = if (draft.step == com.lavasec.app.presentation.settings.BugReportStepUi.CONSENT)
                                com.lavasec.app.presentation.settings.BugReportStepUi.ATTACHMENTS else draft.step,
                        )
                        }
                    }
                }
            }
        }
    }

    suspend fun stageNavigation(navigation: ProductNavigation) {
        navigationMutex.withLock { pendingNavigations.addLast(navigation) }
        drainNavigation(owners.get())
    }

    suspend fun forwardOnboarding(expectedGeneration: Long? = null): PendingVpnConsent? {
        val current = owners.get()?.takeIf { expectedGeneration == null || it.graphGeneration == expectedGeneration } ?: return null
        val beforeState = current.onboarding.state.value
        current.onboarding.forward()
        val after = current.onboarding.state.value
        if (!beforeState.completed && after.completed &&
            lastRotatedGeneration.getAndSet(current.graphGeneration) != current.graphGeneration
        ) {
            rotateCredentialGraph(current.graphGeneration)
        }
        return after.pendingVpnConsent?.takeIf { pending ->
            beforeState.pendingVpnConsent?.id != pending.id
        }
    }

    /** Shared measured handoff is the sole caller allowed to complete this visit. */
    suspend fun finishSharedOnboarding(
        expectedGeneration: Long,
        expectedOwner: com.lavasec.app.onboarding.OnboardingOwner? = null,
        onGraphTransition: ((CredentialGraphTransition) -> Unit)? = null,
    ): Boolean {
        val current = owners.get()?.takeIf { it.graphGeneration == expectedGeneration } ?: return false
        if (expectedOwner != null && current.onboarding !== expectedOwner) return false
        val sourceFactory = ownerFactories[current]
        val before = current.onboarding.state.value
        if (before.completed || before.reconstructionRequired ||
            before.onboarding?.page != com.lavasec.app.foundation.onboarding.OnboardingPage.CONNECTION_QUALITY) return false
        current.onboarding.forward()
        val completed = current.onboarding.state.value.completed
        if (completed && lastRotatedGeneration.getAndSet(current.graphGeneration) != current.graphGeneration) {
            val witnessed = rotateSharedOnboardingGraph
            if (onGraphTransition != null && witnessed != null && sourceFactory != null) {
                // The native completion is durable even if the original Activity
                // disappears. Rotation owns its lifetime; the callback may still
                // refuse the old entry's UI custody without losing that outcome.
                withContext(NonCancellable) {
                    try {
                        witnessed(current.graphGeneration, sourceFactory) { transition ->
                            if (transition.sourceGeneration == current.graphGeneration &&
                                transition.sourceFactory === sourceFactory && current.onboarding.state.value.completed) {
                                onGraphTransition(transition)
                            }
                        }
                    } catch (_: Exception) { }
                }
            } else rotateCredentialGraph(current.graphGeneration)
        }
        return completed
    }

    internal fun sharedOnboardingOwner(expectedGeneration: Long): com.lavasec.app.onboarding.OnboardingOwner? =
        owners.get()?.takeIf { it.graphGeneration == expectedGeneration }?.onboarding

    /** Wait for this produced factory, never adopt the next/latest graph by number. */
    internal suspend fun awaitSharedOnboardingTransition(transition: CredentialGraphTransition): Boolean {
        val accepted = combine(factories, ownerAttachment, state, closedState) { factory, attachment, frame, isClosed ->
            when {
                isClosed || factory !== transition.successorFactory -> false
                attachment == null || attachment.factory !== transition.successorFactory -> null // Exact produced factory is still opening.
                attachment.owner == null -> false // Actual creation failure, bound to that factory.
                owners.get() !== attachment.owner -> null
                attachment.owner.graphGeneration != transition.successorGeneration -> false
                frame.graphGeneration != transition.successorGeneration -> null
                else -> attachment.owner.onboarding.state.value.completed
            }
        }.first { it != null } == true
        if (!accepted) return false
        // Flow tuples can be queued. Preserve the live owner/factory witness at
        // the return edge even if replacement or close raced the last emission.
        val attached = ownerAttachment.value?.takeIf { it.factory === transition.successorFactory }?.owner ?: return false
        return !closed.get() && factories.value === transition.successorFactory && owners.get() === attached &&
            ownerFactories[attached] === transition.successorFactory && attached.graphGeneration == transition.successorGeneration &&
            state.value.graphGeneration == transition.successorGeneration && attached.onboarding.state.value.completed
    }

    suspend fun backOnboarding(expectedGeneration: Long? = null) {
        owners.get()?.takeIf { expectedGeneration == null || it.graphGeneration == expectedGeneration }?.onboarding?.back()
    }

    /** Reopens durable authority after an ambiguous commit; never replays the wizard draft. */
    fun retryOnboarding(expectedGeneration: Long): Boolean {
        if (closed.get()) return false
        val current = owners.get()?.takeIf { it.graphGeneration == expectedGeneration } ?: return false
        val onboarding = current.onboarding.state.value
        if (onboarding.completed || !onboarding.reconstructionRequired || onboarding.commitInFlight) {
            return false
        }
        while (true) {
            val previous = lastOnboardingReconstructionGeneration.get()
            if (previous == expectedGeneration) return false
            if (lastOnboardingReconstructionGeneration.compareAndSet(previous, expectedGeneration)) break
        }
        if (owners.get() !== current) return false
        mutableState.update { state ->
            if (state.graphGeneration == expectedGeneration) {
                state.copy(onboardingReconstructionInFlight = true)
            } else state
        }
        // The application callback also fences this exact generation before replacing its graph.
        // Until that replacement re-reads the saved records, the failed owner remains terminal.
        rotateCredentialGraph(expectedGeneration)
        return true
    }

    suspend fun completeVpnConsent(id: Long, granted: Boolean, expectedGeneration: Long? = null): Boolean =
        owners.get()?.takeIf { expectedGeneration == null || it.graphGeneration == expectedGeneration }?.onboarding?.completeVpnConsent(id, granted) ?: false

    internal suspend fun cancelOnboardingVpnConsent(id: Long, expectedGeneration: Long): Boolean =
        owners.get()?.takeIf { it.graphGeneration == expectedGeneration }?.onboarding?.cancelVpnConsent(id) ?: false

    suspend fun completeNotificationPermission(granted: Boolean, expectedGeneration: Long? = null): Boolean =
        owners.get()?.takeIf { expectedGeneration == null || it.graphGeneration == expectedGeneration }?.onboarding?.completeNotificationPermission(granted) ?: false

    suspend fun selectProtectionLevel(level: com.lavasec.app.foundation.config.ProtectionLevel, expectedGeneration: Long? = null) {
        owners.get()?.takeIf { expectedGeneration == null || it.graphGeneration == expectedGeneration }?.onboarding?.selectProtectionLevel(level)
    }

    suspend fun selectResolver(plan: com.lavasec.app.foundation.resolver.MilestoneResolverPlan) {
        owners.get()?.onboarding?.selectResolver(plan)
    }

    suspend fun selectOnboardingResolverRoute(
        useDeviceDns: Boolean, useEncryptedFallback: Boolean, expectedGeneration: Long,
    ) {
        owners.get()?.takeIf { it.graphGeneration == expectedGeneration }?.onboarding
            ?.selectResolverRoute(useDeviceDns, useEncryptedFallback)
    }

    suspend fun selectOnboardingFallback(enabled: Boolean, expectedGeneration: Long? = null) {
        owners.get()?.takeIf { expectedGeneration == null || it.graphGeneration == expectedGeneration }?.onboarding?.selectFallback(enabled)
    }

    suspend fun consumeReleasedNavigation(id: Long): Boolean {
        val current = owners.get() ?: return false
        if (!current.onboarding.consumeReleasedNavigation(id)) return false
        navigationMutex.withLock {
            if (stagedNavigationOwner === current) {
                if (pendingNavigations.isNotEmpty()) pendingNavigations.removeFirst()
                stagedNavigationOwner = null
            }
        }
        drainNavigation(current)
        return true
    }

    suspend fun performGuardAction(
        action: GuardPrimaryAction,
        expectedGeneration: Long? = null,
        present: NativeSecurityPresenter = { _, _, _ -> },
    ): SettingsRoute? {
        val generation = expectedGeneration ?: owners.get()?.graphGeneration ?: return null
        return (performGuardActionForGeneration(generation, action, present) as? ProtectionCommandResult.Completed)?.settings
    }

    /** RN/native commands recheck the exact owner and rendered action after native authentication. */
    suspend fun performGuardActionForGeneration(
        generation: Long,
        action: GuardPrimaryAction,
        present: NativeSecurityPresenter = { _, _, _ -> },
    ): ProtectionCommandResult {
        val current = owners.get() ?: return ProtectionCommandResult.Rejected
        val protection = current.protection ?: return ProtectionCommandResult.Rejected
        if (current.graphGeneration != generation || protection.state.value.guard.primaryAction != action) {
            return ProtectionCommandResult.Rejected
        }
        // Canonical LavaAppBridge: ordinary Resume is exempt, every other explicit action is fresh.
        if (action != GuardPrimaryAction.Resume &&
            !securityAuthorize(generation, ProtectedSurface.PROTECTION_CONTROL, fresh = true, present = present)
        ) return ProtectionCommandResult.Cancelled
        if (owners.get() !== current || protection.state.value.guard.primaryAction != action) {
            return ProtectionCommandResult.Rejected
        }
        if (!protection.perform(action)) return ProtectionCommandResult.Rejected
        return ProtectionCommandResult.Completed(protection.consumeSettingsRoute())
    }

    suspend fun performPause(
        duration: com.lavasec.app.foundation.pause.PauseDuration,
        expectedGeneration: Long? = null,
        present: NativeSecurityPresenter = { _, _, _ -> },
    ): ProtectionCommandResult {
        val current = owners.get() ?: return ProtectionCommandResult.Rejected
        val protection = current.protection ?: return ProtectionCommandResult.Rejected
        if ((expectedGeneration != null && current.graphGeneration != expectedGeneration) ||
            !protection.state.value.guard.canPause
        ) return ProtectionCommandResult.Rejected
        if (!securityAuthorize(current.graphGeneration, ProtectedSurface.PROTECTION_PAUSE,
                fresh = true, present = present)) return ProtectionCommandResult.Cancelled
        if (owners.get() !== current || !protection.state.value.guard.canPause) return ProtectionCommandResult.Rejected
        return if (protection.pause(duration)) ProtectionCommandResult.Completed() else ProtectionCommandResult.Rejected
    }

    suspend fun refreshActivity() {
        owners.get()?.activity?.refreshSummary()
    }

    suspend fun selectActivityDateRange(start: LocalDate, endInclusive: LocalDate): Boolean {
        val current = owners.get()?.activity ?: return false
        val dateRange = current.state.value.overview.dateRangeOrNull() ?: return false
        val zone = ZoneId.of(dateRange.zoneId)
        return current.setDateLens(
            ActivityDateLens(
                startDayMillis = start.atStartOfDay(zone).toInstant().toEpochMilli(),
                endDayMillis = endInclusive.atStartOfDay(zone).toInstant().toEpochMilli(),
                zoneId = dateRange.zoneId,
                today = dateRange.today,
            ),
        )
    }

    suspend fun selectTopDomainDecision(decision: ActivityDecisionUi) {
        owners.get()?.activity?.setTopDomainDecision(decision.toDomainDecision())
    }

    /**
     * Canonical `activity.query`/`domains.query` (Top Domains): applies the exact summary lens the
     * read requested. The query owner awaits the resulting owner state, so the decision and search
     * travel with the date lens instead of racing three independent reloads.
     */
    suspend fun setActivitySummaryQuery(
        dateLens: ActivityDateLens?,
        decision: DomainDecision?,
        search: String?,
        expectedGeneration: Long? = null,
    ): Boolean = owners.get()?.takeIf { expectedGeneration == null || it.graphGeneration == expectedGeneration }
        ?.activity?.setSummaryQuery(dateLens, decision, search) ?: false

    /** Canonical `domains.query` (Domain History): applies the exact decision/search read scope. */
    suspend fun setActivityHistoryQuery(
        decision: DomainDecision?,
        search: String?,
        expectedGeneration: Long? = null,
    ): Boolean = owners.get()?.takeIf { expectedGeneration == null || it.graphGeneration == expectedGeneration }
        ?.activity?.setHistoryQuery(decision, search) ?: false

    /**
     * Canonical `domains.enableHistory`. Enabling is a privacy logging mutation and flows through
     * the same generation-fenced `setLogging` as the native Privacy surface, so retention is
     * committed before any history row is read. Already-on is an honest success.
     */
    suspend fun enableDomainHistory(): Boolean {
        val current = owners.get() ?: return false
        val logging = current.privacy?.state?.value?.logging ?: return false
        if (logging.keepHistory) return true
        return setLogging(logging.copy(keepHistory = true))
    }

    suspend fun setTopDomainSearch(search: String) {
        owners.get()?.activity?.setTopDomainSearch(search)
    }

    suspend fun refreshHistory() {
        owners.get()?.activity?.refreshHistory()
    }

    suspend fun selectHistoryDecision(decision: ActivityDecisionUi) {
        owners.get()?.activity?.setHistoryDecision(decision.toDomainDecision())
    }

    suspend fun setHistorySearch(search: String) {
        owners.get()?.activity?.setHistorySearch(search)
    }

    suspend fun loadMoreHistory(expectedGeneration: Long? = null) {
        owners.get()?.takeIf { expectedGeneration == null || it.graphGeneration == expectedGeneration }
            ?.activity?.loadMoreHistory()
    }

    /** Canonical domains.stage always authenticates freshly before creating its review. */
    suspend fun requestDomainAction(
        domain: String,
        action: DomainActionUi,
        expectedGeneration: Long,
        present: NativeSecurityPresenter,
    ): com.lavasec.app.product.domain.DomainActionRequestResult? {
        val current = owners.get() ?: return null
        val domainActions = current.domainActions ?: return null
        if (current.graphGeneration != expectedGeneration) return null
        if (!securityAuthorize(expectedGeneration, ProtectedSurface.FILTERS, fresh = true, present = present)) return null
        if (owners.get() !== current) return null
        return domainActions.request(domain, action.toDomainAction())
    }

    /**
     * Canonical `domains.stage` from Domain History: stage one blocked/allowed domain onto the
     * ACTIVE filter's draft and open its review context. Mirrors the iOS
     * `stageDomainHistoryDomainAction` — it edits the active filter keyed by id, refuses when that
     * filter already holds unsaved edits, authenticates freshly for filter editing, and reports the
     * exact outcome so the shared screen can show the real reason (never a blanket message).
     */
    suspend fun stageDomainAction(
        domain: String,
        target: ManualRuleTarget,
        expectedGeneration: Long,
        present: NativeSecurityPresenter,
    ): DomainStageOutcome = stageDomainActionOwned(domain, target, expectedGeneration, present,
        isCurrent = { true }, onDraftOpened = { true })

    /** Only native lifetime custody sees this graph/library/lease witness; no raw rule or credential is kept. */
    internal class StandaloneDomainReviewRetirementWitness internal constructor(
        internal val detail: FilterDetailRetirementWitness,
        internal val library: FilterLibraryOwner,
        internal val draftLease: String,
    ) {
        internal var cleanup: kotlinx.coroutines.Deferred<Boolean>? = null
        internal val filterId get() = detail.filterId
        internal val generation get() = detail.graph.graphGeneration
    }

    /** Register the exact newly opened draft before its next suspension. */
    internal suspend fun stageDomainActionOwned(
        domain: String, target: ManualRuleTarget, expectedGeneration: Long,
        present: NativeSecurityPresenter, isCurrent: () -> Boolean,
        onDraftOpened: (StandaloneDomainReviewRetirementWitness) -> Boolean,
        domainCheckAction: com.lavasec.app.foundation.filter.DomainCheckRuleAction? = null,
    ): DomainStageOutcome {
        val current = owners.get() ?: return DomainStageOutcome.Unavailable
        val library = current.filterLibrary ?: return DomainStageOutcome.Unavailable
        if (current.graphGeneration != expectedGeneration || !isCurrent()) return DomainStageOutcome.Unavailable
        val activeId = current.filterDraft.value?.activeProfileId ?: return DomainStageOutcome.Unavailable
        if (!securityAuthorize(expectedGeneration, ProtectedSurface.FILTERS, fresh = true, present = present)) {
            return DomainStageOutcome.Unavailable
        }
        if (owners.get() !== current || !isCurrent()) return DomainStageOutcome.Unavailable
        if (library.hasUnsavedDraft(activeId)) return DomainStageOutcome.UnsavedActiveDraft
        if (owners.get() !== current || !isCurrent()) return DomainStageOutcome.Unavailable
        val detail = openFilterDetailOwned(activeId, expectedGeneration, isCurrent)
            ?: return DomainStageOutcome.Unavailable
        var witness: StandaloneDomainReviewRetirementWitness? = null
        var staged = false
        var originalFailure: Throwable? = null
        try {
            // beginEdit can suspend behind the actual admission lock. The detail already has
            // native custody, so every failure before a draft witness also needs exact cleanup.
            if (!library.beginEdit(activeId) { owners.get() === current && isCurrent() }) {
                return DomainStageOutcome.Unavailable
            }
            val lease = library.draftLease(activeId) ?: return DomainStageOutcome.Unavailable
            val opened = StandaloneDomainReviewRetirementWitness(detail, library, lease)
            witness = opened
            val stillOwned = {
                !closed.get() && owners.get() === current && current.filterLibrary === library &&
                    library.draftLease(activeId) == lease && isCurrent()
            }
            if (!onDraftOpened(opened) || !stillOwned()) return DomainStageOutcome.Unavailable
            val edit = if (domainCheckAction == null) library.addDomainToDraft(activeId, domain, target, lease, stillOwned)
                else library.stageDomainCheckInDraft(activeId, domain, domainCheckAction, lease, stillOwned)
            when (val add = edit) {
                is ManualRuleEditResult.Rejected -> return DomainStageOutcome.RuleRejected(add.reason)
                is ManualRuleEditResult.Applied -> Unit
                null -> return DomainStageOutcome.Unavailable
            }
            val review = (library.captureReviewAttempt(activeId, expectedLease = lease,
                isCurrent = stillOwned) as? FilterLibraryOwner.ReviewAttempt.Captured)?.review
            if (review == null || !stillOwned()) return DomainStageOutcome.Unavailable
            reviewedFilter.set(review)
            staged = true
            return DomainStageOutcome.Staged(activeId)
        } catch (failure: Throwable) {
            originalFailure = failure
            throw failure
        } finally {
            if (!staged) {
                try {
                    // Both jobs belong to the surviving VM scope. A cancelled/failed caller
                    // schedules compensation without awaiting and replacing its original error.
                    val cleanup = witness?.let(::retireStandaloneDomainReview) ?: retireFilterDetail(detail)
                    if (originalFailure == null) cleanup.await()
                } catch (cleanupFailure: Throwable) {
                    val original = originalFailure
                    if (original == null) throw cleanupFailure
                    if (cleanupFailure !== original) original.addSuppressed(cleanupFailure)
                }
            }
        }
    }

    internal fun retireStandaloneDomainReview(
        witness: StandaloneDomainReviewRetirementWitness,
    ): kotlinx.coroutines.Deferred<Boolean> {
        val cleanup = synchronized(witness) {
            witness.cleanup ?: ownerScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                val detail = witness.detail
                val current = detail.graph
                val library = witness.library
                val stillOwned = { !closed.get() && owners.get() === current && current.filterLibrary === library }
                if (!stillOwned()) return@async false
                val review = reviewedFilter.get()?.takeIf { it.owner === library && it.filterId == detail.filterId }
                if (!library.discardDraft(detail.filterId, null, null, witness.draftLease, stillOwned)) return@async false
                review?.let { reviewedFilter.compareAndSet(it, null) }
                if (stillOwned() && selectedFilterId.value == detail.filterId &&
                    filterPresentationEpoch.get() == detail.presentationEpoch) {
                    filterPresentationEpoch.incrementAndGet()
                    selectedFilterId.value = null
                }
                true
            }.also { witness.cleanup = it }
        }
        cleanup.start()
        return cleanup
    }

    suspend fun markDomainReviewVisible(reviewId: Long, expectedGeneration: Long): Boolean =
        owners.get()?.takeIf { it.graphGeneration == expectedGeneration }?.domainActions?.markReviewVisible(reviewId) ?: false

    suspend fun cancelDomainReviewBeforeVisible(reviewId: Long, expectedGeneration: Long): Boolean =
        owners.get()?.takeIf { it.graphGeneration == expectedGeneration }?.domainActions?.cancelBeforeVisible(reviewId) ?: false

    suspend fun confirmDomainAction(
        reviewId: Long,
        expectedGeneration: Long,
        present: NativeSecurityPresenter,
    ): com.lavasec.app.product.domain.DomainActionConfirmResult? {
        val current = owners.get() ?: return null
        val domainActions = current.domainActions ?: return null
        if (current.graphGeneration != expectedGeneration) return null
        if (!securityAuthorize(expectedGeneration, ProtectedSurface.FILTERS, present = present)) return null
        if (owners.get() !== current) return null
        return domainActions.confirm(reviewId)
    }

    // Teardown remains authentication-exempt, but an old render cannot cancel a new owner's review.
    suspend fun cancelDomainAction(reviewId: Long, expectedGeneration: Long): Boolean =
        owners.get()?.takeIf { it.graphGeneration == expectedGeneration }?.domainActions?.cancel(reviewId) ?: false

    suspend fun dismissDomainNotice(noticeId: Long, expectedGeneration: Long): Boolean =
        owners.get()?.takeIf { it.graphGeneration == expectedGeneration }?.domainActions?.dismissNotice(noticeId) ?: false

    /**
     * Requests one whole-filter Apply through the exact current owner generation. The owner
     * fences its own close (a retired generation cannot reach its coordinator), matching the
     * domain-action fence: the owners reference is captured at call time and only that
     * generation's switch capability runs.
     */
    suspend fun requestFilterProfileApply(
        filterId: String,
        expectedGeneration: Long? = null,
        present: NativeSecurityPresenter = { _, _, _ -> },
    ): Boolean {
        val current = owners.get() ?: return false
        if (expectedGeneration != null && current.graphGeneration != expectedGeneration) return false
        val switch = current.filterProfileSwitch ?: return false
        // Canonical LavaAppFilters.filter.switch always asks freshly, even with an editing grant.
        if (!securityAuthorize(current.graphGeneration, ProtectedSurface.FILTERS, fresh = true, present = present)) return false
        if (owners.get() !== current) return false
        // The current switch owner revalidates existence, tier and reconstruction after the prompt.
        return switch.apply(filterId)
    }

    /** Native-only capability captured at the actual detail-selection boundary. */
    internal class FilterDetailRetirementWitness internal constructor(
        internal val graph: CredentialProductOwners,
        internal val library: FilterLibraryOwner?,
        internal val filterId: String,
        internal val presentationEpoch: Long,
        internal val wasCreating: Boolean,
        internal val creationLease: String?,
    )

    /** Opens the detail surface for one filter (the drafts stay per-filter by id). */
    suspend fun openFilterDetail(filterId: String) {
        openFilterDetailCurrent(filterId, null, null)
    }

    /** A shared native host receives the exact selected visit, never a later snapshot. */
    internal suspend fun openFilterDetailOwned(
        filterId: String, expectedGeneration: Long, isCurrent: () -> Boolean,
    ): FilterDetailRetirementWitness? = openFilterDetailCurrent(filterId, expectedGeneration, isCurrent)

    private suspend fun openFilterDetailCurrent(
        filterId: String, expectedGeneration: Long?, isCurrent: (() -> Boolean)?,
    ): FilterDetailRetirementWitness? {
        val current = owners.get() ?: return null
        if (expectedGeneration != null && current.graphGeneration != expectedGeneration) return null
        if (isCurrent?.invoke() == false) return null
        val library = current.filterLibrary
        if (current.filterDraft.value?.profile(filterId) == null && library?.isCreating(filterId) != true) return null
        val previous = selectedFilterId.value
        val presentation = filterPresentationEpoch.get()
        val stillCurrent = {
            !closed.get() && owners.get() === current && selectedFilterId.value == previous &&
                filterPresentationEpoch.get() == presentation && isCurrent?.invoke() != false
        }
        if (previous != null && previous != filterId && library?.isCreating(previous) == true) {
            val lease = library.draftLease(previous) ?: return null
            if (!library.discardDraft(previous, null, null, lease, stillCurrent)) return null
        }
        if (!stillCurrent()) return null
        val epoch = filterPresentationEpoch.incrementAndGet()
        val witness = FilterDetailRetirementWitness(current, library, filterId, epoch,
            library?.isCreating(filterId) == true, library?.draftLease(filterId))
        reviewedFilter.set(null)
        selectedFilterId.value = filterId
        return witness
    }

    /** Schedule directly from native teardown: this job belongs to the surviving VM,
     * not the retiring Activity/module coroutine. No read grant is needed for exact cleanup. */
    internal fun retireFilterDetail(witness: FilterDetailRetirementWitness): kotlinx.coroutines.Deferred<Boolean> =
        ownerScope.async {
            val stillOwned = {
                !closed.get() && owners.get() === witness.graph && witness.graph.filterLibrary === witness.library &&
                    selectedFilterId.value == witness.filterId && filterPresentationEpoch.get() == witness.presentationEpoch
            }
            if (!stillOwned()) return@async false
            val library = witness.library
            if (library?.isCreating(witness.filterId) == true) {
                val lease = witness.creationLease.takeIf { witness.wasCreating } ?: return@async false
                if (!library.discardDraft(witness.filterId, null, null, lease, stillOwned)) return@async false
            }
            if (!stillOwned()) return@async false
            filterPresentationEpoch.incrementAndGet()
            reviewedFilter.set(null)
            selectedFilterId.value = null
            true
        }

    /** Saved filters retain their rule drafts; leaving an unsaved creation discards it. */
    suspend fun closeFilterDetail(expectedFilterId: String? = null) {
        val selected = selectedFilterId.value
        if (expectedFilterId != null && selected != expectedFilterId) return
        val presentation = filterPresentationEpoch.get()
        val current = owners.get()
        val library = current?.filterLibrary
        val stillOwned = {
            owners.get() === current && selectedFilterId.value == selected && filterPresentationEpoch.get() == presentation
        }
        if (selected != null && library?.isCreating(selected) == true) {
            val lease = library.draftLease(selected) ?: return
            if (!library.discardDraft(selected, null, null, lease, stillOwned)) return
        }
        if (!stillOwned()) return
        filterPresentationEpoch.incrementAndGet()
        reviewedFilter.set(null)
        selectedFilterId.value = null
    }

    // ---- Read-only share/import facts (the FilterShareOwner port) ----

    /** The live saved profile for one filter id, or null when unknown/unavailable. */
    fun savedFilterProfile(filterId: String): FilterProfileDraft? =
        owners.get()?.filterDraft?.value?.profile(filterId)

    fun domainCheckRevisionForSnapshot(expectedGeneration: Long?): String? {
        val current = owners.get()?.takeIf { it.graphGeneration == expectedGeneration } ?: return null
        return current.domainCheckRevision?.invoke()?.takeIf { owners.get() === current }
    }

    suspend fun checkDomain(domain: String, expectedGeneration: Long): com.lavasec.app.product.LocalDomainCheckEvidence? {
        val current = owners.get()?.takeIf { it.graphGeneration == expectedGeneration } ?: return null
        return current.domainChecker?.invoke(domain)?.takeIf { owners.get() === current && !closed.get() }
    }

    /** One saved library from the same credential graph as the rendered snapshot. */
    fun savedFiltersForSnapshot(expectedGeneration: Long?): List<FilterProfileDraft> {
        val current = owners.get() ?: return emptyList()
        if (current.graphGeneration != expectedGeneration) return emptyList()
        val profiles = current.filterDraft.value?.profiles.orEmpty()
        return profiles.takeIf { owners.get() === current }.orEmpty()
    }

    /** Read the immutable review witness, never an asynchronously rendered review projection. */
    fun reviewedFilterWeakensProtection(filterId: String, expectedGeneration: Long): Boolean? {
        val current = owners.get() ?: return null
        val review = reviewedFilter.get() ?: return null
        if (current.graphGeneration != expectedGeneration || review.owner !== current.filterLibrary ||
            review.filterId != filterId || selectedFilterId.value != filterId) return null
        review.newIdentity?.let { identity ->
            return false.takeIf { current.filterLibrary?.newFilter?.value === identity }
        }
        val saved = review.baseline.library.profile(filterId) ?: return null
        return com.lavasec.app.foundation.filter.FilterDraftPolicy.diff(saved, review.draft).weakensProtection
    }

    /**
     * Captures the complete configuration review invalidator for [expectedGeneration], or null
     * when the configuration is not complete/coherent. [tier] is the current entitlement fact
     * from the account/billing authority. Pure: it does not publish.
     */
    fun captureConfigurationWitness(
        expectedGeneration: Long,
        tier: EntitlementTierFact,
    ): AppConfigurationWitness? {
        val current = owners.get() ?: return null
        if (current.graphGeneration != expectedGeneration) return null
        val library = current.filterDraft.value ?: return null
        val state = current.configurationState.value
        return runCatching {
            AppConfigurationWitness.capture(
                AppConfigurationWitnessFacts.fromPersisted(state, library, tier),
                generation = state.configurationIdentity?.generation ?: 0L,
            )
        }.getOrNull()
    }

    /** Captures and atomically publishes the configuration witness for a new review. */
    fun publishConfigurationWitness(
        expectedGeneration: Long,
        tier: EntitlementTierFact,
    ): AppConfigurationWitness? =
        captureConfigurationWitness(expectedGeneration, tier)?.let(configurationWitnessSlot::publish)

    /** Retires the visible witness after a review is consumed or discarded. */
    fun clearConfigurationWitness() {
        configurationWitnessSlot.clear()
    }

    suspend fun finishUnchangedFilterEdit(filterId: String, expectedGeneration: Long): Boolean {
        val current = owners.get() ?: return false
        if (current.graphGeneration != expectedGeneration || selectedFilterId.value != filterId) return false
        val accepted = current.filterLibrary?.discardUnchangedDraft(filterId) == true
        if (accepted) reviewedFilter.set(null)
        return accepted
    }

    /**
     * Custom blocklist definitions to enumerate in a share payload, read live from the confirmed
     * library (never a captured snapshot). A device with no custom sources returns empty.
     */
    fun customBlocklistSources(): List<ShareableCustomBlocklistSource> =
        owners.get()?.filterLibrary?.currentLibrary()?.profiles
            ?.flatMap { it.customBlocklists }
            ?.map { source ->
                ShareableCustomBlocklistSource(
                    id = source.id,
                    displayName = source.displayName,
                    sourceUrl = source.sourceUrl,
                    parseFormat = ShareableBlocklistParseFormat.fromWire(source.parseFormat.name.lowercase())
                        ?: ShareableBlocklistParseFormat.AUTO,
                )
            }
            ?: emptyList()

    /**
     * The durable custom blocklist definitions of the displayed filter (its draft when one is
     * open, else its saved profile), for the shared picker's "Your Lists" section.
     */
    fun customBlocklistDefinitionsForDisplayedFilter(): List<com.lavasec.app.foundation.filter.CustomBlocklistSource> {
        val current = owners.get() ?: return emptyList()
        val filterId = selectedFilterId.value ?: return emptyList()
        val owner = current.filterLibrary ?: return emptyList()
        return (owner.draftFor(filterId) ?: owner.currentLibrary().profile(filterId))
            ?.customBlocklists
            ?: emptyList()
    }

    /** The signed catalog's source ids, or empty when the graph has not supplied a catalog. */
    fun availableCatalogSourceIds(): Set<String> =
        owners.get()?.filterCatalog?.value?.map { it.id }?.toSet() ?: emptySet()

    /** Current catalog from the attached product generation. */
    internal fun catalogForQueries(): com.lavasec.app.data.BundledBlocklistCatalog? = owners.get()?.catalogForQueries?.invoke()

    /** Best-effort cache preparation through the attached native owner. */
    internal suspend fun topUpPreparedFilters(expiresAt: Long): Int = owners.get()?.preparedTopUp?.invoke(expiresAt) ?: 0

    /**
     * Installs one validated remote catalog into the live filter authority of the current
     * generation. Returns false when no owner is attached or the authority kept its resident
     * artifact. The durable catalog cache is written by the sync owner, not here.
     */
    suspend fun installCatalog(artifact: com.lavasec.app.foundation.catalog.CatalogArtifact): Boolean =
        owners.get()?.catalogInstaller?.install(artifact) ?: false

    /** The current plan limits used to reconcile an imported payload. */
    fun currentFeatureLimits(): com.lavasec.app.foundation.subscription.FeatureLimits =
        owners.get()?.featureLimits?.value
            ?: com.lavasec.app.foundation.subscription.FeatureLimits.FREE

    /** Canonical filter.edit authenticates once and cannot replace an already-open draft. */
    suspend fun beginFilterEdit(
        filterId: String,
        expectedGeneration: Long,
        present: NativeSecurityPresenter,
    ): Boolean {
        val current = owners.get() ?: return false
        val owner = current.filterLibrary ?: return false
        val presentation = filterPresentationEpoch.get()
        if (current.graphGeneration != expectedGeneration || selectedFilterId.value != filterId) return false
        if (!filterEditAuthenticationInFlight.compareAndSet(false, true)) return false
        return try {
            if (!securityAuthorize(expectedGeneration, ProtectedSurface.FILTERS, present = present)) return false
            if (owners.get() !== current || filterPresentationEpoch.get() != presentation || selectedFilterId.value != filterId) return false
            owner.beginEdit(filterId)
        } finally {
            filterEditAuthenticationInFlight.set(false)
        }
    }

    private suspend fun <T> withFilterEditing(
        expectedGeneration: Long,
        filterId: String? = null,
        present: NativeSecurityPresenter,
        fresh: Boolean = false,
        action: suspend (FilterLibraryOwner) -> T,
    ): T? {
        val current = owners.get() ?: return null
        val library = current.filterLibrary ?: return null
        val presentation = filterPresentationEpoch.get()
        if (current.graphGeneration != expectedGeneration ||
            (filterId != null && selectedFilterId.value != filterId)) return null
        if (!securityAuthorize(expectedGeneration, ProtectedSurface.FILTERS, fresh = fresh, present = present)) return null
        if (owners.get() !== current || (filterId != null &&
                (filterPresentationEpoch.get() != presentation || selectedFilterId.value != filterId))) return null
        return action(library)
    }

    suspend fun addBlockedDomainToDraft(
        filterId: String,
        domain: String,
        expectedGeneration: Long,
        present: NativeSecurityPresenter,
    ): ManualRuleEditResult? = withFilterEditing(expectedGeneration, filterId, present) { owner ->
        owner.addDomainToDraft(filterId, domain, ManualRuleTarget.BLOCKED).also {
            if (it is ManualRuleEditResult.Applied) reviewedFilter.set(null)
        }
    }

    suspend fun addAllowedExceptionToDraft(
        filterId: String,
        domain: String,
        expectedGeneration: Long,
        present: NativeSecurityPresenter,
    ): ManualRuleEditResult? = withFilterEditing(expectedGeneration, filterId, present) { owner ->
        owner.addDomainToDraft(filterId, domain, ManualRuleTarget.ALLOWED).also {
            if (it is ManualRuleEditResult.Applied) reviewedFilter.set(null)
        }
    }

    suspend fun removeDomainFromDraft(
        filterId: String,
        domain: String,
        target: ManualRuleTarget,
        expectedGeneration: Long,
        present: NativeSecurityPresenter,
    ): Boolean = withFilterEditing(expectedGeneration, filterId, present) { owner ->
        owner.removeDomainFromDraft(filterId, domain, target).also {
            if (it) reviewedFilter.set(null)
        }
    } ?: false

    suspend fun replaceSourcesInDraft(
        filterId: String, sourceIds: Set<String>, expectedGeneration: Long, present: NativeSecurityPresenter,
    ): Boolean {
        val current = owners.get() ?: return false
        if (current.graphGeneration != expectedGeneration) return false
        val catalog = current.filterCatalog.value ?: return false
        val known = catalog.map { it.id }.toSet() + current.filterLibrary?.draftFor(filterId)
            ?.customBlocklists.orEmpty().map { it.id }
        if (!known.containsAll(sourceIds)) return false
        return withFilterEditing(expectedGeneration, filterId, present) { owner ->
            if (owners.get() !== current) false else owner.replaceSourcesInDraft(filterId, sourceIds).also {
                if (it) reviewedFilter.set(null)
            }
        } ?: false
    }

    suspend fun toggleSourceInDraft(
        filterId: String,
        sourceId: String,
        enabled: Boolean,
        expectedGeneration: Long,
        present: NativeSecurityPresenter,
    ): Boolean = withFilterEditing(expectedGeneration, filterId, present) { owner ->
        owner.toggleSourceInDraft(filterId, sourceId, enabled).also {
            if (it) reviewedFilter.set(null)
        }
    } ?: false

    suspend fun undoDomainInDraft(
        filterId: String,
        domain: String,
        expectedGeneration: Long,
        present: NativeSecurityPresenter,
    ): Boolean = withFilterEditing(expectedGeneration, filterId, present) { owner ->
        owner.undoDomainInDraft(filterId, domain).also {
            if (it) reviewedFilter.set(null)
        }
    } ?: false

    suspend fun undoSourceInDraft(
        filterId: String,
        sourceId: String,
        expectedGeneration: Long,
        present: NativeSecurityPresenter,
    ): Boolean = withFilterEditing(expectedGeneration, filterId, present) { owner ->
        owner.undoSourceInDraft(filterId, sourceId).also {
            if (it) reviewedFilter.set(null)
        }
    } ?: false

    /** Adds a Plus-gated custom blocklist to the draft and enables it atomically. */
    suspend fun addCustomBlocklistToDraft(
        filterId: String,
        name: String,
        url: String,
        format: BlocklistFormat,
        expectedGeneration: Long,
        present: NativeSecurityPresenter,
    ): CustomBlocklistEditResult? = addCustomBlocklistToDraft(filterId, name, url, format,
        expectedGeneration, present, null)

    suspend fun addCustomBlocklistToDraft(
        filterId: String, name: String, url: String, format: BlocklistFormat,
        expectedGeneration: Long, present: NativeSecurityPresenter, expectedLease: String?,
    ): CustomBlocklistEditResult? {
        val current = owners.get() ?: return null
        return withFilterEditing(expectedGeneration, filterId, present) { owner ->
            if (owners.get() !== current) {
                null
            } else {
                owner.addCustomBlocklistToDraft(filterId, name, url, format, expectedLease) { candidateUrl ->
                    catalogSourceIdForUrl(current, candidateUrl)
                }.also {
                    if (it is CustomBlocklistEditResult.Applied) reviewedFilter.set(null)
                }
            }
        }
    }

    suspend fun deleteCustomBlocklistFromDraft(
        filterId: String,
        sourceId: String,
        expectedGeneration: Long,
        present: NativeSecurityPresenter,
    ): Boolean = withFilterEditing(expectedGeneration, filterId, present) { owner ->
        owner.deleteCustomBlocklistFromDraft(filterId, sourceId).also {
            if (it) reviewedFilter.set(null)
        }
    } ?: false

    suspend fun undoCustomBlocklistInDraft(
        filterId: String,
        sourceId: String,
        expectedGeneration: Long,
        present: NativeSecurityPresenter,
    ): Boolean = withFilterEditing(expectedGeneration, filterId, present) { owner ->
        owner.undoCustomBlocklistInDraft(filterId, sourceId).also {
            if (it) reviewedFilter.set(null)
        }
    } ?: false

    /** The curated catalog source whose URL matches [url], or null. Used for known-URL collapse. */
    private fun catalogSourceIdForUrl(
        owners: com.lavasec.app.CredentialProductOwners,
        url: String,
    ): String? = owners.filterCatalog.value
        ?.firstOrNull { it.sourceUrl.isNotBlank() && it.sourceUrl == url }
        ?.id

    suspend fun discardFilterDraft(filterId: String) {
        discardFilterDraft(filterId, null, null)
    }

    suspend fun discardFilterDraft(
        filterId: String,
        expectedDraft: com.lavasec.app.foundation.filter.FilterProfileDraft?,
        expectedNewFilter: com.lavasec.app.foundation.filter.FilterProfileDraft?,
        expectedGeneration: Long? = null,
        expectedLease: String? = null,
    ): Boolean {
        val current = owners.get() ?: return false
        if (expectedGeneration != null && current.graphGeneration != expectedGeneration) return false
        val library = current.filterLibrary ?: return false
        val review = reviewedFilter.get()?.takeIf { it.owner === library && it.filterId == filterId }
        if (!library.discardDraft(filterId, expectedDraft, expectedNewFilter, expectedLease)) return false
        if (owners.get() !== current) return false
        review?.let { reviewedFilter.compareAndSet(it, null) }
        return true
    }

    /** Canonical filter.save authenticates freshly before showing the reviewed draft. */
    suspend fun requestFilterReview(
        filterId: String,
        expectedGeneration: Long,
        present: NativeSecurityPresenter,
    ): Boolean = requestFilterReviewAttempt(filterId, expectedGeneration, present) is
        FilterLibraryOwner.ReviewAttempt.Captured

    /** Preserve a typed capacity refusal from the actual owned draft, never a delayed UI frame. */
    suspend fun requestFilterReviewAttempt(
        filterId: String,
        expectedGeneration: Long,
        present: NativeSecurityPresenter,
    ): FilterLibraryOwner.ReviewAttempt {
        val unavailable = FilterLibraryOwner.ReviewAttempt.Unavailable
        val current = owners.get() ?: return unavailable
        val library = current.filterLibrary ?: return unavailable
        val presentation = filterPresentationEpoch.get()
        if (current.graphGeneration != expectedGeneration || selectedFilterId.value != filterId) return unavailable
        val draft = library.draftFor(filterId) ?: return unavailable
        val lease = library.draftLease(filterId) ?: return unavailable
        reviewedFilter.set(null)
        if (!securityAuthorize(expectedGeneration, ProtectedSurface.FILTERS, fresh = true, present = present)) return unavailable
        val authorized = captureMutationAuthorizationFence(expectedGeneration, ProtectedSurface.FILTERS) ?: return unavailable
        val readRevision = current.security?.state?.value?.authenticationRevision ?: return unavailable
        val stillOwned = {
            owners.get() === current && filterPresentationEpoch.get() == presentation &&
                selectedFilterId.value == filterId && library.draftFor(filterId) === draft &&
                library.draftLease(filterId) == lease && current.security?.state?.value?.authenticationRevision == readRevision && authorized()
        }
        if (!stillOwned()) return unavailable
        val attempt = library.captureReviewAttempt(filterId, draft, lease, stillOwned)
        if (!stillOwned()) return unavailable
        if (attempt is FilterLibraryOwner.ReviewAttempt.Captured) reviewedFilter.set(attempt.review)
        return attempt
    }

    /** Save's untouched-template exception uses the exact native review, never a lagging UI frame. */
    fun reviewedFilterIsUntouchedCreation(filterId: String, expectedGeneration: Long): Boolean {
        val current = owners.get() ?: return false
        val library = current.filterLibrary ?: return false
        val review = reviewedFilter.get() ?: return false
        val identity = review.newIdentity ?: return false
        if (current.graphGeneration != expectedGeneration || review.owner !== library ||
            review.filterId != filterId || selectedFilterId.value != filterId ||
            library.newFilter.value !== identity || library.draftFor(filterId) !== review.draft) return false
        return !com.lavasec.app.foundation.filter.FilterDraftPolicy.diff(identity, review.draft).hasChanges &&
            identity.customBlocklists == review.draft.customBlocklists
    }

    /** Opening the child review page reuses Save's witness and authorization turn. */
    suspend fun openFilterReview(
        filterId: String, expectedGeneration: Long, present: NativeSecurityPresenter,
    ): Boolean {
        val review = reviewedFilter.get()
            ?: return requestFilterReview(filterId, expectedGeneration, present)
        val current = owners.get() ?: return false
        val presentation = filterPresentationEpoch.get()
        if (reviewedFilterWeakensProtection(filterId, expectedGeneration) == null) return false
        if (!securityAuthorize(expectedGeneration, ProtectedSurface.FILTERS, present = present)) return false
        return owners.get() === current && filterPresentationEpoch.get() == presentation &&
            reviewedFilter.get() === review && selectedFilterId.value == filterId
    }

    /** Confirmation revalidates the exact review; the fresh save grant is reusable on this child page. */
    suspend fun commitFilterReview(
        filterId: String,
        expectedGeneration: Long,
        onAdmitted: () -> Unit = {},
        present: NativeSecurityPresenter,
    ): Boolean {
        lastFilterSourceFailure.set(null)
        val current = owners.get() ?: return false
        val library = current.filterLibrary ?: return false
        val review = reviewedFilter.get() ?: return false
        val presentation = filterPresentationEpoch.get()
        if (current.graphGeneration != expectedGeneration || review.owner !== library ||
            review.filterId != filterId || selectedFilterId.value != filterId) return false
        if (!securityAuthorize(expectedGeneration, ProtectedSurface.FILTERS, present = present)) return false
        if (owners.get() !== current || filterPresentationEpoch.get() != presentation || reviewedFilter.get() !== review) return false
        // Consume before suspension so a queued double-confirm cannot replace the first outcome.
        // Failed publication retains its exact witness in the owner's retry operation.
        if (!reviewedFilter.compareAndSet(review, null)) return false
        // The Android host can show progress only after authorization, before a slow publication.
        onAdmitted()
        val committed = library.commitDraft(filterId, review)
        if (!committed) {
            lastFilterSourceFailure.set(
                (library.state.value as? FilterLibraryOwner.State.Failed)
                    ?.failure
                    ?.let { it as? FilterLibraryOwner.Failure.Source }
                    ?.let { FilterSourceFailureInfo(it.sourceName, it.failure, it.sourceId) },
            )
        }
        return committed
    }

    /** Consume the custom-blocklist fetch failure of the most recent [commitFilterReview]. */
    fun consumeFilterSourceFailure(): FilterSourceFailureInfo? =
        lastFilterSourceFailure.getAndSet(null)

    /** Native preparation copy does not consume the caller's typed failure result. */
    internal fun filterSourceFailureForPreparation(): FilterSourceFailureInfo? = lastFilterSourceFailure.get()

    suspend fun createFilter(
        name: String, duplicateFromId: String?, expectedGeneration: Long, present: NativeSecurityPresenter,
    ): Boolean = withFilterEditing(expectedGeneration, present = present) {
        it.createFilter(name, duplicateFromId)
    } ?: false

    suspend fun renameFilter(
        filterId: String, name: String, expectedGeneration: Long, emoji: String? = null,
        present: NativeSecurityPresenter,
    ): Boolean = renameFilter(filterId, name, expectedGeneration, emoji, present, null, null)

    suspend fun renameFilter(
        filterId: String, name: String, expectedGeneration: Long, emoji: String? = null,
        present: NativeSecurityPresenter,
        expectedDraft: com.lavasec.app.foundation.filter.FilterProfileDraft?,
        expectedNewFilter: com.lavasec.app.foundation.filter.FilterProfileDraft?,
        isCurrent: (() -> Boolean)? = null,
    ): Boolean = withFilterEditing(expectedGeneration, present = present) {
        val mutationCurrent = isCurrent?.let { supplied ->
            val authorization = captureMutationAuthorizationFence(expectedGeneration, ProtectedSurface.FILTERS)
                ?: return@withFilterEditing false
            val guard: () -> Boolean = { supplied() && authorization() }
            guard
        }
        if (mutationCurrent?.invoke() == false) false
        else it.renameFilter(filterId, name, emoji, expectedDraft, expectedNewFilter, mutationCurrent).also { renamed -> if (renamed) reviewedFilter.set(null) }
    } ?: false

    suspend fun deleteFilters(
        filterIds: Set<String>, expectedGeneration: Long, fresh: Boolean = false,
        present: NativeSecurityPresenter,
    ): Boolean {
        val requested = filterIds.toSet()
        // Canonical library.form/delete uses ordinary edit authorization; single filter.delete is fresh.
        return withFilterEditing(expectedGeneration, present = present, fresh = fresh) { it.deleteFilters(requested) } ?: false
    }

    suspend fun restoreDefaultFilters(expectedGeneration: Long, present: NativeSecurityPresenter): Boolean =
        withFilterEditing(expectedGeneration, present = present) { it.restoreDefaults() } ?: false

    /**
     * Canonical reviewed-import confirmation persistence. `targetFilterId` is null for
     * add-as-new and the replaced filter id otherwise. The share/import owner has already
     * reconciled and confirmed the content; this commits it through the ordinary
     * filter-editing authorization and the shared generation-fenced library coordinator, so an
     * import is one verified publication (never a partial write). A missing owner or a failed
     * authorization is an honest `null`, mapped by the bridge, never a fabricated success.
     */
    suspend fun commitImportedFilter(
        configuration: com.lavasec.app.foundation.share.ShareableFilterConfiguration,
        targetFilterId: String?,
        expectedGeneration: Long,
        present: NativeSecurityPresenter,
        expectedLibrary: FilterLibraryDraft? = null,
        isCurrent: (() -> Boolean)? = null,
    ): FilterLibraryMutationResult? = withFilterEditing(expectedGeneration, present = present) { owner ->
        if (isCurrent?.invoke() == false) null
        else owner.commitImported(configuration, targetFilterId, expectedLibrary, isCurrent)
    }

    /**
     * The live confirmed filter library of the current credential generation, or `null` when no
     * owner is attached. The reviewed backup-restore apply captures its checkpoint here so the
     * "before" side comes from the same authority the replacement commits to.
     */
    fun currentFilterLibrary(): FilterLibraryDraft? =
        owners.get()?.filterLibrary?.currentLibrary()

    /**
     * Replaces the entire saved library in one generation-fenced commit; this is the
     * backup-restore apply. It mirrors [commitImportedFilter]'s owner fence (the exact owners
     * snapshot is re-checked after the commit so a retired generation cannot report success from
     * a stale owner) but deliberately requires no filter-editing authentication: the restore is
     * authorized by the native backup flow that owns the reviewed plan, and the library content
     * was already validated by `BackupRestorePlan`. A missing owner or a non-committed result is
     * an honest `false`, never a fabricated success.
     */
    suspend fun replaceFilterLibrary(library: FilterLibraryDraft): Boolean =
        replaceFilterLibraryInternal(library, clearEditing = true, expectedLibrary = null)

    /**
     * Restore-path variant: preserve the in-memory drafts and open edit session across the write so
     * a failed publication rollback does not lose the user's unsaved work; the caller ends them via
     * [discardFilterEditingSessions] only after the whole transaction commits.
     *
     * [expectedLibrary] is the exact library the restore review was prepared from. When supplied it
     * is enforced atomically inside the coordinator-backed replacement (compare-and-replace), so a
     * filter mutation committed between the checkpoint and this call is refused rather than
     * overwritten. [isCurrent], when supplied, reaches the coordinator's final write boundary;
     * a completed forward write remains reported committed so the restore publisher can roll it back.
     */
    suspend fun replaceFilterLibraryPreservingEditing(
        library: FilterLibraryDraft,
        expectedLibrary: FilterLibraryDraft? = null,
        isCurrent: (() -> Boolean)? = null,
    ): Boolean = replaceFilterLibraryInternal(library, clearEditing = false, expectedLibrary = expectedLibrary, isCurrent = isCurrent)

    /** Native restore compensation requires the exact owner-local forward receipt and generation. */
    suspend fun rollbackFilterLibraryPreservingEditing(
        library: FilterLibraryDraft,
        expectedLibrary: FilterLibraryDraft?,
    ): Boolean {
        val current = owners.get() ?: return false
        val owner = current.filterLibrary ?: return false
        if (owners.get() !== current) return false
        return owner.rollbackReplacement(library, expectedLibrary)
    }

    /** Native QA owns this visit-bound write; it cannot reuse restore's editor-preserving path. */
    suspend fun replaceFilterLibraryForQa(
        desired: FilterLibraryDraft,
        expectedLibrary: FilterLibraryDraft,
        expectedGraph: Long,
        isCurrent: () -> Boolean,
    ): Boolean {
        if (!BuildConfig.DEBUG) return false
        val current = owners.get() ?: return false
        val library = current.filterLibrary ?: return false
        if (current.graphGeneration != expectedGraph) return false
        val admitted = { owners.get() === current && current.graphGeneration == expectedGraph && isCurrent() }
        if (!admitted()) return false
        return library.replaceReviewedForQa(desired, expectedLibrary, admitted) && admitted()
    }

    fun currentQaLogging(): LoggingSettings? = owners.get()?.privacy?.state?.value?.logging

    /** DNS, active sources and exact probes share the existing one-artifact generation CAS. */
    suspend fun applyQaCompositeConfiguration(
        desired: FilterLibraryDraft,
        tiers: List<ResolverTierChoice>,
        expectedLibrary: FilterLibraryDraft,
        expectedGraph: Long,
        isCurrent: () -> Boolean,
    ): com.lavasec.app.product.QaCompositeConfigurationResult {
        if (!BuildConfig.DEBUG) return com.lavasec.app.product.QaCompositeConfigurationResult.Unavailable
        val current = owners.get() ?: return com.lavasec.app.product.QaCompositeConfigurationResult.Unavailable
        val library = current.filterLibrary ?: return com.lavasec.app.product.QaCompositeConfigurationResult.Unavailable
        val resolver = current.resolverPlanSwitch ?: return com.lavasec.app.product.QaCompositeConfigurationResult.Unavailable
        val caller = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            ?: return com.lavasec.app.product.QaCompositeConfigurationResult.Unavailable
        val admitted = { caller.isActive && owners.get() === current && current.graphGeneration == expectedGraph && customDraftToken.value == null && isCurrent() }
        if (!admitted() || library.currentLibrary() != expectedLibrary) return com.lavasec.app.product.QaCompositeConfigurationResult.Unavailable
        val selection = expectedLibrary.resolverTarget.toSelection()
        val context = ResolverDnsContext.encode(selection)
        return resolver.applyReviewedDnsForQa(
            SaveResolverTiers(tiers, context),
            ResolverDnsEditContext(selection, current.featureLimits.value, resolver.chainedDnsEdit.value, context),
            admitted,
        ) { accepted ->
            library.replaceReviewedCompositeForQa(desired.withResolverPlan(accepted.target), expectedLibrary, accepted, admitted)
        }
    }

    /** A separate privacy commit follows one configuration CAS; failures remain truthful partial resets. */
    suspend fun resetQaState(
        expectedLibrary: FilterLibraryDraft,
        expectedLogging: LoggingSettings,
        expectedGraph: Long,
        isCurrent: () -> Boolean,
    ): com.lavasec.app.product.QaCompositeConfigurationResult {
        val current = owners.get() ?: return com.lavasec.app.product.QaCompositeConfigurationResult.Unavailable
        val caller = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            ?: return com.lavasec.app.product.QaCompositeConfigurationResult.Unavailable
        val admitted = { caller.isActive && owners.get() === current && current.graphGeneration == expectedGraph && isCurrent() }
        val selection = expectedLibrary.resolverTarget.toSelection()
        // Frozen reset changes the primary only; retain the selected fallback and its consent.
        val tiers = listOf(ResolverTierChoice("google-public-dns", "IP")) + selection.tiers.drop(1).map { tier ->
            val entry = tier.entry
            ResolverTierChoice(entry.providerId.rawId, entry.transport.token, tier.retainedName ?: entry.customName ?: "",
                entry.customDohUrl ?: entry.customDotEndpoint ?: entry.customDoqEndpoint ?: entry.customPlainEndpoint ?: "",
                entry.customSecondaryEndpoint ?: "", tier.isEnabled)
        }
        val configuration = applyQaCompositeConfiguration(expectedLibrary.withQaProbeSet(null), tiers, expectedLibrary, expectedGraph, admitted)
        if (configuration !is com.lavasec.app.product.QaCompositeConfigurationResult.Applied) return configuration
        val privacy = current.privacy ?: return com.lavasec.app.product.QaCompositeConfigurationResult.CommittedReconciliationRequired
        val result = try {
            withContext(NonCancellable) { privacy.resetForQa(expectedLogging, configuration.generation, admitted) }
        } catch (_: Exception) { return com.lavasec.app.product.QaCompositeConfigurationResult.CommittedReconciliationRequired }
        if (result != com.lavasec.app.product.privacy.PrivacyMutationResult.Completed)
            return com.lavasec.app.product.QaCompositeConfigurationResult.CommittedReconciliationRequired
        if (owners.get() === current) {
            runCatching { current.activity?.refreshSummary(); current.activity?.refreshHistory(); current.activity?.reloadNetworkAfterClear() }
        }
        return configuration
    }

    private suspend fun replaceFilterLibraryInternal(
        library: FilterLibraryDraft,
        clearEditing: Boolean,
        expectedLibrary: FilterLibraryDraft?,
        isCurrent: (() -> Boolean)? = null,
    ): Boolean {
        val current = owners.get() ?: return false
        val libraryOwner = current.filterLibrary ?: return false
        if (owners.get() !== current || isCurrent?.invoke() == false) return false
        val canPublish = isCurrent?.let { witness -> { owners.get() === current && witness() } }
        if (!libraryOwner.replaceAll(library, clearEditing, expectedLibrary, canPublish)) return false
        // With a final publication witness the committed result is authoritative. The restore
        // publisher must observe a committed forward write even if the graph retired just after
        // it, so its generation check reports/compensates that write instead of claiming no change.
        return isCurrent != null || owners.get() === current
    }

    /** Ends every in-memory editing session after a committed restore publication. */
    fun discardFilterEditingSessions() {
        owners.get()?.filterLibrary?.discardEditingSessions()
    }

    // ---- Canonical whole-library edit session (library.edit/cancel/toggleDeletion/form) ----

    /** The live whole-library edit session of the exact generation, for the bridge snapshot. */
    fun libraryEditSessionForSnapshot(expectedGeneration: Long?): FilterLibraryEditSession? {
        val current = owners.get() ?: return null
        if (current.graphGeneration != expectedGeneration) return null
        return current.filterLibrary?.editSession?.value
    }

    /** Canonical `library.edit`: non-fresh filter-editing authorization, then begin the session. */
    suspend fun beginLibraryEditing(
        expectedGeneration: Long,
        present: NativeSecurityPresenter,
    ): FilterLibraryEditOutcome? = withLibraryEditing(expectedGeneration, present) {
        it.beginLibraryEditing()
    }

    /**
     * Canonical `library.cancel`: local session teardown. A revoked editing grant must not trap
     * the user behind another auth prompt, so this never authenticates.
     */
    suspend fun cancelLibraryEditing(expectedGeneration: Long): Boolean {
        val current = owners.get() ?: return false
        if (current.graphGeneration != expectedGeneration) return false
        val library = current.filterLibrary ?: return false
        if (owners.get() !== current) return false
        return library.cancelLibraryEditing() is FilterLibraryEditOutcome.Completed
    }

    suspend fun toggleLibraryDeletion(
        filterId: String,
        expectedGeneration: Long,
        present: NativeSecurityPresenter,
    ): FilterLibraryEditOutcome? = withLibraryEditing(expectedGeneration, present) {
        it.toggleStagedDeletion(filterId)
    }

    suspend fun stageLibraryCreate(
        name: String?,
        duplicateFromId: String?,
        expectedGeneration: Long,
        present: NativeSecurityPresenter,
    ): FilterLibraryEditOutcome? = withLibraryEditing(expectedGeneration, present) {
        it.stageLibraryCreate(name, duplicateFromId)
    }

    /** Preserve the existing presenter-last entry point for native call sites. */
    suspend fun beginCreatingFilter(
        name: String?, duplicateFromId: String?, expectedGeneration: Long, present: NativeSecurityPresenter,
    ): FilterLibraryEditOutcome? = beginCreatingFilter(name, duplicateFromId, expectedGeneration, present, null)

    /** Create belongs to the detail editor, independently of the library chooser session. */
    suspend fun beginCreatingFilter(
        name: String?, duplicateFromId: String?, expectedGeneration: Long, present: NativeSecurityPresenter,
        expectedSession: com.lavasec.app.foundation.filter.FilterLibraryEditSession?,
        isCurrent: (() -> Boolean)? = null,
        onDetailPresented: ((FilterDetailRetirementWitness) -> Unit)? = null,
    ): FilterLibraryEditOutcome? {
        val current = owners.get() ?: return null
        val owner = current.filterLibrary ?: return null
        val presentation = filterPresentationEpoch.get()
        if (current.graphGeneration != expectedGeneration) return null
        if (!securityAuthorize(expectedGeneration, ProtectedSurface.FILTERS, present = present)) return null
        val mutationCurrent = isCurrent?.let { supplied ->
            val authorization = captureMutationAuthorizationFence(expectedGeneration, ProtectedSurface.FILTERS) ?: return null
            val guard: () -> Boolean = { supplied() && authorization() }
            guard
        }
        val creationCurrent = {
            owners.get() === current && filterPresentationEpoch.get() == presentation && mutationCurrent?.invoke() != false
        }
        if (!creationCurrent()) return null
        val outcome = owner.beginCreatingFilter(name, duplicateFromId, expectedSession, creationCurrent)
        if (outcome !is FilterLibraryEditOutcome.Created) return outcome
        val identity = owner.newFilter.value ?: return null
        val creationLease = owner.draftLease(outcome.filterId)
        val creationPresentation = filterPresentationEpoch.incrementAndGet()
        val detailWitness = FilterDetailRetirementWitness(current, owner, outcome.filterId,
            creationPresentation, true, creationLease)
        reviewedFilter.set(null)
        selectedFilterId.value = outcome.filterId
        var delivered = false
        try {
            // Bind the native host before waiting for projection: a late returned ID must
            // never capture a successor's same-looking detail presentation.
            onDetailPresented?.invoke(detailWitness)
            // The shared route immediately reads the next snapshot to determine whether this
            // ID exists. Wait for the real owner projection, including cancellation/retirement,
            // instead of reporting an ID before its detail is observable.
            combine(state, owner.newFilter, selectedFilterId) { rendered, pending, selected ->
                owners.get() !== current || mutationCurrent?.invoke() == false || pending !== identity || selected != outcome.filterId ||
                    (rendered.graphGeneration == expectedGeneration && rendered.newFilter === identity &&
                        rendered.filterDetail?.filterId == outcome.filterId)
            }.first { it }
            if (owners.get() !== current || mutationCurrent?.invoke() == false || owner.newFilter.value !== identity ||
                selectedFilterId.value != outcome.filterId || filterPresentationEpoch.get() != creationPresentation) return null
            delivered = true
            return outcome
        } finally {
            if (!delivered) withContext(NonCancellable) {
                // Exact native cleanup survives caller/Activity cancellation and refuses a
                // reopened successor even when it still uses this same creation id/lease.
                retireFilterDetail(detailWitness).await()
            }
        }
    }

    suspend fun stageLibraryRename(
        filterId: String, name: String, emoji: String?, expectedGeneration: Long, present: NativeSecurityPresenter,
    ): FilterLibraryEditOutcome? = stageLibraryRename(filterId, name, emoji, expectedGeneration, present, null)

    suspend fun commitLibraryEdit(
        expectedIds: Set<String>?, expectedGeneration: Long, present: NativeSecurityPresenter,
    ): FilterLibraryEditOutcome? = commitLibraryEdit(expectedIds, expectedGeneration, present, null)

    suspend fun stageLibraryRename(
        filterId: String,
        name: String,
        emoji: String?,
        expectedGeneration: Long,
        present: NativeSecurityPresenter,
        expectedSession: com.lavasec.app.foundation.filter.FilterLibraryEditSession?,
        isCurrent: (() -> Boolean)? = null,
    ): FilterLibraryEditOutcome? = withLibraryEditing(expectedGeneration, present) {
        val mutationCurrent = isCurrent?.let { supplied ->
            val authorization = captureMutationAuthorizationFence(expectedGeneration, ProtectedSurface.FILTERS)
                ?: return@withLibraryEditing null
            val guard: () -> Boolean = { supplied() && authorization() }
            guard
        }
        if (mutationCurrent?.invoke() == false) null
        else it.stageLibraryRename(filterId, name, emoji, expectedSession, mutationCurrent)
    }

    suspend fun commitLibraryEdit(
        expectedIds: Set<String>?,
        expectedGeneration: Long,
        present: NativeSecurityPresenter,
        expectedSession: com.lavasec.app.foundation.filter.FilterLibraryEditSession?,
        isCurrent: (() -> Boolean)? = null,
    ): FilterLibraryEditOutcome? = withLibraryEditing(expectedGeneration, present) {
        val mutationCurrent = isCurrent?.let { supplied ->
            val authorization = captureMutationAuthorizationFence(expectedGeneration, ProtectedSurface.FILTERS)
                ?: return@withLibraryEditing null
            val guard: () -> Boolean = { supplied() && authorization() }
            guard
        }
        if (mutationCurrent?.invoke() == false) null
        else it.commitLibraryEdit(expectedIds, expectedSession, mutationCurrent)
    }

    /**
     * The generation + filter-editing fence shared by every `library.*` command except cancel.
     * Canonical authenticates once on `library.edit` and reuses the grant for the rest of the
     * session; the owner fences its own close, so only this generation's session can mutate.
     */
    private suspend fun <T> withLibraryEditing(
        expectedGeneration: Long,
        present: NativeSecurityPresenter,
        action: suspend (FilterLibraryOwner) -> T,
    ): T? {
        val current = owners.get() ?: return null
        val library = current.filterLibrary ?: return null
        if (current.graphGeneration != expectedGeneration) return null
        if (!securityAuthorize(expectedGeneration, ProtectedSurface.FILTERS, present = present)) return null
        if (owners.get() !== current) return null
        return action(library)
    }

    /**
     * Re-admits the last failed library operation. The owner re-derives the desired library
     * from the CURRENT state, so a retry cannot duplicate a write or leak a stale generation.
     */
    suspend fun retryFilterPreparation(
        expectedGeneration: Long, onAdmitted: () -> Unit = {}, present: NativeSecurityPresenter,
    ): Boolean {
        lastFilterSourceFailure.set(null)
        val current = owners.get() ?: return false
        if (current.graphGeneration != expectedGeneration) return false
        val library = current.filterLibrary ?: return false
        val failure = library.state.value as? FilterLibraryOwner.State.Failed ?: return false
        val operation = failure.operation as? FilterLibraryOperation.CommitDraft ?: return false
        if (operation.review == null || operation.filterId != selectedFilterId.value) return false
        if (!securityAuthorize(expectedGeneration, ProtectedSurface.FILTERS, present = present)) return false
        if (owners.get() !== current) return false
        onAdmitted()
        val committed = library.retryReviewed(failure)
        if (!committed) lastFilterSourceFailure.set(
            (library.state.value as? FilterLibraryOwner.State.Failed)?.failure
                ?.let { it as? FilterLibraryOwner.Failure.Source }
                ?.let { FilterSourceFailureInfo(it.sourceName, it.failure, it.sourceId) })
        return committed
    }

    internal fun canRetryFilterPreparation(expectedGeneration: Long): Boolean {
        val current = owners.get()?.takeIf { it.graphGeneration == expectedGeneration } ?: return false
        val failure = current.filterLibrary?.state?.value as? FilterLibraryOwner.State.Failed ?: return false
        val operation = failure.operation as? FilterLibraryOperation.CommitDraft ?: return false
        return operation.review != null && operation.filterId == selectedFilterId.value &&
            (failure.failure is FilterLibraryOwner.Failure.Commit || failure.failure is FilterLibraryOwner.Failure.Source)
    }

    /** Marks the preparation Success as consumed so the surface settles back to Idle. */
    suspend fun consumeFilterPreparationSuccess() {
        preparationSuccessConsumed.set(true)
    }

    /**
     * Requests one resolver-plan switch through the exact current owner generation. The owner
     * fences its own close exactly like [requestFilterProfileApply] (a retired generation
     * cannot reach its coordinator): the owners reference is captured at call time and only
     * that generation's switch capability runs. The resolver switch shares the coordinator
     * with profile switches, so the same reconstruction rotation recovers its wedge.
     */
    suspend fun requestResolverPlanSwitch(plan: MilestoneResolverPlan): Boolean {
        val current = owners.get() ?: return false
        return current.resolverPlanSwitch?.apply(plan) ?: false
    }

    /**
     * Canonical `dns.tiers`: evaluate the complete requested ladder against the CONFIRMED
     * selection, the live limits and the chained edit gate through [ResolverSelectionPolicy],
     * and commit the accepted ladder through the exact generation's resolver switch. A
     * refusal never commits.
     */
    suspend fun applyDnsTiers(
        tiers: List<ResolverTierChoice>,
        context: String,
        expectedGeneration: Long,
    ): ResolverDnsEditOutcome {
        val current = owners.get() ?: return ResolverDnsEditOutcome.Unavailable
        if (current.graphGeneration != expectedGeneration) return ResolverDnsEditOutcome.Unavailable
        val selection = current.resolverPlan.value?.toSelection()
            ?: return ResolverDnsEditOutcome.Unavailable
        val owner = current.resolverPlanSwitch ?: return ResolverDnsEditOutcome.Unavailable
        return owner.applyDnsEdit(
            request = SaveResolverTiers(tiers, expectedEditorContext = context),
            context = ResolverDnsEditContext(
                current = selection,
                limits = current.featureLimits.value,
                chained = owner.chainedDnsEdit.value,
                editorContext = ResolverDnsContext.encode(selection),
            ),
        )
    }

    /**
     * Canonical `dns.toggle`: enable/disable one projected ladder row. The policy retains the
     * row's identity and position while disabled and refuses the last active row, so the shared
     * ordered-tier editor round-trips without collapsing the ladder.
     */
    suspend fun applyDnsTierEnabled(
        index: Int,
        enabled: Boolean,
        context: String,
        expectedGeneration: Long,
    ): ResolverDnsEditOutcome {
        val current = owners.get() ?: return ResolverDnsEditOutcome.Unavailable
        if (current.graphGeneration != expectedGeneration) return ResolverDnsEditOutcome.Unavailable
        val selection = current.resolverPlan.value?.toSelection()
            ?: return ResolverDnsEditOutcome.Unavailable
        val owner = current.resolverPlanSwitch ?: return ResolverDnsEditOutcome.Unavailable
        return owner.applyDnsEdit(
            request = SetResolverTierEnabled(index, enabled, expectedEditorContext = context),
            context = ResolverDnsEditContext(
                current = selection,
                limits = current.featureLimits.value,
                chained = owner.chainedDnsEdit.value,
                editorContext = ResolverDnsContext.encode(selection),
            ),
        )
    }

    /**
     * Canonical fallback toggle. Reachable on the Android wire through `vpn.toggle fallback`;
     * it shares the same policy/commit path as the encrypted-fallback tier and is unit-tested.
     */
    suspend fun applyDnsFallbackEnabled(
        enabled: Boolean,
        context: String,
        expectedGeneration: Long,
    ): ResolverDnsEditOutcome {
        val current = owners.get() ?: return ResolverDnsEditOutcome.Unavailable
        if (current.graphGeneration != expectedGeneration) return ResolverDnsEditOutcome.Unavailable
        val selection = current.resolverPlan.value?.toSelection()
            ?: return ResolverDnsEditOutcome.Unavailable
        val owner = current.resolverPlanSwitch ?: return ResolverDnsEditOutcome.Unavailable
        return owner.applyDnsEdit(
            request = SetResolverFallbackEnabled(enabled, expectedEditorContext = context),
            context = ResolverDnsEditContext(
                current = selection,
                limits = current.featureLimits.value,
                chained = owner.chainedDnsEdit.value,
                editorContext = ResolverDnsContext.encode(selection),
            ),
        )
    }

    /**
     * Canonical `dns.customDraft`: open a custom-resolver editor session. Gated on the live
     * Plus limit before anything is created; a free user gets the policy's
     * [ResolverEditRejection.CustomRequiresPlus] refusal, never an editor that cannot save.
     */
    fun openCustomDnsDraft(choice: ResolverTierChoice?, expectedGeneration: Long? = null): ResolverDnsEditOutcome {
        val current = owners.get() ?: return ResolverDnsEditOutcome.Unavailable
        if (expectedGeneration != null && current.graphGeneration != expectedGeneration) return ResolverDnsEditOutcome.Unavailable
        if (!current.featureLimits.value.allowsCustomDNS) {
            return ResolverDnsEditOutcome.Refused(ResolverEditRejection.CustomRequiresPlus)
        }
        return synchronized(customDraftLock) {
            if (owners.get() !== current) return@synchronized ResolverDnsEditOutcome.Unavailable
            hydrateCustomDns(current, current.resolverPlan.value)
            val initial = choice?.let {
                CustomResolverDraft(it.name, it.primary, it.secondary)
            } ?: customSavedDraft.value
            val token = java.util.UUID.randomUUID().toString()
            customDraftSession.value = CustomResolverDraftSession.from(initial)
            customDraftToken.value = token
            ResolverDnsEditOutcome.DraftOpened(token)
        }
    }

    private fun persistedCustomDns(plan: FilterResolverPlan?): CustomResolverDraft {
        if (plan == null) return CustomResolverDraft()
        val entry = plan.toSelection().tiers.firstOrNull { it.entry.providerId == ResolverProviderId.CUSTOM }?.entry
            ?: return CustomResolverDraft()
        val primary = entry.customDohUrl ?: entry.customDotEndpoint ?: entry.customDoqEndpoint ?: entry.customPlainEndpoint
            ?: return CustomResolverDraft()
        return CustomResolverDraft(name = entry.customName ?: "", primaryAddress = primary,
            secondaryAddress = entry.customSecondaryEndpoint ?: "")
    }

    private fun hydrateCustomDns(current: CredentialProductOwners, plan: FilterResolverPlan?) = synchronized(customDraftLock) {
        if (owners.get() !== current) return@synchronized
        val sameSource = customSavedOwner === current && customSavedPlan == plan
        val persisted = persistedCustomDns(plan)
        val existing = customSavedDraft.value
        val persistedTransport = CustomResolverValidator.parse(persisted.trimmedPrimary)?.transport
        val normalizedLocalPrimary = when {
            persistedTransport == com.lavasec.app.foundation.resolver.ParsedCustomResolverTransport.DOH -> CustomResolverValidator.normalizedDohUrl(existing.trimmedPrimary)
            persistedTransport == com.lavasec.app.foundation.resolver.ParsedCustomResolverTransport.DOT -> CustomResolverValidator.normalizedDotEndpoint(existing.trimmedPrimary)
            persistedTransport == com.lavasec.app.foundation.resolver.ParsedCustomResolverTransport.DOQ -> CustomResolverValidator.normalizedDoqEndpoint(existing.trimmedPrimary)
            persistedTransport == com.lavasec.app.foundation.resolver.ParsedCustomResolverTransport.PLAIN -> CustomResolverValidator.normalizedPlainEndpoint(existing.trimmedPrimary)
            else -> null
        }
        val normalizedLocalSecondary = when {
            existing.trimmedSecondary.isEmpty() -> ""
            persistedTransport == com.lavasec.app.foundation.resolver.ParsedCustomResolverTransport.DOH ->
                CustomResolverValidator.normalizedDohUrl(existing.trimmedSecondary) ?: existing.trimmedSecondary
            persistedTransport == com.lavasec.app.foundation.resolver.ParsedCustomResolverTransport.DOT ->
                CustomResolverValidator.normalizedDotEndpoint(existing.trimmedSecondary) ?: existing.trimmedSecondary
            persistedTransport == com.lavasec.app.foundation.resolver.ParsedCustomResolverTransport.DOQ ->
                CustomResolverValidator.normalizedDoqEndpoint(existing.trimmedSecondary) ?: existing.trimmedSecondary
            persistedTransport == com.lavasec.app.foundation.resolver.ParsedCustomResolverTransport.PLAIN ->
                CustomResolverValidator.normalizedPlainEndpoint(existing.trimmedSecondary) ?: existing.trimmedSecondary
            else -> existing.trimmedSecondary
        }
        val matchingLocalDraft = customSavedOwner === current && persisted.trimmedPrimary.isNotEmpty() &&
            normalizedLocalPrimary == persisted.trimmedPrimary &&
            normalizedLocalSecondary == persisted.trimmedSecondary &&
            existing.normalizedName == persisted.normalizedName
        if (sameSource && (matchingLocalDraft || persisted.isCleared && existing.isCleared)) return@synchronized
        customSavedDraft.value = if (matchingLocalDraft) existing else persisted
        customSavedOwner = current
        customSavedPlan = plan
        // A different graph or confirmed endpoint cannot retain an editor opened over old facts.
        if (!sameSource) {
            customDraftSession.value = null
            customDraftToken.value = null
        }
    }

    private fun resetCustomDns(expectedOwner: CredentialProductOwners? = null) = synchronized(customDraftLock) {
        if (expectedOwner != null && customSavedOwner !== expectedOwner) return@synchronized
        customSavedOwner = null
        customSavedPlan = null
        customSavedDraft.value = CustomResolverDraft()
        customDraftSession.value = null
        customDraftToken.value = null
    }

    /** Validate a token-owned picker draft; the parent DNS editor alone commits its ladder. */
    suspend fun stageCustomDns(
        id: String,
        context: String,
        name: String,
        primary: String,
        secondary: String,
        expectedGeneration: Long,
    ): ResolverDnsEditOutcome {
        val current = owners.get() ?: return ResolverDnsEditOutcome.Unavailable
        if (current.graphGeneration != expectedGeneration) return ResolverDnsEditOutcome.Unavailable
        val selection = current.resolverPlan.value?.toSelection() ?: return ResolverDnsEditOutcome.Unavailable
        val session = synchronized(customDraftLock) {
            if (id != customDraftToken.value || customSavedOwner !== current ||
                customSavedPlan?.toSelection() != selection
            ) return ResolverDnsEditOutcome.Refused(ResolverEditRejection.StaleEditor)
            customDraftSession.value ?: return ResolverDnsEditOutcome.Refused(ResolverEditRejection.StaleEditor)
        }
        val owner = current.resolverPlanSwitch ?: return ResolverDnsEditOutcome.Unavailable
        val draft = CustomResolverDraft(name, primary, secondary)
        val transport = CustomResolverValidator.parse(draft.trimmedPrimary)?.transport?.toCatalogTransport()
            ?: CatalogResolverTransport.DOH
        val outcome = owner.stageCustomDns(
            choice = ResolverTierChoice(ResolverProviderId.CUSTOM.rawId, transport.token,
                draft.name, draft.primaryAddress, draft.secondaryAddress),
            expectedEditorContext = context,
            context = ResolverDnsEditContext(selection, current.featureLimits.value, owner.chainedDnsEdit.value,
                ResolverDnsContext.encodeCustom(selection, customSavedDraft.value)),
        )
        if (outcome !is ResolverDnsEditOutcome.DraftStaged) return outcome
        // Admission can suspend while reloading chained authority. A dismissed editor, source
        // change or retired graph cannot return a completed choice into a later picker.
        return synchronized(customDraftLock) {
            if (owners.get() !== current || customDraftSession.value !== session || id != customDraftToken.value ||
                current.resolverPlan.value?.toSelection() != selection
            ) ResolverDnsEditOutcome.Refused(ResolverEditRejection.StaleEditor)
            else if (!current.featureLimits.value.allowsCustomDNS) ResolverDnsEditOutcome.Refused(ResolverEditRejection.CustomRequiresPlus)
            else outcome
        }
    }

    /**
     * Canonical `dns.custom`: commit the custom-resolver draft. The draft is normalized
     * through [CustomResolverDraftSession] and then committed through the same
     * admission-locked DNS edit path as every other tier, so an invalid address, a Plus
     * gate and a runtime capability refusal each surface their own typed outcome. A valid
     * custom endpoint commits through the value-carrying resolver plan. Editing an existing
     * custom row retains the rest of its ordered ladder; an all-empty draft is the canonical
     * clear and an honest no-op.
     */
    suspend fun saveCustomDns(
        context: String,
        name: String,
        primary: String,
        secondary: String,
        expectedGeneration: Long,
    ): ResolverDnsEditOutcome {
        val current = owners.get() ?: return ResolverDnsEditOutcome.Unavailable
        if (current.graphGeneration != expectedGeneration) return ResolverDnsEditOutcome.Unavailable
        if (!current.featureLimits.value.allowsCustomDNS) {
            return ResolverDnsEditOutcome.Refused(ResolverEditRejection.CustomRequiresPlus)
        }
        val session = customDraftSession.value
            ?: return ResolverDnsEditOutcome.Refused(ResolverEditRejection.StaleEditor)
        val edited = session.editName(name).editPrimary(primary).editSecondary(secondary)
        if (edited.isCleared) {
            customDraftSession.value = CustomResolverDraftSession.empty
            customDraftToken.value = null
            customSavedDraft.value = persistedCustomDns(current.resolverPlan.value)
            return ResolverDnsEditOutcome.Applied()
        }
        val selection = current.resolverPlan.value?.toSelection()
            ?: return ResolverDnsEditOutcome.Unavailable
        val owner = current.resolverPlanSwitch ?: return ResolverDnsEditOutcome.Unavailable
        // The editor's saved values identify the row being edited, even when it is disabled or
        // follows another custom resolver. Never substitute the effective primary for that row.
        val savedPrimary = CustomResolverValidator.parse(session.saved.trimmedPrimary)
        val savedSecondary = session.saved.trimmedSecondary.takeIf(String::isNotEmpty)
            ?.let(CustomResolverValidator::parse)
        val editingIndices = selection.tiers.indices.filter { index ->
            val entry = selection.tiers[index].entry
            val primaryValue = entry.customDohUrl ?: entry.customDotEndpoint ?: entry.customDoqEndpoint ?: entry.customPlainEndpoint
            entry.providerId == ResolverProviderId.CUSTOM && savedPrimary != null &&
                entry.transport == savedPrimary.transport.toCatalogTransport() &&
                primaryValue?.let(CustomResolverValidator::parse)?.normalized == savedPrimary.normalized &&
                (entry.customSecondaryEndpoint?.let(CustomResolverValidator::parse)?.normalized ?: "") ==
                    (savedSecondary?.normalized ?: "")
        }
        val hasSavedCustom = selection.tiers.any { it.entry.providerId == ResolverProviderId.CUSTOM }
        if (hasSavedCustom && (editingIndices.size != 1 ||
                session.saved.trimmedSecondary.isNotEmpty() && savedSecondary == null)
        ) {
            return ResolverDnsEditOutcome.Refused(ResolverEditRejection.StaleEditor)
        }
        val editingIndex = editingIndices.singleOrNull()
        // The existing cached source plan is the draft's witness. A source change can precede
        // its flow collector, so check it here as well as the admission-locked wire context.
        synchronized(customDraftLock) {
            if (owners.get() !== current || customDraftSession.value !== session ||
                customSavedOwner !== current || customSavedPlan?.toSelection() != selection
            ) return ResolverDnsEditOutcome.Refused(ResolverEditRejection.StaleEditor)
            customDraftSession.value = edited
        }
        val transport = CustomResolverValidator.parse(edited.draft.trimmedPrimary)
            ?.transport
            ?.toCatalogTransport()
            ?: CatalogResolverTransport.DOH
        val tier = ResolverTierChoice(
            providerId = ResolverProviderId.CUSTOM.rawId,
            transportToken = transport.token,
            name = edited.draft.name,
            primary = edited.draft.primaryAddress,
            secondary = edited.draft.secondaryAddress,
            isEnabled = editingIndex?.let { selection.tiers[it].isEnabled } ?: true,
        )
        val tiers = if (editingIndex == null) listOf(tier) else selection.tiers.mapIndexed { index, retained ->
            if (index == editingIndex) tier else ResolverTierChoice(
                providerId = retained.entry.providerId.rawId,
                transportToken = retained.entry.transport.token,
                name = retained.retainedName ?: retained.entry.customName ?: "",
                primary = retained.entry.customDohUrl ?: retained.entry.customDotEndpoint ?: retained.entry.customDoqEndpoint ?: retained.entry.customPlainEndpoint ?: "",
                secondary = retained.entry.customSecondaryEndpoint ?: "",
                isEnabled = retained.isEnabled,
            )
        }
        // Commit the complete retained ladder through the common admission lock; only the
        // identified custom row changes, and every other row keeps its values and position.
        val outcome = owner.applyDnsEdit(
            request = SaveResolverTiers(tiers, expectedEditorContext = context),
            context = ResolverDnsEditContext(
                current = selection,
                limits = current.featureLimits.value,
                chained = owner.chainedDnsEdit.value,
                editorContext = ResolverDnsContext.encodeCustom(selection, customSavedDraft.value),
            ),
        )
        // Retain the committed draft as the saved entry and close the completed session, so the
        // snapshot exposes `dns.custom` and reopening the editor starts from the live endpoint
        // rather than blank fields. Only a commit that actually landed updates custody; a
        // refusal/failure keeps the session live for a retry.
        if (outcome is ResolverDnsEditOutcome.Applied) {
            synchronized(customDraftLock) {
                if (owners.get() === current && customDraftSession.value === edited) {
                    val entry = outcome.commit?.selection?.tiers?.getOrNull(editingIndex ?: 0)?.entry
                    customSavedDraft.value = edited.draft.copy(
                        name = entry?.customName ?: edited.draft.normalizedName ?: "",
                        primaryAddress = entry?.customDohUrl ?: entry?.customDotEndpoint ?: entry?.customDoqEndpoint ?: entry?.customPlainEndpoint
                            ?: edited.draft.trimmedPrimary,
                        secondaryAddress = entry?.customSecondaryEndpoint ?: edited.draft.trimmedSecondary,
                    )
                    customDraftSession.value = null
                    customDraftToken.value = null
                }
            }
        }
        return outcome
    }

    /**
     * Canonical `customEntry.dismiss`: close the custom editor when the token matches. A
     * mismatched id is an honest no-op (the snapshot keeps the live token).
     */
    fun dismissCustomEntry(id: String, expectedGeneration: Long? = null): Boolean {
        return synchronized(customDraftLock) {
            // The original saved owner remains the token's generation witness while its
            // current graph detaches; hydration replaces it only as it clears this token.
            if (expectedGeneration != null && customSavedOwner?.graphGeneration != expectedGeneration) return@synchronized false
            val token = customDraftToken.value ?: return@synchronized false
            if (token != id) return@synchronized false
            customDraftSession.value = null
            customDraftToken.value = null
            true
        }
    }

    /** Restore invokes this last: a false result guarantees no retained-data deletion occurred. */
    suspend fun setLoggingForRestore(
        settings: LoggingSettings,
        expectedGeneration: Long? = owners.get()?.graphGeneration,
        stillAuthorized: () -> Boolean = { true },
    ): Boolean {
        val current = owners.get() ?: return false
        if (current.graphGeneration != expectedGeneration) return false
        val privacy = current.privacy ?: return false
        val result = privacy.setLoggingForRestore(settings) {
            owners.get() === current && current.graphGeneration == expectedGeneration && stillAuthorized()
        }
        if (!result.isDurableCommit()) return false
        if (!settings.keepNetworkActivity) runCatching { current.activity?.reloadNetworkAfterClear() }
        if (owners.get() === current && result is
            com.lavasec.app.product.privacy.PrivacyMutationResult.CommittedReconciliationRequired &&
            lastPrivacyReconciliationGeneration.getAndSet(current.graphGeneration) != current.graphGeneration
        ) {
            runCatching { rotateCredentialGraph(current.graphGeneration) }
        }
        return true
    }

    suspend fun setLogging(settings: LoggingSettings): Boolean {
        val current = owners.get() ?: return false
        val privacy = current.privacy ?: return false
        val retainedBefore = privacy.state.value.logging?.keepNetworkActivity
        val admitted = privacy.setLogging(settings)
        // The owner's Boolean is admission only; a durable commit is the load-bearing fact for a
        // caller that publishes it as applied (e.g. the backup restore's preference write). A
        // Failed/Cancelled mutation must report false so the restore rolls back.
        if (!admitted || !privacy.state.value.lastMutation.isDurableCommit()) return false
        if (retainedBefore == true && !settings.keepNetworkActivity) {
            // Turning retention off clears the saved rows (privacy controller parity with
            // iOS setKeepNetworkActivity); drop predecessor rows immediately instead of
            // waiting for the policy-driven reload.
            current.activity?.reloadNetworkAfterClear()
        }
        if (
            owners.get() === current &&
            privacy.state.value.lastMutation is
            com.lavasec.app.product.privacy.PrivacyMutationResult.CommittedReconciliationRequired &&
            lastPrivacyReconciliationGeneration.getAndSet(current.graphGeneration) !=
            current.graphGeneration
        ) {
            rotateCredentialGraph(current.graphGeneration)
        }
        return true
    }

    suspend fun clearLocalActivity(): Boolean =
        clearLocalActivity(countsAndUptime = true, domainHistory = true)

    /**
     * Scoped retained-log clear used by the canonical `logs.clear` command. At least one class
     * must be selected (the owner's [LocalActivityClearSelection] enforces it); the reads are
     * refreshed only after a confirmed durable mutation.
     */
    suspend fun clearLocalActivity(countsAndUptime: Boolean, domainHistory: Boolean): Boolean {
        if (!countsAndUptime && !domainHistory) return false
        val current = owners.get() ?: return false
        val privacy = current.privacy ?: return false
        val admitted = privacy.clear(
            LocalActivityClearSelection(countsAndUptime = countsAndUptime, domainHistory = domainHistory),
        )
        val mutation = if (admitted) privacy.state.value.lastMutation else null
        if (mutation.isDurableCommit()) {
            current.activity?.refreshSummary()
            current.activity?.refreshHistory()
        }
        // Readers must observe committed SQL deletion, but a retained diagnostics store still
        // makes the requested clear incomplete. An explicit retry reattempts that store's clear;
        // rotating the graph would only reload its retained history.
        return mutation == com.lavasec.app.product.privacy.PrivacyMutationResult.Completed
    }

    /**
     * Canonical `logs.clear` "Clear Lava Guard progress": the standalone destructive guard-progress
     * clear, distinct from the retention toggle. Counts, history and Network Activity stay.
     */
    suspend fun clearGuardProgress(): Boolean {
        val current = owners.get() ?: return false
        val privacy = current.privacy ?: return false
        val admitted = privacy.clearGuardProgress()
        return admitted && privacy.state.value.lastMutation.isDurableCommit()
    }

    /** Generation-fenced Network Activity clear; counts/uptime and Domain History stay. */
    suspend fun clearNetworkActivity(): Boolean {
        val current = owners.get() ?: return false
        val privacy = current.privacy ?: return false
        val admitted = privacy.clearNetworkActivity()
        val committed = admitted && privacy.state.value.lastMutation.isDurableCommit()
        if (committed) {
            current.activity?.reloadNetworkAfterClear()
        }
        return committed
    }

    suspend fun refreshNetworkActivity(): Boolean =
        owners.get()?.activity?.refreshNetwork() ?: false

    suspend fun loadMoreNetworkActivity(): Boolean =
        owners.get()?.activity?.loadMoreNetwork() != null

    suspend fun exportLocalActivity(options: LocalActivityExportOptions = LocalActivityExportOptions()): Boolean =
        owners.get()?.privacy?.export(options) ?: false

    fun createExportShareIntent(): Intent? {
        val current = owners.get() ?: return null
        val handle = current.privacy?.state?.value?.exportHandle ?: return null
        return runCatching { current.createExportShareIntent?.invoke(handle) }.getOrNull()
    }

    suspend fun clearDiagnostics(): Boolean = owners.get()?.diagnostics?.clear() ?: false

    internal suspend fun sampleNerdStats(expectedGeneration: Long): com.lavasec.app.diagnostics.LiveNerdStatsSample? {
        val current = owners.get()?.takeIf { it.graphGeneration == expectedGeneration } ?: return null
        val sample = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { current.sampleNerdStats?.invoke() }
        return sample?.takeIf { owners.get() === current && state.value.graphGeneration == expectedGeneration }
    }

    suspend fun refreshDiagnostics(expectedGeneration: Long? = null): Boolean =
        owners.get()?.takeIf { expectedGeneration == null || it.graphGeneration == expectedGeneration }
            ?.diagnostics?.refresh() ?: false

    suspend fun setCustomizationReduceMotion(enabled: Boolean): Boolean =
        owners.get()?.customization?.setReduceMotion(enabled) ?: false

    suspend fun setCustomizationHaptics(enabled: Boolean): Boolean =
        owners.get()?.customization?.setHaptics(enabled) ?: false

    /**
     * Re-reads the OS notification-channel status (foreground refreshes) and
     * retries the ambient protection post: Android silently drops notify()
     * while notifications are blocked, so returning from system settings is
     * the honest moment to re-post the current status.
     */
    suspend fun refreshCustomizationNotifications(): Boolean {
        val current = owners.get() ?: return false
        val refreshed = current.customization?.refreshNotifications() ?: false
        current.protectionAmbient?.rePostCurrent()
        return refreshed
    }

    suspend fun securitySetPasscode(code: String): Boolean =
        owners.get()?.security?.setPasscode(code) ?: false

    suspend fun securityChangePasscode(current: String, new: String): Boolean =
        owners.get()?.security?.changePasscode(current, new) ?: false

    suspend fun securityRemovePasscode(code: String): Boolean =
        owners.get()?.security?.removePasscode(code) ?: false

    suspend fun securitySetPasscodeResult(expectedGeneration: Long, code: String): com.lavasec.app.security.SecurityCredentialMutationResult {
        val current = owners.get()?.takeIf { it.graphGeneration == expectedGeneration }
            ?: return com.lavasec.app.security.SecurityCredentialMutationResult.REFUSED
        val result = current.security?.setPasscodeResult(code)
            ?: return com.lavasec.app.security.SecurityCredentialMutationResult.REFUSED
        return if (owners.get() === current) result else com.lavasec.app.security.SecurityCredentialMutationResult.REFUSED
    }

    suspend fun securityChangePasscodeResult(expectedGeneration: Long, current: String, new: String): com.lavasec.app.security.SecurityCredentialMutationResult {
        val graph = owners.get()?.takeIf { it.graphGeneration == expectedGeneration }
            ?: return com.lavasec.app.security.SecurityCredentialMutationResult.REFUSED
        val result = graph.security?.changePasscodeResult(current, new)
            ?: return com.lavasec.app.security.SecurityCredentialMutationResult.REFUSED
        return if (owners.get() === graph) result else com.lavasec.app.security.SecurityCredentialMutationResult.REFUSED
    }

    suspend fun securityRemovePasscodeResult(expectedGeneration: Long, code: String): com.lavasec.app.security.SecurityCredentialMutationResult {
        val current = owners.get()?.takeIf { it.graphGeneration == expectedGeneration }
            ?: return com.lavasec.app.security.SecurityCredentialMutationResult.REFUSED
        val result = current.security?.removePasscodeResult(code)
            ?: return com.lavasec.app.security.SecurityCredentialMutationResult.REFUSED
        return if (owners.get() === current) result else com.lavasec.app.security.SecurityCredentialMutationResult.REFUSED
    }

    internal fun sharedOnboardingAuthority(): Pair<Long?, OnboardingOwnerState?> {
        val current = owners.get() ?: return null to null
        return current.graphGeneration to current.onboarding.state.value
    }

    internal fun sharedFilterDraft(expectedGeneration: Long, id: String): FilterProfileDraft? {
        val current = owners.get() ?: return null
        if (current.graphGeneration != expectedGeneration) return null
        return current.filterLibrary?.draftFor(id)
    }

    internal fun sharedFilterDraftLease(expectedGeneration: Long, id: String): String? {
        val current = owners.get() ?: return null
        if (current.graphGeneration != expectedGeneration) return null
        return current.filterLibrary?.draftLease(id)
    }

    internal fun sharedNewFilter(expectedGeneration: Long): FilterProfileDraft? {
        val current = owners.get() ?: return null
        if (current.graphGeneration != expectedGeneration) return null
        return current.filterLibrary?.newFilter?.value
    }

    /** A shared RN feedback visit uses the credential graph's existing reviewed report owner. */
    internal fun sharedFeedbackOwner(expectedGeneration: Long): com.lavasec.app.diagnostics.NativeBugReportOwner? {
        val current = owners.get() ?: return null
        return current.reports.takeIf { current.graphGeneration == expectedGeneration }
    }

    /** Read the current authority, not a delayed combined UI emission, for bridge redaction. */
    fun securityStateForSnapshot(expectedGeneration: Long?): SecurityOwnerState? {
        val current = owners.get() ?: return null
        if (current.graphGeneration != expectedGeneration) return null
        // Re-query the mutable platform biometric facts so the snapshot reflects an enrollment or
        // lockout change since construction (no process restart required).
        current.security?.refreshBiometricFacts()
        return current.security?.state?.value
    }

    /** A successful native admission cannot lend its grant to a later authentication turn. */
    internal fun captureMutationAuthorizationFence(
        expectedGeneration: Long,
        surface: ProtectedSurface,
    ): (() -> Boolean)? {
        val current = owners.get() ?: return null
        if (current.graphGeneration != expectedGeneration) return null
        val security = current.security ?: return null
        val witness = com.lavasec.app.security.MutationAuthorizationWitness.capture(security, surface) ?: return null
        return {
            val live = owners.get()
            live != null && live === current && live.graphGeneration == expectedGeneration && live.security === security && witness.isCurrent()
        }
    }

    suspend fun securityAuthorize(
        expectedGeneration: Long,
        surface: ProtectedSurface?,
        fresh: Boolean = false,
        present: NativeSecurityPresenter,
    ): Boolean = securityAuthorizeResult(expectedGeneration, surface, fresh, present) ==
        SecurityAuthorization.AUTHORIZED

    /**
     * The tri-state authentication result. [SecurityAuthorization.CANCELLED] is carried to the
     * bridge so a user-dismissed biometric (or passcode) prompt is suppressed rather than reported
     * as an ordinary authorisation failure.
     */
    suspend fun securityAuthorizeResult(
        expectedGeneration: Long,
        surface: ProtectedSurface?,
        fresh: Boolean = false,
        present: NativeSecurityPresenter,
    ): SecurityAuthorization {
        lastSecurityAuthorization.set(SecurityAuthorization.DENIED)
        val current = owners.get() ?: return SecurityAuthorization.DENIED
        if (current.graphGeneration != expectedGeneration) return SecurityAuthorization.DENIED
        val security = current.security ?: return SecurityAuthorization.DENIED
        val outcome = security.requireAuthenticationOutcome(surface, fresh) { request ->
            present(request, { code -> security.completeAuthentication(request.id, code) },
                { security.cancelAuthentication(request.id, userCancelled = true) })
        }
        if (owners.get() !== current) return SecurityAuthorization.DENIED
        lastSecurityAuthorization.set(outcome)
        return outcome
    }

    /**
     * Consume whether the most recent [securityAuthorizeResult] was a user cancellation.
     *
     * Owner-backed commands (`withFilterEditing` et al.) collapse the tri-state to a Boolean, so a
     * biometric-prompt dismissal would otherwise surface as an ordinary refusal. The bridge reads
     * this immediately after such a command and, when true, reports the canonical `cancelled`
     * result. The bridge serializes owner-backed commands on one admission lane, so a
     * status/confirmation read cannot interleave with the command whose outcome it consumes.
     */
    fun consumeAuthenticationCancellation(): Boolean =
        lastSecurityAuthorization.getAndSet(SecurityAuthorization.DENIED) ==
            SecurityAuthorization.CANCELLED

    fun securityResetAuthenticationTurn(): Long = owners.get()?.security?.resetAuthenticationTurn() ?: 0L

    fun securityEnterForeground(owner: Any, expectedGeneration: Long,
        visit: com.lavasec.app.security.SecurityPresentationVisit?): Boolean {
        val current = owners.get()?.takeIf { it.graphGeneration == expectedGeneration } ?: return false
        current.security?.enterForeground(owner, expectedGeneration, visit) ?: return false
        return owners.get() === current
    }

    fun securityOwnsForeground(owner: Any, expectedGeneration: Long): Boolean {
        val current = owners.get()?.takeIf { it.graphGeneration == expectedGeneration } ?: return false
        return current.security?.ownsForeground(owner, expectedGeneration) == true && owners.get() === current
    }

    fun securitySuspendForeground(owner: Any, expectedGeneration: Long,
        visit: com.lavasec.app.security.SecurityPresentationVisit?): Boolean {
        val current = owners.get()?.takeIf { it.graphGeneration == expectedGeneration } ?: return false
        current.security?.suspendForeground(owner, expectedGeneration, visit) ?: return false
        return owners.get() === current
    }

    fun securityPauseForeground(owner: Any, expectedGeneration: Long,
        visit: com.lavasec.app.security.SecurityPresentationVisit?): Boolean {
        val current = owners.get()?.takeIf { it.graphGeneration == expectedGeneration } ?: return false
        current.security?.pauseForeground(owner, expectedGeneration, visit) ?: return false
        return owners.get() === current
    }

    suspend fun securityRetryAvailability(owner: Any, expectedGeneration: Long): Boolean {
        val current = owners.get()?.takeIf { it.graphGeneration == expectedGeneration } ?: return false
        val security = current.security ?: return false
        if (!security.ownsForeground(owner, expectedGeneration)) return false
        return security.retryAvailability() && owners.get() === current && security.ownsForeground(owner, expectedGeneration)
    }

    fun securityRetireForeground(owner: Any) { owners.get()?.security?.retireForeground(owner) }

    fun securityRetirePresentationVisit(visit: com.lavasec.app.security.SecurityPresentationVisit) {
        owners.get()?.security?.retirePresentationVisit(visit)
    }

    suspend fun securityUnlock(code: String, surface: ProtectedSurface? = null, expectedRevision: Long? = null): Boolean =
        owners.get()?.security?.unlock(code, surface, expectedRevision) ?: false

    suspend fun securitySelectLockTimeout(option: LockTimeoutOption): Boolean =
        owners.get()?.security?.selectLockTimeout(option) ?: false

    suspend fun securitySetProtectedSurface(
        surface: ProtectedSurface,
        enabled: Boolean,
        expectedRevision: Long,
        canCommit: () -> Boolean = { true },
        present: NativeSecurityPresenter = { _, _, _ -> },
    ): Boolean {
        val current = owners.get() ?: return false
        val security = current.security ?: return false
        if (!canCommit() || security.state.value.authenticationRevision != expectedRevision) return false
        // Canonical protectedActions.*: disabling verifies the target surface freshly.
        if (!enabled && !securityAuthorize(current.graphGeneration, surface, fresh = true, present = present)) return false
        val admitted = { owners.get() === current && canCommit() &&
            security.state.value.authenticationRevision == expectedRevision }
        if (!admitted()) return false
        return security.setProtectedSurface(surface, enabled, isCurrent = admitted)
    }

    suspend fun securityResetAfterRecoveryFailure(): Boolean =
        owners.get()?.security?.resetAfterRecoveryFailure() ?: false

    /**
     * Canonical `settings.set biometrics`: delegate the opt-in to the [SecurityOwner], the same
     * owner that projects the `security` snapshot and enforces the gate. The owner applies the
     * canonical enable policy (an enable without a usable enrolled authenticator is persisted off)
     * and a refusal is a real failure, never a fabricated success.
     *
     * Enabling while the enrollment-bound key is `KeyInvalidated` regenerates that Keystore key
     * and clears the invalidation, which removes the passcode fallback a newly enrolled biometric
     * must otherwise use. That is a security-state mutation, so it requires a fresh authentication
     * first — the passcode, because the invalidated biometric key cannot be prompted — exactly as
     * `protectedActions.*` does. A normal enable over an available biometric needs no prompt.
     */
    suspend fun securitySetBiometrics(
        enabled: Boolean,
        expectedGeneration: Long? = null,
        canCommit: () -> Boolean = { true },
        present: NativeSecurityPresenter = { _, _, _ -> },
    ): Boolean {
        val current = owners.get() ?: return false
        if (expectedGeneration != null && current.graphGeneration != expectedGeneration || !canCommit()) return false
        val security = current.security ?: return false
        val revision = security.state.value.authenticationRevision
        val resetsEnrollmentKey = enabled &&
            security.state.value.biometricAvailability == BiometricAvailability.KeyInvalidated
        // Disabling a credential method always needs fresh evidence, including when Settings is
        // not protected. A locked-out biometric uses the same real passcode fallback as unlocking.
        if ((!enabled || resetsEnrollmentKey) && !securityAuthorize(current.graphGeneration,
                surface = null, fresh = true, present = present)) return false
        val admitted = { owners.get() === current && canCommit() &&
            security.state.value.authenticationRevision == revision }
        if (!admitted()) return false
        val accepted = security.setBiometrics(enabled, enrollmentKeyResetAuthorized = resetsEnrollmentKey,
            isCurrent = admitted)
        return accepted && admitted() && security.state.value.biometricEnabled == enabled
    }

    // Native-only report review and consent. The captured bytes survive unchanged manual retries.
    private fun updateBugReport(transform: (BugReportUiState) -> BugReportUiState) = synchronized(reportLock) {
        if (!bugReportDraft.value.submitting) bugReportDraft.value = transform(bugReportDraft.value)
    }

    fun selectBugReportTopic(topic: com.lavasec.app.presentation.settings.BugReportTopicUi) {
        updateBugReport { it.selectTopic(topic) }
    }

    fun setBugReportDescription(description: String) {
        updateBugReport { it.setDescription(description) }
    }

    fun setBugReportAffectedSite(site: String) {
        updateBugReport { it.setAffectedSite(site) }
    }

    fun setBugReportContactEmail(email: String) {
        updateBugReport { it.setContactEmail(email) }
    }

    fun setBugReportDiagnosticsConsent(enabled: Boolean) {
        updateBugReport { it.setDiagnosticsConsent(enabled) }
    }

    fun setBugReportConsentGranted(granted: Boolean): Unit = synchronized(reportLock) {
        if (bugReportDraft.value.submitting) return
        // Consent is owner-driven: the machine gates the send, so the surface updates the exact
        // frozen review capability rather than a parallel UI-only flag.
        if (granted) reportReviewOwner?.grantConsent() else reportReviewOwner?.revokeConsent()
        bugReportDraft.value = bugReportDraft.value.setConsentGranted(granted)
    }

    fun advanceBugReportStep(): Unit = synchronized(reportLock) {
        val draft = bugReportDraft.value
        if (draft.submitting) return
        if (draft.step == com.lavasec.app.presentation.settings.BugReportStepUi.ATTACHMENTS) {
            val reportOwner = owners.get()?.reports
            val review = reportOwner?.prepare(draft.draft, draft.includeDiagnostics)
            reportReview = review
            reportReviewOwner = reportOwner
            bugReportDraft.value = draft.advance().copy(
                reviewedPreview = review?.preview, consentGranted = false,
                failure = if (review == null) BugReportFailureUi.DRAFT_FAILED else null,
            )
        } else updateBugReport { it.advance() }
    }

    fun backBugReportStep() {
        updateBugReport { it.backStep() }
    }

    fun discardBugReport(): Unit = synchronized(reportLock) {
        if (bugReportDraft.value.submitting) return
        reportReviewOwner?.discard()
        reportReview = null
        reportReviewOwner = null
        updateBugReport { it.discard() }
    }

    suspend fun submitBugReport(): Boolean {
        val admitted = synchronized(reportLock) {
            val current = owners.get() ?: return false
            val draft = bugReportDraft.value
            val reportOwner = current.reports ?: return false
            val review = reportReview ?: return false
            if (!draft.canSubmit() || reportReviewOwner !== reportOwner || draft.reviewedPreview != review.preview) return false
            bugReportDraft.value = draft.markSubmitting()
            Triple(current, draft, review)
        }
        val (current, draft, review) = admitted
        return try {
            val result = checkNotNull(current.reports).submit(review)
            synchronized(reportLock) {
                if (owners.get() !== current || reportReview !== review) return false
                bugReportDraft.value = when (result) {
                    com.lavasec.app.diagnostics.BugReportSendResult.SENT -> draft.markSubmitted()
                    com.lavasec.app.diagnostics.BugReportSendResult.RATE_LIMITED -> draft.markFailed(BugReportFailureUi.RATE_LIMITED)
                    else -> draft.markFailed(BugReportFailureUi.SEND_FAILED)
                }
            }
            result == com.lavasec.app.diagnostics.BugReportSendResult.SENT
        } catch (cancelled: CancellationException) {
            synchronized(reportLock) {
                if (owners.get() === current && reportReview === review) {
                    bugReportDraft.value = draft.markFailed(BugReportFailureUi.SEND_FAILED)
                }
            }
            throw cancelled
        }
    }

    suspend fun createFeedbackIntent(
        description: String,
        includeDiagnostics: Boolean,
        attachLocalExport: Boolean,
    ): Intent? {
        val current = owners.get() ?: return null
        val attachment = current.privacy?.state?.value?.exportHandle.takeIf {
            attachLocalExport
        }
        val draft = current.diagnostics?.composeFeedback(
            description = description,
            includeDiagnostics = includeDiagnostics,
            localLogExport = attachment,
        ) ?: return null
        val intent = if (draft.attachment != null) {
            runCatching {
                current.createExportShareIntent?.invoke(draft.attachment)
            }.getOrNull() ?: return null
        } else {
            Intent(Intent.ACTION_SEND).setType("text/plain")
        }
        return intent.apply {
            putExtra(Intent.EXTRA_SUBJECT, draft.subject)
            putExtra(Intent.EXTRA_TEXT, draft.body)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closedState.value = true
        owners.getAndSet(null)?.close()
        resetCustomDns()
        ownerScope.cancel()
    }

    override fun onCleared() {
        close()
    }

    private suspend fun observe(created: CredentialProductOwners) {
        val protection: Flow<ProtectionOwnerState?> = created.protection?.state ?: flowOf(null)
        val activity: Flow<ActivityUiState?> = created.activity?.state ?: flowOf(null)
        val privacy: Flow<PrivacyOwnerState?> = created.privacy?.state ?: flowOf(null)
        val diagnostics: Flow<DiagnosticsFeedbackOwnerState?> =
            created.diagnostics?.state ?: flowOf(null)
        val libraryOwnerState: Flow<FilterLibraryOwner.State> =
            created.filterLibrary?.state ?: flowOf(FilterLibraryOwner.State.Idle)
        val libraryDrafts = combine(
            created.filterLibrary?.drafts ?: flowOf(emptyMap()),
            created.filterLibrary?.newFilter ?: flowOf(null),
        ) { drafts, newFilter -> drafts to newFilter }
        // kotlinx.coroutines combine offers typed overloads only up to five flows,
        // so the product ports are bundled first, then combined with the
        // read-only filter facts and the owner states of this exact generation.
        combine(
            created.onboarding.state,
            protection,
            activity,
            privacy,
            diagnostics,
        ) { onboarding, guard, activityState, privacyState, diagnosticsState ->
            ObservedProductPorts(onboarding, guard, activityState, privacyState, diagnosticsState)
        }.combine(
            created.customization?.state ?: flowOf(null),
        ) { ports, customizationState ->
            ObservedProductPorts(
                ports.onboarding,
                ports.protection,
                ports.activity,
                ports.privacy,
                ports.diagnostics,
                customizationState,
                null,
            )
        }.combine(
            created.security?.state ?: flowOf(null),
        ) { ports, securityState ->
            ports.copy(security = securityState)
        }.combine(created.filterLibraryState) { ports, filterLibrary ->
            ports to filterLibrary
        }.combine(
            combine(
                created.filterDraft,
                created.filterActiveRuleCount,
                created.filterCatalog,
                selectedFilterId,
                created.featureLimits,
            ) { draft, ruleCount, catalog, selected, limits ->
                FilterDerivationInputs(draft, ruleCount, catalog, selected, limits)
            },
        ) { (ports, filterLibrary), derivation ->
            ports to FilterAndDerivation(filterLibrary, derivation)
        }.combine(
            combine(
                libraryOwnerState,
                libraryDrafts,
                created.filterLibrary?.reconstructionRequired ?: flowOf(false),
                created.filterProfileSwitch?.state ?: flowOf(FilterProfileSwitchOwner.State.Idle),
                created.filterProfileSwitch?.reconstructionRequired ?: flowOf(false),
            ) { libraryState, draftFacts, libraryReconstructionRequired, switchState, switchReconstructionRequired ->
                FilterOwnerLibraryPorts(
                    libraryState,
                    draftFacts.first,
                    libraryReconstructionRequired,
                    switchState,
                    switchReconstructionRequired,
                    draftFacts.second,
                )
            }.combine(
                combine(
                    created.resolverPlan,
                    created.resolverPlanSwitch?.state ?: flowOf(ResolverPlanSwitchOwner.State.Idle),
                    created.resolverPlanSwitch?.reconstructionRequired ?: flowOf(false),
                    created.resolverPlanSwitch?.chainedDnsEdit ?: flowOf(ChainedDnsEditState.editable),
                ) { plan, resolverSwitchState, resolverSwitchReconstructionRequired, chainedDnsEdit ->
                    ResolverPorts(
                        plan,
                        resolverSwitchState,
                        resolverSwitchReconstructionRequired,
                        chainedDnsEdit,
                    )
                },
            ) { libraryPorts, resolverPorts ->
                FilterOwnerPorts(
                    libraryPorts.libraryState,
                    libraryPorts.drafts,
                    libraryPorts.libraryReconstructionRequired,
                    libraryPorts.switchState,
                    libraryPorts.switchReconstructionRequired,
                    resolverPorts.plan,
                    resolverPorts.resolverSwitchState,
                    resolverPorts.resolverSwitchReconstructionRequired,
                    resolverPorts.chainedDnsEdit,
                    libraryPorts.newFilter,
                )
            },
        ) { (ports, filterAndDerivation), owners ->
            val mutationPortsAvailable = ports.activity != null && created.domainActions != null
            val available = ports.onboarding.completed && ports.protection != null &&
                (mutationPortsAvailable || !created.filterMutationsAvailable) &&
                ports.privacy != null && ports.diagnostics != null
            val derived = deriveFilterStates(filterAndDerivation, owners)
            MainProductUiState(
                graphGeneration = created.graphGeneration,
                onboarding = ports.onboarding,
                protection = ports.protection,
                protectionOnboardingPhase = protectionOnboardingPhaseFor(ports.onboarding),
                activity = ports.activity ?: ActivityUiState.unavailable,
                privacy = ports.privacy,
                diagnostics = ports.diagnostics,
                customization = ports.customization,
                security = ports.security,
                filterLibrary = filterAndDerivation.library,
                featureLimits = filterAndDerivation.derivation.limits,
                selectedFilterId = filterAndDerivation.derivation.selectedFilterId,
                filterDetail = derived.detail,
                filterReview = derived.review,
                newFilter = owners.newFilter,
                filterProfileSwitch = owners.switchState.toUi(),
                filterLibraryMutation = derived.mutation,
                filterPreparation = derived.preparation,
                resolverPlan = owners.plan?.plan,
                resolverPlanSwitch = owners.resolverSwitchState.toUi(),
                resolverSelection = owners.plan?.toSelection(),
                chainedDnsEdit = owners.chainedDnsEdit,
                resolverHealth = ports.protection?.resolverHealth,
                productAvailable = available,
                unavailableReason = when {
                    ports.onboarding.reconstructionRequired ->
                        ports.onboarding.error ?: "Onboarding authority requires reconstruction."
                    ports.onboarding.completed && !available ->
                        "The complete credential product ports are unavailable."
                    else -> null
                },
            ) to (
                owners.switchReconstructionRequired ||
                    owners.resolverSwitchReconstructionRequired ||
                    owners.libraryReconstructionRequired
                )
        }.combine(created.catalogReconstructionRequired) { (exact, reconstructionRequired), catalogRequired ->
            exact to (reconstructionRequired || catalogRequired)
        }.combine(created.filterTotalRuleCount) { (exact, reconstructionRequired), total ->
            // A plan change explains the saved artifact immediately; it does not command a reload.
            exact.copy(filterCompiledRuleCount = total) to reconstructionRequired
        }.combine(bugReportDraft) { (exact, reconstructionRequired), bugReport ->
            // The export availability follows the live privacy owner handle so
            // the attachment inventory always shows the real local-export fact.
            Triple(
                exact.copy(bugReport = bugReport),
                reconstructionRequired,
                bugReport,
            )
        }.collect { (exact, reconstructionRequired, bugReport) ->
            if (owners.get() === created) {
                hydrateCustomDns(created, created.resolverPlan.value)
                mutableState.value = exact.copy(
                    onboardingReconstructionInFlight = exact.onboarding?.reconstructionRequired == true &&
                        lastOnboardingReconstructionGeneration.get() == created.graphGeneration,
                )
                // A switch/edit outcome that wedged this generation's coordinator
                // (ProtectionUnavailable) recovers through the documented reconstruction
                // path: rotate the exact credential graph once per generation, mirroring
                // the privacy reconciliation guard. Profile switches, resolver switches, and
                // library edits and catalog installs share the authority, so any sticky requirement plus
                // the generation guard keeps the rotation exactly-once.
                if (
                    reconstructionRequired &&
                    lastFilterSwitchReconstructionGeneration.getAndSet(created.graphGeneration) !=
                    created.graphGeneration
                ) {
                    rotateCredentialGraph(created.graphGeneration)
                }
            }
        }
    }

    /**
     * Derives the detail/review/preparation/mutation surfaces from one selected filter id,
     * the live library facts, and the owner generation's drafts and state. Pure mapping —
     * nothing here mutates product state.
     */
    private fun deriveFilterStates(
        inputs: FilterAndDerivation,
        owners: FilterOwnerPorts,
    ): DerivedFilterStates {
        val library = inputs.library
        val draft = inputs.derivation.draft
        val catalog = inputs.derivation.catalog
        val selectedId = inputs.derivation.selectedFilterId
        val drafts = owners.drafts
        val ownerState = owners.libraryState
        val ruleCount = inputs.derivation.ruleCount

        val saved = selectedId?.let { id -> draft?.profile(id) ?: owners.newFilter?.takeIf { it.id == id } }
        val creating = saved != null && owners.newFilter?.id == saved.id
        val detail = if (draft != null && saved != null) {
            FilterLibraryMapper.detail(
                library = draft,
                catalog = catalog.orEmpty(),
                activeBlockRuleCount = ruleCount,
                saved = saved,
                draft = drafts[saved.id],
                limits = inputs.derivation.limits,
                busy = ownerState is FilterLibraryOwner.State.Mutating &&
                    ownerState.filterId == saved.id,
                creating = creating,
            )
        } else {
            null
        }
        val review = if (draft != null && saved != null) {
            drafts[saved.id]?.let { draftProfile ->
                FilterLibraryMapper.review(
                    library = draft,
                    catalog = catalog.orEmpty(),
                    saved = saved,
                    draft = draftProfile,
                    limits = inputs.derivation.limits,
                    busy = ownerState is FilterLibraryOwner.State.Mutating &&
                        ownerState.filterId == saved.id,
                    creating = creating,
                )
            }
        } else {
            null
        }
        val preparation: FilterPreparationUiState
        val mutation: FilterLibraryMutationUiState
        when (ownerState) {
            FilterLibraryOwner.State.Idle -> {
                preparation = FilterPreparationUiState.Idle
                mutation = FilterLibraryMutationUiState.Idle
                preparationSuccessConsumed.set(true)
            }
            is FilterLibraryOwner.State.Mutating -> {
                preparationSuccessConsumed.set(false)
                val commit = ownerState.operation as? FilterLibraryOperation.CommitDraft
                preparation = if (commit != null) {
                    FilterPreparationUiState.Preparing(
                        library?.filter(commit.filterId)?.name ?: "",
                    )
                } else {
                    FilterPreparationUiState.Idle
                }
                mutation = if (commit == null) {
                    FilterLibraryMutationUiState.InFlight(
                        ownerState.operation.toKind(),
                        ownerState.filterId,
                    )
                } else {
                    FilterLibraryMutationUiState.Idle
                }
            }
            is FilterLibraryOwner.State.Failed -> {
                preparationSuccessConsumed.set(false)
                val commit = ownerState.operation as? FilterLibraryOperation.CommitDraft
                preparation = if (commit != null) {
                    FilterPreparationUiState.Failed(
                        retryable = ownerState.failure is FilterLibraryOwner.Failure.Commit,
                    )
                } else {
                    FilterPreparationUiState.Idle
                }
                mutation = if (commit == null) {
                    FilterLibraryMutationUiState.Failed(
                        kind = ownerState.operation.toKind(),
                        filterId = ownerState.filterId,
                        rejection = (ownerState.failure as? FilterLibraryOwner.Failure.Rejected)
                            ?.reason
                            ?.toUi(),
                    )
                } else {
                    FilterLibraryMutationUiState.Idle
                }
            }
            is FilterLibraryOwner.State.Succeeded -> {
                val commit = ownerState.operation as? FilterLibraryOperation.CommitDraft
                preparation = if (commit != null && !preparationSuccessConsumed.get()) {
                    FilterPreparationUiState.Success
                } else {
                    FilterPreparationUiState.Idle
                }
                mutation = if (commit == null) {
                    FilterLibraryMutationUiState.Succeeded(
                        kind = ownerState.operation.toKind(),
                        filterId = ownerState.filterId,
                    )
                } else {
                    FilterLibraryMutationUiState.Idle
                }
            }
        }
        return DerivedFilterStates(detail, review, preparation, mutation)
    }

    /**
     * Maps the onboarding authority's commit lifecycle onto the canonical [OnboardingPhase].
     * A completed onboarding means the initial filter was provisioned (READY); an in-flight
     * commit is the default-filter preparation (PREPARING_DEFAULT_FILTER); a reconstruction
     * requirement is a failed preparation (PREPARATION_FAILED); otherwise the install is fresh.
     */
    private fun protectionOnboardingPhaseFor(state: OnboardingOwnerState): OnboardingPhase = when {
        state.completed -> OnboardingPhase.READY
        state.commitInFlight -> OnboardingPhase.PREPARING_DEFAULT_FILTER
        state.reconstructionRequired -> OnboardingPhase.PREPARATION_FAILED
        else -> OnboardingPhase.FRESH_INSTALL
    }

    private suspend fun drainNavigation(current: CredentialProductOwners?) {
        val owner = current ?: return
        navigationMutex.withLock {
            if (owners.get() !== owner || stagedNavigationOwner === owner) return@withLock
            val next = pendingNavigations.firstOrNull() ?: return@withLock
            if (owner.onboarding.stageNavigation(next)) stagedNavigationOwner = owner
        }
    }

    private companion object {
        const val NO_GENERATION = Long.MIN_VALUE
    }
}

/** One owner generation's observed product ports, bundled for the flow combine. */
private data class ObservedProductPorts(
    val onboarding: OnboardingOwnerState,
    val protection: ProtectionOwnerState?,
    val activity: ActivityUiState?,
    val privacy: PrivacyOwnerState?,
    val diagnostics: DiagnosticsFeedbackOwnerState?,
    val customization: CustomizationOwnerState? = null,
    val security: SecurityOwnerState? = null,
)

/**
 * Both coordinator-backed switch surfaces plus the live resolver plan of one generation,
 * bundled for the flow combine (kotlinx.coroutines combine offers typed overloads only up
 * to five flows).
 */
private data class FilterOwnerPorts(
    val libraryState: FilterLibraryOwner.State,
    val drafts: Map<String, com.lavasec.app.foundation.filter.FilterProfileDraft>,
    val libraryReconstructionRequired: Boolean,
    val switchState: FilterProfileSwitchOwner.State,
    val switchReconstructionRequired: Boolean,
    val plan: FilterResolverPlan?,
    val resolverSwitchState: ResolverPlanSwitchOwner.State,
    val resolverSwitchReconstructionRequired: Boolean,
    val chainedDnsEdit: ChainedDnsEditState,
    val newFilter: FilterProfileDraft?,
)

private data class FilterOwnerLibraryPorts(
    val libraryState: FilterLibraryOwner.State,
    val drafts: Map<String, com.lavasec.app.foundation.filter.FilterProfileDraft>,
    val libraryReconstructionRequired: Boolean,
    val switchState: FilterProfileSwitchOwner.State,
    val switchReconstructionRequired: Boolean,
    val newFilter: FilterProfileDraft?,
)

private data class ResolverPorts(
    val plan: FilterResolverPlan?,
    val resolverSwitchState: ResolverPlanSwitchOwner.State,
    val resolverSwitchReconstructionRequired: Boolean,
    val chainedDnsEdit: ChainedDnsEditState,
)

/** The live filter facts one state emission derives the Filters surfaces from. */
private data class FilterDerivationInputs(
    val draft: com.lavasec.app.foundation.filter.FilterLibraryDraft?,
    val ruleCount: Int?,
    val catalog: List<FilterSourceCatalogEntry>?,
    val selectedFilterId: String?,
    val limits: com.lavasec.app.foundation.subscription.FeatureLimits,
)

private data class FilterAndDerivation(
    val library: FilterLibraryState?,
    val derivation: FilterDerivationInputs,
)

private data class DerivedFilterStates(
    val detail: FilterDetailState?,
    val review: FilterReviewState?,
    val preparation: FilterPreparationUiState,
    val mutation: FilterLibraryMutationUiState,
)

private fun FilterLibraryOperation.toKind(): FilterLibraryMutationKindUi = when (this) {
    is FilterLibraryOperation.CommitDraft -> FilterLibraryMutationKindUi.DRAFT_COMMIT
    is FilterLibraryOperation.CreateFilter -> FilterLibraryMutationKindUi.CREATE
    is FilterLibraryOperation.RenameFilter -> FilterLibraryMutationKindUi.RENAME
    is FilterLibraryOperation.DeleteFilters -> FilterLibraryMutationKindUi.DELETE
    // The whole-library batch is the canonical staged-deletion confirmation; the closest
    // existing native mutation presentation is DELETE.
    is FilterLibraryOperation.CommitLibraryEdit -> FilterLibraryMutationKindUi.DELETE
    // A confirmed import adds content (add-as-new) or rewrites it (replace); the shared import
    // screen owns its own review surface, so the nearest native presentation is CREATE.
    is FilterLibraryOperation.CommitImported -> FilterLibraryMutationKindUi.CREATE
    FilterLibraryOperation.RestoreDefaults -> FilterLibraryMutationKindUi.RESTORE_DEFAULTS
}

private fun com.lavasec.app.foundation.filter.FilterLibraryRejection.toUi(): FilterLibraryRejectionUi =
    when (this) {
        com.lavasec.app.foundation.filter.FilterLibraryRejection.UNKNOWN_FILTER ->
            FilterLibraryRejectionUi.UNKNOWN_FILTER
        com.lavasec.app.foundation.filter.FilterLibraryRejection.BLANK_NAME ->
            FilterLibraryRejectionUi.BLANK_NAME
        com.lavasec.app.foundation.filter.FilterLibraryRejection.INVALID_NAME ->
            FilterLibraryRejectionUi.INVALID_NAME
        com.lavasec.app.foundation.filter.FilterLibraryRejection.INVALID_EMOJI ->
            FilterLibraryRejectionUi.INVALID_EMOJI
        com.lavasec.app.foundation.filter.FilterLibraryRejection.DUPLICATE_NAME ->
            FilterLibraryRejectionUi.DUPLICATE_NAME
        com.lavasec.app.foundation.filter.FilterLibraryRejection.LIMIT_REACHED ->
            FilterLibraryRejectionUi.LIMIT_REACHED
        com.lavasec.app.foundation.filter.FilterLibraryRejection.BLOCKED_DOMAIN_LIMIT ->
            FilterLibraryRejectionUi.BLOCKED_DOMAIN_LIMIT
        com.lavasec.app.foundation.filter.FilterLibraryRejection.ALLOWED_DOMAIN_LIMIT ->
            FilterLibraryRejectionUi.ALLOWED_DOMAIN_LIMIT
        com.lavasec.app.foundation.filter.FilterLibraryRejection.FROZEN_FILTER ->
            FilterLibraryRejectionUi.FROZEN_FILTER
        com.lavasec.app.foundation.filter.FilterLibraryRejection.ACTIVE_FILTER_CANNOT_BE_DELETED ->
            FilterLibraryRejectionUi.ACTIVE_FILTER_CANNOT_BE_DELETED
        com.lavasec.app.foundation.filter.FilterLibraryRejection.LAST_FILTER_CANNOT_BE_DELETED ->
            FilterLibraryRejectionUi.LAST_FILTER_CANNOT_BE_DELETED
        com.lavasec.app.foundation.filter.FilterLibraryRejection.UNKNOWN_DUPLICATE_SOURCE ->
            FilterLibraryRejectionUi.UNKNOWN_DUPLICATE_SOURCE
    }

private fun ActivityContentState<com.lavasec.app.presentation.activity.ActivityOverviewSnapshot>
    .dateRangeOrNull() = when (this) {
    is ActivityContentState.Ready -> value.dateRange
    is ActivityContentState.Loading -> lastGood?.dateRange
    is ActivityContentState.Failed -> lastGood?.dateRange
    ActivityContentState.Disabled,
    ActivityContentState.Unavailable,
    -> null
}

private fun ActivityDecisionUi.toDomainDecision(): DomainDecision = when (this) {
    ActivityDecisionUi.Allowed -> DomainDecision.ALLOWED
    ActivityDecisionUi.Blocked -> DomainDecision.BLOCKED
}

private fun DomainActionUi.toDomainAction(): DomainAction = when (this) {
    DomainActionUi.Allow -> DomainAction.ALLOW
    DomainActionUi.Block -> DomainAction.BLOCK
    DomainActionUi.Remove -> DomainAction.REMOVE
}

private fun FilterProfileSwitchOwner.State.toUi(): FilterProfileSwitchUiState = when (this) {
    FilterProfileSwitchOwner.State.Idle -> FilterProfileSwitchUiState.Idle
    is FilterProfileSwitchOwner.State.Applying -> FilterProfileSwitchUiState.Applying(
        targetFilterId = targetProfileId,
    )
    is FilterProfileSwitchOwner.State.Failed -> FilterProfileSwitchUiState.Failed(
        targetFilterId = targetProfileId,
        failure = when (failure) {
            FilterProfileSwitchOwner.Failure.FROZEN_TARGET ->
                FilterProfileSwitchFailureUi.FROZEN_TARGET
            FilterProfileSwitchOwner.Failure.COMMIT -> FilterProfileSwitchFailureUi.COMMIT
        },
    )
}

private fun ResolverPlanSwitchOwner.State.toUi(): ResolverPlanSwitchUiState = when (this) {
    ResolverPlanSwitchOwner.State.Idle -> ResolverPlanSwitchUiState.Idle
    is ResolverPlanSwitchOwner.State.Applying -> ResolverPlanSwitchUiState.Applying(plan)
    is ResolverPlanSwitchOwner.State.Failed -> ResolverPlanSwitchUiState.Failed(plan)
}
