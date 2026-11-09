package com.lavasec.app.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import com.lavasec.app.foundation.chained.ChainedDnsCaptureFloor
import com.lavasec.app.foundation.chained.ChainedRoutingPolicy
import com.lavasec.app.foundation.chained.ChainedUpstreamConfigurationValidator
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.health.PrimaryResolverIdentity
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.foundation.pause.PauseDuration
import com.lavasec.app.foundation.pause.RuntimePauseDirective
import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.platform.canonicalChainedUpstreamConfigurationFingerprint
import com.lavasec.app.platform.PlatformVpnFacts
import com.lavasec.app.platform.ProtectionFailure
import com.lavasec.app.platform.ProtectionState
import com.lavasec.app.platform.StartOrigin
import com.lavasec.app.platform.VpnOwnership
import com.lavasec.app.vpn.chained.ChainedCaptureFloorRefreshGate
import com.lavasec.app.vpn.chained.ChainedAwaitingNetworkAction
import com.lavasec.app.vpn.chained.ChainedAwaitingNetworkPolicy
import com.lavasec.app.vpn.chained.ChainedDefaultNetworkIdentity
import com.lavasec.app.vpn.chained.ChainedEndpointPreflightResolution
import com.lavasec.app.vpn.chained.ChainedEndpointResolver
import com.lavasec.app.vpn.chained.ChainedEngineState
import com.lavasec.app.vpn.chained.ChainedOwnerSlot
import com.lavasec.app.vpn.chained.ChainedPeerPreflightOutcome
import com.lavasec.app.vpn.chained.ChainedRecoverySerializer
import com.lavasec.app.vpn.chained.ChainedRuntimeFailure
import com.lavasec.app.vpn.chained.ChainedRuntimeMode
import com.lavasec.app.vpn.chained.ChainedStartupGate
import com.lavasec.app.vpn.chained.ChainedStatePublicationFence
import com.lavasec.app.vpn.chained.ChainedSurrenderNetworkPolicy
import com.lavasec.app.vpn.chained.ChainedTerminalShutdownGate
import com.lavasec.app.vpn.chained.ChainedTerminationAction
import com.lavasec.app.vpn.chained.ChainedTerminationPolicy
import com.lavasec.app.vpn.chained.ChainedDnsPolicy
import com.lavasec.app.vpn.chained.ChainedTunRuntimeOwner
import com.lavasec.app.vpn.chained.ChainedTunTermination
import com.lavasec.app.vpn.chained.ChainedVpnCredentials
import com.lavasec.app.vpn.chained.chainedActivityPublications
import com.lavasec.app.vpn.chained.chainedAttemptAdmittedAfterPreflight
import com.lavasec.app.vpn.chained.chainedEndpointPreflightResolution
import com.lavasec.app.vpn.chained.chainedStartRequiresAwaitingNetwork
import com.lavasec.app.vpn.chained.isEstablished
import com.lavasec.app.vpn.chained.shouldClearChainedActivityOnClaim
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

internal class VpnRuntimeStartException(
    val failure: ProtectionFailure,
    cause: Throwable? = null,
) : IllegalStateException(failure.toString(), cause)

