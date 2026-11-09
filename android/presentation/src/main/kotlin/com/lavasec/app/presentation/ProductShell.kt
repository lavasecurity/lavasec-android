package com.lavasec.app.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lavasec.app.designsystem.LavaContentInsets
import com.lavasec.app.designsystem.LavaIcons
import com.lavasec.app.designsystem.LocalLavaContentInsets
import com.lavasec.app.presentation.activity.ActivityQueryCallbacks
import com.lavasec.app.presentation.activity.ActivityScreen
import com.lavasec.app.presentation.activity.ActivityUiState
import com.lavasec.app.presentation.activity.DomainActionReviewDialog
import com.lavasec.app.presentation.activity.DomainHistoryScreen
import com.lavasec.app.presentation.activity.DomainNoticeCallbacks
import com.lavasec.app.presentation.activity.DomainReviewCallbacks
import com.lavasec.app.presentation.activity.DomainRowActionCallbacks
import com.lavasec.app.presentation.activity.TopDomainsScreen
import com.lavasec.app.presentation.filters.FilterDetailScreen
import com.lavasec.app.presentation.filters.FilterDetailState
import com.lavasec.app.presentation.filters.FilterLibraryActions
import com.lavasec.app.presentation.filters.FilterLibraryMutationUiState
import com.lavasec.app.presentation.filters.FilterLibraryScreen
import com.lavasec.app.presentation.filters.FilterLibraryState
import com.lavasec.app.presentation.filters.FilterOverviewNavigation
import com.lavasec.app.presentation.filters.FilterPreparationScreen
import com.lavasec.app.presentation.filters.FilterPreparationUiState
import com.lavasec.app.presentation.filters.FilterProfileSwitchUiState
import com.lavasec.app.presentation.filters.FilterReviewScreen
import com.lavasec.app.presentation.filters.FilterReviewState
import com.lavasec.app.presentation.filters.FiltersScreen
import com.lavasec.app.presentation.guard.GuardScreen
import com.lavasec.app.presentation.settings.AccountSettingsScreen
import com.lavasec.app.presentation.settings.AccountUiState
import com.lavasec.app.presentation.settings.AppUnlockOverlay
import com.lavasec.app.presentation.settings.BugReportCallbacks
import com.lavasec.app.presentation.settings.BugReportScreen
import com.lavasec.app.presentation.settings.BugReportUiState
import com.lavasec.app.presentation.settings.CustomizationCallbacks
import com.lavasec.app.presentation.settings.CustomizationSettingsScreen
import com.lavasec.app.presentation.settings.CustomizationUiState
import com.lavasec.app.presentation.settings.DnsResolverSettingsScreen
import com.lavasec.app.presentation.settings.EntitlementUiState
import com.lavasec.app.presentation.settings.LegalNoticeSectionUi
import com.lavasec.app.presentation.settings.LegalNoticesScreen
import com.lavasec.app.presentation.settings.NerdStatsScreen
import com.lavasec.app.presentation.settings.NerdStatsUiState
import com.lavasec.app.presentation.settings.NetworkActivityScreen
import com.lavasec.app.presentation.settings.PrivacyDataCallbacks
import com.lavasec.app.presentation.settings.PrivacyDataScreen
import com.lavasec.app.presentation.settings.PrivacyDataUiState
import com.lavasec.app.presentation.settings.ResolverPlanSwitchUiState
import com.lavasec.app.presentation.settings.SecurityCallbacks
import com.lavasec.app.presentation.settings.SecuritySettingsScreen
import com.lavasec.app.presentation.settings.protects
import com.lavasec.app.presentation.settings.SecurityUiState
import com.lavasec.app.presentation.settings.SettingsResolverUiState
import com.lavasec.app.presentation.settings.SettingsScreen
import com.lavasec.app.presentation.settings.SettingsRowState
import com.lavasec.app.presentation.settings.UpgradeSettingsScreen
import com.lavasec.app.presentation.settings.settingsRowStates

