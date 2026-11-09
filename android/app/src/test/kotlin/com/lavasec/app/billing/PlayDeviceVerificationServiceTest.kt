package com.lavasec.app.billing

import com.lavasec.app.backend.AndroidAppService
import com.lavasec.app.backend.PlayIntegrityTokenPort
import com.lavasec.app.backend.ServiceTestFixture
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class PlayDeviceVerificationServiceTest {
    private val product = "lava_security_plus_monthly"
    private fun receipt() = PlayPurchase("secret-purchase", setOf(product), true, false)
    private fun verdict() = JSONObject("""{"verified":true,"active":true,"plan":"lava_security_plus","source":"google-play","account_id":null,"product_id":"lava_security_plus_monthly","checked_at":"1970-01-01T00:16:40Z","valid_until":"1970-01-01T00:20:00Z","expires_at":"1970-01-01T01:00:00Z","acknowledgement_required":true}""")

    @Test fun `fresh verified device purchase requires no Lava session`() = runTest {
        ServiceTestFixture().use { f ->
            val service = PlayDeviceVerificationService(AndroidAppService(f.server.url("/"), PlayIntegrityTokenPort { "integrity" }, f.http)) { Instant.ofEpochSecond(1000) }
            f.server.enqueue(MockResponse().setBody(verdict().toString()))
            val result = service.verify(receipt())
            assertTrue(result.active)
            assertTrue(result.acknowledgementRequired)
            val request = f.server.takeRequest()
            assertNull(request.getHeader("Authorization"))
            assertEquals("secret-purchase", JSONObject(request.body.readUtf8()).getString("purchase_token"))
        }
    }

    @Test fun `unverified mismatched stale and extended verdicts cannot grant`() = runTest {
        ServiceTestFixture().use { f ->
            val service = PlayDeviceVerificationService(AndroidAppService(f.server.url("/"), PlayIntegrityTokenPort { "integrity" }, f.http)) { Instant.ofEpochSecond(1000) }
            for (invalid in listOf(
                verdict().put("verified", false), verdict().put("account_id", "unexpected-account"),
                verdict().put("product_id", "lava_security_plus_yearly"), verdict().put("source", "unverified"),
                verdict().put("valid_until", "1970-01-01T00:30:00Z"),
                verdict().put("valid_until", "1970-01-01T00:16:00Z"),
                verdict().put("expires_at", JSONObject.NULL),
                verdict().put("active", false),
            )) {
                f.server.enqueue(MockResponse().setBody(invalid.toString()))
                assertTrue(runCatching { service.verify(receipt()) }.isFailure)
            }
        }
    }

    @Test fun `only documented expired retention is a confirmed inactive receipt`() = runTest {
        ServiceTestFixture().use { f ->
            val service = PlayDeviceVerificationService(AndroidAppService(f.server.url("/"), PlayIntegrityTokenPort { "integrity" }, f.http))
            f.server.enqueue(MockResponse().setResponseCode(410).setBody("private upstream data"))
            val expired = service.verify(receipt())
            assertEquals(product, expired.productId)
            assertFalse(expired.active)
            assertFalse(expired.acknowledgementRequired)
            assertNull(expired.expiresAt)
            for (status in listOf(400, 403, 404, 422, 429, 500, 503)) {
                f.server.enqueue(MockResponse().setResponseCode(status).setBody("private upstream data"))
                assertTrue(runCatching { service.verify(receipt()) }.isFailure)
            }
        }
    }

}
