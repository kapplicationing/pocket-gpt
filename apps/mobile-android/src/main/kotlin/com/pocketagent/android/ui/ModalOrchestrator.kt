package com.pocketagent.android.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.pocketagent.android.R
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.ui.components.AppBottomSheet
import com.pocketagent.android.ui.components.ConfirmDialog
import com.pocketagent.android.ui.state.ChatUiState
import com.pocketagent.android.ui.state.CompletionSettings
import com.pocketagent.android.ui.state.ModalSurface
import com.pocketagent.android.ui.state.RuntimeKeepAlivePreference
import com.pocketagent.android.runtime.PresetBackingStore
import com.pocketagent.android.voice.VoiceActivationUiState
import com.pocketagent.core.ModelPreset
import com.pocketagent.runtime.RuntimePerformanceProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModalOrchestrator(
    state: ChatUiState,
    voiceState: VoiceActivationUiState,
    provisioningState: ModelProvisioningUiState,
    modelLibraryState: ModelLibraryUiState,
    presetBackingStore: PresetBackingStore,
    pendingRoutingModeSwitch: Pair<String, String>?,
    pendingMeteredWarningVersion: ModelDistributionVersion?,
    downloads: List<DownloadTaskState>,
    onDismissSurface: () -> Unit,
    onUseToolPrompt: (String) -> Unit,
    onDefaultThinkingEnabledChanged: (Boolean) -> Unit,
    onModelPresetSelected: (ModelPreset) -> Unit,
    onOpenPresetCustomization: () -> Unit,
    onPresetBackingChanged: (ModelPreset, String) -> Unit,
    onResetPresetMappings: () -> Unit,
    onPerformanceProfileSelected: (RuntimePerformanceProfile) -> Unit,
    onKeepAlivePreferenceSelected: (RuntimeKeepAlivePreference) -> Unit,
    onVoiceActivationChanged: (Boolean) -> Unit,
    onRequestAssistantRole: () -> Unit,
    onOpenBatteryOptimizationSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onWifiOnlyDownloadsChanged: (Boolean) -> Unit,
    onGpuAccelerationEnabledChanged: (Boolean) -> Unit,
    onExportDiagnostics: () -> Unit,
    completionSettings: CompletionSettings,
    onCompletionSettingsChanged: (CompletionSettings) -> Unit,
    onDismissRoutingModeSwitch: () -> Unit,
    onConfirmRoutingModeSwitch: (String, String) -> Unit,
    onDismissMeteredDownloadWarning: () -> Unit,
    onConfirmMeteredDownloadWarning: (ModelDistributionVersion) -> Unit,
    onOnboardingPageChanged: (Int) -> Unit,
    onNextOnboardingPage: () -> Unit,
    onSkipOnboarding: () -> Unit,
    onFinishOnboarding: () -> Unit,
    onStartOnboardingDownload: () -> Unit,
) {
    if (state.activeSurface is ModalSurface.ToolSuggestions) {
        ToolDialog(
            onDismiss = onDismissSurface,
            onUsePrompt = onUseToolPrompt,
        )
    }

    if (state.activeSurface is ModalSurface.AdvancedSettings) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        AppBottomSheet(
            title = stringResource(id = R.string.ui_advanced_controls_title),
            sheetState = sheetState,
            onDismiss = onDismissSurface,
        ) {
            AdvancedSettingsSheet(
                state = state,
                voiceState = voiceState,
                wifiOnlyDownloadsEnabled = provisioningState.downloadPreferences.wifiOnlyEnabled,
                onDefaultThinkingEnabledChanged = onDefaultThinkingEnabledChanged,
                presetBackingStore = presetBackingStore,
                onModelPresetSelected = onModelPresetSelected,
                onOpenPresetCustomization = onOpenPresetCustomization,
                onPerformanceProfileSelected = onPerformanceProfileSelected,
                onKeepAlivePreferenceSelected = onKeepAlivePreferenceSelected,
                onVoiceActivationChanged = onVoiceActivationChanged,
                onRequestAssistantRole = onRequestAssistantRole,
                onOpenBatteryOptimizationSettings = onOpenBatteryOptimizationSettings,
                onOpenAppSettings = onOpenAppSettings,
                onWifiOnlyDownloadsChanged = onWifiOnlyDownloadsChanged,
                onGpuAccelerationEnabledChanged = onGpuAccelerationEnabledChanged,
                onExportDiagnostics = onExportDiagnostics,
            )
        }
    }

    if (state.activeSurface is ModalSurface.PresetCustomization) {
        val presetSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        AppBottomSheet(
            title = stringResource(id = R.string.ui_preset_customize_title),
            sheetState = presetSheetState,
            onDismiss = onDismissSurface,
        ) {
            PresetCustomizationSheetContent(
                libraryState = modelLibraryState,
                presetBackingStore = presetBackingStore,
                onBackingModelSelected = onPresetBackingChanged,
                onResetToDefaults = onResetPresetMappings,
            )
        }
    }

    if (state.activeSurface is ModalSurface.CompletionSettings) {
        val completionSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        AppBottomSheet(
            title = stringResource(id = R.string.ui_completion_settings_title),
            sheetState = completionSheetState,
            onDismiss = onDismissSurface,
        ) {
            CompletionSettingsSheet(
                settings = completionSettings,
                onSettingsChanged = onCompletionSettingsChanged,
                onClose = onDismissSurface,
            )
        }
    }

    RoutingModeSwitchDialog(
        pending = pendingRoutingModeSwitch,
        onDismiss = onDismissRoutingModeSwitch,
        onConfirm = onConfirmRoutingModeSwitch,
    )

    MeteredDownloadWarningDialog(
        pending = pendingMeteredWarningVersion,
        onDismiss = onDismissMeteredDownloadWarning,
        onConfirm = onConfirmMeteredDownloadWarning,
    )

    if (state.activeSurface is ModalSurface.Onboarding) {
        OnboardingScreen(
            currentPage = state.onboardingPage,
            onPageChanged = onOnboardingPageChanged,
            onNextPage = onNextOnboardingPage,
            onSkip = onSkipOnboarding,
            onFinish = onFinishOnboarding,
            isDownloading = downloads.any { !it.terminal },
            downloadProgress = downloads.firstOrNull { !it.terminal }?.progressPercent?.div(100f),
            onStartDownload = onStartOnboardingDownload,
        )
    }
}

