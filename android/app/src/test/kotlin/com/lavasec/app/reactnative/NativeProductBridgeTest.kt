package com.lavasec.app.reactnative

import com.lavasec.app.foundation.preferences.PreferenceValue
import com.lavasec.app.foundation.flows.NativeFlowBuildConfiguration
import com.lavasec.app.foundation.resolver.CatalogResolverTransport
import com.lavasec.app.foundation.resolver.ResolverTierChoice
import com.lavasec.app.foundation.security.ProtectedSurface
import com.lavasec.app.foundation.sudoku.SudokuGame
import com.lavasec.app.platform.ChainedConfigurationToggleKey
import com.lavasec.app.foundation.sudoku.SudokuPuzzle
import com.lavasec.app.reactnative.components.LavaNativeFlowHost
import com.lavasec.app.reactnative.components.LavaNativeFlowLaunchResult
import com.lavasec.app.reactnative.components.LavaNativeFlowLauncher
import com.lavasec.app.reactnative.components.LavaNativeFlowRequest
import java.io.File
import java.util.Locale
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Strict wire validation for the canonical filter/library commands. Deliberately a plain JUnit
 * test (no Robolectric, no `org.json`): the parser is the security-relevant gate — an unknown or
 * malformed command must be a typed rejection, never a fabricated success — and it must be
 * executable on the aarch64 work host, where the Robolectric native runtime is unavailable.
 */
class NativeProductBridgeTest {
    @Test fun `domain checker validates visit and action shapes without accepting arbitrary mutations`() {
        val visit = "884db449-818d-452f-9a4c-f745e969f087"
        val query = parse("domainCheck.query", "visit" to visit, "input" to "https://Example.com/path", "refresh" to true)
        assertEquals(ProductDomainCheckCommand.Query(visit, "https://Example.com/path", true), (query as ProductCommandParse.Supported).command)
        for (action in com.lavasec.app.foundation.filter.DomainCheckRuleAction.entries) {
            val stage = parse("domainCheck.stage", "visit" to visit, "proof" to "native-proof", "decision" to action.wire)
            assertEquals(ProductDomainCheckCommand.Stage(visit, "native-proof", action), (stage as ProductCommandParse.Supported).command)
            assertFalse(stage.command.commitsDurableConfiguration())
        }
        assertEquals(ProductDomainCheckCommand.Cancel(visit), (parse("domainCheck.cancel", "visit" to visit) as ProductCommandParse.Supported).command)
        for (bad in listOf("", "not-a-visit", "a".repeat(36)))
            assertEquals(ProductCommandReasons.INVALID_ARGUMENT, invalid("domainCheck.query", "visit" to bad, "input" to "example.com"))
        assertEquals(ProductCommandReasons.INVALID_ARGUMENT, invalid("domainCheck.stage", "visit" to visit, "proof" to "", "decision" to "allowed"))
        assertEquals(ProductCommandReasons.INVALID_ARGUMENT, invalid("domainCheck.stage", "visit" to visit, "proof" to "proof", "decision" to "deleteFilters"))
        assertEquals(ProductCommandReasons.INVALID_ARGUMENT, invalid("domainCheck.query", "visit" to visit, "input" to "example.com", "refresh" to "true"))
    }
    @Test fun `native locale projection respects explicit Chinese script before region`() {
        listOf(
            "zh-Hans-HK" to "zh-Hans",
            "zh-Hans-TW" to "zh-Hans",
            "zh-Hant-CN" to "zh-Hant",
            "ZH-hant-mo" to "zh-Hant",
            "zh-MO" to "zh-Hant",
            "zh-HK" to "zh-Hant",
            "zh-TW" to "zh-Hant",
            "zh-CN" to "zh-Hans",
        ).forEach { (tag, expected) ->
            assertEquals(tag, expected, nativePresentationLocale(Locale.forLanguageTag(tag)))
        }
    }

    @Test fun `native locale projection preserves Portuguese wire shape and regional base languages`() {
        listOf(
            "pt" to "pt",
            "pt-PT" to "pt",
            "pt-BR" to "pt-BR",
            "fr-CA" to "fr",
            "de-DE" to "de",
            "en-GB" to "en",
            "pl-PL" to "pl",
        ).forEach { (tag, expected) ->
            assertEquals(tag, expected, nativePresentationLocale(Locale.forLanguageTag(tag)))
        }
    }

    @Test fun `every manual domain refusal supplies actionable display copy`() {
        val messages = com.lavasec.app.foundation.filter.ManualRuleRejection.entries.map {
            manualRuleRejectionMessage(it).also { message -> assertTrue(message.length > 15) }
        }
        assertEquals(messages.size, messages.toSet().size)
    }

    @Test fun `every custom blocklist fetch failure maps to a distinct stable wire code`() {
        val codes = com.lavasec.app.foundation.filter.CustomBlocklistSourceFailure.entries.map {
            customBlocklistSourceFailureWireCode(it)
        }
        assertEquals(
            listOf(
                "custom-blocklist-fetch-network",
                "custom-blocklist-fetch-http",
                "custom-blocklist-fetch-empty",
                "custom-blocklist-fetch-too-large",
                "custom-blocklist-fetch-timeout",
                "custom-blocklist-fetch-insecure",
                "custom-blocklist-fetch-redirect",
                "custom-blocklist-fetch-storage",
                "custom-blocklist-parse-failed",
            ),
            codes,
        )
        assertTrue(codes.none { "https://" in it || "example.com" in it })
    }

    @Test fun `vpn begin only takes the pending import marker when it imports the first rotation`() {
        val source = File("src/main/kotlin/com/lavasec/app/reactnative/NativeProductBridge.kt")
        assertTrue("bridge source missing at ${source.absolutePath}", source.isFile)
        val text = source.readText()
        val begin = text.substring(
            text.indexOf("is ProductVpnCommand.Begin ->"),
            text.indexOf("firstConfigurationResult(outcome)"),
        )
        // Native host custody registers before the picker can suspend and retains the exact
        // imported pending capability; ordinary Begin must never erase another import.
        assertTrue(begin.contains("val outcome = drafts.begin("))
        assertTrue(begin.contains("module = this, host = host, generation = generation, composition = composition,"))
        assertTrue(begin.contains("owner = owner, repository = composition.configurationRepository, id = command.id,"))
        assertTrue(begin.contains("expectedGeneration = expected, graphCurrent = graphCurrent,"))
        assertTrue(begin.contains("hostCurrent = { !vpnDraftModuleRetired.get() && host.matches(currentVisitHost()) && activity.foreground }"))
        assertTrue(begin.contains("recover = { recoverPendingFirstVpnImport(composition) }"))
        assertTrue(begin.contains("}.beginOrImport(command.id, expected)"))
        assertFalse("an ordinary begin must retain the pending marker", begin.contains("else null"))

        val ownerSource = File("src/main/kotlin/com/lavasec/app/reactnative/NativeVpnDraftHostOwner.kt")
        assertTrue("native parent custody source missing at ${ownerSource.absolutePath}", ownerSource.isFile)
        val custody = ownerSource.readText()
        val custodyStart = custody.indexOf("suspend fun begin(")
        val custodyEnd = custody.indexOf("fun hasImport(", custodyStart)
        assertTrue("native Begin custody region is present", custodyStart >= 0 && custodyEnd > custodyStart)
        val ownedBegin = custody.substring(custodyStart, custodyEnd)
        val registration = ownedBegin.indexOf("lane.visits.add(it)")
        val operation = ownedBegin.indexOf("outcome = operation()")
        assertTrue("register exact native visit before picker/storage suspension", registration in 0 until operation)
        assertTrue(ownedBegin.contains("pending = expectedGeneration == 0L"))
        assertTrue(ownedBegin.contains("visit.configurationGeneration = draft.draft.generation"))
        assertTrue(ownedBegin.contains("visit.pending = visit.pending || draft.imported"))
        assertTrue("an ordinary draft must retain other pending visits",
            ownedBegin.contains("if (!visit.pending) lane.visits.removeAll { it !== visit && !it.pending }"))
        assertTrue("pending custody captures the actual repository receipt", ownedBegin.contains("captureIdentity(visit)"))
        assertTrue("repository receipt must belong to this original editor", custody.contains("stored?.pendingImportId == visit.id"))
        assertTrue("repository receipt must retain the original draft generation",
            custody.contains("stored.identity.generation == visit.configurationGeneration"))
    }

