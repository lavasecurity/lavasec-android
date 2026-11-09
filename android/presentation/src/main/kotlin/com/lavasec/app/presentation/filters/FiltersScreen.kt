package com.lavasec.app.presentation.filters

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.lavasec.app.designsystem.LavaIconSizes
import com.lavasec.app.designsystem.LavaIcons
import com.lavasec.app.designsystem.LavaMetrics
import com.lavasec.app.designsystem.LavaNavigationCardMetrics
import com.lavasec.app.designsystem.LavaNavigationCard
import com.lavasec.app.designsystem.LavaPanel
import com.lavasec.app.designsystem.LavaRadii
import com.lavasec.app.designsystem.LavaScreenTypography
import com.lavasec.app.designsystem.LavaSettingsIcons
import com.lavasec.app.designsystem.LavaSection
import com.lavasec.app.designsystem.LavaSpacing
import com.lavasec.app.designsystem.LocalLavaContentInsets
import com.lavasec.app.designsystem.LocalLavaSemanticColors
import com.lavasec.app.designsystem.lavaScreenScrollPadding
import com.lavasec.app.presentation.R

/**
 * The two connection states the overview card lets the user preview to learn how Lava behaves.
 * Purely educational (user-driven) — deliberately NOT coupled to live tunnel health, matching
 * iOS `FilterConnectionPreview`. The poor state teaches the fail-closed behavior: when the phone can't
 * reach the internet, Lava blocks sites to stay safe. Framed as the phone's connectivity, not
 * "Lava can't reach its filter" — the filter is local, so that framing would be misleading.
 */
enum class FilterConnectionPreview(val labelResource: Int, val captionResource: Int) {
    NORMAL(
        R.string.filters_connection_normal,
        R.string.filters_connection_normal_caption,
    ),
    POOR(
        R.string.filters_connection_poor,
        R.string.filters_connection_poor_caption,
    ),
}

/**
 * Navigation-only intents the overview owns. Always supplied by the host when library state
 * exists; null drops the "What's filtering?" rows entirely instead of rendering dead ones.
 */
data class FilterOverviewNavigation(
    val onOpenActiveDetail: () -> Unit,
    val onOpenLibrary: () -> Unit,
)

/**
 * Filters overview — the iOS `FiltersView.swift` tab: the educational connection-preview card
 * with the Phone → Lava → Internet diagram, the local-filter explanation, and "What's
 * filtering?" (Now filtering + Your filters). "Got a good filter?" (Share/Import) is
 * deliberately absent: the share-payload contract ships in this slice, but the share/import UI
 * rows land with the platform import path — the overview must not show inert buttons.
 *
 * @param state null when the product cannot supply filter facts (before onboarding, or when
 * the confirmed configuration is unavailable); the screen then keeps the honest "unavailable"
 * panel instead of inventing rows or controls.
 */
@Composable
fun FiltersScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    state: FilterLibraryState? = null,
    navigation: FilterOverviewNavigation? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .lavaScreenScrollPadding(LocalLavaContentInsets.current)
            .testTag("filters_screen"),
        verticalArrangement = Arrangement.spacedBy(LavaMetrics.sectionSpacing),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = LavaIcons.Back,
                contentDescription = stringResource(R.string.navigate_back),
            )
        }
        Text(
            text = stringResource(R.string.filters_title),
            style = LavaScreenTypography.screenTitle,
            modifier = Modifier.semantics { heading() },
        )
        val library = state
        if (library == null) {
            LavaPanel(modifier = Modifier.testTag("filters_unavailable_panel")) {
                Text(
                    text = stringResource(R.string.filters_live_unavailable),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.filters_live_unavailable_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            FilterOverviewCard()
            FilterLocalExplanationPanel()
            if (navigation != null) {
                WhatIsFilteringSection(
                    library = library,
                    navigation = navigation,
                )
            }
        }
    }
}

/** The connection-preview card: picker, diagram, and the state caption (iOS FiltersOverviewPanel). */
@Composable
private fun FilterOverviewCard(modifier: Modifier = Modifier) {
    var preview by remember { mutableStateOf(FilterConnectionPreview.NORMAL) }
    LavaPanel(modifier = modifier.testTag("filters_overview_card")) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(LavaSpacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ConnectionPreviewSelector(
                preview = preview,
                onPreviewChange = { preview = it },
            )
            FiltersFlowDiagram(blockedSecondHop = preview == FilterConnectionPreview.POOR)
            Text(
                text = stringResource(preview.captionResource),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag("filters_connection_caption"),
            )
        }
    }
}

