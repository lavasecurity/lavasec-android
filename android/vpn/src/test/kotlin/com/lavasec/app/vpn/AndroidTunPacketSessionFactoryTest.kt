package com.lavasec.app.vpn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Network
import android.net.VpnService
import androidx.test.core.app.ApplicationProvider
import java.io.Closeable
import java.io.IOException
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidTunPacketSessionFactoryTest {
    @Test
    fun `API 28 29 and 36 configure exact plan then establish once`() {
        val network = network(42)
        val configureIntent = configureIntent()

        listOf(
            28 to api28Calls(),
            29 to api29PlusCalls(),
            36 to api29PlusCalls(),
        ).forEach { (sdkInt, expectedCalls) ->
            val descriptor = RecordingDescriptor()
            val builder = RecordingEstablishingTunBuilder(descriptor)
            val adapter = RecordingSessionAdapter()
            val result = factory(
                sdkInt = sdkInt,
                builderFactory = EstablishingTunBuilderFactory { builder },
                sessionAdapter = adapter,
            ).establish(network, configureIntent)

            assertTrue(result is TunEstablishmentResult.Established)
            assertEquals(expectedCalls + "establish", builder.calls)
            assertEquals(1, builder.establishCalls)
            assertSame(network, builder.recordedUnderlyingNetworks.single())
            assertSame(configureIntent, builder.recordedConfigureIntent)
            assertSame(descriptor, adapter.descriptor)
            assertEquals(0, descriptor.closeCalls)

            (result as TunEstablishmentResult.Established).session.close()
            assertEquals(1, descriptor.closeCalls)
        }
    }

    @Test
    fun `builder creation failure stops before configuration establishment and adaptation`() {
        val failure = IOException("builder unavailable")
        val adapter = RecordingSessionAdapter()

        val result = factory(
            builderFactory = EstablishingTunBuilderFactory { throw failure },
            sessionAdapter = adapter,
        ).establish(network(42), configureIntent())

        assertEquals(
            TunEstablishmentFailure.BuilderCreationFailed(failure),
            (result as TunEstablishmentResult.Failed).failure,
        )
        assertEquals(0, adapter.calls)
    }

    @Test
    fun `configuration failure never calls establish or transfers a descriptor`() {
        val failure = IOException("configuration rejected")
        val builder = RecordingEstablishingTunBuilder(
            descriptor = RecordingDescriptor(),
            failOnCall = "route:10.111.0.1/32",
            failure = failure,
        )
        val adapter = RecordingSessionAdapter()

        val result = factory(
            builderFactory = EstablishingTunBuilderFactory { builder },
            sessionAdapter = adapter,
        ).establish(network(42), configureIntent())

        assertEquals(
            TunEstablishmentFailure.ConfigurationFailed(failure),
            (result as TunEstablishmentResult.Failed).failure,
        )
        assertEquals(0, builder.establishCalls)
        assertEquals(0, adapter.calls)
    }

    @Test
    fun `establishment exception is typed and not retried`() {
        val failure = IOException("platform establish failed")
        val builder = RecordingEstablishingTunBuilder(
            descriptor = RecordingDescriptor(),
            establishmentFailure = failure,
        )
        val adapter = RecordingSessionAdapter()

        val result = factory(
            builderFactory = EstablishingTunBuilderFactory { builder },
            sessionAdapter = adapter,
        ).establish(network(42), configureIntent())

        assertEquals(
            TunEstablishmentFailure.EstablishmentFailed(failure),
            (result as TunEstablishmentResult.Failed).failure,
        )
        assertEquals(1, builder.establishCalls)
        assertEquals(0, adapter.calls)
    }

    @Test
    fun `null establishment result is a typed unavailable descriptor`() {
        val builder = RecordingEstablishingTunBuilder(descriptor = null)
        val adapter = RecordingSessionAdapter()

        val result = factory(
            builderFactory = EstablishingTunBuilderFactory { builder },
            sessionAdapter = adapter,
        ).establish(network(42), configureIntent())

        assertEquals(
            TunEstablishmentFailure.DescriptorUnavailable,
            (result as TunEstablishmentResult.Failed).failure,
        )
        assertEquals(1, builder.establishCalls)
        assertEquals(0, adapter.calls)
    }

    @Test
    fun `adapter failure consumes descriptor once and factory never double closes`() {
        val descriptor = RecordingDescriptor()
        val failure = IOException("adapter failed")
        val adapter = TunPacketSessionAdapter { transferred ->
            assertSame(descriptor, transferred)
            transferred.close()
            throw failure
        }

        val result = factory(
            builderFactory = EstablishingTunBuilderFactory {
                RecordingEstablishingTunBuilder(descriptor)
            },
            sessionAdapter = adapter,
        ).establish(network(42), configureIntent())

        assertEquals(
            TunEstablishmentFailure.SessionAdaptationFailed(failure),
            (result as TunEstablishmentResult.Failed).failure,
        )
        assertEquals(1, descriptor.closeCalls)
    }

    @Test
    fun `production adapter closes an unexpected descriptor type exactly once`() {
        val descriptor = RecordingDescriptor()
        val result = AndroidTunPacketSessionFactory(
            productLabel = "Lava Security",
            sdkInt = 28,
            builderFactory = EstablishingTunBuilderFactory {
                RecordingEstablishingTunBuilder(descriptor)
            },
        ).establish(network(42), configureIntent())

        val failure = (result as TunEstablishmentResult.Failed).failure
            as TunEstablishmentFailure.SessionAdaptationFailed
        assertTrue(failure.cause is IllegalArgumentException)
        assertEquals(1, descriptor.closeCalls)
    }

    @Test
    fun `successful result transfers sole session ownership to caller`() {
        val descriptor = RecordingDescriptor()
        val builder = RecordingEstablishingTunBuilder(descriptor)
        val adapter = RecordingSessionAdapter()

        val result = factory(
            builderFactory = EstablishingTunBuilderFactory { builder },
            sessionAdapter = adapter,
        ).establish(network(42), configureIntent()) as TunEstablishmentResult.Established

        assertSame(adapter.session, result.session)
        assertSame(descriptor, adapter.descriptor)
        assertEquals(0, descriptor.closeCalls)

        result.session.close()
        result.session.close()

        assertEquals(1, descriptor.closeCalls)
    }

    @Test
    fun `factory surface cannot start runtime or publish protection state`() {
        val methodNames = AndroidTunPacketSessionFactory::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) }
            .map { it.name }
            .toSet()

        assertEquals(setOf("establish"), methodNames)
        assertFalse(methodNames.any { it.contains("start", ignoreCase = true) })
        assertFalse(methodNames.any { it.contains("protect", ignoreCase = true) })
        assertFalse(methodNames.any { it.contains("publish", ignoreCase = true) })
    }

    @Test
    fun `service backed factory cannot override the device SDK level`() {
        val serviceConstructor = AndroidTunPacketSessionFactory::class.java.declaredConstructors
            .single { constructor ->
                constructor.parameterTypes.firstOrNull() == VpnService::class.java
            }

        assertEquals(
            listOf(VpnService::class.java, String::class.java),
            serviceConstructor.parameterTypes.toList(),
        )
    }

    private fun factory(
        sdkInt: Int = 28,
        builderFactory: EstablishingTunBuilderFactory,
        sessionAdapter: TunPacketSessionAdapter,
    ) = AndroidTunPacketSessionFactory(
        productLabel = "Lava Security",
        sdkInt = sdkInt,
        builderFactory = builderFactory,
        sessionAdapter = sessionAdapter,
    )

    private fun configureIntent(): PendingIntent {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return PendingIntent.getActivity(
            context,
            0,
            Intent("com.lavasec.app.CONFIGURE_VPN"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun network(handle: Int): Network =
        Network::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .newInstance(handle)

    private fun api28Calls(): List<String> = listOf(
        "session:Lava Security",
        "mtu:1280",
        "blocking:false",
        "address:10.111.0.2/32",
        "dns:10.111.0.1",
        "route:10.111.0.1/32",
        "family:2",
        "family:10",
        "underlying:1",
        "configure",
    )

    private fun api29PlusCalls(): List<String> =
        api28Calls().dropLast(1) + listOf("metered:false", "configure")

    private class RecordingDescriptor : Closeable {
        var closeCalls = 0

        override fun close() {
            closeCalls += 1
        }
    }

    private class RecordingSessionAdapter : TunPacketSessionAdapter {
        var calls = 0
        var descriptor: Closeable? = null
        lateinit var session: RecordingSession

        override fun adapt(descriptor: Closeable): TunPacketSession {
            calls += 1
            this.descriptor = descriptor
            return RecordingSession(descriptor).also { session = it }
        }
    }

    private class RecordingSession(
        private val descriptor: Closeable,
    ) : TunPacketSession {
        private var closed = false

        override suspend fun readPacket(into: ByteArray): TunRead =
            error("not used by establishment tests")

        override fun tryWritePacket(packet: ByteArray): TunWriteAttempt =
            error("not used by establishment tests")

        override suspend fun awaitWritable(): TunWritable =
            error("not used by establishment tests")

        override fun close() {
            if (closed) return
            closed = true
            descriptor.close()
        }
    }

    private class RecordingEstablishingTunBuilder(
        private val descriptor: Closeable?,
        private val failOnCall: String? = null,
        private val failure: Throwable = IOException("configuration failed"),
        private val establishmentFailure: Throwable? = null,
    ) : EstablishingTunBuilder {
        val calls = mutableListOf<String>()
        var establishCalls = 0
        var recordedUnderlyingNetworks: Array<Network> = emptyArray()
        var recordedConfigureIntent: PendingIntent? = null

        override fun setSession(label: String) = record("session:$label")

        override fun setMtu(mtu: Int) = record("mtu:$mtu")

        override fun setBlocking(blocking: Boolean) = record("blocking:$blocking")

        override fun addAddress(address: String, prefix: Int) =
            record("address:$address/$prefix")

        override fun addDnsServer(address: String) = record("dns:$address")

        override fun addRoute(address: String, prefix: Int) =
            record("route:$address/$prefix")

        override fun allowFamily(family: Int) = record("family:$family")

        override fun setUnderlyingNetworks(networks: Array<Network>) {
            recordedUnderlyingNetworks = networks
            record("underlying:${networks.size}")
        }

        override fun setConfigureIntent(intent: PendingIntent) {
            recordedConfigureIntent = intent
            record("configure")
        }

        override fun setMetered(metered: Boolean) = record("metered:$metered")

        override fun establish(): Closeable? {
            establishCalls += 1
            calls += "establish"
            establishmentFailure?.let { throw it }
            return descriptor
        }

        private fun record(call: String) {
            calls += call
            if (call == failOnCall) throw failure
        }
    }
}
