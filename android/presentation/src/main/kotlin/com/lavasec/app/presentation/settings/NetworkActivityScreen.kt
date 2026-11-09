package com.lavasec.app.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lavasec.app.designsystem.LocalLavaSemanticColors
import com.lavasec.app.designsystem.LavaPanel
import com.lavasec.app.designsystem.LavaSpacing
import com.lavasec.app.designsystem.LocalLavaContentInsets
import com.lavasec.app.foundation.activity.NetworkActivityConnectivityState
import com.lavasec.app.foundation.activity.NetworkActivityContract
import com.lavasec.app.foundation.activity.NetworkActivityEvent
import com.lavasec.app.foundation.activity.NetworkActivityFallbackActivationCause
import com.lavasec.app.foundation.activity.NetworkActivityNetworkKind
import com.lavasec.app.foundation.activity.NetworkActivityProbeFailure
import com.lavasec.app.foundation.activity.NetworkActivityReconnectReason
import com.lavasec.app.foundation.activity.NetworkActivityRecoveryReason
import com.lavasec.app.foundation.activity.NetworkActivityResolverIdentity
import com.lavasec.app.foundation.activity.NetworkActivityResolverTransport
import com.lavasec.app.foundation.activity.NetworkActivitySettingsFailureReason
import com.lavasec.app.foundation.activity.NetworkActivityUserAction
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.health.ProtectionFallback
import com.lavasec.app.presentation.R
import com.lavasec.app.presentation.activity.ActivityContentState
import com.lavasec.app.presentation.activity.ActivityDetailHeader
import com.lavasec.app.presentation.activity.ActivityStatePanel
import com.lavasec.app.presentation.activity.NetworkActivityRowUi
import com.lavasec.app.presentation.activity.NetworkActivitySentinelKeyUi
import com.lavasec.app.presentation.activity.NetworkActivitySnapshot
import com.lavasec.app.presentation.activity.formatDomainHistoryTimestamp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

/**
 * Settings → Advanced → Network Activity. iOS parity: DiagnosticsNetworkActivity.swift's
 * NetworkActivityLogView — the privacy panel, the theme-pill + timestamp + event line +
 * state line row, the "No network activity yet" empty row, the 30-row paging, and the
 * scoped clear confirmation. The honest Disabled state replaces the log when the
 * retention toggle is off ("Local network activity off").
 */
