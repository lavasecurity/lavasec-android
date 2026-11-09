package com.lavasec.app.backup

import android.app.Activity
import android.content.MutableContextWrapper
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.lavasec.app.foundation.backup.BackupPasskeyMaterial
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * AndroidX Credential Manager adapter for the PRF backup ceremony.
 *
 * The typed Credential Manager options expose no WebAuthn `extensions` field, so this adapter uses
 * the raw JSON request/response passthrough that Credential Manager supports: a create or get
 * request carries `extensions.prf.eval.first`, and the returned `clientExtensionResults.prf.results`
 * carries the 32-byte output. Credential Manager exchanges these values with the user's native
 * provider; PRF output and raw credentials never enter the JavaScript bridge.
 *
 * The relying-party origin must be registered with `/.well-known/assetlinks.json` on the served
 * host for a live ceremony. The admission probe checks only configured origin and local provider
 * evidence; it cannot prove the hosted association or a particular credential's PRF. The actual
 * secure ceremony remains responsible for refusing those failures.
 */
internal class AndroidBackupPasskeyCeremony(
    private val activity: Activity,
    private val relyingParty: BackupPasskeyRelyingParty,
    credentials: BackupPasskeyCredentialPort? = null,
    private val currentHost: () -> Activity? = { activity },
) : BackupPasskeyCeremony {

    private val credentials = credentials ?: AndroidBackupPasskeyCredentialPort(activity, currentHost)
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private fun hostIsCurrent(): Boolean = !activity.isFinishing && !activity.isDestroyed && currentHost() === activity
    private fun retiredHost() = BackupPasskeyCeremonyResult.Failed(BackupPasskeyCeremonyResult.REASON_CEREMONY_FAILED)

    override suspend fun create(salt: ByteArray): BackupPasskeyCeremonyResult {
        require(salt.size == 32)
        if (!hostIsCurrent()) return retiredHost()
        val requestJson = JSONObject().apply {
            put("rp", JSONObject().put("id", relyingParty.rpId).put("name", relyingParty.rpName))
            put(
                "user",
                JSONObject()
                    .put("id", encoder.encodeToString(randomBytes(16)))
                    .put("name", relyingParty.userName)
                    .put("displayName", relyingParty.userDisplayName),
            )
            put("challenge", encoder.encodeToString(randomBytes(32)))
            put(
                "pubKeyCredParams",
                JSONArray()
                    .put(JSONObject().put("type", "public-key").put("alg", -7))
                    .put(JSONObject().put("type", "public-key").put("alg", -257)),
            )
            put("timeout", 60_000)
            put("attestation", "none")
            put("authenticatorSelection", JSONObject().put("residentKey", "required").put("userVerification", "required"))
            put("extensions", prfExtension(salt))
        }.toString()
        val response = try {
            credentials.create(requestJson)
        } catch (cancelled: CreateCredentialCancellationException) {
            return BackupPasskeyCeremonyResult.Cancelled
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return BackupPasskeyCeremonyResult.Failed(BackupPasskeyCeremonyResult.REASON_CEREMONY_FAILED)
        }
        if (!hostIsCurrent()) return retiredHost()
        val registration = response?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return BackupPasskeyCeremonyResult.Failed(BackupPasskeyCeremonyResult.REASON_CEREMONY_FAILED)
        val credentialId = credentialIdOf(registration)
            ?: return BackupPasskeyCeremonyResult.Failed(BackupPasskeyCeremonyResult.REASON_CEREMONY_FAILED)
        val prf = registration.optJSONObject("clientExtensionResults")?.optJSONObject("prf")
        // WebAuthn permits support to be reported at registration without evaluating PRF then.
        // The frozen iOS flow likewise asserts after registration to obtain its actual output.
        if (prf?.optBoolean("enabled") == true && !prf.has("results")) {
            return assert(credentialId, salt)
        }
        return readPrf(registration, salt, credentialId)
    }

    override suspend fun assert(credentialId: String, salt: ByteArray): BackupPasskeyCeremonyResult {
        require(salt.size == 32)
        if (!hostIsCurrent()) return retiredHost()
        if (!isCredentialId(credentialId)) {
            return BackupPasskeyCeremonyResult.Failed(BackupPasskeyCeremonyResult.REASON_CEREMONY_FAILED)
        }
        val requestJson = JSONObject().apply {
            put("challenge", encoder.encodeToString(randomBytes(32)))
            put("rpId", relyingParty.rpId)
            put("timeout", 60_000)
            put("userVerification", "required")
            put(
                "allowCredentials",
                JSONArray().put(JSONObject().put("type", "public-key").put("id", credentialId)),
            )
            put("extensions", prfExtension(salt))
        }.toString()
        val response = try {
            credentials.assert(requestJson)
        } catch (cancelled: GetCredentialCancellationException) {
            return BackupPasskeyCeremonyResult.Cancelled
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return BackupPasskeyCeremonyResult.Failed(BackupPasskeyCeremonyResult.REASON_CEREMONY_FAILED)
        }
        if (!hostIsCurrent()) return retiredHost()
        val assertion = response?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return BackupPasskeyCeremonyResult.Failed(BackupPasskeyCeremonyResult.REASON_CEREMONY_FAILED)
        if (credentialIdOf(assertion) != credentialId) {
            return BackupPasskeyCeremonyResult.Failed(BackupPasskeyCeremonyResult.REASON_CEREMONY_FAILED)
        }
        return readPrf(assertion, salt, credentialId)
    }

    private fun prfExtension(salt: ByteArray): JSONObject =
        JSONObject().put("prf", JSONObject().put("eval", JSONObject().put("first", encoder.encodeToString(salt))))

    private fun credentialIdOf(json: JSONObject): String? {
        if (json.opt("type") != "public-key") return null
        if (listOf("id", "rawId").any { field -> json.has(field) &&
                (json.opt(field) !is String || (json.opt(field) as String).isBlank()) }) return null
        val id = (json.opt("id") as? String)?.takeIf(String::isNotBlank)
        val rawId = (json.opt("rawId") as? String)?.takeIf(String::isNotBlank)
        if (id != null && rawId != null && id != rawId) return null
        return (id ?: rawId)?.takeIf(::isCredentialId)
    }

    /** Public credential handles use WebAuthn's base64url encoding; malformed handles cannot seal a slot. */
    private fun isCredentialId(value: String): Boolean {
        if (value.isEmpty() || value.length > 2048) return false
        val decoded = try { Base64.getUrlDecoder().decode(value) } catch (_: IllegalArgumentException) { return false }
        return decoded.isNotEmpty()
    }

    /**
     * Reads `clientExtensionResults.prf.results.first`, the base64url 32-byte PRF output. A missing,
     * malformed or wrong-length value is a typed failure, never a synthesized output.
     */
    private fun readPrf(json: JSONObject, salt: ByteArray, credentialId: String): BackupPasskeyCeremonyResult {
        if (credentialId.isBlank()) {
            return BackupPasskeyCeremonyResult.Failed(BackupPasskeyCeremonyResult.REASON_CEREMONY_FAILED)
        }
        val first = json.optJSONObject("clientExtensionResults")
            ?.optJSONObject("prf")
            ?.optJSONObject("results")
            ?.optString("first")
            .orEmpty()
        val output = first.takeIf { it.isNotBlank() }
            ?.let { runCatching { Base64.getUrlDecoder().decode(it) }.getOrNull() }
            ?: return BackupPasskeyCeremonyResult.Failed(BackupPasskeyCeremonyResult.REASON_PRF_MISSING)
        return try {
            if (output.size != 32) {
                BackupPasskeyCeremonyResult.Failed(BackupPasskeyCeremonyResult.REASON_PRF_MISSING)
            } else {
                BackupPasskeyCeremonyResult.Obtained(BackupPasskeyMaterial(output, salt, credentialId))
            }
        } finally {
            output.fill(0)
        }
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
}

/** The native Credential Manager boundary; request/response JSON never crosses the JS bridge. */
internal interface BackupPasskeyCredentialPort {
    suspend fun create(requestJson: String): String?
    suspend fun assert(requestJson: String): String?
}

private class AndroidBackupPasskeyCredentialPort(
    private val activity: Activity,
    private val currentHost: () -> Activity?,
) : BackupPasskeyCredentialPort {
    // Manager/probes are app-scoped; each UI request uses only its initiating Activity's task.
    private val manager: CredentialManager by lazy { CredentialManager.create(activity.applicationContext) }
    private fun requireHost() {
        check(!activity.isFinishing && !activity.isDestroyed && currentHost() === activity) { "Backup ceremony host retired" }
    }

    override suspend fun create(requestJson: String): String? {
        requireHost()
        val response = manager.createCredential(MutableContextWrapper(activity), CreatePublicKeyCredentialRequest(requestJson))
        requireHost()
        return (response as? CreatePublicKeyCredentialResponse)?.registrationResponseJson
    }

    override suspend fun assert(requestJson: String): String? {
        requireHost()
        val response = manager.getCredential(MutableContextWrapper(activity),
            GetCredentialRequest(listOf(GetPublicKeyCredentialOption(requestJson))))
        requireHost()
        return (response.credential as? PublicKeyCredential)?.authenticationResponseJson
    }
}
