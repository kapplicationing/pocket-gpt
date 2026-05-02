package com.pocketagent.android.runtime

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.pocketagent.android.BuildConfig
import com.pocketagent.inference.ModelRuntimeProfile
import com.pocketagent.nativebridge.OpenClRuntimePolicy
import com.pocketagent.runtime.ModelRegistry
import java.security.MessageDigest
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

enum class GpuProbeStatus {
    PENDING,
    QUALIFIED,
    FAILED,
}

enum class GpuProbeFailureReason {
    RUNTIME_UNSUPPORTED,
    MODEL_UNAVAILABLE,
    PROBE_PROCESS_DIED,
    PROBE_TIMEOUT,
    PROBE_BIND_FAILED,
    NATIVE_LOAD_FAILED,
    NATIVE_GENERATE_FAILED,
    NATIVE_RUNTIME_UNAVAILABLE,
    SERVICE_BUSY,
    UNKNOWN,
}

data class GpuProbeResult(
    val status: GpuProbeStatus,
    val maxStableGpuLayers: Int = 0,
    val failureReason: GpuProbeFailureReason? = null,
    val detail: String? = null,
    val cacheKey: String? = null,
    val checkedAtEpochMs: Long = System.currentTimeMillis(),
)

data class GpuProbeRequest(
    val modelId: String,
    val modelVersion: String,
    val modelPath: String,
    val layerLadder: List<Int>,
    val modelContentFingerprint: String? = null,
    val modelFileSizeBytes: Long = 0L,
    val backendProfile: String = "auto",
)

interface GpuProbeClient {
    suspend fun runProbe(request: GpuProbeRequest, timeoutMs: Long): GpuProbeResult
}

internal interface GpuProbeResultStore {
    fun get(cacheKey: String): String?
    fun put(cacheKey: String, value: String)
}

interface GpuOffloadQualifier {
    fun evaluate(
        runtimeSupported: Boolean,
        deviceAdvisory: DeviceGpuOffloadAdvisory = DeviceGpuOffloadAdvisory(),
    ): GpuProbeResult

    fun diagnosticsLine(): String
    fun reportRuntimeFailure(
        reason: GpuProbeFailureReason,
        detail: String? = null,
    ) = Unit

    companion object {
        val DISABLED: GpuOffloadQualifier = object : GpuOffloadQualifier {
            override fun evaluate(
                runtimeSupported: Boolean,
                deviceAdvisory: DeviceGpuOffloadAdvisory,
            ): GpuProbeResult {
                return if (runtimeSupported) {
                    GpuProbeResult(
                        status = GpuProbeStatus.QUALIFIED,
                        maxStableGpuLayers = 32,
                        detail = "gpu_qualifier_passthrough",
                    )
                } else {
                    GpuProbeResult(
                        status = GpuProbeStatus.FAILED,
                        failureReason = GpuProbeFailureReason.RUNTIME_UNSUPPORTED,
                        detail = "runtime_reports_unsupported",
                    )
                }
            }

            override fun diagnosticsLine(): String = "GPU_PROBE|status=disabled_passthrough"
        }
    }
}

private const val PREFS_NAME = "pocketagent_gpu_probe_cache"
private const val RESULT_PREFIX = "gpu_probe_result_"
private const val KEY_LAST_WRITE_EPOCH_MS = "gpu_probe_last_write_ms"
private const val PROBE_POLICY_VERSION = 3
private const val PROBE_TOTAL_TIMEOUT_MS = 2 * 60_000L
private const val PROBE_MIN_LAYER_TIMEOUT_MS = 12_000L
private const val PROBE_MAX_LAYER_TIMEOUT_MS = 45_000L
private const val PROBE_STALE_PENDING_TIMEOUT_MS = PROBE_TOTAL_TIMEOUT_MS + 30_000L
private const val PROBE_BUSY_RETRY_DELAY_MS = 5_000L
private const val PROBE_BUSY_MAX_RETRIES = 12
private const val MIB = 1024L * 1024L
private const val MIN_GPU_HEADROOM_BYTES = 256L * MIB
private val PROBE_RETRIABLE_REASONS: Set<GpuProbeFailureReason> = setOf(
    GpuProbeFailureReason.SERVICE_BUSY,
    GpuProbeFailureReason.PROBE_TIMEOUT,
    GpuProbeFailureReason.PROBE_BIND_FAILED,
)
private val PROBE_LAYER_LADDER_FULL: List<Int> = listOf(1, 2, 4, 8, 16, 32)

