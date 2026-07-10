package com.pocketagent.nativebridge

import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

enum class RuntimeBackend {
    NATIVE_JNI,
    REMOTE_ANDROID_SERVICE,
    ADB_FALLBACK,
    UNAVAILABLE,
}

enum class GpuExecutionBackend {
    AUTO,
    HEXAGON,
    OPENCL,
    CPU,
}

enum class FlashAttnMode(val code: Int) {
    AUTO(0),
    ON(1),
    OFF(2);

    companion object {
        fun fromCode(code: Int): FlashAttnMode = entries.firstOrNull { it.code == code } ?: AUTO
    }
}

enum class CachePolicy(val code: Int) {
    OFF(0),
    PREFIX_KV_REUSE(1),
    PREFIX_KV_REUSE_STRICT(2),
}

enum class KvCachePreset(val code: Int) {
    SAFE(0),
    BALANCED(1),
    AGGRESSIVE(2);

    companion object {
        fun fromCode(code: Int): KvCachePreset = entries.firstOrNull { it.code == code } ?: SAFE
    }
}

data class RuntimeSamplingConfig(
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val minP: Float = 0.0f,
    val typicalP: Float = 1.0f,
    val repeatLastN: Int = 64,
    val repeatPenalty: Float = 1.0f,
    val frequencyPenalty: Float = 0.0f,
    val presencePenalty: Float = 0.0f,
    val mirostat: Int = 0,
    val mirostatTau: Float = 5.0f,
    val mirostatEta: Float = 0.1f,
    val xtcThreshold: Float = 0.1f,
    val xtcProbability: Float = 0.0f,
    val seed: Int = -1,
)

data class RuntimeLoadConfig(
    val nThreads: Int = 0,
    val nThreadsBatch: Int = 0,
    val nBatch: Int = 512,
    val nUbatch: Int = 512,
    val nCtx: Int = 2048,
    val gpuEnabled: Boolean = false,
    val gpuLayers: Int = 0,
    val gpuBackend: GpuExecutionBackend = GpuExecutionBackend.AUTO,
    val flashAttnMode: FlashAttnMode = FlashAttnMode.AUTO,
    val strictGpuOffload: Boolean = true,
    val kvCachePreset: KvCachePreset = KvCachePreset.SAFE,
    val speculativeEnabled: Boolean = false,
    val speculativeDraftModelId: String? = null,
    val speculativeDraftModelPath: String? = null,
    val speculativeMaxDraftTokens: Int = 6,
    val speculativeMinDraftTokens: Int = 2,
    val speculativeDraftGpuLayers: Int = 0,
    val useMmap: Boolean = true,
    val useMlock: Boolean = false,
)

data class RuntimeGenerationConfig(
    val nThreads: Int = 0,
    val nThreadsBatch: Int = 0,
    val nBatch: Int = 512,
    val nUbatch: Int = 512,
    val nCtx: Int = 2048,
    val gpuEnabled: Boolean = false,
    val gpuLayers: Int = 0,
    val gpuBackend: GpuExecutionBackend = GpuExecutionBackend.AUTO,
    val flashAttnMode: FlashAttnMode = FlashAttnMode.AUTO,
    val strictGpuOffload: Boolean = true,
    val kvCachePreset: KvCachePreset = KvCachePreset.SAFE,
    val sampling: RuntimeSamplingConfig = RuntimeSamplingConfig(),
    val speculativeEnabled: Boolean = false,
    val speculativeDraftModelId: String? = null,
    val speculativeDraftModelPath: String? = null,
    val speculativeMaxDraftTokens: Int = 6,
    val speculativeMinDraftTokens: Int = 2,
    val speculativeDraftGpuLayers: Int = 0,
    val useMmap: Boolean = true,
    val useMlock: Boolean = false,
    val nKeep: Int = 128,
) {
    companion object {
        fun default(): RuntimeGenerationConfig {
            return RuntimeGenerationConfig()
        }
    }

    fun toLoadConfig(): RuntimeLoadConfig {
        return RuntimeLoadConfig(
            nThreads = nThreads,
            nThreadsBatch = nThreadsBatch,
            nBatch = nBatch,
            nUbatch = nUbatch,
            nCtx = nCtx,
            gpuEnabled = gpuEnabled,
            gpuLayers = gpuLayers,
            gpuBackend = gpuBackend,
            flashAttnMode = flashAttnMode,
            strictGpuOffload = strictGpuOffload,
            kvCachePreset = kvCachePreset,
            speculativeEnabled = speculativeEnabled,
            speculativeDraftModelId = speculativeDraftModelId,
            speculativeDraftModelPath = speculativeDraftModelPath,
            speculativeMaxDraftTokens = speculativeMaxDraftTokens,
            speculativeMinDraftTokens = speculativeMinDraftTokens,
            speculativeDraftGpuLayers = speculativeDraftGpuLayers,
            useMmap = useMmap,
            useMlock = useMlock,
        )
    }
}

