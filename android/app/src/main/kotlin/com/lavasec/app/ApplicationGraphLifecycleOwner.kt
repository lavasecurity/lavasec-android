package com.lavasec.app

import com.lavasec.app.platform.ChainedComposition
import com.lavasec.app.vpn.CredentialVpnHostPort
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Public-only process graph. Its construction must never touch credential-protected storage. */
internal interface DeviceApplicationGraph : AutoCloseable {
    /** Authenticated Direct Boot host, or null when public authority is unavailable. */
    val vpnHost: CredentialVpnHostPort?
        get() = null
}

/** One exact unlocked generation that owns every credential-protected runtime resource. */
internal interface CredentialApplicationGraph : AutoCloseable {
    val graphGeneration: Long
    val dnsLeakCanaryOwner: com.lavasec.app.data.CredentialDnsLeakCanaryOwner? get() = null
    val accountBackend: com.lavasec.app.account.AccountBackend? get() = null
    val plusBackend: com.lavasec.app.billing.NativePlusBackend? get() = null
    val vpnHost: CredentialVpnHostPort
    val productOwnerFactory: CredentialProductOwnerFactory?
        get() = null

    /**
     * The chained-upstream composition for this unlocked generation, or `null` when the graph
     * does not offer one. The React Native surface reads it to answer status/toggle, and the
     * credential VPN attachment reads it to open gate-cleared chained credentials.
     */
    val chainedComposition: ChainedComposition?
        get() = null

    /**
     * True only after re-reading coherent durable onboarding, protection, configuration, and
     * filter authority. False is an authoritative off state; null is unavailable or ambiguous.
     */
    val authoritativeProtectionIntent: Boolean?
}

/** One immutable projection of the attached graph; never constructs or owns another canary. */
internal data class CredentialDnsLeakCanaryBinding(
    val graphGeneration: Long,
    val owner: com.lavasec.app.data.CredentialDnsLeakCanaryOwner,
)

/** Actual graph identities produced by one serialized rotation, before factory publication. */
internal data class CredentialGraphTransition(
    val sourceGeneration: Long,
    val sourceFactory: CredentialProductOwnerFactory,
    val successorGeneration: Long,
    val successorFactory: CredentialProductOwnerFactory,
)

/**
 * Serializes the device/credential graph boundary for one application process.
 *
 * A replacement registration is installed before its predecessor is detached, so service starts
 * never observe an empty host registry during graph rotation. Private predecessor resources close
 * only after the replacement owns the registry.
 */
