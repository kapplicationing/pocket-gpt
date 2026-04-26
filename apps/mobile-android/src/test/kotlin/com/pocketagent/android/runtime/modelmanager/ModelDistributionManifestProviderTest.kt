package com.pocketagent.android.runtime.modelmanager

import com.pocketagent.android.runtime.RuntimeDomainError
import com.pocketagent.android.runtime.RuntimeDomainException
import com.pocketagent.android.runtime.RuntimeErrorCodes
import com.pocketagent.core.model.ModelArtifactRole
import com.pocketagent.core.model.ModelSourceKind
import com.pocketagent.inference.ModelCatalog
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelDistributionManifestProviderTest {
    @Test
    fun `load manifest falls back to bundled catalog when endpoint is empty`() = runTest {
        val provider = ModelDistributionManifestProvider(
            context = null,
            endpointOverride = "",
            bundledManifestLoader = { bundledManifestJson() },
        )

        val manifest = provider.loadManifest()

        assertEquals(ManifestSource.BUNDLED, manifest.source)
        assertEquals(2, manifest.models.size)
        assertNotNull(manifest.syncedAtEpochMs)
        assertEquals(null, manifest.lastError)
    }

    @Test
    fun `load manifest overlays bundled with remote catalog`() = runTest {
        val provider = ModelDistributionManifestProvider(
            context = null,
            endpointOverride = "https://example.test/catalog.json",
            bundledManifestLoader = { bundledManifestJson() },
            remoteManifestLoader = { remoteManifestJson() },
        )

        val manifest = provider.loadManifest()

        assertEquals(ManifestSource.BUNDLED_AND_REMOTE, manifest.source)
        val model0 = manifest.models.first { it.modelId == "qwen3.5-0.8b-q4" }
        val model2 = manifest.models.first { it.modelId == "qwen3-1.7b-q4_k_m" }
        assertTrue(model0.versions.any { it.version == "q4_1" })
        assertTrue(model2.versions.any { it.version == "q4_k_m" })
        assertEquals(null, manifest.lastError)
    }

    @Test
    fun `load manifest sorts numeric version tokens semantically`() = runTest {
        val provider = ModelDistributionManifestProvider(
            context = null,
            endpointOverride = "",
            bundledManifestLoader = { manifestWithMixedSemanticVersionsJson() },
        )

        val manifest = provider.loadManifest()

        val versions = manifest.models.single().versions.map { it.version }
        assertEquals(listOf("q4_10", "q4_9", "q4_2"), versions)
    }

    @Test
    fun `merge manifest keeps semantic version ordering after overlay`() = runTest {
        val provider = ModelDistributionManifestProvider(
            context = null,
            endpointOverride = "https://example.test/catalog.json",
            bundledManifestLoader = { manifestWithBundledVersionOrderingJson() },
            remoteManifestLoader = { manifestWithRemoteVersionOrderingJson() },
        )

        val manifest = provider.loadManifest()

        val versions = manifest.models.single().versions.map { it.version }
        assertEquals(listOf("q4_10", "q4_2"), versions)
    }

    @Test
    fun `load manifest keeps bundled catalog and warning when remote refresh fails`() = runTest {
        val provider = ModelDistributionManifestProvider(
            context = null,
            endpointOverride = "https://example.test/catalog.json",
            bundledManifestLoader = { bundledManifestJson() },
            remoteManifestLoader = { error("network down") },
        )

        val manifest = provider.loadManifest()

        assertEquals(ManifestSource.BUNDLED, manifest.source)
        assertTrue(manifest.models.isNotEmpty())
        assertTrue(manifest.lastError?.contains("network down") == true)
    }

    @Test
    fun `load manifest drops invalid catalog entries and reports warning`() = runTest {
        val provider = ModelDistributionManifestProvider(
            context = null,
            endpointOverride = "",
            bundledManifestLoader = { manifestWithInvalidEntriesJson() },
        )

        val manifest = provider.loadManifest()

        assertEquals(ManifestSource.BUNDLED, manifest.source)
        assertEquals(1, manifest.models.size)
        val model = manifest.models.single()
        assertEquals("qwen3.5-0.8b-q4", model.modelId)
        assertEquals(1, model.versions.size)
        assertTrue(manifest.lastError?.contains("Dropped") == true)
    }

    @Test
    fun `load manifest accepts version when provenance fields are omitted`() = runTest {
        val provider = ModelDistributionManifestProvider(
            context = null,
            endpointOverride = "",
            bundledManifestLoader = { manifestWithoutProvenanceJson() },
        )

        val manifest = provider.loadManifest()

        assertEquals(ManifestSource.BUNDLED, manifest.source)
        assertEquals(1, manifest.models.size)
        val version = manifest.models.single().versions.single()
        assertEquals("", version.provenanceIssuer)
        assertEquals("", version.provenanceSignature)
        assertEquals(null, manifest.lastError)
    }

    @Test
    fun `load manifest deduplicates repeated model-version entries`() = runTest {
        val provider = ModelDistributionManifestProvider(
            context = null,
            endpointOverride = "",
            bundledManifestLoader = { manifestWithDuplicateEntriesJson() },
        )

        val manifest = provider.loadManifest()

        assertEquals(ManifestSource.BUNDLED, manifest.source)
        assertEquals(1, manifest.models.size)
        val model = manifest.models.single()
        assertEquals("qwen3.5-0.8b-q4", model.modelId)
        assertEquals(1, model.versions.size)
        assertTrue(manifest.lastError?.contains("duplicate") == true)
    }

    @Test
    fun `load manifest parses provenance strict verification policy`() = runTest {
        val provider = ModelDistributionManifestProvider(
            context = null,
            endpointOverride = "",
            bundledManifestLoader = { manifestWithStrictVerificationPolicyJson() },
        )

        val manifest = provider.loadManifest()

        val version = manifest.models.single().versions.single()
        assertEquals(DownloadVerificationPolicy.PROVENANCE_STRICT, version.verificationPolicy)
        assertEquals(null, manifest.lastError)
    }

    @Test
    fun `load manifest falls back to integrity policy when verification policy is invalid`() = runTest {
        val provider = ModelDistributionManifestProvider(
            context = null,
            endpointOverride = "",
            bundledManifestLoader = { manifestWithInvalidVerificationPolicyJson() },
        )

        val manifest = provider.loadManifest()

        val version = manifest.models.single().versions.single()
        assertEquals(DownloadVerificationPolicy.INTEGRITY_ONLY, version.verificationPolicy)
        assertTrue(manifest.lastError?.contains("invalid verificationPolicy") == true)
    }

    @Test
    fun `bundled catalog unavailable returns deterministic error code`() = runTest {
        val provider = ModelDistributionManifestProvider(
            context = null,
            endpointOverride = "",
            bundledManifestLoader = null,
        )

        val manifest = provider.loadManifest()

        assertTrue(manifest.models.isEmpty())
        assertTrue(manifest.lastError?.startsWith("MODEL_MANIFEST_BUNDLED_UNAVAILABLE:") == true)
    }

    @Test
    fun `remote refresh warning returns deterministic error code`() = runTest {
        val provider = ModelDistributionManifestProvider(
            context = null,
            endpointOverride = "https://example.test/catalog.json",
            bundledManifestLoader = { bundledManifestJson() },
            remoteManifestLoader = { error("network down") },
        )

        val manifest = provider.loadManifest()

        assertTrue(manifest.lastError?.contains("MODEL_MANIFEST_REMOTE_FETCH_FAILED:") == true)
    }

    @Test
    fun `remote manifest runtime domain error keeps stable machine code`() = runTest {
        val provider = ModelDistributionManifestProvider(
            context = null,
            endpointOverride = "https://example.test/catalog.json",
            bundledManifestLoader = { bundledManifestJson() },
            remoteManifestLoader = {
                throw RuntimeDomainException(
                    domainError = RuntimeDomainError(
                        code = RuntimeErrorCodes.MODEL_MANIFEST_HTTP_ERROR,
                        userMessage = "Model catalog refresh failed. Falling back to bundled catalog.",
                        technicalDetail = "endpoint=https://example.test/catalog.json;http=503",
                    ),
                )
            },
        )

        val manifest = provider.loadManifest()

        assertTrue(manifest.lastError?.contains(RuntimeErrorCodes.MODEL_MANIFEST_HTTP_ERROR) == true)
    }

    @Test
    fun `load manifest parses bundle artifacts and source kind`() = runTest {
        val provider = ModelDistributionManifestProvider(
            context = null,
            endpointOverride = "",
            bundledManifestLoader = { manifestWithArtifactBundleJson() },
        )

        val manifest = provider.loadManifest()

        val version = manifest.models.single().versions.single()
        assertEquals(ModelSourceKind.HUGGING_FACE, version.sourceKind)
        assertEquals("hf-chatml", version.promptProfileId)
        assertEquals(2, version.artifacts.size)
        assertEquals(ModelArtifactRole.PRIMARY_GGUF, version.artifacts.first().role)
        assertEquals(ModelArtifactRole.MMPROJ, version.artifacts.last().role)
    }

    @Test
    fun `load manifest drops invalid optional artifact and keeps valid version`() = runTest {
        val provider = ModelDistributionManifestProvider(
            context = null,
            endpointOverride = "",
            bundledManifestLoader = { manifestWithInvalidOptionalArtifactJson() },
        )

        val manifest = provider.loadManifest()

        val version = manifest.models.single().versions.single()
        assertEquals(1, version.artifacts.size)
        assertEquals(ModelArtifactRole.PRIMARY_GGUF, version.artifacts.single().role)
        assertTrue(manifest.lastError?.contains("Dropped optional artifact") == true)
    }

    @Test
    fun `load manifest drops bundle version when no primary artifact remains`() = runTest {
        val provider = ModelDistributionManifestProvider(
            context = null,
            endpointOverride = "",
            bundledManifestLoader = { manifestWithoutPrimaryArtifactJson() },
        )

        val manifest = provider.loadManifest()

        assertTrue(manifest.models.isEmpty())
        assertTrue(manifest.lastError?.contains("Dropped") == true)
    }

    @Test
    fun `bundled asset provisions required mmproj for launch vision variants`() = runTest {
        val provider = ModelDistributionManifestProvider(
            context = null,
            endpointOverride = "",
            bundledManifestLoader = { bundledAssetManifestJson() },
        )

        val manifest = provider.loadManifest()
        val versionById = manifest.models
            .first { it.modelId == ModelCatalog.QWEN_3_5_0_8B_Q4 }
            .versions
            .associateBy { it.version }
        val expectedMmProjFileName = ModelCatalog.mmProjFileNameFor(ModelCatalog.QWEN_3_5_0_8B_Q4)

        listOf("q4_0", "ud_iq2_xxs").forEach { version ->
            val artifacts = versionById.getValue(version).artifacts
            assertTrue(
                artifacts.any { artifact ->
                    artifact.role == ModelArtifactRole.MMPROJ &&
                        artifact.required &&
                        artifact.fileName == expectedMmProjFileName
                },
                "Expected $version to carry a required MMPROJ artifact.",
            )
            assertTrue(
                artifacts.any { artifact ->
                    artifact.role == ModelArtifactRole.PRIMARY_GGUF &&
                        artifact.verificationPolicy == DownloadVerificationPolicy.PROVENANCE_STRICT
                },
                "Expected $version to preserve strict verification on the primary GGUF.",
            )
        }
    }
}

