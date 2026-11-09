package com.lavasec.app.foundation.dns

import com.lavasec.app.foundation.filter.DomainRule
import com.lavasec.app.foundation.filter.DomainRuleSet
import com.lavasec.app.foundation.filter.ArtifactConfigurationIdentity
import com.lavasec.app.foundation.filter.BlocklistFormat
import com.lavasec.app.foundation.filter.DomainFilterDecisionSource
import com.lavasec.app.foundation.filter.FilterCompileRequest
import com.lavasec.app.foundation.filter.FilterCompiler
import com.lavasec.app.foundation.filter.FilterDecisionReason
import com.lavasec.app.foundation.filter.FilterSourcePayload
import com.lavasec.app.foundation.filter.FilterSnapshot
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsDecisionTest {
    private val snapshot = FilterSnapshot(
        blockRules = DomainRuleSet.of(DomainRule.parse("ads.example.com")),
    )

    @Test
    fun `decision core forwards allows and synthesizes blocked answers`() {
        val allowed = DnsDecision.evaluate(dnsQuery("apple.com"), snapshot) as DnsDecision.Forward
        assertEquals("apple.com", allowed.question.normalizedDomain)
        assertEquals(FilterDecisionReason.DEFAULT_ALLOW, allowed.filterDecision.reason)

        val query = dnsQuery("ads.example.com")
        val blocked = DnsDecision.evaluate(query, snapshot) as DnsDecision.Respond
        assertEquals(FilterDecisionReason.BLOCKLIST, blocked.filterDecision.reason)
        assertArrayEquals(DnsMessage.blockedResponse(query), blocked.response)
    }

    @Test
    fun `pre-parsed question preserves allow and block semantics`() {
        for (domain in listOf("apple.com", "ads.example.com")) {
            val query = dnsQuery(domain)
            val question = DnsMessage.parseQuestion(query)

            val actual = DnsDecision.evaluate(query, question, snapshot)

            assertEquals(
                decisionFingerprint(DnsDecision.evaluate(query, snapshot)),
                decisionFingerprint(actual),
            )
            val actualQuestion = when (actual) {
                is DnsDecision.Forward -> actual.question
                is DnsDecision.Respond -> actual.question
                is DnsDecision.Failure -> error("valid question unexpectedly failed")
            }
            assertSame(question, actualQuestion)
        }
    }

    @Test
    fun `pre-parsed question from different query becomes SERVFAIL without filtering`() {
        val query = dnsQuery("apple.com")
        val foreignQuestion = DnsMessage.parseQuestion(dnsQuery("other.example"))
        val countingSource = CountingDecisionSource(snapshot)

        val failure = DnsDecision.evaluate(query, foreignQuestion, countingSource) as DnsDecision.Failure

        assertEquals(DnsMessageError.MALFORMED_QUESTION, failure.error.reason)
        assertNotNull(failure.response)
        assertEquals(2, failure.response!!.readUInt16(2) and 0x000f)
        assertEquals(0, countingSource.decisionCalls)
    }

    @Test
    fun `pre-parsed valid decision consults filter source exactly once`() {
        val query = dnsQuery("ads.example.com")
        val countingSource = CountingDecisionSource(snapshot)

        DnsDecision.evaluate(query, DnsMessage.parseQuestion(query), countingSource)

        assertEquals(1, countingSource.decisionCalls)
    }

    @Test
    fun `in-memory and mapped decision sources produce identical DNS decisions and precedence`() =
        withMappedDecisionSources { memory, mapped ->
            val queries = listOf(
                dnsQuery("threat.ads.example.com"),
                dnsQuery("trusted.ads.example.com"),
                dnsQuery("cdn.ads.example.com"),
                dnsQuery("apple.com"),
            )

            for (query in queries) {
                assertEquals(
                    decisionFingerprint(DnsDecision.evaluate(query, memory)),
                    decisionFingerprint(DnsDecision.evaluate(query, mapped)),
                )
            }
        }

    @Test
    fun `DNS evaluation borrows a mapped source without closing its artifact lease`() =
        withMappedDecisionSources { _, mapped, artifact ->
            assertTrue(artifact.channelIsOpenForTesting)
            val decision = DnsDecision.evaluate(
                dnsQuery("cdn.ads.example.com"),
                mapped,
            ) as DnsDecision.Respond
            assertEquals(FilterDecisionReason.BLOCKLIST, decision.filterDecision.reason)
            assertTrue(artifact.channelIsOpenForTesting)
        }

    @Test
    fun `decision response byte arrays are defensive at the public boundary`() {
        val query = dnsQuery("ads.example.com")
        val blocked = DnsDecision.evaluate(query, snapshot) as DnsDecision.Respond
        val expected = blocked.response.copyOf()
        val exposed = blocked.response
        exposed[0] = (exposed[0].toInt() xor 0xff).toByte()
        assertArrayEquals(expected, blocked.response)

        val failure = DnsDecision.evaluate(dnsQuery("localhost"), snapshot) as DnsDecision.Failure
        val expectedFailure = failure.response!!.copyOf()
        val exposedFailure = failure.response!!
        exposedFailure[0] = (exposedFailure[0].toInt() xor 0xff).toByte()
        assertArrayEquals(expectedFailure, failure.response)

        val headerless = DnsDecision.evaluate(byteArrayOf(0x12, 0x34, 0x01), snapshot) as DnsDecision.Failure
        assertEquals(null, headerless.response)
    }

    @Test
    fun `wire-invalid domain becomes SERVFAIL rather than an invalid-domain filter block`() {
        val invalidDomain = dnsQuery("localhost")
        val decision = DnsDecision.evaluate(invalidDomain, snapshot) as DnsDecision.Failure

        assertEquals(DnsMessageError.INVALID_DOMAIN, decision.error.reason)
        assertNotNull(decision.response)
        assertEquals(2, decision.response!!.readUInt16(2) and 0x000f)
    }

    @Test
    fun `wire labels that normalize to a different domain receive SERVFAIL before filtering`() {
        val cases = listOf(
            "leading whitespace" to listOf(" ads", "example", "com"),
            "trailing whitespace" to listOf("ads", "example", "com "),
            "literal dot" to listOf("ads.example", "com"),
            "ideographic full stop" to listOf("ads\u3002example", "com"),
            "fullwidth full stop" to listOf("ads\uff0eexample", "com"),
            "halfwidth ideographic full stop" to listOf("ads\uff61example", "com"),
        )

        for ((name, labels) in cases) {
            val failure = DnsDecision.evaluate(dnsQueryFromLabels(labels), snapshot) as DnsDecision.Failure
            assertEquals(name, DnsMessageError.MALFORMED_QUESTION, failure.error.reason)
            assertNotNull(name, failure.response)
            assertEquals(name, 2, failure.response!!.readUInt16(2) and 0x000f)
        }
    }

    @Test
    fun `legitimate case and IDNA normalization remains forwardable`() {
        val decision = DnsDecision.evaluate(
            dnsQueryFromLabels(listOf("Bücher", "Example")),
            snapshot,
        ) as DnsDecision.Forward

        assertEquals("xn--bcher-kva.example", decision.question.normalizedDomain)
        assertEquals(FilterDecisionReason.DEFAULT_ALLOW, decision.filterDecision.reason)
    }

    @Test
    fun `deterministic 10000-input mutation corpus matches the pinned result on repeated runs`() {
        val corpus = mutationCorpus(count = 10_000, seed = 0x5EED_C0DEL)
        val expected = CorpusResult(
            total = 10_000,
            forward = 195,
            respond = 428,
            failure = 9_377,
            digest = -805_418_180,
        )

        assertEquals(expected, classify(corpus))
        assertEquals(expected, classify(corpus))
    }

    private fun classify(corpus: List<ByteArray>): CorpusResult {
        var digest = 1
        var forward = 0
        var respond = 0
        var failure = 0
        for (query in corpus) {
            when (val decision = DnsDecision.evaluate(query, snapshot)) {
                is DnsDecision.Forward -> {
                    forward++
                    digest = 31 * digest + decision.question.rawRecordType
                }
                is DnsDecision.Respond -> {
                    respond++
                    digest = 31 * digest + decision.response.contentHashCode()
                }
                is DnsDecision.Failure -> {
                    failure++
                    digest = 31 * digest + decision.error.reason.ordinal
                    digest = 31 * digest + (decision.response?.contentHashCode() ?: 0)
                }
            }
        }
        return CorpusResult(corpus.size, forward, respond, failure, digest)
    }

    private fun decisionFingerprint(decision: DnsDecision): List<Any?> = when (decision) {
        is DnsDecision.Forward -> listOf(
            "forward",
            decision.question.normalizedDomain,
            decision.filterDecision.action,
            decision.filterDecision.reason,
        )
        is DnsDecision.Respond -> listOf(
            "respond",
            decision.question.normalizedDomain,
            decision.filterDecision.action,
            decision.filterDecision.reason,
            decision.response.toList(),
        )
        is DnsDecision.Failure -> listOf(
            "failure",
            decision.error.reason,
            decision.response?.toList(),
        )
    }

    private fun withMappedDecisionSources(
        block: (FilterSnapshot, DomainFilterDecisionSource) -> Unit,
    ) = withMappedDecisionSources { memory, mapped, _ -> block(memory, mapped) }

    private fun withMappedDecisionSources(
        block: (FilterSnapshot, DomainFilterDecisionSource, com.lavasec.app.foundation.filter.FilterArtifact) -> Unit,
    ) {
        val directory = Files.createTempDirectory("dns-decision-mapped-").toFile()
        val sourceBytes = "ads.example.com\n".encodeToByteArray()
        val memory = FilterSnapshot(
            blockRules = DomainRuleSet.of(DomainRule.parse("ads.example.com")),
            allowRules = DomainRuleSet.of(DomainRule.parse("trusted.ads.example.com")),
            nonAllowableThreatRules = DomainRuleSet.of(DomainRule.parse("threat.ads.example.com")),
        )
        val prepared = runBlocking {
            FilterCompiler(File(directory, "scratch")).compile(
                FilterCompileRequest(
                    configurationIdentity = ArtifactConfigurationIdentity(1, "dns-decision"),
                    selectedSources = listOf(
                        FilterSourcePayload(
                            id = "source",
                            format = BlocklistFormat.PLAIN_DOMAINS,
                            acceptedSha256 = sha256(sourceBytes),
                            declaredByteSize = sourceBytes.size.toLong(),
                            openStream = { ByteArrayInputStream(sourceBytes) },
                        ),
                    ),
                    catalogRevision = "catalog",
                    catalogSha256 = "8".repeat(64),
                    maxRules = 10,
                    manualAllowRules = listOf(DomainRule.parse("trusted.ads.example.com")),
                    guardrailRules = listOf(DomainRule.parse("threat.ads.example.com")),
                ),
            )
        }
        try {
            com.lavasec.app.foundation.filter.FilterArtifact.open(prepared.file, prepared.sha256).use { artifact ->
                block(memory, artifact.snapshot, artifact)
            }
        } finally {
            prepared.close()
            directory.deleteRecursively()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private class CountingDecisionSource(
        private val delegate: DomainFilterDecisionSource,
    ) : DomainFilterDecisionSource {
        var decisionCalls = 0
            private set

        override val blockRuleCount: Int get() = delegate.blockRuleCount
        override val allowRuleCount: Int get() = delegate.allowRuleCount
        override val guardrailRuleCount: Int get() = delegate.guardrailRuleCount

        override fun decide(rawDomain: String) = delegate.decide(rawDomain).also { decisionCalls++ }

        override fun decideNormalized(normalizedDomain: String) =
            delegate.decideNormalized(normalizedDomain).also { decisionCalls++ }
    }

    private fun mutationCorpus(count: Int, seed: Long): List<ByteArray> {
        val random = Random(seed)
        val base = dnsQuery("ads.example.com")
        return List(count) { index ->
            when (index % 5) {
                0 -> base.copyOf().also { it[random.nextInt(it.size)] = random.nextInt(256).toByte() }
                1 -> base.copyOf(random.nextInt(base.size + 1))
                2 -> base.copyOf(base.size + random.nextInt(33)).also { bytes ->
                    for (offset in base.size until bytes.size) bytes[offset] = random.nextInt(256).toByte()
                }
                3 -> Random(random.nextLong()).nextBytes(random.nextInt(301))
                else -> base.copyOf(DnsMessage.MAX_MESSAGE_BYTES + 1)
            }
        }
    }

    private data class CorpusResult(
        val total: Int,
        val forward: Int,
        val respond: Int,
        val failure: Int,
        val digest: Int,
    )
}
