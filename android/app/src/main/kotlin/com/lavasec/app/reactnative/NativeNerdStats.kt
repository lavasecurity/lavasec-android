package com.lavasec.app.reactnative

import com.lavasec.app.diagnostics.LiveNerdStatsSample
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverTransport
import com.lavasec.app.foundation.health.*
import com.lavasec.app.foundation.resolver.*
import java.text.NumberFormat
import kotlin.math.ceil

/** The shared RN StatsScreen contract, populated from a fresh, generation-fenced native sample. */
internal fun nerdStatsResult(
    sample: LiveNerdStatsSample,
    selection: ResolverSelection?,
    app: List<List<String>>,
    timestamp: (Long) -> String,
): StatsQueryResult {
    val live = sample.health
    val stats = live.diagnostics
    fun count(value: Long) = NumberFormat.getIntegerInstance().format(value)
    fun date(value: Long?) = value?.let(timestamp) ?: "None yet"
    fun row(id: String, title: String, value: Any) = StatsRow(id, title, value.toString())
    val sections = mutableListOf(StatsSection("network", "Network & runtime", listOf(
        row("connected", "Connection", sample.status),
        row("network", "Network", sample.network),
        row("network-path", "Network path", sample.networkPath),
        row("internet", "Internet access", when {
            !sample.connected || sample.chained || live.internetValidated == null -> "Not sampled"
            live.internetValidated == true -> "Internet check passed"
            else -> "Internet unverified"
        }),
        row("network-changes", "Network changes", count(stats.networkChanges)),
        row("last-network-change", "Last network change", date(stats.lastNetworkChangeAtMillis)),
        row("runtime-resets", "Runtime resets", count(stats.runtimeResets)),
        row("last-runtime-reset", "Last runtime reset", date(stats.lastRuntimeResetAtMillis)),
        row("data-path", "Data path", if (sample.chained) "Chained (VPN)" else if (sample.connected) "DNS-only" else "Not connected"),
    )))
    if (sample.chained) sections += StatsSection("chaining", "VPN chaining", listOf(
        row("health", "Connectivity health", live.health.assessment.severity.name.lowercase().replace('_', ' ')),
        row("dns-answered", "Chained DNS answered", count(stats.tiers[0]?.served ?: 0)),
        row("dns-unanswered", "Chained DNS unanswered", count(stats.tiers[0]?.failures ?: 0)),
        row("dns-outages", "Chained DNS outages", live.chainedDiagnostics?.dnsOutages ?: "Not sampled yet"),
        row("link-outages", "Chained outages", live.chainedDiagnostics?.outages ?: "Not sampled yet"),
        row("data-outages", "Data-path outages", live.chainedDiagnostics?.dataPathOutages ?: "Not sampled yet"),
        row("unanswered", "Unanswered destinations", live.chainedDiagnostics?.unansweredDestinations?.let { if (it >= 2) "2 or more" else it.toString() } ?: "Not sampled yet"),
        row("longest-unanswered", "Longest unanswered", live.chainedDiagnostics?.longestUnansweredSeconds?.let { "$it s" } ?: "Not sampled yet"),
        row("sent", "Data-path sent (last min)", live.chainedDiagnostics?.sentLastMinute?.let { "${count(it)} bytes" } ?: "Not sampled yet"),
        row("received", "Data-path received (last min)", live.chainedDiagnostics?.receivedLastMinute?.let { "${count(it)} bytes" } ?: "Not sampled yet"),
    ))
    for ((tier, title) in listOf(0 to "T0 · VPN DNS health", 1 to "T1 · Primary DNS health", 2 to "T2 · Fallback DNS health")) {
        val observation = stats.tiers[tier]
        val latest = observation?.latest
        val admission = observation?.latestAdmission
        val age = observation?.observedAtMillis?.let { sample.sampledAtMillis - it }
        val current = sample.connected && (tier != 0 || sample.chained) && age != null && age in 0L..90_000L
        val admissionAge = observation?.admissionObservedAtMillis?.let { sample.sampledAtMillis - it }
        val currentAdmission = sample.connected && (tier != 0 || sample.chained) &&
            admissionAge != null && admissionAge in 0L..90_000L
        val label = when {
            !sample.connected -> "Not connected"
            tier == 0 && !sample.chained -> "Not active"
            latest == null -> "No observations yet"
            !current -> "Sample out of date"
            latest.outcome == ResolverTierDiagnosticOutcome.SERVED -> "Served"
            latest.outcome == ResolverTierDiagnosticOutcome.ANSWERED -> "Replied without service"
            latest.outcome == ResolverTierDiagnosticOutcome.FAILED -> "Failed"
            else -> "Not attempted"
        }
        val rows = mutableListOf(row("observation", "Latest observation", label))
        if (admission != null) rows += row("admission", "Latest attempt",
            if (currentAdmission) admissionLabel(admission.failure) else "Status unavailable")
        val source = latest ?: admission
        if (observation != null && source != null) {
            rows += row("source", "Resolver source", resolverName(source.resolver))
            source.transport?.let { rows += row("transport", "Transport", transportName(it)) }
            rows += row("egress", "DNS route", if (tier == 0) "VPN" else "Physical network")
            rows += row("attempts", "Attempts", count(observation.attempts))
            rows += row("served", "Served", count(observation.served))
            rows += row("answered", "Replies without service", count(observation.answered))
            rows += row("failures", "Failures", count(observation.failures))
            rows += row("skipped", "Not attempted", count(observation.notAttempted))
            rows += row("failure-streak", "Failure streak", count(observation.failureStreak))
            rows += row("rejected-streak", "Rejected reply streak", count(observation.rejectedStreak))
            rows += row("recovery", "Recovery", when {
                !current && !currentAdmission -> "Status unavailable"
                currentAdmission && observation.failureStreak == 0L && observation.rejectedStreak == 0L -> "Not started"
                observation.failureStreak == 0L && observation.rejectedStreak == 0L -> "Not needed"
                live.health.requiresReconnect -> "Reconnect needed"
                else -> "Waiting for recovery"
            })
            observation.lastFailure?.let { rows += row("last-failure", "Last failure", it.name.lowercase().replace('_', ' ')) }
        }
        sections += StatsSection("dns-health-$tier", title, rows)
    }
    val latencies = stats.responseLatenciesMillis.sorted()
    fun percentile(percent: Double) = latencies[(ceil(latencies.size * percent).toInt() - 1).coerceAtLeast(0)]
    val performance = mutableListOf(
        row("resolver", "Last resolver", stats.lastResolver?.let(::resolverName) ?: "None yet"),
        row("transport", "Last transport", stats.lastTransport?.let(::transportName) ?: "None yet"),
        row("doh-protocol", "DoH protocol", stats.lastHttpProtocol?.let { if (it == DnsHttpProtocol.HTTP_2) "HTTP/2" else "HTTP/1.1" } ?: if (stats.lastTransport == ResolverTransport.MULLVAD_DOH) "Not observed" else "Not in use"),
        row("last-response", "Last DNS response", stats.responseLatenciesMillis.lastOrNull()?.let { "$it ms" } ?: "None yet"),
        row("response-time", "DNS response time", if (latencies.isEmpty()) "None yet" else "p50 ${percentile(.5)} ms · p95 ${percentile(.95)} ms · p99 ${percentile(.99)} ms\nLast ${latencies.size} responses"),
        row("upstream-success", "Upstream success", count(stats.upstreamSuccesses)),
        row("last-success", "Last success", date(stats.lastSuccessAtMillis)),
        row("upstream-failures", "Upstream failures", count(stats.upstreamFailures)),
        row("last-failure-time", "Last failure time", date(stats.lastFailureAtMillis)),
        row("timeouts", "Timeouts", count(stats.timeouts)),
        row("tcp-fallback", "TCP fallback", "${count(stats.tcpResponses)} responses"),
        row("smoke-probes", "DNS smoke probes", "${count(stats.smokeProbes)} · ${count(stats.smokeSuccesses)} succeeded"),
        row("device-fallback", "Device DNS fallback", "${count(stats.deviceFallbacks)} responses"),
        // This engine has no upstream response cache. Zero percent would imply a measured cache.
        row("cache", "Cache hit rate", "Not used on Android"),
        row("active-fallback", "Active fallback", when (live.health.activeFallback) {
            ProtectionFallback.NONE -> "Off"
            ProtectionFallback.DEVICE_DNS -> "Device DNS"
            ProtectionFallback.ENCRYPTED_DNS -> "Encrypted DNS"
            ProtectionFallback.PLAIN_DNS -> "Plain DNS"
        }),
        row("reconnect-needed", "Reconnect needed", if (live.health.requiresReconnect) "Yes" else "No"),
    )
    stats.lastFailure?.let { performance += row("last-failure", "Last failure", it.name.lowercase().replace('_', ' ')) }
    performance += row("session-started", "Session started", date(stats.startedAtMillis))
    performance += row("sampled", "Sampled", timestamp(sample.sampledAtMillis))
    sections += StatsSection("performance", "Performance & sampling", performance)
    return StatsQueryResult(
        sampleNotice = if (sample.connected) "Counters since this protection session started. Refresh captures the current runtime." else "Protection is not connected. No live session counters.",
        app = app,
        tiers = listOf(
            listOf("T0 · VPN chaining", sample.vpnConfiguration),
            listOf("1 · Primary DNS", selection?.primary?.let { resolverConfiguration(it, sample.deviceDns) } ?: "Unavailable"),
            listOf("2 · Fallback DNS", selection?.fallback?.let { resolverConfiguration(it, sample.deviceDns) }
                ?: selection?.tiers?.firstOrNull { !it.isEnabled }?.entry?.let { "Off\n${resolverConfiguration(it, sample.deviceDns)}" } ?: "Off"),
            listOf("S · System DNS", sample.systemDns),
        ),
        healthSections = sections,
    )
}

