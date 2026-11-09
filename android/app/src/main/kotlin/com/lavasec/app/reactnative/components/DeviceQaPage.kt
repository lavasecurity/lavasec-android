package com.lavasec.app.reactnative.components

import android.app.Activity
import android.content.ContextWrapper
import com.facebook.react.bridge.ReactContext
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lavasec.app.R
import com.lavasec.app.BuildConfig
import com.lavasec.app.reactnative.LavaReactActivity
import com.lavasec.app.foundation.resolver.ResolverTierChoice

/** Frozen AdminQAView/QAInternetScenario families; private previews never enter the product graph. */
@Composable
internal fun DeviceQaPage(owner: DeviceQaPageOwner, navigate: (String) -> Boolean) {
    val state by owner.state.collectAsState()
    val context = LocalContext.current
    // Search and scenario labels must follow configuration changes just like stringResource.
    val resources = LocalResources.current
    var search by remember(state.visit) { mutableStateOf("") }
    var probeSuffix by remember(state.visit) { mutableStateOf("") }
    var canaryResolverIP by remember(state.visit) { mutableStateOf("") }
    val canary = owner.canarySnapshot()?.collectAsState()?.value
    var copiedDomain by remember(state.visit) { mutableStateOf<String?>(null) }
    var suite by remember(state.visit) { mutableStateOf<QaSuite?>(null) }
    var guide by remember(state.visit) { mutableStateOf<QaScenario?>(null) }
    val visit = state.visit
    // Even a queued old Compose callback must carry its rendered visit, not sample a newer one.
    fun admitted(action: () -> Unit) { if (visit != null && owner.isCurrent(visit)) action() }
    fun settings(action: DeviceQaAction) { admitted { owner.prepare(action, visit) } }
    val enabled = visit != null && owner.isCurrent(visit) && !state.busy
    val entries = deviceQaEntries(context, owner, visit, navigate, { suite = it }, { guide = it })
    val filtered = entries.filter { entry ->
        val values = listOf(resources.getString(entry.section), resources.getString(entry.title), resources.getString(entry.summary), entry.id)
        search.isBlank() || values.any { it.contains(search.trim(), ignoreCase = true) }
    }
    val domains = owner.probeDomains().filter { search.isBlank() || it.contains(search.trim(), true) || resources.getString(R.string.device_qa_probe_domains).contains(search.trim(), true) }
    Column(Modifier.fillMaxSize().testTag("device_qa_native").verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        QaHeading(R.string.device_qa_test_lava_on_this_device)
        Text(stringResource(R.string.device_qa_prepare_test_settings_check_app_features_and_run_guided_network_checks))
        OutlinedTextField(value = search, onValueChange = { value -> admitted { search = value } }, label = { Text(stringResource(R.string.device_qa_search_device_qa)) }, modifier = Modifier.fillMaxWidth().testTag("qa_search"), singleLine = true, enabled = enabled)
        TextButton(onClick = { admitted { owner.showResult(true) } }, enabled = enabled && state.result != null, modifier = Modifier.testTag("qa_result")) { Text(stringResource(R.string.device_qa_view_qa_result)) }
        for ((section, rows) in filtered.groupBy { it.section }) {
            QaHeading(section)
            rows.forEach { entry ->
                OutlinedButton(onClick = { admitted(entry.action) }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("qa.${entry.id}")) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(stringResource(entry.title))
                        Text(stringResource(entry.summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        val customVisible = search.isBlank() || listOf(resources.getString(R.string.device_qa_custom_probe_suffix),
            resources.getString(R.string.device_qa_apply_custom_qa_probes)).any { it.contains(search.trim(), true) }
        if (customVisible) {
            QaHeading(R.string.device_qa_custom_probe_suffix)
            Text(stringResource(R.string.device_qa_use_this_for_lan_or_sslip_io_physical_device_checks))
            OutlinedTextField(value = probeSuffix, onValueChange = { value -> admitted { probeSuffix = value } },
                label = { Text(stringResource(R.string.device_qa_custom_probe_suffix)) },
                placeholder = { Text("192-168-1-20.sslip.io") }, singleLine = true, enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("qa_probe_suffix"))
            OutlinedButton(onClick = { admitted { owner.prepareCustomProbes(probeSuffix, visit) } },
                enabled = enabled && probeSuffix.isNotBlank(), modifier = Modifier.testTag("qa_apply_custom_probes")) {
                Text(stringResource(R.string.device_qa_apply_custom_qa_probes))
            }
        }
        val canaryVisible = search.isBlank() || listOf(resources.getString(R.string.device_qa_dns_leak_canary),
            resources.getString(R.string.device_qa_resolver_ip)).any { it.contains(search.trim(), true) }
        if (canaryVisible) {
            QaHeading(R.string.device_qa_dns_leak_canary)
            Text(stringResource(R.string.device_qa_canary_guidance))
            OutlinedTextField(value = canaryResolverIP, onValueChange = { value -> admitted { canaryResolverIP = value } },
                label = { Text(stringResource(R.string.device_qa_resolver_ip)) }, singleLine = true, enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("qa_canary_resolver"))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { settings(DeviceQaAction.ArmCanary(canaryResolverIP)) }, enabled = enabled) {
                    Text(stringResource(R.string.device_qa_arm_leak_canary))
                }
                OutlinedButton(onClick = { settings(DeviceQaAction.DisarmCanary) }, enabled = enabled) {
                    Text(stringResource(R.string.device_qa_disarm))
                }
            }
            if (canary?.available != true) Text(stringResource(R.string.device_qa_canary_unavailable))
            else {
                val arm = canary.arm
                if (arm == null) Text(stringResource(R.string.device_qa_canary_disarmed))
                else Text(stringResource(R.string.device_qa_canary_armed, arm.resolverIP, arm.nonce))
                canary.lastReceipt?.let { receipt ->
                    Text(stringResource(R.string.device_qa_canary_udp_accepted, receipt.arm.resolverIP, receipt.acceptedBytes))
                    val local = receipt.localAddress?.let { "[$it]:${receipt.localPort}" }
                        ?: stringResource(R.string.device_qa_not_observed)
                    Text(stringResource(R.string.device_qa_canary_receipt_attachment, receipt.networkHandle.toString(),
                        receipt.serviceGeneration.toString(), receipt.chainedSessionGeneration.toString(), local))
                }
            }
        }
        if (domains.isNotEmpty()) {
            QaHeading(R.string.device_qa_probe_domains)
            domains.forEach { domain ->
                OutlinedButton(onClick = { admitted {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(domain, domain))
                    copiedDomain = domain
                } }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("qa.copy.$domain")) {
                    Column { Text(domain); Text(stringResource(if (copiedDomain == domain) R.string.device_qa_copied else R.string.device_qa_copy_domain)) }
                }
            }
        }
        if (filtered.isEmpty() && domains.isEmpty() && !customVisible && !canaryVisible) Text(stringResource(R.string.device_qa_no_matching_checks))
    }
    state.pending?.let {
        AlertDialog(onDismissRequest = { admitted { owner.cancelPending() } }, title = { Text(stringResource(R.string.device_qa_apply_test_settings)) },
            text = { Text(stringResource(R.string.device_qa_applying_settings_does_not_verify_protection_or_complete_a_network_check)) },
            confirmButton = { TextButton(onClick = { admitted { owner.confirm() } }, enabled = enabled) { Text(stringResource(R.string.device_qa_apply)) } },
            dismissButton = { TextButton(onClick = { admitted { owner.cancelPending() } }) { Text(stringResource(R.string.device_qa_cancel)) } })
    }
    if (state.showingResult && state.pending == null) {
        val result = state.result
        AlertDialog(onDismissRequest = { admitted { owner.showResult(false) } }, title = { Text(stringResource(R.string.device_qa_qa_result)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                QaResultMessage(result)
                Text(stringResource(R.string.device_qa_applying_settings_does_not_verify_protection_or_complete_a_network_check))
            }
        }, confirmButton = { TextButton(onClick = { admitted { owner.showResult(false) } }) { Text(stringResource(R.string.device_qa_close)) } })
    }
    state.previewLimits?.let { limits ->
        AlertDialog(onDismissRequest = { admitted { owner.closePreview() } }, title = { Text(stringResource(R.string.device_qa_preview)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.device_qa_rehearse_setup_with_simulated_actions_nothing_is_saved))
                Text("${stringResource(R.string.device_qa_allowed_domains)}: ${limits.maxAllowedDomains}")
                Text("${stringResource(R.string.device_qa_blocked_domains)}: ${limits.maxBlockedDomains}")
                Text("${stringResource(R.string.device_qa_filter_rules)}: ${limits.maxFilterRules}")
                Text("${stringResource(R.string.device_qa_saved_filters)}: ${limits.maxFilters}")
            }
        }, confirmButton = { TextButton(onClick = { admitted { owner.closePreview() } }) { Text(stringResource(R.string.device_qa_close)) } })
    }
    suite?.takeIf { guide == null && state.pending == null && !state.showingResult }?.let { selected ->
        AlertDialog(onDismissRequest = { admitted { suite = null } }, title = { Text(stringResource(selected.title)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.device_qa_open_a_check_apply_its_settings_and_record_the_observed_outcome_results_here_are_reported_by_the_tester_and_last_for_this_session))
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(selected.scenarios, key = { it.id }) { scenario ->
                        TextButton(onClick = { admitted { guide = scenario; suite = null } }, enabled = enabled) {
                            Column { Text(scenario.label(resources)); Text(state.reportedResults[scenario.id] ?: stringResource(R.string.device_qa_not_run)) }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { admitted { suite = null } }) { Text(stringResource(R.string.device_qa_close)) } })
    }
    guide?.takeIf { state.pending == null && !state.showingResult }?.let { selected -> QaGuidedCheck(selected, owner, enabled, state.preparedWitness != null,
        { action -> admitted(action) }, { admitted { guide = null } }) }
}

