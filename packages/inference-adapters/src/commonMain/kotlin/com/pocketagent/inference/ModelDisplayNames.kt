package com.pocketagent.inference

/**
 * Human-readable labels for known catalog models. Unknown IDs fall back to a simple prettified form.
 * Dynamic / manifest models can be registered at runtime (Android) when discovered.
 */
object ModelDisplayNames {
    private val registry = mutableMapOf(
        ModelCatalog.SMOKE_ECHO_120M to "Smoke Echo (debug)",
        ModelCatalog.QWEN3_0_6B_Q4_K_M to "Qwen3 0.6B",
        ModelCatalog.QWEN3_1_7B_Q4_K_M to "Qwen3 1.7B",
        ModelCatalog.LLAMA_3_2_1B_Q4_K_M to "Llama 3.2 1B",
        ModelCatalog.QWEN_3_5_0_8B_Q4 to "Qwen3.5 0.8B Vision",
    )

    fun register(modelId: String, displayName: String) {
        val trimmedId = modelId.trim()
        val trimmedName = displayName.trim()
        if (trimmedId.isNotEmpty() && trimmedName.isNotEmpty()) {
            registry[trimmedId] = trimmedName
        }
    }

    fun displayNameFor(modelId: String): String {
        val id = modelId.trim()
        if (id.isEmpty()) {
            return ""
        }
        registry[id]?.let { return it }
        return prettifyModelId(id)
    }

    private fun prettifyModelId(modelId: String): String {
        return modelId
            .replace('-', ' ')
            .replace('_', ' ')
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { ch -> ch.uppercaseChar() }
            }
    }
}
