package com.lavasec.app

import android.content.Intent
import com.lavasec.app.foundation.filter.FilterCatalogSnapshot
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.CatalogFilterSource
import com.lavasec.app.foundation.filter.FilterSourceCategory
import com.lavasec.app.foundation.filter.ManualRuleRejection
import com.lavasec.app.foundation.filter.ManualRuleTarget
import com.lavasec.app.product.FilterLibraryEditOutcome
import com.lavasec.app.product.FilterLibraryOwner
import com.lavasec.app.product.FilterLibraryPort
import com.lavasec.app.product.FilterLibraryMutationResult
import com.lavasec.app.foundation.config.AppConfiguration
import com.lavasec.app.foundation.onboarding.OnboardingPhase
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.foundation.resolver.FilterResolverPlan
import com.lavasec.app.foundation.resolver.CustomResolverDraft
import com.lavasec.app.foundation.resolver.CatalogResolverTransport
import com.lavasec.app.foundation.resolver.ResolverProviderId
import com.lavasec.app.foundation.resolver.ResolverSelectionEntry
import com.lavasec.app.foundation.resolver.ResolverSelection
import com.lavasec.app.foundation.resolver.ResolverSelectionPresets
import com.lavasec.app.foundation.resolver.ResolverTier
import com.lavasec.app.foundation.resolver.ResolverTierChoice
import com.lavasec.app.foundation.resolver.toFilterResolverPlan
import com.lavasec.app.foundation.resolver.toSelection
import com.lavasec.app.onboarding.OnboardingOwner
import com.lavasec.app.platform.VpnPreparation
import com.lavasec.app.platform.VpnPreparationResult
import com.lavasec.app.presentation.ProductNavigation
import com.lavasec.app.presentation.ProductDestination
import com.lavasec.app.presentation.filters.FilterLibraryState
import com.lavasec.app.presentation.filters.FilterRowUi

