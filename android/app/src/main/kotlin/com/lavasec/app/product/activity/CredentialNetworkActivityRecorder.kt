package com.lavasec.app.product.activity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.lavasec.app.foundation.activity.NetworkActivityCapturedState
import com.lavasec.app.foundation.activity.NetworkActivityConnectivityState
import com.lavasec.app.foundation.activity.NetworkActivityEvent
import com.lavasec.app.foundation.activity.NetworkActivityFallbackActivationCause
import com.lavasec.app.foundation.activity.NetworkActivityNetworkKind
import com.lavasec.app.foundation.activity.NetworkActivityProtectionState
import com.lavasec.app.foundation.activity.NetworkActivityReconnectReason
import com.lavasec.app.foundation.activity.NetworkActivityRecoveryReason
import com.lavasec.app.foundation.activity.NetworkActivityResolverIdentity
import com.lavasec.app.foundation.activity.NetworkActivityResolverTransport
import com.lavasec.app.foundation.activity.NetworkActivityUserAction
import com.lavasec.app.foundation.health.ProtectionFallback
import com.lavasec.app.foundation.health.ProtectionHealthSeverity
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.platform.ProtectionController
import com.lavasec.app.platform.ProtectionState
import com.lavasec.app.vpn.VpnHealthStatus
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The bounded domain-free facts a ConnectivityManager default-network callback can prove:
 * presence, physical transport kind, and validated-path state. No network is represented as
 * `present=false, kind=UNKNOWN` — the store contract's honest absence value.
 */
data class AndroidDefaultNetworkFacts(
    val present: Boolean = false,
    val kind: NetworkActivityNetworkKind = NetworkActivityNetworkKind.UNKNOWN,
    val pathSatisfied: Boolean = false,
) {
    init {
        require(present || kind == NetworkActivityNetworkKind.UNKNOWN) {
            "an absent network cannot claim a transport kind"
        }
        require(present || !pathSatisfied) {
            "an absent network cannot claim a satisfied path"
        }
    }
}

/**
 * Thin app-side wrapper over the platform default-network callback (iOS parity:
 * PacketTunnelProvider's network-path monitoring, but from the process that can see it).
 * It deliberately exposes only transport kind + validated path + loss: no addresses, no
 * SSIDs, no other identifying facts ever cross this boundary.
 */
class AndroidDefaultNetworkMonitor(
    context: Context,
) : AutoCloseable {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val mutableFacts = MutableStateFlow(AndroidDefaultNetworkFacts())
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            mutableFacts.value = AndroidDefaultNetworkFacts(
                present = true,
                kind = networkCapabilities.transportKind(),
                pathSatisfied = networkCapabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED,
                ),
            )
        }

        override fun onLost(network: Network) {
            mutableFacts.value = AndroidDefaultNetworkFacts()
        }
    }

    val facts: StateFlow<AndroidDefaultNetworkFacts> = mutableFacts.asStateFlow()

    init {
        runCatching { manager.registerDefaultNetworkCallback(callback) }
    }

    override fun close() {
        runCatching { manager.unregisterNetworkCallback(callback) }
    }
}

private fun NetworkCapabilities.transportKind(): NetworkActivityNetworkKind = when {
    hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkActivityNetworkKind.WIFI
    hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkActivityNetworkKind.CELLULAR
    hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkActivityNetworkKind.WIRED
    else -> NetworkActivityNetworkKind.OTHER
}

