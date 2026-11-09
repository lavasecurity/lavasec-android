package com.lavasec.app.edition

import com.lavasec.app.presentation.ProductNavigation
import org.junit.Assert.*
import org.junit.Test

class EditionDeepLinksTest {
    @Test fun `custom scheme ownership is isolated for coinstalled packages`() {
        assertEquals(ProductNavigation.guard, EditionDeepLinks.parse("lavasecurity://guard", AppEdition.PLAY))
        assertEquals(ProductNavigation.guard, EditionDeepLinks.parse("lavasecurity-foss://guard", AppEdition.FOSS))
        assertNull(EditionDeepLinks.parse("lavasecurity://guard", AppEdition.FOSS))
        assertNull(EditionDeepLinks.parse("lavasecurity-foss://guard", AppEdition.PLAY))
    }

    @Test fun `edition schemes retain route and malformed URI validation`() {
        for (edition in AppEdition.entries) {
            assertNull(EditionDeepLinks.parse("${edition.deepLinkScheme}://account", edition))
            assertNull(EditionDeepLinks.parse("${edition.deepLinkScheme}://guard?account=x", edition))
            assertNull(EditionDeepLinks.parse("${edition.deepLinkScheme}://guard#ignored", edition))
            assertNull(EditionDeepLinks.parse("${edition.deepLinkScheme}://user@guard", edition))
            assertEquals(ProductNavigation.guard, EditionDeepLinks.parse("https://lavasecurity.app/app/guard", edition))
        }
    }
}
