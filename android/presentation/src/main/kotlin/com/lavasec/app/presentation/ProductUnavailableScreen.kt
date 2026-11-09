package com.lavasec.app.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.lavasec.app.designsystem.LavaMetrics
import com.lavasec.app.designsystem.LavaPanel
import com.lavasec.app.designsystem.LavaScreenTypography
import com.lavasec.app.designsystem.LavaSpacing

@Composable
fun ProductUnavailableScreen(reason: String?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = LavaMetrics.screenInset,
                end = LavaMetrics.screenInset,
                top = LavaSpacing.screenTop,
                bottom = LavaMetrics.navSurfaceClearance,
            ),
        verticalArrangement = Arrangement.spacedBy(LavaSpacing.lg),
    ) {
        Text(
            text = stringResource(R.string.product_unavailable_title),
            style = LavaScreenTypography.screenTitle,
            modifier = Modifier.semantics { heading() },
        )
        LavaPanel(modifier = Modifier.testTag("product_unavailable_panel")) {
            Text(
                text = reason ?: stringResource(R.string.product_unavailable_detail),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
