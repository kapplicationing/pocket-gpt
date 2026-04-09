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
            ModelCatalog.QWEN3_0_6B_Q4_K_M,
            ModelCatalog.defaultGetReadyModelId(ModelRuntimeProfile.PROD),
        )
        assertEquals(
            ModelCatalog.QWEN3_0_6B_Q4_K_M,
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
        val supportedModels = setOf(ModelCatalog.QWEN3_0_6B_Q4_K_M)

        val unsupported = ModelCatalog.validateBridgeLoad(
            modelId = ModelCatalog.LLAMA_3_2_1B_Q4_K_M,
            modelPath = "/tmp/model.gguf",
            supportedModels = supportedModels,
        )
        val missingPath = ModelCatalog.validateBridgeLoad(
            modelId = ModelCatalog.QWEN3_0_6B_Q4_K_M,
            modelPath = "",
            supportedModels = supportedModels,
        )
        val invalidPath = ModelCatalog.validateBridgeLoad(
            modelId = ModelCatalog.QWEN3_0_6B_Q4_K_M,
            modelPath = "/tmp/model.bin",
            supportedModels = supportedModels,
        )
        val valid = ModelCatalog.validateBridgeLoad(
            modelId = ModelCatalog.QWEN3_0_6B_Q4_K_M,
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
    fun `explicit alternative model stays out of startup and auto routing`() {
        val descriptor = ModelCatalog.descriptorFor(ModelCatalog.LLAMA_3_2_1B_Q4_K_M)!!
        assertEquals(false, descriptor.startupCandidate)
        assertEquals(false, descriptor.autoRoutingEnabled)
        assertEquals(ModelTier.BASELINE, descriptor.tier)
    }

    @Test
    fun `vision lane remains auto routable and explicit text alternative keeps routing enum`() {
        val vision = ModelCatalog.descriptorFor(ModelCatalog.QWEN_3_5_0_8B_Q4)!!
        val llama = ModelCatalog.descriptorFor(ModelCatalog.LLAMA_3_2_1B_Q4_K_M)!!
        assertTrue(vision.autoRoutingEnabled)
        assertTrue(ModelCatalog.routingModesForModel(vision.modelId).contains(RoutingMode.QWEN_0_8B))
        assertEquals(false, llama.autoRoutingEnabled)
        assertTrue(ModelCatalog.routingModesForModel(llama.modelId).contains(RoutingMode.LLAMA_3_2_1B))
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
                targetModelId = ModelCatalog.QWEN3_1_7B_Q4_K_M,
                draftModelId = ModelCatalog.QWEN3_0_6B_Q4_K_M,
            ),
        )
        assertTrue(
            ModelCatalog.isSpeculativeDraftCompatible(
                targetModelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
                draftModelId = ModelCatalog.QWEN3_0_6B_Q4_K_M,
            ),
        )
        assertFalse(
            ModelCatalog.isSpeculativeDraftCompatible(
                targetModelId = ModelCatalog.LLAMA_3_2_1B_Q4_K_M,
                draftModelId = ModelCatalog.QWEN3_0_6B_Q4_K_M,
            ),
        )
        assertFalse(
            ModelCatalog.isSpeculativeDraftCompatible(
                targetModelId = "missing-target",
                draftModelId = ModelCatalog.QWEN3_0_6B_Q4_K_M,
            ),
        )
    }

    @Test
    fun `normalized specs expose prompt profile and artifact bundle metadata`() {
        val llama = ModelCatalog.normalizedSpecFor(ModelCatalog.LLAMA_3_2_1B_Q4_K_M)
        val qwenVision = ModelCatalog.normalizedSpecFor(ModelCatalog.QWEN_3_5_0_8B_Q4)

        assertEquals("llama3-default", llama?.promptProfile?.profileId)
        assertEquals("assistant", llama?.promptProfile?.assistantRoleName)
        assertTrue(
            qwenVision?.variants?.firstOrNull()
                ?.artifactBundle
                ?.artifacts
                ?.any { artifact -> artifact.role == ModelArtifactRole.MMPROJ } == true,
        )
    }
}
