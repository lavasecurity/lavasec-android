package com.lavasec.app.haptics

import com.lavasec.app.foundation.haptics.HapticKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticCommandHandlerTest {
    private class Recorder {
        val played = mutableListOf<HapticKind>()

        val sink = HapticEffectSink { kind -> played += kind }
    }

    private fun handler(enabled: Boolean, recorder: Recorder) =
        HapticCommandHandler(hapticsEnabled = { enabled }, sink = recorder.sink)

    @Test
    fun `known semantics play their canonical Android kind when haptics are enabled`() {
        val recorder = Recorder()
        val handler = handler(enabled = true, recorder)
        val expected = mapOf(
            "selected" to HapticKind.SELECTION,
            "engaged" to HapticKind.IMPACT_LIGHT,
            "acknowledged" to HapticKind.IMPACT_LIGHT,
            "succeeded" to HapticKind.NOTIFICATION_SUCCESS,
            "attentionRequired" to HapticKind.NOTIFICATION_WARNING,
            "failed" to HapticKind.NOTIFICATION_ERROR,
            "inspectionEmpty" to HapticKind.IMPACT_SOFT,
        )
        expected.forEach { (wire, kind) ->
            assertEquals(HapticCommandOutcome.PLAYED, handler.handle(wire))
            assertEquals(kind, recorder.played.last())
        }
        assertEquals(expected.values.toList(), recorder.played)
    }

    @Test
    fun `unknown semantics are rejected and never played`() {
        val recorder = Recorder()
        val handler = handler(enabled = true, recorder)
        listOf(null, "", " ", "selection", "changed", "rejected", "success", "nope").forEach { wire ->
            assertEquals(HapticCommandOutcome.UNKNOWN_SEMANTIC, handler.handle(wire))
        }
        assertTrue(recorder.played.isEmpty())
    }

    @Test
    fun `the Lava Haptics preference suppresses a known semantic but not the parse`() {
        val recorder = Recorder()
        val handler = handler(enabled = false, recorder)
        assertEquals(HapticCommandOutcome.SUPPRESSED, handler.handle("selected"))
        assertTrue(recorder.played.isEmpty())
    }

    @Test
    fun `an unknown semantic is still rejected when haptics are disabled`() {
        val recorder = Recorder()
        val handler = handler(enabled = false, recorder)
        assertEquals(HapticCommandOutcome.UNKNOWN_SEMANTIC, handler.handle("success"))
        assertTrue(recorder.played.isEmpty())
    }
}
