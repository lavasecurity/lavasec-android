package com.lavasec.app.backup

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.lavasec.app.foundation.backup.BackupEnvelopeCrypto
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Exercises the production JSON adapter with controlled native credential-provider responses. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AndroidBackupPasskeyAdapterTest {
    private val salt = ByteArray(32) { (it + 3).toByte() }
    private val output = ByteArray(32) { (it + 7).toByte() }
    private val id = "Y3JlZGVudGlhbA"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val rp = BackupPasskeyRelyingParty.fromOrigin("https://lavasecurity.app", "account-A", "Lava backup")!!

    private class Provider : BackupPasskeyCredentialPort {
        val creates = mutableListOf<JSONObject>()
        val assertions = mutableListOf<JSONObject>()
        var registration: suspend () -> String? = { null }
        var assertion: suspend () -> String? = { null }
        override suspend fun create(requestJson: String): String? {
            creates += JSONObject(requestJson)
            return registration()
        }
        override suspend fun assert(requestJson: String): String? {
            assertions += JSONObject(requestJson)
            return assertion()
        }
    }

    private var controller: ActivityController<Activity>? = null
    private val activity: Activity get() = (controller ?: Robolectric.buildActivity(Activity::class.java).setup()
        .also { controller = it }).get()
    @After fun closeActivity() { controller?.pause()?.stop()?.destroy() }
    private fun adapter(provider: Provider) = AndroidBackupPasskeyCeremony(activity, rp, provider)
    private fun credential(credentialId: String = id, result: ByteArray? = output): JSONObject = JSONObject()
        .put("id", credentialId).put("rawId", credentialId).put("type", "public-key")
        .put("response", JSONObject().put("clientDataJSON", "fixture-native-client-data"))
        .put("clientExtensionResults", JSONObject().put("prf", JSONObject().apply {
            if (result != null) put("results", JSONObject().put("first", encoder.encodeToString(result)))
        }))

    @Test
    fun `recreated finishing and destroyed hosts refuse before any native provider invocation`() = runTest {
        for (mode in listOf("recreated", "finishing", "destroyed")) {
            val host = Robolectric.buildActivity(Activity::class.java).setup()
            val replacement = Robolectric.buildActivity(Activity::class.java).setup()
            try {
                var current: Activity? = host.get()
                val provider = Provider().apply {
                    registration = { error("retired host must not present") }
                    assertion = { error("retired host must not present") }
                }
                val ceremony = AndroidBackupPasskeyCeremony(host.get(), rp, provider, currentHost = { current })
                when (mode) {
                    "recreated" -> current = replacement.get()
                    "finishing" -> host.get().finish()
                    "destroyed" -> host.pause().stop().destroy()
                }
                val expected = BackupPasskeyCeremonyResult.Failed(BackupPasskeyCeremonyResult.REASON_CEREMONY_FAILED)
                assertEquals(mode, expected, ceremony.create(salt))
                assertEquals(mode, expected, ceremony.assert(id, salt))
                assertTrue(provider.creates.isEmpty())
                assertTrue(provider.assertions.isEmpty())
            } finally {
                if (!host.get().isDestroyed) host.pause().stop().destroy()
                replacement.pause().stop().destroy()
            }
        }
    }

    @Test
    fun `late provider output is refused after recreation and is never rebound to a new host`() = runTest {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        val replacement = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            var current: Activity? = host.get()
            val provider = Provider().apply {
                registration = { current = replacement.get(); credential().toString() }
                assertion = { current = replacement.get(); credential().toString() }
            }
            val ceremony = AndroidBackupPasskeyCeremony(host.get(), rp, provider, currentHost = { current })
            val expected = BackupPasskeyCeremonyResult.Failed(BackupPasskeyCeremonyResult.REASON_CEREMONY_FAILED)
            assertEquals(expected, ceremony.create(salt))
            current = host.get()
            assertEquals(expected, ceremony.assert(id, salt))
            assertEquals(1, provider.creates.size)
            assertEquals(1, provider.assertions.size)
            // Even a new attempt on the old ceremony cannot attach itself to the new host.
            assertEquals(expected, ceremony.assert(id, salt))
            assertEquals(1, provider.assertions.size)
        } finally { host.pause().stop().destroy(); replacement.pause().stop().destroy() }
    }

    @Test
    fun `registration request and returned native PRF seal a real passkey slot`() = runTest {
        val provider = Provider().apply { registration = { credential().toString() } }
        val material = (adapter(provider).create(salt) as BackupPasskeyCeremonyResult.Obtained).material
        try {
            val request = provider.creates.single()
            assertEquals(rp.rpId, request.getJSONObject("rp").getString("id"))
            assertEquals("required", request.getJSONObject("authenticatorSelection").getString("userVerification"))
            assertEquals(encoder.encodeToString(salt), request.getJSONObject("extensions")
                .getJSONObject("prf").getJSONObject("eval").getString("first"))
            assertEquals(32, Base64.getUrlDecoder().decode(request.getString("challenge")).size)
            assertEquals(16, Base64.getUrlDecoder().decode(request.getJSONObject("user").getString("id")).size)
            val plaintext = "portable settings".toByteArray()
            val envelope = BackupEnvelopeCrypto.seal(plaintext, mapOf("recoveryPhrase" to "phrase".toCharArray()), 1_000,
                passkey = material)
            assertArrayEquals(plaintext, BackupEnvelopeCrypto.unlockWithPasskeyPrf(envelope, output))
            assertEquals(id, envelope.keySlots.single { it.kind == BackupEnvelopeCrypto.SLOT_PASSKEY }.credentialID)
            assertTrue(provider.assertions.isEmpty())
        } finally { material.close() }
    }

    @Test
    fun `registration support without output evaluates the exact newly registered credential`() = runTest {
        val provider = Provider().apply {
            registration = { credential(result = null).apply {
                getJSONObject("clientExtensionResults").getJSONObject("prf").put("enabled", true)
            }.toString() }
            assertion = { credential().toString() }
        }
        val material = (adapter(provider).create(salt) as BackupPasskeyCeremonyResult.Obtained).material
        try {
            val request = provider.assertions.single()
            assertEquals(rp.rpId, request.getString("rpId"))
            assertEquals(id, request.getJSONArray("allowCredentials").getJSONObject(0).getString("id"))
            assertEquals("required", request.getString("userVerification"))
            assertEquals(encoder.encodeToString(salt), request.getJSONObject("extensions")
                .getJSONObject("prf").getJSONObject("eval").getString("first"))
            material.usePrf { actual, actualSalt -> assertArrayEquals(output, actual); assertArrayEquals(salt, actualSalt) }
        } finally { material.close() }
    }

    @Test
    fun `malformed registration and wrong returned credential are typed refusals`() = runTest {
        for (response in listOf("not-json", credential().put("rawId", "different").toString())) {
            val provider = Provider().apply { registration = { response } }
            assertEquals(BackupPasskeyCeremonyResult.Failed(BackupPasskeyCeremonyResult.REASON_CEREMONY_FAILED),
                adapter(provider).create(salt))
            assertTrue(provider.assertions.isEmpty())
        }
        for (response in listOf("not-json", credential("different").toString(),
            credential().put("id", JSONObject.NULL).put("rawId", JSONObject.NULL).toString())) {
            val provider = Provider().apply { assertion = { response } }
            assertEquals(BackupPasskeyCeremonyResult.Failed(BackupPasskeyCeremonyResult.REASON_CEREMONY_FAILED),
                adapter(provider).assert(id, salt))
        }
    }

    @Test
    fun `invalid credential handles and non-public-key native responses cannot produce material`() = runTest {
        for (badId in listOf("", "bad id", "not+url/base64", "a", "====", "😀", "A".repeat(2_049))) {
            val provider = Provider().apply {
                registration = { credential(badId).toString() }
                assertion = { error("an invalid stored credential must not reach the provider") }
            }
            val expected = BackupPasskeyCeremonyResult.Failed(BackupPasskeyCeremonyResult.REASON_CEREMONY_FAILED)
            assertEquals(badId, expected, adapter(provider).create(salt))
            assertEquals(badId, expected, adapter(provider).assert(badId, salt))
            assertTrue(provider.assertions.isEmpty())
        }
        for (response in listOf(credential().put("type", "password"), credential().put("type", JSONObject.NULL),
            credential().put("id", 42), credential().put("rawId", 42), credential().put("id", ""),
            credential().put("rawId", JSONObject.NULL))) {
            val provider = Provider().apply { registration = { response.toString() }; assertion = { response.toString() } }
            val expected = BackupPasskeyCeremonyResult.Failed(BackupPasskeyCeremonyResult.REASON_CEREMONY_FAILED)
            assertEquals(expected, adapter(provider).create(salt))
            assertEquals(expected, adapter(provider).assert(id, salt))
        }
    }

    @Test
    fun `absent or malformed PRF never produces material or retries a malformed output`() = runTest {
        for (response in listOf(credential(result = null), credential(result = ByteArray(31)),
            credential().apply { getJSONObject("clientExtensionResults").getJSONObject("prf")
                .getJSONObject("results").put("first", "not base64") })) {
            val provider = Provider().apply { registration = { response.toString() } }
            assertEquals(BackupPasskeyCeremonyResult.Failed(BackupPasskeyCeremonyResult.REASON_PRF_MISSING),
                adapter(provider).create(salt))
            assertTrue(provider.assertions.isEmpty())
        }
    }

    @Test
    fun `native dismissals are cancellation while coroutine cancellation is propagated`() = runTest {
        val create = Provider().apply { registration = { throw CreateCredentialCancellationException() } }
        val get = Provider().apply { assertion = { throw GetCredentialCancellationException() } }
        assertEquals(BackupPasskeyCeremonyResult.Cancelled, adapter(create).create(salt))
        assertEquals(BackupPasskeyCeremonyResult.Cancelled, adapter(get).assert(id, salt))
        val retired = Provider().apply { registration = { throw CancellationException("retired") } }
        assertTrue(runCatching { adapter(retired).create(salt) }.exceptionOrNull() is CancellationException)
    }

    @Test
    fun `RP configuration must name a secure origin`() {
        for (origin in listOf("", "http://lavasecurity.app", "https://user@lavasecurity.app", "https://lavasecurity.app/path",
            "https://lavasecurity.app?query", "https://lavasecurity.app#fragment", "https://lavasecurity.app:0")) {
            assertNull(origin, BackupPasskeyRelyingParty.fromOrigin(origin, "account", "Lava backup"))
        }
        assertEquals("lavasecurity.app", BackupPasskeyRelyingParty.fromOrigin("https://lavasecurity.app:443/", "account", "Lava backup")?.rpId)
    }

    @Test
    @Config(sdk = [34])
    fun `framework probe resolves a protected enabled credential provider rather than constructing a manager`() {
        val context = RuntimeEnvironment.getApplication()
        val intent = Intent(AndroidBackupPasskeyProviderFacts.PROVIDER_ACTION)
        val packageManager = Shadows.shadowOf(context.packageManager)
        val facts = AndroidBackupPasskeyProviderFacts(context)
        assertFalse(facts.frameworkProviderServicePresent())
        val service = ServiceInfo().apply {
            packageName = "fixture.credentials"
            name = "fixture.credentials.Provider"
            exported = true
            enabled = true
            permission = AndroidBackupPasskeyProviderFacts.PROVIDER_PERMISSION
            applicationInfo = ApplicationInfo().apply { enabled = true }
        }
        packageManager.addResolveInfoForIntent(intent, ResolveInfo().apply { serviceInfo = service })
        assertTrue(facts.frameworkProviderServicePresent())
        service.permission = null
        assertFalse(facts.frameworkProviderServicePresent())
        service.permission = AndroidBackupPasskeyProviderFacts.PROVIDER_PERMISSION
        service.exported = false
        assertFalse(facts.frameworkProviderServicePresent())
        service.exported = true
        service.applicationInfo.enabled = false
        assertFalse(facts.frameworkProviderServicePresent())
    }
}
