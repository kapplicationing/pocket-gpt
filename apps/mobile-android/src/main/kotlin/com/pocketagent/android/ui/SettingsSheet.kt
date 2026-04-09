package com.pocketagent.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalHapticFeedback
import com.pocketagent.android.R
import com.pocketagent.android.runtime.GpuProbeFailureReason
import com.pocketagent.android.runtime.GpuProbeStatus
import com.pocketagent.android.ui.components.SectionHeader
import com.pocketagent.android.ui.theme.PocketAgentDimensions
import com.pocketagent.android.ui.theme.tickLight
import com.pocketagent.android.ui.state.ChatUiState
import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.RuntimeUiState
import com.pocketagent.android.ui.state.RuntimeKeepAlivePreference
import com.pocketagent.core.RoutingMode
import com.pocketagent.runtime.RuntimePerformanceProfile

@Composable
internal fun AdvancedSettingsSheet(
    state: ChatUiState,
    wifiOnlyDownloadsEnabled: Boolean,
    onDefaultThinkingEnabledChanged: (Boolean) -> Unit,
    onRoutingModeSelected: (RoutingMode) -> Unit,
    onPerformanceProfileSelected: (RuntimePerformanceProfile) -> Unit,
    onKeepAlivePreferenceSelected: (RuntimeKeepAlivePreference) -> Unit,
    onWifiOnlyDownloadsChanged: (Boolean) -> Unit,
    onGpuAccelerationEnabledChanged: (Boolean) -> Unit,
    onExportDiagnostics: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val gpuToggleEnabled = state.runtime.gpuAccelerationSupported || state.runtime.gpuManualOverrideAllowed
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(PocketAgentDimensions.sheetHorizontalPadding)
            .navigationBarsPadding()
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.screenPadding),
    ) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = {
                    haptic.tickLight()
                    selectedTab = 0
                },
                text = { Text(stringResource(id = R.string.ui_tab_general)) },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = {
                    haptic.tickLight()
                    selectedTab = 1
                },
                text = { Text(stringResource(id = R.string.ui_tab_model)) },
            )
            Tab(
                selected = selectedTab == 2,
                onClick = {
                    haptic.tickLight()
                    selectedTab = 2
                },
                text = { Text(stringResource(id = R.string.ui_tab_about)) },
            )
        }

        Spacer(modifier = Modifier.height(PocketAgentDimensions.compactSpacing))

        when (selectedTab) {
            0 -> GeneralTabContent(
                state = state,
                wifiOnlyDownloadsEnabled = wifiOnlyDownloadsEnabled,
                haptic = haptic,
                onPerformanceProfileSelected = onPerformanceProfileSelected,
                onWifiOnlyDownloadsChanged = onWifiOnlyDownloadsChanged,
                onDefaultThinkingEnabledChanged = onDefaultThinkingEnabledChanged,
                onKeepAlivePreferenceSelected = onKeepAlivePreferenceSelected,
            )
            1 -> ModelTabContent(
                state = state,
                gpuToggleEnabled = gpuToggleEnabled,
                haptic = haptic,
                onRoutingModeSelected = onRoutingModeSelected,
                onGpuAccelerationEnabledChanged = onGpuAccelerationEnabledChanged,
            )
            2 -> AboutTabContent(
                state = state,
                haptic = haptic,
                onExportDiagnostics = onExportDiagnostics,
            )
        }
    }
}

