@file:Suppress("InvalidPackageDeclaration")

// This Android source-set test intentionally exercises the shared runtime package API in place.
package com.pocketagent.runtime

import com.pocketagent.core.ChatResponse
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.inference.ArtifactVerificationStatus
import com.pocketagent.inference.DeviceState
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.runtime.ChatStreamDelta
import com.pocketagent.runtime.ModelResidencyPolicy
import com.pocketagent.runtime.PerformanceRuntimeConfig
import com.pocketagent.runtime.InteractionContentPart
import com.pocketagent.runtime.InteractionMessage
import com.pocketagent.runtime.InteractionRole
import com.pocketagent.runtime.RuntimePerformanceProfile
import com.pocketagent.runtime.RuntimeRequestContext
import com.pocketagent.runtime.SamplingOverrides
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultMvpRuntimeFacadeTest {
    @Test
    fun `stream chat emits delta and completed events`() = runTest {
        val container = FakeRuntimeContainer()
        val facade = DefaultMvpRuntimeFacade(container)
        val request = requestV2()

        val events = facade.streamChat(request).toList()
        val tokenEvents = events.filterIsInstance<ChatStreamEvent.Delta>()
        val completed = events.filterIsInstance<ChatStreamEvent.Completed>().single()
        val phases = events.filterIsInstance<ChatStreamEvent.Phase>().map { phase -> phase.phase }.toSet()

        assertTrue(events.first() is ChatStreamEvent.Started)
        assertEquals(2, tokenEvents.size)
        assertEquals(ChatStreamDelta.TextDelta("hello "), tokenEvents[0].delta)
        assertEquals(ChatStreamDelta.TextDelta("world "), tokenEvents[1].delta)
        assertEquals("response", completed.response.text)
        assertTrue(phases.contains(ChatStreamPhase.CHAT_START))
        assertTrue(phases.contains(ChatStreamPhase.MODEL_LOAD))
        assertTrue(phases.contains(ChatStreamPhase.PROMPT_PROCESSING))
        assertTrue(phases.contains(ChatStreamPhase.TOKEN_STREAM))
        assertTrue(phases.contains(ChatStreamPhase.CHAT_END))
        assertEquals("hello", container.lastUserText)
        assertEquals(64, container.lastMaxTokens)
    }

    @Test
    fun `protocol start does not signal admission before container generation ownership`() = runTest {
        val container = FakeRuntimeContainer().apply {
            sendGate = CountDownLatch(1)
        }
        val facade = DefaultMvpRuntimeFacade(container)
        val startedSeen = CompletableDeferred<Unit>()
        val admissionSeen = AtomicBoolean(false)
        val collection = backgroundScope.launch {
            facade.streamChatWithAdmission(requestV2()) {
                admissionSeen.set(true)
            }.collect { event ->
                if (event is ChatStreamEvent.Started) {
                    startedSeen.complete(Unit)
                }
            }
        }
        try {
            withTimeout(2_000L) { startedSeen.await() }
            assertFalse(admissionSeen.get())

            container.sendGate?.countDown()
            withTimeout(2_000L) {
                while (!admissionSeen.get()) {
                    yield()
                }
            }
            collection.join()
            assertTrue(admissionSeen.get())
        } finally {
            container.sendGate?.countDown()
            collection.cancel()
        }
    }

    @Test
    fun `stream chat preserves complete typed request context`() = runTest {
        val container = FakeRuntimeContainer()
        val facade = DefaultMvpRuntimeFacade(container)
        val performanceConfig = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.FAST,
            availableCpuThreads = 6,
            gpuEnabled = true,
            gpuLayers = 3,
        )
        val residencyPolicy = ModelResidencyPolicy(
            keepLoadedWhileAppForeground = false,
            idleUnloadTtlMs = 12_345L,
            warmupOnStartup = false,
            adaptiveIdleTtl = false,
        )
        val samplingOverrides = SamplingOverrides(
            temperature = 0.4f,
            topP = 0.8f,
            topK = 17,
            maxTokens = 321,
            systemPrompt = "Keep the answer compact.",
            showThinking = true,
        )
        val request = requestV2(requestId = "req-context").copy(
            deviceState = DeviceState(batteryPercent = 31, thermalLevel = 6, ramClassGb = 12),
            maxTokens = 321,
            requestTimeoutMs = 98_765L,
            previousResponseId = "response-previous",
            performanceConfig = performanceConfig,
            residencyPolicy = residencyPolicy,
            samplingOverrides = samplingOverrides,
            memoryRetention = RuntimeMemoryRetention.EPHEMERAL,
        )

        facade.streamChat(request).toList()

        assertEquals(
            RuntimeRequestContext(
                deviceState = request.deviceState,
                maxTokens = request.maxTokens,
                keepModelLoaded = true,
                requestTimeoutMs = request.requestTimeoutMs,
                requestId = request.requestId,
                previousResponseId = request.previousResponseId,
                performanceConfig = performanceConfig,
                residencyPolicy = residencyPolicy,
                samplingOverrides = samplingOverrides,
                memoryRetention = RuntimeMemoryRetention.EPHEMERAL,
            ),
            container.lastContext,
        )
        assertEquals(true, container.lastContext?.samplingOverrides?.showThinking)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `legacy scalar overload delegates into typed request context`() {
        val container = FakeRuntimeContainer()
        val performanceConfig = PerformanceRuntimeConfig.default()
        val residencyPolicy = ModelResidencyPolicy(idleUnloadTtlMs = 4_321L)

        container.sendChatMessages(
            sessionId = SessionId("legacy-session"),
            messages = emptyList(),
            taskType = "legacy-task",
            deviceState = DeviceState(batteryPercent = 44, thermalLevel = 2, ramClassGb = 6),
            maxTokens = 77,
            keepModelLoaded = true,
            onToken = {},
            requestTimeoutMs = 55_000L,
            requestId = "legacy-request",
            previousResponseId = "legacy-response",
            performanceConfig = performanceConfig,
            residencyPolicy = residencyPolicy,
        )

        assertEquals("legacy-request", container.lastContext?.requestId)
        assertEquals("legacy-response", container.lastContext?.previousResponseId)
        assertEquals(performanceConfig, container.lastContext?.performanceConfig)
        assertEquals(residencyPolicy, container.lastContext?.residencyPolicy)

        container.sendUserMessage(
            sessionId = SessionId("legacy-session"),
            userText = "legacy user message",
            taskType = "legacy-task",
            deviceState = DeviceState(batteryPercent = 55, thermalLevel = 1, ramClassGb = 8),
            maxTokens = 88,
            keepModelLoaded = false,
            onToken = {},
            requestTimeoutMs = 66_000L,
            requestId = "legacy-user-request",
            performanceConfig = performanceConfig,
            residencyPolicy = residencyPolicy,
        )

        assertEquals("legacy-user-request", container.lastContext?.requestId)
        assertEquals(88, container.lastContext?.maxTokens)
        assertEquals("legacy user message", container.lastUserText)
    }

    @Test
    fun `delegates runtime operations to container`() {
        val container = FakeRuntimeContainer()
        val facade = DefaultMvpRuntimeFacade(container)

        assertEquals("session-1", facade.createSession().value)
        assertEquals("tool:calculator", facade.runTool("calculator", """{"expression":"1+2"}"""))
        assertEquals("image:/tmp/a.jpg", facade.analyzeImage("/tmp/a.jpg", "describe"))
        assertEquals("diag=ok", facade.exportDiagnostics())
        facade.setRoutingMode(RoutingMode.QWEN3_1_7B)
        assertEquals(RoutingMode.QWEN3_1_7B, facade.getRoutingMode())
        assertEquals(listOf("check"), facade.runStartupChecks())

        val turns = listOf(Turn(role = "user", content = "hello", timestampEpochMs = 1))
        facade.restoreSession(SessionId("session-1"), turns)
        assertEquals(1, container.restoreCalls)
        assertTrue(facade.deleteSession(SessionId("session-1")))
        assertEquals(1, container.deleteCalls)
    }

    @Test
    fun `stream chat emits cancelled event on runtime cancellation`() = runTest {
        val container = FakeRuntimeContainer().apply {
            sendError = RuntimeGenerationCancelledException(requestId = "req-cancel")
        }
        val facade = DefaultMvpRuntimeFacade(container)
        val request = requestV2(requestId = "req-cancel")

        val events = facade.streamChat(request).toList()
        val cancelled = events.filterIsInstance<ChatStreamEvent.Cancelled>().single()

        assertTrue(events.first() is ChatStreamEvent.Started)
        assertEquals("cancelled", cancelled.reason)
    }

    @Test
    fun `stream chat emits failed event with bridge error code`() = runTest {
        val container = FakeRuntimeContainer().apply {
            sendError = RuntimeGenerationFailureException(
                message = "utf8 stream failure",
                errorCode = "JNI_UTF8_STREAM_ERROR",
            )
        }
        val facade = DefaultMvpRuntimeFacade(container)
        val request = requestV2(requestId = "req-fail")

        val events = facade.streamChat(request).toList()
        val failed = events.filterIsInstance<ChatStreamEvent.Failed>().single()

        assertTrue(events.first() is ChatStreamEvent.Started)
        assertEquals("jni_utf8_stream_error", failed.errorCode)
    }

    @Test
    fun `stream chat maps real checksum verification failure to reprovision recovery`() = runTest {
        val modelId = ModelCatalog.QWEN3_0_6B_Q4_K_M
        val verifier = ArtifactVerifier(
            RuntimeConfig(
                artifactPayloadByModelId = mapOf(modelId to "actual-model-payload".encodeToByteArray()),
                artifactFilePathByModelId = emptyMap(),
                artifactSha256ByModelId = mapOf(modelId to "0".repeat(64)),
                artifactProvenanceIssuerByModelId = mapOf(modelId to "internal-release"),
                artifactProvenanceSignatureByModelId = mapOf(modelId to "test-signature"),
                runtimeCompatibilityTag = "android-arm64-v8a",
                requireNativeRuntimeForStartupChecks = false,
                prefixCacheEnabled = false,
                prefixCacheStrict = false,
                responseCacheTtlSec = 0L,
                responseCacheMaxEntries = 0,
            ),
        )
        var verificationFailure: RuntimeArtifactVerificationException? = null
        val container = FakeRuntimeContainer().apply {
            beforeSend = {
                try {
                    verifier.verifyArtifactOrThrow(modelId)
                } catch (error: RuntimeArtifactVerificationException) {
                    verificationFailure = error
                    throw error
                }
            }
        }
        val facade = DefaultMvpRuntimeFacade(container)

        val events = facade.streamChat(requestV2(requestId = "req-checksum-mismatch")).toList()
        val failed = events.filterIsInstance<ChatStreamEvent.Failed>().single()

        assertEquals(ArtifactVerificationStatus.CHECKSUM_MISMATCH, verificationFailure?.verificationStatus)
        assertEquals("checksum_mismatch", verificationFailure?.errorCode)
        assertEquals("checksum_mismatch", failed.errorCode)
        assertEquals(RuntimeRecoveryDisposition.REPROVISION_MODEL, failed.recoveryDisposition)
        assertTrue(failed.message.contains("CHECKSUM_MISMATCH"))
    }

    @Test
    fun `terminal failure does not trigger duplicate cancel from awaitClose`() = runTest {
        val container = FakeRuntimeContainer().apply {
            sendError = RuntimeGenerationFailureException(
                message = "runtime failure",
                errorCode = "JNI_RUNTIME_ERROR",
            )
        }
        val facade = DefaultMvpRuntimeFacade(container)
        val request = requestV2(requestId = "req-fail-nocancel")

        val events = facade.streamChat(request).toList()

        assertTrue(events.any { it is ChatStreamEvent.Failed })
        assertEquals(0, container.cancelByRequestCalls)
        assertEquals(0, container.cancelBySessionCalls)
    }

    @Test
    fun `stale request cleanup cannot cancel newer session owner when exact cancellation misses`() = runTest {
        val container = FakeRuntimeContainer().apply {
            sendGate = CountDownLatch(1)
            cancelByRequestResult = false
            activeRequestId = "req-B"
        }
        val facade = DefaultMvpRuntimeFacade(container)

        facade.streamChat(
            requestV2(
                requestId = "req-A",
                sessionId = SessionId("shared-session"),
            ),
        ).take(1).toList()

        assertEquals(1, container.cancelByRequestCalls)
        assertEquals(listOf("req-A"), container.cancelledRequestIds)
        assertEquals(0, container.cancelBySessionCalls)
        assertEquals(emptyList<String>(), container.sessionCancelledRequestIds)
        assertEquals("req-B", container.activeRequestId)
    }

    @Test
    fun `producer cancellation propagates without becoming a failed stream event`() = runTest {
        val cancellation = CancellationException("parent cancelled")
        val container = FakeRuntimeContainer().apply {
            sendError = cancellation
        }
        val facade = DefaultMvpRuntimeFacade(container)
        val observed = mutableListOf<ChatStreamEvent>()

        val thrown = assertFailsWith<CancellationException> {
            facade.streamChat(requestV2(requestId = "req-parent-cancel")).collect(observed::add)
        }

        assertEquals(cancellation.message, thrown.message)
        assertTrue(observed.none { event -> event is ChatStreamEvent.Failed })
        assertEquals(1, container.cancelByRequestCalls)
        assertEquals(0, container.cancelBySessionCalls)
    }
}