internal class ApplicationGraphLifecycleOwner(
    private val deviceGraphFactory: () -> DeviceApplicationGraph,
    private val credentialGraphFactory: suspend (Long) -> CredentialApplicationGraph,
    private val attachCredentialHost: (CredentialVpnHostPort) -> AutoCloseable,
    private val restoreProtection: suspend () -> Unit,
    private val retireProtection: suspend () -> Unit = {},
) : AutoCloseable {
    private val mutex = Mutex()
    private var closed = false
    private var nextCredentialGeneration = 1L
    private var deviceGraph: DeviceApplicationGraph? = null
    private var deviceRegistration: AutoCloseable? = null
    private var credentialGraph: CredentialApplicationGraph? = null
    private var credentialRegistration: AutoCloseable? = null
    private var protectionRestorationPending = false
    private var protectionRetirementPending = false
    private val mutableProductOwnerFactory = MutableStateFlow<CredentialProductOwnerFactory?>(null)
    private val mutableChainedComposition = MutableStateFlow<ChainedComposition?>(null)
    private val mutableDnsLeakCanaryBinding = MutableStateFlow<CredentialDnsLeakCanaryBinding?>(null)
    val dnsLeakCanaryBinding: StateFlow<CredentialDnsLeakCanaryBinding?> = mutableDnsLeakCanaryBinding.asStateFlow()
    private val mutablePlusBackend = MutableStateFlow<com.lavasec.app.billing.NativePlusBackend?>(null)
    val plusBackend = mutablePlusBackend.asStateFlow()
    private val mutableAccountBackend = MutableStateFlow<com.lavasec.app.account.AccountBackend?>(null)
    val accountBackend: StateFlow<com.lavasec.app.account.AccountBackend?> = mutableAccountBackend.asStateFlow()

    val productOwnerFactory: StateFlow<CredentialProductOwnerFactory?> =
        mutableProductOwnerFactory.asStateFlow()

    /**
     * The live credential generation's chained composition, or `null` while no unlocked graph is
     * installed. A process-scoped flow so the React Native native module can read the current
     * composition without holding a reference to a retiring graph.
     */
    val chainedComposition: StateFlow<ChainedComposition?> =
        mutableChainedComposition.asStateFlow()

    suspend fun reconcile(userUnlocked: Boolean) = mutex.withLock {
        check(!closed) { "application graph lifecycle is closed" }
        ensureDeviceGraph()
        if (!userUnlocked) {
            retireCredentialGraph()
        } else if (credentialGraph == null) {
            installCredentialGraph()
        }
        if (userUnlocked) reconcileProtectionIfPending()
    }

    suspend fun rotateCredentialGraph(expectedGeneration: Long) = mutex.withLock {
        check(!closed) { "application graph lifecycle is closed" }
        if (credentialGraph?.graphGeneration != expectedGeneration) return@withLock
        ensureDeviceGraph()
        retireCredentialGraph()
        installCredentialGraph()
        reconcileProtectionIfPending()
    }

    /** Only an acknowledged native setup may request this factory-bound transition. */
    suspend fun rotateCredentialGraphAfterOnboarding(
        expectedGeneration: Long,
        expectedFactory: CredentialProductOwnerFactory,
        beforePublication: (CredentialGraphTransition) -> Unit,
    ): CredentialGraphTransition? = mutex.withLock {
        check(!closed) { "application graph lifecycle is closed" }
        val source = credentialGraph?.takeIf {
            it.graphGeneration == expectedGeneration && it.productOwnerFactory === expectedFactory
        } ?: return@withLock null
        ensureDeviceGraph()
        retireCredentialGraph()
        var transition: CredentialGraphTransition? = null
        installCredentialGraph { successor ->
            val factory = successor.productOwnerFactory ?: return@installCredentialGraph
            val receipt = CredentialGraphTransition(source.graphGeneration, expectedFactory, successor.graphGeneration, factory)
            transition = receipt
            // This callback performs native atomic custody only. Its failure cannot
            // undo the durable setup or prevent publication of the installed graph.
            try { beforePublication(receipt) } catch (_: Exception) { }
        }
        reconcileProtectionIfPending()
        transition
    }

    override fun close() {
        runBlocking {
            mutex.withLock {
                if (closed) return@withLock
                closed = true
                var failure: Throwable? = runCatching { retireCredentialGraph() }.exceptionOrNull()
                try {
                    deviceRegistration?.close()
                } catch (thrown: Throwable) {
                    failure?.addSuppressed(thrown) ?: run { failure = thrown }
                } finally {
                    deviceRegistration = null
                }
                try {
                    deviceGraph?.close()
                } catch (thrown: Throwable) {
                    failure?.addSuppressed(thrown) ?: run { failure = thrown }
                } finally {
                    deviceGraph = null
                }
                failure?.let { throw it }
            }
        }
    }

    internal fun credentialGenerationForTest(): Long? = credentialGraph?.graphGeneration

    private fun ensureDeviceGraph() {
        if (deviceGraph == null) deviceGraph = deviceGraphFactory()
        attachDeviceHostIfAvailable()
    }

    private suspend fun installCredentialGraph(beforeProductPublication: (CredentialApplicationGraph) -> Unit = {}) {
        val generation = nextCredentialGeneration
        nextCredentialGeneration = Math.addExact(nextCredentialGeneration, 1L)
        val graph = credentialGraphFactory(generation)
        try {
            require(graph.graphGeneration == generation) {
                "credential graph factory returned the wrong generation"
            }
            require(graph.vpnHost.graphGeneration == generation) {
                "credential VPN host does not match its graph generation"
            }
        } catch (failure: Throwable) {
            runCatching { graph.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        val registration = try {
            attachCredentialHost(graph.vpnHost)
        } catch (failure: Throwable) {
            runCatching { graph.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        val priorDeviceRegistration = deviceRegistration.also { deviceRegistration = null }
        try {
            priorDeviceRegistration?.close()
        } catch (failure: Throwable) {
            runCatching { registration.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching { graph.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching { attachDeviceHostIfAvailable() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        credentialGraph = graph
        credentialRegistration = registration
        beforeProductPublication(graph)
        mutableProductOwnerFactory.value = graph.productOwnerFactory
        mutableChainedComposition.value = graph.chainedComposition
        mutableDnsLeakCanaryBinding.value = graph.dnsLeakCanaryOwner?.let { CredentialDnsLeakCanaryBinding(graph.graphGeneration, it) }
        mutableAccountBackend.value = graph.accountBackend
        mutablePlusBackend.value = graph.plusBackend
        protectionRestorationPending = graph.authoritativeProtectionIntent == true
        protectionRetirementPending = graph.authoritativeProtectionIntent != true
    }

    private fun retireCredentialGraph() {
        if (!closed) attachDeviceHostForReplacement()
        val registration = credentialRegistration
        val graph = credentialGraph
        credentialRegistration = null
        credentialGraph = null
        mutableProductOwnerFactory.value = null
        mutableChainedComposition.value = null
        mutableDnsLeakCanaryBinding.value = null
        mutableAccountBackend.value = null
        mutablePlusBackend.value = null
        protectionRestorationPending = false
        protectionRetirementPending = false
        var failure: Throwable? = null
        try {
            registration?.close()
        } catch (thrown: Throwable) {
            failure = thrown
        }
        try {
            graph?.close()
        } catch (thrown: Throwable) {
            failure?.addSuppressed(thrown) ?: run { failure = thrown }
        }
        failure?.let { throw it }
    }

    private fun attachDeviceHostForReplacement() {
        if (closed || deviceRegistration != null) return
        val host = deviceGraph?.vpnHost ?: return
        deviceRegistration = attachCredentialHost(host)
    }

    private fun attachDeviceHostIfAvailable() {
        if (closed || deviceRegistration != null || credentialRegistration != null) return
        val host = deviceGraph?.vpnHost ?: return
        deviceRegistration = attachCredentialHost(host)
    }

    private suspend fun reconcileProtectionIfPending() {
        if (protectionRestorationPending) {
            restoreProtection()
            protectionRestorationPending = false
        } else if (protectionRetirementPending) {
            retireProtection()
            protectionRetirementPending = false
        }
    }
}
