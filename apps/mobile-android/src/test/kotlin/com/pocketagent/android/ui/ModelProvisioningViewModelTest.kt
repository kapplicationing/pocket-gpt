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
import com.pocketagent.android.runtime.huggingface.DefaultHuggingFaceModelAcquisition
import com.pocketagent.android.runtime.huggingface.FixtureHuggingFaceEndpointAdapter
import com.pocketagent.android.runtime.huggingface.HuggingFaceAcquisitionBlockReason
import com.pocketagent.android.runtime.huggingface.HuggingFaceAcquisitionException
import com.pocketagent.android.runtime.huggingface.HuggingFaceCandidate
import com.pocketagent.android.runtime.huggingface.HuggingFaceModelAcquisition
import com.pocketagent.android.runtime.huggingface.HuggingFaceModelReference
import com.pocketagent.android.runtime.huggingface.OkHttpHuggingFaceHubClient
import com.pocketagent.android.runtime.huggingface.HuggingFaceRecentModel
import com.pocketagent.android.runtime.huggingface.HuggingFaceRecentModelStore
import com.pocketagent.android.runtime.huggingface.HuggingFaceSearchFileResult
import com.pocketagent.android.runtime.huggingface.HuggingFaceTargetModel
import com.pocketagent.android.runtime.huggingface.toRecentModel
import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelDistributionModel
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.android.runtime.modelmanager.StorageSummary
import com.pocketagent.core.model.ModelSourceKind
import com.pocketagent.core.model.SourceTrustPolicy
import com.pocketagent.android.ui.state.ModelLoadingState
import com.pocketagent.android.testutil.fakeUri
import com.pocketagent.nativebridge.ModelLifecycleErrorCode
import com.pocketagent.runtime.RuntimeLoadedModel
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun `hugging face candidate resolve exposes ready state and enqueue reuses gateway`() = runTest(dispatcher) {
        val version = sampleDownloadVersion().copy(
            sourceKind = ModelSourceKind.HUGGING_FACE,
            displayName = "owner/repo / model.gguf",
        )
        val acquisition = FakeHuggingFaceModelAcquisition(candidate = sampleHuggingFaceCandidate(version))
        val gateway = FakeProvisioningGateway()
        val viewModel = ModelProvisioningViewModel(
            gateway = gateway,
            huggingFaceModelAcquisition = acquisition,
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.resolveHuggingFaceCandidate(
            input = "https://huggingface.co/owner/repo/resolve/main/model.gguf",
            targetModelId = "qwen3.5-0.8b-q4",
        )
        advanceUntilIdle()

        val ready = viewModel.uiState.value.huggingFaceAcquisitionState
        assertTrue(ready is HuggingFaceAcquisitionUiState.Ready)
        assertEquals(version, ready.candidate.version)

        assertEquals("task-1", viewModel.enqueueDownload(version))
        advanceUntilIdle()

        assertEquals(version, gateway.lastEnqueuedVersion)
        assertTrue(viewModel.uiState.value.huggingFaceAcquisitionState is HuggingFaceAcquisitionUiState.Ready)
        assertTrue(viewModel.uiState.value.enqueuingModelIds.isEmpty())
    }

    @Test
    fun `hugging face enqueue stores recent redownload entry`() = runTest(dispatcher) {
        val version = sampleDownloadVersion().copy(
            sourceKind = ModelSourceKind.HUGGING_FACE,
            displayName = "owner/repo / model.gguf",
        )
        val candidate = sampleHuggingFaceCandidate(version)
        val acquisition = FakeHuggingFaceModelAcquisition(candidate = candidate)
        val recentStore = FakeHuggingFaceRecentModelStore()
        val gateway = FakeProvisioningGateway()
        val viewModel = ModelProvisioningViewModel(
            gateway = gateway,
            huggingFaceModelAcquisition = acquisition,
            huggingFaceRecentModelStore = recentStore,
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.resolveHuggingFaceCandidate(
            input = "https://huggingface.co/owner/repo/resolve/main/model.gguf",
            targetModelId = "qwen3.5-0.8b-q4",
        )
        advanceUntilIdle()

        assertEquals("task-1", viewModel.enqueueDownload(version))
        advanceUntilIdle()

        val recent = viewModel.uiState.value.recentHuggingFaceModels.single()
        assertEquals("owner/repo / model.gguf", recent.displayName)
        assertEquals("https://huggingface.co/owner/repo/resolve/main/model.gguf", recent.originUrl)
        assertEquals("qwen3.5-0.8b-q4", recent.targetModelId)
        assertEquals(candidate.sha256, recent.sha256)
    }

    @Test
    fun `hugging face fixture search resolve and enqueue stays hermetic`() = runTest(dispatcher) {
        val sha = "b".repeat(64)
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
                          {"rfilename": "model.gguf"}
                        ]
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
                    [
                      {
                        "path": "model.gguf",
                        "lfs": {
                          "oid": "$sha",
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
            val targetModelId = acquisition.supportedTargets().first().modelId
            val recentStore = FakeHuggingFaceRecentModelStore()
            val gateway = FakeProvisioningGateway()
            val viewModel = ModelProvisioningViewModel(
                gateway = gateway,
                huggingFaceModelAcquisition = acquisition,
                huggingFaceRecentModelStore = recentStore,
                ioDispatcher = dispatcher,
            )
            advanceUntilIdle()

            viewModel.searchHuggingFaceFiles(" tiny ")
            advanceUntilIdle()

            val searchState = viewModel.uiState.value.huggingFaceSearchState
            assertTrue(searchState is HuggingFaceSearchUiState.Results)
            val result = searchState.results.single()
            assertEquals("owner/repo / model.gguf", result.displayName)
            assertEquals("https://huggingface.co/owner/repo/resolve/main/model.gguf", result.canonicalUrl)

            viewModel.resolveHuggingFaceCandidate(
                input = result.canonicalUrl,
                targetModelId = targetModelId,
            )
            advanceUntilIdle()

            val ready = viewModel.uiState.value.huggingFaceAcquisitionState
            assertTrue(ready is HuggingFaceAcquisitionUiState.Ready)
            val version = ready.candidate.version
            assertEquals(targetModelId, version.modelId)
            assertEquals(ModelSourceKind.HUGGING_FACE, version.sourceKind)
            assertEquals("owner/repo / model.gguf", version.displayName)
            assertEquals(sha, version.expectedSha256)
            assertEquals(4096L, version.fileSizeBytes)
            assertEquals(server.url("/owner/repo/resolve/main/model.gguf").toString(), version.downloadUrl)
            assertEquals("https://huggingface.co/owner/repo/resolve/main/model.gguf", version.sourceRef?.originUrl)
            assertEquals(SourceTrustPolicy.INTEGRITY_ONLY, version.sourceRef?.trustPolicy)
            assertEquals(version.downloadUrl, version.artifacts.single().downloadUrl)

            assertEquals("task-1", viewModel.enqueueDownload(version))
            advanceUntilIdle()

            assertEquals(version, gateway.lastEnqueuedVersion)
            assertEquals("owner/repo / model.gguf", recentStore.list().single().displayName)
            assertEquals(
                listOf(
                    "/api/models?search=tiny&limit=20&full=true",
                    "/api/models/owner/repo/tree/main",
                    "/api/models/owner/repo/revision/main",
                ),
                listOf(
                    server.takeRequest().path,
                    server.takeRequest().path,
                    server.takeRequest().path,
                ),
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `hugging face resolve blocks when tree omits requested file`() = runTest(dispatcher) {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    [
                      {
                        "path": "different.gguf",
                        "lfs": {
                          "oid": "${"c".repeat(64)}",
                          "size": 4096
                        }
                      }
                    ]
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
            val targetModelId = acquisition.supportedTargets().first().modelId
            val viewModel = ModelProvisioningViewModel(
                gateway = FakeProvisioningGateway(),
                huggingFaceModelAcquisition = acquisition,
                ioDispatcher = dispatcher,
            )
            advanceUntilIdle()

            viewModel.resolveHuggingFaceCandidate(
                input = "https://huggingface.co/owner/repo/resolve/main/model.gguf",
                targetModelId = targetModelId,
            )
            advanceUntilIdle()

            val blocked = viewModel.uiState.value.huggingFaceAcquisitionState
            assertTrue(blocked is HuggingFaceAcquisitionUiState.Blocked)
            assertEquals(HuggingFaceAcquisitionBlockReason.FILE_NOT_FOUND, blocked.reason)
            assertEquals("/api/models/owner/repo/tree/main", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `hugging face enqueue failure does not store recent model`() = runTest(dispatcher) {
        val version = sampleDownloadVersion().copy(
            sourceKind = ModelSourceKind.HUGGING_FACE,
            displayName = "owner/repo / model.gguf",
        )
        val candidate = sampleHuggingFaceCandidate(version)
        val recentStore = FakeHuggingFaceRecentModelStore()
        val gateway = FakeProvisioningGateway().apply {
            enqueueFailure = IllegalStateException("blocked before enqueue")
        }
        val viewModel = ModelProvisioningViewModel(
            gateway = gateway,
            huggingFaceModelAcquisition = FakeHuggingFaceModelAcquisition(candidate = candidate),
            huggingFaceRecentModelStore = recentStore,
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.resolveHuggingFaceCandidate(
            input = "https://huggingface.co/owner/repo/resolve/main/model.gguf",
            targetModelId = "qwen3.5-0.8b-q4",
        )
        advanceUntilIdle()

        assertFailsWith<IllegalStateException> {
            viewModel.enqueueDownload(version)
        }
        advanceUntilIdle()

        assertTrue(recentStore.list().isEmpty())
        assertTrue(viewModel.uiState.value.huggingFaceAcquisitionState is HuggingFaceAcquisitionUiState.Ready)
        assertTrue(viewModel.uiState.value.enqueuingModelIds.isEmpty())
    }

    @Test
    fun `remove recent hugging face model updates ui state`() = runTest(dispatcher) {
        val version = sampleDownloadVersion().copy(
            sourceKind = ModelSourceKind.HUGGING_FACE,
            displayName = "owner/repo / model.gguf",
        )
        val candidate = sampleHuggingFaceCandidate(version)
        val recentStore = FakeHuggingFaceRecentModelStore().apply {
            upsert(candidate, enqueuedAtEpochMs = 1234L)
        }
        val viewModel = ModelProvisioningViewModel(
            gateway = FakeProvisioningGateway(),
            huggingFaceRecentModelStore = recentStore,
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        val recent = viewModel.uiState.value.recentHuggingFaceModels.single()
        viewModel.removeRecentHuggingFaceModel(recent.id)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.recentHuggingFaceModels.isEmpty())
    }

    @Test
    fun `clear recent hugging face models updates ui state`() = runTest(dispatcher) {
        val version = sampleDownloadVersion().copy(
            sourceKind = ModelSourceKind.HUGGING_FACE,
            displayName = "owner/repo / model.gguf",
        )
        val candidate = sampleHuggingFaceCandidate(version)
        val recentStore = FakeHuggingFaceRecentModelStore().apply {
            upsert(candidate, enqueuedAtEpochMs = 1234L)
            upsert(
                candidate.copy(
                    reference = candidate.reference.copy(filePath = "other.gguf"),
                    displayName = "owner/repo / other.gguf",
                ),
                enqueuedAtEpochMs = 2345L,
            )
        }
        val viewModel = ModelProvisioningViewModel(
            gateway = FakeProvisioningGateway(),
            huggingFaceRecentModelStore = recentStore,
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.recentHuggingFaceModels.size)

        viewModel.clearRecentHuggingFaceModels()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.recentHuggingFaceModels.isEmpty())
    }

    @Test
    fun `hugging face candidate failures and clear update acquisition state`() = runTest(dispatcher) {
        val acquisition = FakeHuggingFaceModelAcquisition(
            failure = HuggingFaceAcquisitionException(
                reason = HuggingFaceAcquisitionBlockReason.MISSING_SHA,
                userMessage = "Missing checksum",
            ),
        )
        val viewModel = ModelProvisioningViewModel(
            gateway = FakeProvisioningGateway(),
            huggingFaceModelAcquisition = acquisition,
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.resolveHuggingFaceCandidate(
            input = "https://huggingface.co/owner/repo/resolve/main/model.gguf",
            targetModelId = "qwen3.5-0.8b-q4",
        )
        advanceUntilIdle()

        val blocked = viewModel.uiState.value.huggingFaceAcquisitionState
        assertTrue(blocked is HuggingFaceAcquisitionUiState.Blocked)
        assertEquals(HuggingFaceAcquisitionBlockReason.MISSING_SHA, blocked.reason)
        assertEquals("Missing checksum", blocked.message)

        viewModel.clearHuggingFaceCandidate()

        assertTrue(viewModel.uiState.value.huggingFaceAcquisitionState is HuggingFaceAcquisitionUiState.Idle)
    }

    @Test
    fun `stale hugging face resolve completion is ignored after clear`() = runTest(dispatcher) {
        val resolveStarted = CompletableDeferred<Unit>()
        val pendingCandidate = CompletableDeferred<HuggingFaceCandidate>()
        val version = sampleDownloadVersion()
        val acquisition = FakeHuggingFaceModelAcquisition(
            resolve = { _, _ ->
                resolveStarted.complete(Unit)
                pendingCandidate.await()
            },
        )
        val viewModel = ModelProvisioningViewModel(
            gateway = FakeProvisioningGateway(),
            huggingFaceModelAcquisition = acquisition,
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        val resolveJob = launch {
            viewModel.resolveHuggingFaceCandidate(
                input = "https://huggingface.co/owner/repo/resolve/main/model.gguf",
                targetModelId = "qwen3.5-0.8b-q4",
            )
        }
        advanceUntilIdle()

        assertTrue(resolveStarted.isCompleted)

        viewModel.clearHuggingFaceCandidate()
        pendingCandidate.complete(sampleHuggingFaceCandidate(version))
        resolveJob.join()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.huggingFaceAcquisitionState is HuggingFaceAcquisitionUiState.Idle)
    }

    @Test
    fun `hugging face search exposes results and clear resets state`() = runTest(dispatcher) {
        val result = sampleHuggingFaceSearchResult()
        val acquisition = FakeHuggingFaceModelAcquisition(searchResults = listOf(result))
        val viewModel = ModelProvisioningViewModel(
            gateway = FakeProvisioningGateway(),
            huggingFaceModelAcquisition = acquisition,
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.searchHuggingFaceFiles("  qwen gguf  ")
        advanceUntilIdle()

        val searchState = viewModel.uiState.value.huggingFaceSearchState
        assertTrue(searchState is HuggingFaceSearchUiState.Results)
        assertEquals("qwen gguf", searchState.query)
        assertEquals(listOf(result), searchState.results)
        assertEquals(listOf("qwen gguf"), acquisition.searchQueries)

        viewModel.clearHuggingFaceSearch()

        assertTrue(viewModel.uiState.value.huggingFaceSearchState is HuggingFaceSearchUiState.Idle)
    }

    @Test
    fun `blank hugging face search clears state without calling acquisition`() = runTest(dispatcher) {
        val acquisition = FakeHuggingFaceModelAcquisition(searchResults = listOf(sampleHuggingFaceSearchResult()))
        val viewModel = ModelProvisioningViewModel(
            gateway = FakeProvisioningGateway(),
            huggingFaceModelAcquisition = acquisition,
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.searchHuggingFaceFiles("   ")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.huggingFaceSearchState is HuggingFaceSearchUiState.Idle)
        assertTrue(acquisition.searchQueries.isEmpty())
    }

    @Test
    fun `hugging face search failure exposes blocked state`() = runTest(dispatcher) {
        val acquisition = FakeHuggingFaceModelAcquisition(
            searchFailure = HuggingFaceAcquisitionException(
                reason = HuggingFaceAcquisitionBlockReason.NETWORK_ERROR,
                userMessage = "Search failed",
            ),
        )
        val viewModel = ModelProvisioningViewModel(
            gateway = FakeProvisioningGateway(),
            huggingFaceModelAcquisition = acquisition,
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.searchHuggingFaceFiles("qwen")
        advanceUntilIdle()

        val searchState = viewModel.uiState.value.huggingFaceSearchState
        assertTrue(searchState is HuggingFaceSearchUiState.Blocked)
        assertEquals("Search failed", searchState.message)
    }

    @Test
    fun `load and manual offload clears loaded model but preserves last used restore target`() = runTest(dispatcher) {
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
        assertNull(viewModel.uiState.value.lifecycle.loadedModel)
        assertEquals(
            RuntimeLoadedModel("qwen3.5-0.8b-q4", "1"),
            viewModel.uiState.value.lifecycle.lastUsedModel,
        )
        val visibleState = viewModel.modelLoadingState.value
        assertTrue(visibleState is ModelLoadingState.Idle)
        assertNull(visibleState.loadedModel)
        assertEquals(
            RuntimeLoadedModel("qwen3.5-0.8b-q4", "1"),
            visibleState.lastUsedModel,
        )
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
    var enqueueFailure: Throwable? = null
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
        enqueueFailure?.let { throw it }
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

private class FakeHuggingFaceModelAcquisition(
    private val candidate: HuggingFaceCandidate? = null,
    private val failure: HuggingFaceAcquisitionException? = null,
    private val searchResults: List<HuggingFaceSearchFileResult> = emptyList(),
    private val searchFailure: HuggingFaceAcquisitionException? = null,
    private val resolve: (suspend (String, String) -> HuggingFaceCandidate)? = null,
) : HuggingFaceModelAcquisition {
    val searchQueries = mutableListOf<String>()

    override fun supportedTargets(): List<HuggingFaceTargetModel> {
        return listOf(HuggingFaceTargetModel(modelId = "qwen3.5-0.8b-q4", displayName = "Qwen"))
    }

    override suspend fun searchFiles(query: String, limit: Int): List<HuggingFaceSearchFileResult> {
        searchFailure?.let { throw it }
        searchQueries += query
        return searchResults
    }

    override suspend fun resolveCandidate(input: String, targetModelId: String): HuggingFaceCandidate {
        failure?.let { throw it }
        resolve?.let { return it(input, targetModelId) }
        return requireNotNull(candidate)
    }
}

private class FakeHuggingFaceRecentModelStore : HuggingFaceRecentModelStore {
    private var models: List<HuggingFaceRecentModel> = emptyList()

    override fun list(): List<HuggingFaceRecentModel> = models

    override fun upsert(candidate: HuggingFaceCandidate, enqueuedAtEpochMs: Long) {
        val recent = candidate.toRecentModel(enqueuedAtEpochMs)
        models = models.filterNot { model -> model.id == recent.id } + recent
    }

    override fun remove(id: String) {
        models = models.filterNot { model -> model.id == id }
    }

    override fun clear() {
        models = emptyList()
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

private fun sampleHuggingFaceCandidate(version: ModelDistributionVersion): HuggingFaceCandidate {
    return HuggingFaceCandidate(
        reference = HuggingFaceModelReference(
            repoId = "owner/repo",
            revision = "main",
            filePath = "model.gguf",
        ),
        target = HuggingFaceTargetModel(
            modelId = version.modelId,
            displayName = "Qwen",
        ),
        displayName = "owner/repo / model.gguf",
        sha256 = version.expectedSha256,
        sizeBytes = version.fileSizeBytes,
        version = version,
    )
}

private fun sampleHuggingFaceSearchResult(): HuggingFaceSearchFileResult {
    return HuggingFaceSearchFileResult(
        reference = HuggingFaceModelReference(
            repoId = "owner/repo",
            revision = "main",
            filePath = "model.gguf",
        ),
        displayName = "owner/repo / model.gguf",
        modelCardUrl = "https://huggingface.co/owner/repo",
        downloads = 42L,
        likes = 7L,
        license = "apache-2.0",
        gated = false,
        private = false,
    )
}
