package com.pocketagent.runtime

import com.pocketagent.core.ObservabilityModule
import com.pocketagent.core.PolicyModule
import com.pocketagent.core.RoutingMode
import com.pocketagent.inference.DeviceState
import com.pocketagent.inference.ImageInputResult
import com.pocketagent.inference.ImageInputModule
import com.pocketagent.inference.InferenceModule
import java.util.UUID

internal class ImageAnalyzeUseCase(
    private val policyModule: PolicyModule,
    private val inferenceModule: InferenceModule,
    private val artifactVerifier: ArtifactVerifier,
    private val imageInputModule: ImageInputModule,
    private val observabilityModule: ObservabilityModule,
    private val modelLifecycleCoordinator: ModelLifecycleCoordinator,
    private val routingModeProvider: () -> RoutingMode,
    private val residentModelIdProvider: () -> String? = { null },
    private val availableCpuCoresProvider: () -> Int = { Runtime.getRuntime().availableProcessors() },
    private val operationCoordinator: RuntimeOperationCoordinator = RuntimeOperationCoordinator(),
    private val clearResidentModel: (String) -> Unit = {},
) {
    fun execute(
        imagePath: String,
        prompt: String,
        deviceState: DeviceState,
    ): ImageAnalysisResult {
        val availableCpuCores = availableCpuCoresProvider().coerceAtLeast(1)
        if (!isMultimodalCapable(deviceState)) {
            return ImageAnalysisResult.Failure(
                failure = ImageFailure.Runtime(
                    code = "device_insufficient",
                    userMessage = "This device doesn't have enough resources for image analysis.",
                    technicalDetail = "multimodal_gate: ram=${deviceState.ramClassGb}GB cores=$availableCpuCores",
                ),
            )
        }
        if (!policyModule.enforceDataBoundary("routing.image_model_select")) {
            return ImageAnalysisResult.Failure(
                failure = ImageFailure.PolicyDenied(
                    technicalDetail = "Policy module rejected image routing event type.",
                ),
            )
        }
        val requestId = "image-${UUID.randomUUID()}"
        val generationLease = when (
            val admission = operationCoordinator.tryAcquireGeneration(
                requestId = requestId,
                sessionId = "image",
            )
        ) {
            is GenerationAdmissionResult.Acquired -> admission.lease
            is GenerationAdmissionResult.Rejected -> return ImageAnalysisResult.Failure(
                failure = ImageFailure.Runtime(
                    code = admission.code.lowercase(),
                    userMessage = "The runtime is busy with another request.",
                    technicalDetail = admission.detail,
                ),
            )
        }
        return try {
            executeAdmitted(imagePath = imagePath, prompt = prompt, deviceState = deviceState)
        } finally {
            generationLease.close()
        }
    }

    private fun executeAdmitted(
        imagePath: String,
        prompt: String,
        deviceState: DeviceState,
    ): ImageAnalysisResult {
        val startedMs = System.currentTimeMillis()
        val residentModelBefore = residentModelIdProvider()
        var imageModelLoadAttempted = false
        var imageModelLoaded = false
        var imageModelId: String? = null
        val output = runCatching {
            val modelId = modelLifecycleCoordinator.selectRunnableModelId(
                routingMode = routingModeProvider(),
                taskType = "image",
                deviceState = deviceState,
            )
            imageModelId = modelId
            check(artifactVerifier.manager().setActiveModel(modelId)) {
                "Model artifact not registered for image runtime model: $modelId"
            }
            artifactVerifier.verifyArtifactOrThrow(modelId)
            imageModelLoadAttempted = true
            imageModelLoaded = inferenceModule.loadModel(modelId)
            check(imageModelLoaded) {
                "Failed to load runtime model for image analysis: $modelId"
            }
            if (!policyModule.enforceDataBoundary("inference.image_analyze")) {
                return@runCatching ImageAnalysisResult.Failure(
                    failure = ImageFailure.PolicyDenied(
                        technicalDetail = "Policy module rejected image analysis event type.",
                    ),
                )
            }

            when (
                val imageResult = imageInputModule.analyzeImageResult(
                    com.pocketagent.inference.ImageRequest(
                        imagePath = imagePath,
                        prompt = prompt,
                        maxTokens = 128,
                    ),
                )
            ) {
                is ImageInputResult.Success -> ImageAnalysisResult.Success(imageResult.content)
                is ImageInputResult.ValidationFailure -> ImageAnalysisResult.Failure(
                    failure = ImageFailure.Validation(
                        code = imageResult.code.lowercase(),
                        userMessage = "That image could not be processed.",
                        technicalDetail = imageResult.detail,
                    ),
                )
                is ImageInputResult.RuntimeFailure -> ImageAnalysisResult.Failure(
                    failure = ImageFailure.Runtime(
                        code = imageResult.code.lowercase(),
                        userMessage = "Image analysis failed.",
                        technicalDetail = imageResult.detail,
                    ),
                )
            }
        }.getOrElse { error ->
            ImageAnalysisResult.Failure(
                failure = ImageFailure.Runtime(
                    code = "image_runtime_error",
                    userMessage = "Image analysis failed.",
                    technicalDetail = error.message ?: "Unknown image runtime error",
                ),
            )
        }
        // Only unload if the image model wasn't the user's resident chat model.
        // Unconditional unload would nuke the user's chat model when they share
        // the same inference engine.
        val wasResidentBefore = residentModelBefore != null && residentModelBefore == imageModelId
        if (imageModelLoadAttempted && !wasResidentBefore) {
            inferenceModule.unloadModel()
            if (residentModelBefore != null && !inferenceModule.loadModel(residentModelBefore)) {
                clearResidentModel("image_restore_failed")
                return ImageAnalysisResult.Failure(
                    failure = ImageFailure.Runtime(
                        code = "resident_restore_failed",
                        userMessage = "Image analysis finished, but the chat model could not be restored.",
                        technicalDetail = "modelId=$residentModelBefore",
                    ),
                )
            }
        }

        val totalLatency = System.currentTimeMillis() - startedMs
        requirePolicyEvent(
            eventType = "observability.record_runtime_metrics",
            failureMessage = "Policy module rejected diagnostics metric event type.",
        )
        observabilityModule.recordLatencyMetric("inference.image.total_ms", totalLatency.toDouble())
        observabilityModule.recordThermalSnapshot(deviceState.thermalLevel)
        return output
    }

    private fun requirePolicyEvent(eventType: String, failureMessage: String) {
        check(policyModule.enforceDataBoundary(eventType)) { failureMessage }
    }

    private fun isMultimodalCapable(deviceState: DeviceState): Boolean {
        val minRamGb = MIN_MULTIMODAL_RAM_GB
        val minCores = MIN_MULTIMODAL_CORES
        return deviceState.ramClassGb >= minRamGb &&
            availableCpuCoresProvider().coerceAtLeast(1) >= minCores
    }

    companion object {
        const val MIN_MULTIMODAL_RAM_GB = 6
        const val MIN_MULTIMODAL_CORES = 6
    }
}