private fun bundledAssetManifestJson(): String {
    val repoRoot = findRepoRoot()
    val catalogPath = repoRoot.resolve("apps/mobile-android/src/main/assets/model-distribution-catalog.json")
    return catalogPath.toFile().readText()
}

private fun findRepoRoot(): Path {
    var current: Path? = Path.of("").toAbsolutePath().normalize()
    while (current != null) {
        if (Files.exists(current.resolve("settings.gradle.kts"))) {
            return current
        }
        current = current.parent
    }
    error("Could not locate pocket-gpt repo root.")
}

private fun bundledManifestJson(): String {
    return """
        {
          "models": [
            {
              "modelId": "qwen3.5-0.8b-q4",
              "displayName": "Qwen 3.5 0.8B (Q4)",
              "versions": [
                {
                  "version": "q4_k_m",
                  "downloadUrl": "https://example.test/qwen-0.8b-q4_0.gguf",
                  "expectedSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "provenanceIssuer": "internal",
                  "provenanceSignature": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "runtimeCompatibility": "android-arm64-v8a",
                  "fileSizeBytes": 111
                }
              ]
            },
            {
              "modelId": "qwen3-1.7b-q4_k_m",
              "displayName": "Qwen3 1.7B Instruct (Q4_K_M)",
              "versions": [
                {
                  "version": "q4_k_m",
                  "downloadUrl": "https://example.test/qwen3-1.7b-q4_k_m.gguf",
                  "expectedSha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "provenanceIssuer": "internal",
                  "provenanceSignature": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "runtimeCompatibility": "android-arm64-v8a",
                  "fileSizeBytes": 222
                }
              ]
            }
          ]
        }
    """.trimIndent()
}

