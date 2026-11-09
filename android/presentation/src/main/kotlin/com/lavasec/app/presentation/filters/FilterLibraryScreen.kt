package com.lavasec.app.presentation.filters

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.lavasec.app.presentation.R

/**
 * The saved-filter library — the iOS `FilterLibraryView.swift` `AllFiltersView`: an orientation
 * panel, one row per saved filter with the in-effect marker / frozen lock / honest rule
 * summary, edit mode with add-rename-stage-delete and restore defaults, and the Apply-versus-
 * View & edit choice for non-active filters.
 *
 * Deletion is blocklist-style staged: tapping a row's delete only stages it (strikethrough +
 * undo); the commit row opens a confirmation dialog. Leaving edit mode discards the staged
 * set, exactly like iOS clears `stagedDeletions` on exit. The active filter is never
 * deletable, the last remaining filter is never deletable, and frozen filters are read-only.
 *
 * @param actions null when the product cannot supply mutations; every control is then omitted
 * rather than rendered inert (in production the host always supplies them with library state).
 */
@Composable
fun FilterLibraryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    state: FilterLibraryState? = null,
    switchState: FilterProfileSwitchUiState = FilterProfileSwitchUiState.Idle,
    mutationState: FilterLibraryMutationUiState = FilterLibraryMutationUiState.Idle,
    actions: FilterLibraryActions? = null,
) {
    var editing by remember { mutableStateOf(false) }
    var stagedDeletions by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingApplyId by remember { mutableStateOf<String?>(null) }
    var pendingCreate by remember { mutableStateOf(false) }
    var renamingId by remember { mutableStateOf<String?>(null) }
    var pendingDeleteConfirm by remember { mutableStateOf(false) }
    var pendingDiscardConfirm by remember { mutableStateOf(false) }
    var pendingRestoreConfirm by remember { mutableStateOf(false) }

    // Close the create/rename dialogs on the durable outcome; otherwise the updated library
    // immediately re-validates the just-created name inside the still-open dialog and reports
    // it as a duplicate (the owner's Succeeded state is sticky until the next admission).
    LaunchedEffect(mutationState) {
        if (mutationState is FilterLibraryMutationUiState.Succeeded) {
            when (mutationState.kind) {
                FilterLibraryMutationKindUi.CREATE -> pendingCreate = false
                FilterLibraryMutationKindUi.RENAME -> renamingId = null
                else -> Unit
            }
        }
    }

    val library = state
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .lavaScreenScrollPadding(LocalLavaContentInsets.current)
            .testTag("filter_library_screen"),
        verticalArrangement = Arrangement.spacedBy(LavaMetrics.sectionSpacing),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = LavaIcons.Back,
                contentDescription = stringResource(R.string.navigate_back),
            )
        }
        Text(
            text = stringResource(R.string.filters_your_filters),
            style = LavaScreenTypography.screenTitle,
            modifier = Modifier.semantics { heading() },
        )
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
            FilterLibraryHowPanel()
            LavaSection(
                title = stringResource(R.string.filters_your_filters),
                contentSpacing = LavaNavigationCardMetrics.interCardSpacing,
            ) {
                library.filters.forEach { filter ->
                    FilterLibraryRow(
                        filter = filter,
                        editing = editing,
                        pendingDeletion = filter.id in stagedDeletions,
                        canDelete = actions != null &&
                            library.filters.size > 1 &&
                            filter.id != library.activeFilterId &&
                            !filter.frozen,
                        applying = switchState is FilterProfileSwitchUiState.Applying &&
                            switchState.targetFilterId == filter.id,
                        controlsEnabled = actions != null &&
                            switchState !is FilterProfileSwitchUiState.Applying,
                        onRowClick = {
                            when {
                                filter.active -> actions?.onOpenDetail(filter.id)
                                actions == null -> Unit
                                filter.frozen -> actions.onOpenDetail(filter.id)
                                else -> pendingApplyId = filter.id
                            }
                        },
                        onRename = { renamingId = filter.id },
                        onToggleDelete = {
                            stagedDeletions = if (filter.id in stagedDeletions) {
                                stagedDeletions - filter.id
                            } else {
                                stagedDeletions + filter.id
                            }
                        },
                    )
                }
                if (actions != null && editing) {
                    OutlinedButton(
                        onClick = { pendingCreate = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("filters_add_filter"),
                    ) {
                        Icon(
                            imageVector = LavaIcons.Plus,
                            contentDescription = null,
                            modifier = Modifier.size(LavaIconSizes.inline),
                        )
                        Text(stringResource(R.string.filters_add_filter))
                    }
                }
            }
            if (actions != null && editing) {
                OutlinedButton(
                    onClick = { pendingRestoreConfirm = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("filters_restore_defaults"),
                ) {
                    Icon(
                        imageVector = LavaIcons.Undo,
                        contentDescription = null,
                        modifier = Modifier.size(LavaIconSizes.inline),
                    )
                    Text(stringResource(R.string.filters_restore_defaults))
                }
            }
            FilterLibraryFailurePanel(
                state = library,
                switchState = switchState,
                mutationState = mutationState,
            )
            if (actions != null) {
                if (editing) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                if (stagedDeletions.isEmpty()) {
                                    editing = false
                                } else {
                                    pendingDiscardConfirm = true
                                }
                            },
                            modifier = Modifier.weight(1f).testTag("filters_close_edit_mode"),
                        ) {
                            Text(stringResource(R.string.filters_close_edit_mode))
                        }
                        Button(
                            onClick = { pendingDeleteConfirm = true },
                            enabled = stagedDeletions.isNotEmpty() &&
                                mutationState !is FilterLibraryMutationUiState.InFlight,
                            modifier = Modifier.weight(1f).testTag("filters_confirm_deletions"),
                        ) {
                            Text(stringResource(R.string.filters_confirm_deletions))
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = { editing = true },
                        enabled = mutationState !is FilterLibraryMutationUiState.InFlight,
                        modifier = Modifier.fillMaxWidth().testTag("filters_edit_library"),
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
    }

    val applyFilter = pendingApplyId?.let { id -> library?.filter(id) }
    if (applyFilter != null && actions != null) {
        FilterApplyChoiceDialog(
            filterName = applyFilter.name,
            frozen = applyFilter.frozen,
            onApply = {
                pendingApplyId = null
                actions.onApplyFilterRequest(applyFilter.id)
            },
            onView = {
                pendingApplyId = null
                actions.onOpenDetail(applyFilter.id)
            },
            onDismiss = { pendingApplyId = null },
        )
    }
    if (pendingCreate && actions != null && library != null) {
        CreateFilterDialog(
            library = library,
            mutationState = mutationState,
            onCreate = { name, duplicateFromId -> actions.onCreateFilter(name, duplicateFromId) },
            onDismiss = { pendingCreate = false },
        )
    }
    val renaming = renamingId?.let { id -> library?.filter(id) }
    if (renaming != null && actions != null) {
        RenameFilterDialog(
            filter = renaming,
            mutationState = mutationState,
            onRename = { name -> actions.onRenameFilter(renaming.id, name) },
            onDismiss = { renamingId = null },
        )
    }
    if (pendingDeleteConfirm && actions != null) {
        DeleteFiltersConfirmDialog(
            names = library?.filters
                ?.filter { it.id in stagedDeletions }
                ?.map(FilterRowUi::name)
                .orEmpty(),
            onConfirm = {
                actions.onDeleteFilters(stagedDeletions)
                pendingDeleteConfirm = false
                editing = false
                stagedDeletions = emptySet()
            },
            onDismiss = { pendingDeleteConfirm = false },
        )
    }
    if (pendingDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { pendingDiscardConfirm = false },
            modifier = Modifier.testTag("filters_discard_dialog"),
            title = { Text(stringResource(R.string.filters_discard_title)) },
            text = { Text(stringResource(R.string.filters_discard_staged_deletions_detail)) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDiscardConfirm = false
                        editing = false
                        stagedDeletions = emptySet()
                    },
                    modifier = Modifier.testTag("filters_discard_confirm"),
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
    if (pendingRestoreConfirm && library != null) {
        AlertDialog(
            onDismissRequest = { pendingRestoreConfirm = false },
            modifier = Modifier.testTag("filters_restore_dialog"),
            title = { Text(stringResource(R.string.filters_restore_defaults_confirm_title)) },
            text = { Text(stringResource(R.string.filters_restore_defaults_confirm_detail)) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingRestoreConfirm = false
                        editing = false
                        stagedDeletions = emptySet()
                        actions?.onRestoreDefaults()
                    },
                    modifier = Modifier.testTag("filters_restore_confirm"),
                ) {
                    Text(stringResource(R.string.filters_restore))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingRestoreConfirm = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

@Composable
private fun FilterLibraryHowPanel(modifier: Modifier = Modifier) {
    LavaPanel(modifier = modifier.testTag("filters_library_how_panel")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(LavaSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = LavaIcons.Filters,
                contentDescription = null,
                tint = LocalLavaSemanticColors.current.safe,
                modifier = Modifier.size(LavaIconSizes.endpoint),
            )
            Column(verticalArrangement = Arrangement.spacedBy(LavaSpacing.xs)) {
                Text(
                    text = stringResource(R.string.filters_library_how_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.filters_library_how_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FilterLibraryRow(
    filter: FilterRowUi,
    editing: Boolean,
    pendingDeletion: Boolean,
    canDelete: Boolean,
    applying: Boolean,
    controlsEnabled: Boolean,
    onRowClick: () -> Unit,
    onRename: () -> Unit,
    onToggleDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LavaPanel(modifier = modifier.testTag("filters_library_row")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(LavaSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(LavaSpacing.xs),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(LavaSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = filter.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = if (pendingDeletion) {
                            TextDecoration.LineThrough
                        } else {
                            TextDecoration.None
                        },
                        color = if (pendingDeletion) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .testTag("filters_library_row_name"),
                    )
                    if (editing && !filter.frozen && !pendingDeletion) {
                        Icon(
                            imageVector = LavaIcons.Pencil,
                            contentDescription = stringResource(R.string.filters_edit),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(LavaIconSizes.inline),
                        )
                    }
                }
                if (filter.blocksNothing) {
                    Text(
                        text = stringResource(R.string.filters_blocks_nothing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalLavaSemanticColors.current.attentionText,
                    )
                } else if (filter.activeRuleCount != null) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.filters_rule_count,
                            filter.activeRuleCount,
                            filter.activeRuleCount,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.filters_not_in_effect),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when {
                pendingDeletion -> Unit
                filter.frozen -> {
                    Row(
                        modifier = Modifier.testTag("filters_frozen_marker"),
                        horizontalArrangement = Arrangement.spacedBy(LavaSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = LavaSettingsIcons.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(LavaIconSizes.inline),
                        )
                        Text(
                            text = stringResource(R.string.filters_locked),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                filter.active -> {
                    Row(
                        modifier = Modifier.testTag("filters_active_marker"),
                        horizontalArrangement = Arrangement.spacedBy(LavaSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = LavaIcons.PlayCircle,
                            contentDescription = null,
                            tint = LocalLavaSemanticColors.current.safe,
                            modifier = Modifier.size(LavaIconSizes.control),
                        )
                        Text(
                            text = stringResource(R.string.filters_now_filtering),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = LocalLavaSemanticColors.current.safe,
                        )
                    }
                }
                applying -> {
                    Text(
                        text = stringResource(R.string.filters_applying),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("filters_apply_in_flight"),
                    )
                }
            }
        }
        if (!pendingDeletion && !editing) {
            OutlinedButton(
                onClick = onRowClick,
                enabled = controlsEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("filters_library_row_action_${filter.id}"),
            ) {
                Text(
                    stringResource(
                        when {
                            filter.active -> R.string.filters_view
                            filter.frozen -> R.string.filters_view
                            else -> R.string.filters_use_this_filter
                        },
                    ),
                )
            }
        }
        if (editing) {
            if (canDelete) {
                IconButton(
                    onClick = onToggleDelete,
                    modifier = Modifier.testTag("filters_stage_delete_${filter.id}"),
                ) {
                    Icon(
                        imageVector = if (pendingDeletion) LavaIcons.Undo else LavaIcons.Trash,
                        contentDescription = stringResource(
                            if (pendingDeletion) {
                                R.string.filters_undo_delete
                            } else {
                                R.string.filters_delete
                            },
                        ),
                        tint = if (pendingDeletion) {
                            LocalLavaSemanticColors.current.safe
                        } else {
                            LocalLavaSemanticColors.current.danger
                        },
                    )
                }
            }
            if (!filter.frozen && !pendingDeletion) {
                OutlinedButton(
                    onClick = onRename,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("filters_rename_${filter.id}"),
                ) {
                    Text(stringResource(R.string.filters_rename_filter))
                }
            }
        }
    }
}

@Composable
private fun FilterLibraryFailurePanel(
    state: FilterLibraryState,
    switchState: FilterProfileSwitchUiState,
    mutationState: FilterLibraryMutationUiState,
) {
    when (switchState) {
        is FilterProfileSwitchUiState.Failed -> {
            LavaPanel(modifier = Modifier.testTag("filters_switch_error")) {
                Text(
                    text = stringResource(R.string.filters_switch_failed_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = when (switchState.failure) {
                        FilterProfileSwitchFailureUi.FROZEN_TARGET ->
                            stringResource(R.string.filters_switch_frozen_detail)
                        FilterProfileSwitchFailureUi.COMMIT ->
                            stringResource(
                                R.string.filters_switch_failed_detail,
                                state.filter(switchState.targetFilterId)?.name ?: "",
                            )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        else -> Unit
    }
    when (mutationState) {
        is FilterLibraryMutationUiState.Failed -> {
            LavaPanel(modifier = Modifier.testTag("filters_library_mutation_error")) {
                Text(
                    text = stringResource(R.string.filters_library_mutation_failed_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = mutationState.rejection?.let { rejection ->
                        if (rejection == FilterLibraryRejectionUi.LIMIT_REACHED) {
                            stringResource(R.string.filters_max_filters_detail, state.maxFilters)
                        } else {
                            stringResource(detailResource(rejection))
                        }
                    } ?: stringResource(mutationFailureDetail(mutationState.kind)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        else -> Unit
    }
}

private fun detailResource(rejection: FilterLibraryRejectionUi): Int = when (rejection) {
    FilterLibraryRejectionUi.LIMIT_REACHED -> R.string.filters_max_filters_detail
    FilterLibraryRejectionUi.DUPLICATE_NAME -> R.string.filters_duplicate_name
    FilterLibraryRejectionUi.BLANK_NAME -> R.string.filters_name_required
    FilterLibraryRejectionUi.INVALID_NAME -> R.string.filters_invalid_name
    FilterLibraryRejectionUi.INVALID_EMOJI -> R.string.filters_invalid_emoji
    FilterLibraryRejectionUi.BLOCKED_DOMAIN_LIMIT -> R.string.filters_blocked_limit_detail
    FilterLibraryRejectionUi.ALLOWED_DOMAIN_LIMIT -> R.string.filters_allowed_limit_detail
    FilterLibraryRejectionUi.FROZEN_FILTER -> R.string.filters_frozen_detail
    FilterLibraryRejectionUi.ACTIVE_FILTER_CANNOT_BE_DELETED ->
        R.string.filters_delete_active_rejected
    FilterLibraryRejectionUi.LAST_FILTER_CANNOT_BE_DELETED ->
        R.string.filters_delete_last_rejected
    FilterLibraryRejectionUi.UNKNOWN_FILTER,
    FilterLibraryRejectionUi.UNKNOWN_DUPLICATE_SOURCE,
    -> R.string.filters_library_mutation_failed_commit
}

/** The honest per-kind failure detail for a compile/publish failure (no editor refusal). */
private fun mutationFailureDetail(kind: FilterLibraryMutationKindUi): Int = when (kind) {
    FilterLibraryMutationKindUi.CREATE -> R.string.filters_library_mutation_failed_create
    else -> R.string.filters_library_mutation_failed_commit
}

/** Apply versus View & edit — the iOS action-choice alert. */
@Composable
private fun FilterApplyChoiceDialog(
    filterName: String,
    frozen: Boolean,
    onApply: () -> Unit,
    onView: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Filter names are user data — the title renders them raw (never localized), so a name
    // matching a localization key still identifies the exact filter being applied.
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("filters_apply_choice_dialog"),
        title = { Text(filterName) },
        text = {
            Text(
                stringResource(
                    if (frozen) {
                        R.string.filters_frozen_detail
                    } else {
                        R.string.filters_apply_view_detail
                    },
                ),
            )
        },
        confirmButton = {
            if (!frozen) {
                Button(
                    onClick = onApply,
                    modifier = Modifier.testTag("filters_apply_confirm"),
                ) {
                    Text(stringResource(R.string.filters_apply))
                }
            } else {
                Button(
                    onClick = onView,
                    modifier = Modifier.testTag("filters_view_confirm"),
                ) {
                    Text(stringResource(R.string.filters_view))
                }
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onView) {
                Text(
                    stringResource(
                        if (frozen) R.string.filters_view else R.string.filters_view_and_edit,
                    ),
                )
            }
        },
    )
}

/** Create a new filter: a name plus an optional "duplicate from" source (iOS CreateFilterSheet). */
@Composable
private fun CreateFilterDialog(
    library: FilterLibraryState,
    mutationState: FilterLibraryMutationUiState,
    onCreate: (name: String, duplicateFromId: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var duplicateFromId by remember { mutableStateOf<String?>(null) }
    val trimmed = name.trim()
    val duplicateName = trimmed.isNotEmpty() &&
        library.filters.any { filter ->
            filter.name.trim().lowercase() == trimmed.lowercase()
        }
    val rejectedDuplicate = mutationState is FilterLibraryMutationUiState.Failed &&
        mutationState.kind == FilterLibraryMutationKindUi.CREATE &&
        mutationState.rejection == FilterLibraryRejectionUi.DUPLICATE_NAME
    val rejectedLimit = mutationState is FilterLibraryMutationUiState.Failed &&
        mutationState.kind == FilterLibraryMutationKindUi.CREATE &&
        mutationState.rejection == FilterLibraryRejectionUi.LIMIT_REACHED
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("filters_create_dialog"),
        title = { Text(stringResource(R.string.filters_new_filter_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LavaSpacing.md)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.filters_filter_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions.Default,
                    modifier = Modifier.fillMaxWidth().testTag("filters_create_name"),
                )
                if (duplicateName || rejectedDuplicate) {
                    Text(
                        text = stringResource(R.string.filters_duplicate_name),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (rejectedLimit) {
                    Text(
                        text = stringResource(R.string.filters_max_filters_detail, library.maxFilters),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                DuplicateFromPicker(
                    library = library,
                    selectedId = duplicateFromId,
                    onSelect = { duplicateFromId = it },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name, duplicateFromId) },
                enabled = trimmed.isNotEmpty() && !duplicateName && !rejectedLimit,
                modifier = Modifier.testTag("filters_create_confirm"),
            ) {
                Text(stringResource(R.string.filters_create))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel))
            }
        },
    )
}

@Composable
private fun DuplicateFromPicker(
    library: FilterLibraryState,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(LavaSpacing.xs)) {
        Text(
            text = stringResource(R.string.filters_start_from),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedButton(
            onClick = { onSelect(null) },
            modifier = Modifier.fillMaxWidth().testTag("filters_duplicate_none"),
        ) {
            Text(
                text = stringResource(R.string.filters_empty_filter_option),
                modifier = Modifier.weight(1f),
            )
            if (selectedId == null) {
                Icon(
                    imageVector = LavaIcons.Check,
                    contentDescription = null,
                    modifier = Modifier.size(LavaIconSizes.inline),
                )
            }
        }
        library.filters.forEach { filter ->
            OutlinedButton(
                onClick = { onSelect(filter.id) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("filters_duplicate_from_${filter.id}"),
            ) {
                Text(text = filter.name, modifier = Modifier.weight(1f))
                if (selectedId == filter.id) {
                    Icon(
                        imageVector = LavaIcons.Check,
                        contentDescription = null,
                        modifier = Modifier.size(LavaIconSizes.inline),
                    )
                }
            }
        }
    }
}

/** Rename sheet (iOS RenameFilterSheet): a named field + save, with the duplicate-name gate. */
@Composable
private fun RenameFilterDialog(
    filter: FilterRowUi,
    mutationState: FilterLibraryMutationUiState,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(filter.name) }
    val trimmed = name.trim()
    val rejectedDuplicate = mutationState is FilterLibraryMutationUiState.Failed &&
        mutationState.kind == FilterLibraryMutationKindUi.RENAME &&
        mutationState.rejection == FilterLibraryRejectionUi.DUPLICATE_NAME
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("filters_rename_dialog"),
        title = { Text(stringResource(R.string.filters_rename_filter)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LavaSpacing.sm)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.filters_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("filters_rename_name"),
                )
                if (rejectedDuplicate) {
                    Text(
                        text = stringResource(R.string.filters_duplicate_name),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onRename(name) },
                enabled = trimmed.isNotEmpty(),
                modifier = Modifier.testTag("filters_rename_confirm"),
            ) {
                Text(stringResource(R.string.filters_save))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel))
            }
        },
    )
}

/** Confirmation for staged deletions (iOS DeleteFiltersConfirmationSheet). */
@Composable
private fun DeleteFiltersConfirmDialog(
    names: List<String>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("filters_delete_confirm_dialog"),
        title = {
            Text(
                stringResource(
                    if (names.size == 1) {
                        R.string.filters_delete_filter_confirm_title
                    } else {
                        R.string.filters_delete_filters_confirm_title
                    },
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LavaSpacing.sm)) {
                Text(stringResource(R.string.filters_delete_undo_title))
                Text(
                    text = stringResource(R.string.filters_delete_undo_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                names.forEach { name ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(LavaSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = LavaIcons.Minus,
                            contentDescription = null,
                            tint = LocalLavaSemanticColors.current.attentionText,
                            modifier = Modifier.size(LavaIconSizes.inline),
                        )
                        // Names are user data — rendered raw, never localized.
                        Text(text = name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.testTag("filters_delete_confirm"),
            ) {
                Text(
                    pluralStringResource(
                        R.plurals.filters_delete_count,
                        names.size,
                        names.size,
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