private data class GpuProbePolicy(
    val layerLadder: List<Int>,
    val totalTimeoutMs: Long,
    val backendProfile: String = "auto",
) {
    fun timeoutForLayer(layer: Int, nativeDiagnostics: NativeBackendDiagnostics): Long {
        val layerTimeout = when {
            layer <= 2 -> 20_000L
            layer <= 4 -> 25_000L
            layer <= 8 -> 30_000L
            layer <= 16 -> 38_000L
            else -> 45_000L
        }
        // OpenCL kernel JIT can take several seconds on first probe.
        val openclWarmupBudget = if (nativeDiagnostics.compiledBackend.contains("opencl", ignoreCase = true)) {
            10_000L
        } else {
            0L
        }
        return (layerTimeout + openclWarmupBudget).coerceIn(PROBE_MIN_LAYER_TIMEOUT_MS, PROBE_MAX_LAYER_TIMEOUT_MS)
    }
}

private fun resolveProbePolicy(
    @Suppress("UNUSED_PARAMETER") nativeDiagnostics: NativeBackendDiagnostics,
): GpuProbePolicy {
    return GpuProbePolicy(
        layerLadder = PROBE_LAYER_LADDER_FULL,
        totalTimeoutMs = PROBE_TOTAL_TIMEOUT_MS,
        backendProfile = "opencl",
    )
}

private fun resolveProbeRequestFromStore(store: AndroidRuntimeProvisioningStore): GpuProbeRequest? {
    val snapshot = store.snapshot()
    val defaultModelId = ModelRegistry.default().defaultGetReadyModelId(profile = ModelRuntimeProfile.PROD)
    val orderedModels = buildList {
        val defaultState = snapshot.models.firstOrNull { state -> state.modelId == defaultModelId }
        if (defaultState != null) {
            add(defaultState)
        }
        addAll(snapshot.models.filter { state -> state.modelId != defaultModelId })
    }

    val candidate = orderedModels.firstOrNull { state ->
        !state.absolutePath.isNullOrBlank() && !state.activeVersion.isNullOrBlank() && state.isProvisioned
    } ?: return null
    val activeVersion = candidate.activeVersion.orEmpty()
    val activeDescriptor = candidate.installedVersions.firstOrNull { version ->
        version.version == activeVersion && version.absolutePath.isNotBlank()
    }
    val modelPath = activeDescriptor?.absolutePath ?: candidate.absolutePath.orEmpty()
    val modelFingerprint = activeDescriptor?.sha256 ?: candidate.sha256
    val modelFileSizeBytes = activeDescriptor?.fileSizeBytes?.coerceAtLeast(0L) ?: 0L

    return GpuProbeRequest(
        modelId = candidate.modelId,
        modelVersion = activeVersion,
        modelPath = modelPath,
        layerLadder = PROBE_LAYER_LADDER_FULL,
        modelContentFingerprint = modelFingerprint,
        modelFileSizeBytes = modelFileSizeBytes,
        backendProfile = "opencl",
    )
}

class AndroidGpuOffloadQualifier(
    context: Context,
    probeClient: GpuProbeClient = AndroidGpuProbeClient(context.applicationContext),
    probeRequestResolver: () -> GpuProbeRequest? = AndroidRuntimeProvisioningStore(context.applicationContext).let { store ->
        { resolveProbeRequestFromStore(store) }
    },
    backendDiagnosticsReader: NativeBackendDiagnosticsReader = NativeBackendDiagnosticsReader(
        payloadProvider = {
            createDefaultAndroidRuntimeBridge(context.applicationContext).backendDiagnosticsJson()
        },
    ),
    now: () -> Long = { System.currentTimeMillis() },
) : GpuOffloadQualifier {
    private val appContext = context.applicationContext
    private val appBuildSignature: String by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            val versionCode = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(packageInfo)
            "${versionCode}:${packageInfo.lastUpdateTime}"
        }.getOrDefault("${BuildConfig.VERSION_CODE}:${BuildConfig.VERSION_NAME}")
    }

    private val delegate by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MainThreadGuard.assertNotMainThread("AndroidGpuOffloadQualifier.delegate")
        InternalAndroidGpuOffloadQualifier(
            probeClient = probeClient,
            probeRequestResolver = probeRequestResolver,
            backendDiagnosticsReader = backendDiagnosticsReader,
            now = now,
            appBuildSignature = appBuildSignature,
            deviceFingerprint = Build.FINGERPRINT,
            resultStore = SharedPrefsGpuProbeResultStore(
                prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            ),
        )
    }

    override fun evaluate(
        runtimeSupported: Boolean,
        deviceAdvisory: DeviceGpuOffloadAdvisory,
    ): GpuProbeResult {
        MainThreadGuard.assertNotMainThread("AndroidGpuOffloadQualifier.evaluate")
        return AppOperationTrace.section(
            name = "gpu.eligibility.evaluate",
            detail = { "runtime_supported=$runtimeSupported" },
        ) {
            delegate.evaluate(runtimeSupported, deviceAdvisory)
        }
    }

    override fun diagnosticsLine(): String = delegate.diagnosticsLine()

    override fun reportRuntimeFailure(reason: GpuProbeFailureReason, detail: String?) {
        delegate.reportRuntimeFailure(reason = reason, detail = detail)
    }
}

