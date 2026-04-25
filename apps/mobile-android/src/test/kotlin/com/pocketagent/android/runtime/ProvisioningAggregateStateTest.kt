package com.pocketagent.android.runtime

import android.content.Context
import android.content.ContextWrapper
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
    fun `aggregate store seeds manifest without refreshing snapshot from download changes`() = runTest {
        val dependency = AggregateProvisioningRuntime()
        val storeScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val store = DefaultProvisioningAggregateStore(
            context = AggregateStoreTestContext(),
            coroutineScope = storeScope,
            runtimeBindings = dependency.bindings,
        )
        runCurrent()

        assertFalse(store.currentState().manifestLoaded)
        assertEquals("snapshot-0", store.currentState().snapshot.storageRootLabel)

        dependency.snapshotResult = sampleSnapshot(storageRootLabel = "snapshot-1")
        dependency.downloads.value = listOf(sampleDownloadTask())
        advanceUntilIdle()

        val updated = store.observeState().value
        assertEquals(1, updated.downloads.size)
        assertEquals("snapshot-0", updated.snapshot.storageRootLabel)

        val seeded = store.seed()

        assertTrue(seeded.manifestLoaded)
        assertEquals(1, seeded.manifest.models.size)
        assertEquals(seeded, store.observeState().value)
        storeScope.cancel()
    }

    @Test
    fun `gateway aggregate observation stays aligned with legacy adapters`() = runTest {
        val dependency = AggregateProvisioningRuntime()
        val gatewayScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val gateway = DefaultProvisioningGateway(
            context = AggregateStoreTestContext(),
            coroutineScope = gatewayScope,
            runtimeBindings = dependency.bindings,
        )
        runCurrent()

        assertFalse(gateway.observeProvisioningAggregateState().value.manifestLoaded)

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

        assertEquals(dependency.downloads.value, aggregate.downloads)
        assertEquals(dependency.downloadPreferences.value, aggregate.downloadPreferences)
        assertEquals(dependency.lifecycle.value, aggregate.lifecycle)
        assertEquals("snapshot-0", aggregate.snapshot.storageRootLabel)

        val seeded = gateway.seedProvisioningAggregateState()

        assertTrue(seeded.manifestLoaded)
        assertEquals(seeded.manifest, gateway.observeProvisioningAggregateState().value.manifest)
        assertEquals("snapshot-2", seeded.snapshot.storageRootLabel)
        gatewayScope.cancel()
    }
}

private class AggregateProvisioningRuntime {
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

    val bindings = ProvisioningRuntimeBindings(
        currentProvisioningSnapshot = { snapshotResult },
        observeDownloads = { downloads },
        observeDownloadPreferences = { downloadPreferences },
        currentDownloadPreferences = { downloadPreferences.value },
        observeModelLifecycle = { lifecycle },
        currentModelLifecycle = { lifecycle.value },
        importModelFromUri = { modelId, _ ->
            RuntimeModelImportResult(
                modelId = modelId,
                version = "1",
                absolutePath = "/tmp/$modelId.gguf",
                sha256 = "a".repeat(64),
                copiedBytes = 123L,
                isActive = true,
            )
        },
        loadModelDistributionManifest = { manifestResult },
        listInstalledVersions = { modelId ->
            snapshotResult.models.firstOrNull { model -> model.modelId == modelId }?.installedVersions.orEmpty()
        },
        setActiveVersion = { _, _ -> ProvisioningMutationResult.Applied },
        clearActiveVersion = { _: String -> ProvisioningMutationResult.Applied },
        removeVersion = { _, _ -> ProvisioningMutationResult.Applied },
        loadInstalledModel = { modelId, version ->
            RuntimeModelLifecycleCommandResult.applied(
                loadedModel = RuntimeLoadedModel(modelId = modelId, modelVersion = version),
            )
        },
        loadLastUsedModel = { RuntimeModelLifecycleCommandResult.applied() },
        offloadModel = { _: String -> RuntimeModelLifecycleCommandResult.applied() },
        enqueueDownload = { _, _ -> "task-1" },
        shouldWarnForMeteredLargeDownload = { _: ModelDistributionVersion -> false },
        setDownloadWifiOnlyEnabled = { enabled ->
            downloadPreferences.value = downloadPreferences.value.copy(wifiOnlyEnabled = enabled)
        },
        acknowledgeLargeDownloadCellularWarning = {
            downloadPreferences.value = downloadPreferences.value.copy(
                largeDownloadCellularWarningAcknowledged = true,
            )
        },
        pauseDownload = { _: String -> },
        resumeDownload = { _: String -> },
        retryDownload = { _: String -> },
        cancelDownload = { _: String -> },
        syncDownloadsFromScheduler = { },
    )
}

private class AggregateStoreTestContext : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this
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
