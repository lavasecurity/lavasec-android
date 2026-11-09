package com.lavasec.app.reactnative

import com.lavasec.app.foundation.resolver.ResolverCatalog

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.SystemClock
import android.provider.Settings
import android.net.Uri
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.lavasec.app.BuildConfig
import com.lavasec.app.edition.AppEdition
import com.lavasec.app.LavasecApplication
import com.lavasec.app.MainActivity
import com.lavasec.app.DomainStageOutcome
import com.lavasec.app.FilterSourceFailureInfo
import com.lavasec.app.MainProductUiState
import com.lavasec.app.security.SecurityAuthorization
import com.lavasec.app.security.AndroidKeystoreChainedKeyItemStore
import com.lavasec.app.ProtectionCommandResult
import com.lavasec.app.account.AccountAvailability
import com.lavasec.app.account.AccountBackend
import com.lavasec.app.account.AccountCommandReasons
import com.lavasec.app.account.AccountPresentation
import com.lavasec.app.account.AccountProviders
import com.lavasec.app.account.AccountSessionChanged
import com.lavasec.app.account.AccountUnavailable
import com.lavasec.app.account.NativeSignInResult
import com.lavasec.app.account.projectAccount
import com.lavasec.app.backup.AndroidBackupLocalStore
import com.lavasec.app.backup.AndroidBackupPasskeyCeremony
import com.lavasec.app.backup.AndroidBackupPasskeyPrfCapability
import com.lavasec.app.backup.BackupAutomaticUploadCoordinator
import com.lavasec.app.backup.BackupAccountBinding
import com.lavasec.app.backup.BackupCloudSource
import com.lavasec.app.backup.BackupCommand
import com.lavasec.app.backup.BackupCommandResult
import com.lavasec.app.backup.BackupFailure
import com.lavasec.app.backup.BackupFailureCopy
import com.lavasec.app.backup.BackupOwner
import com.lavasec.app.backup.BackupPasskeyCeremony
import com.lavasec.app.backup.BackupPasskeyCeremonyResult
import com.lavasec.app.backup.BackupPasskeyRelyingParty
import com.lavasec.app.backup.BackupSealingKeyStore
import com.lavasec.app.backup.BackupSetupResult
import com.lavasec.app.backup.BackupSetupBasis
import com.lavasec.app.backup.KeyItemBackupSealingKeyStore
import com.lavasec.app.backup.LibraryBackupPayloadSource
import com.lavasec.app.backup.ProductBackupRestoreAuthority
import com.lavasec.app.backup.ProductBackupRestoreCustody
import com.lavasec.app.backup.ProductionBackupRestorePublisher
import com.lavasec.app.backup.ProductionBackupRestoreUnlockSource
import com.lavasec.app.backup.ProductionBackupSetup
import com.lavasec.app.backup.SessionBackupAccountAuthority
import com.lavasec.app.backup.SupabaseBackupCloud
import com.lavasec.app.backup.UnavailableBackupPasskeyCeremony
import com.lavasec.app.backup.newBackupDeviceSecret
import com.lavasec.app.backup.newBackupPasskeySalt
import com.lavasec.app.backup.presentBackupPasskeyConsent
import com.lavasec.app.backup.presentBackupRestoreMethod
import com.lavasec.app.backup.presentBackupRestorePhrase
import com.lavasec.app.backup.presentBackupRestoreReview
import com.lavasec.app.backup.presentBackupSetupPhrase
import com.lavasec.app.backup.toBackupRetainedPreferences
import com.lavasec.app.backup.withCustodyLock
import com.lavasec.app.data.CatalogRepository
import com.lavasec.app.data.CatalogRefreshStatus
import com.lavasec.app.data.BundledBlocklistCatalog
import com.lavasec.app.data.CatalogQueryOwner
import com.lavasec.app.data.CatalogSyncOwner
import com.lavasec.app.foundation.biometrics.BiometricAccessPolicy
import com.lavasec.app.foundation.backup.BackupRecoveryMethod
import com.lavasec.app.foundation.backup.BackupPasskeyMaterial
import com.lavasec.app.foundation.backup.BackupRestoreApplyOutcome
import com.lavasec.app.foundation.backup.BackupRestoreBasis
import com.lavasec.app.foundation.backup.BackupRestoreOwner
import com.lavasec.app.foundation.backup.BackupDeviceKeyCapability
import com.lavasec.app.foundation.backup.BackupRestorePreparation
import com.lavasec.app.foundation.backup.BackupRestoreRefusal
import com.lavasec.app.foundation.backup.BackupRestoreUnlock
import com.lavasec.app.foundation.backup.BackupRetainedPreferences
import com.lavasec.app.foundation.catalog.CatalogCandidateRejection
import com.lavasec.app.foundation.chained.ChainedReadiness
import com.lavasec.app.foundation.chained.ChainedConfigurationIssue
import com.lavasec.app.foundation.chained.ChainedReadinessRefusal
import com.lavasec.app.foundation.chained.ChainedRoutingPolicy
import com.lavasec.app.foundation.notifications.NotificationCategory
import com.lavasec.app.foundation.preferences.AndroidCapability
import com.lavasec.app.foundation.preferences.PreferenceValue
import com.lavasec.app.foundation.preferences.ProductPreferenceKey
import com.lavasec.app.haptics.AndroidHapticEffectPlayer
import com.lavasec.app.haptics.HapticCommandHandler
import com.lavasec.app.haptics.HapticCommandOutcome
import com.lavasec.app.foundation.filter.CustomBlocklistEditResult
import com.lavasec.app.foundation.filter.CustomBlocklistRejection
import com.lavasec.app.foundation.filter.ManualRuleEditResult
import com.lavasec.app.foundation.filter.ManualRuleRejection
import com.lavasec.app.foundation.filter.ManualRuleTarget
import com.lavasec.app.security.NativeSecurityPresenter
import com.lavasec.app.security.presentPasscodeAuthentication
import com.lavasec.app.security.presentPasscodeManagement
import com.lavasec.app.foundation.pause.PauseDuration
import com.lavasec.app.foundation.security.ProtectedSurface
import com.lavasec.app.foundation.filter.FilterLibraryRejection
import com.lavasec.app.product.AndroidQrImageDecoder
import com.lavasec.app.product.FilterLibraryEditOutcome
import com.lavasec.app.product.FilterLibraryMutationResult
import com.lavasec.app.product.FilterShareAcquisitionRefusal
import com.lavasec.app.product.FilterShareAcquisitionResult
import com.lavasec.app.product.AndroidFilterShareDeviceHost
import com.lavasec.app.product.AndroidVpnConfigurationPickSource
import com.lavasec.app.product.FilterShareCandidateResult
import com.lavasec.app.product.FilterShareContent
import com.lavasec.app.product.FilterShareCardRequests
import com.lavasec.app.product.FilterShareCardQrEncoder
import com.lavasec.app.product.FilterShareCardSummary
import com.lavasec.app.product.FilterShareCardCopy
import com.lavasec.app.product.AndroidFilterShareCardArtifact
import com.lavasec.app.product.FilterShareFailureReason
import com.lavasec.app.product.FilterShareImportFailureReason
import com.lavasec.app.product.FilterShareDeviceAdapter
import com.lavasec.app.product.FilterShareImportReviewResult
import com.lavasec.app.product.FilterShareOwner
import com.lavasec.app.product.FilterShareSource
import com.lavasec.app.product.ShareQrRenderer
import com.lavasec.app.product.activity.ActivityVisibilityOwner
import com.lavasec.app.foundation.share.ShareableFilterConfiguration
import com.lavasec.app.foundation.share.ShareableFilterImportConfirmation
import com.lavasec.app.foundation.share.ShareableFilterImportRefusal
import com.lavasec.app.foundation.share.ShareableFilterImportReview
import com.lavasec.app.foundation.flows.NativeFlow
import com.lavasec.app.foundation.flows.NativeFlowBuildConfiguration
import com.lavasec.app.foundation.flows.NativeFlowInventory
import com.lavasec.app.reactnative.components.LavaNativeFlowHost
import com.lavasec.app.reactnative.components.LavaNativeFlowOutcome
import com.lavasec.app.reactnative.components.LavaNativeFlowProduction
import com.lavasec.app.reactnative.components.LavaNativeFlowReason
import com.lavasec.app.reactnative.components.LavaNativeFlowRequest
import com.lavasec.app.foundation.sudoku.SudokuGame
import com.lavasec.app.foundation.sudoku.SudokuGameStore
import com.lavasec.app.foundation.sudoku.SudokuPuzzle
import com.lavasec.app.R
import com.lavasec.app.platform.ChainedComposition
import com.lavasec.app.platform.ChainedConfigurationCommandOwner
import com.lavasec.app.platform.ChainedConfigurationDraftRow
import com.lavasec.app.platform.ChainedConfigurationEditorCommitResult
import com.lavasec.app.platform.ChainedConfigurationEditorRefusal
import com.lavasec.app.platform.ChainedConfigurationEditorResult
import com.lavasec.app.platform.ChainedConfigurationMetadata
import com.lavasec.app.platform.ChainedConfigurationRemovalResult
import com.lavasec.app.platform.ChainedConfigurationToggleKey
import com.lavasec.app.platform.ChainedConfigurationToggleResult
import com.lavasec.app.platform.ChainedVpnRestriction
import com.lavasec.app.platform.ProtectionState
import com.lavasec.app.platform.StoredChainedUpstreamConfiguration
import com.lavasec.app.platform.EntitlementTierFact
import com.lavasec.app.platform.vpnRestriction
import com.lavasec.app.platform.LoggingSettings
import com.lavasec.app.platform.SettingsRoute
import com.lavasec.app.platform.SubscriptionTier
import com.lavasec.app.platform.VpnFirstConfigurationBegin
import com.lavasec.app.platform.VpnFirstConfigurationCoordinator
import com.lavasec.app.platform.VpnPickedConfigurationEdit
import com.lavasec.app.presentation.GuardPrimaryAction
import com.lavasec.app.product.ResolverDnsContext
import com.lavasec.app.product.ResolverDnsEditOutcome
import com.lavasec.app.product.privacy.LocalActivityExportOptions
import com.lavasec.app.presentation.filters.FilterPreparationUiState
import com.lavasec.app.settings.NotificationWrite
import com.lavasec.app.settings.PreferenceWrite
import com.lavasec.app.settings.ProductSettingsOwner
import com.lavasec.app.settings.ProductSettingsStore
import com.lavasec.app.settings.SettingsCommandDispatcher
import com.lavasec.app.settings.SettingsCommandPorts
import com.lavasec.app.settings.SettingsSetResult
import com.lavasec.app.settings.wireValue
import com.lavasec.app.sudoku.SharedPreferencesSudokuGameStore
import com.lavasec.app.sudoku.toWireJson
import com.lavasec.app.vpn.LavaVpnRuntimeBridge
import com.lavasec.app.vpn.chained.ChainedActivitySignal
import com.lavasec.app.vpn.LavaVpnServiceClient
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Adapter only: every command uses the same generation-fenced Kotlin owner as native screens. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class NativeProductBridge(
    private val context: ReactApplicationContext,
    private val scope: CoroutineScope,
) {
    private val application get() = context.applicationContext as LavasecApplication
    private val product get() = application.foregroundProduct
    private val sudokuStore: SudokuGameStore = SharedPreferencesSudokuGameStore(context)
    private val revision = AtomicLong()
    private val snapshotEmission = NativeSnapshotEmission()
    private val admission = NativeCommandAdmission()
    private val domainChecks = NativeDomainCheckOwner()
    private val displayClearRevision = AtomicLong()
    private var lastForegroundId: String? = null
    private var foregroundClosing: String? = null
    private fun sharedOwnerChanged() { scope.launch(Dispatchers.Main) { publish() } }
    private val preparationOwner by lazy {
        NativePreparationOwner(
            currentHost = ::currentVisitHost,
            currentGeneration = { product.state.value.graphGeneration },
            canRead = { product.state.value.productAvailable && sharedFieldsReadable(NativeSharedForegroundKind.CREATE_FILTER) },
            publish = ::sharedOwnerChanged,
            announce = { success ->
                playHaptic(JSONObject().put("kind", if (success) "succeeded" else "failed"))
                currentVisitActivity()?.window?.decorView?.announceForAccessibility(
                    context.getString(if (success) com.lavasec.app.presentation.R.string.filters_prepare_success_title
                        else com.lavasec.app.presentation.R.string.filters_prepare_failed_title))
            },
        )
    }
    // Activity lifecycle publishes this exact witness before RN callbacks. IO publication
    // checks never read ReactContext's UI-confined currentActivity WeakReference.
    private fun currentVisitHost(): NativeVisitHost? = application.nativeVisitHosts.current()
    private fun currentVisitActivity(): LavaReactActivity? {
        val host = currentVisitHost() ?: return null
        return (host.owner as? LavaReactActivity)?.takeIf { host.matches(currentVisitHost()) }
    }
    private data class FilterDetailHost(val activity: LavaReactActivity, val nativeHostID: String)
    private val filterDetailHostsClosed = java.util.concurrent.atomic.AtomicBoolean()
    private val filterDetailRetirements = java.util.concurrent.ConcurrentHashMap<FilterDetailHost,
        com.lavasec.app.MainProductViewModel.FilterDetailRetirementWitness>()

    private fun rememberFilterDetail(host: NativeVisitHost,
        witness: com.lavasec.app.MainProductViewModel.FilterDetailRetirementWitness) {
        val activity = host.owner as? LavaReactActivity ?: run { product.retireFilterDetail(witness); return }
        val key = FilterDetailHost(activity, host.nativeHostID)
        filterDetailRetirements[key] = witness
        // A returned witness remains the original selected visit even after a suspended
        // caller loses its host. Schedule exact teardown in the surviving VM immediately.
        if ((filterDetailHostsClosed.get() || !host.matches(currentVisitHost())) &&
            filterDetailRetirements.remove(key, witness)) product.retireFilterDetail(witness)
    }

    private fun retireFilterDetailHost(key: FilterDetailHost) {
        val witness = filterDetailRetirements.remove(key) ?: return
        // Do not launch in this module's retiring scope: VM cleanup owns its own lifetime.
        product.retireFilterDetail(witness)
    }

    private fun retireVisitHost(activity: LavaReactActivity) {
        accountBackupFlow?.takeIf { it.host.owner === activity && it.host.nativeHostID == activity.nativeHostID }?.close()
        retireFilterDetailHost(FilterDetailHost(activity, activity.nativeHostID))
        customEntryHosts.retireHost(activity, activity.nativeHostID)
        sharedForeground.retireHost(activity, activity.nativeHostID)
        sharedVpnEditor.retireHost(activity, activity.nativeHostID)
        product.nativeVpnDraftHosts.retireHost(activity, activity.nativeHostID)
        standaloneDomainReviews.retireHost(activity, activity.nativeHostID)
    }
    private val sharedVpnEditor: NativeSharedVpnEditorOwner by lazy {
        NativeSharedVpnEditorOwner(
            currentGeneration = { product.state.value.graphGeneration ?: -1L },
            currentHost = ::currentVisitHost,
            canRead = { sharedForeground.currentId == null && sharedFieldsReadable(NativeSharedForegroundKind.FEEDBACK) },
            canInteract = {
                val activity = context.currentActivity as? LavaReactActivity
                val generation = product.state.value.graphGeneration
                generation != null && activity?.isPresentationReady(generation,
                    sharedVpnEditor.currentId?.let { "foreground:$it" }) == true
            },
            reauthorize = {
                val activity = context.currentActivity as? LavaReactActivity
                val generation = product.state.value.graphGeneration
                if (activity == null || generation == null || !activity.awaitForeground() || context.currentActivity !== activity) false
                else {
                    val present: NativeSecurityPresenter = { _, verify, cancel -> activity.presentPasscodeAuthentication(verify, cancel) }
                    val granted = product.securityAuthorize(generation, ProtectedSurface.APP_UNLOCK, present = present) &&
                        product.securityAuthorize(generation, ProtectedSurface.APP_SETTINGS, present = present)
                    granted && context.currentActivity === activity && !activity.isDestroyed && activity.foreground &&
                        product.state.value.graphGeneration == generation
                }
            },
            privacyCoverRequired = {
                com.lavasec.app.security.SecurityPrivacyPolicy.requiresBackgroundCover(
                    product.securityStateForSnapshot(product.state.value.graphGeneration))
            },
            pickDocument = {
                val activity = context.currentActivity as? LavaReactActivity
                if (activity == null) com.lavasec.app.platform.VpnConfigurationPick.Unavailable
                else AndroidVpnConfigurationPickSource(activity, activity.activityResultRegistry).pickDocument()
            },
            publish = ::sharedOwnerChanged,
            authorityRevision = { product.securityStateForSnapshot(product.state.value.graphGeneration)?.authenticationRevision ?: -1L },
            editorIdentity = { application.chainedComposition.value?.let { composition ->
                val owner = chainedOwner(composition)
                owner.currentDraft()?.let { Triple(owner, it.id, it.revision) } ?: owner
            } },
            coroutineScope = scope,
            requiredMessage = { context.getString(R.string.vpn_configuration_required) },
            invalidMessage = { context.getString(R.string.vpn_configuration_invalid) },
            importFailedMessage = { context.getString(R.string.vpn_configuration_import_failed) },
            importFailureMessage = { reason -> com.lavasec.app.product.vpnConfigurationImportFailureMessage(context, reason) },
        )
    }
    private class DeepLinkSetup(
        val handoff: NativeDeepLinkEntryRegistry.SetupHandoff,
        val nativeOwner: com.lavasec.app.onboarding.OnboardingOwner,
        val host: NativeVisitHost,
    ) {
        val claim = AtomicReference(handoff.claim)
        val transition = AtomicReference<com.lavasec.app.CredentialGraphTransition?>(null)
    }
    private val deepLinkSetups = java.util.concurrent.ConcurrentHashMap<String, DeepLinkSetup>()

    private val sharedOnboarding by lazy {
        NativeSharedOnboardingOwner(scope = scope, read = { product.sharedOnboardingAuthority() },
            foreground = { currentVisitActivity()?.foreground == true },
            readyForOnboarding = { generation, id ->
                (context.currentActivity as? LavaReactActivity)?.isPresentationReady(generation, "onboarding:$id") == true
            }, onChanged = ::sharedOwnerChanged, port = object : NativeSharedOnboardingOwner.Port {
                override suspend fun forward(generation: Long) = product.forwardOnboarding(generation)
                override suspend fun back(generation: Long) { product.backOnboarding(generation) }
                override suspend fun selectProtectionLevel(generation: Long, level: com.lavasec.app.foundation.config.ProtectionLevel) {
                    product.selectProtectionLevel(level, generation)
                }
                override suspend fun selectFallback(generation: Long, enabled: Boolean) { product.selectOnboardingFallback(enabled, generation) }
                override suspend fun completeVpnConsent(generation: Long, id: Long, granted: Boolean) = product.completeVpnConsent(id, granted, generation)
                override suspend fun cancelVpnConsent(generation: Long, id: Long) = product.cancelOnboardingVpnConsent(id, generation)
                override suspend fun completeNotifications(generation: Long, granted: Boolean) = product.completeNotificationPermission(granted, generation)
                override suspend fun presentVpn(pending: com.lavasec.app.onboarding.PendingVpnConsent): Boolean {
                    val activity = context.currentActivity as? LavaReactActivity ?: throw CancellationException("host-closed")
                    val granted = activity.presentNative(pending.request) == Activity.RESULT_OK
                    if (!activity.awaitForeground() || context.currentActivity !== activity || activity.isDestroyed) {
                        throw CancellationException("host-closed")
                    }
                    return granted
                }
                override suspend fun requestNotifications(): Boolean {
                    val activity = context.currentActivity as? LavaReactActivity ?: throw CancellationException("host-closed")
                    val granted = activity.requestNotifications()
                    if (!activity.awaitForeground() || context.currentActivity !== activity || activity.isDestroyed) {
                        throw CancellationException("host-closed")
                    }
                    return granted
                }
                override fun notificationsGranted() = currentVisitActivity()?.notificationsGranted() == true
                override fun vpnInstalled() = currentVisitActivity()?.let { VpnService.prepare(it) == null } == true
                override suspend fun finish(generation: Long) = product.finishSharedOnboarding(generation)
                override suspend fun finish(generation: Long, visitID: String): Boolean {
                    val setup = deepLinkSetups[visitID] ?: return product.finishSharedOnboarding(generation)
                    return product.finishSharedOnboarding(generation, setup.nativeOwner) { transition ->
                        // The lifecycle callback is before factory publication and may
                        // run off Main. Only immutable/atomic native host custody is read.
                        if (filterDetailHostsClosed.get() || !setup.host.matches(currentVisitHost()) || deepLinkSetups[visitID] !== setup) return@finishSharedOnboarding
                        val next = NativeDeepLinkEntryRegistry.process.transferSetupHandoff(setup.handoff, visitID,
                            setup.nativeOwner, transition.sourceGeneration, transition.successorGeneration) ?: return@finishSharedOnboarding
                        setup.claim.set(next)
                        setup.transition.set(transition)
                    }
                }
            })
    }
    private val sharedForeground: NativeSharedForegroundOwner by lazy {
        NativeSharedForegroundOwner(
            currentGeneration = { product.state.value.graphGeneration ?: -1L },
            currentHost = ::currentVisitHost,
            canRead = ::sharedFieldsReadable, publish = ::sharedOwnerChanged,
            canInteract = { kind ->
                val generation = product.state.value.graphGeneration
                val activity = context.currentActivity as? LavaReactActivity
                generation != null && sharedFieldsReadable(kind) &&
                    activity?.isPresentationReady(generation, sharedForeground.currentId?.let { "foreground:$it" }) == true
            },
            feedbackOwner = { product.state.value.graphGeneration?.let(product::sharedFeedbackOwner) },
            copyReceipt = { receipt ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("Lava report receipt", receipt))
                clipboard != null
            },
            sampleFeedback = { product.refreshDiagnostics() },
            authorizationRevision = { product.securityStateForSnapshot(product.state.value.graphGeneration)?.authenticationRevision ?: -1L },
        )
    }
    private val activityShareCards = NativeActivityShareCards()
    private val shareCards = FilterShareCardRequests()
    @Volatile private var accountBackupFlow: NativeAccountBackupFlow? = null
    @Volatile private var portableBackupActive = false
    private val portableBackupModuleRetired = java.util.concurrent.atomic.AtomicBoolean(false)
    private val pendingPortableBackup by lazy {
        AndroidPendingPortableBackupStore(File(application.noBackupFilesDir, "portable-import"))
    }

    private fun openAccountBackupFlow(kind: String, generation: Long, activity: LavaReactActivity): NativeAccountBackupFlow? {
        val backend = application.accountBackend.value ?: return null
        val account = backend.sessions.state.value
        if (portableBackupActive || account.availability != AccountAvailability.SIGNED_IN || accountBackupFlow?.retained() == true ||
            sharedForeground.currentId != null || sharedVpnEditor.currentId != null) return null
        val host = currentVisitHost()?.takeIf { it.owner === activity } ?: return null
        lateinit var flow: NativeAccountBackupFlow
        flow = NativeAccountBackupFlow(kind, host, generation, ::currentVisitHost,
            { product.state.value.graphGeneration },
            { application.accountBackend.value === backend && backupFlowStillBound(generation, account.userId, account.generation) },
            { activity.foreground && product.securityStateForSnapshot(generation)?.let {
                it.isAuthorized(ProtectedSurface.APP_UNLOCK) && it.isAuthorized(ProtectedSurface.APP_SETTINGS) &&
                    (kind != "backupRestore" || it.isAuthorized(ProtectedSurface.FILTERS))
            } == true },
            { activity.isPresentationReady(generation, "foreground:${flow.id}") },
            ::sharedOwnerChanged,
            { product.securityStateForSnapshot(generation)?.authenticationRevision ?: -1L })
        accountBackupFlow = flow
        activity.accountBackupFlow = flow
        sharedOwnerChanged()
        return flow
    }

    private fun retireAccountBackupFlow(flow: NativeAccountBackupFlow) {
        flow.close()
        if (accountBackupFlow === flow) accountBackupFlow = null
        (flow.host.owner as? LavaReactActivity)?.let { activity ->
            activity.runOnUiThread { if (activity.accountBackupFlow === flow) activity.accountBackupFlow = null }
        }
        sharedOwnerChanged()
    }
    private val guardGestureHaptics by lazy {
        com.lavasec.app.haptics.NativeGuardGestureHaptics(
            scope = scope,
            currentHost = { (context.currentActivity as? LavaReactActivity)?.window?.decorView },
            foreground = { (context.currentActivity as? LavaReactActivity)?.foreground == true },
            hapticsEnabled = { product.state.value.customization?.haptics ?: true },
            sink = { host, kind -> AndroidHapticEffectPlayer(host as android.view.View).play(kind) },
        )
    }
    private val shareCardLifecycle = object : com.facebook.react.bridge.LifecycleEventListener {
        override fun onHostResume() { guardGestureHaptics.authorityChanged(); scope.launch(Dispatchers.Main) { installSharedPresentationHost(); startRequestedPreview(); startRequestedDeepLink() } }
        override fun onHostPause() { domainChecks.retire(); preparationOwner.presentationChanged(); guardGestureHaptics.handle("end"); shareCards.revoke(); activityShareCards.revoke() }
        override fun onHostDestroy() {
            domainChecks.retire()
            preparationOwner.authorityChanged()
            // The Activity retirement handler owns exact teardown. A late React host
            // callback must not retire an import already admitted by its successor.
            if (importVisit?.isCurrent(currentVisitHost(), product.state.value.graphGeneration) == false)
                retireImportVisit()
            (context.currentActivity as? LavaReactActivity)?.let(::retireVisitHost)
            guardGestureHaptics.handle("end"); shareCards.revoke(); activityShareCards.revoke()
        }
    }

    /** Monotonic identity for a flow-host launch; binds a late result to the exact request. */
    private val flowRequestId = AtomicLong()

    /**
     * The one [LavaNativeFlowHost] for the current foreground activity. Cached per activity
     * identity so its single-active-presentation slot is shared across commands — a fresh host per
     * command would defeat that guard — and rebuilt when React swaps the foreground activity.
     */
    private var flowHostActivity: LavaReactActivity? = null
    private var flowHost: LavaNativeFlowHost? = null

    /**
     * The single native flow host for [activity], or `null` when it cannot be constructed. The
     * host is the sole owner of route/credential presentations; the bridge is only the wire
     * adapter. `qaTools` follows the same DEBUG_ONLY capability the snapshot projects.
     */
    private fun nativeFlowHost(activity: LavaReactActivity): LavaNativeFlowHost? {
        if (flowHostActivity !== activity) {
            flowHostActivity = activity
            flowHost = runCatching {
                LavaNativeFlowProduction.host(
                    activity = activity,
                    configuration = if (BuildConfig.DEBUG) {
                        NativeFlowBuildConfiguration.DEBUG
                    } else {
                        NativeFlowBuildConfiguration.RELEASE
                    },
                )
            }.getOrNull()
        }
        return flowHost
    }

    /** The app-layer share/import owner reads live filter facts and never mutates the library. */
    private val shareOwner: FilterShareOwner by lazy {
        FilterShareOwner(
            source = object : FilterShareSource {
                override fun savedProfile(filterId: String) = product.savedFilterProfile(filterId)
                override fun customBlocklists() = product.customBlocklistSources()
                override fun availableCatalogSourceIds() = product.availableCatalogSourceIds()
                override fun featureLimits() = product.currentFeatureLimits()
            },
        )
    }

    private val clipboard: ClipboardManager
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /**
     * The one credential-protected backup store shared by every backup owner: the lifecycle owner
     * below, the setup path, and the automatic-upload coordinator. Sharing one instance keeps the
     * in-memory envelope cache coherent between setup and `backup.now`.
     */
    private val backupLocalStore: AndroidBackupLocalStore by lazy {
        AndroidBackupLocalStore(context.applicationContext)
    }

    /**
     * The device-retained backup sealing key (Keystore-wrapped, non-exportable), shared by setup and
     * the owner so a later upload can re-seal the live payload without the recovery phrase.
     */
    private val backupSealingKeys: BackupSealingKeyStore by lazy {
        KeyItemBackupSealingKeyStore(AndroidKeystoreChainedKeyItemStore(context.applicationContext))
    }

    /**
     * The process-durable encrypted-backup owner. It reads the account authority from the
     * application's credential graph and the cloud client from the account backend, so it survives
     * React-context recreation and is never reconstructed per snapshot. The same durable store is
     * the automatic-upload preference authority for `settings.set backup.automatic`, and the live
     * filter library is its portable-payload source for setup. Restore is wired to the reviewed
     * [backupRestoreOwner] below.
     */
    private val backupOwner: BackupOwner by lazy {
        BackupOwner(
            cloud = BackupCloudSource {
                application.accountBackend.value?.backups?.let(::SupabaseBackupCloud)
            },
            accounts = SessionBackupAccountAuthority { application.accountBackend.value?.sessions },
            store = backupLocalStore,
            deletionStore = backupLocalStore,
            automaticPreference = backupLocalStore,
            sealingKeys = backupSealingKeys,
            payloadSource = LibraryBackupPayloadSource(
                library = { product.currentFilterLibrary() },
                guardianLedger = { application.guardProgress.captureLedger() },
                preferences = {
                    product.state.value.privacy?.logging?.toBackupRetainedPreferences(
                        protectionEnabledHint = product.state.value.protection?.lifecycle
                            ?.isRequestedEnabled ?: false,
                    ) ?: BackupRetainedPreferences.DEFAULT
                },
            ),
            failureCopy = BackupFailureCopy { failure ->
                context.getString(
                    when (failure) {
                        BackupFailure.UPLOAD_FAILED -> R.string.backup_failure_upload_failed
                        BackupFailure.DELETION_NOT_SAVED -> R.string.backup_failure_deletion_not_saved
                        BackupFailure.LOCAL_CLEANUP_INCOMPLETE ->
                            R.string.backup_failure_local_cleanup_incomplete

                        BackupFailure.REMOTE_DELETE_OFFLINE -> R.string.backup_failure_remote_delete_offline
                        BackupFailure.DELETION_PENDING -> R.string.backup_failure_deletion_pending
                        BackupFailure.STATE_UNAVAILABLE -> R.string.backup_failure_state_unavailable
                    },
                )
            },
        )
    }

    /**
     * Production "setup -> upload" over the same store and account-bound cloud client. It seals the
     * live portable payload with the user's recovery secret, durably stores the local envelope and
     * uploads it bound to the captured account generation.
     */
    private val backupSetupOwner: ProductionBackupSetup by lazy {
        ProductionBackupSetup(
            payloadSource = LibraryBackupPayloadSource(
                library = { product.currentFilterLibrary() },
                guardianLedger = { application.guardProgress.captureLedger() },
                preferences = {
                    product.state.value.privacy?.logging?.toBackupRetainedPreferences(
                        protectionEnabledHint = product.state.value.protection?.lifecycle
                            ?.isRequestedEnabled ?: false,
                    ) ?: BackupRetainedPreferences.DEFAULT
                },
            ),
            store = backupLocalStore,
            accounts = SessionBackupAccountAuthority { application.accountBackend.value?.sessions },
            cloud = BackupCloudSource {
                application.accountBackend.value?.backups?.let(::SupabaseBackupCloud)
            },
            reEnableGate = { backupOwner.admitReEnableForSetup() },
            reEnableCommit = { backupOwner.commitReEnableForSetup() },
            sealingKeys = backupSealingKeys,
        )
    }

    /**
     * The production download + decrypt adapter. It loads the account's current envelope through
     * the account-bound cloud client and decrypts with the user-supplied secret, so restore is a
     * real positive path instead of a typed unavailable.
     */
    private val backupRestoreUnlockSource: ProductionBackupRestoreUnlockSource by lazy {
        ProductionBackupRestoreUnlockSource(
            accounts = SessionBackupAccountAuthority { application.accountBackend.value?.sessions },
            cloud = BackupCloudSource {
                application.accountBackend.value?.backups?.let(::SupabaseBackupCloud)
            },
            // Re-key a restored envelope with a fresh device key so `backup.now`/automatic continue
            // from the restored ciphertext without retaining the user's recovery phrase.
            deviceSecretFactory = { newBackupDeviceSecret() },
            deviceCustody = backupLocalStore,
            deviceKeys = backupSealingKeys,
        )
    }

    /**
     * The native PRF ceremony seam. It resolves to the AndroidX Credential Manager adapter only when
     * a relying-party origin is configured; otherwise it fails closed, mirroring the capability
     * probe. The ceremony material never crosses the bridge.
     */
    private fun backupPasskeyCeremony(activity: LavaReactActivity, accountID: String?): BackupPasskeyCeremony {
        val relyingParty = BackupPasskeyRelyingParty.fromOrigin(
            origin = BuildConfig.BACKUP_PASSKEY_RP_ORIGIN,
            userName = accountID.orEmpty(),
            userDisplayName = "Lava backup",
        )
        return if (relyingParty == null) {
            UnavailableBackupPasskeyCeremony
        } else {
            AndroidBackupPasskeyCeremony(activity, relyingParty, currentHost = { currentVisitActivity() })
        }
    }

    /**
     * Coalesced automatic uploads for configuration changes. It only arms when a local envelope
     * exists and the durable `backup.automatic` preference is on; the send rides the same
     * account-bound path as `backup.now`.
     */
    private val backupAutomaticCoordinator: BackupAutomaticUploadCoordinator by lazy {
        BackupAutomaticUploadCoordinator(
            owner = backupOwner,
            accounts = SessionBackupAccountAuthority { application.accountBackend.value?.sessions },
            store = backupLocalStore,
            automaticPreference = backupLocalStore,
            deletionFenceBlocks = { backupOwner.snapshot().deletionPending },
            scope = scope,
            currentConfigurationGeneration = { product.state.value.graphGeneration ?: 0L },
        )
    }

    // Warm the asynchronous local provider probe before the user opens a setup/restore sheet.
    private val backupPasskeyCapability = AndroidBackupPasskeyPrfCapability.production(context)
    @Volatile private var backupDeviceKeyBasis: BackupSetupBasis? = null

    /**
     * The reviewed restore-apply owner. The publisher mutates the SAME live product state every
     * other filter/logging mutation uses: [ProductBackupRestoreAuthority] wraps the live filter
     * library owner and the logging privacy owner, so a restore lands one generation-fenced
     * library commit plus one durable logging commit (never a second persistence authority).
     */
    private val backupRestoreOwner: BackupRestoreOwner by lazy {
        BackupRestoreOwner(
            publisher = ProductionBackupRestorePublisher(
                ProductBackupRestoreAuthority(
                    library = { product.currentFilterLibrary() },
                    logging = { product.state.value.privacy?.logging },
                    // The credential generation binds the whole restore transaction; a rotation
                    // between the library and logging writes fails the publication.
                    currentGeneration = { product.state.value.graphGeneration },
                    // The restore preserves drafts/editing during the write so a failed rollback
                    // does not lose unsaved work; the publisher discards them only on full commit.
                    replaceLibraryWith = { library, expected -> product.replaceFilterLibraryPreservingEditing(library, expected) },
                    rollbackLibraryWith = { library, expected -> product.rollbackFilterLibraryPreservingEditing(library, expected) },
                    setLoggingWith = { settings -> product.setLoggingForRestore(settings) },
                    restoreLoggingWith = { settings, expectedGeneration, stillAuthorized ->
                        product.setLoggingForRestore(settings, expectedGeneration, stillAuthorized)
                    },
                    discardEditingWith = { product.discardFilterEditingSessions() },
                    protectionEnabledHint = {
                        product.state.value.protection?.lifecycle?.isRequestedEnabled ?: false
                    },
                ),
                custody = ProductBackupRestoreCustody(
                    store = backupLocalStore,
                    sealingKeys = backupSealingKeys,
                    liveAccount = {
                        application.accountBackend.value?.sessions?.state?.value?.userId
                    },
                ),
                accounts = SessionBackupAccountAuthority { application.accountBackend.value?.sessions },
                guardian = application.guardProgress,
            ),
            // Passkey restore is offered only when the pre-ceremony probe (credential provider +
            // configured relying-party origin) says so; the ceremony itself still has to produce a
            // valid 32-byte PRF output or the owner refuses PasskeyUnavailable.
            passkeyPrf = backupPasskeyCapability,
            deviceKey = BackupDeviceKeyCapability {
                val basis = backupDeviceKeyBasis
                val live = application.accountBackend.value?.sessions?.state?.value
                basis != null && live?.availability == AccountAvailability.SIGNED_IN &&
                    live.userId == basis.account.accountID && live.generation == basis.accountGeneration
            },
        )
    }
    /**
     * The process-owned validated catalog acquisition/cache/install owner, shared with durable
     * Android maintenance workers. Its lazy construction stays behind a guard: a bundled-catalog failure must never take down every other command or the snapshot.
     * The persisted last-known-good is loaded off the main thread by [initialize]. The installer
     * is the live authority's `installCatalog`, so a selected catalog is recompiled and committed
     * through the same generation-fenced coordinator as every other filter mutation.
     */
    private val catalogSyncOwner: CatalogSyncOwner? get() = application.catalogMaintenance.sync

    /** Query the exact installed compile authority. A persisted-but-uncommitted repair
     * candidate must never appear as a selectable catalog, and refreshes cannot leave a stale
     * lazily captured catalog in the shared picker. */
    private fun catalogQueryOwner() = CatalogQueryOwner(product.catalogForQueries() ?: EMPTY_BLOCKLIST_CATALOG)

    /**
     * Minimal owner for the canonical `activity.visibility` review-moment dwell token. Tracks the
     * live moment on a monotonic clock; no durable state and no fabricated review prompt.
     */
    private val activityVisibilityOwner = ActivityVisibilityOwner(
        nowMillis = { SystemClock.elapsedRealtime() },
    )

    /**
     * Durable retained preferences (`appearance`/`look`/`matchTextSize`/`textSize` and the four
     * notification categories). App-level presentation state, not credential evidence, so it is
     * constructed from ordinary app storage and reconstructs from what was persisted.
     */
    private val settingsStore = ProductSettingsStore(context)
    private val settingsOwner = ProductSettingsOwner(
        initialPreferences = settingsStore.loadPreferences(),
        initialNotifications = settingsStore.loadNotifications(),
        persistPreferences = settingsStore::persistPreferences,
        persistNotifications = settingsStore::persistNotifications,
        admitsLook = { requested, current ->
            runCatching {
                com.lavasec.app.foundation.preferences.GuardianProgressPolicy.available(
                    requested, current, application.plusBackend.value?.entitlement?.state?.value?.localFeaturesAvailable == true,
                    application.guardProgress.captureLedger())
            }.getOrDefault(requested == "original" || requested == current)
        },
    )

    /**
     * The canonical `settings.set` dispatcher. Retention is delegated to the live privacy owner
     * (the same owner the native Privacy surface writes) and haptics to the existing
     * customization owner, so the bridge is a thin adapter over one authority per key.
     */
    private val settingsDispatcher = SettingsCommandDispatcher(
        ports = object : SettingsCommandPorts {
            override suspend fun applyPreference(
                key: ProductPreferenceKey,
                value: PreferenceValue,
            ): PreferenceWrite = settingsOwner.setPreference(key, value)

            override suspend fun applyNotification(
                category: NotificationCategory,
                enabled: Boolean,
            ): NotificationWrite = settingsOwner.setNotification(category, enabled)

            override fun currentLoggingSettings(): LoggingSettings? =
                product.state.value.privacy?.logging

            override suspend fun applyLoggingSettings(settings: LoggingSettings): Boolean =
                product.setLogging(settings)

            override suspend fun applyHaptics(enabled: Boolean): Boolean =
                product.setCustomizationHaptics(enabled)

            /**
             * `backup.automatic` is the backup owner's durable preference, the same authority the
             * `backup` snapshot reads. A refused write (a deletion fence, an in-flight operation, or
             * a lost durable write) is an honest failure, never a fabricated success.
             */
            override suspend fun applyBackupAutomatic(enabled: Boolean): PreferenceWrite =
                if (backupOwner.setAutomaticEnabled(enabled)) {
                    // Enabling schedules a coalesced upload through the same account-bound path; the
                    // policy suppresses it when no local envelope exists, so an unconfigured device
                    // schedules nothing.
                    if (enabled) {
                        if (AppEdition.current.cloudBackup) backupAutomaticCoordinator.onConfigurationChanged()
                    }
                    PreferenceWrite.APPLIED
                } else {
                    PreferenceWrite.FAILED
                }

            /**
             * `protectedActions.*` is the SecurityOwner's authority, not a retained preference.
             * The owner runs the fresh authentication a removal requires; a missing owner or a
             * refused write is an honest failure, never a fabricated success.
             */
            override suspend fun applyProtectedSurface(
                surface: ProtectedSurface,
                enabled: Boolean,
            ): PreferenceWrite {
                val originalHost = currentVisitHost() ?: return PreferenceWrite.FAILED
                val activity = originalHost.owner as? LavaReactActivity ?: return PreferenceWrite.FAILED
                val generation = product.state.value.graphGeneration
                    ?: return PreferenceWrite.FAILED
                val revision = product.securityStateForSnapshot(generation)?.authenticationRevision
                    ?: return PreferenceWrite.FAILED
                val accepted = product.securitySetProtectedSurface(
                    surface = surface,
                    enabled = enabled,
                    expectedRevision = revision,
                    canCommit = { originalHost.matches(currentVisitHost()) && activity.foreground &&
                        product.state.value.graphGeneration == generation },
                ) { _, verify, cancel -> activity.presentPasscodeAuthentication(verify, cancel) }
                return if (accepted) PreferenceWrite.APPLIED else PreferenceWrite.FAILED
            }

            /**
             * The biometric capability the live [com.lavasec.app.security.SecurityOwner] reports
             * for `settings.set biometrics`. A registered authenticator with detectable hardware
             * is `AVAILABLE`; the fail-closed [com.lavasec.app.security.UnavailableBiometricAuthenticatorPort]
             * (every build without a device-gated adapter) reports `Unavailable`, which stays the
             * honest `unavailable-per-platform` classification — never a fabricated available.
             */
            override fun biometricCapability(): AndroidCapability {
                val generation = product.state.value.graphGeneration
                    ?: return AndroidCapability.UNAVAILABLE_PER_PLATFORM
                val state = product.securityStateForSnapshot(generation)
                    ?: return AndroidCapability.UNAVAILABLE_PER_PLATFORM
                return if (state.biometricEnabled || BiometricAccessPolicy.shouldShowToggle(state.biometricAvailability)) {
                    AndroidCapability.AVAILABLE
                } else {
                    AndroidCapability.UNAVAILABLE_PER_PLATFORM
                }
            }

            /**
             * Canonical `biometrics`: delegate the opt-in to the SecurityOwner, the same owner that
             * projects the `security` snapshot and enforces the gate. An enable is durable only when
             * the owner confirms it persisted; an enable the platform cannot evaluate is an honest
             * failure, never a fabricated success.
             */
            override suspend fun applyBiometrics(enabled: Boolean): PreferenceWrite {
                val generation = product.state.value.graphGeneration ?: return PreferenceWrite.FAILED
                val originalHost = currentVisitHost() ?: return PreferenceWrite.FAILED
                val activity = originalHost.owner as? LavaReactActivity ?: return PreferenceWrite.FAILED
                // The owner authenticates disable freshly and captures the same epoch at the write
                // edge. A successor host, foreground or graph cannot accept the old confirmation.
                val accepted = product.securitySetBiometrics(enabled, expectedGeneration = generation,
                    canCommit = { originalHost.matches(currentVisitHost()) && activity.foreground &&
                        product.state.value.graphGeneration == generation }) { _, verify, cancel ->
                    activity.presentPasscodeAuthentication(verify, cancel)
                }
                if (!accepted) return PreferenceWrite.FAILED
                val state = product.securityStateForSnapshot(generation) ?: return PreferenceWrite.FAILED
                return if (!enabled || state.biometricEnabled) PreferenceWrite.APPLIED else PreferenceWrite.FAILED
            }
        },
    )

    /**
     * The headless Activity/Top Domains/Domain History/Network Activity/Nerd Stats read owner.
     * It adapts the same Activity state machine, diagnostics sample and resolver selection the
     * native surfaces render, so a shared screen reads real owner facts or an explicit refusal.
     */
    private val queryOwner: ProductQueryOwner by lazy {
        ProductQueryOwner(product, AndroidQueryResources(context.applicationContext))
    }

    /**
     * The opaque review token minted by `filter.review` and consumed by `filter.apply`. The owner
     * keeps the authoritative review witness; this only proves the caller's apply matches the
     * generation+filter review it was handed, so a stale token is a typed rejection.
     */
    private var reviewToken: String? = null
    private var reviewTokenKey: String? = null

    /**
     * Open "bring your own list" editor sessions: an opaque token (returned by `filter.customList`)
     * bound to the filter whose draft the editor mutates. The Android editor is RN, so the token
     * fences `filter.addCustomList` to the filter the picker opened; `customEntry.dismiss` clears it.
     */
    private data class SharedCustomEntry(val id: String, val kind: String, val generation: Long,
        val host: NativeVisitHost, val filterId: String? = null, val context: String = "", val draftLease: String? = null)
    private val customEntries = java.util.Collections.synchronizedMap(linkedMapOf<String, SharedCustomEntry>())
    private val customBlocklistEditorSessions = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val customEntryHosts = NativeCustomEntryHostOwner(
        currentGeneration = { product.state.value.graphGeneration }, currentHost = ::currentVisitHost)

    /** Main-dispatcher creation/host retirement; module close is serialized by the registry lock.
     * Teardown captures this exact native record, never a successor host, token or draft. */
    private fun rememberCustomEntry(entry: SharedCustomEntry): Boolean {
        val replaced = synchronized(customEntries) {
            if (customEntries.containsKey(entry.id)) return false
            customEntries.values.filter { previous -> previous.generation != entry.generation ||
                previous.kind == entry.kind && previous.host.sameHost(entry.host) &&
                    (entry.kind == "dns" || previous.filterId == entry.filterId) }
        }
        // Opening a new DNS form replaced its VM token. A new blocklist form for this exact
        // host/filter replaces its former form capability, without cancelling the parent draft.
        replaced.forEach { customEntryHosts.retire(it.id, it.generation) }
        synchronized(customEntries) { customEntries[entry.id] = entry }
        return customEntryHosts.register(entry.id, entry.generation, entry.host) {
            synchronized(customEntries) {
                if (customEntries[entry.id] !== entry) return@synchronized
                customEntries.remove(entry.id)
                if (entry.kind == "dns") product.dismissCustomEntry(entry.id, entry.generation)
                else entry.filterId?.let { customBlocklistEditorSessions.remove(entry.id, it) }
            }
        }
    }

    /**
     * The pending standalone Domain History review: an opaque token the shared Review screen echoes
     * back on `filter.apply`, bound to the filter whose draft it staged. Mirrors the iOS
     * `standaloneDomainReviews` map (one entry in practice — a review is modal). Cleared when the
     * review is applied or cancelled.
     */
    @Volatile private var standaloneReviewToken: String? = null
    @Volatile private var standaloneReviewFilterId: String? = null
    /**
     * The credential generation the standalone review was staged against. Cancellation must act
     * only on that captured owner: after a rotation the same filter id may name a different graph's
     * unsaved draft, and discarding against the current owner would destroy the new graph's edits.
     */
    @Volatile private var standaloneReviewGeneration: Long? = null
    @Volatile private var standaloneReviewLease: String? = null

    private class StandaloneDomainReview(
        val visit: NativeDomainReviewHostOwner.Visit,
        val witness: com.lavasec.app.MainProductViewModel.StandaloneDomainReviewRetirementWitness,
        val domainCheck: NativeDomainCheckOwner.Grant? = null,
    )
    private val standaloneReviewLock = Any()
    private var standaloneReviewRecord: StandaloneDomainReview? = null
    private val standaloneDomainReviews = NativeDomainReviewHostOwner(
        currentGeneration = { product.state.value.graphGeneration }, currentHost = ::currentVisitHost)

    private fun clearStandaloneReview(record: StandaloneDomainReview) = synchronized(standaloneReviewLock) {
        if (standaloneReviewRecord !== record) return@synchronized
        standaloneReviewRecord = null
        standaloneReviewToken = null
        standaloneReviewFilterId = null
        standaloneReviewGeneration = null
        standaloneReviewLease = null
    }

    /** The transient camera capture for the in-flight QR import; deleted on every outcome. */
    private var cameraCaptureFile: File? = null

    /** Module teardown marks exact parent capabilities before cancelling its command scope. */
    private val vpnDraftModuleRetired = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Durable copy of the pending first-import marker so a process kill before Save/Cancel is
     * recovered on the next launch and the uncommitted rotation is removed.
     */
    private val pendingVpnImportStore: PendingVpnImportStore by lazy {
        SharedPreferencesPendingVpnImportStore(context)
    }

    /**
     * The one reviewed import awaiting confirmation. The bridge owns this witness (the shared
     * screen only sees counts), so a confirmation can never supply its own content, and a review
     * built against a different target or a retired generation is refused as stale. Cleared on
     * every review, confirmation, and refusal.
     */
    private var importVisit: NativeImportVisit? = null
    private var importReviewID: String? = null
    private var importReviewLibrary: com.lavasec.app.foundation.filter.FilterLibraryDraft? = null
    private var importReview: ShareableFilterImportReview? = null
    private var importReviewTarget: String? = null
    private var importReviewGeneration: Long? = null

    /** The raw candidate behind [importReview], kept native so a scan can be re-reviewed for a
     *  replacement target without exposing the code to JavaScript. */
    private var importReviewRaw: String? = null

    /**
     * The chained-configuration editor authority for the current credential graph.
     *
     * [ChainedConfigurationCommandOwner] holds the in-memory editor draft as a projection over the
     * SINGLE committed rotation; it is rebuilt whenever the credential composition rotates, so a
     * draft opened against a retired generation is discarded by construction.
     */
    private val chainedOwnerLock = Any()
    private var chainedOwnerComposition: ChainedComposition? = null
    private var chainedOwner: ChainedConfigurationCommandOwner? = null

    /**
     * The latest `vpn` snapshot family, refreshed by the chained-composition collector in
     * [initialize]. `@Volatile` because it is written on the IO dispatcher and read by the
     * synchronized [snapshot] projection; `null` before the first read or with no live composition.
     */
    @Volatile private var vpnFamily: NativeVpnFamilyProjection<ChainedComposition, VpnFamilySnapshot>? = null

    /**
     * Transient account command state projected with the owner snapshot. `@Volatile` because it is
     * written on the command/IO dispatcher and read by the synchronized [snapshot] projection.
     * Never holds a credential: only which provider admission is running, whether a deletion is
     * in flight, and the last user-facing auth message.
     */
    @Volatile private var accountSignInProvider: String? = null
    @Volatile private var accountDeleting: Boolean = false
    @Volatile private var accountMessage: String = ""

    private var previewHost: LavaReactActivity? = null
    private fun installSharedPresentationHost() {
        // Retire a previous Activity before a successor installs ports or observes a snapshot.
        sharedForeground.authorityChanged()
        sharedVpnEditor.authorityChanged()
        preparationOwner.authorityChanged()
        accountBackupFlow?.authorityChanged()
        val activity = context.currentActivity as? LavaReactActivity ?: return
        filterDetailRetirements.keys.filter {
            it.activity !== activity || it.nativeHostID != activity.nativeHostID
        }.forEach(::retireFilterDetailHost)
        activity.setNativeVisitRetirementHandler {
            preparationOwner.retireHost(activity, activity.nativeHostID)
            retireVisitHost(activity)
            if (importVisit?.retireHost(activity, activity.nativeHostID) == true) retireImportVisit()
        }
        activity.setWireGuardInputPort(sharedVpnEditor)
        activity.setForegroundBackHandler {
            val accountFlow = accountBackupFlow
            when {
                accountFlow?.retained() == true -> accountFlow.cancel(accountFlow.id)
                preparationOwner.currentId != null -> { preparationOwner.currentId?.let(preparationOwner::cancel); true }
                sharedForeground.currentId != null -> { sharedForeground.requestDismiss(); true }
                sharedVpnEditor.currentId != null -> { sharedVpnEditor.requestDismiss(); true }
                sharedOnboarding.snapshot() != null -> {
                    val setup = sharedOnboarding.snapshot()
                    val generation = product.sharedOnboardingAuthority().first
                    if (setup != null && generation != null) scope.launch(Dispatchers.Main) {
                        val type = if (setup.optBoolean("mock") && setup.optJSONArray("history")?.length() == 0)
                            "onboarding.dismiss" else "onboarding.back"
                        sharedOnboarding.execute(generation, JSONObject().put("type", type).put("id", setup.optString("id")))
                    }
                    true
                }
                else -> false
            }
        }
    }
    private fun startRequestedPreview() {
        val activity = context.currentActivity as? LavaReactActivity ?: return
        if (!BuildConfig.DEBUG || !activity.isOnboardingPreview || previewHost === activity) return
        val generation = product.sharedOnboardingAuthority().first ?: return
        val id = sharedOnboarding.enter(generation, mock = true) ?: return
        previewHost = activity
        scope.launch(Dispatchers.Main) {
            sharedOnboarding.awaitCompletion(id)
            if (context.currentActivity === activity && activity.isOnboardingPreview && !activity.isFinishing) activity.finish()
        }
    }

    /** A validated native entry may request only one of the four shared public destinations. */
    private fun startRequestedDeepLink() {
        val activity = currentVisitActivity() ?: return
        if (!activity.foreground || !activity.nativeHostAlive || activity.isFinishing) return
        val id = activity.nativeDeepLinkEntryID ?: return
        val (generation, state) = product.sharedOnboardingAuthority()
        if (generation == null || state == null || state.reconstructionRequired) return
        val security = product.securityStateForSnapshot(generation) ?: return
        if (product.state.value.copy(security = security).isLocked(ProtectedSurface.APP_UNLOCK, System.currentTimeMillis())) return
        val entries = NativeDeepLinkEntryRegistry.process
        val claim = entries.claim(id, activity, activity.nativeHostID, generation) ?: return
        if (!entries.begin(claim)) return
        val nativeOwner = if (state.completed) null else product.sharedOnboardingOwner(generation)
        val onboarding = if (state.completed) null else sharedOnboarding.enter(generation, mock = false)
        if (!state.completed && (onboarding == null || nativeOwner == null)) { entries.cancel(claim); return }
        val setup = if (onboarding != null && nativeOwner != null) {
            val handoff = entries.prepareSetupHandoff(claim, onboarding, nativeOwner)
            val host = currentVisitHost()
            if (handoff == null || host == null || host.owner !== activity || !host.matches(currentVisitHost())) { entries.cancel(claim); return }
            DeepLinkSetup(handoff, nativeOwner, host).also { deepLinkSetups[onboarding] = it }
        } else null
        scope.launch(Dispatchers.Main) {
            try {
                if (onboarding != null && !sharedOnboarding.awaitCompletion(onboarding)) return@launch
                val actualClaim = setup?.claim?.get() ?: claim
                if (setup != null) {
                    val transition = setup.transition.get() ?: return@launch
                    if (!product.awaitSharedOnboardingTransition(transition)) return@launch
                }
                val actualGeneration = actualClaim.generation
                if (!activity.awaitForeground() || context.currentActivity !== activity || !activity.nativeHostAlive ||
                    !entries.isCurrent(actualClaim, product.state.value.graphGeneration ?: -1L)) return@launch
                val authority = product.sharedOnboardingAuthority()
                if (authority.first != actualGeneration || authority.second?.completed != true) return@launch
                // This successor starts a fresh authorization turn. Setup's old
                // graph grants are never reused by the external destination.
                val unlocked = product.securityAuthorize(actualGeneration, ProtectedSurface.APP_UNLOCK) { _, verify, cancel ->
                    activity.presentPasscodeAuthentication(verify, cancel)
                }
                if (!unlocked || context.currentActivity !== activity || !activity.foreground || !activity.nativeHostAlive ||
                    !entries.isCurrent(actualClaim, product.state.value.graphGeneration ?: -1L)) return@launch
                val surface = actualClaim.destination.surface
                val authorized = surface == null || product.securityAuthorize(actualGeneration, surface) { _, verify, cancel ->
                    activity.presentPasscodeAuthentication(verify, cancel)
                }
                val current = product.sharedOnboardingAuthority()
                if (!authorized || context.currentActivity !== activity || !activity.foreground || !activity.nativeHostAlive ||
                    activity.isFinishing || current.first != actualGeneration || current.second?.completed != true) return@launch
                val currentSecurity = product.securityStateForSnapshot(actualGeneration) ?: return@launch
                if (product.state.value.copy(security = currentSecurity).isLocked(ProtectedSurface.APP_UNLOCK, System.currentTimeMillis()) ||
                    surface != null && !currentSecurity.isAuthorized(surface)) return@launch
                if (entries.publish(actualClaim, actualGeneration, setupCompleted = true, authorized = true)) publish()
            } finally {
                val actualClaim = setup?.claim?.get() ?: claim
                if (entries.navigation(id, activity, activity.nativeHostID, actualClaim.generation) == null) entries.cancel(actualClaim)
                if (onboarding != null && setup != null) deepLinkSetups.remove(onboarding, setup)
            }
        }
        publish()
    }

    fun initialize() {
        context.addLifecycleEventListener(shareCardLifecycle)
        scope.launch { product.state.collect { sharedForeground.authorityChanged(); sharedVpnEditor.authorityChanged();
            withContext(Dispatchers.Main) { guardGestureHaptics.authorityChanged(); installSharedPresentationHost(); startRequestedPreview(); startRequestedDeepLink() }; publish() } }
        scope.launch {
            product.state.map { it.graphGeneration }.distinctUntilChanged().collectLatest { generation ->
                if (generation != null) application.guardProgress.state.collect { publish() }
            }
        }
        scope.launch { application.started.collect { sharedForeground.authorityChanged(); sharedVpnEditor.authorityChanged(); publish() } }
        // The account owner is per credential graph: republish when the backend is installed or
        // rotated and on every session change, so a sign-in/out is reflected without a command.
        scope.launch {
            application.accountBackend.collectLatest { backend ->
                publish()
                backend?.sessions?.state?.collect { accountBackupFlow?.authorityChanged(); publish() }
            }
        }
        scope.launch {
            application.plusBackend.collectLatest { backend ->
                publish()
                if (backend != null) combine(backend.entitlement.state, backend.purchases.state,
                    backend.controller.state) { _, _, _ -> Unit }.collect { publish() }
            }
        }
        scope.launch { withContext(Dispatchers.IO) { catalogSyncOwner?.loadCachedInstalled() } }
        // The `vpn` snapshot family is derived from the live per-credential-generation composition.
        // Saved selection and installed-owner identity are independent. Adoption/failed reload
        // and service replacement must refresh the family even without another durable write.
        // The in-memory editor draft is overlaid at read time by `snapshot()`.
        scope.launch {
            observeNativeVpnFamily(
                compositions = application.chainedComposition,
                currentComposition = { application.chainedComposition.value },
                updates = { composition ->
                    sharedVpnEditor.authorityChanged()
                    vpnFamilyUpdates(
                        composition.configurationRepository.storedConfiguration,
                        composition.preferenceStore.chainedEnabled,
                        composition.preferenceStore.chainedDnsFallbackEnabled,
                        LavaVpnRuntimeBridge.chainedActivity,
                        LavaVpnRuntimeBridge.state,
                        // Entitlement changes invalidate cached needsPlus/canEnable too.
                        application.plusBackend.flatMapLatest { it?.entitlement?.state ?: flowOf(null) },
                    )
                },
                read = ::buildVpnFamily,
                publish = { projection ->
                    sharedVpnEditor.authorityChanged()
                    vpnFamily = projection
                    publish()
                },
            )
        }
        // Recover an eagerly imported first rotation the user never committed (a process kill before
        // Save/Cancel left only the durable marker). Runs when the composition becomes available.
        scope.launch {
            application.chainedComposition.collectLatest { composition ->
                if (composition != null) admission.recover {
                    product.nativeVpnDraftHosts.serialized(composition) { recoverPendingFirstVpnImport(composition) }
                }
            }
        }
    }

    /**
     * Removes only the atomically tagged first import left by a process death. A live editor owns
     * its pending rotation until Save/Cancel. Cleanup compares the tag and identity inside the
     * repository write lock, so a newer saved configuration cannot be removed by recovery.
     */
    private suspend fun recoverPendingFirstVpnImport(composition: ChainedComposition) {
        if (product.nativeVpnDraftHosts.hasImport(composition)) return
        val repository = composition.configurationRepository
        val committed = repository.loadConfiguration()
        val transactionId = committed?.pendingImportId
        if (transactionId != null) {
            // Operational failures leave the tag in the authoritative record for a later retry.
            repository.removePendingImport(transactionId, committed.identity)
            return
        }
        // Older builds wrote a separate generation-only marker after publication. That marker
        // cannot distinguish an unchanged successful Save from an abandoned import. Preserve the
        // saved configuration rather than deleting user data on this ambiguous legacy witness.
        val legacy = pendingVpnImportStore.load() ?: return
        val accountID = application.accountBackend.value?.sessions?.state?.value?.userId
        if (legacy.accountID != accountID) return
        val issue = repository.configurationIssue()
        if (issue == null || issue == com.lavasec.app.foundation.chained.ChainedConfigurationIssue.MISSING_CONFIGURATION) {
            pendingVpnImportStore.clear()
        }
    }

    /** The editor authority for [composition], rebuilt when the credential graph rotates. */
    private fun chainedOwner(composition: ChainedComposition): ChainedConfigurationCommandOwner =
        synchronized(chainedOwnerLock) {
            if (chainedOwnerComposition !== composition || chainedOwner == null) {
                chainedOwnerComposition = composition
                chainedOwner = ChainedConfigurationCommandOwner(
                    repository = composition.configurationRepository,
                    enabledPreference = object : com.lavasec.app.platform.ChainedEnabledPreferencePort {
                        override suspend fun setEnabled(enabled: Boolean) = composition.setEnabled(enabled)
                        override suspend fun setEnabledWhileCurrent(enabled: Boolean, isCurrent: () -> Boolean): Boolean =
                            composition.preferenceStore.setChainedEnabledWhileCurrent(enabled, isCurrent)
                    },
                    loadEnabled = composition.preferenceStore::loadEnabled,
                    dnsFallbackPreference = { enabled ->
                        application.chainedComposition.value === composition &&
                            composition.preferenceStore.setChainedDnsFallbackEnabled(enabled) &&
                            application.chainedComposition.value === composition
                    },
                    onFullTunnelCommitted = { expected ->
                        application.chainedComposition.value === composition &&
                            composition.configurationRepository.loadConfiguration()?.identity?.generation == expected &&
                            (!composition.preferenceStore.loadDnsFallbackEnabled() ||
                                composition.preferenceStore.setChainedDnsFallbackEnabled(false)) &&
                            application.chainedComposition.value === composition
                    },
                    onFullTunnelCommittedWhileCurrent = { expected, isCurrent ->
                        isCurrent() && application.chainedComposition.value === composition &&
                            composition.configurationRepository.loadConfiguration()?.identity?.generation == expected &&
                            isCurrent() && (!composition.preferenceStore.loadDnsFallbackEnabled() ||
                                composition.preferenceStore.setChainedDnsFallbackEnabledWhileCurrent(false, isCurrent)) &&
                            isCurrent() && application.chainedComposition.value === composition
                    },
                )
            }
            requireNotNull(chainedOwner)
        }

    /**
     * The confirmed `vpn` snapshot family, or `null` when no live composition exists. Every field
     * is owner-confirmed; the family projects the ordered native selection and never
     * carries configuration text or key material.
     */
    private suspend fun buildVpnFamily(
        composition: ChainedComposition,
        runtime: VpnRuntimeRotationWitness,
    ): VpnFamilySnapshot {
        val stored = try {
            composition.configurationRepository.loadConfiguration()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        // A key failure does not erase readable non-secret rotation metadata. Conversely, the
        // serving read can degrade storage errors to null; only a classified absence proves removal.
        val issue = if (stored == null) {
            try {
                composition.configurationRepository.configurationIssue()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ChainedConfigurationIssue.UNAVAILABLE
            }
        } else null
        val rotationNote = vpnRotationNoteResource(runtime, stored, issue)?.let(context::getString).orEmpty()
        val status = try {
            composition.status()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return VpnFamilySnapshot.unavailable().copy(rotationNote = rotationNote)
        }
        val rows = stored?.configuration?.orderedHops?.map { row ->
            val it = ChainedConfigurationMetadata.from(row.withoutEntryHop())
                ChainedConfigurationDraftRow(
                    name = row.displayName.ifEmpty { it.endpoint },
                    mode = if (it.eligibility.routingPolicy == ChainedRoutingPolicy.FULL_TUNNEL) {
                        "Full tunnel"
                    } else {
                        "Split tunnel"
                    },
                    isEnabled = row.isEnabled,
                )
        } ?: emptyList()
        val enableReason = (status.enableReadiness as? ChainedReadiness.NotReady)?.reason
        // The device restriction comes from the SAME live availability verdict that gates the
        // engine, so a device-ineligible entitled user is told why instead of seeing a
        // disabled enable control with no explanation.
        val restriction = runCatching { composition.availability.current() }.getOrNull()
            ?.vpnRestriction()
            ?.let { reason ->
                when (reason) {
                    ChainedVpnRestriction.DEVICE_INSUFFICIENT_MEMORY ->
                        context.getString(R.string.vpn_restriction_device_ineligible)

                    ChainedVpnRestriction.DEVICE_STATE_UNREADABLE ->
                        context.getString(R.string.vpn_restriction_device_state_unreadable)
                }
            } ?: ""
        return VpnFamilySnapshot(
            setup = status.configured,
            enabled = status.enabled,
            canEnable = status.canEnable,
            canEdit = true,
            fallback = composition.preferenceStore.loadDnsFallbackEnabled(),
            canChangeFallback = stored?.configuration?.containsFullTunnel != true,
            needsPlus = enableReason == ChainedReadinessRefusal.NOT_ENTITLED,
            busy = false,
            restriction = restriction,
            error = "",
            unavailable = false,
            needsRepair = status.configured && !status.secretsAvailable,
            generation = stored?.identity?.generation?.toString() ?: "",
            rows = rows,
            draft = null,
            rotationNote = rotationNote,
            runtimeWitness = runtime,
        )
    }

    fun snapshot(promise: Promise) {
        val host = currentVisitHost()
        val value = snapshot(host)
        if (!snapshotHostStillCurrent(host)) promise.reject("stale-host", "The native app window is inactive.")
        else promise.resolve(value)
    }

    private fun snapshotHostStillCurrent(host: NativeVisitHost?): Boolean =
        if (host == null) currentVisitHost() == null else host.matches(currentVisitHost())

    fun snapshotForHost(hostID: String, promise: Promise) {
        val host = currentVisitHost()
        if (host == null || !host.matches(host) || !NativeHostBinding.accepts(hostID, host.nativeHostID, false)) {
            promise.reject("stale-host", "The native app window is inactive.")
            return
        }
        val value = snapshot(host)
        if (!host.matches(currentVisitHost())) {
            promise.reject("stale-host", "The native app window is inactive.")
        } else promise.resolve(value)
    }

    private fun ownsCommandHost(request: JSONObject): Boolean {
        val host = currentVisitHost()
        return NativeHostBinding.accepts(request,
            host?.nativeHostID, host == null || !host.matches(host)) || NativeHostBinding.isExactRetirement(request)
    }

    // Bind the revision to its exact read under one lock; concurrent query/event projection must
    // never attach a newer revision to an older owner read.
    @Synchronized
    private fun snapshot(host: NativeVisitHost? = currentVisitHost()): String {
        val activity = host?.owner as? LavaReactActivity
        val foreground = application.started.value && host?.matches(currentVisitHost()) == true && activity?.foreground == true
        val plus = application.plusBackend.value
        val state = product.state.value
        val security = product.securityStateForSnapshot(state.graphGeneration)
        val projected = JSONObject(productSnapshot(
            state.copy(security = security), revision.incrementAndGet(), foreground,
            System.currentTimeMillis(), plus?.entitlement?.state?.value,
            plus?.purchases?.state?.value, plus?.controller?.state?.value,
            sudoku = if (foreground && state.canRetainSudoku()) sudokuStore.load() else null,
            dnsCustomDraft = product.dnsCustomDraftSession.value,
            dnsCustomToken = product.dnsCustomDraftToken.value,
            dnsCustomSaved = product.dnsCustomSavedDraft.value,
            backup = backupOwner.snapshot(),
            libraryEditing = product.libraryEditSessionForSnapshot(state.graphGeneration),
            preferences = settingsOwner.state.value.preferences,
            notifications = settingsOwner.state.value.notifications,
            locale = presentationLocale(),
            savedFilters = product.savedFiltersForSnapshot(state.graphGeneration),
            catalog = catalogSyncOwner?.snapshot(),
            account = accountPresentation(),
            vpn = vpnFamily?.valueFor(application.chainedComposition.value)?.copy(draft = chainedOwner?.currentDraft()),
            vpnRuntime = VpnRuntimeRotationWitness(LavaVpnRuntimeBridge.chainedActivity.value, LavaVpnRuntimeBridge.state.value),
            guardProgress = if (state.graphGeneration != null) application.guardProgress.state.value else null,
            domainCheckRevision = product.domainCheckRevisionForSnapshot(state.graphGeneration),
        ))
        projected.put("edition", JSONObject().apply {
            val edition = AppEdition.current
            put("id", edition.id)
            put("accounts", edition.accounts)
            put("cloudBackup", edition.cloudBackup)
            put("purchases", edition.purchases)
            put("localFeaturesUnlocked", edition.localFeaturesUnlocked)
            put("portableBackup", true)
            put("nativeSupport", edition.nativeSupport)
        })
        if (foreground && !state.copy(security = security).isLocked(ProtectedSurface.APP_SETTINGS, System.currentTimeMillis())) {
            projected.put("portableBackup", JSONObject().put("pending", pendingPortableBackup.status()))
        }
        host?.let { projected.put("nativeHostID", it.nativeHostID) }
        projected.put("backgroundPrivacyCoverRequired", com.lavasec.app.security.SecurityPrivacyPolicy.requiresBackgroundCover(security))
        projected.put("appPrivacyCoverRequired", activity?.appPrivacyCoverRequired == true)
        if (security == null || security.availability != com.lavasec.app.security.SecurityAvailability.Available || security.recoveryRequired) {
            projected.put("presentationError", "Lava could not access its security settings. Try again.")
        }
        projected.put("presentationBlocked", !foreground || state.copy(security = security)
            .isLocked(ProtectedSurface.APP_UNLOCK, System.currentTimeMillis()))
        projected.optJSONObject("filterSession")?.let { session ->
            state.graphGeneration?.let { generation ->
                product.sharedFilterDraftLease(generation, session.optString("id"))?.let { session.put("lease", it) }
            }
        }
        projected.optJSONObject("security")?.apply {
            put("appUnlockCoverChoice", security?.appUnlockCoverChoice ?: JSONObject.NULL)
            put("ownerRevision", "${state.graphGeneration}:${application.accountBackend.value?.sessions?.state?.value?.generation ?: 0L}")
            put("sourceRevision", product.currentFilterLibrary()?.hashCode()?.toString() ?: "unavailable")
            put("displayClearRevision", displayClearRevision.get().toString())
        }
        projected.optJSONObject("vpn")?.put("authorized", foreground &&
            !state.copy(security = security).isLocked(ProtectedSurface.APP_SETTINGS, System.currentTimeMillis()) &&
            !state.copy(security = security).isLocked(ProtectedSurface.APP_UNLOCK, System.currentTimeMillis()))
        val liveId = sharedForeground.currentId ?: sharedVpnEditor.currentId ?: accountBackupFlow?.takeIf { it.retained() }?.id
        if (lastForegroundId != null && lastForegroundId != liveId) foregroundClosing = lastForegroundId
        lastForegroundId = liveId
        (sharedForeground.projection() ?: sharedVpnEditor.projection())?.let { projected.put("foregroundFlow", it) }
        if (foreground && state.graphGeneration == accountBackupFlow?.generation &&
            !projected.optBoolean("presentationBlocked", true) &&
            !state.copy(security = security).isLocked(ProtectedSurface.APP_SETTINGS, System.currentTimeMillis())) {
            accountBackupFlow?.projection()?.let { projected.put("foregroundFlow", it) }
        }
        customEntryProjection()?.let { projected.put("customEntry", it) }
        sharedOnboarding.snapshot()?.let { projected.put("onboardingSetup", it) }
        preparationOwner.authorityChanged()
        projected.put("filterPreparationPresented", preparationOwner.presentedFor(state.graphGeneration))
        // Both the captured snapshot and the live native owner must permit the
        // read. A grant changing during projection cannot mix private progress
        // into a covered or older-generation snapshot.
        if (foreground && projected.optBoolean("available") &&
            !projected.optBoolean("presentationBlocked", true) &&
            projected.optJSONObject("filters")?.optBoolean("locked", true) == false) {
            preparationOwner.projection(state.graphGeneration)?.let { projected.put("preparation", it) }
        }
        activity?.let { entryActivity ->
            val generation = state.graphGeneration
            if (generation != null && foreground && state.productAvailable && state.onboarding?.completed == true &&
                product.sharedOnboardingAuthority().let { it.first == generation && it.second?.completed == true }) {
                NativeDeepLinkEntryRegistry.process.navigation(entryActivity.nativeDeepLinkEntryID, entryActivity, entryActivity.nativeHostID, generation)?.let { request ->
                    if (security?.isAuthorized(ProtectedSurface.APP_UNLOCK) == true &&
                        (request.surface == null || security.isAuthorized(request.surface))) {
                        projected.put("navigation", JSONObject().put("serial", request.serial).put("tab", request.tab).put("screen", request.screen))
                    }
                }
            }
        }
        foregroundClosing?.let { projected.put("foregroundClosing", JSONArray(listOf(it))) }
        return projected.toString()
    }

    /**
     * Confirmed account projection, or `null` when no account backend is installed (pre-unlock /
     * unavailable build). The owner snapshot carries only availability, provider identifiers and
     * generation; the bridge adds provider capability and the transient command state.
     */
    private fun accountPresentation(): AccountPresentation? {
        val backend = application.accountBackend.value ?: return null
        return projectAccount(
            snapshot = backend.sessions.state.value,
            googleAvailable = backend.googleSignIn.configured,
            appleAvailable = backend.appleSignIn.configured,
            signInProvider = accountSignInProvider,
            deleting = accountDeleting,
            message = accountMessage,
        )
    }

    /**
     * The locale the app is serving, in the same shape the iOS `presentation.locale` uses
     * (`zh-Hant`/`zh-Hans`/`pt-BR` for the script/region variants Android resource qualifiers
     * distinguish; the bare language otherwise).
     */
    private fun presentationLocale(): String = nativePresentationLocale(Locale.getDefault())

    /** Return the exact observer projection for the corresponding command receipt. */
    private fun publish(host: NativeVisitHost? = currentVisitHost(), commandReceipt: Boolean = false): String? = runCatching {
        if (!snapshotHostStillCurrent(host)) return@runCatching null
        val composition = application.chainedComposition.value
        val generation = product.state.value.graphGeneration
        val value = snapshot(host)
        // Main may resume B during A's IO snapshot construction. Never
        // emit mixed-owner fields under A's immutable host receipt.
        if (!snapshotHostStillCurrent(host) || application.chainedComposition.value !== composition ||
            product.state.value.graphGeneration != generation) return@runCatching null
        snapshotEmission.emit(value, commandReceipt) { delivered ->
            context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(SNAPSHOT_EVENT, delivered)
        }
        value
    }.getOrNull()

    private fun publishedResponse(response: String, host: NativeVisitHost?): String {
        val projection = publish(host, commandReceipt = true)
        // A paint failure cannot undo an already committed mutation or replace its truthful
        // cancellation/refusal. It carries no successor fields and is a separate receipt fact.
        val failure = if (snapshotHostStillCurrent(host)) NativeCommandProjectionFailure.UNAVAILABLE
            else NativeCommandProjectionFailure.STALE_HOST
        return nativeCommandPublication(response, projection, failure)
    }

    fun command(request: String, promise: Promise) {
        scope.launch {
            try {
                val input = JSONObject(request)
                if (input.optInt("schema") == 1 && input.optString("type") == "domainCheck.cancel") {
                    val parsed = ProductCommandParser.parse("domainCheck.cancel", JsonProductCommandInput(input))
                    val cancel = (parsed as? ProductCommandParse.Supported)?.command as? ProductDomainCheckCommand.Cancel
                    if (cancel == null) promise.resolve(result("rejected", "invalid-argument"))
                    else {
                        domainChecks.cancel(cancel.visit, input.optString("nativeHostID"))
                        promise.resolve(result("completed"))
                    }
                    return@launch
                }
                if (input.opt("type") == "vpn.retire") {
                    promise.resolve(withContext(Dispatchers.Main.immediate) { retireVpnDraftRoute(input) })
                    return@launch
                }
                if (!ownsCommandHost(input)) {
                    promise.resolve(result("rejected", "stale-host"))
                    return@launch
                }
                val commandHost = currentVisitHost()?.takeIf { input.opt("nativeHostID") == it.nativeHostID }
                // Security recovery is public and read-free; it cannot grant access or run an app action.
                if (input.optInt("schema") == 1 && input.optString("type") == "presentation.retry") {
                    val activity = commandHost?.owner as? LavaReactActivity
                    val generation = product.state.value.graphGeneration
                    val accepted = activity != null && activity.foreground && generation != null &&
                        product.securityRetryAvailability(activity, generation)
                    promise.resolve(if (accepted && commandHost.matches(currentVisitHost())) result("completed") else result("rejected", "stale-host"))
                    return@launch
                }
                // Cold retry controls acknowledge fixed public paint only. Never build a
                // private response snapshot or run a mutation/authorization/publication lane.
                if (input.optInt("schema") == 1 && input.optString("type") in
                    setOf("navigation.retryPresentation", "navigation.retryPresentationReady")) {
                    val capturedHost = currentVisitHost()
                    val response = withContext(Dispatchers.Main.immediate) {
                        if (capturedHost == null || input.opt("nativeHostID") != capturedHost.nativeHostID ||
                            !capturedHost.matches(currentVisitHost()) || !ownsCommandHost(input)) {
                            result("rejected", "stale-host")
                        } else {
                            val activity = capturedHost.owner as? LavaReactActivity
                            val generation = product.state.value.graphGeneration
                            val serial = (input.opt("serial") as? Number)?.toDouble()
                            if (activity == null || !activity.foreground || generation == null || serial == null ||
                                !serial.isFinite() || serial < 1 || serial > 9_007_199_254_740_991.0 || serial % 1.0 != 0.0) {
                                result("rejected", "stale-presentation")
                            } else if (input.optString("type") == "navigation.retryPresentation") {
                                if (activity.prepareRuntimeRetry(generation, serial.toLong())) {
                                    result("completed", value = JSONObject().put("generation", generation.toString()).put("serial", serial.toLong()))
                                } else result("rejected", "stale-presentation")
                            } else if (input.optString("generation").toLongOrNull() == generation &&
                                activity.acknowledgeRuntimeRetry(generation, serial.toLong())) {
                                result("completed", value = true)
                            } else result("rejected", "stale-presentation")
                        }
                    }
                    promise.resolve(response)
                    return@launch
                }
                // The shared Guard composition emits effect-only gestures. End must revoke a
                // ramp even after background/graph retirement and never waits for a prompt.
                if (input.optInt("schema") == 1 && input.optString("type") == "guard.gesture") {
                    val outcome = withContext(Dispatchers.Main.immediate) {
                        if (!ownsCommandHost(input)) return@withContext null
                        guardGestureHaptics.handle(input.optString("gesture"))
                    }
                    promise.resolve(if (outcome == null) result("rejected", "stale-host") else if (outcome == com.lavasec.app.haptics.GuardGestureHapticOutcome.UNKNOWN_GESTURE)
                        result("rejected", "invalid-guard-gesture") else result("completed"))
                    return@launch
                }
                // Haptics are effect-only: they mutate no state, so they bypass admission (a
                // native prompt must not swallow feedback) and skip the snapshot publish (a
                // haptic changes nothing the JS tree renders). An unknown `kind` is still a
                // typed rejection, never a silent success.
                if (input.optInt("schema") == 1 && input.optString("type") == "haptic") {
                    promise.resolve(withContext(Dispatchers.Main.immediate) {
                        if (ownsCommandHost(input)) playHaptic(input) else result("rejected", "stale-host")
                    })
                    return@launch
                }
                // Import cancellation is read-free and bypasses the mutation/picker lane.
                // The unguessable visit ID can only retire that visit, never a successor.
                if (input.optInt("schema") == 1 && input.optString("type") == "native.flow" &&
                    input.optString("flow") == "importCancel") {
                    withContext(Dispatchers.Main.immediate) {
                        if (input.optString("visitID") == importVisit?.id) retireImportVisit()
                    }
                    promise.resolve(result("completed"))
                    return@launch
                }
                // Controls must stay responsive while the original filter.apply owns mutation
                // admission. Only exact cancellation is read-free; all other controls recheck
                // current native host and FILTERS authority in the owner.
                if (input.optInt("schema") == 1 && input.optString("type") == "activity.share.retire") {
                    withContext(Dispatchers.Main.immediate) { activityShareCards.revoke(input.optString("token")) }
                    promise.resolve(result("completed"))
                    return@launch
                }
                if (input.optInt("schema") == 1 && input.optString("type") == "accountBackup.command") {
                    val response = withContext(Dispatchers.Main.immediate) {
                        val flow = accountBackupFlow
                        val closing = input.optString("action") in setOf("close", "back")
                        val accepted = if (closing) flow?.cancel(input.optString("id")) == true
                            else ownsCommandHost(input) && flow?.command(input) == true
                        result(if (accepted || closing) "completed" else "rejected",
                            if (accepted || closing) null else "stale-account-backup")
                    }
                    // Exact retirement returns no private or successor snapshot.
                    if (input.optString("action") in setOf("close", "back")) promise.resolve(response)
                    else promise.resolve(publishedResponse(response, commandHost))
                    return@launch
                }
                if (input.optInt("schema") == 1 && input.optString("type").startsWith("preparation.")) {
                    val response = withContext(Dispatchers.Main.immediate) {
                        val type = input.optString("type")
                        val accepted = if (type == "preparation.cancel")
                            preparationOwner.cancel(input.optString("id"))
                        else ownsCommandHost(input) && preparationOwner.command(type, input.optString("id"))
                        if (accepted) result("completed")
                        else result("rejected", "stale-preparation")
                    }
                    if (input.optString("type") == "preparation.cancel") {
                        promise.resolve(response)
                        return@launch
                    }
                    if (commandHost == null) {
                        publish()
                        promise.resolve(nativeCommandPublication(response, null, NativeCommandProjectionFailure.STALE_HOST))
                    } else promise.resolve(publishedResponse(response, commandHost))
                    return@launch
                }
                // Revocation never waits behind a native prompt or another mutation. It only
                // removes access, so background/stale callers may revoke but cannot grant.
                if (input.optInt("schema") == 1 && input.optString("type") == "navigation.endTurn") {
                    val response = withContext(Dispatchers.Main.immediate) {
                        if (!ownsCommandHost(input)) result("rejected", "stale-host") else {
                            product.securityResetAuthenticationTurn()
                            result("completed")
                        }
                    }
                    promise.resolve(publishedResponse(response, commandHost))
                    return@launch
                }
                // A share-card grant that is no longer the live export capability is stale
                // regardless of admission. Validate it BEFORE the single mutation lane so a
                // concurrent read (e.g. the refocus `share.query`, which renders the QR/PNG off
                // the main dispatcher) cannot mask a revoked token as `busy`. A token that is
                // still live enters admission unchanged and may still legitimately report `busy`.
                if (input.optInt("schema") == 1 && input.optString("type") == "share.card" &&
                    shareCards.authorizedPayload(input.optString("token")) == null
                ) {
                    promise.resolve(result("rejected", "stale-share"))
                    return@launch
                }
                val type = input.optString("type")
                if (input.optInt("schema") == 1 && input.has("retirementGeneration") &&
                    type in setOf("filter.cancel", "customEntry.dismiss")) {
                    val response = withContext(Dispatchers.Main.immediate) { retirePlusDestination(input) }
                    // This exact read-free capability can retire an old host's draft. Do not
                    // return a successor host's private projection to that retired caller.
                    if (commandHost == null) {
                        publish()
                        promise.resolve(nativeCommandPublication(response, null, NativeCommandProjectionFailure.STALE_HOST))
                    }
                    else promise.resolve(publishedResponse(response, commandHost))
                    return@launch
                }
                if (input.optInt("schema") == 1 && (isSharedForegroundStart(input) ||
                        type.startsWith("foreground.") || type.startsWith("feedback.") || type.startsWith("onboarding.") || type.startsWith("vpnEditor.") ||
                        type in setOf("vpn.begin", "vpn.edit") ||
                        type == "native.flow" && input.optString("flow") in setOf("onboarding", "onboardingPreview") ||
                        type in setOf("navigation.presentation", "navigation.presentationReady", "navigation.accept"))) {
                    val response = withContext(Dispatchers.Main.immediate) {
                        try {
                            val response = if (!ownsCommandHost(input)) result("rejected", "stale-host")
                                else if (isSharedForegroundStart(input)) beginSharedForeground(input) else execute(input)
                            debugForegroundEntryFailure(input, response)
                            response
                        } catch (failure: Exception) {
                            debugForegroundEntryFailure(input, exception = failure.javaClass.simpleName)
                            throw failure
                        }
                    }
                    sharedForeground.authorityChanged()
                    sharedVpnEditor.authorityChanged()
                    promise.resolve(publishedResponse(response, commandHost))
                    return@launch
                }
                // Protection uses its own lane so a suspended filter publication cannot swallow
                // Stop/Pause. SecurityOwner still admits only one authentication request, and
                // the activity keeps a single pending native flow; neither prompt gate is bypassed.
                val response = admission.run(input.optString("type")) {
                    val response = withContext(Dispatchers.Main.immediate) {
                        try {
                            val response = execute(input)
                            debugForegroundEntryFailure(input, response)
                            response
                        } catch (failure: Exception) {
                            debugForegroundEntryFailure(input, exception = failure.javaClass.simpleName)
                            throw failure
                        }
                    }
                    sharedForeground.authorityChanged()
                    sharedVpnEditor.authorityChanged()
                    publishedResponse(response, commandHost)
                } ?: result("rejected", "busy")
                // A completion callback may immediately submit the next command. Release the
                // completed operation before resolving so it cannot reject that command as busy.
                promise.resolve(response)
            } catch (cancelled: CancellationException) {
                promise.resolve(result("cancelled", "host-closed"))
                throw cancelled
            } catch (_: Exception) {
                promise.resolve(result("rejected", "operation-failed"))
            }
        }
    }

    /**
     * Renders the canonical `haptic` command on the main thread. Effect-only: no generation gate
     * (a stale UI generation must not silence feedback) and no snapshot publish. An unknown
     * semantic returns a typed rejection (`invalid-haptic`), never a successful no-op.
     */
    private fun playHaptic(request: JSONObject): String {
        val activity = context.currentActivity as? LavaReactActivity
            ?: return result("rejected", "host-unavailable")
        if (!activity.foreground) return result("rejected", "background")
        val view = activity.window?.decorView ?: return result("rejected", "unavailable")
        val handler = HapticCommandHandler(
            // Lava haptics default on; a missing customization state reads as enabled, matching iOS.
            hapticsEnabled = { product.state.value.customization?.haptics ?: true },
            sink = AndroidHapticEffectPlayer(view),
        )
        return when (handler.handle(request.optString("kind"))) {
            HapticCommandOutcome.PLAYED, HapticCommandOutcome.SUPPRESSED -> result("completed")
            HapticCommandOutcome.UNKNOWN_SEMANTIC -> result("rejected", "invalid-haptic")
        }
    }

    private suspend fun executeLinkedAccountFlow(generation: Long, activity: LavaReactActivity): String {
        val host = NativeVisitHost(activity, activity.nativeHostID) { activity.nativeHostAlive }
        val backend = application.accountBackend.value ?: return result("rejected", "unavailable")
        val account = backend.sessions.state.value
        if (account.availability != AccountAvailability.SIGNED_IN || account.userId == null) {
            return result("rejected", "sign-in-required")
        }
        val security = product.securityStateForSnapshot(generation) ?: return result("rejected", "unavailable")
        // Account labels are App Settings reads. Authorize before presenting any native
        // identity, and keep the original account/host/turn through the authentication await.
        val readAuthorized = product.securityAuthorize(generation, ProtectedSurface.APP_SETTINGS) { _, verify, cancel ->
            activity.presentPasscodeAuthentication(verify, cancel)
        }
        if (!readAuthorized) return result("cancelled", "authentication")
        if (!host.matches(currentVisitHost()) || !activity.foreground || activity.isFinishing || activity.isDestroyed ||
            product.state.value.graphGeneration != generation || application.accountBackend.value !== backend ||
            backend.sessions.state.value != account ||
            product.securityStateForSnapshot(generation)?.authenticationRevision != security.authenticationRevision) {
            return result("rejected", "stale-account")
        }
        fun readable(): Boolean = host.matches(currentVisitHost()) && activity.foreground &&
            !activity.isFinishing && !activity.isDestroyed && product.state.value.graphGeneration == generation &&
            product.securityStateForSnapshot(generation)?.let {
                it.isAuthorized(ProtectedSurface.APP_UNLOCK) && it.isAuthorized(ProtectedSurface.APP_SETTINGS)
            } == true
        var mutationAuthorization: (() -> Boolean)? = null
        val visit = NativeLinkedAccountVisit(
            host = host, generation = generation, backend = backend, account = account,
            authorityRevision = security.authenticationRevision,
            currentHost = ::currentVisitHost,
            currentGeneration = { product.state.value.graphGeneration },
            currentBackend = { application.accountBackend.value },
            currentAccount = { backend.sessions.state.value },
            currentAuthorityRevision = { product.securityStateForSnapshot(generation)?.authenticationRevision },
            canRead = ::readable,
            canMutate = { readable() && mutationAuthorization?.invoke() == true },
            authorize = {
                val admitted = product.securityAuthorize(generation, ProtectedSurface.APP_SETTINGS) { _, verify, cancel ->
                    activity.presentPasscodeAuthentication(verify, cancel)
                }
                mutationAuthorization = if (admitted)
                    product.captureMutationAuthorizationFence(generation, ProtectedSurface.APP_SETTINGS) else null
                admitted && mutationAuthorization != null
            },
            signOut = { accountGeneration, isCurrent -> backend.googleSignIn.signOut(accountGeneration, isCurrent) },
            deleteAccount = { accountGeneration, isCurrent ->
                val cloud = backend.cloud ?: throw AccountUnavailable()
                withContext(Dispatchers.IO) { cloud.deleteAccount(accountGeneration, isCurrent) }
            },
        )
        val flow = openAccountBackupFlow("account", generation, activity) ?: return result("rejected", "foreground-busy")
        val stop = flow.observe { if (!flow.retained() && !visit.completingRevocation()) visit.cancel() }
        try {
            flow.configureAccount(visit.connections() ?: return result("rejected", "stale-account"))
            while (flow.retained()) {
                val command = flow.awaitAction("account.signOut", "account.delete")
                flow.working(true)
                val outcome = visit.perform(if (command.optString("action") == "account.delete")
                    NativeLinkedAccountAction.DELETE else NativeLinkedAccountAction.SIGN_OUT,
                    confirmed = command.optBoolean("confirmed"))
                when (outcome) {
                    NativeLinkedAccountOutcome.COMPLETED -> return result("completed")
                    NativeLinkedAccountOutcome.STALE -> return result("rejected", "stale-account")
                    NativeLinkedAccountOutcome.FAILED, NativeLinkedAccountOutcome.REFUSED -> flow.failure(activity.linkedAccountCopy().failure)
                    else -> flow.working(false)
                }
            }
            return result("cancelled", "dismissed")
        } finally { stop(); visit.cancel(); retireAccountBackupFlow(flow) }
    }

    private suspend fun execute(request: JSONObject): String {
        if (!ownsCommandHost(request)) return result("rejected", "stale-host")
        if (request.optInt("schema") != 1) return result("rejected", "unsupported-schema")
        val activity = context.currentActivity as? LavaReactActivity
            ?: return result("rejected", "host-unavailable")
        if (!activity.foreground) return result("rejected", "background")
        val sampled = product.state.value
        val before = sampled.copy(security = product.securityStateForSnapshot(sampled.graphGeneration))
        // `vpn.begin`/`vpn.rowToggle` carry the committed ROTATION generation under `generation`
        // and the app graph generation under `productGeneration`; every other command carries the
        // graph generation under `generation`. Prefer the explicit graph field when present.
        val generation = (request.optString("productGeneration").takeIf(String::isNotEmpty)
            ?: request.optString("generation")).toLongOrNull()
        if (generation == null || generation != before.graphGeneration) {
            return result("rejected", "stale-generation")
        }
        if (request.optString("type") == "navigation.presentation") {
            if (!nativePresentationRevisionMatches(request.opt("readRevision"), before.security?.authenticationRevision)) {
                return result("rejected", "stale-presentation")
            }
            val name = request.optString("surface")
            val surface = when (name) {
                "activityViewing" -> ProtectedSurface.ACTIVITY
                "filterEditing" -> ProtectedSurface.FILTERS
                "appSettings" -> ProtectedSurface.APP_SETTINGS
                "none", "credentials" -> null
                else -> return result("rejected", "unsupported-surface")
            }
            val accepted = activity.setProtectedPresentation(generation, request.optLong("serial", -1L),
                surface, credentials = name == "credentials", ownerID = request.optString("ownerID").takeIf(String::isNotEmpty),
                readRevision = request.optString("readRevision").toLongOrNull())
            return result(if (accepted) "completed" else "rejected", if (accepted) null else "stale-presentation")
        }
        if (request.optString("type") == "navigation.presentationReady") {
            if (!nativePresentationRevisionMatches(request.opt("readRevision"), before.security?.authenticationRevision)) {
                return result("rejected", "stale-presentation")
            }
            val accepted = activity.setPresentationReady(generation, request.optLong("serial", -1L),
                ownerID = request.optString("ownerID").takeIf(String::isNotEmpty),
                readRevision = request.optString("readRevision").toLongOrNull())
            return result(if (accepted) "completed" else "rejected", if (accepted) null else "stale-presentation")
        }
        // `domains.cancel` is the teardown of an app-owned staged review: it reads nothing and the
        // handler discards only the exact owned token/generation, so it must clear the App-Unlock
        // gate exactly as it clears the ACTIVITY gate in `executeDomainCommand`. Otherwise the store's
        // queued cancel replay is refused as `app-locked` and strands the abandoned draft as
        // "Unsaved filter edits". The following entry/retirement commands are explicit
        // exemptions. Foreground/feedback/VPN entry handlers separately renew App Unlock and
        // their actual displayed surface; exact foreground dismissal only retires its UUID.
        // Every other locked command remains refused.
        if (before.isLocked(ProtectedSurface.APP_UNLOCK, System.currentTimeMillis()) &&
            !(request.optString("type") == "navigation.authorize" && request.optString("surface") == "appUnlock") &&
            request.optString("type") !in setOf("domains.cancel", "foreground.dismiss", "foreground.enter", "feedback.enter", "vpnEditor.enter")) {
            return result("rejected", "app-locked")
        }
        val entryCommand = request.optString("type") in setOf("foreground.enter", "feedback.enter", "vpnEditor.enter")
        if (entryCommand) {
            val id = request.optString("id")
            val vpnEntry = id == sharedVpnEditor.currentId
            val backupEntry = id == accountBackupFlow?.takeIf { it.retained() }?.id
            val kind = sharedForeground.currentKind
            if (!backupEntry && !vpnEntry && (id != sharedForeground.currentId || kind == null)) return result("rejected", "foreground-stale")
            val surface = if (backupEntry || vpnEntry || kind == NativeSharedForegroundKind.FEEDBACK) ProtectedSurface.APP_SETTINGS
                else if (kind in setOf(NativeSharedForegroundKind.CREATE_FILTER, NativeSharedForegroundKind.RENAME_FILTER,
                    NativeSharedForegroundKind.DELETE_FILTERS)) ProtectedSurface.FILTERS else null
            val present: NativeSecurityPresenter = { _, verify, cancel -> activity.presentPasscodeAuthentication(verify, cancel) }
            if (!product.securityAuthorize(generation, ProtectedSurface.APP_UNLOCK, present = present) ||
                surface != null && !product.securityAuthorize(generation, surface, present = present)) return result("cancelled", "authentication")
            if (context.currentActivity !== activity || activity.isDestroyed || !activity.foreground || product.state.value.graphGeneration != generation ||
                (if (backupEntry) accountBackupFlow?.takeIf { it.retained() }?.id else if (vpnEntry) sharedVpnEditor.currentId else sharedForeground.currentId) != id) return result("rejected", "foreground-stale")
            if (backupEntry) {
                if (accountBackupFlow?.kind == "backupRestore" &&
                    !product.securityAuthorize(generation, ProtectedSurface.FILTERS, present = present)) return result("cancelled", "authentication")
                return result("completed")
            }
        }
        if (request.optString("type").startsWith("onboarding.")) {
            return sharedOnboarding.execute(generation, request).toString()
        }
        if (request.optString("type") == "native.flow" && request.optString("flow") in setOf("onboarding", "onboardingPreview")) {
            val mock = request.optString("flow") == "onboardingPreview"
            if (mock && !BuildConfig.DEBUG) return result("rejected", "debug-only")
            val authority = product.sharedOnboardingAuthority()
            if (!mock && authority.first == generation && authority.second?.reconstructionRequired == true) {
                // Reconstruct from durable saved facts. Never replay an ambiguous wizard draft.
                return if (product.retryOnboarding(generation)) result("completed")
                    else result("rejected", "onboarding-recovery-busy")
            }
            val id = sharedOnboarding.enter(generation, mock = mock) ?: return result("rejected", "onboarding-unavailable")
            publish()
            return result(if (sharedOnboarding.awaitCompletion(id)) "completed" else "cancelled")
        }
        if (request.optString("type") == "system.open") {
            if (request.optString("target") != "settings") return result("rejected", "unsupported-target")
            activity.startActivity(Intent(Settings.ACTION_SETTINGS))
            return result("completed")
        }
        if (request.optString("type") in setOf("customEntry.enter", "customEntry.save")) {
            return executeCustomEntry(request, generation, activity)
        }
        if (request.optString("type") == "vpn.enter") {
            val accepted = product.securityAuthorize(generation, ProtectedSurface.APP_SETTINGS) { _, verify, cancel ->
                activity.presentPasscodeAuthentication(verify, cancel)
            }
            return result(if (accepted) "completed" else "cancelled")
        }
        if (request.optString("type") == "navigation.accept") {
            val entries = NativeDeepLinkEntryRegistry.process
            val navigation = entries.navigation(activity.nativeDeepLinkEntryID, activity, activity.nativeHostID, generation)
                ?: return result("rejected", "stale-navigation")
            val authority = product.sharedOnboardingAuthority()
            if (navigation.serial != request.optLong("serial", -1L) || authority.first != generation || authority.second?.completed != true ||
                !activity.hasPresentationReceipt(generation, request.optLong("presentationSerial", -1L)) ||
                navigation.surface != null && product.securityStateForSnapshot(generation)?.isAuthorized(navigation.surface) != true) {
                return result("rejected", "stale-navigation")
            }
            val accepted = entries.accept(activity.nativeDeepLinkEntryID, activity, activity.nativeHostID, generation, navigation.serial, request.optLong("presentationSerial", -1L))
            return result(if (accepted) "completed" else "rejected", if (accepted) null else "stale-navigation")
        }
        if (request.optString("type").startsWith("vpnEditor.") ||
            request.optString("type").startsWith("foreground.") && request.optString("id") == sharedVpnEditor.currentId) {
            val input = if (request.optString("type") == "foreground.enter")
                JSONObject(request.toString()).put("type", "vpnEditor.enter") else request
            return sharedVpnEditor.execute(input).toJson()
        }
        if (request.optString("type").startsWith("foreground.") || request.optString("type").startsWith("feedback.")) {
            return sharedForeground.execute(request).toJson()
        }
        return when (request.optString("type")) {
            "navigation.authorize" -> {
                // Match canonical wire names; unsupported surfaces remain explicit until their
                // owner/command gates are ported. Never reinterpret an unknown purpose as none.
                val surface = when (request.optString("surface")) {
                    "activityViewing" -> ProtectedSurface.ACTIVITY
                    "filterEditing" -> ProtectedSurface.FILTERS
                    "appSettings" -> ProtectedSurface.APP_SETTINGS
                    "appUnlock" -> ProtectedSurface.APP_UNLOCK
                    "credentials" -> null
                    else -> return result("rejected", "unsupported-surface")
                }
                if (request.optBoolean("newTurn")) {
                    product.securityResetAuthenticationTurn()
                    if (surface != ProtectedSurface.APP_UNLOCK && !product.securityAuthorize(
                            generation, ProtectedSurface.APP_UNLOCK,
                        ) { _, verify, cancel -> activity.presentPasscodeAuthentication(verify, cancel) }) {
                        return result("cancelled", "authentication")
                    }
                }
                val accepted = product.securityAuthorize(generation, surface) { _, verify, cancel ->
                    activity.presentPasscodeAuthentication(verify, cancel)
                }
                result(if (accepted && activity.foreground) "completed" else "cancelled")
            }
            "backup.export", "backup.import", "backup.pending.resume", "backup.pending.discard" -> {
                if (request.keys().asSequence().any { it !in setOf("schema", "type", "generation", "nativeHostID") })
                    return result("rejected", "unsupported-argument")
                executePortableBackup(generation, activity, action = request.optString("type"))
            }
            "native.flow" -> {
                val route = request.optString("flow")
                if (!AppEdition.current.accounts && route in setOf("account", "backupSetup", "backupRestore", "plus")) {
                    return result("rejected", "edition-unavailable")
                }
                if (route == "importBegin") {
                    if (!product.securityAuthorize(generation, ProtectedSurface.FILTERS) { _, verify, cancel ->
                            activity.presentPasscodeAuthentication(verify, cancel)
                        }) return result("cancelled", "authentication")
                    val host = currentVisitHost() ?: return result("rejected", "stale-host")
                    if (!activity.foreground || product.state.value.graphGeneration != generation)
                        return result("rejected", "stale-generation")
                    retireImportVisit()
                    val visit = NativeImportVisit(host, generation)
                    importVisit = visit
                    return result("completed", value = JSONObject().put("visitID", visit.id))
                }
                if (route == "importTarget") {
                    if (!ownsImportReview(request, generation)) return result("rejected", "stale-review")
                    val raw = importReviewRaw ?: return result("rejected", "stale-review")
                    return reviewImport(raw, request.optString("target").takeIf(String::isNotBlank), generation)
                }
                // RN arranges stages; native acquisition, decoding and confirmation stay here.
                if (NativeProductRoutes.importPresentationUnavailable(route)) {
                    return executeImportFlow(route, request, before, activity)
                }
                if (route == NativeProductRoutes.IMPORT_APPLY) {
                    val response = executeImportApply(request, before, generation, activity)
                    // A reviewed import commits the filter library directly (never through a parsed
                    // `filter.*` command), so it arms the automatic-upload coordinator here.
                    if (response.isCompletedCommandResult()) if (AppEdition.current.cloudBackup) backupAutomaticCoordinator.onConfigurationChanged()
                    return response
                }
                // Shared Account stays in RN; the connected-provider action is a native leaf
                // containing real session labels and guarded sign-out/deletion, as on iOS.
                if (route == NativeFlow.ACCOUNT.wireName) {
                    return executeLinkedAccountFlow(generation, activity)
                }
                // Canonical passcode management: the shared Security screen dispatches this to open
                // the credential flow. The owner remains the only authority and the dialog keeps the
                // passcode native; no credential material crosses the bridge.
                if (route == NativeFlow.PASSCODE.wireName) {
                    val security = product.securityStateForSnapshot(generation)
                        ?: return result("rejected", "unavailable")
                    val completed = activity.presentPasscodeManagement(
                        enabled = security.enabled,
                        setPasscode = { code -> product.securitySetPasscodeResult(generation, code) },
                        changePasscode = { current, new -> product.securityChangePasscodeResult(generation, current, new) },
                        removePasscode = { code -> product.securityRemovePasscodeResult(generation, code) },
                    )
                    return result(if (completed) "completed" else "cancelled")
                }
                // The reviewed restore-apply owner is wired to the live product and the production
                // unlock source downloads + decrypts the account's envelope; the secret is collected
                // and the phrase kept entirely native, so no restore material crosses the bridge.
                if (route == NativeFlow.BACKUP_RESTORE.wireName) {
                    return executeBackupRestoreFlow(before, generation, activity)
                }
                // Setup seals the live portable payload with a recovery phrase and uploads it
                // through the account-bound cloud client. The phrase is generated and confirmed in
                // the native dialog; only the completed/refused status crosses the bridge.
                if (route == NativeFlow.BACKUP_SETUP.wireName) {
                    return executeBackupSetupFlow(generation, activity)
                }
                val navigation = NativeProductRoutes.navigation(route)
                // A canonical `native.flow` that is also an Android navigation destination is
                // presented through the single `LavaNativeFlowHost` so its build/QA gate and its
                // graph/account binding are enforced in exactly one place. An Android-only
                // navigation route (no canonical flow) stays on the direct path — the host's
                // canonical inventory would refuse it — and a canonical-only route with no
                // Android presentation keeps its typed `invalid-flow` refusal rather than opening
                // the product UI.
                if (NativeFlowInventory.isCanonicalFlowName(route) && navigation != null) {
                    val hostRequest = LavaNativeFlowRequest(
                        requestId = flowRequestId.incrementAndGet(),
                        flow = route,
                        graphGeneration = generation,
                        accountId = application.accountBackend.value?.sessions?.state?.value?.userId,
                    )
                    val outcome = presentNativeFlow(nativeFlowHost(activity), hostRequest)
                    return result(outcome.status, outcome.reason)
                }
                if (navigation == null) return result("rejected", "invalid-flow")
                val completed = activity.presentNative(
                    Intent(activity, MainActivity::class.java).putExtra(MainActivity.NATIVE_FLOW_ROUTE, route),
                ) == Activity.RESULT_OK
                result(if (completed) "completed" else "cancelled")
            }
            "protection.toggle" -> {
                if (!before.productAvailable || before.onboarding?.completed != true) {
                    return result("rejected", "setup-required")
                }
                val action = before.protection?.guard?.primaryAction
                    ?: return result("rejected", "unavailable")
                if (action.wireCode() != request.optString("action")) return result("rejected", "stale-action")
                if (action == GuardPrimaryAction.TurnOn) {
                    val consent = VpnService.prepare(activity)
                    if (consent != null && activity.presentNative(consent) != Activity.RESULT_OK) {
                        return result("cancelled", "vpn-consent")
                    }
                }
                val outcome = product.performGuardActionForGeneration(generation, action) { _, verify, cancel ->
                    activity.presentPasscodeAuthentication(verify, cancel)
                }
                if (outcome is ProtectionCommandResult.Completed) {
                    outcome.settings?.let { activity.startActivity(settingsIntent(it)) }
                }
                protectionResult(outcome)
            }
            "protection.pause" -> {
                if (before.protection?.guard?.canPause != true) return result("rejected", "unavailable")
                val duration = PauseDuration.fromMinutes(request.optInt("minutes"))
                    ?: return result("rejected", "invalid-duration")
                protectionResult(product.performPause(duration, generation) { _, verify, cancel ->
                    activity.presentPasscodeAuthentication(verify, cancel)
                })
            }
            "purchase.buy", "purchase.restore", "purchase.refresh", "purchase.clearMessage", "purchase.manage" -> {
                if (!AppEdition.current.purchases) return result("rejected", "edition-unavailable")
                val backend = application.plusBackend.value ?: return result("rejected", "unavailable")
                if (!before.productAvailable) return result("rejected", "unavailable")
                when (request.optString("type")) {
                    "purchase.buy" -> {
                        val accepted = backend.controller.buy(request.optString("id")) {
                            backend.billing.launch(activity, request.optString("id")) {
                                // Sample after Billing connection/WorkManager suspension, immediately
                                // before the prompt. An account is optional; a retired host is not.
                                check(activity.foreground && application.plusBackend.value === backend &&
                                    product.state.value.graphGeneration == generation)
                                val account = application.accountBackend.value?.sessions?.state?.value
                                account?.userId?.takeIf {
                                    account.availability == com.lavasec.app.account.AccountAvailability.SIGNED_IN
                                }?.let(com.lavasec.app.account.GooglePlayAccountBinding::forUser)
                            }
                        }
                        result(if (accepted) "completed" else "rejected")
                    }
                    "purchase.restore" -> result(if (backend.controller.restore()) "completed" else "rejected")
                    "purchase.refresh" -> {
                        backend.controller.loadProducts()
                        backend.controller.refresh()
                        result("completed")
                    }
                    "purchase.clearMessage" -> { backend.controller.clearMessage(); result("completed") }
                    else -> {
                        // Closed destination; RN cannot supply an arbitrary URI or package.
                        activity.startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/account/subscriptions?package=com.lavasec.app")))
                        result("completed")
                    }
                }
            }
            "activity.refresh" -> {
                if (before.isLocked(ProtectedSurface.ACTIVITY, System.currentTimeMillis())) {
                    return result("rejected", "locked")
                }
                product.refreshActivity()
                result("completed")
            }
            "settings.set" -> {
                val command = parseSettingsCommand(request)
                    ?: return result("rejected", ProductCommandReasons.INVALID_ARGUMENT)
                // iOS authorizes every settings mutation under the app-settings boundary except
                // the security surfaces it owns directly; match that gate before any owner runs.
                if (command.key != "biometrics" && !command.key.startsWith("protectedActions.")) {
                    var authenticationCancelled = false
                    val present: NativeSecurityPresenter = { _, verify, cancel ->
                        activity.presentPasscodeAuthentication(verify) { authenticationCancelled = true; cancel() }
                    }
                    if (!authorizeCommand(
                            generation,
                            ProtectedSurface.APP_SETTINGS,
                            present,
                            { authenticationCancelled = true },
                        )
                    ) {
                        return authenticationResult(result("rejected", "unauthorized"), authenticationCancelled)
                    }
                }
                executeSettingsCommand(command).also { maybeArmAutomaticBackup(command, it) }
            }
            else -> when (val parsed = ProductCommandParser.parse(
                request.optString("type"),
                JsonProductCommandInput(request),
            )) {
                is ProductCommandParse.Supported -> {
                    val command = parsed.command
                    val response = when (command) {
                        is ProductFilterCommand -> executeFilterCommand(
                            command, before, generation, activity,
                        )
                        is ProductDnsCommand -> executeDnsCommand(command, generation, activity)
                        is ProductVpnCommand -> executeVpnCommand(command, generation, activity)
                        is ProductLibraryCommand -> executeLibraryCommand(command, generation, activity)
                        is ProductShareCommand -> executeShareCommand(command, before, activity)
                        is ProductActivityCardQuery -> executeActivityCardQuery(command, generation)
                        is ProductActivityShare -> executeActivityShare(command, activity)
                        is ProductSudokuCommand -> executeSudokuCommand(command)
                        is ProductAccountCommand -> executeAccountCommand(command, generation, activity)
                        is ProductBackupCommand -> executeBackupCommand(command)
                        is ProductSettingsCommand -> executeSettingsCommand(command)
                        is ProductQueryCommand -> executeQueryCommand(command, before, generation, activity)
                        is ProductDomainCommand -> executeDomainCommand(command, before, generation, activity)
                        is ProductDomainCheckCommand -> executeDomainCheckCommand(command, generation, activity)
                        is ProductLogsCommand -> executeLogsCommand(command, generation, activity, before)
                        ProductReportsSample -> queryOwner.reportsSample(expectedGeneration = generation).toCommandResult()
                        is ProductCatalogQuery -> executeCatalogQuery(command)
                        is ProductActivityVisibility -> executeActivityVisibility(command)
                    }
                    maybeArmAutomaticBackup(command, response)
                    response
                }
                ProductCommandParse.Unsupported -> result("rejected", ProductCommandReasons.UNSUPPORTED_COMMAND)
                ProductCommandParse.Unknown -> result("rejected", ProductCommandReasons.UNKNOWN_COMMAND)
                is ProductCommandParse.Invalid -> result("rejected", parsed.reason)
            }
        }
    }

    /**
     * Arms the encrypted-backup automatic-upload coordinator after a durable configuration mutation
     * commits. The classifier excludes reads, presentation-only effects and draft-only stages; the
     * coordinator re-checks the account/generation binding, the local envelope and the automatic
     * preference, so a redundant or stale arm can never upload another account's or generation's
     * state. Never runs for a non-completed command result, so a refusal is not a fabricated change.
     */
    private fun maybeArmAutomaticBackup(command: ProductCommand, response: String) {
        if (!AppEdition.current.cloudBackup) return
        if (!command.commitsDurableConfiguration()) return
        if (!response.isCompletedCommandResult()) return
        if (AppEdition.current.cloudBackup) backupAutomaticCoordinator.onConfigurationChanged()
    }

    /** Whether a bridge reply JSON carries a `completed` status; malformed replies are not success. */
    private fun String.isCompletedCommandResult(): Boolean =
        runCatching { JSONObject(this).optString("status") == "completed" }.getOrDefault(false)

    /**
     * Canonical `sudoku.*`. `sudoku.save` persists the parser-validated game; `sudoku.new` generates
     * a deterministic puzzle from a fresh random seed, retains it only with consent, and returns
     * the same `SudokuGame` shape the snapshot family exposes.
     */
    private fun executeSudokuCommand(command: ProductSudokuCommand): String {
        return when (command) {
            is ProductSudokuCommand.Save -> {
                if (!product.state.value.canRetainSudoku()) return result("rejected", "retention-disabled")
                sudokuStore.save(command.game)
                result("completed")
            }
            is ProductSudokuCommand.New -> {
                val game = SudokuGame.fresh(SudokuPuzzle.generate(ThreadLocalRandom.current().nextLong(), command.challenge))
                if (product.state.value.canRetainSudoku()) sudokuStore.save(game)
                result("completed", value = game.toWireJson())
            }
        }
    }

    /**
     * Canonical `account.*`. The process [AccountBackend] owns the session; the bridge only binds
     * the operation to the account generation observed at dispatch and maps the owner's typed
     * outcome. Google and Apple credentials stay in their native adapters; Apple browser OAuth
     * remains unavailable until its backend provider and redirect activation is configured.
     * No token, user id or email is ever returned.
     *
     * The account generation is captured BEFORE any provider UI or passcode prompt; the owner's
     * `beginSignIn` ticket / `requireCurrent` revalidate it at the durable commit, so a concurrent
     * sign-out or a different account signing in supersedes this operation instead of writing to
     * the wrong account.
     */
    private suspend fun executeAccountCommand(
        command: ProductAccountCommand,
        generation: Long,
        activity: LavaReactActivity,
    ): String {
        if (!AppEdition.current.accounts) return result("rejected", "edition-unavailable")
        val backend = application.accountBackend.value
            ?: return accountRejected(
                AccountCommandReasons.SERVICE_UNAVAILABLE,
                "Account is not available on this device yet.",
            )
        val accounts = backend.sessions
        val accountGeneration = accounts.state.value.generation
        return when (command) {
            ProductAccountCommand.Apple -> executeAppleSignIn(backend, activity)
            ProductAccountCommand.Google -> executeGoogleSignIn(backend, activity)
            ProductAccountCommand.SignOut -> executeAccountSignOut(backend, accountGeneration)
            ProductAccountCommand.Delete ->
                executeAccountDelete(backend, generation, accountGeneration, activity)
        }
    }

    /**
     * Runs one Apple admission through the native coordinator, then projects the message. The
     * adapter is typed-unavailable while its provider activation gate is disabled, so a tap never
     * fabricates a session; no credential ever crosses `result(...)`.
     */
    private suspend fun executeAppleSignIn(
        backend: AccountBackend,
        activity: LavaReactActivity,
    ): String {
        accountMessage = ""
        accountSignInProvider = AccountProviders.APPLE
        publish()
        return try {
            when (backend.appleSignIn.signIn(activity)) {
                NativeSignInResult.SIGNED_IN -> result("completed")
                NativeSignInResult.CANCELLED -> {
                    accountMessage = "Sign in was cancelled."
                    result("completed")
                }
                NativeSignInResult.BUSY -> accountOutcome(
                    AccountCommandReasons.BUSY, "Another sign-in is already in progress.",
                )
                NativeSignInResult.UNAVAILABLE -> accountOutcome(
                    AccountCommandReasons.APPLE_UNAVAILABLE,
                    "Apple sign-in is not available on this device.",
                )
                NativeSignInResult.SUPERSEDED -> accountOutcome(
                    AccountCommandReasons.SUPERSEDED, "The account changed while signing in.",
                )
                NativeSignInResult.FAILED -> accountOutcome(
                    AccountCommandReasons.FAILED, "Sign in couldn't be completed. Try again.",
                )
            }
        } catch (cancelled: CancellationException) {
            accountMessage = ""
            throw cancelled
        } finally {
            accountSignInProvider = null
            publish()
        }
    }

    /** Runs one Google admission through the existing native coordinator, then projects the message. */
    private suspend fun executeGoogleSignIn(
        backend: AccountBackend,
        activity: LavaReactActivity,
    ): String {
        accountMessage = ""
        accountSignInProvider = AccountProviders.GOOGLE
        publish()
        return try {
            when (backend.googleSignIn.signIn(activity)) {
                NativeSignInResult.SIGNED_IN -> result("completed")
                // A user cancel is not an error: it is shown as the account message, never an alert.
                NativeSignInResult.CANCELLED -> {
                    accountMessage = "Sign in was cancelled."
                    result("completed")
                }
                NativeSignInResult.BUSY -> accountOutcome(
                    AccountCommandReasons.BUSY, "Another sign-in is already in progress.",
                )
                NativeSignInResult.UNAVAILABLE -> accountOutcome(
                    AccountCommandReasons.GOOGLE_UNAVAILABLE,
                    "Google sign-in is not available on this device.",
                )
                NativeSignInResult.SUPERSEDED -> accountOutcome(
                    AccountCommandReasons.SUPERSEDED, "The account changed while signing in.",
                )
                NativeSignInResult.FAILED -> accountOutcome(
                    AccountCommandReasons.FAILED, "Sign in couldn't be completed. Try again.",
                )
            }
        } catch (cancelled: CancellationException) {
            accountMessage = ""
            throw cancelled
        } finally {
            accountSignInProvider = null
            publish()
        }
    }

    /**
     * Revokes local authority through the owner, then clears the provider's account cache. Bound to
     * the generation captured at dispatch so a newer sign-in is never revoked; a silent no-op when
     * no provider consent is installed.
     */
    private suspend fun executeAccountSignOut(
        backend: AccountBackend,
        accountGeneration: Long,
    ): String {
        accountMessage = "Signed out."
        return try {
            backend.googleSignIn.signOut(accountGeneration)
            result("completed")
        } catch (cancelled: CancellationException) {
            accountMessage = ""
            throw cancelled
        } catch (_: AccountSessionChanged) {
            accountOutcome(AccountCommandReasons.SUPERSEDED, "The account changed while signing out.")
        } catch (_: Exception) {
            accountOutcome(AccountCommandReasons.FAILED, "Sign out couldn't be completed. Try again.")
        }
    }

    /**
     * Server-side account deletion. Deleting is app-settings protected (matching iOS), so the
     * passcode is checked after the signed-in precondition and before any network call. The
     * deletion request carries the generation captured at dispatch and the owner revalidates it
     * around the send and the local sign-out, so a rotated session cannot delete another account.
     */
    private suspend fun executeAccountDelete(
        backend: AccountBackend,
        generation: Long,
        accountGeneration: Long,
        activity: LavaReactActivity,
    ): String {
        if (backend.sessions.state.value.availability != AccountAvailability.SIGNED_IN) {
            return accountRejected(
                AccountCommandReasons.NOT_SIGNED_IN, "Sign in before deleting your account.",
            )
        }
        var authenticationCancelled = false
        val present: NativeSecurityPresenter = { _, verify, cancel ->
            activity.presentPasscodeAuthentication(verify) { authenticationCancelled = true; cancel() }
        }
        if (!authorizeCommand(generation, ProtectedSurface.APP_SETTINGS, present, { authenticationCancelled = true })) {
            return authenticationResult(result("rejected", "unauthorized"), authenticationCancelled)
        }
        val cloud = backend.cloud ?: return accountRejected(
            AccountCommandReasons.SERVICE_UNAVAILABLE,
            "Account deletion is not available on this device.",
        )
        accountDeleting = true
        publish()
        return try {
            cloud.deleteAccount(accountGeneration)
            accountMessage = "Deleted your Lava account."
            result("completed")
        } catch (cancelled: CancellationException) {
            accountMessage = ""
            throw cancelled
        } catch (_: AccountSessionChanged) {
            accountOutcome(AccountCommandReasons.SUPERSEDED, "The account changed while deleting.")
        } catch (_: AccountUnavailable) {
            accountOutcome(AccountCommandReasons.NOT_SIGNED_IN, "Sign in before deleting your account.")
        } catch (_: Exception) {
            accountOutcome(AccountCommandReasons.FAILED, "Could not delete your account. Try again.")
        } finally {
            accountDeleting = false
            publish()
        }
    }

    /** Records the user-facing message on the projection and returns a typed rejection. */
    private fun accountRejected(reason: String, message: String): String =
        accountOutcome(reason, message)

    private fun accountOutcome(reason: String, message: String): String {
        accountMessage = message
        return result("rejected", reason)
    }

    /**
     * Canonical `dns.*` commands. Every one delegates to the shared owner: the policy and
     * the custom-draft session decide the outcome, and this only maps it to the canonical
     * wire result. DNS edits sit under the app-settings access boundary in iOS, so the
     * mutating commands require [ProtectedSurface.APP_SETTINGS]; a mismatched editor token
     * is an honest no-op. A refusal is never rendered as success.
     */
    private suspend fun executeDnsCommand(
        command: ProductDnsCommand,
        generation: Long,
        activity: LavaReactActivity,
    ): String {
        var authenticationCancelled = false
        val present: NativeSecurityPresenter = { _, verify, cancel ->
            activity.presentPasscodeAuthentication(verify) { authenticationCancelled = true; cancel() }
        }
        suspend fun authorized(): Boolean =
            authorizeCommand(generation, ProtectedSurface.APP_SETTINGS, present, { authenticationCancelled = true })

        return when (command) {
            is ProductDnsCommand.Tiers -> {
                if (!authorized()) return authenticationResult(result("rejected", "unauthorized"), authenticationCancelled)
                dnsResult(product.applyDnsTiers(command.tiers, command.context, generation))
            }
            is ProductDnsCommand.Toggle -> {
                if (!authorized()) return authenticationResult(result("rejected", "unauthorized"), authenticationCancelled)
                // The canonical toggle addresses any projected ladder row; the policy retains the
                // row in place and refuses to disable the last active one. An out-of-range index
                // is refused with a stable code rather than reinterpreted.
                if (command.index !in 0..1) return result("rejected", "invalid-tier-index")
                dnsResult(product.applyDnsTierEnabled(command.index, command.value, command.context, generation))
            }
            is ProductDnsCommand.CustomDraft -> {
                val host = currentVisitHost()?.takeIf { it.owner === activity && it.matches(currentVisitHost()) }
                    ?: return result("rejected", "custom-entry-expired")
                if (!authorized()) return authenticationResult(result("rejected", "unauthorized"), authenticationCancelled)
                if (!host.matches(currentVisitHost())) return result("rejected", "custom-entry-expired")
                val outcome = product.openCustomDnsDraft(command.choice, generation)
                if (outcome is ResolverDnsEditOutcome.DraftOpened) {
                    var retained = false
                    try {
                        val context = JSONObject(snapshot()).optJSONObject("dns")?.optString("tiersContext").orEmpty()
                        retained = rememberCustomEntry(SharedCustomEntry(outcome.token, "dns", generation, host, context = context))
                        if (!retained) return result("rejected", "custom-entry-expired")
                    } finally {
                        // Context construction can fail before a registry entry exists. Compensate
                        // only the already-opened original token/G and preserve the original failure.
                        if (!retained) product.dismissCustomEntry(outcome.token, generation)
                    }
                }
                dnsResult(outcome)
            }
            is ProductDnsCommand.Custom -> {
                val entry = synchronized(customEntries) { customEntries.values.lastOrNull {
                    it.kind == "dns" && it.generation == generation && it.host.owner === activity &&
                        it.host.matches(currentVisitHost()) && product.dnsCustomDraftToken.value == it.id } }
                    ?: return result("rejected", "custom-entry-expired")
                val admitted = customEntryHosts.captureAdmission(entry.id, entry.generation, entry.host)
                    ?: return result("rejected", "custom-entry-expired")
                if (!authorized()) return authenticationResult(result("rejected", "unauthorized"), authenticationCancelled)
                if (!admitted() || currentVisitActivity() !== activity || !activity.foreground ||
                    product.dnsCustomDraftToken.value != entry.id ||
                    synchronized(customEntries) { customEntries[entry.id] !== entry }) {
                    return result("rejected", "custom-entry-expired")
                }
                val outcome = product.saveCustomDns(
                    context = command.context,
                    name = command.name,
                    primary = command.primary,
                    secondary = command.secondary,
                    expectedGeneration = generation,
                )
                if (outcome is ResolverDnsEditOutcome.Applied) {
                    customEntryHosts.retire(entry.id, entry.generation)
                }
                dnsResult(outcome)
            }
            is ProductDnsCommand.CustomStage -> {
                if (!authorized()) return authenticationResult(result("rejected", "unauthorized"), authenticationCancelled)
                val outcome = product.stageCustomDns(command.id, command.context, command.name,
                    command.primary, command.secondary, generation)
                if (outcome is ResolverDnsEditOutcome.DraftStaged) customEntryHosts.retire(command.id, generation)
                dnsResult(outcome)
            }
            is ProductDnsCommand.DismissCustomEntry -> {
                // Local editor teardown only; never an authorization prompt, matching iOS. The same
                // opaque id may be a custom-blocklist editor token; clearing it is harmless.
                if (!customEntryHosts.retire(command.id, generation)) {
                    // Native callers can open the VM DNS editor without a Bridge form record.
                    product.dismissCustomEntry(command.id, generation)
                }
                result("completed")
            }
        }
    }

    /**
     * Canonical `library.*`: every stage delegates to the shared whole-library edit session in
     * [com.lavasec.app.MainProductViewModel]; `library.edit` authenticates once and the rest of
     * the session reuses that filter-editing grant, while `library.cancel` is local teardown.
     * Create opens its own detail draft; rename stages in this session. Only `form delete`
     * validates and commits the library batch. A new detail is first persisted by `filter.save`.
     */
    private fun customEntryProjection(): JSONObject? {
        val entry = synchronized(customEntries) { customEntries.values.lastOrNull() } ?: return null
        val generation = product.state.value.graphGeneration ?: return null
        if (entry.generation != generation || !entry.host.matches(currentVisitHost())) return null
        val kind = if (entry.kind == "dns") NativeSharedForegroundKind.FEEDBACK else NativeSharedForegroundKind.RENAME_FILTER
        if (!sharedFieldsReadable(kind)) return null
        if (entry.kind == "dns") {
            if (product.dnsCustomDraftToken.value != entry.id) return null
            val draft = product.dnsCustomDraftSession.value?.draft ?: return null
            return JSONObject().put("id", entry.id).put("kind", "dns")
                .put("name", draft.name).put("primary", draft.primaryAddress).put("secondary", draft.secondaryAddress)
                .put("allowed", product.state.value.featureLimits.allowsCustomDNS).put("overBudget", false)
        }
        val filterId = entry.filterId ?: return null
        if (entry.draftLease == null || product.sharedFilterDraftLease(generation, filterId) != entry.draftLease ||
            customBlocklistEditorSessions[entry.id] != filterId) return null
        return JSONObject().put("id", entry.id).put("kind", "blocklist").put("name", "")
            .put("primary", "").put("secondary", "").put("allowed", product.state.value.featureLimits.allowsCustomBlocklists)
            .put("overBudget", product.sharedFilterDraft(generation, filterId)?.customBlocklists?.size?.let {
                it >= com.lavasec.app.foundation.filter.CustomBlocklistSource.MAX_CUSTOM_BLOCKLISTS_PER_PROFILE
            } == true)
    }

    private suspend fun executeCustomEntry(request: JSONObject, generation: Long, activity: LavaReactActivity): String {
        val entry = customEntries[request.optString("id")] ?: return result("rejected", "custom-entry-expired")
        if (entry.generation != generation || entry.host.owner !== activity ||
            !entry.host.matches(currentVisitHost())) return result("rejected", "custom-entry-expired")
        val present: NativeSecurityPresenter = { _, verify, cancel -> activity.presentPasscodeAuthentication(verify, cancel) }
        val surface = if (entry.kind == "dns") ProtectedSurface.APP_SETTINGS else ProtectedSurface.FILTERS
        if (!product.securityAuthorize(generation, surface, present = present)) return result("cancelled", "authentication")
        if (context.currentActivity !== activity || activity.isDestroyed || !activity.foreground || product.state.value.graphGeneration != generation || customEntries[entry.id] !== entry) return result("rejected", "custom-entry-expired")
        if (request.optString("type") == "customEntry.enter") return result("completed")
        if (!activity.isPresentationReady(generation, "custom-entry:${entry.id}")) return result("rejected", "presentation-not-ready")
        if (entry.kind == "dns") {
            val outcome = product.stageCustomDns(entry.id, entry.context,
                request.optString("name"), request.optString("primary"), request.optString("secondary"), generation)
            if (outcome is ResolverDnsEditOutcome.DraftStaged) customEntryHosts.retire(entry.id, entry.generation)
            return dnsResult(outcome)
        }
        if (entry.draftLease == null || product.sharedFilterDraftLease(generation, entry.filterId.orEmpty()) != entry.draftLease) return result("rejected", "custom-entry-expired")
        val before = product.state.value
        return executeFilterCommand(ProductFilterCommand.AddCustomList(entry.id, request.optString("name"),
            request.optString("url"), com.lavasec.app.foundation.filter.BlocklistFormat.AUTO), before, generation, activity)
    }

    /** Closed metadata-only retirement: resolve the original registered capability. */
    private fun retireVpnDraftRoute(request: JSONObject): String {
        val keys = setOf("schema", "type", "id", "retirementGeneration", "nativeHostID", "retirementLease")
        if (request.keys().asSequence().toSet() != keys || request.opt("schema") != 1 ||
            request.opt("type") != "vpn.retire") return result("rejected", ProductCommandReasons.INVALID_ARGUMENT)
        val id = (request.opt("id") as? String)?.takeIf { it.isNotBlank() }
            ?: return result("rejected", ProductCommandReasons.INVALID_ARGUMENT)
        val hostID = (request.opt("nativeHostID") as? String)?.takeIf { it.isNotBlank() }
            ?: return result("rejected", ProductCommandReasons.INVALID_ARGUMENT)
        val retirementLease = (request.opt("retirementLease") as? String)?.takeIf { value ->
            runCatching { java.util.UUID.fromString(value).toString() == value }.getOrDefault(false)
        } ?: return result("rejected", ProductCommandReasons.INVALID_ARGUMENT)
        val rawGeneration = request.opt("retirementGeneration") as? String
            ?: return result("rejected", ProductCommandReasons.INVALID_ARGUMENT)
        val generation = rawGeneration.toLongOrNull()?.takeIf { it >= 0 && it.toString() == rawGeneration }
            ?: return result("rejected", ProductCommandReasons.INVALID_ARGUMENT)
        if (product.state.value.graphGeneration != generation) return result("rejected", "stale-generation")
        val composition = application.chainedComposition.value ?: return result("rejected", "unavailable")
        val owner = chainedOwner(composition)
        return if (product.nativeVpnDraftHosts.retireRoute(this, hostID, generation,
                composition, owner, id, retirementLease)) result("completed")
            else result("rejected", "stale-vpn-draft")
    }

    /** Read-free token retirement is allowed under a cover and never cancels a successor draft. */
    private suspend fun retirePlusDestination(request: JSONObject): String {
        val expectedGeneration = request.optString("retirementGeneration").toLongOrNull() ?: return result("rejected", "stale-generation")
        if (expectedGeneration != product.state.value.graphGeneration) return result("completed")
        val id = request.optString("id")
        if (request.optString("type") == "customEntry.dismiss") {
            customEntries[id]?.takeIf { it.generation == expectedGeneration }?.let {
                customEntryHosts.retire(id, expectedGeneration)
            }
        } else {
            val lease = request.optString("retirementLease").takeIf(String::isNotEmpty)
                ?: return result("rejected", "stale-destination")
            product.discardFilterDraft(id, expectedDraft = null, expectedNewFilter = null,
                expectedGeneration = expectedGeneration, expectedLease = lease)
        }
        return result("completed")
    }

    private fun sharedFieldsReadable(kind: NativeSharedForegroundKind): Boolean {
        val host = currentVisitHost() ?: return false
        val activity = host.owner as? LavaReactActivity ?: return false
        val sampled = product.state.value
        val security = product.securityStateForSnapshot(sampled.graphGeneration)
        val state = sampled.copy(security = security)
        if (!host.matches(currentVisitHost()) || !activity.foreground || security == null ||
            state.isLocked(ProtectedSurface.APP_UNLOCK, System.currentTimeMillis())) return false
        val surface = when (kind) {
            NativeSharedForegroundKind.CREATE_FILTER, NativeSharedForegroundKind.RENAME_FILTER,
            NativeSharedForegroundKind.DELETE_FILTERS -> ProtectedSurface.FILTERS
            NativeSharedForegroundKind.FEEDBACK -> ProtectedSurface.APP_SETTINGS
            else -> null
        }
        return surface == null || !state.isLocked(surface, System.currentTimeMillis())
    }

    private val debugForegroundControlEvents = java.util.concurrent.atomic.AtomicInteger()

    /** Debug-only post-handler branch facts; no private input, copy, IDs or values. */
    private fun debugForegroundEntryFailure(input: JSONObject, response: String? = null, exception: String? = null) {
        if (!BuildConfig.DEBUG) return
        val type = input.optString("type")
        val control = type in setOf(
            "foreground.dismiss", "foreground.enter", "foreground.dirty", "foreground.identity", "foreground.submit",
            "feedback.change", "feedback.topic", "feedback.diagnostics", "feedback.enter", "feedback.edit",
            "feedback.step", "feedback.prepare", "feedback.submit", "feedback.copy", "feedback.preview",
        )
        if (!(control || isSharedForegroundStart(input) || type in setOf(
                "vpn.begin", "vpn.edit", "activity.query", "domains.query", "network.query", "stats.query",
                "onboarding.back", "onboarding.navigate", "onboarding.choice", "onboarding.vpn",
                "onboarding.notifications", "onboarding.ready", "onboarding.open", "onboarding.release", "onboarding.complete"))) return
        runCatching {
            val reply = response?.let { JSONObject(it) }
            if (reply?.optString("status") == "completed" && !control) return@runCatching
            val controlEvent = if (control) {
                val previous = debugForegroundControlEvents.getAndUpdate { if (it < 256) it + 1 else it }
                if (previous >= 256) return@runCatching
                previous + 1
            } else 0
            val code = Regex("[a-zA-Z0-9.-]{1,64}")
            fun bounded(value: String?): String = value?.takeIf { code.matches(it) } ?: "redacted"
            val activity = context.currentActivity as? LavaReactActivity
            val generation = product.state.value.graphGeneration
            val requestedGeneration = (input.optString("productGeneration").takeIf(String::isNotEmpty)
                ?: input.optString("generation")).toLongOrNull()
            val security = product.securityStateForSnapshot(generation)
            val foregroundID = sharedForeground.currentId
            val host = currentVisitHost()
            android.util.Log.w("LavaForegroundEntry", "LAVA_FOREGROUND_ENTRY type=${bounded(input.optString("type"))} " +
                "status=${bounded(reply?.optString("status"))} reason=${bounded(reply?.let { it.optString("reason").ifEmpty { it.optString("code") } })} " +
                "exception=${bounded(exception)} hostPresent=${activity != null} foreground=${activity?.foreground == true} " +
                "destroyed=${activity?.isDestroyed != false} sourceMatches=${generation != null && requestedGeneration == generation} " +
                "securityPresent=${security != null} appGrant=${security?.isAuthorized(ProtectedSurface.APP_UNLOCK) == true} " +
                "activityGrant=${security?.isAuthorized(ProtectedSurface.ACTIVITY) == true} " +
                "filterGrant=${security?.isAuthorized(ProtectedSurface.FILTERS) == true} " +
                "settingsGrant=${security?.isAuthorized(ProtectedSurface.APP_SETTINGS) == true} " +
                "sharedBusy=${foregroundID != null} vpnBusy=${sharedVpnEditor.currentId != null} " +
                "controlEvent=$controlEvent commandHostMatches=${host != null && host.matches(currentVisitHost()) && host.nativeHostID == input.optString("nativeHostID")} " +
                "foregroundOwnerMatches=${foregroundID != null && foregroundID == input.optString("id")} " +
                "foregroundReady=${generation != null && foregroundID != null && activity?.isPresentationReady(generation, "foreground:$foregroundID") == true} " +
                "onboardingReady=${generation?.let { activity?.isPresentationReady(it, "onboarding:${input.optString("id")}") } == true}")
        }
    }

    private fun isSharedForegroundStart(input: JSONObject): Boolean =
        input.optString("type") in setOf("library.form", "filter.renameForm") ||
            input.optString("type") == "native.flow" && input.optString("flow") in setOf("feedback", "licenses", "automation")

    /** A foreground visit suspends only its caller; its controls and native write lane remain free. */
    private suspend fun beginSharedForeground(input: JSONObject): String {
        val activity = context.currentActivity as? LavaReactActivity ?: return result("rejected", "host-unavailable")
        val generation = input.optString("generation").toLongOrNull() ?: return result("rejected", "stale-generation")
        if (portableBackupActive || sharedVpnEditor.currentId != null || accountBackupFlow?.retained() == true) return result("rejected", "foreground-busy")
        if (input.optInt("schema") != 1 || generation != product.state.value.graphGeneration || !activity.foreground) {
            return result("rejected", "stale-generation")
        }
        val type = input.optString("type")
        val form = input.optString("form")
        val kind = when {
            type == "filter.renameForm" || type == "library.form" && form == "rename" -> NativeSharedForegroundKind.RENAME_FILTER
            type == "library.form" && form == "create" -> NativeSharedForegroundKind.CREATE_FILTER
            type == "library.form" && form == "delete" -> NativeSharedForegroundKind.DELETE_FILTERS
            input.optString("flow") == "feedback" -> NativeSharedForegroundKind.FEEDBACK
            input.optString("flow") == "licenses" -> NativeSharedForegroundKind.LICENSES
            input.optString("flow") == "automation" -> NativeSharedForegroundKind.AUTOMATION
            else -> return result("rejected", "invalid-flow")
        }
        val present: NativeSecurityPresenter = { _, verify, cancel -> activity.presentPasscodeAuthentication(verify, cancel) }
        val surface = when (kind) {
            NativeSharedForegroundKind.CREATE_FILTER, NativeSharedForegroundKind.RENAME_FILTER,
            NativeSharedForegroundKind.DELETE_FILTERS -> ProtectedSurface.FILTERS
            NativeSharedForegroundKind.FEEDBACK -> ProtectedSurface.APP_SETTINGS
            else -> null
        }
        if (!product.securityAuthorize(generation, ProtectedSurface.APP_UNLOCK, present = present) ||
            surface != null && !product.securityAuthorize(generation, surface, present = present)) return result("cancelled", "authentication")
        if (context.currentActivity !== activity || activity.isDestroyed || !activity.foreground || product.state.value.graphGeneration != generation) return result("rejected", "stale-generation")
        if (kind == NativeSharedForegroundKind.FEEDBACK && !AppEdition.current.nativeSupport) {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://lavasecurity.app/support/")))
            return result("completed")
        }
        val session = product.libraryEditSessionForSnapshot(generation)
        val library = product.currentFilterLibrary()
        val id = input.optString("id")
        val expectedDraft = if (type == "filter.renameForm") product.sharedFilterDraft(generation, id) else null
        val expectedNewFilter = if (type == "filter.renameForm") product.sharedNewFilter(generation) else null
        val libraryRename = if (type == "library.form" && form == "rename")
            session?.let { NativeLibraryRenameContext(it, id) } else null
        // Reopening a staged rename must use the same displayed identity as its Library row.
        val target = if (type == "library.form" && form == "rename") libraryRename?.target
            else expectedDraft ?: library?.profile(id)
        if (type == "filter.renameForm" && expectedDraft == null) return result("rejected", "not-editing")
        if (kind == NativeSharedForegroundKind.RENAME_FILTER && target == null) return result("rejected", "unknown-filter")
        val isCurrent = {
            product.state.value.graphGeneration == generation && when (kind) {
                NativeSharedForegroundKind.CREATE_FILTER, NativeSharedForegroundKind.DELETE_FILTERS ->
                    product.libraryEditSessionForSnapshot(generation) === session && product.currentFilterLibrary() == library
                NativeSharedForegroundKind.RENAME_FILTER -> if (type == "filter.renameForm")
                    product.sharedFilterDraft(generation, id) === expectedDraft && product.sharedNewFilter(generation) === expectedNewFilter
                    else libraryRename?.isCurrent(product.libraryEditSessionForSnapshot(generation)) == true &&
                        product.currentFilterLibrary() == library
                else -> true
            }
        }
        val notices = if (kind == NativeSharedForegroundKind.LICENSES) {
            com.lavasec.app.data.CatalogLegalNotices(context.assets) { context.getString(it) }.buildSections()
                .joinToString("\n\n") { section -> section.title + "\n\n" + section.notices.joinToString("\n\n") { it.displayName + "\n\n" + it.noticeText } }
        } else ""
        val visitHost = currentVisitHost()?.takeIf { it.owner === activity }
            ?: return result("rejected", "host-closed")
        val isVisitCurrent = { !filterDetailHostsClosed.get() && visitHost.matches(currentVisitHost()) && activity.foreground &&
            product.state.value.graphGeneration == generation }
        return sharedForeground.begin(NativeSharedForegroundSpec(
            kind = kind, name = target?.name.orEmpty(), emoji = target?.emoji ?: "🌿",
            templates = library?.profiles.orEmpty().map { NativeSharedForegroundTemplate(it.id, it.name) },
            canCreate = session?.isEditing == true && !session.hasChanges &&
                library != null && library.profiles.size < product.state.value.featureLimits.maxFilters,
            reviewedLibrarySession = session, notices = notices, isCurrent = isCurrent,
            isNameAvailable = { name -> libraryRename?.isNameAvailable(name) ?: library?.profiles.orEmpty().none {
                it.id != id && com.lavasec.app.foundation.filter.FilterIdentityPolicy.normalizedName(it.name)
                    .equals(com.lavasec.app.foundation.filter.FilterIdentityPolicy.normalizedName(name), ignoreCase = true)
            } },
            create = { template ->
                if (!isCurrent() || !isVisitCurrent()) null else (product.beginCreatingFilter(null, template, generation, present,
                    expectedSession = session, isCurrent = isVisitCurrent,
                    onDetailPresented = { rememberFilterDetail(visitHost, it) }) as? FilterLibraryEditOutcome.Created)?.filterId
            },
            rename = { name, emoji ->
                if (!isCurrent() || !isVisitCurrent()) false else if (type == "filter.renameForm")
                    product.renameFilter(id, name, generation, emoji, present,
                        expectedDraft = expectedDraft, expectedNewFilter = expectedNewFilter, isCurrent = isVisitCurrent)
                else product.stageLibraryRename(id, name, emoji, generation, present,
                    expectedSession = libraryRename?.session ?: session, isCurrent = isVisitCurrent) == FilterLibraryEditOutcome.Completed
            },
            delete = {
                val committed = isCurrent() && isVisitCurrent() && product.commitLibraryEdit(null, generation, present,
                    expectedSession = session, isCurrent = isVisitCurrent) == FilterLibraryEditOutcome.Completed
                if (committed) if (AppEdition.current.cloudBackup) backupAutomaticCoordinator.onConfigurationChanged()
                committed
            },
        )).toJson()
    }

    private suspend fun executeLibraryCommand(
        command: ProductLibraryCommand,
        generation: Long,
        activity: LavaReactActivity,
    ): String {
        var authenticationCancelled = false
        val present: NativeSecurityPresenter = { _, verify, cancel ->
            activity.presentPasscodeAuthentication(verify) { authenticationCancelled = true; cancel() }
        }
        val outcome = when (command) {
            ProductLibraryCommand.Edit ->
                libraryResult(product.beginLibraryEditing(generation, present))
            ProductLibraryCommand.Cancel -> {
                // Discard is local teardown; a revoked grant must not trap the user.
                product.cancelLibraryEditing(generation)
                result("completed")
            }
            is ProductLibraryCommand.ToggleDeletion ->
                libraryResult(product.toggleLibraryDeletion(command.id, generation, present))
            is ProductLibraryCommand.Form.Create ->
                libraryResult(
                    product.beginCreatingFilter(
                        command.name, command.duplicateFromId, generation, present,
                    ),
                )
            is ProductLibraryCommand.Form.Rename ->
                libraryResult(
                    product.stageLibraryRename(
                        command.id, command.name, command.emoji, generation, present,
                    ),
                )
            is ProductLibraryCommand.Form.Delete ->
                libraryResult(product.commitLibraryEdit(command.ids?.toSet(), generation, present))
        }
        return authenticationResult(outcome, authenticationCancelled)
    }

    private fun libraryResult(outcome: FilterLibraryEditOutcome?): String = when (outcome) {
        null -> result("rejected", "unauthorized")
        is FilterLibraryEditOutcome.Created -> result("completed", value = outcome.filterId)
        FilterLibraryEditOutcome.Completed -> result("completed")
        is FilterLibraryEditOutcome.Rejected -> result("rejected", outcome.reason.wireCode())
    }

    /**
     * Canonical `share.*`: the app-layer [FilterShareOwner] computes the shareable content and this
     * adapter performs the only platform effects. `share.copy` writes the bare code to the Android
     * clipboard, `share.present` opens the system share chooser with the canonical link, and
     * `share.card` captures the shared RN composer and launches a read-granted image chooser. None of these
     * mutate the saved filter.
     */
    private suspend fun executeShareCommand(
        command: ProductShareCommand,
        before: MainProductUiState,
        activity: LavaReactActivity,
    ): String {
        if (!before.productAvailable) return result("rejected", "unavailable")
        val generation = before.graphGeneration ?: return result("rejected", "unavailable")
        var authenticationCancelled = false
        val present: NativeSecurityPresenter = { _, verify, cancel ->
            activity.presentPasscodeAuthentication(verify) { authenticationCancelled = true; cancel() }
        }
        val authorized = authorizeCommand(generation, ProtectedSurface.FILTERS, present, { authenticationCancelled = true })
        if (!authorized) return result(if (authenticationCancelled) "cancelled" else "rejected", "authentication")
        return when (command) {
            is ProductShareCommand.Query -> when (val content = shareOwner.content(command.id)) {
                is FilterShareContent.Ready -> {
                    // The 512x512 ARGB buffer, matrix/bitmap build, PNG compression and Base64 are
                    // CPU-heavy; render off the main dispatcher `execute()` runs in.
                    val rendered = withContext(Dispatchers.Default) {
                        ShareQrRenderer.renderPngDataUri(content.url) to FilterShareCardQrEncoder.encode(content.url)
                    }
                    if (!shareCardCurrent(before, activity, content)) return result("rejected", "stale-share")
                    val image = rendered.first
                    val card = rendered.second?.let { qr ->
                        val request = shareCards.issue(content, before.graphGeneration,
                            before.security?.authenticationRevision ?: 0L, activity, presentationLocale())
                        // A transient surface unmount retires only the mounted-view inventory, not
                        // this export grant, so a remounted surface re-admits from the live payload.
                        // Mirrors iOS LavaShareCardSurfaceRegistry.reauthorize.
                        installShareCardAuthority()
                        JSONObject().put("token", request.token).put("payload", content.url)
                            .put("moduleCount", qr.size).put("labels", JSONArray(FilterShareCardCopy.labels(
                                context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply {
                                    setLocales(android.os.LocaleList(Locale.forLanguageTag(presentationLocale())))
                                }), FilterShareCardSummary.from(content.configuration))))
                    }
                    result(
                        "completed",
                        value = JSONObject()
                            .put("code", content.code)
                            .put("url", content.url)
                            // The shared ShareDetail renders this card; an encode failure stays null
                            // (the card area shows its loading/unavailable state) rather than a fake
                            // image.
                            .put("image", image ?: JSONObject.NULL)
                            .put("card", card ?: JSONObject.NULL),
                    )
                }
                is FilterShareContent.Unavailable ->
                    result("rejected", shareFailureReason(content.reason))
            }
            is ProductShareCommand.Copy -> when (val content = shareOwner.content(command.id)) {
                is FilterShareContent.Ready -> {
                    clipboard.setPrimaryClip(ClipData.newPlainText(SHARE_CLIP_LABEL, content.code))
                    result("completed")
                }
                is FilterShareContent.Unavailable ->
                    result("rejected", shareFailureReason(content.reason))
            }
            is ProductShareCommand.Present -> when (val content = shareOwner.content(command.id)) {
                is FilterShareContent.Ready -> {
                    activity.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, content.url),
                            null,
                        ),
                    )
                    result("completed")
                }
                is FilterShareContent.Unavailable ->
                    result("rejected", shareFailureReason(content.reason))
            }
            is ProductShareCommand.Card -> {
                val content = shareOwner.content(command.id) as? FilterShareContent.Ready
                    ?: return result("rejected", "stale-share")
                fun current(): Boolean = shareCardCurrent(before, activity, content) &&
                    shareCards.accepted(command.token, command.id, before.graphGeneration,
                        before.security?.authenticationRevision ?: 0L, activity, presentationLocale(), content) != null
                if (!current()) return result("rejected", "stale-share")
                val bitmap = LavaShareCardSurfaces.capture(command.token, content.url, activity)
                    ?: return result("rejected", "share-card-not-ready")
                val bytes = try {
                    withContext(Dispatchers.Default) {
                        java.io.ByteArrayOutputStream().use { stream ->
                            if (bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)) stream.toByteArray() else null
                        }
                    }
                } finally { bitmap.recycle() }
                if (bytes == null || !current()) return result("rejected", "stale-share")
                var artifact: android.net.Uri? = null
                var launched = false
                try {
                    withContext(Dispatchers.IO) { artifact = AndroidFilterShareCardArtifact.create(context, bytes) }
                    val uri = artifact ?: return result("rejected", "share-card-unavailable")
                    if (!current()) return result("rejected", "stale-share")
                    val intent = AndroidFilterShareCardArtifact.shareIntent(context, uri)
                        ?: return result("rejected", "share-card-unavailable")
                    activity.startActivity(Intent.createChooser(intent, null))
                    launched = true
                    // A translucent system chooser can leave the host resumed. Retire this
                    // exact export grant at handoff rather than depending on a pause callback.
                    shareCards.revoke(command.token)
                    result("completed") // Chooser launched; never a message-delivery receipt.
                } finally {
                    if (!launched) artifact?.let { uri ->
                        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { AndroidFilterShareCardArtifact.discard(context, uri) }
                    }
                }
            }
        }
    }

    private fun shareCardCurrent(before: MainProductUiState, activity: LavaReactActivity,
                                 content: FilterShareContent.Ready): Boolean {
        val live = product.state.value
        val security = product.securityStateForSnapshot(live.graphGeneration)
        return context.currentActivity === activity && activity.foreground && !activity.isDestroyed &&
            live.productAvailable && live.graphGeneration == before.graphGeneration &&
            security?.authenticationRevision == before.security?.authenticationRevision &&
            !live.copy(security = security).isLocked(ProtectedSurface.APP_UNLOCK, System.currentTimeMillis()) &&
            !live.copy(security = security).isLocked(ProtectedSurface.FILTERS, System.currentTimeMillis()) &&
            shareOwner.content(content.filterId) == content
    }

    fun invalidate() {
        domainChecks.retire()
        portableBackupModuleRetired.set(true)
        preparationOwner.close()
        vpnDraftModuleRetired.set(true)
        product.nativeVpnDraftHosts.retireModule(this)
        filterDetailHostsClosed.set(true)
        customEntryHosts.close()
        standaloneDomainReviews.close()
        filterDetailRetirements.keys.toList().forEach(::retireFilterDetailHost)
        guardGestureHaptics.close()
        retireImportVisit()
        accountBackupFlow?.let(::retireAccountBackupFlow)
        sharedForeground.close()
        sharedOnboarding.close()
        sharedVpnEditor.close()
        shareCards.revoke()
        activityShareCards.revoke()
        LavaShareCardSurfaces.reauthorize = null
        context.removeLifecycleEventListener(shareCardLifecycle)
    }

    /**
     * Canonical `vpn.*`: the ordered chained-configuration editor.
     *
     * Every command drives [ChainedConfigurationCommandOwner] over the live credential
     * repository; the wire carries only opaque draft ids/indexes/generation and never configuration
     * text, so the private and pre-shared keys stay in the native editor and the owner's opaque key
     * lease. Row edits, swapping and per-row participation retain their independent native credentials.
     *
     * `vpn.toggle fallback` is the resolver owner's setting, projected onto the existing
     * `dns.toggle` path so Android keeps one fallback authority; `vpn.toggle enabled` commits the
     * chained preference through the composition and asks the live runtime to reload.
     */
    private suspend fun executeVpnCommand(
        command: ProductVpnCommand,
        generation: Long,
        activity: LavaReactActivity,
    ): String {
        if ((command is ProductVpnCommand.Begin || command is ProductVpnCommand.Edit) &&
            (portableBackupActive || accountBackupFlow?.retained() == true)) return result("rejected", "foreground-busy")
        val composition = application.chainedComposition.value
            ?: return result("rejected", "unavailable")
        var authenticationCancelled = false
        val present: NativeSecurityPresenter = { _, verify, cancel ->
            activity.presentPasscodeAuthentication(verify) { authenticationCancelled = true; cancel() }
        }
        suspend fun authorized(): Boolean =
            authorizeCommand(generation, ProtectedSurface.APP_SETTINGS, present, { authenticationCancelled = true })
        suspend fun authorizedOrRejected(): String? =
            if (authorized()) null
            else authenticationResult(result("rejected", "unauthorized"), authenticationCancelled)

        val owner = chainedOwner(composition)
        val host = NativeVisitHost(activity, activity.nativeHostID) { activity.nativeHostAlive }
        val drafts = product.nativeVpnDraftHosts
        val graphCurrent = {
            product.state.value.graphGeneration == generation &&
                application.chainedComposition.value === composition && chainedOwner(composition) === owner
        }
        return when (command) {
            is ProductVpnCommand.Begin -> {
                authorizedOrRejected()?.let { return it }
                val expected = vpnBeginExpectedGeneration(command.generation)
                    ?: return result("rejected", ProductCommandReasons.INVALID_ARGUMENT)
                val outcome = drafts.begin(
                    module = this, host = host, generation = generation, composition = composition,
                    owner = owner, repository = composition.configurationRepository, id = command.id,
                    expectedGeneration = expected, graphCurrent = graphCurrent,
                    hostCurrent = { !vpnDraftModuleRetired.get() && host.matches(currentVisitHost()) && activity.foreground },
                    recover = { recoverPendingFirstVpnImport(composition) },
                    operation = {
                        firstConfigurationCoordinator(owner, activity) {
                            chainedOwner(composition) === owner && authorized()
                        }.beginOrImport(command.id, expected)
                    },
                )
                val retirement = (outcome as? VpnFirstConfigurationBegin.Draft)?.let {
                    drafts.captureRouteRetirement(this, host, generation, composition, owner, it.draft)
                }
                val response = firstConfigurationResult(outcome)
                if (retirement == null) response else JSONObject(response).apply {
                    getJSONObject("value").put("retirement", JSONObject()
                        .put("id", retirement.id)
                        .put("retirementGeneration", retirement.retirementGeneration.toString())
                        .put("nativeHostID", retirement.nativeHostID)
                        .put("retirementLease", retirement.retirementLease))
                }.toString()
            }
            is ProductVpnCommand.Cancel -> drafts.serialized(composition) {
                if (!graphCurrent() || vpnDraftModuleRetired.get() || !host.matches(currentVisitHost()) || !activity.foreground) {
                    return@serialized result("rejected", "unavailable")
                }
                // The durable tag and exact identity own compensation even when the original
                // Begin could not return its draft. A failed cleanup retains the native capability
                // so the same current editor can explicitly retry Discard.
                owner.cancelEditor(command.id)
                val stored = composition.configurationRepository.loadConfiguration()
                if (stored?.pendingImportId == command.id) {
                    return@serialized when (composition.configurationRepository.removePendingImport(command.id, stored.identity)) {
                        com.lavasec.app.platform.ChainedUpstreamPendingRemovalResult.Removed,
                        com.lavasec.app.platform.ChainedUpstreamPendingRemovalResult.Superseded -> {
                            drafts.release(host, generation, composition, owner, command.id)
                            if (graphCurrent()) pendingVpnImportStore.clear()
                            result("completed")
                        }
                        com.lavasec.app.platform.ChainedUpstreamPendingRemovalResult.AuthoritativeReadFailed,
                        is com.lavasec.app.platform.ChainedUpstreamPendingRemovalResult.Failed ->
                            result("rejected", REASON_VPN_IMPORT_CLEANUP_FAILED)
                    }
                }
                if (drafts.ownsPendingImport(this, host, generation, composition, owner, command.id) && stored == null &&
                    composition.configurationRepository.configurationIssue() !=
                    com.lavasec.app.foundation.chained.ChainedConfigurationIssue.MISSING_CONFIGURATION) {
                    return@serialized result("rejected", REASON_VPN_IMPORT_CLEANUP_FAILED)
                }
                drafts.release(host, generation, composition, owner, command.id)
                result("completed")
            }
            is ProductVpnCommand.Reset -> {
                authorizedOrRejected()?.let { return it }
                editorResult(owner.resetEditor(command.id))
            }
            is ProductVpnCommand.Swap -> {
                authorizedOrRejected()?.let { return it }
                editorResult(owner.swapEditorRows(command.id))
            }
            is ProductVpnCommand.Remove -> {
                authorizedOrRejected()?.let { return it }
                editorResult(owner.stageEditorRemoval(command.id, command.index))
            }
            is ProductVpnCommand.Commit -> {
                authorizedOrRejected()?.let { return it }
                // Navigation can revoke this grant while the repository read/edit is suspended.
                // Capture after real authorization and retain that exact turn through publication.
                drafts.serialized(composition) {
                    val authority = product.captureMutationAuthorizationFence(generation, ProtectedSurface.APP_SETTINGS)
                        ?: return@serialized result("rejected", "unauthorized")
                    // An old completion must not reload a service successor installed while it awaited storage.
                    val serviceGeneration = LavaVpnRuntimeBridge.serviceGeneration
                    val outcome = owner.commitEditor(command.id,
                        forceNewGeneration = drafts.ownsImport(host, generation, composition, owner, command.id),
                        isCurrent = {
                            !vpnDraftModuleRetired.get() && host.matches(currentVisitHost()) && activity.foreground &&
                                product.state.value.graphGeneration == generation &&
                                application.chainedComposition.value === composition && chainedOwner(composition) === owner &&
                                authority()
                        })
                    // Durable profile publication ends first-import pending ownership even when
                    // the final settings write must be retried by the rebased editor. Unpublished
                    // drafts remain pending so cancel can remove the eagerly imported rotation.
                    val published = when (outcome) {
                        is ChainedConfigurationEditorCommitResult.Committed -> outcome.identity
                        is ChainedConfigurationEditorCommitResult.Failed -> outcome.committedIdentity
                        else -> null
                    }
                    val removed = outcome is ChainedConfigurationEditorCommitResult.Removed ||
                        (outcome as? ChainedConfigurationEditorCommitResult.Failed)?.removedGeneration != null
                    val sameGraph = product.state.value.graphGeneration == generation &&
                        application.chainedComposition.value === composition && chainedOwner(composition) === owner
                    if (published != null || removed) {
                        drafts.finishCommit(host, generation, composition, owner, command.id)
                        // A legacy account journal belongs to the original graph, never a successor graph.
                        if (sameGraph) pendingVpnImportStore.clear()
                    }
                    if ((published != null || removed) && sameGraph) {
                        serviceGeneration?.let { live -> runCatching { LavaVpnServiceClient(context).reload(live) } }
                    }
                    commitResult(outcome)
                }
            }
            is ProductVpnCommand.Edit -> {
                authorizedOrRejected()?.let { return it }
                // The replacement text is supplied by a first-party presentation (the native
                // `vpnConfiguration` flow the bridge must not fabricate). Android reads it from a
                // user-chosen WireGuard configuration document and stages it through the owner;
                // the text never crosses the JS bridge. A missing presentation stays a typed
                // unavailable, never a staged draft the user did not supply.
                pickedEditResult(
                    firstConfigurationCoordinator(owner, activity) {
                        chainedOwner(composition) === owner && authorized()
                    }.stagePickedReplacement(command.id, command.index),
                )
            }
            is ProductVpnCommand.Toggle -> {
                authorizedOrRejected()?.let { return it }
                if (application.chainedComposition.value !== composition || product.state.value.graphGeneration != generation) {
                    return result("rejected", "unavailable")
                }
                when (val outcome = if (command.key == ChainedConfigurationToggleKey.FALLBACK) {
                    owner.setDnsFallbackEnabled(command.value)
                } else owner.toggle(command.key, command.value)) {
                    ChainedConfigurationToggleResult.Applied -> {
                        // Commit succeeded; ask the live runtime to pick up the new mode. A missing
                        // live generation is discharged by the next start reading the preference.
                        LavaVpnRuntimeBridge.serviceGeneration?.let { live ->
                            runCatching { LavaVpnServiceClient(context).reload(live) }
                        }
                        result("completed")
                    }
                    is ChainedConfigurationToggleResult.Refused ->
                        result("rejected", outcome.refusal.wireCode)
                    ChainedConfigurationToggleResult.Failed ->
                        result("rejected", REASON_VPN_TOGGLE_FAILED)
                }
            }
            is ProductVpnCommand.RowToggle -> {
                authorizedOrRejected()?.let { return it }
                val expected = command.generation.toLongOrNull()
                    ?: return result("rejected", ChainedConfigurationEditorRefusal.STALE_GENERATION.wireCode)
                val outcome = owner.setRowEnabled(command.index, command.value, expected)
                // A preference failure can follow an already durable selection publication.
                // Reload from authoritative facts even on a refused UI outcome.
                LavaVpnRuntimeBridge.serviceGeneration?.let { live -> runCatching { LavaVpnServiceClient(context).reload(live) } }
                commitResult(outcome)
            }
        }
    }

    private fun editorResult(outcome: ChainedConfigurationEditorResult): String = when (outcome) {
        is ChainedConfigurationEditorResult.Draft ->
            result("completed", value = draftJson(outcome.draft))
        is ChainedConfigurationEditorResult.Refused ->
            result("rejected", outcome.refusal.wireCode)
        is ChainedConfigurationEditorResult.Rejected ->
            // The parser's typed refusal is a log identifier; the wire carries one stable
            // editor-scoped code and the native editor renders the actionable detail.
            result("rejected", REASON_VPN_CONFIGURATION_INVALID)
    }

    /** The first-party entry coordinator over the live owner and the activity's document picker. */
    private fun firstConfigurationCoordinator(
        owner: ChainedConfigurationCommandOwner,
        activity: LavaReactActivity,
        revalidate: suspend () -> Boolean = { true },
    ): VpnFirstConfigurationCoordinator {
        activity.setWireGuardInputPort(sharedVpnEditor)
        val host = NativeVisitHost(activity, activity.nativeHostID) { activity.nativeHostAlive }
        val generation = product.state.value.graphGeneration
        val isHostCurrent = {
            host.matches(currentVisitHost()) && activity.foreground &&
                product.state.value.graphGeneration == generation
        }
        // Capture only after the caller's real authorization. A repository guard runs on IO;
        // refreshing its witness is allowed only after successful native reauthorization.
        val writeAuthority = AtomicReference<(() -> Boolean)?>(generation?.let {
            product.captureMutationAuthorizationFence(it, ProtectedSurface.APP_SETTINGS)
        })
        return VpnFirstConfigurationCoordinator(owner = owner, picks = sharedVpnEditor,
            revalidate = {
                if (!isHostCurrent() || !revalidate() || !isHostCurrent()) false
                else {
                    val refreshed = generation?.let {
                        product.captureMutationAuthorizationFence(it, ProtectedSurface.APP_SETTINGS)
                    }
                    writeAuthority.set(refreshed)
                    refreshed?.invoke() == true
                }
            }, isCurrent = {
                isHostCurrent() && writeAuthority.get()?.invoke() == true
            })
    }

    private fun firstConfigurationResult(outcome: VpnFirstConfigurationBegin): String = when (outcome) {
        is VpnFirstConfigurationBegin.Draft ->
            editorResult(ChainedConfigurationEditorResult.Draft(outcome.draft))
        VpnFirstConfigurationBegin.Cancelled -> result("cancelled")
        VpnFirstConfigurationBegin.Unavailable -> result("rejected", REASON_VPN_EDITOR_UNAVAILABLE)
        is VpnFirstConfigurationBegin.Refused -> result("rejected", outcome.refusal.wireCode)
        is VpnFirstConfigurationBegin.Rejected ->
            result("rejected", REASON_VPN_CONFIGURATION_INVALID)
    }

    private fun pickedEditResult(outcome: VpnPickedConfigurationEdit): String = when (outcome) {
        is VpnPickedConfigurationEdit.Draft ->
            editorResult(ChainedConfigurationEditorResult.Draft(outcome.draft))
        VpnPickedConfigurationEdit.Cancelled -> result("cancelled")
        VpnPickedConfigurationEdit.Unavailable -> result("rejected", REASON_VPN_EDITOR_UNAVAILABLE)
        is VpnPickedConfigurationEdit.Refused -> result("rejected", outcome.refusal.wireCode)
        is VpnPickedConfigurationEdit.Rejected ->
            result("rejected", REASON_VPN_CONFIGURATION_INVALID)
    }

    private fun commitResult(outcome: ChainedConfigurationEditorCommitResult): String = when (outcome) {
        is ChainedConfigurationEditorCommitResult.Committed ->
            result("completed", value = outcome.identity.generation.toString())
        ChainedConfigurationEditorCommitResult.Removed -> result("completed")
        is ChainedConfigurationEditorCommitResult.Refused ->
            result("rejected", outcome.refusal.wireCode)
        is ChainedConfigurationEditorCommitResult.Rejected ->
            result("rejected", "vpn-commit-refused")
        ChainedConfigurationEditorCommitResult.AuthoritativeReadFailed ->
            result("rejected", "vpn-commit-unavailable")
        is ChainedConfigurationEditorCommitResult.Failed ->
            result("rejected", "vpn-commit-failed")
    }

    private fun dnsResult(outcome: ResolverDnsEditOutcome): String = when (outcome) {
        is ResolverDnsEditOutcome.Applied -> result("completed")
        is ResolverDnsEditOutcome.DraftOpened -> result("completed", value = outcome.token)
        is ResolverDnsEditOutcome.DraftStaged -> result("completed", value = JSONObject().apply {
            put("id", ResolverDnsWire.encode(outcome.choice.providerId, outcome.choice.transportToken))
            put("name", outcome.choice.name)
            put("primary", outcome.choice.primary)
            put("secondary", outcome.choice.secondary)
            put("transport", outcome.choice.transportToken)
            put("metadata", ResolverCatalog.custom.privacySummary)
        })
        is ResolverDnsEditOutcome.Refused -> result("rejected", outcome.rejection.dnsResultWireCode())
        is ResolverDnsEditOutcome.CommitFailed -> result("rejected", "dns-commit-failed")
        is ResolverDnsEditOutcome.Unserved -> result("rejected", "dns-unserved")
        ResolverDnsEditOutcome.Unavailable -> result("rejected", "unavailable")
    }

    /** Offline file flow uses the live transactional publisher without account or cloud custody. */
    private suspend fun executePortableBackup(generation: Long, activity: LavaReactActivity, action: String): String {
        if (portableBackupActive || sharedForeground.currentId != null || sharedVpnEditor.currentId != null ||
            accountBackupFlow?.retained() == true) return result("rejected", "foreground-busy")
        portableBackupActive = true
        try {
        val host = currentVisitHost()?.takeIf { it.owner === activity } ?: return result("rejected", "stale-host")
        var settingsFence: (() -> Boolean)? = null
        var filtersFence: (() -> Boolean)? = null
        fun current(): Boolean = !portableBackupModuleRetired.get() && host.matches(currentVisitHost()) && activity.nativeHostAlive &&
            activity.foreground && product.state.value.graphGeneration == generation && product.state.value.productAvailable
        fun authorized(): Boolean = current() && settingsFence?.invoke() == true && filtersFence?.invoke() == true
        val authority = ProductBackupRestoreAuthority(
            library = { product.currentFilterLibrary() },
            logging = { product.state.value.privacy?.logging },
            currentGeneration = { product.state.value.graphGeneration },
            replaceLibraryWith = { library, expected ->
                authorized() && product.replaceFilterLibraryPreservingEditing(library, expected, ::authorized)
            },
            rollbackLibraryWith = { library, expected ->
                // Compensation uses the publisher's captured generation and exact forward CAS;
                // foreground/security retirement cannot strand a refused import half-applied.
                product.rollbackFilterLibraryPreservingEditing(library, expected)
            },
            setLoggingWith = { settings -> authorized() && product.setLoggingForRestore(settings) },
            restoreLoggingWith = { settings, expected, transactionAuthorized ->
                product.setLoggingForRestore(settings, expected) { authorized() && transactionAuthorized() }
            },
            discardEditingWith = { product.discardFilterEditingSessions() },
            protectionEnabledHint = { product.state.value.protection?.lifecycle?.isRequestedEnabled ?: false },
        )
        val source = object : PortableBackupSource {
            override val restoreOwner = BackupRestoreOwner(
                publisher = ProductionBackupRestorePublisher(
                    authority, guardian = application.guardProgress,
                    publicationExclusion = if (AppEdition.current.cloudBackup)
                        object : com.lavasec.app.backup.BackupRestorePublicationExclusion {
                            override suspend fun <T> withExclusiveAccess(block: suspend () -> T): T =
                                backupLocalStore.withCustodyLock(block)
                        } else null,
                ),
            )
            override suspend fun authorize(): SecurityAuthorization {
                product.consumeAuthenticationCancellation()
                if (!current()) return SecurityAuthorization.DENIED
                val presenter: NativeSecurityPresenter = { _, verify, cancel -> activity.presentPasscodeAuthentication(verify, cancel) }
                for (surface in listOf(ProtectedSurface.APP_SETTINGS, ProtectedSurface.FILTERS)) {
                    val authorization = product.securityAuthorizeResult(generation, surface, present = presenter)
                    product.consumeAuthenticationCancellation()
                    if (authorization != SecurityAuthorization.AUTHORIZED) return authorization
                }
                if (!current()) return SecurityAuthorization.DENIED
                settingsFence = product.captureMutationAuthorizationFence(generation, ProtectedSurface.APP_SETTINGS)
                filtersFence = product.captureMutationAuthorizationFence(generation, ProtectedSurface.FILTERS)
                return if (authorized()) SecurityAuthorization.AUTHORIZED else SecurityAuthorization.DENIED
            }
            override fun publicationStillAuthorized(): Boolean = authorized()
            override suspend fun capture(): PortableBackupSnapshot? = withContext(Dispatchers.IO) {
                if (!authorized()) return@withContext null
                val library = authority.currentLibrary() ?: return@withContext null
                val preferences = authority.currentPreferences() ?: return@withContext null
                val ledger = application.guardProgress.captureLedger()
                val payload = com.lavasec.app.foundation.backup.BackupPayloadMapper.toPayload(library, preferences, ledger)
                if (!authorized() || product.currentFilterLibrary() != library) return@withContext null
                PortableBackupSnapshot(payload, library, preferences,
                    BackupRestoreBasis(accountID = null, configurationGeneration = generation, guardianLedger = ledger))
            }
        }
        val flow = NativePortableBackupFlow(activity, source, pendingPortableBackup)
        val outcome = when (action) {
            "backup.export" -> flow.export()
            "backup.import" -> flow.restore()
            "backup.pending.resume" -> flow.resume()
            "backup.pending.discard" -> flow.discard()
            else -> return result("rejected", "unsupported-command")
        }
        if (outcome.applied && AppEdition.current.cloudBackup)
            backupAutomaticCoordinator.onConfigurationChanged()
        return result(outcome.status, outcome.reason)
        } finally { portableBackupActive = false }
    }

    /**
     * Delegates one canonical `backup.*` command to the process backup owner. The owner captures
     * and revalidates the account/generation binding around every network send and publication, so
     * a stale product generation cannot upload or delete another account's backup.
     */
    private suspend fun executeBackupCommand(command: ProductBackupCommand): String {
        if (!AppEdition.current.cloudBackup) return result("rejected", "edition-unavailable")
        val outcome = backupOwner.execute(
            when (command) {
                ProductBackupCommand.Refresh -> BackupCommand.Refresh
                ProductBackupCommand.BackUpNow -> BackupCommand.BackUpNow
                ProductBackupCommand.Disable -> BackupCommand.Disable
                ProductBackupCommand.Delete -> BackupCommand.Delete
            },
        )
        return when (outcome) {
            BackupCommandResult.Completed -> result("completed")
            is BackupCommandResult.Rejected -> result("rejected", outcome.reason)
            is BackupCommandResult.Cancelled -> result("cancelled", outcome.reason)
        }
    }

    /**
     * Runs the setup "seal -> store -> upload" path through [backupSetupOwner]. The recovery phrase
     * is generated and confirmed in the native dialog and only the completed/refused status crosses
     * the bridge; the phrase [CharArray] is cleared immediately after the owner consumes it. A
     * failure maps to a stable typed reason, never a fabricated success.
     */
    private suspend fun executeBackupSetupFlow(generation: Long, activity: LavaReactActivity): String {
        val account = application.accountBackend.value?.sessions?.state?.value
        if (account?.availability != AccountAvailability.SIGNED_IN)
            return result("rejected", "backup-setup-sign-in-required")
        if (product.currentFilterLibrary() == null) return result("rejected", "backup-setup-payload-unavailable")
        val present: NativeSecurityPresenter = { _, verify, cancel -> activity.presentPasscodeAuthentication(verify, cancel) }
        if (!authorizeCommand(generation, ProtectedSurface.APP_SETTINGS, present, {})) return result("cancelled", "authentication")
        val basis = BackupSetupBasis(BackupAccountBinding(account.userId, true), account.generation)
        val flow = openAccountBackupFlow("backupSetup", generation, activity) ?: return result("rejected", "foreground-busy")
        var passkey: BackupPasskeyMaterial? = null
        try {
            flow.configureSetup(backupRestoreOwner.selectMethod(BackupRecoveryMethod.PASSKEY).isUsable)
            while (true) {
                val selection = flow.awaitAction("setup.begin")
                if (!selection.optBoolean("passkey")) break
                flow.phase("overview", working = true, passkey = true)
                val salt = newBackupPasskeySalt()
                val created = try { backupPasskeyCeremony(activity, account.userId).create(salt) } finally { salt.fill(0) }
                if (created is BackupPasskeyCeremonyResult.Obtained) {
                    passkey = created.material
                    if (!flow.retained()) return result("cancelled", "dismissed")
                    flow.phase("validatePasskey")
                    val validation = flow.awaitAction("setup.validate", "setup.cancelValidation")
                    if (validation.optString("action") == "setup.cancelValidation") {
                        passkey?.close(); passkey = null; flow.phase("overview"); continue
                    }
                    flow.working(true)
                    val material = checkNotNull(passkey)
                    val assertionSalt = material.usePrf { _, bytes -> bytes.copyOf() }
                    val assertion = try { backupPasskeyCeremony(activity, account.userId).assert(material.credentialId, assertionSalt) }
                        finally { assertionSalt.fill(0) }
                    val matches = if (assertion is BackupPasskeyCeremonyResult.Obtained) try {
                        material.usePrf { expected, _ -> assertion.material.usePrf { actual, _ ->
                            material.credentialId == assertion.material.credentialId && java.security.MessageDigest.isEqual(expected, actual)
                        } }
                    } finally { assertion.material.close() } else false
                    if (matches && flow.retained()) break
                    passkey?.close(); passkey = null
                }
                flow.phase("overview")
                flow.failure("Passkey could not be used. Try again, or continue without Passkey.")
            }
            flow.createPhrase(withPasskey = passkey != null)
            flow.awaitAction("setup.finish")
            // A provider sheet or background transition must renew the native settings grant.
            if (!authorizeCommand(generation, ProtectedSurface.APP_SETTINGS, present, {})) return result("cancelled", "authentication")
            val mutation = product.captureMutationAuthorizationFence(generation, ProtectedSurface.APP_SETTINGS)
                ?: return result("rejected", "unauthorized")
            val secret = flow.borrowSecret()
            flow.phase("upload", working = true)
            val outcome = try {
                withContext(Dispatchers.IO) {
                    backupSetupOwner.setup(secret, com.lavasec.app.foundation.backup.BackupSetupConsent(
                        attemptID = flow.id, savedRecoveryPhrase = true, understandsNoRecovery = true),
                        passkey = passkey, slotKind = ProductionBackupRestoreUnlockSource.SLOT_RECOVERY_PHRASE,
                        expectedBasis = basis,
                        stillAuthorized = { flow.retained() && mutation() && backupFlowStillBound(generation, account.userId, account.generation) })
                }
            } finally { secret.fill('\u0000'); passkey?.close(); passkey = null }
            if (outcome is BackupSetupResult.Rejected) return result("rejected", outcome.reason)
            var uploaded = (outcome as BackupSetupResult.Completed).uploaded
            while (!uploaded && flow.retained()) {
                flow.failure(activity.getString(R.string.backup_failure_upload_failed), upload = true)
                flow.awaitAction("setup.retry")
                if (!authorizeCommand(generation, ProtectedSurface.APP_SETTINGS, present, {})) return result("cancelled", "authentication")
                val retryFence = product.captureMutationAuthorizationFence(generation, ProtectedSurface.APP_SETTINGS)
                    ?: return result("rejected", "unauthorized")
                flow.working(true)
                // The native owner reuses the committed key slots; retry never creates new recovery material.
                uploaded = withContext(Dispatchers.IO) {
                    backupOwner.retryFirstUpload(basis) { flow.retained() && retryFence() &&
                        backupFlowStillBound(generation, account.userId, account.generation) }
                }
            }
            if (uploaded && flow.retained()) { flow.complete(); flow.awaitClosed() }
            return result("completed", value = if (uploaded) "uploaded" else "local")
        } finally { passkey?.close(); retireAccountBackupFlow(flow) }
    }

    private fun backupFlowStillBound(generation: Long, accountID: String?, accountGeneration: Long): Boolean {
        val live = application.accountBackend.value?.sessions?.state?.value ?: return false
        return product.state.value.graphGeneration == generation && live.availability == AccountAvailability.SIGNED_IN &&
            live.userId == accountID && live.generation == accountGeneration
    }

    /**
     * Runs the reviewed backup-restore flow through the live [backupRestoreOwner]. The account
     * binding and review basis are captured here; the production unlock source downloads and
     * decrypts the account's envelope with the phrase collected natively. A wrong secret, a damaged
     * payload, an unavailable capability or a schema newer than this build refuses before any state
     * write; a prepared review is confirmed by an explicit native confirmation and applied
     * atomically. No restore material or review handle ever leaves the native side.
     */
    private suspend fun executeBackupRestoreFlow(
        before: MainProductUiState,
        generation: Long,
        activity: LavaReactActivity,
    ): String {
        val library = product.currentFilterLibrary()
            ?: return result("rejected", "backup-restore-unavailable")
        val account = application.accountBackend.value?.sessions?.state?.value
        if (account?.availability != AccountAvailability.SIGNED_IN) {
            return result("rejected", "backup-restore-sign-in-required")
        }
        // This review displays and replaces the filter library as well as logging settings.
        // Acquire both read grants before downloading or presenting its private details.
        var authenticationCancelled = false
        val present: NativeSecurityPresenter = { _, verify, cancel ->
            activity.presentPasscodeAuthentication(verify) { authenticationCancelled = true; cancel() }
        }
        if (!authorizeCommand(generation, ProtectedSurface.APP_SETTINGS, present, { authenticationCancelled = true }) ||
            !authorizeCommand(generation, ProtectedSurface.FILTERS, present, { authenticationCancelled = true })) {
            return authenticationResult(result("rejected", "unauthorized"), authenticationCancelled)
        }
        if (!backupFlowStillBound(generation, account.userId, account.generation)) {
            return result("rejected", "backup-restore-stale-review")
        }
        val deviceKeyAvailable = withContext(Dispatchers.IO) { backupRestoreUnlockSource.deviceKeyAvailable() }
        if (!backupFlowStillBound(generation, account.userId, account.generation)) {
            return result("rejected", "backup-restore-stale-review")
        }
        backupDeviceKeyBasis = if (deviceKeyAvailable) BackupSetupBasis(
            BackupAccountBinding(account.userId, true), account.generation) else null
        val passkeyAvailable = backupRestoreOwner.selectMethod(BackupRecoveryMethod.PASSKEY).isUsable
        val flow = openAccountBackupFlow("backupRestore", generation, activity) ?: return result("rejected", "foreground-busy")
        try {
        flow.configureRestore(deviceKeyAvailable, passkeyAvailable)
        while (flow.retained()) {
        flow.awaitAction("restore.prepare")
        val method = when (flow.method()) {
            "deviceKey" -> BackupRecoveryMethod.DEVICE_KEY
            "passkey" -> BackupRecoveryMethod.PASSKEY
            else -> BackupRecoveryMethod.RECOVERY_CODE
        }
        flow.working(true)
        val unlock = when (method) {
            BackupRecoveryMethod.PASSKEY -> {
                // The passkey sheet pauses the activity; re-check the App Settings grant before the
                // download/PRF evaluation touches the envelope.
                if (!authorizeCommand(generation, ProtectedSurface.APP_SETTINGS, present, { authenticationCancelled = true })) {
                    return authenticationResult(result("rejected", "unauthorized"), authenticationCancelled)
                }
                if (!backupFlowStillBound(generation, account.userId, account.generation)) {
                    return result("rejected", "backup-restore-stale-review")
                }
                withContext(Dispatchers.IO) { backupRestoreUnlockSource.unlockWithPasskey(backupPasskeyCeremony(activity, account.userId)) }
            }

            BackupRecoveryMethod.DEVICE_KEY -> {
                if (!authorizeCommand(generation, ProtectedSurface.APP_SETTINGS, present, { authenticationCancelled = true })) {
                    return authenticationResult(result("rejected", "unauthorized"), authenticationCancelled)
                }
                if (!backupFlowStillBound(generation, account.userId, account.generation)) {
                    return result("rejected", "backup-restore-stale-review")
                }
                val host = currentVisitHost()?.takeIf { it.owner === activity && it.matches(it) }
                    ?: return result("rejected", "stale-host")
                withContext(Dispatchers.IO) {
                    backupRestoreUnlockSource.unlockWithDeviceKey {
                        flow.retained() && host.matches(currentVisitHost()) &&
                            backupFlowStillBound(generation, account.userId, account.generation)
                    }
                }
            }

            BackupRecoveryMethod.RECOVERY_CODE -> {
                val phrase = flow.borrowSecret()
                try {
                    // The restore dialog paused the activity; returning from the background revokes
                    // the App Settings grant, so re-check it before the download/decrypt touches the secret.
                    if (!authorizeCommand(generation, ProtectedSurface.APP_SETTINGS, present, { authenticationCancelled = true })) {
                        return authenticationResult(result("rejected", "unauthorized"), authenticationCancelled)
                    }
                    if (!backupFlowStillBound(generation, account.userId, account.generation)) {
                        return result("rejected", "backup-restore-stale-review")
                    }
                    // Download + decrypt (PBKDF2) must not block the main dispatcher `execute()` runs in.
                    withContext(Dispatchers.IO) { backupRestoreUnlockSource.unlock(phrase) }
                } finally {
                    phrase.fill('\u0000')
                }
            }
        }
        if (!flow.retained() || !backupFlowStillBound(generation, account.userId, account.generation)) {
            (unlock as? BackupRestoreUnlock.Unlocked)?.adoption?.close()
            return if (flow.dismissedByUser()) result("cancelled", "dismissed")
                else result("rejected", "backup-restore-stale-review")
        }
        if (unlock is BackupRestoreUnlock.Cancelled) { flow.working(false); continue }
        val preferences = product.state.value.privacy?.logging
            ?.toBackupRetainedPreferences(
                protectionEnabledHint = before.protection?.lifecycle?.isRequestedEnabled ?: false,
            )
            ?: BackupRetainedPreferences.DEFAULT
        val ledger = try { withContext(Dispatchers.IO) { application.guardProgress.captureLedger() } }
        catch (failed: Exception) {
            (unlock as? BackupRestoreUnlock.Unlocked)?.adoption?.close()
            if (failed is kotlinx.coroutines.CancellationException) throw failed
            return result("rejected", "backup-restore-unavailable")
        }
        if (!flow.retained() || !backupFlowStillBound(generation, account.userId, account.generation)) {
            (unlock as? BackupRestoreUnlock.Unlocked)?.adoption?.close()
            return if (flow.dismissedByUser()) result("cancelled", "dismissed")
                else result("rejected", "backup-restore-stale-review")
        }
        val basis = BackupRestoreBasis(accountID = account.userId, configurationGeneration = generation,
            accountGeneration = account.generation, guardianLedger = ledger)
        val preparation = backupRestoreOwner.prepare(
            unlock = unlock,
            method = method,
            currentLibrary = library,
            currentPreferences = preferences,
            basis = basis,
        )
        when (preparation) {
            is BackupRestorePreparation.Refused -> {
                flow.failure(backupRestoreRefusalReason(preparation.refusal))
                continue
            }
            is BackupRestorePreparation.Prepared -> {
                return try {
                    val plan = preparation.plan
                    flow.review(NativeBackupRestoreReview.project(plan), plan.requiresResolverConfirmation)
                    flow.awaitAction("restore.confirm")
                    flow.working(true)
                    // The review dialog paused the activity as well; re-run the authorization now, at
                    // the publish edge, so a grant revoked while the dialog was open cannot apply a
                    // restore from the stale turn.
                    if (!authorizeCommand(generation, ProtectedSurface.APP_SETTINGS, present, { authenticationCancelled = true })) {
                        return authenticationResult(result("rejected", "unauthorized"), authenticationCancelled)
                    }
                    // Re-read the live basis at the confirm edge; the review may have gone stale while
                    // the dialog was open (a configuration write or credential rotation). Account
                    // session transitions do not rotate the graph generation, so the account must be
                    // re-read too or a former account's downloaded backup could be applied after it is
                    // no longer current.
                    val liveAccount = application.accountBackend.value?.sessions?.state?.value
                    if (liveAccount?.availability != AccountAvailability.SIGNED_IN) {
                        return result("rejected", "backup-restore-sign-in-required")
                    }
                    if (liveAccount.userId != basis.accountID || liveAccount.generation != basis.accountGeneration) {
                        return result("rejected", "backup-restore-stale-review")
                    }
                    val liveGeneration = product.state.value.graphGeneration
                        ?: return result("rejected", "backup-restore-unavailable")
                    val liveLibrary = product.currentFilterLibrary()
                        ?: return result("rejected", "backup-restore-unavailable")
                    val liveLedger = try { withContext(Dispatchers.IO) { application.guardProgress.captureLedger() } }
                    catch (failed: Exception) {
                        if (failed is kotlinx.coroutines.CancellationException) throw failed
                        return result("rejected", "backup-restore-unavailable")
                    }
                    val mutation = product.captureMutationAuthorizationFence(generation, ProtectedSurface.APP_SETTINGS)
                        ?: return result("rejected", "unauthorized")
                    val outcome = withContext(Dispatchers.IO) {
                        // No suspension between admission and the existing atomic restore owner.
                        if (!flow.admitPublication(mutation)) return@withContext BackupRestoreApplyOutcome.Refused(BackupRestoreRefusal.StaleReview)
                        // The owner's publisher blocks on the filter-library write and logging commit
                        // (and possibly a compensating rollback), so the synchronous confirm must be
                        // driven off the main thread.
                        backupRestoreOwner.confirm(
                            reviewID = preparation.reviewID,
                            resolverChangeConfirmed = true,
                            currentBasis = basis.copy(configurationGeneration = liveGeneration,
                                accountGeneration = liveAccount.generation, guardianLedger = liveLedger),
                            currentLibrary = liveLibrary,
                        )
                    }
                    when (outcome) {
                        is BackupRestoreApplyOutcome.Applied -> {
                            if (flow.retained()) { flow.complete(); flow.awaitClosed() }
                            result("completed")
                        }
                        is BackupRestoreApplyOutcome.Refused ->
                            result("rejected", backupRestoreRefusalReason(outcome.refusal))
                    }
                } finally {
                    // Dismissal, stale account/graph, failed authorization and coroutine retirement
                    // all release the exact review's transient adoption key. Confirm consumes it.
                    backupRestoreOwner.discard(preparation.reviewID)
                }
            }
        }
        }
        return result("cancelled", "dismissed")
        } finally { retireAccountBackupFlow(flow) }
    }

    /** Stable wire reason for a restore refusal; never a log identifier or a fabricated success. */
    private fun backupRestoreRefusalReason(refusal: BackupRestoreRefusal): String = when (refusal) {
        BackupRestoreRefusal.WrongSecret -> "backup-restore-wrong-secret"
        BackupRestoreRefusal.CorruptPayload -> "backup-restore-corrupt-payload"
        is BackupRestoreRefusal.UnsupportedSchema -> "backup-restore-unsupported-schema"
        BackupRestoreRefusal.NotRestorable -> "backup-restore-not-restorable"
        BackupRestoreRefusal.PasskeyUnavailable -> "backup-restore-passkey-unavailable"
        BackupRestoreRefusal.ResolverConfirmationRequired ->
            "backup-restore-resolver-confirmation-required"

        BackupRestoreRefusal.StaleReview -> "backup-restore-stale-review"
        BackupRestoreRefusal.RollbackFailed -> "backup-restore-rollback-failed"
        // Distinct codes: the generic "needs a confirmed filter" copy is wrong for a missing/expired
        // review and for an interrupted publish, so each names its own cause.
        BackupRestoreRefusal.NoReview -> "backup-restore-no-review"
        BackupRestoreRefusal.PublishInterrupted -> "backup-restore-publish-interrupted"
        BackupRestoreRefusal.Unavailable -> "backup-restore-unavailable"
    }

    /**
     * Canonical Activity/Diagnostics reads. Every branch delegates to [queryOwner]; this only maps
     * the typed outcome to the canonical wire result. The exact Activity, graph and security turn
     * must still own APP_UNLOCK and ACTIVITY at the private serialization edge after suspension.
     */
    private fun currentActivityShare(token: String): NativeActivityShareCards.Request? = activityShareCards.accepted(
        token, queryReadAuthority(), product.state.value.privacy?.logging, presentationLocale(), java.time.ZoneId.systemDefault(),
    )

    private fun installShareCardAuthority() {
        LavaShareCardSurfaces.reauthorize = { token ->
            currentActivityShare(token)?.let { NativeActivityShareCards.PAYLOAD } ?: shareCards.authorizedPayload(token)
        }
    }

    private suspend fun executeActivityCardQuery(command: ProductActivityCardQuery, generation: Long): String {
        // The Android activity owner currently has no native bucket sampler. Do not export
        // invented time series or turn unavailable samples into measured zeroes.
        if (command.chart != "total") return result("rejected", "activity-chart-unavailable")
        val captured = queryReadAuthority()
        val policy = product.state.value.privacy?.logging
        if (policy?.keepCounts != true) return result("rejected", "activity-counts-unavailable")
        val locale = presentationLocale()
        val zone = java.time.ZoneId.systemDefault()
        val attempt = activityShareCards.begin()
        val fence = NativeQueryReadFence(captured)
        val outcome = fence.read(::queryReadAuthority) {
            queryOwner.activity(command.start, command.end, command.hourly, expectedGeneration = generation)
        }
        val summary = (outcome as? QueryOutcome.Value)?.value as? ActivityQueryResult
            ?: return outcome.toCommandResult()
        val modules = withContext(Dispatchers.Default) { FilterShareCardQrEncoder.encode(NativeActivityShareCards.PAYLOAD)?.size }
            ?: return result("rejected", "share-card-unavailable")
        if (fence.rejection(queryReadAuthority()) != null || policy != product.state.value.privacy?.logging ||
            locale != presentationLocale() || zone != java.time.ZoneId.systemDefault()) return result("rejected", "stale-share")
        val grant = activityShareCards.issue(attempt, captured, policy, locale, zone, command, summary, modules)
            ?: return result("rejected", "stale-share")
        installShareCardAuthority()
        return result("completed", value = grant.content)
    }

    private suspend fun executeActivityShare(command: ProductActivityShare, activity: LavaReactActivity): String {
        val grant = currentActivityShare(command.token) ?: return result("rejected", "stale-share")
        fun current() = currentActivityShare(command.token) === grant
        val bitmap = awaitMountedShareCard(::current, {
            LavaShareCardSurfaces.capture(command.token, NativeActivityShareCards.PAYLOAD, activity)
        }) ?: return result("rejected", if (current()) "share-card-not-ready" else "stale-share")
        val bytes = try {
            withContext(Dispatchers.Default) { java.io.ByteArrayOutputStream().use { stream ->
                if (bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)) stream.toByteArray() else null
            } }
        } finally { bitmap.recycle() }
        if (bytes == null || !current()) return result("rejected", "stale-share")
        var artifact: android.net.Uri? = null
        var launched = false
        try {
            withContext(Dispatchers.IO) { artifact = AndroidFilterShareCardArtifact.create(context, bytes) }
            val uri = artifact ?: return result("rejected", "share-card-unavailable")
            if (!current()) return result("rejected", "stale-share")
            val intent = AndroidFilterShareCardArtifact.shareIntent(context, uri)
                ?: return result("rejected", "share-card-unavailable")
            activity.startActivity(Intent.createChooser(intent, null))
            launched = true
            activityShareCards.revoke(command.token)
            return result("completed") // System chooser handoff, never proof of delivery.
        } finally {
            if (!launched) artifact?.let { uri -> withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                AndroidFilterShareCardArtifact.discard(context, uri)
            } }
        }
    }

    private suspend fun executeQueryCommand(
        command: ProductQueryCommand,
        before: MainProductUiState,
        generation: Long,
        activity: LavaReactActivity,
    ): String {
        val captured = NativeQueryReadAuthority(
            host = activity,
            nativeHostID = activity.nativeHostID,
            generation = before.graphGeneration,
            foreground = activity.foreground,
            destroyed = activity.isDestroyed,
            security = before.security,
            displayClearRevision = displayClearRevision.get(),
        )
        val outcome = NativeQueryReadFence(captured).read(current = ::queryReadAuthority) {
            when (command) {
                is ProductQueryCommand.Activity -> queryOwner.activity(
                    command.start, command.end, command.hourly, expectedGeneration = generation,
                )
                is ProductQueryCommand.Domains -> queryOwner.domains(
                    history = command.history,
                    decision = command.decision,
                    search = command.search,
                    startSeconds = command.start,
                    endSeconds = command.end,
                    limit = command.limit,
                    expectedGeneration = generation,
                )

                ProductQueryCommand.Network -> queryOwner.network(expectedGeneration = generation)
                ProductQueryCommand.Stats -> queryOwner.stats(expectedGeneration = generation)
            }
        }
        // This is the private serialization edge: no await may separate the final live fence
        // from encoding. An old query cannot borrow a resumed grant or a replacement window.
        return outcome.toCommandResult()
    }

    private fun queryReadAuthority(): NativeQueryReadAuthority {
        val activity = context.currentActivity as? LavaReactActivity
        val generation = product.state.value.graphGeneration
        return NativeQueryReadAuthority(
            host = activity,
            nativeHostID = activity?.nativeHostID,
            generation = generation,
            foreground = activity?.foreground == true,
            destroyed = activity?.isDestroyed != false,
            security = product.securityStateForSnapshot(generation),
            displayClearRevision = displayClearRevision.get(),
        )
    }

    /**
     * Canonical `catalog.query`: the shared "Choose Blocklists" picker reads the effective catalog
     * sections. Resolving the catalog does file I/O, so it runs off the main dispatcher; the read is
     * side-effect free and never fabricates a rule count (the picker bar is indeterminate).
     */
    private suspend fun executeCatalogQuery(command: ProductCatalogQuery): String {
        val budget = product.state.value.featureLimits.maxFilterRules
        val customSources = product.customBlocklistDefinitionsForDisplayedFilter()
        val query = withContext(Dispatchers.IO) {
            catalogQueryOwner().query(command.ids, budget, customSources)
        }
        return result("completed", value = catalogQueryJson(query))
    }

    /**
     * Canonical `activity.visibility`: hand the review-moment dwell token to the real owner. The
     * shared caller is fire-and-forget, so this always acknowledges; the owner consumes the signal.
     */
    private fun executeActivityVisibility(command: ProductActivityVisibility): String {
        activityVisibilityOwner.setVisible(
            token = command.token,
            visible = command.visible,
            startSeconds = command.start,
            endSeconds = command.end,
        )
        return result("completed")
    }

    /** Saved-rule checks and reviewed actions share the active filter's native authority. */
    private suspend fun executeDomainCheckCommand(
        command: ProductDomainCheckCommand,
        generation: Long,
        activity: LavaReactActivity,
    ): String {
        if (command is ProductDomainCheckCommand.Cancel) {
            domainChecks.cancel(command.visit, activity.nativeHostID)
            return result("completed")
        }
        if (command is ProductDomainCheckCommand.Stage) {
            if (preparationOwner.presentedFor(generation)) return result("rejected", "domain-check-stale")
            val grant = domainChecks.checked(command.visit, command.proof, activity.nativeHostID, generation,
                product.domainCheckRevisionForSnapshot(generation)) ?: return result("rejected", "domain-check-stale")
            val evidence = grant.evidence
            val profile = evidence.confirmed.configuration.profile(evidence.confirmed.configuration.activeProfileId)
                ?: return result("rejected", "domain-check-stale")
            val rules = profile.manualRules
            val action = command.action
            if (action == com.lavasec.app.foundation.filter.DomainCheckRuleAction.ALLOWED &&
                evidence.decision.reason == com.lavasec.app.foundation.filter.FilterDecisionReason.THREAT_GUARDRAIL)
                return result("rejected", "domain-check-guardrail")
            if (action == com.lavasec.app.foundation.filter.DomainCheckRuleAction.REMOVE_ALLOWED &&
                rules.allowedDomains.none { evidence.domain == it || evidence.domain.endsWith(".$it") } ||
                action == com.lavasec.app.foundation.filter.DomainCheckRuleAction.REMOVE_BLOCKED && evidence.domain !in rules.blockedDomains)
                return result("rejected", "domain-check-stale")
            val decision = if (action == com.lavasec.app.foundation.filter.DomainCheckRuleAction.ALLOWED ||
                action == com.lavasec.app.foundation.filter.DomainCheckRuleAction.REMOVE_ALLOWED) DomainDecision.ALLOWED else DomainDecision.BLOCKED
            val sampled = product.state.value
            return executeDomainCommand(ProductDomainCommand.Stage(evidence.domain, decision),
                sampled.copy(security = product.securityStateForSnapshot(generation)), generation, activity, grant, action)
        }
        command as ProductDomainCheckCommand.Query
        if (!command.refresh) withContext(Dispatchers.Main.immediate) {
            // The custom Android editor is not registered with RN's TextInputState.
            // Dismiss its actual IME for an explicit check; quiet refreshes keep focus.
            val focused = activity.currentFocus
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(focused?.windowToken ?: activity.window.decorView.windowToken, 0)
            focused?.clearFocus()
        }
        val ticket = domainChecks.begin(command.visit, activity.nativeHostID, generation)
        var cancelled = false
        val present: NativeSecurityPresenter = { _, verify, cancel ->
            activity.presentPasscodeAuthentication(verify) { cancelled = true; cancel() }
        }
        if (!product.securityAuthorize(generation, ProtectedSurface.FILTERS, present = present))
            return result(if (cancelled) "cancelled" else "rejected", "authentication")
        val fence = NativeQueryReadFence(queryReadAuthority(), ProtectedSurface.FILTERS)
        fun current(): Boolean = domainChecks.isCurrent(ticket) && context.currentActivity === activity &&
            fence.rejection(queryReadAuthority()) == null
        if (!current()) return result("rejected", "domain-check-stale")
        val parsed = try { com.lavasec.app.foundation.domain.DomainCheckInput.parse(command.input) }
            catch (failure: com.lavasec.app.foundation.domain.DomainCheckInputException) {
                return result("completed", value = JSONObject().put("status", "invalid").put("message", failure.message))
            }
        val revision = product.domainCheckRevisionForSnapshot(generation)
        if (preparationOwner.presentedFor(generation))
            return result("completed", value = JSONObject().put("status", "unavailable").put("domain", parsed.domain))
        val evidence = product.checkDomain(parsed.domain, generation)
        if (!current()) return result("rejected", "domain-check-stale")
        if (preparationOwner.presentedFor(generation) || evidence == null || evidence.revision != revision || evidence.revision != product.domainCheckRevisionForSnapshot(generation))
            return result("completed", value = JSONObject().put("status", "unavailable").put("domain", parsed.domain))
        val profile = evidence.confirmed.configuration.profile(evidence.confirmed.configuration.activeProfileId)
            ?: return result("rejected", "domain-check-stale")
        val grant = domainChecks.accept(ticket, evidence) ?: return result("rejected", "domain-check-stale")
        val reason = when (evidence.decision.reason) {
            com.lavasec.app.foundation.filter.FilterDecisionReason.DEFAULT_ALLOW -> "defaultAllow"
            com.lavasec.app.foundation.filter.FilterDecisionReason.LOCAL_ALLOWLIST -> "localAllowlist"
            com.lavasec.app.foundation.filter.FilterDecisionReason.BLOCKLIST -> "blocklist"
            com.lavasec.app.foundation.filter.FilterDecisionReason.THREAT_GUARDRAIL -> "threatGuardrail"
            com.lavasec.app.foundation.filter.FilterDecisionReason.PAUSED_ALLOW -> "pausedAllow"
            com.lavasec.app.foundation.filter.FilterDecisionReason.INVALID_DOMAIN -> "invalidDomain"
            com.lavasec.app.foundation.filter.FilterDecisionReason.PROTECTION_UNAVAILABLE -> "protectionUnavailable"
        }
        val value = JSONObject().put("status", "checked").put("domain", parsed.domain)
            .put("decision", if (evidence.decision.action == com.lavasec.app.foundation.filter.FilterAction.BLOCK) "block" else "allow")
            .put("reason", reason).put("filterID", profile.id).put("filterName", profile.name).put("filterEmoji", profile.emoji)
            .put("proof", grant.proof).put("ignoredURLParts", parsed.ignoredURLParts).put("repairedScheme", parsed.repairedScheme)
            .put("alreadyAllowed", parsed.domain in profile.manualRules.allowedDomains)
            .put("alreadyBlocked", parsed.domain in profile.manualRules.blockedDomains)
            .put("allowedExceptions", JSONArray(profile.manualRules.allowedDomains.filter { parsed.domain == it || parsed.domain.endsWith(".$it") }.sorted()))
        // Use the observed state shown by Guard. Persisted intent can differ from
        // the running VPN after process restoration and is not an Off observation.
        when (product.state.value.protection?.guard?.lifecycle) {
            com.lavasec.app.presentation.GuardLifecycleUi.PAUSED -> value.put("protection", "paused")
            com.lavasec.app.presentation.GuardLifecycleUi.INACTIVE -> value.put("protection", "off")
            else -> Unit
        }
        return result("completed", value = value)
    }

    private suspend fun executeDomainCommand(
        command: ProductDomainCommand,
        before: MainProductUiState,
        generation: Long,
        activity: LavaReactActivity,
        domainCheck: NativeDomainCheckOwner.Grant? = null,
        domainCheckAction: com.lavasec.app.foundation.filter.DomainCheckRuleAction? = null,
    ): String {
        // `domains.cancel` is the teardown of a staged review the app itself owns; it performs no
        // read, so a backgrounded/locked replay (the store queues an undeliverable cancel and
        // replays it on foreground) must be allowed to discard the staged draft rather than being
        // refused as activity-locked (which would strand it as "Unsaved filter edits").
        if (command !is ProductDomainCommand.Cancel &&
            before.isLocked(if (domainCheck == null) ProtectedSurface.ACTIVITY else ProtectedSurface.FILTERS, System.currentTimeMillis())
        ) {
            return result("rejected", if (domainCheck == null) "activity-locked" else "filters-locked")
        }
        return when (command) {
            ProductDomainCommand.EnableHistory -> queryOwner.enableHistory().toCommandResult()
            is ProductDomainCommand.Copy -> {
                clipboard.setPrimaryClip(ClipData.newPlainText(DOMAIN_CLIP_LABEL, command.domain))
                result("completed")
            }

            // Canonical `domains.stage`: stage the domain onto the ACTIVE filter's draft and open
            // its review. Mirrors the iOS `stageDomainHistoryDomainAction` + standalone token map;
            // the returned `standaloneReview` is echoed back by the shared Review screen on
            // `filter.apply` and fences that apply to exactly this staged draft.
            is ProductDomainCommand.Stage -> {
                val originalHost = NativeVisitHost(activity, activity.nativeHostID) { activity.nativeHostAlive }
                val token = UUID.randomUUID().toString()
                // Install custody before authentication or draft staging can suspend. A late
                // draft attaches to this original handle, including after host/module retirement.
                val visit = standaloneDomainReviews.begin(token, generation, originalHost)
                    ?: return result("rejected", "domains-review-unavailable")
                var delivered = false
                try {
                    var authenticationCancelled = false
                    val present: NativeSecurityPresenter = { _, verify, cancel ->
                        activity.presentPasscodeAuthentication(verify) { authenticationCancelled = true; cancel() }
                    }
                    val target = if (command.decision == DomainDecision.BLOCKED) {
                        ManualRuleTarget.BLOCKED
                    } else {
                        ManualRuleTarget.ALLOWED
                    }
                    product.consumeAuthenticationCancellation()
                    val outcome = product.stageDomainActionOwned(command.domain, target, generation, present,
                        isCurrent = { standaloneDomainReviews.isCurrent(visit) && (domainCheck == null ||
                            domainChecks.isCurrent(domainCheck, product.domainCheckRevisionForSnapshot(generation)) &&
                                product.securityStateForSnapshot(generation)?.let { it.isAuthorized(ProtectedSurface.APP_UNLOCK) && it.isAuthorized(ProtectedSurface.FILTERS) } == true) },
                        onDraftOpened = { witness ->
                            val record = StandaloneDomainReview(visit, witness, domainCheck)
                            val attached = standaloneDomainReviews.attach(visit) {
                                // Cleanup belongs to VM ownerScope, never the retiring module.
                                product.retireStandaloneDomainReview(witness)
                                clearStandaloneReview(record)
                            }
                            var previous: StandaloneDomainReview? = null
                            val retained = attached && synchronized(standaloneReviewLock) {
                                if (!standaloneDomainReviews.isCurrent(visit)) false
                                else {
                                    previous = standaloneReviewRecord
                                    standaloneReviewRecord = record
                                    standaloneReviewToken = token
                                    standaloneReviewFilterId = witness.filterId
                                    standaloneReviewGeneration = generation
                                    standaloneReviewLease = witness.draftLease
                                    true
                                }
                            }
                            previous?.let { standaloneDomainReviews.retire(it.visit) }
                            retained
                        }, domainCheckAction = domainCheckAction)
                    // Typed user cancellation remains distinct and never delivers a staged token.
                    if (authenticationCancelled || product.consumeAuthenticationCancellation()) {
                        return result("cancelled")
                    }
                    when (outcome) {
                        is DomainStageOutcome.Staged -> {
                            val retained = synchronized(standaloneReviewLock) {
                                standaloneReviewRecord?.visit === visit && standaloneDomainReviews.isCurrent(visit)
                            }
                            if (!retained) return result("rejected", "domains-review-unavailable")
                            delivered = true
                            result("completed", value = JSONObject().put("id", outcome.filterId)
                                .put("standaloneReview", token))
                        }
                        DomainStageOutcome.UnsavedActiveDraft -> result("completed", value = softRejection(
                            "Unsaved filter edits",
                            "You have unsaved changes to your active filter. Save or discard them before changing domains here.",
                        ))
                        is DomainStageOutcome.RuleRejected -> {
                            val limitReached = outcome.reason == ManualRuleRejection.ALLOWED_LIMIT_REACHED ||
                                outcome.reason == ManualRuleRejection.BLOCKED_LIMIT_REACHED
                            val hasPlus = application.plusBackend.value
                                ?.entitlement?.state?.value?.localFeaturesAvailable == true
                            result("completed", value = softRejection("Cannot change domain",
                                manualRuleRejectionMessage(outcome.reason), limitReached = limitReached && !hasPlus))
                        }
                        DomainStageOutcome.Unavailable -> result("rejected", "domains-review-unavailable")
                    }
                } finally {
                    // Also release empty failed/cancelled admissions; no host tombstones remain.
                    if (!delivered) standaloneDomainReviews.retire(visit)
                }
            }

            // Canonical `domains.cancel`: the shared Review screen discards an unconfirmed
            // standalone review on unmount. Only the exact live token acts; the staged draft is
            // discarded and the detail context closed so the active filter is left untouched.
            is ProductDomainCommand.Cancel -> {
                val record = synchronized(standaloneReviewLock) { standaloneReviewRecord }
                val token = record?.visit?.token
                val capturedGeneration = record?.visit?.generation
                val capturedLease = record?.witness?.draftLease
                if (record != null && token != null && token == command.token && capturedGeneration == generation && capturedLease != null) {
                    standaloneDomainReviews.retire(record.visit)
                    // Host callback and ordinary Cancel join one exact lease-bound cleanup job.
                    product.retireStandaloneDomainReview(record.witness).await()
                }
                // An unknown/absent token is an idempotent no-op (double unmount, stale pop).
                result("completed")
            }
        }
    }

    /**
     * Canonical `logs.clear` / `logs.export`. `clear` inherits the Activity authorization when the
     * caller is the headless Activity surface and the kind is activity-scoped, and App-Settings
     * authorization otherwise (matching `LavaAppSettings.clearLogs`); `export` builds the same
     * local archive the native Privacy row does. Every kind is mapped to the concrete privacy
     * owner scope, and an unknown kind is a typed refusal, never a silent no-op.
     */
    private suspend fun executeLogsCommand(
        command: ProductLogsCommand,
        generation: Long,
        activity: LavaReactActivity,
        before: MainProductUiState,
    ): String {
        val activityScoped = command is ProductLogsCommand.Clear &&
            command.fromActivity &&
            command.kind in ACTIVITY_LOG_KINDS
        if (activityScoped && before.isLocked(ProtectedSurface.ACTIVITY, System.currentTimeMillis())) {
            return result("rejected", "activity-locked")
        }
        var authenticationCancelled = false
        val present: NativeSecurityPresenter = { _, verify, cancel ->
            activity.presentPasscodeAuthentication(verify) { authenticationCancelled = true; cancel() }
        }
        val surface = if (activityScoped) ProtectedSurface.ACTIVITY else ProtectedSurface.APP_SETTINGS
        if (!authorizeCommand(generation, surface, present, { authenticationCancelled = true })) {
            return authenticationResult(result("rejected", "unauthorized"), authenticationCancelled)
        }
        return when (command) {
            is ProductLogsCommand.Clear -> clearLocalLogs(command.kind)
            is ProductLogsCommand.Export -> exportLocalLogs(activity, command.domains)
        }
    }

    private suspend fun clearLocalLogs(kind: String): String {
        if (kind !in LOG_CLEAR_CONFIRMATIONS) return result("rejected", "unknown-log")
        // Authorization has already admitted this exact clear. Retire pre-clear queries before
        // any durable owner can suspend or publish a partially cleared multi-owner state.
        activityShareCards.revoke()
        displayClearRevision.incrementAndGet()
        val cleared = try {
            when (kind) {
                "Clear filtering counts" ->
                    product.clearLocalActivity(countsAndUptime = true, domainHistory = false)

                "Clear domain history" ->
                    product.clearLocalActivity(countsAndUptime = false, domainHistory = true)

                "Clear network activity" -> product.clearNetworkActivity()
                "Clear Lava Guard progress" -> product.clearGuardProgress()
                // Canonical "Clear all logs": filtering counts, domain history, numeric DNS fallback
                // evidence, network activity and unearned guard progress. Android has separate stores, so
                // each class is cleared through its own serialized privacy mutation and success is
                // reported only when every class fully completed, including diagnostics cleanup.
                "Clear all logs" -> {
                    val reports = product.clearLocalActivity(countsAndUptime = true, domainHistory = true)
                    val network = product.clearNetworkActivity()
                    val guard = product.clearGuardProgress()
                    reports && network && guard
                }

                else -> return result("rejected", "unknown-log")
            }
        } finally {
            // Retire reads admitted while pending, also when a durable clear throws/cancels.
            activityShareCards.revoke()
            displayClearRevision.incrementAndGet()
        }
        return if (cleared) {
            result("completed", value = LOG_CLEAR_CONFIRMATIONS[kind] ?: "All logs cleared.")
        } else {
            result("rejected", "clear-failed")
        }
    }

    private suspend fun exportLocalLogs(activity: LavaReactActivity, domains: Boolean): String {
        val exported = product.exportLocalActivity(
            LocalActivityExportOptions(countsAndUptime = true, domainHistory = domains, networkActivity = true),
        )
        if (!exported) return result("rejected", "export-failed")
        val share = product.createExportShareIntent() ?: return result("rejected", "export-failed")
        activity.startActivity(Intent.createChooser(share, null))
        return result("completed")
    }

    private fun QueryOutcome.toCommandResult(): String = when (this) {
        is QueryOutcome.Value -> result("completed", value = ProductQueryWire.encode(value))
        is QueryOutcome.Rejected -> result("rejected", reason)
    }

    /** Strictly decodes one `settings.set` request, or `null` when key/value are malformed. */
    private fun parseSettingsCommand(request: JSONObject): ProductSettingsCommand.Set? {
        val parsed = ProductCommandParser.parse("settings.set", JsonProductCommandInput(request))
        return (parsed as? ProductCommandParse.Supported)?.command as? ProductSettingsCommand.Set
    }

    /**
     * Canonical `settings.set`. Every key/type/domain rule and owner delegation lives in
     * [SettingsCommandDispatcher], so the bridge adds no policy of its own. An iOS-only key
     * returns its capability classification (`unavailable-per-platform`) rather than a success.
     */
    private suspend fun executeSettingsCommand(command: ProductSettingsCommand): String {
        val set = command as? ProductSettingsCommand.Set
            ?: return result("rejected", ProductCommandReasons.INVALID_ARGUMENT)
        activityShareCards.revoke()
        val securityCommand = set.key == "biometrics" || set.key.startsWith("protectedActions.")
        if (securityCommand) product.consumeAuthenticationCancellation()
        val response = when (val outcome = settingsDispatcher.dispatch(set.key, set.value)) {
            is SettingsSetResult.Applied ->
                result("completed", value = outcome.capability.wireValue())
            is SettingsSetResult.CapabilityUnavailable ->
                result("rejected", outcome.capability.wireValue(), value = outcome.capability.wireValue())
            SettingsSetResult.Unsupported ->
                result("rejected", ProductCommandReasons.UNSUPPORTED_SETTING)
            SettingsSetResult.UnknownKey ->
                result("rejected", ProductCommandReasons.UNKNOWN_SETTING)
            is SettingsSetResult.InvalidValue ->
                result("rejected", ProductCommandReasons.INVALID_SETTING_VALUE, value = outcome.key)
            SettingsSetResult.Refused ->
                result("rejected", ProductCommandReasons.SETTING_REFUSED)
        }
        return if (securityCommand) authenticationResult(response, product.consumeAuthenticationCancellation()) else response
    }

    private fun shareFailureReason(reason: FilterShareFailureReason): String = when (reason) {
        FilterShareFailureReason.UNKNOWN_FILTER -> "unknown-filter"
        FilterShareFailureReason.EMPTY -> "share-empty"
        FilterShareFailureReason.CONTAINS_PRIVATE_SOURCE_PARAMETERS -> "share-private-source"
        FilterShareFailureReason.EXCEEDS_SHARE_CAPACITY -> "share-exceeds-capacity"
        FilterShareFailureReason.CODE_UNAVAILABLE -> "share-code-unavailable"
        FilterShareFailureReason.QR_RENDERER_UNAVAILABLE -> "share-card-unavailable"
        FilterShareFailureReason.DOCUMENT_PICKER_UNAVAILABLE -> "share-document-unavailable"
        FilterShareFailureReason.CAMERA_SCANNER_UNAVAILABLE -> "share-camera-unavailable"
    }

    /**
     * Canonical import flows (`import`/`importCode`/`importScan`) on Android. There is no native
     * import UI, so each method resolves through the [FilterShareOwner] platform-adapter/review
     * boundary: camera and photo acquire bounded QR images on-device, while a supplied code is
     * decoded and reconciled by [FilterShareOwner.reviewImport]. The owner
     * mutates nothing; this returns the reviewed subset (or the precise refusal) for the shared
     * screen. A method that could produce a candidate in the future funnels it through the same
     * review, so the platform adapter never reaches the saved library.
     */
    /**
     * A fresh writable cache file the camera capture writes into, exposed through the dedicated
     * `${applicationId}.camera` FileProvider. A failed provider resolution returns null so the host
     * reports the camera method unavailable rather than crashing the launch.
     */
    private fun newCameraCaptureUri(context: Context): Uri? = runCatching {
        val directory = File(context.cacheDir, "camera-captures").apply { mkdirs() }
        val file = File.createTempFile("scan-", ".jpg", directory)
        // Remember the transient capture so every outcome (success, cancel, launch/decode failure)
        // deletes it; a stale full-resolution photo must not linger in the cache.
        cameraCaptureFile?.delete()
        cameraCaptureFile = file
        androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.camera", file)
    }.getOrNull()

    private suspend fun executeImportFlow(
        route: String,
        request: JSONObject,
        before: MainProductUiState,
        activity: LavaReactActivity,
    ): String {
        val visit = importVisit
        if (visit == null || request.optString("visitID") != visit.id ||
            !visit.isCurrent(currentVisitHost(), before.graphGeneration)) return result("rejected", "stale-review")
        val code = request.optString("code").takeIf { it.isNotBlank() }
        val target = request.optString("target").takeIf { it.isNotBlank() }
        if (code == null) {
            if (route !in setOf(NativeFlow.IMPORT_SCAN.wireName, NativeFlow.IMPORT.wireName))
                return result("rejected", "import-presentation-unavailable")
            // The production device adapter owns the ActivityResultContracts plumbing for this
            // attempt; it binds the foreground activity's registry and is disposed once the
            // user/OS returns. An unlaunchable host refuses typed, never fabricating a candidate.
            val devicePort = FilterShareDeviceAdapter(
                AndroidFilterShareDeviceHost(
                    context = activity,
                    registry = activity.activityResultRegistry,
                    // The canonical import destination is Photo: decode a selected image locally,
                    // through the same bounded QR reader as the camera, never as UTF-8 text.
                    documentMimeType = "image/*",
                    decodeDocumentImage = { uri -> AndroidQrImageDecoder.decode(activity, uri) },
                    // The camera QR method: a writable FileProvider target the capture writes to
                    // and the on-device ZXing reader decodes. Both must be present for
                    // `canLaunchCamera`; a failed URI or decode yields a typed refusal, never a
                    // fabricated candidate.
                    cameraOutputUri = { newCameraCaptureUri(activity) },
                    decodeCameraImage = { uri -> AndroidQrImageDecoder.decode(activity, uri) },
                ),
            )
            return try {
                val acquisition = visit.acquire {
                    if (route == NativeFlow.IMPORT_SCAN.wireName) devicePort.requestCameraAcquisition()
                    else devicePort.requestDocumentAcquisition()
                }
                if (importVisit !== visit || !visit.isCurrent(currentVisitHost(), product.state.value.graphGeneration))
                    result("rejected", "stale-review")
                else acquisitionResult(acquisition, before.graphGeneration)
            } finally {
                // Delete the transient camera capture on every outcome before disposing the host.
                cameraCaptureFile?.delete()
                cameraCaptureFile = null
                devicePort.dispose()
            }
        }
        return reviewImport(code, target, before.graphGeneration)
    }

    /**
     * Route one bounded device acquisition through the owner's review boundary. A
     * [FilterShareAcquisitionResult.Candidate] is decoded and reconciled by [reviewImport];
     * a rejected payload keeps its input-failure vocabulary, while a refused acquisition
     * (permission denied / cancelled / no adapter) is distinct from a missing feature so a
     * dismissed picker never reads as unavailability. Nothing here mutates the library.
     */
    private fun acquisitionResult(
        acquisition: FilterShareAcquisitionResult,
        generation: Long?,
    ): String = when (acquisition) {
        is FilterShareAcquisitionResult.Candidate ->
            reviewImport(acquisition.input.rawInput, null, generation)
        is FilterShareAcquisitionResult.Rejected -> result("rejected", importFailureReason(acquisition.reason))
        is FilterShareAcquisitionResult.Refused -> result("rejected", acquisitionRefusalReason(acquisition.refusal))
    }

    private fun candidateResult(
        candidate: FilterShareCandidateResult,
        generation: Long?,
    ): String = when (candidate) {
        is FilterShareCandidateResult.Candidate -> reviewImport(candidate.input.rawInput, null, generation)
        is FilterShareCandidateResult.Unavailable -> result("rejected", shareFailureReason(candidate.reason))
    }

    /**
     * Decode one raw payload and store the review the shared screen will later confirm. The
     * [target] (null = add-as-new) binds the review to the filter a replacement would overwrite,
     * so the owner's confirmation can refuse a target that changed after review. The raw code stays native; only the bounded, reconciled review projection leaves the bridge.
     */
    private fun reviewImport(raw: String, target: String?, generation: Long?): String =
        when (val outcome = shareOwner.reviewImport(raw, target)) {
            is FilterShareImportReviewResult.Reviewed -> {
                importReviewID = java.util.UUID.randomUUID().toString()
                importReviewLibrary = product.currentFilterLibrary()
                importReview = outcome.review
                importReviewTarget = target
                importReviewGeneration = generation
                importReviewRaw = raw
                result("completed", value = importReviewValue(outcome))
            }
            is FilterShareImportReviewResult.Unavailable -> {
                clearImportReview()
                result("rejected", importFailureReason(outcome.reason))
            }
        }

    /**
     * Confirm the stored reviewed import and persist the confirmed content through the existing
     * filter owner. The confirmation is the one mutation boundary: nothing is written until
     * [shareOwner.confirmAsNewFilter]/[shareOwner.confirmReplace] produce the content, and the
     * persist runs under the ordinary filter-editing authorization. A missing review, a target
     * that does not match the one reviewed, a retired generation, or an owner refusal is a typed
     * rejection — never a partial import.
     */
    private suspend fun executeImportApply(
        request: JSONObject,
        before: MainProductUiState,
        generation: Long,
        activity: LavaReactActivity,
    ): String {
        val visit = importVisit
        if (visit == null || !ownsImportReview(request, generation)) return result("rejected", "stale-review")
        val library = importReviewLibrary ?: return result("rejected", "stale-review")
        val review = importReview
        val target = request.optString("target").takeIf { it.isNotBlank() }
        if (review == null || importReviewGeneration != before.graphGeneration) {
            clearImportReview()
            return result("rejected", "stale-review")
        }
        if (target != importReviewTarget) return result("rejected", "stale-review")
        val confirmReview = review
        val confirmation = if (target == null) {
            shareOwner.confirmAsNewFilter(confirmReview)
        } else {
            shareOwner.confirmReplace(
                review = confirmReview,
                targetFilterId = target,
                targetIsActive = before.filterLibrary?.activeFilterId == target,
                confirmedActiveReplacement = request.optBoolean("confirmed"),
            )
        }
        clearImportReview()
        return when (confirmation) {
            is ShareableFilterImportConfirmation.AddNewFilter ->
                persistImported(confirmation.applied, targetFilterId = null, generation, activity, visit, library)
            is ShareableFilterImportConfirmation.ReplaceContents ->
                persistImported(confirmation.applied, targetFilterId = target, generation, activity, visit, library)
            is ShareableFilterImportConfirmation.Refused ->
                result("rejected", importRefusalReason(confirmation.reason))
        }
    }

    private suspend fun persistImported(
        configuration: ShareableFilterConfiguration,
        targetFilterId: String?,
        generation: Long,
        activity: LavaReactActivity,
        visit: NativeImportVisit,
        library: com.lavasec.app.foundation.filter.FilterLibraryDraft,
    ): String {
        var authenticationCancelled = false
        val present: NativeSecurityPresenter = { _, verify, cancel ->
            activity.presentPasscodeAuthentication(verify) { authenticationCancelled = true; cancel() }
        }
        product.consumeAuthenticationCancellation()
        val outcome = product.commitImportedFilter(configuration, targetFilterId, generation, present,
            expectedLibrary = library,
            isCurrent = { activity.foreground && importVisit === visit && visit.isCurrent(currentVisitHost(), product.state.value.graphGeneration) &&
                product.securityStateForSnapshot(generation)?.isAuthorized(ProtectedSurface.APP_UNLOCK) == true &&
                product.securityStateForSnapshot(generation)?.isAuthorized(ProtectedSurface.FILTERS) == true },
        )
        // The owner collapses the tri-state; recover a dismissed biometric prompt from the record.
        val cancelled = authenticationCancelled || product.consumeAuthenticationCancellation()
        return when (outcome) {
            is FilterLibraryMutationResult.Applied -> result("completed")
            is FilterLibraryMutationResult.Rejected -> authenticationResult(
                result("rejected", importLibraryReason(outcome.reason)),
                cancelled,
            )
            null,
            FilterLibraryMutationResult.Superseded,
            -> authenticationResult(result("rejected", "stale-review"), cancelled)
            is FilterLibraryMutationResult.Failed,
            is FilterLibraryMutationResult.FailedSource,
            FilterLibraryMutationResult.ProtectionUnavailable,
            -> authenticationResult(result("rejected", "import-refused"), cancelled)
        }
    }

    private fun importLibraryReason(reason: FilterLibraryRejection): String = when (reason) {
        FilterLibraryRejection.UNKNOWN_FILTER -> "unknown-filter"
        FilterLibraryRejection.LIMIT_REACHED -> "library-limit-reached"
        FilterLibraryRejection.FROZEN_FILTER -> "library-frozen-filter"
        else -> "import-refused"
    }

    private fun importRefusalReason(reason: ShareableFilterImportRefusal): String = when (reason) {
        ShareableFilterImportRefusal.NothingToImport -> "nothing-to-import"
        ShareableFilterImportRefusal.StaleReview -> "stale-review"
        ShareableFilterImportRefusal.ActiveReplacementNotConfirmed -> "active-replacement-not-confirmed"
        ShareableFilterImportRefusal.PlanNotFullyAccepted -> "plan-not-fully-accepted"
    }

    private fun ownsImportReview(request: JSONObject, generation: Long): Boolean {
        val visit = importVisit ?: return false
        return request.optString("visitID") == visit.id && request.optString("reviewID") == importReviewID &&
            visit.isCurrent(currentVisitHost(), generation) && importReviewGeneration == generation &&
            product.securityStateForSnapshot(generation)?.isAuthorized(ProtectedSurface.FILTERS) == true
    }

    private fun retireImportVisit() {
        importVisit?.retire()
        importVisit = null
        clearImportReview()
    }

    private fun clearImportReview() {
        importReviewID = null
        importReviewLibrary = null
        importReview = null
        importReviewTarget = null
        importReviewGeneration = null
        importReviewRaw = null
    }

    private fun importReviewValue(outcome: FilterShareImportReviewResult.Reviewed): JSONObject {
        val applied = outcome.review.plan.applied
        val diff = outcome.review.replacement.selectionDiff
        fun group(title: String, removed: List<String>, added: List<String>) = JSONObject()
            .put("title", title).put("removed", JSONArray(removed)).put("added", JSONArray(added))
        return JSONObject()
            .put("reviewID", importReviewID)
            .put("blockedDomains", JSONArray(applied.blockedDomains.sorted()))
            .put("allowedDomains", applied.allowedDomains?.let { JSONArray(it.sorted()) } ?: JSONObject.NULL)
            .put("customBlocklists", JSONArray(applied.customBlocklists.map {
                JSONObject().put("id", it.id).put("name", it.displayName)
            }))
            .put("dropped", JSONArray(outcome.review.plan.dropped.map { JSONObject().put("kind", it.kind.name).put("label", it.label) }))
            .put("replacementGroups", JSONArray(listOf(
                group("Blocklists", diff.removedBlocklistIDs, diff.addedBlocklistIDs),
                group("Blocked domains", diff.removedBlockedDomains, diff.addedBlockedDomains),
                group("Allowed exceptions", diff.removedAllowedDomains, diff.addedAllowedDomains),
                group("Custom blocklists", outcome.review.replacement.removedCustomBlocklists.map { it.displayName },
                    outcome.review.replacement.addedCustomBlocklists.map { it.displayName }),
            )))
            .put("blocklistIds", JSONArray(applied.enabledBlocklistIDs.toList()))
            .put("blockedDomainCount", applied.blockedDomains.size)
            .put("allowedDomainCount", applied.allowedDomains?.size ?: JSONObject.NULL)
            .put("droppedCount", outcome.review.plan.dropped.size)
            .put("unknownSourceIds", JSONArray(outcome.unknownSourceIds.toList()))
            .put("canConfirm", outcome.review.canConfirm)
    }

    private fun importFailureReason(reason: FilterShareImportFailureReason): String = when (reason) {
        FilterShareImportFailureReason.NOT_A_CODE -> "not-a-code"
        FilterShareImportFailureReason.INVALID_LINK -> "invalid-link"
        FilterShareImportFailureReason.UNSUPPORTED_VERSION -> "unsupported-version"
        FilterShareImportFailureReason.INTEGRITY_CHECK_FAILED -> "integrity-check-failed"
        FilterShareImportFailureReason.PAYLOAD_TOO_LARGE -> "payload-too-large"
        FilterShareImportFailureReason.MALFORMED_PAYLOAD -> "malformed-payload"
        FilterShareImportFailureReason.UNKNOWN_FILTER -> "unknown-filter"
    }

    /**
     * Distinguishes a withheld acquisition (permission denial, user cancellation, or a device with
     * no registered adapter) from the input-failure vocabulary. A cancelled picker is a user fact,
     * never a missing feature, so each refusal has its own stable wire code.
     */
    /** The current entitlement fact from the plus backend; Unknown when unread/unavailable. */
    private fun entitlementTierFact(): EntitlementTierFact {
        val plus = application.plusBackend.value?.entitlement?.state?.value
            ?: return EntitlementTierFact.Unknown
        if (!plus.available) return EntitlementTierFact.Unknown
        return EntitlementTierFact.Known(
            if (plus.localFeaturesAvailable) SubscriptionTier.PLUS else SubscriptionTier.FREE,
        )
    }

    private fun acquisitionRefusalReason(refusal: FilterShareAcquisitionRefusal): String = when (refusal) {
        FilterShareAcquisitionRefusal.PERMISSION_DENIED -> "share-permission-denied"
        FilterShareAcquisitionRefusal.CANCELLED -> "share-cancelled"
        FilterShareAcquisitionRefusal.ADAPTER_UNAVAILABLE -> "share-adapter-unavailable"
    }

    /**
     * Delegates one validated canonical filter/library command to the shared
     * [com.lavasec.app.MainProductViewModel] owner. No mutation, policy, or authorization is
     * re-implemented here: the owner applies its generation fence, `ProtectedSurface.FILTERS`
     * authentication, and draft/library invariants, and this only maps its outcome to the
     * canonical wire result.
     */
    private suspend fun runFilterPreparation(
        filterId: String, generation: Long, present: NativeSecurityPresenter, origin: String,
    ): Boolean {
        val draft = product.sharedFilterDraft(generation, filterId)
        val newFilter = product.sharedNewFilter(generation)
        val lease = product.sharedFilterDraftLease(generation, filterId)
        var retry = false
        var previous: NativePreparationOwner.Attempt? = null
        while (true) {
            var attempt: NativePreparationOwner.Attempt? = null
            val progress = com.lavasec.app.product.FilterPreparationProgress(
                isCurrent = { attempt?.let(preparationOwner::isCurrent) == true },
                report = { phase -> attempt?.let { preparationOwner.advance(it, phase) } },
                bindWorker = { job -> attempt?.let { preparationOwner.attach(it, job) } ?: job.cancel() },
                publicationGuard = com.lavasec.app.platform.NativeFilterPublicationGuard { publication ->
                    val owned = attempt
                    if (owned == null) com.lavasec.app.platform.FilterMutationPublishResult.Failed(
                        com.lavasec.app.platform.FilterMutationFailure.COMMIT_FAILED)
                    else preparationOwner.publish(owned, publication)
                },
            )
            val admitted = {
                val revision = product.securityStateForSnapshot(generation)?.authenticationRevision
                attempt = preparationOwner.begin(filterId, origin, replacing = previous) {
                    product.state.value.graphGeneration == generation &&
                        product.state.value.productAvailable &&
                        revision != null && product.securityStateForSnapshot(generation)?.authenticationRevision == revision &&
                        sharedFieldsReadable(NativeSharedForegroundKind.CREATE_FILTER)
                }
                if (attempt == null) throw CancellationException("Preparation host retired")
            }
            val committed = try {
                withContext(progress) {
                    if (retry) product.retryFilterPreparation(generation, admitted, present)
                    else product.commitFilterReview(filterId, generation, admitted, present)
                }
            } catch (cancelled: CancellationException) {
                // Exact Close cancels the native worker, not this command's owner.
                // A real module cancellation still propagates to its supervisor.
                currentCoroutineContext().ensureActive()
                false
            } catch (_: Exception) {
                false
            }
            val owned = attempt ?: run { previous?.let(preparationOwner::release); return false }
            if (!preparationOwner.isCurrent(owned)) {
                preparationOwner.release(owned)
                return committed
            }
            val sourceFailure = if (!committed) product.filterSourceFailureForPreparation() else null
            preparationOwner.finishOperation(owned, committed,
                message = context.getString(com.lavasec.app.presentation.R.string.filters_prepare_failed_detail),
                retryable = !committed && product.canRetryFilterPreparation(generation),
                failureReason = sourceFailure?.failure?.let(::customBlocklistSourceFailureWireCode),
                failureSource = sourceFailure?.sourceName)
            val decision = try {
                preparationOwner.awaitDecision(owned)
            } catch (cancelled: CancellationException) {
                preparationOwner.release(owned)
                throw cancelled
            }
            if (decision != NativePreparationOwner.Decision.RETRY) preparationOwner.release(owned)
            // Animation acknowledges a committed operation; it never creates or undoes it.
            if (committed) { product.consumeFilterPreparationSuccess(); return true }
            when (decision) {
                NativePreparationOwner.Decision.RETRY -> { retry = true; previous = owned }
                NativePreparationOwner.Decision.KEEP_CURRENT -> {
                    if (lease != null) product.discardFilterDraft(filterId, draft, newFilter, generation, lease)
                    return false
                }
                else -> return false
            }
        }
    }

    private suspend fun executeFilterCommand(
        command: ProductFilterCommand,
        before: MainProductUiState,
        generation: Long,
        activity: LavaReactActivity,
    ): String {
        var authenticationCancelled = false
        val present: NativeSecurityPresenter = { _, verify, cancel ->
            activity.presentPasscodeAuthentication(verify) { authenticationCancelled = true; cancel() }
        }
        // Clear any outcome from an earlier command; the owner-backed methods below record their own.
        product.consumeAuthenticationCancellation()
        fun known(id: String) = before.filterLibrary?.filter(id) != null
        fun editable(id: String) = known(id) || before.newFilter?.id == id
        fun preparationBusy() = before.filterPreparation is FilterPreparationUiState.Preparing

        val outcome = when (command) {
            is ProductFilterCommand.Open -> {
                if (!editable(command.id)) return result("rejected", "unknown-filter")
                if (preparationBusy()) return result("rejected", "preparation-in-progress")
                val visitHost = currentVisitHost()?.takeIf { it.owner === activity }
                    ?: return result("rejected", "host-closed")
                val current = { !filterDetailHostsClosed.get() && visitHost.matches(currentVisitHost()) &&
                    activity.foreground && product.state.value.graphGeneration == generation }
                val witness = product.openFilterDetailOwned(command.id, generation, current)
                    ?: return result("rejected", "stale-generation")
                rememberFilterDetail(visitHost, witness)
                if (!current()) return result("rejected", "stale-generation")
                result("completed")
            }
            is ProductFilterCommand.Close -> {
                // Only the displayed filter can close; a mismatched id is an honest no-op.
                product.closeFilterDetail(command.id)
                result("completed")
            }
            is ProductFilterCommand.Edit -> {
                if (!editable(command.id)) return result("rejected", "unknown-filter")
                if (preparationBusy()) return result("rejected", "preparation-in-progress")
                if (product.beginFilterEdit(command.id, generation, present)) result("completed")
                else result("rejected", "edit-refused")
            }
            is ProductFilterCommand.Cancel -> {
                // Discard is local teardown; it never requires an authorization prompt.
                if (before.selectedFilterId == command.id) product.discardFilterDraft(command.id)
                result("completed")
            }
            is ProductFilterCommand.Save -> executeFilterSave(command, before, generation, present)
            is ProductFilterCommand.Review -> {
                if (!editable(command.id)) return result("rejected", "unknown-filter")
                if (!product.openFilterReview(command.id, generation, present)) {
                    result("rejected", "review-refused")
                } else {
                    val token = UUID.randomUUID().toString()
                    reviewToken = token
                    reviewTokenKey = reviewKey(generation, command.id)
                    product.publishConfigurationWitness(generation, entitlementTierFact())
                    result("completed", value = token)
                }
            }
            is ProductFilterCommand.Apply -> {
                val key = reviewKey(generation, command.id)
                // A standalone Domain History review is fenced by its own token AND must match the
                // filter whose draft it staged. While one is pending for this filter the exact token
                // is REQUIRED — an absent token is stale, not an ordinary apply — so a caller cannot
                // commit the staged domain change with only the ordinary review token. A token with
                // no pending review, or one for a different filter, is likewise stale.
                val standaloneRecord = synchronized(standaloneReviewLock) {
                    standaloneReviewRecord?.takeIf { it.witness.filterId == command.id }
                }
                val standalonePending = standaloneReviewToken != null && standaloneReviewFilterId == command.id
                val standaloneMismatch = if (standalonePending) {
                    command.standaloneReview != standaloneReviewToken ||
                        standaloneReviewGeneration != generation || standaloneRecord == null ||
                        !standaloneDomainReviews.isCurrent(standaloneRecord.visit) ||
                        standaloneRecord.domainCheck?.let { it.evidence.revision != product.domainCheckRevisionForSnapshot(generation) } == true
                } else {
                    command.standaloneReview != null
                }
                // The configuration witness strengthens the token fence: when it was captured at
                // review, a configuration change (rename/rules/emoji/active filter/resolver/
                // protection level/privacy toggles/entitlement) invalidates the review. When the
                // witness could not be captured (incomplete configuration), the token fence alone
                // applies so an otherwise-valid review is never permanently refused.
                val publishedWitness = product.configurationWitness
                val witnessInvalidated = publishedWitness != null &&
                    product.captureConfigurationWitness(generation, entitlementTierFact()) != publishedWitness
                if (standaloneMismatch ||
                    reviewToken == null || reviewToken != command.review || reviewTokenKey != key
                ) {
                    result("rejected", "stale-review")
                } else if (witnessInvalidated) {
                    result("rejected", "stale-review")
                } else {
                    val committed = runFilterPreparation(command.id, generation, present,
                        if (standaloneRecord == null) "filters" else "domainHistory")
                    if (committed) {
                        reviewToken = null
                        reviewTokenKey = null
                        // Release the exact successful standalone visit without draft discard.
                        standaloneRecord?.let {
                            standaloneDomainReviews.release(it.visit)
                            clearStandaloneReview(it)
                        }
                        product.clearConfigurationWitness()
                        result("completed")
                    } else {
                        product.consumeFilterSourceFailure()?.let { customSourceFailureResult(it) }
                            ?: result("rejected", "apply-refused")
                    }
                }
            }
            is ProductFilterCommand.Switch -> {
                if (!known(command.id)) return result("rejected", "unknown-filter")
                if (product.requestFilterProfileApply(command.id, generation, present)) result("completed")
                else result("rejected", "switch-refused")
            }
            is ProductFilterCommand.Delete -> {
                if (product.deleteFilters(setOf(command.id), generation, fresh = true, present = present)) result("completed")
                else result("rejected", "delete-refused")
            }
            is ProductFilterCommand.Create -> {
                if (product.createFilter(command.name, command.duplicateFromId, generation, present)) {
                    result("completed")
                } else {
                    result("rejected", "create-refused")
                }
            }
            is ProductFilterCommand.Rename -> {
                if (product.renameFilter(command.id, command.name, generation, emoji = command.emoji, present = present)) result("completed")
                else result("rejected", "rename-refused")
            }
            is ProductFilterCommand.Domain -> {
                if (!editable(command.id)) return result("rejected", "unknown-filter")
                if (command.remove) {
                    val target = if (command.decision == DomainDecision.BLOCKED) {
                        ManualRuleTarget.BLOCKED
                    } else {
                        ManualRuleTarget.ALLOWED
                    }
                    if (product.removeDomainFromDraft(command.id, command.domain, target, generation, present)) {
                        result("completed")
                    } else {
                        result("rejected", "domain-refused")
                    }
                } else {
                    val outcome = if (command.decision == DomainDecision.BLOCKED) {
                        product.addBlockedDomainToDraft(command.id, command.domain, generation, present)
                    } else {
                        product.addAllowedExceptionToDraft(command.id, command.domain, generation, present)
                    }
                    when (outcome) {
                        is ManualRuleEditResult.Applied -> result("completed")
                        is ManualRuleEditResult.Rejected -> result(
                            "completed",
                            value = org.json.JSONObject()
                                .put("isAccepted", false)
                                .put("reason", outcome.reason.name.lowercase(Locale.ROOT))
                                .put("title", "Cannot add domain")
                                .put("message", manualRuleRejectionMessage(outcome.reason)),
                        )
                        null -> result("rejected", "domain-refused")
                    }
                }
            }
            is ProductFilterCommand.UndoDomain ->
                result(if (product.undoDomainInDraft(command.id, command.domain, generation, present)) "completed" else "rejected")
            is ProductFilterCommand.UndoList ->
                result(if (product.undoSourceInDraft(command.id, command.sourceID, generation, present)) "completed" else "rejected")
            is ProductFilterCommand.Lists ->
                result(if (product.replaceSourcesInDraft(command.id, command.ids.toSet(), generation, present)) "completed" else "rejected")
            is ProductFilterCommand.CustomList -> {
                if (!editable(command.id)) return result("rejected", "unknown-filter")
                if (preparationBusy()) return result("rejected", "preparation-in-progress")
                val host = currentVisitHost()?.takeIf { it.owner === activity && it.matches(currentVisitHost()) }
                    ?: return result("rejected", "custom-entry-expired")
                val token = UUID.randomUUID().toString()
                customBlocklistEditorSessions[token] = command.id
                if (!rememberCustomEntry(SharedCustomEntry(token, "blocklist", generation, host, filterId = command.id,
                    draftLease = product.sharedFilterDraftLease(generation, command.id)))) {
                    customBlocklistEditorSessions.remove(token, command.id)
                    return result("rejected", "custom-entry-expired")
                }
                result("completed", value = token)
            }
            is ProductFilterCommand.AddCustomList -> {
                val filterId = customBlocklistEditorSessions[command.token]
                    ?: return result("rejected", "custom-entry-expired")
                val entry = customEntries[command.token]
                val lease = entry?.draftLease ?: return result("rejected", "custom-entry-expired")
                if (entry.generation != generation || entry.host.owner !== activity ||
                    !entry.host.matches(currentVisitHost()) || product.sharedFilterDraftLease(generation, filterId) != lease) return result("rejected", "custom-entry-expired")
                if (!editable(filterId)) return result("rejected", "unknown-filter")
                when (
                    val added = product.addCustomBlocklistToDraft(
                        filterId = filterId,
                        name = command.name,
                        url = command.url,
                        format = command.format,
                        expectedGeneration = generation,
                        present = present,
                        expectedLease = lease,
                    )
                ) {
                    is CustomBlocklistEditResult.Applied -> {
                        customEntryHosts.retire(entry.id, entry.generation)
                        result("completed", value = added.source.id)
                    }
                    is CustomBlocklistEditResult.Rejected ->
                        result("rejected", customBlocklistRejectionReason(added.reason))
                    null -> result("rejected", "list-refused")
                }
            }
            is ProductFilterCommand.DeleteCustomList -> {
                if (!editable(command.id)) return result("rejected", "unknown-filter")
                if (product.deleteCustomBlocklistFromDraft(command.id, command.sourceID, generation, present)) {
                    result("completed")
                } else {
                    result("rejected", "list-refused")
                }
            }
            is ProductFilterCommand.RemoveList -> {
                if (!editable(command.id)) return result("rejected", "unknown-filter")
                if (product.toggleSourceInDraft(command.id, command.sourceID, false, generation, present)) {
                    result("completed")
                } else {
                    result("rejected", "list-refused")
                }
            }
            ProductFilterCommand.RestoreDefaults -> {
                if (product.restoreDefaultFilters(generation, present)) result("completed")
                else result("rejected", "restore-refused")
            }
            is ProductFilterCommand.Refresh -> executeCatalogRefresh(command, before)
        }
        // A biometric-prompt dismissal returns from the owner as an ordinary `false`; the recorded
        // tri-state recovers it so the shared screen suppresses cancellation instead of an error.
        return authenticationResult(
            outcome,
            authenticationCancelled || product.consumeAuthenticationCancellation(),
        )
    }

    /**
     * Canonical `filter.refresh`: a validated remote-catalog refresh for the active filter.
     *
     * A refresh addressed to a filter that is not the active one is an honest no-op success (the
     * active filter is the only one the authority recompiles). For the active filter the owner's
     * typed outcome maps to a stable wire result. A selected catalog is installed into the live
     * filter authority by the owner's installer before the result is reported, so an
     * `updated`/`unchanged` completion proves the durable artifact was recompiled; an offline,
     * invalid, stale-rollback, persist-failed or install-failed refresh is a rejection and leaves
     * the resident artifact and the effective catalog untouched.
     */
    private suspend fun executeCatalogRefresh(
        command: ProductFilterCommand.Refresh,
        before: MainProductUiState,
    ): String {
        if (before.filterLibrary?.activeFilterId != command.id) {
            return result("completed", value = CATALOG_NOT_ACTIVE)
        }
        if (!AppEdition.current.dynamicCatalog) {
            // Metadata remains pinned. The ordinary installer force-recompiles selected sources
            // and retains the confirmed artifact if acquisition/compilation/publication fails.
            val installed = application.catalogMaintenance.refresh()
            return if (installed) result("completed", value = CATALOG_UNCHANGED)
                else result("rejected", CATALOG_INSTALL_FAILED)
        }
        val owner = catalogSyncOwner ?: return result("rejected", CATALOG_UNAVAILABLE)
        val refresh = owner.refresh()
        if (refresh.status in setOf(CatalogRefreshStatus.UPDATED, CatalogRefreshStatus.UNCHANGED)) {
            scope.launch {
                try { application.catalogMaintenance.topUp(scheduleRepair = false) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Speculative warming cannot change the accepted refresh result. */ }
            }
        }
        return catalogRefreshResult(refresh)
    }

    private fun catalogRefreshResult(refresh: com.lavasec.app.data.CatalogRefreshResult): String =
        when (refresh.status) {
            CatalogRefreshStatus.UPDATED -> result("completed", value = CATALOG_UPDATED)
            CatalogRefreshStatus.UNCHANGED -> result("completed", value = CATALOG_UNCHANGED)
            CatalogRefreshStatus.SUPERSEDED -> result("completed", value = CATALOG_SUPERSEDED)
            CatalogRefreshStatus.OFFLINE -> result("rejected", CATALOG_OFFLINE)
            CatalogRefreshStatus.INSTALL_FAILED -> refresh.sourceFailure?.let {
                customBlocklistSourceFailureWireResult(it.sourceName, it.failure, it.sourceId)
            } ?: result("rejected", CATALOG_INSTALL_FAILED)
            CatalogRefreshStatus.PERSIST_FAILED -> result("rejected", CATALOG_PERSIST_FAILED)
            CatalogRefreshStatus.KEPT_INSTALLED -> result("rejected", catalogRejectionReason(refresh.rejection))
            CatalogRefreshStatus.BUNDLED_FALLBACK -> result("rejected", catalogRejectionReason(refresh.rejection))
        }

    private fun catalogRejectionReason(rejection: CatalogCandidateRejection?): String =
        when (rejection) {
            is CatalogCandidateRejection.StaleRollback -> CATALOG_STALE
            is CatalogCandidateRejection.Invalid -> CATALOG_INVALID
            null -> CATALOG_UNAVAILABLE
        }

    /**
     * Canonical `filter.save`: an untouched creation saves its initial template and an unchanged
     * existing edit closes trivially; every CHANGED draft — added/removed blocklists, blocked
     * domains, allowed exceptions, and edits of active, inactive or newly created filters — shares
     * the one visible Review boundary before any durable mutation. The owner revalidates the exact
     * reviewed draft/baseline, active identity, credential generation and token at `filter.apply`,
     * so a changed draft, a switched active filter, a rotated credential or a stale token requires
     * a fresh review.
     */
    private suspend fun executeFilterSave(
        command: ProductFilterCommand.Save,
        before: MainProductUiState,
        generation: Long,
        present: NativeSecurityPresenter,
    ): String {
        val detail = before.filterDetail
        if (detail == null || detail.filterId != command.id ||
            (before.filterLibrary?.filter(command.id) == null && before.newFilter?.id != command.id)
        ) {
            return result("rejected", "unknown-filter")
        }
        if (product.finishUnchangedFilterEdit(command.id, generation)) {
            return result("completed", value = SAVE_SAVED)
        }
        val attempt = product.requestFilterReviewAttempt(command.id, generation, present)
        if (attempt !is com.lavasec.app.product.FilterLibraryOwner.ReviewAttempt.Captured) {
            val upgradeReason = nativeFilterSaveUpgradeReason(
                attempt, application.plusBackend.value?.entitlement?.state?.value?.localFeaturesAvailable == true,
            )
            return if (upgradeReason != null) result("completed", value = JSONObject().put("upgradeReason", upgradeReason))
            else result("rejected", "review-refused")
        }
        // A brand-new filter whose draft still equals its creation template is the one creation
        // that needs no review: it saves directly, exactly as the untouched-edit path does. Any
        // rule/source/domain change on a new filter falls through to Review below.
        if (untouchedNewFilterTemplate(command.id, before)) {
            if (product.commitFilterReview(command.id, generation, present = present)) {
                return result("completed", value = SAVE_SAVED)
            }
            return product.consumeFilterSourceFailure()?.let { customSourceFailureResult(it) }
                ?: result("rejected", "save-refused")
        }
        return result("completed", value = SAVE_REVIEW)
    }

    /** An unedited creation may apply directly only from Save's actual native review witness. */
    private fun untouchedNewFilterTemplate(filterId: String, before: MainProductUiState): Boolean {
        val generation = before.graphGeneration ?: return false
        return product.reviewedFilterIsUntouchedCreation(filterId, generation)
    }

    private fun reviewKey(generation: Long, filterId: String): String = "$generation:$filterId"

    private fun protectionResult(outcome: ProtectionCommandResult): String = when (outcome) {
        is ProtectionCommandResult.Completed -> result("completed")
        ProtectionCommandResult.Cancelled -> result("cancelled", "authentication")
        ProtectionCommandResult.Rejected -> result("rejected", "stale-action")
    }

    private fun settingsIntent(route: SettingsRoute): Intent = when (route) {
        SettingsRoute.VpnSettings -> Intent(Settings.ACTION_VPN_SETTINGS)
        SettingsRoute.NetworkSettings -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
        SettingsRoute.NotificationSettings -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }

    /** Stable wire codes for a custom-blocklist editor refusal; JS maps them to localized copy. */
    private fun customBlocklistRejectionReason(reason: CustomBlocklistRejection): String = when (reason) {
        CustomBlocklistRejection.INVALID_URL -> "custom-blocklist-invalid-url"
        CustomBlocklistRejection.UNSUPPORTED_SCHEME -> "custom-blocklist-unsupported-scheme"
        CustomBlocklistRejection.MISSING_HOST -> "custom-blocklist-missing-host"
        CustomBlocklistRejection.PRIVATE_NETWORK_HOST -> "custom-blocklist-private-network"
        CustomBlocklistRejection.CREDENTIALS_NOT_ALLOWED -> "custom-blocklist-credentials"
        CustomBlocklistRejection.REQUIRES_PLUS -> "custom-blocklist-requires-plus"
        CustomBlocklistRejection.DUPLICATE_URL -> "custom-blocklist-duplicate-url"
        CustomBlocklistRejection.DUPLICATE_NAME -> "custom-blocklist-duplicate-name"
        CustomBlocklistRejection.LIMIT_REACHED -> "custom-blocklist-limit"
    }

    /**
     * A custom blocklist fetch failure as a stable per-class wire code plus the user's own name
     * for the list. The URL is never included; JS maps the code to localized copy.
     */
    private fun customSourceFailureResult(failure: FilterSourceFailureInfo): String =
        customBlocklistSourceFailureWireResult(failure.sourceName, failure.failure, failure.sourceId)

    private fun result(status: String, reason: String? = null, value: Any? = null): String {
        val json = JSONObject()
            .put("schema", 1).put("status", status).put("reason", reason ?: JSONObject.NULL)
        // Canonical commands with a payload (filter.save "saved"/"review", filter.review token,
        // a soft domain rejection) carry it here; every other command omits the field.
        if (value != null) json.put("value", value)
        return json.toString()
    }

    /**
     * Runs one authentication request for a command. A user cancellation — the passcode dialog OR a
     * dismissed biometric prompt — invokes [markCancelled] so the caller returns the `cancelled`
     * result its UI suppresses, instead of an ordinary `unauthorized` failure.
     */
    private suspend fun authorizeCommand(
        generation: Long,
        surface: ProtectedSurface?,
        present: NativeSecurityPresenter,
        markCancelled: () -> Unit,
        fresh: Boolean = false,
    ): Boolean = when (product.securityAuthorizeResult(generation, surface, fresh, present)) {
        SecurityAuthorization.AUTHORIZED -> true
        SecurityAuthorization.CANCELLED -> {
            markCancelled()
            false
        }
        SecurityAuthorization.DENIED -> false
    }

    /**
     * A canonical soft rejection: the command succeeded as a wire request, but the owner refused
     * with user-facing copy the shared screen presents as an alert (`{rejection:{title,message}}`).
     */
    private fun softRejection(title: String, message: String, limitReached: Boolean = false): JSONObject =
        JSONObject().put(
            "rejection",
            JSONObject().put("title", title).put("message", message).put("limitReached", limitReached),
        )

    companion object {
        const val SNAPSHOT_EVENT = "lavaAppSnapshot"

        /** Clipboard label for the bare `LF1-` setup code copied by `share.copy`. */
        const val SHARE_CLIP_LABEL = "Lava filter setup code"

        /** Clipboard label for the domain copied by `domains.copy`. */
        const val DOMAIN_CLIP_LABEL = "Domain"

        /**
         * The first-party configuration presentation is unavailable this launch (no document
         * picker, or no registry). Distinct from a user cancellation so the surface can explain it.
         */
        const val REASON_VPN_EDITOR_UNAVAILABLE = "vpn-configuration-editor-unavailable"
        const val REASON_VPN_IMPORT_CLEANUP_FAILED = "vpn-import-cleanup-failed"

        /** A staged configuration text was not a valid chained upstream. */
        const val REASON_VPN_CONFIGURATION_INVALID = "vpn-configuration-invalid"

        /** The chained-preference write or the owner delegation failed. */
        const val REASON_VPN_TOGGLE_FAILED = "vpn-toggle-failed"

        /** Canonical `filter.save` result: the draft was applied durably. */
        const val SAVE_SAVED = "saved"

        /** Canonical `filter.save` result: the draft must be reviewed before it is applied. */
        const val SAVE_REVIEW = "review"

        /**
         * Stable `filter.refresh` result values. The installed/effective catalog is install-time
         * state, not a user-visible success: only `updated`/`unchanged`/`superseded`/`not-active`
         * complete, and every failure is a typed rejection.
         */
        const val CATALOG_UPDATED = "updated"
        const val CATALOG_UNCHANGED = "unchanged"
        const val CATALOG_SUPERSEDED = "superseded"
        const val CATALOG_NOT_ACTIVE = "not-active"
        const val CATALOG_OFFLINE = "catalog-offline"
        const val CATALOG_INVALID = "catalog-invalid"
        const val CATALOG_STALE = "catalog-stale"
        const val CATALOG_UNAVAILABLE = "catalog-unavailable"
        const val CATALOG_PERSIST_FAILED = "catalog-persist-failed"
        const val CATALOG_INSTALL_FAILED = "catalog-install-failed"

        /**
         * The honest empty catalog a failed asset load degrades to, so `catalog.query` returns an
         * empty (never fabricated) picker instead of an unsupported rejection.
         */
        private val EMPTY_BLOCKLIST_CATALOG = BundledBlocklistCatalog(
            schemaVersion = 1,
            categories = emptyList(),
            sources = emptyList(),
            revision = "",
            sha256 = "",
            assetText = "",
        )
    }
}