private fun remoteManifestJson(): String {
    return """
        {
          "models": [
            {
              "modelId": "qwen3.5-0.8b-q4",
              "displayName": "Qwen 3.5 0.8B (Q4)",
              "versions": [
                {
                  "version": "q4_1",
                  "downloadUrl": "https://example.test/qwen-0.8b-q4_1.gguf",
                  "expectedSha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                  "provenanceIssuer": "internal",
                  "provenanceSignature": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                  "runtimeCompatibility": "android-arm64-v8a",
                  "fileSizeBytes": 333
                }
              ]
            }
          ]
        }
    """.trimIndent()
}

private fun manifestWithInvalidEntriesJson(): String {
    return """
        {
          "models": [
            {
              "modelId": "qwen3.5-0.8b-q4",
              "displayName": "Qwen 3.5 0.8B (Q4)",
              "versions": [
                {
                  "version": "q4_valid",
                  "downloadUrl": "https://example.test/valid.gguf",
                  "expectedSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "runtimeCompatibility": "android-arm64-v8a",
                  "fileSizeBytes": 111
                },
                {
                  "version": "q4_bad_url",
                  "downloadUrl": "http://example.test/invalid.gguf",
                  "expectedSha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "runtimeCompatibility": "android-arm64-v8a",
                  "fileSizeBytes": 111
                },
                {
                  "version": "q4_bad_sha",
                  "downloadUrl": "https://example.test/invalid2.gguf",
                  "expectedSha256": "not-a-sha",
                  "runtimeCompatibility": "android-arm64-v8a",
                  "fileSizeBytes": 111
                }
              ]
            },
            {
              "modelId": "qwen3-1.7b-q4_k_m",
              "displayName": "Qwen3 1.7B Instruct (Q4_K_M)",
              "versions": [
                {
                  "version": "q4_missing_size",
                  "downloadUrl": "https://example.test/missing-size.gguf",
                  "expectedSha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                  "runtimeCompatibility": "android-arm64-v8a"
                }
              ]
            }
          ]
        }
    """.trimIndent()
}

