package com.pocketagent.android.runtime.modelmanager

import com.pocketagent.android.runtime.huggingface.DefaultHuggingFaceModelAcquisition
import com.pocketagent.android.runtime.huggingface.FixtureHuggingFaceEndpointAdapter
import com.pocketagent.android.runtime.huggingface.HuggingFaceAcquisitionBlockReason
import com.pocketagent.android.runtime.huggingface.HuggingFaceAcquisitionException
import com.pocketagent.android.runtime.huggingface.HuggingFaceHubClient
import com.pocketagent.android.runtime.huggingface.HuggingFaceHubFileMetadata
import com.pocketagent.android.runtime.huggingface.HuggingFaceHubRepositoryMetadata
import com.pocketagent.android.runtime.huggingface.HuggingFaceModelReference
import com.pocketagent.android.runtime.huggingface.OkHttpHuggingFaceHubClient
import com.pocketagent.android.runtime.huggingface.RealHuggingFaceEndpointAdapter
import com.pocketagent.android.runtime.huggingface.configuredHuggingFaceEndpointAdapter
import com.pocketagent.android.runtime.huggingface.treeApiUrl
import com.pocketagent.core.model.ModelSourceKind
import com.pocketagent.core.model.SourceTrustPolicy
import com.pocketagent.inference.ModelCatalog
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HuggingFaceModelReferenceTest {
    @Test
    fun `parse accepts resolve urls and builds canonical resolve url`() {
        val reference = HuggingFaceModelReference.parse(
            "https://huggingface.co/owner/repo/resolve/main/models/Qwen3-0.6B-Q4_K_M.gguf",
        )

        assertEquals("owner/repo", reference.repoId)
        assertEquals("main", reference.revision)
        assertEquals("models/Qwen3-0.6B-Q4_K_M.gguf", reference.filePath)
        assertEquals(
            "https://huggingface.co/owner/repo/resolve/main/models/Qwen3-0.6B-Q4_K_M.gguf",
            reference.canonicalResolveUrl,
        )
    }

    @Test
    fun `parse accepts blob urls but canonicalizes to resolve urls`() {
        val reference = HuggingFaceModelReference.parse(
            "https://huggingface.co/owner/repo/blob/main/model.gguf",
        )

        assertEquals("https://huggingface.co/owner/repo/resolve/main/model.gguf", reference.canonicalResolveUrl)
    }

    @Test
    fun `tree API lookup targets parent directory not file path`() {
        val rootFile = HuggingFaceModelReference.parse(
            "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf",
        )
        val nestedFile = HuggingFaceModelReference.parse(
            "https://huggingface.co/owner/repo/resolve/main/models/text/model.gguf",
        )

        assertEquals(
            "https://huggingface.co/api/models/unsloth/Qwen3-0.6B-GGUF/tree/main",
            rootFile.treeApiUrl().toString(),
        )
        assertEquals(
            "https://huggingface.co/api/models/owner/repo/tree/main/models/text",
            nestedFile.treeApiUrl().toString(),
        )
    }

    @Test
    fun `parse rejects invalid hosts non gguf and sharded gguf`() {
        assertFailsWith<HuggingFaceAcquisitionException> {
            HuggingFaceModelReference.parse("https://example.test/owner/repo/resolve/main/model.gguf")
        }.also { error ->
            assertEquals(HuggingFaceAcquisitionBlockReason.INVALID_URL, error.reason)
        }
        assertFailsWith<HuggingFaceAcquisitionException> {
            HuggingFaceModelReference.parse("https://huggingface.co/owner/repo/resolve/main/model.bin")
        }.also { error ->
            assertEquals(HuggingFaceAcquisitionBlockReason.NON_GGUF, error.reason)
        }
        assertFailsWith<HuggingFaceAcquisitionException> {
            HuggingFaceModelReference.parse("https://huggingface.co/owner/repo/resolve/main/model-00001-of-00002.gguf")
        }.also { error ->
            assertEquals(HuggingFaceAcquisitionBlockReason.SHARDED_GGUF, error.reason)
        }
    }
}

