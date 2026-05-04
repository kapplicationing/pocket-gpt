package com.pocketagent.runtime

import com.pocketagent.core.InMemoryConversationModule
import com.pocketagent.core.ObservabilityModule
import com.pocketagent.core.PolicyModule
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.model.PromptTemplateFamily
import com.pocketagent.inference.DeviceState
import com.pocketagent.inference.InferenceModule
import com.pocketagent.inference.InferenceRequest
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.inference.RoutingModule
import com.pocketagent.memory.InMemoryMemoryModule
import com.pocketagent.memory.MemoryChunk
import com.pocketagent.memory.MemoryModule
import com.pocketagent.nativebridge.CacheAwareGenerationPort
import com.pocketagent.nativebridge.CachePolicy
import com.pocketagent.nativebridge.GenerationFinishReason
import com.pocketagent.nativebridge.GenerationResult
import com.pocketagent.nativebridge.ModelRuntimeMetadata
import com.pocketagent.nativebridge.RuntimeInferencePorts
import com.pocketagent.nativebridge.RuntimeModelRegistryPort
import com.pocketagent.nativebridge.RuntimeReloadReason
import com.pocketagent.nativebridge.RuntimeResidencyPort
import com.pocketagent.nativebridge.RuntimeResidencyState
import com.pocketagent.nativebridge.BridgeError
import com.pocketagent.nativebridge.ManagedRuntimePort
import com.pocketagent.nativebridge.ModelLifecycleEvent
import com.pocketagent.nativebridge.ModelLifecycleState
import com.pocketagent.nativebridge.RuntimeBackend
import com.pocketagent.nativebridge.RuntimeGenerationConfig
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SendMessageUseCaseTest {
    @Test
    fun `throws when routing policy event is denied`() {
        val fixture = createFixture(
            runtimeConfig = sendRuntimeConfig(streamContractV2Enabled = true),
            policyModule = SendPolicyModule(
                allowedEvents = setOf(
                    "memory.write_user_turn",
                    "inference.generate",
                    "observability.record_runtime_metrics",
                ),
            ),
            inferenceModule = SendRecordingInferenceModule(),
        )

        val error = assertFailsWith<IllegalStateException> {
            fixture.useCase.execute(fixture.request())
        }

        assertTrue(error.message?.contains("Policy module rejected routing event type.") == true)
    }

    @Test
    fun `interactive chat bypasses response cache and still requires model load`() {
        val fixture = createFixture(
            runtimeConfig = sendRuntimeConfig(streamContractV2Enabled = true),
            policyModule = permissivePolicy(),
            inferenceModule = SendRecordingInferenceModule(loadModelResult = false),
        )

        val error = assertFailsWith<IllegalStateException> {
            fixture.useCase.execute(fixture.request())
        }

        assertTrue(error.message?.contains("Failed to load runtime model") == true)
        assertEquals(1, fixture.inferenceModule.loadCalls)
    }

    @Test
    fun `image attachments are rejected for non vision models before inference`() {
        val fixture = createFixture(
            runtimeConfig = sendRuntimeConfig(streamContractV2Enabled = true),
            policyModule = permissivePolicy(),
            inferenceModule = SendRecordingInferenceModule(),
            routingModule = SendStaticRoutingModule(modelId = ModelCatalog.LLAMA_3_2_1B_Q4_K_M),
        )

        val error = assertFailsWith<RuntimeImageAttachmentUnsupportedException> {
            fixture.useCase.execute(
                fixture.request(
                    messages = listOf(
                        InteractionMessage(
                            role = InteractionRole.USER,
                            parts = listOf(
                                InteractionContentPart.Text("what is in this image?"),
                                InteractionContentPart.Image("/tmp/example.png"),
                            ),
                        ),
                    ),
                ),
            )
        }

        assertTrue(error.message?.contains("image attachments", ignoreCase = true) == true)
        assertEquals(0, fixture.inferenceModule.loadCalls)
    }

    @Test
    fun `image attachments are rejected for vision model when multimodal runtime is disabled`() {
        val managedRuntime = object : ManagedRuntimePort {
            override fun loadModel(modelId: String, modelVersion: String?, strictGpuOffload: Boolean): Boolean = true
            override fun setRuntimeGenerationConfig(config: RuntimeGenerationConfig) = Unit
            override fun supportsGpuOffload(): Boolean = false
            override fun runtimeBackend(): RuntimeBackend = RuntimeBackend.NATIVE_JNI
            override fun lastBridgeError(): BridgeError? = null
            override fun currentModelLifecycleState(): ModelLifecycleEvent = ModelLifecycleEvent(state = ModelLifecycleState.UNLOADED)
            override fun observeModelLifecycleState(listener: (ModelLifecycleEvent) -> Unit): AutoCloseable = AutoCloseable {}
            override fun currentRssMb(): Double? = null
            override fun isRuntimeReleased(): Boolean = false
            override fun isMultimodalEnabled(): Boolean = false
        }
        val fixture = createFixture(
            runtimeConfig = sendRuntimeConfig(streamContractV2Enabled = true),
            policyModule = permissivePolicy(),
            inferenceModule = SendRecordingInferenceModule(),
            routingModule = SendStaticRoutingModule(modelId = ModelCatalog.QWEN_3_5_0_8B_Q4),
            runtimeInferencePorts = RuntimeInferencePorts(
                managedRuntime = managedRuntime,
            ),
        )

        val error = assertFailsWith<RuntimeImageAttachmentUnsupportedException> {
            fixture.useCase.execute(
                fixture.request(
                    messages = listOf(
                        InteractionMessage(
                            role = InteractionRole.USER,
                            parts = listOf(
                                InteractionContentPart.Text("what is in this image?"),
                                InteractionContentPart.Image("/tmp/example.png"),
                            ),
                        ),
                    ),
                ),
            )
        }

        assertTrue(error.message?.contains("multimodal support", ignoreCase = true) == true)
        assertEquals(0, fixture.inferenceModule.loadCalls)
    }

    @Test
    fun `timeout invokes request scoped cancellation when stream contract v2 is enabled`() {
        val fixture = createFixture(
            runtimeConfig = sendRuntimeConfig(streamContractV2Enabled = true),
            policyModule = permissivePolicy(),
            inferenceModule = SendRecordingInferenceModule(
                generatedTokens = listOf("late "),
                busyWaitMsBeforeTokens = 30L,
            ),
        )

        assertFailsWith<RuntimeGenerationTimeoutException> {
            fixture.useCase.execute(
                fixture.request(
                    requestTimeoutMs = 5L,
                ),
            )
        }

        assertTrue(fixture.cancelByRequestCalls > 0)
        assertEquals(0, fixture.cancelBySessionCalls)
    }

    @Test
    fun `timeout invokes session cancellation when stream contract v2 is disabled`() {
        val fixture = createFixture(
            runtimeConfig = sendRuntimeConfig(streamContractV2Enabled = false),
            policyModule = permissivePolicy(),
            inferenceModule = SendRecordingInferenceModule(
                generatedTokens = listOf("late "),
                busyWaitMsBeforeTokens = 30L,
            ),
        )

        assertFailsWith<RuntimeGenerationTimeoutException> {
            fixture.useCase.execute(
                fixture.request(
                    requestTimeoutMs = 5L,
                ),
            )
        }

        assertTrue(fixture.cancelBySessionCalls > 0)
        assertEquals(0, fixture.cancelByRequestCalls)
    }

    @Test
    fun `generation does not timeout after first token is emitted`() {
        val fixture = createFixture(
            runtimeConfig = sendRuntimeConfig(streamContractV2Enabled = true),
            policyModule = permissivePolicy(),
            inferenceModule = SendRecordingInferenceModule(
                generatedTokens = listOf("hello ", "world "),
                busyWaitMsAfterFirstToken = 30L,
            ),
        )

        val response = fixture.useCase.execute(
            fixture.request(
                requestTimeoutMs = 5L,
            ),
        )

        assertEquals("hello world", response.text)
        assertEquals("completed", response.finishReason)
        assertEquals(0, fixture.cancelByRequestCalls)
        assertEquals(0, fixture.cancelBySessionCalls)
    }

    @Test
    fun `generation only finishes timeout guard after first visible token`() {
        val fixture = createFixture(
            runtimeConfig = sendRuntimeConfig(streamContractV2Enabled = true),
            policyModule = permissivePolicy(),
            inferenceModule = SendRecordingInferenceModule(
                generatedTokens = listOf("<think>", "reasoning...", "</think>", "final answer"),
                busyWaitMsAfterFirstToken = 30L,
            ),
        )

        val error = assertFailsWith<RuntimeGenerationTimeoutException> {
            fixture.useCase.execute(
                fixture.request(
                    requestTimeoutMs = 5L,
                ),
            )
        }

        assertEquals(5L, error.timeoutMs)
        assertEquals(1, fixture.cancelByRequestCalls)
        assertEquals(0, fixture.cancelBySessionCalls)
    }

    @Test
    fun `generation survives short hidden think block when visible answer arrives within deadline`() {
        val fixture = createFixture(
            runtimeConfig = sendRuntimeConfig(streamContractV2Enabled = true),
            policyModule = permissivePolicy(),
            inferenceModule = SendRecordingInferenceModule(
                generatedTokens = listOf("<think>", "reasoning...", "</think>", "final answer"),
                busyWaitMsAfterFirstToken = 1L,
            ),
        )

        val response = fixture.useCase.execute(
            fixture.request(
                requestTimeoutMs = 50L,
            ),
        )

        assertEquals("final answer", response.text)
        assertEquals("completed", response.finishReason)
        assertEquals(0, fixture.cancelByRequestCalls)
        assertEquals(0, fixture.cancelBySessionCalls)
    }

    @Test
    fun `residency policy controls unload now vs idle schedule`() {
        val unloadNowFixture = createFixture(
            runtimeConfig = sendRuntimeConfig(streamContractV2Enabled = true),
            policyModule = permissivePolicy(),
            inferenceModule = SendRecordingInferenceModule(generatedTokens = listOf("ok ")),
        )
        unloadNowFixture.useCase.execute(
            unloadNowFixture.request(
                keepModelLoaded = false,
                residencyPolicy = ModelResidencyPolicy(keepLoadedWhileAppForeground = true, idleUnloadTtlMs = 4_000L),
            ),
        )
        assertEquals(1, unloadNowFixture.inferenceModule.unloadCalls)
        assertTrue(unloadNowFixture.runtimeResidencyManager.listResident().isEmpty())

        val scheduleFixture = createFixture(
            runtimeConfig = sendRuntimeConfig(streamContractV2Enabled = true),
            policyModule = permissivePolicy(),
            inferenceModule = SendRecordingInferenceModule(generatedTokens = listOf("ok ")),
        )
        scheduleFixture.useCase.execute(
            scheduleFixture.request(
                keepModelLoaded = true,
                residencyPolicy = ModelResidencyPolicy(keepLoadedWhileAppForeground = true, idleUnloadTtlMs = 4_000L),
            ),
        )
        assertEquals(0, scheduleFixture.inferenceModule.unloadCalls)
        assertEquals(1, scheduleFixture.runtimeResidencyManager.listResident().size)
    }

    @Test
    fun `memory planning blocks request before native model load`() {
        val tracker = MemoryBudgetTracker().also {
            it.recordAvailableMemoryAfterRelease(700.0)
        }
        val inferenceModule = SendRecordingInferenceModule()
        val fixture = createFixture(
            runtimeConfig = sendRuntimeConfig(streamContractV2Enabled = true),
            policyModule = permissivePolicy(),
            inferenceModule = inferenceModule,
            runtimeInferencePorts = RuntimeInferencePorts(
                modelRegistry = SendModelRegistryPort(),
            ),
            runtimePlanResolver = RuntimePlanResolver(
                memoryBudgetTracker = tracker,
                recommendedGpuLayers = { _, _ -> 4 },
            ),
        )

        val error = assertFailsWith<RuntimeModelLoadPlanningException> {
            fixture.useCase.execute(fixture.request())
        }

        assertTrue(error.message?.contains("tracked safe memory ceiling") == true)
        assertEquals(0, inferenceModule.loadCalls)
    }

    @Test
    fun `memory planning rescue keeps request runnable when batch reduction fits`() {
        val tracker = MemoryBudgetTracker().also {
            it.recordAvailableMemoryAfterRelease(1600.0)
        }
        val inferenceModule = SendRecordingInferenceModule()
        val fixture = createFixture(
            runtimeConfig = sendRuntimeConfig(streamContractV2Enabled = true),
            policyModule = permissivePolicy(),
            inferenceModule = inferenceModule,
            runtimeInferencePorts = RuntimeInferencePorts(
                modelRegistry = SendModelRegistryPort(),
            ),
            runtimePlanResolver = RuntimePlanResolver(
                memoryBudgetTracker = tracker,
                recommendedGpuLayers = { _, _ -> 4 },
            ),
        )

        val response = fixture.useCase.execute(fixture.request())

        assertEquals("hello world", response.text)
        assertEquals(1, inferenceModule.loadCalls)
        assertTrue((response.runtimeStats?.estimatedMemoryMb ?: 0.0) > 0.0)
    }

    @Test
    fun `llama profile skips thinking and tool-call processing`() {
        val streamedTokens = mutableListOf<String>()
        val fixture = createFixture(
            runtimeConfig = sendRuntimeConfig(streamContractV2Enabled = true),
            policyModule = permissivePolicy(),
            inferenceModule = SendRecordingInferenceModule(
                generatedTokens = listOf(
                    "<think>private reasoning</think>",
                    "Visible response ",
                    "<tool_call>{\"name\":\"calculator\",\"arguments\":{\"expression\":\"1+1\"}}</tool_call>",
                ),
            ),
            routingModule = SendStaticRoutingModule(modelId = ModelCatalog.LLAMA_3_2_1B_Q4_K_M),
        )

        val response = fixture.useCase.execute(
            fixture.request().copy(
                onToken = { token -> streamedTokens += token },
            ),
        )

        assertEquals("<think>private reasoning</think>Visible response <tool_call>{\"name\":\"calculator\",\"arguments\":{\"expression\":\"1+1\"}}</tool_call>", response.text)
        assertTrue(response.toolCalls.isEmpty())
        assertEquals(null, response.reasoningContent)
        assertEquals(
            listOf(
                "<think>private reasoning</think>",
                "Visible response ",
                "<tool_call>{\"name\":\"calculator\",\"arguments\":{\"expression\":\"1+1\"}}</tool_call>",
            ),
            streamedTokens,
        )
    }

    @Test
    fun `chatml profile parses bare json tool payloads into tool calls`() {
        val fixture = createFixture(
            runtimeConfig = sendRuntimeConfig(streamContractV2Enabled = true),
            policyModule = permissivePolicy(),
            inferenceModule = SendRecordingInferenceModule(
                generatedTokens = listOf(
                    """{"name":"date_time","arguments":{}}""",
                    "\n",
                    """{"name":"notes_lookup","arguments":{"query":"how you doin today lad?"}}""",
                ),
            ),
            routingModule = SendStaticRoutingModule(modelId = ModelCatalog.QWEN3_0_6B_Q4_K_M),
        )

        val response = fixture.useCase.execute(fixture.request())

        assertTrue(response.text.isBlank())
        assertEquals("tool_calls", response.finishReason)
        assertEquals(2, response.toolCalls.size)
        assertEquals("date_time", response.toolCalls[0].name)
        assertEquals("notes_lookup", response.toolCalls[1].name)
    }

    @Test
    fun `chatml profile ignores stray tool payload when visible answer is present`() {
        val streamedTokens = mutableListOf<String>()
        val fixture = createFixture(
            runtimeConfig = sendRuntimeConfig(streamContractV2Enabled = true),
            policyModule = permissivePolicy(),
            inferenceModule = SendRecordingInferenceModule(
                generatedTokens = listOf(
                    "<think>private reasoning</think>",
                    "Visible response ",
                    "<tool_call>{\"name\":\"calculator\",\"arguments\":{\"expression\":\"1+1\"}}",
                ),
            ),
            routingModule = SendStaticRoutingModule(modelId = ModelCatalog.QWEN3_1_7B_Q4_K_M),
        )

        val response = fixture.useCase.execute(
            fixture.request().copy(
                onToken = { token -> streamedTokens += token },
            ),
        )

        assertEquals("Visible response", response.text)
        assertEquals("completed", response.finishReason)
        assertTrue(response.toolCalls.isEmpty())
        assertEquals(null, response.reasoningContent)
        assertEquals(
            listOf(
                "Visible response ",
                "<tool_call>{\"name\":\"calculator\",\"arguments\":{\"expression\":\"1+1\"}}",
            ),
            streamedTokens,
        )
    }

    @Test
    fun `runtime stats carry active backend identity from cache aware runtime port`() {
        val fixture = createFixture(
            runtimeConfig = sendRuntimeConfig(streamContractV2Enabled = true),
            policyModule = permissivePolicy(),
            inferenceModule = SendRecordingInferenceModule(),
            runtimeInferencePorts = RuntimeInferencePorts(
                cacheAwareGeneration = SendCacheAwareGenerationPort(backendIdentity = "opencl"),
                modelRegistry = SendModelRegistryPort(),
            ),
        )

        val response = fixture.useCase.execute(fixture.request())

        assertEquals("opencl", response.runtimeStats?.backendIdentity)
    }

    @Test
    fun `runtime stats include residency and prefix cache diagnostics`() {
        val fixture = createFixture(
            runtimeConfig = sendRuntimeConfig(streamContractV2Enabled = true),
            policyModule = permissivePolicy(),
            inferenceModule = SendRecordingInferenceModule(),
            runtimeInferencePorts = RuntimeInferencePorts(
                cacheAwareGeneration = SendCacheAwareGenerationPort(backendIdentity = "cpu"),
                modelRegistry = SendModelRegistryPort(),
                residency = SendResidencyPort(
                    state = RuntimeResidencyState(
                        resident = true,
                        residentHit = true,
                        residentHitCount = 3L,
                        reloadReason = RuntimeReloadReason.GENERATION_CONFIG_CHANGED,
                        lastLoadDurationMs = 420L,
                    ),
                    prefixCacheDiagnosticsLine = "PREFIX_CACHE_DIAG|last_cache_hit=true|last_reused_tokens=128|prefix_cache_hit_rate=0.75|store_state_success=2|store_state_failure=1|restore_state_success=4|restore_state_failure=0",
                ),
            ),
        )

        val response = fixture.useCase.execute(fixture.request())

        assertEquals(420L, response.runtimeStats?.modelLoadMs)
        assertEquals(true, response.runtimeStats?.residentHit)
        assertEquals(3L, response.runtimeStats?.residentHitCount)
        assertEquals("generation_config_changed", response.runtimeStats?.reloadReason)
        assertEquals(true, response.runtimeStats?.prefixCacheLastHit)
        assertEquals(128, response.runtimeStats?.prefixCacheLastReusedTokens)
        assertEquals(0.75, response.runtimeStats?.prefixCacheHitRate)
        assertEquals(2, response.runtimeStats?.prefixCacheStoreSuccessCount)
        assertEquals(1, response.runtimeStats?.prefixCacheStoreFailureCount)
        assertEquals(4, response.runtimeStats?.prefixCacheRestoreSuccessCount)
        assertEquals(0, response.runtimeStats?.prefixCacheRestoreFailureCount)
    }

    @Test
    fun `prompt memory does not echo current user turn`() {
        val templateRenderer = RecordingTemplateRenderer()
        val memoryModule = RecordingMemoryModule().apply {
            saveMemoryChunk(
                MemoryChunk(
                    id = "persisted-1",
                    content = "phoenix launch notes",
                    createdAtEpochMs = 1L,
                ),
            )
        }
        val fixture = createFixture(
            runtimeConfig = sendRuntimeConfig(streamContractV2Enabled = true),
            policyModule = permissivePolicy(),
            inferenceModule = SendRecordingInferenceModule(),
            memoryModule = memoryModule,
            interactionPlanner = InteractionPlanner(
                interactionRegistry = ModelInteractionRegistry(),
                templateRenderer = templateRenderer,
            ),
        )

        fixture.useCase.execute(
            fixture.request().copy(
                userText = "phoenix launch notes today",
            ),
        )

        val systemText = templateRenderer.lastSystemText()
        assertTrue(systemText.contains("memory: phoenix launch notes"))
        assertTrue(!systemText.contains("memory: phoenix launch notes today"))
    }

    @Test
    fun `follow up prompts retain session memory snippets`() {
        val templateRenderer = RecordingTemplateRenderer()
        val memoryModule = RecordingMemoryModule().apply {
            saveMemoryChunk(
                MemoryChunk(
                    id = "persisted-1",
                    content = "rollout checklist fallback plan",
                    createdAtEpochMs = 1L,
                ),
            )
        }
        val fixture = createFixture(
            runtimeConfig = sendRuntimeConfig(streamContractV2Enabled = true),
            policyModule = permissivePolicy(),
            inferenceModule = SendRecordingInferenceModule(generatedTokens = listOf("status ")),
            memoryModule = memoryModule,
            interactionPlanner = InteractionPlanner(
                interactionRegistry = ModelInteractionRegistry(),
                templateRenderer = templateRenderer,
            ),
        )

        fixture.useCase.execute(
            fixture.request().copy(
                sessionId = SessionId("session-follow-up"),
                userText = "summarize rollout checklist",
            ),
        )
        val firstSystemText = templateRenderer.lastSystemText()

        fixture.useCase.execute(
            fixture.request().copy(
                sessionId = SessionId("session-follow-up"),
                userText = "shorten that summary",
            ),
        )
        val secondSystemText = templateRenderer.lastSystemText()

        assertTrue(firstSystemText.contains("memory: rollout checklist fallback plan"))
        assertTrue(secondSystemText.contains("memory: rollout checklist fallback plan"))
    }

    @Test
    fun `injectImageMarkers with zero images returns prompt unchanged`() {
        val prompt = "<|im_start|>user\nHello<|im_end|>"
        assertEquals(prompt, injectImageMarkers(prompt, 0))
    }

    @Test
    fun `injectImageMarkers with negative count returns prompt unchanged`() {
        val prompt = "<|im_start|>user\nHello<|im_end|>"
        assertEquals(prompt, injectImageMarkers(prompt, -1))
    }

    @Test
    fun `injectImageMarkers inserts single marker after last user tag`() {
        val prompt = "<|im_start|>system\nYou are helpful.<|im_end|>\n<|im_start|>user\nDescribe this image.<|im_end|>"
        val result = injectImageMarkers(prompt, 1)
        val expected = "<|im_start|>system\nYou are helpful.<|im_end|>\n<|im_start|>user\n<__media__>\nDescribe this image.<|im_end|>"
        assertEquals(expected, result)
    }

    @Test
    fun `injectImageMarkers inserts multiple markers`() {
        val prompt = "<|im_start|>user\nCompare these images.<|im_end|>"
        val result = injectImageMarkers(prompt, 3)
        assertTrue(result.contains("<__media__>\n<__media__>\n<__media__>"))
    }

    @Test
    fun `injectImageMarkers without user tag prepends markers`() {
        val prompt = "Some plain prompt"
        val result = injectImageMarkers(prompt, 1)
        assertTrue(result.startsWith("<__media__>\n"))
        assertTrue(result.endsWith("Some plain prompt"))
    }

    @Test
    fun `follow up prompts include prior user and assistant turns`() {
        val templateRenderer = RecordingTemplateRenderer()
        val fixture = createFixture(
            runtimeConfig = sendRuntimeConfig(streamContractV2Enabled = true),
            policyModule = permissivePolicy(),
            inferenceModule = SendRecordingInferenceModule(generatedTokens = listOf("status ")),
            interactionPlanner = InteractionPlanner(
                interactionRegistry = ModelInteractionRegistry(),
                templateRenderer = templateRenderer,
            ),
        )

        fixture.useCase.execute(
            fixture.request().copy(
                sessionId = SessionId("session-history"),
                userText = "summarize rollout checklist",
            ),
        )
        fixture.useCase.execute(
            fixture.request().copy(
                sessionId = SessionId("session-history"),
                userText = "shorten that summary",
            ),
        )

        val secondPrompt = templateRenderer.lastPrompt
        assertTrue(secondPrompt.contains("summarize rollout checklist"))
        assertTrue(secondPrompt.contains("status"))
        assertTrue(secondPrompt.contains("shorten that summary"))
    }
}

