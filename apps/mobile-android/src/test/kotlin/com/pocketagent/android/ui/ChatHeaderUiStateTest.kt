package com.pocketagent.android.ui

import com.pocketagent.android.ui.state.ModelLoadingState
import com.pocketagent.runtime.RuntimeLoadedModel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Assert.assertEquals

class ChatHeaderUiStateTest {
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
        )

        assertEquals("Qwen3.5 0.8B Vision q4_0", uiState.lastUsedModelLabel)
        assertNull(uiState.activeRuntimeModelLabel)
        assertTrue(uiState.canLoadLastUsedModel)
    }

    @Test
    fun `active loaded model shows model label rather than preset label`() {
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
        )
        assertEquals("Qwen3 1.7B q4_k_m", uiState.activeRuntimeModelLabel)
    }
}
