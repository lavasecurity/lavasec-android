package com.lavasec.app.vpn.chained

import org.junit.Assert.*
import org.junit.Test

class ChainedDiagnosticWindowTest {
    @Test fun `minute window handles idle expiry same-second updates and engine replacement`() {
        val window = ChainedDiagnosticWindow()
        assertNull(window.totals(0))
        window.observe(0, 1, 100, 200)
        window.observe(0, 1, 150, 220)
        assertEquals(150L to 220L, window.totals(0))
        window.observe(10, 1, 250, 320)
        window.observe(20, 2, 10, 20)
        assertEquals(260L to 340L, window.totals(20))
        assertEquals(110L to 120L, window.totals(60))
        assertEquals(0L to 0L, window.totals(80))
        window.observe(19, 2, 1000, 2000)
        assertEquals(0L to 0L, window.totals(80))
    }
}
