package com.lavasec.app.reactnative

import com.lavasec.app.foundation.security.ProtectedSurface
import com.lavasec.app.presentation.ProductDeepLink
import com.lavasec.app.presentation.ProductNavigation
import org.junit.Assert.*
import org.junit.Test

class NativeDeepLinkEntryTest {
    @Test fun `only the four actually validated public destinations can be staged`() {
        val cases = mapOf("guard" to Triple("GuardTab", "Guard", null),
            "filters" to Triple("GuardTab", "Filters", ProtectedSurface.FILTERS),
            "activity" to Triple("GuardTab", "Activity", ProtectedSurface.ACTIVITY),
            "settings" to Triple("SettingsTab", "Settings", ProtectedSurface.APP_SETTINGS))
        cases.forEach { (route, expected) ->
            val parsed = checkNotNull(ProductDeepLink.parse("lavasecurity://$route"))
            val destination = checkNotNull(NativeDeepLinkEntryRegistry.destination(parsed))
            assertEquals(expected.first, destination.tab); assertEquals(expected.second, destination.screen)
            assertEquals(expected.third, destination.surface)
        }
        listOf("lavasecurity://activity/top-domains", "lavasecurity://settings?secret=value", "https://lavasecurity.app/app/settings/extra").forEach {
            assertNull(ProductDeepLink.parse(it))
        }
        assertNull(NativeDeepLinkEntryRegistry.destination(ProductNavigation.guard.open(com.lavasec.app.presentation.ProductDestination.Activity)
            .open(com.lavasec.app.presentation.ProductDestination.TopDomains)))
    }

    @Test fun `bootstrap binds once to the real initial graph and cannot adopt ordinary rotation`() {
        val entries = NativeDeepLinkEntryRegistry(); val host = Any()
        val id = entries.stage(ProductNavigation.settings, null)
        assertTrue(entries.attach(id, host, "A")); val claim = checkNotNull(entries.claim(id, host, "A", 7))
        assertTrue(entries.begin(claim)); assertFalse(entries.begin(claim))
        assertFalse(entries.publish(claim, 7, setupCompleted = false, authorized = true))
        assertNull(entries.navigation(id, host, "A", 7))
        assertNull(entries.claim(id, host, "A", 8))
        assertFalse(entries.publish(claim, 7, setupCompleted = true, authorized = true))
    }

    @Test fun `configured entry still requires actual native authorization before the closed redirect`() {
        val entries = NativeDeepLinkEntryRegistry(); val host = Any()
        val id = entries.stage(ProductNavigation.settings, 7)
        assertTrue(entries.attach(id, host, "A")); val claim = checkNotNull(entries.claim(id, host, "A", 7)); assertTrue(entries.begin(claim))
        assertFalse(entries.publish(claim, 7, setupCompleted = true, authorized = false))
        assertNull(entries.navigation(id, host, "A", 7))
        assertTrue(entries.publish(claim, 7, setupCompleted = true, authorized = true))
        assertEquals("SettingsTab", entries.navigation(id, host, "A", 7)?.tab)
        assertEquals("Settings", entries.navigation(id, host, "A", 7)?.screen)
    }

    @Test fun `another Activity or nonce cannot borrow the original child entry or its receipt`() {
        val entries = NativeDeepLinkEntryRegistry(); val host = Any()
        val id = entries.stage(ProductNavigation.guard, 7)
        assertTrue(entries.attach(id, host, "A")); val claim = checkNotNull(entries.claim(id, host, "A", 7)); assertTrue(entries.begin(claim))
        assertNull(entries.claim(id, Any(), "A", 7)); assertNull(entries.claim(id, host, "B", 7))
        assertTrue(entries.publish(claim, 7, setupCompleted = true, authorized = true))
        assertNull(entries.navigation(id, Any(), "A", 7)); assertNull(entries.navigation(id, host, "B", 7))
        assertNull(entries.navigation(id, host, "A", 8))
    }

