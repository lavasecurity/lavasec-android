package com.lavasec.app.vpn

@JvmInline
internal value class TunQueryId(val value: Long)

internal sealed interface TunQueryState {
    data object Planning : TunQueryState
    data object PlanningSuperseded : TunQueryState
    data class ResolutionReady(val plan: ProxyPlan.Forward) : TunQueryState
    data class WaitingResolution(val plan: ProxyPlan.Forward) : TunQueryState
    data class Resolving(
        val plan: ProxyPlan.Forward,
        val workerIndex: Int,
    ) : TunQueryState
    data class AwaitingOriginalWriteCapacity(
        val pending: ProxyResult.Pending,
        val retainsResolutionSlot: Boolean,
    ) : TunQueryState
    data object AwaitingReplacementWriteCapacity : TunQueryState
    data class ReadyPending(val pending: ProxyResult.Pending) : TunQueryState
    data class StagedOriginal(val publication: ProxyPublication) : TunQueryState
    data class WrittenOriginalAwaitingEvidence(
        val evidence: TunTerminalEvidence,
    ) : TunQueryState
    data object ReplacementReady : TunQueryState
    data object Suppressed : TunQueryState
    data object Terminal : TunQueryState
}

internal data class TunSchedulerSnapshot(
    val readerPermitHeld: Boolean,
    val liveRecords: Int,
    val activeResolutions: Int,
    val waitingResolutions: Int,
    val materializedWrites: Int,
    val replacementPromotions: Int,
    val states: Map<TunQueryId, String>,
)

internal sealed interface TunPlanCommit {
    data object Dropped : TunPlanCommit
    data object Queued : TunPlanCommit
    data class RejectOverloaded(
        val id: TunQueryId,
        val plan: ProxyPlan.Forward,
    ) : TunPlanCommit
    data class AwaitWriterCapacity(val id: TunQueryId) : TunPlanCommit
    data class ReplacementOwned(val id: TunQueryId) : TunPlanCommit
    data object Suppressed : TunPlanCommit
}

internal data class TunResolutionClaim(
    val id: TunQueryId,
    val plan: ProxyPlan.Forward,
)

internal enum class TunResolutionFailureDisposition {
    IGNORE_STALE,
    TERMINATE_CURRENT,
}

internal enum class TunPlanningFailureDisposition {
    IGNORE_STALE,
    TERMINATE_CURRENT,
}

internal sealed interface TunWorkerCommit {
    data object Materialized : TunWorkerCommit
    data class AwaitWriterCapacity(val id: TunQueryId) : TunWorkerCommit
    data class ReplacementOwned(val id: TunQueryId) : TunWorkerCommit
    data object Suppressed : TunWorkerCommit
}

internal sealed interface TunWriterClaim {
    val id: TunQueryId

    data class Publish(
        override val id: TunQueryId,
        val pending: ProxyResult.Pending,
    ) : TunWriterClaim

    data class Write(override val id: TunQueryId) : TunWriterClaim
}

internal sealed interface TunSelectedWrite {
    val id: TunQueryId
    val packet: ByteArray

    data class Original(
        override val id: TunQueryId,
        override val packet: ByteArray,
    ) : TunSelectedWrite

    data class Replacement(
        override val id: TunQueryId,
        override val packet: ByteArray,
    ) : TunSelectedWrite
}

internal sealed interface TunWriteCommit {
    data object ReplacementComplete : TunWriteCommit
    data class OriginalEvidence(val evidence: TunTerminalEvidence) : TunWriteCommit
}

internal sealed interface TunEngineInstall {
    data class Installed(val priorEngine: ReadyDnsEngine) : TunEngineInstall
    data object RejectedAlreadyCurrent : TunEngineInstall
    data object RejectedUninstalled : TunEngineInstall
}

