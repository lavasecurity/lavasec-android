package com.lavasec.app

import android.app.Application
import android.app.Activity
import android.os.Bundle
import android.os.UserManager
import androidx.work.Configuration
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import com.facebook.react.soloader.OpenSourceMergedSoMapping
import com.facebook.soloader.SoLoader
import com.lavasec.app.platform.ProtectionState
import com.lavasec.app.platform.ChainedComposition
import com.lavasec.app.reactnative.LavaAppPackage
import com.lavasec.app.vpn.LavaVpnRuntimeBridge
import com.lavasec.app.vpn.LavaVpnServiceClient
import com.lavasec.app.work.ActivityRetentionScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

open class LavasecApplication : Application(), ReactApplication, Configuration.Provider {
    // JobScheduler may recreate SystemJobService before the asynchronous credential graph.
    // WorkManager's own lock performs on-demand initialization for that process entry point.
    override val workManagerConfiguration: Configuration
        get() {
            check(isUserUnlocked()) { "WorkManager requires credential storage to be unlocked" }
            return Configuration.Builder().build()
        }

    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val initializedPlusBackends = mutableSetOf<com.lavasec.app.billing.NativePlusBackend>()
    private var processTerminated = false
    private var processTermination: CompletableDeferred<Unit>? = null

    /** Register only an existing owner, so teardown never initializes a lazy billing graph. */
    internal fun registerPlusBackend(backend: com.lavasec.app.billing.NativePlusBackend) {
        val closeNow = synchronized(initializedPlusBackends) {
            if (processTerminated) true else { initializedPlusBackends.add(backend); false }
        }
        if (closeNow) backend.close()
    }
    /** Weak current-Activity supplier for Activity-hosted platform surfaces (the biometric prompt). */
    private val currentActivityHolder = CurrentActivityHolder()
    /** Atomic host authority for IO write edges; RN's currentActivity is UI-confined. */
    internal val nativeVisitHosts = com.lavasec.app.reactnative.NativeVisitHostRegistry()
    private val startedMutable = MutableStateFlow(false)
    internal val guardProgress: com.lavasec.app.data.GuardianProgressStore by lazy {
        check(isUserUnlocked()) { "Guardian progress requires credential storage" }
        com.lavasec.app.data.GuardianProgressStore.get(this)
    }
    private lateinit var graphs: ApplicationGraphLifecycleOwner
    /** One foreground product composition shared by RN and native flow activities. */
    internal val foregroundProduct: MainProductViewModel by lazy {
        MainProductViewModel(productOwnerFactory, ::rotateCredentialGraph,
            rotateSharedOnboardingGraph = ::rotateSharedOnboardingGraph)
    }
    internal val catalogMaintenance by lazy { com.lavasec.app.data.CatalogMaintenance(this, foregroundProduct) }
    private val reactNativeRuntime: Unit by lazy {
        SoLoader.init(this, OpenSourceMergedSoMapping)
        DefaultNewArchitectureEntryPoint.load()
        // RN tolerates a missing appmodules library. This host requires its core Java
        // TurboModule provider, so fail here instead of reporting missing DeviceInfo in JS.
        SoLoader.loadLibrary("appmodules")
        Unit
    }

    /** ReactActivity reads C++ feature flags before it asks the application for reactHost. */
    internal fun initializeReactNativeRuntime(): Unit = reactNativeRuntime

    internal fun nativeReactPackages(): List<com.facebook.react.ReactPackage> =
        com.facebook.react.PackageList(this).packages.apply { add(LavaAppPackage()) }
            .apply { add(0, com.lavasec.app.reactnative.NativeReactViewPackage()) }

    /**
     * React Native foreground host used by [com.lavasec.app.reactnative.LavaReactActivity].
     *
     * Native flows share the same product composition as this foreground host.
     * Engine state and credential-generation retirement remain application owned.
     */
    override val reactHost: ReactHost by lazy {
        initializeReactNativeRuntime()
        getDefaultReactHost(
            context = applicationContext,
            packageList = nativeReactPackages(),
            // Internal/debug APKs must launch offline using the bundle produced by preBuild.
            // The AAR's debug default would otherwise try to fetch JavaScript from Metro.
            useDevSupport = false,
        )
    }

