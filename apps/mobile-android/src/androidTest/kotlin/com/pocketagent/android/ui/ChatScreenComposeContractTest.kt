package com.pocketagent.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketagent.android.ui.state.ChatGatePrimaryAction
import com.pocketagent.android.ui.state.ChatGateState
import com.pocketagent.android.ui.state.ChatGateStatus
import com.pocketagent.android.ui.state.ChatSessionUiModel
import com.pocketagent.android.ui.state.ChatUiState
import com.pocketagent.android.ui.state.ComposerUiState
import com.pocketagent.android.ui.state.MessageKind
import com.pocketagent.android.ui.state.MessageRole
import com.pocketagent.android.ui.state.MessageUiModel
import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.RuntimeUiState
import com.pocketagent.android.ui.state.StartupProbeState
import com.pocketagent.android.ui.state.activeSession
import com.pocketagent.android.voice.VoiceDictationPhase
import com.pocketagent.android.voice.VoiceDictationState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatScreenComposeContractTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun inlineBlockedCardCoexistsWithTopRuntimeErrorBanner() {
        val state = testUiState(
            runtime = RuntimeUiState(
                startupProbeState = StartupProbeState.BLOCKED,
                modelRuntimeStatus = ModelRuntimeStatus.ERROR,
                lastErrorCode = "UI-STARTUP-001",
                lastErrorUserMessage = "Startup checks blocked",
            ),
        )
        val gate = ChatGateState(
            status = ChatGateStatus.BLOCKED_RUNTIME_CHECK,
            primaryAction = ChatGatePrimaryAction.REFRESH_RUNTIME_CHECKS,
        )

        composeRule.setContent {
            MaterialTheme {
                Scaffold(
                    bottomBar = {
                        ComposerBar(
                            text = "blocked",
                            isSending = false,
                            chatGateState = gate,
                            onTextChanged = {},
                            onSend = {},
                            onCancelSend = {},
                            onSubmitEdit = {},
                            onCancelEdit = {},
                            onAttachImage = {},
                            onRemoveImage = {},
                            onOpenToolDialog = {},
                            onToggleThinking = {},
                            onOpenCompletionSettings = {},
                            onBlockedAction = {},
                        )
                    },
                ) { innerPadding ->
                    TestChatScreenBody(
                        state = state,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }

        composeRule.onNodeWithTag("runtime_error_banner").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_gate_inline_card").assertIsDisplayed()
        composeRule.onNodeWithTag("send_button").assertTextEquals("Refresh")
        composeRule.onNodeWithText("Runtime checks need attention before sending. Refresh checks and retry.")
            .assertIsDisplayed()
    }

    @Test
    fun inThreadPlaceholderOnlyRendersForBlankStreamingAssistantMessage() {
        val blankStreamingState = testUiState(
            runtime = RuntimeUiState(
                startupProbeState = StartupProbeState.READY,
                modelRuntimeStatus = ModelRuntimeStatus.READY,
            ),
            messages = listOf(
                MessageUiModel(
                    id = "assistant-stream",
                    role = MessageRole.ASSISTANT,
                    content = "",
                    timestampEpochMs = 1L,
                    kind = MessageKind.TEXT,
                    isStreaming = true,
                ),
            ),
        )
        val nonBlankStreamingState = blankStreamingState.copy(
            sessions = listOf(
                blankStreamingState.sessions.first().copy(
                    messages = listOf(
                        blankStreamingState.sessions.first().messages.first().copy(content = "hello"),
                    ),
                ),
            ),
            composer = ComposerUiState(),
        )
        var currentState by mutableStateOf(blankStreamingState)

        composeRule.setContent {
            MaterialTheme {
                TestChatScreenBody(
                    state = currentState,
                )
            }
        }
        composeRule.onNodeWithText("Preparing response…").assertIsDisplayed()

        composeRule.runOnIdle {
            currentState = nonBlankStreamingState
        }
        composeRule.onAllNodesWithText("Preparing response…").assertCountEquals(0)
    }

    @Test
    fun dictationShowsEditablePartialAndBlocksSendUntilCaptureFinishes() {
        var toggled = false
        composeRule.setContent {
            MaterialTheme {
                ComposerBar(
                    text = "existing draft",
                    isSending = false,
                    chatGateState = ChatGateState(
                        status = ChatGateStatus.READY,
                        primaryAction = ChatGatePrimaryAction.NONE,
                    ),
                    onTextChanged = {},
                    onSend = {},
                    onCancelSend = {},
                    onSubmitEdit = {},
                    onCancelEdit = {},
                    onAttachImage = {},
                    onRemoveImage = {},
                    onOpenToolDialog = {},
                    onToggleThinking = {},
                    onOpenCompletionSettings = {},
                    onBlockedAction = {},
                    dictationState = VoiceDictationState(
                        phase = VoiceDictationPhase.LISTENING,
                        partialTranscript = "spoken words",
                    ),
                    onToggleDictation = { toggled = true },
                )
            }
        }

        composeRule.onNodeWithText("Listening in English: spoken words").assertIsDisplayed()
        composeRule.onNodeWithTag("send_button").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Stop dictation").performClick()
        composeRule.runOnIdle { assertTrue(toggled) }
    }

    private fun testUiState(
        runtime: RuntimeUiState,
        messages: List<MessageUiModel> = emptyList(),
    ): ChatUiState {
        val session = ChatSessionUiModel(
            id = "session-1",
            title = "Session",
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
            messages = messages,
        )
        return ChatUiState(
            sessions = listOf(session),
            activeSessionId = session.id,
            runtime = runtime,
        )
    }
}

@Composable
private fun TestChatScreenBody(
    state: ChatUiState,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (state.runtime.lastErrorCode != null) {
            Text(
                text = state.runtime.lastErrorUserMessage.orEmpty(),
                modifier = Modifier.testTag("runtime_error_banner"),
            )
        }
        state.activeSession()?.messages?.forEach { message ->
            if (message.role == MessageRole.ASSISTANT && message.isStreaming && message.content.isBlank()) {
                Text("Preparing response…")
            } else {
                Text(message.content)
            }
        }
    }
}
