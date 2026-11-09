package com.lavasec.app.presentation.guard

import com.lavasec.app.presentation.GuardConnectivityUi
import com.lavasec.app.presentation.GuardLifecycleUi
import com.lavasec.app.presentation.GuardMessageUi
import com.lavasec.app.presentation.GuardPauseUi
import com.lavasec.app.presentation.GuardPrimaryAction
import com.lavasec.app.presentation.GuardProtectionHealthUi
import com.lavasec.app.presentation.R
import com.lavasec.app.presentation.mascot.GuardianMascotState
import com.lavasec.app.platform.SettingsRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Typed Guard status mapping: the mascot pose and the copy resources derive from
 * the typed GuardProtectionHealthUi fields, never from localized title strings.
 * The iOS baseline is GuardianView.swift `guardianState` over
 * `protectionConnectivitySeverity`.
 */
class GuardStatusMappingTest {
    @Test
    fun `pause override always wins the mascot pose`() {
        val paused = health(
            lifecycle = GuardLifecycleUi.PAUSED,
            connectivity = GuardConnectivityUi.UNAVAILABLE,
            pause = GuardPauseUi(deadlineEpochMillis = 400_000L, remainingMinutes = 5L),
            action = GuardPrimaryAction.Resume,
        )

        assertEquals(GuardianMascotState.PAUSED, paused.toGuardianMascotState())
    }

    @Test
    fun `protected connectivity severities map to the iOS mascot poses`() {
        val cases = mapOf(
            GuardConnectivityUi.HEALTHY to GuardianMascotState.AWAKE,
            GuardConnectivityUi.ENCRYPTED_FALLBACK to GuardianMascotState.AWAKE,
            GuardConnectivityUi.DEVICE_DNS_FALLBACK to GuardianMascotState.AWAKE,
            GuardConnectivityUi.UNAVAILABLE to GuardianMascotState.AWAKE,
            GuardConnectivityUi.RECOVERING to GuardianMascotState.RETRYING,
            GuardConnectivityUi.NETWORK_UNAVAILABLE to GuardianMascotState.RETRYING,
            GuardConnectivityUi.DNS_SLOW to GuardianMascotState.CONCERNED,
            GuardConnectivityUi.NEEDS_RECONNECT to GuardianMascotState.CONCERNED,
        )

        cases.forEach { (connectivity, expected) ->
            val actual = health(
                lifecycle = GuardLifecycleUi.PROTECTED,
                connectivity = connectivity,
                action = GuardPrimaryAction.TurnOff,
            )

            assertEquals(connectivity.name, expected, actual.toGuardianMascotState())
        }
    }

    @Test
    fun `transient lifecycles wake and inactive sleeps`() {
        listOf(
            GuardLifecycleUi.STARTING,
            GuardLifecycleUi.AWAITING_NETWORK,
            GuardLifecycleUi.RECONNECTING,
            GuardLifecycleUi.STOPPING,
        ).forEach { lifecycle ->
            assertEquals(
                lifecycle.name,
                GuardianMascotState.WAKING,
                health(lifecycle, GuardConnectivityUi.UNAVAILABLE).toGuardianMascotState(),
            )
        }
        assertEquals(
            GuardianMascotState.SLEEPING,
            health(GuardLifecycleUi.INACTIVE, GuardConnectivityUi.UNAVAILABLE)
                .toGuardianMascotState(),
        )
    }

    @Test
    fun `attention states are concerned whether settings-owned or reconnect-needed`() {
        val settings = health(
            lifecycle = GuardLifecycleUi.ATTENTION,
            connectivity = GuardConnectivityUi.UNAVAILABLE,
            action = GuardPrimaryAction.OpenSettings(SettingsRoute.VpnSettings),
        )
        val reconnect = health(
            lifecycle = GuardLifecycleUi.ATTENTION,
            connectivity = GuardConnectivityUi.UNAVAILABLE,
            action = GuardPrimaryAction.Reconnect,
        )

        assertEquals(GuardianMascotState.CONCERNED, settings.toGuardianMascotState())
        assertEquals(GuardianMascotState.CONCERNED, reconnect.toGuardianMascotState())
    }

    @Test
    fun `only protected healthy or fallback states reach the awake tap acknowledgement`() {
        // The awake pose gates the mascot tap gratitude; the typed mapping must
        // never yield AWAKE for attention, transient, or inactive states.
        val awake = listOf(
            GuardConnectivityUi.HEALTHY,
            GuardConnectivityUi.ENCRYPTED_FALLBACK,
            GuardConnectivityUi.DEVICE_DNS_FALLBACK,
            GuardConnectivityUi.PLAIN_DNS_FALLBACK,
            GuardConnectivityUi.UNAVAILABLE,
        )
        val other = GuardConnectivityUi.entries - awake.toSet()

        awake.forEach { connectivity ->
            assertEquals(
                connectivity.name,
                GuardianMascotState.AWAKE,
                health(GuardLifecycleUi.PROTECTED, connectivity).toGuardianMascotState(),
            )
        }
        other.forEach { connectivity ->
            assertEquals(
                connectivity.name,
                false,
                health(GuardLifecycleUi.PROTECTED, connectivity).toGuardianMascotState() ==
                    GuardianMascotState.AWAKE,
            )
        }
        listOf(
            GuardLifecycleUi.INACTIVE,
            GuardLifecycleUi.ATTENTION,
            GuardLifecycleUi.STARTING,
            GuardLifecycleUi.PAUSED,
        ).forEach { lifecycle ->
            val connectivity = if (lifecycle == GuardLifecycleUi.PAUSED) {
                GuardConnectivityUi.UNAVAILABLE
            } else {
                GuardConnectivityUi.HEALTHY
            }
            val pause = if (lifecycle == GuardLifecycleUi.PAUSED) {
                GuardPauseUi(deadlineEpochMillis = 400_000L, remainingMinutes = 5L)
            } else {
                null
            }
            assertEquals(
                lifecycle.name,
                false,
                health(lifecycle, connectivity, pause = pause).toGuardianMascotState() ==
                    GuardianMascotState.AWAKE,
            )
        }
    }

