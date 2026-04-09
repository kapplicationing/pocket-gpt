package com.pocketagent.inference

import com.pocketagent.core.RoutingMode
import com.pocketagent.core.model.ModelArtifactRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelCatalogTest {
    @Test
    fun `default get ready model follows runtime profile`() {
        assertEquals(
            ModelCatalog.QWEN_3_5_0_8B_Q4,
            ModelCatalog.defaultGetReadyModelId(ModelRuntimeProfile.PROD),
        )
        assertEquals(
            ModelCatalog.QWEN_3_5_0_8B_Q4,
            ModelCatalog.defaultGetReadyModelId(ModelRuntimeProfile.DEV_FAST),
        )
    }

    @Test
    fun `bridge supported model list is descriptor driven`() {
        val supported = ModelCatalog.bridgeSupportedModels().toSet()
        val expected = ModelCatalog.modelDescriptors()
            .filter { it.bridgeSupported }
            .map { it.modelId }
            .toSet()
        assertEquals(expected, supported)
        assertTrue(!supported.contains(ModelCatalog.SMOKE_ECHO_120M))
    }

    @Test
    fun `bridge load validation enforces model and gguf path rules`() {
        val supportedModels = setOf(ModelCatalog.QWEN_3_5_0_8B_Q4)

        val unsupported = ModelCatalog.validateBridgeLoad(
            modelId = ModelCatalog.QWEN_3_5_2B_Q4,
            modelPath = "/tmp/model.gguf",
            supportedModels = supportedModels,
        )
        val missingPath = ModelCatalog.validateBridgeLoad(
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            modelPath = "",
            supportedModels = supportedModels,
        )
        val invalidPath = ModelCatalog.validateBridgeLoad(
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            modelPath = "/tmp/model.bin",
            supportedModels = supportedModels,
        )
        val valid = ModelCatalog.validateBridgeLoad(
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            modelPath = "/tmp/model.gguf",
            supportedModels = supportedModels,
        )

        assertEquals(false, unsupported.accepted)
        assertEquals("MODEL_UNSUPPORTED", unsupported.code)
        assertEquals(false, missingPath.accepted)
        assertEquals("MODEL_PATH_MISSING", missingPath.code)
        assertEquals(false, invalidPath.accepted)
        assertEquals("MODEL_PATH_INVALID", invalidPath.code)
        assertEquals(true, valid.accepted)
        assertEquals("/tmp/model.gguf", valid.normalizedModelPath)
    }

    @Test
    fun `explicit routing model lookup is descriptor driven`() {
        ModelCatalog.modelDescriptors()
            .flatMap { descriptor ->
                descriptor.explicitRoutingModes.map { mode -> mode to descriptor.modelId }
            }
            .forEach { (mode, expectedModelId) ->
                assertEquals(expectedModelId, ModelCatalog.modelIdForRoutingMode(mode), "routing mode $mode")
            }
        assertNull(ModelCatalog.modelIdForRoutingMode(RoutingMode.AUTO))
    }

    @Test
    fun `routing modes for model include auto for auto-routing models`() {
        ModelCatalog.modelDescriptors()
            .filter { it.includeAutoRoutingMode }
            .forEach { descriptor ->
                val modes = ModelCatalog.routingModesForModel(descriptor.modelId)
                assertTrue(
                    modes.contains(RoutingMode.AUTO),
                    "model ${descriptor.modelId} should include AUTO routing mode",
                )
                assertTrue(
                    modes.containsAll(descriptor.explicitRoutingModes),
                    "model ${descriptor.modelId} should include its explicit routing modes",
                )
            }
    }

    @Test
    fun `draft model is not a startup candidate or auto-routing candidate`() {
        val descriptor = ModelCatalog.descriptorFor(ModelCatalog.SMOLLM3_3B_UD_IQ2_XXS)!!
        assertEquals(false, descriptor.startupCandidate)
        assertEquals(false, descriptor.autoRoutingEnabled)
        assertEquals(ModelTier.DEBUG, descriptor.tier)
    }

    @Test
    fun `bonsai expansion tiers stay bridge-supported but opt in only`() {
        listOf(
            ModelCatalog.BONSAI_1_7B_Q1_0_G128 to 4,
            ModelCatalog.BONSAI_4B_Q1_0_G128 to 6,
        ).forEach { (modelId, minRamGb) ->
            val descriptor = ModelCatalog.descriptorFor(modelId)!!
            assertTrue(descriptor.bridgeSupported, "$modelId should stay runtime-enabled")
            assertEquals(false, descriptor.autoRoutingEnabled, "$modelId should stay out of auto-routing")
            assertEquals(false, descriptor.startupCandidate, "$modelId should stay opt-in")
            assertEquals(minRamGb, descriptor.minRamGb, "$modelId should keep the expected RAM floor")
            assertTrue(ModelCatalog.routingModesForModel(modelId).isEmpty(), "$modelId should not require a routing enum")
        }
    }

    @Test
    fun `descriptors expose env key tokens`() {
        ModelCatalog.modelDescriptors().forEach { descriptor ->
            assertEquals(
                descriptor.modelId,
                ModelCatalog.descriptorForEnvKeyToken(descriptor.envKeyToken)?.modelId,
                "env key token lookup failed for ${descriptor.envKeyToken}",
            )
        }
    }

    @Test
    fun `speculative draft compatibility is descriptor family driven`() {
        assertTrue(
            ModelCatalog.isSpeculativeDraftCompatible(
                targetModelId = ModelCatalog.SMOLLM3_3B_Q4_K_M,
                draftModelId = ModelCatalog.SMOLLM3_3B_UD_IQ2_XXS,
            ),
        )
        assertTrue(
            ModelCatalog.isSpeculativeDraftCompatible(
                targetModelId = ModelCatalog.QWEN_3_5_2B_Q4,
                draftModelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            ),
        )
        assertFalse(
            ModelCatalog.isSpeculativeDraftCompatible(
                targetModelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
                draftModelId = ModelCatalog.SMOLLM3_3B_UD_IQ2_XXS,
            ),
        )
        assertFalse(
            ModelCatalog.isSpeculativeDraftCompatible(
                targetModelId = "missing-target",
                draftModelId = ModelCatalog.SMOLLM3_3B_UD_IQ2_XXS,
            ),
        )
    }

    @Test
    fun `normalized specs expose prompt profile and artifact bundle metadata`() {
        val gemma = ModelCatalog.normalizedSpecFor(ModelCatalog.GEMMA_4_E2B_Q4_K_M)
        val qwenVision = ModelCatalog.normalizedSpecFor(ModelCatalog.QWEN_3_5_0_8B_Q4)

        assertEquals("gemma4-e2b", gemma?.promptProfile?.profileId)
        assertEquals("model", gemma?.promptProfile?.assistantRoleName)
        assertTrue(
            qwenVision?.variants?.firstOrNull()
                ?.artifactBundle
                ?.artifacts
                ?.any { artifact -> artifact.role == ModelArtifactRole.MMPROJ } == true,
        )
    }
}
