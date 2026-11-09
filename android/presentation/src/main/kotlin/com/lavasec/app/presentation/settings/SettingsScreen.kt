package com.lavasec.app.presentation.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.lavasec.app.designsystem.LavaNavigationCard
import com.lavasec.app.designsystem.LavaNavigationCardMetrics
import com.lavasec.app.designsystem.LavaMetrics
import com.lavasec.app.designsystem.LavaScreenTypography
import com.lavasec.app.designsystem.LavaSection
import com.lavasec.app.designsystem.LavaSettingsIcons
import com.lavasec.app.designsystem.LocalLavaContentInsets
import com.lavasec.app.designsystem.lavaScreenScrollPadding
import com.lavasec.app.platform.LoggingSettings
import com.lavasec.app.presentation.ProductDestination
import com.lavasec.app.presentation.R
import com.lavasec.app.presentation.resolver.rowSummary

/**
 * One root-row typed state: the row's localized title/summary resource ids, the
 * exact destination it opens, and the pinned icon role. The screen derives the
 * visible copy from these values only — no renderer ever compares row copy.
 * [summaryArgs] composes summaries from typed data (the resolver row composes
 * "<Provider> (<Transport>)" from the catalog selection).
 */
data class SettingsRowState(
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
    val summaryArgs: List<Any> = emptyList(),
    val enabled: Boolean = true,
    val destination: ProductDestination,
    val iconRole: SettingsRowIconRole,
)

/** The pinned iOS Settings glyph roles (SF Symbol roles from SettingsView.swift). */
enum class SettingsRowIconRole {
    Account,
    Upgrade,
    Customization,
    PrivacyData,
    Security,
    DnsResolver,
    BugReport,
    Help,
    Legal,
    NerdStats,
    NetworkActivity,
}

/** The iOS SettingsView group this destination belongs to. */
enum class SettingsRowGroup {
    YourLava,
    ProtectionChoices,
    Support,
    Advanced,
}

internal val SettingsRowState.group: SettingsRowGroup
    get() = when (destination) {
        ProductDestination.Account,
        ProductDestination.Upgrade,
        ProductDestination.Customization,
        -> SettingsRowGroup.YourLava
        ProductDestination.PrivacyData,
        ProductDestination.Security,
        ProductDestination.DnsResolver,
        -> SettingsRowGroup.ProtectionChoices
        ProductDestination.BugReport,
        ProductDestination.LegalNotices,
        -> SettingsRowGroup.Support
        ProductDestination.NerdStats,
        ProductDestination.NetworkActivity,
        -> SettingsRowGroup.Advanced
        else -> error("${destination.name} is not a Settings root row destination")
    }

/**
 * The canonical Settings root rows in the iOS group order, filtered to the rows
 * whose product state/handler is actually available. Pure mapping — the
 * summaries are composed here from the owners' real state (the privacy summary
 * is never invented, and the security summary reflects the actual lock state).
 */
