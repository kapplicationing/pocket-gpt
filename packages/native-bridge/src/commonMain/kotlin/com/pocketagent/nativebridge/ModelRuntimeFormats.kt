package com.pocketagent.nativebridge

enum class ModelRuntimeFormatFamily {
    STANDARD,
    UNKNOWN_SPECIALIZED,
}

data class ModelRuntimeFormatHint(
    val family: ModelRuntimeFormatFamily,
    val normalizedToken: String? = null,
) {
    val isStandard: Boolean
        get() = family == ModelRuntimeFormatFamily.STANDARD
}

data class ModelRuntimeFormatProbeInput(
    val modelId: String,
    val modelVersion: String? = null,
    val modelPath: String? = null,
    val declaredQuantization: String? = null,
)

object ModelRuntimeFormats {
    private val standardQuantRegex = Regex(
        """(?:^|[._-])(iq[1-4](?:[._-][a-z0-9_]+)?|q[1-8](?:[._-][0-9a-z_]+)?|f16|f32|fp16|fp32|mxfp4(?:[._-]moe)?)(?:[._-]|$)""",
        RegexOption.IGNORE_CASE,
    )
    fun infer(
        modelId: String,
        modelVersion: String?,
        modelPath: String?,
    ): ModelRuntimeFormatHint {
        return infer(
            ModelRuntimeFormatProbeInput(
                modelId = modelId,
                modelVersion = modelVersion,
                modelPath = modelPath,
            ),
        )
    }

    fun infer(input: ModelRuntimeFormatProbeInput): ModelRuntimeFormatHint {
        val hints = buildList {
            add(input.declaredQuantization.orEmpty())
            add(input.modelVersion.orEmpty())
            add(input.modelPath?.substringAfterLast('/')?.substringBeforeLast('.').orEmpty())
            add(input.modelId)
        }.map { raw -> raw.trim().lowercase() }.filter { it.isNotBlank() }

        hints.firstOrNull { standardQuantRegex.containsMatchIn(it) }?.let { raw ->
            return ModelRuntimeFormatHint(
                family = ModelRuntimeFormatFamily.STANDARD,
                normalizedToken = extractToken(raw, standardQuantRegex),
            )
        }
        return ModelRuntimeFormatHint(family = ModelRuntimeFormatFamily.UNKNOWN_SPECIALIZED)
    }

    private fun extractToken(
        raw: String,
        regex: Regex,
    ): String? {
        return regex.find(raw)
            ?.value
            ?.trim('.', '_', '-')
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
    }
}
