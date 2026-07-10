package com.pocketagent.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketagent.android.runtime.RuntimeDiagnosticsSnapshot
import com.pocketagent.android.runtime.RuntimeDiagnosticsSnapshotParser
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.nativebridge.CachePolicy
import com.pocketagent.nativebridge.GpuExecutionBackend
import com.pocketagent.nativebridge.ModelLoadOptions
import com.pocketagent.nativebridge.NativeJniLlamaCppBridge
import com.pocketagent.nativebridge.RuntimeGenerationConfig
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeBackendTruthInstrumentationTest {
    @Test
    fun captureBackendTruthMatrix() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Skipping backend truth matrix. Set stage2_enable_backend_truth_test=true to run.",
            parseBooleanArg(args, ARG_ENABLE_BACKEND_TRUTH_TEST, defaultValue = false),
        )
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val artifactDir = args.getString(ARG_ARTIFACT_DIR)?.trim().orEmpty()
        val standardModelId = args.getString(ARG_STANDARD_MODEL_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: ModelCatalog.QWEN_3_5_0_8B_Q4
        val specializedModelId = args.getString(ARG_SPECIALIZED_MODEL_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: ModelCatalog.LLAMA_3_2_1B_Q4_K_M
        val standardModelPath = args.getString(ARG_STANDARD_MODEL_PATH)
            ?.let(::resolveModelPath)
        val specializedModelPath = args.getString(ARG_SPECIALIZED_MODEL_PATH)
            ?.let(::resolveModelPath)
        val prompt = args.getString(ARG_PROMPT)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "Reply with one short sentence proving the backend truth probe ran."
        val traceLines = mutableListOf<String>()

        fun record(
            scope: String,
            stage: String,
            rawDiagnostics: String?,
            extras: Map<String, String> = emptyMap(),
        ) {
            val snapshot = snapshotFromBackendJson(rawDiagnostics)
            val payload = rawDiagnostics
                ?.takeIf { it.isNotBlank() }
                ?.let(::JSONObject)
            val line = buildTruthLine(
                scope = scope,
                stage = stage,
                snapshot = snapshot,
                payload = payload,
                extras = extras,
            )
            traceLines += line
            println(line)
        }

        fun runDirectProbe(
            label: String,
            modelId: String? = null,
            modelPath: String? = null,
            config: RuntimeGenerationConfig,
            strictGpuOffload: Boolean = false,
            runGeneration: Boolean = false,
        ) {
            val bridge = NativeJniLlamaCppBridge(fallbackEnabled = false)
            bridge.setRuntimeGenerationConfig(config)
            record(
                scope = "direct_bridge",
                stage = "${label}_pre_support",
                rawDiagnostics = bridge.backendDiagnosticsJson(),
                extras = mapOf(
                    "requested_backend" to config.gpuBackend.name.lowercase(),
                    "gpu_enabled" to config.gpuEnabled.toString(),
                ),
            )
            val gpuSupported = bridge.supportsGpuOffload()
            record(
                scope = "direct_bridge",
                stage = "${label}_post_support",
                rawDiagnostics = bridge.backendDiagnosticsJson(),
                extras = mapOf(
                    "requested_backend" to config.gpuBackend.name.lowercase(),
                    "gpu_enabled" to config.gpuEnabled.toString(),
                    "supports_gpu_offload" to gpuSupported.toString(),
                ),
            )
            if (modelId == null || modelPath == null) {
                return
            }
            val loadOk = bridge.loadModel(
                modelId = modelId,
                modelPath = modelPath,
                options = ModelLoadOptions(
                    strictGpuOffload = strictGpuOffload,
                ),
            )
            record(
                scope = "direct_bridge",
                stage = "${label}_after_load",
                rawDiagnostics = bridge.backendDiagnosticsJson(),
                extras = mapOf(
                    "model_id" to modelId,
                    "load_ok" to loadOk.toString(),
                    "strict_gpu_offload" to strictGpuOffload.toString(),
                    "last_error_code" to (bridge.lastError()?.code ?: "none"),
                    "last_error_detail" to sanitizeField(bridge.lastError()?.detail),
                ),
            )
            if (runGeneration && loadOk) {
                val tokens = mutableListOf<String>()
                val generation = bridge.generate(
                    requestId = "backend-truth-$label",
                    prompt = prompt,
                    maxTokens = max(8, prompt.length / 12),
                    cacheKey = null,
                    cachePolicy = CachePolicy.OFF,
                    onToken = { token -> tokens += token },
                )
                record(
                    scope = "direct_bridge",
                    stage = "${label}_after_generate",
                    rawDiagnostics = bridge.backendDiagnosticsJson(),
                    extras = mapOf(
                        "model_id" to modelId,
                        "generation_success" to generation.success.toString(),
                        "token_count" to generation.tokenCount.toString(),
                        "first_token_ms" to generation.firstTokenMs.toString(),
                        "total_ms" to generation.totalMs.toString(),
                        "streamed_tokens" to tokens.size.toString(),
                    ),
                )
            }
        }

        runDirectProbe(
            label = "startup_auto",
            config = RuntimeGenerationConfig.default().copy(
                gpuEnabled = true,
                gpuBackend = GpuExecutionBackend.AUTO,
                strictGpuOffload = false,
            ),
        )

        if (standardModelPath != null) {
            runDirectProbe(
                label = "standard_cpu",
                modelId = standardModelId,
                modelPath = standardModelPath,
                config = RuntimeGenerationConfig.default().copy(
                    gpuEnabled = false,
                    gpuBackend = GpuExecutionBackend.CPU,
                    strictGpuOffload = false,
                ),
                strictGpuOffload = false,
                runGeneration = true,
            )
            runDirectProbe(
                label = "standard_auto",
                modelId = standardModelId,
                modelPath = standardModelPath,
                config = RuntimeGenerationConfig.default().copy(
                    gpuEnabled = true,
                    gpuBackend = GpuExecutionBackend.AUTO,
                    strictGpuOffload = false,
                ),
                strictGpuOffload = false,
            )
            runDirectProbe(
                label = "standard_opencl",
                modelId = standardModelId,
                modelPath = standardModelPath,
                config = RuntimeGenerationConfig.default().copy(
                    gpuEnabled = true,
                    gpuBackend = GpuExecutionBackend.OPENCL,
                    strictGpuOffload = false,
                ),
                strictGpuOffload = false,
            )
        }

        if (specializedModelPath != null) {
            runDirectProbe(
                label = "specialized_opencl",
                modelId = specializedModelId,
                modelPath = specializedModelPath,
                config = RuntimeGenerationConfig.default().copy(
                    gpuEnabled = true,
                    gpuBackend = GpuExecutionBackend.OPENCL,
                    strictGpuOffload = true,
                ),
                strictGpuOffload = true,
            )
        }

        if (standardModelPath != null) {
            AppRuntimeDependencies.resetRuntimeFacadeFactoryForTests()
            val seeded = AppRuntimeDependencies.seedModelFromAbsolutePath(
                context = appContext,
                modelId = standardModelId,
                absolutePath = standardModelPath,
            )
            assertTrue(
                "Failed to activate seeded $standardModelId version ${seeded.version}.",
                AppRuntimeDependencies.setActiveVersion(
                    context = appContext,
                    modelId = standardModelId,
                    version = seeded.version,
                ).changed,
            )
            AppRuntimeDependencies.installProductionRuntime(appContext)
            val facade = AppRuntimeDependencies.runtimeFacadeFactory()
            record(
                scope = "app_runtime",
                stage = "post_install",
                rawDiagnostics = extractBackendPayload(facade.exportDiagnostics()),
                extras = mapOf(
                    "runtime_backend" to facade.runtimeBackend().toString(),
                ),
            )
            val startupChecks = facade.runStartupChecks()
            record(
                scope = "app_runtime",
                stage = "post_startup_checks",
                rawDiagnostics = extractBackendPayload(facade.exportDiagnostics()),
                extras = mapOf(
                    "startup_checks" to sanitizeField(startupChecks.joinToString(separator = ";")),
                ),
            )
            val resourceControl = facade as com.pocketagent.runtime.RuntimeResourceControl
            val loadResult = resourceControl.loadModel(
                modelId = standardModelId,
                modelVersion = seeded.version,
            )
            record(
                scope = "app_runtime",
                stage = "post_explicit_load",
                rawDiagnostics = extractBackendPayload(facade.exportDiagnostics()),
                extras = mapOf(
                    "model_id" to standardModelId,
                    "load_success" to loadResult.success.toString(),
                    "load_detail" to sanitizeField(loadResult.detail),
                    "loaded_model_id" to (resourceControl.loadedModel()?.modelId ?: "none"),
                ),
            )
        }

        if (artifactDir.isNotBlank()) {
            val outDir = File(artifactDir).apply { mkdirs() }
            File(outDir, "backend-truth-matrix.txt").writeText(
                traceLines.joinToString(separator = "\n", postfix = "\n"),
            )
        }
    }

    private fun snapshotFromBackendJson(rawDiagnostics: String?): RuntimeDiagnosticsSnapshot {
        val payload = rawDiagnostics?.trim().orEmpty()
        if (payload.isBlank()) {
            return RuntimeDiagnosticsSnapshot()
        }
        return RuntimeDiagnosticsSnapshotParser.parse("GPU_PROBE|native_backend_payload=$payload")
    }

    private fun buildTruthLine(
        scope: String,
        stage: String,
        snapshot: RuntimeDiagnosticsSnapshot,
        payload: JSONObject?,
        extras: Map<String, String>,
    ): String {
        val fields = linkedMapOf(
            "scope" to scope,
            "stage" to stage,
            "compiled_backends" to snapshot.compiledBackends.joinToString(separator = ",").ifBlank { "none" },
            "discovered_backends" to snapshot.discoveredBackends.orEmpty().joinToString(separator = ",").ifBlank { "none" },
            "registered_backends" to snapshot.registeredBackends.orEmpty().joinToString(separator = ",").ifBlank { "none" },
            "active_backend" to (snapshot.activeBackend ?: "unknown"),
            "backend_profile" to (snapshot.backendProfile ?: "unknown"),
            "qualification_state" to snapshot.backendQualificationState.name.lowercase(),
            "native_runtime_supported" to (snapshot.nativeRuntimeSupported?.toString() ?: "unknown"),
            "opencl_icd_source" to (snapshot.openclIcdSource ?: "unknown"),
            "opencl_icd_filenames" to sanitizeField(snapshot.openclIcdFilenames),
            "opencl_device_count" to payload?.optInt("opencl_device_count", -1)?.takeIf { it >= 0 }?.toString().orEmpty().ifBlank { "unknown" },
            "hexagon_device_count" to payload?.optInt("hexagon_device_count", -1)?.takeIf { it >= 0 }?.toString().orEmpty().ifBlank { "unknown" },
            "active_model_quantization" to sanitizeField(snapshot.activeModelQuantization),
        )
        extras.forEach { (key, value) ->
            fields[key] = sanitizeField(value)
        }
        return buildString {
            append("BACKEND_TRUTH")
            fields.forEach { (key, value) ->
                append('|').append(key).append('=').append(value.ifBlank { "none" })
            }
        }
    }

    private fun extractBackendPayload(exportedDiagnostics: String): String? {
        val marker = "native_backend_payload="
        val line = exportedDiagnostics.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.startsWith("GPU_PROBE|") && it.contains(marker) }
            ?: return null
        return line.substringAfter(marker).trim().ifBlank { null }
    }

    private fun resolveModelPath(value: String?): String? {
        val normalized = value?.trim().orEmpty()
        if (normalized.isEmpty()) {
            return null
        }
        val candidates = listOf(
            normalized,
            normalized.replace("/sdcard/", "/storage/emulated/0/"),
            normalized.replace("/storage/emulated/0/", "/sdcard/"),
        )
        return candidates
            .asSequence()
            .map(::File)
            .firstOrNull { file -> file.exists() && file.isFile }
            ?.absolutePath
    }

    private fun sanitizeField(value: String?): String {
        return value
            ?.replace("|", "/")
            ?.replace("\n", " ")
            ?.trim()
            ?.ifBlank { null }
            ?: "none"
    }

    private fun parseBooleanArg(
        args: android.os.Bundle,
        key: String,
        defaultValue: Boolean,
    ): Boolean {
        val raw = args.getString(key)?.trim()?.lowercase() ?: return defaultValue
        return when (raw) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> defaultValue
        }
    }

    companion object {
        private const val ARG_ENABLE_BACKEND_TRUTH_TEST = "stage2_enable_backend_truth_test"
        private const val ARG_ARTIFACT_DIR = "stage2_backend_truth_artifact_dir"
        private const val ARG_STANDARD_MODEL_ID = "stage2_backend_truth_standard_model_id"
        private const val ARG_STANDARD_MODEL_PATH = "stage2_backend_truth_standard_model_path"
        private const val ARG_SPECIALIZED_MODEL_ID = "stage2_backend_truth_specialized_model_id"
        private const val ARG_SPECIALIZED_MODEL_PATH = "stage2_backend_truth_specialized_model_path"
        private const val ARG_PROMPT = "stage2_backend_truth_prompt"
    }
}
