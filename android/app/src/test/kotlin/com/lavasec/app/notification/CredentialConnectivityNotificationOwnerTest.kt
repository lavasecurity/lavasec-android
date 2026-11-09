package com.lavasec.app.notification

import com.lavasec.app.foundation.health.PrimaryResolverIdentity
import com.lavasec.app.foundation.health.ProtectionConnectivityNotification
import com.lavasec.app.foundation.health.ProtectionConnectivityNotificationHistory
import com.lavasec.app.foundation.health.ProtectionHealthAccumulator
import com.lavasec.app.foundation.health.ProtectionHealthContext
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.vpn.VpnHealthNotificationEvidence
import com.lavasec.app.vpn.VpnHealthStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CredentialConnectivityNotificationOwnerTest {
    @Test
    fun `posts exact actionable health rejects stale engine and cancels on recovery`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val health = MutableStateFlow(VpnHealthStatus())
        val history = MemoryHistoryStore()
        val presenter = RecordingPresenter()
        var now = 1_000L
        val owner = CredentialConnectivityNotificationOwner(
            health = health,
            historyStore = history,
            presenter = presenter,
            scope = this,
            dispatcher = dispatcher,
            nowMillis = { now },
        )
        runCurrent()

        health.value = problemStatus(engine = 2L, sequence = 1L, eventAt = now)
        runCurrent()
        assertEquals(listOf("reconnect-needed:1000"), presenter.posted.map { it.identifier })
        assertEquals("reconnect-needed:1000", history.value.unresolvedProblemNotificationId)

        now += 1L
        health.value = problemStatus(engine = 1L, sequence = 99L, eventAt = now)
        runCurrent()
        assertEquals(1, presenter.posted.size)

        now += 1L
        health.value = healthyStatus(engine = 2L, sequence = 2L, recoveredAt = now)
        runCurrent()
        assertEquals(1, presenter.cancelCount)
        assertNull(history.value.unresolvedProblemNotificationId)

        owner.close()
        health.value = problemStatus(engine = 3L, sequence = 1L, eventAt = now)
        runCurrent()
        assertEquals(1, presenter.posted.size)
    }

    @Test
    fun `graph replacement reuses credential history and cannot redeliver same event`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val eventAt = 5_000L
        val health = MutableStateFlow(problemStatus(1L, 1L, eventAt))
        val history = MemoryHistoryStore()
        val presenter = RecordingPresenter()
        val first = CredentialConnectivityNotificationOwner(
            health,
            history,
            presenter,
            this,
            dispatcher,
            nowMillis = { eventAt },
        )
        runCurrent()
        assertEquals(1, presenter.posted.size)
        first.close()

        val replacement = CredentialConnectivityNotificationOwner(
            health,
            history,
            presenter,
            this,
            dispatcher,
            nowMillis = { eventAt },
        )
        runCurrent()
        assertEquals(1, presenter.posted.size)
        replacement.close()
    }

    @Test
    fun `transient history write failure preserves the pipeline for later health`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val health = MutableStateFlow(VpnHealthStatus())
        val history = MemoryHistoryStore(failedWrites = 1)
        val presenter = RecordingPresenter()
        var now = 7_000L
        val owner = CredentialConnectivityNotificationOwner(
            health,
            history,
            presenter,
            this,
            dispatcher,
            nowMillis = { now },
        )
        runCurrent()

        health.value = problemStatus(1L, 1L, now)
        runCurrent()
        now += 1L
        health.value = problemStatus(2L, 1L, now)
        runCurrent()

        assertEquals(2, history.writeAttempts)
        assertEquals(2, presenter.posted.size)
        assertEquals("reconnect-needed:7001", history.value.unresolvedProblemNotificationId)
        owner.close()
    }

    private fun problemStatus(
        engine: Long,
        sequence: Long,
        eventAt: Long,
    ) = VpnHealthStatus(
        revision = sequence,
        health = health(engine, sequence).copy(
            consecutiveResolverFailures = 3,
            requiresReconnect = true,
        ),
        notificationEvidence = VpnHealthNotificationEvidence(
            lastTransportFailureAtMillis = eventAt,
        ),
    )

    private fun healthyStatus(
        engine: Long,
        sequence: Long,
        recoveredAt: Long,
    ) = VpnHealthStatus(
        revision = sequence,
        health = health(engine, sequence),
        notificationEvidence = VpnHealthNotificationEvidence(
            lastPrimarySuccessAtMillis = recoveredAt,
        ),
    )

    private fun health(engine: Long, sequence: Long) = ProtectionHealthAccumulator(
        isConnected = true,
        context = ProtectionHealthContext(
            sessionGeneration = 1L,
            networkGeneration = 1L,
            engineGeneration = engine,
            observationSequence = sequence,
            observedAtMillis = sequence,
            primaryResolverIdentity = PrimaryResolverIdentity("device-dns:v4=01010101;v6="),
            fallbackCoverage = ResolverFallbackCoverage.ENCRYPTED_DNS,
        ),
    )

    private class MemoryHistoryStore(
        private var failedWrites: Int = 0,
    ) : ConnectivityNotificationHistoryStore {
        var value = ProtectionConnectivityNotificationHistory.EMPTY
        var writeAttempts = 0

        override fun read(): ProtectionConnectivityNotificationHistory = value

        override fun write(history: ProtectionConnectivityNotificationHistory): Boolean {
            writeAttempts += 1
            if (failedWrites > 0) {
                failedWrites -= 1
                return false
            }
            value = history
            return true
        }
    }

    private class RecordingPresenter : ProtectionProblemNotificationPresenter {
        val posted = mutableListOf<ProtectionConnectivityNotification>()
        var cancelCount = 0

        override fun post(problem: ProtectionConnectivityNotification): Boolean {
            posted += problem
            return true
        }

        override fun cancel(): Boolean {
            cancelCount += 1
            return true
        }
    }
}
