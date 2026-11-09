package com.lavasec.app.foundation.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the exact canonical notification-category catalog against the pinned iOS
 * source (c4658d973bae05dd3b7aa84c014cb2f76ff61a0b). The expectation is hardcoded,
 * so a new/renamed/removed canonical category fails this test until the model is
 * explicitly updated.
 */
class NotificationCategoryCatalogTest {
    private data class Canonical(
        val preferenceKey: String,
        val stableKey: String,
        val defaultsKey: String,
        val bodyKey: String?,
        val semanticsNeedle: String,
    )

    private val canonical: List<Canonical> = listOf(
        // ReactNative/native-app/LavaAppSettings.swift, notifications.* branch.
        Canonical(
            preferenceKey = "notifications.Filter changes",
            stableKey = "filter-changed",
            defaultsKey = "lavasec.notifications.enabled.filter-changed",
            bodyKey = "notif.body.switchedTo",
            semanticsNeedle = "Switched to <Filter>",
        ),
        Canonical(
            preferenceKey = "notifications.Filter couldn't switch",
            stableKey = "filter-could-not-apply",
            defaultsKey = "lavasec.notifications.enabled.filter-could-not-apply",
            bodyKey = "notif.body.couldNotSwitchTo",
            semanticsNeedle = "Couldn't switch to <Filter>",
        ),
        Canonical(
            preferenceKey = "notifications.Protection resumed",
            stableKey = "protection-resumed",
            defaultsKey = "lavasec.notifications.enabled.protection-resumed",
            bodyKey = "notif.body.pauseEnded",
            semanticsNeedle = "Pause ended - protection is back on",
        ),
        Canonical(
            preferenceKey = "notifications.Connection updates",
            stableKey = "connectivity",
            defaultsKey = "lavasec.notifications.enabled.connectivity",
            bodyKey = null,
            semanticsNeedle = "connectivity",
        ),
    )

    @Test
    fun `the model category set matches the canonical catalog exactly`() {
        assertEquals(
            canonical.map { it.preferenceKey },
            NotificationCategory.entries.map { it.preferenceKey },
        )
    }

    @Test
    fun `every canonical category declares the canon identity, default and semantics`() {
        for (entry in canonical) {
            val category = NotificationCategory.fromPreferenceKey(entry.preferenceKey)
            assertTrue("missing model category for ${entry.preferenceKey}", category != null)
            requireNotNull(category)

            assertEquals("stable key for ${entry.preferenceKey}", entry.stableKey, category.stableKey)
            assertEquals("defaults key for ${entry.preferenceKey}", entry.defaultsKey, category.defaultsKey)
            assertEquals("body key for ${entry.preferenceKey}", entry.bodyKey, category.bodyKey)
            assertTrue("default must be ON for ${entry.preferenceKey}", category.defaultEnabled)
            assertTrue(
                "semantics for ${entry.preferenceKey} must mention ${entry.semanticsNeedle}",
                category.semantics.contains(entry.semanticsNeedle),
            )
            assertTrue(
                "semantics for ${entry.preferenceKey} must not be blank",
                category.semantics.isNotBlank(),
            )
        }
    }

    @Test
    fun `a future canonical category fails closed until the model is changed`() {
        assertNull(NotificationCategory.fromPreferenceKey("notifications.Digest"))
        val rejected = NotificationPreferences.canonicalDefaults()
            .set("notifications.Digest", true)
        assertTrue(rejected.isFailure)
        assertTrue(rejected.exceptionOrNull() is UnknownNotificationCategoryException)
        assertNull(NotificationCategory.fromStableKey("digest"))
    }
}
