package com.lavasec.app

import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.platform.ProtectionState
import com.lavasec.app.platform.VpnOwnership
import com.lavasec.app.product.FilterProfileSwitchResult
import com.lavasec.app.product.domain.DomainActionApplyResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProductionFilterAuthorityReloadTest {
    @Test
    fun `committed mutation reloads every live runtime state exactly once`() = runTest {
        val live = listOf<ProtectionState>(
            ProtectionState.Starting(com.lavasec.app.platform.StartOrigin.VisibleUser, 1L),
            ProtectionState.AwaitingNetwork(com.lavasec.app.platform.StartOrigin.VisibleUser, 1L),
            ProtectionState.Reconnecting(com.lavasec.app.platform.StartOrigin.Reload, 1L),
            ProtectionState.Protected(ConfigurationIdentity(1L, "one"), VpnOwnership.User),
            ProtectionState.Paused(10_000L),
        )
        var reloads = 0

        live.forEach { state ->
            assertEquals(
                DomainActionApplyResult.Applied(2L),
                reloadCommittedFilterIfRunning(
                    result = DomainActionApplyResult.Applied(2L),
                    applied = true,
                    protection = state,
                    reload = { reloads += 1 },
                ),
            )
        }

        assertEquals(live.size, reloads)
    }

    @Test
    fun `noncommitted mutation and terminal protection never reload`() = runTest {
        var reloads = 0
        val terminal = listOf<ProtectionState>(
            ProtectionState.Off,
            ProtectionState.SetupRequired,
            ProtectionState.Stopping,
            ProtectionState.Failed(com.lavasec.app.platform.ProtectionFailure.DependencyUnavailable),
        )

        terminal.forEach { state ->
            reloadCommittedFilterIfRunning(
                result = DomainActionApplyResult.Applied(2L),
                applied = true,
                protection = state,
                reload = { reloads += 1 },
            )
        }
        reloadCommittedFilterIfRunning(
            result = DomainActionApplyResult.Failed("not committed"),
            applied = false,
            protection = ProtectionState.Protected(
                ConfigurationIdentity(1L, "one"),
                VpnOwnership.User,
            ),
            reload = { reloads += 1 },
        )

        assertEquals(0, reloads)
    }

    @Test
    fun `committed switch reports applied even when the reload dispatch fails`() = runTest {
        var reloads = 0

        val result = reloadCommittedFilterBestEffort(
            result = FilterProfileSwitchResult.Applied("extra", generation = 3L),
            applied = true,
            protection = ProtectionState.Protected(
                ConfigurationIdentity(1L, "one"),
                VpnOwnership.User,
            ),
            reload = {
                reloads += 1
                error("Android rejected the service-start command")
            },
        )

        assertEquals(FilterProfileSwitchResult.Applied("extra", 3L), result)
        assertEquals(1, reloads)
    }

    @Test
    fun `failed reload dispatch arms the runtime reconciliation requirement`() = runTest {
        var armed = 0
        var discharged = 0

        reloadCommittedFilterBestEffort(
            result = FilterProfileSwitchResult.Applied("extra", 3L),
            applied = true,
            protection = ProtectionState.Protected(
                ConfigurationIdentity(1L, "one"),
                VpnOwnership.User,
            ),
            reload = { error("Android rejected the service-start command") },
            onDispatchSucceeded = { discharged += 1 },
            onDispatchFailed = { armed += 1 },
        )

        assertEquals(1, armed)
        assertEquals(0, discharged)
    }

    @Test
    fun `successful reload dispatch discharges a pending reconciliation requirement`() = runTest {
        var armed = 0
        var discharged = 0

        reloadCommittedFilterBestEffort(
            result = FilterProfileSwitchResult.Applied("extra", 3L),
            applied = true,
            protection = ProtectionState.Protected(
                ConfigurationIdentity(1L, "one"),
                VpnOwnership.User,
            ),
            reload = { },
            onDispatchSucceeded = { discharged += 1 },
            onDispatchFailed = { armed += 1 },
        )

        assertEquals(0, armed)
        assertEquals(1, discharged)
    }

    @Test
    fun `reconciliation retries an armed reload and discharges on success`() = runTest {
        var reloads = 0
        val latch = RuntimeReloadReconciliation(
            protection = {
                ProtectionState.Protected(
                    ConfigurationIdentity(1L, "one"),
                    VpnOwnership.User,
                )
            },
            reload = { reloads += 1 },
        )

        assertTrue(latch.reconcileOnce())
        assertEquals(0, reloads)

        latch.onDispatchFailed()
        assertTrue(latch.required.value)

        assertTrue(latch.reconcileOnce())
        assertEquals(1, reloads)
        assertFalse(latch.required.value)
    }

    @Test
    fun `failed reconciliation stays armed for a later runtime path`() = runTest {
        val latch = RuntimeReloadReconciliation(
            protection = {
                ProtectionState.Protected(
                    ConfigurationIdentity(1L, "one"),
                    VpnOwnership.User,
                )
            },
            reload = { error("background dispatch rejected again") },
        )
        latch.onDispatchFailed()

        assertFalse(latch.reconcileOnce())
        assertTrue(latch.required.value)
        assertFalse(latch.reconcileOnce())
        assertTrue(latch.required.value)
    }

    @Test
    fun `terminal protection discharges the armed requirement without dispatching`() = runTest {
        var reloads = 0
        val latch = RuntimeReloadReconciliation(
            protection = { ProtectionState.Off },
            reload = { reloads += 1 },
        )
        latch.onDispatchFailed()

        assertTrue(latch.reconcileOnce())
        assertEquals(0, reloads)
        assertFalse(latch.required.value)
    }

    @Test
    fun `reconciliation never swallows cancellation`() = runTest {
        val latch = RuntimeReloadReconciliation(
            protection = {
                ProtectionState.Protected(
                    ConfigurationIdentity(1L, "one"),
                    VpnOwnership.User,
                )
            },
            reload = { throw CancellationException("reconcile cancelled") },
        )
        latch.onDispatchFailed()
        val probe = async { latch.reconcileOnce() }

        probe.join()
        assertTrue(probe.getCompletionExceptionOrNull() is CancellationException)
        assertTrue(latch.required.value)
    }

    @Test
    fun `best effort reload never swallows cancellation`() = runTest {
        val probe = async {
            reloadCommittedFilterBestEffort(
                result = FilterProfileSwitchResult.Applied("extra", 3L),
                applied = true,
                protection = ProtectionState.Protected(
                    ConfigurationIdentity(1L, "one"),
                    VpnOwnership.User,
                ),
                reload = { throw CancellationException("caller was cancelled during reload") },
            )
        }

        probe.join()
        assertTrue(probe.getCompletionExceptionOrNull() is CancellationException)
    }
}
