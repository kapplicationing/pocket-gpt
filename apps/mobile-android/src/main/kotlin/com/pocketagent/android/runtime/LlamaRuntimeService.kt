package com.pocketagent.android.runtime

import android.app.Service
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.pocketagent.nativebridge.BridgeError
import com.pocketagent.nativebridge.CachePolicy
import com.pocketagent.nativebridge.GenerationFinishReason
import com.pocketagent.nativebridge.GenerationResult
import com.pocketagent.nativebridge.NativeJniLlamaCppBridge
import com.pocketagent.nativebridge.RuntimeGenerationConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LlamaRuntimeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal var primaryBridge: NativeJniLlamaCppBridge = NativeJniLlamaCppBridge(fallbackEnabled = false)
    internal var probeRunnerFactory: () -> GpuProbeRunner = { GpuProbeRunner() }
    internal val ipcSendMonitor = IpcSendMonitor(tag = LOG_TAG)
    private val bridgeLock = Any()
    private val executionGate = LlamaRuntimeExecutionGate()
    private var currentConfig: RuntimeGenerationConfig = RuntimeGenerationConfig.default()
    private val serviceEpoch: Long = System.currentTimeMillis()

    private val messenger = Messenger(
        Handler(Looper.getMainLooper()) { message ->
            val replyTo = message.replyTo ?: return@Handler true
            val what = message.what
            val correlationId = message.arg1
            val data = Bundle(message.data ?: Bundle())
            scope.launch {
                handleMessage(what = what, correlationId = correlationId, data = data, replyTo = replyTo)
            }
            true
        },
    )

    override fun onBind(intent: android.content.Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        primaryBridge.closeRuntime(timeoutMs = SERVICE_CLOSE_TIMEOUT_MS)
        scope.cancel()
        super.onDestroy()
    }

    private fun handleMessage(what: Int, correlationId: Int, data: Bundle, replyTo: Messenger) {
        when (what) {
            in LlamaRuntimeIpc.MSG_PING..LlamaRuntimeIpc.MSG_GET_CONFIG -> {
                handleConfigMessage(what, correlationId, data, replyTo)
            }

            in LlamaRuntimeIpc.MSG_LIST_MODELS..LlamaRuntimeIpc.MSG_UNLOAD_MODEL -> {
                handleModelMessage(what, correlationId, data, replyTo)
            }

            in LlamaRuntimeIpc.MSG_GENERATE_START..LlamaRuntimeIpc.MSG_GENERATE_CANCEL -> {
                handleGenerationMessage(what, correlationId, data, replyTo)
            }

            in LlamaRuntimeIpc.MSG_SUPPORTS_GPU..LlamaRuntimeIpc.MSG_LAST_ERROR -> {
                handleDiagnosticsMessage(what, correlationId, data, replyTo)
            }
        }
    }

    private fun handleConfigMessage(what: Int, correlationId: Int, data: Bundle, replyTo: Messenger) {
        when (what) {
            LlamaRuntimeIpc.MSG_PING -> sendReply(replyTo, correlationId, okReply())
            LlamaRuntimeIpc.MSG_SET_CONFIG -> handleSetConfig(
                correlationId = correlationId,
                data = data,
                replyTo = replyTo,
            )
            LlamaRuntimeIpc.MSG_GET_CONFIG -> sendReply(replyTo, correlationId, currentConfig.toBundle().withOk())
        }
    }

    private fun handleModelMessage(what: Int, correlationId: Int, data: Bundle, replyTo: Messenger) {
        when (what) {
            LlamaRuntimeIpc.MSG_LIST_MODELS -> handleNonStreaming(correlationId, replyTo) {
                okReply().apply {
                    putStringArrayList(
                        LlamaRuntimeIpc.EXTRA_AVAILABLE_MODELS,
                        ArrayList(primaryBridge.listAvailableModels()),
                    )
                }
            }

            LlamaRuntimeIpc.MSG_LOAD_MODEL -> handleNonStreaming(correlationId, replyTo) {
                val modelId = data.getString(LlamaRuntimeIpc.EXTRA_MODEL_ID).orEmpty()
                val modelPath = data.getString(LlamaRuntimeIpc.EXTRA_MODEL_PATH)
                val loaded = primaryBridge.loadModel(modelId, modelPath)
                if (loaded) okReply() else errorReply(primaryBridge.lastError())
            }

            LlamaRuntimeIpc.MSG_UNLOAD_MODEL -> handleNonStreaming(correlationId, replyTo) {
                primaryBridge.unloadModel()
                okReply()
            }
        }
    }

    private fun handleGenerationMessage(what: Int, correlationId: Int, data: Bundle, replyTo: Messenger) {
        when (what) {
            LlamaRuntimeIpc.MSG_GENERATE_START -> handleGenerate(
                correlationId = correlationId,
                data = data,
                replyTo = replyTo,
            )
            LlamaRuntimeIpc.MSG_GENERATE_CANCEL -> handleGenerateCancel(
                correlationId = correlationId,
                requestId = data.getString(LlamaRuntimeIpc.EXTRA_REQUEST_ID),
                replyTo = replyTo,
            )
        }
    }

    private fun handleDiagnosticsMessage(what: Int, correlationId: Int, data: Bundle, replyTo: Messenger) {
        when (what) {
            LlamaRuntimeIpc.MSG_SUPPORTS_GPU -> handleNonStreaming(correlationId, replyTo) {
                okReply().apply {
                    putBoolean(LlamaRuntimeIpc.EXTRA_RUNTIME_SUPPORTED, primaryBridge.supportsGpuOffload())
                }
            }

            LlamaRuntimeIpc.MSG_GET_BACKEND_DIAGNOSTICS -> handleNonStreaming(correlationId, replyTo) {
                okReply().apply {
                    putString(LlamaRuntimeIpc.EXTRA_BACKEND_DIAGNOSTICS_JSON, primaryBridge.backendDiagnosticsJson())
                }
            }

            LlamaRuntimeIpc.MSG_RUN_GPU_PROBE -> handleProbe(
                correlationId = correlationId,
                data = data,
                replyTo = replyTo,
            )
            LlamaRuntimeIpc.MSG_LAST_ERROR -> {
                val error = primaryBridge.lastError()
                sendReply(
                    replyTo,
                    correlationId,
                    okReply().apply {
                        putString(LlamaRuntimeIpc.EXTRA_LAST_ERROR_CODE, error?.code)
                        putString(LlamaRuntimeIpc.EXTRA_LAST_ERROR_DETAIL, error?.detail)
                    },
                )
            }
        }
    }

    private fun handleSetConfig(correlationId: Int, data: Bundle, replyTo: Messenger) {
        val config = data.toRuntimeGenerationConfig()
        if (executionGate.isBusyForConfig()) {
            sendReply(replyTo, correlationId, busyReply())
            return
        }
        synchronized(bridgeLock) {
            currentConfig = config
            primaryBridge.setRuntimeGenerationConfig(config)
        }
        sendReply(replyTo, correlationId, okReply())
    }

    private fun handleNonStreaming(
        correlationId: Int,
        replyTo: Messenger,
        block: () -> Bundle,
    ) {
        if (!executionGate.tryBeginNonStreaming()) {
            sendReply(replyTo, correlationId, busyReply())
            return
        }
        val bundle = try {
            synchronized(bridgeLock) {
                block()
            }
        } finally {
            executionGate.endNonStreaming()
        }
        sendReply(replyTo, correlationId, bundle)
    }

    private fun handleGenerate(correlationId: Int, data: Bundle, replyTo: Messenger) {
        val requestId = data.getString(LlamaRuntimeIpc.EXTRA_REQUEST_ID).orEmpty()
        val prompt = data.getString(LlamaRuntimeIpc.EXTRA_PROMPT).orEmpty()
        val maxTokens = data.getInt(LlamaRuntimeIpc.EXTRA_MAX_TOKENS, 0)
        val cacheKey = data.getString(LlamaRuntimeIpc.EXTRA_CACHE_KEY)
        val cachePolicyCode = data.getInt(LlamaRuntimeIpc.EXTRA_CACHE_POLICY, 0)
        val cachePolicy = CachePolicy.entries.firstOrNull { it.code == cachePolicyCode }
            ?: CachePolicy.OFF
        if (requestId.isBlank()) {
            sendStreamResult(replyTo, correlationId, invalidRequestIdGenerationResult())
            return
        }
        val generationLease = executionGate.tryBeginGeneration(requestId)
        if (generationLease == null) {
            sendStreamResult(replyTo, correlationId, busyGenerationResult())
            return
        }
        val result = try {
            primaryBridge.generate(
                requestId = requestId,
                prompt = prompt,
                maxTokens = maxTokens,
                cacheKey = cacheKey,
                cachePolicy = cachePolicy,
            ) { token ->
                sendStreamToken(replyTo, correlationId, token)
            }
        } finally {
            executionGate.endGeneration(generationLease)
        }
        sendStreamResult(replyTo, correlationId, result)
    }

    private fun handleGenerateCancel(correlationId: Int, requestId: String?, replyTo: Messenger) {
        val decision = executionGate.generationCancelDecision(requestId)
        if (decision != LlamaRuntimeExecutionGate.GenerationCancelDecision.OWNER) {
            sendReply(
                replyTo,
                correlationId,
                errorReply(
                    code = requireNotNull(decision.errorCode),
                    detail = decision.detail,
                ),
            )
            return
        }
        val exactRequestId = requireNotNull(requestId)
        val cancelled = primaryBridge.cancelGeneration(exactRequestId)
        sendReply(
            replyTo,
            correlationId,
            if (cancelled) {
                okReply()
            } else {
                errorReply(
                    code = REMOTE_ERROR_CANCEL_REJECTED,
                    detail = "cancel_request_no_longer_active",
                )
            },
        )
    }

    private fun handleProbe(correlationId: Int, data: Bundle, replyTo: Messenger) {
        val request = data.toProbeRequestOrNull()
        if (request == null) {
            sendReply(
                replyTo,
                correlationId,
                errorReply(code = REMOTE_ERROR_RUNTIME, detail = "probe_request_invalid"),
            )
            return
        }
        if (!executionGate.tryBeginProbe()) {
            sendReply(replyTo, correlationId, busyProbeReply())
            return
        }
        val result = try {
            probeRunnerFactory().runProbeLadder(request)
        } finally {
            executionGate.endProbe()
        }
        sendReply(
            replyTo,
            correlationId,
            Bundle().apply {
                putBoolean(LlamaRuntimeIpc.EXTRA_OK, result.status == GpuProbeStatus.QUALIFIED)
                putString(LlamaRuntimeIpc.EXTRA_PROBE_STATUS, result.status.name)
                putInt(LlamaRuntimeIpc.EXTRA_PROBE_MAX_LAYERS, result.maxStableGpuLayers)
                putString(LlamaRuntimeIpc.EXTRA_PROBE_REASON, result.failureReason?.name)
                putString(LlamaRuntimeIpc.EXTRA_PROBE_DETAIL, result.detail)
                putLong(LlamaRuntimeIpc.EXTRA_SERVICE_EPOCH, serviceEpoch)
            },
        )
    }

    private fun sendReply(replyTo: Messenger, correlationId: Int, payload: Bundle) {
        val message = Message.obtain(null, LlamaRuntimeIpc.MSG_REPLY).apply {
            arg1 = correlationId
            data = payload.withEpoch(serviceEpoch)
        }
        ipcSendMonitor.send(IpcMessageKind.REPLY) { replyTo.send(message) }
    }

    private fun sendStreamToken(replyTo: Messenger, correlationId: Int, token: String) {
        val message = Message.obtain(null, LlamaRuntimeIpc.MSG_STREAM_TOKEN).apply {
            arg1 = correlationId
            data = Bundle().apply { putString(LlamaRuntimeIpc.EXTRA_TOKEN, token) }
        }
        ipcSendMonitor.send(IpcMessageKind.STREAM_TOKEN) { replyTo.send(message) }
    }

    private fun sendStreamResult(replyTo: Messenger, correlationId: Int, result: GenerationResult) {
        val message = Message.obtain(null, LlamaRuntimeIpc.MSG_STREAM_RESULT).apply {
            arg1 = correlationId
            data = result.toBundle().withEpoch(serviceEpoch)
        }
        ipcSendMonitor.send(IpcMessageKind.STREAM_RESULT) { replyTo.send(message) }
    }

    private fun okReply(): Bundle = Bundle().apply { putBoolean(LlamaRuntimeIpc.EXTRA_OK, true) }

    private fun busyReply(): Bundle = errorReply(code = REMOTE_ERROR_BUSY, detail = REMOTE_ERROR_BUSY.lowercase())

    private fun busyProbeReply(): Bundle {
        return Bundle().apply {
            putString(LlamaRuntimeIpc.EXTRA_PROBE_STATUS, GpuProbeStatus.FAILED.name)
            putInt(LlamaRuntimeIpc.EXTRA_PROBE_MAX_LAYERS, 0)
            putString(LlamaRuntimeIpc.EXTRA_PROBE_REASON, GpuProbeFailureReason.SERVICE_BUSY.name)
            putString(LlamaRuntimeIpc.EXTRA_PROBE_DETAIL, REMOTE_ERROR_BUSY.lowercase())
            putBoolean(LlamaRuntimeIpc.EXTRA_OK, false)
            putLong(LlamaRuntimeIpc.EXTRA_SERVICE_EPOCH, serviceEpoch)
        }
    }

    private fun errorReply(error: BridgeError?): Bundle {
        return errorReply(
            code = error?.code ?: REMOTE_ERROR_RUNTIME,
            detail = error?.detail,
        )
    }

    private fun errorReply(code: String, detail: String?): Bundle {
        return Bundle().apply {
            putBoolean(LlamaRuntimeIpc.EXTRA_OK, false)
            putString(LlamaRuntimeIpc.EXTRA_ERROR_CODE, code)
            putString(LlamaRuntimeIpc.EXTRA_ERROR_DETAIL, detail)
        }
    }

    private fun busyGenerationResult(): GenerationResult {
        return GenerationResult(
            finishReason = GenerationFinishReason.ERROR,
            tokenCount = 0,
            firstTokenMs = -1L,
            totalMs = 0L,
            cancelled = false,
            errorCode = REMOTE_ERROR_BUSY,
        )
    }

    private fun invalidRequestIdGenerationResult(): GenerationResult {
        return GenerationResult(
            finishReason = GenerationFinishReason.ERROR,
            tokenCount = 0,
            firstTokenMs = -1L,
            totalMs = 0L,
            cancelled = false,
            errorCode = REMOTE_ERROR_REQUEST_ID_REQUIRED,
        )
    }

    private companion object {
        const val LOG_TAG = "LlamaRuntimeService"
        const val SERVICE_CLOSE_TIMEOUT_MS = 5_000L
    }
}
