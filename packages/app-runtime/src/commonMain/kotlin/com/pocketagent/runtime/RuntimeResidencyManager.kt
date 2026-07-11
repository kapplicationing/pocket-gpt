package com.pocketagent.runtime

import com.pocketagent.inference.InferenceModule
import com.pocketagent.nativebridge.RuntimeResidencyState
import com.pocketagent.nativebridge.RuntimeInferencePorts
import com.pocketagent.nativebridge.runtimeInferencePorts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

internal data class ResidentRuntimeSlot(
    val modelId: String,
    val slotId: String,
    val sessionCacheIdentity: SessionCacheIdentity? = null,
    val keepAliveMs: Long,
    val expiresAtEpochMs: Long,
    val lastTouchedAtEpochMs: Long,
) {
    val sessionCacheKey: String? get() = sessionCacheIdentity?.cacheKey
}

internal class RuntimeResidencyManager(
    private val inferenceModule: InferenceModule,
    private val runtimeInferencePorts: RuntimeInferencePorts = inferenceModule.runtimeInferencePorts(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onAfterLoad: (ResidentRuntimeSlot) -> Unit = {},
    private val onBeforeUnload: (ResidentRuntimeSlot, String) -> Unit = { _, _ -> },
) {
    private val lock = Any()
    private var expiryJob: Job? = null
    private var residentSlot: ResidentRuntimeSlot? = null
    private var activeRequestCount: Int = 0
    private var pendingUnloadReason: String? = null
    private var lastUnloadReason: String = "none"
    private val autoReleaseDisableReasons: MutableSet<String> = mutableSetOf()
    @Volatile
    var wasAutoReleased: Boolean = false
        private set
    @Volatile
    var lastAutoReleasedModelId: String? = null
        private set

    fun ensureLoaded(
        modelId: String,
        slotId: String,
        keepAliveMs: Long,
        sessionCacheIdentity: SessionCacheIdentity? = null,
        strictGpuOffload: Boolean = false,
    ): Boolean {
        val safeKeepAliveMs = keepAliveMs.coerceAtLeast(1L)
        val loaded = runtimeInferencePorts.managedRuntime?.loadModel(
            modelId = modelId,
            modelVersion = sessionCacheIdentity?.modelVersion,
            strictGpuOffload = strictGpuOffload,
        ) ?: inferenceModule.loadModel(modelId)
        var slot: ResidentRuntimeSlot? = null
        synchronized(lock) {
            if (loaded) {
                slot = attachResidentSlotLocked(
                    modelId = modelId,
                    slotId = slotId,
                    sessionCacheIdentity = sessionCacheIdentity,
                    keepAliveMs = safeKeepAliveMs,
                )
            }
        }
        slot?.let(onAfterLoad)
        return loaded
    }

    fun attachResidentSlot(
        modelId: String,
        slotId: String,
        keepAliveMs: Long,
        sessionCacheIdentity: SessionCacheIdentity? = null,
    ): Boolean {
        val safeKeepAliveMs = keepAliveMs.coerceAtLeast(1L)
        var slot: ResidentRuntimeSlot? = null
        synchronized(lock) {
            slot = attachResidentSlotLocked(
                modelId = modelId,
                slotId = slotId,
                sessionCacheIdentity = sessionCacheIdentity,
                keepAliveMs = safeKeepAliveMs,
            )
        }
        slot?.let(onAfterLoad)
        return true
    }

    fun touch(slotId: String, keepAliveMs: Long): Boolean {
        synchronized(lock) {
            val current = residentSlot ?: return false
            if (current.slotId != slotId) {
                return false
            }
            refreshSlotLocked(current, keepAliveMs.coerceAtLeast(1L))
            return true
        }
    }

    fun touchKeepAlive(keepAliveMs: Long? = null): Boolean {
        synchronized(lock) {
            val current = residentSlot ?: return false
            refreshSlotLocked(current, keepAliveMs?.coerceAtLeast(1L) ?: current.keepAliveMs)
            return true
        }
    }

    fun shortenKeepAlive(maxKeepAliveMs: Long): Boolean {
        synchronized(lock) {
            val current = residentSlot ?: return false
            val nextKeepAliveMs = minOf(current.keepAliveMs, maxKeepAliveMs.coerceAtLeast(1L))
            refreshSlotLocked(current, nextKeepAliveMs)
            return true
        }
    }

    fun listResident(): List<ResidentRuntimeSlot> {
        synchronized(lock) {
            return residentSlot?.let(::listOf) ?: emptyList()
        }
    }

    fun unload(reason: String): Boolean {
        when (requestUnload(reason)) {
            RuntimeUnloadDisposition.UNLOADED,
            RuntimeUnloadDisposition.NO_RESIDENT_MODEL,
            RuntimeUnloadDisposition.QUEUED,
            -> Unit
        }
        return true
    }

    fun requestUnload(reason: String): RuntimeUnloadDisposition {
        var slotToUnload: ResidentRuntimeSlot? = null
        val sanitizedReason: String
        synchronized(lock) {
            val hasResident = residentSlot != null
            if (!hasResident) {
                return RuntimeUnloadDisposition.NO_RESIDENT_MODEL
            }
            sanitizedReason = sanitizeReason(reason)
            if (activeRequestCount > 0) {
                pendingUnloadReason = sanitizedReason
                return RuntimeUnloadDisposition.QUEUED
            }
            pendingUnloadReason = null
            lastUnloadReason = sanitizedReason
            slotToUnload = residentSlot
            cancelExpiryLocked()
            residentSlot = null
            updateNativeResidencyLocked()
        }
        slotToUnload?.let { onBeforeUnload(it, sanitizedReason) }
        if (slotToUnload != null) {
            inferenceModule.unloadModel()
        }
        return RuntimeUnloadDisposition.UNLOADED
    }

    fun onTrimMemory(level: Int): Boolean {
        return when {
            level >= TRIM_MEMORY_RUNNING_CRITICAL -> unload(reason = "trim_memory_$level")
            level >= TRIM_MEMORY_RUNNING_LOW -> shortenKeepAlive(15_000L)
            level >= TRIM_MEMORY_RUNNING_MODERATE -> shortenKeepAlive(60_000L)
            level >= TRIM_MEMORY_UI_HIDDEN -> shortenKeepAlive(120_000L)
            else -> false
        }
    }

    fun onAppBackground(): Boolean {
        synchronized(lock) {
            if (autoReleaseDisableReasons.isNotEmpty()) {
                return false
            }
            val modelId = residentSlot?.modelId
            if (modelId != null) {
                wasAutoReleased = true
                lastAutoReleasedModelId = modelId
            }
        }
        return unload(reason = "app_background")
    }

    fun addAutoReleaseDisableReason(reason: String) {
        synchronized(lock) {
            autoReleaseDisableReasons.add(reason)
        }
    }

    fun removeAutoReleaseDisableReason(reason: String) {
        synchronized(lock) {
            autoReleaseDisableReasons.remove(reason)
        }
    }

    fun shouldAutoRelease(): Boolean {
        synchronized(lock) {
            return autoReleaseDisableReasons.isEmpty()
        }
    }

    fun clearAutoReleasedState() {
        wasAutoReleased = false
        lastAutoReleasedModelId = null
    }

    fun onGenerationStarted() {
        synchronized(lock) {
            activeRequestCount += 1
            if (activeRequestCount == 1) {
                autoReleaseDisableReasons.add(AUTO_RELEASE_REASON_ACTIVE_GENERATION)
            }
            cancelExpiryLocked()
        }
    }

    fun onGenerationFinished(slotId: String?, keepAliveMs: Long?) {
        var queuedReason: String? = null
        synchronized(lock) {
            activeRequestCount = (activeRequestCount - 1).coerceAtLeast(0)
            if (activeRequestCount == 0) {
                autoReleaseDisableReasons.remove(AUTO_RELEASE_REASON_ACTIVE_GENERATION)
            }
            if (slotId != null && keepAliveMs != null) {
                val current = residentSlot
                if (current != null && current.slotId == slotId) {
                    refreshSlotLocked(current, keepAliveMs.coerceAtLeast(1L))
                }
            }
            if (activeRequestCount == 0) {
                queuedReason = pendingUnloadReason
                pendingUnloadReason = null
            }
        }
        val reason = queuedReason ?: return
        requestUnload(reason)
    }

    fun loadedModelId(): String? {
        synchronized(lock) {
            return residentSlot?.modelId
        }
    }

    fun queueDepth(): Int {
        synchronized(lock) {
            return activeRequestCount
        }
    }

    fun diagnosticsLine(residencyState: RuntimeResidencyState?): String {
        val slot = synchronized(lock) { residentSlot }
        val residentModel = slot?.modelId ?: residencyState?.key?.modelId.orEmpty()
        val slotId = slot?.slotId.orEmpty()
        val expiresAt = slot?.expiresAtEpochMs?.toString().orEmpty()
        val reloadReason = residencyState?.reloadReason?.name?.lowercase().orEmpty()
        val loadMs = residencyState?.lastLoadDurationMs?.toString().orEmpty()
        val warmupMs = residencyState?.lastWarmupDurationMs?.toString().orEmpty()
        val queueDepth = queueDepth()
        return buildString {
            append("RUNTIME_RESIDENCY")
            append("|resident_model=")
            append(if (residentModel.isNotBlank()) residentModel else "none")
            append("|slot_id=")
            append(if (slotId.isNotBlank()) slotId else "none")
            append("|expires_at=")
            append(if (expiresAt.isNotBlank()) expiresAt else "none")
            append("|last_load_ms=")
            append(if (loadMs.isNotBlank()) loadMs else "none")
            append("|last_warmup_ms=")
            append(if (warmupMs.isNotBlank()) warmupMs else "none")
            append("|reload_reason=")
            append(if (reloadReason.isNotBlank()) reloadReason else "none")
            append("|queue_depth=")
            append(queueDepth)
            append("|last_unload_reason=")
            append(lastUnloadReason)
            append("|pending_unload=")
            append(
                synchronized(lock) {
                    if (pendingUnloadReason == null) "none" else pendingUnloadReason
                },
            )
        }
    }

    fun close() {
        synchronized(lock) {
            cancelExpiryLocked()
            pendingUnloadReason = null
            residentSlot = null
            activeRequestCount = 0
        }
        scope.cancel()
    }

    fun clearResidentAfterExternalUnload(reason: String) {
        cancelAndClear(reason)
    }

    private fun refreshSlotLocked(current: ResidentRuntimeSlot, keepAliveMs: Long) {
        residentSlot = current.copy(
            keepAliveMs = keepAliveMs,
            expiresAtEpochMs = nowMs() + keepAliveMs,
            lastTouchedAtEpochMs = nowMs(),
        )
        updateNativeResidencyLocked()
        if (activeRequestCount == 0) {
            scheduleExpiryLocked(keepAliveMs)
        }
    }

    private fun attachResidentSlotLocked(
        modelId: String,
        slotId: String,
        sessionCacheIdentity: SessionCacheIdentity?,
        keepAliveMs: Long,
    ): ResidentRuntimeSlot {
        val slot = ResidentRuntimeSlot(
            modelId = modelId,
            slotId = slotId,
            sessionCacheIdentity = sessionCacheIdentity,
            keepAliveMs = keepAliveMs,
            expiresAtEpochMs = nowMs() + keepAliveMs,
            lastTouchedAtEpochMs = nowMs(),
        )
        residentSlot = slot
        updateNativeResidencyLocked()
        if (activeRequestCount == 0) {
            scheduleExpiryLocked(keepAliveMs)
        } else {
            cancelExpiryLocked()
        }
        return slot
    }

    private fun cancelAndClear(reason: String) {
        synchronized(lock) {
            lastUnloadReason = sanitizeReason(reason)
            cancelExpiryLocked()
            residentSlot = null
            pendingUnloadReason = null
            updateNativeResidencyLocked()
        }
    }

    private fun scheduleExpiryLocked(delayMs: Long) {
        cancelExpiryLocked()
        val current = residentSlot ?: return
        val safeDelay = delayMs.coerceAtLeast(1L)
        expiryJob = scope.launch {
            delay(safeDelay)
            var slotToUnload: ResidentRuntimeSlot? = null
            synchronized(lock) {
                if (activeRequestCount > 0) {
                    scheduleExpiryLocked(safeDelay)
                    return@launch
                }
                if (residentSlot?.slotId != current.slotId) {
                    return@launch
                }
                slotToUnload = residentSlot
                residentSlot = null
                lastUnloadReason = "idle_ttl"
                updateNativeResidencyLocked()
            }
            slotToUnload?.let {
                onBeforeUnload(it, "idle_ttl")
                inferenceModule.unloadModel()
            }
        }
    }

    private fun updateNativeResidencyLocked() {
        runtimeInferencePorts.residency?.updateResidencySlot(
            slotId = residentSlot?.slotId,
            expiresAtEpochMs = residentSlot?.expiresAtEpochMs,
        )
    }

    private fun cancelExpiryLocked() {
        expiryJob?.cancel()
        expiryJob = null
    }

    private companion object {
        // Mirror Android ComponentCallbacks2 trim levels so residency policy can run in common code.
        private const val TRIM_MEMORY_UI_HIDDEN = 20
        private const val TRIM_MEMORY_RUNNING_MODERATE = 5
        private const val TRIM_MEMORY_RUNNING_LOW = 10
        private const val TRIM_MEMORY_RUNNING_CRITICAL = 15
        private const val AUTO_RELEASE_REASON_ACTIVE_GENERATION = "active_generation"
    }

    private fun sanitizeReason(reason: String): String {
        return reason.trim().lowercase().replace(Regex("[^a-z0-9_]+"), "_").ifBlank { "manual" }
    }
}

internal enum class RuntimeUnloadDisposition {
    UNLOADED,
    QUEUED,
    NO_RESIDENT_MODEL,
}
