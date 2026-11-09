package com.lavasec.app.platform

import com.lavasec.app.foundation.filter.ArtifactConfigurationIdentity
import com.lavasec.app.foundation.filter.BlocklistFormat
import com.lavasec.app.foundation.filter.DomainRule
import com.lavasec.app.foundation.filter.FilterCompileRequest
import com.lavasec.app.foundation.filter.FilterCompiler
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.FilterSourcePayload
import com.lavasec.app.foundation.filter.ManualDomainRules
import com.lavasec.app.foundation.filter.PreparedFilterArtifact
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal data class TestFilterCompileAuthorities(
    val catalog: FilterCatalogCompileAuthority,
    val acceptedSources: AcceptedFilterSourceAuthority,
)

internal class RealFilterArtifactFixture private constructor(
    private val root: File,
    private val prepared: PreparedFilterArtifact,
) : AutoCloseable {
    val file: File get() = prepared.file
    val reference = FilterArtifactReference(
        sha256 = prepared.sha256,
        artifactIdentity = prepared.identity,
    )
    val compilationReceipt get() = prepared.compilationReceipt
    val publicationArtifact get() = prepared

    fun read(
        file: File = this.file,
        reference: FilterArtifactReference = this.reference,
    ): DirectBootArtifactReadResult =
        DirectBootArtifactReader.verifying { file }.read(admittedSnapshot(reference))

    fun verifiedArtifact(): VerifiedFilterArtifact {
        val available = CredentialFilterArtifactStagingVerifier.verify(
            file = file,
            compilationReceipt = compilationReceipt,
        ) as CredentialFilterArtifactStagingResult.Verified
        return try {
            available.verifiedArtifact
        } finally {
            available.close()
        }
    }

    fun verifiedArtifactWithRelabeledCompileInputs(
        compileInputsSha256: String,
    ): VerifiedFilterArtifact {
        val original = prepared.identity.compileInputsSha256
        require(original != compileInputsSha256)
        require(compileInputsSha256.matches(Regex("[0-9a-f]{64}")))
        val bytes = file.readBytes()
        val originalBytes = original.encodeToByteArray()
        val replacement = compileInputsSha256.encodeToByteArray()
        val offset = bytes.indexOfSubsequence(originalBytes)
        require(offset >= 0)
        require(bytes.indexOfSubsequence(originalBytes, offset + originalBytes.size) < 0)
        replacement.copyInto(bytes, offset)
        file.writeBytes(bytes)
        val forgedReference = FilterArtifactReference(
            sha256 = sha256(bytes),
            artifactIdentity = prepared.identity.copy(
                compileInputsSha256 = compileInputsSha256,
            ),
        )
        val available = read(reference = forgedReference) as DirectBootArtifactReadResult.Available
        return try {
            available.verifiedArtifact
        } finally {
            available.close()
        }
    }

    override fun close() {
        prepared.close()
        root.deleteRecursively()
    }

    private fun admittedSnapshot(
        reference: FilterArtifactReference,
    ): DirectBootConfigurationSnapshot {
        val artifactConfiguration = reference.artifactIdentity.configuration
        val state = DirectBootAppState.safeDefault().copy(
            configurationIdentity = ConfigurationIdentity(
                artifactConfiguration.generation,
                artifactConfiguration.fingerprint,
            ),
        )
        val key = SecretKeySpec("fixture-direct-boot-key".encodeToByteArray(), "HmacSHA256")
        val authenticator = DirectBootSnapshotAuthenticator { commitmentSha256 ->
            Mac.getInstance("HmacSHA256").run {
                init(key)
                doFinal(commitmentSha256.encodeToByteArray()).joinToString("") { "%02x".format(it) }
            }
        }
        val freshnessAuthority = object : DirectBootSnapshotFreshnessAuthority() {
            override fun rotateCurrentKey(): DirectBootSnapshotKeyRotationResult =
                rotated(authenticator)

            override fun currentAuthenticatorOrNull(): DirectBootSnapshotAuthenticator =
                authenticator
        }
        val epoch = when (val start = freshnessAuthority.beginPublication()) {
            is DirectBootSnapshotPublicationStart.Ready -> start.epoch
            DirectBootSnapshotPublicationStart.RejectedBeforeInvalidation,
            DirectBootSnapshotPublicationStart.InvalidatedWithoutReplacement,
            -> error("fixture freshness key rotation must succeed")
        }
        check(freshnessAuthority.claimAuthoritativeCommit(epoch, state.protectionIntent))
        val committedPublication = requireNotNull(
            freshnessAuthority.confirmAuthoritativeCommit(epoch, state),
        )
        val persisted = DirectBootConfigurationSnapshot.sealForPublication(
            state,
            reference,
            committedPublication,
        )
        return requireNotNull(
            DirectBootConfigurationSnapshot.restore(persisted, freshnessAuthority),
        )
    }

    companion object {
        fun authoritiesFor(configuration: FilterLibraryDraft): TestFilterCompileAuthorities {
            val catalogSources = configuration.profiles
                .flatMap { it.enabledSourceIds }
                .distinct()
                .sorted()
                .map { CatalogCompileSourceIdentity(it, BlocklistFormat.HOSTS) }
            val catalog = FilterCatalogCompileAuthority(
                revision = CATALOG_REVISION,
                sha256 = CATALOG_SHA256,
                sources = catalogSources,
                maxRules = 100,
            )
            val acceptedSources = AcceptedFilterSourceAuthority { requested, _ ->
                requested.map { descriptor ->
                    require(descriptor in catalogSources)
                    sourcePayload(descriptor.id, descriptor.format)
                }
            }
            return TestFilterCompileAuthorities(catalog, acceptedSources)
        }

        suspend fun create(plan: AuthorizedFilterCompilePlan): RealFilterArtifactFixture =
            create(plan.compileRequest)

        suspend fun create(configuration: ConfigurationIdentity): RealFilterArtifactFixture {
            return create(
                FilterCompileRequest(
                    configurationIdentity = ArtifactConfigurationIdentity(
                        configuration.generation,
                        configuration.fingerprint,
                    ),
                    selectedSources = emptyList(),
                    catalogRevision = CATALOG_REVISION,
                    catalogSha256 = CATALOG_SHA256,
                    maxRules = 0,
                    intentionalEmpty = true,
                ),
            )
        }

        suspend fun createForConfiguration(
            configuration: FilterLibraryDraft,
            identity: ConfigurationIdentity,
            compiledManualRules: ManualDomainRules =
                requireNotNull(configuration.profile(configuration.activeProfileId)).manualRules,
        ): RealFilterArtifactFixture {
            val root = Files.createTempDirectory("platform-contract-artifact-").toFile()
            return try {
                val active = requireNotNull(configuration.profile(configuration.activeProfileId))
                val selectedSources =
                    active.enabledSourceIds.sorted().map { sourcePayload(it, BlocklistFormat.HOSTS) }
                val request = FilterCompileRequest(
                    configurationIdentity = ArtifactConfigurationIdentity(
                        identity.generation,
                        identity.fingerprint,
                    ),
                    selectedSources = selectedSources,
                    catalogRevision = CATALOG_REVISION,
                    catalogSha256 = CATALOG_SHA256,
                    maxRules = 100,
                    manualAllowRules = compiledManualRules.allowedDomains.sorted().map(DomainRule::parse),
                    manualBlockRules = compiledManualRules.blockedDomains.sorted().map(DomainRule::parse),
                )
                val prepared = FilterCompiler(File(root, "scratch")).compile(request)
                RealFilterArtifactFixture(root, prepared)
            } catch (error: Throwable) {
                root.deleteRecursively()
                throw error
            }
        }

        suspend fun createForConfiguration(
            configuration: FilterLibraryDraft,
            generation: Long,
            compiledManualRules: ManualDomainRules =
                requireNotNull(configuration.profile(configuration.activeProfileId)).manualRules,
        ): RealFilterArtifactFixture {
            val identity = ConfigurationIdentity(
                generation = generation,
                fingerprint = canonicalFilterConfigurationFingerprint(
                    configuration = configuration,
                    catalogRevision = CATALOG_REVISION,
                    catalogSha256 = CATALOG_SHA256,
                ),
            )
            return createForConfiguration(configuration, identity, compiledManualRules)
        }

        suspend fun create(request: FilterCompileRequest): RealFilterArtifactFixture {
            val root = Files.createTempDirectory("platform-contract-artifact-").toFile()
            return try {
                val prepared = FilterCompiler(File(root, "scratch")).compile(request)
                RealFilterArtifactFixture(root, prepared)
            } catch (error: Throwable) {
                root.deleteRecursively()
                throw error
            }
        }

        private fun sourcePayload(
            sourceId: String,
            format: BlocklistFormat,
        ): FilterSourcePayload {
            val host = sourceHost(sourceId)
            val bytes = when (format) {
                BlocklistFormat.AUTO,
                BlocklistFormat.PLAIN_DOMAINS,
                -> "$host\n"
                BlocklistFormat.HOSTS -> "0.0.0.0 $host\n"
                BlocklistFormat.ADBLOCK -> "||$host^\n"
                BlocklistFormat.DNSMASQ -> "address=/$host/\n"
            }.encodeToByteArray()
            return FilterSourcePayload(
                id = sourceId,
                format = format,
                acceptedSha256 = sha256(bytes),
                declaredByteSize = bytes.size.toLong(),
                openStream = { bytes.inputStream() },
            )
        }

        private fun sourceHost(sourceId: String): String =
            "${sha256(sourceId.encodeToByteArray()).take(16)}.fixture.example"

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        private fun ByteArray.indexOfSubsequence(
            target: ByteArray,
            startIndex: Int = 0,
        ): Int {
            if (target.isEmpty()) return startIndex.coerceAtMost(size)
            for (index in startIndex..size - target.size) {
                if (target.indices.all { offset -> this[index + offset] == target[offset] }) {
                    return index
                }
            }
            return -1
        }

        private const val CATALOG_REVISION = "contract-catalog-revision"
        private val CATALOG_SHA256 = "b".repeat(64)
    }
}

internal fun interface ProtectionIntentAuthoritativeCommit {
    suspend fun commitAndReadBack(desired: Boolean): DirectBootAppState?
}

internal fun interface DirectBootProtectionIntentMirrorPublisher {
    suspend fun publish(
        state: DirectBootAppState,
        committedPublication: DirectBootCommittedSnapshotPublication,
    ): DirectBootMirrorResult
}

/** Test-only factory for exercising the public trusted-adapter subclass boundary. */
internal fun ProtectionIntentWriteCoordinator(
    freshnessAuthority: DirectBootSnapshotFreshnessAuthority,
    authoritativeCommit: ProtectionIntentAuthoritativeCommit,
    mirrorPublisher: DirectBootProtectionIntentMirrorPublisher,
): ProtectionIntentWriteCoordinator =
    object : ProtectionIntentWriteCoordinator(freshnessAuthority) {
        override suspend fun commitAndReadBack(desired: Boolean): DirectBootAppState? =
            authoritativeCommit.commitAndReadBack(desired)

        override suspend fun publishMirror(
            state: DirectBootAppState,
            committedPublication: DirectBootCommittedSnapshotPublication,
        ): DirectBootMirrorResult = mirrorPublisher.publish(state, committedPublication)
    }
