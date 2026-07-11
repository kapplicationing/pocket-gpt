package com.pocketagent.android.runtime

import com.pocketagent.android.runtime.modelmanager.gguf.GgufMetadata
import com.pocketagent.inference.ModelCatalog
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModelImportIdentityValidatorTest {
    @Test
    fun `matching architecture and dimensions are accepted`() {
        ModelImportIdentityValidator.validateMetadataOrThrow(
            modelId = ModelCatalog.QWEN3_0_6B_Q4_K_M,
            metadata = metadata(architecture = "qwen3", embeddingSize = 1024),
        )
    }

    @Test
    fun `architecture mismatch is rejected with stable domain code`() {
        val error = assertFailsWith<RuntimeDomainException> {
            ModelImportIdentityValidator.validateMetadataOrThrow(
                modelId = ModelCatalog.QWEN3_0_6B_Q4_K_M,
                metadata = metadata(architecture = "llama", embeddingSize = 1024),
            )
        }

        assertEquals(RuntimeErrorCodes.PROVISIONING_IMPORT_MODEL_MISMATCH, error.domainError.code)
    }

    @Test
    fun `same architecture with wrong model dimensions is rejected`() {
        val error = assertFailsWith<RuntimeDomainException> {
            ModelImportIdentityValidator.validateMetadataOrThrow(
                modelId = ModelCatalog.QWEN3_0_6B_Q4_K_M,
                metadata = metadata(architecture = "qwen3", embeddingSize = 2048),
            )
        }

        assertEquals(RuntimeErrorCodes.PROVISIONING_IMPORT_MODEL_MISMATCH, error.domainError.code)
    }

    @Test
    fun `missing declared architecture is rejected instead of assuming llama`() {
        val error = assertFailsWith<RuntimeDomainException> {
            ModelImportIdentityValidator.validateMetadataOrThrow(
                modelId = ModelCatalog.LLAMA_3_2_1B_Q4_K_M,
                metadata = metadata(architecture = null, embeddingSize = 2048),
            )
        }

        assertEquals(RuntimeErrorCodes.PROVISIONING_IMPORT_MODEL_MISMATCH, error.domainError.code)
    }

    @Test
    fun `metadata-only file identity is rejected`() {
        val error = assertFailsWith<RuntimeDomainException> {
            ModelImportIdentityValidator.validateMetadataOrThrow(
                modelId = ModelCatalog.QWEN3_0_6B_Q4_K_M,
                metadata = metadata(
                    architecture = "qwen3",
                    embeddingSize = 1024,
                    tensorCount = 0L,
                ),
            )
        }

        assertEquals(RuntimeErrorCodes.PROVISIONING_IMPORT_MODEL_MISMATCH, error.domainError.code)
    }

    @Test
    fun `malformed file is rejected before publication`() {
        val malformed = Files.createTempFile("pocketgpt-invalid-import", ".gguf").toFile()
            .apply { writeText("not-a-gguf") }

        try {
            val error = assertFailsWith<RuntimeDomainException> {
                ModelImportIdentityValidator.validateOrThrow(
                    modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
                    modelFile = malformed,
                )
            }
            assertEquals(RuntimeErrorCodes.PROVISIONING_IMPORT_INVALID_GGUF, error.domainError.code)
        } finally {
            malformed.delete()
        }
    }

    private fun metadata(
        architecture: String?,
        embeddingSize: Int,
        tensorCount: Long = 1L,
    ): GgufMetadata {
        return GgufMetadata(
            version = GgufMetadata.GgufVersion.VALIDATED_V3,
            tensorCount = tensorCount,
            kvCount = 4L,
            basic = GgufMetadata.BasicInfo(),
            architecture = GgufMetadata.ArchitectureInfo(
                architecture = architecture,
                fileType = 15,
            ),
            dimensions = GgufMetadata.DimensionsInfo(embeddingSize = embeddingSize),
        )
    }
}
