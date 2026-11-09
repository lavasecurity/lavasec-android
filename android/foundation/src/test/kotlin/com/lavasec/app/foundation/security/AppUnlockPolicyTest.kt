package com.lavasec.app.foundation.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUnlockPolicyTest {
    @Test
    fun `unlock is never required while disabled`() {
        val session = AppUnlockSession(
            enabled = false,
            lastUnlockEpochMillis = null,
            lockTimeoutMinutes = 0L,
        )
        assertFalse(session.requiresUnlock(nowEpochMillis = Long.MAX_VALUE))
    }

    @Test
    fun `an enabled session with no unlock yet always requires unlock`() {
        val session = AppUnlockSession(
            enabled = true,
            lastUnlockEpochMillis = null,
            lockTimeoutMinutes = 5L,
        )
        assertTrue(session.requiresUnlock(nowEpochMillis = 0L))
    }

    @Test
    fun `immediately always re-asks even inside the same millisecond`() {
        val session = AppUnlockSession(
            enabled = true,
            lastUnlockEpochMillis = 10L,
            lockTimeoutMinutes = 0L,
        )
        assertTrue(session.requiresUnlock(nowEpochMillis = 11L))
    }

    @Test
    fun `the minute timeout keeps the session for exactly one minute`() {
        val session = AppUnlockSession(
            enabled = true,
            lastUnlockEpochMillis = 1_000L,
            lockTimeoutMinutes = 1L,
        )
        assertFalse(session.requiresUnlock(nowEpochMillis = 1_000L + 60_000L))
        assertTrue(session.requiresUnlock(nowEpochMillis = 1_000L + 60_001L))
    }

    @Test
    fun `the five minute timeout expires after five minutes`() {
        val session = AppUnlockSession(
            enabled = true,
            lastUnlockEpochMillis = 0L,
            lockTimeoutMinutes = 5L,
        )
        assertFalse(session.requiresUnlock(nowEpochMillis = 5L * 60_000L))
        assertTrue(session.requiresUnlock(nowEpochMillis = 5L * 60_000L + 1L))
    }

    @Test
    fun `the passcode derivation matches the pinned iOS sha256 of salt plus code`() {
        val salt = "salt".toByteArray(Charsets.UTF_8)
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest(salt + "1234".toByteArray(Charsets.UTF_8))
        val actual = PasscodeDerivation.derive(salt, "1234")

        assertTrue(java.util.Arrays.equals(expected, actual))
        assertTrue(PasscodeDerivation.matches(expected, actual))
        assertFalse(PasscodeDerivation.matches(expected, PasscodeDerivation.derive(salt, "4321")))
        assertFalse(PasscodeDerivation.matches(expected, byteArrayOf()))
    }

    @Test
    fun `timeout options carry their exact minutes`() {
        assertEquals(
            listOf(0L, 1L, 5L),
            LockTimeoutOption.entries.map(LockTimeoutOption::minutes),
        )
    }
}