@Composable private fun QaHeading(resource: Int) { Text(stringResource(resource), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }) }
internal fun openExternal(context: Context, intent: Intent): Boolean = runCatching {
    // A second shared RN window must be a new Activity in the originating task.
    // NEW_TASK can select the existing React task root and discard preview extras.
    var owner: Context = context
    var activity: Activity? = null
    while (true) {
        activity = (owner as? Activity) ?: (owner as? ReactContext)?.currentActivity
        if (activity != null) break
        val base = (owner as? ContextWrapper)?.baseContext ?: break
        if (base === owner) break
        owner = base
    }
    if (activity != null) activity.startActivity(intent)
    else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}.isSuccess

@Composable
private fun QaGuidedCheck(scenario: QaScenario, owner: DeviceQaPageOwner, enabled: Boolean, prepared: Boolean, admitted: (() -> Unit) -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    var evidence by remember(scenario.id) { mutableStateOf("") }
    var verified by remember(scenario.id) { mutableStateOf(false) }
    var reported by remember(scenario.id) { mutableStateOf<Int?>(null) }
    val matches = owner.preparedConfigurationMatches(scenario.id)
    AlertDialog(onDismissRequest = close, title = { Text(stringResource(scenario.condition.title)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.device_qa_lava_prepares_probes_and_settings_you_must_create_the_network_condition_and_verify_the_active_tunnel_downloads_and_probes))
            Button(onClick = { admitted {
                if (scenario.condition.available) owner.prepare(DeviceQaAction.Scenario(scenario.id, scenario.dns?.tiers, scenario.load?.load))
                else owner.perform(DeviceQaAction.Refusal("ios-constrained-path-check-unavailable"))
                verified = false; reported = null; evidence = ""
            } }, enabled = enabled) { Text(stringResource(R.string.device_qa_apply_test_settings)) }
            QaHeading(R.string.device_qa_procedure)
            val result by owner.state.collectAsState()
            result.result?.takeIf { it.outcome == DeviceQaOutcome.REFUSED || it.outcome == DeviceQaOutcome.COMMITTED_RECONCILIATION_REQUIRED }?.let {
                QaResultMessage(it)
            }
            scenario.condition.steps.forEachIndexed { index, resource -> Text("${index + 1}. ${stringResource(resource)}") }
            QaHeading(R.string.device_qa_expected_result)
            Text(stringResource(scenario.condition.expected))
            TextButton(onClick = { admitted { if (!openExternal(context, Intent(Settings.ACTION_WIRELESS_SETTINGS))) owner.perform(DeviceQaAction.Refusal("system-settings-unavailable")) } }, enabled = enabled) { Text(stringResource(R.string.device_qa_settings)) }
            TextButton(onClick = { admitted { if (!openExternal(context, Intent(Intent.ACTION_VIEW, Uri.parse(HOSTED_QA_URL)))) owner.perform(DeviceQaAction.Refusal("browser-unavailable")) } }, enabled = enabled) { Text(stringResource(R.string.device_qa_open_hosted_qa_site)) }
            Row { Checkbox(checked = verified && matches, onCheckedChange = { value -> admitted { verified = value } }, enabled = enabled && prepared && matches); Text(stringResource(R.string.device_qa_i_verified_the_setup_and_positive_control)) }
            Text(stringResource(R.string.device_qa_confirm_the_active_tunnel_uses_these_settings_selected_rules_have_finished_loading_and_the_allowed_probe_works_a_timeout_alone_does_not_prove_blocking))
            OutlinedTextField(value = evidence, onValueChange = { value -> admitted { evidence = value } }, label = { Text(stringResource(R.string.device_qa_observed_result_and_evidence)) }, enabled = enabled)
            Row {
                for ((result, label, reportedLabel) in listOf(Triple("passed", R.string.device_qa_passed, R.string.device_qa_passed_tester_reported), Triple("failed", R.string.device_qa_failed, R.string.device_qa_failed_tester_reported), Triple("blocked", R.string.device_qa_blocked, R.string.device_qa_blocked_tester_reported))) {
                    TextButton(onClick = { admitted { if (owner.record(scenario.id, result, verified, evidence)) reported = reportedLabel } }, enabled = enabled && evidence.isNotBlank() && (result == "blocked" || prepared && matches && verified)) { Text(stringResource(label)) }
                }
            }
            reported?.let { Text(stringResource(it)) }
            if (prepared && !matches) Text(stringResource(R.string.device_qa_settings_changed_apply_the_test_settings_again_before_recording_a_result))
        }
    }, confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.device_qa_close)) } })
}

