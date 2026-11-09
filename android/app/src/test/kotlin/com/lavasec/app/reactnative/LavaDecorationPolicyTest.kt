package com.lavasec.app.reactnative

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.lavasec.app.foundation.preferences.GuardianLook
import com.lavasec.app.presentation.mascot.GuardianMascotState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Policy tests for the `LavaDecoration` platform leaf.
 *
 * Pure JVM: the Android view managers themselves need a `ThemedReactContext` and
 * a host renderer (Robolectric/Fabric), which this aarch64 host cannot execute,
 * so the JVM lane pins the decisions the managers delegate here.
 */
class LavaDecorationPolicyTest {
    @Test
    fun `shared RN guardian contact leaf never paints a competing mascot`() {
        for (mood in listOf("sleeping", "awake", "grateful", "locked")) {
            assertEquals(LavaDecorationPolicy.Drawing.TRANSPARENT, LavaDecorationPolicy.drawing("guardian.gestures", mood))
        }
        assertEquals(LavaDecorationPolicy.Drawing.LOCKED, LavaDecorationPolicy.drawing("", "locked"))
        assertEquals(LavaDecorationPolicy.Drawing.GUARDIAN, LavaDecorationPolicy.drawing("", "awake"))
    }

    @Test
    fun `catalog material never becomes a Guardian symbol or reveal and recycling restores ordinary roles`() {
        for (mood in listOf("sleeping", "awake", "locked")) {
            assertEquals(LavaDecorationPolicy.Drawing.CATALOG_CONTROL,
                LavaDecorationPolicy.drawing("catalog.control.material", mood))
        }
        assertFalse(LavaDecorationPolicy.admitsGuardianContact("catalog.control.material"))
        assertFalse(LavaDecorationPolicy.admitsGuardianContact("form.separator"))
        assertTrue(LavaDecorationPolicy.admitsGuardianContact("guardian.gestures"))
        assertEquals(LavaDecorationPolicy.Drawing.SYMBOL, LavaDecorationPolicy.drawing("magnifyingglass", "awake"))
        assertEquals(LavaDecorationPolicy.Drawing.TRANSPARENT, LavaDecorationPolicy.drawing("guardian.gestures", "awake"))
    }

    @Test
    fun `native catalog outline follows half the actual height after resize in either layout direction`() {
        for (direction in LayoutDirection.entries) for (size in listOf(Size(280f, 44f), Size(160f, 64f))) {
            val outline = NativeCatalogControlShape.createOutline(size, direction, Density(2f)) as Outline.Rounded
            assertEquals(0f, outline.roundRect.left, 0f)
            assertEquals(size.width, outline.roundRect.right, 0f)
            assertEquals(size.height, outline.roundRect.bottom, 0f)
            for (radius in listOf(outline.roundRect.topLeftCornerRadius, outline.roundRect.topRightCornerRadius,
                outline.roundRect.bottomLeftCornerRadius, outline.roundRect.bottomRightCornerRadius)) {
                assertEquals(size.height / 2f, radius.x, 0f)
                assertEquals(size.height / 2f, radius.y, 0f)
            }
        }
    }

    @Test
    fun `shared catalog appearance pins native Material theme and reset follows the system again`() {
        for (systemDark in listOf(false, true)) {
            assertFalse(LavaDecorationPolicy.darkAppearance("light", systemDark))
            assertTrue(LavaDecorationPolicy.darkAppearance("dark", systemDark))
            assertEquals(systemDark, LavaDecorationPolicy.darkAppearance("", systemDark))
            assertEquals(systemDark, LavaDecorationPolicy.darkAppearance("unknown", systemDark))
        }
    }

    @Test
    fun `locked portrait and brand contour do not become a sleeping mascot or fallback icon`() {
        assertEquals(LavaDecorationPolicy.Drawing.BRAND, LavaDecorationPolicy.drawing("lava.shield.fill", "awake"))
        assertEquals(LavaDecorationPolicy.Drawing.RANKING, LavaDecorationPolicy.drawing("lava.ranking", "awake"))
        assertEquals(LavaDecorationPolicy.Drawing.LOCKED, LavaDecorationPolicy.drawing("", "locked"))
        assertEquals(LavaDecorationPolicy.Drawing.GUARDIAN, LavaDecorationPolicy.drawing("", "awake"))
        assertEquals(LavaDecorationPolicy.Drawing.SYMBOL, LavaDecorationPolicy.drawing("shield.fill", "locked"))
    }