/**
 * App-side producer that records only events it can truthfully derive into the credential
 * Network Activity store. Every event is domain-free and every captured-state fact comes
 * from one of three real sources:
 *
 *  1. User commands — protection on/off/reconnect admitted through the exact
 *     [ProtectionController] decorator ([CredentialNetworkActivityRecordingController]),
 *     mirroring the iOS AppViewModel recording at the user gesture (AppViewModel.swift
 *     `appendAppNetworkActivity`).
 *  2. The process VPN runtime — [VpnHealthStatus] transitions (activeFallback edges,
 *     requiresReconnect edges, severity recovery) and [ProtectionState] transitions
 *     (off/starting→Protected), mirroring the iOS tunnel's ResolverHealthGateway event
 *     mapping in PacketTunnelProvider.swift.
 *  3. The default network — [AndroidDefaultNetworkFacts] transitions, mirroring the iOS
 *     tunnel's network-path monitoring.
 *
 * Honesty boundaries enforced here:
 *  - The producer cannot attribute *why* a fallback activated (it only observes the
 *    accumulated flag), so every FallbackActivated carries
 *    [NetworkActivityFallbackActivationCause.ProducerUnspecified] — the same value the
 *    foundation's `DnsHealthEvent.FallbackActivated.toNetworkActivityFallbackEvent`
 *    adapter selects.
 *  - It cannot attribute *why* the runtime raised requiresReconnect (artifact/plan/tun
 *    write paths are indistinguishable in the accumulated status), so every
 *    ReconnectNeeded carries [NetworkActivityReconnectReason.RuntimeUnavailable].
 *  - Recovery is derived from severity returning to HEALTHY and from fallback
 *    deactivation on a stable engine identity, so
 *    [NetworkActivityRecoveryReason.PRIMARY_RESOLVER] states the present-tense fact
 *    ("the primary resolver route is healthy again") rather than an unverifiable cause.
 *  - Device-DNS transport evidence is not exposed to the app process; the captured
 *    DEVICE_UDP records the engine's always-first device transport (DeviceDnsTransport
 *    issues UDP/53 before any TCP retry), never a per-query observation.
 *  - A graph creation never fabricates history: each flow's first emission anchors the
 *    baseline and only later emissions derive transitions.
 */