/** Production composition of the reviewed DNS-only TUN, ready engine, and bounded runtime. */
internal class ProductionVpnOwnedRuntime(
    private val service: VpnService,
    private val request: VpnOwnedRuntimeRequest,
    private val eligibleNetwork: () -> SelectedVpnNetwork? = { selectNetwork(service) },
    private val observeNetworkAvailability: ((() -> Unit) -> AutoCloseable) = { callback ->
        observeEligibleNetwork(service, callback)
    },
    private val observeDnsNetworkChanges: (SelectedVpnNetwork, () -> Unit) -> DnsNetworkObservation =
        { initial, changed -> AndroidDnsNetworkObservation(service, initial, changed) },
    /**
     * Begins delivering default-network path transitions and returns what must be retained to stop
     * them. Production registers a default network callback; a test injects a fake. Used while a
     * chained owner is live to feed the outage driver's rebind/path-recovery entry point. The
     * `Network` argument is the underlay the live chained socket was established on, so the path
     * tracker is seeded with it and a default-network change ahead of the callback's first
     * snapshot is still reported (finding 4021623299).
     */
    private val observeDefaultNetworkPath: ((Network, (Boolean, Boolean) -> Unit) -> AutoCloseable) =
        { establishmentNetwork, callback ->
            observeDefaultNetwork(service, establishmentNetwork, callback)
        },
    /**
     * The one serial engine confinement for this runtime's whole tunnel lifecycle — the Android
     * analogue of the provider-held `ChainedEngineQueue` in iOS
     * (`PacketTunnelProvider+ChainedDataPath.buildChainedRuntimeIfLatched`).
     *
     * This class is the Android provider analogue, and it is the ONE establishment authority: the
     * chained route-claiming establishment ([startChainedRuntime] →
     * [com.lavasec.app.vpn.chained.ChainedTunRuntimeOwner.establish] →
     * `ChainedTunEstablishment.establish`) and the DNS-only establishment
     * ([startDnsOnlyNetworkRuntime] → `AndroidTunPacketSessionFactory.establish`) are both invoked
     * from this class, and every resource-claiming transition runs on this queue. That maps the
     * provider's one `setTunnelNetworkSettings` per lifecycle onto Android: the iOS provider
     * installs its settings once and swaps engines underneath them, whereas
     * `VpnService.Builder.establish()` REPLACES the active interface, so this queue is where the
     * unavoidable platform deviation is confined — a mode change is a serialized re-establish
     * executed by the queue owner, never two concurrent establishes. The queue also gates each
     * claim on its generation so an operation superseded mid-establishment releases what it
     * prepared instead of tearing down a tunnel it no longer owns.
     *
     * Defaults to a fresh queue (sharing the process-wide single-thread executor), so every
     * resource-claiming transition and every `VpnService.Builder.establish()` is serialized.
     * Production may inject the service-level queue so a predecessor and its replacement candidate
     * share one serial owner.
     */
    private val engineQueue: ChainedEngineQueue = ChainedEngineQueue(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val pauseClockRecheckMillis: Long = PAUSE_CLOCK_RECHECK_MILLIS,
    /**
     * Asks the owning service to restart this runtime on the next command, exactly as an explicit
     * reload does. Also refreshes an installed chained capture floor after eligible DNS changes.
     * The service-generation argument binds the reload to this
     * runtime, so a stop/newer start that retires it makes the queued reload a no-op.
     */
    private val requestRuntimeRebuild: (Long) -> Unit = {},
    /**
     * Publishes the live protection runtime's activity and installed mode, so the status surface
     * can tell a settled DNS-only fallback from an in-progress chained startup.
     */
    private val publishChainedActivity: (Boolean, ChainedRuntimeMode, Long) -> Unit = { _, _, _ -> },
) : VpnOwnedRuntime {
    init {
        require(pauseClockRecheckMillis > 0L)
    }

    private val lock = ReentrantLock()

    /**
     * Serializes each recovery operation's endpoint preflight with the commit that consumes its
     * artifacts. An explicit reconnect and awaiting-network recovery preflight outside
     * [engineQueue] and then commit under it, but they share the single preflight slot; without
     * this gate two overlapping attempts interleave and the first commit consumes the second's
     * artifacts (installing one replacement and then closing it as the second races in). Distinct
     * from [engineQueue], which serializes the resource-claiming commits themselves; the preflight
     * is deliberately kept off the engine queue so a slow resolver never delays a stop's teardown.
     */
    private val chainedRecovery = ChainedRecoverySerializer()
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.IO)
    private var started = false
    private var closed = false

    /**
     * Latched once a successor supersedes this runtime, and never cleared.
     *
     * [supersede] bumps the engine-queue generation, but the queue mints a FRESH token on every
     * later [ChainedEngineQueue.run], so a queued internal reconnect / awaiting-network retry /
     * termination recovery on THIS runtime would pass its own generation check and `establish()`
     * another TUN — deactivating the successor's just-adopted interface. This monotonic latch is
     * the runtime-level fence every lifecycle entry point checks ([lifecycleAdmitted]); the
     * already-installed tunnel is untouched, so make-before-break still holds.
     */
    private var superseded = false
    private var engineGeneration = 1L
    private var networkGeneration = request.owner.sessionGeneration
    private var attachmentGate: ExactCredentialRuntimeAttachment? = null
    private var epochGate: ServiceRuntimeEpochGate? = null
    private var healthOwner: ExactSessionHealthOwner? = null

    /** The health identity reported for the current chained session, or `null` when not chained. */
    private var chainedHealthIdentity: VpnHealthEngineIdentity? = null

    /** Whether [chainedHealthIdentity] has been installed into [healthOwner] yet. */
    private var chainedHealthInstalled = false
    private var runtime: BoundedTunRuntime? = null

    /**
     * The chained session, when this run is chained. Mutually exclusive with [runtime]: the
     * chained driver replaces the DNS engine/TUN pipeline entirely, so the two are never both
     * live. The slot keeps the owner identity so a terminal callback that races a reconnect is
     * ignored rather than closing the replacement.
     */
    private val chainedOwners = ChainedOwnerSlot<ChainedTunRuntimeOwner>()

    private var canaryLaunchCaptured = false
    private var canaryChannel: com.lavasec.app.vpn.qa.DnsLeakCanaryChannel? = null
    private var canaryLaunchArm: com.lavasec.app.vpn.qa.DnsLeakCanaryArm? = null

    /** Capture before preflight/handshake once per whole service launch, never on internal retry. */
    private fun captureCanaryLaunch() = lock.withLock {
        if (!canaryLaunchCaptured && !closed && !superseded) {
            canaryLaunchCaptured = true
            canaryChannel = (request.attachment as? CredentialVpnRuntimeAttachment)?.dnsLeakCanaryChannel
            canaryLaunchArm = canaryChannel?.captureArm()
        }
    }

    private val resolverSmokeProbes = ResolverSmokeProbeOwner(
        scope = scope,
        nowMillis = System::currentTimeMillis,
        queryIds = { PROCESS_EVENT_IDS.next() },
        isRuntimeCurrent = { engine -> lock.withLock {
            !closed && !superseded && !awaitingNetwork && runtime != null && chainedOwners.currentOrNull() == null &&
                engineGeneration == engine.epoch.engineGeneration
        } },
        health = { lock.withLock { healthOwner } },
    )

    /**
     * The activity collector's publish path, fenced to the installed owner under the same [lock]
     * the teardown takes to clear [chainedOwners] and publish `NONE`, so a retired owner's
     * descheduled emission cannot bring the surface back to `chained` after stop/surrender.
     */
    private val chainedStateFence = ChainedStatePublicationFence(
        lock = lock,
        slot = chainedOwners,
        isClosed = { closed },
        // The fence holds the owner-slot lock across this read and publication. A saved rotation
        // can change independently, but the signal always names this installed owner's rotation.
        publish = { active, mode ->
            val runningGeneration = if (mode == ChainedRuntimeMode.CHAINED) {
                chainedOwners.currentOrNull()?.configurationGeneration ?: 0L
            } else {
                0L
            }
            publishChainedActivity(active, mode, runningGeneration)
        },
    )

    /**
     * Claims the chained owner and applies its terminal action as the engine queue's newest
     * operation, so a pending terminal action cannot race a replacement.
     */
    private val chainedTermination = ChainedTerminationCoordinator(engineQueue, chainedOwners)

    /**
     * The startup-commit/live decision shared with the terminal callback, so a failure racing
     * the startup is handled synchronously by the startup (which then falls back) and can never
     * schedule an asynchronous teardown of the DNS-only fallback. Bound to the attempted owner, so
     * a superseded owner's delayed callback cannot poison a replacement's startup.
     */
    private val chainedStartup =
        ChainedStartupGate<ChainedTunRuntimeOwner, ChainedTunTermination>()

    /**
     * Latched by a claimed hard chained failure while the reconnect serialization is held, so a
     * concurrent reconnect cannot install a replacement that the stale terminal transition then
     * closes and retires the whole service.
     */
    private val chainedTerminalShutdown = ChainedTerminalShutdownGate()

    /** Observes the installed chained owner's engine lifecycle and republishes the activity/mode. */
    private var chainedStateJob: Job? = null

    private var completion: Job? = null
    private var activityHeartbeat: Job? = null
    private var pauseExpiry: Job? = null
    private var networkRegistration: AutoCloseable? = null
    private var dnsNetworkObservation: DnsNetworkObservation? = null
    private var dnsNetworkRefresh: Job? = null
    private val dnsNetworkChanges = Channel<Unit>(Channel.CONFLATED)
    /**
     * The default-network path callback armed while a chained owner is live. Distinct from
     * [networkRegistration], which only exists while awaiting a network.
     */
    private var chainedPathRegistration: AutoCloseable? = null
    private var chainedFloorRefreshJob: Job? = null
    private var chainedFloorRefreshGate: ChainedCaptureFloorRefreshGate<ChainedTunRuntimeOwner>? = null
    private var awaitingNetwork = false

    /**
     * True when the awaiting state is a deferred chained-surrender DNS-only rebuild. Network
     * recovery must complete that fallback instead of starting the initial (possibly chained)
     * runtime and reinstalling the mode that just surrendered.
     */
    private var pendingDnsOnlyRebuild = false
    private var terminalFailure: ProtectionFailure = ProtectionFailure.TunnelEnded
    private var selected: SelectedVpnNetwork? = null
    private lateinit var runtimeAttachment: CredentialVpnRuntimeAttachment

    /**
     * The chained-endpoint preflight resolved by [preflight] while the predecessor still owned its
     * tunnel. Consumed once by the next [start]; a [ChainedPreflightState.Refused] records an
     * attempted lookup that failed, so [start] falls straight back to DNS-only instead of
     * re-resolving in the no-tunnel gap.
     */
    private var chainedPreflight: ChainedPreflightState? = null

    /**
     * The chained credentials [preflight] opened and gate-verified while the predecessor still owned
     * its tunnel.
     *
     * Transferred to the next [startInitialNetworkRuntime] so the post-retirement start does not
     * perform a second (slow, hardware-backed) Keystore read in the no-tunnel gap. Ownership is
     * consumed under the same lock as [chainedPreflight]; every path that does not hand it to a live
     * chained owner closes it.
     */
    private var chainedPreflightCredentials: ChainedVpnCredentials? = null

    /**
     * True when [preflightChainedEndpoint] reached the credential read (the slow, hardware-backed
     * Keystore step) for the next start, whether or not that read succeeded.
     *
     * A failed read (or an underlay that vanished after the credentials were opened) stashes
     * nothing, so without this sentinel the following [startInitialNetworkRuntime] cannot tell
     * "no preflight ran" from "the preflight ran and carried nothing" and would re-open the
     * credentials in the no-tunnel gap after the predecessor was already retired. Consumed (and
     * cleared) once, under the same lock as [chainedPreflight] and [chainedPreflightCredentials];
     * while it is set, a missing credential carry falls straight back to DNS-only.
     */
    private var chainedPreflightAttempted = false

    override fun start() {
        captureCanaryLaunch()
        lock.withLock {
            // A successor already owns the interface; starting would establish a TUN that
            // deactivates it. The latch is the same one every later entry point checks.
            if (superseded) return
            check(!started && !closed) { "VPN runtime can only start once" }
            started = true
        }
        val attachment = request.attachment as? CredentialVpnRuntimeAttachment
            ?: throw VpnRuntimeStartException(ProtectionFailure.DependencyUnavailable)
        runtimeAttachment = attachment
        val network = currentEligibleNetwork()
        // The preflight may have reached the credential read while no underlay was available (or the
        // underlay vanished mid-lookup) and therefore stashed nothing. If a network has since
        // appeared, route through awaiting-network recovery so the preflight re-runs against it and
        // the chained attempt is not silently downgraded to a permanent DNS-only runtime with no
        // later retry. [armNetworkAvailability] immediately launches a recovery pass to close the
        // select/register race.
        val preflightNeedsNetwork = lock.withLock {
            chainedPreflight is ChainedPreflightState.NetworkUnavailable
        }
        // The helper expects `eligibleNetworkAvailable` (a network is in hand), NOT its inverse:
        // passing `network == null` made every start with an available network enter
        // awaiting-network recovery, so no TUN was installed until an availability callback ran.
        val requiresAwaitingNetwork =
            chainedStartRequiresAwaitingNetwork(network != null, preflightNeedsNetwork)
        // The explicit `network == null ||` lets the compiler smart-cast `network` below; the pure
        // gate decides the preflight-network-unavailable case even when a network is available now.
        if (network == null || requiresAwaitingNetwork) {
            lock.withLock { awaitingNetwork = true }
            armNetworkAvailability()
            publishProtection(
                ProtectionState.AwaitingNetwork(
                    origin = request.origin,
                    generation = request.owner.sessionGeneration,
                ),
            )
            scheduleCurrentPauseExpiry()
            return
        }
        startInitialNetworkRuntime(network)
    }

    /**
     * Opens the chained credentials and resolves the peer endpoint while the predecessor still owns
     * its tunnel.
     *
     * The command owner retires the predecessor before [start], so a hostname lookup performed in
     * [start] would run with no tunnel installed; a slow or timed-out resolver would then let
     * device traffic bypass protection on non-lockdown devices. The opened credentials are carried
     * into [startInitialNetworkRuntime] for the same reason, so the start does not perform a second
     * (slow, hardware-backed) Keystore read in that gap. This is best-effort: any refusal (no
     * chaining, no eligible network, unresolvable host) leaves [start] to fall back to the unchanged
     * DNS-only path.
     */
    override suspend fun preflight() {
        captureCanaryLaunch()
        if (lock.withLock { started || closed }) return
        preflightChainedEndpoint()
    }

    /**
     * Opens the chained credentials and resolves the peer endpoint, stashing the outcome for the
     * next [startInitialNetworkRuntime].
     *
     * [preflight] runs this before the command owner's start; the runtime's own recovery paths
     * ([retryAwaitingNetwork] and [performReconnectSerialized]) run it directly, because they reach
     * [startInitialNetworkRuntime] without a command-owner preflight. Without it a hostname endpoint
     * is [ChainedPeerPreflightOutcome.NOT_ATTEMPTED] and the admission gate refuses it, silently
     * degrading chaining to DNS-only with no later attempt. Reusing the same bounded resolution
     * loop keeps the stashed `(network, address)` pair internally consistent when the underlay
     * changes mid-lookup, and discards it entirely when the underlay disappears so a stale address
     * is never handed to start. Reaching the credential read always sets [chainedPreflightAttempted],
     * so a failed read is distinguishable from "no preflight ran" and the next start does not
     * re-open the credentials in the no-tunnel gap.
     *
     * @return the network the stashed outcome is keyed to, or `null` when no chained attempt was
     *   possible (chaining off, no eligible network, or the underlay vanished mid-lookup)
     *   and nothing was stashed.
     */
    private suspend fun preflightChainedEndpoint(): SelectedVpnNetwork? {
        if (lock.withLock { closed }) return null
        val attachment = request.attachment as? CredentialVpnRuntimeAttachment ?: return null
        runtimeAttachment = attachment
        // Record the attempt as soon as it reaches the credential read. This is the slow
        // hardware-backed step the carry exists to keep out of the no-tunnel gap, so a start that
        // follows a failed read must not mistake the empty stash for "no preflight ran" and re-open
        // it. Set under the lock and only while open, so a close cannot resurrect the intent.
        val open = lock.withLock {
            if (closed) return@withLock false
            chainedPreflightAttempted = true
            true
        }
        if (!open) return null
        val credentials = runCatching { chainedCredentialsForInitialRuntime() }.getOrNull() ?: return null
        // Ownership of `credentials` is transferred to `chainedPreflightCredentials` only on a
        // successful stash. Every other exit zeroizes it here — a refusal, a vanished underlay, a
        // close that wins the race, and (critically) cancellation of the suspending lookup, which
        // would otherwise leak the opened key material.
        var transferred = false
        try {
            val endpointHost = credentials.configuration.transportConfiguration.endpointHostWireForm
            val endpointPort = credentials.configuration.transportConfiguration.endpointPort
            // No eligible underlay to resolve against. Record the outcome (rather than leaving the
            // empty stash indistinguishable from "no preflight ran") so the following start enters
            // awaiting-network recovery and re-runs the preflight when a network appears, instead of
            // consuming it as a permanent DNS-only decision.
            val initialNetwork = runCatching { currentEligibleNetwork() }.getOrNull()
            if (initialNetwork == null) {
                lock.withLock {
                    if (!closed) chainedPreflight = ChainedPreflightState.NetworkUnavailable
                }
                return null
            }
            var network: SelectedVpnNetwork = checkNotNull(initialNetwork) {
                "the preflight requires an eligible network"
            }
            // Resolve against a stable snapshot. If the selected network or its link properties
            // change during the lookup, re-resolve on the fresh snapshot so the stashed
            // `(network, address)` pair is internally consistent; start only reuses the address when
            // the network it selected still matches. Bounded, because an underlay that keeps
            // changing cannot be preflighted and start then falls back to DNS-only. A vanished or
            // unstable underlay discards the snapshot entirely rather than reusing a network that is
            // no longer the current one.
            var address: InetSocketAddress? = null
            var retries = 0
            while (true) {
                val probe = network
                address = resolveChainedPeer(probe, endpointHost, endpointPort)
                val current = runCatching { currentEligibleNetwork() }.getOrNull()
                when (
                    chainedEndpointPreflightResolution(
                        networkAvailable = current != null,
                        // Structural, not referential: each `currentEligibleNetwork()` constructs a fresh
                        // `SelectedVpnNetwork`, so `===` would never match and every preflight
                        // would burn its whole retry budget re-resolving an unchanged underlay.
                        sameNetwork = current == probe,
                        retries = retries,
                        maxRetries = MAX_PREFLIGHT_RESOLUTION_RETRIES,
                    )
                ) {
                    ChainedEndpointPreflightResolution.RESOLVED -> break
                    ChainedEndpointPreflightResolution.RE_RESOLVE -> {
                        // Only produced for a non-null snapshot that differs from the probe.
                        network = checkNotNull(current) { "re-resolve requires an eligible network" }
                        retries++
                    }

                    ChainedEndpointPreflightResolution.UNDERLAY_LOST -> {
                        // The underlay vanished or never settled, so the address resolved on `probe`
                        // belongs to a network that is not the current one and must not be stashed
                        // or handed to start. Record the network-unavailable outcome so start enters
                        // the awaiting-network path and re-runs this preflight once a network
                        // returns; this exit releases the credentials via the finally below.
                        lock.withLock {
                            if (!closed) chainedPreflight = ChainedPreflightState.NetworkUnavailable
                        }
                        return null
                    }
                }
            }
            var supersededCredentials: ChainedVpnCredentials? = null
            val stashed = lock.withLock {
                if (closed) return@withLock false
                supersededCredentials = chainedPreflightCredentials
                chainedPreflight = when (val resolved = address) {
                    null -> ChainedPreflightState.Refused(network)
                    else -> ChainedPreflightState.Resolved(
                        network = network,
                        hostWireForm = endpointHost,
                        port = endpointPort,
                        address = resolved,
                    )
                }
                chainedPreflightCredentials = credentials
                true
            }
            runCatching { supersededCredentials?.close() }
            if (!stashed) return null
            transferred = true
            return network
        } finally {
            if (!transferred) runCatching { credentials.close() }
        }
    }

    /**
     * Resolves the chained peer on an I/O dispatcher.
     *
     * [preflightChainedEndpoint] can be reached from a service callback (an explicit reconnect runs
     * on the main thread) as well as from the command owner's IO scope; target SDK 36 raises
     * `NetworkOnMainThreadException` for `Network.getAllByName` on the main thread, which
     * [ChainedEndpointResolver] would swallow as a refusal and silently downgrade hostname
     * upstreams to DNS-only. Suspending on [Dispatchers.IO] keeps the lookup off the main thread
     * and under structured cancellation: the caller's coroutine can be cancelled (a Stop, a newer
     * command) while the lookup is in flight, and the wrapped coroutine unwinds as soon as the
     * blocking resolver returns. The lookup is never performed on [engineQueue].
     */
    private suspend fun resolveChainedPeer(
        network: SelectedVpnNetwork,
        endpointHost: String,
        endpointPort: Int,
    ): InetSocketAddress? = withContext(Dispatchers.IO) {
        ChainedEndpointResolver.resolve(network.network, endpointHost, endpointPort)
    }

    private fun startInitialNetworkRuntime(network: SelectedVpnNetwork) {
        // The commit — and only the commit — runs as a new confined operation: the preflight above
        // stays off the engine queue so its slow resolver never delays stop teardown, while the
        // route-claiming establishment below is serialized with every other transition and gated on
        // this operation's generation. A nested call (awaiting-network recovery, an internal
        // reconnect) reuses its enclosing operation's token.
        engineQueue.run { generation ->
            startInitialNetworkRuntimeConfined(network, generation)
        }
    }

    private fun startInitialNetworkRuntimeConfined(
        network: SelectedVpnNetwork,
        generation: Long,
    ) {
        // A superseded predecessor must not claim a new interface: its queued recovery can re-mint
        // an engine-queue token, so the generation fence alone is not enough.
        if (!lifecycleAdmitted()) return
        if (!engineQueue.isEstablishAllowed(generation)) return
        // A preflight that reached the credential read with no eligible underlay stashed a
        // NetworkUnavailable marker. This commit point has a network in hand, so re-enter
        // awaiting-network recovery and re-run the preflight against it rather than consuming the
        // empty stash as a permanent DNS-only decision. This covers the internal reconnect/recovery
        // paths that reach here without going through `start`.
        if (lock.withLock { chainedPreflight is ChainedPreflightState.NetworkUnavailable }) {
            lock.withLock { awaitingNetwork = true }
            armNetworkAvailability()
            publishCurrentRuntimeState()
            return
        }
        lock.withLock {
            awaitingNetwork = false
            // A normal start honours the durable gate; it is not a deferred fallback.
            pendingDnsOnlyRebuild = false
        }
        selected = network

        // Consume the preflight artifacts under one lock: the peer address is only valid for the
        // network it was resolved on, and the credentials opened by the preflight are transferred to
        // this start so the post-retirement gap does not perform a second Keystore read.
        var preflight: ChainedPreflightState? = null
        var carriedCredentials: ChainedVpnCredentials? = null
        var preflightAttempted = false
        lock.withLock {
            preflight = chainedPreflight.also { chainedPreflight = null }
            carriedCredentials = chainedPreflightCredentials.also { chainedPreflightCredentials = null }
            preflightAttempted = chainedPreflightAttempted.also { chainedPreflightAttempted = false }
        }
        val preflightState = preflight
        val outcome = when {
            preflightState is ChainedPreflightState.Refused && preflightState.network == network ->
                ChainedPeerPreflightOutcome.REFUSED

            preflightState is ChainedPreflightState.Resolved && preflightState.network == network ->
                ChainedPeerPreflightOutcome.RESOLVED

            else -> ChainedPeerPreflightOutcome.NOT_ATTEMPTED
        }
        // Chained mode is a different TUN and a different driver. It is attempted only when the
        // credential attachment hands over gate-cleared credentials (readiness + stored
        // configuration + account/device eligibility + readable key half). Pause controls
        // filtering independently of the admitted VPN transport. Any refusal or
        // establishment failure releases what was taken and falls through to the unchanged
        // DNS-only body below, so filtering is never lost to a chained start failure. The preflight
        // already opened the credentials while the predecessor owned its tunnel, so only a start
        // that never ran a preflight re-opens here. A start whose preflight reached the credential
        // read but carried nothing (a failed read or a vanished underlay) must NOT re-open: that read
        // is the slow hardware-backed step the carry exists to skip, and repeating it after the
        // predecessor was retired leaves a no-tunnel gap (for an IP literal it would even admit the
        // chained attempt).
        val credentials = when {
            carriedCredentials != null -> carriedCredentials
            preflightAttempted -> null
            else -> chainedCredentialsForInitialRuntime()
        }
        val resolutionRequired = credentials
            ?.configuration
            ?.endpointHostWireForm
            ?.let { !ChainedUpstreamConfigurationValidator.isIPLiteral(it) }
            ?: false
        if (
            !chainedAttemptAdmittedAfterPreflight(outcome, resolutionRequired) ||
            (preflightAttempted && credentials == null)
        ) {
            // The peer preflight either ran and failed, reached the credential read but carried
            // nothing, or never ran for a hostname. Re-resolving
            // (or re-opening the credentials) after the predecessor is retired would leave a
            // no-tunnel gap, so fall straight back to DNS-only; an IP literal that was never
            // preflighted needs no lookup and may still be admitted.
            runCatching { credentials?.close() }
            startDnsOnlyNetworkRuntime(network, generation)
            return
        }
        val matchingPreflight = (preflightState as? ChainedPreflightState.Resolved)
            ?.takeIf { it.network == network }

        if (credentials != null) {
            if (startChainedRuntime(network, credentials, matchingPreflight, generation)) return
            runCatching { credentials.close() }
            // A terminal chained failure during start may have already closed this runtime; do
            // not build a DNS engine on a closed owner.
            if (!engineQueue.isEstablishAllowed(generation)) return
        }

        startDnsOnlyNetworkRuntime(network, generation)
    }

    /**
     * Installs the DNS-only runtime against [network], fencing the DNS engine to a new network
     * generation when the underlay changed.
     *
     * Used by the immediate chained-surrender fallback and by the deferred rebuild once a network
     * returns; it clears the pending-DNS-only intent so a normal start cannot inherit it.
     */
    private fun installDnsOnlyRuntime(network: SelectedVpnNetwork, operationGeneration: Long) {
        // The surrender/awaiting-network recovery restores DNS-only for THIS runtime; once a
        // successor owns the interface that rebuild would replace the successor's tunnel.
        if (!lifecycleAdmitted()) return
        if (!engineQueue.isEstablishAllowed(operationGeneration)) return
        val generation = ChainedSurrenderNetworkPolicy.dnsOnlyRebuildGeneration(
            networkAvailable = true,
            sameUnderlyingNetwork = lock.withLock { network == selected },
            currentGeneration = lock.withLock { networkGeneration },
        ) ?: return
        lock.withLock {
            selected = network
            networkGeneration = generation
            awaitingNetwork = false
            pendingDnsOnlyRebuild = false
        }
        startDnsOnlyNetworkRuntime(network, operationGeneration)
    }

    /**
     * Builds the unchanged DNS-only TUN and DNS engine.
     *
     * Used for a normal start with no chained credentials, as the fall-through when chained
     * establishment refuses, and to rebuild after a chained outage surrender. The caller has
     * already set [selected] and cleared `awaitingNetwork`; no DNS runtime is live.
     */
    private fun startDnsOnlyNetworkRuntime(network: SelectedVpnNetwork, operationGeneration: Long) {
        // The engine queue is the single authority on "is this operation still current": a Stop or
        // a newer Start bumps the generation before submitting its own operation, so an in-flight
        // establish aborts and releases rather than claiming the interface. This is checked on the
        // engine-queue thread immediately before the claim, which is what makes the check atomic
        // with respect to every other attempt (no second establish can be running).
        if (!lifecycleAdmitted()) return
        if (!engineQueue.isEstablishAllowed(operationGeneration)) return
        val attachment = runtimeAttachment
        val networkContext = NetworkContext(
            network = network.network,
            networkGeneration = networkGeneration,
            dnsServers = network.dnsServers,
            isCellular = network.isCellular,
            isWifi = network.isWifi,
            portalNetworkIsCurrent = { runCatching { currentEligibleNetwork() == network }.getOrDefault(false) },
        )
        val resolverPlan = when (
            val plan = ResolverPlanFactory.build(attachment.resolverConfiguration, networkContext)
        ) {
            is ResolverPlanBuildResult.Ready -> plan.plan
            is ResolverPlanBuildResult.Unavailable ->
                throw VpnRuntimeStartException(ProtectionFailure.EngineUnavailable)
        }
        // Re-check immediately before claiming routes: establishment installs the TUN and its
        // routes are live before any driver exists, so a supersede that landed while the plan was
        // being built must abort here rather than claim them.
        if (!engineQueue.isEstablishAllowed(operationGeneration)) return
        val packetSession = when (
            val establishment = AndroidTunPacketSessionFactory(service, PRODUCT_LABEL).establish(
                underlyingNetwork = network.network,
                configureIntent = attachment.configureIntent,
            )
        ) {
            is TunEstablishmentResult.Established -> establishment.session
            is TunEstablishmentResult.Failed ->
                throw VpnRuntimeStartException(ProtectionFailure.EstablishmentFailed)
        }
        // The claim itself is non-cancellable, so a supersede can land during `establish()`. The
        // interface is already claimed, so it must not be closed out from under the successor: the
        // queue resolves the claim atomically with the current-operation check and RETAINS a
        // superseded interface as the current tunnel, releasing it only when the successor's
        // establishment replaces it (the Android mapping of iOS's "the session stays until it is
        // replaced"). A closed queue refuses the claim, which is then ours to release.
        when (engineQueue.resolveRouteClaim(operationGeneration, packetSession)) {
            ChainedClaimOutcome.REFUSED -> {
                runCatching { packetSession.close() }
                return
            }

            ChainedClaimOutcome.RETAINED -> return

            ChainedClaimOutcome.INSTALLED -> Unit
        }
        val exactAttachment = ExactCredentialRuntimeAttachment(
            expectedServiceSessionGeneration = request.owner.sessionGeneration,
            expectedEngineGeneration = engineGeneration,
            host = request.host.host,
            attachment = attachment,
        )
        attachmentGate = exactAttachment
        val exactEpoch = ServiceRuntimeEpochGate(request.owner.sessionGeneration, engineGeneration)
        epochGate = exactEpoch
        val exactHealth = ExactSessionHealthOwner(
            sessionGeneration = request.owner.sessionGeneration,
            nowMillis = System::currentTimeMillis,
            publish = { status -> request.healthPublication.publish(status) },
            requestReconnect = { expectedEngine -> scheduleSelfReconnect(expectedEngine) },
        )
        healthOwner = exactHealth
        val observer = ProductionTunRuntimeObserver(
            serviceSessionGeneration = request.owner.sessionGeneration,
            graphGeneration = attachment.graphGeneration,
            engineGeneration = { lock.withLock { engineGeneration } },
            attachment = exactAttachment,
            publication = request.publication,
            epochGate = exactEpoch,
            health = exactHealth,
            onFailure = { failure -> lock.withLock { terminalFailure = failure } },
        )
        val bounded = BoundedTunRuntime(
            sessionToken = request.owner.sessionGeneration,
            packetSession = packetSession,
            observer = observer,
            dispatcher = Dispatchers.IO,
        )
        // Register the installed runtime atomically with a `closed`/current-generation check.
        // `supersede()` takes this same `lock`, so a newer Start/Reload cannot land between the
        // check and the registration: when it did land, the claimed interface is RETAINED (never
        // closed) so the successor replaces it without a gap, and the un-started driver is
        // abandoned rather than started behind the successor's back. `close()` snapshots the
        // runtime it will release, so a close that wins between the check above and this
        // registration leaves `runtime` unset (its teardown already ran) and this start must
        // release everything it created instead of installing a driver behind the teardown's back.
        val installed = lock.withLock {
            when {
                closed -> false
                !engineQueue.isEstablishAllowed(operationGeneration) -> {
                    engineQueue.retainRouteClaim(packetSession)
                    false
                }

                else -> {
                    runtime = bounded
                    true
                }
            }
        }
        if (!installed) {
            // A close owns the claim, so release the un-started driver and anchors. A supersession
            // already handed the session to the queue via `retainRouteClaim`, so the driver is
            // abandoned un-started and the queue releases the interface when a successor installs.
            if (lock.withLock { closed }) {
                bounded.requestClose()
                runBlocking { runCatching { bounded.join() } }
            }
            exactEpoch.close()
            exactAttachment.close()
            return
        }
        val initialEngine = try {
            createEngine(
                attachment = attachment,
                bounded = bounded,
                epochGate = exactEpoch,
                networkContext = networkContext,
                resolverPlan = resolverPlan,
                engineGeneration = engineGeneration,
            )
        } catch (failure: Throwable) {
            bounded.requestClose()
            runBlocking { runCatching { bounded.join() } }
            exactEpoch.close()
            exactAttachment.close()
            throw failure
        }
        try {
            bounded.start(initialEngine)
            exactHealth.installEngine(initialEngine.epoch.toHealthIdentity(resolverPlan.primaryResolverKind),
                devicePrimaryEpoch = initialEngine.epoch.takeIf { resolverPlan.primaryResolverKind == ResolverKind.DEVICE },
                primaryEpoch = initialEngine.epoch, internetValidated = false)
        } catch (failure: Throwable) {
            runCatching { initialEngine.close() }
            bounded.requestClose()
            exactEpoch.close()
            exactAttachment.close()
            throw failure
        }
        resolverSmokeProbes.install(initialEngine)
        completion = scope.launch {
            runCatching { bounded.join() }
            val terminal = lock.withLock {
                if (closed) return@launch
                closed = true
                ProductionRuntimeTerminalOwnership(
                    heartbeat = activityHeartbeat.also { activityHeartbeat = null },
                    networkRegistration = networkRegistration.also { networkRegistration = null },
                    engineGeneration = engineGeneration,
                    failure = terminalFailure,
                )
            }
            terminal.heartbeat?.cancel()
            resolverSmokeProbes.close()
            terminal.networkRegistration?.close()
            exactAttachment.acceptProtectionEvidence(
                VpnProtectionEvidence.State(
                    serviceSessionGeneration = request.owner.sessionGeneration,
                    engineGeneration = terminal.engineGeneration,
                    graphGeneration = request.attachment.graphGeneration,
                    observedAtMillis = System.currentTimeMillis().coerceAtLeast(0L),
                    active = false,
                ),
            )
            exactEpoch.close()
            exactAttachment.close()
            // The DNS runtime ended: the terminal publication both retires the exact service owner
            // and clears the chained mode under the command owner's monitor (see
            // `VpnServiceCommandOwner.clearChainedActivity`), so a completion that lost to a reload
            // cannot publish NONE over the replacement's mode. It is the last event this runtime
            // emits.
            request.terminalPublication.publish(
                this@ProductionVpnOwnedRuntime,
                terminal.failure,
            )
            retireDnsNetworkObservation()
            scope.cancel()
        }
        retireNetworkRegistration()
        observeRunningDnsNetwork(network)
        publishCurrentRuntimeState()
    }

    private fun chainedCredentialsForInitialRuntime(): ChainedVpnCredentials? = runCatching {
        runtimeAttachment.openChainedCredentials()
    }.getOrNull()

    /**
     * Starts the chained session and, on success, publishes the live protection state.
     *
     * Returns `false` without having taken ownership of [credentials] at any refusal, so the
     * caller can release them and fall back to DNS-only. `true` means the session is live and the
     * credentials now belong to the installed [ChainedTunRuntimeOwner]. [preflight] carries the
     * peer address resolved by [preflight] while the predecessor still owned its tunnel, so
     * [ChainedTunRuntimeOwner.establish] does not repeat the (slow) hostname lookup.
     */
    private fun startChainedRuntime(
        network: SelectedVpnNetwork,
        credentials: ChainedVpnCredentials,
        preflight: ChainedPreflightState.Resolved?,
        operationGeneration: Long,
    ): Boolean {
        // A superseded predecessor must not claim the chained TUN from under its successor.
        if (!lifecycleAdmitted()) return false
        if (!engineQueue.isEstablishAllowed(operationGeneration)) return false
        chainedStartup.retire()

        // The chained run shares the exact attachment/epoch anchors the DNS engine uses so
        // heartbeat and activity evidence keep flowing, but it installs no DNS engine generation.
        // Created before the engine so a failure here cannot leak an allocated chained owner; if a
        // concurrent close already latched `closed` and captured an empty anchor slot, creating them
        // now would leak them, so refuse and let the caller release the credentials.
        if (!ensureRuntimeAnchors()) return false

        val resolvedPeerAddress = preflight
            ?.takeIf {
                it.hostWireForm == credentials.configuration.transportConfiguration.endpointHostWireForm &&
                    it.port == credentials.configuration.transportConfiguration.endpointPort
            }
            ?.address

        // Pin the exact verified artifact before claiming the route. A refused mapping leaves
        // the predecessor intact and takes the ordinary DNS-only failure/fallback path.
        val anchors = lock.withLock {
            if (closed) return false
            Triple(engineGeneration, epochGate ?: return false, attachmentGate ?: return false)
        }
        val (exactGeneration, exactEpoch, exactAttachment) = anchors
        val policyAttachment = runtimeAttachment
        val savedDnsContext = NetworkContext(network.network, lock.withLock { networkGeneration },
            network.dnsServers, network.isCellular, isWifi = network.isWifi,
            portalNetworkIsCurrent = { runCatching { currentEligibleNetwork() == network }.getOrDefault(false) })
        val savedDnsPlan = if (credentials.dnsFallbackEnabled &&
            credentials.configuration.effectiveRoutingPolicy == ChainedRoutingPolicy.SPLIT_TUNNEL) {
            (ResolverPlanFactory.build(policyAttachment.resolverConfiguration, savedDnsContext) as? ResolverPlanBuildResult.Ready)?.plan
        } else null
        val policySource = try {
            val lease = policyAttachment.openFilterRuntimeLease()
            // A rejected duplicate alias never transfers ownership: closing it here would
            // retire the other runtime. After a successful claim, take owns all failure cleanup.
            lease.claimExclusiveRuntimeOwnership()
            ArtifactBackedProxyPolicySource.take(
                lease,
                ProxyPolicyControlSource {
                    ProxyPolicyControl(policyAttachment.activeProtectionSessionId, policyAttachment.currentPause())
                },
            )
        } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        }
        val dnsPolicy = ChainedDnsPolicy(
            source = policySource,
            identity = policySource.identity,
            ownedSource = policySource,
            eventIds = DecisionEventIdSource { PROCESS_EVENT_IDS.next() },
            clock = EpochMillisClock(System::currentTimeMillis),
            fence = ProxyPublicationFence { completion ->
                exactEpoch.completeIfCurrent(exactGeneration) {
                    engineQueue.isEstablishAllowed(operationGeneration) && completion()
                }
            },
            record = { activity, events, evidence ->
                healthOwner?.recordBatch(exactGeneration, events, evidence)
                val accepted = exactAttachment.tryAccept(
                    VpnTelemetry(
                        serviceSessionGeneration = request.owner.sessionGeneration,
                        engineGeneration = exactGeneration,
                        graphGeneration = policyAttachment.graphGeneration,
                        activity = activity,
                        healthEvents = events,
                        resolverQueryEvidence = evidence,
                    ),
                )
                if (!accepted) healthOwner?.recordPrivateTelemetryDrop(exactGeneration)
            },
        )
        val savedDnsFallback = try {
            savedDnsPlan?.let { plan ->
                SavedDnsChainedFallback(
                    epoch = EngineEpoch(request.owner.sessionGeneration, policySource.identity.configurationIdentity,
                        policySource.identity.artifactDigest, policySource.identity.revision,
                        savedDnsContext.networkGeneration, exactGeneration, plan.primaryResolverIdentity, plan.fallbackCoverage),
                    networkContext = savedDnsContext,
                    resolverPlan = plan,
                    resolverFactory = productionResolverFactory(service),
                    isCurrent = {
                        exactEpoch.completeIfCurrent(exactGeneration) {
                            engineQueue.isEstablishAllowed(operationGeneration)
                        }
                    },
                )
            }
        } catch (failure: Throwable) {
            runCatching { dnsPolicy.close() }
            throw failure
        }
        val owner = try {
            ChainedTunRuntimeOwner.establish(
                service = service,
                productLabel = PRODUCT_LABEL,
                network = network.network,
                configureIntent = runtimeAttachment.configureIntent,
                credentials = credentials,
                dnsPolicy = dnsPolicy,
                onDiagnostics = { healthOwner?.recordChainedDiagnostics(exactGeneration, it) },
                resolvedPeerAddress = resolvedPeerAddress,
                onTermination = { reportingOwner, termination ->
                    // If the runtime is already live, the termination belongs to the running session
                    // and is handled asynchronously once its owner identity is re-checked. Otherwise
                    // it raced the startup, which returns it from commit() and handles it
                    // synchronously so it cannot outlive the fallback. The gate only accepts the owner
                    // this attempt is gating, so a superseded owner's delayed callback is discarded.
                    if (chainedStartup.recordTermination(reportingOwner, termination)) {
                        onChainedTermination(reportingOwner, termination)
                    }
                },
                // The established owner must not claim the device interface when the engine queue has
                // superseded this operation (a Stop/onDestroy or a newer Start), because establishing
                // deactivates the predecessor's/newer candidate's TUN. The queue checks its own
                // generation immediately before and after the non-cancellable claim on the engine queue
                // thread, which no second attempt can be running on.
                engineQueue = engineQueue,
                operationGeneration = operationGeneration,
                // A replacement channel/rebuild must bind to the underlay the device is on NOW, not
                // the one captured at establishment (which may have gone away).
                currentNetwork = { currentEligibleNetwork()?.network },
                // Ownership transfers to the T0 egress on success and is consumed on every
                // establishment refusal/throw. Full mode never constructs this physical route.
                dnsFallback = savedDnsFallback,
                capturedResolverAddresses = { underlay ->
                    currentEligibleNetwork()?.takeIf { it.network == underlay }?.dnsServers
                        ?.mapNotNull { it.hostAddress }
                },
            )
        } catch (failure: Throwable) {
            runCatching { savedDnsFallback?.close() }
            runCatching { dnsPolicy.close() }
            throw failure
        }
        if (owner == null) {
            runCatching { dnsPolicy.close() }
            return false
        }

        // Bind the startup window to this attempt before it can start and report. No callback can
        // fire before the owner exists, so covering the start/commit window here is sufficient.
        chainedStartup.begin(owner)

        // Register the live owner atomically with a current-operation re-check. `establish`
        // allocates the TUN, the peer socket, and the ~50 MB engine over a slow window, and the
        // command owner starts the candidate outside the command monitor, so a concurrent
        // Stop/onDestroy can supersede this operation and capture an empty chained-owner slot
        // during it. Installing after that would leave a route-claiming owner the close never
        // captured and that keeps carrying traffic after protection reports Off. When the queue's
        // generation was superseded, retire the startup binding and release the owner so the caller's
        // fallback closes the credentials and builds DNS-only.
        val installed = lock.withLock {
            if (!engineQueue.isEstablishAllowed(operationGeneration)) {
                false
            } else {
                chainedOwners.install(owner)
                awaitingNetwork = false
                selected = network
                true
            }
        }
        if (!installed) {
            // The claimed TUN must not be torn down out from under the successor: hand it to the
            // queue as the retained current tunnel (or release it if the queue is already closed)
            // and release the rest of the owner. This is the chained path's mapping of the same
            // "session stays until replaced" rule the DNS path applies above.
            chainedStartup.retire()
            owner.handOffRouteClaim(engineQueue)
            return false
        }
        // The chained path installs no DNS engine, so unlike the DNS-only path it must create the
        // session-health owner here and report the chained lifecycle to it; otherwise the health
        // accumulator stays at its reset default (`isConnected = false`) and an otherwise Protected
        // session is classified RECONNECT_NEEDED with no Guard/resolver health.
        healthOwner = ExactSessionHealthOwner(
            sessionGeneration = request.owner.sessionGeneration,
            nowMillis = System::currentTimeMillis,
            publish = { status -> request.healthPublication.publish(status) },
            // Chained session recovery is driven by the chained engine's own retry/surrender
            // policy, not the DNS engine-generation self-reconnect.
            requestReconnect = { },
        )
        chainedHealthIdentity = chainedHealthEngineIdentity(credentials, savedDnsContext.networkGeneration,
            exactGeneration, savedDnsPlan?.let { ResolverPlanFactory.coverageFor(it.primaryResolverKind) }
                ?: ResolverFallbackCoverage.NONE)
        chainedHealthInstalled = false
        observeChainedState(owner)
        try {
            owner.start()
        } catch (failure: Throwable) {
            chainedStartup.retire()
            stopObservingChainedState()
            lock.withLock { chainedOwners.clear() }
            runCatching { owner.close() }
            return false
        }
        val raced = chainedStartup.commit(owner)
        if (raced != null) {
            // The failure raced the startup: close the owner synchronously before returning, so
            // the caller can build the DNS-only fallback without a scheduled teardown owning it.
            stopObservingChainedState()
            lock.withLock { chainedOwners.clear() }
            runCatching { owner.close() }
            return false
        }
        // The chained runtime has claimed the recovered network; the awaiting-network availability
        // callback must not stay registered for the whole chained session (every later capability
        // or link-properties update would launch a no-op retry).
        retireNetworkRegistration()
        // ...but a live chained session DOES need path transitions: a roam invalidates the socket
        // pinned to the old underlay, and the driver rebinds under the live session rather than
        // waiting for the silence detector. The tracker is seeded with the underlay the owner's
        // peer socket was actually pinned to (finding 4021623299).
        armChainedPathRegistration(owner)
        publishCurrentRuntimeState()
        return true
    }

    /**
     * Arms the default-network path callback for the live chained owner, replacing any previous
     * registration. Each transition is forwarded to the installed owner so the outage driver can
     * rebind (satisfied) or quiesce (unsatisfied). [owner] carries the underlay the
     * owner's peer socket was pinned to, used to seed the path tracker so the callback's first
     * snapshot is compared against the socket's actual network rather than treated as a baseline
     * (finding 4021623299).
     */
    private fun armChainedPathRegistration(owner: ChainedTunRuntimeOwner) {
        retireChainedPathRegistration()
        val gate = ChainedCaptureFloorRefreshGate(owner, owner.installedPlan.dnsCaptureFloor)
        lock.withLock { chainedFloorRefreshGate = gate }
        fun refreshIfChanged(): Boolean {
            val selectedNetwork = currentEligibleNetwork() ?: return false
            val addresses = selectedNetwork.dnsServers.mapNotNull { it.hostAddress }
            val observedFloor = ChainedDnsCaptureFloor.forCapturedResolvers(addresses)
            if (owner.installedPlan.dnsCaptureFloor.ipv4Routes.isEmpty() ||
                owner.installedPlan.dnsCaptureFloor.hasSameDestinations(observedFloor)
            ) return false
            return lock.withLock {
                if (closed || superseded || chainedOwners.currentOrNull() !== owner || chainedFloorRefreshGate !== gate) return false
                // An arbitrary/stale network notification has no authority. Recheck the actually
                // eligible snapshot at delivery; the new activation rechecks it again at TUN claim.
                val current = currentEligibleNetwork() ?: return false
                if (current.network != selectedNetwork.network ||
                    ChainedDnsCaptureFloor.forCapturedResolvers(current.dnsServers.mapNotNull { it.hostAddress }) != observedFloor
                ) return true
                val attempt = gate.claim(owner, observedFloor, SystemClock.elapsedRealtime()) ?: return true
                try {
                    requestRuntimeRebuild(request.owner.sessionGeneration)
                } catch (_: Exception) {
                    gate.rejected(attempt)
                }
                true
            }
        }
        fun refreshWithRetry() {
            lock.withLock {
                if (closed || superseded || chainedOwners.currentOrNull() !== owner || chainedFloorRefreshJob?.isActive == true) return
                // Service scheduling does not report adoption. Retry only while membership differs
                // and THIS predecessor remains current; healthy sessions have no polling task.
                val retry = scope.launch(start = CoroutineStart.LAZY) {
                    while (lifecycleAdmitted() && chainedOwners.currentOrNull() === owner) {
                        if (!refreshIfChanged()) break
                        delay(gate.retryAfterMillis)
                    }
                }
                chainedFloorRefreshJob = retry
                retry.start()
            }
        }
        val registration = runCatching {
            observeDefaultNetworkPath(owner.underlayNetwork) { satisfied, interfacesChanged ->
                val admitted = lock.withLock { !closed && !superseded && chainedOwners.currentOrNull() === owner }
                if (admitted) owner.pathChanged(satisfied, interfacesChanged)
                refreshWithRetry()
            }
        }.getOrNull()
        val registrationRetired = lock.withLock {
            if (closed || superseded || chainedOwners.currentOrNull() !== owner) true
            else { chainedPathRegistration = registration; false }
        }
        if (registrationRetired) {
            gate.retire()
            runCatching { registration?.close() }
            return
        }
        // Close a DNS-change race before registration delivered its initial callback.
        refreshWithRetry()
    }

    private fun retireChainedPathRegistration() {
        val registration = chainedPathRegistration
        chainedPathRegistration = null
        chainedFloorRefreshJob?.cancel()
        chainedFloorRefreshJob = null
        lock.withLock { chainedFloorRefreshGate?.retire(); chainedFloorRefreshGate = null }
        runCatching { registration?.close() }
    }

    /**
     * Handles a termination from a live chained runtime.
     *
     * A hard failure is terminal for protection; an outage surrender rebuilds the unchanged
     * DNS-only tunnel so filtering continues. The owner is claimed and the action applied as the
     * engine queue's newest operation, so a reconnect either replaces the owner first (making this
     * claim stale and a no-op) or waits for the action — the pending action can never operate on a
     * superseded state. The terminal publication runs outside the engine queue claim, because it
     * enters the service command owner's monitor and blocking the engine queue there would stall
     * every later lifecycle transition.
     */
    private fun onChainedTermination(
        reportingOwner: ChainedTunRuntimeOwner,
        termination: ChainedTunTermination,
    ) {
        scope.launch {
            var terminal: ProtectionFailure? = null
            chainedTermination.claimAndRun(
                candidate = reportingOwner,
                guard = { lock.withLock { !closed } },
            ) { operationGeneration ->
                chainedStartup.retire()
                retireChainedPathRegistration()
                // Stop observing the claimed owner BEFORE any rebuild: cancelling the collector
                // does not wait for an in-flight emission, and the DNS-only fallback's own mode
                // publication must not be overwritten by a late emission from the retired owner.
                stopObservingChainedState()
                // The chained session is ending; report disconnected so the health accumulator does
                // not keep an otherwise Protected session marked connected while it rebuilds or
                // falls back to DNS-only.
                publishChainedConnection(false)
                runCatching { reportingOwner.close() }
                when (ChainedTerminationPolicy.actionFor(termination)) {
                    ChainedTerminationAction.TERMINATE_PROTECTION -> {
                        // The policy only returns this for a hard failure, so the cast is an invariant.
                        terminal = chainedProtectionFailure(
                            (termination as ChainedTunTermination.Failed).failure,
                        )
                        // Latch the terminal shutdown while the engine queue is still held: the
                        // teardown below runs outside it, and a reconnect that acquired it in that
                        // gap would otherwise install a replacement the stale terminal transition
                        // then closes (retiring the service).
                        chainedTerminalShutdown.mark()
                    }

                    ChainedTerminationAction.REBUILD_DNS_ONLY -> {
                        // A surrender can be caused by a handoff/loss, so the network the chained
                        // session used may now be defunct; reselect before rebuilding DNS-only.
                        val network = currentEligibleNetwork()
                        if (network == null) {
                            // Retain the DNS-only intent: network recovery must complete this
                            // fallback, not reopen chained credentials.
                            lock.withLock {
                                awaitingNetwork = true
                                pendingDnsOnlyRebuild = true
                            }
                            armNetworkAvailability()
                            publishCurrentRuntimeState()
                        } else {
                            try {
                                installDnsOnlyRuntime(network, operationGeneration)
                            } catch (failure: Throwable) {
                                terminal = (failure as? VpnRuntimeStartException)?.failure
                                    ?: ProtectionFailure.DependencyUnavailable
                            }
                        }
                    }
                }
                // Clear the claimed owner's activity publication only when the action left no live
                // runtime. This runs INSIDE the engine queue, so a network-availability callback
                // that installs and publishes a DNS-only fallback cannot interleave between this
                // check and the publication: otherwise a stale NONE would overwrite DNS_ONLY and,
                // with no chained-state collector to correct it, the settled fallback would render
                // as Recovering indefinitely.
                val clear = lock.withLock {
                    shouldClearChainedActivityOnClaim(
                        hasChainedOwner = chainedOwners.currentOrNull() != null,
                        hasDnsOnlyRuntime = runtime != null,
                    )
                }
                if (clear) publishChainedActivity(false, ChainedRuntimeMode.NONE, 0L)
            }
            terminal?.let(::terminateRuntime)
        }
    }

    private fun closeChainedForRebuild() {
        retireChainedPathRegistration()
        val owner = lock.withLock { chainedOwners.take() } ?: return
        chainedStartup.retire()
        stopObservingChainedState()
        publishChainedConnection(false)
        publishChainedActivity(false, ChainedRuntimeMode.NONE, 0L)
        runCatching { owner.close() }
    }

    /**
     * Creates the exact attachment/epoch anchors once for this service session.
     *
     * The DNS path creates them unconditionally while installing its engine; the chained path
     * installs no engine, so it creates them lazily (and reuses them across a chained rebuild)
     * to keep the attachment evidence and the epoch fence live.
     *
     * @return `false` when the runtime is already closed: `close()` has latched `closed` and
     *   captured an empty anchor slot, so creating them now would leak them past the teardown that
     *   already ran. The caller must abort rather than allocate the chained engine.
     */
    private fun ensureRuntimeAnchors(): Boolean = lock.withLock {
        if (closed) return@withLock false
        if (attachmentGate == null) {
            attachmentGate = ExactCredentialRuntimeAttachment(
                expectedServiceSessionGeneration = request.owner.sessionGeneration,
                expectedEngineGeneration = engineGeneration,
                host = request.host.host,
                attachment = runtimeAttachment,
            )
        }
        if (epochGate == null) {
            epochGate = ServiceRuntimeEpochGate(request.owner.sessionGeneration, engineGeneration)
        }
        true
    }

    private fun chainedProtectionFailure(failure: ChainedRuntimeFailure): ProtectionFailure =
        when (failure) {
            ChainedRuntimeFailure.TUN_READ_FAILED,
            ChainedRuntimeFailure.TUN_WRITE_FAILED,
            -> ProtectionFailure.TunnelReadFailed

            ChainedRuntimeFailure.PEER_SOCKET_FAILED,
            ChainedRuntimeFailure.ENGINE_UNAVAILABLE,
            ChainedRuntimeFailure.ENGINE_FAILED,
            // AUTHORIZATION_REFUSED is policy-mapped to the DNS-only rebuild, so it never reaches
            // this terminal mapping; the branch keeps the `when` exhaustive.
            ChainedRuntimeFailure.AUTHORIZATION_REFUSED,
            -> ProtectionFailure.TunnelEnded
        }

    override fun pause(duration: PauseDuration) {
        val pause = runtimeAttachment.requestPause(duration)
            ?: throw VpnRuntimeStartException(ProtectionFailure.PersistenceUnavailable)
        publishProtection(ProtectionState.Paused(pause.deadlineMillis))
        schedulePauseExpiry(pause)
        // DNS policy reads the durable pause on each admission/delivery. The installed
        // interface, WireGuard session and recovery accounting remain authoritative.
    }

    override fun resume() {
        if (!runtimeAttachment.requestResume()) {
            throw VpnRuntimeStartException(ProtectionFailure.PersistenceUnavailable)
        }
        cancelPauseExpiry()
        publishCurrentRuntimeState()
    }

    override suspend fun reconnect() {
        performReconnect(expectedEngineGeneration = null)
    }

    /**
     * Preflights the chained endpoint OUTSIDE [engineQueue], then runs the serialized reconnect.
     *
     * The lookup can be slow, and the engine queue is the serial owner Stop/close teardown also
     * uses; holding it across the lookup would delay a stop's teardown until the resolver finished.
     * Running the preflight before submitting the confined commit keeps the serial owner free of
     * I/O. The confined body re-validates `closed`/generation and aborts rather than starting, so a
     * stop that wins the race is honoured; a state change during the lookup degrades to the
     * unchanged DNS-only path.
     */
    private suspend fun performReconnect(expectedEngineGeneration: Long?) {
        if (chainedTerminalShutdown.isPending()) return
        // Serialize the preflight with its commit: an explicit reconnect and awaiting-network
        // recovery both write the runtime's single preflight slot, so allowing their commits to
        // interleave lets one consume the other's stashed address/credentials and then race the
        // replacement to DNS-only. The gate also keeps the slow lookup off [engineQueue].
        chainedRecovery.run {
            val needsInitialRuntime = lock.withLock { started && !closed && runtime == null }
            if (needsInitialRuntime) {
                // Open the credentials and resolve the endpoint while the predecessor still owns its
                // tunnel. The stashed result is only reused when the network re-selected at commit
                // time still matches it (the serialized body re-selects), so a handoff during the
                // lookup is revalidated rather than trusted.
                preflightChainedEndpoint()
            }
            engineQueue.run { operationGeneration ->
                performReconnectSerialized(expectedEngineGeneration, operationGeneration)
            }
        }
    }

    private fun performReconnectSerialized(
        expectedEngineGeneration: Long?,
        operationGeneration: Long,
    ) {
        // A superseded predecessor must neither rebuild nor tear down its retained tunnel for
        // make-before-break; the successor owns the interface. Checked BEFORE closeChainedForRebuild
        // so a queued reconnect cannot close the tunnel the successor is about to replace.
        if (!lifecycleAdmitted()) return
        // A claimed hard chained failure owns the teardown; it runs outside this engine queue, so
        // a reconnect that acquires it in that gap must not install a replacement the stale terminal
        // transition would then close (retiring the service).
        if (chainedTerminalShutdown.isPending()) return
        // A stop can win the race while the preflight ran outside the engine queue: do not start a
        // runtime on a superseded/closed owner.
        if (!engineQueue.isEstablishAllowed(operationGeneration)) return
        val needsInitialRuntime = lock.withLock {
            if (!started || closed) return
            runtime == null
        }
        // A chained session does not participate in the DNS engine-generation reconnect path.
        // Tear it down (without detaching the shared attachment) and rebuild from durable
        // authority, so the next start re-reads the gate and re-opens credentials. An explicit
        // reconnect also abandons any deferred DNS-only fallback and re-evaluates the gate.
        closeChainedForRebuild()
        lock.withLock { pendingDnsOnlyRebuild = false }
        if (needsInitialRuntime) {
            // Re-select the underlay at commit time: the preflight resolved on the snapshot it
            // observed, and the network can change or vanish after its final check. Passing the
            // fresh snapshot makes startInitialNetworkRuntime refuse a mismatched stashed preflight
            // (hostname -> DNS-only) instead of binding the replacement to a dead underlay.
            val initialNetwork = currentEligibleNetwork()
            if (initialNetwork == null) {
                lock.withLock { awaitingNetwork = true }
                armNetworkAvailability()
                publishCurrentRuntimeState()
                return
            }
            startInitialNetworkRuntime(initialNetwork)
            return
        }
        val bounded = lock.withLock {
            // A supersede can land after the initial check above; abort instead of asserting on a
            // runtime that is being torn down.
            if (!started || closed) return
            if (
                expectedEngineGeneration != null &&
                engineGeneration != expectedEngineGeneration
            ) {
                return
            }
            checkNotNull(runtime)
        }
        val priorNetwork = checkNotNull(selected)
        val network = currentEligibleNetwork()
        if (network == null) {
            lock.withLock { awaitingNetwork = true }
            armNetworkAvailability()
            publishCurrentRuntimeState()
            return
        }
        val nextGeneration = lock.withLock { Math.addExact(engineGeneration, 1L) }
        val nextNetworkGeneration = lock.withLock {
            if (network == priorNetwork) networkGeneration else Math.addExact(networkGeneration, 1L)
        }
        val networkContext = NetworkContext(
            network.network,
            nextNetworkGeneration,
            network.dnsServers,
            network.isCellular,
            isWifi = network.isWifi,
            portalNetworkIsCurrent = { runCatching { currentEligibleNetwork() == network }.getOrDefault(false) },
        )
        val resolverPlan = when (
            val plan = ResolverPlanFactory.build(runtimeAttachment.resolverConfiguration, networkContext)
        ) {
            is ResolverPlanBuildResult.Ready -> plan.plan
            is ResolverPlanBuildResult.Unavailable ->
                throw VpnRuntimeStartException(ProtectionFailure.EngineUnavailable)
        }
        val next = createEngine(
            attachment = runtimeAttachment,
            bounded = bounded,
            epochGate = checkNotNull(epochGate),
            networkContext = networkContext,
            resolverPlan = resolverPlan,
            engineGeneration = nextGeneration,
        )
        // A supersede can land during the engine allocation above; close the fresh engine and abort
        // rather than claim a generation on a superseded/closed owner.
        if (!engineQueue.isEstablishAllowed(operationGeneration)) {
            runCatching { next.close() }
            return
        }
        try {
            if (!service.setUnderlyingNetworks(arrayOf(network.network))) {
                throw VpnRuntimeStartException(ProtectionFailure.NetworkUnavailable)
            }
            lock.withLock { engineGeneration = nextGeneration }
            checkNotNull(attachmentGate).installEngine(nextGeneration)
            checkNotNull(epochGate).installEngine(nextGeneration)
            checkNotNull(healthOwner).installEngine(next.epoch.toHealthIdentity(resolverPlan.primaryResolverKind),
                devicePrimaryEpoch = next.epoch.takeIf { resolverPlan.primaryResolverKind == ResolverKind.DEVICE },
                primaryEpoch = next.epoch, internetValidated = false)
        } catch (failure: Throwable) {
            runCatching { next.close() }
            throw failure
        }
        val installed = runBlocking { bounded.replaceEngine(next) }
        if (!installed) throw VpnRuntimeStartException(ProtectionFailure.EngineUnavailable)
        lock.withLock {
            selected = network
            networkGeneration = nextNetworkGeneration
            awaitingNetwork = false
        }
        resolverSmokeProbes.install(next)
        retireNetworkRegistration()
        lock.withLock { dnsNetworkObservation }?.let { observation ->
            healthOwner?.recordInternetValidation(nextGeneration, observation.internetValidated(network.network))
        }
        publishCurrentRuntimeState()
    }

    private fun currentEligibleNetwork(): SelectedVpnNetwork? {
        val observation = lock.withLock { dnsNetworkObservation }
        return if (observation == null) eligibleNetwork() else observation.snapshot()
    }

    /** One conflated consumer bounds callback storms and keeps refresh off the Android callback. */
    private fun observeRunningDnsNetwork(network: SelectedVpnNetwork) {
        if (lock.withLock { closed || superseded || dnsNetworkObservation != null }) return
        val observation = observeDnsNetworkChanges(network) { dnsNetworkChanges.trySend(Unit) }
        val admitted = lock.withLock {
            if (closed || superseded || dnsNetworkObservation != null) false
            else { dnsNetworkObservation = observation; true }
        }
        if (!admitted) { observation.close(); return }
        healthOwner?.recordInternetValidation(engineGeneration, observation.internetValidated(network.network))
        val job = scope.launch {
            for (ignored in dnsNetworkChanges) {
                delay(50L)
                while (dnsNetworkChanges.tryReceive().isSuccess) { /* coalesce */ }
                if (!lifecycleAdmitted()) break
                try {
                    chainedRecovery.run {
                        engineQueue.run refresh@ { generation ->
                            if (!lifecycleAdmitted() || !engineQueue.isEstablishAllowed(generation)) return@refresh
                            val installed = lock.withLock { runtime != null && chainedOwners.currentOrNull() == null }
                            if (!installed) return@refresh
                            val next = observation.snapshot()
                            // A validation-only callback updates presentation without replacing
                            // the DNS engine, changing resolver order or granting service credit.
                            healthOwner?.recordInternetValidation(engineGeneration,
                                next != null && next == lock.withLock { selected } &&
                                    observation.internetValidated(next.network))
                            if (next == lock.withLock { selected } && !lock.withLock { awaitingNetwork }) return@refresh
                            if (next == null && !lock.withLock { awaitingNetwork }) {
                                // Retire response authority immediately, retaining the interface
                                // until a coherent replacement underlay is available.
                                val epoch = lock.withLock { Math.addExact(engineGeneration, 1L).also { engineGeneration = it } }
                                attachmentGate?.installEngine(epoch)
                                epochGate?.installEngine(epoch)
                            }
                            try {
                                performReconnectSerialized(null, generation)
                            } catch (failure: VpnRuntimeStartException) {
                                if (failure.failure != ProtectionFailure.NetworkUnavailable) throw failure
                                // A handoff can reject binding the freshly allocated engine. Its
                                // allocation is already closed by performReconnectSerialized;
                                // retain this runtime and recover under the same serial authority.
                                if (lifecycleAdmitted() && engineQueue.isEstablishAllowed(generation)) {
                                    lock.withLock { awaitingNetwork = true }
                                    armNetworkAvailability()
                                    publishCurrentRuntimeState()
                                }
                            }
                        }
                    }
                } catch (failure: Throwable) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    if (lifecycleAdmitted()) terminateRuntime((failure as? VpnRuntimeStartException)?.failure
                        ?: ProtectionFailure.EngineUnavailable)
                }
            }
        }
        lock.withLock {
            if (closed || superseded) job.cancel() else dnsNetworkRefresh = job
        }
        dnsNetworkChanges.trySend(Unit) // closes the selection/registration race
    }

    private fun retireDnsNetworkObservation() {
        val owned = lock.withLock {
            val observation = dnsNetworkObservation.also { dnsNetworkObservation = null }
            val job = dnsNetworkRefresh.also { dnsNetworkRefresh = null }
            observation to job
        }
        owned.second?.cancel()
        owned.first?.close()
    }

    private fun armNetworkAvailability() {
        val shouldArm = lock.withLock {
            !closed && networkRegistration == null && awaitingNetwork
        }
        if (!shouldArm) return
        val registration = observeNetworkAvailability {
            scope.launch { retryAwaitingNetwork() }
        }
        val closeImmediately = lock.withLock {
            if (closed || !awaitingNetwork || networkRegistration != null) {
                true
            } else {
                networkRegistration = registration
                false
            }
        }
        if (closeImmediately) registration.close()
        // Closes the select/register race when a network became eligible between both operations.
        scope.launch { retryAwaitingNetwork() }
    }

    /**
     * Completes the awaiting-network recovery, preflighting the chained endpoint OUTSIDE
     * [engineQueue].
     *
     * Startup recovery did not run through the command owner, so it must preflight the chained
     * endpoint before starting the initial runtime; otherwise a hostname endpoint is refused as
     * NOT_ATTEMPTED and silently degrades to DNS-only with no later attempt. The slow lookup runs
     * outside the engine queue so a concurrent Stop never waits on the resolver.
     * RebuildDnsOnly and ReconnectRunningRuntime install no chained session and need no preflight.
     */
    private suspend fun retryAwaitingNetwork() {
        // Same preflight/commit serialization as [performReconnect]: the two recovery paths share the
        // runtime's single preflight slot, so their commits must not interleave.
        chainedRecovery.run {
            val preflightRequired = lock.withLock {
                if (!started || closed || !awaitingNetwork || chainedTerminalShutdown.isPending()) {
                    null
                } else {
                    val action = ChainedAwaitingNetworkPolicy.actionFor(
                        pendingDnsOnlyRebuild = pendingDnsOnlyRebuild,
                        hasRuntime = runtime != null,
                    )
                    ChainedAwaitingNetworkPolicy.requiresEndpointPreflight(action)
                }
            }
            if (preflightRequired != null) {
                if (preflightRequired) preflightChainedEndpoint()
                val terminal = engineQueue.run { operationGeneration ->
                    retryAwaitingNetworkSerialized(operationGeneration)
                }
                // Terminal publication may enter VpnServiceCommandOwner's monitor. Keep that call
                // outside the engine queue so a concurrent main-thread Stop/Reload cannot form an
                // AB-BA cycle (and so it never blocks the serial owner).
                terminal?.let(::terminateRuntime)
            }
        }
    }

    private fun retryAwaitingNetworkSerialized(operationGeneration: Long): ProtectionFailure? {
        // The availability callback can fire after a successor superseded this runtime; installing
        // here would replace the successor's adopted interface.
        if (!lifecycleAdmitted()) return null
        if (!engineQueue.isEstablishAllowed(operationGeneration)) return null
        val shouldRetry = lock.withLock {
            started && !closed && awaitingNetwork && !chainedTerminalShutdown.isPending()
        }
        if (!shouldRetry) return null
        val network = currentEligibleNetwork() ?: return null
        return try {
            val action = ChainedAwaitingNetworkPolicy.actionFor(
                pendingDnsOnlyRebuild = lock.withLock { pendingDnsOnlyRebuild },
                hasRuntime = lock.withLock { runtime != null },
            )
            when (action) {
                ChainedAwaitingNetworkAction.RebuildDnsOnly ->
                    installDnsOnlyRuntime(network, operationGeneration)
                ChainedAwaitingNetworkAction.StartInitialRuntime -> {
                    // Re-select the underlay at commit time: the preflight may have resolved on a
                    // network that changed or vanished after its final check, and starting on the
                    // stale one would bind a tunnel to a dead underlay. A stashed preflight that no
                    // longer matches is refused by startInitialNetworkRuntime (hostname ->
                    // DNS-only).
                    val startNetwork = currentEligibleNetwork()
                    if (startNetwork == null) {
                        lock.withLock { awaitingNetwork = true }
                        publishCurrentRuntimeState()
                    } else {
                        startInitialNetworkRuntime(startNetwork)
                    }
                }

                ChainedAwaitingNetworkAction.ReconnectRunningRuntime ->
                    performReconnectSerialized(
                        expectedEngineGeneration = null,
                        operationGeneration = operationGeneration,
                    )
            }
            null
        } catch (failure: Throwable) {
            val terminal = (failure as? VpnRuntimeStartException)?.failure
                ?: ProtectionFailure.DependencyUnavailable
            if (terminal == ProtectionFailure.NetworkUnavailable) {
                lock.withLock { awaitingNetwork = true }
                publishCurrentRuntimeState()
                null
            } else {
                terminal
            }
        }
    }

    private fun publishCurrentRuntimeState() {
        // The status surface consumes this so it never claims traffic is routed upstream while a
        // DNS-only fallback is carrying it or while the chained handshake is still pending, and it
        // can tell a settled DNS-only fallback (DNS_ONLY) from an in-progress chained startup
        // (CHAINED with active = false). The snapshot and the publication share one critical
        // section under the runtime lock, so a concurrent teardown that clears the owner and
        // publishes NONE cannot be overwritten by this path's stale chained mode.
        chainedStateFence.publishComputed {
            if (closed) {
                false to ChainedRuntimeMode.NONE
            } else {
                val owner = chainedOwners.currentOrNull()
                when {
                    owner != null -> owner.state.value.isEstablished to ChainedRuntimeMode.CHAINED
                    runtime != null -> false to ChainedRuntimeMode.DNS_ONLY
                    else -> false to ChainedRuntimeMode.NONE
                }
            }
        }
        when (val pause = runtimeAttachment.currentPause()) {
            RuntimePauseDirective.Filtering -> {
                cancelPauseExpiry()
                val active = lock.withLock {
                    (runtime != null || chainedOwners.currentOrNull() != null) &&
                        !closed && !awaitingNetwork
                }
                publishProtection(
                    if (active) {
                        ProtectionState.Protected(
                            runtimeAttachment.configurationIdentity,
                            platformFacts(service).ownership,
                        )
                    } else {
                        ProtectionState.AwaitingNetwork(
                            request.origin,
                            request.owner.sessionGeneration,
                        )
                    },
                )
            }
            is RuntimePauseDirective.Paused -> {
                publishProtection(ProtectionState.Paused(pause.state.deadlineMillis))
                schedulePauseExpiry(pause.state)
            }
        }
    }

    /** Exact activation and T0 resolver identity, plus independently consented saved-DNS coverage. */
    private fun chainedHealthEngineIdentity(
        credentials: ChainedVpnCredentials,
        capturedNetworkGeneration: Long,
        capturedEngineGeneration: Long,
        savedDnsCoverage: ResolverFallbackCoverage,
    ): VpnHealthEngineIdentity = VpnHealthEngineIdentity(
        sessionGeneration = request.owner.sessionGeneration,
        networkGeneration = capturedNetworkGeneration,
        engineGeneration = capturedEngineGeneration,
        primaryResolverIdentity = PrimaryResolverIdentity(
            "chained-upstream:${credentials.generation}:" +
                canonicalChainedUpstreamConfigurationFingerprint(credentials.configuration) + ":" +
                credentials.configuration.stackDNSAddresses.joinToString(";").ifBlank { "none" },
        ),
        // This declares the configured saved primary; an admitted mixed saved ladder can
        // deliver another actual category, which remains explicit in its terminal evidence.
        fallbackCoverage = savedDnsCoverage,
    )

    /**
     * Reports the chained session's connection fact through the shared health owner.
     *
     * The first `Established` observation installs the health engine identity (which publishes the
     * initial `ConnectionChanged(true)`); later transitions publish `ConnectionChanged(connected)`
     * for the installed generation. Without this the health accumulator stays at its reset default
     * (`isConnected = false`), so an otherwise Protected session is classified `RECONNECT_NEEDED`
     * and Guard/resolver health is unavailable.
     */
    private fun publishChainedConnection(connected: Boolean) {
        lock.withLock {
            if (closed) return
            val health = healthOwner ?: return
            val identity = chainedHealthIdentity ?: return
            if (chainedHealthInstalled) {
                health.recordConnectionChanged(identity.engineGeneration, connected)
            } else if (connected) {
                chainedHealthInstalled = true
                health.installEngine(identity)
            }
        }
    }

    /**
     * Observes the installed owner's engine lifecycle and republishes the activity/mode on every
     * change, so the status flips to "on" only when the handshake completes and back off when the
     * engine starts recovering. It also reports the session-health connection transitions.
     */
    private fun observeChainedState(owner: ChainedTunRuntimeOwner) {
        chainedStateJob?.cancel()
        chainedStateJob = scope.launch {
            owner.state
                .chainedActivityPublications()
                .collect { (active, mode) ->
                    // The identity check, the activity publication, and the health report run in ONE
                    // critical section under the runtime lock: cancelling the previous collector
                    // does not wait for an in-flight emission, so a retired owner's `Established`
                    // must not republish `true` after a replacement or DNS-only fallback cleared the
                    // slot, and the health side effect must not be applied to the replacement the
                    // reconnect installed in the gap between the check and the report.
                    chainedStateFence.publishIfCurrent(owner, active, mode) {
                        publishChainedConnection(active)
                    }
                }
        }
    }

    private fun stopObservingChainedState() {
        chainedStateJob?.cancel()
        chainedStateJob = null
    }

    private fun scheduleCurrentPauseExpiry() {
        val pause = runtimeAttachment.currentPause() as? RuntimePauseDirective.Paused ?: return
        schedulePauseExpiry(pause.state)
    }

    private fun schedulePauseExpiry(pause: com.lavasec.app.foundation.pause.PauseState) {
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            while (true) {
                val now = nowMillis().coerceAtLeast(0L)
                if (now >= pause.deadlineMillis) break
                delay(minOf(pause.deadlineMillis - now, pauseClockRecheckMillis))
            }
            val cleared = runCatching {
                runtimeAttachment.requestResume(expectedPause = pause)
            }.getOrDefault(false)
            if (!cleared) {
                val logicallyFiltering = runCatching {
                    runtimeAttachment.currentPause() === RuntimePauseDirective.Filtering
                }.getOrDefault(false)
                if (!logicallyFiltering) return@launch
            }
            lock.withLock { if (pauseExpiry === job) pauseExpiry = null }
            publishCurrentRuntimeState()
        }
        val previous = lock.withLock {
            pauseExpiry.also { pauseExpiry = job }
        }
        previous?.cancel()
        job.start()
    }

    private fun cancelPauseExpiry() {
        lock.withLock { pauseExpiry.also { pauseExpiry = null } }?.cancel()
    }

    private fun retireNetworkRegistration() {
        lock.withLock { networkRegistration.also { networkRegistration = null } }?.close()
    }

    private fun scheduleSelfReconnect(expectedEngineGeneration: Long) {
        scope.launch {
            val current = lock.withLock {
                !closed && engineGeneration == expectedEngineGeneration
            }
            if (!current) return@launch
            publishProtection(
                ProtectionState.Reconnecting(
                    origin = com.lavasec.app.platform.StartOrigin.Reload,
                    generation = request.owner.sessionGeneration,
                ),
            )
            try {
                performReconnect(expectedEngineGeneration)
            } catch (failure: Throwable) {
                terminateRuntime(
                    (failure as? VpnRuntimeStartException)?.failure
                        ?: ProtectionFailure.DependencyUnavailable,
                )
            }
        }
    }

    private fun terminateRuntime(failure: ProtectionFailure) {
        publishProtection(ProtectionState.Failed(failure))
        runCatching { close() }
        request.terminalPublication.publish(this, failure)
    }

    /**
     * Invalidates any in-flight lifecycle operation on this runtime (bumps the engine queue's
     * generation) without tearing anything down.
     *
     * The command owner calls this synchronously when it supersedes a pending candidate or stops
     * the installed runtime, so a candidate that is mid-establishment cannot complete its claim
     * after the decision to discard it. [close] also supersedes, so a caller that follows with a
     * close only bumps the generation twice, which is harmless.
     */
    override fun supersede() {
        // Take the runtime lock so supersession is atomic with the install paths' current-generation
        // re-checks. Without it a newer Start/Reload could land between a check and the registration
        // and let a stale post-establish operation register/start; with it the registration sees the
        // bumped generation and retains the claimed interface instead. The lock is only ever held
        // across short bookkeeping (never across establishment or the engine allocation), so the
        // service thread that calls this never blocks on a slow operation.
        //
        // The generation bump alone is not a sufficient fence: the queue mints a fresh token on
        // the next `run`, and the recovery entry points reject only `closed`, so a queued
        // reconnect/awaiting-network retry/termination recovery could still establish a TUN after
        // the successor adopted the interface. The monotonic [superseded] latch closes that; it is
        // never cleared, and it does not touch the installed tunnel (make-before-break).
        lock.withLock {
            superseded = true
            engineQueue.supersede()
        }
        resolverSmokeProbes.close()
        retireDnsNetworkObservation()
    }

    /**
     * Whether this runtime may still run a tunnel lifecycle operation.
     *
     * `false` once a successor has superseded it or it is closing. Every establishment entry point
     * consults this BEFORE doing any work, because Android's `establish()` replaces the active
     * interface: a superseded predecessor must retain its already-installed tunnel for
     * make-before-break but must never claim a new one from under the successor.
     */
    private fun lifecycleAdmitted(): Boolean = lock.withLock { !superseded && !closed }

    override fun close() {
        val owned = beginClose() ?: return
        resolverSmokeProbes.close()
        // `beginClose` has already latched `closed` AND the engine-queue generation under `lock`, so
        // every install path's atomic `isEstablishAllowed` re-check refuses to register a resource
        // this teardown would miss. Only the slow teardown is left, and it is submitted to the serial
        // owner so a slow chained JNI/engine join must never block the calling (service) thread,
        // which delivers the next Stop/Revoke/Start.
        engineQueue.enqueue { finishClose(owned) }
    }

    /**
     * Latches `closed` and captures the owned resources, and latches/supersedes the engine queue,
     * all under [lock] only.
     *
     * Latching the engine-queue generation under the SAME lock the install paths use for their final
     * `isEstablishAllowed` re-check is what makes the capture exact: if `beginClose` wins, a
     * concurrent install sees the bumped generation/closed latch and refuses to register; if the
     * install wins, `beginClose` captures what it registered.
     *
     * This deliberately does NOT wait for the engine queue's in-flight operation. [close] is called
     * synchronously from the service main thread (`ACTION_STOP`/`onDestroy`), and a confined
     * transition can hold the serial owner across [closeChainedForRebuild] and
     * [startInitialNetworkRuntimeConfined] — witness teardown, TUN setup, and a ~50 MB JNI
     * allocation — so waiting for it could ANR the stop. Every install path re-checks the
     * engine-queue generation atomically under this lock and self-cleans when it lost:
     * [startDnsOnlyNetworkRuntime] releases the packet session it just claimed, and
     * [startChainedRuntime] releases the owner it just established. The engine join stays in
     * [finishClose], which the engine queue runs after the in-flight operation releases its thread.
     */
    private fun beginClose(): ProductionRuntimeCloseOwnership? = lock.withLock {
        if (closed) return@withLock null
        closed = true
        engineQueue.close()
        ProductionRuntimeCloseOwnership(
            runtime = runtime,
            chained = chainedOwners.take(),
            attachment = attachmentGate,
            epoch = epochGate,
            heartbeat = activityHeartbeat,
            pauseExpiry = pauseExpiry,
            networkRegistration = networkRegistration,
            chainedPreflightCredentials = chainedPreflightCredentials.also {
                chainedPreflightCredentials = null
            },
            engineGeneration = engineGeneration,
        )
    }

    private fun finishClose(owned: ProductionRuntimeCloseOwnership) {
        retireDnsNetworkObservation()
        chainedStartup.retire()
        retireChainedPathRegistration()
        stopObservingChainedState()
        // Clear the installed mode only when this runtime is still the command owner's current one.
        // `finishClose` runs asynchronously on the engine-queue thread, including for a superseded
        // candidate whose deferred `close()` submitted this teardown after a newer start replaced
        // it — so an unconditional process-wide NONE could clobber the replacement's live
        // DNS_ONLY/CHAINED publication with no later event to correct it. The command owner performs
        // the exact-owner check and the clear under its monitor (mirroring its accepted terminal
        // retirement).
        request.clearChainedActivityWhenCurrent(request.owner)
        owned.heartbeat?.cancel()
        owned.pauseExpiry?.cancel()
        // The chained owner retires the session witness and zeroizes its key material. It is
        // closed before the attachment/epoch anchors so no engine call can race the teardown.
        runCatching { owned.chained?.close() }
        // Credentials the preflight opened but never transferred to a live chained owner are
        // zeroized here; the owner closes the credentials it accepted ownership of.
        runCatching { owned.chainedPreflightCredentials?.close() }
        owned.networkRegistration?.close()
        owned.attachment?.acceptProtectionEvidence(
            VpnProtectionEvidence.State(
                serviceSessionGeneration = request.owner.sessionGeneration,
                engineGeneration = owned.engineGeneration,
                graphGeneration = request.attachment.graphGeneration,
                observedAtMillis = System.currentTimeMillis().coerceAtLeast(0L),
                active = false,
            ),
        )
        owned.epoch?.close()
        var failure: Throwable? = null
        try {
            runBlocking { owned.runtime?.closeAndJoin() }
        } catch (thrown: Throwable) {
            failure = thrown
        } finally {
            val credentialGate = owned.attachment
            if (credentialGate == null) {
                runCatching { request.attachment.detach() }
            } else {
                runCatching { credentialGate.close() }
            }
            completion?.cancel()
            scope.cancel()
        }
        failure?.let { throw it }
    }

    private fun publishProtection(state: ProtectionState): Boolean {
        if (lock.withLock { closed }) return false
        if (!request.publication.publish(state)) return false
        val active = state.hasObservedLocalProtection()
        publishActivityState(active)
        synchronizeActivityHeartbeat(active)
        return true
    }

    private fun publishActivityState(active: Boolean) {
        val current = lock.withLock {
            if (closed) null else attachmentGate?.let { it to engineGeneration }
        } ?: return
        current.first.acceptProtectionEvidence(
            VpnProtectionEvidence.State(
                serviceSessionGeneration = request.owner.sessionGeneration,
                engineGeneration = current.second,
                graphGeneration = request.attachment.graphGeneration,
                observedAtMillis = System.currentTimeMillis().coerceAtLeast(0L),
                active = active,
            ),
        )
    }

    private fun synchronizeActivityHeartbeat(active: Boolean) {
        val previous = lock.withLock {
            if (!active || closed) {
                activityHeartbeat.also { activityHeartbeat = null }
            } else if (activityHeartbeat == null) {
                activityHeartbeat = scope.launch {
                    while (true) {
                        delay(PROTECTION_HEARTBEAT_INTERVAL_MILLIS)
                        publishActivityHeartbeat()
                    }
                }
                null
            } else {
                null
            }
        }
        previous?.cancel()
    }

    private fun publishActivityHeartbeat() {
        val current = lock.withLock {
            if (closed || activityHeartbeat == null) {
                null
            } else {
                attachmentGate?.let { it to engineGeneration }
            }
        } ?: return
        current.first.acceptProtectionEvidence(
            VpnProtectionEvidence.Heartbeat(
                serviceSessionGeneration = request.owner.sessionGeneration,
                engineGeneration = current.second,
                graphGeneration = request.attachment.graphGeneration,
                observedAtMillis = System.currentTimeMillis().coerceAtLeast(0L),
            ),
        )
        // The existing runtime heartbeat is the only routine scheduling source. Chained owners
        // never install a physical probe target; superseded/retired engines are refused again.
        resolverSmokeProbes.tick()
        publishLeakCanaryIfEstablished()
    }

    /** Uses the existing heartbeat, not a new scheduler, resolver, or telemetry producer. */
    private fun publishLeakCanaryIfEstablished() {
        val launch = lock.withLock {
            if (closed || superseded) null else {
                val owner = chainedOwners.currentOrNull() ?: return@withLock null
                val arm = canaryLaunchArm ?: return@withLock null
                val channel = canaryChannel ?: return@withLock null
                Triple(owner, arm, channel)
            }
        } ?: return
        if (launch.third.captureArm() != launch.second) return
        val owner = launch.first
        val generation = owner.canaryGenerationIfEstablished() ?: return
        val network = currentEligibleNetwork()?.network ?: return
        val resource = com.lavasec.app.vpn.qa.DnsLeakCanaryEmitterFactory.create(
            service, network, launch.second, request.owner.sessionGeneration, nowMillis,
        ) ?: return
        if (!owner.adoptCanary(generation, resource)) return
        try {
            // Protect/bind work occurs on this heartbeat's IO coroutine, outside engine/arm locks.
            if (!resource.prepare()) return
            // Network selection may perform Binder IO: keep it outside the short final send
            // locks. The prepared socket remains pinned to this exact NOT_VPN network; a loss
            // at the syscall edge is a no-datagram error, never an unbound default-route retry.
            if (currentEligibleNetwork()?.network != network) return
            owner.runCanary(generation, resource) {
                lock.withLock {
                    if (closed || superseded || chainedOwners.currentOrNull() !== owner) {
                        com.lavasec.app.vpn.qa.DnsLeakCanarySend.NoDatagramSent
                    } else {
                        launch.third.sendIfCurrent(launch.second, { !closed && !superseded }) {
                            resource.send(generation)
                        }
                    }
                }
            }
        } finally {
            owner.releaseCanary(resource)
        }
    }

    private fun createEngine(
        attachment: CredentialVpnRuntimeAttachment,
        bounded: BoundedTunRuntime,
        epochGate: ServiceRuntimeEpochGate,
        networkContext: NetworkContext,
        resolverPlan: ResolverPlan,
        engineGeneration: Long,
    ): ReadyDnsEngine = ArtifactBackedReadyDnsEngineFactory(
        controls = ProxyPolicyControlSource {
            ProxyPolicyControl(
                activeProtectionSessionId = attachment.activeProtectionSessionId,
                pause = attachment.currentPause(),
            )
        },
        resolverFactory = productionResolverFactory(service, DeviceResolverWedgeEvidence { epoch ->
            lock.withLock {
                !closed && !superseded && this@ProductionVpnOwnedRuntime.engineGeneration == epoch.engineGeneration &&
                    healthOwner?.deviceResolverReconnectEpisodeIsActive(epoch) == true
            }
        }),
        eventIds = DecisionEventIdSource { PROCESS_EVENT_IDS.next() },
        wallClock = EpochMillisClock(System::currentTimeMillis),
        monotonicClock = SystemMonotonicClock,
        publicationSink = bounded.publicationSink,
        unavailablePublicationFence = ProxyPublicationFence { completion ->
            epochGate.completeIfCurrent(engineGeneration, completion)
        },
    ).create(
        lease = attachment.openFilterRuntimeLease(),
        sessionGeneration = request.owner.sessionGeneration,
        engineGeneration = engineGeneration,
        networkContext = networkContext,
        resolverPlan = resolverPlan,
    )

    private companion object {
        const val PRODUCT_LABEL = "LavaSec"
        const val PAUSE_CLOCK_RECHECK_MILLIS = 1_000L

        /**
         * How many times [preflight] re-resolves the peer after observing a changed underlay
         * snapshot, so the stashed network/address pair stays internally consistent without spinning
         * on an underlay that never settles.
         */
        const val MAX_PREFLIGHT_RESOLUTION_RETRIES = 2
        val PROCESS_EVENT_IDS = PositiveSequence()
    }
}

