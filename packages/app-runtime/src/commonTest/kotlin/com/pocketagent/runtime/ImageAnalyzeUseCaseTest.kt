package com.pocketagent.runtime

import com.pocketagent.core.ObservabilityModule
import com.pocketagent.core.PolicyModule
import com.pocketagent.core.RoutingMode
import com.pocketagent.inference.DeviceState
import com.pocketagent.inference.ImageInputModule
import com.pocketagent.inference.ImageInputResult
import com.pocketagent.inference.ImageRequest
import com.pocketagent.inference.InferenceModule
import com.pocketagent.inference.InferenceRequest
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.inference.RoutingModule
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageAnalyzeUseCaseTest {
    @Test
    fun `returns policy denied failure before model selection when routing event is blocked`() {
        val inference = ImageRecordingInferenceModule()
        val useCase = buildUseCase(
            inference = inference,
            policy = ImageEventPolicyModule(allowedEvents = setOf("observability.record_runtime_metrics")),
            imageInput = StaticImageInputModule(ImageInputResult.Success("ok")),
        )

        val result = useCase.execute(
            imagePath = "/tmp/img.jpg",
            prompt = "describe",
            deviceState = DEVICE_STATE,
        )

        assertTrue(result is ImageAnalysisResult.Failure)
        assertTrue((result as ImageAnalysisResult.Failure).failure is ImageFailure.PolicyDenied)
        assertEquals(0, inference.loadCalls)
        assertEquals(0, inference.unloadCalls)
    }

    @Test
    fun `maps image validation failure to typed validation contract`() {
        val useCase = buildUseCase(
            inference = ImageRecordingInferenceModule(),
            policy = ImageEventPolicyModule(
                allowedEvents = setOf(
                    "routing.image_model_select",
                    "inference.image_analyze",
                    "observability.record_runtime_metrics",
                ),
            ),
            imageInput = StaticImageInputModule(
                ImageInputResult.ValidationFailure(
                    code = "UNSUPPORTED_EXTENSION",
                    detail = "extension 'tiff' is not supported",
                ),
            ),
        )

        val result = useCase.execute("/tmp/img.tiff", "describe", DEVICE_STATE)

        assertTrue(result is ImageAnalysisResult.Failure)
        val failure = (result as ImageAnalysisResult.Failure).failure
        assertTrue(failure is ImageFailure.Validation)
        assertEquals("unsupported_extension", failure.code)
        assertEquals("extension 'tiff' is not supported", failure.technicalDetail)
    }

    @Test
    fun `success path loads and unloads model and records observability`() {
        val inference = ImageRecordingInferenceModule()
        val observability = RecordingObservabilityModule()
        val useCase = buildUseCase(
            inference = inference,
            policy = ImageEventPolicyModule(
                allowedEvents = setOf(
                    "routing.image_model_select",
                    "inference.image_analyze",
                    "observability.record_runtime_metrics",
                ),
            ),
            imageInput = StaticImageInputModule(ImageInputResult.Success("image summary")),
            observability = observability,
        )

        val result = useCase.execute("/tmp/img.jpg", "describe", DEVICE_STATE)

        assertTrue(result is ImageAnalysisResult.Success)
        assertEquals("image summary", (result as ImageAnalysisResult.Success).content)
        assertEquals(1, inference.loadCalls)
        assertEquals(1, inference.unloadCalls)
        assertTrue(observability.metrics.any { it.first == "inference.image.total_ms" })
    }

    @Test
    fun `skips unload when image model matches resident chat model`() {
        val inference = ImageRecordingInferenceModule()
        val useCase = buildUseCase(
            inference = inference,
            policy = ALL_EVENTS_POLICY,
            imageInput = StaticImageInputModule(ImageInputResult.Success("ok")),
            residentModelIdProvider = { ModelCatalog.QWEN_3_5_0_8B_Q4 },
        )

        val result = useCase.execute("/tmp/img.jpg", "describe", DEVICE_STATE)

        assertTrue(result is ImageAnalysisResult.Success)
        assertEquals(1, inference.loadCalls)
        assertEquals(0, inference.unloadCalls)
    }

    @Test
    fun `unloads when image model differs from resident chat model`() {
        val inference = ImageRecordingInferenceModule()
        val useCase = buildUseCase(
            inference = inference,
            policy = ALL_EVENTS_POLICY,
            imageInput = StaticImageInputModule(ImageInputResult.Success("ok")),
            residentModelIdProvider = { ModelCatalog.QWEN3_1_7B_Q4_K_M },
        )

        val result = useCase.execute("/tmp/img.jpg", "describe", DEVICE_STATE)

        assertTrue(result is ImageAnalysisResult.Success)
        assertEquals(1, inference.loadCalls)
        assertEquals(1, inference.unloadCalls)
    }

    @Test
    fun `unloads when no resident model is set`() {
        val inference = ImageRecordingInferenceModule()
        val useCase = buildUseCase(
            inference = inference,
            policy = ALL_EVENTS_POLICY,
            imageInput = StaticImageInputModule(ImageInputResult.Success("ok")),
            residentModelIdProvider = { null },
        )

        val result = useCase.execute("/tmp/img.jpg", "describe", DEVICE_STATE)

        assertTrue(result is ImageAnalysisResult.Success)
        assertEquals(1, inference.loadCalls)
        assertEquals(1, inference.unloadCalls)
    }

    @Test
    fun `returns device insufficient when cpu floor is not met`() {
        val useCase = buildUseCase(
            inference = ImageRecordingInferenceModule(),
            policy = ALL_EVENTS_POLICY,
            imageInput = StaticImageInputModule(ImageInputResult.Success("ok")),
            availableCpuCoresProvider = { 4 },
        )

        val result = useCase.execute("/tmp/img.jpg", "describe", DEVICE_STATE)

        assertTrue(result is ImageAnalysisResult.Failure)
        val failure = (result as ImageAnalysisResult.Failure).failure
        assertTrue(failure is ImageFailure.Runtime)
        assertEquals("device_insufficient", failure.code)
    }
}

