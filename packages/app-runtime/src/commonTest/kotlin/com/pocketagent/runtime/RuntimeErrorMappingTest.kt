package com.pocketagent.runtime

import com.pocketagent.nativebridge.ModelLifecycleErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeErrorMappingTest {
    @Test
    fun `stable runtime error codes map to typed recovery dispositions`() {
        val expected = mapOf(
            "MODEL_FILE_UNAVAILABLE" to RuntimeRecoveryDisposition.REPROVISION_MODEL,
            "manifest_invalid" to RuntimeRecoveryDisposition.REPROVISION_MODEL,
            "unknown_model" to RuntimeRecoveryDisposition.REPROVISION_MODEL,
            "unknown_version" to RuntimeRecoveryDisposition.REPROVISION_MODEL,
            "missing_payload" to RuntimeRecoveryDisposition.REPROVISION_MODEL,
            "checksum_mismatch" to RuntimeRecoveryDisposition.REPROVISION_MODEL,
            "provenance_issuer_mismatch" to RuntimeRecoveryDisposition.REPROVISION_MODEL,
            "provenance_signature_mismatch" to RuntimeRecoveryDisposition.REPROVISION_MODEL,
            "runtime_incompatible_model_format" to RuntimeRecoveryDisposition.SELECT_DIFFERENT_MODEL,
            "out-of-memory" to RuntimeRecoveryDisposition.SELECT_DIFFERENT_MODEL,
            "backend_init_failed" to RuntimeRecoveryDisposition.RESTART_RUNTIME,
            "busy_generation" to RuntimeRecoveryDisposition.RETRY_REQUEST,
            "template_unavailable" to RuntimeRecoveryDisposition.SELECT_DIFFERENT_MODEL,
            "cancelled_by_newer_request" to RuntimeRecoveryDisposition.NONE,
            "unrecognized_runtime_error" to RuntimeRecoveryDisposition.RETRY_REQUEST,
        )

        expected.forEach { (errorCode, disposition) ->
            assertEquals(disposition, RuntimeFailureRecoveryPolicy.forErrorCode(errorCode), errorCode)
        }
    }

    @Test
    fun `bridge compatibility code wins over generic model wording`() {
        assertEquals(
            ModelLifecycleErrorCode.RUNTIME_INCOMPATIBLE,
            mapBridgeLifecycleCode("RUNTIME_INCOMPATIBLE_MODEL_FORMAT"),
        )
        assertEquals(
            ModelLifecycleErrorCode.MODEL_FILE_UNAVAILABLE,
            mapBridgeLifecycleCode("MODEL_FILE_UNAVAILABLE"),
        )
    }
}