class HuggingFaceModelAcquisitionTest {
    @Test
    fun `endpoint adapters preserve real source URL while fixture rewrites network URLs`() {
        val reference = HuggingFaceModelReference.parse(
            "https://huggingface.co/owner/repo/resolve/main/models/model.gguf",
        )
        val fixtureAdapter = FixtureHuggingFaceEndpointAdapter("http://127.0.0.1:8765/base/".toHttpUrl())

        assertEquals(
            "https://huggingface.co/api/models/owner/repo/tree/main/models",
            RealHuggingFaceEndpointAdapter.treeApiUrl(reference).toString(),
        )
        assertEquals(
            "https://huggingface.co/api/models/owner/repo/revision/main",
            RealHuggingFaceEndpointAdapter.modelInfoApiUrl(reference).toString(),
        )
        assertEquals(
            "https://huggingface.co/api/models?search=tiny&limit=5&full=true",
            RealHuggingFaceEndpointAdapter.modelSearchApiUrl(query = "tiny", limit = 5).toString(),
        )
        assertEquals(
            "https://huggingface.co/owner/repo/resolve/main/models/model.gguf",
            RealHuggingFaceEndpointAdapter.artifactDownloadUrl(reference),
        )
        assertEquals(
            "http://127.0.0.1:8765/base/api/models/owner/repo/tree/main/models",
            fixtureAdapter.treeApiUrl(reference).toString(),
        )
        assertEquals(
            "http://127.0.0.1:8765/base/api/models/owner/repo/revision/main",
            fixtureAdapter.modelInfoApiUrl(reference).toString(),
        )
        assertEquals(
            "http://127.0.0.1:8765/base/api/models?search=tiny&limit=5&full=true",
            fixtureAdapter.modelSearchApiUrl(query = "tiny", limit = 5).toString(),
        )
        assertEquals(
            "http://127.0.0.1:8765/base/owner/repo/resolve/main/models/model.gguf",
            fixtureAdapter.artifactDownloadUrl(reference),
        )
        assertTrue(configuredHuggingFaceEndpointAdapter("") is RealHuggingFaceEndpointAdapter)
        assertTrue(configuredHuggingFaceEndpointAdapter("http://127.0.0.1:8765/") is FixtureHuggingFaceEndpointAdapter)
    }