    @Test fun `the restore flow re-authorizes after each native dialog and before publication`() {
        val source = File("src/main/kotlin/com/lavasec/app/reactnative/NativeProductBridge.kt")
        assertTrue("bridge source missing at ${source.absolutePath}", source.isFile)
        val text = source.readText()
        val start = text.indexOf("private suspend fun executeBackupRestoreFlow")
        assertTrue("restore flow not found", start >= 0)
        val end = text.indexOf("private fun backupRestoreRefusalReason", start)
        val flow = text.substring(start, end)

        // The phrase dialog pauses the activity and resets the App Settings grant, so the grant must
        // be re-checked after it returns and before anything is downloaded/decrypted.
        val phraseDialog = flow.indexOf("presentBackupRestorePhrase()")
        val unlock = flow.indexOf("backupRestoreUnlockSource.unlock", phraseDialog)
        val afterPhrase = flow.indexOf("authorizeCommand", phraseDialog)
        assertTrue("re-authorize after the phrase dialog", afterPhrase in phraseDialog until unlock)

        // The review dialog does the same, and the publish edge is the last chance to reject a stale
        // grant before `confirm` mutates the live library and logging settings.
        val reviewDialog = flow.indexOf("presentBackupRestoreReview(", phraseDialog)
        val confirm = flow.indexOf("backupRestoreOwner.confirm(", reviewDialog)
        val afterReview = flow.indexOf("authorizeCommand", reviewDialog)
        assertTrue("re-authorize after the review dialog", afterReview in reviewDialog until confirm)
    }

    @Test fun `every changed filter draft reaches Review before any durable mutation`() {
        val source = File("src/main/kotlin/com/lavasec/app/reactnative/NativeProductBridge.kt")
        assertTrue("bridge source missing at ${source.absolutePath}", source.isFile)
        val text = source.readText()
        val start = text.indexOf("private suspend fun executeFilterSave")
        assertTrue("executeFilterSave not found", start >= 0)
        val end = text.indexOf("private fun untouchedNewFilterTemplate", start)
        assertTrue("untouched-new-template helper not found", end > start)
        val save = text.substring(start, end)

        // The protection-weakening derivation no longer decides the boundary: every changed draft
        // returns Review, and the only direct commit left is the untouched creation template.
        assertFalse("filter.save must not derive the boundary from the weakening diff", save.contains("reviewedFilterWeakensProtection"))
        val reviewReturn = save.indexOf("return result(\"completed\", value = SAVE_REVIEW)")
        val untouched = save.indexOf("untouchedNewFilterTemplate(command.id, before)")
        val directCommit = save.indexOf("product.commitFilterReview")
        assertTrue("requestFilterReview must precede the boundary", save.indexOf("product.requestFilterReview") in 0 until reviewReturn)
        assertTrue("the untouched-template guard must precede the changed return", untouched in 0 until reviewReturn)
        assertTrue("the direct commit must be guarded by the untouched-template check", directCommit in untouched until reviewReturn)
        // Exact-review binding stays with the owner: confirm revalidates the reviewed draft/basis.
        assertTrue(save.contains("return result(\"completed\", value = SAVE_SAVED)"))

        val helper = text.substring(end, text.indexOf("/** Stable wire codes for a custom-blocklist editor refusal", end))
        assertTrue(helper.contains("product.reviewedFilterIsUntouchedCreation(filterId, generation)"))
        assertFalse("A projected frame can lag the completed native domain mutation", helper.contains("before.filterDetail"))
    }

    @Test fun `a standalone domain review cancel is token and generation bound and idempotent`() {
        val source = File("src/main/kotlin/com/lavasec/app/reactnative/NativeProductBridge.kt")
        assertTrue("bridge source missing at ${source.absolutePath}", source.isFile)
        val text = source.readText()
        assertTrue(text.contains("token != null && token == command.token"))
        assertTrue("a rotation must not discard the replacement graph's draft", text.contains("capturedGeneration == generation"))
        assertTrue("same-ID successor drafts require the captured visit lease", text.contains("capturedLease != null"))
        assertTrue(text.contains("standaloneDomainReviews.retire(record.visit)"))
        assertTrue(text.contains("product.retireStandaloneDomainReview(record.witness).await()"))
        assertTrue(text.contains("standaloneDomainReviews.retireHost(activity, activity.nativeHostID)"))
        assertTrue(text.contains("standaloneDomainReviews.close()"))
        assertTrue(text.contains("standaloneReviewRecord?.takeIf { it.witness.filterId == command.id }"))
        assertFalse("legacy ID-only teardown must not survive", text.contains("product.discardFilterDraft(filterId)"))
        assertTrue("an unknown/absent token stays an idempotent no-op", text.contains("idempotent no-op"))
    }