/** Canonical log kinds the Activity surface may clear under the Activity authorization. */
private val ACTIVITY_LOG_KINDS = setOf("Clear domain history", "Clear network activity")

/**
 * The canonical past-tense confirmations announced after each local-log clear. They match the iOS
 * `LocalLogClearTarget.clearedConfirmation` keys so the shared `actions.ts` announcement is stable.
 */
private val LOG_CLEAR_CONFIRMATIONS = mapOf(
    "Clear filtering counts" to "Filtering counts cleared.",
    "Clear domain history" to "Domain history cleared.",
    "Clear network activity" to "Network activity cleared.",
    "Clear Lava Guard progress" to "Lava Guard progress cleared.",
    "Clear all logs" to "All logs cleared.",
)

/**
 * The bridge-visible outcome of one native flow launch: the canonical wire status plus an optional
 * stable reason code. Kept separate from [LavaNativeFlowOutcome] so the JSON envelope is built once,
 * in the bridge, and this mapping stays a pure JVM-testable decision.
 */
internal data class NativeFlowBridgeResult(val status: String, val reason: String?)

/**
 * Maps a flow-host outcome to the bridge result. A completed/cancelled presentation is delivered
 * as-is; a refusal keeps its stable host reason; a rotated credential graph is a `stale-generation`
 * fence so the shared screen's stale copy applies rather than a fabricated cancellation.
 */
