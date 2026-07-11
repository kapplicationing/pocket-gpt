package com.pocketagent.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RuntimeOperationCoordinatorTest {
    @Test
    fun `generation admission is single flight and stale close cannot release current owner`() {
        val coordinator = RuntimeOperationCoordinator()
        val first = assertIs<GenerationAdmissionResult.Acquired>(
            coordinator.tryAcquireGeneration(requestId = "request-a", sessionId = "session-a"),
        ).lease

        val overlapping = assertIs<GenerationAdmissionResult.Rejected>(
            coordinator.tryAcquireGeneration(requestId = "request-b", sessionId = "session-b"),
        )
        assertEquals(RUNTIME_BUSY_GENERATION_CODE, overlapping.code)

        first.close()
        val current = assertIs<GenerationAdmissionResult.Acquired>(
            coordinator.tryAcquireGeneration(requestId = "request-c", sessionId = "session-c"),
        ).lease

        first.close()
        val afterStaleClose = assertIs<GenerationAdmissionResult.Rejected>(
            coordinator.tryAcquireGeneration(requestId = "request-d", sessionId = "session-d"),
        )
        assertEquals(RUNTIME_BUSY_GENERATION_CODE, afterStaleClose.code)
        current.close()
        assertTrue(coordinator.isGenerationIdle())
    }

    @Test
    fun `request cancellation only invokes callback for exact active owner`() {
        val coordinator = RuntimeOperationCoordinator()
        val active = assertIs<GenerationAdmissionResult.Acquired>(
            coordinator.tryAcquireGeneration(requestId = "request-a", sessionId = "session-a"),
        ).lease
        var cancelCalls = 0

        val wrongOwner = coordinator.cancelByRequest("request-b") {
            cancelCalls += 1
            CancellationResult(cancelled = true, code = "CANCELLED")
        }
        val exactOwner = coordinator.cancelByRequest("request-a") { requestId ->
            cancelCalls += 1
            assertEquals("request-a", requestId)
            CancellationResult(cancelled = true, code = "CANCELLED")
        }

        assertEquals("REQUEST_NOT_ACTIVE", wrongOwner.code)
        assertTrue(exactOwner.cancelled)
        assertEquals(1, cancelCalls)
        active.close()
    }

    @Test
    fun `lifecycle drain cancels exact owner and keeps admission closed until lease closes`() {
        val coordinator = RuntimeOperationCoordinator()
        val active = assertIs<GenerationAdmissionResult.Acquired>(
            coordinator.tryAcquireGeneration(requestId = "request-a", sessionId = "session-a"),
        ).lease
        val cancelStarted = CountDownLatch(1)
        val allowCancelReturn = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val drain = executor.submit<LifecycleAdmissionResult> {
                coordinator.acquireLifecycle(timeoutMs = 2_000L) { requestId ->
                    assertEquals("request-a", requestId)
                    cancelStarted.countDown()
                    assertTrue(allowCancelReturn.await(2, TimeUnit.SECONDS))
                    CancellationResult(cancelled = true, code = "CANCELLED")
                }
            }
            assertTrue(cancelStarted.await(2, TimeUnit.SECONDS))

            assertIs<GenerationAdmissionResult.Rejected>(
                coordinator.tryAcquireGeneration(requestId = "request-b", sessionId = "session-b"),
            )
            allowCancelReturn.countDown()
            active.close()

            val lifecycleLease = assertIs<LifecycleAdmissionResult.Acquired>(
                drain.get(2, TimeUnit.SECONDS),
            ).lease
            assertIs<GenerationAdmissionResult.Rejected>(
                coordinator.tryAcquireGeneration(requestId = "request-c", sessionId = "session-c"),
            )

            lifecycleLease.close()
            val admitted = assertIs<GenerationAdmissionResult.Acquired>(
                coordinator.tryAcquireGeneration(requestId = "request-d", sessionId = "session-d"),
            )
            admitted.lease.close()
        } finally {
            allowCancelReturn.countDown()
            active.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `rejected cancellation fails drain closed without lifecycle lease`() {
        val coordinator = RuntimeOperationCoordinator()
        val active = assertIs<GenerationAdmissionResult.Acquired>(
            coordinator.tryAcquireGeneration(requestId = "request-a", sessionId = "session-a"),
        ).lease

        val drain = coordinator.acquireLifecycle(timeoutMs = 100L) { requestId ->
            CancellationResult(
                cancelled = false,
                code = "CANCEL_REJECTED",
                detail = "requestId=$requestId",
            )
        }

        val rejected = assertIs<LifecycleAdmissionResult.Rejected>(drain)
        assertEquals("CANCEL_REJECTED", rejected.code)
        active.close()
        val admitted = assertIs<GenerationAdmissionResult.Acquired>(
            coordinator.tryAcquireGeneration(requestId = "request-b", sessionId = "session-b"),
        )
        admitted.lease.close()
    }

    @Test
    fun `rejected runtime close reopens admission after active owner finishes`() {
        val coordinator = RuntimeOperationCoordinator()
        val active = assertIs<GenerationAdmissionResult.Acquired>(
            coordinator.tryAcquireGeneration(requestId = "request-a", sessionId = "session-a"),
        ).lease

        val close = coordinator.closePermanently(timeoutMs = 100L) { requestId ->
            CancellationResult(
                cancelled = false,
                code = "CANCEL_REJECTED",
                detail = "requestId=$requestId",
            )
        }

        val rejected = assertIs<RuntimeCloseAdmissionResult.Rejected>(close)
        assertEquals("CANCEL_REJECTED", rejected.code)
        active.close()
        val admitted = assertIs<GenerationAdmissionResult.Acquired>(
            coordinator.tryAcquireGeneration(requestId = "request-b", sessionId = "session-b"),
        )
        admitted.lease.close()
    }

    @Test
    fun `timed out drain blocks admission until timeout then reopens safely`() {
        val coordinator = RuntimeOperationCoordinator()
        val active = assertIs<GenerationAdmissionResult.Acquired>(
            coordinator.tryAcquireGeneration(requestId = "request-a", sessionId = "session-a"),
        ).lease
        val cancelStarted = CountDownLatch(1)
        val allowCancelReturn = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val drain = executor.submit<LifecycleAdmissionResult> {
                coordinator.acquireLifecycle(timeoutMs = 25L) {
                    cancelStarted.countDown()
                    assertTrue(allowCancelReturn.await(2, TimeUnit.SECONDS))
                    CancellationResult(cancelled = true, code = "CANCELLED")
                }
            }
            assertTrue(cancelStarted.await(2, TimeUnit.SECONDS))
            assertIs<GenerationAdmissionResult.Rejected>(
                coordinator.tryAcquireGeneration(requestId = "request-b", sessionId = "session-b"),
            )
            allowCancelReturn.countDown()

            val rejected = assertIs<LifecycleAdmissionResult.Rejected>(
                drain.get(2, TimeUnit.SECONDS),
            )
            assertEquals(RUNTIME_IDLE_TIMEOUT_CODE, rejected.code)

            active.close()
            val admitted = assertIs<GenerationAdmissionResult.Acquired>(
                coordinator.tryAcquireGeneration(requestId = "request-c", sessionId = "session-c"),
            )
            admitted.lease.close()
        } finally {
            allowCancelReturn.countDown()
            active.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `interrupted drain reopens admission after active owner finishes`() {
        val coordinator = RuntimeOperationCoordinator()
        val active = assertIs<GenerationAdmissionResult.Acquired>(
            coordinator.tryAcquireGeneration(requestId = "request-a", sessionId = "session-a"),
        ).lease
        val cancelCalled = CountDownLatch(1)
        val drainFinished = CountDownLatch(1)
        val result = AtomicReference<LifecycleAdmissionResult>()
        val worker = thread(start = true, name = "runtime-drain-interruption-test") {
            result.set(
                coordinator.acquireLifecycle(timeoutMs = 10_000L) {
                    cancelCalled.countDown()
                    CancellationResult(cancelled = true, code = "CANCELLED")
                },
            )
            drainFinished.countDown()
        }
        try {
            assertTrue(cancelCalled.await(2, TimeUnit.SECONDS))
            worker.interrupt()
            assertTrue(drainFinished.await(2, TimeUnit.SECONDS))

            val rejected = assertIs<LifecycleAdmissionResult.Rejected>(result.get())
            assertEquals("GENERATION_IDLE_WAIT_INTERRUPTED", rejected.code)
            active.close()
            val admitted = assertIs<GenerationAdmissionResult.Acquired>(
                coordinator.tryAcquireGeneration(requestId = "request-b", sessionId = "session-b"),
            )
            admitted.lease.close()
        } finally {
            worker.interrupt()
            active.close()
        }
    }
}
