package com.pocketagent.android.ui

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.pocketagent.android.runtime.GpuProbeFailureReason
import com.pocketagent.android.runtime.GpuProbeResult
import com.pocketagent.android.runtime.GpuProbeStatus
import com.pocketagent.android.runtime.RuntimeDiagnosticsSnapshot
import com.pocketagent.android.ui.state.FirstSessionStage
import com.pocketagent.android.ui.state.ModalSurface
import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.RuntimeKeepAlivePreference
import com.pocketagent.android.ui.state.StartupProbeState
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.runtime.RuntimePerformanceProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal fun ChatViewModel.setRoutingModeInternal(mode: RoutingMode) {
    val effectiveMode = coerceSupportedRoutingMode(mode)
    if (_uiState.value.runtime.routingMode == effectiveMode) {
        return
    }
    runtimeFacade.setRoutingMode(effectiveMode)
    _uiState.update { state ->
        state.copy(runtime = state.runtime.copy(routingMode = effectiveMode))
    }
    persistState()
}

internal fun ChatViewModel.setPerformanceProfileInternal(profile: RuntimePerformanceProfile) {
    if (_uiState.value.runtime.performanceProfile == profile) {
        return
    }
    _uiState.update { state ->
        state.copy(
            runtime = state.runtime.copy(
                performanceProfile = profile,
                modelStatusDetail = performanceProfileStatusDetail(
                    profile = profile,
                    gpuEnabled = state.runtime.gpuAccelerationEnabled,
                    gpuSupported = state.runtime.gpuAccelerationSupported,
                ),
            ),
        )
    }
    persistState()
}

internal fun ChatViewModel.setKeepAlivePreferenceInternal(preference: RuntimeKeepAlivePreference) {
    if (_uiState.value.runtime.keepAlivePreference == preference) {
        return
    }
    _uiState.update { state ->
        state.copy(runtime = state.runtime.copy(keepAlivePreference = preference))
    }
    persistState()
}

internal fun ChatViewModel.setGpuAccelerationEnabledInternal(enabled: Boolean) {
    val snapshot = _uiState.value.runtime
    val supported = snapshot.gpuAccelerationSupported || snapshot.gpuManualOverrideAllowed
    val effective = enabled && supported
    val detail = if (enabled && !snapshot.gpuAccelerationSupported) {
        if (snapshot.gpuManualOverrideAllowed) {
            "Debug override enabled. Runtime may still fall back to CPU. ${snapshot.gpuProbeDetail.orEmpty()}".trim()
        } else {
            when (snapshot.gpuProbeStatus) {
                GpuProbeStatus.PENDING ->
                    "Validating GPU support... keeping CPU until probe is qualified."
                GpuProbeStatus.FAILED ->
                    "GPU acceleration unavailable (${snapshot.gpuProbeFailureReason ?: "probe_failed"}). ${snapshot.gpuProbeDetail.orEmpty()}".trim()
                else ->
                    "GPU acceleration is unavailable on this build/device. Using CPU."
            }
        }
    } else {
        performanceProfileStatusDetail(
            profile = snapshot.performanceProfile,
            gpuEnabled = effective,
            gpuSupported = snapshot.gpuAccelerationSupported,
        )
    }
    if (snapshot.gpuAccelerationEnabled == effective && snapshot.modelStatusDetail == detail) {
        return
    }
    _uiState.update { state ->
        state.copy(
            runtime = state.runtime.copy(
                gpuAccelerationEnabled = effective,
                modelStatusDetail = detail,
            ),
        )
    }
    persistState()
}

internal fun ChatViewModel.prefillComposerInternal(text: String) {
    applyComposerDraft(text)
    _uiState.update { state ->
        state.copy(activeSurface = ModalSurface.None)
    }
}

internal fun ChatViewModel.nextOnboardingPageInternal() {
    _uiState.update { state ->
        state.copy(onboardingPage = (state.onboardingPage + 1).coerceAtMost(ONBOARDING_LAST_PAGE))
    }
}

