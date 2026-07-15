@file:Suppress("LongMethod", "MaxLineLength")

package com.pocketagent.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import com.pocketagent.android.R
import com.pocketagent.android.ui.components.SectionHeader
import com.pocketagent.android.ui.state.CompletionSettings
import com.pocketagent.android.ui.theme.PocketAgentDimensions
import com.pocketagent.android.ui.theme.tickLight
import com.pocketagent.runtime.DEFAULT_CHAT_MAX_TOKENS
import com.pocketagent.runtime.MAX_APP_CONTEXT_TOKENS
import kotlin.math.roundToInt

@Composable
internal fun CompletionSettingsSheet(
    settings: CompletionSettings,
    onSettingsChanged: (CompletionSettings) -> Unit,
    onClose: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var temperature by remember(settings) { mutableFloatStateOf(settings.temperature) }
    var topP by remember(settings) { mutableFloatStateOf(settings.topP) }
    var topK by remember(settings) { mutableIntStateOf(settings.topK) }
    var maxTokens by remember(settings) { mutableIntStateOf(settings.maxTokens) }
    var repeatPenalty by remember(settings) { mutableFloatStateOf(settings.repeatPenalty) }
    var frequencyPenalty by remember(settings) { mutableFloatStateOf(settings.frequencyPenalty) }
    var presencePenalty by remember(settings) { mutableFloatStateOf(settings.presencePenalty) }
    var systemPrompt by remember(settings.systemPrompt) { mutableStateOf(TextFieldValue(settings.systemPrompt)) }
    var lastCommittedSystemPrompt by remember(settings.systemPrompt) { mutableStateOf(settings.systemPrompt) }
    var showAdvanced by remember { mutableStateOf(false) }

    LaunchedEffect(settings.systemPrompt) {
        if (settings.systemPrompt != systemPrompt.text) {
            systemPrompt = TextFieldValue(settings.systemPrompt)
        }
    }

    fun emitUpdate() {
        lastCommittedSystemPrompt = systemPrompt.text
        onSettingsChanged(
            CompletionSettings(
                temperature = temperature,
                topP = topP,
                topK = topK,
                maxTokens = maxTokens,
                repeatPenalty = repeatPenalty,
                frequencyPenalty = frequencyPenalty,
                presencePenalty = presencePenalty,
                systemPrompt = systemPrompt.text,
                showThinking = settings.showThinking,
            ),
        )
    }

    fun commitSystemPromptIfChanged() {
        if (systemPrompt.text != lastCommittedSystemPrompt) {
            emitUpdate()
        }
    }

    fun resetDefaults() {
        val defaults = CompletionSettings(showThinking = settings.showThinking)
        temperature = defaults.temperature
        topP = defaults.topP
        topK = defaults.topK
        maxTokens = defaults.maxTokens
        repeatPenalty = defaults.repeatPenalty
        frequencyPenalty = defaults.frequencyPenalty
        presencePenalty = defaults.presencePenalty
        systemPrompt = TextFieldValue(defaults.systemPrompt)
        onSettingsChanged(defaults)
    }

    val currentCommitSystemPrompt by rememberUpdatedState(newValue = { commitSystemPromptIfChanged() })
    DisposableEffect(Unit) {
        onDispose {
            currentCommitSystemPrompt()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = PocketAgentDimensions.sheetHorizontalPadding)
            .navigationBarsPadding(),
        contentPadding = PaddingValues(bottom = PocketAgentDimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
    ) {
        item(key = "completion_reset") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    haptic.tickLight()
                    resetDefaults()
                }) {
                    Text(stringResource(id = R.string.ui_completion_reset_defaults))
                }
            }
        }

        item(key = "completion_system_prompt") {
            Column(
                verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
            ) {
                SectionHeader(
                    title = stringResource(id = R.string.ui_completion_system_prompt_label),
                    subtitle = stringResource(id = R.string.ui_completion_system_prompt_desc),
                )
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("completion_system_prompt_input")
                        .onFocusChanged { focusState ->
                            if (!focusState.isFocused) {
                                commitSystemPromptIfChanged()
                            }
                        },
                    minLines = 3,
                    maxLines = 6,
                    placeholder = {
                        Text(stringResource(id = R.string.ui_completion_system_prompt_placeholder))
                    },
                )
            }
        }

        item(key = "completion_common") {
            CompletionCommonSettingsSection(
                temperature = temperature,
                maxTokens = maxTokens,
                onTemperatureChanged = { temperature = it },
                onMaxTokensChanged = { maxTokens = it },
                onValueChangeFinished = { emitUpdate() },
            )
        }

        item(key = "completion_thinking") {
            CompletionThinkingSection(showThinking = settings.showThinking)
        }

        item(key = "completion_advanced") {
            CompletionAdvancedSettingsSection(
                showAdvanced = showAdvanced,
                topP = topP,
                topK = topK,
                repeatPenalty = repeatPenalty,
                frequencyPenalty = frequencyPenalty,
                presencePenalty = presencePenalty,
                onToggle = {
                    haptic.tickLight()
                    showAdvanced = !showAdvanced
                },
                onTopPChanged = { topP = it },
                onTopKChanged = { topK = it },
                onRepeatPenaltyChanged = { repeatPenalty = it },
                onFrequencyPenaltyChanged = { frequencyPenalty = it },
                onPresencePenaltyChanged = { presencePenalty = it },
                onValueChangeFinished = { emitUpdate() },
            )
        }

        item(key = "completion_done") {
            Button(
                onClick = {
                    haptic.tickLight()
                    commitSystemPromptIfChanged()
                    onClose()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(id = R.string.ui_completion_done))
            }
        }
    }
}

