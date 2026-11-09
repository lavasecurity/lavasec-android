package com.lavasec.app.presentation.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lavasec.app.designsystem.LavaIcons
import com.lavasec.app.designsystem.LavaPanel
import com.lavasec.app.designsystem.LavaRadii
import com.lavasec.app.designsystem.LavaScreenTypography
import com.lavasec.app.designsystem.LavaSpacing
import com.lavasec.app.designsystem.LocalLavaContentInsets
import com.lavasec.app.designsystem.lavaScreenScrollPadding
import com.lavasec.app.foundation.config.AppConfiguration
import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.onboarding.OnboardingPage
import com.lavasec.app.foundation.onboarding.OnboardingState
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.foundation.resolver.CatalogResolverTransport
import com.lavasec.app.foundation.resolver.ResolverConfiguration
import com.lavasec.app.foundation.resolver.toSelection
import com.lavasec.app.presentation.R
import com.lavasec.app.presentation.resolver.labelResource
import com.lavasec.app.presentation.resolver.summaryResource
import androidx.compose.ui.platform.testTag

data class OnboardingUiState(
    val onboarding: OnboardingState,
    val vpnConsentPending: Boolean = false,
    val vpnConsentDenied: Boolean = false,
    val notificationPermissionGranted: Boolean? = null,
    val commitInFlight: Boolean = false,
    val reconstructionRequired: Boolean = false,
    val reconstructionInFlight: Boolean = false,
    val error: String? = null,
) {
    init {
        require(!reconstructionRequired || !commitInFlight)
        require(!reconstructionInFlight || reconstructionRequired)
        require(!vpnConsentPending || onboarding.page == OnboardingPage.VPN)
        require(!vpnConsentDenied || onboarding.page == OnboardingPage.VPN)
    }
}

data class OnboardingCallbacks(
    val onForward: () -> Unit,
    val onBack: () -> Unit = {},
    val onRetry: (() -> Unit)? = null,
    val onNotificationPermissionRequest: (() -> Unit)? = null,
    val onOpenNotificationSettings: (() -> Unit)? = null,
    val onProtectionLevelSelected: (ProtectionLevel) -> Unit = {},
    val onResolverSelected: (MilestoneResolverPlan) -> Unit = {},
    val onResolverRouteSelected: ((useDeviceDns: Boolean, useEncryptedFallback: Boolean) -> Unit)? = null,
)

/**
 * One selectable setup choice rendered as a grouped card: label, selected state,
 * and the consequence summary. The durable selected value stays in the
 * foundation owner's draft; this type is presentation-only.
 */
data class OnboardingChoiceUi(
    val label: String,
    val summary: String,
    val selected: Boolean,
)

/** Presentation-only onboarding surface; Android launchers and durable writes remain host-owned. */
@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    callbacks: OnboardingCallbacks,
    modifier: Modifier = Modifier,
) {
    val page = state.onboarding.page
    val enabled = !state.commitInFlight && !state.reconstructionRequired &&
        !state.vpnConsentPending
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("onboarding_screen")
            .verticalScroll(rememberScrollState())
            .lavaScreenScrollPadding(LocalLavaContentInsets.current),
        verticalArrangement = Arrangement.spacedBy(LavaSpacing.lg),
    ) {
        Text(
            text = stringResource(page.titleResource),
            style = LavaScreenTypography.screenTitle,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(page.bodyResource),
            style = MaterialTheme.typography.bodyLarge,
        )
        when (page) {
            OnboardingPage.VPN -> if (state.vpnConsentDenied && !state.vpnConsentPending) {
                LavaPanel {
                    Text(
                        text = stringResource(R.string.onboarding_vpn_denied_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OnboardingPage.NOTIFICATIONS ->
                if (state.notificationPermissionGranted == false) {
                    NotificationDeniedControls(callbacks, enabled)
                }

            OnboardingPage.PROTECTION_LEVEL -> ProtectionChoices(state, callbacks, enabled)

            OnboardingPage.CONNECTION_QUALITY -> ResolverChoices(state, callbacks, enabled)

            else -> Unit
        }
        state.error?.let { message ->
            LavaPanel {
                Text(message, color = MaterialTheme.colorScheme.error)
            }
        }
        if (state.commitInFlight) Text(stringResource(R.string.onboarding_saving))
        if (state.reconstructionRequired) {
            if (state.reconstructionInFlight) {
                Text(stringResource(R.string.onboarding_retrying))
            }
            callbacks.onRetry?.let { retry ->
                Button(
                    onClick = retry,
                    enabled = !state.reconstructionInFlight,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.onboarding_retry))
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(LavaSpacing.md),
        ) {
            if (state.onboarding.canGoBack) {
                OutlinedButton(
                    onClick = callbacks.onBack,
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.onboarding_back))
                }
            }
            val notificationAction = page == OnboardingPage.NOTIFICATIONS
            val notificationDenied = state.notificationPermissionGranted == false
            Button(
                onClick = when {
                    // After a denial a second dialog request can fail without asking
                    // (Android 11+), so the primary action advances instead and the
                    // system-settings route is offered beside the explanation above.
                    notificationAction && notificationDenied -> callbacks.onForward
                    notificationAction -> callbacks.onNotificationPermissionRequest ?: {}
                    else -> callbacks.onForward
                },
                enabled = enabled && (!notificationAction || notificationDenied ||
                    callbacks.onNotificationPermissionRequest != null),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    stringResource(
                        if (notificationAction && notificationDenied) {
                            R.string.onboarding_continue
                        } else {
                            page.actionResource
                        },
                    ),
                )
            }
        }
    }
}

