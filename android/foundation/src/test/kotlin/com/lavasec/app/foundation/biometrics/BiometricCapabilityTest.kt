package com.lavasec.app.foundation.biometrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiometricCapabilityTest {

    @Test
    fun `the four canonical biometric kinds are exactly the supported set`() {
        assertEquals(
            listOf("FINGERPRINT", "FACE", "IRIS", "WEAK_CREDENTIAL"),
            BiometricKind.entries.map(BiometricKind::name),
        )
    }

    @Test
    fun `only an available capability is usable`() {
        assertTrue(BiometricAvailability.Available(BiometricKind.FINGERPRINT).isUsable)
        assertTrue(BiometricAvailability.Available(BiometricKind.FACE).isUsable)
        assertTrue(BiometricAvailability.Available(BiometricKind.IRIS).isUsable)
        assertTrue(BiometricAvailability.Available(BiometricKind.WEAK_CREDENTIAL).isUsable)

        assertFalse(BiometricAvailability.NotEnrolled.isUsable)
        assertFalse(BiometricAvailability.HardwareUnavailable.isUsable)
        assertFalse(BiometricAvailability.KeyInvalidated.isUsable)
        assertFalse(BiometricAvailability.Unavailable.isUsable)
    }
}
