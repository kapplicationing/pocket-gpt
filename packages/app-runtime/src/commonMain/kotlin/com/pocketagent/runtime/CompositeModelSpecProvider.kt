package com.pocketagent.runtime

import com.pocketagent.core.model.ModelSpecProvider
import com.pocketagent.core.model.ModelVariantSpec
import com.pocketagent.core.model.NormalizedModelSpec

class CompositeModelSpecProvider(
    private val providers: List<ModelSpecProvider>,
) : ModelSpecProvider {
    override fun allSpecs(): List<NormalizedModelSpec> {
        val seenModelIds = mutableSetOf<String>()
        return providers
            .flatMap { provider -> provider.allSpecs() }
            .filter { spec -> seenModelIds.add(spec.modelId) }
    }

    override fun specFor(modelId: String): NormalizedModelSpec? {
        return providers.firstNotNullOfOrNull { provider -> provider.specFor(modelId) }
    }

    override fun variantFor(modelId: String, version: String?): ModelVariantSpec? {
        return providers.firstNotNullOfOrNull { provider -> provider.variantFor(modelId, version) }
    }
}
