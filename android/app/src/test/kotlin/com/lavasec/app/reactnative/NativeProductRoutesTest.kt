package com.lavasec.app.reactnative

import com.lavasec.app.foundation.flows.NativeFlow
import com.lavasec.app.presentation.ProductDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Pure-JVM coverage for the closed `native.flow` route vocabulary. [NativeProductRoutes] is
 * Robolectric-free, so this runs on the aarch64 delivery host as well as x86_64 CI, unlike
 * `NativeProductSnapshotTest`.
 */
class NativeProductRoutesTest {
    @Test
    fun `canonical licenses flow opens the offline legal notices presentation`() {
        // F13/U08: the shared Legal screen's "Full License Texts" row sends `native.flow licenses`.
        // It must open the real offline LegalNotices destination, not fall through to the bridge's
        // generic `invalid-flow` refusal.
        assertEquals("licenses", NativeFlow.LICENSES.wireName)
        val legal = NativeProductRoutes.navigation("legal")
        assertNotNull(legal)
        assertNotNull(NativeProductRoutes.navigation(NativeFlow.LICENSES.wireName))
        assertEquals(legal, NativeProductRoutes.navigation(NativeFlow.LICENSES.wireName))
        assertEquals(
            ProductDestination.LegalNotices,
            NativeProductRoutes.navigation(NativeFlow.LICENSES.wireName)!!.path.last(),
        )
    }

    @Test
    fun `unknown and internal flows still resolve to no navigation`() {
        assertEquals(null, NativeProductRoutes.navigation("https://example.com"))
        assertEquals(null, NativeProductRoutes.navigation("vpnConfiguration"))
    }
}