@Composable
private fun QaResultMessage(result: DeviceQaResult?) {
    val message = result.message()
    val unavailableSubject = when (message) {
        DeviceQaResultMessage.CHECK_UNAVAILABLE -> R.string.device_qa_guided_network_check
        DeviceQaResultMessage.SETTINGS_UNAVAILABLE -> R.string.device_qa_settings
        DeviceQaResultMessage.HOSTED_QA_UNAVAILABLE -> R.string.device_qa_hosted_qa
        DeviceQaResultMessage.DNS_UNAVAILABLE -> R.string.device_qa_dns_setups
        DeviceQaResultMessage.CANARY_UNAVAILABLE -> R.string.device_qa_dns_leak_canary
        else -> null
    }
    val text = if (unavailableSubject != null) {
        stringResource(R.string.device_qa_is_not_available_in_this_build, stringResource(unavailableSubject))
    } else stringResource(when (message) {
        DeviceQaResultMessage.NO_RESULT -> R.string.device_qa_no_result_yet
        DeviceQaResultMessage.SAVED -> R.string.device_qa_saved
        DeviceQaResultMessage.RECONCILIATION_REQUIRED -> R.string.device_qa_reset_reconciliation
        DeviceQaResultMessage.DONE -> R.string.device_qa_done
        DeviceQaResultMessage.DISABLED -> R.string.device_qa_disabled
        DeviceQaResultMessage.CANCELLED -> R.string.device_qa_cancel
        DeviceQaResultMessage.SETTINGS_CHANGED -> R.string.device_qa_settings_changed_apply_the_test_settings_again_before_recording_a_result
        DeviceQaResultMessage.NOT_CONFIGURED -> R.string.device_qa_not_configured
        DeviceQaResultMessage.INVALID_DOMAIN -> R.string.device_qa_invalid_domain
        DeviceQaResultMessage.INVALID_ADDRESS -> R.string.device_qa_invalid_address
        else -> R.string.device_qa_filtering_could_not_update
    })
    Text(text)
}

