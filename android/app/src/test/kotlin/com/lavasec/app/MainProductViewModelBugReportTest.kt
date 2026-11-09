package com.lavasec.app

import android.content.Intent
import com.lavasec.app.diagnostics.BugReportEnvironment
import com.lavasec.app.diagnostics.BugReportReview
import com.lavasec.app.diagnostics.BugReportSendResult
import com.lavasec.app.diagnostics.NativeBugReportOwner
import com.lavasec.app.onboarding.OnboardingOwner
import com.lavasec.app.platform.VpnPreparation
import com.lavasec.app.platform.VpnPreparationResult
import com.lavasec.app.presentation.settings.BugReportFailureUi
import com.lavasec.app.presentation.settings.BugReportStepUi
import com.lavasec.app.presentation.settings.BugReportTopicUi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MainProductViewModelBugReportTest {
    @Test fun `review captures native bytes and only explicit consent plus receipt completes`() = runTest {
        val submitted = mutableListOf<BugReportReview>()
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(Factory(1) {
            submitted += it
            BugReportSendResult.SENT
        })
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        review(vm)
        advanceUntilIdle()
        val preview = checkNotNull(vm.state.value.bugReport.reviewedPreview)
        assertTrue(preview.contains("shop.example.com"))
        assertTrue(preview.contains("user@example.com"))
        assertFalse(vm.submitBugReport())
        assertTrue(submitted.isEmpty())
        vm.setBugReportConsentGranted(true)
        assertTrue(vm.submitBugReport())
        advanceUntilIdle()
        val body = JSONObject(submitted.single().requestBytes().toString(Charsets.UTF_8))
        assertTrue(body.getString("user_description").contains("shop.example.com"))
        assertFalse(body.has("app"))
        assertFalse(body.has("recent_dns_events"))
        assertEquals("user@example.com", body.getString("contact_email"))
        assertEquals(BugReportStepUi.SUBMITTED, vm.state.value.bugReport.step)
        vm.close()
    }

    @Test fun `uncertain delivery keeps exact bytes and retry must receive success`() = runTest {
        val sent = mutableListOf<BugReportReview>()
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(Factory(1) {
            sent += it
            if (sent.size == 1) BugReportSendResult.FAILED else BugReportSendResult.SENT
        })
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        review(vm)
        vm.setBugReportConsentGranted(true)
        assertFalse(vm.submitBugReport())
        advanceUntilIdle()
        assertEquals(BugReportFailureUi.SEND_FAILED, vm.state.value.bugReport.failure)
        assertEquals(BugReportStepUi.CONSENT, vm.state.value.bugReport.step)
        assertTrue(vm.submitBugReport())
        assertSame(sent[0], sent[1])
        assertArrayEquals(sent[0].requestBytes(), sent[1].requestBytes())
        vm.close()
    }

    @Test fun `editing or toggling diagnostics revokes consent and requires new review`() = runTest {
        val sent = mutableListOf<BugReportReview>()
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(Factory(1) { sent += it; BugReportSendResult.FAILED })
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        review(vm)
        vm.setBugReportConsentGranted(true)
        assertFalse(vm.submitBugReport())
        vm.backBugReportStep()
        vm.setBugReportDiagnosticsConsent(true)
        assertFalse(vm.submitBugReport())
        vm.advanceBugReportStep()
        assertFalse(vm.submitBugReport())
        vm.setBugReportConsentGranted(true)
        assertFalse(vm.submitBugReport())
        assertNotEquals(sent[0].reportID, sent[1].reportID)
        assertTrue(JSONObject(sent[1].requestBytes().toString(Charsets.UTF_8)).has("app"))
        vm.close()
    }

    @Test fun `double tap and draft changes cannot alter a report while sending`() = runTest {
        val gate = CompletableDeferred<BugReportSendResult>()
        var sends = 0
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(Factory(1) { sends++; gate.await() })
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        advanceUntilIdle(); review(vm); vm.setBugReportConsentGranted(true)
        val first = async { vm.submitBugReport() }; runCurrent()
        assertFalse(vm.submitBugReport())
        vm.setBugReportDescription("replacement")
        vm.discardBugReport()
        gate.complete(BugReportSendResult.SENT)
        assertTrue(first.await()); advanceUntilIdle()
        assertEquals(1, sends)
        assertEquals("Site will not load", vm.state.value.bugReport.description)
        vm.close()
    }

    @Test fun `retiring credential owner cancels send and revokes reviewed consent`() = runTest {
        val gate = CompletableDeferred<BugReportSendResult>()
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(Factory(1) { gate.await() })
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        advanceUntilIdle(); review(vm); vm.setBugReportConsentGranted(true)
        val sending = async { vm.submitBugReport() }; runCurrent()
        factories.value = Factory(2) { BugReportSendResult.SENT }
        advanceUntilIdle()
        assertTrue(sending.isCancelled)
        assertNull(vm.state.value.bugReport.reviewedPreview)
        assertFalse(vm.state.value.bugReport.consentGranted)
        assertFalse(vm.submitBugReport())
        gate.complete(BugReportSendResult.SENT)
        assertEquals(BugReportStepUi.ATTACHMENTS, vm.state.value.bugReport.step)
        vm.close()
    }

    @Test fun `unavailable report service cannot show successful submission`() = runTest {
        val factories = MutableStateFlow<CredentialProductOwnerFactory?>(Factory(1))
        val vm = MainProductViewModel(factories, {}, StandardTestDispatcher(testScheduler))
        advanceUntilIdle(); review(vm); vm.setBugReportConsentGranted(true)
        assertFalse(vm.submitBugReport()); advanceUntilIdle()
        assertEquals(BugReportFailureUi.DRAFT_FAILED, vm.state.value.bugReport.failure)
        assertEquals(BugReportStepUi.CONSENT, vm.state.value.bugReport.step)
        vm.close()
    }

    private fun review(vm: MainProductViewModel) {
        vm.selectBugReportTopic(BugReportTopicUi.WEBSITE_ACCESS)
        vm.setBugReportAffectedSite("shop.example.com")
        vm.setBugReportDescription("Site will not load")
        vm.setBugReportContactEmail("user@example.com")
        repeat(3) { vm.advanceBugReportStep() }
    }

    private class Factory(val generation: Long, val send: (suspend (BugReportReview) -> BugReportSendResult)? = null) : CredentialProductOwnerFactory {
        override fun create(scope: CoroutineScope) = CredentialProductOwners(
            graphGeneration = generation,
            generationScope = CoroutineScope(kotlinx.coroutines.SupervisorJob(scope.coroutineContext[kotlinx.coroutines.Job])),
            onboarding = OnboardingOwner(onboardingCompleted = false, vpnPreparation = VpnPreparation<Intent> { VpnPreparationResult.Prepared }, commit = {}, scope = scope),
            protection = null, domainActions = null, activity = null, privacy = null, diagnostics = null,
            reports = send?.let { NativeBugReportOwner(
                { BugReportEnvironment("1.0", "1", "9", 28, "off", null, 42) }, it, scope,
            ) },
            onClose = {},
        )
        override fun close() = Unit
    }
}
