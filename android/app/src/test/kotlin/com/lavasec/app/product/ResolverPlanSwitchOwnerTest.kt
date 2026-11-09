package com.lavasec.app.product

import com.lavasec.app.foundation.resolver.CatalogResolverTransport
import com.lavasec.app.foundation.resolver.ChainedDnsEditState
import com.lavasec.app.foundation.resolver.FilterResolverPlan
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.foundation.resolver.ResolverEditRejection
import com.lavasec.app.foundation.resolver.ResolverEditResult
import com.lavasec.app.foundation.resolver.ResolverProviderId
import com.lavasec.app.foundation.resolver.ResolverSelectionPresets
import com.lavasec.app.foundation.resolver.ResolverTierChoice
import com.lavasec.app.foundation.resolver.SaveResolverTiers
import com.lavasec.app.foundation.subscription.FeatureLimits
import com.lavasec.app.platform.FilterMutationFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ResolverPlanSwitchOwnerTest {
    @Test fun `QA validates fresh chain and real Plus without making a separate resolver write`() = runTest {
        val port = FakePort { plan -> ResolverPlanSwitchResult.Applied(plan, 2) }
        var fresh = ChainedDnsEditState.editable
        val guard = com.lavasec.app.platform.NativeFilterPublicationGuard { it() }
        val owner = ResolverPlanSwitchOwner(port, this, StandardTestDispatcher(testScheduler),
            captureQaDnsAdmission = { QaDnsPublicationAdmission(fresh, guard) })
        val selection = ResolverSelectionPresets.DEVICE_DNS_ONLY
        val context = ResolverDnsEditContext(selection, FeatureLimits.FREE, ChainedDnsEditState.editable, ResolverDnsContext.encode(selection))
        val custom = SaveResolverTiers(listOf(ResolverTierChoice("custom-dns", "DoQ", primary = "doq://dns.adguard-dns.com:853")), context.editorContext)
        var compositeCalls = 0
        val publish: suspend (ReviewedQaResolverEdit) -> FilterLibraryMutationResult = {
            compositeCalls++; org.junit.Assert.assertSame(guard, it.publicationGuard)
            FilterLibraryMutationResult.Applied(4)
        }
        assertEquals(QaCompositeConfigurationResult.Refused(ResolverEditRejection.CustomRequiresPlus),
            owner.applyReviewedDnsForQa(custom, context, { true }, publish))
        fresh = ChainedDnsEditState(true, false, false)
        assertEquals(QaCompositeConfigurationResult.Refused(ResolverEditRejection.ChainedEditLocked),
            owner.applyReviewedDnsForQa(custom, context.copy(limits = FeatureLimits.PLUS), { true }, publish))
        fresh = ChainedDnsEditState.editable
        assertEquals(QaCompositeConfigurationResult.Applied(4),
            owner.applyReviewedDnsForQa(custom, context.copy(limits = FeatureLimits.PLUS), { true }, publish))
        assertEquals(QaCompositeConfigurationResult.Unavailable,
            owner.applyReviewedDnsForQa(custom, context.copy(limits = FeatureLimits.PLUS, editorContext = null), { true }, publish))
        assertEquals(1, compositeCalls); assertTrue(port.requests.isEmpty())
        owner.close()
    }

    @Test fun `QA caller cancellation during fresh authority read never invokes composite publication`() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val port = FakePort { plan -> ResolverPlanSwitchResult.Applied(plan, 2) }
        val owner = ResolverPlanSwitchOwner(port, this, StandardTestDispatcher(testScheduler), captureQaDnsAdmission = {
            entered.complete(Unit)
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() }
            QaDnsPublicationAdmission(ChainedDnsEditState.editable, com.lavasec.app.platform.NativeFilterPublicationGuard { it() })
        })
        val selection = ResolverSelectionPresets.DEVICE_DNS_ONLY
        val context = ResolverDnsEditContext(selection, FeatureLimits.FREE, ChainedDnsEditState.editable, ResolverDnsContext.encode(selection))
        var publications = 0
        val task = launch { owner.applyReviewedDnsForQa(SaveResolverTiers(listOf(ResolverTierChoice("google-public-dns", "IP")), context.editorContext), context, { true }) {
            publications++; FilterLibraryMutationResult.Applied(2)
        } }
        entered.await(); task.cancel(); release.complete(Unit); task.join()
        assertEquals(0, publications); assertTrue(port.requests.isEmpty()); owner.close()
    }

    @Test
    fun `staging validates without applying and reloads fresh chained authority`() = runTest {
        val port = FakePort { plan -> ResolverPlanSwitchResult.Applied(plan, 2) }
        var fresh = ChainedDnsEditState.editable
        val owner = ResolverPlanSwitchOwner(port, this, StandardTestDispatcher(testScheduler), loadChainedDnsEdit = { fresh })
        val choice = ResolverTierChoice("custom-dns", "DoH", "", "https://dns.example/dns-query")
        val context = ResolverDnsEditContext(ResolverSelectionPresets.DEVICE_DNS_ONLY,
            FeatureLimits.PLUS, ChainedDnsEditState.editable, "source")
        assertEquals(ResolverDnsEditOutcome.DraftStaged(choice), owner.stageCustomDns(choice, "source", context))
        assertEquals(ResolverDnsEditOutcome.Refused(ResolverEditRejection.StaleEditor), owner.stageCustomDns(choice, "old", context))
        assertEquals(ResolverDnsEditOutcome.Refused(ResolverEditRejection.CustomRequiresPlus),
            owner.stageCustomDns(choice, "source", context.copy(limits = FeatureLimits.FREE)))
        fresh = ChainedDnsEditState(true, false, false)
        assertEquals(ResolverDnsEditOutcome.Refused(ResolverEditRejection.ChainedEditLocked),
            owner.stageCustomDns(choice, "source", context))
        assertEquals(ResolverPlanSwitchOwner.State.Idle, owner.state.value)
        assertTrue(port.requests.isEmpty())
        owner.close()
        assertEquals(ResolverDnsEditOutcome.Unavailable, owner.stageCustomDns(choice, "source", context))
    }

    @Test
    fun `cancelled staging and closed owner after fresh load never return completed choice`() = runTest {
        val port = FakePort { plan -> ResolverPlanSwitchResult.Applied(plan, 2) }
        val entered = CompletableDeferred<Unit>();val release = CompletableDeferred<Unit>()
        val owner = ResolverPlanSwitchOwner(port, this, StandardTestDispatcher(testScheduler), loadChainedDnsEdit = {
            entered.complete(Unit);release.await();ChainedDnsEditState.editable
        })
        val context = ResolverDnsEditContext(ResolverSelectionPresets.DEVICE_DNS_ONLY,
            FeatureLimits.PLUS, ChainedDnsEditState.editable, "source")
        var completed = false
        val staging = launch { owner.stageCustomDns(ResolverTierChoice("custom-dns", "DoQ", primary = "doq://dns.example:853"), "source", context);completed = true }
        entered.await();staging.cancel();release.complete(Unit);staging.join()
        assertFalse(completed);assertTrue(port.requests.isEmpty());owner.close()

        lateinit var closing: ResolverPlanSwitchOwner
        closing = ResolverPlanSwitchOwner(port, this, loadChainedDnsEdit = { closing.close();ChainedDnsEditState.editable })
        // Retiring during the fresh-authority read cannot return a completed proposal.
        assertEquals(ResolverDnsEditOutcome.Unavailable,
            closing.stageCustomDns(ResolverTierChoice("custom-dns", "DoH", primary = "https://dns.example/query"), "source", context))
        assertTrue(port.requests.isEmpty())
    }

    @Test
    fun `applied switch returns true and settles to idle`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val port = FakePort { plan ->
            ResolverPlanSwitchResult.Applied(plan, generation = 2L)
        }
        val owner = ResolverPlanSwitchOwner(port, this, dispatcher)

        assertTrue(owner.apply(MilestoneResolverPlan.MULLVAD_ONLY))
        advanceUntilIdle()

        assertEquals(listOf(MilestoneResolverPlan.MULLVAD_ONLY), port.requests)
        assertEquals(ResolverPlanSwitchOwner.State.Idle, owner.state.value)
        assertFalse(owner.reconstructionRequired.value)
        owner.close()
    }

    @Test
    fun `in flight state is visible while the port is suspended`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val release = CompletableDeferred<Unit>()
        val port = FakePort { plan ->
            release.await()
            ResolverPlanSwitchResult.Applied(plan, generation = 2L)
        }
        val owner = ResolverPlanSwitchOwner(port, this, dispatcher)

        val applying = launch(dispatcher) {
            owner.apply(MilestoneResolverPlan.DEVICE_DNS_ONLY)
        }
        advanceUntilIdle()

        assertEquals(
            ResolverPlanSwitchOwner.State.Applying(MilestoneResolverPlan.DEVICE_DNS_ONLY),
            owner.state.value,
        )
        release.complete(Unit)
        applying.join()
        advanceUntilIdle()
        assertEquals(ResolverPlanSwitchOwner.State.Idle, owner.state.value)
        assertTrue(applying.isCompleted)
        owner.close()
    }

    @Test
    fun `failed commit keeps honest failure facts`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val port = FakePort { plan ->
            ResolverPlanSwitchResult.Failed(plan, FilterMutationFailure.COMMIT_FAILED)
        }
        val owner = ResolverPlanSwitchOwner(port, this, dispatcher)

        assertFalse(owner.apply(MilestoneResolverPlan.MULLVAD_ONLY))
        advanceUntilIdle()

        assertEquals(
            ResolverPlanSwitchOwner.State.Failed(
                MilestoneResolverPlan.MULLVAD_ONLY,
                ResolverPlanSwitchOwner.Failure.COMMIT,
            ),
            owner.state.value,
        )
        assertFalse(owner.reconstructionRequired.value)
        owner.close()
    }

    @Test
    fun `no change refusal keeps honest failure facts`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val port = FakePort { plan ->
            ResolverPlanSwitchResult.Rejected(plan, ResolverPlanSwitchRejection.NO_CHANGE)
        }
        val owner = ResolverPlanSwitchOwner(port, this, dispatcher)

        assertFalse(owner.apply(MilestoneResolverPlan.DEVICE_DNS_ONLY))
        advanceUntilIdle()

        assertEquals(
            ResolverPlanSwitchOwner.State.Failed(
                MilestoneResolverPlan.DEVICE_DNS_ONLY,
                ResolverPlanSwitchOwner.Failure.COMMIT,
            ),
            owner.state.value,
        )
        owner.close()
    }

    @Test
    fun `unavailable authority settles to idle and arms reconstruction for the host`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val port = FakePort { plan ->
            ResolverPlanSwitchResult.ProtectionUnavailable(plan)
        }
        val owner = ResolverPlanSwitchOwner(port, this, dispatcher)
        assertFalse(owner.reconstructionRequired.value)

        assertFalse(owner.apply(MilestoneResolverPlan.MULLVAD_ONLY))
        advanceUntilIdle()

        assertEquals(ResolverPlanSwitchOwner.State.Idle, owner.state.value)
        assertTrue(owner.reconstructionRequired.value)
        owner.close()
    }

    @Test
    fun `closed owner refuses apply and never reaches the port`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val port = FakePort { plan ->
            ResolverPlanSwitchResult.Applied(plan, generation = 2L)
        }
        val owner = ResolverPlanSwitchOwner(port, this, dispatcher)
        owner.close()

        assertFalse(owner.apply(MilestoneResolverPlan.DEVICE_DNS_ONLY))
        assertEquals(emptyList<MilestoneResolverPlan>(), port.requests)
    }

    @Test
    fun `an accepted dns tier ladder is evaluated by policy and committed through the port`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val port = FakePort { plan -> ResolverPlanSwitchResult.Applied(plan, generation = 2L) }
        val owner = ResolverPlanSwitchOwner(port, this, dispatcher)

        val outcome = owner.applyDnsEdit(
            request = SaveResolverTiers(
                listOf(
                    ResolverTierChoice("device-dns", "IP"),
                    ResolverTierChoice("mullvad", "DoH"),
                ),
            ),
            context = ResolverDnsEditContext(
                current = ResolverSelectionPresets.DEVICE_DNS_ONLY,
                limits = FeatureLimits.PLUS,
                chained = ChainedDnsEditState.editable,
            ),
        )
        advanceUntilIdle()

        assertTrue("expected Applied but was $outcome", outcome is ResolverDnsEditOutcome.Applied)
        assertEquals(listOf(MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK), port.requests)
        owner.close()
    }

    @Test
    fun `an unsupported provider is refused by policy and never reaches the port`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val port = FakePort { plan -> ResolverPlanSwitchResult.Applied(plan, generation = 2L) }
        val owner = ResolverPlanSwitchOwner(port, this, dispatcher)

        val outcome = owner.applyDnsEdit(
            request = SaveResolverTiers(listOf(ResolverTierChoice("mullvad-doh", "DoH"))),
            context = ResolverDnsEditContext(
                current = ResolverSelectionPresets.DEVICE_DNS_ONLY,
                limits = FeatureLimits.PLUS,
                chained = ChainedDnsEditState.editable,
            ),
        )
        advanceUntilIdle()

        assertEquals(
            ResolverDnsEditOutcome.Refused(ResolverEditRejection.UnknownProvider("mullvad-doh")),
            outcome,
        )
        assertEquals(emptyList<MilestoneResolverPlan>(), port.requests)
        owner.close()
    }

    @Test
    fun `the chained full-tunnel gate is enforced by the policy and refuses without committing`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val port = FakePort { plan -> ResolverPlanSwitchResult.Applied(plan, generation = 2L) }
        val owner = ResolverPlanSwitchOwner(port, this, dispatcher)
        val locked = ChainedDnsEditState(chainingEnabled = true, fallbackPreference = true, storedIsSplitTunnel = false)

        val outcome = owner.applyDnsEdit(
            request = SaveResolverTiers(listOf(ResolverTierChoice("device-dns", "IP"))),
            context = ResolverDnsEditContext(
                current = ResolverSelectionPresets.DEVICE_DNS_ONLY,
                limits = FeatureLimits.PLUS,
                chained = locked,
            ),
        )
        advanceUntilIdle()

        assertEquals(ResolverDnsEditOutcome.Refused(ResolverEditRejection.ChainedEditLocked), outcome)
        assertEquals(emptyList<MilestoneResolverPlan>(), port.requests)
        owner.close()
    }

    @Test
    fun `durable chained gate overrides a stale editable screen before dns admission`() = runTest {
        val port = FakePort { plan -> ResolverPlanSwitchResult.Applied(plan, generation = 2L) }
        val owner = ResolverPlanSwitchOwner(port, this, StandardTestDispatcher(testScheduler),
            loadChainedDnsEdit = { ChainedDnsEditState(true, true, false) })
        val outcome = owner.applyDnsEdit(
            SaveResolverTiers(listOf(ResolverTierChoice("mullvad", "DoH"))),
            ResolverDnsEditContext(ResolverSelectionPresets.DEVICE_DNS_ONLY, FeatureLimits.PLUS, ChainedDnsEditState.editable),
        )
        assertEquals(ResolverDnsEditOutcome.Refused(ResolverEditRejection.ChainedEditLocked), outcome)
        assertTrue(port.requests.isEmpty())
        owner.close()
    }

    @Test
    fun `failed durable chain read and retirement during that read never apply dns`() = runTest {
        val port = FakePort { plan -> ResolverPlanSwitchResult.Applied(plan, generation = 2L) }
        val context = ResolverDnsEditContext(ResolverSelectionPresets.DEVICE_DNS_ONLY, FeatureLimits.PLUS, ChainedDnsEditState.editable)
        val request = SaveResolverTiers(listOf(ResolverTierChoice("mullvad", "DoH")))
        val failing = ResolverPlanSwitchOwner(port, this, StandardTestDispatcher(testScheduler),
            loadChainedDnsEdit = { error("durable authority unavailable") })
        assertEquals(ResolverDnsEditOutcome.Unavailable, failing.applyDnsEdit(request, context))
        failing.close()

        val readStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val retiring = ResolverPlanSwitchOwner(port, this, StandardTestDispatcher(testScheduler),
            loadChainedDnsEdit = { readStarted.complete(Unit); release.await(); ChainedDnsEditState.editable })
        var outcome: ResolverDnsEditOutcome? = null
        val edit = launch { outcome = retiring.applyDnsEdit(request, context) }
        readStarted.await()
        retiring.close()
        release.complete(Unit)
        edit.join()
        assertEquals(ResolverDnsEditOutcome.Unavailable, outcome)
        assertTrue(port.requests.isEmpty())
    }

    @Test
    fun `a stale editor context is refused before any commit`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val port = FakePort { plan -> ResolverPlanSwitchResult.Applied(plan, generation = 2L) }
        val owner = ResolverPlanSwitchOwner(port, this, dispatcher)

        val outcome = owner.applyDnsEdit(
            request = SaveResolverTiers(
                listOf(ResolverTierChoice("mullvad", "DoH")),
                expectedEditorContext = "old-context",
            ),
            context = ResolverDnsEditContext(
                current = ResolverSelectionPresets.DEVICE_DNS_ONLY,
                limits = FeatureLimits.PLUS,
                chained = ChainedDnsEditState.editable,
                editorContext = "live-context",
            ),
        )
        advanceUntilIdle()

        assertEquals(ResolverDnsEditOutcome.Refused(ResolverEditRejection.StaleEditor), outcome)
        assertEquals(emptyList<MilestoneResolverPlan>(), port.requests)
        owner.close()
    }

    @Test
    fun `a valid custom tier commits through the port as the encrypted-only plan`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val port = FakePort { plan -> ResolverPlanSwitchResult.Applied(plan, generation = 2L) }
        val owner = ResolverPlanSwitchOwner(port, this, dispatcher)

        val outcome = owner.applyDnsEdit(
            request = SaveResolverTiers(
                listOf(
                    ResolverTierChoice(
                        "custom-dns",
                        "DoH",
                        primary = "https://dns.example/query",
                    ),
                ),
            ),
            context = ResolverDnsEditContext(
                current = ResolverSelectionPresets.DEVICE_DNS_ONLY,
                limits = FeatureLimits.PLUS,
                chained = ChainedDnsEditState.editable,
            ),
        )
        advanceUntilIdle()

        // The policy accepts a valid custom DoH selection and the value-carrying plan now commits
        // it through the port as the encrypted-only route shape.
        assertTrue("expected Applied but was $outcome", outcome is ResolverDnsEditOutcome.Applied)
        val commit = (outcome as ResolverDnsEditOutcome.Applied).commit
        assertEquals(ResolverProviderId.CUSTOM, commit?.selection?.primary?.providerId)
        assertEquals(CatalogResolverTransport.DOH, commit?.selection?.primary?.transport)
        assertEquals("https://dns.example/query", commit?.selection?.primary?.customDohUrl)
        assertEquals(listOf(MilestoneResolverPlan.MULLVAD_ONLY), port.requests)
        owner.close()
    }

    @Test
    fun `an invalid custom DoH endpoint is refused by policy and never reaches the port`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val port = FakePort { plan -> ResolverPlanSwitchResult.Applied(plan, generation = 2L) }
        val owner = ResolverPlanSwitchOwner(port, this, dispatcher)

        val outcome = owner.applyDnsEdit(
            request = SaveResolverTiers(
                listOf(ResolverTierChoice("custom-dns", "DoH", primary = "not-a-url")),
            ),
            context = ResolverDnsEditContext(
                current = ResolverSelectionPresets.DEVICE_DNS_ONLY,
                limits = FeatureLimits.PLUS,
                chained = ChainedDnsEditState.editable,
            ),
        )
        advanceUntilIdle()

        assertTrue(
            "expected a custom-invalid refusal but was $outcome",
            outcome is ResolverDnsEditOutcome.Refused &&
                (outcome as ResolverDnsEditOutcome.Refused).rejection is ResolverEditRejection.CustomInvalid,
        )
        assertEquals(emptyList<MilestoneResolverPlan>(), port.requests)
        owner.close()
    }

    @Test
    fun `evaluateDnsEdit is pure and does not touch the port`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val port = FakePort { plan -> ResolverPlanSwitchResult.Applied(plan, generation = 2L) }
        val owner = ResolverPlanSwitchOwner(port, this, dispatcher)

        val evaluated = owner.evaluateDnsEdit(
            request = SaveResolverTiers(listOf(ResolverTierChoice("mullvad", "DoH"))),
            context = ResolverDnsEditContext(
                current = ResolverSelectionPresets.DEVICE_DNS_ONLY,
                limits = FeatureLimits.PLUS,
                chained = ChainedDnsEditState.editable,
            ),
        )
        advanceUntilIdle()

        assertTrue(evaluated is ResolverEditResult.Accepted)
        assertEquals(emptyList<MilestoneResolverPlan>(), port.requests)
        owner.close()
    }

    private class FakePort(
        private val behavior: suspend (MilestoneResolverPlan) -> ResolverPlanSwitchResult,
    ) : ResolverPlanSwitchPort {
        val requests = mutableListOf<MilestoneResolverPlan>()

        override suspend fun apply(plan: FilterResolverPlan): ResolverPlanSwitchResult {
            requests += plan.plan
            return behavior(plan.plan)
        }
    }
}