private fun manifestWithoutProvenanceJson(): String {
    return """
        {
          "models": [
            {
              "modelId": "qwen3.5-0.8b-q4",
              "displayName": "Qwen 3.5 0.8B (Q4)",
              "versions": [
                {
                  "version": "q4_k_m",
                  "downloadUrl": "https://example.test/qwen-0.8b-q4_0.gguf",
                  "expectedSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "runtimeCompatibility": "android-arm64-v8a",
                  "fileSizeBytes": 1234
                }
              ]
            }
          ]
        }
    """.trimIndent()
}

private fun manifestWithDuplicateEntriesJson(): String {
    return """
        {
          "models": [
            {
              "modelId": "qwen3.5-0.8b-q4",
              "displayName": "Qwen 3.5 0.8B (Q4)",
              "versions": [
                {
                  "version": "q4_k_m",
                  "downloadUrl": "https://example.test/dup-a.gguf",
                  "expectedSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "runtimeCompatibility": "android-arm64-v8a",
                  "fileSizeBytes": 1234
                }
              ]
            },
            {
              "modelId": "qwen3.5-0.8b-q4",
              "displayName": "Qwen 3.5 0.8B Duplicate Name",
              "versions": [
                {
                  "version": "q4_k_m",
                  "downloadUrl": "https://example.test/dup-b.gguf",
                  "expectedSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "runtimeCompatibility": "android-arm64-v8a",
                  "fileSizeBytes": 1234
                }
              ]
            }
          ]
        }
    """.trimIndent()
}

