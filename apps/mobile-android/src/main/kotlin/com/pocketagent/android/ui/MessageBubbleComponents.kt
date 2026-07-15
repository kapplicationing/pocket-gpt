package com.pocketagent.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.pocketagent.android.R
import com.pocketagent.android.ui.state.MessageRole
import com.pocketagent.android.ui.state.MessageUiModel
import com.pocketagent.android.ui.theme.PocketAgentDimensions
import com.pocketagent.android.ui.theme.tickConfirm
import com.pocketagent.android.ui.theme.tickLight

@OptIn(ExperimentalFoundationApi::class)
@Composable
@Suppress("CyclomaticComplexMethod", "LongMethod")
internal fun MessageBubble(
    message: MessageUiModel,
    runtimeStatusDetail: String?,
    onEditMessage: (String) -> Unit,
    onRegenerateMessage: (String) -> Unit,
    onCopiedToClipboard: () -> Unit,
    clipboardManager: ClipboardManager,
    isFirstInGroup: Boolean = true,
    isLastInGroup: Boolean = true,
    speakingMessageId: String? = null,
    onReadAloud: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val isUser = message.role == MessageRole.USER
    var showContextMenu by remember { mutableStateOf(false) }
    var menuOffset by remember { mutableStateOf(DpOffset.Zero) }
    val menuInteractionSource = remember(message.id) { MutableInteractionSource() }
    val indication = LocalIndication.current
    val compactSpacing = PocketAgentDimensions.sectionSpacing / 2
    val bubbleShape = messageBubbleShape(
        isUser = isUser,
        isFirstInGroup = isFirstInGroup,
        isLastInGroup = isLastInGroup,
    )
    val hasContextActions = message.content.isNotBlank() && message.role == MessageRole.USER
    val messageOptionsLabel = stringResource(R.string.cd_message_options)

    fun openContextMenu(offset: Offset?) {
        if (!hasContextActions) return
        menuOffset = offset?.let { pressOffset ->
            with(density) {
                DpOffset(pressOffset.x.toDp(), pressOffset.y.toDp())
            }
        } ?: DpOffset.Zero
        haptic.tickConfirm()
        showContextMenu = true
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            tonalElevation = 1.dp,
            color = messageBubbleColor(message.role),
            modifier = Modifier
                .messageBubbleTestTag(message)
                .clip(bubbleShape)
                .fillMaxWidth(0.9f)
                .widthIn(max = 600.dp),
        ) {
            Box {
                Column(
                    modifier = Modifier
                        .then(
                            if (hasContextActions) {
                                Modifier
                                    .indication(menuInteractionSource, indication)
                                    .semantics {
                                        onLongClick(
                                            label = messageOptionsLabel,
                                            action = {
                                                openContextMenu(offset = null)
                                                true
                                            },
                                        )
                                    }
                                    .pointerInput(hasContextActions) {
                                        detectTapGestures(
                                            onPress = { pressOffset ->
                                                val press = PressInteraction.Press(pressOffset)
                                                menuInteractionSource.tryEmit(press)
                                                val released = tryAwaitRelease()
                                                menuInteractionSource.tryEmit(
                                                    if (released) {
                                                        PressInteraction.Release(press)
                                                    } else {
                                                        PressInteraction.Cancel(press)
                                                    },
                                                )
                                            },
                                            onLongPress = ::openContextMenu,
                                        )
                                    }
                            } else {
                                Modifier
                            },
                        )
                        .padding(PocketAgentDimensions.messageBubblePadding),
                ) {
                    val attachmentPaths = message.imagePaths.ifEmpty { listOfNotNull(message.imagePath) }
                    when {
                        attachmentPaths.isNotEmpty() -> {
                            Text(
                                text = stringResource(id = R.string.ui_images_attached, attachmentPaths.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(compactSpacing))
                            AttachmentThumbnailRow(attachmentPaths)
                            Spacer(modifier = Modifier.height(compactSpacing))
                        }

                        message.toolName != null -> {
                            val statusSuffix = message.interaction?.toolCalls?.firstOrNull()?.status.toReadableSuffix()
                            Text(
                                text = stringResource(
                                    id = R.string.ui_tool_message_label,
                                    message.toolName,
                                ) + statusSuffix,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(compactSpacing))
                        }
                    }

                    if (!message.reasoningContent.isNullOrBlank()) {
                        ThinkingBubble(reasoningContent = message.reasoningContent)
                        Spacer(modifier = Modifier.height(compactSpacing + (compactSpacing / 2)))
                    }

                    val content = message.content
                    if (shouldRenderInThreadLoadingPlaceholder(message)) {
                        if (message.isThinking) {
                            ThinkingInProgressIndicator()
                        } else {
                            LoadingDotsAnimation()
                        }
                        runtimeStatusDetail?.takeIf { it.isNotBlank() }?.let { detail ->
                            Spacer(modifier = Modifier.height(compactSpacing))
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            MarkdownMessageContent(
                                content = content,
                                clipboardManager = clipboardManager,
                                onCopiedToClipboard = onCopiedToClipboard,
                            )
                        }
                    }

                    MessageMetrics(message)
                    FailedMessageRetry(message, onRegenerateMessage)
                    MessageActions(
                        message = message,
                        onCopiedToClipboard = onCopiedToClipboard,
                        clipboardManager = clipboardManager,
                        isSpeaking = speakingMessageId == message.id,
                        onReadAloud = onReadAloud,
                    )
                }

                MessageContextMenu(
                    message = message,
                    expanded = showContextMenu,
                    offset = menuOffset,
                    onDismiss = { showContextMenu = false },
                    onEditMessage = onEditMessage,
                )
            }
        }
    }
}

