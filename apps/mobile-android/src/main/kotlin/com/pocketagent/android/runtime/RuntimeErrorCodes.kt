package com.pocketagent.android.runtime

/**
 * Central runtime error-code registry for Android app/runtime boundary.
 *
 * Keep codes stable once shipped so diagnostics and telemetry remain queryable.
 */
object RuntimeErrorCodes {
    const val PROVISIONING_IMPORT_SOURCE_UNREADABLE = "PROVISIONING_IMPORT_SOURCE_UNREADABLE"
    const val PROVISIONING_IMPORT_INVALID_GGUF = "PROVISIONING_IMPORT_INVALID_GGUF"
    const val PROVISIONING_IMPORT_MODEL_MISMATCH = "PROVISIONING_IMPORT_MODEL_MISMATCH"
    const val PROVISIONING_IMPORT_PERSIST_FAILED = "PROVISIONING_IMPORT_PERSIST_FAILED"
    const val PROVISIONING_REMOVE_ACTIVE_VERSION_BLOCKED = "PROVISIONING_REMOVE_ACTIVE_VERSION_BLOCKED"
    const val MODEL_MANIFEST_HTTP_ERROR = "MODEL_MANIFEST_HTTP_ERROR"
    const val MODEL_ADMISSION_BLOCKED = "MODEL_ADMISSION_BLOCKED"
}

data class RuntimeDomainError(
    val code: String,
    val userMessage: String,
    val technicalDetail: String? = null,
)

class RuntimeDomainException(
    val domainError: RuntimeDomainError,
    cause: Throwable? = null,
) : RuntimeException(
    buildString {
        append(domainError.code)
        domainError.technicalDetail?.takeIf { it.isNotBlank() }?.let {
            append(":")
            append(it)
        }
    },
    cause,
)
