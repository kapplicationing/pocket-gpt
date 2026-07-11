package com.pocketagent.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.pocketagent.android.R
import com.pocketagent.android.runtime.PresetBackingStore
import com.pocketagent.android.ui.theme.PocketAgentDimensions
import com.pocketagent.core.ModelPreset
import com.pocketagent.inference.ModelDisplayNames
import com.pocketagent.inference.PresetRoutingResolver

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PresetCustomizationSheetContent(
    libraryState: ModelLibraryUiState,
    presetBackingStore: PresetBackingStore,
    onBackingModelSelected: (ModelPreset, String) -> Unit,
    onResetToDefaults: () -> Unit,
) {
    val installedIds = remember(libraryState.snapshot) {
        libraryState.snapshot.models.map { model -> model.modelId }.toSet()
    }
    var showResetConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = PocketAgentDimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.screenPadding),
    ) {
        Text(
            text = stringResource(id = R.string.ui_preset_customize_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        listOf(ModelPreset.QUICK, ModelPreset.BALANCED, ModelPreset.VISION).forEach { preset ->
            PresetBackingDropdownRow(
                preset = preset,
                installedModelIds = installedIds,
                presetBackingStore = presetBackingStore,
                onModelIdSelected = { modelId -> onBackingModelSelected(preset, modelId) },
            )
        }
        TextButton(
            onClick = { showResetConfirm = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(id = R.string.ui_preset_reset_defaults))
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(id = R.string.ui_preset_reset_defaults)) },
            text = { Text(stringResource(id = R.string.ui_preset_reset_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirm = false
                        onResetToDefaults()
                    },
                ) {
                    Text(stringResource(id = R.string.ui_preset_reset_defaults))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(id = R.string.ui_cancel_button))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetBackingDropdownRow(
    preset: ModelPreset,
    installedModelIds: Set<String>,
    presetBackingStore: PresetBackingStore,
    onModelIdSelected: (String) -> Unit,
) {
    val candidates = remember(preset, installedModelIds) {
        when (preset) {
            ModelPreset.VISION -> PresetRoutingResolver.visionPresetEligibleModelIds(installedModelIds)
            ModelPreset.QUICK,
            ModelPreset.BALANCED,
            -> PresetRoutingResolver.textPresetEligibleModelIds(installedModelIds)
            ModelPreset.AUTO -> emptyList()
        }
    }
    val effectiveId = PresetRoutingResolver.effectiveBackingModelId(
        preset,
        presetBackingStore.customBackingModelId(preset),
    ) ?: return
    var expanded by remember { mutableStateOf(false) }
    val label = presetCustomizationTitle(preset)
    val summary = ModelDisplayNames.displayNameFor(effectiveId)

    Column(verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.compactSpacing)) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
        Text(
            text = stringResource(id = R.string.ui_preset_using_model, summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (candidates.isEmpty()) {
            Text(
                text = stringResource(id = R.string.ui_preset_no_models_for_tier),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            return
        }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                readOnly = true,
                singleLine = true,
                value = summary,
                onValueChange = {},
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                candidates.forEach { modelId ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = ModelDisplayNames.displayNameFor(modelId),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            expanded = false
                            onModelIdSelected(modelId)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun presetCustomizationTitle(preset: ModelPreset): String {
    return when (preset) {
        ModelPreset.AUTO -> stringResource(id = R.string.ui_preset_auto)
        ModelPreset.QUICK -> stringResource(id = R.string.ui_preset_quick)
        ModelPreset.BALANCED -> stringResource(id = R.string.ui_preset_balanced_chat)
        ModelPreset.VISION -> stringResource(id = R.string.ui_preset_vision)
    }
}