data class LoadedRuntimeKey(
    val modelId: String,
    val modelPath: String?,
    val backend: RuntimeBackend,
    val loadConfig: RuntimeLoadConfig,
)

data class ModelRuntimeMetadata(
    val layerCount: Int? = null,
    val sizeBytes: Long? = null,
    val contextLength: Int? = null,
    val embeddingSize: Int? = null,
    val headCountKv: Int? = null,
    val keyLength: Int? = null,
    val valueLength: Int? = null,
    val vocabSize: Int? = null,
    val slidingWindow: Int? = null,
    val architecture: String? = null,
)

enum class RuntimeReloadReason {
    INITIAL_LOAD,
    MODEL_CHANGED,
    MODEL_PATH_CHANGED,
    BACKEND_CHANGED,
    GENERATION_CONFIG_CHANGED,
    EXPLICIT_UNLOAD,
}

data class RuntimeResidencyState(
    val key: LoadedRuntimeKey? = null,
    val resident: Boolean = false,
    val residentHit: Boolean = false,
    val residentHitCount: Long = 0L,
    val slotId: String? = null,
    val expiresAtEpochMs: Long? = null,
    val reloadReason: RuntimeReloadReason? = null,
    val lastLoadDurationMs: Long? = null,
    val lastWarmupDurationMs: Long? = null,
    val lastLoadAtEpochMs: Long? = null,
    val lastAccessAtEpochMs: Long? = null,
)

enum class ModelLifecycleState {
    UNLOADED,
    LOADING,
    LOADED,
    OFFLOADING,
    FAILED,
}

enum class ModelLoadingStage {
    PRECHECK,
    UNLOADING_PREVIOUS,
    INITIALIZING_RUNTIME,
    LOADING_MODEL,
    RESTORING_SESSION_CACHE,
    WARMING_UP,
    COMPLETED,
}

enum class ModelLifecycleErrorCode {
    MODEL_FILE_UNAVAILABLE,
    RUNTIME_INCOMPATIBLE,
    BACKEND_INIT_FAILED,
    OUT_OF_MEMORY,
    BUSY_GENERATION,
    CANCELLED_BY_NEWER_REQUEST,
    UNKNOWN,
}

data class ModelLifecycleError(
    val code: ModelLifecycleErrorCode,
    val detail: String? = null,
)

data class ModelLoadOptions(
    val modelVersion: String? = null,
    val strictGpuOffload: Boolean = true,
)

data class LoadedModelInfo(
    val modelId: String,
    val modelPath: String?,
    val modelVersion: String? = null,
)

data class ModelLifecycleEvent(
    val state: ModelLifecycleState,
    val modelId: String? = null,
    val modelVersion: String? = null,
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val error: ModelLifecycleError? = null,
    val loadingDetail: String? = null,
    val loadingStage: ModelLoadingStage? = null,
    val loadingProgress: Float? = null,
)

enum class GenerationFinishReason {
    COMPLETED,
    MAX_TOKENS,
    CANCELLED,
    CALLBACK_ERROR,
    UTF8_STREAM_ERROR,
    ERROR,
}

data class GenerationResult(
    val finishReason: GenerationFinishReason,
    val tokenCount: Int,
    val firstTokenMs: Long,
    val totalMs: Long,
    val cancelled: Boolean,
    val prefillMs: Long? = null,
    val decodeMs: Long? = null,
    val tokensPerSec: Double? = null,
    val peakRssMb: Double? = null,
    val errorCode: String? = null,
    val kvCachePreset: KvCachePreset? = null,
) {
    val success: Boolean
        get() = finishReason == GenerationFinishReason.COMPLETED || finishReason == GenerationFinishReason.MAX_TOKENS
}

internal fun resolveActiveBackendIdentity(diagnosticsPayload: String?): String? {
    val payload = diagnosticsPayload?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val activeBackend = ACTIVE_BACKEND_PATTERN.find(payload)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() && it != "auto" }
    if (activeBackend != null) {
        return activeBackend
    }
    val compiledBackends = COMPILED_BACKEND_PATTERN.find(payload)
        ?.groupValues
        ?.getOrNull(1)
        ?.split(',')
        ?.map { token -> token.trim().lowercase() }
        ?.filter { token -> token.isNotEmpty() }
        .orEmpty()
        .distinct()
    return compiledBackends.singleOrNull()
        ?.takeIf { backend -> backend != "auto" }
}