internal class CredentialNetworkActivityRecorder(
    private val store: CredentialNetworkActivityRuntimeStore,
    protection: Flow<ProtectionState>,
    health: Flow<VpnHealthStatus>,
    networkFacts: Flow<AndroidDefaultNetworkFacts>,
    private val retentionEnabled: () -> Boolean,
    private val onRecorded: () -> Unit = {},
    private val networkSource: AutoCloseable? = null,
    scope: CoroutineScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val privacyFence: com.lavasec.app.product.privacy.CredentialPrivacyFence = com.lavasec.app.product.privacy.CredentialPrivacyFence(),
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private var previousProtection: ProtectionState? = null
    private var previousHealth: VpnHealthStatus? = null
    private var previousNetwork: AndroidDefaultNetworkFacts? = null
    private var snapshot = RecorderSnapshot()
    private val ownerJob = SupervisorJob(scope.coroutineContext[Job])
    private val ownerScope = CoroutineScope(scope.coroutineContext + ownerJob)

    init {
        ownerScope.launch { protection.collect(::onProtection) }
        ownerScope.launch { health.collect(::onHealth) }
        ownerScope.launch { networkFacts.collect(::onNetwork) }
    }

    /** Wraps the exact service client so admitted user commands become user-action events. */
    fun recordingController(delegate: ProtectionController): ProtectionController =
        CredentialNetworkActivityRecordingController(delegate, ::recordUserAction)

    /**
     * Wraps the exact resolver-switch port so a successful plan change becomes the
     * user-action event the iOS changeResolver/toggleDeviceDNSFallback recordings map
     * to. The current plan is read at call time from the same live plan flow the
     * Settings surface renders, never from a stale creation-time snapshot.
     */
    fun recordingResolverSwitchPort(
        delegate: com.lavasec.app.product.ResolverPlanSwitchPort,
        currentPlan: () -> com.lavasec.app.foundation.resolver.MilestoneResolverPlan?,
    ): com.lavasec.app.product.ResolverPlanSwitchPort =
        CredentialNetworkActivityRecordingResolverSwitchPort(delegate, currentPlan, ::recordUserAction)

    /** Wraps the exact profile-switch port so an Applied switch records CHANGE_FILTERS. */
    fun recordingProfileSwitchPort(
        delegate: com.lavasec.app.product.FilterProfileSwitchPort,
    ): com.lavasec.app.product.FilterProfileSwitchPort =
        CredentialNetworkActivityRecordingProfileSwitchPort(delegate) {
            recordUserAction(NetworkActivityUserAction.CHANGE_FILTERS)
        }

    /** Records CHANGE_FILTERS when an Applied library mutation changed the filter in effect. */
    fun recordLibraryApplied(
        before: com.lavasec.app.foundation.filter.FilterLibraryDraft,
        after: com.lavasec.app.foundation.filter.FilterLibraryDraft,
    ) {
        if (libraryActiveFilteringChanged(before, after)) {
            recordUserAction(NetworkActivityUserAction.CHANGE_FILTERS)
        }
    }

    fun recordUserAction(action: NetworkActivityUserAction) {
        record(NetworkActivityEvent.UserAction(action))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        ownerJob.cancel()
        networkSource?.let { source -> runCatching(source::close) }
    }

    private fun onProtection(state: ProtectionState) {
        val previous = synchronized(lock) {
            val prior = previousProtection
            previousProtection = state
            snapshot = snapshot.copy(protection = state)
            prior
        } ?: return
        if (previous !is ProtectionState.Protected && state is ProtectionState.Protected) {
            record(NetworkActivityEvent.ProtectionConnected)
        }
    }

    private fun onHealth(status: VpnHealthStatus) {
        val previous = synchronized(lock) {
            val prior = previousHealth
            previousHealth = status
            snapshot = snapshot.copy(health = status)
            prior
        } ?: return
        healthTransitions(previous.health, status.health).forEach(::record)
    }

    private fun onNetwork(facts: AndroidDefaultNetworkFacts) {
        val previous = synchronized(lock) {
            val prior = previousNetwork
            previousNetwork = facts
            snapshot = snapshot.copy(network = facts)
            prior
        } ?: return
        if (facts == previous) return
        record(
            NetworkActivityEvent.NetworkChanged(
                from = previous.kind.takeIf { previous.present },
                // A loss keeps the last observed kind so the row reads "…: Wi-Fi path lost"
                // exactly like the iOS display line (NetworkActivityLog.swift displayLine).
                to = facts.kind.takeIf { facts.present } ?: previous.kind,
                pathSatisfied = facts.pathSatisfied,
            ),
        )
    }

    internal fun healthTransitions(
        previous: com.lavasec.app.foundation.health.ProtectionHealthAccumulator,
        current: com.lavasec.app.foundation.health.ProtectionHealthAccumulator,
    ): List<NetworkActivityEvent> {
        val events = mutableListOf<NetworkActivityEvent>()
        val engineStable = current.isConnected && sameEngineIdentity(previous, current)
        when {
            previous.activeFallback == ProtectionFallback.NONE &&
                current.activeFallback != ProtectionFallback.NONE -> events +=
                NetworkActivityEvent.FallbackActivated(
                    fallback = current.activeFallback,
                    cause = NetworkActivityFallbackActivationCause.ProducerUnspecified,
                )

            engineStable &&
                previous.activeFallback != ProtectionFallback.NONE &&
                current.activeFallback == ProtectionFallback.NONE -> when (
                previous.activeFallback
            ) {
                ProtectionFallback.DEVICE_DNS ->
                    events += NetworkActivityEvent.DeviceDnsFallbackRecovered

                ProtectionFallback.PLAIN_DNS, ProtectionFallback.ENCRYPTED_DNS -> events +=
                    NetworkActivityEvent.ConnectivityRecovered(
                        NetworkActivityRecoveryReason.PRIMARY_RESOLVER,
                    )

                ProtectionFallback.NONE -> Unit
            }

            else -> Unit
        }
        if (!previous.requiresReconnect && current.requiresReconnect) {
            events += NetworkActivityEvent.ReconnectNeeded(
                NetworkActivityReconnectReason.RuntimeUnavailable,
            )
        }
        val previousSeverity = previous.assessment.severity
        val currentSeverity = current.assessment.severity
        if (
            current.isConnected &&
            currentSeverity == ProtectionHealthSeverity.HEALTHY &&
            previousSeverity in RECOVERY_PREDECESSOR_SEVERITIES
        ) {
            // Closes the wedge→recovery pair like the iOS gateway recovery record: a
            // reconnect rebuild that discharges requiresReconnect, or an organic primary
            // recovery that clears the failure streak, both land here as "healthy again".
            events += NetworkActivityEvent.ConnectivityRecovered(
                NetworkActivityRecoveryReason.PRIMARY_RESOLVER,
            )
        }
        return events
    }

    private fun sameEngineIdentity(
        previous: com.lavasec.app.foundation.health.ProtectionHealthAccumulator,
        current: com.lavasec.app.foundation.health.ProtectionHealthAccumulator,
    ): Boolean {
        val priorContext = previous.context ?: return false
        val currentContext = current.context ?: return false
        return priorContext.sessionGeneration == currentContext.sessionGeneration &&
            priorContext.networkGeneration == currentContext.networkGeneration &&
            priorContext.engineGeneration == currentContext.engineGeneration
    }

    private fun record(event: NetworkActivityEvent) {
        val now = checkedNow()
        var recorded = false
        privacyFence.tryTelemetryWrite {
            // Check retention inside the same boundary that disables it and clears saved rows.
            if (retentionEnabled()) synchronized(lock) {
                if (!closed.get()) {
                    try {
                        store.record(event, capturedState(snapshot), now)
                        recorded = true
                    } catch (_: java.io.IOException) {
                        // A telemetry write cannot turn a successful protection command into a
                        // failed command. A later event retries; failed writes never publish rows.
                    }
                }
            }
        }
        if (!recorded) return
        // Wake the Activity owner so the exact revision reload republishes the new row,
        // mirroring the iOS refreshNetworkActivityLog(force: true) after every append.
        onRecorded()
    }

    private fun capturedState(snapshot: RecorderSnapshot): NetworkActivityCapturedState {
        val health = snapshot.health.health
        val context = health.context
        val coverage = context?.fallbackCoverage ?: ResolverFallbackCoverage.NONE
        val (resolver, transport) = resolverFacts(context)
        return NetworkActivityCapturedState(
            protection = snapshot.protection.toCapturedProtectionState(),
            connectivity = connectivityState(health),
            networkKind = snapshot.network.kind,
            networkPathSatisfied = snapshot.network.pathSatisfied,
            resolver = resolver,
            resolverTransport = transport,
            fallbackCoverage = coverage,
            // Without an engine identity a fallback cannot be active: the reducer clears it
            // on disconnect/retire, and the contract forbids an active fallback without the
            // matching coverage. The clamp keeps a transient mid-publish window coherent.
            activeFallback = if (context != null) health.activeFallback else ProtectionFallback.NONE,
        )
    }

    private fun resolverFacts(
        context: com.lavasec.app.foundation.health.ProtectionHealthContext?,
    ): Pair<NetworkActivityResolverIdentity, NetworkActivityResolverTransport?> {
        val identity = context?.primaryResolverIdentity?.value
            ?: return NetworkActivityResolverIdentity.NONE to null
        return if (identity.startsWith("chained-upstream:")) {
            NetworkActivityResolverIdentity.WIREGUARD_T0 to NetworkActivityResolverTransport.WIREGUARD_UDP
        } else if (identity.startsWith(DEVICE_DNS_IDENTITY_PREFIX)) {
            NetworkActivityResolverIdentity.DEVICE_DNS to
                NetworkActivityResolverTransport.DEVICE_UDP
        } else if (identity.startsWith("catalog-ip:")) {
            val providerId = identity.substringAfter(":").substringBefore(":")
            (if (providerId == "custom-dns") NetworkActivityResolverIdentity.PLAIN_OTHER
                else NetworkActivityResolverIdentity.fromBuiltInDohResolverId(providerId + "-doh")) to NetworkActivityResolverTransport.PLAIN_UDP
        } else if (identity.startsWith("catalog-doh:") || identity.startsWith("catalog-dot:")) {
            val providerId = identity.substringAfter(":").substringBefore(":")
            NetworkActivityResolverIdentity.fromBuiltInDohResolverId(providerId + "-doh") to
                if (identity.startsWith("catalog-dot:")) NetworkActivityResolverTransport.DNS_OVER_TLS
                else NetworkActivityResolverTransport.DNS_OVER_HTTPS
        } else if (identity.startsWith("custom-doq:")) {
            NetworkActivityResolverIdentity.ENCRYPTED_OTHER to NetworkActivityResolverTransport.DNS_OVER_QUIC
        } else if (identity.startsWith("custom-dot:") || identity.startsWith("catalog-dot:")) {
            NetworkActivityResolverIdentity.ENCRYPTED_OTHER to NetworkActivityResolverTransport.DNS_OVER_TLS
        } else {
            NetworkActivityResolverIdentity.fromBuiltInDohResolverId(identity) to
                NetworkActivityResolverTransport.DNS_OVER_HTTPS
        }
    }

    private fun connectivityState(
        health: com.lavasec.app.foundation.health.ProtectionHealthAccumulator,
    ): NetworkActivityConnectivityState {
        if (!health.isConnected) return NetworkActivityConnectivityState.HEALTHY
        if (!health.networkAvailable) return NetworkActivityConnectivityState.NETWORK_LOST
        return when (health.assessment.severity) {
            ProtectionHealthSeverity.HEALTHY -> NetworkActivityConnectivityState.HEALTHY
            ProtectionHealthSeverity.RECOVERING -> NetworkActivityConnectivityState.RECOVERING
            ProtectionHealthSeverity.USING_PLAIN_DNS_FALLBACK -> NetworkActivityConnectivityState.PLAIN_DNS_FALLBACK
            ProtectionHealthSeverity.USING_DEVICE_DNS_FALLBACK ->
                NetworkActivityConnectivityState.DEVICE_DNS_FALLBACK

            ProtectionHealthSeverity.USING_ENCRYPTED_FALLBACK ->
                NetworkActivityConnectivityState.ENCRYPTED_FALLBACK

            ProtectionHealthSeverity.DNS_SLOW -> NetworkActivityConnectivityState.DNS_SLOW
            ProtectionHealthSeverity.NETWORK_UNAVAILABLE ->
                NetworkActivityConnectivityState.NETWORK_LOST

            ProtectionHealthSeverity.NEEDS_RECONNECT ->
                NetworkActivityConnectivityState.NEEDS_RECONNECT
        }
    }

    private fun ProtectionState.toCapturedProtectionState(): NetworkActivityProtectionState =
        when (this) {
            ProtectionState.Off,
            ProtectionState.SetupRequired,
            is ProtectionState.Failed,
            -> NetworkActivityProtectionState.OFF

            // The contract has no RECONNECTING value; reconnection is still establishment.
            is ProtectionState.Starting,
            is ProtectionState.AwaitingNetwork,
            is ProtectionState.Reconnecting,
            -> NetworkActivityProtectionState.STARTING

            is ProtectionState.Protected -> NetworkActivityProtectionState.PROTECTED
            is ProtectionState.Paused -> NetworkActivityProtectionState.PAUSED
            ProtectionState.Stopping -> NetworkActivityProtectionState.STOPPING
        }

    private fun checkedNow(): Long = nowMillis().also { require(it >= 0L) }

    private data class RecorderSnapshot(
        val protection: ProtectionState = ProtectionState.Off,
        val health: VpnHealthStatus = VpnHealthStatus(),
        val network: AndroidDefaultNetworkFacts = AndroidDefaultNetworkFacts(),
    )

    private companion object {
        val RECOVERY_PREDECESSOR_SEVERITIES = setOf(
            ProtectionHealthSeverity.RECOVERING,
            ProtectionHealthSeverity.DNS_SLOW,
            ProtectionHealthSeverity.NEEDS_RECONNECT,
        )
        const val DEVICE_DNS_IDENTITY_PREFIX = "device-dns:"
    }
}

/**
 * Records a user action exactly when its command is admitted to the runtime, before the
 * delegate dispatch — the iOS AppViewModel records at the same user-gesture point, so a
 * later service rejection does not retract the user's observable action.
 */
internal class CredentialNetworkActivityRecordingController(
    private val delegate: ProtectionController,
    private val onUserCommand: (NetworkActivityUserAction) -> Unit,
) : ProtectionController {
    override val state: StateFlow<ProtectionState> get() = delegate.state

    override suspend fun start() {
        onUserCommand(NetworkActivityUserAction.TURN_PROTECTION_ON)
        delegate.start()
    }

    override suspend fun stop() {
        onUserCommand(NetworkActivityUserAction.TURN_PROTECTION_OFF)
        delegate.stop()
    }

    override suspend fun pause(duration: com.lavasec.app.foundation.pause.PauseDuration) =
        delegate.pause(duration)

    override suspend fun resume() = delegate.resume()

    override suspend fun reconnect() {
        onUserCommand(NetworkActivityUserAction.RECONNECT_PROTECTION)
        delegate.reconnect()
    }
}
