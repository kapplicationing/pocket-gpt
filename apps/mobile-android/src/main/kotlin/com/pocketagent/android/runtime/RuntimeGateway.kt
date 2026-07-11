package com.pocketagent.android.runtime

import android.util.Log
import com.pocketagent.android.RuntimeFacadeAvailability
import com.pocketagent.android.RuntimeFacadeUnavailableException
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.ImageAnalysisResult
import com.pocketagent.runtime.ChatStreamCommand
import com.pocketagent.runtime.ChatStreamRequestPlanner
import com.pocketagent.runtime.MvpRuntimeFacade
import com.pocketagent.runtime.PreparedChatStream
import com.pocketagent.runtime.RuntimeLoadedModel
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult
import com.pocketagent.runtime.RuntimeRecoveryDisposition
import com.pocketagent.runtime.RuntimeResourceControl
import com.pocketagent.runtime.RuntimeWarmupSupport
import com.pocketagent.runtime.StreamChatRequestV2
import com.pocketagent.runtime.ToolExecutionResult
import com.pocketagent.runtime.WarmupResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach

data class DeviceGpuOffloadAdvisory(
    val isArm64V8a: Boolean = true,
    val isEmulator: Boolean = false,
    val isAdrenoFamily: Boolean = true,
    val hasArmDotProd: Boolean = true,
    val hasArmI8mm: Boolean = true,
    val adrenoGeneration: Int = 0,
    val supportedForProbe: Boolean = true,
    val automaticOpenClEligible: Boolean = true,
    val reason: String = "assumed_supported",
) {
    fun cacheIdentity(): String {
        return listOf(
            "arm64=$isArm64V8a",
            "emulator=$isEmulator",
            "adreno=$isAdrenoFamily",
            "dotprod=$hasArmDotProd",
            "i8mm=$hasArmI8mm",
            "adrenoGen=$adrenoGeneration",
            "probeSupported=$supportedForProbe",
            "autoOpenCl=$automaticOpenClEligible",
            "reason=$reason",
        ).joinToString(separator = "|")
    }
}

interface DeviceGpuOffloadSupport {
    fun advisory(): DeviceGpuOffloadAdvisory

    fun isSupported(): Boolean = advisory().supportedForProbe

    companion object {
        val ASSUME_SUPPORTED = object : DeviceGpuOffloadSupport {
            override fun advisory(): DeviceGpuOffloadAdvisory = DeviceGpuOffloadAdvisory()
        }
    }
}

enum class RuntimeSessionUnavailableReason {
    REPLACING,
    CLOSING,
    CLOSED,
    ;

    val isTransient: Boolean
        get() = this != CLOSED
}

sealed interface RuntimeSessionCreationResult {
    data class Created(val sessionId: SessionId) : RuntimeSessionCreationResult

    data class Unavailable(
        val reason: RuntimeSessionUnavailableReason,
        val errorCode: String,
        val userMessage: String,
        val recoveryDisposition: RuntimeRecoveryDisposition,
    ) : RuntimeSessionCreationResult
}

internal class RuntimeSessionUnavailableException(
    val unavailable: RuntimeSessionCreationResult.Unavailable,
) : IllegalStateException(unavailable.userMessage) {
    val errorCode: String get() = unavailable.errorCode
    val recoveryDisposition: RuntimeRecoveryDisposition get() = unavailable.recoveryDisposition
}

interface ChatRuntimeService {
    /** Boundary implementation only. Production callers must use [createRuntimeSession]. */
    fun createSession(): SessionId

    fun createRuntimeSession(): RuntimeSessionCreationResult {
        return try {
            RuntimeSessionCreationResult.Created(createSession())
        } catch (error: RuntimeFacadeUnavailableException) {
            error.availability.toRuntimeSessionUnavailable()
        }
    }

    fun prepareChatStream(command: ChatStreamCommand): PreparedChatStream =
        ChatStreamRequestPlanner(runtimeGenerationTimeoutMs = 0L).prepare(command)

