package com.lavasec.app.foundation.filter

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.ArrayList
import java.util.PriorityQueue
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

data class FilterSourcePayload(
    val id: String,
    val format: BlocklistFormat,
    val acceptedSha256: String,
    val declaredByteSize: Long,
    val openStream: (() -> InputStream)?,
    /** Set by the native source authority, never inferred from a user-controlled identifier. */
    val isCustom: Boolean = false,
)

data class FilterCompileRequest(
    val configurationIdentity: ArtifactConfigurationIdentity,
    val selectedSources: List<FilterSourcePayload>,
    val catalogRevision: String,
    val catalogSha256: String,
    val maxRules: Int,
    val intentionalEmpty: Boolean = false,
    val manualAllowRules: List<DomainRule> = emptyList(),
    val manualBlockRules: List<DomainRule> = emptyList(),
    val guardrailRules: List<DomainRule> = emptyList(),
)

/**
 * Canonical commitment to every compile input that can change artifact rule semantics.
 *
 * Accepted source digests bind the source bytes, while size limits and stream handles are
 * validation mechanics rather than output semantics. Manual and guardrail rule order and
 * duplication are canonicalized because compilation sorts and deduplicates them.
 */
fun canonicalFilterCompileInputsSha256(
    configurationIdentity: ArtifactConfigurationIdentity,
    selectedSources: List<ArtifactSourceIdentity>,
    catalogRevision: String,
    catalogSha256: String,
    intentionalEmpty: Boolean,
    manualAllowRules: List<DomainRule>,
    manualBlockRules: List<DomainRule>,
    guardrailRules: List<DomainRule>,
): String {
    return canonicalSha256("filter-compile-inputs-v2") {
        writeLong(configurationIdentity.generation)
        writeString(configurationIdentity.fingerprint)
        writeInt(selectedSources.size)
        selectedSources.forEach { source ->
            writeString(source.id)
            writeString(source.acceptedSha256.lowercase())
            writeString(source.format.name)
        }
        writeString(catalogRevision)
        writeString(catalogSha256.lowercase())
        writeBoolean(intentionalEmpty)
        writeCanonicalRules(manualAllowRules)
        writeCanonicalRules(manualBlockRules)
        writeCanonicalRules(guardrailRules)
    }
}

private fun CanonicalSha256Encoder.writeCanonicalRules(rules: List<DomainRule>) {
    val canonical = rules.distinct().sortedWith(
        compareBy(DomainRule::domain, DomainRule::matchesSubdomains),
    )
    writeInt(canonical.size)
    canonical.forEach { rule ->
        writeString(rule.domain)
        writeBoolean(rule.matchesSubdomains)
    }
}

enum class FilterCompileError {
    MISSING_SOURCE,
    SOURCE_TOO_LARGE,
    SOURCE_SIZE_MISMATCH,
    SOURCE_DIGEST_MISMATCH,
    SOURCE_RULE_LIMIT_EXCEEDED,
    RULE_LIMIT_EXCEEDED,
    UNEXPECTED_EMPTY,
}

class FilterCompileException(val reason: FilterCompileError, val sourceId: String? = null) :
    IllegalArgumentException(if (sourceId == null) reason.name else "${reason.name}: $sourceId")

