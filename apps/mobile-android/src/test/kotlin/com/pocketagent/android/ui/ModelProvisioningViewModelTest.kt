package com.pocketagent.android.ui

import android.net.Uri
import com.pocketagent.android.runtime.ProvisionedModelState
import com.pocketagent.android.runtime.ProvisioningGateway
import com.pocketagent.android.runtime.ProvisioningAggregateState
import com.pocketagent.android.runtime.RuntimeDomainError
import com.pocketagent.android.runtime.RuntimeDomainException
import com.pocketagent.android.runtime.RuntimeErrorCodes
import com.pocketagent.android.runtime.DefaultModelCatalogEligibilityEvaluator
import com.pocketagent.android.runtime.DeviceGpuOffloadAdvisory
import com.pocketagent.android.runtime.GpuProbeFailureReason
import com.pocketagent.android.runtime.GpuProbeResult
import com.pocketagent.android.runtime.GpuProbeStatus
import com.pocketagent.android.runtime.ModelEligibilitySignals
import com.pocketagent.android.runtime.ModelEligibilitySignalsProvider
import com.pocketagent.android.runtime.ModelSupportLevel
import com.pocketagent.android.runtime.RuntimeModelImportResult
import com.pocketagent.android.runtime.RuntimeModelLifecycleSnapshot
import com.pocketagent.android.runtime.RuntimeProvisioningSnapshot
import com.pocketagent.android.runtime.ProvisioningMutationResult
import com.pocketagent.android.runtime.modelmanager.DownloadPreferencesState
import com.pocketagent.android.runtime.modelmanager.DownloadNetworkPreference
import com.pocketagent.android.runtime.modelmanager.DownloadRequestOptions
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.DownloadTaskStatus
import com.pocketagent.android.runtime.modelmanager.DownloadVerificationPolicy
import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelDistributionModel
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.android.runtime.modelmanager.StorageSummary
import com.pocketagent.android.testutil.fakeUri
import com.pocketagent.nativebridge.ModelLifecycleErrorCode
import com.pocketagent.runtime.RuntimeLoadedModel
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ModelProvisioningViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init loads seeded aggregate state and observes aggregate updates`() = runTest(dispatcher) {
        val gateway = FakeProvisioningGateway()
        val viewModel = ModelProvisioningViewModel(gateway, ioDispatcher = dispatcher)
        advanceUntilIdle()

        assertEquals("qwen3.5-0.8b-q4", viewModel.uiState.value.snapshot?.models?.firstOrNull()?.modelId)
        assertTrue(viewModel.uiState.value.manifestLoaded)
        assertEquals(0, viewModel.uiState.value.downloads.size)

        gateway.setDownloads(listOf(sampleDownloadTask()))
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.downloads.size)
    }

    @Test
    fun `import model updates importing state and observes aggregate snapshot refresh`() = runTest(dispatcher) {
        val gateway = FakeProvisioningGateway()
        val viewModel = ModelProvisioningViewModel(gateway, ioDispatcher = dispatcher)
        advanceUntilIdle()

        val result = viewModel.importModelFromUri(
            modelId = "qwen3.5-0.8b-q4",
            sourceUri = fakeUri(),
        )
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        assertFalse(viewModel.uiState.value.isImporting)
        assertTrue(gateway.importCalls > 0)
        assertEquals("2", viewModel.uiState.value.snapshot?.models?.firstOrNull()?.activeVersion)
    }

    @Test
    fun `import model maps runtime domain error to user safe message`() = runTest(dispatcher) {
        val gateway = FakeProvisioningGateway().apply {
            importFailure = RuntimeDomainException(
                domainError = RuntimeDomainError(
                    code = RuntimeErrorCodes.PROVISIONING_IMPORT_SOURCE_UNREADABLE,
                    userMessage = "Unable to read the selected model file.",
                    technicalDetail = "source uri unreadable",
                ),
            )
        }
        val viewModel = ModelProvisioningViewModel(gateway, ioDispatcher = dispatcher)
        advanceUntilIdle()

        val result = viewModel.importModelFromUri(
            modelId = "qwen3.5-0.8b-q4",
            sourceUri = fakeUri(),
        )
        advanceUntilIdle()

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is ProvisioningUserFacingException)
        assertEquals("Unable to read the selected model file.", error?.message)
        val userFacing = error as ProvisioningUserFacingException
        assertEquals(RuntimeErrorCodes.PROVISIONING_IMPORT_SOURCE_UNREADABLE, userFacing.code)
    }

    @Test
    fun `metered download warning check has async UI-facing path`() = runTest(dispatcher) {
        val version = sampleDownloadVersion()
        val gateway = FakeProvisioningGateway().apply {
            shouldWarnForMeteredLargeDownloadResult = true
        }
        val viewModel = ModelProvisioningViewModel(gateway, ioDispatcher = dispatcher)
        advanceUntilIdle()

        val shouldWarn = viewModel.shouldWarnForMeteredLargeDownloadAsync(version)
        advanceUntilIdle()

        assertTrue(shouldWarn)
        assertEquals(version, gateway.lastWarnVersion)
    }


    @Test
    fun `manifest and version actions delegate through gateway`() = runTest(dispatcher) {
        val gateway = FakeProvisioningGateway()
        val viewModel = ModelProvisioningViewModel(gateway, ioDispatcher = dispatcher)
        advanceUntilIdle()

        viewModel.refreshManifest()
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.manifest.models.size)

        assertTrue(viewModel.setActiveVersion("qwen3.5-0.8b-q4", "1").changed)
        assertTrue(viewModel.removeVersion("qwen3.5-0.8b-q4", "1").changed)
        viewModel.cancelDownload("task-1")
        assertEquals(1, gateway.setActiveCalls)
        assertEquals(1, gateway.removeCalls)
        assertEquals(1, gateway.cancelCalls)
    }

    @Test
    fun `clear active version delegates through gateway and refreshes snapshot`() = runTest(dispatcher) {
        val gateway = FakeProvisioningGateway()
        val viewModel = ModelProvisioningViewModel(gateway, ioDispatcher = dispatcher)
        advanceUntilIdle()

        assertTrue(viewModel.clearActiveVersion("qwen3.5-0.8b-q4").changed)
        advanceUntilIdle()

        assertEquals(1, gateway.clearActiveCalls)
        assertEquals(null, viewModel.uiState.value.snapshot?.models?.firstOrNull()?.activeVersion)
    }

    @Test
    fun `download preference actions update observed state and warning checks delegate`() = runTest(dispatcher) {
        val version = sampleDownloadVersion()
        val gateway = FakeProvisioningGateway().apply {
            shouldWarnForMeteredLargeDownloadResult = true
        }
        val viewModel = ModelProvisioningViewModel(gateway, ioDispatcher = dispatcher)
        advanceUntilIdle()

        assertTrue(viewModel.shouldWarnForMeteredLargeDownload(version))
        assertEquals(version, gateway.lastWarnVersion)

        viewModel.setDownloadWifiOnlyEnabled(true)
        viewModel.acknowledgeLargeDownloadCellularWarning()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.downloadPreferences.wifiOnlyEnabled)
        assertTrue(viewModel.uiState.value.downloadPreferences.largeDownloadCellularWarningAcknowledged)
    }

    @Test
    fun `library ui state exposes provisioning and download data`() = runTest(dispatcher) {
        val gateway = FakeProvisioningGateway().apply {
            setDownloads(listOf(sampleDownloadTask()))
        }
        val viewModel = ModelProvisioningViewModel(gateway, ioDispatcher = dispatcher)
        advanceUntilIdle()
        viewModel.refreshManifest()
        advanceUntilIdle()

        val libraryState = viewModel.uiState.value.toModelLibraryUiState(defaultGetReadyModelId = "qwen3.5-0.8b-q4")

        assertTrue(libraryState != null)
        assertEquals("qwen3.5-0.8b-q4", libraryState?.snapshot?.models?.firstOrNull()?.modelId)
        assertEquals(1, libraryState?.downloads?.size)
        assertEquals(null, libraryState?.defaultModelVersion?.modelId)
    }

    @Test
    fun `library ui state carries unsupported bonsai eligibility for cpu only devices`() = runTest(dispatcher) {
        val bonsaiVersion = ModelDistributionVersion(
            modelId = "bonsai-1.7b-q1_0_g128",
            version = "q1_0_g128",
            downloadUrl = "https://example.com/bonsai-1.7b.gguf",
            expectedSha256 = "b".repeat(64),
            provenanceIssuer = "issuer",
            provenanceSignature = "sig",
            runtimeCompatibility = "android-arm64-v8a",
            fileSizeBytes = 123L,
        )
        val gateway = FakeProvisioningGateway().apply {
            manifestResult = ModelDistributionManifest(
                models = listOf(
                    ModelDistributionModel(
                        modelId = bonsaiVersion.modelId,
                        displayName = "Bonsai 1.7B",
                        versions = listOf(bonsaiVersion),
                    ),
                ),
            )
        }
        val signalsProvider = object : ModelEligibilitySignalsProvider {
            override fun currentSignals(): ModelEligibilitySignals {
                return ModelEligibilitySignals(
                    runtimeCompatibilityTag = "android-arm64-v8a",
                    runtimeSupportsGpuOffload = false,
                    deviceAdvisory = DeviceGpuOffloadAdvisory(
                        supportedForProbe = false,
                        automaticOpenClEligible = false,
                        reason = "adreno_family_missing",
                    ),
                    gpuProbeResult = GpuProbeResult(
                        status = GpuProbeStatus.FAILED,
                        failureReason = GpuProbeFailureReason.RUNTIME_UNSUPPORTED,
                    ),
                )
            }
        }
        val viewModel = ModelProvisioningViewModel(
            gateway = gateway,
            eligibilityEvaluator = DefaultModelCatalogEligibilityEvaluator(),
            eligibilitySignalsProvider = signalsProvider,
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.refreshManifest()
        advanceUntilIdle()

        val libraryState = viewModel.uiState.value.toModelLibraryUiState(defaultGetReadyModelId = "qwen3.5-0.8b-q4")
        val eligibility = libraryState!!.eligibility.eligibilityFor(bonsaiVersion.modelId, bonsaiVersion.version)
        assertEquals(ModelSupportLevel.UNSUPPORTED, eligibility.supportLevel)
        assertFalse(eligibility.catalogVisible)
        assertFalse(eligibility.downloadAllowed)
        assertFalse(eligibility.loadAllowed)
    }

    @Test
    fun `runtime ui state exposes lifecycle and installed versions`() = runTest(dispatcher) {
        val gateway = FakeProvisioningGateway().apply {
            setLifecycle(
                RuntimeModelLifecycleSnapshot(
                    loadedModel = RuntimeLoadedModel(
                        modelId = "qwen3.5-0.8b-q4",
                        modelVersion = "1",
                    ),
                ),
            )
        }
        val viewModel = ModelProvisioningViewModel(gateway, ioDispatcher = dispatcher)
        advanceUntilIdle()

        val runtimeState = viewModel.uiState.value.toRuntimeModelUiState()

        assertTrue(runtimeState != null)
        assertEquals("qwen3.5-0.8b-q4", runtimeState?.lifecycle?.loadedModel?.modelId)
        assertTrue(runtimeState?.snapshot?.models?.firstOrNull()?.installedVersions?.isNotEmpty() == true)
    }

    @Test
    fun `enqueue download forwards explicit request options`() = runTest(dispatcher) {
        val gateway = FakeProvisioningGateway()
        val viewModel = ModelProvisioningViewModel(gateway, ioDispatcher = dispatcher)
        val version = sampleDownloadVersion()
        val options = DownloadRequestOptions(
            networkPreference = DownloadNetworkPreference.UNMETERED_ONLY,
            userInitiated = false,
        )
        advanceUntilIdle()

        assertEquals("task-1", viewModel.enqueueDownload(version, options))
        assertEquals(version, gateway.lastEnqueuedVersion)
        assertEquals(options, gateway.lastEnqueuedOptions)
    }

    @Test
    fun `load and offload model update lifecycle state`() = runTest(dispatcher) {
        val gateway = FakeProvisioningGateway()
        val viewModel = ModelProvisioningViewModel(gateway, ioDispatcher = dispatcher)
        advanceUntilIdle()

        val loadResult = viewModel.loadInstalledModel("qwen3.5-0.8b-q4", "1")
        advanceUntilIdle()
        assertTrue(loadResult.success)
        assertEquals(
            "qwen3.5-0.8b-q4",
            viewModel.uiState.value.lifecycle.loadedModel?.modelId,
        )

        val offloadResult = viewModel.offloadModel("manual")
        advanceUntilIdle()
        assertTrue(offloadResult?.success == true)
        assertEquals(null, viewModel.uiState.value.lifecycle.loadedModel)
    }

    @Test
    fun `remove version async success sets status and refreshes snapshot`() = runTest(dispatcher) {
        val gateway = FakeProvisioningGateway()
        val viewModel = ModelProvisioningViewModel(gateway, ioDispatcher = dispatcher)
        advanceUntilIdle()

        viewModel.setStatusMessage("Removing...")
        val result = viewModel.removeVersionAsync("qwen3.5-0.8b-q4", "1")
        advanceUntilIdle()

        assertTrue(result.changed)
        assertEquals(1, gateway.removeCalls)
        assertTrue(
            viewModel.uiState.value.snapshot?.models?.firstOrNull()?.installedVersions?.isEmpty() == true,
        )
    }

    @Test
    fun `remove version async failure preserves failed status`() = runTest(dispatcher) {
        val gateway = FakeProvisioningGateway().apply { removeResult = false }
        val viewModel = ModelProvisioningViewModel(gateway, ioDispatcher = dispatcher)
        advanceUntilIdle()

        val installedVersionsBefore = viewModel.uiState.value.snapshot
            ?.models
            ?.firstOrNull()
            ?.installedVersions
            ?.map { it.version }
        assertFalse(viewModel.removeVersionAsync("qwen3.5-0.8b-q4", "1").changed)
        advanceUntilIdle()

        assertEquals(
            installedVersionsBefore,
            viewModel.uiState.value.snapshot
                ?.models
                ?.firstOrNull()
                ?.installedVersions
                ?.map { it.version },
        )
    }

    @Test
    fun `set status message updates ui state`() = runTest(dispatcher) {
        val gateway = FakeProvisioningGateway()
        val viewModel = ModelProvisioningViewModel(gateway, ioDispatcher = dispatcher)
        advanceUntilIdle()

        viewModel.setStatusMessage("test message")
        assertEquals("test message", viewModel.uiState.value.statusMessage)
        viewModel.setStatusMessage(null)
        assertEquals(null, viewModel.uiState.value.statusMessage)
    }
}

private class FakeProvisioningGateway : ProvisioningGateway {
    val downloads = MutableStateFlow<List<DownloadTaskState>>(emptyList())
    val downloadPreferences = MutableStateFlow(DownloadPreferencesState())
    val lifecycle = MutableStateFlow(RuntimeModelLifecycleSnapshot.initial())
    private val aggregateState = MutableStateFlow(ProvisioningAggregateState())
    var snapshotResult: RuntimeProvisioningSnapshot = sampleSnapshot()
    var manifestResult: ModelDistributionManifest = ModelDistributionManifest(
        models = listOf(
            ModelDistributionModel(
                modelId = "qwen3.5-0.8b-q4",
                displayName = "Qwen",
                versions = emptyList(),
            ),
        ),
    )
    var importCalls: Int = 0
    var setActiveCalls: Int = 0
    var clearActiveCalls: Int = 0
    var removeCalls: Int = 0
    var cancelCalls: Int = 0
    var importFailure: Throwable? = null
    var lastEnqueuedVersion: ModelDistributionVersion? = null
    var lastEnqueuedOptions: DownloadRequestOptions? = null
    var shouldWarnForMeteredLargeDownloadResult: Boolean = false
    var lastWarnVersion: ModelDistributionVersion? = null
    var removeResult: Boolean = true

    init {
        syncAggregateState()
    }

    override fun observeProvisioningAggregateState() = aggregateState

    override suspend fun seedProvisioningAggregateState(): ProvisioningAggregateState {
        val seeded = aggregateState.value.copy(
            snapshot = snapshotResult,
            downloads = downloads.value,
            downloadPreferences = downloadPreferences.value,
            lifecycle = lifecycle.value,
            manifest = manifestResult,
            manifestLoaded = true,
        )
        aggregateState.value = seeded
        return seeded
    }

    override suspend fun importModelFromUri(modelId: String, sourceUri: Uri): RuntimeModelImportResult {
        importFailure?.let { throw it }
        importCalls += 1
        snapshotResult = sampleSnapshot(activeVersion = "2", installedVersion = "2")
        syncAggregateState()
        return RuntimeModelImportResult(
            modelId = modelId,
            version = "2",
            absolutePath = "/tmp/model.gguf",
            sha256 = "a".repeat(64),
            copiedBytes = 123L,
            isActive = true,
        )
    }

    override fun listInstalledVersions(modelId: String): List<ModelVersionDescriptor> {
        return snapshotResult.models.first().installedVersions
    }

    override fun setActiveVersion(modelId: String, version: String): ProvisioningMutationResult {
        setActiveCalls += 1
        snapshotResult = snapshotResult.withActiveVersion(modelId = modelId, activeVersion = version)
        syncAggregateState()
        return ProvisioningMutationResult.Applied
    }

    override fun clearActiveVersion(modelId: String): ProvisioningMutationResult {
        clearActiveCalls += 1
        snapshotResult = snapshotResult.withActiveVersion(modelId = modelId, activeVersion = null)
        syncAggregateState()
        return ProvisioningMutationResult.Applied
    }

    override fun removeVersion(modelId: String, version: String): ProvisioningMutationResult {
        removeCalls += 1
        if (removeResult) {
            snapshotResult = snapshotResult.withRemovedVersion(modelId = modelId, version = version)
            syncAggregateState()
            return ProvisioningMutationResult.Applied
        }
        return ProvisioningMutationResult.NoChange(detail = "remove_failed")
    }

    override suspend fun loadInstalledModel(modelId: String, version: String): RuntimeModelLifecycleCommandResult {
        val loaded = RuntimeLoadedModel(modelId = modelId, modelVersion = version)
        lifecycle.value = lifecycle.value.copy(
            state = com.pocketagent.nativebridge.ModelLifecycleState.LOADED,
            loadedModel = loaded,
            lastUsedModel = loaded,
        )
        syncAggregateState()
        return RuntimeModelLifecycleCommandResult.applied(loadedModel = loaded)
    }

    override suspend fun loadLastUsedModel(): RuntimeModelLifecycleCommandResult {
        return RuntimeModelLifecycleCommandResult.rejected(
            code = ModelLifecycleErrorCode.MODEL_FILE_UNAVAILABLE,
            detail = "last_loaded_model_missing",
        )
    }

    override suspend fun offloadModel(reason: String): RuntimeModelLifecycleCommandResult {
        lifecycle.value = lifecycle.value.copy(
            state = com.pocketagent.nativebridge.ModelLifecycleState.UNLOADED,
            loadedModel = null,
            queuedOffload = false,
        )
        syncAggregateState()
        return RuntimeModelLifecycleCommandResult.applied()
    }

    override suspend fun enqueueDownload(version: ModelDistributionVersion, options: DownloadRequestOptions): String {
        lastEnqueuedVersion = version
        lastEnqueuedOptions = options
        return "task-1"
    }

    override fun shouldWarnForMeteredLargeDownload(version: ModelDistributionVersion): Boolean {
        lastWarnVersion = version
        return shouldWarnForMeteredLargeDownloadResult
    }

    override fun setDownloadWifiOnlyEnabled(enabled: Boolean) {
        downloadPreferences.value = downloadPreferences.value.copy(wifiOnlyEnabled = enabled)
        syncAggregateState()
    }

    override fun acknowledgeLargeDownloadCellularWarning() {
        downloadPreferences.value = downloadPreferences.value.copy(
            largeDownloadCellularWarningAcknowledged = true,
        )
        syncAggregateState()
    }

    override fun pauseDownload(taskId: String) = Unit

    override fun resumeDownload(taskId: String) = Unit

    override fun retryDownload(taskId: String) = Unit

    override fun cancelDownload(taskId: String) {
        cancelCalls += 1
    }

    override fun syncDownloadsFromScheduler() = Unit

    fun setDownloads(tasks: List<DownloadTaskState>) {
        downloads.value = tasks
        syncAggregateState()
    }

    fun setLifecycle(snapshot: RuntimeModelLifecycleSnapshot) {
        lifecycle.value = snapshot
        syncAggregateState()
    }

    private fun syncAggregateState() {
        aggregateState.value = aggregateState.value.copy(
            snapshot = snapshotResult,
            downloads = downloads.value,
            downloadPreferences = downloadPreferences.value,
            lifecycle = lifecycle.value,
        )
    }
}

private fun sampleSnapshot(
    modelId: String = "qwen3.5-0.8b-q4",
    installedVersion: String = "1",
    activeVersion: String? = "1",
): RuntimeProvisioningSnapshot {
    return RuntimeProvisioningSnapshot(
        models = listOf(
            ProvisionedModelState(
                modelId = modelId,
                displayName = "Qwen",
                fileName = "qwen.gguf",
                absolutePath = "/tmp/qwen.gguf",
                sha256 = "a".repeat(64),
                importedAtEpochMs = 1L,
                activeVersion = activeVersion,
                installedVersions = listOf(
                    ModelVersionDescriptor(
                        modelId = modelId,
                        version = installedVersion,
                        displayName = "Qwen",
                        absolutePath = "/tmp/qwen.gguf",
                        sha256 = "a".repeat(64),
                        provenanceIssuer = "issuer",
                        provenanceSignature = "sig",
                        runtimeCompatibility = "android-arm64-v8a",
                        fileSizeBytes = 123L,
                        importedAtEpochMs = 1L,
                        isActive = true,
                    ),
                ),
            ),
        ),
        storageSummary = StorageSummary(
            totalBytes = 1_000L,
            freeBytes = 500L,
            usedByModelsBytes = 250L,
            tempDownloadBytes = 0L,
        ),
        requiredModelIds = setOf("qwen3.5-0.8b-q4"),
    )
}

private fun RuntimeProvisioningSnapshot.withActiveVersion(
    modelId: String,
    activeVersion: String?,
): RuntimeProvisioningSnapshot {
    return copy(
        models = models.map { model ->
            if (model.modelId != modelId) {
                model
            } else {
                model.copy(activeVersion = activeVersion)
            }
        },
    )
}

private fun RuntimeProvisioningSnapshot.withRemovedVersion(
    modelId: String,
    version: String,
): RuntimeProvisioningSnapshot {
    return copy(
        models = models.map { model ->
            if (model.modelId != modelId) {
                model
            } else {
                val installedVersions = model.installedVersions.filterNot { descriptor ->
                    descriptor.version == version
                }
                model.copy(
                    activeVersion = model.activeVersion.takeUnless { it == version },
                    installedVersions = installedVersions,
                )
            }
        },
    )
}

private fun sampleDownloadTask(): DownloadTaskState {
    return DownloadTaskState(
        taskId = "task-1",
        modelId = "qwen3.5-0.8b-q4",
        version = "1",
        downloadUrl = "https://example.com/model.gguf",
        expectedSha256 = "a".repeat(64),
        provenanceIssuer = "issuer",
        provenanceSignature = "sig",
        verificationPolicy = DownloadVerificationPolicy.INTEGRITY_ONLY,
        runtimeCompatibility = "android-arm64-v8a",
        status = DownloadTaskStatus.DOWNLOADING,
        progressBytes = 50L,
        totalBytes = 100L,
        updatedAtEpochMs = 1L,
    )
}

private fun sampleDownloadVersion(): ModelDistributionVersion {
    return ModelDistributionVersion(
        modelId = "qwen3.5-0.8b-q4",
        version = "1",
        downloadUrl = "https://example.com/model.gguf",
        expectedSha256 = "a".repeat(64),
        provenanceIssuer = "issuer",
        provenanceSignature = "sig",
        runtimeCompatibility = "android-arm64-v8a",
        fileSizeBytes = 2L * 1024L * 1024L * 1024L,
    )
}
