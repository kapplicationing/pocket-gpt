package com.pocketagent.android.runtime

import android.net.Uri
import com.pocketagent.android.runtime.modelmanager.DownloadPreferencesState
import com.pocketagent.android.runtime.modelmanager.DownloadRequestOptions
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.DownloadTaskStatus
import com.pocketagent.android.runtime.modelmanager.DownloadVerificationPolicy
import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelDistributionModel
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.android.runtime.modelmanager.StorageSummary
import com.pocketagent.core.model.ModelSourceKind
import com.pocketagent.nativebridge.ModelLifecycleState
import com.pocketagent.runtime.RuntimeLoadedModel
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProvisioningAggregateStateTest {
    @Test
    fun `aggregate store seeds manifest and refreshes snapshot from download changes`() = runTest {
        val dependency = AggregateProvisioningDependencyAccess()
        val storeScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val store = DefaultProvisioningAggregateStore(
            dependencies = dependency,
            coroutineScope = storeScope,
        )
        runCurrent()

        assertFalse(store.currentState().manifestLoaded)
        assertEquals("snapshot-0", store.currentState().snapshot.storageRootLabel)

        dependency.snapshotResult = sampleSnapshot(storageRootLabel = "snapshot-1")
        dependency.downloads.value = listOf(sampleDownloadTask())
        advanceUntilIdle()

        val updated = store.observeState().value
        assertEquals(1, updated.downloads.size)
        assertEquals("snapshot-1", updated.snapshot.storageRootLabel)

        val seeded = store.seed()

        assertTrue(seeded.manifestLoaded)
        assertEquals(1, seeded.manifest.models.size)
        assertEquals(seeded, store.observeState().value)
        storeScope.cancel()
    }

    @Test
    fun `gateway aggregate observation stays aligned with legacy adapters`() = runTest {
        val dependency = AggregateProvisioningDependencyAccess()
        val gatewayScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val gateway = DefaultProvisioningGateway(
            dependencies = dependency,
            coroutineScope = gatewayScope,
        )
        runCurrent()

        assertFalse(gateway.currentProvisioningAggregateState().manifestLoaded)

        dependency.downloadPreferences.value = DownloadPreferencesState(
            wifiOnlyEnabled = true,
            largeDownloadCellularWarningAcknowledged = true,
        )
        dependency.lifecycle.value = RuntimeModelLifecycleSnapshot(
            state = ModelLifecycleState.LOADED,
            loadedModel = RuntimeLoadedModel(
                modelId = "qwen3.5-0.8b-q4",
                modelVersion = "1",
            ),
        )
        dependency.snapshotResult = sampleSnapshot(storageRootLabel = "snapshot-2")
        dependency.downloads.value = listOf(sampleDownloadTask(taskId = "task-2"))
        advanceUntilIdle()

        val aggregate = gateway.observeProvisioningAggregateState().value

        assertEquals(gateway.observeDownloads().value, aggregate.downloads)
        assertEquals(gateway.currentDownloadPreferences(), aggregate.downloadPreferences)
        assertEquals(gateway.currentModelLifecycle(), aggregate.lifecycle)
        assertEquals("snapshot-2", gateway.currentSnapshot().storageRootLabel)

        val seeded = gateway.seedProvisioningAggregateState()

        assertTrue(seeded.manifestLoaded)
        assertEquals(seeded.manifest, gateway.observeProvisioningAggregateState().value.manifest)
        assertEquals(seeded.snapshot, gateway.currentSnapshot())
        gatewayScope.cancel()
    }
}

private class AggregateProvisioningDependencyAccess : ProvisioningDependencyAccess {
    val downloads = MutableStateFlow<List<DownloadTaskState>>(emptyList())
    val downloadPreferences = MutableStateFlow(DownloadPreferencesState())
    val lifecycle = MutableStateFlow(RuntimeModelLifecycleSnapshot.initial())
    var snapshotResult: RuntimeProvisioningSnapshot = sampleSnapshot(storageRootLabel = "snapshot-0")
    var manifestResult: ModelDistributionManifest = ModelDistributionManifest(
        models = listOf(
            ModelDistributionModel(
                modelId = "qwen3.5-0.8b-q4",
                displayName = "Qwen",
                versions = listOf(sampleDistributionVersion()),
            ),
        ),
    )

    override fun currentProvisioningSnapshot(): RuntimeProvisioningSnapshot = snapshotResult

    override fun observeDownloads() = downloads

    override fun observeDownloadPreferences() = downloadPreferences

    override fun currentDownloadPreferences(): DownloadPreferencesState = downloadPreferences.value

    override fun observeModelLifecycle() = lifecycle

    override fun currentModelLifecycle(): RuntimeModelLifecycleSnapshot = lifecycle.value

    override suspend fun importModelFromUri(
        modelId: String,
        sourceUri: Uri,
    ): RuntimeModelImportResult = RuntimeModelImportResult(
        modelId = modelId,
        version = "1",
        absolutePath = "/tmp/$modelId.gguf",
        sha256 = "a".repeat(64),
        copiedBytes = 123L,
        isActive = true,
    )

