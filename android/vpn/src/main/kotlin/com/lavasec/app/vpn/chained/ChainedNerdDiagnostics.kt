package com.lavasec.app.vpn.chained

/** Numeric-only engine diagnostics, read from the existing liveness sampler. */
data class ChainedNerdDiagnostics(
    val outages: Int,
    val dnsOutages: Int,
    val dataPathOutages: Int,
    val unansweredDestinations: Int,
    val longestUnansweredSeconds: Int,
    val sentLastMinute: Long?,
    val receivedLastMinute: Long?,
    /** A retained session is suspended or still needs authenticated post-wake evidence. */
    val rechecking: Boolean = false,
)

/** At most one bucket per monotonic second; engine replacement starts a new byte baseline. */
internal class ChainedDiagnosticWindow {
    private data class Bucket(val second: Int, var tx: Long, var rx: Long)
    private val buckets = ArrayDeque<Bucket>()
    private var generation: Long? = null
    private var priorTx = 0L
    private var priorRx = 0L
    private var lastSecond: Int? = null
    fun observe(now: Int, generation: Long, tx: Long?, rx: Long?) {
        if (tx == null || rx == null || tx < 0 || rx < 0 || lastSecond?.let { now < it } == true) return
        val deltaTx = if (this.generation == generation) (tx - priorTx).coerceAtLeast(0) else tx
        val deltaRx = if (this.generation == generation) (rx - priorRx).coerceAtLeast(0) else rx
        this.generation = generation; priorTx = tx; priorRx = rx; lastSecond = now
        if (buckets.lastOrNull()?.second == now) {
            buckets.last().tx += deltaTx; buckets.last().rx += deltaRx
        } else buckets.addLast(Bucket(now, deltaTx, deltaRx))
        trim(now)
    }
    fun totals(now: Int): Pair<Long, Long>? {
        if (generation == null) return null
        trim(now)
        return buckets.sumOf { it.tx } to buckets.sumOf { it.rx }
    }
    private fun trim(now: Int) { while (buckets.firstOrNull()?.let { now - it.second >= 60 } == true) buckets.removeFirst() }
}
