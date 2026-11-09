package com.lavasec.app.presentation

import com.lavasec.app.foundation.health.ProtectionHealthAssessment
import com.lavasec.app.foundation.health.ProtectionHealthSeverity
import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.platform.ProtectionFailure
import com.lavasec.app.platform.ProtectionState
import com.lavasec.app.platform.SettingsRoute
import com.lavasec.app.platform.StartOrigin
import com.lavasec.app.platform.VpnOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Typed protection-status mapping (slice 02): GuardProtectionHealthUi carries
 * lifecycle/connectivity/pause/message facts and no localized copy, so these
 * assertions pin the typed vocabulary instead of title strings.
 */
class ProtectionPresentationTest {
    @Test fun `internet uncertainty and wake checks preserve adverse DNS evidence and ownership`() {
        val state = ProtectionState.Protected(ConfigurationIdentity(7, "confirmed"), VpnOwnership.User)
        fun project(severity: ProtectionHealthSeverity, verified: Boolean? = false, checking: Boolean = false) =
            GuardProtectionHealthUi.from(state, ProtectionHealthAssessment(severity,
                com.lavasec.app.foundation.health.ProtectionHealthAction.TURN_OFF), 1000,
                internetValidated = verified, rechecking = checking)
        assertEquals(GuardConnectivityUi.INTERNET_UNVERIFIED, project(ProtectionHealthSeverity.HEALTHY).connectivity)
        assertTrue(project(ProtectionHealthSeverity.HEALTHY).canPause)
        assertEquals(GuardConnectivityUi.HEALTHY, project(ProtectionHealthSeverity.HEALTHY, true).connectivity)
        assertEquals(GuardConnectivityUi.RECHECKING, project(ProtectionHealthSeverity.HEALTHY, checking = true).connectivity)
        assertFalse(project(ProtectionHealthSeverity.HEALTHY, checking = true).canPause)
        for (severity in listOf(ProtectionHealthSeverity.DNS_SLOW, ProtectionHealthSeverity.NEEDS_RECONNECT,
            ProtectionHealthSeverity.NETWORK_UNAVAILABLE)) {
            assertEquals(project(severity, null).connectivity, project(severity, checking = true).connectivity)
        }
        val managed = GuardProtectionHealthUi.from(state.copy(ownership = VpnOwnership.SystemAlwaysOn),
            ProtectionHealthAssessment(ProtectionHealthSeverity.HEALTHY,
                com.lavasec.app.foundation.health.ProtectionHealthAction.TURN_OFF), 1000, internetValidated = false)
        assertEquals(GuardPrimaryAction.OpenSettings(SettingsRoute.VpnSettings), managed.primaryAction)
        assertFalse(managed.canPause)
    }
    @Test
    fun `maps every lifecycle branch without optimistic protection`() {
        val identity = ConfigurationIdentity(generation = 7, fingerprint = "confirmed")
        val cases = listOf(
            Case(ProtectionState.Off, GuardLifecycleUi.INACTIVE, GuardPrimaryAction.TurnOn),
            Case(ProtectionState.SetupRequired, GuardLifecycleUi.INACTIVE, GuardPrimaryAction.TurnOn),
            Case(
                ProtectionState.Starting(StartOrigin.VisibleUser, 1),
                GuardLifecycleUi.STARTING,
                null,
            ),
            Case(
                ProtectionState.AwaitingNetwork(StartOrigin.VisibleUser, 1),
                GuardLifecycleUi.AWAITING_NETWORK,
                null,
            ),
            Case(
                ProtectionState.Reconnecting(StartOrigin.VisibleUser, 1),
                GuardLifecycleUi.RECONNECTING,
                null,
            ),
            Case(
                ProtectionState.Protected(identity, VpnOwnership.User),
                GuardLifecycleUi.PROTECTED,
                GuardPrimaryAction.TurnOff,
            ),
            Case(ProtectionState.Paused(400_000L), GuardLifecycleUi.PAUSED, GuardPrimaryAction.Resume),
            Case(ProtectionState.Stopping, GuardLifecycleUi.STOPPING, null),
        )

        cases.forEach { expected ->
            val actual = GuardProtectionHealthUi.from(expected.state, nowEpochMillis = 100_000L)

            assertEquals(expected.state.toString(), expected.lifecycle, actual.lifecycle)
            assertEquals(expected.state.toString(), expected.action, actual.primaryAction)
        }
    }

