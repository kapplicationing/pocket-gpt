package com.pocketagent.android.runtime

import android.content.ContentUris
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketagent.inference.ModelCatalog
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidRuntimeProvisioningStoreInstrumentationTest {
    private lateinit var appContext: android.content.Context
    private lateinit var store: AndroidRuntimeProvisioningStore

    @Before
    fun setUp() {
        appContext = InstrumentationRegistry.getInstrumentation().targetContext
        resetProvisioningStorage()
        store = AndroidRuntimeProvisioningStore(appContext)
    }

    @After
    fun tearDown() {
        resetProvisioningStorage()
    }

    @Test
    fun seedingSamePathReplacesPreviousVersionEntry() = runBlocking {
        val source = writeTempFile(
            dir = appContext.cacheDir,
            fileName = "seed-same-path.gguf",
            content = "same-path-seed-content",
        )
        store.seedModelFromAbsolutePath(
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            absolutePath = source.absolutePath,
            version = "seed-v1",
        )
        val latest = store.seedModelFromAbsolutePath(
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            absolutePath = source.absolutePath,
            version = "seed-v2",
        )

        val versions = store.listInstalledVersions(ModelCatalog.QWEN_3_5_0_8B_Q4)
        val normalizedSourcePath = normalizePath(source.absolutePath)
        assertEquals(
            "Expected one canonical entry for a repeatedly seeded source path.",
            1,
            versions.count { normalizePath(it.absolutePath) == normalizedSourcePath },
        )
        assertEquals("seed-v2", latest.version)
        assertEquals("seed-v2", versions.single().version)
    }

    @Test
    fun managedModelDirectoryLandsInExternalAppStorage() {
        val externalRoot = appContext.getExternalFilesDir(null)
            ?: throw AssertionError("External files dir is unavailable.")
        val managedDir = store.managedModelDirectory()

        assertTrue(
            "Managed model storage should resolve under app-specific external storage.",
            normalizePath(managedDir.absolutePath)
                .startsWith(normalizePath(externalRoot.absolutePath)),
        )
    }

    @Test
    fun importModelResolvesVisionMmprojFromAppDownloadsDirectory() = runBlocking {
        val source = writeTempFile(
            dir = appContext.cacheDir,
            fileName = "vision-import-source.gguf",
            content = "vision-import-source-content",
        )
        val mmProjFileName = ModelCatalog.mmProjFileNameFor(ModelCatalog.QWEN_3_5_0_8B_Q4)
            ?: throw AssertionError("Expected mmproj filename for vision model.")
        val downloadsDir = File("/sdcard/Download/${appContext.packageName}/models").apply { mkdirs() }
        val mmProj = writeTempFile(
            dir = downloadsDir,
            fileName = mmProjFileName,
            content = "vision-mmproj-content",
        )

        store.importModel(
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            sourceUri = android.net.Uri.fromFile(source),
            version = "vision-import-v1",
        )

        assertEquals(
            normalizePath(mmProj.absolutePath),
            normalizePath(store.resolveMmProjPath(ModelCatalog.QWEN_3_5_0_8B_Q4).orEmpty()),
        )
        assertEquals(
            normalizePath(mmProj.absolutePath),
            normalizePath(
                store.listInstalledVersions(ModelCatalog.QWEN_3_5_0_8B_Q4)
                    .single()
                    .artifacts
                    .first { artifact -> artifact.role == com.pocketagent.core.model.ModelArtifactRole.MMPROJ }
                    .absolutePath
                    .orEmpty(),
            ),
        )
    }

    @Test
    fun removeInactiveSeededVersionDoesNotDeleteExternalSourcePath() = runBlocking {
        val oldSource = writeTempFile(
            dir = appContext.cacheDir,
            fileName = "seed-old.gguf",
            content = "old-seed",
        )
        val newSource = writeTempFile(
            dir = appContext.cacheDir,
            fileName = "seed-new.gguf",
            content = "new-seed",
        )

        store.seedModelFromAbsolutePath(
            modelId = ModelCatalog.QWEN3_1_7B_Q4_K_M,
            absolutePath = oldSource.absolutePath,
            version = "seed-old",
        )
        store.seedModelFromAbsolutePath(
            modelId = ModelCatalog.QWEN3_1_7B_Q4_K_M,
            absolutePath = newSource.absolutePath,
            version = "seed-new",
        )

        val versionsBefore = store.listInstalledVersions(ModelCatalog.QWEN3_1_7B_Q4_K_M)
        val inactive = versionsBefore.first { !it.isActive }
        assertTrue(
            "Expected inactive seeded source path to be removed from metadata.",
            store.removeVersion(ModelCatalog.QWEN3_1_7B_Q4_K_M, inactive.version),
        )

        val versionsAfter = store.listInstalledVersions(ModelCatalog.QWEN3_1_7B_Q4_K_M)
        assertEquals(1, versionsAfter.size)
        assertTrue(
            "Seeded source paths should not be deleted by removeVersion.",
            oldSource.exists(),
        )
        assertTrue(newSource.exists())
    }

    @Test
    fun removeInactiveManagedVersionDeletesOrphanedManagedFile() {
        val managedDir = store.managedModelDirectory().apply { mkdirs() }
        val oldManaged = writeTempFile(
            dir = managedDir,
            fileName = "managed-old.gguf",
            content = "managed-old",
        )
        val newManaged = writeTempFile(
            dir = managedDir,
            fileName = "managed-new.gguf",
            content = "managed-new",
        )

        store.installDownloadedModel(
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            version = "managed-old",
            absolutePath = oldManaged.absolutePath,
            sha256 = sha256Hex(oldManaged),
            provenanceIssuer = "internal-release",
            provenanceSignature = "",
            runtimeCompatibility = store.expectedRuntimeCompatibilityTag(),
            fileSizeBytes = oldManaged.length(),
            makeActive = true,
        )
        store.installDownloadedModel(
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            version = "managed-new",
            absolutePath = newManaged.absolutePath,
            sha256 = sha256Hex(newManaged),
            provenanceIssuer = "internal-release",
            provenanceSignature = "",
            runtimeCompatibility = store.expectedRuntimeCompatibilityTag(),
            fileSizeBytes = newManaged.length(),
            makeActive = true,
        )

        assertTrue(store.removeVersion(ModelCatalog.QWEN_3_5_0_8B_Q4, "managed-old"))
        assertFalse(
            "Orphaned managed model files should be deleted when removed from installed versions.",
            oldManaged.exists(),
        )
        assertTrue(newManaged.exists())
    }

    @Test
    fun installDownloadedModelMirrorsManagedArtifactIntoDownloadsFolder() {
        val managedDir = store.managedModelDirectory().apply { mkdirs() }
        val source = writeTempFile(
            dir = managedDir,
            fileName = "download-mirror-source.gguf",
            content = "download-mirror-source-content",
        )
        val version = "downloads-mirror-v1"
        val installed = store.installDownloadedModel(
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            version = version,
            absolutePath = source.absolutePath,
            sha256 = sha256Hex(source),
            provenanceIssuer = "internal-release",
            provenanceSignature = "",
            runtimeCompatibility = store.expectedRuntimeCompatibilityTag(),
            fileSizeBytes = source.length(),
            makeActive = false,
        )

        val expectedDisplayName = "qwen3.5-0.8b-q4-$version.gguf"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mirroredUri = findMirroredDownloadsUri(expectedDisplayName)
            assertNotNull("Expected mirrored model entry in MediaStore Downloads.", mirroredUri)
            val mirroredBytes = appContext.contentResolver.openInputStream(mirroredUri!!)?.use { stream ->
                stream.readBytes()
            }
            assertNotNull("Expected mirrored model bytes to be readable from Downloads.", mirroredBytes)
            val installedBytes = File(installed.absolutePath).readBytes()
            assertTrue(
                "Expected mirrored Downloads model bytes to match installed model bytes.",
                installedBytes.contentEquals(mirroredBytes),
            )
        } else {
            @Suppress("DEPRECATION")
            val downloadsRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val mirroredFile = File(downloadsRoot, "${appContext.packageName}/models/$expectedDisplayName")
            assertTrue(
                "Expected mirrored model file in public Downloads path.",
                mirroredFile.exists() && mirroredFile.isFile,
            )
        }
    }

    @Test
    fun persistedManagedModelsAreDiscoveredAfterPreferencesReset() = runBlocking {
        val source = writeTempFile(
            dir = appContext.cacheDir,
            fileName = "persisted-discovery-source.gguf",
            content = "persisted-model-content",
        )
        val sourceUri = android.net.Uri.fromFile(source)

        val imported = store.importModel(
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            sourceUri = sourceUri,
            version = "persisted-v1",
        )
        assertTrue(File(imported.absolutePath).exists())

        appContext.getSharedPreferences("pocketagent_runtime_models", 0).edit().clear().commit()
        val recoveredStore = AndroidRuntimeProvisioningStore(appContext)
        val recoveredSnapshot = recoveredStore.snapshot()
        val recoveredModel = recoveredSnapshot.models.first { it.modelId == ModelCatalog.QWEN_3_5_0_8B_Q4 }

        assertTrue("Expected persisted model to be rediscovered after metadata reset.", recoveredModel.isProvisioned)
        assertTrue(
            "Expected recovered installed versions to include the imported version.",
            recoveredModel.installedVersions.any { it.version == "persisted-v1" },
        )
        assertEquals("persisted-v1", recoveredModel.activeVersion)
    }

    @Test
    fun persistedDownloadWorkspaceModelsAreDiscoveredAfterPreferencesReset() {
        val fallbackDir = store.managedDownloadWorkspaceDirectory().apply { mkdirs() }
        val fallbackModel = writeTempFile(
            dir = fallbackDir,
            fileName = "qwen3.5-0.8b-q4.gguf",
            content = "persisted-download-workspace-content",
        )
        val expectedPath = normalizePath(fallbackModel.absolutePath)

        appContext.getSharedPreferences("pocketagent_runtime_models", 0).edit().clear().commit()
        val recoveredStore = AndroidRuntimeProvisioningStore(appContext)
        val recoveredSnapshot = recoveredStore.snapshot()
        val recoveredModel = recoveredSnapshot.models.first { it.modelId == ModelCatalog.QWEN_3_5_0_8B_Q4 }

        assertTrue(
            "Expected download workspace model to be rediscovered after metadata reset.",
            recoveredModel.isProvisioned,
        )
        assertEquals("discovered", recoveredModel.activeVersion)
        assertEquals(expectedPath, normalizePath(recoveredModel.absolutePath.orEmpty()))
    }

    @Test
    fun persistedDynamicManagedModelsAreDiscoveredAfterPreferencesReset() = runBlocking {
        val source = writeTempFile(
            dir = appContext.cacheDir,
            fileName = "persisted-dynamic-discovery-source.gguf",
            content = "persisted-dynamic-model-content",
        )
        val sourceUri = android.net.Uri.fromFile(source)
        val modelId = ModelCatalog.QWEN3_1_7B_Q4_K_M

        val imported = store.importModel(
            modelId = modelId,
            sourceUri = sourceUri,
            version = "persisted-dynamic-v1",
        )
        assertTrue(File(imported.absolutePath).exists())

        appContext.getSharedPreferences("pocketagent_runtime_models", 0).edit().clear().commit()
        val recoveredStore = AndroidRuntimeProvisioningStore(appContext)
        val recoveredSnapshot = recoveredStore.snapshot()
        val recoveredModel = recoveredSnapshot.models.firstOrNull { it.modelId == modelId }

        assertNotNull("Expected recovered snapshot to include dynamic model id.", recoveredModel)
        assertTrue("Expected dynamic persisted model to be rediscovered after metadata reset.", recoveredModel!!.isProvisioned)
        assertTrue(
            "Expected recovered dynamic installed versions to include the imported version.",
            recoveredModel.installedVersions.any { it.version == "persisted-dynamic-v1" },
        )
        assertEquals("persisted-dynamic-v1", recoveredModel.activeVersion)
    }

    @Test
    fun pathAliasMigrationSelfHealsStaleActiveVersionPathAfterFlagWasSet() {
        val managedDir = store.managedModelDirectory().apply { mkdirs() }
        val canonicalModel = writeTempFile(
            dir = managedDir,
            fileName = "qwen3.5-0.8b-q4.gguf",
            content = "alias-migration-repair",
        )
        val stalePath = File(managedDir, "qwen3.5-0.8b-q4-q4_0.gguf").absolutePath
        assertFalse(File(stalePath).exists())

        val seededArray = JSONArray().put(
            JSONObject()
                .put("version", "q4_0")
                .put("absolutePath", stalePath)
                .put("sha256", sha256Hex(canonicalModel))
                .put("provenanceIssuer", "internal-release")
                .put("provenanceSignature", "sig-q4_0")
                .put("runtimeCompatibility", store.expectedRuntimeCompatibilityTag())
                .put("fileSizeBytes", canonicalModel.length())
                .put("importedAtEpochMs", System.currentTimeMillis()),
        )
        appContext.getSharedPreferences("pocketagent_runtime_models", 0).edit()
            .putString("model_versions_json_0_8b", seededArray.toString())
            .putString("model_active_version_0_8b", "q4_0")
            .putBoolean("path_alias_migration_done_v1", true)
            .apply()

        val recoveredStore = AndroidRuntimeProvisioningStore(appContext)
        val recovered = recoveredStore.snapshot()
            .models
            .first { it.modelId == ModelCatalog.QWEN_3_5_0_8B_Q4 }

        assertEquals("q4_0", recovered.activeVersion)
        assertEquals(
            normalizePath(canonicalModel.absolutePath),
            normalizePath(recovered.absolutePath.orEmpty()),
        )
        assertTrue(recovered.isProvisioned)
    }

    @Test
    fun corruptedVersionsJsonSelfHealsFromDiscoveredManagedModelFile() {
        val managedDir = store.managedModelDirectory().apply { mkdirs() }
        val canonicalModel = writeTempFile(
            dir = managedDir,
            fileName = "qwen3.5-0.8b-q4.gguf",
            content = "corrupt-json-recovery",
        )

        appContext.getSharedPreferences("pocketagent_runtime_models", 0).edit()
            .putString("model_versions_json_0_8b", "{invalid-json")
            .putString("model_active_version_0_8b", "broken-version")
            .commit()

        val recoveredStore = AndroidRuntimeProvisioningStore(appContext)
        val snapshot = recoveredStore.snapshot()
        val recovered = snapshot.models.first { it.modelId == ModelCatalog.QWEN_3_5_0_8B_Q4 }

        assertTrue("Expected recovered model to be provisioned after metadata corruption.", recovered.isProvisioned)
        assertEquals("discovered", recovered.activeVersion)
        assertEquals(
            normalizePath(canonicalModel.absolutePath),
            normalizePath(recovered.absolutePath.orEmpty()),
        )
        assertTrue(
            snapshot.recoverableCorruptions.any { signal ->
                signal.code == "PROVISIONING_VERSIONS_RECOVERED_FROM_DISCOVERY" &&
                    signal.technicalDetail.contains("model=${ModelCatalog.QWEN_3_5_0_8B_Q4}")
            },
        )
    }

    @Test
    fun removeVersionThenSnapshotShowsVersionAbsent() {
        val managedDir = store.managedModelDirectory().apply { mkdirs() }
        val v1File = writeTempFile(dir = managedDir, fileName = "remove-roundtrip-v1.gguf", content = "v1")
        val v2File = writeTempFile(dir = managedDir, fileName = "remove-roundtrip-v2.gguf", content = "v2")

        store.installDownloadedModel(
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            version = "rt-v1",
            absolutePath = v1File.absolutePath,
            sha256 = sha256Hex(v1File),
            provenanceIssuer = "internal-release",
            provenanceSignature = "",
            runtimeCompatibility = store.expectedRuntimeCompatibilityTag(),
            fileSizeBytes = v1File.length(),
            makeActive = true,
        )
        store.installDownloadedModel(
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            version = "rt-v2",
            absolutePath = v2File.absolutePath,
            sha256 = sha256Hex(v2File),
            provenanceIssuer = "internal-release",
            provenanceSignature = "",
            runtimeCompatibility = store.expectedRuntimeCompatibilityTag(),
            fileSizeBytes = v2File.length(),
            makeActive = false,
        )

        assertTrue(store.removeVersion(ModelCatalog.QWEN_3_5_0_8B_Q4, "rt-v2"))

        val snapshot = store.snapshot()
        val model = snapshot.models.first { it.modelId == ModelCatalog.QWEN_3_5_0_8B_Q4 }
        assertTrue(model.installedVersions.none { it.version == "rt-v2" })
        assertTrue(model.installedVersions.any { it.version == "rt-v1" })
    }

    @Test
    fun storageSummaryRefreshesImmediatelyAfterRemovingInstalledVersion() = runBlocking {
        val source = writeTempFile(
            dir = appContext.cacheDir,
            fileName = "storage-summary-remove-source.gguf",
            content = "storage-summary-remove-content",
        )
        val sourceUri = android.net.Uri.fromFile(source)

        store.importModel(
            modelId = ModelCatalog.QWEN3_1_7B_Q4_K_M,
            sourceUri = sourceUri,
            version = "storage-v1",
        )

        val beforeRemove = store.storageSummary()
        assertTrue(
            "Expected imported model bytes to contribute to used storage before removal.",
            beforeRemove.usedByModelsBytes > 0L,
        )

        assertTrue(store.clearActiveVersion(ModelCatalog.QWEN3_1_7B_Q4_K_M))
        assertTrue(store.removeVersion(ModelCatalog.QWEN3_1_7B_Q4_K_M, "storage-v1"))

        val afterRemove = store.storageSummary()
        assertEquals(
            "Storage summary should drop removed model bytes immediately instead of serving a stale cached value.",
            0L,
            afterRemove.usedByModelsBytes,
        )
    }

    @Test
    fun removeOnlyVersionClearsActiveVersionInSnapshot() = runBlocking {
        val source = writeTempFile(
            dir = appContext.cacheDir,
            fileName = "remove-only-source.gguf",
            content = "only-version",
        )
        val sourceUri = android.net.Uri.fromFile(source)

        store.importModel(
            modelId = ModelCatalog.QWEN3_1_7B_Q4_K_M,
            sourceUri = sourceUri,
            version = "only-v1",
        )

        val snapshotBefore = store.snapshot()
        val modelBefore = snapshotBefore.models.first { it.modelId == ModelCatalog.QWEN3_1_7B_Q4_K_M }
        assertEquals("only-v1", modelBefore.activeVersion)
        assertEquals(1, modelBefore.installedVersions.size)

        store.clearActiveVersion(ModelCatalog.QWEN3_1_7B_Q4_K_M)
        assertTrue(store.removeVersion(ModelCatalog.QWEN3_1_7B_Q4_K_M, "only-v1"))

        val snapshotAfter = store.snapshot()
        val modelAfter = snapshotAfter.models.first { it.modelId == ModelCatalog.QWEN3_1_7B_Q4_K_M }
        assertTrue(modelAfter.installedVersions.isEmpty())
        assertEquals(null, modelAfter.activeVersion)
    }

    @Test
    fun removeActiveVersionWithAlternativesReturnsFalse() {
        val managedDir = store.managedModelDirectory().apply { mkdirs() }
        val v1File = writeTempFile(dir = managedDir, fileName = "guard-v1.gguf", content = "guard-v1")
        val v2File = writeTempFile(dir = managedDir, fileName = "guard-v2.gguf", content = "guard-v2")

        store.installDownloadedModel(
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            version = "guard-v1",
            absolutePath = v1File.absolutePath,
            sha256 = sha256Hex(v1File),
            provenanceIssuer = "internal-release",
            provenanceSignature = "",
            runtimeCompatibility = store.expectedRuntimeCompatibilityTag(),
            fileSizeBytes = v1File.length(),
            makeActive = true,
        )
        store.installDownloadedModel(
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            version = "guard-v2",
            absolutePath = v2File.absolutePath,
            sha256 = sha256Hex(v2File),
            provenanceIssuer = "internal-release",
            provenanceSignature = "",
            runtimeCompatibility = store.expectedRuntimeCompatibilityTag(),
            fileSizeBytes = v2File.length(),
            makeActive = false,
        )

        assertFalse(
            "Removing the active version when alternatives exist should be blocked by the store.",
            store.removeVersion(ModelCatalog.QWEN_3_5_0_8B_Q4, "guard-v1"),
        )

        val snapshot = store.snapshot()
        val model = snapshot.models.first { it.modelId == ModelCatalog.QWEN_3_5_0_8B_Q4 }
        assertEquals(2, model.installedVersions.size)
    }

    @Test
    fun emptyVersionsJsonSelfHealsFromDiscoveredManagedModelFile() {
        val managedDir = store.managedModelDirectory().apply { mkdirs() }
        val canonicalModel = writeTempFile(
            dir = managedDir,
            fileName = "qwen3.5-0.8b-q4.gguf",
            content = "empty-json-recovery",
        )

        appContext.getSharedPreferences("pocketagent_runtime_models", 0).edit()
            .putString("model_versions_json_0_8b", "[]")
            .remove("model_active_version_0_8b")
            .commit()

        val recoveredStore = AndroidRuntimeProvisioningStore(appContext)
        val snapshot = recoveredStore.snapshot()
        val recovered = snapshot.models.first { it.modelId == ModelCatalog.QWEN_3_5_0_8B_Q4 }

        assertTrue("Expected recovered model to be provisioned after empty metadata reset.", recovered.isProvisioned)
        assertEquals("discovered", recovered.activeVersion)
        assertEquals(
            normalizePath(canonicalModel.absolutePath),
            normalizePath(recovered.absolutePath.orEmpty()),
        )
        assertTrue(
            snapshot.recoverableCorruptions.any { signal ->
                signal.code == "PROVISIONING_VERSIONS_RECOVERED_FROM_DISCOVERY" &&
                    signal.technicalDetail.contains("source=PROVISIONING_VERSIONS_EMPTY")
            },
        )
    }

    private fun writeTempFile(dir: File, fileName: String, content: String): File {
        dir.mkdirs()
        return File(dir, fileName).apply { writeText(content) }
    }

    private fun resetProvisioningStorage() {
        appContext.getSharedPreferences("pocketagent_runtime_models", 0).edit().clear().commit()
        File(appContext.filesDir, "runtime-models").deleteRecursively()
        File(appContext.filesDir, "runtime-model-downloads").deleteRecursively()
        File("/sdcard/Android/media/${appContext.packageName}/models").deleteRecursively()
        File("/storage/emulated/0/Android/media/${appContext.packageName}/models").deleteRecursively()
        File("/sdcard/Android/media/${appContext.packageName}/runtime-model-downloads").deleteRecursively()
        File("/storage/emulated/0/Android/media/${appContext.packageName}/runtime-model-downloads").deleteRecursively()
        File("/sdcard/Download/${appContext.packageName}/models").deleteRecursively()
        File("/storage/emulated/0/Download/${appContext.packageName}/models").deleteRecursively()
        arrayOf(appContext.getExternalFilesDir(null))
            .filterNotNull()
            .forEach { mediaDir ->
                File(mediaDir, "models").deleteRecursively()
                File(mediaDir, "runtime-model-downloads").deleteRecursively()
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = appContext.contentResolver
            val selection = "${MediaStore.Downloads.RELATIVE_PATH} = ?"
            val selectionArgs = arrayOf("${Environment.DIRECTORY_DOWNLOADS}/${appContext.packageName}/models/")
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                selection,
                selectionArgs,
                null,
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                    resolver.delete(uri, null, null)
                }
            }
        }
    }

    private fun findMirroredDownloadsUri(displayName: String): android.net.Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null
        }
        val resolver = appContext.contentResolver
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/${appContext.packageName}/models/"
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf(displayName, relativePath)
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            selection,
            selectionArgs,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val id = cursor.getLong(idIndex)
            return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
        }
        return null
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    private fun normalizePath(value: String): String {
        return runCatching { File(value).canonicalPath }.getOrElse { File(value).absolutePath }
    }
}
