package com.lavasec.app.reactnative

import android.content.Context
import android.os.Build
import com.lavasec.app.BuildConfig
import com.lavasec.app.MainProductViewModel
import com.lavasec.app.foundation.activity.NetworkActivityCapturedState
import com.lavasec.app.foundation.activity.NetworkActivityConnectivityState
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
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.presentation.R
import com.lavasec.app.presentation.activity.ActivityContentState
import com.lavasec.app.presentation.activity.ActivityDateRangeUi
import com.lavasec.app.presentation.activity.ActivityDecisionUi
import com.lavasec.app.presentation.activity.ActivityUiState
import com.lavasec.app.presentation.activity.DomainHistoryReasonUi
import com.lavasec.app.presentation.activity.DomainHistoryRowUi
import com.lavasec.app.presentation.activity.NetworkActivityRowUi
import com.lavasec.app.presentation.activity.TopDomainRowUi
import com.lavasec.app.product.activity.ActivityDateLens
import com.lavasec.app.product.activity.DomainDecision
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** One typed headless read outcome. A rejection is a stable reason code, never localized copy. */
internal sealed interface QueryOutcome {
    data class Value(val value: Any) : QueryOutcome

    data class Rejected(val reason: String) : QueryOutcome
}

/** Canonical `activity.query` payload: exact numeric counters plus the formatted local uptime. */
internal data class ActivityQueryResult(val allowed: Long, val blocked: Long, val uptime: String)

/** Canonical `domains.query` row (Top Domains or Domain History). */
internal data class DomainQueryRow(
    val id: String,
    val domain: String,
    val metadata: String,
    val icon: String,
    val tone: String,
)

/** Canonical `network.query` row. `theme` is intentionally omitted (no invented pill identity). */
internal data class NetworkQueryRow(
    val id: String,
    val title: String,
    val subtitle: String,
    val metadata: String,
)

/** Canonical `stats.query` payload: the bounded Nerd Stats facts Android actually owns. */
internal data class StatsQueryResult(
    val sampleNotice: String?,
    val app: List<List<String>>,
    val tiers: List<List<String>>,
    val healthSections: List<StatsSection>,
)

internal data class StatsSection(val id: String, val title: String, val rows: List<StatsRow>)

internal data class StatsRow(val id: String, val title: String, val value: String)

/** Resource lookups the query owner needs; injectable so the mapping stays unit-testable. */
internal interface QueryResources {
    fun string(id: Int, vararg args: Any): String

    fun plural(id: Int, count: Int, vararg args: Any): String
}

internal class AndroidQueryResources(private val context: Context) : QueryResources {
    override fun string(id: Int, vararg args: Any): String =
        if (args.isEmpty()) context.getString(id) else context.getString(id, *args)

    override fun plural(id: Int, count: Int, vararg args: Any): String =
        context.resources.getQuantityString(id, count, *args)
}

/**
 * Headless canonical query owner for the shared Activity / Top Domains / Domain History / Network
 * Activity / Nerd Stats screens. It reads the SAME owners the native surfaces use
 * ([MainProductViewModel]'s Activity state machine, live diagnostics sample and resolver selection) so
 * a shared screen never fabricates a row: every value is either confirmed owner evidence or an
 * explicit [QueryOutcome.Rejected] reason.
 *
 * Every read is bounded by [QUERY_TIMEOUT_MILLIS]. The bridge's query lane serializes read lenses,
 * and each read awaits the exact query identity it applied before returning, so an older read can
 * never publish rows for a newer screen (stale-query fence).
 */
