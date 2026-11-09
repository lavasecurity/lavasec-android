package com.lavasec.app.reactnative

import android.app.Activity
import android.app.Application
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NativeWindowViewportTest {
    private fun vertical(left: Int, right: Int, separating: Boolean = true, occluding: Boolean = false) =
        NativeWindowFold(Rect(left, 0, right, 1000), true, separating, occluding)
    @Test fun `book posture includes zero-width crease and stable equal-pane choice`() {
        assertEquals(Rect(0, 0, 500, 800), usableWindowPane(Rect(0, 0, 1000, 800), listOf(vertical(500, 500))))
        assertEquals(Rect(520, 0, 1200, 800), usableWindowPane(Rect(0, 0, 1200, 800), listOf(vertical(500, 520))))
    }
    @Test fun `all vertical partitions compete regardless of feature order`() {
        val window = Rect(0, 0, 1200, 800)
        val folds = listOf(vertical(700, 700), vertical(400, 400))
        assertEquals(Rect(700, 0, 1200, 800), usableWindowPane(window, folds))
        assertEquals(Rect(700, 0, 1200, 800), usableWindowPane(window, folds.reversed()))
        val occluding = listOf(vertical(690, 710, false, true), vertical(390, 410, false, true))
        assertEquals(Rect(710, 0, 1200, 800), usableWindowPane(window, occluding))
        assertEquals(Rect(710, 0, 1200, 800), usableWindowPane(window, occluding.reversed()))
    }
    @Test fun `mixed feature order retains the largest usable upper partition`() {
        val window = Rect(0, 0, 1200, 800)
        val folds = listOf(vertical(700, 700), vertical(400, 400),
            NativeWindowFold(Rect(0, 390, 1200, 410), false, true, true))
        val expected = Rect(700, 0, 1200, 390)
        assertEquals(expected, usableWindowPane(window, folds))
        assertEquals(expected, usableWindowPane(window, folds.reversed()))
        assertEquals(expected, usableWindowPane(window, listOf(folds[1], folds[2], folds[0])))
    }
    @Test fun `flat nonseparating fold does not crop but an occluding hinge does`() {
        val window = Rect(0, 0, 1000, 800)
        assertEquals(window, usableWindowPane(window, listOf(vertical(480, 520, separating = false))))
        assertEquals(Rect(0, 0, 480, 800), usableWindowPane(window, listOf(vertical(480, 520, false, true))))
    }
    @Test fun `tabletop and window offsets use actual content coordinates`() {
        val window = Rect(100, 24, 1100, 824)
        val fold = NativeWindowFold(Rect(0, 400, 1200, 420), false, true, false)
        // The lower pane is taller after the top inset, but lies flat in tabletop posture.
        assertEquals(Rect(100, 24, 1100, 400), usableWindowPane(window, listOf(fold)))
        val edgeHinge = NativeWindowFold(Rect(0, 0, 1200, 40), false, false, true)
        assertEquals(Rect(100, 40, 1100, 824), usableWindowPane(window, listOf(edgeHinge)))
        // An IME-resized content rectangle above the hinge is no longer split by it.
        assertEquals(Rect(100, 24, 1100, 350), usableWindowPane(Rect(100, 24, 1100, 350), listOf(fold)))
    }
    @Test fun `clipped horizontal windows choose a usable lower pane instead of a top sliver`() {
        val fold = NativeWindowFold(Rect(0, 400, 1200, 420), false, true, false)
        for (top in listOf(380, 400, 420)) {
            assertEquals(Rect(100, 420, 1100, 824),
                usableWindowPane(Rect(100, top, 1100, 824), listOf(fold)))
        }
        assertEquals(Rect(100, 420, 1100, 824), usableWindowPane(Rect(100, 24, 1100, 824),
            listOf(fold.copy(separating = false, occluding = true))))
    }
    @Test fun `outside folds are ignored and edge occlusion is excluded`() {
        val window = Rect(0, 0, 800, 600)
        assertEquals(window, usableWindowPane(window, listOf(vertical(900, 920))))
        assertEquals(Rect(20, 0, 800, 600), usableWindowPane(window, listOf(vertical(-10, 20, false, true))))
        assertEquals(Rect(0, 0, 780, 600), usableWindowPane(window, listOf(vertical(780, 820, false, true))))
    }
    @Test fun `lock and recovery controls remain in the usable pane while the cover fills the window`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val content = FrameLayout(activity)
        val root = View(activity)
        val cover = ReactAppLockView(activity, content, {}, {})
        content.addView(root, FrameLayout.LayoutParams(-1, -1))
        content.addView(cover, FrameLayout.LayoutParams(-1, -1))
        val owner = NativeWindowViewport(content, root) { cover.usePane(it, content.width, content.height) }
        val locked = com.lavasec.app.security.SecurityOwnerState(
            availability = com.lavasec.app.security.SecurityAvailability.Available, enabled = true,
            protectedSurfaces = setOf(com.lavasec.app.foundation.security.ProtectedSurface.APP_UNLOCK))
        fun layout() {
            content.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            content.layout(0, 0, 1000, 800)
        }
        try {
            layout()
            for (folds in listOf(listOf(vertical(490, 510)), listOf(vertical(290, 310)),
                listOf(NativeWindowFold(Rect(0, 390, 1000, 410), false, true, true)), emptyList())) {
                owner.update(folds)
                val pane = usableWindowPane(Rect(0, 0, 1000, 800), folds)
                for (security in listOf(locked, locked.copy(recoveryRequired = true))) {
                    cover.render(true, security, false); layout()
                    assertEquals(1000, cover.width); assertEquals(800, cover.height)
                    assertTrue(cover.isClickable); assertEquals(View.INVISIBLE, root.visibility)
                    for (index in 0 until cover.childCount) {
                        val control = cover.getChildAt(index)
                        if (control.visibility == View.VISIBLE) {
                            assertTrue("A visible lock control must not intersect the hinge",
                                pane.contains(control.left, control.top, control.right, control.bottom))
                            assertTrue(control.width > 0 && control.height > 0)
                        }
                    }
                }
            }
        } finally { owner.close(); activity.finish() }
    }

    @Test fun `native pane resizes the existing React root while privacy cover stays full size`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val content = FrameLayout(activity)
        val root = View(activity); val cover = View(activity)
        content.addView(root, FrameLayout.LayoutParams(-1, -1)); content.addView(cover, FrameLayout.LayoutParams(-1, -1))
        val owner = NativeWindowViewport(content, root)
        content.layout(0, 0, 1000, 800)
        owner.update(listOf(vertical(490, 510)))
        assertEquals(510, (root.layoutParams as FrameLayout.LayoutParams).rightMargin)
        assertEquals(0, (cover.layoutParams as FrameLayout.LayoutParams).rightMargin)
        assertSame(root, content.getChildAt(0))
        content.layout(0, 0, 400, 800) // split window no longer intersects the fold
        assertEquals(0, (root.layoutParams as FrameLayout.LayoutParams).rightMargin)
        owner.update(emptyList())
        assertEquals(0, (root.layoutParams as FrameLayout.LayoutParams).leftMargin)
        owner.close(); owner.update(listOf(vertical(190, 210)))
        assertEquals(0, (root.layoutParams as FrameLayout.LayoutParams).rightMargin)
        activity.finish()
    }
}