private class SendMessageFixture(
    val inferenceModule: SendRecordingInferenceModule,
    val runtimeResidencyManager: RuntimeResidencyManager,
) {
    lateinit var useCase: SendMessageUseCase
    var cancelByRequestCalls: Int = 0
    var cancelBySessionCalls: Int = 0

    fun request(
        requestTimeoutMs: Long = 60_000L,
        keepModelLoaded: Boolean = true,
        residencyPolicy: ModelResidencyPolicy = ModelResidencyPolicy(
            keepLoadedWhileAppForeground = true,
            idleUnloadTtlMs = 3_000L,
        ),
        messages: List<InteractionMessage> = emptyList(),
    ): SendMessageUseCase.Request {
        return SendMessageUseCase.Request(
            sessionId = SessionId("session-1"),
            userText = "hello world",
            messages = messages,
            taskType = "short_text",
            executionContext = RuntimeRequestContext(
                deviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 8),
                maxTokens = 64,
                keepModelLoaded = keepModelLoaded,
                requestTimeoutMs = requestTimeoutMs,
                requestId = "req-1",
                performanceConfig = PerformanceRuntimeConfig.default(),
                residencyPolicy = residencyPolicy,
            ),
            onToken = {},
            routingMode = RoutingMode.AUTO,
        )
    }
}

