package com.lavasec.app.presentation.settings

import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.lavasec.app.designsystem.LavaTheme
import com.lavasec.app.foundation.security.LockTimeoutOption
import com.lavasec.app.foundation.security.ProtectedSurface
import com.lavasec.app.presentation.ProductDestination
import com.lavasec.app.presentation.ProductNavigation
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
class SecuritySettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `unavailable state renders the honest panel without controls`() {
        setScreen(state = SecurityUiState.Unavailable, callbacks = callbacks())

        compose.onNodeWithTag("security_unavailable_panel").assertExists()
        compose.onNodeWithTag("security_set_passcode").assertDoesNotExist()
    }

    @Test
    fun `recovery failure offers only the reset action`() {
        var reset = false
        setScreen(
            state = SecurityUiState.RecoveryFailure,
            callbacks = callbacks().copy(onResetAfterRecoveryFailure = { reset = true }),
        )

        compose.onNodeWithText("The saved passcode verifier could not be read.").assertExists()
        compose.onNodeWithTag("security_recovery_reset").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(reset) }
        // Ordinary native Security retains its historical reset-then-new-passcode behavior.
        compose.onNodeWithTag("security_new_passcode_field").assertExists()
    }

    @Test
    fun `native recovery return resets without mounting a different passcode setup dialog`() {
        val state = mutableStateOf<SecurityUiState>(SecurityUiState.RecoveryFailure)
        var resets = 0
        var setups = 0
        setScreen(stateProvider = { state.value }, callbacks = callbacks().copy(
            recoveryReturnsToCaller = true,
            onResetAfterRecoveryFailure = { resets++; state.value = SecurityUiState.Disabled(LockTimeoutOption.IMMEDIATELY) },
            onSetPasscode = { setups++ },
        ))
        compose.onNodeWithText("Reset passcode and return").assertExists()
        compose.onNodeWithText("Set a new passcode").assertDoesNotExist()
        compose.onNodeWithTag("security_recovery_reset").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, resets); assertEquals(0, setups) }
        compose.onNodeWithTag("security_new_passcode_field").assertDoesNotExist()
        compose.onNodeWithTag("security_confirm_passcode_field").assertDoesNotExist()
    }

    @Test
    fun `disabled state offers setup and explains the local unlock guarantee`() {
        var passcode: String? = null
        setScreen(
            state = SecurityUiState.Disabled(LockTimeoutOption.IMMEDIATELY),
            callbacks = callbacks().copy(onSetPasscode = { passcode = it }),
        )

        compose.onNodeWithText("Unlock happens on this device. A local unlock key is kept safe on this phone. Lava never sees it.")
            .assertExists()
        compose.onNodeWithTag("security_set_passcode").performScrollTo().performClick()
        // Setup asks for the new passcode twice; a mismatch keeps the dialog open.
        compose.onNodeWithTag("security_new_passcode_field").performTextInput("1234")
        compose.onNodeWithTag("security_confirm_passcode_field").performTextInput("4321")
        compose.onNodeWithText("Confirm").performClick()
        compose.onNodeWithTag("security_passcode_mismatch").assertExists()
        compose.runOnIdle { assertEquals(null, passcode) }
        // A matching pair dispatches the owner's setup with the exact code.
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("security_set_passcode").performClick()
        compose.onNodeWithTag("security_new_passcode_field").performTextInput("1234")
        compose.onNodeWithTag("security_confirm_passcode_field").performTextInput("1234")
        compose.onNodeWithText("Confirm").performClick()
        compose.runOnIdle { assertEquals("1234", passcode) }
    }

    @Test
    fun `enabled state exposes protected surfaces without retired timeout choices`() {
        val timeouts = mutableListOf<LockTimeoutOption>()
        val surfaces = mutableListOf<Pair<ProtectedSurface, Boolean>>()
        setScreen(
            state = enabled(),
            callbacks = callbacks().copy(
                onLockTimeoutSelected = { timeouts += it },
                onProtectedSurfaceChanged = { surface, enabled -> surfaces += surface to enabled },
            ),
        )

        compose.onNodeWithTag("security_timeout_5_minutes").assertDoesNotExist()
        compose.onNodeWithTag("security_surface_activity_switch").performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(timeouts.isEmpty())
            assertEquals(listOf(ProtectedSurface.ACTIVITY to false), surfaces)
        }
    }

    @Test
    fun `control and pause switches are separate from screen protection`() {
        val changed = mutableListOf<Pair<ProtectedSurface, Boolean>>()
        setScreen(state = enabled(), callbacks = callbacks().copy(
            onProtectedSurfaceChanged = { surface, selected -> changed += surface to selected },
        ))
        compose.onNodeWithTag("security_surface_protection_control_switch").performScrollTo().performClick()
        compose.onNodeWithTag("security_surface_protection_pause_switch").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(ProtectedSurface.PROTECTION_CONTROL to false,
                ProtectedSurface.PROTECTION_PAUSE to false), changed)
            // App Unlock is a real whole-app lock; this test is about the control/pause surfaces
            // being separate from the screen protection, so exclude it from the fixture.
            assertFalse(
                enabled(protectedSurfaces = ProtectedSurface.ALL - ProtectedSurface.APP_UNLOCK)
                    .requiresUnlock(com.lavasec.app.presentation.ProductNavigation.guard, 0L),
            )
        }
    }

    @Test
    fun `changing the passcode verifies the current one first`() {
        var change: Pair<String, String>? = null
        setScreen(
            state = enabled(),
            callbacks = callbacks().copy(onChangePasscode = { current, new -> change = current to new }),
        )

        compose.onNodeWithTag("security_change_passcode").performScrollTo().performClick()
        compose.onNodeWithTag("security_current_passcode_field").performTextInput("1234")
        compose.onNodeWithTag("security_new_passcode_field").performTextInput("5678")
        compose.onNodeWithTag("security_confirm_passcode_field").performTextInput("5678")
        compose.onNodeWithText("Confirm").performClick()
        compose.runOnIdle { assertEquals("1234" to "5678", change) }
    }

    @Test
    fun `removing the passcode requires the current one behind a confirmation`() {
        var removed: String? = null
        setScreen(
            state = enabled(),
            callbacks = callbacks().copy(onRemovePasscode = { removed = it }),
        )

        compose.onNodeWithTag("security_remove_passcode").performScrollTo().performClick()
        compose.onNodeWithText("Turn off Security passcode?").assertExists()
        compose.onNodeWithTag("security_remove_passcode_field").performTextInput("1234")
        compose.onNodeWithText("Turn off").performClick()
        compose.runOnIdle { assertEquals("1234", removed) }
    }

    @Test
    fun `the unlock decision uses current turn grants and ignores persisted timestamps`() {
        val security = enabled(
            lastUnlockEpochMillis = 1_000L,
            lockTimeout = LockTimeoutOption.ONE_MINUTE,
            protectedSurfaces = setOf(ProtectedSurface.ACTIVITY),
        )

        // A persisted timestamp is not a grant, even inside the old timeout.
        assertTrue(security.requiresUnlock(activity, 1_000L + 59_000L))
        val granted = security.copy(authenticatedSurfaces = setOf(ProtectedSurface.ACTIVITY))
        assertFalse(granted.requiresUnlock(activity, Long.MAX_VALUE))
        assertFalse(granted.requiresUnlock(topDomains, Long.MAX_VALUE))
        // A revoked grant re-locks regardless of the old timeout.
        assertTrue(security.requiresUnlock(activity, 1_000L + 60_001L))
        // A child evidence route shares the Activity family gate.
        assertTrue(security.requiresUnlock(topDomains, Long.MAX_VALUE))
        assertTrue(security.requiresUnlock(domainHistory, Long.MAX_VALUE))
        for (destination in listOf(ProductDestination.NetworkActivity, ProductDestination.NerdStats)) {
            val route = ProductNavigation.settings.open(destination)
            assertTrue(security.requiresUnlock(route, Long.MAX_VALUE))
            assertFalse(granted.requiresUnlock(route, Long.MAX_VALUE))
        }
        // Filters is not a protected surface here and never re-locks — nor
        // does any of its children.
        assertFalse(security.requiresUnlock(filters, Long.MAX_VALUE))
        assertFalse(security.requiresUnlock(filterDetail, Long.MAX_VALUE))
        // The Security route itself never gates on the session.
        assertFalse(security.requiresUnlock(securityRoute, Long.MAX_VALUE))
    }

    @Test
    fun `a filters-family lock covers every child route`() {
        val security = enabled(protectedSurfaces = setOf(ProtectedSurface.FILTERS))
        for (route in listOf(filters, filterLibrary, filterDetail, filterReview, filterPreparation)) {
            assertTrue(security.requiresUnlock(route, Long.MAX_VALUE))
        }
    }

    @Test
    fun `no turn grant requires authentication regardless of legacy timeout`() {
        val security = enabled(lockTimeout = LockTimeoutOption.IMMEDIATELY)
        assertTrue(security.requiresUnlock(activity, 2_000L))
    }

    @Test
    fun `a wrong passcode keeps the change dialog open and a verified change closes it`() {
        val state = mutableStateOf<SecurityUiState>(enabled())
        setScreen(stateProvider = { state.value }, callbacks = callbacks())

        compose.onNodeWithTag("security_change_passcode").performScrollTo().performClick()
        compose.onNodeWithTag("security_current_passcode_field").performTextInput("wrong")
        compose.onNodeWithTag("security_new_passcode_field").performTextInput("5678")
        compose.onNodeWithTag("security_confirm_passcode_field").performTextInput("5678")
        compose.onNodeWithText("Confirm").performClick()

        // The owner reports WRONG_PASSCODE: the dialog stays open with the
        // inline error so the user can retry.
        compose.runOnIdle {
            state.value = enabled(failure = SecurityFailureUi.WRONG_PASSCODE)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("security_current_passcode_field").assertExists()
        compose.onNodeWithText("Wrong passcode").assertExists()

        // A verified change advances lastChangeEpochMillis: the dialog closes.
        compose.runOnIdle {
            state.value = enabled(lastChangeEpochMillis = 7L)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("security_current_passcode_field").assertDoesNotExist()
    }

    @Test
    fun `a wrong passcode keeps the remove dialog open`() {
        val state = mutableStateOf<SecurityUiState>(enabled())
        setScreen(stateProvider = { state.value }, callbacks = callbacks())

        compose.onNodeWithTag("security_remove_passcode").performScrollTo().performClick()
        compose.onNodeWithTag("security_remove_passcode_field").performTextInput("wrong")
        compose.onNodeWithText("Turn off").performClick()

        compose.runOnIdle {
            state.value = enabled(failure = SecurityFailureUi.WRONG_PASSCODE)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("security_remove_passcode_field").assertExists()
        compose.onNodeWithText("Wrong passcode").assertExists()
    }

    private fun enabled(
        lastUnlockEpochMillis: Long? = 0L,
        lastChangeEpochMillis: Long? = null,
        lockTimeout: LockTimeoutOption = LockTimeoutOption.IMMEDIATELY,
        protectedSurfaces: Set<ProtectedSurface> = ProtectedSurface.ALL,
        failure: SecurityFailureUi? = null,
    ) = SecurityUiState.Enabled(
        lastUnlockEpochMillis = lastUnlockEpochMillis,
        lastChangeEpochMillis = lastChangeEpochMillis,
        lockTimeout = lockTimeout,
        protectedSurfaces = protectedSurfaces,
        inFlight = false,
        failure = failure,
    )

    private val activity = ProductNavigation.guard.open(ProductDestination.Activity)
    private val topDomains = activity.open(ProductDestination.TopDomains)
    private val domainHistory = activity.open(ProductDestination.DomainHistory)
    private val filters = ProductNavigation.guard.open(ProductDestination.Filters)
    private val filterLibrary = filters.open(ProductDestination.FilterLibrary)
    private val filterDetail = filters.open(ProductDestination.FilterDetail)
    private val filterReview = filterDetail.open(ProductDestination.FilterReview)
    private val filterPreparation = filterReview.open(ProductDestination.FilterPreparation)
    private val securityRoute = ProductNavigation.settings.open(ProductDestination.Security)

    private fun callbacks() = SecurityCallbacks(
        onSetPasscode = {},
        onChangePasscode = { _, _ -> },
        onRemovePasscode = {},
        onUnlock = {},
        onLockTimeoutSelected = {},
        onProtectedSurfaceChanged = { _, _ -> },
        onResetAfterRecoveryFailure = {},
    )

    private fun setScreen(
        state: SecurityUiState? = null,
        callbacks: SecurityCallbacks? = null,
        stateProvider: (() -> SecurityUiState)? = null,
    ) {
        compose.setContent {
            LavaTheme {
                SecuritySettingsScreen(
                    state = stateProvider?.invoke() ?: state ?: SecurityUiState.Unavailable,
                    callbacks = callbacks,
                    onBack = {},
                    modifier = Modifier.requiredWidth(400.dp).requiredHeight(800.dp),
                )
            }
        }
    }
}
