package com.lavasec.app.data

import com.lavasec.app.foundation.dns.DnsDecisionEvent
import com.lavasec.app.platform.LoggingSettings
import com.lavasec.app.product.activity.ProductActivityRevisionSignal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-local wake-up signal; durable SQLite revisions remain the source of truth. */
class ActivityRevisionBus {
    private val mutable = MutableStateFlow(ProductActivityRevisionSignal(0L))
    val current: StateFlow<ProductActivityRevisionSignal> = mutable.asStateFlow()

    @Synchronized
    fun publish(revisions: ActivityRepositoryRevisions) {
        val previous = mutable.value
        check(previous.token < Long.MAX_VALUE) { "Activity revision signal is saturated" }
        mutable.value = ProductActivityRevisionSignal(
            token = previous.token + 1L,
            policyRevision = maxOf(previous.policyRevision, revisions.policyRevision),
        )
    }
}

/**
 * The sole credential-graph writer exposed to runtime telemetry producers.
 *
 * It reads the committed privacy policy at admission time, never buffers rejected events, and
 * publishes a wake-up only after the corresponding SQLite transaction commits.
 */
class CredentialActivityWriter(
    private val repository: ActivityRepository,
    private val revisionBus: ActivityRevisionBus,
    private val guardianUsage: GuardianUsageRecorder? = null,
) {
    // Exact writer lifetime; never borrowed from another graph/attachment or reset by log pruning.
    private var runtimeProducerNamespace: Long? = null

    /** Called only after physical delivery and the existing exact credential/privacy admission. */
    @Synchronized
    fun recordRuntime(event: DnsDecisionEvent): Boolean {
        val outcome = repository.recordRuntime(event, runtimeProducerNamespace)
        runtimeProducerNamespace = outcome.namespace
        if (outcome.recorded) revisionBus.publish(repository.revisions())
        return outcome.recorded
    }

    @Synchronized
    fun record(event: DnsDecisionEvent): Boolean {
        val written = repository.record(event, repository.loggingPolicy().settings)
        if (written) revisionBus.publish(repository.revisions())
        return written
    }

    @Synchronized
    fun applyLoggingPolicy(settings: LoggingSettings): ActivityLoggingPolicySnapshot {
        val before = repository.loggingPolicy()
        val committed = repository.applyLoggingPolicy(settings)
        if (committed.settings.keepLavaGuardProgress != before.settings.keepLavaGuardProgress) {
            guardianUsage?.resetTracking()
        }
        if (committed.revision != before.revision) {
            revisionBus.publish(repository.revisions())
        }
        return committed
    }

    @Synchronized
    fun recordProtectionState(active: Boolean, atMillis: Long): Boolean {
        val policy = repository.loggingPolicy().settings
        val guardWritten = guardianUsage?.observe(active, atMillis, policy.keepLavaGuardProgress) == true
        if (!policy.keepCounts) return guardWritten
        val written = repository.recordProtectionState(active, atMillis)
        if (written) revisionBus.publish(repository.revisions())
        return written || guardWritten
    }

    @Synchronized
    fun recordProtectionHeartbeat(atMillis: Long): Boolean {
        val policy = repository.loggingPolicy().settings
        val guardWritten = guardianUsage?.observe(true, atMillis, policy.keepLavaGuardProgress) == true
        if (!policy.keepCounts) return guardWritten
        val written = repository.recordProtectionHeartbeat(atMillis)
        if (written) revisionBus.publish(repository.revisions())
        return written || guardWritten
    }

    @Synchronized
    fun publishCommittedMutation() {
        revisionBus.publish(repository.revisions())
    }
}
