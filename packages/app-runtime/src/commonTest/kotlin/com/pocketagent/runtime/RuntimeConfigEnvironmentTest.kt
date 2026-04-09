package com.pocketagent.runtime

import com.pocketagent.inference.ModelCatalog
import com.pocketagent.inference.ModelRuntimeProfile
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class RuntimeConfigEnvironmentTest {
    @Test
    fun `model env names are derived from catalog tokens`() {
        assertEquals(
            "POCKETGPT_MODEL_QWEN_3_5_0_8B_Q4_SIDELOAD_PATH",
            RuntimeConfig.sideLoadPathEnvName(ModelCatalog.QWEN_3_5_0_8B_Q4),
        )
        assertEquals(
            "POCKETGPT_MODEL_QWEN3_0_6B_Q4_K_M_SHA256",
            RuntimeConfig.sha256EnvName(ModelCatalog.QWEN3_0_6B_Q4_K_M),
        )
    }

    @Test
    fun `fromEnvironment parses model-agnostic env keys for qwen3 0_6b model`() {
        val modelId = ModelCatalog.QWEN3_0_6B_Q4_K_M
        val payloadSha = "a".repeat(64)
        val environment = mapOf(
            RuntimeConfig.MODEL_RUNTIME_PROFILE_ENV to "dev_fast",
            RuntimeConfig.MODEL_PROVENANCE_ISSUER_ENV to "global-issuer",
            RuntimeConfig.sideLoadPathEnvName(modelId) to "/tmp/qwen3-0.6b.gguf",
            RuntimeConfig.sha256EnvName(modelId) to payloadSha,
            RuntimeConfig.provenanceSignatureEnvName(modelId) to "sig-qwen3-0.6b",
        )

        val config = RuntimeConfig.fromEnvironment(environment = environment)

        assertEquals(ModelRuntimeProfile.DEV_FAST, config.modelRuntimeProfile)
        assertEquals("/tmp/qwen3-0.6b.gguf", config.artifactFilePathByModelId.getValue(modelId))
        assertEquals(payloadSha, config.artifactSha256ByModelId.getValue(modelId))
        assertEquals("global-issuer", config.artifactProvenanceIssuerByModelId.getValue(modelId))
        assertEquals("sig-qwen3-0.6b", config.artifactProvenanceSignatureByModelId.getValue(modelId))
    }

    @Test
    fun `per-model issuer env overrides global issuer when deriving signature`() {
        val modelId = ModelCatalog.QWEN_3_5_0_8B_Q4
        val payloadSha = "b".repeat(64)
        val modelIssuer = "model-issuer"
        val environment = mapOf(
            RuntimeConfig.MODEL_PROVENANCE_ISSUER_ENV to "global-issuer",
            RuntimeConfig.sha256EnvName(modelId) to payloadSha,
            RuntimeConfig.provenanceIssuerEnvName(modelId) to modelIssuer,
        )

        val config = RuntimeConfig.fromEnvironment(environment = environment)

        assertEquals(modelIssuer, config.artifactProvenanceIssuerByModelId.getValue(modelId))
        assertEquals(
            sha256Hex("$modelIssuer|$modelId|$payloadSha|v1".encodeToByteArray()),
            config.artifactProvenanceSignatureByModelId.getValue(modelId),
        )
    }

    @Test
    fun `fromEnvironment loads sideload payload bytes when file exists`() {
        val modelId = ModelCatalog.QWEN_3_5_0_8B_Q4
        val tempFile = Files.createTempFile("runtime-config-test-", ".gguf")
        val payload = "tiny-test-payload".encodeToByteArray()
        Files.write(tempFile, payload)
        try {
            val config = RuntimeConfig.fromEnvironment(
                environment = mapOf(
                    RuntimeConfig.sideLoadPathEnvName(modelId) to tempFile.toString(),
                ),
            )

            assertContentEquals(payload, config.artifactPayloadByModelId.getValue(modelId))
            assertEquals(sha256Hex(payload), config.artifactSha256ByModelId.getValue(modelId))
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    @Test
    fun `fromEnvironment ignores sideload payload for missing file`() {
        val modelId = ModelCatalog.QWEN_3_5_0_8B_Q4
        val config = RuntimeConfig.fromEnvironment(
            environment = mapOf(
                RuntimeConfig.sideLoadPathEnvName(modelId) to "/tmp/path-that-does-not-exist-for-runtime-config.gguf",
            ),
        )

        assertNull(config.artifactPayloadByModelId[modelId])
    }
}

private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
