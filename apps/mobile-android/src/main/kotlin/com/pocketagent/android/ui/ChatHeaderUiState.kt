package com.pocketagent.android.ui

import com.pocketagent.android.runtime.PresetBackingStore
import com.pocketagent.android.ui.state.ModelLoadingState
import com.pocketagent.core.ModelPreset
import com.pocketagent.core.RoutingMode
import com.pocketagent.inference.ModelDisplayNames
import com.pocketagent.runtime.RuntimeLoadedModel

internal data class ChatHeaderUiState(
    val activeRuntimeModelLabel: String?,
    val lastUsedModelLabel: String?,
    val canLoadLastUsedModel: Boolean,
)

internal fun deriveChatHeaderUiState(
    modelLoadingState: ModelLoadingState,
    routingMode: RoutingMode,
    presetBackingStore: PresetBackingStore,
): ChatHeaderUiState {
    val activeRuntimeModelLabel = modelLoadingState.loadedModel?.toActiveChipLabel(
        routingMode = routingMode,
        presetBackingStore = presetBackingStore,
    )
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

private fun RuntimeLoadedModel.toActiveChipLabel(
    routingMode: RoutingMode,
    presetBackingStore: PresetBackingStore,
): String {
    val preset = presetBackingStore.presetMatchingRoutingMode(routingMode)
    if (preset != null && preset != ModelPreset.AUTO) {
        return presetHeaderLabel(preset)
    }
    if (routingMode == RoutingMode.AUTO) {
        return "Auto"
    }
    return toLastUsedChipLabel()
}

private fun presetHeaderLabel(preset: ModelPreset): String {
    return when (preset) {
        ModelPreset.AUTO -> "Auto"
        ModelPreset.QUICK -> "Quick"
        ModelPreset.BALANCED -> "Balanced"
        ModelPreset.VISION -> "Vision"
    }
}
