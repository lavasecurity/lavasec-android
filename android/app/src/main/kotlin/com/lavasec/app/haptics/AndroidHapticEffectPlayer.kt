package com.lavasec.app.haptics

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import com.lavasec.app.foundation.haptics.HapticKind

/**
 * The Android renderer for [HapticKind]. It uses [View.performHapticFeedback] rather than the raw
 * `Vibrator`: it needs no `android.permission.VIBRATE` and it respects the system touch-feedback
 * setting, on top of the Lava Haptics preference the caller already applied.
 */
class AndroidHapticEffectPlayer(private val view: View) : HapticEffectSink {
    override fun play(kind: HapticKind) {
        val constant = when (kind) {
            HapticKind.SELECTION -> HapticFeedbackConstants.CLOCK_TICK
            HapticKind.IMPACT_SOFT -> HapticFeedbackConstants.TEXT_HANDLE_MOVE
            HapticKind.IMPACT_LIGHT -> HapticFeedbackConstants.VIRTUAL_KEY
            HapticKind.IMPACT_MEDIUM -> HapticFeedbackConstants.CONTEXT_CLICK
            HapticKind.IMPACT_HEAVY -> HapticFeedbackConstants.LONG_PRESS
            HapticKind.NOTIFICATION_SUCCESS -> confirmConstant()
            HapticKind.NOTIFICATION_WARNING -> HapticFeedbackConstants.CONTEXT_CLICK
            HapticKind.NOTIFICATION_ERROR -> rejectConstant()
        }
        view.performHapticFeedback(constant)
    }

    private fun confirmConstant(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.VIRTUAL_KEY

    private fun rejectConstant(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT
        else HapticFeedbackConstants.LONG_PRESS
}
