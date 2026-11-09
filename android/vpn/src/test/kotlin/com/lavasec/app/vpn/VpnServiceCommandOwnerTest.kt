package com.lavasec.app.vpn

import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.foundation.pause.PauseDuration
import com.lavasec.app.platform.ProtectionFailure
import com.lavasec.app.platform.ProtectionState
import com.lavasec.app.platform.StartOrigin
import com.lavasec.app.platform.VpnOwnership
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnServiceCommandOwnerTest {
    @Test
    fun `foreground admission precedes host discovery construction and expensive start`() {
        val events = mutableListOf<String>()
        val hostRegistry = CredentialVpnHostRegistry()
        hostRegistry.attach(CommandHost(71L, events))
        val owner = VpnServiceCommandOwner(
            foreground = VpnForegroundAdmission {
                events += "foreground"
            },
            hostRegistry = hostRegistry,
            state = ExactVpnServiceState(),
            runtimeFactory = VpnOwnedRuntimeFactory { request ->
                events += "construct:${request.host.graphGeneration}"
                RecordingOwnedRuntime(request, events)
            },
        )

        assertEquals(
            VpnCommandResult.Sticky,
            owner.handle(VpnServiceCommand.Start(StartOrigin.VisibleUser)),
        )

        assertEquals(listOf("foreground", "attach:1", "construct:71", "preflight:1", "start:1"), events)
    }

    @Test
    fun `a replacement preflights before retiring the predecessor`() {
        val events = mutableListOf<String>()
        val hostRegistry = CredentialVpnHostRegistry()
        hostRegistry.attach(CommandHost(76L, events))
        val owner = VpnServiceCommandOwner(
            foreground = VpnForegroundAdmission {},
            hostRegistry = hostRegistry,
            state = ExactVpnServiceState(),
            runtimeFactory = VpnOwnedRuntimeFactory { request ->
                RecordingOwnedRuntime(request, events)
            },
        )
        owner.handle(VpnServiceCommand.Start(StartOrigin.VisibleUser))
        events.clear()

        owner.handle(VpnServiceCommand.Start(StartOrigin.Reload))

        // The replacement resolves its endpoint (preflight) and starts while the predecessor still
        // owns its tunnel; only then is the predecessor closed and the replacement adopted, so a
        // slow lookup/start cannot leave a no-tunnel gap (a make-before-break handoff).
        val preflight = events.indexOf("preflight:2")
        val close = events.indexOf("close:1")
        val start = events.indexOf("start:2")
        assertTrue("preflight recorded: $events", preflight >= 0)
        assertTrue("predecessor close recorded: $events", close >= 0)
        assertTrue("replacement start recorded: $events", start >= 0)
        assertTrue("preflight must precede the replacement start: $events", preflight < start)
        assertTrue("the replacement start must precede the predecessor close: $events", start < close)
    }

    @Test
    fun `a racing successor captures the live predecessor, not the in-flight candidate`() {
        val state = ExactVpnServiceState()
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val closeOrder = CopyOnWriteArrayList<Int>()
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val runtimes = mutableListOf<BlockingStartRuntime>()
        val predecessorClosedWhenCandidateStarted = AtomicBoolean(true)
        try {
            val owner = VpnServiceCommandOwner(
                foreground = VpnForegroundAdmission {},
                hostRegistry = CredentialVpnHostRegistry().apply {
                    attach(CommandHost(115L, mutableListOf()))
                },
                state = state,
                runtimeFactory = VpnOwnedRuntimeFactory {
                    val index = runtimes.size
                    if (index == 0) {
                        // The initial predecessor starts immediately.
                        BlockingStartRuntime(index, closeOrder).also(runtimes::add)
                    } else {
                        // Every replacement's start blocks, so a successor can race it while it is
                        // still inside the non-suspending start.
                        BlockingStartRuntime(
                            index = index,
                            closeOrder = closeOrder,
                            startEntered = startEntered,
                            releaseStart = releaseStart,
                            onStart = {
                                if (index == 1) {
                                    predecessorClosedWhenCandidateStarted.set(
                                        runtimes.first().closed,
                                    )
                                }
                            },
                        ).also(runtimes::add)
                    }
                },
                scope = CoroutineScope(SupervisorJob() + dispatcher),
            )

            // The initial predecessor (generation 1) is established and adopted before the reloads.
            owner.handle(VpnServiceCommand.Start(StartOrigin.VisibleUser))
            val startDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
            while (!runtimes[0].started && System.nanoTime() < startDeadline) {
                Thread.yield()
            }
            assertTrue("the initial predecessor must start", runtimes[0].started)
            // Barrier on the same single-thread executor so the initial commit (adoption) has
            // finished before the reloads are issued.
            val initialCommitted = CountDownLatch(1)
            executor.execute { initialCommitted.countDown() }
            assertTrue("the initial predecessor must commit", initialCommitted.await(5, TimeUnit.SECONDS))
            val firstGeneration = (state.state.value as? ProtectionState.Starting)?.generation
            assertNotNull(firstGeneration)

            // Reload #1 installs candidate generation 2; its start blocks (a slow chained start).
            assertEquals(
                VpnCommandResult.Sticky,
                owner.handle(
                    VpnServiceCommand.Reload(expectedServiceGeneration = firstGeneration),
                ),
            )
            assertTrue("the replacement must enter start", startEntered.await(5, TimeUnit.SECONDS))
            val secondGeneration = (state.state.value as? ProtectionState.Starting)?.generation
            assertNotNull(secondGeneration)

            // Reload #2 races the still-in-flight generation-2 candidate. It must capture the
            // still-live predecessor (generation 1), not the not-yet-installed candidate; with the
            // bug the successor's preflight would run with no functioning tunnel.
            assertEquals(
                VpnCommandResult.Sticky,
                owner.handle(
                    VpnServiceCommand.Reload(expectedServiceGeneration = secondGeneration),
                ),
            )

            releaseStart.countDown()
            val settleDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
            while (closeOrder.size < 2 && System.nanoTime() < settleDeadline) {
                Thread.yield()
            }
            assertTrue(
                "the successor must install and retire a predecessor: $closeOrder",
                closeOrder.size >= 2,
            )
            assertFalse(
                "the predecessor must still be live when the in-flight candidate starts",
                predecessorClosedWhenCandidateStarted.get(),
            )
            // The superseded generation-2 candidate closes first; the live generation-1 predecessor
            // is retired only after the successor (generation 3) starts and adopts it.
            assertEquals(listOf(1, 0), closeOrder.toList())
            owner.close()
        } finally {
            releaseStart.countDown()
            dispatcher.close()
        }
    }

    @Test
    fun `a stale reload after a failed start is terminal, not superseded`() {
        val state = ExactVpnServiceState()
        var refuse = false
        val owner = VpnServiceCommandOwner(
            foreground = VpnForegroundAdmission {},
            hostRegistry = CredentialVpnHostRegistry().apply {
                attach(CommandHost(101L, mutableListOf()))
            },
            state = state,
            runtimeFactory = VpnOwnedRuntimeFactory { request ->
                if (refuse) error("factory refused")
                RecordingOwnedRuntime(request, mutableListOf())
            },
        )
        // The first start succeeds and installs generation 1.
        assertEquals(
            VpnCommandResult.Sticky,
            owner.handle(VpnServiceCommand.Start(StartOrigin.VisibleUser)),
        )

        // A second start installs generation 2 and then fails; it must leave no owner behind.
        refuse = true
        assertEquals(
            VpnCommandResult.NotSticky,
            owner.handle(VpnServiceCommand.Start(StartOrigin.Reload)),
        )

        // A reload bound to the (now retired) generation 1 has no live runtime to rebuild: it must
        // finish the start, not be treated as superseded (which would keep the service alive
        // runtime-less so a null-intent restart could re-enable protection).
        assertEquals(
            VpnCommandResult.NotSticky,
            owner.handle(VpnServiceCommand.Reload(expectedServiceGeneration = 1L)),
        )
    }

    @Test
    fun `replacement fences old callbacks before close and exposes only successor state`() {
        val state = ExactVpnServiceState()
        val runtimes = mutableListOf<RecordingOwnedRuntime>()
        val owner = VpnServiceCommandOwner(
            foreground = VpnForegroundAdmission {},
            hostRegistry = CredentialVpnHostRegistry().apply {
                attach(CommandHost(81L, mutableListOf()))
            },
            state = state,
            runtimeFactory = VpnOwnedRuntimeFactory { request ->
                RecordingOwnedRuntime(request, mutableListOf()).also(runtimes::add)
            },
        )
        val identity = ConfigurationIdentity(4L, "current")

        owner.handle(VpnServiceCommand.Start(StartOrigin.VisibleUser))
        assertTrue(
            runtimes.single().request.publication.publish(
                ProtectionState.Protected(identity, VpnOwnership.User),
            ),
        )
        owner.handle(VpnServiceCommand.Start(StartOrigin.Reload))

        assertEventuallyClosed("the predecessor must be closed") { runtimes.first().closed }
        assertFalse(
            runtimes.first().request.publication.publish(
                ProtectionState.Failed(ProtectionFailure.TunnelEnded),
            ),
        )
        assertTrue(
            runtimes.last().request.publication.publish(
                ProtectionState.Protected(identity, VpnOwnership.User),
            ),
        )
        assertEquals(
            ProtectionState.Protected(identity, VpnOwnership.User),
            state.state.value,
        )
    }

    @Test
    fun `missing unlocked host fails closed without constructing a runtime`() {
        var constructions = 0
        val state = ExactVpnServiceState()
        val owner = VpnServiceCommandOwner(
            foreground = VpnForegroundAdmission {},
            hostRegistry = CredentialVpnHostRegistry(),
            state = state,
            runtimeFactory = VpnOwnedRuntimeFactory {
                constructions += 1
                error("must not construct")
            },
        )

        assertEquals(
            VpnCommandResult.NotSticky,
            owner.handle(VpnServiceCommand.Start(StartOrigin.VisibleUser)),
        )

        assertEquals(0, constructions)
        assertEquals(
            ProtectionState.Failed(ProtectionFailure.DependencyUnavailable),
            state.state.value,
        )
    }

    @Test
    fun `stop fences callbacks and closes attachment exactly once`() {
        val host = CommandHost(91L, mutableListOf())
        val state = ExactVpnServiceState()
        lateinit var runtime: RecordingOwnedRuntime
        val owner = VpnServiceCommandOwner(
            foreground = VpnForegroundAdmission {},
            hostRegistry = CredentialVpnHostRegistry().apply { attach(host) },
            state = state,
            runtimeFactory = VpnOwnedRuntimeFactory { request ->
                RecordingOwnedRuntime(request, mutableListOf()).also { runtime = it }
            },
        )
        owner.handle(VpnServiceCommand.Start(StartOrigin.SystemAlwaysOn))

        assertEquals(VpnCommandResult.NotSticky, owner.handle(VpnServiceCommand.Stop))
        assertEquals(ProtectionState.Off, state.state.value)
        assertEventuallyClosed("the stopped runtime must be closed") { runtime.closed }
        assertEquals(1, host.attachments.single().detachCount)
        assertEquals(VpnCommandResult.NotSticky, owner.handle(VpnServiceCommand.Stop))
        assertEquals(1, host.attachments.single().detachCount)
    }

    @Test
    fun `a stale reload after a stop finishes the service start`() {
        val state = ExactVpnServiceState()
        var constructions = 0
        val owner = VpnServiceCommandOwner(
            foreground = VpnForegroundAdmission {},
            hostRegistry = CredentialVpnHostRegistry().apply {
                attach(CommandHost(97L, mutableListOf()))
            },
            state = state,
            runtimeFactory = VpnOwnedRuntimeFactory { request ->
                constructions += 1
                RecordingOwnedRuntime(request, mutableListOf())
            },
        )
        owner.handle(VpnServiceCommand.Start(StartOrigin.VisibleUser))
        val generation = (state.state.value as? ProtectionState.Starting)?.generation
        assertNotNull(generation)

        // The user stops protection; a chained preference reload was queued behind it carrying the
        // generation observed before the stop. With no runtime left there is nothing to rebuild, so
        // the result must be terminal (`NotSticky`) and the service must stop rather than linger
        // and let a later sticky restart re-enable protection.
        owner.handle(VpnServiceCommand.Stop)
        assertEquals(
            VpnCommandResult.NotSticky,
            owner.handle(VpnServiceCommand.Reload(expectedServiceGeneration = generation)),
        )

        // No runtime was reconstructed and protection stays off.
        assertEquals(1, constructions)
        assertEquals(ProtectionState.Off, state.state.value)
    }

    @Test
    fun `a stale reload with a newer owner is superseded, not terminal`() {
        val state = ExactVpnServiceState()
        var constructions = 0
        val owner = VpnServiceCommandOwner(
            foreground = VpnForegroundAdmission {},
            hostRegistry = CredentialVpnHostRegistry().apply {
                attach(CommandHost(99L, mutableListOf()))
            },
            state = state,
            runtimeFactory = VpnOwnedRuntimeFactory { request ->
                constructions += 1
                RecordingOwnedRuntime(request, mutableListOf())
            },
        )
        owner.handle(VpnServiceCommand.Start(StartOrigin.VisibleUser))
        val firstGeneration = (state.state.value as? ProtectionState.Starting)?.generation
        owner.handle(VpnServiceCommand.Start(StartOrigin.SystemAlwaysOn))
        val secondGeneration = (state.state.value as? ProtectionState.Starting)?.generation
        assertNotNull(firstGeneration)
        assertNotNull(secondGeneration)
        assertNotEquals(firstGeneration, secondGeneration)

        // The reload bound to the retired first generation must not finish the newer service start.
        assertEquals(
            VpnCommandResult.Superseded,
            owner.handle(VpnServiceCommand.Reload(expectedServiceGeneration = firstGeneration)),
        )
        assertEquals(2, constructions)
    }

    @Test
    fun `a reload matching the installed generation rebuilds`() {
        val state = ExactVpnServiceState()
        var constructions = 0
        val owner = VpnServiceCommandOwner(
            foreground = VpnForegroundAdmission {},
            hostRegistry = CredentialVpnHostRegistry().apply {
                attach(CommandHost(98L, mutableListOf()))
            },
            state = state,
            runtimeFactory = VpnOwnedRuntimeFactory { request ->
                constructions += 1
                RecordingOwnedRuntime(request, mutableListOf())
            },
        )
        owner.handle(VpnServiceCommand.Start(StartOrigin.VisibleUser))
        val generation = (state.state.value as? ProtectionState.Starting)?.generation
        assertNotNull(generation)

        assertEquals(
            VpnCommandResult.Sticky,
            owner.handle(VpnServiceCommand.Reload(expectedServiceGeneration = generation)),
        )
        assertEquals(2, constructions)
    }

    @Test
    fun `a preference reload supersedes a pending initial start`() {
        val state = ExactVpnServiceState()
        val preflightEntered = CountDownLatch(1)
        val releasePreflight = CountDownLatch(1)
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val runtimes = mutableListOf<BlockingPreflightRuntime>()
        try {
            val owner = VpnServiceCommandOwner(
                foreground = VpnForegroundAdmission {},
                hostRegistry = CredentialVpnHostRegistry().apply {
                    attach(CommandHost(112L, mutableListOf()))
                },
                state = state,
                runtimeFactory = VpnOwnedRuntimeFactory {
                    BlockingPreflightRuntime(preflightEntered, releasePreflight).also(runtimes::add)
                },
                scope = CoroutineScope(SupervisorJob() + dispatcher),
            )
            assertEquals(
                VpnCommandResult.Sticky,
                owner.handle(VpnServiceCommand.Start(StartOrigin.VisibleUser)),
            )
            assertTrue(preflightEntered.await(5, TimeUnit.SECONDS))
            val generation = (state.state.value as? ProtectionState.Starting)?.generation
            assertNotNull(generation)

            // The initial start is still preflighting. A reload (a chained preference change
            // committing during the slow lookup) must supersede that candidate and run a fresh
            // start: the candidate may already hold credentials opened under the previous
            // preference, so adopting it would install a mode the changed preference no longer
            // wants while status reports the preference off. The fresh preflight re-reads the gate.
            assertEquals(
                VpnCommandResult.Sticky,
                owner.handle(VpnServiceCommand.Reload(expectedServiceGeneration = generation)),
            )
            assertEquals(2, runtimes.size)

            releasePreflight.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
            while (runtimes.getOrNull(1)?.started != true && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertTrue(
                "the fresh candidate must start: ${runtimes.map { it.started }}",
                runtimes[1].started,
            )
            assertEventuallyClosed("the superseded candidate must be closed") { runtimes[0].closed }
            owner.close()
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `a chained reconnect runs off the service thread and a stop stays responsive`() {
        val state = ExactVpnServiceState()
        val reconnectEntered = CountDownLatch(1)
        val releaseReconnect = CountDownLatch(1)
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val runtimes = mutableListOf<BlockingReconnectRuntime>()
        try {
            val owner = VpnServiceCommandOwner(
                foreground = VpnForegroundAdmission {},
                hostRegistry = CredentialVpnHostRegistry().apply {
                    attach(CommandHost(113L, mutableListOf()))
                },
                state = state,
                runtimeFactory = VpnOwnedRuntimeFactory {
                    BlockingReconnectRuntime(reconnectEntered, releaseReconnect).also(runtimes::add)
                },
                scope = CoroutineScope(SupervisorJob() + dispatcher),
                // The close must run on a different thread than the (blocked) command scope, as it
                // does in production where `scope` is Dispatchers.IO.
                teardownDispatcher = Dispatchers.IO,
            )
            owner.handle(VpnServiceCommand.Start(StartOrigin.VisibleUser))
            val startDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
            while (!runtimes.single().started && System.nanoTime() < startDeadline) {
                Thread.yield()
            }
            assertTrue("the initial runtime must start", runtimes.single().started)

            // A chained reconnect internally preflights its endpoint (a credential/Keystore read
            // and a blocking hostname lookup) before it drops the live predecessor. It must run off
            // the service thread, so the command is accepted without waiting for the action.
            val handled = CountDownLatch(1)
            val commandThread = Thread {
                owner.handle(VpnServiceCommand.Reconnect)
                handled.countDown()
            }
            commandThread.start()
            assertTrue("the reconnect command must be accepted", handled.await(5, TimeUnit.SECONDS))
            assertTrue(
                "the reconnect action must run off the service thread",
                reconnectEntered.await(5, TimeUnit.SECONDS),
            )

            // While the reconnect is still blocked, a stop must still be serviced on the calling
            // thread: the reconnect no longer holds the command owner's monitor, and its resolved
            // outcome is fenced on the runtime identity it started with. The runtime's blocking
            // close is deferred to the teardown dispatcher, so the command returns without waiting.
            assertEquals(VpnCommandResult.NotSticky, owner.handle(VpnServiceCommand.Stop))
            val closeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
            while (!runtimes.single().closed && System.nanoTime() < closeDeadline) {
                Thread.yield()
            }
            assertTrue("the stopped runtime must be closed", runtimes.single().closed)
            assertEquals(ProtectionState.Off, state.state.value)

            releaseReconnect.countDown()
            commandThread.join(TimeUnit.SECONDS.toMillis(5))
        } finally {
            releaseReconnect.countDown()
            dispatcher.close()
        }
    }

    @Test
    fun `a chained resume runs off the service thread and a stop stays responsive`() {
        val state = ExactVpnServiceState()
        val resumeEntered = CountDownLatch(1)
        val releaseResume = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val runtimes = mutableListOf<BlockingResumeRuntime>()
        try {
            val owner = VpnServiceCommandOwner(
                foreground = VpnForegroundAdmission {},
                hostRegistry = CredentialVpnHostRegistry().apply {
                    attach(CommandHost(119L, mutableListOf()))
                },
                state = state,
                runtimeFactory = VpnOwnedRuntimeFactory {
                    BlockingResumeRuntime(resumeEntered, releaseResume).also(runtimes::add)
                },
                scope = CoroutineScope(SupervisorJob() + dispatcher),
                // The close must run on a different thread than the (blocked) command scope, as it
                // does in production where `scope` is Dispatchers.IO.
                teardownDispatcher = Dispatchers.IO,
            )
            owner.handle(VpnServiceCommand.Start(StartOrigin.VisibleUser))
            val startDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
            while (!runtimes.single().started && System.nanoTime() < startDeadline) {
                Thread.yield()
            }
            assertTrue("the initial runtime must start", runtimes.single().started)
            // Barrier on the same single-thread executor so the initial commit (adoption) has
            // finished before the resume is issued.
            val committed = CountDownLatch(1)
            executor.execute { committed.countDown() }
            assertTrue("the initial runtime must commit", committed.await(5, TimeUnit.SECONDS))

            // A chained resume reconciles activation through a production attachment that blocks on
            // DataStore/Keystore reads. It must run off the service thread, so the command is
            // accepted without waiting for the action.
            val handled = CountDownLatch(1)
            val commandThread = Thread {
                owner.handle(VpnServiceCommand.Resume)
                handled.countDown()
            }
            commandThread.start()
            assertTrue("the resume command must be accepted", handled.await(5, TimeUnit.SECONDS))
            assertTrue(
                "the resume action must run off the service thread",
                resumeEntered.await(5, TimeUnit.SECONDS),
            )

            // While the resume is still blocked, a stop must still be serviced on the calling
            // thread: the resume no longer holds the command owner's monitor, and its resolved
            // outcome is fenced on the runtime identity it started with. The runtime's blocking
            // close is deferred to the teardown dispatcher, so the command returns without waiting.
            assertEquals(VpnCommandResult.NotSticky, owner.handle(VpnServiceCommand.Stop))
            val closeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
            while (!runtimes.single().closed && System.nanoTime() < closeDeadline) {
                Thread.yield()
            }
            assertTrue("the stopped runtime must be closed", runtimes.single().closed)
            assertEquals(ProtectionState.Off, state.state.value)

            releaseResume.countDown()
            commandThread.join(TimeUnit.SECONDS.toMillis(5))
        } finally {
            releaseResume.countDown()
            dispatcher.close()
        }
    }

    @Test
    fun `a superseded reload is not terminal for the service start`() {
        // `onStartCommand` keeps the service running (START_STICKY) for both applied commands and a
        // superseded reload; only a genuinely terminal result finishes the start.
        assertFalse(isTerminalStart(VpnCommandResult.Sticky))
        assertFalse(isTerminalStart(VpnCommandResult.Superseded))
        assertTrue(isTerminalStart(VpnCommandResult.NotSticky))
    }

    @Test
    fun `pause resume and reconnect stay on the installed runtime`() {
        lateinit var runtime: RecordingOwnedRuntime
        val owner = VpnServiceCommandOwner(
            foreground = VpnForegroundAdmission {},
            hostRegistry = CredentialVpnHostRegistry().apply {
                attach(CommandHost(92L, mutableListOf()))
            },
            state = ExactVpnServiceState(),
            runtimeFactory = VpnOwnedRuntimeFactory { request ->
                RecordingOwnedRuntime(request, mutableListOf()).also { runtime = it }
            },
        )
        owner.handle(VpnServiceCommand.Start(StartOrigin.VisibleUser))

        assertEquals(
            VpnCommandResult.Sticky,
            owner.handle(VpnServiceCommand.Pause(PauseDuration.FIVE_MINUTES)),
        )
        assertEquals(VpnCommandResult.Sticky, owner.handle(VpnServiceCommand.Resume))
        assertEquals(VpnCommandResult.Sticky, owner.handle(VpnServiceCommand.Reconnect))
        assertEquals(
            listOf("pause:FIVE_MINUTES", "resume", "reconnect"),
            runtime.controls,
        )
    }

    @Test
    fun `revocation closes resources and preserves the revoked terminal state`() {
        val state = ExactVpnServiceState()
        lateinit var runtime: RecordingOwnedRuntime
        val owner = VpnServiceCommandOwner(
            foreground = VpnForegroundAdmission {},
            hostRegistry = CredentialVpnHostRegistry().apply {
                attach(CommandHost(94L, mutableListOf()))
            },
            state = state,
            runtimeFactory = VpnOwnedRuntimeFactory { request ->
                RecordingOwnedRuntime(request, mutableListOf()).also { runtime = it }
            },
        )
        owner.handle(VpnServiceCommand.Start(StartOrigin.SystemAlwaysOn))

        owner.handle(VpnServiceCommand.Revoke)

        assertEventuallyClosed("the revoked runtime must be closed") { runtime.closed }
        assertEquals(
            ProtectionState.Failed(ProtectionFailure.Revoked),
            state.state.value,
        )
    }

    @Test
    fun `an asynchronous reconnect failure fences and closes the failed runtime`() {
        val state = ExactVpnServiceState()
        var terminalExits = 0
        lateinit var runtime: RecordingOwnedRuntime
        val owner = VpnServiceCommandOwner(
            foreground = VpnForegroundAdmission {},
            hostRegistry = CredentialVpnHostRegistry().apply {
                attach(CommandHost(93L, mutableListOf()))
            },
            state = state,
            runtimeFactory = VpnOwnedRuntimeFactory { request ->
                RecordingOwnedRuntime(request, mutableListOf()).also {
                    it.reconnectFailure = VpnRuntimeStartException(
                        ProtectionFailure.EngineUnavailable,
                    )
                    runtime = it
                }
            },
            terminalExit = VpnTerminalServiceExit { terminalExits += 1 },
        )
        owner.handle(VpnServiceCommand.Start(StartOrigin.VisibleUser))

        // The reconnect action runs asynchronously (the default scope is Unconfined, so it
        // completes inline here). Its failure is terminal for the service, not a terminal command
        // result: the command reports Sticky and the failure finishes the start via terminalExit.
        assertEquals(VpnCommandResult.Sticky, owner.handle(VpnServiceCommand.Reconnect))

        assertEventuallyClosed("the failed runtime must be closed") { runtime.closed }
        assertEquals(1, terminalExits)
        assertEquals(
            ProtectionState.Failed(ProtectionFailure.EngineUnavailable),
            state.state.value,
        )
        assertFalse(
            runtime.request.publication.publish(
                ProtectionState.Protected(ConfigurationIdentity(8L, "stale"), VpnOwnership.User),
            ),
        )
    }

    @Test
    fun `exact terminal runtime completion retires state and exits foreground ownership`() {
        val state = ExactVpnServiceState()
        var terminalExits = 0
        lateinit var runtime: RecordingOwnedRuntime
        val owner = VpnServiceCommandOwner(
            foreground = VpnForegroundAdmission {},
            hostRegistry = CredentialVpnHostRegistry().apply {
                attach(CommandHost(95L, mutableListOf()))
            },
            state = state,
            runtimeFactory = VpnOwnedRuntimeFactory { request ->
                RecordingOwnedRuntime(request, mutableListOf()).also { runtime = it }
            },
            terminalExit = VpnTerminalServiceExit { terminalExits += 1 },
        )
        owner.handle(VpnServiceCommand.Start(StartOrigin.VisibleUser))

        assertTrue(
            runtime.request.terminalPublication.publish(
                runtime,
                ProtectionFailure.TunnelEnded,
            ),
        )

        assertEquals(ProtectionState.Failed(ProtectionFailure.TunnelEnded), state.state.value)
        assertEquals(1, terminalExits)
        assertEquals(VpnCommandResult.NotSticky, owner.handle(VpnServiceCommand.Resume))
    }

    @Test
    fun `an accepted terminal clears the chained mode under the monitor, a stale one does not`() {
        val state = ExactVpnServiceState()
        val runtimes = mutableListOf<RecordingOwnedRuntime>()
        var clears = 0
        val owner = VpnServiceCommandOwner(
            foreground = VpnForegroundAdmission {},
            hostRegistry = CredentialVpnHostRegistry().apply {
                attach(CommandHost(96L, mutableListOf()))
            },
            state = state,
            runtimeFactory = VpnOwnedRuntimeFactory { request ->
                RecordingOwnedRuntime(request, mutableListOf()).also(runtimes::add)
            },
            clearChainedActivity = { clears += 1 },
        )
        owner.handle(VpnServiceCommand.Start(StartOrigin.VisibleUser))

        // The installed runtime's terminal completion is accepted: it retires the exact owner and
        // clears the chained mode as part of that retirement.
        assertTrue(
            runtimes[0].request.terminalPublication.publish(
                runtimes[0],
                ProtectionFailure.TunnelEnded,
            ),
        )
        assertEquals(1, clears)

        // A replacement is installed; the superseded runtime's completion is rejected and must not
        // clear the replacement's mode.
        owner.handle(VpnServiceCommand.Start(StartOrigin.Reload))
        assertFalse(
            runtimes[0].request.terminalPublication.publish(
                runtimes[0],
                ProtectionFailure.TunnelEnded,
            ),
        )
        assertEquals(1, clears)
    }

    @Test
    fun `a superseded candidate close does not clear the replacement chained mode`() {
        val state = ExactVpnServiceState()
        val runtimes = mutableListOf<RecordingOwnedRuntime>()
        var clears = 0
        val owner = VpnServiceCommandOwner(
            foreground = VpnForegroundAdmission {},
            hostRegistry = CredentialVpnHostRegistry().apply {
                attach(CommandHost(114L, mutableListOf()))
            },
            state = state,
            runtimeFactory = VpnOwnedRuntimeFactory { request ->
                RecordingOwnedRuntime(request, mutableListOf()).also(runtimes::add)
            },
            clearChainedActivity = { clears += 1 },
        )
        owner.handle(VpnServiceCommand.Start(StartOrigin.VisibleUser))

        // The installed runtime is the current owner: its close-time clear applies.
        runtimes[0].request.clearChainedActivityWhenCurrent(runtimes[0].request.owner)
        assertEquals(1, clears)

        // A replacement supersedes it; the old runtime's close-time clear must be a no-op so it
        // cannot publish NONE over the replacement's DNS_ONLY/CHAINED mode.
        owner.handle(VpnServiceCommand.Start(StartOrigin.Reload))
        runtimes[0].request.clearChainedActivityWhenCurrent(runtimes[0].request.owner)
        assertEquals(1, clears)

        // The replacement, now current, still clears when it is the one being closed.
        runtimes[1].request.clearChainedActivityWhenCurrent(runtimes[1].request.owner)
        assertEquals(2, clears)
    }

    @Test
    fun `reconnect admission serializes concurrent replacements`() {
        val executor = Executors.newSingleThreadExecutor()
        val admission = ChainedEngineQueue(executor)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val concurrent = java.util.concurrent.atomic.AtomicInteger()
        val maximum = java.util.concurrent.atomic.AtomicInteger()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit {
                admission.run { _ ->
                    maximum.accumulateAndGet(concurrent.incrementAndGet(), ::maxOf)
                    entered.countDown()
                    release.await()
                    concurrent.decrementAndGet()
                }
            }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val second = pool.submit {
                admission.run { _ ->
                    maximum.accumulateAndGet(concurrent.incrementAndGet(), ::maxOf)
                    concurrent.decrementAndGet()
                }
            }
            release.countDown()
            first.get(5, java.util.concurrent.TimeUnit.SECONDS)
            second.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(1, maximum.get())
        } finally {
            pool.shutdownNow()
            executor.shutdownNow()
        }
    }

    @Test
    fun `stop during an in-flight start closes the pending candidate`() {
        val state = ExactVpnServiceState()
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val runtimes = mutableListOf<BlockingStartRuntime>()
        try {
            val owner = VpnServiceCommandOwner(
                foreground = VpnForegroundAdmission {},
                hostRegistry = CredentialVpnHostRegistry().apply {
                    attach(CommandHost(116L, mutableListOf()))
                },
                state = state,
                runtimeFactory = VpnOwnedRuntimeFactory {
                    val index = runtimes.size
                    if (index == 0) {
                        BlockingStartRuntime(index, mutableListOf()).also(runtimes::add)
                    } else {
                        BlockingStartRuntime(
                            index = index,
                            closeOrder = mutableListOf(),
                            startEntered = startEntered,
                            releaseStart = releaseStart,
                        ).also(runtimes::add)
                    }
                },
                scope = CoroutineScope(SupervisorJob() + dispatcher),
                // The close must run on a different thread than the (blocked) command scope, as it
                // does in production where `scope` is Dispatchers.IO.
                teardownDispatcher = Dispatchers.IO,
            )

            assertEquals(
                VpnCommandResult.Sticky,
                owner.handle(VpnServiceCommand.Start(StartOrigin.VisibleUser)),
            )
            val startDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
            while (!runtimes[0].started && System.nanoTime() < startDeadline) {
                Thread.yield()
            }
            assertTrue("the predecessor must start", runtimes[0].started)
            val committed = CountDownLatch(1)
            executor.execute { committed.countDown() }
            assertTrue("the predecessor must commit", committed.await(5, TimeUnit.SECONDS))

            val generation = (state.state.value as? ProtectionState.Starting)?.generation
            assertNotNull(generation)
            // A reload installs candidate generation 2; its non-suspending start blocks while its
            // route-claiming TUN is being established, and the predecessor is still the owned
            // runtime.
            assertEquals(
                VpnCommandResult.Sticky,
                owner.handle(VpnServiceCommand.Reload(expectedServiceGeneration = generation)),
            )
            assertTrue("the candidate must enter start", startEntered.await(5, TimeUnit.SECONDS))
            assertFalse("the candidate must not be closed yet", runtimes[1].closed)

            // The user stops protection while that start is still in flight. Cancellation cannot
            // interrupt it, so the pending candidate's TUN must be closed by the teardown itself or
            // it would outlive protection reporting Off. The close is dispatched to the teardown
            // thread, so the command returns without running it inline.
            assertEquals(VpnCommandResult.NotSticky, owner.handle(VpnServiceCommand.Stop))
            val closeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
            while (
                (!runtimes[1].closed || !runtimes[0].closed) &&
                System.nanoTime() < closeDeadline
            ) {
                Thread.yield()
            }
            assertTrue("the pending candidate must be closed", runtimes[1].closed)
            assertTrue("the installed predecessor must be closed", runtimes[0].closed)
            assertEquals(ProtectionState.Off, state.state.value)
        } finally {
            releaseStart.countDown()
            dispatcher.close()
        }
    }

    @Test
    fun `a terminal emitted during startup prevents adoption and is handled`() {
        val state = ExactVpnServiceState()
        var terminalExits = 0
        lateinit var predecessor: RecordingOwnedRuntime
        lateinit var candidate: TerminatingStartRuntime
        val owner = VpnServiceCommandOwner(
            foreground = VpnForegroundAdmission {},
            hostRegistry = CredentialVpnHostRegistry().apply {
                attach(CommandHost(117L, mutableListOf()))
            },
            state = state,
            runtimeFactory = VpnOwnedRuntimeFactory { request ->
                if (request.owner.sessionGeneration == 1L) {
                    RecordingOwnedRuntime(request, mutableListOf()).also { predecessor = it }
                } else {
                    TerminatingStartRuntime(request, ProtectionFailure.TunnelEnded).also {
                        candidate = it
                    }
                }
            },
            terminalExit = VpnTerminalServiceExit { terminalExits += 1 },
        )
        // The predecessor installs normally.
        assertEquals(
            VpnCommandResult.Sticky,
            owner.handle(VpnServiceCommand.Start(StartOrigin.VisibleUser)),
        )
        val firstGeneration = (state.state.value as? ProtectionState.Starting)?.generation
        assertNotNull(firstGeneration)

        // The replacement's engine terminates during its own asynchronous start, before it can be
        // adopted. The terminal event must be latched and handled exactly like a terminal retirement
        // instead of being rejected as a stale owner; a dead candidate must never be adopted.
        assertEquals(
            VpnCommandResult.Sticky,
            owner.handle(VpnServiceCommand.Start(StartOrigin.Reload)),
        )
        assertTrue("the candidate must have started", candidate.started)
        assertEventuallyClosed("the dead candidate must be closed") { candidate.closed }
        assertEventuallyClosed("the stranded predecessor must be closed") { predecessor.closed }
        assertEquals(ProtectionState.Failed(ProtectionFailure.TunnelEnded), state.state.value)
        assertEquals(1, terminalExits)
        // Nothing was adopted: a control command finds no installed runtime and is terminal.
        assertEquals(VpnCommandResult.NotSticky, owner.handle(VpnServiceCommand.Resume))
    }

    @Test
    fun `a failed start closes the stranded predecessor outside the command monitor`() {
        val state = ExactVpnServiceState()
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        lateinit var predecessor: BlockingCloseRuntime
        lateinit var candidate: FailingStartRuntime
        try {
            val owner = VpnServiceCommandOwner(
                foreground = VpnForegroundAdmission {},
                hostRegistry = CredentialVpnHostRegistry().apply {
                    attach(CommandHost(118L, mutableListOf()))
                },
                state = state,
                runtimeFactory = VpnOwnedRuntimeFactory { request ->
                    if (request.owner.sessionGeneration == 1L) {
                        BlockingCloseRuntime(closeEntered, releaseClose).also { predecessor = it }
                    } else {
                        FailingStartRuntime().also { candidate = it }
                    }
                },
                scope = CoroutineScope(SupervisorJob() + dispatcher),
            )

            assertEquals(
                VpnCommandResult.Sticky,
                owner.handle(VpnServiceCommand.Start(StartOrigin.VisibleUser)),
            )
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
            while (!predecessor.started && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertTrue("the predecessor must start", predecessor.started)
            val committed = CountDownLatch(1)
            executor.execute { committed.countDown() }
            assertTrue("the predecessor must commit", committed.await(5, TimeUnit.SECONDS))

            // The replacement's start throws; the failure branch must close the stranded predecessor
            // OUTSIDE the command monitor. Its close blocks here so the monitor's freedom is
            // observable.
            assertEquals(
                VpnCommandResult.Sticky,
                owner.handle(VpnServiceCommand.Start(StartOrigin.Reload)),
            )
            assertTrue(
                "the failure branch must close the predecessor",
                closeEntered.await(5, TimeUnit.SECONDS),
            )

            // A Stop delivered through the synchronized `handle` must be serviced while that close
            // is still blocked; if the failure branch held the monitor across the close, this would
            // hang until the close is released.
            val serviced = CountDownLatch(1)
            val stopThread = Thread {
                owner.handle(VpnServiceCommand.Stop)
                serviced.countDown()
            }
            stopThread.start()
            assertTrue(
                "a concurrent command must not block on the predecessor close",
                serviced.await(2, TimeUnit.SECONDS),
            )
            releaseClose.countDown()
            stopThread.join(TimeUnit.SECONDS.toMillis(5))
            val candidateDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
            while (!candidate.closed && System.nanoTime() < candidateDeadline) {
                Thread.yield()
            }
            assertTrue("the failed candidate must be closed", candidate.closed)
        } finally {
            releaseClose.countDown()
            dispatcher.close()
        }
    }

    @Test
    fun `a deferred terminal exit does not stop a replacement installed during the predecessor close`() {
        val state = ExactVpnServiceState()
        var terminalExits = 0
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        lateinit var predecessor: BlockingCloseRuntime
        lateinit var replacement: RecordingOwnedRuntime
        try {
            val owner = VpnServiceCommandOwner(
                foreground = VpnForegroundAdmission {},
                hostRegistry = CredentialVpnHostRegistry().apply {
                    attach(CommandHost(120L, mutableListOf()))
                },
                state = state,
                runtimeFactory = VpnOwnedRuntimeFactory { request ->
                    when (request.owner.sessionGeneration) {
                        1L ->
                            BlockingCloseRuntime(closeEntered, releaseClose).also {
                                predecessor = it
                            }

                        2L ->
                            TerminatingStartRuntime(request, ProtectionFailure.TunnelEnded)

                        else ->
                            RecordingOwnedRuntime(request, mutableListOf()).also {
                                replacement = it
                            }
                    }
                },
                terminalExit = VpnTerminalServiceExit { terminalExits += 1 },
                scope = CoroutineScope(SupervisorJob() + dispatcher),
            )

            // The predecessor is established and adopted before the replacement is issued.
            assertEquals(
                VpnCommandResult.Sticky,
                owner.handle(VpnServiceCommand.Start(StartOrigin.VisibleUser)),
            )
            val committed = CountDownLatch(1)
            executor.execute { committed.countDown() }
            assertTrue("the predecessor must commit", committed.await(5, TimeUnit.SECONDS))

            // The replacement's engine terminates during its own asynchronous start. Its terminal
            // retirement runs under the monitor, but the deferred service exit happens only after
            // the stranded predecessor is closed OUTSIDE the monitor.
            assertEquals(
                VpnCommandResult.Sticky,
                owner.handle(VpnServiceCommand.Start(StartOrigin.Reload)),
            )
            assertTrue(
                "the deferred exit must close the stranded predecessor",
                closeEntered.await(5, TimeUnit.SECONDS),
            )

            // A newer Start lands while that close is still blocked. It owns the service, so the
            // deferred terminal exit for the failed candidate must not stop it.
            assertEquals(
                VpnCommandResult.Sticky,
                owner.handle(VpnServiceCommand.Start(StartOrigin.Reload)),
            )

            releaseClose.countDown()
            val settled = CountDownLatch(1)
            executor.execute { settled.countDown() }
            assertTrue("the replacement must settle", settled.await(5, TimeUnit.SECONDS))

            assertEquals(
                "a replacement installed during the predecessor close must keep the service alive",
                0,
                terminalExits,
            )
            assertFalse("the replacement must not be stopped", replacement.closed)
            assertEventuallyClosed("the stranded predecessor must be closed") { predecessor.closed }
            owner.close()
        } finally {
            releaseClose.countDown()
            dispatcher.close()
        }
    }

    @Test
    fun `a pause during a pending start controls the adopted successor, not the predecessor`() {
        assertControlDuringPendingStartLandsOnSuccessor(
            command = VpnServiceCommand.Pause(PauseDuration.FIVE_MINUTES),
            expectedControl = "pause:FIVE_MINUTES",
        )
    }

    @Test
    fun `a resume during a pending start controls the adopted successor, not the predecessor`() {
        assertControlDuringPendingStartLandsOnSuccessor(
            command = VpnServiceCommand.Resume,
            expectedControl = "resume",
        )
    }

    /**
     * A Pause/Resume that arrives while a Start/Reload is still preflighting must not be applied to
     * the predecessor still held by `runtime`: `currentOwner`/`state` already identify the in-flight
     * candidate, so the predecessor's publications and rebuilds would be fenced out and protection
     * would stay stranded in `Starting` (and a Pause would never persist). The control must instead
     * run a fresh, consistently owned start and apply the intent to the adopted successor.
     */
    private fun assertControlDuringPendingStartLandsOnSuccessor(
        command: VpnServiceCommand,
        expectedControl: String,
    ) {
        val state = ExactVpnServiceState()
        val preflightEntered = CountDownLatch(1)
        val releasePreflight = CountDownLatch(1)
        // A multi-threaded command scope, as production's Dispatchers.IO is: the superseded
        // candidate's non-interruptible preflight must not starve the fresh successor that is
        // replacing it, and that successor is the one that closes the superseded candidate once it
        // is live (the superseded candidate is retained, not torn down, until then).
        val executor = Executors.newFixedThreadPool(4)
        val dispatcher = executor.asCoroutineDispatcher()
        val runtimes = mutableListOf<BlockingControlRuntime>()
        try {
            val owner = VpnServiceCommandOwner(
                foreground = VpnForegroundAdmission {},
                hostRegistry = CredentialVpnHostRegistry().apply {
                    attach(CommandHost(121L, mutableListOf()))
                },
                state = state,
                runtimeFactory = VpnOwnedRuntimeFactory { request ->
                    val index = runtimes.size
                    val runtime = if (index == 1) {
                        // Only the replacement candidate blocks, so the control arrives while its
                        // owner (not `runtime`) owns the service.
                        BlockingControlRuntime(request, preflightEntered, releasePreflight)
                    } else {
                        BlockingControlRuntime(request)
                    }
                    runtime.also(runtimes::add)
                },
                scope = CoroutineScope(SupervisorJob() + dispatcher),
                teardownDispatcher = Dispatchers.IO,
            )

            // The predecessor (generation 1) is established and adopted.
            assertEquals(
                VpnCommandResult.Sticky,
                owner.handle(VpnServiceCommand.Start(StartOrigin.VisibleUser)),
            )
            val establishedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
            while (!runtimes[0].started && System.nanoTime() < establishedDeadline) {
                Thread.yield()
            }
            assertTrue("the predecessor must start", runtimes[0].started)
            val committed = CountDownLatch(1)
            executor.execute { committed.countDown() }
            assertTrue("the predecessor must commit", committed.await(5, TimeUnit.SECONDS))
            val generation = (state.state.value as? ProtectionState.Starting)?.generation
            assertNotNull(generation)

            // A reload installs candidate generation 2; its preflight blocks, so the candidate owns
            // `currentOwner`/`state` while `runtime` still holds the predecessor.
            assertEquals(
                VpnCommandResult.Sticky,
                owner.handle(VpnServiceCommand.Reload(expectedServiceGeneration = generation)),
            )
            assertTrue(
                "the candidate must enter preflight",
                preflightEntered.await(5, TimeUnit.SECONDS),
            )
            assertFalse("the candidate must not be closed yet", runtimes[1].closed)

            // The user controls protection while generation 2 is still preflighting. The command is
            // accepted now, but the control must be applied to a fresh successor, never to the
            // stale-owned predecessor.
            assertEquals(VpnCommandResult.Sticky, owner.handle(command))
            assertEquals("a fresh successor start must be issued", 3, runtimes.size)
            val successor = runtimes[2]
            // Superseded closes are now deferred off the command thread, so await the asynchronous
            // close rather than asserting it synchronously.
            val supersededClosedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
            while (!runtimes[1].closed && System.nanoTime() < supersededClosedDeadline) {
                Thread.yield()
            }
            assertTrue("the superseded candidate must be closed", runtimes[1].closed)

            releasePreflight.countDown()
            assertTrue(
                "the intent must be applied to the successor",
                successor.controlApplied.await(5, TimeUnit.SECONDS),
            )

            assertEventuallyClosed("the predecessor must be retired") { runtimes[0].closed }
            assertEquals(
                "the successor must receive exactly the user's intent",
                listOf(expectedControl),
                successor.controls.toList(),
            )
            assertTrue(
                "the predecessor must never receive the control",
                runtimes[0].controls.isEmpty(),
            )

            // The successor owns the service state, so its publications are accepted (protection is
            // not stranded on the retired candidate) while the retired predecessor's are fenced out.
            val identity = ConfigurationIdentity(9L, "successor")
            assertTrue(
                successor.request.publication.publish(
                    ProtectionState.Protected(identity, VpnOwnership.User),
                ),
            )
            assertEquals(ProtectionState.Protected(identity, VpnOwnership.User), state.state.value)
            assertFalse(
                runtimes[0].request.publication.publish(
                    ProtectionState.Protected(
                        ConfigurationIdentity(1L, "predecessor"),
                        VpnOwnership.User,
                    ),
                ),
            )
            owner.close()
        } finally {
            releasePreflight.countDown()
            dispatcher.close()
        }
    }

    @Test
    fun `a pause deferred by a pending start is inherited when a later reload supersedes it`() {
        assertDeferredControlSurvivesSupersession(
            command = VpnServiceCommand.Pause(PauseDuration.FIVE_MINUTES),
            expectedControl = "pause:FIVE_MINUTES",
        )
    }

    @Test
    fun `a resume deferred by a pending start is inherited when a later reload supersedes it`() {
        assertDeferredControlSurvivesSupersession(
            command = VpnServiceCommand.Resume,
            expectedControl = "resume",
        )
    }

    /**
     * A Pause/Resume that arrives while a Start/Reload is preflighting is applied after adoption.
     * If a later Start/Reload supersedes that attempt, the intent must travel with it to the
     * successor; dropping it would leave a routine reload after Pause filtering, or a reload after
     * Resume paused.
     */
    private fun assertDeferredControlSurvivesSupersession(
        command: VpnServiceCommand,
        expectedControl: String,
    ) {
        val state = ExactVpnServiceState()
        val firstCandidatePreflight = CountDownLatch(1)
        val releaseFirstCandidate = CountDownLatch(1)
        val secondCandidatePreflight = CountDownLatch(1)
        val releaseSecondCandidate = CountDownLatch(1)
        // A multi-threaded dispatcher, like the production Dispatchers.IO: a superseded candidate's
        // non-interruptible preflight must not occupy the only worker and starve the successor's
        // preflight (a single-threaded executor would serialise them and the latch would never open).
        val executor = Executors.newFixedThreadPool(4)
        val dispatcher = executor.asCoroutineDispatcher()
        val runtimes = mutableListOf<BlockingControlRuntime>()
        try {
            val owner = VpnServiceCommandOwner(
                foreground = VpnForegroundAdmission {},
                hostRegistry = CredentialVpnHostRegistry().apply {
                    attach(CommandHost(125L, mutableListOf()))
                },
                state = state,
                runtimeFactory = VpnOwnedRuntimeFactory { request ->
                    val index = runtimes.size
                    val runtime = when (index) {
                        1 -> BlockingControlRuntime(
                            request,
                            firstCandidatePreflight,
                            releaseFirstCandidate,
                        )

                        2 -> BlockingControlRuntime(
                            request,
                            secondCandidatePreflight,
                            releaseSecondCandidate,
                        )

                        else -> BlockingControlRuntime(request)
                    }
                    runtime.also(runtimes::add)
                },
                scope = CoroutineScope(SupervisorJob() + dispatcher),
            )
            // The predecessor (generation 1) is established and adopted.
            assertEquals(
                VpnCommandResult.Sticky,
                owner.handle(VpnServiceCommand.Start(StartOrigin.VisibleUser)),
            )
            val committed = CountDownLatch(1)
            executor.execute { committed.countDown() }
            assertTrue("the predecessor must commit", committed.await(5, TimeUnit.SECONDS))
            val firstGeneration = (state.state.value as? ProtectionState.Starting)?.generation
            assertNotNull(firstGeneration)

            // A reload installs candidate generation 2 and blocks in its preflight.
            assertEquals(
                VpnCommandResult.Sticky,
                owner.handle(VpnServiceCommand.Reload(firstGeneration)),
            )
            assertTrue(firstCandidatePreflight.await(5, TimeUnit.SECONDS))

            // The user controls protection while generation 2 is preflighting: a fresh successor
            // (generation 3) is started carrying the intent.
            assertEquals(VpnCommandResult.Sticky, owner.handle(command))
            assertTrue(secondCandidatePreflight.await(5, TimeUnit.SECONDS))
            val secondGeneration = (state.state.value as? ProtectionState.Starting)?.generation
            assertNotNull(secondGeneration)
            assertEquals(3, runtimes.size)

            // A later reload supersedes generation 3 before it adopts. It must inherit the deferred
            // control instead of dropping it.
            assertEquals(
                VpnCommandResult.Sticky,
                owner.handle(VpnServiceCommand.Reload(secondGeneration)),
            )
            assertEquals(4, runtimes.size)

            releaseFirstCandidate.countDown()
            releaseSecondCandidate.countDown()
            assertTrue(
                "the final successor must apply the inherited control",
                runtimes[3].controlApplied.await(5, TimeUnit.SECONDS),
            )
            // The predecessor retire is deferred off the command thread too; await it.
            val predecessorClosedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
            while (!runtimes[0].closed && System.nanoTime() < predecessorClosedDeadline) {
                Thread.yield()
            }
            assertTrue("the predecessor must be retired", runtimes[0].closed)
            assertEquals(
                "the successor must receive exactly the user's intent",
                listOf(expectedControl),
                runtimes[3].controls.toList(),
            )
            assertTrue(
                "the first superseded candidate must not receive the control",
                runtimes[1].controls.isEmpty(),
            )
            assertTrue(
                "the second superseded candidate must not receive the control",
                runtimes[2].controls.isEmpty(),
            )
            owner.close()
        } finally {
            releaseFirstCandidate.countDown()
            releaseSecondCandidate.countDown()
            dispatcher.close()
        }
    }

    @Test
    fun `a superseded pending candidate is closed outside the command monitor`() {
        val state = ExactVpnServiceState()
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        // A multi-threaded command scope, as production's Dispatchers.IO is: the superseded
        // candidate's non-suspending start must not starve the successor replacing it, which is the
        // one that closes the superseded candidate once it is live.
        val executor = Executors.newFixedThreadPool(4)
        val dispatcher = executor.asCoroutineDispatcher()
        val runtimes = mutableListOf<VpnOwnedRuntime>()
        try {
            val owner = VpnServiceCommandOwner(
                foreground = VpnForegroundAdmission {},
                hostRegistry = CredentialVpnHostRegistry().apply {
                    attach(CommandHost(124L, mutableListOf()))
                },
                state = state,
                runtimeFactory = VpnOwnedRuntimeFactory { request ->
                    val runtime: VpnOwnedRuntime = when (runtimes.size) {
                        // The initial predecessor starts immediately.
                        0 -> RecordingOwnedRuntime(request, mutableListOf())
                        // The in-flight candidate blocks in start, then in close when superseded.
                        1 -> BlockingStartAndCloseRuntime(
                            startEntered,
                            releaseStart,
                            closeEntered,
                            releaseClose,
                        )
                        // The successor the superseding reload installs closes quickly.
                        else -> RecordingOwnedRuntime(request, mutableListOf())
                    }
                    runtime.also(runtimes::add)
                },
                scope = CoroutineScope(SupervisorJob() + dispatcher),
                // The blocking close must run on a different thread than the (blocked) command
                // scope, as it does in production where `scope` is Dispatchers.IO.
                teardownDispatcher = Dispatchers.IO,
            )
            owner.handle(VpnServiceCommand.Start(StartOrigin.VisibleUser))
            val committed = CountDownLatch(1)
            executor.execute { committed.countDown() }
            assertTrue("the predecessor must commit", committed.await(5, TimeUnit.SECONDS))
            val generation = (state.state.value as? ProtectionState.Starting)?.generation
            assertNotNull(generation)

            // A reload installs candidate generation 2; its start blocks, so it is the pending
            // attempt.
            assertEquals(
                VpnCommandResult.Sticky,
                owner.handle(VpnServiceCommand.Reload(expectedServiceGeneration = generation)),
            )
            assertTrue("the candidate must enter start", startEntered.await(5, TimeUnit.SECONDS))

            val candidateGeneration = (state.state.value as? ProtectionState.Starting)?.generation
            assertNotNull(candidateGeneration)
            // A second reload supersedes the pending candidate. It is retained for the successor
            // (not closed out from under it), and the successor closes it once it is live; that
            // close blocks here — but only OUTSIDE the command monitor.
            val superseding = Thread {
                owner.handle(VpnServiceCommand.Reload(expectedServiceGeneration = candidateGeneration))
            }
            superseding.start()
            assertTrue(
                "the superseded candidate must be closed by its live successor",
                closeEntered.await(5, TimeUnit.SECONDS),
            )

            // A Stop must be serviced on its own thread while that close is blocked; if the close
            // held the monitor this would hang until the close is released.
            val serviced = CountDownLatch(1)
            val stopThread = Thread {
                owner.handle(VpnServiceCommand.Stop)
                serviced.countDown()
            }
            stopThread.start()
            assertTrue(
                "a concurrent command must not block on the superseded candidate close",
                serviced.await(2, TimeUnit.SECONDS),
            )

            releaseStart.countDown()
            releaseClose.countDown()
            superseding.join(TimeUnit.SECONDS.toMillis(5))
            stopThread.join(TimeUnit.SECONDS.toMillis(5))
            owner.close()
        } finally {
            releaseStart.countDown()
            releaseClose.countDown()
            dispatcher.close()
        }
    }

    @Test
    fun `a resume waits for an in-flight pause so controls keep delivery order`() {
        val state = ExactVpnServiceState()
        val pauseEntered = CountDownLatch(1)
        val releasePause = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val dispatcher = executor.asCoroutineDispatcher()
        lateinit var runtime: BlockingPauseRuntime
        try {
            val owner = VpnServiceCommandOwner(
                foreground = VpnForegroundAdmission {},
                hostRegistry = CredentialVpnHostRegistry().apply {
                    attach(CommandHost(123L, mutableListOf()))
                },
                state = state,
                runtimeFactory = VpnOwnedRuntimeFactory { request ->
                    BlockingPauseRuntime(pauseEntered, releasePause).also { runtime = it }
                },
                scope = CoroutineScope(SupervisorJob() + dispatcher),
            )
            owner.handle(VpnServiceCommand.Start(StartOrigin.VisibleUser))
            val startDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
            while (!runtime.started && System.nanoTime() < startDeadline) {
                Thread.yield()
            }
            assertTrue("the runtime must start", runtime.started)
            val committed = CountDownLatch(1)
            executor.execute { committed.countDown() }
            assertTrue("the runtime must commit", committed.await(5, TimeUnit.SECONDS))

            assertEquals(
                VpnCommandResult.Sticky,
                owner.handle(VpnServiceCommand.Pause(PauseDuration.FIVE_MINUTES)),
            )
            assertTrue("the pause must run", pauseEntered.await(5, TimeUnit.SECONDS))

            // Give the resume a chance to run on the other pool thread. With the sequence fence and
            // the control lock it must still be waiting behind the in-flight pause.
            assertEquals(VpnCommandResult.Sticky, owner.handle(VpnServiceCommand.Resume))
            Thread.sleep(100L)
            assertFalse(
                "the resume must not overtake the in-flight pause",
                runtime.controls.contains("resume"),
            )

            releasePause.countDown()
            val settleDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
            while (runtime.controls.size < 2 && System.nanoTime() < settleDeadline) {
                Thread.yield()
            }
            assertEquals(
                listOf("pause:FIVE_MINUTES", "resume"),
                runtime.controls.toList(),
            )
            owner.close()
        } finally {
            releasePause.countDown()
            dispatcher.close()
        }
    }

    @Test
    fun `a slow deferred close does not block the command thread`() {
        val state = ExactVpnServiceState()
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        lateinit var runtime: BlockingCloseRuntime
        val owner = VpnServiceCommandOwner(
            foreground = VpnForegroundAdmission {},
            hostRegistry = CredentialVpnHostRegistry().apply {
                attach(CommandHost(129L, mutableListOf()))
            },
            state = state,
            runtimeFactory = VpnOwnedRuntimeFactory { request ->
                BlockingCloseRuntime(closeEntered, releaseClose).also { runtime = it }
            },
            // Production's teardown dispatcher. The default scope is Unconfined and would run the
            // close inline on the command thread; this proves the close is off it.
            teardownDispatcher = Dispatchers.IO,
        )
        try {
            owner.handle(VpnServiceCommand.Start(StartOrigin.VisibleUser))
            val startDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
            while (!runtime.started && System.nanoTime() < startDeadline) {
                Thread.yield()
            }
            assertTrue("the runtime must start", runtime.started)

            // Stop claims the runtime and dispatches its blocking close. The command must return
            // without waiting for the close, or a slow JNI/engine teardown would ANR the service
            // main thread that called `onStartCommand` and delay later commands.
            val returned = CountDownLatch(1)
            val commandThread = Thread {
                owner.handle(VpnServiceCommand.Stop)
                returned.countDown()
            }
            commandThread.start()
            assertTrue(
                "the stop command must return before the close completes",
                returned.await(5, TimeUnit.SECONDS),
            )
            assertTrue("the deferred close must run", closeEntered.await(5, TimeUnit.SECONDS))
            releaseClose.countDown()
            commandThread.join(TimeUnit.SECONDS.toMillis(5))
            owner.close()
        } finally {
            releaseClose.countDown()
        }
    }

    @Test
    fun `a deferred pause cannot overwrite a resume delivered after adoption`() {
        val state = ExactVpnServiceState()
        val candidatePreflight = CountDownLatch(1)
        val releaseCandidate = CountDownLatch(1)
        val pauseEntered = CountDownLatch(1)
        val releasePause = CountDownLatch(1)
        val resumeApplied = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(4)
        val dispatcher = executor.asCoroutineDispatcher()
        val runtimes = mutableListOf<OrderedDeferredControlRuntime>()
        try {
            val owner = VpnServiceCommandOwner(
                foreground = VpnForegroundAdmission {},
                hostRegistry = CredentialVpnHostRegistry().apply {
                    attach(CommandHost(128L, mutableListOf()))
                },
                state = state,
                runtimeFactory = VpnOwnedRuntimeFactory { request ->
                    val index = runtimes.size
                    val runtime = if (index == 0) {
                        OrderedDeferredControlRuntime(
                            request = request,
                            pauseEntered = pauseEntered,
                            releasePause = releasePause,
                            resumeApplied = resumeApplied,
                        )
                    } else {
                        OrderedDeferredControlRuntime(
                            request = request,
                            preflightEntered = candidatePreflight,
                            releasePreflight = releaseCandidate,
                            pauseEntered = pauseEntered,
                            releasePause = releasePause,
                            resumeApplied = resumeApplied,
                        )
                    }
                    runtime.also(runtimes::add)
                },
                scope = CoroutineScope(SupervisorJob() + dispatcher),
            )

            // The predecessor (generation 1) is adopted without a preflight block.
            assertEquals(
                VpnCommandResult.Sticky,
                owner.handle(VpnServiceCommand.Start(StartOrigin.VisibleUser)),
            )
            val predecessorDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
            while (!runtimes[0].started && System.nanoTime() < predecessorDeadline) {
                Thread.yield()
            }
            assertTrue("the predecessor must start", runtimes[0].started)
            val firstGeneration = (state.state.value as? ProtectionState.Starting)?.generation
            assertNotNull(firstGeneration)

            // A reload installs generation 2 and blocks in its preflight.
            assertEquals(
                VpnCommandResult.Sticky,
                owner.handle(VpnServiceCommand.Reload(firstGeneration)),
            )
            assertTrue(candidatePreflight.await(5, TimeUnit.SECONDS))

            // Pause arrives while generation 2 is still preflighting: a fresh successor (generation
            // 3) is started and carries the deferred pause.
            assertEquals(
                VpnCommandResult.Sticky,
                owner.handle(VpnServiceCommand.Pause(PauseDuration.FIVE_MINUTES)),
            )
            assertEquals(3, runtimes.size)

            // Release the successor so it adopts; its deferred pause then runs and blocks.
            releaseCandidate.countDown()
            val successor = runtimes[2]
            assertTrue("the deferred pause must run", pauseEntered.await(5, TimeUnit.SECONDS))

            // A Resume delivered now is the LATER command. It must wait behind the in-flight
            // deferred pause (same ordered control path) and must not be overwritten when that pause
            // completes: without the sequence fence the un-serialized deferred pause could land
            // after the resume and leave protection paused.
            assertEquals(VpnCommandResult.Sticky, owner.handle(VpnServiceCommand.Resume))
            assertFalse(
                "the resume must not overtake the deferred pause",
                resumeApplied.await(100L, TimeUnit.MILLISECONDS),
            )
            releasePause.countDown()
            assertTrue("the resume must eventually apply", resumeApplied.await(5, TimeUnit.SECONDS))
            assertFalse(
                "the stale deferred pause must not apply after the newer resume",
                successor.resumeAppliedBeforePauseCompleted,
            )
            owner.close()
        } finally {
            releaseCandidate.countDown()
            releasePause.countDown()
            dispatcher.close()
        }
    }

    @Test
    fun `a failed pause closes the runtime outside the command monitor`() {
        val state = ExactVpnServiceState()
        val pauseEntered = CountDownLatch(1)
        val releasePause = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        lateinit var runtime: FailingBlockingControlRuntime
        try {
            val owner = VpnServiceCommandOwner(
                foreground = VpnForegroundAdmission {},
                hostRegistry = CredentialVpnHostRegistry().apply {
                    attach(CommandHost(126L, mutableListOf()))
                },
                state = state,
                runtimeFactory = VpnOwnedRuntimeFactory { request ->
                    FailingBlockingControlRuntime(
                        pauseEntered,
                        releasePause,
                        closeEntered,
                        releaseClose,
                    ).also { runtime = it }
                },
                scope = CoroutineScope(SupervisorJob() + dispatcher),
            )
            owner.handle(VpnServiceCommand.Start(StartOrigin.VisibleUser))
            val startDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
            while (!runtime.started && System.nanoTime() < startDeadline) {
                Thread.yield()
            }
            assertTrue("the runtime must start", runtime.started)
            val committed = CountDownLatch(1)
            executor.execute { committed.countDown() }
            assertTrue("the runtime must commit", committed.await(5, TimeUnit.SECONDS))

            assertEquals(
                VpnCommandResult.Sticky,
                owner.handle(VpnServiceCommand.Pause(PauseDuration.FIVE_MINUTES)),
            )
            assertTrue("the pause must run", pauseEntered.await(5, TimeUnit.SECONDS))
            releasePause.countDown()

            // The pause throws; the failure path must claim the runtime under the monitor and then
            // close it OUTSIDE it. The close blocks here so the monitor's freedom is observable.
            assertTrue("the failed runtime must be closed", closeEntered.await(5, TimeUnit.SECONDS))
            assertEquals(
                ProtectionState.Failed(ProtectionFailure.EngineUnavailable),
                state.state.value,
            )

            val serviced = CountDownLatch(1)
            val stopThread = Thread {
                owner.handle(VpnServiceCommand.Stop)
                serviced.countDown()
            }
            stopThread.start()
            assertTrue(
                "a concurrent command must not block on the control-failure close",
                serviced.await(2, TimeUnit.SECONDS),
            )
            releaseClose.countDown()
            stopThread.join(TimeUnit.SECONDS.toMillis(5))
            owner.close()
        } finally {
            releasePause.countDown()
            releaseClose.countDown()
            dispatcher.close()
        }
    }

    @Test
    fun `a superseded in-flight candidate is retained until the successor is live`() {
        val state = ExactVpnServiceState()
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val runtimes = mutableListOf<BlockingStartRuntime>()
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val owner = VpnServiceCommandOwner(
                foreground = VpnForegroundAdmission {},
                hostRegistry = CredentialVpnHostRegistry().apply {
                    attach(CommandHost(130L, mutableListOf()))
                },
                state = state,
                runtimeFactory = VpnOwnedRuntimeFactory {
                    val index = runtimes.size
                    if (index == 0) {
                        BlockingStartRuntime(index, mutableListOf()).also(runtimes::add)
                    } else {
                        BlockingStartRuntime(
                            index = index,
                            closeOrder = mutableListOf(),
                            startEntered = startEntered,
                            releaseStart = releaseStart,
                        ).also(runtimes::add)
                    }
                },
                scope = CoroutineScope(SupervisorJob() + dispatcher),
            )
            owner.handle(VpnServiceCommand.Start(StartOrigin.VisibleUser))
            val initialDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
            while (!runtimes[0].started && System.nanoTime() < initialDeadline) {
                Thread.yield()
            }
            assertTrue("the predecessor must start", runtimes[0].started)
            val committed = CountDownLatch(1)
            executor.execute { committed.countDown() }
            assertTrue("the predecessor must commit", committed.await(5, TimeUnit.SECONDS))
            val firstGeneration = (state.state.value as? ProtectionState.Starting)?.generation
            assertNotNull(firstGeneration)

            // Generation 2 claims the interface but blocks in its non-suspending start.
            assertEquals(
                VpnCommandResult.Sticky,
                owner.handle(VpnServiceCommand.Reload(expectedServiceGeneration = firstGeneration)),
            )
            assertTrue("the candidate must enter start", startEntered.await(5, TimeUnit.SECONDS))
            val candidateGeneration = (state.state.value as? ProtectionState.Starting)?.generation
            assertNotNull(candidateGeneration)

            // Generation 3 supersedes it while its claim is still in flight. The superseded
            // candidate must NOT be closed yet: it owns the only claimed interface and is retained
            // until the successor (generation 3) is live, so the device never loses its tunnel.
            assertEquals(
                VpnCommandResult.Sticky,
                owner.handle(VpnServiceCommand.Reload(expectedServiceGeneration = candidateGeneration)),
            )
            assertFalse(
                "the superseded in-flight candidate must be retained until its successor is live",
                runtimes[1].closed,
            )

            // Release the blocked start: the successor runs, adopts, and only then closes the
            // retained superseded candidate.
            releaseStart.countDown()
            assertEventuallyClosed("the superseded candidate must be released once the successor is live") {
                runtimes[1].closed
            }
            owner.close()
        } finally {
            releaseStart.countDown()
            dispatcher.close()
        }
    }

    private fun assertEventuallyClosed(label: String, closed: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
        while (!closed() && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertTrue(label, closed())
    }
}

private class CommandHost(
    override val graphGeneration: Long,
    private val events: MutableList<String>,
) : CredentialVpnHostPort {
    val attachments = mutableListOf<CommandAttachment>()

    override fun openAttachment(request: CredentialVpnAttachRequest): CredentialVpnAttachment {
        events += "attach:${request.serviceSessionGeneration}"
        return CommandAttachment(graphGeneration).also(attachments::add)
    }
}

private class CommandAttachment(
    override val graphGeneration: Long,
) : CredentialVpnAttachment {
    var detachCount = 0

    override fun tryAcceptTelemetry(telemetry: VpnTelemetry): Boolean = true

    override fun detach() {
        detachCount += 1
    }
}

private class RecordingOwnedRuntime(
    val request: VpnOwnedRuntimeRequest,
    private val events: MutableList<String>,
) : VpnOwnedRuntime {
    var closed = false
    val controls = mutableListOf<String>()
    var reconnectFailure: Throwable? = null

    override suspend fun preflight() {
        events += "preflight:${request.owner.sessionGeneration}"
    }

    override fun start() {
        events += "start:${request.owner.sessionGeneration}"
    }

    override fun close() {
        if (closed) return
        closed = true
        events += "close:${request.owner.sessionGeneration}"
        request.attachment.detach()
    }

    override fun pause(duration: PauseDuration) {
        controls += "pause:${duration.name}"
    }

    override fun resume() {
        controls += "resume"
    }

    override suspend fun reconnect() {
        reconnectFailure?.let { throw it }
        controls += "reconnect"
    }
}

private class BlockingPreflightRuntime(
    private val preflightEntered: CountDownLatch,
    private val releasePreflight: CountDownLatch,
) : VpnOwnedRuntime {
    @Volatile
    var started = false

    @Volatile
    var closed = false

    override suspend fun preflight() {
        preflightEntered.countDown()
        check(releasePreflight.await(5, TimeUnit.SECONDS)) { "test preflight was not released" }
    }

    override fun start() {
        started = true
    }

    override fun close() {
        closed = true
    }
}

private class BlockingReconnectRuntime(
    private val reconnectEntered: CountDownLatch,
    private val releaseReconnect: CountDownLatch,
) : VpnOwnedRuntime {
    @Volatile
    var started = false

    @Volatile
    var closed = false

    override fun start() {
        started = true
    }

    override suspend fun reconnect() {
        reconnectEntered.countDown()
        check(releaseReconnect.await(5, TimeUnit.SECONDS)) { "test reconnect was not released" }
    }

    override fun close() {
        closed = true
    }
}

private class BlockingResumeRuntime(
    private val resumeEntered: CountDownLatch,
    private val releaseResume: CountDownLatch,
) : VpnOwnedRuntime {
    @Volatile
    var started = false

    @Volatile
    var closed = false

    override fun start() {
        started = true
    }

    override fun resume() {
        resumeEntered.countDown()
        check(releaseResume.await(5, TimeUnit.SECONDS)) { "test resume was not released" }
    }

    override fun close() {
        closed = true
    }
}

private class BlockingStartRuntime(
    private val index: Int,
    private val closeOrder: MutableList<Int>,
    private val startEntered: CountDownLatch? = null,
    private val releaseStart: CountDownLatch? = null,
    private val onStart: () -> Unit = {},
) : VpnOwnedRuntime {
    @Volatile
    var started = false

    @Volatile
    var closed = false

    override fun start() {
        onStart()
        startEntered?.countDown()
        releaseStart?.let { check(it.await(5, TimeUnit.SECONDS)) { "test start was not released" } }
        started = true
    }

    override fun close() {
        if (closed) return
        closed = true
        closeOrder.add(index)
    }
}

private class TerminatingStartRuntime(
    private val request: VpnOwnedRuntimeRequest,
    private val failure: ProtectionFailure,
) : VpnOwnedRuntime {
    @Volatile
    var started = false

    @Volatile
    var closed = false

    override fun start() {
        started = true
        // The engine dies immediately, before the command owner can adopt the candidate.
        request.terminalPublication.publish(this, failure)
    }

    override fun close() {
        closed = true
    }
}

private class FailingStartRuntime : VpnOwnedRuntime {
    @Volatile
    var closed = false

    override fun start() {
        throw VpnRuntimeStartException(ProtectionFailure.EngineUnavailable)
    }

    override fun close() {
        closed = true
    }
}

private class BlockingCloseRuntime(
    private val closeEntered: CountDownLatch,
    private val releaseClose: CountDownLatch,
) : VpnOwnedRuntime {
    @Volatile
    var started = false

    @Volatile
    var closed = false

    override fun start() {
        started = true
    }

    override fun close() {
        if (closed) return
        closed = true
        closeEntered.countDown()
        check(releaseClose.await(5, TimeUnit.SECONDS)) { "test close was not released" }
    }
}

/**
 * A runtime whose [preflight] can block (so a start stays `pending`) and which records the
 * pause/resume controls it receives. [controlApplied] latches when a control lands, so a test can
 * wait for the asynchronous successor adoption without racing a plain list.
 */
private class BlockingControlRuntime(
    val request: VpnOwnedRuntimeRequest,
    private val preflightEntered: CountDownLatch? = null,
    private val releasePreflight: CountDownLatch? = null,
) : VpnOwnedRuntime {
    @Volatile
    var started = false

    @Volatile
    var closed = false

    val controls = CopyOnWriteArrayList<String>()
    val controlApplied = CountDownLatch(1)

    override suspend fun preflight() {
        val entered = preflightEntered ?: return
        entered.countDown()
        val release = releasePreflight ?: return
        check(release.await(5, TimeUnit.SECONDS)) { "test preflight was not released" }
    }

    override fun start() {
        started = true
    }

    override fun pause(duration: PauseDuration) {
        controls += "pause:${duration.name}"
        controlApplied.countDown()
    }

    override fun resume() {
        controls += "resume"
        controlApplied.countDown()
    }

    override fun close() {
        closed = true
    }
}

/**
 * A runtime for the deferred-control ordering test.
 *
 * [preflight] may block (so the attempt stays pending), [pause] blocks BEFORE recording completion
 * (so an un-serialized Resume that overtakes it is observable), and [resume] records whether it
 * applied before the pause completed.
 */
private class OrderedDeferredControlRuntime(
    val request: VpnOwnedRuntimeRequest,
    private val preflightEntered: CountDownLatch? = null,
    private val releasePreflight: CountDownLatch? = null,
    private val pauseEntered: CountDownLatch,
    private val releasePause: CountDownLatch,
    private val resumeApplied: CountDownLatch,
) : VpnOwnedRuntime {
    @Volatile
    var started = false

    @Volatile
    var closed = false

    @Volatile
    var pauseCompleted = false

    @Volatile
    var resumeAppliedBeforePauseCompleted = false

    override suspend fun preflight() {
        val entered = preflightEntered ?: return
        entered.countDown()
        val release = releasePreflight ?: return
        check(release.await(5, TimeUnit.SECONDS)) { "test preflight was not released" }
    }

    override fun start() {
        started = true
    }

    override fun pause(duration: PauseDuration) {
        pauseEntered.countDown()
        check(releasePause.await(5, TimeUnit.SECONDS)) { "test pause was not released" }
        pauseCompleted = true
    }

    override fun resume() {
        if (!pauseCompleted) resumeAppliedBeforePauseCompleted = true
        resumeApplied.countDown()
    }

    override fun close() {
        closed = true
    }
}

/** A runtime whose [pause] blocks, so a following control can be observed queued behind it. */
private class BlockingPauseRuntime(
    private val pauseEntered: CountDownLatch,
    private val releasePause: CountDownLatch,
) : VpnOwnedRuntime {
    @Volatile
    var started = false

    @Volatile
    var closed = false

    val controls = CopyOnWriteArrayList<String>()

    override fun start() {
        started = true
    }

    override fun pause(duration: PauseDuration) {
        controls += "pause:${duration.name}"
        pauseEntered.countDown()
        check(releasePause.await(5, TimeUnit.SECONDS)) { "test pause was not released" }
    }

    override fun resume() {
        controls += "resume"
    }

    override fun close() {
        closed = true
    }
}

/**
 * A runtime whose [pause] blocks and then throws, and whose [close] blocks, so the control-failure
 * teardown can be observed happening outside the command monitor.
 */
private class FailingBlockingControlRuntime(
    private val pauseEntered: CountDownLatch,
    private val releasePause: CountDownLatch,
    private val closeEntered: CountDownLatch,
    private val releaseClose: CountDownLatch,
) : VpnOwnedRuntime {
    @Volatile
    var started = false

    @Volatile
    var closed = false

    override fun start() {
        started = true
    }

    override fun pause(duration: PauseDuration) {
        pauseEntered.countDown()
        check(releasePause.await(5, TimeUnit.SECONDS)) { "test pause was not released" }
        throw VpnRuntimeStartException(ProtectionFailure.EngineUnavailable)
    }

    override fun close() {
        if (closed) return
        closed = true
        closeEntered.countDown()
        check(releaseClose.await(5, TimeUnit.SECONDS)) { "test close was not released" }
    }
}

/**
 * A runtime whose [start] blocks (so it stays the pending attempt) and whose [close] blocks (so a
 * superseding command's post-monitor close can be observed).
 */
private class BlockingStartAndCloseRuntime(
    private val startEntered: CountDownLatch,
    private val releaseStart: CountDownLatch,
    private val closeEntered: CountDownLatch,
    private val releaseClose: CountDownLatch,
) : VpnOwnedRuntime {
    @Volatile
    var started = false

    @Volatile
    var closed = false

    override fun start() {
        startEntered.countDown()
        check(releaseStart.await(5, TimeUnit.SECONDS)) { "test start was not released" }
        started = true
    }

    override fun close() {
        if (closed) return
        closed = true
        closeEntered.countDown()
        check(releaseClose.await(5, TimeUnit.SECONDS)) { "test close was not released" }
    }
}
