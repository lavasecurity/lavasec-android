package com.lavasec.app.backend

import com.lavasec.app.diagnostics.BugReportReview
import com.lavasec.app.diagnostics.BugReportSendResult
import com.lavasec.app.diagnostics.NativeBugReportService
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NativeBugReportServiceTest {
    private val id = "af7a027a-a204-4ce9-80f7-0280ef7e7d80"
    private val body = """{"report_id":"$id","device":{"platform":"android"},"include_recent_dns_events":false,"user_description":"User-entered example.com"}""".toByteArray()
    private val review get() = BugReportReview(id, "Reviewed text", body)

    @Test fun `report uses attested exact body and matching receipt without account credentials`() = runTest {
        ServiceTestFixture().use { fixture ->
            val service = NativeBugReportService(listOf(AndroidAppService(fixture.server.url("/"), PlayIntegrityTokenPort {
                assertEquals(playIntegrityRequestHash(AndroidServiceAction.SUBMIT_REPORT, body), it)
                "native-proof"
            }, fixture.http)))
            fixture.server.enqueue(MockResponse().setResponseCode(201).setBody("""{"report_id":"$id"}"""))
            assertEquals(BugReportSendResult.SENT, service.submit(review))
            val sent = fixture.server.takeRequest()
            assertEquals("/v1/android/bug-reports", sent.path)
            assertArrayEquals(body, sent.body.readByteArray())
            assertEquals("native-proof", sent.getHeader("X-Lava-Play-Integrity"))
            assertNull(sent.getHeader("Authorization"))
        }
    }

    @Test fun `unrelated or malformed success receipt is not submitted`() = runTest {
        ServiceTestFixture().use { f ->
            val service = NativeBugReportService(listOf(AndroidAppService(f.server.url("/"), { "proof" }, f.http)))
            for (receipt in listOf("{}", "not-json", """{"report_id":123}""", """{"report_id":"1"}""")) {
                f.server.enqueue(MockResponse().setResponseCode(201).setBody(receipt))
                assertEquals(BugReportSendResult.FAILED, service.submit(review))
            }
        }
    }

    @Test fun `fallback keeps exact id and bytes but acquires a fresh integrity token`() = runTest {
        ServiceTestFixture().use { primary -> ServiceTestFixture().use { fallback ->
            var tokens = 0
            val token = PlayIntegrityTokenPort { tokens++; "proof-$tokens" }
            val service = NativeBugReportService(listOf(
                AndroidAppService(primary.server.url("/"), token, primary.http),
                AndroidAppService(fallback.server.url("/"), token, fallback.http),
            ))
            primary.server.enqueue(MockResponse().setResponseCode(503))
            fallback.server.enqueue(MockResponse().setResponseCode(201).setBody("""{"report_id":"$id"}"""))
            assertEquals(BugReportSendResult.SENT, service.submit(review))
            assertEquals(2, tokens)
            val first = primary.server.takeRequest(); val second = fallback.server.takeRequest()
            assertArrayEquals(first.body.readByteArray(), second.body.readByteArray())
            assertNotEquals(first.getHeader("X-Lava-Play-Integrity"), second.getHeader("X-Lava-Play-Integrity"))
        } }
    }

    @Test fun `429 terminates without trying fallback`() = runTest {
        ServiceTestFixture().use { f ->
            var fallbackTokens = 0
            val service = NativeBugReportService(listOf(
                AndroidAppService(f.server.url("/"), { "proof" }, f.http),
                AndroidAppService(f.server.url("/"), { fallbackTokens++; error("must not run") }, f.http),
            ))
            f.server.enqueue(MockResponse().setResponseCode(429).setBody("private error text"))
            assertEquals(BugReportSendResult.RATE_LIMITED, service.submit(review))
            assertEquals(1, f.server.requestCount)
            assertEquals(0, fallbackTokens)
        }
    }

    @Test fun `failed attestation never sends an unattested report`() = runTest {
        ServiceTestFixture().use { f ->
            val service = NativeBugReportService(listOf(AndroidAppService(f.server.url("/"), { throw IOException("not configured") }, f.http)))
            assertEquals(BugReportSendResult.FAILED, service.submit(review))
            assertEquals(0, f.server.requestCount)
            assertEquals(BugReportSendResult.UNAVAILABLE, NativeBugReportService(emptyList()).submit(review))
        }
    }
}
