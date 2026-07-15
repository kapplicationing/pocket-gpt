package com.pocketagent.android.ui

import com.pocketagent.android.runtime.RuntimeProvisioningSnapshot
import com.pocketagent.android.runtime.ProvisionedModelState
import com.pocketagent.android.runtime.modelmanager.DownloadProcessingStage
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.DownloadTaskStatus
import com.pocketagent.android.runtime.modelmanager.DownloadVerificationPolicy
import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelDistributionModel
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.android.ui.state.ModelLoadingState
import com.pocketagent.core.model.ModelSourceKind
import com.pocketagent.runtime.RuntimeLoadedModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OnboardingSetupUiStateTest {
    @Test
    fun `download progress is exposed only during byte transfer`() {
        val state = resolve(download = task(status = DownloadTaskStatus.DOWNLOADING, progressBytes = 40L))

        assertEquals(OnboardingSetupPhase.DOWNLOADING, state.phase)
        assertEquals(0.4f, state.progress)
    }

    @Test
    fun `verification has a phase without fake transfer progress`() {
        val state = resolve(
            download = task(
                status = DownloadTaskStatus.VERIFYING,
                progressBytes = 100L,
                stage = DownloadProcessingStage.VERIFYING,
            ),
        )

        assertEquals(OnboardingSetupPhase.CHECKING, state.phase)
        assertNull(state.progress)
    }

    @Test
    fun `installed terminal moves to starting even while installation metadata remains`() {
        val state = resolve(
            download = task(
                status = DownloadTaskStatus.COMPLETED,
                progressBytes = 100L,
                stage = DownloadProcessingStage.INSTALLING,
            ),
        )

        assertEquals(OnboardingSetupPhase.STARTING, state.phase)
    }

    @Test
    fun `pending activation without scheduler task is preparing`() {
        val state = resolve(download = null, pending = MODEL_ID to VERSION)

        assertEquals(OnboardingSetupPhase.PREPARING, state.phase)
    }

    @Test
    fun `pending installed target is starting instead of offering another download start`() {
        val state = resolveOnboardingSetupUiState(
            defaultModelId = MODEL_ID,
            manifest = MANIFEST,
            provisioningSnapshot = installedSnapshot(),
            downloads = emptyList(),
            pendingActivation = MODEL_ID to VERSION,
            modelLoadingState = ModelLoadingState.Idle(),
            sendReady = false,
        )

        assertEquals(OnboardingSetupPhase.STARTING, state.phase)
    }

    @Test
    fun `queued automatic retry remains preparing instead of failing setup`() {
        val state = resolve(
            download = task(
                status = DownloadTaskStatus.QUEUED,
                progressBytes = 40L,
            ),
        )

        assertEquals(OnboardingSetupPhase.PREPARING, state.phase)
    }

    @Test
    fun `request in flight keeps setup preparing before intent is persisted`() {
        val state = resolveOnboardingSetupUiState(
            defaultModelId = MODEL_ID,
            manifest = MANIFEST,
            provisioningSnapshot = RuntimeProvisioningSnapshot.empty(),
            downloads = emptyList(),
            pendingActivation = null,
            modelLoadingState = ModelLoadingState.Idle(),
            sendReady = false,
            setupRequestInFlight = true,
        )

        assertEquals(OnboardingSetupPhase.PREPARING, state.phase)
        assertEquals(true, state.setupRequestInFlight)
    }

    @Test
    fun `loaded target is ready even after pending intent is cleared`() {
        val loaded = RuntimeLoadedModel(modelId = MODEL_ID, modelVersion = VERSION)
        val state = resolve(
            download = task(status = DownloadTaskStatus.COMPLETED, progressBytes = 100L),
            pending = null,
            loadingState = ModelLoadingState.Loaded(
                model = loaded,
                lastUsedModel = loaded,
                detail = null,
                readyAtEpochMs = 2L,
            ),
        )

        assertEquals(OnboardingSetupPhase.READY, state.phase)
        assertEquals(1f, state.progress)
    }

    @Test
    fun `loaded target remains starting until send checks are ready`() {
        val loaded = RuntimeLoadedModel(modelId = MODEL_ID, modelVersion = VERSION)
        val state = resolveOnboardingSetupUiState(
            defaultModelId = MODEL_ID,
            manifest = MANIFEST,
            provisioningSnapshot = RuntimeProvisioningSnapshot.empty(),
            downloads = emptyList(),
            pendingActivation = null,
            modelLoadingState = ModelLoadingState.Loaded(
                model = loaded,
                lastUsedModel = loaded,
                detail = null,
                readyAtEpochMs = 2L,
            ),
            sendReady = false,
        )

        assertEquals(OnboardingSetupPhase.STARTING, state.phase)
        assertNull(state.progress)
    }

    @Test
    fun `stale terminal task is ignored without setup intent`() {
        val state = resolve(
            download = task(status = DownloadTaskStatus.FAILED, progressBytes = 20L),
            pending = null,
        )

        assertEquals(OnboardingSetupPhase.NOT_STARTED, state.phase)
    }

    @Test
    fun `failed download asks for attention`() {
        val state = resolve(
            download = task(status = DownloadTaskStatus.FAILED, progressBytes = 20L),
        )

        assertEquals(OnboardingSetupPhase.NEEDS_ATTENTION, state.phase)
    }

    @Test
    fun `enqueue failure asks for attention without a scheduler task`() {
        val state = resolveOnboardingSetupUiState(
            defaultModelId = MODEL_ID,
            manifest = MANIFEST,
            provisioningSnapshot = RuntimeProvisioningSnapshot.empty(),
            downloads = emptyList(),
            pendingActivation = null,
            modelLoadingState = ModelLoadingState.Idle(),
            sendReady = false,
            setupFailureMessage = "Not enough storage",
        )

        assertEquals(OnboardingSetupPhase.NEEDS_ATTENTION, state.phase)
        assertEquals("Not enough storage", state.detail)
    }

    @Test
    fun `activation failure wins over a completed download while intent is retained`() {
        val state = resolveOnboardingSetupUiState(
            defaultModelId = MODEL_ID,
            manifest = MANIFEST,
            provisioningSnapshot = RuntimeProvisioningSnapshot.empty(),
            downloads = listOf(task(status = DownloadTaskStatus.COMPLETED, progressBytes = 100L)),
            pendingActivation = MODEL_ID to VERSION,
            modelLoadingState = ModelLoadingState.Idle(),
            sendReady = false,
            setupFailureMessage = "Could not activate the starter model",
        )

        assertEquals(OnboardingSetupPhase.NEEDS_ATTENTION, state.phase)
        assertEquals("Could not activate the starter model", state.detail)
    }

    private fun resolve(
        download: DownloadTaskState?,
        pending: Pair<String, String>? = MODEL_ID to VERSION,
        loadingState: ModelLoadingState = ModelLoadingState.Idle(),
    ): OnboardingSetupUiState {
        return resolveOnboardingSetupUiState(
            defaultModelId = MODEL_ID,
            manifest = MANIFEST,
            provisioningSnapshot = RuntimeProvisioningSnapshot.empty(),
            downloads = listOfNotNull(download),
            pendingActivation = pending,
            modelLoadingState = loadingState,
            sendReady = loadingState is ModelLoadingState.Loaded,
            setupFailureMessage = null,
        )
    }

    private fun task(
        status: DownloadTaskStatus,
        progressBytes: Long,
        stage: DownloadProcessingStage = DownloadProcessingStage.DOWNLOADING,
    ): DownloadTaskState {
        return DownloadTaskState(
            taskId = "task",
            modelId = MODEL_ID,
            version = VERSION,
            sourceKind = ModelSourceKind.BUILT_IN,
            displayName = "Qwen starter",
            downloadUrl = "https://example.test/qwen.gguf",
            expectedSha256 = "a".repeat(64),
            provenanceIssuer = "issuer",
            provenanceSignature = "signature",
            verificationPolicy = DownloadVerificationPolicy.INTEGRITY_ONLY,
            runtimeCompatibility = "android-arm64-v8a",
            processingStage = stage,
            status = status,
            progressBytes = progressBytes,
            totalBytes = 100L,
            updatedAtEpochMs = 1L,
            message = status.name,
        )
    }

    private fun installedSnapshot(): RuntimeProvisioningSnapshot {
        val installedVersion = ModelVersionDescriptor(
            modelId = MODEL_ID,
            version = VERSION,
            displayName = "Qwen starter",
            absolutePath = "/tmp/$MODEL_ID-$VERSION.gguf",
            sha256 = "a".repeat(64),
            provenanceIssuer = "issuer",
            provenanceSignature = "signature",
            runtimeCompatibility = "android-arm64-v8a",
            fileSizeBytes = 100L,
            importedAtEpochMs = 1L,
            isActive = true,
        )
        return RuntimeProvisioningSnapshot.empty().copy(
            models = listOf(
                ProvisionedModelState(
                    modelId = MODEL_ID,
                    displayName = "Qwen starter",
                    fileName = "$MODEL_ID.gguf",
                    absolutePath = installedVersion.absolutePath,
                    sha256 = installedVersion.sha256,
                    importedAtEpochMs = installedVersion.importedAtEpochMs,
                    activeVersion = VERSION,
                    installedVersions = listOf(installedVersion),
                ),
            ),
        )
    }

    companion object {
        private const val MODEL_ID = "qwen-starter"
        private const val VERSION = "q4"
        private val VERSION_ENTRY = ModelDistributionVersion(
            modelId = MODEL_ID,
            version = VERSION,
            downloadUrl = "https://example.test/qwen.gguf",
            expectedSha256 = "a".repeat(64),
            provenanceIssuer = "issuer",
            provenanceSignature = "signature",
            runtimeCompatibility = "android-arm64-v8a",
            fileSizeBytes = 100L,
        )
        private val MANIFEST = ModelDistributionManifest(
            models = listOf(
                ModelDistributionModel(
                    modelId = MODEL_ID,
                    displayName = "Qwen starter",
                    versions = listOf(VERSION_ENTRY),
                ),
            ),
        )
    }
}
