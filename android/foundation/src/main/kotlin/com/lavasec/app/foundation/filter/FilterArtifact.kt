package com.lavasec.app.foundation.filter

import com.lavasec.app.foundation.domain.DomainName
import com.lavasec.app.foundation.domain.DomainValidationException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

data class ArtifactConfigurationIdentity(val generation: Long, val fingerprint: String)

data class ArtifactSourceIdentity(
    val id: String,
    val acceptedSha256: String,
    val format: BlocklistFormat,
)

data class ArtifactRuleCounts(
    val blockExact: Int,
    val blockSuffix: Int,
    val allowExact: Int,
    val allowSuffix: Int,
    val guardrailExact: Int,
    val guardrailSuffix: Int,
) {
    val total: Int get() = blockExact + blockSuffix + allowExact + allowSuffix + guardrailExact + guardrailSuffix
    internal fun asArray(): IntArray = intArrayOf(
        blockExact, blockSuffix, allowExact, allowSuffix, guardrailExact, guardrailSuffix,
    )
}

data class FilterArtifactIdentity(
    val configuration: ArtifactConfigurationIdentity,
    val selectedSources: List<ArtifactSourceIdentity>,
    val catalogRevision: String,
    val catalogSha256: String,
    val artifactSchema: Int,
    val pointerSchema: Int,
    val parserRulesVersion: Int,
    val ruleCounts: ArtifactRuleCounts,
    val ruleTablesSha256: String,
    val compileInputsSha256: String,
    val intentionalEmpty: Boolean,
)

data class ExpectedArtifactSource(
    val id: String,
    val format: BlocklistFormat,
)

data class FilterArtifactExpectation(
    val configuration: ArtifactConfigurationIdentity,
    val selectedSources: List<ExpectedArtifactSource>,
    val catalogRevision: String,
    val catalogSha256: String,
)

/**
 * Opaque proof that [FilterCompiler] produced one exact artifact from its accepted compile input.
 *
 * The constructor is module-private so consumers can carry this receipt across the staging
 * boundary but cannot mint one for self-authored artifact bytes.
 */
class FilterCompilationReceipt internal constructor(
    artifactSha256: String,
    artifactIdentity: FilterArtifactIdentity,
) {
    val artifactSha256: String
    val artifactIdentity: FilterArtifactIdentity

    init {
        require(artifactSha256.length == 64 && artifactSha256.all { it in '0'..'9' || it in 'a'..'f' }) {
            "compiled artifact SHA-256 must be canonical lowercase hexadecimal"
        }
        this.artifactSha256 = artifactSha256.lowercase(Locale.ROOT)
        this.artifactIdentity = artifactIdentity.copy(
            selectedSources = Collections.unmodifiableList(artifactIdentity.selectedSources.toList()),
        )
    }
}

enum class FilterArtifactUnavailableReason {
    POINTER_MISSING,
    POINTER_INVALID,
    POINTER_SCHEMA_MISMATCH,
    ARTIFACT_MISSING,
    DIGEST_MISMATCH,
    DECODE_INVALID,
    ARTIFACT_SCHEMA_MISMATCH,
    PARSER_VERSION_MISMATCH,
    RULE_TABLE_INVALID,
    IDENTITY_MISMATCH,
    SOURCE_COVERAGE_MISMATCH,
    UNEXPECTED_EMPTY,
    STORAGE_UNAVAILABLE,
}

sealed interface FilterArtifactOpenResult {
    data class Available(val artifact: FilterArtifact) : FilterArtifactOpenResult
    data class Unavailable(val reason: FilterArtifactUnavailableReason) : FilterArtifactOpenResult
}

class PreparedFilterArtifact internal constructor(
    val file: File,
    val sha256: String,
    val identity: FilterArtifactIdentity,
    val tierRuleCount: Int,
    val memoryMetrics: FilterCompileMemoryMetrics,
) : AutoCloseable {
    val compilationReceipt = FilterCompilationReceipt(sha256, identity)
    val blockRuleCount: Int get() = identity.ruleCounts.blockExact + identity.ruleCounts.blockSuffix
    override fun close() {
        file.parentFile?.takeIf { it.name.startsWith("compile-") }?.deleteRecursively()
    }
}

