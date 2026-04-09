package com.pocketagent.android.runtime

import com.pocketagent.android.runtime.modelmanager.InstalledArtifactDescriptor
import com.pocketagent.core.model.ModelArtifactRole
import com.pocketagent.core.model.ModelSourceKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StoredModelSidecarMetadataTest {
    @Test
    fun `sidecar metadata round trips artifacts and prompt profile`() {
        val file = File.createTempFile("pocketgpt-sidecar", ".json")
        try {
            StoredModelSidecarMetadataStore.write(
                metadataFile = file,
                metadata = StoredModelSidecarMetadata(
                    modelId = "qwen3.5-0.8b-q4",
                    version = "q4_0",
                    sourceKind = ModelSourceKind.HUGGING_FACE,
                    promptProfileId = "chatml-default",
                    artifacts = listOf(
                        InstalledArtifactDescriptor(
                            artifactId = "primary",
                            role = ModelArtifactRole.PRIMARY_GGUF,
                            fileName = "qwen.gguf",
                            absolutePath = "/tmp/qwen.gguf",
                        ),
                        InstalledArtifactDescriptor(
                            artifactId = "mmproj",
                            role = ModelArtifactRole.MMPROJ,
                            fileName = "qwen-mmproj.gguf",
                            absolutePath = "/tmp/qwen-mmproj.gguf",
                        ),
                    ),
                ),
            )

            val decoded = StoredModelSidecarMetadataStore.read(file)

            assertNotNull(decoded)
            assertEquals(ModelSourceKind.HUGGING_FACE, decoded.sourceKind)
            assertEquals("chatml-default", decoded.promptProfileId)
            assertEquals(2, decoded.artifacts.size)
            assertEquals(ModelArtifactRole.MMPROJ, decoded.artifacts.last().role)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `legacy gguf sidecar is decoded into parameter snapshot`() {
        val file = File.createTempFile("pocketgpt-sidecar-legacy", ".json")
        try {
            file.writeText(
                """
                {
                  "modelId": "llama-3.2-1b-instruct-q4_k_m",
                  "version": "q4_k_m",
                  "gguf": {
                    "architecture": {
                      "architecture": "gemma2",
                      "quantizationVersion": 2,
                      "vocabSize": 256000
                    },
                    "dimensions": {
                      "contextLength": 8192,
                      "blockCount": 26,
                      "embeddingSize": 2304
                    },
                    "attention": {
                      "headCount": 8,
                      "headCountKv": 4
                    }
                  }
                }
                """.trimIndent(),
            )

            val decoded = StoredModelSidecarMetadataStore.read(file)

            assertNotNull(decoded)
            assertEquals("gemma2", decoded.parameters.architecture)
            assertEquals(8192, decoded.parameters.contextLength)
            assertEquals(26, decoded.parameters.layerCount)
            assertEquals(2304, decoded.parameters.embeddingSize)
            assertEquals(8, decoded.parameters.headCount)
            assertEquals(4, decoded.parameters.headCountKv)
            assertEquals(256000, decoded.parameters.vocabularySize)
            assertNull(decoded.promptProfileId)
        } finally {
            file.delete()
        }
    }
}
