package com.lavasec.app.account

import java.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AccountSessionCodecTest {
    @Test fun `session round trip retains iOS wire keys and no token in string projection`() {
        val session = AccountSession("access-secret", "refresh-secret", 3600, USER, "a@example.com", setOf("google", "apple"))
        val bytes = AccountSessionCodec.encode(session)
        val restored = AccountSessionCodec.decode(bytes, 100)
        assertEquals(session.userId, restored.userId)
        assertEquals(session.accessToken, restored.accessToken)
        assertEquals(session.refreshToken, restored.refreshToken)
        assertEquals(session.providers, restored.providers)
        assertFalse(restored.toString().contains("secret"))
        assertTrue(bytes.toString(Charsets.UTF_8).contains("access_token"))
    }

    @Test fun `invalid identity and excessive or missing expiry are rejected`() {
        for (user in listOf("wrong", USER)) {
            val session = AccountSession("access", "refresh", if (user == USER) 1_000_000 else 3600, user, null, emptySet())
            assertTrue(runCatching { AccountSessionCodec.decode(AccountSessionCodec.encode(session), 100) }.isFailure)
        }
        assertTrue(runCatching { AccountSessionCodec.decode("{}".toByteArray(), 100) }.isFailure)
    }

    @Test fun `configuration refuses secret credentials and URL redirect components`() {
        val good = "https://project.supabase.co/".toHttpUrl()
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"role\":\"service_role\"}".toByteArray())
        for (key in listOf("sb_secret_dontship", "header.$payload.signature")) {
            assertTrue(runCatching { AccountServiceConfiguration(good, key) }.isFailure)
        }
        for (url in listOf("http://project.supabase.co", "https://user@project.supabase.co", "https://project.supabase.co/?x=1")) {
            assertTrue(runCatching { AccountServiceConfiguration(url.toHttpUrl(), "sb_publishable_public") }.isFailure)
        }
        assertNotNull(AccountServiceConfiguration(good, "sb_publishable_public"))
    }

    companion object { private const val USER = "10000000-0000-0000-0000-000000000000" }
}
