package com.pocketagent.android.ui.controllers

import com.pocketagent.android.ui.clearError
import com.pocketagent.android.ui.state.ChatSessionUiModel
import com.pocketagent.android.ui.state.activeSession
import com.pocketagent.android.ui.state.ChatUiState
import com.pocketagent.android.ui.state.ComposerUiState
import com.pocketagent.android.ui.state.MessageKind
import com.pocketagent.android.ui.state.MessageRole
import com.pocketagent.android.ui.state.MessageUiModel
import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.PersistedToolCallStatus
import com.pocketagent.android.ui.state.RuntimeUiState
import com.pocketagent.android.ui.state.UiError
import com.pocketagent.runtime.DEFAULT_CHAT_MAX_TOKENS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidChatConversationServiceTest {
    private val service = AndroidChatConversationService()

    @Test
    fun `create session seeds bounded default completion settings`() {
        val update = service.createSession(
            state = ChatUiState(),
            sessionId = "session-1",
            nowEpochMs = 1L,
        )

        assertEquals(DEFAULT_CHAT_MAX_TOKENS, update.state.activeSession()?.completionSettings?.maxTokens)
    }

    @Test
    fun `add attached image keeps a single latest selection`() {
        val state = baseState(
            composer = ComposerUiState(attachedImages = listOf("/tmp/old.png")),
        )

        val updated = service.addAttachedImage(state, " /tmp/new.png ")

        assertEquals(listOf("/tmp/new.png"), updated.composer.attachedImages)
    }

    @Test
    fun `start editing clamps legacy multi image history to one attachment`() {
        val state = baseState(
            messages = listOf(
                message(
                    id = "u1",
                    role = MessageRole.USER,
                    content = "look at this",
                    kind = MessageKind.IMAGE,
                    imagePath = "/tmp/primary.png",
                    imagePaths = listOf("/tmp/first.png", "/tmp/second.png"),
                ),
            ),
        )

        val updated = service.startEditing(state, "u1")

        assertNotNull(updated)
        assertEquals(listOf("/tmp/first.png"), updated?.composer?.attachedImages)
    }

    @Test
    fun `submit edit trims trailing timeline and keeps composer draft`() {
        val state = baseState(
            messages = listOf(
                message(id = "u1", role = MessageRole.USER, content = "old"),
                message(id = "a1", role = MessageRole.ASSISTANT, content = "answer"),
                message(id = "u2", role = MessageRole.USER, content = "follow up"),
            ),
            composer = ComposerUiState(
                text = "updated draft",
                editingMessageId = "u1",
                attachedImages = listOf("/tmp/a.png"),
            ),
        )

        val update = service.submitEdit(state)

        assertNotNull(update)
        assertEquals(0, update?.state?.activeSession()?.messages?.size)
        assertEquals("updated draft", update?.state?.composer?.text)
        assertEquals(listOf("/tmp/a.png"), update?.state?.composer?.attachedImages)
        assertEquals(null, update?.state?.composer?.editingMessageId)
        assertTrue(update?.shouldPersist == true)
    }

    @Test
    fun `regenerate trims assistant reply and seeds composer from previous user turn`() {
        val state = baseState(
            messages = listOf(
                message(
                    id = "u1",
                    role = MessageRole.USER,
                    content = "hello",
                    kind = MessageKind.IMAGE,
                    imagePath = "/tmp/legacy.png",
                    imagePaths = listOf("/tmp/first.png", "/tmp/second.png"),
                ),
                message(id = "a1", role = MessageRole.ASSISTANT, content = "first"),
                message(id = "a2", role = MessageRole.ASSISTANT, content = "retry me"),
            ),
        )

        val update = service.regenerateResponse(state, "a2")

        assertNotNull(update)
        assertEquals(listOf("u1", "a1"), update?.state?.activeSession()?.messages?.map(MessageUiModel::id))
        assertEquals("hello", update?.state?.composer?.text)
        assertEquals(listOf("/tmp/first.png"), update?.state?.composer?.attachedImages)
        assertTrue(update?.shouldPersist == true)
    }

    @Test
    fun `image analysis preparation and completion update runtime and timeline`() {
        val state = baseState(runtime = RuntimeUiState().copy(modelRuntimeStatus = ModelRuntimeStatus.READY).clearError())
        val prepared = service.prepareImageAnalysis(
            state = state,
            imageMessage = message(id = "img-1", role = MessageRole.USER, content = "Analyze attached image", kind = MessageKind.IMAGE),
        )

        assertNotNull(prepared)
        assertTrue(prepared?.state?.composer?.isSending == true)
        assertEquals(ModelRuntimeStatus.LOADING, prepared?.state?.runtime?.modelRuntimeStatus)
        assertEquals(1, prepared?.state?.activeSession()?.messages?.size)

        val completed = service.applyImageAnalysisSuccess(
            state = prepared!!.state,
            sessionId = prepared.sessionId,
            assistantMessage = message(id = "img-2", role = MessageRole.ASSISTANT, content = "A cat", kind = MessageKind.IMAGE),
            runtimeBackend = "NATIVE_JNI",
        )

        assertFalse(completed.composer.isSending)
        assertEquals(ModelRuntimeStatus.READY, completed.runtime.modelRuntimeStatus)
        assertEquals("NATIVE_JNI", completed.runtime.runtimeBackend)
        assertEquals(listOf("img-1", "img-2"), completed.activeSession()?.messages?.map(MessageUiModel::id))
    }

    @Test
    fun `tool prep and diagnostics failure append system-facing messages`() {
        val state = baseState()
        val prepared = service.prepareToolExecution(
            state = state,
            requestMessage = message(id = "u-tool", role = MessageRole.USER, content = "Run tool: calculator"),
            assistantToolCallMessage = message(
                id = "a-tool",
                role = MessageRole.ASSISTANT,
                content = "",
                kind = MessageKind.TOOL,
                toolName = "calculator",
            ),
            toolCallId = "tc-1",
        )

        assertNotNull(prepared)
        assertTrue(prepared?.state?.composer?.isSending == true)
        assertEquals(2, prepared?.state?.activeSession()?.messages?.size)

        val failedUpdate = service.appendDiagnosticsFailure(
            state = prepared!!.state,
            errorMessage = message(id = "sys-1", role = MessageRole.SYSTEM, content = "Diagnostics failed"),
            uiError = UiError(
                code = "UI-RUNTIME-001",
                userMessage = "Diagnostics failed.",
                technicalDetail = "boom",
            ),
        )

        assertNotNull(failedUpdate)
        assertEquals(3, failedUpdate?.state?.activeSession()?.messages?.size)
        assertEquals("UI-RUNTIME-001", failedUpdate?.state?.runtime?.lastErrorCode)
        assertTrue(failedUpdate?.shouldPersist == true)
    }

    private fun baseState(
        messages: List<MessageUiModel> = emptyList(),
        composer: ComposerUiState = ComposerUiState(),
        runtime: RuntimeUiState = RuntimeUiState(),
    ): ChatUiState {
        return ChatUiState(
            sessions = listOf(
                ChatSessionUiModel(
                    id = "session-1",
                    title = "Test",
                    createdAtEpochMs = 1L,
                    updatedAtEpochMs = 1L,
                    messages = messages,
                ),
            ),
            activeSessionId = "session-1",
            composer = composer,
            runtime = runtime,
        )
    }

    private fun message(
        id: String,
        role: MessageRole,
        content: String,
        kind: MessageKind = MessageKind.TEXT,
        toolName: String? = null,
        imagePath: String? = null,
        imagePaths: List<String> = emptyList(),
    ): MessageUiModel {
        return MessageUiModel(
            id = id,
            role = role,
            content = content,
            timestampEpochMs = 1L,
            kind = kind,
            imagePath = imagePath,
            imagePaths = imagePaths,
            toolName = toolName,
        )
    }
}