@Composable
private fun RoutingModeSwitchDialog(
    pending: Pair<String, String>?,
    onDismiss: () -> Unit,
    onConfirm: (modelId: String, version: String) -> Unit,
) {
    pending?.let { (modelId, version) ->
        ConfirmDialog(
            title = stringResource(id = R.string.ui_switch_model_title),
            text = stringResource(id = R.string.ui_switch_model_body, modelId, version),
            confirmLabel = stringResource(id = R.string.ui_load),
            dismissLabel = stringResource(id = R.string.ui_later),
            onConfirm = { onConfirm(modelId, version) },
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun MeteredDownloadWarningDialog(
    pending: ModelDistributionVersion?,
    onDismiss: () -> Unit,
    onConfirm: (ModelDistributionVersion) -> Unit,
) {
    pending?.let { version ->
        ConfirmDialog(
            title = stringResource(id = R.string.ui_large_download_metered_title),
            text = stringResource(
                id = R.string.ui_large_download_metered_body,
                version.modelId,
                version.fileSizeBytes.formatAsGiB(),
            ),
            confirmLabel = stringResource(id = R.string.ui_large_download_metered_continue),
            dismissLabel = stringResource(id = R.string.ui_cancel_button),
            onConfirm = { onConfirm(version) },
            onDismiss = onDismiss,
        )
    }
}