/**
 * Post-denial notification explanation plus the system-settings route. Lava keeps
 * notifications optional, so the page advances through the primary action while
 * the settings route stays available for the user who wants to re-enable them.
 */
@Composable
private fun NotificationDeniedControls(
    callbacks: OnboardingCallbacks,
    enabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(LavaSpacing.md)) {
        LavaPanel {
            Text(
                text = stringResource(R.string.onboarding_notifications_denied_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val openSettings = callbacks.onOpenNotificationSettings
        if (openSettings != null) {
            OutlinedButton(
                onClick = openSettings,
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.guard_action_open_settings))
            }
        }
    }
}

@Composable
private fun ProtectionChoices(
    state: OnboardingUiState,
    callbacks: OnboardingCallbacks,
    enabled: Boolean,
) {
    val draft = state.onboarding.draft
    OnboardingChoiceGroup(
        choices = ProtectionLevel.entries.map { level ->
            OnboardingChoiceUi(
                label = stringResource(level.displayLabelResource),
                summary = stringResource(level.summaryResource),
                selected = draft.protectionLevel == level,
            )
        },
        enabled = enabled,
        disclosure = stringResource(
            R.string.onboarding_level_default_disclosure,
            stringResource(AppConfiguration.default().protectionLevel.displayLabelResource),
        ),
        onSelected = { index -> callbacks.onProtectionLevelSelected(ProtectionLevel.entries[index]) },
    )
}

@Composable
private fun ResolverChoices(
    state: OnboardingUiState,
    callbacks: OnboardingCallbacks,
    enabled: Boolean,
) {
    val draft = state.onboarding.draft
    // These retained native cards choose a route shape, not a different provider.
    // Fresh setup uses Quad9; legacy Mullvad drafts keep their exact endpoint identity.
    val selected = resolverRoute(draft.resolver)
    val defaults = OnboardingState.initial().draft.resolver
    val defaultPlan = resolverRoute(defaults)
    val providerName = resolverProviderName(draft.resolver)
    OnboardingChoiceGroup(
        choices = MilestoneResolverPlan.entries.map { plan ->
            OnboardingChoiceUi(
                label = stringResource(plan.labelResource),
                summary = stringResource(plan.summaryResource, providerName, providerName),
                selected = selected == plan,
            )
        },
        enabled = enabled,
        disclosure = stringResource(
            R.string.onboarding_resolver_default_disclosure,
            stringResource(defaultPlan.labelResource),
            resolverProviderName(defaults),
        ),
        onSelected = { index ->
            val plan = MilestoneResolverPlan.entries[index]
            val route = callbacks.onResolverRouteSelected
            if (route != null) route(plan.configuration.useDeviceDns, plan.configuration.useEncryptedFallback)
            else callbacks.onResolverSelected(plan)
        },
    )
}

/** The onboarding adapter does not broaden the legacy preset/backup enum. */
private fun resolverRoute(configuration: ResolverConfiguration): MilestoneResolverPlan =
    MilestoneResolverPlan.entries.first { it.configuration.useDeviceDns == configuration.useDeviceDns &&
        it.configuration.useEncryptedFallback == configuration.useEncryptedFallback }

