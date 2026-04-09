package com.pocketagent.runtime

import com.pocketagent.core.RoutingMode
import com.pocketagent.inference.DeviceState
import com.pocketagent.inference.InferenceModule
import com.pocketagent.inference.InferenceRequest
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.inference.RoutingModule
import com.pocketagent.nativebridge.CachePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelLifecycleCoordinatorTest {
    @Test
    fun `select runnable model returns preferred model when available`() {
        val inference = LifecycleInferenceModule(
            availableModels = listOf(ModelCatalog.QWEN3_0_6B_Q4_K_M, ModelCatalog.QWEN3_1_7B_Q4_K_M),
        )
        val coordinator = ModelLifecycleCoordinator(
            inferenceModule = inference,
            routingModule = LifecycleRoutingModule(selectedModel = ModelCatalog.QWEN3_1_7B_Q4_K_M),
            runtimeConfig = lifecycleRuntimeConfig(),
        )

        val selected = coordinator.selectRunnableModelId(
            routingMode = RoutingMode.AUTO,
            taskType = "short_text",
            deviceState = DEVICE_STATE,
        )

        assertEquals(ModelCatalog.QWEN3_1_7B_Q4_K_M, selected)
    }

    @Test
    fun `select runnable model falls back to available preferred order`() {
        val inference = LifecycleInferenceModule(
            availableModels = listOf(ModelCatalog.QWEN3_1_7B_Q4_K_M),
        )
        val coordinator = ModelLifecycleCoordinator(
            inferenceModule = inference,
            routingModule = LifecycleRoutingModule(selectedModel = ModelCatalog.QWEN3_0_6B_Q4_K_M),
            runtimeConfig = lifecycleRuntimeConfig(),
        )

        val selected = coordinator.selectRunnableModelId(
            routingMode = RoutingMode.AUTO,
            taskType = "short_text",
            deviceState = DEVICE_STATE,
        )

        assertEquals(ModelCatalog.QWEN3_1_7B_Q4_K_M, selected)
    }

    @Test
    fun `resolve native cache policy respects strict and disabled settings`() {
        val inference = LifecycleInferenceModule(availableModels = emptyList())
        val routing = LifecycleRoutingModule(selectedModel = ModelCatalog.QWEN_3_5_0_8B_Q4)

        val disabled = ModelLifecycleCoordinator(
            inferenceModule = inference,
            routingModule = routing,
            runtimeConfig = lifecycleRuntimeConfig(prefixCacheEnabled = false, prefixCacheStrict = false),
        )
        val strict = ModelLifecycleCoordinator(
            inferenceModule = inference,
            routingModule = routing,
            runtimeConfig = lifecycleRuntimeConfig(prefixCacheEnabled = true, prefixCacheStrict = true),
        )
        val nonStrict = ModelLifecycleCoordinator(
            inferenceModule = inference,
            routingModule = routing,
            runtimeConfig = lifecycleRuntimeConfig(prefixCacheEnabled = true, prefixCacheStrict = false),
        )

        assertEquals(CachePolicy.OFF, disabled.resolveNativeCachePolicy())
        assertEquals(CachePolicy.PREFIX_KV_REUSE_STRICT, strict.resolveNativeCachePolicy())
        assertEquals(CachePolicy.PREFIX_KV_REUSE, nonStrict.resolveNativeCachePolicy())
    }

    @Test
    fun `select runnable model honors explicit qwen3 1_7b routing mode`() {
        val inference = LifecycleInferenceModule(
            availableModels = listOf(
                ModelCatalog.QWEN3_1_7B_Q4_K_M,
            ),
        )
        val coordinator = ModelLifecycleCoordinator(
            inferenceModule = inference,
            routingModule = LifecycleRoutingModule(selectedModel = ModelCatalog.QWEN3_0_6B_Q4_K_M),
            runtimeConfig = lifecycleRuntimeConfig(),
        )

        val selected = coordinator.selectRunnableModelId(
            routingMode = RoutingMode.QWEN3_1_7B,
            taskType = "short_text",
            deviceState = DEVICE_STATE,
        )

        assertEquals(ModelCatalog.QWEN3_1_7B_Q4_K_M, selected)
    }

    @Test
    fun `preferred model order falls back to llama when qwen unavailable`() {
        val inference = LifecycleInferenceModule(
            availableModels = listOf(
                ModelCatalog.LLAMA_3_2_1B_Q4_K_M,
            ),
        )
        val coordinator = ModelLifecycleCoordinator(
            inferenceModule = inference,
            routingModule = LifecycleRoutingModule(selectedModel = ModelCatalog.QWEN3_1_7B_Q4_K_M),
            runtimeConfig = lifecycleRuntimeConfig(),
        )

        val selected = coordinator.selectRunnableModelId(
            routingMode = RoutingMode.AUTO,
            taskType = "short_text",
            deviceState = DEVICE_STATE,
        )

        assertEquals(ModelCatalog.LLAMA_3_2_1B_Q4_K_M, selected)
    }

    @Test
    fun `explicit routing fallback emits warning when resolver has no binding`() {
        val warnings = mutableListOf<String>()
        val inference = LifecycleInferenceModule(
            availableModels = listOf(ModelCatalog.QWEN_3_5_0_8B_Q4),
        )
        val coordinator = ModelLifecycleCoordinator(
            inferenceModule = inference,
            routingModule = LifecycleRoutingModule(selectedModel = ModelCatalog.QWEN_3_5_0_8B_Q4),
            runtimeConfig = lifecycleRuntimeConfig(),
            explicitModelIdResolver = { null },
            onWarning = warnings::add,
        )

        val selected = coordinator.selectRunnableModelId(
            routingMode = RoutingMode.QWEN_0_8B,
            taskType = "short_text",
            deviceState = DEVICE_STATE,
        )

        assertEquals(ModelCatalog.QWEN_3_5_0_8B_Q4, selected)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("falling back to auto-routing"))
    }
}

