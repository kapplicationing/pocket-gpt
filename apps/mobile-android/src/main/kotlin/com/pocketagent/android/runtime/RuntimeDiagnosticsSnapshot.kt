package com.pocketagent.android.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class BackendQualificationState {
    UNKNOWN,
    RUNTIME_UNSUPPORTED,
    MODEL_UNAVAILABLE,
    PROBE_PENDING,
    PROBE_FAILED,
    PROBE_QUALIFIED,
}

enum class BackendFeatureQualificationState {
    UNKNOWN,
    QUALIFIED,
    GUARDED,
    UNAVAILABLE,
}

enum class RuntimeBackendFamily {
    CPU,
    OPENCL,
    HEXAGON,
    VULKAN,
}

data class RuntimeBackendCapability(
    val family: RuntimeBackendFamily,
    val backend: String,
    val compiled: Boolean,
    val registered: Boolean = false,
    val discovered: Boolean? = null,
    val active: Boolean,
    val deviceCount: Int? = null,
    val runtimeAvailable: Boolean? = null,
    val qualified: Boolean? = null,
)

data class RuntimeDiagnosticsSnapshot(
    val backendProfile: String? = null,
    val compiledBackend: String? = null,
    val compiledBackends: List<String> = emptyList(),
    val registeredBackendCount: Int? = null,
    val registeredBackends: List<String>? = null,
    val openclIcdFilenames: String? = null,
    val openclIcdSource: String? = null,
    val discoveredBackends: List<String>? = null,
    val activeBackend: String? = null,
    val backendCapabilities: List<RuntimeBackendCapability> = emptyList(),
    val backendCapabilityByFamily: Map<RuntimeBackendFamily, RuntimeBackendCapability> = emptyMap(),
    val backendQualificationState: BackendQualificationState = BackendQualificationState.UNKNOWN,
    val nativeRuntimeSupported: Boolean? = null,
    val strictAcceleratorFailFast: Boolean? = null,
    val autoBackendCpuFallback: Boolean? = null,
    val openclDeviceVersion: String? = null,
    val openclAdrenoGeneration: Int? = null,
    val requestedModelQuantization: String? = null,
    val activeModelQuantization: String? = null,
    val modelMemoryMode: String? = null,
    val prefixCacheMode: String? = null,
    val lastMmapReadaheadLabel: String? = null,
    val lastMmapReadaheadBytes: Long? = null,
    val lastMmapReadaheadResult: Int? = null,
    val lastMmapReadaheadMs: Long? = null,
    val mmapReadaheadCount: Long? = null,
    val flashAttnQualificationState: BackendFeatureQualificationState = BackendFeatureQualificationState.UNKNOWN,
    val quantizedKvQualificationState: BackendFeatureQualificationState = BackendFeatureQualificationState.UNKNOWN,
    val flashAttnGuardReason: String? = null,
    val quantizedKvGuardReason: String? = null,
) {
    fun backendCapability(family: RuntimeBackendFamily): RuntimeBackendCapability? {
        return backendCapabilityByFamily[family]
    }
}