@Composable
fun NetworkActivityScreen(
    state: ActivityContentState<NetworkActivitySnapshot>,
    loadingMore: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onRefresh: (() -> Unit)? = null,
    onLoadMore: (() -> Unit)? = null,
    onClear: (() -> Unit)? = null,
) {
    var confirmClear by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val snapshot = when (state) {
        is ActivityContentState.Ready -> state.value
        is ActivityContentState.Loading -> state.lastGood
        is ActivityContentState.Failed -> state.lastGood
        ActivityContentState.Unavailable,
        ActivityContentState.Disabled,
        -> null
    }
    val sentinelKey = snapshot?.nextPageKey?.takeIf { snapshot.rows.isNotEmpty() }
    val sentinelItemKey = sentinelKey?.let(::networkActivitySentinelLazyItemKey)
    var dispatchedForVisibilityCycle by remember(sentinelKey) { mutableStateOf(false) }
    val loadingMoreDescription = stringResource(R.string.network_activity_loading_more)

    LaunchedEffect(listState, sentinelKey, sentinelItemKey, loadingMore, onLoadMore) {
        if (sentinelKey == null || sentinelItemKey == null || onLoadMore == null || loadingMore) {
            return@LaunchedEffect
        }
        val sentinelVisibility = snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.any { it.key == sentinelItemKey }
        }.distinctUntilChanged()
        if (dispatchedForVisibilityCycle) {
            sentinelVisibility.first { visible -> !visible }
            dispatchedForVisibilityCycle = false
        }
        sentinelVisibility.first { visible -> visible }
        dispatchedForVisibilityCycle = true
        onLoadMore()
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().testTag("network_activity_screen"),
        contentPadding = LocalLavaContentInsets.current.scrollContentPadding(),
        verticalArrangement = Arrangement.spacedBy(LavaSpacing.md),
    ) {
        item(key = "network-activity-header") {
            ActivityDetailHeader(stringResource(R.string.settings_network_activity_title), onBack)
        }

        item(key = "network-activity-privacy") {
            NetworkActivityPrivacyPanel()
        }

        if (snapshot == null) {
            item(key = "network-activity-state") {
                when (state) {
                    ActivityContentState.Unavailable -> ActivityStatePanel(
                        title = stringResource(R.string.activity_live_unavailable),
                        detail = stringResource(R.string.activity_live_unavailable_detail),
                        tag = "network_activity_unavailable",
                    )
                    ActivityContentState.Disabled -> NetworkActivityDisabledPanel()
                    is ActivityContentState.Loading -> ActivityStatePanel(
                        title = stringResource(R.string.network_activity_loading),
                        tag = "network_activity_initial_loading",
                        loading = true,
                    )
                    is ActivityContentState.Failed -> ActivityStatePanel(
                        title = stringResource(R.string.network_activity_failed),
                        detail = state.message,
                        tag = "network_activity_failure",
                        onRetry = onRefresh,
                    )
                    is ActivityContentState.Ready -> error("Ready always carries a snapshot")
                }
            }
            return@LazyColumn
        }

        when (state) {
            is ActivityContentState.Loading -> item(key = "network-activity-refreshing") {
                ActivityStatePanel(
                    title = stringResource(R.string.network_activity_refreshing),
                    tag = "network_activity_refreshing",
                    loading = true,
                )
            }
            is ActivityContentState.Failed -> item(key = "network-activity-retained-failure") {
                ActivityStatePanel(
                    title = stringResource(R.string.network_activity_refresh_failed),
                    detail = state.message,
                    tag = "network_activity_retained_failure",
                    onRetry = onRefresh,
                )
            }
            ActivityContentState.Unavailable,
            ActivityContentState.Disabled,
            is ActivityContentState.Ready,
            -> Unit
        }

            if (snapshot.rows.isNotEmpty()) {
            item(key = "network-activity-actions") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(LavaSpacing.sm),
                ) {
                    if (onRefresh != null) {
                        OutlinedButton(
                            onClick = onRefresh,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("network_activity_refresh"),
                        ) {
                            Text(stringResource(R.string.network_activity_refresh))
                        }
                    }
                    if (onClear != null) {
                        OutlinedButton(
                            onClick = { confirmClear = true },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("network_activity_clear"),
                        ) {
                            Text(stringResource(R.string.network_activity_clear_action))
                        }
                    }
                }
            }
            items(
                items = snapshot.rows,
                key = { row -> "network-activity-row:${row.id}" },
            ) { row ->
                NetworkActivityRow(row = row)
            }
        } else {
            item(key = "network-activity-empty") {
                // iOS "No network activity yet" (LavaEmptyListRow in the log view).
                ActivityStatePanel(
                    title = stringResource(R.string.network_activity_empty),
                    tag = "network_activity_empty",
                )
            }
        }

        if (sentinelKey != null && sentinelItemKey != null) {
            item(key = sentinelItemKey) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(LavaSpacing.sm),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .testTag("network_activity_sentinel"),
                    )
                    if (loadingMore) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(28.dp)
                                .semantics {
                                    contentDescription = loadingMoreDescription
                                }
                                .testTag("network_activity_paging_progress"),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }
        }
    }

    if (confirmClear && onClear != null) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.network_activity_clear_title)) },
            text = { Text(stringResource(R.string.network_activity_clear_detail)) },
            confirmButton = {
                Button(
                    onClick = {
                        confirmClear = false
                        onClear()
                    },
                    modifier = Modifier.testTag("network_activity_clear_confirm"),
                ) {
                    Text(stringResource(R.string.network_activity_clear_action))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

@Composable
private fun NetworkActivityPrivacyPanel() {
    // iOS NetworkActivityPrivacyInfoPanel copy (DiagnosticsNetworkActivity.swift):
    // lock heading, local-only explainer, and the 7-day retention line plus the bounded
    // row cap. The iOS "Review Privacy & Data" navigation link has no Android
    // destination, so the Settings privacy section (same screen context) covers that
    // path instead.
    LavaPanel(modifier = Modifier.testTag("network_activity_privacy_panel")) {
        Column(verticalArrangement = Arrangement.spacedBy(LavaSpacing.sm)) {
            Text(
                text = stringResource(R.string.network_activity_stays_local),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.network_activity_privacy_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.network_activity_retention),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    R.string.network_activity_retention_count,
                    NetworkActivityContract.MAX_ROWS.toString(),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("network_activity_retention_count"),
            )
        }
    }
}

@Composable
private fun NetworkActivityDisabledPanel() {
    ActivityStatePanel(
        title = stringResource(R.string.settings_network_activity_off),
        detail = stringResource(R.string.network_activity_disabled_detail),
        tag = "network_activity_disabled",
    )
}

/** One log row: theme pill + timestamp above the event line and the captured Lava state. */
@Composable
private fun NetworkActivityRow(row: NetworkActivityRowUi) {
    LavaPanel {
        Column(verticalArrangement = Arrangement.spacedBy(LavaSpacing.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LavaSpacing.sm),
            ) {
                NetworkActivityThemePill(row.event)
                Text(
                    text = formatDomainHistoryTimestamp(row.timestampMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                text = networkActivityEventLine(row),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = networkActivityStateLine(row),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NetworkActivityThemePill(event: NetworkActivityEvent) {
    val pill = networkActivityThemeFor(event)
    val semantics = LocalLavaSemanticColors.current
    val (label, tint, background) = when (pill) {
        NetworkActivityTheme.NetworkChange -> Triple(
            stringResource(R.string.network_activity_theme_network_change),
            semantics.safe,
            semantics.panelStroke.copy(alpha = 0.18f),
        )
        NetworkActivityTheme.ProtectionLifecycle -> Triple(
            stringResource(R.string.network_activity_theme_protection),
            semantics.safe,
            semantics.panelStroke.copy(alpha = 0.18f),
        )
        NetworkActivityTheme.UserAction -> Triple(
            stringResource(R.string.network_activity_theme_user_action),
            semantics.safe,
            semantics.panelStroke.copy(alpha = 0.18f),
        )
        NetworkActivityTheme.SmokeTestSucceeded -> Triple(
            stringResource(R.string.network_activity_theme_smoke_test),
            semantics.safe,
            semantics.panelStroke.copy(alpha = 0.18f),
        )
        NetworkActivityTheme.SmokeTestFailed -> Triple(
            stringResource(R.string.network_activity_theme_smoke_test),
            semantics.attentionText,
            semantics.attentionBackground,
        )
        NetworkActivityTheme.DeviceDns -> Triple(
            stringResource(R.string.network_activity_theme_device_dns),
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
        )
        NetworkActivityTheme.Reconnect -> Triple(
            stringResource(R.string.network_activity_theme_reconnect),
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
        )
    }
    Row(
        modifier = Modifier
            .background(background, RoundedCornerShape(50))
            .padding(horizontal = LavaSpacing.sm, vertical = LavaSpacing.xs)
            .testTag("network_activity_theme_pill"),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = tint,
        )
    }
}

internal enum class NetworkActivityTheme {
    NetworkChange,
    ProtectionLifecycle,
    UserAction,
    SmokeTestSucceeded,
    SmokeTestFailed,
    DeviceDns,
    Reconnect,
}

/** iOS activityTheme mapping (DiagnosticsNetworkActivity.swift) verbatim. */
internal fun networkActivityThemeFor(event: NetworkActivityEvent): NetworkActivityTheme =
    when (event) {
        is NetworkActivityEvent.NetworkChanged -> NetworkActivityTheme.NetworkChange
        NetworkActivityEvent.ProtectionConnected -> NetworkActivityTheme.ProtectionLifecycle
        is NetworkActivityEvent.UserAction -> NetworkActivityTheme.UserAction
        is NetworkActivityEvent.DnsSmokeProbeSucceeded -> NetworkActivityTheme.SmokeTestSucceeded
        is NetworkActivityEvent.DnsSmokeProbeFailed -> NetworkActivityTheme.SmokeTestFailed
        is NetworkActivityEvent.FallbackActivated,
        NetworkActivityEvent.DeviceDnsFallbackRecovered,
        -> NetworkActivityTheme.DeviceDns

        is NetworkActivityEvent.ReconnectNeeded -> NetworkActivityTheme.Reconnect
        is NetworkActivityEvent.ConnectivityRecovered -> NetworkActivityTheme.ProtectionLifecycle
        is NetworkActivityEvent.NetworkSettingsReapplyFailed -> NetworkActivityTheme.SmokeTestFailed
    }

@Composable
internal fun networkActivityEventLine(row: NetworkActivityRowUi): String {
    val event = row.event
    val suffix = if (row.occurrenceCount > 1) {
        " (×${row.occurrenceCount})"
    } else {
        ""
    }
    val line = when (event) {
        is NetworkActivityEvent.NetworkChanged -> {
            val from = event.from
            val to = networkKindLabel(event.to)
            when {
                from != null && from != event.to && event.pathSatisfied ->
                    stringResource(R.string.network_activity_event_network_changed, networkKindLabel(from), to)

                event.pathSatisfied ->
                    stringResource(R.string.network_activity_event_network_path_available, to)

                else ->
                    stringResource(R.string.network_activity_event_network_path_lost, to)
            }
        }
        NetworkActivityEvent.ProtectionConnected ->
            stringResource(R.string.network_activity_event_connected)

        is NetworkActivityEvent.UserAction -> stringResource(
            when (event.action) {
                NetworkActivityUserAction.TURN_PROTECTION_ON ->
                    R.string.network_activity_user_action_turn_on
                NetworkActivityUserAction.TURN_PROTECTION_OFF ->
                    R.string.network_activity_user_action_turn_off
                NetworkActivityUserAction.RECONNECT_PROTECTION ->
                    R.string.network_activity_user_action_reconnect
                NetworkActivityUserAction.CHANGE_RESOLVER ->
                    R.string.network_activity_user_action_change_resolver
                NetworkActivityUserAction.TOGGLE_DEVICE_DNS_FALLBACK ->
                    R.string.network_activity_user_action_toggle_fallback
                NetworkActivityUserAction.CHANGE_FILTERS ->
                    R.string.network_activity_user_action_change_filters
                NetworkActivityUserAction.CLEAR_ACTIVITY ->
                    R.string.network_activity_user_action_clear_activity
            },
        )

        is NetworkActivityEvent.DnsSmokeProbeSucceeded -> stringResource(
            R.string.network_activity_event_smoke_succeeded,
            resolverLabel(event.resolver),
            transportLabel(event.transport),
        )

        is NetworkActivityEvent.DnsSmokeProbeFailed -> stringResource(
            R.string.network_activity_event_smoke_failed,
            probeFailureLabel(event.reason),
        )

        is NetworkActivityEvent.FallbackActivated -> stringResource(
            when (event.fallback) {
                ProtectionFallback.PLAIN_DNS -> R.string.network_activity_event_plain_fallback_activated
                ProtectionFallback.DEVICE_DNS ->
                    R.string.network_activity_event_device_fallback_activated
                ProtectionFallback.ENCRYPTED_DNS ->
                    R.string.network_activity_event_encrypted_fallback_activated
                ProtectionFallback.NONE ->
                    error("the contract forbids a NONE fallback activation")
            },
            fallbackCauseLabel(event.cause),
        )

        NetworkActivityEvent.DeviceDnsFallbackRecovered ->
            stringResource(R.string.network_activity_event_device_fallback_recovered)

        is NetworkActivityEvent.ReconnectNeeded -> stringResource(
            R.string.network_activity_event_reconnect_needed,
            reconnectReasonLabel(event.reason),
        )

        is NetworkActivityEvent.ConnectivityRecovered -> stringResource(
            R.string.network_activity_event_recovered,
            recoveryReasonLabel(event.reason),
        )

        is NetworkActivityEvent.NetworkSettingsReapplyFailed -> stringResource(
            R.string.network_activity_event_settings_reapply_failed,
            settingsFailureLabel(event.reason),
        )
    }
    return line + suffix
}

@Composable
internal fun networkActivityStateLine(row: NetworkActivityRowUi): String {
    val state = row.capturedState
    val connectivity = when (state.connectivity) {
        NetworkActivityConnectivityState.HEALTHY ->
            stringResource(R.string.network_activity_connectivity_healthy)
        NetworkActivityConnectivityState.PLAIN_DNS_FALLBACK ->
            stringResource(R.string.network_activity_connectivity_plain_fallback)
        NetworkActivityConnectivityState.DEVICE_DNS_FALLBACK ->
            stringResource(R.string.network_activity_connectivity_device_fallback)
        NetworkActivityConnectivityState.ENCRYPTED_FALLBACK ->
            stringResource(R.string.network_activity_connectivity_encrypted_fallback)
        NetworkActivityConnectivityState.RECOVERING ->
            stringResource(R.string.network_activity_connectivity_recovering)
        NetworkActivityConnectivityState.DNS_SLOW ->
            stringResource(R.string.network_activity_connectivity_dns_slow)
        NetworkActivityConnectivityState.NEEDS_RECONNECT ->
            stringResource(R.string.nerd_stats_reconnect_needed)
        NetworkActivityConnectivityState.NETWORK_LOST ->
            stringResource(R.string.network_activity_connectivity_network_lost)
    }
    // iOS deviceDNSFallbackDisplayText: active wins, then configured coverage, then off.
    val fallback = when {
        state.activeFallback == ProtectionFallback.DEVICE_DNS ->
            stringResource(R.string.network_activity_fallback_active)
        state.fallbackCoverage == com.lavasec.app.foundation.health.ResolverFallbackCoverage.DEVICE_DNS ->
            stringResource(R.string.network_activity_fallback_idle)
        else -> stringResource(R.string.network_activity_fallback_off)
    }
    return stringResource(
        R.string.network_activity_state_line,
        connectivity,
        stateResolverLabel(state.resolver),
        fallback,
    )
}

@Composable
private fun stateResolverLabel(resolver: NetworkActivityResolverIdentity): String =
    if (resolver == NetworkActivityResolverIdentity.NONE) {
        // iOS falls back to a generic label when the resolver identity is unavailable.
        stringResource(R.string.network_activity_resolver_unknown)
    } else {
        resolverLabel(resolver)
    }

@Composable
private fun resolverLabel(resolver: NetworkActivityResolverIdentity): String =
    stringResource(
        when (resolver) {
            NetworkActivityResolverIdentity.WIREGUARD_T0 -> R.string.network_activity_resolver_wireguard
            NetworkActivityResolverIdentity.DEVICE_DNS ->
                R.string.network_activity_theme_device_dns
            NetworkActivityResolverIdentity.MULLVAD -> R.string.resolver_mullvad_name
            NetworkActivityResolverIdentity.CLOUDFLARE ->
                R.string.network_activity_resolver_cloudflare
            NetworkActivityResolverIdentity.QUAD9_UNFILTERED, NetworkActivityResolverIdentity.QUAD9_SECURE ->
                R.string.network_activity_resolver_quad9
            NetworkActivityResolverIdentity.HAGEZI_ROOT ->
                R.string.network_activity_resolver_hagezi
            NetworkActivityResolverIdentity.GOOGLE_PUBLIC_DNS ->
                R.string.network_activity_resolver_google
            NetworkActivityResolverIdentity.ENCRYPTED_OTHER ->
                R.string.network_activity_resolver_encrypted_other
            NetworkActivityResolverIdentity.PLAIN_OTHER -> R.string.onboarding_resolver_plain
            NetworkActivityResolverIdentity.NONE ->
                error("NONE resolver identity has no display name")
        },
    )

@Composable
private fun transportLabel(transport: NetworkActivityResolverTransport): String =
    stringResource(
        when (transport) {
            NetworkActivityResolverTransport.WIREGUARD_UDP -> R.string.network_activity_transport_wireguard
            NetworkActivityResolverTransport.DEVICE_UDP ->
                R.string.network_activity_transport_device_udp
            NetworkActivityResolverTransport.DEVICE_TCP ->
                R.string.network_activity_transport_device_tcp
            NetworkActivityResolverTransport.DNS_OVER_HTTPS ->
                R.string.network_activity_transport_doh
            NetworkActivityResolverTransport.DNS_OVER_QUIC -> R.string.network_activity_transport_doq
            NetworkActivityResolverTransport.PLAIN_UDP -> R.string.network_activity_transport_plain_udp
            NetworkActivityResolverTransport.PLAIN_TCP -> R.string.network_activity_transport_plain_tcp
            NetworkActivityResolverTransport.DNS_OVER_TLS -> R.string.network_activity_transport_dot
        },
    )

@Composable
private fun networkKindLabel(kind: NetworkActivityNetworkKind): String = stringResource(
    when (kind) {
        NetworkActivityNetworkKind.UNKNOWN -> R.string.nerd_stats_transport_unknown
        NetworkActivityNetworkKind.WIFI -> R.string.nerd_stats_transport_wifi
        NetworkActivityNetworkKind.CELLULAR -> R.string.nerd_stats_transport_cellular
        NetworkActivityNetworkKind.WIRED -> R.string.nerd_stats_transport_wired
        NetworkActivityNetworkKind.OTHER -> R.string.nerd_stats_transport_other
    },
)

@Composable
private fun probeFailureLabel(failure: NetworkActivityProbeFailure): String = when (failure) {
    is NetworkActivityProbeFailure.ResolverFailure ->
        resolverFailureLabel(failure.reason)
    NetworkActivityProbeFailure.RejectedResponse ->
        stringResource(R.string.network_activity_reason_rejected_response)
}

@Composable
private fun fallbackCauseLabel(cause: NetworkActivityFallbackActivationCause): String =
    when (cause) {
        NetworkActivityFallbackActivationCause.ProducerUnspecified ->
            stringResource(R.string.network_activity_reason_unspecified)
        NetworkActivityFallbackActivationCause.QueryFallback ->
            stringResource(R.string.network_activity_reason_query_fallback)
        NetworkActivityFallbackActivationCause.FallbackCheck ->
            stringResource(R.string.network_activity_reason_fallback_check)
        is NetworkActivityFallbackActivationCause.PrimaryFailure ->
            resolverFailureLabel(cause.reason)
    }

@Composable
private fun reconnectReasonLabel(reason: NetworkActivityReconnectReason): String = when (reason) {
    is NetworkActivityReconnectReason.ResolverFailure -> resolverFailureLabel(reason.reason)
    NetworkActivityReconnectReason.RejectedResponse ->
        stringResource(R.string.network_activity_reason_rejected_response)
    NetworkActivityReconnectReason.DeviceDnsRecapture ->
        stringResource(R.string.network_activity_reason_device_dns_recapture)
    NetworkActivityReconnectReason.RuntimeUnavailable ->
        stringResource(R.string.network_activity_reason_runtime_unavailable)
}

@Composable
private fun recoveryReasonLabel(reason: NetworkActivityRecoveryReason): String = when (reason) {
    NetworkActivityRecoveryReason.PRIMARY_RESOLVER ->
        stringResource(R.string.network_activity_reason_primary_resolver)
    NetworkActivityRecoveryReason.DEVICE_DNS_FALLBACK ->
        stringResource(R.string.network_activity_reason_device_dns_fallback)
    NetworkActivityRecoveryReason.ENCRYPTED_RESOLVER ->
        stringResource(R.string.network_activity_reason_encrypted_resolver)
}

@Composable
private fun settingsFailureLabel(reason: NetworkActivitySettingsFailureReason): String =
    when (reason) {
        NetworkActivitySettingsFailureReason.PLATFORM_REJECTED ->
            stringResource(R.string.network_activity_reason_platform_rejected)
        NetworkActivitySettingsFailureReason.STALE_SESSION ->
            stringResource(R.string.network_activity_reason_stale_session)
        NetworkActivitySettingsFailureReason.RUNTIME_UNAVAILABLE ->
            stringResource(R.string.network_activity_reason_runtime_unavailable)
    }

@Composable
private fun resolverFailureLabel(reason: ResolverFailureReason): String = stringResource(
    when (reason) {
        ResolverFailureReason.TIMEOUT -> R.string.network_activity_failure_timeout
        ResolverFailureReason.PROTECT_FAILED -> R.string.network_activity_failure_protect_failed
        ResolverFailureReason.BIND_FAILED -> R.string.network_activity_failure_bind_failed
        ResolverFailureReason.SOCKET_FAILED -> R.string.network_activity_failure_socket_failed
        ResolverFailureReason.SEND_FAILED -> R.string.network_activity_failure_send_failed
        ResolverFailureReason.RECEIVE_FAILED -> R.string.network_activity_failure_receive_failed
        ResolverFailureReason.MISMATCH_LIMIT -> R.string.network_activity_failure_mismatch_limit
        ResolverFailureReason.INVALID_RESPONSE -> R.string.network_activity_failure_invalid_response
        ResolverFailureReason.OVERSIZED_RESPONSE -> R.string.network_activity_failure_oversized_response
        ResolverFailureReason.HTTP_STATUS -> R.string.network_activity_failure_http_status
        ResolverFailureReason.HTTP_CONTENT_TYPE -> R.string.network_activity_failure_http_content_type
        ResolverFailureReason.TLS_FAILED -> R.string.network_activity_failure_tls_failed
        ResolverFailureReason.BACKED_OFF -> R.string.network_activity_failure_backed_off
        ResolverFailureReason.CANCELLED -> R.string.network_activity_failure_cancelled
        ResolverFailureReason.STALE_EPOCH -> R.string.network_activity_failure_stale_epoch
        ResolverFailureReason.OVERLOADED -> R.string.network_activity_failure_overloaded
    },
)

internal fun networkActivitySentinelLazyItemKey(key: NetworkActivitySentinelKeyUi): String =
    buildString {
        append("network-activity-sentinel|")
        append(key.cutMaxRowId)
        append('|')
        append(key.cutCapturedAtMillis)
        append('|')
        append(key.cutContinuationEpoch)
        append('|')
        append(key.sourceRevision)
        append('|')
        append(key.finalRowId)
    }