private fun createFixture(
    runtimeConfig: RuntimeConfig,
    policyModule: SendPolicyModule,
    inferenceModule: SendRecordingInferenceModule,
    routingModule: RoutingModule = SendStaticRoutingModule(),
    runtimeInferencePorts: RuntimeInferencePorts = RuntimeInferencePorts(),
    runtimePlanResolver: RuntimePlanResolver = RuntimePlanResolver(),
    memoryModule: MemoryModule = InMemoryMemoryModule(),
    interactionPlanner: InteractionPlanner = InteractionPlanner(interactionRegistry = ModelInteractionRegistry()),
): SendMessageFixture {
    val modelLifecycle = ModelLifecycleCoordinator(
        inferenceModule = inferenceModule,
        routingModule = routingModule,
        runtimeConfig = runtimeConfig,
    )
    val observability = NoopObservabilityModule()
    val runtimeResidencyManager = RuntimeResidencyManager(
        inferenceModule = inferenceModule,
        runtimeInferencePorts = runtimeInferencePorts,
    )
    val fixture = SendMessageFixture(
        inferenceModule = inferenceModule,
        runtimeResidencyManager = runtimeResidencyManager,
    )
    fixture.useCase = SendMessageUseCase(
        conversationModule = InMemoryConversationModule(),
        routingModule = routingModule,
        policyModule = policyModule,
        observabilityModule = observability,
        memoryModule = memoryModule,
        inferenceModule = inferenceModule,
        runtimeConfig = runtimeConfig,
        artifactVerifier = ArtifactVerifier(runtimeConfig),
        interactionPlanner = interactionPlanner,
        inferenceExecutor = InferenceExecutor(
            inferenceModule = inferenceModule,
            runtimeConfig = runtimeConfig,
            runtimeInferencePorts = runtimeInferencePorts,
        ),
        modelLifecycleCoordinator = modelLifecycle,
        runtimePlanResolver = runtimePlanResolver,
        runtimeResidencyManager = runtimeResidencyManager,
        cancelByRequest = { _ ->
            fixture.cancelByRequestCalls += 1
            true
        },
        cancelBySession = { _ ->
            fixture.cancelBySessionCalls += 1
            true
        },
        runtimeInferencePorts = runtimeInferencePorts,
    )
    return fixture
}

