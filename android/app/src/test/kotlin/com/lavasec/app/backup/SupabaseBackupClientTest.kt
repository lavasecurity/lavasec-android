package com.lavasec.app.backup

import com.lavasec.app.account.AccountSessionChanged
import com.lavasec.app.backend.ServiceTestFixture
import com.lavasec.app.backend.ServiceTestFixture.Companion.USER
import com.lavasec.app.foundation.backup.BackupEnvelopeCrypto
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SupabaseBackupClientTest {
    @Test fun `ciphertext upload fetch and deletion use own account and exact iOS columns`() = runTest {
        ServiceTestFixture().use { f ->
            val backup = SupabaseBackupClient(f.configuration, f.accounts, f.http)
            val envelope = BackupEnvelopeCrypto.seal("settings fixture".toByteArray(), mapOf("password" to "secret".toCharArray()), 1000, iterations = 8)
            f.server.enqueue(MockResponse().setResponseCode(201))
            backup.upload(0, envelope)
            val upload = f.server.takeRequest()
            assertEquals("POST", upload.method)
            assertEquals("user_id", upload.requestUrl!!.queryParameter("on_conflict"))
            assertEquals("Bearer access", upload.getHeader("Authorization"))
            assertEquals("sb_publishable_fixture", upload.getHeader("apikey"))
            val row = JSONObject(upload.body.readUtf8())
            assertEquals(USER, row.getString("user_id"))
            assertFalse(row.toString().contains("settings fixture"))
            f.server.enqueue(MockResponse().setBody("[$row]"))
            assertEquals(envelope, backup.fetchLatest(0))
            val fetch = f.server.takeRequest()
            assertEquals("eq.$USER", fetch.requestUrl!!.queryParameter("user_id"))
            assertEquals("is.null", fetch.requestUrl!!.queryParameter("disabled_at"))
            f.server.enqueue(MockResponse().setResponseCode(204))
            backup.deleteRemote(0)
            assertEquals("DELETE", f.server.takeRequest().method)
        }
    }
    @Test fun `metadata fallback avoids ciphertext and never substitutes restore timestamp`() = runTest {
        ServiceTestFixture().use { f ->
            val backup = SupabaseBackupClient(f.configuration, f.accounts, f.http)
            f.server.enqueue(MockResponse().setResponseCode(400))
            f.server.enqueue(MockResponse().setBody("[{\"user_id\":\"$USER\"}]"))
            val metadata = backup.fetchMetadata(0)!!
            assertNull(metadata.uploadedAtEpochSeconds)
            assertEquals("user_id,uploaded_at", f.server.takeRequest().requestUrl!!.queryParameter("select"))
            assertEquals("user_id", f.server.takeRequest().requestUrl!!.queryParameter("select"))
        }
    }
    @Test fun `stale account command cannot send and in flight response cannot publish after logout`() = runTest {
        ServiceTestFixture().use { f ->
            val admitted = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    admitted.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    return MockResponse().setBody("[{\"user_id\":\"$USER\"}]")
                }
            }
            val backup = SupabaseBackupClient(f.configuration, f.accounts, f.http)
            val result = async(Dispatchers.IO) { runCatching { backup.fetchMetadata(0) }.exceptionOrNull() }
            try {
                assertTrue(withContext(Dispatchers.IO) { admitted.await(10, TimeUnit.SECONDS) })
                f.accounts.signOut()
            } finally { release.countDown() }
            assertTrue(result.await() is AccountSessionChanged)
            assertTrue(runCatching { backup.deleteRemote(0) }.exceptionOrNull() is AccountSessionChanged)
            assertEquals(1, f.server.requestCount)
        }
    }
}
