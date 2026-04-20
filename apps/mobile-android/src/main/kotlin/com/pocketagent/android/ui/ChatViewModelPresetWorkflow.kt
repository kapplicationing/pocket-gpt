package com.pocketagent.android.ui

import com.pocketagent.core.ModelPreset

internal fun ChatViewModel.setCustomPresetBacking(preset: ModelPreset, modelId: String) {
    presetBackingStore.setCustomBackingModelId(preset, modelId)
}