interface LlamaCppRuntimeBridge {
    fun isReady(): Boolean
    fun listAvailableModels(): List<String>
    fun loadModel(modelId: String): Boolean = loadModel(modelId, null, ModelLoadOptions())
    fun loadModel(modelId: String, modelPath: String?): Boolean
    fun loadModel(modelId: String, modelPath: String?, options: ModelLoadOptions): Boolean =
        loadModel(modelId, modelPath)
    fun setRuntimeGenerationConfig(config: RuntimeGenerationConfig) {}
    fun getRuntimeGenerationConfig(): RuntimeGenerationConfig = RuntimeGenerationConfig.default()
    fun supportsGpuOffload(): Boolean = false
    fun modelLayerCount(): Int? = null
    fun modelSizeBytes(): Long? = null
    fun estimateMaxGpuLayers(nCtx: Int): Int? = null
    fun actualGpuLayers(): Int? = null
    fun actualDraftGpuLayers(): Int? = null
    fun lastGpuLoadRetryCount(): Int? = null
    fun currentRssMb(): Double? = null
    fun isRuntimeReleased(): Boolean = true
    fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit): GenerationResult =
        generate(
            requestId = "legacy",
            prompt = prompt,
            maxTokens = maxTokens,
            cacheKey = null,
            cachePolicy = CachePolicy.OFF,
            onToken = onToken,
        )
    fun generate(
        requestId: String,
        prompt: String,
        maxTokens: Int,
        cacheKey: String?,
        cachePolicy: CachePolicy,
        onToken: (String) -> Unit,
    ): GenerationResult
    fun cancelGeneration(): Boolean = false
    fun cancelGeneration(requestId: String): Boolean = cancelGeneration()
    fun unloadModel()
    fun offloadModel(reason: String): Boolean {
        unloadModel()
        return true
    }
    fun getLoadedModel(): LoadedModelInfo? = null
    fun currentModelLifecycleState(): ModelLifecycleEvent {
        val loaded = getLoadedModel()
        return if (loaded != null) {
            ModelLifecycleEvent(
                state = ModelLifecycleState.LOADED,
                modelId = loaded.modelId,
                modelVersion = loaded.modelVersion,
            )
        } else {
            ModelLifecycleEvent(state = ModelLifecycleState.UNLOADED)
        }
    }
    fun observeModelLifecycleState(listener: (ModelLifecycleEvent) -> Unit): AutoCloseable {
        listener(currentModelLifecycleState())
        return AutoCloseable { }
    }
    fun runtimeBackend(): RuntimeBackend
    fun lastError(): BridgeError? = null
    fun backendDiagnosticsJson(): String? = null
    fun activeBackendIdentity(): String? = resolveActiveBackendIdentity(backendDiagnosticsJson())
    fun setBackendProfile(profile: String) {}
    fun prefixCacheDiagnosticsLine(): String? = null
    fun saveSessionCache(filePath: String): Boolean = false
    fun loadSessionCache(filePath: String): Boolean = false
    fun initMultimodal(mmProjPath: String, useGpu: Boolean, imageMaxTokens: Int): Boolean = false
    fun freeMultimodal() {}
    fun isMultimodalEnabled(): Boolean = false
    fun generateWithImages(
        requestId: String,
        prompt: String,
        imagePaths: List<String>,
        maxTokens: Int,
        onToken: (String) -> Unit,
    ): GenerationResult = generate(
        requestId = requestId,
        prompt = prompt,
        maxTokens = maxTokens,
        cacheKey = null,
        cachePolicy = CachePolicy.OFF,
        onToken = onToken,
    )
}

private val ACTIVE_BACKEND_PATTERN = Regex(""""active_backend"\s*:\s*"([^"]+)"""")
private val COMPILED_BACKEND_PATTERN = Regex(""""compiled_backend"\s*:\s*"([^"]+)"""")

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

fun interface CommandRunner {
    fun run(command: List<String>): CommandResult
}

internal class ProcessCommandRunner : CommandRunner {
    override fun run(command: List<String>): CommandResult {
        return runCatching {
            val process = ProcessBuilder(command).start()
            val stdoutBytes = ByteArrayOutputStream()
            val stderrBytes = ByteArrayOutputStream()
            val stdoutDrain = thread(name = "process-command-runner-stdout", start = true) {
                process.inputStream.use { input -> input.copyTo(stdoutBytes) }
            }
            val stderrDrain = thread(name = "process-command-runner-stderr", start = true) {
                process.errorStream.use { input -> input.copyTo(stderrBytes) }
            }
            val exitCode = process.waitFor()
            stdoutDrain.join()
            stderrDrain.join()
            CommandResult(
                exitCode = exitCode,
                stdout = stdoutBytes.toString(Charsets.UTF_8.name()),
                stderr = stderrBytes.toString(Charsets.UTF_8.name()),
            )
        }.getOrElse { error ->
            CommandResult(
                exitCode = -1,
                stdout = "",
                stderr = error.message ?: error::class.simpleName.orEmpty(),
            )
        }
    }
}
