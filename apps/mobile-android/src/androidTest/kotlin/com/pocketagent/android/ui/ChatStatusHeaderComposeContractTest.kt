package com.pocketagent.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketagent.android.ui.state.ModelLoadingState
import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.RuntimeUiState
import com.pocketagent.android.ui.state.StartupProbeState
import com.pocketagent.runtime.RuntimeLoadedModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatStatusHeaderComposeContractTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun refreshActionStaysHiddenWhileSendIsInFlight() {
        composeRule.setContent {
            MaterialTheme {
                OfflineAndStatusHeader(
                    runtime = RuntimeUiState(
                        startupProbeState = StartupProbeState.READY,
                        modelRuntimeStatus = ModelRuntimeStatus.LOADING,
                        modelStatusDetail = "Prefill...",
                        sendElapsedMs = 179_800L,
                        sendSlowState = "Loading model and prefill can take longer on older phones. You can keep waiting or cancel.",
                    ),
                    modelLoadingState = loadedModelState(),
                    onOpenModels = {},
                    canLoadLastUsedModel = false,
                    lastUsedModelLabel = null,
                    onLoadLastUsedModel = {},
                    activeRuntimeModelLabel = "Auto",
                    onRefresh = {},
                    isOffline = false,
                )
            }
        }

        composeRule.onNodeWithText("Prefill... (179.8s)").assertIsDisplayed()
        composeRule.onNodeWithText("Loading model and prefill can take longer on older phones. You can keep waiting or cancel.")
            .assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("refresh_button").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun refreshActionRemainsAvailableWhenNoSendIsActive() {
        composeRule.setContent {
            MaterialTheme {
                OfflineAndStatusHeader(
                    runtime = RuntimeUiState(
                        startupProbeState = StartupProbeState.BLOCKED_TIMEOUT,
                        modelRuntimeStatus = ModelRuntimeStatus.NOT_READY,
                        modelStatusDetail = "Startup checks timed out. Runtime readiness is unknown; refresh checks before sending.",
                    ),
                    modelLoadingState = loadedModelState(),
                    onOpenModels = {},
                    canLoadLastUsedModel = false,
                    lastUsedModelLabel = null,
                    onLoadLastUsedModel = {},
                    activeRuntimeModelLabel = "Auto",
                    onRefresh = {},
                    isOffline = false,
                )
            }
        }

        composeRule.onNodeWithTag("refresh_button").assertIsDisplayed()
    }

    private fun loadedModelState(): ModelLoadingState.Loaded {
        return ModelLoadingState.Loaded(
            model = RuntimeLoadedModel(
                modelId = "qwen3.5-0.8b-q4",
                modelVersion = "seed-1",
            ),
            lastUsedModel = RuntimeLoadedModel(
                modelId = "qwen3.5-0.8b-q4",
                modelVersion = "seed-1",
            ),
            detail = null,
            readyAtEpochMs = 1L,
        )
    }
}
