package com.lavasec.app.foundation.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the exact canonical retained key catalog and its defaults against the
 * pinned iOS source (c4658d973bae05dd3b7aa84c014cb2f76ff61a0b). Because the
 * expectation is hardcoded, a new/renamed/removed canonical key fails this test
 * until the model is explicitly updated — the model never grows implicitly.
 */
class ProductPreferenceCatalogTest {
    private data class Canonical(
        val wire: String,
        val type: PreferenceValueType,
        val default: PreferenceValue,
    )

    private val canonical: List<Canonical> = listOf(
        // ReactNative/native-app/LavaAppSettings.swift setSetting dispatch.
        Canonical("appearance", PreferenceValueType.TEXT, PreferenceValue.Text("system")),
        Canonical("look", PreferenceValueType.TEXT, PreferenceValue.Text("original")),
        Canonical("matchTextSize", PreferenceValueType.BOOLEAN, PreferenceValue.Bool(true)),
        Canonical("textSize", PreferenceValueType.INTEGER, PreferenceValue.IntValue(3)),
        Canonical("haptics", PreferenceValueType.BOOLEAN, PreferenceValue.Bool(true)),
        Canonical("liveActivityPauseMinutes", PreferenceValueType.INTEGER, PreferenceValue.IntValue(5)),
        Canonical("liveActivities", PreferenceValueType.BOOLEAN, PreferenceValue.Bool(false)),
        Canonical("matchIcon", PreferenceValueType.BOOLEAN, PreferenceValue.Bool(true)),
        Canonical("backup.automatic", PreferenceValueType.BOOLEAN, PreferenceValue.Bool(false)),
        Canonical("notifications.Filter changes", PreferenceValueType.BOOLEAN, PreferenceValue.Bool(true)),
        Canonical(
            "notifications.Filter couldn't switch",
            PreferenceValueType.BOOLEAN,
            PreferenceValue.Bool(true),
        ),
        Canonical("notifications.Protection resumed", PreferenceValueType.BOOLEAN, PreferenceValue.Bool(true)),
        Canonical("notifications.Connection updates", PreferenceValueType.BOOLEAN, PreferenceValue.Bool(true)),
        Canonical("logs.Filtering Counts", PreferenceValueType.BOOLEAN, PreferenceValue.Bool(true)),
        Canonical("logs.Domain logs", PreferenceValueType.BOOLEAN, PreferenceValue.Bool(true)),
        Canonical("logs.Network activity", PreferenceValueType.BOOLEAN, PreferenceValue.Bool(true)),
        Canonical("logs.Lava Guard Progress", PreferenceValueType.BOOLEAN, PreferenceValue.Bool(true)),
    )

    @Test
    fun `the model key set matches the canonical catalog exactly`() {
        assertEquals(canonical.map { it.wire }, ProductPreferenceKey.entries.map { it.wire })
    }

    @Test
    fun `every canonical key declares the canon value type and default`() {
        for (entry in canonical) {
            val key = ProductPreferenceKey.fromWire(entry.wire)
            assertTrue("missing model key for ${entry.wire}", key != null)
            requireNotNull(key)
            assertEquals("type for ${entry.wire}", entry.type, key.valueType)
            assertEquals("default for ${entry.wire}", entry.default, key.canonicalDefault)
            assertEquals(
                "canonical default must match the model initializer for ${entry.wire}",
                entry.default,
                ProductPreferences.canonicalDefaults().value(key),
            )
        }
    }

    @Test
    fun `a future canonical key fails closed until the model is changed`() {
        // The fixture is the future-key tripwire: if iOS adds a retained key, this
        // test is where the model must be updated explicitly. Until then the runtime
        // rejects it.
        assertNull(ProductPreferenceKey.fromWire("newFutureRetainedKey"))
        val rejected = ProductPreferences.canonicalDefaults()
            .set("newFutureRetainedKey", PreferenceValue.Bool(true))
        assertTrue(rejected.isFailure)
        assertTrue(rejected.exceptionOrNull() is UnknownPreferenceKeyException)
    }
}
