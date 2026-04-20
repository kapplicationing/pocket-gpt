package com.pocketagent.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.pocketagent.android.R
import com.pocketagent.android.ui.theme.tickLight
import com.pocketagent.core.ModelPreset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PocketAgentTopBar(
    activeRuntimeModelLabel: String?,
    lastUsedModelLabel: String?,
    modelLibraryState: ModelLibraryUiState,
    onOpenSessionDrawer: () -> Unit,
    onModelPresetSelected: (ModelPreset) -> Unit,
    onOpenModelLibrary: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var showModelMenu by remember { mutableStateOf(false) }
    val hasInstalledModels = remember(modelLibraryState) {
        modelLibraryState.snapshot.models.any { model -> model.installedVersions.isNotEmpty() }
    }

    TopAppBar(
        title = {
            Text(
                text = stringResource(id = R.string.ui_app_title),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
        },
        navigationIcon = {
            IconButton(
                modifier = Modifier.testTag("session_drawer_button"),
                onClick = {
                    haptic.tickLight()
                    onOpenSessionDrawer()
                },
            ) {
                Icon(
                    Icons.Default.Menu,
                    contentDescription = stringResource(id = R.string.a11y_session_drawer_open),
                )
            }
        },
        actions = {
            Box {
                AssistChip(
                    onClick = {
                        haptic.tickLight()
                        if (!hasInstalledModels) {
                            onOpenModelLibrary()
                        } else {
                            showModelMenu = true
                        }
                    },
                    modifier = Modifier
                        .testTag("open_model_library")
                        .widthIn(max = 120.dp),
                    label = {
                        Text(
                            text = activeRuntimeModelLabel
                                ?: lastUsedModelLabel
                                ?: stringResource(id = R.string.ui_models_title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
                DropdownMenu(
                    expanded = showModelMenu,
                    onDismissRequest = { showModelMenu = false },
                ) {
                    ModelPreset.selectablePresets.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(text = presetQuickMenuTitle(preset)) },
                            onClick = {
                                haptic.tickLight()
                                showModelMenu = false
                                onModelPresetSelected(preset)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(text = stringResource(id = R.string.ui_more_models)) },
                        onClick = {
                            haptic.tickLight()
                            showModelMenu = false
                            onOpenModelLibrary()
                        },
                    )
                }
            }
            IconButton(
                modifier = Modifier.testTag("advanced_sheet_button"),
                onClick = {
                    haptic.tickLight()
                    onOpenAdvancedSettings()
                },
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = stringResource(id = R.string.a11y_advanced_controls_open),
                )
            }
        },
    )
}

@Composable
private fun presetQuickMenuTitle(preset: ModelPreset): String {
    return when (preset) {
        ModelPreset.AUTO -> stringResource(id = R.string.ui_preset_auto)
        ModelPreset.QUICK -> stringResource(id = R.string.ui_preset_quick)
        ModelPreset.BALANCED -> stringResource(id = R.string.ui_preset_balanced_chat)
        ModelPreset.VISION -> stringResource(id = R.string.ui_preset_vision)
    }
}