internal object RuntimeDiagnosticsSnapshotParser {
    fun parse(exportedDiagnostics: String?): RuntimeDiagnosticsSnapshot {
        if (exportedDiagnostics.isNullOrBlank()) {
            return RuntimeDiagnosticsSnapshot()
        }
        val lines = exportedDiagnostics.lineSequence()
            .map { line -> line.trim() }
            .filter { line -> line.isNotEmpty() }
            .toList()
        val gpuOffloadLine = lines.lastOrNull { line -> line.startsWith("GPU_OFFLOAD|") }
        val gpuProbeLine = lines.lastOrNull { line -> line.startsWith("GPU_PROBE|") }
        val offloadFields = parsePipeFields(gpuOffloadLine)
        val gpuProbeFields = parsePipeFields(gpuProbeLine)
        val backendPayload = extractNativeBackendPayload(gpuProbeLine)
        val nativePayloadRoot = parseJsonObject(backendPayload)

        val backendProfile = nativePayloadRoot.stringOrNull("backend_profile")
            ?: offloadFields["backend_profile"]?.trim()?.takeIf { value -> value.isNotEmpty() }
        val compiledBackend = nativePayloadRoot.stringOrNull("compiled_backend")
        val activeBackend = nativePayloadRoot.stringOrNull("active_backend")
            ?: gpuProbeFields["active_backend"]?.trim()?.takeIf { value -> value.isNotEmpty() }
        val runtimeSupported = nativePayloadRoot.booleanOrNull("runtime_supported")
            ?: offloadFields["runtime_supported"].toBooleanOrNullCompat()
        val strictAcceleratorFailFast = nativePayloadRoot.booleanOrNull("strict_accelerator_fail_fast")
            ?: offloadFields["strict_accelerator_fail_fast"].toBooleanOrNullCompat()
        val autoBackendCpuFallback = nativePayloadRoot.booleanOrNull("auto_backend_cpu_fallback")
            ?: offloadFields["auto_backend_cpu_fallback"].toBooleanOrNullCompat()
        val openclDeviceCount = nativePayloadRoot.intOrNull("opencl_device_count")
        val hexagonDeviceCount = nativePayloadRoot.intOrNull("hexagon_device_count")
        val compiledBackends = parseBackendList(
            compiledBackend ?: gpuProbeFields["compiled_backends"],
        )
        val registeredBackendCount = nativePayloadRoot.intOrNull("registered_backend_count")
        val registeredBackends = parseBackendList(nativePayloadRoot.stringOrNull("registered_backends"))
            .ifEmpty { null }
        val openclIcdFilenames = nativePayloadRoot.stringOrNull("opencl_icd_filenames")
        val openclIcdSource = nativePayloadRoot.stringOrNull("opencl_icd_source")
        val discoveredBackends = resolveDiscoveredBackends(
            openclDeviceCount = openclDeviceCount,
            hexagonDeviceCount = hexagonDeviceCount,
            discoveredBackendsOverride = gpuProbeFields["discovered_backends"],
            activeBackend = activeBackend,
        )
        val probeStatus = gpuProbeFields["status"]?.trim()?.ifEmpty { null }
            ?: offloadFields["probe_status"]?.trim()?.ifEmpty { null }
        val probeReason = gpuProbeFields["reason"]?.trim()?.ifEmpty { null }
            ?: offloadFields["probe_reason"]?.trim()?.ifEmpty { null }
        val backendQualificationState = parseBackendQualificationState(gpuProbeFields["qualification_state"])
            ?: resolveBackendQualificationState(
                runtimeSupported = runtimeSupported,
                probeStatus = probeStatus,
                probeReason = probeReason,
            )
        val backendCapabilities = buildBackendCapabilities(
            compiledBackends = compiledBackends,
            registeredBackends = registeredBackends,
            discoveredBackends = discoveredBackends,
            activeBackend = activeBackend,
            openclDeviceCount = openclDeviceCount,
            hexagonDeviceCount = hexagonDeviceCount,
            runtimeSupported = runtimeSupported,
            backendQualificationState = backendQualificationState,
        )
        val openclDeviceVersion = nativePayloadRoot.stringOrNull("opencl_device_version")
        val openclAdrenoGeneration = nativePayloadRoot.intOrNull("opencl_adreno_generation")
        val requestedModelQuantization = nativePayloadRoot.stringOrNull("requested_model_quantization")
        val activeModelQuantization = nativePayloadRoot.stringOrNull("active_model_quantization")
        val modelMemoryMode = nativePayloadRoot.stringOrNull("model_memory_mode")
        val prefixCacheMode = nativePayloadRoot.stringOrNull("prefix_cache_mode")
        val lastMmapReadaheadLabel = nativePayloadRoot.stringOrNull("last_mmap_readahead_label")
        val lastMmapReadaheadBytes = nativePayloadRoot.longOrNull("last_mmap_readahead_bytes")
        val lastMmapReadaheadResult = nativePayloadRoot.intOrNull("last_mmap_readahead_result")
        val lastMmapReadaheadMs = nativePayloadRoot.longOrNull("last_mmap_readahead_ms")
        val mmapReadaheadCount = nativePayloadRoot.longOrNull("mmap_readahead_count")
        val flashAttnGuardReason = nativePayloadRoot.stringOrNull("flash_attn_guard_reason")
        val quantizedKvGuardReason = nativePayloadRoot.stringOrNull("quantized_kv_guard_reason")
        val flashAttnQualificationState = parseBackendFeatureQualificationState(gpuProbeFields["flash_attn_feature_state"])
            ?: resolveBackendFeatureQualificationState(
                guardReason = flashAttnGuardReason,
                activeBackend = activeBackend,
                backendQualificationState = backendQualificationState,
                runtimeSupported = runtimeSupported,
            )
        val quantizedKvQualificationState = parseBackendFeatureQualificationState(gpuProbeFields["quantized_kv_feature_state"])
            ?: resolveBackendFeatureQualificationState(
                guardReason = quantizedKvGuardReason,
                activeBackend = activeBackend,
                backendQualificationState = backendQualificationState,
                runtimeSupported = runtimeSupported,
            )

        return RuntimeDiagnosticsSnapshot(
            backendProfile = backendProfile,
            compiledBackend = compiledBackend,
            compiledBackends = compiledBackends,
            registeredBackendCount = registeredBackendCount,
            registeredBackends = registeredBackends,
            openclIcdFilenames = openclIcdFilenames,
            openclIcdSource = openclIcdSource,
            discoveredBackends = discoveredBackends,
            activeBackend = activeBackend,
            backendCapabilities = backendCapabilities,
            backendCapabilityByFamily = backendCapabilities.associateBy { capability -> capability.family },
            backendQualificationState = backendQualificationState,
            nativeRuntimeSupported = runtimeSupported,
            strictAcceleratorFailFast = strictAcceleratorFailFast,
            autoBackendCpuFallback = autoBackendCpuFallback,
            openclDeviceVersion = openclDeviceVersion,
            openclAdrenoGeneration = openclAdrenoGeneration,
            requestedModelQuantization = requestedModelQuantization,
            activeModelQuantization = activeModelQuantization,
            modelMemoryMode = modelMemoryMode,
            prefixCacheMode = prefixCacheMode,
            lastMmapReadaheadLabel = lastMmapReadaheadLabel,
            lastMmapReadaheadBytes = lastMmapReadaheadBytes,
            lastMmapReadaheadResult = lastMmapReadaheadResult,
            lastMmapReadaheadMs = lastMmapReadaheadMs,
            mmapReadaheadCount = mmapReadaheadCount,
            flashAttnQualificationState = flashAttnQualificationState,
            quantizedKvQualificationState = quantizedKvQualificationState,
            flashAttnGuardReason = flashAttnGuardReason,
            quantizedKvGuardReason = quantizedKvGuardReason,
        )
    }

