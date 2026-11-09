package com.lavasec.app.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LavaNativeDesignContractTest {
    @Test
    fun `brand contract is static by default and keeps native motion optional`() {
        assertEquals(LavaColors.SafeGreen, LavaLightColorScheme.primary)
        assertEquals(LavaColors.SafeGreenDark, LavaDarkColorScheme.primary)
        assertFalse(LavaTier.CALM.allowsDelightMotion)
        assertFalse(LavaTier.TECHNICAL.allowsDelightMotion)
    }
}