internal class ProductQueryOwner(
    private val product: MainProductViewModel,
    private val resources: QueryResources,
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
) {
    suspend fun activity(
        startSeconds: Long?,
        endSeconds: Long?,
        hourly: Boolean,
        expectedGeneration: Long? = null,
    ): QueryOutcome {
        val generation = readGeneration(expectedGeneration) ?: return QueryOutcome.Rejected(REASON_UNAVAILABLE)
        // `hourly` selects the shared chart's bucket resolution; Android has no bucket sampler, so
        // the exact digest counters are returned and the chart renders without fabricated bars.
        val lens = if (startSeconds == null && endSeconds == null) {
            null
        } else {
            dateLens(startSeconds, endSeconds) ?: return QueryOutcome.Rejected(REASON_RANGE_UNAVAILABLE)
        }
        // No requested range keeps the owner's current lens; an explicit range must be applied.
        if (!product.setActivitySummaryQuery(dateLens = lens, decision = null, search = null, expectedGeneration = generation)) {
            return QueryOutcome.Rejected(REASON_UNAVAILABLE)
        }
        val overview = awaitActivity(generation) { state ->
            val value = state.overview.valueOrLastGood() ?: return@awaitActivity null
            if (lens != null && !value.dateRange.matches(lens)) null else value
        } ?: return QueryOutcome.Rejected(REASON_UNAVAILABLE)
        val counts = overview.counts
        return QueryOutcome.Value(
            ActivityQueryResult(
                allowed = counts?.allowed ?: 0L,
                blocked = counts?.blocked ?: 0L,
                uptime = formatUptime(overview.localProtectionUptimeMillis),
            ),
        )
    }

    suspend fun domains(
        history: Boolean,
        decision: DomainQueryDecision,
        search: String,
        startSeconds: Long?,
        endSeconds: Long?,
        limit: Int?,
        expectedGeneration: Long? = null,
    ): QueryOutcome {
        val generation = readGeneration(expectedGeneration) ?: return QueryOutcome.Rejected(REASON_DOMAINS_UNAVAILABLE)
        // The native Activity owner trims once on admission; comparison must use that same identity.
        val effectiveSearch = search.trim()
        val effectiveLimit = (limit ?: DEFAULT_DOMAIN_LIMIT).coerceIn(1, MAX_DOMAIN_LIMIT)
        return if (history) {
            historyRows(decision, effectiveSearch, effectiveLimit, generation)
        } else {
            topDomains(decision, effectiveSearch, startSeconds, endSeconds, effectiveLimit, generation)
        }
    }

    suspend fun network(expectedGeneration: Long? = null): QueryOutcome {
        val generation = readGeneration(expectedGeneration) ?: return QueryOutcome.Rejected(REASON_NETWORK_UNAVAILABLE)
        // There is no query argument for Network Activity; read the owner's current page. The
        // Activity state machine already reloads on revision signals, so this never forces a
        // duplicate network read per poll.
        val snapshot = awaitActivity(generation) { state ->
            state.networkActivity.valueOrLastGood()
        } ?: return QueryOutcome.Rejected(REASON_NETWORK_UNAVAILABLE)
        val rows = snapshot.rows.map(::networkRow)
        return QueryOutcome.Value(rows)
    }

    suspend fun stats(expectedGeneration: Long? = null): QueryOutcome {
        val generation = readGeneration(expectedGeneration) ?: return QueryOutcome.Rejected(REASON_DIAGNOSTICS_UNAVAILABLE)
        val selection = product.state.value.resolverSelection
        val sample = product.sampleNerdStats(generation) ?: return QueryOutcome.Rejected(REASON_DIAGNOSTICS_UNAVAILABLE)
        if (readGeneration(generation) == null || selection != product.state.value.resolverSelection) {
            return QueryOutcome.Rejected(REASON_DIAGNOSTICS_UNAVAILABLE)
        }
        return QueryOutcome.Value(nerdStatsResult(sample, selection, listOf(
            listOf(resources.string(R.string.nerd_stats_version), BuildConfig.VERSION_NAME),
            listOf(resources.string(R.string.nerd_stats_build), BuildConfig.VERSION_CODE.toString()),
            listOf(resources.string(R.string.nerd_stats_platform), resources.string(R.string.nerd_stats_android_platform, Build.VERSION.RELEASE)),
        ), ::formatTimestamp))
    }

    suspend fun reportsSample(expectedGeneration: Long? = null): QueryOutcome {
        val generation = readGeneration(expectedGeneration) ?: return QueryOutcome.Rejected(REASON_DIAGNOSTICS_UNAVAILABLE)
        // A report sample is the owner's own persisted operational record; refresh reloads the
        // latest sample so the shared pull-to-refresh never reports an un-backfilled success.
        val refreshed = runCatching { product.refreshDiagnostics(expectedGeneration = generation) }.getOrDefault(false)
        return if (refreshed && readGeneration(generation) != null) QueryOutcome.Value(JSONObject())
        else QueryOutcome.Rejected(REASON_DIAGNOSTICS_UNAVAILABLE)
    }

    suspend fun enableHistory(): QueryOutcome =
        if (product.enableDomainHistory()) QueryOutcome.Value(true)
        else QueryOutcome.Rejected(REASON_HISTORY_UNAVAILABLE)

    private suspend fun topDomains(
        decision: DomainQueryDecision,
        search: String,
        startSeconds: Long?,
        endSeconds: Long?,
        limit: Int,
        generation: Long,
    ): QueryOutcome {
        // No range means "the owner's current lens"; a supplied range must be resolvable.
        val lens = if (startSeconds == null && endSeconds == null) {
            null
        } else {
            dateLens(startSeconds, endSeconds) ?: return QueryOutcome.Rejected(REASON_RANGE_UNAVAILABLE)
        }
        if (decision == DomainQueryDecision.ALL) {
            // The native Top Domains owner holds one decision lens at a time, so "All" is the
            // merge of both authoritative reads — never a synonym for either single decision.
            val blocked = readTopDomains(DomainDecision.BLOCKED, search, lens, generation)
                ?: return QueryOutcome.Rejected(REASON_DOMAINS_UNAVAILABLE)
            val allowed = readTopDomains(DomainDecision.ALLOWED, search, lens, generation)
                ?: return QueryOutcome.Rejected(REASON_DOMAINS_UNAVAILABLE)
            val combined = blocked.map { it to ActivityDecisionUi.Blocked } +
                allowed.map { it to ActivityDecisionUi.Allowed }
            val merged = combined
                .sortedWith(
                    compareByDescending<Pair<TopDomainRowUi, ActivityDecisionUi>> { it.first.count }
                        .thenBy { it.first.domain },
                )
                .take(limit)
            return QueryOutcome.Value(merged.map { (row, rowDecision) -> topRow(row, rowDecision) })
        }
        val native = decision.native() ?: return QueryOutcome.Rejected(REASON_DOMAINS_UNAVAILABLE)
        val rows = readTopDomains(native, search, lens, generation)
            ?: return QueryOutcome.Rejected(REASON_DOMAINS_UNAVAILABLE)
        return QueryOutcome.Value(rows.take(limit).map { topRow(it, native.toUi()) })
    }

    private suspend fun readTopDomains(
        decision: DomainDecision,
        search: String,
        lens: ActivityDateLens?,
        generation: Long,
    ): List<TopDomainRowUi>? {
        if (!product.setActivitySummaryQuery(dateLens = lens, decision = decision, search = search, expectedGeneration = generation)) {
            return null
        }
        return awaitActivity(generation) { state ->
            val snapshot = state.topDomains.valueOrLastGood() ?: return@awaitActivity null
            if (snapshot.decision != decision.toUi()) return@awaitActivity null
            if (snapshot.search != search) return@awaitActivity null
            if (lens != null && !snapshot.dateRange.matches(lens)) return@awaitActivity null
            snapshot.rows
        }
    }

    private suspend fun historyRows(
        decision: DomainQueryDecision,
        search: String,
        limit: Int,
        generation: Long,
    ): QueryOutcome {
        if (decision == DomainQueryDecision.ALL) {
            val blocked = readHistory(DomainDecision.BLOCKED, search, limit, generation)
                ?: return QueryOutcome.Rejected(REASON_HISTORY_UNAVAILABLE)
            val allowed = readHistory(DomainDecision.ALLOWED, search, limit, generation)
                ?: return QueryOutcome.Rejected(REASON_HISTORY_UNAVAILABLE)
            val merged = (blocked + allowed)
                .sortedWith(compareByDescending<DomainHistoryRowUi> { it.timestampMillis }.thenBy { it.id })
                .take(limit)
            return QueryOutcome.Value(merged.map { historyRow(it) })
        }
        val native = decision.native() ?: return QueryOutcome.Rejected(REASON_HISTORY_UNAVAILABLE)
        val rows = readHistory(native, search, limit, generation)
            ?: return QueryOutcome.Rejected(REASON_HISTORY_UNAVAILABLE)
        return QueryOutcome.Value(rows.take(limit).map { historyRow(it) })
    }

    /**
     * Reads exactly the requested decision's first page. The shared screen grows its `limit` for
     * paging; a native page is [com.lavasec.app.product.LOCAL_LOG_PAGE_SIZE] wide, so a larger
     * `limit` is served by the owner's own load-more path, never by a synthetic concatenation.
     */
    private suspend fun readHistory(
        decision: DomainDecision,
        search: String,
        limit: Int,
        generation: Long,
    ): List<DomainHistoryRowUi>? {
        if (!product.setActivityHistoryQuery(decision, search, expectedGeneration = generation)) return null
        val first = awaitActivity(generation) { state ->
            val snapshot = state.domainHistory.valueOrLastGood() ?: return@awaitActivity null
            if (snapshot.decision != decision.toUi() || snapshot.search != search) return@awaitActivity null
            snapshot
        } ?: return null
        val rows = ArrayList<DomainHistoryRowUi>(first.rows)
        var snapshot = first
        while (rows.size < limit && snapshot.nextPageKey != null) {
            product.loadMoreHistory(expectedGeneration = generation)
            val next = awaitActivity(generation) { state ->
                val value = state.domainHistory.valueOrLastGood() ?: return@awaitActivity null
                if (value.decision != decision.toUi() || value.search != search) return@awaitActivity null
                value.takeIf { it.rows.size > snapshot.rows.size }
            } ?: break
            snapshot = next
            rows.clear()
            rows.addAll(next.rows)
        }
        return rows.takeIf { readGeneration(generation) != null }
    }

    private fun topRow(row: TopDomainRowUi, decision: ActivityDecisionUi): DomainQueryRow {
        val formatted = NumberFormat.getIntegerInstance().format(row.count)
        val metadata = resources.plural(
            R.plurals.activity_domain_times,
            row.count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            formatted,
        )
        return DomainQueryRow(
            id = "${decision.name.lowercase(Locale.ROOT)}:${row.domain}",
            domain = row.domain,
            metadata = metadata,
            icon = decision.outcomeIcon(),
            tone = decision.outcomeTone(),
        )
    }

    private fun historyRow(row: DomainHistoryRowUi): DomainQueryRow {
        val detail = resources.string(
            R.string.activity_history_row_detail,
            resources.string(row.reason.labelResource()),
            formatTimestamp(row.timestampMillis),
        )
        return DomainQueryRow(
            id = "history:${row.id}",
            domain = row.domain,
            metadata = detail,
            icon = row.decision.outcomeIcon(),
            tone = row.decision.outcomeTone(),
        )
    }

    private fun networkRow(row: NetworkActivityRowUi): NetworkQueryRow = NetworkQueryRow(
        id = row.id.toString(),
        title = eventLine(row),
        subtitle = stateLine(row),
        metadata = formatTimestamp(row.timestampMillis),
    )

    // ---- Network Activity display mapping (Android localized copy, no domain names) ----

    private fun eventLine(row: NetworkActivityRowUi): String {
        val suffix = if (row.occurrenceCount > 1) " (×${row.occurrenceCount})" else ""
        val event = row.event
        val line = when (event) {
            is NetworkActivityEvent.NetworkChanged -> {
                val from = event.from
                val to = resources.string(event.to.labelResource())
                when {
                    from != null && from != event.to && event.pathSatisfied ->
                        resources.string(R.string.network_activity_event_network_changed, resources.string(from.labelResource()), to)

                    event.pathSatisfied ->
                        resources.string(R.string.network_activity_event_network_path_available, to)

                    else ->
                        resources.string(R.string.network_activity_event_network_path_lost, to)
                }
            }

            NetworkActivityEvent.ProtectionConnected ->
                resources.string(R.string.network_activity_event_connected)

            is NetworkActivityEvent.UserAction -> resources.string(
                when (event.action) {
                    NetworkActivityUserAction.TURN_PROTECTION_ON -> R.string.network_activity_user_action_turn_on
                    NetworkActivityUserAction.TURN_PROTECTION_OFF -> R.string.network_activity_user_action_turn_off
                    NetworkActivityUserAction.RECONNECT_PROTECTION -> R.string.network_activity_user_action_reconnect
                    NetworkActivityUserAction.CHANGE_RESOLVER -> R.string.network_activity_user_action_change_resolver
                    NetworkActivityUserAction.TOGGLE_DEVICE_DNS_FALLBACK -> R.string.network_activity_user_action_toggle_fallback
                    NetworkActivityUserAction.CHANGE_FILTERS -> R.string.network_activity_user_action_change_filters
                    NetworkActivityUserAction.CLEAR_ACTIVITY -> R.string.network_activity_user_action_clear_activity
                },
            )

            is NetworkActivityEvent.DnsSmokeProbeSucceeded -> resources.string(
                R.string.network_activity_event_smoke_succeeded,
                resolverLabel(event.resolver),
                transportLabel(event.transport),
            )

            is NetworkActivityEvent.DnsSmokeProbeFailed -> resources.string(
                R.string.network_activity_event_smoke_failed,
                probeFailureLabel(event.reason),
            )

            is NetworkActivityEvent.FallbackActivated -> resources.string(
                when (event.fallback) {
                    ProtectionFallback.PLAIN_DNS -> R.string.network_activity_event_plain_fallback_activated
                    ProtectionFallback.DEVICE_DNS -> R.string.network_activity_event_device_fallback_activated
                    ProtectionFallback.ENCRYPTED_DNS -> R.string.network_activity_event_encrypted_fallback_activated
                    ProtectionFallback.NONE -> error("the contract forbids a NONE fallback activation")
                },
                fallbackCauseLabel(event.cause),
            )

            NetworkActivityEvent.DeviceDnsFallbackRecovered ->
                resources.string(R.string.network_activity_event_device_fallback_recovered)

            is NetworkActivityEvent.ReconnectNeeded -> resources.string(
                R.string.network_activity_event_reconnect_needed,
                reconnectReasonLabel(event.reason),
            )

            is NetworkActivityEvent.ConnectivityRecovered -> resources.string(
                R.string.network_activity_event_recovered,
                recoveryReasonLabel(event.reason),
            )

            is NetworkActivityEvent.NetworkSettingsReapplyFailed -> resources.string(
                R.string.network_activity_event_settings_reapply_failed,
                settingsFailureLabel(event.reason),
            )
        }
        return line + suffix
    }

    private fun stateLine(row: NetworkActivityRowUi): String {
        val state = row.capturedState
        val connectivity = resources.string(
            when (state.connectivity) {
                NetworkActivityConnectivityState.HEALTHY -> R.string.network_activity_connectivity_healthy
                NetworkActivityConnectivityState.PLAIN_DNS_FALLBACK -> R.string.network_activity_connectivity_plain_fallback
                NetworkActivityConnectivityState.DEVICE_DNS_FALLBACK -> R.string.network_activity_connectivity_device_fallback
                NetworkActivityConnectivityState.ENCRYPTED_FALLBACK -> R.string.network_activity_connectivity_encrypted_fallback
                NetworkActivityConnectivityState.RECOVERING -> R.string.network_activity_connectivity_recovering
                NetworkActivityConnectivityState.DNS_SLOW -> R.string.network_activity_connectivity_dns_slow
                NetworkActivityConnectivityState.NEEDS_RECONNECT -> R.string.nerd_stats_reconnect_needed
                NetworkActivityConnectivityState.NETWORK_LOST -> R.string.network_activity_connectivity_network_lost
            },
        )
        val fallback = fallbackStateLabel(state)
        return resources.string(
            R.string.network_activity_state_line,
            connectivity,
            stateResolverLabel(state.resolver),
            fallback,
        )
    }

    private fun fallbackStateLabel(state: NetworkActivityCapturedState): String = resources.string(
        when {
            state.activeFallback == ProtectionFallback.DEVICE_DNS -> R.string.network_activity_fallback_active
            state.fallbackCoverage == ResolverFallbackCoverage.DEVICE_DNS -> R.string.network_activity_fallback_idle
            else -> R.string.network_activity_fallback_off
        },
    )

    private fun stateResolverLabel(resolver: NetworkActivityResolverIdentity): String =
        if (resolver == NetworkActivityResolverIdentity.NONE) {
            resources.string(R.string.network_activity_resolver_unknown)
        } else {
            resolverLabel(resolver)
        }

    private fun resolverLabel(resolver: NetworkActivityResolverIdentity): String = resources.string(
        when (resolver) {
            NetworkActivityResolverIdentity.WIREGUARD_T0 -> R.string.network_activity_resolver_wireguard
            NetworkActivityResolverIdentity.DEVICE_DNS -> R.string.network_activity_theme_device_dns
            NetworkActivityResolverIdentity.MULLVAD -> R.string.resolver_mullvad_name
            NetworkActivityResolverIdentity.CLOUDFLARE -> R.string.network_activity_resolver_cloudflare
            NetworkActivityResolverIdentity.QUAD9_UNFILTERED, NetworkActivityResolverIdentity.QUAD9_SECURE -> R.string.network_activity_resolver_quad9
            NetworkActivityResolverIdentity.HAGEZI_ROOT -> R.string.network_activity_resolver_hagezi
            NetworkActivityResolverIdentity.GOOGLE_PUBLIC_DNS -> R.string.network_activity_resolver_google
            NetworkActivityResolverIdentity.ENCRYPTED_OTHER -> R.string.network_activity_resolver_encrypted_other
            NetworkActivityResolverIdentity.PLAIN_OTHER -> R.string.onboarding_resolver_plain
            NetworkActivityResolverIdentity.NONE -> error("NONE resolver identity has no display name")
        },
    )

    private fun transportLabel(transport: NetworkActivityResolverTransport): String = resources.string(
        when (transport) {
            NetworkActivityResolverTransport.WIREGUARD_UDP -> R.string.network_activity_transport_wireguard
            NetworkActivityResolverTransport.DEVICE_UDP -> R.string.network_activity_transport_device_udp
            NetworkActivityResolverTransport.DEVICE_TCP -> R.string.network_activity_transport_device_tcp
            NetworkActivityResolverTransport.DNS_OVER_HTTPS -> R.string.network_activity_transport_doh
            NetworkActivityResolverTransport.DNS_OVER_QUIC -> R.string.network_activity_transport_doq
            NetworkActivityResolverTransport.PLAIN_UDP -> R.string.network_activity_transport_plain_udp
            NetworkActivityResolverTransport.PLAIN_TCP -> R.string.network_activity_transport_plain_tcp
            NetworkActivityResolverTransport.DNS_OVER_TLS -> R.string.network_activity_transport_dot
        },
    )

    private fun probeFailureLabel(failure: NetworkActivityProbeFailure): String = when (failure) {
        is NetworkActivityProbeFailure.ResolverFailure -> resolverFailureLabel(failure.reason)
        NetworkActivityProbeFailure.RejectedResponse -> resources.string(R.string.network_activity_reason_rejected_response)
    }

    private fun fallbackCauseLabel(cause: NetworkActivityFallbackActivationCause): String = when (cause) {
        NetworkActivityFallbackActivationCause.ProducerUnspecified -> resources.string(R.string.network_activity_reason_unspecified)
        NetworkActivityFallbackActivationCause.QueryFallback -> resources.string(R.string.network_activity_reason_query_fallback)
        NetworkActivityFallbackActivationCause.FallbackCheck -> resources.string(R.string.network_activity_reason_fallback_check)
        is NetworkActivityFallbackActivationCause.PrimaryFailure -> resolverFailureLabel(cause.reason)
    }

    private fun reconnectReasonLabel(reason: NetworkActivityReconnectReason): String = when (reason) {
        is NetworkActivityReconnectReason.ResolverFailure -> resolverFailureLabel(reason.reason)
        NetworkActivityReconnectReason.RejectedResponse -> resources.string(R.string.network_activity_reason_rejected_response)
        NetworkActivityReconnectReason.DeviceDnsRecapture -> resources.string(R.string.network_activity_reason_device_dns_recapture)
        NetworkActivityReconnectReason.RuntimeUnavailable -> resources.string(R.string.network_activity_reason_runtime_unavailable)
    }

    private fun recoveryReasonLabel(reason: NetworkActivityRecoveryReason): String = resources.string(
        when (reason) {
            NetworkActivityRecoveryReason.PRIMARY_RESOLVER -> R.string.network_activity_reason_primary_resolver
            NetworkActivityRecoveryReason.DEVICE_DNS_FALLBACK -> R.string.network_activity_reason_device_dns_fallback
            NetworkActivityRecoveryReason.ENCRYPTED_RESOLVER -> R.string.network_activity_reason_encrypted_resolver
        },
    )

    private fun settingsFailureLabel(reason: NetworkActivitySettingsFailureReason): String = resources.string(
        when (reason) {
            NetworkActivitySettingsFailureReason.PLATFORM_REJECTED -> R.string.network_activity_reason_platform_rejected
            NetworkActivitySettingsFailureReason.STALE_SESSION -> R.string.network_activity_reason_stale_session
            NetworkActivitySettingsFailureReason.RUNTIME_UNAVAILABLE -> R.string.network_activity_reason_runtime_unavailable
        },
    )

    private fun resolverFailureLabel(reason: ResolverFailureReason): String = resources.string(
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

    // ---- Shared helpers ----

    private fun readGeneration(expectedGeneration: Long?): Long? =
        product.state.value.graphGeneration?.takeIf { expectedGeneration == null || it == expectedGeneration }

    private suspend fun <T> awaitActivity(generation: Long, predicate: (ActivityUiState) -> T?): T? =
        withTimeoutOrNull(QUERY_TIMEOUT_MILLIS) {
            // A replacement graph must neither satisfy this read nor hold the serialized read lane
            // for the timeout. Return unavailable as soon as the captured owner is retired.
            val accepted = product.state.map { state ->
                state.graphGeneration to state.activity.takeIf { state.graphGeneration == generation }?.let(predicate)
            }.first { (currentGeneration, value) -> currentGeneration != generation || value != null }
            accepted.second.takeIf { accepted.first == generation }
        }

    private fun dateLens(startSeconds: Long?, endSeconds: Long?): ActivityDateLens? =
        activityDateLens(zoneId(), startSeconds, endSeconds)

    private fun formatTimestamp(timestampMillis: Long): String {
        val local = Instant.ofEpochMilli(timestampMillis).atZone(zoneId())
        val day = local.format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))
        val time = local.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()))
        return "$day · $time"
    }

    private fun formatUptime(millis: Long?): String {
        if (millis == null || millis <= 0L) return "0m"
        val totalMinutes = millis / 60_000L
        val days = totalMinutes / (60L * 24L)
        val hours = (totalMinutes / 60L) % 24L
        val minutes = totalMinutes % 60L
        val parts = buildList {
            if (days > 0L) add("${days}d")
            if (hours > 0L) add("${hours}h")
            if (minutes > 0L || isEmpty()) add("${minutes}m")
        }
        return parts.joinToString(" ")
    }

    private companion object {
        const val QUERY_TIMEOUT_MILLIS = 10_000L
        const val DEFAULT_DOMAIN_LIMIT = 30
        const val MAX_DOMAIN_LIMIT = 300
        const val REASON_RANGE_UNAVAILABLE = "activity-range-unavailable"
        const val REASON_UNAVAILABLE = "activity-unavailable"
        const val REASON_DOMAINS_UNAVAILABLE = "domains-unavailable"
        const val REASON_HISTORY_UNAVAILABLE = "domain-history-unavailable"
        const val REASON_NETWORK_UNAVAILABLE = "network-unavailable"
        const val REASON_DIAGNOSTICS_UNAVAILABLE = "diagnostics-unavailable"
    }
}