    @Test
    fun `maps every failure branch to its recoverable or exact settings action`() {
        val cases = listOf(
            FailureCase(ProtectionFailure.ConsentRequired(SettingsRoute.VpnSettings), GuardPrimaryAction.OpenSettings(SettingsRoute.VpnSettings)),
            FailureCase(ProtectionFailure.EngineUnavailable, GuardPrimaryAction.Reconnect),
            FailureCase(ProtectionFailure.ArtifactMissing, GuardPrimaryAction.Reconnect),
            FailureCase(ProtectionFailure.ArtifactCorrupt, GuardPrimaryAction.Reconnect),
            FailureCase(ProtectionFailure.ArtifactMismatched, GuardPrimaryAction.Reconnect),
            FailureCase(ProtectionFailure.ForegroundUnavailable(), GuardPrimaryAction.Reconnect),
            FailureCase(ProtectionFailure.ForegroundUnavailable(SettingsRoute.NotificationSettings), GuardPrimaryAction.OpenSettings(SettingsRoute.NotificationSettings)),
            FailureCase(ProtectionFailure.PrivateDnsUnsupported(SettingsRoute.NetworkSettings), GuardPrimaryAction.OpenSettings(SettingsRoute.NetworkSettings)),
            FailureCase(ProtectionFailure.LockdownEnabled(SettingsRoute.VpnSettings), GuardPrimaryAction.OpenSettings(SettingsRoute.VpnSettings)),
            FailureCase(ProtectionFailure.LockdownUnknown(SettingsRoute.VpnSettings), GuardPrimaryAction.OpenSettings(SettingsRoute.VpnSettings)),
            FailureCase(ProtectionFailure.AlwaysOnStopRejected(SettingsRoute.VpnSettings), GuardPrimaryAction.OpenSettings(SettingsRoute.VpnSettings)),
            FailureCase(ProtectionFailure.Revoked, GuardPrimaryAction.TurnOn),
            FailureCase(ProtectionFailure.EstablishmentFailed, GuardPrimaryAction.Reconnect),
            FailureCase(ProtectionFailure.PersistenceUnavailable, GuardPrimaryAction.Reconnect),
            FailureCase(ProtectionFailure.DependencyUnavailable, GuardPrimaryAction.Reconnect),
            FailureCase(ProtectionFailure.NetworkUnavailable, null),
            FailureCase(ProtectionFailure.TunnelEnded, GuardPrimaryAction.Reconnect),
            FailureCase(ProtectionFailure.TunnelReadFailed, GuardPrimaryAction.Reconnect),
        )

        cases.forEach { expected ->
            val actual = GuardProtectionHealthUi.from(
                ProtectionState.Failed(expected.failure),
                nowEpochMillis = 0L,
            )

            assertEquals(expected.failure.toString(), expected.action, actual.primaryAction)
        }
    }

    @Test
    fun `only confirmed protected state and always-on stop rejection use the protected lifecycle`() {
        val identity = ConfigurationIdentity(generation = 7, fingerprint = "confirmed")
        val states = listOf(
            ProtectionState.Off,
            ProtectionState.SetupRequired,
            ProtectionState.Starting(StartOrigin.VisibleUser, 1),
            ProtectionState.AwaitingNetwork(StartOrigin.VisibleUser, 1),
            ProtectionState.Reconnecting(StartOrigin.VisibleUser, 1),
            ProtectionState.Protected(identity, VpnOwnership.User),
            ProtectionState.Paused(1),
            ProtectionState.Stopping,
        ) + allFailures.map(ProtectionState::Failed)

        states.forEach { state ->
            val actual = GuardProtectionHealthUi.from(state, nowEpochMillis = 0L)
            val maySayProtected = state is ProtectionState.Protected ||
                (state as? ProtectionState.Failed)?.failure is ProtectionFailure.AlwaysOnStopRejected

            if (maySayProtected) {
                assertEquals(state.toString(), GuardLifecycleUi.PROTECTED, actual.lifecycle)
            } else {
                assertFalse(state.toString(), actual.lifecycle == GuardLifecycleUi.PROTECTED)
            }
        }
    }

    @Test
    fun `revoked VPN permission is presented as inactive and requires a fresh turn on`() {
        val actual = GuardProtectionHealthUi.from(
            state = ProtectionState.Failed(ProtectionFailure.Revoked),
            nowEpochMillis = 0L,
        )

        assertEquals(GuardLifecycleUi.INACTIVE, actual.lifecycle)
        assertEquals(GuardMessageUi.VPN_PERMISSION_REVOKED, actual.message)
        assertEquals(GuardPrimaryAction.TurnOn, actual.primaryAction)
        assertFalse(actual.primaryAction == GuardPrimaryAction.Reconnect)
    }