    @Test fun `an app-locked domain cancel teardown is admitted while other commands stay refused`() {
        val source = File("src/main/kotlin/com/lavasec/app/reactnative/NativeProductBridge.kt")
        assertTrue("bridge source missing at ${source.absolutePath}", source.isFile)
        val text = source.readText()
        val lockedReturn = "return result(\"rejected\", \"app-locked\")"
        val gate = text.substring(
            text.indexOf("if (before.isLocked(ProtectedSurface.APP_UNLOCK"),
            text.indexOf(lockedReturn),
        )
        // Owned teardown is read-free. Shared Enter commands need to reach their explicit
        // native reauthorization below this gate so metadata can hydrate under the cover.
        // Every other user command, including writes, still receives app-locked.
        assertTrue(gate.contains("navigation.authorize"))
        assertTrue(gate.contains("\"appUnlock\""))
        val exemptionMarker = "request.optString(\"type\") !in setOf("
        assertTrue("the app-lock gate must retain a finite explicit exemption set", gate.contains(exemptionMarker))
        val exemptions = Regex("\"([^\"]+)\"").findAll(gate.substringAfter(exemptionMarker).substringBefore(")"))
            .map { it.groupValues[1] }.toSet()
        assertEquals(setOf("domains.cancel", "foreground.dismiss", "foreground.enter", "feedback.enter", "vpnEditor.enter"), exemptions)
        assertTrue(text.contains(lockedReturn))
        // The teardown handler keeps its exact token + generation fence, so a queued/stale cancel
        // never discards a newer owner's staged draft.
        val cancel = text.substring(
            text.indexOf("is ProductDomainCommand.Cancel ->"),
            text.indexOf("private suspend fun executeLogsCommand"),
        )
        assertTrue(cancel.contains("token != null && token == command.token"))
        assertTrue(cancel.contains("capturedGeneration == generation"))
        assertTrue(cancel.contains("capturedLease != null"))
        assertTrue(cancel.contains("standaloneDomainReviews.retire(record.visit)"))
        assertTrue(cancel.contains("product.retireStandaloneDomainReview(record.witness).await()"))

        // Enter never inherits only an exception: it rechecks exact owned IDs, authorizes
        // App Unlock plus the appropriate private family, then validates the original host.
        val entryStart = text.indexOf("val entryCommand = request.optString(\"type\")")
        val entryEnd = text.indexOf("if (request.optString(\"type\").startsWith(\"onboarding.\"))", entryStart)
        assertTrue(entryStart >= 0 && entryEnd > entryStart)
        val entry = text.substring(entryStart, entryEnd)
        assertTrue(entry.contains("id != sharedForeground.currentId || kind == null"))
        assertTrue(entry.contains("product.securityAuthorize(generation, ProtectedSurface.APP_UNLOCK"))
        assertTrue(entry.contains("surface != null && !product.securityAuthorize(generation, surface"))
        assertTrue(entry.contains("context.currentActivity !== activity || activity.isDestroyed || !activity.foreground"))
        assertTrue(entry.contains("product.state.value.graphGeneration != generation"))
    }

    @Test fun `a limit-reached domain stage projects the contextual Plus offer for blocked and allowed`() {
        val source = File("src/main/kotlin/com/lavasec/app/reactnative/NativeProductBridge.kt")
        assertTrue("bridge source missing at ${source.absolutePath}", source.isFile)
        val text = source.readText()
        val start = text.indexOf("is ProductDomainCommand.Stage ->")
        assertTrue("domain stage branch not found", start >= 0)
        val stage = text.substring(start, text.indexOf("is ProductDomainCommand.Cancel ->", start))
        // Both manual-rule capacity reasons are the only upsell triggers, and Plus removes it.
        assertTrue(stage.contains("ManualRuleRejection.ALLOWED_LIMIT_REACHED"))
        assertTrue(stage.contains("ManualRuleRejection.BLOCKED_LIMIT_REACHED"))
        assertTrue(stage.contains("limitReached = limitReached && !hasPlus"))
        // A staged review returns the fresh native token the shared Review echoes on filter.apply.
        assertTrue(stage.contains("standaloneReview"))
        assertTrue(stage.contains("standaloneReviewToken = token"))
    }

    private class MapInput(private val values: Map<String, Any?>) : ProductCommandInput {        override fun has(name: String): Boolean = values.containsKey(name) && values[name] != null
        override fun string(name: String): String? = values[name] as? String
        override fun boolean(name: String): Boolean? = values[name] as? Boolean
        override fun long(name: String): Long? {
            val value = values[name] as? Number ?: return null
            val integral = value.toLong()
            return integral.takeIf { value.toDouble() == integral.toDouble() }
        }
        override fun sudokuGame(name: String): SudokuGame? = values[name] as? SudokuGame
        @Suppress("UNCHECKED_CAST")
        override fun dnsTiers(name: String): List<ResolverTierChoice>? = values[name] as? List<ResolverTierChoice>
        override fun dnsChoice(name: String): ResolverTierChoice? = values[name] as? ResolverTierChoice
        override fun stringList(name: String): List<String>? {
            val value = values[name] as? List<*> ?: return null
            val result = ArrayList<String>(value.size)
            for (element in value) result += element as? String ?: return null
            return result
        }

        override fun preferenceValue(name: String): PreferenceValue? = values[name] as? PreferenceValue
    }

    private fun parse(type: String, vararg values: Pair<String, Any?>) =
        ProductCommandParser.parse(type, MapInput(mapOf(*values)))

    private fun supported(type: String, vararg values: Pair<String, Any?>): ProductFilterCommand {
        val result = parse(type, *values)
        assertTrue("expected Supported for $type but was $result", result is ProductCommandParse.Supported)
        return (result as ProductCommandParse.Supported).command as ProductFilterCommand
    }

    private fun invalid(type: String, vararg values: Pair<String, Any?>): String {
        val result = parse(type, *values)
        assertTrue("expected Invalid for $type but was $result", result is ProductCommandParse.Invalid)
        return (result as ProductCommandParse.Invalid).reason
    }

    @Test fun `every owner-backed filter command parses to its typed shape`() {
        assertEquals(ProductFilterCommand.Open("a"), supported("filter.open", "id" to "a"))
        assertEquals(ProductFilterCommand.Close("a"), supported("filter.close", "id" to "a"))
        assertEquals(ProductFilterCommand.Edit("a"), supported("filter.edit", "id" to "a"))
        assertEquals(ProductFilterCommand.Cancel("a"), supported("filter.cancel", "id" to "a"))
        assertEquals(ProductFilterCommand.Save("a"), supported("filter.save", "id" to "a"))
        assertEquals(ProductFilterCommand.Review("a"), supported("filter.review", "id" to "a"))
        assertEquals(ProductFilterCommand.Apply("a", "t"), supported("filter.apply", "id" to "a", "review" to "t"))
        assertEquals(
            ProductFilterCommand.Apply("a", "t", "s"),
            supported("filter.apply", "id" to "a", "review" to "t", "standaloneReview" to "s"),
        )
        assertEquals(ProductFilterCommand.Switch("a"), supported("filter.switch", "id" to "a"))
        assertEquals(ProductFilterCommand.Delete("a"), supported("filter.delete", "id" to "a"))
        assertEquals(ProductFilterCommand.Create("n", null), supported("filter.create", "name" to "n"))
        assertEquals(ProductFilterCommand.Create("n", "d"), supported("filter.create", "name" to "n", "duplicate" to "d"))
        assertEquals(ProductFilterCommand.Rename("a", "n"), supported("filter.rename", "id" to "a", "name" to "n"))
        assertEquals(
            ProductFilterCommand.Rename("a", "n", "👩🏽‍💻"),
            supported("filter.rename", "id" to "a", "name" to "n", "emoji" to "👩🏽‍💻"),
        )
        assertEquals(
            ProductFilterCommand.Domain("a", "example.com", DomainDecision.BLOCKED, false),
            supported("filter.domain", "id" to "a", "domain" to "example.com", "decision" to "blocked"),
        )
        assertEquals(
            ProductFilterCommand.Domain("a", "example.com", DomainDecision.ALLOWED, true),
            supported("filter.domain", "id" to "a", "domain" to "example.com", "decision" to "allowed", "remove" to true),
        )
        assertEquals(ProductFilterCommand.RemoveList("a", "s"), supported("filter.removeList", "id" to "a", "sourceID" to "s"))
        assertEquals(ProductFilterCommand.RestoreDefaults, supported("filter.restoreDefaults"))
    }