private fun <T> ActivityContentState<T>.valueOrLastGood(): T? = when (this) {
    is ActivityContentState.Ready -> value
    is ActivityContentState.Loading -> lastGood
    is ActivityContentState.Failed -> lastGood
    ActivityContentState.Disabled,
    ActivityContentState.Unavailable,
    -> null
}

/**
 * Converts the canonical Unix-seconds range into an exact local-day [ActivityDateLens] in [zone].
 * A boundary outside the selectable Activity window (older than 24 months or in the future) is
 * refused with null rather than clamped into a different range; reversed bounds are normalized to
 * the same days the picker would read.
 */
internal fun activityDateLens(zone: ZoneId, startSeconds: Long?, endSeconds: Long?): ActivityDateLens? {
    if (startSeconds == null && endSeconds == null) return null
    val first = startSeconds ?: endSeconds!!
    val second = endSeconds ?: startSeconds!!
    val today = LocalDate.now(zone)
    val lower = Instant.ofEpochSecond(minOf(first, second)).atZone(zone).toLocalDate()
    val upper = Instant.ofEpochSecond(maxOf(first, second)).atZone(zone).toLocalDate()
    return runCatching {
        ActivityDateLens(
            startDayMillis = lower.atStartOfDay(zone).toInstant().toEpochMilli(),
            endDayMillis = upper.atStartOfDay(zone).toInstant().toEpochMilli(),
            zoneId = zone.id,
            today = today,
        )
    }.getOrNull()
}

