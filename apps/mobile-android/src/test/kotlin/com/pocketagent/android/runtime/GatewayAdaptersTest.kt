package com.pocketagent.android.runtime

import android.net.Uri
import com.pocketagent.android.runtime.modelmanager.DownloadPreferencesState
import com.pocketagent.android.runtime.modelmanager.DownloadNetworkPreference
import com.pocketagent.android.runtime.modelmanager.DownloadRequestOptions
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.DownloadTaskStatus
import com.pocketagent.android.runtime.modelmanager.DownloadVerificationPolicy
import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.android.runtime.modelmanager.StorageSummary
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.core.ChatResponse
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.ChatStreamDelta
import com.pocketagent.runtime.ChatStreamEvent.Delta
import com.pocketagent.runtime.ChatStreamEvent.Started
import com.pocketagent.runtime.ChatStreamEvent.Completed
import com.pocketagent.runtime.ChatStreamPlan
import com.pocketagent.runtime.ImageAnalysisResult
import com.pocketagent.runtime.InteractionContentPart
import com.pocketagent.runtime.InteractionMessage
import com.pocketagent.runtime.InteractionRole
import com.pocketagent.runtime.MvpRuntimeFacade
import com.pocketagent.runtime.PerformanceRuntimeConfig
import com.pocketagent.runtime.PreparedChatStream
import com.pocketagent.runtime.RuntimeLoadedModel
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult
import com.pocketagent.runtime.RuntimePerformanceProfile
import com.pocketagent.runtime.RuntimeWarmupSupport
import com.pocketagent.runtime.StreamChatRequestV2
import com.pocketagent.runtime.ToolExecutionResult
import com.pocketagent.runtime.WarmupResult
import com.pocketagent.android.testutil.fakeUri
import com.pocketagent.nativebridge.ModelLifecycleErrorCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GatewayAdaptersTest {
    @Test
    fun `mvp runtime gateway delegates typed calls to facade`() = runTest {
        val facade = RecordingMvpRuntimeFacade()
        val gateway = MvpRuntimeGateway(
            facade = facade,
            runtimeTuning = object : RuntimeTuning {
                override fun diagnosticsReport(): String = "RUNTIME_TUNING|model=qwen|benchmark_win_count=2"
            },
        )
        val sessionId = gateway.createSession()
        gateway.setRoutingMode(RoutingMode.QWEN3_1_7B)

        val streamEvents = gateway.streamPreparedChat(
            preparedRequest(
                StreamChatRequestV2(
                sessionId = sessionId,
                messages = listOf(
                    InteractionMessage(
                        role = InteractionRole.USER,
                        parts = listOf(InteractionContentPart.Text("hello")),
                    ),
                ),
                taskType = "short_text",
                deviceState = com.pocketagent.inference.DeviceState(80, 3, 8),
                ),
            ),
        ).toList()
        val tool = gateway.runTool("calculator", """{"expression":"1+1"}""")
        val image = gateway.analyzeImage("/tmp/a.jpg", "describe")
        val checks = gateway.runStartupChecks()
        val diagnostics = gateway.exportDiagnostics()
        val runtimeSnapshot = gateway.runtimeDiagnosticsSnapshot()

        assertEquals(RoutingMode.QWEN3_1_7B, gateway.getRoutingMode())
        assertEquals("session-1", sessionId.value)
        assertEquals(3, streamEvents.size)
        assertTrue(tool is ToolExecutionResult.Success)
        assertTrue(image is ImageAnalysisResult.Success)
        assertEquals(listOf("ok"), checks)
        assertTrue(diagnostics.startsWith("diag=ok"))
        assertTrue(diagnostics.contains("GPU_OFFLOAD|runtime_supported="))
        assertTrue(diagnostics.contains("RUNTIME_TUNING|model=qwen|benchmark_win_count=2"))
        assertEquals(null, runtimeSnapshot.strictAcceleratorFailFast)
        assertEquals(null, runtimeSnapshot.autoBackendCpuFallback)
        assertEquals("calculator", facade.lastToolName)
        assertEquals("/tmp/a.jpg", facade.lastImagePath)
    }

    @Test
    fun `default provisioning gateway delegates to dependency access seam`() = runTest {
        val dependency = RecordingProvisioningDependencyAccess()
        val gateway = DefaultProvisioningGateway(
            dependencies = dependency,
            coroutineScope = backgroundScope,
        )
        val version = ModelDistributionVersion(
            modelId = "qwen3.5-0.8b-q4",
            version = "1",
            downloadUrl = "https://example.com/model.gguf",
            expectedSha256 = "a".repeat(64),
            provenanceIssuer = "issuer",
            provenanceSignature = "sig",
            runtimeCompatibility = "android-arm64-v8a",
            fileSizeBytes = 123L,
        )

        assertEquals("task-1", gateway.enqueueDownload(version))
        gateway.pauseDownload("task-1")
        gateway.resumeDownload("task-1")
        gateway.retryDownload("task-1")
        gateway.cancelDownload("task-1")
        assertTrue(gateway.setActiveVersion(version.modelId, version.version))
        assertTrue(gateway.removeVersion(version.modelId, version.version))
        val imported = gateway.importModelFromUri(version.modelId, fakeUri())
        val manifest = gateway.loadModelDistributionManifest()
        val installed = gateway.listInstalledVersions(version.modelId)
        val snapshot = gateway.currentSnapshot()

        assertEquals(version.modelId, imported.modelId)
        assertEquals(1, manifest.models.size)
        assertEquals(1, installed.size)
        assertEquals(1, snapshot.models.size)
        assertEquals(1, gateway.observeDownloads().value.size)
        assertEquals("task-1", dependency.lastPausedTaskId)
        assertEquals("task-1", dependency.lastResumedTaskId)
        assertEquals("task-1", dependency.lastRetriedTaskId)
        assertEquals("task-1", dependency.lastCancelledTaskId)
    }

    @Test
    fun `default provisioning gateway delegates download preference controls and request options`() = runTest {
        val dependency = RecordingProvisioningDependencyAccess().apply {
            meteredWarningResult = true
        }
        val gateway = DefaultProvisioningGateway(
            dependencies = dependency,
            coroutineScope = backgroundScope,
        )
        val version = ModelDistributionVersion(
            modelId = "qwen3.5-0.8b-q4",
            version = "2",
            downloadUrl = "https://example.com/model-v2.gguf",
            expectedSha256 = "b".repeat(64),
            provenanceIssuer = "issuer",
            provenanceSignature = "sig",
            runtimeCompatibility = "android-arm64-v8a",
            fileSizeBytes = 2L * 1024L * 1024L * 1024L,
        )
        val options = DownloadRequestOptions(
            networkPreference = DownloadNetworkPreference.UNMETERED_ONLY,
            userInitiated = false,
        )

        assertEquals("task-1", gateway.enqueueDownload(version, options))
        assertEquals(options, dependency.lastEnqueuedOptions)
        assertTrue(gateway.shouldWarnForMeteredLargeDownload(version))
        assertEquals(version, dependency.lastWarningVersion)

        gateway.setDownloadWifiOnlyEnabled(true)
        gateway.acknowledgeLargeDownloadCellularWarning()

        assertTrue(gateway.currentDownloadPreferences().wifiOnlyEnabled)
        assertTrue(gateway.currentDownloadPreferences().largeDownloadCellularWarningAcknowledged)
    }

    @Test
    fun `default provisioning gateway blocks disallowed admission actions`() = runTest {
        val dependency = RecordingProvisioningDependencyAccess()
        val gateway = DefaultProvisioningGateway(
            dependencies = dependency,
            admissionPolicy = blockedAdmissionPolicy(
                reason = ModelEligibilityReason.GPU_RUNTIME_UNAVAILABLE,
            ),
            coroutineScope = backgroundScope,
        )
        val version = ModelDistributionVersion(
            modelId = "qwen3-1.7b-q4_k_m",
            version = "q4_k_m",
            downloadUrl = "https://example.com/qwen3-1.7b.gguf",
            expectedSha256 = "c".repeat(64),
            provenanceIssuer = "issuer",
            provenanceSignature = "sig",
            runtimeCompatibility = "android-arm64-v8a",
            fileSizeBytes = 123L,
        )

        val downloadError = assertFailsWith<RuntimeDomainException> {
            gateway.enqueueDownload(version)
        }
        val loadResult = gateway.loadInstalledModel("qwen3.5-0.8b-q4", "1")

        assertEquals(RuntimeErrorCodes.MODEL_ADMISSION_BLOCKED, downloadError.domainError.code)
        assertEquals(ModelLifecycleErrorCode.RUNTIME_INCOMPATIBLE, loadResult.errorCode)
        assertTrue(loadResult.detail.orEmpty().contains("action=load"))
    }

    @Test
    fun `mvp runtime gateway keeps gpu disabled while probe is pending`() {
        val facade = RecordingMvpRuntimeFacade()
        val gateway = MvpRuntimeGateway(
            facade = facade,
            deviceGpuOffloadSupport = fakeDeviceGpuOffloadSupport(true),
            gpuOffloadQualifier = FakeGpuQualifier(
                resultWhenRuntimeSupported = GpuProbeResult(
                    status = GpuProbeStatus.PENDING,
                    detail = "probe_running",
                ),
            ),
        )

        assertFalse(gateway.supportsGpuOffload())
    }

    @Test
    fun `mvp runtime gateway disables gpu offload when probe fails`() {
        val facade = RecordingMvpRuntimeFacade(gpuSupported = true)
        val gateway = MvpRuntimeGateway(
            facade = facade,
            deviceGpuOffloadSupport = fakeDeviceGpuOffloadSupport(true),
            gpuOffloadQualifier = FakeGpuQualifier(
                resultWhenRuntimeSupported = GpuProbeResult(
                    status = GpuProbeStatus.FAILED,
                    failureReason = GpuProbeFailureReason.NATIVE_LOAD_FAILED,
                ),
            ),
        )

        assertFalse(gateway.supportsGpuOffload())
    }

    @Test
    fun `mvp runtime gateway enables gpu offload when probe qualifies runtime`() {
        val facade = RecordingMvpRuntimeFacade(gpuSupported = true)
        val gateway = MvpRuntimeGateway(
            facade = facade,
            deviceGpuOffloadSupport = fakeDeviceGpuOffloadSupport(false),
            gpuOffloadQualifier = FakeGpuQualifier(
                resultWhenRuntimeSupported = GpuProbeResult(
                    status = GpuProbeStatus.QUALIFIED,
                    maxStableGpuLayers = 8,
                ),
            ),
        )

        assertTrue(gateway.supportsGpuOffload())
        assertEquals(8, gateway.gpuOffloadStatus().maxStableGpuLayers)
    }

    @Test
    fun `mvp runtime gateway delegates warmup to facade when supported`() {
        val facade = RecordingMvpRuntimeFacade(
            warmupResult = WarmupResult(
                attempted = true,
                warmed = true,
                residentHit = false,
                warmupDurationMs = 12L,
            ),
        )
        val gateway = MvpRuntimeGateway(facade = facade)

        val result = gateway.warmupActiveModel()

        assertTrue(result.attempted)
        assertTrue(result.warmed)
        assertEquals(1, facade.warmupCalls)
    }

    @Test
    fun `mvp runtime gateway demotes qualified gpu after remote process death`() = runTest {
        val qualifier = FakeGpuQualifier(
            resultWhenRuntimeSupported = GpuProbeResult(
                status = GpuProbeStatus.QUALIFIED,
                maxStableGpuLayers = 8,
            ),
        )
        val facade = RecordingMvpRuntimeFacade(
            gpuSupported = true,
            streamChatEvents = flowOf(
                Started(requestId = "req-remote", startedAtEpochMs = 1L),
                ChatStreamEvent.Failed(
                    requestId = "req-remote",
                    errorCode = "REMOTE_PROCESS_DIED",
                    message = "remote runtime disconnected during gpu generation",
                ),
            ),
        )
        val gateway = MvpRuntimeGateway(
            facade = facade,
            deviceGpuOffloadSupport = fakeDeviceGpuOffloadSupport(true),
            gpuOffloadQualifier = qualifier,
        )

        gateway.streamPreparedChat(
            preparedRequest(
                StreamChatRequestV2(
                sessionId = SessionId("session-remote"),
                messages = listOf(
                    InteractionMessage(
                        role = InteractionRole.USER,
                        parts = listOf(InteractionContentPart.Text("hello")),
                    ),
                ),
                taskType = "short_text",
                deviceState = com.pocketagent.inference.DeviceState(80, 3, 8),
                performanceConfig = PerformanceRuntimeConfig.forProfile(
                    profile = RuntimePerformanceProfile.BALANCED,
                    availableCpuThreads = 4,
                    gpuEnabled = true,
                    gpuLayers = 8,
                ),
                ),
            ),
        ).toList()

        assertEquals(1, qualifier.reportedFailures.size)
        assertEquals(GpuProbeFailureReason.NATIVE_GENERATE_FAILED, qualifier.reportedFailures.single().first)
    }

    @Test
    fun `mvp runtime gateway demotes qualified gpu after gpu stream failure`() = runTest {
        val qualifier = FakeGpuQualifier(
            resultWhenRuntimeSupported = GpuProbeResult(
                status = GpuProbeStatus.QUALIFIED,
                maxStableGpuLayers = 8,
            ),
        )
        val facade = RecordingMvpRuntimeFacade(
            gpuSupported = true,
            streamChatEvents = flowOf(
                Started(requestId = "req-1", startedAtEpochMs = 1L),
                ChatStreamEvent.Failed(
                    requestId = "req-1",
                    errorCode = "JNI_RUNTIME_ERROR",
                    message = "native stream failure",
                ),
            ),
        )
        val gateway = MvpRuntimeGateway(
            facade = facade,
            deviceGpuOffloadSupport = fakeDeviceGpuOffloadSupport(true),
            gpuOffloadQualifier = qualifier,
        )

        gateway.streamPreparedChat(
            preparedRequest(
                StreamChatRequestV2(
                sessionId = SessionId("session-1"),
                messages = listOf(
                    InteractionMessage(
                        role = InteractionRole.USER,
                        parts = listOf(InteractionContentPart.Text("hello")),
                    ),
                ),
                taskType = "short_text",
                deviceState = com.pocketagent.inference.DeviceState(80, 3, 8),
                performanceConfig = PerformanceRuntimeConfig.forProfile(
                    profile = RuntimePerformanceProfile.BALANCED,
                    availableCpuThreads = 4,
                    gpuEnabled = true,
                    gpuLayers = 8,
                ),
                ),
            ),
        ).toList()

        assertEquals(1, qualifier.reportedFailures.size)
        assertEquals(GpuProbeFailureReason.NATIVE_GENERATE_FAILED, qualifier.reportedFailures.single().first)
    }
}

