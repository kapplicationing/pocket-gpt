package com.pocketagent.android.ui.state

import androidx.compose.runtime.Immutable
import com.pocketagent.android.runtime.errorCodeName
import com.pocketagent.runtime.ImageAnalysisResult
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult
import com.pocketagent.runtime.ToolExecutionResult

enum class RecoveryAction {
    RETRY_LOAD,
    REDOWNLOAD_MODEL,
    CHANGE_MODEL,
    RESTART_APP,
    REFRESH_CHECKS,
    NONE,
}

@Immutable
data class UiError(
    val code: String,
    val userMessage: String,
    val technicalDetail: String? = null,
    val recoveryAction: RecoveryAction = RecoveryAction.NONE,
) {
    val recoverable: Boolean get() = recoveryAction != RecoveryAction.NONE
}

object UiErrorMapper {
    private const val STARTUP_CODE = "UI-STARTUP-001"
    private const val IMAGE_VALIDATION_CODE = "UI-IMG-VAL-001"
    private const val TOOL_SCHEMA_CODE = "UI-TOOL-SCHEMA-001"
    private const val RUNTIME_CODE = "UI-RUNTIME-001"

    fun startupFailure(startupChecks: List<String>): UiError? {
        if (startupChecks.isEmpty()) {
            return null
        }
        val detail = startupChecks.joinToString(" | ")
        val normalized = detail.lowercase()
        val (userMessage, recovery) = when {
            normalized.contains("runtime_incompatible_model_format")
            ->
                "This model format is not compatible with the current runtime. Choose a different model." to RecoveryAction.CHANGE_MODEL
            normalized.contains("missing runtime model") ->
                "Runtime setup is incomplete. Download or import required models, then refresh checks." to RecoveryAction.REDOWNLOAD_MODEL
            normalized.contains("model_artifact_config_missing") ->
                "Runtime artifact metadata is missing. Reinstall or re-provision models, then refresh checks." to RecoveryAction.REDOWNLOAD_MODEL
            normalized.contains("checksum_mismatch") || normalized.contains("checksum mismatch") ->
                "Model verification failed (checksum mismatch). Re-download or re-import the model." to RecoveryAction.REDOWNLOAD_MODEL
            normalized.contains("provenance_issuer_mismatch") ||
                normalized.contains("provenance_signature_mismatch") ||
                normalized.contains("provenance") ->
                "Model verification failed (provenance mismatch). Use a trusted model source and retry." to RecoveryAction.REDOWNLOAD_MODEL
            normalized.contains("runtime_incompatible") || normalized.contains("runtime compatibility") ->
                "Model runtime compatibility failed. Import a compatible model build and refresh checks." to RecoveryAction.CHANGE_MODEL
            normalized.contains("build is missing native runtime library") ||
                normalized.contains("libpocket_llama.so") ->
                "This app build is missing the native runtime. Install a full build and retry." to RecoveryAction.RESTART_APP
            normalized.contains("runtime backend is adb_fallback") || normalized.contains("runtime backend is unavailable") ->
                "Native runtime backend is unavailable. Confirm device/runtime setup and retry." to RecoveryAction.RESTART_APP
            normalized.contains("template_unavailable") || normalized.contains("model profile missing") ->
                "Model interaction template is unavailable. Reinstall or update the model library entry, then refresh checks." to RecoveryAction.CHANGE_MODEL
            normalized.contains("startup checks timed out") || normalized.contains("timed out") ->
                "Runtime checks timed out. Refresh runtime checks before sending a message." to RecoveryAction.REFRESH_CHECKS
            else ->
                "Runtime setup is incomplete. Open the model library, refresh checks, and retry." to RecoveryAction.REFRESH_CHECKS
        }
        return UiError(
            code = STARTUP_CODE,
            userMessage = userMessage,
            technicalDetail = detail,
            recoveryAction = recovery,
        )
    }

    fun fromImageResult(result: String): UiError? {
        if (!result.startsWith("IMAGE_VALIDATION_ERROR:")) {
            return null
        }
        return UiError(
            code = IMAGE_VALIDATION_CODE,
            userMessage = "That image could not be processed. Use a supported file and try again.",
            technicalDetail = result,
        )
    }

    fun fromImageResult(result: ImageAnalysisResult): UiError? {
        return when (result) {
            is ImageAnalysisResult.Success -> null
            is ImageAnalysisResult.Failure -> when (result.failure) {
                is com.pocketagent.runtime.ImageFailure.Validation -> UiError(
                    code = IMAGE_VALIDATION_CODE,
                    userMessage = result.failure.userMessage,
                    technicalDetail = result.failure.technicalDetail,
                )
                else -> UiError(
                    code = RUNTIME_CODE,
                    userMessage = result.failure.userMessage,
                    technicalDetail = result.failure.technicalDetail,
                )
            }
        }
    }

