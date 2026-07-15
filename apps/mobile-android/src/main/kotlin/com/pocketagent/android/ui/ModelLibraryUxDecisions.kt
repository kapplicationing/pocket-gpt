package com.pocketagent.android.ui

import com.pocketagent.android.runtime.ProvisionedModelState
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.DownloadTaskStatus
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.android.ui.state.ModelLoadingState
import com.pocketagent.runtime.RuntimeLoadedModel

internal enum class ModelLibrarySection {
    MY_MODELS,
    EXPLORE,
}

internal data class ModelLibraryNavigationState(
    val selectedSection: ModelLibrarySection = ModelLibrarySection.MY_MODELS,
    val advancedSourcesExpanded: Boolean = false,
)

internal enum class DownloadedModelBadge {
    LOADED,
    SWITCHING,
    READY,
}

internal data class RemoveVersionPlan(
    val requiresOffload: Boolean,
    val requiresClearingActiveSelection: Boolean,
    val isBlockedByActiveSelection: Boolean,
)

internal fun resolveDownloadedModelBadge(
    model: ProvisionedModelState,
    version: ModelVersionDescriptor,
    activeModel: RuntimeLoadedModel?,
    loadedModel: RuntimeLoadedModel?,
): DownloadedModelBadge {
    val isLoaded = loadedModel?.modelId == model.modelId && loadedModel.modelVersion == version.version
    if (isLoaded) {
        return DownloadedModelBadge.LOADED
    }
    val isRequested = activeModel?.modelId == model.modelId &&
        activeModel.modelVersion == version.version &&
        loadedModel != activeModel
    if (isRequested) {
        return DownloadedModelBadge.SWITCHING
    }
    return DownloadedModelBadge.READY
}

internal fun ModelLoadingState.switchingRequestedModel(): RuntimeLoadedModel? {
    return (this as? ModelLoadingState.Loading)?.requestedModel
}

internal fun managementDownloadTasks(downloads: List<DownloadTaskState>): List<DownloadTaskState> =
    downloads
        .filter { task ->
            !task.terminal ||
                task.status == DownloadTaskStatus.FAILED ||
                task.status == DownloadTaskStatus.CANCELLED
        }
        .sortedByDescending { task -> task.updatedAtEpochMs }

internal fun resolveModelLibraryBackNavigation(
    state: ModelLibraryNavigationState,
): ModelLibraryNavigationState? =
    when {
        state.advancedSourcesExpanded -> state.copy(advancedSourcesExpanded = false)
        state.selectedSection != ModelLibrarySection.MY_MODELS ->
            state.copy(selectedSection = ModelLibrarySection.MY_MODELS)
        else -> null
    }

internal fun resolveRemoveVersionPlan(
    model: ProvisionedModelState,
    version: ModelVersionDescriptor,
    loadedModel: RuntimeLoadedModel?,
): RemoveVersionPlan {
    val isLoaded = loadedModel?.modelId == model.modelId && loadedModel.modelVersion == version.version
    val isActive = version.isActive || model.activeVersion == version.version
    val isOnlyInstalledVersion = model.installedVersions.size <= 1
    val canClearActiveSelection = isActive && isOnlyInstalledVersion
    return RemoveVersionPlan(
        requiresOffload = isLoaded,
        requiresClearingActiveSelection = canClearActiveSelection,
        isBlockedByActiveSelection = isActive && !canClearActiveSelection,
    )
}
