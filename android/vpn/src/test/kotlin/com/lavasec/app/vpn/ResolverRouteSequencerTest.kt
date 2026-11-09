package com.lavasec.app.vpn

import android.net.Network
import com.lavasec.app.foundation.dns.DnsHealthEvent
import com.lavasec.app.foundation.dns.DnsMessage
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.dns.ResolverTransport
import com.lavasec.app.foundation.health.PrimaryResolverIdentity
import com.lavasec.app.foundation.health.PrimaryResolverOutcome
import com.lavasec.app.foundation.health.ProtectionFallback
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.foundation.health.ResolverQueryHealthEvidence
import com.lavasec.app.foundation.health.DnsFallbackDisposition
import com.lavasec.app.foundation.health.DnsFallbackObservation
import com.lavasec.app.foundation.health.DnsFallbackReason
import com.lavasec.app.foundation.health.DnsFallbackResponseClass
import com.lavasec.app.foundation.health.ResolverTierDiagnosticOutcome
import com.lavasec.app.platform.ConfigurationIdentity
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ResolverRouteSequencerTest {
    @Test
    fun `primary only probe cannot conceal Device rejection with qualified encrypted rescue`() = runBlocking {
        val plan = orderedDevicePlan()
        val exactEpoch = request(plan).epoch
        val health = ExactSessionHealthOwner(1, { 1_000L }, {}, {})
        health.installEngine(VpnHealthEngineIdentity(1, plan.networkGeneration, 1,
            plan.primaryResolverIdentity, plan.fallbackCoverage), exactEpoch)
        repeat(3) { health.recordResolverQuery(1,
            ResolverQueryHealthEvidence.primaryFailed(it + 1L, ResolverFailureReason.TIMEOUT)) }
        val device = RecordingDeviceRoute { request, _ -> answer(request, ResolverTransport.DEVICE_UDP, 5) }
        val encrypted = RecordingResolver { request -> answer(request, ResolverTransport.DOT) }
        val route = sequencer(plan, device, orderedEncrypted(plan, encrypted),
            wedge = DeviceResolverWedgeEvidence(health::deviceResolverReconnectEpisodeIsActive))
        assertTrue(health.deviceResolverReconnectEpisodeIsActive(exactEpoch))
        val primary = assertAnswer(route.resolvePrimary(request(plan, queryId = 10)).result, ResolverTransport.DEVICE_UDP)
        assertEquals(5, primary.dnsMessage[3].toInt() and 15)
        assertTrue(encrypted.requests.isEmpty())
        assertAnswer(route.resolve(request(plan, queryId = 11)).result, ResolverTransport.DOT)
        assertEquals(1, encrypted.requests.size)
    }

    @Test
    fun `ordered Device rejection uses real owner episode then primary recovery restores authority`() = runBlocking {
        for (rejection in listOf(2, 5)) {
            val plan = orderedDevicePlan()
            val exactEpoch = request(plan).epoch
            val reconnects = mutableListOf<Long>()
            val health = ExactSessionHealthOwner(1, { 1_000L }, {}, reconnects::add)
            health.installEngine(VpnHealthEngineIdentity(1, plan.networkGeneration, 1,
                plan.primaryResolverIdentity, plan.fallbackCoverage), exactEpoch)
            var deviceRcode = rejection
            val device = RecordingDeviceRoute { routeRequest, _ -> answer(routeRequest, ResolverTransport.DEVICE_UDP, deviceRcode) }
            val encrypted = RecordingResolver { routeRequest -> answer(routeRequest, ResolverTransport.DOT) }
            val route = sequencer(plan, device, orderedEncrypted(plan, encrypted),
                wedge = DeviceResolverWedgeEvidence(health::deviceResolverReconnectEpisodeIsActive))

            val cold = route.resolve(request(plan, queryId = 10))
            assertEquals(rejection, assertAnswer(cold.result, ResolverTransport.DEVICE_UDP).dnsMessage[3].toInt() and 15)
            assertTrue(encrypted.requests.isEmpty())
            repeat(3) { health.recordResolverQuery(1,
                ResolverQueryHealthEvidence.primaryFailed(it + 20L, ResolverFailureReason.RECEIVE_FAILED)) }
            assertEquals(listOf(1L), reconnects)
            val carried = assertAnswer(route.resolve(request(plan, queryId = 100)).result, ResolverTransport.DOT)
            assertEquals(ResolverQueryHealthEvidence.primaryRejectedWithFallback(100, ProtectionFallback.ENCRYPTED_DNS),
                carried.resolverQueryEvidence?.healthOnly())
            assertFallback(carried.resolverQueryEvidence, DnsFallbackReason.RESOLVER_OUTAGE, DnsFallbackDisposition.SERVED)
            assertTrue(carried.healthEvents.none { it is DnsHealthEvent.ResolverAttemptFailed && it.resolver == ResolverKind.DEVICE })
            health.recordResolverQuery(1, checkNotNull(carried.resolverQueryEvidence))
            assertTrue(health.deviceResolverReconnectEpisodeIsActive(exactEpoch))

            deviceRcode = 0 // A real Device NODATA answer is authoritative, including during a wedge.
            val recovered = assertAnswer(route.resolve(request(plan, queryId = 101)).result, ResolverTransport.DEVICE_UDP)
            health.recordResolverQuery(1, checkNotNull(recovered.resolverQueryEvidence))
            assertFalse(health.deviceResolverReconnectEpisodeIsActive(exactEpoch))
            deviceRcode = rejection
            assertAnswer(route.resolve(request(plan, queryId = 102)).result, ResolverTransport.DEVICE_UDP)
            assertEquals(1, encrypted.requests.size)
        }
    }

    @Test
    fun `ordered encrypted rejection never consults Device episode and wedged Device NXDOMAIN stays authoritative`() = runBlocking {
        for (encryptedFirst in listOf(false, true)) {
            for (rcode in if (encryptedFirst) listOf(2, 5) else listOf(0, 3)) {
                val plan = orderedDevicePlan(encryptedFirst)
                val health = ExactSessionHealthOwner(1, { 1_000L }, {}, {})
                val epoch = request(plan).epoch
                health.installEngine(VpnHealthEngineIdentity(1, plan.networkGeneration, 1,
                    plan.primaryResolverIdentity, plan.fallbackCoverage), epoch.takeUnless { encryptedFirst })
                repeat(3) { health.recordResolverQuery(1, ResolverQueryHealthEvidence.primaryFailed(it + 1L, ResolverFailureReason.TIMEOUT)) }
                val device = RecordingDeviceRoute { routeRequest, _ -> answer(routeRequest, ResolverTransport.DEVICE_UDP, rcode) }
                val encrypted = RecordingResolver { routeRequest -> answer(routeRequest, ResolverTransport.MULLVAD_DOH, rcode) }
                val result = sequencer(plan, device, orderedEncrypted(plan, encrypted),
                    wedge = DeviceResolverWedgeEvidence { admitted ->
                        if (encryptedFirst) fail("an encrypted rejection must never consult a Device wedge")
                        health.deviceResolverReconnectEpisodeIsActive(admitted)
                    }).resolve(request(plan))
                val terminal = assertAnswer(result.result, if (encryptedFirst) ResolverTransport.MULLVAD_DOH else ResolverTransport.DEVICE_UDP)
                assertEquals(rcode, terminal.dnsMessage[3].toInt() and 15)
                assertEquals(if (encryptedFirst) 0 else 1, device.requests.size)
                assertEquals(if (encryptedFirst) 1 else 0, encrypted.requests.size)
            }
        }
    }

    @Test
    fun `qualified rejection followed by encrypted failure preserves first error and backoff in both route implementations`() = runBlocking {
        for (ordered in listOf(false, true)) {
            val plan = if (ordered) orderedDevicePlan() else devicePlan(mullvadEnabled = true)
            val epoch = request(plan).epoch
            val health = ExactSessionHealthOwner(1, { 1_000L }, {}, {})
            health.installEngine(VpnHealthEngineIdentity(1, plan.networkGeneration, 1,
                plan.primaryResolverIdentity, plan.fallbackCoverage), epoch)
            repeat(3) { health.recordResolverQuery(1, ResolverQueryHealthEvidence.primaryFailed(it + 1L, ResolverFailureReason.TIMEOUT)) }
            val device = RecordingDeviceRoute { routeRequest, _ -> answer(routeRequest, ResolverTransport.DEVICE_UDP, 5) }
            val encrypted = RecordingResolver { ResolverResult.Failure(ResolverFailureReason.TLS_FAILED) }
            val route = sequencer(plan, device, if (ordered) orderedEncrypted(plan, encrypted) else encrypted,
                wedge = DeviceResolverWedgeEvidence(health::deviceResolverReconnectEpisodeIsActive))
            for (id in listOf(10L, 11L)) {
                val request = request(plan, queryId = id)
                val candidate = route.resolve(request)
                val result = assertAnswer(candidate.result, ResolverTransport.DEVICE_UDP)
                assertArrayEquals(response(request.query, 5, 0), result.dnsMessage)
                assertEquals(ResolverKind.DEVICE, candidate.acceptedResolverKind)
                val evidence = checkNotNull(result.resolverQueryEvidence)
                assertEquals(ProtectionFallback.NONE, evidence.successfulFallback)
                assertEquals(listOf(1, 2), evidence.tierObservations.map { it.tier })
                assertEquals(listOf(ResolverTierDiagnosticOutcome.ANSWERED,
                    if (id == 10L) ResolverTierDiagnosticOutcome.FAILED else ResolverTierDiagnosticOutcome.NOT_ATTEMPTED),
                    evidence.tierObservations.map { it.outcome })
                assertEquals(if (id == 10L) ResolverFailureReason.TLS_FAILED else ResolverFailureReason.BACKED_OFF,
                    evidence.tierObservations.last().failure)
                assertEquals(DnsFallbackResponseClass.DNS_ERROR, evidence.tierObservations.first().responseClass)
                assertEquals(5, evidence.tierObservations.first().responseCode)
                assertFallback(evidence, DnsFallbackReason.RESOLVER_OUTAGE, DnsFallbackDisposition.ATTEMPTED)
            }
            assertEquals(1, encrypted.requests.size)
        }
    }
    @Test
    fun `Device route is invoked once with the complete selected address snapshot`() = runBlocking {
        val addresses = listOf(ip("192.0.2.53"), ip("2001:db8::53"), ip("192.0.2.54"))
        val plan = devicePlan(addresses, mullvadEnabled = false)
        val request = request(plan)
        val device = RecordingDeviceRoute { routeRequest, _ ->
            answer(routeRequest, ResolverTransport.DEVICE_UDP)
        }

        val candidate = sequencer(plan, device = device).resolve(request)

        assertAnswer(candidate.result, ResolverTransport.DEVICE_UDP)
        assertEquals(1, device.requests.size)
        assertEquals(addresses, device.requests.single().networkContext.dnsServers)
    }

    @Test
    fun `invalid query provenance calls no route and neither sets nor clears endpoint keys`() = runBlocking {
        val address = ip("192.0.2.53")
        val plan = devicePlan(listOf(address), mullvadEnabled = true)
        val clock = FakeClock(0L)
        val primedPolicy = ResolverEndpointBackoffPolicy(clock)
        primedPolicy.recordFailure(
            ResolverEndpointKey.DeviceAddress(address),
            ResolverFailureReason.TIMEOUT,
        )
        primedPolicy.recordFailure(
            ResolverEndpointKey.MullvadDoh,
            ResolverFailureReason.TIMEOUT,
        )
        val device = RecordingDeviceRoute { routeRequest, _ ->
            answer(routeRequest, ResolverTransport.DEVICE_UDP)
        }
        val mullvad = RecordingResolver { routeRequest ->
            answer(routeRequest, ResolverTransport.MULLVAD_DOH)
        }
        val invalid = request(
            plan,
            queryBytes = query(id = 0x4321),
            parsedQuery = query(id = 0x1234),
        )

        val primedCandidate = sequencer(
            plan,
            device = device,
            mullvad = mullvad,
            policy = primedPolicy,
        ).resolve(invalid)

        assertFailure(primedCandidate.result, ResolverFailureReason.INVALID_RESPONSE, resolver = null)
        assertTrue(primedPolicy.isBackedOff(ResolverEndpointKey.DeviceAddress(address)))
        assertTrue(primedPolicy.isBackedOff(ResolverEndpointKey.MullvadDoh))
        assertTrue(device.requests.isEmpty())
        assertTrue(mullvad.requests.isEmpty())

        val freshPolicy = ResolverEndpointBackoffPolicy(clock)
        sequencer(
            plan,
            device = device,
            mullvad = mullvad,
            policy = freshPolicy,
        ).resolve(invalid)

        assertFalse(freshPolicy.isBackedOff(ResolverEndpointKey.DeviceAddress(address)))
        assertFalse(freshPolicy.isBackedOff(ResolverEndpointKey.MullvadDoh))
        assertTrue(device.requests.isEmpty())
        assertTrue(mullvad.requests.isEmpty())
    }

    @Test
    fun `valid positive negative and unqualified error answers stay authoritative`() =
        runBlocking {
            val cases = buildList {
                add(RcodeCase("NOERROR answer", rcode = 0, answerCount = 1))
                add(RcodeCase("NOERROR NODATA", rcode = 0, answerCount = 0))
                add(RcodeCase("NXDOMAIN", rcode = 3, answerCount = 0))
                (0..15)
                    .filterNot { it == 0 || it == 2 || it == 3 || it == 5 }
                    .forEach { add(RcodeCase("RCODE $it", rcode = it, answerCount = 0)) }
            }

            cases.forEachIndexed { index, case ->
                val plan = devicePlan(mullvadEnabled = true)
                val request = request(plan, queryId = index.toLong())
                var wedgeReads = 0
                val device = RecordingDeviceRoute { routeRequest, _ ->
                    answer(
                        routeRequest,
                        ResolverTransport.DEVICE_UDP,
                        rcode = case.rcode,
                        answerCount = case.answerCount,
                    )
                }
                val mullvad = RecordingResolver {
                    fail("${case.label} must not invoke Mullvad")
                    ResolverResult.Failure(ResolverFailureReason.RECEIVE_FAILED)
                }

                val candidate = sequencer(
                    plan,
                    device = device,
                    mullvad = mullvad,
                    wedge = DeviceResolverWedgeEvidence {
                        wedgeReads += 1
                        case.rcode == 0 || case.rcode == 3
                    },
                ).resolve(request)

                val terminal = assertAnswer(candidate.result, ResolverTransport.DEVICE_UDP)
                assertEquals(case.label, case.rcode, terminal.dnsMessage[3].toInt() and 0x0f)
                assertEquals(case.label, 0, mullvad.requests.size)
                assertEquals(case.label, if (case.rcode == 0 || case.rcode == 3) 0 else 1, wedgeReads)
                assertEquals(
                    case.label,
                    ResolverQueryHealthEvidence.primaryAccepted(request.queryId, latencyMillis = 0L),
                    terminal.resolverQueryEvidence?.healthOnly(),
                )
                assertTrue(checkNotNull(terminal.resolverQueryEvidence).fallbackObservations.isEmpty())
            }
        }

    @Test
    fun `healthy QR SERVFAIL and REFUSED remain authoritative`() = runBlocking {
        listOf(2, 5).forEach { rcode ->
            val plan = devicePlan(mullvadEnabled = true)
            val request = request(plan, queryId = rcode.toLong())
            val observedEpochs = mutableListOf<EngineEpoch>()
            val device = RecordingDeviceRoute { routeRequest, _ ->
                answer(routeRequest, ResolverTransport.DEVICE_UDP, rcode = rcode)
            }
            val mullvad = RecordingResolver {
                fail("healthy RCODE $rcode must not invoke Mullvad")
                ResolverResult.Failure(ResolverFailureReason.RECEIVE_FAILED)
            }

            val candidate = sequencer(
                plan,
                device = device,
                mullvad = mullvad,
                wedge = DeviceResolverWedgeEvidence { epoch ->
                    observedEpochs += epoch
                    false
                },
            ).resolve(request)

            val terminal = assertAnswer(candidate.result, ResolverTransport.DEVICE_UDP)
            assertEquals(rcode, terminal.dnsMessage[3].toInt() and 0x0f)
            assertEquals(listOf(request.epoch), observedEpochs)
            assertTrue(mullvad.requests.isEmpty())
        }
    }

    @Test
    fun `fresh exact-epoch wedge evidence permits QR SERVFAIL and REFUSED fallback`() =
        runBlocking {
            listOf(2, 5).forEach { rcode ->
                val plan = devicePlan(mullvadEnabled = true)
                val request = request(plan, queryId = rcode.toLong())
                var reconnectEpisodeActive = false
                val observedEpochs = mutableListOf<EngineEpoch>()
                val device = RecordingDeviceRoute { routeRequest, _ ->
                    answer(routeRequest, ResolverTransport.DEVICE_TCP, rcode = rcode)
                }
                val mullvad = RecordingResolver { routeRequest ->
                    answer(routeRequest, ResolverTransport.MULLVAD_DOH)
                }
                val routeSequencer = sequencer(
                    plan,
                    device = device,
                    mullvad = mullvad,
                    wedge = DeviceResolverWedgeEvidence { epoch ->
                        observedEpochs += epoch
                        reconnectEpisodeActive
                    },
                )

                val healthy = routeSequencer.resolve(request)
                reconnectEpisodeActive = true
                val wedged = routeSequencer.resolve(request(plan, queryId = 100L + rcode))

                assertAnswer(healthy.result, ResolverTransport.DEVICE_TCP)
                val terminal = assertAnswer(wedged.result, ResolverTransport.MULLVAD_DOH)
                assertEquals(1, mullvad.requests.size)
                assertEquals(listOf(request.epoch, request.epoch), observedEpochs)
                assertEquals(
                    ResolverQueryHealthEvidence.primaryRejectedWithFallback(
                        queryId = 100L + rcode,
                        fallback = ProtectionFallback.ENCRYPTED_DNS,
                    ),
                    terminal.resolverQueryEvidence?.healthOnly(),
                )
                assertFallback(terminal.resolverQueryEvidence, DnsFallbackReason.RESOLVER_OUTAGE, DnsFallbackDisposition.SERVED)
                assertTrue(terminal.healthEvents.isEmpty())
                assertEquals(ResolverKind.DEVICE, wedged.primaryResolverKind)
                assertEquals(ResolverKind.MULLVAD_DOH, wedged.acceptedResolverKind)
            }
        }

    @Test
    fun `active wedge rejection suppressed by Mullvad backoff stays attributed to Device`() =
        runBlocking {
            listOf(2, 5).forEach { rcode ->
                val plan = devicePlan(mullvadEnabled = true)
                val request = request(plan, queryId = 20L + rcode)
                val policy = ResolverEndpointBackoffPolicy(FakeClock(0L))
                policy.recordFailure(
                    ResolverEndpointKey.MullvadDoh,
                    ResolverFailureReason.TIMEOUT,
                )
                val device = RecordingDeviceRoute { routeRequest, _ ->
                    answer(routeRequest, ResolverTransport.DEVICE_UDP, rcode = rcode)
                }
                val mullvad = RecordingResolver {
                    fail("backed-off Mullvad must not be invoked")
                    ResolverResult.Failure(ResolverFailureReason.RECEIVE_FAILED)
                }

                val candidate = sequencer(
                    plan,
                    device = device,
                    mullvad = mullvad,
                    policy = policy,
                    wedge = DeviceResolverWedgeEvidence { true },
                ).resolve(request)

                val terminal = assertAnswer(candidate.result, ResolverTransport.DEVICE_UDP)
                assertArrayEquals(response(request.query, rcode, 0), terminal.dnsMessage)
                assertEquals(1, device.requests.size)
                assertTrue(mullvad.requests.isEmpty())
                assertTrue(terminal.healthEvents.isEmpty())
                assertEquals(ProtectionFallback.NONE, terminal.resolverQueryEvidence?.successfulFallback)
                assertFallback(terminal.resolverQueryEvidence, DnsFallbackReason.RESOLVER_OUTAGE, DnsFallbackDisposition.ATTEMPTED)
                assertEquals(ResolverTierDiagnosticOutcome.ANSWERED, terminal.resolverQueryEvidence?.tierObservations?.first()?.outcome)
                assertEquals(ResolverTierDiagnosticOutcome.NOT_ATTEMPTED, terminal.resolverQueryEvidence?.tierObservations?.last()?.outcome)
            }
        }

    @Test
    fun `Device typed failure uses Mullvad once with exact ordered evidence`() = runBlocking {
        val plan = devicePlan(mullvadEnabled = true)
        val request = request(plan, queryId = 41L)
        val device = RecordingDeviceRoute { _, _ ->
            ResolverResult.Failure(ResolverFailureReason.TIMEOUT)
        }
        val mullvad = RecordingResolver { routeRequest ->
            answer(routeRequest, ResolverTransport.MULLVAD_DOH)
        }

        val candidate = sequencer(plan, device = device, mullvad = mullvad).resolve(request)

        val terminal = assertAnswer(candidate.result, ResolverTransport.MULLVAD_DOH)
        assertEquals(1, device.requests.size)
        assertEquals(1, mullvad.requests.size)
        assertEquals(
            listOf(
                DnsHealthEvent.ResolverAttemptFailed(
                    ResolverKind.DEVICE,
                    ResolverFailureReason.TIMEOUT,
                ),
            ),
            terminal.healthEvents,
        )
        assertEquals(
            ResolverQueryHealthEvidence.primaryFailed(
                queryId = 41L,
                reason = ResolverFailureReason.TIMEOUT,
                successfulFallback = ProtectionFallback.ENCRYPTED_DNS,
            ),
            terminal.resolverQueryEvidence?.healthOnly(),
        )
        assertEquals(ResolverKind.DEVICE, candidate.primaryResolverKind)
        assertEquals(ResolverKind.MULLVAD_DOH, candidate.acceptedResolverKind)
        assertFallback(terminal.resolverQueryEvidence, DnsFallbackReason.TRANSPORT_FAILURE, DnsFallbackDisposition.SERVED)
    }

    @Test
    fun `Device reply-boundary failure uses Mullvad once`() = runBlocking {
        val plan = devicePlan(mullvadEnabled = true)
        val request = request(plan, queryId = 42L)
        val device = RecordingDeviceRoute { routeRequest, _ ->
            answer(routeRequest, ResolverTransport.MULLVAD_DOH)
        }
        val mullvad = RecordingResolver { routeRequest ->
            answer(routeRequest, ResolverTransport.MULLVAD_DOH)
        }

        val candidate = sequencer(plan, device = device, mullvad = mullvad).resolve(request)

        val terminal = assertAnswer(candidate.result, ResolverTransport.MULLVAD_DOH)
        assertEquals(1, device.requests.size)
        assertEquals(1, mullvad.requests.size)
        assertEquals(
            listOf(
                DnsHealthEvent.ResolverAttemptFailed(
                    ResolverKind.DEVICE,
                    ResolverFailureReason.INVALID_RESPONSE,
                ),
            ),
            terminal.healthEvents,
        )
        assertEquals(
            ResolverQueryHealthEvidence.primaryFailed(
                queryId = 42L,
                reason = ResolverFailureReason.INVALID_RESPONSE,
                successfulFallback = ProtectionFallback.ENCRYPTED_DNS,
            ),
            terminal.resolverQueryEvidence?.healthOnly(),
        )
        assertFallback(terminal.resolverQueryEvidence, DnsFallbackReason.TRANSPORT_FAILURE, DnsFallbackDisposition.SERVED)
    }

    @Test
    fun `Device boundary rejection backs off only the answered address before next query`() =
        runBlocking {
            val first = ip("192.0.2.53")
            val answered = ip("192.0.2.54")
            val plan = devicePlan(listOf(first, answered), mullvadEnabled = true)
            val policy = ResolverEndpointBackoffPolicy(FakeClock(0L))
            val answeredEndpoint = ResolverEndpointKey.DeviceAddress(answered)
            var wireAttempts = 0
            val device = RecordingDeviceRoute(answeredEndpoint = answered) { routeRequest, backoff ->
                if (backoff.isBackedOff(answeredEndpoint)) {
                    ResolverResult.Failure(ResolverFailureReason.BACKED_OFF)
                } else {
                    wireAttempts += 1
                    backoff.recordSuccess(answeredEndpoint)
                    answer(routeRequest, ResolverTransport.MULLVAD_DOH)
                }
            }
            val mullvad = RecordingResolver { routeRequest ->
                answer(routeRequest, ResolverTransport.MULLVAD_DOH)
            }
            val routeSequencer = sequencer(
                plan = plan,
                device = device,
                mullvad = mullvad,
                policy = policy,
            )

            val boundaryRejected = routeSequencer.resolve(request(plan, queryId = 43L))

            assertAnswer(boundaryRejected.result, ResolverTransport.MULLVAD_DOH)
            assertFalse(policy.isBackedOff(ResolverEndpointKey.DeviceAddress(first)))
            assertTrue(policy.isBackedOff(answeredEndpoint))

            val suppressed = routeSequencer.resolve(request(plan, queryId = 44L))

            assertAnswer(suppressed.result, ResolverTransport.MULLVAD_DOH)
            assertEquals(1, wireAttempts)
            assertEquals(2, device.requests.size)
            assertEquals(2, mullvad.requests.size)
        }

    @Test
    fun `typed stale epoch and cancellation terminate without Mullvad`() = runBlocking {
        listOf(
            ResolverFailureReason.STALE_EPOCH,
            ResolverFailureReason.CANCELLED,
        ).forEach { reason ->
            val plan = devicePlan(mullvadEnabled = true)
            val device = RecordingDeviceRoute { _, _ -> ResolverResult.Failure(reason) }
            val mullvad = RecordingResolver {
                fail("$reason must not invoke Mullvad")
                ResolverResult.Failure(ResolverFailureReason.RECEIVE_FAILED)
            }

            val candidate = sequencer(plan, device = device, mullvad = mullvad)
                .resolve(request(plan))

            assertFailure(candidate.result, reason, ResolverKind.DEVICE)
            assertTrue(mullvad.requests.isEmpty())
            assertEquals(null, candidate.acceptedResolverKind)
        }
    }

    @Test
    fun `thrown coroutine cancellation never falls through or mutates Mullvad policy`() {
        val plan = devicePlan(mullvadEnabled = true)
        val policy = ResolverEndpointBackoffPolicy(FakeClock(0L))
        val device = RecordingDeviceRoute { _, _ ->
            throw CancellationException("cancel device route")
        }
        val mullvad = RecordingResolver {
            fail("coroutine cancellation must not invoke Mullvad")
            ResolverResult.Failure(ResolverFailureReason.RECEIVE_FAILED)
        }

        assertThrows(CancellationException::class.java) {
            runBlocking {
                sequencer(
                    plan,
                    device = device,
                    mullvad = mullvad,
                    policy = policy,
                ).resolve(request(plan))
            }
        }

        assertTrue(mullvad.requests.isEmpty())
        assertFalse(policy.isBackedOff(ResolverEndpointKey.MullvadDoh))
    }

    @Test
    fun `Mullvad-only success is primary and emits no fallback transition`() = runBlocking {
        val plan = mullvadPlan()
        val request = request(plan, queryId = 51L)
        val clock = FakeClock(1_000_000L)
        val mullvad = RecordingResolver { routeRequest ->
            clock.advance(4_000_000L)
            answer(routeRequest, ResolverTransport.MULLVAD_DOH)
        }

        val candidate = sequencer(plan, mullvad = mullvad, clock = clock).resolve(request)

        val terminal = assertAnswer(candidate.result, ResolverTransport.MULLVAD_DOH)
        assertEquals(ResolverKind.MULLVAD_DOH, candidate.primaryResolverKind)
        assertEquals(ResolverKind.MULLVAD_DOH, candidate.acceptedResolverKind)
        assertEquals(
            ResolverQueryHealthEvidence.primaryAccepted(queryId = 51L, latencyMillis = 4L),
            terminal.resolverQueryEvidence?.healthOnly(),
        )
        assertTrue(terminal.healthEvents.isEmpty())
        assertFalse(terminal.healthEvents.any { it is DnsHealthEvent.FallbackActivated })
    }

    @Test
    fun `Mullvad-only failure is primary and emits no fallback transition`() = runBlocking {
        val plan = mullvadPlan()
        val request = request(plan, queryId = 52L)
        val mullvad = RecordingResolver {
            ResolverResult.Failure(ResolverFailureReason.TLS_FAILED)
        }

        val candidate = sequencer(plan, mullvad = mullvad).resolve(request)

        val terminal = assertFailure(
            candidate.result,
            ResolverFailureReason.TLS_FAILED,
            ResolverKind.MULLVAD_DOH,
        )
        assertEquals(1, mullvad.requests.size)
        assertEquals(
            listOf(
                DnsHealthEvent.ResolverAttemptFailed(
                    ResolverKind.MULLVAD_DOH,
                    ResolverFailureReason.TLS_FAILED,
                ),
            ),
            terminal.healthEvents,
        )
        assertEquals(
            ResolverQueryHealthEvidence.primaryFailed(52L, ResolverFailureReason.TLS_FAILED),
            terminal.resolverQueryEvidence?.healthOnly(),
        )
        assertFalse(terminal.healthEvents.any { it is DnsHealthEvent.FallbackActivated })
        assertEquals(null, candidate.acceptedResolverKind)
    }

    @Test
    fun `fallback failure fails closed after one attempt per route and never recurses`() = runBlocking {
        val plan = devicePlan(mullvadEnabled = true)
        val request = request(plan, queryId = 61L)
        val device = RecordingDeviceRoute { _, _ ->
            ResolverResult.Failure(ResolverFailureReason.RECEIVE_FAILED)
        }
        val mullvad = RecordingResolver {
            ResolverResult.Failure(ResolverFailureReason.HTTP_STATUS)
        }

        val candidate = sequencer(plan, device = device, mullvad = mullvad).resolve(request)

        val terminal = assertFailure(
            candidate.result,
            ResolverFailureReason.HTTP_STATUS,
            ResolverKind.MULLVAD_DOH,
        )
        assertEquals(1, device.requests.size)
        assertEquals(1, mullvad.requests.size)
        assertEquals(
            listOf(
                DnsHealthEvent.ResolverAttemptFailed(
                    ResolverKind.DEVICE,
                    ResolverFailureReason.RECEIVE_FAILED,
                ),
                DnsHealthEvent.ResolverAttemptFailed(
                    ResolverKind.MULLVAD_DOH,
                    ResolverFailureReason.HTTP_STATUS,
                ),
            ),
            terminal.healthEvents,
        )
        assertEquals(
            ResolverQueryHealthEvidence.primaryFailed(61L, ResolverFailureReason.RECEIVE_FAILED),
            terminal.resolverQueryEvidence?.healthOnly(),
        )
        assertFallback(terminal.resolverQueryEvidence, DnsFallbackReason.TRANSPORT_FAILURE, DnsFallbackDisposition.ATTEMPTED)
    }

    @Test
    fun `retry-worthy Mullvad primary failure suppresses next query silently`() = runBlocking {
        val plan = mullvadPlan()
        val clock = FakeClock(0L)
        val policy = ResolverEndpointBackoffPolicy(clock)
        val mullvad = RecordingResolver {
            ResolverResult.Failure(ResolverFailureReason.TIMEOUT)
        }
        val routeSequencer = sequencer(plan, mullvad = mullvad, policy = policy, clock = clock)

        val attempted = routeSequencer.resolve(request(plan, queryId = 71L))
        val suppressed = routeSequencer.resolve(request(plan, queryId = 72L))

        assertFailure(attempted.result, ResolverFailureReason.TIMEOUT, ResolverKind.MULLVAD_DOH)
        val terminal = assertFailure(
            suppressed.result,
            ResolverFailureReason.BACKED_OFF,
            ResolverKind.MULLVAD_DOH,
        )
        assertEquals(1, mullvad.requests.size)
        assertTrue(terminal.healthEvents.isEmpty())
        assertEquals(
            ResolverQueryHealthEvidence.primaryFailed(72L, ResolverFailureReason.BACKED_OFF),
            terminal.resolverQueryEvidence?.healthOnly(),
        )
    }

    @Test
    fun `retry-worthy Mullvad fallback failure suppresses next fallback silently`() = runBlocking {
        val plan = devicePlan(mullvadEnabled = true)
        val clock = FakeClock(0L)
        val policy = ResolverEndpointBackoffPolicy(clock)
        val device = RecordingDeviceRoute { _, _ ->
            ResolverResult.Failure(ResolverFailureReason.RECEIVE_FAILED)
        }
        val mullvad = RecordingResolver {
            ResolverResult.Failure(ResolverFailureReason.TLS_FAILED)
        }
        val routeSequencer = sequencer(
            plan,
            device = device,
            mullvad = mullvad,
            policy = policy,
            clock = clock,
        )

        routeSequencer.resolve(request(plan, queryId = 81L))
        val suppressed = routeSequencer.resolve(request(plan, queryId = 82L))

        val terminal = assertFailure(
            suppressed.result,
            ResolverFailureReason.RECEIVE_FAILED,
            ResolverKind.DEVICE,
        )
        assertEquals(2, device.requests.size)
        assertEquals(1, mullvad.requests.size)
        assertEquals(
            listOf(
                DnsHealthEvent.ResolverAttemptFailed(
                    ResolverKind.DEVICE,
                    ResolverFailureReason.RECEIVE_FAILED,
                ),
            ),
            terminal.healthEvents,
        )
        assertEquals(
            ResolverQueryHealthEvidence.primaryFailed(82L, ResolverFailureReason.RECEIVE_FAILED),
            terminal.resolverQueryEvidence?.healthOnly(),
        )
    }

    @Test
    fun `accepted Mullvad primary and fallback answers clear their endpoint key`() = runBlocking {
        listOf(false, true).forEach { devicePrimary ->
            val plan = if (devicePrimary) devicePlan(mullvadEnabled = true) else mullvadPlan()
            val clock = FakeClock(0L)
            val policy = ResolverEndpointBackoffPolicy(clock)
            val device = if (devicePrimary) {
                RecordingDeviceRoute { _, _ ->
                    ResolverResult.Failure(ResolverFailureReason.RECEIVE_FAILED)
                }
            } else {
                null
            }
            val mullvad = RecordingResolver { routeRequest ->
                policy.recordFailure(
                    ResolverEndpointKey.MullvadDoh,
                    ResolverFailureReason.TIMEOUT,
                )
                answer(routeRequest, ResolverTransport.MULLVAD_DOH)
            }

            val candidate = sequencer(
                plan,
                device = device,
                mullvad = mullvad,
                policy = policy,
                clock = clock,
            ).resolve(request(plan))

            assertAnswer(candidate.result, ResolverTransport.MULLVAD_DOH)
            assertFalse(
                "accepted ${if (devicePrimary) "fallback" else "primary"} must clear Mullvad",
                policy.isBackedOff(ResolverEndpointKey.MullvadDoh),
            )
        }
    }

    @Test
    fun `Mullvad primary boundary rejection replaces backoff from its observation`() =
        runBlocking {
            val plan = mullvadPlan()
            val clock = FakeClock(0L)
            val policy = ResolverEndpointBackoffPolicy(clock)
            policy.recordFailure(
                ResolverEndpointKey.MullvadDoh,
                ResolverFailureReason.TIMEOUT,
            )
            clock.advance(30_000_000_000L)
            val mullvad = RecordingResolver { routeRequest ->
                answer(routeRequest, ResolverTransport.DEVICE_UDP)
            }
            val routeSequencer = sequencer(
                plan,
                mullvad = mullvad,
                policy = policy,
                clock = clock,
            )

            val boundaryRejected = routeSequencer.resolve(request(plan, queryId = 111L))
            clock.advance(29_999_000_000L)
            val suppressed = routeSequencer.resolve(request(plan, queryId = 112L))

            assertFailure(
                boundaryRejected.result,
                ResolverFailureReason.INVALID_RESPONSE,
                ResolverKind.MULLVAD_DOH,
            )
            val terminal = assertFailure(
                suppressed.result,
                ResolverFailureReason.BACKED_OFF,
                ResolverKind.MULLVAD_DOH,
            )
            assertEquals(1, mullvad.requests.size)
            assertTrue(terminal.healthEvents.isEmpty())
            clock.advance(1_000_000L)
            assertFalse(policy.isBackedOff(ResolverEndpointKey.MullvadDoh))
        }

    @Test
    fun `Mullvad fallback boundary rejection replaces backoff from its observation`() =
        runBlocking {
            val plan = devicePlan(mullvadEnabled = true)
            val clock = FakeClock(0L)
            val policy = ResolverEndpointBackoffPolicy(clock)
            policy.recordFailure(
                ResolverEndpointKey.MullvadDoh,
                ResolverFailureReason.TIMEOUT,
            )
            clock.advance(30_000_000_000L)
            val device = RecordingDeviceRoute { _, _ ->
                ResolverResult.Failure(ResolverFailureReason.RECEIVE_FAILED)
            }
            val mullvad = RecordingResolver { routeRequest ->
                answer(routeRequest, ResolverTransport.DEVICE_UDP)
            }
            val routeSequencer = sequencer(
                plan,
                device = device,
                mullvad = mullvad,
                policy = policy,
                clock = clock,
            )

            val boundaryRejected = routeSequencer.resolve(request(plan, queryId = 121L))
            clock.advance(29_999_000_000L)
            val suppressed = routeSequencer.resolve(request(plan, queryId = 122L))

            assertFailure(
                boundaryRejected.result,
                ResolverFailureReason.INVALID_RESPONSE,
                ResolverKind.MULLVAD_DOH,
            )
            val terminal = assertFailure(
                suppressed.result,
                ResolverFailureReason.RECEIVE_FAILED,
                ResolverKind.DEVICE,
            )
            assertEquals(2, device.requests.size)
            assertEquals(1, mullvad.requests.size)
            assertEquals(
                listOf(
                    DnsHealthEvent.ResolverAttemptFailed(
                        ResolverKind.DEVICE,
                        ResolverFailureReason.RECEIVE_FAILED,
                    ),
                ),
                terminal.healthEvents,
            )
            clock.advance(1_000_000L)
            assertFalse(policy.isBackedOff(ResolverEndpointKey.MullvadDoh))
        }

    @Test
    fun `non-retry-worthy Mullvad outcomes preserve an existing key and its expiry`() =
        runBlocking {
            listOf(
                ResolverFailureReason.CANCELLED,
                ResolverFailureReason.STALE_EPOCH,
                ResolverFailureReason.OVERLOADED,
                ResolverFailureReason.BACKED_OFF,
            ).forEach { reason ->
                val plan = mullvadPlan()
                val clock = FakeClock(0L)
                val policy = ResolverEndpointBackoffPolicy(clock)
                val mullvad = RecordingResolver {
                    policy.recordFailure(
                        ResolverEndpointKey.MullvadDoh,
                        ResolverFailureReason.TIMEOUT,
                    )
                    clock.advance(10_000_000_000L)
                    ResolverResult.Failure(reason)
                }

                val candidate = sequencer(
                    plan,
                    mullvad = mullvad,
                    policy = policy,
                    clock = clock,
                ).resolve(request(plan))

                assertFailure(candidate.result, reason, ResolverKind.MULLVAD_DOH)
                assertEquals(reason.name, 1, mullvad.requests.size)
                clock.advance(19_999_000_000L)
                assertTrue(reason.name, policy.isBackedOff(ResolverEndpointKey.MullvadDoh))
                clock.advance(1_000_000L)
                assertFalse(reason.name, policy.isBackedOff(ResolverEndpointKey.MullvadDoh))
            }
        }

    @Test
    fun `already-backed-off Mullvad outcome neither calls the wire nor extends expiry`() =
        runBlocking {
            val plan = mullvadPlan()
            val clock = FakeClock(0L)
            val policy = ResolverEndpointBackoffPolicy(clock)
            policy.recordFailure(ResolverEndpointKey.MullvadDoh, ResolverFailureReason.TIMEOUT)
            clock.advance(29_999_000_000L)
            val mullvad = RecordingResolver {
                fail("backed-off Mullvad must not be invoked")
                ResolverResult.Failure(ResolverFailureReason.RECEIVE_FAILED)
            }

            val candidate = sequencer(
                plan,
                mullvad = mullvad,
                policy = policy,
                clock = clock,
            ).resolve(request(plan))

            val terminal = assertFailure(
                candidate.result,
                ResolverFailureReason.BACKED_OFF,
                ResolverKind.MULLVAD_DOH,
            )
            assertTrue(mullvad.requests.isEmpty())
            assertTrue(terminal.healthEvents.isEmpty())
            clock.advance(1_000_000L)
            assertFalse(policy.isBackedOff(ResolverEndpointKey.MullvadDoh))
        }

    @Test
    fun `policy retirement racing with Mullvad completion rejects the late observation`() =
        runBlocking {
            val plan = mullvadPlan()
            val policyClock = FakeClock(0L)
            val policy = ResolverEndpointBackoffPolicy(policyClock)
            val mullvad = RecordingResolver {
                policy.retire()
                ResolverResult.Failure(ResolverFailureReason.TIMEOUT)
            }

            val candidate = sequencer(
                plan,
                mullvad = mullvad,
                policy = policy,
                clock = FakeClock(0L),
            ).resolve(request(plan))

            assertFailure(
                candidate.result,
                ResolverFailureReason.TIMEOUT,
                ResolverKind.MULLVAD_DOH,
            )
            assertEquals(0L, policyClock.readCount)
            assertFalse(policy.isBackedOff(ResolverEndpointKey.MullvadDoh))
        }

    @Test
    fun `primary accepted latency uses actual monotonic elapsed time across signed wrap`() =
        runBlocking {
            val plan = devicePlan(mullvadEnabled = false)
            val clock = FakeClock(Long.MAX_VALUE - 500_000L)
            val device = RecordingDeviceRoute { routeRequest, _ ->
                clock.advance(2_500_000L)
                answer(routeRequest, ResolverTransport.DEVICE_UDP)
            }

            val candidate = sequencer(plan, device = device, clock = clock)
                .resolve(request(plan, queryId = 91L))

            val terminal = assertAnswer(candidate.result, ResolverTransport.DEVICE_UDP)
            assertEquals(
                ResolverQueryHealthEvidence.primaryAccepted(91L, latencyMillis = 2L),
                terminal.resolverQueryEvidence?.healthOnly(),
            )
        }

    @Test
    fun `terminal events and evidence are exact ordered immutable snapshots`() = runBlocking {
        val plan = devicePlan(mullvadEnabled = true)
        val request = request(plan, queryId = 101L)
        val childEvents = mutableListOf<DnsHealthEvent>(
            DnsHealthEvent.ResolverRecovered(ResolverKind.DEVICE),
        )
        val device = RecordingDeviceRoute { _, _ ->
            ResolverResult.Failure(
                reason = ResolverFailureReason.SOCKET_FAILED,
                healthEvents = childEvents,
                resolver = ResolverKind.MULLVAD_DOH,
                resolverQueryEvidence = ResolverQueryHealthEvidence.primaryAccepted(999L, 999L),
            )
        }
        val mullvad = RecordingResolver {
            ResolverResult.Failure(
                reason = ResolverFailureReason.HTTP_CONTENT_TYPE,
                healthEvents = childEvents,
                resolver = ResolverKind.DEVICE,
                resolverQueryEvidence = ResolverQueryHealthEvidence.primaryAccepted(998L, 998L),
            )
        }

        val candidate = sequencer(plan, device = device, mullvad = mullvad).resolve(request)
        childEvents.clear()

        val terminal = assertFailure(
            candidate.result,
            ResolverFailureReason.HTTP_CONTENT_TYPE,
            ResolverKind.MULLVAD_DOH,
        )
        assertEquals(
            listOf(
                DnsHealthEvent.ResolverAttemptFailed(
                    ResolverKind.DEVICE,
                    ResolverFailureReason.SOCKET_FAILED,
                ),
                DnsHealthEvent.ResolverAttemptFailed(
                    ResolverKind.MULLVAD_DOH,
                    ResolverFailureReason.HTTP_CONTENT_TYPE,
                ),
            ),
            terminal.healthEvents,
        )
        assertEquals(
            ResolverQueryHealthEvidence.primaryFailed(101L, ResolverFailureReason.SOCKET_FAILED),
            terminal.resolverQueryEvidence?.healthOnly(),
        )
        assertFallback(terminal.resolverQueryEvidence, DnsFallbackReason.TRANSPORT_FAILURE, DnsFallbackDisposition.ATTEMPTED)
        assertImmutable(terminal.healthEvents)
        assertEquals(ResolverKind.DEVICE, candidate.primaryResolverKind)
        assertEquals(null, candidate.acceptedResolverKind)
    }

    @Test
    fun `matching caller ID and valid oversized answer stay complete until client delivery`() = runBlocking {
        val plan = devicePlan(mullvadEnabled = true)
        val callerQuery = query(id = 0x4321)
        val request = request(plan, queryBytes = callerQuery)
        val source = response(
            query = callerQuery,
            rcode = 0,
            answerCount = 1,
            size = 1_253,
            upstreamId = 0x4321,
        )
        val sourceSnapshot = source.copyOf()
        val device = RecordingDeviceRoute { _, _ ->
            ResolverResult.Answer(source, ResolverTransport.DEVICE_TCP)
        }
        val mullvad = RecordingResolver {
            fail("an oversized accepted Device answer remains authoritative")
            ResolverResult.Failure(ResolverFailureReason.RECEIVE_FAILED)
        }

        val candidate = sequencer(
            plan,
            device = device,
            mullvad = mullvad,
            wedge = DeviceResolverWedgeEvidence { true },
        ).resolve(request)

        val terminal = assertAnswer(candidate.result, ResolverTransport.DEVICE_TCP)
        assertEquals(0x4321, read16(terminal.dnsMessage, 0))
        assertArrayEquals(sourceSnapshot, terminal.dnsMessage)
        assertEquals(1_253, terminal.dnsMessage.size)
        assertEquals(0, read16(terminal.dnsMessage, 2) and 0x0200)
        assertEquals(1, read16(terminal.dnsMessage, 6))
        val delivered = checkNotNull(ResolverReplyBoundary.boundForClient(request.query, terminal.dnsMessage))
        assertTrue(delivered.size <= DnsOnlyTunContract.MAX_RESPONSE_DNS_BYTES)
        assertTrue(read16(delivered, 2) and 0x0200 != 0)
        assertEquals(0, read16(delivered, 6))
        assertArrayEquals(sourceSnapshot, source)
        assertNotSame(source, terminal.dnsMessage)
        assertTrue(mullvad.requests.isEmpty())
    }

    @Test
    fun `non-QR RCODE 2 and 5 fail identity validation without entering fallback`() = runBlocking {
        listOf(2, 5).forEach { rcode ->
            val plan = devicePlan(mullvadEnabled = true)
            val device = RecordingDeviceRoute { routeRequest, _ ->
                answer(
                    routeRequest,
                    ResolverTransport.DEVICE_UDP,
                    rcode = rcode,
                    qr = false,
                )
            }
            val mullvad = RecordingResolver {
                fail("non-QR RCODE bits are not a rejection classification")
                ResolverResult.Failure(ResolverFailureReason.RECEIVE_FAILED)
            }

            val candidate = sequencer(
                plan,
                device = device,
                mullvad = mullvad,
                wedge = DeviceResolverWedgeEvidence { true },
            ).resolve(request(plan))

            val failure = assertFailure(candidate.result, ResolverFailureReason.MISMATCH_LIMIT, ResolverKind.DEVICE)
            assertTrue(failure.terminal)
            assertEquals(null, candidate.acceptedResolverKind)
            assertTrue(checkNotNull(failure.resolverQueryEvidence).fallbackObservations.isEmpty())
            assertTrue(mullvad.requests.isEmpty())
        }
    }

    @Test
    fun `mismatched transaction cannot become an accepted reply or authorize another resolver`() = runBlocking {
        val plan = devicePlan(mullvadEnabled = true)
        val request = request(plan, queryBytes = query(id = 0x4321))
        val device = RecordingDeviceRoute { _, _ ->
            ResolverResult.Answer(response(request.query, rcode = 0, answerCount = 1, upstreamId = 0x1234),
                ResolverTransport.DEVICE_UDP)
        }
        val mullvad = RecordingResolver { fail("unmatched transaction cannot authorize fallback");
            ResolverResult.Failure(ResolverFailureReason.RECEIVE_FAILED) }
        val candidate = sequencer(plan, device, mullvad).resolve(request)
        val failure = assertFailure(candidate.result, ResolverFailureReason.MISMATCH_LIMIT, ResolverKind.DEVICE)
        assertTrue(failure.terminal)
        assertEquals(null, candidate.acceptedResolverKind)
        assertTrue(mullvad.requests.isEmpty())
        assertTrue(checkNotNull(failure.resolverQueryEvidence).fallbackObservations.isEmpty())
    }

    private fun sequencer(
        plan: ResolverPlan,
        device: DeviceDnsRoute? = null,
        mullvad: DnsResolver? = null,
        policy: ResolverEndpointBackoffPolicy = ResolverEndpointBackoffPolicy(FakeClock(0L)),
        wedge: DeviceResolverWedgeEvidence = DeviceResolverWedgeEvidence { false },
        clock: MonotonicClock = FakeClock(0L),
    ) = ResolverRouteSequencer(
        plan = plan,
        deviceRoute = device,
        mullvadRoute = mullvad,
        endpointBackoff = policy,
        wedgeEvidence = wedge,
        clock = clock,
    )

    private fun devicePlan(
        addresses: List<InetAddress> = listOf(ip("192.0.2.53")),
        mullvadEnabled: Boolean,
    ): ResolverPlan = ResolverPlan(
        network = network(42),
        networkGeneration = 7L,
        isCellular = false,
        deviceDnsServers = addresses,
        mullvadEnabled = mullvadEnabled,
        primaryResolverKind = ResolverKind.DEVICE,
        primaryResolverIdentity = PrimaryResolverIdentity("device-primary"),
        fallbackCoverage = if (mullvadEnabled) {
            ResolverFallbackCoverage.ENCRYPTED_DNS
        } else {
            ResolverFallbackCoverage.NONE
        },
    )

    private fun orderedDevicePlan(encryptedFirst: Boolean = false): ResolverPlan {
        val device = devicePlan(mullvadEnabled = false)
        val encrypted = ResolverPlan(device.network, device.networkGeneration, false, emptyList(), true,
            ResolverKind.MULLVAD_DOH, primaryResolverIdentity = PrimaryResolverIdentity("ordered-encrypted"),
            fallbackCoverage = ResolverFallbackCoverage.NONE)
        val leaves = if (encryptedFirst) listOf(encrypted, device) else listOf(device, encrypted)
        return ResolverPlan(device.network, device.networkGeneration, false, device.deviceDnsServers, true,
            leaves.first().primaryResolverKind, primaryResolverIdentity = leaves.first().primaryResolverIdentity,
            fallbackCoverage = if (encryptedFirst) ResolverFallbackCoverage.DEVICE_DNS else ResolverFallbackCoverage.ENCRYPTED_DNS,
            orderedRoutes = leaves)
    }

    // The older policy assertions deliberately compare only policy evidence. Dedicated route
    // diagnostics below assert the new metadata independently, without changing health behavior.
    private fun ResolverQueryHealthEvidence.healthOnly() = copy(tierObservations = emptyList(), responseLatencyMillis = null,
        httpProtocol = null, fallbackObservations = emptyList())

    private fun assertFallback(evidence: ResolverQueryHealthEvidence?, reason: DnsFallbackReason, disposition: DnsFallbackDisposition) {
        assertEquals(listOf(DnsFallbackObservation(1, 2, reason, disposition)), checkNotNull(evidence).fallbackObservations)
    }

    @Test fun `both route implementations record exact tier outcomes protocol and fallback duration`() = runBlocking {
        for (ordered in listOf(false, true)) {
            val plan = if (ordered) orderedDevicePlan() else devicePlan(mullvadEnabled = true)
            val device = RecordingDeviceRoute { _, _ -> ResolverResult.Failure(ResolverFailureReason.TIMEOUT) }
            val encrypted = RecordingResolver { request ->
                val response = answer(request, if (ordered) ResolverTransport.DOT else ResolverTransport.MULLVAD_DOH)
                ResolverResult.Answer(response.dnsMessage, response.transport,
                    httpProtocol = if (ordered) null else com.lavasec.app.foundation.health.DnsHttpProtocol.HTTP_2)
            }
            val route = sequencer(plan, device, if (ordered) orderedEncrypted(plan, encrypted) else encrypted)
            val evidence = checkNotNull(route.resolve(request(plan)).result.resolverQueryEvidence)
            assertEquals(listOf(1, 2), evidence.tierObservations.map { it.tier })
            assertEquals(listOf(com.lavasec.app.foundation.health.ResolverTierDiagnosticOutcome.FAILED,
                com.lavasec.app.foundation.health.ResolverTierDiagnosticOutcome.SERVED), evidence.tierObservations.map { it.outcome })
            assertEquals(ResolverFailureReason.TIMEOUT, evidence.tierObservations.first().failure)
            assertEquals(if (ordered) ResolverTransport.DOT else ResolverTransport.MULLVAD_DOH, evidence.tierObservations.last().transport)
            assertEquals(if (ordered) null else com.lavasec.app.foundation.health.DnsHttpProtocol.HTTP_2, evidence.httpProtocol)
            assertTrue(evidence.responseLatencyMillis != null)
            assertFallback(evidence, if (ordered) DnsFallbackReason.TIER_BUDGET_EXPIRED else DnsFallbackReason.TRANSPORT_FAILURE,
                DnsFallbackDisposition.SERVED)
        }
    }

    private fun orderedEncrypted(plan: ResolverPlan, delegate: DnsResolver) = object : OrderedEncryptedDnsResolver {
        override fun resolverFor(identity: PrimaryResolverIdentity): DnsResolver {
            assertTrue(plan.orderedRoutes!!.any { it.primaryResolverIdentity == identity && it.primaryResolverKind == ResolverKind.MULLVAD_DOH })
            return delegate
        }
        override suspend fun resolve(request: ResolverRequest): ResolverResult = error("ordered leaves require exact lookup")
    }

    private fun mullvadPlan(): ResolverPlan = ResolverPlan(
        network = network(43),
        networkGeneration = 8L,
        isCellular = false,
        deviceDnsServers = emptyList(),
        mullvadEnabled = true,
        primaryResolverKind = ResolverKind.MULLVAD_DOH,
        primaryResolverIdentity = PrimaryResolverIdentity("mullvad-doh"),
        fallbackCoverage = ResolverFallbackCoverage.NONE,
    )

    private fun request(
        plan: ResolverPlan,
        queryId: Long = 1L,
        queryBytes: ByteArray = query(),
        parsedQuery: ByteArray = queryBytes,
    ): ResolverRequest = ResolverRequest(
        query = queryBytes,
        question = DnsMessage.parseQuestion(parsedQuery),
        epoch = EngineEpoch(
            sessionGeneration = 1L,
            configurationIdentity = ConfigurationIdentity(1L, "cfg"),
            artifactDigest = "artifact",
            policyRevision = 1L,
            networkGeneration = plan.networkGeneration,
            engineGeneration = 1L,
            primaryResolverIdentity = plan.primaryResolverIdentity,
            fallbackCoverage = plan.fallbackCoverage,
        ),
        networkContext = NetworkContext(
            network = plan.network,
            networkGeneration = plan.networkGeneration,
            dnsServers = plan.deviceDnsServers,
            isCellular = plan.isCellular,
        ),
        queryId = queryId,
    )

    private fun answer(
        request: ResolverRequest,
        transport: ResolverTransport,
        rcode: Int = 0,
        answerCount: Int = 0,
        qr: Boolean = true,
    ): ResolverResult.Answer = ResolverResult.Answer(
        response(
            query = request.query,
            rcode = rcode,
            answerCount = answerCount,
            qr = qr,
        ),
        transport,
    )

    private fun response(
        query: ByteArray,
        rcode: Int,
        answerCount: Int,
        qr: Boolean = true,
        size: Int? = null,
        upstreamId: Int = read16(query, 0),
    ): ByteArray {
        val address = byteArrayOf(0xc0.toByte(), 0x0c, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 192.toByte(), 0, 2, 10)
        val recordsEnd = query.size + answerCount * address.size
        val responseSize = size ?: recordsEnd
        require(responseSize >= recordsEnd)
        require(responseSize == recordsEnd || responseSize - recordsEnd >= 15)
        return ByteArray(responseSize).also { body ->
            query.copyInto(body)
            write16(body, 0, upstreamId)
            write16(body, 2, (if (qr) 0x8180 else 0x0180) or rcode)
            write16(body, 6, answerCount)
            repeat(answerCount) { index -> address.copyInto(body, query.size + index * address.size) }
            if (responseSize > recordsEnd) {
                // A real EDNS PADDING option supplies extra bytes without fabricating answer RRs.
                val paddingSize = responseSize - recordsEnd - 15
                write16(body, 10, 1)
                body[recordsEnd] = 0
                write16(body, recordsEnd + 1, 41)
                write16(body, recordsEnd + 3, 4096)
                write16(body, recordsEnd + 9, paddingSize + 4)
                write16(body, recordsEnd + 11, 12)
                write16(body, recordsEnd + 13, paddingSize)
            }
        }
    }

    private fun query(id: Int = 0x1234): ByteArray = byteArrayOf(
        (id ushr 8).toByte(), id.toByte(), 0x01, 0x00, 0x00, 0x01,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x07, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
        'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
        0x03, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
        0x00, 0x00, 0x01, 0x00, 0x01,
    )

    private fun assertAnswer(
        result: ResolverResult,
        transport: ResolverTransport,
    ): ResolverResult.Answer {
        assertTrue("expected answer but was $result", result is ResolverResult.Answer)
        result as ResolverResult.Answer
        assertEquals(transport, result.transport)
        return result
    }

    private fun assertFailure(
        result: ResolverResult,
        reason: ResolverFailureReason,
        resolver: ResolverKind?,
    ): ResolverResult.Failure {
        assertTrue("expected failure but was $result", result is ResolverResult.Failure)
        result as ResolverResult.Failure
        assertEquals(reason, result.reason)
        assertEquals(resolver, result.resolver)
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> assertImmutable(values: List<T>) {
        try {
            (values as MutableList<T>).clear()
        } catch (_: UnsupportedOperationException) {
            return
        }
        fail("list was mutable")
    }

    private fun ip(value: String): InetAddress = InetAddress.getByName(value)

    private fun network(handle: Int): Network =
        Network::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .newInstance(handle)

    private fun read16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun write16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private data class RcodeCase(
        val label: String,
        val rcode: Int,
        val answerCount: Int,
    )

    private class RecordingDeviceRoute(
        private val answeredEndpoint: InetAddress? = null,
        private val result: suspend (
            request: ResolverRequest,
            endpointBackoff: ResolverEndpointBackoffPolicy,
        ) -> ResolverResult,
    ) : DeviceDnsRoute {
        val requests = mutableListOf<ResolverRequest>()

        override suspend fun resolve(
            request: ResolverRequest,
            endpointBackoff: ResolverEndpointBackoffPolicy,
        ): DeviceDnsRouteResult {
            requests += request
            val routeResult = result(request, endpointBackoff)
            return DeviceDnsRouteResult(
                result = routeResult,
                answeredEndpoint = if (routeResult is ResolverResult.Answer) {
                    ResolverEndpointKey.DeviceAddress(
                        answeredEndpoint
                            ?: DeviceDnsAddressPolicy.select(
                                request.networkContext.dnsServers,
                                request.networkContext.isCellular,
                            ).first(),
                    )
                } else {
                    null
                },
            )
        }
    }

    private class RecordingResolver(
        private val result: suspend (ResolverRequest) -> ResolverResult,
    ) : DnsResolver {
        val requests = mutableListOf<ResolverRequest>()

        override suspend fun resolve(request: ResolverRequest): ResolverResult {
            requests += request
            return result(request)
        }
    }

    private class FakeClock(initialNanos: Long) : MonotonicClock {
        private val now = AtomicLong(initialNanos)
        private val reads = AtomicLong(0L)

        val readCount: Long
            get() = reads.get()

        override fun nowNanos(): Long {
            reads.incrementAndGet()
            return now.get()
        }

        fun advance(nanos: Long) {
            now.addAndGet(nanos)
        }
    }
}