internal class TunQueryScheduler(
    private val sessionToken: Long,
    initialEngine: ReadyDnsEngine,
    private val capacities: TunRuntimeCapacities,
) {
    private val records = LinkedHashMap<TunQueryId, QueryRecord>()
    private val waitingResolutionIds = ArrayDeque<TunQueryId>()
    private val writerIds = ArrayDeque<TunQueryId>()
    private val replacementPromotionIds = ArrayDeque<TunQueryId>()
    private var currentEngine: ReadyDnsEngine? = initialEngine
    private var nextId = 1L
    private var readerPermitHeld = false

    init {
        require(sessionToken > 0L) { "session token must be positive" }
        require(initialEngine.epoch.sessionGeneration == sessionToken) {
            "initial engine must belong to the scheduler session"
        }
        assertInvariants()
    }

    fun reserveReaderPermit(): Boolean = checked {
        if (
            readerPermitHeld ||
            currentEngine == null ||
            records.size + 1 > capacities.liveRecords
        ) {
            false
        } else {
            readerPermitHeld = true
            true
        }
    }

    fun releaseReaderPermit() = checked {
        readerPermitHeld = false
    }

    fun currentEngineForPlanning(): ReadyDnsEngine? = checked {
        currentEngine
    }

    fun isCurrentEngine(engine: ReadyDnsEngine): Boolean = checked {
        currentEngine === engine
    }

    fun retainsReaderWriterCapacity(id: TunQueryId): Boolean = checked {
        when (records[id]?.state) {
            is TunQueryState.AwaitingOriginalWriteCapacity,
            TunQueryState.AwaitingReplacementWriteCapacity,
            -> true
            else -> false
        }
    }

    fun beginPlanning(packet: ByteArray, engine: ReadyDnsEngine): TunQueryId = checked {
        check(readerPermitHeld) { "reader permit must be reserved before planning" }
        check(currentEngine === engine) { "planning engine must still be current" }
        require(packet.isNotEmpty()) { "captured packet must not be empty" }
        require(packet.size <= DnsOnlyTunContract.MTU) {
            "captured packet exceeds the TUN MTU"
        }
        check(nextId in 1L until Long.MAX_VALUE) { "query identifier space exhausted" }

        val id = TunQueryId(nextId++)
        records[id] = QueryRecord(
            id = id,
            capturedPacket = packet,
            sessionToken = sessionToken,
            engineEpoch = engine.epoch,
            engineGeneration = engine.epoch.engineGeneration,
            state = TunQueryState.Planning,
        )
        readerPermitHeld = false
        id
    }

    fun commitPlan(id: TunQueryId, plan: ProxyPlan): TunPlanCommit = checked {
        val record = records[id] ?: return@checked TunPlanCommit.Suppressed
        when (record.state) {
            TunQueryState.Planning -> commitCurrentPlan(record, plan)
            TunQueryState.PlanningSuperseded -> commitSupersededPlan(record, plan)
            TunQueryState.Suppressed,
            TunQueryState.Terminal,
            -> TunPlanCommit.Suppressed
            else -> error("query $id is not awaiting a plan")
        }
    }

    fun commitOverload(id: TunQueryId, result: ProxyResult): TunPlanCommit = checked {
        val record = records[id] ?: return@checked TunPlanCommit.Suppressed
        when (record.state) {
            TunQueryState.Planning -> when (result) {
                is ProxyResult.Pending -> {
                    if (materializeOriginal(record, result, retainsResolutionSlot = false)) {
                        TunPlanCommit.Queued
                    } else {
                        TunPlanCommit.AwaitWriterCapacity(id)
                    }
                }
                ProxyResult.NotPublishable -> {
                    terminalize(record)
                    TunPlanCommit.Dropped
                }
            }
            TunQueryState.PlanningSuperseded -> {
                scheduleReplacement(record)
                TunPlanCommit.ReplacementOwned(id)
            }
            TunQueryState.AwaitingReplacementWriteCapacity,
            TunQueryState.ReplacementReady,
            -> TunPlanCommit.ReplacementOwned(id)
            TunQueryState.Suppressed,
            TunQueryState.Terminal,
            -> TunPlanCommit.Suppressed
            else -> error("query $id is not awaiting overload rejection")
        }
    }

    fun classifyPlanningFailure(
        id: TunQueryId,
    ): TunPlanningFailureDisposition = checked {
        val record =
            records[id] ?: return@checked TunPlanningFailureDisposition.IGNORE_STALE
        when (record.state) {
            TunQueryState.Planning ->
                TunPlanningFailureDisposition.TERMINATE_CURRENT
            TunQueryState.PlanningSuperseded -> {
                if (record.hasReplacement()) {
                    scheduleReplacement(record)
                } else {
                    terminalize(record)
                }
                TunPlanningFailureDisposition.IGNORE_STALE
            }
            TunQueryState.AwaitingReplacementWriteCapacity,
            TunQueryState.ReplacementReady,
            TunQueryState.Suppressed,
            TunQueryState.Terminal,
            -> TunPlanningFailureDisposition.IGNORE_STALE
            else -> error("query $id has unexpected state after planning failure")
        }
    }

    fun claimResolution(workerIndex: Int): TunResolutionClaim? = checked {
        require(workerIndex >= 0) { "worker index must be non-negative" }
        val record = records.values.firstOrNull {
            it.state is TunQueryState.ResolutionReady
        } ?: return@checked null
        val ready = record.state as TunQueryState.ResolutionReady
        record.state = TunQueryState.Resolving(ready.plan, workerIndex)
        TunResolutionClaim(record.id, ready.plan)
    }

    fun completeResolution(
        id: TunQueryId,
        result: ProxyResult,
    ): TunWorkerCommit = checked {
        val record = records[id] ?: return@checked TunWorkerCommit.Suppressed
        when (record.state) {
            is TunQueryState.Resolving -> when (result) {
                is ProxyResult.Pending -> {
                    if (materializeOriginal(record, result, retainsResolutionSlot = true)) {
                        TunWorkerCommit.Materialized
                    } else {
                        TunWorkerCommit.AwaitWriterCapacity(id)
                    }
                }
                ProxyResult.NotPublishable -> {
                    terminalize(record)
                    promoteWaitingResolutions()
                    TunWorkerCommit.Suppressed
                }
            }
            TunQueryState.AwaitingReplacementWriteCapacity,
            TunQueryState.ReplacementReady,
            -> TunWorkerCommit.ReplacementOwned(id)
            TunQueryState.Suppressed,
            TunQueryState.Terminal,
            -> TunWorkerCommit.Suppressed
            else -> error("query $id is not resolving")
        }
    }

    fun classifyResolutionFailure(
        claim: TunResolutionClaim,
        workerIndex: Int,
    ): TunResolutionFailureDisposition = checked {
        val record =
            records[claim.id] ?: return@checked TunResolutionFailureDisposition.IGNORE_STALE
        when (val state = record.state) {
            is TunQueryState.Resolving -> {
                check(state.plan === claim.plan && state.workerIndex == workerIndex) {
                    "resolution failure does not match the current worker claim"
                }
                TunResolutionFailureDisposition.TERMINATE_CURRENT
            }
            TunQueryState.AwaitingReplacementWriteCapacity,
            TunQueryState.ReplacementReady,
            TunQueryState.Suppressed,
            TunQueryState.Terminal,
            -> TunResolutionFailureDisposition.IGNORE_STALE
            else -> error("query ${claim.id} has unexpected state after resolution failure")
        }
    }

    fun materializeAwaitingWrite(id: TunQueryId): Boolean = checked {
        val record = records[id] ?: return@checked false
        when (val state = record.state) {
            is TunQueryState.AwaitingOriginalWriteCapacity -> {
                if (materializedWriteCount() >= capacities.materializedWrites) {
                    false
                } else {
                    record.state = TunQueryState.ReadyPending(state.pending)
                    scheduleWriter(record)
                    if (state.retainsResolutionSlot) promoteWaitingResolutions()
                    true
                }
            }
            TunQueryState.AwaitingReplacementWriteCapacity -> {
                if (materializedWriteCount() >= capacities.materializedWrites) {
                    false
                } else {
                    record.state = TunQueryState.ReplacementReady
                    scheduleReplacementPromotion(record)
                    true
                }
            }
            else -> false
        }
    }

    fun nextWriterClaim(): TunWriterClaim? = checked {
        nextReplacementClaim() ?: nextOriginalClaim() ?: nextAwaitingOriginalClaim()
    }

    fun stagePublication(
        id: TunQueryId,
        publication: ProxyPublication,
    ): Boolean = checked {
        val record = records[id] ?: return@checked false
        if (record.state is TunQueryState.ReadyPending) {
            record.state = TunQueryState.StagedOriginal(publication)
            true
        } else {
            false
        }
    }

    fun selectWrite(id: TunQueryId): TunSelectedWrite? = checked {
        val record = records[id] ?: return@checked null
        when (val state = record.state) {
            is TunQueryState.StagedOriginal -> {
                removeWriterSchedule(record)
                TunSelectedWrite.Original(
                    id = id,
                    packet = state.publication.responsePacket,
                )
            }
            TunQueryState.ReplacementReady -> {
                if (record.replacementScheduled) {
                    check(replacementPromotionIds.remove(record.id))
                    record.replacementScheduled = false
                }
                TunSelectedWrite.Replacement(
                    id = id,
                    packet = record.replacementPacket(),
                )
            }
            else -> null
        }
    }

    fun commitWritten(id: TunQueryId): TunWriteCommit = checked {
        val record = checkNotNull(records[id]) { "unknown writer query $id" }
        when (val state = record.state) {
            is TunQueryState.StagedOriginal -> {
                val evidence = TunTerminalEvidence(
                    engineGeneration = record.engineGeneration,
                    activity = state.publication.activity,
                    healthEvents = state.publication.healthEvents,
                    resolverQueryEvidence = state.publication.resolverQueryEvidence,
                    primaryAnswerBearing = state.publication.primaryAnswerBearing,
                )
                record.state = TunQueryState.WrittenOriginalAwaitingEvidence(evidence)
                TunWriteCommit.OriginalEvidence(evidence)
            }
            TunQueryState.ReplacementReady -> {
                terminalize(record)
                TunWriteCommit.ReplacementComplete
            }
            else -> error("query $id has no selected write")
        }
    }

    fun retainWouldBlock(id: TunQueryId) = checked {
        val record = records[id] ?: return@checked
        when (record.state) {
            is TunQueryState.StagedOriginal -> scheduleWriter(record)
            TunQueryState.ReplacementReady -> scheduleReplacementPromotion(record)
            else -> Unit
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun finishEvidence(id: TunQueryId, accepted: Boolean) = checked {
        val record = records[id] ?: return@checked
        if (record.state is TunQueryState.WrittenOriginalAwaitingEvidence) {
            terminalize(record)
        }
    }

    fun failWriter(id: TunQueryId) = checked {
        val record = records[id] ?: return@checked
        when (record.state) {
            is TunQueryState.ReadyPending,
            is TunQueryState.StagedOriginal,
            -> terminalize(record)
            TunQueryState.AwaitingReplacementWriteCapacity,
            TunQueryState.ReplacementReady,
            -> scheduleReplacementPromotion(record)
            else -> Unit
        }
    }

    fun installReplacement(nextEngine: ReadyDnsEngine): TunEngineInstall = checked {
        val previous =
            currentEngine ?: return@checked TunEngineInstall.RejectedUninstalled
        if (nextEngine === previous) {
            return@checked TunEngineInstall.RejectedAlreadyCurrent
        }
        if (
            nextEngine.epoch.sessionGeneration != sessionToken ||
            nextEngine.epoch.engineGeneration <= previous.epoch.engineGeneration
        ) {
            return@checked TunEngineInstall.RejectedUninstalled
        }

        currentEngine = nextEngine
        records.values.forEach { record ->
            if (record.engineGeneration < nextEngine.epoch.engineGeneration) {
                when (record.state) {
                    TunQueryState.Planning -> {
                        record.state = TunQueryState.PlanningSuperseded
                    }
                    TunQueryState.PlanningSuperseded,
                    is TunQueryState.WrittenOriginalAwaitingEvidence,
                    TunQueryState.Suppressed,
                    TunQueryState.Terminal,
                    -> Unit
                    else -> scheduleReplacement(record)
                }
            }
        }
        promoteWaitingResolutions()
        TunEngineInstall.Installed(previous)
    }

    fun suppressAll(): List<ReadyDnsEngine> = checked {
        val engine = currentEngine ?: return@checked emptyList()
        currentEngine = null
        waitingResolutionIds.clear()
        writerIds.clear()
        replacementPromotionIds.clear()
        records.values.forEach { record ->
            record.writerScheduled = false
            record.replacementScheduled = false
            if (record.state !is TunQueryState.WrittenOriginalAwaitingEvidence) {
                record.state = TunQueryState.Suppressed
            }
        }
        listOf(engine)
    }

    fun releaseSuppressed() = checked {
        records.values
            .filter {
                it.state === TunQueryState.Suppressed ||
                    it.state === TunQueryState.Terminal
            }
            .toList()
            .forEach(::terminalize)
    }

    fun snapshot(): TunSchedulerSnapshot = checked {
        TunSchedulerSnapshot(
            readerPermitHeld = readerPermitHeld,
            liveRecords = records.size,
            activeResolutions = activeResolutionCount(),
            waitingResolutions = waitingResolutionCount(),
            materializedWrites = materializedWriteCount(),
            replacementPromotions = replacementPromotionIds.size,
            states = records.mapValuesTo(linkedMapOf()) { (_, record) ->
                record.state.snapshotName()
            },
        )
    }

    private fun commitCurrentPlan(
        record: QueryRecord,
        plan: ProxyPlan,
    ): TunPlanCommit = when (plan) {
        ProxyPlan.Drop -> {
            terminalize(record)
            TunPlanCommit.Dropped
        }
        is ProxyPlan.Immediate -> {
            record.installReplacement(plan.replacement)
            if (materializeOriginal(record, plan.result, retainsResolutionSlot = false)) {
                TunPlanCommit.Queued
            } else {
                TunPlanCommit.AwaitWriterCapacity(record.id)
            }
        }
        is ProxyPlan.Forward -> {
            record.installReplacement(plan.replacement)
            when {
                activeResolutionCount() < capacities.activeResolutions -> {
                    record.state = TunQueryState.ResolutionReady(plan)
                    TunPlanCommit.Queued
                }
                waitingResolutionCount() < capacities.waitingResolutions -> {
                    record.state = TunQueryState.WaitingResolution(plan)
                    waitingResolutionIds.addLast(record.id)
                    TunPlanCommit.Queued
                }
                else -> TunPlanCommit.RejectOverloaded(record.id, plan)
            }
        }
    }

    private fun commitSupersededPlan(
        record: QueryRecord,
        plan: ProxyPlan,
    ): TunPlanCommit = when (plan) {
        ProxyPlan.Drop -> {
            terminalize(record)
            TunPlanCommit.Dropped
        }
        is ProxyPlan.Immediate -> {
            record.installReplacement(plan.replacement)
            scheduleReplacement(record)
            TunPlanCommit.ReplacementOwned(record.id)
        }
        is ProxyPlan.Forward -> {
            record.installReplacement(plan.replacement)
            scheduleReplacement(record)
            TunPlanCommit.ReplacementOwned(record.id)
        }
    }

    private fun materializeOriginal(
        record: QueryRecord,
        pending: ProxyResult.Pending,
        retainsResolutionSlot: Boolean,
    ): Boolean {
        if (materializedWriteCount() >= capacities.materializedWrites) {
            record.state = TunQueryState.AwaitingOriginalWriteCapacity(
                pending = pending,
                retainsResolutionSlot = retainsResolutionSlot,
            )
            return false
        }
        record.state = TunQueryState.ReadyPending(pending)
        scheduleWriter(record)
        if (retainsResolutionSlot) promoteWaitingResolutions()
        return true
    }

    private fun scheduleReplacement(record: QueryRecord) {
        check(record.hasReplacement()) { "replacement packet is not installed" }
        val alreadyOwnsCapacity = record.state.isMaterializedWrite()
        waitingResolutionIds.remove(record.id)
        removeWriterSchedule(record)
        record.state = if (
            alreadyOwnsCapacity ||
            materializedWriteCount() < capacities.materializedWrites
        ) {
            TunQueryState.ReplacementReady
        } else {
            TunQueryState.AwaitingReplacementWriteCapacity
        }
        scheduleReplacementPromotion(record)
        promoteWaitingResolutions()
    }

    private fun scheduleWriter(record: QueryRecord) {
        if (!record.writerScheduled) {
            record.writerScheduled = true
            writerIds.addLast(record.id)
        }
    }

    private fun removeWriterSchedule(record: QueryRecord) {
        if (record.writerScheduled) {
            check(writerIds.remove(record.id))
            record.writerScheduled = false
        }
    }

    private fun scheduleReplacementPromotion(record: QueryRecord) {
        if (!record.replacementScheduled) {
            record.replacementScheduled = true
            replacementPromotionIds.addLast(record.id)
        }
    }

    private fun nextReplacementClaim(): TunWriterClaim.Write? {
        if (replacementPromotionIds.isEmpty()) return null

        val capacityAvailable =
            materializedWriteCount() < capacities.materializedWrites
        val claimIndex = if (capacityAvailable) {
            0
        } else {
            replacementPromotionIds.indexOfFirst { id ->
                records[id]?.state === TunQueryState.ReplacementReady
            }
        }
        if (claimIndex < 0) return null

        val id = replacementPromotionIds.removeAt(claimIndex)
        val record = checkNotNull(records[id])
        check(
            record.state === TunQueryState.AwaitingReplacementWriteCapacity ||
                record.state === TunQueryState.ReplacementReady,
        )
        if (record.state === TunQueryState.AwaitingReplacementWriteCapacity) {
            check(capacityAvailable)
            record.state = TunQueryState.ReplacementReady
        }
        record.replacementScheduled = false
        removeWriterSchedule(record)
        return TunWriterClaim.Write(id)
    }

    private fun nextOriginalClaim(): TunWriterClaim? {
        while (writerIds.isNotEmpty()) {
            val id = writerIds.removeFirst()
            val record = records[id] ?: continue
            record.writerScheduled = false
            when (val state = record.state) {
                is TunQueryState.ReadyPending ->
                    return TunWriterClaim.Publish(id, state.pending)
                is TunQueryState.StagedOriginal ->
                    return TunWriterClaim.Write(id)
                else -> Unit
            }
        }
        return null
    }

    private fun nextAwaitingOriginalClaim(): TunWriterClaim.Publish? {
        if (materializedWriteCount() >= capacities.materializedWrites) return null
        val record = records.values.firstOrNull {
            it.state is TunQueryState.AwaitingOriginalWriteCapacity
        } ?: return null
        val awaiting = record.state as TunQueryState.AwaitingOriginalWriteCapacity
        record.state = TunQueryState.ReadyPending(awaiting.pending)
        if (awaiting.retainsResolutionSlot) promoteWaitingResolutions()
        return TunWriterClaim.Publish(record.id, awaiting.pending)
    }

    private fun promoteWaitingResolutions() {
        while (
            activeResolutionCount() < capacities.activeResolutions &&
            waitingResolutionIds.isNotEmpty()
        ) {
            val id = waitingResolutionIds.removeFirst()
            val record = records[id] ?: continue
            val waiting = record.state as? TunQueryState.WaitingResolution ?: continue
            record.state = TunQueryState.ResolutionReady(waiting.plan)
        }
    }

    private fun terminalize(record: QueryRecord) {
        val before = ownershipAccounting()
        val released = record.ownership()

        check(waitingResolutionIds.remove(record.id) == (released.waitingQueueEntries == 1))
        removeWriterSchedule(record)
        if (record.replacementScheduled) {
            check(replacementPromotionIds.remove(record.id))
            record.replacementScheduled = false
        }
        record.state = TunQueryState.Terminal
        check(records.remove(record.id) === record) {
            "query ${record.id} was released more than once"
        }
        check(ownershipAccounting() == before - released) {
            "terminal release did not subtract the record's exact ownership"
        }
    }

    private fun activeResolutionCount(): Int = records.values.count {
        when (val state = it.state) {
            is TunQueryState.ResolutionReady,
            is TunQueryState.Resolving,
            -> true
            is TunQueryState.AwaitingOriginalWriteCapacity ->
                state.retainsResolutionSlot
            else -> false
        }
    }

    private fun waitingResolutionCount(): Int = records.values.count {
        it.state is TunQueryState.WaitingResolution
    }

    private fun materializedWriteCount(): Int = records.values.count {
        it.state.isMaterializedWrite()
    }

    private fun TunQueryState.isMaterializedWrite(): Boolean =
        this is TunQueryState.ReadyPending ||
            this is TunQueryState.StagedOriginal ||
            this is TunQueryState.WrittenOriginalAwaitingEvidence ||
            this === TunQueryState.ReplacementReady

    private fun TunQueryState.snapshotName(): String = when (this) {
        TunQueryState.Planning -> "Planning"
        TunQueryState.PlanningSuperseded -> "PlanningSuperseded"
        is TunQueryState.ResolutionReady -> "ResolutionReady"
        is TunQueryState.WaitingResolution -> "WaitingResolution"
        is TunQueryState.Resolving -> "Resolving"
        is TunQueryState.AwaitingOriginalWriteCapacity ->
            "AwaitingOriginalWriteCapacity"
        TunQueryState.AwaitingReplacementWriteCapacity ->
            "AwaitingReplacementWriteCapacity"
        is TunQueryState.ReadyPending -> "ReadyPending"
        is TunQueryState.StagedOriginal -> "StagedOriginal"
        is TunQueryState.WrittenOriginalAwaitingEvidence ->
            "WrittenOriginalAwaitingEvidence"
        TunQueryState.ReplacementReady -> "ReplacementReady"
        TunQueryState.Suppressed -> "Suppressed"
        TunQueryState.Terminal -> "Terminal"
    }

    private fun assertInvariants() {
        val ownership = ownershipAccounting()
        val recordsOwnership = records.values
            .map(QueryRecord::ownership)
            .fold(RecordOwnership.None, RecordOwnership::plus)
        check(ownership == recordsOwnership.toAccounting()) {
            "record categories do not conserve scheduler ownership"
        }
        check(ownership.activeResolutions <= capacities.activeResolutions) {
            "active resolution capacity exceeded"
        }
        check(ownership.waitingResolutions <= capacities.waitingResolutions) {
            "waiting resolution capacity exceeded"
        }
        check(ownership.materializedWrites <= capacities.materializedWrites) {
            "materialized write capacity exceeded"
        }
        check(
            records.size +
                (if (readerPermitHeld) 1 else 0) <= capacities.liveRecords,
        ) {
            "live record capacity exceeded"
        }
        check(replacementPromotionIds.size <= records.size) {
            "replacement promotions exceed live records"
        }

        assertQueue(
            ids = waitingResolutionIds,
            matches = { it.state is TunQueryState.WaitingResolution },
            name = "waiting resolution",
        )
        check(
            records.values.count { it.state is TunQueryState.WaitingResolution } ==
                waitingResolutionIds.size,
        ) {
            "every waiting resolution must be queued exactly once"
        }
        assertQueue(
            ids = writerIds,
            matches = {
                it.writerScheduled &&
                    (
                        it.state is TunQueryState.ReadyPending ||
                            it.state is TunQueryState.StagedOriginal
                        )
            },
            name = "writer",
        )
        check(
            records.values.count { it.writerScheduled } == writerIds.size,
        ) {
            "every scheduled writer must be queued exactly once"
        }
        assertQueue(
            ids = replacementPromotionIds,
            matches = {
                it.replacementScheduled &&
                    (
                        it.state === TunQueryState.AwaitingReplacementWriteCapacity ||
                            it.state === TunQueryState.ReplacementReady
                        )
            },
            name = "replacement promotion",
        )
        check(
            records.values.count { it.replacementScheduled } ==
                replacementPromotionIds.size,
        ) {
            "every scheduled replacement must be queued exactly once"
        }

        records.forEach { (id, record) ->
            check(id == record.id)
            check(record.sessionToken == sessionToken)
            check(record.engineEpoch.sessionGeneration == sessionToken)
            check(record.engineGeneration == record.engineEpoch.engineGeneration)
            if (
                record.state === TunQueryState.AwaitingReplacementWriteCapacity ||
                record.state === TunQueryState.ReplacementReady
            ) {
                check(record.hasReplacement())
            }
        }
    }

    private fun ownershipAccounting() = OwnershipAccounting(
        liveRecords = records.size,
        activeResolutions = activeResolutionCount(),
        waitingResolutions = waitingResolutionCount(),
        materializedWrites = materializedWriteCount(),
        replacementPromotions = replacementPromotionIds.size,
        waitingQueueEntries = waitingResolutionIds.size,
        writerQueueEntries = writerIds.size,
    )

    private fun assertQueue(
        ids: Collection<TunQueryId>,
        matches: (QueryRecord) -> Boolean,
        name: String,
    ) {
        check(ids.toSet().size == ids.size) { "$name queue contains a duplicate ID" }
        ids.forEach { id ->
            val record = checkNotNull(records[id]) { "$name queue contains a stale ID" }
            check(matches(record)) { "$name queue contains a mismatched state" }
        }
    }

    private fun <T> checked(block: () -> T): T {
        val result = block()
        assertInvariants()
        return result
    }

    private data class OwnershipAccounting(
        val liveRecords: Int,
        val activeResolutions: Int,
        val waitingResolutions: Int,
        val materializedWrites: Int,
        val replacementPromotions: Int,
        val waitingQueueEntries: Int,
        val writerQueueEntries: Int,
    ) {
        operator fun minus(record: RecordOwnership) = OwnershipAccounting(
            liveRecords = liveRecords - record.liveRecords,
            activeResolutions = activeResolutions - record.activeResolutions,
            waitingResolutions = waitingResolutions - record.waitingResolutions,
            materializedWrites = materializedWrites - record.materializedWrites,
            replacementPromotions =
                replacementPromotions - record.replacementPromotions,
            waitingQueueEntries = waitingQueueEntries - record.waitingQueueEntries,
            writerQueueEntries = writerQueueEntries - record.writerQueueEntries,
        )
    }

    private data class RecordOwnership(
        val liveRecords: Int,
        val activeResolutions: Int,
        val waitingResolutions: Int,
        val materializedWrites: Int,
        val replacementPromotions: Int,
        val waitingQueueEntries: Int,
        val writerQueueEntries: Int,
    ) {
        operator fun plus(other: RecordOwnership) = RecordOwnership(
            liveRecords = liveRecords + other.liveRecords,
            activeResolutions = activeResolutions + other.activeResolutions,
            waitingResolutions = waitingResolutions + other.waitingResolutions,
            materializedWrites = materializedWrites + other.materializedWrites,
            replacementPromotions =
                replacementPromotions + other.replacementPromotions,
            waitingQueueEntries = waitingQueueEntries + other.waitingQueueEntries,
            writerQueueEntries = writerQueueEntries + other.writerQueueEntries,
        )

        fun toAccounting() = OwnershipAccounting(
            liveRecords = liveRecords,
            activeResolutions = activeResolutions,
            waitingResolutions = waitingResolutions,
            materializedWrites = materializedWrites,
            replacementPromotions = replacementPromotions,
            waitingQueueEntries = waitingQueueEntries,
            writerQueueEntries = writerQueueEntries,
        )

        companion object {
            val None = RecordOwnership(0, 0, 0, 0, 0, 0, 0)
        }
    }

    private class QueryRecord(
        val id: TunQueryId,
        capturedPacket: ByteArray,
        val sessionToken: Long,
        val engineEpoch: EngineEpoch,
        val engineGeneration: Long,
        var state: TunQueryState,
    ) {
        private val packet = capturedPacket.copyOf()
        private var replacementPacket: ByteArray? = null
        var writerScheduled: Boolean = false
        var replacementScheduled: Boolean = false

        @Suppress("unused")
        fun capturedPacket(): ByteArray = packet.copyOf()

        fun installReplacement(response: ProxyReplacementResponse) {
            check(replacementPacket == null)
            replacementPacket = response.snapshot()
        }

        fun hasReplacement(): Boolean = replacementPacket != null

        fun replacementPacket(): ByteArray =
            checkNotNull(replacementPacket).copyOf()

        fun ownership() = RecordOwnership(
            liveRecords = 1,
            activeResolutions = when (val current = state) {
                is TunQueryState.ResolutionReady,
                is TunQueryState.Resolving,
                -> 1
                is TunQueryState.AwaitingOriginalWriteCapacity ->
                    if (current.retainsResolutionSlot) 1 else 0
                else -> 0
            },
            waitingResolutions =
                if (state is TunQueryState.WaitingResolution) 1 else 0,
            materializedWrites = if (
                state is TunQueryState.ReadyPending ||
                state is TunQueryState.StagedOriginal ||
                state is TunQueryState.WrittenOriginalAwaitingEvidence ||
                state === TunQueryState.ReplacementReady
            ) {
                1
            } else {
                0
            },
            replacementPromotions = if (replacementScheduled) 1 else 0,
            waitingQueueEntries =
                if (state is TunQueryState.WaitingResolution) 1 else 0,
            writerQueueEntries = if (writerScheduled) 1 else 0,
        )
    }
}
