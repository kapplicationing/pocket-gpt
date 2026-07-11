package com.pocketagent.android.ui.state

import com.pocketagent.nativebridge.ModelLifecycleErrorCode
import com.pocketagent.runtime.ImageAnalysisResult
import com.pocketagent.runtime.ImageFailure
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult
import com.pocketagent.runtime.ToolExecutionResult
import com.pocketagent.runtime.ToolFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiErrorMapperTest {
    @Test
    fun `startup mapper returns null when checks are empty`() {
        assertNull(UiErrorMapper.startupFailure(emptyList()))
    }

    @Test
    fun `startup mapper differentiates missing model guidance`() {
        val error = UiErrorMapper.startupFailure(
            listOf("Missing runtime model(s): qwen_0_8b"),
        )

        assertNotNull(error)
        assertEquals("UI-STARTUP-001", error.code)
        assertTrue(error.userMessage.contains("Download or import required models"))
    }

    @Test
    fun `startup mapper differentiates checksum mismatch guidance`() {
        val error = UiErrorMapper.startupFailure(
            listOf("Artifact verification failed: CHECKSUM_MISMATCH"),
        )

        assertNotNull(error)
        assertTrue(error.userMessage.contains("checksum mismatch"))
    }

    @Test
    fun `startup mapper differentiates provenance mismatch guidance`() {
        val error = UiErrorMapper.startupFailure(
            listOf("Artifact verification failed: PROVENANCE_SIGNATURE_MISMATCH"),
        )

        assertNotNull(error)
        assertTrue(error.userMessage.contains("provenance mismatch"))
    }

    @Test
    fun `startup mapper differentiates runtime compatibility guidance`() {
        val error = UiErrorMapper.startupFailure(
            listOf("Artifact verification failed: RUNTIME_INCOMPATIBLE"),
        )

        assertNotNull(error)
        assertTrue(error.userMessage.contains("compatibility"))
    }

    @Test
    fun `startup mapper differentiates unsupported model format guidance`() {
        val error = UiErrorMapper.startupFailure(
            listOf("RUNTIME_INCOMPATIBLE_MODEL_FORMAT:modelId=unsupported-model|required_format=unsupported_quant"),
        )

        assertNotNull(error)
        assertTrue(error.userMessage.contains("model format", ignoreCase = true))
    }

    @Test
    fun `startup mapper differentiates backend readiness guidance`() {
        val error = UiErrorMapper.startupFailure(
            listOf("Runtime backend is ADB_FALLBACK but NATIVE runtime is required"),
        )

        assertNotNull(error)
        assertTrue(error.userMessage.contains("Native runtime backend is unavailable"))
    }

    @Test
    fun `startup mapper differentiates missing native build packaging guidance`() {
        val error = UiErrorMapper.startupFailure(
            listOf("Build is missing native runtime library (libpocket_llama.so). Install proper build."),
        )

        assertNotNull(error)
        assertTrue(error.userMessage.contains("missing the native runtime", ignoreCase = true))
    }

    @Test
    fun `startup mapper differentiates template unavailable guidance`() {
        val error = UiErrorMapper.startupFailure(
            listOf("TEMPLATE_UNAVAILABLE: model profile missing for qwen3.5-0.8b-q4"),
        )

        assertNotNull(error)
        assertTrue(error.userMessage.contains("template", ignoreCase = true))
    }

    @Test
    fun `startup mapper differentiates timeout guidance`() {
        val error = UiErrorMapper.startupFailure(
            listOf("Startup checks timed out after 30s."),
        )

        assertNotNull(error)
        assertTrue(error.userMessage.contains("timed out"))
    }

    @Test
    fun `runtime cancelled maps to deterministic runtime code and detail`() {
        val error = UiErrorMapper.runtimeCancelled("cancelled")

        assertEquals("UI-RUNTIME-001", error.code)
        assertTrue(error.userMessage.contains("cancelled", ignoreCase = true))
        assertTrue(error.technicalDetail?.contains("cancelled", ignoreCase = true) == true)
    }

    @Test
    fun `runtime failure surfaces image attachment guidance`() {
        val error = UiErrorMapper.runtimeFailure(
            "image_attachments_unsupported:Selected model cannot process image attachments.",
        )

        assertEquals("UI-RUNTIME-001", error.code)
        assertTrue(error.userMessage.contains("image attachments", ignoreCase = true))
        assertEquals(RecoveryAction.CHANGE_MODEL, error.recoveryAction)
    }

    @Test
    fun `typed tool validation failure maps to deterministic tool schema ui code`() {
        val error = UiErrorMapper.fromToolResult(
            ToolExecutionResult.Failure(
                ToolFailure.Validation(
                    code = "invalid_field_value",
                    userMessage = "That tool request was rejected for safety.",
                    technicalDetail = "Field 'expression' has disallowed characters.",
                ),
            ),
        )

        assertNotNull(error)
        assertEquals("UI-TOOL-SCHEMA-001", error.code)
        assertEquals("invalid_field_value", error.sourceCode)
    }

    @Test
    fun `typed image validation failure maps to deterministic image ui code`() {
        val error = UiErrorMapper.fromImageResult(
            ImageAnalysisResult.Failure(
                ImageFailure.Validation(
                    code = "unsupported_extension",
                    userMessage = "That image could not be processed.",
                    technicalDetail = "extension 'tiff' is not supported",
                ),
            ),
        )

        assertNotNull(error)
        assertEquals("UI-IMG-VAL-001", error.code)
        assertEquals("unsupported_extension", error.sourceCode)
    }

    @Test
    fun `typed image runtime failure preserves source code and recovery`() {
        val error = UiErrorMapper.fromImageResult(
            ImageAnalysisResult.Failure(
                ImageFailure.Runtime(
                    code = "out_of_memory",
                    userMessage = "Image analysis failed.",
                    technicalDetail = "allocation failed",
                ),
            ),
        )

        assertNotNull(error)
        assertEquals("out_of_memory", error.sourceCode)
        assertEquals(RecoveryAction.CHANGE_MODEL, error.recoveryAction)
    }

    @Test
    fun `typed tool execution failure preserves source code`() {
        val error = UiErrorMapper.fromToolResult(
            ToolExecutionResult.Failure(
                ToolFailure.Execution(
                    code = "tool_runtime_error",
                    userMessage = "Tool request failed.",
                    technicalDetail = "execution failed",
                ),
            ),
        )

        assertNotNull(error)
        assertEquals("tool_runtime_error", error.sourceCode)
        assertEquals(RecoveryAction.RETRY_LOAD, error.recoveryAction)
    }

    @Test
    fun `model lifecycle oom maps to actionable guidance`() {
        val error = UiErrorMapper.fromModelLifecycleResult(
            RuntimeModelLifecycleCommandResult.rejected(
                code = ModelLifecycleErrorCode.OUT_OF_MEMORY,
                detail = "needs 4096 MB but only 2048 MB available",
            ),
        )

        assertNotNull(error)
        assertTrue(error.userMessage.contains("fit in available memory", ignoreCase = true))
        assertEquals(RecoveryAction.CHANGE_MODEL, error.recoveryAction)
        assertEquals("OUT_OF_MEMORY", error.sourceCode)
    }

    @Test
    fun `model lifecycle runtime incompatible maps unsupported model format guidance`() {
        val error = UiErrorMapper.fromModelLifecycleResult(
            RuntimeModelLifecycleCommandResult.rejected(
                code = ModelLifecycleErrorCode.RUNTIME_INCOMPATIBLE,
                detail = "RUNTIME_INCOMPATIBLE_MODEL_FORMAT:" +
                    "modelId=unsupported-model|required_format=unsupported_quant",
            ),
        )

        assertNotNull(error)
        assertTrue(error.userMessage.contains("model format", ignoreCase = true))
        assertEquals(RecoveryAction.CHANGE_MODEL, error.recoveryAction)
    }

}
