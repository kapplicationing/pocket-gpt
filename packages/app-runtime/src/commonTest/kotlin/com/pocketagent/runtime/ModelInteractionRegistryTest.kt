package com.pocketagent.runtime

import com.pocketagent.inference.ModelCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModelInteractionRegistryTest {
    @Test
    fun `registry provides interaction profile for every bridge-supported model`() {
        val registry = ModelInteractionRegistry()

        ModelCatalog.modelDescriptors()
            .filter { descriptor -> descriptor.bridgeSupported }
            .forEach { descriptor ->
                val profile = registry.interactionProfileForModel(descriptor.modelId)
                assertEquals(
                    descriptor.templateFamily,
                    profile.templateFamily,
                    "template family mismatch for ${descriptor.modelId}",
                )
            }
    }

    @Test
    fun `registry resolves expected interaction capabilities per model family`() {
        val registry = ModelInteractionRegistry()

        val qwenProfile = registry.interactionProfileForModel(ModelCatalog.QWEN3_1_7B_Q4_K_M)
        assertEquals(ThinkingSupport.THINK_TAGS, qwenProfile.thinkingSupport)
        assertTrue(qwenProfile.toolCallSupport is ToolCallSupport.XmlTagFormat)
        assertEquals(SystemPromptStrategy.NATIVE, qwenProfile.systemPromptStrategy)

        val llamaProfile = registry.interactionProfileForModel(ModelCatalog.LLAMA_3_2_1B_Q4_K_M)
        assertEquals(ThinkingSupport.NONE, llamaProfile.thinkingSupport)
        assertEquals(ToolCallSupport.NONE, llamaProfile.toolCallSupport)
        assertEquals(SystemPromptStrategy.NATIVE, llamaProfile.systemPromptStrategy)
        assertEquals(null, llamaProfile.roleNameOverrides[InteractionRole.ASSISTANT])
    }

    @Test
    fun `registry reports template unavailable for unknown model`() {
        val registry = ModelInteractionRegistry()

        val error = assertFailsWith<RuntimeTemplateUnavailableException> {
            registry.interactionProfileForModel("unknown-model")
        }
        assertTrue(error.message?.contains("TEMPLATE_UNAVAILABLE") == true)
        assertTrue(registry.ensureTemplateAvailable("unknown-model")?.contains("TEMPLATE_UNAVAILABLE") == true)
    }
}
