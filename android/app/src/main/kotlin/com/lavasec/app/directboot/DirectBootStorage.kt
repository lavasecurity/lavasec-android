package com.lavasec.app.directboot

import com.lavasec.app.foundation.filter.ArtifactConfigurationIdentity
import com.lavasec.app.foundation.filter.ArtifactRuleCounts
import com.lavasec.app.foundation.filter.ArtifactSourceIdentity
import com.lavasec.app.foundation.filter.BlocklistFormat
import com.lavasec.app.foundation.filter.FilterArtifactIdentity
import com.lavasec.app.foundation.resolver.CatalogResolverTransport
import com.lavasec.app.foundation.resolver.ResolverProviderId
import com.lavasec.app.foundation.resolver.ResolverSelectionEntry
import com.lavasec.app.foundation.resolver.ResolverSelection
import com.lavasec.app.foundation.resolver.ResolverTier
import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.platform.DirectBootAppState
import com.lavasec.app.platform.DirectBootConfigurationSnapshot
import com.lavasec.app.platform.DirectBootArtifactReadResult
import com.lavasec.app.platform.DirectBootArtifactReader
import com.lavasec.app.platform.DirectBootSnapshotFreshnessAuthority
import com.lavasec.app.platform.FilterArtifactReference
import com.lavasec.app.platform.PersistedDirectBootConfigurationSnapshot
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ConcurrentHashMap

internal object DirectBootStorageLayout {
    private const val ARTIFACT_DIRECTORY = "artifacts"
    private const val SNAPSHOT_FILE = "configuration.snapshot"

    fun artifacts(root: File): File = File(root, ARTIFACT_DIRECTORY)
    fun snapshot(root: File): File = File(root, SNAPSHOT_FILE)
    fun artifact(root: File, digest: String): File = File(artifacts(root), "$digest.lsfa")
}

internal object DirectBootRootLocks {
    private val locks = ConcurrentHashMap<String, Any>()

    fun forRoot(root: File): Any = locks.getOrPut(root.absoluteFile.normalize().path) { Any() }
}

/** Strict bounded binary codec for the untrusted authenticated Direct Boot envelope. */
internal object DirectBootSnapshotCodec {
    private val MAGIC = "LAVADBS1".encodeToByteArray()

    /**
     * Version two appends the custom DoH endpoint to the state; version three appends the custom
     * DoT endpoint after it. Older records still decode with the newer endpoint absent, so an
     * upgrade never rejects a published mirror. Version four adds DoQ; version five adds custom
     * name/secondary and the exact retained ladder, including labels and disabled positions.
     * Version six appends custom plain IP-literal endpoints to those rows.
     */
    private const val VERSION = 6
    private const val PLAIN_TIERS_VERSION = 6
    private const val CUSTOM_DOQ_VERSION = 4
    private const val RETAINED_TIERS_VERSION = 5
    private const val CUSTOM_DOT_VERSION = 3
    private const val LEGACY_VERSION = 1
    private const val CUSTOM_DOH_VERSION = 2
    private const val MAX_BYTES = 1024 * 1024
    private const val MAX_STRING_BYTES = 64 * 1024
    private const val MAX_SOURCES = 10_000

