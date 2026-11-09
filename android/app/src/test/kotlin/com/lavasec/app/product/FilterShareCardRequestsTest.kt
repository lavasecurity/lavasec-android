package com.lavasec.app.product

import com.lavasec.app.foundation.share.ShareableFilterConfiguration
import org.junit.Assert.*
import org.junit.Test

class FilterShareCardRequestsTest {
    private val content = FilterShareContent.Ready("selected", "controlled-code", "https://lavasecurity.app/app/filter?code=controlled", ShareableFilterConfiguration(enabledBlocklistIDs = setOf("curated"), blockedDomains = setOf("blocked.invalid")))
    private val host = Any()
    private fun owner(): FilterShareCardRequests {
        var sequence = 0
        return FilterShareCardRequests { "request-${++sequence}" }
    }
    @Test fun `unchanged polling reuses only the exact native render capability`() {
        val owner = owner()
        val first = owner.issue(content, 7L, 3L, host, "en")
        assertSame(first, owner.issue(content, 7L, 3L, host, "en"))
        assertSame(first, owner.accepted(first.token, content.filterId, 7L, 3L, host, "en", content))
    }
    @Test fun `each source privacy or host change refuses the old card`() {
        val owner = owner()
        val request = owner.issue(content, 7L, 3L, host, "en")
        assertNull(owner.accepted(request.token, "other", 7L, 3L, host, "en", content))
        assertNull(owner.accepted(request.token, content.filterId, 8L, 3L, host, "en", content))
        assertNull(owner.accepted(request.token, content.filterId, 7L, 4L, host, "en", content))
        assertNull(owner.accepted(request.token, content.filterId, 7L, 3L, Any(), "en", content))
        assertNull(owner.accepted(request.token, content.filterId, 7L, 3L, host, "ja", content))
        assertNull(owner.accepted(request.token, content.filterId, 7L, 3L, host, "en", content.copy(url = "changed")))
        assertNull(owner.accepted(request.token, content.filterId, 7L, 3L, host, "en", FilterShareContent.Unavailable(FilterShareFailureReason.UNKNOWN_FILTER)))
    }
    @Test fun `retirement cannot resurrect an identical old payload after background return`() {
        val owner = owner()
        val old = owner.issue(content, 7L, 3L, host, "en")
        owner.revoke()
        assertNull(owner.accepted(old.token, content.filterId, 7L, 3L, host, "en", content))
        val fresh = owner.issue(content, 7L, 3L, host, "en")
        assertNotEquals(old.token, fresh.token)
        assertNull(owner.accepted(old.token, content.filterId, 7L, 3L, host, "en", content))
    }
    @Test fun `a different selected payload replaces the sole capability rather than accumulating exports`() {
        val owner = owner()
        val old = owner.issue(content, 7L, 3L, host, "en")
        val changed = content.copy(code = "changed", url = "https://lavasecurity.app/app/filter?code=changed")
        val fresh = owner.issue(changed, 7L, 3L, host, "en")
        assertNull(owner.accepted(old.token, content.filterId, 7L, 3L, host, "en", content))
        assertSame(fresh, owner.accepted(fresh.token, content.filterId, 7L, 3L, host, "en", changed))
    }
    // Mirrors iOS `testTransientWithdrawalReadmitsAStillAuthorizedToken`: a surface withdrawal
    // retires only the mounted-view inventory, so the live grant still re-admits the same token.
    @Test fun `transient withdrawal readmits a still authorized token`() {
        val owner = owner()
        val request = owner.issue(content, 7L, 3L, host, "en")
        assertEquals(content.url, owner.authorizedPayload(request.token))
        assertSame(
            request,
            owner.accepted(request.token, content.filterId, 7L, 3L, host, "en", content),
        )
    }
    // Mirrors iOS `testRetiredAuthorityTokenIsNotReAdmittedAfterWithdrawal`: once the grant is
    // retired (background/security/content/host change or a successful capture) a remounted
    // surface is refused exactly as before.
    @Test fun `retired authority token is not re admitted after withdrawal`() {
        val owner = owner()
        val request = owner.issue(content, 7L, 3L, host, "en")
        owner.revoke(request.token)
        assertNull(owner.authorizedPayload(request.token))
        assertNull(owner.accepted(request.token, content.filterId, 7L, 3L, host, "en", content))
    }
}
