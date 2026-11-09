package com.lavasec.app.platform

import com.lavasec.app.foundation.pause.PauseDuration
import kotlinx.coroutines.flow.StateFlow

enum class StartOrigin {
    VisibleUser,
    StickyRestart,
    SystemAlwaysOn,
    Reload,
    Resume,
}

enum class VpnOwnership { User, SystemAlwaysOn, UnknownApi28 }

enum class LockdownStatus { Disabled, Enabled, UnknownApi28 }

/**
 * Runtime VPN facts observable by a consumer [android.net.VpnService].
 *
 * Android 7+ can configure Always-on VPN, but [android.net.VpnService.isAlwaysOn]
 * and [android.net.VpnService.isLockdownEnabled] are public only from API 29.
 * On API 28 neither fact is observable without device/profile-owner privileges,
 * so absence of evidence must not be interpreted as user ownership or disabled
 * lockdown.
 */
data class PlatformVpnFacts(
    val ownership: VpnOwnership,
    val lockdown: LockdownStatus,
) {
    companion object {
        fun api28(): PlatformVpnFacts = PlatformVpnFacts(
            ownership = VpnOwnership.UnknownApi28,
            lockdown = LockdownStatus.UnknownApi28,
        )

        /** Values must be read from [android.net.VpnService] on API 29+. */
        fun api29(alwaysOn: Boolean, lockdown: Boolean): PlatformVpnFacts = PlatformVpnFacts(
            ownership = if (alwaysOn) VpnOwnership.SystemAlwaysOn else VpnOwnership.User,
            lockdown = if (lockdown) LockdownStatus.Enabled else LockdownStatus.Disabled,
        )
    }
}

sealed interface SettingsRoute {
    data object VpnSettings : SettingsRoute
    data object NetworkSettings : SettingsRoute
    data object NotificationSettings : SettingsRoute
}

sealed interface ProtectionFailure {
    val route: SettingsRoute? get() = null

    data class ConsentRequired(override val route: SettingsRoute) : ProtectionFailure
    data object EngineUnavailable : ProtectionFailure
    data object ArtifactMissing : ProtectionFailure
    data object ArtifactCorrupt : ProtectionFailure
    data object ArtifactMismatched : ProtectionFailure
    data class ForegroundUnavailable(override val route: SettingsRoute? = null) : ProtectionFailure
    data class PrivateDnsUnsupported(override val route: SettingsRoute) : ProtectionFailure
    data class LockdownEnabled(override val route: SettingsRoute) : ProtectionFailure
    data class LockdownUnknown(override val route: SettingsRoute) : ProtectionFailure
    data class AlwaysOnStopRejected(override val route: SettingsRoute) : ProtectionFailure
    data object Revoked : ProtectionFailure
    data object EstablishmentFailed : ProtectionFailure
    data object PersistenceUnavailable : ProtectionFailure
    data object DependencyUnavailable : ProtectionFailure
    data object NetworkUnavailable : ProtectionFailure
    /** The active Android TUN descriptor reached EOF without an intentional retirement. */
    data object TunnelEnded : ProtectionFailure
    /** The active Android TUN descriptor failed while reading a packet. */
    data object TunnelReadFailed : ProtectionFailure
}

sealed interface ProtectionState {
    data object Off : ProtectionState
    data object SetupRequired : ProtectionState
    data class Starting(val origin: StartOrigin, val generation: Long) : ProtectionState
    data class AwaitingNetwork(val origin: StartOrigin, val generation: Long) : ProtectionState
    data class Reconnecting(val origin: StartOrigin, val generation: Long) : ProtectionState
    data class Protected(
        val configurationIdentity: ConfigurationIdentity,
        val ownership: VpnOwnership,
    ) : ProtectionState
    data class Paused(val untilEpochMillis: Long) : ProtectionState
    data object Stopping : ProtectionState
    data class Failed(val failure: ProtectionFailure) : ProtectionState

    /**
     * Whether a live runtime exists that the app may ask to reload.
     *
     * [Stopping] is deliberately NOT live: a preference write that completes while a concurrent
     * stop is tearing the runtime down must not queue a reload, because the new service start
     * would receive a newer start ID than the stop and restart protection just after the user
     * turned it off. [Off], [SetupRequired], and [Failed] have no runtime to reload.
     */
    val isLiveForReload: Boolean
        get() = when (this) {
            Off,
            SetupRequired,
            Stopping,
            is Failed,
            -> false

            is Starting,
            is AwaitingNetwork,
            is Reconnecting,
            is Protected,
            is Paused,
            -> true
        }

    /**
     * A stable, user-facing runtime-mode code for this protection state.
     *
     * Lets a surface distinguish an intentionally inactive protection (`off`, which includes a
     * setup or terminal failure) or an intentional `paused` from a `running` session that is merely
     * settling — a chained preference that is enabled while protection is off must not be rendered
     * as "recovering". The codes are a wire contract consumed by the JS layer.
     */
    val runtimeModeCode: String
        get() = when (this) {
            Off,
            SetupRequired,
            Stopping,
            is Failed,
            -> "off"

            is Paused -> "paused"

            is Starting,
            is AwaitingNetwork,
            is Reconnecting,
            is Protected,
            -> "running"
        }
}

interface ProtectionController {
    val state: StateFlow<ProtectionState>

    suspend fun start()

    suspend fun stop()

    suspend fun pause(duration: PauseDuration)

    suspend fun resume()

    /** Requests a same-session resolver/tunnel rebuild without changing protection desire. */
    suspend fun reconnect()
}

/**
 * Whether a reload requested against [expectedServiceGeneration] may run while
 * [currentServiceGeneration] is installed.
 *
 * A chained preference write can snapshot a still-`Protected` runtime and enqueue a reload behind a
 * queued stop. The stop retires the session generation, so the reload would otherwise start a new
 * session and override the user turning protection off. Binding the reload to the generation
 * observed at enqueue time makes a stale reload a no-op; an unbound reload (`null`, used by an
 * internal runtime rebuild) always proceeds.
 */
fun vpnReloadIsAdmitted(
    expectedServiceGeneration: Long?,
    currentServiceGeneration: Long?,
): Boolean =
    expectedServiceGeneration == null || expectedServiceGeneration == currentServiceGeneration
