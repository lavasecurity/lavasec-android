package com.lavasec.app.designsystem

import android.provider.Settings
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LavaThemeRuntimeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `static scheme is default and semantic colors stay pinned`() {
        lateinit var semantic: LavaSemanticColors
        compose.setContent {
            LavaTheme(darkTheme = false) {
                semantic = LocalLavaSemanticColors.current
                assertEquals(LavaLightColorScheme, MaterialTheme.colorScheme)
            }
        }
        compose.runOnIdle { assertEquals(LavaLightSemanticColors, semantic) }
    }

    @Test
    fun `animator scale changes update reduced motion live`() {
        val systemResolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        Settings.Global.putFloat(systemResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        lateinit var notify: () -> Unit
        compose.setContent {
            val resolver = LocalContext.current.contentResolver
            notify = {
                Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
                resolver.notifyChange(
                    Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                    null,
                )
            }
            LavaTheme(reducedMotion = null) {
                Text(
                    text = "motion",
                    modifier = Modifier.testTag("reduced_${LocalLavaReducedMotion.current}"),
                )
            }
        }

        compose.runOnIdle { notify() }
        compose.waitForIdle()
        compose.onNodeWithTag("reduced_true").assertExists()
    }

    @Test
    fun `explicit reduced motion override wins over the system`() {
        compose.setContent {
            val resolver = LocalContext.current.contentResolver
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
            LavaTheme(reducedMotion = false) {
                Text(
                    text = "motion",
                    modifier = Modifier.testTag("reduced_${LocalLavaReducedMotion.current}"),
                )
            }
        }

        compose.onNodeWithTag("reduced_false").assertExists()
    }
}