    fun fromToolResult(result: String): UiError? {
        if (result.startsWith("TOOL_VALIDATION_ERROR:")) {
            return UiError(
                code = TOOL_SCHEMA_CODE,
                userMessage = "That tool request was rejected for safety. Update input and retry.",
                technicalDetail = result,
            )
        }
        if (result.contains("TOOL_VALIDATION_ERROR:")) {
            return UiError(
                code = TOOL_SCHEMA_CODE,
                userMessage = "That tool request was rejected for safety. Update input and retry.",
                technicalDetail = result,
            )
        }
        if (result.startsWith("Tool error:")) {
            return runtimeFailure(result)
        }
        return null
    }

    fun fromToolResult(result: ToolExecutionResult): UiError? {
        return when (result) {
            is ToolExecutionResult.Success -> null
            is ToolExecutionResult.Failure -> when (result.failure) {
                is com.pocketagent.runtime.ToolFailure.Validation -> UiError(
                    code = TOOL_SCHEMA_CODE,
                    userMessage = result.failure.userMessage,
                    technicalDetail = result.failure.technicalDetail,
                )
                else -> UiError(
                    code = RUNTIME_CODE,
                    userMessage = result.failure.userMessage,
                    technicalDetail = result.failure.technicalDetail,
                )
            }
        }
    }

    fun runtimeFailure(detail: String?): UiError {
        val normalized = detail?.lowercase().orEmpty()
        if (normalized.contains("image_attachments_unsupported") ||
            normalized.contains("does not support image attachments") ||
            normalized.contains("cannot process image attachments")
        ) {
            // TODO: migrate to string resource for localization
            return UiError(
                code = RUNTIME_CODE,
                userMessage = "This model cannot use image attachments. Switch to a vision-capable model and retry.",
                technicalDetail = detail,
                recoveryAction = RecoveryAction.CHANGE_MODEL,
            )
        }
        return UiError(
            code = RUNTIME_CODE,
            userMessage = "Request failed. Please try again.",
            technicalDetail = detail,
            recoveryAction = RecoveryAction.RETRY_LOAD,
        )
    }

    fun runtimeCancelled(reason: String?): UiError {
        val normalized = reason?.trim().orEmpty().ifBlank { "cancelled" }
        return UiError(
            code = RUNTIME_CODE,
            userMessage = "Request was cancelled. Send again when ready.",
            technicalDetail = "Generation cancelled: $normalized.",
        )
    }

    fun runtimeTimeout(timeoutMs: Long): UiError {
        val seconds = (timeoutMs / 1000L).coerceAtLeast(1L)
        return UiError(
            code = RUNTIME_CODE,
            userMessage = "Request timed out. Confirm runtime readiness and retry.",
            technicalDetail = "Generation timed out after ${seconds}s.",
            recoveryAction = RecoveryAction.REFRESH_CHECKS,
        )
    }

    private const val MODEL_LIFECYCLE_CODE = "UI-MODEL-LIFECYCLE-001"

    fun fromModelLifecycleResult(result: RuntimeModelLifecycleCommandResult): UiError? {
        if (result.success) return null
        val errorCode = result.errorCodeName() ?: "UNKNOWN"
        val normalizedDetail = result.detail?.lowercase().orEmpty()
        val (userMessage, recovery) = when (errorCode) {
            "MODEL_FILE_UNAVAILABLE" ->
                "Model file is unavailable. Re-download or re-import the model." to RecoveryAction.REDOWNLOAD_MODEL
            "RUNTIME_INCOMPATIBLE" ->
                if (
                    normalizedDetail.contains("runtime_incompatible_model_format")
                ) {
                    "This model format is not compatible with the current runtime. Choose a different model." to RecoveryAction.CHANGE_MODEL
                } else {
                    "Model is not compatible with this runtime. Choose a different model." to RecoveryAction.CHANGE_MODEL
                }
            "OUT_OF_MEMORY" ->
                "Model could not fit in available memory. Try a smaller model, lower context, or offload the current model first." to RecoveryAction.CHANGE_MODEL
            "BACKEND_INIT_FAILED" ->
                "Runtime backend failed to initialize. Restart the app and try again." to RecoveryAction.RESTART_APP
            "BUSY_GENERATION" ->
                "Model is busy with an active generation. Try loading again shortly." to RecoveryAction.RETRY_LOAD
            "CANCELLED_BY_NEWER_REQUEST" ->
                "Load was superseded by a newer request." to RecoveryAction.NONE
            "UNKNOWN" ->
                "Model failed to load. Please try again." to RecoveryAction.RETRY_LOAD
            else ->
                "Model failed to load. Please try again." to RecoveryAction.RETRY_LOAD
        }
        return UiError(
            code = MODEL_LIFECYCLE_CODE,
            userMessage = userMessage,
            technicalDetail = result.detail,
            recoveryAction = recovery,
        )
    }
}