@Composable
private fun GeneralTabContent(
    state: ChatUiState,
    wifiOnlyDownloadsEnabled: Boolean,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    onPerformanceProfileSelected: (RuntimePerformanceProfile) -> Unit,
    onWifiOnlyDownloadsChanged: (Boolean) -> Unit,
    onDefaultThinkingEnabledChanged: (Boolean) -> Unit,
    onKeepAlivePreferenceSelected: (RuntimeKeepAlivePreference) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = PocketAgentDimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.screenPadding),
    ) {
        SectionHeader(
            title = stringResource(id = R.string.ui_speed_battery_title),
            subtitle = stringResource(id = R.string.ui_speed_battery_subtitle),
        )
        RuntimePerformanceProfile.entries.forEach { profile ->
            val (label, description) = performanceProfileLabels(profile)
            val profileDescription = stringResource(id = R.string.a11y_performance_profile, label)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = state.runtime.performanceProfile == profile,
                        onClick = { haptic.tickLightThen { onPerformanceProfileSelected(profile) } },
                        role = Role.RadioButton,
                    )
                    .semantics { contentDescription = profileDescription },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = state.runtime.performanceProfile == profile,
                    onClick = null,
                )
                Spacer(modifier = Modifier.width(PocketAgentDimensions.sectionSpacing))
                Column {
                    Text(label)
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        HorizontalDivider()

        SectionHeader(
            title = stringResource(id = R.string.ui_download_controls_title),
            subtitle = stringResource(id = R.string.ui_download_controls_subtitle),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = wifiOnlyDownloadsEnabled,
                    role = Role.Switch,
                    onValueChange = { checked ->
                        haptic.tickLight()
                        onWifiOnlyDownloadsChanged(checked)
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Switch(
                checked = wifiOnlyDownloadsEnabled,
                onCheckedChange = null,
            )
            Spacer(modifier = Modifier.width(PocketAgentDimensions.sectionSpacing))
            Text(stringResource(id = R.string.ui_download_wifi_only_toggle))
        }

        HorizontalDivider()

        SectionHeader(
            title = stringResource(id = R.string.ui_keep_alive_title),
            subtitle = stringResource(id = R.string.ui_keep_alive_subtitle),
        )
        KEEP_ALIVE_UI_OPTIONS.forEach { preference ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = state.runtime.keepAlivePreference == preference,
                        onClick = { haptic.tickLightThen { onKeepAlivePreferenceSelected(preference) } },
                        role = Role.RadioButton,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = state.runtime.keepAlivePreference == preference,
                    onClick = null,
                )
                Spacer(modifier = Modifier.width(PocketAgentDimensions.sectionSpacing))
                Text(keepAlivePreferenceLabel(preference))
            }
        }

        HorizontalDivider()

        SectionHeader(title = stringResource(id = R.string.ui_reasoning_title))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = state.defaultThinkingEnabled,
                    role = Role.Switch,
                    onValueChange = { checked ->
                        haptic.tickLight()
                        onDefaultThinkingEnabledChanged(checked)
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Switch(
                checked = state.defaultThinkingEnabled,
                onCheckedChange = null,
            )
            Spacer(modifier = Modifier.width(PocketAgentDimensions.sectionSpacing))
            Column {
                Text(stringResource(id = R.string.ui_default_thinking_toggle))
                Text(
                    text = stringResource(id = R.string.ui_default_thinking_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(id = R.string.ui_default_thinking_scope_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ModelTabContent(
    state: ChatUiState,
    gpuToggleEnabled: Boolean,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    onRoutingModeSelected: (RoutingMode) -> Unit,
    onGpuAccelerationEnabledChanged: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = PocketAgentDimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.screenPadding),
    ) {
        SectionHeader(
            title = stringResource(id = R.string.ui_model_selection_title),
            subtitle = stringResource(id = R.string.ui_model_selection_subtitle),
        )

        supportedRoutingModes().forEach { mode ->
            val (label, description) = routingModeLabels(mode)
            val routingDescription = stringResource(id = R.string.a11y_routing_mode, label)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = state.runtime.routingMode == mode,
                        onClick = { haptic.tickLightThen { onRoutingModeSelected(mode) } },
                        role = Role.RadioButton,
                    )
                    .semantics { contentDescription = routingDescription },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = state.runtime.routingMode == mode,
                    onClick = null,
                )
                Spacer(modifier = Modifier.width(PocketAgentDimensions.sectionSpacing))
                Column {
                    Text(label)
                    if (description.isNotBlank()) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        HorizontalDivider()

        SectionHeader(title = stringResource(id = R.string.ui_advanced_experimental_title))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = state.runtime.gpuAccelerationEnabled,
                    enabled = gpuToggleEnabled,
                    role = Role.Switch,
                    onValueChange = { checked ->
                        haptic.tickLight()
                        onGpuAccelerationEnabledChanged(checked)
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Switch(
                checked = state.runtime.gpuAccelerationEnabled,
                enabled = gpuToggleEnabled,
                onCheckedChange = null,
            )
            Spacer(modifier = Modifier.width(PocketAgentDimensions.sectionSpacing))
            Text(
                text = when {
                    state.runtime.gpuProbeStatus == GpuProbeStatus.PENDING ->
                        stringResource(id = R.string.ui_gpu_acceleration_validating)
                    state.runtime.gpuAccelerationSupported ->
                        stringResource(id = R.string.ui_gpu_acceleration_toggle)
                    state.runtime.gpuManualOverrideAllowed ->
                        stringResource(id = R.string.ui_gpu_acceleration_toggle_debug_override)
                    else ->
                        stringResource(
                            id = R.string.ui_gpu_acceleration_unavailable_with_reason,
                            gpuProbeFailureReasonLabel(state.runtime.gpuProbeFailureReason),
                        )
                },
            )
        }
        Text(
            text = stringResource(id = R.string.ui_gpu_acceleration_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!state.runtime.gpuProbeDetail.isNullOrBlank()) {
            Text(
                text = state.runtime.gpuProbeDetail.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = if (state.runtime.gpuAccelerationSupported) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
        if (shouldShowOpenClQuantizationWarning(state)) {
            Text(
                text = stringResource(id = R.string.ui_gpu_acceleration_opencl_quant_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun AboutTabContent(
    state: ChatUiState,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    onExportDiagnostics: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = PocketAgentDimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.screenPadding),
    ) {
        SectionHeader(
            title = stringResource(id = R.string.ui_diagnostics_section_title),
            subtitle = stringResource(id = R.string.ui_diagnostics_export_hint),
        )
        TextButton(onClick = { haptic.tickLightThen(onExportDiagnostics) }) {
            Text(stringResource(id = R.string.ui_export_diagnostics))
        }

        HorizontalDivider()

        DiagnosticsSection(
            runtime = state.runtime,
            haptic = haptic,
        )

        HorizontalDivider()

        // --- Privacy (collapsible) ---
        PrivacySection(haptic = haptic)
    }
}

@Composable
private fun DiagnosticsSection(
    runtime: RuntimeUiState,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
) {
    var expanded by remember { mutableStateOf(false) }
    val diagnosticsLabel = stringResource(id = R.string.a11y_toggle_diagnostics)
    val diagnosticsState = stringResource(
        id = if (expanded) R.string.a11y_expanded else R.string.a11y_collapsed,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                haptic.tickLightThen { expanded = !expanded }
            }
            .disclosureSemantics(
                label = diagnosticsLabel,
                stateLabel = diagnosticsState,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(id = R.string.ui_diagnostics_section_title),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = stringResource(id = if (expanded) R.string.ui_hide else R.string.ui_show),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    if (expanded) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(
                modifier = Modifier.padding(PocketAgentDimensions.cardPadding),
                verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.compactSpacing),
            ) {
                DiagnosticLine(
                    label = stringResource(id = R.string.ui_diag_runtime),
                    value = when (runtime.modelRuntimeStatus) {
                        ModelRuntimeStatus.NOT_READY -> stringResource(id = R.string.ui_model_status_not_ready)
                        ModelRuntimeStatus.LOADING -> stringResource(id = R.string.ui_model_status_loading)
                        ModelRuntimeStatus.READY -> stringResource(id = R.string.ui_model_status_ready)
                        ModelRuntimeStatus.ERROR -> stringResource(id = R.string.ui_model_status_error)
                    },
                )
                runtime.runtimeBackend?.let { DiagnosticLine(stringResource(id = R.string.ui_diag_backend), it) }
                runtime.modelStatusDetail?.let { DiagnosticLine(stringResource(id = R.string.ui_diag_detail), it) }
                runtime.activeModelId?.let { DiagnosticLine(stringResource(id = R.string.ui_diag_active_model), it) }
                runtime.activeModelQuantization?.let { DiagnosticLine(stringResource(id = R.string.ui_diag_model_quantization), it) }
                runtime.modelMemoryMode?.let { DiagnosticLine(stringResource(id = R.string.ui_diag_model_memory_mode), it) }
                runtime.prefixCacheMode?.let { DiagnosticLine(stringResource(id = R.string.ui_diag_prefix_cache_mode), it) }
                runtime.lastFirstTokenLatencyMs?.let { DiagnosticLine(stringResource(id = R.string.ui_diag_first_token), stringResource(id = R.string.ui_diag_ms_format, it)) }
                runtime.lastTotalLatencyMs?.let { DiagnosticLine(stringResource(id = R.string.ui_diag_total_latency), stringResource(id = R.string.ui_diag_ms_format, it)) }
                runtime.lastModelLoadMs?.let { DiagnosticLine(stringResource(id = R.string.ui_diag_model_load), stringResource(id = R.string.ui_diag_ms_format, it)) }
                runtime.lastPrefillMs?.let { DiagnosticLine(stringResource(id = R.string.ui_diag_prefill), stringResource(id = R.string.ui_diag_ms_format, it)) }
                runtime.lastDecodeMs?.let { DiagnosticLine(stringResource(id = R.string.ui_diag_decode), stringResource(id = R.string.ui_diag_ms_format, it)) }
                runtime.lastTokensPerSec?.let { DiagnosticLine(stringResource(id = R.string.ui_diag_decode_rate), stringResource(id = R.string.ui_diag_tok_s_format, it)) }
                runtime.lastPeakRssMb?.let { DiagnosticLine(stringResource(id = R.string.ui_diag_peak_rss), stringResource(id = R.string.ui_diag_mb_format, it)) }
                runtime.lastResidentHit?.let {
                    DiagnosticLine(
                        stringResource(id = R.string.ui_diag_resident_hit),
                        stringResource(id = if (it) R.string.ui_runtime_technical_yes else R.string.ui_runtime_technical_no),
                    )
                }
                runtime.lastResidentHitCount?.let { DiagnosticLine(stringResource(id = R.string.ui_diag_resident_hit_count), it.toString()) }
                runtime.lastReloadReason?.let { DiagnosticLine(stringResource(id = R.string.ui_diag_reload_reason), it) }
                runtime.lastPrefixCacheHit?.let {
                    DiagnosticLine(
                        stringResource(id = R.string.ui_diag_prefix_cache_hit),
                        stringResource(id = if (it) R.string.ui_runtime_technical_yes else R.string.ui_runtime_technical_no),
                    )
                }
                runtime.lastPrefixCacheReusedTokens?.let { DiagnosticLine(stringResource(id = R.string.ui_diag_prefix_cache_reused_tokens), it.toString()) }
                runtime.lastPrefixCacheHitRate?.let {
                    DiagnosticLine(
                        stringResource(id = R.string.ui_diag_prefix_cache_hit_rate),
                        String.format(java.util.Locale.US, "%.2f", it),
                    )
                }
            }
        }
    }
}

@Composable
private fun PrivacySection(
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
) {
    var expanded by remember { mutableStateOf(false) }
    val privacyLabel = stringResource(id = R.string.a11y_toggle_privacy)
    val privacyState = stringResource(
        id = if (expanded) R.string.a11y_expanded else R.string.a11y_collapsed,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                haptic.tickLightThen { expanded = !expanded }
            }
            .disclosureSemantics(
                label = privacyLabel,
                stateLabel = privacyState,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(id = R.string.ui_privacy_title),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = stringResource(id = if (expanded) R.string.ui_hide else R.string.ui_show),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    if (expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.tightSpacing)) {
            Text(stringResource(id = R.string.ui_privacy_item_1), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(id = R.string.ui_privacy_item_2), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(id = R.string.ui_privacy_item_3), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun performanceProfileLabels(profile: RuntimePerformanceProfile): Pair<String, String> {
    return when (profile) {
        RuntimePerformanceProfile.BATTERY -> Pair(
            stringResource(id = R.string.ui_performance_profile_battery),
            stringResource(id = R.string.ui_performance_profile_battery_desc),
        )
        RuntimePerformanceProfile.BALANCED -> Pair(
            stringResource(id = R.string.ui_performance_profile_balanced),
            stringResource(id = R.string.ui_performance_profile_balanced_desc),
        )
        RuntimePerformanceProfile.FAST -> Pair(
            stringResource(id = R.string.ui_performance_profile_fast),
            stringResource(id = R.string.ui_performance_profile_fast_desc),
        )
    }
}

@Composable
private fun routingModeLabels(mode: RoutingMode): Pair<String, String> {
    return when (mode) {
        RoutingMode.AUTO -> Pair(
            stringResource(id = R.string.ui_routing_mode_auto),
            stringResource(id = R.string.ui_routing_mode_auto_desc),
        )
        else -> Pair(mode.name, "")
    }
}

@Composable
private fun gpuProbeFailureReasonLabel(reason: String?): String {
    return when (reason) {
        GpuProbeFailureReason.MODEL_UNAVAILABLE.name ->
            stringResource(id = R.string.ui_gpu_acceleration_reason_model_required)
        null, "" ->
            stringResource(id = R.string.ui_gpu_acceleration_reason_unknown)
        else -> reason.lowercase().replace('_', ' ')
    }
}

private val KEEP_ALIVE_UI_OPTIONS = listOf(
    RuntimeKeepAlivePreference.AUTO,
    RuntimeKeepAlivePreference.ALWAYS,
    RuntimeKeepAlivePreference.FIVE_MINUTES,
    RuntimeKeepAlivePreference.UNLOAD_IMMEDIATELY,
)

@Composable
private fun keepAlivePreferenceLabel(preference: RuntimeKeepAlivePreference): String {
    return when (preference) {
        RuntimeKeepAlivePreference.AUTO -> stringResource(id = R.string.ui_keep_alive_auto)
        RuntimeKeepAlivePreference.ALWAYS -> stringResource(id = R.string.ui_keep_alive_always)
        RuntimeKeepAlivePreference.ONE_MINUTE -> stringResource(id = R.string.ui_keep_alive_one_minute)
        RuntimeKeepAlivePreference.FIVE_MINUTES -> stringResource(id = R.string.ui_keep_alive_five_minutes)
        RuntimeKeepAlivePreference.FIFTEEN_MINUTES -> stringResource(id = R.string.ui_keep_alive_fifteen_minutes)
        RuntimeKeepAlivePreference.UNLOAD_IMMEDIATELY -> stringResource(id = R.string.ui_keep_alive_unload_immediately)
    }
}

// --- GPU Quantization Compatibility Utilities ---

private fun shouldShowOpenClQuantizationWarning(state: ChatUiState): Boolean {
    return shouldShowOpenClQuantizationWarning(runtime = state.runtime)
}

internal fun shouldShowOpenClQuantizationWarning(runtime: RuntimeUiState): Boolean {
    if (!runtime.gpuAccelerationEnabled) {
        return false
    }
    val activeBackend = runtime.activeBackend?.trim()?.lowercase().orEmpty()
    val backendProfile = runtime.backendProfile?.trim()?.lowercase().orEmpty()
    val compiledBackends = runtime.compiledBackend?.trim()?.lowercase().orEmpty()
    val backendMayUseOpenCl = activeBackend == "opencl" ||
        backendProfile == "opencl" ||
        (backendProfile == "auto" && compiledBackends.contains("opencl"))
    if (!backendMayUseOpenCl) {
        return false
    }
    val quantHint = runtime.activeModelQuantization?.trim()?.lowercase().orEmpty()
    val modelId = runtime.activeModelId?.trim()?.lowercase().orEmpty()
    val quantSource = when {
        quantHint.isNotBlank() && quantHint != "unknown" -> quantHint
        modelId.isNotBlank() -> modelId
        else -> ""
    }
    if (quantSource.isBlank()) {
        return false
    }
    if (OPENCL_SAFE_QUANT_MODEL_REGEX.containsMatchIn(quantSource)) {
        return false
    }
    return OPENCL_UNSUPPORTED_QUANT_MODEL_REGEX.containsMatchIn(quantSource)
}

internal val OPENCL_SAFE_QUANT_MODEL_REGEX = Regex(
    """(?:^|[._-])(q4[._-]?0|q6[._-]?k|q8[._-]?0|f16|f32|fp16|fp32|mxfp4(?:[._-]moe)?)(?:[._-]|$)""",
    RegexOption.IGNORE_CASE,
)

internal val OPENCL_UNSUPPORTED_QUANT_MODEL_REGEX = Regex(
    """(?:^|[._-])(q(?:[1-8][._-](?:k|[0-9])[._-]?[a-z0-9_]*|5|8)|iq[1-4](?:[._-][a-z]+)?)(?:[._-]|$)""",
    RegexOption.IGNORE_CASE,
)
