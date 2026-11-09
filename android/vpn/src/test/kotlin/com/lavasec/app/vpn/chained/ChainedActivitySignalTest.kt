package com.lavasec.app.vpn.chained

import com.lavasec.app.foundation.chained.ChainedReconnectDecision
import com.lavasec.app.vpn.engine.LavaWgStats
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every publish must be observable, including a false→false DNS-only fallback: a bare Boolean
 * StateFlow would suppress it and a screen opened during the failed attempt would never learn the
 * runtime settled on DNS-only. The signal also carries the installed mode so a settled fallback is
 * distinguishable from an in-progress chained startup.
 */
class ChainedActivitySignalTest {

    @Test
    fun `same active mode publishes the exact newly installed rotation as one signal`() {
        val predecessor = ChainedActivitySignal(true, ChainedRuntimeMode.CHAINED, 4L, 7L)
        val replacement = nextChainedActivitySignal(predecessor, true, ChainedRuntimeMode.CHAINED, 3L)

        assertTrue(replacement.active)
        assertEquals(ChainedRuntimeMode.CHAINED, replacement.mode)
        assertEquals(3L, replacement.runningConfigurationGeneration)
        assertEquals(5L, replacement.sequence)
        assertNotEquals(predecessor, replacement)
    }

    @Test
    fun `recovering owner retains its immutable rotation and settled fallback or stop clears it`() {
        val established = ChainedActivitySignal(true, ChainedRuntimeMode.CHAINED, 4L, 7L)
        val recovering = nextChainedActivitySignal(established, false, ChainedRuntimeMode.CHAINED, 7L)
        assertFalse(recovering.active)
        assertEquals(7L, recovering.runningConfigurationGeneration)
        for (mode in listOf(ChainedRuntimeMode.DNS_ONLY, ChainedRuntimeMode.NONE)) {
            val cleared = nextChainedActivitySignal(recovering, false, mode, 7L)
            assertEquals(0L, cleared.runningConfigurationGeneration)
            assertEquals(6L, cleared.sequence)
        }
    }

    @Test
    fun `older publisher cannot inherit a preceding owner's generation`() {
        val known = ChainedActivitySignal(true, ChainedRuntimeMode.CHAINED, 4L, 7L)
        val unknown = nextChainedActivitySignal(known, true, ChainedRuntimeMode.CHAINED)
        assertEquals(0L, unknown.runningConfigurationGeneration)
    }

    @Test
    fun `republishing the same active value advances the sequence`() {
        val first = nextChainedActivitySignal(
            previous = ChainedActivitySignal(
                active = false,
                mode = ChainedRuntimeMode.NONE,
                sequence = 0L,
            ),
            active = false,
            mode = ChainedRuntimeMode.NONE,
        )
        val second = nextChainedActivitySignal(
            previous = first,
            active = false,
            mode = ChainedRuntimeMode.NONE,
        )

        assertNotEquals(first, second)
        assertFalse(second.active)
        assertEquals(2L, second.sequence)
    }

    @Test
    fun `an establish transition advances the sequence and flips active`() {
        val established = nextChainedActivitySignal(
            previous = ChainedActivitySignal(
                active = false,
                mode = ChainedRuntimeMode.CHAINED,
                sequence = 4L,
            ),
            active = true,
            mode = ChainedRuntimeMode.CHAINED,
        )

        assertEquals(5L, established.sequence)
        assertTrue(established.active)
        assertEquals(ChainedRuntimeMode.CHAINED, established.mode)
    }

    @Test
    fun `a DNS-only fallback publishes the settled mode with active false`() {
        val fellBack = nextChainedActivitySignal(
            previous = ChainedActivitySignal(
                active = false,
                mode = ChainedRuntimeMode.CHAINED,
                sequence = 4L,
            ),
            active = false,
            mode = ChainedRuntimeMode.DNS_ONLY,
        )

        assertFalse(fellBack.active)
        assertEquals(ChainedRuntimeMode.DNS_ONLY, fellBack.mode)
        assertEquals(5L, fellBack.sequence)
    }

    @Test
    fun `a claimed owner with a live DNS-only runtime does not clear the settled mode`() {
        // A REBUILD_DNS_ONLY action installed the DNS-only fallback; the post-claim publication
        // must not overwrite its DNS_ONLY mode with NONE, or the screen renders Recovering.
        assertFalse(
            shouldClearChainedActivityOnClaim(
                hasChainedOwner = false,
                hasDnsOnlyRuntime = true,
            ),
        )
    }

    @Test
    fun `a claimed owner with no live runtime clears the activity publication`() {
        assertTrue(
            shouldClearChainedActivityOnClaim(
                hasChainedOwner = false,
                hasDnsOnlyRuntime = false,
            ),
        )
    }

    @Test
    fun `a claimed owner replaced by a new owner does not clear the publication`() {
        // A reconnect installed a replacement owner; its own publication must stand.
        assertFalse(
            shouldClearChainedActivityOnClaim(
                hasChainedOwner = true,
                hasDnsOnlyRuntime = false,
            ),
        )
    }

    @Test
    fun `repeated established samples are deduplicated while transitions publish`() = runBlocking {
        // The 250 ms ticker assigns a fresh `Established(stats)` each sample. Publishing each one
        // bumped the sequence and made an open screen re-read status (a Keystore decrypt) every
        // tick; distinctUntilChanged must collapse them to one while still publishing the genuine
        // established→recovering and recovering→established transitions.
        val states = flowOf(
            ChainedEngineState.Starting,
            ChainedEngineState.Established(stats(txBytes = 1)),
            ChainedEngineState.Established(stats(txBytes = 2)),
            ChainedEngineState.Established(stats(txBytes = 3)),
            ChainedEngineState.Recovering(
                ChainedReconnectDecision.Retry(
                    afterSeconds = 1,
                    attempt = 1,
                    attemptDeadlineSeconds = 30,
                ),
            ),
            ChainedEngineState.Established(stats(txBytes = 4)),
        )

        val publications = states.chainedActivityPublications().toList()

        assertEquals(
            listOf(
                false to ChainedRuntimeMode.CHAINED,
                true to ChainedRuntimeMode.CHAINED,
                false to ChainedRuntimeMode.CHAINED,
                true to ChainedRuntimeMode.CHAINED,
            ),
            publications,
        )
    }

    private fun stats(txBytes: Long): LavaWgStats = LavaWgStats(
        timeSinceLastHandshakeMs = 0L,
        txBytes = txBytes,
        rxBytes = 0L,
        estimatedRoundTripMs = -1,
        estimatedLoss = 0f,
    )
}
