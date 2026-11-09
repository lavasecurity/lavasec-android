package com.lavasec.app.notification

import com.lavasec.app.vpn.LavaVpnService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationActionRoutingTest {
    @Test
    fun `protection mutations route to the exact vpn service commands, never a local implementation`() {
        assertEquals(
            NotificationActionRoute.BackendCommand(LavaVpnService.ACTION_RECONNECT),
            NotificationActionRouting.routeFor(NotificationAction.RECONNECT),
        )
        assertEquals(
            NotificationActionRoute.BackendCommand(LavaVpnService.ACTION_PAUSE),
            NotificationActionRouting.routeFor(NotificationAction.PAUSE),
        )
        assertEquals(
            NotificationActionRoute.BackendCommand(LavaVpnService.ACTION_RESUME),
            NotificationActionRouting.routeFor(NotificationAction.RESUME),
        )
        assertEquals(
            NotificationActionRoute.BackendCommand(LavaVpnService.ACTION_STOP),
            NotificationActionRouting.routeFor(NotificationAction.STOP),
        )
    }

    @Test
    fun `navigation actions resolve to validated product deep-link routes`() {
        assertEquals(
            NotificationActionRoute.AppRoute("guard"),
            NotificationActionRouting.routeFor(NotificationAction.OPEN_APP),
        )
        assertEquals(
            NotificationActionRoute.AppRoute("guard"),
            NotificationActionRouting.routeFor(NotificationAction.OPEN_GUARD),
        )
        assertEquals(
            NotificationActionRoute.AppRoute("filters"),
            NotificationActionRouting.routeFor(NotificationAction.OPEN_FILTERS),
        )
        assertEquals(
            NotificationActionRoute.AppRoute("activity"),
            NotificationActionRouting.routeFor(NotificationAction.OPEN_ACTIVITY),
        )
        assertEquals(
            NotificationActionRoute.AppRoute("settings"),
            NotificationActionRouting.routeFor(NotificationAction.OPEN_SETTINGS),
        )
    }

    @Test
    fun `system settings actions resolve to out-of-app destinations`() {
        assertEquals(
            NotificationActionRoute.SystemSettings(SystemSettingsDestination.APP_NOTIFICATION),
            NotificationActionRouting.routeFor(NotificationAction.NOTIFICATION_SETTINGS),
        )
        assertEquals(
            NotificationActionRoute.SystemSettings(SystemSettingsDestination.VPN),
            NotificationActionRouting.routeFor(NotificationAction.VPN_SETTINGS),
        )
    }

    @Test
    fun `every action resolves and every service action stays inside the admissible set`() {
        for (action in NotificationAction.entries) {
            val route = NotificationActionRouting.routeFor(action)
            assertTrue("$action has no route", route != null)
            if (route is NotificationActionRoute.BackendCommand) {
                assertTrue(
                    "$action escaped the service-command allowlist",
                    route.serviceAction in NotificationActionRouting.admissibleServiceActions,
                )
            }
        }
    }

    @Test
    fun `unknown wire ids are refused instead of guessed`() {
        assertNull(NotificationActionRouting.routeForWireId("phoneQA"))
        assertNull(NotificationActionRouting.routeForWireId("guard"))
        assertNull(NotificationActionRouting.routeForWireId(""))
        assertNull(NotificationActionRouting.routeForWireId("OPEN_GUARD"))
    }

    @Test
    fun `wire ids round-trip to the same route`() {
        for (action in NotificationAction.entries) {
            assertEquals(
                NotificationActionRouting.routeFor(action),
                NotificationActionRouting.routeForWireId(action.wireId),
            )
        }
    }
}