/** "When the connection is [Normal ▾]" — a lead-in label plus a tappable state chip. */
@Composable
private fun ConnectionPreviewSelector(
    preview: FilterConnectionPreview,
    onPreviewChange: (FilterConnectionPreview) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val semantic = LocalLavaSemanticColors.current
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(LavaSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.filters_connection_lead_in),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            Surface(
                onClick = { expanded = true },
                modifier = Modifier.testTag("filters_connection_preview_chip"),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(LavaRadii.pill),
                color = semantic.attentionBackground,
                contentColor = semantic.attentionText,
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = LavaSpacing.md, vertical = LavaSpacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(LavaSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(preview.labelResource),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Icon(
                        imageVector = LavaIcons.ChevronDown,
                        contentDescription = null,
                        modifier = Modifier.size(LavaIconSizes.inline),
                    )
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.testTag("filters_connection_preview_menu"),
            ) {
                FilterConnectionPreview.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(stringResource(option.labelResource)) },
                        trailingIcon = {
                            if (option == preview) {
                                Icon(
                                    imageVector = LavaIcons.Check,
                                    contentDescription = null,
                                    tint = LocalLavaSemanticColors.current.attentionText,
                                    modifier = Modifier.size(LavaIconSizes.inline),
                                )
                            }
                        },
                        onClick = {
                            onPreviewChange(option)
                            expanded = false
                        },
                        modifier = Modifier.testTag(
                            "filters_connection_preview_option_${option.name.lowercase()}",
                        ),
                    )
                }
            }
        }
    }
}

/**
 * One-glance explanation of where Lava sits: Phone → Lava shield → Internet. The plan pins
 * 40dp endpoint/node bounds and 16dp horizontal spacing between nodes and hops; labels sit
 * under each node and wrap at large font scales so the diagram stays legible.
 */
@Composable
fun FiltersFlowDiagram(
    blockedSecondHop: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().testTag("filters_flow_diagram"),
        horizontalArrangement = Arrangement.spacedBy(LavaSpacing.lg),
        verticalAlignment = Alignment.Top,
    ) {
        DiagramNode(
            icon = LavaIcons.Phone,
            label = stringResource(R.string.filters_diagram_phone),
        )
        DiagramHop(blocked = false)
        DiagramNode(
            icon = LavaIcons.Guard,
            label = "Lava",
        )
        DiagramHop(blocked = blockedSecondHop)
        DiagramNode(
            icon = LavaSettingsIcons.Globe,
            label = stringResource(R.string.filters_diagram_internet),
            dimmed = blockedSecondHop,
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.DiagramNode(
    icon: ImageVector,
    label: String,
    dimmed: Boolean = false,
) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LavaSpacing.sm),
    ) {
        Box(modifier = Modifier.size(LavaIconSizes.node), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (dimmed) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(LavaIconSizes.endpoint),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DiagramHop(
    blocked: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(top = LavaSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = LavaIcons.ArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                alpha = if (blocked) 0.3f else 0.6f,
            ),
            modifier = Modifier.size(LavaIconSizes.small),
        )
        if (blocked) {
            Icon(
                imageVector = LavaIcons.XMark,
                contentDescription = stringResource(R.string.filters_diagram_blocked_hop),
                tint = LocalLavaSemanticColors.current.danger,
                modifier = Modifier.size(LavaIconSizes.inline),
            )
        }
    }
}

/** The local-filter explanation, pinned to the iOS local-filtering copy. */
@Composable
private fun FilterLocalExplanationPanel(modifier: Modifier = Modifier) {
    LavaPanel(modifier = modifier.testTag("filters_local_explanation")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(LavaSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = LavaSettingsIcons.Lock,
                contentDescription = null,
                tint = LocalLavaSemanticColors.current.safe,
                modifier = Modifier.size(LavaIconSizes.endpoint),
            )
            Column(verticalArrangement = Arrangement.spacedBy(LavaSpacing.xs)) {
                Text(
                    text = stringResource(R.string.filters_local_explanation_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.filters_local_explanation_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun WhatIsFilteringSection(
    library: FilterLibraryState,
    navigation: FilterOverviewNavigation,
) {
    val active = library.activeFilter
    // A loaded-but-empty filter is zero protection, not calm silence: the row swaps its
    // verbatim name summary for the localized warning (iOS FilterInEffectRow).
    val summary = if (active.blocksNothing) {
        stringResource(R.string.filters_blocks_nothing_unprotected)
    } else {
        active.name
    }
    LavaSection(
        title = stringResource(R.string.filters_whats_filtering),
        contentSpacing = LavaNavigationCardMetrics.interCardSpacing,
    ) {
        LavaNavigationCard(
            title = stringResource(R.string.filters_now_filtering),
            summary = summary,
            leadingIcon = LavaIcons.PlayCircle,
            modifier = Modifier.testTag("filters_now_filtering_row"),
            onClick = navigation.onOpenActiveDetail,
        )
        LavaNavigationCard(
            title = stringResource(R.string.filters_your_filters),
            summary = stringResource(R.string.filters_your_filters_summary),
            leadingIcon = LavaIcons.Filters,
            modifier = Modifier.testTag("filters_your_filters_row"),
            onClick = navigation.onOpenLibrary,
        )
    }
}
