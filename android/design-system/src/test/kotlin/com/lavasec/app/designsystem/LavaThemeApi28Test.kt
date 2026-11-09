package com.lavasec.app.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LavaThemeApi28Test {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `dynamic request falls back to the static Lava scheme before Android 12`() {
        var primary = Color.Unspecified
        var semantic = LavaLightSemanticColors
        compose.setContent {
            LavaTheme(darkTheme = false, useDynamicColor = true) {
                primary = MaterialTheme.colorScheme.primary
                semantic = LocalLavaSemanticColors.current
            }
        }

        compose.runOnIdle {
            assertEquals(LavaLightColorScheme.primary, primary)
            assertEquals(LavaLightSemanticColors, semantic)
        }
    }
}
