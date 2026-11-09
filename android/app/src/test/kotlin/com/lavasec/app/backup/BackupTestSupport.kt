package com.lavasec.app.backup

import com.lavasec.app.foundation.backup.BackupDeletionIntent
import com.lavasec.app.foundation.backup.BackupDeletionIntentStore
import com.lavasec.app.foundation.backup.BackupDeletionRead
import com.lavasec.app.foundation.backup.BackupEnvelope

/** Shared pure-JVM fakes for the backup setup/unlock/automatic tests (no Android, no Robolectric). */
internal const val TEST_ACCOUNT = "account-A"

internal class FakeBackupAccounts(
    var accountID: String? = TEST_ACCOUNT,
    var signedIn: Boolean = true,
    var generation: Long = 7,
) : BackupAccountAuthority {
    override fun binding(): BackupAccountBinding = BackupAccountBinding(accountID, signedIn)

    override fun generation(): Long = generation

    fun switchTo(id: String?) {
        accountID = id
        signedIn = id != null
        generation += 1
    }
}

internal class FakeBackupCloud : BackupCloudClient {
    var uploadCalls = 0
    var latestCalls = 0
    var metadataCalls = 0
    var deleteCalls = 0
    var latest: BackupEnvelope? = null
    var failUpload = false
    var failLatest = false
    var onFetchLatest: (() -> Unit)? = null

    override suspend fun upload(expectedGeneration: Long, envelope: BackupEnvelope) {
        uploadCalls += 1
        if (failUpload) throw RuntimeException("upload failed")
    }

    override suspend fun fetchLatest(expectedGeneration: Long): BackupEnvelope? {
        latestCalls += 1
        onFetchLatest?.invoke()
        if (failLatest) throw RuntimeException("download failed")
        return latest
    }

    override suspend fun fetchMetadata(expectedGeneration: Long): BackupRemoteMetadata? {
        metadataCalls += 1
        return null
    }

    override suspend fun deleteRemote(expectedGeneration: Long) {
        deleteCalls += 1
    }
}

internal class FakeBackupLocalStore : BackupLocalStore {
    var envelope: BackupEnvelope? = null
    var envelopeAccountID: String? = null
    var uploadedAt: Long? = null
    var envelopeSaves = 0
    var failSave = false
    var failSaveAccount = false
    var onSave: (() -> Unit)? = null

    override fun loadEnvelope(): BackupEnvelope? = envelope

    override fun saveEnvelope(envelope: BackupEnvelope): Boolean {
        envelopeSaves += 1
        onSave?.invoke()
        if (failSave) return false
        this.envelope = envelope
        return true
    }

    override fun loadEnvelopeAccountID(): String? = envelopeAccountID

    override fun saveEnvelopeAccountID(accountID: String?): Boolean {
        if (failSaveAccount) return false
        envelopeAccountID = accountID
        return true
    }

    override fun deleteEnvelope(): Boolean {
        envelope = null
        envelopeAccountID = null
        return true
    }

    override fun loadUploadedAtEpochSeconds(): Long? = uploadedAt

    override fun recordUploadedAtEpochSeconds(value: Long): Boolean {
        uploadedAt = value
        return true
    }

    override fun clearUploadMarker(): Boolean {
        uploadedAt = null
        return true
    }
}

internal class FakeBackupDeletionStore : BackupDeletionIntentStore {
    var intent: BackupDeletionIntent? = null

    override fun read(): BackupDeletionRead = intent?.let(BackupDeletionRead::Present)
        ?: BackupDeletionRead.Absent

    override fun write(intent: BackupDeletionIntent): Boolean {
        this.intent = intent
        return true
    }

    override fun clear(): Boolean {
        intent = null
        return true
    }
}

internal class FakeAutomaticPreference(initial: Boolean = false) : BackupAutomaticPreferenceStore {
    private var enabled = initial

    override fun loadAutomaticEnabled(): Boolean = enabled

    override fun saveAutomaticEnabled(enabled: Boolean): Boolean {
        this.enabled = enabled
        return true
    }
}
