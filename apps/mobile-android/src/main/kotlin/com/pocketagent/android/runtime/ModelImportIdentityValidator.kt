package com.pocketagent.android.runtime

import com.pocketagent.android.runtime.modelmanager.GgufMetadataExtractor
import com.pocketagent.android.runtime.modelmanager.gguf.GgufMetadata
import com.pocketagent.inference.ModelCatalog
import java.io.File
import java.util.Locale

internal object ModelImportIdentityValidator {
    fun validateOrThrow(modelId: String, modelFile: File) {
        val metadata = GgufMetadataExtractor.inspectValidatedModelStructure(modelFile).getOrElse { error ->
            throw RuntimeDomainException(
                domainError = RuntimeDomainError(
                    code = RuntimeErrorCodes.PROVISIONING_IMPORT_INVALID_GGUF,
                    userMessage = "The selected file is not a valid supported GGUF model.",
                    technicalDetail = "model=$modelId;path=${modelFile.absolutePath};error=${error.message}",
                ),
                cause = error,
            )
        }
        validateMetadataOrThrow(modelId = modelId, metadata = metadata)
    }

    internal fun validateMetadataOrThrow(modelId: String, metadata: GgufMetadata) {
        if (metadata.tensorCount <= 0L) {
            throw modelMismatch(
                detail = "model=$modelId;tensor_count=${metadata.tensorCount}",
            )
        }
        val descriptor = ModelCatalog.descriptorFor(modelId) ?: return
        val actualArchitecture = metadata.architecture?.architecture
            ?.trim()
            ?.lowercase(Locale.US)
        val actualEmbeddingSize = metadata.dimensions?.embeddingSize
        val architectureMatches = actualArchitecture != null &&
            descriptor.ggufArchitectures.any { expected -> expected.equals(actualArchitecture, ignoreCase = true) }
        val dimensionsMatch = descriptor.expectedEmbeddingSize?.let { expected ->
            actualEmbeddingSize == expected
        } ?: true
        if (!architectureMatches || !dimensionsMatch) {
            throw modelMismatch(
                detail = buildString {
                    append("model=$modelId")
                    append(";expected_arch=${descriptor.ggufArchitectures.sorted().joinToString(",")}")
                    append(";actual_arch=${actualArchitecture ?: "missing"}")
                    append(";expected_embedding=${descriptor.expectedEmbeddingSize ?: "any"}")
                    append(";actual_embedding=${actualEmbeddingSize ?: "missing"}")
                },
            )
        }
    }

    private fun modelMismatch(detail: String): RuntimeDomainException {
        return RuntimeDomainException(
            domainError = RuntimeDomainError(
                code = RuntimeErrorCodes.PROVISIONING_IMPORT_MODEL_MISMATCH,
                userMessage = "The selected GGUF belongs to a different model.",
                technicalDetail = detail,
            ),
        )
    }
}
