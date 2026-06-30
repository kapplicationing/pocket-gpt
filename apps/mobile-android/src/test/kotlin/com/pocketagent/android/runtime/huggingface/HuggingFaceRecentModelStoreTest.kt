package com.pocketagent.android.runtime.huggingface

import com.pocketagent.android.runtime.modelmanager.DownloadVerificationPolicy
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.core.model.ModelSourceKind
import kotlin.test.Test
import kotlin.test.assertEquals

class HuggingFaceRecentModelStoreTest {
    @Test
    fun `recent models round trip through json codec`() {
        val recent = sampleCandidate().toRecentModel(enqueuedAtEpochMs = 1234L)

        val decoded = decodeRecentModels(encodeRecentModels(listOf(recent)))

        assertEquals(listOf(recent), decoded)
        assertEquals(
            "https://huggingface.co/owner/repo/resolve/main/model.gguf",
            decoded.single().originUrl,
        )
    }

    @Test
    fun `decode skips malformed rows`() {
        val raw = """
            [
              {"repoId":"owner/repo","revision":"main"},
              {
                "repoId":"owner/repo",
                "revision":"main",
                "filePath":"model.gguf",
                "targetModelId":"qwen3.5-0.8b-q4",
                "sha256":"${"a".repeat(64)}",
                "sizeBytes":1024,
                "lastDownloadEnqueuedAtEpochMs":99
              }
            ]
        """.trimIndent()

        val decoded = decodeRecentModels(raw)

        assertEquals(1, decoded.size)
        assertEquals("owner/repo / model.gguf", decoded.single().displayName)
    }

    private fun sampleCandidate(): HuggingFaceCandidate {
        val version = ModelDistributionVersion(
            modelId = "qwen3.5-0.8b-q4",
            version = "hf-model-aaaaaaaaaaaa",
            downloadUrl = "https://huggingface.co/owner/repo/resolve/main/model.gguf",
            expectedSha256 = "a".repeat(64),
            provenanceIssuer = "huggingface:owner/repo",
            provenanceSignature = "",
            runtimeCompatibility = "android-arm64-v8a",
            fileSizeBytes = 1024L,
            sourceKind = ModelSourceKind.HUGGING_FACE,
            displayName = "owner/repo / model.gguf",
            verificationPolicy = DownloadVerificationPolicy.INTEGRITY_ONLY,
        )
        return HuggingFaceCandidate(
            reference = HuggingFaceModelReference(
                repoId = "owner/repo",
                revision = "main",
                filePath = "model.gguf",
            ),
            target = HuggingFaceTargetModel(
                modelId = "qwen3.5-0.8b-q4",
                displayName = "Qwen 3.5 0.8B",
            ),
            displayName = "owner/repo / model.gguf",
            sha256 = "a".repeat(64),
            sizeBytes = 1024L,
            version = version,
        )
    }
}
