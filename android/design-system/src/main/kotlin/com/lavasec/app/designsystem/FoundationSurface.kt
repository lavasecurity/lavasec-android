package com.lavasec.app.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.material3.Surface

@Composable
fun FoundationSurface(content: @Composable () -> Unit = {}) {
    LavaTheme {
        Surface(content = content)
    }
}
