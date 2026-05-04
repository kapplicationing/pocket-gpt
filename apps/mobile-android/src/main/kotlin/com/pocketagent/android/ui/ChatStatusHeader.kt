package com.pocketagent.android.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Sync
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketagent.android.R
import com.pocketagent.android.ui.state.ModelLoadingState
import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.RuntimeUiState
import com.pocketagent.android.ui.state.StartupProbeState
import com.pocketagent.android.ui.theme.PocketAgentDimensions
import java.util.Locale

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun OfflineAndStatusHeader(
    runtime: RuntimeUiState,
    modelLoadingState: ModelLoadingState,
    onOpenModels: () -> Unit,
    canLoadLastUsedModel: Boolean,
    lastUsedModelLabel: String?,
    onLoadLastUsedModel: () -> Unit,
    activeRuntimeModelLabel: String?,
    onRefresh: () -> Unit,
    isOffline: Boolean = false,
) {
    var showTechnicalDetails by remember(runtime.lastErrorTechnicalDetail) {
        mutableStateOf(false)
    }
    val sendInProgress = runtime.sendElapsedMs != null
    val loadedLifecycleNotice = (modelLoadingState as? ModelLoadingState.Loaded)
        ?.detail
        ?.takeIf { detail -> detail.isNotBlank() }

    val isReadyAndClean = modelLoadingState is ModelLoadingState.Loaded
        && runtime.modelRuntimeStatus == ModelRuntimeStatus.READY
        && runtime.lastErrorUserMessage == null
        && loadedLifecycleNotice == null
    AnimatedContent(
        targetState = isReadyAndClean,
        transitionSpec = {
            (fadeIn() + expandVertically(expandFrom = Alignment.Top))
                .togetherWith(fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top))
        },
        label = "StatusHeaderCollapse",
    ) { ready ->
        if (ready) {
            if (isOffline) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PocketAgentDimensions.cardPadding, vertical = PocketAgentDimensions.sectionSpacing)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    horizontalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OfflineStatusChip()
                }
            }
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .padding(PocketAgentDimensions.cardPadding)
                        .animateContentSize()
                        .semantics {
                            liveRegion = LiveRegionMode.Polite
                        },
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
                        verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
                    ) {
                        if (isOffline) {
                            OfflineStatusChip()
                        }
                        AnimatedContent(
                            targetState = modelLoadingState,
                            transitionSpec = {
                                (scaleIn(initialScale = 0.9f) + fadeIn())
                                    .togetherWith(scaleOut(targetScale = 0.9f) + fadeOut())
                            },
                            label = "LifecycleChipAnimation",
                        ) { targetLoadingState ->
                            val targetColors = targetLoadingState.assistChipColors()
                            val targetIcon = targetLoadingState.leadingIcon()
                            val targetLabel = targetLoadingState.readableRuntimeStateLabel()
                            AssistChip(
                                onClick = onOpenModels,
                                colors = targetColors,
                                leadingIcon = {
                                    Icon(
                                        imageVector = targetIcon,
                                        contentDescription = null,
                                    )
                                },
                                label = { StatusChipLabel(targetLabel) },
                            )
                        }
                        if (!activeRuntimeModelLabel.isNullOrBlank()) {
                            AssistChip(
                                onClick = onOpenModels,
                                label = { StatusChipLabel(activeRuntimeModelLabel) },
                            )
                        }
                    }

                    when (modelLoadingState) {
                        is ModelLoadingState.Loading -> {
                            val progress = modelLoadingState.progress?.coerceIn(0f, 1f)
                            if (progress != null && progress > 0f) {
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Text(
                                text = buildString {
                                    append(modelLoadingState.stage)
                                    if (progress != null && progress > 0f) {
                                        append(" ")
                                        append((progress * 100).toInt())
                                        append("%")
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        is ModelLoadingState.Offloading -> {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = if (modelLoadingState.queued) {
                                    stringResource(id = R.string.ui_model_runtime_offload_queued)
                                } else {
                                    stringResource(id = R.string.ui_model_runtime_offload_releasing)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        is ModelLoadingState.Error -> {
                            Text(
                                text = modelLoadingState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        is ModelLoadingState.Loaded -> {
                            loadedLifecycleNotice?.let { detail ->
                                Text(
                                    text = detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        else -> Unit
                    }

                    if (modelLoadingState is ModelLoadingState.Loaded &&
                        runtime.modelRuntimeStatus != ModelRuntimeStatus.READY
                    ) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                        )
                        runtime.modelStatusDetail?.takeIf { it.isNotBlank() }?.let { detail ->
                            Text(
                                text = buildString {
                                    append(detail)
                                    runtime.sendElapsedMs?.let { elapsedMs ->
                                        append(" (")
                                        append(formatSendElapsed(elapsedMs))
                                        append(")")
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        runtime.sendSlowState?.takeIf { it.isNotBlank() }?.let { slowState ->
                            Text(
                                text = slowState,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (
                        modelLoadingState !is ModelLoadingState.Loading &&
                        modelLoadingState !is ModelLoadingState.Offloading &&
                        !sendInProgress
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing)) {
                            TextButton(
                                onClick = onRefresh,
                                modifier = Modifier.testTag("refresh_button"),
                                enabled = runtime.startupProbeState != StartupProbeState.RUNNING,
                            ) {
                                Text(stringResource(id = R.string.ui_refresh_runtime_checks))
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = canLoadLastUsedModel && !lastUsedModelLabel.isNullOrBlank(),
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        if (!lastUsedModelLabel.isNullOrBlank()) {
                            TextButton(onClick = onLoadLastUsedModel) {
                                Text(stringResource(id = R.string.ui_model_runtime_load_last_used_short, lastUsedModelLabel))
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = runtime.lastErrorUserMessage != null,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        runtime.lastErrorUserMessage?.let { errorMessage ->
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                Column(modifier = Modifier.padding(PocketAgentDimensions.cardPadding)) {
                                    Text(
                                        text = errorMessage,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    TextButton(onClick = { showTechnicalDetails = !showTechnicalDetails }) {
                                        Text(
                                            if (showTechnicalDetails) {
                                                stringResource(id = R.string.ui_hide_technical_details)
                                            } else {
                                                stringResource(id = R.string.ui_show_technical_details)
                                            },
                                        )
                                    }
                                    if (showTechnicalDetails) {
                                        runtime.lastErrorTechnicalDetail?.let { technical ->
                                            Text(
                                                text = technical,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                            )
                                        }
                                    }
                                    Text(
                                        text = stringResource(id = runtime.recoveryHintTextResId()),
                                        modifier = Modifier.padding(top = 6.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ModelLoadingState.readableRuntimeStateLabel(): String {
    return when (this) {
        is ModelLoadingState.Idle -> stringResource(id = R.string.ui_model_runtime_state_unloaded)
        is ModelLoadingState.Loading -> stringResource(id = R.string.ui_model_runtime_state_loading)
        is ModelLoadingState.Loaded -> stringResource(id = R.string.ui_model_runtime_state_loaded)
        is ModelLoadingState.Offloading -> {
            if (queued) {
                stringResource(id = R.string.ui_model_runtime_state_offloading_queued)
            } else {
                stringResource(id = R.string.ui_model_runtime_state_offloading)
            }
        }

        is ModelLoadingState.Error -> stringResource(id = R.string.ui_model_runtime_state_failed)
    }
}

internal fun RuntimeUiState.recoveryHintTextResId(): Int {
    val nativeRuntimeMissing = lastErrorTechnicalDetail
        ?.contains("libpocket_llama.so", ignoreCase = true) == true ||
        lastErrorTechnicalDetail?.contains("build is missing native runtime library", ignoreCase = true) == true
    val timeoutError = lastErrorTechnicalDetail
        ?.contains("timed out", ignoreCase = true) == true ||
        lastErrorUserMessage?.contains("timed out", ignoreCase = true) == true
    return if (nativeRuntimeMissing) {
        R.string.ui_native_runtime_missing_hint
    } else if (timeoutError) {
        R.string.ui_runtime_timeout_hint
    } else {
        R.string.ui_model_setup_hint
    }
}

private fun formatSendElapsed(elapsedMs: Long): String {
    return if (elapsedMs < 1_000L) {
        "${elapsedMs} ms"
    } else {
        String.format(Locale.US, "%.1fs", elapsedMs / 1_000.0)
    }
}

@Composable
internal fun StatusChipLabel(text: String) {
    Text(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 220.dp),
    )
}

@Composable
private fun OfflineStatusChip() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            StatusChipLabel(stringResource(id = R.string.ui_offline_indicator))
        }
    }
}

@Composable
private fun ModelLoadingState.assistChipColors() = when (this) {
    is ModelLoadingState.Loaded -> AssistChipDefaults.assistChipColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )
    is ModelLoadingState.Loading -> AssistChipDefaults.assistChipColors(
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
    )
    is ModelLoadingState.Offloading -> AssistChipDefaults.assistChipColors(
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )
    is ModelLoadingState.Error -> AssistChipDefaults.assistChipColors(
        containerColor = MaterialTheme.colorScheme.errorContainer,
        labelColor = MaterialTheme.colorScheme.onErrorContainer,
    )
    is ModelLoadingState.Idle -> AssistChipDefaults.assistChipColors()
}

@Composable
private fun ModelLoadingState.leadingIcon() = when (this) {
    is ModelLoadingState.Loaded -> Icons.Filled.CheckCircle
    is ModelLoadingState.Loading -> Icons.Filled.Sync
    is ModelLoadingState.Offloading -> Icons.Filled.HourglassEmpty
    is ModelLoadingState.Error -> Icons.Filled.Error
    is ModelLoadingState.Idle -> Icons.Filled.RadioButtonUnchecked
}
