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
import com.pocketagent.runtime.RuntimeStreamAdmissionSupport
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
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
    fun `blocked stale call times out replacement without blocking UI or losing old runtime`() {
        val oldRuntime = BlockingFacade(
            sessionId = "session-old",
            callStarted = CountDownLatch(1),
            releaseCall = CountDownLatch(1),
        )
        val unusedRuntime = RetryableCleanupFacade(
            sessionId = "session-unused",
            firstCloseResult = RuntimeCloseResult.rejected("UNUSED_CLOSE_REJECTED", "cleanup_failed"),
        )
        val replacementExecutor = Executors.newSingleThreadExecutor()
        val callExecutor = Executors.newSingleThreadExecutor()
        val uiExecutor = Executors.newSingleThreadExecutor()
        val replacementDispatcher = replacementExecutor.asCoroutineDispatcher()
        val uiDispatcher = uiExecutor.asCoroutineDispatcher()
        val uiScope = CoroutineScope(SupervisorJob() + uiDispatcher)
        val hotSwap = HotSwappableRuntimeFacade(
            initial = oldRuntime,
            replacementDispatcher = replacementDispatcher,
            replacementDrainTimeoutMs = 500L,
        )
        try {
            val staleCall = callExecutor.submit<SessionId> { hotSwap.createSession() }
            assertTrue(oldRuntime.callStarted.await(2, TimeUnit.SECONDS))
            val replacement = uiScope.async { hotSwap.replace(unusedRuntime) }
            awaitAvailability(hotSwap, RuntimeFacadeAvailability.REPLACING)

            assertFalse(hotSwap.deleteSession(SessionId("delete-during-replacement")))
            hotSwap.setRoutingMode(RoutingMode.QWEN_0_8B)
            val uiMarker = uiExecutor.submit<String> { "ui-responsive" }
            assertEquals("ui-responsive", uiMarker.get(2, TimeUnit.SECONDS))

            val result = runBlocking { withTimeout(2_000L) { replacement.await() } }
            assertFalse(result.success)
            assertEquals("RUNTIME_DELEGATE_DRAIN_TIMEOUT", result.code)
            assertTrue(result.detail.orEmpty().contains("UNUSED_CLOSE_REJECTED"))
            assertEquals(RuntimeFacadeAvailability.READY, hotSwap.availability())
            assertEquals(1L, oldRuntime.closeStarted.count, "old close must not start before drain")
            assertEquals(1, unusedRuntime.cleanupCloseCalls)
            assertEquals(listOf(5_000L), unusedRuntime.cleanupTimeouts)
            assertEquals(1, hotSwap.retainedUnusedDelegateCount())
            assertEquals(0, oldRuntime.deleteCalls)
            assertEquals(0, unusedRuntime.deleteCalls)
            assertEquals(RoutingMode.QWEN_0_8B, hotSwap.getRoutingMode())

            val retry = hotSwap.retryRetainedUnusedDelegateCleanup()
            assertTrue(retry.single().contains("unusedRuntimeCleanup=closed"))
            assertEquals(2, unusedRuntime.cleanupCloseCalls)
            assertEquals(0, hotSwap.retainedUnusedDelegateCount())

            oldRuntime.releaseCall.countDown()
            assertEquals("session-old", staleCall.get(2, TimeUnit.SECONDS).value)
            assertEquals("session-old", hotSwap.createSession().value)
        } finally {
            oldRuntime.releaseCall.countDown()
            uiScope.cancel()
            replacementDispatcher.close()
            uiDispatcher.close()
            replacementExecutor.shutdownNow()
            callExecutor.shutdownNow()
            uiExecutor.shutdownNow()
        }
    }

    @Test
    fun `close uses caller timeout while draining stale call`() {
        val oldRuntime = BlockingFacade(
            sessionId = "session-old",
            callStarted = CountDownLatch(1),
            releaseCall = CountDownLatch(1),
        )
        val callExecutor = Executors.newSingleThreadExecutor()
        val closeExecutor = Executors.newSingleThreadExecutor()
        val hotSwap = HotSwappableRuntimeFacade(oldRuntime)
        try {
            val staleCall = callExecutor.submit<SessionId> { hotSwap.createSession() }
            assertTrue(oldRuntime.callStarted.await(2, TimeUnit.SECONDS))
            val close = closeExecutor.submit<RuntimeCloseResult> { hotSwap.closeRuntime(timeoutMs = 75L) }

            val result = close.get(2, TimeUnit.SECONDS)
            assertFalse(result.success)
            assertEquals("RUNTIME_DELEGATE_DRAIN_TIMEOUT", result.code)
            assertEquals(RuntimeFacadeAvailability.READY, hotSwap.availability())
            assertEquals(1L, oldRuntime.closeStarted.count, "delegate close must not run after drain timeout")

            oldRuntime.releaseCall.countDown()
            assertEquals("session-old", staleCall.get(2, TimeUnit.SECONDS).value)
        } finally {
            oldRuntime.releaseCall.countDown()
            callExecutor.shutdownNow()
            closeExecutor.shutdownNow()
        }
    }

    @Test
    fun `closed facade rejects lifecycle mutations as unavailable not busy`() {
        val hotSwap = HotSwappableRuntimeFacade(StubFacade(sessionId = "session-old"))

        assertTrue(hotSwap.closeRuntime(timeoutMs = 500L).success)

        assertEquals(ModelLifecycleErrorCode.UNKNOWN, hotSwap.loadModel("model", "v1").errorCode)
        assertEquals(ModelLifecycleErrorCode.UNKNOWN, hotSwap.offloadModel("closed-test").errorCode)
        assertFalse(hotSwap.deleteSession(SessionId("closed-session")))
    }

    @Test
    fun `started event retains selected runtime until explicit generation admission`() {
        val oldRuntime = AdmissionBarrierFacade()
        val newRuntime = StubFacade(sessionId = "session-new")
        val executor = Executors.newFixedThreadPool(2)
        val hotSwap = HotSwappableRuntimeFacade(oldRuntime)
        try {
            val collection = executor.submit<List<ChatStreamEvent>> {
                runBlocking { hotSwap.streamChat(testStreamRequest()).toList() }
            }
            assertTrue(oldRuntime.startedEmitted.await(2, TimeUnit.SECONDS))
            val replacement = executor.submit<RuntimeReplacementResult> { hotSwap.replaceInTest(newRuntime) }
            awaitAvailability(hotSwap, RuntimeFacadeAvailability.REPLACING)
            assertEquals(1L, oldRuntime.closeStarted.count)
            assertEquals(1L, oldRuntime.producerStarted.count)

            oldRuntime.allowAdmission.countDown()

            assertTrue(oldRuntime.producerStarted.await(2, TimeUnit.SECONDS))
            assertTrue(oldRuntime.closeStarted.await(2, TimeUnit.SECONDS))
            assertTrue(replacement.get(2, TimeUnit.SECONDS).success)
            assertIs<ChatStreamEvent.Started>(collection.get(2, TimeUnit.SECONDS).single())
            assertFalse(oldRuntime.producerEnteredClosedRuntime.get())
            assertEquals(1, oldRuntime.cancellationRequests.get())
            assertEquals("session-new", hotSwap.createSession().value)
        } finally {
            oldRuntime.allowAdmission.countDown()
            oldRuntime.releaseProducer.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `cancelled never admitted stream releases selection ownership exactly once`() = runBlocking {
        val oldRuntime = NeverAdmittedFacade()
        val newRuntime = StubFacade(sessionId = "session-new")
        val hotSwap = HotSwappableRuntimeFacade(oldRuntime)
        val collection = launch(Dispatchers.Default) {
            hotSwap.streamChat(testStreamRequest()).toList()
        }
        assertTrue(oldRuntime.startedEmitted.await(2, TimeUnit.SECONDS))

        collection.cancelAndJoin()
        val replacement = hotSwap.replace(newRuntime)

        assertTrue(replacement.success)
        assertEquals(1, oldRuntime.closeCalls)
        assertEquals("session-new", hotSwap.createSession().value)
    }

    @Test
    fun `failed old close retains old delegate and owns unused new until retry`() {
        val first = StubFacade(
            sessionId = "session-old",
            closeResult = RuntimeCloseResult.rejected("CLOSE_REJECTED"),
        )
        val second = RetryableCleanupFacade(
            sessionId = "session-new",
            firstCloseResult = RuntimeCloseResult.rejected("UNUSED_CLOSE_REJECTED"),
        )
        val hotSwap = HotSwappableRuntimeFacade(first)

        val replacement = hotSwap.replaceInTest(second)

        assertFalse(replacement.success)
        assertEquals("session-old", hotSwap.createSession().value)
        assertEquals(1, first.closeCalls)
        assertTrue(replacement.detail.orEmpty().contains("unusedRuntimeCleanup=retained"))
        assertEquals(1, hotSwap.retainedUnusedDelegateCount())
        hotSwap.retryRetainedUnusedDelegateCleanup()
        assertEquals(2, second.cleanupCloseCalls)
        assertEquals(0, hotSwap.retainedUnusedDelegateCount())
    }

    @Test
    fun `close owner retries retained unused runtime within the caller budget`() {
        val oldRuntime = RetryableCleanupFacade(
            sessionId = "session-old",
            firstCloseResult = RuntimeCloseResult.rejected("OLD_CLOSE_REJECTED"),
        )
        val unusedRuntime = RetryableCleanupFacade(
            sessionId = "session-unused",
            firstCloseResult = RuntimeCloseResult.rejected("UNUSED_CLOSE_REJECTED"),
        )
        val hotSwap = HotSwappableRuntimeFacade(oldRuntime)
        assertFalse(hotSwap.replaceInTest(unusedRuntime).success)
        assertEquals(1, hotSwap.retainedUnusedDelegateCount())

        val close = hotSwap.closeRuntime(timeoutMs = 1_000L)

        assertTrue(close.success)
        assertEquals(RuntimeFacadeAvailability.CLOSED, hotSwap.availability())
        assertEquals(2, oldRuntime.cleanupCloseCalls)
        assertEquals(2, unusedRuntime.cleanupCloseCalls)
        assertTrue(oldRuntime.cleanupTimeouts.last() in 0L..1_000L)
        assertTrue(unusedRuntime.cleanupTimeouts.last() in 0L..1_000L)
        assertEquals(0, hotSwap.retainedUnusedDelegateCount())
    }

    @Test
    fun `close owner reports and retains repeated unused close rejection`() {
        val oldRuntime = RetryableCleanupFacade(
            sessionId = "session-old",
            firstCloseResult = RuntimeCloseResult.rejected("OLD_CLOSE_REJECTED"),
        )
        val unusedRuntime = StubFacade(
            sessionId = "session-unused",
            closeResult = RuntimeCloseResult.rejected("UNUSED_CLOSE_REJECTED"),
        )
        val hotSwap = HotSwappableRuntimeFacade(oldRuntime)
        assertFalse(hotSwap.replaceInTest(unusedRuntime).success)
        assertEquals(1, hotSwap.retainedUnusedDelegateCount())

        val close = hotSwap.closeRuntime(timeoutMs = 1_000L)

        assertFalse(close.success)
        assertFalse(close.runtimeReusable)
        assertEquals("RETAINED_UNUSED_RUNTIME_CLEANUP_INCOMPLETE", close.code)
        assertTrue(close.detail.orEmpty().contains("UNUSED_CLOSE_REJECTED"))
        assertEquals(RuntimeFacadeAvailability.CLOSED, hotSwap.availability())
        assertEquals(2, unusedRuntime.closeCalls)
        assertEquals(1, hotSwap.retainedUnusedDelegateCount())

        val terminalRetry = hotSwap.closeRuntime(timeoutMs = 1_000L)
        assertTrue(terminalRetry.detail.orEmpty().contains("remaining=1"))
        assertEquals(3, unusedRuntime.closeCalls)
        assertEquals(1, hotSwap.retainedUnusedDelegateCount())
    }

    @Test
    fun `install preflight prevents repeated delegate builds while cleanup remains unresolved`() {
        val oldRuntime = StubFacade(
            sessionId = "session-old",
            closeResult = RuntimeCloseResult.rejected("OLD_CLOSE_REJECTED"),
        )
        val hotSwap = HotSwappableRuntimeFacade(oldRuntime)
        val installs = RuntimeInstallSingleFlight()
        val builds = AtomicInteger(0)

        fun install(): RuntimeInstallOutcome {
            return installs.install(
                shouldInstall = { true },
                readCandidate = { RuntimeInstallCandidate("fingerprint", Unit) },
                readInstalledFingerprint = { null },
                preflight = { hotSwap.prepareForRuntimeInstall(timeoutMs = 50L) },
                build = {
                    builds.incrementAndGet()
                    StubFacade(
                        sessionId = "session-unused",
                        closeResult = RuntimeCloseResult.rejected("UNUSED_CLOSE_REJECTED"),
                    )
                },
                replace = { _, delegate -> hotSwap.replaceInTest(delegate) },
                finalizePublished = { _, _ -> error("rejected install must not finalize") },
                commitFingerprint = { error("rejected install must not commit") },
                onCoalesced = { error("install must not coalesce") },
            )
        }

        assertIs<RuntimeInstallOutcome.Rejected>(install())
        assertEquals(1, builds.get())
        assertEquals(1, hotSwap.retainedUnusedDelegateCount())

        val directCandidate = StubFacade(sessionId = "session-direct")
        val directReplacement = hotSwap.replaceInTest(directCandidate)
        assertFalse(directReplacement.success)
        assertEquals("RETAINED_UNUSED_RUNTIME_CLEANUP_PENDING", directReplacement.code)
        assertEquals(1, directCandidate.closeCalls)
        assertEquals(1, oldRuntime.closeCalls)
        assertEquals(1, hotSwap.retainedUnusedDelegateCount())

        assertIs<RuntimeInstallOutcome.Deferred>(install())
        assertIs<RuntimeInstallOutcome.Deferred>(install())
        assertEquals(1, builds.get())
        assertEquals(1, hotSwap.retainedUnusedDelegateCount())
    }

    @Test
    fun `unsupported new lifetime remains owned after rejection`() {
        val oldRuntime = StubFacade(sessionId = "session-old")
        val unsupportedNewRuntime = NoLifetimeFacade(sessionId = "session-new")
        val hotSwap = HotSwappableRuntimeFacade(oldRuntime)

        val replacement = hotSwap.replaceInTest(unsupportedNewRuntime)

        assertFalse(replacement.success)
        assertEquals("NEW_RUNTIME_LIFETIME_UNSUPPORTED", replacement.code)
        assertTrue(replacement.detail.orEmpty().contains("unusedRuntimeCleanup=retained"))
        assertEquals(1, hotSwap.retainedUnusedDelegateCount())
        assertEquals("session-old", hotSwap.createSession().value)
        val retry = hotSwap.retryRetainedUnusedDelegateCleanup(timeoutMs = 50L)
        assertTrue(retry.single().contains("UNUSED_RUNTIME_LIFETIME_UNSUPPORTED"))
        assertEquals(1, hotSwap.retainedUnusedDelegateCount())

        val close = hotSwap.closeRuntime(timeoutMs = 50L)
        assertFalse(close.success)
        assertFalse(close.runtimeReusable)
        assertEquals("RETAINED_UNUSED_RUNTIME_CLEANUP_INCOMPLETE", close.code)
        assertTrue(close.detail.orEmpty().contains("UNUSED_RUNTIME_LIFETIME_UNSUPPORTED"))
        assertEquals(RuntimeFacadeAvailability.CLOSED, hotSwap.availability())
        assertEquals(1, hotSwap.retainedUnusedDelegateCount())

        val terminalRetry = hotSwap.closeRuntime(timeoutMs = 50L)
        assertEquals("RETAINED_UNUSED_RUNTIME_CLEANUP_INCOMPLETE", terminalRetry.code)
        assertTrue(terminalRetry.detail.orEmpty().contains("remaining=1"))
        assertEquals(1, hotSwap.retainedUnusedDelegateCount())
    }

    @Test
    fun `unsupported old lifetime retains failed new cleanup until retry`() {
        val oldRuntime = NoLifetimeFacade(sessionId = "session-old")
        val newRuntime = RetryableCleanupFacade(
            sessionId = "session-new",
            firstCloseResult = RuntimeCloseResult.rejected("UNUSED_CLOSE_REJECTED"),
        )
        val hotSwap = HotSwappableRuntimeFacade(oldRuntime)

        val replacement = hotSwap.replaceInTest(newRuntime)

        assertFalse(replacement.success)
        assertEquals("OLD_RUNTIME_LIFETIME_UNSUPPORTED", replacement.code)
        assertTrue(replacement.detail.orEmpty().contains("unusedRuntimeCleanup=retained"))
        assertEquals(1, hotSwap.retainedUnusedDelegateCount())
        assertEquals("session-old", hotSwap.createSession().value)
        hotSwap.retryRetainedUnusedDelegateCleanup()
        assertEquals(2, newRuntime.cleanupCloseCalls)
        assertEquals(0, hotSwap.retainedUnusedDelegateCount())
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
    fun `close exception keeps old owner and cleans unused new delegate`() {
        val first = ThrowingCloseFacade()
        val second = StubFacade(sessionId = "session-new")
        val hotSwap = HotSwappableRuntimeFacade(first)

        val replacement = hotSwap.replaceInTest(second)

        assertFalse(replacement.success)
        assertEquals("OLD_RUNTIME_CLOSE_EXCEPTION", replacement.code)
        assertEquals("session-old", hotSwap.createSession().value)
        assertEquals(1, second.closeCalls)
    }

    @Test
    fun `facade close exception preserves reusable old owner`() {
        val oldRuntime = ThrowingCloseFacade()
        val hotSwap = HotSwappableRuntimeFacade(oldRuntime)

        val close = hotSwap.closeRuntime(timeoutMs = 500L)

        assertFalse(close.success)
        assertTrue(close.runtimeReusable)
        assertEquals("RUNTIME_CLOSE_EXCEPTION", close.code)
        assertEquals(RuntimeFacadeAvailability.READY, hotSwap.availability())
        assertEquals("session-old", hotSwap.createSession().value)
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

    @Test
    fun `published delegate guard rejects stale warmup target`() {
        val first = StubFacade(sessionId = "session-old")
        val second = StubFacade(sessionId = "session-new")
        val hotSwap = HotSwappableRuntimeFacade(first)
        var warmupSchedules = 0

        assertTrue(hotSwap.runIfPublished(first) { warmupSchedules += 1 })
        assertTrue(hotSwap.replaceInTest(second).success)
        assertFalse(hotSwap.runIfPublished(first) { warmupSchedules += 1 })
        assertTrue(hotSwap.runIfPublished(second) { warmupSchedules += 1 })
        assertEquals(2, warmupSchedules)
    }

    @Test
    fun `cancelled facade warmup drains its lease before old runtime closes`() {
        val oldRuntime = BlockingWarmupCloseFacade()
        val newRuntime = RecordingWarmupFacade()
        val hotSwap = HotSwappableRuntimeFacade(oldRuntime)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val orchestrator = RuntimeWarmupOrchestrator(
            scope = scope,
            dispatcher = Dispatchers.Default,
            warmupTimeoutMs = 5_000L,
            logger = {},
        )
        try {
            orchestrator.scheduleWarmupIfSupported(hotSwap)
            assertTrue(oldRuntime.warmupStarted.await(2, TimeUnit.SECONDS))

            orchestrator.cancelActiveWarmup()
            val replacement = hotSwap.replaceInTest(newRuntime)

            assertTrue(replacement.success)
            assertTrue(oldRuntime.closeObservedWarmupStopped.get())
            assertEquals(1, oldRuntime.warmupCalls.get())
            assertFalse(oldRuntime.warmupInvokedAfterClose.get())

            orchestrator.scheduleWarmupIfSupported(hotSwap)
            assertTrue(newRuntime.warmupStarted.await(2, TimeUnit.SECONDS))
            assertEquals(1, newRuntime.warmupCalls.get())
            assertEquals(1, oldRuntime.warmupCalls.get(), "new warmup must not capture stale delegate")
        } finally {
            orchestrator.shutdown()
        }
    }
}

private open class StubFacade(
    private val sessionId: String = "session-1",
    private val closeResult: RuntimeCloseResult = RuntimeCloseResult.closed(),
) : MvpRuntimeFacade, RuntimeLifetimePort {
    private var routingMode: RoutingMode = RoutingMode.AUTO
    var closeCalls: Int = 0
    val closeTimeouts = mutableListOf<Long>()
    var deleteCalls: Int = 0

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

    override fun deleteSession(sessionId: SessionId): Boolean {
        deleteCalls += 1
        return true
    }

    open override fun closeRuntime(timeoutMs: Long): RuntimeCloseResult {
        closeCalls += 1
        closeTimeouts += timeoutMs
        return closeResult
    }
}

private class NoLifetimeFacade(
    sessionId: String,
) : MvpRuntimeFacade by StubFacade(sessionId = sessionId)

private class RetryableCleanupFacade(
    sessionId: String,
    private val firstCloseResult: RuntimeCloseResult,
) : StubFacade(sessionId = sessionId) {
    var cleanupCloseCalls: Int = 0
    val cleanupTimeouts = mutableListOf<Long>()

    override fun closeRuntime(timeoutMs: Long): RuntimeCloseResult {
        cleanupCloseCalls += 1
        cleanupTimeouts += timeoutMs
        return if (cleanupCloseCalls == 1) firstCloseResult else RuntimeCloseResult.closed()
    }
}

private class BlockingFlowFacade(
    private val order: MutableList<String>,
) : StubFacade(), RuntimeStreamAdmissionSupport {
    val flowStarted = CountDownLatch(1)
    val releaseFlow = CountDownLatch(1)

    override fun streamChat(request: StreamChatRequestV2): Flow<ChatStreamEvent> {
        return streamChatWithAdmission(request = request, onGenerationAdmitted = {})
    }

    override fun streamChatWithAdmission(
        request: StreamChatRequestV2,
        onGenerationAdmitted: () -> Unit,
    ): Flow<ChatStreamEvent> = flow {
        synchronized(order) { order += "flow_started" }
        flowStarted.countDown()
        onGenerationAdmitted()
        emit(ChatStreamEvent.Started(request.requestId, startedAtEpochMs = 1L))
        releaseFlow.await(2, TimeUnit.SECONDS)
        synchronized(order) { order += "flow_finished" }
    }

    override fun closeRuntime(timeoutMs: Long): RuntimeCloseResult {
        synchronized(order) { order += "close_old" }
        releaseFlow.countDown()
        return RuntimeCloseResult.closed()
    }
}

private class SlowTerminalFlowFacade : StubFacade(), RuntimeStreamAdmissionSupport {
    val flowStarted = CountDownLatch(1)
    val releaseFlow = CountDownLatch(1)

    override fun streamChat(request: StreamChatRequestV2): Flow<ChatStreamEvent> {
        return streamChatWithAdmission(request = request, onGenerationAdmitted = {})
    }

    override fun streamChatWithAdmission(
        request: StreamChatRequestV2,
        onGenerationAdmitted: () -> Unit,
    ): Flow<ChatStreamEvent> = flow {
        flowStarted.countDown()
        onGenerationAdmitted()
        emit(ChatStreamEvent.Started(request.requestId, startedAtEpochMs = 1L))
        releaseFlow.await(2, TimeUnit.SECONDS)
    }
}

private class AdmissionBarrierFacade : StubFacade(sessionId = "session-old"), RuntimeStreamAdmissionSupport {
    private val operations = RuntimeOperationCoordinator()
    val startedEmitted = CountDownLatch(1)
    val allowAdmission = CountDownLatch(1)
    val producerStarted = CountDownLatch(1)
    val closeStarted = CountDownLatch(1)
    val releaseProducer = CountDownLatch(1)
    val producerEnteredClosedRuntime = AtomicBoolean(false)
    val cancellationRequests = AtomicInteger(0)
    private val closed = AtomicBoolean(false)

    override fun streamChat(request: StreamChatRequestV2): Flow<ChatStreamEvent> {
        return streamChatWithAdmission(request = request, onGenerationAdmitted = {})
    }

    override fun streamChatWithAdmission(
        request: StreamChatRequestV2,
        onGenerationAdmitted: () -> Unit,
    ): Flow<ChatStreamEvent> = flow {
        emit(ChatStreamEvent.Started(request.requestId, startedAtEpochMs = 1L))
        startedEmitted.countDown()
        allowAdmission.await(2, TimeUnit.SECONDS)
        val admission = operations.tryAcquireGeneration(request.requestId, request.sessionId.value)
        val generationLease = (admission as GenerationAdmissionResult.Acquired).lease
        try {
            onGenerationAdmitted()
            onGenerationAdmitted()
            producerEnteredClosedRuntime.set(closed.get())
            producerStarted.countDown()
            releaseProducer.await(2, TimeUnit.SECONDS)
        } finally {
            generationLease.close()
        }
    }

    override fun closeRuntime(timeoutMs: Long): RuntimeCloseResult {
        closeStarted.countDown()
        val result = when (
            val admission = operations.closePermanently(timeoutMs) {
                cancellationRequests.incrementAndGet()
                releaseProducer.countDown()
                CancellationResult(cancelled = true, code = "CANCELLED_FOR_CLOSE")
            }
        ) {
            RuntimeCloseAdmissionResult.Ready -> RuntimeCloseResult.closed()
            is RuntimeCloseAdmissionResult.Rejected -> RuntimeCloseResult.rejected(
                admission.code,
                admission.detail,
            )
        }
        if (result.success) {
            closed.set(true)
        }
        return result
    }
}

private class NeverAdmittedFacade : StubFacade(sessionId = "session-old"), RuntimeStreamAdmissionSupport {
    val startedEmitted = CountDownLatch(1)

    override fun streamChat(request: StreamChatRequestV2): Flow<ChatStreamEvent> {
        return streamChatWithAdmission(request = request, onGenerationAdmitted = {})
    }

    override fun streamChatWithAdmission(
        request: StreamChatRequestV2,
        onGenerationAdmitted: () -> Unit,
    ): Flow<ChatStreamEvent> = flow {
        emit(ChatStreamEvent.Started(request.requestId, startedAtEpochMs = 1L))
        startedEmitted.countDown()
        kotlinx.coroutines.awaitCancellation()
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
        releaseCall.await()
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

private class BlockingWarmupCloseFacade : StubFacade(), RuntimeWarmupSupport {
    val warmupStarted = CountDownLatch(1)
    val warmupCalls = AtomicInteger(0)
    val warmupInvokedAfterClose = AtomicBoolean(false)
    val closeObservedWarmupStopped = AtomicBoolean(false)
    private val warmupStopped = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    override fun warmupActiveModel(): WarmupResult {
        warmupCalls.incrementAndGet()
        warmupInvokedAfterClose.set(closed.get())
        warmupStarted.countDown()
        try {
            CountDownLatch(1).await()
        } catch (_: InterruptedException) {
            return WarmupResult.skipped("warmup_interrupted")
        } finally {
            warmupStopped.set(true)
        }
        return WarmupResult.skipped("unexpected")
    }

    override fun closeRuntime(timeoutMs: Long): RuntimeCloseResult {
        closeObservedWarmupStopped.set(warmupStopped.get())
        closed.set(true)
        return super.closeRuntime(timeoutMs)
    }
}

private class RecordingWarmupFacade : StubFacade(), RuntimeWarmupSupport {
    val warmupStarted = CountDownLatch(1)
    val warmupCalls = AtomicInteger(0)

    override fun warmupActiveModel(): WarmupResult {
        warmupCalls.incrementAndGet()
        warmupStarted.countDown()
        return WarmupResult(
            attempted = true,
            warmed = true,
            residentHit = false,
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

private fun awaitAvailability(
    facade: HotSwappableRuntimeFacade,
    expected: RuntimeFacadeAvailability,
) {
    runBlocking {
        withTimeout(2_000L) {
            while (facade.availability() != expected) {
                yield()
            }
        }
    }
}
