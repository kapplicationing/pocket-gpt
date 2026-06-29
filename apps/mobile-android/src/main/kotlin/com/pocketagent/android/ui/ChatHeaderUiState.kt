package com.pocketagent.android.ui

import com.pocketagent.android.ui.state.ModelLoadingState
import com.pocketagent.inference.ModelDisplayNames
import com.pocketagent.runtime.RuntimeLoadedModel

internal data class ChatHeaderUiState(
    val activeRuntimeModelLabel: String?,
    val lastUsedModelLabel: String?,
    val canLoadLastUsedModel: Boolean,
)

internal fun deriveChatHeaderUiState(
    modelLoadingState: ModelLoadingState,
): ChatHeaderUiState {
    val activeRuntimeModelLabel = modelLoadingState.loadedModel?.toLastUsedChipLabel()
    val lastUsedModelLabel = modelLoadingState.lastUsedModel?.toLastUsedChipLabel()
    val canLoadLastUsedModel = modelLoadingState.loadedModel == null &&
        modelLoadingState.lastUsedModel != null &&
        modelLoadingState !is ModelLoadingState.Error &&
        modelLoadingState !is ModelLoadingState.Loading &&
        modelLoadingState !is ModelLoadingState.Offloading
    return ChatHeaderUiState(
        activeRuntimeModelLabel = activeRuntimeModelLabel,
        lastUsedModelLabel = lastUsedModelLabel,
        canLoadLastUsedModel = canLoadLastUsedModel,
    )
}

private fun RuntimeLoadedModel.toLastUsedChipLabel(): String {
    val name = ModelDisplayNames.displayNameFor(modelId)
    val ver = modelVersion?.trim().orEmpty()
    return if (ver.isNotEmpty()) {
        "$name $ver"
    } else {
        name
    }
}
