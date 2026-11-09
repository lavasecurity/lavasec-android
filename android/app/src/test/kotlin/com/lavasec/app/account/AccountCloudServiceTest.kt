package com.lavasec.app.account

import com.lavasec.app.backend.ServiceTestFixture
import com.lavasec.app.backend.ServiceTestFixture.Companion.USER
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AccountCloudServiceTest {
    @Test fun `Play account binding matches the Worker hash contract`() {
        // Independently generated with the Worker's Node SHA-256 input contract.
        assertEquals("d86a16899a751c46a7f68fe5a8814440eef338fcf02219e0f4ee1edd75b7137d", GooglePlayAccountBinding.forUser(USER))
    }

    @Test fun `verified purchase is account bound short lived and authenticated`() = runTest {
        ServiceTestFixture().use { f ->
            val cloud = AccountCloudService(f.server.url("/"), f.accounts, f.http) { 1000 }
            f.server.enqueue(MockResponse().setBody(verdict().toString()))
            val result = cloud.verifyGooglePlayPurchase("purchase-proof")
            assertTrue(result.active)
            assertEquals(USER, result.accountId)
            assertEquals(1200, result.validUntilEpochSeconds)
            assertTrue(result.acknowledgementRequired)
            val request = f.server.takeRequest()
            assertEquals("/v1/account/entitlements/google-play-verify", request.path)
            assertEquals("Bearer access", request.getHeader("Authorization"))
            assertEquals("purchase-proof", JSONObject(request.body.readUtf8()).getString("purchase_token"))
        }
    }
    @Test fun `unverified foreign expired and extended verdicts never grant access`() = runTest {
        ServiceTestFixture().use { f ->
            val cloud = AccountCloudService(f.server.url("/"), f.accounts, f.http) { 1000 }
            val invalid = listOf(
                verdict().put("verified", false),
                verdict().put("account_id", "20000000-0000-0000-0000-000000000000"),
                verdict().put("source", "app-store-local"),
                verdict().put("valid_until", "1970-01-01T00:30:00Z"),
                verdict().put("valid_until", "1970-01-01T00:16:00Z"),
                verdict().put("expires_at", JSONObject.NULL),
            )
            for (value in invalid) {
                f.server.enqueue(MockResponse().setBody(value.toString()))
                assertTrue(runCatching { cloud.verifyGooglePlayPurchase("purchase-proof") }.isFailure)
            }
        }
    }
    @Test fun `account deletion requires server acknowledgement and current generation`() = runTest {
        ServiceTestFixture().use { f ->
            f.accounts.initialize()
            val cloud = AccountCloudService(f.server.url("/"), f.accounts, f.http) { 1000 }
            f.server.enqueue(MockResponse().setBody("{\"deleted\":false}"))
            assertTrue(runCatching { cloud.deleteAccount(0) }.isFailure)
            assertEquals(AccountAvailability.SIGNED_IN, f.accounts.state.value.availability)
            f.server.enqueue(MockResponse().setBody("{\"deleted\":true}"))
            cloud.deleteAccount(0)
            assertEquals(AccountAvailability.SIGNED_OUT, f.accounts.state.value.availability)
            assertEquals("/v1/account/delete", f.server.takeRequest().path)
            assertEquals("/v1/account/delete", f.server.takeRequest().path)
            assertTrue(runCatching { cloud.deleteAccount(0) }.exceptionOrNull() is AccountSessionChanged)
            assertEquals(2, f.server.requestCount)
        }
    }
    private fun verdict() = JSONObject("""{"verified":true,"active":true,"plan":"lava_security_plus","source":"google-play","account_id":"d86a16899a751c46a7f68fe5a8814440eef338fcf02219e0f4ee1edd75b7137d","product_id":"lava_plus_monthly","checked_at":"1970-01-01T00:16:40Z","valid_until":"1970-01-01T00:20:00Z","expires_at":"1970-01-01T01:00:00Z","acknowledgement_required":true}""")
}