internal fun ChatViewModel.setOnboardingPageInternal(page: Int) {
    val clamped = page.coerceIn(0, ONBOARDING_LAST_PAGE)
    if (_uiState.value.onboardingPage == clamped) {
        return
    }
    _uiState.update { state ->
        state.copy(onboardingPage = clamped)
    }
}

internal fun ChatViewModel.completeOnboardingInternal() {
    _uiState.update { state ->
        state.copy(
            activeSurface = ModalSurface.None,
            onboardingPage = ONBOARDING_LAST_PAGE,
            firstSessionStage = if (sendFlow.isRuntimeReadyForSend(state.runtime)) {
                FirstSessionStage.READY_TO_CHAT
            } else {
                FirstSessionStage.GET_READY
            },
        )
    }
    recordFirstSessionEventOnce(TELEMETRY_EVENT_SIMPLE_FIRST_ENTERED)
    persistState()
}

internal fun ChatViewModel.skipOnboardingInternal() {
    completeOnboardingInternal()
}

internal fun ChatViewModel.refreshRuntimeReadinessInternal(statusDetailOverride: String? = null) {
    val activeRequestId = activeSendRequestId
    val activeSessionId = _uiState.value.activeSessionId
    viewModelScope.launch(ioDispatcher) {
        activeRequestId?.let(runtimeFacade::cancelGenerationByRequest)
        activeSessionId?.let { sessionId ->
            runtimeFacade.cancelGeneration(SessionId(sessionId))
        }
    }
    launchStartupProbeInternal(statusDetailOverride)
}

internal fun ChatViewModel.onGetReadyTappedInternal() {
    _uiState.update { state ->
        state.copy(firstSessionStage = FirstSessionStage.GET_READY)
    }
    recordFirstSessionEventOnce(TELEMETRY_EVENT_GET_READY_STARTED)
    persistState()
}

internal fun ChatViewModel.onFirstAnswerCompletedInternal() {
    _uiState.update { state ->
        state.copy(
            firstAnswerCompleted = true,
            firstSessionStage = FirstSessionStage.FIRST_ANSWER_DONE,
        )
    }
    recordFirstSessionEventOnce(TELEMETRY_EVENT_FIRST_ANSWER_COMPLETED)
    persistState()
}

internal fun ChatViewModel.onFollowUpCompletedInternal() {
    _uiState.update { state ->
        state.copy(
            followUpCompleted = true,
            firstSessionStage = FirstSessionStage.FOLLOW_UP_DONE,
        )
    }
    recordFirstSessionEventOnce(TELEMETRY_EVENT_FOLLOW_UP_COMPLETED)
    persistState()
}

internal fun ChatViewModel.onAdvancedUnlockedInternal() {
    _uiState.update { state ->
        state.copy(
            advancedUnlocked = true,
            firstSessionStage = FirstSessionStage.ADVANCED_UNLOCKED,
        )
    }
    recordFirstSessionEventOnce(TELEMETRY_EVENT_ADVANCED_UNLOCKED)
    persistState()
}

internal fun ChatViewModel.bootstrapStateInternal() {
    val loadedState = persistenceFlow.loadBootstrapState()
    val bootstrapResult = startupFlow.bootstrap(loadedState)
    _uiState.value = bootstrapResult.state
    applyComposerDraft(bootstrapResult.state.composer.text)
    bootstrapResult.hydrateSessionId?.let { sessionId ->
        hydrateSessionMessagesIfNeeded(sessionId)
    }
    refreshRuntimeDiagnostics()
    refreshGpuProbeStatusIfPendingInternal()
    ensureSimpleFirstEnteredTelemetryIfNeeded()
    if (bootstrapResult.shouldPersist) {
        persistState()
    }
    if (bootstrapResult.shouldRunStartupProbe) {
        launchStartupProbeInternal()
    }
}

internal fun ChatViewModel.launchStartupProbeInternal(statusDetailOverride: String? = null) {
    startupProbeOrchestrator.launch(statusDetailOverride = statusDetailOverride)
}

