package com.pocketagent.android.runtime

import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidRuntimeProvisioningStoreSignalsTest {
    @Test
    fun `staleActiveVersionSignal returns signal when active version is missing`() {
        val signal = staleActiveVersionSignal(
            modelId = "qwen3.5-0.8b-q4",
            activeVersion = "q4_missing",
            installedVersions = listOf(
                descriptor(version = "q4_0"),
                descriptor(version = "q4_1"),
            ),
        )

        assertEquals("MODEL_ACTIVE_VERSION_STALE", signal?.code)
    }

    @Test
    fun `staleActiveVersionSignal returns null when active version resolves`() {
        val signal = staleActiveVersionSignal(
            modelId = "qwen3.5-0.8b-q4",
            activeVersion = "q4_0",
            installedVersions = listOf(
                descriptor(version = "q4_0"),
            ),
        )

        assertNull(signal)
    }

    @Test
    fun `active selection chooses newest loadable descriptor when active pointer is missing`() {
        val older = loadableFile(prefix = "older")
        val newer = loadableFile(prefix = "newer")

        val selected = selectRuntimeConfigDescriptor(
            activeVersion = null,
            versions = listOf(
                descriptor(version = "older", absolutePath = older.absolutePath, importedAtEpochMs = 1L),
                descriptor(version = "newer", absolutePath = newer.absolutePath, importedAtEpochMs = 2L),
            ),
        )

        assertEquals("newer", selected?.version)
    }

    @Test
    fun `active selection replaces stale active descriptor file with loadable installed descriptor`() {
        val loadable = loadableFile(prefix = "replacement")

        val selected = selectRuntimeConfigDescriptor(
            activeVersion = "stale",
            versions = listOf(
                descriptor(version = "stale", absolutePath = "/tmp/missing-stale-model.gguf", importedAtEpochMs = 2L),
                descriptor(version = "replacement", absolutePath = loadable.absolutePath, importedAtEpochMs = 1L),
            ),
        )

        assertEquals("replacement", selected?.version)
    }

    @Test
    fun `active selection does not select incomplete provenance or incompatible runtime`() {
        val incomplete = loadableFile(prefix = "incomplete")
        val incompatible = loadableFile(prefix = "incompatible")

        val selected = selectRuntimeConfigDescriptor(
            activeVersion = null,
            versions = listOf(
                descriptor(version = "incomplete", absolutePath = incomplete.absolutePath, provenanceSignature = ""),
                descriptor(
                    version = "incompatible",
                    absolutePath = incompatible.absolutePath,
                    runtimeCompatibility = "android-arm64-v9a",
                ),
            ),
        )

        assertNull(selected)
    }

    @Test
    fun `stored active selection chooses newest loadable entry when active pointer is missing`() {
        val older = loadableFile(prefix = "stored-older")
        val newer = loadableFile(prefix = "stored-newer")

        val selected = selectRuntimeConfigEntry(
            activeVersion = null,
            entries = listOf(
                storedEntry(version = "older", absolutePath = older.absolutePath, importedAtEpochMs = 1L),
                storedEntry(version = "newer", absolutePath = newer.absolutePath, importedAtEpochMs = 2L),
            ),
        )

        assertEquals("newer", selected?.version)
    }

    private fun descriptor(
        version: String,
        absolutePath: String = "/tmp/$version.gguf",
        provenanceSignature: String = "sig",
        runtimeCompatibility: String = PROVISIONING_RUNTIME_COMPATIBILITY_TAG,
        importedAtEpochMs: Long = 1L,
    ): ModelVersionDescriptor {
        return ModelVersionDescriptor(
            modelId = "qwen3.5-0.8b-q4",
            version = version,
            displayName = "Qwen 3.5 0.8B",
            absolutePath = absolutePath,
            sha256 = "a".repeat(64),
            provenanceIssuer = "issuer",
            provenanceSignature = provenanceSignature,
            runtimeCompatibility = runtimeCompatibility,
            fileSizeBytes = 1L,
            importedAtEpochMs = importedAtEpochMs,
            isActive = false,
        )
    }

    private fun storedEntry(
        version: String,
        absolutePath: String,
        importedAtEpochMs: Long,
    ): StoredVersionEntry {
        return StoredVersionEntry(
            version = version,
            absolutePath = absolutePath,
            sha256 = "a".repeat(64),
            provenanceIssuer = "issuer",
            provenanceSignature = "sig",
            runtimeCompatibility = PROVISIONING_RUNTIME_COMPATIBILITY_TAG,
            fileSizeBytes = 1L,
            importedAtEpochMs = importedAtEpochMs,
        )
    }

    private fun loadableFile(prefix: String): File {
        return File.createTempFile(prefix, ".gguf").apply {
            writeText("model")
            deleteOnExit()
        }
    }
}