private class SendRecordingInferenceModule(
    private val loadModelResult: Boolean = true,
    private val generatedTokens: List<String> = listOf("hello ", "world "),
    private val busyWaitMsBeforeTokens: Long = 0L,
    private val busyWaitMsAfterFirstToken: Long = 0L,
) : InferenceModule {
    var loadCalls: Int = 0
    var unloadCalls: Int = 0

    override fun listAvailableModels(): List<String> {
        return listOf(
            ModelCatalog.QWEN_3_5_0_8B_Q4,
            ModelCatalog.QWEN3_1_7B_Q4_K_M,
            ModelCatalog.LLAMA_3_2_1B_Q4_K_M,
        )
    }

    override fun loadModel(modelId: String): Boolean {
        loadCalls += 1
        return loadModelResult
    }

    override fun generateStream(request: InferenceRequest, onToken: (String) -> Unit) {
        busyWait(busyWaitMsBeforeTokens)
        generatedTokens.forEachIndexed { index, token ->
            onToken(token)
            if (index == 0) {
                busyWait(busyWaitMsAfterFirstToken)
            }
        }
    }

    override fun unloadModel() {
        unloadCalls += 1
    }

    private fun busyWait(waitMs: Long) {
        if (waitMs <= 0L) {
            return
        }
        val start = System.currentTimeMillis()
        while ((System.currentTimeMillis() - start) < waitMs) {
            Thread.onSpinWait()
        }
    }
}

