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
                    ModelTemplateProfile.valueOf(descriptor.chatTemplateId),
                    profile.templateProfile,
                    "template profile mismatch for ${descriptor.modelId}",
                )
            }
    }

    @Test
    fun `registry resolves expected interaction capabilities per model family`() {
        val registry = ModelInteractionRegistry()

        val qwenProfile = registry.interactionProfileForModel(ModelCatalog.QWEN_3_5_0_8B_Q4)
        assertEquals(ThinkingSupport.THINK_TAGS, qwenProfile.thinkingSupport)
        assertTrue(qwenProfile.toolCallSupport is ToolCallSupport.XmlTagFormat)
        assertEquals(SystemPromptStrategy.NATIVE, qwenProfile.systemPromptStrategy)

        val phiProfile = registry.interactionProfileForModel(ModelCatalog.PHI_4_MINI_Q4_K_M)
        assertEquals(ThinkingSupport.THINK_TAGS, phiProfile.thinkingSupport)
        assertEquals(ToolCallSupport.NONE, phiProfile.toolCallSupport)

        val gemmaProfile = registry.interactionProfileForModel(ModelCatalog.GEMMA_4_E2B_Q4_K_M)
        assertEquals(ThinkingSupport.NONE, gemmaProfile.thinkingSupport)
        assertEquals(ToolCallSupport.NONE, gemmaProfile.toolCallSupport)
        assertEquals(SystemPromptStrategy.NATIVE, gemmaProfile.systemPromptStrategy)
        assertEquals("model", gemmaProfile.roleNameOverrides[InteractionRole.ASSISTANT])
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
