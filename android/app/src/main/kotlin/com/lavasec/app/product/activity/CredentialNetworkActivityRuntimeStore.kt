package com.lavasec.app.product.activity

import com.lavasec.app.foundation.activity.NetworkActivityCapturedState
import com.lavasec.app.foundation.activity.NetworkActivityContract
import com.lavasec.app.foundation.activity.NetworkActivityEntry
import com.lavasec.app.foundation.activity.NetworkActivityEvent
import com.lavasec.app.product.PageToken
import com.lavasec.app.product.Revisioned
import com.lavasec.app.product.StableKey
import com.lavasec.app.product.StablePage
import com.lavasec.app.product.StableReadCut
import java.util.LinkedHashMap

/** Bounded credential-graph read surface backed by credential-only retained events in production. */
internal class CredentialNetworkActivityRuntimeStore(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val persistence: NetworkActivityPersistence? = null,
    retainExisting: Boolean = true,
) : CredentialNetworkActivityStore, AutoCloseable {
    private val lock = Any()
    private var closed = false
    private var loadFailed = false
    private var currentRevision = 0L
    private var nextRowId = 1L
    private var nextContinuationEpoch = 1L
    private var entries = emptyList<NetworkActivityEntry>()
    private val frozen = object : LinkedHashMap<Long, FrozenSnapshot>(
        MAX_FROZEN_READS + 1,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Long, FrozenSnapshot>,
        ): Boolean = size > MAX_FROZEN_READS
    }

    init {
        try {
            if (!retainExisting) persistence?.save(emptyList())
            val loaded = if (retainExisting) persistence?.load().orEmpty() else emptyList()
            val retained = NetworkActivityContract.normalize(loaded, checkedNow())
            if (loaded != retained) persistence?.save(retained)
            entries = retained
            nextRowId = Math.addExact(loaded.maxOfOrNull { it.id } ?: 0L, 1L)
        } catch (_: Exception) {
            // Broken Activity storage must not prevent protection from starting. Reads stay
            // explicitly unavailable until a successful clear or a later graph reconstruction.
            loadFailed = true
        }
    }

    override fun revision(): Long = synchronized(lock) {
        checkReadable()
        currentRevision
    }

    fun record(
        event: NetworkActivityEvent,
        capturedState: NetworkActivityCapturedState,
        occurredAtMillis: Long,
    ): Long = synchronized(lock) {
        checkReadable()
        require(occurredAtMillis >= 0L)
        val id = nextRowId
        nextRowId = Math.addExact(nextRowId, 1L)
        val now = checkedNow()
        val next = NetworkActivityContract.record(
            entries = entries,
            incoming = NetworkActivityEntry(
                id = id,
                latestOccurrenceEpochMillis = occurredAtMillis,
                event = event,
                capturedState = capturedState,
            ),
            nowEpochMillis = now,
        )
        if (next != entries) {
            persistence?.save(next)
            entries = next
            currentRevision = Math.addExact(currentRevision, 1L)
        }
        currentRevision
    }

    override fun loadPage(
        request: NetworkActivityPageRequest,
    ): Revisioned<StablePage<NetworkActivityRow>> = synchronized(lock) {
        checkReadable()
        val snapshot = request.token?.let(::reopenFrozenSnapshot) ?: freezeCurrentSnapshot()
        val before = request.token?.beforeExclusive
        val remaining = if (before == null) {
            snapshot.rows
        } else {
            snapshot.rows.filter { row -> row.isStrictlyBefore(before) }
        }
        val fetched = remaining.take(request.fetchLimit)
        val visible = fetched.take(request.pageSize)
        val next = if (fetched.size > request.pageSize) {
            val last = visible.last()
            PageToken(
                cut = snapshot.cut,
                beforeExclusive = StableKey(last.timestampMillis, last.id),
            )
        } else {
            null
        }
        if (next != null) frozen[snapshot.cut.continuationEpoch] = snapshot
        Revisioned(
            revision = snapshot.revision,
            value = StablePage(visible, next, snapshot.cut),
        )
    }

    /** Called under the shared export/privacy fence; a single bounded, pruned snapshot. */
    fun exportSnapshot(): ByteArray = synchronized(lock) {
        checkReadable()
        freezeCurrentSnapshot()
        NetworkActivityJson.encode(entries)
    }

    /** Explicit privacy clear. Even an already-empty clear advances the observable revision. */
    override fun clear(): Long = synchronized(lock) {
        check(!closed)
        persistence?.save(emptyList())
        loadFailed = false
        entries = emptyList()
        frozen.clear()
        currentRevision = Math.addExact(currentRevision, 1L)
        currentRevision
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            entries = emptyList()
            frozen.clear()
        }
    }

    internal fun sizeForTest(): Int = synchronized(lock) { entries.size }

    private fun freezeCurrentSnapshot(): FrozenSnapshot {
        val now = checkedNow()
        val normalized = NetworkActivityContract.normalize(entries, now)
        if (normalized != entries) {
            persistence?.save(normalized)
            entries = normalized
            currentRevision = Math.addExact(currentRevision, 1L)
        }
        val epoch = nextContinuationEpoch
        nextContinuationEpoch = Math.addExact(nextContinuationEpoch, 1L)
        val rows = entries.map(::NetworkActivityRow)
        return FrozenSnapshot(
            rows = rows,
            revision = currentRevision,
            cut = StableReadCut(
                maxRowId = rows.maxOfOrNull(NetworkActivityRow::id) ?: 0L,
                capturedAtMillis = now,
                continuationEpoch = epoch,
            ),
        )
    }

    private fun reopenFrozenSnapshot(token: PageToken): FrozenSnapshot {
        val before = requireNotNull(token.beforeExclusive) {
            "a continuation requires an exact row position"
        }
        val snapshot = frozen[token.cut.continuationEpoch]
            ?: throw IllegalArgumentException("Network Activity continuation is no longer live")
        require(snapshot.cut == token.cut) { "Network Activity continuation cut mismatch" }
        require(before.id <= token.cut.maxRowId)
        return snapshot
    }

    private fun NetworkActivityRow.isStrictlyBefore(key: StableKey): Boolean =
        timestampMillis < key.timestampMillis ||
            (timestampMillis == key.timestampMillis && id < key.id)

    private fun checkReadable() {
        check(!closed)
        if (loadFailed) throw java.io.IOException("Retained Network Activity is unavailable")
    }

    private fun checkedNow(): Long = nowMillis().also { require(it >= 0L) }

    private data class FrozenSnapshot(
        val rows: List<NetworkActivityRow>,
        val revision: Long,
        val cut: StableReadCut,
    )

    companion object {
        private const val MAX_FROZEN_READS = 8
    }
}