private class RecordingMemoryModule : MemoryModule {
    private val chunks = mutableListOf<MemoryChunk>()

    override fun saveMemoryChunk(chunk: MemoryChunk): Boolean {
        chunks += chunk
        return true
    }

    override fun retrieveRelevantMemory(query: String, limit: Int): List<MemoryChunk> {
        val queryTokens = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
        return chunks
            .map { chunk ->
                chunk to chunk.content.lowercase().split(Regex("\\s+")).count { token -> queryTokens.contains(token) }
            }
            .sortedByDescending { it.second }
            .filter { it.second > 0 }
            .take(limit)
            .map { it.first }
    }

    override fun pruneMemory(maxChunks: Int): Int = 0
}

private class RecordingTemplateRenderer : ChatTemplateRenderer {
    var lastMessages: List<InteractionMessage> = emptyList()
        private set
    var lastPrompt: String = ""
        private set

    override fun render(
        messages: List<InteractionMessage>,
        modelProfile: PromptTemplateFamily,
    ): RenderedPrompt {
        lastMessages = messages
        lastPrompt = messages.joinToString(separator = "\n") { message ->
            message.parts
                .filterIsInstance<InteractionContentPart.Text>()
                .joinToString(separator = "") { it.text }
        }
        return RenderedPrompt(
            prompt = lastPrompt,
            stopSequences = emptyList(),
            templateFamily = modelProfile,
        )
    }