data class FilterCompileMemoryMetrics(
    val baselineHeapBytes: Long,
    val peakHeapBytes: Long,
    val compactIndexBytes: Long,
    val liveHeapObserved: Boolean,
) {
    val transientHeapDeltaBytes: Long get() = maxOf(0, peakHeapBytes - baselineHeapBytes)
}

interface DomainFilterDecisionSource {
    val blockRuleCount: Int
    val allowRuleCount: Int
    val guardrailRuleCount: Int
    fun decide(rawDomain: String): FilterDecision
    fun decideNormalized(normalizedDomain: String): FilterDecision
}

/** A validated, memory-mapped immutable filter artifact. */
class FilterArtifact private constructor(
    private val channel: FileChannel,
    private val mapping: MappedByteBuffer,
    val sha256: String,
    val identity: FilterArtifactIdentity,
    tables: List<MappedRuleTable>,
) : AutoCloseable {
    val snapshot = MappedFilterSnapshot(
        exactBlocks = tables[0],
        suffixBlocks = tables[1],
        exactAllows = tables[2],
        suffixAllows = tables[3],
        exactGuardrails = tables[4],
        suffixGuardrails = tables[5],
    )

    val channelIsOpenForTesting: Boolean get() = channel.isOpen

    override fun close() {
        channel.close()
    }

    companion object {
        const val ARTIFACT_SCHEMA = 2
        const val POINTER_SCHEMA = 1
        const val MAX_SERIALIZED_ARTIFACT_BYTES = 512L * 1024L * 1024L
        private val MAGIC = "LAVAFILT".encodeToByteArray()

        fun open(
            file: File,
            expectedSha256: String,
            expectedIdentity: FilterArtifactIdentity? = null,
            digestCheckpoint: () -> Unit = {},
            decodeCheckpoint: () -> Unit = {},
        ): FilterArtifact = openValidated(
            file = file,
            expectedSha256 = expectedSha256,
            expectedIdentity = expectedIdentity,
            digestCheckpoint = digestCheckpoint,
            decodeCheckpoint = decodeCheckpoint,
        )

        fun openResult(
            file: File,
            expectedSha256: String,
            expectation: FilterArtifactExpectation? = null,
            digestCheckpoint: () -> Unit = {},
            decodeCheckpoint: () -> Unit = {},
            channelOpenedCheckpoint: (FileChannel) -> Unit = {},
        ): FilterArtifactOpenResult {
            if (!file.exists()) {
                return FilterArtifactOpenResult.Unavailable(FilterArtifactUnavailableReason.ARTIFACT_MISSING)
            }
            if (!file.isFile) {
                return FilterArtifactOpenResult.Unavailable(FilterArtifactUnavailableReason.STORAGE_UNAVAILABLE)
            }
            var artifact: FilterArtifact? = null
            return try {
                val opened = openValidated(
                    file = file,
                    expectedSha256 = expectedSha256,
                    expectedIdentity = null,
                    digestCheckpoint = digestCheckpoint,
                    decodeCheckpoint = decodeCheckpoint,
                    channelOpenedCheckpoint = channelOpenedCheckpoint,
                )
                artifact = opened
                expectation?.let { validateExpectation(opened.identity, it) }
                FilterArtifactOpenResult.Available(opened).also { artifact = null }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ArtifactOpenException) {
                FilterArtifactOpenResult.Unavailable(error.reason)
            } catch (_: Exception) {
                FilterArtifactOpenResult.Unavailable(FilterArtifactUnavailableReason.DECODE_INVALID)
            } finally {
                artifact?.close()
            }
        }

        /**
         * Validates an already-open descriptor and transfers its ownership to this call.
         *
         * On success the returned [FilterArtifact] owns the channel. Every unavailable or
         * exceptional path closes it. Callers can therefore open and inspect one descriptor with
         * platform-specific no-follow and regular-file checks without reopening a pathname.
         */
        fun openResult(
            channel: FileChannel,
            expectedSha256: String,
            expectation: FilterArtifactExpectation? = null,
            digestCheckpoint: () -> Unit = {},
            decodeCheckpoint: () -> Unit = {},
            channelOpenedCheckpoint: (FileChannel) -> Unit = {},
        ): FilterArtifactOpenResult {
            var artifact: FilterArtifact? = null
            var ownershipTransferred = false
            return try {
                val opened = openValidated(
                    channel = channel,
                    expectedSha256 = expectedSha256,
                    expectedIdentity = null,
                    digestCheckpoint = digestCheckpoint,
                    decodeCheckpoint = decodeCheckpoint,
                    channelOpenedCheckpoint = channelOpenedCheckpoint,
                )
                artifact = opened
                expectation?.let { validateExpectation(opened.identity, it) }
                FilterArtifactOpenResult.Available(opened).also {
                    artifact = null
                    ownershipTransferred = true
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ArtifactOpenException) {
                FilterArtifactOpenResult.Unavailable(error.reason)
            } catch (_: Exception) {
                FilterArtifactOpenResult.Unavailable(FilterArtifactUnavailableReason.DECODE_INVALID)
            } finally {
                artifact?.close()
                if (!ownershipTransferred && channel.isOpen) {
                    channel.close()
                }
            }
        }

        fun openOrNull(
            file: File,
            expectedSha256: String,
            expectedIdentity: FilterArtifactIdentity? = null,
            digestCheckpoint: () -> Unit = {},
            decodeCheckpoint: () -> Unit = {},
        ): FilterArtifact? = try {
            open(file, expectedSha256, expectedIdentity, digestCheckpoint, decodeCheckpoint)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }

        private fun openValidated(
            file: File,
            expectedSha256: String,
            expectedIdentity: FilterArtifactIdentity?,
            digestCheckpoint: () -> Unit,
            decodeCheckpoint: () -> Unit,
            channelOpenedCheckpoint: (FileChannel) -> Unit = {},
        ): FilterArtifact {
            val channel = try {
                FileChannel.open(file.toPath(), READ, NOFOLLOW_LINKS)
            } catch (error: Exception) {
                throw ArtifactOpenException(FilterArtifactUnavailableReason.STORAGE_UNAVAILABLE, error)
            }
            return openValidated(
                channel = channel,
                expectedSha256 = expectedSha256,
                expectedIdentity = expectedIdentity,
                digestCheckpoint = digestCheckpoint,
                decodeCheckpoint = decodeCheckpoint,
                channelOpenedCheckpoint = channelOpenedCheckpoint,
            )
        }

        private fun openValidated(
            channel: FileChannel,
            expectedSha256: String,
            expectedIdentity: FilterArtifactIdentity?,
            digestCheckpoint: () -> Unit,
            decodeCheckpoint: () -> Unit,
            channelOpenedCheckpoint: (FileChannel) -> Unit = {},
        ): FilterArtifact {
            try {
                val artifactSize = channel.size()
                channelOpenedCheckpoint(channel)
                ensureArtifact(
                    artifactSize in 1..MAX_SERIALIZED_ARTIFACT_BYTES,
                    FilterArtifactUnavailableReason.DECODE_INVALID,
                )
                val actualSha256 = sha256(channel, digestCheckpoint)
                ensureArtifact(
                    actualSha256.equals(expectedSha256, ignoreCase = true),
                    FilterArtifactUnavailableReason.DIGEST_MISMATCH,
                )
                val mapping = channel.map(FileChannel.MapMode.READ_ONLY, 0, artifactSize)
                val decoded = decode(mapping, decodeCheckpoint)
                ensureArtifact(
                    expectedIdentity == null || decoded.first == expectedIdentity,
                    FilterArtifactUnavailableReason.IDENTITY_MISMATCH,
                )
                return FilterArtifact(
                    channel = channel,
                    mapping = mapping,
                    sha256 = actualSha256,
                    identity = decoded.first,
                    tables = decoded.second,
                )
            } catch (error: IOException) {
                channel.close()
                throw ArtifactOpenException(FilterArtifactUnavailableReason.STORAGE_UNAVAILABLE, error)
            } catch (error: Throwable) {
                channel.close()
                throw error
            }
        }

        private fun validateExpectation(
            identity: FilterArtifactIdentity,
            expectation: FilterArtifactExpectation,
        ) {
            if (identity.intentionalEmpty && expectation.selectedSources.isNotEmpty()) {
                invalidArtifact(FilterArtifactUnavailableReason.UNEXPECTED_EMPTY)
            }
            ensureArtifact(
                identity.configuration == expectation.configuration &&
                    identity.catalogRevision == expectation.catalogRevision &&
                    identity.catalogSha256.equals(expectation.catalogSha256, ignoreCase = true),
                FilterArtifactUnavailableReason.IDENTITY_MISMATCH,
            )
            val actualSources = identity.selectedSources.map { ExpectedArtifactSource(it.id, it.format) }
            ensureArtifact(
                actualSources == expectation.selectedSources,
                FilterArtifactUnavailableReason.SOURCE_COVERAGE_MISMATCH,
            )
        }

        internal fun write(
            file: File,
            identity: FilterArtifactIdentity,
            tables: List<File>,
            checkpoint: () -> Unit = {},
        ) {
            require(tables.size == TABLE_COUNT)
            checkpoint()
            file.outputStream().buffered(64 * 1024).use { raw ->
                DataOutputStream(raw).use { output ->
                    output.write(MAGIC)
                    output.writeInt(ARTIFACT_SCHEMA)
                    output.writeInt(BlocklistParser.RULES_VERSION)
                    val identityBytes = encodeIdentity(identity)
                    output.writeInt(identityBytes.size)
                    identity.ruleCounts.asArray().forEach(output::writeInt)
                    output.write(identityBytes)
                    val buffer = ByteArray(64 * 1024)
                    for (table in tables) {
                        table.inputStream().buffered(64 * 1024).use { input ->
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                checkpoint()
                            }
                        }
                    }
                }
            }
        }

        internal fun hashRuleTables(
            tables: List<File>,
            counts: IntArray,
            checkpoint: () -> Unit = {},
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val domainBuffer = ByteArray(253)
            checkpoint()
            for (index in tables.indices) {
                digest.update(index.toByte())
                digest.updateInt(counts[index])
                DataInputStream(tables[index].inputStream().buffered(64 * 1024)).use { input ->
                    val lengthBytes = ByteArray(2)
                    repeat(counts[index]) { ruleIndex ->
                        input.readFully(lengthBytes)
                        val length = ((lengthBytes[0].toInt() and 0xff) shl 8) or (lengthBytes[1].toInt() and 0xff)
                        digest.updateInt(length)
                        input.readFully(domainBuffer, 0, length)
                        digest.update(domainBuffer, 0, length)
                        if (ruleIndex % CHECKPOINT_RULE_INTERVAL == 0) checkpoint()
                    }
                    require(input.read() == -1)
                }
            }
            return digest.hex()
        }

        fun sha256(file: File, checkpoint: () -> Unit = {}): String {
            val digest = MessageDigest.getInstance("SHA-256")
            checkpoint()
            file.inputStream().buffered(64 * 1024).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    checkpoint()
                }
            }
            return digest.hex()
        }

        private fun sha256(channel: FileChannel, checkpoint: () -> Unit): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteBuffer.allocate(64 * 1024)
            var position = 0L
            checkpoint()
            while (true) {
                buffer.clear()
                val count = channel.read(buffer, position)
                if (count < 0) break
                if (count == 0) continue
                position += count
                buffer.flip()
                digest.update(buffer)
                checkpoint()
            }
            return digest.hex()
        }

        fun testingFirstTableOffset(bytes: ByteArray): Int {
            val buffer = ByteBuffer.wrap(bytes)
            buffer.position(MAGIC.size + 8)
            val identityLength = buffer.int
            return MAGIC.size + 8 + 4 + TABLE_COUNT * 4 + identityLength
        }

        private fun decode(
            source: ByteBuffer,
            checkpoint: () -> Unit,
        ): Pair<FilterArtifactIdentity, List<MappedRuleTable>> {
            var phase = DecodePhase.STRUCTURE
            try {
                checkpoint()
                val buffer = source.asReadOnlyBuffer()
                val magic = ByteArray(MAGIC.size)
                buffer.getChecked(magic)
                ensureArtifact(magic.contentEquals(MAGIC), FilterArtifactUnavailableReason.DECODE_INVALID)
                ensureArtifact(
                    buffer.intChecked() == ARTIFACT_SCHEMA,
                    FilterArtifactUnavailableReason.ARTIFACT_SCHEMA_MISMATCH,
                )
                ensureArtifact(
                    buffer.intChecked() == BlocklistParser.RULES_VERSION,
                    FilterArtifactUnavailableReason.PARSER_VERSION_MISMATCH,
                )
                val identityLength = buffer.intChecked()
                ensureArtifact(
                    identityLength in 1..minOf(buffer.remaining(), MAX_IDENTITY_BYTES),
                    FilterArtifactUnavailableReason.DECODE_INVALID,
                )
                val counts = IntArray(TABLE_COUNT) {
                    buffer.intChecked().also { count ->
                        ensureArtifact(count >= 0, FilterArtifactUnavailableReason.RULE_TABLE_INVALID)
                    }
                }
                ensureArtifact(
                    counts.fold(0L) { total, count -> total + count } <= 2_000_000L,
                    FilterArtifactUnavailableReason.RULE_TABLE_INVALID,
                )
                val identityBytes = ByteArray(identityLength)
                buffer.getChecked(identityBytes)
                val identity = decodeIdentity(identityBytes, checkpoint)
                ensureArtifact(
                    identity.artifactSchema == ARTIFACT_SCHEMA,
                    FilterArtifactUnavailableReason.ARTIFACT_SCHEMA_MISMATCH,
                )
                ensureArtifact(
                    identity.pointerSchema == POINTER_SCHEMA,
                    FilterArtifactUnavailableReason.POINTER_SCHEMA_MISMATCH,
                )
                ensureArtifact(
                    identity.parserRulesVersion == BlocklistParser.RULES_VERSION,
                    FilterArtifactUnavailableReason.PARSER_VERSION_MISMATCH,
                )
                ensureArtifact(
                    identity.ruleCounts.asArray().contentEquals(counts),
                    FilterArtifactUnavailableReason.RULE_TABLE_INVALID,
                )

                phase = DecodePhase.TABLES
                val tables = ArrayList<MappedRuleTable>(TABLE_COUNT)
                val tableDigest = MessageDigest.getInstance("SHA-256")
                val domainBuffer = ByteArray(253)
                for (tableIndex in 0 until TABLE_COUNT) {
                    tableDigest.update(tableIndex.toByte())
                    tableDigest.updateInt(counts[tableIndex])
                    val offsets = IntArray(counts[tableIndex])
                    val lengths = ShortArray(counts[tableIndex])
                    var previousOffset = -1
                    var previousLength = 0
                    repeat(counts[tableIndex]) { index ->
                        val length = buffer.shortChecked().toInt() and 0xffff
                        ensureArtifact(
                            length in 1..253 && buffer.remaining() >= length,
                            FilterArtifactUnavailableReason.RULE_TABLE_INVALID,
                        )
                        offsets[index] = buffer.position()
                        lengths[index] = length.toShort()
                        buffer.position(buffer.position() + length)
                        ensureArtifact(
                            previousOffset < 0 ||
                                compare(source, previousOffset, previousLength, offsets[index], length) < 0,
                            FilterArtifactUnavailableReason.RULE_TABLE_INVALID,
                        )
                        previousOffset = offsets[index]
                        previousLength = length
                        tableDigest.updateInt(length)
                        for (byteIndex in 0 until length) {
                            domainBuffer[byteIndex] = source.get(offsets[index] + byteIndex)
                        }
                        tableDigest.update(domainBuffer, 0, length)
                        if (index % CHECKPOINT_RULE_INTERVAL == 0) checkpoint()
                    }
                    tables += MappedRuleTable(source.asReadOnlyBuffer(), offsets, lengths)
                }
                ensureArtifact(!buffer.hasRemaining(), FilterArtifactUnavailableReason.RULE_TABLE_INVALID)
                ensureArtifact(
                    tableDigest.hex() == identity.ruleTablesSha256,
                    FilterArtifactUnavailableReason.RULE_TABLE_INVALID,
                )
                ensureArtifact(
                    if (identity.intentionalEmpty) {
                        identity.ruleCounts.total == 0 && identity.selectedSources.isEmpty()
                    } else {
                        // A loaded custom list may legitimately contain only comments or allow
                        // rules. Its exact source digest still commits the zero-rule artifact.
                        identity.ruleCounts.total > 0 || identity.selectedSources.isNotEmpty()
                    },
                    FilterArtifactUnavailableReason.UNEXPECTED_EMPTY,
                )
                return identity to tables
            } catch (error: CancellationException) {
                throw error
            } catch (error: ArtifactOpenException) {
                throw error
            } catch (error: Exception) {
                throw ArtifactOpenException(
                    if (phase == DecodePhase.TABLES) {
                        FilterArtifactUnavailableReason.RULE_TABLE_INVALID
                    } else {
                        FilterArtifactUnavailableReason.DECODE_INVALID
                    },
                    error,
                )
            }
        }

        private fun encodeIdentity(identity: FilterArtifactIdentity): ByteArray = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeLong(identity.configuration.generation)
                output.writeString(identity.configuration.fingerprint)
                val sources = identity.selectedSources
                output.writeInt(sources.size)
                for (source in sources) {
                    output.writeString(source.id)
                    output.writeString(source.acceptedSha256)
                    output.writeString(source.format.name)
                }
                output.writeString(identity.catalogRevision)
                output.writeString(identity.catalogSha256)
                output.writeInt(identity.artifactSchema)
                output.writeInt(identity.pointerSchema)
                output.writeInt(identity.parserRulesVersion)
                identity.ruleCounts.asArray().forEach(output::writeInt)
                output.writeString(identity.ruleTablesSha256)
                output.writeString(identity.compileInputsSha256)
                output.writeBoolean(identity.intentionalEmpty)
            }
            bytes.toByteArray()
        }

        private fun decodeIdentity(
            bytes: ByteArray,
            checkpoint: () -> Unit,
        ): FilterArtifactIdentity = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val configuration = ArtifactConfigurationIdentity(input.readLong(), input.readStringChecked())
            val sourceCount = input.readInt()
            require(sourceCount in 0..10_000)
            val sources = List(sourceCount) { index ->
                if (index % CHECKPOINT_RULE_INTERVAL == 0) checkpoint()
                ArtifactSourceIdentity(
                    input.readStringChecked(),
                    input.readStringChecked(),
                    BlocklistFormat.valueOf(input.readStringChecked()),
                )
            }
            ensureArtifact(
                sources.map { it.id }.distinct().size == sources.size &&
                    sources.all { it.acceptedSha256.isCanonicalSha256() },
                FilterArtifactUnavailableReason.DECODE_INVALID,
            )
            val catalogRevision = input.readStringChecked()
            val catalogSha = input.readStringChecked()
            val artifactSchema = input.readInt()
            val pointerSchema = input.readInt()
            val parserVersion = input.readInt()
            val counts = IntArray(TABLE_COUNT) { input.readInt().also { require(it >= 0) } }
            val tableHash = input.readStringChecked()
            val compileInputsHash = input.readStringChecked()
            val intentionalEmpty = input.readBoolean()
            require(input.read() == -1)
            require(catalogSha.isCanonicalSha256())
            require(tableHash.isCanonicalSha256())
            require(compileInputsHash.isCanonicalSha256())
            FilterArtifactIdentity(
                configuration, sources, catalogRevision, catalogSha, artifactSchema, pointerSchema,
                parserVersion, ArtifactRuleCounts(counts[0], counts[1], counts[2], counts[3], counts[4], counts[5]),
                tableHash, compileInputsHash, intentionalEmpty,
            )
        }

        private fun DataOutputStream.writeString(value: String) {
            val bytes = value.encodeToByteArray()
            require(bytes.size <= FILTER_ARTIFACT_MAX_STRING_BYTES)
            writeInt(bytes.size)
            write(bytes)
        }

        private fun DataInputStream.readStringChecked(): String {
            val size = readInt()
            require(size in 0..FILTER_ARTIFACT_MAX_STRING_BYTES)
            val bytes = ByteArray(size)
            readFully(bytes)
            return bytes.toString(Charsets.UTF_8)
        }

        private fun String.isCanonicalSha256(): Boolean =
            length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

        private fun ByteBuffer.intChecked(): Int { require(remaining() >= 4); return int }
        private fun ByteBuffer.shortChecked(): Short { require(remaining() >= 2); return short }
        private fun ByteBuffer.getChecked(target: ByteArray) { require(remaining() >= target.size); get(target) }
        private fun MessageDigest.updateInt(value: Int) = update(ByteBuffer.allocate(4).putInt(value).array())
        private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }
        private fun compare(
            bytes: ByteBuffer,
            leftOffset: Int,
            leftLength: Int,
            rightOffset: Int,
            rightLength: Int,
        ): Int {
            for (index in 0 until minOf(leftLength, rightLength)) {
                val difference = (bytes.get(leftOffset + index).toInt() and 0xff) -
                    (bytes.get(rightOffset + index).toInt() and 0xff)
                if (difference != 0) return difference
            }
            return leftLength - rightLength
        }

        private fun ensureArtifact(
            condition: Boolean,
            reason: FilterArtifactUnavailableReason,
        ) {
            if (!condition) invalidArtifact(reason)
        }

        private fun invalidArtifact(reason: FilterArtifactUnavailableReason): Nothing =
            throw ArtifactOpenException(reason)

        private class ArtifactOpenException(
            val reason: FilterArtifactUnavailableReason,
            cause: Throwable? = null,
        ) : IllegalArgumentException(reason.name, cause)

        private enum class DecodePhase { STRUCTURE, TABLES }

        private const val TABLE_COUNT = 6
        private const val MAX_IDENTITY_BYTES = 1_000_000
        private const val CHECKPOINT_RULE_INTERVAL = 1_024
    }
}