@Composable
fun ProductShell(
    navigation: ProductNavigation,
    guardHealth: GuardProtectionHealthUi,
    versionName: String,
    buildNumber: String?,
    onNavigationChange: (ProductNavigation) -> Unit,
    modifier: Modifier = Modifier,
    onGuardPrimaryAction: ((GuardPrimaryAction) -> Unit)? = null,
    onGuardPause: ((com.lavasec.app.foundation.pause.PauseDuration) -> Unit)? = null,
    onOpenHelp: (() -> Unit)? = null,
    activityState: ActivityUiState = ActivityUiState.unavailable,
    activityQueryCallbacks: ActivityQueryCallbacks? = null,
    domainRowActionCallbacks: DomainRowActionCallbacks? = null,
    domainReviewCallbacks: DomainReviewCallbacks? = null,
    domainNoticeCallbacks: DomainNoticeCallbacks? = null,
    accountState: AccountUiState = AccountUiState.Unavailable,
    entitlementState: EntitlementUiState = EntitlementUiState.Unavailable,
    customizationState: CustomizationUiState? = null,
    customizationCallbacks: CustomizationCallbacks? = null,
    privacyDataState: PrivacyDataUiState? = null,
    privacyDataCallbacks: PrivacyDataCallbacks? = null,
    securityState: SecurityUiState? = null,
    securityCallbacks: SecurityCallbacks? = null,
    bugReportState: BugReportUiState = BugReportUiState(),
    bugReportCallbacks: BugReportCallbacks? = null,
    settingsResolverState: SettingsResolverUiState? = null,
    resolverSwitchState: ResolverPlanSwitchUiState = ResolverPlanSwitchUiState.Idle,
    onResolverSelected: ((com.lavasec.app.foundation.resolver.MilestoneResolverPlan) -> Unit)? = null,
    onDnsSelection: ((com.lavasec.app.foundation.resolver.ResolverSelection) -> Unit)? = null,
    onClearNetworkActivity: (() -> Unit)? = null,
    legalNoticeSections: List<LegalNoticeSectionUi> = emptyList(),
    legalPrivacyUrl: String = "",
    onOpenUrl: ((String) -> Unit)? = null,
    nerdStatsState: NerdStatsUiState? = null,
    sampledTimeLabel: ((Long) -> String)? = null,
    guardError: String? = null,
    guardActionDisabled: GuardActionDisabledUi? = null,
    filterLibraryState: FilterLibraryState? = null,
    filterOverviewNavigation: FilterOverviewNavigation? = null,
    filterDetailState: FilterDetailState? = null,
    filterReviewState: FilterReviewState? = null,
    filterProfileSwitchState: FilterProfileSwitchUiState = FilterProfileSwitchUiState.Idle,
    filterLibraryMutationState: FilterLibraryMutationUiState = FilterLibraryMutationUiState.Idle,
    filterPreparationState: FilterPreparationUiState = FilterPreparationUiState.Idle,
    filterLibraryActions: FilterLibraryActions? = null,
    /**
     * The clock the app-unlock gate evaluates against. The host refreshes this
     * on foreground and on every navigation change so an expired unlock
     * session re-locks the protected surfaces deterministically; tests pin it.
     */
    securityNowMillis: Long = 0L,
) {
    val childVisible = navigation.path.size > 1
    BackHandler(enabled = childVisible) {
        val result = navigation.onBack()
        if (result is ProductBackResult.Navigate) onNavigationChange(result.navigation)
    }

    Box(modifier = modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            when (ProductLayout.forWidth(maxWidth)) {
                ProductLayout.Compact -> CompactShell(
                    navigation = navigation,
                    guardHealth = guardHealth,
                    versionName = versionName,
                    buildNumber = buildNumber,
                    onNavigationChange = onNavigationChange,
                    onGuardPrimaryAction = onGuardPrimaryAction,
                    onGuardPause = onGuardPause,
                    onOpenHelp = onOpenHelp,
                    activityState = activityState,
                    activityQueryCallbacks = activityQueryCallbacks,
                    domainRowActionCallbacks = domainRowActionCallbacks,
                    settings = SettingsProductPorts(
                        accountState = accountState,
                        entitlementState = entitlementState,
                        customizationState = customizationState,
                        customizationCallbacks = customizationCallbacks,
                        privacyDataState = privacyDataState,
                        privacyDataCallbacks = privacyDataCallbacks,
                        securityState = securityState,
                        securityCallbacks = securityCallbacks,
                        bugReportState = bugReportState,
                        bugReportCallbacks = bugReportCallbacks,
                        resolverState = settingsResolverState,
                        resolverSwitchState = resolverSwitchState,
                        onResolverSelected = onResolverSelected,
                        onDnsSelection = onDnsSelection,
                        onClearNetworkActivity = onClearNetworkActivity,
                        securityNowMillis = securityNowMillis,
                    ),
                    legalNoticeSections = legalNoticeSections,
                    legalPrivacyUrl = legalPrivacyUrl,
                    onOpenUrl = onOpenUrl,
                    nerdStatsState = nerdStatsState,
                    sampledTimeLabel = sampledTimeLabel,
                    guardError = guardError,
                    guardActionDisabled = guardActionDisabled,
                    filterLibraryState = filterLibraryState,
                    filterOverviewNavigation = filterOverviewNavigation,
                    filterDetailState = filterDetailState,
                    filterReviewState = filterReviewState,
                    filterProfileSwitchState = filterProfileSwitchState,
                    filterLibraryMutationState = filterLibraryMutationState,
                    filterPreparationState = filterPreparationState,
                    filterLibraryActions = filterLibraryActions,
                )

                ProductLayout.Expanded -> ExpandedShell(
                    navigation = navigation,
                    guardHealth = guardHealth,
                    versionName = versionName,
                    buildNumber = buildNumber,
                    onNavigationChange = onNavigationChange,
                    onGuardPrimaryAction = onGuardPrimaryAction,
                    onGuardPause = onGuardPause,
                    onOpenHelp = onOpenHelp,
                    activityState = activityState,
                    activityQueryCallbacks = activityQueryCallbacks,
                    domainRowActionCallbacks = domainRowActionCallbacks,
                    settings = SettingsProductPorts(
                        accountState = accountState,
                        entitlementState = entitlementState,
                        customizationState = customizationState,
                        customizationCallbacks = customizationCallbacks,
                        privacyDataState = privacyDataState,
                        privacyDataCallbacks = privacyDataCallbacks,
                        securityState = securityState,
                        securityCallbacks = securityCallbacks,
                        bugReportState = bugReportState,
                        bugReportCallbacks = bugReportCallbacks,
                        resolverState = settingsResolverState,
                        resolverSwitchState = resolverSwitchState,
                        onResolverSelected = onResolverSelected,
                        onDnsSelection = onDnsSelection,
                        onClearNetworkActivity = onClearNetworkActivity,
                        securityNowMillis = securityNowMillis,
                    ),
                    legalNoticeSections = legalNoticeSections,
                    legalPrivacyUrl = legalPrivacyUrl,
                    onOpenUrl = onOpenUrl,
                    nerdStatsState = nerdStatsState,
                    sampledTimeLabel = sampledTimeLabel,
                    guardError = guardError,
                    guardActionDisabled = guardActionDisabled,
                    filterLibraryState = filterLibraryState,
                    filterOverviewNavigation = filterOverviewNavigation,
                    filterDetailState = filterDetailState,
                    filterReviewState = filterReviewState,
                    filterProfileSwitchState = filterProfileSwitchState,
                    filterLibraryMutationState = filterLibraryMutationState,
                    filterPreparationState = filterPreparationState,
                    filterLibraryActions = filterLibraryActions,
                )
            }
        }
        DomainActionReviewDialog(
            state = activityState.domainAction,
            notice = activityState.domainActionNotice,
            reviewCallbacks = domainReviewCallbacks,
            noticeCallbacks = domainNoticeCallbacks,
        )
    }
}

