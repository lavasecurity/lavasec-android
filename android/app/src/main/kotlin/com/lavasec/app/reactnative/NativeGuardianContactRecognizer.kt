package com.lavasec.app.reactnative

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.lavasec.app.haptics.GuardLongPressHapticSchedule

/** Contact recognition only; RN owns actions and the bridge owns haptics. */
internal class NativeGuardianContactRecognizer(
    private val view: View,
    private val enabled: () -> Boolean,
    private val emit: (String) -> Unit,
) {
    private val timer = Handler(Looper.getMainLooper())
    private val tapSlop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
    private var contact = false
    private var tracking = false
    private var revealed = false
    private var tapEligible = false
    private var pointerId = -1
    private var originX = 0f
    private var originY = 0f
    private val reveal = Runnable {
        if (!tracking || !contact || !enabled()) { cancel(); return@Runnable }
        revealed = true
        finishContact()
        emit("reveal")
    }

    fun touch(event: MotionEvent): Boolean {
        if (!enabled()) { cancel(); return false }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancel()
                if (event.pointerCount != 1) return false
                tracking = true; contact = true; revealed = false; tapEligible = true
                pointerId = event.getPointerId(0); originX = event.x; originY = event.y
                // The frozen iOS tracking guard locks ancestor scrolling for the entire contact.
                view.parent?.requestDisallowInterceptTouchEvent(true)
                emit("start")
                timer.postDelayed(reveal, GuardLongPressHapticSchedule.HOLD_DURATION_MILLIS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                val index = event.findPointerIndex(pointerId)
                if (index < 0 || event.pointerCount != 1) { cancel(); return true }
                val dx = event.getX(index) - originX; val dy = event.getY(index) - originY
                if (dx * dx + dy * dy > tapSlop * tapSlop) tapEligible = false
                // Long-press drift remains owned, matching allowableMovement = infinity on iOS.
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!tracking) return false
                val dx = event.x - originX; val dy = event.y - originY
                val tap = !revealed && tapEligible && event.pointerCount == 1 && dx * dx + dy * dy <= tapSlop * tapSlop
                cancel()
                if (tap) emit("tap")
                return true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                val consumed = tracking
                cancel()
                return consumed
            }
        }
        return tracking
    }

    fun cancel() {
        timer.removeCallbacks(reveal)
        val owned = tracking
        tracking = false; tapEligible = false; revealed = false; pointerId = -1
        finishContact()
        if (owned) view.parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun finishContact() {
        if (!contact) return
        contact = false
        emit("end")
    }
}
