package com.lavasec.app.foundation.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the canonical `FilterIdentityPolicy` port against the pinned iOS source. The vectors
 * mirror the Swift implementation's rules: NFC spelling, Unicode whitespace trimming, the
 * base/combining name grammar, and the unchanged-legacy-name edit exception.
 */
class FilterIdentityPolicyTest {
    @Test
    fun `normalized name composes canonically and trims only surrounding whitespace`() {
        assertEquals("é", FilterIdentityPolicy.normalizedName("e\u0301"))
        assertEquals("café", FilterIdentityPolicy.normalizedName("cafe\u0301"))
        assertEquals("My Filter", FilterIdentityPolicy.normalizedName("  \t My Filter \n"))
        assertEquals("a  b", FilterIdentityPolicy.normalizedName("a  b"))
        assertEquals("", FilterIdentityPolicy.normalizedName("\u00a0\u2028"))
    }

    @Test
    fun `valid names accept letters digits single spaces and trailing combining marks`() {
        assertTrue(FilterIdentityPolicy.isValidName("Core"))
        assertTrue(FilterIdentityPolicy.isValidName("My Filter 2"))
        assertTrue(FilterIdentityPolicy.isValidName("café"))
        assertTrue(FilterIdentityPolicy.isValidName("cafe\u0301"))
        assertTrue(FilterIdentityPolicy.isValidName("日本語"))
        assertTrue(FilterIdentityPolicy.isValidName("123"))
        assertTrue(FilterIdentityPolicy.isValidName("a1 b2"))
    }

    @Test
    fun `invalid names reject blanks controls punctuation leading marks and emoji`() {
        assertFalse(FilterIdentityPolicy.isValidName(""))
        assertFalse(FilterIdentityPolicy.isValidName("   "))
        assertFalse(FilterIdentityPolicy.isValidName("a\u0001b"))
        assertFalse(FilterIdentityPolicy.isValidName("\u0301a"))
        assertFalse(FilterIdentityPolicy.isValidName(" \u0301"))
        assertFalse(FilterIdentityPolicy.isValidName("a-b"))
        assertFalse(FilterIdentityPolicy.isValidName("a_b"))
        assertFalse(FilterIdentityPolicy.isValidName("a\u200Db"))
        assertFalse(FilterIdentityPolicy.isValidName("🌱"))
    }

    @Test
    fun `name for edit retains an unchanged legacy name even when today's grammar rejects it`() {
        assertEquals(
            "My-Filter",
            FilterIdentityPolicy.nameForEdit("My-Filter", savedName = "My-Filter"),
        )
        assertEquals(
            "café",
            FilterIdentityPolicy.nameForEdit("cafe\u0301", savedName = "café"),
        )
    }

    @Test
    fun `name for edit normalizes a real rename and refuses an invalid one`() {
        assertEquals("New", FilterIdentityPolicy.nameForEdit("  New  ", savedName = "Old"))
        assertEquals("café", FilterIdentityPolicy.nameForEdit("cafe\u0301", savedName = "Old"))
        assertNull(FilterIdentityPolicy.nameForEdit("bad-name", savedName = "Old"))
        assertNull(FilterIdentityPolicy.nameForEdit("\u0001", savedName = "Old"))
    }

    @Test
    fun `emoji validation accepts flags keycaps joined families and modifiers`() {
        assertTrue(FilterIdentityPolicy.isValidEmoji("🇺🇸"))
        assertTrue(FilterIdentityPolicy.isValidEmoji("1️⃣"))
        assertTrue(FilterIdentityPolicy.isValidEmoji("1⃣"))
        assertTrue(FilterIdentityPolicy.isValidEmoji("👨‍👩‍👧"))
        assertTrue(FilterIdentityPolicy.isValidEmoji("👍🏽"))
        assertTrue(FilterIdentityPolicy.isValidEmoji("❤️"))
    }

    @Test
    fun `emoji validation rejects text modifiers fragments and multi-grapheme input`() {
        assertFalse(FilterIdentityPolicy.isValidEmoji(""))
        assertFalse(FilterIdentityPolicy.isValidEmoji("1"))
        assertFalse(FilterIdentityPolicy.isValidEmoji("A"))
        assertFalse(FilterIdentityPolicy.isValidEmoji("abc"))
        assertFalse(FilterIdentityPolicy.isValidEmoji("🏽"))
        assertFalse(FilterIdentityPolicy.isValidEmoji("❤"))
        assertFalse(FilterIdentityPolicy.isValidEmoji("🌱🌿"))
    }

    @Test
    fun `default emoji is stable deterministic and name aware`() {
        assertEquals("🌱", FilterIdentityPolicy.defaultEmoji(id = "filter-essential", name = "Core"))
        assertEquals("🪴", FilterIdentityPolicy.defaultEmoji(id = "filter-balanced", name = "BALANCED"))
        assertEquals("💐", FilterIdentityPolicy.defaultEmoji(id = "filter-extra", name = "extra"))
        val first = FilterIdentityPolicy.defaultEmoji(id = "filter-7f3a", name = "Custom")
        assertEquals(first, FilterIdentityPolicy.defaultEmoji(id = "filter-7f3a", name = "Custom"))
        assertTrue(FilterIdentityPolicy.isValidEmoji(first))
    }

    @Test
    fun `display name prefixes only a valid emoji`() {
        assertEquals("🌱 Core", FilterIdentityPolicy.displayName("Core", "🌱"))
        assertEquals("Core", FilterIdentityPolicy.displayName("Core", "not-an-emoji"))
        assertEquals("Core", FilterIdentityPolicy.displayName("Core", ""))
    }

    @Test
    fun `canonical equality matches Swift string equality`() {
        assertTrue(FilterIdentityPolicy.canonicallyEqual("café", "cafe\u0301"))
        assertTrue(FilterIdentityPolicy.canonicallyEqual("same", "same"))
        assertFalse(FilterIdentityPolicy.canonicallyEqual("a", "b"))
        assertFalse(FilterIdentityPolicy.canonicallyEqual("a", "a "))
    }
}