private fun admissionLabel(reason: ResolverFailureReason?): String = when (reason) {
    ResolverFailureReason.BACKED_OFF -> "Waiting to retry"
    ResolverFailureReason.TIMEOUT -> "DNS request expired"
    ResolverFailureReason.STALE_EPOCH, ResolverFailureReason.CANCELLED -> "Connection changed"
    ResolverFailureReason.OVERLOADED -> "DNS capacity unavailable"
    else -> "Temporarily unavailable"
}

private fun resolverConfiguration(entry: ResolverSelectionEntry, deviceDns: List<String>): String {
    val endpoints = if (entry.providerId == ResolverProviderId.DEVICE_DNS) deviceDns else when (entry.transport) {
        CatalogResolverTransport.DOH -> listOfNotNull(entry.customDohUrl ?: ResolverCatalog.dohUrl(entry.provider), entry.customSecondaryEndpoint)
        CatalogResolverTransport.DOT -> listOfNotNull(entry.customDotEndpoint ?: ResolverCatalog.dotEndpoint(entry.provider), entry.customSecondaryEndpoint)
        CatalogResolverTransport.DOQ -> listOfNotNull(entry.customDoqEndpoint, entry.customSecondaryEndpoint)
        CatalogResolverTransport.PLAIN_DNS -> entry.customPlainEndpoint?.let { listOfNotNull(it, entry.customSecondaryEndpoint) } ?: ResolverCatalog.addresses(entry.provider)
    }
    return listOf(entry.customName ?: entry.provider.displayName, when (entry.transport) {
        CatalogResolverTransport.DOH -> "DNS over HTTPS"
        CatalogResolverTransport.DOT -> "DNS over TLS"
        CatalogResolverTransport.DOQ -> "DNS over QUIC"
        CatalogResolverTransport.PLAIN_DNS -> "Standard DNS"
    }, endpoints.joinToString(", ").ifEmpty { "No DNS servers available" }).joinToString("\n")
}
private fun resolverName(kind: ResolverKind): String = when (kind) {
    ResolverKind.DEVICE -> "Device DNS"
    ResolverKind.MULLVAD_DOH -> "Selected resolver"
    ResolverKind.PLAIN_DNS -> "Selected resolver"
    ResolverKind.WIREGUARD_T0 -> "VPN DNS"
}
private fun transportName(transport: ResolverTransport): String = when (transport) {
    ResolverTransport.DEVICE_UDP, ResolverTransport.PLAIN_UDP -> "UDP"
    ResolverTransport.DEVICE_TCP, ResolverTransport.PLAIN_TCP -> "TCP"
    ResolverTransport.MULLVAD_DOH -> "DNS over HTTPS"
    ResolverTransport.DOT -> "DNS over TLS"
    ResolverTransport.DOQ -> "DNS over QUIC"
    ResolverTransport.WIREGUARD_UDP -> "UDP over WireGuard"
}