    @Test fun `a fresh source intent retires the old target and every late old completion or cleanup`() {
        val entries = NativeDeepLinkEntryRegistry(); val a = Any(); val b = Any()
        val oldId = entries.stage(ProductNavigation.guard, 7)
        assertTrue(entries.attach(oldId, a, "A")); val old = checkNotNull(entries.claim(oldId, a, "A", 7)); assertTrue(entries.begin(old))
        val nextId = entries.stage(ProductNavigation.settings, 7)
        assertTrue(entries.attach(nextId, b, "B")); val next = checkNotNull(entries.claim(nextId, b, "B", 7)); assertTrue(entries.begin(next))
        assertFalse(entries.publish(old, 7, setupCompleted = true, authorized = true))
        entries.cancel(old); entries.release(oldId, a, "A", recreating = false)
        assertTrue(entries.publish(next, 7, setupCompleted = true, authorized = true))
        assertEquals("Settings", entries.navigation(nextId, b, "B", 7)?.screen)
    }

    @Test fun `genuine configuration replacement permits a new child but rejects the old callback`() {
        val entries = NativeDeepLinkEntryRegistry(); val a = Any(); val b = Any()
        val id = entries.stage(ProductNavigation.settings, 7)
        assertTrue(entries.attach(id, a, "A")); val old = checkNotNull(entries.claim(id, a, "A", 7)); assertTrue(entries.begin(old))
        entries.release(id, a, "A", recreating = true)
        assertTrue(entries.attach(id, b, "B")); val next = checkNotNull(entries.claim(id, b, "B", 7)); assertTrue(entries.begin(next))
        assertFalse(entries.publish(old, 7, setupCompleted = true, authorized = true))
        entries.cancel(old); entries.release(id, a, "A", recreating = false)
        assertTrue(entries.publish(next, 7, setupCompleted = true, authorized = true))
    }

    @Test fun `permanent cancellation discards the target and only a fresh validated entry reopens it`() {
        val entries = NativeDeepLinkEntryRegistry(); val host = Any()
        val id = entries.stage(ProductNavigation.settings, 7)
        assertTrue(entries.attach(id, host, "A")); val old = checkNotNull(entries.claim(id, host, "A", 7)); assertTrue(entries.begin(old))
        entries.release(id, host, "A", recreating = false)
        assertNull(entries.claim(id, Any(), "B", 7))
        assertFalse(entries.publish(old, 7, setupCompleted = true, authorized = true))
        val nextID = entries.stage(checkNotNull(ProductDeepLink.parse("lavasecurity://settings")), 7)
        assertTrue(entries.attach(nextID, host, "A")); val next = checkNotNull(entries.claim(nextID, host, "A", 7)); assertTrue(entries.begin(next))
        assertTrue(entries.publish(next, 7, setupCompleted = true, authorized = true))
        assertTrue(checkNotNull(entries.navigation(nextID, host, "A", 7)).serial > 1)
    }

    @Test fun `destroying the attached child before graph or unlock admission retires its ticket`() {
        val entries = NativeDeepLinkEntryRegistry(); val a = Any(); val b = Any()
        val id = entries.stage(ProductNavigation.settings, null)
        assertTrue(entries.attach(id, a, "A"))
        assertFalse(entries.attach(id, b, "B"))
        entries.release(id, a, "A", recreating = false)
        assertFalse(entries.attach(id, b, "B"))
        assertNull(entries.claim(id, b, "B", 7))
        assertNull(entries.acceptance(id, a, "A", 7))
        val successor = entries.stage(ProductNavigation.guard, null)
        assertTrue(entries.attach(successor, b, "B"))
        entries.release(id, a, "A", recreating = false)
        assertNotNull(entries.claim(successor, b, "B", 7))
    }

    @Test fun `wrong host with wrong generation cannot retire the rightful successor`() {
        val entries = NativeDeepLinkEntryRegistry(); val a = Any(); val b = Any()
        val id = entries.stage(ProductNavigation.settings, 7)
        assertTrue(entries.attach(id, b, "B"))
        assertNull(entries.claim(id, a, "A", 8))
        val next = checkNotNull(entries.claim(id, b, "B", 7)); assertTrue(entries.begin(next))
        assertTrue(entries.publish(next, 7, setupCompleted = true, authorized = true))
        assertEquals("Settings", entries.navigation(id, b, "B", 7)?.screen)
    }