    @Test fun `source selection and undo commands validate their payloads`() {
        assertEquals(ProductFilterCommand.Lists("a", listOf("source")), supported("filter.lists", "id" to "a", "ids" to listOf("source")))
        assertEquals(ProductFilterCommand.UndoDomain("a", "example.com"), supported("filter.undoDomain", "id" to "a", "domain" to "example.com", "decision" to "blocked"))
        assertEquals(ProductFilterCommand.UndoList("a", "source"), supported("filter.undoList", "id" to "a", "sourceID" to "source"))
        assertEquals("invalid-argument", invalid("filter.lists", "id" to "a", "ids" to listOf("source", "source")))
        assertEquals("invalid-argument", invalid("filter.undoDomain", "id" to "a", "domain" to "example.com", "decision" to "unknown"))
    }

    @Test fun `missing or wrong-typed arguments are a typed rejection`() {
        assertEquals("invalid-argument", invalid("filter.open"))
        assertEquals("invalid-argument", invalid("filter.open", "id" to 7))
        assertEquals("invalid-argument", invalid("filter.apply", "id" to "a"))
        assertEquals("invalid-argument", invalid("filter.create", "name" to true))
        assertEquals("invalid-argument", invalid("filter.create", "name" to "n", "duplicate" to 4))
        assertEquals("invalid-argument", invalid("filter.rename", "id" to "a", "name" to 9))
        assertEquals("invalid-argument", invalid("filter.rename", "id" to "a", "name" to "n", "emoji" to 9))
        assertEquals("invalid-argument", invalid("filter.domain", "id" to "a", "domain" to "example.com", "decision" to "maybe"))
        assertEquals("invalid-argument", invalid("filter.domain", "id" to "a", "domain" to "example.com", "decision" to "blocked", "remove" to "yes"))
        assertEquals("invalid-argument", invalid("filter.removeList", "id" to "a"))
    }

    @Test fun `unsupported canonical arguments are refused rather than ignored`() {
        assertEquals("invalid-argument", invalid("filter.apply", "id" to "a", "review" to "t", "standaloneReview" to " "))
        // Well-typed identity values reach the real owner policy; the parser never ignores emoji.
        assertEquals(
            ProductFilterCommand.Rename("a", "n", "x"),
            supported("filter.rename", "id" to "a", "name" to "n", "emoji" to "x"),
        )
    }

    @Test fun `ownerless canonical commands and unknown types stay explicit`() {
        assertEquals(
            ProductCommandParse.Unsupported,
            parse("filter.renameForm", "id" to "a", "ids" to listOf("a")),
        )
        assertEquals(ProductCommandParse.Unknown, parse("filter.frobnicate", "id" to "a"))
        assertEquals(ProductCommandParse.Unknown, parse("protection.resume", "id" to "a"))
    }

    @Test fun `custom blocklist commands parse to their typed shapes`() {
        val custom = parse("filter.customList", "id" to "a", "ids" to listOf("a", "b"))
        assertEquals(
            ProductCommandParse.Supported(ProductFilterCommand.CustomList("a", listOf("a", "b"))),
            custom,
        )
        assertEquals(
            ProductCommandParse.Supported(
                ProductFilterCommand.AddCustomList("tok", "List", "https://example.com/list.txt", com.lavasec.app.foundation.filter.BlocklistFormat.AUTO),
            ),
            parse("filter.addCustomList", "token" to "tok", "name" to "List", "url" to "https://example.com/list.txt"),
        )
        assertEquals(
            ProductCommandParse.Supported(
                ProductFilterCommand.DeleteCustomList("a", "custom-1"),
            ),
            parse("filter.deleteCustomList", "id" to "a", "sourceID" to "custom-1"),
        )
    }

    private fun librarySupported(type: String, vararg values: Pair<String, Any?>): ProductLibraryCommand {
        val result = parse(type, *values)
        assertTrue("expected Supported for $type but was $result", result is ProductCommandParse.Supported)
        return (result as ProductCommandParse.Supported).command as ProductLibraryCommand
    }

    @Test fun `library session commands parse to their typed shapes`() {
        assertEquals(ProductLibraryCommand.Edit, librarySupported("library.edit"))
        assertEquals(ProductLibraryCommand.Cancel, librarySupported("library.cancel"))
        assertEquals(
            ProductLibraryCommand.ToggleDeletion("a"),
            librarySupported("library.toggleDeletion", "id" to "a"),
        )
        assertEquals(
            ProductLibraryCommand.Form.Create(null, null),
            librarySupported("library.form", "form" to "create"),
        )
        assertEquals(
            ProductLibraryCommand.Form.Create("New", "source"),
            librarySupported("library.form", "form" to "create", "name" to "New", "id" to "source"),
        )
        assertEquals(
            ProductLibraryCommand.Form.Rename("a", "Renamed", "🔥"),
            librarySupported("library.form", "form" to "rename", "id" to "a", "name" to "Renamed", "emoji" to "🔥"),
        )
        assertEquals(
            ProductLibraryCommand.Form.Delete(listOf("a", "b")),
            librarySupported("library.form", "form" to "delete", "ids" to listOf("a", "b")),
        )
        assertEquals(
            ProductLibraryCommand.Form.Delete(null),
            librarySupported("library.form", "form" to "delete"),
        )
    }

    @Test fun `malformed library session arguments are a typed rejection`() {
        assertEquals("invalid-argument", invalid("library.toggleDeletion"))
        assertEquals("invalid-argument", invalid("library.toggleDeletion", "id" to 7))
        assertEquals("invalid-argument", invalid("library.form"))
        assertEquals("invalid-argument", invalid("library.form", "form" to "frobnicate"))
        assertEquals("invalid-argument", invalid("library.form", "form" to "rename", "id" to "a"))
        assertEquals("invalid-argument", invalid("library.form", "form" to "rename", "id" to "a", "name" to 4))
        assertEquals("invalid-argument", invalid("library.form", "form" to "create", "name" to true))
        assertEquals("invalid-argument", invalid("library.form", "form" to "delete", "ids" to listOf("a", 4)))
        assertEquals("invalid-argument", invalid("library.form", "form" to "delete", "ids" to "a"))
    }

    @Test fun `library edit refusals map to stable wire codes`() {
        assertEquals("library-not-editing", com.lavasec.app.product.FilterLibraryEditRejection.NOT_EDITING.wireCode())
        assertEquals("library-active-filter", com.lavasec.app.product.FilterLibraryEditRejection.ACTIVE_FILTER.wireCode())
        assertEquals("library-frozen-filter", com.lavasec.app.product.FilterLibraryEditRejection.FROZEN_FILTER.wireCode())
        assertEquals("library-stale-staging", com.lavasec.app.product.FilterLibraryEditRejection.STALE_STAGING.wireCode())
        assertEquals("library-commit-failed", com.lavasec.app.product.FilterLibraryEditRejection.COMMIT_FAILED.wireCode())
    }

    @Test fun `filter refresh parses to its typed shape and rejects a missing id`() {
        assertEquals(
            ProductFilterCommand.Refresh("active"),
            supported("filter.refresh", "id" to "active"),
        )
        assertEquals("invalid-argument", invalid("filter.refresh"))
        assertEquals("invalid-argument", invalid("filter.refresh", "id" to 7))    }

