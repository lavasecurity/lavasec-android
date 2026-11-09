package com.lavasec.app.backend

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AndroidAppServiceTest {
    @Test fun `exact body and purpose hash match independent Worker vectors`() {
        val body = """{"purchase_token":"secret-purchase"}""".toByteArray()
        assertEquals("bhP4SiyMK54_v7h4M-dVjAAhm9vQuTmphHZh-QvVT68",
            playIntegrityRequestHash(AndroidServiceAction.VERIFY_PURCHASE, body))
        assertEquals("HH57OO4LyEyZ9sO7EerbwHHyrVKCSmMNirWxCyH7mHk",
            playIntegrityRequestHash(AndroidServiceAction.SUBMIT_REPORT, body))
    }

    @Test fun `body is frozen before attestation and no account token is sent`() = runTest {
        ServiceTestFixture().use { f ->
            val body = """{"purchase_token":"secret-purchase"}""".toByteArray()
            val expected = body.copyOf()
            val service = AndroidAppService(f.server.url("/"), PlayIntegrityTokenPort { hash ->
                assertEquals("bhP4SiyMK54_v7h4M-dVjAAhm9vQuTmphHZh-QvVT68", hash)
                body.fill(0)
                "native-integrity-token"
            }, f.http)
            f.server.enqueue(MockResponse().setBody("{}"))
            service.post(AndroidServiceAction.VERIFY_PURCHASE, body)
            val request = f.server.takeRequest()
            assertEquals("/v1/android/entitlements/google-play-verify", request.path)
            assertArrayEquals(expected, request.body.readByteArray())
            assertEquals("native-integrity-token", request.getHeader("X-Lava-Play-Integrity"))
            assertNull(request.getHeader("Authorization"))
        }
    }

    @Test fun `failed attestation or oversized body cannot be transmitted`() = runTest {
        ServiceTestFixture().use { f ->
            var attempts = 0
            val service = AndroidAppService(f.server.url("/"), PlayIntegrityTokenPort {
                attempts++
                throw IOException("unavailable")
            }, f.http)
            assertTrue(runCatching { service.post(AndroidServiceAction.VERIFY_PURCHASE, ByteArray(8193)) }.isFailure)
            assertEquals(0, attempts)
            assertTrue(runCatching { service.post(AndroidServiceAction.SUBMIT_REPORT, "{}".toByteArray()) }.isFailure)
            assertEquals(1, attempts)
            assertEquals(0, f.server.requestCount)
        }
    }
}
