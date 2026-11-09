package com.lavasec.app.foundation.filter

import java.io.ByteArrayInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FilterArtifactPipelineTest {
    @Test
    fun `per operation progress observes real artifact writes without retaining a previous caller`() =
        withTemporaryDirectory { directory -> runBlocking {
            val compiler = FilterCompiler(directory)
            val first = mutableListOf<FilterCompilePhase>()
            compiler.compile(request(listOf(source("progress", "blocked.example\n")))) { first += it }.use { prepared ->
                assertTrue(first.contains(FilterCompilePhase.ARTIFACT_WRITE))
                FilterArtifact.open(prepared.file, prepared.sha256, prepared.identity).use { opened ->
                    assertEquals(FilterDecisionReason.BLOCKLIST, opened.snapshot.decide("blocked.example").reason)
                }
            }
            val captured = first.toList()
            compiler.compile(request(listOf(source("next", "next.example\n")))).close()
            assertEquals(captured, first)
        } }

    @Test
    fun `prepared parser version four artifacts are refused after service-rule retention changes`() =
        withTemporaryDirectory { directory -> runBlocking {
            val prepared=FilterCompiler(directory).compile(request(listOf(source("service","apple.com\n"))))
            try {
                val bytes=prepared.file.readBytes()
                // LAVAFILT magic (8 bytes), artifact schema (4), parser rules version (4).
                java.nio.ByteBuffer.wrap(bytes).putInt(12,4)
                val old=File(directory,"old-parser.lsfa");old.writeBytes(bytes)
                val result=FilterArtifact.openResult(old,FilterArtifact.sha256(old))
                assertTrue(result is FilterArtifactOpenResult.Unavailable)
                assertEquals(FilterArtifactUnavailableReason.PARSER_VERSION_MISMATCH,(result as FilterArtifactOpenResult.Unavailable).reason)
            } finally {prepared.close()}
        } }

    @Test
    fun `custom zero and service-domain lists compile and reopen with exact source identities`() =
        withTemporaryDirectory { directory -> runBlocking {
            for (text in listOf("", "# comments\n", "@@||allowed.example^\n", "apple.com\nchild.google.com\n")) {
                val payload = source("custom-empty", text).copy(isCustom = true)
                val prepared = FilterCompiler(directory).compile(request(listOf(payload)))
                try {
                    assertEquals(if (text.contains("apple.com")) 2 else 0, prepared.identity.ruleCounts.total)
                    assertFalse(prepared.identity.intentionalEmpty)
                    assertEquals(listOf(ArtifactSourceIdentity(payload.id, payload.acceptedSha256, payload.format)),
                        prepared.identity.selectedSources)
                    FilterArtifact.open(prepared.file, prepared.sha256, prepared.identity).use { opened ->
                        assertEquals(FilterDecisionReason.DEFAULT_ALLOW, opened.snapshot.decide("unlisted.example").reason)
                    }
                } finally { prepared.close() }
            }
        } }

    @Test
    fun `custom malformed payload fails by source while mixed content keeps valid rules`() =
        withTemporaryDirectory { directory -> runBlocking {
            val compiler = FilterCompiler(directory)
            for (text in listOf("bad syntax / path\n", "-invalid.example\n")) {
                try {
                    compiler.compile(request(listOf(source("custom-bad", text).copy(isCustom = true))))
                    fail("malformed custom source was compiled")
                } catch (error: CustomBlocklistSourceAcquisitionException) {
                    assertEquals("custom-bad", error.sourceId)
                    assertEquals(CustomBlocklistSourceFailure.PARSE_FAILED, error.failure)
                }
                assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith("compile-") })
            }
            val mixed = source("custom-mixed", "good.example\n-invalid.example\nbad syntax / path\n").copy(isCustom = true)
            val prepared = compiler.compile(request(listOf(mixed)))
            try {
                FilterArtifact.open(prepared.file, prepared.sha256, prepared.identity).use { opened ->
                    assertEquals(FilterDecisionReason.BLOCKLIST, opened.snapshot.decide("good.example").reason)
                }
            } finally { prepared.close() }
        } }

    @Test
    fun `canonical compile commitment supports artifact identity strings above modified UTF limit`() {
        val longRevision = "r".repeat(70_000)
        val identity = ArtifactConfigurationIdentity(7, "configuration")
        val first = canonicalFilterCompileInputsSha256(
            configurationIdentity = identity,
            selectedSources = emptyList(),
            catalogRevision = longRevision,
            catalogSha256 = "a".repeat(64),
            intentionalEmpty = true,
            manualAllowRules = emptyList(),
            manualBlockRules = emptyList(),
            guardrailRules = emptyList(),
        )
        val second = canonicalFilterCompileInputsSha256(
            configurationIdentity = identity,
            selectedSources = emptyList(),
            catalogRevision = longRevision,
            catalogSha256 = "a".repeat(64),
            intentionalEmpty = true,
            manualAllowRules = emptyList(),
            manualBlockRules = emptyList(),
            guardrailRules = emptyList(),
        )
        val changed = canonicalFilterCompileInputsSha256(
            configurationIdentity = identity,
            selectedSources = emptyList(),
            catalogRevision = "${longRevision.dropLast(1)}s",
            catalogSha256 = "a".repeat(64),
            intentionalEmpty = true,
            manualAllowRules = emptyList(),
            manualBlockRules = emptyList(),
            guardrailRules = emptyList(),
        )

        assertEquals(first, second)
        assertNotEquals(first, changed)
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `compiler snapshots manual and guardrail rules before any checkpoint can mutate callers`() =
        withTemporaryDirectory { directory -> runBlocking {
            val originalBlock = DomainRule.parse("original-block.example")
            val replacementBlock = DomainRule.parse("replacement-block.example")
            val mutableBlocks = mutableListOf(originalBlock)
            var mutated = false
            val request = request(emptyList(), maxRules = 2).copy(
                manualBlockRules = mutableBlocks,
            )
            val compiler = FilterCompiler(
                scratchRoot = directory,
                checkpointObserver = FilterCompileCheckpointObserver { phase ->
                    if (phase == FilterCompilePhase.TABLE_HASH && !mutated) {
                        mutableBlocks.clear()
                        mutableBlocks += replacementBlock
                        mutated = true
                    }
                },
            )

            val prepared = compiler.compile(request)
            try {
                val expectedCommitment = canonicalFilterCompileInputsSha256(
                    configurationIdentity = prepared.identity.configuration,
                    selectedSources = prepared.identity.selectedSources,
                    catalogRevision = prepared.identity.catalogRevision,
                    catalogSha256 = prepared.identity.catalogSha256,
                    intentionalEmpty = prepared.identity.intentionalEmpty,
                    manualAllowRules = emptyList(),
                    manualBlockRules = listOf(originalBlock),
                    guardrailRules = emptyList(),
                )

                assertTrue(mutated)
                assertEquals(expectedCommitment, prepared.identity.compileInputsSha256)
                FilterArtifact.open(prepared.file, prepared.sha256).use { artifact ->
                    assertEquals(
                        FilterDecisionReason.BLOCKLIST,
                        artifact.snapshot.decide(originalBlock.domain).reason,
                    )
                    assertEquals(
                        FilterDecisionReason.DEFAULT_ALLOW,
                        artifact.snapshot.decide(replacementBlock.domain).reason,
                    )
                }
            } finally {
                prepared.close()
            }
        } }

    @Test
    fun `compiler embeds a canonical commitment to exact rule semantics`() =
        withTemporaryDirectory { directory -> runBlocking {
            val request = request(
                listOf(source("ordinary", "blocked.example\n")),
                maxRules = 10,
            ).copy(
                manualAllowRules = listOf(
                    DomainRule.parse("z-allowed.example"),
                    DomainRule.parse("a-allowed.example"),
                    DomainRule.parse("a-allowed.example"),
                ),
                manualBlockRules = listOf(DomainRule.parse("manual-block.example")),
                guardrailRules = listOf(DomainRule.parse("guardrail.example")),
            )
            val prepared = FilterCompiler(directory).compile(request)
            try {
                val identity = prepared.identity
                val expected = canonicalFilterCompileInputsSha256(
                    configurationIdentity = identity.configuration,
                    selectedSources = identity.selectedSources,
                    catalogRevision = identity.catalogRevision,
                    catalogSha256 = identity.catalogSha256,
                    intentionalEmpty = identity.intentionalEmpty,
                    manualAllowRules = request.manualAllowRules.reversed(),
                    manualBlockRules = request.manualBlockRules,
                    guardrailRules = request.guardrailRules,
                )
                val missingManualBlock = canonicalFilterCompileInputsSha256(
                    configurationIdentity = identity.configuration,
                    selectedSources = identity.selectedSources,
                    catalogRevision = identity.catalogRevision,
                    catalogSha256 = identity.catalogSha256,
                    intentionalEmpty = identity.intentionalEmpty,
                    manualAllowRules = request.manualAllowRules,
                    manualBlockRules = emptyList(),
                    guardrailRules = request.guardrailRules,
                )

                assertEquals(expected, identity.compileInputsSha256)
                assertNotEquals(missingManualBlock, identity.compileInputsSha256)
            } finally {
                prepared.close()
            }
        } }

    @Test
    fun `mapped artifact reports exact effective block allow and guardrail counts`() =
        withTemporaryDirectory { directory -> runBlocking {
            val compiler = FilterCompiler(directory)
            val allowOnly = compiler.compile(
                request(emptyList(), maxRules = 1).copy(
                    manualAllowRules = listOf(DomainRule.parse("allowed.example")),
                ),
            )
            val overlapped = compiler.compile(
                request(listOf(source("ordinary", "overlap.example\nkept.example\n")), maxRules = 4).copy(
                    manualAllowRules = listOf(DomainRule.parse("overlap.example")),
                    guardrailRules = listOf(DomainRule.parse("guardrail.example")),
                ),
            )
            try {
                FilterArtifact.open(allowOnly.file, allowOnly.sha256).use { artifact ->
                    assertEquals(0, artifact.snapshot.blockRuleCount)
                    assertEquals(0, artifact.snapshot.effectiveBlockRuleCount)
                    assertEquals(1, artifact.snapshot.allowRuleCount)
                    assertEquals(0, artifact.snapshot.guardrailRuleCount)
                }
                FilterArtifact.open(overlapped.file, overlapped.sha256).use { artifact ->
                    assertEquals(2, artifact.snapshot.blockRuleCount)
                    assertEquals(1, artifact.snapshot.effectiveBlockRuleCount)
                    assertEquals(1, artifact.snapshot.allowRuleCount)
                    assertEquals(1, artifact.snapshot.guardrailRuleCount)
                    assertEquals(FilterDecisionReason.LOCAL_ALLOWLIST, artifact.snapshot.decide("overlap.example").reason)
                    assertEquals(FilterDecisionReason.BLOCKLIST, artifact.snapshot.decide("kept.example").reason)
                    assertEquals(FilterDecisionReason.THREAT_GUARDRAIL, artifact.snapshot.decide("guardrail.example").reason)
                }
            } finally {
                allowOnly.close()
                overlapped.close()
            }
        } }

    @Test
    fun `mapped effective count preserves nested exact suffix and descendant guardrail semantics`() =
        withTemporaryDirectory { directory -> runBlocking {
            val prepared = FilterCompiler(directory).compile(
                request(emptyList(), maxRules = 20).copy(
                    manualBlockRules = listOf(
                        DomainRule.parse("exact.blocked.example", matchesSubdomains = false),
                        DomainRule.parse("suffix.blocked.example"),
                        DomainRule.parse("child.deep.example", matchesSubdomains = false),
                        DomainRule.parse("nested.branch.example"),
                        DomainRule.parse("unrelated.block.example", matchesSubdomains = false),
                        DomainRule.parse("ads.guarded.example", matchesSubdomains = false),
                    ),
                    manualAllowRules = listOf(
                        DomainRule.parse("exact.blocked.example", matchesSubdomains = false),
                        DomainRule.parse("suffix.blocked.example"),
                        DomainRule.parse("deep.example"),
                        DomainRule.parse("branch.example"),
                        DomainRule.parse("nested.branch.example"),
                        DomainRule.parse("guarded.example"),
                        DomainRule.parse("unrelated.allow.example"),
                    ),
                    guardrailRules = listOf(
                        DomainRule.parse("threat.guarded.example", matchesSubdomains = false),
                    ),
                ),
            )
            try {
                FilterArtifact.open(prepared.file, prepared.sha256).use { artifact ->
                    assertEquals(6, artifact.snapshot.blockRuleCount)
                    assertEquals(7, artifact.snapshot.allowRuleCount)
                    assertEquals(1, artifact.snapshot.guardrailRuleCount)
                    assertEquals(1, artifact.snapshot.effectiveBlockRuleCount)
                }
            } finally {
                prepared.close()
            }
        } }

    @Test
    fun `compiler admits FOSS manual allows beyond Plus quota under the total rule budget`() =
        withTemporaryDirectory { directory -> runBlocking {
            val compiler = FilterCompiler(directory)
            val accepted = compiler.compile(
                request(emptyList(), maxRules = 1_000).copy(
                    manualAllowRules = manualAllowRules(1_000),
                ),
            )
            try {
                assertEquals(1_000, accepted.identity.ruleCounts.allowSuffix)
            } finally {
                accepted.close()
            }

            assertCompileError(FilterCompileError.RULE_LIMIT_EXCEEDED) {
                compiler.compile(
                    request(emptyList(), maxRules = 1_000).copy(
                        manualAllowRules = manualAllowRules(1_001),
                    ),
                )
            }
            val expanded = compiler.compile(request(emptyList(), maxRules = 1_003).copy(
                manualAllowRules = manualAllowRules(1_001),
                manualBlockRules = listOf(DomainRule.parse("child.allow-1000.example"), DomainRule.parse("unrelated.example")),
            ))
            try {
                FilterArtifact.open(expanded.file, expanded.sha256, expanded.identity).use { artifact ->
                    assertEquals(1_001, artifact.snapshot.allowRuleCount)
                    assertEquals(FilterDecisionReason.LOCAL_ALLOWLIST, artifact.snapshot.decide("child.allow-1000.example").reason)
                    assertEquals(1, artifact.snapshot.effectiveBlockRuleCount)
                }
            } finally { expanded.close() }
        } }

    @Test
    fun `artifact decode accepts FOSS allows beyond Plus quota and keeps total safety ceiling`() =
        withTemporaryDirectory { directory ->
            val domains = (0 until 1_001).map { index ->
                "allow-${index.toString().padStart(4, '0')}.example"
            }
            val tables = List(6) { index -> File(directory, "table-$index.tbl") }
            tables.forEach { writeRuleTable(it, emptyList()) }
            writeRuleTable(tables[3], domains)
            val counts = intArrayOf(0, 0, 0, domains.size, 0, 0)
            val identity = FilterArtifactIdentity(
                configuration = ArtifactConfigurationIdentity(7, "decoder-ceiling"),
                selectedSources = emptyList(),
                catalogRevision = "decoder-ceiling",
                catalogSha256 = "88e15e8fc80092830d58da54fe01897327b24dd689fc45090f6734e3ae9155b0",
                artifactSchema = FilterArtifact.ARTIFACT_SCHEMA,
                pointerSchema = FilterArtifact.POINTER_SCHEMA,
                parserRulesVersion = BlocklistParser.RULES_VERSION,
                ruleCounts = ArtifactRuleCounts(0, 0, 0, domains.size, 0, 0),
                ruleTablesSha256 = FilterArtifact.hashRuleTables(tables, counts),
                compileInputsSha256 = "d".repeat(64),
                intentionalEmpty = false,
            )
            val artifact = File(directory, "over-limit.lsfa")
            FilterArtifact.write(artifact, identity, tables)

            val result = FilterArtifact.openResult(artifact, FilterArtifact.sha256(artifact))

            assertTrue(result is FilterArtifactOpenResult.Available)
            (result as FilterArtifactOpenResult.Available).artifact.use { opened ->
                assertEquals(1_001, opened.snapshot.allowRuleCount)
                assertEquals(FilterDecisionReason.LOCAL_ALLOWLIST, opened.snapshot.decide("child.allow-1000.example").reason)
                assertEquals(0, opened.snapshot.effectiveBlockRuleCount)
            }
            // Reject oversized declared tables before allocating indexes or traversing content.
            val oversized = identity.copy(ruleCounts = ArtifactRuleCounts(0, 0, 0, FilterCompiler.MAX_SOURCE_RULES + 1, 0, 0))
            FilterArtifact.write(artifact, oversized, tables)
            val rejected = FilterArtifact.openResult(artifact, FilterArtifact.sha256(artifact))
            assertTrue(rejected is FilterArtifactOpenResult.Unavailable)
            assertEquals(FilterArtifactUnavailableReason.RULE_TABLE_INVALID,
                (rejected as FilterArtifactOpenResult.Unavailable).reason)
        }

    @Test
    fun `artifact decode rejects malformed compiler commitments`() =
        withTemporaryDirectory { directory ->
            val tables = List(6) { index -> File(directory, "empty-table-$index.tbl") }
            tables.forEach { writeRuleTable(it, emptyList()) }
            val counts = IntArray(6)
            for ((index, malformedCommitment) in listOf("a".repeat(63), "g".repeat(64)).withIndex()) {
                val identity = FilterArtifactIdentity(
                    configuration = ArtifactConfigurationIdentity(7, "malformed-receipt"),
                    selectedSources = emptyList(),
                    catalogRevision = "malformed-receipt",
                    catalogSha256 = "a".repeat(64),
                    artifactSchema = FilterArtifact.ARTIFACT_SCHEMA,
                    pointerSchema = FilterArtifact.POINTER_SCHEMA,
                    parserRulesVersion = BlocklistParser.RULES_VERSION,
                    ruleCounts = ArtifactRuleCounts(0, 0, 0, 0, 0, 0),
                    ruleTablesSha256 = FilterArtifact.hashRuleTables(tables, counts),
                    compileInputsSha256 = malformedCommitment,
                    intentionalEmpty = true,
                )
                val artifact = File(directory, "malformed-receipt-$index.lsfa")
                FilterArtifact.write(artifact, identity, tables)

                val result = FilterArtifact.openResult(artifact, FilterArtifact.sha256(artifact))

                assertTrue(result is FilterArtifactOpenResult.Unavailable)
                assertEquals(
                    FilterArtifactUnavailableReason.DECODE_INVALID,
                    (result as FilterArtifactOpenResult.Unavailable).reason,
                )
            }
        }

    @Test
    fun `compiler deduplicates all source rules including service domains and is deterministic`() =
        withTemporaryDirectory { directory -> runBlocking {
            val sourceA = source("a", "dup.example\napi.lavasecurity.app\nonly-a.example\n")
            val sourceB = source("b", "dup.example\nsub.api.lavasecurity.app\nonly-b.example\n")
            val request = request(listOf(sourceA, sourceB))
            val compiler = FilterCompiler(directory)

            val first = compiler.compile(request)
            val second = compiler.compile(request)
            try {
                assertEquals(first.sha256, second.sha256)
                assertArrayEquals(first.file.readBytes(), second.file.readBytes())
                assertEquals(5, first.blockRuleCount)
                FilterArtifact.open(first.file, first.sha256).use { artifact ->
                    assertEquals(FilterDecisionReason.BLOCKLIST, artifact.snapshot.decide("x.dup.example").reason)
                    assertEquals(FilterDecisionReason.BLOCKLIST, artifact.snapshot.decide("api.lavasecurity.app").reason)
                    assertEquals(FilterDecisionReason.BLOCKLIST, artifact.snapshot.decide("sub.api.lavasecurity.app").reason)
                }
            } finally {
                first.close()
                second.close()
            }
        } }

    @Test
    fun `compiler bounds merge fan-in and deletes consumed runs for duplicate-heavy input`() =
        withTemporaryDirectory { directory -> runBlocking {
            val peakReaders = AtomicInteger()
            val compiler = FilterCompiler(
                scratchRoot = directory,
                chunkRuleLimit = 1,
                mergeFanIn = 3,
                mergeObserver = FilterMergeResourceObserver { readers ->
                    peakReaders.updateAndGet { maxOf(it, readers) }
                },
            )
            val duplicateHeavy = "duplicate.example\n".repeat(70)
            val compileRequest = request(listOf(source("duplicates", duplicateHeavy)))

            val first = compiler.compile(compileRequest)
            val second = compiler.compile(compileRequest)
            try {
                assertEquals(1, first.blockRuleCount)
                assertEquals(first.sha256, second.sha256)
                assertEquals(3, peakReaders.get())
                assertTrue(
                    first.file.parentFile!!.walkTopDown().none {
                        it.name.startsWith("run-") || ".merge-pass-" in it.name
                    },
                )
            } finally {
                first.close()
                second.close()
            }
        } }

    @Test
    fun `all former service exemptions remain blocked after compile and artifact reopen`() =
        withTemporaryDirectory { directory -> runBlocking {
            val protected = listOf("apple.com", "icloud.com", "mzstatic.com", "itunes.apple.com", "apps.apple.com", "lavasecurity.com", "lavasecurity.app", "api.lavasecurity.app", "lavasec.app", "lavasec.example", "accounts.google.com", "google.com")
            val duplicated = protected.flatMap { listOf(it, "child.$it") }.joinToString("\n", postfix = "\n")
            val prepared = FilterCompiler(directory).compile(
                request(listOf(source("a", duplicated + "kept.example\n"), source("b", duplicated + "kept.example\n"))),
            )
            try {
                assertEquals(protected.flatMap { listOf(it, "child.$it") }.toSet().size + 1, prepared.blockRuleCount)
                assertEquals(5, prepared.identity.parserRulesVersion)
                FilterArtifact.open(prepared.file, prepared.sha256).use { artifact ->
                    for (domain in protected) {
                        assertEquals(domain, FilterDecisionReason.BLOCKLIST, artifact.snapshot.decide(domain).reason)
                        assertEquals("x.$domain", FilterDecisionReason.BLOCKLIST, artifact.snapshot.decide("x.$domain").reason)
                    }
                    assertEquals(FilterDecisionReason.BLOCKLIST, artifact.snapshot.decide("kept.example").reason)
                }
            } finally {
                prepared.close()
            }
        } }

    @Test
    fun `exact and suffix collisions remain separate compiled entries`() =
        withTemporaryDirectory { directory -> runBlocking {
            val prepared = FilterCompiler(directory).compile(
                request(listOf(source("a", "collision.example\n")), maxRules = 2).copy(
                    manualBlockRules = listOf(DomainRule.parse("collision.example", matchesSubdomains = false)),
                ),
            )
            try {
                assertEquals(1, prepared.identity.ruleCounts.blockExact)
                assertEquals(1, prepared.identity.ruleCounts.blockSuffix)
                assertEquals(2, prepared.blockRuleCount)
            } finally {
                prepared.close()
            }
        } }

    @Test
    fun `compiler enforces exact tier boundary missing source payload size and intentional empty`() =
        withTemporaryDirectory { directory -> runBlocking {
            val compiler = FilterCompiler(directory, chunkRuleLimit = 2)
            val atLimit = request(listOf(source("a", "a.example\nb.example\n")), maxRules = 2)
            compiler.compile(atLimit).close()
            compiler.compile(
                request(listOf(source("a", "a.example\n")), maxRules = 1).copy(
                    manualBlockRules = listOf(DomainRule.parse("a.example")),
                ),
            ).close()

            assertCompileError(FilterCompileError.RULE_LIMIT_EXCEEDED) {
                compiler.compile(request(listOf(source("a", "a.example\nb.example\nc.example\n")), maxRules = 2))
            }
            assertCompileError(FilterCompileError.MISSING_SOURCE, "a") {
                compiler.compile(request(listOf(source("a", "a.example\n", available = false))))
            }
            assertCompileError(FilterCompileError.SOURCE_TOO_LARGE, "a") {
                compiler.compile(request(listOf(source("a", "a.example\n", declaredBytes = 45L * 1024 * 1024 + 1))))
            }
            assertCompileError(FilterCompileError.SOURCE_TOO_LARGE, "actual-too-large") {
                compiler.compile(request(listOf(FilterSourcePayload(
                    id = "actual-too-large",
                    format = BlocklistFormat.PLAIN_DOMAINS,
                    acceptedSha256 = "0".repeat(64),
                    declaredByteSize = FilterCompiler.MAX_SOURCE_BYTES,
                    openStream = { RepeatingInputStream(FilterCompiler.MAX_SOURCE_BYTES + 1) },
                ))))
            }
            assertCompileError(FilterCompileError.SOURCE_SIZE_MISMATCH, "a") {
                compiler.compile(request(listOf(source("a", "a.example\n", declaredBytes = 1))))
            }
            val wrongHash = source("a", "a.example\n").copy(acceptedSha256 = "0".repeat(64))
            assertCompileError(FilterCompileError.SOURCE_DIGEST_MISMATCH, "a") {
                compiler.compile(request(listOf(wrongHash)))
            }
            assertCompileError(FilterCompileError.UNEXPECTED_EMPTY) {
                compiler.compile(request(emptyList(), intentionalEmpty = false))
            }
            compiler.compile(request(emptyList(), intentionalEmpty = true)).close()
            assertEquals(500_000, FilterCompiler.FREE_RULE_CAP)
            assertEquals(2_000_000, FilterCompiler.PLUS_RULE_CAP)
            try {
                compiler.compile(request(listOf(source("a", "a.example\n")), maxRules = 2_000_001))
                fail("future Plus cap + 1 must be rejected")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        } }

    @Test
    fun `each enabled curated source contributes valid rules including service domains`() =
        withTemporaryDirectory { directory -> runBlocking {
            val compiler = FilterCompiler(directory)
            val good = source("good", "good.example\n")
            val empty = source("empty", "")
            val protectedOnly = source("protected", "apple.com\nchild.google.com\n")
            val wrongFormatBytes = "plain-but-declared-hosts.example\n".encodeToByteArray()
            val wrongFormat = FilterSourcePayload(
                id = "wrong-format",
                format = BlocklistFormat.HOSTS,
                acceptedSha256 = sha256(wrongFormatBytes),
                declaredByteSize = wrongFormatBytes.size.toLong(),
                openStream = { ByteArrayInputStream(wrongFormatBytes) },
            )

            assertCompileError(FilterCompileError.UNEXPECTED_EMPTY, "empty") {
                compiler.compile(request(listOf(good, empty)))
            }
            val services = compiler.compile(request(listOf(good, protectedOnly)))
            try {
                assertEquals(3, services.blockRuleCount)
                FilterArtifact.open(services.file, services.sha256).use { artifact ->
                    assertEquals(FilterDecisionReason.BLOCKLIST, artifact.snapshot.decide("apple.com").reason)
                    assertEquals(FilterDecisionReason.BLOCKLIST, artifact.snapshot.decide("child.google.com").reason)
                }
            } finally { services.close() }
            assertCompileError(FilterCompileError.UNEXPECTED_EMPTY, "wrong-format") {
                compiler.compile(
                    request(listOf(wrongFormat)).copy(
                        manualBlockRules = listOf(DomainRule.parse("manual.example")),
                    ),
                )
            }
            assertCompileError(FilterCompileError.UNEXPECTED_EMPTY, "empty") {
                compiler.compile(request(listOf(empty), intentionalEmpty = true))
            }
        } }

    @Test
    fun `artifact rejects digest corruption ordering counts and identity mismatch`() =
        withTemporaryDirectory { directory -> runBlocking {
            val prepared = FilterCompiler(directory).compile(request(listOf(source("a", "a.example\nb.example\n"))))
            try {
                val wrongIdentity = prepared.identity.copy(
                    configuration = ArtifactConfigurationIdentity(99, "different"),
                )
                assertNull(FilterArtifact.openOrNull(prepared.file, prepared.sha256, wrongIdentity))

                val corrupt = File(directory, "corrupt.artifact")
                corrupt.writeBytes(prepared.file.readBytes().also { bytes -> bytes[bytes.lastIndex] = (bytes.last() + 1).toByte() })
                assertNull(FilterArtifact.openOrNull(corrupt, prepared.sha256, prepared.identity))

                val malformed = File(directory, "malformed.artifact")
                malformed.writeBytes(prepared.file.readBytes().also { bytes ->
                    val tableOffset = FilterArtifact.testingFirstTableOffset(bytes)
                    bytes[tableOffset] = 0x7f
                    bytes[tableOffset + 1] = 0x7f
                    bytes[tableOffset + 2] = 0x7f
                    bytes[tableOffset + 3] = 0x7f
                })
                assertNull(FilterArtifact.openOrNull(malformed, sha256(malformed.readBytes()), prepared.identity))
            } finally {
                prepared.close()
            }
        } }

    @Test
    fun `open or null rethrows cancellation from its validation checkpoint`() =
        withTemporaryDirectory { directory -> runBlocking {
            val prepared = FilterCompiler(directory).compile(request(listOf(source("a", "a.example\n"))))
            try {
                try {
                    FilterArtifact.openOrNull(
                        prepared.file,
                        prepared.sha256,
                        prepared.identity,
                        digestCheckpoint = { throw CancellationException("cancelled artifact open") },
                    )
                    fail("openOrNull swallowed cancellation")
                } catch (error: CancellationException) {
                    assertEquals("cancelled artifact open", error.message)
                }
            } finally {
                prepared.close()
            }
        } }

    @Test
    fun `gate coalesces same key cancels superseded compile fences stale publish and recovers`() = runBlocking {
        val gate = FilterCompileGate()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val publishes = mutableListOf<String>()
        val firstStarted = CompletableDeferred<Unit>()

        suspend fun submit(key: String, pause: Long = 0) = gate.submit(
            key = key,
            compile = {
                val now = active.incrementAndGet()
                maxActive.updateAndGet { maxOf(it, now) }
                try {
                    if (key == "a") firstStarted.complete(Unit)
                    delay(pause)
                    key
                } finally {
                    active.decrementAndGet()
                }
            },
            publish = { value, fence ->
                assertTrue(fence.commitIfCurrent { synchronized(publishes) { publishes += value } })
            },
        )

        val first = async { runCatching { submit("a", 10_000) } }
        firstStarted.await()
        val newer = async { submit("b") }
        newer.await()
        assertTrue(first.await().exceptionOrNull() is CancellationException)
        assertEquals(listOf("b"), publishes)
        assertEquals(1, maxActive.get())

        val compileCalls = AtomicInteger()
        val sameA = async { gate.submit("same", { compileCalls.incrementAndGet(); delay(20); "ok" }, { _, _ -> }) }
        val sameB = async { gate.submit("same", { compileCalls.incrementAndGet(); "bad" }, { _, _ -> }) }
        sameA.await(); sameB.await()
        assertEquals(1, compileCalls.get())

        try {
            gate.submit("failure", { throw IllegalStateException("boom") }, { _, _ -> })
            fail("failure must propagate")
        } catch (_: IllegalStateException) {
            // Expected.
        }
        gate.submit("recovery", { "recovered" }, { value, fence ->
            assertTrue(fence.commitIfCurrent { publishes += value })
        })
        assertTrue("recovered" in publishes)
    }

    @Test
    fun `superseding invalidation wins an injected race before pointer commit`() = runBlocking {
        repeat(25) {
        val gate = FilterCompileGate()
        val oldAtCommitBoundary = CompletableDeferred<Unit>()
        val releaseOldCommit = CompletableDeferred<Unit>()
        val publications = mutableListOf<String>()

        val old = async {
            runCatching {
                gate.submit(
                    key = "old",
                    compile = { "old" },
                    publish = { value, fence ->
                        withContext(NonCancellable) {
                            oldAtCommitBoundary.complete(Unit)
                            releaseOldCommit.await()
                            assertFalse(fence.commitIfCurrent { publications += value })
                        }
                    },
                )
            }
        }
        oldAtCommitBoundary.await()
        val newer = async(start = CoroutineStart.UNDISPATCHED) {
            gate.submit(
                key = "new",
                compile = { "new" },
                publish = { value, fence -> assertTrue(fence.commitIfCurrent { publications += value }) },
            )
        }
        releaseOldCommit.complete(Unit)

        assertEquals("new", newer.await())
        assertTrue(old.await().exceptionOrNull() is CancellationException)
        assertEquals(listOf("new"), publications)
        gate.close()
        }
    }

    @Test
    fun `cancelling one coalesced caller invalidates shared work and cannot publish or wedge next submit`() = runBlocking {
        val gate = FilterCompileGate()
        val compileStarted = CompletableDeferred<Unit>()
        val releaseCancelledCompile = CompletableDeferred<Unit>()
        val publications = mutableListOf<String>()

        suspend fun sharedSubmit() = gate.submit(
            key = "shared",
            compile = {
                compileStarted.complete(Unit)
                withContext(NonCancellable) { releaseCancelledCompile.await() }
                "shared"
            },
            publish = { value, fence ->
                assertFalse(fence.commitIfCurrent { publications += value })
            },
        )

        val first = async { runCatching { sharedSubmit() } }
        compileStarted.await()
        val cancelledCoalesced = async(start = CoroutineStart.UNDISPATCHED) { runCatching { sharedSubmit() } }
        cancelledCoalesced.cancel(CancellationException("caller left"))
        releaseCancelledCompile.complete(Unit)
        cancelledCoalesced.join()

        assertTrue(first.await().exceptionOrNull() is CancellationException)
        assertTrue(publications.isEmpty())
        assertEquals(
            "next",
            gate.submit(
                key = "next",
                compile = { "next" },
                publish = { value, fence -> assertTrue(fence.commitIfCurrent { publications += value }) },
            ),
        )
        assertEquals(listOf("next"), publications)
        gate.close()
    }

    @Test
    fun `caller cancellation at commit boundary invalidates fence before noncancellable publish resumes`() = runBlocking {
        repeat(25) {
        val gate = FilterCompileGate()
        val publishAtCommitBoundary = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        val publications = mutableListOf<String>()

        val caller = async {
            gate.submit(
                key = "cancelled",
                compile = { "cancelled" },
                publish = { value, fence ->
                    withContext(NonCancellable) {
                        publishAtCommitBoundary.complete(Unit)
                        releaseCommit.await()
                        assertFalse(fence.commitIfCurrent { publications += value })
                    }
                },
            )
        }
        publishAtCommitBoundary.await()
        caller.cancel(CancellationException("caller left at commit boundary"))
        releaseCommit.complete(Unit)
        caller.join()

        assertTrue(publications.isEmpty())
        assertEquals(
            "recovered",
            gate.submit(
                key = "recovered",
                compile = { "recovered" },
                publish = { value, fence -> assertTrue(fence.commitIfCurrent { publications += value }) },
            ),
        )
        assertEquals(listOf("recovered"), publications)
        gate.close()
        }
    }

    @Test
    fun `cancelled compiler cleans scratch before the next gated body starts`() =
        withTemporaryDirectory { directory -> runBlocking {
            val gate = FilterCompileGate()
            val started = CompletableDeferred<Unit>()
            val line = "never-ending.example\n".encodeToByteArray()
            val endless = object : InputStream() {
                var index = 0
                override fun read(): Int {
                    started.complete(Unit)
                    return line[index++ % line.size].toInt() and 0xff
                }
            }
            val request = request(listOf(FilterSourcePayload(
                id = "endless",
                format = BlocklistFormat.PLAIN_DOMAINS,
                acceptedSha256 = "0".repeat(64),
                declaredByteSize = FilterCompiler.MAX_SOURCE_BYTES,
                openStream = { endless },
            )))
            val first = async {
                runCatching {
                    gate.submit(
                        "old",
                        compile = { FilterCompiler(directory).compile(request) },
                        publish = { _, _ -> fail("cancelled compile published") },
                    )
                }
            }
            started.await()
            gate.submit("new", compile = { "new" }, publish = { _, fence ->
                assertTrue(fence.commitIfCurrent { })
            })
            assertTrue(first.await().exceptionOrNull() is CancellationException)
            assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith("compile-") })
            gate.close()
        } }

    @Test
    fun `compiler cancellation checkpoints cover every long artifact and manual-rule phase`() =
        withTemporaryDirectory { directory -> runBlocking {
            val manualRules = (0 until 6_000).map { index ->
                DomainRule.parse("manual-${index.toString().padStart(5, '0')}.example")
            }
            val compileRequest = request(emptyList(), maxRules = manualRules.size).copy(
                manualBlockRules = manualRules,
            )

            for (target in FilterCompilePhase.entries) {
                var targetHits = 0
                val compiler = FilterCompiler(
                    scratchRoot = directory,
                    checkpointObserver = FilterCompileCheckpointObserver { reached ->
                        if (reached == target && ++targetHits == 2) {
                            throw CancellationException("cancel at $target")
                        }
                    },
                )
                try {
                    compiler.compile(compileRequest)
                    fail("$target did not cancel")
                } catch (error: CancellationException) {
                    assertTrue(error.message.orEmpty().contains(target.name))
                }
                assertEquals("$target checkpoint sampling", 2, targetHits)
                assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith("compile-") })
            }

            FilterCompiler(directory).compile(
                request(emptyList(), maxRules = 1).copy(
                    manualBlockRules = listOf(DomainRule.parse("recovered.example")),
                ),
            ).close()
        } }

    private fun request(
        sources: List<FilterSourcePayload>,
        maxRules: Int = 500_000,
        intentionalEmpty: Boolean = false,
    ) = FilterCompileRequest(
        configurationIdentity = ArtifactConfigurationIdentity(7, "config-fingerprint"),
        selectedSources = sources,
        catalogRevision = "pinned-revision",
        catalogSha256 = "88e15e8fc80092830d58da54fe01897327b24dd689fc45090f6734e3ae9155b0",
        maxRules = maxRules,
        intentionalEmpty = intentionalEmpty,
    )

    private fun source(
        id: String,
        text: String,
        available: Boolean = true,
        declaredBytes: Long = text.encodeToByteArray().size.toLong(),
    ): FilterSourcePayload {
        val bytes = text.encodeToByteArray()
        return FilterSourcePayload(
            id = id,
            format = BlocklistFormat.PLAIN_DOMAINS,
            acceptedSha256 = sha256(bytes),
            declaredByteSize = declaredBytes,
            openStream = if (available) ({ ByteArrayInputStream(bytes) }) else null,
        )
    }

    private suspend fun assertCompileError(
        reason: FilterCompileError,
        sourceId: String? = null,
        body: suspend () -> Unit,
    ) {
        try {
            body()
            fail("expected $reason")
        } catch (error: FilterCompileException) {
            assertEquals(reason, error.reason)
            assertEquals(sourceId, error.sourceId)
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun manualAllowRules(count: Int): List<DomainRule> = (0 until count).map { index ->
        DomainRule.parse("allow-${index.toString().padStart(4, '0')}.example")
    }

    private fun writeRuleTable(file: File, domains: List<String>) {
        DataOutputStream(file.outputStream().buffered()).use { output ->
            for (domain in domains.sorted()) {
                val bytes = domain.encodeToByteArray()
                output.writeShort(bytes.size)
                output.write(bytes)
            }
        }
    }

    private inline fun withTemporaryDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("filter-artifact-test-").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }

    private class RepeatingInputStream(private var remaining: Long) : InputStream() {
        override fun read(): Int = if (remaining-- > 0) 'x'.code else -1

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            val count = minOf(length.toLong(), remaining).toInt()
            buffer.fill('x'.code.toByte(), offset, offset + count)
            remaining -= count
            return count
        }
    }
}
