package com.lavasec.app.backup

import com.lavasec.app.foundation.backup.BackupPasskeyMaterial
import java.net.URI
import java.security.SecureRandom

/**
 * One WebAuthn PRF ceremony outcome. It never fabricates success: a dismissed prompt is
 * [Cancelled], and any provider/authenticator/PRF failure is a typed [Failed] code.
 */
internal sealed interface BackupPasskeyCeremonyResult {
    /** The authenticator returned a 32-byte PRF output bound to [material]'s credential. */
    data class Obtained(val material: BackupPasskeyMaterial) : BackupPasskeyCeremonyResult

    /** The user dismissed the provider sheet; nothing was produced. */
    data object Cancelled : BackupPasskeyCeremonyResult

    /** The provider, authenticator or PRF extension failed; [reason] is a stable wire code. */
    data class Failed(val reason: String) : BackupPasskeyCeremonyResult

    companion object {
        const val REASON_CEREMONY_FAILED = "backup-passkey-ceremony-failed"
        const val REASON_PRF_MISSING = "backup-passkey-prf-unavailable"
    }
}

/**
 * The passkey ceremony seam. [create] registers a credential and evaluates PRF with [salt];
 * [assert] evaluates PRF for an existing credential. Implementations own all authenticator material
 * and must not leak it; [BackupPasskeyMaterial] zeroizes its copies on close. A caller passes only a
 * 32-byte salt and receives only the typed result.
 */
internal interface BackupPasskeyCeremony {
    suspend fun create(salt: ByteArray): BackupPasskeyCeremonyResult

    suspend fun assert(credentialId: String, salt: ByteArray): BackupPasskeyCeremonyResult
}

/** Fails closed when no relying party is configured, so a caller can hold a non-null seam. */
internal object UnavailableBackupPasskeyCeremony : BackupPasskeyCeremony {
    override suspend fun create(salt: ByteArray): BackupPasskeyCeremonyResult =
        BackupPasskeyCeremonyResult.Failed(BackupPasskeyCeremonyResult.REASON_PRF_MISSING)

    override suspend fun assert(credentialId: String, salt: ByteArray): BackupPasskeyCeremonyResult =
        BackupPasskeyCeremonyResult.Failed(BackupPasskeyCeremonyResult.REASON_PRF_MISSING)
}

/** The WebAuthn relying-party identity a ceremony presents; derived from the configured origin. */
internal data class BackupPasskeyRelyingParty(
    val rpId: String,
    val rpName: String,
    val userName: String,
    val userDisplayName: String,
) {
    internal companion object {
        /**
         * Maps a configured RP origin (`https://host[:port]`) to a relying-party id (the host). A
         * blank or unparseable origin yields `null`, so the caller falls back to the unavailable
         * seam rather than running a ceremony against an unknown domain.
         */
        fun fromOrigin(origin: String, userName: String, userDisplayName: String): BackupPasskeyRelyingParty? {
            val uri = runCatching { URI(origin) }.getOrNull() ?: return null
            val host = uri.host
            if (uri.scheme != "https" || host.isNullOrBlank() || uri.userInfo != null ||
                uri.query != null || uri.fragment != null || uri.path !in listOf("", "/") ||
                uri.port !in -1..65535 || uri.port == 0
            ) return null
            return BackupPasskeyRelyingParty(
                rpId = host,
                rpName = "Lava",
                userName = userName,
                userDisplayName = userDisplayName,
            )
        }
    }
}


/** A fresh non-secret 32-byte PRF input, persisted in the envelope to reproduce the same output. */
internal fun newBackupPasskeySalt(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
