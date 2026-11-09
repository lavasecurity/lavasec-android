package com.lavasec.app.reactnative

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NativeAuthenticationResultTest {
    @Test fun `every authentication refusal remains cancellation across the wire`() {
        for (reason in listOf("unauthorized", "edit-refused", "review-refused", "save-refused", "apply-refused",
            "switch-refused", "delete-refused", "create-refused", "rename-refused", "domain-refused")) {
            val reply = """{"schema":1,"status":"rejected","reason":"$reason"}"""
            val result = JSONObject(authenticationResult(reply, true))
            assertEquals(1, result.getInt("schema"))
            assertEquals("cancelled", result.getString("status"))
            assertEquals("authentication", result.getString("reason"))
            assertEquals(reply, authenticationResult(reply, false))
        }
    }
    @Test fun `a late dismissal cannot replace success review payload or an existing cancellation`() {
        for (status in listOf("completed", "cancelled")) {
            val reply = """{"schema":1,"status":"$status","reason":null,"value":"review"}"""
            assertEquals(reply, authenticationResult(reply, true))
        }
    }
}
