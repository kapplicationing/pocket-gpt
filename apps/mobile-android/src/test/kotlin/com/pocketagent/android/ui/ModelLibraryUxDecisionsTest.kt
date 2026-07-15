package com.pocketagent.android.ui

import com.pocketagent.android.runtime.ProvisionedModelState
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.DownloadTaskStatus
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.android.ui.state.ModelLoadingState
import com.pocketagent.runtime.RuntimeLoadedModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelLibraryUxDecisionsTest {
    @Test
    fun `only a live load request marks a model as switching`() {
        val requestedModel = RuntimeLoadedModel(modelId = "qwen3.5-0.8b-q4", modelVersion = "q4_0")
        val loading = ModelLoadingState.Loading(
            requestedModel = requestedModel,
            loadedModel = null,
            lastUsedModel = null,
            progress = null,
            stage = "Loading model",
            timestampMs = 1L,
        )
        val failed = ModelLoadingState.Error(
            requestedModel = requestedModel,
            loadedModel = null,
            lastUsedModel = requestedModel,
            message = "Out of memory",
            code = "LOAD_FAILED",
            detail = null,
            timestampMs = 2L,
        )
        val offloading = ModelLoadingState.Offloading(
            loadedModel = null,
            lastUsedModel = requestedModel,
            reason = null,
            queued = false,
            timestampMs = 3L,
        )

        assertEquals(requestedModel, loading.switchingRequestedModel())
        assertNull(failed.switchingRequestedModel())
        assertNull(offloading.switchingRequestedModel())
    }

    @Test
    fun `back closes advanced sources before leaving explore`() {
        val nextState = resolveModelLibraryBackNavigation(
            ModelLibraryNavigationState(
                selectedSection = ModelLibrarySection.EXPLORE,
                advancedSourcesExpanded = true,
            ),
        )

        assertEquals(
            ModelLibraryNavigationState(selectedSection = ModelLibrarySection.EXPLORE),
            nextState,
        )
    }

    @Test
    fun `back returns explore to my models before dismissing the library`() {
        assertEquals(
            ModelLibraryNavigationState(),
            resolveModelLibraryBackNavigation(
                ModelLibraryNavigationState(selectedSection = ModelLibrarySection.EXPLORE),
            ),
        )
        assertNull(resolveModelLibraryBackNavigation(ModelLibraryNavigationState()))
    }

    @Test
    fun `management downloads keep catalog transfers and recovery tasks visible`() {
        val tasks = listOf(
            downloadTask("catalog-transfer", DownloadTaskStatus.DOWNLOADING, updatedAtEpochMs = 4L),
            downloadTask("retry-needed", DownloadTaskStatus.FAILED, updatedAtEpochMs = 3L),
            downloadTask("installed", DownloadTaskStatus.INSTALLED_INACTIVE, updatedAtEpochMs = 2L),
            downloadTask("completed", DownloadTaskStatus.COMPLETED, updatedAtEpochMs = 1L),
        )

        assertEquals(
            listOf("catalog-transfer", "retry-needed"),
            managementDownloadTasks(tasks).map { task -> task.taskId },
        )
    }

    @Test
    fun `provisioned default version is ready when not loaded`() {
        val model = provisionedModel(
            modelId = "qwen3.5-0.8b-q4",
            activeVersion = "q4_0",
            installedVersions = listOf(
                versionDescriptor(
                    modelId = "qwen3.5-0.8b-q4",
                    version = "q4_0",
                    isActive = true,
                ),
            ),
        )

        val badge = resolveDownloadedModelBadge(
            model = model,
            version = model.installedVersions.first(),
            activeModel = null,
            loadedModel = null,
        )

        assertEquals(DownloadedModelBadge.READY, badge)
    }

    @Test
    fun `provisioned active non default version is ready when not loaded`() {
        val model = provisionedModel(
            modelId = "qwen3-1.7b-q4_k_m",
            activeVersion = "q4_k_m",
            installedVersions = listOf(
                versionDescriptor(
                    modelId = "qwen3-1.7b-q4_k_m",
                    version = "q4_k_m",
                    isActive = true,
                ),
            ),
        )

        val badge = resolveDownloadedModelBadge(
            model = model,
            version = model.installedVersions.first(),
            activeModel = null,
            loadedModel = null,
        )

        assertEquals(DownloadedModelBadge.READY, badge)
    }

    @Test
    fun `loaded badge wins over provisioned active metadata`() {
        val model = provisionedModel(
            modelId = "qwen3.5-0.8b-q4",
            activeVersion = "q4_0",
            installedVersions = listOf(
                versionDescriptor(
                    modelId = "qwen3.5-0.8b-q4",
                    version = "q4_0",
                    isActive = true,
                ),
            ),
        )

        val badge = resolveDownloadedModelBadge(
            model = model,
            version = model.installedVersions.first(),
            activeModel = RuntimeLoadedModel(modelId = "qwen3.5-0.8b-q4", modelVersion = "q4_0"),
            loadedModel = RuntimeLoadedModel(modelId = "qwen3.5-0.8b-q4", modelVersion = "q4_0"),
        )

        assertEquals(DownloadedModelBadge.LOADED, badge)
    }

    @Test
    fun `removing only installed active version clears active selection`() {
        val model = provisionedModel(
            modelId = "qwen3.5-0.8b-q4",
            activeVersion = "q4_0",
            installedVersions = listOf(
                versionDescriptor(
                    modelId = "qwen3.5-0.8b-q4",
                    version = "q4_0",
                    isActive = true,
                ),
            ),
        )

        val plan = resolveRemoveVersionPlan(
            model = model,
            version = model.installedVersions.first(),
            loadedModel = null,
        )

        assertTrue(plan.requiresClearingActiveSelection)
        assertFalse(plan.isBlockedByActiveSelection)
        assertFalse(plan.requiresOffload)
    }

    @Test
    fun `removing loaded only installed active version offloads first and clears active selection`() {
        val model = provisionedModel(
            modelId = "qwen3.5-0.8b-q4",
            activeVersion = "q4_0",
            installedVersions = listOf(
                versionDescriptor(
                    modelId = "qwen3.5-0.8b-q4",
                    version = "q4_0",
                    isActive = true,
                ),
            ),
        )

        val plan = resolveRemoveVersionPlan(
            model = model,
            version = model.installedVersions.first(),
            loadedModel = RuntimeLoadedModel(modelId = "qwen3.5-0.8b-q4", modelVersion = "q4_0"),
        )

        assertTrue(plan.requiresOffload)
        assertTrue(plan.requiresClearingActiveSelection)
        assertFalse(plan.isBlockedByActiveSelection)
    }

    @Test
    fun `removing active version with alternatives stays blocked`() {
        val model = provisionedModel(
            modelId = "qwen3.5-0.8b-q4",
            activeVersion = "q4_0",
            installedVersions = listOf(
                versionDescriptor(modelId = "qwen3.5-0.8b-q4", version = "q4_0", isActive = true),
                versionDescriptor(modelId = "qwen3.5-0.8b-q4", version = "q4_k_m", isActive = false),
            ),
        )

        val plan = resolveRemoveVersionPlan(
            model = model,
            version = model.installedVersions.first(),
            loadedModel = null,
        )

        assertTrue(plan.isBlockedByActiveSelection)
        assertFalse(plan.requiresClearingActiveSelection)
    }

    @Test
    fun `removing non-active version with alternatives is not blocked`() {
        val model = provisionedModel(
            modelId = "qwen3.5-0.8b-q4",
            activeVersion = "q4_0",
            installedVersions = listOf(
                versionDescriptor(modelId = "qwen3.5-0.8b-q4", version = "q4_0", isActive = true),
                versionDescriptor(modelId = "qwen3.5-0.8b-q4", version = "q4_k_m", isActive = false),
            ),
        )
        val toRemove = model.installedVersions.single { it.version == "q4_k_m" }

        val plan = resolveRemoveVersionPlan(
            model = model,
            version = toRemove,
            loadedModel = null,
        )

        assertFalse(plan.isBlockedByActiveSelection)
        assertFalse(plan.requiresClearingActiveSelection)
        assertFalse(plan.requiresOffload)
    }

    @Test
    fun `removing loaded but not active version requires offload only`() {
        val model = provisionedModel(
            modelId = "qwen3.5-0.8b-q4",
            activeVersion = "q4_0",
            installedVersions = listOf(
                versionDescriptor(modelId = "qwen3.5-0.8b-q4", version = "q4_0", isActive = true),
                versionDescriptor(modelId = "qwen3.5-0.8b-q4", version = "q4_k_m", isActive = false),
            ),
        )
        val toRemove = model.installedVersions.single { it.version == "q4_k_m" }

        val plan = resolveRemoveVersionPlan(
            model = model,
            version = toRemove,
            loadedModel = RuntimeLoadedModel(modelId = "qwen3.5-0.8b-q4", modelVersion = "q4_k_m"),
        )

        assertTrue(plan.requiresOffload)
        assertFalse(plan.requiresClearingActiveSelection)
        assertFalse(plan.isBlockedByActiveSelection)
    }

    @Test
    fun `removing version when model has no active version at all`() {
        val model = provisionedModel(
            modelId = "qwen3.5-0.8b-q4",
            activeVersion = null,
            installedVersions = listOf(
                versionDescriptor(modelId = "qwen3.5-0.8b-q4", version = "q4_0", isActive = false),
            ),
        )

        val plan = resolveRemoveVersionPlan(
            model = model,
            version = model.installedVersions.first(),
            loadedModel = null,
        )

        assertFalse(plan.isBlockedByActiveSelection)
        assertFalse(plan.requiresClearingActiveSelection)
        assertFalse(plan.requiresOffload)
    }

    @Test
    fun `badge after remove shows ready for remaining non-active version`() {
        val model = provisionedModel(
            modelId = "qwen3.5-0.8b-q4",
            activeVersion = null,
            installedVersions = listOf(
                versionDescriptor(modelId = "qwen3.5-0.8b-q4", version = "q4_k_m", isActive = false),
            ),
        )
        val version = model.installedVersions.first()

        val badge = resolveDownloadedModelBadge(
            model = model,
            version = version,
            activeModel = null,
            loadedModel = null,
        )

        assertEquals(DownloadedModelBadge.READY, badge)
    }

    @Test
    fun `badge during load shows switching when loaded differs from active`() {
        val model = provisionedModel(
            modelId = "qwen3.5-0.8b-q4",
            activeVersion = "q4_0",
            installedVersions = listOf(
                versionDescriptor(modelId = "qwen3.5-0.8b-q4", version = "q4_0", isActive = true),
            ),
        )
        val version = model.installedVersions.first()
        val activeModel = RuntimeLoadedModel(modelId = "qwen3.5-0.8b-q4", modelVersion = "q4_0")
        val loadedModel = RuntimeLoadedModel(modelId = "qwen3-1.7b-q4_k_m", modelVersion = "q4_k_m")

        val badge = resolveDownloadedModelBadge(
            model = model,
            version = version,
            activeModel = activeModel,
            loadedModel = loadedModel,
        )

        assertEquals(DownloadedModelBadge.SWITCHING, badge)
    }

    @Test
    fun `badge when loaded model version differs from active version`() {
        val model = provisionedModel(
            modelId = "qwen3.5-0.8b-q4",
            activeVersion = "q4_0",
            installedVersions = listOf(
                versionDescriptor(modelId = "qwen3.5-0.8b-q4", version = "q4_0", isActive = true),
                versionDescriptor(modelId = "qwen3.5-0.8b-q4", version = "q4_k_m", isActive = false),
            ),
        )
        val loadedModel = RuntimeLoadedModel(modelId = "qwen3.5-0.8b-q4", modelVersion = "q4_k_m")
        val q40 = model.installedVersions.single { it.version == "q4_0" }
        val q4km = model.installedVersions.single { it.version == "q4_k_m" }

        val loadedBadge = resolveDownloadedModelBadge(
            model = model,
            version = q4km,
            activeModel = null,
            loadedModel = loadedModel,
        )
        val provisionedActiveBadge = resolveDownloadedModelBadge(
            model = model,
            version = q40,
            activeModel = null,
            loadedModel = loadedModel,
        )

        assertEquals(DownloadedModelBadge.LOADED, loadedBadge)
        assertEquals(DownloadedModelBadge.READY, provisionedActiveBadge)
    }
}

