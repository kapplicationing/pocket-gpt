package com.pocketagent.nativebridge

enum class OpenClQuantCompatibility {
    SUPPORTED,
    EXPERIMENTAL,
    UNSUPPORTED,
}

object OpenClRuntimePolicy {
    const val MIN_AUTOMATIC_ADRENO_GENERATION = 7

    private val safeQuantRegex = Regex(
        """(?:^|[._-])(q4[._-]?0|q6[._-]?k)(?:[._-]|$)""",
        RegexOption.IGNORE_CASE,
    )

    private val knownQuantRegex = Regex(
        """(?:^|[._-])(q[1-8](?:[._-][0-9a-z_]+)|iq[1-4](?:[._-][a-z]+)?|f16|f32|fp16|fp32|mxfp4(?:[._-]moe)?)(?:[._-]|$)""",
        RegexOption.IGNORE_CASE,
    )

    fun releaseQuantCompatibility(
        modelPath: String?,
        modelId: String,
        modelVersion: String?,
    ): OpenClQuantCompatibility {
        val versionCompatibility = classifyQuantHint(modelVersion.orEmpty())
        if (versionCompatibility != null) {
            return versionCompatibility
        }
        val filenameStem = modelPath
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            .orEmpty()
            .lowercase()
        return buildList {
            if (filenameStem.isNotBlank()) {
                add(filenameStem)
            }
            val normalizedModelId = modelId.trim().lowercase()
            if (normalizedModelId.isNotBlank()) {
                add(normalizedModelId)
            }
        }.asSequence()
            .mapNotNull(::classifyQuantHint)
            .firstOrNull()
            ?: OpenClQuantCompatibility.EXPERIMENTAL
    }

    fun isReleaseSafeQuantization(
        modelPath: String?,
        modelId: String,
        modelVersion: String?,
    ): Boolean = releaseQuantCompatibility(
        modelPath = modelPath,
        modelId = modelId,
        modelVersion = modelVersion,
    ) == OpenClQuantCompatibility.SUPPORTED

    private fun classifyQuantHint(rawHint: String): OpenClQuantCompatibility? {
        if (rawHint.isBlank()) {
            return null
        }
        return when {
            safeQuantRegex.containsMatchIn(rawHint) -> OpenClQuantCompatibility.SUPPORTED
            knownQuantRegex.containsMatchIn(rawHint) -> OpenClQuantCompatibility.UNSUPPORTED
            else -> null
        }
    }
}
