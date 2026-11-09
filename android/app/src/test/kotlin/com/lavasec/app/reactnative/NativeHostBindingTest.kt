package com.lavasec.app.reactnative

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NativeHostBindingTest {
    @Test fun `retained root cannot read act or acknowledge a replacement window`() {
        assertTrue(NativeHostBinding.accepts("source", "source", false))
        assertFalse(NativeHostBinding.accepts("source", "preview", false))
        assertFalse(NativeHostBinding.accepts("source", "source", true))
        assertFalse(NativeHostBinding.accepts("source", null, false))
        assertFalse(NativeHostBinding.accepts("", "preview", false))
        assertFalse(NativeHostBinding.accepts(JSONObject().put("nativeHostID", ""), "preview", false))
        assertFalse(NativeHostBinding.accepts(JSONObject().put("nativeHostID", JSONObject.NULL), "preview", false))
        assertTrue(NativeHostBinding.accepts(JSONObject(), "preview", false))
        for (type in listOf("navigation.presentation", "navigation.presentationReady", "navigation.endTurn",
            "onboarding.forward", "haptic", "guard.gesture", "filters.query")) {
            assertFalse(NativeHostBinding.isExactRetirement(JSONObject().put("schema", 1).put("type", type)
                .put("id", "source-owner").put("token", "review-token")))
        }
    }

    @Test fun `window retirement only permits exact read free owned cleanup`() {
        val command = JSONObject().put("schema", 1).put("type", "filter.cancel").put("id", "filter")
        assertFalse(NativeHostBinding.isExactRetirement(command))
        command.put("retirementGeneration", "7")
        assertFalse(NativeHostBinding.isExactRetirement(command))
        command.put("retirementLease", "draft-uuid")
        assertTrue(NativeHostBinding.isExactRetirement(command))
        assertFalse(NativeHostBinding.isExactRetirement(JSONObject().put("schema", 1).put("type", "domains.cancel")))
        assertTrue(NativeHostBinding.isExactRetirement(JSONObject().put("schema", 1).put("type", "domains.cancel").put("token", "owned-token")))
    }
}