internal data class QaEntry(val id: String, val section: Int, val title: Int, val summary: Int, val action: () -> Unit)
private data class HapticEntry(val id: String, val title: Int, val summary: Int, val semantic: String)
internal data class QaCondition(val id: String, val title: Int, val summary: Int, val steps: List<Int>, val expected: Int, val available: Boolean)
internal data class QaDnsSetup(val id: String, val title: Int, val summary: Int, val tiers: List<ResolverTierChoice>)
internal data class QaLoad(val load: DeviceQaBlocklistLoad, val title: Int, val summary: Int)
internal data class QaScenario(val condition: QaCondition, val dns: QaDnsSetup? = null, val load: QaLoad? = null) {
    val id: String get() = listOfNotNull(condition.id, dns?.id, load?.load?.name).joinToString("__")
    fun label(resources: Resources): String = listOfNotNull(condition.title, dns?.title, load?.title).joinToString(" / ") { resources.getString(it) }
}
internal data class QaSuite(val id: String, val title: Int, val summary: Int, val conditions: Set<String>?, val dns: Set<String>?, val loads: Set<DeviceQaBlocklistLoad>?) {
    val scenarios: List<QaScenario> get() = QA_CONDITIONS.filter { conditions == null || it.id in conditions }.flatMap { condition ->
        QA_DNS_SETUPS.filter { (dns == null || it.id in dns) && (condition.id != "mtuDoQFragmentation" || it.id.startsWith("customDoQ")) }.flatMap { setup ->
            QA_LOADS.filter { loads == null || it.load in loads }.map { QaScenario(condition, setup, it) }
        }
    }
}
internal const val HOSTED_QA_URL = com.lavasec.app.foundation.filter.DeviceQaProbeSet.HOSTED_PAGE_URL
internal val QA_CONDITIONS = listOf(
    QaCondition("cellularHandover", R.string.device_qa_cellular_handover, R.string.device_qa_move_between_towers_or_radio_bands_while_the_tunnel_is_connected, listOf(R.string.device_qa_start_on_lte_or_5g_with_wi_fi_disabled, R.string.device_qa_begin_the_hosted_qa_allow_and_block_probes, R.string.device_qa_move_through_a_tower_or_band_handover_area_for_at_least_60_seconds, R.string.device_qa_repeat_the_probes_after_the_cellular_path_changes), R.string.device_qa_allowed_probes_keep_resolving_and_blocked_probes_stay_blocked_across_the_radio_handover, true),
    QaCondition("wifiToCellularSwitch", R.string.device_qa_wi_fi_to_cellular_switch, R.string.device_qa_leave_wi_fi_coverage_and_confirm_cellular_recovery_without_manual_reconnect, listOf(R.string.device_qa_start_on_a_stable_wi_fi_network_with_protection_connected, R.string.device_qa_open_the_hosted_qa_page_and_run_the_allow_and_block_probes, R.string.device_qa_move_slowly_until_wi_fi_alternates_between_usable_stalled_and_disconnected, R.string.device_qa_run_the_probes_again_once_cellular_data_is_active), R.string.device_qa_the_tunnel_refreshes_network_settings_and_resumes_dns_filtering_on_cellular, true),
    QaCondition("cellularToWifiSwitch", R.string.device_qa_cellular_to_wi_fi_switch, R.string.device_qa_join_wi_fi_while_protected_on_cellular_and_confirm_the_resolver_refreshes, listOf(R.string.device_qa_start_on_cellular_data_with_wi_fi_disabled, R.string.device_qa_run_the_hosted_qa_probes_once, R.string.device_qa_enable_wi_fi_and_join_a_trusted_network, R.string.device_qa_run_the_probes_again_after_the_tunnel_reports_connected), R.string.device_qa_the_tunnel_picks_up_the_wi_fi_path_and_keeps_filtering_without_stale_cellular_resolver_state, true),
    QaCondition("flappingEdgeWifi", R.string.device_qa_flapping_edge_wi_fi, R.string.device_qa_hold_the_device_where_wi_fi_oscillates_between_barely_usable_and_unusable, listOf(R.string.device_qa_stand_at_the_edge_of_wi_fi_range_with_cellular_enabled, R.string.device_qa_keep_protection_connected_and_hosted_probes_visible, R.string.device_qa_move_slowly_until_wi_fi_alternates_between_usable_stalled_and_disconnected, R.string.device_qa_run_probes_after_each_path_change_without_manually_toggling_protection), R.string.device_qa_repeated_wi_fi_path_churn_does_not_strand_dns_on_a_half_dead_local_resolver, true),
    QaCondition("sameSSIDRoaming", R.string.device_qa_same_ssid_ap_roaming, R.string.device_qa_move_across_mesh_or_enterprise_aps_that_share_an_ssid_but_change_bssid_dns, listOf(R.string.device_qa_walk_between_two_access_points_that_share_the_same_ssid, R.string.device_qa_keep_the_device_unlocked_and_protection_connected_during_the_roam, R.string.device_qa_run_hosted_probes_before_and_after_the_bssid_changes, R.string.device_qa_repeat_once_while_the_app_is_backgrounded), R.string.device_qa_the_tunnel_notices_same_ssid_path_changes_and_refreshes_network_settings_after_ap_roam, true),
    QaCondition("wifiInternetBlackhole", R.string.device_qa_wi_fi_internet_blackhole, R.string.device_qa_stay_attached_to_wi_fi_whose_upstream_internet_or_dns_path_is_dead, listOf(R.string.device_qa_join_wi_fi_that_stays_associated_but_has_no_upstream_internet_or_working_dns, R.string.device_qa_keep_cellular_data_available_as_the_escape_path, R.string.device_qa_run_the_hosted_probes_while_wi_fi_still_appears_connected, R.string.device_qa_disable_wi_fi_only_after_observing_whether_the_tunnel_recovers_by_itself), R.string.device_qa_wi_fi_remains_associated_but_lava_avoids_treating_the_dead_upstream_path_as_healthy, true),
    QaCondition("airplaneModeRecovery", R.string.device_qa_airplane_mode_recovery, R.string.device_qa_drop_every_radio_then_restore_service_and_verify_the_tunnel_settles, listOf(R.string.device_qa_enable_airplane_mode_for_10_seconds, R.string.device_qa_disable_airplane_mode_and_wait_for_data_service, R.string.device_qa_confirm_protection_reconnects_or_self_recovers, R.string.device_qa_run_allowed_blocked_exception_and_guardrail_probes), R.string.device_qa_protection_reconnects_or_recovers_after_radios_return_with_no_manual_profile_reset, true),
    QaCondition("elevatorSignalLoss", R.string.device_qa_elevator_signal_loss, R.string.device_qa_simulate_sudden_no_service_loss_followed_by_partial_signal_return, listOf(R.string.device_qa_start_a_protected_browsing_session_before_entering_the_elevator, R.string.device_qa_enter_a_known_signal_loss_elevator_or_shielded_area, R.string.device_qa_wait_for_service_to_drop_or_become_unusable, R.string.device_qa_exit_and_run_hosted_probes_as_soon_as_signal_returns), R.string.device_qa_after_signal_returns_dns_recovers_without_leaving_stale_dns_state_or_a_wedged_resolver, true),
    QaCondition("deprioritizedLowBandwidth", R.string.device_qa_deprioritized_low_bandwidth, R.string.device_qa_exercise_slow_deprioritized_or_congested_cellular_data, listOf(R.string.device_qa_use_a_low_priority_throttled_congested_or_hotspot_deprioritized_cellular_line, R.string.device_qa_start_with_protection_connected_and_hosted_probes_ready, R.string.device_qa_load_allowed_and_blocked_domains_repeatedly_for_2_minutes, R.string.device_qa_watch_for_dns_timeout_recovery_without_toggling_protection), R.string.device_qa_slow_lookups_may_retry_but_the_app_avoids_false_success_and_eventually_resolves_or_reports_failure_cleanly, true),
    QaCondition("lowDataModeConstrained", R.string.device_qa_low_data_mode, R.string.device_qa_run_probes_while_ios_marks_the_active_network_as_constrained, listOf(R.string.device_qa_enable_low_data_mode_for_the_active_wi_fi_or_cellular_path, R.string.device_qa_start_protection_and_confirm_hosted_probes_are_ready, R.string.device_qa_run_allow_and_block_probes_repeatedly_for_2_minutes, R.string.device_qa_disable_low_data_mode_and_repeat_the_probes), R.string.device_qa_constrained_path_timeouts_use_normal_recovery_backoff_and_do_not_require_profile_reinstall, false),
    QaCondition("ipv6OnlyNAT64", R.string.device_qa_ipv6_only_nat64, R.string.device_qa_exercise_ipv6_only_nat64_and_dns64_resolver_bootstrap_behavior, listOf(R.string.device_qa_join_an_ipv6_only_nat64_dns64_network_or_lab_profile, R.string.device_qa_start_protection_and_apply_hosted_probes, R.string.device_qa_run_allowed_blocked_exception_and_guardrail_probes, R.string.device_qa_repeat_with_an_encrypted_resolver_setup_that_requires_bootstrap_addresses), R.string.device_qa_ipv6_only_dns_nat64_synthesis_and_encrypted_resolver_bootstrap_continue_to_resolve_supported_probes, true),
    QaCondition("mtuDoQFragmentation", R.string.device_qa_mtu_doq_fragmentation, R.string.device_qa_stress_encrypted_dns_on_paths_where_large_udp_packets_or_quic_frames_break, listOf(R.string.device_qa_use_a_path_with_reduced_mtu_udp_fragmentation_loss_or_quic_throttling, R.string.device_qa_apply_the_custom_doq_dns_setup_from_device_qa, R.string.device_qa_run_hosted_probes_until_at_least_one_timeout_or_retry_path_is_visible, R.string.device_qa_switch_to_doh_and_confirm_the_same_probes_recover), R.string.device_qa_doq_failures_surface_clearly_and_the_tester_can_confirm_doh_falls_back_cleanly_on_the_same_path, true),
    QaCondition("captivePortalRejoin", R.string.device_qa_captive_portal_rejoin, R.string.device_qa_move_through_a_wi_fi_network_that_requires_sign_in_or_dns_interception, listOf(R.string.device_qa_join_a_wi_fi_network_with_a_sign_in_page_or_dns_interception, R.string.device_qa_complete_the_portal_sign_in_outside_lava_if_required, R.string.device_qa_return_to_lava_and_wait_for_protection_to_settle, R.string.device_qa_run_hosted_probes_to_confirm_no_stale_portal_resolver_remains), R.string.device_qa_after_portal_sign_in_lava_refreshes_resolver_state_and_no_longer_routes_through_the_captive_portal_dns_path, true),
)

