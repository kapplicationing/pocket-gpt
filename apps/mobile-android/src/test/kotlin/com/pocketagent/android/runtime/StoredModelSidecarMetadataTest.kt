package com.pocketagent.android.runtime

import com.pocketagent.android.runtime.modelmanager.InstalledArtifactDescriptor
import com.pocketagent.core.model.ModelArtifactRole
import com.pocketagent.core.model.ModelSourceKind
import com.pocketagent.core.model.ModelSourceRef
import com.pocketagent.core.model.SourceTrustPolicy
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

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
                    displayName = "unsloth/Qwen3.5-0.8B-GGUF / qwen.gguf",
                    sourceKind = ModelSourceKind.HUGGING_FACE,
                    sourceRef = ModelSourceRef(
                        kind = ModelSourceKind.HUGGING_FACE,
                        originId = "qwen3.5-0.8b-q4",
                        publisher = "unsloth",
                        repository = "unsloth/Qwen3.5-0.8B-GGUF",
                        trustPolicy = SourceTrustPolicy.INTEGRITY_ONLY,
                        revision = "main",
                        originUrl = "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/qwen.gguf",
                    ),
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
                    parameters = StoredModelParameterSnapshot(
                        architecture = "qwen3",
                        quantization = "Q4_K_M",
                        contextLength = 32768,
                        layerCount = 28,
                    ),
                ),
            )

            val decoded = StoredModelSidecarMetadataStore.read(file)

            assertNotNull(decoded)
            assertEquals("unsloth/Qwen3.5-0.8B-GGUF / qwen.gguf", decoded.displayName)
            assertEquals(ModelSourceKind.HUGGING_FACE, decoded.sourceKind)
            assertEquals("unsloth/Qwen3.5-0.8B-GGUF", decoded.sourceRef?.repository)
            assertEquals(SourceTrustPolicy.INTEGRITY_ONLY, decoded.sourceRef?.trustPolicy)
            assertEquals("main", decoded.sourceRef?.revision)
            assertEquals("https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/qwen.gguf", decoded.sourceRef?.originUrl)
            assertEquals("chatml-default", decoded.promptProfileId)
            assertEquals(2, decoded.artifacts.size)
            assertEquals(ModelArtifactRole.MMPROJ, decoded.artifacts.last().role)
            assertEquals("qwen3", decoded.parameters.architecture)
            assertEquals("Q4_K_M", decoded.parameters.quantization)
            assertEquals(32768, decoded.parameters.contextLength)
            assertEquals(28, decoded.parameters.layerCount)
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

    @Test
    fun `sidecar metadata read is cached until file changes`() {
        val file = File.createTempFile("pocketgpt-sidecar-cache", ".json")
        try {
            StoredModelSidecarMetadataStore.write(
                metadataFile = file,
                metadata = StoredModelSidecarMetadata(
                    modelId = "qwen3.5-0.8b-q4",
                    version = "q4_0",
                    sourceKind = ModelSourceKind.LOCAL_IMPORT,
                ),
            )

            val first = StoredModelSidecarMetadataStore.read(file)
            val second = StoredModelSidecarMetadataStore.read(file)

            assertSame(first, second)

            StoredModelSidecarMetadataStore.write(
                metadataFile = file,
                metadata = StoredModelSidecarMetadata(
                    modelId = "qwen3.5-0.8b-q4",
                    version = "q8_0_extended",
                    sourceKind = ModelSourceKind.HUGGING_FACE,
                ),
            )

            val changed = StoredModelSidecarMetadataStore.read(file)

            assertNotNull(changed)
            assertEquals("q8_0_extended", changed.version)
        } finally {
            file.delete()
        }
    }
}