private val DEVICE_STATE = DeviceState(batteryPercent = 85, thermalLevel = 3, ramClassGb = 8)

private val ALL_EVENTS_POLICY = ImageEventPolicyModule(
    allowedEvents = setOf(
        "routing.image_model_select",
        "inference.image_analyze",
        "observability.record_runtime_metrics",
    ),
)

    private fun buildUseCase(
    inference: ImageRecordingInferenceModule,
    policy: ImageEventPolicyModule,
    imageInput: ImageInputModule,
    observability: RecordingObservabilityModule = RecordingObservabilityModule(),
    residentModelIdProvider: () -> String? = { null },
    availableCpuCoresProvider: () -> Int = { 8 },
): ImageAnalyzeUseCase {
    val runtimeConfig = imageRuntimeConfig()
    val modelLifecycleCoordinator = ModelLifecycleCoordinator(
        inferenceModule = inference,
        routingModule = ImageStaticRoutingModule(),
        runtimeConfig = runtimeConfig,
    )
    return ImageAnalyzeUseCase(
        policyModule = policy,
        inferenceModule = inference,
        artifactVerifier = ArtifactVerifier(runtimeConfig),
        imageInputModule = imageInput,
        observabilityModule = observability,
        modelLifecycleCoordinator = modelLifecycleCoordinator,
        routingModeProvider = { RoutingMode.AUTO },
        residentModelIdProvider = residentModelIdProvider,
        availableCpuCoresProvider = availableCpuCoresProvider,
    )
}

private class StaticImageInputModule(
    private val result: ImageInputResult,
) : ImageInputModule {
    override fun analyzeImage(request: ImageRequest): String = "legacy"

    override fun analyzeImageResult(request: ImageRequest): ImageInputResult = result
}

private class ImageRecordingInferenceModule : InferenceModule {
    var loadCalls: Int = 0
    var unloadCalls: Int = 0

    override fun listAvailableModels(): List<String> {
        return listOf(ModelCatalog.QWEN_3_5_0_8B_Q4, ModelCatalog.QWEN3_1_7B_Q4_K_M)
    }

    override fun loadModel(modelId: String): Boolean {
        loadCalls += 1
        return true
    }

    override fun generateStream(request: InferenceRequest, onToken: (String) -> Unit) {
        onToken("unused")
    }

    override fun unloadModel() {
        unloadCalls += 1
    }
}

private class RecordingObservabilityModule : ObservabilityModule {
    val metrics = mutableListOf<Pair<String, Double>>()
    var thermalSnapshots = mutableListOf<Int>()

    override fun recordLatencyMetric(name: String, valueMs: Double) {
        metrics += name to valueMs
    }

    override fun recordThermalSnapshot(level: Int) {
        thermalSnapshots += level
    }

    override fun exportLocalDiagnostics(): String = "diag"
}

private class ImageEventPolicyModule(
    private val allowedEvents: Set<String>,
) : PolicyModule {
    override fun isNetworkAllowedForAction(action: String): Boolean = false

    override fun getRetentionWindowDays(): Int = 30

    override fun enforceDataBoundary(eventType: String): Boolean = allowedEvents.contains(eventType)
}

private class ImageStaticRoutingModule : RoutingModule {
    override fun selectModel(taskType: String, deviceState: DeviceState): String = ModelCatalog.QWEN_3_5_0_8B_Q4

    override fun selectContextBudget(taskType: String, deviceState: DeviceState): Int = 512
}

private fun imageRuntimeConfig(): RuntimeConfig {
    val payload0 = "payload-0".encodeToByteArray()
    val payload2 = "payload-2".encodeToByteArray()
    return RuntimeConfig(
        artifactPayloadByModelId = mapOf(
            ModelCatalog.QWEN_3_5_0_8B_Q4 to payload0,
            ModelCatalog.QWEN3_1_7B_Q4_K_M to payload2,
        ),
        artifactFilePathByModelId = mapOf(
            ModelCatalog.QWEN_3_5_0_8B_Q4 to "",
            ModelCatalog.QWEN3_1_7B_Q4_K_M to "",
        ),
        artifactSha256ByModelId = mapOf(
            ModelCatalog.QWEN_3_5_0_8B_Q4 to imageSha256(payload0),
            ModelCatalog.QWEN3_1_7B_Q4_K_M to imageSha256(payload2),
        ),
        artifactProvenanceIssuerByModelId = mapOf(
            ModelCatalog.QWEN_3_5_0_8B_Q4 to "internal-release",
            ModelCatalog.QWEN3_1_7B_Q4_K_M to "internal-release",
        ),
        artifactProvenanceSignatureByModelId = mapOf(
            ModelCatalog.QWEN_3_5_0_8B_Q4 to "sig-0",
            ModelCatalog.QWEN3_1_7B_Q4_K_M to "sig-2",
        ),
        runtimeCompatibilityTag = "android-arm64-v8a",
        requireNativeRuntimeForStartupChecks = false,
        prefixCacheEnabled = true,
        prefixCacheStrict = false,
        responseCacheTtlSec = 0L,
        responseCacheMaxEntries = 0,
        streamContractV2Enabled = true,
    )
}

private fun imageSha256(payload: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(payload)
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
