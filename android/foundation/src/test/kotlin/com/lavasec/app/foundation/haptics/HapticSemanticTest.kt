package com.lavasec.app.foundation.haptics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HapticSemanticTest {
    /**
     * The exact canonical vocabulary pinned to iOS `LavaFeedbackSemantic`
     * (c4658d973bae05dd3b7aa84c014cb2f76ff61a0b). If iOS adds a case, Android must add it
     * deliberately (and map it) rather than silently accepting a new wire value.
     */
    @Test
    fun `semantic set is exactly the canonical seven with their wire values`() {
        assertEquals(
            listOf(
                "selected",
                "engaged",
                "succeeded",
                "attentionRequired",
                "failed",
                "acknowledged",
                "inspectionEmpty",
            ),
            HapticSemantic.entries.map(HapticSemantic::wireValue),
        )
    }

    @Test
    fun `fromWire round-trips every canonical value`() {
        HapticSemantic.entries.forEach { semantic ->
            assertEquals(semantic, HapticSemantic.fromWire(semantic.wireValue))
        }
    }

    @Test
    fun `fromWire rejects unknown, missing, blank, and case-mismatched values`() {
        listOf<String?>(null, "", " ", "selection", "SUCCESS", "success", "changed", "rejected",
            "unknown").forEach { value ->
            assertNull("expected null for $value", HapticSemantic.fromWire(value))
        }
    }

    @Test
    fun `the canonical semantics produce exactly the expected Android kinds`() {
        assertEquals(
            setOf(
                HapticKind.SELECTION,
                HapticKind.IMPACT_LIGHT,
                HapticKind.IMPACT_SOFT,
                HapticKind.NOTIFICATION_SUCCESS,
                HapticKind.NOTIFICATION_WARNING,
                HapticKind.NOTIFICATION_ERROR,
            ),
            HapticSemantic.entries.map(HapticSemantic::androidKind).toSet(),
        )
    }

    @Test
    fun `mapping matches the canonical iOS playSemantic intent`() {
        assertEquals(HapticKind.SELECTION, HapticSemantic.SELECTED.androidKind)
        assertEquals(HapticKind.IMPACT_LIGHT, HapticSemantic.ENGAGED.androidKind)
        assertEquals(HapticKind.IMPACT_LIGHT, HapticSemantic.ACKNOWLEDGED.androidKind)
        assertEquals(HapticKind.NOTIFICATION_SUCCESS, HapticSemantic.SUCCEEDED.androidKind)
        assertEquals(HapticKind.NOTIFICATION_WARNING, HapticSemantic.ATTENTION_REQUIRED.androidKind)
        assertEquals(HapticKind.NOTIFICATION_ERROR, HapticSemantic.FAILED.androidKind)
        assertEquals(HapticKind.IMPACT_SOFT, HapticSemantic.INSPECTION_EMPTY.androidKind)
    }
}
