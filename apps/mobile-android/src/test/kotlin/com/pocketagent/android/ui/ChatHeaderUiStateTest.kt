package com.pocketagent.android.ui

import com.pocketagent.android.ui.state.ModelLoadingState
import com.pocketagent.core.RoutingMode
import com.pocketagent.runtime.RuntimeLoadedModel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assert.assertEquals

class ChatHeaderUiStateTest {
    private val store = FakePresetBackingStore()

    @Test
    fun `error state hides load last used action`() {
        val lastUsedModel = RuntimeLoadedModel(
            modelId = "qwen3.5-0.8b-q4",
            modelVersion = "q4_0",
        )

        val uiState = deriveChatHeaderUiState(
            modelLoadingState = ModelLoadingState.Error(
                requestedModel = lastUsedModel,
                loadedModel = null,
                lastUsedModel = lastUsedModel,
                message = "Load failed",
                code = "LOAD_FAILED",
                detail = "boom",
                timestampMs = 1L,
            ),
            routingMode = RoutingMode.QWEN_0_8B,
            presetBackingStore = store,
        )

        assertEquals("Qwen3.5 0.8B Vision q4_0", uiState.lastUsedModelLabel)
        assertFalse(uiState.canLoadLastUsedModel)
    }

    @Test
    fun `idle state with last used model shows load last used action`() {
        val lastUsedModel = RuntimeLoadedModel(
            modelId = "qwen3.5-0.8b-q4",
            modelVersion = "q4_0",
        )

        val uiState = deriveChatHeaderUiState(
            modelLoadingState = ModelLoadingState.Idle(
                loadedModel = null,
                lastUsedModel = lastUsedModel,
                updatedAtEpochMs = 1L,
            ),
            routingMode = RoutingMode.AUTO,
            presetBackingStore = store,
        )

        assertEquals("Qwen3.5 0.8B Vision q4_0", uiState.lastUsedModelLabel)
        assertTrue(uiState.canLoadLastUsedModel)
    }

    @Test
    fun `active loaded model shows preset label when routing matches tier`() {
        val loaded = RuntimeLoadedModel(
            modelId = "qwen3-1.7b-q4_k_m",
            modelVersion = "q4_k_m",
        )
        val uiState = deriveChatHeaderUiState(
            modelLoadingState = ModelLoadingState.Loaded(
                model = loaded,
                lastUsedModel = loaded,
                detail = null,
                readyAtEpochMs = 1L,
            ),
            routingMode = RoutingMode.QWEN3_1_7B,
            presetBackingStore = store,
        )
        assertEquals("Balanced", uiState.activeRuntimeModelLabel)
    }
}