private fun Modifier.messageBubbleTestTag(message: MessageUiModel): Modifier {
    return when {
        message.role == MessageRole.USER -> testTag("message_bubble_user")
        message.isStreaming -> testTag("message_bubble_assistant_streaming")
        message.content.isBlank() -> testTag("message_bubble_assistant_pending")
        else -> testTag("message_bubble_assistant_complete")
    }
}

@Composable
private fun MessageMetrics(message: MessageUiModel) {
    if (message.role != MessageRole.ASSISTANT || message.isStreaming || message.content.isBlank()) return
    val hasMetadata = message.tokensPerSec != null || message.firstTokenMs != null || message.totalLatencyMs != null
    if (!hasMetadata) return
    val haptic = LocalHapticFeedback.current
    var expanded by remember(message.id) { mutableStateOf(false) }
    val metricsLabel = stringResource(id = R.string.a11y_toggle_metrics)
    val metricsStateLabel = stringResource(
        id = if (expanded) R.string.a11y_expanded else R.string.a11y_collapsed,
    )
    Spacer(modifier = Modifier.height(PocketAgentDimensions.sectionSpacing / 2))
    if (expanded) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    haptic.tickLight()
                    expanded = false
                }
                .disclosureSemantics(
                    label = metricsLabel,
                    stateLabel = metricsStateLabel,
                ),
            horizontalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
        ) {
            val style = MaterialTheme.typography.labelSmall
            val color = MaterialTheme.colorScheme.onSurfaceVariant
            message.tokensPerSec?.let {
                Text(stringResource(id = R.string.ui_metric_tokens_per_sec, it), style = style, color = color)
            }
            message.firstTokenMs?.let {
                Text(stringResource(id = R.string.ui_metric_ttft, it), style = style, color = color)
            }
            message.totalLatencyMs?.let {
                Text(stringResource(id = R.string.ui_metric_total_latency, it), style = style, color = color)
            }
        }
    } else {
        message.tokensPerSec?.let { tps ->
            Text(
                text = stringResource(id = R.string.ui_metric_tokens_per_sec, tps),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier
                    .clickable {
                        haptic.tickLight()
                        expanded = true
                    }
                    .disclosureSemantics(
                        label = metricsLabel,
                        stateLabel = metricsStateLabel,
                    ),
            )
        }
    }
}