    fun lastSystemText(): String {
        val system = lastMessages.first { it.role == InteractionRole.SYSTEM }
        return system.parts
            .filterIsInstance<InteractionContentPart.Text>()
            .joinToString(separator = "") { it.text }
    }
}

private class SendCacheAwareGenerationPort(
    private val backendIdentity: String,
) : CacheAwareGenerationPort {
    override fun generateStreamWithCache(
        requestId: String,
        request: InferenceRequest,
        cacheKey: String?,
        cachePolicy: CachePolicy,
        onToken: (String) -> Unit,
    ): GenerationResult {
        onToken("hello ")
        onToken("world ")
        return GenerationResult(
            finishReason = GenerationFinishReason.COMPLETED,
            tokenCount = 2,
            firstTokenMs = 1L,
            totalMs = 2L,
            cancelled = false,
            prefillMs = 1L,
            decodeMs = 1L,
            tokensPerSec = 2_000.0,
            peakRssMb = 512.0,
        )
    }

    override fun cancelGeneration(requestId: String): Boolean = true

    override fun actualGpuLayers(): Int? = 4

    override fun actualDraftGpuLayers(): Int? = 0

    override fun lastGpuLoadRetryCount(): Int? = 0

    override fun activeBackendIdentity(): String? = backendIdentity
}