import com.lavasec.app.presentation.filters.FilterProfileSwitchFailureUi
import com.lavasec.app.presentation.filters.FilterProfileSwitchUiState
import com.lavasec.app.presentation.settings.ResolverPlanSwitchUiState
import com.lavasec.app.product.DomainActionOwner
import com.lavasec.app.product.activity.ActivityOwner
import com.lavasec.app.product.privacy.PrivacyMutationResult
import com.lavasec.app.product.privacy.PrivacyOwner
import java.util.concurrent.atomic.AtomicInteger
import java.time.ZoneId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import com.lavasec.app.foundation.security.ProtectedSurface
import com.lavasec.app.presentation.GuardPrimaryAction
import com.lavasec.app.platform.ProtectionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
class MainProductViewModelTest {
    @Test fun `native custom host destruction drops original DNS session without persisting or requiring a live host`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val writes = mutableListOf<FilterResolverPlan>()
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(
            generation = 301, completed = true, dispatcher = dispatcher,
            resolverPlan = MilestoneResolverPlan.DEVICE_DNS_ONLY,
            resolverSwitchPort = com.lavasec.app.product.ResolverPlanSwitchPort { plan ->
                writes += plan; com.lavasec.app.product.ResolverPlanSwitchResult.Applied(plan.plan, 2)
            }, featureLimits = MutableStateFlow(com.lavasec.app.foundation.subscription.FeatureLimits.PLUS)))
        val vm = MainProductViewModel(factories, { _ -> }, dispatcher)
        try {
            advanceUntilIdle()
            val initial = vm.state.value.resolverSelection
            val choice = ResolverTierChoice("custom-dns", "DoT", "Uncommitted name", "tls://first.example:853", "")
            val opened = vm.openCustomDnsDraft(choice, 301) as com.lavasec.app.product.ResolverDnsEditOutcome.DraftOpened
            val activity = Any(); var alive = true
            val host = com.lavasec.app.reactnative.NativeVisitHost(activity, "a") { alive }
            val registry = com.lavasec.app.reactnative.NativeVisitHostRegistry(); assertTrue(registry.publish(host))
            val custody = com.lavasec.app.reactnative.NativeCustomEntryHostOwner({ vm.state.value.graphGeneration }, registry::current)
            assertTrue(custody.register(opened.token, 301, host) { assertTrue(vm.dismissCustomEntry(opened.token, 301)) })
            assertEquals(opened.token, vm.dnsCustomDraftToken.value)
            alive = false; assertTrue(registry.retire(activity, "a"))
            custody.retireHost(activity, "a")
            assertNull(vm.dnsCustomDraftToken.value); assertNull(vm.dnsCustomDraftSession.value)
            assertEquals(initial, vm.state.value.resolverSelection); assertEquals(CustomResolverDraft(), vm.dnsCustomSavedDraft.value)
            assertTrue(writes.isEmpty())
            custody.retireHost(activity, "a"); custody.close()
            val fresh = vm.openCustomDnsDraft(null, 301) as com.lavasec.app.product.ResolverDnsEditOutcome.DraftOpened
            assertFalse(opened.token == fresh.token); assertEquals(fresh.token, vm.dnsCustomDraftToken.value)
            assertTrue(vm.dismissCustomEntry(fresh.token, 301))
        } finally { vm.close(); advanceUntilIdle() }
    }

    @Test fun `late original custom host retirement cannot dismiss fresh token or newer credential graph`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val writes = mutableListOf<FilterResolverPlan>()
        fun factory(generation: Long) = FakeOwnerFactory(
            generation = generation, completed = true, dispatcher = dispatcher,
            resolverPlan = MilestoneResolverPlan.DEVICE_DNS_ONLY,
            resolverSwitchPort = com.lavasec.app.product.ResolverPlanSwitchPort { plan ->
                writes += plan; com.lavasec.app.product.ResolverPlanSwitchResult.Applied(plan.plan, 2)
            }, featureLimits = MutableStateFlow(com.lavasec.app.foundation.subscription.FeatureLimits.PLUS))
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(factory(302))
        val vm = MainProductViewModel(factories, { _ -> }, dispatcher)
        try {
            advanceUntilIdle()
            val opened = vm.openCustomDnsDraft(null, 302) as com.lavasec.app.product.ResolverDnsEditOutcome.DraftOpened
            val a = Any(); val hostA = com.lavasec.app.reactnative.NativeVisitHost(a, "a") { true }
            val hostB = com.lavasec.app.reactnative.NativeVisitHost(Any(), "b") { true }; var current = hostA
            val custody = com.lavasec.app.reactnative.NativeCustomEntryHostOwner({ vm.state.value.graphGeneration }, { current })
            var originalRetirementCalls = 0
            assertTrue(custody.register(opened.token, 302, hostA) {
                originalRetirementCalls++
                assertFalse(vm.dismissCustomEntry(opened.token, 302))
            })
            val sameGraph = vm.openCustomDnsDraft(null, 302) as com.lavasec.app.product.ResolverDnsEditOutcome.DraftOpened
            current = hostB; custody.retireHost(a, "a")
            assertEquals("The original host callback must actually have run", 1, originalRetirementCalls)
            assertFalse(opened.token == sameGraph.token); assertEquals(sameGraph.token, vm.dnsCustomDraftToken.value)
            assertFalse(vm.dismissCustomEntry(sameGraph.token, 301))
            factories.value = factory(303); advanceUntilIdle()
            val successor = vm.openCustomDnsDraft(null, 303) as com.lavasec.app.product.ResolverDnsEditOutcome.DraftOpened
            assertEquals(com.lavasec.app.product.ResolverDnsEditOutcome.Unavailable, vm.openCustomDnsDraft(null, 302))
            assertFalse(vm.dismissCustomEntry(successor.token, 302)); assertFalse(vm.dismissCustomEntry(opened.token, 302))
            assertEquals(successor.token, vm.dnsCustomDraftToken.value)
            assertTrue(vm.dismissCustomEntry(successor.token, 303)); assertTrue(writes.isEmpty())
            custody.close()
            assertEquals(1, originalRetirementCalls)
        } finally { vm.close(); advanceUntilIdle() }
    }

    @Test
    fun `staging raw stamp choice is token fenced and only outer tier save commits its exact slot`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        for (stamp in DnsStampFixture.frozen) for (index in 0..1) {
            val first = ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN, true, "Saved first name")
            val second = ResolverTier(ResolverSelectionEntry(ResolverProviderId.QUAD9_UNFILTERED, CatalogResolverTransport.DOT),
                false, "Saved second name")
            val initial = ResolverSelection(listOf(first, second))
            val resolver = MutableStateFlow<FilterResolverPlan?>(initial.toFilterResolverPlan()!!)
            val writes = mutableListOf<FilterResolverPlan>()
            val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(
                generation = 97, completed = true, dispatcher = dispatcher, resolverValue = resolver,
                resolverSwitchPort = com.lavasec.app.product.ResolverPlanSwitchPort { plan ->
                    writes += plan;resolver.value = plan
                    com.lavasec.app.product.ResolverPlanSwitchResult.Applied(plan.plan, 2)
                }, featureLimits = MutableStateFlow(com.lavasec.app.foundation.subscription.FeatureLimits.PLUS)))
            val vm = MainProductViewModel(factories, { _ -> }, dispatcher)
            try {
                advanceUntilIdle()
                val opened = vm.openCustomDnsDraft(null) as com.lavasec.app.product.ResolverDnsEditOutcome.DraftOpened
                val context = com.lavasec.app.product.ResolverDnsContext.encodeCustom(initial, vm.dnsCustomSavedDraft.value)
                assertEquals(com.lavasec.app.product.ResolverDnsEditOutcome.Refused(com.lavasec.app.foundation.resolver.ResolverEditRejection.StaleEditor),
                    vm.stageCustomDns("wrong-token", context, "", stamp.primary, stamp.secondary, 97))
                val staged = vm.stageCustomDns(opened.token, context, "", stamp.primary, stamp.secondary, 97)
                    as com.lavasec.app.product.ResolverDnsEditOutcome.DraftStaged
                assertEquals("", staged.choice.name)
                assertEquals(stamp.primary, staged.choice.primary)
                assertEquals(stamp.secondary, staged.choice.secondary)
                assertTrue(writes.isEmpty())
                assertEquals(initial, resolver.value!!.toSelection())
                assertEquals(CustomResolverDraft(), vm.dnsCustomSavedDraft.value)
                // Closing the custom form tears down its token; the validated choice remains a
                // proposal in the parent editor until the full ordered request is committed.
                assertTrue(vm.dismissCustomEntry(opened.token))
                assertEquals(com.lavasec.app.product.ResolverDnsEditOutcome.Refused(com.lavasec.app.foundation.resolver.ResolverEditRejection.StaleEditor),
                    vm.stageCustomDns(opened.token, context, "", stamp.primary, stamp.secondary, 97))
                val tiers = initial.tiers.mapIndexed { slot, row ->
                    if (slot == index) staged.choice.copy(isEnabled = row.isEnabled) else choiceFor(row)
                }
                assertTrue(vm.applyDnsTiers(tiers, com.lavasec.app.product.ResolverDnsContext.encode(initial), 97) is
                    com.lavasec.app.product.ResolverDnsEditOutcome.Applied)
                assertEquals(initial.tiers[1 - index], writes.single().toSelection().tiers[1 - index])
                val entry = writes.single().toSelection().tiers[index].entry
                assertEquals(stamp.entry().copy(customName = null), entry)
            } finally { vm.close();advanceUntilIdle() }
        }
    }

    @Test
    fun `staging refuses source delta and cancelled draft without selecting a resolver`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val initial = ResolverSelectionPresets.DEVICE_DNS_ONLY
        val resolver = MutableStateFlow<FilterResolverPlan?>(initial.toFilterResolverPlan()!!)
        val writes = mutableListOf<FilterResolverPlan>()
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(
            generation = 98, completed = true, dispatcher = dispatcher, resolverValue = resolver,
            resolverSwitchPort = com.lavasec.app.product.ResolverPlanSwitchPort { plan ->
                writes += plan;com.lavasec.app.product.ResolverPlanSwitchResult.Applied(plan.plan, 2)
            }, featureLimits = MutableStateFlow(com.lavasec.app.foundation.subscription.FeatureLimits.PLUS)))
        val vm = MainProductViewModel(factories, { _ -> }, dispatcher)
        val stale = com.lavasec.app.product.ResolverDnsEditOutcome.Refused(com.lavasec.app.foundation.resolver.ResolverEditRejection.StaleEditor)
        try {
            advanceUntilIdle()
            val opened = vm.openCustomDnsDraft(null) as com.lavasec.app.product.ResolverDnsEditOutcome.DraftOpened
            val context = com.lavasec.app.product.ResolverDnsContext.encodeCustom(initial, CustomResolverDraft())
            resolver.value = ResolverSelectionPresets.MULLVAD_ONLY.toFilterResolverPlan()!!
            assertEquals(stale, vm.stageCustomDns(opened.token, context, "", "https://dns.example/query", "", 98))
            advanceUntilIdle()
            val next = vm.openCustomDnsDraft(null) as com.lavasec.app.product.ResolverDnsEditOutcome.DraftOpened
            vm.dismissCustomEntry(next.token)
            assertEquals(stale, vm.stageCustomDns(next.token, context, "", "https://dns.example/query", "", 98))
            assertTrue(writes.isEmpty())
            assertEquals(ResolverSelectionPresets.MULLVAD_ONLY, resolver.value!!.toSelection())
        } finally { vm.close();advanceUntilIdle() }
    }

    @Test
    fun `staging completion after suspended admission cannot adopt a new token or source`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        for (changeSource in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>();val release = CompletableDeferred<Unit>()
            val resolver = MutableStateFlow<FilterResolverPlan?>(ResolverSelectionPresets.DEVICE_DNS_ONLY.toFilterResolverPlan()!!)
            val writes = mutableListOf<FilterResolverPlan>()
            val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(
                generation = 99, completed = true, dispatcher = dispatcher, resolverValue = resolver,
                resolverFreshGate = { entered.complete(Unit);release.await();com.lavasec.app.foundation.resolver.ChainedDnsEditState.editable },
                resolverSwitchPort = com.lavasec.app.product.ResolverPlanSwitchPort { plan ->
                    writes += plan;com.lavasec.app.product.ResolverPlanSwitchResult.Applied(plan.plan, 2)
                }, featureLimits = MutableStateFlow(com.lavasec.app.foundation.subscription.FeatureLimits.PLUS)))
            val vm = MainProductViewModel(factories, { _ -> }, dispatcher)
            try {
                advanceUntilIdle()
                val opened = vm.openCustomDnsDraft(null) as com.lavasec.app.product.ResolverDnsEditOutcome.DraftOpened
                val context = com.lavasec.app.product.ResolverDnsContext.encodeCustom(resolver.value!!.toSelection(), CustomResolverDraft())
                val staging = async { vm.stageCustomDns(opened.token, context, "", "doq://dns.example:853", "", 99) }
                entered.await()
                if (changeSource) resolver.value = ResolverSelectionPresets.MULLVAD_ONLY.toFilterResolverPlan()!!
                else {vm.dismissCustomEntry(opened.token);vm.openCustomDnsDraft(null)}
                release.complete(Unit)
                assertEquals(com.lavasec.app.product.ResolverDnsEditOutcome.Refused(com.lavasec.app.foundation.resolver.ResolverEditRejection.StaleEditor), staging.await())
                assertTrue(writes.isEmpty())
            } finally { vm.close();advanceUntilIdle() }
        }
    }

    @Test
    fun `editing raw stamp row preserves the other retained row and reopens after graph recreation`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        for (stamp in DnsStampFixture.frozen) for (editingIndex in 0..1) {
            val custom = ResolverTier(stamp.entry(), isEnabled = editingIndex == 0, retainedName = "Old editor label")
            val other = ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN,
                isEnabled = editingIndex == 1, retainedName = "Retained device name")
            val initial = ResolverSelection(if (editingIndex == 0) listOf(custom, other) else listOf(other, custom))
            val resolver = MutableStateFlow<FilterResolverPlan?>(initial.toFilterResolverPlan()!!)
            val writes = mutableListOf<FilterResolverPlan>()
            val port = com.lavasec.app.product.ResolverPlanSwitchPort { plan ->
                writes += plan
                resolver.value = plan
                com.lavasec.app.product.ResolverPlanSwitchResult.Applied(plan.plan, 2)
            }
            fun factories(generation: Long) = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(
                generation = generation, completed = true, dispatcher = dispatcher, resolverValue = resolver,
                resolverSwitchPort = port,
                featureLimits = MutableStateFlow(com.lavasec.app.foundation.subscription.FeatureLimits.PLUS)))
            val vm = MainProductViewModel(factories(91), { _ -> }, dispatcher)
            try {
                advanceUntilIdle()
                assertTrue(vm.openCustomDnsDraft(choiceFor(custom)) is com.lavasec.app.product.ResolverDnsEditOutcome.DraftOpened)
                val context = com.lavasec.app.product.ResolverDnsContext.encodeCustom(initial, vm.dnsCustomSavedDraft.value)
                val outcome = vm.saveCustomDns(context, "Edited stamp", stamp.secondary, stamp.primary, 91)
                assertTrue("${stamp.transport} at $editingIndex: $outcome", outcome is com.lavasec.app.product.ResolverDnsEditOutcome.Applied)
                advanceUntilIdle()
                val committed = writes.single().toSelection()
                assertEquals(other, committed.tiers[1 - editingIndex])
                assertEquals(custom.isEnabled, committed.tiers[editingIndex].isEnabled)
                assertEquals(stamp.entry(primary = stamp.secondary, secondary = stamp.primary).copy(customName = "Edited stamp"),
                    committed.tiers[editingIndex].entry)
                assertNull(committed.tiers[editingIndex].retainedName)
                assertNull(vm.dnsCustomDraftToken.value)
            } finally { vm.close(); advanceUntilIdle() }

            val recreated = MainProductViewModel(factories(92), { _ -> }, dispatcher)
            try {
                advanceUntilIdle()
                assertTrue(recreated.openCustomDnsDraft(choiceFor(resolver.value!!.toSelection().tiers[editingIndex])) is
                    com.lavasec.app.product.ResolverDnsEditOutcome.DraftOpened)
                val expected = CustomResolverDraft("Edited stamp", stamp.secondary, stamp.primary)
                assertEquals(expected, recreated.dnsCustomDraftSession.value!!.saved)
                assertEquals(expected, recreated.dnsCustomDraftSession.value!!.draft)
                assertFalse(recreated.dnsCustomDraftSession.value!!.isDirty)
                assertEquals(other, recreated.state.value.resolverSelection!!.tiers[1 - editingIndex])
            } finally { recreated.close(); advanceUntilIdle() }
        }
    }

    @Test
    fun `editing the second custom resolver keeps first custom raw identity and both retained names`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = ResolverTier(DnsStampFixture.frozen.first { it.transport == CatalogResolverTransport.DOH }.entry(),
            retainedName = "Separate retained row name")
        val second = ResolverTier(DnsStampFixture.frozen.first { it.transport == CatalogResolverTransport.DOQ }.entry())
        val initial = ResolverSelection(listOf(first, second))
        val resolver = MutableStateFlow<FilterResolverPlan?>(initial.toFilterResolverPlan()!!)
        val writes = mutableListOf<FilterResolverPlan>()
        val port = com.lavasec.app.product.ResolverPlanSwitchPort { plan ->
            writes += plan; resolver.value = plan
            com.lavasec.app.product.ResolverPlanSwitchResult.Applied(plan.plan, 2)
        }
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(
            generation = 93, completed = true, dispatcher = dispatcher, resolverValue = resolver,
            resolverSwitchPort = port,
            featureLimits = MutableStateFlow(com.lavasec.app.foundation.subscription.FeatureLimits.PLUS)))
        val vm = MainProductViewModel(factories, { _ -> }, dispatcher)
        try {
            advanceUntilIdle()
            vm.openCustomDnsDraft(choiceFor(second))
            val context = com.lavasec.app.product.ResolverDnsContext.encodeCustom(initial, vm.dnsCustomSavedDraft.value)
            assertTrue(vm.saveCustomDns(context, "Edited second", "doq://second.example:8853", "doq://backup.example:8853", 93) is
                com.lavasec.app.product.ResolverDnsEditOutcome.Applied)
            advanceUntilIdle()
            val selection = writes.single().toSelection()
            assertEquals(first, selection.tiers[0])
            assertEquals("Stamp fixture", selection.tiers[0].entry.customName)
            assertEquals("Separate retained row name", selection.tiers[0].retainedName)
            assertEquals("Edited second", selection.tiers[1].entry.customName)
            vm.openCustomDnsDraft(choiceFor(selection.tiers[1]))
            assertEquals(CustomResolverDraft("Edited second", "doq://second.example:8853", "doq://backup.example:8853"),
                vm.dnsCustomDraftSession.value!!.saved)
        } finally { vm.close(); advanceUntilIdle() }
    }

    @Test
    fun `refused custom row edit keeps dirty draft and original ladder for a valid retry`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val stamp = DnsStampFixture.frozen.first { it.transport == CatalogResolverTransport.DOT }
        val initial = ResolverSelection(listOf(ResolverTier(stamp.entry()),
            ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN, false, "Disabled device")))
        val resolver = MutableStateFlow<FilterResolverPlan?>(initial.toFilterResolverPlan()!!)
        val writes = mutableListOf<FilterResolverPlan>()
        val port = com.lavasec.app.product.ResolverPlanSwitchPort { plan ->
            writes += plan; resolver.value = plan
            com.lavasec.app.product.ResolverPlanSwitchResult.Applied(plan.plan, 2)
        }
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(
            generation = 94, completed = true, dispatcher = dispatcher, resolverValue = resolver,
            resolverSwitchPort = port,
            featureLimits = MutableStateFlow(com.lavasec.app.foundation.subscription.FeatureLimits.PLUS)))
        val vm = MainProductViewModel(factories, { _ -> }, dispatcher)
        try {
            advanceUntilIdle()
            val opened = vm.openCustomDnsDraft(null) as com.lavasec.app.product.ResolverDnsEditOutcome.DraftOpened
            val saved = vm.dnsCustomSavedDraft.value
            val context = com.lavasec.app.product.ResolverDnsContext.encodeCustom(initial, saved)
            val refused = vm.saveCustomDns(context, "Changed name", "sdns://invalid", stamp.secondary, 94)
            assertTrue(refused is com.lavasec.app.product.ResolverDnsEditOutcome.Refused)
            assertTrue(writes.isEmpty())
            assertEquals(initial, resolver.value!!.toSelection())
            assertEquals(saved, vm.dnsCustomSavedDraft.value)
            assertEquals(saved, vm.dnsCustomDraftSession.value!!.saved)
            assertTrue(vm.dnsCustomDraftSession.value!!.hasUnsavedDraft)
            assertEquals("sdns://invalid", vm.dnsCustomDraftSession.value!!.draft.primaryAddress)
            assertEquals(opened.token, vm.dnsCustomDraftToken.value)
            assertTrue(vm.saveCustomDns(context, "Changed name", stamp.primary, stamp.secondary, 94) is
                com.lavasec.app.product.ResolverDnsEditOutcome.Applied)
            assertEquals(initial.tiers[1], writes.single().toSelection().tiers[1])
        } finally { vm.close(); advanceUntilIdle() }
    }

    @Test
    fun `source change before collector and unmatched saved custom choice both refuse stale edits`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val stamp = DnsStampFixture.frozen.first { it.transport == CatalogResolverTransport.DOH }
        val initial = ResolverSelection(listOf(ResolverTier(stamp.entry()),
            ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN, false, "Old device name")))
        val changed = initial.copy(tiers = listOf(initial.tiers[0], initial.tiers[1].copy(retainedName = "New device name")))
        val resolver = MutableStateFlow<FilterResolverPlan?>(initial.toFilterResolverPlan()!!)
        val writes = mutableListOf<FilterResolverPlan>()
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(
            generation = 95, completed = true, dispatcher = dispatcher, resolverValue = resolver,
            resolverSwitchPort = com.lavasec.app.product.ResolverPlanSwitchPort { plan ->
                writes += plan; com.lavasec.app.product.ResolverPlanSwitchResult.Applied(plan.plan, 2)
            }, featureLimits = MutableStateFlow(com.lavasec.app.foundation.subscription.FeatureLimits.PLUS)))
        val vm = MainProductViewModel(factories, { _ -> }, dispatcher)
        val stale = com.lavasec.app.product.ResolverDnsEditOutcome.Refused(
            com.lavasec.app.foundation.resolver.ResolverEditRejection.StaleEditor)
        try {
            advanceUntilIdle()
            vm.openCustomDnsDraft(null)
            resolver.value = changed.toFilterResolverPlan()!!
            // Even a caller supplying the newly published context cannot adopt the old draft.
            val newContext = com.lavasec.app.product.ResolverDnsContext.encodeCustom(changed, vm.dnsCustomSavedDraft.value)
            assertEquals(stale, vm.saveCustomDns(newContext, "Edited", stamp.primary, stamp.secondary, 95))
            assertTrue(writes.isEmpty())
            advanceUntilIdle()
            assertNull(vm.dnsCustomDraftSession.value)
            vm.openCustomDnsDraft(ResolverTierChoice("custom-dns", "DoH", "Unrelated", "https://other.example/dns-query"))
            assertEquals(stale, vm.saveCustomDns(newContext, "Unrelated", "https://other.example/dns-query", "", 95))
            assertTrue(writes.isEmpty())
            assertEquals(changed, resolver.value!!.toSelection())
        } finally { vm.close(); advanceUntilIdle() }
    }

    private fun choiceFor(tier: ResolverTier) = ResolverTierChoice(
        providerId = tier.entry.providerId.rawId, transportToken = tier.entry.transport.token,
        name = tier.retainedName ?: tier.entry.customName ?: "",
        primary = tier.entry.customDohUrl ?: tier.entry.customDotEndpoint ?: tier.entry.customDoqEndpoint ?: tier.entry.customPlainEndpoint ?: "",
        secondary = tier.entry.customSecondaryEndpoint ?: "", isEnabled = tier.isEnabled)

    @Test
    fun `same endpoint authoritative name change rehydrates while unrelated updates keep dirty local name`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val stamp = DnsStampFixture.frozen.first { it.transport == CatalogResolverTransport.DOH }
        val initial = ResolverSelection(listOf(ResolverTier(stamp.entry()),
            ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN, false, "Saved device")))
        val resolver = MutableStateFlow<FilterResolverPlan?>(initial.toFilterResolverPlan()!!)
        val limits = MutableStateFlow(com.lavasec.app.foundation.subscription.FeatureLimits.PLUS)
        val writes = mutableListOf<FilterResolverPlan>()
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(
            generation = 96, completed = true, dispatcher = dispatcher, resolverValue = resolver,
            resolverSwitchPort = com.lavasec.app.product.ResolverPlanSwitchPort { plan ->
                writes += plan; com.lavasec.app.product.ResolverPlanSwitchResult.Applied(plan.plan, 2)
            }, featureLimits = limits))
        val vm = MainProductViewModel(factories, { _ -> }, dispatcher)
        try {
            advanceUntilIdle()
            val opened = vm.openCustomDnsDraft(null) as com.lavasec.app.product.ResolverDnsEditOutcome.DraftOpened
            val context = com.lavasec.app.product.ResolverDnsContext.encodeCustom(initial, vm.dnsCustomSavedDraft.value)
            assertTrue(vm.saveCustomDns(context, "Intentional local name", "sdns://invalid", stamp.secondary, 96) is
                com.lavasec.app.product.ResolverDnsEditOutcome.Refused)
            limits.value = limits.value.copy(maxFilters = 51)
            advanceUntilIdle()
            assertEquals("Intentional local name", vm.dnsCustomDraftSession.value!!.draft.name)
            assertTrue(vm.dnsCustomDraftSession.value!!.isDirty)
            assertEquals(opened.token, vm.dnsCustomDraftToken.value)
            assertEquals("Stamp fixture", vm.dnsCustomSavedDraft.value.name)

            val changed = initial.copy(tiers = listOf(initial.tiers[0].copy(
                entry = initial.tiers[0].entry.copy(customName = "Durable new name")), initial.tiers[1]))
            resolver.value = changed.toFilterResolverPlan()!!
            advanceUntilIdle()
            assertNull(vm.dnsCustomDraftToken.value)
            assertNull(vm.dnsCustomDraftSession.value)
            assertEquals(CustomResolverDraft("Durable new name", stamp.primary, stamp.secondary), vm.dnsCustomSavedDraft.value)
            vm.openCustomDnsDraft(null)
            assertEquals(vm.dnsCustomSavedDraft.value, vm.dnsCustomDraftSession.value!!.draft)

            resolver.value = changed.copy(tiers = listOf(changed.tiers[0].copy(
                entry = changed.tiers[0].entry.copy(customName = null)), changed.tiers[1])).toFilterResolverPlan()!!
            advanceUntilIdle()
            assertEquals(CustomResolverDraft("", stamp.primary, stamp.secondary), vm.dnsCustomSavedDraft.value)
            assertTrue(writes.isEmpty())
        } finally { vm.close(); advanceUntilIdle() }
    }

    @Test
    fun `new graph reopens persisted custom plain endpoint and secondary from retained row`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val selection = com.lavasec.app.foundation.resolver.ResolverSelection(listOf(
            com.lavasec.app.foundation.resolver.ResolverTier(com.lavasec.app.foundation.resolver.ResolverSelectionEntry(
                com.lavasec.app.foundation.resolver.ResolverProviderId.CUSTOM,
                com.lavasec.app.foundation.resolver.CatalogResolverTransport.PLAIN_DNS,
                customPlainEndpoint = "192.168.1.53", customName = "Office", customSecondaryEndpoint = "192.168.1.54"))))
        val plan = selection.toFilterResolverPlan()!!
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(generation = 81,
            completed = true, dispatcher = dispatcher, resolverValue = MutableStateFlow<FilterResolverPlan?>(plan),
            featureLimits = MutableStateFlow(com.lavasec.app.foundation.subscription.FeatureLimits.PLUS)))
        val vm = MainProductViewModel(factories, { _ -> }, dispatcher)
        try {
            advanceUntilIdle()
            val expected = CustomResolverDraft("Office", "192.168.1.53", "192.168.1.54")
            assertEquals(expected, vm.dnsCustomSavedDraft.value)
            assertTrue(vm.openCustomDnsDraft(null) is com.lavasec.app.product.ResolverDnsEditOutcome.DraftOpened)
            assertEquals(expected, vm.dnsCustomDraftSession.value!!.draft)
        } finally { vm.close(); advanceUntilIdle() }
    }

    @Test
    fun `new product graph rehydrates exact persisted custom name primary and secondary`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val plans = listOf(
            FilterResolverPlan(
                MilestoneResolverPlan.MULLVAD_ONLY,
                customDohUrl = "https://dns.example:8443/query",
                customName = "Home DNS",
                customSecondaryEndpoint = "https://dns2.example:8443/query",
            ),
            FilterResolverPlan(
                MilestoneResolverPlan.MULLVAD_ONLY,
                customDotEndpoint = "tls://dns.example:8853",
                customName = "Office DNS",
                customSecondaryEndpoint = "tls://dns2.example:8853",
            ),
        )
        for (plan in plans) {
            val resolver = MutableStateFlow<FilterResolverPlan?>(plan)
            val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(
                generation = 81, completed = true, dispatcher = dispatcher, resolverValue = resolver,
                featureLimits = MutableStateFlow(com.lavasec.app.foundation.subscription.FeatureLimits.PLUS),
            ))
            repeat(2) {
                val viewModel = MainProductViewModel(factories, { _ -> }, dispatcher)
                advanceUntilIdle()
                val expected = CustomResolverDraft(
                    plan.customName ?: "",
                    plan.customDohUrl ?: plan.customDotEndpoint!!,
                    plan.customSecondaryEndpoint ?: "",
                )
                assertEquals(expected, viewModel.dnsCustomSavedDraft.value)
                assertTrue(viewModel.openCustomDnsDraft(null) is com.lavasec.app.product.ResolverDnsEditOutcome.DraftOpened)
                assertEquals(expected, viewModel.dnsCustomDraftSession.value?.draft)
                viewModel.close()
                advanceUntilIdle()
            }
        }
    }

    @Test
    fun `explicit picker choice seeds name primary and secondary without changing confirmed resolver`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(
            generation = 82, completed = true, dispatcher = dispatcher, resolverPlan = MilestoneResolverPlan.DEVICE_DNS_ONLY,
            featureLimits = MutableStateFlow(com.lavasec.app.foundation.subscription.FeatureLimits.PLUS),
        ))
        val viewModel = MainProductViewModel(factories, { _ -> }, dispatcher)
        advanceUntilIdle()
        val choice = ResolverTierChoice("custom-dns", "DoT", "Office DNS", "tls://first.example:853", "tls://second.example:8853")
        assertTrue(viewModel.openCustomDnsDraft(choice) is com.lavasec.app.product.ResolverDnsEditOutcome.DraftOpened)
        assertEquals(CustomResolverDraft(choice.name, choice.primary, choice.secondary), viewModel.dnsCustomDraftSession.value?.draft)
        assertEquals(MilestoneResolverPlan.DEVICE_DNS_ONLY.toSelection(), viewModel.state.value.resolverSelection)
        assertEquals(CustomResolverDraft(), viewModel.dnsCustomSavedDraft.value)
        viewModel.close()
    }

    @Test
    fun `same graph retains accepted name and graph change drops it and old editor token`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val resolver = MutableStateFlow<FilterResolverPlan?>(FilterResolverPlan(MilestoneResolverPlan.DEVICE_DNS_ONLY))
        val port = com.lavasec.app.product.ResolverPlanSwitchPort { plan ->
            resolver.value = plan
            com.lavasec.app.product.ResolverPlanSwitchResult.Applied(plan.plan, 2)
        }
        fun factory(generation: Long) = FakeOwnerFactory(
            generation = generation, completed = true, dispatcher = dispatcher, resolverValue = resolver, resolverSwitchPort = port,
            featureLimits = MutableStateFlow(com.lavasec.app.foundation.subscription.FeatureLimits.PLUS),
        )
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(factory(83))
        val viewModel = MainProductViewModel(factories, { _ -> }, dispatcher)
        advanceUntilIdle()
        viewModel.openCustomDnsDraft(null)
        val outcome = viewModel.saveCustomDns(
            com.lavasec.app.product.ResolverDnsContext.encodeCustom(resolver.value!!.toSelection(), CustomResolverDraft()),
            "Office DNS", "tls://first.example:853", "", 83,
        )
        assertTrue(outcome is com.lavasec.app.product.ResolverDnsEditOutcome.Applied)
        advanceUntilIdle()
        val opened = viewModel.openCustomDnsDraft(null) as com.lavasec.app.product.ResolverDnsEditOutcome.DraftOpened
        assertEquals(CustomResolverDraft("Office DNS", "tls://first.example:853", ""), viewModel.dnsCustomDraftSession.value?.draft)
        factories.value = factory(84)
        advanceUntilIdle()
        assertNull(viewModel.dnsCustomDraftSession.value)
        assertNull(viewModel.dnsCustomDraftToken.value)
        assertFalse(viewModel.dismissCustomEntry(opened.token))
        assertEquals(CustomResolverDraft("Office DNS", "tls://first.example:853", ""), viewModel.dnsCustomSavedDraft.value)
        viewModel.openCustomDnsDraft(null)
        assertEquals(viewModel.dnsCustomSavedDraft.value, viewModel.dnsCustomDraftSession.value?.draft)
        viewModel.close()
    }

    @Test
    fun `confirmed endpoint change invalidates open custom editor and rehydrates new persisted value`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val resolver = MutableStateFlow<FilterResolverPlan?>(FilterResolverPlan(MilestoneResolverPlan.MULLVAD_ONLY,
            customDohUrl = "https://first.example/query"))
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(
            generation = 85, completed = true, dispatcher = dispatcher, resolverValue = resolver,
            featureLimits = MutableStateFlow(com.lavasec.app.foundation.subscription.FeatureLimits.PLUS),
        ))
        val viewModel = MainProductViewModel(factories, { _ -> }, dispatcher)
        advanceUntilIdle()
        viewModel.openCustomDnsDraft(null)
        resolver.value = FilterResolverPlan(MilestoneResolverPlan.MULLVAD_ONLY, customDotEndpoint = "tls://second.example:8853")
        advanceUntilIdle()
        assertNull(viewModel.dnsCustomDraftToken.value)
        assertNull(viewModel.dnsCustomDraftSession.value)
        viewModel.openCustomDnsDraft(null)
        assertEquals(CustomResolverDraft("", "tls://second.example:8853", ""), viewModel.dnsCustomDraftSession.value?.draft)
        viewModel.close()
    }

    @Test
    fun `custom name and secondary commit through the authority and rehydrate the editor`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val initialPlan = FilterResolverPlan(MilestoneResolverPlan.DEVICE_DNS_ONLY)
        val resolver = MutableStateFlow<FilterResolverPlan?>(initialPlan)
        val writes = mutableListOf<FilterResolverPlan>()
        val port = com.lavasec.app.product.ResolverPlanSwitchPort { plan ->
            writes += plan
            resolver.value = plan
            com.lavasec.app.product.ResolverPlanSwitchResult.Applied(plan.plan, 2)
        }
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(
            generation = 86, completed = true, dispatcher = dispatcher, resolverValue = resolver,
            resolverSwitchPort = port,
            featureLimits = MutableStateFlow(com.lavasec.app.foundation.subscription.FeatureLimits.PLUS),
        ))
        val viewModel = MainProductViewModel(factories, { _ -> }, dispatcher)
        advanceUntilIdle()
        viewModel.openCustomDnsDraft(null)
        val context = com.lavasec.app.product.ResolverDnsContext.encodeCustom(initialPlan.toSelection(), CustomResolverDraft())

        assertTrue(viewModel.saveCustomDns(
            context, "Office DNS", "tls://first.example:853", "tls://second.example:8853", 86,
        ) is com.lavasec.app.product.ResolverDnsEditOutcome.Applied)
        advanceUntilIdle()
        assertEquals(
            listOf(
                FilterResolverPlan(
                    MilestoneResolverPlan.MULLVAD_ONLY,
                    customDotEndpoint = "tls://first.example:853",
                    customName = "Office DNS",
                    customSecondaryEndpoint = "tls://second.example:8853",
                ),
            ),
            writes,
        )
        assertEquals(
            CustomResolverDraft("Office DNS", "tls://first.example:853", "tls://second.example:8853"),
            viewModel.dnsCustomSavedDraft.value,
        )
        assertNull(viewModel.dnsCustomDraftToken.value)
        // A reopened editor rehydrates the committed name and secondary from the durable plan.
        viewModel.openCustomDnsDraft(null)
        assertEquals(
            CustomResolverDraft("Office DNS", "tls://first.example:853", "tls://second.example:8853"),
            viewModel.dnsCustomDraftSession.value?.draft,
        )
        viewModel.close()
    }
    @Test fun `snapshot security sees revocation before combined UI state catches up`() = runTest {
        val surface = com.lavasec.app.foundation.security.ProtectedSurface.ACTIVITY
        val verifier = object : com.lavasec.app.security.PasscodeVerifierPort {
            override fun availability() = com.lavasec.app.security.PasscodeVerifierAvailability.Available
            override fun credentialState() = com.lavasec.app.security.PasscodeCredentialState.Available
            override fun configure(code: String) = false
            override fun remove() = false
            override fun verify(code: String) = com.lavasec.app.security.PasscodeVerification.Verified
        }
        val prefs = object : com.lavasec.app.security.SecurityPreferencesPort {
            var stored = com.lavasec.app.security.SecurityPreferences(enabled = true, protectedSurfaces = setOf(surface))
            override fun load() = stored
            override fun save(preferences: com.lavasec.app.security.SecurityPreferences) { stored = preferences }
        }
        val security = com.lavasec.app.security.SecurityOwner(verifier, prefs, backgroundScope)
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L, security = security))
        val viewModel = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        try {
            advanceUntilIdle()
            security.unlock("1234", surface)
            advanceUntilIdle()
            assertTrue(viewModel.state.value.security!!.isAuthorized(surface))
            viewModel.securityResetAuthenticationTurn()
            // Intentionally do not advance the combined UI collector.
            assertTrue(viewModel.state.value.security!!.isAuthorized(surface))
            assertFalse(viewModel.securityStateForSnapshot(7L)!!.isAuthorized(surface))
            assertNull(viewModel.securityStateForSnapshot(8L))
        } finally { viewModel.close(); security.close() }
    }

    @Test fun `security set biometrics delegates the durable opt-in to the owner`() = runTest {
        val enrolled = object : com.lavasec.app.security.BiometricAuthenticatorPort {
            override fun availability() = com.lavasec.app.foundation.biometrics.BiometricAvailability.Available(
                com.lavasec.app.foundation.biometrics.BiometricKind.FINGERPRINT,
            )
            override fun isLockedOut() = false
            override suspend fun authenticate(purpose: String) =
                com.lavasec.app.foundation.biometrics.BiometricPromptResult.Success
            override fun cancel() = Unit
        }
        val security = testSecurity(emptySet(), enrolled)
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L, security = security))
        val viewModel = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        try {
            advanceUntilIdle()
            assertTrue(viewModel.securitySetBiometrics(true))
            advanceUntilIdle()
            assertTrue(viewModel.securityStateForSnapshot(7L)!!.biometricEnabled)
            assertTrue(viewModel.securitySetBiometrics(false))
            advanceUntilIdle()
            assertFalse(viewModel.securityStateForSnapshot(7L)!!.biometricEnabled)
        } finally { viewModel.close(); security.close() }
    }

    @Test fun `invalidated biometric enrollment is regenerated only after a fresh authorization`() = runTest {
        val biometric = object : com.lavasec.app.security.BiometricAuthenticatorPort {
            var resets = 0
            var availabilityState: com.lavasec.app.foundation.biometrics.BiometricAvailability =
                com.lavasec.app.foundation.biometrics.BiometricAvailability.KeyInvalidated

            override fun availability() = availabilityState

            override fun isLockedOut() = false

            override suspend fun authenticate(purpose: String) =
                com.lavasec.app.foundation.biometrics.BiometricPromptResult.Cancelled

            override fun cancel() = Unit

            override fun resetEnrollmentBoundKey(): Boolean {
                resets++
                availabilityState = com.lavasec.app.foundation.biometrics.BiometricAvailability.Available(
                    com.lavasec.app.foundation.biometrics.BiometricKind.FINGERPRINT,
                )
                return true
            }
        }
        val security = testSecurity(setOf(ProtectedSurface.APP_SETTINGS), biometric)
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L, security = security))
        val viewModel = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        try {
            advanceUntilIdle()
            // A dismissed prompt must never delete/regenerate the enrollment-bound key.
            var prompts = 0
            assertFalse(viewModel.securitySetBiometrics(true) { _, _, cancel -> prompts++; cancel() })
            assertEquals(1, prompts)
            assertEquals(0, biometric.resets)
            // A verified passcode authorizes the reset, so enabling then takes effect.
            assertTrue(viewModel.securitySetBiometrics(true) { _, verify, _ -> verify("1234"); awaitCancellation() })
            assertEquals(1, biometric.resets)
            advanceUntilIdle()
            assertTrue(viewModel.securityStateForSnapshot(7L)!!.biometricEnabled)
        } finally { viewModel.close(); security.close() }
    }

    @Test fun `tier status changes retain the compiled artifact and do not rotate the graph`() = runTest {
        val limits = MutableStateFlow(com.lavasec.app.foundation.subscription.FeatureLimits.PLUS)
        val count = MutableStateFlow<Int?>(500001)
        val rotations = mutableListOf<Long>()
        val factory = FakeOwnerFactory(7L, featureLimits = limits, filterTotalRuleCount = count)
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(factory)
        val viewModel = MainProductViewModel(factories, { rotations += it }, StandardTestDispatcher(testScheduler))
        try {
            advanceUntilIdle()
            assertEquals(500001, viewModel.state.value.filterCompiledRuleCount)
            assertEquals(2000000, viewModel.state.value.featureLimits.maxFilterRules)
            limits.value = com.lavasec.app.foundation.subscription.FeatureLimits.FREE
            advanceUntilIdle()
            assertEquals(500001, viewModel.state.value.filterCompiledRuleCount)
            assertEquals(500000, viewModel.state.value.featureLimits.maxFilterRules)
            assertTrue(rotations.isEmpty())
            limits.value = com.lavasec.app.foundation.subscription.FeatureLimits.PLUS
            advanceUntilIdle()
            assertEquals(2000000, viewModel.state.value.featureLimits.maxFilterRules)
            assertTrue(rotations.isEmpty())
        } finally { viewModel.close() }
    }

    @Test
    fun `coherent catalog migration failure keeps protection UI usable while mutation commands stay unavailable`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        for (withheld in listOf(false, true)) {
            val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(
                generation = 7L, completed = true, dispatcher = dispatcher,
                protectionController = ControlRecorder(), loggingResult = PrivacyMutationResult.Completed,
                diagnosticsAvailable = true, filterMutationsAvailable = !withheld,
            ))
            val viewModel = MainProductViewModel(factories, {}, dispatcher)
            try {
                advanceUntilIdle()
                assertEquals(withheld, viewModel.state.value.productAvailable)
                assertNotNull(viewModel.state.value.protection)
                assertEquals(OnboardingPhase.READY, viewModel.state.value.protectionOnboardingPhase)
                assertFalse(viewModel.refreshNetworkActivity())
                assertEquals(DomainStageOutcome.Unavailable,
                    viewModel.stageDomainAction("blocked.example", ManualRuleTarget.BLOCKED, 7L) { _, _, _ ->
                        error("catalog edits must remain withheld")
                    })
            } finally { viewModel.close() }
        }
    }

    @Test fun `protection onboarding phase follows the onboarding authority`() = runTest {
        val completedFactories = MutableStateFlow<CredentialProductOwnerFactory?>(
            FakeOwnerFactory(7L, completed = true, protectionController = ControlRecorder()),
        )
        val completedVm = MainProductViewModel(completedFactories, {}, StandardTestDispatcher(testScheduler))
        try {
            advanceUntilIdle()
            assertEquals(OnboardingPhase.READY, completedVm.state.value.protectionOnboardingPhase)
        } finally { completedVm.close() }

        val freshFactories = MutableStateFlow<CredentialProductOwnerFactory?>(
            FakeOwnerFactory(8L, completed = false),
        )
        val freshVm = MainProductViewModel(freshFactories, {}, StandardTestDispatcher(testScheduler))
        try {
            advanceUntilIdle()
            assertEquals(OnboardingPhase.FRESH_INSTALL, freshVm.state.value.protectionOnboardingPhase)
        } finally { freshVm.close() }
    }

    @Test
    fun `onboarding retry reconstructs the failed exact generation once without replaying its draft`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val rotations = mutableListOf<Long>()
        var commits = 0
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(
            generation = 7L, dispatcher = dispatcher,
            commit = { commits += 1; error("Initial filter preparation failed.") },
        ))
        val viewModel = MainProductViewModel(factories, { rotations += it }, dispatcher)
        try {
            advanceUntilIdle()
            reachOnboardingCommit(viewModel)
            viewModel.forwardOnboarding()
            advanceUntilIdle()
            val failed = checkNotNull(viewModel.state.value.onboarding)
            assertTrue(failed.reconstructionRequired)
            assertFalse(failed.completed)
            assertEquals("Initial filter preparation failed.", failed.error)
            assertEquals(OnboardingPhase.PREPARATION_FAILED, viewModel.state.value.protectionOnboardingPhase)
            assertTrue(rotations.isEmpty())

            assertTrue(viewModel.retryOnboarding(7L))
            assertEquals(listOf(7L), rotations)
            assertTrue(viewModel.state.value.onboardingReconstructionInFlight)
            assertEquals(failed, viewModel.state.value.onboarding)
            assertFalse(viewModel.state.value.productAvailable)
            assertFalse(viewModel.retryOnboarding(7L))
            viewModel.forwardOnboarding()
            viewModel.backOnboarding()
            advanceUntilIdle()
            assertEquals(1, commits)
            assertTrue(viewModel.state.value.onboardingReconstructionInFlight)

            // A graph replacement supplies the re-read durable result; Retry does not infer it.
            factories.value = FakeOwnerFactory(8L, completed = true, dispatcher = dispatcher)
            advanceUntilIdle()
            assertTrue(viewModel.state.value.onboarding?.completed == true)
            assertFalse(viewModel.state.value.onboardingReconstructionInFlight)
            assertEquals(OnboardingPhase.READY, viewModel.state.value.protectionOnboardingPhase)
            assertFalse(viewModel.retryOnboarding(7L))
            assertFalse(viewModel.retryOnboarding(8L))
            assertEquals(1, commits)
            assertEquals(listOf(7L), rotations)
        } finally { viewModel.close() }
    }

    @Test
    fun `onboarding retry rejects a retired failed owner and a fresh current owner`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val rotations = mutableListOf<Long>()
        fun failedFactory(generation: Long) = FakeOwnerFactory(
            generation = generation, dispatcher = dispatcher, commit = { error("Preparation failed.") },
        )
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(failedFactory(9L))
        val viewModel = MainProductViewModel(factories, { rotations += it }, dispatcher)
        try {
            advanceUntilIdle()
            assertFalse(viewModel.retryOnboarding(9L))
            reachOnboardingCommit(viewModel)
            viewModel.forwardOnboarding()
            advanceUntilIdle()
            assertTrue(viewModel.state.value.onboarding?.reconstructionRequired == true)

            factories.value = failedFactory(10L)
            advanceUntilIdle()
            assertFalse(viewModel.retryOnboarding(9L))
            assertFalse(viewModel.retryOnboarding(10L))
            reachOnboardingCommit(viewModel)
            viewModel.forwardOnboarding()
            advanceUntilIdle()
            assertTrue(viewModel.state.value.onboarding?.reconstructionRequired == true)
            assertFalse(viewModel.retryOnboarding(9L))
            assertTrue(rotations.isEmpty())
            assertTrue(viewModel.retryOnboarding(10L))
            assertEquals(listOf(10L), rotations)
        } finally { viewModel.close() }
        assertFalse(viewModel.retryOnboarding(10L))
    }

    @Test
    fun `onboarding retry cannot retire a commit that is still in flight`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val rotations = mutableListOf<Long>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(
            generation = 11L, dispatcher = dispatcher,
            commit = { started.complete(Unit); release.await(); error("Preparation failed.") },
        ))
        val viewModel = MainProductViewModel(factories, { rotations += it }, dispatcher)
        try {
            advanceUntilIdle()
            reachOnboardingCommit(viewModel)
            val committing = launch(dispatcher) { viewModel.forwardOnboarding() }
            advanceUntilIdle()
            assertTrue(started.isCompleted)
            assertTrue(viewModel.state.value.onboarding?.commitInFlight == true)
            assertFalse(viewModel.retryOnboarding(11L))
            assertTrue(rotations.isEmpty())
            release.complete(Unit)
            advanceUntilIdle()
            committing.join()
            assertTrue(viewModel.state.value.onboarding?.reconstructionRequired == true)
            assertTrue(viewModel.retryOnboarding(11L))
            assertEquals(listOf(11L), rotations)
        } finally { viewModel.close() }
    }

    @Test
    fun `actual shared setup commit hands native entry to produced graph before VM publication`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val durable = mutableListOf<AppConfiguration>()
        val entries = com.lavasec.app.reactnative.NativeDeepLinkEntryRegistry()
        val activity = Any()
        val graphs = ApplicationGraphLifecycleOwner(
            deviceGraphFactory = { object : DeviceApplicationGraph { override fun close() = Unit } },
            credentialGraphFactory = { generation ->
                val ownerFactory = FakeOwnerFactory(generation = generation, dispatcher = dispatcher,
                    completed = durable.isNotEmpty(), commit = { durable += it })
                object : CredentialApplicationGraph {
                    override val graphGeneration = generation
                    override val productOwnerFactory = ownerFactory
                    override val authoritativeProtectionIntent = false
                    override val vpnHost = object : com.lavasec.app.vpn.CredentialVpnHostPort {
                        override val graphGeneration = generation
                        override fun openAttachment(request: com.lavasec.app.vpn.CredentialVpnAttachRequest):
                            com.lavasec.app.vpn.CredentialVpnAttachment? = null
                    }
                    override fun close() = Unit
                }
            },
            attachCredentialHost = { AutoCloseable {} }, restoreProtection = {},
        )
        val ordinary = mutableListOf<Long>()
        val vm = MainProductViewModel(graphs.productOwnerFactory, { ordinary += it }, dispatcher,
            rotateSharedOnboardingGraph = graphs::rotateCredentialGraphAfterOnboarding)
        try {
            graphs.reconcile(userUnlocked = true); advanceUntilIdle()
            val nativeOwner = checkNotNull(vm.sharedOnboardingOwner(1L))
            val sourceFactory = checkNotNull(graphs.productOwnerFactory.value)
            val id = entries.stage(ProductNavigation.settings, null)
            assertTrue(entries.attach(id, activity, "A"))
            val source = checkNotNull(entries.claim(id, activity, "A", 1L)); assertTrue(entries.begin(source))
            val handoff = checkNotNull(entries.prepareSetupHandoff(source, "real-setup", nativeOwner))
            reachOnboardingCommit(vm); advanceUntilIdle()
            assertTrue(durable.isEmpty())
            var receipt: CredentialGraphTransition? = null
            var next: com.lavasec.app.reactnative.NativeDeepLinkEntryRegistry.Claim? = null
            val finishing = async(dispatcher) {
                vm.finishSharedOnboarding(1L, nativeOwner) { transition ->
                    assertTrue(nativeOwner.state.value.completed)
                    assertEquals(1, durable.size)
                    assertNull(graphs.productOwnerFactory.value)
                    assertTrue(transition.sourceFactory === sourceFactory)
                    next = entries.transferSetupHandoff(handoff, "real-setup", nativeOwner,
                        transition.sourceGeneration, transition.successorGeneration)
                    assertNotNull(next)
                    // Actual source VM state can still be delivered while its successor is not published.
                    assertEquals(1L, vm.state.value.graphGeneration)
                    assertNull(entries.claim(id, activity, "A", checkNotNull(vm.state.value.graphGeneration)))
                    receipt = transition
                }
            }
            advanceUntilIdle(); assertTrue(finishing.await())
            val produced = checkNotNull(receipt)
            assertTrue(graphs.productOwnerFactory.value === produced.successorFactory)
            assertTrue(vm.awaitSharedOnboardingTransition(produced))
            assertEquals(produced.successorGeneration, vm.state.value.graphGeneration)
            assertTrue(vm.sharedOnboardingAuthority().second?.completed == true)
            assertTrue(ordinary.isEmpty()); assertEquals(1, durable.size)
            val claim = checkNotNull(next)
            assertFalse(entries.publish(claim, produced.successorGeneration, setupCompleted = true, authorized = false))
            assertTrue(entries.publish(claim, produced.successorGeneration, setupCompleted = true, authorized = true))
            val navigation = checkNotNull(entries.navigation(id, activity, "A", produced.successorGeneration))
            assertTrue(entries.accept(id, activity, "A", produced.successorGeneration, navigation.serial, 12L))
            assertEquals("Settings", entries.acceptance(id, activity, "A", produced.successorGeneration)?.navigation?.screen)
            assertFalse(vm.finishSharedOnboarding(1L, nativeOwner) { error("repeat receipt") })
            assertEquals(1, durable.size)
        } finally { vm.close(); graphs.close() }
    }

    @Test
    fun `queued source unavailable frame cannot refuse an exact successor still creating`() = runTest {
        val dispatcher = kotlinx.coroutines.Dispatchers.Default
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val sourceFactory = FakeOwnerFactory(7L, dispatcher = dispatcher)
        val produced = object : CredentialProductOwnerFactory {
            override fun create(scope: CoroutineScope): CredentialProductOwners {
                entered.countDown()
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "Successor creation was not released" }
                return FakeOwnerFactory(42L, completed = true, dispatcher = dispatcher).create(scope)
            }
            override fun close() = Unit
        }
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(sourceFactory)
        var transition: CredentialGraphTransition? = null
        lateinit var vm: MainProductViewModel
        vm = MainProductViewModel(factories, {}, dispatcher, rotateSharedOnboardingGraph = { generation, source, receipt ->
            // Reproduce the actual old completed frame that has no configured ports,
            // rather than substituting a manufactured error into the UI projection.
            vm.state.first { it.graphGeneration == 7L && it.onboarding?.completed == true && it.unavailableReason != null }
            val actual = CredentialGraphTransition(generation, source, 42L, produced)
            receipt(actual); transition = actual; factories.value = produced
            actual
        })
        try {
            vm.state.first { it.graphGeneration == 7L }
            val nativeOwner = checkNotNull(vm.sharedOnboardingOwner(7L))
            reachOnboardingCommit(vm)
            assertTrue(vm.finishSharedOnboarding(7L, nativeOwner) {})
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(7L, vm.state.value.graphGeneration)
            assertTrue(vm.state.value.onboarding?.completed == true)
            assertNotNull(vm.state.value.unavailableReason)
            val waiting = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                vm.awaitSharedOnboardingTransition(checkNotNull(transition))
            }
            runCurrent()
            assertFalse(waiting.isCompleted)
            release.countDown()
            assertTrue(waiting.await())
            assertEquals(42L, vm.state.value.graphGeneration)
            assertTrue(vm.sharedOnboardingOwner(42L)?.state?.value?.completed == true)
        } finally { release.countDown(); vm.close() }
    }


    @Test
    fun `exact successor waiter refuses retirement or actual creation failure without needing a new UI frame`() = runTest {
        for (retirement in listOf("null-factory", "replacement", "close", "creation-failure")) {
            val dispatcher = kotlinx.coroutines.Dispatchers.Default
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val source = FakeOwnerFactory(7L, dispatcher = dispatcher)
            val produced = object : CredentialProductOwnerFactory {
                override fun create(scope: CoroutineScope): CredentialProductOwners {
                    entered.countDown()
                    check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "Successor creation was not released" }
                    if (retirement == "creation-failure") error("Exact successor creation failed")
                    return FakeOwnerFactory(42L, completed = true, dispatcher = dispatcher).create(scope)
                }
                override fun close() = Unit
            }
            val factories = MutableStateFlow<CredentialProductOwnerFactory?>(source)
            var transition: CredentialGraphTransition? = null
            lateinit var vm: MainProductViewModel
            vm = MainProductViewModel(factories, {}, dispatcher, rotateSharedOnboardingGraph = { generation, factory, receipt ->
                vm.state.first { it.graphGeneration == 7L && it.onboarding?.completed == true && it.unavailableReason != null }
                val actual = CredentialGraphTransition(generation, factory, 42L, produced)
                receipt(actual); transition = actual; factories.value = produced
                actual
            })
            try {
                vm.state.first { it.graphGeneration == 7L }
                val nativeOwner = checkNotNull(vm.sharedOnboardingOwner(7L)); reachOnboardingCommit(vm)
                assertTrue(vm.finishSharedOnboarding(7L, nativeOwner) {})
                assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
                val oldFrame = vm.state.value
                val waiting = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    vm.awaitSharedOnboardingTransition(checkNotNull(transition))
                }
                runCurrent()
                assertFalse(retirement, waiting.isCompleted)
                when (retirement) {
                    "null-factory" -> factories.value = null
                    "replacement" -> factories.value = FakeOwnerFactory(43L, completed = true, dispatcher = dispatcher)
                    "close" -> vm.close()
                    else -> release.countDown()
                }
                assertFalse(retirement, waiting.await())
                if (retirement != "creation-failure") assertTrue(vm.state.value === oldFrame)
                assertTrue(nativeOwner.state.value.completed) // Retiring UI custody never reverses the acknowledged commit.
            } finally { release.countDown(); vm.close() }
        }
    }


    @Test
    fun `same-generation replacement setup owner cannot finish through captured predecessor`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var commits = 0; var receipts = 0
        val first = FakeOwnerFactory(7L, dispatcher = dispatcher, commit = { commits++ })
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(first)
        val vm = MainProductViewModel(factories, {}, dispatcher, rotateSharedOnboardingGraph = { _, _, _ ->
            receipts++; error("wrong owner entered rotation")
        })
        try {
            advanceUntilIdle(); val original = checkNotNull(vm.sharedOnboardingOwner(7L))
            factories.value = FakeOwnerFactory(7L, dispatcher = dispatcher, commit = { commits++ })
            advanceUntilIdle(); reachOnboardingCommit(vm); advanceUntilIdle()
            assertFalse(vm.sharedOnboardingOwner(7L) === original)
            assertFalse(vm.finishSharedOnboarding(7L, original) { error("old owner received successor") })
            assertEquals(0, commits); assertEquals(0, receipts)
            assertFalse(checkNotNull(vm.sharedOnboardingOwner(7L)).state.value.completed)
        } finally { vm.close() }
    }

    @Test
    fun `ambiguous native setup commit cannot produce graph transition or destination custody`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var rotations = 0; var callbacks = 0
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L, dispatcher = dispatcher,
            commit = { error("durable acknowledgement unavailable") }))
        val vm = MainProductViewModel(factories, { rotations++ }, dispatcher,
            rotateSharedOnboardingGraph = { _, _, _ -> rotations++; null })
        try {
            advanceUntilIdle(); val nativeOwner = checkNotNull(vm.sharedOnboardingOwner(7L))
            reachOnboardingCommit(vm); advanceUntilIdle()
            val finish = async(dispatcher) { vm.finishSharedOnboarding(7L, nativeOwner) { callbacks++ } }
            advanceUntilIdle(); assertFalse(finish.await())
            assertEquals(0, rotations); assertEquals(0, callbacks)
            assertTrue(nativeOwner.state.value.reconstructionRequired)
            assertFalse(nativeOwner.state.value.completed)
        } finally { vm.close() }
    }

    @Test
    fun `retired child cannot inherit durable setup transition and unrelated produced factory is refused`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sourceFactory = FakeOwnerFactory(7L, dispatcher = dispatcher)
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(sourceFactory)
        val entries = com.lavasec.app.reactnative.NativeDeepLinkEntryRegistry(); val a = Any(); val b = Any()
        var captured: CredentialGraphTransition? = null
        val successorFactory = FakeOwnerFactory(42L, dispatcher = dispatcher, completed = true)
        val unrelatedFactory = FakeOwnerFactory(43L, dispatcher = dispatcher, completed = true)
        val vm = MainProductViewModel(factories, {}, dispatcher, rotateSharedOnboardingGraph = { generation, source, receipt ->
            val actual = CredentialGraphTransition(generation, source, 42L, successorFactory)
            receipt(actual)
            // An independent credential replacement wins after the exact receipt.
            factories.value = unrelatedFactory
            actual
        })
        try {
            advanceUntilIdle(); val nativeOwner = checkNotNull(vm.sharedOnboardingOwner(7L))
            val id = entries.stage(ProductNavigation.settings, 7L); assertTrue(entries.attach(id, a, "A"))
            val claim = checkNotNull(entries.claim(id, a, "A", 7L)); assertTrue(entries.begin(claim))
            val handoff = checkNotNull(entries.prepareSetupHandoff(claim, "setup-A", nativeOwner))
            reachOnboardingCommit(vm); advanceUntilIdle()
            entries.release(id, a, "A", recreating = false)
            val nextID = entries.stage(ProductNavigation.guard, 43L); assertTrue(entries.attach(nextID, b, "B"))
            val finish = async(dispatcher) { vm.finishSharedOnboarding(7L, nativeOwner) {
                captured = it
                assertNull(entries.transferSetupHandoff(handoff, "setup-A", nativeOwner,
                    it.sourceGeneration, it.successorGeneration))
            } }
            advanceUntilIdle(); assertTrue(finish.await())
            assertTrue(nativeOwner.state.value.completed) // Durable fact survives the retired UI.
            assertFalse(vm.awaitSharedOnboardingTransition(checkNotNull(captured)))
            val next = checkNotNull(entries.claim(nextID, b, "B", 43L)); assertTrue(entries.begin(next))
            assertTrue(entries.publish(next, 43L, setupCompleted = true, authorized = true))
            assertEquals("Guard", entries.navigation(nextID, b, "B", 43L)?.screen)
            assertNull(entries.acceptance(id, a, "A", 42L))
        } finally { vm.close() }
    }


    private suspend fun reachOnboardingCommit(viewModel: MainProductViewModel) {
        repeat(4) { viewModel.forwardOnboarding() }
        viewModel.completeNotificationPermission(granted = false)
        repeat(2) { viewModel.forwardOnboarding() }
    }

    @Test
    fun `native onboarding route callback fences its generation and preserves the committed Quad9 provider`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val committed = mutableListOf<AppConfiguration>()
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(
            generation = 7L, dispatcher = dispatcher, commit = { committed += it }))
        val rotations = mutableListOf<Long>()
        val vm = MainProductViewModel(factories, { rotations += it }, dispatcher)
        try {
            advanceUntilIdle()
            vm.stageNavigation(ProductNavigation.settings)
            repeat(4) { vm.forwardOnboarding() }
            vm.completeNotificationPermission(granted = false)
            repeat(2) { vm.forwardOnboarding() }
            advanceUntilIdle()
            val before = checkNotNull(vm.state.value.onboarding?.onboarding).draft
            vm.selectOnboardingResolverRoute(false, true, 6L)
            advanceUntilIdle()
            assertEquals(before, vm.state.value.onboarding?.onboarding?.draft)
            vm.selectOnboardingResolverRoute(false, true, 7L)
            advanceUntilIdle()
            val chosen = before.copy(resolver = before.resolver.copy(useDeviceDns = false, useEncryptedFallback = true))
            assertEquals(chosen, vm.state.value.onboarding?.onboarding?.draft)
            assertEquals(com.lavasec.app.foundation.resolver.BuiltInDohResolverIds.QUAD9_UNFILTERED, chosen.resolver.encryptedFallbackId)
            assertTrue(committed.isEmpty())
            vm.forwardOnboarding(); advanceUntilIdle()
            assertEquals(listOf(chosen), committed)
            assertEquals(listOf(7L), rotations)
            assertEquals(ProductNavigation.settings, vm.state.value.onboarding?.releasedNavigation?.navigation)
        } finally { vm.close() }
    }

    @Test
    fun `pending deep link is staged into the exact owner and released after one commit`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val factories = MutableStateFlow<CredentialProductOwnerFactory?>(null)
            val rotations = mutableListOf<Long>()
            val viewModel = MainProductViewModel(
                factories = factories,
                rotateCredentialGraph = { rotations += it },
                dispatcher = dispatcher,
            )

            viewModel.stageNavigation(ProductNavigation.settings)
            // Both the generation owner and its ViewModel observations belong to this scheduler.
            factories.value = FakeOwnerFactory(generation = 7L, dispatcher = dispatcher)
            advanceUntilIdle()

            repeat(3) { viewModel.forwardOnboarding() }
            viewModel.forwardOnboarding()
            viewModel.completeNotificationPermission(granted = false)
            viewModel.forwardOnboarding()
            viewModel.forwardOnboarding()
            viewModel.forwardOnboarding()
            advanceUntilIdle()

            val onboarding = checkNotNull(viewModel.state.value.onboarding)
            assertTrue(onboarding.completed)
            assertEquals(
                ProductNavigation.settings,
                onboarding.releasedNavigation?.navigation,
            )
            assertEquals(listOf(7L), rotations)

            viewModel.forwardOnboarding()
            assertEquals(listOf(7L), rotations)
            viewModel.close()
        }

    @Test
    fun `multiple pending deep links survive owner attachment and drain in order`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(null)
        val viewModel = MainProductViewModel(factories, { _ -> }, dispatcher)

        val activity = ProductNavigation.guard.open(ProductDestination.Activity)
        viewModel.stageNavigation(activity)
        viewModel.stageNavigation(ProductNavigation.settings)
        factories.value = FakeOwnerFactory(
            generation = 8L,
            completed = true,
            dispatcher = dispatcher,
        )
        advanceUntilIdle()

        val first = checkNotNull(viewModel.state.value.onboarding?.releasedNavigation)
        assertEquals(activity, first.navigation)
        assertTrue(viewModel.consumeReleasedNavigation(first.id))
        advanceUntilIdle()
        assertEquals(
            ProductNavigation.settings,
            viewModel.state.value.onboarding?.releasedNavigation?.navigation,
        )
        viewModel.close()
    }

    @Test
    fun `deep link queued during onboarding commit drains immediately after commit`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val commitStarted = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(
            FakeOwnerFactory(
                generation = 9L,
                dispatcher = dispatcher,
                commit = {
                    commitStarted.complete(Unit)
                    releaseCommit.await()
                },
            ),
        )
        val viewModel = MainProductViewModel(factories, { _ -> }, dispatcher)
        advanceUntilIdle()

        repeat(3) { viewModel.forwardOnboarding() }
        viewModel.forwardOnboarding()
        viewModel.completeNotificationPermission(granted = false)
        viewModel.forwardOnboarding()
        viewModel.forwardOnboarding()
        val completing = launch(dispatcher) { viewModel.forwardOnboarding() }
        advanceUntilIdle()
        assertTrue(commitStarted.isCompleted)
        assertTrue(viewModel.state.value.onboarding?.commitInFlight == true)

        val navigation = launch(dispatcher) {
            viewModel.stageNavigation(ProductNavigation.settings)
        }
        advanceUntilIdle()
        releaseCommit.complete(Unit)
        advanceUntilIdle()
        completing.join()
        navigation.join()

        val onboarding = checkNotNull(viewModel.state.value.onboarding)
        assertTrue(onboarding.completed)
        assertEquals(
            ProductNavigation.settings,
            onboarding.releasedNavigation?.navigation,
        )
        viewModel.close()
    }

    @Test
    fun `factory replacement retires the old generation and never fabricates product state`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val factories = MutableStateFlow<CredentialProductOwnerFactory?>(null)
            val firstClosed = AtomicInteger()
            val secondClosed = AtomicInteger()
            val viewModel = MainProductViewModel(
                factories = factories,
                rotateCredentialGraph = { _ -> },
                dispatcher = dispatcher,
            )

            factories.value = FakeOwnerFactory(11L, firstClosed)
            advanceUntilIdle()
            assertEquals(11L, viewModel.state.value.graphGeneration)
            assertFalse(viewModel.state.value.productAvailable)
            assertNull(viewModel.state.value.protection)

            factories.value = FakeOwnerFactory(12L, secondClosed)
            advanceUntilIdle()

            assertEquals(1, firstClosed.get())
            assertEquals(12L, viewModel.state.value.graphGeneration)
            assertFalse(viewModel.state.value.productAvailable)
            assertNull(viewModel.state.value.protection)
            viewModel.close()
            assertEquals(1, secondClosed.get())
        }

    @Test
    fun `durable privacy commit requiring reconciliation rotates the exact graph`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val rotations = mutableListOf<Long>()
        val desired = com.lavasec.app.platform.LoggingSettings(keepHistory = false)
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(
            FakeOwnerFactory(
                generation = 13L,
                completed = true,
                dispatcher = dispatcher,
                loggingResult = PrivacyMutationResult.CommittedReconciliationRequired(
                    "runtime policy requires graph reconciliation",
                ),
            ),
        )
        val viewModel = MainProductViewModel(factories, { rotations += it }, dispatcher)
        advanceUntilIdle()

        assertTrue(viewModel.setLogging(desired))
        advanceUntilIdle()

        assertEquals(listOf(13L), rotations)
        assertEquals(desired, viewModel.state.value.privacy?.logging)
        viewModel.close()
    }

    @Test
    fun `one exact VPN consent request is returned only once`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val request = Intent("vpn-consent")
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(
            FakeOwnerFactory(
                generation = 19L,
                vpn = VpnPreparationResult.ConsentRequired(request),
            ),
        )
        val viewModel = MainProductViewModel(factories, { _ -> }, dispatcher)
        advanceUntilIdle()
        repeat(3) { viewModel.forwardOnboarding() }

        val first = viewModel.forwardOnboarding()
        val duplicate = viewModel.forwardOnboarding()

        assertTrue(first?.request === request)
        assertNull(duplicate)
        viewModel.close()
    }

    @Test
    fun `filter library facts flow through product state and stay null when absent`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val library = FilterLibraryState(
            filters = listOf(
                FilterRowUi("core", "Core", false, false, false, null),
                FilterRowUi("balanced", "Balanced", true, false, false, 128),
                FilterRowUi("extra", "Extra", false, false, false, null),
            ),
            activeFilterId = "balanced",
            maxFilters = 3,
        )
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(
            FakeOwnerFactory(generation = 21L, filterLibrary = library),
        )
        val viewModel = MainProductViewModel(factories, { _ -> }, dispatcher)
        advanceUntilIdle()
        assertEquals(library, viewModel.state.value.filterLibrary)

        factories.value = FakeOwnerFactory(generation = 22L)
        advanceUntilIdle()
        assertNull(viewModel.state.value.filterLibrary)
        viewModel.close()
    }

    @Test
    fun `filter profile apply flows in flight state through the product and settles`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val release = CompletableDeferred<Unit>()
        val port = SwitchPort { target ->
            release.await()
            com.lavasec.app.product.FilterProfileSwitchResult.Applied(target, 2L)
        }
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(
            FakeOwnerFactory(
                generation = 25L,
                completed = true,
                dispatcher = dispatcher,
                switchPort = port,
                security = testSecurity(emptySet()),
            ),
        )
        val viewModel = MainProductViewModel(factories, { _ -> }, dispatcher)
        advanceUntilIdle()

        val applying = launch(dispatcher) { viewModel.requestFilterProfileApply("extra") }
        advanceUntilIdle()
        assertEquals(
            FilterProfileSwitchUiState.Applying("extra"),
            viewModel.state.value.filterProfileSwitch,
        )
        assertEquals(listOf("extra"), port.requests)

        release.complete(Unit)
        applying.join()
        advanceUntilIdle()
        assertEquals(FilterProfileSwitchUiState.Idle, viewModel.state.value.filterProfileSwitch)
        viewModel.close()
    }

    @Test
    fun `filter profile apply failure facts flow through the product`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val port = SwitchPort { target ->
            com.lavasec.app.product.FilterProfileSwitchResult.Rejected(
                target,
                com.lavasec.app.product.FilterProfileSwitchRejection.FROZEN_TARGET,
            )
        }
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(
            FakeOwnerFactory(
                generation = 26L,
                completed = true,
                dispatcher = dispatcher,
                switchPort = port,
                security = testSecurity(emptySet()),
            ),
        )
        val viewModel = MainProductViewModel(factories, { _ -> }, dispatcher)
        advanceUntilIdle()

        assertFalse(viewModel.requestFilterProfileApply("core"))
        advanceUntilIdle()
        assertEquals(
            FilterProfileSwitchUiState.Failed(
                "core",
                FilterProfileSwitchFailureUi.FROZEN_TARGET,
            ),
            viewModel.state.value.filterProfileSwitch,
        )
        viewModel.close()
    }

    @Test
    fun `filter profile apply is refused without owners or without a switch owner`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(null)
        val viewModel = MainProductViewModel(factories, { _ -> }, dispatcher)

        assertFalse(viewModel.requestFilterProfileApply("core"))

        factories.value = FakeOwnerFactory(generation = 27L, completed = true, dispatcher = dispatcher)
        advanceUntilIdle()
        assertFalse(viewModel.requestFilterProfileApply("core"))
        assertEquals(FilterProfileSwitchUiState.Idle, viewModel.state.value.filterProfileSwitch)
        viewModel.close()
    }

    @Test
    fun `filter profile apply after rotation lands on the new generation capability only`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val firstPort = SwitchPort { target ->
                com.lavasec.app.product.FilterProfileSwitchResult.Applied(target, 2L)
            }
            val factories = MutableStateFlow<CredentialProductOwnerFactory?>(
                FakeOwnerFactory(
                    generation = 28L,
                    completed = true,
                    dispatcher = dispatcher,
                    switchPort = firstPort,
                    security = testSecurity(emptySet()),
                ),
            )
            val viewModel = MainProductViewModel(factories, { _ -> }, dispatcher)
            advanceUntilIdle()
            assertTrue(viewModel.requestFilterProfileApply("extra"))

            factories.value = FakeOwnerFactory(
                generation = 29L,
                completed = true,
                dispatcher = dispatcher,
            )
            advanceUntilIdle()
            // The rotated generation supplies no switch capability; the retired owner's port
            // must not be reachable through the ViewModel anymore.
            assertFalse(viewModel.requestFilterProfileApply("core"))
            assertEquals(listOf("extra"), firstPort.requests)
            viewModel.close()
        }

    @Test
    fun `filter switch protection unavailable rotates the exact graph`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val rotations = mutableListOf<Long>()
        val port = SwitchPort { target ->
            com.lavasec.app.product.FilterProfileSwitchResult.ProtectionUnavailable(target)
        }
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(
            FakeOwnerFactory(
                generation = 30L,
                completed = true,
                dispatcher = dispatcher,
                switchPort = port,
                security = testSecurity(emptySet()),
            ),
        )
        val viewModel = MainProductViewModel(factories, { rotations += it }, dispatcher)
        advanceUntilIdle()

        assertFalse(viewModel.requestFilterProfileApply("core"))
        advanceUntilIdle()

        // The wedged coordinator recovers through the documented reconstruction path,
        // exactly like the privacy reconciliation rotation.
        assertEquals(listOf(30L), rotations)
        assertEquals(FilterProfileSwitchUiState.Idle, viewModel.state.value.filterProfileSwitch)
        viewModel.close()
    }

    @Test
    fun `filter switch reconstruction rotates the exact generation only once`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val rotations = mutableListOf<Long>()
        val port = SwitchPort { target ->
            com.lavasec.app.product.FilterProfileSwitchResult.ProtectionUnavailable(target)
        }
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(
            FakeOwnerFactory(
                generation = 31L,
                completed = true,
                dispatcher = dispatcher,
                switchPort = port,
                security = testSecurity(emptySet()),
            ),
        )
        val viewModel = MainProductViewModel(factories, { rotations += it }, dispatcher)
        advanceUntilIdle()

        assertFalse(viewModel.requestFilterProfileApply("core"))
        advanceUntilIdle()
        // A second wedged apply in the same generation must not rotate again: the sticky
        // requirement plus the generation guard keep the reconstruction exactly-once.
        assertFalse(viewModel.requestFilterProfileApply("extra"))
        advanceUntilIdle()

        assertEquals(listOf(31L), rotations)
        viewModel.close()
    }

    @Test
    fun `catalog reconstruction rotates once for each current generation and ignores the retired signal`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val rotations = mutableListOf<Long>()
        val first = MutableStateFlow(false)
        val second = MutableStateFlow(false)
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(
            generation = 31L, completed = true, dispatcher = dispatcher, catalogReconstructionRequired = first))
        val viewModel = MainProductViewModel(factories, { rotations += it }, dispatcher)
        try {
            advanceUntilIdle()
            first.value = true
            advanceUntilIdle()
            assertEquals(listOf(31L), rotations)
            first.value = false
            advanceUntilIdle()
            first.value = true
            advanceUntilIdle()
            assertEquals("Repeated source emissions cannot rotate the same graph twice", listOf(31L), rotations)
            factories.value = FakeOwnerFactory(generation = 32L, completed = true, dispatcher = dispatcher,
                catalogReconstructionRequired = second)
            advanceUntilIdle()
            first.value = false
            advanceUntilIdle()
            first.value = true
            advanceUntilIdle()
            assertEquals("The retired generation cannot rotate its successor", listOf(31L), rotations)
            second.value = true
            advanceUntilIdle()
            assertEquals(listOf(31L, 32L), rotations)
        } finally { viewModel.close() }
    }

    @Test
    fun `resolver plan apply flows in flight state through the product and settles`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val release = CompletableDeferred<Unit>()
        val port = ResolverPort { plan ->
            release.await()
            com.lavasec.app.product.ResolverPlanSwitchResult.Applied(plan, 2L)
        }
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(
            FakeOwnerFactory(
                generation = 40L,
                completed = true,
                dispatcher = dispatcher,
                resolverSwitchPort = port,
            ),
        )
        val viewModel = MainProductViewModel(factories, { _ -> }, dispatcher)
        advanceUntilIdle()

        val applying = launch(dispatcher) {
            viewModel.requestResolverPlanSwitch(MilestoneResolverPlan.MULLVAD_ONLY)
        }
        advanceUntilIdle()
        assertEquals(
            ResolverPlanSwitchUiState.Applying(MilestoneResolverPlan.MULLVAD_ONLY),
            viewModel.state.value.resolverPlanSwitch,
        )
        assertEquals(listOf(MilestoneResolverPlan.MULLVAD_ONLY), port.requests)

        release.complete(Unit)
        applying.join()
        advanceUntilIdle()
        assertEquals(ResolverPlanSwitchUiState.Idle, viewModel.state.value.resolverPlanSwitch)
        viewModel.close()
    }

    @Test
    fun `resolver plan apply failure facts flow through the product`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val port = ResolverPort { plan ->
            com.lavasec.app.product.ResolverPlanSwitchResult.Rejected(
                plan,
                com.lavasec.app.product.ResolverPlanSwitchRejection.NO_CHANGE,
            )
        }
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(
            FakeOwnerFactory(
                generation = 41L,
                completed = true,
                dispatcher = dispatcher,
                resolverSwitchPort = port,
            ),
        )
        val viewModel = MainProductViewModel(factories, { _ -> }, dispatcher)
        advanceUntilIdle()

        assertFalse(viewModel.requestResolverPlanSwitch(MilestoneResolverPlan.DEVICE_DNS_ONLY))
        advanceUntilIdle()
        assertEquals(
            ResolverPlanSwitchUiState.Failed(MilestoneResolverPlan.DEVICE_DNS_ONLY),
            viewModel.state.value.resolverPlanSwitch,
        )
        viewModel.close()
    }

    @Test
    fun `resolver plan apply is refused without owners or without a switch owner`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(null)
        val viewModel = MainProductViewModel(factories, { _ -> }, dispatcher)

        assertFalse(viewModel.requestResolverPlanSwitch(MilestoneResolverPlan.MULLVAD_ONLY))

        factories.value = FakeOwnerFactory(generation = 42L, completed = true, dispatcher = dispatcher)
        advanceUntilIdle()
        assertFalse(viewModel.requestResolverPlanSwitch(MilestoneResolverPlan.MULLVAD_ONLY))
        assertEquals(ResolverPlanSwitchUiState.Idle, viewModel.state.value.resolverPlanSwitch)
        viewModel.close()
    }

    @Test
    fun `dns tiers commit the accepted ladder through the exact generation policy`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val port = ResolverPort { plan ->
            com.lavasec.app.product.ResolverPlanSwitchResult.Applied(plan, 2L)
        }
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(
            FakeOwnerFactory(
                generation = 44L,
                completed = true,
                dispatcher = dispatcher,
                resolverPlan = MilestoneResolverPlan.DEVICE_DNS_ONLY,
                resolverSwitchPort = port,
                featureLimits = MutableStateFlow(com.lavasec.app.foundation.subscription.FeatureLimits.PLUS),
            ),
        )
        val viewModel = MainProductViewModel(factories, { _ -> }, dispatcher)
        advanceUntilIdle()

        val outcome = viewModel.applyDnsTiers(
            tiers = listOf(com.lavasec.app.foundation.resolver.ResolverTierChoice("mullvad", "DoH")),
            context = com.lavasec.app.product.ResolverDnsContext.encode(
                MilestoneResolverPlan.DEVICE_DNS_ONLY.toSelection(),
            ),
            expectedGeneration = 44L,
        )
        advanceUntilIdle()

        assertTrue("expected Applied but was $outcome", outcome is com.lavasec.app.product.ResolverDnsEditOutcome.Applied)
        assertEquals(listOf(MilestoneResolverPlan.MULLVAD_ONLY), port.requests)
        viewModel.close()
    }

    @Test
    fun `dns tiers refuse an unsupported provider without touching the switch port`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val port = ResolverPort { plan ->
            com.lavasec.app.product.ResolverPlanSwitchResult.Applied(plan, 2L)
        }
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(
            FakeOwnerFactory(
                generation = 45L,
                completed = true,
                dispatcher = dispatcher,
                resolverPlan = MilestoneResolverPlan.DEVICE_DNS_ONLY,
                resolverSwitchPort = port,
                featureLimits = MutableStateFlow(com.lavasec.app.foundation.subscription.FeatureLimits.PLUS),
            ),
        )
        val viewModel = MainProductViewModel(factories, { _ -> }, dispatcher)
        advanceUntilIdle()

        val outcome = viewModel.applyDnsTiers(
            tiers = listOf(com.lavasec.app.foundation.resolver.ResolverTierChoice("mullvad-doh", "DoH")),
            context = com.lavasec.app.product.ResolverDnsContext.encode(
                MilestoneResolverPlan.DEVICE_DNS_ONLY.toSelection(),
            ),
            expectedGeneration = 45L,
        )
        advanceUntilIdle()

        assertEquals(
            com.lavasec.app.product.ResolverDnsEditOutcome.Refused(
                com.lavasec.app.foundation.resolver.ResolverEditRejection.UnknownProvider("mullvad-doh"),
            ),
            outcome,
        )
        assertEquals(emptyList<MilestoneResolverPlan>(), port.requests)
        viewModel.close()
    }

    @Test
    fun `custom draft open is plus-gated and dismiss is token-fenced`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val free = MutableStateFlow(com.lavasec.app.foundation.subscription.FeatureLimits.FREE)
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(
            FakeOwnerFactory(
                generation = 46L,
                completed = true,
                dispatcher = dispatcher,
                resolverPlan = MilestoneResolverPlan.DEVICE_DNS_ONLY,
                featureLimits = free,
            ),
        )
        val viewModel = MainProductViewModel(factories, { _ -> }, dispatcher)
        advanceUntilIdle()

        assertEquals(
            com.lavasec.app.product.ResolverDnsEditOutcome.Refused(
                com.lavasec.app.foundation.resolver.ResolverEditRejection.CustomRequiresPlus,
            ),
            viewModel.openCustomDnsDraft(null),
        )

        free.value = com.lavasec.app.foundation.subscription.FeatureLimits.PLUS
        advanceUntilIdle()
        val opened = viewModel.openCustomDnsDraft(null)
        assertTrue(opened is com.lavasec.app.product.ResolverDnsEditOutcome.DraftOpened)
        val token = (opened as com.lavasec.app.product.ResolverDnsEditOutcome.DraftOpened).token
        assertEquals(token, viewModel.dnsCustomDraftToken.value)
        assertFalse(viewModel.dismissCustomEntry("wrong-token"))
        assertTrue(viewModel.dismissCustomEntry(token))
        assertNull(viewModel.dnsCustomDraftToken.value)
        assertNull(viewModel.dnsCustomDraftSession.value)
        viewModel.close()
    }

    @Test fun `native QA publishes all scenario fields once and refuses custom DNS editor or wrong graph`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val initial = FilterLibraryDraft.seededDefaults(com.lavasec.app.foundation.filter.FilterCatalogSnapshot(emptyList()))
        val port = QaLibraryPort(initial)
        val limits = MutableStateFlow(com.lavasec.app.foundation.subscription.FeatureLimits.FREE)
        val library = FilterLibraryOwner(port, backgroundScope, dispatcher, limits = { limits.value })
        val resolver = ResolverPort { error("QA must not make a separate resolver commit") }
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(91L,
            completed = true, dispatcher = dispatcher, libraryOwner = library, libraryDraft = MutableStateFlow(initial),
            resolverPlan = initial.resolverPlan, resolverSwitchPort = resolver, featureLimits = limits,
            resolverQaAdmission = { com.lavasec.app.product.QaDnsPublicationAdmission(
                com.lavasec.app.foundation.resolver.ChainedDnsEditState.editable, com.lavasec.app.platform.NativeFilterPublicationGuard { it() }) }))
        val vm = MainProductViewModel(factories, { _ -> }, dispatcher); advanceUntilIdle()
        val desired = initial.withQaProbeSet(com.lavasec.app.foundation.filter.DeviceQaProbeSet.HOSTED)
        val tiers = listOf(com.lavasec.app.foundation.resolver.ResolverTierChoice("google-public-dns", "IP"))
        try {
            assertTrue(vm.applyQaCompositeConfiguration(desired, tiers, initial, 90L) { true } is com.lavasec.app.product.QaCompositeConfigurationResult.Unavailable)
            assertEquals(com.lavasec.app.product.ResolverDnsEditOutcome.Refused(
                com.lavasec.app.foundation.resolver.ResolverEditRejection.CustomRequiresPlus), vm.openCustomDnsDraft(null))
            assertNull(vm.dnsCustomDraftToken.value)
            // Only this unit fixture changes limits; an admitted editor requires real Plus policy.
            // Both native owners must evaluate the same fixture limit authority.
            limits.value = com.lavasec.app.foundation.subscription.FeatureLimits.PLUS
            advanceUntilIdle()
            val opened = vm.openCustomDnsDraft(null)
            assertTrue(opened is com.lavasec.app.product.ResolverDnsEditOutcome.DraftOpened)
            assertTrue(vm.applyQaCompositeConfiguration(desired, tiers, initial, 91L) { true } is com.lavasec.app.product.QaCompositeConfigurationResult.Unavailable)
            vm.dismissCustomEntry(checkNotNull(vm.dnsCustomDraftToken.value))
            assertTrue(vm.applyQaCompositeConfiguration(desired, tiers, initial, 91L) { true } is com.lavasec.app.product.QaCompositeConfigurationResult.Applied)
            assertEquals(1, port.writes); assertEquals(2L, port.generation)
            assertEquals(desired.qaProbeSet, port.library.qaProbeSet)
            assertEquals("google-public-dns", port.library.resolverTarget.toSelection().primary.providerId.rawId)
            assertEquals(initial.profiles, port.library.profiles)
            assertTrue(resolver.requests.isEmpty())
        } finally { vm.close(); library.close() }
    }

    @Test fun `QA reset reports committed partial reset when final privacy refuses and uses one library generation`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val retainedFallback = ResolverTier(ResolverSelectionEntry(ResolverProviderId.QUAD9_UNFILTERED, CatalogResolverTransport.DOT),
            false, "Retained disabled fallback")
        val initial = FilterLibraryDraft.seededDefaults(com.lavasec.app.foundation.filter.FilterCatalogSnapshot(emptyList()))
            .withQaProbeSet(com.lavasec.app.foundation.filter.DeviceQaProbeSet.HOSTED)
            .withResolverPlan(checkNotNull(ResolverSelection(listOf(ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN), retainedFallback)).toFilterResolverPlan()))
        val port = QaLibraryPort(initial); val library = FilterLibraryOwner(port, backgroundScope, dispatcher)
        var privacyCalls = 0
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(92L,
            completed = true, dispatcher = dispatcher, libraryOwner = library, libraryDraft = MutableStateFlow(initial),
            resolverPlan = initial.resolverPlan, resolverSwitchPort = ResolverPort { error("no separate DNS write") },
            loggingResult = PrivacyMutationResult.Completed,
            resolverQaAdmission = { com.lavasec.app.product.QaDnsPublicationAdmission(
                com.lavasec.app.foundation.resolver.ChainedDnsEditState.editable, com.lavasec.app.platform.NativeFilterPublicationGuard { it() }) },
            qaReset = { previous, generation, current ->
                privacyCalls++; assertEquals(com.lavasec.app.platform.LoggingSettings(), previous)
                assertEquals(port.generation, generation); assertTrue(current()); PrivacyMutationResult.Cancelled
            }))
        val vm = MainProductViewModel(factories, { _ -> }, dispatcher); advanceUntilIdle()
        try {
            assertEquals(com.lavasec.app.product.QaCompositeConfigurationResult.CommittedReconciliationRequired,
                vm.resetQaState(initial, com.lavasec.app.platform.LoggingSettings(), 92L) { true })
            assertEquals(1, port.writes); assertEquals(2L, port.generation); assertEquals(1, privacyCalls)
            assertNull(port.library.qaProbeSet)
            assertEquals("google-public-dns", port.library.resolverTarget.toSelection().primary.providerId.rawId)
            assertEquals(retainedFallback, port.library.resolverTarget.toSelection().tiers[1])
            assertEquals(initial.profiles, port.library.profiles)
            assertEquals(com.lavasec.app.platform.LoggingSettings(), vm.currentQaLogging())
        } finally { vm.close(); library.close() }
    }

    @Test
    fun `dns custom commits a valid DoH endpoint through the resolver switch`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val port = ResolverPort { plan ->
            com.lavasec.app.product.ResolverPlanSwitchResult.Applied(plan, 2L)
        }
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(
            FakeOwnerFactory(
                generation = 47L,
                completed = true,
                dispatcher = dispatcher,
                resolverPlan = MilestoneResolverPlan.DEVICE_DNS_ONLY,
                resolverSwitchPort = port,
                featureLimits = MutableStateFlow(com.lavasec.app.foundation.subscription.FeatureLimits.PLUS),
            ),
        )
        val viewModel = MainProductViewModel(factories, { _ -> }, dispatcher)
        advanceUntilIdle()

        val opened = viewModel.openCustomDnsDraft(null)
        assertTrue(opened is com.lavasec.app.product.ResolverDnsEditOutcome.DraftOpened)
        val context = com.lavasec.app.product.ResolverDnsContext.encodeCustom(
            MilestoneResolverPlan.DEVICE_DNS_ONLY.toSelection(),
            com.lavasec.app.foundation.resolver.CustomResolverDraft(),
        )
        val outcome = viewModel.saveCustomDns(
            context = context,
            name = "Home",
            primary = "https://dns.example/query",
            secondary = "",
            expectedGeneration = 47L,
        )
        advanceUntilIdle()

        // A valid custom DoH passes policy and now commits through the value-carrying resolver
        // switch, reporting Applied and touching the port exactly once.
        assertTrue(
            "expected Applied but was $outcome",
            outcome is com.lavasec.app.product.ResolverDnsEditOutcome.Applied,
        )
        assertEquals(listOf(MilestoneResolverPlan.MULLVAD_ONLY), port.requests)
        viewModel.close()
    }

    @Test
    fun `saveCustomDns refuses an invalid DoH endpoint and never touches the switch port`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val port = ResolverPort { plan ->
            com.lavasec.app.product.ResolverPlanSwitchResult.Applied(plan, 2L)
        }
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(
            FakeOwnerFactory(
                generation = 48L,
                completed = true,
                dispatcher = dispatcher,
                resolverPlan = MilestoneResolverPlan.DEVICE_DNS_ONLY,
                resolverSwitchPort = port,
                featureLimits = MutableStateFlow(com.lavasec.app.foundation.subscription.FeatureLimits.PLUS),
            ),
        )
        val viewModel = MainProductViewModel(factories, { _ -> }, dispatcher)
        advanceUntilIdle()

        assertTrue(viewModel.openCustomDnsDraft(null) is com.lavasec.app.product.ResolverDnsEditOutcome.DraftOpened)
        val context = com.lavasec.app.product.ResolverDnsContext.encodeCustom(
            MilestoneResolverPlan.DEVICE_DNS_ONLY.toSelection(),
            com.lavasec.app.foundation.resolver.CustomResolverDraft(),
        )
        val outcome = viewModel.saveCustomDns(
            context = context,
            name = "Home",
            primary = "not-a-url",
            secondary = "",
            expectedGeneration = 48L,
        )
        advanceUntilIdle()

        assertTrue(
            "expected a custom-invalid refusal but was $outcome",
            outcome is com.lavasec.app.product.ResolverDnsEditOutcome.Refused,
        )
        assertEquals(emptyList<MilestoneResolverPlan>(), port.requests)
        viewModel.close()
    }

    @Test
    fun `resolver plan apply after rotation lands on the new generation capability only`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val firstPort = ResolverPort { plan ->
                com.lavasec.app.product.ResolverPlanSwitchResult.Applied(plan, 2L)
            }
            val factories = MutableStateFlow<CredentialProductOwnerFactory?>(
                FakeOwnerFactory(
                    generation = 43L,
                    completed = true,
                    dispatcher = dispatcher,
                    resolverSwitchPort = firstPort,
                ),
            )
            val viewModel = MainProductViewModel(factories, { _ -> }, dispatcher)
            advanceUntilIdle()
            assertTrue(viewModel.requestResolverPlanSwitch(MilestoneResolverPlan.MULLVAD_ONLY))

            factories.value = FakeOwnerFactory(
                generation = 44L,
                completed = true,
                dispatcher = dispatcher,
            )
            advanceUntilIdle()
            // The rotated generation supplies no switch capability; the retired owner's port
            // must not be reachable through the ViewModel anymore.
            assertFalse(viewModel.requestResolverPlanSwitch(MilestoneResolverPlan.DEVICE_DNS_ONLY))
            assertEquals(listOf(MilestoneResolverPlan.MULLVAD_ONLY), firstPort.requests)
            viewModel.close()
        }

    @Test
    fun `resolver plan wedge rotates the exact generation only once`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val rotations = mutableListOf<Long>()
        val port = ResolverPort { plan ->
            com.lavasec.app.product.ResolverPlanSwitchResult.ProtectionUnavailable(plan)
        }
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(
            FakeOwnerFactory(
                generation = 45L,
                completed = true,
                dispatcher = dispatcher,
                resolverSwitchPort = port,
            ),
        )
        val viewModel = MainProductViewModel(factories, { rotations += it }, dispatcher)
        advanceUntilIdle()

        assertFalse(viewModel.requestResolverPlanSwitch(MilestoneResolverPlan.MULLVAD_ONLY))
        advanceUntilIdle()
        // A second wedged apply in the same generation must not rotate again: the sticky
        // requirement plus the generation guard keep the reconstruction exactly-once.
        assertFalse(viewModel.requestResolverPlanSwitch(MilestoneResolverPlan.DEVICE_DNS_ONLY))
        advanceUntilIdle()

        assertEquals(listOf(45L), rotations)
        assertEquals(ResolverPlanSwitchUiState.Idle, viewModel.state.value.resolverPlanSwitch)
        viewModel.close()
    }

    @Test
    fun `current resolver plan flows through product state and stays null when absent`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(
            FakeOwnerFactory(
                generation = 46L,
                dispatcher = dispatcher,
                resolverPlan = MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK,
            ),
        )
        val viewModel = MainProductViewModel(factories, { _ -> }, dispatcher)
        advanceUntilIdle()
        assertEquals(
            MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK,
            viewModel.state.value.resolverPlan,
        )

        factories.value = FakeOwnerFactory(generation = 47L, dispatcher = dispatcher)
        advanceUntilIdle()
        assertNull(viewModel.state.value.resolverPlan)
        viewModel.close()
    }

    @Test fun `canonical domain queries normalize search through the real owner for both lists and decisions`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val port = NetworkPort(dispatcher, queryReady = true)
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(
            FakeOwnerFactory(70L, completed = true, dispatcher = dispatcher, networkPort = port))
        val vm = MainProductViewModel(factories, {}, dispatcher)
        val resources = object : com.lavasec.app.reactnative.QueryResources {
            override fun string(id: Int, vararg args: Any) = id.toString()
            override fun plural(id: Int, count: Int, vararg args: Any) = count.toString()
        }
        val queries = com.lavasec.app.reactnative.ProductQueryOwner(vm, resources) { ZoneId.of("UTC") }
        try {
            advanceUntilIdle()
            val summaryStart = port.summaryQueries.size
            val historyStart = port.historyQueries.size
            for (history in listOf(false, true)) {
                for (decision in com.lavasec.app.reactnative.DomainQueryDecision.entries) {
                    val read = async {
                        queries.domains(history, decision, "  tracker.example ", null, null, 30, expectedGeneration = 70L)
                    }
                    advanceUntilIdle()
                    val result = read.await() as com.lavasec.app.reactnative.QueryOutcome.Value
                    @Suppress("UNCHECKED_CAST")
                    val rows = result.value as List<com.lavasec.app.reactnative.DomainQueryRow>
                    assertEquals(if (decision == com.lavasec.app.reactnative.DomainQueryDecision.ALL) 2 else 1, rows.size)
                    assertTrue(rows.all { it.domain == "tracker.example" })
                }
            }
            val summaries = port.summaryQueries.drop(summaryStart)
            val historyReads = port.historyQueries.drop(historyStart)
            assertTrue(summaries.isNotEmpty())
            assertTrue(historyReads.isNotEmpty())
            assertTrue(summaries.all { it.topDomainSearch == "tracker.example" })
            assertTrue(historyReads.all { it.search == "tracker.example" })
        } finally { vm.close() }
    }

    @Test fun `canonical query setters cannot move a successor graph lens and awaited reads retire promptly`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val oldPort = NetworkPort(dispatcher)
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(
            FakeOwnerFactory(71L, completed = true, dispatcher = dispatcher, networkPort = oldPort))
        val vm = MainProductViewModel(factories, {}, dispatcher)
        val resources = object : com.lavasec.app.reactnative.QueryResources {
            override fun string(id: Int, vararg args: Any) = id.toString()
            override fun plural(id: Int, count: Int, vararg args: Any) = count.toString()
        }
        val queries = com.lavasec.app.reactnative.ProductQueryOwner(vm, resources) { ZoneId.of("UTC") }
        try {
            advanceUntilIdle()
            val oldSummaries = oldPort.summaryQueries.toList()
            val oldHistory = oldPort.historyQueries.toList()
            assertFalse(vm.setActivitySummaryQuery(null, null, "wrong", expectedGeneration = 70L))
            assertFalse(vm.setActivityHistoryQuery(null, "wrong", expectedGeneration = 70L))
            assertEquals(oldSummaries, oldPort.summaryQueries)
            assertEquals(oldHistory, oldPort.historyQueries)
            val waiting = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                queries.network(expectedGeneration = 71L)
            }
            assertFalse(waiting.isCompleted)
            val startedAt = testScheduler.currentTime
            val successor = NetworkPort(dispatcher, queryReady = true)
            factories.value = FakeOwnerFactory(72L, completed = true, dispatcher = dispatcher, networkPort = successor)
            advanceUntilIdle()
            assertEquals(com.lavasec.app.reactnative.QueryOutcome.Rejected("network-unavailable"), waiting.await())
            assertEquals(startedAt, testScheduler.currentTime)
            val nextSummaries = successor.summaryQueries.toList()
            val nextHistory = successor.historyQueries.toList()
            assertFalse(vm.setActivitySummaryQuery(null, null, "old", expectedGeneration = 71L))
            assertFalse(vm.setActivityHistoryQuery(null, "old", expectedGeneration = 71L))
            vm.loadMoreHistory(expectedGeneration = 71L)
            assertEquals(nextSummaries, successor.summaryQueries)
            assertEquals(nextHistory, successor.historyQueries)
            assertTrue(vm.setActivitySummaryQuery(null, null, "fresh", expectedGeneration = 72L))
            advanceUntilIdle()
            assertEquals("fresh", successor.summaryQueries.last().topDomainSearch)
        } finally { vm.close() }
    }

    @Test
    fun `network activity clear is generation fenced and reloads without predecessor rows`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val port = NetworkPort(dispatcher)
            val factories = MutableStateFlow<CredentialProductOwnerFactory?>(
                FakeOwnerFactory(
                    generation = 50L,
                    completed = true,
                    dispatcher = dispatcher,
                    networkPort = port,
                ),
            )
            val viewModel = MainProductViewModel(factories, { _ -> }, dispatcher)
            advanceUntilIdle()
            val loadsBefore = port.loadNetworkActivityCalls

            assertTrue(viewModel.clearNetworkActivity())
            advanceUntilIdle()

            assertEquals(loadsBefore + 1, port.loadNetworkActivityCalls)
            viewModel.close()
        }

    @Test
    fun `local log scope clear and guard progress clear delegate to the privacy owner`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(
            FakeOwnerFactory(
                generation = 60L,
                completed = true,
                dispatcher = dispatcher,
                loggingResult = PrivacyMutationResult.Completed,
            ),
        )
        val viewModel = MainProductViewModel(factories, { _ -> }, dispatcher)
        try {
            advanceUntilIdle()
            assertTrue(viewModel.clearLocalActivity(countsAndUptime = false, domainHistory = true))
            advanceUntilIdle()
            assertTrue(viewModel.clearGuardProgress())
            advanceUntilIdle()
            // At least one class must be selected; a no-op selection is refused before the owner runs.
            assertFalse(viewModel.clearLocalActivity(countsAndUptime = false, domainHistory = false))
        } finally {
            viewModel.close()
        }
    }

    @Test
    fun `partially committed log clear refreshes readers but reports incomplete until explicit retry succeeds`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val port = NetworkPort(dispatcher, queryReady = true)
        val rotations = mutableListOf<Long>()
        var attempts = 0
        var retainedDiagnostics = true
        val selections = mutableListOf<com.lavasec.app.product.privacy.LocalActivityClearSelection>()
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(
            generation = 61L, completed = true, dispatcher = dispatcher, networkPort = port,
            localClear = { selection ->
                selections += selection
                attempts++
                if (attempts == 1) PrivacyMutationResult.CommittedReconciliationRequired("numeric history remains retained")
                else {
                    retainedDiagnostics = false
                    PrivacyMutationResult.Completed
                }
            }))
        val viewModel = MainProductViewModel(factories, { rotations += it }, dispatcher)
        try {
            advanceUntilIdle()
            val summaryReads = port.summaryQueries.size
            val historyReads = port.historyQueries.size

            assertFalse(viewModel.clearLocalActivity())
            advanceUntilIdle()

            assertTrue(retainedDiagnostics)
            assertTrue(port.summaryQueries.size > summaryReads)
            assertTrue(port.historyQueries.size > historyReads)
            assertTrue(viewModel.state.value.privacy?.lastMutation is PrivacyMutationResult.CommittedReconciliationRequired)
            assertEquals("numeric history remains retained", viewModel.state.value.privacy?.error)
            assertTrue(rotations.isEmpty())

            assertTrue(viewModel.clearLocalActivity())
            advanceUntilIdle()

            assertFalse(retainedDiagnostics)
            assertEquals(2, attempts)
            assertEquals(List(2) { com.lavasec.app.product.privacy.LocalActivityClearSelection(true, true) }, selections)
            assertEquals(PrivacyMutationResult.Completed, viewModel.state.value.privacy?.lastMutation)
            assertNull(viewModel.state.value.privacy?.error)
        } finally { viewModel.close() }
    }

    @Test
    fun `network activity clear is refused without owners`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = MainProductViewModel(
            MutableStateFlow(null),
            { _ -> },
            dispatcher,
        )

        assertFalse(viewModel.clearNetworkActivity())
        assertFalse(viewModel.refreshNetworkActivity())
        assertFalse(viewModel.loadMoreNetworkActivity())
        viewModel.close()
    }

    @Test
    fun `failed network activity clear reports failure and does not reload the surface`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val port = NetworkPort(dispatcher)
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(
            FakeOwnerFactory(
                generation = 51L,
                completed = true,
                dispatcher = dispatcher,
                networkPort = port,
                networkClearResult = PrivacyMutationResult.Failed("clear failed"),
            ),
        )
        val viewModel = MainProductViewModel(factories, { _ -> }, dispatcher)
        advanceUntilIdle()
        val loadsBefore = port.loadNetworkActivityCalls

        // A Failed mutation is not a durable clear: the command must report failure, not admission.
        assertFalse(viewModel.clearNetworkActivity())
        advanceUntilIdle()

        assertEquals(loadsBefore, port.loadNetworkActivityCalls)
        viewModel.close()
    }

    @Test
    fun `turning network activity retention off reloads without predecessor rows`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val port = NetworkPort(dispatcher)
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(
            FakeOwnerFactory(
                generation = 52L,
                completed = true,
                dispatcher = dispatcher,
                networkPort = port,
            ),
        )
        val viewModel = MainProductViewModel(factories, { _ -> }, dispatcher)
        advanceUntilIdle()
        val loadsBefore = port.loadNetworkActivityCalls

        assertTrue(viewModel.setLogging(com.lavasec.app.platform.LoggingSettings(keepNetworkActivity = false)))
        advanceUntilIdle()

        assertEquals(loadsBefore + 1, port.loadNetworkActivityCalls)
        viewModel.close()
    }

    @Test
    fun `network activity refresh and load more are admitted through the exact generation`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val port = NetworkPort(dispatcher)
            val factories = MutableStateFlow<CredentialProductOwnerFactory?>(
                FakeOwnerFactory(
                    generation = 53L,
                    completed = true,
                    dispatcher = dispatcher,
                    networkPort = port,
                ),
            )
            val viewModel = MainProductViewModel(factories, { _ -> }, dispatcher)
            advanceUntilIdle()
            val loadsBefore = port.loadNetworkActivityCalls

            assertTrue(viewModel.refreshNetworkActivity())
            advanceUntilIdle()
            assertEquals(loadsBefore + 1, port.loadNetworkActivityCalls)

            assertTrue(viewModel.loadMoreNetworkActivity())
            viewModel.close()
        }

    @Test fun `explicit control always verifies freshly before committing or dispatching`() = runTest {
        val security = testSecurity(setOf(ProtectedSurface.PROTECTION_CONTROL))
        val controller = ControlRecorder()
        val commits = mutableListOf<Boolean>()
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, protectionController = controller, protectionCommits = commits))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        try {
            advanceUntilIdle()
            for ((state, action) in listOf(
                ProtectionState.Off to GuardPrimaryAction.TurnOn,
                controller.protectedState to GuardPrimaryAction.TurnOff,
                ProtectionState.Failed(com.lavasec.app.platform.ProtectionFailure.EngineUnavailable) to GuardPrimaryAction.Reconnect,
            )) {
                controller.mutable.value = state
                advanceUntilIdle()
                // A navigation grant does not authorize an explicit control operation.
                security.unlock("1234", ProtectedSurface.PROTECTION_CONTROL)
                val beforeEvents = controller.events.size
                val beforeCommits = commits.size
                assertEquals(ProtectionCommandResult.Cancelled, vm.performGuardActionForGeneration(7L, action))
                assertEquals(beforeEvents, controller.events.size)
                assertEquals(beforeCommits, commits.size)
                val result = vm.performGuardActionForGeneration(7L, action) { request, verify, _ ->
                    assertEquals(ProtectedSurface.PROTECTION_CONTROL, request.surface)
                    assertEquals(beforeEvents, controller.events.size)
                    assertFalse(verify("wrong"))
                    assertEquals(beforeCommits, commits.size)
                    verify("1234")
                    awaitCancellation()
                }
                assertTrue(result is ProtectionCommandResult.Completed)
                assertEquals(beforeEvents + 1, controller.events.size)
            }
            assertEquals(listOf("start", "stop", "reconnect"), controller.events)
            assertEquals(listOf(true, false), commits)
        } finally { vm.close(); security.close() }
    }

    @Test fun `pause gate is independent and resume keeps canonical exemption`() = runTest {
        val security = testSecurity(setOf(ProtectedSurface.PROTECTION_PAUSE, ProtectedSurface.PROTECTION_CONTROL))
        val controller = ControlRecorder().apply { mutable.value = protectedState }
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, protectionController = controller))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        try {
            advanceUntilIdle()
            val duration = com.lavasec.app.foundation.pause.PauseDuration.FIVE_MINUTES
            assertEquals(ProtectionCommandResult.Cancelled, vm.performPause(duration, 7L))
            assertTrue(controller.events.isEmpty())
            assertTrue(vm.performPause(duration, 7L) { request, verify, _ ->
                assertEquals(ProtectedSurface.PROTECTION_PAUSE, request.surface)
                verify("1234"); awaitCancellation()
            } is ProtectionCommandResult.Completed)
            controller.mutable.value = ProtectionState.Paused(Long.MAX_VALUE)
            advanceUntilIdle()
            assertTrue(vm.performGuardActionForGeneration(7L, GuardPrimaryAction.Resume) { _, _, _ ->
                error("Resume must not prompt")
            } is ProtectionCommandResult.Completed)
            assertEquals(listOf("pause", "resume"), controller.events)
        } finally { vm.close(); security.close() }
    }

    @Test fun `graph rotation during native authentication cannot dispatch an old action`() = runTest {
        val security = testSecurity(setOf(ProtectedSurface.PROTECTION_CONTROL))
        val controller = ControlRecorder()
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, protectionController = controller))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        try {
            advanceUntilIdle()
            val shown = CompletableDeferred<Unit>()
            val action = async { vm.performGuardActionForGeneration(7L, GuardPrimaryAction.TurnOn) { _, _, _ ->
                shown.complete(Unit); awaitCancellation()
            } }
            shown.await()
            factories.value = FakeOwnerFactory(8L, security = security, protectionController = controller)
            advanceUntilIdle()
            assertEquals(ProtectionCommandResult.Cancelled, action.await())
            assertTrue(controller.events.isEmpty())
        } finally { vm.close(); security.close() }
    }

    @Test fun `action is sampled again after native authentication`() = runTest {
        val security = testSecurity(setOf(ProtectedSurface.PROTECTION_CONTROL))
        val controller = ControlRecorder()
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, protectionController = controller))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        try {
            advanceUntilIdle()
            val result = vm.performGuardActionForGeneration(7L, GuardPrimaryAction.TurnOn) { _, verify, _ ->
                controller.mutable.value = controller.protectedState
                advanceUntilIdle()
                verify("1234"); awaitCancellation()
            }
            assertEquals(ProtectionCommandResult.Rejected, result)
            assertTrue(controller.events.isEmpty())
        } finally { vm.close(); security.close() }
    }

    @Test fun `disabling a selected surface requires fresh auth and stale settings callbacks fail`() = runTest {
        val security = testSecurity(setOf(ProtectedSurface.ACTIVITY))
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L, security = security))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        try {
            advanceUntilIdle()
            security.unlock("1234", ProtectedSurface.ACTIVITY)
            val revision = security.state.value.authenticationRevision
            assertFalse(vm.securitySetProtectedSurface(ProtectedSurface.ACTIVITY, false, revision))
            assertTrue(ProtectedSurface.ACTIVITY in security.state.value.protectedSurfaces)
            assertTrue(vm.securitySetProtectedSurface(ProtectedSurface.ACTIVITY, false, revision) { request, verify, _ ->
                assertEquals(ProtectedSurface.ACTIVITY, request.surface)
                verify("1234"); awaitCancellation()
            })
            assertFalse(ProtectedSurface.ACTIVITY in security.state.value.protectedSurfaces)
            assertFalse(vm.securitySetProtectedSurface(ProtectedSurface.ACTIVITY, true, revision))
            assertTrue(vm.securitySetProtectedSurface(ProtectedSurface.ACTIVITY, true,
                security.state.value.authenticationRevision) { _, _, _ -> error("enabling does not require fresh auth") })
        } finally { vm.close(); security.close() }
    }

    @Test fun `unselected controls work without a prompt and absent authority cannot authorize control`() = runTest {
        val security = testSecurity(setOf(ProtectedSurface.ACTIVITY))
        val controller = ControlRecorder()
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, protectionController = controller))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        try {
            advanceUntilIdle()
            assertTrue(vm.performGuardActionForGeneration(7L, GuardPrimaryAction.TurnOn) { _, _, _ ->
                error("control is not selected")
            } is ProtectionCommandResult.Completed)
            factories.value = FakeOwnerFactory(8L, protectionController = controller)
            advanceUntilIdle()
            assertEquals(ProtectionCommandResult.Cancelled, vm.performGuardActionForGeneration(8L, GuardPrimaryAction.TurnOn))
            assertEquals(listOf("start"), controller.events)
        } finally { vm.close(); security.close() }
    }

    @Test fun `switching filters requires a fresh grant before every dispatch`() = runTest {
        val security = testSecurity(setOf(ProtectedSurface.FILTERS))
        val port = SwitchPort { target -> com.lavasec.app.product.FilterProfileSwitchResult.Applied(target, 2L) }
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, switchPort = port))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        try {
            advanceUntilIdle()
            security.unlock("1234", ProtectedSurface.FILTERS)
            assertFalse(vm.requestFilterProfileApply("extra", 7L) { request, _, cancel ->
                assertEquals(ProtectedSurface.FILTERS, request.surface)
                assertTrue(port.requests.isEmpty())
                cancel()
            })
            assertTrue(port.requests.isEmpty())
            for (target in listOf("extra", "core")) {
                val before = port.requests.toList()
                assertTrue(vm.requestFilterProfileApply(target, 7L) { request, verify, _ ->
                    assertEquals(ProtectedSurface.FILTERS, request.surface)
                    assertEquals(before, port.requests)
                    verify("1234")
                    awaitCancellation()
                })
            }
            assertEquals(listOf("extra", "core"), port.requests)
        } finally { vm.close(); security.close() }
    }

    @Test fun `filter switch refuses stale callbacks and a graph retired during authentication`() = runTest {
        val security = testSecurity(setOf(ProtectedSurface.FILTERS))
        val port = SwitchPort { target -> com.lavasec.app.product.FilterProfileSwitchResult.Applied(target, 2L) }
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, switchPort = port))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        try {
            advanceUntilIdle()
            assertFalse(vm.requestFilterProfileApply("extra", 6L) { _, _, _ -> error("Stale callback must not prompt") })
            val shown = CompletableDeferred<Unit>()
            val action = async {
                vm.requestFilterProfileApply("extra", 7L) { _, _, _ -> shown.complete(Unit); awaitCancellation() }
            }
            shown.await()
            factories.value = FakeOwnerFactory(8L, security = security, switchPort = port)
            advanceUntilIdle()
            assertFalse(action.await())
            assertTrue(port.requests.isEmpty())
        } finally { vm.close(); security.close() }
    }

    @Test fun `filter switch requires readable authority but does not prompt for an unselected choice`() = runTest {
        val security = testSecurity(emptySet())
        val port = SwitchPort { target -> com.lavasec.app.product.FilterProfileSwitchResult.Applied(target, 2L) }
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L, switchPort = port))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        try {
            advanceUntilIdle()
            assertFalse(vm.requestFilterProfileApply("extra", 7L))
            assertTrue(port.requests.isEmpty())
            factories.value = FakeOwnerFactory(8L, security = security, switchPort = port)
            advanceUntilIdle()
            assertTrue(vm.requestFilterProfileApply("extra", 8L) { _, _, _ -> error("Unselected choice must not prompt") })
            assertEquals(listOf("extra"), port.requests)
        } finally { vm.close(); security.close() }
    }

    @Test fun `standalone domain staging edits the active filter draft and captures a review`() = runTest {
        val security = testSecurity(setOf(ProtectedSurface.FILTERS))
        val fixture = reviewedLibrary()
        val active = fixture.library.value!!.activeProfileId
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, libraryOwner = fixture.owner, libraryDraft = fixture.library))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        try {
            advanceUntilIdle()
            security.unlock("1234", ProtectedSurface.FILTERS)
            // A stale render refuses without touching the draft.
            assertEquals(DomainStageOutcome.Unavailable, vm.stageDomainAction("blocked.example", ManualRuleTarget.BLOCKED, 6L) { _, _, _ -> error("Stale render") })
            // A cancelled authorization stages nothing.
            assertEquals(DomainStageOutcome.Unavailable, vm.stageDomainAction("blocked.example", ManualRuleTarget.BLOCKED, 7L) { _, _, cancel -> cancel() })
            // The live render authenticates once, stages onto the ACTIVE filter, and captures the review.
            var prompts = 0
            assertEquals(DomainStageOutcome.Staged(active), vm.stageDomainAction("blocked.example", ManualRuleTarget.BLOCKED, 7L) { _, verify, _ ->
                prompts++; verify("1234"); awaitCancellation()
            })
            assertEquals(1, prompts)
            assertEquals(setOf("blocked.example"), fixture.owner.draftFor(active)!!.manualRules.blockedDomains)
            assertTrue(vm.reviewedFilterWeakensProtection(active, 7L) != null)
        } finally { vm.close(); security.close() }
    }

    @Test fun `standalone domain staging reports the exact rule refusal`() = runTest {
        val security = testSecurity(setOf(ProtectedSurface.FILTERS))
        val fixture = reviewedLibrary()
        val active = fixture.library.value!!.activeProfileId
        // Persist a blocked domain so re-staging it is an ALREADY_BLOCKED refusal, not a draft conflict.
        fixture.owner.beginEdit(active)
        fixture.owner.addDomainToDraft(active, "blocked.example", ManualRuleTarget.BLOCKED)
        advanceUntilIdle()
        assertTrue(fixture.owner.commitDraft(active))
        advanceUntilIdle()
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, libraryOwner = fixture.owner, libraryDraft = fixture.library))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        try {
            advanceUntilIdle()
            security.unlock("1234", ProtectedSurface.FILTERS)
            assertEquals(
                DomainStageOutcome.RuleRejected(ManualRuleRejection.ALREADY_BLOCKED),
                vm.stageDomainAction("blocked.example", ManualRuleTarget.BLOCKED, 7L) { _, verify, _ -> verify("1234"); awaitCancellation() },
            )
        } finally { vm.close(); security.close() }
    }

    private suspend fun TestScope.stageVisibleDomain(
        vm: MainProductViewModel,
        nativeHost: com.lavasec.app.product.NativeDomainActionHost,
        generation: Long,
        present: com.lavasec.app.security.NativeSecurityPresenter,
    ): com.lavasec.app.product.domain.DomainActionReview {
        val request = async {
            vm.requestDomainAction("example.com", com.lavasec.app.presentation.activity.DomainActionUi.Block, generation, present)
        }
        advanceUntilIdle()
        val review = (nativeHost.reviewBridge.stateFlow.first { it is com.lavasec.app.product.NativeDomainActionReviewState.AwaitingVisibility }
            as com.lavasec.app.product.NativeDomainActionReviewState.AwaitingVisibility).review
        assertTrue(vm.markDomainReviewVisible(review.id, generation))
        assertEquals(com.lavasec.app.product.domain.DomainActionRequestResult.Staged(review), request.await())
        return review
    }

    @Test fun `standalone domain staging is fresh and confirmation reuses its grant`() = runTest {
        val security = testSecurity(setOf(ProtectedSurface.FILTERS))
        val nativeHost = host()
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, domainHost = nativeHost, dispatcher = StandardTestDispatcher(testScheduler)))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        try {
            advanceUntilIdle()
            security.unlock("1234", ProtectedSurface.FILTERS)
            assertEquals(null, vm.requestDomainAction("example.com", com.lavasec.app.presentation.activity.DomainActionUi.Block, 6L) { _, _, _ -> error("Stale render") })
            assertEquals(null, vm.requestDomainAction("example.com", com.lavasec.app.presentation.activity.DomainActionUi.Block, 7L) { _, _, cancel -> cancel() })
            var prompts = 0
            val review = stageVisibleDomain(vm, nativeHost, 7L) { _, verify, _ ->
                prompts++; verify("1234"); awaitCancellation()
            }
            val result = vm.confirmDomainAction(review.id, 7L) { _, _, _ -> error("Reuse staging grant") }
            assertTrue(result is com.lavasec.app.product.domain.DomainActionConfirmResult.Applied)
            assertEquals(1, prompts)
        } finally { vm.close(); security.close() }
    }

    @Test fun `domain confirmation can be cancelled while local review teardown needs no grant`() = runTest {
        val security = testSecurity(setOf(ProtectedSurface.FILTERS))
        val nativeHost = host()
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, domainHost = nativeHost, dispatcher = StandardTestDispatcher(testScheduler)))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        try {
            advanceUntilIdle()
            val review = stageVisibleDomain(vm, nativeHost, 7L) { _, verify, _ ->
                verify("1234"); awaitCancellation()
            }
            security.resetAuthenticationTurn()
            assertEquals(null, vm.confirmDomainAction(review.id, 7L) { _, _, cancel -> cancel() })
            assertTrue(vm.cancelDomainAction(review.id, 7L))
        } finally { vm.close(); security.close() }
    }

    @Test fun `old domain review callbacks cannot act on a replacement owner with the same review id`() = runTest {
        val firstSecurity = testSecurity(emptySet())
        val secondSecurity = testSecurity(emptySet())
        val firstHost = host()
        val secondHost = host()
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = firstSecurity, domainHost = firstHost, dispatcher = StandardTestDispatcher(testScheduler)))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        val noPrompt: com.lavasec.app.security.NativeSecurityPresenter = { _, _, _ -> error("Unselected choice") }
        try {
            advanceUntilIdle()
            val first = stageVisibleDomain(vm, firstHost, 7L, noPrompt)
            factories.value = FakeOwnerFactory(8L, security = secondSecurity, domainHost = secondHost, dispatcher = StandardTestDispatcher(testScheduler))
            advanceUntilIdle()
            val second = stageVisibleDomain(vm, secondHost, 8L, noPrompt)
            assertEquals(first.id, second.id)
            assertFalse(vm.markDomainReviewVisible(first.id, 7L))
            assertFalse(vm.cancelDomainReviewBeforeVisible(first.id, 7L))
            assertFalse(vm.cancelDomainAction(first.id, 7L))
            assertEquals(null, vm.confirmDomainAction(first.id, 7L, noPrompt))
            assertTrue(vm.confirmDomainAction(second.id, 8L, noPrompt) is com.lavasec.app.product.domain.DomainActionConfirmResult.Applied)
        } finally { vm.close(); firstSecurity.close(); secondSecurity.close() }
    }

    @Test fun `a dismissed biometric prompt is recorded as an authentication cancellation`() = runTest {
        val biometric = object : com.lavasec.app.security.BiometricAuthenticatorPort {
            override fun availability() =
                com.lavasec.app.foundation.biometrics.BiometricAvailability.Available(
                    com.lavasec.app.foundation.biometrics.BiometricKind.FINGERPRINT,
                )

            override fun isLockedOut() = false

            override suspend fun authenticate(purpose: String) =
                com.lavasec.app.foundation.biometrics.BiometricPromptResult.Cancelled

            override fun cancel() = Unit
        }
        val security = testSecurity(setOf(ProtectedSurface.FILTERS), biometric)
        assertTrue(security.setBiometrics(true))
        val fixture = reviewedLibrary()
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, libraryOwner = fixture.owner, libraryDraft = fixture.library))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        val id = fixture.library.value!!.activeProfileId
        try {
            advanceUntilIdle(); vm.openFilterDetail(id)
            // The owner returns an ordinary `false`, but the tri-state records the cancellation so
            // the bridge can report `cancelled` instead of a `*-refused` error.
            assertEquals(null, vm.addBlockedDomainToDraft(id, "blocked.example", 7L) { _, _, _ ->
                error("the biometric prompt must not fall back to passcode")
            })
            assertTrue(vm.consumeAuthenticationCancellation())
            assertFalse(vm.consumeAuthenticationCancellation())
            assertTrue(fixture.writes.isEmpty())
        } finally { vm.close(); security.close(); fixture.owner.close() }
    }

    @Test fun `single deletion requires fresh authorization despite an editing grant`() = runTest {
        val security = testSecurity(setOf(ProtectedSurface.FILTERS))
        val fixture = reviewedLibrary()
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, libraryOwner = fixture.owner, libraryDraft = fixture.library))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        val id = fixture.library.value!!.activeProfileId
        try {
            advanceUntilIdle(); vm.openFilterDetail(id)
            assertTrue(vm.beginFilterEdit(id, 7L) { _, verify, _ -> verify("1234"); awaitCancellation() })
            var prompts = 0
            assertFalse(vm.deleteFilters(setOf("filter-essential"), 7L, fresh = true) { _, _, cancel -> prompts++; cancel() })
            assertEquals(1, prompts)
            assertTrue(fixture.writes.isEmpty())
            vm.discardFilterDraft(id)
            assertTrue(vm.deleteFilters(setOf("filter-essential"), 7L, fresh = false) { _, _, _ ->
                error("Native batch deletion reuses the edit grant")
            })
        } finally { vm.close(); security.close(); fixture.owner.close() }
    }

    @Test fun `every draft and library mutation refuses stale or cancelled authorization`() = runTest {
        val security = testSecurity(setOf(ProtectedSurface.FILTERS))
        val fixture = reviewedLibrary()
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, libraryOwner = fixture.owner, libraryDraft = fixture.library))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        val id = fixture.library.value!!.activeProfileId
        try {
            advanceUntilIdle(); vm.openFilterDetail(id)
            assertTrue(vm.beginFilterEdit(id, 7L) { _, verify, _ -> verify("1234"); awaitCancellation() })
            security.resetAuthenticationTurn()
            for (generation in listOf(6L, 7L)) {
                var prompts = 0
                val deny: com.lavasec.app.security.NativeSecurityPresenter = { _, _, cancel -> prompts++; cancel() }
                assertEquals(null, vm.addBlockedDomainToDraft(id, "blocked.example", generation, deny))
                assertEquals(null, vm.addAllowedExceptionToDraft(id, "allowed.example", generation, deny))
                assertFalse(vm.removeDomainFromDraft(id, "blocked.example", com.lavasec.app.foundation.filter.ManualRuleTarget.BLOCKED, generation, deny))
                assertFalse(vm.toggleSourceInDraft(id, "blocklistproject-basic", false, generation, deny))
                assertFalse(vm.createFilter("Another", null, generation, deny))
                assertFalse(vm.renameFilter(id, "Renamed", generation, present = deny))
                assertFalse(vm.deleteFilters(setOf("filter-essential"), generation, present = deny))
                assertFalse(vm.restoreDefaultFilters(generation, deny))
                assertEquals(if (generation == 7L) 8 else 0, prompts)
                assertTrue(fixture.writes.isEmpty())
                assertEquals(fixture.library.value!!.profile(id), fixture.owner.draftFor(id))
            }
        } finally { vm.close(); security.close(); fixture.owner.close() }
    }

    @Test fun `draft mutation refuses changed presentation while library rename reuses current grant`() = runTest {
        val security = testSecurity(setOf(ProtectedSurface.FILTERS))
        val fixture = reviewedLibrary()
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, libraryOwner = fixture.owner, libraryDraft = fixture.library))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        val id = fixture.library.value!!.activeProfileId
        try {
            advanceUntilIdle(); vm.openFilterDetail(id)
            vm.beginFilterEdit(id, 7L) { _, verify, _ -> verify("1234"); awaitCancellation() }
            security.resetAuthenticationTurn()
            assertEquals(null, vm.addBlockedDomainToDraft(id, "stale.example", 7L) { _, verify, _ ->
                vm.closeFilterDetail(); vm.openFilterDetail(id)
                verify("1234"); awaitCancellation()
            })
            assertTrue(fixture.owner.draftFor(id)!!.manualRules.blockedDomains.isEmpty())
            assertTrue(vm.renameFilter(id, "Renamed", 7L) { _, _, _ -> error("Reuse editing grant") })
            assertEquals("Renamed", fixture.library.value!!.profile(id)!!.name)
        } finally { vm.close(); security.close(); fixture.owner.close() }
    }

    @Test fun `editing back to reviewed contents or recreating a discarded draft cannot revive review`() = runTest {
        val security = testSecurity(setOf(ProtectedSurface.FILTERS))
        val fixture = reviewedLibrary()
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, libraryOwner = fixture.owner, libraryDraft = fixture.library))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        val id = fixture.library.value!!.activeProfileId
        val cached: com.lavasec.app.security.NativeSecurityPresenter = { _, _, _ -> error("Reuse editing grant") }
        val authenticate: com.lavasec.app.security.NativeSecurityPresenter = { _, verify, _ -> verify("1234"); awaitCancellation() }
        try {
            advanceUntilIdle(); vm.openFilterDetail(id)
            vm.beginFilterEdit(id, 7L, authenticate)
            vm.addBlockedDomainToDraft(id, "reviewed.example", 7L, cached)
            assertTrue(vm.requestFilterReview(id, 7L, authenticate))
            assertTrue(vm.openFilterReview(id, 7L, cached))
            assertFalse(vm.openFilterReview(id, 8L, cached))
            vm.addAllowedExceptionToDraft(id, "temporary.example", 7L, cached)
            assertTrue(vm.removeDomainFromDraft(id, "temporary.example", com.lavasec.app.foundation.filter.ManualRuleTarget.ALLOWED, 7L, cached))
            assertFalse(vm.commitFilterReview(id, 7L, present = cached))
            assertTrue(vm.requestFilterReview(id, 7L, authenticate))
            vm.discardFilterDraft(id)
            assertTrue(vm.beginFilterEdit(id, 7L, cached))
            vm.addBlockedDomainToDraft(id, "reviewed.example", 7L, cached)
            assertFalse(vm.commitFilterReview(id, 7L, present = cached))
            assertTrue(fixture.writes.isEmpty())
        } finally { vm.close(); security.close(); fixture.owner.close() }
    }

    @Test fun `edit refuses stale or cancelled authentication and changed presentation`() = runTest {
        val security = testSecurity(setOf(ProtectedSurface.FILTERS))
        val fixture = reviewedLibrary()
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, libraryOwner = fixture.owner, libraryDraft = fixture.library))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        val id = fixture.library.value!!.activeProfileId
        try {
            advanceUntilIdle(); vm.openFilterDetail(id)
            assertFalse(vm.beginFilterEdit(id, 6L) { _, _, _ -> error("Stale generation") })
            assertFalse(vm.beginFilterEdit(id, 7L) { _, _, cancel -> cancel() })
            assertEquals(null, fixture.owner.draftFor(id))
            assertFalse(vm.beginFilterEdit(id, 7L) { _, verify, _ ->
                vm.closeFilterDetail(); vm.openFilterDetail(id)
                verify("1234"); awaitCancellation()
            })
            assertEquals(null, fixture.owner.draftFor(id))
        } finally { vm.close(); security.close(); fixture.owner.close() }
    }

    @Test fun `edit coalesces concurrent prompts and retained draft survives repeated edit`() = runTest {
        val security = testSecurity(setOf(ProtectedSurface.FILTERS))
        val fixture = reviewedLibrary()
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, libraryOwner = fixture.owner, libraryDraft = fixture.library))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        val id = fixture.library.value!!.activeProfileId
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            advanceUntilIdle(); vm.openFilterDetail(id)
            val first = async { vm.beginFilterEdit(id, 7L) { _, verify, _ ->
                entered.complete(Unit); release.await(); verify("1234"); awaitCancellation()
            } }
            entered.await()
            assertFalse(vm.beginFilterEdit(id, 7L) { _, _, _ -> error("Duplicate prompt") })
            release.complete(Unit)
            assertTrue(first.await())
            vm.addBlockedDomainToDraft(id, "retained.example", 7L) { _, _, _ -> error("Use editing grant") }
            assertTrue(vm.beginFilterEdit(id, 7L) { _, _, _ -> error("Reuse editing grant") })
            assertEquals(setOf("retained.example"), fixture.owner.draftFor(id)!!.manualRules.blockedDomains)
        } finally { release.complete(Unit); vm.close(); security.close(); fixture.owner.close() }
    }

    @Test fun `review witness detects weakening before the async UI projection catches up`() = runTest {
        val security = testSecurity(setOf(ProtectedSurface.FILTERS))
        val fixture = reviewedLibrary()
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, libraryOwner = fixture.owner, libraryDraft = fixture.library))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        val id = fixture.library.value!!.activeProfileId
        try {
            advanceUntilIdle()
            vm.openFilterDetail(id)
            assertTrue(vm.beginFilterEdit(id, 7L) { _, verify, _ -> verify("1234"); awaitCancellation() })
            vm.addAllowedExceptionToDraft(id, "trusted.example", 7L) { _, _, _ -> error("Use editing grant") }
            assertFalse(vm.finishUnchangedFilterEdit(id, 7L))
            assertTrue(vm.requestFilterReview(id, 7L) { _, verify, _ -> verify("1234"); awaitCancellation() })
            assertEquals(true, vm.reviewedFilterWeakensProtection(id, 7L))
            assertEquals(null, vm.reviewedFilterWeakensProtection(id, 8L))
            assertTrue(fixture.writes.isEmpty())
            vm.discardFilterDraft(id)
            assertEquals(null, vm.reviewedFilterWeakensProtection(id, 7L))
            assertTrue(vm.savedFiltersForSnapshot(8L).isEmpty())
            assertTrue(vm.beginFilterEdit(id, 7L) { _, _, _ -> error("Use editing grant") })
            assertFalse(vm.finishUnchangedFilterEdit(id, 8L))
            assertTrue(vm.finishUnchangedFilterEdit(id, 7L))
        } finally { vm.close(); security.close(); fixture.owner.close() }
    }

    @Test fun `typed Save discovers both full domain lists on the authenticated original draft without mutation`() = runTest {
        for (blocked in listOf(true, false)) {
            val nativeLimits = MutableStateFlow(com.lavasec.app.foundation.subscription.FeatureLimits.PLUS)
            val security = testSecurity(setOf(ProtectedSurface.FILTERS))
            val fixture = reviewedLibrary(limits = { nativeLimits.value })
            val id = fixture.library.value!!.activeProfileId
            val capacity = (0 until 26).map { "capacity-$it.example" }.toSet()
            val rules = if (blocked) com.lavasec.app.foundation.filter.ManualDomainRules(blockedDomains = capacity)
                else com.lavasec.app.foundation.filter.ManualDomainRules(allowedDomains = capacity)
            fixture.library.value = fixture.library.value!!.withProfile(fixture.library.value!!.profile(id)!!.withManualRules(rules))
            val saved = fixture.library.value
            val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
                security = security, libraryOwner = fixture.owner, libraryDraft = fixture.library,
                featureLimits = nativeLimits))
            val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
            val authenticate: com.lavasec.app.security.NativeSecurityPresenter = { _, verify, _ -> verify("1234"); awaitCancellation() }
            try {
                advanceUntilIdle(); vm.openFilterDetail(id); assertTrue(vm.beginFilterEdit(id, 7L, authenticate))
                val staged = if (blocked) vm.addAllowedExceptionToDraft(id, "staged.example", 7L, authenticate)
                    else vm.addBlockedDomainToDraft(id, "staged.example", 7L, authenticate)
                assertTrue(staged is com.lavasec.app.foundation.filter.ManualRuleEditResult.Applied)
                val draft = fixture.owner.draftFor(id); val lease = fixture.owner.draftLease(id)
                // The edit was admitted under Plus; the authenticated Save discovers the lapse
                // against the same native draft, lease and current Free limits.
                nativeLimits.value = com.lavasec.app.foundation.subscription.FeatureLimits.FREE
                val attempt = vm.requestFilterReviewAttempt(id, 7L, authenticate)
                val expected = if (blocked) com.lavasec.app.foundation.filter.FilterDraftReviewRejection.BLOCKED_DOMAIN_LIMIT
                    else com.lavasec.app.foundation.filter.FilterDraftReviewRejection.ALLOWED_DOMAIN_LIMIT
                assertEquals(FilterLibraryOwner.ReviewAttempt.Rejected(expected), attempt)
                assertNull(vm.reviewedFilterWeakensProtection(id, 7L))
                assertSame(draft, fixture.owner.draftFor(id)); assertEquals(lease, fixture.owner.draftLease(id))
                assertEquals(saved, fixture.library.value); assertTrue(fixture.writes.isEmpty())
                nativeLimits.value = com.lavasec.app.foundation.subscription.FeatureLimits.PLUS
                assertTrue(vm.requestFilterReviewAttempt(id, 7L, authenticate) is FilterLibraryOwner.ReviewAttempt.Captured)
                assertEquals(saved, fixture.library.value); assertTrue(fixture.writes.isEmpty())
            } finally { vm.close(); security.close(); fixture.owner.close() }
        }
    }

    @Test fun `typed capacity cannot escape cancelled authentication changed draft lease graph or revoked grant`() = runTest {
        for (boundary in listOf("cancel", "draft", "lease", "graph", "grant")) {
            val nativeLimits = MutableStateFlow(com.lavasec.app.foundation.subscription.FeatureLimits.PLUS)
            val security = testSecurity(setOf(ProtectedSurface.FILTERS))
            val fixture = reviewedLibrary(limits = { nativeLimits.value })
            val id = fixture.library.value!!.activeProfileId
            fixture.library.value = fixture.library.value!!.withProfile(fixture.library.value!!.profile(id)!!.withManualRules(
                com.lavasec.app.foundation.filter.ManualDomainRules(blockedDomains = (0 until 26).map { "capacity-$it.example" }.toSet())))
            val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
                security = security, libraryOwner = fixture.owner, libraryDraft = fixture.library,
                featureLimits = nativeLimits))
            val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            try {
                advanceUntilIdle(); vm.openFilterDetail(id)
                assertTrue(vm.beginFilterEdit(id, 7L) { _, verify, _ -> verify("1234"); awaitCancellation() })
                assertTrue(vm.addAllowedExceptionToDraft(id, "staged.example", 7L) { _, _, _ ->
                    error("Use the authenticated editing grant") } is com.lavasec.app.foundation.filter.ManualRuleEditResult.Applied)
                nativeLimits.value = com.lavasec.app.foundation.subscription.FeatureLimits.FREE
                val pending = async { vm.requestFilterReviewAttempt(id, 7L) { _, verify, cancel ->
                    entered.complete(Unit); release.await()
                    if (boundary == "cancel") cancel()
                    else { verify("1234"); if (boundary == "grant") security.resetAuthenticationTurn() }
                    awaitCancellation()
                } }
                entered.await()
                when (boundary) {
                    "draft" -> assertTrue(fixture.owner.removeDomainFromDraft(id, "capacity-0.example",
                        com.lavasec.app.foundation.filter.ManualRuleTarget.BLOCKED))
                    "lease" -> { fixture.owner.discardDraft(id); fixture.owner.beginEdit(id) }
                    "graph" -> { factories.value = FakeOwnerFactory(8L); advanceUntilIdle() }
                }
                release.complete(Unit)
                assertEquals(FilterLibraryOwner.ReviewAttempt.Unavailable, pending.await())
                assertTrue(fixture.writes.isEmpty()); assertNull(vm.reviewedFilterWeakensProtection(id, 7L))
            } finally { release.complete(Unit); vm.close(); security.close(); fixture.owner.close() }
        }
    }

    @Test fun `missing security and an old graph never provide typed capacity metadata`() = runTest {
        val fixture = reviewedLibrary()
        val id = fixture.library.value!!.activeProfileId
        fixture.library.value = fixture.library.value!!.withProfile(fixture.library.value!!.profile(id)!!.withManualRules(
            com.lavasec.app.foundation.filter.ManualDomainRules(allowedDomains = (0 until 26).map { "capacity-$it.example" }.toSet())))
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            libraryOwner = fixture.owner, libraryDraft = fixture.library))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        try {
            advanceUntilIdle(); vm.openFilterDetail(id); assertTrue(fixture.owner.beginEdit(id))
            for (generation in listOf(6L, 7L)) assertEquals(FilterLibraryOwner.ReviewAttempt.Unavailable,
                vm.requestFilterReviewAttempt(id, generation) { _, _, _ -> error("Unavailable authority cannot present") })
            assertTrue(fixture.writes.isEmpty())
        } finally { vm.close(); fixture.owner.close() }
    }

    @Test fun `save authenticates freshly before review and exact confirmation reuses that grant`() = runTest {
        val security = testSecurity(setOf(ProtectedSurface.FILTERS))
        val fixture = reviewedLibrary()
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, libraryOwner = fixture.owner, libraryDraft = fixture.library))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        val id = fixture.library.value!!.activeProfileId
        try {
            advanceUntilIdle()
            vm.openFilterDetail(id)
            assertTrue(vm.beginFilterEdit(id, 7L) { _, verify, _ -> verify("1234"); awaitCancellation() })
            vm.addBlockedDomainToDraft(id, "reviewed.example", 7L) { _, _, _ -> error("Use editing grant") }
            security.unlock("1234", ProtectedSurface.FILTERS)
            assertFalse(vm.requestFilterReview(id, 7L) { _, _, cancel -> cancel() })
            assertFalse(vm.commitFilterReview(id, 7L, onAdmitted = { error("Unreviewed confirmation must not open progress") }) { _, _, _ -> error("No review") })
            assertEquals(0, fixture.writes.size)
            var prompts = 0
            assertTrue(vm.requestFilterReview(id, 7L) { request, verify, _ ->
                prompts++
                assertEquals(ProtectedSurface.FILTERS, request.surface)
                assertTrue(fixture.writes.isEmpty())
                verify("1234"); awaitCancellation()
            })
            var progressAdmitted = false
            assertTrue(vm.commitFilterReview(id, 7L, onAdmitted = {
                assertTrue(fixture.writes.isEmpty())
                progressAdmitted = true
            }) { _, _, _ -> error("Confirmation must reuse the save grant") })
            assertTrue(progressAdmitted)
            assertEquals(1, prompts)
            assertEquals(1, fixture.writes.size)
            assertEquals(setOf("reviewed.example"), fixture.library.value!!.profile(id)!!.manualRules.blockedDomains)
        } finally { vm.close(); security.close(); fixture.owner.close() }
    }

    @Test fun `review cannot be admitted after the displayed filter changes during authentication`() = runTest {
        val security = testSecurity(setOf(ProtectedSurface.FILTERS))
        val fixture = reviewedLibrary()
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, libraryOwner = fixture.owner, libraryDraft = fixture.library))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        val id = fixture.library.value!!.activeProfileId
        try {
            advanceUntilIdle(); vm.openFilterDetail(id); vm.beginFilterEdit(id, 7L) { _, verify, _ -> verify("1234"); awaitCancellation() }
            vm.addBlockedDomainToDraft(id, "reviewed.example", 7L) { _, _, _ -> error("Use editing grant") }
            assertFalse(vm.requestFilterReview(id, 6L) { _, _, _ -> error("Stale generation") })
            assertFalse(vm.requestFilterReview(id, 7L) { _, verify, _ ->
                vm.closeFilterDetail(); vm.openFilterDetail(id)
                verify("1234"); awaitCancellation()
            })
            assertFalse(vm.commitFilterReview(id, 7L) { _, _, _ -> error("Retired presentation") })
            assertTrue(fixture.writes.isEmpty())
        } finally { vm.close(); security.close(); fixture.owner.close() }
    }

    @Test fun `a queued confirmation cannot consume the same review twice`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val security = testSecurity(emptySet())
        val fixture = reviewedLibrary { entered.complete(Unit); release.await() }
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, libraryOwner = fixture.owner, libraryDraft = fixture.library))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        val id = fixture.library.value!!.activeProfileId
        try {
            advanceUntilIdle(); vm.openFilterDetail(id); vm.beginFilterEdit(id, 7L) { _, verify, _ -> verify("1234"); awaitCancellation() }
            vm.addBlockedDomainToDraft(id, "reviewed.example", 7L) { _, _, _ -> error("Use editing grant") }
            assertTrue(vm.requestFilterReview(id, 7L) { _, _, _ -> error("Unselected choice") })
            val first = async { vm.commitFilterReview(id, 7L) { _, _, _ -> error("Unselected choice") } }
            entered.await()
            assertFalse(vm.commitFilterReview(id, 7L, onAdmitted = { error("Duplicate progress") }) { _, _, _ -> error("Duplicate prompt") })
            release.complete(Unit)
            assertTrue(first.await())
            assertEquals(1, fixture.writes.size)
            assertTrue(fixture.owner.state.value is FilterLibraryOwner.State.Succeeded)
        } finally { release.complete(Unit); vm.close(); security.close(); fixture.owner.close() }
    }

    @Test fun `retry authorization cannot switch to a later library operation`() = runTest {
        val security = testSecurity(setOf(ProtectedSurface.FILTERS))
        val fixture = reviewedLibrary(failFirst = true)
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, libraryOwner = fixture.owner, libraryDraft = fixture.library))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        val id = fixture.library.value!!.activeProfileId
        try {
            advanceUntilIdle(); vm.openFilterDetail(id); vm.beginFilterEdit(id, 7L) { _, verify, _ -> verify("1234"); awaitCancellation() }
            vm.addBlockedDomainToDraft(id, "reviewed.example", 7L) { _, _, _ -> error("Use editing grant") }
            assertTrue(vm.requestFilterReview(id, 7L) { _, verify, _ -> verify("1234"); awaitCancellation() })
            assertFalse(vm.commitFilterReview(id, 7L) { _, _, _ -> error("Use save grant") })
            security.resetAuthenticationTurn()
            assertFalse(vm.retryFilterPreparation(7L) { _, verify, _ ->
                val other = fixture.library.value!!.profiles.first { it.id != id }.id
                assertTrue(fixture.owner.renameFilter(other, "Other filter"))
                verify("1234"); awaitCancellation()
            })
            assertEquals(2, fixture.writes.size)
            assertTrue(fixture.library.value!!.profile(id)!!.manualRules.blockedDomains.isEmpty())
        } finally { vm.close(); security.close(); fixture.owner.close() }
    }

    @Test fun `library session edits stage without writing and commit only the confirmed batch`() = runTest {
        val security = testSecurity(setOf(ProtectedSurface.FILTERS))
        // Plus limits leave room for the staged addition beside the seeded defaults.
        val fixture = reviewedLibrary(
            limits = { com.lavasec.app.foundation.subscription.FeatureLimits.PLUS },
        )
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, libraryOwner = fixture.owner, libraryDraft = fixture.library))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        val active = fixture.library.value!!.activeProfileId
        val other = fixture.library.value!!.profiles.first { it.id != active }.id
        val authenticate: com.lavasec.app.security.NativeSecurityPresenter =
            { _, verify, _ -> verify("1234"); awaitCancellation() }
        val reuse: com.lavasec.app.security.NativeSecurityPresenter =
            { _, _, _ -> error("Session commands must reuse the editing grant") }
        try {
            advanceUntilIdle()
            assertTrue(vm.beginLibraryEditing(7L, authenticate) is FilterLibraryEditOutcome.Completed)
            // Create and rename are draft-only: the saved library and the durable writer are untouched.
            assertTrue(
                vm.stageLibraryCreate("New filter", null, 7L, reuse) is FilterLibraryEditOutcome.Created,
            )
            assertTrue(vm.toggleLibraryDeletion(other, 7L, reuse) is FilterLibraryEditOutcome.Completed)
            assertEquals(0, fixture.writes.size)
            assertEquals(other, fixture.library.value!!.profile(other)!!.id)
            // Confirmation commits the whole validated batch exactly once.
            assertTrue(
                vm.commitLibraryEdit(setOf(other), 7L, reuse) is FilterLibraryEditOutcome.Completed,
            )
            assertEquals(1, fixture.writes.size)
            assertNull(fixture.library.value!!.profile(other))
            // Snapshot projection reflects the reset session and is generation-fenced.
            assertFalse(vm.libraryEditSessionForSnapshot(7L)!!.isEditing)
            assertNull(vm.libraryEditSessionForSnapshot(8L))
            // Cancel is local teardown and never authenticates.
            assertTrue(vm.cancelLibraryEditing(7L))
            assertFalse(vm.cancelLibraryEditing(8L))
        } finally { vm.close(); security.close(); fixture.owner.close() }
    }

    @Test fun `library session refuses stale generation or cancelled authorization without staging`() = runTest {
        val security = testSecurity(setOf(ProtectedSurface.FILTERS))
        val fixture = reviewedLibrary()
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, libraryOwner = fixture.owner, libraryDraft = fixture.library))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        val other = fixture.library.value!!.profiles[1].id
        val deny: com.lavasec.app.security.NativeSecurityPresenter = { _, _, cancel -> cancel() }
        try {
            advanceUntilIdle()
            assertNull(vm.beginLibraryEditing(6L, deny))
            assertNull(vm.beginLibraryEditing(7L, deny))
            assertTrue(vm.toggleLibraryDeletion(other, 7L, deny) == null)
            assertFalse(vm.libraryEditSessionForSnapshot(7L)!!.isEditing)
            assertTrue(fixture.writes.isEmpty())
        } finally { vm.close(); security.close(); fixture.owner.close() }
    }

    @Test fun `creation presents an owned draft before returning and writes only a freshly reviewed first save`() = runTest {
        val security = testSecurity(setOf(ProtectedSurface.FILTERS))
        val fixture = reviewedLibrary(limits = { com.lavasec.app.foundation.subscription.FeatureLimits.PLUS })
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, libraryOwner = fixture.owner, libraryDraft = fixture.library,
            featureLimits = MutableStateFlow(com.lavasec.app.foundation.subscription.FeatureLimits.PLUS)))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        var prompts = 0
        val authenticate: com.lavasec.app.security.NativeSecurityPresenter =
            { _, verify, _ -> prompts++; verify("1234"); awaitCancellation() }
        val reuse: com.lavasec.app.security.NativeSecurityPresenter = { _, _, _ -> error("Use the editing grant") }
        try {
            advanceUntilIdle()
            val initial = fixture.library.value
            vm.beginLibraryEditing(7L, authenticate)
            val id = (vm.beginCreatingFilter(null, null, 7L, reuse) as FilterLibraryEditOutcome.Created).filterId
            assertEquals(id, vm.state.value.newFilter!!.id)
            assertEquals(id, vm.state.value.selectedFilterId)
            assertEquals(id, vm.state.value.filterDetail!!.filterId)
            assertTrue(vm.state.value.filterDetail!!.draftHasChanges)
            assertEquals(initial, fixture.library.value)
            assertTrue(fixture.writes.isEmpty())
            assertTrue(vm.cancelLibraryEditing(7L))
            assertNotNull(fixture.owner.newFilter.value)
            assertTrue(vm.renameFilter(id, "My new filter", 7L, emoji = "🌿", present = reuse))
            assertFalse(vm.commitFilterReview(id, 7L, present = reuse))
            assertTrue(vm.requestFilterReview(id, 7L, authenticate))
            assertEquals(2, prompts)
            assertEquals(false, vm.reviewedFilterWeakensProtection(id, 7L))
            assertTrue(vm.commitFilterReview(id, 7L, present = reuse))
            advanceUntilIdle()
            assertEquals(1, fixture.writes.size)
            assertEquals(initial!!.activeProfileId, fixture.library.value!!.activeProfileId)
            assertEquals("My new filter", fixture.library.value!!.profile(id)!!.name)
            assertEquals("🌿", fixture.library.value!!.profile(id)!!.emoji)
            assertNull(vm.state.value.newFilter)
        } finally { vm.close(); security.close(); fixture.owner.close() }
    }

    @Test fun `new filter direct save exception follows exact native review after blocked and allowed edits`() = runTest {
        for (target in listOf(
            com.lavasec.app.foundation.filter.ManualRuleTarget.BLOCKED,
            com.lavasec.app.foundation.filter.ManualRuleTarget.ALLOWED,
        )) {
            val security = testSecurity(setOf(ProtectedSurface.FILTERS))
            val fixture = reviewedLibrary(limits = { com.lavasec.app.foundation.subscription.FeatureLimits.PLUS })
            val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
                security = security, libraryOwner = fixture.owner, libraryDraft = fixture.library,
                featureLimits = MutableStateFlow(com.lavasec.app.foundation.subscription.FeatureLimits.PLUS)))
            val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
            val authenticate: com.lavasec.app.security.NativeSecurityPresenter =
                { _, verify, _ -> verify("1234"); awaitCancellation() }
            val reuse: com.lavasec.app.security.NativeSecurityPresenter = { _, _, _ -> error("Use the editing grant") }
            try {
                advanceUntilIdle()
                vm.beginLibraryEditing(7L, authenticate)
                val id = (vm.beginCreatingFilter(null, null, 7L, reuse) as FilterLibraryEditOutcome.Created).filterId
                val initial = fixture.library.value
                assertFalse("No review can authorize the direct-save exception", vm.reviewedFilterIsUntouchedCreation(id, 7L))
                assertTrue(vm.requestFilterReview(id, 7L, authenticate))
                assertTrue("An exactly reviewed unedited template may apply directly", vm.reviewedFilterIsUntouchedCreation(id, 7L))
                assertTrue(fixture.owner.addDomainToDraft(id, "native-review.invalid", target) is
                    com.lavasec.app.foundation.filter.ManualRuleEditResult.Applied)
                assertFalse("A changed actual draft invalidates even the earlier untouched review", vm.reviewedFilterIsUntouchedCreation(id, 7L))
                assertTrue(vm.requestFilterReview(id, 7L, authenticate))
                assertFalse("Changed native rules must enter Review regardless of projected UI timing", vm.reviewedFilterIsUntouchedCreation(id, 7L))
                assertFalse(vm.reviewedFilterIsUntouchedCreation(id, 6L))
                assertEquals(initial, fixture.library.value)
                assertTrue("The Review discriminator cannot write", fixture.writes.isEmpty())
            } finally { vm.close(); security.close(); fixture.owner.close() }
        }
    }

    @Test fun `leaving a creation or retiring its credential graph discards the unsaved ID`() = runTest {
        for (departure in listOf("close", "open", "retire")) {
            val security = testSecurity(emptySet())
            val fixture = reviewedLibrary(limits = { com.lavasec.app.foundation.subscription.FeatureLimits.PLUS })
            val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
                security = security, libraryOwner = fixture.owner, libraryDraft = fixture.library,
                featureLimits = MutableStateFlow(com.lavasec.app.foundation.subscription.FeatureLimits.PLUS)))
            val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
            val reuse: com.lavasec.app.security.NativeSecurityPresenter = { _, _, _ -> error("Unprotected") }
            try {
                advanceUntilIdle()
                vm.beginLibraryEditing(7L, reuse)
                val id = (vm.beginCreatingFilter(null, null, 7L, reuse) as FilterLibraryEditOutcome.Created).filterId
                if (departure == "retire") {
                    factories.value = FakeOwnerFactory(8L)
                    advanceUntilIdle()
                } else if (departure == "open") {
                    vm.openFilterDetail(fixture.library.value!!.activeProfileId)
                    advanceUntilIdle()
                } else {
                    // A stale close for another ID cannot erase the currently viewed creation.
                    vm.closeFilterDetail("other")
                    assertEquals(id, fixture.owner.newFilter.value!!.id)
                    vm.closeFilterDetail(id)
                    advanceUntilIdle()
                }
                assertNull(fixture.owner.newFilter.value)
                assertNull(fixture.owner.draftFor(id))
                assertTrue(fixture.writes.isEmpty())
                assertFalse(vm.commitFilterReview(id, 7L, present = reuse))
            } finally { vm.close(); security.close(); fixture.owner.close() }
        }
    }

    @Test fun `native creation captures its retirement visit before projection and exact teardown drops only the unsaved detail`() = runTest {
        val security = testSecurity(emptySet())
        val fixture = reviewedLibrary(limits = { com.lavasec.app.foundation.subscription.FeatureLimits.PLUS })
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, libraryOwner = fixture.owner, libraryDraft = fixture.library,
            featureLimits = MutableStateFlow(com.lavasec.app.foundation.subscription.FeatureLimits.PLUS)))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        val reuse: com.lavasec.app.security.NativeSecurityPresenter = { _, _, _ -> error("Unprotected") }
        var captured: MainProductViewModel.FilterDetailRetirementWitness? = null
        try {
            advanceUntilIdle(); vm.beginLibraryEditing(7L, reuse)
            val before = fixture.library.value
            val outcome = vm.beginCreatingFilter(null, null, 7L, reuse, null, isCurrent = { true }, onDetailPresented = {
                captured = it
                assertEquals(it.filterId, fixture.owner.newFilter.value!!.id)
                assertNull("Native host must bind before the coherent RN projection", vm.state.value.newFilter)
            }) as FilterLibraryEditOutcome.Created
            val witness = checkNotNull(captured)
            assertEquals(outcome.filterId, witness.filterId)
            assertEquals(fixture.owner.draftLease(outcome.filterId), witness.creationLease)
            // Identity changes belong to this same creation lease and must not orphan cleanup.
            assertTrue(vm.renameFilter(outcome.filterId, "Retained identity", 7L, emoji = "🌿", present = reuse))
            assertTrue(vm.retireFilterDetail(witness).await()); advanceUntilIdle()
            assertNull(vm.state.value.selectedFilterId); assertNull(vm.state.value.filterDetail)
            assertNull(fixture.owner.newFilter.value); assertNull(fixture.owner.draftFor(outcome.filterId))
            assertNull(fixture.owner.draftLease(outcome.filterId))
            assertEquals(before, fixture.library.value); assertTrue(fixture.writes.isEmpty())
            assertFalse(vm.retireFilterDetail(witness).await())
        } finally { vm.close(); security.close(); fixture.owner.close() }
    }

    @Test fun `native saved-detail retirement is read-free and preserves its private rule draft`() = runTest {
        val security = testSecurity(setOf(ProtectedSurface.FILTERS))
        val fixture = reviewedLibrary()
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, libraryOwner = fixture.owner, libraryDraft = fixture.library))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        try {
            advanceUntilIdle()
            val id = fixture.library.value!!.activeProfileId
            assertNull(vm.openFilterDetailOwned(id, 6L) { true })
            assertNull(vm.openFilterDetailOwned(id, 7L) { false })
            val witness = checkNotNull(vm.openFilterDetailOwned(id, 7L) { true })
            assertTrue(vm.beginFilterEdit(id, 7L) { _, verify, _ -> verify("1234"); awaitCancellation() })
            assertTrue(vm.addBlockedDomainToDraft(id, "retained.example", 7L) { _, _, _ -> error("Use current grant") }
                is com.lavasec.app.foundation.filter.ManualRuleEditResult.Applied)
            val draft = checkNotNull(fixture.owner.draftFor(id))
            val lease = fixture.owner.draftLease(id)
            security.resetAuthenticationTurn()
            assertTrue(vm.retireFilterDetail(witness).await()); advanceUntilIdle()
            assertNull(vm.state.value.selectedFilterId)
            assertTrue(fixture.owner.draftFor(id) === draft)
            assertEquals(lease, fixture.owner.draftLease(id))
            assertEquals(setOf("retained.example"), draft.manualRules.blockedDomains)
            assertTrue(fixture.writes.isEmpty())
        } finally { vm.close(); security.close(); fixture.owner.close() }
    }

    @Test fun `VM-owned queued retirement survives a cancelled caller and refuses a same-id successor visit`() = runTest {
        for (reopen in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val security = testSecurity(emptySet())
            val fixture = reviewedLibrary(limits = { com.lavasec.app.foundation.subscription.FeatureLimits.PLUS },
                beforeApply = { entered.complete(Unit); release.await() })
            val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
                security = security, libraryOwner = fixture.owner, libraryDraft = fixture.library,
                featureLimits = MutableStateFlow(com.lavasec.app.foundation.subscription.FeatureLimits.PLUS)))
            val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
            val reuse: com.lavasec.app.security.NativeSecurityPresenter = { _, _, _ -> error("Unprotected") }
            var first: MainProductViewModel.FilterDetailRetirementWitness? = null
            try {
                advanceUntilIdle(); vm.beginLibraryEditing(7L, reuse)
                val id = (vm.beginCreatingFilter(null, null, 7L, reuse, null, onDetailPresented = { first = it })
                    as FilterLibraryEditOutcome.Created).filterId
                val identity = fixture.owner.newFilter.value
                val draft = fixture.owner.draftFor(id)
                val lease = fixture.owner.draftLease(id)
                val saved = fixture.library.value!!.profiles.first()
                val occupying = async { fixture.owner.renameFilter(saved.id, saved.name, isCurrent = { true }) }
                entered.await()
                val retiring = vm.retireFilterDetail(checkNotNull(first))
                val caller = async { retiring.await() }
                advanceUntilIdle(); assertFalse(retiring.isCompleted)
                caller.cancel(); caller.join()
                assertFalse("Activity/module cancellation cannot cancel VM-owned cleanup", retiring.isCancelled)
                val successor = if (reopen) checkNotNull(vm.openFilterDetailOwned(id, 7L) { true }) else null
                release.complete(Unit); assertTrue(occupying.await())
                assertEquals(!reopen, retiring.await()); advanceUntilIdle()
                assertEquals(1, fixture.writes.size)
                assertNull(fixture.library.value!!.profile(id))
                if (reopen) {
                    assertEquals(id, vm.state.value.selectedFilterId)
                    assertTrue(fixture.owner.newFilter.value === identity)
                    assertTrue(fixture.owner.draftFor(id) === draft)
                    assertEquals(lease, fixture.owner.draftLease(id))
                    assertTrue(vm.retireFilterDetail(checkNotNull(successor)).await())
                    advanceUntilIdle()
                }
                assertNull(vm.state.value.selectedFilterId); assertNull(fixture.owner.newFilter.value)
                assertNull(fixture.owner.draftFor(id)); assertNull(fixture.owner.draftLease(id))
                assertEquals(1, fixture.writes.size)
            } finally { release.complete(Unit); vm.close(); security.close(); fixture.owner.close() }
        }
    }

    @Test fun `ordinary queued detail close cannot discard the reopened same-id creation`() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val security = testSecurity(emptySet())
        val fixture = reviewedLibrary(limits = { com.lavasec.app.foundation.subscription.FeatureLimits.PLUS },
            beforeApply = { entered.complete(Unit); release.await() })
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, libraryOwner = fixture.owner, libraryDraft = fixture.library,
            featureLimits = MutableStateFlow(com.lavasec.app.foundation.subscription.FeatureLimits.PLUS)))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        val reuse: com.lavasec.app.security.NativeSecurityPresenter = { _, _, _ -> error("Unprotected") }
        try {
            advanceUntilIdle(); vm.beginLibraryEditing(7L, reuse)
            val id = (vm.beginCreatingFilter(null, null, 7L, reuse) as FilterLibraryEditOutcome.Created).filterId
            val identity = fixture.owner.newFilter.value
            val draft = fixture.owner.draftFor(id)
            val lease = fixture.owner.draftLease(id)
            val saved = fixture.library.value!!.profiles.first()
            val occupying = async { fixture.owner.renameFilter(saved.id, saved.name, isCurrent = { true }) }
            entered.await()
            val closing = async { vm.closeFilterDetail(id) }
            advanceUntilIdle(); assertFalse(closing.isCompleted)
            checkNotNull(vm.openFilterDetailOwned(id, 7L) { true })
            release.complete(Unit); assertTrue(occupying.await()); closing.await(); advanceUntilIdle()
            assertEquals(id, vm.state.value.selectedFilterId)
            assertTrue(fixture.owner.newFilter.value === identity)
            assertTrue(fixture.owner.draftFor(id) === draft)
            assertEquals(lease, fixture.owner.draftLease(id))
            assertNull(fixture.library.value!!.profile(id)); assertEquals(1, fixture.writes.size)
            // The current visit still performs its ordinary close successfully.
            vm.closeFilterDetail(id); advanceUntilIdle()
            assertNull(vm.state.value.selectedFilterId); assertNull(fixture.owner.newFilter.value)
            assertNull(fixture.owner.draftFor(id)); assertNull(fixture.owner.draftLease(id))
            assertEquals(1, fixture.writes.size)
        } finally { release.complete(Unit); vm.close(); security.close(); fixture.owner.close() }
    }

    @Test fun `old native detail retirement cannot adopt a replacement graph with the same saved id`() = runTest {
        val securityA = testSecurity(emptySet()); val securityB = testSecurity(emptySet())
        val a = reviewedLibrary(); val b = reviewedLibrary()
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = securityA, libraryOwner = a.owner, libraryDraft = a.library))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        try {
            advanceUntilIdle()
            val id = a.library.value!!.activeProfileId
            val old = checkNotNull(vm.openFilterDetailOwned(id, 7L) { true })
            factories.value = FakeOwnerFactory(8L, security = securityB, libraryOwner = b.owner, libraryDraft = b.library)
            advanceUntilIdle()
            val successor = checkNotNull(vm.openFilterDetailOwned(id, 8L) { true })
            assertFalse(vm.retireFilterDetail(old).await()); advanceUntilIdle()
            assertEquals(id, vm.state.value.selectedFilterId)
            assertTrue(b.writes.isEmpty())
            assertTrue(vm.retireFilterDetail(successor).await()); advanceUntilIdle()
            assertNull(vm.state.value.selectedFilterId)
        } finally { vm.close(); securityA.close(); securityB.close(); a.owner.close(); b.owner.close() }
    }

    @Test fun `queued native creation cannot publish over a newly selected filter visit`() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val security = testSecurity(emptySet())
        val fixture = reviewedLibrary(limits = { com.lavasec.app.foundation.subscription.FeatureLimits.PLUS },
            beforeApply = { entered.complete(Unit); release.await() })
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, libraryOwner = fixture.owner, libraryDraft = fixture.library,
            featureLimits = MutableStateFlow(com.lavasec.app.foundation.subscription.FeatureLimits.PLUS)))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        val reuse: com.lavasec.app.security.NativeSecurityPresenter = { _, _, _ -> error("Unprotected") }
        var callbacks = 0
        try {
            advanceUntilIdle(); vm.beginLibraryEditing(7L, reuse)
            val saved = fixture.library.value!!.profiles.first()
            val occupying = async { fixture.owner.renameFilter(saved.id, saved.name, isCurrent = { true }) }
            entered.await()
            val creating = async { vm.beginCreatingFilter(null, null, 7L, reuse, null, isCurrent = { true },
                onDetailPresented = { callbacks++ }) }
            advanceUntilIdle(); assertFalse(creating.isCompleted)
            checkNotNull(vm.openFilterDetailOwned(saved.id, 7L) { true })
            release.complete(Unit); assertTrue(occupying.await())
            assertTrue(creating.await() is FilterLibraryEditOutcome.Rejected)
            advanceUntilIdle()
            assertEquals(0, callbacks); assertNull(fixture.owner.newFilter.value)
            assertEquals(saved.id, vm.state.value.selectedFilterId)
            assertEquals(1, fixture.writes.size)
        } finally { release.complete(Unit); vm.close(); security.close(); fixture.owner.close() }
    }

    @Test fun `cancelling creation before the coherent presentation discards its unpublished identity`() = runTest {
        val security = testSecurity(emptySet())
        val fixture = reviewedLibrary(limits = { com.lavasec.app.foundation.subscription.FeatureLimits.PLUS })
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, libraryOwner = fixture.owner, libraryDraft = fixture.library,
            featureLimits = MutableStateFlow(com.lavasec.app.foundation.subscription.FeatureLimits.PLUS)))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        val reuse: com.lavasec.app.security.NativeSecurityPresenter = { _, _, _ -> error("Unprotected") }
        try {
            advanceUntilIdle()
            vm.beginLibraryEditing(7L, reuse)
            val creating = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                vm.beginCreatingFilter(null, null, 7L, reuse)
            }
            assertNotNull(fixture.owner.newFilter.value)
            creating.cancel()
            creating.join()
            advanceUntilIdle()
            assertNull(fixture.owner.newFilter.value)
            assertNull(vm.state.value.newFilter)
            assertNull(vm.state.value.selectedFilterId)
            assertTrue(fixture.writes.isEmpty())
        } finally { vm.close(); security.close(); fixture.owner.close() }
    }

    @Test fun `creation refuses a navigation change during authorization rather than adopting a late identity`() = runTest {
        val security = testSecurity(setOf(ProtectedSurface.FILTERS))
        val fixture = reviewedLibrary(limits = { com.lavasec.app.foundation.subscription.FeatureLimits.PLUS })
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, libraryOwner = fixture.owner, libraryDraft = fixture.library,
            featureLimits = MutableStateFlow(com.lavasec.app.foundation.subscription.FeatureLimits.PLUS)))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        try {
            advanceUntilIdle()
            vm.beginLibraryEditing(7L) { _, verify, _ -> verify("1234"); awaitCancellation() }
            security.resetAuthenticationTurn()
            assertNull(vm.beginCreatingFilter(null, null, 7L) { _, verify, _ ->
                vm.closeFilterDetail()
                verify("1234"); awaitCancellation()
            })
            assertNull(fixture.owner.newFilter.value)
            assertTrue(fixture.writes.isEmpty())
        } finally { vm.close(); security.close(); fixture.owner.close() }
    }

    @Test fun `QA mutation binds the exact graph and visit and refuses an unsaved filter editor`() = runTest {
        val fixture = reviewedLibrary(limits = { com.lavasec.app.foundation.subscription.FeatureLimits.PLUS })
        val security = testSecurity(emptySet())
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, libraryOwner = fixture.owner, libraryDraft = fixture.library))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        try {
            advanceUntilIdle()
            val before = fixture.library.value!!
            val active = before.profile(before.activeProfileId)!!
            val desired = before.withProfile(active.withManualRules(com.lavasec.app.foundation.filter.ManualDomainRules(
                blockedDomains = setOf("qa.example"))))
            assertFalse(vm.replaceFilterLibraryForQa(desired, before, 6L) { true })
            assertFalse(vm.replaceFilterLibraryForQa(desired, before, 7L) { false })
            assertTrue(fixture.writes.isEmpty())
            vm.beginLibraryEditing(7L) { _, _, _ -> error("Unprotected") }
            vm.beginCreatingFilter(null, null, 7L) { _, _, _ -> error("Unprotected") }
            vm.cancelLibraryEditing(7L)
            assertFalse(vm.replaceFilterLibraryForQa(desired, before, 7L) { true })
            assertNotNull(fixture.owner.newFilter.value)
            vm.closeFilterDetail()
            assertTrue(vm.replaceFilterLibraryForQa(desired, before, 7L) { true })
            assertEquals(listOf(desired), fixture.writes)
            assertFalse(fixture.owner.retryLast())
        } finally { vm.close(); security.close(); fixture.owner.close() }
    }

    @Test fun `QA final mutation refuses a replacement owners instance during preparation`() = runTest {
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val fixture = reviewedLibrary(beforeApply = {
            started.complete(Unit)
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() }
        })
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            libraryOwner = fixture.owner, libraryDraft = fixture.library))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        try {
            advanceUntilIdle()
            val before = fixture.library.value!!
            val desired = before.withProfile(before.profile(before.activeProfileId)!!.withManualRules(
                com.lavasec.app.foundation.filter.ManualDomainRules(blockedDomains = setOf("qa.example"))))
            val caller = async { vm.replaceFilterLibraryForQa(desired, before, 7L) { true } }
            started.await()
            factories.value = FakeOwnerFactory(8L)
            advanceUntilIdle()
            release.complete(Unit)
            assertFalse(caller.await())
            assertTrue(fixture.writes.isEmpty())
            assertEquals(before, fixture.library.value)
        } finally { release.complete(Unit); vm.close(); fixture.owner.close() }
    }

    @Test fun `mutation authorization witness binds actual bundle and cannot borrow a successor turn`() = runTest {
        val security = testSecurity(setOf(ProtectedSurface.APP_UNLOCK, ProtectedSurface.FILTERS))
        val replacementSecurity = testSecurity(emptySet())
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L, security = security))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        val authenticate: com.lavasec.app.security.NativeSecurityPresenter = { _, verify, _ -> verify("1234"); awaitCancellation() }
        try {
            advanceUntilIdle()
            assertNull(vm.captureMutationAuthorizationFence(7L, ProtectedSurface.FILTERS))
            assertNull(vm.captureMutationAuthorizationFence(8L, ProtectedSurface.FILTERS))
            assertTrue(vm.securityAuthorize(7L, ProtectedSurface.APP_UNLOCK, present = authenticate))
            assertTrue(vm.securityAuthorize(7L, ProtectedSurface.FILTERS, present = authenticate))
            val old = checkNotNull(vm.captureMutationAuthorizationFence(7L, ProtectedSurface.FILTERS))
            assertTrue(old())
            security.resetAuthenticationTurn()
            assertFalse(old())
            assertTrue(vm.securityAuthorize(7L, ProtectedSurface.APP_UNLOCK, present = authenticate))
            assertTrue(vm.securityAuthorize(7L, ProtectedSurface.FILTERS, present = authenticate))
            assertFalse(old())
            val fresh = checkNotNull(vm.captureMutationAuthorizationFence(7L, ProtectedSurface.FILTERS))
            assertTrue(fresh())
            // A distinct bundle with the same graph and same underlying security storage is still a successor.
            factories.value = FakeOwnerFactory(7L, security = security)
            advanceUntilIdle()
            assertFalse(fresh())
            val sameGraph = checkNotNull(vm.captureMutationAuthorizationFence(7L, ProtectedSurface.FILTERS))
            assertTrue(sameGraph())
            factories.value = FakeOwnerFactory(7L, security = replacementSecurity)
            advanceUntilIdle()
            assertFalse(sameGraph())
            val unprotected = checkNotNull(vm.captureMutationAuthorizationFence(7L, ProtectedSurface.FILTERS))
            replacementSecurity.resetAuthenticationTurn()
            assertTrue(unprotected())
            factories.value = FakeOwnerFactory(8L, security = replacementSecurity)
            advanceUntilIdle()
            assertFalse(unprotected())
            assertNull(vm.captureMutationAuthorizationFence(7L, ProtectedSurface.FILTERS))
        } finally { vm.close(); security.close(); replacementSecurity.close() }
    }

    @Test fun `shared rename and delete carry authorization through suspended owner publication`() = runTest {
        for (operation in listOf("rename", "delete")) for (transition in listOf("revoke", "regrant", "unprotected-turn")) {
            val security = testSecurity(if (transition == "unprotected-turn") emptySet()
                else setOf(ProtectedSurface.APP_UNLOCK, ProtectedSurface.FILTERS))
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val fixture = reviewedLibrary(beforeApply = { entered.complete(Unit); release.await() })
            val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
                security = security, libraryOwner = fixture.owner, libraryDraft = fixture.library))
            val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
            val authenticate: com.lavasec.app.security.NativeSecurityPresenter = { _, verify, _ -> verify("1234"); awaitCancellation() }
            val reuse: com.lavasec.app.security.NativeSecurityPresenter = { _, _, _ -> error("Native grants already admitted") }
            try {
                advanceUntilIdle()
                assertTrue(vm.securityAuthorize(7L, ProtectedSurface.APP_UNLOCK, present = authenticate))
                val before = fixture.library.value!!
                val id = if (operation == "rename") before.activeProfileId else before.profiles.first { it.id != before.activeProfileId }.id
                if (operation == "rename") {
                    vm.openFilterDetail(id)
                    assertTrue(vm.beginFilterEdit(id, 7L, authenticate))
                } else {
                    assertTrue(vm.beginLibraryEditing(7L, authenticate) is FilterLibraryEditOutcome.Completed)
                    assertTrue(vm.toggleLibraryDeletion(id, 7L, reuse) is FilterLibraryEditOutcome.Completed)
                }
                val draft = vm.sharedFilterDraft(7L, id)
                val session = vm.libraryEditSessionForSnapshot(7L)
                val saving = async {
                    if (operation == "rename") vm.renameFilter(id, "Guarded rename", 7L, "🍀", reuse,
                        expectedDraft = draft, expectedNewFilter = null, isCurrent = { true })
                    else vm.commitLibraryEdit(setOf(id), 7L, reuse, expectedSession = session,
                        isCurrent = { true }) is FilterLibraryEditOutcome.Completed
                }
                entered.await()
                assertTrue(fixture.writes.isEmpty())
                security.resetAuthenticationTurn()
                if (transition == "regrant") {
                    assertTrue(vm.securityAuthorize(7L, ProtectedSurface.APP_UNLOCK, present = authenticate))
                    assertTrue(vm.securityAuthorize(7L, ProtectedSurface.FILTERS, present = authenticate))
                }
                release.complete(Unit)
                assertEquals("$operation / $transition", transition == "unprotected-turn", saving.await())
                if (transition == "unprotected-turn") {
                    assertEquals(1, fixture.writes.size)
                    if (operation == "rename") assertEquals("Guarded rename", fixture.library.value!!.profile(id)!!.name)
                    else assertNull(fixture.library.value!!.profile(id))
                } else {
                    assertEquals(before, fixture.library.value)
                    assertTrue(fixture.writes.isEmpty())
                    if (operation == "rename") assertTrue(fixture.owner.draftFor(id) === draft)
                    else assertTrue(vm.libraryEditSessionForSnapshot(7L) === session)
                }
            } finally { release.complete(Unit); vm.close(); security.close(); fixture.owner.close() }
        }
    }

    private data class ReviewFixture(
        val owner: FilterLibraryOwner,
        val library: MutableStateFlow<FilterLibraryDraft?>,
        val writes: MutableList<FilterLibraryDraft>,
    )

    private fun TestScope.reviewedLibrary(
        failFirst: Boolean = false,
        limits: () -> com.lavasec.app.foundation.subscription.FeatureLimits = {
            com.lavasec.app.foundation.subscription.FeatureLimits.FREE
        },
        beforeApply: suspend () -> Unit = {},
    ): ReviewFixture {
        val catalog = FilterCatalogSnapshot(listOf(CatalogFilterSource("blocklistproject-basic", FilterSourceCategory.SECURITY, true)))
        val library = MutableStateFlow<FilterLibraryDraft?>(FilterLibraryDraft.seededDefaults(catalog))
        val writes = mutableListOf<FilterLibraryDraft>()
        var failNext = failFirst
        var generation = 0L
        val port = object : FilterLibraryPort {
            override fun currentLibrary() = checkNotNull(library.value)
            override fun reviewBasis() = com.lavasec.app.product.FilterLibraryReviewBasis(currentLibrary(), generation)
            override suspend fun apply(desired: FilterLibraryDraft, admission: com.lavasec.app.foundation.subscription.FeatureLimitAdmission): FilterLibraryMutationResult {
                beforeApply()
                return publish(desired)
            }
            private fun publish(desired: FilterLibraryDraft): FilterLibraryMutationResult {
                writes += desired
                if (failNext) {
                    failNext = false
                    return FilterLibraryMutationResult.Failed(com.lavasec.app.platform.FilterMutationFailure.COMPILE_FAILED)
                }
                library.value = desired
                generation++
                return FilterLibraryMutationResult.Applied(generation)
            }
            override suspend fun applyReviewed(desired: FilterLibraryDraft, expected: com.lavasec.app.product.FilterLibraryReviewBasis, admission: com.lavasec.app.foundation.subscription.FeatureLimitAdmission): FilterLibraryMutationResult {
                if (reviewBasis() != expected) return FilterLibraryMutationResult.Superseded
                return apply(desired, admission)
            }
            override suspend fun applyReviewedWhileCurrent(
                desired: FilterLibraryDraft, expected: com.lavasec.app.product.FilterLibraryReviewBasis,
                admission: com.lavasec.app.foundation.subscription.FeatureLimitAdmission, isCurrent: () -> Boolean,
            ): FilterLibraryMutationResult {
                beforeApply()
                if (reviewBasis() != expected || !isCurrent()) return FilterLibraryMutationResult.Superseded
                return publish(desired)
            }
            override suspend fun restoreDefaults(admission: com.lavasec.app.foundation.subscription.FeatureLimitAdmission) = error("Not part of review")
        }
        return ReviewFixture(
            FilterLibraryOwner(
                port, backgroundScope, StandardTestDispatcher(testScheduler), limits = limits,
            ),
            library,
            writes,
        )
    }

    @Test fun `native foreground wrapper restores the same prior filter once without reviving old writes`() = runTest {
        val security = testSecurity(setOf(ProtectedSurface.APP_UNLOCK, ProtectedSurface.FILTERS))
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L, security = security))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        val host = Any(); val visit = com.lavasec.app.security.SecurityPresentationVisit { true }
        var prompts = 0
        val authenticate: com.lavasec.app.security.NativeSecurityPresenter = { _, verify, _ ->
            prompts++; verify("1234"); awaitCancellation()
        }
        try {
            advanceUntilIdle()
            assertTrue(vm.securityEnterForeground(host, 7L, visit))
            assertTrue(vm.securityAuthorize(7L, ProtectedSurface.APP_UNLOCK, present = authenticate))
            assertTrue(vm.securityAuthorize(7L, ProtectedSurface.FILTERS, present = authenticate))
            val oldWrite = checkNotNull(vm.captureMutationAuthorizationFence(7L, ProtectedSurface.FILTERS))
            assertTrue(vm.securitySuspendForeground(host, 7L, visit))
            assertFalse(oldWrite())
            assertFalse(vm.securityStateForSnapshot(7L)!!.isAuthorized(ProtectedSurface.FILTERS))
            assertFalse(vm.securityEnterForeground(host, 8L, visit))
            assertTrue(vm.securityEnterForeground(host, 7L, visit))
            prompts = 0
            assertTrue(vm.securityAuthorize(7L, ProtectedSurface.APP_UNLOCK, present = authenticate))
            assertTrue(vm.securityAuthorize(7L, ProtectedSurface.FILTERS, present = authenticate))
            assertEquals("The second reader call consumes the freshly restored read grant", 1, prompts)
            assertFalse(oldWrite())
            assertTrue(checkNotNull(vm.captureMutationAuthorizationFence(7L, ProtectedSurface.FILTERS))())
            vm.securityResetAuthenticationTurn()
            assertTrue(vm.securityStateForSnapshot(7L)!!.isAuthorized(ProtectedSurface.APP_UNLOCK))
            assertFalse(vm.securityStateForSnapshot(7L)!!.isAuthorized(ProtectedSurface.FILTERS))
        } finally { vm.close(); security.close() }
    }

    @Test fun `disabling a stored biometric requires fresh evidence and lockout falls back without changing passcode or choices`() = runTest {
        for (decision in listOf("verify", "cancel", "biometricCancel")) {
            var locked = false
            var biometricPrompts = 0
            val biometric = object : com.lavasec.app.security.BiometricAuthenticatorPort {
                override fun availability() = com.lavasec.app.foundation.biometrics.BiometricAvailability.Available(
                    com.lavasec.app.foundation.biometrics.BiometricKind.FINGERPRINT)
                override fun isLockedOut() = locked
                override suspend fun authenticate(purpose: String): com.lavasec.app.foundation.biometrics.BiometricPromptResult {
                    biometricPrompts++
                    if (decision == "biometricCancel") return com.lavasec.app.foundation.biometrics.BiometricPromptResult.Cancelled
                    locked = true
                    return com.lavasec.app.foundation.biometrics.BiometricPromptResult.Lockout(permanent = false)
                }
                override fun cancel() = Unit
            }
            val choices = setOf(ProtectedSurface.FILTERS)
            val security = testSecurity(choices, biometric)
            assertTrue(security.setBiometrics(true))
            val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L, security = security))
            val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
            var passcodePrompts = 0
            try {
                advanceUntilIdle()
                val accepted = vm.securitySetBiometrics(false, expectedGeneration = 7L) { request, verify, cancel ->
                    passcodePrompts++
                    assertNull("An unprotected Settings page still requires fresh credential evidence", request.surface)
                    if (decision == "verify") { verify("1234"); awaitCancellation() } else cancel()
                }
                assertEquals(decision, decision == "verify", accepted)
                assertEquals(1, biometricPrompts)
                assertEquals(if (decision == "biometricCancel") 0 else 1, passcodePrompts)
                assertEquals(decision != "verify", security.state.value.biometricEnabled)
                assertTrue(security.state.value.enabled)
                assertEquals(choices, security.state.value.protectedSurfaces)
                assertEquals(decision != "verify", vm.consumeAuthenticationCancellation())
            } finally { vm.close(); security.close() }
        }
    }

    @Test fun `held biometric disable cannot borrow a replacement host graph or authentication turn`() = runTest {
        for (boundary in listOf("host", "graph", "turn")) {
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val biometric = object : com.lavasec.app.security.BiometricAuthenticatorPort {
                override fun availability() = com.lavasec.app.foundation.biometrics.BiometricAvailability.Available(
                    com.lavasec.app.foundation.biometrics.BiometricKind.FINGERPRINT)
                override fun isLockedOut() = false
                override suspend fun authenticate(purpose: String): com.lavasec.app.foundation.biometrics.BiometricPromptResult {
                    started.complete(Unit); release.await()
                    return com.lavasec.app.foundation.biometrics.BiometricPromptResult.Success
                }
                override fun cancel() = Unit
            }
            val security = testSecurity(emptySet(), biometric)
            assertTrue(security.setBiometrics(true))
            val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L, security = security))
            val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
            var hostCurrent = true
            try {
                advanceUntilIdle()
                val result = async { vm.securitySetBiometrics(false, expectedGeneration = 7L,
                    canCommit = { hostCurrent }) { _, _, _ -> error("Usable biometric does not need passcode UI") } }
                started.await()
                when (boundary) {
                    "host" -> hostCurrent = false
                    "graph" -> { factories.value = FakeOwnerFactory(8L); advanceUntilIdle() }
                    "turn" -> security.resetAuthenticationTurn()
                }
                release.complete(Unit)
                assertFalse(boundary, result.await())
                assertTrue("A refused original confirmation cannot disable the durable preference", security.state.value.biometricEnabled)
            } finally { release.complete(Unit); vm.close(); security.close() }
        }
    }

    @Test fun `a native only foreground can authenticate settings without inheriting the paused React visit`() = runTest {
        val security = testSecurity(setOf(ProtectedSurface.APP_UNLOCK, ProtectedSurface.FILTERS, ProtectedSurface.ACTIVITY))
        val vm = MainProductViewModel(MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L, security = security)),
            {}, StandardTestDispatcher(testScheduler))
        val react = Any(); val native = Any(); val visit = com.lavasec.app.security.SecurityPresentationVisit { true }
        val presenter: com.lavasec.app.security.NativeSecurityPresenter = { _, verify, _ -> verify("1234"); awaitCancellation() }
        try {
            advanceUntilIdle()
            assertFalse(vm.securityOwnsForeground(native, 7))
            assertTrue(vm.securityEnterForeground(react, 7, visit))
            assertTrue(vm.securityAuthorize(7, ProtectedSurface.FILTERS, present = presenter))
            assertNull("The Filters reader cannot lend the independent protected App Unlock grant",
                vm.captureMutationAuthorizationFence(7, ProtectedSurface.FILTERS))
            assertTrue(vm.securityAuthorize(7, ProtectedSurface.APP_UNLOCK, present = presenter))
            val oldWrite = checkNotNull(vm.captureMutationAuthorizationFence(7, ProtectedSurface.FILTERS))
            assertTrue("Both actual grants must admit the original write before handoff", oldWrite())
            assertTrue(vm.securitySuspendForeground(react, 7, visit))
            assertFalse("Pausing React must immediately retire its admitted write", oldWrite())
            assertTrue(vm.securityEnterForeground(native, 7, null))
            assertFalse(vm.securityOwnsForeground(react, 7))
            assertFalse(vm.securityOwnsForeground(native, 8))
            assertTrue(vm.securityOwnsForeground(native, 7))
            assertTrue(vm.securityAuthorize(7, ProtectedSurface.APP_UNLOCK, present = presenter))
            assertFalse("The native flow cannot inherit the covered React reader", security.state.value.isAuthorized(ProtectedSurface.FILTERS))
            val revision = security.state.value.authenticationRevision
            assertTrue(vm.securitySetProtectedSurface(ProtectedSurface.ACTIVITY, false, revision,
                canCommit = { vm.securityOwnsForeground(native, 7) }, present = presenter))
            assertFalse(ProtectedSurface.ACTIVITY in security.state.value.protectedSurfaces)
            vm.securitySuspendForeground(react, 7, visit); vm.securityRetireForeground(react)
            assertTrue(vm.securityOwnsForeground(native, 7))
            assertTrue(vm.securitySuspendForeground(native, 7, null))
            assertFalse(vm.securityOwnsForeground(native, 7))
            assertFalse(vm.securityAuthorize(7, ProtectedSurface.FILTERS, present = presenter))
            assertTrue(vm.securityEnterForeground(react, 7, visit))
            assertTrue(vm.securityAuthorize(7, ProtectedSurface.APP_UNLOCK, present = presenter))
            assertFalse("Returning to React does not resurrect a visit retired by native handoff", security.state.value.isAuthorized(ProtectedSurface.FILTERS))
            assertFalse(oldWrite())
            vm.securityRetireForeground(react)
            assertFalse(vm.securityOwnsForeground(react, 7))
        } finally { vm.close(); security.close() }
    }

    @Test fun `invalidated key enable requires fresh credentials even when Settings protection is off`() = runTest {
        val biometric = object : com.lavasec.app.security.BiometricAuthenticatorPort {
            var resets = 0
            var current: com.lavasec.app.foundation.biometrics.BiometricAvailability =
                com.lavasec.app.foundation.biometrics.BiometricAvailability.KeyInvalidated
            override fun availability() = current
            override fun isLockedOut() = false
            override suspend fun authenticate(purpose: String) = error("Invalidated key cannot authenticate")
            override fun cancel() = Unit
            override fun resetEnrollmentBoundKey(): Boolean {
                resets++; current = com.lavasec.app.foundation.biometrics.BiometricAvailability.Available(
                    com.lavasec.app.foundation.biometrics.BiometricKind.FINGERPRINT)
                return true
            }
        }
        val security = testSecurity(emptySet(), biometric)
        val vm = MainProductViewModel(MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L, security = security)),
            {}, StandardTestDispatcher(testScheduler))
        try {
            advanceUntilIdle()
            var prompts = 0
            assertFalse(vm.securitySetBiometrics(true) { request, _, cancel ->
                prompts++; assertNull(request.surface); cancel()
            })
            assertEquals(1, prompts); assertEquals(0, biometric.resets)
            assertFalse(security.state.value.biometricEnabled)
            assertTrue(vm.consumeAuthenticationCancellation())
            assertTrue(vm.securitySetBiometrics(true) { request, verify, _ ->
                prompts++; assertNull(request.surface); verify("1234"); awaitCancellation()
            })
            assertEquals(2, prompts); assertEquals(1, biometric.resets)
            assertTrue(security.state.value.biometricEnabled)
            assertTrue(security.state.value.enabled)
            assertTrue(security.state.value.protectedSurfaces.isEmpty())
        } finally { vm.close(); security.close() }
    }


    @Test fun `late custom registration compensation clears its original token while graph reset waits without touching a fresh graph`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val writes = mutableListOf<FilterResolverPlan>()
        fun factory(generation: Long) = FakeOwnerFactory(
            generation = generation, completed = true, dispatcher = dispatcher,
            resolverPlan = MilestoneResolverPlan.DEVICE_DNS_ONLY,
            resolverSwitchPort = com.lavasec.app.product.ResolverPlanSwitchPort { plan ->
                writes += plan; com.lavasec.app.product.ResolverPlanSwitchResult.Applied(plan.plan, 2)
            }, featureLimits = MutableStateFlow(com.lavasec.app.foundation.subscription.FeatureLimits.PLUS))
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(factory(304))
        val vm = MainProductViewModel(factories, {}, dispatcher)
        val detached = java.util.concurrent.CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val transition = Thread { try { testScheduler.runCurrent() } catch (caught: Throwable) { failure.set(caught) } }
        var observer: kotlinx.coroutines.Job? = null
        try {
            advanceUntilIdle()
            val opened = vm.openCustomDnsDraft(null, 304) as com.lavasec.app.product.ResolverDnsEditOutcome.DraftOpened
            // Observe the real collectLatest retirement; never replace its owner or token fields.
            @Suppress("UNCHECKED_CAST")
            val attachments = MainProductViewModel::class.java.getDeclaredField("ownerAttachment")
                .apply { isAccessible = true }.get(vm) as kotlinx.coroutines.flow.StateFlow<Any?>
            observer = launch(kotlinx.coroutines.Dispatchers.Unconfined) {
                attachments.first { it == null }; detached.countDown()
            }
            val draftLock = MainProductViewModel::class.java.getDeclaredField("customDraftLock")
                .apply { isAccessible = true }.get(vm)
            synchronized(draftLock) {
                factories.value = null; transition.start()
                assertTrue("Actual owner detachment precedes reset's locked clear", detached.await(5, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(com.lavasec.app.product.ResolverDnsEditOutcome.Unavailable, vm.openCustomDnsDraft(null, 304))
                assertEquals(opened.token, vm.dnsCustomDraftToken.value)
                assertNotNull(vm.dnsCustomDraftSession.value)
                assertFalse(vm.dismissCustomEntry(opened.token, 305))
                var callbacks = 0; var removed = false
                val deadHost = com.lavasec.app.reactnative.NativeVisitHost(Any(), "retired") { false }
                val custody = com.lavasec.app.reactnative.NativeCustomEntryHostOwner({ vm.state.value.graphGeneration }, { deadHost })
                assertFalse(custody.register(opened.token, 304, deadHost) {
                    callbacks++; removed = vm.dismissCustomEntry(opened.token, 304)
                })
                assertEquals(1, callbacks); assertTrue("Refusal compensation must clear the actual original token", removed)
                assertNull(vm.dnsCustomDraftToken.value); assertNull(vm.dnsCustomDraftSession.value)
                assertTrue(writes.isEmpty()); custody.close(); assertEquals(1, callbacks)
            }
            transition.join(5_000); assertFalse(transition.isAlive); failure.get()?.let { throw it }
            factories.value = factory(305); advanceUntilIdle()
            val successor = vm.openCustomDnsDraft(null, 305) as com.lavasec.app.product.ResolverDnsEditOutcome.DraftOpened
            assertFalse(vm.dismissCustomEntry(opened.token, 304))
            assertFalse(vm.dismissCustomEntry(successor.token, 304))
            assertEquals(successor.token, vm.dnsCustomDraftToken.value); assertNotNull(vm.dnsCustomDraftSession.value)
            assertTrue(vm.dismissCustomEntry(successor.token, 305)); assertNull(vm.dnsCustomDraftToken.value)
            assertTrue(writes.isEmpty())
        } finally { observer?.cancel(); vm.close(); transition.join(5_000); advanceUntilIdle() }
    }

    private fun TestScope.testSecurity(
        surfaces: Set<ProtectedSurface>,
        biometric: com.lavasec.app.security.BiometricAuthenticatorPort =
            com.lavasec.app.security.UnavailableBiometricAuthenticatorPort,
    ): com.lavasec.app.security.SecurityOwner {
        val verifier = object : com.lavasec.app.security.PasscodeVerifierPort {
            override fun availability() = com.lavasec.app.security.PasscodeVerifierAvailability.Available
            override fun credentialState() = com.lavasec.app.security.PasscodeCredentialState.Available
            override fun configure(code: String) = false
            override fun remove() = false
            override fun verify(code: String) = if (code == "1234") com.lavasec.app.security.PasscodeVerification.Verified
                else com.lavasec.app.security.PasscodeVerification.Wrong
        }
        val preferences = object : com.lavasec.app.security.SecurityPreferencesPort {
            var saved = com.lavasec.app.security.SecurityPreferences(enabled = true, protectedSurfaces = surfaces)
            override fun load() = saved
            override fun save(preferences: com.lavasec.app.security.SecurityPreferences) { saved = preferences }
        }
        return com.lavasec.app.security.SecurityOwner(
            verifier, preferences, backgroundScope, biometricAuthenticator = biometric,
        )
    }

    private class ControlRecorder : com.lavasec.app.platform.ProtectionController {
        val events = mutableListOf<String>()
        val mutable = MutableStateFlow<ProtectionState>(ProtectionState.Off)
        val protectedState = ProtectionState.Protected(com.lavasec.app.platform.ConfigurationIdentity(1L, "test"),
            com.lavasec.app.platform.VpnOwnership.User)
        override val state = mutable
        override suspend fun start() { events += "start" }
        override suspend fun stop() { events += "stop" }
        override suspend fun reconnect() { events += "reconnect" }
        override suspend fun resume() { events += "resume" }
        override suspend fun pause(duration: com.lavasec.app.foundation.pause.PauseDuration) { events += "pause" }
    }

    private class NetworkPort(
        private val dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        private val queryReady: Boolean = false,
    ) : com.lavasec.app.product.activity.ProductActivityPort {
        var loadNetworkActivityCalls = 0
        val summaryQueries = mutableListOf<com.lavasec.app.product.activity.ActivitySummaryQuery>()
        val historyQueries = mutableListOf<com.lavasec.app.product.activity.DomainHistoryQuery>()
        private val signals = kotlinx.coroutines.flow.MutableSharedFlow<
            com.lavasec.app.product.activity.ProductActivityRevisionSignal
        >(extraBufferCapacity = 1)
        override val revisionSignals: kotlinx.coroutines.flow.Flow<
            com.lavasec.app.product.activity.ProductActivityRevisionSignal
        > = signals

        override suspend fun summaryCountRevision(): Long = 0L

        override suspend fun domainLogRevision(): Long = 0L

        override suspend fun networkRevision(): Long = 0L

        override suspend fun loadSummary(
            query: com.lavasec.app.product.activity.ActivitySummaryQuery,
            topDomainsLimit: Int,
        ): com.lavasec.app.product.Revisioned<com.lavasec.app.product.activity.ActivitySummaryRead> {
            summaryQueries += query
            val availability = if (queryReady) com.lavasec.app.product.activity.DomainHistoryAvailability.RETAINED
                else com.lavasec.app.product.activity.DomainHistoryAvailability.DISABLED
            val result = if (queryReady) com.lavasec.app.product.activity.ProductReadResult.Available(
                com.lavasec.app.product.activity.ActivitySummary(
                    digest = com.lavasec.app.product.activity.ActivityDigest(3L, 2L, 10L),
                    topDomains = listOf(com.lavasec.app.product.activity.TopDomainRow("tracker.example", 2L)),
                    dateLens = query.dateLens, topDomainsDomainRevision = 0L, topDomainsPolicyRevision = 1L,
                    topDomainsAvailability = availability, topDomainsDecision = query.topDomainDecision,
                    topDomainsSearch = query.topDomainSearch))
                else com.lavasec.app.product.activity.ProductReadResult.Disabled
            return com.lavasec.app.product.Revisioned(0L, com.lavasec.app.product.activity.ActivitySummaryRead(
                query = query, topDomainsDomainRevision = 0L, topDomainsPolicyRevision = 1L,
                topDomainsAvailability = availability, result = result))
        }

        override suspend fun loadDomainHistory(
            request: com.lavasec.app.product.activity.DomainHistoryPageRequest,
        ): com.lavasec.app.product.Revisioned<com.lavasec.app.product.activity.DomainHistoryRead> {
            historyQueries += request.query
            val availability = if (queryReady) com.lavasec.app.product.activity.DomainHistoryAvailability.RETAINED
                else com.lavasec.app.product.activity.DomainHistoryAvailability.DISABLED
            val result = if (queryReady) com.lavasec.app.product.activity.ProductReadResult.Available(
                com.lavasec.app.product.StablePage(
                    rows = listOf(com.lavasec.app.product.activity.DomainHistoryRow(
                        id = if (request.query.decision == com.lavasec.app.product.activity.DomainDecision.BLOCKED) 1L else 2L,
                        timestampMillis = 1L, domain = "tracker.example", decision = request.query.decision,
                        reason = if (request.query.decision == com.lavasec.app.product.activity.DomainDecision.BLOCKED)
                            com.lavasec.app.foundation.filter.FilterDecisionReason.BLOCKLIST
                            else com.lavasec.app.foundation.filter.FilterDecisionReason.DEFAULT_ALLOW)),
                    next = null, cut = com.lavasec.app.product.StableReadCut(2L, 1L)))
                else com.lavasec.app.product.activity.ProductReadResult.Disabled
            return com.lavasec.app.product.Revisioned(0L, com.lavasec.app.product.activity.DomainHistoryRead(
                query = request.query, policyRevision = 1L, availability = availability, result = result))
        }

        override suspend fun loadNetworkActivity(
            request: com.lavasec.app.product.activity.NetworkActivityPageRequest,
        ): com.lavasec.app.product.Revisioned<
            com.lavasec.app.product.activity.ProductReadResult<
                com.lavasec.app.product.StablePage<com.lavasec.app.product.activity.NetworkActivityRow>
                >
            > {
            loadNetworkActivityCalls += 1
            return com.lavasec.app.product.Revisioned(
                0L,
                com.lavasec.app.product.activity.ProductReadResult.Disabled,
            )
        }
    }

    /** Unit fixture for one final publication; real compiled-artifact coverage lives in instrumentation. */
    private class QaLibraryPort(var library: FilterLibraryDraft) : com.lavasec.app.product.FilterLibraryPort {
        var generation = 1L; var writes = 0
        override fun currentLibrary() = library
        override fun reviewBasis() = com.lavasec.app.product.FilterLibraryReviewBasis(library, generation)
        override suspend fun apply(desired: FilterLibraryDraft, admission: com.lavasec.app.foundation.subscription.FeatureLimitAdmission): com.lavasec.app.product.FilterLibraryMutationResult = error("ordinary mutation forbidden")
        override suspend fun restoreDefaults(admission: com.lavasec.app.foundation.subscription.FeatureLimitAdmission): com.lavasec.app.product.FilterLibraryMutationResult = error("defaults forbidden")
        override suspend fun applyReviewedForQaComposite(desired: FilterLibraryDraft,
            expected: com.lavasec.app.product.FilterLibraryReviewBasis, admission: com.lavasec.app.foundation.subscription.FeatureLimitAdmission,
            isCurrent: () -> Boolean, publicationGuard: com.lavasec.app.platform.NativeFilterPublicationGuard,
        ): com.lavasec.app.product.FilterLibraryMutationResult {
            var result: com.lavasec.app.product.FilterLibraryMutationResult = com.lavasec.app.product.FilterLibraryMutationResult.Superseded
            publicationGuard.publish {
                if (expected != reviewBasis() || !isCurrent()) com.lavasec.app.platform.FilterMutationPublishResult.Failed(com.lavasec.app.platform.FilterMutationFailure.COMMIT_FAILED)
                else { writes++; generation++; library = desired; result = com.lavasec.app.product.FilterLibraryMutationResult.Applied(generation); com.lavasec.app.platform.FilterMutationPublishResult.Committed }
            }
            return result
        }
    }

    private inner class FakeOwnerFactory(
        private val generation: Long,
        private val ownerClosed: AtomicInteger = AtomicInteger(),
        private val security: com.lavasec.app.security.SecurityOwner? = null,
        private val protectionController: com.lavasec.app.platform.ProtectionController? = null,
        private val protectionCommits: MutableList<Boolean> = mutableListOf(),
        private val vpn: VpnPreparationResult<Intent> = VpnPreparationResult.Prepared,
        private val completed: Boolean = false,
        private val dispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
        private val commit: suspend (AppConfiguration) -> Unit = {},
        private val loggingResult: PrivacyMutationResult? = null,
        private val localClear: (suspend (com.lavasec.app.product.privacy.LocalActivityClearSelection) -> PrivacyMutationResult)? = null,
        private val filterLibrary: FilterLibraryState? = null,
        private val libraryOwner: FilterLibraryOwner? = null,
        private val libraryDraft: MutableStateFlow<FilterLibraryDraft?> = MutableStateFlow(null),
        private val switchPort: com.lavasec.app.product.FilterProfileSwitchPort? = null,
        private val resolverPlan: com.lavasec.app.foundation.resolver.MilestoneResolverPlan? = null,
        private val resolverValue: MutableStateFlow<FilterResolverPlan?>? = null,
        private val resolverSwitchPort: com.lavasec.app.product.ResolverPlanSwitchPort? = null,
        private val resolverFreshGate: (suspend () -> com.lavasec.app.foundation.resolver.ChainedDnsEditState)? = null,
        private val resolverQaAdmission: (suspend () -> com.lavasec.app.product.QaDnsPublicationAdmission?)? = null,
        private val qaReset: (suspend (com.lavasec.app.platform.LoggingSettings, Long, () -> Boolean) -> PrivacyMutationResult)? = null,
        private val networkPort: com.lavasec.app.product.activity.ProductActivityPort? = null,
        private val domainHost: com.lavasec.app.product.NativeDomainActionHost? = null,
        private val networkClearResult: PrivacyMutationResult = PrivacyMutationResult.Completed,
        private val filterMutationsAvailable: Boolean = true,
        private val diagnosticsAvailable: Boolean = false,
        private val filterTotalRuleCount: MutableStateFlow<Int?> = MutableStateFlow(null),
        private val catalogReconstructionRequired: MutableStateFlow<Boolean> = MutableStateFlow(false),
        private val featureLimits: MutableStateFlow<com.lavasec.app.foundation.subscription.FeatureLimits> =
            MutableStateFlow(com.lavasec.app.foundation.subscription.FeatureLimits.FREE),
    ) : CredentialProductOwnerFactory {
        override fun create(scope: CoroutineScope): CredentialProductOwners {
            val onboarding = OnboardingOwner(
                onboardingCompleted = completed,
                vpnPreparation = VpnPreparation<Intent> { vpn },
                commit = commit,
                scope = scope,
                dispatcher = dispatcher,
            )
            val domainActions = (domainHost ?: networkPort?.let { host() })?.let {
                DomainActionOwner(it, scope, dispatcher)
            }
            val activity = networkPort?.let { port ->
                ActivityOwner(
                    port = port,
                    initialDateLens = todayLens(),
                    domainActions = checkNotNull(domainActions),
                    scope = scope,
                    dispatcher = dispatcher,
                )
            }
            return CredentialProductOwners(
                graphGeneration = generation,
                generationScope = CoroutineScope(SupervisorJob()),
                onboarding = onboarding,
                protection = protectionController?.let {
                    com.lavasec.app.protection.ProtectionOwner(it,
                        MutableStateFlow(com.lavasec.app.vpn.VpnHealthStatus()),
                        { value -> protectionCommits += value; com.lavasec.app.protection.ProtectionIntentCommit.Committed }, scope)
                },
                security = security?.openSession(scope),
                domainActions = domainActions,
                activity = activity,
                privacy = if (loggingResult != null || localClear != null || networkPort != null) {
                    PrivacyOwner(
                        initialLogging = com.lavasec.app.platform.LoggingSettings(),
                        setLogging = { loggingResult ?: PrivacyMutationResult.Completed },
                        clear = localClear ?: { PrivacyMutationResult.Completed },
                        export = { com.lavasec.app.product.privacy.LocalExportResult.Cancelled },
                        clearNetworkActivity = { networkClearResult },
                        resetQa = qaReset ?: { _, _, _ -> PrivacyMutationResult.Cancelled },
                        scope = scope,
                    )
                } else {
                    null
                },
                diagnostics = if (diagnosticsAvailable) com.lavasec.app.diagnostics.DiagnosticsFeedbackOwner(
                    load = { null }, clear = { true },
                    feedback = com.lavasec.app.product.privacy.LocalFeedbackComposer({ "test" }, { "test" }, { "" }),
                    scope = scope,
                ) else null,
                filterMutationsAvailable = filterMutationsAvailable,
                onClose = { ownerClosed.incrementAndGet() },
                filterLibraryState = MutableStateFlow(filterLibrary),
                filterTotalRuleCount = filterTotalRuleCount,
                catalogReconstructionRequired = catalogReconstructionRequired,
                featureLimits = featureLimits,
                filterDraft = libraryDraft,
                filterLibrary = libraryOwner,
                filterActiveRuleCount = MutableStateFlow(null),
                filterCatalog = MutableStateFlow(null),
                filterProfileSwitch = switchPort?.let { port ->
                    com.lavasec.app.product.FilterProfileSwitchOwner(port, scope, dispatcher)
                },
                resolverPlan = resolverValue ?: MutableStateFlow(
                    resolverPlan?.let { com.lavasec.app.foundation.resolver.FilterResolverPlan(it) },
                ),
                resolverPlanSwitch = resolverSwitchPort?.let { port ->
                    com.lavasec.app.product.ResolverPlanSwitchOwner(port, scope, dispatcher, loadChainedDnsEdit = resolverFreshGate, captureQaDnsAdmission = resolverQaAdmission)
                },
            )
        }

        override fun close() = Unit
    }

    private fun todayLens(): com.lavasec.app.product.activity.ActivityDateLens {
        val day = java.time.LocalDate.of(2026, 8, 2)
        val start = day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        return com.lavasec.app.product.activity.ActivityDateLens(start, start, "UTC", day)
    }

    private fun host(): com.lavasec.app.product.NativeDomainActionHost {
        val profile = com.lavasec.app.foundation.filter.FilterProfileDraft(
            id = "profile",
            name = "Profile",
            protectionLevel = com.lavasec.app.foundation.config.ProtectionLevel.BALANCED,
            enabledSourceIds = emptySet(),
        )
        return com.lavasec.app.product.NativeDomainActionHost(
            authenticationPolicy = com.lavasec.app.product.domain.DomainActionAuthenticationPolicy.SKIPPED,
            authenticator = com.lavasec.app.product.domain.DomainActionAuthenticator {
                com.lavasec.app.product.domain.DomainActionAuthenticationResult.Authorized
            },
            authority = com.lavasec.app.product.domain.ActiveFilterAuthorityPort {
                com.lavasec.app.product.domain.ActiveFilterAuthority(
                    0L,
                    profile,
                    hasUnsavedChanges = false,
                )
            },
            mutation = com.lavasec.app.product.domain.DomainActionMutationPort { _, _ ->
                com.lavasec.app.product.domain.DomainActionApplyResult.Applied(1L)
            },
        )
    }

    private class SwitchPort(
        private val behavior: suspend (String) -> com.lavasec.app.product.FilterProfileSwitchResult,
    ) : com.lavasec.app.product.FilterProfileSwitchPort {
        val requests = mutableListOf<String>()

        override suspend fun apply(targetProfileId: String): com.lavasec.app.product.FilterProfileSwitchResult {
            requests += targetProfileId
            return behavior(targetProfileId)
        }
    }

    private class ResolverPort(
        private val behavior: suspend (com.lavasec.app.foundation.resolver.MilestoneResolverPlan) ->
        com.lavasec.app.product.ResolverPlanSwitchResult,
    ) : com.lavasec.app.product.ResolverPlanSwitchPort {
        val requests = mutableListOf<com.lavasec.app.foundation.resolver.MilestoneResolverPlan>()

        override suspend fun apply(
            plan: com.lavasec.app.foundation.resolver.FilterResolverPlan,
        ): com.lavasec.app.product.ResolverPlanSwitchResult {
            requests += plan.plan
            return behavior(plan.plan)
        }
    }

    @Test fun `standalone native host retirement invokes cleanup and removes its actual staged rule draft`() = runTest {
        val security = testSecurity(setOf(ProtectedSurface.FILTERS)); val fixture = reviewedLibrary()
        val active = fixture.library.value!!.activeProfileId
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, libraryOwner = fixture.owner, libraryDraft = fixture.library))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        val activity = Any(); var alive = true
        val host = com.lavasec.app.reactnative.NativeVisitHost(activity, "original") { alive }
        val custody = com.lavasec.app.reactnative.NativeDomainReviewHostOwner({ vm.state.value.graphGeneration }, { host })
        var callbacks = 0
        var witness: MainProductViewModel.StandaloneDomainReviewRetirementWitness? = null
        try {
            advanceUntilIdle(); security.unlock("1234", ProtectedSurface.FILTERS)
            val visit = checkNotNull(custody.begin("original-token", 7, host))
            assertEquals(DomainStageOutcome.Staged(active), vm.stageDomainActionOwned("abandoned.example", ManualRuleTarget.BLOCKED, 7,
                present = { _, verify, _ -> verify("1234"); awaitCancellation() },
                isCurrent = { custody.isCurrent(visit) }, onDraftOpened = { original ->
                    witness = original
                    custody.attach(visit) { callbacks++; vm.retireStandaloneDomainReview(original) }
                }))
            assertEquals(setOf("abandoned.example"), fixture.owner.draftFor(active)!!.manualRules.blockedDomains)
            custody.retireHost(Any(), "original"); custody.retireHost(activity, "wrong-host")
            advanceUntilIdle(); assertEquals(0, callbacks); assertNotNull(fixture.owner.draftFor(active))
            alive = false; custody.retireHost(activity, "original"); custody.retireHost(activity, "original")
            advanceUntilIdle(); assertEquals(1, callbacks); assertNull(fixture.owner.draftFor(active))
            assertTrue(vm.retireStandaloneDomainReview(checkNotNull(witness)).await())
            assertNull(vm.sharedFilterDraftLease(7, active)); assertTrue(fixture.writes.isEmpty())
            assertFalse("abandoned.example" in fixture.library.value!!.profile(active)!!.manualRules.blockedDomains)
            custody.close(); advanceUntilIdle(); assertEquals(1, callbacks)
            // A replacement native host can actually stage again; no invisible unsaved draft survives.
            val freshHost = com.lavasec.app.reactnative.NativeVisitHost(Any(), "fresh") { true }
            val freshCustody = com.lavasec.app.reactnative.NativeDomainReviewHostOwner({ 7L }, { freshHost })
            val fresh = checkNotNull(freshCustody.begin("fresh-token", 7, freshHost))
            assertEquals(DomainStageOutcome.Staged(active), vm.stageDomainActionOwned("fresh.example", ManualRuleTarget.BLOCKED, 7,
                present = { _, verify, _ -> verify("1234"); awaitCancellation() },
                isCurrent = { freshCustody.isCurrent(fresh) }, onDraftOpened = { original ->
                    freshCustody.attach(fresh) { vm.retireStandaloneDomainReview(original) }
                }))
            freshCustody.close(); advanceUntilIdle(); assertNull(fixture.owner.draftFor(active))
        } finally { custody.close(); vm.close(); security.close() }
    }

    @Test fun `standalone host retirement cannot discard a same filter successor lease or its current review`() = runTest {
        val security = testSecurity(setOf(ProtectedSurface.FILTERS)); val fixture = reviewedLibrary()
        val active = fixture.library.value!!.activeProfileId
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, libraryOwner = fixture.owner, libraryDraft = fixture.library))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        val activity = Any(); val host = com.lavasec.app.reactnative.NativeVisitHost(activity, "original") { true }
        val custody = com.lavasec.app.reactnative.NativeDomainReviewHostOwner({ 7L }, { host }); var callbacks = 0
        var witness: MainProductViewModel.StandaloneDomainReviewRetirementWitness? = null
        try {
            advanceUntilIdle(); security.unlock("1234", ProtectedSurface.FILTERS)
            val visit = checkNotNull(custody.begin("original-token", 7, host))
            assertEquals(DomainStageOutcome.Staged(active), vm.stageDomainActionOwned("old.example", ManualRuleTarget.BLOCKED, 7,
                present = { _, verify, _ -> verify("1234"); awaitCancellation() },
                isCurrent = { custody.isCurrent(visit) }, onDraftOpened = { original ->
                    witness = original; custody.attach(visit) { callbacks++; vm.retireStandaloneDomainReview(original) }
                }))
            val oldLease = checkNotNull(witness).draftLease
            assertTrue(fixture.owner.discardDraft(active, null, null, oldLease))
            assertTrue(fixture.owner.beginEdit(active)); val freshLease = fixture.owner.draftLease(active)
            assertFalse(oldLease == freshLease)
            fixture.owner.addDomainToDraft(active, "successor.example", ManualRuleTarget.BLOCKED)
            val successorDraft = fixture.owner.draftFor(active)
            assertNull(fixture.owner.addDomainToDraft(active, "stale.example", ManualRuleTarget.BLOCKED, oldLease) { true })
            assertNull(fixture.owner.addDomainToDraft(active, "retired.example", ManualRuleTarget.BLOCKED, freshLease) { false })
            assertSame(successorDraft, fixture.owner.draftFor(active))
            assertFalse(fixture.owner.beginEdit(active) { false })
            assertEquals(freshLease, fixture.owner.draftLease(active))
            assertTrue(vm.requestFilterReview(active, 7) { _, verify, _ -> verify("1234"); awaitCancellation() })
            custody.retireHost(activity, "original"); advanceUntilIdle(); assertEquals(1, callbacks)
            assertFalse(vm.retireStandaloneDomainReview(checkNotNull(witness)).await())
            assertEquals(freshLease, vm.sharedFilterDraftLease(7, active))
            assertEquals(setOf("successor.example"), fixture.owner.draftFor(active)!!.manualRules.blockedDomains)
            assertTrue(vm.commitFilterReview(active, 7, present = { _, _, _ -> error("Reuse the current review grant") }))
            assertEquals(setOf("successor.example"), fixture.library.value!!.profile(active)!!.manualRules.blockedDomains)
        } finally { custody.close(); vm.close(); security.close() }
    }

    @Test fun `standalone cleanup cannot borrow a rotated same ID credential library`() = runTest {
        val security = testSecurity(setOf(ProtectedSurface.FILTERS))
        val freshSecurity = testSecurity(setOf(ProtectedSurface.FILTERS)); val oldFixture = reviewedLibrary()
        val freshFixture = reviewedLibrary(); val active = oldFixture.library.value!!.activeProfileId
        assertEquals(active, freshFixture.library.value!!.activeProfileId)
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, libraryOwner = oldFixture.owner, libraryDraft = oldFixture.library))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        val host = com.lavasec.app.reactnative.NativeVisitHost(Any(), "original") { true }
        val custody = com.lavasec.app.reactnative.NativeDomainReviewHostOwner({ vm.state.value.graphGeneration }, { host })
        var callbacks = 0; var witness: MainProductViewModel.StandaloneDomainReviewRetirementWitness? = null
        try {
            advanceUntilIdle(); security.unlock("1234", ProtectedSurface.FILTERS)
            val visit = checkNotNull(custody.begin("token", 7, host))
            assertEquals(DomainStageOutcome.Staged(active), vm.stageDomainActionOwned("old.example", ManualRuleTarget.BLOCKED, 7,
                present = { _, verify, _ -> verify("1234"); awaitCancellation() },
                isCurrent = { custody.isCurrent(visit) }, onDraftOpened = { original ->
                    witness = original; custody.attach(visit) { callbacks++; vm.retireStandaloneDomainReview(original) }
                }))
            factories.value = FakeOwnerFactory(8L, security = freshSecurity, libraryOwner = freshFixture.owner,
                libraryDraft = freshFixture.library); advanceUntilIdle()
            assertTrue(freshFixture.owner.beginEdit(active)); val freshLease = freshFixture.owner.draftLease(active)
            freshFixture.owner.addDomainToDraft(active, "new-graph.example", ManualRuleTarget.BLOCKED)
            custody.close(); advanceUntilIdle(); assertEquals(1, callbacks)
            assertFalse(vm.retireStandaloneDomainReview(checkNotNull(witness)).await())
            assertEquals(freshLease, vm.sharedFilterDraftLease(8, active))
            assertEquals(setOf("new-graph.example"), freshFixture.owner.draftFor(active)!!.manualRules.blockedDomains)
            assertTrue(oldFixture.writes.isEmpty()); assertTrue(freshFixture.writes.isEmpty())
        } finally { custody.close(); vm.close(); security.close(); freshSecurity.close() }
    }

    @Test fun `standalone Apply releases native custody without any later host discard callback`() = runTest {
        val security = testSecurity(setOf(ProtectedSurface.FILTERS)); val fixture = reviewedLibrary()
        val active = fixture.library.value!!.activeProfileId
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, libraryOwner = fixture.owner, libraryDraft = fixture.library))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        val activity = Any(); val host = com.lavasec.app.reactnative.NativeVisitHost(activity, "a") { true }
        val custody = com.lavasec.app.reactnative.NativeDomainReviewHostOwner({ 7L }, { host }); var callbacks = 0
        try {
            advanceUntilIdle(); security.unlock("1234", ProtectedSurface.FILTERS)
            val visit = checkNotNull(custody.begin("token", 7, host))
            assertEquals(DomainStageOutcome.Staged(active), vm.stageDomainActionOwned("saved.example", ManualRuleTarget.BLOCKED, 7,
                present = { _, verify, _ -> verify("1234"); awaitCancellation() },
                isCurrent = { custody.isCurrent(visit) }, onDraftOpened = { original ->
                    custody.attach(visit) { callbacks++; vm.retireStandaloneDomainReview(original) }
                }))
            assertTrue(vm.commitFilterReview(active, 7, present = { _, _, _ -> error("Reuse staged review authorization") }))
            assertTrue(custody.release(visit)); custody.retireHost(activity, "a"); custody.close(); advanceUntilIdle()
            assertEquals(0, callbacks); assertEquals(1, fixture.writes.size)
            assertEquals(setOf("saved.example"), fixture.library.value!!.profile(active)!!.manualRules.blockedDomains)
            assertNull(fixture.owner.draftFor(active))
        } finally { custody.close(); vm.close(); security.close() }
    }

    @Test fun `standalone destruction during suspended authentication stages nothing after late verification`() = runTest {
        val security = testSecurity(setOf(ProtectedSurface.FILTERS)); val fixture = reviewedLibrary()
        val active = fixture.library.value!!.activeProfileId
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, libraryOwner = fixture.owner, libraryDraft = fixture.library))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        val activity = Any(); var alive = true; val host = com.lavasec.app.reactnative.NativeVisitHost(activity, "a") { alive }
        val custody = com.lavasec.app.reactnative.NativeDomainReviewHostOwner({ 7L }, { host })
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var callbacks = 0
        try {
            advanceUntilIdle(); security.unlock("1234", ProtectedSurface.FILTERS)
            val visit = checkNotNull(custody.begin("token", 7, host))
            val pending = async { vm.stageDomainActionOwned("late.example", ManualRuleTarget.BLOCKED, 7,
                present = { _, verify, _ -> entered.complete(Unit); release.await(); verify("1234"); awaitCancellation() },
                isCurrent = { custody.isCurrent(visit) }, onDraftOpened = { original ->
                    custody.attach(visit) { callbacks++; vm.retireStandaloneDomainReview(original) }
                }) }
            runCurrent(); assertTrue(entered.isCompleted)
            alive = false; custody.retireHost(activity, "a"); release.complete(Unit); advanceUntilIdle()
            assertEquals(DomainStageOutcome.Unavailable, pending.await()); assertEquals(0, callbacks)
            assertNull(fixture.owner.draftFor(active)); assertTrue(fixture.writes.isEmpty())
        } finally { release.complete(Unit); custody.close(); vm.close(); security.close() }
    }

    @Test fun `late staged draft attachment after original host death invokes actual compensation once`() = runTest {
        val security = testSecurity(setOf(ProtectedSurface.FILTERS)); val fixture = reviewedLibrary()
        val active = fixture.library.value!!.activeProfileId
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, libraryOwner = fixture.owner, libraryDraft = fixture.library))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        val activity = Any(); var alive = true; val host = com.lavasec.app.reactnative.NativeVisitHost(activity, "a") { alive }
        val custody = com.lavasec.app.reactnative.NativeDomainReviewHostOwner({ 7L }, { host }); var callbacks = 0
        try {
            advanceUntilIdle(); security.unlock("1234", ProtectedSurface.FILTERS)
            val visit = checkNotNull(custody.begin("token", 7, host))
            assertEquals(DomainStageOutcome.Unavailable, vm.stageDomainActionOwned("late.example", ManualRuleTarget.BLOCKED, 7,
                present = { _, verify, _ -> verify("1234"); awaitCancellation() },
                isCurrent = { custody.isCurrent(visit) }, onDraftOpened = { original ->
                    assertNotNull(fixture.owner.draftFor(active))
                    alive = false; custody.retireHost(activity, "a")
                    custody.attach(visit) { callbacks++; vm.retireStandaloneDomainReview(original) }
                }))
            advanceUntilIdle(); assertEquals(1, callbacks); assertNull(fixture.owner.draftFor(active))
            assertNull(vm.sharedFilterDraftLease(7, active)); assertTrue(fixture.writes.isEmpty())
            custody.close(); assertEquals(1, callbacks)
        } finally { custody.close(); vm.close(); security.close() }
    }

    @Test fun `cancelled module staging schedules exact draft compensation on the surviving VM scope`() = runTest {
        val security = testSecurity(setOf(ProtectedSurface.FILTERS)); val fixture = reviewedLibrary()
        val active = fixture.library.value!!.activeProfileId
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
            security = security, libraryOwner = fixture.owner, libraryDraft = fixture.library))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        val moduleJob = SupervisorJob()
        val module = CoroutineScope(moduleJob + StandardTestDispatcher(testScheduler))
        val host = com.lavasec.app.reactnative.NativeVisitHost(Any(), "a") { true }
        val custody = com.lavasec.app.reactnative.NativeDomainReviewHostOwner({ 7L }, { host })
        var callbacks = 0; var witness: MainProductViewModel.StandaloneDomainReviewRetirementWitness? = null
        try {
            advanceUntilIdle(); security.unlock("1234", ProtectedSurface.FILTERS)
            val visit = checkNotNull(custody.begin("token", 7, host))
            val pending = module.async {
                vm.stageDomainActionOwned("cancelled.example", ManualRuleTarget.BLOCKED, 7,
                    present = { _, verify, _ -> verify("1234"); awaitCancellation() },
                    isCurrent = { custody.isCurrent(visit) }, onDraftOpened = { original ->
                        assertNotNull(fixture.owner.draftFor(active)); witness = original
                        val retained = custody.attach(visit) { callbacks++; vm.retireStandaloneDomainReview(original) }
                        moduleJob.cancel()
                        retained
                    })
            }
            advanceUntilIdle()
            // Authentication completes on the real IO dispatcher; drain of the test scheduler
            // alone does not establish that the module callback has cancelled its command.
            pending.join()
            assertTrue("The original module command must remain cancelled", pending.isCancelled)
            assertTrue("Compensation must survive cancellation of that module",
                vm.retireStandaloneDomainReview(checkNotNull(witness)).await())
            assertNull(fixture.owner.draftFor(active)); assertNull(vm.sharedFilterDraftLease(7, active))
            assertTrue(fixture.writes.isEmpty())
            custody.close(); advanceUntilIdle(); assertEquals(1, callbacks)
            assertNull(fixture.owner.draftFor(active))
        } finally { moduleJob.cancel(); custody.close(); vm.close(); security.close() }
    }

    @Test fun `cancelled standalone begin waiting for real admission retires only its original detail`() = runTest {
        for (withSuccessor in listOf(false, true)) {
            val security = testSecurity(setOf(ProtectedSurface.FILTERS)); val fixture = reviewedLibrary()
            val saved = checkNotNull(fixture.library.value); val active = saved.activeProfileId
            val other = saved.profiles.first { it.id != active }.id
            val factories = MutableStateFlow<CredentialProductOwnerFactory?>(FakeOwnerFactory(7L,
                security = security, libraryOwner = fixture.owner, libraryDraft = fixture.library))
            val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
            // Observe the real native detail StateFlow and hold the owner's actual lock; no
            // production hook, fake begin outcome, source counter or queued mutation is used.
            @Suppress("UNCHECKED_CAST")
            val selected = MainProductViewModel::class.java.getDeclaredField("selectedFilterId")
                .apply { isAccessible = true }.get(vm) as MutableStateFlow<String?>
            val admission = FilterLibraryOwner::class.java.getDeclaredField("admission")
                .apply { isAccessible = true }.get(fixture.owner) as kotlinx.coroutines.sync.Mutex
            val held = CompletableDeferred<Unit>(); val failureSeen = CompletableDeferred<Throwable>()
            var attached = 0
            val observer = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
                selected.first { id ->
                    if (id != active) false else {
                        assertTrue("The actual edit lock must be free before native detail publication", admission.tryLock())
                        held.complete(Unit)
                        true
                    }
                }
            }
            try {
                advanceUntilIdle(); security.unlock("1234", ProtectedSurface.FILTERS)
                val pending = async {
                    try {
                        vm.stageDomainActionOwned("never-staged.example", ManualRuleTarget.BLOCKED, 7,
                            present = { _, verify, _ -> verify("1234"); awaitCancellation() },
                            isCurrent = { true }, onDraftOpened = { attached++; true })
                    } catch (failure: Throwable) { failureSeen.complete(failure); throw failure }
                }
                runCurrent()
                // Fresh passcode verification runs on SecurityOwner's IO scope. Wait for
                // the real lock witness, or fail if the original command finishes first.
                kotlinx.coroutines.selects.select<Unit> {
                    held.onAwait { }
                    pending.onAwait {
                        throw AssertionError("The original stage completed before its admission lock was held")
                    }
                }
                runCurrent()
                assertTrue("The real beginEdit must wait after native detail opens", held.isCompleted)
                assertFalse(pending.isCompleted); assertEquals(active, vm.state.value.selectedFilterId)
                assertEquals(0, attached); assertNull(fixture.owner.draftFor(active))
                if (withSuccessor) {
                    vm.openFilterDetail(other); runCurrent()
                    assertEquals(other, vm.state.value.selectedFilterId)
                }
                val original = kotlinx.coroutines.CancellationException("original domain command cancelled")
                pending.cancel(original); runCurrent()
                assertTrue(pending.isCancelled)
                val observed = failureSeen.await()
                // Coroutine stack-trace recovery may copy a suspended cancellation; it must
                // retain the cancellation type and the exact original exception as its cause.
                assertTrue("The original failure must remain cancellation",
                    observed is kotlinx.coroutines.CancellationException)
                assertEquals("Recovery must preserve the exact cancellation type", original.javaClass, observed.javaClass)
                assertSame("Recovery must retain the exact original cancellation",
                    original, if (observed === original) observed else observed.cause)
                assertEquals(0, attached); assertNull(fixture.owner.draftFor(active))
                if (withSuccessor) {
                    assertEquals("Old compensation must preserve the ordinary successor", other, vm.state.value.selectedFilterId)
                    assertEquals(other, vm.state.value.filterDetail!!.filterId)
                } else {
                    assertNull("The cancelled original detail must close on the surviving VM", vm.state.value.selectedFilterId)
                    assertNull(vm.state.value.filterDetail)
                }
                assertEquals(saved, fixture.library.value); assertTrue(fixture.writes.isEmpty())
                admission.unlock(); advanceUntilIdle()
                assertNull(fixture.owner.draftFor(active)); assertTrue(fixture.writes.isEmpty())
            } finally {
                if (held.isCompleted && admission.isLocked) admission.unlock()
                observer.cancel(); vm.close(); security.close()
            }
        }
    }
}