private fun requestV2(
    requestId: String = "req-1",
    sessionId: SessionId = SessionId("session-1"),
): StreamChatRequestV2 {
    return StreamChatRequestV2(
        sessionId = sessionId,
        requestId = requestId,
        messages = listOf(
            InteractionMessage(
                role = InteractionRole.USER,
                parts = listOf(InteractionContentPart.Text("hello")),
            ),
        ),
        taskType = "short_text",
        deviceState = DeviceState(80, 3, 8),
        maxTokens = 64,
    )
}

private class FakeRuntimeContainer : RuntimeContainer {
    private var currentRoutingMode: RoutingMode = RoutingMode.AUTO
    var lastUserText: String = ""
    var lastMaxTokens: Int = 0
    var restoreCalls: Int = 0
    var deleteCalls: Int = 0
    var sendError: Throwable? = null
    var beforeSend: (() -> Unit)? = null
    var cancelByRequestCalls: Int = 0
    var cancelBySessionCalls: Int = 0
    var cancelByRequestResult: Boolean = true
    var activeRequestId: String? = null
    var sendGate: CountDownLatch? = null
    val cancelledRequestIds: MutableList<String> = mutableListOf()
    val sessionCancelledRequestIds: MutableList<String> = mutableListOf()
    var lastContext: RuntimeRequestContext? = null

