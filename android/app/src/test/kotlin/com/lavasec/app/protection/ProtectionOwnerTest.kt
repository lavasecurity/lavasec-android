package com.lavasec.app.protection

import com.lavasec.app.foundation.chained.ChainedConfigurationIssue
import com.lavasec.app.foundation.onboarding.OnboardingPhase
import com.lavasec.app.foundation.onboarding.ProtectionLifecycleRecovery
import com.lavasec.app.foundation.onboarding.ProtectionRecoveryAction
import com.lavasec.app.foundation.pause.PauseDuration
import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.platform.ProtectionController
import com.lavasec.app.platform.ProtectionState
import com.lavasec.app.platform.StartOrigin
import com.lavasec.app.platform.VpnOwnership
import com.lavasec.app.presentation.GuardPrimaryAction
import com.lavasec.app.vpn.VpnHealthStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProtectionOwnerTest {
    @Test
    fun `missing or unreadable chained credentials refuse explicit start before intent and runtime mutation`() = runTest {
        for (issue in ChainedConfigurationIssue.entries) {
            val controller = RecordingProtectionController()
            val commits = mutableListOf<Boolean>()
            var currentIssue: ChainedConfigurationIssue? = issue
            val owner = ProtectionOwner(controller, MutableStateFlow(VpnHealthStatus()),
                { commits += it; ProtectionIntentCommit.Committed }, backgroundScope,
                validateChainedConfigurationForStart = { currentIssue })
            for (action in listOf(GuardPrimaryAction.TurnOn, GuardPrimaryAction.Reconnect)) {
                owner.perform(action)
                assertTrue(commits.isEmpty())
                assertTrue(controller.events.isEmpty())
                assertEquals(issue.message, owner.state.value.error)
                assertFalse(owner.state.value.commandInFlight)
            }
            currentIssue = null
            owner.perform(GuardPrimaryAction.TurnOn)
            assertEquals(listOf(true), commits)
            assertEquals(listOf("start"), controller.events)
            assertNull(owner.state.value.error)
            owner.close()
        }
    }

    @Test
    fun `chained setup refusal never prevents explicit stop or ordinary pause resume`() = runTest {
        val controller = RecordingProtectionController()
        val commits = mutableListOf<Boolean>()
        var probes = 0
        val owner = ProtectionOwner(controller, MutableStateFlow(VpnHealthStatus()),
            { commits += it; ProtectionIntentCommit.Committed }, backgroundScope,
            validateChainedConfigurationForStart = { probes++; ChainedConfigurationIssue.UNAVAILABLE })
        owner.perform(GuardPrimaryAction.TurnOff)
        owner.perform(GuardPrimaryAction.Resume)
        assertEquals(0, probes)
        assertEquals(listOf(false), commits)
        assertEquals(listOf("stop", "resume"), controller.events)
        owner.close()
    }

    @Test
    fun `a thrown setup probe reports a fixed failure without dispatch`() = runTest {
        val controller = RecordingProtectionController()
        val owner = ProtectionOwner(controller, MutableStateFlow(VpnHealthStatus()),
            { error("must not commit") }, backgroundScope,
            validateChainedConfigurationForStart = { error("private provider details") })
        owner.perform(GuardPrimaryAction.TurnOn)
        assertEquals(ChainedConfigurationIssue.UNAVAILABLE.message, owner.state.value.error)
        assertTrue(controller.events.isEmpty())
        owner.close()
    }

    @Test
    fun `retired owner cannot dispatch after a suspended configuration probe`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val controller = RecordingProtectionController()
        val owner = ProtectionOwner(controller, MutableStateFlow(VpnHealthStatus()),
            { error("retired owner must not commit") }, backgroundScope,
            validateChainedConfigurationForStart = { entered.complete(Unit); release.await(); null })
        val request = async { owner.perform(GuardPrimaryAction.TurnOn) }
        entered.await()
        owner.close()
        release.complete(Unit)
        assertFalse(request.await())
        assertTrue(controller.events.isEmpty())
    }

    @Test
    fun `Guard is derived from actual service state and never from submitted intent`() = runTest {
        val controller = RecordingProtectionController()
        val commits = mutableListOf<Boolean>()
        val owner = ProtectionOwner(
            controller = controller,
            health = MutableStateFlow(VpnHealthStatus()),
            commitProtectionIntent = {
                commits += it
                ProtectionIntentCommit.Committed
            },
            scope = this,
            nowMillis = { 10L },
        )
        runCurrent()

        owner.perform(GuardPrimaryAction.TurnOn)

        assertEquals(listOf(true), commits)
        assertEquals(listOf("start"), controller.events)
        assertEquals(
            com.lavasec.app.presentation.GuardLifecycleUi.INACTIVE,
            owner.state.value.guard.lifecycle,
        )
        assertFalse(owner.state.value.commandInFlight)

        controller.mutable.value = ProtectionState.Starting(StartOrigin.VisibleUser, 1L)
        runCurrent()
        assertEquals(
            com.lavasec.app.presentation.GuardLifecycleUi.STARTING,
            owner.state.value.guard.lifecycle,
        )
        controller.mutable.value = ProtectionState.Protected(
            ConfigurationIdentity(1L, "a".repeat(64)),
            VpnOwnership.User,
        )
        runCurrent()
        assertEquals(
            com.lavasec.app.presentation.GuardLifecycleUi.PROTECTED,
            owner.state.value.guard.lifecycle,
        )
        owner.close()
    }

    @Test
    fun `rejected or ambiguous intent never reaches the VPN controller`() = runTest {
        listOf(
            ProtectionIntentCommit.Rejected("not committed"),
            ProtectionIntentCommit.ReconstructionRequired("ambiguous"),
        ).forEach { result ->
            val controller = RecordingProtectionController()
            val owner = ProtectionOwner(
                controller = controller,
                health = MutableStateFlow(VpnHealthStatus()),
                commitProtectionIntent = { result },
                scope = this,
            )
            runCurrent()

            owner.perform(GuardPrimaryAction.TurnOn)

            assertTrue(controller.events.isEmpty())
            assertEquals(
            com.lavasec.app.presentation.GuardLifecycleUi.INACTIVE,
            owner.state.value.guard.lifecycle,
        )
            assertTrue(owner.state.value.error?.isNotBlank() == true)
            assertEquals(
                result is ProtectionIntentCommit.ReconstructionRequired,
                owner.state.value.reconstructionRequired,
            )
            owner.close()
        }
    }

    @Test
    fun `turn off commits authority before stopping and resume does not rewrite intent`() = runTest {
        val ordered = mutableListOf<String>()
        val controller = RecordingProtectionController(ordered)
        controller.mutable.value = ProtectionState.Protected(
            ConfigurationIdentity(2L, "b".repeat(64)),
            VpnOwnership.User,
        )
        val owner = ProtectionOwner(
            controller = controller,
            health = MutableStateFlow(VpnHealthStatus()),
            commitProtectionIntent = {
                ordered += "commit:$it"
                ProtectionIntentCommit.Committed
            },
            scope = this,
        )
        runCurrent()

        owner.perform(GuardPrimaryAction.TurnOff)
        owner.perform(GuardPrimaryAction.Resume)

        assertEquals(listOf("commit:false", "stop", "resume"), ordered)
        owner.close()
    }

    @Test
    fun `controller failure is reported without forging a terminal service state`() = runTest {
        val controller = RecordingProtectionController().apply { failStart = true }
        val owner = ProtectionOwner(
            controller = controller,
            health = MutableStateFlow(VpnHealthStatus()),
            commitProtectionIntent = { ProtectionIntentCommit.Committed },
            scope = this,
        )
        runCurrent()

        owner.perform(GuardPrimaryAction.TurnOn)

        assertEquals(
            com.lavasec.app.presentation.GuardLifecycleUi.INACTIVE,
            owner.state.value.guard.lifecycle,
        )
        assertEquals("start failed", owner.state.value.error)
        assertFalse(owner.state.value.commandInFlight)
        owner.close()
    }

    @Test
    fun `closed owner rejects commands and cannot publish late flow changes`() = runTest {
        val controller = RecordingProtectionController()
        val owner = ProtectionOwner(
            controller = controller,
            health = MutableStateFlow(VpnHealthStatus()),
            commitProtectionIntent = { ProtectionIntentCommit.Committed },
            scope = this,
        )
        runCurrent()
        val before = owner.state.value

        owner.close()
        controller.mutable.value = ProtectionState.Starting(StartOrigin.Reload, 8L)
        runCurrent()

        assertEquals(before, owner.state.value)
        assertFalse(owner.perform(GuardPrimaryAction.TurnOn))
        assertTrue(controller.events.isEmpty())
    }

    @Test
    fun `settings actions are returned to the Android host without controller mutation`() = runTest {
        val controller = RecordingProtectionController()
        val owner = ProtectionOwner(
            controller = controller,
            health = MutableStateFlow(VpnHealthStatus()),
            commitProtectionIntent = { ProtectionIntentCommit.Committed },
            scope = this,
        )

        val route = owner.perform(
            GuardPrimaryAction.OpenSettings(com.lavasec.app.platform.SettingsRoute.VpnSettings),
        )

        assertTrue(route)
        assertEquals(
            com.lavasec.app.platform.SettingsRoute.VpnSettings,
            owner.consumeSettingsRoute(),
        )
        assertNull(owner.consumeSettingsRoute())
        assertTrue(controller.events.isEmpty())
        owner.close()
    }

    @Test
    fun `graph retirement cancels an uncommitted owner command`() = runTest {
        val commitStarted = CompletableDeferred<Unit>()
        val neverCommit = CompletableDeferred<ProtectionIntentCommit>()
        val controller = RecordingProtectionController()
        val owner = ProtectionOwner(
            controller = controller,
            health = MutableStateFlow(VpnHealthStatus()),
            commitProtectionIntent = {
                commitStarted.complete(Unit)
                neverCommit.await()
            },
            scope = this,
        )
        val command = async { owner.perform(GuardPrimaryAction.TurnOn) }
        commitStarted.await()

        owner.close()
        runCurrent()

        assertFalse(command.await())
        assertTrue(controller.events.isEmpty())
    }

    @Test
    fun `armed runtime reload requirement retries the attachment and settles once discharged`() =
        runTest {
            val controller = RecordingProtectionController()
            val required = MutableStateFlow(false)
            var attempts = 0
            val owner = ProtectionOwner(
                controller = controller,
                health = MutableStateFlow(VpnHealthStatus()),
                commitProtectionIntent = { ProtectionIntentCommit.Committed },
                scope = this,
                runtimeReloadRequired = required,
                reconcileRuntimeReload = {
                    attempts += 1
                    required.value = false
                    true
                },
            )
            runCurrent()

            assertEquals(0, attempts)
            required.value = true
            runCurrent()

            assertEquals(1, attempts)
            assertFalse(required.value)
            owner.close()
        }

    @Test
    fun `failed reconciliation stays armed and retries on the next runtime state change`() =
        runTest {
            val controller = RecordingProtectionController()
            val required = MutableStateFlow(false)
            var attempts = 0
            val owner = ProtectionOwner(
                controller = controller,
                health = MutableStateFlow(VpnHealthStatus()),
                commitProtectionIntent = { ProtectionIntentCommit.Committed },
                scope = this,
                runtimeReloadRequired = required,
                reconcileRuntimeReload = {
                    attempts += 1
                    false
                },
            )
            runCurrent()

            required.value = true
            runCurrent()
            assertEquals(1, attempts)
            assertTrue(required.value)

            // A later runtime transition retries the deferred attachment while armed.
            controller.mutable.value = ProtectionState.Starting(StartOrigin.Reload, 9L)
            runCurrent()
            assertEquals(2, attempts)

            owner.close()
            assertTrue(controller.events.isEmpty())
        }

    @Test
    fun `failed reconciliation retries when the app returns to the foreground`() = runTest {
        val controller = RecordingProtectionController()
        val required = MutableStateFlow(false)
        val started = MutableStateFlow(false)
        var attempts = 0
        val owner = ProtectionOwner(
            controller = controller,
            health = MutableStateFlow(VpnHealthStatus()),
            commitProtectionIntent = { ProtectionIntentCommit.Committed },
            scope = this,
            runtimeReloadRequired = required,
            reconcileRuntimeReload = {
                attempts += 1
                false
            },
            appStarted = started,
        )
        runCurrent()

        // The armed requirement's immediate retry runs while the app is still backgrounded
        // and fails; a runtime transition while backgrounded keeps the existing semantics.
        required.value = true
        runCurrent()
        assertEquals(1, attempts)
        controller.mutable.value = ProtectionState.Starting(StartOrigin.Reload, 9L)
        runCurrent()
        assertEquals(2, attempts)

        // Returning to the foreground retries the armed latch without any runtime
        // transition; leaving the foreground never dispatches (a backgrounded reload is
        // exactly the dispatch the execution limits rejected).
        started.value = true
        runCurrent()
        assertEquals(3, attempts)
        started.value = false
        runCurrent()
        assertEquals(3, attempts)
        started.value = true
        runCurrent()
        assertEquals(4, attempts)
        assertTrue(required.value)

        owner.close()
        assertTrue(controller.events.isEmpty())
    }

    @Test
    fun `foreground reconciliation discharge ends further retries`() = runTest {
        val controller = RecordingProtectionController()
        val required = MutableStateFlow(false)
        val started = MutableStateFlow(false)
        var attempts = 0
        val owner = ProtectionOwner(
            controller = controller,
            health = MutableStateFlow(VpnHealthStatus()),
            commitProtectionIntent = { ProtectionIntentCommit.Committed },
            scope = this,
            runtimeReloadRequired = required,
            reconcileRuntimeReload = {
                attempts += 1
                if (started.value) {
                    required.value = false
                    true
                } else {
                    false
                }
            },
            appStarted = started,
        )
        runCurrent()

        // The backgrounded immediate retry fails and keeps the requirement armed.
        required.value = true
        runCurrent()
        assertEquals(1, attempts)
        assertTrue(required.value)

        // The foreground entry dispatches once and the authority discharges the latch.
        started.value = true
        runCurrent()
        assertEquals(2, attempts)
        assertFalse(required.value)

        // Later foreground entries do not re-dispatch a discharged requirement.
        started.value = false
        runCurrent()
        started.value = true
        runCurrent()
        assertEquals(2, attempts)

        owner.close()
        assertTrue(controller.events.isEmpty())
    }

    @Test
    fun `reconciliation dispatch failure is not surfaced as a forged guard error`() = runTest {
        val controller = RecordingProtectionController()
        val required = MutableStateFlow(false)
        val owner = ProtectionOwner(
            controller = controller,
            health = MutableStateFlow(VpnHealthStatus()),
            commitProtectionIntent = { ProtectionIntentCommit.Committed },
            scope = this,
            runtimeReloadRequired = required,
            reconcileRuntimeReload = { error("background dispatch rejected") },
        )
        runCurrent()

        required.value = true
        runCurrent()

        assertEquals(
            com.lavasec.app.presentation.GuardLifecycleUi.INACTIVE,
            owner.state.value.guard.lifecycle,
        )
        assertNull(owner.state.value.error)
        owner.close()
    }

    @Test
    fun `resolver health derives from the same runtime assessment as the guard status`() =
        runTest {
            val controller = RecordingProtectionController()
            controller.mutable.value = ProtectionState.Protected(
                ConfigurationIdentity(3L, "c".repeat(64)),
                VpnOwnership.User,
            )
            val health = MutableStateFlow(
                VpnHealthStatus(
                    health = com.lavasec.app.foundation.health.ProtectionHealthAccumulator(
                        isConnected = true,
                        activeFallback = com.lavasec.app.foundation.health.ProtectionFallback.ENCRYPTED_DNS,
                        context = com.lavasec.app.foundation.health.ProtectionHealthContext(
                            sessionGeneration = 1L,
                            networkGeneration = 1L,
                            engineGeneration = 1L,
                            observationSequence = 1L,
                            observedAtMillis = 10L,
                            primaryResolverIdentity = com.lavasec.app.foundation.health
                                .PrimaryResolverIdentity("device-dns:v4=08080808"),
                            fallbackCoverage = com.lavasec.app.foundation.health
                                .ResolverFallbackCoverage.ENCRYPTED_DNS,
                        ),
                    ),
                ),
            )
            val owner = ProtectionOwner(
                controller = controller,
                health = health,
                commitProtectionIntent = { ProtectionIntentCommit.Committed },
                scope = this,
                nowMillis = { 10L },
            )
            runCurrent()

            // One assessment feeds both projections: the guard connectivity and
            // the typed resolver health can never disagree.
            assertEquals(
                com.lavasec.app.presentation.GuardConnectivityUi.ENCRYPTED_FALLBACK,
                owner.state.value.guard.connectivity,
            )
            assertEquals(
                com.lavasec.app.presentation.resolver.ResolverHealthUi.ENCRYPTED_FALLBACK,
                owner.state.value.resolverHealth,
            )

            // Without a confirmed assessment context the health is null, never fabricated.
            health.value = VpnHealthStatus()
            runCurrent()
            assertEquals(
                com.lavasec.app.presentation.GuardConnectivityUi.UNAVAILABLE,
                owner.state.value.guard.connectivity,
            )
            assertNull(owner.state.value.resolverHealth)
            owner.close()
        }

    @Test
    fun `start is refused until first-run preparation completes, then granted`() = runTest {
        val controller = RecordingProtectionController()
        val commits = mutableListOf<Boolean>()
        val owner = ProtectionOwner(
            controller = controller,
            health = MutableStateFlow(VpnHealthStatus()),
            commitProtectionIntent = { commits += it; ProtectionIntentCommit.Committed },
            scope = this,
            initialOnboardingPhase = OnboardingPhase.FRESH_INSTALL,
        )
        runCurrent()
        assertEquals(OnboardingPhase.FRESH_INSTALL, owner.state.value.lifecycle!!.onboarding)
        assertFalse(owner.state.value.lifecycle!!.isRequestedEnabled)

        // Denied: the request records no intent, commits nothing and dispatches nothing.
        owner.perform(GuardPrimaryAction.TurnOn)
        assertTrue(commits.isEmpty())
        assertTrue(controller.events.isEmpty())
        assertFalse(owner.state.value.lifecycle!!.isRequestedEnabled)
        assertEquals(OnboardingPhase.FRESH_INSTALL, owner.state.value.lifecycle!!.onboarding)
        assertTrue(owner.state.value.error?.isNotBlank() == true)

        // Granted: preparation reaches READY and the same request now starts.
        assertTrue(owner.beginDefaultFilterPreparation())
        assertTrue(owner.completeDefaultFilterPreparation(prepared = true))
        assertEquals(OnboardingPhase.READY, owner.state.value.lifecycle!!.onboarding)
        owner.perform(GuardPrimaryAction.TurnOn)
        assertTrue(owner.state.value.lifecycle!!.isRequestedEnabled)
        assertEquals(listOf(true), commits)
        assertEquals(listOf("start"), controller.events)
        owner.close()
    }

    @Test
    fun `failed preparation is surfaced as a retryable recovery that keeps start refused`() = runTest {
        val controller = RecordingProtectionController()
        val owner = ProtectionOwner(
            controller = controller,
            health = MutableStateFlow(VpnHealthStatus()),
            commitProtectionIntent = { error("must not commit") },
            scope = this,
            initialOnboardingPhase = OnboardingPhase.FRESH_INSTALL,
        )
        runCurrent()
        assertTrue(owner.beginDefaultFilterPreparation())
        assertTrue(owner.completeDefaultFilterPreparation(prepared = false))
        assertEquals(OnboardingPhase.PREPARATION_FAILED, owner.state.value.lifecycle!!.onboarding)
        assertEquals(
            ProtectionRecoveryAction.RETRY_DEFAULT_FILTER_PREPARATION,
            owner.state.value.lifecycle!!.recovery!!.action,
        )
        owner.perform(GuardPrimaryAction.TurnOn)
        assertTrue(owner.state.value.lifecycle!!.recovery is ProtectionLifecycleRecovery.DefaultFilterPreparationFailed)
        assertTrue(controller.events.isEmpty())
        owner.close()
    }

    @Test
    fun `stop during preparation cancels it and records explicit off intent`() = runTest {
        val controller = RecordingProtectionController()
        val commits = mutableListOf<Boolean>()
        val owner = ProtectionOwner(
            controller = controller,
            health = MutableStateFlow(VpnHealthStatus()),
            commitProtectionIntent = { commits += it; ProtectionIntentCommit.Committed },
            scope = this,
            initialOnboardingPhase = OnboardingPhase.PREPARING_DEFAULT_FILTER,
        )
        runCurrent()
        assertEquals(OnboardingPhase.PREPARING_DEFAULT_FILTER, owner.state.value.lifecycle!!.onboarding)

        owner.perform(GuardPrimaryAction.TurnOff)
        assertEquals(OnboardingPhase.FRESH_INSTALL, owner.state.value.lifecycle!!.onboarding)
        assertFalse(owner.state.value.lifecycle!!.isRequestedEnabled)
        assertFalse(owner.state.value.lifecycle!!.isConfirmedEnabled)
        assertEquals(listOf(false), commits)
        assertEquals(listOf("stop"), controller.events)
        owner.close()
    }

    @Test
    fun `pause is session-bound and a restart reconciles an expired window`() = runTest {
        val controller = RecordingProtectionController()
        controller.mutable.value = ProtectionState.Protected(
            ConfigurationIdentity(1L, "a".repeat(64)),
            VpnOwnership.User,
        )
        var now = 1_000L
        val owner = ProtectionOwner(
            controller = controller,
            health = MutableStateFlow(VpnHealthStatus()),
            commitProtectionIntent = { ProtectionIntentCommit.Committed },
            scope = this,
            nowMillis = { now },
        )
        runCurrent()
        assertTrue(owner.state.value.lifecycle!!.isConfirmedEnabled)

        owner.pause(PauseDuration.TEN_MINUTES)
        val active = owner.state.value.lifecycle!!.activePause
        assertNotNull(active)
        assertEquals(owner.state.value.lifecycle!!.protectionSessionId, active!!.protectionSessionId)
        assertEquals(listOf("pause:10"), controller.events)

        // A restart whose clock has advanced past the persisted deadline drops the window.
        now = active.deadlineMillis + 1L
        assertTrue(owner.reconcilePause())
        assertNull(owner.state.value.lifecycle!!.activePause)
        owner.close()

        // A replacement owner constructed against a still-valid runtime pause adopts it; the
        // persisted deadline (not the constructor's clock) is the validity input.
        controller.mutable.value = ProtectionState.Paused(
            2_000L + PauseDuration.TEN_MINUTES.durationMillis,
        )
        val restarted = ProtectionOwner(
            controller = controller,
            health = MutableStateFlow(VpnHealthStatus()),
            commitProtectionIntent = { ProtectionIntentCommit.Committed },
            scope = this,
            nowMillis = { 2_000L },
        )
        runCurrent()
        assertNotNull(restarted.state.value.lifecycle!!.activePause)
        restarted.close()
    }

    @Test
    fun `resume with unavailable configuration lifts the pause and surfaces the retry recovery`() = runTest {
        val controller = RecordingProtectionController()
        controller.mutable.value = ProtectionState.Protected(
            ConfigurationIdentity(1L, "a".repeat(64)),
            VpnOwnership.User,
        )
        val owner = ProtectionOwner(
            controller = controller,
            health = MutableStateFlow(VpnHealthStatus()),
            commitProtectionIntent = { ProtectionIntentCommit.Committed },
            scope = this,
            nowMillis = { 1_000L },
            resumeConfigurationAvailable = { false },
        )
        runCurrent()
        owner.pause(PauseDuration.FIVE_MINUTES)
        assertNotNull(owner.state.value.lifecycle!!.activePause)

        owner.perform(GuardPrimaryAction.Resume)
        assertNull(owner.state.value.lifecycle!!.activePause)
        assertTrue(
            owner.state.value.lifecycle!!.recovery is
                ProtectionLifecycleRecovery.ResumeConfigurationUnavailable,
        )
        assertEquals(
            ProtectionRecoveryAction.RETRY_RESUME,
            owner.state.value.lifecycle!!.recovery!!.action,
        )
        assertEquals(listOf("pause:5", "resume"), controller.events)
        owner.close()
    }

    @Test
    fun `requested and confirmed are reported independently and never derived from each other`() = runTest {
        val controller = RecordingProtectionController()
        val owner = ProtectionOwner(
            controller = controller,
            health = MutableStateFlow(VpnHealthStatus()),
            commitProtectionIntent = { ProtectionIntentCommit.Committed },
            scope = this,
            nowMillis = { 10L },
        )
        runCurrent()
        assertFalse(owner.state.value.lifecycle!!.isRequestedEnabled)
        assertFalse(owner.state.value.lifecycle!!.isConfirmedEnabled)

        // An explicit request is recorded immediately, before any platform confirmation.
        owner.perform(GuardPrimaryAction.TurnOn)
        assertTrue(owner.state.value.lifecycle!!.isRequestedEnabled)
        assertFalse(owner.state.value.lifecycle!!.isConfirmedEnabled)

        // The platform observation writes only the confirmed side.
        controller.mutable.value = ProtectionState.Protected(
            ConfigurationIdentity(1L, "a".repeat(64)),
            VpnOwnership.User,
        )
        runCurrent()
        assertTrue(owner.state.value.lifecycle!!.isConfirmedEnabled)
        assertTrue(owner.state.value.lifecycle!!.isRequestedEnabled)

        // A stop is requested while the platform still reports connected: the two disagree.
        owner.perform(GuardPrimaryAction.TurnOff)
        assertFalse(owner.state.value.lifecycle!!.isRequestedEnabled)
        assertTrue(owner.state.value.lifecycle!!.isConfirmedEnabled)
        owner.close()
    }
}

private class RecordingProtectionController(
    val events: MutableList<String> = mutableListOf(),
) : ProtectionController {
    val mutable = MutableStateFlow<ProtectionState>(ProtectionState.Off)
    override val state = mutable
    var failStart = false

    override suspend fun start() {
        events += "start"
        if (failStart) error("start failed")
    }

    override suspend fun stop() {
        events += "stop"
    }

    override suspend fun pause(duration: PauseDuration) {
        events += "pause:${duration.minutes}"
    }

    override suspend fun resume() {
        events += "resume"
    }

    override suspend fun reconnect() {
        events += "reconnect"
    }
}