    @Test fun `share commands parse to their typed shapes and reject a missing id`() {
        listOf(
            "share.query" to ProductShareCommand.Query("a"),
            "share.copy" to ProductShareCommand.Copy("a"),
            "share.card" to ProductShareCommand.Card("a", "native-render-token"),
            "share.present" to ProductShareCommand.Present("a"),
        ).forEach { (type, expected) ->
            val result = if (type == "share.card") parse(type, "id" to "a", "token" to "native-render-token") else parse(type, "id" to "a")
            assertTrue("expected Supported for $type but was $result", result is ProductCommandParse.Supported)
            assertEquals(expected, (result as ProductCommandParse.Supported).command)
        }
        assertEquals("invalid-argument", invalid("share.query"))
        assertEquals("invalid-argument", invalid("share.copy", "id" to 7))
        assertEquals("invalid-argument", invalid("share.card", "id" to "a"))
        assertEquals("invalid-argument", invalid("share.card", "id" to "a", "token" to ""))
    }

    @Test fun `a revoked share-card grant is refused before it can contend for the single admission lane`() {
        val source = File("src/main/kotlin/com/lavasec/app/reactnative/NativeProductBridge.kt")
        assertTrue("bridge source missing at ${source.absolutePath}", source.isFile)
        val text = source.readText()
        val command = text.substring(
            text.indexOf("fun command(request: String, promise: Promise)"),
            text.indexOf("private fun playHaptic(request: JSONObject)"),
        )
        val tokenCheck = command.indexOf("shareCards.authorizedPayload(input.optString(\"token\"))")
        val lane = command.indexOf("admission.run(input.optString(\"type\"))")
        assertTrue("the revoked share-card token must be validated", tokenCheck >= 0)
        assertTrue("the share-card token check must precede the mutation lane", tokenCheck in 0 until lane)
        assertTrue(
            "a token that is not the live grant must reject with stale-share",
            command.substring(tokenCheck, lane).contains("result(\"rejected\", \"stale-share\")"),
        )
    }

    @Test fun `vpn commands parse to their typed single-rotation shapes`() {
        assertEquals(ProductVpnCommand.Begin("e1", "42"), vpnSupported("vpn.begin", "id" to "e1", "generation" to "42"))
        // An unconfigured device projects the canonical empty generation, so `vpn.begin` must
        // accept it as the not-configured first-configuration entry, not reject it as malformed.
        assertEquals(ProductVpnCommand.Begin("e1", ""), vpnSupported("vpn.begin", "id" to "e1", "generation" to ""))
        assertEquals(ProductVpnCommand.Cancel("e1"), vpnSupported("vpn.cancel", "id" to "e1"))
        assertEquals(ProductVpnCommand.Reset("e1"), vpnSupported("vpn.reset", "id" to "e1"))
        assertEquals(ProductVpnCommand.Swap("e1"), vpnSupported("vpn.swap", "id" to "e1"))
        assertEquals(ProductVpnCommand.Commit("e1"), vpnSupported("vpn.commit", "id" to "e1"))
        assertEquals(ProductVpnCommand.Edit("e1", 0), vpnSupported("vpn.edit", "id" to "e1", "index" to 0))
        assertEquals(ProductVpnCommand.Remove("e1", 1), vpnSupported("vpn.remove", "id" to "e1", "index" to 1))
        assertEquals(
            ProductVpnCommand.Toggle(ChainedConfigurationToggleKey.ENABLED, false),
            vpnSupported("vpn.toggle", "key" to "enabled", "value" to false),
        )
        assertEquals(
            ProductVpnCommand.RowToggle("42", 0, true),
            vpnSupported("vpn.rowToggle", "generation" to "42", "index" to 0, "value" to true),
        )
    }

    private fun vpnSupported(type: String, vararg values: Pair<String, Any?>): ProductVpnCommand {
        val result = parse(type, *values)
        assertTrue("expected Supported for $type but was $result", result is ProductCommandParse.Supported)
        return (result as ProductCommandParse.Supported).command as ProductVpnCommand
    }

    @Test fun `vpn commands reject malformed arguments and smuggled key material`() {
        assertEquals("invalid-argument", invalid("vpn.begin", "id" to "e1"))
        assertEquals("invalid-argument", invalid("vpn.begin", "generation" to "42"))
        assertEquals("invalid-argument", invalid("vpn.begin", "id" to "", "generation" to "42"))
        assertEquals("invalid-argument", invalid("vpn.commit"))
        assertEquals("invalid-argument", invalid("vpn.toggle", "key" to "frobnicate", "value" to true))
        assertEquals("invalid-argument", invalid("vpn.toggle", "key" to "enabled", "value" to "yes"))
        assertEquals("invalid-argument", invalid("vpn.edit", "id" to "e1", "index" to 2))
        assertEquals("invalid-argument", invalid("vpn.rowToggle", "generation" to "42", "index" to 3, "value" to true))
        // Configuration text and key material are never accepted on the wire; the native editor
        // stages them through the owner's opaque lease.
        listOf("privateKey", "presharedKey", "rawConfiguration", "configuration", "conf", "keyMaterial").forEach { field ->
            assertEquals(
                "unsupported-argument",
                invalid("vpn.commit", "id" to "e1", field to "secret"),
            )
        }
        assertEquals(
            "unsupported-argument",
            invalid("vpn.begin", "id" to "e1", "generation" to "42", "privateKey" to "secret"),
        )
    }

    @Test fun `vpn begin maps the canonical empty generation to the not-configured sentinel`() {
        // The empty generation is the unconfigured state (no committed rotation); the first
        // configuration is imported over that state, so it must not be reported as malformed.
        assertEquals(0L, vpnBeginExpectedGeneration(""))
        assertEquals(42L, vpnBeginExpectedGeneration("42"))
        // A committed generation is always > 0, so the sentinel cannot collide; a non-numeric
        // value is malformed and the caller answers `invalid-argument`.
        assertNull(vpnBeginExpectedGeneration("nope"))
        assertNull(vpnBeginExpectedGeneration("4.2"))
    }

    @Test fun `canonical import flows are recognized as an explicit unavailable presentation`() {
        assertTrue(NativeProductRoutes.importPresentationUnavailable("import"))
        assertTrue(NativeProductRoutes.importPresentationUnavailable("importCode"))
        assertTrue(NativeProductRoutes.importPresentationUnavailable("importScan"))
        assertFalse(NativeProductRoutes.importPresentationUnavailable("filters"))
        assertFalse(NativeProductRoutes.importPresentationUnavailable("https://example.com"))
    }

    @Test fun `sudoku commands parse to their typed shapes and reject malformed games`() {
        assertEquals(
            ProductSudokuCommand.New(challenge = false),
            (parse("sudoku.new") as ProductCommandParse.Supported).command,
        )
        assertEquals(
            ProductSudokuCommand.New(challenge = true),
            (parse("sudoku.new", "challenge" to true) as ProductCommandParse.Supported).command,
        )
        assertEquals(
            ProductSudokuCommand.New(challenge = false),
            (parse("sudoku.new", "challenge" to false) as ProductCommandParse.Supported).command,
        )
        // A present non-boolean challenge is a typed rejection, never a silent coercion.
        assertEquals("invalid-argument", invalid("sudoku.new", "challenge" to "true"))
        assertEquals("invalid-argument", invalid("sudoku.new", "challenge" to 1))
        assertEquals("invalid-argument", invalid("sudoku.new", "challenge" to listOf(true)))

        val game = SudokuGame.fresh(SudokuPuzzle.generate(648))
        assertEquals(
            ProductSudokuCommand.Save(game),
            (parse("sudoku.save", "game" to game) as ProductCommandParse.Supported).command,
        )

        // A missing or malformed game payload is a typed rejection, never a partial save.
        assertEquals("invalid-argument", invalid("sudoku.save"))
        assertEquals("invalid-argument", invalid("sudoku.save", "game" to "not-a-game"))
    }

