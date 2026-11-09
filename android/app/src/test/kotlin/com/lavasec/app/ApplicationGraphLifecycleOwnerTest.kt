package com.lavasec.app

import com.lavasec.app.vpn.CredentialVpnAttachRequest
import com.lavasec.app.vpn.CredentialVpnAttachment
import com.lavasec.app.vpn.CredentialVpnHostPort
import com.lavasec.app.vpn.VpnTelemetry
import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.platform.ProtectionFailure
import com.lavasec.app.platform.ProtectionState
import com.lavasec.app.platform.StartOrigin
import com.lavasec.app.platform.VpnOwnership
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertNotNull
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationGraphLifecycleOwnerTest {
    @Test
    fun `authoritative restoration restarts a live runtime so it reopens the new graph`() =
        runTest {
            val liveStates = listOf(
                ProtectionState.Starting(StartOrigin.Reload, 1L),
                ProtectionState.AwaitingNetwork(StartOrigin.Reload, 1L),
                ProtectionState.Reconnecting(StartOrigin.Reload, 1L),
                ProtectionState.Protected(
                    ConfigurationIdentity(1L, "a".repeat(64)),
                    VpnOwnership.User,
                ),
                ProtectionState.Paused(100L),
                ProtectionState.Stopping,
            )
            liveStates.forEach { state ->
                val events = mutableListOf<String>()
                restoreAuthoritativeProtection(
                    current = state,
                    stop = { events += "stop" },
                    start = { events += "start" },
                )
                assertEquals(state.toString(), listOf("stop", "start"), events)
            }

            listOf(
                ProtectionState.Off,
                ProtectionState.SetupRequired,
                ProtectionState.Failed(ProtectionFailure.DependencyUnavailable),
            ).forEach { state ->
                val events = mutableListOf<String>()
                restoreAuthoritativeProtection(
                    current = state,
                    stop = { events += "stop" },
                    start = { events += "start" },
                )
                assertEquals(state.toString(), listOf("start"), events)
            }
        }

    @Test
    fun `locked startup creates only the device graph and performs no private read`() = runTest {
        val events = mutableListOf<String>()
        val factory = RecordingGraphFactory(events, restoreIntent = true)
        val owner = ApplicationGraphLifecycleOwner(
            deviceGraphFactory = factory::createDevice,
            credentialGraphFactory = factory::createCredential,
            attachCredentialHost = { host -> factory.attach(host) },
            restoreProtection = { events += "restore" },
        )

        owner.reconcile(userUnlocked = false)

        assertEquals(listOf("device", "attach:9000"), events)
        assertEquals(0, factory.privateReads)
        assertNull(owner.credentialGenerationForTest())
        owner.close()
        assertEquals(listOf("device", "attach:9000", "detach:9000", "close-device"), events)
    }

    @Test
    fun `unlock attaches a new credential generation before authoritative restoration`() = runTest {
        val events = mutableListOf<String>()
        val factory = RecordingGraphFactory(events, restoreIntent = true)
        val owner = ApplicationGraphLifecycleOwner(
            deviceGraphFactory = factory::createDevice,
            credentialGraphFactory = factory::createCredential,
            attachCredentialHost = { host -> factory.attach(host) },
            restoreProtection = { events += "restore" },
        )

        owner.reconcile(userUnlocked = false)
        owner.reconcile(userUnlocked = true)

        assertEquals(
            listOf("device", "attach:9000", "private-read:1", "attach:1", "detach:9000", "restore"),
            events,
        )
        assertEquals(1L, owner.credentialGenerationForTest())
    }

    @Test
    fun `duplicate startup and unlock reconciliation reuse the exact attached generation`() =
        runTest {
            val events = mutableListOf<String>()
            val factory = RecordingGraphFactory(events, restoreIntent = true)
            val owner = ApplicationGraphLifecycleOwner(
                deviceGraphFactory = factory::createDevice,
                credentialGraphFactory = factory::createCredential,
                attachCredentialHost = { host -> factory.attach(host) },
                restoreProtection = { events += "restore" },
            )

            owner.reconcile(userUnlocked = true)
            owner.reconcile(userUnlocked = true)

            assertEquals(
                listOf("device", "attach:9000", "private-read:1", "attach:1", "detach:9000", "restore"),
                events,
            )
            assertEquals(1, factory.privateReads)
        }

    @Test
    fun `incomplete onboarding or off intent never manufactures a restoration`() = runTest {
        listOf(false, null).forEach { restoration ->
            val events = mutableListOf<String>()
            val factory = RecordingGraphFactory(events, restoreIntent = restoration)
            val owner = ApplicationGraphLifecycleOwner(
                deviceGraphFactory = factory::createDevice,
                credentialGraphFactory = factory::createCredential,
                attachCredentialHost = { host -> factory.attach(host) },
                restoreProtection = { events += "restore" },
            )

            owner.reconcile(userUnlocked = true)

            assertFalse("restoration=$restoration", "restore" in events)
            owner.close()
        }
    }

    @Test
    fun `unlock retires a Direct Boot runtime when credential authority is off or ambiguous`() =
        runTest {
            listOf(false, null).forEach { authority ->
                val events = mutableListOf<String>()
                val factory = RecordingGraphFactory(events, restoreIntent = authority)
                val owner = ApplicationGraphLifecycleOwner(
                    deviceGraphFactory = factory::createDevice,
                    credentialGraphFactory = factory::createCredential,
                    attachCredentialHost = factory::attach,
                    restoreProtection = { events += "restore" },
                    retireProtection = { events += "retire" },
                )

                owner.reconcile(userUnlocked = false)
                owner.reconcile(userUnlocked = true)

                assertEquals("authority=$authority", "retire", events.last())
                assertFalse("authority=$authority", "restore" in events)
                owner.close()
            }
        }

    @Test
    fun `failed restoration stays pending without rebuilding or manufacturing success`() =
        runTest {
            val events = mutableListOf<String>()
            val factory = RecordingGraphFactory(events, restoreIntent = true)
            var attempts = 0
            val owner = ApplicationGraphLifecycleOwner(
                deviceGraphFactory = factory::createDevice,
                credentialGraphFactory = factory::createCredential,
                attachCredentialHost = { host -> factory.attach(host) },
                restoreProtection = {
                    attempts += 1
                    events += "restore:$attempts"
                    if (attempts == 1) error("platform start rejected")
                },
            )

            assertEquals(
                "platform start rejected",
                runCatching { owner.reconcile(userUnlocked = true) }.exceptionOrNull()?.message,
            )
            owner.reconcile(userUnlocked = true)

            assertEquals(
                listOf(
                    "device",
                    "attach:9000",
                    "private-read:1",
                    "attach:1",
                    "detach:9000",
                    "restore:1",
                    "restore:2",
                ),
                events,
            )
            assertEquals(1, factory.privateReads)
            assertEquals(1L, owner.credentialGenerationForTest())
        }

    @Test
    fun `replacement admits the successor before detaching and closing the predecessor`() =
        runTest {
            val events = mutableListOf<String>()
            val factory = RecordingGraphFactory(events, restoreIntent = true)
            val owner = ApplicationGraphLifecycleOwner(
                deviceGraphFactory = factory::createDevice,
                credentialGraphFactory = factory::createCredential,
                attachCredentialHost = { host -> factory.attach(host) },
                restoreProtection = { events += "restore" },
            )
            owner.reconcile(userUnlocked = true)
            events.clear()

            owner.rotateCredentialGraph(expectedGeneration = 1L)

            assertEquals(
                listOf(
                    "attach:9000",
                    "detach:1",
                    "close-credential:1",
                    "private-read:2",
                    "attach:2",
                    "detach:9000",
                    "restore",
                ),
                events,
            )
            assertEquals(2L, owner.credentialGenerationForTest())
        }

    @Test
    fun `stale rotation request cannot retire the installed successor`() = runTest {
        val events = mutableListOf<String>()
        val factory = RecordingGraphFactory(events, restoreIntent = true)
        val owner = ApplicationGraphLifecycleOwner(
            deviceGraphFactory = factory::createDevice,
            credentialGraphFactory = factory::createCredential,
            attachCredentialHost = factory::attach,
            restoreProtection = { events += "restore" },
        )
        owner.reconcile(userUnlocked = true)
        owner.rotateCredentialGraph(expectedGeneration = 1L)
        events.clear()

        owner.rotateCredentialGraph(expectedGeneration = 1L)

        assertTrue(events.isEmpty())
        assertEquals(2L, owner.credentialGenerationForTest())
        owner.close()
    }

    @Test
    fun `setup receipt precedes actual successor factory publication and ignores queued source reads`() = runTest {
        val events = mutableListOf<String>()
        val factory = RecordingGraphFactory(events, restoreIntent = false)
        val owner = ApplicationGraphLifecycleOwner(factory::createDevice, factory::createCredential,
            factory::attach, restoreProtection = {})
        try {
            owner.reconcile(userUnlocked = true)
            val sourceFactory = checkNotNull(owner.productOwnerFactory.value)
            val entries = com.lavasec.app.reactnative.NativeDeepLinkEntryRegistry()
            val host = Any(); val nativeSetup = Any()
            val id = entries.stage(com.lavasec.app.presentation.ProductNavigation.settings, 1L)
            assertTrue(entries.attach(id, host, "A"))
            val source = checkNotNull(entries.claim(id, host, "A", 1L)); assertTrue(entries.begin(source))
            val handoff = checkNotNull(entries.prepareSetupHandoff(source, "actual-setup", nativeSetup))
            var transferred: com.lavasec.app.reactnative.NativeDeepLinkEntryRegistry.Claim? = null
            val transition = checkNotNull(owner.rotateCredentialGraphAfterOnboarding(1L, sourceFactory) { receipt ->
                // Source is already retired; successor is attached but has not been exposed to VM observers.
                assertNull(owner.productOwnerFactory.value)
                assertEquals(receipt.successorGeneration, owner.credentialGenerationForTest())
                assertSame(sourceFactory, receipt.sourceFactory)
                assertSame(factory.productFactories.last(), receipt.successorFactory)
                transferred = entries.transferSetupHandoff(handoff, "actual-setup", nativeSetup,
                    receipt.sourceGeneration, receipt.successorGeneration)
                assertNotNull(transferred)
                // A reentrant old VM observer cannot cancel the proved new claim.
                assertNull(entries.claim(id, host, "A", 1L))
                assertFalse(entries.publish(source, 1L, setupCompleted = true, authorized = true))
            })
            assertSame(transition.successorFactory, owner.productOwnerFactory.value)
            assertEquals(2L, transition.successorGeneration)
            val next = checkNotNull(transferred)
            assertFalse(entries.begin(checkNotNull(entries.claim(id, host, "A", transition.successorGeneration))))
            assertTrue(entries.publish(next, transition.successorGeneration, setupCompleted = true, authorized = true))
            val navigation = checkNotNull(entries.navigation(id, host, "A", transition.successorGeneration))
            assertTrue(entries.accept(id, host, "A", transition.successorGeneration, navigation.serial, 12L))
            assertEquals("Settings", entries.acceptance(id, host, "A", transition.successorGeneration)?.navigation?.screen)
        } finally { owner.close() }
    }

    @Test
    fun `ordinary rotation winning the mutex cannot be adopted by a queued setup receipt`() = runTest {
        val events = mutableListOf<String>()
        val factory = RecordingGraphFactory(events, restoreIntent = false)
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val owner = ApplicationGraphLifecycleOwner(factory::createDevice, { generation ->
            if (generation == 2L) { entered.complete(Unit); release.await() }
            factory.createCredential(generation)
        }, factory::attach, restoreProtection = {})
        try {
            owner.reconcile(userUnlocked = true)
            val source = checkNotNull(owner.productOwnerFactory.value)
            val ordinary = async { owner.rotateCredentialGraph(1L) }
            entered.await()
            var receipts = 0
            val queued = async { owner.rotateCredentialGraphAfterOnboarding(1L, source) { receipts++ } }
            runCurrent()
            assertFalse(queued.isCompleted)
            release.complete(Unit); ordinary.await()
            assertNull(queued.await()); assertEquals(0, receipts)
            assertEquals(2L, owner.credentialGenerationForTest())
            assertSame(factory.productFactories.last(), owner.productOwnerFactory.value)
        } finally { release.complete(Unit); owner.close() }
    }

    @Test
    fun `wrong source factory or generation cannot produce an onboarding receipt`() = runTest {
        val events = mutableListOf<String>()
        val factory = RecordingGraphFactory(events, restoreIntent = false)
        val owner = ApplicationGraphLifecycleOwner(factory::createDevice, factory::createCredential,
            factory::attach, restoreProtection = {})
        try {
            owner.reconcile(userUnlocked = true)
            val source = checkNotNull(owner.productOwnerFactory.value); val before = events.toList()
            var receipts = 0
            assertNull(owner.rotateCredentialGraphAfterOnboarding(1L, RecordingProductOwnerFactory()) { receipts++ })
            assertNull(owner.rotateCredentialGraphAfterOnboarding(9L, source) { receipts++ })
            assertEquals(0, receipts); assertEquals(before, events)
            assertSame(source, owner.productOwnerFactory.value)
        } finally { owner.close() }
    }

    @Test
    fun `failed successor has no receipt and receipt callback failure cannot prevent real publication`() = runTest {
        val events = mutableListOf<String>()
        val factory = RecordingGraphFactory(events, restoreIntent = false)
        val owner = ApplicationGraphLifecycleOwner(factory::createDevice, factory::createCredential,
            factory::attach, restoreProtection = {})
        try {
            owner.reconcile(userUnlocked = true)
            val source = checkNotNull(owner.productOwnerFactory.value)
            var receipts = 0
            factory.failGeneration = 2L
            assertTrue(runCatching { owner.rotateCredentialGraphAfterOnboarding(1L, source) { receipts++ } }.isFailure)
            assertEquals(0, receipts); assertNull(owner.productOwnerFactory.value)
            factory.failGeneration = null; owner.reconcile(userUnlocked = true)
            val recovered = checkNotNull(owner.productOwnerFactory.value)
            assertEquals(3L, owner.credentialGenerationForTest())
            val receipt = checkNotNull(owner.rotateCredentialGraphAfterOnboarding(3L, recovered) {
                receipts++; error("retired child refused custody")
            })
            assertEquals(1, receipts); assertEquals(4L, receipt.successorGeneration)
            assertSame(receipt.successorFactory, owner.productOwnerFactory.value)
            assertEquals(4L, owner.credentialGenerationForTest())
        } finally { owner.close() }
    }


    @Test
    fun `relock and close fence registration before private graph resources`() = runTest {
        val events = mutableListOf<String>()
        val factory = RecordingGraphFactory(events, restoreIntent = true)
        val owner = ApplicationGraphLifecycleOwner(
            deviceGraphFactory = factory::createDevice,
            credentialGraphFactory = factory::createCredential,
            attachCredentialHost = { host -> factory.attach(host) },
            restoreProtection = { events += "restore" },
        )
        owner.reconcile(userUnlocked = true)
        events.clear()

        owner.reconcile(userUnlocked = false)

        assertEquals(listOf("attach:9000", "detach:1", "close-credential:1"), events)
        assertNull(owner.credentialGenerationForTest())
        owner.close()
        assertEquals(
            listOf("attach:9000", "detach:1", "close-credential:1", "detach:9000", "close-device"),
            events,
        )
    }

    @Test
    fun `product owner factory follows only the exact attached credential generation`() = runTest {
        val events = mutableListOf<String>()
        val factory = RecordingGraphFactory(events, restoreIntent = false)
        val owner = ApplicationGraphLifecycleOwner(
            deviceGraphFactory = factory::createDevice,
            credentialGraphFactory = factory::createCredential,
            attachCredentialHost = { host -> factory.attach(host) },
            restoreProtection = { events += "restore" },
        )

        assertNull(owner.productOwnerFactory.value)
        owner.reconcile(userUnlocked = true)
        val first = factory.productFactories.single()
        assertSame(first, owner.productOwnerFactory.value)

        owner.rotateCredentialGraph(expectedGeneration = 1L)
        val second = factory.productFactories.last()
        assertNotSame(first, second)
        assertSame(second, owner.productOwnerFactory.value)

        owner.reconcile(userUnlocked = false)
        assertNull(owner.productOwnerFactory.value)
    }

    @Test
    fun `failed replacement remains detached and process recreation rereads authority`() = runTest {
        val events = mutableListOf<String>()
        val factory = RecordingGraphFactory(events, restoreIntent = true)
        val first = ApplicationGraphLifecycleOwner(
            deviceGraphFactory = factory::createDevice,
            credentialGraphFactory = factory::createCredential,
            attachCredentialHost = { host -> factory.attach(host) },
            restoreProtection = { events += "restore" },
        )
        first.reconcile(userUnlocked = true)
        factory.failGeneration = 2L
        events.clear()

        val failure = runCatching {
            first.rotateCredentialGraph(expectedGeneration = 1L)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(
            listOf("attach:9000", "detach:1", "close-credential:1", "private-read:2"),
            events,
        )
        assertNull(first.credentialGenerationForTest())

        factory.failGeneration = null
        events.clear()
        val recreated = ApplicationGraphLifecycleOwner(
            deviceGraphFactory = factory::createDevice,
            credentialGraphFactory = factory::createCredential,
            attachCredentialHost = { host -> factory.attach(host) },
            restoreProtection = { events += "restore" },
        )
        recreated.reconcile(userUnlocked = true)

        assertEquals(
            listOf("device", "attach:9000", "private-read:1", "attach:1", "detach:9000", "restore"),
            events,
        )
        assertEquals(3, factory.privateReads)
    }

    @Test
    fun `invalid credential generation closes the candidate and preserves the device host`() =
        runTest {
            val events = mutableListOf<String>()
            val factory = RecordingGraphFactory(events, restoreIntent = true).apply {
                returnedGeneration = 2L
            }
            val owner = ApplicationGraphLifecycleOwner(
                deviceGraphFactory = factory::createDevice,
                credentialGraphFactory = factory::createCredential,
                attachCredentialHost = factory::attach,
                restoreProtection = { events += "restore" },
            )

            owner.reconcile(userUnlocked = false)
            val failure = runCatching { owner.reconcile(userUnlocked = true) }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertEquals(
                listOf("device", "attach:9000", "private-read:1", "close-credential:2"),
                events,
            )
            assertNull(owner.credentialGenerationForTest())
            owner.close()
        }
}

private class RecordingGraphFactory(
    private val events: MutableList<String>,
    private val restoreIntent: Boolean?,
) {
    var privateReads: Int = 0
    var failGeneration: Long? = null
    var returnedGeneration: Long? = null
    val productFactories = mutableListOf<CredentialProductOwnerFactory>()

    fun createDevice(): DeviceApplicationGraph {
        events += "device"
        return RecordingDeviceGraph(events)
    }

    suspend fun createCredential(generation: Long): CredentialApplicationGraph {
        privateReads += 1
        events += "private-read:$generation"
        if (failGeneration == generation) error("ambiguous credential authority")
        val productFactory = RecordingProductOwnerFactory()
        productFactories += productFactory
        return RecordingCredentialGraph(
            returnedGeneration ?: generation,
            restoreIntent,
            events,
            productFactory,
        )
    }

    fun attach(host: CredentialVpnHostPort): AutoCloseable {
        events += "attach:${host.graphGeneration}"
        return AutoCloseable { events += "detach:${host.graphGeneration}" }
    }
}

private class RecordingDeviceGraph(
    private val events: MutableList<String>,
) : DeviceApplicationGraph, CredentialVpnHostPort {
    override val graphGeneration: Long = 9_000L
    override val vpnHost: CredentialVpnHostPort get() = this

    override fun openAttachment(request: CredentialVpnAttachRequest): CredentialVpnAttachment? =
        object : CredentialVpnAttachment {
            override val graphGeneration: Long = this@RecordingDeviceGraph.graphGeneration
            override fun tryAcceptTelemetry(telemetry: VpnTelemetry): Boolean = false
            override fun detach() = Unit
        }

    override fun close() {
        events += "close-device"
    }
}

private class RecordingCredentialGraph(
    override val graphGeneration: Long,
    override val authoritativeProtectionIntent: Boolean?,
    private val events: MutableList<String>,
    override val productOwnerFactory: CredentialProductOwnerFactory,
) : CredentialApplicationGraph, CredentialVpnHostPort {
    override val vpnHost: CredentialVpnHostPort get() = this

    override fun openAttachment(request: CredentialVpnAttachRequest): CredentialVpnAttachment? =
        object : CredentialVpnAttachment {
            override val graphGeneration: Long = this@RecordingCredentialGraph.graphGeneration

            override fun tryAcceptTelemetry(telemetry: VpnTelemetry): Boolean = true

            override fun detach() = Unit
        }

    override fun close() {
        events += "close-credential:$graphGeneration"
    }
}

private class RecordingProductOwnerFactory : CredentialProductOwnerFactory {
    override fun create(scope: CoroutineScope): CredentialProductOwners =
        error("recording factory is not opened by the graph lifecycle")

    override fun close() = Unit
}
