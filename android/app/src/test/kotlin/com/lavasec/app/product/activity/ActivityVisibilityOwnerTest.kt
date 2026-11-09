package com.lavasec.app.product.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Plain JUnit coverage for the review-moment dwell owner (no Robolectric, so it runs on the
 * aarch64 work host). Pins that the canonical `activity.visibility` signal has a real owner effect
 * and is not silently dropped.
 */
class ActivityVisibilityOwnerTest {
    @Test fun `records a completed dwell only after the minimum visible duration`() {
        var now = 0L
        val owner = ActivityVisibilityOwner(nowMillis = { now })
        owner.setVisible("t1", visible = true, startSeconds = 10L, endSeconds = 20L)
        assertEquals("t1", owner.snapshot().visibleToken)
        assertEquals(10L, owner.snapshot().visibleStartSeconds)
        assertEquals(20L, owner.snapshot().visibleEndSeconds)

        now = 1_000L
        owner.setVisible("t1", visible = false)
        assertEquals(0L, owner.snapshot().completedDwells)
        assertNull(owner.snapshot().visibleToken)

        owner.setVisible("t2", visible = true)
        now = 4_000L
        owner.setVisible("t2", visible = false)
        assertEquals(1L, owner.snapshot().completedDwells)
    }

    @Test fun `a stale hide never clears a newer visible token`() {
        var now = 0L
        val owner = ActivityVisibilityOwner(nowMillis = { now })
        owner.setVisible("old", visible = true)
        owner.setVisible("new", visible = true)
        now = 5_000L
        owner.setVisible("old", visible = false)
        assertEquals("new", owner.snapshot().visibleToken)
        assertEquals(0L, owner.snapshot().completedDwells)
        owner.setVisible("new", visible = false)
        assertEquals(1L, owner.snapshot().completedDwells)
    }

    @Test fun `a hide with no visible moment is a no-op`() {
        val owner = ActivityVisibilityOwner(nowMillis = { 0L })
        owner.setVisible("t", visible = false)
        assertNull(owner.snapshot().visibleToken)
        assertEquals(0L, owner.snapshot().completedDwells)
    }
}
