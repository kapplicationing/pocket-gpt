package com.pocketagent.runtime

import com.pocketagent.core.ChatResponse
import com.pocketagent.core.ChatToolCall
import com.pocketagent.core.ConversationModule
import com.pocketagent.core.ObservabilityModule
import com.pocketagent.core.PolicyModule
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.RuntimeExecutionStats
import com.pocketagent.core.SessionId
import com.pocketagent.inference.InferenceModule
import com.pocketagent.inference.InferenceRequest
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.inference.RoutingModule
import com.pocketagent.memory.MemoryChunk
import com.pocketagent.memory.MemoryModule
import com.pocketagent.nativebridge.RuntimeInferencePorts
import com.pocketagent.nativebridge.runtimeInferencePorts

internal class SendMessageUseCase(
    private val conversationModule: ConversationModule,
    private val routingModule: RoutingModule,
    private val policyModule: PolicyModule,
    private val observabilityModule: ObservabilityModule,
    private val memoryModule: MemoryModule,
    private val inferenceModule: InferenceModule,
    private val runtimeConfig: RuntimeConfig,
    private val artifactVerifier: ArtifactVerifier,
    private val interactionPlanner: InteractionPlanner,
    private val inferenceExecutor: InferenceExecutor,
    private val modelLifecycleCoordinator: ModelLifecycleCoordinator,
    private val runtimePlanResolver: RuntimePlanResolver,
    private val runtimeResidencyManager: RuntimeResidencyManager,
    private val cancelByRequest: (String) -> Boolean,
    private val cancelBySession: (SessionId) -> Boolean,
    private val runtimeInferencePorts: RuntimeInferencePorts = inferenceModule.runtimeInferencePorts(),
    private val generationTimeoutGuardFactory: (timeoutMs: Long, onTimeout: () -> Unit) -> GenerationTimeoutGuardHandle = { timeoutMs, onTimeout ->
        GenerationTimeoutGuard(timeoutMs = timeoutMs, onTimeout = onTimeout)
    },
) {
    private val sessionMemorySnippetsBySessionId: MutableMap<String, List<String>> = mutableMapOf()

    data class Request(
        val sessionId: SessionId,
        val userText: String,
        val messages: List<InteractionMessage> = emptyList(),
        val taskType: String,
        val executionContext: RuntimeRequestContext,
        val onToken: (String) -> Unit,
        val onThinkingStateChanged: (Boolean) -> Unit = {},
        val routingMode: RoutingMode,
    )

    fun execute(request: Request): ChatResponse {
        val executionContext = request.executionContext
        val latestUserText = request.messages.latestUserMessageText().ifBlank { request.userText }
        requirePolicyEvent(
            eventType = "routing.model_select",
            failureMessage = "Policy module rejected routing event type.",
        )
        val modelId = modelLifecycleCoordinator.selectRunnableModelId(
            routingMode = request.routingMode,
            taskType = request.taskType,
            deviceState = executionContext.deviceState,
        )
        check(artifactVerifier.manager().setActiveModel(modelId)) {
            "Model artifact not registered for runtime model: $modelId"
        }
        val modelVersion = artifactVerifier.manager().getActiveModelVersion()
        artifactVerifier.verifyArtifactOrThrow(modelId)
        interactionPlanner.ensureTemplateAvailable(modelId)?.let { message ->
            throw RuntimeTemplateUnavailableException(message)
        }

        val userImagePaths = request.messages.extractLatestUserImagePaths()
        val multimodalEnabled = runtimeInferencePorts.managedRuntime?.isMultimodalEnabled() == true
        val multimodalRequest = userImagePaths.isNotEmpty()
        if (multimodalRequest) {
            val supportsImages = ModelCatalog.isVisionCapable(modelId)
            if (!supportsImages || !multimodalEnabled) {
                throw RuntimeImageAttachmentUnsupportedException(
                    message = buildString {
                        append("Selected model cannot process image attachments.")
                        if (!supportsImages) {
                            append(" Choose a vision-capable model.")
                        } else {
                            append(" Load a runtime with multimodal support.")
                        }
                    },
                )
            }
        }
        conversationModule.appendUserTurn(request.sessionId, latestUserText)
        val transcriptMessages = if (request.messages.isNotEmpty()) {
            request.messages
        } else {
            conversationModule.listTurns(request.sessionId).map { turn -> turn.toInteractionMessage() }
        }
        val memorySnippets = resolveMemorySnippets(
            sessionId = request.sessionId,
            latestUserText = latestUserText,
            transcriptMessages = transcriptMessages,
        )
        requirePolicyEvent(
            eventType = "memory.write_user_turn",
            failureMessage = "Policy module rejected memory write event type.",
        )
        memoryModule.saveMemoryChunk(
            MemoryChunk(
                id = "mem-${System.currentTimeMillis()}",
                content = latestUserText,
                createdAtEpochMs = System.currentTimeMillis(),
            ),
        )

        val contextBudget = routingModule.selectContextBudget(request.taskType, executionContext.deviceState)
        val promptCharBudget = when (request.taskType) {
            "long_text" -> MAX_PROMPT_CHARS_LONG
            else -> MAX_PROMPT_CHARS_SHORT
        }
        val showThinking = executionContext.samplingOverrides?.showThinking ?: false
        val renderedPrompt = interactionPlanner.buildRenderedPrompt(
            modelId = modelId,
            messages = transcriptMessages,
            memorySnippets = memorySnippets,
            taskType = request.taskType,
            deviceState = executionContext.deviceState,
            promptCharBudget = minOf(contextBudget * 4, promptCharBudget),
            showThinking = showThinking,
        )
        val isMultimodalRequest = multimodalRequest && multimodalEnabled
        // Debug: MULTIMODAL_DECISION images=${userImagePaths.size} mmEnabled=$multimodalEnabled routing=$isMultimodalRequest
        val prompt = if (isMultimodalRequest) {
            injectImageMarkers(renderedPrompt.prompt, userImagePaths.size)
        } else {
            renderedPrompt.prompt
        }
        val managedRuntime = runtimeInferencePorts.managedRuntime
        val cacheAwareGeneration = runtimeInferencePorts.cacheAwareGeneration
        val modelRegistry = runtimeInferencePorts.modelRegistry
        val residencyPort = runtimeInferencePorts.residency
        val runtimePlan = runtimePlanResolver.resolve(
            sessionId = request.sessionId.value,
            modelId = modelId,
            modelVersion = modelVersion,
            taskType = request.taskType,
            stopSequences = renderedPrompt.stopSequences,
            requestConfig = executionContext.performanceConfig,
            residencyPolicy = executionContext.residencyPolicy,
            deviceState = executionContext.deviceState,
            runtimeInferencePorts = runtimeInferencePorts,
        )
        runtimePlan.loadBlockedReason?.let { blockedReason ->
            throw RuntimeModelLoadPlanningException(
                message = blockedReason,
                estimatedMemoryMb = runtimePlan.estimatedMemoryMb,
            )
        }
        val prefixCacheKey = buildPrefixCacheKey(runtimePlan.prefixCacheSlotId)
        requirePolicyEvent(
            eventType = "inference.generate",
            failureMessage = "Policy module rejected inference event type.",
        )
        val startedMs = System.currentTimeMillis()
        val interactionProfile = interactionPlanner.interactionProfileForModel(modelId)
        val streamFilters = ResponsePipelineFactory.createStreamFilters(
            profile = interactionProfile,
            showThinking = showThinking,
        )

        val effectivePerformanceConfig = runtimePlan.effectiveConfig
        val thermalThrottled = effectivePerformanceConfig != executionContext.performanceConfig

        managedRuntime?.setRuntimeGenerationConfig(runtimePlan.generationConfig)

        check(
            runtimeResidencyManager.ensureLoaded(
                modelId = modelId,
                slotId = runtimePlan.prefixCacheSlotId,
                keepAliveMs = runtimePlan.keepAliveMs,
                sessionCacheIdentity = runtimePlan.sessionCacheIdentity,
                strictGpuOffload = runtimePlan.generationConfig.strictGpuOffload,
            ),
        ) {
            "Failed to load runtime model: $modelId"
        }
        residencyPort?.residencyState()?.let { state ->
            recordResidencyMetrics(
                observabilityModule = observabilityModule,
                residencyState = state,
                loadDurationMs = state.lastLoadDurationMs,
                thermalThrottled = thermalThrottled,
            )
        }

        val cachePolicy = modelLifecycleCoordinator.resolveNativeCachePolicy()
        var firstTokenLatencyMs = -1L
        var finishReason = "completed"
        var responseText = ""
        var reasoningContent: String? = null
        var parsedToolCalls: List<InteractionToolCall> = emptyList()
        var executionResult: InferenceExecutionResult? = null
        val timeoutGuard = generationTimeoutGuardFactory(
            executionContext.requestTimeoutMs,
            {
                if (runtimeConfig.streamContractV2Enabled) {
                    cancelByRequest(executionContext.requestId)
                } else {
                    cancelBySession(request.sessionId)
                }
            },
        )
        var lastThinkingState = false
        runtimeResidencyManager.onGenerationStarted()
        val tokenHandler: (String) -> Unit = { token ->
            if (!timeoutGuard.timedOut()) {
                val filterResult = streamFilters.thinkingFilter?.filterToken(token)
                val visibleText = filterResult?.visibleText ?: token
                val currentThinkingState = filterResult?.isCurrentlyThinking ?: false
                if (currentThinkingState != lastThinkingState) {
                    lastThinkingState = currentThinkingState
                    request.onThinkingStateChanged(currentThinkingState)
                }
                if (visibleText.isNotEmpty()) {
                    if (firstTokenLatencyMs < 0) {
                        firstTokenLatencyMs = System.currentTimeMillis() - startedMs
                        timeoutGuard.finish()
                    }
                    request.onToken(visibleText)
                }
            }
        }
        try {
            executionResult = if (isMultimodalRequest) {
                inferenceExecutor.executeWithImages(
                    sessionId = request.sessionId.value,
                    requestId = executionContext.requestId,
                    prompt = prompt,
                    imagePaths = userImagePaths,
                    maxTokens = executionContext.maxTokens,
                    stopSequences = renderedPrompt.stopSequences,
                    onToken = tokenHandler,
                )
            } else {
                inferenceExecutor.execute(
                    sessionId = request.sessionId.value,
                    requestId = executionContext.requestId,
                    request = InferenceRequest(prompt = prompt, maxTokens = executionContext.maxTokens),
                    cacheKey = prefixCacheKey,
                    cachePolicy = cachePolicy,
                    stopSequences = renderedPrompt.stopSequences,
                    onToken = tokenHandler,
                )
            }
            finishReason = executionResult.finishReason
            val flushResult = streamFilters.thinkingFilter?.flush()
            if (lastThinkingState) {
                lastThinkingState = false
                request.onThinkingStateChanged(false)
            }
            flushResult?.visibleText?.takeIf { it.isNotEmpty() }?.let { flushed ->
                request.onToken(flushed)
            }
            val cleanedText = ResponsePipelineFactory.stripThinking(
                text = executionResult.text,
                profile = interactionProfile,
            )
            reasoningContent = streamFilters.thinkingFilter?.capturedThinking()?.ifBlank { null }
            val toolCallResult = ResponsePipelineFactory.parseToolCalls(
                text = cleanedText,
                profile = interactionProfile,
            )
            responseText = toolCallResult.textWithoutToolCalls.trim()
            parsedToolCalls = if (responseText.isBlank()) {
                toolCallResult.toolCalls
            } else {
                emptyList()
            }
            if (parsedToolCalls.isNotEmpty()) {
                finishReason = "tool_calls"
            }
            if (timeoutGuard.timedOut()) {
                throw RuntimeGenerationTimeoutException(executionContext.requestTimeoutMs)
            }
        } catch (error: RuntimeException) {
            if (timeoutGuard.timedOut()) {
                throw RuntimeGenerationTimeoutException(executionContext.requestTimeoutMs)
            }
            throw error
        } finally {
            timeoutGuard.finish()
            if (lastThinkingState) {
                request.onThinkingStateChanged(false)
            }
            if (!executionContext.keepModelLoaded || !executionContext.residencyPolicy.keepLoadedWhileAppForeground) {
                runtimeResidencyManager.unload(reason = "request_policy")
                runtimeResidencyManager.onGenerationFinished(slotId = null, keepAliveMs = null)
            } else {
                runtimeResidencyManager.onGenerationFinished(
                    slotId = runtimePlan.prefixCacheSlotId,
                    keepAliveMs = runtimePlan.keepAliveMs,
                )
            }
        }
        if (timeoutGuard.timedOut()) {
            throw RuntimeGenerationTimeoutException(executionContext.requestTimeoutMs)
        }
        check(responseText.isNotBlank() || finishReason == "tool_calls") { "Runtime returned no tokens." }
        if (firstTokenLatencyMs < 0) {
            firstTokenLatencyMs = (System.currentTimeMillis() - startedMs).coerceAtLeast(1L)
        }
        val totalLatency = System.currentTimeMillis() - startedMs
        val prefillMs = executionResult?.prefillMs ?: firstTokenLatencyMs
        val decodeMs = executionResult?.decodeMs ?: (totalLatency - prefillMs).coerceAtLeast(0L)
        val tokenCount = executionResult?.tokenCount ?: 0
        val peakRssMb = executionResult?.peakRssMb
        val tokensPerSec = executionResult?.tokensPerSec
            ?: if (tokenCount > 0 && decodeMs > 0) {
                tokenCount.toDouble() / (decodeMs.toDouble() / 1000.0)
            } else {
                0.0
            }
        requirePolicyEvent(
            eventType = "observability.record_runtime_metrics",
            failureMessage = "Policy module rejected diagnostics metric event type.",
        )
        observabilityModule.recordLatencyMetric("inference.first_token_ms", firstTokenLatencyMs.toDouble())
        observabilityModule.recordLatencyMetric("inference.total_ms", totalLatency.toDouble())
        observabilityModule.recordLatencyMetric("inference.prefill_ms", prefillMs.toDouble())
        observabilityModule.recordLatencyMetric("inference.decode_ms", decodeMs.toDouble())
        observabilityModule.recordLatencyMetric("inference.tokens_per_sec", tokensPerSec)
        peakRssMb?.let { observabilityModule.recordLatencyMetric("inference.peak_rss_mb", it) }
        observabilityModule.recordLatencyMetric("inference.profile", effectivePerformanceConfig.profile.ordinal.toDouble())
        observabilityModule.recordLatencyMetric(
            "inference.gpu_mode",
            if (effectivePerformanceConfig.gpuEnabled && effectivePerformanceConfig.gpuLayers > 0) 1.0 else 0.0,
        )
        observabilityModule.recordLatencyMetric(
            "inference.thermal_throttled",
            if (thermalThrottled) 1.0 else 0.0,
        )
        cacheAwareGeneration?.actualGpuLayers()?.let {
            observabilityModule.recordLatencyMetric("inference.actual_applied_gpu_layers", it.toDouble())
        }
        cacheAwareGeneration?.actualDraftGpuLayers()?.let {
            observabilityModule.recordLatencyMetric("inference.actual_applied_draft_gpu_layers", it.toDouble())
        }
        cacheAwareGeneration?.lastGpuLoadRetryCount()?.let {
            observabilityModule.recordLatencyMetric("inference.gpu_load_retry_count", it.toDouble())
        }
        val residencyState = residencyPort?.residencyState()
        val prefixCacheDiagnostics = parsePrefixCacheDiagnostics(residencyPort?.prefixCacheDiagnosticsLine())
        residencyState?.residentHitCount?.let {
            observabilityModule.recordLatencyMetric("inference.resident_hit_count", it.toDouble())
        }
        prefixCacheDiagnostics?.lastReusedTokens?.let {
            observabilityModule.recordLatencyMetric("inference.prefix_cache_reused_tokens", it.toDouble())
        }
        prefixCacheDiagnostics?.hitRate?.let {
            observabilityModule.recordLatencyMetric("inference.prefix_cache_hit_rate", it)
        }
        prefixCacheDiagnostics?.lastCacheHit?.let {
            observabilityModule.recordLatencyMetric("inference.prefix_cache_hit", if (it) 1.0 else 0.0)
        }
        observabilityModule.recordThermalSnapshot(executionContext.deviceState.thermalLevel)
        conversationModule.appendAssistantTurn(request.sessionId, responseText)

        val actualGpuLayers = cacheAwareGeneration?.actualGpuLayers() ?: runtimePlan.effectiveConfig.gpuLayers
        val actualDraftGpuLayers = cacheAwareGeneration?.actualDraftGpuLayers()
            ?: runtimePlan.effectiveConfig.speculativeDraftGpuLayers
        return ChatResponse(
            sessionId = request.sessionId,
            modelId = modelId,
            text = responseText,
            firstTokenLatencyMs = firstTokenLatencyMs,
            totalLatencyMs = totalLatency,
            requestId = executionContext.requestId,
            finishReason = finishReason,
            runtimeStats = RuntimeExecutionStats(
                modelLoadMs = residencyState?.lastLoadDurationMs,
                prefillMs = prefillMs,
                decodeMs = decodeMs,
                tokensPerSec = tokensPerSec,
                peakRssMb = peakRssMb,
                backendIdentity = cacheAwareGeneration?.activeBackendIdentity(),
                residentHit = residencyState?.residentHit,
                residentHitCount = residencyState?.residentHitCount,
                reloadReason = residencyState?.reloadReason?.name?.lowercase(),
                requestedKvCacheMethod = executionResult?.requestedKvCacheMethod,
                effectiveKvCacheMethod = executionResult?.effectiveKvCacheMethod,
                kvCacheMethodPreset = executionResult?.kvCacheMethodPreset,
                kvCacheMethodDemotionReason = executionResult?.kvCacheMethodDemotionReason,
                prefixCacheHitRate = prefixCacheDiagnostics?.hitRate,
                prefixCacheLastHit = prefixCacheDiagnostics?.lastCacheHit,
                prefixCacheLastReusedTokens = prefixCacheDiagnostics?.lastReusedTokens,
                prefixCacheStoreSuccessCount = prefixCacheDiagnostics?.storeStateSuccessCount,
                prefixCacheStoreFailureCount = prefixCacheDiagnostics?.storeStateFailureCount,
                prefixCacheRestoreSuccessCount = prefixCacheDiagnostics?.restoreStateSuccessCount,
                prefixCacheRestoreFailureCount = prefixCacheDiagnostics?.restoreStateFailureCount,
                appliedGpuLayers = actualGpuLayers,
                appliedDraftGpuLayers = actualDraftGpuLayers,
                gpuLoadRetryCount = cacheAwareGeneration?.lastGpuLoadRetryCount(),
                modelLayerCount = modelRegistry?.cachedModelLayerCount(modelId),
                estimatedMaxGpuLayers = modelRegistry?.cachedEstimatedMaxGpuLayers(
                    modelId = modelId,
                    nCtx = runtimePlan.generationConfig.nCtx,
                ),
                estimatedMemoryMb = runtimePlan.estimatedMemoryMb,
            ),
            toolCalls = parsedToolCalls.map { call ->
                ChatToolCall(id = call.id, name = call.name, argumentsJson = call.argumentsJson)
            },
            reasoningContent = reasoningContent,
        )
    }
    private fun buildPrefixCacheKey(slotId: String): String = slotId

    private fun resolveMemorySnippets(
        sessionId: SessionId,
        latestUserText: String,
        transcriptMessages: List<InteractionMessage>,
    ): List<String> {
        val cachedSnippets = synchronized(sessionMemorySnippetsBySessionId) {
            sessionMemorySnippetsBySessionId[sessionId.value].orEmpty()
        }
        if (!shouldRetrieveMemorySnippets(transcriptMessages)) {
            return cachedSnippets
        }
        if (cachedSnippets.isNotEmpty()) {
            return cachedSnippets
        }
        val retrievedSnippets = memoryModule.retrieveRelevantMemory(latestUserText, 3)
            .map { chunk -> chunk.content.trim() }
            .filter { snippet -> snippet.isNotBlank() }
        if (retrievedSnippets.isNotEmpty()) {
            synchronized(sessionMemorySnippetsBySessionId) {
                sessionMemorySnippetsBySessionId[sessionId.value] = retrievedSnippets
            }
        }
        return retrievedSnippets
    }

    private fun requirePolicyEvent(eventType: String, failureMessage: String) {
        check(policyModule.enforceDataBoundary(eventType)) { failureMessage }
    }

    companion object {
        private const val MAX_PROMPT_CHARS_SHORT: Int = 1024
        private const val MAX_PROMPT_CHARS_LONG: Int = 2048
    }
}