    private fun parsePipeFields(line: String?): Map<String, String> {
        if (line.isNullOrBlank()) {
            return emptyMap()
        }
        return line.split('|')
            .drop(1)
            .mapNotNull { token ->
                val separator = token.indexOf('=')
                if (separator <= 0) {
                    null
                } else {
                    val key = token.substring(0, separator).trim()
                    val value = token.substring(separator + 1).trim()
                    if (key.isEmpty()) {
                        null
                    } else {
                        key to value
                    }
                }
            }
            .toMap()
    }

    private fun extractNativeBackendPayload(line: String?): String? {
        if (line.isNullOrBlank()) {
            return null
        }
        val marker = "native_backend_payload="
        val markerIndex = line.indexOf(marker)
        if (markerIndex < 0) {
            return null
        }
        return line.substring(markerIndex + marker.length)
            .trim()
            .takeIf { payload -> payload.isNotEmpty() }
    }

    private fun parseJsonObject(payload: String?): JsonObject {
        if (payload.isNullOrBlank()) {
            return JsonObject(emptyMap())
        }
        return runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrElse { JsonObject(emptyMap()) }
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        return this[key]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.takeIf { value -> value.isNotEmpty() }
    }

    private fun JsonObject.booleanOrNull(key: String): Boolean? {
        return this[key]
            ?.jsonPrimitive
            ?.contentOrNull
            .toBooleanOrNullCompat()
    }

    private fun JsonObject.intOrNull(key: String): Int? {
        return this[key]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.toIntOrNull()
    }

