package com.pocketagent.android.ui

import android.content.res.Configuration
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.pocketagent.android.R
import com.pocketagent.android.ui.state.ChatGatePrimaryAction
import com.pocketagent.android.ui.state.ChatGateState
import com.pocketagent.android.ui.state.ChatGateStatus
import com.pocketagent.android.ui.theme.PocketAgentDimensions
import com.pocketagent.android.ui.theme.tickLight
import com.pocketagent.android.voice.VoiceDictationPhase
import com.pocketagent.android.voice.VoiceDictationState
import java.io.File

@Composable
@Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList")
internal fun ComposerBar(
    text: String,
    isSending: Boolean,
    isCancelling: Boolean = false,
    chatGateState: ChatGateState,
    editingMessageId: String? = null,
    attachedImages: List<String> = emptyList(),
    activeSessionId: String? = null,
    onTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    onCancelSend: () -> Unit,
    onSubmitEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onAttachImage: () -> Unit,
    canAttachImages: Boolean = true,
    onRemoveImage: (Int) -> Unit,
    onOpenToolDialog: () -> Unit,
    showThinkingToggle: Boolean = false,
    thinkingEnabled: Boolean = false,
    onToggleThinking: () -> Unit,
    onOpenCompletionSettings: () -> Unit,
    onBlockedAction: (ChatGatePrimaryAction) -> Unit,
    consumeImeInsets: Boolean = true,
    autoFocusEnabled: Boolean = true,
    dictationState: VoiceDictationState = VoiceDictationState(),
    onToggleDictation: () -> Unit = {},
) {
    val haptic = LocalHapticFeedback.current
    val isEditing = editingMessageId != null
    val launchSafeAttachedImages = launchSafeSingleImagePaths(attachedImages)
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    LaunchedEffect(autoFocusEnabled) {
        if (!autoFocusEnabled) {
            focusManager.clearFocus(force = true)
        }
    }
    LaunchedEffect(activeSessionId) {
        if (autoFocusEnabled && activeSessionId != null) {
            focusRequester.requestFocus()
        }
    }
    LaunchedEffect(editingMessageId) {
        if (autoFocusEnabled && editingMessageId != null) {
            focusRequester.requestFocus()
        }
    }
    val canTriggerBlockedAction = hasChatGatePrimaryAction(chatGateState)
    val dictationActive = dictationState.isCaptureActive
    val sendButtonEnabled = when {
        dictationActive -> false
        isSending -> !isCancelling
        isEditing -> text.isNotBlank()
        chatGateState.isReady -> text.isNotBlank()
        else -> canTriggerBlockedAction
    }
    val sendButtonDescription = stringResource(id = R.string.a11y_send_button)
    val sendStateDescription = when {
        dictationActive -> stringResource(id = R.string.a11y_send_state_dictating)
        isSending && isCancelling -> stringResource(id = R.string.a11y_send_state_cancelling)
        isSending -> stringResource(id = R.string.a11y_send_state_sending)
        !chatGateState.isReady -> stringResource(id = R.string.a11y_send_state_runtime_not_ready)
        text.isBlank() -> stringResource(id = R.string.a11y_send_state_disabled)
        else -> stringResource(id = R.string.a11y_send_state_enabled)
    }
    val handlePrimaryComposerAction: () -> Unit = {
        when {
            dictationActive -> Unit
            isSending && !isCancelling -> onCancelSend()
            isEditing -> onSubmitEdit()
            chatGateState.isReady && text.isNotBlank() -> onSend()
            !chatGateState.isReady -> onBlockedAction(chatGateState.primaryAction)
        }
    }
    val verticalPadding = if (isLandscape) {
        PocketAgentDimensions.sectionSpacing / 2
    } else {
        PocketAgentDimensions.sectionSpacing
    }
    val compactSpacing = PocketAgentDimensions.sectionSpacing / 2

    val imePaddingModifier = if (consumeImeInsets) Modifier.imePadding() else Modifier
    val contentAnimationModifier = if (consumeImeInsets) Modifier.animateContentSize() else Modifier
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .then(imePaddingModifier)
            .then(contentAnimationModifier),
    ) {
        // No verticalScroll on this Column: the inner OutlinedTextField already scrolls its
        // text via maxLines, and the Column is bounded by heightIn(max = 280.dp). Wrapping
        // the whole composer in a scroll container forced an extra layout pass per keystroke.
        // See docs/architecture/android-performance-contract.md (2026-05-02 RCA).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
                .padding(horizontal = PocketAgentDimensions.sectionSpacing, vertical = verticalPadding),
        ) {
            if (shouldShowChatGateInlineCard(chatGateState)) {
                ChatGateInlineCard(chatGateState = chatGateState)
                Spacer(modifier = Modifier.size(compactSpacing))
            }
            if (isEditing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = compactSpacing),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(id = R.string.ui_editing_message),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    IconButton(
                        onClick = {
                            haptic.tickLight()
                            onCancelEdit()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(id = R.string.a11y_cancel_edit),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            if (launchSafeAttachedImages.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = compactSpacing),
                    horizontalArrangement = Arrangement.spacedBy(compactSpacing),
                ) {
                    launchSafeAttachedImages.forEachIndexed { index, path ->
                        val attachmentLabel = Uri.parse(path).lastPathSegment
                            ?.takeIf { it.isNotBlank() }
                            ?: File(path).name.ifBlank { path }
                        Box(modifier = Modifier.size(48.dp)) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(path)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = stringResource(
                                    id = R.string.a11y_attached_image_thumbnail,
                                    attachmentLabel,
                                ),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(MaterialTheme.shapes.small),
                            )
                            IconButton(
                                onClick = {
                                    haptic.tickLight()
                                    onRemoveImage(index)
                                },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .minimumInteractiveComponentSize(),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(id = R.string.a11y_remove_image),
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }
            }
            ComposerActionStrip(
                isSending = isSending,
                showThinkingToggle = showThinkingToggle,
                thinkingEnabled = thinkingEnabled,
                onAttachImage = onAttachImage,
                canAttachImages = canAttachImages,
                onOpenToolDialog = onOpenToolDialog,
                onToggleThinking = onToggleThinking,
                onOpenCompletionSettings = onOpenCompletionSettings,
                dictationState = dictationState,
                onToggleDictation = onToggleDictation,
            )
            VoiceDictationStatus(dictationState)
            ComposerInputRow(
                text = text,
                isSending = isSending,
                isCancelling = isCancelling,
                isEditing = isEditing,
                isLandscape = isLandscape,
                chatGateState = chatGateState,
                sendButtonEnabled = sendButtonEnabled,
                sendButtonDescription = sendButtonDescription,
                sendStateDescription = sendStateDescription,
                focusRequester = focusRequester,
                compactSpacing = compactSpacing,
                onTextChanged = onTextChanged,
                onPrimaryAction = handlePrimaryComposerAction,
            )
        }
    }
}