@Composable
private fun CompactShell(
    navigation: ProductNavigation,
    guardHealth: GuardProtectionHealthUi,
    versionName: String,
    buildNumber: String?,
    onNavigationChange: (ProductNavigation) -> Unit,
    onGuardPrimaryAction: ((GuardPrimaryAction) -> Unit)?,
    onGuardPause: ((com.lavasec.app.foundation.pause.PauseDuration) -> Unit)?,
    onOpenHelp: (() -> Unit)?,
    activityState: ActivityUiState,
    activityQueryCallbacks: ActivityQueryCallbacks?,
    domainRowActionCallbacks: DomainRowActionCallbacks?,
    settings: SettingsProductPorts,
    legalNoticeSections: List<LegalNoticeSectionUi>,
    legalPrivacyUrl: String,
    onOpenUrl: ((String) -> Unit)?,
    nerdStatsState: NerdStatsUiState?,
    sampledTimeLabel: ((Long) -> String)?,
    guardError: String?,
    guardActionDisabled: GuardActionDisabledUi?,
    filterLibraryState: FilterLibraryState?,
    filterOverviewNavigation: FilterOverviewNavigation?,
    filterDetailState: FilterDetailState?,
    filterReviewState: FilterReviewState?,
    filterProfileSwitchState: FilterProfileSwitchUiState,
    filterLibraryMutationState: FilterLibraryMutationUiState,
    filterPreparationState: FilterPreparationUiState,
    filterLibraryActions: FilterLibraryActions?,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            RootNavigationBar(navigation, onNavigationChange)
        },
    ) { scaffoldPadding ->
        // The Scaffold reserves the bottom bar region (bar height plus its consumed
        // safe insets) in scaffoldPadding.bottom. The scrollable screens consume that
        // reservation through LocalLavaContentInsets at their scroll container, so the
        // last row always clears the navigation surface by the pinned 16dp clearance —
        // derived from Scaffold inner padding + WindowInsets.safeDrawing, never from a
        // hardcoded screenshot height.
        val navigationSurfaceHeight = scaffoldPadding.calculateBottomPadding()
        CompositionLocalProvider(
            LocalLavaContentInsets provides LavaContentInsets(
                top = scaffoldPadding.calculateTopPadding(),
                bottom = navigationSurfaceHeight,
                navigationSurfaceHeight = navigationSurfaceHeight,
            ),
        ) {
            val layoutDirection = LocalLayoutDirection.current
            ProductContent(
                navigation = navigation,
                guardHealth = guardHealth,
                versionName = versionName,
                buildNumber = buildNumber,
                onNavigationChange = onNavigationChange,
                onGuardPrimaryAction = onGuardPrimaryAction,
                    onGuardPause = onGuardPause,
                onOpenHelp = onOpenHelp,
                activityState = activityState,
                activityQueryCallbacks = activityQueryCallbacks,
                domainRowActionCallbacks = domainRowActionCallbacks,
                settings = settings,
                legalNoticeSections = legalNoticeSections,
                legalPrivacyUrl = legalPrivacyUrl,
                onOpenUrl = onOpenUrl,
                nerdStatsState = nerdStatsState,
                sampledTimeLabel = sampledTimeLabel,
                guardError = guardError,
                guardActionDisabled = guardActionDisabled,
                filterLibraryState = filterLibraryState,
                filterOverviewNavigation = filterOverviewNavigation,
                filterDetailState = filterDetailState,
                filterReviewState = filterReviewState,
                filterProfileSwitchState = filterProfileSwitchState,
                filterLibraryMutationState = filterLibraryMutationState,
                filterPreparationState = filterPreparationState,
                filterLibraryActions = filterLibraryActions,
                // Only top/side insets pad the shell viewport; the bottom reservation
                // belongs to the scroll containers, otherwise the 16dp clearance would
                // be double-counted.
                padding = PaddingValues(
                    top = scaffoldPadding.calculateTopPadding(),
                    start = scaffoldPadding.calculateStartPadding(layoutDirection),
                    end = scaffoldPadding.calculateEndPadding(layoutDirection),
                    bottom = 0.dp,
                ),
            )
        }
    }
}