    private fun JsonObject.longOrNull(key: String): Long? {
        return this[key]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.toLongOrNull()
    }

    private fun parseBackendList(raw: String?): List<String> {
        return raw.orEmpty()
            .split(',')
            .map { token -> token.trim().lowercase() }
            .filter { token -> token.isNotEmpty() }
            .distinct()
            .sorted()
    }

    private fun resolveDiscoveredBackends(
        openclDeviceCount: Int?,
        hexagonDeviceCount: Int?,
        discoveredBackendsOverride: String?,
        activeBackend: String?,
    ): List<String>? {
        val parsedOverride = parseBackendList(discoveredBackendsOverride)
        if (parsedOverride.isNotEmpty()) {
            return parsedOverride
        }
        val hasDeviceCountEvidence = openclDeviceCount != null || hexagonDeviceCount != null
        if (!hasDeviceCountEvidence) {
            return null
        }
        val discovered = mutableSetOf<String>()
        if ((openclDeviceCount ?: 0) > 0) {
            discovered += "opencl"
        }
        if ((hexagonDeviceCount ?: 0) > 0) {
            discovered += "hexagon"
        }
        val normalizedActiveBackend = activeBackend?.trim()?.lowercase()
        if (normalizedActiveBackend != null && normalizedActiveBackend in setOf("opencl", "hexagon")) {
            discovered += normalizedActiveBackend
        }
        return discovered.toList().sorted()
    }

    private fun buildBackendCapabilities(
        compiledBackends: List<String>,
        registeredBackends: List<String>?,
        discoveredBackends: List<String>?,
        activeBackend: String?,
        openclDeviceCount: Int?,
        hexagonDeviceCount: Int?,
        runtimeSupported: Boolean?,
        backendQualificationState: BackendQualificationState,
    ): List<RuntimeBackendCapability> {
        val normalizedActiveBackend = activeBackend?.trim()?.lowercase()?.takeIf { value -> value.isNotEmpty() }
        val backendNames = mutableSetOf<String>()
        backendNames += compiledBackends
        registeredBackends?.let { backendNames += it }
        discoveredBackends?.let { backendNames += it }
        normalizedActiveBackend?.let { backendNames += it }
        return backendNames
            .sorted()
            .mapNotNull { backend ->
                val family = backendFamilyFor(backend) ?: return@mapNotNull null
                val discovered = discoveredBackends?.contains(backend)
                val active = normalizedActiveBackend == backend
                val registered = registeredBackends?.contains(backend) == true
                val deviceCount = when (family) {
                    RuntimeBackendFamily.OPENCL -> openclDeviceCount
                    RuntimeBackendFamily.HEXAGON -> hexagonDeviceCount
                    RuntimeBackendFamily.CPU,
                    RuntimeBackendFamily.VULKAN,
                    -> null
                }
                RuntimeBackendCapability(
                    family = family,
                    backend = backend,
                    compiled = compiledBackends.contains(backend),
                    registered = registered,
                    discovered = discovered,
                    active = active,
                    deviceCount = deviceCount,
                    runtimeAvailable = resolveRuntimeAvailability(
                        family = family,
                        discovered = discovered,
                        active = active,
                        deviceCount = deviceCount,
                        runtimeSupported = runtimeSupported,
                        registered = registered,
                    ),
                    qualified = resolveBackendQualifiedState(
                        family = family,
                        active = active,
                        runtimeSupported = runtimeSupported,
                        backendQualificationState = backendQualificationState,
                    ),
                )
            }
    }

    private fun backendFamilyFor(backend: String): RuntimeBackendFamily? {
        return when (backend.trim().lowercase()) {
            "cpu" -> RuntimeBackendFamily.CPU
            "opencl" -> RuntimeBackendFamily.OPENCL
            "hexagon" -> RuntimeBackendFamily.HEXAGON
            "vulkan" -> RuntimeBackendFamily.VULKAN
            else -> null
        }
    }

