package com.lavasec.app.settings

import com.lavasec.app.foundation.notifications.NotificationCategory
import com.lavasec.app.foundation.notifications.NotificationPreferences
import com.lavasec.app.foundation.preferences.PreferenceValue
import com.lavasec.app.foundation.preferences.ProductPreferenceKey
import com.lavasec.app.foundation.preferences.ProductPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Durable-write guarantees for the retained preference owner (pure JVM, no Android). */
class ProductSettingsOwnerTest {
    @Test fun `locked look is refused before persistence and admission rechecks retained courtesy`() = runTest {
        var writes = 0
        var earned = false
        val owner = ProductSettingsOwner(ProductPreferences.canonicalDefaults(),
            NotificationPreferences.canonicalDefaults(), { writes++; true }, { true },
            admitsLook = { requested, current -> requested == "original" || requested == current || earned })
        assertEquals(PreferenceWrite.INVALID, owner.setPreference(ProductPreferenceKey.LOOK, PreferenceValue.Text("emerald")))
        assertEquals(0, writes)
        earned = true
        assertEquals(PreferenceWrite.APPLIED, owner.setPreference(ProductPreferenceKey.LOOK, PreferenceValue.Text("emerald")))
        earned = false
        assertEquals(PreferenceWrite.APPLIED, owner.setPreference(ProductPreferenceKey.LOOK, PreferenceValue.Text("emerald")))
        assertEquals(PreferenceWrite.INVALID, owner.setPreference(ProductPreferenceKey.LOOK, PreferenceValue.Text("aquamarine")))
    }
    @Test
    fun `an accepted write persists through the port and publishes the new model`() = runTest {
        var persisted: ProductPreferences? = null
        val owner = ProductSettingsOwner(
            initialPreferences = ProductPreferences.canonicalDefaults(),
            initialNotifications = NotificationPreferences.canonicalDefaults(),
            persistPreferences = { persisted = it; true },
            persistNotifications = { true },
        )

        assertEquals(
            PreferenceWrite.APPLIED,
            owner.setPreference(ProductPreferenceKey.APPEARANCE, PreferenceValue.Text("dark")),
        )
        assertEquals("dark", persisted?.appearance?.wire)
        assertEquals("dark", owner.state.value.preferences.appearance.wire)
    }

    @Test
    fun `an invalid value never reaches the store and leaves the previous model authoritative`() = runTest {
        var persistCalls = 0
        val owner = ProductSettingsOwner(
            initialPreferences = ProductPreferences.canonicalDefaults(),
            initialNotifications = NotificationPreferences.canonicalDefaults(),
            persistPreferences = { persistCalls++; true },
            persistNotifications = { true },
        )

        assertEquals(
            PreferenceWrite.INVALID,
            owner.setPreference(ProductPreferenceKey.APPEARANCE, PreferenceValue.Text("rainbow")),
        )
        assertEquals(0, persistCalls)
        assertEquals("system", owner.state.value.preferences.appearance.wire)
    }

    @Test
    fun `a failed or throwing durable write is reported and never published`() = runTest {
        var fail = true
        val owner = ProductSettingsOwner(
            initialPreferences = ProductPreferences.canonicalDefaults(),
            initialNotifications = NotificationPreferences.canonicalDefaults(),
            persistPreferences = { if (fail) false else true },
            persistNotifications = { if (fail) throw IllegalStateException("disk") else true },
        )

        assertEquals(
            PreferenceWrite.FAILED,
            owner.setPreference(ProductPreferenceKey.LOOK, PreferenceValue.Text("emerald")),
        )
        assertEquals("original", owner.state.value.preferences.look.wire)

        assertEquals(
            NotificationWrite.FAILED,
            owner.setNotification(NotificationCategory.FILTER_CHANGED, false),
        )
        assertEquals(true, owner.state.value.notifications.isEnabled(NotificationCategory.FILTER_CHANGED))

        fail = false
        assertEquals(
            PreferenceWrite.APPLIED,
            owner.setPreference(ProductPreferenceKey.LOOK, PreferenceValue.Text("emerald")),
        )
        assertEquals("emerald", owner.state.value.preferences.look.wire)
    }

    @Test
    fun `a closed owner refuses every mutation`() = runTest {
        val owner = ProductSettingsOwner(
            initialPreferences = ProductPreferences.canonicalDefaults(),
            initialNotifications = NotificationPreferences.canonicalDefaults(),
            persistPreferences = { true },
            persistNotifications = { true },
        )
        owner.close()

        assertEquals(
            PreferenceWrite.CLOSED,
            owner.setPreference(ProductPreferenceKey.APPEARANCE, PreferenceValue.Text("dark")),
        )
        assertEquals(
            NotificationWrite.CLOSED,
            owner.setNotification(NotificationCategory.FILTER_CHANGED, false),
        )
        assertFalse(owner.state.value.preferences.appearance.wire == "dark")
        assertTrue(owner.state.value.notifications.isEnabled(NotificationCategory.FILTER_CHANGED))
    }
}