    fun streamPreparedChat(prepared: PreparedChatStream): Flow<ChatStreamEvent>
    fun cancelGeneration(sessionId: SessionId): Boolean
    fun cancelGenerationByRequest(requestId: String): Boolean
    fun runTool(toolName: String, jsonArgs: String): ToolExecutionResult
    fun analyzeImage(imagePath: String, prompt: String): ImageAnalysisResult
    fun exportDiagnostics(): String
    fun setRoutingMode(mode: RoutingMode)
    fun getRoutingMode(): RoutingMode
    fun runStartupChecks(): List<String>
    fun restoreSession(sessionId: SessionId, turns: List<Turn>)
    fun deleteSession(sessionId: SessionId): Boolean
    fun runtimeBackend(): String?
    fun runtimeDiagnosticsSnapshot(): RuntimeDiagnosticsSnapshot = RuntimeDiagnosticsSnapshot()
    fun supportsGpuOffload(): Boolean
    fun warmupActiveModel(): WarmupResult = WarmupResult.skipped("warmup_unsupported")
    fun loadModel(modelId: String, modelVersion: String? = null): RuntimeModelLifecycleCommandResult =
        RuntimeModelLifecycleCommandResult.rejected(
            code = com.pocketagent.nativebridge.ModelLifecycleErrorCode.UNKNOWN,
            detail = "runtime_model_load_unsupported",
        )
    fun offloadModel(reason: String = "manual"): RuntimeModelLifecycleCommandResult =
        RuntimeModelLifecycleCommandResult.applied()
    fun loadedModel(): RuntimeLoadedModel? = null
    fun activeGenerationCount(): Int = 0
    fun reportGpuRuntimeFailure(reason: GpuProbeFailureReason, detail: String? = null) = Unit
    fun evictResidentModel(reason: String = "manual"): Boolean = false
    fun touchKeepAlive(): Boolean = false
    fun shortenKeepAlive(ttlMs: Long): Boolean = false
    fun onTrimMemory(level: Int): Boolean = false
    fun onAppBackground(): Boolean = false
    fun onAppForeground(): Boolean = false
    fun addAutoReleaseDisableReason(reason: String) = Unit
    fun removeAutoReleaseDisableReason(reason: String) = Unit
    fun gpuOffloadStatus(): GpuProbeResult = if (supportsGpuOffload()) {
        GpuProbeResult(status = GpuProbeStatus.QUALIFIED, maxStableGpuLayers = 32)
    } else {
        GpuProbeResult(
            status = GpuProbeStatus.FAILED,
            failureReason = GpuProbeFailureReason.UNKNOWN,
            detail = "gpu_offload_unsupported",
        )
    }
}

private fun RuntimeFacadeAvailability.toRuntimeSessionUnavailable(): RuntimeSessionCreationResult.Unavailable {
    return when (this) {
        RuntimeFacadeAvailability.READY -> error("Ready runtime cannot reject session creation.")
        RuntimeFacadeAvailability.REPLACING -> RuntimeSessionCreationResult.Unavailable(
            reason = RuntimeSessionUnavailableReason.REPLACING,
            errorCode = "RUNTIME_REPLACEMENT_IN_PROGRESS",
            userMessage = "Runtime is switching models. Try creating the chat again shortly.",
            recoveryDisposition = RuntimeRecoveryDisposition.RETRY_REQUEST,
        )
        RuntimeFacadeAvailability.CLOSING -> RuntimeSessionCreationResult.Unavailable(
            reason = RuntimeSessionUnavailableReason.CLOSING,
            errorCode = "RUNTIME_CLOSE_IN_PROGRESS",
            userMessage = "Runtime is shutting down. Try creating the chat again shortly.",
            recoveryDisposition = RuntimeRecoveryDisposition.RETRY_REQUEST,
        )
        RuntimeFacadeAvailability.CLOSED -> RuntimeSessionCreationResult.Unavailable(
            reason = RuntimeSessionUnavailableReason.CLOSED,
            errorCode = "RUNTIME_CLOSED",
            userMessage = "Runtime is unavailable. Restart the app before creating another chat.",
            recoveryDisposition = RuntimeRecoveryDisposition.RESTART_RUNTIME,
        )
    }
}

