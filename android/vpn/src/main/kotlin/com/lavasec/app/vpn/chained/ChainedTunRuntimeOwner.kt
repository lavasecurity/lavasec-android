package com.lavasec.app.vpn.chained

import android.app.PendingIntent
import android.net.Network
import android.net.VpnService
import android.os.SystemClock
import com.lavasec.app.foundation.chained.ChainedRoutePlan
import com.lavasec.app.foundation.chained.ChainedOwnResolverPorts
import com.lavasec.app.foundation.chained.ChainedSessionBuildFailure
import com.lavasec.app.foundation.chained.ChainedUpstreamConfigurationValidator
import com.lavasec.app.vpn.ChainedClaimOutcome
import com.lavasec.app.vpn.ChainedEngineQueue
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Resolves the WireGuard peer endpoint to one socket address.
 *
 * A hostname is always resolved through the UNDERLYING [Network] (`Network.getAllByName`, API 23+,
 * which the minSdk 28 floor guarantees), and this runs BEFORE the route-claiming TUN is established
 * so a slow or timed-out lookup cannot blackhole the device. Using the selected network keeps the
 * bootstrap to the underlay's own resolvers rather than the process default (which the tunnel may
 * have captured, or be about to). An IP literal never touches the resolver at all.
 *
 * IPv4 is preferred: the chained engine carries IPv4 inner traffic and the peer's `AllowedIPs`
 * are IPv4, so an A/AAAA pair must not silently pick a v6 underlay the peer cannot answer on.
 */
internal object ChainedEndpointResolver {

    fun resolve(
        network: Network,
        hostWireForm: String,
        port: Int,
    ): InetSocketAddress? {
        val candidates = try {
            if (ChainedUpstreamConfigurationValidator.isIPLiteral(hostWireForm)) {
                listOf(InetAddress.getByName(hostWireForm))
            } else {
                network.getAllByName(hostWireForm).toList()
            }
        } catch (_: Exception) {
            emptyList()
        }
        val address = candidates.firstOrNull { it is Inet4Address }
            ?: candidates.firstOrNull()
            ?: return null
        return InetSocketAddress(address, port)
    }
}

/**
 * Reuses the address already resolved for the physical transport profile. The exit carries its
 * preceding entry as stack metadata, whereas the same profile read on its own does not. Compare
 * complete standalone profiles so only that container metadata is removed from the identity.
 * The caller supplies an address already fenced to the captured underlay; other profiles still
 * resolve on that same underlay before any route claim.
 */
internal fun chainedPhysicalPeerAddress(
    row: com.lavasec.app.foundation.chained.ChainedUpstreamConfiguration,
    configuration: com.lavasec.app.foundation.chained.ChainedUpstreamConfiguration,
    primaryAddress: InetSocketAddress,
    resolveEndpoint: (String, Int) -> InetSocketAddress?,
): InetSocketAddress? =
    if (row.withoutEntryHop() == configuration.transportConfiguration.withoutEntryHop()) primaryAddress
    else resolveEndpoint(row.endpointHostWireForm, row.endpointPort)

/**
 * Owns the resources of one chained tunnel lifecycle: the TUN, the protected peer socket, the
 * engine owner/data path, the outage driver, and its coroutine loops.
 *
 * Constructed only by [establish], which performs the whole device-facing setup (endpoint
 * resolution, socket protect/bind, witness, ~50 MB engine allocation, then route-claiming TUN
 * establishment) and returns `null` for any refusal — the caller then falls back to the unchanged
 * DNS-only path. Because Android allows only one active VPN interface, the engine and socket are
 * prepared before the TUN is established, so the predecessor stays active until the candidate is
 * fully ready. A non-null owner has ALREADY taken ownership of [credentials]: [close] zeroizes
 * them.
 *
 * The [ChainedOutageDriver] owns the attempt lifecycle (budget across attempts, retry ladder and
 * backoff, per-attempt watchdog, building/superseding sessions); this class only owns the platform
 * resources and reports the driver's terminal outcome to the control plane. The single
 * establishment point and the lost-race handling are unchanged: [establish] is the sole
 * `ChainedTunEstablishment.establish` caller and retains a route claim it can no longer own.
 *
 * The durable witness is recorded in [establish] BEFORE the engine allocation and retired by
 * [close] (a clean stop and a terminal failure both end the session in-process, so both clear it).
 * Because a replacement build runs OFF the engine queue on [sessionBuilds] and the ~50 MB JNI
 * allocation is non-cancellable, [close] defers that retirement — and the key zeroization — until
 * every started build has returned (`ChainedBuildSettlement`): the Android form of the iOS
 * credential lifetime, where the build is inline and `scrubSecrets()` runs before `makeSession`
 * returns, so a kill during construction is always attributed and the keys are never mutated under
 * a live build.
 *
 * Device-gated by construction: `VpnService`, `Network`, and `PendingIntent` require the
 * platform, so the setup is exercised by the `:vpn` Robolectric suite, not the host JVM harness.
 */