private data class ProductionRuntimeCloseOwnership(
    val runtime: BoundedTunRuntime?,
    val chained: ChainedTunRuntimeOwner?,
    val attachment: ExactCredentialRuntimeAttachment?,
    val epoch: ServiceRuntimeEpochGate?,
    val heartbeat: Job?,
    val pauseExpiry: Job?,
    val networkRegistration: AutoCloseable?,
    val chainedPreflightCredentials: ChainedVpnCredentials?,
    val engineGeneration: Long,
)

private data class ProductionRuntimeTerminalOwnership(
    val heartbeat: Job?,
    val networkRegistration: AutoCloseable?,
    val engineGeneration: Long,
    val failure: ProtectionFailure,
)

private fun ProtectionState.hasObservedLocalProtection(): Boolean = when (this) {
    is ProtectionState.Protected,
    is ProtectionState.Reconnecting,
    -> true

    ProtectionState.Off,
    ProtectionState.SetupRequired,
    is ProtectionState.Starting,
    is ProtectionState.AwaitingNetwork,
    is ProtectionState.Paused,
    ProtectionState.Stopping,
    is ProtectionState.Failed,
    -> false
}

private const val PROTECTION_HEARTBEAT_INTERVAL_MILLIS = 60_000L

internal fun restoredRuntimeProtectionState(
    pause: RuntimePauseDirective,
    identity: ConfigurationIdentity,
    ownership: VpnOwnership,
): ProtectionState = when (pause) {
    RuntimePauseDirective.Filtering -> ProtectionState.Protected(identity, ownership)
    is RuntimePauseDirective.Paused -> ProtectionState.Paused(pause.state.deadlineMillis)
}