    private fun dnsSupported(type: String, vararg values: Pair<String, Any?>): ProductDnsCommand {
        val result = parse(type, *values)
        assertTrue("expected Supported for $type but was $result", result is ProductCommandParse.Supported)
        return (result as ProductCommandParse.Supported).command as ProductDnsCommand
    }

    @Test fun `dns commands parse to their typed shapes`() {
        val tiers = listOf(
            ResolverTierChoice("device-dns", "IP"),
            ResolverTierChoice("mullvad", "DoH"),
        )
        assertEquals(
            ProductDnsCommand.Tiers("ctx", tiers),
            dnsSupported("dns.tiers", "context" to "ctx", "tiers" to tiers),
        )
        assertEquals(
            ProductDnsCommand.CustomDraft(null),
            dnsSupported("dns.customDraft"),
        )
        assertEquals(
            ProductDnsCommand.CustomDraft(ResolverTierChoice("custom-dns", "DoH", "Home", "https://dns.example/query", "")),
            dnsSupported(
                "dns.customDraft",
                "choice" to ResolverTierChoice("custom-dns", "DoH", "Home", "https://dns.example/query", ""),
            ),
        )
        assertEquals(
            ProductDnsCommand.Custom("ctx", "Home", "https://dns.example/query", ""),
            dnsSupported("dns.custom", "context" to "ctx", "name" to "Home", "primary" to "https://dns.example/query", "secondary" to ""),
        )
        assertEquals(ProductDnsCommand.CustomStage("token", "ctx", "", "sdns://primary", "sdns://secondary"),
            dnsSupported("dns.customStage", "id" to "token", "context" to "ctx", "name" to "",
                "primary" to "sdns://primary", "secondary" to "sdns://secondary"))
        assertFalse(dnsSupported("dns.customStage", "id" to "token", "context" to "ctx", "name" to "",
            "primary" to "sdns://primary", "secondary" to "sdns://secondary").commitsDurableConfiguration())
        assertEquals(
            ProductDnsCommand.DismissCustomEntry("token-1"),
            dnsSupported("customEntry.dismiss", "id" to "token-1"),
        )
        assertEquals(
            ProductDnsCommand.Toggle("ctx", 1, true),
            dnsSupported("dns.toggle", "context" to "ctx", "index" to 1, "value" to true),
        )
    }

    @Test fun `malformed dns arguments are a typed rejection`() {
        assertEquals("invalid-argument", invalid("dns.tiers", "tiers" to listOf(ResolverTierChoice("mullvad", "DoH"))))
        assertEquals("invalid-argument", invalid("dns.tiers", "context" to "ctx"))
        assertEquals("invalid-argument", invalid("dns.custom", "context" to "ctx", "name" to "Home", "primary" to "1.1.1.1"))
        assertEquals("invalid-argument", invalid("customEntry.dismiss"))
        assertEquals("invalid-argument", invalid("dns.customStage", "context" to "ctx", "name" to "", "primary" to "1.1.1.1", "secondary" to ""))
        assertEquals("invalid-argument", invalid("dns.customStage", "id" to "", "context" to "ctx", "name" to "", "primary" to "1.1.1.1", "secondary" to ""))
        // Only the primary/fallback ladder (index 0..1) is representable; a toggle outside it is
        // malformed, never silently reinterpreted as the fallback slot.
        assertEquals("invalid-argument", invalid("dns.toggle", "context" to "ctx", "index" to 2, "value" to true))
        assertEquals("invalid-argument", invalid("dns.toggle", "context" to "ctx", "index" to 1))
    }

    @Test fun `dns wire ids round-trip and a malformed id stays opaque for the policy`() {
        assertEquals("mullvad|DoH", ResolverDnsWire.encode("mullvad", "DoH"))
        assertEquals("mullvad" to "DoH", ResolverDnsWire.decode("mullvad|DoH"))
        // No separator, leading separator, and trailing separator all stay opaque rather than
        // silently defaulting to a provider or transport.
        assertEquals("mullvad" to "", ResolverDnsWire.decode("mullvad"))
        assertEquals("|DoH" to "", ResolverDnsWire.decode("|DoH"))
        assertEquals("mullvad|" to "", ResolverDnsWire.decode("mullvad|"))
    }

    @Test fun `policy refusals map to stable wire codes`() {
        assertEquals("chained-edit-locked", com.lavasec.app.foundation.resolver.ResolverEditRejection.ChainedEditLocked.wireCode())
        assertEquals("stale-editor", com.lavasec.app.foundation.resolver.ResolverEditRejection.StaleEditor.wireCode())
        assertEquals(
            "unknown-provider",
            com.lavasec.app.foundation.resolver.ResolverEditRejection.UnknownProvider("bogus").wireCode(),
        )
        assertEquals(
            "transport-unavailable",
            com.lavasec.app.foundation.resolver.ResolverEditRejection.TransportUnavailable(
                com.lavasec.app.foundation.resolver.ResolverProviderId.MULLVAD,
                CatalogResolverTransport.DOT,
                com.lavasec.app.foundation.resolver.ResolverTransportUnavailableReason.DOT_RUNTIME_ABSENT,
            ).wireCode(),
        )
    }

    @Test fun `a custom tier refused for the absent runtime route is named honestly`() {
        assertEquals(
            "custom-resolver-runtime-absent",
            com.lavasec.app.foundation.resolver.ResolverEditRejection.TransportUnavailable(
                com.lavasec.app.foundation.resolver.ResolverProviderId.CUSTOM,
                CatalogResolverTransport.DOH,
                com.lavasec.app.foundation.resolver.ResolverTransportUnavailableReason.CUSTOM_RESOLVER_RUNTIME_ABSENT,
            ).dnsResultWireCode(),
        )
        // A built-in provider/transport that has no runtime route keeps the generic code so
        // non-custom copy still applies.
        assertEquals(
            "transport-unavailable",
            com.lavasec.app.foundation.resolver.ResolverEditRejection.TransportUnavailable(
                com.lavasec.app.foundation.resolver.ResolverProviderId.MULLVAD,
                CatalogResolverTransport.DOT,
                com.lavasec.app.foundation.resolver.ResolverTransportUnavailableReason.DOT_RUNTIME_ABSENT,
            ).dnsResultWireCode(),
        )
    }

    private fun settingsSupported(key: String, value: PreferenceValue): ProductSettingsCommand {
        val result = parse("settings.set", "key" to key, "value" to value)
        assertTrue("expected Supported for settings.set but was $result", result is ProductCommandParse.Supported)
        return (result as ProductCommandParse.Supported).command as ProductSettingsCommand
    }