fun settingsRowStates(
    account: AccountUiState?,
    entitlement: EntitlementUiState?,
    customization: CustomizationUiState?,
    privacyLogging: LoggingSettings?,
    security: SecurityUiState?,
    resolver: SettingsResolverUiState?,
    bugReportAvailable: Boolean,
    legalNoticesAvailable: Boolean,
    nerdStatsAvailable: Boolean,
    networkActivityAvailable: Boolean,
): List<SettingsRowState> = buildList {
    // Your Lava. Account and Upgrade are honest unavailable destinations until
    // the Android backend contract exists; the rows exist and navigate.
    if (account != null) {
        add(
            SettingsRowState(
                titleRes = R.string.settings_account_backup_title,
                summaryRes = R.string.settings_account_backup_summary,
                destination = ProductDestination.Account,
                iconRole = SettingsRowIconRole.Account,
            ),
        )
    }
    if (entitlement != null) {
        add(
            SettingsRowState(
                titleRes = R.string.settings_upgrade_title,
                summaryRes = R.string.settings_upgrade_summary,
                destination = ProductDestination.Upgrade,
                iconRole = SettingsRowIconRole.Upgrade,
            ),
        )
    }
    if (customization != null) {
        add(
            SettingsRowState(
                titleRes = R.string.settings_customization_title,
                summaryRes = R.string.settings_customization_summary,
                destination = ProductDestination.Customization,
                iconRole = SettingsRowIconRole.Customization,
            ),
        )
    }
    // Protection choices: Privacy & data before Security, DNS Resolver after
    // (the iOS order).
    if (privacyLogging != null) {
        add(
            SettingsRowState(
                titleRes = R.string.settings_privacy_data_title,
                summaryRes = privacySummaryRes(privacyLogging),
                destination = ProductDestination.PrivacyData,
                iconRole = SettingsRowIconRole.PrivacyData,
            ),
        )
    }
    if (security != null) {
        add(
            SettingsRowState(
                titleRes = R.string.settings_security_title,
                summaryRes = securitySummaryRes(security),
                destination = ProductDestination.Security,
                iconRole = SettingsRowIconRole.Security,
            ),
        )
    }
    if (resolver != null) {
        // The confirmed retained selection supplies the provider, name and actual transport.
        val summary = resolver.selection?.rowSummary() ?: resolver.plan?.rowSummary()
        add(
            SettingsRowState(
                titleRes = R.string.settings_dns_resolver_title,
                summaryRes = summary?.resource ?: R.string.settings_dns_resolver_summary,
                summaryArgs = summary?.args ?: emptyList(),
                destination = ProductDestination.DnsResolver,
                iconRole = SettingsRowIconRole.DnsResolver,
            ),
        )
    }
    // Support: the multi-step Bug Report flow replaces the inline feedback
    // composer; Legal Notices keeps its existing route (Help is host-owned and
    // rendered by the screen alongside this group).
    if (bugReportAvailable) {
        add(
            SettingsRowState(
                titleRes = R.string.settings_bug_report_title,
                summaryRes = R.string.settings_bug_report_summary,
                destination = ProductDestination.BugReport,
                iconRole = SettingsRowIconRole.BugReport,
            ),
        )
    }
    if (legalNoticesAvailable) {
        add(
            SettingsRowState(
                titleRes = R.string.settings_legal_title,
                summaryRes = R.string.settings_legal_summary,
                destination = ProductDestination.LegalNotices,
                iconRole = SettingsRowIconRole.Legal,
            ),
        )
    }
    if (nerdStatsAvailable) {
        add(
            SettingsRowState(
                titleRes = R.string.settings_nerd_stats_title,
                summaryRes = R.string.settings_nerd_stats_summary,
                destination = ProductDestination.NerdStats,
                iconRole = SettingsRowIconRole.NerdStats,
            ),
        )
    }
    if (networkActivityAvailable) {
        add(
            SettingsRowState(
                titleRes = R.string.settings_network_activity_title,
                summaryRes = if (privacyLogging?.keepNetworkActivity == false) {
                    R.string.settings_network_activity_off
                } else {
                    R.string.settings_network_activity_on
                },
                destination = ProductDestination.NetworkActivity,
                iconRole = SettingsRowIconRole.NetworkActivity,
            ),
        )
    }
}

/** "All local logs on/off" or the honest mixed summary, derived from the owner. */
private fun privacySummaryRes(logging: LoggingSettings): Int = when {
    logging.keepCounts && logging.keepHistory && logging.keepNetworkActivity ->
        R.string.settings_privacy_data_summary_all_on
    !logging.keepCounts && !logging.keepHistory && !logging.keepNetworkActivity ->
        R.string.settings_privacy_data_summary_all_off
    else -> R.string.settings_privacy_data_summary_some
}

private fun securitySummaryRes(security: SecurityUiState): Int = when (security) {
    is SecurityUiState.Enabled -> R.string.settings_security_summary_on
    is SecurityUiState.Disabled -> R.string.settings_security_summary_off
    SecurityUiState.Unavailable,
    SecurityUiState.RecoveryFailure,
    -> R.string.settings_security_summary_unavailable
}

/**
 * Settings composed around the iOS information architecture (SettingsView.swift):
 * the Your Lava / Protection choices / Support / Advanced group order with one
 * [LavaNavigationCard] per route, then the version/build footer instead of a
 * large outlined About card. Slice 04 turns the inline privacy/feedback controls
 * into the navigable Privacy & data, Security, and Bug Report routes; the root
 * holds only rows whose real product state exists. Help stays a host-opened
 * external link (it has no product destination).
 */
