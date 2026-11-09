package com.lavasec.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.lavasec.app.designsystem.LavaTheme
import com.lavasec.app.data.CatalogLegalNotices
import com.lavasec.app.foundation.security.ProtectedSurface
import com.lavasec.app.platform.SettingsRoute
import com.lavasec.app.presentation.ProductDeepLink
import com.lavasec.app.presentation.ProductDestination
import com.lavasec.app.presentation.ProductNavigation
import com.lavasec.app.presentation.ProductShell
import com.lavasec.app.presentation.ProductUnavailableScreen
import com.lavasec.app.presentation.activity.ActivityQueryCallbacks
import com.lavasec.app.presentation.activity.DomainNoticeCallbacks
import com.lavasec.app.presentation.activity.DomainReviewCallbacks
import com.lavasec.app.presentation.activity.DomainRowActionCallbacks
import com.lavasec.app.presentation.filters.FilterLibraryActions
import com.lavasec.app.presentation.filters.FilterOverviewNavigation
import com.lavasec.app.presentation.filters.FilterPreparationUiState
import com.lavasec.app.presentation.onboarding.OnboardingCallbacks
import com.lavasec.app.presentation.onboarding.OnboardingScreen
import com.lavasec.app.presentation.onboarding.OnboardingUiState
import com.lavasec.app.presentation.settings.BugReportCallbacks
import com.lavasec.app.presentation.settings.CustomizationCallbacks
import com.lavasec.app.presentation.settings.CustomizationUiState
import com.lavasec.app.presentation.settings.NerdStatsUiState
import com.lavasec.app.presentation.settings.PrivacyDataCallbacks
import com.lavasec.app.presentation.settings.PrivacyDataUiState
import com.lavasec.app.presentation.settings.ResolverPlanSwitchUiState
import com.lavasec.app.presentation.settings.SecurityEntryAccess
import com.lavasec.app.presentation.settings.SecurityEntryGate
import com.lavasec.app.presentation.settings.SecurityEntryIdentity
import com.lavasec.app.presentation.settings.SecurityCallbacks
import com.lavasec.app.presentation.settings.SecurityFailureUi
import com.lavasec.app.presentation.settings.SecurityUiState
import com.lavasec.app.presentation.settings.SettingsResolverUiState
import com.lavasec.app.presentation.settings.protects
import com.lavasec.app.security.presentPasscodeAuthentication
import com.lavasec.app.security.SecurityAvailability
import com.lavasec.app.security.SecurityFailure
import com.lavasec.app.security.SecurityOwnerState
import com.lavasec.app.security.SecurityPrivacyPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val activityRecreationMarker: ActivityRecreationMarker by viewModels()
    private val productViewModel: MainProductViewModel
        get() = (application as LavasecApplication).foregroundProduct
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val navigationRequest: MutableState<NavigationRequest> = mutableStateOf(
        NavigationRequest(
            navigation = ProductNavigation.guard,
            sequence = 0L,
        ),
    )
    private var externalEntryHandedOff = false
    private val versionName = BuildConfig.VERSION_NAME
    // Kept for legacy display metadata; access uses navigation-turn grants, never this clock.
    private val securityNowMillis = mutableStateOf(System.currentTimeMillis())
    private val securityRevisionFloor = mutableStateOf(0L)

    private val vpnConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val pending = productViewModel.state.value.onboarding?.pendingVpnConsent
            ?: return@registerForActivityResult
        activityScope.launch {
            productViewModel.completeVpnConsent(
                id = pending.id,
                granted = result.resultCode == Activity.RESULT_OK,
            )
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        activityScope.launch {
            productViewModel.completeNotificationPermission(granted)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isConfigurationRecreation = activityRecreationMarker.consumeConfigurationChange()
        if (savedInstanceState == null) {
            val nativeRoute = intent?.getStringExtra(NATIVE_FLOW_ROUTE)
                ?.let(com.lavasec.app.reactnative.NativeProductRoutes::navigation)
            if (nativeRoute != null) {
                navigate(nativeRoute)
                if (!externalEntryHandedOff) stageNavigation(nativeRoute)
            } else {
                deliverNavigation(intent)
            }
        } else {
            navigationRequest.value = navigationRequest.value.copy(
                navigation = ProductNavigation.restore(
                    savedInstanceState.getStringArrayList(SAVED_NAVIGATION_PATH).orEmpty(),
                ),
            )
            if (com.lavasec.app.edition.EditionNativeNavigation.requiresReactSettings(navigationRequest.value.navigation)) {
                handoffToReact(ProductNavigation.settings)
                return
            }
            val isHistoryRelaunch =
                (intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
            if (!isConfigurationRecreation && !isHistoryRelaunch) {
                if (!deliverNavigation(intent)) {
                    stageNavigation(navigationRequest.value.navigation)
                }
            } else {
                stageNavigation(navigationRequest.value.navigation)
            }
        }
        if (externalEntryHandedOff) return
        enableEdgeToEdge()
        setContent {
            val request = navigationRequest.value
            val product by productViewModel.state.collectAsState()
            val scope = rememberCoroutineScope()
            val released = product.onboarding?.releasedNavigation
            LaunchedEffect(released?.id) {
                if (released != null) {
                    navigate(released.navigation)
                    productViewModel.consumeReleasedNavigation(released.id)
                }
            }
            val lifecycleStarted = remember { mutableStateOf(Lifecycle.State.INITIALIZED) }
            val foregroundEpoch = remember { mutableStateOf(0L) }
            val nativeSecurityResumed = remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
            DisposableEffect(lifecycle) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_START) {
                        lifecycleStarted.value = Lifecycle.State.STARTED
                        // Returning to the foreground refreshes the unlock
                        // clock and the real notification-channel status (the
                        // user may have flipped them in system settings).
                        securityNowMillis.value = System.currentTimeMillis()
                        activityScope.launch {
                            productViewModel.refreshCustomizationNotifications()
                        }
                    } else if (event == Lifecycle.Event.ON_RESUME) {
                        nativeSecurityResumed.value = true
                        enterNativeSecurityForeground()
                    } else if (event == Lifecycle.Event.ON_PAUSE) {
                        nativeSecurityResumed.value = false
                    } else if (event == Lifecycle.Event.ON_STOP) {
                        foregroundEpoch.value++
                        productViewModel.state.value.graphGeneration?.let { generation ->
                            productViewModel.securitySuspendForeground(this@MainActivity, generation, null)
                            securityRevisionFloor.value = productViewModel.securityStateForSnapshot(generation)
                                ?.authenticationRevision ?: securityRevisionFloor.value
                        }
                        lifecycleStarted.value = Lifecycle.State.CREATED
                        securityNowMillis.value = System.currentTimeMillis()
                    }
                }
                lifecycle.addObserver(observer)
                lifecycleStarted.value = lifecycle.currentState
                onDispose { lifecycle.removeObserver(observer) }
            }
            // A native-only flow can arrive before the graph opens. Its protected body stays
            // unprepared until the exact resumed Activity owns that generation.
            LaunchedEffect(product.graphGeneration, nativeSecurityResumed.value) {
                enterNativeSecurityForeground()
            }
            LaunchedEffect(
                request.navigation,
                product.diagnostics != null,
                lifecycleStarted.value,
            ) {
                while (lifecycleStarted.value == Lifecycle.State.STARTED &&
                    request.navigation.path.lastOrNull() ==
                    com.lavasec.app.presentation.ProductDestination.NerdStats &&
                    product.diagnostics != null
                ) {
                    withContext(Dispatchers.IO) {
                        productViewModel.refreshDiagnostics()
                    }
                    delay(5_000L)
                }
            }
            LavaTheme(
                // The persisted customization preference drives the theme's
                // reduced-motion override; null means "follow the Android
                // system setting" exactly like the rest of the app.
                reducedMotion = product.customization?.reduceMotion,
            ) {
                Column(Modifier.fillMaxSize()) {
                    if (intent?.hasExtra(NATIVE_FLOW_ROUTE) == true) {
                        Box(Modifier.statusBarsPadding()) {
                            TextButton(onClick = {
                                setResult(Activity.RESULT_OK)
                                finish()
                            }) { Text(stringResource(com.lavasec.app.presentation.R.string.bug_report_done)) }
                        }
                    }
                    Box(Modifier.weight(1f)) {
                val onboarding = product.onboarding
                LaunchedEffect(onboarding?.completed) {
                    if (intent?.getStringExtra(NATIVE_FLOW_ROUTE) == "onboarding" &&
                        onboarding?.completed == true
                    ) {
                        setResult(Activity.RESULT_OK)
                        finish()
                    }
                }
                when {
                    onboarding != null && !onboarding.completed -> OnboardingScreen(
                        state = OnboardingUiState(
                            onboarding = checkNotNull(onboarding.onboarding),
                            vpnConsentPending = onboarding.pendingVpnConsent != null,
                            vpnConsentDenied = onboarding.vpnConsentDenied,
                            notificationPermissionGranted =
                                onboarding.notificationPermissionGranted,
                            commitInFlight = onboarding.commitInFlight,
                            reconstructionRequired = onboarding.reconstructionRequired,
                            reconstructionInFlight = product.onboardingReconstructionInFlight,
                            error = onboarding.error,
                        ),
                        callbacks = OnboardingCallbacks(
                            onForward = {
                                scope.launch {
                                    productViewModel.forwardOnboarding()?.let { pending ->
                                        vpnConsent.launch(pending.request)
                                    }
                                }
                            },
                            onBack = { scope.launch { productViewModel.backOnboarding() } },
                            onRetry = product.graphGeneration?.let { expectedGeneration ->
                                { productViewModel.retryOnboarding(expectedGeneration); Unit }
                            },
                            onNotificationPermissionRequest = {
                                requestNotificationPermission()
                            },
                            onOpenNotificationSettings = {
                                openSettings(SettingsRoute.NotificationSettings)
                            },
                            onProtectionLevelSelected = { level ->
                                scope.launch { productViewModel.selectProtectionLevel(level) }
                            },
                            onResolverSelected = { plan ->
                                scope.launch { productViewModel.selectResolver(plan) }
                            },
                            onResolverRouteSelected = { device, encrypted ->
                                product.graphGeneration?.let { generation ->
                                    scope.launch { productViewModel.selectOnboardingResolverRoute(device, encrypted, generation) }
                                }
                            },
                        ),
                    )

                    product.productAvailable -> {
                        val protection = checkNotNull(product.protection)
                        val activityCallbacks = remember(scope) {
                            ActivityQueryCallbacks(
                                onRefreshSummary = {
                                    scope.launch { productViewModel.refreshActivity() }
                                },
                                onDateRangeSelected = { start, end ->
                                    scope.launch {
                                        productViewModel.selectActivityDateRange(start, end)
                                    }
                                },
                                onTopDomainDecisionSelected = { decision ->
                                    scope.launch {
                                        productViewModel.selectTopDomainDecision(decision)
                                    }
                                },
                                onTopDomainSearchChanged = { search ->
                                    scope.launch { productViewModel.setTopDomainSearch(search) }
                                },
                                onRefreshHistory = {
                                    scope.launch { productViewModel.refreshHistory() }
                                },
                                onHistoryDecisionSelected = { decision ->
                                    scope.launch {
                                        productViewModel.selectHistoryDecision(decision)
                                    }
                                },
                                onHistorySearchChanged = { search ->
                                    scope.launch { productViewModel.setHistorySearch(search) }
                                },
                                onLoadMoreHistory = {
                                    scope.launch { productViewModel.loadMoreHistory() }
                                },
                                onRefreshNetworkActivity = {
                                    scope.launch { productViewModel.refreshNetworkActivity() }
                                },
                                onLoadMoreNetworkActivity = {
                                    scope.launch { productViewModel.loadMoreNetworkActivity() }
                                },
                            )
                        }
                        val rowCallbacks = remember(scope, product.graphGeneration) {
                            DomainRowActionCallbacks { domain, action ->
                                scope.launch {
                                    val generation = product.graphGeneration ?: return@launch
                                    productViewModel.requestDomainAction(domain, action, generation) { _, verify, cancel ->
                                        this@MainActivity.presentPasscodeAuthentication(verify, cancel)
                                    }
                                }
                            }
                        }
                        val reviewCallbacks = remember(scope, product.graphGeneration) {
                            DomainReviewCallbacks(
                                onReviewVisible = { id ->
                                    scope.launch {
                                        product.graphGeneration?.let { productViewModel.markDomainReviewVisible(id, it) }
                                    }
                                },
                                onCancelBeforeVisible = { id ->
                                    scope.launch {
                                        product.graphGeneration?.let { productViewModel.cancelDomainReviewBeforeVisible(id, it) }
                                    }
                                },
                                onConfirm = { id ->
                                    scope.launch {
                                        val generation = product.graphGeneration ?: return@launch
                                        productViewModel.confirmDomainAction(id, generation) { _, verify, cancel ->
                                            this@MainActivity.presentPasscodeAuthentication(verify, cancel)
                                        }
                                    }
                                },
                                onCancel = { id ->
                                    scope.launch { product.graphGeneration?.let { productViewModel.cancelDomainAction(id, it) } }
                                },
                            )
                        }
                        val noticeCallbacks = remember(scope, product.graphGeneration) {
                            DomainNoticeCallbacks { id ->
                                scope.launch { product.graphGeneration?.let { productViewModel.dismissDomainNotice(id, it) } }
                            }
                        }
                        val privacy = checkNotNull(product.privacy)
                        val diagnostics = checkNotNull(product.diagnostics)
                        val privacyDataState = PrivacyDataUiState(
                            logging = privacy.logging,
                            inFlight = privacy.inFlight || diagnostics.inFlight,
                            exportReady = privacy.exportHandle != null,
                            error = privacy.error ?: diagnostics.error,
                        )
                        val settingsResolverState = SettingsResolverUiState(
                            plan = product.resolverPlan,
                            selection = product.resolverSelection,
                            health = product.resolverHealth,
                            inFlight = product.resolverPlanSwitch is
                                ResolverPlanSwitchUiState.Applying,
                            error = null,
                        )
                        val customizationState = product.customization?.let { customization ->
                            CustomizationUiState(
                                reduceMotion = customization.reduceMotion,
                                haptics = customization.haptics,
                                notificationsAllowed = customization.notificationsAllowed,
                                notificationsBlocked = customization.notificationsBlocked,
                                inFlight = customization.inFlight,
                                error = customization.error,
                            )
                        }
                        val securityAuthority = product.graphGeneration?.takeIf { generation ->
                            productViewModel.securityOwnsForeground(this@MainActivity, generation)
                        }?.let(productViewModel::securityStateForSnapshot)
                        val securityState = securityAuthority?.toUi(securityRevisionFloor.value)
                        val privacyDataCallbacks = remember(scope) {
                            PrivacyDataCallbacks(
                                onLoggingChange = { logging ->
                                    scope.launch { productViewModel.setLogging(logging) }
                                },
                                onClearLocalActivity = {
                                    scope.launch { productViewModel.clearLocalActivity() }
                                },
                                onExportLocalActivity = {
                                    scope.launch { productViewModel.exportLocalActivity() }
                                },
                                onShareLocalExport = {
                                    productViewModel.createExportShareIntent()?.let { share ->
                                        startActivity(
                                            Intent.createChooser(
                                                share,
                                                getString(R.string.share_local_activity),
                                            ),
                                        )
                                    }
                                },
                                onClearDiagnostics = {
                                    scope.launch { productViewModel.clearDiagnostics() }
                                },
                            )
                        }
                        val customizationCallbacks = remember(scope) {
                            CustomizationCallbacks(
                                onReduceMotionChanged = { enabled ->
                                    scope.launch {
                                        productViewModel.setCustomizationReduceMotion(enabled)
                                    }
                                },
                                onHapticsChanged = { enabled ->
                                    scope.launch { productViewModel.setCustomizationHaptics(enabled) }
                                },
                                onOpenNotificationSettings = {
                                    openSettings(SettingsRoute.NotificationSettings)
                                },
                            )
                        }
                        val securityCallbacks = remember(scope, request, product.graphGeneration, foregroundEpoch.value, securityAuthority?.authenticationRevision) {
                            val unlockRevision = securityAuthority?.authenticationRevision
                            val recoveryReturnsToCaller = intent?.getStringExtra(NATIVE_FLOW_ROUTE) == "security" &&
                                request.navigation.path.lastOrNull() == ProductDestination.Security
                            val resetRecovery = securityRecoveryResetAction(
                                expectedGeneration = product.graphGeneration,
                                expectedForegroundEpoch = foregroundEpoch.value,
                                currentForegroundEpoch = { foregroundEpoch.value },
                                currentGeneration = { productViewModel.state.value.graphGeneration },
                                readSecurity = productViewModel::securityStateForSnapshot,
                                reset = productViewModel::securityResetAfterRecoveryFailure,
                            )
                            SecurityCallbacks(
                                onSetPasscode = { code ->
                                    scope.launch { productViewModel.securitySetPasscode(code) }
                                },
                                onChangePasscode = { current, new ->
                                    scope.launch {
                                        productViewModel.securityChangePasscode(current, new)
                                    }
                                },
                                onRemovePasscode = { code ->
                                    scope.launch { productViewModel.securityRemovePasscode(code) }
                                },
                                onUnlock = { code ->
                                    // The whole-app lock blocks every destination first; a
                                    // destination surface is granted only after it clears.
                                    val authority = securityAuthority
                                    val appUnlockRequired = authority != null &&
                                        ProtectedSurface.APP_UNLOCK in authority.protectedSurfaces &&
                                        ProtectedSurface.APP_UNLOCK !in authority.authenticatedSurfaces
                                    val surface = if (appUnlockRequired) {
                                        ProtectedSurface.APP_UNLOCK
                                    } else {
                                        ProtectedSurface.entries.firstOrNull { candidate ->
                                            request.navigation.path.any { candidate.protects(it) }
                                        }
                                    }
                                    if (unlockRevision != null) scope.launch {
                                        productViewModel.securityUnlock(code, surface, unlockRevision)
                                    }
                                },
                                onLockTimeoutSelected = { option ->
                                    scope.launch {
                                        productViewModel.securitySelectLockTimeout(option)
                                    }
                                },
                                onProtectedSurfaceChanged = { surface, enabled ->
                                    scope.launch {
                                        productViewModel.securitySetProtectedSurface(
                                            surface,
                                            enabled,
                                            expectedRevision = unlockRevision ?: return@launch,
                                        ) { _, verify, cancel ->
                                            this@MainActivity.presentPasscodeAuthentication(verify, cancel)
                                        }
                                    }
                                },
                                onResetAfterRecoveryFailure = {
                                    scope.launch {
                                        if (recoveryReturnsToCaller) resetRecovery()
                                        else productViewModel.securityResetAfterRecoveryFailure()
                                    }
                                },
                                recoveryReturnsToCaller = recoveryReturnsToCaller,
                            )
                        }
                        val bugReportCallbacks = remember(scope) {
                            BugReportCallbacks(
                                onTopicSelected = { topic ->
                                    productViewModel.selectBugReportTopic(topic)
                                },
                                onDescriptionChanged = { description ->
                                    productViewModel.setBugReportDescription(description)
                                },
                                onDiagnosticsConsentChanged = { enabled ->
                                    productViewModel.setBugReportDiagnosticsConsent(enabled)
                                },
                                onAffectedSiteChanged = productViewModel::setBugReportAffectedSite,
                                onContactEmailChanged = productViewModel::setBugReportContactEmail,
                                onConsentGrantedChanged = { granted ->
                                    productViewModel.setBugReportConsentGranted(granted)
                                },
                                onAdvance = { productViewModel.advanceBugReportStep() },
                                onBack = { productViewModel.backBugReportStep() },
                                onSubmit = {
                                    scope.launch {
                                        productViewModel.submitBugReport()
                                    }
                                },
                                onDiscard = { productViewModel.discardBugReport() },
                                onDone = {
                                    productViewModel.discardBugReport()
                                    navigate(ProductNavigation.settings)
                                },
                            )
                        }
                        val legalNoticeSections = remember {
                            CatalogLegalNotices(
                                assets = assets,
                                stringResource = { id -> getString(id) },
                            ).buildSections()
                        }
                        val nerdStatsState = remember(product.diagnostics?.record) {
                            NerdStatsUiState.fromSnapshot(
                                snapshot = product.diagnostics?.record?.snapshot,
                                versionName = versionName,
                                buildNumber = BuildConfig.VERSION_CODE.toString(),
                                androidPlatformLabel = getString(
                                    com.lavasec.app.presentation.R.string
                                        .nerd_stats_android_platform,
                                    Build.VERSION.RELEASE,
                                ),
                            )
                        }
                        val sampledTimeLabel = remember {
                            { millis: Long ->
                                java.text.DateFormat.getDateTimeInstance(
                                    java.text.DateFormat.MEDIUM,
                                    java.text.DateFormat.SHORT,
                                ).format(java.util.Date(millis))
                            }
                        }
                        // The background/app-switcher privacy mask: while app
                        // unlock is on, FLAG_SECURE hides this activity's
                        // content from screenshots and the app switcher. The
                        // flag follows the real security owner state through the
                        // SAME SecurityPrivacyPolicy as the native cover — a
                        // confirmed all-off policy clears it, never an
                        // unconditional always-on default (D4).
                        val securityOwnerState = securityAuthority
                        val backgroundCoverRequired = SecurityPrivacyPolicy.requiresBackgroundCover(securityOwnerState)
                        SideEffect {
                            if (backgroundCoverRequired) {
                                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                            } else {
                                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                            }
                        }
                        val filterLibraryActions = remember(scope, product.graphGeneration) {
                            FilterLibraryActions(
                                onApplyFilterRequest = { filterId ->
                                    scope.launch {
                                        productViewModel.requestFilterProfileApply(filterId, product.graphGeneration) { _, verify, cancel ->
                                            this@MainActivity.presentPasscodeAuthentication(verify, cancel)
                                        }
                                    }
                                },
                                onOpenDetail = { filterId ->
                                    scope.launch { productViewModel.openFilterDetail(filterId) }
                                    navigate(openFilterDetailNavigation())
                                },
                                onOpenLibrary = {
                                    navigate(openFilterLibraryNavigation())
                                },
                                onBeginEdit = { filterId ->
                                    scope.launch {
                                        val generation = product.graphGeneration ?: return@launch
                                        productViewModel.beginFilterEdit(filterId, generation) { _, verify, cancel ->
                                            this@MainActivity.presentPasscodeAuthentication(verify, cancel)
                                        }
                                    }
                                },
                                onDiscardDraft = { filterId ->
                                    scope.launch { productViewModel.discardFilterDraft(filterId) }
                                },
                                onOpenReview = { filterId ->
                                    scope.launch {
                                        val generation = product.graphGeneration ?: return@launch
                                        if (productViewModel.requestFilterReview(filterId, generation) { _, verify, cancel ->
                                                this@MainActivity.presentPasscodeAuthentication(verify, cancel)
                                            }) navigate(openFilterReviewNavigation())
                                    }
                                },
                                onCommitReview = { filterId ->
                                    scope.launch {
                                        val generation = product.graphGeneration ?: return@launch
                                        productViewModel.commitFilterReview(filterId, generation,
                                            onAdmitted = { navigate(openFilterPreparationNavigation()) },
                                        ) { _, verify, cancel ->
                                            this@MainActivity.presentPasscodeAuthentication(verify, cancel)
                                        }
                                    }
                                },
                                onCancelReview = {
                                    navigate(openFilterDetailNavigation())
                                },
                                onAddBlockedDomain = { filterId, domain ->
                                    product.graphGeneration?.let { generation ->
                                        productViewModel.addBlockedDomainToDraft(filterId, domain, generation) { _, verify, cancel ->
                                            this@MainActivity.presentPasscodeAuthentication(verify, cancel)
                                        }
                                    }
                                },
                                onAddAllowedException = { filterId, domain ->
                                    product.graphGeneration?.let { generation ->
                                        productViewModel.addAllowedExceptionToDraft(filterId, domain, generation) { _, verify, cancel ->
                                            this@MainActivity.presentPasscodeAuthentication(verify, cancel)
                                        }
                                    }
                                },
                                onRemoveDomain = { filterId, domain, target ->
                                    scope.launch {
                                        val generation = product.graphGeneration ?: return@launch
                                        productViewModel.removeDomainFromDraft(filterId, domain, target, generation) { _, verify, cancel ->
                                            this@MainActivity.presentPasscodeAuthentication(verify, cancel)
                                        }
                                    }
                                },
                                onToggleSource = { filterId, sourceId, enabled ->
                                    product.graphGeneration?.let { generation ->
                                        productViewModel.toggleSourceInDraft(filterId, sourceId, enabled, generation) { _, verify, cancel ->
                                            this@MainActivity.presentPasscodeAuthentication(verify, cancel)
                                        }
                                    } ?: false
                                },
                                onCreateFilter = { name, duplicateFromId ->
                                    scope.launch {
                                        val generation = product.graphGeneration ?: return@launch
                                        productViewModel.createFilter(name, duplicateFromId, generation) { _, verify, cancel ->
                                            this@MainActivity.presentPasscodeAuthentication(verify, cancel)
                                        }
                                    }
                                },
                                onRenameFilter = { filterId, name ->
                                    scope.launch {
                                        val generation = product.graphGeneration ?: return@launch
                                        productViewModel.renameFilter(filterId, name, generation, present = { _, verify, cancel ->
                                            this@MainActivity.presentPasscodeAuthentication(verify, cancel)
                                        })
                                    }
                                },
                                onDeleteFilters = { filterIds ->
                                    scope.launch {
                                        val generation = product.graphGeneration ?: return@launch
                                        productViewModel.deleteFilters(filterIds, generation) { _, verify, cancel ->
                                            this@MainActivity.presentPasscodeAuthentication(verify, cancel)
                                        }
                                    }
                                },
                                onRestoreDefaults = {
                                    scope.launch {
                                        val generation = product.graphGeneration ?: return@launch
                                        productViewModel.restoreDefaultFilters(generation) { _, verify, cancel ->
                                            this@MainActivity.presentPasscodeAuthentication(verify, cancel)
                                        }
                                    }
                                },
                                onRetryPreparation = {
                                    scope.launch {
                                        val generation = product.graphGeneration ?: return@launch
                                        productViewModel.retryFilterPreparation(generation) { _, verify, cancel ->
                                            this@MainActivity.presentPasscodeAuthentication(verify, cancel)
                                        }
                                    }
                                },
                                onKeepCurrentAfterFailure = {
                                    // The draft stays in its per-filter slot; the editor detail
                                    // is the honest return point after a failed commit.
                                    navigate(openFilterDetailNavigation())
                                },
                                onConsumePreparationSuccess = {
                                    scope.launch {
                                        productViewModel.consumeFilterPreparationSuccess()
                                    }
                                },
                            )
                        }
                        val filterOverviewNavigation = remember {
                            FilterOverviewNavigation(
                                onOpenActiveDetail = {
                                    scope.launch {
                                        product.filterLibrary?.activeFilterId?.let { activeId ->
                                            productViewModel.openFilterDetail(activeId)
                                        }
                                    }
                                    navigate(openFilterDetailNavigation())
                                },
                                onOpenLibrary = {
                                    navigate(openFilterLibraryNavigation())
                                },
                            )
                        }
                        // A durable preparation Success settles the cover back to the Filters
                        // overview (the committed filter is in effect); the sticky Succeeded
                        // owner state is consumed so the next preparation starts fresh.
                        LaunchedEffect(product.filterPreparation) {
                            if (product.filterPreparation is FilterPreparationUiState.Success) {
                                navigate(
                                    ProductNavigation.restore(
                                        ProductNavigation.guard
                                            .open(ProductDestination.Filters)
                                            .save(),
                                    ),
                                )
                                productViewModel.consumeFilterPreparationSuccess()
                            }
                        }
                        val entryForegroundEpoch = foregroundEpoch.value
                        SecurityEntryGate(
                            identity = SecurityEntryIdentity(
                                request.navigation, request.sequence, product.graphGeneration,
                                entryForegroundEpoch,
                            ),
                            foreground = lifecycleStarted.value.isAtLeast(Lifecycle.State.STARTED),
                            access = when {
                                securityAuthority == null -> SecurityEntryAccess.Unknown
                                securityAuthority.inFlight &&
                                    securityAuthority.availability != SecurityAvailability.Available -> SecurityEntryAccess.Updating
                                securityAuthority.availability != SecurityAvailability.Available ||
                                    securityAuthority.recoveryRequired -> SecurityEntryAccess.Recovery
                                else -> SecurityEntryAccess.Readable
                            },
                            authorize = {
                                product.graphGeneration?.let { generation ->
                                    productViewModel.securityAuthorize(generation, surface = null) { _, verify, cancel ->
                                        this@MainActivity.presentPasscodeAuthentication(verify, cancel)
                                    }
                                } ?: false
                            },
                            onCancelled = {
                                if (navigationRequest.value == request &&
                                    foregroundEpoch.value == entryForegroundEpoch &&
                                    productViewModel.state.value.graphGeneration == product.graphGeneration &&
                                    lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                                ) {
                                    if (intent?.getStringExtra(NATIVE_FLOW_ROUTE) == "security") {
                                        setResult(Activity.RESULT_CANCELED)
                                        finish()
                                    } else navigate(ProductNavigation.settings)
                                }
                            },
                        ) {
                            ProductShell(
                                navigation = request.navigation,
                                guardHealth = protection.guard,
                                guardError = protection.error,
                                guardActionDisabled = when {
                                    protection.reconstructionRequired ->
                                        com.lavasec.app.presentation.GuardActionDisabledUi
                                            .RECONSTRUCTION_REQUIRED
                                    protection.commandInFlight ->
                                        com.lavasec.app.presentation.GuardActionDisabledUi
                                            .COMMAND_IN_FLIGHT
                                    else -> null
                                },
                                versionName = versionName,
                                buildNumber = BuildConfig.VERSION_CODE.toString(),
                                onNavigationChange = ::navigate,
                                onGuardPrimaryAction = { action ->
                                    scope.launch {
                                        productViewModel.performGuardAction(action, product.graphGeneration) { _, verify, cancel ->
                                            this@MainActivity.presentPasscodeAuthentication(verify, cancel)
                                        }?.let(::openSettings)
                                    }
                                },
                                onGuardPause = { duration ->
                                    scope.launch {
                                        productViewModel.performPause(duration, product.graphGeneration) { _, verify, cancel ->
                                            this@MainActivity.presentPasscodeAuthentication(verify, cancel)
                                        }
                                    }
                                },
                                onOpenHelp = ::openHelp,
                                activityState = product.activity,
                                activityQueryCallbacks = activityCallbacks,
                                domainRowActionCallbacks = rowCallbacks,
                                domainReviewCallbacks = reviewCallbacks,
                                domainNoticeCallbacks = noticeCallbacks,
                                customizationState = customizationState,
                                customizationCallbacks = customizationCallbacks,
                                privacyDataState = privacyDataState,
                                privacyDataCallbacks = privacyDataCallbacks,
                                securityState = securityState,
                                securityCallbacks = securityCallbacks,
                                bugReportState = product.bugReport,
                                bugReportCallbacks = bugReportCallbacks,
                                settingsResolverState = settingsResolverState,
                                resolverSwitchState = product.resolverPlanSwitch,
                                onDnsSelection = if (product.chainedDnsEdit.canEditDns) { requested ->
                                    val generation = product.graphGeneration
                                    val confirmed = product.resolverSelection
                                    if (generation != null && confirmed != null) scope.launch {
                                        productViewModel.applyDnsTiers(
                                            requested.tiers.map { tier ->
                                                val entry = tier.entry
                                                com.lavasec.app.foundation.resolver.ResolverTierChoice(
                                                    providerId = entry.providerId.rawId,
                                                    transportToken = entry.transport.token,
                                                    name = tier.retainedName ?: entry.customName ?: "",
                                                    primary = entry.customDohUrl ?: entry.customDotEndpoint ?:
                                                        entry.customDoqEndpoint ?: entry.customPlainEndpoint ?: "",
                                                    secondary = entry.customSecondaryEndpoint ?: "",
                                                    isEnabled = tier.isEnabled,
                                                )
                                            },
                                            com.lavasec.app.product.ResolverDnsContext.encode(confirmed),
                                            generation,
                                        )
                                    }
                                } else null,
                                onClearNetworkActivity = {
                                    scope.launch { productViewModel.clearNetworkActivity() }
                                },
                                legalNoticeSections = legalNoticeSections,
                                legalPrivacyUrl = PRIVACY_URL,
                                onOpenUrl = ::openUrl,
                                nerdStatsState = nerdStatsState,
                                sampledTimeLabel = sampledTimeLabel,
                                filterLibraryState = product.filterLibrary,
                                filterOverviewNavigation = filterOverviewNavigation,
                                filterDetailState = product.filterDetail,
                                filterReviewState = product.filterReview,
                                filterProfileSwitchState = product.filterProfileSwitch,
                                filterLibraryMutationState = product.filterLibraryMutation,
                                filterPreparationState = product.filterPreparation,
                                filterLibraryActions = filterLibraryActions,
                                securityNowMillis = securityNowMillis.value,
                            )
                        }
                    }

                    else -> ProductUnavailableScreen(product.unavailableReason)
                }
                    }
                }
            }
        }
    }

    private fun enterNativeSecurityForeground() {
        if (externalEntryHandedOff || isFinishing || isDestroyed ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        productViewModel.state.value.graphGeneration?.let { generation ->
            productViewModel.securityEnterForeground(this, generation, null)
        }
    }

    override fun onResume() {
        super.onResume()
        enterNativeSecurityForeground()
    }

    override fun onPause() {
        productViewModel.state.value.graphGeneration?.let { generation ->
            productViewModel.securityPauseForeground(this, generation, null)
        }
        super.onPause()
    }

    override fun onDestroy() {
        productViewModel.securityRetireForeground(this)
        if (isChangingConfigurations) {
            activityRecreationMarker.markConfigurationChange()
        }
        activityScope.cancel()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList(
            SAVED_NAVIGATION_PATH,
            ArrayList(navigationRequest.value.navigation.save()),
        )
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (deliverNavigation(intent)) setIntent(intent)
    }

    private fun deliverNavigation(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val navigation = intent.dataString?.let { com.lavasec.app.edition.EditionDeepLinks.parse(it) } ?: return false
        navigationRequest.value = NavigationRequest(
            navigation = navigation,
            sequence = navigationRequest.value.sequence + 1,
        )
        handoffToReact(navigation)
        return true
    }

    private fun handoffToReact(navigation: ProductNavigation) {
        // The public destinations are covered RN pages. Keep the parsed target in native
        // custody, rather than staging it for this retained Compose shell to consume.
        val ticket = com.lavasec.app.reactnative.NativeDeepLinkEntryRegistry.process.stage(
            navigation, productViewModel.state.value.graphGeneration,
        )
        externalEntryHandedOff = true
        startActivity(Intent(this, com.lavasec.app.reactnative.LavaReactActivity::class.java)
            // The standard target must replace its old task instance and attach a fresh ticket.
            // SINGLE_TOP would reuse an Activity whose onCreate already consumed another entry.
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(com.lavasec.app.reactnative.LavaReactActivity.DEEP_LINK_ENTRY_EXTRA, ticket))
        finish()
    }

    private fun stageNavigation(navigation: ProductNavigation) {
        // External/restored routes always begin a new turn, even when their path is unchanged.
        securityRevisionFloor.value = productViewModel.securityResetAuthenticationTurn()
        activityScope.launch { productViewModel.stageNavigation(navigation) }
    }

    private fun navigate(navigation: ProductNavigation) {
        if (com.lavasec.app.edition.EditionNativeNavigation.requiresReactSettings(navigation)) {
            productViewModel.securityResetAuthenticationTurn()
            handoffToReact(ProductNavigation.settings)
            return
        }
        if (navigation.endsAuthenticationTurn(navigationRequest.value.navigation)) {
            securityRevisionFloor.value = productViewModel.securityResetAuthenticationTurn()
        }
        navigationRequest.value = navigationRequest.value.copy(navigation = navigation)
        // Revocation above precedes navigation publication; the revision floor prevents
        // a delayed owner-flow emission from exposing the new page with old grants.
        securityNowMillis.value = System.currentTimeMillis()
    }

    // Filter destinations are always constructed from the canonical Filters path, never from
    // the navigation captured at composition time: a remembered callback bundle would keep the
    // launch-time path and fail the open() receiver checks (e.g. Guard.open(FilterDetail))
    // once the user has navigated into Filters.
    private fun openFilterDetailNavigation(): ProductNavigation =
        ProductNavigation.guard
            .open(ProductDestination.Filters)
            .open(ProductDestination.FilterDetail)

    private fun openFilterLibraryNavigation(): ProductNavigation =
        ProductNavigation.guard
            .open(ProductDestination.Filters)
            .open(ProductDestination.FilterLibrary)

    private fun openFilterReviewNavigation(): ProductNavigation =
        openFilterDetailNavigation().open(ProductDestination.FilterReview)

    private fun openFilterPreparationNavigation(): ProductNavigation =
        openFilterReviewNavigation().open(ProductDestination.FilterPreparation)

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            activityScope.launch {
                productViewModel.completeNotificationPermission(granted = true)
            }
        }
    }

    private fun openSettings(route: SettingsRoute) {
        val intent = when (route) {
            SettingsRoute.VpnSettings -> Intent(Settings.ACTION_VPN_SETTINGS)
            SettingsRoute.NetworkSettings -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
            SettingsRoute.NotificationSettings -> Intent(
                Settings.ACTION_APP_NOTIFICATION_SETTINGS,
            ).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        startActivity(intent)
    }

    /** The recovery control belongs to this exact native route, not a later resumed window. */
    internal fun securityRecoveryResetAction(
        expectedGeneration: Long?,
        expectedForegroundEpoch: Long,
        currentForegroundEpoch: () -> Long,
        currentGeneration: () -> Long?,
        readSecurity: (Long) -> SecurityOwnerState?,
        reset: suspend () -> Boolean,
    ): suspend () -> Unit {
        val expectedRequest = navigationRequest.value
        val originalFlow = intent?.getStringExtra(NATIVE_FLOW_ROUTE)
        fun isCurrent(): Boolean = originalFlow == "security" &&
            expectedRequest.navigation.path.lastOrNull() == ProductDestination.Security && expectedGeneration != null &&
            navigationRequest.value == expectedRequest && currentGeneration() == expectedGeneration &&
            currentForegroundEpoch() == expectedForegroundEpoch && !isFinishing && !isDestroyed &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && hasWindowFocus() &&
            intent?.getStringExtra(NATIVE_FLOW_ROUTE) == originalFlow
        return resetAction@{
            if (!isCurrent()) return@resetAction
            val resetCompleted = reset()
            if (!resetCompleted || !isCurrent() || originalFlow != "security" ||
                expectedRequest.navigation.path.lastOrNull() != ProductDestination.Security) return@resetAction
            val security = readSecurity(checkNotNull(expectedGeneration)) ?: return@resetAction
            if (security.availability != SecurityAvailability.Available || security.recoveryRequired ||
                security.inFlight || security.enabled) return@resetAction
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    private fun openHelp() {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(HELP_URL)))
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private data class NavigationRequest(
        val navigation: ProductNavigation,
        val sequence: Long,
    )

    companion object {
        const val NATIVE_FLOW_ROUTE = "com.lavasec.app.NATIVE_FLOW_ROUTE"
        private const val HELP_URL = "https://lavasecurity.app/support/"
        private const val PRIVACY_URL = "https://lavasecurity.app/privacy/"
        private const val SAVED_NAVIGATION_PATH = "product_navigation_path"
    }
}

internal class MainProductViewModelFactory(
    private val application: LavasecApplication,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass == MainProductViewModel::class.java)
        return MainProductViewModel(
            factories = application.productOwnerFactory,
            rotateCredentialGraph = application::rotateCredentialGraph,
        ) as T
    }
}

