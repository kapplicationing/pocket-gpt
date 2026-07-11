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
import com.pocketagent.nativebridge.ModelLifecycleErrorCode
import com.pocketagent.nativebridge.RuntimeCloseResult
import com.pocketagent.nativebridge.RuntimeLifetimePort
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HotSwappableRuntimeFacadeTest {
    @Test
    fun `blocking runtime close does not stall the UI dispatcher`() {
        val oldRuntime = BlockingCloseFacade()
        val newRuntime = StubFacade(sessionId = "session-new")
        val replacementExecutor = Executors.newSingleThreadExecutor()
        val uiExecutor = Executors.newSingleThreadExecutor()
        val replacementDispatcher = replacementExecutor.asCoroutineDispatcher()
        val uiDispatcher = uiExecutor.asCoroutineDispatcher()
        val uiScope = CoroutineScope(SupervisorJob() + uiDispatcher)
        val hotSwap = HotSwappableRuntimeFacade(
            initial = oldRuntime,
            replacementDispatcher = replacementDispatcher,
        )
        try {
            val replacement = uiScope.async { hotSwap.replace(newRuntime) }
            runBlocking { withTimeout(2_000L) { oldRuntime.closeStarted.await() } }

            assertFailsWith<RuntimeFacadeUnavailableException> {
                hotSwap.createSession()
            }
            assertEquals(1, hotSwap.activeGenerationCount(), "transition must report busy")
            assertEquals(
                ModelLifecycleErrorCode.BUSY_GENERATION,
                hotSwap.loadModel("model", "v1").errorCode,
            )
            val terminal = runBlocking { hotSwap.streamChat(testStreamRequest()).toList().single() }
            assertEquals(
                "RUNTIME_REPLACEMENT_IN_PROGRESS",
                assertIs<ChatStreamEvent.Failed>(terminal).errorCode,
            )
            hotSwap.setRoutingMode(RoutingMode.QWEN_0_8B)
            val uiMarker = uiExecutor.submit<String> { "ui-responsive" }
            assertEquals("ui-responsive", uiMarker.get(2, TimeUnit.SECONDS))

            oldRuntime.releaseClose.countDown()
            assertTrue(runBlocking { withTimeout(2_000L) { replacement.await() } }.success)
            assertEquals("session-new", hotSwap.createSession().value)
            assertEquals(RoutingMode.QWEN_0_8B, hotSwap.getRoutingMode())
        } finally {
            oldRuntime.releaseClose.countDown()
            uiScope.cancel()
            replacementDispatcher.close()
            uiDispatcher.close()
            replacementExecutor.shutdownNow()
            uiExecutor.shutdownNow()
        }
    }

    @Test
    fun `concurrent replacements have one deterministic lifetime owner`() = runBlocking {
        val first = BlockingCloseFacade()
        val second = StubFacade(sessionId = "session-second")
        val third = StubFacade(sessionId = "session-third")
        val replacementExecutor = Executors.newSingleThreadExecutor()
        val replacementDispatcher = replacementExecutor.asCoroutineDispatcher()
        val hotSwap = HotSwappableRuntimeFacade(
            initial = first,
            replacementDispatcher = replacementDispatcher,
        )
        try {
            val replaceSecond = async { hotSwap.replace(second) }
            withTimeout(2_000L) { first.closeStarted.await() }
            val replaceThird = async { hotSwap.replace(third) }
            yield()

            first.releaseClose.countDown()
            assertTrue(withTimeout(2_000L) { replaceSecond.await() }.success)
            assertTrue(withTimeout(2_000L) { replaceThird.await() }.success)
            assertEquals(1, second.closeCalls)
            assertEquals("session-third", hotSwap.createSession().value)
        } finally {
            first.releaseClose.countDown()
            replacementDispatcher.close()
            replacementExecutor.shutdownNow()
        }
    }

    @Test
    fun `replacement drains stale call lease before close and rejects new calls`() = runBlocking {
        val oldRuntime = BlockingFacade(
            sessionId = "session-old",
            callStarted = CountDownLatch(1),
            releaseCall = CountDownLatch(1),
        )
        val newRuntime = StubFacade(sessionId = "session-new")
        val replacementExecutor = Executors.newSingleThreadExecutor()
        val callExecutor = Executors.newSingleThreadExecutor()
        val replacementDispatcher = replacementExecutor.asCoroutineDispatcher()
        val hotSwap = HotSwappableRuntimeFacade(
            initial = oldRuntime,
            replacementDispatcher = replacementDispatcher,
        )
        try {
            val staleCall = callExecutor.submit<SessionId> { hotSwap.createSession() }
            assertTrue(oldRuntime.callStarted.await(2, TimeUnit.SECONDS))
            val replacement = async { hotSwap.replace(newRuntime) }
            withTimeout(2_000L) {
                while (hotSwap.availability() != RuntimeFacadeAvailability.REPLACING) {
                    yield()
                }
            }

            assertEquals(1L, oldRuntime.closeStarted.count, "close must wait for the stale lease")
            assertFailsWith<RuntimeFacadeUnavailableException> { hotSwap.createSession() }

            oldRuntime.releaseCall.countDown()
            assertTrue(oldRuntime.closeStarted.await(2, TimeUnit.SECONDS))
            assertEquals("session-old", staleCall.get(2, TimeUnit.SECONDS).value)
            assertTrue(withTimeout(2_000L) { replacement.await() }.success)
            assertEquals("session-new", hotSwap.createSession().value)
        } finally {
            oldRuntime.releaseCall.countDown()
            replacementDispatcher.close()
            replacementExecutor.shutdownNow()
            callExecutor.shutdownNow()
        }
    }

    @Test
    fun `failed old close retains old delegate and closes unused new delegate`() {
        val first = StubFacade(
            sessionId = "session-old",
            closeResult = RuntimeCloseResult.rejected("CLOSE_REJECTED"),
        )
        val second = StubFacade(sessionId = "session-new")
        val hotSwap = HotSwappableRuntimeFacade(first)

        val replacement = hotSwap.replaceInTest(second)

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

        val replacement = hotSwap.replaceInTest(second)
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

            val replacement = executor.submit<RuntimeReplacementResult> { hotSwap.replaceInTest(second) }
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

            val replacement = executor.submit<RuntimeReplacementResult> { hotSwap.replaceInTest(second) }

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

        val replacement = hotSwap.replaceInTest(second)

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

        val replacement = hotSwap.replaceInTest(second)

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

            val replaceFuture = executor.submit<RuntimeReplacementResult> { hotSwap.replaceInTest(second) }
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

private class BlockingCloseFacade : StubFacade(sessionId = "session-old") {
    val closeStarted = CompletableDeferred<Unit>()
    val releaseClose = CountDownLatch(1)

    override fun closeRuntime(timeoutMs: Long): RuntimeCloseResult {
        closeStarted.complete(Unit)
        releaseClose.await(timeoutMs, TimeUnit.MILLISECONDS)
        return RuntimeCloseResult.closed()
    }
}

private class BlockingFacade(
    sessionId: String,
    val callStarted: CountDownLatch,
    val releaseCall: CountDownLatch,
) : StubFacade(sessionId) {
    val closeStarted = CountDownLatch(1)

    override fun createSession(): SessionId {
        callStarted.countDown()
        releaseCall.await(2, TimeUnit.SECONDS)
        return super.createSession()
    }

    override fun closeRuntime(timeoutMs: Long): RuntimeCloseResult {
        closeStarted.countDown()
        return RuntimeCloseResult.closed()
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

private fun HotSwappableRuntimeFacade.replaceInTest(newDelegate: MvpRuntimeFacade): RuntimeReplacementResult {
    return runBlocking { replace(newDelegate) }
}
