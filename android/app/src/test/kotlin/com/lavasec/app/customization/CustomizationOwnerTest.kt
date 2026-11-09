package com.lavasec.app.customization

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CustomizationOwnerTest {
    @Test
    fun `preferences persist through the owner ports and never through compose`() = runTest {
        var persisted = CustomizationPreferences()
        val owner = CustomizationOwner(
            initial = CustomizationPreferences(reduceMotion = null, haptics = true),
            persist = { persisted = it },
            notificationChannelAllowed = { true },
            scope = backgroundScope,
        )

        assertEquals(false, owner.state.value.reduceMotion)
        assertTrue(owner.setReduceMotion(true))
        assertEquals(CustomizationPreferences(reduceMotion = true, haptics = true), persisted)
        assertEquals(true, owner.state.value.reduceMotion)

        // Turning reduce motion off returns to "follow the system" (null).
        assertTrue(owner.setReduceMotion(false))
        assertNull(persisted.reduceMotion)
        assertEquals(false, owner.state.value.reduceMotion)

        assertTrue(owner.setHaptics(false))
        assertEquals(false, persisted.haptics)
        assertEquals(false, owner.state.value.haptics)
    }

    @Test
    fun `the notification status is the read OS truth`() = runTest {
        val owner = CustomizationOwner(
            initial = CustomizationPreferences(),
            persist = {},
            notificationChannelAllowed = { false },
            scope = backgroundScope,
        )

        assertTrue(owner.refreshNotifications())
        assertEquals(false, owner.state.value.notificationsAllowed)
        assertEquals(true, owner.state.value.notificationsBlocked)
    }

    @Test
    fun `a closed owner refuses every mutation`() = runTest {
        val owner = CustomizationOwner(
            initial = CustomizationPreferences(),
            persist = {},
            notificationChannelAllowed = { null },
            scope = backgroundScope,
        )
        owner.close()

        assertFalse(owner.setReduceMotion(true))
        assertFalse(owner.setHaptics(false))
        assertFalse(owner.refreshNotifications())
    }
}