class MvpRuntimeGateway(
    private val facade: MvpRuntimeFacade,
    private val deviceGpuOffloadSupport: DeviceGpuOffloadSupport = DeviceGpuOffloadSupport.ASSUME_SUPPORTED,
    private val gpuOffloadQualifier: GpuOffloadQualifier = GpuOffloadQualifier.DISABLED,
    private val runtimeTuning: RuntimeTuning = RuntimeTuning.DISABLED,
) : ChatRuntimeService {
    private val tag = "RuntimeGateway"
    private val gpuStatusLock = Any()
    @Volatile
    private var gpuStatusCache: GpuProbeResult? = null
    @Volatile
    private var gpuStatusCachedAtMs: Long = 0L
    private val diagnosticsLock = Any()
    @Volatile
    private var diagnosticsCache: RuntimeDiagnosticsSnapshot? = null
    @Volatile
    private var diagnosticsCachedAtMs: Long = 0L
    private val planner = ChatStreamRequestPlanner(
        runtimeGenerationTimeoutMs = 0L,
        recommendedConfig = { modelIdHint, baseConfig, gpuQualifiedLayers ->
            runtimeTuning.applyRecommendedConfig(
                modelIdHint = modelIdHint,
                baseConfig = baseConfig,
                gpuQualifiedLayers = gpuQualifiedLayers,
            )
        },
    )

    override fun createSession(): SessionId = facade.createSession()

    override fun prepareChatStream(command: ChatStreamCommand): PreparedChatStream {
        return planner.prepare(command)
    }

    override fun streamPreparedChat(prepared: PreparedChatStream): Flow<ChatStreamEvent> {
        val request = prepared.runtimeRequest
        return facade.streamChat(request)
            .onEach { event ->
                if (event is ChatStreamEvent.Failed) {
                    maybeDemoteGpuAfterFailure(
                        request = request,
                        errorCode = event.errorCode,
                        message = event.message,
                    )
                }
            }
            .catch { error ->
                if (isGpuRequested(request) && error !is kotlinx.coroutines.TimeoutCancellationException) {
                    reportGpuRuntimeFailure(
                        reason = GpuProbeFailureReason.NATIVE_GENERATE_FAILED,
                        detail = "stream_exception:${error.message ?: error::class.simpleName}",
                    )
                }
                throw error
            }
    }

    override fun cancelGeneration(sessionId: SessionId): Boolean = facade.cancelGeneration(sessionId)

    override fun cancelGenerationByRequest(requestId: String): Boolean = facade.cancelGenerationByRequest(requestId)

    override fun runTool(toolName: String, jsonArgs: String): ToolExecutionResult {
        return facade.runToolDetailed(toolName = toolName, jsonArgs = jsonArgs)
    }

    override fun analyzeImage(imagePath: String, prompt: String): ImageAnalysisResult {
        return facade.analyzeImageDetailed(imagePath = imagePath, prompt = prompt)
    }

    override fun exportDiagnostics(): String {
        val probe = gpuOffloadStatus()
        val runtimeSupported = runCatching { facade.supportsGpuOffload() }.getOrElse { false }
        return facade.exportDiagnostics() +
            buildGpuDiagnosticsFooter(runtimeSupported = runtimeSupported, probe = probe)
    }

    override fun setRoutingMode(mode: RoutingMode) {
        facade.setRoutingMode(mode)
    }

    override fun getRoutingMode(): RoutingMode = facade.getRoutingMode()

    override fun runStartupChecks(): List<String> = facade.runStartupChecks()

    override fun restoreSession(sessionId: SessionId, turns: List<Turn>) {
        facade.restoreSession(sessionId = sessionId, turns = turns)
    }

    override fun deleteSession(sessionId: SessionId): Boolean = facade.deleteSession(sessionId)

    override fun runtimeBackend(): String? = facade.runtimeBackend()

    override fun runtimeDiagnosticsSnapshot(): RuntimeDiagnosticsSnapshot {
        val now = System.currentTimeMillis()
        diagnosticsCache?.let { cached ->
            if (now - diagnosticsCachedAtMs < RUNTIME_DIAGNOSTICS_CACHE_TTL_MS) {
                return cached
            }
        }
        synchronized(diagnosticsLock) {
            val again = System.currentTimeMillis()
            diagnosticsCache?.let { cached ->
                if (again - diagnosticsCachedAtMs < RUNTIME_DIAGNOSTICS_CACHE_TTL_MS) {
                    return cached
                }
            }
            val rawFacade = runCatching { facade.exportDiagnostics() }.getOrElse { "" }
            val probe = gpuOffloadStatus()
            val runtimeSupported = runCatching { facade.supportsGpuOffload() }.getOrElse { false }
            val footer = buildGpuDiagnosticsFooter(runtimeSupported = runtimeSupported, probe = probe)
            val parsed = RuntimeDiagnosticsSnapshotParser.parse(rawFacade + footer)
            diagnosticsCache = parsed
            diagnosticsCachedAtMs = again
            return parsed
        }
    }

    override fun loadModel(modelId: String, modelVersion: String?): RuntimeModelLifecycleCommandResult {
        return (facade as? RuntimeResourceControl)?.loadModel(modelId = modelId, modelVersion = modelVersion)
            ?: RuntimeModelLifecycleCommandResult.rejected(
                code = com.pocketagent.nativebridge.ModelLifecycleErrorCode.UNKNOWN,
                detail = "runtime_model_load_unsupported",
            )
    }

    override fun offloadModel(reason: String): RuntimeModelLifecycleCommandResult {
        return (facade as? RuntimeResourceControl)?.offloadModel(reason = reason)
            ?: RuntimeModelLifecycleCommandResult.applied()
    }

    override fun loadedModel(): RuntimeLoadedModel? {
        return (facade as? RuntimeResourceControl)?.loadedModel()
    }

    override fun activeGenerationCount(): Int {
        return (facade as? RuntimeResourceControl)?.activeGenerationCount() ?: 0
    }

    override fun evictResidentModel(reason: String): Boolean {
        return (facade as? RuntimeResourceControl)?.evictResidentModel(reason) ?: false
    }

    override fun touchKeepAlive(): Boolean {
        return (facade as? RuntimeResourceControl)?.touchKeepAlive() ?: false
    }

    override fun shortenKeepAlive(ttlMs: Long): Boolean {
        return (facade as? RuntimeResourceControl)?.shortenKeepAlive(ttlMs) ?: false
    }

    override fun onTrimMemory(level: Int): Boolean {
        return (facade as? RuntimeResourceControl)?.onTrimMemory(level) ?: false
    }

    override fun onAppBackground(): Boolean {
        return (facade as? RuntimeResourceControl)?.onAppBackground() ?: false
    }

    override fun onAppForeground(): Boolean {
        return (facade as? RuntimeResourceControl)?.onAppForeground() ?: false
    }

    override fun addAutoReleaseDisableReason(reason: String) {
        (facade as? RuntimeResourceControl)?.addAutoReleaseDisableReason(reason)
    }

    override fun removeAutoReleaseDisableReason(reason: String) {
        (facade as? RuntimeResourceControl)?.removeAutoReleaseDisableReason(reason)
    }

    override fun supportsGpuOffload(): Boolean {
        val status = gpuOffloadStatus()
        return status.status == GpuProbeStatus.QUALIFIED && status.maxStableGpuLayers > 0
    }

    override fun warmupActiveModel(): WarmupResult {
        return (facade as? RuntimeWarmupSupport)?.warmupActiveModel()
            ?: WarmupResult.skipped("warmup_unsupported")
    }

    override fun reportGpuRuntimeFailure(reason: GpuProbeFailureReason, detail: String?) {
        invalidateGpuAndDiagnosticsCaches()
            runCatching { gpuOffloadQualifier.reportRuntimeFailure(reason = reason, detail = detail) }
            .onFailure { error ->
                val errorName = error.message ?: error::class.simpleName
                safeLogInfo("GPU_OFFLOAD|demote_failed|reason=$reason|detail=${detail.orEmpty()}|error=$errorName")
            }
    }

    override fun gpuOffloadStatus(): GpuProbeResult {
        val now = System.currentTimeMillis()
        gpuStatusCache?.let { cached ->
            if (now - gpuStatusCachedAtMs < GPU_OFFLOAD_STATUS_CACHE_TTL_MS) {
                return cached
            }
        }
        synchronized(gpuStatusLock) {
            val t = System.currentTimeMillis()
            gpuStatusCache?.let { cached ->
                if (t - gpuStatusCachedAtMs < GPU_OFFLOAD_STATUS_CACHE_TTL_MS) {
                    return cached
                }
            }
            val probe = computeGpuOffloadStatusUncached()
            gpuStatusCache = probe
            gpuStatusCachedAtMs = t
            return probe
        }
    }

    private fun computeGpuOffloadStatusUncached(): GpuProbeResult {
        val runtimeSupported = runCatching { facade.supportsGpuOffload() }.getOrElse { false }
        val deviceAdvisory = runCatching { deviceGpuOffloadSupport.advisory() }.getOrElse {
            DeviceGpuOffloadAdvisory(
                supportedForProbe = false,
                automaticOpenClEligible = false,
                reason = "advisory_query_failed:${it.message ?: it::class.simpleName}",
            )
        }
        val probe = runCatching { gpuOffloadQualifier.evaluate(runtimeSupported, deviceAdvisory) }.getOrElse {
            GpuProbeResult(
                status = GpuProbeStatus.FAILED,
                failureReason = GpuProbeFailureReason.UNKNOWN,
                detail = "probe_evaluation_failed:${it.message ?: it::class.simpleName}",
            )
        }
        if (runtimeSupported != deviceAdvisory.supportedForProbe || probe.status != GpuProbeStatus.QUALIFIED) {
            safeLogInfo(
                "GPU_OFFLOAD|eligibility|runtime_supported=$runtimeSupported|" +
                    "device_feature_advisory_supported=${deviceAdvisory.supportedForProbe}|" +
                    "device_feature_release_opencl_eligible=${deviceAdvisory.automaticOpenClEligible}|" +
                    "device_feature_reason=${deviceAdvisory.reason}|" +
                    "probe_status=${probe.status}|probe_layers=${probe.maxStableGpuLayers}|" +
                    "probe_reason=${probe.failureReason ?: "none"}|authoritative=runtime_plus_probe",
            )
        }
        return probe
    }

    internal fun invalidatePerformanceCaches() {
        invalidateGpuAndDiagnosticsCaches()
    }

    private fun invalidateGpuAndDiagnosticsCaches() {
        synchronized(gpuStatusLock) {
            gpuStatusCache = null
            gpuStatusCachedAtMs = 0L
        }
        synchronized(diagnosticsLock) {
            diagnosticsCache = null
            diagnosticsCachedAtMs = 0L
        }
    }

    private fun buildGpuDiagnosticsFooter(runtimeSupported: Boolean, probe: GpuProbeResult): String {
        val deviceAdvisory = runCatching { deviceGpuOffloadSupport.advisory() }.getOrElse {
            DeviceGpuOffloadAdvisory(
                supportedForProbe = false,
                automaticOpenClEligible = false,
                reason = "advisory_query_failed:${it.message ?: it::class.simpleName}",
            )
        }
        val tuningDiagnostics = runtimeTuning.diagnosticsReport().takeIf { it.isNotBlank() }
        return buildString {
            appendLine()
            append(
                "GPU_OFFLOAD|runtime_supported=$runtimeSupported|" +
                    "device_feature_advisory_supported=${deviceAdvisory.supportedForProbe}|" +
                    "device_feature_release_opencl_eligible=${deviceAdvisory.automaticOpenClEligible}|" +
                    "device_feature_arm64=${deviceAdvisory.isArm64V8a}|" +
                    "device_feature_emulator=${deviceAdvisory.isEmulator}|" +
                    "device_feature_adreno=${deviceAdvisory.isAdrenoFamily}|" +
                    "device_feature_dotprod=${deviceAdvisory.hasArmDotProd}|" +
                    "device_feature_i8mm=${deviceAdvisory.hasArmI8mm}|" +
                    "device_feature_adreno_gen=${deviceAdvisory.adrenoGeneration}|" +
                    "device_feature_reason=${deviceAdvisory.reason}|" +
                    "probe_status=${probe.status}|probe_layers=${probe.maxStableGpuLayers}|" +
                    "probe_reason=${probe.failureReason ?: "none"}|" +
                    "probe_source=runtime_plus_probe|probe_detail=${probe.detail.orEmpty()}",
            )
            appendLine()
            append(gpuOffloadQualifier.diagnosticsLine())
            tuningDiagnostics?.let {
                appendLine()
                append(it)
            }
        }
    }

    private fun safeLogInfo(message: String) {
        runCatching { Log.i(tag, message) }
    }

    private fun isGpuRequested(request: StreamChatRequestV2): Boolean {
        val config = request.performanceConfig
        return config.gpuEnabled && config.gpuLayers > 0
    }

    private fun maybeDemoteGpuAfterFailure(
        request: StreamChatRequestV2,
        errorCode: String,
        message: String,
    ) {
        if (!isGpuRequested(request)) {
            return
        }
        if (!shouldDemoteForFailure(errorCode = errorCode, message = message)) {
            return
        }
        reportGpuRuntimeFailure(
            reason = GpuProbeFailureReason.NATIVE_GENERATE_FAILED,
            detail = "stream_failed:code=$errorCode|message=${message.take(240)}",
        )
    }

    private fun shouldDemoteForFailure(errorCode: String, message: String): Boolean {
        val code = errorCode.trim().lowercase()
        if (code == "template_unavailable") {
            return false
        }
        if (code.containsAnyGpuFailureToken()) {
            return true
        }
        val normalizedMessage = message.lowercase()
        if (normalizedMessage.containsAnyGpuFailureToken()) {
            return true
        }
        return code == "runtime_error"
    }
}

private fun String.containsAnyGpuFailureToken(): Boolean {
    return GPU_FAILURE_TOKENS.any { token -> contains(token) }
}

private val GPU_FAILURE_TOKENS = listOf(
    "jni",
    "gpu",
    "opencl",
    "hexagon",
    "backend",
    "remote_process_died",
    "remote_runtime",
    "n_gpu_layers",
    "native load",
)

private const val GPU_OFFLOAD_STATUS_CACHE_TTL_MS = 5_000L
private const val RUNTIME_DIAGNOSTICS_CACHE_TTL_MS = 2_000L
