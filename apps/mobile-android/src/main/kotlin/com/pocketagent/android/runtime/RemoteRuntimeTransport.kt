package com.pocketagent.android.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.pocketagent.nativebridge.BridgeError
import com.pocketagent.nativebridge.CachePolicy
import com.pocketagent.nativebridge.GenerationFinishReason
import com.pocketagent.nativebridge.GenerationResult
import com.pocketagent.nativebridge.KvCacheMethod
import com.pocketagent.nativebridge.KvCacheMethodPreset
import com.pocketagent.nativebridge.RuntimeGenerationConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal const val DEFAULT_COMMAND_TIMEOUT_MS = 5_000L
internal const val LOAD_MODEL_TIMEOUT_MS = 120_000L
internal const val UNLOAD_MODEL_TIMEOUT_MS = 15_000L
internal const val SET_CONFIG_TIMEOUT_MS = 15_000L
internal const val LIST_MODELS_TIMEOUT_MS = 15_000L
internal const val GENERATE_RESULT_TIMEOUT_MS = 600_000L
internal const val REMOTE_ERROR_PROCESS_DIED = "REMOTE_PROCESS_DIED"
internal const val REMOTE_ERROR_BIND_FAILED = "REMOTE_BIND_FAILED"
internal const val REMOTE_ERROR_TIMEOUT = "REMOTE_TIMEOUT"
internal const val REMOTE_ERROR_BUSY = "REMOTE_BUSY"
internal const val REMOTE_ERROR_RUNTIME = "REMOTE_RUNTIME_ERROR"
internal const val REMOTE_ERROR_MODEL_NOT_LOADED = "REMOTE_MODEL_NOT_LOADED"

internal interface RemoteRuntimeTransport {
    fun epoch(): Long
    fun ping(): Boolean
    fun setRuntimeGenerationConfig(config: RuntimeGenerationConfig): Boolean
    fun getRuntimeGenerationConfig(): RuntimeGenerationConfig
    fun listAvailableModels(): List<String>
    fun loadModel(modelId: String, modelPath: String?): Boolean
    fun unloadModel(): Boolean
    fun generate(
        requestId: String,
        prompt: String,
        maxTokens: Int,
        cacheKey: String?,
        cachePolicy: CachePolicy,
        onToken: (String) -> Unit,
    ): GenerationResult
    fun cancelGeneration(requestId: String?): Boolean
    fun supportsGpuOffload(): Boolean
    fun backendDiagnosticsJson(): String?
    fun lastError(): BridgeError?
    fun runGpuProbe(request: GpuProbeRequest, timeoutMs: Long): GpuProbeResult
}

