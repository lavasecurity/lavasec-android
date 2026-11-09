package com.lavasec.app.foundation.haptics

/**
 * The canonical semantic feedback vocabulary, pinned to the iOS `LavaFeedbackSemantic` enum at
 * commit c4658d973bae05dd3b7aa84c014cb2f76ff61a0b (`Sources/LavaSecPresentation`).
 *
 * These are platform-independent *meanings*: a renderer decides hardware and whether the user's
 * preference allows the effect at all. The wire values are exactly the strings the RN contract
 * sends as `{type:'haptic', kind}`; an unknown value is a rejection, never a silent no-op.
 */
enum class HapticSemantic(val wireValue: String) {
    /** A selection crossing or discrete control change (the light selection tick). */
    SELECTED("selected"),

    /** A control was engaged (tapped/held); a light confirmation impact. */
    ENGAGED("engaged"),

    /** A consequential operation completed successfully. */
    SUCCEEDED("succeeded"),

    /** The user must attend to something; a warning notification. */
    ATTENTION_REQUIRED("attentionRequired"),

    /** A consequential operation failed. */
    FAILED("failed"),

    /** A tap/gesture was acknowledged without changing state. */
    ACKNOWLEDGED("acknowledged"),

    /** A measured-zero/inspection target was inspected; a restrained soft pulse. */
    INSPECTION_EMPTY("inspectionEmpty"),
    ;

    companion object {
        /**
         * Parse a wire `kind`. Returns `null` for a missing, blank, or unknown value so callers can
         * reject it explicitly (a typed error) instead of treating it as a successful no-op.
         */
        fun fromWire(value: String?): HapticSemantic? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * The Android-renderable feedback kinds the canonical semantics project onto. This is the pure
 * target of [HapticSemantic.androidKind]: no Android framework type appears here, so the mapping
 * is JVM-testable.
 */
enum class HapticKind {
    /** A light impact (selection confirmation, acknowledged tap). */
    IMPACT_LIGHT,

    /** A medium impact. */
    IMPACT_MEDIUM,

    /** A heavy impact. */
    IMPACT_HEAVY,

    /** A restrained soft pulse (an empty/inspection target). */
    IMPACT_SOFT,

    /** A success notification. */
    NOTIFICATION_SUCCESS,

    /** A warning notification (attention required). */
    NOTIFICATION_WARNING,

    /** An error notification (failure). */
    NOTIFICATION_ERROR,

    /** A selection tick for discrete adjacent choices. */
    SELECTION,
}

/**
 * Pure projection of the canonical semantic onto the Android kind. Mirrors the iOS
 * `ProtectionHapticFeedback.playSemantic` mapping: `selected` -> selection change,
 * `engaged`/`acknowledged` -> light impact, `succeeded` -> success, `attentionRequired` ->
 * warning, `failed` -> error, `inspectionEmpty` -> soft impact.
 *
 * `IMPACT_MEDIUM`/`IMPACT_HEAVY` are part of the platform kind vocabulary but no canonical
 * semantic currently selects them; they exist so the renderer has one complete impact scale.
 */
val HapticSemantic.androidKind: HapticKind
    get() = when (this) {
        HapticSemantic.SELECTED -> HapticKind.SELECTION
        HapticSemantic.ENGAGED -> HapticKind.IMPACT_LIGHT
        HapticSemantic.ACKNOWLEDGED -> HapticKind.IMPACT_LIGHT
        HapticSemantic.SUCCEEDED -> HapticKind.NOTIFICATION_SUCCESS
        HapticSemantic.ATTENTION_REQUIRED -> HapticKind.NOTIFICATION_WARNING
        HapticSemantic.FAILED -> HapticKind.NOTIFICATION_ERROR
        HapticSemantic.INSPECTION_EMPTY -> HapticKind.IMPACT_SOFT
    }
