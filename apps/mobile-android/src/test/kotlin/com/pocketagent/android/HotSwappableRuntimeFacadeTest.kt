package com.pocketagent.android

import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.inference.DeviceState
import com.pocketagent.runtime.InteractionMessage
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.MvpRuntimeFacade
import com.pocketagent.runtime.RuntimeResourceControl
import com.pocketagent.runtime.RuntimeOperationCoordinator
import com.pocketagent.runtime.RuntimeCloseAdmissionResult
import com.pocketagent.runtime.GenerationAdmissionResult
import com.pocketagent.runtime.GenerationLease
import com.pocketagent.runtime.CancellationResult
import com.pocketagent.runtime.RuntimeWarmupSupport
import com.pocketagent.runtime.StreamChatRequestV2
import com.pocketagent.runtime.WarmupResult
import com.pocketagent.nativebridge.ModelLifecycleEvent
import com.pocketagent.nativebridge.RuntimeCloseResult
import com.pocketagent.nativebridge.RuntimeLifetimePort
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HotSwappableRuntimeFacadeTest {
    @Test
    fun `failed old close retains old delegate and closes unused new delegate`() {
        val first = StubFacade(
            sessionId = "session-old",
            closeResult = RuntimeCloseResult.rejected("CLOSE_REJECTED"),
        )
        val second = StubFacade(sessionId = "session-new")
        val hotSwap = HotSwappableRuntimeFacade(first)

        val replacement = hotSwap.replace(second)

        assertFalse(replacement.success)
        assertEquals("session-old", hotSwap.createSession().value)
        assertEquals(1, first.closeCalls)
        assertEquals(1, second.closeCalls)
    }

    @Test
    fun `replacement close rejection leaves old runtime usable after owner finishes`() {
        val first = CoordinatedLifetimeFacade()
        val active = first.beginGeneration()
        val second = StubFacade(sessionId = "session-new")
        val hotSwap = HotSwappableRuntimeFacade(first)

        val replacement = hotSwap.replace(second)
        active.close()

        assertFalse(replacement.success)
        assertEquals("session-old", hotSwap.createSession().value)
        assertEquals(1, second.closeCalls)
    }

    @Test
    fun `replace closes blocking collected flow before publishing new delegate`() {
        val order = mutableListOf<String>()
        val first = BlockingFlowFacade(order)
        val second = StubFacade(sessionId = "session-new")
        val hotSwap = HotSwappableRuntimeFacade(first)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val collection = executor.submit {
                runBlocking { hotSwap.streamChat(testStreamRequest()).toList() }
            }
            assertTrue(first.flowStarted.await(2, TimeUnit.SECONDS))

            val replacement = executor.submit<RuntimeReplacementResult> { hotSwap.replace(second) }
            assertTrue(replacement.get(2, TimeUnit.SECONDS).success)
            collection.get(2, TimeUnit.SECONDS)

            assertEquals(listOf("flow_started", "close_old", "flow_finished"), order)
            assertEquals("session-new", hotSwap.createSession().value)
        } finally {
            first.releaseFlow.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `successful close commits replacement while old collector finishes slowly`() {
        val first = SlowTerminalFlowFacade()
        val second = StubFacade(sessionId = "session-new")
        val hotSwap = HotSwappableRuntimeFacade(first)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val collection = executor.submit {
                runBlocking { hotSwap.streamChat(testStreamRequest()).toList() }
            }
            assertTrue(first.flowStarted.await(2, TimeUnit.SECONDS))

            val replacement = executor.submit<RuntimeReplacementResult> { hotSwap.replace(second) }

            assertTrue(replacement.get(2, TimeUnit.SECONDS).success)
            assertEquals("session-new", hotSwap.createSession().value)
            assertFalse(collection.isDone, "terminal collection may outlive the closed runtime")
            first.releaseFlow.countDown()
            collection.get(2, TimeUnit.SECONDS)
        } finally {
            first.releaseFlow.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `irreversible close warning still commits new delegate`() {
        val first = StubFacade(
            sessionId = "session-old",
            closeResult = RuntimeCloseResult.terminated("DISPATCHER_JOIN_TIMEOUT"),
        )
        val second = StubFacade(sessionId = "session-new")
        val hotSwap = HotSwappableRuntimeFacade(first)

        val replacement = hotSwap.replace(second)

        assertTrue(replacement.success)
        assertEquals("DISPATCHER_JOIN_TIMEOUT", replacement.code)
        assertEquals("session-new", hotSwap.createSession().value)
        assertEquals(0, second.closeCalls)
    }

    @Test
    fun `close exception fails closed by publishing new delegate`() {
        val first = ThrowingCloseFacade()
        val second = StubFacade(sessionId = "session-new")
        val hotSwap = HotSwappableRuntimeFacade(first)

        val replacement = hotSwap.replace(second)

        assertTrue(replacement.success)
        assertEquals("OLD_RUNTIME_CLOSE_EXCEPTION", replacement.code)
        assertEquals("session-new", hotSwap.createSession().value)
        assertEquals(0, second.closeCalls)
    }

    @Test
    fun `replace waits for in-flight call and subsequent calls use new delegate`() {
        val callStarted = CountDownLatch(1)
        val releaseCall = CountDownLatch(1)
        val first = BlockingFacade(
            sessionId = "session-old",
            callStarted = callStarted,
            releaseCall = releaseCall,
        )
        val second = StubFacade(sessionId = "session-new")
        val hotSwap = HotSwappableRuntimeFacade(first)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val inFlight = executor.submit<SessionId> { hotSwap.createSession() }
            assertTrue(callStarted.await(2, TimeUnit.SECONDS))

            val replaceFuture = executor.submit<RuntimeReplacementResult> { hotSwap.replace(second) }
            assertFailsWith<TimeoutException> {
                replaceFuture.get(100, TimeUnit.MILLISECONDS)
            }
            assertFalse(replaceFuture.isDone, "replace should wait until in-flight read call completes")

            releaseCall.countDown()
            assertEquals("session-old", inFlight.get(2, TimeUnit.SECONDS).value)
            replaceFuture.get(2, TimeUnit.SECONDS)

            assertEquals("session-new", hotSwap.createSession().value)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `eviction delegates to resource-control facade`() {
        val hotSwap = HotSwappableRuntimeFacade(ResourceControlStubFacade())

        val evicted = (hotSwap as RuntimeResourceControl).evictResidentModel("trim_memory")

        assertTrue(evicted)
    }

    @Test
    fun `warmup delegates to warmup-capable facade`() {
        val hotSwap = HotSwappableRuntimeFacade(WarmupStubFacade())

        val result = (hotSwap as RuntimeWarmupSupport).warmupActiveModel()

        assertTrue(result.warmed)
        assertTrue(result.attempted)
    }
}

private open class StubFacade(
    private val sessionId: String = "session-1",
    private val closeResult: RuntimeCloseResult = RuntimeCloseResult.closed(),
) : MvpRuntimeFacade, RuntimeLifetimePort {
    private var routingMode: RoutingMode = RoutingMode.AUTO
    var closeCalls: Int = 0

    override fun createSession(): SessionId = SessionId(sessionId)

    override fun streamChat(request: StreamChatRequestV2): Flow<ChatStreamEvent> = emptyFlow()

    override fun cancelGeneration(sessionId: SessionId): Boolean = true

    override fun cancelGenerationByRequest(requestId: String): Boolean = true

    override fun runTool(toolName: String, jsonArgs: String): String = "tool:$toolName"

    override fun analyzeImage(imagePath: String, prompt: String): String = "image:$imagePath"

    override fun exportDiagnostics(): String = "diag"

    override fun setRoutingMode(mode: RoutingMode) {
        routingMode = mode
    }

    override fun getRoutingMode(): RoutingMode = routingMode

    override fun runStartupChecks(): List<String> = emptyList()

    override fun restoreSession(sessionId: SessionId, turns: List<Turn>) = Unit

    override fun deleteSession(sessionId: SessionId): Boolean = true

    open override fun closeRuntime(timeoutMs: Long): RuntimeCloseResult {
        closeCalls += 1
        return closeResult
    }
}

private class BlockingFlowFacade(
    private val order: MutableList<String>,
) : StubFacade() {
    val flowStarted = CountDownLatch(1)
    val releaseFlow = CountDownLatch(1)

    override fun streamChat(request: StreamChatRequestV2): Flow<ChatStreamEvent> = flow {
        synchronized(order) { order += "flow_started" }
        flowStarted.countDown()
        releaseFlow.await(2, TimeUnit.SECONDS)
        synchronized(order) { order += "flow_finished" }
    }

    override fun closeRuntime(timeoutMs: Long): RuntimeCloseResult {
        synchronized(order) { order += "close_old" }
        releaseFlow.countDown()
        return RuntimeCloseResult.closed()
    }
}

private class SlowTerminalFlowFacade : StubFacade() {
    val flowStarted = CountDownLatch(1)
    val releaseFlow = CountDownLatch(1)

    override fun streamChat(request: StreamChatRequestV2): Flow<ChatStreamEvent> = flow {
        flowStarted.countDown()
        releaseFlow.await(2, TimeUnit.SECONDS)
    }
}

private class CoordinatedLifetimeFacade : StubFacade(sessionId = "session-old") {
    private val operations = RuntimeOperationCoordinator()

    fun beginGeneration(): GenerationLease {
        return (operations.tryAcquireGeneration("request-active", "session-old") as GenerationAdmissionResult.Acquired)
            .lease
    }

    override fun createSession(): SessionId {
        val admission = operations.tryAcquireGeneration("request-next", "session-old")
        check(admission is GenerationAdmissionResult.Acquired)
        admission.lease.close()
        return super.createSession()
    }

    override fun closeRuntime(timeoutMs: Long): RuntimeCloseResult {
        return when (
            val admission = operations.closePermanently(timeoutMs) { requestId ->
                CancellationResult(false, "CANCEL_REJECTED", "requestId=$requestId")
            }
        ) {
            RuntimeCloseAdmissionResult.Ready -> RuntimeCloseResult.closed()
            is RuntimeCloseAdmissionResult.Rejected -> RuntimeCloseResult.rejected(admission.code, admission.detail)
        }
    }
}

private class ThrowingCloseFacade : StubFacade(sessionId = "session-old") {
    override fun closeRuntime(timeoutMs: Long): RuntimeCloseResult {
        error("close exploded before teardown")
    }
}

private class BlockingFacade(
    sessionId: String,
    private val callStarted: CountDownLatch,
    private val releaseCall: CountDownLatch,
) : StubFacade(sessionId) {
    override fun createSession(): SessionId {
        callStarted.countDown()
        releaseCall.await(2, TimeUnit.SECONDS)
        return super.createSession()
    }
}

private class ResourceControlStubFacade : StubFacade(), RuntimeResourceControl {
    override fun evictResidentModel(reason: String): Boolean = true

    override fun exportDiagnosticsJson(): String? = null

    override fun currentModelLifecycleEvent(): ModelLifecycleEvent? = null

    override fun observeModelLifecycleEvents(listener: (ModelLifecycleEvent) -> Unit): AutoCloseable {
        return AutoCloseable { }
    }
}

private class WarmupStubFacade : StubFacade(), RuntimeWarmupSupport {
    override fun warmupActiveModel(): WarmupResult {
        return WarmupResult(
            attempted = true,
            warmed = true,
            residentHit = false,
            loadDurationMs = 5L,
            warmupDurationMs = 5L,
        )
    }
}

private fun testStreamRequest(): StreamChatRequestV2 {
    return StreamChatRequestV2(
        sessionId = SessionId("session-1"),
        messages = emptyList<InteractionMessage>(),
        taskType = "short_text",
        deviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 8),
        requestId = "request-1",
    )
}
