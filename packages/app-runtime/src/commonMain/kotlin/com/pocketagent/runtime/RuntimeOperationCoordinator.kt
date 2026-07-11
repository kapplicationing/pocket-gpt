package com.pocketagent.runtime

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Owns admission to generation and lifecycle mutation for one runtime graph.
 *
 * A lifecycle lease keeps generation admission closed until the mutation is complete. Generation
 * leases use an identity token so stale cleanup cannot release a newer owner.
 */
class RuntimeOperationCoordinator {
    private val lock = ReentrantLock()
    private val stateChanged = lock.newCondition()
    private var nextLeaseId: Long = 1L
    private var activeGeneration: ActiveGeneration? = null
    private var lifecycleLeaseId: Long? = null
    private var lifecycleDrainPending: Boolean = false
    private var cancellationPendingLeaseId: Long? = null
    private var closePreparationLeaseId: Long? = null
    private var permanentlyClosed: Boolean = false

    fun tryAcquireGeneration(requestId: String, sessionId: String): GenerationAdmissionResult {
        return lock.withLock {
            val current = activeGeneration
            if (current != null || generationAdmissionClosed()) {
                return@withLock GenerationAdmissionResult.Rejected(
                    code = RUNTIME_BUSY_GENERATION_CODE,
                    detail = current?.let { owner ->
                        "activeRequestId=${owner.requestId}|activeSessionId=${owner.sessionId}"
                    } ?: "runtime_lifecycle_mutation_in_progress",
                )
            }
            val leaseId = nextLeaseId++
            activeGeneration = ActiveGeneration(
                leaseId = leaseId,
                requestId = requestId,
                sessionId = sessionId,
            )
            GenerationAdmissionResult.Acquired(
                GenerationLease(
                    leaseId = leaseId,
                    requestId = requestId,
                    sessionId = sessionId,
                    release = ::releaseGeneration,
                ),
            )
        }
    }

    fun cancelByRequest(
        requestId: String,
        cancel: (String) -> CancellationResult,
    ): CancellationResult {
        return cancelMatching(
            noMatch = CancellationResult(
                cancelled = false,
                code = "REQUEST_NOT_ACTIVE",
                detail = "requestId=$requestId",
            ),
            matches = { active -> active.requestId == requestId },
            cancel = cancel,
        )
    }

    fun cancelBySession(
        sessionId: String,
        cancel: (String) -> CancellationResult,
    ): CancellationResult {
        return cancelMatching(
            noMatch = CancellationResult(
                cancelled = false,
                code = "SESSION_NOT_ACTIVE",
                detail = "sessionId=$sessionId",
            ),
            matches = { active -> active.sessionId == sessionId },
            cancel = cancel,
        )
    }

    fun acquireLifecycle(
        timeoutMs: Long,
        cancel: (String) -> CancellationResult,
    ): LifecycleAdmissionResult {
        val activeToCancel = lock.withLock {
            lifecycleAdmissionRejection()?.let { rejection -> return rejection }
            lifecycleDrainPending = true
            activeGeneration
        }

        if (activeToCancel != null) {
            val cancellation = runCatching { cancel(activeToCancel.requestId) }.getOrElse { error ->
                reopenAdmissionAfterRejectedDrain()
                return LifecycleAdmissionResult.Rejected(
                    code = "CANCEL_EXCEPTION",
                    detail = error.message ?: error::class.simpleName,
                )
            }
            if (!cancellation.cancelled) {
                val ownerStillActive = lock.withLock {
                    activeGeneration?.leaseId == activeToCancel.leaseId
                }
                if (ownerStillActive) {
                    reopenAdmissionAfterRejectedDrain()
                    return LifecycleAdmissionResult.Rejected(
                        code = cancellation.code,
                        detail = cancellation.detail ?: "requestId=${activeToCancel.requestId}",
                    )
                }
            }
        }

        val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
        return lock.withLock {
            var remainingNanos = timeoutNanos
            while (activeGeneration != null) {
                if (remainingNanos <= 0L) {
                    lifecycleDrainPending = false
                    stateChanged.signalAll()
                    return@withLock LifecycleAdmissionResult.Rejected(
                        code = RUNTIME_IDLE_TIMEOUT_CODE,
                        detail = activeGeneration?.let { owner -> "requestId=${owner.requestId}" },
                    )
                }
                remainingNanos = try {
                    stateChanged.awaitNanos(remainingNanos)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    lifecycleDrainPending = false
                    stateChanged.signalAll()
                    return@withLock LifecycleAdmissionResult.Rejected(
                        code = "GENERATION_IDLE_WAIT_INTERRUPTED",
                        detail = activeGeneration?.let { owner -> "requestId=${owner.requestId}" },
                    )
                }
            }
            val leaseId = nextLeaseId++
            lifecycleLeaseId = leaseId
            lifecycleDrainPending = false
            LifecycleAdmissionResult.Acquired(
                LifecycleLease(
                    leaseId = leaseId,
                    release = ::releaseLifecycle,
                ),
            )
        }
    }

