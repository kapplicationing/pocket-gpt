package com.pocketagent.runtime

import com.pocketagent.inference.InferenceModule
import com.pocketagent.inference.InferenceRequest
import com.pocketagent.nativebridge.CachePolicy
import com.pocketagent.nativebridge.RuntimeInferencePorts
import com.pocketagent.nativebridge.runtimeInferencePorts

internal class InferenceExecutor(
    private val inferenceModule: InferenceModule,
    private val runtimeConfig: RuntimeConfig,
    private val runtimeInferencePorts: RuntimeInferencePorts = inferenceModule.runtimeInferencePorts(),
    private val operationCoordinator: RuntimeOperationCoordinator = RuntimeOperationCoordinator(),
) {
    // Streaming accumulation, stop handling, metrics, and native error mapping form one state machine.
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "ComplexCondition")
    fun execute(
        sessionId: String,
        requestId: String,
        request: InferenceRequest,
        cacheKey: String?,
        cachePolicy: CachePolicy,
        stopSequences: List<String>,
        onToken: (String) -> Unit,
        generationLease: GenerationLease? = null,
    ): InferenceExecutionResult {
        val acquiredHere = generationLease == null
        val activeLease = generationLease ?: acquireGenerationLease(requestId = requestId, sessionId = sessionId)
        require(activeLease.requestId == requestId && activeLease.sessionId == sessionId) {
            "Generation lease owner does not match requestId=$requestId sessionId=$sessionId"
        }
        val nativeInference = runtimeInferencePorts.cacheAwareGeneration
        val streamedText = StringBuilder()
        var stoppedBySequence = false
        var finishReason = "completed"
        var bridgeErrorCode: String? = null
        var tokenCount = 0
        var firstTokenMs = -1L
        var totalMs = 0L
        var prefillMs: Long? = null
        var decodeMs: Long? = null
        var tokensPerSec: Double? = null
        var peakRssMb: Double? = null
        var kvCachePreset: String? = null
        val startedAtMs = System.currentTimeMillis()
        try {
            if (nativeInference != null) {
                val result = nativeInference.generateStreamWithCache(
                    requestId = requestId,
                    request = request,
                    cacheKey = cacheKey,
                    cachePolicy = cachePolicy,
                    onToken = { token ->
                        if (stoppedBySequence) return@generateStreamWithCache
                        streamedText.append(token)
                        if (streamedText.endsWithAny(stopSequences)) {
                            stoppedBySequence = true
                            nativeInference.cancelGeneration(requestId)
                            return@generateStreamWithCache
                        }
                        onToken(token)
                    },
                )
                tokenCount = result.tokenCount
                firstTokenMs = result.firstTokenMs
                totalMs = result.totalMs
                prefillMs = result.prefillMs
                decodeMs = result.decodeMs
                tokensPerSec = result.tokensPerSec
                peakRssMb = result.peakRssMb
                kvCachePreset = result.kvCachePreset?.name
                finishReason = result.finishReason.name.lowercase()
                bridgeErrorCode = result.errorCode
                if (!result.success) {
                    if (result.cancelled && stoppedBySequence) {
                        finishReason = "stop_sequence"
                    } else if (result.cancelled) {
                        throw RuntimeGenerationCancelledException(requestId = requestId)
                    } else {
                        throw RuntimeGenerationFailureException(
                            message = buildGenerationFailureMessage(
                                prefix = "llama.cpp runtime generation failed",
                                result = result,
                            ),
                            errorCode = result.errorCode,
                        )
                    }
                }
            } else {
                var ignoreFurtherTokens = false
                inferenceModule.generateStream(request) { token ->
                    if (ignoreFurtherTokens) {
                        return@generateStream
                    }
                    if (firstTokenMs < 0L) {
                        firstTokenMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
                    }
                    tokenCount += 1
                    streamedText.append(token)
                    if (streamedText.endsWithAny(stopSequences)) {
                        stoppedBySequence = true
                        ignoreFurtherTokens = true
                        finishReason = "stop_sequence"
                        return@generateStream
                    }
                    onToken(token)
                }
                totalMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
                prefillMs = if (firstTokenMs >= 0L) firstTokenMs else null
                decodeMs = if (firstTokenMs >= 0L) (totalMs - firstTokenMs).coerceAtLeast(0L) else null
                val decodeSnapshot = decodeMs
                tokensPerSec = if (tokenCount > 0 && decodeSnapshot != null && decodeSnapshot > 0L) {
                    tokenCount.toDouble() / (decodeSnapshot.toDouble() / 1000.0)
                } else {
                    null
                }
            }
            if (totalMs <= 0L) {
                totalMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
            }
            if (prefillMs == null && firstTokenMs >= 0L) {
                prefillMs = firstTokenMs
            }
            if (decodeMs == null && firstTokenMs >= 0L) {
                decodeMs = (totalMs - firstTokenMs).coerceAtLeast(0L)
            }
            val decodeSnapshot = decodeMs
            if (tokensPerSec == null && tokenCount > 0 && decodeSnapshot != null && decodeSnapshot > 0L) {
                tokensPerSec = tokenCount.toDouble() / (decodeSnapshot.toDouble() / 1000.0)
            }
            return InferenceExecutionResult(
                text = streamedText.toString().trimStopSequences(stopSequences),
                finishReason = resolvedFinishReason(finishReason, bridgeErrorCode),
                bridgeErrorCode = bridgeErrorCode,
                tokenCount = tokenCount,
                firstTokenMs = firstTokenMs,
                totalMs = totalMs,
                prefillMs = prefillMs,
                decodeMs = decodeMs,
                tokensPerSec = tokensPerSec,
                peakRssMb = peakRssMb,
                kvCachePreset = kvCachePreset,
            )
        } finally {
            if (acquiredHere) {
                activeLease.close()
            }
        }
    }

    // Multimodal streaming mirrors the text state machine and must preserve identical terminal semantics.
    @Suppress("CyclomaticComplexMethod")
    fun executeWithImages(
        sessionId: String,
        requestId: String,
        prompt: String,
        imagePaths: List<String>,
        maxTokens: Int,
        stopSequences: List<String>,
        onToken: (String) -> Unit,
        generationLease: GenerationLease? = null,
    ): InferenceExecutionResult {
        val managedRuntime = runtimeInferencePorts.managedRuntime
            ?: return InferenceExecutionResult(
                text = "",
                finishReason = "error:multimodal_not_available",
                bridgeErrorCode = "MULTIMODAL_NOT_AVAILABLE",
                tokenCount = 0,
                firstTokenMs = -1L,
                totalMs = 0L,
                prefillMs = null,
                decodeMs = null,
                tokensPerSec = null,
                peakRssMb = null,
            )
        val acquiredHere = generationLease == null
        val activeLease = generationLease ?: acquireGenerationLease(requestId = requestId, sessionId = sessionId)
        require(activeLease.requestId == requestId && activeLease.sessionId == sessionId) {
            "Generation lease owner does not match requestId=$requestId sessionId=$sessionId"
        }
        val streamedText = StringBuilder()
        var stoppedBySequence = false
        var finishReason = "completed"
        var bridgeErrorCode: String? = null
        var tokenCount = 0
        var firstTokenMs = -1L
        val startedAtMs = System.currentTimeMillis()
        try {
            val result = managedRuntime.generateWithImages(
                requestId = requestId,
                prompt = prompt,
                imagePaths = imagePaths,
                maxTokens = maxTokens,
                onToken = { token ->
                    if (stoppedBySequence) return@generateWithImages
                    streamedText.append(token)
                    if (streamedText.endsWithAny(stopSequences)) {
                        stoppedBySequence = true
                        runtimeInferencePorts.cacheAwareGeneration?.cancelGeneration(requestId)
                        return@generateWithImages
                    }
                    tokenCount++
                    if (firstTokenMs < 0L && token.isNotEmpty()) {
                        firstTokenMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
                    }
                    onToken(token)
                },
            )
            finishReason = result.finishReason.name.lowercase()
            bridgeErrorCode = result.errorCode
            if (!result.success) {
                if (result.cancelled && stoppedBySequence) {
                    finishReason = "stop_sequence"
                } else if (result.cancelled) {
                    throw RuntimeGenerationCancelledException(requestId = requestId)
                } else {
                    throw RuntimeGenerationFailureException(
                        message = buildGenerationFailureMessage(
                            prefix = "Multimodal generation failed",
                            result = result,
                        ),
                        errorCode = result.errorCode,
                    )
                }
            }
            val totalMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
            val prefillMs = if (firstTokenMs >= 0L) firstTokenMs else null
            val decodeMs = if (firstTokenMs >= 0L) (totalMs - firstTokenMs).coerceAtLeast(0L) else null
            val tokensPerSec = if (tokenCount > 0 && decodeMs != null && decodeMs > 0L) {
                tokenCount.toDouble() / (decodeMs.toDouble() / 1000.0)
            } else {
                null
            }
            return InferenceExecutionResult(
                text = streamedText.toString().let { raw ->
                    var value = raw
                    stopSequences.forEach { stop ->
                        if (stop.isNotEmpty() && value.endsWith(stop)) value = value.removeSuffix(stop)
                    }
                    value
                },
                finishReason = resolvedFinishReason(finishReason, bridgeErrorCode),
                bridgeErrorCode = bridgeErrorCode,
                tokenCount = tokenCount,
                firstTokenMs = firstTokenMs,
                totalMs = totalMs,
                prefillMs = prefillMs,
                decodeMs = decodeMs,
                tokensPerSec = tokensPerSec,
                peakRssMb = result.peakRssMb,
            )
        } finally {
            if (acquiredHere) {
                activeLease.close()
            }
        }
    }

    fun cancelByRequest(requestId: String): Boolean {
        return cancelByRequestDetailed(requestId).cancelled
    }

    fun cancelByRequestDetailed(requestId: String): CancellationResult {
        if (!runtimeConfig.streamContractV2Enabled) {
            return CancellationResult(
                cancelled = false,
                code = "STREAM_CONTRACT_V2_DISABLED",
                detail = "requestId=$requestId",
            )
        }
        return operationCoordinator.cancelByRequest(requestId, ::cancelNativeGeneration)
    }

    fun cancelBySession(sessionId: String): Boolean {
        return cancelBySessionDetailed(sessionId).cancelled
    }

    fun cancelBySessionDetailed(sessionId: String): CancellationResult {
        if (!runtimeConfig.streamContractV2Enabled) {
            return CancellationResult(
                cancelled = false,
                code = "STREAM_CONTRACT_V2_DISABLED",
                detail = "sessionId=$sessionId",
            )
        }
        return operationCoordinator.cancelBySession(sessionId, ::cancelNativeGeneration)
    }

    fun isIdle(): Boolean = operationCoordinator.isGenerationIdle()

    fun acquireLifecycleLease(timeoutMs: Long = 5_000L): LifecycleAdmissionResult {
        return operationCoordinator.acquireLifecycle(timeoutMs, ::cancelNativeGeneration)
    }

    fun acquireGenerationLease(requestId: String, sessionId: String): GenerationLease {
        return when (val admission = operationCoordinator.tryAcquireGeneration(requestId, sessionId)) {
            is GenerationAdmissionResult.Acquired -> admission.lease
            is GenerationAdmissionResult.Rejected -> throw RuntimeGenerationFailureException(
                message = "Runtime generation rejected: ${admission.detail.orEmpty()}",
                errorCode = admission.code,
            )
        }
    }

    private fun cancelNativeGeneration(requestId: String): CancellationResult {
        val native = runtimeInferencePorts.cacheAwareGeneration
            ?: return CancellationResult(
                cancelled = false,
                code = "RUNTIME_NOT_NATIVE_BRIDGE",
                detail = "requestId=$requestId",
            )
        val managedRuntime = runtimeInferencePorts.managedRuntime
            ?: return CancellationResult(
                cancelled = false,
                code = "RUNTIME_NOT_NATIVE_BRIDGE",
                detail = "requestId=$requestId",
            )
        if (native.cancelGeneration(requestId)) {
            return CancellationResult(cancelled = true, code = "CANCELLED")
        }
        val bridgeError = managedRuntime.lastBridgeError()
        return CancellationResult(
            cancelled = false,
            code = bridgeError?.code ?: "CANCEL_REJECTED",
            detail = bridgeError?.detail ?: "requestId=$requestId",
        )
    }

    fun cancelNativeGenerationForLifetime(requestId: String): CancellationResult {
        return cancelNativeGeneration(requestId)
    }

    private fun resolvedFinishReason(finishReason: String, bridgeErrorCode: String?): String {
        return if (bridgeErrorCode.isNullOrBlank()) {
            finishReason
        } else {
            "$finishReason:${bridgeErrorCode.lowercase()}"
        }
    }

    private fun buildGenerationFailureMessage(
        prefix: String,
        result: com.pocketagent.nativebridge.GenerationResult,
    ): String {
        return "$prefix: finish=${result.finishReason} code=${result.errorCode.orEmpty()}"
    }
}

data class InferenceExecutionResult(
    val text: String,
    val finishReason: String,
    val bridgeErrorCode: String?,
    val tokenCount: Int,
    val firstTokenMs: Long,
    val totalMs: Long,
    val prefillMs: Long?,
    val decodeMs: Long?,
    val tokensPerSec: Double?,
    val peakRssMb: Double?,
    val kvCachePreset: String? = null,
)

data class CancellationResult(
    val cancelled: Boolean,
    val code: String,
    val detail: String? = null,
)

private fun String.trimStopSequences(stopSequences: List<String>): String {
    var value = this
    stopSequences.forEach { stop ->
        if (stop.isNotEmpty() && value.endsWith(stop)) {
            value = value.removeSuffix(stop)
        }
    }
    return value
}

private fun StringBuilder.endsWithAny(suffixes: List<String>): Boolean {
    return suffixes.any { suffix ->
        if (suffix.isEmpty() || suffix.length > length) {
            false
        } else {
            suffix.indices.all { suffixIndex ->
                this[length - suffix.length + suffixIndex] == suffix[suffixIndex]
            }
        }
    }
}