internal val QA_DNS_SETUPS = listOf(
    QaDnsSetup("deviceNoEncryptedFallback", R.string.device_qa_device_dns, R.string.device_qa_device_resolver_as_primary_encrypted_fallback_off, listOf(ResolverTierChoice("device-dns", "IP"))),
    QaDnsSetup("deviceEncryptedDoHFallback", R.string.device_qa_device_encrypted_fallback, R.string.device_qa_device_resolver_primary_with_quad9_doh_as_the_wedged_resolver_escape_path, listOf(ResolverTierChoice("device-dns", "IP"), ResolverTierChoice("quad9-unfiltered", "DoH"))),
    QaDnsSetup("plainWithDeviceFallback", R.string.device_qa_plain_dns_device_fallback, R.string.device_qa_google_ip_resolver_primary_with_per_query_device_dns_fallback_on, listOf(ResolverTierChoice("google-public-dns", "IP"), ResolverTierChoice("device-dns", "IP"))),
    QaDnsSetup("plainWithoutDeviceFallback", R.string.device_qa_plain_dns_no_device_fallback, R.string.device_qa_google_ip_resolver_primary_with_device_dns_fallback_off, listOf(ResolverTierChoice("google-public-dns", "IP"))),
    QaDnsSetup("dohWithDeviceFallback", R.string.device_qa_doh_device_fallback, R.string.device_qa_cloudflare_doh_primary_with_per_query_device_dns_fallback_on, listOf(ResolverTierChoice("cloudflare-1111", "DoH"), ResolverTierChoice("device-dns", "IP"))),
    QaDnsSetup("dohWithoutDeviceFallback", R.string.device_qa_doh_no_device_fallback, R.string.device_qa_cloudflare_doh_primary_with_device_dns_fallback_off, listOf(ResolverTierChoice("cloudflare-1111", "DoH"))),
    QaDnsSetup("dotWithoutDeviceFallback", R.string.device_qa_dot_no_device_fallback, R.string.device_qa_cloudflare_dot_primary_with_device_dns_fallback_off_to_expose_hard_failures, listOf(ResolverTierChoice("cloudflare-1111", "DoT"))),
    QaDnsSetup("dotWithDeviceFallback", R.string.device_qa_dot_device_fallback, R.string.device_qa_cloudflare_dot_primary_with_per_query_device_dns_fallback_on, listOf(ResolverTierChoice("cloudflare-1111", "DoT"), ResolverTierChoice("device-dns", "IP"))),
    QaDnsSetup("customDoQWithoutDeviceFallback", R.string.device_qa_custom_doq_no_device_fallback, R.string.device_qa_custom_doq_endpoint_with_device_dns_fallback_off, listOf(ResolverTierChoice("custom-dns", "DoQ", name="QA DoQ", primary="doq://dns.adguard-dns.com:853"))),
    QaDnsSetup("customDoQWithDeviceFallback", R.string.device_qa_custom_doq_device_fallback, R.string.device_qa_custom_doq_endpoint_with_per_query_device_dns_fallback_on, listOf(ResolverTierChoice("custom-dns", "DoQ", name="QA DoQ", primary="doq://dns.adguard-dns.com:853"), ResolverTierChoice("device-dns", "IP"))),
)