    override suspend fun loadModelDistributionManifest(): ModelDistributionManifest = manifestResult

    override fun listInstalledVersions(modelId: String): List<ModelVersionDescriptor> {
        return snapshotResult.models.firstOrNull { model -> model.modelId == modelId }?.installedVersions.orEmpty()
    }

    override fun setActiveVersion(modelId: String, version: String): Boolean = true

    override fun clearActiveVersion(modelId: String): Boolean = true

    override fun removeVersion(modelId: String, version: String): Boolean = true

    override suspend fun loadInstalledModel(
        modelId: String,
        version: String,
    ): RuntimeModelLifecycleCommandResult {
        return RuntimeModelLifecycleCommandResult.applied(
            loadedModel = RuntimeLoadedModel(modelId = modelId, modelVersion = version),
        )
    }

    override suspend fun loadLastUsedModel(): RuntimeModelLifecycleCommandResult {
        return RuntimeModelLifecycleCommandResult.applied()
    }

    override suspend fun offloadModel(reason: String): RuntimeModelLifecycleCommandResult {
        return RuntimeModelLifecycleCommandResult.applied()
    }

    override suspend fun enqueueDownload(
        version: ModelDistributionVersion,
        options: DownloadRequestOptions,
    ): String = "task-1"

    override fun shouldWarnForMeteredLargeDownload(version: ModelDistributionVersion): Boolean = false

    override fun setDownloadWifiOnlyEnabled(enabled: Boolean) {
        downloadPreferences.value = downloadPreferences.value.copy(wifiOnlyEnabled = enabled)
    }

    override fun acknowledgeLargeDownloadCellularWarning() {
        downloadPreferences.value = downloadPreferences.value.copy(
            largeDownloadCellularWarningAcknowledged = true,
        )
    }

    override fun pauseDownload(taskId: String) = Unit

    override fun resumeDownload(taskId: String) = Unit

    override fun retryDownload(taskId: String) = Unit

    override fun cancelDownload(taskId: String) = Unit

    override fun syncDownloadsFromScheduler() = Unit
}

private fun sampleSnapshot(storageRootLabel: String): RuntimeProvisioningSnapshot {
    return RuntimeProvisioningSnapshot(
        models = listOf(
            ProvisionedModelState(
                modelId = "qwen3.5-0.8b-q4",
                displayName = "Qwen",
                fileName = "qwen.gguf",
                absolutePath = "/tmp/qwen.gguf",
                sha256 = "a".repeat(64),
                importedAtEpochMs = 1L,
                activeVersion = "1",
                installedVersions = listOf(
                    ModelVersionDescriptor(
                        modelId = "qwen3.5-0.8b-q4",
                        version = "1",
                        displayName = "Qwen",
                        absolutePath = "/tmp/qwen.gguf",
                        sha256 = "a".repeat(64),
                        provenanceIssuer = "issuer",
                        provenanceSignature = "sig",
                        runtimeCompatibility = "android-arm64-v8a",
                        fileSizeBytes = 123L,
                        importedAtEpochMs = 1L,
                        isActive = true,
                        sourceKind = ModelSourceKind.BUILT_IN,
                    ),
                ),
                storageRootLabel = storageRootLabel,
            ),
        ),
        storageSummary = StorageSummary(
            totalBytes = 1_000L,
            freeBytes = 500L,
            usedByModelsBytes = 250L,
            tempDownloadBytes = 0L,
        ),
        requiredModelIds = setOf("qwen3.5-0.8b-q4"),
        storageRootLabel = storageRootLabel,
    )
}

private fun sampleDistributionVersion(): ModelDistributionVersion {
    return ModelDistributionVersion(
        modelId = "qwen3.5-0.8b-q4",
        version = "1",
        downloadUrl = "https://example.com/qwen.gguf",
        expectedSha256 = "a".repeat(64),
        provenanceIssuer = "issuer",
        provenanceSignature = "sig",
        runtimeCompatibility = "android-arm64-v8a",
        fileSizeBytes = 123L,
    )
}

private fun sampleDownloadTask(taskId: String = "task-1"): DownloadTaskState {
    return DownloadTaskState(
        taskId = taskId,
        modelId = "qwen3.5-0.8b-q4",
        version = "1",
        sourceKind = ModelSourceKind.BUILT_IN,
        downloadUrl = "https://example.com/qwen.gguf",
        expectedSha256 = "a".repeat(64),
        provenanceIssuer = "issuer",
        provenanceSignature = "sig",
        verificationPolicy = DownloadVerificationPolicy.INTEGRITY_ONLY,
        runtimeCompatibility = "android-arm64-v8a",
        status = DownloadTaskStatus.DOWNLOADING,
        progressBytes = 10L,
        totalBytes = 123L,
        updatedAtEpochMs = 1L,
    )
}
