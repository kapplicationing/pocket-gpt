package com.pocketagent.inference

import com.pocketagent.core.ModelPreset
import com.pocketagent.core.RoutingMode

object ModelPresetDefaults {
    fun defaultBackingModelId(preset: ModelPreset): String? {
        return when (preset) {
            ModelPreset.AUTO -> null
            ModelPreset.QUICK -> ModelCatalog.QWEN3_0_6B_Q4_K_M
            ModelPreset.BALANCED -> ModelCatalog.QWEN3_1_7B_Q4_K_M
            ModelPreset.VISION -> ModelCatalog.QWEN_3_5_0_8B_Q4
        }
    }
}

object PresetRoutingResolver {
    fun effectiveBackingModelId(preset: ModelPreset, customBackingModelId: String?): String? {
        if (preset == ModelPreset.AUTO) {
            return null
        }
        val custom = customBackingModelId?.trim().orEmpty()
        if (custom.isNotEmpty()) {
            return custom
        }
        return ModelPresetDefaults.defaultBackingModelId(preset)
    }

    fun routingModeForBacking(backingModelId: String?): RoutingMode {
        if (backingModelId.isNullOrBlank()) {
            return RoutingMode.AUTO
        }
        return ModelCatalog.primaryExplicitRoutingMode(backingModelId) ?: RoutingMode.AUTO
    }

    fun routingModeForPreset(preset: ModelPreset, customBackingModelId: String?): RoutingMode {
        if (preset == ModelPreset.AUTO) {
            return RoutingMode.AUTO
        }
        return routingModeForBacking(effectiveBackingModelId(preset, customBackingModelId))
    }

    fun presetMatchingRoutingMode(
        routingMode: RoutingMode,
        quickCustom: String?,
        balancedCustom: String?,
        visionCustom: String?,
    ): ModelPreset? {
        if (routingMode == RoutingMode.AUTO) {
            return ModelPreset.AUTO
        }
        for (preset in listOf(ModelPreset.QUICK, ModelPreset.BALANCED, ModelPreset.VISION)) {
            val custom = when (preset) {
                ModelPreset.QUICK -> quickCustom
                ModelPreset.BALANCED -> balancedCustom
                ModelPreset.VISION -> visionCustom
                else -> null
            }
            val backing = effectiveBackingModelId(preset, custom)
            if (routingModeForBacking(backing) == routingMode) {
                return preset
            }
        }
        return null
    }

    fun visionPresetEligibleModelIds(installedModelIds: Set<String>): List<String> {
        return installedModelIds
            .filter { modelId -> ModelCatalog.isVisionCapable(modelId) }
            .sorted()
    }

    fun textPresetEligibleModelIds(installedModelIds: Set<String>): List<String> {
        return installedModelIds
            .filter { modelId -> ModelCatalog.descriptorFor(modelId)?.bridgeSupported == true }
            .sorted()
    }
}
