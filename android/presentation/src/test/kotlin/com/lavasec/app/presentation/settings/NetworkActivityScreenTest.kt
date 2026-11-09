package com.lavasec.app.presentation.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lavasec.app.designsystem.LavaTheme
import com.lavasec.app.foundation.activity.NetworkActivityCapturedState
import com.lavasec.app.foundation.activity.NetworkActivityConnectivityState
import com.lavasec.app.foundation.activity.NetworkActivityEvent
import com.lavasec.app.foundation.activity.NetworkActivityNetworkKind
import com.lavasec.app.foundation.activity.NetworkActivityProtectionState
import com.lavasec.app.foundation.activity.NetworkActivityResolverIdentity
import com.lavasec.app.foundation.activity.NetworkActivityResolverTransport
import com.lavasec.app.foundation.health.ProtectionFallback
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.presentation.activity.ActivityContentState
import com.lavasec.app.presentation.activity.NetworkActivityRowUi
import com.lavasec.app.presentation.activity.NetworkActivitySnapshot
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NetworkActivityScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var previousLocale: Locale
    private lateinit var previousTimeZone: TimeZone

    @Before
    fun useDeterministicLocalTime() {
        previousLocale = Locale.getDefault()
        previousTimeZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
    }

    @After
    fun restoreLocalTime() {
        Locale.setDefault(previousLocale)
        TimeZone.setDefault(previousTimeZone)
    }

    @Test
    fun `privacy panel renders the local-only copy with the retention line`() {
        setScreen(ActivityContentState.Ready(snapshot(emptyList())))

        compose.onNodeWithTag("network_activity_privacy_panel").assertIsDisplayed()
        compose.onNodeWithText("Stays on this device").assertExists()
        compose.onNodeWithText(
            "A local log of connection and protection events on this device. It's sent to us " +
                "only if you attach it to a bug report.",
        ).assertExists()
        compose.onNodeWithText("Kept on this device for 7 days.").assertExists()
        compose.onNodeWithTag("network_activity_retention_count").assertExists()
        compose.onNodeWithText("Up to 300 entries are kept.").assertExists()
    }

    @Test
    fun `rows render the theme pill with the event line and captured state line`() {
        val row = NetworkActivityRowUi(
            id = 1L,
            timestampMillis = 1_753_600_000_000L,
            event = NetworkActivityEvent.NetworkChanged(
                from = NetworkActivityNetworkKind.WIFI,
                to = NetworkActivityNetworkKind.CELLULAR,
                pathSatisfied = true,
            ),
            capturedState = capturedState(),
            occurrenceCount = 1,
        )
        setScreen(ActivityContentState.Ready(snapshot(listOf(row))))

        compose.onNodeWithTag("network_activity_row").assertDoesNotExist()
        compose.onAllNodesWithTag("network_activity_theme_pill")[0].assertExists()
        compose.onNodeWithText("Network Change").assertExists()
        compose.onNodeWithText("Network changed: Wi-Fi to Cellular").assertExists()
        compose.onNodeWithText(
            "Lava: Healthy, Device DNS, Device fallback off",
        ).assertExists()
    }

    @Test
    fun `rolled up rows surface the occurrence count suffix`() {
        val row = NetworkActivityRowUi(
            id = 1L,
            timestampMillis = 1_753_600_000_000L,
            event = NetworkActivityEvent.ProtectionConnected,
            capturedState = capturedState(),
            occurrenceCount = 4,
        )
        setScreen(ActivityContentState.Ready(snapshot(listOf(row))))

        compose.onNodeWithText("Connected (×4)").assertExists()
        compose.onNodeWithText("Protection").assertExists()
    }

    @Test
    fun `empty retained log renders the iOS no activity yet state`() {
        setScreen(ActivityContentState.Ready(snapshot(emptyList())))

        compose.onNodeWithTag("network_activity_empty").assertExists()
        compose.onNodeWithText("No network activity yet").assertExists()
        compose.onNodeWithTag("network_activity_clear").assertDoesNotExist()
    }

    @Test
    fun `disabled retention renders the honest off state without rows`() {
        setScreen(ActivityContentState.Disabled)

        compose.onNodeWithTag("network_activity_disabled").assertExists()
        compose.onNodeWithText("Local network activity off").assertExists()
        compose.onNodeWithText(
            "Saved network activity entries will be cleared and new network activity entries " +
                "will not be saved.",
        ).assertExists()
        compose.onNodeWithTag("network_activity_theme_pill").assertDoesNotExist()
    }

    @Test
    fun `unavailable surface renders the honest unavailable panel`() {
        setScreen(ActivityContentState.Unavailable)

        compose.onNodeWithTag("network_activity_unavailable").assertExists()
        compose.onNodeWithText("Local Activity evidence is unavailable.").assertExists()
    }

    @Test
    fun `clear requires an explicit confirmation and dispatches only on confirm`() {
        var clears = 0
        setScreen(
            state = ActivityContentState.Ready(snapshot(listOf(connectedRow()))),
            onClear = { clears += 1 },
        )

        compose.onNodeWithTag("network_activity_clear").performClick()
        compose.runOnIdle { assertEquals(0, clears) }
        compose.onNodeWithText("Clear local network activity?").assertExists()
        compose.onNodeWithText(
            "This removes saved network activity entries from this device. Filtering counts " +
                "and domain history are unchanged.",
        ).assertExists()
        compose.onNodeWithTag("network_activity_clear_confirm").performClick()
        compose.runOnIdle { assertEquals(1, clears) }
    }

    @Test
    fun `clear controls are absent without callbacks`() {
        setScreen(ActivityContentState.Ready(snapshot(listOf(connectedRow()))))

        compose.onNodeWithTag("network_activity_clear").assertDoesNotExist()
    }

    @Test
    fun `failed refresh retains rows and offers retry`() {
        var retries = 0
        setScreen(
            state = ActivityContentState.Failed(
                message = "store unavailable",
                lastGood = snapshot(listOf(connectedRow())),
            ),
            onRefresh = { retries += 1 },
        )

        compose.onNodeWithText("Network Activity could not be refreshed").assertExists()
        compose.onNodeWithText("store unavailable").assertExists()
        compose.onNodeWithTag("activity_retry_button").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    private fun setScreen(
        state: ActivityContentState<NetworkActivitySnapshot>,
        onClear: (() -> Unit)? = null,
        onRefresh: (() -> Unit)? = null,
    ) {
        compose.setContent {
            LavaTheme {
                NetworkActivityScreen(
                    state = state,
                    loadingMore = false,
                    onBack = {},
                    onRefresh = onRefresh,
                    onLoadMore = {},
                    onClear = onClear,
                )
            }
        }
    }

    private fun snapshot(rows: List<NetworkActivityRowUi>) = NetworkActivitySnapshot(
        rows = rows,
        nextPageKey = null,
    )

    private fun connectedRow() = NetworkActivityRowUi(
        id = 2L,
        timestampMillis = 1_753_600_000_000L,
        event = NetworkActivityEvent.ProtectionConnected,
        capturedState = capturedState(),
        occurrenceCount = 1,
    )

    private fun capturedState() = NetworkActivityCapturedState(
        protection = NetworkActivityProtectionState.PROTECTED,
        connectivity = NetworkActivityConnectivityState.HEALTHY,
        networkKind = NetworkActivityNetworkKind.WIFI,
        networkPathSatisfied = true,
        resolver = NetworkActivityResolverIdentity.DEVICE_DNS,
        resolverTransport = NetworkActivityResolverTransport.DEVICE_UDP,
        fallbackCoverage = ResolverFallbackCoverage.NONE,
        activeFallback = ProtectionFallback.NONE,
    )
}
