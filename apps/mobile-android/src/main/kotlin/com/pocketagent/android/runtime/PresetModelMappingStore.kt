package com.pocketagent.android.runtime

import android.content.Context
import com.pocketagent.core.ModelPreset
import com.pocketagent.core.RoutingMode
import com.pocketagent.inference.PresetRoutingResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface PresetBackingStore {
    fun revisionFlow(): StateFlow<Long>
    fun customBackingModelId(preset: ModelPreset): String?
    fun setCustomBackingModelId(preset: ModelPreset, modelId: String?)
    fun resetToDefaults()
    fun routingModeForPreset(preset: ModelPreset): RoutingMode
    fun presetMatchingRoutingMode(routingMode: RoutingMode): ModelPreset?
}

class PresetModelMappingStore(
    context: Context,
) : PresetBackingStore {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val revision = MutableStateFlow(0L)

    override fun revisionFlow(): StateFlow<Long> = revision.asStateFlow()

    override fun customBackingModelId(preset: ModelPreset): String? {
        if (preset == ModelPreset.AUTO) {
            return null
        }
        return prefs.getString(keyFor(preset), null)?.trim()?.takeIf { it.isNotEmpty() }
    }

    override fun setCustomBackingModelId(preset: ModelPreset, modelId: String?) {
        if (preset == ModelPreset.AUTO) {
            return
        }
        val editor = prefs.edit()
        val trimmed = modelId?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            editor.remove(keyFor(preset))
        } else {
            editor.putString(keyFor(preset), trimmed)
        }
        editor.apply()
        bumpRevision()
    }

    override fun resetToDefaults() {
        prefs.edit().clear().apply()
        bumpRevision()
    }

    override fun routingModeForPreset(preset: ModelPreset): RoutingMode {
        return PresetRoutingResolver.routingModeForPreset(preset, customBackingModelId(preset))
    }

    override fun presetMatchingRoutingMode(routingMode: RoutingMode): ModelPreset? {
        return PresetRoutingResolver.presetMatchingRoutingMode(
            routingMode = routingMode,
            quickCustom = customBackingModelId(ModelPreset.QUICK),
            balancedCustom = customBackingModelId(ModelPreset.BALANCED),
            visionCustom = customBackingModelId(ModelPreset.VISION),
        )
    }

    private fun bumpRevision() {
        revision.value = revision.value + 1L
    }

    private fun keyFor(preset: ModelPreset): String = "backing_${preset.name}"

    private companion object {
        private const val PREFS_NAME = "pocketagent_preset_mappings"
    }
}
