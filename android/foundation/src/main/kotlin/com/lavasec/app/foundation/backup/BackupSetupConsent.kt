package com.lavasec.app.foundation.backup

import java.util.UUID

/**
 * Explicit acknowledgments for one generated recovery phrase / setup attempt.
 *
 * Ported from the canonical iOS `BackupSetupConsent` (pinned at `c4658d9`). A copy action never
 * asserts that the user saved the phrase or accepted the loss of recovery: [copiedRecoveryPhrase]
 * is recorded separately, and [canFinish] depends only on the two explicit acknowledgments.
 *
 * Immutable by design; each acknowledgment returns a new value. [reset] starts a fresh attempt,
 * which invalidates every previous copy and acknowledgment because the attempt identity changes.
 */
data class BackupSetupConsent(
    /** Identity of the setup attempt; a new attempt invalidates all previous acknowledgments. */
    val attemptID: String = UUID.randomUUID().toString(),
    /** Whether the current phrase was copied through the local, expiring pasteboard action. */
    val copiedRecoveryPhrase: Boolean = false,
    /** The user's explicit confirmation that the current phrase is stored safely. */
    val savedRecoveryPhrase: Boolean = false,
    /** The user's explicit acceptance of losing access if all recovery methods are lost. */
    val understandsNoRecovery: Boolean = false,
) {
    init {
        require(attemptID.isNotEmpty()) { "backup setup consent attempt id must not be empty" }
    }

    /** Both acknowledgments are required; copying the phrase is optional. */
    val canFinish: Boolean get() = savedRecoveryPhrase && understandsNoRecovery

    /** Copying is optional and never changes explicit acknowledgments. */
    fun recordCopy(): BackupSetupConsent = copy(copiedRecoveryPhrase = true)

    /** Records the explicit "I saved the recovery phrase" acknowledgment. */
    fun acknowledgeRecoveryPhraseSaved(): BackupSetupConsent = copy(savedRecoveryPhrase = true)

    /** Records the explicit "I understand there is no recovery" acknowledgment. */
    fun acknowledgeNoRecovery(): BackupSetupConsent = copy(understandsNoRecovery = true)

    /**
     * Invalidates consent when the phrase or setup method changes: returns a brand-new attempt with
     * no accepted acknowledgments.
     */
    fun reset(attemptIDProvider: () -> String = { UUID.randomUUID().toString() }): BackupSetupConsent =
        BackupSetupConsent(attemptID = attemptIDProvider())
}
