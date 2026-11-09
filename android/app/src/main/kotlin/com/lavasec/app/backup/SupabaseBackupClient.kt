package com.lavasec.app.backup

import com.lavasec.app.account.AccountServiceConfiguration
import com.lavasec.app.account.AccountSession
import com.lavasec.app.account.AccountSessionOwner
import com.lavasec.app.backend.BoundedServiceHttp
import com.lavasec.app.backend.ServiceHttpFailure
import com.lavasec.app.foundation.backup.BackupEnvelope
import java.time.Instant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal data class BackupRemoteMetadata(val userId: String, val uploadedAtEpochSeconds: Long?)

/** Account-bound cloud ciphertext operations. No password, recovery phrase or device key is sent. */
internal class SupabaseBackupClient(
    private val configuration: AccountServiceConfiguration,
    private val accounts: AccountSessionOwner,
    private val http: BoundedServiceHttp = BoundedServiceHttp(),
) {
    suspend fun upload(expectedGeneration: Long, envelope: BackupEnvelope) = accounts.withSession(expectedGeneration) { session ->
        val body = BackupEnvelopeJson.upload(envelope, session.userId).toString()
        require(body.toByteArray(Charsets.UTF_8).size <= MAX_ENVELOPE_RESPONSE_BYTES)
        val request = request(session, mapOf("on_conflict" to "user_id"))
            .header("Prefer", "resolution=merge-duplicates,return=minimal")
            .post(body.toRequestBody(JSON)).build()
        http.execute(request, MAX_METADATA_RESPONSE_BYTES)
        Unit
    }

    suspend fun fetchLatest(expectedGeneration: Long): BackupEnvelope? = accounts.withSession(expectedGeneration) { session ->
        val request = request(session, mapOf("select" to "user_id,backup_version,envelope_version,ciphertext,metadata,ciphertext_byte_size,disabled_at",
            "user_id" to "eq.${session.userId}", "disabled_at" to "is.null", "limit" to "1")).get().build()
        val rows = JSONArray(http.execute(request, MAX_ENVELOPE_RESPONSE_BYTES).toString(Charsets.UTF_8))
        require(rows.length() <= 1)
        if (rows.length() == 0) null else BackupEnvelopeJson.decode(rows.getJSONObject(0), session.userId)
    }

    /** Presence checks never download ciphertext and do not mistake restore time for upload time. */
    suspend fun fetchMetadata(expectedGeneration: Long): BackupRemoteMetadata? = accounts.withSession(expectedGeneration) { session ->
        suspend fun read(includeUploadDate: Boolean): BackupRemoteMetadata? {
            val request = request(session, mapOf("select" to if (includeUploadDate) "user_id,uploaded_at" else "user_id",
                "user_id" to "eq.${session.userId}", "disabled_at" to "is.null", "limit" to "1")).get().build()
            val rows = JSONArray(http.execute(request, MAX_METADATA_RESPONSE_BYTES).toString(Charsets.UTF_8))
            require(rows.length() <= 1)
            if (rows.length() == 0) return null
            val row = rows.getJSONObject(0)
            require(row.getString("user_id") == session.userId)
            val uploaded = if (row.isNull("uploaded_at")) null else Instant.parse(row.getString("uploaded_at")).epochSecond
            return BackupRemoteMetadata(session.userId, uploaded)
        }
        try { read(true) } catch (failure: ServiceHttpFailure) {
            if (failure.status != 400) throw failure
            read(false) // Same legacy backend compatibility as iOS; never fall back to updated_at.
        }
    }

    suspend fun markRestored(expectedGeneration: Long, atEpochSeconds: Long) = accounts.withSession(expectedGeneration) { session ->
        val body = JSONObject().put("last_restored_at", Instant.ofEpochSecond(atEpochSeconds).toString()).toString()
        http.execute(request(session, mapOf("user_id" to "eq.${session.userId}"))
            .header("Prefer", "return=minimal").patch(body.toRequestBody(JSON)).build(), MAX_METADATA_RESPONSE_BYTES)
        Unit
    }

    suspend fun deleteRemote(expectedGeneration: Long) = accounts.withSession(expectedGeneration) { session ->
        http.execute(request(session, mapOf("user_id" to "eq.${session.userId}"))
            .header("Prefer", "return=minimal").delete().build(), MAX_METADATA_RESPONSE_BYTES)
        Unit
    }

    private fun request(session: AccountSession, query: Map<String, String>): Request.Builder {
        val url = configuration.projectUrl.newBuilder().encodedPath("/rest/v1/user_backups")
        query.forEach { (name, value) -> url.addQueryParameter(name, value) }
        return Request.Builder().url(url.build()).header("apikey", configuration.publishableKey)
            .header("Authorization", "Bearer ${session.accessToken}").header("Accept", "application/json")
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val MAX_METADATA_RESPONSE_BYTES = 32 * 1024
        const val MAX_ENVELOPE_RESPONSE_BYTES = 512 * 1024
    }
}
