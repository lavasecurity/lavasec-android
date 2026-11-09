package com.lavasec.app.haptics

import com.lavasec.app.foundation.haptics.HapticKind
import com.lavasec.app.foundation.haptics.HapticSemantic
import com.lavasec.app.foundation.haptics.androidKind

/** The physical effect port. Implemented by the Android adapter; a recorder stands in for tests. */
fun interface HapticEffectSink {
    fun play(kind: HapticKind)
}

/** The typed outcome of a `haptic` command, so the bridge can reject explicitly. */
enum class HapticCommandOutcome {
    /** The effect was played. */
    PLAYED,

    /** A known semantic was suppressed by the Lava Haptics preference; still a success. */
    SUPPRESSED,

    /** The `kind` is not part of the canonical vocabulary; the command must be rejected. */
    UNKNOWN_SEMANTIC,
}

/**
 * Pure command handler for the canonical `haptic` command: parse the semantic, apply the Lava
 * Haptics preference, then project onto the Android kind and render through [sink].
 *
 * Unknown semantics are rejected before the preference gate, so a disabled preference can never
 * turn an invalid `kind` into a successful no-op. Contains no Android types, so it is
 * unit-testable on the JVM.
 */
class HapticCommandHandler(
    private val hapticsEnabled: () -> Boolean,
    private val sink: HapticEffectSink,
) {
    fun handle(wireSemantic: String?): HapticCommandOutcome {
        val semantic = HapticSemantic.fromWire(wireSemantic)
            ?: return HapticCommandOutcome.UNKNOWN_SEMANTIC
        if (!hapticsEnabled()) return HapticCommandOutcome.SUPPRESSED
        sink.play(semantic.androidKind)
        return HapticCommandOutcome.PLAYED
    }
}
