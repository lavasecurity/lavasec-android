package com.lavasec.app.vpn

import android.app.PendingIntent
import android.net.VpnService
import com.lavasec.app.foundation.dns.DnsHealthEvent
import com.lavasec.app.foundation.dns.TunWriteFailureReason
import com.lavasec.app.foundation.pause.PauseDuration
import com.lavasec.app.foundation.pause.PauseState
import com.lavasec.app.foundation.pause.RuntimePauseDirective
import com.lavasec.app.foundation.resolver.ResolverConfiguration
import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.platform.ProtectionFailure
import com.lavasec.app.platform.ProtectionState
import com.lavasec.app.platform.VpnOwnership
import com.lavasec.app.platform.VerifiedFilterRuntimeLease
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionVpnRuntimeFenceTest {
    @Test
    fun `service owner keeps a no-network production start sticky and awaiting`() {
        val attachment = AwaitingRuntimeAttachment(graphGeneration = 108L)
        val host = AwaitingRuntimeHost(attachment)
        val state = ExactVpnServiceState()
        val owner = VpnServiceCommandOwner(
            foreground = VpnForegroundAdmission {},
            hostRegistry = CredentialVpnHostRegistry().apply { attach(host) },
            state = state,
            runtimeFactory = VpnOwnedRuntimeFactory { request ->
                ProductionVpnOwnedRuntime(
                    service = VpnService(),
                    request = request,
                    eligibleNetwork = { null },
                    observeNetworkAvailability = { AutoCloseable {} },
                )
            },
        )
        try {
            assertEquals(
                VpnCommandResult.Sticky,
                owner.handle(
                    VpnServiceCommand.Start(
                        com.lavasec.app.platform.StartOrigin.SystemAlwaysOn,
                    ),
                ),
            )
            assertEquals(
                ProtectionState.AwaitingNetwork(
                    com.lavasec.app.platform.StartOrigin.SystemAlwaysOn,
                    1L,
                ),
                state.state.value,
            )
            assertEquals(0, attachment.detaches.get())
        } finally {
            owner.close()
        }
        // The command owner now defers the runtime teardown off the service thread, and the
        // production runtime's `close()` submits the actual engine-queue teardown asynchronously
        // (`ChainedEngineQueue.enqueue`), so the host detach happens after `close()` returns.
        awaitCondition { attachment.detaches.get() == 1 }
        assertEquals(1, attachment.detaches.get())
    }

    @Test
    fun `no eligible network remains owned awaiting an exact availability callback`() {
        val attachment = AwaitingRuntimeAttachment(graphGeneration = 106L)
        val host = AwaitingRuntimeHost(attachment)
        val states = mutableListOf<ProtectionState>()
        val callback = AtomicReference<(() -> Unit)?>(null)
        val registrationCloses = AtomicInteger()
        val runtime = ProductionVpnOwnedRuntime(
            service = VpnService(),
            request = VpnOwnedRuntimeRequest(
                owner = VpnServiceOwner(16L),
                origin = com.lavasec.app.platform.StartOrigin.SystemAlwaysOn,
                host = CredentialHostSnapshot(host.graphGeneration, host),
                attachment = attachment,
                publication = VpnStatePublication(states::add),
            ),
            eligibleNetwork = { null },
            observeNetworkAvailability = { available ->
                callback.set(available)
                AutoCloseable { registrationCloses.incrementAndGet() }
            },
        )

        runtime.start()

        assertEquals(
            listOf(
                ProtectionState.AwaitingNetwork(
                    com.lavasec.app.platform.StartOrigin.SystemAlwaysOn,
                    16L,
                ),
            ),
            states,
        )
        assertTrue(callback.get() != null)
        assertEquals(0, attachment.detaches.get())

        runtime.close()
        awaitCondition { registrationCloses.get() == 1 && attachment.detaches.get() == 1 }
        assertEquals(1, registrationCloses.get())
        assertEquals(1, attachment.detaches.get())
    }

    @Test
    fun `cold preflight while paused still checks transport eligibility`() {
        val attachment = AwaitingRuntimeAttachment(graphGeneration = 113L, pauseDeadlineMillis = Long.MAX_VALUE)
        attachment.requestPause(PauseDuration.FIVE_MINUTES)
        val host = AwaitingRuntimeHost(attachment)
        val runtime = ProductionVpnOwnedRuntime(
            service = VpnService(),
            request = VpnOwnedRuntimeRequest(
                owner = VpnServiceOwner(22L), origin = com.lavasec.app.platform.StartOrigin.VisibleUser,
                host = CredentialHostSnapshot(host.graphGeneration, host), attachment = attachment,
                publication = VpnStatePublication { true },
            ),
            eligibleNetwork = { null }, observeNetworkAvailability = { AutoCloseable {} },
        )
        try {
            kotlinx.coroutines.runBlocking { runtime.preflight() }
            assertEquals(1, attachment.credentialOpens.get())
            assertEquals(Long.MAX_VALUE, attachment.pause.get()?.deadlineMillis)
        } finally { runtime.close() }
    }

    @Test
    fun `pause and resume do not reopen transport credentials or request a mode rebuild`() {
        val attachment = AwaitingRuntimeAttachment(graphGeneration = 113L, pauseDeadlineMillis = Long.MAX_VALUE)
        val host = AwaitingRuntimeHost(attachment)
        val rebuilds = AtomicInteger()
        val runtime = ProductionVpnOwnedRuntime(
            service = VpnService(),
            request = VpnOwnedRuntimeRequest(
                owner = VpnServiceOwner(22L),
                origin = com.lavasec.app.platform.StartOrigin.VisibleUser,
                host = CredentialHostSnapshot(host.graphGeneration, host),
                attachment = attachment,
                publication = VpnStatePublication { true },
            ),
            eligibleNetwork = { null },
            observeNetworkAvailability = { AutoCloseable {} },
            requestRuntimeRebuild = { rebuilds.incrementAndGet() },
        )
        try {
            runtime.start()
            // Registration immediately schedules a recovery preflight. Wait for that
            // startup read before measuring reads caused by pause/resume.
            assertTrue(attachment.credentialRead.await(5L, TimeUnit.SECONDS))
            val openedAtStart = attachment.credentialOpens.get()
            runtime.pause(PauseDuration.FIVE_MINUTES)
            assertEquals(Long.MAX_VALUE, attachment.pause.get()?.deadlineMillis)
            runtime.resume()
            assertEquals(null, attachment.pause.get())
            assertEquals(openedAtStart, attachment.credentialOpens.get())
            assertEquals(0, rebuilds.get())
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `timed pause expiry clears exact durable pause and republishes awaiting network`() {
        val attachment = AwaitingRuntimeAttachment(
            graphGeneration = 107L,
            reportPersistedPauseAsLogicallyExpired = true,
        )
        val host = AwaitingRuntimeHost(attachment)
        val states = java.util.concurrent.CopyOnWriteArrayList<ProtectionState>()
        val runtime = ProductionVpnOwnedRuntime(
            service = VpnService(),
            request = VpnOwnedRuntimeRequest(
                owner = VpnServiceOwner(17L),
                origin = com.lavasec.app.platform.StartOrigin.VisibleUser,
                host = CredentialHostSnapshot(host.graphGeneration, host),
                attachment = attachment,
                publication = VpnStatePublication(states::add),
            ),
            eligibleNetwork = { null },
            observeNetworkAvailability = { AutoCloseable {} },
            nowMillis = { 1_000L },
        )
        try {
            runtime.start()
            runtime.pause(PauseDuration.FIVE_MINUTES)

            assertTrue(attachment.resumed.await(5L, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
            while (states.lastOrNull() !is ProtectionState.AwaitingNetwork &&
                System.nanoTime() < deadline
            ) {
                Thread.yield()
            }

            assertEquals(
                listOf(
                    ProtectionState.AwaitingNetwork(
                        com.lavasec.app.platform.StartOrigin.VisibleUser,
                        17L,
                    ),
                    ProtectionState.Paused(1_000L),
                    ProtectionState.AwaitingNetwork(
                        com.lavasec.app.platform.StartOrigin.VisibleUser,
                        17L,
                    ),
                ),
                states,
            )
            assertEquals(null, attachment.pause.get())
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `logical pause expiry republishes runtime truth when stale cleanup is unavailable`() {
        val attachment = AwaitingRuntimeAttachment(
            graphGeneration = 109L,
            reportPersistedPauseAsLogicallyExpired = true,
            resumeSucceeds = false,
        )
        val host = AwaitingRuntimeHost(attachment)
        val states = java.util.concurrent.CopyOnWriteArrayList<ProtectionState>()
        val runtime = ProductionVpnOwnedRuntime(
            service = VpnService(),
            request = VpnOwnedRuntimeRequest(
                owner = VpnServiceOwner(18L),
                origin = com.lavasec.app.platform.StartOrigin.VisibleUser,
                host = CredentialHostSnapshot(host.graphGeneration, host),
                attachment = attachment,
                publication = VpnStatePublication(states::add),
            ),
            eligibleNetwork = { null },
            observeNetworkAvailability = { AutoCloseable {} },
            nowMillis = { 1_000L },
        )
        try {
            runtime.start()
            runtime.pause(PauseDuration.FIVE_MINUTES)

            assertTrue(attachment.resumed.await(5L, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
            while (states.lastOrNull() !is ProtectionState.AwaitingNetwork &&
                System.nanoTime() < deadline
            ) {
                Thread.yield()
            }

            assertTrue(attachment.pause.get() != null)
            assertTrue(states.last() is ProtectionState.AwaitingNetwork)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `wall clock rollback cannot expire a pause before its persisted deadline`() {
        val now = AtomicLong(90L)
        val firstClockRead = CountDownLatch(1)
        val attachment = AwaitingRuntimeAttachment(
            graphGeneration = 110L,
            pauseDeadlineMillis = 100L,
        )
        val host = AwaitingRuntimeHost(attachment)
        val runtime = ProductionVpnOwnedRuntime(
            service = VpnService(),
            request = VpnOwnedRuntimeRequest(
                owner = VpnServiceOwner(19L),
                origin = com.lavasec.app.platform.StartOrigin.VisibleUser,
                host = CredentialHostSnapshot(host.graphGeneration, host),
                attachment = attachment,
                publication = VpnStatePublication { true },
            ),
            eligibleNetwork = { null },
            observeNetworkAvailability = { AutoCloseable {} },
            nowMillis = {
                now.get().also { firstClockRead.countDown() }
            },
            pauseClockRecheckMillis = 10L,
        )
        try {
            runtime.start()
            runtime.pause(PauseDuration.FIVE_MINUTES)
            assertTrue(firstClockRead.await(5L, TimeUnit.SECONDS))

            now.set(0L)
            assertFalse(attachment.resumed.await(50L, TimeUnit.MILLISECONDS))

            now.set(100L)
            assertTrue(attachment.resumed.await(5L, TimeUnit.SECONDS))
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `wall clock advance expires a pause before the original delay`() {
        val now = AtomicLong(0L)
        val firstClockRead = CountDownLatch(1)
        val attachment = AwaitingRuntimeAttachment(
            graphGeneration = 111L,
            pauseDeadlineMillis = 10_000L,
        )
        val host = AwaitingRuntimeHost(attachment)
        val runtime = ProductionVpnOwnedRuntime(
            service = VpnService(),
            request = VpnOwnedRuntimeRequest(
                owner = VpnServiceOwner(20L),
                origin = com.lavasec.app.platform.StartOrigin.VisibleUser,
                host = CredentialHostSnapshot(host.graphGeneration, host),
                attachment = attachment,
                publication = VpnStatePublication { true },
            ),
            eligibleNetwork = { null },
            observeNetworkAvailability = { AutoCloseable {} },
            nowMillis = {
                now.get().also { firstClockRead.countDown() }
            },
            pauseClockRecheckMillis = 10L,
        )
        try {
            runtime.start()
            runtime.pause(PauseDuration.FIVE_MINUTES)
            assertTrue(firstClockRead.await(5L, TimeUnit.SECONDS))

            now.set(10_000L)
            assertTrue(attachment.resumed.await(500L, TimeUnit.MILLISECONDS))
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `runtime reload republishes the exact durable pause instead of protected`() {
        val identity = ConfigurationIdentity(2L, "two")
        val pause = PauseState(deadlineMillis = 90_000L, protectionSessionId = 7L)

        assertEquals(
            ProtectionState.Paused(90_000L),
            restoredRuntimeProtectionState(
                RuntimePauseDirective.Paused(pause),
                identity,
                VpnOwnership.User,
            ),
        )
        assertEquals(
            ProtectionState.Protected(identity, VpnOwnership.User),
            restoredRuntimeProtectionState(
                RuntimePauseDirective.Filtering,
                identity,
                VpnOwnership.User,
            ),
        )
    }

    @Test
    fun `close after preflight failure still detaches the exact host attachment`() {
        val attachment = RuntimeFenceAttachment(graphGeneration = 100L, accept = true)
        val host = RuntimeFenceHost(attachment)
        val owner = VpnServiceOwner(10L)
        val runtime = ProductionVpnOwnedRuntime(
            service = VpnService(),
            request = VpnOwnedRuntimeRequest(
                owner = owner,
                origin = com.lavasec.app.platform.StartOrigin.VisibleUser,
                host = CredentialHostSnapshot(host.graphGeneration, host),
                attachment = attachment,
                publication = VpnStatePublication { true },
            ),
        )

        runtime.close()
        runtime.close()

        awaitCondition { attachment.detaches.get() == 1 }
        assertEquals(1, attachment.detaches.get())
    }

    @Test
    fun `close clears the chained mode through the current-owner callback`() {
        val attachment = AwaitingRuntimeAttachment(graphGeneration = 112L)
        val host = AwaitingRuntimeHost(attachment)
        val owner = VpnServiceOwner(21L)
        var clearedFor: VpnServiceOwner? = null
        val runtime = ProductionVpnOwnedRuntime(
            service = VpnService(),
            request = VpnOwnedRuntimeRequest(
                owner = owner,
                origin = com.lavasec.app.platform.StartOrigin.VisibleUser,
                host = CredentialHostSnapshot(host.graphGeneration, host),
                attachment = attachment,
                publication = VpnStatePublication { true },
                clearChainedActivityWhenCurrent = { clearedFor = it },
            ),
            eligibleNetwork = { null },
            observeNetworkAvailability = { AutoCloseable {} },
        )
        try {
            runtime.start()
        } finally {
            runtime.close()
        }

        // `finishClose` must route the mode clear through the owner-fenced callback rather than
        // publishing NONE directly, so a superseded candidate cannot clobber the replacement. The
        // teardown is submitted to the engine queue (never inline on the service thread), so wait
        // for it to run before asserting.
        awaitCondition { clearedFor == owner }
        assertEquals(owner, clearedFor)
    }

    @Test
    fun `backpressured private telemetry drops without terminating packet execution`() {
        val attachment = RuntimeFenceAttachment(graphGeneration = 101L, accept = false)
        val host = RuntimeFenceHost(attachment)
        val exact = ExactCredentialRuntimeAttachment(11L, 1L, host, attachment)
        val epoch = ServiceRuntimeEpochGate(11L, 1L)
        val observer = ProductionTunRuntimeObserver(
            serviceSessionGeneration = 11L,
            graphGeneration = 101L,
            engineGeneration = { 1L },
            attachment = exact,
            publication = VpnStatePublication { true },
            epochGate = epoch,
        )

        assertTrue(
            observer.acceptTerminalEvidence(
                TunTerminalEvidence(1L, null, emptyList(), null),
            ),
        )
        assertEquals(1, attachment.attempts.get())
    }

    @Test
    fun `terminal evidence retains producer engine across replacement preflip`() {
        val attachment = RuntimeFenceAttachment(graphGeneration = 103L, accept = true)
        val host = RuntimeFenceHost(attachment)
        val exact = ExactCredentialRuntimeAttachment(13L, 1L, host, attachment)
        val epoch = ServiceRuntimeEpochGate(13L, 1L)
        val healthPublications = mutableListOf<VpnHealthStatus>()
        val health = ExactSessionHealthOwner(
            sessionGeneration = 13L,
            nowMillis = { 1_000L },
            publish = healthPublications::add,
            requestReconnect = {},
        )
        health.installEngine(
            VpnHealthEngineIdentity(
                sessionGeneration = 13L,
                networkGeneration = 1L,
                engineGeneration = 1L,
                primaryResolverIdentity =
                    com.lavasec.app.foundation.health.PrimaryResolverIdentity("device-dns:v4=01010101;v6="),
                fallbackCoverage =
                    com.lavasec.app.foundation.health.ResolverFallbackCoverage.ENCRYPTED_DNS,
            ),
        )
        val observer = ProductionTunRuntimeObserver(
            serviceSessionGeneration = 13L,
            graphGeneration = 103L,
            engineGeneration = { 2L },
            attachment = exact,
            publication = VpnStatePublication { true },
            epochGate = epoch,
            health = health,
        )
        exact.installEngine(2L)
        epoch.installEngine(2L)
        health.installEngine(
            VpnHealthEngineIdentity(
                sessionGeneration = 13L,
                networkGeneration = 1L,
                engineGeneration = 2L,
                primaryResolverIdentity =
                    com.lavasec.app.foundation.health.PrimaryResolverIdentity("device-dns:v4=08080808;v6="),
                fallbackCoverage =
                    com.lavasec.app.foundation.health.ResolverFallbackCoverage.ENCRYPTED_DNS,
            ),
        )
        val revision = healthPublications.last().revision

        assertTrue(
            observer.acceptTerminalEvidence(
                TunTerminalEvidence(
                    engineGeneration = 1L,
                    activity = null,
                    healthEvents = emptyList(),
                    resolverQueryEvidence =
                        com.lavasec.app.foundation.health.ResolverQueryHealthEvidence.primaryFailed(
                            1L,
                            com.lavasec.app.foundation.dns.ResolverFailureReason.TIMEOUT,
                        ),
                ),
            ),
        )

        assertEquals(0, attachment.attempts.get())
        assertEquals(revision, healthPublications.last().revision)
    }

    @Test
    fun `engine rotation rejects late evidence and closed session rejects terminal publication`() {
        val attachment = RuntimeFenceAttachment(graphGeneration = 102L, accept = true)
        val host = RuntimeFenceHost(attachment)
        val exact = ExactCredentialRuntimeAttachment(12L, 1L, host, attachment)
        val epoch = ServiceRuntimeEpochGate(12L, 1L)
        var reportedEngine = 1L
        val states = mutableListOf<ProtectionState>()
        val observer = ProductionTunRuntimeObserver(
            serviceSessionGeneration = 12L,
            graphGeneration = 102L,
            engineGeneration = { reportedEngine },
            attachment = exact,
            publication = VpnStatePublication { state -> states.add(state) },
            epochGate = epoch,
        )

        epoch.installEngine(2L)
        exact.installEngine(2L)
        observer.publishHealth(DnsHealthEvent.TunWriteFailed(TunWriteFailureReason.WRITE_FAILED))
        assertEquals(0, attachment.attempts.get())

        reportedEngine = 2L
        observer.publishHealth(DnsHealthEvent.TunWriteFailed(TunWriteFailureReason.WRITE_FAILED))
        assertEquals(1, attachment.attempts.get())

        epoch.close()
        observer.publishFailure(ProtectionFailure.TunnelEnded)
        assertTrue(states.isEmpty())
        assertFalse(epoch.completeForSession { true })
    }

    @Test
    fun `protection uptime evidence is fenced by exact service engine and graph`() {
        val attachment = RuntimeFenceAttachment(graphGeneration = 104L, accept = true)
        val exact = ExactCredentialRuntimeAttachment(
            expectedServiceSessionGeneration = 14L,
            expectedEngineGeneration = 1L,
            host = RuntimeFenceHost(attachment),
            attachment = attachment,
        )

        assertTrue(
            exact.acceptProtectionEvidence(
                VpnProtectionEvidence.State(14L, 1L, 104L, 1_000L, active = true),
            ),
        )
        exact.installEngine(2L)
        assertFalse(
            exact.acceptProtectionEvidence(
                VpnProtectionEvidence.Heartbeat(14L, 1L, 104L, 2_000L),
            ),
        )
        assertTrue(
            exact.acceptProtectionEvidence(
                VpnProtectionEvidence.Heartbeat(14L, 2L, 104L, 2_000L),
            ),
        )
        exact.close()
        assertFalse(
            exact.acceptProtectionEvidence(
                VpnProtectionEvidence.State(14L, 2L, 104L, 3_000L, active = false),
            ),
        )
        assertEquals(2, attachment.protectionEvidence.size)
    }

    @Test
    fun `accepted terminal failure is retained for exact owner retirement`() {
        val attachment = RuntimeFenceAttachment(graphGeneration = 105L, accept = true)
        var terminal: ProtectionFailure? = null
        val observer = ProductionTunRuntimeObserver(
            serviceSessionGeneration = 15L,
            graphGeneration = 105L,
            engineGeneration = { 1L },
            attachment = ExactCredentialRuntimeAttachment(
                15L,
                1L,
                RuntimeFenceHost(attachment),
                attachment,
            ),
            publication = VpnStatePublication { true },
            epochGate = ServiceRuntimeEpochGate(15L, 1L),
            onFailure = { terminal = it },
        )

        observer.publishFailure(ProtectionFailure.TunnelReadFailed)

        assertEquals(ProtectionFailure.TunnelReadFailed, terminal)
    }
    @Test
    fun `receipt storage exception drops only private terminal telemetry`() {
        val attempts = AtomicInteger()
        val attachment = object : CredentialVpnAttachment {
            override val graphGeneration = 107L
            override fun tryAcceptTelemetry(telemetry: VpnTelemetry): Boolean {
                attempts.incrementAndGet()
                throw IllegalStateException("namespace storage unavailable")
            }
            override fun detach() = Unit
        }
        val host = object : CredentialVpnHostPort {
            override val graphGeneration = 107L
            override fun openAttachment(request: CredentialVpnAttachRequest) = attachment
        }
        val exact = ExactCredentialRuntimeAttachment(17L, 1L, host, attachment)
        val epoch = ServiceRuntimeEpochGate(17L, 1L)
        val publications = mutableListOf<VpnHealthStatus>()
        val health = ExactSessionHealthOwner(
            sessionGeneration = 17L,
            nowMillis = { 1_000L },
            publish = publications::add,
            requestReconnect = {},
        )
        health.installEngine(VpnHealthEngineIdentity(
            sessionGeneration = 17L, networkGeneration = 1L, engineGeneration = 1L,
            primaryResolverIdentity =
                com.lavasec.app.foundation.health.PrimaryResolverIdentity("device-dns:v4=01010101;v6="),
            fallbackCoverage =
                com.lavasec.app.foundation.health.ResolverFallbackCoverage.ENCRYPTED_DNS,
        ))
        val failures = mutableListOf<ProtectionFailure>()
        val states = mutableListOf<ProtectionState>()
        val observer = ProductionTunRuntimeObserver(
            serviceSessionGeneration = 17L, graphGeneration = 107L,
            engineGeneration = { 1L }, attachment = exact,
            publication = VpnStatePublication { states += it; true }, epochGate = epoch,
            health = health, onFailure = failures::add,
        )
        val event = com.lavasec.app.foundation.dns.DnsDecisionEvent(
            id = 1L, timestampMillis = 1_000L, normalizedDomain = "terminal-fault.example",
            decision = com.lavasec.app.foundation.filter.FilterDecision(
                com.lavasec.app.foundation.filter.FilterAction.ALLOW,
                com.lavasec.app.foundation.filter.FilterDecisionReason.DEFAULT_ALLOW,
            ),
        )
        // This is the real post-write observer boundary; it does not claim a test TUN send.
        assertTrue(observer.acceptTerminalEvidence(TunTerminalEvidence(1L, event, emptyList(), null)))
        assertEquals(1, attempts.get())
        assertEquals(1L, publications.last().droppedPrivateTelemetryCount)
        assertTrue(failures.isEmpty())
        assertTrue(states.isEmpty())
        assertTrue(epoch.completeIfCurrent(1L) { true })
    }

}

private fun awaitCondition(timeoutMillis: Long = 5_000L, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    while (!condition() && System.nanoTime() < deadline) {
        Thread.yield()
    }
}

private class AwaitingRuntimeHost(
    private val attachment: AwaitingRuntimeAttachment,
) : CredentialVpnHostPort {
    override val graphGeneration: Long = attachment.graphGeneration

    override fun openAttachment(request: CredentialVpnAttachRequest): CredentialVpnAttachment =
        attachment
}

private class AwaitingRuntimeAttachment(
    override val graphGeneration: Long,
    private val reportPersistedPauseAsLogicallyExpired: Boolean = false,
    private val resumeSucceeds: Boolean = true,
    private val pauseDeadlineMillis: Long = 1_000L,
) : CredentialVpnRuntimeAttachment {
    val detaches = AtomicInteger()
    val resumed = CountDownLatch(1)
    val credentialOpens = AtomicInteger()
    val credentialRead = CountDownLatch(1)
    val pause = AtomicReference<PauseState?>(null)

    override val configurationIdentity = ConfigurationIdentity(1L, "identity")
    override val resolverConfiguration = ResolverConfiguration()
    override val activeProtectionSessionId: Long = 1L
    override val configureIntent: PendingIntent
        get() = error("configure intent is not read while awaiting a network")

    override fun openFilterRuntimeLease(): VerifiedFilterRuntimeLease =
        error("filter lease is not opened while awaiting a network")

    override fun currentPause(): RuntimePauseDirective =
        if (reportPersistedPauseAsLogicallyExpired) {
            RuntimePauseDirective.Filtering
        } else {
            pause.get()?.let(RuntimePauseDirective::Paused) ?: RuntimePauseDirective.Filtering
        }

    override fun openChainedCredentials(): com.lavasec.app.vpn.chained.ChainedVpnCredentials? {
        credentialOpens.incrementAndGet()
        credentialRead.countDown()
        return null
    }

    override fun requestPause(duration: PauseDuration): PauseState =
        PauseState(pauseDeadlineMillis, activeProtectionSessionId).also(pause::set)

    override fun requestResume(expectedPause: PauseState?): Boolean {
        if (expectedPause != null && pause.get() != expectedPause) return false
        resumed.countDown()
        if (resumeSucceeds) pause.set(null)
        return resumeSucceeds
    }

    override fun tryAcceptTelemetry(telemetry: VpnTelemetry): Boolean = false

    override fun detach() {
        detaches.incrementAndGet()
    }
}

private class RuntimeFenceHost(
    private val attachment: RuntimeFenceAttachment,
) : CredentialVpnHostPort {
    override val graphGeneration: Long = attachment.graphGeneration

    override fun openAttachment(request: CredentialVpnAttachRequest): CredentialVpnAttachment =
        attachment
}

private class RuntimeFenceAttachment(
    override val graphGeneration: Long,
    private val accept: Boolean,
) : CredentialVpnAttachment {
    val attempts = AtomicInteger()
    val detaches = AtomicInteger()
    val protectionEvidence = mutableListOf<VpnProtectionEvidence>()

    override fun tryAcceptTelemetry(telemetry: VpnTelemetry): Boolean {
        attempts.incrementAndGet()
        return accept
    }

    override fun acceptProtectionEvidence(evidence: VpnProtectionEvidence): Boolean {
        protectionEvidence += evidence
        return accept
    }

    override fun detach() {
        detaches.incrementAndGet()
    }
}