@Composable
fun SettingsScreen(
    versionName: String,
    buildNumber: String?,
    rows: List<SettingsRowState>,
    onRowClick: (SettingsRowState) -> Unit,
    modifier: Modifier = Modifier,
    onOpenHelp: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .lavaScreenScrollPadding(LocalLavaContentInsets.current)
            .testTag("settings_screen"),
        verticalArrangement = Arrangement.spacedBy(LavaMetrics.sectionSpacing),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = LavaScreenTypography.screenTitle,
            modifier = Modifier.semantics { heading() },
        )
        SettingsRowGroup.entries.forEach { group ->
            val groupRows = rows.filter { it.group == group }
            if (groupRows.isNotEmpty() || (group == SettingsRowGroup.Support && onOpenHelp != null)) {
                LavaSection(
                    title = stringResource(group.titleRes),
                    contentSpacing = LavaNavigationCardMetrics.interCardSpacing,
                ) {
                    groupRows.forEach { row ->
                        LavaNavigationCard(
                            title = stringResource(row.titleRes),
                            summary = stringResource(row.summaryRes, *row.summaryArgs.toTypedArray()),
                            enabled = row.enabled,
                            leadingIcon = row.iconRole.icon,
                            onClick = { onRowClick(row) },
                            modifier = Modifier.testTag(row.testTag),
                        )
                    }
                    if (group == SettingsRowGroup.Support && onOpenHelp != null) {
                        LavaNavigationCard(
                            title = stringResource(R.string.help_title),
                            summary = stringResource(R.string.help_external_summary),
                            leadingIcon = LavaSettingsIcons.Help,
                            onClick = onOpenHelp,
                            modifier = Modifier.testTag("settings_help_card"),
                        )
                    }
                }
            }
        }
        // iOS renders appVersionString as a centered footer below the groups
        // (SettingsView.swift); Android keeps the version/build line out of a card.
        Text(
            text = buildNumber?.let {
                stringResource(R.string.version_build_format, versionName, it)
            } ?: stringResource(R.string.version_format, versionName),
            style = LavaScreenTypography.screenFooter,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().testTag("settings_version_footer"),
        )
    }
}

internal val SettingsRowGroup.titleRes: Int
    get() = when (this) {
        SettingsRowGroup.YourLava -> R.string.settings_your_lava_title
        SettingsRowGroup.ProtectionChoices -> R.string.settings_protection_choices_title
        SettingsRowGroup.Support -> R.string.settings_support_title
        SettingsRowGroup.Advanced -> R.string.settings_advanced_title
    }

internal val SettingsRowIconRole.icon: ImageVector
    get() = when (this) {
        SettingsRowIconRole.Account -> LavaSettingsIcons.Account
        SettingsRowIconRole.Upgrade -> LavaSettingsIcons.Upgrade
        SettingsRowIconRole.Customization -> LavaSettingsIcons.Customization
        SettingsRowIconRole.PrivacyData -> LavaSettingsIcons.PrivacyData
        SettingsRowIconRole.Security -> LavaSettingsIcons.Lock
        SettingsRowIconRole.DnsResolver -> LavaSettingsIcons.Globe
        SettingsRowIconRole.BugReport -> LavaSettingsIcons.Bug
        SettingsRowIconRole.Help -> LavaSettingsIcons.Help
        SettingsRowIconRole.Legal -> LavaSettingsIcons.Doc
        SettingsRowIconRole.NerdStats -> LavaSettingsIcons.Info
        SettingsRowIconRole.NetworkActivity -> LavaSettingsIcons.NetworkActivity
    }

internal val SettingsRowState.testTag: String
    get() = when (destination) {
        ProductDestination.Account -> "settings_account_card"
        ProductDestination.Upgrade -> "settings_upgrade_card"
        ProductDestination.Customization -> "settings_customization_card"
        ProductDestination.PrivacyData -> "settings_privacy_data_card"
        ProductDestination.Security -> "settings_security_card"
        ProductDestination.DnsResolver -> "settings_dns_resolver_card"
        ProductDestination.BugReport -> "settings_bug_report_card"
        ProductDestination.LegalNotices -> "settings_legal_card"
        ProductDestination.NerdStats -> "settings_nerd_stats_card"
        ProductDestination.NetworkActivity -> "settings_network_activity_card"
        else -> error("${destination.name} has no Settings root card")
    }