class MappedFilterSnapshot internal constructor(
    private val exactBlocks: MappedRuleTable,
    private val suffixBlocks: MappedRuleTable,
    private val exactAllows: MappedRuleTable,
    private val suffixAllows: MappedRuleTable,
    private val exactGuardrails: MappedRuleTable,
    private val suffixGuardrails: MappedRuleTable,
) : DomainFilterDecisionSource {
    override val blockRuleCount: Int get() = exactBlocks.count + suffixBlocks.count
    override val allowRuleCount: Int get() = exactAllows.count + suffixAllows.count
    override val guardrailRuleCount: Int get() = exactGuardrails.count + suffixGuardrails.count
    val effectiveBlockRuleCount: Int by lazy {
        var reducingAllows = 0
        exactAllows.forEachValue { domain ->
            if (allowReducesProtection(domain)) reducingAllows += 1
        }
        if (suffixAllows.count > 0) {
            // Query the existing mapped index directly: a FOSS-sized allow list must not be
            // copied into millions of Strings and heap trie nodes to calculate this count.
            val descendantMatcher = DescendantQueryMatcher(suffixAllows)
            val blockAtOrBelow = BooleanArray(suffixAllows.count)
            val guardrailAtOrBelow = BooleanArray(suffixAllows.count)
            exactBlocks.markDescendantQueries(descendantMatcher, blockAtOrBelow)
            suffixBlocks.markDescendantQueries(descendantMatcher, blockAtOrBelow)
            exactGuardrails.markDescendantQueries(descendantMatcher, guardrailAtOrBelow)
            suffixGuardrails.markDescendantQueries(descendantMatcher, guardrailAtOrBelow)
            var index = 0
            suffixAllows.forEachValue { domain ->
                val reducesProtection =
                    !matches(exactGuardrails, suffixGuardrails, domain) &&
                        !guardrailAtOrBelow[index] &&
                        (matches(exactBlocks, suffixBlocks, domain) || blockAtOrBelow[index])
                if (reducesProtection) reducingAllows += 1
                index += 1
            }
        }
        maxOf(0, blockRuleCount - reducingAllows)
    }

    override fun decide(rawDomain: String): FilterDecision {
        val normalized = try { DomainName.parse(rawDomain).value } catch (_: DomainValidationException) {
            return FilterDecision(FilterAction.BLOCK, FilterDecisionReason.INVALID_DOMAIN)
        }
        return decideNormalized(normalized)
    }

    override fun decideNormalized(normalizedDomain: String): FilterDecision = when {
        matches(exactGuardrails, suffixGuardrails, normalizedDomain) -> FilterDecision(FilterAction.BLOCK, FilterDecisionReason.THREAT_GUARDRAIL)
        matches(exactAllows, suffixAllows, normalizedDomain) -> FilterDecision(FilterAction.ALLOW, FilterDecisionReason.LOCAL_ALLOWLIST)
        matches(exactBlocks, suffixBlocks, normalizedDomain) -> FilterDecision(FilterAction.BLOCK, FilterDecisionReason.BLOCKLIST)
        else -> FilterDecision.DEFAULT_ALLOW
    }

    private fun matches(exact: MappedRuleTable, suffix: MappedRuleTable, domain: String): Boolean {
        if (exact.contains(domain) || suffix.contains(domain)) return true
        var index = domain.indexOf('.')
        while (index >= 0) {
            if (suffix.contains(domain.substring(index + 1))) return true
            index = domain.indexOf('.', index + 1)
        }
        return false
    }

    private fun allowReducesProtection(domain: String): Boolean {
        if (matches(exactGuardrails, suffixGuardrails, domain)) return false
        return matches(exactBlocks, suffixBlocks, domain)
    }
}