    fun encode(snapshot: PersistedDirectBootConfigurationSnapshot): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(MAGIC)
                output.writeInt(VERSION)
                output.writeState(snapshot.state)
                output.writeReference(snapshot.artifact)
                output.writeString(snapshot.authenticationTag)
            }
            bytes.toByteArray().also { require(it.size in 1..MAX_BYTES) }
        }

    fun decodeOrNull(file: File): PersistedDirectBootConfigurationSnapshot? {
        if (!file.isFile || file.length() !in 1..MAX_BYTES.toLong()) return null
        return try {
            val bytes = file.readBytes()
            if (bytes.size !in 1..MAX_BYTES) return null
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val magic = ByteArray(MAGIC.size).also(input::readFully)
                if (!magic.contentEquals(MAGIC)) return null
                val version = input.readInt()
                // Accept every version the state reader supports, including the intermediate
                // version 2 (custom DoH) an earlier build wrote; rejecting it would drop the
                // Direct Boot mirror on upgrade.
                if (version !in LEGACY_VERSION..VERSION) return null
                val snapshot = PersistedDirectBootConfigurationSnapshot(
                    state = input.readState(version),
                    artifact = input.readReference(),
                    authenticationTag = input.readString(),
                )
                if (input.read() != -1) return null
                snapshot
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun DataOutputStream.writeState(state: DirectBootAppState) {
        writeBoolean(state.onboardingCompleted)
        writeBoolean(state.protectionIntent)
        writeBoolean(state.useDeviceDns)
        writeBoolean(state.useEncryptedFallback)
        writeNullableString(state.encryptedFallbackId)
        writeNullableString(state.activeProfileId)
        writeBoolean(state.configurationIdentity != null)
        state.configurationIdentity?.let { identity ->
            writeLong(identity.generation)
            writeString(identity.fingerprint)
        }
        writeBoolean(state.credentialUnlockRequired)
        // Version two appends a custom DoH endpoint; version three appends the custom DoT endpoint
        // after it. Written for every record so the version determines the layout; a legacy record
        // decodes with the later fields absent.
        writeNullableString(state.customDohUrl)
        writeNullableString(state.customDotEndpoint)
        writeNullableString(state.customDoqEndpoint)
        writeNullableString(state.customName)
        writeNullableString(state.customSecondaryEndpoint)
        writeBoolean(state.savedTiers != null)
        state.savedTiers?.let { tiers ->
            require(tiers.size in 1..2)
            writeInt(tiers.size)
            tiers.forEach { tier ->
                writeString(tier.entry.providerId.rawId)
                writeString(tier.entry.transport.name)
                writeNullableString(tier.entry.customDohUrl)
                writeNullableString(tier.entry.customDotEndpoint)
                writeNullableString(tier.entry.customDoqEndpoint)
                writeNullableString(tier.entry.customName)
                writeNullableString(tier.entry.customSecondaryEndpoint)
                writeBoolean(tier.isEnabled)
                writeNullableString(tier.retainedName)
                writeNullableString(tier.entry.customPlainEndpoint)
            }
        }
    }

    private fun DataInputStream.readState(version: Int): DirectBootAppState {
        val onboardingCompleted = readBoolean()
        val protectionIntent = readBoolean()
        val useDeviceDns = readBoolean()
        val useEncryptedFallback = readBoolean()
        val encryptedFallbackId = readNullableString()
        val activeProfileId = readNullableString()
        val configurationIdentity = if (readBoolean()) {
            ConfigurationIdentity(readLong(), readString())
        } else {
            null
        }
        val credentialUnlockRequired = readBoolean()
        val customDohUrl = if (version >= CUSTOM_DOH_VERSION) readNullableString() else null
        val customDotEndpoint = if (version >= CUSTOM_DOT_VERSION) readNullableString() else null
        val customDoqEndpoint = if (version >= CUSTOM_DOQ_VERSION) readNullableString() else null
        val customName = if (version >= RETAINED_TIERS_VERSION) readNullableString() else null
        val customSecondaryEndpoint = if (version >= RETAINED_TIERS_VERSION) readNullableString() else null
        val savedTiers = if (version >= RETAINED_TIERS_VERSION && readBoolean()) {
            List(readInt().also { require(it in 1..2) }) {
                val providerId = readString()
                val transport = readString()
                val doh = readNullableString()
                val dot = readNullableString()
                val doq = readNullableString()
                val customName = readNullableString()
                val secondary = readNullableString()
                val enabled = readBoolean()
                val name = readNullableString()
                val plain = if (version >= PLAIN_TIERS_VERSION) readNullableString() else null
                val entry = ResolverSelectionEntry(
                    providerId = ResolverProviderId.entries.first { it.rawId == providerId },
                    transport = CatalogResolverTransport.entries.first { it.name == transport },
                    customDohUrl = doh, customDotEndpoint = dot, customDoqEndpoint = doq,
                    customName = customName, customSecondaryEndpoint = secondary, customPlainEndpoint = plain,
                )
                ResolverTier(entry, enabled, name)
            }.also { ResolverSelection(it) }
        } else null
        return DirectBootAppState(
            onboardingCompleted = onboardingCompleted,
            protectionIntent = protectionIntent,
            useDeviceDns = useDeviceDns,
            useEncryptedFallback = useEncryptedFallback,
            encryptedFallbackId = encryptedFallbackId,
            customDohUrl = customDohUrl,
            customDotEndpoint = customDotEndpoint,
            customDoqEndpoint = customDoqEndpoint,
            customName = customName,
            customSecondaryEndpoint = customSecondaryEndpoint,
            savedTiers = savedTiers,
            activeProfileId = activeProfileId,
            configurationIdentity = configurationIdentity,
            credentialUnlockRequired = credentialUnlockRequired,
        )
    }

    private fun DataOutputStream.writeReference(reference: FilterArtifactReference) {
        writeString(reference.sha256)
        val identity = reference.artifactIdentity
        writeLong(identity.configuration.generation)
        writeString(identity.configuration.fingerprint)
        require(identity.selectedSources.size <= MAX_SOURCES)
        writeInt(identity.selectedSources.size)
        identity.selectedSources.forEach { source ->
            writeString(source.id)
            writeString(source.acceptedSha256)
            writeInt(source.format.ordinal)
        }
        writeString(identity.catalogRevision)
        writeString(identity.catalogSha256)
        writeInt(identity.artifactSchema)
        writeInt(identity.pointerSchema)
        writeInt(identity.parserRulesVersion)
        identity.ruleCounts.asList().forEach(::writeInt)
        writeString(identity.ruleTablesSha256)
        writeString(identity.compileInputsSha256)
        writeBoolean(identity.intentionalEmpty)
    }

    private fun DataInputStream.readReference(): FilterArtifactReference {
        val artifactSha256 = readString()
        val configuration = ArtifactConfigurationIdentity(readLong(), readString())
        val sourceCount = readInt().also { require(it in 0..MAX_SOURCES) }
        val sources = List(sourceCount) {
            ArtifactSourceIdentity(
                id = readString(),
                acceptedSha256 = readString(),
                format = BlocklistFormat.entries.getOrNull(readInt())
                    ?: throw IllegalArgumentException("unknown source format"),
            )
        }
        val catalogRevision = readString()
        val catalogSha256 = readString()
        val artifactSchema = readInt()
        val pointerSchema = readInt()
        val parserRulesVersion = readInt()
        val counts = IntArray(6) { readInt().also { count -> require(count >= 0) } }
        return FilterArtifactReference(
            sha256 = artifactSha256,
            artifactIdentity = FilterArtifactIdentity(
                configuration = configuration,
                selectedSources = sources,
                catalogRevision = catalogRevision,
                catalogSha256 = catalogSha256,
                artifactSchema = artifactSchema,
                pointerSchema = pointerSchema,
                parserRulesVersion = parserRulesVersion,
                ruleCounts = ArtifactRuleCounts(
                    blockExact = counts[0],
                    blockSuffix = counts[1],
                    allowExact = counts[2],
                    allowSuffix = counts[3],
                    guardrailExact = counts[4],
                    guardrailSuffix = counts[5],
                ),
                ruleTablesSha256 = readString(),
                compileInputsSha256 = readString(),
                intentionalEmpty = readBoolean(),
            ),
        )
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        value?.let { writeString(it) }
    }

    private fun DataInputStream.readNullableString(): String? =
        if (readBoolean()) readString() else null

    private fun DataOutputStream.writeString(value: String) {
        val encoded = value.encodeToByteArray()
        require(encoded.size <= MAX_STRING_BYTES)
        writeInt(encoded.size)
        write(encoded)
    }

    private fun DataInputStream.readString(): String {
        val size = readInt().also { require(it in 0..MAX_STRING_BYTES) }
        val bytes = ByteArray(size).also(::readFully)
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    private fun ArtifactRuleCounts.asList(): List<Int> = listOf(
        blockExact,
        blockSuffix,
        allowExact,
        allowSuffix,
        guardrailExact,
        guardrailSuffix,
    )
}

internal class DeviceProtectedConfigurationReader(
    private val root: File,
    private val freshnessAuthority: DirectBootSnapshotFreshnessAuthority,
) : com.lavasec.app.platform.DirectBootConfigurationReader {
    override fun readOrNull(): DirectBootConfigurationSnapshot? =
        synchronized(DirectBootRootLocks.forRoot(root)) {
            val persisted = DirectBootSnapshotCodec.decodeOrNull(
                DirectBootStorageLayout.snapshot(root),
            ) ?: return@synchronized null
            DirectBootConfigurationSnapshot.restore(persisted, freshnessAuthority)
        }
}

/** Reopens the exact public artifact before exposing any Direct Boot behavior state. */
internal fun readVerifiedDeviceProtectedStateOrNull(
    root: File,
    freshnessAuthority: DirectBootSnapshotFreshnessAuthority,
): DirectBootAppState? =
    readVerifiedDeviceProtectedSnapshotOrNull(root, freshnessAuthority)?.state

/** Returns the authenticated envelope only after reopening its exact artifact successfully. */
internal fun readVerifiedDeviceProtectedSnapshotOrNull(
    root: File,
    freshnessAuthority: DirectBootSnapshotFreshnessAuthority,
): DirectBootConfigurationSnapshot? {
    val snapshot = DeviceProtectedConfigurationReader(root, freshnessAuthority).readOrNull()
        ?: return null
    return when (
        val opened = DirectBootArtifactReader.verifying { reference ->
            DirectBootStorageLayout.artifact(root, reference.sha256)
        }.read(snapshot)
    ) {
        is DirectBootArtifactReadResult.Available -> opened.use { snapshot }
        is DirectBootArtifactReadResult.Unavailable -> null
    }
}