internal val QA_LOADS = listOf(
    QaLoad(DeviceQaBlocklistLoad.MINIMAL, R.string.device_qa_minimal, R.string.device_qa_no_optional_blocklists_isolates_resolver_and_path_behavior),
    QaLoad(DeviceQaBlocklistLoad.RECOMMENDED, R.string.device_qa_recommended, R.string.device_qa_fresh_install_defaults_for_ordinary_user_behavior),
    QaLoad(DeviceQaBlocklistLoad.LARGE, R.string.device_qa_large, R.string.device_qa_block_list_basic_default_plus_hagezi_multi_pro_and_oisd_small),
    QaLoad(DeviceQaBlocklistLoad.STRESS, R.string.device_qa_stress, R.string.device_qa_every_curated_source_enabled_for_maximum_filter_prep_and_memory_pressure),
)

internal val QA_SUITES = listOf(
    QaSuite("handoverSmoke", R.string.device_qa_handover_smoke, R.string.device_qa_quick_wi_fi_cellular_transition_pass_using_normal_and_heavy_filters, setOf("wifiToCellularSwitch", "cellularToWifiSwitch"), setOf("deviceNoEncryptedFallback", "dohWithDeviceFallback"), setOf(DeviceQaBlocklistLoad.RECOMMENDED, DeviceQaBlocklistLoad.LARGE)),
    QaSuite("airplaneElevatorRecovery", R.string.device_qa_airplane_elevator_recovery, R.string.device_qa_hard_no_service_recovery_with_and_without_fallback_escape_paths, setOf("airplaneModeRecovery", "elevatorSignalLoss"), setOf("deviceNoEncryptedFallback", "deviceEncryptedDoHFallback", "dotWithoutDeviceFallback"), setOf(DeviceQaBlocklistLoad.RECOMMENDED, DeviceQaBlocklistLoad.STRESS)),
    QaSuite("deprioritizedCellular", R.string.device_qa_deprioritized_cellular, R.string.device_qa_congested_or_throttled_cellular_pass_across_every_encrypted_transport_family, setOf("cellularHandover", "deprioritizedLowBandwidth"), setOf("plainWithDeviceFallback", "dohWithoutDeviceFallback", "dohWithDeviceFallback", "dotWithoutDeviceFallback", "dotWithDeviceFallback", "customDoQWithDeviceFallback"), setOf(DeviceQaBlocklistLoad.LARGE)),
    QaSuite("fullNetworkSweep", R.string.device_qa_full_network_sweep, R.string.device_qa_guided_network_checks_across_compatible_dns_setups_and_blocklist_loads, null, null, null),
)