private fun manifestWithStrictVerificationPolicyJson(): String {
    return """
        {
          "models": [
            {
              "modelId": "qwen3.5-0.8b-q4",
              "displayName": "Qwen 3.5 0.8B (Q4)",
              "versions": [
                {
                  "version": "q4_k_m",
                  "downloadUrl": "https://example.test/strict.gguf",
                  "expectedSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "provenanceIssuer": "internal",
                  "provenanceSignature": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "verificationPolicy": "PROVENANCE_STRICT",
                  "runtimeCompatibility": "android-arm64-v8a",
                  "fileSizeBytes": 1234
                }
              ]
            }
          ]
        }
    """.trimIndent()
}

private fun manifestWithInvalidVerificationPolicyJson(): String {
    return """
        {
          "models": [
            {
              "modelId": "qwen3.5-0.8b-q4",
              "displayName": "Qwen 3.5 0.8B (Q4)",
              "versions": [
                {
                  "version": "q4_k_m",
                  "downloadUrl": "https://example.test/invalid-policy.gguf",
                  "expectedSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "provenanceIssuer": "internal",
                  "provenanceSignature": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "verificationPolicy": "STRICT_UNKNOWN",
                  "runtimeCompatibility": "android-arm64-v8a",
                  "fileSizeBytes": 1234
                }
              ]
            }
          ]
        }
    """.trimIndent()
}

private fun manifestWithMixedSemanticVersionsJson(): String {
    return """
        {
          "models": [
            {
              "modelId": "qwen3.5-0.8b-q4",
              "displayName": "Qwen 3.5 0.8B (Q4)",
              "versions": [
                {
                  "version": "q4_2",
                  "downloadUrl": "https://example.test/q4_2.gguf",
                  "expectedSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "runtimeCompatibility": "android-arm64-v8a",
                  "fileSizeBytes": 1234
                },
                {
                  "version": "q4_10",
                  "downloadUrl": "https://example.test/q4_10.gguf",
                  "expectedSha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "runtimeCompatibility": "android-arm64-v8a",
                  "fileSizeBytes": 1234
                },
                {
                  "version": "q4_9",
                  "downloadUrl": "https://example.test/q4_9.gguf",
                  "expectedSha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                  "runtimeCompatibility": "android-arm64-v8a",
                  "fileSizeBytes": 1234
                }
              ]
            }
          ]
        }
    """.trimIndent()
}

private fun manifestWithBundledVersionOrderingJson(): String {
    return """
        {
          "models": [
            {
              "modelId": "qwen3.5-0.8b-q4",
              "displayName": "Qwen 3.5 0.8B (Q4)",
              "versions": [
                {
                  "version": "q4_2",
                  "downloadUrl": "https://example.test/q4_2.gguf",
                  "expectedSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "runtimeCompatibility": "android-arm64-v8a",
                  "fileSizeBytes": 1234
                }
              ]
            }
          ]
        }
    """.trimIndent()
}

