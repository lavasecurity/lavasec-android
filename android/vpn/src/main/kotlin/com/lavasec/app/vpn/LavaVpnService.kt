package com.lavasec.app.vpn

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.IBinder
import com.lavasec.app.foundation.pause.PauseDuration
import com.lavasec.app.platform.ProtectionController
import com.lavasec.app.platform.ProtectionState
import com.lavasec.app.platform.StartOrigin
import com.lavasec.app.vpn.chained.ChainedActivitySignal
import com.lavasec.app.vpn.chained.ChainedRuntimeMode
import com.lavasec.app.vpn.chained.nextChainedActivitySignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Process bridge installed by the app-owned device/credential graph lifecycle. */
object LavaVpnRuntimeBridge {
    private val hosts = CredentialVpnHostRegistry()
    private val serviceState = ExactVpnServiceState()
    private val healthState = ExactVpnHealthState()
    private val chainedActivityState = MutableStateFlow(
        ChainedActivitySignal(
            active = false,
            mode = ChainedRuntimeMode.NONE,
            sequence = 0L,
        ),
    )

    val state: StateFlow<ProtectionState> get() = serviceState.state
    val health: StateFlow<VpnHealthStatus> get() = healthState.state

    /**
     * The installed service session generation, or `null` when no runtime is installed.
     *
     * A preference-triggered reload snapshots this and passes it to the service, so a reload queued
     * behind a stop (which retired the generation) is a no-op rather than restarting protection.
     */
    val serviceGeneration: Long? get() = serviceState.currentGenerationOrNull()

    /**
     * The live chained-activity publications, versioned so an attempt outcome that does not change
     * the Boolean (a false→false DNS-only fallback) is still a distinct emission the screen can
     * refresh on, and carrying the installed mode so a settled fallback is distinguishable from an
     * in-progress chained startup.
     */
    val chainedActivity: StateFlow<ChainedActivitySignal> = chainedActivityState.asStateFlow()

    fun attachCredentialHost(host: CredentialVpnHostPort): AutoCloseable = hosts.attach(host)

    /**
     * Published by the owning runtime on every chained install/clear. The sequence advances even
     * when [active]/[mode] repeat, so observers are notified of the outcome.
     */
    fun publishChainedActivity(
        active: Boolean,
        mode: ChainedRuntimeMode,
        runningConfigurationGeneration: Long = 0L,
    ) {
        chainedActivityState.update { previous ->
            nextChainedActivitySignal(previous, active, mode, runningConfigurationGeneration)
        }
    }

    internal fun hosts(): CredentialVpnHostRegistry = hosts
    internal fun exactState(): ExactVpnServiceState = serviceState
    internal fun exactHealth(): ExactVpnHealthState = healthState
}

class LavaVpnServiceClient(
    context: Context,
) : ProtectionController {
    private val appContext = context.applicationContext

    override val state: StateFlow<ProtectionState> get() = LavaVpnRuntimeBridge.state

    override suspend fun start() {
        appContext.startForegroundService(serviceIntent(LavaVpnService.ACTION_START))
    }

    override suspend fun stop() {
        appContext.startService(serviceIntent(LavaVpnService.ACTION_STOP))
    }

    override suspend fun pause(duration: PauseDuration) {
        appContext.startService(
            serviceIntent(LavaVpnService.ACTION_PAUSE).putExtra(
                LavaVpnService.EXTRA_PAUSE_MINUTES,
                duration.minutes,
            ),
        )
    }

    override suspend fun resume() {
        appContext.startService(serviceIntent(LavaVpnService.ACTION_RESUME))
    }

    override suspend fun reconnect() {
        appContext.startService(serviceIntent(LavaVpnService.ACTION_RECONNECT))
    }

    /**
     * Reopens the exact credential attachment after an authoritative filter generation changes.
     *
     * [expectedServiceGeneration] is the service generation observed when the caller decided to
     * reload; the service no-ops a reload whose generation is no longer installed, so a reload
     * queued behind a stop cannot restart protection. `null` is an internal runtime rebuild.
     */
    suspend fun reload(expectedServiceGeneration: Long? = null) {
        appContext.startService(
            serviceIntent(LavaVpnService.ACTION_RELOAD).apply {
                expectedServiceGeneration?.let {
                    putExtra(LavaVpnService.EXTRA_EXPECTED_SERVICE_GENERATION, it)
                }
            },
        )
    }

    private fun serviceIntent(action: String): Intent =
        Intent(appContext, LavaVpnService::class.java).setAction(action)
}

open class LavaVpnService : VpnService() {
    private lateinit var commandOwner: VpnServiceCommandOwner

