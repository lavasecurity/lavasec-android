package com.lavasec.app.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lavasec.app.designsystem.LavaIcons
import com.lavasec.app.designsystem.LavaPanel
import com.lavasec.app.designsystem.LavaScreenTypography
import com.lavasec.app.designsystem.LavaSection
import com.lavasec.app.designsystem.LavaSpacing
import com.lavasec.app.designsystem.LocalLavaContentInsets
import com.lavasec.app.designsystem.lavaScreenScrollPadding
import com.lavasec.app.foundation.resolver.CatalogResolverTransport
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.foundation.resolver.ResolverCatalog
import com.lavasec.app.foundation.resolver.ResolverProvider
import com.lavasec.app.foundation.resolver.ResolverProviderId
import com.lavasec.app.foundation.resolver.ResolverSelection
import com.lavasec.app.foundation.resolver.ResolverTransportCapability
import com.lavasec.app.foundation.resolver.ResolverTransportUnavailableReason
import com.lavasec.app.foundation.resolver.toSelection
import com.lavasec.app.presentation.R
import com.lavasec.app.presentation.resolver.ResolverHealthUi
import com.lavasec.app.presentation.resolver.detailResource
import com.lavasec.app.presentation.resolver.messageResource
import com.lavasec.app.presentation.resolver.servedTransportOrNull
import com.lavasec.app.presentation.resolver.titleResource
import com.lavasec.app.presentation.resolver.settingsProviderIndex
import com.lavasec.app.presentation.resolver.withSettingsProvider
import com.lavasec.app.presentation.resolver.withSettingsDevicePrimary
import com.lavasec.app.presentation.resolver.withSettingsFallback

/** Confirmed resolver rows and live health; absent authority renders the unavailable panel. */
data class SettingsResolverUiState(
    val plan: MilestoneResolverPlan?,
    val health: ResolverHealthUi? = null,
    val inFlight: Boolean,
    val error: String?,
    /** Exact confirmed rows from the existing owner, including disabled rows and endpoints. */
    val selection: ResolverSelection? = null,
)

/**
 * Honest in-flight and failure facts for the resolver-plan switch, derived from the owner
 * state. While a switch is applying every control is disabled; a failed switch names its
 * requested plan so the error copy stays truthful.
 */
sealed interface ResolverPlanSwitchUiState {
    data object Idle : ResolverPlanSwitchUiState

    data class Applying(val plan: MilestoneResolverPlan) : ResolverPlanSwitchUiState

    data class Failed(val plan: MilestoneResolverPlan) : ResolverPlanSwitchUiState
}

/**
 * Renders the confirmed retained selection and forwards exact value-carrying edits. Compatibility
 * milestone callbacks expose only the choices they can represent; production uses [onDnsSelection]
 * and the existing generation-fenced policy/commit owner.
 */
