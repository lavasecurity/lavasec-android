package com.lavasec.app.reactnative.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.provider.Settings
import com.lavasec.app.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Presentation only. Settings, confirmations, configuration witnesses and results stay in
 * DeviceQaPageOwner. No configuration, account material or credential buffer is serialized. */
internal class NativeQaPresentation(
    private val context: Context,
    val owner: DeviceQaPageOwner,
    private val admittedHost: () -> Boolean,
    private val navigate: (String) -> Boolean,
    private val closePage: () -> Boolean,
) {
    private val resources get() = context.resources
    private var visit: DeviceQaVisit? = null
    private var previousAuthority: DeviceQaAuthority? = null
    private var visitID: String? = null
    private var layoutReady = false
    private var closed = false
    private var suite: QaSuite? = null
    private var guide: QaScenario? = null
    private var detailID = UUID.randomUUID().toString()
    private var resultID = UUID.randomUUID().toString()
    private var lastResult: DeviceQaResult? = null
    private var resultVisible = false
    private var previewVisible = false
    private var search = ""
    private var suffix = ""
    private var canaryIP = ""
    private var evidence = ""
    private var verified = false
    private var copied: String? = null
    var changed: () -> Unit = {}
    var revision = 0L; private set
    val ready get() = layoutReady && current()

    private fun current(): Boolean = !closed && admittedHost() && visit?.let(owner::isCurrent) == true
    /** A return may restore the selected public check, never its old verification or input. */
    fun refresh() {
        val next = owner.state.value.visit?.takeIf { admittedHost() && owner.isCurrent(it) }
        if (next != visit) {
            if (next != null && previousAuthority != next.authority) { suite = null; guide = null }
            if (next != null) previousAuthority = next.authority
            visit = next; visitID = next?.let { UUID.randomUUID().toString() }; layoutReady = false
            search = ""; suffix = ""; canaryIP = ""; evidence = ""; verified = false; copied = null
            detailID = UUID.randomUUID().toString()
        }
        val state = owner.state.value
        if ((state.showingResult && (!resultVisible || state.result !== lastResult)) ||
            (state.previewLimits != null && !previewVisible)) resultID = UUID.randomUUID().toString()
        resultVisible = state.showingResult; previewVisible = state.previewLimits != null; lastResult = state.result
        revision++; changed()
    }
    fun layout(id: String, width: Double, height: Double): Boolean {
        if (!current() || id != visitID || !width.isFinite() || !height.isFinite() || width <= 0 || height <= 0 || width > 16384 || height > 16384) return false
        if (!layoutReady) { layoutReady = true; revision++; changed() }
        return true
    }
    fun close() { closed = true; visit = null; visitID = null; layoutReady = false; suite = null; guide = null; evidence = ""; suffix = ""; canaryIP = ""; search = ""; copied = null; previousAuthority = null; changed = {} }
    fun hasDetail() = current() && (owner.state.value.showingResult || owner.state.value.previewLimits != null || guide != null || suite != null)
    fun back(): Boolean {
        if (!current()) return false
        val state = owner.state.value
        when {
            state.previewLimits != null -> owner.closePreview()
            state.showingResult -> owner.showResult(false)
            guide != null -> { guide = null; suite = null; detailID = UUID.randomUUID().toString() }
            suite != null -> { suite = null; detailID = UUID.randomUUID().toString() }
            else -> return closePage()
        }
        refresh(); return true
    }
    private fun displayedDetailID(): String? = if (owner.state.value.showingResult || owner.state.value.previewLimits != null) resultID
        else detailID.takeIf { guide != null || suite != null }
    private fun entries(): List<QaEntry> = deviceQaEntries(context, owner, visit, navigate,
        { suite = it; guide = null; detailID = UUID.randomUUID().toString() },
        { guide = it; suite = null; detailID = UUID.randomUUID().toString(); verified = false; evidence = "" })
        .filter { it.id != "lowDataModeConstrained" }

    fun send(raw: String): Boolean {
        // A 4,096-unit evidence field may use six JSON characters per escaped unit.
        if (raw.length > 32768) return false
        val command = runCatching { JSONObject(raw) }.getOrNull() ?: return false
        if (command.keys().asSequence().any { it !in setOf("id", "detail", "action", "value", "row") }) return false
        if (!current() || command.optString("id") != visitID || !layoutReady) return false
        val suppliedDetail = command.optString("detail").takeIf { it.isNotEmpty() }
        if (suppliedDetail != displayedDetailID()) return false
        val action = command.optString("action")
        val state = owner.state.value
        if ((state.busy || state.pending != null) && action !in setOf("close", "detail.close", "detail.back")) return false
        fun string(max: Int): String? = (command.opt("value") as? String)?.takeIf { it.length <= max }
        val visit = visit ?: return false
        when (action) {
            "close", "detail.close", "detail.back" -> return back()
            "search" -> { if (hasDetail()) return false; search = string(200) ?: return false }
            "result" -> { if (state.result == null) return false; owner.showResult(true) }
            "row" -> {
                if (hasDetail()) return false
                val row = command.optString("row")
                if (row.startsWith("copy:")) {
                    val domain = row.removePrefix("copy:").takeIf { it in owner.probeDomains() } ?: return false
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(domain, domain)); copied = domain
                } else (entries().firstOrNull { it.id == row } ?: return false).action()
            }
            "scenario" -> {
                val selected = suite?.scenarios?.firstOrNull { it.id == command.optString("row") && it.condition.available } ?: return false
                guide = selected; suite = null; verified = false; evidence = ""; detailID = UUID.randomUUID().toString()
            }
            "prepare" -> {
                val selected = guide?.takeIf { it.condition.available } ?: return false
                verified = false; evidence = ""; detailID = UUID.randomUUID().toString()
                owner.prepare(DeviceQaAction.Scenario(selected.id, selected.dns?.tiers, selected.load?.load), visit)
            }
            "verified" -> {
                val selected = guide ?: return false
                val value = command.opt("value") as? Boolean ?: return false
                if (value && !owner.preparedConfigurationMatches(selected.id)) return false
                verified = value
            }
            "evidence" -> { if (guide == null) return false; evidence = string(4096) ?: return false }
            "record" -> {
                val selected = guide ?: return false
                val result = when (string(16)) { "Passed" -> "passed"; "Failed" -> "failed"; "Blocked" -> "blocked"; else -> return false }
                if (!owner.record(selected.id, result, verified, evidence)) return false
            }
            "settings" -> {
                if (guide == null || state.showingResult || state.previewLimits != null) return false
                if (!openExternal(context, Intent(Settings.ACTION_WIRELESS_SETTINGS)))
                    owner.perform(DeviceQaAction.Refusal("system-settings-unavailable"), visit)
            }
            "hosted" -> if (!openExternal(context, Intent(Intent.ACTION_VIEW, Uri.parse(HOSTED_QA_URL)))) owner.perform(DeviceQaAction.Refusal("browser-unavailable"), visit)
            "suffix" -> { if (hasDetail()) return false; suffix = string(253) ?: return false }
            "probes.apply" -> { if (hasDetail()) return false; owner.prepareCustomProbes(suffix, visit) }
            "canaryIP" -> { if (hasDetail()) return false; canaryIP = string(64) ?: return false }
            "canary.arm" -> { if (hasDetail()) return false; owner.prepare(DeviceQaAction.ArmCanary(canaryIP), visit) }
            "canary.disarm" -> { if (hasDetail()) return false; owner.prepare(DeviceQaAction.DisarmCanary, visit) }
            else -> return false
        }
        refresh(); return true
    }

    fun snapshot(): JSONObject? {
        if (!current()) return null
        val state = owner.state.value
        val enabled = layoutReady && !state.busy && state.pending == null
        val query = search.trim()
        val entries = entries().filter { query.isEmpty() || listOf(it.id, text(it.section), text(it.title), text(it.summary)).any { value -> value.contains(query, true) } }
        val sections = JSONArray()
        for ((section, rows) in entries.groupBy { it.section }) sections.put(JSONObject().put("title", text(section)).put("rows", JSONArray(rows.map {
            row(it.id, text(it.title), text(it.summary), disabled = !enabled)
        })))
        val domains = owner.probeDomains().filter { query.isEmpty() || it.contains(query, true) || text(R.string.device_qa_probe_domains).contains(query, true) }
        if (domains.isNotEmpty()) sections.put(JSONObject().put("title", text(R.string.device_qa_probe_domains)).put("rows", JSONArray(domains.map {
            row("copy:$it", it, text(if (copied == it) R.string.device_qa_copied else R.string.device_qa_copy_domain), disabled = !enabled)
        })))
        val formsVisible = query.isEmpty() || listOf(
            R.string.device_qa_custom_probe_suffix, R.string.device_qa_apply_custom_qa_probes,
            R.string.device_qa_dns_leak_canary, R.string.device_qa_resolver_ip,
            R.string.device_qa_arm_leak_canary, R.string.device_qa_disarm,
            R.string.device_qa_wireguard_configuration, R.string.device_qa_vpn_chaining,
        ).any { text(it).contains(query, true) }
        val projection = JSONObject().put("id", visitID).put("search", search).put("sections", sections).put("formsVisible", formsVisible)
            .put("hasConfiguration", false).put("staging", state.busy).put("probeSuffix", suffix).put("canaryIP", canaryIP).put("hasResult", state.result != null)
        detail(enabled)?.let { projection.put("detail", it) }
        return JSONObject().put("revision", revision).put("interactive", enabled).put("state", projection).put("canaryStatus", canaryText()).put("labels", JSONObject().put("suffix", text(R.string.device_qa_custom_probe_suffix)).put("canary", text(R.string.device_qa_resolver_ip)))
    }
    private fun detail(enabled: Boolean): JSONObject? {
        val state = owner.state.value
        if (state.previewLimits != null) {
            val limits = state.previewLimits
            val message = listOf(text(R.string.device_qa_rehearse_setup_with_simulated_actions_nothing_is_saved),
                "${text(R.string.device_qa_allowed_domains)}: ${limits.maxAllowedDomains}", "${text(R.string.device_qa_blocked_domains)}: ${limits.maxBlockedDomains}",
                "${text(R.string.device_qa_filter_rules)}: ${limits.maxFilterRules}", "${text(R.string.device_qa_saved_filters)}: ${limits.maxFilters}").joinToString("\n")
            return JSONObject().put("id", resultID).put("kind", "result").put("title", text(R.string.device_qa_preview)).put("message", message)
        }
        if (state.showingResult) return JSONObject().put("id", resultID).put("kind", "result").put("title", text(R.string.device_qa_qa_result)).put("message", state.result.qaText(resources))
        guide?.let { selected ->
            val matches = owner.preparedConfigurationMatches(selected.id)
            return JSONObject().put("id", detailID).put("kind", "check").put("title", text(selected.condition.title))
                .put("steps", JSONArray(selected.condition.steps.map(::text))).put("expected", text(selected.condition.expected))
                .put("prepared", state.preparedScenarioId == selected.id).put("configurationMatches", matches).put("verified", verified && matches)
                .put("evidence", evidence).put("canRecord", enabled && verified && matches && evidence.isNotBlank()).put("canBlock", enabled && evidence.isNotBlank())
                .put("result", reported(state.reportedResults[selected.id]))
        }
        suite?.let { selected ->
            val scenarios = selected.scenarios.filter { it.condition.available }
            return JSONObject().put("id", detailID).put("kind", "suite").put("title", text(selected.title)).put("name", text(selected.title))
                .put("count", scenarios.size.toString()).put("rows", JSONArray(scenarios.map { row(it.id, it.label(resources), state.reportedResults[it.id]?.let(::reported) ?: text(R.string.device_qa_not_run), disabled = !enabled) }))
        }
        return null
    }
    private fun canaryText(): String {
        val value = owner.canarySnapshot()?.value
        if (value?.available != true) return text(R.string.device_qa_canary_unavailable)
        return buildList {
            add(value.arm?.let { resources.getString(R.string.device_qa_canary_armed, it.resolverIP, it.nonce) } ?: text(R.string.device_qa_canary_disarmed))
            value.lastReceipt?.let { receipt ->
                add(resources.getString(R.string.device_qa_canary_udp_accepted, receipt.arm.resolverIP, receipt.acceptedBytes))
                val local = receipt.localAddress?.let { "[$it]:${receipt.localPort}" } ?: text(R.string.device_qa_not_observed)
                add(resources.getString(R.string.device_qa_canary_receipt_attachment, receipt.networkHandle.toString(), receipt.serviceGeneration.toString(), receipt.chainedSessionGeneration.toString(), local))
            }
        }.joinToString("\n")
    }
    private fun reported(value: String?): String {
        if (value == null) return ""
        val label = when (value.substringBefore('\n')) {
            "passed" -> R.string.device_qa_passed_tester_reported
            "failed" -> R.string.device_qa_failed_tester_reported
            else -> R.string.device_qa_blocked_tester_reported
        }
        return text(label) + "\n" + value.substringAfter('\n', "")
    }
    private fun text(id: Int) = resources.getString(id)
    private fun row(id: String, title: String, subtitle: String, disabled: Boolean) = JSONObject().put("id", id).put("title", title).put("subtitle", subtitle).put("disabled", disabled)
}

internal fun DeviceQaResult?.qaText(resources: Resources): String {
    val message = message()
    val subject = when (message) {
        DeviceQaResultMessage.CHECK_UNAVAILABLE -> R.string.device_qa_guided_network_check
        DeviceQaResultMessage.SETTINGS_UNAVAILABLE -> R.string.device_qa_settings
        DeviceQaResultMessage.HOSTED_QA_UNAVAILABLE -> R.string.device_qa_hosted_qa
        DeviceQaResultMessage.DNS_UNAVAILABLE -> R.string.device_qa_dns_setups
        DeviceQaResultMessage.CANARY_UNAVAILABLE -> R.string.device_qa_dns_leak_canary
        else -> null
    }
    if (subject != null) return resources.getString(R.string.device_qa_is_not_available_in_this_build, resources.getString(subject))
    return resources.getString(when (message) {
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
}