private fun ActivityDateRangeUi.matches(lens: ActivityDateLens): Boolean {
    val zone = ZoneId.of(zoneId)
    val lensStart = Instant.ofEpochMilli(lens.startDayMillis).atZone(zone).toLocalDate()
    val lensEnd = Instant.ofEpochMilli(lens.endDayMillis).atZone(zone).toLocalDate()
    return start == lensStart && endInclusive == lensEnd
}

private fun DomainQueryDecision.native(): DomainDecision? = when (this) {
    DomainQueryDecision.ALLOWED -> DomainDecision.ALLOWED
    DomainQueryDecision.BLOCKED -> DomainDecision.BLOCKED
    DomainQueryDecision.ALL -> null
}

private fun DomainDecision.toUi(): ActivityDecisionUi = when (this) {
    DomainDecision.ALLOWED -> ActivityDecisionUi.Allowed
    DomainDecision.BLOCKED -> ActivityDecisionUi.Blocked
}

private fun ActivityDecisionUi.outcomeIcon(): String = when (this) {
    ActivityDecisionUi.Allowed -> "arrow.right.circle.fill"
    ActivityDecisionUi.Blocked -> "xmark.circle.fill"
}

private fun ActivityDecisionUi.outcomeTone(): String = when (this) {
    ActivityDecisionUi.Allowed -> "green"
    ActivityDecisionUi.Blocked -> "orange"
}

