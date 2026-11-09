package com.lavasec.app.platform

import com.lavasec.app.foundation.filter.FilterArtifact

/**
 * Restores the post-open witness for one exact persisted artifact/reference pair.
 *
 * The storage boundary must already have opened [artifact] while verifying
 * [FilterArtifactReference.sha256]. This verifier then requires the mapping to remain open and
 * its complete embedded identity to equal [reference]. It accepts neither a pathname nor
 * metadata alone.
 *
 * The caller retains ownership of [artifact] on every outcome. This verifier never closes it.
 */
object PersistedFilterArtifactRestorationVerifier {
    fun verifyOrNull(
        reference: FilterArtifactReference,
        artifact: FilterArtifact,
    ): VerifiedFilterArtifact? =
        try {
            VerifiedFilterArtifact.fromOpened(reference, artifact)
        } catch (_: IllegalStateException) {
            null
        }
}