private class SendModelRegistryPort : RuntimeModelRegistryPort {
    override fun registerModelPath(modelId: String, absolutePath: String) = Unit

    override fun registeredModelPath(modelId: String): String? {
        return when (modelId) {
            ModelCatalog.QWEN_3_5_0_8B_Q4 -> "/tmp/qwen-0.8b.gguf"
            else -> null
        }
    }

    override fun registerModelMetadata(modelId: String, metadata: ModelRuntimeMetadata) = Unit

    override fun cachedModelMetadata(modelId: String): ModelRuntimeMetadata? {
        if (modelId != ModelCatalog.QWEN_3_5_0_8B_Q4) {
            return null
        }
        return ModelRuntimeMetadata(
            layerCount = 22,
            sizeBytes = 1_200_000_000L,
            contextLength = 4096,
            embeddingSize = 2048,
            headCountKv = 8,
            keyLength = 128,
            valueLength = 128,
            vocabSize = 151_936,
            architecture = "qwen3",
        )
    }

    override fun cachedModelLayerCount(modelId: String): Int? = cachedModelMetadata(modelId)?.layerCount

    override fun cachedModelSizeBytes(modelId: String): Long? = cachedModelMetadata(modelId)?.sizeBytes

    override fun cachedEstimatedMaxGpuLayers(modelId: String, nCtx: Int): Int? = 4
}

