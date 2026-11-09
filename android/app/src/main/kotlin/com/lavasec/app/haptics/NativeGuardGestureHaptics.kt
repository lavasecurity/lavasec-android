package com.lavasec.app.haptics

import com.lavasec.app.foundation.haptics.HapticKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class GuardGestureHapticOutcome { COMPLETED, SUPPRESSED, UNKNOWN_GESTURE }

/** The discrete fallback from frozen GuardianLongPressHaptics, including its silent grace. */
internal object GuardLongPressHapticSchedule {
    const val HOLD_DURATION_MILLIS = 1_200L
    const val MOVEMENT_TOLERANCE = 24
    const val GRACE_MILLIS = 300L
    const val PULSE_INTERVAL_MILLIS = 100L
    data class Pulse(val delayMillis: Long, val kind: HapticKind)
    val pulses: List<Pulse> = List(9) { index ->
        Pulse(GRACE_MILLIS + index * PULSE_INTERVAL_MILLIS, when {
            index < 3 -> HapticKind.IMPACT_LIGHT
            index < 6 -> HapticKind.IMPACT_MEDIUM
            else -> HapticKind.IMPACT_HEAVY
        })
    }
}

/**
 * Effect-only receiver for shared Guard gestures. The native decoration recognizes contact;
 * this owner supplies the iOS discrete fallback without publishing a product snapshot.
 *
 * Each pulse belongs to the exact host that accepted start. Losing foreground, changing host,
 * disabling App Haptics, ending contact or closing permanently cancels that ramp. The Android
 * sink uses View.performHapticFeedback without ignore-setting flags, preserving the system gate.
 */
class NativeGuardGestureHaptics(
    scope: CoroutineScope,
    private val currentHost: () -> Any?,
    private val foreground: () -> Boolean,
    private val hapticsEnabled: () -> Boolean,
    private val sink: (host: Any, kind: HapticKind) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : AutoCloseable {
    private val lock = Any()
    private val effects = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + dispatcher)
    private class Ramp(val host: Any) { var job: Job? = null }
    private var ramp: Ramp? = null
    private var closed = false

    fun handle(gesture: String?): GuardGestureHapticOutcome = synchronized(lock) {
        if (gesture !in setOf("start", "end", "reveal", "tap")) return@synchronized GuardGestureHapticOutcome.UNKNOWN_GESTURE
        if (gesture == "end") {
            cancelRampLocked()
            return@synchronized GuardGestureHapticOutcome.COMPLETED
        }
        if (gesture == "start" || gesture == "reveal") cancelRampLocked()
        val host = currentHost()
        if (host == null || !mayPlay(host)) return@synchronized GuardGestureHapticOutcome.SUPPRESSED
        when (gesture) {
            "start" -> {
                val owned = Ramp(host)
                ramp = owned
                owned.job = effects.launch {
                    try {
                        var previousDelay = 0L
                        for (pulse in GuardLongPressHapticSchedule.pulses) {
                            delay(pulse.delayMillis - previousDelay)
                            previousDelay = pulse.delayMillis
                            synchronized(lock) {
                                if (ramp !== owned || !mayPlay(owned.host)) {
                                    if (ramp === owned) cancelRampLocked()
                                    return@launch
                                }
                                sink(owned.host, pulse.kind)
                            }
                        }
                    } finally {
                        synchronized(lock) { if (ramp === owned) ramp = null }
                    }
                }
            }
            "reveal" -> sink(host, HapticKind.IMPACT_HEAVY)
            "tap" -> sink(host, HapticKind.IMPACT_LIGHT)
        }
        GuardGestureHapticOutcome.COMPLETED
    }

    /** Invoke on native pause/destroy/host replacement and preference changes. Never resumes. */
    fun authorityChanged() = synchronized(lock) {
        ramp?.let { if (!mayPlay(it.host)) cancelRampLocked() }
    }

    private fun mayPlay(host: Any) = !closed && effects.coroutineContext[Job]?.isActive == true &&
        foreground() && hapticsEnabled() && currentHost() === host

    private fun cancelRampLocked() {
        val retired = ramp
        ramp = null
        retired?.job?.cancel()
    }

    override fun close() {
        synchronized(lock) { closed = true; cancelRampLocked() }
        effects.cancel()
    }
}
