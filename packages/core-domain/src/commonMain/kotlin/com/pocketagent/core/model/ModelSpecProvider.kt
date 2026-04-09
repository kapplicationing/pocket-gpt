package com.pocketagent.core.model

interface ModelSpecProvider {
    fun allSpecs(): List<NormalizedModelSpec>

    fun specFor(modelId: String): NormalizedModelSpec? = allSpecs().firstOrNull { spec -> spec.modelId == modelId }

    fun variantFor(modelId: String, version: String?): ModelVariantSpec? = specFor(modelId)?.variant(version)
}
