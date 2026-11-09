package com.lavasec.app.reactnative.components

import com.lavasec.app.foundation.flows.NativeFlowBuildConfiguration
import com.lavasec.app.foundation.security.ProtectedSurface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM coverage for the single native flow host: the public/internal vocabulary boundary, the
 * one-active-presentation rule and the result binding to request/route/graph/account.
 */
class LavaNativeFlowPolicyTest {

    private fun request(requestId: Long = 1L, flow: String = "account") =
        LavaNativeFlowRequest(requestId = requestId, flow = flow, graphGeneration = 3L, accountId = "acct-1")

    @Test
    fun `a canonical flow is launchable in release`() {
        assertNull(LavaNativeFlowPolicy.refusal(request(flow = "account"), NativeFlowBuildConfiguration.RELEASE))
    }

    @Test
    fun `an internal presentation is refused in every build`() {
        for (configuration in NativeFlowBuildConfiguration.entries) {
            assertEquals(
                LavaNativeFlowReason.INTERNAL_PRESENTATION,
                LavaNativeFlowPolicy.refusal(request(flow = "createFilter"), configuration),
            )
        }
    }

    @Test
    fun `a QA-only flow is refused in release`() {
        assertEquals(
            LavaNativeFlowReason.QA_FLOW_IN_RELEASE,
            LavaNativeFlowPolicy.refusal(request(flow = "phoneQA"), NativeFlowBuildConfiguration.RELEASE),
        )
        assertNull(LavaNativeFlowPolicy.refusal(request(flow = "phoneQA"), NativeFlowBuildConfiguration.DEBUG))
    }

    @Test
    fun `an unknown flow is refused`() {
        assertEquals(
            LavaNativeFlowReason.UNKNOWN_FLOW,
            LavaNativeFlowPolicy.refusal(request(flow = "notAFlow"), NativeFlowBuildConfiguration.RELEASE),
        )
    }

    @Test
    fun `only one request may own the host`() {
        assertTrue(LavaNativeFlowPolicy.admit(activeRequestId = null, requestId = 1L))
        assertTrue(LavaNativeFlowPolicy.admit(activeRequestId = 1L, requestId = 1L))
        assertEquals(false, LavaNativeFlowPolicy.admit(activeRequestId = 1L, requestId = 2L))
    }

    @Test
    fun `a completed result is delivered when the graph and account still match`() {
        val outcome = LavaNativeFlowPolicy.bindResult(
            request(),
            LavaNativeFlowLaunchResult.Launched(completed = true),
            currentGraphGeneration = 3L,
            currentAccountId = "acct-1",
        )
        assertEquals(LavaNativeFlowOutcome.Completed(1L), outcome)
    }

    @Test
    fun `a dismissed result is a typed cancellation`() {
        val outcome = LavaNativeFlowPolicy.bindResult(
            request(),
            LavaNativeFlowLaunchResult.Launched(completed = false),
            currentGraphGeneration = 3L,
            currentAccountId = "acct-1",
        )
        assertEquals(LavaNativeFlowOutcome.Cancelled(1L, LavaNativeFlowReason.CANCELLED), outcome)
    }

    @Test
    fun `a rotated credential graph makes the result stale`() {
        val outcome = LavaNativeFlowPolicy.bindResult(
            request(),
            LavaNativeFlowLaunchResult.Launched(completed = true),
            currentGraphGeneration = 4L,
            currentAccountId = "acct-1",
        )
        assertEquals(LavaNativeFlowOutcome.Stale(1L), outcome)
    }

    @Test
    fun `a switched account refuses the result`() {
        val outcome = LavaNativeFlowPolicy.bindResult(
            request(),
            LavaNativeFlowLaunchResult.Launched(completed = true),
            currentGraphGeneration = 3L,
            currentAccountId = "acct-2",
        )
        assertEquals(LavaNativeFlowOutcome.Refused(1L, LavaNativeFlowReason.ACCOUNT_CHANGED), outcome)
    }