private val DEVICE_STATE = DeviceState(batteryPercent = 80, thermalLevel = 2, ramClassGb = 8)

private class LifecycleInferenceModule(
    private val availableModels: List<String>,
) : InferenceModule {
    override fun listAvailableModels(): List<String> = availableModels

    override fun loadModel(modelId: String): Boolean = true

    override fun generateStream(request: InferenceRequest, onToken: (String) -> Unit) {
        onToken("ok")
    }

    override fun unloadModel() = Unit
}

private class LifecycleRoutingModule(
    private val selectedModel: String,
) : RoutingModule {
    override fun selectModel(taskType: String, deviceState: DeviceState): String = selectedModel

    override fun selectContextBudget(taskType: String, deviceState: DeviceState): Int = 512
}

private fun lifecycleRuntimeConfig(
    prefixCacheEnabled: Boolean = true,
    prefixCacheStrict: Boolean = false,
): RuntimeConfig {
    val sha0 = "a".repeat(64)
    val sha2 = "b".repeat(64)
    return RuntimeConfig(
        artifactPayloadByModelId = mapOf(
                ModelCatalog.QWEN3_0_6B_Q4_K_M to "payload-0".encodeToByteArray(),
                ModelCatalog.QWEN3_1_7B_Q4_K_M to "payload-1".encodeToByteArray(),
        ),
        artifactFilePathByModelId = mapOf(
                ModelCatalog.QWEN3_0_6B_Q4_K_M to "",
                ModelCatalog.QWEN3_1_7B_Q4_K_M to "",
        ),
        artifactSha256ByModelId = mapOf(
                ModelCatalog.QWEN3_0_6B_Q4_K_M to sha0,
                ModelCatalog.QWEN3_1_7B_Q4_K_M to sha2,
        ),
        artifactProvenanceIssuerByModelId = mapOf(
                ModelCatalog.QWEN3_0_6B_Q4_K_M to "internal-release",
                ModelCatalog.QWEN3_1_7B_Q4_K_M to "internal-release",
        ),
        artifactProvenanceSignatureByModelId = mapOf(
                ModelCatalog.QWEN3_0_6B_Q4_K_M to "sig-0",
                ModelCatalog.QWEN3_1_7B_Q4_K_M to "sig-2",
        ),
        runtimeCompatibilityTag = "android-arm64-v8a",
        requireNativeRuntimeForStartupChecks = false,
        prefixCacheEnabled = prefixCacheEnabled,
        prefixCacheStrict = prefixCacheStrict,
        responseCacheTtlSec = 0L,
        responseCacheMaxEntries = 0,
        streamContractV2Enabled = true,
    )
}