    fun isGenerationIdle(): Boolean = lock.withLock { activeGeneration == null }

    fun closePermanently(
        timeoutMs: Long,
        cancel: (String) -> CancellationResult,
    ): RuntimeCloseAdmissionResult {
        return when (val preparation = prepareClose(timeoutMs = timeoutMs, cancel = cancel)) {
            RuntimeClosePreparationResult.Closed -> RuntimeCloseAdmissionResult.Ready
            is RuntimeClosePreparationResult.Rejected -> RuntimeCloseAdmissionResult.Rejected(
                preparation.code,
                preparation.detail,
            )
            is RuntimeClosePreparationResult.Prepared -> {
                preparation.lease.commit()
                RuntimeCloseAdmissionResult.Ready
            }
        }
    }

    fun prepareClose(
        timeoutMs: Long,
        cancel: (String) -> CancellationResult,
    ): RuntimeClosePreparationResult {
        val activeToCancel = lock.withLock {
            if (permanentlyClosed) return RuntimeClosePreparationResult.Closed
            if (lifecycleDrainPending || lifecycleLeaseId != null) {
                return RuntimeClosePreparationResult.Rejected(RUNTIME_BUSY_GENERATION_CODE)
            }
            lifecycleDrainPending = true
            activeGeneration
        }
        if (activeToCancel != null) {
            val cancellation = runCatching { cancel(activeToCancel.requestId) }.getOrElse { error ->
                rollbackCloseAdmission()
                return RuntimeClosePreparationResult.Rejected(
                    code = "CANCEL_EXCEPTION",
                    detail = error.message ?: error::class.simpleName,
                )
            }
            if (!cancellation.cancelled && lock.withLock { activeGeneration != null }) {
                rollbackCloseAdmission()
                return RuntimeClosePreparationResult.Rejected(cancellation.code, cancellation.detail)
            }
        }
        val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
        return lock.withLock {
            var remaining = timeoutNanos
            while (activeGeneration != null || lifecycleLeaseId != null) {
                if (remaining <= 0L) {
                    lifecycleDrainPending = false
                    stateChanged.signalAll()
                    return@withLock RuntimeClosePreparationResult.Rejected(
                        code = RUNTIME_IDLE_TIMEOUT_CODE,
                        detail = activeGeneration?.requestId,
                    )
                }
                remaining = try {
                    stateChanged.awaitNanos(remaining)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    lifecycleDrainPending = false
                    stateChanged.signalAll()
                    return@withLock RuntimeClosePreparationResult.Rejected("RUNTIME_CLOSE_INTERRUPTED")
                }
            }
            val leaseId = nextLeaseId++
            closePreparationLeaseId = leaseId
            RuntimeClosePreparationResult.Prepared(
                RuntimeCloseLease(
                    leaseId = leaseId,
                    commit = ::commitClose,
                    rollback = ::rollbackClose,
                ),
            )
        }
    }

    private fun commitClose(leaseId: Long) {
        lock.withLock {
            if (closePreparationLeaseId != leaseId) return
            closePreparationLeaseId = null
            permanentlyClosed = true
            lifecycleDrainPending = false
            stateChanged.signalAll()
        }
    }

    private fun rollbackClose(leaseId: Long) {
        lock.withLock {
            if (closePreparationLeaseId != leaseId) return
            closePreparationLeaseId = null
            lifecycleDrainPending = false
            stateChanged.signalAll()
        }
    }

    private fun rollbackCloseAdmission() {
        lock.withLock {
            lifecycleDrainPending = false
            stateChanged.signalAll()
        }
    }