private fun DomainHistoryReasonUi.labelResource(): Int = when (this) {
    DomainHistoryReasonUi.DefaultAllow -> R.string.activity_history_reason_default
    DomainHistoryReasonUi.PausedAllow -> R.string.activity_history_reason_paused_allow
    DomainHistoryReasonUi.LocalAllowlist -> R.string.activity_history_reason_allowlist
    DomainHistoryReasonUi.Blocklist -> R.string.activity_history_reason_blocklist
    DomainHistoryReasonUi.ThreatGuardrail -> R.string.activity_history_reason_threat_guardrail
    DomainHistoryReasonUi.InvalidDomain -> R.string.activity_history_reason_invalid_domain
    DomainHistoryReasonUi.ProtectionUnavailable -> R.string.activity_history_reason_failed_safe
}

private fun NetworkActivityNetworkKind.labelResource(): Int = when (this) {
    NetworkActivityNetworkKind.UNKNOWN -> R.string.nerd_stats_transport_unknown
    NetworkActivityNetworkKind.WIFI -> R.string.nerd_stats_transport_wifi
    NetworkActivityNetworkKind.CELLULAR -> R.string.nerd_stats_transport_cellular
    NetworkActivityNetworkKind.WIRED -> R.string.nerd_stats_transport_wired
    NetworkActivityNetworkKind.OTHER -> R.string.nerd_stats_transport_other
}