    /**
     * True while the visible activity surface is started. MainActivity publishes its
     * lifecycle here; process-scoped collectors treat entries into the foreground as the
     * app-visible trigger for retrying foreground-only work (the protection owner retries
     * an armed runtime reload on each entry). An Application-owned flow is deliberate: the
     * credential product graph outlives any activity instance and must observe the signal
     * without a UI-owned owner.
     */
    val started: StateFlow<Boolean> = startedMutable.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(currentActivityHolder)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private val visible = mutableSetOf<Activity>()
            override fun onActivityStarted(activity: Activity) {
                visible.add(activity)
                startedMutable.value = true
            }
            override fun onActivityStopped(activity: Activity) {
                visible.remove(activity)
                startedMutable.value = visible.isNotEmpty()
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) { visible.remove(activity) }
        })
        val factory = ProductionApplicationGraphFactory(
            this,
            appStarted = started,
            currentActivity = currentActivityHolder::current,
        )
        val vpn = LavaVpnServiceClient(this)
        graphs = ApplicationGraphLifecycleOwner(
            deviceGraphFactory = factory::createDevice,
            credentialGraphFactory = factory::createCredential,
            attachCredentialHost = LavaVpnRuntimeBridge::attachCredentialHost,
            restoreProtection = {
                restoreAuthoritativeProtection(
                    current = LavaVpnRuntimeBridge.state.value,
                    stop = vpn::stop,
                    start = vpn::start,
                )
            },
            retireProtection = vpn::stop,
        )
        // Application.onCreate precedes every service callback. Install the authenticated public
        // host synchronously so an Always-on start can never race an empty registry pre-unlock.
        runCatching {
            runBlocking(Dispatchers.IO) { graphs.reconcile(userUnlocked = false) }
        }
        if (isUserUnlocked()) reconcileGraphs(userUnlocked = true)
    }

    /**
     * Cancel immediately, then drain owned work without blocking the main looper that cancelled
     * SDK continuations may need. Callers retaining an emulator/application environment must wait
     * for this completion before destroying it. Credential graph rotation does not call this.
     */
    internal fun beginProcessTermination(): Deferred<Unit> {
        val backends: List<com.lavasec.app.billing.NativePlusBackend>
        val completion: CompletableDeferred<Unit>
        synchronized(initializedPlusBackends) {
            processTermination?.let { return it }
            processTerminated = true
            backends = initializedPlusBackends.toList()
            initializedPlusBackends.clear()
            completion = CompletableDeferred()
            processTermination = completion
        }
        var failure: Throwable? = null
        fun record(thrown: Throwable) {
            if (failure == null) failure = thrown
            else if (failure !== thrown) failure!!.addSuppressed(thrown)
        }
        backends.forEach { backend -> try { backend.close() } catch (thrown: Throwable) { record(thrown) } }
        val processJob = processScope.coroutineContext[Job]
        processJob?.cancel()
        completion.invokeOnCompletion { thrown ->
            if (thrown != null) android.util.Log.e("LavasecApplication", "Process cleanup failed", thrown)
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { processJob?.join() } catch (thrown: Throwable) { record(thrown) }
            backends.forEach { backend ->
                try { backend.closeAndJoin() } catch (thrown: Throwable) { record(thrown) }
            }
            if (::graphs.isInitialized) try { graphs.close() } catch (thrown: Throwable) { record(thrown) }
            val thrown = failure
            if (thrown == null) completion.complete(Unit) else completion.completeExceptionally(thrown)
        }
        return completion
    }

    override fun onTerminate() {
        beginProcessTermination()
        super.onTerminate()
    }

    internal fun handleUserUnlocked() {
        reconcileGraphs(userUnlocked = true)
    }

    internal val productOwnerFactory: StateFlow<CredentialProductOwnerFactory?>
        get() = graphs.productOwnerFactory

    /**
     * The live credential generation's chained composition, or `null` before first unlock / after
     * a graph retirement. Read by [com.lavasec.app.reactnative.LavaAppModule] to answer the
     * chained status/toggle surface and to trigger the VPN reload.
     */
    internal val plusBackend: StateFlow<com.lavasec.app.billing.NativePlusBackend?>
        get() = graphs.plusBackend

    internal val accountBackend: StateFlow<com.lavasec.app.account.AccountBackend?>
        get() = graphs.accountBackend

    internal val chainedComposition: StateFlow<ChainedComposition?>
        get() = graphs.chainedComposition

    /** Existing debug owner from the currently attached credential graph, absent when locked. */
    internal val dnsLeakCanaryBinding: CredentialDnsLeakCanaryBinding?
        get() = graphs.dnsLeakCanaryBinding.value

    internal val dnsLeakCanaryOwner: com.lavasec.app.data.CredentialDnsLeakCanaryOwner?
        get() = dnsLeakCanaryBinding?.owner

    internal fun dnsLeakCanaryOwnerFor(expectedGeneration: Long): com.lavasec.app.data.CredentialDnsLeakCanaryOwner? =
        dnsLeakCanaryBinding?.takeIf { it.graphGeneration == expectedGeneration }?.owner

    internal suspend fun rotateSharedOnboardingGraph(
        expectedGeneration: Long,
        expectedFactory: CredentialProductOwnerFactory,
        beforePublication: (CredentialGraphTransition) -> Unit,
    ): CredentialGraphTransition? = graphs.rotateCredentialGraphAfterOnboarding(
        expectedGeneration, expectedFactory, beforePublication,
    )

    internal fun rotateCredentialGraph(expectedGeneration: Long) {
        processScope.launch {
            try {
                graphs.rotateCredentialGraph(expectedGeneration)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A later durable reconciliation can retry the exact generation.
            }
        }
    }

    private fun reconcileGraphs(userUnlocked: Boolean) {
        processScope.launch {
            try {
                graphs.reconcile(userUnlocked)
                if (userUnlocked) {
                    ActivityRetentionScheduler.startIfUnlocked(this@LavasecApplication)
                    if (com.lavasec.app.edition.AppEdition.current.dynamicCatalog) {
                        com.lavasec.app.work.CatalogMaintenanceScheduler.install(this@LavasecApplication)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The next process/unlock reconciliation starts again from durable authority.
            }
        }
    }

    private fun isUserUnlocked(): Boolean = runCatching {
        getSystemService(UserManager::class.java).isUserUnlocked
    }.getOrDefault(false)
}

/** A live service must reopen the newly attached graph; reconnect alone retains the old host. */
internal suspend fun restoreAuthoritativeProtection(
    current: ProtectionState,
    stop: suspend () -> Unit,
    start: suspend () -> Unit,
) {
    when (current) {
        ProtectionState.Off,
        ProtectionState.SetupRequired,
        is ProtectionState.Failed,
        -> Unit

        is ProtectionState.Starting,
        is ProtectionState.AwaitingNetwork,
        is ProtectionState.Reconnecting,
        is ProtectionState.Protected,
        is ProtectionState.Paused,
        ProtectionState.Stopping,
        -> stop()
    }
    start()
}
