package com.lavasec.app.vpn.chained

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lifecycle-publication gates: a failed endpoint preflight must not be retried in the no-tunnel
 * gap, and a missing preflight is only safe for an endpoint that needs no resolver.
 */
class ChainedLifecycleGatesTest {

    /**
     * A stand-in for the platform `Network`: equality is its `netId`, and every construction is a
     * distinct reference. Passing freshly built instances (rather than JVM-interned string
     * literals) is what makes the identity tests pin STRUCTURAL equality — a reference-equality
     * (`!==`) regression would report every same-network observation as a change and fail them,
     * even though production passes distinct `Network` instances whose equality is the netId.
     */
    private data class FakeNetwork(val netId: String)

    private fun net(netId: String): Any = FakeNetwork(netId)

    @Test
    fun `a refused preflight is not retried while a resolved one is admitted`() {
        assertFalse(
            chainedAttemptAdmittedAfterPreflight(
                ChainedPeerPreflightOutcome.REFUSED,
                resolutionRequired = true,
            ),
        )
        assertFalse(
            chainedAttemptAdmittedAfterPreflight(
                ChainedPeerPreflightOutcome.REFUSED,
                resolutionRequired = false,
            ),
        )
        assertTrue(
            chainedAttemptAdmittedAfterPreflight(
                ChainedPeerPreflightOutcome.RESOLVED,
                resolutionRequired = true,
            ),
        )
        assertTrue(
            chainedAttemptAdmittedAfterPreflight(
                ChainedPeerPreflightOutcome.RESOLVED,
                resolutionRequired = false,
            ),
        )
    }

    @Test
    fun `a missing preflight is admitted only when the endpoint needs no resolver`() {
        // An IP literal is resolved locally by ChainedEndpointResolver, so a missing preflight
        // cannot touch the underlay.
        assertTrue(
            chainedAttemptAdmittedAfterPreflight(
                ChainedPeerPreflightOutcome.NOT_ATTEMPTED,
                resolutionRequired = false,
            ),
        )
        // A hostname would re-run the underlay lookup after the predecessor is retired, leaving the
        // device with no TUN: fall back to DNS-only instead.
        assertFalse(
            chainedAttemptAdmittedAfterPreflight(
                ChainedPeerPreflightOutcome.NOT_ATTEMPTED,
                resolutionRequired = true,
            ),
        )
    }

    @Test
    fun `a preflight that needed a network re-enters awaiting recovery when one appears`() {
        // No eligible network at start: always await.
        assertTrue(
            chainedStartRequiresAwaitingNetwork(
                eligibleNetworkAvailable = false,
                preflightNetworkUnavailable = false,
            ),
        )
        assertTrue(
            chainedStartRequiresAwaitingNetwork(
                eligibleNetworkAvailable = false,
                preflightNetworkUnavailable = true,
            ),
        )
        // The network appeared after a preflight that could not select one: the start must re-enter
        // recovery so the preflight is re-run against it, rather than committing DNS-only forever.
        assertTrue(
            chainedStartRequiresAwaitingNetwork(
                eligibleNetworkAvailable = true,
                preflightNetworkUnavailable = true,
            ),
        )
        // A network is available and the preflight did not need one: commit normally.
        assertFalse(
            chainedStartRequiresAwaitingNetwork(
                eligibleNetworkAvailable = true,
                preflightNetworkUnavailable = false,
            ),
        )
    }

    @Test
    fun `a vanished underlay discards the preflight snapshot instead of reusing it`() {
        // The freshly selected network is null: the probe network is no longer eligible, so its
        // resolved address must not be reused. The caller must enter the awaiting-network/reselect
        // path rather than stashing a pair that would bind a tunnel to a dead underlay.
        assertEquals(
            ChainedEndpointPreflightResolution.UNDERLAY_LOST,
            chainedEndpointPreflightResolution(
                networkAvailable = false,
                sameNetwork = false,
                retries = 0,
                maxRetries = 2,
            ),
        )
    }

    @Test
    fun `the preflight loop resolves a stable snapshot and re-resolves a changed one`() {
        assertEquals(
            ChainedEndpointPreflightResolution.RESOLVED,
            chainedEndpointPreflightResolution(
                networkAvailable = true,
                sameNetwork = true,
                retries = 0,
                maxRetries = 2,
            ),
        )
        // A changed underlay within the retry budget re-resolves on the fresh snapshot so the
        // stashed network/address pair stays internally consistent.
        assertEquals(
            ChainedEndpointPreflightResolution.RE_RESOLVE,
            chainedEndpointPreflightResolution(
                networkAvailable = true,
                sameNetwork = false,
                retries = 0,
                maxRetries = 2,
            ),
        )
        // An exhausted budget must NOT accept the probe: the underlay changed on every observation,
        // so the probe address belongs to an obsolete underlay. Discard it so the caller falls back
        // to DNS-only instead of handing start a stale address.
        assertEquals(
            ChainedEndpointPreflightResolution.UNDERLAY_LOST,
            chainedEndpointPreflightResolution(
                networkAvailable = true,
                sameNetwork = false,
                retries = 2,
                maxRetries = 2,
            ),
        )
    }