private class FakeGpuQualifier(
    private val resultWhenRuntimeSupported: GpuProbeResult,
) : GpuOffloadQualifier {
    val reportedFailures: MutableList<Pair<GpuProbeFailureReason, String?>> = mutableListOf()

    override fun evaluate(
        runtimeSupported: Boolean,
        deviceAdvisory: DeviceGpuOffloadAdvisory,
    ): GpuProbeResult {
        return if (runtimeSupported) {
            resultWhenRuntimeSupported
        } else {
            GpuProbeResult(
                status = GpuProbeStatus.FAILED,
                failureReason = GpuProbeFailureReason.RUNTIME_UNSUPPORTED,
            )
        }
    }

    override fun diagnosticsLine(): String = "GPU_PROBE|status=fake"

    override fun reportRuntimeFailure(reason: GpuProbeFailureReason, detail: String?) {
        reportedFailures += reason to detail
    }
}

private fun fakeDeviceGpuOffloadSupport(supported: Boolean): DeviceGpuOffloadSupport {
    return object : DeviceGpuOffloadSupport {
        override fun advisory(): DeviceGpuOffloadAdvisory = DeviceGpuOffloadAdvisory(
            supportedForProbe = supported,
            automaticOpenClEligible = supported,
            reason = if (supported) "fake_supported" else "fake_unsupported",
        )
    }
}

