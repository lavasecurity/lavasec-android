package com.lavasec.app.foundation.onboarding

import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.pause.PauseDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure first-run protection/onboarding lifecycle ported from the canonical iOS commit
 * `c4658d9`. Every test exercises a pure transition over [ProtectionLifecycle]; no Android types,
 * platform calls, or storage are involved.
 */
class ProtectionLifecycleTest {

    private val nowMillis = 1_800_000_000_000L

    // --- helpers ---------------------------------------------------------------------------

    private fun applied(result: ProtectionLifecycleResult): ProtectionLifecycleResult.Applied {
        assertTrue("expected Applied but was $result", result is ProtectionLifecycleResult.Applied)
        return result as ProtectionLifecycleResult.Applied
    }

    private fun ignored(result: ProtectionLifecycleResult): ProtectionLifecycleResult.Ignored {
        assertTrue("expected Ignored but was $result", result is ProtectionLifecycleResult.Ignored)
        return result as ProtectionLifecycleResult.Ignored
    }

    private fun refused(result: ProtectionLifecycleResult): ProtectionLifecycleResult.Refused {
        assertTrue("expected Refused but was $result", result is ProtectionLifecycleResult.Refused)
        return result as ProtectionLifecycleResult.Refused
    }

    /** A fresh install whose default filter preparation has completed. */
    private fun ready(level: ProtectionLevel = ProtectionLevel.BALANCED): ProtectionLifecycle {
        val fresh = ProtectionLifecycleMachine.freshInstall(level)
        val preparing = applied(
            ProtectionLifecycleMachine.beginDefaultFilterPreparation(fresh),
        ).state
        return applied(
            ProtectionLifecycleMachine.completeDefaultFilterPreparation(preparing, prepared = true),
        ).state
    }

    /** A ready lifecycle with a confirmed-connected runtime in its first protection session. */
    private fun readyAndConnected(): ProtectionLifecycle {
        val starting = applied(ProtectionLifecycleMachine.requestStart(ready())).state
        val established = applied(
            ProtectionLifecycleMachine.completeStart(
                starting,
                claimGeneration = starting.claimGeneration,
                success = true,
            ),
        ).state
        return applied(
            ProtectionLifecycleMachine.confirmPlatformStatus(
                established,
                ProtectionLifecycleStatus.CONNECTED,
                onDemandConfirmedEnabled = false,
            ),
        ).state
    }

    // --- required lifecycle tests ----------------------------------------------------------

    @Test
    fun `requested and confirmed protection states are independent`() {
        val fresh = ProtectionLifecycleMachine.freshInstall()
        assertFalse(fresh.isRequestedEnabled)
        assertFalse(fresh.isConfirmedEnabled)
        assertEquals(ProtectionLifecycleStatus.INVALID, fresh.confirmation.status)

        val starting = applied(ProtectionLifecycleMachine.requestStart(ready())).state
        // The start is REQUESTED but nothing is CONFIRMED yet.
        assertTrue(starting.isRequestedEnabled)
        assertFalse(starting.isConfirmedEnabled)
        assertEquals(ProtectionLifecycleStatus.INVALID, starting.confirmation.status)
        assertEquals(1L, starting.intent.revision)

        val connected = applied(
            ProtectionLifecycleMachine.confirmPlatformStatus(
                starting,
                ProtectionLifecycleStatus.CONNECTED,
                onDemandConfirmedEnabled = false,
            ),
        ).state
        assertTrue(connected.isRequestedEnabled)
        assertTrue(connected.isConfirmedEnabled)

        // Commit an OFF: requested flips immediately, confirmed does not until the platform says so.
        val stopping = applied(ProtectionLifecycleMachine.requestStop(connected)).state
        assertFalse(stopping.isRequestedEnabled)
        assertTrue(stopping.isConfirmedEnabled)
        assertTrue(stopping.isStopPending)
        assertEquals(2L, stopping.intent.revision)

        val stopped = applied(
            ProtectionLifecycleMachine.confirmPlatformStatus(
                stopping,
                ProtectionLifecycleStatus.DISCONNECTED,
                onDemandConfirmedEnabled = false,
            ),
        ).state
        assertFalse(stopped.isRequestedEnabled)
        assertFalse(stopped.isConfirmedEnabled)
    }