    @Test
    fun `reveal animates only between two visible active measured frames`() {
        val live = LavaDecorationPolicy.Reveal(true, true, true)
        assertTrue(LavaDecorationPolicy.animateReveal(live, live, false))
        assertEquals(600, LavaDecorationPolicy.REVEAL_DURATION_MILLIS)
        assertFalse(LavaDecorationPolicy.animateReveal(live, live, true))
        for (retired in listOf(live.copy(enabled = false), live.copy(visible = false), live.copy(active = false))) {
            assertFalse(LavaDecorationPolicy.animateReveal(retired, live, false))
            assertFalse(LavaDecorationPolicy.animateReveal(live, retired, false))
        }
    }

    @Test
    fun `semantic symbols retain distinct action meanings and explicit fill`() {
        assertTrue(MaterialSymbolGlyphs.glyph("arrow.clockwise.icloud") != MaterialSymbolGlyphs.glyph("arrow.up.right"))
        assertTrue(MaterialSymbolGlyphs.glyph("square.and.arrow.down") != MaterialSymbolGlyphs.glyph("square.and.arrow.up"))
        assertEquals("MaterialSymbolsRounded350", MaterialSymbolGlyphs.font("checkmark"))
        assertEquals("MaterialSymbolsRounded350Filled", MaterialSymbolGlyphs.font("checkmark.circle.fill"))
    }

    @Test
    fun `mood maps onto the shared mascot state graph case-insensitively`() {
        assertEquals(GuardianMascotState.AWAKE, LavaDecorationPolicy.moodState("awake"))
        assertEquals(GuardianMascotState.GRATEFUL, LavaDecorationPolicy.moodState("GRATEFUL"))
        assertEquals(GuardianMascotState.PAUSED, LavaDecorationPolicy.moodState("paused"))
        assertEquals(GuardianMascotState.CONCERNED, LavaDecorationPolicy.moodState("concerned"))
        assertEquals(GuardianMascotState.RETRYING, LavaDecorationPolicy.moodState("retrying"))
        assertEquals(GuardianMascotState.WAKING, LavaDecorationPolicy.moodState("waking"))
    }

    @Test
    fun `an unknown or absent mood sleeps rather than inventing a live pose`() {
        assertEquals(GuardianMascotState.SLEEPING, LavaDecorationPolicy.moodState(null))
        assertEquals(GuardianMascotState.SLEEPING, LavaDecorationPolicy.moodState(""))
        assertEquals(GuardianMascotState.SLEEPING, LavaDecorationPolicy.moodState("excited"))
    }

    @Test
    fun `look resolves the canonical persisted wire values`() {
        assertEquals(GuardianLook.ORIGINAL, LavaDecorationPolicy.look("original"))
        assertEquals(GuardianLook.FIRE_OPAL, LavaDecorationPolicy.look("emberObsidian"))
        assertEquals(GuardianLook.PURPLE_OBSIDIAN, LavaDecorationPolicy.look("purpleObsidian"))
        assertEquals(GuardianLook.OBSIDIAN, LavaDecorationPolicy.look("obsidian"))
        assertEquals(GuardianLook.CHERRY_QUARTZ, LavaDecorationPolicy.look("strawberryObsidian"))
        assertEquals(GuardianLook.EMERALD, LavaDecorationPolicy.look("emerald"))
        assertEquals(GuardianLook.KIWI_CREME, LavaDecorationPolicy.look("kiwiCreme"))
        assertEquals(GuardianLook.AQUAMARINE, LavaDecorationPolicy.look("aquamarine"))
    }

    @Test
    fun `an unknown or absent look preserves the original`() {
        assertEquals(GuardianLook.ORIGINAL, LavaDecorationPolicy.look(null))
        assertEquals(GuardianLook.ORIGINAL, LavaDecorationPolicy.look(""))
        assertEquals(GuardianLook.ORIGINAL, LavaDecorationPolicy.look("sepia"))
    }

    @Test
    fun `every canonical look is drawable on Android`() {
        // The iOS faceted artwork (ObsidianShieldBody/LavaGuardianShellFacet) is
        // ported, so the leaf no longer refuses any canonical shield style — every
        // GuardianShieldStyle wire value resolves to a renderable look.
        val canonicalWireValues = GuardianLook.entries.map { it.wire }
        assertEquals(8, canonicalWireValues.size)
        canonicalWireValues.forEach { wire ->
            assertEquals(wire, LavaDecorationPolicy.look(wire).wire)
        }
    }
}