@Composable
private fun FailedMessageRetry(
    message: MessageUiModel,
    onRegenerateMessage: (String) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val isFailed = message.role == MessageRole.ASSISTANT &&
        !message.isStreaming &&
        message.finishReason?.let {
            it.startsWith("failed") || it == "timeout" || it == "cancelled"
        } == true
    if (!isFailed) return
    val compactSpacing = PocketAgentDimensions.sectionSpacing / 2
    Spacer(modifier = Modifier.height(compactSpacing))
    Surface(
        onClick = {
            haptic.tickLight()
            onRegenerateMessage(message.id)
        },
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = PocketAgentDimensions.messageBubblePadding,
                vertical = PocketAgentDimensions.sectionSpacing,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(compactSpacing + (compactSpacing / 2)),
        ) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = stringResource(id = R.string.a11y_retry),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = stringResource(id = R.string.ui_retry),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun MessageActions(
    message: MessageUiModel,
    onCopiedToClipboard: () -> Unit,
    clipboardManager: ClipboardManager,
    isSpeaking: Boolean,
    onReadAloud: (String, String) -> Unit,
) {
    if (message.content.isBlank() || message.isStreaming) return
    val haptic = LocalHapticFeedback.current
    Spacer(modifier = Modifier.height(PocketAgentDimensions.sectionSpacing / 2))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (message.role == MessageRole.ASSISTANT) {
            IconButton(
                onClick = {
                    haptic.tickLight()
                    onReadAloud(message.id, message.content)
                },
                modifier = Modifier.testTag("read_aloud_button"),
            ) {
                Icon(
                    imageVector = if (isSpeaking) Icons.Default.StopCircle else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = stringResource(
                        id = if (isSpeaking) R.string.a11y_stop_read_aloud else R.string.a11y_read_aloud,
                    ),
                    modifier = Modifier.size(16.dp),
                    tint = if (isSpeaking) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    },
                )
            }
        }
        IconButton(
            onClick = {
                haptic.tickLight()
                clipboardManager.setText(AnnotatedString(message.content))
                onCopiedToClipboard()
            },
        ) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = stringResource(id = R.string.a11y_copy_message),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun MessageContextMenu(
    message: MessageUiModel,
    expanded: Boolean,
    offset: DpOffset = DpOffset.Zero,
    onDismiss: () -> Unit,
    onEditMessage: (String) -> Unit,
) {
    if (message.role != MessageRole.USER) return
    val haptic = LocalHapticFeedback.current
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = offset,
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(id = R.string.ui_edit)) },
            onClick = {
                haptic.tickLight()
                onEditMessage(message.id)
                onDismiss()
            },
        )
    }
}

@Composable
private fun messageBubbleColor(role: MessageRole) = when (role) {
    MessageRole.USER -> MaterialTheme.colorScheme.primaryContainer
    MessageRole.ASSISTANT -> MaterialTheme.colorScheme.surfaceVariant
    MessageRole.TOOL -> MaterialTheme.colorScheme.secondaryContainer
    MessageRole.SYSTEM -> MaterialTheme.colorScheme.errorContainer
}

private fun messageBubbleShape(
    isUser: Boolean,
    isFirstInGroup: Boolean,
    isLastInGroup: Boolean,
): RoundedCornerShape {
    val compactSpacing = PocketAgentDimensions.sectionSpacing / 2
    val cornerRadius = PocketAgentDimensions.messageBubblePadding
    val smallCorner = compactSpacing
    return when {
        isFirstInGroup && isLastInGroup -> RoundedCornerShape(cornerRadius)
        isUser && isFirstInGroup -> RoundedCornerShape(cornerRadius, cornerRadius, smallCorner, cornerRadius)
        isUser && isLastInGroup -> RoundedCornerShape(cornerRadius, smallCorner, cornerRadius, cornerRadius)
        isUser -> RoundedCornerShape(cornerRadius, smallCorner, smallCorner, cornerRadius)
        !isUser && isFirstInGroup -> RoundedCornerShape(cornerRadius, cornerRadius, cornerRadius, smallCorner)
        !isUser && isLastInGroup -> RoundedCornerShape(smallCorner, cornerRadius, cornerRadius, cornerRadius)
        else -> RoundedCornerShape(smallCorner, cornerRadius, cornerRadius, smallCorner)
    }
}