@Composable
private fun ExpandedShell(
    navigation: ProductNavigation,
    guardHealth: GuardProtectionHealthUi,
    versionName: String,
    buildNumber: String?,
    onNavigationChange: (ProductNavigation) -> Unit,
    onGuardPrimaryAction: ((GuardPrimaryAction) -> Unit)?,
    onGuardPause: ((com.lavasec.app.foundation.pause.PauseDuration) -> Unit)?,
    onOpenHelp: (() -> Unit)?,
    activityState: ActivityUiState,
    activityQueryCallbacks: ActivityQueryCallbacks?,
    domainRowActionCallbacks: DomainRowActionCallbacks?,
    settings: SettingsProductPorts,
    legalNoticeSections: List<LegalNoticeSectionUi>,
    legalPrivacyUrl: String,
    onOpenUrl: ((String) -> Unit)?,
    nerdStatsState: NerdStatsUiState?,
    sampledTimeLabel: ((Long) -> String)?,
    guardError: String?,
    guardActionDisabled: GuardActionDisabledUi?,
    filterLibraryState: FilterLibraryState?,
    filterOverviewNavigation: FilterOverviewNavigation?,
    filterDetailState: FilterDetailState?,
    filterReviewState: FilterReviewState?,
    filterProfileSwitchState: FilterProfileSwitchUiState,
    filterLibraryMutationState: FilterLibraryMutationUiState,
    filterPreparationState: FilterPreparationUiState,
    filterLibraryActions: FilterLibraryActions?,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        RootNavigationRail(navigation, onNavigationChange)
        VerticalDivider(modifier = Modifier.fillMaxHeight())
        val density = LocalDensity.current
        val safeDrawing = WindowInsets.safeDrawing
        CompositionLocalProvider(
            LocalLavaContentInsets provides LavaContentInsets(
                top = with(density) { safeDrawing.getTop(this).toDp() },
                bottom = with(density) { safeDrawing.getBottom(this).toDp() },
                // No bottom navigation surface on the expanded rail layout.
                navigationSurfaceHeight = 0.dp,
            ),
        ) {
            ProductContent(
                navigation = navigation,
                guardHealth = guardHealth,
                versionName = versionName,
                buildNumber = buildNumber,
                onNavigationChange = onNavigationChange,
                onGuardPrimaryAction = onGuardPrimaryAction,
                    onGuardPause = onGuardPause,
                onOpenHelp = onOpenHelp,
                activityState = activityState,
                activityQueryCallbacks = activityQueryCallbacks,
                domainRowActionCallbacks = domainRowActionCallbacks,
                settings = settings,
                legalNoticeSections = legalNoticeSections,
                legalPrivacyUrl = legalPrivacyUrl,
                onOpenUrl = onOpenUrl,
                nerdStatsState = nerdStatsState,
                sampledTimeLabel = sampledTimeLabel,
                guardError = guardError,
                guardActionDisabled = guardActionDisabled,
                filterLibraryState = filterLibraryState,
                filterOverviewNavigation = filterOverviewNavigation,
                filterDetailState = filterDetailState,
                filterReviewState = filterReviewState,
                filterProfileSwitchState = filterProfileSwitchState,
                filterLibraryMutationState = filterLibraryMutationState,
                filterPreparationState = filterPreparationState,
                filterLibraryActions = filterLibraryActions,
                padding = PaddingValues(),
                modifier = Modifier.weight(1f).windowInsetsPadding(WindowInsets.safeDrawing),
            )
        }
    }
}

