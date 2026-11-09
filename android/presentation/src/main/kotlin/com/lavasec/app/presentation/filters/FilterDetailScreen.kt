package com.lavasec.app.presentation.filters

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.lavasec.app.designsystem.LavaIconSizes
import com.lavasec.app.designsystem.LavaIcons
import com.lavasec.app.designsystem.LavaMetrics
import com.lavasec.app.designsystem.LavaNavigationCardMetrics
import com.lavasec.app.designsystem.LavaPanel
import com.lavasec.app.designsystem.LavaScreenTypography
import com.lavasec.app.designsystem.LavaSettingsIcons
import com.lavasec.app.designsystem.LavaSection
import com.lavasec.app.designsystem.LavaSpacing
import com.lavasec.app.designsystem.LocalLavaContentInsets
import com.lavasec.app.designsystem.LocalLavaSemanticColors
import com.lavasec.app.designsystem.lavaScreenScrollPadding
import com.lavasec.app.foundation.filter.ManualRuleEditResult
import com.lavasec.app.foundation.filter.ManualRuleRejection
import com.lavasec.app.foundation.filter.ManualRuleTarget
import com.lavasec.app.presentation.R
import kotlinx.coroutines.launch

/**
 * One filter's detail ("My list") — the iOS `FilterMyListView.swift` `MyListCover`: the
 * rules panel (in-effect rule count or the honest empty-filter alarm / the not-in-effect
 * line), the block shelf (blocklist sources + blocked domains) and the allow shelf (allowed
 * exceptions), with edit mode
 * adding the source toggles, the two domain sheets, and the review/commit flow.
 *
 * The shown contents are the saved filter overlaid with its per-filter draft — the screen
 * itself never mutates state; every edit goes through [actions] into the generation-fenced
 * owner. A frozen filter renders read-only with the frozen explanation (no edit affordance).
 */