/** Serializes a typed headless query result into the canonical wire JSON the RN adapter reads. */
internal object ProductQueryWire {
    fun encode(value: Any): Any = when (value) {
        is ActivityQueryResult -> JSONObject()
            .put("allowed", value.allowed)
            .put("blocked", value.blocked)
            .put("uptime", value.uptime)

        is List<*> -> JSONArray().apply {
            value.forEach { row ->
                put(
                    when (row) {
                        is DomainQueryRow -> JSONObject()
                            .put("id", row.id)
                            .put("domain", row.domain)
                            .put("metadata", row.metadata)
                            .put("icon", row.icon)
                            .put("tone", row.tone)

                        is NetworkQueryRow -> JSONObject()
                            .put("id", row.id)
                            .put("title", row.title)
                            .put("subtitle", row.subtitle)
                            .put("metadata", row.metadata)

                        else -> JSONObject.NULL
                    },
                )
            }
        }

        is StatsQueryResult -> JSONObject().apply {
            put("sampleNotice", value.sampleNotice ?: JSONObject.NULL)
            put("app", JSONArray(value.app.map { JSONArray(it) }))
            put("tiers", JSONArray(value.tiers.map { JSONArray(it) }))
            put(
                "healthSections",
                JSONArray(
                    value.healthSections.map { section ->
                        JSONObject()
                            .put("id", section.id)
                            .put("title", section.title)
                            .put(
                                "rows",
                                JSONArray(
                                    section.rows.map { row ->
                                        JSONObject().put("id", row.id).put("title", row.title).put("value", row.value)
                                    },
                                ),
                            )
                    },
                ),
            )
        }

        is Boolean -> value
        is JSONObject -> value
        is JSONArray -> value
        else -> JSONObject.NULL
    }
}