    private fun resolveRuntimeAvailability(
        family: RuntimeBackendFamily,
        discovered: Boolean?,
        active: Boolean,
        deviceCount: Int?,
        runtimeSupported: Boolean?,
        registered: Boolean,
    ): Boolean? {
        return when (family) {
            RuntimeBackendFamily.CPU -> if (active || registered) true else null
            RuntimeBackendFamily.OPENCL,
            RuntimeBackendFamily.HEXAGON,
            -> when {
                deviceCount != null -> deviceCount > 0
                discovered != null -> discovered
                active && runtimeSupported != null -> runtimeSupported
                else -> null
            }
            RuntimeBackendFamily.VULKAN -> discovered
        }
    }

    private fun resolveBackendQualifiedState(
        family: RuntimeBackendFamily,
        active: Boolean,
        runtimeSupported: Boolean?,
        backendQualificationState: BackendQualificationState,
    ): Boolean? {
        return when (family) {
            RuntimeBackendFamily.CPU -> if (active) true else null
            RuntimeBackendFamily.OPENCL,
            RuntimeBackendFamily.HEXAGON,
            RuntimeBackendFamily.VULKAN,
            -> when {
                active && backendQualificationState == BackendQualificationState.PROBE_QUALIFIED -> true
                runtimeSupported == false -> false
                active && backendQualificationState in setOf(
                    BackendQualificationState.RUNTIME_UNSUPPORTED,
                    BackendQualificationState.MODEL_UNAVAILABLE,
                    BackendQualificationState.PROBE_FAILED,
                ) -> false
                else -> null
            }
        }
    }

    private fun parseBackendQualificationState(raw: String?): BackendQualificationState? {
        val normalized = raw?.trim()?.uppercase()?.takeIf { value -> value.isNotEmpty() } ?: return null
        return runCatching { BackendQualificationState.valueOf(normalized) }.getOrNull()
    }

    private fun resolveBackendQualificationState(
        runtimeSupported: Boolean?,
        probeStatus: String?,
        probeReason: String?,
    ): BackendQualificationState {
        if (runtimeSupported == false) {
            return BackendQualificationState.RUNTIME_UNSUPPORTED
        }
        val normalizedStatus = probeStatus?.trim()?.uppercase()
        val normalizedReason = probeReason?.trim()?.uppercase()
        return when (normalizedStatus) {
            "QUALIFIED" -> BackendQualificationState.PROBE_QUALIFIED
            "PENDING" -> BackendQualificationState.PROBE_PENDING
            "FAILED" -> {
                if (normalizedReason == "MODEL_UNAVAILABLE") {
                    BackendQualificationState.MODEL_UNAVAILABLE
                } else {
                    BackendQualificationState.PROBE_FAILED
                }
            }

            else -> BackendQualificationState.UNKNOWN
        }
    }

    private fun parseBackendFeatureQualificationState(raw: String?): BackendFeatureQualificationState? {
        val normalized = raw?.trim()?.uppercase()?.takeIf { value -> value.isNotEmpty() } ?: return null
        return runCatching { BackendFeatureQualificationState.valueOf(normalized) }.getOrNull()
    }

    private fun resolveBackendFeatureQualificationState(
        guardReason: String?,
        activeBackend: String?,
        backendQualificationState: BackendQualificationState,
        runtimeSupported: Boolean?,
    ): BackendFeatureQualificationState {
        if (!guardReason.isNullOrBlank()) {
            return BackendFeatureQualificationState.GUARDED
        }
        val normalizedActiveBackend = activeBackend?.trim()?.lowercase()
        if (normalizedActiveBackend == "cpu") {
            return BackendFeatureQualificationState.UNAVAILABLE
        }
        if (runtimeSupported == false) {
            return BackendFeatureQualificationState.UNAVAILABLE
        }
        if (
            backendQualificationState == BackendQualificationState.PROBE_QUALIFIED &&
            normalizedActiveBackend != null &&
            normalizedActiveBackend in setOf("opencl", "hexagon")
        ) {
            return BackendFeatureQualificationState.QUALIFIED
        }
        return BackendFeatureQualificationState.UNKNOWN
    }

    private fun String?.toBooleanOrNullCompat(): Boolean? {
        val normalized = this?.trim()?.lowercase() ?: return null
        return when (normalized) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> null
        }
    }
}
