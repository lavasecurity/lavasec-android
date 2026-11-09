package com.lavasec.app.presentation.settings

import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.lavasec.app.designsystem.LavaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BugReportFlowTest {
    @Test
    fun `the flow advances topic to details to attachments to consent to submit`() {
        var state = BugReportUiState()
        assertFalse(state.canAdvance())

        state = state.selectTopic(BugReportTopicUi.VPN_OR_FILTER)
        assertTrue(state.canAdvance())
        state = state.advance()
        assertEquals(BugReportStepUi.DETAILS, state.step)

        assertFalse(state.canAdvance())
        state = state.setDescription("protection keeps dropping")
        assertTrue(state.canAdvance())
        state = state.advance()
        assertEquals(BugReportStepUi.ATTACHMENTS, state.step)

        // Back returns to the previous step with the draft intact.
        state = state.backStep()
        assertEquals(BugReportStepUi.DETAILS, state.step)
        assertEquals("protection keeps dropping", state.description)
        state = state.advance()

        state = state.setDiagnosticsConsent(true)
        state = state.advance()
        assertEquals(BugReportStepUi.CONSENT, state.step)
        assertFalse(state.canSubmit())

        state = state.copy(reviewedPreview = "Reviewed report").setConsentGranted(true)
        assertTrue(state.canSubmit())

        state = state.markSubmitting()
        assertFalse(state.canSubmit())
        state = state.markSubmitted()
        assertEquals(BugReportStepUi.SUBMITTED, state.step)

        // Discard drops the reviewed content and consent.
        state = state.discard()
        assertEquals(BugReportStepUi.TOPIC, state.step)
        assertEquals(null, state.topic)
        assertEquals("", state.description)
        assertEquals(null, state.reviewedPreview)
    }

    @Test
    fun `descriptions are capped and failures are typed never raw`() {
        val state = BugReportUiState()
            .setDescription("x".repeat(BugReportUiState.MAX_DESCRIPTION_LENGTH + 50))
        assertEquals(BugReportUiState.MAX_DESCRIPTION_LENGTH, state.descriptionPreview.length)
        assertEquals(
            BugReportFailureUi.DRAFT_FAILED,
            state.markFailed(BugReportFailureUi.DRAFT_FAILED).failure,
        )
    }

    @Test
    fun `the attachments preview preserves deliberately entered website and contact text`() {
        val state = BugReportUiState()
            .selectTopic(BugReportTopicUi.WEBSITE_ACCESS)
            .advance()
            .setDescription("opening shop.example.com with user@example.com id 123456789 fails")
            .advance()

        assertEquals(
            "opening shop.example.com with user@example.com id 123456789 fails",
            state.descriptionPreview,
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BugReportScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the screen walks the steps and gates submit on explicit consent`() {
        val submissions = mutableListOf<BugReportSubmission>()
        var done = false
        setScreen(
            initial = BugReportUiState(),
            onSubmit = { submissions += it },
            onDone = { done = true },
        )

        // Topic step: Continue disabled until a topic is chosen.
        compose.onNodeWithTag("bug_report_continue").assertIsNotEnabled()
        compose.onNodeWithTag("bug_report_topic_website_access").performScrollTo().performClick()
        compose.onNodeWithTag("bug_report_continue").performScrollTo().performClick()

        // Details step.
        compose.onNodeWithTag("bug_report_affected_site").performScrollTo().performTextInput("shop.example.com")
        compose.onNodeWithTag("bug_report_description")
            .performScrollTo()
            .performTextInput("opening shop.example.com fails")
        compose.onNodeWithTag("bug_report_continue").performScrollTo().performClick()

        // Attachments step: explicit text stays visible, optional diagnostics default off.
        compose.onNodeWithText("What this report contains").assertExists()
        compose.onNodeWithText("opening shop.example.com fails").assertExists()
        compose.onNodeWithTag("bug_report_include_diagnostics").performScrollTo().performClick()
        compose.onNodeWithTag("bug_report_continue").performScrollTo().performClick()

        // Consent step: Submit stays disabled until the explicit consent lands.
        compose.onNodeWithTag("bug_report_submit").assertIsNotEnabled()
        compose.onNodeWithTag("bug_report_consent_checkbox").performScrollTo().performClick()
        compose.onNodeWithTag("bug_report_submit").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("bug_report_submit").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, submissions.size)
            val submission = submissions.single()
            assertEquals(BugReportTopicUi.WEBSITE_ACCESS, submission.topic)
            assertEquals("opening shop.example.com fails", submission.description)
            assertTrue(submission.includeDiagnostics)
            assertEquals("shop.example.com", submission.affectedSite)
        }

        // The submitted state thanks the user and Done returns to the route owner.
        compose.onNodeWithText("Thank you, Lava will look into this").assertExists()
        compose.onNodeWithTag("bug_report_done").performClick()
        compose.runOnIdle { assertTrue(done) }
    }

    @Test
    fun `back pops one step and the top back button asks before discarding`() {
        val discarded = mutableListOf<Unit>()
        val backed = mutableListOf<Unit>()
        setScreen(
            initial = BugReportUiState(),
            onDiscard = { discarded += Unit },
            onBack = { backed += Unit },
        )

        compose.onNodeWithTag("bug_report_topic_vpn_or_filter").performScrollTo().performClick()
        compose.onNodeWithTag("bug_report_continue").performScrollTo().performClick()
        compose.onNodeWithTag("bug_report_back").performScrollTo().performClick()
        compose.onNodeWithTag("bug_report_topic_vpn_or_filter").assertExists()

        // The route-level back (top) confirms the discard, never silently drops.
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Discard feedback?").assertExists()
        compose.onNodeWithText("Discard").performClick()
        compose.runOnIdle {
            assertEquals(1, discarded.size)
            // Confirming the discard also leaves the route.
            assertEquals(1, backed.size)
        }
    }

    @Test
    fun `a failed submission renders the honest failure panel`() {
        setScreen(
            initial = BugReportUiState()
                .selectTopic(BugReportTopicUi.OTHER)
                .advance()
                .setDescription("details")
                .advance()
                .advance()
                .markFailed(BugReportFailureUi.DRAFT_FAILED),
        )

        compose.onNodeWithText("Could not send feedback").assertExists()
        compose.onNodeWithText("The report could not be prepared. Go back to review your draft and try again.")
            .assertExists()
    }

    private fun setScreen(
        initial: BugReportUiState,
        onSubmit: (BugReportSubmission) -> Unit = {},
        onDone: () -> Unit = {},
        onDiscard: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        compose.setContent {
            LavaTheme {
                var state by androidx.compose.runtime.remember {
                    mutableStateOf(initial)
                }
                BugReportScreen(
                    state = state,
                    callbacks = BugReportCallbacks(
                        onTopicSelected = { state = state.selectTopic(it) },
                        onDescriptionChanged = { state = state.setDescription(it) },
                        onDiagnosticsConsentChanged = { state = state.setDiagnosticsConsent(it) },
                        onAffectedSiteChanged = { state = state.setAffectedSite(it) },
                        onContactEmailChanged = { state = state.setContactEmail(it) },
                        onConsentGrantedChanged = { state = state.setConsentGranted(it) },
                        onAdvance = { state = state.advance().let { if (it.step == BugReportStepUi.CONSENT) it.copy(reviewedPreview = "Reviewed report") else it } },
                        onBack = { state = state.backStep() },
                        onSubmit = {
                            onSubmit(
                                BugReportSubmission(
                                    topic = checkNotNull(state.topic),
                                    description = state.description,
                                    includeDiagnostics = state.includeDiagnostics,
                                    affectedSite = state.affectedSite,
                                    contactEmail = state.contactEmail,
                                ),
                            )
                            state = state.markSubmitting().markSubmitted()
                        },
                        onDiscard = {
                            state = state.discard()
                            onDiscard()
                        },
                        onDone = onDone,
                    ),
                    onBack = onBack,
                    modifier = Modifier.requiredWidth(400.dp).requiredHeight(800.dp),
                )
            }
        }
    }
}