    @Test
    fun `fixture endpoint resolves hub metadata through real http client without changing provenance url`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    [
                      {
                        "path": "models/model.gguf",
                        "lfs": {
                          "oid": "${"b".repeat(64)}",
                          "size": 4096
                        }
                      }
                    ]
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "id": "owner/repo",
                      "cardData": {
                        "license": "apache-2.0",
                        "license_link": "https://huggingface.co/owner/repo/blob/main/LICENSE"
                      },
                      "tags": ["license:apache-2.0"]
                    }
                    """.trimIndent(),
                ),
        )
        server.start()
        try {
            val endpointAdapter = FixtureHuggingFaceEndpointAdapter(server.url("/"))
            val acquisition = DefaultHuggingFaceModelAcquisition(
                endpointAdapter = endpointAdapter,
                hubClient = OkHttpHuggingFaceHubClient(endpointAdapter),
            )

            val candidate = acquisition.resolveCandidate(
                input = "https://huggingface.co/owner/repo/resolve/main/models/model.gguf",
                targetModelId = ModelCatalog.QWEN3_0_6B_Q4_K_M,
            )

            assertEquals(
                "/api/models/owner/repo/tree/main/models",
                server.takeRequest().path,
            )
            assertEquals(
                "/api/models/owner/repo/revision/main",
                server.takeRequest().path,
            )
            assertEquals(
                "https://huggingface.co/owner/repo/resolve/main/models/model.gguf",
                candidate.version.sourceRef?.originUrl,
            )
            assertEquals(
                server.url("/owner/repo/resolve/main/models/model.gguf").toString(),
                candidate.version.downloadUrl,
            )
            assertEquals(candidate.version.downloadUrl, candidate.version.artifacts.single().downloadUrl)
            assertEquals("https://huggingface.co/owner/repo", candidate.modelCardUrl)
            assertEquals("apache-2.0", candidate.license)
            assertEquals("https://huggingface.co/owner/repo/blob/main/LICENSE", candidate.licenseUrl)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `fixture endpoint searches gguf siblings through real http client`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    [
                      {
                        "id": "owner/repo",
                        "downloads": 42,
                        "likes": 7,
                        "gated": false,
                        "private": false,
                        "cardData": {
                          "license": "apache-2.0"
                        },
                        "siblings": [
                          {"rfilename": ".gitattributes"},
                          {"rfilename": "models/model.gguf"},
                          {"rfilename": "models/model-00001-of-00002.gguf"}
                        ]
                      }
                    ]
                    """.trimIndent(),
                ),
        )
        server.start()
        try {
            val endpointAdapter = FixtureHuggingFaceEndpointAdapter(server.url("/"))
            val results = OkHttpHuggingFaceHubClient(endpointAdapter).searchFiles(
                query = "tiny",
                limit = 5,
            )

            val request = server.takeRequest()
            assertEquals("/api/models?search=tiny&limit=5&full=true", request.path)
            assertEquals(1, results.size)
            val result = results.single()
            assertEquals("owner/repo", result.reference.repoId)
            assertEquals("main", result.reference.revision)
            assertEquals("models/model.gguf", result.reference.filePath)
            assertEquals("owner/repo / model.gguf", result.displayName)
            assertEquals("https://huggingface.co/owner/repo/resolve/main/models/model.gguf", result.canonicalUrl)
            assertEquals(42L, result.downloads)
            assertEquals(7L, result.likes)
            assertEquals("apache-2.0", result.license)
            assertFalse(result.gated)
            assertFalse(result.private)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `blank search query does not call hub client`() = runTest {
        val acquisition = DefaultHuggingFaceModelAcquisition(
            hubClient = FakeHuggingFaceHubClient(
                searchFailure = AssertionError("search should not run"),
            ),
        )

        assertEquals(emptyList(), acquisition.searchFiles(query = "   "))
    }

    @Test
    fun `successful candidate materializes distribution version for existing download path`() = runTest {
        val acquisition = DefaultHuggingFaceModelAcquisition(
            hubClient = FakeHuggingFaceHubClient(
                metadata = HuggingFaceHubFileMetadata(
                    path = "model.gguf",
                    sizeBytes = 1234L,
                    lfsOid = "a".repeat(64),
                ),
                repositoryMetadata = HuggingFaceHubRepositoryMetadata(
                    modelCardUrl = "https://huggingface.co/owner/repo",
                    license = "mit",
                    licenseUrl = "https://huggingface.co/owner/repo/blob/main/LICENSE",
                ),
            ),
        )

        val candidate = acquisition.resolveCandidate(
            input = "https://huggingface.co/owner/repo/resolve/main/model.gguf",
            targetModelId = ModelCatalog.QWEN3_0_6B_Q4_K_M,
        )

        assertEquals(ModelCatalog.QWEN3_0_6B_Q4_K_M, candidate.version.modelId)
        assertEquals("hf-model-${"a".repeat(12)}", candidate.version.version)
        assertEquals(ModelSourceKind.HUGGING_FACE, candidate.version.sourceKind)
        assertEquals(SourceTrustPolicy.INTEGRITY_ONLY, candidate.version.sourceRef?.trustPolicy)
        assertEquals("owner/repo", candidate.version.sourceRef?.repository)
        assertEquals("main", candidate.version.sourceRef?.revision)
        assertEquals(candidate.reference.canonicalResolveUrl, candidate.version.downloadUrl)
        assertEquals("a".repeat(64), candidate.version.expectedSha256)
        assertEquals(1234L, candidate.version.fileSizeBytes)
        assertEquals(1, candidate.version.artifacts.size)
        assertEquals("https://huggingface.co/owner/repo", candidate.modelCardUrl)
        assertEquals("mit", candidate.license)
        assertEquals("https://huggingface.co/owner/repo/blob/main/LICENSE", candidate.licenseUrl)
    }

    @Test
    fun `repository metadata lookup failure does not block candidate materialization`() = runTest {
        val acquisition = DefaultHuggingFaceModelAcquisition(
            hubClient = FakeHuggingFaceHubClient(
                metadata = HuggingFaceHubFileMetadata(
                    path = "model.gguf",
                    sizeBytes = 1234L,
                    lfsOid = "a".repeat(64),
                ),
                repositoryFailure = IllegalStateException("metadata unavailable"),
            ),
        )

        val candidate = acquisition.resolveCandidate(
            input = "https://huggingface.co/owner/repo/resolve/main/model.gguf",
            targetModelId = ModelCatalog.QWEN3_0_6B_Q4_K_M,
        )

        assertEquals("https://huggingface.co/owner/repo", candidate.modelCardUrl)
        assertEquals(null, candidate.license)
        assertEquals(null, candidate.licenseUrl)
    }

    @Test
    fun `candidate validation blocks missing lfs sha and missing size`() = runTest {
        val missingSha = DefaultHuggingFaceModelAcquisition(
            hubClient = FakeHuggingFaceHubClient(
                metadata = HuggingFaceHubFileMetadata(path = "model.gguf", sizeBytes = 1L, lfsOid = null),
            ),
        )
        assertFailsWith<HuggingFaceAcquisitionException> {
            missingSha.resolveCandidate(
                input = "https://huggingface.co/owner/repo/resolve/main/model.gguf",
                targetModelId = ModelCatalog.QWEN3_0_6B_Q4_K_M,
            )
        }.also { error ->
            assertEquals(HuggingFaceAcquisitionBlockReason.MISSING_SHA, error.reason)
        }

        val missingSize = DefaultHuggingFaceModelAcquisition(
            hubClient = FakeHuggingFaceHubClient(
                metadata = HuggingFaceHubFileMetadata(path = "model.gguf", sizeBytes = null, lfsOid = "a".repeat(64)),
            ),
        )
        assertFailsWith<HuggingFaceAcquisitionException> {
            missingSize.resolveCandidate(
                input = "https://huggingface.co/owner/repo/resolve/main/model.gguf",
                targetModelId = ModelCatalog.QWEN3_0_6B_Q4_K_M,
            )
        }.also { error ->
            assertEquals(HuggingFaceAcquisitionBlockReason.MISSING_SIZE, error.reason)
        }
    }

    @Test
    fun `candidate validation blocks unsupported and companion-artifact targets`() = runTest {
        val acquisition = DefaultHuggingFaceModelAcquisition(
            hubClient = FakeHuggingFaceHubClient(
                metadata = HuggingFaceHubFileMetadata(path = "model.gguf", sizeBytes = 1L, lfsOid = "a".repeat(64)),
            ),
        )

        val targets = acquisition.supportedTargets().map { it.modelId }
        assertTrue(ModelCatalog.QWEN3_0_6B_Q4_K_M in targets)
        assertFalse(ModelCatalog.QWEN_3_5_0_8B_Q4 in targets)

        assertFailsWith<HuggingFaceAcquisitionException> {
            acquisition.resolveCandidate(
                input = "https://huggingface.co/owner/repo/resolve/main/model.gguf",
                targetModelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            )
        }.also { error ->
            assertEquals(HuggingFaceAcquisitionBlockReason.COMPANION_ARTIFACT_REQUIRED, error.reason)
            assertEquals(
                "This model needs extra companion files. PocketGPT only supports single-file text GGUF downloads here.",
                error.userMessage,
            )
        }
    }

    @Test
    fun `candidate validation preserves private gated block from client`() = runTest {
        val acquisition = DefaultHuggingFaceModelAcquisition(
            hubClient = FakeHuggingFaceHubClient(
                failure = HuggingFaceAcquisitionException(
                    reason = HuggingFaceAcquisitionBlockReason.ACCESS_DENIED,
                    userMessage = "private",
                ),
            ),
        )

        assertFailsWith<HuggingFaceAcquisitionException> {
            acquisition.resolveCandidate(
                input = "https://huggingface.co/owner/repo/resolve/main/model.gguf",
                targetModelId = ModelCatalog.QWEN3_0_6B_Q4_K_M,
            )
        }.also { error ->
            assertEquals(HuggingFaceAcquisitionBlockReason.ACCESS_DENIED, error.reason)
        }
    }
}

private class FakeHuggingFaceHubClient(
    private val metadata: HuggingFaceHubFileMetadata? = null,
    private val repositoryMetadata: HuggingFaceHubRepositoryMetadata? = null,
    private val failure: Throwable? = null,
    private val repositoryFailure: Throwable? = null,
    private val searchFailure: Throwable? = null,
) : HuggingFaceHubClient {
    override suspend fun lookupFile(reference: HuggingFaceModelReference): HuggingFaceHubFileMetadata {
        failure?.let { throw it }
        return requireNotNull(metadata)
    }

    override suspend fun lookupRepository(reference: HuggingFaceModelReference): HuggingFaceHubRepositoryMetadata? {
        repositoryFailure?.let { throw it }
        return repositoryMetadata
    }

    override suspend fun searchFiles(query: String, limit: Int): List<com.pocketagent.android.runtime.huggingface.HuggingFaceSearchFileResult> {
        searchFailure?.let { throw it }
        return emptyList()
    }
}