    @Test
    fun `stop during preparation cancels first run preparation and supersedes a pending start`() {
        // (a) A stop while the default filter is still being prepared cancels preparation.
        val preparing = applied(
            ProtectionLifecycleMachine.beginDefaultFilterPreparation(
                ProtectionLifecycleMachine.freshInstall(),
            ),
        ).state
        assertEquals(OnboardingPhase.PREPARING_DEFAULT_FILTER, preparing.onboarding)
        assertEquals(
            ProtectionLifecycleRefusal.PROTECTION_NOT_READY,
            refused(ProtectionLifecycleMachine.requestStart(preparing)).refusal,
        )

        val cancelled = applied(ProtectionLifecycleMachine.requestStop(preparing)).state
        assertEquals(OnboardingPhase.FRESH_INSTALL, cancelled.onboarding)
        assertFalse(cancelled.isRequestedEnabled)
        assertNull(cancelled.action)

        // (b) A stop while a start's preparation is in flight supersedes it.
        val starting = applied(ProtectionLifecycleMachine.requestStart(ready())).state
        val abandonedClaim = starting.claimGeneration
        val superseded = applied(ProtectionLifecycleMachine.requestStop(starting)).state
        assertEquals(ProtectionActionKind.TURN_OFF, superseded.action)
        assertFalse(superseded.isRequestedEnabled)

        // The abandoned start can never complete: it is stale and cannot fabricate connectivity.
        val stale = ignored(
            ProtectionLifecycleMachine.completeStart(
                superseded,
                claimGeneration = abandonedClaim,
                success = true,
            ),
        )
        assertEquals(ProtectionLifecycleIgnore.STALE_CLAIM, stale.reason)
        assertFalse(stale.state.isConfirmedEnabled)
        assertFalse(stale.state.isRequestedEnabled)
    }

    @Test
    fun `pause is durable and restores with a preserved deadline across a restart`() {
        val connected = readyAndConnected()
        val paused = applied(
            ProtectionLifecycleMachine.requestPause(connected, nowMillis, PauseDuration.TEN_MINUTES),
        ).state
        val pause = checkNotNull(paused.pause)
        assertEquals(nowMillis + 600_000L, pause.deadlineMillis)
        assertEquals(connected.protectionSessionId, pause.protectionSessionId)
        // A pause is a filtering window, not a turn-off: requested intent stays ON.
        assertTrue(paused.isRequestedEnabled)

        // Restart with the SAME logical session (the tunnel kept running): the deadline is preserved.
        val restored = applied(
            ProtectionLifecycleMachine.reconcilePause(paused, nowMillis + 60_000L),
        ).state
        val restoredPause = checkNotNull(restored.pause)
        assertEquals(nowMillis + 600_000L, restoredPause.deadlineMillis)
        assertEquals(paused.protectionSessionId, restoredPause.protectionSessionId)

        // Restart past the deadline clears the pause at read time.
        val expired = applied(
            ProtectionLifecycleMachine.reconcilePause(restored, nowMillis + 600_001L),
        ).state
        assertNull(expired.pause)

        // A pause bound to an older session is cleared once the active session advances.
        val newSession = paused.copy(protectionSessionId = paused.protectionSessionId + 1L)
        val sessionMismatch = applied(
            ProtectionLifecycleMachine.reconcilePause(newSession, nowMillis + 60_000L),
        ).state
        assertNull(sessionMismatch.pause)
    }

    @Test
    fun `resume with unavailable configuration surfaces an actionable recovery`() {
        val paused = applied(
            ProtectionLifecycleMachine.requestPause(
                readyAndConnected(),
                nowMillis,
                PauseDuration.FIVE_MINUTES,
            ),
        ).state

        val unavailable = applied(
            ProtectionLifecycleMachine.requestResume(paused, configurationAvailable = false),
        ).state
        // The pause is lifted (the canonical resume already ran) but the failure is actionable.
        assertNull(unavailable.pause)
        assertTrue(unavailable.isRequestedEnabled)
        assertEquals(
            ProtectionRecoveryAction.RETRY_RESUME,
            unavailable.recovery?.action,
        )
        assertTrue(unavailable.recovery is ProtectionLifecycleRecovery.ResumeConfigurationUnavailable)

        // The offered repair succeeds once the configuration is available again.
        val retried = applied(
            ProtectionLifecycleMachine.requestResume(unavailable, configurationAvailable = true),
        ).state
        assertNull(retried.pause)
        assertNull(retried.recovery)
    }

    @Test
    fun `no silent disable is used to obtain a connected state`() {
        val connected = readyAndConnected()
        val stopping = applied(ProtectionLifecycleMachine.requestStop(connected)).state

        // A later CONNECTED observation records the confirmed side without rewriting explicit intent
        // and without granting the request back: stop stays pending.
        val revision = stopping.intent.revision
        val observed = applied(
            ProtectionLifecycleMachine.confirmPlatformStatus(
                stopping,
                ProtectionLifecycleStatus.CONNECTED,
                onDemandConfirmedEnabled = false,
            ),
        ).state
        assertEquals(revision, observed.intent.revision)
        assertFalse(observed.isRequestedEnabled)
        assertTrue(observed.isConfirmedEnabled)
        assertTrue(observed.isStopPending)

        // A failed stop does not silently flip the requested state back on.
        val stopFailed = applied(
            ProtectionLifecycleMachine.completeStop(
                stopping,
                claimGeneration = stopping.claimGeneration,
                success = false,
                message = "Could not stop protection.",
            ),
        ).state
        assertFalse(stopFailed.isRequestedEnabled)
        assertEquals(ProtectionRecoveryAction.RETRY_STOP, stopFailed.recovery?.action)

        // A start request never synthesizes confirmation, and a failed start never disables intent.
        val starting = applied(ProtectionLifecycleMachine.requestStart(ready())).state
        assertFalse(starting.isConfirmedEnabled)
        val startFailed = applied(
            ProtectionLifecycleMachine.completeStart(
                starting,
                claimGeneration = starting.claimGeneration,
                success = false,
                message = "Could not start protection.",
            ),
        ).state
        assertTrue(startFailed.isRequestedEnabled)
        assertFalse(startFailed.isConfirmedEnabled)
        assertEquals(ProtectionRecoveryAction.RETRY_START, startFailed.recovery?.action)
    }

