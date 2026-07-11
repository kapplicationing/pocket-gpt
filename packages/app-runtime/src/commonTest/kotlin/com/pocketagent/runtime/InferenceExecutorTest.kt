package com.pocketagent.runtime

import com.pocketagent.inference.InferenceModule
import com.pocketagent.inference.InferenceRequest
import com.pocketagent.nativebridge.BridgeError
import com.pocketagent.nativebridge.CachePolicy
import com.pocketagent.nativebridge.CacheAwareGenerationPort
import com.pocketagent.nativebridge.GenerationFinishReason
import com.pocketagent.nativebridge.GenerationResult
import com.pocketagent.nativebridge.ManagedRuntimePort
import com.pocketagent.nativebridge.ModelLifecycleEvent
import com.pocketagent.nativebridge.ModelLifecycleState
import com.pocketagent.nativebridge.RuntimeBackend
import com.pocketagent.nativebridge.RuntimeGenerationConfig
import com.pocketagent.nativebridge.RuntimeInferencePorts
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InferenceExecutorTest {
    @Test
    fun `executor rejects overlapping generation before second native call`() {
        val native = BlockingCacheAwareGenerationPort()
        val executor = InferenceExecutor(
            inferenceModule = FakeInferenceModule(emptyList()),
            runtimeConfig = testRuntimeConfig(),
            runtimeInferencePorts = RuntimeInferencePorts(cacheAwareGeneration = native),
        )
        val workers = Executors.newSingleThreadExecutor()
        try {
            val first = workers.submit<InferenceExecutionResult> {
                executor.execute(
                    sessionId = "session-a",
                    requestId = "request-a",
                    request = InferenceRequest(prompt = "prompt-a", maxTokens = 8),
                    cacheKey = null,
                    cachePolicy = CachePolicy.OFF,
                    stopSequences = emptyList(),
                    onToken = {},
                )
            }
            assertTrue(native.generationStarted.await(2, TimeUnit.SECONDS))

            val failure = assertFailsWith<RuntimeGenerationFailureException> {
                executor.execute(
                    sessionId = "session-b",
                    requestId = "request-b",
                    request = InferenceRequest(prompt = "prompt-b", maxTokens = 8),
                    cacheKey = null,
                    cachePolicy = CachePolicy.OFF,
                    stopSequences = emptyList(),
                    onToken = {},
                )
            }

            assertEquals(RUNTIME_BUSY_GENERATION_CODE, failure.errorCode)
            assertEquals(1, native.generateCalls.get())
            native.releaseGeneration.countDown()
            first.get(2, TimeUnit.SECONDS)
            assertTrue(executor.isIdle())
        } finally {
            native.releaseGeneration.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun `executor trims stop sequence from final text and suppresses trailing deltas`() {
        val fakeInference = FakeInferenceModule(
            tokens = listOf("hello ", "world<|im_end|>", "ignored"),
        )
        val executor = InferenceExecutor(
            inferenceModule = fakeInference,
            runtimeConfig = testRuntimeConfig(),
        )
        val streamed = mutableListOf<String>()

        val result = executor.execute(
            sessionId = "s1",
            requestId = "r1",
            request = InferenceRequest(prompt = "prompt", maxTokens = 32),
            cacheKey = null,
            cachePolicy = CachePolicy.OFF,
            stopSequences = listOf("<|im_end|>"),
            onToken = { token -> streamed += token },
        )

        assertEquals("hello world", result.text)
        assertEquals("stop_sequence", result.finishReason)
        assertEquals(listOf("hello "), streamed)
    }

    @Test
    fun `cancel by request respects stream contract flag`() {
        val executor = InferenceExecutor(
            inferenceModule = FakeInferenceModule(emptyList()),
            runtimeConfig = testRuntimeConfig(streamContractV2Enabled = false),
        )
        assertFalse(executor.cancelByRequest("req"))
        assertFalse(executor.cancelBySession("session"))
        assertEquals("STREAM_CONTRACT_V2_DISABLED", executor.cancelByRequestDetailed("req").code)
    }

    @Test
    fun `executeWithImages returns error when managedRuntime is null`() {
        val executor = InferenceExecutor(
            inferenceModule = FakeInferenceModule(emptyList()),
            runtimeConfig = testRuntimeConfig(),
            runtimeInferencePorts = RuntimeInferencePorts(managedRuntime = null),
        )
        val result = executor.executeWithImages(
            sessionId = "s1",
            requestId = "r1",
            prompt = "describe",
            imagePaths = listOf("/tmp/img.jpg"),
            maxTokens = 32,
            stopSequences = emptyList(),
            onToken = {},
        )
        assertEquals("error:multimodal_not_available", result.finishReason)
        assertEquals("MULTIMODAL_NOT_AVAILABLE", result.bridgeErrorCode)
        assertEquals(0, result.tokenCount)
    }

    @Test
    fun `executeWithImages streams tokens and returns result on success`() {
        val fakeRuntime = FakeManagedRuntime(
            tokens = listOf("The ", "image ", "shows ", "a cat."),
            finishReason = GenerationFinishReason.COMPLETED,
        )
        val executor = InferenceExecutor(
            inferenceModule = FakeInferenceModule(emptyList()),
            runtimeConfig = testRuntimeConfig(),
            runtimeInferencePorts = RuntimeInferencePorts(managedRuntime = fakeRuntime),
        )
        val streamed = mutableListOf<String>()

        val result = executor.executeWithImages(
            sessionId = "s1",
            requestId = "r1",
            prompt = "describe this",
            imagePaths = listOf("/tmp/img.jpg"),
            maxTokens = 64,
            stopSequences = emptyList(),
            onToken = { streamed += it },
        )

        assertEquals("completed", result.finishReason)
        assertEquals(4, result.tokenCount)
        assertEquals(listOf("The ", "image ", "shows ", "a cat."), streamed)
        assertTrue(result.text.contains("cat"))
    }

    @Test
    fun `executeWithImages detects stop sequence`() {
        val fakeRuntime = FakeManagedRuntime(
            tokens = listOf("hello", "<|im_end|>", "extra"),
            finishReason = GenerationFinishReason.CANCELLED,
            cancelledFlag = true,
        )
        val executor = InferenceExecutor(
            inferenceModule = FakeInferenceModule(emptyList()),
            runtimeConfig = testRuntimeConfig(),
            runtimeInferencePorts = RuntimeInferencePorts(managedRuntime = fakeRuntime),
        )
        val streamed = mutableListOf<String>()

        val result = executor.executeWithImages(
            sessionId = "s1",
            requestId = "r1",
            prompt = "prompt",
            imagePaths = listOf("/tmp/img.jpg"),
            maxTokens = 32,
            stopSequences = listOf("<|im_end|>"),
            onToken = { streamed += it },
        )

        assertEquals("stop_sequence", result.finishReason)
        assertEquals("hello", result.text)
        assertEquals(listOf("hello"), streamed)
    }

    @Test
    fun `executeWithImages throws on generation failure`() {
        val fakeRuntime = FakeManagedRuntime(
            tokens = emptyList(),
            finishReason = GenerationFinishReason.ERROR,
            errorCode = "OOM",
        )
        val executor = InferenceExecutor(
            inferenceModule = FakeInferenceModule(emptyList()),
            runtimeConfig = testRuntimeConfig(),
            runtimeInferencePorts = RuntimeInferencePorts(managedRuntime = fakeRuntime),
        )

        assertFailsWith<RuntimeGenerationFailureException> {
            executor.executeWithImages(
                sessionId = "s1",
                requestId = "r1",
                prompt = "prompt",
                imagePaths = listOf("/tmp/img.jpg"),
                maxTokens = 32,
                stopSequences = emptyList(),
                onToken = {},
            )
        }
    }

    @Test
    fun `executeWithImages cleans up active state even on failure`() {
        val fakeRuntime = FakeManagedRuntime(
            tokens = emptyList(),
            finishReason = GenerationFinishReason.ERROR,
        )
        val executor = InferenceExecutor(
            inferenceModule = FakeInferenceModule(emptyList()),
            runtimeConfig = testRuntimeConfig(),
            runtimeInferencePorts = RuntimeInferencePorts(managedRuntime = fakeRuntime),
        )

        runCatching {
            executor.executeWithImages(
                sessionId = "s1",
                requestId = "r1",
                prompt = "prompt",
                imagePaths = listOf("/tmp/img.jpg"),
                maxTokens = 32,
                stopSequences = emptyList(),
                onToken = {},
            )
        }

        assertTrue(executor.isIdle())
    }
}

private class BlockingCacheAwareGenerationPort : CacheAwareGenerationPort {
    val generationStarted = CountDownLatch(1)
    val releaseGeneration = CountDownLatch(1)
    val generateCalls = AtomicInteger(0)

    override fun generateStreamWithCache(
        requestId: String,
        request: InferenceRequest,
        cacheKey: String?,
        cachePolicy: CachePolicy,
        onToken: (String) -> Unit,
    ): GenerationResult {
        generateCalls.incrementAndGet()
        generationStarted.countDown()
        releaseGeneration.await(2, TimeUnit.SECONDS)
        onToken("ok")
        return GenerationResult(
            finishReason = GenerationFinishReason.COMPLETED,
            tokenCount = 1,
            firstTokenMs = 1L,
            totalMs = 2L,
            cancelled = false,
        )
    }

    override fun cancelGeneration(requestId: String): Boolean = true

    override fun actualGpuLayers(): Int? = null

    override fun actualDraftGpuLayers(): Int? = null

    override fun lastGpuLoadRetryCount(): Int? = null
}

private class FakeInferenceModule(
    private val tokens: List<String>,
) : InferenceModule {
    override fun listAvailableModels(): List<String> = listOf("fake")

    override fun loadModel(modelId: String): Boolean = true

    override fun generateStream(request: InferenceRequest, onToken: (String) -> Unit) {
        tokens.forEach { token -> onToken(token) }
    }

    override fun unloadModel() = Unit
}

private class FakeManagedRuntime(
    private val tokens: List<String>,
    private val finishReason: GenerationFinishReason,
    private val cancelledFlag: Boolean = false,
    private val errorCode: String? = null,
) : ManagedRuntimePort {
    override fun loadModel(modelId: String, modelVersion: String?, strictGpuOffload: Boolean) = true
    override fun setRuntimeGenerationConfig(config: RuntimeGenerationConfig) = Unit
    override fun supportsGpuOffload() = false
    override fun runtimeBackend() = RuntimeBackend.NATIVE_JNI
    override fun lastBridgeError(): BridgeError? = null
    override fun currentModelLifecycleState() = ModelLifecycleEvent(
        state = ModelLifecycleState.LOADED,
        modelId = "fake",
    )
    override fun observeModelLifecycleState(listener: (ModelLifecycleEvent) -> Unit): AutoCloseable {
        return AutoCloseable {}
    }
    override fun currentRssMb(): Double? = null
    override fun isRuntimeReleased() = false
    override fun isMultimodalEnabled() = true
    override fun generateWithImages(
        requestId: String,
        prompt: String,
        imagePaths: List<String>,
        maxTokens: Int,
        onToken: (String) -> Unit,
    ): GenerationResult {
        tokens.forEach { token -> onToken(token) }
        return GenerationResult(
            finishReason = finishReason,
            tokenCount = tokens.size,
            firstTokenMs = 10L,
            totalMs = 100L,
            cancelled = cancelledFlag,
            errorCode = errorCode,
        )
    }
}

private fun testRuntimeConfig(streamContractV2Enabled: Boolean = true): RuntimeConfig {
    return RuntimeConfig(
        artifactPayloadByModelId = emptyMap(),
        artifactFilePathByModelId = emptyMap(),
        artifactSha256ByModelId = emptyMap(),
        artifactProvenanceIssuerByModelId = emptyMap(),
        artifactProvenanceSignatureByModelId = emptyMap(),
        runtimeCompatibilityTag = "test",
        requireNativeRuntimeForStartupChecks = false,
        prefixCacheEnabled = false,
        prefixCacheStrict = false,
        responseCacheTtlSec = 0L,
        responseCacheMaxEntries = 0,
        streamContractV2Enabled = streamContractV2Enabled,
    )
}