internal class ActivityRecreationMarker : ViewModel() {
    private var configurationChangePending = false

    fun markConfigurationChange() {
        configurationChangePending = true
    }

    fun consumeConfigurationChange(): Boolean {
        val wasConfigurationChange = configurationChangePending
        configurationChangePending = false
        return wasConfigurationChange
    }
}

/** Maps the security owner state to the typed presentation state. */
private fun SecurityOwnerState.toUi(requiredRevision: Long): SecurityUiState {
    val timeout = com.lavasec.app.foundation.security.LockTimeoutOption.entries
        .firstOrNull { it.minutes == lockTimeoutMinutes }
        ?: com.lavasec.app.foundation.security.LockTimeoutOption.DEFAULT
    return when {
        availability == SecurityAvailability.Unavailable -> SecurityUiState.Unavailable
        recoveryRequired -> SecurityUiState.RecoveryFailure
        !enabled -> SecurityUiState.Disabled(lockTimeout = timeout)
        else -> SecurityUiState.Enabled(
            lastUnlockEpochMillis = lastUnlockEpochMillis,
            lastChangeEpochMillis = lastChangeEpochMillis,
            lockTimeout = timeout,
            protectedSurfaces = protectedSurfaces,
            authenticatedSurfaces = authenticatedSurfaces.takeIf { authenticationRevision >= requiredRevision } ?: emptySet(),
            inFlight = inFlight,
            failure = when (failure) {
                SecurityFailure.WRONG_PASSCODE -> SecurityFailureUi.WRONG_PASSCODE
                SecurityFailure.SECURE_STORAGE -> SecurityFailureUi.SECURE_STORAGE
                SecurityFailure.OPERATION_FAILED -> SecurityFailureUi.OPERATION_FAILED
                null -> null
            },
        )
    }
}