    @Test fun `settings set decodes the closed value union and rejects missing arguments`() {
        assertEquals(
            ProductSettingsCommand.Set("appearance", PreferenceValue.Text("dark")),
            settingsSupported("appearance", PreferenceValue.Text("dark")),
        )
        assertEquals(
            ProductSettingsCommand.Set("matchTextSize", PreferenceValue.Bool(false)),
            settingsSupported("matchTextSize", PreferenceValue.Bool(false)),
        )
        assertEquals(
            ProductSettingsCommand.Set("textSize", PreferenceValue.IntValue(6)),
            settingsSupported("textSize", PreferenceValue.IntValue(6)),
        )
        assertEquals(
            ProductSettingsCommand.Set("dnsPatchProvider", PreferenceValue.Text("quad9")),
            settingsSupported("dnsPatchProvider", PreferenceValue.Text("quad9")),
        )
        assertEquals("invalid-argument", invalid("settings.set", "value" to PreferenceValue.Bool(true)))
        assertEquals("invalid-argument", invalid("settings.set", "key" to "appearance"))
        assertEquals("invalid-argument", invalid("settings.set"))
    }

    @Test fun `account commands parse to their typed shapes and refuse injected identity or tokens`() {
        assertEquals(
            ProductAccountCommand.Google,
            (parse("account.google") as ProductCommandParse.Supported).command,
        )
        assertEquals(
            ProductAccountCommand.Apple,
            (parse("account.apple") as ProductCommandParse.Supported).command,
        )
        assertEquals(
            ProductAccountCommand.SignOut,
            (parse("account.signOut") as ProductCommandParse.Supported).command,
        )
        assertEquals(
            ProductAccountCommand.Delete,
            (parse("account.delete") as ProductCommandParse.Supported).command,
        )
        // The owner binds the account/generation; an injected account, identity or token is
        // refused rather than ignored, so no credential can enter from RN.
        for (argument in listOf("account", "accountID", "userId", "token", "accessToken", "refreshToken", "envelope")) {
            assertEquals(
                "unsupported-argument",
                invalid("account.google", argument to "someone-else"),
            )
        }
    }

    @Test fun `backup commands parse to their typed shapes and refuse caller-supplied identity`() {
        assertEquals(
            ProductBackupCommand.Refresh,
            (parse("backup.refresh") as ProductCommandParse.Supported).command,
        )
        assertEquals(
            ProductBackupCommand.BackUpNow,
            (parse("backup.now") as ProductCommandParse.Supported).command,
        )
        assertEquals(
            ProductBackupCommand.Disable,
            (parse("backup.disable") as ProductCommandParse.Supported).command,
        )
        assertEquals(
            ProductBackupCommand.Delete,
            (parse("backup.delete") as ProductCommandParse.Supported).command,
        )
        // The owner binds the account/generation; an injected identity is refused, not ignored.
        assertEquals(
            "unsupported-argument",
            invalid("backup.now", "account" to "someone-else"),
        )
        assertEquals(
            "unsupported-argument",
            invalid("backup.delete", "accountID" to "someone-else"),
        )
        assertEquals(
            "unsupported-argument",
            invalid("backup.now", "envelope" to "forged"),
        )
    }

    @Test fun `only durable configuration commits arm the automatic backup upload`() {
        // Committed filter/library/resolver/vpn/preference mutations all arm the coordinator.
        assertTrue(command("filter.apply", "id" to "f1", "review" to "token").commitsDurableConfiguration())
        assertTrue(command("filter.rename", "id" to "f1", "name" to "Renamed").commitsDurableConfiguration())
        assertTrue(command("dns.toggle", "context" to "ctx", "index" to 1L, "value" to true).commitsDurableConfiguration())
        assertTrue(command("vpn.commit", "id" to "draft").commitsDurableConfiguration())
        assertTrue(
            command("settings.set", "key" to "appearance", "value" to PreferenceValue.Text("dark"))
                .commitsDurableConfiguration(),
        )
        // Only the whole-library delete form commits; create/rename stage a draft.
        assertTrue(
            command("library.form", "form" to "delete", "ids" to listOf("f1"))
                .commitsDurableConfiguration(),
        )
        assertFalse(
            command("library.form", "form" to "rename", "id" to "f1", "name" to "Renamed")
                .commitsDurableConfiguration(),
        )
        // Draft-only stages and reads/effects never arm an upload.
        assertFalse(
            command("filter.domain", "id" to "f1", "domain" to "example.com", "decision" to "blocked")
                .commitsDurableConfiguration(),
        )
        assertFalse(command("filter.review", "id" to "f1").commitsDurableConfiguration())
        assertFalse(command("backup.now").commitsDurableConfiguration())
        assertFalse(command("share.query", "id" to "f1").commitsDurableConfiguration())
        assertFalse(command("activity.visibility", "token" to "t", "visible" to true).commitsDurableConfiguration())
    }

    private fun command(type: String, vararg values: Pair<String, Any?>): ProductCommand {
        val result = parse(type, *values)
        assertTrue("expected Supported for $type but was $result", result is ProductCommandParse.Supported)
        return (result as ProductCommandParse.Supported).command
    }

    @Test fun `activity and diagnostics queries parse to their typed shapes`() {
        assertEquals(
            ProductQueryCommand.Activity(null, null, false),
            command("activity.query"),
        )
        assertEquals(
            ProductQueryCommand.Activity(1_700_000_000L, 1_700_086_400L, true),
            command("activity.query", "start" to 1_700_000_000L, "end" to 1_700_086_400L, "hourly" to true),
        )
        assertEquals(
            ProductQueryCommand.Domains(false, DomainQueryDecision.ALL, "", null, null, 31),
            command("domains.query", "history" to false, "decision" to "All", "search" to "", "limit" to 31),
        )
        assertEquals(
            ProductQueryCommand.Domains(true, DomainQueryDecision.BLOCKED, "exa", 1_700_000_000L, 1_700_086_400L, null),
            command(
                "domains.query",
                "history" to true,
                "decision" to "Blocked",
                "search" to "exa",
                "start" to 1_700_000_000L,
                "end" to 1_700_086_400L,
            ),
        )
        assertEquals(ProductQueryCommand.Network, command("network.query"))
        assertEquals(ProductQueryCommand.Stats, command("stats.query"))
        assertEquals(ProductReportsSample, command("reports.sample"))
    }

    @Test fun `catalog query parses its bounded unique source ids`() {
        assertEquals(ProductCatalogQuery(listOf("a", "b")), command("catalog.query", "ids" to listOf("a", "b")))
        assertEquals(ProductCatalogQuery(emptyList()), command("catalog.query", "ids" to emptyList<String>()))
        assertEquals("invalid-argument", invalid("catalog.query"))
        assertEquals("invalid-argument", invalid("catalog.query", "ids" to listOf("a", "a")))
        assertEquals("invalid-argument", invalid("catalog.query", "ids" to listOf("")))
        assertEquals("invalid-argument", invalid("catalog.query", "ids" to listOf(1)))
    }