    @Test fun `only the exact accepted navigation consumes the ticket and recreation cannot replay it`() {
        val entries = NativeDeepLinkEntryRegistry(); val a = Any()
        val id = entries.stage(ProductNavigation.settings, 7)
        assertTrue(entries.attach(id, a, "A"))
        val claim = checkNotNull(entries.claim(id, a, "A", 7)); assertTrue(entries.begin(claim))
        assertFalse(entries.accept(id, a, "A", 7, 1, 12))
        assertTrue(entries.publish(claim, 7, setupCompleted = true, authorized = true))
        val serial = checkNotNull(entries.navigation(id, a, "A", 7)).serial
        assertFalse(entries.accept(id, Any(), "A", 7, serial, 12))
        assertFalse(entries.accept(id, a, "A", 8, serial, 12))
        assertFalse(entries.accept(id, a, "A", 7, serial + 1, 12))
        assertNotNull(entries.navigation(id, a, "A", 7))
        assertNull(entries.acceptance(id, a, "A", 7))
        assertTrue(entries.accept(id, a, "A", 7, serial, 12))
        assertEquals(serial, entries.acceptance(id, a, "A", 7)?.navigation?.serial)
        assertEquals(12L, entries.acceptance(id, a, "A", 7)?.presentationSerial)
        assertNull(entries.acceptance(id, Any(), "A", 7))
        assertNull(entries.acceptance(id, a, "A", 8))
        entries.release(id, a, "A", recreating = true)
        assertFalse(entries.attach(id, Any(), "B"))
        assertNull(entries.navigation(id, a, "A", 7))
        assertFalse(entries.publish(claim, 7, setupCompleted = true, authorized = true))
    }

    @Test fun `a source already bound to a graph cannot be claimed after credential replacement`() {
        val entries = NativeDeepLinkEntryRegistry(); val host = Any()
        val id = entries.stage(ProductNavigation.settings, 7)
        assertTrue(entries.attach(id, host, "A"))
        assertNull(entries.claim(id, host, "A", 8)); assertNull(entries.claim(id, host, "A", 7))
    }

    @Test fun `successful setup transfers only its exact owner before queued source observations`() {
        val entries = NativeDeepLinkEntryRegistry(); val host = Any(); val owner = Any()
        val id = entries.stage(ProductNavigation.settings, null)
        assertTrue(entries.attach(id, host, "A"))
        val source = checkNotNull(entries.claim(id, host, "A", 7)); assertTrue(entries.begin(source))
        val handoff = checkNotNull(entries.prepareSetupHandoff(source, "setup-A", owner))
        val successor = checkNotNull(entries.transferSetupHandoff(handoff, "setup-A", owner, 7, 42))
        assertEquals(42L, successor.generation)
        // Reentrant/queued source VM frames have no authority and cannot retire the receipt.
        repeat(3) { assertNull(entries.claim(id, host, "A", 7)) }
        assertNull(entries.navigation(id, host, "A", 7))
        assertFalse(entries.publish(source, 7, setupCompleted = true, authorized = true))
        entries.cancel(source)
        val observed = checkNotNull(entries.claim(id, host, "A", 42))
        assertFalse(entries.begin(observed)) // The source dispatched setup exactly once.
        assertFalse(entries.publish(successor, 42, setupCompleted = true, authorized = false))
        assertFalse(entries.accept(id, host, "A", 42, 1, 12))
        assertTrue(entries.publish(successor, 42, setupCompleted = true, authorized = true))
        val serial = checkNotNull(entries.navigation(id, host, "A", 42)).serial
        assertFalse(entries.accept(id, host, "A", 7, serial, 12))
        assertTrue(entries.accept(id, host, "A", 42, serial, 12))
        assertEquals("Settings", entries.acceptance(id, host, "A", 42)?.navigation?.screen)
        assertNull(entries.acceptance(id, host, "A", 7))
        assertFalse(entries.accept(id, host, "A", 42, serial, 12))
    }