internal class ProductionTunRuntimeObserver(
    private val serviceSessionGeneration: Long,
    private val graphGeneration: Long,
    private val engineGeneration: () -> Long,
    private val attachment: ExactCredentialRuntimeAttachment,
    private val publication: VpnStatePublication,
    private val epochGate: ServiceRuntimeEpochGate,
    private val health: ExactSessionHealthOwner? = null,
    private val onFailure: (ProtectionFailure) -> Unit = {},
) : TunRuntimeObserver {
    override fun acceptTerminalEvidence(evidence: TunTerminalEvidence): Boolean {
        val generation = evidence.engineGeneration
        health?.recordBatch(
            engineGeneration = generation,
            healthEvents = evidence.healthEvents,
            resolverQueryEvidence = evidence.resolverQueryEvidence,
            primaryAnswerBearing = evidence.primaryAnswerBearing,
        )
        epochGate.completeIfCurrent(generation) {
            val accepted = attachment.tryAccept(
                VpnTelemetry(
                    serviceSessionGeneration = serviceSessionGeneration,
                    engineGeneration = generation,
                    graphGeneration = graphGeneration,
                    activity = evidence.activity,
                    healthEvents = evidence.healthEvents,
                    resolverQueryEvidence = evidence.resolverQueryEvidence,
                ),
            )
            if (!accepted) health?.recordPrivateTelemetryDrop(generation)
            true
        }
        // Telemetry rejection is an intentional drop, not a packet-runtime failure.
        return true
    }

    override fun publishHealth(event: com.lavasec.app.foundation.dns.DnsHealthEvent) {
        val generation = engineGeneration()
        health?.recordHealth(generation, event)
        epochGate.completeIfCurrent(generation) {
            val accepted = attachment.tryAccept(
                VpnTelemetry(
                    serviceSessionGeneration,
                    generation,
                    graphGeneration,
                    healthEvents = listOf(event),
                ),
            )
            if (!accepted) health?.recordPrivateTelemetryDrop(generation)
            true
        }
    }

    override fun publishFailure(failure: ProtectionFailure) {
        epochGate.completeForSession {
            publication.publish(ProtectionState.Failed(failure)).also { accepted ->
                if (accepted) onFailure(failure)
            }
        }
    }
}

