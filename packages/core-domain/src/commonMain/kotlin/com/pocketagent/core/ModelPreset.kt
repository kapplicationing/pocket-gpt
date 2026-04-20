package com.pocketagent.core

/** User-facing model tier for chat routing (Quick / Balanced / Vision / Auto). */
enum class ModelPreset {
    AUTO,
    QUICK,
    BALANCED,
    VISION,
    ;

    companion object {
        val selectablePresets: List<ModelPreset> = listOf(AUTO, QUICK, BALANCED, VISION)
    }
}
