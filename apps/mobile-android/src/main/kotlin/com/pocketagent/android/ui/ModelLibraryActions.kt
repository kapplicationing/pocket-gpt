package com.pocketagent.android.ui

import android.content.Context
import com.pocketagent.android.R
import com.pocketagent.android.runtime.MODEL_OFFLOAD_REASON_MANUAL
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.ui.state.ModelLoadingState
import com.pocketagent.android.ui.state.activeOrRequestedModel
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult

internal class ModelLibraryActions(
    private val context: Context,
    private val viewModel: ChatViewModel,
    private val provisioningViewModel: ModelProvisioningViewModel,
    private val appViewModel: ChatAppViewModel,
    private val chatAppLaunchers: ChatAppLaunchers,
    private val defaultGetReadyModelId: String?,
    private val modelLoadingStateProvider: () -> ModelLoadingState,
    private val modelLibraryStateProvider: () -> ModelLibraryUiState,
    private val showBusyModelOperationFeedback: suspend () -> Unit,
) {
    fun dismissSheet() {
        viewModel.dismissSurface()
    }

    fun importModel(modelId: String) {
        appViewModel.setSelectedModelIdForImport(modelId)
        chatAppLaunchers.launchModelImportPicker()
    }

    suspend fun resolveHuggingFaceCandidate(input: String, targetModelId: String) {
        provisioningViewModel.resolveHuggingFaceCandidate(input = input, targetModelId = targetModelId)
    }

    fun clearHuggingFaceCandidate() {
        provisioningViewModel.clearHuggingFaceCandidate()
    }

    fun downloadVersion(version: ModelDistributionVersion) {
        chatAppLaunchers.launchDownloadFlow(version)
    }

    suspend fun pauseDownload(taskId: String) {
        provisioningViewModel.pauseDownloadAsync(taskId)
        provisioningViewModel.setStatusMessage(context.getString(R.string.ui_model_download_paused))
    }

    suspend fun resumeDownload(taskId: String) {
        provisioningViewModel.resumeDownloadAsync(taskId)
        provisioningViewModel.setStatusMessage(context.getString(R.string.ui_model_download_resumed))
    }

    suspend fun retryDownload(taskId: String) {
        provisioningViewModel.retryDownloadAsync(taskId)
        provisioningViewModel.setStatusMessage(context.getString(R.string.ui_model_download_retried))
    }

    suspend fun cancelDownload(taskId: String) {
        provisioningViewModel.cancelDownloadAsync(taskId)
        provisioningViewModel.setStatusMessage(context.getString(R.string.ui_model_download_cancelled))
    }

    suspend fun activateVersion(modelId: String, version: String): Boolean {
        val activationResult = provisioningViewModel.setActiveVersionAsync(modelId, version)
        if (!activationResult.changed) {
            provisioningViewModel.setStatusMessage(
                provisioningMutationFailureMessage(
                    result = activationResult,
                    fallbackMessage = context.getString(R.string.ui_model_version_activation_failed),
                ),
            )
            return false
        }
        val nextSequence = appViewModel.incrementReadinessRefreshSequence()
        logProvisioningTransition(
            phase = "manual_activation",
            eventId = "refresh-$nextSequence",
            detail = "$modelId@$version",
        )
        val statusMessage = context.getString(
            R.string.ui_model_version_activated,
            modelId,
            version,
        )
        viewModel.refreshRuntimeReadiness(statusDetailOverride = statusMessage)
        provisioningViewModel.setStatusMessage(statusMessage)
        return true
    }

    suspend fun loadModelVersion(
        modelId: String,
        version: String,
        closeOnSuccess: Boolean,
    ): RuntimeModelLifecycleCommandResult? {
        val result = provisioningViewModel.loadModel(modelId = modelId, version = version)
        if (result == null) {
            showBusyModelOperationFeedback()
            return null
        }
        viewModel.handleCompletedModelOperation(result)
        provisioningViewModel.setStatusMessage(
            lifecycleStatusMessage(
                context = context,
                result = result,
                fallbackModelId = modelId,
                fallbackVersion = version,
            ),
        )
        if (result.success && closeOnSuccess) {
            viewModel.dismissSurface()
        }
        return result
    }

    suspend fun loadLastUsedModel(closeOnSuccess: Boolean): RuntimeModelLifecycleCommandResult? {
        val result = provisioningViewModel.loadLastUsedModel()
        if (result == null) {
            showBusyModelOperationFeedback()
            return null
        }
        viewModel.handleCompletedModelOperation(result)
        val loadingState = modelLoadingStateProvider()
        provisioningViewModel.setStatusMessage(
            lifecycleStatusMessage(
                context = context,
                result = result,
                fallbackModelId = loadingState.lastUsedModel?.modelId,
                fallbackVersion = loadingState.lastUsedModel?.modelVersion,
            ),
        )
        if (result.success && closeOnSuccess) {
            viewModel.dismissSurface()
        }
        return result
    }

    suspend fun offloadModel(closeOnSuccess: Boolean): RuntimeModelLifecycleCommandResult? {
        val result = provisioningViewModel.offloadModel(reason = MODEL_OFFLOAD_REASON_MANUAL)
        if (result == null) {
            showBusyModelOperationFeedback()
            return null
        }
        viewModel.handleCompletedModelOperation(result)
        val loadingState = modelLoadingStateProvider()
        provisioningViewModel.setStatusMessage(
            lifecycleStatusMessage(
                context = context,
                result = result,
                fallbackModelId = loadingState.activeOrRequestedModel()?.modelId,
                fallbackVersion = loadingState.activeOrRequestedModel()?.modelVersion,
            ),
        )
        if (result.success && closeOnSuccess) {
            viewModel.dismissSurface()
        }
        return result
    }

    suspend fun refreshAll() {
        provisioningViewModel.refreshManifest()
        provisioningViewModel.refreshDownloadsAsync()
        viewModel.refreshRuntimeReadiness()
        provisioningViewModel.setStatusMessage(
            context.getString(R.string.ui_model_refresh_runtime_feedback),
        )
    }

    suspend fun runGetReadyFlow() {
        viewModel.onGetReadyTapped()
        provisioningViewModel.setStatusMessage(context.getString(R.string.ui_get_ready_started_status))
        provisioningViewModel.refreshManifest()
        val manifest = provisioningViewModel.uiState.value.manifest
        val defaultVersion = resolveDefaultGetReadyVersion(
            manifest = manifest,
            defaultModelId = defaultGetReadyModelId,
        )
        if (defaultVersion == null) {
            provisioningViewModel.setStatusMessage(
                context.getString(R.string.ui_model_downloads_manifest_empty),
            )
            viewModel.showSurface(com.pocketagent.android.ui.state.ModalSurface.ModelLibrary)
            return
        }

        val existingVersion = provisioningViewModel.listInstalledVersionsAsync(
            modelId = defaultVersion.modelId,
        ).firstOrNull { it.version == defaultVersion.version }

        if (existingVersion != null) {
            val activationResult = provisioningViewModel.setActiveVersionAsync(
                modelId = defaultVersion.modelId,
                version = defaultVersion.version,
            )
            if (!activationResult.changed) {
                provisioningViewModel.setStatusMessage(
                    provisioningMutationFailureMessage(
                        result = activationResult,
                        fallbackMessage = context.getString(R.string.ui_model_version_activation_failed),
                    ),
                )
                return
            }
            loadModelVersion(
                modelId = defaultVersion.modelId,
                version = defaultVersion.version,
                closeOnSuccess = false,
            )
            return
        }

        appViewModel.setPendingGetReadyActivation(defaultVersion.modelId to defaultVersion.version)
        chatAppLaunchers.launchDownloadFlow(defaultVersion)
        viewModel.showSurface(com.pocketagent.android.ui.state.ModalSurface.ModelLibrary)
    }

    suspend fun removeVersion(modelId: String, version: String) {
        val modelLibraryState = modelLibraryStateProvider()
        val model = modelLibraryState.snapshot.models.firstOrNull { it.modelId == modelId }
        val targetVersion = model?.installedVersions?.firstOrNull { it.version == version }
        val removePlan = if (model != null && targetVersion != null) {
            resolveRemoveVersionPlan(
                model = model,
                version = targetVersion,
                loadedModel = modelLoadingStateProvider().loadedModel,
            )
        } else {
            null
        }
        if (removePlan?.isBlockedByActiveSelection == true) {
            provisioningViewModel.setStatusMessage(
                context.getString(R.string.ui_model_version_remove_failed),
            )
            return
        }
        if (removePlan?.requiresOffload == true) {
            provisioningViewModel.offloadModel(reason = MODEL_OFFLOAD_REASON_MANUAL)
        }
        if (removePlan?.requiresClearingActiveSelection == true) {
            provisioningViewModel.clearActiveVersionAsync(modelId)
        }
        val removeResult = provisioningViewModel.removeVersionAsync(modelId, version)
        val statusMessage = if (removeResult.changed) {
            context.getString(R.string.ui_model_version_removed, modelId, version)
        } else {
            provisioningMutationFailureMessage(
                result = removeResult,
                fallbackMessage = context.getString(R.string.ui_model_version_remove_failed),
            )
        }
        if (removeResult.changed) {
            viewModel.refreshRuntimeReadiness(statusDetailOverride = statusMessage)
        }
        provisioningViewModel.setStatusMessage(statusMessage)
    }
}