    @Test fun `setup transfer refuses wrong visit owner source and repeated receipt`() {
        val entries = NativeDeepLinkEntryRegistry(); val host = Any(); val owner = Any()
        val id = entries.stage(ProductNavigation.settings, 7)
        assertTrue(entries.attach(id, host, "A"))
        val source = checkNotNull(entries.claim(id, host, "A", 7)); assertTrue(entries.begin(source))
        val handoff = checkNotNull(entries.prepareSetupHandoff(source, "setup-A", owner))
        assertNull(entries.transferSetupHandoff(handoff, "setup-B", owner, 7, 42))
        assertNull(entries.transferSetupHandoff(handoff, "setup-A", Any(), 7, 42))
        assertNull(entries.transferSetupHandoff(handoff, "setup-A", owner, 8, 42))
        assertNull(entries.transferSetupHandoff(handoff, "setup-A", owner, 7, 7))
        assertNull(entries.transferSetupHandoff(handoff, "setup-A", owner, 7, 0))
        assertTrue(entries.isCurrent(source, 7))
        assertNotNull(entries.transferSetupHandoff(handoff, "setup-A", owner, 7, 42))
        assertNull(entries.transferSetupHandoff(handoff, "setup-A", owner, 7, 43))
        assertNull(entries.prepareSetupHandoff(checkNotNull(entries.claim(id, host, "A", 42)), "setup-B", owner))
    }

    @Test fun `unwitnessed credential rotation still retires a setup successor`() {
        val entries = NativeDeepLinkEntryRegistry(); val host = Any(); val owner = Any()
        val id = entries.stage(ProductNavigation.guard, 7)
        assertTrue(entries.attach(id, host, "A"))
        val source = checkNotNull(entries.claim(id, host, "A", 7)); assertTrue(entries.begin(source))
        val handoff = checkNotNull(entries.prepareSetupHandoff(source, "setup-A", owner))
        val successor = checkNotNull(entries.transferSetupHandoff(handoff, "setup-A", owner, 7, 42))
        assertNull(entries.claim(id, host, "A", 43))
        assertNull(entries.claim(id, host, "A", 42))
        assertFalse(entries.publish(successor, 42, setupCompleted = true, authorized = true))
        assertNull(entries.acceptance(id, host, "A", 42))
    }

    @Test fun `canceled destroyed or recreated child cannot transfer its late setup to successor`() {
        for (retirement in listOf("cancel", "destroy", "recreate", "fresh-intent")) {
            val entries = NativeDeepLinkEntryRegistry(); val a = Any(); val b = Any(); val owner = Any()
            val id = entries.stage(ProductNavigation.settings, 7)
            assertTrue(entries.attach(id, a, "A"))
            val source = checkNotNull(entries.claim(id, a, "A", 7)); assertTrue(entries.begin(source))
            val handoff = checkNotNull(entries.prepareSetupHandoff(source, "setup-A", owner))
            val successorID = when (retirement) {
                "cancel" -> { entries.cancel(source); entries.stage(ProductNavigation.guard, 7) }
                "destroy" -> { entries.release(id, a, "A", recreating = false); entries.stage(ProductNavigation.guard, 7) }
                "recreate" -> { entries.release(id, a, "A", recreating = true); id }
                else -> entries.stage(ProductNavigation.guard, 7)
            }
            assertTrue(entries.attach(successorID, b, "B"))
            val successor = checkNotNull(entries.claim(successorID, b, "B", 7)); assertTrue(entries.begin(successor))
            assertNull(retirement, entries.transferSetupHandoff(handoff, "setup-A", owner, 7, 42))
            entries.cancel(source); entries.release(id, a, "A", recreating = false)
            assertTrue(retirement, entries.publish(successor, 7, setupCompleted = true, authorized = true))
            assertNotNull(entries.navigation(successorID, b, "B", 7))
        }
    }

}
