package com.pocketagent.android.runtime

import com.pocketagent.nativebridge.BridgeError
import com.pocketagent.nativebridge.CachePolicy
import com.pocketagent.nativebridge.GenerationFinishReason
import com.pocketagent.nativebridge.GenerationResult
import com.pocketagent.nativebridge.RuntimeBackend
import com.pocketagent.nativebridge.RuntimeGenerationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteLlamaCppRuntimeBridgeTest {
    @Test
    fun `bridge reloads active model after remote epoch changes`() {
        val transport = FakeRemoteRuntimeTransport()
        val bridge = RemoteLlamaCppRuntimeBridge(transport)

        bridge.setRuntimeGenerationConfig(
            RuntimeGenerationConfig.default().copy(
                gpuEnabled = true,
                gpuLayers = 1,
            ),
        )
        assertTrue(bridge.loadModel("model-a", "/tmp/model-a.gguf"))

        transport.epochValue += 1L
        val result = bridge.generate(
            requestId = "req-1",
            prompt = "hello",
            maxTokens = 8,
            cacheKey = null,
            cachePolicy = CachePolicy.OFF,
        ) {}

        assertTrue(result.success)
        assertEquals(2, transport.loadModelCalls)
        assertEquals(3, transport.setConfigCalls)
    }

    @Test
    fun `bridge surfaces remote process death as structured error`() {
        val transport = FakeRemoteRuntimeTransport(
            nextGenerationResult = GenerationResult(
                finishReason = GenerationFinishReason.ERROR,
                tokenCount = 0,
                firstTokenMs = -1L,
                totalMs = 0L,
                cancelled = false,
                errorCode = "REMOTE_PROCESS_DIED",
            ),
            nextLastError = BridgeError("REMOTE_PROCESS_DIED", "runtime process disconnected"),
        )
        val bridge = RemoteLlamaCppRuntimeBridge(transport)

        assertTrue(bridge.loadModel("model-a", "/tmp/model-a.gguf"))
        val result = bridge.generate(
            requestId = "req-2",
            prompt = "hello",
            maxTokens = 8,
            cacheKey = null,
            cachePolicy = CachePolicy.OFF,
        ) {}

        assertFalse(result.success)
        assertEquals("REMOTE_PROCESS_DIED", result.errorCode)
        assertEquals("REMOTE_PROCESS_DIED", bridge.lastError()?.code)
    }

    @Test
    fun `bridge surfaces remote timeout as structured error`() {
        val transport = FakeRemoteRuntimeTransport(
            nextGenerationResult = GenerationResult(
                finishReason = GenerationFinishReason.ERROR,
                tokenCount = 0,
                firstTokenMs = -1L,
                totalMs = 0L,
                cancelled = false,
                errorCode = "REMOTE_TIMEOUT",
            ),
            nextLastError = BridgeError("REMOTE_TIMEOUT", "stream result timeout"),
        )
        val bridge = RemoteLlamaCppRuntimeBridge(transport)

        assertTrue(bridge.loadModel("model-a", "/tmp/model-a.gguf"))
        val result = bridge.generate(
            requestId = "req-timeout",
            prompt = "hello",
            maxTokens = 8,
            cacheKey = null,
            cachePolicy = CachePolicy.OFF,
        ) {}

        assertFalse(result.success)
        assertEquals("REMOTE_TIMEOUT", result.errorCode)
        assertEquals("REMOTE_TIMEOUT", bridge.lastError()?.code)
    }

    @Test
    fun `runtime backend reports remote service when ping succeeds`() {
        val bridge = RemoteLlamaCppRuntimeBridge(FakeRemoteRuntimeTransport())
        assertEquals(RuntimeBackend.REMOTE_ANDROID_SERVICE, bridge.runtimeBackend())
    }

    @Test
    fun `close unloads model and closes remote transport`() {
        val transport = FakeRemoteRuntimeTransport()
        val bridge = RemoteLlamaCppRuntimeBridge(transport)
        assertTrue(bridge.loadModel("model-a", "/tmp/model-a.gguf"))

        val result = bridge.closeRuntime(timeoutMs = 1_000L)

        assertTrue(result.success)
        assertEquals(1, transport.unloadModelCalls)
        assertEquals(1, transport.closeCalls)
        assertNull(bridge.getLoadedModel())
    }

    @Test
    fun `cancel preserves structured wrong-owner error`() {
        val transport = FakeRemoteRuntimeTransport(
            nextCancellationResult = RemoteCancellationResult(
                cancelled = false,
                error = BridgeError(
                    REMOTE_ERROR_NOT_GENERATION_OWNER,
                    "cancel_request_not_owner",
                ),
            ),
        )
        val bridge = RemoteLlamaCppRuntimeBridge(transport)

        assertFalse(bridge.cancelGeneration("request-b"))
        assertEquals(REMOTE_ERROR_NOT_GENERATION_OWNER, bridge.lastError()?.code)
        assertEquals("cancel_request_not_owner", bridge.lastError()?.detail)
    }

    @Test
    fun `runtime mode defaults to in process and honors explicit override`() {
        assertEquals("in_process", resolveAndroidRuntimeMode(emptyMap()))
        assertEquals("in_process", resolveAndroidRuntimeMode(emptyMap()))
        assertEquals(
            "in_process",
            resolveAndroidRuntimeMode(
                mapOf("POCKETGPT_ANDROID_RUNTIME_MODE" to "in_process"),
            ),
        )
        assertEquals(
            "remote",
            resolveAndroidRuntimeMode(
                mapOf("POCKETGPT_ANDROID_RUNTIME_MODE" to "remote"),
            ),
        )
        assertEquals(
            "in_process",
            resolveAndroidRuntimeMode(
                mapOf("POCKETGPT_ANDROID_RUNTIME_MODE" to "unexpected"),
            ),
        )
    }
}