/** One native action inventory for the legacy body and the shared Android composition. */
internal fun deviceQaEntries(context: Context, owner: DeviceQaPageOwner, visit: DeviceQaVisit?,
    navigate: (String) -> Boolean, openSuite: (QaSuite) -> Unit, openGuide: (QaScenario) -> Unit): List<QaEntry> {
    fun settings(action: DeviceQaAction) { owner.prepare(action, visit) }
    return buildList {
        if (BuildConfig.DEBUG) add(QaEntry("mock-onboarding", R.string.device_qa_onboarding, R.string.device_qa_mock_fresh_onboarding, R.string.device_qa_rehearse_setup_with_simulated_actions_nothing_is_saved) {
            // Rehearse the shipping shared RN tree in an independent host. The
            // DEBUG native visit owns simulated permissions and never saves facts.
            if (!openExternal(context, Intent(context, LavaReactActivity::class.java)
                    .putExtra(LavaReactActivity.ONBOARDING_PREVIEW_EXTRA, true))) {
                owner.perform(DeviceQaAction.Refusal("host-unavailable"), visit)
            }
        })
        QA_SUITES.forEach { value -> add(QaEntry(value.id, R.string.device_qa_internet_qa_suites, value.title, value.summary) { openSuite(value) }) }
        QA_CONDITIONS.forEach { value -> add(QaEntry(value.id, R.string.device_qa_network_conditions, value.title, value.summary) { openGuide(QaScenario(value)) }) }
        QA_DNS_SETUPS.forEach { value -> add(QaEntry(value.id, R.string.device_qa_dns_setups, value.title, value.summary) { settings(DeviceQaAction.Dns(value.tiers)) }) }
        QA_LOADS.forEach { value -> add(QaEntry(value.load.name, R.string.device_qa_blocklist_loads, value.title, value.summary) { settings(DeviceQaAction.Load(value.load)) }) }
        for ((id, title, summary, semantic) in listOf(
            HapticEntry("turn-on-success", R.string.device_qa_turn_on_success, R.string.device_qa_notification_success, "succeeded"),
            HapticEntry("turn-on-failure", R.string.device_qa_turn_on_failure, R.string.device_qa_notification_error, "failed"),
            HapticEntry("turn-off", R.string.device_qa_turn_off, R.string.device_qa_light_impact, "acknowledged"),
            HapticEntry("guardian-tap", R.string.device_qa_guardian_tap, R.string.device_qa_light_impact, "acknowledged"),
        )) add(QaEntry(id, R.string.device_qa_haptics, title, summary) { owner.perform(DeviceQaAction.Haptic(semantic), visit) })
        add(QaEntry("feedback", R.string.device_qa_app_flows, R.string.device_qa_normal_user_feedback, R.string.device_qa_open_the_normal_user_bug_report_sheet) { owner.perform(DeviceQaAction.Feedback, visit) })
        add(QaEntry("paid-preview", R.string.device_qa_plan_limits, R.string.device_qa_test_paid, R.string.device_qa_preview) { owner.preview(true) })
        add(QaEntry("free-preview", R.string.device_qa_plan_limits, R.string.device_qa_test_free, R.string.device_qa_preview) { owner.preview(false) })
        add(QaEntry("history-on", R.string.device_qa_resolver_privacy, R.string.device_qa_enable_local_history, R.string.device_qa_turn_on_local_only_domain_history) { settings(DeviceQaAction.History(true)) })
        add(QaEntry("history-off", R.string.device_qa_resolver_privacy, R.string.device_qa_disable_local_history, R.string.device_qa_turn_off_local_domain_history_and_clear_saved_rows) { settings(DeviceQaAction.History(false)) })
        add(QaEntry("clear-activity", R.string.device_qa_resolver_privacy, R.string.device_qa_clear_local_activity, R.string.device_qa_clear_saved_local_activity_rows_without_changing_the_history_setting) { settings(DeviceQaAction.ClearActivity) })
        add(QaEntry("reset-qa-state", R.string.device_qa_resolver_privacy, R.string.device_qa_clear_qa_state, R.string.device_qa_reset_summary) { settings(DeviceQaAction.Reset) })
        add(QaEntry("hosted-probes", R.string.device_qa_filtering, R.string.device_qa_apply_hosted_qa_probes, R.string.device_qa_install_hosted_probe_rules_for_the_device_qa_page) { settings(DeviceQaAction.Probes(com.lavasec.app.foundation.filter.DeviceQaProbeSet.HOSTED)) })
        add(QaEntry("default-allow", R.string.device_qa_filtering, R.string.device_qa_test_default_allow, R.string.device_qa_prepare_the_default_allow_probe_domain) { settings(DeviceQaAction.Probes(com.lavasec.app.foundation.filter.DeviceQaProbeSet.HOSTED)) })
        add(QaEntry("allow-list", R.string.device_qa_filtering, R.string.device_qa_test_allow_list, R.string.device_qa_prepare_the_allowlist_override_probe) { settings(DeviceQaAction.Probes(com.lavasec.app.foundation.filter.DeviceQaProbeSet.HOSTED)) })
        add(QaEntry("deny-list", R.string.device_qa_filtering, R.string.device_qa_test_deny_list, R.string.device_qa_prepare_the_denylist_probe) { settings(DeviceQaAction.Probes(com.lavasec.app.foundation.filter.DeviceQaProbeSet.HOSTED)) })
        add(QaEntry("threat-guardrail", R.string.device_qa_filtering, R.string.device_qa_test_threat_guardrail, R.string.device_qa_prepare_the_protected_threat_guardrail_probe) { settings(DeviceQaAction.Probes(com.lavasec.app.foundation.filter.DeviceQaProbeSet.HOSTED)) })
        add(QaEntry("clear-probes", R.string.device_qa_filtering, R.string.device_qa_clear_qa_probes, R.string.device_qa_clear_qa_probes) { settings(DeviceQaAction.Probes(null)) })
        // Android VPN consent/profile management belongs to the OS; no pretend profile install.
        add(QaEntry("vpn-settings", R.string.device_qa_vpn_profile, R.string.device_qa_settings, R.string.device_qa_vpn_profile) { if (!openExternal(context, Intent(Settings.ACTION_VPN_SETTINGS))) owner.perform(DeviceQaAction.Refusal("system-settings-unavailable"), visit) })
        add(QaEntry("chained-editor", R.string.device_qa_chained_upstream, R.string.device_qa_wireguard_configuration, R.string.device_qa_chained_upstream) { navigate("VPNChaining") })
        add(QaEntry("hosted-site", R.string.device_qa_hosted_qa, R.string.device_qa_open_hosted_qa_site, R.string.device_qa_run_allowed_blocked_exception_and_threat_guardrail_probes) { if (!openExternal(context, Intent(Intent.ACTION_VIEW, Uri.parse(HOSTED_QA_URL)))) owner.perform(DeviceQaAction.Refusal("browser-unavailable")) })
    }
}
