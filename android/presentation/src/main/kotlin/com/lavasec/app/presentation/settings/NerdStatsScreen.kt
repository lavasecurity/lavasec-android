package com.lavasec.app.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.lavasec.app.designsystem.LavaIcons
import com.lavasec.app.designsystem.LavaMetrics
import com.lavasec.app.designsystem.LavaNavigationCardMetrics
import com.lavasec.app.designsystem.LavaPanel
import com.lavasec.app.designsystem.LavaScreenTypography
import com.lavasec.app.designsystem.LavaSection
import com.lavasec.app.designsystem.LavaSpacing
import com.lavasec.app.designsystem.LocalLavaContentInsets
import com.lavasec.app.designsystem.lavaScreenScrollPadding
import com.lavasec.app.foundation.diagnostics.OperationalAndroidPathState
import com.lavasec.app.foundation.diagnostics.OperationalAndroidTransport
import com.lavasec.app.foundation.diagnostics.OperationalDiagnosticsSnapshotV1
import com.lavasec.app.foundation.health.ProtectionFallback
import com.lavasec.app.presentation.R

/** The bounded, domain-free facts the Nerd Stats surface may display. */
data class NerdStatsUiState(
    val versionName: String,
    val buildNumber: String,
    val androidPlatformLabel: String,
    val tunnelHealth: TunnelHealthUi?,
) {
    companion object {
        /** Projects an exact persisted sample; `null` yields the honest not-sampled state. */
        fun fromSnapshot(
            snapshot: OperationalDiagnosticsSnapshotV1?,
            versionName: String,
            buildNumber: String,
            androidPlatformLabel: String,
        ): NerdStatsUiState = NerdStatsUiState(
            versionName = versionName,
            buildNumber = buildNumber,
            androidPlatformLabel = androidPlatformLabel,
            tunnelHealth = snapshot?.let {
                TunnelHealthUi(
                    transport = it.androidTransport,
                    pathState = it.androidPathState,
                    connected = it.isConnected,
                    consecutiveFailures = it.consecutiveResolverFailures,
                    consecutiveRejections = it.consecutiveResolverRejections,
                    lastResponseLatencyMillis = it.recentResponseLatenciesMillis.lastOrNull(),
                    activeFallback = it.activeFallback,
                    requiresReconnect = it.requiresReconnect,
                    sampledWallClockMillis = it.sampledWallClockMillis,
                )
            },
        )
    }
}

/** One sampled operational-health observation; every field is a bounded fact. */
data class TunnelHealthUi(
    val transport: OperationalAndroidTransport,
    val pathState: OperationalAndroidPathState,
    val connected: Boolean,
    val consecutiveFailures: Int,
    val consecutiveRejections: Int,
    val lastResponseLatencyMillis: Long?,
    val activeFallback: ProtectionFallback,
    val requiresReconnect: Boolean,
    val sampledWallClockMillis: Long,
)

