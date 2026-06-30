package com.pocketagent.android.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.pocketagent.android.R
import com.pocketagent.android.runtime.PresetBackingStore
import com.pocketagent.android.ui.components.AppBottomSheet
import com.pocketagent.android.ui.state.ModalSurface
import com.pocketagent.android.ui.state.ModelLoadingState
import com.pocketagent.core.RoutingMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelLibrarySheetHost(
    activeSurface: ModalSurface,
    provisioningViewModel: ModelProvisioningViewModel,
    defaultGetReadyModelId: String?,
    modelLoadingState: ModelLoadingState,
    routingMode: RoutingMode,
    presetBackingStore: PresetBackingStore,
    modelRemoveUndoState: ModelRemoveUndoState,
    actions: ModelLibraryActions,
) {
    if (activeSurface !is ModalSurface.ModelLibrary) {
        return
    }

    val provisioningState by provisioningViewModel.uiState.collectAsState()
    val modelLibraryState = remember(provisioningState, defaultGetReadyModelId) {
        provisioningState.toModelLibraryUiState(defaultGetReadyModelId)
    } ?: return
    val runtimeModelState = remember(provisioningState) {
        provisioningState.toRuntimeModelUiState()
    } ?: return
    val scope = rememberCoroutineScope()
    val runtimeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pendingRemoveVersion by remember { mutableStateOf<Pair<String, String>?>(null) }

    AppBottomSheet(
        title = stringResource(id = R.string.ui_model_library_title),
        sheetState = runtimeSheetState,
        onDismiss = actions::dismissSheet,
    ) {
        ModelSheet(
            libraryState = modelLibraryState,
            runtimeState = runtimeModelState,
            modelLoadingState = modelLoadingState,
            routingMode = routingMode,
            presetBackingStore = presetBackingStore,
            hiddenVersionKeys = modelRemoveUndoState.hiddenVersionKeys,
            onEvent = { event ->
                when (event) {
                    is ModelSheetEvent.ImportModel -> {
                        actions.importModel(event.modelId)
                    }
                    is ModelSheetEvent.ResolveHuggingFaceCandidate -> scope.launch {
                        actions.resolveHuggingFaceCandidate(event.input, event.targetModelId)
                    }
                    ModelSheetEvent.ClearHuggingFaceCandidate -> {
                        actions.clearHuggingFaceCandidate()
                    }
                    is ModelSheetEvent.RemoveRecentHuggingFaceModel -> scope.launch {
                        actions.removeRecentHuggingFaceModel(event.id)
                    }
                    is ModelSheetEvent.DownloadVersion -> actions.downloadVersion(event.version)
                    is ModelSheetEvent.PauseDownload -> scope.launch {
                        actions.pauseDownload(event.taskId)
                    }
                    is ModelSheetEvent.ResumeDownload -> scope.launch {
                        actions.resumeDownload(event.taskId)
                    }
                    is ModelSheetEvent.RetryDownload -> scope.launch {
                        actions.retryDownload(event.taskId)
                    }
                    is ModelSheetEvent.CancelDownload -> scope.launch {
                        actions.cancelDownload(event.taskId)
                    }
                    is ModelSheetEvent.LoadVersion -> scope.launch {
                        actions.loadModelVersion(event.modelId, event.version, closeOnSuccess = true)
                    }
                    is ModelSheetEvent.RetryLoad -> {
                        if (event.version.isNullOrBlank()) {
                            scope.launch { actions.loadLastUsedModel(closeOnSuccess = true) }
                        } else {
                            scope.launch {
                                actions.loadModelVersion(event.modelId, event.version, closeOnSuccess = true)
                            }
                        }
                    }
                    ModelSheetEvent.LoadLastUsedModel -> scope.launch {
                        actions.loadLastUsedModel(closeOnSuccess = true)
                    }
                    ModelSheetEvent.OffloadModel -> scope.launch {
                        actions.offloadModel(closeOnSuccess = false)
                    }
                    is ModelSheetEvent.RequestRemove -> {
                        pendingRemoveVersion = event.modelId to event.version
                    }
                    ModelSheetEvent.RefreshAll -> {
                        scope.launch {
                            actions.refreshAll()
                        }
                    }
                    ModelSheetEvent.Close -> actions.dismissSheet()
                }
            },
        )
    }

    pendingRemoveVersion?.let { (modelId, version) ->
        val model = modelLibraryState.snapshot.models.firstOrNull { it.modelId == modelId }
        val targetVersion = model?.installedVersions?.firstOrNull { it.version == version }
        val removePlan = if (model != null && targetVersion != null) {
            resolveRemoveVersionPlan(
                model = model,
                version = targetVersion,
                loadedModel = modelLoadingState.loadedModel,
            )
        } else {
            null
        }
        AlertDialog(
            onDismissRequest = { pendingRemoveVersion = null },
            title = { Text(stringResource(id = R.string.ui_remove_model_title)) },
            text = {
                Text(
                    text = when {
                        removePlan?.isBlockedByActiveSelection == true ->
                            stringResource(id = R.string.ui_remove_model_body_active_blocked, version)
                        removePlan?.requiresOffload == true && removePlan.requiresClearingActiveSelection ->
                            stringResource(id = R.string.ui_remove_model_body_loaded_only_active, version)
                        removePlan?.requiresOffload == true ->
                            stringResource(id = R.string.ui_remove_model_body_loaded, version)
                        removePlan?.requiresClearingActiveSelection == true ->
                            stringResource(id = R.string.ui_remove_model_body_only_active, version)
                        else ->
                            stringResource(id = R.string.ui_remove_model_body, version)
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRemoveVersion = null
                        modelRemoveUndoState.requestRemove(modelId, version)
                    },
                    enabled = removePlan?.isBlockedByActiveSelection != true,
                ) {
                    Text(stringResource(id = R.string.ui_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoveVersion = null }) {
                    Text(stringResource(id = R.string.ui_cancel_button))
                }
            },
        )
    }
}