/** External-sort compiler that bounds heap independently of accepted payload size. */
class FilterCompiler(
    private val scratchRoot: File,
    private val chunkRuleLimit: Int = 8_192,
    private val memoryObserver: FilterCompileMemoryObserver = FilterCompileMemoryObserver.NONE,
    private val mergeFanIn: Int = DEFAULT_MERGE_FAN_IN,
    private val mergeObserver: FilterMergeResourceObserver = FilterMergeResourceObserver.NONE,
    private val checkpointObserver: FilterCompileCheckpointObserver = FilterCompileCheckpointObserver.NONE,
) {
    init {
        require(chunkRuleLimit > 0)
        require(mergeFanIn >= 2)
    }

    suspend fun compile(
        request: FilterCompileRequest,
        progressObserver: FilterCompileCheckpointObserver = FilterCompileCheckpointObserver.NONE,
    ): PreparedFilterArtifact {
        val orderedSources = request.selectedSources.toList()
        val manualAllowRules = request.manualAllowRules.toList()
        val manualBlockRules = request.manualBlockRules.toList()
        val guardrailRules = request.guardrailRules.toList()
        require(request.maxRules in 0..MAX_SOURCE_RULES)
        // Plan-specific manual-domain quotas are checked by mutation admission. The compiler
        // enforces the same total engine safety bound for allow, block and selected-source rules.
        if (manualAllowRules.size.toLong() + manualBlockRules.size + guardrailRules.size > MAX_SOURCE_RULES) {
            fail(FilterCompileError.RULE_LIMIT_EXCEEDED)
        }
        val compileContext = coroutineContext
        fun checkpoint(phase: FilterCompilePhase) {
            compileContext.ensureActive()
            checkpointObserver.checkpoint(phase)
            progressObserver.checkpoint(phase)
        }
        val memory = MemoryProbe(memoryObserver)
        val work = File(scratchRoot, "compile-${UUID.randomUUID()}")
        check(work.mkdirs()) { "Could not create compile scratch directory" }
        try {
            require(orderedSources.map { it.id }.distinct().size == orderedSources.size)
            val sourceTables = ArrayList<File>(orderedSources.size)
            for ((index, source) in orderedSources.withIndex()) {
                coroutineContext.ensureActive()
                sourceTables += compileSource(source, File(work, "source-$index"), memory)
            }

            memory.sample("sources-complete")
            val selectedSuffix = File(work, "selected-suffix.tbl")
            mergeRuns(sourceTables, selectedSuffix, MAX_SOURCE_RULES)
            val manualExact = writeRules(manualBlockRules, false, File(work, "manual-exact.tbl"), ::checkpoint)
            val manualSuffix = writeRules(manualBlockRules, true, File(work, "manual-suffix.tbl"), ::checkpoint)
            val allowExact = writeRules(manualAllowRules, false, File(work, "allow-exact.tbl"), ::checkpoint)
            val allowSuffix = writeRules(manualAllowRules, true, File(work, "allow-suffix.tbl"), ::checkpoint)
            val guardExact = writeRules(guardrailRules, false, File(work, "guard-exact.tbl"), ::checkpoint)
            val guardSuffix = writeRules(guardrailRules, true, File(work, "guard-suffix.tbl"), ::checkpoint)

            val manualExactCount = countRules(manualExact) { checkpoint(FilterCompilePhase.MANUAL_COUNT) }
            val allowExactCount = countRules(allowExact) { checkpoint(FilterCompilePhase.MANUAL_COUNT) }
            val allowSuffixCount = countRules(allowSuffix) { checkpoint(FilterCompilePhase.MANUAL_COUNT) }
            val guardExactCount = countRules(guardExact) { checkpoint(FilterCompilePhase.MANUAL_COUNT) }
            val guardSuffixCount = countRules(guardSuffix) { checkpoint(FilterCompilePhase.MANUAL_COUNT) }
            val blockSuffix = File(work, "block-suffix.tbl")
            val blockSuffixCount = mergeRuns(listOf(selectedSuffix, manualSuffix), blockSuffix, MAX_SOURCE_RULES)
            val tierCount = blockSuffixCount + manualExactCount +
                allowExactCount + allowSuffixCount + guardExactCount + guardSuffixCount
            if (tierCount > request.maxRules) fail(FilterCompileError.RULE_LIMIT_EXCEEDED)
            val tables = listOf(manualExact, blockSuffix, allowExact, allowSuffix, guardExact, guardSuffix)
            val counts = intArrayOf(
                manualExactCount, blockSuffixCount, allowExactCount, allowSuffixCount, guardExactCount, guardSuffixCount,
            )
            val total = counts.sum()
            require(!request.intentionalEmpty || total == 0) { "Intentional-empty identity cannot contain rules" }
            if (total == 0 && (orderedSources.isEmpty() != request.intentionalEmpty)) {
                fail(FilterCompileError.UNEXPECTED_EMPTY)
            }

            val ruleCounts = ArtifactRuleCounts(
                counts[0], counts[1], counts[2], counts[3], counts[4], counts[5],
            )
            val selectedSourceIdentities = orderedSources.map {
                ArtifactSourceIdentity(it.id, it.acceptedSha256.lowercase(), it.format)
            }
            val identity = FilterArtifactIdentity(
                configuration = request.configurationIdentity,
                selectedSources = selectedSourceIdentities,
                catalogRevision = request.catalogRevision,
                catalogSha256 = request.catalogSha256.lowercase(),
                artifactSchema = FilterArtifact.ARTIFACT_SCHEMA,
                pointerSchema = FilterArtifact.POINTER_SCHEMA,
                parserRulesVersion = BlocklistParser.RULES_VERSION,
                ruleCounts = ruleCounts,
                ruleTablesSha256 = FilterArtifact.hashRuleTables(tables, counts) {
                    checkpoint(FilterCompilePhase.TABLE_HASH)
                },
                compileInputsSha256 = canonicalFilterCompileInputsSha256(
                    configurationIdentity = request.configurationIdentity,
                    selectedSources = selectedSourceIdentities,
                    catalogRevision = request.catalogRevision,
                    catalogSha256 = request.catalogSha256,
                    intentionalEmpty = request.intentionalEmpty,
                    manualAllowRules = manualAllowRules,
                    manualBlockRules = manualBlockRules,
                    guardrailRules = guardrailRules,
                ),
                intentionalEmpty = request.intentionalEmpty,
            )
            val artifact = File(work, "artifact.lsfa")
            FilterArtifact.write(artifact, identity, tables) { checkpoint(FilterCompilePhase.ARTIFACT_WRITE) }
            val digest = FilterArtifact.sha256(artifact) { checkpoint(FilterCompilePhase.ARTIFACT_SHA256) }
            val validation = FilterArtifact.open(
                artifact,
                digest,
                identity,
                digestCheckpoint = { checkpoint(FilterCompilePhase.ARTIFACT_SHA256) },
                decodeCheckpoint = { checkpoint(FilterCompilePhase.ARTIFACT_DECODE) },
            )
            memory.sample("decoded-artifact")
            validation.close()
            return PreparedFilterArtifact(
                artifact,
                digest,
                identity,
                tierCount,
                FilterCompileMemoryMetrics(
                    memory.baseline,
                    memory.peak,
                    counts.sum().toLong() * 6,
                    memory.observedLiveHeap,
                ),
            )
        } catch (error: Throwable) {
            work.deleteRecursively()
            throw error
        }
    }

    private suspend fun compileSource(source: FilterSourcePayload, directory: File, memory: MemoryProbe): File {
        val opener = source.openStream ?: fail(FilterCompileError.MISSING_SOURCE, source.id)
        if (source.declaredByteSize !in 0..MAX_SOURCE_BYTES) fail(FilterCompileError.SOURCE_TOO_LARGE, source.id)
        check(directory.mkdirs())
        val runs = ArrayList<File>()
        val chunk = ArrayList<String>(chunkRuleLimit)
        val digest = MessageDigest.getInstance("SHA-256")
        val raw = opener()
        val counting = CountingInputStream(DigestInputStream(raw, digest), MAX_SOURCE_BYTES, source.id)
        val context = coroutineContext

        fun flushChunk() {
            if (chunk.isEmpty()) return
            val run = File(directory, "run-${runs.size}.tbl")
            writeSortedDomains(chunk, run, context::ensureActive)
            runs += run
            memory.sample("source-chunk")
            chunk.clear()
        }

        val summary = counting.use { input ->
            BlocklistParser(maxDiagnostics = 0).forEachRule(
                input,
                source.format,
                checkpoint = context::ensureActive,
            ) { rule ->
                context.ensureActive()
                // iOS #858: every valid downloaded rule survives ingestion. Explicit allowed
                // exceptions and non-allowable guardrails are applied by the decision owner.
                chunk += rule.domain
                if (chunk.size >= chunkRuleLimit) flushChunk()
            }
        }
        flushChunk()
        if (counting.count != source.declaredByteSize) fail(FilterCompileError.SOURCE_SIZE_MISMATCH, source.id)
        if (digest.hex() != source.acceptedSha256.lowercase()) fail(FilterCompileError.SOURCE_DIGEST_MISMATCH, source.id)
        if (source.isCustom && summary.whollyRejected) {
            throw CustomBlocklistSourceAcquisitionException(source.id, CustomBlocklistSourceFailure.PARSE_FAILED)
        }
        val output = File(directory, "unique.tbl")
        val uniqueRuleCount = mergeRuns(
            runs,
            output,
            MAX_SOURCE_RULES,
            FilterCompileError.SOURCE_RULE_LIMIT_EXCEEDED,
            source.id,
        )
        if (uniqueRuleCount == 0 && !source.isCustom) fail(FilterCompileError.UNEXPECTED_EMPTY, source.id)
        return output
    }

    private fun writeRules(
        rules: List<DomainRule>,
        matchesSubdomains: Boolean,
        output: File,
        checkpoint: (FilterCompilePhase) -> Unit,
    ): File {
        val domains = ArrayList<String>()
        checkpoint(FilterCompilePhase.MANUAL_COPY)
        for ((index, rule) in rules.withIndex()) {
            if (rule.matchesSubdomains == matchesSubdomains) domains += rule.domain
            if (index % CHECKPOINT_RULE_INTERVAL == 0) checkpoint(FilterCompilePhase.MANUAL_COPY)
        }
        writeSortedDomains(domains, output) { checkpoint(FilterCompilePhase.MANUAL_SORT) }
        return output
    }

    private fun writeSortedDomains(
        domains: Collection<String>,
        output: File,
        checkpoint: () -> Unit = {},
    ) {
        output.parentFile?.mkdirs()
        val sorted = if (domains is MutableList<String>) domains else ArrayList(domains)
        var comparisons = 0
        checkpoint()
        sorted.sortWith { left, right ->
            if (++comparisons % CHECKPOINT_COMPARISON_INTERVAL == 0) checkpoint()
            left.compareTo(right)
        }
        checkpoint()
        DataOutputStream(output.outputStream().buffered(64 * 1024)).use { stream ->
            var previous: String? = null
            for ((index, domain) in sorted.withIndex()) {
                if (domain != previous) stream.writeDomain(domain)
                previous = domain
                if (index % CHECKPOINT_RULE_INTERVAL == 0) checkpoint()
            }
        }
    }

    private suspend fun mergeRuns(
        initialRuns: List<File>,
        output: File,
        maxUnique: Int,
        limitError: FilterCompileError = FilterCompileError.RULE_LIMIT_EXCEEDED,
        sourceId: String? = null,
    ): Int {
        output.parentFile?.mkdirs()
        if (initialRuns.isEmpty()) {
            output.outputStream().buffered(64 * 1024).use { }
            return 0
        }
        var pass = 0
        var runs = initialRuns.toList()
        while (runs.size > mergeFanIn) {
            val nextPass = ArrayList<File>((runs.size + mergeFanIn - 1) / mergeFanIn)
            for ((groupIndex, group) in runs.chunked(mergeFanIn).withIndex()) {
                coroutineContext.ensureActive()
                val intermediate = File(
                    output.parentFile,
                    ".${output.name}.merge-pass-$pass-$groupIndex.tbl",
                )
                mergeRunsDirect(group, intermediate, maxUnique, limitError, sourceId)
                deleteConsumedRuns(group)
                nextPass += intermediate
            }
            runs = nextPass
            pass++
        }
        val count = mergeRunsDirect(runs, output, maxUnique, limitError, sourceId)
        deleteConsumedRuns(runs)
        return count
    }

    private suspend fun mergeRunsDirect(
        runs: List<File>,
        output: File,
        maxUnique: Int,
        limitError: FilterCompileError,
        sourceId: String?,
    ): Int {
        require(runs.size in 1..mergeFanIn)
        val readers = ArrayList<RunReader>(runs.size)
        try {
            for (run in runs) {
                readers += RunReader(run)
                mergeObserver.readersOpened(readers.size)
            }
            val queue = PriorityQueue<RunValue>(compareBy<RunValue> { it.domain }.thenBy { it.reader })
            readers.forEachIndexed { index, reader -> reader.next()?.let { queue += RunValue(it, index) } }
            var count = 0
            var previous: String? = null
            DataOutputStream(output.outputStream().buffered(64 * 1024)).use { stream ->
                while (queue.isNotEmpty()) {
                    coroutineContext.ensureActive()
                    val value = queue.remove()
                    if (value.domain != previous) {
                        count++
                        if (count > maxUnique) fail(limitError, sourceId)
                        stream.writeDomain(value.domain)
                        previous = value.domain
                    }
                    readers[value.reader].next()?.let { queue += RunValue(it, value.reader) }
                }
            }
            return count
        } finally {
            readers.forEach(RunReader::close)
        }
    }

    private fun deleteConsumedRuns(runs: List<File>) {
        for (run in runs) check(run.delete() || !run.exists()) { "Could not delete consumed merge run" }
    }

    private fun countRules(file: File, checkpoint: () -> Unit): Int = RunReader(file).use { reader ->
        var count = 0
        checkpoint()
        while (reader.next() != null) {
            count++
            if (count % CHECKPOINT_RULE_INTERVAL == 0) checkpoint()
        }
        checkpoint()
        count
    }

    private class RunReader(file: File) : AutoCloseable {
        private val input = DataInputStream(file.inputStream().buffered(64 * 1024))
        fun next(): String? {
            val first = input.read()
            if (first < 0) return null
            val second = input.read()
            require(second >= 0)
            val length = (first shl 8) or second
            val bytes = ByteArray(length)
            input.readFully(bytes)
            return bytes.toString(Charsets.UTF_8)
        }
        override fun close() = input.close()
    }

    private class CountingInputStream(
        input: InputStream,
        private val maximum: Long,
        private val sourceId: String,
    ) : FilterInputStream(BufferedInputStream(input, 64 * 1024)) {
        var count: Long = 0
            private set
        override fun read(): Int = super.read().also { if (it >= 0) increment(1) }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) increment(it.toLong()) }
        private fun increment(amount: Long) {
            count += amount
            if (count > maximum) fail(FilterCompileError.SOURCE_TOO_LARGE, sourceId)
        }
    }

    private data class RunValue(val domain: String, val reader: Int)

    private class MemoryProbe(private val observer: FilterCompileMemoryObserver) {
        val baseline: Long = observer.measureLiveHeapBytes("baseline") ?: usedHeap()
        var peak: Long = baseline
            private set
        var observedLiveHeap: Boolean = observer !== FilterCompileMemoryObserver.NONE
            private set
        fun sample(phase: String) {
            val measured = observer.measureLiveHeapBytes(phase)
            peak = maxOf(peak, measured ?: usedHeap())
        }
        companion object {
            private fun usedHeap(): Long = Runtime.getRuntime().run { totalMemory() - freeMemory() }
        }
    }

    companion object {
        const val FREE_RULE_CAP = 500_000
        const val PLUS_RULE_CAP = 2_000_000
        const val MAX_SOURCE_RULES = 2_000_000
        const val MAX_SOURCE_BYTES = 45L * 1024 * 1024
        const val DEFAULT_MERGE_FAN_IN = 32
        private const val CHECKPOINT_RULE_INTERVAL = 1_024
        private const val CHECKPOINT_COMPARISON_INTERVAL = 4_096

        private fun DataOutputStream.writeDomain(domain: String) {
            val bytes = domain.encodeToByteArray()
            require(bytes.size in 1..253)
            writeShort(bytes.size)
            write(bytes)
        }

        private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }
        private fun fail(error: FilterCompileError, sourceId: String? = null): Nothing =
            throw FilterCompileException(error, sourceId)
    }
}

fun interface FilterCompileMemoryObserver {
    /** Returns live heap bytes for a retained-structure checkpoint, or null to use passive sampling. */
    fun measureLiveHeapBytes(phase: String): Long?

    companion object {
        val NONE = FilterCompileMemoryObserver { null }
    }
}

fun interface FilterMergeResourceObserver {
    /** Reports the number of run readers held by a direct merge. */
    fun readersOpened(count: Int)

    companion object {
        val NONE = FilterMergeResourceObserver { }
    }
}

enum class FilterCompilePhase {
    MANUAL_COPY,
    MANUAL_SORT,
    MANUAL_COUNT,
    TABLE_HASH,
    ARTIFACT_WRITE,
    ARTIFACT_SHA256,
    ARTIFACT_DECODE,
}

fun interface FilterCompileCheckpointObserver {
    fun checkpoint(phase: FilterCompilePhase)

    companion object {
        val NONE = FilterCompileCheckpointObserver { }
    }
}