    @Test fun `activity visibility parses its token, dwell state and optional window`() {
        assertEquals(
            ProductActivityVisibility("t", true, null, null),
            command("activity.visibility", "token" to "t", "visible" to true),
        )
        assertEquals(
            ProductActivityVisibility("t", false, 1_700_000_000L, 1_700_086_400L),
            command(
                "activity.visibility",
                "token" to "t", "visible" to false,
                "start" to 1_700_000_000L, "end" to 1_700_086_400L,
            ),
        )
        assertEquals("invalid-argument", invalid("activity.visibility"))
        assertEquals("invalid-argument", invalid("activity.visibility", "token" to " ", "visible" to true))
        assertEquals("invalid-argument", invalid("activity.visibility", "token" to "t"))
        assertEquals(
            "invalid-argument",
            invalid("activity.visibility", "token" to "t", "visible" to true, "start" to 2L, "end" to 1L),
        )
        assertEquals(
            "invalid-argument",
            invalid("activity.visibility", "token" to "t", "visible" to true, "start" to "now"),
        )
    }

    @Test fun `malformed query arguments are a typed rejection`() {
        assertEquals("invalid-argument", invalid("activity.query", "start" to -1))
        assertEquals("invalid-argument", invalid("activity.query", "start" to 2L, "end" to 1L))
        assertEquals("invalid-argument", invalid("activity.query", "start" to "now"))
        assertEquals("invalid-argument", invalid("activity.query", "hourly" to "yes"))
        assertEquals("invalid-argument", invalid("domains.query", "history" to false, "decision" to "Maybe", "search" to ""))
        assertEquals("invalid-argument", invalid("domains.query", "decision" to "All", "search" to ""))
        assertEquals("invalid-argument", invalid("domains.query", "history" to false, "decision" to "All", "search" to "x".repeat(201)))
        assertEquals("invalid-argument", invalid("domains.query", "history" to false, "decision" to "All", "search" to "", "limit" to 0))
        assertEquals("invalid-argument", invalid("domains.query", "history" to false, "decision" to "All", "search" to "", "limit" to 301))
        assertEquals("invalid-argument", invalid("domains.query", "history" to false, "decision" to "All", "search" to "", "limit" to 1.5))
    }

    @Test fun `domain list commands parse to their typed shapes`() {
        assertEquals(ProductDomainCommand.EnableHistory, command("domains.enableHistory"))
        assertEquals(ProductDomainCommand.Copy("example.com"), command("domains.copy", "domain" to "example.com"))
        assertEquals(
            ProductDomainCommand.Stage("example.com", DomainDecision.BLOCKED),
            command("domains.stage", "domain" to "example.com", "decision" to "blocked"),
        )
        assertEquals(
            ProductDomainCommand.Stage("example.com", DomainDecision.ALLOWED),
            command("domains.stage", "domain" to "example.com", "decision" to "allowed"),
        )
        assertEquals(ProductDomainCommand.Cancel("token-1"), command("domains.cancel", "token" to "token-1"))
    }

    @Test fun `local-log commands parse to their typed shapes`() {
        assertEquals(
            ProductLogsCommand.Clear("Clear domain history", true),
            command("logs.clear", "kind" to "Clear domain history", "surface" to "activityViewing"),
        )
        assertEquals(
            ProductLogsCommand.Clear("Clear Lava Guard progress", false),
            command("logs.clear", "kind" to "Clear Lava Guard progress"),
        )
        assertEquals(ProductLogsCommand.Export(false), command("logs.export", "domains" to false))
        assertEquals(ProductLogsCommand.Export(true), command("logs.export", "domains" to true))
    }

    @Test fun `malformed local-log arguments are a typed rejection`() {
        // A non-canonical surface or an out-of-bounds kind never reaches the owner.
        assertEquals("invalid-argument", invalid("logs.clear", "kind" to "Clear domain history", "surface" to "settings"))
        assertEquals("invalid-argument", invalid("logs.clear"))
        assertEquals("invalid-argument", invalid("logs.clear", "kind" to "x".repeat(65)))
        // Export selection must be an explicit boolean, never a truthy string.
        assertEquals("invalid-argument", invalid("logs.export", "domains" to "true"))
        assertEquals("invalid-argument", invalid("logs.export"))
    }

    @Test fun `malformed domain list arguments are a typed rejection`() {
        assertEquals("invalid-argument", invalid("domains.copy"))
        assertEquals("invalid-argument", invalid("domains.copy", "domain" to "   "))
        assertEquals("invalid-argument", invalid("domains.stage", "domain" to "example.com", "decision" to "maybe"))
        assertEquals("invalid-argument", invalid("domains.cancel"))
        assertEquals("invalid-argument", invalid("domains.cancel", "token" to "   "))
    }

    /** A flow-host launcher whose single route result is fixed, isolating the seam's mapping. */
    private class FixedFlowLauncher(
        private val routeResult: () -> LavaNativeFlowLaunchResult,
    ) : LavaNativeFlowLauncher {
        override suspend fun presentRoute(request: LavaNativeFlowRequest): LavaNativeFlowLaunchResult =
            routeResult()

        override suspend fun authorizeCredential(
            request: LavaNativeFlowRequest,
            surface: ProtectedSurface?,
        ): LavaNativeFlowLaunchResult = LavaNativeFlowLaunchResult.Launched(completed = true)
    }

    private fun flowHost(
        launcher: LavaNativeFlowLauncher,
        generation: Long = 7L,
        account: String? = "acct-1",
    ) = LavaNativeFlowHost(
        configuration = NativeFlowBuildConfiguration.DEBUG,
        launcher = launcher,
        currentGraphGeneration = { generation },
        currentAccountId = { account },
    )

    private fun flowRequest(flow: String = "account") =
        LavaNativeFlowRequest(requestId = 1L, flow = flow, graphGeneration = 7L, accountId = "acct-1")

    @Test fun `the flow-host seam completes a presented canonical route`() = runTest {
        assertEquals(
            NativeFlowBridgeResult("completed", null),
            presentNativeFlow(
                flowHost(FixedFlowLauncher { LavaNativeFlowLaunchResult.Launched(true) }),
                flowRequest(),
            ),
        )
    }

    @Test fun `the flow-host seam keeps a dismissal as a typed cancellation`() = runTest {
        assertEquals(
            NativeFlowBridgeResult("cancelled", null),
            presentNativeFlow(
                flowHost(FixedFlowLauncher { LavaNativeFlowLaunchResult.Launched(false) }),
                flowRequest(),
            ),
        )
    }

    @Test fun `the flow-host seam keeps a refusal's stable reason`() = runTest {
        // An internal-only presentation is refused by the host before the launcher runs.
        assertEquals(
            NativeFlowBridgeResult("rejected", "internal-presentation"),
            presentNativeFlow(
                flowHost(FixedFlowLauncher { LavaNativeFlowLaunchResult.Launched(true) }),
                flowRequest(flow = "createFilter"),
            ),
        )
    }

    @Test fun `a credential-graph rotation after the launch is a stale fence`() = runTest {
        assertEquals(
            NativeFlowBridgeResult("rejected", "stale-generation"),
            presentNativeFlow(
                flowHost(FixedFlowLauncher { LavaNativeFlowLaunchResult.Launched(true) }, generation = 9L),
                flowRequest(),
            ),
        )
    }

    @Test fun `an absent flow host is a typed host-unavailable refusal`() = runTest {
        assertEquals(
            NativeFlowBridgeResult("rejected", "host-unavailable"),
            presentNativeFlow(null, flowRequest()),
        )
    }
}