private class FakeRemoteRuntimeTransport(
    var epochValue: Long = 1L,
    private val pingOk: Boolean = true,
    private val loadModelOk: Boolean = true,
    private val gpuSupported: Boolean = true,
    private val nextDiagnosticsJson: String? = null,
    private val nextGenerationResult: GenerationResult = GenerationResult(
        finishReason = GenerationFinishReason.COMPLETED,
        tokenCount = 1,
        firstTokenMs = 1L,
        totalMs = 2L,
        cancelled = false,
    ),
    private val nextLastError: BridgeError? = null,
    private val nextCancellationResult: RemoteCancellationResult =
        RemoteCancellationResult(cancelled = true),
) : RemoteRuntimeTransport {
    var setConfigCalls: Int = 0
    var loadModelCalls: Int = 0
    var unloadModelCalls: Int = 0
    var closeCalls: Int = 0

    override fun epoch(): Long = epochValue

    override fun ping(): Boolean = pingOk

    override fun setRuntimeGenerationConfig(config: RuntimeGenerationConfig): Boolean {
        setConfigCalls += 1
        return true
    }

    override fun getRuntimeGenerationConfig(): RuntimeGenerationConfig = RuntimeGenerationConfig.default()

    override fun listAvailableModels(): List<String> = listOf("model-a")

    override fun loadModel(modelId: String, modelPath: String?): Boolean {
        loadModelCalls += 1
        return loadModelOk
    }

    override fun unloadModel(): Boolean {
        unloadModelCalls += 1
        return true
    }

    override fun close(): Boolean {
        closeCalls += 1
        return true
    }

    override fun generate(
        requestId: String,
        prompt: String,
        maxTokens: Int,
        cacheKey: String?,
        cachePolicy: CachePolicy,
        onToken: (String) -> Unit,
    ): GenerationResult {
        if (nextGenerationResult.success) {
            onToken("token")
        }
        return nextGenerationResult
    }

    override fun cancelGeneration(requestId: String?): RemoteCancellationResult = nextCancellationResult

    override fun supportsGpuOffload(): Boolean = gpuSupported

    override fun backendDiagnosticsJson(): String? = nextDiagnosticsJson

    override fun lastError(): BridgeError? = nextLastError

    override fun runGpuProbe(request: GpuProbeRequest, timeoutMs: Long): GpuProbeResult {
        return GpuProbeResult(
            status = GpuProbeStatus.QUALIFIED,
            maxStableGpuLayers = 1,
        )
    }
}