internal class MappedRuleTable(
    private val bytes: ByteBuffer,
    private val offsets: IntArray,
    private val lengths: ShortArray,
) {
    val count: Int get() = offsets.size

    fun forEachValue(action: (String) -> Unit) {
        for (index in offsets.indices) action(valueAt(index))
    }

    fun markDescendantQueries(
        matcher: DescendantQueryMatcher,
        matches: BooleanArray,
    ) {
        for (index in offsets.indices) {
            val length = lengths[index].toInt() and 0xffff
            matcher.markMatches(bytes, offsets[index], length, matches)
        }
    }

    private fun valueAt(index: Int): String {
        val length = lengths[index].toInt() and 0xffff
        val value = ByteArray(length)
        val start = offsets[index]
        for (offset in 0 until length) value[offset] = bytes.get(start + offset)
        return value.toString(Charsets.UTF_8)
    }

    fun contains(value: String): Boolean {
        val target = value.encodeToByteArray()
        var low = 0
        var high = offsets.lastIndex
        while (low <= high) {
            val middle = (low + high).ushr(1)
            val comparison = compareAt(middle, target)
            if (comparison == 0) return true
            if (comparison < 0) low = middle + 1 else high = middle - 1
        }
        return false
    }

    /** Allocation-free lookup for a suffix borrowed from another mapped table. */
    fun indexOf(target: ByteBuffer, start: Int, length: Int): Int {
        var low = 0
        var high = offsets.lastIndex
        while (low <= high) {
            val middle = (low + high).ushr(1)
            val ownLength = lengths[middle].toInt() and 0xffff
            var comparison = 0
            for (offset in 0 until minOf(ownLength, length)) {
                comparison = (bytes.get(offsets[middle] + offset).toInt() and 0xff) -
                    (target.get(start + offset).toInt() and 0xff)
                if (comparison != 0) break
            }
            if (comparison == 0) comparison = ownLength - length
            if (comparison == 0) return middle
            if (comparison < 0) low = middle + 1 else high = middle - 1
        }
        return -1
    }

    private fun compareAt(index: Int, target: ByteArray): Int {
        val length = lengths[index].toInt() and 0xffff
        val shared = minOf(length, target.size)
        for (offset in 0 until shared) {
            val difference = (bytes.get(offsets[index] + offset).toInt() and 0xff) - (target[offset].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return length - target.size
    }
}

/** Matches each domain and its parent suffixes against the artifact's sorted mapped index. */
internal class DescendantQueryMatcher(private val queries: MappedRuleTable) {
    fun markMatches(bytes: ByteBuffer, start: Int, length: Int, matches: BooleanArray) {
        var suffixStart = start
        val end = start + length
        while (suffixStart < end) {
            val queryIndex = queries.indexOf(bytes, suffixStart, end - suffixStart)
            if (queryIndex >= 0) matches[queryIndex] = true
            while (suffixStart < end && bytes.get(suffixStart) != '.'.code.toByte()) suffixStart++
            suffixStart++
        }
    }
}