private fun manifestWithRemoteVersionOrderingJson(): String {
    return """
        {
          "models": [
            {
              "modelId": "qwen3.5-0.8b-q4",
              "displayName": "Qwen 3.5 0.8B (Q4)",
              "versions": [
                {
                  "version": "q4_10",
                  "downloadUrl": "https://example.test/q4_10.gguf",
                  "expectedSha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "runtimeCompatibility": "android-arm64-v8a",
                  "fileSizeBytes": 1234
                }
              ]
            }
          ]
        }
    """.trimIndent()
}

private fun manifestWithArtifactBundleJson(): String {
    return """
        {
          "models": [
            {
              "modelId": "qwen3.5-0.8b-q4",
              "displayName": "Qwen 3.5 0.8B (Q4)",
              "versions": [
                {
                  "version": "q4_k_m",
                  "sourceKind": "HUGGING_FACE",
                  "promptProfileId": "hf-chatml",
                  "artifacts": [
                    {
                      "artifactId": "primary",
                      "role": "PRIMARY_GGUF",
                      "fileName": "Qwen3.5-0.8B-Q4_0.gguf",
                      "downloadUrl": "https://example.test/qwen.gguf",
                      "expectedSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                      "runtimeCompatibility": "android-arm64-v8a",
                      "fileSizeBytes": 111
                    },
                    {
                      "artifactId": "mmproj",
                      "role": "MMPROJ",
                      "fileName": "qwen-mmproj.gguf",
                      "downloadUrl": "https://example.test/qwen-mmproj.gguf",
                      "expectedSha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                      "runtimeCompatibility": "android-arm64-v8a",
                      "fileSizeBytes": 22,
                      "required": true
                    }
                  ]
                }
              ]
            }
          ]
        }
    """.trimIndent()
}

private fun manifestWithInvalidOptionalArtifactJson(): String {
    return """
        {
          "models": [
            {
              "modelId": "bundle-opt-test",
              "displayName": "Bundle Optional Test",
              "versions": [
                {
                  "version": "q4_k_m",
                  "sourceKind": "HUGGING_FACE",
                  "downloadUrl": "https://example.test/bundle-no-primary-fallback.gguf",
                  "expectedSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "runtimeCompatibility": "android-arm64-v8a",
                  "fileSizeBytes": 111,
                  "artifacts": [
                    {
                      "artifactId": "primary",
                      "role": "PRIMARY_GGUF",
                      "fileName": "bundle-opt-test.gguf",
                      "downloadUrl": "https://example.test/bundle-opt-test.gguf",
                      "expectedSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                      "runtimeCompatibility": "android-arm64-v8a",
                      "fileSizeBytes": 111
                    },
                    {
                      "artifactId": "mmproj",
                      "role": "MMPROJ",
                      "fileName": "bundle-opt-test-mmproj.gguf",
                      "downloadUrl": "http://example.test/bundle-opt-test-mmproj.gguf",
                      "expectedSha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                      "runtimeCompatibility": "android-arm64-v8a",
                      "fileSizeBytes": 22,
                      "required": false
                    }
                  ]
                }
              ]
            }
          ]
        }
    """.trimIndent()
}

private fun manifestWithoutPrimaryArtifactJson(): String {
    return """
        {
          "models": [
            {
              "modelId": "bundle-no-primary-test",
              "displayName": "Bundle No Primary Test",
              "versions": [
                {
                  "version": "q4_k_m",
                  "sourceKind": "HUGGING_FACE",
                  "artifacts": [
                    {
                      "artifactId": "mmproj",
                      "role": "MMPROJ",
                      "fileName": "bundle-no-primary-mmproj.gguf",
                      "downloadUrl": "https://example.test/bundle-no-primary-mmproj.gguf",
                      "expectedSha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                      "runtimeCompatibility": "android-arm64-v8a",
                      "fileSizeBytes": 22,
                      "required": true
                    }
                  ]
                }
              ]
            }
          ]
        }
    """.trimIndent()
}
