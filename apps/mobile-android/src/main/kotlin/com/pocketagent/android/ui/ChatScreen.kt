package com.pocketagent.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pocketagent.android.R
import com.pocketagent.android.ui.state.ChatSessionUiModel
import com.pocketagent.android.ui.theme.LocalReduceMotion
import com.pocketagent.android.ui.theme.PocketAgentDimensions
import com.pocketagent.android.ui.theme.tickLight
import com.pocketagent.android.ui.state.MessageRole
import com.pocketagent.android.ui.state.MessageUiModel
import com.pocketagent.android.ui.state.ModelLoadingState
import com.pocketagent.android.ui.state.RuntimeUiState
import com.pocketagent.android.ui.state.StreamingState
import kotlinx.coroutines.launch

@Composable
internal fun ChatScreenBody(
    runtime: RuntimeUiState,
    activeSession: ChatSessionUiModel?,
    streaming: StreamingState,
    modelLoadingState: ModelLoadingState,
    onSuggestedPrompt: (String) -> Unit,
    onOpenModels: () -> Unit,
    canLoadLastUsedModel: Boolean,
    lastUsedModelLabel: String?,
    onLoadLastUsedModel: () -> Unit,
    activeRuntimeModelLabel: String?,
    onRefresh: () -> Unit,
    isOffline: Boolean = false,
    onOpenToolDialog: () -> Unit,
    onEditMessage: (String) -> Unit,
    onRegenerateMessage: (String) -> Unit,
    onCopiedToClipboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(PocketAgentDimensions.screenPadding),
    ) {
        OfflineAndStatusHeader(
            runtime = runtime,
            modelLoadingState = modelLoadingState,
            onOpenModels = onOpenModels,
            canLoadLastUsedModel = canLoadLastUsedModel,
            lastUsedModelLabel = lastUsedModelLabel,
            onLoadLastUsedModel = onLoadLastUsedModel,
            activeRuntimeModelLabel = activeRuntimeModelLabel,
            onRefresh = onRefresh,
            isOffline = isOffline,
        )
        Spacer(modifier = Modifier.height(PocketAgentDimensions.sectionSpacing).animateContentSize())
        MessageList(
            activeSession = activeSession,
            streaming = streaming,
            runtimeStatusDetail = runtime.modelStatusDetail,
            onSuggestedPrompt = onSuggestedPrompt,
            onOpenToolDialog = onOpenToolDialog,
            onEditMessage = onEditMessage,
            onRegenerateMessage = onRegenerateMessage,
            onCopiedToClipboard = onCopiedToClipboard,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("chat_message_list"),
        )
    }
}