internal fun LavaNativeFlowOutcome.toBridgeResult(): NativeFlowBridgeResult = when (this) {
    is LavaNativeFlowOutcome.Completed -> NativeFlowBridgeResult("completed", null)
    is LavaNativeFlowOutcome.Cancelled -> NativeFlowBridgeResult("cancelled", null)
    is LavaNativeFlowOutcome.Refused -> NativeFlowBridgeResult("rejected", reason.wireName)
    is LavaNativeFlowOutcome.Stale -> NativeFlowBridgeResult("rejected", "stale-generation")
}

/**
 * Presents one canonical native flow through the single [LavaNativeFlowHost]. A `null` host (the
 * production host could not be built for the foreground activity) is a typed `host-unavailable`
 * refusal — never a fabricated completion and never a silent no-op.
 */
internal suspend fun presentNativeFlow(
    host: LavaNativeFlowHost?,
    request: LavaNativeFlowRequest,
): NativeFlowBridgeResult =
    if (host == null) {
        NativeFlowBridgeResult("rejected", LavaNativeFlowReason.HOST_UNAVAILABLE.wireName)
    } else {
        host.launchRoute(request).toBridgeResult()
    }

/** Explicit Chinese script takes precedence over region; retain the native Portuguese wire shape. */
internal fun nativePresentationLocale(locale: Locale): String = when (locale.language) {
    "zh" -> when (locale.script) {
        "Hans" -> "zh-Hans"
        "Hant" -> "zh-Hant"
        else -> if (locale.country in setOf("TW", "HK", "MO")) "zh-Hant" else "zh-Hans"
    }
    "pt" -> if (locale.country == "BR") "pt-BR" else "pt"
    else -> locale.language
}

