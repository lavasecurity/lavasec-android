package com.lavasec.app.foundation.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the canonical retained preference model: strict key/type
 * dispatch matching the iOS `settings.set` rules, and canon-matching defaults.
 */
class ProductPreferencesTest {
    private fun defaults(): ProductPreferences = ProductPreferences.canonicalDefaults()

    @Test
    fun `unknown wire key is rejected by both value and default setters`() {
        val model = defaults()

        val direct = model.set("not.a.real.key", PreferenceValue.Bool(true))
        assertTrue(direct.isFailure)
        assertTrue(direct.exceptionOrNull() is UnknownPreferenceKeyException)

        val resolverKey = model.set("dnsPatchSetup", PreferenceValue.Text("quad9"))
        assertTrue(resolverKey.isFailure)
        assertTrue(resolverKey.exceptionOrNull() is UnknownPreferenceKeyException)
    }

    @Test
    fun `wrong value shape is rejected per key`() {
        val model = defaults()

        val booleanAsText = model.set(ProductPreferenceKey.HAPTICS, PreferenceValue.Text("true"))
        assertTrue(booleanAsText.isFailure)
        assertTrue(booleanAsText.exceptionOrNull() is InvalidPreferenceValueException)

        val textAsBoolean = model.set(ProductPreferenceKey.APPEARANCE, PreferenceValue.Bool(true))
        assertTrue(textAsBoolean.isFailure)
        assertTrue(textAsBoolean.exceptionOrNull() is InvalidPreferenceValueException)

        val intAsBoolean = model.set(ProductPreferenceKey.TEXT_SIZE, PreferenceValue.Bool(true))
        assertTrue(intAsBoolean.isFailure)
        assertTrue(intAsBoolean.exceptionOrNull() is InvalidPreferenceValueException)

        val intAsText = model.set(
            ProductPreferenceKey.LIVE_ACTIVITY_PAUSE_MINUTES,
            PreferenceValue.Text("5"),
        )
        assertTrue(intAsText.isFailure)
        assertTrue(intAsText.exceptionOrNull() is InvalidPreferenceValueException)
    }

    @Test
    fun `out of domain values are rejected even with the right shape`() {
        val model = defaults()

        assertTrue(
            model.set(ProductPreferenceKey.APPEARANCE, PreferenceValue.Text("sepia")).isFailure,
        )
        assertTrue(
            model.set(ProductPreferenceKey.LOOK, PreferenceValue.Text("neon")).isFailure,
        )
        assertTrue(
            model.set(ProductPreferenceKey.TEXT_SIZE, PreferenceValue.IntValue(7)).isFailure,
        )
        assertTrue(
            model.set(ProductPreferenceKey.TEXT_SIZE, PreferenceValue.IntValue(-1)).isFailure,
        )
        assertTrue(
            model.set(
                ProductPreferenceKey.LIVE_ACTIVITY_PAUSE_MINUTES,
                PreferenceValue.IntValue(0),
            ).isFailure,
        )
        assertTrue(
            model.set(
                ProductPreferenceKey.LIVE_ACTIVITY_PAUSE_MINUTES,
                PreferenceValue.IntValue(31),
            ).isFailure,
        )
    }

    @Test
    fun `valid writes round-trip through the typed accessors and leave the receiver unchanged`() {
        val model = defaults()

        var updated = model.set(ProductPreferenceKey.APPEARANCE, PreferenceValue.Text("dark"))
            .getOrThrow()
        assertEquals(AppearancePreference.DARK, updated.appearance)

        updated = updated.set(
            ProductPreferenceKey.LIVE_ACTIVITY_PAUSE_MINUTES,
            PreferenceValue.IntValue(30),
        ).getOrThrow()
        assertEquals(30, updated.liveActivityPauseMinutes)

        updated = updated.set(ProductPreferenceKey.TEXT_SIZE, PreferenceValue.IntValue(0)).getOrThrow()
        assertEquals(TextSizePreference.X_SMALL, updated.textSize)

        // The original value model is immutable.
        assertEquals(AppearancePreference.SYSTEM, model.appearance)
        assertEquals(5, model.liveActivityPauseMinutes)
        assertEquals(TextSizePreference.LARGE, model.textSize)
    }

    @Test
    fun `look accepts every canonical raw value including the renamed cases`() {
        val model = defaults()

        for (look in GuardianLook.entries) {
            val updated = model.set(ProductPreferenceKey.LOOK, PreferenceValue.Text(look.wire))
            assertTrue("expected ${look.wire} to be accepted", updated.isSuccess)
            assertEquals(look, updated.getOrThrow().look)
        }
    }

    @Test
    fun `equal value models are equal and distinct writes are not`() {
        val base = defaults()
        assertEquals(base, ProductPreferences.canonicalDefaults())
        assertEquals(base.hashCode(), ProductPreferences.canonicalDefaults().hashCode())
        assertFalse(base == base.set(ProductPreferenceKey.HAPTICS, PreferenceValue.Bool(false)).getOrThrow())
    }

    @Test
    fun `requireWire throws for an unknown key and resolves a canonical one`() {
        assertSame(ProductPreferenceKey.HAPTICS, ProductPreferenceKey.requireWire("haptics"))
        val thrown = runCatching { ProductPreferenceKey.requireWire("bogus") }.exceptionOrNull()
        assertTrue(thrown is UnknownPreferenceKeyException)
    }

    @Test
    fun `toMap exposes every retained key`() {
        assertEquals(ProductPreferenceKey.entries.toSet(), defaults().toMap().keys)
        assertNull(ProductPreferenceKey.fromWire("protectedActions.appUnlock"))
    }
}
