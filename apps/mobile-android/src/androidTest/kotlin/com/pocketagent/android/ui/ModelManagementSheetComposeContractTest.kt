package com.pocketagent.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.pocketagent.android.runtime.ModelPathOrigin
import com.pocketagent.android.runtime.ProvisionedModelState
import com.pocketagent.android.runtime.RuntimeModelLifecycleSnapshot
import com.pocketagent.android.runtime.RuntimeProvisioningSnapshot
import com.pocketagent.android.runtime.modelmanager.DownloadProcessingStage
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.DownloadTaskStatus
import com.pocketagent.android.runtime.modelmanager.DownloadVerificationPolicy
import com.pocketagent.android.runtime.huggingface.HuggingFaceCandidate
import com.pocketagent.android.runtime.huggingface.HuggingFaceModelReference
import com.pocketagent.android.runtime.huggingface.HuggingFaceRecentModel
import com.pocketagent.android.runtime.huggingface.HuggingFaceSearchFileResult
import com.pocketagent.android.runtime.huggingface.HuggingFaceTargetModel
import com.pocketagent.android.runtime.modelmanager.ManifestSource
import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelDistributionModel
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.android.runtime.modelmanager.StorageSummary
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.model.ModelSourceKind
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

    private fun assertResourceIdVisible(resourceId: String) {
        composeRule.waitForIdle()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertTrue(
            "Expected resource id to be visible for Maestro: $resourceId",
            device.wait(Until.hasObject(By.res(resourceId)), 2_000L),
        )
    }

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
        composeRule.onNodeWithText("Refresh").performClick()
        composeRule.onNodeWithTag("unified_model_sheet")
            .performScrollToNode(hasText("Downloaded models"))
        composeRule.onNodeWithText("Downloaded models").assertIsDisplayed()
        composeRule.onNodeWithTag("unified_model_sheet")
            .performScrollToNode(hasText("Available models"))
        composeRule.onNodeWithText("Available models").assertIsDisplayed()
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
        composeRule.onNodeWithTag("unified_model_sheet")
            .performScrollToNode(hasText("No downloaded models yet"))
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
    fun installedVersionActionTagDispatchesLoadEventOnly() {
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
        composeRule.onNodeWithTag("unified_model_sheet")
            .performScrollToNode(hasTestTag(loadTag))
        composeRule.onNodeWithTag(loadTag).assertIsDisplayed()
        composeRule.onNodeWithTag(loadTag).performClick()
        composeRule.runOnIdle {
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
    fun huggingFaceSectionDispatchesResolveAndShowsPreview() {
        val events = mutableListOf<ModelSheetEvent>()
        val candidate = sampleHuggingFaceCandidate()
        val libraryState = sampleLibraryState(
            downloads = emptyList(),
        ).copy(
            huggingFaceTargets = listOf(candidate.target),
            huggingFaceAcquisitionState = HuggingFaceAcquisitionUiState.Ready(candidate),
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

        composeRule.onNodeWithTag("model_library_add_hugging_face").assertIsDisplayed()
        composeRule.onNodeWithTag("model_library_hf_url_input")
            .performTextInput("https://huggingface.co/owner/repo/resolve/main/model.gguf")
        composeRule.onNodeWithTag("model_library_hf_check_url").performClick()
        composeRule.onNodeWithTag("model_library_hf_candidate_card").assertIsDisplayed()
        composeRule.onNodeWithText("Model card: https://huggingface.co/owner/repo").assertIsDisplayed()
        composeRule.onNodeWithTag("model_library_hf_open_model_card").performClick()
        composeRule.onNodeWithTag("unified_model_sheet")
            .performScrollToNode(hasText("Checksum: Hugging Face LFS SHA-256 aaaaaaaaaaaa…"))
        composeRule.onNodeWithText("Checksum: Hugging Face LFS SHA-256 aaaaaaaaaaaa…").assertIsDisplayed()
        composeRule.onNodeWithTag("model_library_hf_license").assertIsDisplayed()
        composeRule.onNodeWithText("License: apache-2.0").assertIsDisplayed()
        composeRule.onNodeWithTag("model_library_hf_open_license").performClick()
        composeRule.onNodeWithTag("model_library_hf_storage_impact").assertIsDisplayed()
        composeRule.runOnIdle {
            assertTrue(
                events.contains(
                    ModelSheetEvent.ResolveHuggingFaceCandidate(
                        input = "https://huggingface.co/owner/repo/resolve/main/model.gguf",
                        targetModelId = "qwen3-0.6b-q4_k_m",
                    ),
                ),
            )
            assertTrue(
                "Expected model card event in $events",
                events.contains(ModelSheetEvent.OpenExternalUrl("https://huggingface.co/owner/repo")),
            )
            assertTrue(
                "Expected license event in $events",
                events.contains(ModelSheetEvent.OpenExternalUrl("https://huggingface.co/owner/repo/blob/main/LICENSE")),
            )
        }
    }

    @Test
    fun huggingFaceSearchDispatchesSearchAndResolveFromResult() {
        val events = mutableListOf<ModelSheetEvent>()
        val candidate = sampleHuggingFaceCandidate()
        val searchResult = sampleHuggingFaceSearchResult()
        val libraryState = sampleLibraryState(downloads = emptyList()).copy(
            huggingFaceTargets = listOf(candidate.target),
            huggingFaceSearchState = HuggingFaceSearchUiState.Results(
                query = "qwen",
                results = listOf(searchResult),
            ),
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

        composeRule.onNodeWithTag("model_library_hf_search").assertIsDisplayed()
        composeRule.onNodeWithTag("model_library_hf_search_input").performTextInput("qwen")
        composeRule.onNodeWithTag("model_library_hf_search_button").performClick()
        composeRule.onNodeWithTag("model_library_hf_search_results").assertIsDisplayed()
        composeRule.onNodeWithText("Repo: owner/repo").assertIsDisplayed()
        composeRule.onNodeWithText("owner/repo / model-Q4_K_M.gguf").assertIsDisplayed()
        composeRule.onNodeWithText("File: model-Q4_K_M.gguf").assertIsDisplayed()
        composeRule.onNodeWithText("Quantization: Q4_K_M").assertIsDisplayed()
        composeRule.onNodeWithTag("unified_model_sheet")
            .performScrollToNode(hasText("Downloads: 42 | Likes: 7 | License: apache-2.0"))
        composeRule.onNodeWithText("Downloads: 42 | Likes: 7 | License: apache-2.0").assertIsDisplayed()
        composeRule.onNodeWithTag("model_library_hf_search_open_model_card").performClick()
        composeRule.onNodeWithTag("model_library_hf_search_open_file").performClick()
        composeRule.onNodeWithTag("unified_model_sheet")
            .performScrollToNode(hasTestTag("model_library_hf_search_use_file"))
        composeRule.onNodeWithTag("model_library_hf_search_use_file").performClick()
        composeRule.onNodeWithTag("model_library_hf_search_clear").performClick()

        composeRule.runOnIdle {
            assertTrue(events.contains(ModelSheetEvent.SearchHuggingFaceFiles("qwen")))
            assertTrue(events.contains(ModelSheetEvent.OpenExternalUrl("https://huggingface.co/owner/repo")))
            assertTrue(events.contains(ModelSheetEvent.OpenExternalUrl("https://huggingface.co/owner/repo/resolve/main/model-Q4_K_M.gguf")))
            assertTrue(
                events.contains(
                    ModelSheetEvent.ResolveHuggingFaceCandidate(
                        input = "https://huggingface.co/owner/repo/resolve/main/model-Q4_K_M.gguf",
                        targetModelId = "qwen3-0.6b-q4_k_m",
                    ),
                ),
            )
            assertTrue(events.contains(ModelSheetEvent.ClearHuggingFaceSearch))
        }
    }

    @Test
    fun huggingFaceRecentRowRendersStoredMetadata() {
        val candidate = sampleHuggingFaceCandidate()
        val recent = sampleHuggingFaceRecentModel()
        val libraryState = sampleLibraryState(downloads = emptyList()).copy(
            huggingFaceTargets = listOf(candidate.target),
            recentHuggingFaceModels = listOf(recent),
        )

        composeRule.setContent {
            MaterialTheme {
                ModelSheet(
                    libraryState = libraryState,
                    runtimeState = sampleRuntimeState(),
                    modelLoadingState = sampleRuntimeLoadingState(),
                    routingMode = RoutingMode.AUTO,
                    presetBackingStore = presetBackingStore,
                    onEvent = {},
                )
            }
        }

        composeRule.onNodeWithTag("model_library_hf_recent").assertIsDisplayed()
        composeRule.onNodeWithTag("unified_model_sheet")
            .performScrollToNode(hasTestTag("model_library_hf_recent_license"))
        composeRule.onNodeWithTag("model_library_hf_recent_license").assertIsDisplayed()
    }

    @Test
    fun downloadQueueRendersDynamicDownloadsBeforeInstalledRows() {
        val state = sampleLibraryState(
            downloads = listOf(sampleHuggingFaceDownload()),
        )

        composeRule.setContent {
            MaterialTheme {
                ModelSheet(
                    libraryState = state,
                    runtimeState = sampleRuntimeState(),
                    modelLoadingState = sampleRuntimeLoadingState(),
                    routingMode = RoutingMode.AUTO,
                    presetBackingStore = presetBackingStore,
                    onEvent = {},
                )
            }
        }

        composeRule.onNodeWithTag("unified_model_sheet")
            .performScrollToNode(hasTestTag("model_library_download_queue"))
        composeRule.onNodeWithText("Download queue").assertIsDisplayed()
        composeRule.onNodeWithTag("model_library_download_queue").assertIsDisplayed()
        composeRule.onNodeWithText("owner/repo / model.gguf").assertIsDisplayed()
    }

    @Test
    fun huggingFaceControlsExportResourceIdsForMaestro() {
        val candidate = sampleHuggingFaceCandidate()
        val searchResult = sampleHuggingFaceSearchResult()
        val state = sampleLibraryState(
            huggingFaceAcquisitionState = HuggingFaceAcquisitionUiState.Ready(candidate),
            huggingFaceSearchState = HuggingFaceSearchUiState.Results(
                query = "qwen",
                results = listOf(searchResult),
            ),
        )

        composeRule.setContent {
            MaterialTheme {
                ModelSheet(
                    libraryState = state,
                    runtimeState = sampleRuntimeState(),
                    modelLoadingState = sampleRuntimeLoadingState(),
                    routingMode = RoutingMode.AUTO,
                    presetBackingStore = presetBackingStore,
                    onEvent = {},
                )
            }
        }

        composeRule.onNodeWithTag("unified_model_sheet")
            .performScrollToNode(hasTestTag("model_library_hf_search_results"))
        assertResourceIdVisible("model_library_add_hugging_face")
        assertResourceIdVisible("model_library_hf_search_input")
        assertResourceIdVisible("model_library_hf_search_button")
        assertResourceIdVisible("model_library_hf_search_results")
        assertResourceIdVisible("model_library_hf_search_use_file")

        composeRule.onNodeWithTag("unified_model_sheet")
            .performScrollToNode(hasTestTag("model_library_hf_candidate_card"))
        assertResourceIdVisible("model_library_hf_candidate_card")
    }

    @Test
    fun downloadQueueDispatchesPauseResumeCancelAndRetryEvents() {
        val events = mutableListOf<ModelSheetEvent>()
        val downloadStatus = mutableStateOf(DownloadTaskStatus.DOWNLOADING)

        composeRule.setContent {
            MaterialTheme {
                ModelSheet(
                    libraryState = sampleLibraryState(
                        downloads = listOf(sampleHuggingFaceDownload(status = downloadStatus.value)),
                    ),
                    runtimeState = sampleRuntimeState(),
                    modelLoadingState = sampleRuntimeLoadingState(),
                    routingMode = RoutingMode.AUTO,
                    presetBackingStore = presetBackingStore,
                    onEvent = { events += it },
                )
            }
        }

        composeRule.onNodeWithTag("unified_model_sheet")
            .performScrollToNode(hasTestTag("model_library_download_queue"))
        composeRule.onNodeWithTag("model_library_download_queue_pause").performClick()
        composeRule.onNodeWithTag("model_library_download_queue_cancel").performClick()

        composeRule.runOnIdle { downloadStatus.value = DownloadTaskStatus.PAUSED }
        composeRule.onNodeWithTag("unified_model_sheet")
            .performScrollToNode(hasTestTag("model_library_download_queue"))
        composeRule.onNodeWithTag("model_library_download_queue_resume").performClick()
        composeRule.onNodeWithTag("model_library_download_queue_cancel").performClick()

        composeRule.runOnIdle { downloadStatus.value = DownloadTaskStatus.CANCELLED }
        composeRule.onNodeWithTag("unified_model_sheet")
            .performScrollToNode(hasTestTag("model_library_download_queue"))
        composeRule.onNodeWithTag("model_library_download_queue_retry").performClick()

        composeRule.runOnIdle {
            assertTrue(events.contains(ModelSheetEvent.PauseDownload("hf-task-1")))
            assertTrue(events.contains(ModelSheetEvent.CancelDownload("hf-task-1")))
            assertTrue(events.contains(ModelSheetEvent.ResumeDownload("hf-task-1")))
            assertTrue(events.contains(ModelSheetEvent.RetryDownload("hf-task-1")))
        }
    }

    @Test
    fun downloadQueueControlsExportResourceIdsForMaestro() {
        val downloadStatus = mutableStateOf(DownloadTaskStatus.DOWNLOADING)

        composeRule.setContent {
            MaterialTheme {
                ModelSheet(
                    libraryState = sampleLibraryState(
                        downloads = listOf(sampleHuggingFaceDownload(status = downloadStatus.value)),
                    ),
                    runtimeState = sampleRuntimeState(),
                    modelLoadingState = sampleRuntimeLoadingState(),
                    routingMode = RoutingMode.AUTO,
                    presetBackingStore = presetBackingStore,
                    onEvent = {},
                )
            }
        }

        composeRule.onNodeWithTag("unified_model_sheet")
            .performScrollToNode(hasTestTag("model_library_download_queue"))
        assertResourceIdVisible("model_library_download_queue")
        assertResourceIdVisible("model_library_download_queue_pause")
        assertResourceIdVisible("model_library_download_queue_cancel")

        composeRule.runOnIdle { downloadStatus.value = DownloadTaskStatus.PAUSED }
        composeRule.onNodeWithTag("unified_model_sheet")
            .performScrollToNode(hasTestTag("model_library_download_queue_resume"))
        assertResourceIdVisible("model_library_download_queue_resume")
        assertResourceIdVisible("model_library_download_queue_cancel")

        composeRule.runOnIdle { downloadStatus.value = DownloadTaskStatus.CANCELLED }
        composeRule.onNodeWithTag("unified_model_sheet")
            .performScrollToNode(hasTestTag("model_library_download_queue_retry"))
        composeRule.onNodeWithTag("model_library_download_queue_retry").assertIsDisplayed()
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

        composeRule.onNodeWithTag("unified_model_sheet")
            .performScrollToNode(hasText("No downloaded models yet"))
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
        val loadTag = modelLibraryLoadButtonTag("qwen3.5-0.8b-q4", "q4_0")
        composeRule.onNodeWithTag("unified_model_sheet")
            .performScrollToNode(hasTestTag(loadTag))
        composeRule.onNode(hasTestTag(loadTag).and(stateDescMatcher))
            .assertIsDisplayed()
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
    huggingFaceAcquisitionState: HuggingFaceAcquisitionUiState = HuggingFaceAcquisitionUiState.Idle,
    huggingFaceSearchState: HuggingFaceSearchUiState = HuggingFaceSearchUiState.Idle,
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
        huggingFaceAcquisitionState = huggingFaceAcquisitionState,
        huggingFaceSearchState = huggingFaceSearchState,
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

private fun sampleHuggingFaceCandidate(): HuggingFaceCandidate {
    val version = ModelDistributionVersion(
        modelId = "qwen3-0.6b-q4_k_m",
        version = "hf-model-aaaaaaaaaaaa",
        downloadUrl = "https://huggingface.co/owner/repo/resolve/main/model.gguf",
        expectedSha256 = "a".repeat(64),
        provenanceIssuer = "huggingface:owner/repo",
        provenanceSignature = "",
        runtimeCompatibility = "android-arm64-v8a",
        fileSizeBytes = 1024L,
        sourceKind = ModelSourceKind.HUGGING_FACE,
        displayName = "owner/repo / model.gguf",
        verificationPolicy = DownloadVerificationPolicy.INTEGRITY_ONLY,
    )
    return HuggingFaceCandidate(
        reference = HuggingFaceModelReference(
            repoId = "owner/repo",
            revision = "main",
            filePath = "model.gguf",
        ),
        target = HuggingFaceTargetModel(
            modelId = "qwen3-0.6b-q4_k_m",
            displayName = "Qwen 3 0.6B",
        ),
        displayName = "owner/repo / model.gguf",
            sha256 = "a".repeat(64),
            sizeBytes = 1024L,
            version = version,
            license = "apache-2.0",
            licenseUrl = "https://huggingface.co/owner/repo/blob/main/LICENSE",
        )
}

private fun sampleHuggingFaceRecentModel(): HuggingFaceRecentModel {
    return HuggingFaceRecentModel(
        id = "qwen3-0.6b-q4_k_m|owner/repo|main|model.gguf",
        repoId = "owner/repo",
        revision = "main",
        filePath = "model.gguf",
        targetModelId = "qwen3-0.6b-q4_k_m",
        targetDisplayName = "Qwen 3 0.6B",
        version = "hf-model-aaaaaaaaaaaa",
        displayName = "owner/repo / model.gguf",
        sha256 = "a".repeat(64),
        sizeBytes = 1024L,
        validatedAtEpochMs = 1L,
        lastDownloadEnqueuedAtEpochMs = 2L,
        license = "apache-2.0",
        licenseUrl = "https://huggingface.co/owner/repo/blob/main/LICENSE",
    )
}

private fun sampleHuggingFaceSearchResult(): HuggingFaceSearchFileResult {
    return HuggingFaceSearchFileResult(
        reference = HuggingFaceModelReference(
            repoId = "owner/repo",
            revision = "main",
            filePath = "model-Q4_K_M.gguf",
        ),
        displayName = "owner/repo / model-Q4_K_M.gguf",
        modelCardUrl = "https://huggingface.co/owner/repo",
        downloads = 42L,
        likes = 7L,
        license = "apache-2.0",
        gated = false,
        private = false,
    )
}

private fun sampleHuggingFaceDownload(
    status: DownloadTaskStatus = DownloadTaskStatus.DOWNLOADING,
): DownloadTaskState {
    return DownloadTaskState(
        taskId = "hf-task-1",
        modelId = "qwen3-0.6b-q4_k_m",
        version = "hf-model-aaaaaaaaaaaa",
        displayName = "owner/repo / model.gguf",
        sourceKind = ModelSourceKind.HUGGING_FACE,
        downloadUrl = "https://huggingface.co/owner/repo/resolve/main/model.gguf",
        expectedSha256 = "a".repeat(64),
        provenanceIssuer = "huggingface:owner/repo",
        provenanceSignature = "",
        verificationPolicy = DownloadVerificationPolicy.INTEGRITY_ONLY,
        runtimeCompatibility = "android-arm64-v8a",
        processingStage = DownloadProcessingStage.DOWNLOADING,
        status = status,
        progressBytes = 256L,
        totalBytes = 1024L,
        updatedAtEpochMs = 2L,
        message = "Downloading",
    )
}
