package com.pocketagent.runtime

/** Runtime-owned recovery intent that can cross UI and service boundaries without parsing messages. */
enum class RuntimeRecoveryDisposition {
    RETRY_REQUEST,
    REPROVISION_MODEL,
    SELECT_DIFFERENT_MODEL,
    RESTART_RUNTIME,
    REFRESH_RUNTIME_CHECKS,
    NONE,
}

object RuntimeFailureRecoveryPolicy {
    fun forErrorCode(errorCode: String?): RuntimeRecoveryDisposition {
        return when (errorCode.normalizedRuntimeErrorCode()) {
            "model_file_unavailable",
            "missing_runtime_model",
            "manifest_invalid",
            "unknown_model",
            "unknown_version",
            "missing_payload",
            "checksum_mismatch",
            "provenance_issuer_mismatch",
            "provenance_signature_mismatch" -> RuntimeRecoveryDisposition.REPROVISION_MODEL

            "runtime_incompatible",
            "runtime_incompatible_model_format",
            "out_of_memory",
            "memory_budget_exceeded",
            "template_unavailable",
            "image_attachments_unsupported" -> RuntimeRecoveryDisposition.SELECT_DIFFERENT_MODEL

            "backend_init_failed" -> RuntimeRecoveryDisposition.RESTART_RUNTIME

            "startup_checks_timeout", "runtime_not_ready" -> RuntimeRecoveryDisposition.REFRESH_RUNTIME_CHECKS

            "cancelled_by_newer_request" -> RuntimeRecoveryDisposition.NONE
            "busy_generation" -> RuntimeRecoveryDisposition.RETRY_REQUEST
            else -> RuntimeRecoveryDisposition.RETRY_REQUEST
        }
    }
}

private fun String?.normalizedRuntimeErrorCode(): String {
    return this
        ?.trim()
        ?.lowercase()
        ?.replace('-', '_')
        .orEmpty()
}
