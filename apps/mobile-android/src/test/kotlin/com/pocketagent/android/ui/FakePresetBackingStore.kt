package com.pocketagent.android.ui

import com.pocketagent.android.runtime.PresetBackingStore
import com.pocketagent.core.ModelPreset
import com.pocketagent.core.RoutingMode
import com.pocketagent.inference.PresetRoutingResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class FakePresetBackingStore : PresetBackingStore {
    private val custom = mutableMapOf<ModelPreset, String>()
    private val revision = MutableStateFlow(0L)

    override fun revisionFlow(): StateFlow<Long> = revision.asStateFlow()

    override fun customBackingModelId(preset: ModelPreset): String? {
        return custom[preset]
    }

    override fun setCustomBackingModelId(preset: ModelPreset, modelId: String?) {
        if (preset == ModelPreset.AUTO) {
            return
        }
        val trimmed = modelId?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            custom.remove(preset)
        } else {
            custom[preset] = trimmed
        }
        revision.value = revision.value + 1L
    }

    override fun resetToDefaults() {
        custom.clear()
        revision.value = revision.value + 1L
    }

    override fun routingModeForPreset(preset: ModelPreset): RoutingMode {
        return PresetRoutingResolver.routingModeForPreset(preset, customBackingModelId(preset))
    }

    override fun presetMatchingRoutingMode(routingMode: RoutingMode): ModelPreset? {
        return PresetRoutingResolver.presetMatchingRoutingMode(
            routingMode = routingMode,
            quickCustom = custom[ModelPreset.QUICK],
            balancedCustom = custom[ModelPreset.BALANCED],
            visionCustom = custom[ModelPreset.VISION],
        )
    }
}
