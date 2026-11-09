package com.lavasec.app.foundation.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the canonical per-category notification preference model:
 * canon-matching defaults, independent toggles, and strict rejection of
 * non-canonical `settings.set` keys.
 */
class NotificationPreferencesTest {
    private fun defaults(): NotificationPreferences = NotificationPreferences.canonicalDefaults()

    @Test
    fun `canonical defaults enable every category`() {
        val model = defaults()
        assertEquals(NotificationCategory.entries.toSet(), model.toMap().keys)
        for (category in NotificationCategory.entries) {
            assertTrue("${category.preferenceKey} must default ON", model.isEnabled(category))
        }
    }

    @Test
    fun `toggling one category leaves every other category unchanged`() {
        val model = defaults()

        val updated = model.set(NotificationCategory.CONNECTION_UPDATES, false)
        assertFalse(updated.isEnabled(NotificationCategory.CONNECTION_UPDATES))
        for (category in NotificationCategory.entries) {
            if (category != NotificationCategory.CONNECTION_UPDATES) {
                assertTrue(
                    "${category.preferenceKey} must stay ON",
                    updated.isEnabled(category),
                )
            }
        }

        // The original value model is immutable.
        assertTrue(model.isEnabled(NotificationCategory.CONNECTION_UPDATES))
        assertFalse(model == updated)
    }

    @Test
    fun `each category can be toggled off without touching the others`() {
        var model = defaults()
        for (category in NotificationCategory.entries) {
            model = model.set(category, false)
            assertFalse(model.isEnabled(category))
            for (other in NotificationCategory.entries) {
                if (other != category) {
                    assertTrue(
                        "turning off ${category.preferenceKey} must not change ${other.preferenceKey}",
                        model.isEnabled(other),
                    )
                }
            }
            model = model.set(category, true)
        }
        assertEquals(defaults(), model)
    }

    @Test
    fun `unknown wire key is rejected and leaves the receiver unchanged`() {
        val model = defaults()

        val unknown = model.set("notifications.Bogus category", false)
        assertTrue(unknown.isFailure)
        assertTrue(unknown.exceptionOrNull() is UnknownNotificationCategoryException)

        // A key without the namespace prefix is also non-canonical.
        val missingPrefix = model.set("Filter changes", false)
        assertTrue(missingPrefix.isFailure)
        assertTrue(missingPrefix.exceptionOrNull() is UnknownNotificationCategoryException)

        assertEquals(defaults(), model)
    }

    @Test
    fun `wire key writes round-trip for every canonical category`() {
        for (category in NotificationCategory.entries) {
            val updated = defaults()
                .set(category.preferenceKey, false)
                .getOrThrow()
            assertFalse(updated.isEnabled(category))
        }
    }

    @Test
    fun `resolve and require agree on canonical and unknown keys`() {
        for (category in NotificationCategory.entries) {
            assertSame(category, NotificationCategory.fromPreferenceKey(category.preferenceKey))
            assertSame(category, NotificationCategory.requirePreferenceKey(category.preferenceKey))
            assertSame(category, NotificationCategory.fromStableKey(category.stableKey))
        }

        val thrown = runCatching {
            NotificationCategory.requirePreferenceKey("notifications.Unknown")
        }.exceptionOrNull()
        assertTrue(thrown is UnknownNotificationCategoryException)
    }

    @Test
    fun `equal value models are equal and distinct writes are not`() {
        val base = defaults()
        assertEquals(base, NotificationPreferences.canonicalDefaults())
        assertEquals(base.hashCode(), NotificationPreferences.canonicalDefaults().hashCode())
        assertFalse(
            base == base.set(NotificationCategory.PROTECTION_RESUMED, false),
        )
    }
}
