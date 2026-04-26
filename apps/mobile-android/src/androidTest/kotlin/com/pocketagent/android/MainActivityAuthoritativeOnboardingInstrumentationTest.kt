package com.pocketagent.android

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketagent.android.data.chat.SessionPersistence
import com.pocketagent.android.data.chat.StoredChatState
import com.pocketagent.android.runtime.ChatRuntimeService
import com.pocketagent.android.runtime.ProvisionedModelState
import com.pocketagent.android.runtime.ProvisioningAggregateState
import com.pocketagent.android.runtime.ProvisioningGateway
import com.pocketagent.android.runtime.ProvisioningMutationResult
import com.pocketagent.android.runtime.RuntimeModelImportResult
import com.pocketagent.android.runtime.RuntimeModelLifecycleSnapshot
import com.pocketagent.android.runtime.RuntimeProvisioningSnapshot
import com.pocketagent.android.runtime.modelmanager.DownloadPreferencesState
import com.pocketagent.android.runtime.modelmanager.DownloadRequestOptions
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.android.runtime.modelmanager.StorageSummary
import com.pocketagent.android.runtime.PresetModelMappingStore
import com.pocketagent.android.ui.ModelProvisioningViewModel
import com.pocketagent.android.ui.PocketAgentApp
import com.pocketagent.android.ui.ChatViewModel
import com.pocketagent.core.ChatResponse
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.nativebridge.ModelLifecycleErrorCode
import com.pocketagent.nativebridge.ModelLifecycleState
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.ImageAnalysisResult
import com.pocketagent.runtime.PreparedChatStream
import com.pocketagent.runtime.RuntimeLoadedModel
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult
import com.pocketagent.runtime.ToolExecutionResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityAuthoritativeOnboardingInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun onboardingFlowCompletesIntoChatSurface() {
        val harness = AuthoritativeOnboardingHarness()
        val runtimeGateway = AuthoritativeOnboardingRuntimeGateway(harness)
        val provisioningGateway = AuthoritativeOnboardingProvisioningGateway(harness)
        val viewModel = ChatViewModel(
            runtimeFacade = runtimeGateway,
            sessionPersistence = AuthoritativeInMemorySessionPersistence(
                initialState = StoredChatState(
                    onboardingCompleted = false,
                    advancedUnlocked = false,
                ),
            ),
            presetBackingStore = PresetModelMappingStore(composeRule.activity.applicationContext),
        )
        val provisioningViewModel = ModelProvisioningViewModel(provisioningGateway)

        composeRule.setContent {
            MaterialTheme {
                Surface {
                    PocketAgentApp(
                        viewModel = viewModel,
                        provisioningViewModel = provisioningViewModel,
                    )
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 10_000) {
            hasNodeWithText("Welcome")
        }
        composeRule.onNodeWithText("Welcome").assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding_next").performClick()
        composeRule.onNodeWithTag("onboarding_next").performClick()
        composeRule.onNodeWithTag("onboarding_get_started").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            !hasNodeWithText("Welcome") &&
                hasNodeWithTag("composer_input") &&
                hasNodeWithTag("send_button")
        }
        composeRule.onNodeWithTag("composer_input").assertIsDisplayed()
        composeRule.onNodeWithTag("send_button").assertIsDisplayed()
    }

    private fun hasNodeWithTag(tag: String): Boolean {
        return runCatching {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }.getOrDefault(false)
    }

    private fun hasNodeWithText(text: String): Boolean {
        return runCatching {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }.getOrDefault(false)
    }
}

private data class AuthoritativeOnboardingHarness(
    val modelId: String = "qwen3.5-0.8b-q4",
    val modelVersion: String = "v1",
    var loaded: Boolean = false,
)