    @Test
    fun `system owned and unproven API 28 protection expose VPN settings without stop or pause`() {
        val identity = ConfigurationIdentity(generation = 7, fingerprint = "confirmed")
        val expectedAction = GuardPrimaryAction.OpenSettings(SettingsRoute.VpnSettings)

        listOf(VpnOwnership.SystemAlwaysOn, VpnOwnership.UnknownApi28).forEach { ownership ->
            val actual = GuardProtectionHealthUi.from(
                ProtectionState.Protected(identity, ownership),
                nowEpochMillis = 0L,
            )

            assertEquals(ownership.name, expectedAction, actual.primaryAction)
            assertFalse(ownership.name, actual.canPause)
            assertFalse(ownership.name, actual.primaryAction == GuardPrimaryAction.TurnOff)
        }
    }

    @Test
    fun `UnknownApi28 carries the ownership-unverified message instead of asserting always-on`() {
        val actual = GuardProtectionHealthUi.from(
            state = ProtectionState.Protected(
                ConfigurationIdentity(generation = 7, fingerprint = "confirmed"),
                VpnOwnership.UnknownApi28,
            ),
            nowEpochMillis = 0L,
        )

        assertEquals(GuardPrimaryAction.OpenSettings(SettingsRoute.VpnSettings), actual.primaryAction)
        assertEquals(GuardMessageUi.OWNERSHIP_UNVERIFIED_API_28, actual.message)
        assertFalse(actual.message == GuardMessageUi.ALWAYS_ON_MANAGED)
    }

    @Test
    fun `missing health evidence stays unavailable instead of claiming local filtering health`() {
        val actual = GuardProtectionHealthUi.from(
            state = ProtectionState.Protected(
                ConfigurationIdentity(generation = 7, fingerprint = "confirmed"),
                VpnOwnership.User,
            ),
            nowEpochMillis = 0L,
        )

        assertEquals(GuardLifecycleUi.PROTECTED, actual.lifecycle)
        assertEquals(GuardConnectivityUi.UNAVAILABLE, actual.connectivity)
        assertFalse(actual.connectivity == GuardConnectivityUi.HEALTHY)
    }

    @Test
    fun `Android ownership preserves every health severity while forcing VPN settings`() {
        val identity = ConfigurationIdentity(generation = 7, fingerprint = "confirmed")

        VpnOwnership.entries.forEach { ownership ->
            ProtectionHealthSeverity.entries.forEach { severity ->
                val health = ProtectionHealthAssessment(
                    severity = severity,
                    primaryAction = com.lavasec.app.foundation.health.ProtectionHealthAction.TURN_OFF,
                )
                val user = GuardProtectionHealthUi.from(
                    state = ProtectionState.Protected(identity, VpnOwnership.User),
                    health = health,
                    nowEpochMillis = 0L,
                )
                val actual = GuardProtectionHealthUi.from(
                    state = ProtectionState.Protected(identity, ownership),
                    health = health,
                    nowEpochMillis = 0L,
                )

                if (ownership == VpnOwnership.User) {
                    assertEquals(severity.name, user, actual)
                } else {
                    assertEquals(severity.name, user.lifecycle, actual.lifecycle)
                    assertEquals(severity.name, user.connectivity, actual.connectivity)
                    assertEquals(
                        severity.name,
                        GuardPrimaryAction.OpenSettings(SettingsRoute.VpnSettings),
                        actual.primaryAction,
                    )
                    assertFalse(severity.name, actual.canPause)
                    assertEquals(
                        severity.name,
                        if (ownership == VpnOwnership.SystemAlwaysOn) {
                            GuardMessageUi.ALWAYS_ON_MANAGED
                        } else {
                            GuardMessageUi.OWNERSHIP_UNVERIFIED_API_28
                        },
                        actual.message,
                    )
                }
            }
        }
    }

