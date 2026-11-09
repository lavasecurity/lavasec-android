package com.lavasec.app.foundation.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the notification capability/eligibility model: the four
 * suppression states stay distinct, and a suppressed category is never reported
 * deliverable.
 */
class NotificationEligibilityTest {
    private val category = NotificationCategory.FILTER_CHANGED

    private fun preferences(
        enabled: Boolean = true,
        target: NotificationCategory = category,
    ): NotificationPreferences =
        NotificationPreferences.canonicalDefaults().set(target, enabled)

    private fun capability(
        permission: Boolean = true,
        channel: NotificationChannelState = NotificationChannelState.ENABLED,
    ): NotificationOsCapability = NotificationOsCapability(
        notificationsPermissionGranted = permission,
        channelState = channel,
    )

    private fun evaluate(
        preferences: NotificationPreferences = preferences(),
        target: NotificationCategory = category,
        capability: NotificationOsCapability = capability(),
    ) = NotificationEligibilityPolicy.evaluate(preferences, target, capability)

    @Test
    fun `every gate open is deliverable`() {
        val result = evaluate()
        assertEquals(NotificationEligibility.Deliverable, result)
        assertTrue(result.isDeliverable)
        assertNull(result.suppressionReason)
    }

    @Test
    fun `permission denied and preference off are distinct suppressions`() {
        val preferenceOff = evaluate(preferences = preferences(enabled = false))
        val permissionDenied = evaluate(capability = capability(permission = false))

        assertEquals(
            NotificationEligibility.Suppressed(NotificationSuppression.PREFERENCE_OFF),
            preferenceOff,
        )
        assertEquals(
            NotificationEligibility.Suppressed(NotificationSuppression.OS_PERMISSION_DENIED),
            permissionDenied,
        )
        assertFalse(preferenceOff == permissionDenied)
        assertFalse(preferenceOff.suppressionReason == permissionDenied.suppressionReason)
    }

    @Test
    fun `channel disabled and channel unavailable are distinct suppressions`() {
        val disabled = evaluate(capability = capability(channel = NotificationChannelState.DISABLED))
        val unavailable = evaluate(
            capability = capability(channel = NotificationChannelState.UNAVAILABLE),
        )

        assertEquals(
            NotificationEligibility.Suppressed(NotificationSuppression.CHANNEL_DISABLED),
            disabled,
        )
        assertEquals(
            NotificationEligibility.Suppressed(NotificationSuppression.CHANNEL_UNAVAILABLE),
            unavailable,
        )
        assertFalse(disabled == unavailable)
    }

    @Test
    fun `all four suppressions are reachable and mutually distinct`() {
        val reasons = setOf(
            evaluate(preferences = preferences(enabled = false)).suppressionReason,
            evaluate(capability = capability(permission = false)).suppressionReason,
            evaluate(capability = capability(channel = NotificationChannelState.DISABLED))
                .suppressionReason,
            evaluate(capability = capability(channel = NotificationChannelState.UNAVAILABLE))
                .suppressionReason,
        )
        assertEquals(4, reasons.size)
        assertEquals(
            setOf(
                NotificationSuppression.PREFERENCE_OFF,
                NotificationSuppression.OS_PERMISSION_DENIED,
                NotificationSuppression.CHANNEL_DISABLED,
                NotificationSuppression.CHANNEL_UNAVAILABLE,
            ),
            reasons,
        )
    }

    @Test
    fun `a suppressed category is never deliverable`() {
        val suppressed = listOf(
            evaluate(preferences = preferences(enabled = false)),
            evaluate(capability = capability(permission = false)),
            evaluate(capability = capability(channel = NotificationChannelState.DISABLED)),
            evaluate(capability = capability(channel = NotificationChannelState.UNAVAILABLE)),
        )
        for (result in suppressed) {
            assertFalse(result.isDeliverable)
            assertTrue(result is NotificationEligibility.Suppressed)
            assertTrue(result.suppressionReason != null)
        }
    }

    @Test
    fun `preference off outranks every OS suppression`() {
        for (permission in listOf(true, false)) {
            for (channel in NotificationChannelState.entries) {
                val result = evaluate(
                    preferences = preferences(enabled = false),
                    capability = capability(permission = permission, channel = channel),
                )
                assertEquals(
                    NotificationEligibility.Suppressed(NotificationSuppression.PREFERENCE_OFF),
                    result,
                )
            }
        }
    }

    @Test
    fun `denied permission outranks every channel state`() {
        for (channel in NotificationChannelState.entries) {
            val result = evaluate(capability = capability(permission = false, channel = channel))
            assertEquals(
                NotificationEligibility.Suppressed(NotificationSuppression.OS_PERMISSION_DENIED),
                result,
            )
        }
    }

    @Test
    fun `the policy is category independent`() {
        for (target in NotificationCategory.entries) {
            assertTrue(evaluate(target = target).isDeliverable)
            assertEquals(
                NotificationEligibility.Suppressed(NotificationSuppression.PREFERENCE_OFF),
                evaluate(preferences = preferences(enabled = false, target = target), target = target),
            )
        }
    }
}