private fun preparedRequest(request: StreamChatRequestV2): PreparedChatStream {
    return PreparedChatStream(
        plan = ChatStreamPlan(
            requestId = request.requestId,
            requestTimeoutMs = request.requestTimeoutMs,
            baseConfig = request.performanceConfig,
            effectiveConfig = request.performanceConfig,
        ),
        runtimeRequest = request,
    )
}

private class RecordingMvpRuntimeFacade(
    private val gpuSupported: Boolean = true,
    private val streamChatEvents: Flow<ChatStreamEvent>? = null,
    private val warmupResult: WarmupResult = WarmupResult.skipped("warmup_unsupported"),
) : MvpRuntimeFacade, RuntimeWarmupSupport {
    private var currentRoutingMode: RoutingMode = RoutingMode.AUTO
    var lastToolName: String? = null
    var lastImagePath: String? = null
    var warmupCalls: Int = 0

    override fun createSession(): SessionId = SessionId("session-1")

    override fun streamChat(request: StreamChatRequestV2): Flow<ChatStreamEvent> {
        streamChatEvents?.let { return it }
        return flowOf(
            Started(requestId = request.requestId, startedAtEpochMs = 1L),
            Delta(
                requestId = request.requestId,
                delta = ChatStreamDelta.TextDelta("hi "),
                accumulatedText = "hi",
            ),
            Completed(
                requestId = request.requestId,
                response = ChatResponse(
                    sessionId = request.sessionId,
                    modelId = "auto",
                    text = "ok",
                    firstTokenLatencyMs = 1L,
                    totalLatencyMs = 2L,
                ),
                finishReason = "completed",
            ),
        )
    }

    override fun cancelGeneration(sessionId: SessionId): Boolean = true

    override fun cancelGenerationByRequest(requestId: String): Boolean = true

    override fun runTool(toolName: String, jsonArgs: String): String = "legacy"

    override fun runToolDetailed(toolName: String, jsonArgs: String): ToolExecutionResult {
        lastToolName = toolName
        return ToolExecutionResult.Success("tool:$toolName")
    }

    override fun analyzeImage(imagePath: String, prompt: String): String = "legacy"

    override fun analyzeImageDetailed(imagePath: String, prompt: String): ImageAnalysisResult {
        lastImagePath = imagePath
        return ImageAnalysisResult.Success("image:$imagePath")
    }

    override fun exportDiagnostics(): String = "diag=ok"

    override fun setRoutingMode(mode: RoutingMode) {
        currentRoutingMode = mode
    }

    override fun getRoutingMode(): RoutingMode = currentRoutingMode

    override fun runStartupChecks(): List<String> = listOf("ok")

    override fun restoreSession(sessionId: SessionId, turns: List<Turn>) = Unit

    override fun deleteSession(sessionId: SessionId): Boolean = true

    override fun runtimeBackend(): String = "NATIVE_JNI"

    override fun supportsGpuOffload(): Boolean = gpuSupported

    override fun warmupActiveModel(): WarmupResult {
        warmupCalls += 1
        return warmupResult
    }
}