internal class InternalAndroidGpuOffloadQualifier(
    private val probeClient: GpuProbeClient,
    private val probeRequestResolver: () -> GpuProbeRequest?,
    private val backendDiagnosticsReader: NativeBackendDiagnosticsReader,
    private val now: () -> Long,
    private val appBuildSignature: String,
    private val deviceFingerprint: String,
    private val resultStore: GpuProbeResultStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : GpuOffloadQualifier {
    private val tag = "GpuOffloadQualifier"

    @Volatile
    private var latestResult: GpuProbeResult = GpuProbeResult(
        status = GpuProbeStatus.PENDING,
        detail = "qualification_not_started",
    )

    @Volatile
    private var inFlightCacheKey: String? = null
    @Volatile
    private var latestBackendDiagnostics: NativeBackendDiagnostics = NativeBackendDiagnostics()
    @Volatile
    private var latestBackendDiagnosticsPayload: String = ""
    @Volatile
    private var latestDeviceAdvisory: DeviceGpuOffloadAdvisory = DeviceGpuOffloadAdvisory()

    override fun evaluate(
        runtimeSupported: Boolean,
        deviceAdvisory: DeviceGpuOffloadAdvisory,
    ): GpuProbeResult {
        latestDeviceAdvisory = deviceAdvisory
        val request = probeRequestResolver()
        if (!runtimeSupported) {
            return GpuProbeResult(
                status = GpuProbeStatus.FAILED,
                failureReason = GpuProbeFailureReason.RUNTIME_UNSUPPORTED,
                detail = "native_runtime_support=false",
                checkedAtEpochMs = now(),
            ).also { latestResult = it }
        }
        if (request == null) {
            return GpuProbeResult(
                status = GpuProbeStatus.FAILED,
                failureReason = GpuProbeFailureReason.MODEL_UNAVAILABLE,
                detail = "download_model_to_validate_gpu",
                checkedAtEpochMs = now(),
            ).also { latestResult = it }
        }

        val nativeDiag = backendDiagnosticsReader.read()
        latestBackendDiagnostics = nativeDiag
        latestBackendDiagnosticsPayload = nativeDiag.rawPayload
        qualificationPrecheck(
            request = request,
            nativeDiagnostics = nativeDiag,
            deviceAdvisory = deviceAdvisory,
        )?.let { failure ->
            return failure.also { latestResult = it }
        }
        val cacheKey = computeCacheKey(
            request = request,
            nativeDiagnostics = nativeDiag,
            deviceAdvisory = deviceAdvisory,
        )
        readCachedResult(cacheKey = cacheKey)?.let { cached ->
            latestResult = cached
            return cached
        }
        if (latestResult.cacheKey == cacheKey && latestResult.status != GpuProbeStatus.PENDING) {
            return latestResult
        }

        if (inFlightCacheKey == cacheKey) {
            val pendingAgeMs = (now() - latestResult.checkedAtEpochMs).coerceAtLeast(0L)
            if (pendingAgeMs > PROBE_STALE_PENDING_TIMEOUT_MS) {
                safeLogWarning(
                    "GPU_PROBE|stale_pending_detected|age_ms=$pendingAgeMs|cache_key=$cacheKey",
                )
                inFlightCacheKey = null
            } else {
                return latestResult.copy(status = GpuProbeStatus.PENDING, cacheKey = cacheKey)
            }
        }

        inFlightCacheKey = cacheKey
        val pending = GpuProbeResult(
            status = GpuProbeStatus.PENDING,
            detail = "probe_in_progress",
            cacheKey = cacheKey,
            checkedAtEpochMs = now(),
        )
        latestResult = pending
        scope.launch {
            safeLogInfo("GPU_PROBE|coroutine_started|cache_key=$cacheKey")
            try {
                val result = runCatching {
                    runLayerQualification(
                        request = request,
                        nativeDiagnostics = nativeDiag,
                    )
                }.getOrElse { error ->
                    GpuProbeResult(
                        status = GpuProbeStatus.FAILED,
                        failureReason = GpuProbeFailureReason.UNKNOWN,
                        detail = "probe_exception:${error.message ?: error::class.simpleName}",
                    )
                }.copy(cacheKey = cacheKey, checkedAtEpochMs = now())

                runCatching { writeCachedResult(cacheKey = cacheKey, result = result) }
                    .onFailure { cacheError ->
                        safeLogWarning(
                            "GPU_PROBE|cache_write_failed|cache_key=$cacheKey|detail=${cacheError.message ?: cacheError::class.simpleName}",
                        )
                    }
                latestResult = result
                safeLogInfo(
                    "GPU_PROBE|status=${result.status}|max_layers=${result.maxStableGpuLayers}|reason=${result.failureReason}|" +
                        "detail=${result.detail.orEmpty()}|cache_key=$cacheKey",
                )
            } finally {
                inFlightCacheKey = null
            }
        }
        return pending
    }

    override fun reportRuntimeFailure(
        reason: GpuProbeFailureReason,
        detail: String?,
    ) {
        val request = probeRequestResolver()
        val nativeDiag = backendDiagnosticsReader.read()
        latestBackendDiagnostics = nativeDiag
        latestBackendDiagnosticsPayload = nativeDiag.rawPayload
        val cacheKey = when {
            request != null -> computeCacheKey(
                request = request,
                nativeDiagnostics = nativeDiag,
                deviceAdvisory = latestDeviceAdvisory,
            )
            !latestResult.cacheKey.isNullOrBlank() -> latestResult.cacheKey.orEmpty()
            else -> {
                safeLogWarning("GPU_PROBE|runtime_failure_demote_skipped|reason=$reason|detail=${detail.orEmpty()}")
                return
            }
        }
        val demoted = GpuProbeResult(
            status = GpuProbeStatus.FAILED,
            maxStableGpuLayers = 0,
            failureReason = reason,
            detail = "runtime_failure_demoted:${detail.orEmpty()}",
            cacheKey = cacheKey,
            checkedAtEpochMs = now(),
        )
        latestResult = demoted
        inFlightCacheKey = null
        runCatching { writeCachedResult(cacheKey = cacheKey, result = demoted) }
            .onFailure { cacheError ->
                safeLogWarning(
                    "GPU_PROBE|demote_cache_write_failed|cache_key=$cacheKey|detail=${cacheError.message ?: cacheError::class.simpleName}",
                )
            }
        safeLogInfo(
            "GPU_PROBE|demoted=true|reason=$reason|detail=${detail.orEmpty()}|cache_key=$cacheKey",
        )
    }

    private fun safeLogInfo(message: String) {
        runCatching { Log.i(tag, message) }
    }

    private fun safeLogWarning(message: String) {
        runCatching { Log.w(tag, message) }
    }

    private fun safeLogWarning(message: String, throwable: Throwable) {
        runCatching { Log.w(tag, message, throwable) }
    }

    private suspend fun runLayerQualification(
        request: GpuProbeRequest,
        nativeDiagnostics: NativeBackendDiagnostics,
    ): GpuProbeResult {
        val policy = resolveProbePolicy(nativeDiagnostics)
        val requestLadder = request.layerLadder.filter { it > 0 }.distinct().sorted()
        val orderedLayers = requestLadder.filter { candidate -> policy.layerLadder.contains(candidate) }
            .ifEmpty { policy.layerLadder }
        val estimatedLayerCap = estimateLayerCapForProbe(
            request = request,
            nativeDiagnostics = nativeDiagnostics,
            maxCandidateLayer = orderedLayers.maxOrNull() ?: 0,
        )
        val cappedOrderedLayers = applyProbeLayerCap(
            layers = orderedLayers,
            maxLayers = estimatedLayerCap,
        )
        val layerCapDetail = estimatedLayerCap?.toString() ?: "none"
        if (cappedOrderedLayers.isEmpty()) {
            return GpuProbeResult(
                status = GpuProbeStatus.FAILED,
                failureReason = GpuProbeFailureReason.UNKNOWN,
                detail = "probe_layer_ladder_empty:layer_cap=$layerCapDetail",
            )
        }

        var maxStableLayers = 0
        var failedLayer: Int? = null
        var failure: GpuProbeResult? = null

        val startedAtMs = now()
        for (layer in cappedOrderedLayers) {
            var layerResult: GpuProbeResult? = null
            for (attempt in 0..PROBE_BUSY_MAX_RETRIES) {
                val elapsedMs = (now() - startedAtMs).coerceAtLeast(0L)
                val remainingBudgetMs = (policy.totalTimeoutMs - elapsedMs).coerceAtLeast(0L)
                if (remainingBudgetMs < PROBE_MIN_LAYER_TIMEOUT_MS) {
                    layerResult = GpuProbeResult(
                        status = GpuProbeStatus.FAILED,
                        failureReason = GpuProbeFailureReason.PROBE_TIMEOUT,
                        detail = "probe_total_timeout_exhausted:elapsed_ms=$elapsedMs:layer_cap=$layerCapDetail",
                    )
                    break
                }
                val timeoutMs = minOf(
                    policy.timeoutForLayer(layer = layer, nativeDiagnostics = nativeDiagnostics),
                    remainingBudgetMs,
                )
                safeLogInfo("GPU_PROBE|layer_probe_start|layer=$layer|timeout_ms=$timeoutMs|attempt=$attempt")
                layerResult = probeClient.runProbe(
                    request = request.copy(layerLadder = listOf(layer), backendProfile = policy.backendProfile),
                    timeoutMs = timeoutMs,
                )
                safeLogInfo("GPU_PROBE|layer_probe_done|layer=$layer|status=${layerResult!!.status}|reason=${layerResult.failureReason}")
                val retriable = layerResult.failureReason in PROBE_RETRIABLE_REASONS
                if (retriable) {
                    if (attempt < PROBE_BUSY_MAX_RETRIES) {
                        safeLogInfo("GPU_PROBE|transient_retry|layer=$layer|reason=${layerResult.failureReason}|attempt=$attempt|delay_ms=$PROBE_BUSY_RETRY_DELAY_MS")
                        kotlinx.coroutines.delay(PROBE_BUSY_RETRY_DELAY_MS)
                        continue
                    }
                    safeLogInfo("GPU_PROBE|transient_retry_exhausted|layer=$layer|reason=${layerResult.failureReason}|attempt=$attempt")
                }
                break
            }
            val result = layerResult ?: GpuProbeResult(
                status = GpuProbeStatus.FAILED,
                failureReason = GpuProbeFailureReason.PROBE_TIMEOUT,
                detail = "probe_busy_retries_exhausted:layer_cap=$layerCapDetail",
            )
            if (result.status == GpuProbeStatus.QUALIFIED && result.maxStableGpuLayers > 0) {
                val stableForLayer = result.maxStableGpuLayers.coerceAtMost(layer)
                maxStableLayers = max(maxStableLayers, stableForLayer)
                if (stableForLayer >= layer) {
                    continue
                }
            }
            failedLayer = layer
            failure = result
            break
        }

        if (maxStableLayers > 0) {
            val detail = if (failedLayer == null) {
                "probe_success:layer_cap=$layerCapDetail"
            } else {
                "probe_partial_success:max_stable=$maxStableLayers:last_failed_layer=$failedLayer:" +
                    "reason=${failure?.failureReason ?: "unknown"}:layer_cap=$layerCapDetail"
            }
            return GpuProbeResult(
                status = GpuProbeStatus.QUALIFIED,
                maxStableGpuLayers = maxStableLayers,
                detail = detail,
            )
        }

        return failure?.copy(
            status = GpuProbeStatus.FAILED,
            maxStableGpuLayers = 0,
            detail = failure.detail ?: "probe_failed_at_layer=${failedLayer ?: "unknown"}:layer_cap=$layerCapDetail",
        ) ?: GpuProbeResult(
            status = GpuProbeStatus.FAILED,
            failureReason = GpuProbeFailureReason.UNKNOWN,
            detail = "probe_failed_without_result:layer_cap=$layerCapDetail",
        )
    }

    private fun estimateLayerCapForProbe(
        request: GpuProbeRequest,
        nativeDiagnostics: NativeBackendDiagnostics,
        maxCandidateLayer: Int,
    ): Int? {
        val modelBytes = request.modelFileSizeBytes.takeIf { it > 0L } ?: return null
        val heapBytes = nativeDiagnostics.deviceLocalHeapBytes.takeIf { it > 0L } ?: return null
        val maxLayer = maxCandidateLayer.coerceAtLeast(1)
        val reservedBytes = maxOf(MIN_GPU_HEADROOM_BYTES, heapBytes / 4L)
        val usableBytes = (heapBytes - reservedBytes).coerceAtLeast(0L)
        if (usableBytes <= 0L) {
            return 1
        }
        val scaled = (usableBytes.toDouble() * maxLayer.toDouble()) / modelBytes.toDouble()
        return scaled.toInt().coerceIn(1, maxLayer)
    }

    private fun applyProbeLayerCap(layers: List<Int>, maxLayers: Int?): List<Int> {
        if (layers.isEmpty()) {
            return emptyList()
        }
        val cap = maxLayers ?: return layers
        return layers.filter { layer -> layer <= cap }
            .ifEmpty { listOf(layers.minOrNull() ?: 1) }
            .distinct()
            .sorted()
    }

    override fun diagnosticsLine(): String {
        val result = latestResult
        val nativeDiag = latestBackendDiagnostics
        val deviceAdvisory = latestDeviceAdvisory
        val qualificationState = resolveBackendQualificationState(
            runtimeSupported = nativeDiag.runtimeSupported,
            probeStatus = result.status,
            probeReason = result.failureReason,
        )
        val compiledBackends = nativeDiag.compiledBackends().joinToString(",")
        val registeredBackends = nativeDiag.registeredBackends.joinToString(",")
        val discoveredBackends = nativeDiag.discoveredAcceleratorBackends().joinToString(",")
        val activeBackend = nativeDiag.activeBackend.takeIf { value -> value.isNotBlank() } ?: "unknown"
        val flashAttnFeatureState = resolveBackendFeatureQualificationState(
            guardReason = nativeDiag.flashAttnGuardReason,
            activeBackend = nativeDiag.activeBackend,
            qualificationState = qualificationState,
            runtimeSupported = nativeDiag.runtimeSupported,
        )
        val quantizedKvFeatureState = resolveBackendFeatureQualificationState(
            guardReason = nativeDiag.quantizedKvGuardReason,
            activeBackend = nativeDiag.activeBackend,
            qualificationState = qualificationState,
            runtimeSupported = nativeDiag.runtimeSupported,
        )
        return "GPU_PROBE|status=${result.status}|max_layers=${result.maxStableGpuLayers}|reason=${result.failureReason ?: "none"}|" +
            "detail=${result.detail.orEmpty()}|cache_key=${result.cacheKey.orEmpty()}|" +
            "device_advisory_supported=${deviceAdvisory.supportedForProbe}|" +
            "device_advisory_auto_opencl=${deviceAdvisory.automaticOpenClEligible}|" +
            "device_advisory_reason=${deviceAdvisory.reason}|" +
            "qualification_state=${qualificationState.name.lowercase()}|" +
            "compiled_backends=$compiledBackends|registered_backend_count=${nativeDiag.registeredBackendCount}|" +
            "registered_backends=$registeredBackends|opencl_icd_source=${nativeDiag.openclIcdSource}|" +
            "opencl_icd_filenames=${nativeDiag.openclIcdFilenames}|discovered_backends=$discoveredBackends|active_backend=$activeBackend|" +
            "opencl_device_name=${nativeDiag.openclDeviceName.ifBlank { "unknown" }}|" +
            "opencl_device_version=${nativeDiag.openclDeviceVersion.ifBlank { "unknown" }}|" +
            "flash_attn_feature_state=${flashAttnFeatureState.name.lowercase()}|" +
            "quantized_kv_feature_state=${quantizedKvFeatureState.name.lowercase()}|" +
            "native_backend_payload=${latestBackendDiagnosticsPayload}"
    }

    private fun computeCacheKey(
        request: GpuProbeRequest,
        nativeDiagnostics: NativeBackendDiagnostics,
        deviceAdvisory: DeviceGpuOffloadAdvisory,
    ): String {
        val policy = resolveProbePolicy(nativeDiagnostics)
        val raw = listOf(
            "policy=$PROBE_POLICY_VERSION",
            "fingerprint=$deviceFingerprint",
            "compiledBackend=${nativeDiagnostics.compiledBackend}",
            "activeBackend=${nativeDiagnostics.activeBackend}",
            "openclDevices=${nativeDiagnostics.openclDeviceCount}",
            "hexagonDevices=${nativeDiagnostics.hexagonDeviceCount}",
            "driverName=${nativeDiagnostics.driverName}",
            "driverVersion=${nativeDiagnostics.driverVersion}",
            "apiVersion=${nativeDiagnostics.selectedDeviceApiVersion}",
            "vramBytes=${nativeDiagnostics.deviceLocalHeapBytes}",
            "half16=${nativeDiagnostics.storageBuffer16BitAccess && nativeDiagnostics.shaderFloat16}",
            "build=$appBuildSignature",
            "deviceAdvisory=${deviceAdvisory.cacheIdentity()}",
            "model=${request.cacheIdentity()}",
            "ladder=${policy.layerLadder.joinToString(",")}",
            "backendProfile=${policy.backendProfile}",
        ).joinToString(separator = "|")
        return sha256(raw)
    }

    private fun GpuProbeRequest.cacheIdentity(): String {
        val fingerprint = modelContentFingerprint?.trim().orEmpty()
        val versionOrFingerprint = if (fingerprint.isNotEmpty()) {
            "sha256=$fingerprint"
        } else {
            "version=${modelVersion.ifBlank { "unknown" }}"
        }
        val fileSizePart = modelFileSizeBytes.takeIf { it > 0L }?.let { bytes -> "|bytes=$bytes" }.orEmpty()
        val quantCompatibility = OpenClRuntimePolicy.releaseQuantCompatibility(
            modelPath = modelPath,
            modelId = modelId,
            modelVersion = modelVersion,
        )
        return "${modelId}|$versionOrFingerprint$fileSizePart|openclQuant=${quantCompatibility.name}"
    }

    private fun qualificationPrecheck(
        request: GpuProbeRequest,
        nativeDiagnostics: NativeBackendDiagnostics,
        deviceAdvisory: DeviceGpuOffloadAdvisory,
    ): GpuProbeResult? {
        if (!deviceAdvisory.supportedForProbe) {
            return failPrecheck("device_advisory_rejected:${deviceAdvisory.reason}")
        }
        if (!deviceAdvisory.automaticOpenClEligible) {
            return failPrecheck("device_not_in_release_opencl_allowlist:${deviceAdvisory.reason}")
        }
        if (!nativeDiagnostics.compiledBackends().contains("opencl")) {
            return failPrecheck("opencl_not_compiled")
        }
        if (nativeDiagnostics.openclDeviceCount <= 0) {
            return failPrecheck("opencl_runtime_no_devices")
        }
        if (
            !OpenClRuntimePolicy.isReleaseSafeQuantization(
                modelPath = request.modelPath,
                modelId = request.modelId,
                modelVersion = request.modelVersion,
            )
        ) {
            return failPrecheck(
                "opencl_quant_not_allowlisted:" +
                    OpenClRuntimePolicy.releaseQuantCompatibility(
                        modelPath = request.modelPath,
                        modelId = request.modelId,
                        modelVersion = request.modelVersion,
                    ).name.lowercase(),
            )
        }
        return null
    }

    private fun failPrecheck(detail: String): GpuProbeResult {
        return GpuProbeResult(
            status = GpuProbeStatus.FAILED,
            failureReason = GpuProbeFailureReason.RUNTIME_UNSUPPORTED,
            detail = detail,
            checkedAtEpochMs = now(),
        )
    }

    private fun readCachedResult(cacheKey: String): GpuProbeResult? {
        val encoded = resultStore.get(cacheKey) ?: return null
        val payload = runCatching { Json.parseToJsonElement(encoded).jsonObject }.getOrNull() ?: return null
        val status = payload["status"]?.jsonPrimitive?.content?.let { raw ->
            runCatching { GpuProbeStatus.valueOf(raw) }.getOrNull()
        } ?: return null
        val reason = payload["failureReason"]?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() }
            ?.let { raw -> runCatching { GpuProbeFailureReason.valueOf(raw) }.getOrNull() }
        return GpuProbeResult(
            status = status,
            maxStableGpuLayers = payload["maxStableGpuLayers"]?.jsonPrimitive?.intOrNull ?: 0,
            failureReason = reason,
            detail = payload["detail"]?.jsonPrimitive?.contentOrNull,
            cacheKey = cacheKey,
            checkedAtEpochMs = payload["checkedAtEpochMs"]?.jsonPrimitive?.longOrNull ?: now(),
        )
    }

    private fun writeCachedResult(cacheKey: String, result: GpuProbeResult) {
        val encoded = buildJsonObject {
            put("status", result.status.name)
            put("maxStableGpuLayers", result.maxStableGpuLayers)
            put("failureReason", result.failureReason?.name ?: "")
            put("detail", result.detail.orEmpty())
            put("checkedAtEpochMs", result.checkedAtEpochMs)
        }.toString()
        resultStore.put(cacheKey, encoded)
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun resolveBackendQualificationState(
        runtimeSupported: Boolean,
        probeStatus: GpuProbeStatus,
        probeReason: GpuProbeFailureReason?,
    ): BackendQualificationState {
        if (!runtimeSupported) {
            return BackendQualificationState.RUNTIME_UNSUPPORTED
        }
        return when (probeStatus) {
            GpuProbeStatus.QUALIFIED -> BackendQualificationState.PROBE_QUALIFIED
            GpuProbeStatus.PENDING -> BackendQualificationState.PROBE_PENDING
            GpuProbeStatus.FAILED -> {
                if (probeReason == GpuProbeFailureReason.MODEL_UNAVAILABLE) {
                    BackendQualificationState.MODEL_UNAVAILABLE
                } else {
                    BackendQualificationState.PROBE_FAILED
                }
            }
        }
    }

    private fun resolveBackendFeatureQualificationState(
        guardReason: String?,
        activeBackend: String,
        qualificationState: BackendQualificationState,
        runtimeSupported: Boolean,
    ): BackendFeatureQualificationState {
        if (!guardReason.isNullOrBlank()) {
            return BackendFeatureQualificationState.GUARDED
        }
        val normalizedActiveBackend = activeBackend.trim().lowercase()
        if (normalizedActiveBackend == "cpu" || !runtimeSupported) {
            return BackendFeatureQualificationState.UNAVAILABLE
        }
        if (
            qualificationState == BackendQualificationState.PROBE_QUALIFIED &&
            normalizedActiveBackend in setOf("opencl", "hexagon")
        ) {
            return BackendFeatureQualificationState.QUALIFIED
        }
        return BackendFeatureQualificationState.UNKNOWN
    }

}

private class SharedPrefsGpuProbeResultStore(
    private val prefs: SharedPreferences,
) : GpuProbeResultStore {
    override fun get(cacheKey: String): String? = prefs.getString("$RESULT_PREFIX$cacheKey", null)

    override fun put(cacheKey: String, value: String) {
        prefs.edit()
            .putString("$RESULT_PREFIX$cacheKey", value)
            .putLong(KEY_LAST_WRITE_EPOCH_MS, System.currentTimeMillis())
            .apply()
    }
}

data class NativeBackendDiagnostics(
    val runtimeSupported: Boolean = false,
    val compiledBackend: String = "",
    val activeBackend: String = "",
    val registeredBackendCount: Int = 0,
    val registeredBackends: List<String> = emptyList(),
    val openclIcdFilenames: String = "",
    val openclIcdSource: String = "",
    val openclDeviceName: String = "",
    val openclDeviceVersion: String = "",
    val driverName: String = "",
    val driverVersion: Long = 0L,
    val instanceApiVersion: Long = 0L,
    val selectedDeviceApiVersion: Long = 0L,
    val deviceLocalHeapBytes: Long = 0L,
    val storageBuffer16BitAccess: Boolean = false,
    val shaderFloat16: Boolean = false,
    val flashAttnActive: Boolean = false,
    val flashAttnGuardReason: String = "",
    val quantizedKvGuardReason: String = "",
    val openclDeviceCount: Int = 0,
    val hexagonDeviceCount: Int = 0,
    val rawPayload: String = "",
) {
    fun compiledBackends(): List<String> {
        return compiledBackend
            .split(',')
            .map { token -> token.trim().lowercase() }
            .filter { token -> token.isNotEmpty() }
            .distinct()
            .sorted()
    }

    fun discoveredAcceleratorBackends(): List<String> {
        val discovered = mutableSetOf<String>()
        if (openclDeviceCount > 0) {
            discovered += "opencl"
        }
        if (hexagonDeviceCount > 0) {
            discovered += "hexagon"
        }
        val normalizedActiveBackend = activeBackend.trim().lowercase()
        if (normalizedActiveBackend in setOf("opencl", "hexagon")) {
            discovered += normalizedActiveBackend
        }
        return discovered.toList().sorted()
    }
}

class NativeBackendDiagnosticsReader(
    private val payloadProvider: () -> String? = { null },
) {
    fun read(): NativeBackendDiagnostics {
        val payload = runCatching { payloadProvider() }
            .getOrNull()
            ?.trim()
            .orEmpty()
        val root = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        return NativeBackendDiagnostics(
            runtimeSupported = root.booleanOrDefault("runtime_supported", false),
            compiledBackend = root.stringOrDefault("compiled_backend", ""),
            activeBackend = root.stringOrDefault("active_backend", ""),
            registeredBackendCount = root.intOrDefault("registered_backend_count", 0),
            registeredBackends = root.stringOrDefault("registered_backends", "")
                .split(',')
                .map { token -> token.trim().lowercase() }
                .filter { token -> token.isNotEmpty() }
                .distinct(),
            openclIcdFilenames = root.stringOrDefault("opencl_icd_filenames", ""),
            openclIcdSource = root.stringOrDefault("opencl_icd_source", ""),
            openclDeviceName = root.stringOrDefault("opencl_device_name", ""),
            openclDeviceVersion = root.stringOrDefault("opencl_device_version", ""),
            driverName = root.stringOrDefault("driver_name", ""),
            driverVersion = root.longOrDefault("driver_version", 0L),
            instanceApiVersion = root.longOrDefault("instance_api_version", 0L),
            selectedDeviceApiVersion = root.longOrDefault("selected_device_api_version", 0L),
            deviceLocalHeapBytes = root.longOrDefault("device_local_heap_bytes", 0L),
            storageBuffer16BitAccess = root.booleanOrDefault("storage_buffer_16bit_access", false),
            shaderFloat16 = root.booleanOrDefault("shader_float16", false),
            flashAttnActive = root.booleanOrDefault("flashAttnActive", false),
            flashAttnGuardReason = root.stringOrDefault("flash_attn_guard_reason", ""),
            quantizedKvGuardReason = root.stringOrDefault("quantized_kv_guard_reason", ""),
            openclDeviceCount = root.intOrDefault("opencl_device_count", 0),
            hexagonDeviceCount = root.intOrDefault("hexagon_device_count", 0),
            rawPayload = payload,
        )
    }
}

private fun JsonObject.stringOrDefault(key: String, defaultValue: String): String {
    return this[key]?.jsonPrimitive?.contentOrNull ?: defaultValue
}

private fun JsonObject.longOrDefault(key: String, defaultValue: Long): Long {
    return this[key]?.jsonPrimitive?.longOrNull ?: defaultValue
}

private fun JsonObject.intOrDefault(key: String, defaultValue: Int): Int {
    return this[key]?.jsonPrimitive?.intOrNull ?: defaultValue
}

private fun JsonObject.booleanOrDefault(key: String, defaultValue: Boolean): Boolean {
    return this[key]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()?.let { raw ->
        when (raw) {
            "true", "1" -> true
            "false", "0" -> false
            else -> defaultValue
        }
    } ?: defaultValue
}
