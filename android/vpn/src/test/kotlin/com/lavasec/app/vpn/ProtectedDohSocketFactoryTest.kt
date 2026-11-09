package com.lavasec.app.vpn

import android.net.Network
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.health.PrimaryResolverIdentity
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.platform.ConfigurationIdentity
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProtectedDohSocketFactoryTest {
    @Test
    fun `materializes registers protects binds and gates one unconnected tracked socket in order`() {
        val events = mutableListOf<String>()
        val registry = RecordingRegistry(events)
        val network = network(42)
        val factory = ProtectedDohSocketFactory.withHooks(
            epoch = epoch(),
            network = network,
            registry = registry,
            hooks = hooks(events),
            protector = DohSocketProtector { socket ->
                events += "protect"
                assertTrue(socket.isBound)
                assertFalse(socket.isConnected)
                true
            },
            binder = DohSocketBinder { socket, bound ->
                events += "bind"
                assertSame(network, bound)
                assertTrue(socket.isBound)
                assertFalse(socket.isConnected)
            },
        )

        factory.createSocket().close()

        assertEquals(listOf("materialize", "register", "protect", "bind", "gate"), events.take(5))
        assertEquals(0, registry.ownedCount)
    }

    @Test
    fun `materialization failure closes the partially built socket and remains typed`() {
        val events = mutableListOf<String>()
        val materializeFailure = IOException("materialize")
        val physicalCloseCount = AtomicInteger()
        val factory = factory(
            registry = RecordingRegistry(events),
            hooks = hooks(
                events = events,
                materialize = {
                    events += "materialize"
                    throw materializeFailure
                },
                physicalClose = { physicalCloseCount.incrementAndGet() },
            ),
        )

        assertSocketFailure(ResolverFailureReason.SOCKET_FAILED, materializeFailure) {
            factory.createSocket()
        }

        assertEquals(listOf("materialize"), events)
        assertEquals(1, physicalCloseCount.get())
    }

    @Test
    fun `preconnected overloads reject before materializing any socket`() {
        val events = mutableListOf<String>()
        val factory = factory(hooks = hooks(events))
        val address = InetAddress.getByName("192.0.2.1")

        assertUnsupported { factory.createSocket("dns.mullvad.net", 443) }
        assertUnsupported { factory.createSocket(address, 443) }
        assertUnsupported { factory.createSocket("dns.mullvad.net", 443, address, 0) }
        assertUnsupported { factory.createSocket(address, 443, address, 0) }

        assertTrue(events.isEmpty())
    }

    @Test
    fun `registration rejection and throw rely on registry consumption and remain typed`() {
        listOf(
            RegistrationCase("rejected", false, null, ResolverFailureReason.STALE_EPOCH),
            RegistrationCase("throws", true, IOException("register"), ResolverFailureReason.SOCKET_FAILED),
        ).forEach { case ->
            val events = mutableListOf<String>()
            val physicalCloseCount = AtomicInteger()
            val registry = RecordingRegistry(
                events = events,
                acceptsRegistrations = case.accepts,
                registerFailure = case.failure,
            )
            val factory = factory(
                registry = registry,
                hooks = hooks(events, physicalClose = { physicalCloseCount.incrementAndGet() }),
            )

            assertSocketFailure(case.reason, case.failure) { factory.createSocket() }
            assertEquals("${case.name} must be consumed by the registry", 1, physicalCloseCount.get())
            assertEquals(0, registry.ownedCount)
        }
    }

    @Test
    fun `protect bind and gate failures close once while preserving their typed cause`() {
        val protectFailure = IOException("protect")
        val bindFailure = IOException("bind")
        val gateFailure = IOException("gate")
        listOf(
            FailureCase("protect false", ResolverFailureReason.PROTECT_FAILED, protector = { false }),
            FailureCase("protect throw", ResolverFailureReason.PROTECT_FAILED, protector = { throw protectFailure }, expectedCause = protectFailure),
            FailureCase("bind throw", ResolverFailureReason.BIND_FAILED, binder = { _, _ -> throw bindFailure }, expectedCause = bindFailure),
            FailureCase("gate false", ResolverFailureReason.STALE_EPOCH, current = false),
            FailureCase("gate throw", ResolverFailureReason.STALE_EPOCH, gateFailure = gateFailure, expectedCause = gateFailure),
        ).forEach { case ->
            val events = mutableListOf<String>()
            val physicalCloseCount = AtomicInteger()
            val registry = RecordingRegistry(events, current = case.current, gateFailure = case.gateFailure)
            val factory = factory(
                registry = registry,
                hooks = hooks(events, physicalClose = { physicalCloseCount.incrementAndGet() }),
                protector = DohSocketProtector { case.protector(it) },
                binder = DohSocketBinder { socket, bound -> case.binder(socket, bound) },
            )

            assertSocketFailure(case.reason, case.expectedCause) { factory.createSocket() }
            assertEquals("${case.name} must physically close once", 1, physicalCloseCount.get())
            assertEquals("${case.name} must retire ownership", 0, registry.ownedCount)
        }
    }

    @Test
    fun `cleanup closes even when unregister throws and close failure cannot replace typed failure`() {
        val events = mutableListOf<String>()
        val closeFailure = IOException("close")
        val physicalCloseCount = AtomicInteger()
        val factory = factory(
            registry = RecordingRegistry(events, unregisterFailure = IOException("unregister")),
            hooks = hooks(events, physicalClose = {
                physicalCloseCount.incrementAndGet()
                throw closeFailure
            }),
            protector = DohSocketProtector { false },
        )

        assertSocketFailure(ResolverFailureReason.PROTECT_FAILED) { factory.createSocket() }

        assertEquals(1, physicalCloseCount.get())
    }

    @Test
    fun `caller close races registry retirement through the production atomic close guard once`() {
        val registry = GenerationSocketRegistry(epoch())
        val physicalCloseCount = AtomicInteger()
        val firstPhysicalCloseEntered = CountDownLatch(1)
        val releaseFirstPhysicalClose = CountDownLatch(1)
        val factory = factory(
            registry = registry,
            hooks = hooks(mutableListOf(), physicalClose = {
                if (physicalCloseCount.incrementAndGet() == 1) {
                    firstPhysicalCloseEntered.countDown()
                    assertTrue(releaseFirstPhysicalClose.await(2, TimeUnit.SECONDS))
                }
            }),
        )
        val socket = factory.createSocket()
        val retirementDone = CountDownLatch(1)
        val callerDone = CountDownLatch(1)
        val retire = Thread {
            registry.advanceTo(epoch().copy(engineGeneration = 2))
            retirementDone.countDown()
        }
        val closeCaller = Thread {
            socket.close()
            callerDone.countDown()
        }
        try {
            retire.start()
            assertTrue(firstPhysicalCloseEntered.await(2, TimeUnit.SECONDS))
            closeCaller.start()
            assertTrue(callerDone.await(2, TimeUnit.SECONDS))
            assertEquals(1, physicalCloseCount.get())
            releaseFirstPhysicalClose.countDown()
            assertTrue(retirementDone.await(2, TimeUnit.SECONDS))
            closeCaller.join(2_000)
            retire.join(2_000)
            assertFalse(closeCaller.isAlive)
            assertFalse(retire.isAlive)
            assertEquals(1, physicalCloseCount.get())
            assertEquals(0, registry.sizeForTesting)
        } finally {
            releaseFirstPhysicalClose.countDown()
            registry.close()
        }
    }

    private fun factory(
        registry: ResolverResourceRegistry = RecordingRegistry(mutableListOf()),
        protector: DohSocketProtector = DohSocketProtector { true },
        binder: DohSocketBinder = DohSocketBinder { _, _ -> },
        hooks: DohSocketLifecycleHooks = hooks(mutableListOf()),
    ) = ProtectedDohSocketFactory.withHooks(epoch(), network(7), registry, protector, binder, hooks)

    private fun hooks(
        events: MutableList<String>,
        materialize: (Socket) -> Unit = { socket ->
            events += "materialize"
            socket.bind(InetSocketAddress(0))
        },
        physicalClose: (Socket) -> Unit = { socket ->
            events += "close"
            socket.close()
        },
    ) = DohSocketLifecycleHooks(materialize, physicalClose)

    private fun assertSocketFailure(
        expected: ResolverFailureReason,
        expectedCause: Throwable? = null,
        block: () -> Unit,
    ) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("expected ProtectedDohSocketException but was $failure", failure is ProtectedDohSocketException)
        failure as ProtectedDohSocketException
        assertEquals(expected, failure.reason)
        if (expectedCause != null) assertSame(expectedCause, failure.cause)
    }

    private fun assertUnsupported(block: () -> Unit) {
        try {
            block()
            fail("expected preconnected overload rejection")
        } catch (_: UnsupportedOperationException) {
            // Expected: only the no-argument unconnected factory path is safe.
        }
    }

    private data class RegistrationCase(
        val name: String,
        val accepts: Boolean,
        val failure: Throwable?,
        val reason: ResolverFailureReason,
    )

    private data class FailureCase(
        val name: String,
        val reason: ResolverFailureReason,
        val protector: (Socket) -> Boolean = { true },
        val binder: (Socket, Network) -> Unit = { _, _ -> },
        val current: Boolean = true,
        val gateFailure: Throwable? = null,
        val expectedCause: Throwable? = null,
    )

    private class RecordingRegistry(
        private val events: MutableList<String>,
        private val acceptsRegistrations: Boolean = true,
        private val registerFailure: Throwable? = null,
        private val current: Boolean = true,
        private val gateFailure: Throwable? = null,
        private val unregisterFailure: Throwable? = null,
    ) : ResolverResourceRegistry {
        private val owned = Collections.newSetFromMap(java.util.IdentityHashMap<AutoCloseable, Boolean>())
        val ownedCount: Int get() = synchronized(this) { owned.size }

        override fun registerIfCurrent(resource: AutoCloseable, epoch: EngineEpoch): Boolean = synchronized(this) {
            events += "register"
            registerFailure?.let {
                resource.close()
                throw it
            }
            if (!acceptsRegistrations || !current) {
                resource.close()
                return@synchronized false
            }
            owned += resource
            true
        }

        override fun isCurrent(epoch: EngineEpoch): Boolean = synchronized(this) {
            events += "gate"
            gateFailure?.let { throw it }
            current
        }

        override fun unregister(resource: AutoCloseable): Boolean = synchronized(this) {
            events += "unregister"
            unregisterFailure?.let { throw it }
            owned.remove(resource)
        }

        override fun completeIfCurrent(
            resource: AutoCloseable,
            epoch: EngineEpoch,
            completion: () -> Boolean,
        ): Boolean = synchronized(this) {
            events += "complete"
            if (!current || !owned.contains(resource)) return@synchronized false
            val completed = runCatching(completion).getOrDefault(false)
            owned.remove(resource)
            if (!completed) runCatching { resource.close() }
            completed
        }
    }

    private fun epoch() = EngineEpoch(
        1,
        ConfigurationIdentity(1, "cfg"),
        "artifact",
        1,
        1,
        1,
        PrimaryResolverIdentity("mullvad-doh"),
        ResolverFallbackCoverage.NONE,
    )

    private fun network(handle: Int): Network = Network::class.java
        .getDeclaredConstructor(Int::class.javaPrimitiveType)
        .apply { isAccessible = true }
        .newInstance(handle)
}