internal class ServiceRuntimeEpochGate(
    private val sessionGeneration: Long,
    engineGeneration: Long,
) {
    private val lock = ReentrantLock()
    private var currentEngineGeneration = engineGeneration
    private var open = true

    init {
        require(sessionGeneration > 0L)
        require(engineGeneration > 0L)
    }

    fun installEngine(engineGeneration: Long) = lock.withLock {
        check(open)
        require(engineGeneration > currentEngineGeneration)
        currentEngineGeneration = engineGeneration
    }

    fun completeIfCurrent(engineGeneration: Long, completion: () -> Boolean): Boolean =
        lock.withLock {
            open && currentEngineGeneration == engineGeneration && completion()
        }

    fun completeForSession(completion: () -> Boolean): Boolean = lock.withLock {
        open && sessionGeneration > 0L && completion()
    }

    fun close() = lock.withLock { open = false }
}

internal data class SelectedVpnNetwork(
    val network: Network,
    val dnsServers: List<java.net.InetAddress>,
    val isCellular: Boolean,
    val privateDnsActive: Boolean = false,
    val privateDnsName: String? = null,
    val isWifi: Boolean = false,
)

/**
 * The peer-endpoint preflight resolved by [ProductionVpnOwnedRuntime.preflight] while the
 * predecessor still owned its tunnel, so [ProductionVpnOwnedRuntime.start] does not perform a slow
 * hostname lookup after the predecessor is retired.
 */