/**
 * Decodes the committed-rotation generation a `vpn.begin` fences against.
 *
 * The canonical `vpn` generation is the empty string while no rotation is committed (iOS
 * `status.storedConfigurationGeneration.map(String.init) ?? ""`), so an unconfigured device
 * legitimately echoes `""`. That is the not-configured sentinel [VpnFirstConfigurationCoordinator]
 * imports over; a committed rotation is always generation > 0 (the repository requires it), so
 * the sentinel cannot collide. A non-empty value that is not a number is malformed and returns
 * `null` (the caller answers `invalid-argument`).
 */
internal fun vpnBeginExpectedGeneration(wire: String): Long? =
    if (wire.isEmpty()) 0L else wire.toLongOrNull()

/** Invalidates on every durable or installed-runtime event, including an equal live witness. */
internal fun vpnFamilyUpdates(
    stored: Flow<StoredChainedUpstreamConfiguration?>,
    enabled: Flow<Boolean>,
    dnsFallbackEnabled: Flow<Boolean>,
    activity: Flow<ChainedActivitySignal>,
    state: Flow<ProtectionState>,
    entitlement: Flow<*> = flowOf(Unit),
): Flow<VpnRuntimeRotationWitness> = combine(
    combine(stored, enabled, dnsFallbackEnabled, activity, state) { _, _, _, liveActivity, liveState ->
        VpnRuntimeRotationWitness(liveActivity, liveState)
    },
    entitlement,
) { witness, _ -> witness }