    override fun createSession(): SessionId = SessionId("session-1")

    override fun sendChatMessages(
        sessionId: SessionId,
        messages: List<InteractionMessage>,
        taskType: String,
        context: RuntimeRequestContext,
        onToken: (String) -> Unit,
        onThinkingStateChanged: (Boolean) -> Unit,
        onGenerationAdmitted: () -> Unit,
    ): ChatResponse {
        beforeSend?.invoke()
        sendError?.let { throw it }
        sendGate?.await()
        onGenerationAdmitted()
        lastContext = context
        lastUserText = messages.lastOrNull { it.role == InteractionRole.USER }
            ?.parts
            ?.filterIsInstance<InteractionContentPart.Text>()
            ?.joinToString(separator = "\n") { it.text }
            .orEmpty()
        lastMaxTokens = context.maxTokens
        onToken("hello ")
        onToken("world ")
        return ChatResponse(
            sessionId = sessionId,
            modelId = "auto",
            text = "response",
            firstTokenLatencyMs = 42,
            totalLatencyMs = 75,
        )
    }

    override fun sendUserMessage(
        sessionId: SessionId,
        userText: String,
        taskType: String,
        context: RuntimeRequestContext,
        onToken: (String) -> Unit,
        onThinkingStateChanged: (Boolean) -> Unit,
    ): ChatResponse {
        return sendChatMessages(
            sessionId = sessionId,
            messages = listOf(
                InteractionMessage(
                    role = InteractionRole.USER,
                    parts = listOf(InteractionContentPart.Text(userText)),
                ),
            ),
            taskType = taskType,
            context = context,
            onToken = onToken,
            onThinkingStateChanged = onThinkingStateChanged,
        )
    }

    override fun runTool(toolName: String, jsonArgs: String): String = "tool:$toolName"

    override fun analyzeImage(imagePath: String, prompt: String): String = "image:$imagePath"

    override fun exportDiagnostics(): String = "diag=ok"

    override fun setRoutingMode(mode: RoutingMode) {
        currentRoutingMode = mode
    }

    override fun getRoutingMode(): RoutingMode = currentRoutingMode

    override fun runStartupChecks(): List<String> = listOf("check")

    override fun cancelGeneration(sessionId: SessionId): Boolean {
        cancelBySessionCalls += 1
        activeRequestId?.let(sessionCancelledRequestIds::add)
        sendGate?.countDown()
        return true
    }

    override fun cancelGenerationByRequest(requestId: String): Boolean {
        cancelByRequestCalls += 1
        cancelledRequestIds += requestId
        sendGate?.countDown()
        return cancelByRequestResult
    }

    override fun restoreSession(sessionId: SessionId, turns: List<Turn>) {
        restoreCalls += 1
    }

    override fun deleteSession(sessionId: SessionId): Boolean {
        deleteCalls += 1
        return true
    }
}
