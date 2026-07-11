package com.pocketagent.android.runtime

import android.content.Context
import com.pocketagent.nativebridge.BridgeError
import com.pocketagent.nativebridge.CachePolicy
import com.pocketagent.nativebridge.GenerationFinishReason
import com.pocketagent.nativebridge.GenerationResult
import com.pocketagent.nativebridge.LlamaCppRuntimeBridge
import com.pocketagent.nativebridge.LoadedModelInfo
import com.pocketagent.nativebridge.ModelLifecycleError
import com.pocketagent.nativebridge.ModelLifecycleErrorCode
import com.pocketagent.nativebridge.ModelLifecycleEvent
import com.pocketagent.nativebridge.ModelLifecycleState
import com.pocketagent.nativebridge.ModelLoadOptions
import com.pocketagent.nativebridge.RuntimeBackend
import com.pocketagent.nativebridge.RuntimeCloseResult
import com.pocketagent.nativebridge.RuntimeGenerationConfig

internal class RemoteLlamaCppRuntimeBridge internal constructor(
    private val transport: RemoteRuntimeTransport,
) : LlamaCppRuntimeBridge {
    private val lifecycleLock = Any()
    private val lifecycleObserverLock = Any()
    private var nextLifecycleObserverId: Int = 1
    private val lifecycleObservers: MutableMap<Int, (ModelLifecycleEvent) -> Unit> = mutableMapOf()

    constructor(context: Context) : this(MessengerRemoteRuntimeTransport(context.applicationContext))

    private data class LoadedModelState(
        val modelId: String,
        val modelPath: String?,
        val modelVersion: String? = null,
    )

    @Volatile
    private var runtimeGenerationConfig: RuntimeGenerationConfig = RuntimeGenerationConfig.default()
    @Volatile
    private var lastBridgeError: BridgeError? = null
    @Volatile
    private var loadedModelState: LoadedModelState? = null
    @Volatile
    private var preparedEpoch: Long = Long.MIN_VALUE
    @Volatile
    private var lifecycleEvent: ModelLifecycleEvent = ModelLifecycleEvent(state = ModelLifecycleState.UNLOADED)

    override fun isReady(): Boolean {
        val ready = transport.ping()
        if (!ready) {
            recordError(REMOTE_ERROR_BIND_FAILED, "remote runtime ping failed")
        } else {
            clearError()
        }
        return ready
    }

    override fun listAvailableModels(): List<String> = transport.listAvailableModels()

    override fun loadModel(modelId: String, modelPath: String?): Boolean {
        return loadModel(modelId = modelId, modelPath = modelPath, options = ModelLoadOptions())
    }

    override fun loadModel(modelId: String, modelPath: String?, options: ModelLoadOptions): Boolean {
        emitLifecycleEvent(
            ModelLifecycleEvent(
                state = ModelLifecycleState.LOADING,
                modelId = modelId,
                modelVersion = options.modelVersion,
            ),
        )
        if (!syncConfigToRemote()) {
            emitLifecycleFailure(
                modelId = modelId,
                modelVersion = options.modelVersion,
                errorCode = ModelLifecycleErrorCode.BACKEND_INIT_FAILED,
                detail = lastBridgeError?.detail,
            )
            return false
        }
        val loaded = transport.loadModel(modelId = modelId, modelPath = modelPath)
        if (!loaded) {
            val remoteError = transport.lastError()
            recordError(
                remoteError?.code ?: REMOTE_ERROR_RUNTIME,
                remoteError?.detail ?: "remote load failed:modelId=$modelId",
            )
            emitLifecycleFailure(
                modelId = modelId,
                modelVersion = options.modelVersion,
                errorCode = mapRemoteErrorToLifecycleCode(remoteError?.code),
                detail = remoteError?.detail,
            )
            return false
        }
        loadedModelState = LoadedModelState(
            modelId = modelId,
            modelPath = modelPath,
            modelVersion = options.modelVersion,
        )
        preparedEpoch = transport.epoch()
        clearError()
        emitLifecycleEvent(
            ModelLifecycleEvent(
                state = ModelLifecycleState.LOADED,
                modelId = modelId,
                modelVersion = options.modelVersion,
            ),
        )
        return true
    }

    override fun setRuntimeGenerationConfig(config: RuntimeGenerationConfig) {
        runtimeGenerationConfig = config
        preparedEpoch = Long.MIN_VALUE
        if (!transport.setRuntimeGenerationConfig(config)) {
            recordError(REMOTE_ERROR_RUNTIME, "remote set config failed")
            return
        }
        clearError()
    }

    override fun getRuntimeGenerationConfig(): RuntimeGenerationConfig = runtimeGenerationConfig

    override fun supportsGpuOffload(): Boolean {
        val supported = transport.supportsGpuOffload()
        if (!supported) {
            recordError(REMOTE_ERROR_RUNTIME, "remote supportsGpuOffload returned false")
        } else {
            clearError()
        }
        return supported
    }

    override fun generate(
        requestId: String,
        prompt: String,
        maxTokens: Int,
        cacheKey: String?,
        cachePolicy: CachePolicy,
        onToken: (String) -> Unit,
    ): GenerationResult {
        if (!prepareRuntimeState()) {
            return GenerationResult(
                finishReason = GenerationFinishReason.ERROR,
                tokenCount = 0,
                firstTokenMs = -1L,
                totalMs = 0L,
                cancelled = false,
                errorCode = lastBridgeError?.code ?: REMOTE_ERROR_RUNTIME,
            )
        }
        val result = transport.generate(
            requestId = requestId,
            prompt = prompt,
            maxTokens = maxTokens,
            cacheKey = cacheKey,
            cachePolicy = cachePolicy,
            onToken = onToken,
        )
        if (result.success || result.finishReason == GenerationFinishReason.CANCELLED) {
            clearError()
        } else {
            val code = result.errorCode ?: transport.lastError()?.code ?: REMOTE_ERROR_RUNTIME
            val detail = transport.lastError()?.detail ?: "remote generate failed:requestId=$requestId"
            recordError(code, detail)
            if (code == REMOTE_ERROR_PROCESS_DIED) {
                preparedEpoch = Long.MIN_VALUE
            }
        }
        return result
    }

    override fun cancelGeneration(): Boolean = cancelGenerationInternal(requestId = null)

    override fun cancelGeneration(requestId: String): Boolean = cancelGenerationInternal(requestId)

    private fun cancelGenerationInternal(requestId: String?): Boolean {
        val result = transport.cancelGeneration(requestId)
        if (result.cancelled) {
            clearError()
        } else {
            recordError(
                code = result.error?.code ?: REMOTE_ERROR_RUNTIME,
                detail = result.error?.detail ?: "remote cancel returned false",
            )
        }
        return result.cancelled
    }

    override fun unloadModel() {
        offloadModel(reason = "legacy_unload")
    }

    override fun offloadModel(reason: String): Boolean {
        val loaded = loadedModelState
        emitLifecycleEvent(
            ModelLifecycleEvent(
                state = ModelLifecycleState.OFFLOADING,
                modelId = loaded?.modelId,
                modelVersion = loaded?.modelVersion,
            ),
        )
        if (!transport.unloadModel()) {
            recordError(REMOTE_ERROR_RUNTIME, "remote unload failed:reason=$reason")
            emitLifecycleFailure(
                modelId = loaded?.modelId,
                modelVersion = loaded?.modelVersion,
                errorCode = ModelLifecycleErrorCode.UNKNOWN,
                detail = lastBridgeError?.detail,
            )
            return false
        }
        loadedModelState = null
        preparedEpoch = transport.epoch()
        emitLifecycleEvent(
            ModelLifecycleEvent(
                state = ModelLifecycleState.UNLOADED,
                modelId = loaded?.modelId,
                modelVersion = loaded?.modelVersion,
            ),
        )
        return true
    }

    override fun closeRuntime(timeoutMs: Long): RuntimeCloseResult {
        val unloaded = runCatching { offloadModel("runtime_close") }.getOrDefault(false)
        if (!unloaded) {
            return RuntimeCloseResult.rejected("REMOTE_RUNTIME_UNLOAD_FAILED")
        }
        synchronized(lifecycleObserverLock) {
            lifecycleObservers.clear()
        }
        return if (transport.close()) {
            RuntimeCloseResult.closed()
        } else {
            RuntimeCloseResult.terminated("REMOTE_RUNTIME_UNBIND_FAILED")
        }
    }

    override fun runtimeBackend(): RuntimeBackend {
        return if (transport.ping()) RuntimeBackend.REMOTE_ANDROID_SERVICE else RuntimeBackend.UNAVAILABLE
    }

    override fun lastError(): BridgeError? = lastBridgeError ?: transport.lastError()

    override fun backendDiagnosticsJson(): String? = transport.backendDiagnosticsJson()

    override fun getLoadedModel(): LoadedModelInfo? {
        val loaded = loadedModelState ?: return null
        return LoadedModelInfo(
            modelId = loaded.modelId,
            modelPath = loaded.modelPath,
            modelVersion = loaded.modelVersion,
        )
    }

    override fun currentModelLifecycleState(): ModelLifecycleEvent = lifecycleEvent

    override fun observeModelLifecycleState(listener: (ModelLifecycleEvent) -> Unit): AutoCloseable {
        val id = synchronized(lifecycleObserverLock) {
            val observerId = nextLifecycleObserverId++
            lifecycleObservers[observerId] = listener
            observerId
        }
        listener(lifecycleEvent)
        return AutoCloseable {
            synchronized(lifecycleObserverLock) {
                lifecycleObservers.remove(id)
            }
        }
    }

    private fun prepareRuntimeState(): Boolean {
        if (!syncConfigToRemote()) {
            return false
        }
        val model = loadedModelState ?: run {
            recordError(REMOTE_ERROR_MODEL_NOT_LOADED, "generate requested before loadModel")
            return false
        }
        if (preparedEpoch == transport.epoch()) {
            return true
        }
        val reloaded = transport.loadModel(model.modelId, model.modelPath)
        if (!reloaded) {
            val remoteError = transport.lastError()
            recordError(
                remoteError?.code ?: REMOTE_ERROR_RUNTIME,
                remoteError?.detail ?: "remote model reload failed:modelId=${model.modelId}",
            )
            return false
        }
        preparedEpoch = transport.epoch()
        clearError()
        return true
    }

    private fun syncConfigToRemote(): Boolean {
        val synced = transport.setRuntimeGenerationConfig(runtimeGenerationConfig)
        if (!synced) {
            recordError(REMOTE_ERROR_RUNTIME, "remote set config failed")
            return false
        }
        return true
    }

    private fun recordError(code: String, detail: String?) {
        lastBridgeError = BridgeError(code = code, detail = detail)
    }

    private fun clearError() {
        lastBridgeError = null
    }

    private fun emitLifecycleFailure(
        modelId: String?,
        modelVersion: String?,
        errorCode: ModelLifecycleErrorCode,
        detail: String?,
    ) {
        emitLifecycleEvent(
            ModelLifecycleEvent(
                state = ModelLifecycleState.FAILED,
                modelId = modelId,
                modelVersion = modelVersion,
                error = ModelLifecycleError(code = errorCode, detail = detail),
            ),
        )
    }

    private fun emitLifecycleEvent(event: ModelLifecycleEvent) {
        synchronized(lifecycleLock) {
            lifecycleEvent = event
        }
        val observers = synchronized(lifecycleObserverLock) {
            lifecycleObservers.values.toList()
        }
        observers.forEach { observer ->
            runCatching { observer(event) }
        }
    }

    private fun mapRemoteErrorToLifecycleCode(errorCode: String?): ModelLifecycleErrorCode {
        val normalized = errorCode?.trim()?.uppercase().orEmpty()
        return when {
            normalized.contains("MODEL_NOT_LOADED") ||
                normalized.contains("MODEL_UNAVAILABLE")
            -> ModelLifecycleErrorCode.MODEL_FILE_UNAVAILABLE

            normalized.contains("RUNTIME_INCOMPATIBLE") ||
                normalized.contains("COMPAT")
            -> ModelLifecycleErrorCode.RUNTIME_INCOMPATIBLE

            normalized.contains("BUSY") -> ModelLifecycleErrorCode.BUSY_GENERATION
            normalized.contains("CANCELLED_NEWER_REQUEST") -> ModelLifecycleErrorCode.CANCELLED_BY_NEWER_REQUEST
            normalized.contains("REMOTE") ||
                normalized.contains("RUNTIME")
            -> ModelLifecycleErrorCode.BACKEND_INIT_FAILED

            else -> ModelLifecycleErrorCode.UNKNOWN
        }
    }
}
