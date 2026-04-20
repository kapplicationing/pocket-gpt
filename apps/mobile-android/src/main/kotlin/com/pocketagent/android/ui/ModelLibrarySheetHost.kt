package com.pocketagent.android.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.pocketagent.android.R
import com.pocketagent.android.runtime.PresetBackingStore
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.ui.components.AppBottomSheet
import com.pocketagent.android.ui.state.ModalSurface
import com.pocketagent.android.ui.state.ModelLoadingState
import com.pocketagent.core.RoutingMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelLibrarySheetHost(
    activeSurface: ModalSurface,
    modelLibraryState: ModelLibraryUiState,
    runtimeModelState: RuntimeModelUiState,
    modelLoadingState: ModelLoadingState,
    routingMode: RoutingMode,
    presetBackingStore: PresetBackingStore,
    modelRemoveUndoState: ModelRemoveUndoState,
    viewModel: ChatViewModel,
    provisioningViewModel: ModelProvisioningViewModel,
    appViewModel: ChatAppViewModel,
    onLaunchImportPicker: (String) -> Unit,
    onLaunchDownloadFlow: (ModelDistributionVersion) -> Unit,
    onLoadModelVersion: (String, String, Boolean) -> Unit,
    onLoadLastUsedModel: (Boolean) -> Unit,
    onOffloadModel: (Boolean) -> Unit,
) {
    if (activeSurface !is ModalSurface.ModelLibrary) {
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runtimeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pendingRemoveVersion by remember { mutableStateOf<Pair<String, String>?>(null) }

    AppBottomSheet(
        title = stringResource(id = R.string.ui_model_library_title),
        sheetState = runtimeSheetState,
        onDismiss = viewModel::dismissSurface,
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
                        appViewModel.setSelectedModelIdForImport(event.modelId)
                        onLaunchImportPicker(event.modelId)
                    }
                    is ModelSheetEvent.DownloadVersion -> onLaunchDownloadFlow(event.version)
                    is ModelSheetEvent.PauseDownload -> {
                        provisioningViewModel.pauseDownload(event.taskId)
                        provisioningViewModel.setStatusMessage(context.getString(R.string.ui_model_download_paused))
                    }
                    is ModelSheetEvent.ResumeDownload -> {
                        provisioningViewModel.resumeDownload(event.taskId)
                        provisioningViewModel.setStatusMessage(context.getString(R.string.ui_model_download_resumed))
                    }
                    is ModelSheetEvent.RetryDownload -> {
                        provisioningViewModel.retryDownload(event.taskId)
                        provisioningViewModel.setStatusMessage(context.getString(R.string.ui_model_download_retried))
                    }
                    is ModelSheetEvent.CancelDownload -> {
                        provisioningViewModel.cancelDownload(event.taskId)
                        provisioningViewModel.setStatusMessage(context.getString(R.string.ui_model_download_cancelled))
                    }
                    is ModelSheetEvent.SetDefaultVersion -> {
                        scope.launch {
                            val activated = provisioningViewModel.setActiveVersionAsync(event.modelId, event.version)
                            if (activated) {
                                val nextSequence = appViewModel.incrementReadinessRefreshSequence()
                                logProvisioningTransition(
                                    phase = "manual_activation",
                                    eventId = "refresh-$nextSequence",
                                    detail = "${event.modelId}@${event.version}",
                                )
                                val statusMessage = context.getString(
                                    R.string.ui_model_version_activated,
                                    event.modelId,
                                    event.version,
                                )
                                viewModel.refreshRuntimeReadiness(statusDetailOverride = statusMessage)
                                provisioningViewModel.setStatusMessage(statusMessage)
                            } else {
                                provisioningViewModel.setStatusMessage(
                                    context.getString(R.string.ui_model_version_activation_failed),
                                )
                            }
                        }
                    }
                    is ModelSheetEvent.LoadVersion -> onLoadModelVersion(event.modelId, event.version, true)
                    is ModelSheetEvent.RetryLoad -> {
                        if (event.version.isNullOrBlank()) {
                            onLoadLastUsedModel(true)
                        } else {
                            onLoadModelVersion(event.modelId, event.version, true)
                        }
                    }
                    ModelSheetEvent.LoadLastUsedModel -> onLoadLastUsedModel(true)
                    ModelSheetEvent.OffloadModel -> onOffloadModel(false)
                    is ModelSheetEvent.RequestRemove -> {
                        pendingRemoveVersion = event.modelId to event.version
                    }
                    ModelSheetEvent.RefreshAll -> {
                        scope.launch {
                            provisioningViewModel.refreshManifest()
                            provisioningViewModel.refreshDownloads()
                            provisioningViewModel.refreshSnapshot()
                            viewModel.refreshRuntimeReadiness()
                            provisioningViewModel.setStatusMessage(
                                context.getString(R.string.ui_model_refresh_runtime_feedback),
                            )
                        }
                    }
                    ModelSheetEvent.Close -> viewModel.dismissSurface()
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