@Composable
fun FilterDetailScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    state: FilterDetailState? = null,
    actions: FilterLibraryActions? = null,
) {
    var showingBlockDomainSheet by remember { mutableStateOf(false) }
    var showingExceptionSheet by remember { mutableStateOf(false) }
    var pendingDiscardConfirm by remember { mutableStateOf(false) }

    val detail = state
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .lavaScreenScrollPadding(LocalLavaContentInsets.current)
            .testTag("filter_detail_screen"),
        verticalArrangement = Arrangement.spacedBy(LavaMetrics.sectionSpacing),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = LavaIcons.Back,
                contentDescription = stringResource(R.string.navigate_back),
            )
        }
        // The filter's own name — user data, rendered verbatim as the title.
        Text(
            text = detail?.name ?: stringResource(R.string.filters_title),
            style = LavaScreenTypography.screenTitle,
            modifier = Modifier.semantics { heading() },
        )
        if (detail == null) {
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
            FilterRulesPanel(detail)
            if (detail.frozen) {
                LavaPanel(modifier = Modifier.testTag("filters_frozen_panel")) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(LavaSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = LavaSettingsIcons.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(LavaIconSizes.inline),
                        )
                        Text(
                            text = stringResource(R.string.filters_frozen_detail),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            LavaSection(
                title = stringResource(R.string.filters_detail_blocks_title),
                contentSpacing = LavaNavigationCardMetrics.interCardSpacing,
            ) {
                BlocksShelf(
                    detail = detail,
                    editing = detail.editing && !detail.frozen,
                    onToggleSource = actions?.onToggleSource,
                    onRemoveBlocked = actions?.onRemoveDomain,
                )
                if (detail.editing && !detail.frozen && actions != null) {
                    // Sources are toggled in place from the shelf rows (the signed catalog is
                    // the only source list on Android), so the shelf's only add control is the
                    // blocked-domain sheet — no separate "Add a blocklist" row that would have
                    // nothing honest to open.
                    AddRow(
                        title = stringResource(R.string.filters_block_domain),
                        testTag = "filters_add_blocked_domain",
                        onClick = { showingBlockDomainSheet = true },
                    )
                }
            }
            LavaSection(
                title = stringResource(R.string.filters_detail_allows_title),
                contentSpacing = LavaNavigationCardMetrics.interCardSpacing,
            ) {
                AllowsShelf(
                    detail = detail,
                    editing = detail.editing && !detail.frozen,
                    onRemoveAllowed = actions?.onRemoveDomain,
                )
                if (detail.editing && !detail.frozen && actions != null) {
                    AddRow(
                        title = stringResource(R.string.filters_add_exception),
                        testTag = "filters_add_allowed_exception",
                        onClick = { showingExceptionSheet = true },
                    )
                }
            }
            if (actions != null && detail.editing && !detail.frozen) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            if (detail.draftHasChanges) {
                                pendingDiscardConfirm = true
                            } else {
                                actions.onDiscardDraft(detail.filterId)
                            }
                        },
                        enabled = !detail.busy,
                        modifier = Modifier.weight(1f).testTag("filters_discard_draft"),
                    ) {
                        Text(stringResource(R.string.filters_close_edit_mode))
                    }
                    Button(
                        onClick = { actions.onOpenReview(detail.filterId) },
                        enabled = detail.draftHasChanges && !detail.busy,
                        modifier = Modifier.weight(1f).testTag("filters_open_review"),
                    ) {
                        Text(stringResource(R.string.filters_save))
                    }
                }
            } else if (actions != null && !detail.frozen && !detail.editing) {
                OutlinedButton(
                    onClick = { actions.onBeginEdit(detail.filterId) },
                    modifier = Modifier.fillMaxWidth().testTag("filters_begin_edit"),
                ) {
                    Icon(
                        imageVector = LavaIcons.Pencil,
                        contentDescription = null,
                        modifier = Modifier.size(LavaIconSizes.inline),
                    )
                    Text(stringResource(R.string.filters_edit))
                }
            }
        }
    }

    if (detail != null && actions != null && showingBlockDomainSheet) {
        BlockedDomainSheet(
            detail = detail,
            onAdd = actions.onAddBlockedDomain,
            onDismiss = { showingBlockDomainSheet = false },
        )
    }
    if (detail != null && actions != null && showingExceptionSheet) {
        AllowedExceptionSheet(
            detail = detail,
            onAdd = actions.onAddAllowedException,
            onDismiss = { showingExceptionSheet = false },
        )
    }
    if (pendingDiscardConfirm && detail != null && actions != null) {
        AlertDialog(
            onDismissRequest = { pendingDiscardConfirm = false },
            modifier = Modifier.testTag("filters_discard_draft_dialog"),
            title = { Text(stringResource(R.string.filters_discard_title)) },
            text = { Text(stringResource(R.string.filters_discard_draft_detail)) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDiscardConfirm = false
                        actions.onDiscardDraft(detail.filterId)
                    },
                    modifier = Modifier.testTag("filters_discard_draft_confirm"),
                ) {
                    Text(stringResource(R.string.filters_discard))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingDiscardConfirm = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

@Composable
private fun FilterRulesPanel(detail: FilterDetailState, modifier: Modifier = Modifier) {
    LavaPanel(modifier = modifier.testTag("filters_rules_panel")) {
        val count = detail.ruleCount
        if (count != null) {
            Text(
                text = pluralStringResource(R.plurals.filters_rule_count, count, count),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.filters_rules_in_effect),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            detail.blocksNothing -> {
                Text(
                    text = stringResource(
                        if (detail.inEffect) {
                            R.string.filters_blocks_nothing_unprotected
                        } else {
                            R.string.filters_blocks_nothing
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = LocalLavaSemanticColors.current.attentionText,
                    modifier = Modifier.testTag("filters_empty_alarm"),
                )
            }
            detail.inEffect -> {
                Text(
                    text = stringResource(R.string.filters_catalog_pinned_note),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                Text(
                    text = stringResource(R.string.filters_not_in_effect),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BlocksShelf(
    detail: FilterDetailState,
    editing: Boolean,
    onToggleSource: ((suspend (String, String, Boolean) -> Boolean))?,
    onRemoveBlocked: ((String, String, ManualRuleTarget) -> Unit)?,
) {
    if (detail.sources.none { it.selected } && detail.blockedDomains.isEmpty()) {
        LavaPanel(modifier = Modifier.testTag("filters_blocks_empty")) {
            Text(
                text = stringResource(R.string.filters_no_blocklists),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (editing) {
                Text(
                    text = stringResource(R.string.filters_no_blocklists_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    val scope = rememberCoroutineScope()
    detail.sources.forEach { source ->
        SourceRow(
            source = source,
            editing = editing,
            onToggle = { enabled ->
                if (onToggleSource != null) {
                    scope.launch { onToggleSource(detail.filterId, source.id, enabled) }
                }
            },
        )
    }
    detail.blockedDomains.forEach { domain ->
        DomainRow(
            domain = domain,
            editing = editing,
            onRemove = {
                if (onRemoveBlocked != null) {
                    onRemoveBlocked(detail.filterId, domain, ManualRuleTarget.BLOCKED)
                }
            },
        )
    }
}

@Composable
private fun AllowsShelf(
    detail: FilterDetailState,
    editing: Boolean,
    onRemoveAllowed: ((String, String, ManualRuleTarget) -> Unit)?,
) {
    if (detail.allowedDomains.isEmpty()) {
        LavaPanel(modifier = Modifier.testTag("filters_allows_empty")) {
            Text(
                text = stringResource(R.string.filters_no_allowed),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (editing) {
                Text(
                    text = stringResource(R.string.filters_no_allowed_edit_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    detail.allowedDomains.forEach { domain ->
        DomainRow(
            domain = domain,
            editing = editing,
            onRemove = {
                if (onRemoveAllowed != null) {
                    onRemoveAllowed(detail.filterId, domain, ManualRuleTarget.ALLOWED)
                }
            },
        )
    }
}

@Composable
private fun SourceRow(
    source: FilterSourceOptionUi,
    editing: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    LavaPanel(modifier = modifier.testTag("filters_source_row_${source.id}")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(LavaSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(LavaSpacing.xs),
            ) {
                // Catalog source names are third-party product names from the signed catalog;
                // they are shown verbatim like every iOS surface that renders catalog metadata.
                Text(
                    text = source.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = if (editing && !source.selected) {
                        TextDecoration.LineThrough
                    } else {
                        TextDecoration.None
                    },
                    color = if (editing && !source.selected) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = stringResource(source.category.labelResource),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (editing) {
                if (source.selected) {
                    IconButton(
                        onClick = { onToggle(false) },
                        modifier = Modifier.testTag("filters_source_remove_${source.id}"),
                    ) {
                        Icon(
                            imageVector = LavaIcons.Minus,
                            contentDescription = stringResource(R.string.filters_remove),
                            tint = LocalLavaSemanticColors.current.danger,
                        )
                    }
                } else {
                    IconButton(
                        onClick = { onToggle(true) },
                        modifier = Modifier.testTag("filters_source_add_${source.id}"),
                    ) {
                        Icon(
                            imageVector = LavaIcons.Plus,
                            contentDescription = stringResource(R.string.filters_add),
                            tint = LocalLavaSemanticColors.current.safe,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DomainRow(
    domain: String,
    editing: Boolean,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LavaPanel(modifier = modifier.testTag("filters_domain_row")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(LavaSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Domains are normalized user data — rendered verbatim, never localized.
            Text(
                text = domain,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (editing) {
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.testTag("filters_domain_remove"),
                ) {
                    Icon(
                        imageVector = LavaIcons.Minus,
                        contentDescription = stringResource(R.string.filters_remove),
                        tint = LocalLavaSemanticColors.current.danger,
                    )
                }
            }
        }
    }
}

@Composable
private fun AddRow(
    title: String,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().testTag(testTag),
    ) {
        Icon(
            imageVector = LavaIcons.Plus,
            contentDescription = null,
            modifier = Modifier.size(LavaIconSizes.inline),
        )
        Text(title)
    }
}

/**
 * The block-a-domain sheet: a domain field with the usage counter, the exact
 * [ManualRuleRejection] inline, and the submit gate (iOS AddBlockedDomainSheet).
 */
@Composable
private fun BlockedDomainSheet(
    detail: FilterDetailState,
    onAdd: (suspend (String, String) -> ManualRuleEditResult?),
    onDismiss: () -> Unit,
) {
    DomainSheet(
        title = stringResource(R.string.filters_add_blocked_domain_title),
        placeholder = "example.com",
        usage = stringResource(
            if (detail.blockedDomains.size >= detail.maxBlockedDomains) {
                R.string.filters_blocked_usage_limit
            } else {
                R.string.filters_blocked_usage
            },
            detail.blockedDomains.size,
            detail.maxBlockedDomains,
        ),
        target = ManualRuleTarget.BLOCKED,
        filterId = detail.filterId,
        onAdd = onAdd,
        onDismiss = onDismiss,
        testTag = "filters_blocked_domain_sheet",
    )
}

/**
 * The add-an-exception sheet: the pinned warning panel plus the domain form
 * (iOS AddAllowedExceptionSheet — allow exceptions carry the explicit warning copy).
 */
@Composable
private fun AllowedExceptionSheet(
    detail: FilterDetailState,
    onAdd: (suspend (String, String) -> ManualRuleEditResult?),
    onDismiss: () -> Unit,
) {
    DomainSheet(
        title = stringResource(R.string.filters_add_allowed_exception_title),
        placeholder = "trusted.example.com",
        usage = stringResource(
            if (detail.allowedDomains.size >= detail.maxAllowedDomains) {
                R.string.filters_allowed_usage_limit
            } else {
                R.string.filters_allowed_usage
            },
            detail.allowedDomains.size,
            detail.maxAllowedDomains,
        ),
        target = ManualRuleTarget.ALLOWED,
        filterId = detail.filterId,
        onAdd = onAdd,
        onDismiss = onDismiss,
        testTag = "filters_allowed_exception_sheet",
        warningTitle = stringResource(R.string.filters_allow_warning_title),
        warningDetail = stringResource(R.string.filters_allow_warning_detail),
    )
}

@Composable
private fun DomainSheet(
    title: String,
    placeholder: String,
    usage: String,
    target: ManualRuleTarget,
    filterId: String,
    onAdd: (suspend (String, String) -> ManualRuleEditResult?),
    onDismiss: () -> Unit,
    testTag: String,
    warningTitle: String? = null,
    warningDetail: String? = null,
) {
    var domain by remember { mutableStateOf("") }
    var rejection by remember { mutableStateOf<ManualRuleRejection?>(null) }
    val scope = rememberCoroutineScope()
    val trimmed = domain.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(testTag),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LavaSpacing.md)) {
                if (warningTitle != null && warningDetail != null) {
                    LavaPanel(modifier = Modifier.testTag("filters_domain_sheet_warning")) {
                        Text(
                            text = warningTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = LocalLavaSemanticColors.current.attentionText,
                        )
                        Text(
                            text = warningDetail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                androidx.compose.material3.OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it },
                    label = { Text(stringResource(R.string.filters_domain_label)) },
                    placeholder = { Text(placeholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("filters_domain_input"),
                )
                Text(
                    text = usage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val rejected = rejection
                if (rejected != null) {
                    Text(
                        text = stringResource(rejected.titleResource()),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = LocalLavaSemanticColors.current.attentionText,
                    )
                    Text(
                        text = stringResource(rejected.detailResource()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        val result = onAdd(filterId, domain)
                        if (result is ManualRuleEditResult.Applied) {
                            onDismiss()
                        } else if (result is ManualRuleEditResult.Rejected) {
                            rejection = result.reason
                        }
                    }
                },
                enabled = trimmed.isNotEmpty(),
                modifier = Modifier.testTag("filters_domain_submit"),
            ) {
                Text(
                    stringResource(
                        if (target == ManualRuleTarget.BLOCKED) {
                            R.string.filters_add_domain
                        } else {
                            R.string.filters_add_exception_action
                        },
                    ),
                )
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel))
            }
        },
    )
}

private fun ManualRuleRejection.titleResource(): Int = when (this) {
    ManualRuleRejection.INVALID_DOMAIN -> R.string.filters_domain_reject_title
    ManualRuleRejection.ALREADY_ALLOWED -> R.string.filters_already_allowed
    ManualRuleRejection.ALREADY_BLOCKED -> R.string.filters_already_blocked
    ManualRuleRejection.ALLOWED_LIMIT_REACHED -> R.string.filters_allowed_limit_title
    ManualRuleRejection.BLOCKED_LIMIT_REACHED -> R.string.filters_blocked_limit_title
}

private fun ManualRuleRejection.detailResource(): Int = when (this) {
    ManualRuleRejection.INVALID_DOMAIN -> R.string.filters_invalid_domain
    ManualRuleRejection.ALREADY_ALLOWED -> R.string.filters_already_allowed_detail
    ManualRuleRejection.ALREADY_BLOCKED -> R.string.filters_already_blocked_detail
    ManualRuleRejection.ALLOWED_LIMIT_REACHED -> R.string.filters_allowed_limit_detail
    ManualRuleRejection.BLOCKED_LIMIT_REACHED -> R.string.filters_blocked_limit_detail
}

private val com.lavasec.app.foundation.filter.FilterSourceCategory.labelResource: Int
    get() = when (this) {
        com.lavasec.app.foundation.filter.FilterSourceCategory.SECURITY -> R.string.filters_category_security
        com.lavasec.app.foundation.filter.FilterSourceCategory.MULTI_PURPOSE -> R.string.filters_category_multi_purpose
        com.lavasec.app.foundation.filter.FilterSourceCategory.ADS_TRACKING -> R.string.filters_category_ads_tracking
        com.lavasec.app.foundation.filter.FilterSourceCategory.SOCIAL -> R.string.filters_category_social
        com.lavasec.app.foundation.filter.FilterSourceCategory.NSFW -> R.string.filters_category_nsfw
        com.lavasec.app.foundation.filter.FilterSourceCategory.GAMBLING -> R.string.filters_category_gambling
        com.lavasec.app.foundation.filter.FilterSourceCategory.PIRACY -> R.string.filters_category_piracy
        com.lavasec.app.foundation.filter.FilterSourceCategory.CUSTOM -> R.string.filters_category_custom
    }