private class SendResidencyPort(
    private val state: RuntimeResidencyState,
    private val prefixCacheDiagnosticsLine: String? = null,
) : RuntimeResidencyPort {
    override fun updateResidencySlot(slotId: String?, expiresAtEpochMs: Long?) = Unit

    override fun residencyState(): RuntimeResidencyState = state

    override fun prefixCacheDiagnosticsLine(): String? = prefixCacheDiagnosticsLine

    override fun recordWarmup(durationMs: Long) = Unit
}

private class SendStaticRoutingModule(
    private val modelId: String = ModelCatalog.QWEN_3_5_0_8B_Q4,
) : RoutingModule {
    override fun selectModel(taskType: String, deviceState: DeviceState): String = modelId

    override fun selectContextBudget(taskType: String, deviceState: DeviceState): Int = 512
}

private class SendPolicyModule(
    private val allowedEvents: Set<String>,
) : PolicyModule {
    override fun isNetworkAllowedForAction(action: String): Boolean = false

    override fun getRetentionWindowDays(): Int = 30

    override fun enforceDataBoundary(eventType: String): Boolean = allowedEvents.contains(eventType)
}

private class NoopObservabilityModule : ObservabilityModule {
    override fun recordLatencyMetric(name: String, valueMs: Double) = Unit

    override fun recordThermalSnapshot(level: Int) = Unit

    override fun exportLocalDiagnostics(): String = "diag"
}

private fun permissivePolicy(): SendPolicyModule {
    return SendPolicyModule(
        allowedEvents = setOf(
            "routing.model_select",
            "memory.write_user_turn",
            "inference.generate",
            "observability.record_runtime_metrics",
        ),
    )
}

private fun sendRuntimeConfig(
    streamContractV2Enabled: Boolean,
): RuntimeConfig {
    val payload0 = "payload-0".encodeToByteArray()
    val payload1 = "payload-1".encodeToByteArray()
    val payloadLlama = "payload-llama".encodeToByteArray()
    return RuntimeConfig(
        artifactPayloadByModelId = mapOf(
            ModelCatalog.QWEN_3_5_0_8B_Q4 to payload0,
            ModelCatalog.QWEN3_1_7B_Q4_K_M to payload1,
            ModelCatalog.LLAMA_3_2_1B_Q4_K_M to payloadLlama,
        ),
        artifactFilePathByModelId = mapOf(
            ModelCatalog.QWEN_3_5_0_8B_Q4 to "",
            ModelCatalog.QWEN3_1_7B_Q4_K_M to "",
            ModelCatalog.LLAMA_3_2_1B_Q4_K_M to "",
        ),
        artifactSha256ByModelId = mapOf(
            ModelCatalog.QWEN_3_5_0_8B_Q4 to sendSha256(payload0),
            ModelCatalog.QWEN3_1_7B_Q4_K_M to sendSha256(payload1),
            ModelCatalog.LLAMA_3_2_1B_Q4_K_M to sendSha256(payloadLlama),
        ),
        artifactProvenanceIssuerByModelId = mapOf(
            ModelCatalog.QWEN_3_5_0_8B_Q4 to "internal-release",
            ModelCatalog.QWEN3_1_7B_Q4_K_M to "internal-release",
            ModelCatalog.LLAMA_3_2_1B_Q4_K_M to "internal-release",
        ),
        artifactProvenanceSignatureByModelId = mapOf(
            ModelCatalog.QWEN_3_5_0_8B_Q4 to "sig-0",
            ModelCatalog.QWEN3_1_7B_Q4_K_M to "sig-1",
            ModelCatalog.LLAMA_3_2_1B_Q4_K_M to "sig-llama",
        ),
        runtimeCompatibilityTag = "android-arm64-v8a",
        requireNativeRuntimeForStartupChecks = false,
        prefixCacheEnabled = true,
        prefixCacheStrict = false,
        responseCacheTtlSec = 120L,
        responseCacheMaxEntries = 64,
        streamContractV2Enabled = streamContractV2Enabled,
    )
}

private fun sendSha256(payload: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(payload)
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
