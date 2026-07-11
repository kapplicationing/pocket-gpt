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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.pocketagent.android.R
import com.pocketagent.android.runtime.PresetBackingStore
import com.pocketagent.android.ui.components.AppBottomSheet
import com.pocketagent.android.ui.state.ModalSurface
import com.pocketagent.android.ui.state.ModelLoadingState
import com.pocketagent.core.RoutingMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
@Suppress("CyclomaticComplexMethod", "LongMethod", "MaxLineLength")
internal fun ModelLibrarySheetHost(
    activeSurface: ModalSurface,
    provisioningViewModel: ModelProvisioningViewModel,
    defaultGetReadyModelId: String?,
    modelLoadingState: ModelLoadingState,
    routingMode: RoutingMode,
    presetBackingStore: PresetBackingStore,
    modelRemoveUndoState: ModelRemoveUndoState,
    modelImportRequestActive: Boolean,
    actions: ModelLibraryActions,
    debugModelLibraryReadyTagEnabled: Boolean = false,
    debugModelLibraryStatus: String? = null,
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
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val runtimeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pendingRemoveVersion by remember { mutableStateOf<Pair<String, String>?>(null) }

    val sheetModifier = if (debugModelLibraryReadyTagEnabled) {
        Modifier.testTag("debug_model_library_ready")
    } else {
        Modifier
    }.semantics { testTagsAsResourceId = true }

    AppBottomSheet(
        title = stringResource(id = R.string.ui_model_library_title),
        sheetState = runtimeSheetState,
        onDismiss = actions::dismissSheet,
        modifier = sheetModifier,
    ) {
        if (debugModelLibraryReadyTagEnabled && !debugModelLibraryStatus.isNullOrBlank()) {
            Text(
                text = debugModelLibraryStatus,
                modifier = Modifier.testTag("debug_model_library_status"),
            )
            DebugModelLibraryStatusTags(debugModelLibraryStatus)
        }
        if (debugModelLibraryReadyTagEnabled) {
            Text(
                text = provisioningState.debugHuggingFaceTaskStatus(),
                modifier = Modifier.testTag("debug_model_library_task_status"),
            )
            if (provisioningState.hasDebugHuggingFaceTask()) {
                Text(
                    text = "hf_task_present",
                    modifier = Modifier.testTag("debug_model_library_task_present"),
                )
            }
        }
        ModelSheet(
            libraryState = modelLibraryState,
            runtimeState = runtimeModelState,
            modelLoadingState = modelLoadingState,
            routingMode = routingMode,
            presetBackingStore = presetBackingStore,
            modelImportRequestActive = modelImportRequestActive,
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
                    is ModelSheetEvent.SearchHuggingFaceFiles -> scope.launch {
                        actions.searchHuggingFaceFiles(event.query)
                    }
                    ModelSheetEvent.ClearHuggingFaceSearch -> {
                        actions.clearHuggingFaceSearch()
                    }
                    is ModelSheetEvent.RemoveRecentHuggingFaceModel -> scope.launch {
                        actions.removeRecentHuggingFaceModel(event.id)
                    }
                    ModelSheetEvent.ClearRecentHuggingFaceModels -> scope.launch {
                        actions.clearRecentHuggingFaceModels()
                    }
                    is ModelSheetEvent.OpenExternalUrl -> {
                        uriHandler.openUri(event.url)
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

private fun ModelProvisioningUiState.debugHuggingFaceTaskStatus(): String {
    return when (val state = huggingFaceAcquisitionState) {
        HuggingFaceAcquisitionUiState.Idle -> "hf_candidate:idle"
        HuggingFaceAcquisitionUiState.Resolving -> "hf_candidate:resolving"
        is HuggingFaceAcquisitionUiState.Blocked -> "hf_candidate:blocked:${state.reason}"
        is HuggingFaceAcquisitionUiState.Ready -> {
            val version = state.candidate.version
            val key = "${version.modelId}::${version.version}"
            val task = downloads.firstOrNull { download ->
                "${download.modelId}::${download.version}" == key
            }
            when {
                key in enqueuingModelIds && task == null -> buildString {
                    append("hf_task:ENQUEUING")
                    append("|model=${version.modelId}")
                    append("|version=${version.version}")
                }
                task != null -> buildString {
                    append("hf_task:${task.status}")
                    append("|stage=${task.processingStage}")
                    append("|failure=${task.failureReason ?: "none"}")
                    append("|bytes=${task.progressBytes}/${task.totalBytes}")
                    append("|id=${task.taskId}")
                }
                else -> "hf_task:none|model=${version.modelId}|version=${version.version}"
            }
        }
    }
}

private fun ModelProvisioningUiState.hasDebugHuggingFaceTask(): Boolean {
    val version = (huggingFaceAcquisitionState as? HuggingFaceAcquisitionUiState.Ready)?.candidate?.version
        ?: return false
    val key = "${version.modelId}::${version.version}"
    return downloads.any { download ->
        "${download.modelId}::${download.version}" == key
    }
}

@Composable
private fun DebugModelLibraryStatusTags(status: String) {
    val normalized = status.trim()
    val blockedReason = normalized
        .takeIf { it.startsWith("hf_blocked:") }
        ?.substringAfter("hf_blocked:")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.lowercase()
    val statusTag = when {
        normalized == "hf_ready" -> "debug_model_library_status_ready"
        normalized.startsWith("hf_blocked:") -> "debug_model_library_status_blocked"
        normalized.startsWith("hf_resolving:") -> "debug_model_library_status_resolving"
        normalized == "hf_no_target" -> "debug_model_library_status_no_target"
        normalized == "hf_no_url" -> "debug_model_library_status_no_url"
        normalized == "hf_still_resolving" -> "debug_model_library_status_still_resolving"
        normalized == "hf_idle_after_resolve" -> "debug_model_library_status_idle_after_resolve"
        else -> null
    }
    val terminal = normalized.startsWith("hf_") && !normalized.startsWith("hf_resolving:")
    if (terminal) {
        Text(
            text = "hf_terminal",
            modifier = Modifier.testTag("debug_model_library_status_terminal"),
        )
    }
    statusTag?.let { tag ->
        Text(
            text = tag,
            modifier = Modifier.testTag(tag),
        )
    }
    blockedReason?.let { reason ->
        val tag = "debug_model_library_status_blocked_$reason"
        Text(
            text = tag,
            modifier = Modifier.testTag(tag),
        )
    }
}