internal fun resolverProviderName(configuration: ResolverConfiguration): String =
    (configuration.savedTiers?.firstOrNull { it.isEnabled && it.entry.transport != CatalogResolverTransport.PLAIN_DNS }
        ?: configuration.savedTiers?.firstOrNull { it.entry.transport != CatalogResolverTransport.PLAIN_DNS })?.displayName
        ?: configuration.copy(useDeviceDns = false, useEncryptedFallback = true, savedTiers = null)
            .toSelection().primary.provider.displayName

/**
 * The grouped setup-choice surface: one selectable card per option with the
 * label, selected state (radio semantics plus a check glyph), and the consequence
 * summary, followed by the current-default disclosure. Replaces the bare
 * FilterChip rows of the pre-slice-02 setup flow.
 */
@Composable
private fun OnboardingChoiceGroup(
    choices: List<OnboardingChoiceUi>,
    enabled: Boolean,
    disclosure: String?,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().testTag("onboarding_choices")) {
        Column(verticalArrangement = Arrangement.spacedBy(LavaSpacing.sm)) {
            choices.forEachIndexed { index, choice ->
                val colors = MaterialTheme.colorScheme
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = choice.selected,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = { onSelected(index) },
                        )
                        .testTag("onboarding_choice_$index"),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(LavaRadii.compact),
                    colors = CardDefaults.cardColors(
                        containerColor = if (choice.selected) {
                            colors.surfaceContainerHigh
                        } else {
                            colors.surfaceContainer
                        },
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (choice.selected) colors.primary else colors.outlineVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(LavaSpacing.lg),
                        verticalArrangement = Arrangement.spacedBy(LavaSpacing.xs),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(LavaSpacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = choice.label,
                                style = LavaScreenTypography.rowTitle,
                                modifier = Modifier.weight(1f),
                            )
                            if (choice.selected) {
                                Icon(
                                    imageVector = LavaIcons.Check,
                                    contentDescription = null,
                                    tint = colors.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        Text(
                            text = choice.summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        disclosure?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = LavaSpacing.sm, start = LavaSpacing.xs),
            )
        }
    }
}

private val ProtectionLevel.displayLabelResource: Int
    get() = when (this) {
        ProtectionLevel.CORE -> R.string.onboarding_level_core
        ProtectionLevel.BALANCED -> R.string.onboarding_level_balanced
        ProtectionLevel.EXTRA -> R.string.onboarding_level_extra
    }

private val ProtectionLevel.summaryResource: Int
    get() = when (this) {
        ProtectionLevel.CORE -> R.string.onboarding_level_core_summary
        ProtectionLevel.BALANCED -> R.string.onboarding_level_balanced_summary
        ProtectionLevel.EXTRA -> R.string.onboarding_level_extra_summary
    }

private val OnboardingPage.titleResource: Int
    get() = when (this) {
        OnboardingPage.LAVA -> R.string.onboarding_lava_title
        OnboardingPage.GUARD_INTRO -> R.string.onboarding_guard_title
        OnboardingPage.FEATURES -> R.string.onboarding_features_title
        OnboardingPage.VPN -> R.string.onboarding_vpn_title
        OnboardingPage.NOTIFICATIONS -> R.string.onboarding_notifications_title
        OnboardingPage.PROTECTION_LEVEL -> R.string.onboarding_level_title
        OnboardingPage.CONNECTION_QUALITY -> R.string.onboarding_connection_title
        OnboardingPage.DONE -> R.string.onboarding_done_title
    }

private val OnboardingPage.bodyResource: Int
    get() = when (this) {
        OnboardingPage.LAVA -> R.string.onboarding_lava_body
        OnboardingPage.GUARD_INTRO -> R.string.onboarding_guard_body
        OnboardingPage.FEATURES -> R.string.onboarding_features_body
        OnboardingPage.VPN -> R.string.onboarding_vpn_body
        OnboardingPage.NOTIFICATIONS -> R.string.onboarding_notifications_body
        OnboardingPage.PROTECTION_LEVEL -> R.string.onboarding_level_body
        OnboardingPage.CONNECTION_QUALITY -> R.string.onboarding_connection_body
        OnboardingPage.DONE -> R.string.onboarding_done_body
    }

private val OnboardingPage.actionResource: Int
    get() = when (this) {
        OnboardingPage.VPN -> R.string.onboarding_allow_vpn
        OnboardingPage.NOTIFICATIONS -> R.string.onboarding_choose_notifications
        OnboardingPage.DONE -> R.string.onboarding_finish
        else -> R.string.onboarding_continue
    }
