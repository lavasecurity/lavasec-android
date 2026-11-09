package com.lavasec.app.reactnative

import com.lavasec.app.foundation.chained.ChainedConfigurationIssue
import com.lavasec.app.platform.ChainedConfigurationCommandOwner
import com.lavasec.app.platform.ChainedConfigurationDraft
import com.lavasec.app.platform.ChainedUpstreamConfigurationRepository
import com.lavasec.app.platform.ChainedUpstreamIdentity
import com.lavasec.app.platform.ChainedUpstreamPendingRemovalResult
import com.lavasec.app.platform.VpnFirstConfigurationBegin
import java.util.IdentityHashMap
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Native-only parent editor custody. Activity/module teardown owns no read or write grant.
 * Its compensation removes only the exact pending import, in the surviving product scope.
 */
internal class NativeVpnDraftHostOwner(
    private val scope: CoroutineScope,
    private val cleanupDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private class Lane(val composition: Any) {
        val mutex = Mutex()
        var users = 0
        val visits = mutableListOf<Visit>()
        var cleanupToken: Any? = null
    }
    private class Visit(
        val module: Any,
        val host: NativeVisitHost,
        val generation: Long,
        val owner: ChainedConfigurationCommandOwner,
        val repository: ChainedUpstreamConfigurationRepository,
        val id: String,
        val graphCurrent: () -> Boolean,
        var pending: Boolean,
    ) {
        var configurationGeneration: Long? = null
        var identity: ChainedUpstreamIdentity? = null
        var abandoned = false
        var retirementPending = false
        var automaticRetirementRequested = false
        var retirementLease: String? = null
    }
    private val lock = Any()
    private val lanes = IdentityHashMap<Any, Lane>()
    private fun retainLane(composition: Any): Lane = synchronized(lock) {
        lanes.getOrPut(composition) { Lane(composition) }.also { it.users++ }
    }

    private fun pruneLaneLocked(lane: Lane) {
        if (lane.users == 0 && lane.cleanupToken == null && lane.visits.isEmpty() &&
            lanes[lane.composition] === lane) lanes.remove(lane.composition)
    }

    private fun releaseLane(lane: Lane) = synchronized(lock) {
        check(lane.users > 0)
        lane.users--
        pruneLaneLocked(lane)
    }

    private suspend fun <T> withLane(composition: Any, operation: suspend (Lane) -> T): T {
        val lane = retainLane(composition)
        return try {
            discardObsoleteVisits()
            lane.mutex.withLock { operation(lane) }
        } finally { releaseLane(lane) }
    }

    /** Obsolete graph capabilities carry no mutation authority. Observe their native graph
     * predicate outside the registry lock, then discard only that exact retained visit.
     * Active, queued and cleanup users keep their original lane even if it becomes empty.
     */
    private fun discardObsoleteVisits() {
        val observed = synchronized(lock) {
            lanes.values.flatMap { lane -> lane.visits.map { visit -> lane to visit } }
        }
        val obsolete = observed.filter { (_, visit) -> !visit.graphCurrent() }
        synchronized(lock) {
            obsolete.forEach { (lane, visit) ->
                if (lanes[lane.composition] === lane && lane.visits.remove(visit)) pruneLaneLocked(lane)
            }
        }
    }

    /** Begin, explicit Cancel/Commit, recovery and abandonment share this composition's lane.
     * A retired graph's suspended picker cannot block a different composition.
     */
    suspend fun <T> serialized(composition: Any, operation: suspend () -> T): T =
        withLane(composition) { operation() }

    suspend fun begin(
        module: Any,
        host: NativeVisitHost,
        generation: Long,
        composition: Any,
        owner: ChainedConfigurationCommandOwner,
        repository: ChainedUpstreamConfigurationRepository,
        id: String,
        expectedGeneration: Long,
        graphCurrent: () -> Boolean,
        hostCurrent: () -> Boolean,
        recover: suspend () -> Unit,
        operation: suspend () -> VpnFirstConfigurationBegin,
    ): VpnFirstConfigurationBegin {
        return withLane(composition) { lane ->
            // One explicit Begin retries each failed abandonment once. Never spin or erase a
            // failed durable marker, and never make an obsolete graph block a new composition.
            for (old in synchronized(lock) { lane.visits.filter { it.abandoned } }) {
                // This explicit retry also consumes any not-yet-started retirement attempt.
                synchronized(lock) { old.retirementPending = false }
                if (!cleanup(lane, old)) return@withLane VpnFirstConfigurationBegin.Unavailable
            }
            if (!graphCurrent() || !hostCurrent()) return@withLane VpnFirstConfigurationBegin.Unavailable
            recover()
            if (!graphCurrent() || !hostCurrent()) return@withLane VpnFirstConfigurationBegin.Unavailable
            val existing = synchronized(lock) { lane.visits.firstOrNull { it.pending && !it.abandoned } }
            if (existing != null && (existing.owner !== owner || existing.generation != generation ||
                    existing.id != id || !existing.host.sameHost(host))) {
                return@withLane VpnFirstConfigurationBegin.Unavailable
            }
            val visit = existing ?: Visit(module, host, generation, owner, repository, id,
                graphCurrent, pending = expectedGeneration == 0L).also {
                // Register before picker/storage suspension: destruction marks this capability
                // synchronously even while its lane is held by the real Begin operation.
                synchronized(lock) { lane.visits.add(it) }
            }
            var outcome: VpnFirstConfigurationBegin? = null
            try {
                outcome = operation()
            } finally {
                // The actual coordinator may already have published an import and its own
                // compensation may have failed. Caller/module cancellation cannot discard that
                // durable ownership fact before the VM's cleanup can retry it.
                withContext(NonCancellable) {
                    val draft = outcome as? VpnFirstConfigurationBegin.Draft
                    synchronized(lock) {
                        if (draft != null) {
                            visit.configurationGeneration = draft.draft.generation
                            visit.pending = visit.pending || draft.imported
                            if (!visit.pending) lane.visits.removeAll { it !== visit && !it.pending }
                        }
                        if ((draft == null || !hostCurrent() || !graphCurrent()) && !visit.automaticRetirementRequested) {
                            visit.abandoned = true; visit.retirementPending = true
                            visit.automaticRetirementRequested = true
                        }
                    }
                    try {
                        if (visit.pending) captureIdentity(visit)
                    } catch (cancelled: CancellationException) {
                        // A cancelled receipt read cannot deliver this editor, but may follow a
                        // real pending import. Preserve cancellation and its cleanup capability.
                        synchronized(lock) {
                            visit.abandoned = true; visit.retirementPending = true
                            visit.automaticRetirementRequested = true
                        }
                        throw cancelled
                    } finally {
                        if (visit.abandoned) schedule(lane)
                    }
                }
            }
            // Keep the durable cleanup fact, but a retired host receives no successful editor.
            if (!graphCurrent() || !hostCurrent() ||
                outcome is VpnFirstConfigurationBegin.Draft && visit.abandoned) VpnFirstConfigurationBegin.Unavailable
            else checkNotNull(outcome)
        }
    }

    fun hasImport(composition: Any): Boolean = synchronized(lock) {
        lanes[composition]?.visits?.any { it.pending } == true
    }

    fun ownsImport(host: NativeVisitHost, generation: Long, composition: Any,
        owner: ChainedConfigurationCommandOwner, id: String): Boolean = synchronized(lock) {
        lanes[composition]?.visits?.any { it.pending && !it.abandoned && it.owner === owner &&
            it.generation == generation && it.id == id && it.host.sameHost(host) } == true
    }

    /** Issued only from the actual successful Begin result, never from a later snapshot.
     * This native-only capability permits abandoning one route, not a storage mutation.
     */
    data class RouteRetirement(
        val id: String,
        val retirementGeneration: Long,
        val nativeHostID: String,
        val retirementLease: String,
    )

    fun captureRouteRetirement(module: Any, host: NativeVisitHost, generation: Long,
        composition: Any, owner: ChainedConfigurationCommandOwner,
        draft: ChainedConfigurationDraft): RouteRetirement? = synchronized(lock) {
        lanes[composition]?.visits?.firstOrNull { !it.abandoned && it.module === module &&
            it.owner === owner && it.generation == generation && it.id == draft.id && it.host.sameHost(host) }
    }?.let { visit ->
        // Native graph and editor callbacks stay outside the registry lock. The synchronous
        // Begin caller then rechecks this exact Visit before exposing its one opaque lease.
        if (!visit.graphCurrent() || owner.editorDraft(draft.id) != draft) return@let null
        synchronized(lock) {
            if (visit.abandoned || lanes[composition]?.visits?.any { it === visit } != true) null
            else RouteRetirement(visit.id, visit.generation, visit.host.nativeHostID,
                visit.retirementLease ?: UUID.randomUUID().toString().also { visit.retirementLease = it })
        }
    }

    /** Marker-only page retirement works even if its ordinary Cancel never reached native.
     * The native-issued lease owns that exact route; raw id/host/generation cannot borrow it.
     * No editor/storage read, Discard replay, publication or cleanup scheduling occurs here.
     */
    fun retireRoute(module: Any, nativeHostID: String, generation: Long,
        composition: Any, owner: ChainedConfigurationCommandOwner, id: String,
        retirementLease: String): Boolean = synchronized(lock) {
        lanes[composition]?.visits?.firstOrNull { it.module === module && it.owner === owner &&
            it.generation == generation && it.id == id && it.host.nativeHostID == nativeHostID &&
            it.retirementLease != null && it.retirementLease == retirementLease }
    }?.let { visit ->
        if (!visit.graphCurrent()) return@let false
        synchronized(lock) {
            if (lanes[composition]?.visits?.any { it === visit } != true ||
                visit.retirementLease != retirementLease) false
            else { visit.abandoned = true; true }
        }
    } ?: false

    /** A route-abandoned pending import still owns an unreadable explicit Cancel failure. */
    fun ownsPendingImport(module: Any, host: NativeVisitHost, generation: Long,
        composition: Any, owner: ChainedConfigurationCommandOwner, id: String): Boolean = synchronized(lock) {
        lanes[composition]?.visits?.any { it.pending && it.module === module && it.owner === owner &&
            it.generation == generation && it.id == id && it.host.sameHost(host) } == true
    }

    /** A durable Commit or successful explicit Cancel releases only its original capability. */
    fun release(host: NativeVisitHost, generation: Long, composition: Any,
        owner: ChainedConfigurationCommandOwner, id: String) = synchronized(lock) {
        val lane = lanes[composition] ?: return@synchronized
        lane.visits.removeAll { it.owner === owner && it.generation == generation &&
            it.id == id && it.host.sameHost(host) }
        pruneLaneLocked(lane)
        Unit
    }

    /** Durable publication ends import compensation even if a later settings write failed.
     * A rebased editor still needs ordinary host retirement, never deletion of the saved profile.
     */
    fun finishCommit(host: NativeVisitHost, generation: Long, composition: Any,
        owner: ChainedConfigurationCommandOwner, id: String) = synchronized(lock) {
        val lane = lanes[composition] ?: return@synchronized
        val visits = lane.visits
        val exact = visits.filter { it.owner === owner && it.generation == generation &&
            it.id == id && it.host.sameHost(host) }
        val rebased = owner.editorDraft(id)
        exact.forEach { visit ->
            if (rebased == null) visits.remove(visit)
            else { visit.pending = false; visit.identity = null; visit.configurationGeneration = rebased.generation }
        }
        pruneLaneLocked(lane)
    }

    fun retireHost(owner: Any, nativeHostID: String) = abandon {
        it.host.owner === owner && it.host.nativeHostID == nativeHostID
    }

    fun retireModule(module: Any) = abandon { it.module === module }

    private fun abandon(matches: (Visit) -> Boolean) {
        val affected = synchronized(lock) {
            lanes.values.filter { lane ->
                var changed = false
                lane.visits.forEach { if (matches(it) && !it.automaticRetirementRequested) {
                    it.abandoned = true; it.retirementPending = true
                    it.automaticRetirementRequested = true; changed = true
                } }
                changed
            }
        }
        affected.forEach(::schedule)
    }

    private fun schedule(lane: Lane) {
        val token = Any()
        if (!synchronized(lock) {
                if (lane.cleanupToken != null) false
                else { lane.cleanupToken = token; lane.users++; true }
            }) return
        val worker = scope.launch(cleanupDispatcher) {
            lane.mutex.withLock {
                    while (true) {
                        val pending = synchronized(lock) {
                            lane.visits.filter { it.retirementPending }.also { visits ->
                                visits.forEach { it.retirementPending = false }
                                if (visits.isEmpty() && lane.cleanupToken === token) lane.cleanupToken = null
                            }
                        }
                        if (pending.isEmpty()) break
                        // Drain newly abandoned capabilities only. A failed attempt stays
                        // abandoned with retirementPending=false until an explicit Begin retry.
                        for (visit in pending) cleanup(lane, visit)
                    }
            }
        }
        // Runs even if the surviving scope was cancelled before this worker could start.
        worker.invokeOnCompletion {
            // A queued Begin can already have installed a new worker at Mutex unlock.
            // This worker must never clear that successor's scheduling token.
            synchronized(lock) { if (lane.cleanupToken === token) lane.cleanupToken = null }
            releaseLane(lane)
        }
    }

    private suspend fun captureIdentity(visit: Visit) {
        if (visit.identity != null) return
        try {
            val stored = visit.repository.loadConfiguration()
            if (stored?.pendingImportId == visit.id &&
                (visit.configurationGeneration == null || stored.identity.generation == visit.configurationGeneration)) {
                synchronized(lock) { visit.identity = stored.identity; visit.configurationGeneration = stored.identity.generation }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Unknown storage retains the pending capability, never success. */ }
    }

    private suspend fun cleanup(lane: Lane, visit: Visit): Boolean {
        if (!synchronized(lock) { lane.visits.any { it === visit } }) return true
        if (!visit.graphCurrent()) {
            // Credential repositories fence obsolete owners. Leave durable tags for the current
            // graph's authoritative recovery; do not invoke an old owner's mutation.
            synchronized(lock) { lane.visits.remove(visit) }
            return true
        }
        if (!visit.pending) {
            visit.owner.cancelEditor(visit.id)
            synchronized(lock) { lane.visits.remove(visit) }
            return true
        }
        try {
            if (visit.identity == null) {
                val stored = visit.repository.loadConfiguration()
                if (stored == null) {
                    if (visit.repository.configurationIssue() != ChainedConfigurationIssue.MISSING_CONFIGURATION) return false
                    visit.owner.cancelEditor(visit.id)
                    synchronized(lock) { lane.visits.remove(visit) }
                    return true
                }
                if (stored.pendingImportId != visit.id ||
                    (visit.configurationGeneration != null && stored.identity.generation != visit.configurationGeneration)) {
                    visit.owner.cancelEditor(visit.id)
                    synchronized(lock) { lane.visits.remove(visit) }
                    return true
                }
                synchronized(lock) { visit.identity = stored.identity; visit.configurationGeneration = stored.identity.generation }
            }
            if (!visit.graphCurrent()) {
                synchronized(lock) { lane.visits.remove(visit) }
                return true
            }
            return when (visit.owner.cancelFirstImport(visit.id, checkNotNull(visit.identity))) {
                ChainedUpstreamPendingRemovalResult.Removed, ChainedUpstreamPendingRemovalResult.Superseded -> {
                    synchronized(lock) { lane.visits.remove(visit) }; true
                }
                ChainedUpstreamPendingRemovalResult.AuthoritativeReadFailed,
                is ChainedUpstreamPendingRemovalResult.Failed -> false
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return false }
    }
}