private class AuthoritativeOnboardingRuntimeGateway(
    private val harness: AuthoritativeOnboardingHarness,
) : ChatRuntimeService {
    private var sessionCounter = 0
    private var routingMode: RoutingMode = RoutingMode.AUTO

    override fun createSession(): SessionId {
        sessionCounter += 1
        return SessionId("session-$sessionCounter")
    }

    override fun streamPreparedChat(prepared: PreparedChatStream): Flow<ChatStreamEvent> = flow {
        val request = prepared.runtimeRequest
        emit(
            ChatStreamEvent.Started(
                requestId = request.requestId,
                startedAtEpochMs = System.currentTimeMillis(),
            ),
        )
        if (!harness.loaded) {
            emit(
                ChatStreamEvent.Failed(
                    requestId = request.requestId,
                    errorCode = "MODEL_NOT_LOADED",
                    message = "Missing runtime model",
                    firstTokenMs = null,
                    completionMs = 8L,
                ),
            )
            return@flow
        }
        emit(
            ChatStreamEvent.Completed(
                requestId = request.requestId,
                response = ChatResponse(
                    sessionId = request.sessionId,
                    modelId = harness.modelId,
                    text = "runtime response",
                    firstTokenLatencyMs = 20,
                    totalLatencyMs = 45,
                ),
                finishReason = "completed",
                firstTokenMs = 20,
                completionMs = 45,
            ),
        )
    }

    override fun cancelGeneration(sessionId: SessionId): Boolean = true

    override fun cancelGenerationByRequest(requestId: String): Boolean = true

    override fun runTool(toolName: String, jsonArgs: String): ToolExecutionResult {
        return ToolExecutionResult.Success(content = "tool:$toolName")
    }

    override fun analyzeImage(imagePath: String, prompt: String): ImageAnalysisResult {
        return ImageAnalysisResult.Success(content = "image:$imagePath")
    }

    override fun exportDiagnostics(): String = "diag=ok"

    override fun setRoutingMode(mode: RoutingMode) {
        routingMode = mode
    }

    override fun getRoutingMode(): RoutingMode = routingMode

    override fun runStartupChecks(): List<String> {
        return if (harness.loaded) {
            emptyList()
        } else {
            listOf("missing runtime model: ${harness.modelId}")
        }
    }

    override fun restoreSession(sessionId: SessionId, turns: List<Turn>) = Unit

    override fun deleteSession(sessionId: SessionId): Boolean = true

    override fun runtimeBackend(): String? = "FAKE"

    override fun supportsGpuOffload(): Boolean = false
}