/** Cached non-secret metadata can be read only by its exact live credential composition. */
internal data class NativeVpnFamilyProjection<C : Any, V : Any>(val composition: C, val value: V) {
    fun valueFor(current: C?): V? = if (current === composition) value else null
}

/**
 * Production IO projection pipeline. Cancellation and final current-owner admission both fence
 * a suspended metadata read; equal runtime witnesses still invalidate on source changes.
 */
internal suspend fun <C : Any, R, V : Any> observeNativeVpnFamily(
    compositions: Flow<C?>,
    currentComposition: () -> C?,
    updates: (C) -> Flow<R>,
    read: suspend (C, R) -> V,
    publish: (NativeVpnFamilyProjection<C, V>?) -> Unit,
) {
    compositions.collectLatest { composition ->
        if (composition == null) {
            if (currentComposition() == null) publish(null)
        } else {
            updates(composition).collectLatest { runtime ->
                val value = read(composition, runtime)
                currentCoroutineContext().ensureActive()
                if (currentComposition() === composition) publish(NativeVpnFamilyProjection(composition, value))
            }
        }
    }
}

/** Paint failure is separate from the truthful native operation result. */
internal enum class NativeCommandProjectionFailure(val wireName: String) {
    UNAVAILABLE("unavailable"), STALE_HOST("stale-host"),
}

/** No projection is rebuilt between observer delivery and the corresponding command reply. */
internal fun nativeCommandPublication(response: String, projection: String?,
    failure: NativeCommandProjectionFailure = NativeCommandProjectionFailure.UNAVAILABLE): String =
    JSONObject(response).apply {
        if (projection != null) put("snapshot", JSONObject(projection))
        else put("projectionFailure", failure.wireName)
    }.toString()

/** Normal layout custody must carry its original native authentication turn, including all-off. */
internal fun nativePresentationRevisionMatches(wire: Any?, current: Long?): Boolean =
    current == null || wire is String && wire == current.toString()
