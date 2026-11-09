package com.lavasec.app.chained

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.lavasec.app.platform.ChainedAvailability.Ineligible
import com.lavasec.app.platform.ChainedAvailabilityAuthority
import com.lavasec.app.platform.ChainedAvailabilityRefusal
import com.lavasec.app.platform.ChainedDeviceEligibilityRead
import com.lavasec.app.platform.ChainedDeviceEligibilityUnavailableReason
import com.lavasec.app.platform.ChainedDeviceMemoryReading
import com.lavasec.app.platform.ChainedEntitlementPort
import com.lavasec.app.platform.ChainedMemoryKillScan
import com.lavasec.app.platform.ChainedMemoryKillScanResult
import com.lavasec.app.platform.ChainedProcessExit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidChainedDeviceEligibilityStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val healthyReading = ChainedDeviceMemoryReading(
        totalMemoryBytes = 4_000_000_000L,
        memoryClassMegabytes = 256,
        isLowRamDevice = false,
    )

    private fun store(
        memory: ChainedDeviceMemoryReading? = healthyReading,
        exitProbe: ChainedProcessExitProbe = { ChainedMemoryKillScanResult.Unsupported },
        apiLevel: Int = 36,
        nowMillis: () -> Long = { 1_000L },
    ): AndroidChainedDeviceEligibilityStore = AndroidChainedDeviceEligibilityStore(
        context = context,
        memoryProbe = { memory },
        exitProbe = exitProbe,
        apiLevel = apiLevel,
        nowMillis = nowMillis,
    )

    private fun emptyScan(): ChainedMemoryKillScanResult =
        ChainedMemoryKillScanResult.Scanned(ChainedMemoryKillScan(emptyList()))

    private fun lowMemoryScan(at: Long): ChainedMemoryKillScanResult =
        ChainedMemoryKillScanResult.Scanned(
            ChainedMemoryKillScan(listOf(ChainedProcessExit(at, isLowMemory = true))),
        )

    /** The authoritative verdict for [store], via the production wiring seam. */
    private fun verdictOf(store: AndroidChainedDeviceEligibilityStore, entitled: Boolean = true) =
        ChainedAvailabilityAuthority.production(
            entitlement = ChainedEntitlementPort { entitled },
            eligibilityStore = store,
        ).current()

    @Test
    fun `a recorded kill survives a new store instance`() {
        val first = store(nowMillis = { 111L })
        val recorded = first.recordMemoryKill(111L) as ChainedDeviceEligibilityRead.Available
        assertEquals(1, recorded.backoff.killStreak)
        assertEquals(111L, recorded.backoff.lastKillEpochMillis)

        val reread = store(nowMillis = { 222L }).read()
            as ChainedDeviceEligibilityRead.Available
        assertEquals(1, reread.backoff.killStreak)
        assertFalse(reread.backoff.excluded)
        assertEquals(111L, reread.backoff.lastKillEpochMillis)
    }

    @Test
    fun `three persisted kills exclude the device`() {
        val store = store()
        store.recordMemoryKill(1L)
        store.recordMemoryKill(2L)
        val read = store.recordMemoryKill(3L) as ChainedDeviceEligibilityRead.Available
        assertTrue(read.backoff.excluded)
        assertEquals(
            ChainedAvailabilityRefusal.MEMORY_KILL_EXCLUDED,
            (verdictOf(store) as Ineligible).reason,
        )
    }

    @Test
    fun `read reconciles in-session exit evidence once and advances the consumed baseline`() {
        val store = store(
            exitProbe = { after ->
                when {
                    after == null -> emptyScan()
                    650L > after -> lowMemoryScan(650L)
                    else -> emptyScan()
                }
            },
            nowMillis = { 700L },
        )

        // Start the session at 600, promising a kill only once the session is on record.
        val started = store.markChainedSessionStarted(600L)
            as ChainedDeviceEligibilityRead.Available
        assertEquals(0, started.backoff.killStreak)

        val first = store.read() as ChainedDeviceEligibilityRead.Available
        assertEquals(1, first.backoff.killStreak)

        val second = store.read() as ChainedDeviceEligibilityRead.Available
        assertEquals(1, second.backoff.killStreak)
        assertFalse(second.backoff.excluded)
    }

    @Test
    fun `only the first exit after the witnessed session counts`() {
        var armed = false
        val store = store(
            exitProbe = { after ->
                if (!armed) {
                    emptyScan()
                } else {
                    ChainedMemoryKillScanResult.Scanned(
                        ChainedMemoryKillScan(
                            listOf(500L, 600L, 700L)
                                .filter { after == null || it > after }
                                .map { ChainedProcessExit(it, isLowMemory = true) },
                        ),
                    )
                }
            },
            nowMillis = { 800L },
        )

        store.markChainedSessionStarted(400L)
        armed = true

        val read = store.read() as ChainedDeviceEligibilityRead.Available
        assertEquals(1, read.backoff.killStreak)
        assertFalse(read.backoff.excluded)
    }

    @Test
    fun `a background kill outside a chained session does not count`() {
        val store = store(
            exitProbe = { after ->
                if (after == null || 650L > after) lowMemoryScan(650L) else emptyScan()
            },
            nowMillis = { 700L },
        )

        val read = store.read() as ChainedDeviceEligibilityRead.Available
        assertEquals(0, read.backoff.killStreak)
        assertFalse(read.backoff.excluded)
    }

    @Test
    fun `a live read preserves the witness so a later kill is still counted`() {
        var armed = false
        val store = store(
            exitProbe = { after ->
                if (armed && (after == null || 500L > after)) lowMemoryScan(500L) else emptyScan()
            },
            nowMillis = { 600L },
        )

        store.markChainedSessionStarted(400L)
        // Readiness refresh while the session is still live: no exit has happened yet.
        val live = store.read() as ChainedDeviceEligibilityRead.Available
        assertEquals(0, live.backoff.killStreak)

        armed = true
        val killed = store.read() as ChainedDeviceEligibilityRead.Available
        assertEquals(1, killed.backoff.killStreak)
    }

    @Test
    fun `explicit evidence is retired and not double counted by a later read`() {
        var armed = false
        val store = store(
            exitProbe = { after ->
                if (armed && (after == null || 500L > after)) lowMemoryScan(500L) else emptyScan()
            },
            nowMillis = { 600L },
        )

        store.markChainedSessionStarted(400L)
        armed = true
        val recorded = store.recordMemoryKill(500L) as ChainedDeviceEligibilityRead.Available
        assertEquals(1, recorded.backoff.killStreak)

        // The OS still reports the 500 ms kill; the retired baseline must prevent a second strike.
        val reread = store.read() as ChainedDeviceEligibilityRead.Available
        assertEquals(1, reread.backoff.killStreak)
    }

    @Test
    fun `clear resets the exclusion and prevents retained history from re-excluding`() {
        var armed = false
        val store = store(
            exitProbe = { after ->
                if (armed && (after == null || 500L > after)) lowMemoryScan(500L) else emptyScan()
            },
            nowMillis = { 600L },
        )

        store.markChainedSessionStarted(400L)
        armed = true
        assertEquals(
            1,
            (store.read() as ChainedDeviceEligibilityRead.Available).backoff.killStreak,
        )

        val cleared = store.clearMemoryKillBackoff(700L) as ChainedDeviceEligibilityRead.Available
        assertFalse(cleared.backoff.excluded)
        assertEquals(0, cleared.backoff.killStreak)

        // The OS still retains the 500 ms kill, but the baseline advanced to 700 past it.
        val reread = store.read() as ChainedDeviceEligibilityRead.Available
        assertEquals(0, reread.backoff.killStreak)
        assertFalse(reread.backoff.excluded)
    }

    @Test
    fun `a clean stop resets the streak but keeps a tripped exclusion`() {
        val store = store()
        store.recordMemoryKill(1L)
        store.recordMemoryKill(2L)
        store.recordMemoryKill(3L)

        val stopped = store.markChainedSessionStoppedCleanly(10L)
            as ChainedDeviceEligibilityRead.Available
        assertEquals(0, stopped.backoff.killStreak)
        assertTrue(stopped.backoff.excluded)
    }

    @Test
    fun `an owning clean stop preserves a newer make-before-break witness`() {
        val store = store(nowMillis = { 300L })
        // Predecessor latches at 100; the candidate records a NEWER witness at 200 while it is
        // still alive. The predecessor's clean stop must retire only its own witness.
        store.markChainedSessionStarted(100L)
        store.markChainedSessionStarted(200L)
        assertEquals(200L, activeWitness())

        store.markChainedSessionStoppedCleanly(300L, 100L)
        assertEquals(200L, activeWitness())

        // The candidate's own clean stop (owning 200) clears it.
        store.markChainedSessionStoppedCleanly(400L, 200L)
        assertNull(activeWitness())
    }

    /** Reads the durable in-progress witness straight from the store's preferences file. */
    private fun activeWitness(): Long? = context
        .getSharedPreferences("lavasec_chained_eligibility", Context.MODE_PRIVATE)
        .getLong("active_chained_session_start_epoch_millis", Long.MIN_VALUE)
        .takeIf { it != Long.MIN_VALUE }

    @Test
    fun `an owning clean stop clears a witness the wall clock rebased`() {
        var now = 100_000L
        // An API 30+ empty scan drives the read through `reconcile` (the Unsupported path returns
        // the record unchanged and would never exercise the rebase).
        val store = store(exitProbe = { emptyScan() }, nowMillis = { now })
        // A wrong-clock session start records the start as both the correlation timestamp and the
        // ownership token.
        store.markChainedSessionStarted(100_000L)

        // Clock corrected: a read rebases the correlation witness to 500 while the ownership token
        // survives (and must be persisted, or the owning clean stop cannot match it).
        now = 500L
        store.read()

        // The owner clean-stops with the token it wrote; its marker must clear, not be orphaned.
        now = 600L
        store.markChainedSessionStoppedCleanly(600L, 100_000L)
        assertNull(activeWitness())
    }

    @Test
    fun `a read in flight cannot overwrite a clean stop committed meanwhile`() {
        val enteredScan = CountDownLatch(1)
        val releaseScan = CountDownLatch(1)
        var scanReturnsExit = false
        var blockScan = false
        val store = store(
            exitProbe = { after ->
                if (blockScan) {
                    enteredScan.countDown()
                    releaseScan.await(5, TimeUnit.SECONDS)
                }
                val exits = if (scanReturnsExit) {
                    listOf(ChainedProcessExit(150L, isLowMemory = true))
                        .filter { after == null || it.epochMillis > after }
                } else {
                    emptyList()
                }
                ChainedMemoryKillScanResult.Scanned(ChainedMemoryKillScan(exits))
            },
            nowMillis = { 200L },
        )

        store.markChainedSessionStarted(100L)
        scanReturnsExit = true
        blockScan = true

        val reader = thread { store.read() }
        assertTrue("reader did not reach the exit scan", enteredScan.await(5, TimeUnit.SECONDS))

        val cleaner = thread { store.markChainedSessionStoppedCleanly(300L) }
        // If the store did not serialize, the cleaner commits here and the reader overwrites it.
        Thread.sleep(100)
        releaseScan.countDown()
        reader.join(5_000)
        cleaner.join(5_000)

        val finalState = store(exitProbe = { ChainedMemoryKillScanResult.Unsupported }).read()
            as ChainedDeviceEligibilityRead.Available
        assertEquals(
            "the clean stop must not be overwritten by the stale read",
            0,
            finalState.backoff.killStreak,
        )
    }

    @Test
    fun `a preferences open that fails before unlock is retried after unlock`() {
        val lockedThenUnlocked = FlakyPreferencesContext(
            ApplicationProvider.getApplicationContext(),
        )
        val store = AndroidChainedDeviceEligibilityStore(
            context = lockedThenUnlocked,
            memoryProbe = { healthyReading },
            exitProbe = { ChainedMemoryKillScanResult.Unsupported },
            apiLevel = 36,
            nowMillis = { 1_000L },
        )

        // Pre-unlock: the credential-protected open throws, so this read fails closed.
        assertEquals(
            ChainedDeviceEligibilityRead.Unavailable(
                ChainedDeviceEligibilityUnavailableReason.ELIGIBILITY_STATE_UNREADABLE,
            ),
            store.read(),
        )

        // USER_UNLOCKED: the SAME retained store opens on the next transaction and is honored.
        val read = store.read() as ChainedDeviceEligibilityRead.Available
        assertEquals(0, read.backoff.killStreak)
    }

    @Test
    fun `a corrected-past clock rebases a future witness so the kill still counts`() {
        var now = 100_000L
        val store = store(
            exitProbe = { after ->
                ChainedMemoryKillScanResult.Scanned(
                    ChainedMemoryKillScan(
                        listOf(ChainedProcessExit(600L, isLowMemory = true))
                            .filter { after == null || it.epochMillis > after },
                    ),
                )
            },
            nowMillis = { now },
        )

        // Session start persisted under a wrong future clock.
        store.markChainedSessionStarted(100_000L)

        // Clock corrected: a read rebases both the baseline AND the witness to the corrected now.
        now = 500L
        store.read()

        // The process is killed at corrected 600; the retained exit must still strike.
        now = 700L
        val read = store.read() as ChainedDeviceEligibilityRead.Available
        assertEquals(1, read.backoff.killStreak)
    }

    @Test
    fun `a corrected-past clock does not discard the real exit before reconcile`() {
        // The probe models the "strictly newer than baseline" filter. A future stored baseline
        // would filter out the real exit BEFORE reconcile could rebase, so the store must query
        // the complete history when the mark is ahead of now.
        val store = store(
            exitProbe = { after ->
                ChainedMemoryKillScanResult.Scanned(
                    ChainedMemoryKillScan(
                        listOf(ChainedProcessExit(600L, isLowMemory = true))
                            .filter { after == null || it.epochMillis > after },
                    ),
                )
            },
            nowMillis = { 700L },
        )

        // A wrong-clock session start leaves a future baseline and witness.
        store.markChainedSessionStarted(100_000L)

        val read = store.read() as ChainedDeviceEligibilityRead.Available
        assertEquals(1, read.backoff.killStreak)
    }

    @Test
    fun `a corrected-past clock does not strand a future exit baseline`() {
        // The probe models ApplicationExitInfo's "strictly newer than the baseline" filter.
        val store = store(
            exitProbe = { after ->
                ChainedMemoryKillScanResult.Scanned(
                    ChainedMemoryKillScan(
                        listOf(ChainedProcessExit(600L, isLowMemory = true))
                            .filter { after == null || it.epochMillis > after },
                    ),
                )
            },
            nowMillis = { 700L },
        )

        // A wrong-clock session start leaves a future baseline.
        store.markChainedSessionStarted(100_000L)
        // The clock is corrected and a new session starts in the corrected past.
        store.markChainedSessionStarted(500L)

        // Without the rebase the probe would filter out the real exit and the streak would stay 0.
        val read = store.read() as ChainedDeviceEligibilityRead.Available
        assertEquals(1, read.backoff.killStreak)
    }

    @Test
    fun `two store instances over the same file share the transaction lock`() {
        val enteredScan = CountDownLatch(1)
        val releaseScan = CountDownLatch(1)
        var scanReturnsExit = false
        var blockScan = false
        val readerStore = store(
            exitProbe = { after ->
                if (blockScan) {
                    enteredScan.countDown()
                    releaseScan.await(5, TimeUnit.SECONDS)
                }
                val exits = if (scanReturnsExit) {
                    listOf(ChainedProcessExit(150L, isLowMemory = true))
                        .filter { after == null || it.epochMillis > after }
                } else {
                    emptyList()
                }
                ChainedMemoryKillScanResult.Scanned(ChainedMemoryKillScan(exits))
            },
            nowMillis = { 200L },
        )
        // A DISTINCT instance addressing the same preferences file, as the VPN-lifecycle owner
        // would hold. A per-instance lock would not coordinate these two.
        val cleanerStore = store(nowMillis = { 300L })

        readerStore.markChainedSessionStarted(100L)
        scanReturnsExit = true
        blockScan = true

        val reader = thread { readerStore.read() }
        assertTrue("reader did not reach the exit scan", enteredScan.await(5, TimeUnit.SECONDS))

        val cleaner = thread { cleanerStore.markChainedSessionStoppedCleanly(300L) }
        // If the lock were per instance, the cleaner commits here and the reader overwrites it.
        Thread.sleep(100)
        releaseScan.countDown()
        reader.join(5_000)
        cleaner.join(5_000)

        val finalState = store(exitProbe = { ChainedMemoryKillScanResult.Unsupported }).read()
            as ChainedDeviceEligibilityRead.Available
        assertEquals(
            "a second store instance must not overwrite the other instance's clean stop",
            0,
            finalState.backoff.killStreak,
        )
    }

    @Test
    fun `an API 30 exit-scan failure fails the read closed`() {
        val read = store(exitProbe = { ChainedMemoryKillScanResult.Failed }).read()
        assertEquals(
            ChainedDeviceEligibilityRead.Unavailable(
                ChainedDeviceEligibilityUnavailableReason.ELIGIBILITY_STATE_UNREADABLE,
            ),
            read,
        )
    }

    @Test
    fun `pre-API-30 has no exit evidence and defers to the policy fallback`() {
        val store = store(
            memory = healthyReading.copy(isLowRamDevice = true),
            exitProbe = { ChainedMemoryKillScanResult.Unsupported },
            apiLevel = 28,
        )
        val read = store.read() as ChainedDeviceEligibilityRead.Available

        assertEquals(0, read.backoff.killStreak)
        assertFalse(read.backoff.excluded)
        assertEquals(28, read.apiLevel)

        // The store honestly reports clean; the conservative decision belongs to the authority.
        assertEquals(
            ChainedAvailabilityRefusal.MEMORY_KILL_EXCLUDED,
            (verdictOf(store) as Ineligible).reason,
        )
    }

    @Test
    fun `an unreadable memory reading fails closed`() {
        val read = store(memory = null).read()
        assertEquals(
            ChainedDeviceEligibilityRead.Unavailable(
                ChainedDeviceEligibilityUnavailableReason.MEMORY_UNREADABLE,
            ),
            read,
        )
    }
}

/**
 * A context whose credential-protected `getSharedPreferences` throws until "unlock". Its
 * `applicationContext` is itself, so the store's application-context normalization still routes
 * the open through the failing method.
 */
private class FlakyPreferencesContext(base: Context) : ContextWrapper(base) {
    private var failuresRemaining = 1

    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        if (failuresRemaining > 0) {
            failuresRemaining--
            throw IllegalStateException("credential storage is locked")
        }
        return super.getSharedPreferences(name, mode)
    }
}