@Composable
fun NerdStatsScreen(
    state: NerdStatsUiState,
    sampledTimeLabel: (Long) -> String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("nerd_stats_screen")
            .verticalScroll(rememberScrollState())
            .lavaScreenScrollPadding(LocalLavaContentInsets.current),
        verticalArrangement = Arrangement.spacedBy(LavaMetrics.sectionSpacing),
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.testTag("nerd_stats_back"),
        ) {
            Icon(
                imageVector = LavaIcons.Back,
                contentDescription = stringResource(R.string.navigate_back),
            )
        }
        Text(
            text = stringResource(R.string.settings_nerd_stats_title),
            style = LavaScreenTypography.screenTitle,
            modifier = Modifier.semantics { heading() },
        )
        LavaSection(
            title = stringResource(R.string.nerd_stats_app_title),
            contentSpacing = LavaNavigationCardMetrics.interCardSpacing,
        ) {
            LavaPanel {
                Column(verticalArrangement = Arrangement.spacedBy(LavaSpacing.sm)) {
                    StatsRow(
                        label = stringResource(R.string.nerd_stats_version),
                        value = state.versionName,
                    )
                    StatsRow(
                        label = stringResource(R.string.nerd_stats_build),
                        value = state.buildNumber,
                    )
                    StatsRow(
                        label = stringResource(R.string.nerd_stats_platform),
                        value = state.androidPlatformLabel,
                    )
                }
            }
        }
        LavaSection(
            title = stringResource(R.string.nerd_stats_tunnel_title),
            footer = stringResource(R.string.nerd_stats_tunnel_footer),
            contentSpacing = LavaNavigationCardMetrics.interCardSpacing,
        ) {
            val tunnel = state.tunnelHealth
            if (tunnel == null) {
                LavaPanel {
                    Text(
                        text = stringResource(R.string.nerd_stats_not_sampled),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LavaPanel {
                    Column(verticalArrangement = Arrangement.spacedBy(LavaSpacing.sm)) {
                        StatsRow(
                            label = stringResource(R.string.nerd_stats_connected),
                            value = stringResource(
                                if (tunnel.connected) {
                                    R.string.nerd_stats_yes
                                } else {
                                    R.string.nerd_stats_no
                                },
                            ),
                        )
                        StatsRow(
                            label = stringResource(R.string.nerd_stats_network),
                            value = stringResource(tunnel.transport.labelResource),
                        )
                        StatsRow(
                            label = stringResource(R.string.nerd_stats_network_path),
                            value = stringResource(tunnel.pathState.labelResource),
                        )
                        StatsRow(
                            label = stringResource(R.string.nerd_stats_failures),
                            value = tunnel.consecutiveFailures.toString(),
                        )
                        StatsRow(
                            label = stringResource(R.string.nerd_stats_rejections),
                            value = tunnel.consecutiveRejections.toString(),
                        )
                        StatsRow(
                            label = stringResource(R.string.nerd_stats_last_response),
                            value = tunnel.lastResponseLatencyMillis?.let { latency ->
                                stringResource(R.string.nerd_stats_latency_ms, latency.toString())
                            } ?: stringResource(R.string.nerd_stats_none_yet),
                        )
                        StatsRow(
                            label = stringResource(R.string.nerd_stats_active_fallback),
                            value = stringResource(
                                when (tunnel.activeFallback) {
                                    ProtectionFallback.PLAIN_DNS -> R.string.onboarding_resolver_plain
                                ProtectionFallback.NONE -> R.string.nerd_stats_off
                                    ProtectionFallback.DEVICE_DNS ->
                                        R.string.onboarding_resolver_device
                                    ProtectionFallback.ENCRYPTED_DNS ->
                                        R.string.onboarding_resolver_encrypted
                                },
                            ),
                        )
                        StatsRow(
                            label = stringResource(R.string.nerd_stats_reconnect_needed),
                            value = stringResource(
                                if (tunnel.requiresReconnect) {
                                    R.string.nerd_stats_yes
                                } else {
                                    R.string.nerd_stats_no
                                },
                            ),
                        )
                        StatsRow(
                            label = stringResource(R.string.nerd_stats_sampled),
                            value = sampledTimeLabel(tunnel.sampledWallClockMillis),
                        )
                    }
                }
            }
        }
        // The diagnostics privacy disclosure: local-only retention, the single explicit
        // feedback inclusion path, and where the clear action lives.
        LavaPanel(modifier = Modifier.testTag("nerd_stats_privacy_panel")) {
            Column(verticalArrangement = Arrangement.spacedBy(LavaSpacing.sm)) {
                Text(
                    text = stringResource(R.string.nerd_stats_privacy_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.nerd_stats_privacy_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatsRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(LavaSpacing.xs)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("nerd_stats_value_${label}"),
        )
    }
}

private val OperationalAndroidTransport.labelResource: Int
    get() = when (this) {
        OperationalAndroidTransport.UNKNOWN -> R.string.nerd_stats_transport_unknown
        OperationalAndroidTransport.WIFI -> R.string.nerd_stats_transport_wifi
        OperationalAndroidTransport.CELLULAR -> R.string.nerd_stats_transport_cellular
        OperationalAndroidTransport.ETHERNET -> R.string.nerd_stats_transport_wired
        OperationalAndroidTransport.VPN -> R.string.nerd_stats_transport_vpn
        OperationalAndroidTransport.OTHER -> R.string.nerd_stats_transport_other
    }

private val OperationalAndroidPathState.labelResource: Int
    get() = when (this) {
        OperationalAndroidPathState.UNKNOWN -> R.string.nerd_stats_path_unknown
        OperationalAndroidPathState.VALIDATED -> R.string.nerd_stats_path_validated
        OperationalAndroidPathState.CAPTIVE_PORTAL -> R.string.nerd_stats_path_captive_portal
        OperationalAndroidPathState.UNVALIDATED -> R.string.nerd_stats_path_unvalidated
        OperationalAndroidPathState.LOST -> R.string.nerd_stats_path_lost
    }
