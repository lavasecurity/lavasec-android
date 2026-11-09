package com.lavasec.app.storage

import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.filter.BlocklistFormat
import com.lavasec.app.foundation.filter.DeviceQaProbeSet
import com.lavasec.app.foundation.filter.CustomBlocklistSource
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.FilterProfileDraft
import com.lavasec.app.foundation.filter.ManualDomainRules
import com.lavasec.app.foundation.filter.canonicalFilterArtifactIdentitySha256
import com.lavasec.app.foundation.resolver.CatalogResolverTransport
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.foundation.resolver.ResolverProviderId
import com.lavasec.app.foundation.resolver.ResolverSelectionEntry
import com.lavasec.app.foundation.resolver.ResolverTier
import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.platform.ConfirmedFilterState
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.LinkedHashSet

/**
 * Value-only authoritative filter configuration record.
 *
 * This is persisted metadata, not a verified artifact or a runtime capability. Consumers must
 * reopen the exact immutable generation and reconstruct [ConfirmedFilterState] before use.
 */
internal class AuthoritativeFilterConfigurationRecord(
    val configuration: FilterLibraryDraft,
    val configurationIdentity: ConfigurationIdentity,
    val credentialUnlockRequired: Boolean,
    val artifactSha256: String,
    val artifactIdentitySha256: String,
) {
    init {
        require(configurationIdentity.generation > 0L) {
            "authoritative configuration generation must be positive"
        }
        require(configurationIdentity.fingerprint.isCanonicalLowercaseSha256()) {
            "configuration fingerprint must be canonical lowercase SHA-256"
        }
        require(artifactSha256.isCanonicalLowercaseSha256()) {
            "artifact SHA-256 must be canonical lowercase hexadecimal"
        }
        require(artifactIdentitySha256.isCanonicalLowercaseSha256()) {
            "artifact identity commitment must be canonical lowercase SHA-256"
        }
        require(configuration.profiles.size <= AuthoritativeFilterConfigurationRecordCodec.MAX_PROFILES_PER_LIBRARY) {
            "authoritative configuration contains too many profiles"
        }
        configuration.validatePersistedBounds()
        val hasManualRules = configuration.profiles.any { profile ->
            profile.manualRules.allowedDomains.isNotEmpty() ||
                profile.manualRules.blockedDomains.isNotEmpty()
        }
        // The record has no compiled counts. Reopening the committed artifact reconstructs
        // ConfirmedFilterState, which also requires unlock for a Plus-only rule budget.
        require((!hasManualRules && configuration.qaProbeSet == null) || credentialUnlockRequired) {
            "persisted private filter rules must require credential unlock"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is AuthoritativeFilterConfigurationRecord &&
            configuration == other.configuration &&
            configurationIdentity == other.configurationIdentity &&
            credentialUnlockRequired == other.credentialUnlockRequired &&
            artifactSha256 == other.artifactSha256 &&
            artifactIdentitySha256 == other.artifactIdentitySha256

    override fun hashCode(): Int = listOf(
        configuration,
        configurationIdentity,
        credentialUnlockRequired,
        artifactSha256,
        artifactIdentitySha256,
    ).hashCode()

    companion object {
        fun fromConfirmedState(state: ConfirmedFilterState): AuthoritativeFilterConfigurationRecord =
            AuthoritativeFilterConfigurationRecord(
                configuration = state.configuration,
                configurationIdentity = state.configurationIdentity,
                credentialUnlockRequired = state.credentialUnlockRequired,
                artifactSha256 = state.verifiedArtifact.sha256,
                artifactIdentitySha256 = canonicalFilterArtifactIdentitySha256(
                    state.verifiedArtifact.artifactIdentity,
                ),
            )
    }
}

/**
 * Strict, bounded, checksummed version-one binary envelope for the authoritative record.
 */
internal object AuthoritativeFilterConfigurationRecordCodec {
    // Storage capacity is independent of current tier admission. Fifty Plus filters can retain
    // 100,000 manual domains (~25 MiB at the 253-byte DNS maximum), plus source IDs and metadata.
    // Keep one explicit file bound; a lapse must not make the same committed record unreadable.
    const val MAX_RECORD_BYTES = 64 * 1024 * 1024
    const val MAX_STRING_BYTES = 16 * 1024
    const val MAX_SOURCE_IDS_PER_PROFILE = 4_096

    /**
     * The persisted profile-count ceiling. The free tier hosts three filters and the iOS paid
     * tier caps at fifty, so 64 leaves headroom while keeping the record bounded; a library
     * above this can never be written and a record that claims more fails closed on decode.
     */
    const val MAX_PROFILES_PER_LIBRARY = 64

    /**
     * The current on-disk schema. Version one stored no per-filter emoji; a version-one record
     * decodes with each profile's emoji derived from its stable id and name, the same default a
     * fresh filter would receive, so an upgrade never rewrites or loses a saved library. Version
     * two appended an optional custom encrypted endpoint; version three appends the per-filter
     * custom blocklist definitions after each profile's manual rules, then the custom display name
     * and secondary endpoint after the profiles; version four appends the retained ordered resolver
     * ladder (including disabled rows) after the custom resolver shape. A version-one/two/three
     * record decodes with the later fields absent, so an upgrade never invents a source, a name or
     * a disabled row. Version five adds DoQ endpoints; version six adds independent retained row
     * names, including built-in labels. Earlier records read with those later values absent. Version eight adds the optional
     * native QA exact-host set; older records decode with no QA overlay.
     */
    private const val SCHEMA = 8
    private const val SCHEMA_QA_PROBES = 8
    private const val SCHEMA_PLAIN = 7
    private const val SCHEMA_RETAINED_NAME = 6
    private const val SCHEMA_DOQ = 5
    private const val SCHEMA_SAVED_TIERS = 4
    private const val SCHEMA_CUSTOM_NAME = 3
    private const val SCHEMA_EMOJI = 2
    private const val LEGACY_SCHEMA = 1
    private const val MAX_SAVED_TIERS = 2
    private const val CHECKSUM_BYTES = 32
    private const val HEADER_BYTES = 8 + Int.SIZE_BYTES + Int.SIZE_BYTES
    private const val MAX_PAYLOAD_BYTES = MAX_RECORD_BYTES - HEADER_BYTES - CHECKSUM_BYTES
    private val MAGIC = "LAVACFG1".encodeToByteArray()

    fun encode(record: AuthoritativeFilterConfigurationRecord): ByteArray {
        // Preflight the complete payload without retaining it. Oversized input is refused
        // before a 64 MiB growing buffer can exhaust the caller's heap; a full Plus record
        // needs only its final envelope instead of buffer + payload + envelope copies.
        val sizing = BoundedCountingOutputStream(MAX_PAYLOAD_BYTES)
        DataOutputStream(sizing).use { writePayload(record, it) }
        val payloadSize = sizing.count
        val encoded = ByteArray(HEADER_BYTES + payloadSize + CHECKSUM_BYTES)
        ByteBuffer.wrap(encoded).put(MAGIC).putInt(SCHEMA).putInt(payloadSize)
        val payload = FixedByteArrayOutputStream(encoded, HEADER_BYTES, payloadSize)
        DataOutputStream(payload).use { writePayload(record, it) }
        // A borrowed collection changing between passes must not produce a partial or
        // padded record. Growth is independently refused by the fixed output bound.
        require(payload.count == payloadSize) { "authoritative configuration changed during encoding" }
        val checksumOffset = encoded.size - CHECKSUM_BYTES
        sha256(encoded, checksumOffset).copyInto(encoded, checksumOffset)
        return encoded
    }

    private fun writePayload(record: AuthoritativeFilterConfigurationRecord, output: DataOutputStream) {
        output.writeLong(record.configurationIdentity.generation)
        output.writeBoundedString(record.configurationIdentity.fingerprint)
        output.writeByte(if (record.credentialUnlockRequired) 1 else 0)
        output.writeBoundedString(record.artifactSha256)
        output.writeBoundedString(record.artifactIdentitySha256)
        output.writeBoundedString(record.configuration.activeProfileId)
        output.writeBoundedString(record.configuration.resolverPlan.name)
        output.writeInt(record.configuration.profiles.size)
        record.configuration.profiles.forEach { profile ->
            output.writeBoundedString(profile.id)
            output.writeBoundedString(profile.name)
            // User-created filters have no protection level; the empty string is the
            // canonical marker so the codec never invents a level for them.
            output.writeBoundedString(profile.protectionLevel?.name ?: "")
            output.writeBoundedString(profile.emoji)
            output.writeBoundedStrings(
                profile.enabledSourceIds,
                MAX_SOURCE_IDS_PER_PROFILE,
            )
            output.writeBoundedStrings(
                profile.manualRules.allowedDomains,
                ManualDomainRules.MAX_STORED_ALLOWED_DOMAINS,
            )
            output.writeBoundedStrings(
                profile.manualRules.blockedDomains,
                ManualDomainRules.MAX_STORED_BLOCKED_DOMAINS,
            )
            output.writeInt(profile.customBlocklists.size)
            profile.customBlocklists.forEach { source ->
                output.writeBoundedString(source.id)
                output.writeBoundedString(source.displayName)
                output.writeBoundedString(source.sourceUrl)
                output.writeBoundedString(source.parseFormat.name)
                output.writeLong(source.createdAtEpochMillis)
                output.writeBoundedString(source.lastAcceptedHash ?: "")
            }
        }
        // Schema three appends the custom resolver shape after the profiles as four
        // explicit strings: the DoH URL, the display name, the secondary endpoint and the
        // DoT endpoint, each an empty marker when absent (the DoH and DoT endpoints are
        // mutually exclusive). The fixed layout keeps every field unambiguous and lets a
        // pre-existing schema-one/two record decode through the legacy branch below.
        output.writeBoundedString(record.configuration.customDohUrl ?: "")
        output.writeBoundedString(record.configuration.customName ?: "")
        output.writeBoundedString(record.configuration.customSecondaryEndpoint ?: "")
        output.writeBoundedString(record.configuration.customDotEndpoint ?: "")
        output.writeBoundedString(record.configuration.customDoqEndpoint ?: "")
        // Schema four appends the retained ladder: a count (0 when absent), then each row's
        // provider id, transport token, custom name, custom endpoints and enablement. The
        // count is the explicit absence marker, so a pre-existing record decodes to no
        // retained rows and derives the legacy ladder from the effective fields.
        output.writeInt(record.configuration.savedTiers?.size ?: 0)
        record.configuration.savedTiers?.forEach { tier ->
            output.writeBoundedString(tier.entry.providerId.rawId)
            output.writeBoundedString(tier.entry.transport.token)
            output.writeBoundedString(tier.entry.customName ?: "")
            output.writeBoundedString(tier.entry.customDohUrl ?: "")
            output.writeBoundedString(tier.entry.customDotEndpoint ?: "")
            output.writeBoundedString(tier.entry.customSecondaryEndpoint ?: "")
            output.writeByte(if (tier.isEnabled) 1 else 0)
            output.writeBoundedString(tier.entry.customDoqEndpoint ?: "")
            output.writeBoundedString(tier.retainedName ?: "")
            output.writeBoundedString(tier.entry.customPlainEndpoint ?: "")
        }
        val probes = record.configuration.qaProbeSet
        output.writeByte(if (probes == null) 0 else 1)
        probes?.allDomains?.forEach { output.writeBoundedString(it) }
    }

    fun decodeOrNull(bytes: ByteArray): AuthoritativeFilterConfigurationRecord? {
        if (bytes.size !in (HEADER_BYTES + CHECKSUM_BYTES)..MAX_RECORD_BYTES) return null
        return try {
            val envelope = ByteBuffer.wrap(bytes)
            val magic = ByteArray(MAGIC.size).also(envelope::get)
            if (!magic.contentEquals(MAGIC)) return null
            val schema = envelope.int
            if (schema !in LEGACY_SCHEMA..SCHEMA) return null
            val payloadSize = envelope.int
            if (payloadSize !in 0..MAX_PAYLOAD_BYTES) return null
            val expectedSize = HEADER_BYTES.toLong() + payloadSize.toLong() + CHECKSUM_BYTES
            if (expectedSize != bytes.size.toLong()) return null

            val checksumOffset = HEADER_BYTES + payloadSize
            val expectedChecksum = bytes.copyOfRange(checksumOffset, bytes.size)
            val actualChecksum = sha256(bytes, checksumOffset)
            if (!MessageDigest.isEqual(expectedChecksum, actualChecksum)) return null

            decodePayload(bytes, HEADER_BYTES, payloadSize, schema)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodePayload(
        payload: ByteArray,
        offset: Int,
        length: Int,
        schema: Int,
    ): AuthoritativeFilterConfigurationRecord {
        DataInputStream(ByteArrayInputStream(payload, offset, length)).use { input ->
            val generation = input.readLong()
            val configurationFingerprint = input.readBoundedString()
            val credentialUnlockRequired = when (input.readUnsignedByte()) {
                0 -> false
                1 -> true
                else -> throw InvalidAuthoritativeRecordException()
            }
            val artifactSha256 = input.readBoundedString()
            val artifactIdentitySha256 = input.readBoundedString()
            val activeProfileId = input.readBoundedString()
            val resolverPlanName = input.readBoundedString()
            val resolverPlan = MilestoneResolverPlan.entries.singleOrNull {
                it.name == resolverPlanName
            } ?: throw InvalidAuthoritativeRecordException()
            val profileCount = input.readBoundedCount(
                AuthoritativeFilterConfigurationRecordCodec.MAX_PROFILES_PER_LIBRARY,
            )
            val profiles = ArrayList<FilterProfileDraft>(profileCount)
            repeat(profileCount) {
                val id = input.readBoundedString()
                val name = input.readBoundedString()
                val protectionLevelName = input.readBoundedString()
                val protectionLevel = if (protectionLevelName.isEmpty()) {
                    null
                } else {
                    ProtectionLevel.entries.singleOrNull {
                        it.name == protectionLevelName
                    } ?: throw InvalidAuthoritativeRecordException()
                }
                // Version one stored no emoji; the profile then derives the canonical default
                // for its id and name. Version two and later carry the committed emoji verbatim.
                val emoji = if (schema >= SCHEMA_EMOJI) input.readBoundedString() else null
                val sourceIds = input.readUniqueStringSet(MAX_SOURCE_IDS_PER_PROFILE)
                val allowedDomains = input.readUniqueStringSet(ManualDomainRules.MAX_STORED_ALLOWED_DOMAINS)
                val blockedDomains = input.readUniqueStringSet(ManualDomainRules.MAX_STORED_BLOCKED_DOMAINS)
                val manualRules = ManualDomainRules(
                    allowedDomains = allowedDomains,
                    blockedDomains = blockedDomains,
                )
                if (
                    manualRules.allowedDomains != allowedDomains ||
                    manualRules.blockedDomains != blockedDomains
                ) {
                    throw InvalidAuthoritativeRecordException()
                }
                // Version three appends the custom blocklist definitions after the manual rules;
                // a version-one/two profile carries none.
                val customBlocklists = if (schema >= SCHEMA_CUSTOM_NAME) {
                    readCustomBlocklists(input)
                } else {
                    emptyList()
                }
                profiles += FilterProfileDraft(
                    id = id,
                    name = name,
                    protectionLevel = protectionLevel,
                    enabledSourceIds = sourceIds,
                    manualRules = manualRules,
                    emoji = emoji,
                    customBlocklists = customBlocklists,
                )
            }
            // Schema three appends the custom resolver shape as four explicit strings, each an
            // empty marker when absent; any trailing byte or an incoherent shape fails closed.
            // A schema one/two record carries only the endpoint shape (a DoH URL, or an empty
            // marker followed by a DoT endpoint) and decodes with no name or secondary.
            val customDohUrl: String?
            val customName: String?
            val customSecondaryEndpoint: String?
            val customDotEndpoint: String?
            if (schema >= SCHEMA_CUSTOM_NAME) {
                customDohUrl = input.readBoundedString().takeIf { it.isNotEmpty() }
                customName = input.readBoundedString().takeIf { it.isNotEmpty() }
                customSecondaryEndpoint = input.readBoundedString().takeIf { it.isNotEmpty() }
                customDotEndpoint = input.readBoundedString().takeIf { it.isNotEmpty() }
            } else {
                customName = null
                customSecondaryEndpoint = null
                if (input.available() == 0) {
                    customDohUrl = null
                    customDotEndpoint = null
                } else {
                    val first = input.readBoundedString()
                    if (first.isEmpty()) {
                        customDohUrl = null
                        customDotEndpoint = if (input.available() == 0) {
                            null
                        } else {
                            input.readBoundedString().also {
                                if (input.available() != 0) throw InvalidAuthoritativeRecordException()
                            }
                        }
                    } else {
                        customDohUrl = first
                        customDotEndpoint = if (input.available() == 0) {
                            null
                        } else {
                            input.readBoundedString().also {
                                if (input.available() != 0) throw InvalidAuthoritativeRecordException()
                            }
                        }
                    }
                }
            }
            // Schema four appends the retained ladder; a count of zero means absent and the
            // legacy effective fields decode to the equivalent ladder. Any trailing byte fails.
            val customDoqEndpoint = if (schema >= SCHEMA_DOQ) {
                input.readBoundedString().ifEmpty { null }
            } else null
            val savedTiers = if (schema >= SCHEMA_SAVED_TIERS) {
                readSavedTiers(input, schema)
            } else {
                null
            }
            val probes = if (schema >= SCHEMA_QA_PROBES) {
                when (input.readUnsignedByte()) {
                    0 -> null
                    1 -> {
                        val domains = List(4) { input.readBoundedString() }
                        DeviceQaProbeSet.fromDomains(domains[0], domains[1], domains[2], domains[3]).also {
                            if (it.allDomains != domains) throw InvalidAuthoritativeRecordException()
                        }
                    }
                    else -> throw InvalidAuthoritativeRecordException()
                }
            } else null
            if (input.available() != 0) throw InvalidAuthoritativeRecordException()

            val configuration = FilterLibraryDraft(
                profiles = profiles,
                activeProfileId = activeProfileId,
                resolverPlan = resolverPlan,
                customDohUrl = customDohUrl,
                customDotEndpoint = customDotEndpoint,
                customDoqEndpoint = customDoqEndpoint,
                customName = customName,
                customSecondaryEndpoint = customSecondaryEndpoint,
                savedTiers = savedTiers,
                qaProbeSet = probes,
            )
            return AuthoritativeFilterConfigurationRecord(
                configuration = configuration,
                configurationIdentity = ConfigurationIdentity(
                    generation = generation,
                    fingerprint = configurationFingerprint,
                ),
                credentialUnlockRequired = credentialUnlockRequired,
                artifactSha256 = artifactSha256,
                artifactIdentitySha256 = artifactIdentitySha256,
            )
        }
    }

    private fun DataOutputStream.writeBoundedStrings(
        values: Set<String>,
        maxCount: Int,
    ) {
        require(values.size <= maxCount) {
            "persisted collection count exceeds limit"
        }
        writeInt(values.size)
        values.forEach { writeBoundedString(it) }
    }

    private fun DataOutputStream.writeBoundedString(value: String) {
        val encoded = value.encodePersistedUtf8()
        require(encoded.size <= MAX_STRING_BYTES) { "persisted string exceeds limit" }
        writeInt(encoded.size)
        write(encoded)
    }

    private fun readSavedTiers(input: DataInputStream, schema: Int): List<ResolverTier>? {
        val count = input.readBoundedCount(MAX_SAVED_TIERS)
        if (count == 0) return null
        val tiers = ArrayList<ResolverTier>(count)
        repeat(count) {
            val providerRaw = input.readBoundedString()
            val providerId = ResolverProviderId.entries.singleOrNull { it.rawId == providerRaw }
                ?: throw InvalidAuthoritativeRecordException()
            val transportToken = input.readBoundedString()
            val transport = CatalogResolverTransport.entries.singleOrNull { it.token == transportToken }
                ?: throw InvalidAuthoritativeRecordException()
            val name = input.readBoundedString().ifEmpty { null }
            val doh = input.readBoundedString().ifEmpty { null }
            val dot = input.readBoundedString().ifEmpty { null }
            val secondary = input.readBoundedString().ifEmpty { null }
            val enabled = when (input.readUnsignedByte()) {
                0 -> false
                1 -> true
                else -> throw InvalidAuthoritativeRecordException()
            }
            val doq = if (schema >= SCHEMA_DOQ) input.readBoundedString().ifEmpty { null } else null
            val retainedName = if (schema >= SCHEMA_RETAINED_NAME) input.readBoundedString().ifEmpty { null } else null
            val plain = if (schema >= SCHEMA_PLAIN) input.readBoundedString().ifEmpty { null } else null
            val entry = try {
                ResolverSelectionEntry(providerId, transport, doh, dot, name, secondary, doq, plain)
            } catch (_: IllegalArgumentException) {
                throw InvalidAuthoritativeRecordException()
            }
            tiers += ResolverTier(entry, enabled, retainedName)
        }
        return tiers
    }

    private fun readCustomBlocklists(input: DataInputStream): List<CustomBlocklistSource> {
        val count = input.readBoundedCount(CustomBlocklistSource.MAX_CUSTOM_BLOCKLISTS_PER_PROFILE)
        val sources = ArrayList<CustomBlocklistSource>(count)
        val ids = LinkedHashSet<String>(count)
        repeat(count) {
            val id = input.readBoundedString()
            val displayName = input.readBoundedString()
            val sourceUrl = input.readBoundedString()
            val formatName = input.readBoundedString()
            val format = BlocklistFormat.entries.singleOrNull { it.name == formatName }
                ?: throw InvalidAuthoritativeRecordException()
            val createdAtEpochMillis = input.readLong()
            val lastAcceptedHash = input.readBoundedString().ifEmpty { null }
            if (!ids.add(id)) throw InvalidAuthoritativeRecordException()
            sources += CustomBlocklistSource(
                id = id,
                displayName = displayName,
                sourceUrl = sourceUrl,
                parseFormat = format,
                createdAtEpochMillis = createdAtEpochMillis,
                lastAcceptedHash = lastAcceptedHash,
            )
        }
        return sources
    }

    private fun DataInputStream.readUniqueStringSet(maxCount: Int): Set<String> {
        val count = readBoundedCount(maxCount)
        val values = LinkedHashSet<String>(count)
        repeat(count) {
            if (!values.add(readBoundedString())) throw InvalidAuthoritativeRecordException()
        }
        return values
    }

    private fun DataInputStream.readBoundedCount(maxCount: Int): Int {
        val count = readInt()
        if (count !in 0..maxCount) throw InvalidAuthoritativeRecordException()
        return count
    }

    private fun DataInputStream.readBoundedString(): String {
        val size = readInt()
        if (size !in 0..MAX_STRING_BYTES) throw InvalidAuthoritativeRecordException()
        val encoded = ByteArray(size)
        readFully(encoded)
        return StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(encoded))
            .toString()
    }

    private fun sha256(bytes: ByteArray, length: Int): ByteArray =
        MessageDigest.getInstance("SHA-256").apply { update(bytes, 0, length) }.digest()
}

private fun FilterLibraryDraft.validatePersistedBounds() {
    activeProfileId.requirePersistedStringBound()
    resolverPlan.name.requirePersistedStringBound()
    customDohUrl?.requirePersistedStringBound()
    customDotEndpoint?.requirePersistedStringBound()
    customDoqEndpoint?.requirePersistedStringBound()
    customName?.requirePersistedStringBound()
    customSecondaryEndpoint?.requirePersistedStringBound()
    qaProbeSet?.allDomains?.forEach(String::requirePersistedStringBound)
    require(
        profiles.size <=
            AuthoritativeFilterConfigurationRecordCodec.MAX_PROFILES_PER_LIBRARY,
    ) {
        "profile count exceeds persisted limit"
    }
    profiles.forEach { profile ->
        profile.id.requirePersistedStringBound()
        profile.name.requirePersistedStringBound()
        profile.protectionLevel?.name?.requirePersistedStringBound()
        profile.emoji.requirePersistedStringBound()
        require(
            profile.enabledSourceIds.size <=
                AuthoritativeFilterConfigurationRecordCodec.MAX_SOURCE_IDS_PER_PROFILE,
        ) {
            "enabled source count exceeds persisted limit"
        }
        profile.enabledSourceIds.forEach(String::requirePersistedStringBound)
        profile.manualRules.allowedDomains.forEach(String::requirePersistedStringBound)
        profile.manualRules.blockedDomains.forEach(String::requirePersistedStringBound)
        require(
            profile.customBlocklists.size <=
                CustomBlocklistSource.MAX_CUSTOM_BLOCKLISTS_PER_PROFILE,
        ) {
            "custom blocklist count exceeds persisted limit"
        }
        profile.customBlocklists.forEach { source ->
            source.id.requirePersistedStringBound()
            source.displayName.requirePersistedStringBound()
            source.sourceUrl.requirePersistedStringBound()
            source.parseFormat.name.requirePersistedStringBound()
            source.lastAcceptedHash?.requirePersistedStringBound()
        }
    }
}

private fun String.requirePersistedStringBound() {
    require(
        encodePersistedUtf8().size <=
            AuthoritativeFilterConfigurationRecordCodec.MAX_STRING_BYTES,
    ) {
        "string exceeds authoritative record limit"
    }
}

private fun String.encodePersistedUtf8(): ByteArray {
    return try {
        val encoded = StandardCharsets.UTF_8
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(this))
        ByteArray(encoded.remaining()).also(encoded::get)
    } catch (error: CharacterCodingException) {
        throw IllegalArgumentException("persisted string is not valid Unicode", error)
    }
}

private fun String.isCanonicalLowercaseSha256(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

private class BoundedCountingOutputStream(
    private val maximumBytes: Int,
) : OutputStream() {
    var count: Int = 0
        private set

    init { require(maximumBytes >= 0) }

    override fun write(value: Int) {
        require(count < maximumBytes) { "authoritative configuration payload exceeds persisted limit" }
        count++
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length && count <= maximumBytes - length) {
            "authoritative configuration payload exceeds persisted limit"
        }
        count += length
    }
}

private class FixedByteArrayOutputStream(
    private val destination: ByteArray,
    private val start: Int,
    private val maximumBytes: Int,
) : OutputStream() {
    var count: Int = 0
        private set

    init {
        require(start >= 0 && maximumBytes >= 0 && start <= destination.size - maximumBytes)
    }

    override fun write(value: Int) {
        require(count < maximumBytes) { "authoritative configuration changed during encoding" }
        destination[start + count] = value.toByte()
        count++
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length && count <= maximumBytes - length) {
            "authoritative configuration changed during encoding"
        }
        bytes.copyInto(destination, start + count, offset, offset + length)
        count += length
    }
}

private class InvalidAuthoritativeRecordException : IllegalArgumentException()