@Composable
fun DnsResolverSettingsScreen(
    state: SettingsResolverUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    switchState: ResolverPlanSwitchUiState = ResolverPlanSwitchUiState.Idle,
    onResolverSelected: ((MilestoneResolverPlan) -> Unit)? = null,
    onDnsSelection: ((ResolverSelection) -> Unit)? = null,
) {
    val plan = state.plan
    val selection = state.selection ?: plan?.toSelection()
    val inFlight = state.inFlight || switchState is ResolverPlanSwitchUiState.Applying
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .lavaScreenScrollPadding(LocalLavaContentInsets.current)
            .testTag("dns_resolver_screen"),
        verticalArrangement = Arrangement.spacedBy(LavaSpacing.lg),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = LavaIcons.Back,
                contentDescription = stringResource(R.string.navigate_back),
            )
        }
        Text(
            text = stringResource(R.string.settings_dns_resolver_title),
            style = LavaScreenTypography.screenTitle,
            modifier = Modifier.semantics { heading() },
        )
        if (selection == null) {
            LavaPanel(modifier = Modifier.testTag("settings_resolver_unavailable_panel")) {
                Text(
                    text = stringResource(R.string.settings_resolver_unavailable),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            return@Column
        }
        LavaPanel {
            Text(
                text = stringResource(R.string.settings_resolver_intro_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.settings_resolver_intro_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DeviceDnsSection(
            selection = selection,
            enabled = (onDnsSelection != null || onResolverSelected != null) && !inFlight,
            onPlanSelected = { requested -> onResolverSelected?.invoke(requested) },
            onDnsSelection = onDnsSelection?.takeUnless { inFlight },
        )
        ProvidersSection(
            selection = selection,
            enabled = (onDnsSelection != null || onResolverSelected != null) && !inFlight,
            onPlanSelected = { requested -> onResolverSelected?.invoke(requested) },
            onDnsSelection = onDnsSelection?.takeUnless { inFlight },
        )
        val editedEntry = selection.tiers.getOrNull(selection.settingsProviderIndex())?.entry
        if (editedEntry != null && editedEntry.providerId != ResolverProviderId.DEVICE_DNS) {
            TransportSection(
                entry = editedEntry,
                selection = selection,
                onDnsSelection = onDnsSelection?.takeUnless { inFlight },
            )
        }
        HealthSection(health = state.health)
        if (inFlight) {
            Text(
                text = stringResource(R.string.settings_resolver_applying),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("settings_resolver_applying"),
            )
        }
        if (switchState is ResolverPlanSwitchUiState.Failed) {
            LavaPanel(modifier = Modifier.testTag("settings_resolver_switch_failed")) {
                Text(
                    text = stringResource(R.string.settings_resolver_switch_failed),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Device DNS group: the primary toggle (with the recommended badge when the
 * policy recommends Device DNS — the milestone default), the explicit
 * unencrypted disclosure, and the fallback control. Turning Device DNS on
 * adopts the default device plan (with encrypted fallback), matching onboarding
 * and the iOS leave-device-DNS-off default of Mullvad DoH in reverse.
 */
@Composable
private fun DeviceDnsSection(
    selection: ResolverSelection,
    enabled: Boolean,
    onPlanSelected: (MilestoneResolverPlan) -> Unit,
    onDnsSelection: ((ResolverSelection) -> Unit)?,
) {
    val devicePrimary = selection.primary.providerId == ResolverProviderId.DEVICE_DNS
    LavaSection(title = stringResource(R.string.onboarding_resolver_device)) {
        LavaPanel(modifier = Modifier.testTag("settings_resolver_device_toggle_panel")) {
            ToggleRow(
                title = stringResource(R.string.settings_resolver_device_toggle),
                detail = stringResource(R.string.onboarding_resolver_device_summary),
                checked = devicePrimary,
                enabled = enabled && (onDnsSelection == null ||
                    selection.withSettingsDevicePrimary(!devicePrimary) != null),
                switchTag = "settings_resolver_device_toggle",
                badge = if (ResolverCatalog.recommendedProvider.id == ResolverProviderId.DEVICE_DNS) {
                    stringResource(R.string.settings_resolver_recommended_badge)
                } else {
                    null
                },
                onCheckedChange = { useDevice ->
                    if (onDnsSelection != null) selection.withSettingsDevicePrimary(useDevice)?.let(onDnsSelection)
                    else onPlanSelected(
                        if (useDevice) {
                            // Turning Device DNS back on keeps the safe default:
                            // Device primary with the Mullvad DoH encrypted fallback.
                            MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK
                        } else {
                            // Leaving Device DNS adopts Mullvad DoH, the app default
                            // encrypted primary (iOS applyUseDeviceDNSSetting parity).
                            MilestoneResolverPlan.MULLVAD_ONLY
                        },
                    )
                },
            )
        }
        Text(
            text = stringResource(R.string.settings_resolver_device_unencrypted_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("settings_resolver_device_unencrypted_note"),
        )
        if (devicePrimary) {
            // Fallback control on the device primary: the exact alternative-DNS
            // toggle. Its detail states the operational behavior verbatim: the
            // fallback is used during recovery and the plan returns to Device
            // DNS when it is healthy again.
            LavaPanel(modifier = Modifier.testTag("settings_resolver_fallback_panel")) {
                ToggleRow(
                    title = stringResource(R.string.settings_resolver_fallback_alternative_toggle),
                    detail = stringResource(R.string.settings_resolver_fallback_detail),
                    checked = selection.fallbackEnabled,
                    enabled = enabled && (onDnsSelection == null ||
                        selection.withSettingsFallback(!selection.fallbackEnabled) != null),
                    switchTag = "settings_resolver_fallback_toggle",
                    onCheckedChange = { fallbackOn ->
                        if (onDnsSelection != null) selection.withSettingsFallback(fallbackOn)?.let(onDnsSelection)
                        else onPlanSelected(
                            if (fallbackOn) {
                                MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK
                            } else {
                                MilestoneResolverPlan.DEVICE_DNS_ONLY
                            },
                        )
                    },
                )
            }
        } else {
            // Typed callers can preserve the ordered selection when enabling a Device fallback.
            // The compatibility-only callback cannot express that direction.
            LavaPanel(modifier = Modifier.testTag("settings_resolver_fallback_panel")) {
                ToggleRow(
                    title = stringResource(if (onDnsSelection != null) R.string.settings_resolver_fallback_alternative_toggle
                        else R.string.settings_resolver_fallback_device_toggle),
                    detail = stringResource(if (onDnsSelection != null) R.string.settings_resolver_fallback_detail
                        else R.string.settings_resolver_fallback_device_unavailable),
                    checked = selection.fallbackEnabled,
                    enabled = enabled && onDnsSelection != null &&
                        selection.withSettingsFallback(!selection.fallbackEnabled) != null,
                    switchTag = "settings_resolver_fallback_toggle",
                    onCheckedChange = { enabled -> selection.withSettingsFallback(enabled)?.let { onDnsSelection?.invoke(it) } },
                )
            }
        }
    }
}

/** Canonical provider choices, plus the exact selected legacy authority when one is retained. */
@Composable
private fun ProvidersSection(
    selection: ResolverSelection,
    enabled: Boolean,
    onPlanSelected: (MilestoneResolverPlan) -> Unit,
    onDnsSelection: ((ResolverSelection) -> Unit)?,
) {
    LavaSection(
        title = stringResource(R.string.settings_resolver_providers_title),
        footer = stringResource(R.string.settings_resolver_providers_footer),
    ) {
        val selectedTier = selection.tiers.getOrNull(selection.settingsProviderIndex())
        val selectedEntry = selectedTier?.entry
        val selectedProviderId = selectedEntry?.providerId
        val providers = ResolverCatalog.providers + listOfNotNull(selectedEntry?.provider)
        providers.distinctBy { it.id }.filter { it.id != ResolverProviderId.DEVICE_DNS }.forEach { provider ->
            val requested = selection.withSettingsProvider(provider)
            ProviderRow(
                provider = provider,
                selected = provider.id == selectedProviderId,
                enabled = enabled && if (onDnsSelection != null) requested != null
                    else provider.id == ResolverProviderId.MULLVAD,
                selectedEntry = selectedEntry?.takeIf { it.providerId == provider.id },
                selectedName = selectedTier?.displayName?.takeIf { provider.id == selectedProviderId },
                onSelected = {
                    if (requested != null && requested != selection) {
                        if (onDnsSelection != null) onDnsSelection(requested)
                        else if (provider.id == ResolverProviderId.MULLVAD) onPlanSelected(MilestoneResolverPlan.MULLVAD_ONLY)
                    }
                },
            )
        }
    }
}

@Composable
private fun ProviderRow(
    provider: ResolverProvider,
    selected: Boolean,
    enabled: Boolean,
    selectedEntry: com.lavasec.app.foundation.resolver.ResolverSelectionEntry?,
    selectedName: String?,
    onSelected: () -> Unit,
) {
    val servedTransport = servedTransportOrNull(provider)
    val available = servedTransport != null || selectedEntry != null
    val capability = if (selectedEntry != null) {
        ResolverCatalog.capability(provider, selectedEntry.transport, selectedEntry.customDohUrl,
            selectedEntry.customDotEndpoint, selectedEntry.customDoqEndpoint, selectedEntry.customPlainEndpoint)
    } else if (provider.id == ResolverProviderId.CUSTOM) {
        ResolverTransportCapability.unavailable(
            ResolverTransportUnavailableReason.CUSTOM_RESOLVER_RUNTIME_ABSENT,
        )
    } else {
        servedTransport?.let { ResolverCatalog.capability(provider, it) }
            ?: ResolverTransportCapability.unavailable(
                ResolverTransportUnavailableReason.RUNTIME_SERVES_ONLY_MULLVAD_DOH,
            )
    }
    val customEndpoint = selectedEntry?.let { it.customDohUrl ?: it.customDotEndpoint ?: it.customDoqEndpoint ?: it.customPlainEndpoint }
    val metadata = when {
        customEndpoint != null -> listOfNotNull(customEndpoint, selectedEntry?.customSecondaryEndpoint).joinToString(", ")
        available -> provider.privacySummary
        provider.id == ResolverProviderId.CUSTOM -> stringResource(
            R.string.settings_resolver_unavailable_reason_custom,
        )
        else -> stringResource(capability.unavailableReason?.messageResource
            ?: R.string.settings_resolver_unavailable_reason_mullvad_doh_only)
    }
    LavaPanel {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = selected,
                    enabled = available && enabled,
                    role = Role.RadioButton,
                    onClick = onSelected,
                )
                .testTag("settings_resolver_provider_${provider.id.rawId}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LavaSpacing.md),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(LavaSpacing.xs),
            ) {
                Text(
                    text = selectedName ?: provider.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = metadata,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (available) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    },
                )
            }
            if (selected) {
                Icon(
                    imageVector = LavaIcons.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(24.dp)
                        .testTag("settings_resolver_provider_${provider.id.rawId}_selected_mark"),
                )
            }
        }
    }
}

/**
 * DNS Transport group for the encrypted primary: the selected provider's
 * transports in the stable iOS menu order, each with its runtime capability.
 * Unavailable transports render disabled with their exact reason (no inert
 * unsupported transport), and the footer carries the pinned explanation that
 * plain IP is unencrypted while DoH/DoT/DoQ are encrypted with different
 * network compatibility.
 */
@Composable
private fun TransportSection(
    entry: com.lavasec.app.foundation.resolver.ResolverSelectionEntry,
    selection: ResolverSelection,
    onDnsSelection: ((ResolverSelection) -> Unit)?,
) {
    LavaSection(
        title = stringResource(R.string.settings_resolver_transport_title),
        footer = stringResource(R.string.settings_resolver_transport_explanation),
    ) {
        entry.provider.supportedTransports.sortedBy { it.ordinal }.forEach { transport ->
            val capability = ResolverCatalog.capability(entry.provider, transport, entry.customDohUrl,
                entry.customDotEndpoint, entry.customDoqEndpoint, entry.customPlainEndpoint)
            val requested = selection.withSettingsProvider(entry.provider, transport)
            TransportRow(
                transport = transport,
                capability = capability,
                selected = transport == entry.transport,
                enabled = onDnsSelection != null && capability.available && requested != null,
                onSelected = { if (requested != null && requested != selection) onDnsSelection?.invoke(requested) },
            )
        }
    }
}

@Composable
private fun TransportRow(
    transport: CatalogResolverTransport,
    capability: ResolverTransportCapability,
    selected: Boolean,
    enabled: Boolean,
    onSelected: () -> Unit,
) {
    LavaPanel {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelected)
                .testTag("settings_resolver_transport_${transport.name.lowercase()}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LavaSpacing.md),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(LavaSpacing.xs),
            ) {
                Text(
                    text = transport.token,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!capability.available) {
                    Text(
                        text = stringResource(
                            capability.unavailableReason?.messageResource
                                ?: R.string.settings_resolver_unavailable_reason_dot,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
            if (selected && capability.available) {
                Icon(
                    imageVector = LavaIcons.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(24.dp)
                        .testTag("settings_resolver_transport_${transport.name.lowercase()}_selected_mark"),
                )
            }
        }
    }
}

/**
 * Network & Resolver Health group: the typed live health status derived from
 * the runtime assessment, or the honest unavailable line when no confirmed
 * assessment exists. The state copy pairs are settled per state (see
 * [com.lavasec.app.presentation.resolver.ResolverHealthUi]); slow messaging
 * never claims a reconnect repairs the resolver.
 */
@Composable
private fun HealthSection(health: ResolverHealthUi?) {
    LavaSection(title = stringResource(R.string.settings_resolver_health_title)) {
        LavaPanel(modifier = Modifier.testTag("settings_resolver_health_panel")) {
            if (health == null) {
                Text(
                    text = stringResource(R.string.guard_status_health_unavailable_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("settings_resolver_health_status"),
                )
            } else {
                Column(
                    modifier = Modifier.testTag("settings_resolver_health_status"),
                    verticalArrangement = Arrangement.spacedBy(LavaSpacing.xs),
                ) {
                    Text(
                        text = stringResource(health.titleResource),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(health.detailResource),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    switchTag: String,
    onCheckedChange: (Boolean) -> Unit,
    badge: String? = null,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = LavaScreenTypography.rowTitle)
                if (badge != null) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("settings_resolver_recommended_badge"),
                    )
                }
            }
            Text(
                detail,
                style = LavaScreenTypography.rowSummary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.testTag(switchTag),
        )
    }
}