    @Test
    fun `an unavailable launcher is a typed refusal`() {
        val outcome = LavaNativeFlowPolicy.bindResult(
            request(),
            LavaNativeFlowLaunchResult.Unavailable(LavaNativeFlowReason.HOST_UNAVAILABLE),
            currentGraphGeneration = 3L,
            currentAccountId = "acct-1",
        )
        assertEquals(LavaNativeFlowOutcome.Refused(1L, LavaNativeFlowReason.HOST_UNAVAILABLE), outcome)
    }
}

/** Host-level coverage: the active slot is shared by route and credential presentations. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LavaNativeFlowHostTest {

    private class FakeLauncher(
        private val route: suspend () -> LavaNativeFlowLaunchResult = { LavaNativeFlowLaunchResult.Launched(true) },
    ) : LavaNativeFlowLauncher {
        var routeCalls = 0
        var credentialCalls = 0
        override suspend fun presentRoute(request: LavaNativeFlowRequest): LavaNativeFlowLaunchResult {
            routeCalls++
            return route()
        }

        override suspend fun authorizeCredential(
            request: LavaNativeFlowRequest,
            surface: ProtectedSurface?,
        ): LavaNativeFlowLaunchResult {
            credentialCalls++
            return LavaNativeFlowLaunchResult.Launched(true)
        }
    }

    private fun host(launcher: LavaNativeFlowLauncher, generation: Long = 3L, account: String? = "acct-1") =
        LavaNativeFlowHost(
            configuration = NativeFlowBuildConfiguration.DEBUG,
            launcher = launcher,
            currentGraphGeneration = { generation },
            currentAccountId = { account },
        )

    private fun request(requestId: Long = 1L, flow: String = "account") =
        LavaNativeFlowRequest(requestId, flow, graphGeneration = 3L, accountId = "acct-1")

    @Test
    fun `a route launch binds the result to the initiating graph and account`() = runTest {
        val launcher = FakeLauncher()
        val outcome = host(launcher).launchRoute(request())
        assertEquals(LavaNativeFlowOutcome.Completed(1L), outcome)
        assertEquals(1, launcher.routeCalls)
    }

    @Test
    fun `an internal presentation never reaches the launcher`() = runTest {
        val launcher = FakeLauncher()
        val outcome = host(launcher).launchRoute(request(flow = "createFilter"))
        assertEquals(LavaNativeFlowOutcome.Refused(1L, LavaNativeFlowReason.INTERNAL_PRESENTATION), outcome)
        assertEquals(0, launcher.routeCalls)
    }

    @Test
    fun `the credential flow is presented through the same active slot`() = runTest {
        val launcher = FakeLauncher()
        val outcome = host(launcher).authorizeCredential(request(), ProtectedSurface.APP_SETTINGS)
        assertEquals(LavaNativeFlowOutcome.Completed(1L), outcome)
        assertEquals(1, launcher.credentialCalls)
    }

    @Test
    fun `a second launch is refused while one presentation is active`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val launcher = FakeLauncher(route = { gate.await(); LavaNativeFlowLaunchResult.Launched(true) })
        val host = host(launcher)
        val first = async { host.launchRoute(request(requestId = 1L)) }
        advanceUntilIdle()
        assertEquals(1L, host.activeRequestId)
        val second = host.launchRoute(request(requestId = 2L))
        assertEquals(LavaNativeFlowOutcome.Refused(2L, LavaNativeFlowReason.BUSY), second)
        gate.complete(Unit)
        assertEquals(LavaNativeFlowOutcome.Completed(1L), first.await())
        assertNull(host.activeRequestId)
    }

    @Test
    fun `a result whose graph rotated after launch is stale`() = runTest {
        val launcher = FakeLauncher()
        val outcome = host(launcher, generation = 9L).launchRoute(request())
        assertEquals(LavaNativeFlowOutcome.Stale(1L), outcome)
    }
}
