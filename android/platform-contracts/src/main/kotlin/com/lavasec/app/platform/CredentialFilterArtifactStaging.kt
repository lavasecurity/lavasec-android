package com.lavasec.app.platform

import com.lavasec.app.foundation.filter.FilterArtifact
import com.lavasec.app.foundation.filter.FilterArtifactOpenResult
import com.lavasec.app.foundation.filter.FilterArtifactUnavailableReason
import com.lavasec.app.foundation.filter.FilterCompilationReceipt
import java.io.File

/**
 * Result of reopening compiler output at its credential-protected staging location.
 *
 * This proves that the staged bytes still match one exact compiler-minted receipt. Authorization
 * to publish the resulting witness remains with [TransactionalFilterMutationCoordinator], which
 * independently compares the receipt and complete artifact identity with its authorized plan.
 */
sealed interface CredentialFilterArtifactStagingResult {
    class Verified private constructor(
        val artifact: FilterArtifact,
        val verifiedArtifact: VerifiedFilterArtifact,
    ) : CredentialFilterArtifactStagingResult, AutoCloseable {
        override fun close() {
            artifact.close()
        }

        companion object {
            internal fun create(
                artifact: FilterArtifact,
                verifiedArtifact: VerifiedFilterArtifact,
            ): Verified = Verified(artifact, verifiedArtifact)
        }
    }

    data class Unavailable(
        val reason: FilterArtifactUnavailableReason,
    ) : CredentialFilterArtifactStagingResult
}

/**
 * Public production boundary for credential-protected filter preparation.
 *
 * A caller cannot supply its own expected digest or identity: both come from the opaque
 * compiler-minted [FilterCompilationReceipt]. A successful result owns the still-open mapped
 * artifact and must remain alive until publication resolves, then be closed by staging cleanup.
 */
object CredentialFilterArtifactStagingVerifier {
    fun verify(
        file: File,
        compilationReceipt: FilterCompilationReceipt,
    ): CredentialFilterArtifactStagingResult {
        val opened = when (
            val result = FilterArtifact.openResult(
                file = file,
                expectedSha256 = compilationReceipt.artifactSha256,
            )
        ) {
            is FilterArtifactOpenResult.Available -> result.artifact
            is FilterArtifactOpenResult.Unavailable ->
                return CredentialFilterArtifactStagingResult.Unavailable(result.reason)
        }
        if (opened.identity != compilationReceipt.artifactIdentity) {
            opened.close()
            return CredentialFilterArtifactStagingResult.Unavailable(
                FilterArtifactUnavailableReason.IDENTITY_MISMATCH,
            )
        }
        return try {
            val reference = FilterArtifactReference(
                sha256 = compilationReceipt.artifactSha256,
                artifactIdentity = compilationReceipt.artifactIdentity,
            )
            CredentialFilterArtifactStagingResult.Verified.create(
                artifact = opened,
                verifiedArtifact = VerifiedFilterArtifact.fromOpened(reference, opened),
            )
        } catch (error: Throwable) {
            opened.close()
            throw error
        }
    }
}
