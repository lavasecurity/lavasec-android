package com.lavasec.app.reactnative

import com.lavasec.app.security.SecurityAvailability
import com.lavasec.app.security.SecurityOwnerState
import org.junit.Assert.*
import org.junit.Test

class NativeAppPrivacyBoundaryTest {
    private fun state(choice: Boolean?) = SecurityOwnerState(availability = SecurityAvailability.Available,
        appUnlockCoverChoice = choice)
    @Test fun `only native launch background or replacement can begin an app cover`() {
        val gate = NativeAppPrivacyBoundary()
        gate.classify(7, state(true)); assertTrue(gate.required)
        gate.acknowledge(false); assertTrue(gate.required)
        gate.acknowledge(true); assertFalse(gate.required)
        gate.classify(7, state(true)); gate.acknowledge(false); assertFalse(gate.required)
        gate.background(state(true)); assertTrue(gate.required)
        gate.acknowledge(true); assertFalse(gate.required)
        gate.classify(8, state(true)); assertTrue(gate.required)
    }
    @Test fun `enabling Open Lava during pending OFF layout applies at next background`() {
        val gate = NativeAppPrivacyBoundary()
        gate.classify(7, state(false)); assertFalse(gate.required)
        gate.classify(7, state(true)); assertFalse(gate.required)
        gate.acknowledge(false); assertFalse(gate.required)
        gate.background(state(true)); assertTrue(gate.required)
    }
    @Test fun `unknown launch classifies without turning read failure into consent`() {
        val gate = NativeAppPrivacyBoundary()
        gate.classify(7, state(null)); assertTrue(gate.required)
        gate.classify(7, state(false).copy(availability = SecurityAvailability.Unavailable)); assertFalse(gate.required)
        gate.background(state(false)); assertFalse(gate.required)
    }
}
