package com.lavasec.app.notification

import com.lavasec.app.foundation.health.ProtectionConnectivityNotification
import com.lavasec.app.foundation.health.ProtectionConnectivityNotificationHistory
import com.lavasec.app.foundation.health.ProtectionConnectivityNotificationPolicy
import com.lavasec.app.foundation.health.ProtectionHealthContext
import com.lavasec.app.vpn.VpnHealthStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal interface ConnectivityNotificationHistoryStore {
    fun read(): ProtectionConnectivityNotificationHistory?

    fun write(history: ProtectionConnectivityNotificationHistory): Boolean
}

internal interface ProtectionProblemNotificationPresenter {
    fun post(problem: ProtectionConnectivityNotification): Boolean

    fun cancel(): Boolean
}

/** Credential-graph owner for actionable notifications derived from exact VPN health only. */
internal class CredentialConnectivityNotificationOwner(
    health: Flow<VpnHealthStatus>,
    private val historyStore: ConnectivityNotificationHistoryStore,
    private val presenter: ProtectionProblemNotificationPresenter,
    scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val ownerJob = SupervisorJob(scope.coroutineContext[Job])
    private val ownerScope = CoroutineScope(scope.coroutineContext + ownerJob)
    private var history: ProtectionConnectivityNotificationHistory? = historyStore.read()
    private var lastContext: ProtectionHealthContext? = null

    init {
        ownerScope.launch {
            health.collect { status ->
                withContext(dispatcher) { consume(status) }
            }
        }
    }

    override fun close() {
        ownerJob.cancel()
    }

    private fun consume(status: VpnHealthStatus) {
        val currentHistory = history ?: return
        val context = status.health.context
        if (context == null) {
            resolveRetiredSession(currentHistory)
            return
        }
        val previous = lastContext
        if (previous != null && !context.isStrictlyAfter(previous)) return
        lastContext = context

        val now = nowMillis().takeIf { it >= 0L } ?: return
        val policyEvidence = status.notificationEvidence.toPolicyEvidence()
        val assessment = status.health.assessment
        val resolved = ProtectionConnectivityNotificationPolicy.resolvedProblemNotificationIds(
            assessment = assessment,
            evidence = policyEvidence,
            history = currentHistory,
            nowMillis = now,
        )
        if (resolved.isNotEmpty()) {
            if (!presenter.cancel()) return
            val next = currentHistory.copy(
                problemDeliveryCooldownUntilMillis =
                    ProtectionConnectivityNotificationPolicy
                        .problemDeliveryCooldownUntilAfterClear(
                            assessment = assessment,
                            history = currentHistory,
                            nowMillis = now,
                        ) ?: currentHistory.problemDeliveryCooldownUntilMillis,
                unresolvedProblemNotificationId = null,
                unresolvedProblemKind = null,
            )
            commit(next)
            return
        }

        val problem = ProtectionConnectivityNotificationPolicy.notification(
            assessment = assessment,
            evidence = policyEvidence,
            history = currentHistory,
            nowMillis = now,
        ) ?: return
        if (!presenter.post(problem)) return
        commit(
            currentHistory.copy(
                lastDeliveredNotificationId = problem.identifier,
                lastDeliveredAtMillis = now,
                unresolvedProblemNotificationId = problem.identifier,
                unresolvedProblemKind = problem.kind,
            ),
        )
    }

    private fun resolveRetiredSession(currentHistory: ProtectionConnectivityNotificationHistory) {
        if (currentHistory.unresolvedProblemNotificationId == null) return
        if (!presenter.cancel()) return
        commit(
            currentHistory.copy(
                unresolvedProblemNotificationId = null,
                unresolvedProblemKind = null,
            ),
        )
    }

    private fun commit(next: ProtectionConnectivityNotificationHistory) {
        if (historyStore.write(next)) history = next
    }
}

private fun ProtectionHealthContext.isStrictlyAfter(previous: ProtectionHealthContext): Boolean {
    if (sessionGeneration != previous.sessionGeneration) {
        return sessionGeneration > previous.sessionGeneration
    }
    if (networkGeneration != previous.networkGeneration) {
        return networkGeneration > previous.networkGeneration
    }
    if (engineGeneration != previous.engineGeneration) {
        return engineGeneration > previous.engineGeneration
    }
    return observationSequence > previous.observationSequence
}
