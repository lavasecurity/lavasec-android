package com.lavasec.app.foundation.filter

import com.lavasec.app.foundation.share.ShareableSourceUrlRejection
import com.lavasec.app.foundation.subscription.FeatureLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomBlocklistSourceTest {
    private val plus = FeatureLimits.PLUS

    private fun created(result: CustomBlocklistCreateResult): CustomBlocklistSource {
        assertTrue("expected Created but was $result", result is CustomBlocklistCreateResult.Created)
        return (result as CustomBlocklistCreateResult.Created).source
    }

    private fun rejectedCreate(result: CustomBlocklistCreateResult): ShareableSourceUrlRejection {
        assertTrue("expected Rejected but was $result", result is CustomBlocklistCreateResult.Rejected)
        return (result as CustomBlocklistCreateResult.Rejected).reason
    }

    private fun applied(result: CustomBlocklistEditResult): CustomBlocklistEditResult.Applied {
        assertTrue("expected Applied but was $result", result is CustomBlocklistEditResult.Applied)
        return result as CustomBlocklistEditResult.Applied
    }

    private fun rejectedEdit(result: CustomBlocklistEditResult): CustomBlocklistRejection {
        assertTrue("expected Rejected but was $result", result is CustomBlocklistEditResult.Rejected)
        return (result as CustomBlocklistEditResult.Rejected).reason
    }

    @Test
    fun `create trims the name and falls back to the host`() {
        val named = created(
            CustomBlocklistSource.create(displayName = "  My list  ", rawUrl = "https://example.com/list.txt"),
        )
        assertEquals("My list", named.displayName)
        assertEquals("https://example.com/list.txt", named.sourceUrl)

        val unnamed = created(
            CustomBlocklistSource.create(displayName = "   ", rawUrl = "https://lists.example.org/hosts"),
        )
        assertEquals("lists.example.org", unnamed.displayName)
    }

    @Test
    fun `create maps every URL rejection one to one`() {
        val cases = mapOf(
            "" to ShareableSourceUrlRejection.INVALID_URL,
            "http://example.com/list.txt" to ShareableSourceUrlRejection.UNSUPPORTED_SCHEME,
            "https:///list.txt" to ShareableSourceUrlRejection.MISSING_HOST,
            "https://127.0.0.1/list.txt" to ShareableSourceUrlRejection.PRIVATE_NETWORK_HOST,
            "https://localhost/list.txt" to ShareableSourceUrlRejection.PRIVATE_NETWORK_HOST,
            "https://user:pass@example.com/list.txt" to ShareableSourceUrlRejection.CREDENTIALS_NOT_ALLOWED,
            "not a url" to ShareableSourceUrlRejection.INVALID_URL,
        )
        cases.forEach { (raw, expected) ->
            val reason = rejectedCreate(CustomBlocklistSource.create(displayName = "list", rawUrl = raw))
            assertEquals("wrong reason for '$raw'", expected, reason)
        }
    }

    @Test
    fun `create accepts a public https url`() {
        val result = CustomBlocklistSource.create(displayName = "ok", rawUrl = "https://example.com/list.txt")
        assertTrue(result is CustomBlocklistCreateResult.Created)
    }

    @Test
    fun `add enables the source and rejects duplicate url`() {
        val added = applied(CustomBlocklistEditor.add(sampleProfile(), "List", "https://example.com/a.txt", limits = plus))
        assertTrue(added.source.id in added.profile.enabledSourceIds)
        assertEquals(listOf(added.source.id), added.profile.customBlocklists.map { it.id })

        val duplicate = rejectedEdit(
            CustomBlocklistEditor.add(added.profile, "Other", "https://example.com/a.txt", limits = plus),
        )
        assertEquals(CustomBlocklistRejection.DUPLICATE_URL, duplicate)
    }

    @Test
    fun `add rejects a duplicate display name`() {
        val first = applied(CustomBlocklistEditor.add(sampleProfile(), "Shared name", "https://example.com/a.txt", limits = plus))
        val second = rejectedEdit(
            CustomBlocklistEditor.add(first.profile, " shared name ", "https://example.com/b.txt", limits = plus),
        )
        assertEquals(CustomBlocklistRejection.DUPLICATE_NAME, second)
    }

    @Test
    fun `add is refused without plus`() {
        val result = rejectedEdit(
            CustomBlocklistEditor.add(sampleProfile(), "List", "https://example.com/a.txt", limits = FeatureLimits.FREE),
        )
        assertEquals(CustomBlocklistRejection.REQUIRES_PLUS, result)
    }

    @Test
    fun `add collapses a known catalog url and drops the duplicate custom record`() {
        val custom = applied(
            CustomBlocklistEditor.add(
                sampleProfile(), "Old", "https://catalog.example.com/list.txt",
                limits = plus, catalogSourceIdForUrl = { null },
            ),
        )
        val collapsed = applied(
            CustomBlocklistEditor.add(
                custom.profile, "New", "https://catalog.example.com/list.txt", limits = plus,
                catalogSourceIdForUrl = { url -> if (url == "https://catalog.example.com/list.txt") "catalog-source" else null },
            ),
        )
        assertEquals("catalog-source", collapsed.collapsedToCatalogSourceId)
        assertTrue("catalog-source" in collapsed.profile.enabledSourceIds)
        assertTrue(collapsed.profile.customBlocklists.isEmpty())
    }

    @Test
    fun `delete removes the source and disables it`() {
        val added = applied(CustomBlocklistEditor.add(sampleProfile(), "List", "https://example.com/a.txt", limits = plus))
        val id = added.source.id
        val deleted = CustomBlocklistEditor.delete(added.profile, id)
        assertTrue(deleted.customBlocklists.isEmpty())
        assertTrue(id !in deleted.enabledSourceIds)
    }

    @Test
    fun `undo restores the baseline definition and membership`() {
        val baseline = sampleProfile()
            .withCustomBlocklists(listOf(CustomBlocklistSource("custom-a", "A", "https://a.example.com")))
            .withEnabledSourceIds(setOf("custom-a"))
        val deleted = CustomBlocklistEditor.delete(baseline, "custom-a")
        assertTrue(deleted.customBlocklists.isEmpty())
        val restored = CustomBlocklistEditor.undo(deleted, "custom-a", baseline)
        assertEquals(1, restored.customBlocklists.size)
        assertTrue("custom-a" in restored.enabledSourceIds)
    }

    @Test
    fun `profile validation rejects duplicate custom ids`() {
        val duplicate = runCatching {
            FilterProfileDraft(
                id = "p",
                name = "P",
                protectionLevel = null,
                enabledSourceIds = emptySet(),
                customBlocklists = listOf(
                    CustomBlocklistSource("custom-a", "A", "https://a.example.com"),
                    CustomBlocklistSource("custom-a", "B", "https://b.example.com"),
                ),
            )
        }
        assertTrue(duplicate.isFailure)
    }

    private fun sampleProfile(): FilterProfileDraft = FilterProfileDraft(
        id = "p",
        name = "P",
        protectionLevel = null,
        enabledSourceIds = emptySet(),
    )
}