private class RecordingProvisioningDependencyAccess : ProvisioningDependencyAccess {
    private val downloads = MutableStateFlow(
        listOf(
            DownloadTaskState(
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
                progressBytes = 10L,
                totalBytes = 100L,
                updatedAtEpochMs = 1L,
            ),
        ),
    )
    private val lifecycle = MutableStateFlow(RuntimeModelLifecycleSnapshot.initial())
    private val downloadPreferences = MutableStateFlow(DownloadPreferencesState())

    var lastPausedTaskId: String? = null
    var lastResumedTaskId: String? = null
    var lastRetriedTaskId: String? = null
    var lastCancelledTaskId: String? = null
    var lastEnqueuedOptions: DownloadRequestOptions? = null
    var meteredWarningResult: Boolean = false
    var lastWarningVersion: ModelDistributionVersion? = null

    override fun currentProvisioningSnapshot(): RuntimeProvisioningSnapshot {
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

    override fun observeDownloads() = downloads

    override fun observeDownloadPreferences() = downloadPreferences

    override fun currentDownloadPreferences(): DownloadPreferencesState = downloadPreferences.value

    override fun observeModelLifecycle() = lifecycle

    override fun currentModelLifecycle(): RuntimeModelLifecycleSnapshot = lifecycle.value

    override suspend fun importModelFromUri(
        modelId: String,
        sourceUri: Uri,
    ): RuntimeModelImportResult {
        return RuntimeModelImportResult(
            modelId = modelId,
            version = "1",
            absolutePath = "/tmp/model.gguf",
            sha256 = "a".repeat(64),
            copiedBytes = 123L,
            isActive = true,
        )
    }

    override suspend fun loadModelDistributionManifest(): ModelDistributionManifest {
        return ModelDistributionManifest(
            models = listOf(
                com.pocketagent.android.runtime.modelmanager.ModelDistributionModel(
                    modelId = "qwen3.5-0.8b-q4",
                    displayName = "Qwen",
                    versions = emptyList(),
                ),
            ),
        )
    }

    override fun listInstalledVersions(modelId: String): List<ModelVersionDescriptor> {
        return currentProvisioningSnapshot().models.first().installedVersions
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
        return RuntimeModelLifecycleCommandResult.rejected(
            code = ModelLifecycleErrorCode.MODEL_FILE_UNAVAILABLE,
            detail = "last_loaded_model_missing",
        )
    }

    override suspend fun offloadModel(reason: String): RuntimeModelLifecycleCommandResult {
        return RuntimeModelLifecycleCommandResult.applied()
    }

    override suspend fun enqueueDownload(version: ModelDistributionVersion, options: DownloadRequestOptions): String {
        lastEnqueuedOptions = options
        return "task-1"
    }

    override fun shouldWarnForMeteredLargeDownload(version: ModelDistributionVersion): Boolean {
        lastWarningVersion = version
        return meteredWarningResult
    }

    override fun setDownloadWifiOnlyEnabled(enabled: Boolean) {
        downloadPreferences.value = downloadPreferences.value.copy(wifiOnlyEnabled = enabled)
    }

    override fun acknowledgeLargeDownloadCellularWarning() {
        downloadPreferences.value = downloadPreferences.value.copy(
            largeDownloadCellularWarningAcknowledged = true,
        )
    }

    override fun pauseDownload(taskId: String) {
        lastPausedTaskId = taskId
    }

    override fun resumeDownload(taskId: String) {
        lastResumedTaskId = taskId
    }

    override fun retryDownload(taskId: String) {
        lastRetriedTaskId = taskId
    }

    override fun cancelDownload(taskId: String) {
        lastCancelledTaskId = taskId
    }

    override fun syncDownloadsFromScheduler() = Unit
}

private fun blockedAdmissionPolicy(reason: ModelEligibilityReason): ModelAdmissionPolicy {
    return object : ModelAdmissionPolicy {
        override fun evaluate(
            action: ModelAdmissionAction,
            subject: ModelAdmissionSubject,
        ): ModelAdmissionDecision {
            return ModelAdmissionDecision(
                action = action,
                subject = subject,
                eligibility = ModelVersionEligibility.unsupported(
                    reason = reason,
                    technicalDetail = "blocked_for_test",
                ),
            )
        }
    }
}
