package com.lavasec.app.vpn

import android.net.Network
import com.lavasec.app.foundation.dns.DnsMessage
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverTransport
import com.lavasec.app.foundation.resolver.CustomResolverValidator
import com.lavasec.app.foundation.health.PrimaryResolverIdentity
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.platform.ConfigurationIdentity
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import okio.Timeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MullvadDohTransportTest {
    private val closeables = ArrayDeque<AutoCloseable>()
    private val heldCertificate = HeldCertificate.Builder()
        .commonName(MullvadDohEndpoint.url.host)
        .addSubjectAlternativeName(MullvadDohEndpoint.url.host)
        .build()
    private val serverCertificates = HandshakeCertificates.Builder()
        .heldCertificate(heldCertificate)
        .build()
    private val clientCertificates = HandshakeCertificates.Builder()
        .addTrustedCertificate(heldCertificate.certificate)
        .build()
    private val clientTls = MullvadDohTlsConfiguration(
        socketFactory = clientCertificates.sslSocketFactory(),
        trustManager = clientCertificates.trustManager,
    )

    @After
    fun tearDown() {
        while (closeables.isNotEmpty()) {
            runCatching { closeables.removeLast().close() }
        }
    }

    @Test
    fun `runtime is HTTPS only and explicitly bounds the one epoch client`() {
        val fixture = tlsFixture()
        val runtime = runtime(fixture)
        val client = runtime.clientForTesting

        assertEquals(5_000, client.callTimeoutMillis)
        assertEquals(MullvadDohRuntimeLimits.MAX_REQUESTS, client.dispatcher.maxRequests)
        assertEquals(
            MullvadDohRuntimeLimits.MAX_REQUESTS_PER_HOST,
            client.dispatcher.maxRequestsPerHost,
        )
        assertEquals(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1), client.protocols)
        assertSame(Proxy.NO_PROXY, client.proxy)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
        assertNull(client.cache)
        assertSame(runtime.connectionPoolForTesting, client.connectionPool)
        assertEquals(5, MullvadDohRuntimeLimits.MAX_IDLE_CONNECTIONS)
        assertEquals(5, MullvadDohRuntimeLimits.KEEP_ALIVE_MINUTES)

        assertThrows(IllegalArgumentException::class.java) {
            MullvadDohRuntime(
                epoch = epoch(),
                networkContext = networkContext(),
                registry = GenerationSocketRegistry(epoch()),
                protector = DohSocketProtector { true },
                binder = DohSocketBinder { _, _ -> },
                endpoint = fixture.endpoint.newBuilder().scheme("http").build(),
                dns = fixture.dns,
                tls = clientTls,
            )
        }
    }

    @Test
    fun `POST keeps endpoint hostname and path while zeroing only the DNS ID`() {
        val fixture = tlsFixture()
        val query = query(id = 0x1234)
        val canonical = query.copyOf().also {
            it[0] = 0
            it[1] = 0
        }
        fixture.server.enqueue(dnsResponse(answer(canonical, responseId = 0xbeef)))
        val runtime = runtime(fixture)

        val result = runBlocking { runtime.resolver.resolve(request(query)) }

        assertAnswer(result, query, rcode = 0)
        assertArrayEquals(query(id = 0x1234), query)
        val recorded = fixture.server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", recorded.method)
        assertEquals("/dns-query", recorded.requestUrl!!.encodedPath)
        assertEquals(MullvadDohEndpoint.url.host, fixture.endpoint.host)
        assertEquals(listOf(MullvadDohEndpoint.url.host), fixture.lookedUpHosts)
        assertEquals("application/dns-message", recorded.getHeader("Accept"))
        assertEquals("application/dns-message", recorded.getHeader("Content-Type"))
        val posted = recorded.body.readByteArray()
        assertEquals(0, posted[0].toInt() and 0xff)
        assertEquals(0, posted[1].toInt() and 0xff)
        assertArrayEquals(query.copyOfRange(2, query.size), posted.copyOfRange(2, posted.size))
    }

    @Test
    fun `primary and fallback POST requests retain independent validated encoded paths and static queries`() {
        val primarySuffix = "?mode=first&dns=fixed%2Fvalue&mode=second&empty=&flag"
        for (secondarySuffix in listOf("?mode=secondary%23value&empty=&mode=last&flag", "?")) {
            val primaryBase = tlsFixture()
            val secondaryBase = tlsFixture()
            fun endpoint(base: TlsFixture, suffix: String) = checkNotNull(CustomResolverValidator.normalizedDohUrl(
                "https://${base.endpoint.host}:${base.endpoint.port}/dns%2Fquery$suffix")).toHttpUrl()
            val primary = primaryBase.copy(endpoint = endpoint(primaryBase, primarySuffix))
            val secondary = secondaryBase.copy(endpoint = endpoint(secondaryBase, secondarySuffix))
            val query = query(id = 0x1234)
            primary.server.enqueue(MockResponse().setResponseCode(503))
            secondary.server.enqueue(dnsResponse(answer(canonical(query), responseId = 0xbeef)))
            val resolver = SecondaryEndpointFallbackResolver(runtime(primary).resolver, runtime(secondary).resolver)

            val result = runBlocking { resolver.resolve(request(query)) }

            assertAnswer(result, query, rcode = 0)
            for ((fixture, suffix) in listOf(primary to primarySuffix, secondary to secondarySuffix)) {
                val recorded = fixture.server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("POST", recorded.method)
                assertEquals("/dns%2Fquery$suffix", recorded.path)
                assertEquals("/dns%2Fquery", recorded.requestUrl!!.encodedPath)
                assertEquals(suffix.removePrefix("?"), recorded.requestUrl!!.encodedQuery)
                assertEquals(listOf(MullvadDohEndpoint.url.host), fixture.lookedUpHosts)
                assertArrayEquals(canonical(query), recorded.body.readByteArray())
            }
            assertArrayEquals(query(id = 0x1234), query)
        }
    }

    @Test
    fun `redirects cookies and caching stay disabled`() {
        val query = query()
        val canonical = canonical(query)
        val redirectFixture = tlsFixture()
        redirectFixture.server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "/redirected")
                .setHeader("Content-Type", "application/dns-message")
                .setBody(Buffer().write(answer(canonical))),
        )
        redirectFixture.server.enqueue(dnsResponse(answer(canonical)))

        assertFailure(
            runtime(redirectFixture),
            query,
            ResolverFailureReason.HTTP_STATUS,
        )
        assertEquals(1, redirectFixture.server.requestCount)

        val stateFixture = tlsFixture()
        stateFixture.server.enqueue(
            dnsResponse(answer(canonical))
                .setHeader("Set-Cookie", "session=secret")
                .setHeader("Cache-Control", "public, max-age=3600"),
        )
        stateFixture.server.enqueue(dnsResponse(answer(canonical, rcode = 3)))
        val stateRuntime = runtime(stateFixture)
        assertTrue(runBlocking { stateRuntime.resolver.resolve(request(query)) } is ResolverResult.Answer)
        assertTrue(runBlocking { stateRuntime.resolver.resolve(request(query)) } is ResolverResult.Answer)
        stateFixture.server.takeRequest(1, TimeUnit.SECONDS)
        val second = stateFixture.server.takeRequest(1, TimeUnit.SECONDS)!!
        assertNull(second.getHeader("Cookie"))
        assertEquals(2, stateFixture.server.requestCount)
    }

    @Test
    fun `every successful HTTP range boundary and DNS RCODE remains an answer`() {
        val fixture = tlsFixture()
        val query = query()
        val canonical = canonical(query)
        val cases = listOf(
            200 to 0,
            201 to 2,
            206 to 3,
            226 to 5,
            299 to 0,
        )
        cases.forEach { (status, rcode) ->
            fixture.server.enqueue(dnsResponse(answer(canonical, rcode = rcode), status = status))
        }
        val runtime = runtime(fixture)

        cases.forEach { (_, rcode) ->
            assertAnswer(
                runBlocking { runtime.resolver.resolve(request(query)) },
                query,
                rcode,
            )
        }
    }

    @Test
    fun `DNS media type is case insensitive and permits parameters`() {
        val fixture = tlsFixture()
        val query = query()
        fixture.server.enqueue(
            dnsResponse(
                answer(canonical(query)),
                contentType = "Application/DNS-Message; charset=binary",
            ),
        )
        fixture.server.enqueue(
            dnsResponse(
                answer(canonical(query)),
                contentType = " APPLICATION/DNS-MESSAGE ; version=1 ",
            ),
        )
        val runtime = runtime(fixture)

        repeat(2) {
            assertTrue(runBlocking { runtime.resolver.resolve(request(query)) } is ResolverResult.Answer)
        }
    }

    @Test
    fun `wrong or missing media type and non-success status are typed`() {
        val fixture = tlsFixture()
        val query = query()
        val valid = answer(canonical(query))
        fixture.server.enqueue(
            MockResponse().setResponseCode(200).setBody(Buffer().write(valid)),
        )
        fixture.server.enqueue(dnsResponse(valid, contentType = "text/plain"))
        fixture.server.enqueue(dnsResponse(valid, status = 404))
        fixture.server.enqueue(dnsResponse(valid, status = 300))
        val runtime = runtime(fixture)

        assertFailure(runtime, query, ResolverFailureReason.HTTP_CONTENT_TYPE)
        assertFailure(runtime, query, ResolverFailureReason.HTTP_CONTENT_TYPE)
        assertFailure(runtime, query, ResolverFailureReason.HTTP_STATUS)
        assertFailure(runtime, query, ResolverFailureReason.HTTP_STATUS)
    }

    @Test
    fun `strict validator rejects empty short malformed wrong-question trailing and truncated DNS`() {
        val fixture = tlsFixture()
        val query = query()
        val canonical = canonical(query)
        val malformed = answer(canonical).also {
            it[6] = 0
            it[7] = 1
        }
        val wrongQuestion = answer(canonical).also { it[13] = 'X'.code.toByte() }
        val bodies = listOf(
            byteArrayOf() to ResolverFailureReason.INVALID_RESPONSE,
            ByteArray(11) to ResolverFailureReason.INVALID_RESPONSE,
            malformed to ResolverFailureReason.INVALID_RESPONSE,
            wrongQuestion to ResolverFailureReason.MISMATCH_LIMIT,
            (answer(canonical) + 0) to ResolverFailureReason.INVALID_RESPONSE,
            answer(canonical, truncated = true) to ResolverFailureReason.INVALID_RESPONSE,
        )
        bodies.forEach { (body, _) -> fixture.server.enqueue(dnsResponse(body)) }
        val runtime = runtime(fixture)

        bodies.forEach { (_, reason) ->
            assertFailure(runtime, query, reason)
        }
    }

    @Test
    fun `4096 byte DNS is accepted and declared or chunked 4097 bytes are rejected`() {
        val fixture = tlsFixture()
        val query = query()
        fixture.server.enqueue(dnsResponse(answerWithSize(canonical(query), 4_096)))
        fixture.server.enqueue(dnsResponse(ByteArray(4_097)))
        fixture.server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/dns-message")
                .setChunkedBody(Buffer().write(ByteArray(4_097)), 257),
        )
        val runtime = runtime(fixture)

        val maximum = runBlocking { runtime.resolver.resolve(request(query)) }
        assertTrue(maximum is ResolverResult.Answer)
        assertEquals(4_096, (maximum as ResolverResult.Answer).dnsMessage.size)
        assertFailure(runtime, query, ResolverFailureReason.OVERSIZED_RESPONSE)
        assertFailure(runtime, query, ResolverFailureReason.OVERSIZED_RESPONSE)
    }

    @Test
    fun `missing full epoch gate would admit a foreign request`() {
        val fixture = tlsFixture()
        val admission = admissionRuntime(fixture)
        val foreignEpoch = admission.epoch.copy(policyRevision = admission.epoch.policyRevision + 1)

        assertRejectedBeforeAdmission(
            admission,
            request(query(), epoch = foreignEpoch, networkContext = admission.networkContext),
        )
    }

    @Test
    fun `missing network generation gate would admit a foreign request`() {
        val fixture = tlsFixture()
        val admission = admissionRuntime(fixture)
        val foreignGeneration = admission.epoch.networkGeneration + 1
        val foreignEpoch = admission.epoch.copy(networkGeneration = foreignGeneration)
        val foreignContext = NetworkContext(
            network = admission.networkContext.network,
            networkGeneration = foreignGeneration,
            dnsServers = admission.networkContext.dnsServers,
            isCellular = admission.networkContext.isCellular,
        )

        assertRejectedBeforeAdmission(
            admission,
            request(query(), epoch = foreignEpoch, networkContext = foreignContext),
        )
    }

    @Test
    fun `missing exact Network gate would admit a foreign request`() {
        val fixture = tlsFixture()
        val admission = admissionRuntime(fixture)
        val foreignContext = NetworkContext(
            network = network(10),
            networkGeneration = admission.networkContext.networkGeneration,
            dnsServers = admission.networkContext.dnsServers,
            isCellular = admission.networkContext.isCellular,
        )

        assertRejectedBeforeAdmission(
            admission,
            request(query(), epoch = admission.epoch, networkContext = foreignContext),
        )
    }

    @Test
    fun `request-owned query remains immutable across a suspended transport`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val query = query(id = 0x1234)
        val request = request(query)
        val fixture = raceRuntime(
            callFactory = Call.Factory { httpRequest ->
                LatchingCall(
                    request = httpRequest,
                    responseBytes = answer(canonical(request.query)),
                    entered = entered,
                    release = release,
                )
            },
        )

        val pending = async(Dispatchers.IO) { fixture.runtime.resolver.resolve(request) }
        try {
            await(entered, "suspended transport")
            query.fill(0)
            val copiedRequestQuery = request.query
            copiedRequestQuery.fill(0)
            release.countDown()
            val result = withTimeout(DEADLOCK_GUARD_MILLIS) { pending.await() }

            assertTrue("expected answer but was $result", result is ResolverResult.Answer)
            result as ResolverResult.Answer
            assertEquals(0x12, result.dnsMessage[0].toInt() and 0xff)
            assertEquals(0x34, result.dnsMessage[1].toInt() and 0xff)
        } finally {
            release.countDown()
            val joined = cancelAndJoinBounded(pending)
            fixture.runtime.close()
            fixture.registry.close()
            assertTrue("immutable request cleanup did not terminate", joined)
        }
    }

    @Test
    fun `swallowing coroutine cancellation would return a resolver failure and leak ownership`() =
        runBlocking {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val callRef = AtomicReference<LatchingCall>()
            val fixture = raceRuntime(
                callFactory = Call.Factory { request ->
                    LatchingCall(request, answer(canonical(query())), entered, release)
                        .also(callRef::set)
                },
            )
            val pending = async(Dispatchers.IO) {
                fixture.runtime.resolver.resolve(request(query()))
            }

            try {
                await(entered, "call admission")
                pending.cancel()
                try {
                    withTimeout(DEADLOCK_GUARD_MILLIS) { pending.await() }
                    fail("coroutine cancellation must be rethrown")
                } catch (_: TimeoutCancellationException) {
                    fail("cancelled resolver did not terminate")
                } catch (_: CancellationException) {
                    // Expected coroutine ownership.
                }
                assertEquals(1, callRef.get().cancelCalls.get())
                assertEquals(0, fixture.registry.sizeForTesting)
            } finally {
                release.countDown()
                val joined = cancelAndJoinBounded(pending)
                fixture.runtime.close()
                fixture.registry.close()
                assertTrue("cancelled resolver cleanup did not terminate", joined)
            }
        }

    @Test
    fun `successful concurrent close returns only after the real runtime is fully drained`() =
        runBlocking {
        val serverFixture = tlsFixture()
        serverFixture.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val epoch = epoch()
        val registry = TracingRegistry(epoch)
        val trackedCalls = CopyOnWriteArrayList<CountingDelegatingCall>()
        val enqueued = CountDownLatch(2)
        val callIds = AtomicInteger()
        val runtime = MullvadDohRuntime(
            epoch = epoch,
            networkContext = networkContext(),
            registry = registry,
            protector = DohSocketProtector { true },
            binder = DohSocketBinder { _, _ -> },
            endpoint = serverFixture.endpoint,
            dns = serverFixture.dns,
            tls = clientTls,
            callFactory = { client ->
                Call.Factory { request ->
                    val callId = callIds.incrementAndGet()
                    CountingDelegatingCall(
                        delegate = client.newCall(request),
                        enqueued = enqueued,
                        onCancel = { registry.mark("wrapper cancel $callId") },
                    )
                        .also(trackedCalls::add)
                }
            },
        )
        closeables.add(registry)
        closeables.add(runtime)
        runtime.clientForTesting.dispatcher.maxRequests = 1
        runtime.clientForTesting.dispatcher.maxRequestsPerHost = 1
        val executor = runtime.clientForTesting.dispatcher.executorService
        val first = async(Dispatchers.IO) {
            runtime.resolver.resolve(request(query(id = 0x1234)))
        }
        val second = async(Dispatchers.IO) {
            runtime.resolver.resolve(request(query(id = 0x5678)))
        }
        val closeGate = CountDownLatch(1)
        val closeReturned = CountDownLatch(2)
        val closeFailures = CopyOnWriteArrayList<Throwable>()
        val snapshots = CopyOnWriteArrayList<CloseSnapshot>()
        val closeActors = List(2) {
            Thread {
                await(closeGate, "concurrent close gate")
                try {
                    runtime.close()
                    snapshots += CloseSnapshot(
                        registryResources = registry.size,
                        runningCalls = runtime.clientForTesting.dispatcher.runningCallsCount(),
                        queuedCalls = runtime.clientForTesting.dispatcher.queuedCallsCount(),
                        poolConnections = runtime.connectionPoolForTesting.connectionCount(),
                        idlePoolConnections =
                            runtime.connectionPoolForTesting.idleConnectionCount(),
                        executorTerminated = executor.isTerminated,
                    )
                } catch (failure: Throwable) {
                    closeFailures += failure
                } finally {
                    closeReturned.countDown()
                }
            }.also(Thread::start)
        }

        try {
            await(enqueued, "real calls enqueued")
            assertTrue(serverFixture.server.takeRequest(2, TimeUnit.SECONDS) != null)
            assertEquals(1, runtime.clientForTesting.dispatcher.runningCallsCount())
            assertEquals(1, runtime.clientForTesting.dispatcher.queuedCallsCount())

            closeGate.countDown()
            await(closeReturned, "concurrent runtime close")
            closeActors.forEach { join(it, "concurrent close actor") }

            assertTrue("close failures: $closeFailures", closeFailures.isEmpty())
            assertEquals(2, snapshots.size)
            snapshots.forEach { snapshot ->
                assertEquals("registry resources at close return", 0, snapshot.registryResources)
                assertEquals("running calls at close return", 0, snapshot.runningCalls)
                assertEquals("queued calls at close return", 0, snapshot.queuedCalls)
                assertEquals("pool connections at close return", 0, snapshot.poolConnections)
                assertEquals(
                    "idle pool connections at close return",
                    0,
                    snapshot.idlePoolConnections,
                )
                assertTrue("executor must be terminated at close return", snapshot.executorTerminated)
            }
            assertEquals(listOf(1, 1), trackedCalls.map { it.cancelCalls.get() }.sorted())
        } finally {
            closeGate.countDown()
            closeActors.forEach {
                if (it.isAlive) join(it, "concurrent close cleanup")
            }
            val firstJoined = cancelAndJoinBounded(first)
            val secondJoined = cancelAndJoinBounded(second)
            runtime.close()
            registry.close()
            assertTrue("first real call cleanup did not terminate", firstJoined)
            assertTrue("second real call cleanup did not terminate", secondJoined)
        }
    }

    @Test
    fun `mapping registry cancellation to socket failure would swallow coroutine ownership`() {
        val cancellation = CancellationException("registry cancelled")
        val registry = ConsumingCancellationRegistry(cancellation)
        val callRef = AtomicReference<LatchingCall>()
        val runtime = runtimeWithRegistry(
            registry = registry,
            callFactory = Call.Factory { request ->
                LatchingCall(request, answer(canonical(query()))).also(callRef::set)
            },
        )

        try {
            runBlocking { runtime.resolver.resolve(request(query())) }
            fail("registry CancellationException must be rethrown")
        } catch (failure: CancellationException) {
            assertSame(cancellation, failure)
        } finally {
            runtime.close()
            registry.close()
        }
        assertEquals(1, callRef.get().cancelCalls.get())
    }

    @Test
    fun `releasing an admitted call after enqueue throws would skip physical cancellation`() {
        val enqueueFailure = IOException("enqueue failed")
        val callRef = AtomicReference<LatchingCall>()
        val fixture = raceRuntime(
            callFactory = Call.Factory { request ->
                LatchingCall(
                    request = request,
                    responseBytes = answer(canonical(query())),
                    enqueueFailure = enqueueFailure,
                ).also(callRef::set)
            },
        )

        try {
            val result = runBlocking { fixture.runtime.resolver.resolve(request(query())) }

            assertTrue("expected typed failure but was $result", result is ResolverResult.Failure)
            assertEquals(
                ResolverFailureReason.RECEIVE_FAILED,
                (result as ResolverResult.Failure).reason,
            )
            assertEquals(1, callRef.get().cancelCalls.get())
            assertEquals(0, fixture.registry.sizeForTesting)
        } finally {
            fixture.runtime.close()
            fixture.registry.close()
        }
    }

    @Test
    fun `terminal publication is atomic with queued epoch retirement`() =
        runBlocking {
            val callRef = AtomicReference<LatchingCall>()
            val epoch = epoch()
            val registry = AtomicCompletionRegistry(epoch)
            val runtime = runtimeWithRegistry(
                registry = registry,
                callFactory = Call.Factory { request ->
                    LatchingCall(
                        request = request,
                        responseBytes = answer(canonical(query())),
                    ).also(callRef::set)
                },
            )
            val pending = async(Dispatchers.IO) {
                runtime.resolver.resolve(request(query()))
            }
            val successor = AutoCloseable {}
            val advance = Thread {
                registry.advanceTo(successorEpoch())
            }

            try {
                await(registry.completionReached, "atomic terminal completion")
                advance.start()
                registry.awaitAdvanceQueued(advance)
                registry.releaseCompletion.countDown()
                join(advance, "epoch advance")
                assertTrue(registry.registerIfCurrent(successor, successorEpoch()))
                assertTrue(registry.unregister(successor))
                successor.close()
                val result = withTimeout(DEADLOCK_GUARD_MILLIS) { pending.await() }
                assertTrue(
                    "atomic publication must win before queued retirement",
                    result is ResolverResult.Answer,
                )
                assertEquals(0, callRef.get().cancelCalls.get())
                assertEquals(0, registry.sizeForTesting)
            } finally {
                registry.releaseCompletion.countDown()
                if (advance.isAlive) join(advance, "epoch advance cleanup")
                val joined = cancelAndJoinBounded(pending)
                runtime.close()
                registry.unregister(successor)
                successor.close()
                registry.close()
                assertTrue("retired response cleanup did not terminate", joined)
            }
        }

    @Test
    fun `removing the final runtime ownership gate would publish after close`() = runBlocking {
        val registry = BlockingUnregisterRegistry(epoch())
        val callRef = AtomicReference<LatchingCall>()
        val closeCancellation = CountDownLatch(1)
        val closeFailure = AtomicReference<Throwable?>()
        val runtime = runtimeWithRegistry(
            registry = registry,
            callFactory = Call.Factory { request ->
                LatchingCall(
                    request,
                    answer(canonical(query())),
                    onCancel = closeCancellation::countDown,
                ).also(callRef::set)
            },
        )
        val pending = async(Dispatchers.IO) {
            runtime.resolver.resolve(request(query()))
        }
        val closeActor = Thread {
            try {
                runtime.close()
            } catch (failure: Throwable) {
                closeFailure.set(failure)
            }
        }

        try {
            await(registry.handbackReached, "terminal registry handback")
            closeActor.start()
            await(closeCancellation, "runtime close cancellation")
            registry.releaseHandback.countDown()
            join(closeActor, "runtime close")
            assertNull(closeFailure.get())
            val result = withTimeout(DEADLOCK_GUARD_MILLIS) { pending.await() }
            assertFalse("closed runtime must not publish", result is ResolverResult.Answer)
            assertEquals(1, callRef.get().cancelCalls.get())
            assertEquals(0, registry.sizeForTesting)
        } finally {
            registry.releaseHandback.countDown()
            if (closeActor.isAlive) join(closeActor, "runtime close cleanup")
            val joined = cancelAndJoinBounded(pending)
            runtime.close()
            registry.close()
            assertTrue("closed response cleanup did not terminate", joined)
        }
    }

    @Test
    fun `installing a permissive hostname verifier would accept the wrong TLS identity`() {
        val wrongCertificate = HeldCertificate.Builder()
            .commonName("wrong.example")
            .addSubjectAlternativeName("wrong.example")
            .build()
        val wrongServerCertificates = HandshakeCertificates.Builder()
            .heldCertificate(wrongCertificate)
            .build()
        val trustWrongCertificate = HandshakeCertificates.Builder()
            .addTrustedCertificate(wrongCertificate.certificate)
            .build()
        val fixture = tlsFixture(wrongServerCertificates)
        fixture.server.enqueue(dnsResponse(answer(canonical(query()))))
        val runtimeFixture = runtimeFixture(
            fixture,
            MullvadDohTlsConfiguration(
                trustWrongCertificate.sslSocketFactory(),
                trustWrongCertificate.trustManager,
            ),
        )
        val runtime = runtimeFixture.runtime

        assertFailure(runtime, query(), ResolverFailureReason.TLS_FAILED)
        assertEquals(0, fixture.server.requestCount)
        assertEquals(0, runtimeFixture.registry.sizeForTesting)
    }

    @Test
    fun `replacing the whole-call timeout with connect-only timeout would leave work active`() {
        val fixture = tlsFixture()
        fixture.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val epoch = epoch()
        val registry = TracingRegistry(epoch)
        val networkContext = networkContext()
        val runtime = MullvadDohRuntime(
            epoch = epoch,
            networkContext = networkContext,
            registry = registry,
            protector = DohSocketProtector { true },
            binder = DohSocketBinder { _, _ -> },
            endpoint = fixture.endpoint,
            dns = fixture.dns,
            tls = clientTls,
        )
        closeables.add(registry)
        closeables.add(runtime)
        var ownedAtReturn = -1
        var traceAtReturn = ""
        val result = try {
            runBlocking {
                withTimeoutOrNull(DEADLOCK_GUARD_MILLIS) {
                    runtime.resolver.resolve(request(query()))
                }
            }.also {
                registry.mark("bounded resolver returned")
                ownedAtReturn = registry.size
                traceAtReturn = registry.trace()
            }
        } finally {
            runtime.close()
            registry.close()
        }

        assertTrue("whole-call timeout did not terminate within guard", result != null)
        assertTrue("expected timeout but was $result", result is ResolverResult.Failure)
        assertEquals(ResolverFailureReason.TIMEOUT, (result as ResolverResult.Failure).reason)
        assertEquals(traceAtReturn, 0, ownedAtReturn)
    }

    @Test
    fun `omitting pool eviction or executor shutdown would leak runtime resources`() = runBlocking {
        val fixture = tlsFixture()
        fixture.server.enqueue(dnsResponse(answer(canonical(query()))))
        val runtimeFixture = runtimeFixture(fixture)
        val runtime = runtimeFixture.runtime
        assertTrue(runtime.resolver.resolve(request(query())) is ResolverResult.Answer)
        val executor = runtime.clientForTesting.dispatcher.executorService

        runtime.close()

        assertTrue(executor.isShutdown)
        assertEquals(0, runtime.connectionPoolForTesting.connectionCount())
        assertEquals(0, runtime.connectionPoolForTesting.idleConnectionCount())
        assertEquals(0, runtimeFixture.registry.sizeForTesting)
    }

    @Test
    fun `split cleanup ownership would double cancel during cancellation close and retirement`() =
        runBlocking {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val actorGate = CountDownLatch(1)
            val callRef = AtomicReference<LatchingCall>()
            val fixture = raceRuntime(
                callFactory = Call.Factory { request ->
                    LatchingCall(request, answer(canonical(query())), entered, release)
                        .also(callRef::set)
                },
            )
            val pending = async(Dispatchers.IO) {
                fixture.runtime.resolver.resolve(request(query()))
            }
            val closeActor = Thread {
                await(actorGate, "race actor gate")
                fixture.runtime.close()
            }
            val retireActor = Thread {
                await(actorGate, "race actor gate")
                fixture.registry.advanceTo(successorEpoch())
            }
            val cancelActor = Thread {
                await(actorGate, "race actor gate")
                pending.cancel()
            }

            try {
                await(entered, "blocked call")
                closeActor.start()
                retireActor.start()
                cancelActor.start()
                actorGate.countDown()
                join(closeActor, "runtime close actor")
                join(retireActor, "registry retirement actor")
                join(cancelActor, "coroutine cancellation actor")
                val terminal = runCatching {
                    withTimeout(DEADLOCK_GUARD_MILLIS) { pending.await() }
                }
                terminal.onSuccess {
                    assertFalse("concurrent teardown must not publish an answer", it is ResolverResult.Answer)
                }
                terminal.exceptionOrNull()?.let {
                    assertTrue(
                        "unexpected terminal exception $it",
                        it is CancellationException && it !is TimeoutCancellationException,
                    )
                }
                assertEquals(1, callRef.get().cancelCalls.get())
                assertEquals(0, fixture.registry.sizeForTesting)
            } finally {
                actorGate.countDown()
                release.countDown()
                if (closeActor.isAlive) join(closeActor, "runtime close cleanup")
                if (retireActor.isAlive) join(retireActor, "registry retirement cleanup")
                if (cancelActor.isAlive) join(cancelActor, "coroutine cancellation cleanup")
                val joined = cancelAndJoinBounded(pending)
                fixture.runtime.close()
                fixture.registry.close()
                assertTrue("concurrent resolver cleanup did not terminate", joined)
            }
        }

    private fun runtime(
        fixture: TlsFixture,
        tls: MullvadDohTlsConfiguration = clientTls,
    ): MullvadDohRuntime = runtimeFixture(fixture, tls).runtime

    private fun runtimeFixture(
        fixture: TlsFixture,
        tls: MullvadDohTlsConfiguration = clientTls,
    ): RaceFixture {
        val epoch = epoch()
        val registry = GenerationSocketRegistry(epoch)
        val networkContext = networkContext()
        closeables.add(registry)
        val runtime = MullvadDohRuntime(
            epoch = epoch,
            networkContext = networkContext,
            registry = registry,
            protector = DohSocketProtector { true },
            binder = DohSocketBinder { _, boundNetwork ->
                assertSame(networkContext.network, boundNetwork)
            },
            endpoint = fixture.endpoint,
            dns = fixture.dns,
            tls = tls,
        ).also(closeables::add)
        return RaceFixture(runtime, registry)
    }

    private fun raceRuntime(
        callFactory: Call.Factory,
    ): RaceFixture {
        val epoch = epoch()
        val registry = GenerationSocketRegistry(epoch)
        val networkContext = networkContext()
        closeables.add(registry)
        val runtime = MullvadDohRuntime(
            epoch = epoch,
            networkContext = networkContext,
            registry = registry,
            protector = DohSocketProtector { true },
            binder = DohSocketBinder { _, _ -> },
            callFactory = { callFactory },
        ).also(closeables::add)
        return RaceFixture(runtime, registry)
    }

    private fun runtimeWithRegistry(
        registry: ResolverResourceRegistry,
        callFactory: Call.Factory,
    ): MullvadDohRuntime =
        MullvadDohRuntime(
            epoch = epoch(),
            networkContext = networkContext(),
            registry = registry,
            protector = DohSocketProtector { true },
            binder = DohSocketBinder { _, _ -> },
            callFactory = { callFactory },
        ).also(closeables::add)

    private fun admissionRuntime(fixture: TlsFixture): AdmissionFixture {
        val epoch = epoch()
        val networkContext = networkContext()
        val registry = AdmissionRecordingRegistry()
        val protectCalls = AtomicInteger()
        val bindCalls = AtomicInteger()
        val callCreations = AtomicInteger()
        closeables.add(registry)
        val runtime = MullvadDohRuntime(
            epoch = epoch,
            networkContext = networkContext,
            registry = registry,
            protector = DohSocketProtector {
                protectCalls.incrementAndGet()
                true
            },
            binder = DohSocketBinder { _, _ -> bindCalls.incrementAndGet() },
            endpoint = fixture.endpoint,
            dns = fixture.dns,
            tls = clientTls,
            callFactory = {
                Call.Factory { request ->
                    callCreations.incrementAndGet()
                    LatchingCall(request, answer(canonical(query())))
                }
            },
        ).also(closeables::add)
        return AdmissionFixture(
            runtime = runtime,
            epoch = epoch,
            networkContext = networkContext,
            registry = registry,
            protectCalls = protectCalls,
            bindCalls = bindCalls,
            callCreations = callCreations,
            server = fixture.server,
        )
    }

    private fun assertRejectedBeforeAdmission(
        admission: AdmissionFixture,
        request: ResolverRequest,
    ) {
        admission.server.enqueue(dnsResponse(answer(canonical(request.query))))

        val result = runBlocking { admission.runtime.resolver.resolve(request) }

        assertTrue("expected stale failure but was $result", result is ResolverResult.Failure)
        assertEquals(ResolverFailureReason.STALE_EPOCH, (result as ResolverResult.Failure).reason)
        assertEquals(0, admission.server.requestCount)
        assertEquals(0, admission.callCreations.get())
        assertEquals(0, admission.registry.registerCalls)
        assertEquals(0, admission.protectCalls.get())
        assertEquals(0, admission.bindCalls.get())
    }

    private fun tlsFixture(
        certificates: HandshakeCertificates = serverCertificates,
    ): TlsFixture {
        val server = MockWebServer().also {
            it.protocols = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
            it.useHttps(certificates.sslSocketFactory(), false)
            it.start()
            closeables.add(AutoCloseable { it.shutdown() })
        }
        val endpoint = server.url("/dns-query").newBuilder()
            .host(MullvadDohEndpoint.url.host)
            .build()
        val loopback = InetAddress.getByName("127.0.0.1")
        val lookedUpHosts = mutableListOf<String>()
        val dns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (hostname != MullvadDohEndpoint.url.host) {
                    throw java.net.UnknownHostException(hostname)
                }
                lookedUpHosts += hostname
                return listOf(loopback)
            }
        }
        return TlsFixture(server, endpoint, dns, lookedUpHosts)
    }

    private fun assertAnswer(result: ResolverResult, query: ByteArray, rcode: Int) {
        assertTrue("expected answer but was $result", result is ResolverResult.Answer)
        result as ResolverResult.Answer
        assertEquals(ResolverTransport.MULLVAD_DOH, result.transport)
        assertEquals(query[0], result.dnsMessage[0])
        assertEquals(query[1], result.dnsMessage[1])
        assertEquals(rcode, result.dnsMessage[3].toInt() and 0x0f)
    }

    private fun assertFailure(
        runtime: MullvadDohRuntime,
        query: ByteArray,
        expected: ResolverFailureReason,
    ) {
        val result = runBlocking { runtime.resolver.resolve(request(query)) }
        assertTrue("expected failure but was $result", result is ResolverResult.Failure)
        assertEquals(expected, (result as ResolverResult.Failure).reason)
    }

    private fun dnsResponse(
        body: ByteArray,
        contentType: String = "application/dns-message",
        status: Int = 200,
    ): MockResponse = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", contentType)
        .setBody(Buffer().write(body))

    private fun canonical(query: ByteArray): ByteArray = query.copyOf().also {
        it[0] = 0
        it[1] = 0
    }

    private fun answer(
        query: ByteArray,
        responseId: Int = 0,
        rcode: Int = 0,
        truncated: Boolean = false,
    ): ByteArray = query.copyOf().also {
        it[0] = (responseId ushr 8).toByte()
        it[1] = responseId.toByte()
        it[2] = if (truncated) 0x83.toByte() else 0x81.toByte()
        it[3] = (0x80 or rcode).toByte()
    }

    private fun answerWithSize(query: ByteArray, size: Int): ByteArray {
        val rdataLength = size - query.size - 12
        require(rdataLength in 0..0xffff)
        return ByteArray(size).also { response ->
            answer(query).copyInto(response)
            response[6] = 0
            response[7] = 1
            var cursor = query.size
            response[cursor++] = 0xc0.toByte()
            response[cursor++] = 0x0c
            response[cursor++] = 0x00
            response[cursor++] = 0x10
            response[cursor++] = 0x00
            response[cursor++] = 0x01
            cursor += 4
            response[cursor++] = (rdataLength ushr 8).toByte()
            response[cursor] = rdataLength.toByte()
        }
    }

    private fun request(
        query: ByteArray,
        epoch: EngineEpoch = epoch(),
        networkContext: NetworkContext = networkContext(),
    ): ResolverRequest = ResolverRequest(
        query = query,
        question = DnsMessage.parseQuestion(query),
        epoch = epoch,
        networkContext = networkContext,
    )

    private fun query(id: Int = 0x1234): ByteArray = byteArrayOf(
        (id ushr 8).toByte(), id.toByte(), 0x01, 0x00, 0x00, 0x01,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x07, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
        'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
        0x03, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0x00,
        0x00, 0x01, 0x00, 0x01,
    )

    private fun networkContext(): NetworkContext = NetworkContext(
        network = network(9),
        networkGeneration = 5,
        dnsServers = listOf(InetAddress.getByName("192.0.2.53")),
        isCellular = false,
    )

    private fun epoch(): EngineEpoch = EngineEpoch(
        sessionGeneration = 2,
        configurationIdentity = ConfigurationIdentity(3, "config"),
        artifactDigest = "digest",
        policyRevision = 4,
        networkGeneration = 5,
        engineGeneration = 1,
        primaryResolverIdentity = PrimaryResolverIdentity("device-dns"),
        fallbackCoverage = ResolverFallbackCoverage.NONE,
    )

    private fun successorEpoch(): EngineEpoch =
        epoch().copy(engineGeneration = epoch().engineGeneration + 1)

    private fun await(latch: CountDownLatch, label: String) {
        assertTrue("$label timed out", latch.await(DEADLOCK_GUARD_MILLIS, TimeUnit.MILLISECONDS))
    }

    private fun join(thread: Thread, label: String) {
        thread.join(DEADLOCK_GUARD_MILLIS)
        assertFalse("$label did not terminate", thread.isAlive)
    }

    private suspend fun cancelAndJoinBounded(job: Job): Boolean {
        job.cancel()
        return withTimeoutOrNull(DEADLOCK_GUARD_MILLIS) {
            job.join()
            true
        } ?: false
    }

    private fun network(id: Int): Network = Network::class.java
        .getDeclaredConstructor(Int::class.javaPrimitiveType)
        .apply { isAccessible = true }
        .newInstance(id)

    private data class TlsFixture(
        val server: MockWebServer,
        val endpoint: HttpUrl,
        val dns: Dns,
        val lookedUpHosts: List<String>,
    )

    private data class AdmissionFixture(
        val runtime: MullvadDohRuntime,
        val epoch: EngineEpoch,
        val networkContext: NetworkContext,
        val registry: AdmissionRecordingRegistry,
        val protectCalls: AtomicInteger,
        val bindCalls: AtomicInteger,
        val callCreations: AtomicInteger,
        val server: MockWebServer,
    )

    private data class RaceFixture(
        val runtime: MullvadDohRuntime,
        val registry: GenerationSocketRegistry,
    )

    private data class CloseSnapshot(
        val registryResources: Int,
        val runningCalls: Int,
        val queuedCalls: Int,
        val poolConnections: Int,
        val idlePoolConnections: Int,
        val executorTerminated: Boolean,
    )

    private inner class LatchingCall(
        private val request: Request,
        private val responseBytes: ByteArray,
        private val entered: CountDownLatch? = null,
        private val release: CountDownLatch? = null,
        private val cancelEntered: CountDownLatch? = null,
        private val releaseCancel: CountDownLatch? = null,
        private val onCancel: () -> Unit = {},
        private val enqueueFailure: Throwable? = null,
    ) : Call {
        val cancelCalls = AtomicInteger()
        private val executed = AtomicBoolean(false)
        private val canceled = AtomicBoolean(false)

        override fun request(): Request = request

        override fun execute(): Response = error("synchronous execution is not used")

        override fun enqueue(responseCallback: Callback) {
            check(executed.compareAndSet(false, true))
            enqueueFailure?.let { throw it }
            entered?.countDown()
            release?.let { await(it, "call release") }
            if (canceled.get()) {
                responseCallback.onFailure(this, IOException("Canceled"))
            } else {
                responseCallback.onResponse(
                    this,
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/dns-message")
                        .body(
                            responseBytes.toResponseBody(
                                "application/dns-message".toMediaType(),
                            ),
                        )
                        .build(),
                )
            }
        }

        override fun cancel() {
            cancelCalls.incrementAndGet()
            canceled.set(true)
            onCancel()
            cancelEntered?.countDown()
            releaseCancel?.let { await(it, "call cancel return") }
            release?.countDown()
        }

        override fun isExecuted(): Boolean = executed.get()

        override fun isCanceled(): Boolean = canceled.get()

        override fun timeout(): Timeout = Timeout.NONE

        override fun clone(): Call = LatchingCall(
            request = request,
            responseBytes = responseBytes,
            entered = entered,
            release = release,
            cancelEntered = cancelEntered,
            releaseCancel = releaseCancel,
            onCancel = onCancel,
            enqueueFailure = enqueueFailure,
        )
    }

    private class CountingDelegatingCall(
        private val delegate: Call,
        private val enqueued: CountDownLatch,
        private val onCancel: () -> Unit,
    ) : Call by delegate {
        val cancelCalls = AtomicInteger()

        override fun enqueue(responseCallback: Callback) {
            delegate.enqueue(responseCallback)
            enqueued.countDown()
        }

        override fun cancel() {
            cancelCalls.incrementAndGet()
            onCancel()
            delegate.cancel()
        }

        override fun clone(): Call = CountingDelegatingCall(delegate.clone(), enqueued, onCancel)
    }

    private class ConsumingCancellationRegistry(
        private val cancellation: CancellationException,
    ) : ResolverResourceRegistry, AutoCloseable {
        override fun registerIfCurrent(resource: AutoCloseable, epoch: EngineEpoch): Boolean {
            resource.close()
            throw cancellation
        }

        override fun isCurrent(epoch: EngineEpoch): Boolean = true

        override fun unregister(resource: AutoCloseable): Boolean = false

        override fun completeIfCurrent(
            resource: AutoCloseable,
            epoch: EngineEpoch,
            completion: () -> Boolean,
        ): Boolean = false

        override fun close() = Unit
    }

    private class BlockingUnregisterRegistry(
        initialEpoch: EngineEpoch,
    ) : ResolverResourceRegistry, AutoCloseable {
        private val delegate = GenerationSocketRegistry(initialEpoch)
        private val blockOnce = AtomicBoolean(true)
        val handbackReached = CountDownLatch(1)
        val releaseHandback = CountDownLatch(1)

        override fun registerIfCurrent(resource: AutoCloseable, epoch: EngineEpoch): Boolean =
            delegate.registerIfCurrent(resource, epoch)

        override fun isCurrent(epoch: EngineEpoch): Boolean = delegate.isCurrent(epoch)

        override fun unregister(resource: AutoCloseable): Boolean {
            val released = delegate.unregister(resource)
            if (
                released &&
                resource.javaClass.name.contains("RegistryOwnedCall") &&
                blockOnce.compareAndSet(true, false)
            ) {
                handbackReached.countDown()
                check(
                    releaseHandback.await(
                        DEADLOCK_GUARD_MILLIS,
                        TimeUnit.MILLISECONDS,
                    ),
                ) {
                    "terminal registry handback timed out"
                }
            }
            return released
        }

        override fun completeIfCurrent(
            resource: AutoCloseable,
            epoch: EngineEpoch,
            completion: () -> Boolean,
        ): Boolean = delegate.completeIfCurrent(resource, epoch) {
            if (
                resource.javaClass.name.contains("RegistryOwnedCall") &&
                blockOnce.compareAndSet(true, false)
            ) {
                handbackReached.countDown()
                check(
                    releaseHandback.await(
                        DEADLOCK_GUARD_MILLIS,
                        TimeUnit.MILLISECONDS,
                    ),
                ) {
                    "terminal registry handback timed out"
                }
            }
            completion()
        }

        fun advanceTo(nextEpoch: EngineEpoch) = delegate.advanceTo(nextEpoch)

        val sizeForTesting: Int
            get() = delegate.sizeForTesting

        override fun close() = delegate.close()
    }

    private class AtomicCompletionRegistry(
        initialEpoch: EngineEpoch,
    ) : ResolverResourceRegistry, AutoCloseable {
        private val delegate = GenerationSocketRegistry(initialEpoch)
        private val completionGate = ReentrantLock()
        val completionReached = CountDownLatch(1)
        val releaseCompletion = CountDownLatch(1)

        override fun registerIfCurrent(resource: AutoCloseable, epoch: EngineEpoch): Boolean =
            delegate.registerIfCurrent(resource, epoch)

        override fun isCurrent(epoch: EngineEpoch): Boolean = completionGate.withLock {
            delegate.isCurrent(epoch)
        }

        override fun unregister(resource: AutoCloseable): Boolean = completionGate.withLock {
            delegate.unregister(resource)
        }

        override fun completeIfCurrent(
            resource: AutoCloseable,
            epoch: EngineEpoch,
            completion: () -> Boolean,
        ): Boolean = completionGate.withLock {
            delegate.completeIfCurrent(resource, epoch) {
                completionReached.countDown()
                check(
                    releaseCompletion.await(
                        DEADLOCK_GUARD_MILLIS,
                        TimeUnit.MILLISECONDS,
                    ),
                ) {
                    "atomic completion release timed out"
                }
                completion()
            }
        }

        fun advanceTo(nextEpoch: EngineEpoch) = completionGate.withLock {
            delegate.advanceTo(nextEpoch)
        }

        fun awaitAdvanceQueued(thread: Thread) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                DEADLOCK_GUARD_MILLIS,
            )
            while (!completionGate.hasQueuedThread(thread) && System.nanoTime() < deadline) {
                Thread.onSpinWait()
            }
            assertTrue(
                "epoch advance did not queue behind atomic completion",
                completionGate.hasQueuedThread(thread),
            )
        }

        val sizeForTesting: Int
            get() = delegate.sizeForTesting

        override fun close() = delegate.close()
    }

    private class AdmissionRecordingRegistry : ResolverResourceRegistry, AutoCloseable {
        private val owned = linkedSetOf<AutoCloseable>()
        var registerCalls: Int = 0
            private set

        override fun registerIfCurrent(resource: AutoCloseable, epoch: EngineEpoch): Boolean =
            synchronized(this) {
                registerCalls += 1
                owned += resource
                true
            }

        override fun isCurrent(epoch: EngineEpoch): Boolean = true

        override fun unregister(resource: AutoCloseable): Boolean =
            synchronized(this) { owned.remove(resource) }

        override fun completeIfCurrent(
            resource: AutoCloseable,
            epoch: EngineEpoch,
            completion: () -> Boolean,
        ): Boolean = synchronized(this) {
            if (!owned.contains(resource)) return@synchronized false
            val completed = runCatching(completion).getOrDefault(false)
            owned.remove(resource)
            if (!completed) runCatching { resource.close() }
            completed
        }

        override fun close() {
            val resources = synchronized(this) {
                owned.toList().also { owned.clear() }
            }
            resources.forEach { runCatching { it.close() } }
        }
    }

    private class TracingRegistry(initialEpoch: EngineEpoch) :
        ResolverResourceRegistry,
        AutoCloseable {
        private val delegate = GenerationSocketRegistry(initialEpoch)
        private val lock = Any()
        private val resources = java.util.IdentityHashMap<AutoCloseable, String>()
        private val events = mutableListOf<String>()

        override fun registerIfCurrent(resource: AutoCloseable, epoch: EngineEpoch): Boolean {
            val accepted = delegate.registerIfCurrent(resource, epoch)
            synchronized(lock) {
                events += "register ${resource.javaClass.name}: $accepted"
                if (accepted) resources[resource] = resource.javaClass.name
            }
            return accepted
        }

        override fun isCurrent(epoch: EngineEpoch): Boolean = delegate.isCurrent(epoch)

        override fun unregister(resource: AutoCloseable): Boolean {
            val released = delegate.unregister(resource)
            synchronized(lock) {
                events += "unregister ${resource.javaClass.name}: $released"
                if (released) resources.remove(resource)
            }
            return released
        }

        override fun completeIfCurrent(
            resource: AutoCloseable,
            epoch: EngineEpoch,
            completion: () -> Boolean,
        ): Boolean {
            val completed = delegate.completeIfCurrent(resource, epoch) {
                synchronized(lock) {
                    events += "complete ${resource.javaClass.name}"
                    resources.remove(resource)
                }
                completion()
            }
            synchronized(lock) { events += "complete result: $completed" }
            return completed
        }

        fun mark(label: String) {
            synchronized(lock) { events += "$label; owned=${resources.values}" }
        }

        fun trace(): String = synchronized(lock) { events.joinToString("\n") }

        val size: Int
            get() = synchronized(lock) { resources.size }

        override fun close() {
            mark("registry close")
            delegate.close()
            synchronized(lock) { resources.clear() }
        }
    }

    private companion object {
        const val DEADLOCK_GUARD_MILLIS = 10_000L
    }
}
