package com.pocketagent.runtime

import com.pocketagent.core.model.ArtifactBundleSpec
import com.pocketagent.core.model.ModelSourceKind
import com.pocketagent.core.model.ModelSourceRef
import com.pocketagent.core.model.ModelSpecProvider
import com.pocketagent.core.model.ModelVariantSpec
import com.pocketagent.core.model.NormalizedModelSpec
import com.pocketagent.core.model.PromptProfileRegistry
import com.pocketagent.core.model.PromptTemplateFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CompositeModelSpecProviderTest {
    @Test
    fun `first provider wins for duplicate model identities`() {
        val provider = CompositeModelSpecProvider(
            providers = listOf(
                StaticSpecProvider(
                    listOf(
                        testSpec(
                            modelId = "shared-model",
                            displayName = "Bundled Name",
                            variantIds = listOf("bundled"),
                        ),
                    ),
                ),
                StaticSpecProvider(
                    listOf(
                        testSpec(
                            modelId = "shared-model",
                            displayName = "Manifest Name",
                            variantIds = listOf("remote"),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(listOf("Bundled Name"), provider.allSpecs().map { it.displayName })
        assertEquals("Bundled Name", provider.specFor("shared-model")?.displayName)
    }

    @Test
    fun `variant lookup delegates across providers without conflating identities`() {
        val provider = CompositeModelSpecProvider(
            providers = listOf(
                StaticSpecProvider(emptyList()),
                StaticSpecProvider(
                    listOf(
                        testSpec(
                            modelId = "multi-variant-model",
                            displayName = "Multi Variant",
                            variantIds = listOf("q4", "q8"),
                        ),
                    ),
                ),
            ),
        )

        val q8 = provider.variantFor("multi-variant-model", "q8")

        assertNotNull(q8)
        assertEquals("q8", q8.variantId)
    }
}

private class StaticSpecProvider(
    private val specs: List<NormalizedModelSpec>,
) : ModelSpecProvider {
    override fun allSpecs(): List<NormalizedModelSpec> = specs
}

private fun testSpec(
    modelId: String,
    displayName: String,
    variantIds: List<String>,
): NormalizedModelSpec {
    return NormalizedModelSpec(
        modelId = modelId,
        displayName = displayName,
        promptProfile = PromptProfileRegistry.promptProfileFor(PromptTemplateFamily.CHATML),
        variants = variantIds.map { variantId ->
            ModelVariantSpec(
                variantId = variantId,
                artifactBundle = ArtifactBundleSpec(),
                source = ModelSourceRef(kind = ModelSourceKind.UNKNOWN),
            )
        },
    )
}
