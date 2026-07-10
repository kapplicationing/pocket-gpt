package com.pocketagent.android.runtime

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import com.pocketagent.nativebridge.CachePolicy
import com.pocketagent.nativebridge.FlashAttnMode
import com.pocketagent.nativebridge.GpuExecutionBackend
import com.pocketagent.nativebridge.KvCachePreset
import com.pocketagent.nativebridge.NativeJniLlamaCppBridge
import com.pocketagent.nativebridge.RuntimeGenerationConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

internal interface GpuProbeBridge {
    fun isReady(): Boolean
    fun supportsGpuOffload(): Boolean
    fun setBackendProfile(profile: String)
    fun setRuntimeGenerationConfig(config: RuntimeGenerationConfig)
    fun loadModel(modelId: String, modelPath: String): Boolean
    fun generateSyncProbe(prompt: String, maxTokens: Int, cachePolicy: CachePolicy): Boolean
    fun unloadModel()
    fun lastErrorDetail(): String?
}

internal class NativeGpuProbeBridge : GpuProbeBridge {
    private val delegate = NativeJniLlamaCppBridge(
        fallbackEnabled = false,
    )

    override fun isReady(): Boolean = delegate.isReady()

    override fun supportsGpuOffload(): Boolean = delegate.supportsGpuOffload()

    override fun setBackendProfile(profile: String) {
        delegate.setBackendProfile(profile)
    }

    override fun setRuntimeGenerationConfig(config: RuntimeGenerationConfig) {
        delegate.setRuntimeGenerationConfig(config)
    }

    override fun loadModel(modelId: String, modelPath: String): Boolean {
        return delegate.loadModel(
            modelId = modelId,
            modelPath = modelPath,
        )
    }

    override fun generateSyncProbe(prompt: String, maxTokens: Int, cachePolicy: CachePolicy): Boolean {
        return delegate.generateSyncProbe(
            prompt = prompt,
            maxTokens = maxTokens,
            cachePolicy = cachePolicy,
        )
    }

    override fun unloadModel() {
        delegate.unloadModel()
    }

    override fun lastErrorDetail(): String? {
        return delegate.lastError()?.let { "${it.code}:${it.detail.orEmpty()}" }
    }
}

internal class GpuProbeRunner(
    private val bridgeFactory: () -> GpuProbeBridge = { NativeGpuProbeBridge() },
    private val safeBatch: Int = 256,
    private val maxTokens: Int = 1,
    private val prompts: List<String> = listOf("GPU probe warmup request"),
) {
    fun runProbeLadder(request: GpuProbeRequest): GpuProbeResult {
        val bridge = bridgeFactory()
        if (!bridge.isReady()) {
            return GpuProbeResult(
                status = GpuProbeStatus.FAILED,
                failureReason = GpuProbeFailureReason.NATIVE_RUNTIME_UNAVAILABLE,
                detail = bridge.lastErrorDetail() ?: "probe_runtime_not_ready",
            )
        }
        if (!bridge.supportsGpuOffload()) {
            return GpuProbeResult(
                status = GpuProbeStatus.FAILED,
                failureReason = GpuProbeFailureReason.RUNTIME_UNSUPPORTED,
                detail = "probe_runtime_gpu_support=false",
            )
        }

        bridge.setBackendProfile(request.backendProfile)

        var maxStableLayers = 0
        val ladder = request.layerLadder.filter { it > 0 }.distinct().sorted()
        for (layer in ladder) {
            val config = RuntimeGenerationConfig.default().copy(
                gpuEnabled = true,
                gpuLayers = layer,
                gpuBackend = request.backendProfile.toGpuExecutionBackend(),
                nBatch = safeBatch,
                nUbatch = safeBatch,
                // Force safe defaults for probing so the qualification path matches
                // the release OpenCL policy rather than optimistic AUTO defaults.
                flashAttnMode = FlashAttnMode.OFF,
                kvCachePreset = KvCachePreset.SAFE,
            )
            bridge.setRuntimeGenerationConfig(config)
            val loaded = runCatching {
                bridge.loadModel(
                    modelId = request.modelId,
                    modelPath = request.modelPath,
                )
            }.getOrElse {
                false
            }
            val generationSucceeded = if (loaded) {
                prompts.all { prompt ->
                    runCatching {
                        bridge.generateSyncProbe(
                            prompt = prompt,
                            maxTokens = maxTokens,
                            cachePolicy = CachePolicy.OFF,
                        )
                    }.getOrElse { error ->
                        Log.w("GpuProbeRunner", "GPU_PROBE|layer=$layer|generate_exception=${error.message}", error)
                        false
                    }
                }
            } else {
                false
            }
            runCatching { bridge.unloadModel() }
            if (!loaded) {
                return if (maxStableLayers > 0) {
                    GpuProbeResult(
                        status = GpuProbeStatus.QUALIFIED,
                        maxStableGpuLayers = maxStableLayers,
                        detail = "probe_partial_success:last_failed_layer=$layer",
                    )
                } else {
                    GpuProbeResult(
                        status = GpuProbeStatus.FAILED,
                        failureReason = GpuProbeFailureReason.NATIVE_LOAD_FAILED,
                        detail = bridge.lastErrorDetail() ?: "probe_load_failed:layer=$layer",
                    )
                }
            }
            if (!generationSucceeded) {
                return if (maxStableLayers > 0) {
                    GpuProbeResult(
                        status = GpuProbeStatus.QUALIFIED,
                        maxStableGpuLayers = maxStableLayers,
                        detail = "probe_partial_success:last_generate_failed_layer=$layer",
                    )
                } else {
                    GpuProbeResult(
                        status = GpuProbeStatus.FAILED,
                        failureReason = GpuProbeFailureReason.NATIVE_GENERATE_FAILED,
                        detail = bridge.lastErrorDetail() ?: "probe_generate_failed:layer=$layer",
                    )
                }
            }
            maxStableLayers = max(maxStableLayers, layer)
        }

        return if (maxStableLayers > 0) {
            GpuProbeResult(
                status = GpuProbeStatus.QUALIFIED,
                maxStableGpuLayers = maxStableLayers,
                detail = "probe_success",
            )
        } else {
            GpuProbeResult(
                status = GpuProbeStatus.FAILED,
                failureReason = GpuProbeFailureReason.NATIVE_LOAD_FAILED,
                detail = "probe_no_stable_layers",
            )
        }
    }
}

