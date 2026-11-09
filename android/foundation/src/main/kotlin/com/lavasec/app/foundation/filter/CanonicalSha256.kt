package com.lavasec.app.foundation.filter

import java.security.MessageDigest

internal const val FILTER_ARTIFACT_MAX_STRING_BYTES = 256 * 1024

/**
 * Streaming, collision-unambiguous encoder for cross-module SHA-256 identities.
 *
 * Strings use standard UTF-8 with a four-byte byte-length prefix, matching the artifact identity
 * format and avoiding [java.io.DataOutputStream.writeUTF]'s hidden 64 KiB modified-UTF limit.
 */
class CanonicalSha256Encoder internal constructor(
    private val digest: MessageDigest,
) {
    fun writeInt(value: Int) {
        digest.update(
            byteArrayOf(
                (value ushr 24).toByte(),
                (value ushr 16).toByte(),
                (value ushr 8).toByte(),
                value.toByte(),
            ),
        )
    }

    fun writeLong(value: Long) {
        digest.update(
            byteArrayOf(
                (value ushr 56).toByte(),
                (value ushr 48).toByte(),
                (value ushr 40).toByte(),
                (value ushr 32).toByte(),
                (value ushr 24).toByte(),
                (value ushr 16).toByte(),
                (value ushr 8).toByte(),
                value.toByte(),
            ),
        )
    }

    fun writeBoolean(value: Boolean) {
        digest.update((if (value) 1 else 0).toByte())
    }

    fun writeString(value: String) {
        val bytes = value.encodeToByteArray()
        require(bytes.size <= FILTER_ARTIFACT_MAX_STRING_BYTES) {
            "canonical identity string exceeds artifact format limit"
        }
        writeInt(bytes.size)
        digest.update(bytes)
    }

    internal fun finish(): String = digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Computes a domain-separated canonical SHA-256 identity without buffering the encoded payload.
 */
fun canonicalSha256(
    domain: String,
    encode: CanonicalSha256Encoder.() -> Unit,
): String {
    val encoder = CanonicalSha256Encoder(MessageDigest.getInstance("SHA-256"))
    encoder.writeString(domain)
    encoder.encode()
    return encoder.finish()
}

/**
 * Commits to every behavior-bearing field embedded in one immutable filter artifact.
 *
 * The ordered source list is intentionally not sorted: source order is part of the compiler
 * receipt and a reordered identity must not share a publication pointer commitment.
 */
fun canonicalFilterArtifactIdentitySha256(
    identity: FilterArtifactIdentity,
): String = canonicalSha256("filter-artifact-identity-v1") {
    writeLong(identity.configuration.generation)
    writeString(identity.configuration.fingerprint)
    writeInt(identity.selectedSources.size)
    identity.selectedSources.forEach { source ->
        writeString(source.id)
        writeString(source.acceptedSha256)
        writeString(source.format.name)
    }
    writeString(identity.catalogRevision)
    writeString(identity.catalogSha256)
    writeInt(identity.artifactSchema)
    writeInt(identity.pointerSchema)
    writeInt(identity.parserRulesVersion)
    writeInt(identity.ruleCounts.blockExact)
    writeInt(identity.ruleCounts.blockSuffix)
    writeInt(identity.ruleCounts.allowExact)
    writeInt(identity.ruleCounts.allowSuffix)
    writeInt(identity.ruleCounts.guardrailExact)
    writeInt(identity.ruleCounts.guardrailSuffix)
    writeString(identity.ruleTablesSha256)
    writeString(identity.compileInputsSha256)
    writeBoolean(identity.intentionalEmpty)
}