@Composable
private fun ProductContent(
    navigation: ProductNavigation,
    guardHealth: GuardProtectionHealthUi,
    versionName: String,
    buildNumber: String?,
    onNavigationChange: (ProductNavigation) -> Unit,
    onGuardPrimaryAction: ((GuardPrimaryAction) -> Unit)?,
    onGuardPause: ((com.lavasec.app.foundation.pause.PauseDuration) -> Unit)?,
    onOpenHelp: (() -> Unit)?,
    activityState: ActivityUiState,
    activityQueryCallbacks: ActivityQueryCallbacks?,
    domainRowActionCallbacks: DomainRowActionCallbacks?,
    settings: SettingsProductPorts,
    legalNoticeSections: List<LegalNoticeSectionUi>,
    legalPrivacyUrl: String,
    onOpenUrl: ((String) -> Unit)?,
    nerdStatsState: NerdStatsUiState?,
    sampledTimeLabel: ((Long) -> String)?,
    guardError: String?,
    guardActionDisabled: GuardActionDisabledUi?,
    filterLibraryState: FilterLibraryState?,
    filterOverviewNavigation: FilterOverviewNavigation?,
    filterDetailState: FilterDetailState?,
    filterReviewState: FilterReviewState?,
    filterProfileSwitchState: FilterProfileSwitchUiState,
    filterLibraryMutationState: FilterLibraryMutationUiState,
    filterPreparationState: FilterPreparationUiState,
    filterLibraryActions: FilterLibraryActions?,
    padding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val onChildBack: () -> Unit = {
        val result = navigation.onBack()
        if (result is ProductBackResult.Navigate) onNavigationChange(result.navigation)
    }
    val currentDestination = navigation.path.last()
    val unlockRequired = settings.securityState?.let { security ->
        (security as? SecurityUiState.Enabled)?.requiresUnlock(
            navigation = navigation,
            nowEpochMillis = settings.securityNowMillis,
        ) == true
    } == true
    val protectedPath = navigation.path.any { destination ->
        com.lavasec.app.foundation.security.ProtectedSurface.ALL.any { it.protects(destination) }
    }
    val unavailable = protectedPath && (settings.securityState == null ||
        settings.securityState == SecurityUiState.Unavailable ||
        settings.securityState == SecurityUiState.RecoveryFailure)
    Box(modifier = modifier.fillMaxSize().padding(padding)) {
        // Uncertainty is not authorization. Do not compose sensitive content under a scrim:
        // accessibility and retained child effects must have no underlying evidence to expose.
        if (unavailable) {
            SecuritySettingsScreen(
                state = settings.securityState ?: SecurityUiState.Unavailable,
                callbacks = settings.securityCallbacks,
                onBack = { onNavigationChange(ProductNavigation.guard) },
            )
            return@Box
        }
        // The app-unlock gate covers every protected surface whose session
        // expired; the passcode never leaves the device and the only way
        // through is the owner's verification.
        if (unlockRequired) {
            AppUnlockOverlay(
                wrongPasscode = (settings.securityState as? SecurityUiState.Enabled)
                    ?.failure == com.lavasec.app.presentation.settings.SecurityFailureUi
                    .WRONG_PASSCODE,
                onUnlock = { passcode ->
                    settings.securityCallbacks?.onUnlock(passcode)
                },
                onNavigateAway = if (settings.securityCallbacks != null) {
                    { onNavigationChange(ProductNavigation.guard) }
                } else {
                    null
                },
            )
            return@Box
        }
        when (currentDestination) {
            ProductDestination.Guard -> GuardScreen(
                health = guardHealth,
                onOpenFilters = {
                    onNavigationChange(navigation.open(ProductDestination.Filters))
                },
                onOpenActivity = {
                    onNavigationChange(navigation.open(ProductDestination.Activity))
                },
                onPrimaryAction = onGuardPrimaryAction,
                onPause = onGuardPause,
                disabledReason = guardActionDisabled,
                error = guardError,
            )

            ProductDestination.Filters -> FiltersScreen(
                onBack = onChildBack,
                state = filterLibraryState,
                navigation = filterOverviewNavigation,
            )

            ProductDestination.FilterLibrary -> FilterLibraryScreen(
                onBack = onChildBack,
                state = filterLibraryState,
                switchState = filterProfileSwitchState,
                mutationState = filterLibraryMutationState,
                actions = filterLibraryActions,
            )
            ProductDestination.FilterDetail -> FilterDetailScreen(
                onBack = onChildBack,
                state = filterDetailState,
                actions = filterLibraryActions,
            )
            ProductDestination.FilterReview -> FilterReviewScreen(
                onBack = onChildBack,
                state = filterReviewState,
                actions = filterLibraryActions,
            )
            ProductDestination.FilterPreparation -> FilterPreparationScreen(
                // While a compile/publish is admitted the previous state must stay put (the
                // owner task keeps running either way); back only becomes live when the
                // preparation is idle or failed.
                onBack = {
                    if (filterPreparationState !is FilterPreparationUiState.Preparing) {
                        onChildBack()
                    }
                },
                state = filterPreparationState,
                actions = filterLibraryActions,
            )
            ProductDestination.Activity -> ActivityScreen(
                state = activityState,
                onBack = onChildBack,
                onOpenTopDomains = {
                    onNavigationChange(navigation.open(ProductDestination.TopDomains))
                },
                onOpenDomainHistory = {
                    onNavigationChange(navigation.open(ProductDestination.DomainHistory))
                },
                queryCallbacks = activityQueryCallbacks,
            )
            ProductDestination.TopDomains -> TopDomainsScreen(
                state = activityState.topDomains,
                onBack = onChildBack,
                queryState = activityState.topDomainsQuery,
                queryCallbacks = activityQueryCallbacks,
                rowActionCallbacks = domainRowActionCallbacks,
            )
            ProductDestination.DomainHistory -> DomainHistoryScreen(
                state = activityState.domainHistory,
                retainedDomainLogs = activityState.retainedDomainLogs,
                loadingMore = activityState.historyLoadingMore,
                onBack = onChildBack,
                queryState = activityState.domainHistoryQuery,
                queryCallbacks = activityQueryCallbacks,
                rowActionCallbacks = domainRowActionCallbacks,
            )
            ProductDestination.Settings -> SettingsScreen(
                versionName = versionName,
                buildNumber = buildNumber,
                onOpenHelp = onOpenHelp,
                rows = settingsRowStates(
                    account = settings.accountState,
                    entitlement = settings.entitlementState,
                    customization = settings.customizationState,
                    privacyLogging = settings.privacyDataState?.logging,
                    security = settings.securityState,
                    resolver = settings.resolverState,
                    bugReportAvailable = settings.bugReportCallbacks != null,
                    legalNoticesAvailable = legalNoticeSections.isNotEmpty(),
                    nerdStatsAvailable = nerdStatsState != null && sampledTimeLabel != null,
                    networkActivityAvailable = settings.privacyDataState?.logging != null &&
                        settings.onClearNetworkActivity != null,
                ),
                onRowClick = { row ->
                    onNavigationChange(navigation.open(row.destination))
                },
            )
            ProductDestination.Account -> AccountSettingsScreen(
                state = settings.accountState,
                onBack = onChildBack,
            )
            ProductDestination.Upgrade -> UpgradeSettingsScreen(
                state = settings.entitlementState,
                onBack = onChildBack,
            )
            ProductDestination.Customization -> CustomizationSettingsScreen(
                state = checkNotNull(settings.customizationState) {
                    "Customization requires an app-supplied state"
                },
                callbacks = settings.customizationCallbacks,
                onBack = onChildBack,
            )
            ProductDestination.PrivacyData -> PrivacyDataScreen(
                state = checkNotNull(settings.privacyDataState) {
                    "Privacy & data requires an app-supplied state"
                },
                callbacks = checkNotNull(settings.privacyDataCallbacks) {
                    "Privacy & data requires app-supplied callbacks"
                },
                onBack = onChildBack,
            )
            ProductDestination.Security -> SecuritySettingsScreen(
                state = checkNotNull(settings.securityState) {
                    "Security requires an app-supplied state"
                },
                callbacks = settings.securityCallbacks,
                onBack = onChildBack,
            )
            ProductDestination.BugReport -> BugReportScreen(
                state = settings.bugReportState,
                callbacks = checkNotNull(settings.bugReportCallbacks) {
                    "Bug Report requires app-supplied callbacks"
                },
                onBack = onChildBack,
            )
            ProductDestination.LegalNotices -> LegalNoticesScreen(
                sections = legalNoticeSections,
                privacyUrl = legalPrivacyUrl,
                onBack = onChildBack,
                onOpenUrl = { url ->
                    if (onOpenUrl != null) onOpenUrl(url)
                },
            )
            ProductDestination.NerdStats -> NerdStatsScreen(
                state = checkNotNull(nerdStatsState) {
                    "Nerd Stats requires an app-supplied state"
                },
                sampledTimeLabel = checkNotNull(sampledTimeLabel) {
                    "Nerd Stats requires an app-supplied time formatter"
                },
                onBack = onChildBack,
            )
            ProductDestination.DnsResolver -> DnsResolverSettingsScreen(
                state = checkNotNull(settings.resolverState) {
                    "DNS Resolver requires an app-supplied state"
                },
                switchState = settings.resolverSwitchState,
                onBack = onChildBack,
                onResolverSelected = settings.onResolverSelected,
                onDnsSelection = settings.onDnsSelection,
            )
            ProductDestination.NetworkActivity -> NetworkActivityScreen(
                state = activityState.networkActivity,
                loadingMore = activityState.networkLoadingMore,
                onBack = onChildBack,
                onRefresh = activityQueryCallbacks?.onRefreshNetworkActivity,
                onLoadMore = activityQueryCallbacks?.onLoadMoreNetworkActivity,
                onClear = settings.onClearNetworkActivity,
            )
        }

    }
}