    @Test
    fun `a duplicate start does not create two runs`() {
        val first = applied(ProtectionLifecycleMachine.requestStart(ready())).state
        assertEquals(1L, first.runGeneration)
        assertEquals(ProtectionActionKind.TURN_ON, first.action)

        val duplicate = ignored(ProtectionLifecycleMachine.requestStart(first))
        assertEquals(ProtectionLifecycleIgnore.ACTION_IN_FLIGHT, duplicate.reason)
        assertEquals(1L, duplicate.state.runGeneration)
        assertEquals(first.intent.revision, duplicate.state.intent.revision)
        assertEquals(ProtectionActionKind.TURN_ON, duplicate.state.action)

        val completed = applied(
            ProtectionLifecycleMachine.completeStart(
                first,
                claimGeneration = first.claimGeneration,
                success = true,
            ),
        ).state
        assertNull(completed.action)

        // Only a fully released gate admits the next run.
        val second = applied(ProtectionLifecycleMachine.requestStart(completed)).state
        assertEquals(2L, second.runGeneration)
        assertEquals(2L, second.intent.revision)
    }

    // --- canonical policy / intent parity --------------------------------------------------

    @Test
    fun `confirmed status policy matches NetworkExtension running states`() {
        assertTrue(ProtectionLifecyclePolicy.isProtectionEnabled(ProtectionLifecycleStatus.CONNECTED))
        assertTrue(ProtectionLifecyclePolicy.isProtectionEnabled(ProtectionLifecycleStatus.CONNECTING))
        assertTrue(ProtectionLifecyclePolicy.isProtectionEnabled(ProtectionLifecycleStatus.REASSERTING))
        assertFalse(ProtectionLifecyclePolicy.isProtectionEnabled(ProtectionLifecycleStatus.INVALID))
        assertFalse(ProtectionLifecyclePolicy.isProtectionEnabled(ProtectionLifecycleStatus.DISCONNECTED))
        assertFalse(ProtectionLifecyclePolicy.isProtectionEnabled(ProtectionLifecycleStatus.DISCONNECTING))

        assertTrue(ProtectionLifecyclePolicy.isStopPending(ProtectionLifecycleStatus.DISCONNECTING))
        assertFalse(ProtectionLifecyclePolicy.isStopPending(ProtectionLifecycleStatus.DISCONNECTED))

        assertTrue(ProtectionLifecyclePolicy.isUptimeActive(ProtectionLifecycleStatus.REASSERTING))
        assertFalse(ProtectionLifecyclePolicy.isUptimeActive(ProtectionLifecycleStatus.CONNECTING))

        // Armed-but-dropped is a reconnect wait, never a full off; only `disconnected` qualifies.
        assertTrue(
            ProtectionLifecyclePolicy.isAwaitingOnDemandReconnect(
                ProtectionLifecycleStatus.DISCONNECTED,
                onDemandConfirmedEnabled = true,
            ),
        )
        assertFalse(
            ProtectionLifecyclePolicy.isAwaitingOnDemandReconnect(
                ProtectionLifecycleStatus.DISCONNECTED,
                onDemandConfirmedEnabled = false,
            ),
        )
        assertFalse(
            ProtectionLifecyclePolicy.isAwaitingOnDemandReconnect(
                ProtectionLifecycleStatus.CONNECTED,
                onDemandConfirmedEnabled = true,
            ),
        )
    }

    @Test
    fun `explicit intent advances on every user action and rejects stale captures`() {
        val disabled = ProtectionIntent(isEnabled = false, revision = 0L)
        val staleRequest = disabled.makeRestoreRequest(externalRestartGeneration = "gen-1")
        assertFalse(disabled.allows(staleRequest))

        val enabled = disabled.recordUserIntent(true)
        assertEquals(1L, enabled.revision)
        val freshCapture = enabled.makeRestoreRequest(externalRestartGeneration = "gen-1")
        assertTrue(enabled.allows(freshCapture))
        assertFalse(enabled.allows(staleRequest))

        // A same-direction reconnect still advances the revision, invalidating earlier captures.
        val reconnected = enabled.recordUserIntent(true)
        assertEquals(2L, reconnected.revision)
        assertFalse(reconnected.allows(freshCapture))

        // Protected-data recovery is authoritative too.
        assertEquals(1L, disabled.recoverFromLoadedConfiguration(false).revision)
    }
}
