package com.lavasec.app.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun LavaPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val semanticColors = LocalLavaSemanticColors.current
    Card(
        modifier = modifier.fillMaxWidth(),
        // The one designated compact-panel radius token; navigation cards use
        // LavaRadii.card so the two surface families never mix radii ad hoc.
        shape = RoundedCornerShape(LavaRadii.compact),
        colors = CardDefaults.cardColors(containerColor = semanticColors.panelBackground),
        border = BorderStroke(1.dp, semanticColors.panelStroke),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(LavaSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(LavaSpacing.md),
            content = { content() },
        )
    }
}

@Composable
fun LavaSection(
    title: String,
    modifier: Modifier = Modifier,
    footer: String? = null,
    contentSpacing: Dp = LavaNavigationCardMetrics.rowSpacing,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() }.padding(horizontal = LavaSpacing.xs),
            style = LavaScreenTypography.sectionHeading,
        )
        Spacer(modifier = Modifier.height(LavaMetrics.sectionLabelToFirstCard))
        Column(verticalArrangement = Arrangement.spacedBy(contentSpacing)) {
            content()
        }
        footer?.let {
            Spacer(modifier = Modifier.height(LavaSpacing.sm))
            Text(
                text = it,
                modifier = Modifier.padding(horizontal = LavaSpacing.xs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun LavaNavigationCard(
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    accessory: @Composable RowScope.() -> Unit = {
        Icon(
            imageVector = LavaIcons.Forward,
            contentDescription = null,
            modifier = Modifier.size(LavaIconSizes.control),
        )
    },
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val badgeColor = if (enabled) colors.primaryContainer else colors.primaryContainer.copy(alpha = 0.38f)
    val iconColor = if (enabled) colors.primary else colors.primary.copy(alpha = 0.38f)
    val summaryColor = if (enabled) colors.onSurfaceVariant else colors.onSurfaceVariant.copy(alpha = 0.38f)
    // mergeDescendants makes the whole row ONE focusable button: TalkBack reads the
    // title and summary together instead of landing on the icon, badge, or chevron.
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        enabled = enabled,
        colors = CardDefaults.cardColors(
            containerColor = colors.surfaceContainer,
            contentColor = colors.onSurface,
            disabledContainerColor = colors.surfaceContainer,
            disabledContentColor = colors.onSurface.copy(alpha = 0.38f),
        ),
        // The one designated navigation-card radius token.
        shape = RoundedCornerShape(LavaRadii.card),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = LavaNavigationCardMetrics.rowMinHeight)
                .padding(horizontal = LavaNavigationCardMetrics.horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(LavaMetrics.badgeToTextGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingIcon?.let {
                Box(
                    modifier = Modifier
                        .size(LavaNavigationCardMetrics.badgeSize)
                        .background(badgeColor, RoundedCornerShape(LavaRadii.iconBadge))
                        .testTag("lava_navigation_leading_badge"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier
                            .size(LavaIconSizes.control)
                            .testTag("lava_navigation_leading_icon"),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(LavaSpacing.xs),
            ) {
                Text(title, style = LavaScreenTypography.rowTitle)
                Text(summary, style = LavaScreenTypography.rowSummary, color = summaryColor)
            }
            accessory()
        }
    }
}