    @Test
    fun `copy resources key on typed status and never on localized titles`() {
        assertEquals(
            R.string.guard_status_off_title,
            health(GuardLifecycleUi.INACTIVE, GuardConnectivityUi.UNAVAILABLE).titleResource,
        )
        assertEquals(
            R.string.guard_status_awaiting_network_title,
            health(GuardLifecycleUi.PROTECTED, GuardConnectivityUi.NETWORK_UNAVAILABLE)
                .titleResource,
        )
        assertEquals(
            R.string.guard_status_reconnecting_title,
            health(GuardLifecycleUi.PROTECTED, GuardConnectivityUi.RECOVERING).titleResource,
        )
        assertEquals(
            R.string.guard_status_dns_slow_title,
            health(GuardLifecycleUi.PROTECTED, GuardConnectivityUi.DNS_SLOW).titleResource,
        )
        assertEquals(
            R.string.guard_status_reconnect_needed_title,
            health(GuardLifecycleUi.PROTECTED, GuardConnectivityUi.NEEDS_RECONNECT).titleResource,
        )
        assertEquals(
            R.string.guard_settings_vpn_title,
            health(
                GuardLifecycleUi.ATTENTION,
                GuardConnectivityUi.UNAVAILABLE,
                action = GuardPrimaryAction.OpenSettings(SettingsRoute.VpnSettings),
            ).titleResource,
        )
        assertEquals(
            R.string.guard_settings_notifications_title,
            health(
                GuardLifecycleUi.ATTENTION,
                GuardConnectivityUi.UNAVAILABLE,
                action = GuardPrimaryAction.OpenSettings(SettingsRoute.NotificationSettings),
            ).titleResource,
        )
        assertEquals(
            R.string.guard_status_reconnect_needed_title,
            health(
                GuardLifecycleUi.ATTENTION,
                GuardConnectivityUi.UNAVAILABLE,
                action = GuardPrimaryAction.Reconnect,
            ).titleResource,
        )
    }

    @Test
    fun `messages render from their typed resource`() {
        assertEquals(
            R.string.guard_message_always_on,
            health(
                GuardLifecycleUi.PROTECTED,
                GuardConnectivityUi.UNAVAILABLE,
                message = GuardMessageUi.ALWAYS_ON_MANAGED,
            ).message?.labelResource,
        )
        assertEquals(
            R.string.guard_message_ownership_unverified,
            health(
                GuardLifecycleUi.PROTECTED,
                GuardConnectivityUi.UNAVAILABLE,
                message = GuardMessageUi.OWNERSHIP_UNVERIFIED_API_28,
            ).message?.labelResource,
        )
        assertEquals(
            R.string.guard_message_vpn_permission_revoked,
            health(
                GuardLifecycleUi.INACTIVE,
                GuardConnectivityUi.UNAVAILABLE,
                message = GuardMessageUi.VPN_PERMISSION_REVOKED,
            ).message?.labelResource,
        )
    }

    @Test
    fun `announcement tracker announces only distinct settled changes`() {
        val tracker = GuardStatusAnnouncementTracker()

        // First composition records silently (iOS onChange never fires initially).
        assertNull(tracker.distinctSettledChange("Protected. Local DNS filtering is active"))
        // Identical re-emissions (the Guard poll) never announce.
        assertNull(tracker.distinctSettledChange("Protected. Local DNS filtering is active"))
        // A distinct settled change announces once.
        assertEquals(
            "Paused. Protection resumes in 5 minutes",
            tracker.distinctSettledChange("Paused. Protection resumes in 5 minutes"),
        )
        assertNull(tracker.distinctSettledChange("Paused. Protection resumes in 5 minutes"))
        // Returning to a previous value is still a distinct transition.
        assertEquals(
            "Protected. Local DNS filtering is active",
            tracker.distinctSettledChange("Protected. Local DNS filtering is active"),
        )
    }

    private fun health(
        lifecycle: GuardLifecycleUi,
        connectivity: GuardConnectivityUi,
        action: GuardPrimaryAction? = null,
        pause: GuardPauseUi? = null,
        message: GuardMessageUi? = null,
    ) = GuardProtectionHealthUi(
        lifecycle = lifecycle,
        connectivity = connectivity,
        pause = pause,
        primaryAction = action,
        message = message,
    )
}