private fun provisionedModel(
    modelId: String,
    activeVersion: String?,
    installedVersions: List<ModelVersionDescriptor>,
): ProvisionedModelState {
    return ProvisionedModelState(
        modelId = modelId,
        displayName = modelId,
        fileName = "$modelId.gguf",
        absolutePath = installedVersions.firstOrNull()?.absolutePath,
        sha256 = installedVersions.firstOrNull()?.sha256,
        importedAtEpochMs = 1L,
        activeVersion = activeVersion,
        installedVersions = installedVersions,
    )
}

private fun versionDescriptor(
    modelId: String,
    version: String,
    isActive: Boolean,
): ModelVersionDescriptor {
    return ModelVersionDescriptor(
        modelId = modelId,
        version = version,
        displayName = "$modelId $version",
        absolutePath = "/tmp/$modelId-$version.gguf",
        sha256 = "a".repeat(64),
        provenanceIssuer = "issuer",
        provenanceSignature = "sig",
        runtimeCompatibility = "android-arm64-v8a",
        fileSizeBytes = 1L,
        importedAtEpochMs = 1L,
        isActive = isActive,
    )
}

private fun downloadTask(
    taskId: String,
    status: DownloadTaskStatus,
    updatedAtEpochMs: Long,
): DownloadTaskState {
    return DownloadTaskState(
        taskId = taskId,
        modelId = "model-$taskId",
        version = "q4",
        downloadUrl = "https://example.test/$taskId.gguf",
        expectedSha256 = "a".repeat(64),
        provenanceIssuer = "issuer",
        provenanceSignature = "signature",
        runtimeCompatibility = "android-arm64-v8a",
        status = status,
        progressBytes = 50L,
        totalBytes = 100L,
        updatedAtEpochMs = updatedAtEpochMs,
    )
}