internal class MessengerRemoteRuntimeTransport(
    context: Context,
) : RemoteRuntimeTransport {
    companion object {
        private val ipcLooper: Looper by lazy {
            HandlerThread("PocketRemoteIpc").apply { start() }.looper
        }
    }

    private val appContext = context.applicationContext
    private val connectionEpoch = AtomicLong(1L)
    private val nextCorrelationId = AtomicInteger(1)
    private val pendingReplies = mutableMapOf<Int, LinkedBlockingQueue<Bundle>>()
    private val pendingStreams = mutableMapOf<Int, StreamWaiter>()
    private val transportLock = Any()

    @Volatile
    private var remoteMessenger: Messenger? = null
    @Volatile
    private var bindConnection: ServiceConnection? = null
    @Volatile
    private var bindLatch: CountDownLatch? = null

    private val incomingHandler = Handler(ipcLooper) { message ->
        when (message.what) {
            LlamaRuntimeIpc.MSG_REPLY -> {
                val reply = synchronized(transportLock) { pendingReplies.remove(message.arg1) }
                reply?.offer(message.data ?: Bundle())
                true
            }

            LlamaRuntimeIpc.MSG_STREAM_TOKEN -> {
                val waiter = synchronized(transportLock) { pendingStreams[message.arg1] }
                waiter?.onToken(message.data?.getString(LlamaRuntimeIpc.EXTRA_TOKEN).orEmpty())
                true
            }

            LlamaRuntimeIpc.MSG_STREAM_RESULT -> {
                val waiter = synchronized(transportLock) { pendingStreams.remove(message.arg1) }
                waiter?.resultQueue?.offer(parseGenerationResult(message.data ?: Bundle()))
                true
            }

            else -> false
        }
    }
    private val replyMessenger = Messenger(incomingHandler)

    override fun epoch(): Long = connectionEpoch.get()

    override fun ping(): Boolean {
        val reply = sendCommand(
            what = LlamaRuntimeIpc.MSG_PING,
            payload = Bundle(),
            timeoutMs = DEFAULT_COMMAND_TIMEOUT_MS,
        ) ?: return false
        return reply.getBoolean(LlamaRuntimeIpc.EXTRA_OK, false)
    }

    override fun setRuntimeGenerationConfig(config: RuntimeGenerationConfig): Boolean {
        val reply = sendCommand(
            what = LlamaRuntimeIpc.MSG_SET_CONFIG,
            payload = config.toBundle(),
            timeoutMs = SET_CONFIG_TIMEOUT_MS,
        ) ?: return false
        return reply.getBoolean(LlamaRuntimeIpc.EXTRA_OK, false)
    }

    override fun getRuntimeGenerationConfig(): RuntimeGenerationConfig {
        val reply = sendCommand(
            what = LlamaRuntimeIpc.MSG_GET_CONFIG,
            payload = Bundle(),
            timeoutMs = DEFAULT_COMMAND_TIMEOUT_MS,
        ) ?: return RuntimeGenerationConfig.default()
        return reply.toRuntimeGenerationConfig()
    }

    override fun listAvailableModels(): List<String> {
        val reply = sendCommand(
            what = LlamaRuntimeIpc.MSG_LIST_MODELS,
            payload = Bundle(),
            timeoutMs = LIST_MODELS_TIMEOUT_MS,
        ) ?: return emptyList()
        return reply.getStringArrayList(LlamaRuntimeIpc.EXTRA_AVAILABLE_MODELS).orEmpty()
    }

    override fun loadModel(modelId: String, modelPath: String?): Boolean {
        val reply = sendCommand(
            what = LlamaRuntimeIpc.MSG_LOAD_MODEL,
            payload = Bundle().apply {
                putString(LlamaRuntimeIpc.EXTRA_MODEL_ID, modelId)
                putString(LlamaRuntimeIpc.EXTRA_MODEL_PATH, modelPath)
            },
            timeoutMs = LOAD_MODEL_TIMEOUT_MS,
        ) ?: return false
        return reply.getBoolean(LlamaRuntimeIpc.EXTRA_OK, false)
    }

    override fun unloadModel(): Boolean {
        val reply = sendCommand(
            what = LlamaRuntimeIpc.MSG_UNLOAD_MODEL,
            payload = Bundle(),
            timeoutMs = UNLOAD_MODEL_TIMEOUT_MS,
        ) ?: return false
        return reply.getBoolean(LlamaRuntimeIpc.EXTRA_OK, false)
    }

    override fun generate(
        requestId: String,
        prompt: String,
        maxTokens: Int,
        cacheKey: String?,
        cachePolicy: CachePolicy,
        onToken: (String) -> Unit,
    ): GenerationResult {
        val correlationId = nextCorrelationId.getAndIncrement()
        val waiter = StreamWaiter(onToken = onToken)
        synchronized(transportLock) {
            pendingStreams[correlationId] = waiter
        }
        val remote = ensureConnected() ?: run {
            synchronized(transportLock) { pendingStreams.remove(correlationId) }
            return remoteDiedGenerationResult()
        }
        val message = Message.obtain(null, LlamaRuntimeIpc.MSG_GENERATE_START).apply {
            arg1 = correlationId
            data = Bundle().apply {
                putString(LlamaRuntimeIpc.EXTRA_REQUEST_ID, requestId)
                putString(LlamaRuntimeIpc.EXTRA_PROMPT, prompt)
                putInt(LlamaRuntimeIpc.EXTRA_MAX_TOKENS, maxTokens)
                putString(LlamaRuntimeIpc.EXTRA_CACHE_KEY, cacheKey)
                putInt(LlamaRuntimeIpc.EXTRA_CACHE_POLICY, cachePolicy.code)
            }
            replyTo = replyMessenger
        }
        try {
            remote.send(message)
        } catch (_: RemoteException) {
            handleDisconnectLocked()
            synchronized(transportLock) { pendingStreams.remove(correlationId) }
            return remoteDiedGenerationResult()
        }
        val result = try {
            waiter.resultQueue.poll(GENERATE_RESULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
        if (result != null) {
            return result
        }
        synchronized(transportLock) { pendingStreams.remove(correlationId) }
        runCatching { cancelGeneration(requestId) }
        return remoteTimeoutGenerationResult()
    }

    override fun cancelGeneration(requestId: String?): Boolean {
        val reply = sendCommand(
            what = LlamaRuntimeIpc.MSG_GENERATE_CANCEL,
            payload = Bundle().apply {
                putString(LlamaRuntimeIpc.EXTRA_REQUEST_ID, requestId)
            },
            timeoutMs = DEFAULT_COMMAND_TIMEOUT_MS,
        ) ?: return false
        return reply.getBoolean(LlamaRuntimeIpc.EXTRA_OK, false)
    }

    override fun supportsGpuOffload(): Boolean {
        val reply = sendCommand(
            what = LlamaRuntimeIpc.MSG_SUPPORTS_GPU,
            payload = Bundle(),
            timeoutMs = DEFAULT_COMMAND_TIMEOUT_MS,
        ) ?: return false
        return reply.getBoolean(LlamaRuntimeIpc.EXTRA_RUNTIME_SUPPORTED, false)
    }

    override fun backendDiagnosticsJson(): String? {
        val reply = sendCommand(
            what = LlamaRuntimeIpc.MSG_GET_BACKEND_DIAGNOSTICS,
            payload = Bundle(),
            timeoutMs = DEFAULT_COMMAND_TIMEOUT_MS,
        ) ?: return null
        return reply.getString(LlamaRuntimeIpc.EXTRA_BACKEND_DIAGNOSTICS_JSON)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    override fun lastError(): BridgeError? {
        val reply = sendCommand(
            what = LlamaRuntimeIpc.MSG_LAST_ERROR,
            payload = Bundle(),
            timeoutMs = DEFAULT_COMMAND_TIMEOUT_MS,
        ) ?: return BridgeError(code = REMOTE_ERROR_PROCESS_DIED)
        val code = reply.getString(LlamaRuntimeIpc.EXTRA_LAST_ERROR_CODE).orEmpty().trim()
        if (code.isEmpty()) {
            return null
        }
        return BridgeError(
            code = code,
            detail = reply.getString(LlamaRuntimeIpc.EXTRA_LAST_ERROR_DETAIL),
        )
    }

    override fun runGpuProbe(request: GpuProbeRequest, timeoutMs: Long): GpuProbeResult {
        val reply = sendCommand(
            what = LlamaRuntimeIpc.MSG_RUN_GPU_PROBE,
            payload = Bundle().apply {
                putString(LlamaRuntimeIpc.EXTRA_MODEL_ID, request.modelId)
                putString(LlamaRuntimeIpc.EXTRA_MODEL_VERSION, request.modelVersion)
                putString(LlamaRuntimeIpc.EXTRA_MODEL_PATH, request.modelPath)
                putIntegerArrayList(LlamaRuntimeIpc.EXTRA_LAYER_LADDER, ArrayList(request.layerLadder))
                putString(LlamaRuntimeIpc.EXTRA_BACKEND_PROFILE, request.backendProfile)
            },
            timeoutMs = timeoutMs,
        ) ?: return GpuProbeResult(
            status = GpuProbeStatus.FAILED,
            failureReason = GpuProbeFailureReason.PROBE_PROCESS_DIED,
            detail = "probe_transport_disconnected",
        )
        val transportErrorCode = reply.getString(LlamaRuntimeIpc.EXTRA_ERROR_CODE).orEmpty()
        if (
            !reply.getBoolean(LlamaRuntimeIpc.EXTRA_OK, false) &&
            !reply.containsKey(LlamaRuntimeIpc.EXTRA_PROBE_STATUS)
        ) {
            val reason = when (transportErrorCode) {
                REMOTE_ERROR_TIMEOUT -> GpuProbeFailureReason.PROBE_TIMEOUT
                REMOTE_ERROR_BIND_FAILED -> GpuProbeFailureReason.PROBE_BIND_FAILED
                REMOTE_ERROR_PROCESS_DIED -> GpuProbeFailureReason.PROBE_PROCESS_DIED
                else -> GpuProbeFailureReason.UNKNOWN
            }
            return GpuProbeResult(
                status = GpuProbeStatus.FAILED,
                failureReason = reason,
                detail = reply.getString(LlamaRuntimeIpc.EXTRA_ERROR_DETAIL),
            )
        }
        return GpuProbeResult(
            status = reply.getString(LlamaRuntimeIpc.EXTRA_PROBE_STATUS)
                ?.let { runCatching { GpuProbeStatus.valueOf(it) }.getOrNull() }
                ?: GpuProbeStatus.FAILED,
            maxStableGpuLayers = reply.getInt(LlamaRuntimeIpc.EXTRA_PROBE_MAX_LAYERS, 0),
            failureReason = reply.getString(LlamaRuntimeIpc.EXTRA_PROBE_REASON)
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { GpuProbeFailureReason.valueOf(it) }.getOrNull() },
            detail = reply.getString(LlamaRuntimeIpc.EXTRA_PROBE_DETAIL),
        )
    }

    private fun sendCommand(
        what: Int,
        payload: Bundle,
        timeoutMs: Long,
    ): Bundle? {
        val remote = ensureConnected()
        if (remote == null) {
            android.util.Log.w("RemoteTransport", "IPC_REMOTE|bind_failed|what=$what")
            return remoteFailureReply(REMOTE_ERROR_BIND_FAILED)
        }
        val correlationId = nextCorrelationId.getAndIncrement()
        val queue = LinkedBlockingQueue<Bundle>(1)
        synchronized(transportLock) {
            pendingReplies[correlationId] = queue
        }
        val message = Message.obtain(null, what).apply {
            arg1 = correlationId
            data = payload
            replyTo = replyMessenger
        }
        try {
            remote.send(message)
        } catch (_: RemoteException) {
            synchronized(transportLock) { pendingReplies.remove(correlationId) }
            handleDisconnectLocked()
            return remoteFailureReply(REMOTE_ERROR_PROCESS_DIED)
        }
        val reply = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
        synchronized(transportLock) { pendingReplies.remove(correlationId) }
        if (reply == null) {
            android.util.Log.w("RemoteTransport", "IPC_REMOTE|poll_timeout|what=$what|timeout_ms=$timeoutMs")
        }
        return reply ?: remoteFailureReply(REMOTE_ERROR_TIMEOUT)
    }

    private fun ensureConnected(): Messenger? {
        remoteMessenger?.let { return it }
        val latch = synchronized(transportLock) {
            remoteMessenger?.let { return it }
            bindLatch?.let { return@synchronized it }
            val connectedLatch = CountDownLatch(1)
            bindLatch = connectedLatch
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    remoteMessenger = Messenger(service)
                    synchronized(transportLock) {
                        bindLatch?.countDown()
                        bindLatch = null
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    handleDisconnectLocked()
                }

                override fun onBindingDied(name: ComponentName?) {
                    handleDisconnectLocked()
                }

                override fun onNullBinding(name: ComponentName?) {
                    handleDisconnectLocked()
                }
            }
            bindConnection = connection
            val bound = appContext.bindService(
                Intent(appContext, LlamaRuntimeService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
            if (!bound) {
                bindConnection = null
                bindLatch = null
                connectedLatch.countDown()
            }
            connectedLatch
        }
        latch.await(DEFAULT_COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return remoteMessenger
    }

    private fun handleDisconnectLocked() {
        remoteMessenger = null
        bindConnection = null
        connectionEpoch.incrementAndGet()
        val replyQueues = synchronized(transportLock) {
            val commandQueues = pendingReplies.values.toList()
            val streamWaiters = pendingStreams.values.toList()
            pendingReplies.clear()
            pendingStreams.clear()
            bindLatch?.countDown()
            bindLatch = null
            Pair(commandQueues, streamWaiters)
        }
        replyQueues.first.forEach { it.offer(remoteFailureReply(REMOTE_ERROR_PROCESS_DIED)) }
        replyQueues.second.forEach { it.resultQueue.offer(remoteDiedGenerationResult()) }
    }

    private fun remoteFailureReply(code: String): Bundle {
        return Bundle().apply {
            putBoolean(LlamaRuntimeIpc.EXTRA_OK, false)
            putString(LlamaRuntimeIpc.EXTRA_ERROR_CODE, code)
            putString(LlamaRuntimeIpc.EXTRA_ERROR_DETAIL, code.lowercase())
        }
    }

    private fun remoteDiedGenerationResult(): GenerationResult {
        return GenerationResult(
            finishReason = GenerationFinishReason.ERROR,
            tokenCount = 0,
            firstTokenMs = -1L,
            totalMs = 0L,
            cancelled = false,
            errorCode = REMOTE_ERROR_PROCESS_DIED,
        )
    }

    private fun remoteTimeoutGenerationResult(): GenerationResult {
        return GenerationResult(
            finishReason = GenerationFinishReason.ERROR,
            tokenCount = 0,
            firstTokenMs = -1L,
            totalMs = 0L,
            cancelled = false,
            errorCode = REMOTE_ERROR_TIMEOUT,
        )
    }

    private fun parseGenerationResult(bundle: Bundle): GenerationResult {
        val finishReason = bundle.getString(LlamaRuntimeIpc.EXTRA_RESULT_FINISH_REASON)
            ?.let { runCatching { GenerationFinishReason.valueOf(it) }.getOrNull() }
            ?: GenerationFinishReason.ERROR
        val prefillMs = bundle.takeIf { it.containsKey(LlamaRuntimeIpc.EXTRA_RESULT_PREFILL_MS) }
            ?.getLong(LlamaRuntimeIpc.EXTRA_RESULT_PREFILL_MS)
        val decodeMs = bundle.takeIf { it.containsKey(LlamaRuntimeIpc.EXTRA_RESULT_DECODE_MS) }
            ?.getLong(LlamaRuntimeIpc.EXTRA_RESULT_DECODE_MS)
        val tokensPerSec = bundle.takeIf { it.containsKey(LlamaRuntimeIpc.EXTRA_RESULT_TOKENS_PER_SEC) }
            ?.getDouble(LlamaRuntimeIpc.EXTRA_RESULT_TOKENS_PER_SEC)
        val peakRssMb = bundle.takeIf { it.containsKey(LlamaRuntimeIpc.EXTRA_RESULT_PEAK_RSS_MB) }
            ?.getDouble(LlamaRuntimeIpc.EXTRA_RESULT_PEAK_RSS_MB)
        val requestedKvMethod = bundle.getString(LlamaRuntimeIpc.EXTRA_RESULT_REQUESTED_KV_METHOD)
            ?.let { raw -> runCatching { KvCacheMethod.valueOf(raw) }.getOrNull() }
        val effectiveKvMethod = bundle.getString(LlamaRuntimeIpc.EXTRA_RESULT_EFFECTIVE_KV_METHOD)
            ?.let { raw -> runCatching { KvCacheMethod.valueOf(raw) }.getOrNull() }
        val kvMethodPreset = bundle.getString(LlamaRuntimeIpc.EXTRA_RESULT_KV_METHOD_PRESET)
            ?.let { raw -> runCatching { KvCacheMethodPreset.valueOf(raw) }.getOrNull() }
        return GenerationResult(
            finishReason = finishReason,
            tokenCount = bundle.getInt(LlamaRuntimeIpc.EXTRA_RESULT_TOKEN_COUNT, 0),
            firstTokenMs = bundle.getLong(LlamaRuntimeIpc.EXTRA_RESULT_FIRST_TOKEN_MS, -1L),
            totalMs = bundle.getLong(LlamaRuntimeIpc.EXTRA_RESULT_TOTAL_MS, 0L),
            cancelled = bundle.getBoolean(LlamaRuntimeIpc.EXTRA_RESULT_CANCELLED, false),
            prefillMs = prefillMs,
            decodeMs = decodeMs,
            tokensPerSec = tokensPerSec,
            peakRssMb = peakRssMb,
            errorCode = bundle.getString(LlamaRuntimeIpc.EXTRA_ERROR_CODE),
            requestedKvCacheMethod = requestedKvMethod,
            effectiveKvCacheMethod = effectiveKvMethod,
            kvCacheMethodPreset = kvMethodPreset,
            kvCacheMethodDemotionReason = bundle.getString(LlamaRuntimeIpc.EXTRA_RESULT_KV_METHOD_DEMOTION_REASON),
        )
    }

    private data class StreamWaiter(
        val resultQueue: LinkedBlockingQueue<GenerationResult> = LinkedBlockingQueue(1),
        val onToken: (String) -> Unit,
    )
}