@Composable
private fun CompletionCommonSettingsSection(
    temperature: Float,
    maxTokens: Int,
    onTemperatureChanged: (Float) -> Unit,
    onMaxTokensChanged: (Int) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
    ) {
        HorizontalDivider(modifier = Modifier.padding(vertical = PocketAgentDimensions.sectionSpacing))
        SectionHeader(title = stringResource(id = R.string.ui_completion_common_section))
        SliderSetting(
            label = stringResource(id = R.string.ui_completion_temperature_label),
            description = stringResource(id = R.string.ui_completion_temperature_desc),
            value = temperature,
            valueRange = 0f..2f,
            valueLabel = "%.2f".format(temperature),
            onValueChange = onTemperatureChanged,
            onValueChangeFinished = onValueChangeFinished,
        )
        SliderSetting(
            label = stringResource(id = R.string.ui_completion_max_tokens_label),
            description = stringResource(id = R.string.ui_completion_max_tokens_desc),
            value = maxTokens.toFloat(),
            valueRange = DEFAULT_CHAT_MAX_TOKENS.toFloat()..MAX_APP_CONTEXT_TOKENS.toFloat(),
            valueLabel = maxTokens.toString(),
            onValueChange = { onMaxTokensChanged(it.roundToInt()) },
            onValueChangeFinished = onValueChangeFinished,
        )
    }
}

@Composable
private fun CompletionThinkingSection(showThinking: Boolean) {
    Column(
        verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
    ) {
        HorizontalDivider(modifier = Modifier.padding(vertical = PocketAgentDimensions.sectionSpacing))
        Text(
            text = stringResource(
                id = R.string.ui_completion_thinking_status,
                if (showThinking) {
                    stringResource(id = R.string.ui_completion_thinking_on)
                } else {
                    stringResource(id = R.string.ui_completion_thinking_off)
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(id = R.string.ui_completion_thinking_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CompletionAdvancedSettingsSection(
    showAdvanced: Boolean,
    topP: Float,
    topK: Int,
    repeatPenalty: Float,
    frequencyPenalty: Float,
    presencePenalty: Float,
    onToggle: () -> Unit,
    onTopPChanged: (Float) -> Unit,
    onTopKChanged: (Int) -> Unit,
    onRepeatPenaltyChanged: (Float) -> Unit,
    onFrequencyPenaltyChanged: (Float) -> Unit,
    onPresencePenaltyChanged: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
    ) {
        HorizontalDivider(modifier = Modifier.padding(vertical = PocketAgentDimensions.sectionSpacing))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .semantics(mergeDescendants = true) { role = Role.Button },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(id = R.string.ui_completion_advanced_section),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = if (showAdvanced) {
                    stringResource(id = R.string.action_hide_advanced)
                } else {
                    stringResource(id = R.string.action_show_advanced)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (showAdvanced) {
            SliderSetting(
                label = stringResource(id = R.string.ui_completion_top_p_label),
                description = stringResource(id = R.string.ui_completion_top_p_desc),
                value = topP,
                valueRange = 0f..1f,
                valueLabel = "%.2f".format(topP),
                onValueChange = onTopPChanged,
                onValueChangeFinished = onValueChangeFinished,
            )
            SliderSetting(
                label = stringResource(id = R.string.ui_completion_top_k_label),
                description = stringResource(id = R.string.ui_completion_top_k_desc),
                value = topK.toFloat(),
                valueRange = 1f..200f,
                valueLabel = topK.toString(),
                onValueChange = { onTopKChanged(it.roundToInt()) },
                onValueChangeFinished = onValueChangeFinished,
            )
            SliderSetting(
                label = stringResource(id = R.string.ui_completion_repeat_penalty_label),
                description = stringResource(id = R.string.ui_completion_repeat_penalty_desc),
                value = repeatPenalty,
                valueRange = 0.5f..2f,
                valueLabel = "%.2f".format(repeatPenalty),
                onValueChange = onRepeatPenaltyChanged,
                onValueChangeFinished = onValueChangeFinished,
            )
            SliderSetting(
                label = stringResource(id = R.string.ui_completion_frequency_penalty_label),
                description = stringResource(id = R.string.ui_completion_frequency_penalty_desc),
                value = frequencyPenalty,
                valueRange = 0f..2f,
                valueLabel = "%.2f".format(frequencyPenalty),
                onValueChange = onFrequencyPenaltyChanged,
                onValueChangeFinished = onValueChangeFinished,
            )
            SliderSetting(
                label = stringResource(id = R.string.ui_completion_presence_penalty_label),
                description = stringResource(id = R.string.ui_completion_presence_penalty_desc),
                value = presencePenalty,
                valueRange = 0f..2f,
                valueLabel = "%.2f".format(presencePenalty),
                onValueChange = onPresencePenaltyChanged,
                onValueChangeFinished = onValueChangeFinished,
            )
        }
    }
}

@Composable
private fun SliderSetting(
    label: String,
    description: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    val sliderDescription = stringResource(
        id = R.string.a11y_setting_value,
        label,
        valueLabel,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = valueLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        text = description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = sliderDescription },
    )
}
