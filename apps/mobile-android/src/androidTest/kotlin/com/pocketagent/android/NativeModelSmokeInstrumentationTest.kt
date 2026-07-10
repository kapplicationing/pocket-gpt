package com.pocketagent.android

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.nativebridge.CachePolicy
import com.pocketagent.nativebridge.GpuExecutionBackend
import com.pocketagent.nativebridge.ModelLoadOptions
import com.pocketagent.nativebridge.NativeJniLlamaCppBridge
import com.pocketagent.nativebridge.RuntimeGenerationConfig
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeModelSmokeInstrumentationTest {
    @Test
    fun standardModelLoadsAndGenerates() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Skipping native model smoke. Set stage2_enable_native_model_smoke_test=true to run.",
            parseBooleanArg(args, ARG_ENABLE_NATIVE_MODEL_SMOKE_TEST, defaultValue = false),
        )

        val modelId = (args.getString(ARG_MODEL_ID) ?: ModelCatalog.QWEN3_0_6B_Q4_K_M).trim()
        val modelVersion = (args.getString(ARG_MODEL_VERSION) ?: "q4_k_m").trim()
        val modelPathRaw =
            args
                .getString(ARG_MODEL_PATH)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        assumeTrue("Skipping native model smoke. Provide stage2_native_model_path.", modelPathRaw != null)
        val modelPath = requireFile(checkNotNull(modelPathRaw))

        val bridge = NativeJniLlamaCppBridge(fallbackEnabled = false)
        bridge.setRuntimeGenerationConfig(
            RuntimeGenerationConfig.default().copy(
                gpuEnabled = parseBooleanArg(args, ARG_GPU_ENABLED, defaultValue = true),
                gpuBackend = parseBackendArg(args.getString(ARG_GPU_BACKEND)),
                strictGpuOffload = parseBooleanArg(args, ARG_STRICT_GPU_OFFLOAD, defaultValue = false),
            ),
        )

        assertTrue("Expected native bridge to initialize.", bridge.isReady())
        val loadOk =
            bridge.loadModel(
                modelId = modelId,
                modelPath = modelPath,
                options =
                    ModelLoadOptions(
                        modelVersion = modelVersion,
                        strictGpuOffload = parseBooleanArg(args, ARG_STRICT_GPU_OFFLOAD, defaultValue = false),
                    ),
            )

        assertTrue(
            "Expected native model load to succeed for $modelId, but got ${bridge.lastError()?.detail}",
            loadOk,
        )
        val tokens = mutableListOf<String>()
        val generation =
            bridge.generate(
                requestId = "qual-$modelId",
                prompt = "Reply with one short sentence proving this model generated text.",
                maxTokens = 4,
                cacheKey = null,
                cachePolicy = CachePolicy.OFF,
                onToken = { token -> tokens += token },
            )
        assertTrue("Expected generation success for $modelId.", generation.success)
        assertTrue("Expected at least one streamed token for $modelId.", tokens.isNotEmpty())
        println(
            buildString {
                append("NATIVE_MODEL_SMOKE")
                append("|model_id=").append(modelId)
                append("|result=success")
                append("|token_count=").append(generation.tokenCount)
                append("|first_token_ms=").append(generation.firstTokenMs)
                append("|total_ms=").append(generation.totalMs)
            },
        )
    }

    @Test
    fun multimodalModelLoadsImageAndGenerates() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Skipping native multimodal smoke. Set stage2_enable_native_multimodal_smoke_test=true to run.",
            parseBooleanArg(args, ARG_ENABLE_NATIVE_MULTIMODAL_SMOKE_TEST, defaultValue = false),
        )

        val modelId = (args.getString(ARG_MODEL_ID) ?: ModelCatalog.QWEN_3_5_0_8B_Q4).trim()
        val modelVersion = (args.getString(ARG_MODEL_VERSION) ?: "q4_0").trim()
        val modelPathRaw = args.getString(ARG_MODEL_PATH)?.trim()?.takeIf(String::isNotEmpty)
        val mmprojPathRaw = args.getString(ARG_MMPROJ_PATH)?.trim()?.takeIf(String::isNotEmpty)
        assumeTrue("Skipping native multimodal smoke. Provide stage2_native_model_path.", modelPathRaw != null)
        assumeTrue("Skipping native multimodal smoke. Provide stage2_native_mmproj_path.", mmprojPathRaw != null)
        val modelPath = requireFile(checkNotNull(modelPathRaw))
        val mmprojPath = requireFile(checkNotNull(mmprojPathRaw))

        val bridge = NativeJniLlamaCppBridge(fallbackEnabled = false)
        bridge.setRuntimeGenerationConfig(
            RuntimeGenerationConfig.default().copy(
                gpuEnabled = false,
                gpuBackend = GpuExecutionBackend.CPU,
                strictGpuOffload = false,
            ),
        )

        assertTrue("Expected native bridge to initialize.", bridge.isReady())
        assertTrue(
            "Expected multimodal base model load to succeed, but got ${bridge.lastError()?.detail}",
            bridge.loadModel(
                modelId = modelId,
                modelPath = modelPath,
                options = ModelLoadOptions(modelVersion = modelVersion, strictGpuOffload = false),
            ),
        )
        assertTrue(
            "Expected mmproj initialization to succeed for $mmprojPath.",
            bridge.initMultimodal(mmProjPath = mmprojPath, useGpu = false, imageMaxTokens = 64),
        )
        assertTrue("Expected multimodal runtime to report enabled.", bridge.isMultimodalEnabled())

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val imageFile =
            File(context.cacheDir, "native-multimodal-smoke.png")
        val bitmap =
            Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.RED)
            }
        imageFile.outputStream().use { output ->
            assertTrue("Expected PNG fixture encoding to succeed.", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        bitmap.recycle()

        try {
            val tokens = mutableListOf<String>()
            val generation =
                bridge.generateWithImages(
                    requestId = "qual-mm-$modelId",
                    prompt = "<__media__>\nDescribe the image color in one short sentence.",
                    imagePaths = listOf(imageFile.absolutePath),
                    maxTokens = 4,
                    onToken = { token -> tokens += token },
                )
            assertTrue(
                "Expected multimodal generation success, got ${generation.errorCode}: ${bridge.lastError()?.detail}",
                generation.success,
            )
            assertTrue("Expected at least one multimodal streamed token.", tokens.isNotEmpty())
            println(
                "NATIVE_MULTIMODAL_SMOKE|model_id=$modelId|result=success|token_count=${generation.tokenCount}|first_token_ms=${generation.firstTokenMs}|total_ms=${generation.totalMs}",
            )
        } finally {
            bridge.freeMultimodal()
            bridge.unloadModel()
            imageFile.delete()
        }
    }

    private fun parseBackendArg(raw: String?): GpuExecutionBackend =
        when (raw?.trim()?.uppercase()) {
            "CPU" -> GpuExecutionBackend.CPU
            "OPENCL" -> GpuExecutionBackend.OPENCL
            "HEXAGON" -> GpuExecutionBackend.HEXAGON
            else -> GpuExecutionBackend.AUTO
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

    companion object {
        private const val ARG_ENABLE_NATIVE_MODEL_SMOKE_TEST = "stage2_enable_native_model_smoke_test"
        private const val ARG_ENABLE_NATIVE_MULTIMODAL_SMOKE_TEST = "stage2_enable_native_multimodal_smoke_test"
        private const val ARG_MODEL_ID = "stage2_native_model_id"
        private const val ARG_MODEL_VERSION = "stage2_native_model_version"
        private const val ARG_MODEL_PATH = "stage2_native_model_path"
        private const val ARG_MMPROJ_PATH = "stage2_native_mmproj_path"
        private const val ARG_GPU_ENABLED = "stage2_gpu_enabled"
        private const val ARG_GPU_BACKEND = "stage2_gpu_backend"
        private const val ARG_STRICT_GPU_OFFLOAD = "stage2_strict_gpu_offload"
    }
}
