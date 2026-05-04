package com.pocketagent.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketagent.android.runtime.ModelPathOrigin
import com.pocketagent.android.runtime.ProvisionedModelState
import com.pocketagent.android.runtime.RuntimeModelLifecycleSnapshot
import com.pocketagent.android.runtime.RuntimeProvisioningSnapshot
import com.pocketagent.android.runtime.modelmanager.DownloadProcessingStage
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.DownloadTaskStatus
import com.pocketagent.android.runtime.modelmanager.DownloadVerificationPolicy
import com.pocketagent.android.runtime.modelmanager.ManifestSource
import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelDistributionModel
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.android.runtime.modelmanager.StorageSummary
import com.pocketagent.core.RoutingMode
import com.pocketagent.nativebridge.ModelLifecycleState
import com.pocketagent.android.ui.state.ModelLoadingState
import com.pocketagent.runtime.RuntimeLoadedModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelManagementSheetComposeContractTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val presetBackingStore = FakePresetBackingStore()

    @Test
    fun productionModelSheetRendersAndDispatchesRefreshEvent() {
        val events = mutableListOf<ModelSheetEvent>()

        composeRule.setContent {
            MaterialTheme {
                ModelSheet(
                    libraryState = sampleLibraryState(),
                    runtimeState = sampleRuntimeState(),
                    modelLoadingState = sampleRuntimeLoadingState(),
                    routingMode = RoutingMode.AUTO,
                    presetBackingStore = presetBackingStore,
                    onEvent = { events += it },
                )
            }
        }

        composeRule.onNodeWithTag("unified_model_sheet").assertIsDisplayed()
        composeRule.onNodeWithText("Downloaded models").assertIsDisplayed()
        composeRule.onNodeWithText("Available models").assertIsDisplayed()
        composeRule.onNodeWithText("Refresh").performClick()
        composeRule.runOnIdle {
            assertTrue(events.contains(ModelSheetEvent.RefreshAll))
        }
    }

    @Test
    fun modelLibrarySheetShowsArtifactActionsWithoutRuntimeControls() {
        var openedRuntimeControls = false

        composeRule.setContent {
            MaterialTheme {
                TestModelLibrarySheet(
                    state = sampleLibraryState(),
                    onOpenRuntimeControls = { openedRuntimeControls = true },
                )
            }
        }

        composeRule.onNodeWithText("Model library").assertIsDisplayed()
        composeRule.onNodeWithText("Downloads").assertIsDisplayed()
        composeRule.onNodeWithText("Installed versions").assertIsDisplayed()
        composeRule.onNodeWithTag("model_library_list")
            .performScrollToNode(hasText("Open runtime controls"))
        composeRule.onNodeWithText("Open runtime controls").performClick()
        composeRule.runOnIdle {
            assertTrue(openedRuntimeControls)
        }
        assertTrue(composeRule.onAllNodesWithText("Load now").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("Offload").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun runtimeModelSheetShowsRuntimeActionsWithoutDownloadManager() {
        var openedLibrary = false

        composeRule.setContent {
            MaterialTheme {
                TestRuntimeModelSheet(
                    state = sampleRuntimeState(),
                    onOpenModelLibrary = { openedLibrary = true },
                )
            }
        }

        composeRule.onNodeWithText("Runtime model").assertIsDisplayed()
        composeRule.onNodeWithText("Active model").assertIsDisplayed()
        composeRule.onNodeWithText("Offload").assertIsDisplayed()
        composeRule.onNodeWithText("Open model library").performClick()
        composeRule.runOnIdle {
            assertTrue(openedLibrary)
        }
        assertTrue(composeRule.onAllNodesWithText("Downloads").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("Start download").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun runtimeModelSheetShowsEmptyStateWhenNothingIsInstalled() {
        composeRule.setContent {
            MaterialTheme {
                TestRuntimeModelSheet(
                    state = sampleRuntimeState(
                        snapshot = sampleSnapshot(installed = false),
                        lifecycle = RuntimeModelLifecycleSnapshot.initial(),
                    ),
                    onOpenModelLibrary = {},
                )
            }
        }

        composeRule.onNodeWithText("No installed models yet").assertIsDisplayed()
        composeRule.onNodeWithText("Open model library").assertIsDisplayed()
    }

    @Test
    fun removedVersionStateShowsVisibleFeedbackAndAvailableDownloadAction() {
        val removedMessage =
            "qwen3.5-0.8b-q4 (q4_0) removed from this device. If it still appears below, you can download it again."
        val removedState = sampleLibraryState().copy(
            snapshot = sampleSnapshot(installed = false),
            downloads = emptyList(),
            statusMessage = removedMessage,
        )
        composeRule.setContent {
            MaterialTheme {
                ModelSheet(
                    libraryState = removedState,
                    runtimeState = sampleRuntimeState(
                        snapshot = removedState.snapshot,
                        lifecycle = RuntimeModelLifecycleSnapshot.initial(),
                    ),
                    modelLoadingState = ModelLoadingState.Idle(),
                    routingMode = RoutingMode.AUTO,
                    presetBackingStore = presetBackingStore,
                    onEvent = {},
                )
            }
        }

        composeRule.onNodeWithTag("model_sheet_status_message").assertIsDisplayed()
        composeRule.onNodeWithText(removedMessage).assertIsDisplayed()
        composeRule.onNodeWithText("No downloaded models yet").assertIsDisplayed()
        composeRule.onNodeWithTag("unified_model_sheet").performScrollToNode(hasText("Download"))
        composeRule.onNodeWithText("Download").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("Load").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun statusCardAppearsWhenMessageSetAndDisappearsWhenNull() {
        val stateWithMessage = sampleLibraryState().copy(statusMessage = "Model activated")
        composeRule.setContent {
            MaterialTheme {
                ModelSheet(
                    libraryState = stateWithMessage,
                    runtimeState = sampleRuntimeState(),
                    modelLoadingState = sampleRuntimeLoadingState(),
                    routingMode = RoutingMode.AUTO,
                    presetBackingStore = presetBackingStore,
                    onEvent = {},
                )
            }
        }

        composeRule.onNodeWithTag("model_sheet_status_message").assertIsDisplayed()
        composeRule.onNodeWithText("Model activated").assertIsDisplayed()
    }

    @Test
    fun statusCardHiddenWhenMessageIsNull() {
        val stateNoMessage = sampleLibraryState().copy(statusMessage = null)
        composeRule.setContent {
            MaterialTheme {
                ModelSheet(
                    libraryState = stateNoMessage,
                    runtimeState = sampleRuntimeState(),
                    modelLoadingState = sampleRuntimeLoadingState(),
                    routingMode = RoutingMode.AUTO,
                    presetBackingStore = presetBackingStore,
                    onEvent = {},
                )
            }
        }

        assertTrue(
            composeRule.onAllNodes(hasTestTag("model_sheet_status_message"))
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun errorStateShowsRetryAndChooseAnotherActions() {
        val errorModel = RuntimeLoadedModel(modelId = "qwen3.5-0.8b-q4", modelVersion = "q4_0")
        val errorState = ModelLoadingState.Error(
            requestedModel = errorModel,
            loadedModel = null,
            lastUsedModel = errorModel,
            message = "Failed to load model",
            code = "LOAD_FAILED",
            detail = "out of memory",
            timestampMs = 1L,
        )

        composeRule.setContent {
            MaterialTheme {
                ModelSheet(
                    libraryState = sampleLibraryState(),
                    runtimeState = sampleRuntimeState(),
                    modelLoadingState = errorState,
                    routingMode = RoutingMode.AUTO,
                    presetBackingStore = presetBackingStore,
                    onEvent = {},
                )
            }
        }

        composeRule.onNodeWithText("Retry").assertIsDisplayed()
        composeRule.onNodeWithTag("choose_another_model").assertIsDisplayed()
        composeRule.onNodeWithText("Choose another").assertIsDisplayed()
    }

    @Test
    fun removeButtonIsSeparatedFromPrimaryActionsWithErrorTint() {
        composeRule.setContent {
            MaterialTheme {
                ModelSheet(
                    libraryState = sampleLibraryState(),
                    runtimeState = sampleRuntimeState(),
                    modelLoadingState = sampleRuntimeLoadingState(),
                    routingMode = RoutingMode.AUTO,
                    presetBackingStore = presetBackingStore,
                    onEvent = {},
                )
            }
        }

        composeRule.onNodeWithTag("unified_model_sheet")
            .performScrollToNode(hasTestTag("remove_button_qwen3.5-0.8b-q4_q4_0"))
        composeRule.onNodeWithTag("remove_button_qwen3.5-0.8b-q4_q4_0").assertIsDisplayed()
    }

    @Test
    fun removeButtonDispatchesRequestRemoveEvent() {
        val events = mutableListOf<ModelSheetEvent>()

        composeRule.setContent {
            MaterialTheme {
                ModelSheet(
                    libraryState = sampleLibraryState(),
                    runtimeState = sampleRuntimeState(),
                    modelLoadingState = sampleRuntimeLoadingState(),
                    routingMode = RoutingMode.AUTO,
                    presetBackingStore = presetBackingStore,
                    onEvent = { events += it },
                )
            }
        }

        composeRule.onNodeWithTag("unified_model_sheet")
            .performScrollToNode(hasTestTag("remove_button_qwen3.5-0.8b-q4_q4_0"))
        composeRule.onNodeWithTag("remove_button_qwen3.5-0.8b-q4_q4_0").performClick()
        composeRule.runOnIdle {
            assertTrue(events.any { it is ModelSheetEvent.RequestRemove })
        }
    }

    @Test
    fun installedVersionActionTagsDispatchLoadAndSetActiveEvents() {
        val events = mutableListOf<ModelSheetEvent>()
        val inactiveSnapshot = sampleSnapshot(installed = true, activeVersion = false)
        val idleLoadingState = ModelLoadingState.Idle(
            loadedModel = null,
            lastUsedModel = RuntimeLoadedModel(
                modelId = "qwen3.5-0.8b-q4",
                modelVersion = "q4_0",
            ),
        )

        composeRule.setContent {
            MaterialTheme {
                ModelSheet(
                    libraryState = sampleLibraryState(snapshot = inactiveSnapshot),
                    runtimeState = sampleRuntimeState(
                        snapshot = inactiveSnapshot,
                        lifecycle = RuntimeModelLifecycleSnapshot.initial(),
                    ),
                    modelLoadingState = idleLoadingState,
                    routingMode = RoutingMode.AUTO,
                    presetBackingStore = presetBackingStore,
                    onEvent = { events += it },
                )
            }
        }

        val loadTag = modelLibraryLoadButtonTag("qwen3.5-0.8b-q4", "q4_0")
        val setActiveTag = modelLibrarySetActiveButtonTag("qwen3.5-0.8b-q4", "q4_0")
        composeRule.onNodeWithTag("unified_model_sheet")
            .performScrollToNode(hasTestTag(loadTag))
        composeRule.onNodeWithTag(loadTag).assertIsDisplayed()
        composeRule.onNodeWithTag(setActiveTag).assertIsDisplayed()
        composeRule.onNodeWithTag(setActiveTag).performClick()
        composeRule.onNodeWithTag(loadTag).performClick()
        composeRule.runOnIdle {
            assertTrue(
                events.contains(ModelSheetEvent.SetDefaultVersion("qwen3.5-0.8b-q4", "q4_0")),
            )
            assertTrue(
                events.contains(ModelSheetEvent.LoadVersion("qwen3.5-0.8b-q4", "q4_0")),
            )
        }
    }

    @Test
    fun availableVersionDownloadActionTagDispatchesDownloadEvent() {
        val events = mutableListOf<ModelSheetEvent>()
        val libraryState = sampleLibraryState(
            manifest = sampleManifestWithDownloadableVersion(),
            downloads = emptyList(),
        )

        composeRule.setContent {
            MaterialTheme {
                ModelSheet(
                    libraryState = libraryState,
                    runtimeState = sampleRuntimeState(),
                    modelLoadingState = sampleRuntimeLoadingState(),
                    routingMode = RoutingMode.AUTO,
                    presetBackingStore = presetBackingStore,
                    onEvent = { events += it },
                )
            }
        }

        val downloadTag = modelLibraryDownloadButtonTag("qwen3-0.6b-q4_k_m", "q4_k_m")
        composeRule.onNodeWithTag("unified_model_sheet")
            .performScrollToNode(hasTestTag(downloadTag))
        composeRule.onNodeWithTag(downloadTag).assertIsDisplayed()
        composeRule.onNodeWithTag(downloadTag).performClick()
        composeRule.runOnIdle {
            assertTrue(
                events.contains(
                    ModelSheetEvent.DownloadVersion(
                        sampleManifestWithDownloadableVersion().models.last().versions.single(),
                    ),
                ),
            )
        }
    }

    @Test
    fun hiddenVersionKeysFilterOutModelsFromList() {
        val hiddenKeys = setOf("qwen3.5-0.8b-q4::q4_0")

        composeRule.setContent {
            MaterialTheme {
                ModelSheet(
                    libraryState = sampleLibraryState(),
                    runtimeState = sampleRuntimeState(),
                    modelLoadingState = sampleRuntimeLoadingState(),
                    routingMode = RoutingMode.AUTO,
                    presetBackingStore = presetBackingStore,
                    hiddenVersionKeys = hiddenKeys,
                    onEvent = {},
                )
            }
        }

        composeRule.onNodeWithText("No downloaded models yet").assertIsDisplayed()
    }

    @Test
    fun disabledLoadButtonHasStateDescriptionForLoadedModel() {
        composeRule.setContent {
            MaterialTheme {
                ModelSheet(
                    libraryState = sampleLibraryState(),
                    runtimeState = sampleRuntimeState(),
                    modelLoadingState = sampleRuntimeLoadingState(),
                    routingMode = RoutingMode.AUTO,
                    presetBackingStore = presetBackingStore,
                    onEvent = {},
                )
            }
        }

        val stateDescMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription)
        composeRule.onNodeWithTag("unified_model_sheet")
            .performScrollToNode(hasText("Loaded"))
        val loadedNodes = composeRule.onAllNodes(hasText("Loaded").and(stateDescMatcher))
        assertTrue(loadedNodes.fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun statusCardHasLiveRegionSemantics() {
        val stateWithMessage = sampleLibraryState().copy(statusMessage = "Removing model...")
        composeRule.setContent {
            MaterialTheme {
                ModelSheet(
                    libraryState = stateWithMessage,
                    runtimeState = sampleRuntimeState(),
                    modelLoadingState = sampleRuntimeLoadingState(),
                    routingMode = RoutingMode.AUTO,
                    presetBackingStore = presetBackingStore,
                    onEvent = {},
                )
            }
        }

        val liveRegionMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)
        composeRule.onNode(hasTestTag("model_sheet_status_message").and(liveRegionMatcher))
            .assertIsDisplayed()
    }
}

@Composable
private fun TestModelLibrarySheet(
    state: ModelLibraryUiState,
    onOpenRuntimeControls: () -> Unit,
) {
    LazyColumn(modifier = Modifier.testTag("model_library_list")) {
        item { Text("Model library") }
        item { Text("Downloads") }
        item { Text("Installed versions") }
        item {
            Button(onClick = onOpenRuntimeControls) {
                Text("Open runtime controls")
            }
        }
        item {
            state.statusMessage?.let { Text(it) }
        }
    }
}

@Composable
private fun TestRuntimeModelSheet(
    state: RuntimeModelUiState,
    onOpenModelLibrary: () -> Unit,
) {
    Column {
        Text("Runtime model")
        if (state.snapshot.models.any { it.installedVersions.isNotEmpty() }) {
            Text("Active model")
            Button(onClick = {}) {
                Text("Offload")
            }
        } else {
            Text("No installed models yet")
        }
        Button(onClick = onOpenModelLibrary) {
            Text("Open model library")
        }
    }
}

private fun sampleLibraryState(
    snapshot: RuntimeProvisioningSnapshot = sampleSnapshot(installed = true),
    manifest: ModelDistributionManifest = sampleManifest(),
    downloads: List<DownloadTaskState> = listOf(sampleDownload()),
): ModelLibraryUiState {
    return ModelLibraryUiState(
        snapshot = snapshot,
        manifest = manifest,
        downloads = downloads,
        isImporting = false,
        isManifestLoaded = true,
        statusMessage = "Ready for provisioning actions",
        defaultGetReadyModelId = "qwen3.5-0.8b-q4",
        defaultModelVersion = manifest.models.first().versions.first(),
    )
}

private fun sampleRuntimeState(
    snapshot: RuntimeProvisioningSnapshot = sampleSnapshot(installed = true),
    lifecycle: RuntimeModelLifecycleSnapshot = RuntimeModelLifecycleSnapshot(
        state = ModelLifecycleState.LOADED,
        loadedModel = RuntimeLoadedModel(
            modelId = "qwen3.5-0.8b-q4",
            modelVersion = "q4_0",
        ),
        lastUsedModel = RuntimeLoadedModel(
            modelId = "qwen3.5-0.8b-q4",
            modelVersion = "q4_0",
        ),
    ),
): RuntimeModelUiState {
    return RuntimeModelUiState(
        snapshot = snapshot,
        lifecycle = lifecycle,
        isImporting = false,
        statusMessage = "Runtime ready",
    )
}

private fun sampleRuntimeLoadingState(): ModelLoadingState {
    val loadedModel = RuntimeLoadedModel(
        modelId = "qwen3.5-0.8b-q4",
        modelVersion = "q4_0",
    )
    return ModelLoadingState.Loaded(
        model = loadedModel,
        lastUsedModel = loadedModel,
        detail = null,
        readyAtEpochMs = 1L,
    )
}

private fun sampleSnapshot(installed: Boolean, activeVersion: Boolean = true): RuntimeProvisioningSnapshot {
    val versions = if (installed) {
        listOf(
            ModelVersionDescriptor(
                modelId = "qwen3.5-0.8b-q4",
                version = "q4_0",
                displayName = "Qwen 3.5 0.8B (Q4)",
                absolutePath = "/tmp/qwen.gguf",
                sha256 = "a".repeat(64),
                provenanceIssuer = "",
                provenanceSignature = "",
                runtimeCompatibility = "android-arm64-v8a",
                fileSizeBytes = 1024L,
                importedAtEpochMs = 1L,
                isActive = activeVersion,
            ),
        )
    } else {
        emptyList()
    }
    return RuntimeProvisioningSnapshot(
        models = listOf(
            ProvisionedModelState(
                modelId = "qwen3.5-0.8b-q4",
                displayName = "Qwen 3.5 0.8B (Q4)",
                fileName = "qwen.gguf",
                absolutePath = if (installed) "/tmp/qwen.gguf" else null,
                sha256 = if (installed) "a".repeat(64) else null,
                importedAtEpochMs = if (installed) 1L else null,
                activeVersion = if (installed && activeVersion) "q4_0" else null,
                installedVersions = versions,
                pathOrigin = ModelPathOrigin.MANAGED,
            ),
        ),
        storageSummary = StorageSummary(
            totalBytes = 8L * 1024L * 1024L * 1024L,
            freeBytes = 4L * 1024L * 1024L * 1024L,
            usedByModelsBytes = if (installed) 2L * 1024L * 1024L * 1024L else 0L,
            tempDownloadBytes = 512L * 1024L * 1024L,
        ),
        requiredModelIds = setOf("qwen3.5-0.8b-q4"),
    )
}

private fun sampleManifest(): ModelDistributionManifest {
    return ModelDistributionManifest(
        models = listOf(
            ModelDistributionModel(
                modelId = "qwen3.5-0.8b-q4",
                displayName = "Qwen 3.5 0.8B (Q4)",
                versions = listOf(
                    ModelDistributionVersion(
                        modelId = "qwen3.5-0.8b-q4",
                        version = "q4_0",
                        downloadUrl = "https://example.test/qwen.gguf",
                        expectedSha256 = "a".repeat(64),
                        provenanceIssuer = "",
                        provenanceSignature = "",
                        runtimeCompatibility = "android-arm64-v8a",
                        fileSizeBytes = 2L * 1024L * 1024L * 1024L,
                        verificationPolicy = DownloadVerificationPolicy.INTEGRITY_ONLY,
                    ),
                ),
            ),
        ),
        source = ManifestSource.BUNDLED,
        syncedAtEpochMs = 1L,
    )
}

private fun sampleManifestWithDownloadableVersion(): ModelDistributionManifest {
    return sampleManifest().copy(
        models = sampleManifest().models + listOf(
            ModelDistributionModel(
                modelId = "qwen3-0.6b-q4_k_m",
                displayName = "Qwen 3 0.6B (Q4_K_M)",
                versions = listOf(
                    ModelDistributionVersion(
                        modelId = "qwen3-0.6b-q4_k_m",
                        version = "q4_k_m",
                        downloadUrl = "https://example.test/qwen3-0.6b-q4_k_m.gguf",
                        expectedSha256 = "b".repeat(64),
                        provenanceIssuer = "",
                        provenanceSignature = "",
                        runtimeCompatibility = "android-arm64-v8a",
                        fileSizeBytes = 1024L,
                        verificationPolicy = DownloadVerificationPolicy.INTEGRITY_ONLY,
                    ),
                ),
            ),
        ),
    )
}

private fun sampleDownload(): DownloadTaskState {
    return DownloadTaskState(
        taskId = "task-1",
        modelId = "qwen3.5-0.8b-q4",
        version = "q4_0",
        downloadUrl = "https://example.test/qwen.gguf",
        expectedSha256 = "a".repeat(64),
        provenanceIssuer = "",
        provenanceSignature = "",
        verificationPolicy = DownloadVerificationPolicy.INTEGRITY_ONLY,
        runtimeCompatibility = "android-arm64-v8a",
        processingStage = DownloadProcessingStage.DOWNLOADING,
        status = DownloadTaskStatus.DOWNLOADING,
        progressBytes = 512L,
        totalBytes = 1024L,
        updatedAtEpochMs = 1L,
        message = "Downloading",
    )
}