    private fun generationAdmissionClosed(): Boolean {
        return permanentlyClosed ||
            lifecycleDrainPending ||
            lifecycleLeaseId != null ||
            cancellationPendingLeaseId != null
    }

    private fun lifecycleAdmissionRejection(): LifecycleAdmissionResult.Rejected? {
        val detail = when {
            permanentlyClosed -> "runtime_permanently_closed"
            cancellationPendingLeaseId != null -> "runtime_generation_cancellation_in_progress"
            lifecycleDrainPending || lifecycleLeaseId != null -> "runtime_lifecycle_mutation_in_progress"
            else -> return null
        }
        return LifecycleAdmissionResult.Rejected(
            code = RUNTIME_BUSY_GENERATION_CODE,
            detail = detail,
        )
    }

    private fun cancelMatching(
        noMatch: CancellationResult,
        matches: (ActiveGeneration) -> Boolean,
        cancel: (String) -> CancellationResult,
    ): CancellationResult {
        val owner = lock.withLock {
            val current = activeGeneration?.takeIf(matches) ?: return noMatch
            if (cancellationPendingLeaseId != null || lifecycleDrainPending) {
                return CancellationResult(
                    cancelled = false,
                    code = "CANCEL_IN_PROGRESS",
                    detail = "requestId=${current.requestId}",
                )
            }
            cancellationPendingLeaseId = current.leaseId
            current
        }
        return try {
            cancel(owner.requestId)
        } finally {
            lock.withLock {
                if (cancellationPendingLeaseId == owner.leaseId) {
                    cancellationPendingLeaseId = null
                    stateChanged.signalAll()
                }
            }
        }
    }

    private fun reopenAdmissionAfterRejectedDrain() {
        lock.withLock {
            lifecycleDrainPending = false
            stateChanged.signalAll()
        }
    }

    private fun releaseGeneration(leaseId: Long) {
        lock.withLock {
            if (activeGeneration?.leaseId == leaseId) {
                activeGeneration = null
                stateChanged.signalAll()
            }
        }
    }

    private fun releaseLifecycle(leaseId: Long) {
        lock.withLock {
            if (lifecycleLeaseId == leaseId) {
                lifecycleLeaseId = null
                stateChanged.signalAll()
            }
        }
    }

    private data class ActiveGeneration(
        val leaseId: Long,
        val requestId: String,
        val sessionId: String,
    )
}

sealed interface GenerationAdmissionResult {
    data class Acquired(val lease: GenerationLease) : GenerationAdmissionResult
    data class Rejected(val code: String, val detail: String?) : GenerationAdmissionResult
}

class GenerationLease internal constructor(
    private val leaseId: Long,
    val requestId: String,
    val sessionId: String,
    private val release: (Long) -> Unit,
) : AutoCloseable {
    override fun close() {
        release(leaseId)
    }
}

sealed interface LifecycleAdmissionResult {
    data class Acquired(val lease: LifecycleLease) : LifecycleAdmissionResult
    data class Rejected(val code: String, val detail: String?) : LifecycleAdmissionResult
}

sealed interface RuntimeCloseAdmissionResult {
    data object Ready : RuntimeCloseAdmissionResult
    data class Rejected(val code: String, val detail: String? = null) : RuntimeCloseAdmissionResult
}

sealed interface RuntimeClosePreparationResult {
    data object Closed : RuntimeClosePreparationResult
    data class Prepared(val lease: RuntimeCloseLease) : RuntimeClosePreparationResult
    data class Rejected(val code: String, val detail: String? = null) : RuntimeClosePreparationResult
}

class RuntimeCloseLease internal constructor(
    private val leaseId: Long,
    private val commit: (Long) -> Unit,
    private val rollback: (Long) -> Unit,
) : AutoCloseable {
    private var completed: Boolean = false

    fun commit() {
        if (completed) return
        completed = true
        commit(leaseId)
    }

    override fun close() {
        if (completed) return
        completed = true
        rollback(leaseId)
    }
}

class LifecycleLease internal constructor(
    private val leaseId: Long,
    private val release: (Long) -> Unit,
) : AutoCloseable {
    override fun close() {
        release(leaseId)
    }
}

internal const val RUNTIME_BUSY_GENERATION_CODE = "BUSY_GENERATION"
internal const val RUNTIME_IDLE_TIMEOUT_CODE = "GENERATION_IDLE_TIMEOUT"
