package com.lavasec.app.vpn.chained

import com.lavasec.app.foundation.chained.ChainedReconnectDecision
import com.lavasec.app.foundation.chained.ChainedSurrenderReason
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only an established engine proves the chained path is carrying traffic; a merely-installed owner
 * that is still handshaking or recovering must not be reported as "routed through the upstream".
 */
class ChainedEngineStateEstablishedTest {

    @Test
    fun `only Established reports the chained path as carrying traffic`() {
        assertTrue(ChainedEngineState.Established(stats = null).isEstablished)

        assertFalse(ChainedEngineState.Idle.isEstablished)
        assertFalse(ChainedEngineState.Starting.isEstablished)
        assertFalse(
            ChainedEngineState.Recovering(
                ChainedReconnectDecision.Retry(
                    afterSeconds = 1,
                    attempt = 1,
                    attemptDeadlineSeconds = 15,
                ),
            ).isEstablished,
        )
        assertFalse(
            ChainedEngineState.Surrendered(ChainedSurrenderReason.entries.first()).isEstablished,
        )
    }
}