    @Test
    fun `only an actual interface replacement reports a default-network identity change`() {
        val identity = ChainedDefaultNetworkIdentity()
        // The registration's initial snapshot is the network the chained owner already established
        // on, so it is NOT a change; same-network capability updates are not either. Reporting one
        // would rebind before the first handshake and shut the fresh session into the ladder.
        assertFalse(identity.observed(net("wifi-net")))
        assertFalse(identity.observed(net("wifi-net")))
        // A real interface replacement is a change.
        assertTrue(identity.observed(net("cell-net")))
        assertFalse(identity.observed(net("cell-net")))
        // An absence is not itself a change and does not forget the last identity, so regaining
        // the SAME interface is the offline latch's recovery, not an identity change.
        assertFalse(identity.observed(null))
        assertFalse(identity.observed(net("cell-net")))
        // A different interface after the absence is a change.
        assertTrue(identity.observed(net("wifi-net")))
    }

    @Test
    fun `an unseeded tracker treats its first snapshot as the baseline`() {
        // Without an establishment underlay the tracker has no baseline to compare against, so the
        // callback's first snapshot is the baseline exactly as before (finding 4021623299 adds the
        // seed; it does not remove the empty-tracker behaviour the caller may still rely on).
        val identity = ChainedDefaultNetworkIdentity()
        assertFalse(identity.observed(net("wifi-net")))
        assertTrue(identity.observed(net("cell-net")))
    }

    @Test
    fun `a tracker seeded with the establishment underlay treats that underlay as the baseline`() {
        val identity = ChainedDefaultNetworkIdentity(initial = net("wifi-net"))
        // The callback's first snapshot is the establishment underlay: not a change.
        assertFalse(identity.observed(net("wifi-net")))
        assertFalse(identity.observed(net("wifi-net")))
        // A real replacement still reports.
        assertTrue(identity.observed(net("cell-net")))
    }

    @Test
    fun `a tracker seeded with the establishment underlay reports a change ahead of its first snapshot`() {
        // The default network changed between the socket bind and the callback's first delivery, so
        // the first observed snapshot is the NEW underlay. Seeded with the establishment underlay it
        // must report the change (and thus rebind) instead of treating the new underlay as baseline
        // and leaving the socket pinned to the stale network.
        val identity = ChainedDefaultNetworkIdentity(initial = net("wifi-net"))
        assertTrue(identity.observed(net("cell-net")))
        assertFalse(identity.observed(net("cell-net")))
    }

    @Test
    fun `a tracker reports only the tracked identity as current so a stale onLost is ignored`() {
        // A handoff can deliver the replacement's satisfied callbacks BEFORE the old default's
        // `onLost`. The tracker holds the replacement, so the stale loss of the previous identity
        // must read as not-current: reporting it would set the offline latch and retire a live
        // session whose replacement underlay is fine.
        val identity = ChainedDefaultNetworkIdentity(initial = net("wifi-net"))
        assertTrue(identity.isCurrent(net("wifi-net")))
        identity.observed(net("cell-net"))
        assertTrue(identity.isCurrent(net("cell-net")))
        assertFalse(identity.isCurrent(net("wifi-net")))
        // A null identity is never the current one.
        assertFalse(identity.isCurrent(null))
    }

    @Test
    fun `overlapping recoveries do not interleave a preflight with another attempt's commit`() =
        runBlocking {
            val serializer = ChainedRecoverySerializer()
            val events = mutableListOf<String>()
            val firstPreflighted = CompletableDeferred<Unit>()
            val releaseFirstCommit = CompletableDeferred<Unit>()
            val first = launch {
                serializer.run {
                    events += "preflight:1"
                    firstPreflighted.complete(Unit)
                    releaseFirstCommit.await()
                    events += "commit:1"
                }
            }
            firstPreflighted.await()

            val second = launch {
                serializer.run {
                    events += "preflight:2"
                    events += "commit:2"
                }
            }
            // Let the second recovery reach the gate. It must suspend until the first attempt has
            // committed, so its preflight cannot overwrite the first attempt's stashed artifacts and
            // the first commit cannot consume the second's.
            yield()
            assertEquals(listOf("preflight:1"), events)

            releaseFirstCommit.complete(Unit)
            first.join()
            second.join()
            assertEquals(
                listOf("preflight:1", "commit:1", "preflight:2", "commit:2"),
                events,
            )
        }
}
