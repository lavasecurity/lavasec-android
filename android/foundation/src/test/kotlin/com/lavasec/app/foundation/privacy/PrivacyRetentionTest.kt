package com.lavasec.app.foundation.privacy

import com.lavasec.app.foundation.preferences.PreferenceValue
import com.lavasec.app.foundation.preferences.ProductPreferenceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the canonical privacy retention model: independent clear
 * categories, immediate stop on disable, conservative defaults, and the rule that
 * no category is enrolled without an explicit choice.
 */
class PrivacyRetentionTest {
    private val counts = PrivacyRetentionCategory.FILTERING_COUNTS
    private val history = PrivacyRetentionCategory.DOMAIN_HISTORY
    private val network = PrivacyRetentionCategory.NETWORK_ACTIVITY
    private val guard = PrivacyRetentionCategory.GUARD_PROGRESS

    private fun canonicalWithData(): PrivacyRetention {
        var model = PrivacyRetention.canonicalDefaults()
        PrivacyRetentionCategory.entries.forEach { category ->
            model = checkNotNull(model.record(category))
        }
        return model
    }

    @Test
    fun `canonical defaults match the retained-preference catalog`() {
        val model = PrivacyRetention.canonicalDefaults()

        PrivacyRetentionCategory.entries.forEach { category ->
            assertEquals(
                (category.preferenceKey.canonicalDefault as PreferenceValue.Bool).value,
                model.isCollecting(category),
            )
        }
        assertTrue(model.retainedCategories.isEmpty())
    }

    @Test
    fun `conservative defaults enroll nothing`() {
        val model = PrivacyRetention.conservativeDefaults()

        assertTrue(model.collectingCategories.isEmpty())
        PrivacyRetentionCategory.entries.forEach { category ->
            assertEquals(RetentionChoice.DO_NOT_COLLECT, model.choice(category))
            assertFalse(model.isCollecting(category))
        }
    }

    @Test
    fun `a category is never enabled without an explicit true`() {
        val model = PrivacyRetention.fromPersisted(
            mapOf(
                counts to true,
                history to false,
                // network is absent entirely
                // guard is absent entirely
            ),
        )

        assertTrue(model.isCollecting(counts))
        assertFalse(model.isCollecting(history))
        assertFalse(model.isCollecting(network))
        assertFalse(model.isCollecting(guard))
        assertEquals(setOf(counts), model.collectingCategories)

        val allAbsent = PrivacyRetention.fromPersisted(emptyMap())
        assertTrue(allAbsent.collectingCategories.isEmpty())
    }

    @Test
    fun `clearing one category leaves every other category intact`() {
        val before = canonicalWithData()

        val after = before.clear(PrivacyClearTarget.Category(counts))

        assertFalse(after.hasRetainedData(counts))
        assertTrue(after.hasRetainedData(history))
        assertTrue(after.hasRetainedData(network))
        assertTrue(after.hasRetainedData(guard))
        assertEquals(before.toPersistedMap(), after.toPersistedMap())
    }

    @Test
    fun `clear targets exactly the selected category and never a sibling`() {
        val before = canonicalWithData()

        PrivacyRetentionCategory.entries.forEach { target ->
            val after = before.clear(PrivacyClearTarget.Category(target))
            val expectedCleared = PrivacyRetentionCategory.entries.filter { it != target }.toSet()

            assertFalse("$target must be cleared", after.hasRetainedData(target))
            assertEquals(
                "only $target may be cleared",
                expectedCleared,
                after.retainedCategories,
            )
        }
    }

    @Test
    fun `clear all clears every category`() {
        val after = canonicalWithData().clear(PrivacyClearTarget.All)

        assertTrue(after.retainedCategories.isEmpty())
    }

    @Test
    fun `disabling stops collection immediately`() {
        val model = PrivacyRetention.canonicalDefaults()

        val disabled = model.disable(network, clear = false)

        assertFalse(disabled.isCollecting(network))
        assertNull("a disabled category must refuse a new write", disabled.record(network))
    }

    @Test
    fun `disable with clear removes only that category's retained data`() {
        val before = canonicalWithData()

        val after = before.disable(network)

        assertFalse(after.isCollecting(network))
        assertFalse(after.hasRetainedData(network))
        assertTrue(after.hasRetainedData(counts))
        assertTrue(after.hasRetainedData(history))
        assertTrue(after.hasRetainedData(guard))
    }

    @Test
    fun `disable without clear keeps the retained data`() {
        val before = canonicalWithData()

        val after = before.disable(network, clear = false)

        assertFalse(after.isCollecting(network))
        assertTrue(after.hasRetainedData(network))
    }

    @Test
    fun `disabling never resets a sibling to a permissive default`() {
        val before = PrivacyRetention.fromPersisted(
            mapOf(
                counts to true,
                history to false,
                network to true,
                guard to false,
            ),
        )

        val after = before.disable(network)

        assertEquals(RetentionChoice.COLLECT, after.choice(counts))
        assertEquals(RetentionChoice.DO_NOT_COLLECT, after.choice(history))
        assertEquals(RetentionChoice.DO_NOT_COLLECT, after.choice(network))
        assertEquals(RetentionChoice.DO_NOT_COLLECT, after.choice(guard))
        assertEquals(
            "disable must not re-enroll an opted-out sibling",
            setOf(counts),
            after.collectingCategories,
        )
    }

    @Test
    fun `an explicit enable is the only path back to collection`() {
        val optedOut = PrivacyRetention.conservativeDefaults()
        assertNull(optedOut.record(counts))

        val enabled = optedOut.withChoice(counts, RetentionChoice.COLLECT)
        assertNotNull(enabled.record(counts))
    }

    @Test
    fun `wire keys match the canonical retained-preference keys`() {
        assertEquals("logs.Filtering Counts", counts.wire)
        assertEquals("logs.Domain logs", history.wire)
        assertEquals("logs.Network activity", network.wire)
        assertEquals("logs.Lava Guard Progress", guard.wire)

        assertEquals(ProductPreferenceKey.LOGS_FILTERING_COUNTS, counts.preferenceKey)
        assertEquals(ProductPreferenceKey.LOGS_DOMAINS, history.preferenceKey)
        assertEquals(ProductPreferenceKey.LOGS_NETWORK_ACTIVITY, network.preferenceKey)
        assertEquals(ProductPreferenceKey.LOGS_LAVA_GUARD_PROGRESS, guard.preferenceKey)
    }

    @Test
    fun `clear all target enumerates every category`() {
        assertEquals(
            PrivacyRetentionCategory.entries.toSet(),
            PrivacyClearTarget.All.categories,
        )
        assertEquals(setOf(network), PrivacyClearTarget.Category(network).categories)
    }
}