@Composable
private fun MessageList(
    activeSession: ChatSessionUiModel?,
    streaming: StreamingState,
    runtimeStatusDetail: String?,
    onSuggestedPrompt: (String) -> Unit,
    onOpenToolDialog: () -> Unit,
    onEditMessage: (String) -> Unit,
    onRegenerateMessage: (String) -> Unit,
    onCopiedToClipboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val compactSpacing = PocketAgentDimensions.sectionSpacing / 2
    val clipboardManager = LocalClipboardManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val haptic = LocalHapticFeedback.current
    val reduceMotion = LocalReduceMotion.current
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val latestMessage = activeSession?.messages?.lastOrNull()
    val latestDisplayContent = if (
        latestMessage?.isStreaming == true &&
        streaming.sessionId == activeSession?.id &&
        streaming.messageId == latestMessage.id
    ) {
        streaming.text
    } else {
        latestMessage?.content
    }
    val isNearBottom by remember(listState, activeSession?.id) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            if (layoutInfo.totalItemsCount == 0) {
                true
            } else {
                val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisibleIndex >= layoutInfo.totalItemsCount - 2
            }
        }
    }

    LaunchedEffect(activeSession?.id) {
        val messages = activeSession?.messages ?: return@LaunchedEffect
        if (messages.isNotEmpty()) {
            listState.scrollToItem(index = messages.lastIndex)
        }
    }

    LaunchedEffect(activeSession?.id, activeSession?.messages?.size, latestDisplayContent, latestMessage?.isStreaming) {
        val messages = activeSession?.messages ?: return@LaunchedEffect
        if (messages.isNotEmpty() && isNearBottom) {
            listState.animateScrollToItem(index = messages.lastIndex)
        }
    }

    // Dismiss keyboard when user scrolls the chat list
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            keyboardController?.hide()
        }
    }

    // M9: Announce streaming state changes for screen readers
    val view = LocalView.current
    val assistantRespondingAnnouncement = stringResource(id = R.string.a11y_assistant_responding)
    val responseCompleteAnnouncement = stringResource(id = R.string.a11y_response_complete)
    LaunchedEffect(latestMessage?.isStreaming) {
        if (latestMessage?.role == MessageRole.ASSISTANT) {
            if (latestMessage.isStreaming) {
                view.announceForAccessibility(assistantRespondingAnnouncement)
            } else if (latestMessage.content.isNotBlank()) {
                view.announceForAccessibility(responseCompleteAnnouncement)
            }
        }
    }

    if (activeSession == null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(id = R.string.ui_no_session_selected),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    if (activeSession.messages.isEmpty()) {
        EmptyChatState(
            messagesLoaded = activeSession.messagesLoaded,
            onSuggestedPrompt = onSuggestedPrompt,
            onOpenToolDialog = onOpenToolDialog,
            modifier = modifier,
        )
        return
    }

    val messages = activeSession.messages
    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            reverseLayout = false,
            contentPadding = PaddingValues(
                top = compactSpacing,
                bottom = PocketAgentDimensions.screenPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(compactSpacing / 2, alignment = Alignment.Top),
        ) {
            items(
                count = messages.size,
                key = { messages[it].id },
                contentType = { messages[it].kind },
            ) { index ->
                val message = messages[index]
                val renderedMessage = message.withStreamingText(activeSession.id, streaming)
                val prevMessage = messages.getOrNull(index - 1)
                val nextMessage = messages.getOrNull(index + 1)
                val isFirstInGroup = prevMessage?.role != message.role
                val isLastInGroup = nextMessage?.role != message.role

                // M4: Timestamp separator when time gap > 5 min
                if (isFirstInGroup && message.timestampEpochMs > 0L) {
                    val showTimestamp = prevMessage == null ||
                        (message.timestampEpochMs - prevMessage.timestampEpochMs > 5 * 60 * 1000)
                    if (showTimestamp) {
                        TimestampSeparator(epochMs = message.timestampEpochMs)
                    }
                }

                // M1: Entrance animation (skipped when reduce motion enabled)
                val bubbleModifier = Modifier
                    .animateItem()
                    .padding(
                        top = if (isFirstInGroup) compactSpacing + (compactSpacing / 2) else 0.dp,
                        bottom = if (isLastInGroup) compactSpacing + (compactSpacing / 2) else 0.dp,
                    )

                if (reduceMotion) {
                    MessageBubble(
                        message = renderedMessage,
                        runtimeStatusDetail = runtimeStatusDetail,
                        onEditMessage = onEditMessage,
                        onRegenerateMessage = onRegenerateMessage,
                        onCopiedToClipboard = onCopiedToClipboard,
                        clipboardManager = clipboardManager,
                        isFirstInGroup = isFirstInGroup,
                        isLastInGroup = isLastInGroup,
                        modifier = bubbleModifier,
                    )
                } else {
                    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }
                    AnimatedVisibility(
                        visibleState = visibleState,
                        enter = fadeIn(tween(PocketAgentDimensions.animNormal)) + slideInVertically(
                            initialOffsetY = { it / 4 },
                            animationSpec = tween(PocketAgentDimensions.animNormal),
                        ),
                    ) {
                        MessageBubble(
                            message = renderedMessage,
                            runtimeStatusDetail = runtimeStatusDetail,
                            onEditMessage = onEditMessage,
                            onRegenerateMessage = onRegenerateMessage,
                            onCopiedToClipboard = onCopiedToClipboard,
                            clipboardManager = clipboardManager,
                            isFirstInGroup = isFirstInGroup,
                            isLastInGroup = isLastInGroup,
                            modifier = bubbleModifier,
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = !isNearBottom,
            modifier = Modifier.align(Alignment.BottomEnd).padding(PocketAgentDimensions.screenPadding),
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
        ) {
            FloatingActionButton(
                onClick = {
                    haptic.tickLight()
                    coroutineScope.launch { listState.animateScrollToItem(activeSession.messages.lastIndex) }
                },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = stringResource(id = R.string.a11y_scroll_to_latest),
                )
            }
        }
    }
}

private fun MessageUiModel.withStreamingText(
    sessionId: String,
    streaming: StreamingState,
): MessageUiModel {
    if (!isStreaming || streaming.sessionId != sessionId || streaming.messageId != id) {
        return this
    }
    return copy(
        content = streaming.text,
        isThinking = streaming.isThinking,
    )
}
