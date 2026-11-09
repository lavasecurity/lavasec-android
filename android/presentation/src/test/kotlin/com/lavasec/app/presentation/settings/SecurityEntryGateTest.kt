package com.lavasec.app.presentation.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.lavasec.app.presentation.ProductDestination
import com.lavasec.app.presentation.ProductNavigation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SecurityEntryGateTest {
    @get:Rule val compose = createComposeRule()
    private val entry = SecurityEntryIdentity(ProductNavigation.settings.open(ProductDestination.Security), 0, 1, 0)

    @Test
    fun `credential grant admits page once across same-page settings changes`() {
        val result = CompletableDeferred<Boolean>()
        val label = mutableStateOf("Initial controls")
        var requests = 0
        var composed = false
        compose.setContent {
            SecurityEntryGate(entry, true, SecurityEntryAccess.Readable, {
                requests++
                result.await()
            }, {}) {
                composed = true
                Text(label.value)
            }
        }
        compose.runOnIdle { assertFalse(composed); assertEquals(1, requests) }
        compose.runOnIdle { result.complete(true) }
        compose.onNodeWithText("Initial controls").assertExists()
        compose.runOnIdle { label.value = "Changed choices and passcode" }
        compose.onNodeWithText("Changed choices and passcode").assertExists()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test
    fun `credential publication masks an admitted page without reauthenticating after success`() {
        val access = mutableStateOf(SecurityEntryAccess.Readable)
        var requests = 0
        compose.setContent {
            SecurityEntryGate(entry, true, access.value, { requests++; true }, {}) { Text("Controls") }
        }
        compose.onNodeWithText("Controls").assertExists()
        compose.runOnIdle { access.value = SecurityEntryAccess.Updating }
        compose.onNodeWithText("Controls").assertDoesNotExist()
        compose.runOnIdle { access.value = SecurityEntryAccess.Readable }
        compose.onNodeWithText("Controls").assertExists()
        compose.runOnIdle { assertEquals(1, requests); access.value = SecurityEntryAccess.Updating }
        compose.onNodeWithText("Controls").assertDoesNotExist()
        compose.runOnIdle { access.value = SecurityEntryAccess.Recovery }
        compose.runOnIdle { access.value = SecurityEntryAccess.Readable }
        compose.onNodeWithText("Controls").assertExists()
        compose.runOnIdle { assertEquals(2, requests) }
    }

    @Test
    fun `entry during a credential write waits for its authoritative result`() {
        val access = mutableStateOf(SecurityEntryAccess.Updating)
        var requests = 0
        compose.setContent {
            SecurityEntryGate(entry, true, access.value, { requests++; true }, {}) { Text("Controls") }
        }
        compose.onNodeWithText("Controls").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, requests); access.value = SecurityEntryAccess.Readable }
        compose.onNodeWithText("Controls").assertExists()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test
    fun `unknown authority waits and recovery UI remains reachable without authentication`() {
        val access = mutableStateOf(SecurityEntryAccess.Unknown)
        var requests = 0
        compose.setContent {
            SecurityEntryGate(entry, true, access.value, { requests++; true }, {}) {
                Text(if (access.value == SecurityEntryAccess.Recovery) "Recovery" else "Controls")
            }
        }
        compose.onNodeWithText("Controls").assertDoesNotExist()
        compose.runOnIdle { access.value = SecurityEntryAccess.Recovery }
        compose.onNodeWithText("Recovery").assertExists()
        compose.runOnIdle { assertEquals(0, requests); access.value = SecurityEntryAccess.Readable }
        compose.onNodeWithText("Controls").assertExists()
        compose.runOnIdle { assertEquals(1, requests) }
        // Losing readability discards the admission; recovery cannot grant future access.
        compose.runOnIdle { access.value = SecurityEntryAccess.Recovery }
        compose.onNodeWithText("Recovery").assertExists()
        compose.runOnIdle { access.value = SecurityEntryAccess.Readable }
        compose.onNodeWithText("Controls").assertExists()
        compose.runOnIdle { assertEquals(2, requests) }
    }

    @Test
    fun `background graph rotation and external entries each discard page admission`() {
        val identity = mutableStateOf(entry)
        val foreground = mutableStateOf(true)
        val results = mutableListOf<CompletableDeferred<Boolean>>()
        compose.setContent {
            SecurityEntryGate(identity.value, foreground.value, SecurityEntryAccess.Readable, {
                CompletableDeferred<Boolean>().also { results += it }.await()
            }, {}) { Text("Controls") }
        }
        compose.runOnIdle { results.last().complete(true) }
        compose.onNodeWithText("Controls").assertExists()
        compose.runOnIdle { foreground.value = false }
        compose.onNodeWithText("Controls").assertDoesNotExist()
        compose.runOnIdle { foreground.value = true }
        compose.onNodeWithText("Controls").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, results.size); results.last().complete(true) }
        compose.onNodeWithText("Controls").assertExists()
        for (next in listOf(entry.copy(graphGeneration = 2), entry.copy(graphGeneration = 2, externalSequence = 1),
            entry.copy(graphGeneration = 2, externalSequence = 1, foregroundEpoch = 1))) {
            compose.runOnIdle { identity.value = next }
            compose.onNodeWithText("Controls").assertDoesNotExist()
            compose.runOnIdle { results.last().complete(true) }
            compose.onNodeWithText("Controls").assertExists()
        }
        compose.runOnIdle { assertEquals(5, results.size) }
    }

    @Test
    fun `cancelled authentication never composes controls`() {
        var cancellations = 0
        var composed = false
        compose.setContent {
            SecurityEntryGate(entry, true, SecurityEntryAccess.Readable, { false }, { cancellations++ }) {
                composed = true
            }
        }
        compose.runOnIdle { assertEquals(1, cancellations); assertFalse(composed) }
    }

    @Test
    fun `late completion cannot reveal or cancel a different entry`() {
        val identity = mutableStateOf(entry)
        val first = CompletableDeferred<Boolean>()
        val second = CompletableDeferred<Boolean>()
        var requests = 0
        var cancellations = 0
        compose.setContent {
            SecurityEntryGate(identity.value, true, SecurityEntryAccess.Readable, {
                val result = if (requests++ == 0) first else second
                withContext(NonCancellable) { result.await() }
            }, { cancellations++ }) { Text("Controls") }
        }
        compose.runOnIdle { identity.value = entry.copy(externalSequence = 1) }
        compose.runOnIdle { first.complete(true) }
        compose.onNodeWithText("Controls").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, cancellations); second.complete(true) }
        compose.onNodeWithText("Controls").assertExists()
        compose.runOnIdle { identity.value = entry.copy(navigation = ProductNavigation.settings) }
        compose.runOnIdle { identity.value = entry.copy(externalSequence = 2) }
        compose.runOnIdle { assertEquals(3, requests) }
    }
}
