package com.lavasec.app.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure projection tests for the canonical `account` family. They run as plain JUnit (no
 * Robolectric/org.json) on the aarch64 work host, where the Robolectric native runtime is
 * unavailable, and pin the "never fabricate a linked provider" rule directly.
 */
class AccountPresentationTest {
    private fun signedIn(vararg providers: String) = AccountSnapshot(
        generation = 3L,
        availability = AccountAvailability.SIGNED_IN,
        userId = "11111111-1111-1111-1111-111111111111",
        email = "someone@example.com",
        providers = providers.toSet(),
    )

    private fun project(
        snapshot: AccountSnapshot? = null,
        googleAvailable: Boolean = true,
        signInProvider: String? = null,
        deleting: Boolean = false,
        message: String = "",
        appleAvailable: Boolean = false,
    ) = projectAccount(snapshot, googleAvailable, signInProvider, deleting, message, appleAvailable)

    @Test fun `a signed-out owner projects not-signed-in with no linked provider`() {
        for (availability in listOf(AccountAvailability.SIGNED_OUT, AccountAvailability.NOT_CONFIGURED)) {
            val projected = project(AccountSnapshot(0L, availability))
            assertFalse(projected.signedIn)
            assertFalse(projected.googleConnected)
            assertFalse(projected.appleConnected)
            assertEquals("Not signed in", projected.status)
        }
    }

    @Test fun `provider link state comes only from the reported providers`() {
        val google = project(signedIn(AccountProviders.GOOGLE))
        assertTrue(google.signedIn)
        assertTrue(google.googleConnected)
        assertFalse(google.appleConnected)
        assertEquals("Signed in with Google", google.status)
        assertEquals("Signed in with Google", google.googleTitle)
        assertEquals("Sign in with Apple", google.appleTitle)

        val apple = project(signedIn(AccountProviders.APPLE))
        assertTrue(apple.appleConnected)
        assertFalse(apple.googleConnected)
        assertEquals("Signed in with Apple", apple.status)

        val both = project(signedIn(AccountProviders.APPLE, AccountProviders.GOOGLE))
        assertEquals("Signed in with Apple and Google", both.status)

        // A signed-in session with no readable provider is truthful but names no provider.
        val unknown = project(signedIn())
        assertTrue(unknown.signedIn)
        assertFalse(unknown.googleConnected)
        assertFalse(unknown.appleConnected)
        assertEquals("Signed in", unknown.status)
    }

    @Test fun `absent owner and unreadable storage are distinct honest states`() {
        val absent = project(snapshot = null)
        assertFalse(absent.signedIn)
        assertEquals("Account is not connected to this interface yet.", absent.detail)

        val unreadable = project(AccountSnapshot(0L, AccountAvailability.STORAGE_UNAVAILABLE))
        assertFalse(unreadable.signedIn)
        assertEquals("Lava could not read your account on this device.", unreadable.detail)
    }

    @Test fun `an in-flight sign-in is busy and provider-scoped without claiming a link`() {
        val google = project(snapshot = AccountSnapshot(0L, AccountAvailability.SIGNED_OUT), signInProvider = AccountProviders.GOOGLE)
        assertTrue(google.busy)
        assertTrue(google.googleBusy)
        assertFalse(google.appleBusy)
        assertFalse(google.googleConnected)
        assertEquals("Signing in", google.status)
        assertEquals("Opening sign-in.", google.detail)
        assertEquals("Opening Google sign-in", google.googleTitle)

        // A completed sign-in that races the projection keeps the owner-confirmed link.
        val established = project(signedIn(AccountProviders.GOOGLE), signInProvider = AccountProviders.GOOGLE)
        assertTrue(established.googleBusy)
        assertTrue(established.googleConnected)
        assertEquals("Signed in with Google", established.googleTitle)
    }

    @Test fun `deletion is busy and the last message is projected verbatim`() {
        val deleting = project(signedIn(AccountProviders.GOOGLE), deleting = true, message = "Deleting your Lava account.")
        assertTrue(deleting.busy)
        assertEquals("Deleting your Lava account.", deleting.message)
    }

    @Test fun `provider capability never fabricates an Apple link on Android`() {
        val unconfigured = project(AccountSnapshot(0L, AccountAvailability.NOT_CONFIGURED), googleAvailable = false)
        assertFalse(unconfigured.googleAvailable)
        assertFalse(unconfigured.appleAvailable)
        val configured = project(AccountSnapshot(0L, AccountAvailability.NOT_CONFIGURED), googleAvailable = true)
        assertTrue(configured.googleAvailable)
        assertFalse(configured.appleAvailable)
    }

    @Test fun `a configured Apple adapter is available but still never fabricates a link`() {
        val available = project(AccountSnapshot(0L, AccountAvailability.SIGNED_OUT), appleAvailable = true)
        assertTrue(available.appleAvailable)
        assertFalse(available.appleConnected)

        val linked = project(signedIn(AccountProviders.APPLE), appleAvailable = true)
        assertTrue(linked.appleAvailable)
        assertTrue(linked.appleConnected)
    }
}