@Composable
private fun RootNavigationBar(
    navigation: ProductNavigation,
    onNavigationChange: (ProductNavigation) -> Unit,
) {
    val selection = rootNavigationSelectionColors()
    NavigationBar(modifier = Modifier.testTag("product_navigation_bar")) {
        ProductRoot.entries.forEach { root ->
            NavigationBarItem(
                selected = navigation.path.first() == root.destination,
                onClick = { onNavigationChange(navigation.selectRoot(root)) },
                modifier = Modifier.testTag(root.testTag),
                icon = { Icon(root.icon, contentDescription = null) },
                label = { Text(stringResource(root.labelResource)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = selection.selected,
                    selectedTextColor = selection.selected,
                    indicatorColor = selection.indicator,
                    unselectedIconColor = selection.unselected,
                    unselectedTextColor = selection.unselected,
                ),
            )
        }
    }
}

@Composable
private fun RootNavigationRail(
    navigation: ProductNavigation,
    onNavigationChange: (ProductNavigation) -> Unit,
) {
    val selection = rootNavigationSelectionColors()
    NavigationRail(
        modifier = Modifier
            .fillMaxHeight()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("product_navigation_rail"),
    ) {
        ProductRoot.entries.forEach { root ->
            NavigationRailItem(
                selected = navigation.path.first() == root.destination,
                onClick = { onNavigationChange(navigation.selectRoot(root)) },
                modifier = Modifier.testTag(root.testTag),
                icon = { Icon(root.icon, contentDescription = null) },
                label = { Text(stringResource(root.labelResource)) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = selection.selected,
                    selectedTextColor = selection.selected,
                    indicatorColor = selection.indicator,
                    unselectedIconColor = selection.unselected,
                    unselectedTextColor = selection.unselected,
                ),
            )
        }
    }
}

/**
 * The shared root-tab selection mapping for the bar and the rail. iOS selects its
 * floating tab with the safe-green brand semantic; Android maps the same treatment
 * through the Material color scheme (primary IS safe-green in the Lava schemes),
 * so no literal orange/green color appears at this call site and orange stays
 * reserved for warning/brand accents.
 */
private data class RootNavigationSelectionColors(
    val selected: Color,
    val indicator: Color,
    val unselected: Color,
)

@Composable
private fun rootNavigationSelectionColors(): RootNavigationSelectionColors =
    RootNavigationSelectionColors(
        selected = MaterialTheme.colorScheme.primary,
        indicator = MaterialTheme.colorScheme.primaryContainer,
        unselected = MaterialTheme.colorScheme.onSurfaceVariant,
    )

private val ProductRoot.destination: ProductDestination
    get() = when (this) {
        ProductRoot.Guard -> ProductDestination.Guard
        ProductRoot.Settings -> ProductDestination.Settings
    }

private val ProductRoot.labelResource: Int
    get() = when (this) {
        ProductRoot.Guard -> R.string.guard_title
        ProductRoot.Settings -> R.string.settings_title
    }

private val ProductRoot.icon
    get() = when (this) {
        ProductRoot.Guard -> LavaIcons.Guard
        ProductRoot.Settings -> LavaIcons.Settings
    }

private val ProductRoot.testTag: String
    get() = when (this) {
        ProductRoot.Guard -> "product_root_guard"
        ProductRoot.Settings -> "product_root_settings"
    }

/**
 * The bundle of Settings-root/child ports that the compact and expanded shell
 * layouts forward to the shared [ProductContent]. Grouped so the two layout
 * shells pass one typed bundle instead of sixteen drifting parameters; the
 * public [ProductShell] surface stays explicit per-route.
 */
private data class SettingsProductPorts(
    val accountState: AccountUiState,
    val entitlementState: EntitlementUiState,
    val customizationState: CustomizationUiState?,
    val customizationCallbacks: CustomizationCallbacks?,
    val privacyDataState: PrivacyDataUiState?,
    val privacyDataCallbacks: PrivacyDataCallbacks?,
    val securityState: SecurityUiState?,
    val securityCallbacks: SecurityCallbacks?,
    val bugReportState: BugReportUiState,
    val bugReportCallbacks: BugReportCallbacks?,
    val resolverState: SettingsResolverUiState?,
    val resolverSwitchState: ResolverPlanSwitchUiState,
    val onResolverSelected: ((com.lavasec.app.foundation.resolver.MilestoneResolverPlan) -> Unit)?,
    val onDnsSelection: ((com.lavasec.app.foundation.resolver.ResolverSelection) -> Unit)?,
    val onClearNetworkActivity: (() -> Unit)?,
    val securityNowMillis: Long,
)
