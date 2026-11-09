package com.lavasec.app.reactnative

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NativeSnapshotEmissionTest {
    private fun NativeSnapshotEmission.shouldEmit(snapshot: String, commandReceipt: Boolean = false) = emit(snapshot, commandReceipt) { }

    @Test fun `a failed delivery cannot suppress its retry`() {
        val gate = NativeSnapshotEmission()
        assertThrows(IllegalStateException::class.java) { gate.emit(frame()) { error("React context unavailable") } }
        assertTrue(gate.shouldEmit(frame(2)))
    }

    private fun frame(revision: Int = 1, mutate: (JSONObject) -> Unit = {}): String = JSONObject()
        .put("revision", revision).put("nativeHostID", "a").put("generation", "7")
        .put("foreground", true).put("presentationBlocked", false)
        .put("security", JSONObject().put("readRevision", "5"))
        .put("protection", JSONObject().put("lifecycle", "starting"))
        .also(mutate).toString()

    @Test fun `identical observer bursts emit once but every command receipt emits its exact frame`() {
        val gate = NativeSnapshotEmission()
        assertTrue(gate.shouldEmit(frame()))
        for (revision in 2..50) assertFalse(gate.shouldEmit(frame(revision)))
        assertTrue(gate.shouldEmit(frame(51), commandReceipt = true))
        assertFalse(gate.shouldEmit(frame(52)))
    }

    @Test fun `revocation host security and service transitions publish immediately`() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("foreground", false) }, { it.put("presentationBlocked", true) },
            { it.put("nativeHostID", "b") }, { it.put("generation", "8") },
            { it.getJSONObject("security").put("readRevision", "6") },
            { it.getJSONObject("protection").put("lifecycle", "protected") },
            { it.put("futureAuthorityField", true) },
        )
        for (mutate in mutations) {
            val gate = NativeSnapshotEmission()
            assertTrue(gate.shouldEmit(frame()))
            assertTrue(gate.shouldEmit(frame(2, mutate)))
            assertTrue(gate.shouldEmit(frame(3)))
        }
    }
}