internal class ChainedTunRuntimeOwner private constructor(
    private val runtime: ChainedTunnelRuntime,
    private val dataPath: ChainedDataPath,
    private val credentials: ChainedVpnCredentials,
    private val timers: ChainedDriverTimers,
    /**
     * The I/O scope the replacement session build runs on. The ~50 MB JNI allocation is
     * synchronous and non-cancellable and MUST NOT run on the engine queue (deviation 11), so the
     * source launches it here and posts the ownership-fenced install back to the queue.
     */
    private val sessionBuilds: CoroutineScope,
    private val nowMillis: () -> Long,
    /**
     * The start epoch this runtime recorded with `markChainedSessionStarted`, so its clean stop
     * retires only ITS OWN exact witness and preserves any other active one (a make-before-break
     * candidate, even one stamped before a backward clock move). See
     * `ChainedSessionEligibilityLifecycle.markChainedSessionStoppedCleanly`.
     */
    private val witnessStartEpochMillis: Long,
    /**
     * The underlay the peer socket was pinned to at establishment, so the default-network path
     * tracker can be seeded with it (finding 4021623299). Without the seed, a default-network
     * change landing between the socket bind and the callback's first snapshot makes the tracker's
     * baseline the NEW underlay, reports no change, and skips the rebind that would move the
     * socket off the stale one.
     */
    val underlayNetwork: Network,
    val installedPlan: ChainedRoutePlan,
) : AutoCloseable {

    /** The immutable stored rotation this owner actually installed, never a fresh store read. */
    val configurationGeneration: Long = credentials.generation

    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    /**
     * Settles an in-flight replacement build before the witness is retired and the key material is
     * zeroized. Distinct from [sessionBuilds] because that scope is cancelled at close and
     * `Job.cancel()` cannot retract a synchronous allocation already running on it.
     */
    private val buildSettlement = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The engine lifecycle for this session: the control plane derives "actually chained" from
     * [ChainedEngineState.Established] rather than from the owner merely being installed.
     */
    val state: StateFlow<ChainedEngineState> get() = runtime.state

    /** No auxiliary resource is admitted until the current native session really handshook. */
    fun canaryGenerationIfEstablished(): Long? =
        if (closed.get()) null else dataPath.canaryGenerationIfEstablished()

    fun adoptCanary(generation: Long, resource: com.lavasec.app.vpn.qa.PreparedDnsLeakCanary): Boolean {
        if (closed.get()) { runCatching { resource.close() }; return false }
        return dataPath.adoptCanary(generation, resource)
    }

    fun runCanary(generation: Long, resource: com.lavasec.app.vpn.qa.PreparedDnsLeakCanary,
        send: () -> com.lavasec.app.vpn.qa.DnsLeakCanarySend): com.lavasec.app.vpn.qa.DnsLeakCanarySend =
        dataPath.runCanary(generation, resource) {
            if (closed.get()) com.lavasec.app.vpn.qa.DnsLeakCanarySend.NoDatagramSent else send()
        }

    fun releaseCanary(resource: com.lavasec.app.vpn.qa.PreparedDnsLeakCanary) = dataPath.releaseCanary(resource)

    fun start() {
        check(started.compareAndSet(false, true)) { "chained tunnel runtime can only start once" }
        // The durable witness was already persisted in `establish` BEFORE the engine was
        // allocated; starting drives the loops. The first session's handshake is forced HERE, not
        // inside `establish`: `dataPath.start()` can report `PEER_SOCKET_FAILED` synchronously, and
        // the control plane only opens its startup-termination window (`chainedStartup.begin`)
        // after `establish` returns — forcing the handshake there let a synchronous failure be
        // discarded and an already-failed full-route runtime be adopted.
        dataPath.start()
        runtime.start()
    }

    /**
     * Reports an underlay transition to the outage driver, so the live session's transport is
     * rebound to the path the device is on now rather than waiting for the silence detector.
     */
    fun pathChanged(satisfied: Boolean, interfacesChanged: Boolean) {
        runtime.pathChanged(satisfied, interfacesChanged)
    }

    /**
     * Retains this owner's already-claimed route-claiming TUN as [engineQueue]'s current tunnel,
     * then releases the owner's engine, peer socket, dispatcher, witness, and credentials.
     *
     * Used when a supersession lands between the claim and the owner registration: the interface is
     * live, so closing it would leave the device without a tunnel until the successor establishes.
     * The detached session is owned by the queue and released when a successor installs its claim or
     * the queue closes; a closed queue owns nothing, so the session is released here.
     */
    fun handOffRouteClaim(engineQueue: ChainedEngineQueue) {
        val session = dataPath.detachPacketSession()
        if (session != null && !engineQueue.retainRouteClaim(session)) {
            runCatching { session.close() }
        }
        close()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Capture the build scope's Job BEFORE cancelling it. Cancellation only prevents a build
        // that has not started; the synchronous ~50 MB allocation is non-cancellable, and a build
        // already inside `sessionFactory.create()` runs to completion regardless.
        val buildsJob = sessionBuilds.coroutineContext[Job]
        runCatching { sessionBuilds.cancel() }
        runCatching { runtime.close() }
        // Release the driver's scheduler thread; `retire()` cancels the armed tasks but does not
        // stop the executor, so without this every runtime rebuild would leak a timer thread.
        runCatching { timers.shutdown() }
        // THE WITNESS AND THE KEYS OUTLIVE THE BUILD WINDOW. The witness was persisted by
        // `establish`, and retiring it (and zeroizing the key arrays the session factory still
        // holds by reference) before an in-flight build returns would leave a low-memory kill from
        // that build's remaining ~50 MB work unattributed and could mutate key bytes under a live
        // allocation. `ChainedBuildSettlement` waits for every started build to return, then
        // retires the witness and releases the keys — the Android form of the iOS credential
        // lifetime, where `scrubSecrets()` runs only after the inline build returns. The owning
        // start is passed so a make-before-break candidate's NEWER witness is preserved.
        buildSettlement.launch {
            ChainedBuildSettlement.run(
                awaitBuilds = { buildsJob?.join() },
                retireWitness = ::retireWitness,
                releaseSecrets = credentials::close,
            )
        }
    }

    /**
     * Retires the exact witness THIS runtime recorded, preserving a make-before-break candidate's.
     *
     * Called only from the deferred settlement above, once every started build has returned.
     */
    private fun retireWitness() {
        runCatching {
            credentials.lifecycle.markChainedSessionStoppedCleanly(
                nowMillis(),
                witnessStartEpochMillis,
            )
        }
    }

    companion object {
        /**
         * Creates one protected/bound peer channel, or `null` when the platform refuses the
         * socket. The `VpnService.protect` + underlying-`Network` bind ordering is the same the
         * initial establishment uses, so a rebind and a full rebuild both bind to the physical
         * underlay rather than looping the tunnel's own outer datagrams back into itself.
         */
        private fun createPeerChannel(
            service: VpnService,
            network: Network,
            peerAddress: InetSocketAddress,
        ): ChainedPeerSocket? {
            // The candidate is retained OUTSIDE the bind try: `Network.bindSocket` throws when the
            // selected underlay disappears or refuses the bind, and a socket created inside an
            // `also` that the exception skips is never assigned to `socket`, so the catch cannot
            // close it. Initial establishment and every recovery attempt share this helper, so
            // repeated bind failures during network churn leaked one fd each until the VPN could
            // no longer open sockets (finding 4021942636).
            //
            // The CONSTRUCTOR is guarded in its own try: a `SocketException` from `DatagramSocket()`
            // (e.g. fd exhaustion — the very failure mode this helper guards) must travel the
            // refusal funnel as `null`, not escape it. Escaping `null` would, in `establish`,
            // bypass the `?: return null` and leak the opened key material; in `makeSession`'s
            // `prepare()` it would leak the just-allocated session and triage as a permanent
            // surrender because it is no `ChainedSessionBuildFailure`.
            val candidate = try {
                DatagramSocket()
            } catch (_: Throwable) {
                return null
            }
            return try {
                if (!service.protect(candidate)) {
                    candidate.close()
                    return null
                }
                network.bindSocket(candidate)
                ProtectedUnderlyingPeerSocket(candidate, peerAddress)
            } catch (_: Throwable) {
                runCatching { candidate.close() }
                null
            }
        }

        /** Prepare every physical channel before claiming routes; nested exit has no socket. */
        private fun createSelectionChannel(service: VpnService, network: Network, primaryAddress: InetSocketAddress,
            configuration: com.lavasec.app.foundation.chained.ChainedUpstreamConfiguration): ChainedUpstreamChannel? {
            if (configuration.orderedHops.size == 1) return createPeerChannel(service, network, primaryAddress)
            val physical = if (configuration.usesNestedTransport) listOf(configuration.precedingHops.first()) else configuration.orderedHops
            val channels = mutableListOf<ChainedUpstreamChannel>()
            try {
                physical.forEach { row ->
                    val address = chainedPhysicalPeerAddress(row, configuration, primaryAddress) { host, port ->
                        ChainedEndpointResolver.resolve(network, host, port)
                    } ?: return null
                    channels += createPeerChannel(service, network, address) ?: return null
                }
                return ChainedStackPeerChannel(channels.toList())
            } finally { if (channels.size != physical.size) channels.forEach { runCatching { it.close() } } }
        }

        /**
         * Installs the mode-specific TUN and builds the chained data path/driver, or returns
         * `null` (releasing everything already taken) when the plan is refused or a platform
         * resource cannot be created.
         *
         * On success the caller owns the returned owner and must [close] it; [credentials] are
         * then owned by the owner and must NOT be closed separately. [dnsFallback], when present,
         * is consumed on every return/throw path; the live DNS egress owns it after success.
         *
         * [engineQueue] + [operationGeneration] are the single authority on whether this candidate
         * is still current. The peer/socket/witness/engine are prepared over a slow,
         * non-cancellable window; a Stop or a newer Start supersedes this candidate by bumping the
         * queue generation from any thread. Establishing an Android VPN interface deactivates
         * the predecessor's (or a newer candidate's) interface, so the guard is checked immediately
         * before the route claim and immediately after it, ON the engine-queue thread that runs this
         * establishment. Because the queue executes one operation at a time, no second attempt
         * can be establishing concurrently: "am I current?" + establish is atomic. A superseded
         * candidate refuses the claim — releasing its prepared socket, engine session, witness, and
         * dispatcher — instead of tearing down a tunnel it no longer owns.
         */
        fun establish(
            service: VpnService,
            productLabel: String,
            network: Network,
            configureIntent: PendingIntent,
            credentials: ChainedVpnCredentials,
            dnsPolicy: ChainedDnsPolicy,
            onTermination: (ChainedTunRuntimeOwner, ChainedTunTermination) -> Unit,
            resolvedPeerAddress: InetSocketAddress? = null,
            nowMillis: () -> Long = System::currentTimeMillis,
            engineQueue: ChainedEngineQueue,
            operationGeneration: Long,
            /**
             * The underlay a replacement channel must bind to: the current eligible network, or
             * `null` when none is available. Defaults to the establishment network so an
             * environment with no path monitor keeps the static binding.
             */
            currentNetwork: () -> Network? = { network },
            dnsFallback: ChainedDnsFallback? = null,
            capturedResolverAddresses: (Network) -> List<String>? = { if (it == network) emptyList() else null },
            onDiagnostics: (ChainedNerdDiagnostics) -> Unit = {},
        ): ChainedTunRuntimeOwner? {
            var fallbackTransferred = false
            try {
                return establishOwned(
                    service, productLabel, network, configureIntent, credentials, dnsPolicy,
                    onTermination, resolvedPeerAddress, nowMillis, engineQueue,
                    operationGeneration, currentNetwork, dnsFallback, capturedResolverAddresses, onDiagnostics,
                ).also { fallbackTransferred = it != null }
            } finally {
                if (!fallbackTransferred) runCatching { dnsFallback?.close() }
            }
        }

        private fun establishOwned(
            service: VpnService,
            productLabel: String,
            network: Network,
            configureIntent: PendingIntent,
            credentials: ChainedVpnCredentials,
            dnsPolicy: ChainedDnsPolicy,
            onTermination: (ChainedTunRuntimeOwner, ChainedTunTermination) -> Unit,
            resolvedPeerAddress: InetSocketAddress?,
            nowMillis: () -> Long,
            engineQueue: ChainedEngineQueue,
            operationGeneration: Long,
            currentNetwork: () -> Network?,
            dnsFallback: ChainedDnsFallback?,
            capturedResolverAddresses: (Network) -> List<String>?,
            onDiagnostics: (ChainedNerdDiagnostics) -> Unit,
        ): ChainedTunRuntimeOwner? {
            if (dnsFallback != null && !credentials.dnsFallbackEnabled) return null
            // Android permits only one active VPN interface, so `ChainedTunEstablishment.establish`
            // deactivates the predecessor the instant it runs. Everything slow or fallible must
            // therefore be prepared BEFORE the interface is claimed: the protected/bound peer
            // socket, the witness, and the ~50 MB JNI engine session. Making establishment the LAST
            // step is what keeps the predecessor's tunnel active until the candidate is fully
            // ready, so a Start/Reload racing the establishment cannot find itself with no usable
            // underlay. Any failure releases everything already taken.
            // The underlay the peer socket is pinned to, captured ONCE: it seeds the default-network
            // path tracker so a default-network change landing before the callback's first snapshot
            // is still reported as a change (finding 4021623299). `currentNetwork()` is the live
            // eligible underlay, falling back to the establishment network.
            val underlay = currentNetwork() ?: network
            // Capture membership from the same eligible network the peer will actually bind to.
            // A callback that cannot attest that network refuses rather than publishing old DNS
            // routes against a newer underlay. This snapshot belongs to this installed activation.
            val capturedDnsAddresses = runCatching { capturedResolverAddresses(underlay)?.toList() }
                .getOrNull() ?: return null

            // Resolve the peer BEFORE establishing the mode-specific TUN. The TUN claims the
            // device's routes the instant it is established and has no driver until the owner
            // starts, and the previous runtime was already closed by the reload; resolving a
            // hostname after that would blackhole all device traffic for the duration of a slow or
            // timed-out lookup. On failure this refuses without touching the TUN, so the caller
            // stays on the unchanged DNS-only path.
            //
            // The selected underlay can differ from the network the command owner preflighted on
            // (it changed between preflight and this commit). In that case the stashed
            // [resolvedPeerAddress] belongs to a network the socket will NOT bind to, and the path
            // tracker is seeded with the new underlay, so its first callback sees no identity change
            // and never re-resolves: a split-horizon resolver or an endpoint failover would then
            // blackhole the freshly claimed full-route tunnel (finding 4021942640). Re-resolve the
            // hostname on the underlay the socket actually binds to; the preflight address is reused
            // only while that underlay is unchanged (an IP literal re-resolves locally, no lookup).
            val peerAddress = if (underlay == network) {
                resolvedPeerAddress ?: ChainedEndpointResolver.resolve(
                    network = network,
                    hostWireForm = credentials.configuration.transportConfiguration.endpointHostWireForm,
                    port = credentials.configuration.transportConfiguration.endpointPort,
                )
            } else {
                ChainedEndpointResolver.resolve(
                    network = underlay,
                    hostWireForm = credentials.configuration.transportConfiguration.endpointHostWireForm,
                    port = credentials.configuration.transportConfiguration.endpointPort,
                )
            } ?: return null
            val peerSocket = createSelectionChannel(service, underlay, peerAddress, credentials.configuration)
                ?: return null

            // ONE serial engine confinement for the whole chained lifecycle: the outage driver,
            // its timers, and every engine call the runner makes all run on [engineQueue]. A
            // per-session executor here would let the driver (on the engine queue) and a runner
            // session end (on the executor) block on each other.
            val dispatcher = EngineQueueChainedDispatcher(engineQueue)
            // One session factory for the first session and every attempt: `create` mints a fresh
            // authority-bound permit each time, so a lapsed entitlement is refused on the next
            // attempt rather than reusing the first session's right to construct.
            val sessionFactory = OrderedChainedWireGuardSessionFactory(credentials)
            // The start epoch THIS runtime records. It is passed back on the clean stop so the
            // store retires only this session's EXACT witness and preserves any other active one
            // (the make-before-break candidate that latched while this predecessor was alive).
            val witnessStartEpochMillis = AtomicReference<Long?>(null)
            val retireWitness: () -> Unit = {
                witnessStartEpochMillis.get()?.let { owningStart ->
                    runCatching {
                        credentials.lifecycle.markChainedSessionStoppedCleanly(
                            nowMillis(),
                            owningStart,
                        )
                    }
                }
            }
            // Persist the kill-attribution witness BEFORE the ~50 MB engine allocation, so a
            // low-memory kill during construction is attributable to chaining. A failed witness
            // write refuses to allocate at all and falls back to DNS-only.
            val session = try {
                ChainedWitnessFirstAllocation.run(
                    markWitness = {
                        val startedAt = nowMillis()
                        if (credentials.lifecycle.markChainedSessionStarted(startedAt)) {
                            witnessStartEpochMillis.set(startedAt)
                            true
                        } else {
                            false
                        }
                    },
                    retireWitness = retireWitness,
                    allocate = {
                        sessionFactory.create()
                            ?: throw ChainedRuntimeStartException(
                                ChainedRuntimeFailure.ENGINE_UNAVAILABLE,
                            )
                    },
                )
            } catch (_: Throwable) {
                runCatching { peerSocket.close() }
                return null
            }
            if (session == null) {
                // The witness could not be persisted: the engine must not be allocated, so the
                // allocation-time kill window cannot exist without its attribution record.
                runCatching { peerSocket.close() }
                return null
            }

            // Fence the route claim: a Stop or a newer Start may have superseded this candidate
            // while the non-cancellable session allocation above ran. Claiming the device interface
            // would deactivate the predecessor's (or a newer candidate's) TUN, so a superseded
            // candidate must refuse and release what it prepared.
            if (!engineQueue.isEstablishAllowed(operationGeneration) || currentNetwork() != underlay ||
                runCatching { capturedResolverAddresses(underlay)?.toList() }.getOrNull() != capturedDnsAddresses
            ) {
                retireWitness()
                runCatching { session.close() }
                runCatching { peerSocket.close() }
                return null
            }

            // LAST: claim the device interface. The predecessor stays active until the engine,
            // socket, and witness are all in place.
            val establishment = ChainedTunEstablishment.establish(
                service = service,
                productLabel = productLabel,
                configuration = credentials.configuration,
                underlyingNetwork = underlay,
                configureIntent = configureIntent,
                capturedResolverAddresses = capturedDnsAddresses,
            )
            val established = establishment as? ChainedTunEstablishmentResult.Established
            if (established == null) {
                retireWitness()
                runCatching { session.close() }
                runCatching { peerSocket.close() }
                return null
            }

            // Resolve the claim after establishment: a supersede can win during `establish` itself
            // (the claim is not cancellable). The interface was claimed, so it must not be torn
            // down out from under the successor: the queue resolves the claim atomically with the
            // current-operation check and RETAINS a superseded interface as the current tunnel,
            // releasing it only when the successor's establishment replaces it (the Android mapping
            // of iOS's "the session stays until it is replaced"). The prepared engine/socket/witness
            // are released in every non-installed outcome; a closed queue refuses the claim, which
            // is then ours to release.
            when (engineQueue.resolveRouteClaim(operationGeneration, established.session)) {
                ChainedClaimOutcome.REFUSED -> {
                    runCatching { established.session.close() }
                    retireWitness()
                    runCatching { session.close() }
                    runCatching { peerSocket.close() }
                    return null
                }

                ChainedClaimOutcome.RETAINED -> {
                    retireWitness()
                    runCatching { session.close() }
                    runCatching { peerSocket.close() }
                    return null
                }

                ChainedClaimOutcome.INSTALLED -> Unit
            }

            // The sinks are bound to the concrete owner AFTER construction so a failure can be
            // attributed to the owner that reported it. No callback can fire before the owner
            // exists.
            val terminalSink = AtomicReference<(ChainedTunTermination) -> Unit> { }
            // The resolver ports and the T0 egress share one instance: the egress claims a port
            // before it sends and the classifier reads the same registry to carve our own query
            // out of client DNS. Monotonic elapsed real time, so the port grace window cannot
            // jump under an NTP sync.
            val resolverPorts = ChainedOwnResolverPorts(
                nowMillis = { SystemClock.elapsedRealtime() },
            )
            val driverHolder = AtomicReference<ChainedOutageDriver?>()
            val dnsEgress = ChainedT0ResolverEgress(
                configuration = credentials.configuration,
                ports = resolverPorts,
                policy = dnsPolicy,
                observationEpoch = { driverHolder.get()?.dnsObservationEpoch() ?: 0L },
                observeResolution = { epoch, nameKey -> driverHolder.get()?.reportTunnelDns(epoch, nameKey) },
                nowMillis = { SystemClock.elapsedRealtime() },
                fallback = dnsFallback,
                dispatchCompletion = { action -> dispatcher.run(action) },
            )
            val dataPath = try {
                ChainedDataPath(
                    configuration = credentials.configuration,
                    installedPlan = established.plan,
                    packetSession = established.session,
                    peerSocket = peerSocket,
                    session = session,
                    ownResolverPorts = resolverPorts,
                    upstreamGeneration = credentials.generation,
                    // The T0 client-DNS egress: intercepted queries are rebuilt as inner
                    // datagrams for the configured resolver and carried by this session. It
                    // opens no socket. Only split+captured consent can supply the separately
                    // bound saved-DNS adapter, and only terminal T0 failure reaches it.
                    clientDns = dnsEgress,
                    dispatcher = dispatcher,
                    // Monotonic elapsed real time, never the wall clock: the outage budget
                    // and liveness policies consume these seconds, and an NTP sync or manual
                    // clock change could jump backward (clock unusable) or forward (whole
                    // budget consumed) and surrender a healthy session to DNS-only.
                    nowSeconds = { (SystemClock.elapsedRealtime() / 1_000L).toInt() },
                    onFailure = { failure ->
                        terminalSink.get()(ChainedTunTermination.Failed(failure))
                    },
                )
            } catch (_: Throwable) {
                retireWitness()
                runCatching { session.close() }
                runCatching { established.session.close() }
                runCatching { peerSocket.close() }
                return null
            }

            // The driver is the ONE attempt owner: it holds the budget across attempts, arms the
            // per-attempt watchdog and retry backoff, and builds each replacement session through
            // this source rather than the control plane installing one.
            val timers = EngineQueueChainedDriverTimers(
                engineQueue = engineQueue,
                onTick = { driverHolder.get()?.tick() },
                onTickFailure = {
                    terminalSink.get()(ChainedTunTermination.Failed(ChainedRuntimeFailure.ENGINE_FAILED))
                },
            )
            // A recovery attempt binds to the underlay the device is on NOW, so a hostname endpoint
            // must be re-resolved there: split-horizon DNS or an endpoint failover can map the same
            // name to a different reachable address than the one resolved at establishment, and
            // reusing the initial address would abandon a perfectly good upstream for DNS-only. An
            // IP literal resolves locally without touching the underlay. The initial
            // `resolvedPeerAddress` (a command-owner preflight) is still reused for the first
            // session, which the wiring already built.
            val endpointHost = credentials.configuration.transportConfiguration.endpointHostWireForm
            val endpointPort = credentials.configuration.transportConfiguration.endpointPort
            // The replacement session build runs OFF the engine queue: the ~50 MB JNI allocation
            // is synchronous and non-cancellable, and holding the one serial engine queue across
            // it would defer the driver's armed watchdog (deviation 11). Cancelled with the owner.
            val sessionBuilds = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val source = object : ChainedSessionSource {
                override fun makeChannel(delivery: ChainedChannelDelivery): Boolean {
                    // The rebind transport is built OFF the engine queue, like the ~50 MB session
                    // allocation (deviation 11): a path change leaves the full-route TUN pinned to
                    // the old underlay, and `Network.getAllByName` on the engine queue would defer
                    // the driver's ticks, its armed outage deadline, and teardown for the length of
                    // the lookup (finding 4022018493). `makeChannel` returns as soon as the build is
                    // launched; the outcome comes back through the driver's ownership fence.
                    //
                    // A recovery attempt binds to the underlay the device is on NOW, so a hostname
                    // endpoint must be re-resolved there: split-horizon DNS or an endpoint failover
                    // can map the same name to a different reachable address than the one resolved
                    // at establishment, and reusing the initial address would abandon a perfectly
                    // good upstream for DNS-only. An IP literal resolves locally without touching
                    // the underlay. The initial `resolvedPeerAddress` (a command-owner preflight)
                    // is still reused for the first session, which the wiring already built.
                    sessionBuilds.launch {
                        val underlay = currentNetwork()
                        if (underlay == null) {
                            // No eligible underlay means no rebind channel: the driver falls back to
                            // the ladder rather than binding to the network that just went away.
                            delivery.failed()
                            return@launch
                        }
                        val channel = try {
                            val address =
                                ChainedEndpointResolver.resolve(underlay, endpointHost, endpointPort)
                                    ?: run {
                                        delivery.failed()
                                        return@launch
                                    }
                            createSelectionChannel(service, underlay, address, credentials.configuration)
                                ?: run {
                                    delivery.failed()
                                    return@launch
                                }
                        } catch (_: Throwable) {
                            delivery.failed()
                            return@launch
                        }
                        if (!delivery.adopt(channel)) {
                            // The rebind was superseded while the lookup ran: the channel was built
                            // for an attempt that no longer owns the session, so release it.
                            runCatching { channel.close() }
                        }
                    }
                    return true
                }

                override fun makeSession(
                    events: ChainedSessionEvents,
                    delivery: ChainedSessionDelivery,
                ) {
                    // A full rebuild binds a FRESH socket to the underlay the device is on now: the
                    // previous attempt's channel may be pinned to one that has gone away, and a
                    // stale binding fails silently. A channel or lookup that cannot be built is
                    // transient (the underlay is momentarily unbindable/unresolvable), so it spends
                    // a ladder rung rather than surrendering chaining; the factory's own refusal
                    // (`null`) is permanent.
                    fun prepare(): ChainedPreparedReplacement? {
                        val underlay = currentNetwork() ?: throw ChainedChannelUnavailableException()
                        val address =
                            ChainedEndpointResolver.resolve(underlay, endpointHost, endpointPort)
                                ?: throw ChainedChannelUnavailableException()
                        val replacement = sessionFactory.create() ?: return null
                        val channel = createSelectionChannel(service, underlay, address, credentials.configuration)
                            ?: run {
                                runCatching { replacement.close() }
                                throw ChainedChannelUnavailableException()
                            }
                        return ChainedPreparedReplacement(replacement, channel)
                    }

                    // Returns immediately; the ownership-fenced install is posted back to the
                    // engine queue once the (off-queue) allocation returns.
                    sessionBuilds.launch {
                        val prepared = try {
                            prepare()
                        } catch (failure: Throwable) {
                            delivery.failed(failure)
                            return@launch
                        }
                        if (prepared == null) {
                            delivery.failed(null)
                            return@launch
                        }
                        val adopted = try {
                            delivery.adopt {
                                // Runs ON the engine queue inside the driver's attempt fence, so a
                                // build superseded while allocating never reaches the data path.
                                dataPath.installSession(prepared.session, prepared.channel, events)
                                dataPath
                            }
                        } catch (failure: Throwable) {
                            runCatching { prepared.session.close() }
                            runCatching { prepared.channel.close() }
                            return@launch
                        }
                        if (!adopted) {
                            // The attempt was retired or superseded while the allocation ran: a
                            // session built for it is released, never installed.
                            runCatching { prepared.session.close() }
                            runCatching { prepared.channel.close() }
                        }
                    }
                }
            }
            val driver = ChainedOutageDriver(
                onDiagnostics = onDiagnostics,
                fullTunnel = credentials.configuration.effectiveRoutingPolicy ==
                    com.lavasec.app.foundation.chained.ChainedRoutingPolicy.FULL_TUNNEL,
                engineQueue = engineQueue,
                timers = timers,
                source = source,
                clock = { (SystemClock.elapsedRealtime() / 1_000L).toInt() },
                onSurrender = { _, _ ->
                    terminalSink.get()(ChainedTunTermination.FallBackToDnsOnly)
                },
            )
            driverHolder.set(driver)

            val runtime = ChainedTunnelRuntime(
                driver = driver,
                dataPath = dataPath,
                scopeDispatcher = Dispatchers.IO,
                onTerminalFailure = { failure ->
                    terminalSink.get()(ChainedTunTermination.Failed(failure))
                },
            )
            val owner = ChainedTunRuntimeOwner(
                runtime = runtime,
                dataPath = dataPath,
                credentials = credentials,
                timers = timers,
                sessionBuilds = sessionBuilds,
                nowMillis = nowMillis,
                witnessStartEpochMillis = checkNotNull(witnessStartEpochMillis.get()) {
                    "an installed chained owner always persisted its witness"
                },
                underlayNetwork = underlay,
                installedPlan = established.plan,
            )
            terminalSink.set { termination -> onTermination(owner, termination) }
            // The first session is the wiring's to build (the driver builds only ladder
            // replacements); its end reports through the driver like every later one. The handshake
            // is NOT forced here: `ChainedTunRuntimeOwner.start()` does it after the control plane
            // has bound its startup-termination window, so a synchronous `PEER_SOCKET_FAILED`
            // during the first send cannot be discarded.
            dataPath.installEvents(ChainedSessionEvents { cause -> driver.sessionEnded(cause) })
            driver.adopt(dataPath)
            return owner
        }
    }
}

/**
 * A replacement channel could not be built for a live attempt: no bindable underlay right now.
 *
 * Transient, not permanent — a handoff can momentarily leave no eligible interface — so the
 * outage ladder spends one rung rather than surrendering chained mode for the whole lifecycle.
 * Mirrors iOS `ChainedSessionBuildFailure.unbindableInterface`.
 */
private class ChainedChannelUnavailableException :
    IllegalStateException("no bindable underlay for a chained replacement channel"),
    ChainedSessionBuildFailure {
    override val warrantsAnotherAttempt: Boolean get() = true
}

/**
 * One attempt's freshly built engine session and its bound transport.
 *
 * Produced OFF the engine queue by the source and handed to the driver's ownership-fenced install
 * ON it. Holding the pair here is what lets a build that was superseded while allocating release
 * BOTH resources rather than leaking the ~50 MB engine handle.
 */
private class ChainedPreparedReplacement(
    val session: WireGuardSession,
    val channel: ChainedUpstreamChannel,
)