private sealed interface ChainedPreflightState {
    /** The underlay the outcome is keyed to, or `null` when there was no eligible underlay. */
    val network: SelectedVpnNetwork?

    /** The peer resolved; [address] is reusable by the chained establishment. */
    data class Resolved(
        override val network: SelectedVpnNetwork,
        val hostWireForm: String,
        val port: Int,
        val address: InetSocketAddress,
    ) : ChainedPreflightState

    /**
     * The lookup ran and failed. Start must fall straight back to DNS-only rather than retry it in
     * the no-tunnel gap.
     */
    data class Refused(override val network: SelectedVpnNetwork) : ChainedPreflightState

    /**
     * The preflight reached the credential read but no eligible underlay was available (or it
     * vanished during the lookup), so nothing was stashed.
     *
     * Start must route this through awaiting-network recovery and re-run the preflight once a
     * network appears, instead of treating the empty stash as a final DNS-only decision that is
     * never retried.
     */
    data object NetworkUnavailable : ChainedPreflightState {
        override val network: SelectedVpnNetwork? = null
    }
}

@Suppress("DEPRECATION")
private fun selectNetwork(context: Context): SelectedVpnNetwork? {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val candidates = buildList {
        manager.activeNetwork?.let(::add)
        manager.allNetworks.forEach { if (it !in this) add(it) }
    }
    return candidates.firstNotNullOfOrNull { network -> manager.selectEligible(network) }
}

