package com.pocketagent.android.runtime

internal class LlamaRuntimeExecutionGate {
    private val lock = Any()
    private var generationOwner: GenerationLease? = null
    private var probeActive: Boolean = false
    private var nonStreamingCount: Int = 0

    fun isBusyForConfig(): Boolean = synchronized(lock) { generationOwner != null || probeActive }

    fun tryBeginNonStreaming(): Boolean = synchronized(lock) {
        if (generationOwner != null || probeActive) {
            return@synchronized false
        }
        nonStreamingCount += 1
        true
    }

    fun endNonStreaming() {
        synchronized(lock) {
            if (nonStreamingCount > 0) {
                nonStreamingCount -= 1
            }
        }
    }

    fun tryBeginGeneration(requestId: String): GenerationLease? = synchronized(lock) {
        val exclusiveOperationActive = generationOwner != null || probeActive
        val generationBlocked = exclusiveOperationActive || nonStreamingCount > 0
        if (requestId.isBlank() || generationBlocked) {
            return@synchronized null
        }
        GenerationLease(requestId).also { generationOwner = it }
    }

    fun endGeneration(lease: GenerationLease): Boolean = synchronized(lock) {
        if (generationOwner !== lease) {
            return@synchronized false
        }
        generationOwner = null
        true
    }

    fun generationCancelDecision(requestId: String?): GenerationCancelDecision = synchronized(lock) {
        if (requestId.isNullOrBlank()) {
            return@synchronized GenerationCancelDecision.REQUEST_ID_REQUIRED
        }
        if (generationOwner?.requestId != requestId) {
            return@synchronized GenerationCancelDecision.NOT_OWNER
        }
        GenerationCancelDecision.OWNER
    }

    fun tryBeginProbe(): Boolean = synchronized(lock) {
        if (generationOwner != null || probeActive || nonStreamingCount > 0) {
            return@synchronized false
        }
        probeActive = true
        true
    }

    fun endProbe() {
        synchronized(lock) {
            probeActive = false
        }
    }

    internal class GenerationLease internal constructor(
        internal val requestId: String,
    )

    internal enum class GenerationCancelDecision(
        val errorCode: String?,
        val detail: String?,
    ) {
        OWNER(errorCode = null, detail = null),
        REQUEST_ID_REQUIRED(
            errorCode = REMOTE_ERROR_REQUEST_ID_REQUIRED,
            detail = "cancel_request_id_required",
        ),
        NOT_OWNER(
            errorCode = REMOTE_ERROR_NOT_GENERATION_OWNER,
            detail = "cancel_request_not_owner",
        ),
    }
}