    @Test
    fun `maps every health branch only from confirmed protected lifecycle evidence`() {
        val protected = ProtectionState.Protected(
            ConfigurationIdentity(generation = 7, fingerprint = "confirmed"),
            VpnOwnership.User,
        )
        val cases = listOf(
            HealthCase(
                ProtectionHealthSeverity.HEALTHY,
                GuardConnectivityUi.HEALTHY,
                GuardPrimaryAction.TurnOff,
            ),
            HealthCase(
                ProtectionHealthSeverity.USING_ENCRYPTED_FALLBACK,
                GuardConnectivityUi.ENCRYPTED_FALLBACK,
                GuardPrimaryAction.TurnOff,
            ),
            HealthCase(
                ProtectionHealthSeverity.USING_DEVICE_DNS_FALLBACK,
                GuardConnectivityUi.DEVICE_DNS_FALLBACK,
                GuardPrimaryAction.TurnOff,
            ),
            HealthCase(ProtectionHealthSeverity.RECOVERING, GuardConnectivityUi.RECOVERING, null),
            HealthCase(
                ProtectionHealthSeverity.DNS_SLOW,
                GuardConnectivityUi.DNS_SLOW,
                GuardPrimaryAction.Reconnect,
            ),
            HealthCase(
                ProtectionHealthSeverity.NETWORK_UNAVAILABLE,
                GuardConnectivityUi.NETWORK_UNAVAILABLE,
                null,
            ),
            HealthCase(
                ProtectionHealthSeverity.NEEDS_RECONNECT,
                GuardConnectivityUi.NEEDS_RECONNECT,
                GuardPrimaryAction.Reconnect,
            ),
        )

        cases.forEach { expected ->
            val actual = GuardProtectionHealthUi.from(
                state = protected,
                health = ProtectionHealthAssessment(
                    expected.health,
                    com.lavasec.app.foundation.health.ProtectionHealthAction.TURN_OFF,
                ),
                nowEpochMillis = 0L,
            )

            assertEquals(expected.health.name, GuardLifecycleUi.PROTECTED, actual.lifecycle)
            assertEquals(expected.health.name, expected.connectivity, actual.connectivity)
            assertEquals(expected.health.name, expected.action, actual.primaryAction)
        }
    }

    @Test
    fun `formats pause remaining time from injected now`() {
        val actual = GuardProtectionHealthUi.from(
            state = ProtectionState.Paused(untilEpochMillis = 400_000L),
            nowEpochMillis = 100_000L,
        )

        assertEquals(GuardLifecycleUi.PAUSED, actual.lifecycle)
        assertEquals(400_000L, actual.pause?.deadlineEpochMillis)
        assertEquals(5L, actual.pause?.remainingMinutes)
        assertEquals(GuardPrimaryAction.Resume, actual.primaryAction)
    }

    @Test
    fun `pause deadline formatter collapses expired and sub-minute deadlines to zero`() {
        assertEquals(5L, com.lavasec.app.presentation.guard.pauseDeadlineMinutes(400_000L, 100_000L))
        // Expired deadline: negative remaining time fails closed to zero minutes.
        assertEquals(0L, com.lavasec.app.presentation.guard.pauseDeadlineMinutes(90_000L, 100_000L))
        // Exactly due.
        assertEquals(0L, com.lavasec.app.presentation.guard.pauseDeadlineMinutes(100_000L, 100_000L))
        // One minute in the future ceil-rounds to one.
        assertEquals(1L, com.lavasec.app.presentation.guard.pauseDeadlineMinutes(100_001L, 100_000L))
    }

    @Test
    fun `transient states expose no executable action`() {
        val transientStates = listOf(
            ProtectionState.Starting(StartOrigin.VisibleUser, 1),
            ProtectionState.AwaitingNetwork(StartOrigin.VisibleUser, 1),
            ProtectionState.Reconnecting(StartOrigin.VisibleUser, 1),
            ProtectionState.Stopping,
            ProtectionState.Failed(ProtectionFailure.NetworkUnavailable),
        )

        transientStates.forEach { state ->
            assertNull(
                state.toString(),
                GuardProtectionHealthUi.from(state, nowEpochMillis = 0L).primaryAction,
            )
        }
    }

    private data class Case(
        val state: ProtectionState,
        val lifecycle: GuardLifecycleUi,
        val action: GuardPrimaryAction?,
    )

    private data class FailureCase(
        val failure: ProtectionFailure,
        val action: GuardPrimaryAction?,
    )

    private data class HealthCase(
        val health: ProtectionHealthSeverity,
        val connectivity: GuardConnectivityUi,
        val action: GuardPrimaryAction?,
    )

    private val allFailures = listOf(
        ProtectionFailure.ConsentRequired(SettingsRoute.VpnSettings),
        ProtectionFailure.EngineUnavailable,
        ProtectionFailure.ArtifactMissing,
        ProtectionFailure.ArtifactCorrupt,
        ProtectionFailure.ArtifactMismatched,
        ProtectionFailure.ForegroundUnavailable(),
        ProtectionFailure.PrivateDnsUnsupported(SettingsRoute.NetworkSettings),
        ProtectionFailure.LockdownEnabled(SettingsRoute.VpnSettings),
        ProtectionFailure.LockdownUnknown(SettingsRoute.VpnSettings),
        ProtectionFailure.AlwaysOnStopRejected(SettingsRoute.VpnSettings),
        ProtectionFailure.Revoked,
        ProtectionFailure.EstablishmentFailed,
        ProtectionFailure.PersistenceUnavailable,
        ProtectionFailure.DependencyUnavailable,
        ProtectionFailure.NetworkUnavailable,
        ProtectionFailure.TunnelEnded,
        ProtectionFailure.TunnelReadFailed,
    )
}