private fun shouldRetrieveMemorySnippets(messages: List<InteractionMessage>): Boolean {
    val hasAssistantContext = messages.any { message ->
        message.role == InteractionRole.ASSISTANT || message.role == InteractionRole.TOOL
    }
    if (hasAssistantContext) {
        return false
    }
    return messages.count { message -> message.role == InteractionRole.USER } <= 1
}

private fun List<InteractionMessage>.latestUserMessageText(): String {
    return asReversed()
        .firstOrNull { message -> message.role == InteractionRole.USER }
        ?.parts
        ?.filterIsInstance<InteractionContentPart.Text>()
        ?.joinToString(separator = "\n") { it.text }
        ?.trim()
        .orEmpty()
}

/**
 * Injects media markers into a ChatML-formatted prompt so that mtmd can map them to
 * actual vision embeddings.  Markers are placed immediately after the last `user\n` role tag.
 *
 * The marker MUST match what [mtmd_default_marker()] returns in the native layer — currently
 * `<__media__>`.  Using any other string (e.g. `<image>`) will cause mtmd_tokenize to skip
 * the image embeddings entirely.
 */
internal fun injectImageMarkers(prompt: String, imageCount: Int): String {
    if (imageCount <= 0) return prompt
    val markers = (1..imageCount).joinToString(separator = "\n") { MTMD_MEDIA_MARKER }
    val userRoleTag = "<|im_start|>user\n"
    val lastIdx = prompt.lastIndexOf(userRoleTag)
    if (lastIdx >= 0) {
        val insertAt = lastIdx + userRoleTag.length
        return prompt.substring(0, insertAt) + markers + "\n" + prompt.substring(insertAt)
    }
    return markers + "\n" + prompt
}

private const val MTMD_MEDIA_MARKER = "<__media__>"

private fun com.pocketagent.core.Turn.toInteractionMessage(): InteractionMessage {
    val interactionRole = when (role.trim().lowercase()) {
        "system" -> InteractionRole.SYSTEM
        "assistant" -> InteractionRole.ASSISTANT
        "tool" -> InteractionRole.TOOL
        else -> InteractionRole.USER
    }
    return InteractionMessage(
        id = "turn-${timestampEpochMs}-${interactionRole.name.lowercase()}",
        role = interactionRole,
        parts = listOf(InteractionContentPart.Text(content)),
        metadata = mapOf("timestampEpochMs" to timestampEpochMs.toString()),
    )
}