private fun ConnectivityManager.selectEligible(network: Network): SelectedVpnNetwork? {
    val properties = getLinkProperties(network) ?: return null
    val capabilities = getNetworkCapabilities(network) ?: return null
    if (
        !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) ||
        !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    ) {
        return null
    }
    return SelectedVpnNetwork(
        network = network,
        dnsServers = properties.dnsServers.toList(),
        isCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        privateDnsActive = properties.isPrivateDnsActive,
        privateDnsName = properties.privateDnsServerName,
        isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
    )
}

private fun observeEligibleNetwork(
    context: Context,
    callback: () -> Unit,
): AutoCloseable {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = callback()

        override fun onLost(network: Network) = callback()

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) = callback()

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            callback()
    }
    val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()
    manager.registerNetworkCallback(request, networkCallback)
    return AutoCloseable { runCatching { manager.unregisterNetworkCallback(networkCallback) } }
}

/**
 * Reports default-network path transitions to [callback] as `(satisfied, interfacesChanged)`.
 *
 * A LIVE chained session (unlike the awaiting-network path) must learn when the underlay under its
 * pinned socket changes: the socket does not error, so without this the outage is only detected by
 * silence. `onAvailable`/`onCapabilitiesChanged` are satisfied transitions, `onLost` is not, but an
 * `onLost` is only reported when it names the TRACKED default network: a handoff can deliver the
 * replacement's satisfied callbacks before the old default's `onLost`, and reporting that stale
 * loss would quiesce a session whose replacement underlay is already live. `interfacesChanged` is
 * TRUE only when the reported `Network` identity actually changes
 * ([ChainedDefaultNetworkIdentity]): the initial `onAvailable` reports the already-current default
 * network the chained owner is bound to, and repeated capability updates for the same network are
 * not transitions, so reporting them would rebind a session that never roamed.
 *
 * [establishmentNetwork] seeds the tracker with the underlay the chained socket was pinned to, so a
 * default-network change that lands before the callback's first snapshot is reported as a change
 * rather than mistaken for the baseline (finding 4021623299).
 */