private class AuthoritativeOnboardingProvisioningGateway(
    private val harness: AuthoritativeOnboardingHarness,
) : ProvisioningGateway {
    private val downloads = MutableStateFlow<List<com.pocketagent.android.runtime.modelmanager.DownloadTaskState>>(emptyList())
    private val preferences = MutableStateFlow(DownloadPreferencesState())
    private val lifecycle = MutableStateFlow(
        RuntimeModelLifecycleSnapshot.initial().copy(
            state = ModelLifecycleState.UNLOADED,
            loadedModel = null,
            lastUsedModel = RuntimeLoadedModel(
                modelId = harness.modelId,
                modelVersion = harness.modelVersion,
            ),
        ),
    )
    private val aggregateState = MutableStateFlow(
        ProvisioningAggregateState(
            snapshot = currentSnapshot(),
            downloads = downloads.value,
            downloadPreferences = preferences.value,
            lifecycle = lifecycle.value,
        ),
    )

    override fun observeProvisioningAggregateState(): StateFlow<ProvisioningAggregateState> = aggregateState

    override suspend fun seedProvisioningAggregateState(): ProvisioningAggregateState = aggregateState.value

    override suspend fun importModelFromUri(modelId: String, sourceUri: Uri): RuntimeModelImportResult {
        return RuntimeModelImportResult(
            modelId = modelId,
            version = harness.modelVersion,
            absolutePath = "/tmp/$modelId.gguf",
            sha256 = "a".repeat(64),
            copiedBytes = 128L,
            isActive = true,
        )
    }

    override fun listInstalledVersions(modelId: String): List<ModelVersionDescriptor> {
        return currentSnapshot().models.firstOrNull { it.modelId == modelId }?.installedVersions.orEmpty()
    }

    override fun setActiveVersion(modelId: String, version: String): ProvisioningMutationResult =
        ProvisioningMutationResult.Applied

    override fun clearActiveVersion(modelId: String): ProvisioningMutationResult =
        ProvisioningMutationResult.Applied

    override fun removeVersion(modelId: String, version: String): ProvisioningMutationResult =
        ProvisioningMutationResult.Applied

    override suspend fun loadInstalledModel(modelId: String, version: String): RuntimeModelLifecycleCommandResult {
        val loadedModel = RuntimeLoadedModel(modelId = modelId, modelVersion = version)
        harness.loaded = true
        lifecycle.value = lifecycle.value.copy(
            state = ModelLifecycleState.LOADED,
            loadedModel = loadedModel,
            requestedModel = null,
            lastUsedModel = loadedModel,
            errorCode = null,
            errorDetail = null,
        )
        syncAggregateState()
        return RuntimeModelLifecycleCommandResult.applied(loadedModel = loadedModel)
    }

    override suspend fun loadLastUsedModel(): RuntimeModelLifecycleCommandResult {
        val lastUsed = lifecycle.value.lastUsedModel ?: return RuntimeModelLifecycleCommandResult.rejected(
            code = ModelLifecycleErrorCode.MODEL_FILE_UNAVAILABLE,
            detail = "last_loaded_model_missing",
        )
        return loadInstalledModel(
            modelId = lastUsed.modelId,
            version = lastUsed.modelVersion.orEmpty(),
        )
    }

    override suspend fun offloadModel(reason: String): RuntimeModelLifecycleCommandResult {
        harness.loaded = false
        lifecycle.value = lifecycle.value.copy(
            state = ModelLifecycleState.UNLOADED,
            loadedModel = null,
            requestedModel = null,
            errorCode = null,
            errorDetail = null,
            queuedOffload = false,
        )
        syncAggregateState()
        return RuntimeModelLifecycleCommandResult.applied()
    }

    override suspend fun enqueueDownload(
        version: ModelDistributionVersion,
        options: DownloadRequestOptions,
    ): String = "task-1"

    override fun shouldWarnForMeteredLargeDownload(version: ModelDistributionVersion): Boolean = false

    override fun setDownloadWifiOnlyEnabled(enabled: Boolean) = Unit

    override fun acknowledgeLargeDownloadCellularWarning() = Unit

    override fun pauseDownload(taskId: String) = Unit

    override fun resumeDownload(taskId: String) = Unit

    override fun retryDownload(taskId: String) = Unit

    override fun cancelDownload(taskId: String) = Unit

    override fun syncDownloadsFromScheduler() = Unit

    private fun currentSnapshot(): RuntimeProvisioningSnapshot {
        return RuntimeProvisioningSnapshot(
            models = listOf(
                ProvisionedModelState(
                    modelId = harness.modelId,
                    displayName = "Qwen",
                    fileName = "qwen.gguf",
                    absolutePath = "/tmp/qwen.gguf",
                    sha256 = "a".repeat(64),
                    importedAtEpochMs = 1L,
                    activeVersion = harness.modelVersion,
                    installedVersions = listOf(
                        ModelVersionDescriptor(
                            modelId = harness.modelId,
                            version = harness.modelVersion,
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
            requiredModelIds = setOf(harness.modelId),
        )
    }

    private fun syncAggregateState() {
        aggregateState.value = aggregateState.value.copy(
            snapshot = currentSnapshot(),
            downloads = downloads.value,
            downloadPreferences = preferences.value,
            lifecycle = lifecycle.value,
        )
    }
}

private class AuthoritativeInMemorySessionPersistence(
    initialState: StoredChatState = StoredChatState(),
) : SessionPersistence {
    private var current = initialState

    override fun loadState(): StoredChatState = current

    override fun saveState(state: StoredChatState) {
        current = state
    }
}
