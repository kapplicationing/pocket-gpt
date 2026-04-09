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
            availableModels = listOf(ModelCatalog.QWEN_3_5_0_8B_Q4, ModelCatalog.QWEN_3_5_2B_Q4),
        )
        val coordinator = ModelLifecycleCoordinator(
            inferenceModule = inference,
            routingModule = LifecycleRoutingModule(selectedModel = ModelCatalog.QWEN_3_5_2B_Q4),
            runtimeConfig = lifecycleRuntimeConfig(),
        )

        val selected = coordinator.selectRunnableModelId(
            routingMode = RoutingMode.AUTO,
            taskType = "short_text",
            deviceState = DEVICE_STATE,
        )

        assertEquals(ModelCatalog.QWEN_3_5_2B_Q4, selected)
    }

    @Test
    fun `select runnable model falls back to available preferred order`() {
        val inference = LifecycleInferenceModule(
            availableModels = listOf(ModelCatalog.QWEN_3_5_2B_Q4),
        )
        val coordinator = ModelLifecycleCoordinator(
            inferenceModule = inference,
            routingModule = LifecycleRoutingModule(selectedModel = ModelCatalog.QWEN_3_5_0_8B_Q4),
            runtimeConfig = lifecycleRuntimeConfig(),
        )

        val selected = coordinator.selectRunnableModelId(
            routingMode = RoutingMode.AUTO,
            taskType = "short_text",
            deviceState = DEVICE_STATE,
        )

        assertEquals(ModelCatalog.QWEN_3_5_2B_Q4, selected)
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
    fun `select runnable model honors explicit smollm3 routing mode`() {
        val inference = LifecycleInferenceModule(
            availableModels = listOf(
                ModelCatalog.SMOLLM3_3B_Q4_K_M,
            ),
        )
        val coordinator = ModelLifecycleCoordinator(
            inferenceModule = inference,
            routingModule = LifecycleRoutingModule(selectedModel = ModelCatalog.QWEN_3_5_0_8B_Q4),
            runtimeConfig = lifecycleRuntimeConfig(),
        )

        val selected = coordinator.selectRunnableModelId(
            routingMode = RoutingMode.SMOLLM3_3B,
            taskType = "short_text",
            deviceState = DEVICE_STATE,
        )

        assertEquals(ModelCatalog.SMOLLM3_3B_Q4_K_M, selected)
    }

    @Test
    fun `preferred model order falls back to smollm3 when qwen unavailable`() {
        val inference = LifecycleInferenceModule(
            availableModels = listOf(
                ModelCatalog.SMOLLM3_3B_Q4_K_M,
            ),
        )
        val coordinator = ModelLifecycleCoordinator(
            inferenceModule = inference,
            routingModule = LifecycleRoutingModule(selectedModel = ModelCatalog.QWEN_3_5_2B_Q4),
            runtimeConfig = lifecycleRuntimeConfig(),
        )

        val selected = coordinator.selectRunnableModelId(
            routingMode = RoutingMode.AUTO,
            taskType = "short_text",
            deviceState = DEVICE_STATE,
        )

        assertEquals(ModelCatalog.SMOLLM3_3B_Q4_K_M, selected)
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
            ModelCatalog.QWEN_3_5_0_8B_Q4 to "payload-0".encodeToByteArray(),
            ModelCatalog.QWEN_3_5_2B_Q4 to "payload-2".encodeToByteArray(),
        ),
        artifactFilePathByModelId = mapOf(
            ModelCatalog.QWEN_3_5_0_8B_Q4 to "",
            ModelCatalog.QWEN_3_5_2B_Q4 to "",
        ),
        artifactSha256ByModelId = mapOf(
            ModelCatalog.QWEN_3_5_0_8B_Q4 to sha0,
            ModelCatalog.QWEN_3_5_2B_Q4 to sha2,
        ),
        artifactProvenanceIssuerByModelId = mapOf(
            ModelCatalog.QWEN_3_5_0_8B_Q4 to "internal-release",
            ModelCatalog.QWEN_3_5_2B_Q4 to "internal-release",
        ),
        artifactProvenanceSignatureByModelId = mapOf(
            ModelCatalog.QWEN_3_5_0_8B_Q4 to "sig-0",
            ModelCatalog.QWEN_3_5_2B_Q4 to "sig-2",
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
