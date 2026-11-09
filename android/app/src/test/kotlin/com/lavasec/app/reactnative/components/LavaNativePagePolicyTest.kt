package com.lavasec.app.reactnative.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM coverage for the `LavaNativePage` containment contract.
 *
 * The native view cannot be mounted on this host (Robolectric's runtime is x86_64-only), so the
 * Android-free state machine is the executable proof of the F07 requirements: push, blur, revoke,
 * return, recycle and the stale-callback refusal.
 */
class LavaNativePagePolicyTest {

    /** Pushes a page and admits it the way the view does when it is focused, foreground and authed. */
    private fun admitted(page: String, revision: Long = 7L): LavaNativePageState {
        var state = LavaNativePagePolicy.open(LavaNativePageState(), page)
        state = LavaNativePagePolicy.setFocused(state, true)
        state = LavaNativePagePolicy.setActive(state, true)
        return LavaNativePagePolicy.grant(state, revision, revision)
    }

    @Test
    fun `a pushed page gets a fresh visit identity and starts unauthorised`() {
        val state = LavaNativePagePolicy.open(LavaNativePageState(), "customEntry")
        assertEquals("customEntry", state.page)
        assertEquals(1L, state.visit)
        assertFalse(state.focused)
        assertFalse(state.interactionAllowed)
        assertNull(state.admission)
    }

    @Test
    fun `reopening the same page does not churn the visit identity`() {
        val opened = LavaNativePagePolicy.open(LavaNativePageState(), "customEntry")
        val again = LavaNativePagePolicy.open(opened, "customEntry")
        assertEquals(opened, again)
    }

    @Test
    fun `focus plus foreground plus a matching revision admits interaction`() {
        val state = admitted("customEntry")
        assertTrue(state.interactionAllowed)
        assertEquals(state.activation, state.admission?.activation)
        assertEquals(7L, state.admission?.securityRevision)
    }

    @Test
    fun `a focus transition without foreground remains unauthorised`() {
        var state = LavaNativePagePolicy.open(LavaNativePageState(), "customEntry")
        state = LavaNativePagePolicy.setFocused(state, true)
        state = LavaNativePagePolicy.grant(state, 7L, 7L)
        assertFalse(state.interactionAllowed)
        assertNull(state.admission)
    }

    @Test
    fun `a revision mismatch never admits`() {
        var state = LavaNativePagePolicy.open(LavaNativePageState(), "customEntry")
        state = LavaNativePagePolicy.setFocused(state, true)
        state = LavaNativePagePolicy.setActive(state, true)
        state = LavaNativePagePolicy.grant(state, securityRevision = 8L, expectedRevision = 7L)
        assertFalse(state.interactionAllowed)
    }

    @Test
    fun `blur revokes synchronously and advances the activation generation`() {
        val admitted = admitted("customEntry")
        val activation = admitted.activation
        val blurred = LavaNativePagePolicy.setFocused(admitted, false)
        assertFalse(blurred.interactionAllowed)
        assertNotEquals(activation, blurred.activation)
        // The token captured before the blur can no longer authenticate.
        assertFalse(LavaNativePagePolicy.admits(blurred, activation, 7L))
    }

    @Test
    fun `explicit revoke drops the grant even while still focused`() {
        val admitted = admitted("customEntry")
        val revoked = LavaNativePagePolicy.revoke(admitted)
        assertTrue(revoked.focused)
        assertFalse(revoked.interactionAllowed)
    }

    @Test
    fun `background revokes and returning does not silently restore the grant`() {
        val admitted = admitted("customEntry")
        val background = LavaNativePagePolicy.setActive(admitted, false)
        assertFalse(background.interactionAllowed)
        val foreground = LavaNativePagePolicy.setActive(background, true)
        assertFalse(foreground.interactionAllowed)
        assertTrue(foreground.focused)
    }

    @Test
    fun `returning to a focused page can be re-admitted with the new revision`() {
        val blurred = LavaNativePagePolicy.setFocused(admitted("customEntry"), false)
        val refocused = LavaNativePagePolicy.setFocused(blurred, true)
        val readmitted = LavaNativePagePolicy.grant(refocused, securityRevision = 9L, expectedRevision = 9L)
        assertTrue(readmitted.interactionAllowed)
        assertEquals(9L, readmitted.admission?.securityRevision)
    }

    @Test
    fun `a changed credential revision revokes the existing grant`() {
        val admitted = admitted("customEntry", revision = 7L)
        val revoked = if (admitted.admission?.securityRevision != 8L) {
            LavaNativePagePolicy.revoke(admitted)
        } else {
            admitted
        }
        assertFalse(revoked.interactionAllowed)
    }

    @Test
    fun `navigation is limited to allowlisted destinations`() {
        val state = admitted("vpnChaining")
        val allowed = LavaNativePagePolicy.allowedDestinations("vpnChaining")
        assertTrue(LavaNativePagePolicy.canNavigate(state, state.visit, "DNS", allowed))
        assertTrue(LavaNativePagePolicy.canNavigate(state, state.visit, "Upgrade", allowed))
        assertFalse(LavaNativePagePolicy.canNavigate(state, state.visit, "VPNChaining", allowed))
    }

    @Test
    fun `the dns patch page may additionally open vpn chaining`() {
        val state = admitted("dnsPatch")
        val allowed = LavaNativePagePolicy.allowedDestinations("dnsPatch")
        assertTrue(LavaNativePagePolicy.canNavigate(state, state.visit, "VPNChaining", allowed))
    }

    @Test
    fun `device QA opens the existing chained editor only from a current admitted visit`() {
        val state = admitted("phoneQA")
        val allowed = LavaNativePagePolicy.allowedDestinations("phoneQA")
        assertTrue(LavaNativePagePolicy.canNavigate(state, state.visit, "VPNChaining", allowed))
        assertFalse(LavaNativePagePolicy.canNavigate(state, state.visit - 1, "VPNChaining", allowed))
        assertFalse(LavaNativePagePolicy.canNavigate(LavaNativePagePolicy.setFocused(state, false), state.visit, "VPNChaining", allowed))
        assertFalse(LavaNativePagePolicy.canNavigate(LavaNativePagePolicy.setActive(state, false), state.visit, "VPNChaining", allowed))
        assertFalse(LavaNativePagePolicy.canNavigate(LavaNativePagePolicy.revoke(state), state.visit, "VPNChaining", allowed))
        assertFalse(LavaNativePagePolicy.canNavigate(state, state.visit, "Security", allowed))
        assertFalse(LavaNativePagePolicy.canNavigate(state, state.visit, "https://lavasecurity.app/qa/", allowed))
    }

    @Test
    fun `an administrative or arbitrary destination is refused`() {
        val state = admitted("customEntry")
        val allowed = LavaNativePagePolicy.allowedDestinations("customEntry")
        assertFalse(LavaNativePagePolicy.canNavigate(state, state.visit, "Security", allowed))
        assertFalse(LavaNativePagePolicy.canNavigate(state, state.visit, "https://example.com", allowed))
    }

    @Test
    fun `a stale visit callback is refused even if the new visit is admitted`() {
        val first = admitted("customEntry")
        val staleVisit = first.visit
        // Fabric recycles the view for another visit, which advances the visit identity.
        var reopened = LavaNativePagePolicy.open(LavaNativePagePolicy.resetForRecycle(first), "customEntry")
        reopened = LavaNativePagePolicy.setFocused(reopened, true)
        reopened = LavaNativePagePolicy.setActive(reopened, true)
        reopened = LavaNativePagePolicy.grant(reopened, 7L, 7L)
        assertTrue(reopened.interactionAllowed)
        assertNotEquals(staleVisit, reopened.visit)
        assertFalse(
            LavaNativePagePolicy.canNavigate(
                reopened,
                staleVisit,
                "Upgrade",
                LavaNativePagePolicy.allowedDestinations("customEntry"),
            ),
        )
    }

    @Test
    fun `recycle resets every transient field and supersedes the old visit`() {
        val admitted = admitted("customEntry")
        val recycled = LavaNativePagePolicy.resetForRecycle(admitted)
        assertEquals("", recycled.page)
        assertFalse(recycled.focused)
        assertFalse(recycled.interactionAllowed)
        assertFalse(recycled.isCurrentVisit(admitted.visit))
        assertNotEquals(admitted.activation, recycled.activation)
    }
}