internal fun ChatViewModel.refreshGpuProbeStatusIfPendingInternal() {
    if (_uiState.value.runtime.gpuProbeStatus != GpuProbeStatus.PENDING) {
        gpuProbeRefreshJob?.cancel()
        gpuProbeRefreshJob = null
        return
    }
    if (gpuProbeRefreshJob?.isActive == true) {
        return
    }
    gpuProbeRefreshJob = viewModelScope.launch(ioDispatcher) {
        while (isActive) {
            val nextProbe = runCatching { runtimeFacade.gpuOffloadStatus() }.getOrElse {
                GpuProbeResult(
                    status = GpuProbeStatus.FAILED,
                    failureReason = GpuProbeFailureReason.UNKNOWN,
                    detail = "gpu_probe_refresh_failed:${it.message ?: it::class.simpleName}",
                )
            }
            val changed = updateRuntimeGpuProbeStateInternal(nextProbe)
            if (changed) {
                persistState()
            }
            if (nextProbe.status != GpuProbeStatus.PENDING) {
                return@launch
            }
            delay(GPU_PROBE_REFRESH_INTERVAL_MS)
        }
    }
}

internal fun ChatViewModel.updateRuntimeGpuProbeStateInternal(probe: GpuProbeResult): Boolean {
    var changed = false
    _uiState.update { state ->
        val gpuSupported = probe.status == GpuProbeStatus.QUALIFIED && probe.maxStableGpuLayers > 0
        val runtime = state.runtime
        val nextRuntime = runtime.copy(
            gpuAccelerationSupported = gpuSupported,
            gpuAccelerationEnabled = runtime.gpuAccelerationEnabled && (gpuSupported || runtime.gpuManualOverrideAllowed),
            gpuProbeStatus = probe.status,
            gpuProbeFailureReason = probe.failureReason?.name,
            gpuProbeDetail = probe.detail,
            gpuMaxQualifiedLayers = probe.maxStableGpuLayers,
        )
        changed = runtime != nextRuntime
        if (!changed) {
            state
        } else {
            state.copy(runtime = nextRuntime)
        }
    }
    return changed
}

internal fun ChatViewModel.refreshRuntimeDiagnostics() {
    viewModelScope.launch(ioDispatcher) {
        val diagnostics = runCatching { runtimeFacade.runtimeDiagnosticsSnapshot() }
            .getOrDefault(RuntimeDiagnosticsSnapshot())
        val loadedModel = runCatching { runtimeFacade.loadedModel() }.getOrNull()
        _uiState.update { state ->
            state.copy(
                runtime = state.runtime.copy(
                    activeModelId = loadedModel?.modelId ?: state.runtime.activeModelId,
                    activeBackend = diagnostics.activeBackend ?: state.runtime.activeBackend,
                    backendProfile = diagnostics.backendProfile ?: state.runtime.backendProfile,
                    compiledBackend = diagnostics.compiledBackend ?: state.runtime.compiledBackend,
                    activeModelQuantization = diagnostics.activeModelQuantization
                        ?: state.runtime.activeModelQuantization,
                    modelMemoryMode = diagnostics.modelMemoryMode ?: state.runtime.modelMemoryMode,
                    prefixCacheMode = diagnostics.prefixCacheMode ?: state.runtime.prefixCacheMode,
                    nativeRuntimeSupported = diagnostics.nativeRuntimeSupported
                        ?: state.runtime.nativeRuntimeSupported,
                    strictAcceleratorFailFast = diagnostics.strictAcceleratorFailFast
                        ?: state.runtime.strictAcceleratorFailFast,
                    autoBackendCpuFallback = diagnostics.autoBackendCpuFallback
                        ?: state.runtime.autoBackendCpuFallback,
                ),
            )
        }
    }
}

internal fun ChatViewModel.logStartupProbeInternal(
    phase: String,
    probeToken: Long,
    statusDetailOverride: String?,
    error: Throwable? = null,
) {
    val message = "STARTUP_PROBE|phase=$phase|token=$probeToken|detail=${statusDetailOverride.orEmpty()}"
    runCatching {
        if (error == null) {
            Log.i(LOG_TAG, message)
        } else {
            Log.w(LOG_TAG, message, error)
        }
    }
}
