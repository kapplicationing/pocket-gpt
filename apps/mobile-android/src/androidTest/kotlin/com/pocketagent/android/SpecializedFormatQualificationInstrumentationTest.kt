package com.pocketagent.android

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketagent.android.runtime.AndroidGpuOffloadQualifier
import com.pocketagent.android.runtime.AndroidGpuOffloadSupport
import com.pocketagent.android.runtime.GpuProbeStatus
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.nativebridge.CachePolicy
import com.pocketagent.nativebridge.GpuExecutionBackend
import com.pocketagent.nativebridge.ModelLoadOptions
import com.pocketagent.nativebridge.NativeJniLlamaCppBridge
import com.pocketagent.nativebridge.OpenClProbeQualificationStatus
import com.pocketagent.nativebridge.OpenClQualificationSnapshot
import com.pocketagent.nativebridge.RuntimeGenerationConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpecializedFormatQualificationInstrumentationTest {
    @Test
    fun q1TierLoadQualificationMatchesExpectation() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Skipping specialized-format qualification test. Set stage2_enable_specialized_format_test=true to run.",
            parseBooleanArg(args, ARG_ENABLE_SPECIALIZED_FORMAT_TEST, defaultValue = false),
        )

        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val modelId = (args.getString(ARG_MODEL_ID) ?: ModelCatalog.LLAMA_3_2_1B_Q4_K_M).trim()
        val modelVersion = (args.getString(ARG_MODEL_VERSION) ?: "q4_k_m").trim()
        val expectIncompatible = parseBooleanArg(args, ARG_EXPECT_INCOMPATIBLE, defaultValue = false)
        val modelPath = args.getString(ARG_MODEL_PATH)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::requireFile)
            ?: createPlaceholderModelFile(appContext, modelId)

        val bridge = NativeJniLlamaCppBridge(
            fallbackEnabled = false,
            openClQualificationProvider = buildOpenClQualificationProvider(appContext),
        )
        bridge.setRuntimeGenerationConfig(
            RuntimeGenerationConfig.default().copy(
                gpuEnabled = parseBooleanArg(args, ARG_GPU_ENABLED, defaultValue = true),
                gpuBackend = parseBackendArg(args.getString(ARG_GPU_BACKEND)),
                strictGpuOffload = parseBooleanArg(args, ARG_STRICT_GPU_OFFLOAD, defaultValue = false),
            ),
        )

        assertTrue("Expected native bridge to initialize.", bridge.isReady())
        val loadOk = bridge.loadModel(
            modelId = modelId,
            modelPath = modelPath,
            options = ModelLoadOptions(
                modelVersion = modelVersion,
                strictGpuOffload = parseBooleanArg(args, ARG_STRICT_GPU_OFFLOAD, defaultValue = false),
            ),
        )

        if (expectIncompatible) {
            assertFalse("Expected incompatible load failure for $modelId.", loadOk)
            assertEquals("RUNTIME_INCOMPATIBLE_MODEL_FORMAT", bridge.lastError()?.code)
            println(
                buildString {
                    append("SPECIALIZED_FORMAT_QUAL")
                    append("|model_id=").append(modelId)
                    append("|result=incompatible")
                    append("|detail=").append(bridge.lastError()?.detail.orEmpty())
                },
            )
            return
        }

        assertTrue(
            "Expected specialized-format load to succeed for $modelId, but got ${bridge.lastError()?.detail}",
            loadOk,
        )
        val tokens = mutableListOf<String>()
        val generation = bridge.generate(
            requestId = "qual-$modelId",
            prompt = "Reply with one short sentence proving this model generated text.",
            maxTokens = 24,
            cacheKey = null,
            cachePolicy = CachePolicy.OFF,
            onToken = { token -> tokens += token },
        )
        assertTrue("Expected generation success for $modelId.", generation.success)
        assertTrue("Expected at least one streamed token for $modelId.", tokens.isNotEmpty())
        println(
            buildString {
                append("SPECIALIZED_FORMAT_QUAL")
                append("|model_id=").append(modelId)
                append("|result=success")
                append("|token_count=").append(generation.tokenCount)
                append("|first_token_ms=").append(generation.firstTokenMs)
                append("|total_ms=").append(generation.totalMs)
            },
        )
    }

    private fun buildOpenClQualificationProvider(context: Context): () -> OpenClQualificationSnapshot {
        val support = AndroidGpuOffloadSupport(context.applicationContext)
        val qualifier = AndroidGpuOffloadQualifier(context.applicationContext)
        return {
            val advisory = support.advisory()
            val probe = qualifier.evaluate(
                runtimeSupported = advisory.supportedForProbe,
                deviceAdvisory = advisory,
            )
            OpenClQualificationSnapshot(
                automaticOpenClEligible = advisory.automaticOpenClEligible,
                probeStatus = when (probe.status) {
                    GpuProbeStatus.QUALIFIED -> OpenClProbeQualificationStatus.QUALIFIED
                    GpuProbeStatus.PENDING -> OpenClProbeQualificationStatus.PENDING
                    GpuProbeStatus.FAILED -> OpenClProbeQualificationStatus.FAILED
                },
            )
        }
    }

    private fun parseBackendArg(raw: String?): GpuExecutionBackend {
        return when (raw?.trim()?.uppercase()) {
            "CPU" -> GpuExecutionBackend.CPU
            "OPENCL" -> GpuExecutionBackend.OPENCL
            "HEXAGON" -> GpuExecutionBackend.HEXAGON
            else -> GpuExecutionBackend.AUTO
        }
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

    private fun requireFile(value: String): String {
        val file = File(value)
        require(file.exists() && file.isFile) { "Model path does not exist: $value" }
        return file.absolutePath
    }

    private fun createPlaceholderModelFile(
        context: Context,
        modelId: String,
    ): String {
        val file = File(context.cacheDir, "$modelId-placeholder.gguf")
        if (!file.exists()) {
            file.writeText("placeholder")
        }
        return file.absolutePath
    }

    companion object {
        private const val ARG_ENABLE_SPECIALIZED_FORMAT_TEST = "stage2_enable_specialized_format_test"
        private const val ARG_MODEL_ID = "stage2_specialized_model_id"
        private const val ARG_MODEL_VERSION = "stage2_specialized_model_version"
        private const val ARG_MODEL_PATH = "stage2_specialized_model_path"
        private const val ARG_EXPECT_INCOMPATIBLE = "stage2_expect_incompatible"
        private const val ARG_GPU_ENABLED = "stage2_gpu_enabled"
        private const val ARG_GPU_BACKEND = "stage2_gpu_backend"
        private const val ARG_STRICT_GPU_OFFLOAD = "stage2_strict_gpu_offload"
    }
}