private fun observeDefaultNetwork(
    context: Context,
    establishmentNetwork: Network,
    callback: (Boolean, Boolean) -> Unit,
): AutoCloseable {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    // Registering the callback immediately reports the ALREADY-CURRENT default network through
    // `onAvailable`, and repeated capability updates for it. The chained owner established on
    // that network, so the initial sighting is not an interface change: treating it as one drove
    // an immediate rebind, and before the first handshake `adoptChannel` answers
    // NO_CURRENT_SESSION, shutting the fresh session into the retry ladder though no roam had
    // happened (finding 4020704485). Only a different `Network` identity is a change — the
    // Android form of the iOS `ChainedEligibleInterfaceStash`; same-to-same and capability
    // updates for the same network are not transitions. Seeded with the underlay the socket was
    // established on, so a change before the first snapshot is not swallowed as the baseline.
    val identity = ChainedDefaultNetworkIdentity(establishmentNetwork)
    val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = callback(true, identity.observed(network))

        // A handoff can deliver the replacement's `onAvailable`/`onCapabilitiesChanged` BEFORE the
        // old default's `onLost`, so the tracker already holds the replacement identity. Reporting
        // that stale `onLost` as unsatisfied would set the offline latch, fence the in-flight
        // rebind, stop the ticks, and retire a live session whose underlay is fine. Only the loss of
        // the TRACKED identity is a path loss; any other `onLost` names a network this session never
        // bound to.
        override fun onLost(network: Network) {
            if (identity.isCurrent(network)) callback(false, false)
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) = callback(true, identity.observed(network))

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            // Only prompt a fresh eligible-network read. The notification payload is not the
            // authority and a stale old-default update must not rebind the replacement.
            if (identity.isCurrent(network)) callback(true, false)
        }
    }
    manager.registerDefaultNetworkCallback(networkCallback)
    return AutoCloseable { runCatching { manager.unregisterNetworkCallback(networkCallback) } }
}

private fun productionResolverFactory(
    service: VpnService,
    wedgeEvidence: DeviceResolverWedgeEvidence = DeviceResolverWedgeEvidence { false },
): ProductionReadyDnsResolverFactory {
    val protector = object : OrdinaryVpnSocketProtector {
        override fun protect(socket: DatagramSocket): Boolean = service.protect(socket)
        override fun protect(socket: Socket): Boolean = service.protect(socket)
    }
    val binder = object : ExactNetworkSocketBinder {
        override fun bind(network: android.net.Network, socket: DatagramSocket) {
            network.bindSocket(socket)
        }

        override fun bind(network: android.net.Network, socket: Socket) {
            network.bindSocket(socket)
        }
    }
    return ProductionReadyDnsResolverFactory(
        deviceSocketPlatform = JavaNetDeviceDnsSocketPlatform(protector, binder),
        dohSocketProtector = DohSocketProtector(service::protect),
        dohSocketBinder = DohSocketBinder { socket, network -> network.bindSocket(socket) },
        wedgeEvidence = wedgeEvidence,
    )
}

private fun platformFacts(service: VpnService): PlatformVpnFacts =
    if (Build.VERSION.SDK_INT >= 29) {
        PlatformVpnFacts.api29(service.isAlwaysOn, service.isLockdownEnabled)
    } else {
        PlatformVpnFacts.api28()
    }

private fun EngineEpoch.toHealthIdentity(primaryResolverKind: ResolverKind) = VpnHealthEngineIdentity(
    sessionGeneration = sessionGeneration,
    networkGeneration = networkGeneration,
    engineGeneration = engineGeneration,
    primaryResolverIdentity = primaryResolverIdentity,
    fallbackCoverage = fallbackCoverage,
    primaryResolverKind = primaryResolverKind,
)

private class PositiveSequence {
    private val value = AtomicLong()

    fun next(): Long {
        val next = value.incrementAndGet()
        check(next > 0L) { "positive sequence space is exhausted" }
        return next
    }
}