@Composable
private fun ComposerActionStrip(
    isSending: Boolean,
    showThinkingToggle: Boolean,
    thinkingEnabled: Boolean,
    onAttachImage: () -> Unit,
    canAttachImages: Boolean,
    onOpenToolDialog: () -> Unit,
    onToggleThinking: () -> Unit,
    onOpenCompletionSettings: () -> Unit,
    dictationState: VoiceDictationState,
    onToggleDictation: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val attachImageStateDescription = if (canAttachImages) {
        null
    } else {
        stringResource(id = R.string.a11y_attach_image_requires_vision_model)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { haptic.tickLight(); onAttachImage() },
            enabled = !isSending && canAttachImages,
            modifier = Modifier
                .alpha(if (canAttachImages) 1f else 0.38f)
                .semantics {
                    attachImageStateDescription?.let {
                        stateDescription = it
                    }
                },
        ) {
            Icon(Icons.Default.Image, contentDescription = stringResource(id = R.string.a11y_attach_image))
        }
        IconButton(
            onClick = { haptic.tickLight(); onOpenToolDialog() },
            enabled = !isSending,
        ) {
            Icon(
                imageVector = Icons.Default.Build,
                contentDescription = stringResource(id = R.string.a11y_open_tools),
                modifier = Modifier.size(20.dp),
            )
        }
        DictationActionButton(
            isSending = isSending,
            state = dictationState,
            onToggle = onToggleDictation,
        )
        if (showThinkingToggle) {
            IconButton(onClick = { haptic.tickLight(); onToggleThinking() }) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = stringResource(
                        id = if (thinkingEnabled) R.string.a11y_disable_thinking else R.string.a11y_enable_thinking,
                    ),
                    tint = if (thinkingEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        IconButton(
            onClick = { haptic.tickLight(); onOpenCompletionSettings() },
            modifier = Modifier.testTag("completion_settings_button"),
        ) {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = stringResource(id = R.string.a11y_chat_settings),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun DictationActionButton(
    isSending: Boolean,
    state: VoiceDictationState,
    onToggle: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val description = when (state.phase) {
        VoiceDictationPhase.CHECKING -> stringResource(id = R.string.a11y_prepare_dictation)
        VoiceDictationPhase.FINALIZING -> stringResource(id = R.string.a11y_finish_dictation)
        VoiceDictationPhase.LISTENING -> stringResource(id = R.string.a11y_stop_dictation)
        VoiceDictationPhase.IDLE,
        VoiceDictationPhase.ERROR,
        -> stringResource(id = R.string.a11y_start_dictation)
    }
    IconButton(
        onClick = { haptic.tickLight(); onToggle() },
        enabled = !isSending && state.phase != VoiceDictationPhase.FINALIZING,
        modifier = Modifier
            .testTag("voice_dictation_button")
            .semantics {
                contentDescription = description
            },
    ) {
        when (state.phase) {
            VoiceDictationPhase.CHECKING,
            VoiceDictationPhase.FINALIZING,
            -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
            VoiceDictationPhase.LISTENING -> Icon(
                imageVector = Icons.Default.StopCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            VoiceDictationPhase.IDLE,
            VoiceDictationPhase.ERROR,
            -> Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun VoiceDictationStatus(state: VoiceDictationState) {
    val statusText = when (state.phase) {
        VoiceDictationPhase.CHECKING -> stringResource(id = R.string.ui_dictation_checking)
        VoiceDictationPhase.FINALIZING -> stringResource(id = R.string.ui_dictation_finalizing)
        VoiceDictationPhase.LISTENING -> if (state.partialTranscript.isBlank()) {
            stringResource(id = R.string.ui_dictation_listening)
        } else {
            stringResource(id = R.string.ui_dictation_partial, state.partialTranscript)
        }
        VoiceDictationPhase.IDLE,
        VoiceDictationPhase.ERROR,
        -> null
    } ?: return

    Text(
        text = statusText,
        style = MaterialTheme.typography.bodySmall,
        color = if (state.phase == VoiceDictationPhase.LISTENING) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = PocketAgentDimensions.sectionSpacing / 2)
            .testTag("voice_dictation_status")
            .semantics {
                liveRegion = LiveRegionMode.Polite
            },
    )
}

@Composable
@Suppress("CyclomaticComplexMethod")
private fun ComposerInputRow(
    text: String,
    isSending: Boolean,
    isCancelling: Boolean,
    isEditing: Boolean,
    isLandscape: Boolean,
    chatGateState: ChatGateState,
    sendButtonEnabled: Boolean,
    sendButtonDescription: String,
    sendStateDescription: String,
    focusRequester: FocusRequester,
    compactSpacing: androidx.compose.ui.unit.Dp,
    onTextChanged: (String) -> Unit,
    onPrimaryAction: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(compactSpacing),
    ) {
        // IME state is held LOCALLY in the composition as a TextFieldValue. The String overload
        // of OutlinedTextField forces Compose to rebuild a fresh TextFieldValue (including
        // selection/composition spans) on every recomposition triggered by the upstream
        // ViewModel emission, which causes a per-keystroke double recompose and IME re-sync.
        //
        // Strategy: keep the field's truth here (TextFieldValue), forward only the text to
        // ViewModel.onTextChanged for downstream consumers (gate state, send button enable
        // logic, persistence). Reconcile back ONLY when the upstream `text` was set by code
        // outside the composer (e.g. cancelEdit clears, or applyComposerDraft).
        // See docs/architecture/android-performance-contract.md (2026-05-02 RCA).
        var fieldValue by remember {
            mutableStateOf(TextFieldValue(text = text, selection = TextRange(text.length)))
        }
        if (fieldValue.text != text && text != fieldValue.text) {
            // External update from outside the composer (e.g. cancelEdit, applyComposerDraft).
            fieldValue = TextFieldValue(text = text, selection = TextRange(text.length))
        }
        OutlinedTextField(
            value = fieldValue,
            onValueChange = { newValue ->
                val textChanged = newValue.text != fieldValue.text
                fieldValue = newValue
                if (textChanged) {
                    onTextChanged(newValue.text)
                }
            },
            modifier = Modifier
                .weight(1f)
                .testTag("composer_input")
                .focusRequester(focusRequester),
            label = { Text(stringResource(id = R.string.ui_composer_label)) },
            enabled = !isSending,
            maxLines = if (isLandscape) 2 else 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onPrimaryAction() }),
        )
        val sendInteractionSource = remember { MutableInteractionSource() }
        val isPressed by sendInteractionSource.collectIsPressedAsState()
        val sendScale by animateFloatAsState(
            targetValue = if (isPressed) 0.92f else 1f,
            label = "send_scale",
        )
        val isLoadingModel = !isSending && !isEditing &&
            chatGateState.status == ChatGateStatus.LOADING_MODEL
        Button(
            modifier = Modifier
                .testTag("send_button")
                .graphicsLayer {
                    scaleX = sendScale
                    scaleY = sendScale
                }
                .semantics {
                    contentDescription = sendButtonDescription
                    stateDescription = sendStateDescription
                },
            interactionSource = sendInteractionSource,
            onClick = { haptic.tickLight(); onPrimaryAction() },
            enabled = sendButtonEnabled,
        ) {
            if (isLoadingModel) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(compactSpacing))
            }
            Text(
                when {
                    isSending && isCancelling -> stringResource(id = R.string.ui_cancelling_button)
                    isSending -> stringResource(id = R.string.ui_cancel_button)
                    isEditing -> stringResource(id = R.string.ui_update_button)
                    chatGateState.status == ChatGateStatus.BLOCKED_MODEL_MISSING ->
                        stringResource(id = R.string.ui_setup_button)
                    chatGateState.status == ChatGateStatus.BLOCKED_RUNTIME_CHECK ->
                        stringResource(id = R.string.ui_refresh_runtime_checks)
                    chatGateState.status == ChatGateStatus.LOADING_MODEL ->
                        stringResource(id = R.string.ui_loading_button)
                    chatGateState.status == ChatGateStatus.ERROR_RECOVERABLE ->
                        stringResource(id = R.string.ui_retry_button)
                    else -> stringResource(id = R.string.ui_send_button)
                },
            )
        }
    }
}

@Composable
internal fun ChatGateInlineCard(
    chatGateState: ChatGateState,
) {
    Card(modifier = Modifier.fillMaxWidth().testTag("chat_gate_inline_card")) {
        Text(
            text = chatGateInlineMessage(chatGateState),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(PocketAgentDimensions.cardPadding),
        )
    }
}

@Composable
internal fun chatGateInlineMessage(chatGateState: ChatGateState): String {
    return when (chatGateState.status) {
        ChatGateStatus.READY -> ""
        ChatGateStatus.BLOCKED_MODEL_MISSING -> stringResource(id = R.string.ui_chat_gate_blocked_tap_send_to_setup)
        ChatGateStatus.BLOCKED_RUNTIME_CHECK -> stringResource(id = R.string.ui_chat_gate_blocked_runtime_check)
        ChatGateStatus.LOADING_MODEL -> chatGateState.detail?.takeIf { it.isNotBlank() }
            ?: stringResource(id = R.string.ui_chat_gate_loading)
        ChatGateStatus.ERROR_RECOVERABLE -> chatGateState.detail?.takeIf { it.isNotBlank() }
            ?: stringResource(id = R.string.ui_chat_gate_recoverable)
    }
}

internal fun shouldShowChatGateInlineCard(chatGateState: ChatGateState): Boolean {
    return chatGateState.status != ChatGateStatus.READY
}

internal fun hasChatGatePrimaryAction(chatGateState: ChatGateState): Boolean {
    return chatGateState.primaryAction != ChatGatePrimaryAction.NONE
}

internal fun chatGatePrimaryActionLabelResId(action: ChatGatePrimaryAction): Int? {
    return when (action) {
        ChatGatePrimaryAction.GET_READY -> R.string.ui_get_ready
        ChatGatePrimaryAction.OPEN_MODEL_SETUP -> R.string.ui_open_model_setup
        ChatGatePrimaryAction.REFRESH_RUNTIME_CHECKS -> R.string.ui_refresh_runtime_checks
        ChatGatePrimaryAction.NONE -> null
    }
}