internal object GpuProbeIpc {
    const val MSG_PROBE_REQUEST = 1
    const val MSG_PROBE_RESULT = 2

    const val EXTRA_MODEL_ID = "model_id"
    const val EXTRA_MODEL_VERSION = "model_version"
    const val EXTRA_MODEL_PATH = "model_path"
    const val EXTRA_LAYER_LADDER = "layer_ladder"

    const val EXTRA_BACKEND_PROFILE = "backend_profile"

    const val EXTRA_RESULT_STATUS = "result_status"
    const val EXTRA_RESULT_MAX_LAYERS = "result_max_layers"
    const val EXTRA_RESULT_REASON = "result_reason"
    const val EXTRA_RESULT_DETAIL = "result_detail"
}

class GpuProbeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal var runner: GpuProbeRunner = GpuProbeRunner()

    private val messenger = Messenger(
        Handler(Looper.getMainLooper()) { message ->
            if (message.what != GpuProbeIpc.MSG_PROBE_REQUEST) {
                return@Handler false
            }
            val replyTo = message.replyTo ?: return@Handler true
            val request = requestFromBundle(message.data)
                ?: return@Handler true
            scope.launch {
                val result = runner.runProbeLadder(request)
                sendResult(replyTo, result)
                stopSelf()
            }
            true
        },
    )

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun sendResult(replyTo: Messenger, result: GpuProbeResult) {
        val payload = Bundle().apply {
            putString(GpuProbeIpc.EXTRA_RESULT_STATUS, result.status.name)
            putInt(GpuProbeIpc.EXTRA_RESULT_MAX_LAYERS, result.maxStableGpuLayers)
            putString(GpuProbeIpc.EXTRA_RESULT_REASON, result.failureReason?.name)
            putString(GpuProbeIpc.EXTRA_RESULT_DETAIL, result.detail)
        }
        val message = Message.obtain(null, GpuProbeIpc.MSG_PROBE_RESULT).apply { data = payload }
        runCatching { replyTo.send(message) }
    }

    private fun requestFromBundle(bundle: Bundle?): GpuProbeRequest? {
        val source = bundle ?: return null
        val modelId = source.getString(GpuProbeIpc.EXTRA_MODEL_ID).orEmpty().trim()
        val modelVersion = source.getString(GpuProbeIpc.EXTRA_MODEL_VERSION).orEmpty().trim()
        val modelPath = source.getString(GpuProbeIpc.EXTRA_MODEL_PATH).orEmpty().trim()
        val ladder = source.getIntegerArrayList(GpuProbeIpc.EXTRA_LAYER_LADDER)
            ?.map { it.toInt() }
            ?.filter { it > 0 }
            ?.distinct()
            .orEmpty()
        val backendProfile = source.getString(GpuProbeIpc.EXTRA_BACKEND_PROFILE)?.trim()?.ifEmpty { null } ?: "auto"
        if (modelId.isEmpty() || modelPath.isEmpty() || ladder.isEmpty()) {
            return null
        }
        return GpuProbeRequest(
            modelId = modelId,
            modelVersion = modelVersion,
            modelPath = modelPath,
            layerLadder = ladder,
            backendProfile = backendProfile,
        )
    }
}

internal class AndroidGpuProbeClient internal constructor(
    context: Context,
    private val transport: RemoteRuntimeTransport = MessengerRemoteRuntimeTransport(context.applicationContext),
) : GpuProbeClient {
    override suspend fun runProbe(request: GpuProbeRequest, timeoutMs: Long): GpuProbeResult {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            transport.runGpuProbe(request = request, timeoutMs = timeoutMs)
        }
    }
}

private fun String.toGpuExecutionBackend(): GpuExecutionBackend {
    return when (trim().lowercase()) {
        "hexagon" -> GpuExecutionBackend.HEXAGON
        "opencl" -> GpuExecutionBackend.OPENCL
        "cpu" -> GpuExecutionBackend.CPU
        else -> GpuExecutionBackend.AUTO
    }
}