    override fun onCreate() {
        super.onCreate()
        commandOwner = VpnServiceCommandOwner(
            foreground = VpnForegroundAdmission { enterForeground() },
            hostRegistry = LavaVpnRuntimeBridge.hosts(),
            state = LavaVpnRuntimeBridge.exactState(),
            health = LavaVpnRuntimeBridge.exactHealth(),
            runtimeFactory = VpnOwnedRuntimeFactory { request ->
                ProductionVpnOwnedRuntime(
                    service = this,
                    request = request,
                    // A pause→filtering mode change rebuilds through the same command path an
                    // explicit reload uses; the service is already foreground, so a plain
                    // startService delivers the reload without a second foreground promotion.
                    // The runtime's service generation binds the reload, so a stop/newer start
                    // that retires it makes the queued rebuild a no-op.
                    requestRuntimeRebuild = { expectedServiceGeneration ->
                        startService(
                            Intent(this, LavaVpnService::class.java)
                                .setAction(ACTION_RELOAD)
                                .putExtra(
                                    EXTRA_EXPECTED_SERVICE_GENERATION,
                                    expectedServiceGeneration,
                                ),
                        )
                    },
                    publishChainedActivity = LavaVpnRuntimeBridge::publishChainedActivity,
                )
            },
            terminalExit = VpnTerminalServiceExit {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            },
            // An accepted terminal retirement is the exact service-owner fence; clearing the chained
            // mode here (under the command owner's monitor) means a concurrent Start cannot install
            // a replacement between the acceptance and the clear.
            clearChainedActivity = {
                LavaVpnRuntimeBridge.publishChainedActivity(false, ChainedRuntimeMode.NONE)
            },
            // The replacement's endpoint preflight runs here, never on the main thread, so a slow
            // DNS lookup cannot block `onStartCommand` (or ANR).
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = classifyVpnServiceCommand(intent)
            ?: return finishTerminalStart(startId)
        // A superseded reload is not terminal: a newer start owns the runtime, and finishing this
        // start would remove its foreground notification, stop the service, and close that runtime.
        return if (isTerminalStart(commandOwner.handle(command))) {
            finishTerminalStart(startId)
        } else {
            START_STICKY
        }
    }

    override fun onBind(intent: Intent): IBinder? =
        if (intent.action == SERVICE_INTERFACE) super.onBind(intent) else null

    override fun onRevoke() {
        commandOwner.handle(VpnServiceCommand.Revoke)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (::commandOwner.isInitialized) commandOwner.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun enterForeground() {
        val notifications = VpnForegroundNotificationFactory(this)
        notifications.ensureChannel()
        startForeground(FOREGROUND_NOTIFICATION_ID, notifications.create())
    }

    private fun finishTerminalStart(startId: Int): Int {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
        return START_NOT_STICKY
    }

    companion object {
        const val ACTION_START = "com.lavasec.app.vpn.action.START"
        const val ACTION_STOP = "com.lavasec.app.vpn.action.STOP"
        const val ACTION_PAUSE = "com.lavasec.app.vpn.action.PAUSE"
        const val ACTION_RESUME = "com.lavasec.app.vpn.action.RESUME"
        const val ACTION_RECONNECT = "com.lavasec.app.vpn.action.RECONNECT"
        const val ACTION_RELOAD = "com.lavasec.app.vpn.action.RELOAD"
        const val EXTRA_PAUSE_MINUTES = "com.lavasec.app.vpn.extra.PAUSE_MINUTES"

        /**
         * The service session generation a preference-triggered reload was requested against.
         * Absent for an internal runtime rebuild (which is bound to the live runtime).
         */
        const val EXTRA_EXPECTED_SERVICE_GENERATION =
            "com.lavasec.app.vpn.extra.EXPECTED_SERVICE_GENERATION"
        const val FOREGROUND_NOTIFICATION_ID = 1_001
        const val FOREGROUND_CHANNEL_ID = "lavasec-protection"
    }
}

internal fun classifyVpnServiceCommand(intent: Intent?): VpnServiceCommand? = when (intent?.action) {
    LavaVpnService.ACTION_START -> VpnServiceCommand.Start(StartOrigin.VisibleUser)
    LavaVpnService.ACTION_STOP -> VpnServiceCommand.Stop
    LavaVpnService.ACTION_PAUSE -> intent.getIntExtra(
        LavaVpnService.EXTRA_PAUSE_MINUTES,
        Int.MIN_VALUE,
    ).let(PauseDuration::fromMinutes)?.let(VpnServiceCommand::Pause)
    LavaVpnService.ACTION_RESUME -> VpnServiceCommand.Resume
    LavaVpnService.ACTION_RECONNECT -> VpnServiceCommand.Reconnect
    LavaVpnService.ACTION_RELOAD -> VpnServiceCommand.Reload(
        expectedServiceGeneration = intent
            .takeIf { it.hasExtra(LavaVpnService.EXTRA_EXPECTED_SERVICE_GENERATION) }
            ?.getLongExtra(LavaVpnService.EXTRA_EXPECTED_SERVICE_GENERATION, 0L)
            ?.takeIf { it > 0L },
    )
    VpnService.SERVICE_INTERFACE -> VpnServiceCommand.Start(StartOrigin.SystemAlwaysOn)
    null -> if (intent == null) VpnServiceCommand.Start(StartOrigin.StickyRestart) else null
    else -> null
}
