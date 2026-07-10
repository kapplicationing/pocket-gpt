package com.pocketagent.runtime

import com.pocketagent.inference.InferenceModule
import com.pocketagent.inference.InferenceRequest
import com.pocketagent.nativebridge.CachePolicy
import com.pocketagent.nativebridge.RuntimeInferencePorts
import com.pocketagent.nativebridge.runtimeInferencePorts
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class InferenceExecutor(
    private val inferenceModule: InferenceModule,
    private val runtimeConfig: RuntimeConfig,
    private val runtimeInferencePorts: RuntimeInferencePorts = inferenceModule.runtimeInferencePorts(),
) {
    private val activeByRequestId = ConcurrentHashMap<String, ActiveGenerationState>()
    private val activeBySessionId = ConcurrentHashMap<String, ActiveGenerationState>()
    private val idleLatch = AtomicReference<CountDownLatch?>(null)

    fun execute(
        sessionId: String,
        requestId: String,
        request: InferenceRequest,
        cacheKey: String?,
        cachePolicy: CachePolicy,
        stopSequences: List<String>,
        onToken: (String) -> Unit,
    ): InferenceExecutionResult {
        val state = ActiveGenerationState(requestId = requestId, sessionId = sessionId)
        activeByRequestId[requestId] = state
        activeBySessionId[sessionId] = state
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
                        streamedText.append(token)
                        val projected = streamedText.toString()
                        if (stopSequences.any { projected.endsWith(it) }) {
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
                            message = "llama.cpp runtime generation failed: finish=${result.finishReason} code=${result.errorCode.orEmpty()}",
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
                    val projected = streamedText.toString()
                    if (stopSequences.any { projected.endsWith(it) }) {
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
                finishReason = if (bridgeErrorCode.isNullOrBlank()) finishReason else "$finishReason:${bridgeErrorCode.lowercase()}",
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
            activeByRequestId.remove(requestId)
            activeBySessionId.remove(sessionId)
            if (activeByRequestId.isEmpty()) {
                idleLatch.getAndSet(null)?.countDown()
            }
        }
    }

    fun executeWithImages(
        sessionId: String,
        requestId: String,
        prompt: String,
        imagePaths: List<String>,
        maxTokens: Int,
        stopSequences: List<String>,
        onToken: (String) -> Unit,
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
        val state = ActiveGenerationState(requestId = requestId, sessionId = sessionId)
        activeByRequestId[requestId] = state
        activeBySessionId[sessionId] = state
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
                    val maxStopLen = stopSequences.maxOfOrNull { it.length } ?: 0
                    if (maxStopLen > 0) {
                        val tail = streamedText.substring(
                            (streamedText.length - maxStopLen).coerceAtLeast(0),
                        )
                        if (stopSequences.any { tail.endsWith(it) }) {
                            stoppedBySequence = true
                            runtimeInferencePorts.cacheAwareGeneration?.cancelGeneration(requestId)
                            return@generateWithImages
                        }
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
                        message = "Multimodal generation failed: finish=${result.finishReason} code=${result.errorCode.orEmpty()}",
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
                finishReason = if (bridgeErrorCode.isNullOrBlank()) finishReason else "$finishReason:${bridgeErrorCode.lowercase()}",
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
            activeByRequestId.remove(requestId)
            activeBySessionId.remove(sessionId)
            if (activeByRequestId.isEmpty()) {
                idleLatch.getAndSet(null)?.countDown()
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
        val active = activeByRequestId[requestId]
            ?: return CancellationResult(
                cancelled = false,
                code = "REQUEST_NOT_ACTIVE",
                detail = "requestId=$requestId",
            )
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
        val cancelled = native.cancelGeneration(active.requestId)
        if (cancelled) {
            return CancellationResult(cancelled = true, code = "CANCELLED")
        }
        val bridgeError = managedRuntime.lastBridgeError()
        return CancellationResult(
            cancelled = false,
            code = bridgeError?.code ?: "CANCEL_REJECTED",
            detail = bridgeError?.detail ?: "requestId=${active.requestId}",
        )
    }

    fun cancelBySession(sessionId: String): Boolean {
        return cancelBySessionDetailed(sessionId).cancelled
    }

    fun cancelBySessionDetailed(sessionId: String): CancellationResult {
        val active = activeBySessionId[sessionId]
            ?: return CancellationResult(
                cancelled = false,
                code = "SESSION_NOT_ACTIVE",
                detail = "sessionId=$sessionId",
            )
        return cancelByRequestDetailed(active.requestId)
    }

    fun isIdle(): Boolean = activeByRequestId.isEmpty()

    fun cancelAllAndAwaitIdle(timeoutMs: Long = 5_000L): Boolean {
        if (activeByRequestId.isEmpty()) {
            return true
        }
        val latch = CountDownLatch(1)
        idleLatch.set(latch)
        if (activeByRequestId.isEmpty()) {
            idleLatch.set(null)
            return true
        }
        val native = runtimeInferencePorts.cacheAwareGeneration
        for (requestId in activeByRequestId.keys.toList()) {
            native?.cancelGeneration(requestId) ?: break
        }
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
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

private data class ActiveGenerationState(
    val requestId: String,
    val sessionId: String,
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
