package com.pocketagent.android.ui

import androidx.compose.runtime.Immutable
import com.pocketagent.android.runtime.RuntimeProvisioningSnapshot
import com.pocketagent.android.runtime.modelmanager.DownloadProcessingStage
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.DownloadTaskStatus
import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.modelmanager.bundleTotalBytes
import com.pocketagent.android.ui.state.ModelLoadingState
import com.pocketagent.inference.ModelDisplayNames

@Immutable
internal enum class OnboardingSetupPhase {
    NOT_STARTED,
    PREPARING,
    DOWNLOADING,
    PAUSED,
    CHECKING,
    FINISHING,
    STARTING,
    READY,
    NEEDS_ATTENTION,
}

@Immutable
internal data class OnboardingSetupUiState(
    val phase: OnboardingSetupPhase = OnboardingSetupPhase.NOT_STARTED,
    val modelId: String? = null,
    val version: String? = null,
    val modelName: String? = null,
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val progress: Float? = null,
    val downloadSpeedBps: Long? = null,
    val etaSeconds: Long? = null,
    val alreadyInstalled: Boolean = false,
    val hasDownloadTask: Boolean = false,
    val setupRequestInFlight: Boolean = false,
    val detail: String? = null,
)

private data class OnboardingSetupTarget(
    val modelId: String?,
    val version: String?,
    val modelName: String?,
    val distributionVersion: ModelDistributionVersion?,
)

internal fun resolveOnboardingSetupUiState(
    defaultModelId: String?,
    manifest: ModelDistributionManifest,
    provisioningSnapshot: RuntimeProvisioningSnapshot?,
    downloads: List<DownloadTaskState>,
    pendingActivation: Pair<String, String>?,
    modelLoadingState: ModelLoadingState,
    sendReady: Boolean,
    setupFailureMessage: String? = null,
    setupRequestInFlight: Boolean = false,
): OnboardingSetupUiState {
    val target = resolveOnboardingSetupTarget(
        manifest = manifest,
        defaultModelId = defaultModelId,
        pendingActivation = pendingActivation,
    )
    val matchingTask = downloads.latestTaskFor(
        target = target,
        includeTerminal = pendingActivation != null,
    )
    val base = target.toBaseUiState(
        provisioningSnapshot = provisioningSnapshot,
        matchingTask = matchingTask,
    ).copy(setupRequestInFlight = setupRequestInFlight)
    return resolveRuntimeSetupState(
        base = base,
        target = target,
        modelLoadingState = modelLoadingState,
        sendReady = sendReady,
    ) ?: setupFailureMessage
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { message ->
            base.copy(
                phase = OnboardingSetupPhase.NEEDS_ATTENTION,
                detail = message,
            )
        }
        ?: matchingTask?.toOnboardingSetupState(base)
        ?: base.copy(
            phase = when {
                pendingActivation != null && base.alreadyInstalled -> OnboardingSetupPhase.STARTING
                pendingActivation != null || setupRequestInFlight -> OnboardingSetupPhase.PREPARING
                else -> OnboardingSetupPhase.NOT_STARTED
            },
        )
}

private fun resolveOnboardingSetupTarget(
    manifest: ModelDistributionManifest,
    defaultModelId: String?,
    pendingActivation: Pair<String, String>?,
): OnboardingSetupTarget {
    val defaultVersion = resolveDefaultGetReadyVersion(
        manifest = manifest,
        defaultModelId = defaultModelId,
    )
    val modelId = pendingActivation?.first ?: defaultVersion?.modelId ?: defaultModelId
    return OnboardingSetupTarget(
        modelId = modelId,
        version = pendingActivation?.second ?: defaultVersion?.version,
        modelName = modelId?.let(ModelDisplayNames::displayNameFor) ?: defaultVersion?.displayName,
        distributionVersion = defaultVersion,
    )
}

private fun List<DownloadTaskState>.latestTaskFor(
    target: OnboardingSetupTarget,
    includeTerminal: Boolean,
): DownloadTaskState? {
    return asSequence()
        .filter { task ->
            task.modelId == target.modelId &&
                (target.version == null || task.version == target.version)
        }
        .filter { task -> includeTerminal || !task.terminal }
        .maxByOrNull { task -> task.updatedAtEpochMs }
}

private fun OnboardingSetupTarget.toBaseUiState(
    provisioningSnapshot: RuntimeProvisioningSnapshot?,
    matchingTask: DownloadTaskState?,
): OnboardingSetupUiState {
    val installed = provisioningSnapshot?.models
        ?.firstOrNull { model -> model.modelId == modelId }
        ?.installedVersions
        ?.any { installedVersion -> version == null || installedVersion.version == version }
        ?: false
    return OnboardingSetupUiState(
        modelId = modelId,
        version = version,
        modelName = modelName,
        totalBytes = matchingTask?.totalBytes?.takeIf { bytes -> bytes > 0L }
            ?: distributionVersion?.bundleTotalBytes()?.coerceAtLeast(0L)
            ?: 0L,
        downloadedBytes = matchingTask?.progressBytes?.coerceAtLeast(0L) ?: 0L,
        progress = matchingTask?.downloadProgress(),
        downloadSpeedBps = matchingTask?.downloadSpeedBps,
        etaSeconds = matchingTask?.etaSeconds,
        alreadyInstalled = installed,
        hasDownloadTask = matchingTask != null,
    )
}

private fun resolveRuntimeSetupState(
    base: OnboardingSetupUiState,
    target: OnboardingSetupTarget,
    modelLoadingState: ModelLoadingState,
    sendReady: Boolean,
): OnboardingSetupUiState? {
    if (modelLoadingState.loadedModel.matches(target.modelId, target.version)) {
        return base.copy(
            phase = if (sendReady) OnboardingSetupPhase.READY else OnboardingSetupPhase.STARTING,
            progress = if (sendReady) 1f else null,
        )
    }
    val loading = modelLoadingState as? ModelLoadingState.Loading
    if (loading != null && loading.requestedModel.matches(target.modelId, target.version)) {
        return base.copy(
            phase = OnboardingSetupPhase.STARTING,
            progress = loading.progress,
            detail = loading.stage,
        )
    }
    val error = modelLoadingState as? ModelLoadingState.Error
    if (error != null && error.requestedModel.matches(target.modelId, target.version)) {
        return base.copy(
            phase = OnboardingSetupPhase.NEEDS_ATTENTION,
            detail = error.message,
        )
    }
    return null
}

private fun DownloadTaskState.toOnboardingSetupState(
    base: OnboardingSetupUiState,
): OnboardingSetupUiState {
    val phase = onboardingSetupPhase()
    return base.copy(
        phase = phase,
        detail = message,
        progress = if (phase == OnboardingSetupPhase.DOWNLOADING) base.progress else null,
    )
}

private fun DownloadTaskState.onboardingSetupPhase(): OnboardingSetupPhase {
    return when {
        status == DownloadTaskStatus.FAILED || status == DownloadTaskStatus.CANCELLED ->
            OnboardingSetupPhase.NEEDS_ATTENTION
        status == DownloadTaskStatus.PAUSED -> OnboardingSetupPhase.PAUSED
        status == DownloadTaskStatus.COMPLETED || status == DownloadTaskStatus.INSTALLED_INACTIVE ->
            OnboardingSetupPhase.STARTING
        processingStage == DownloadProcessingStage.INSTALLING -> OnboardingSetupPhase.FINISHING
        status == DownloadTaskStatus.VERIFYING || processingStage == DownloadProcessingStage.VERIFYING ->
            OnboardingSetupPhase.CHECKING
        status == DownloadTaskStatus.DOWNLOADING -> OnboardingSetupPhase.DOWNLOADING
        else -> OnboardingSetupPhase.PREPARING
    }
}

private fun DownloadTaskState.downloadProgress(): Float? {
    if (totalBytes <= 0L || processingStage != DownloadProcessingStage.DOWNLOADING) {
        return null
    }
    return (progressBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
}

private fun com.pocketagent.runtime.RuntimeLoadedModel?.matches(
    modelId: String?,
    version: String?,
): Boolean {
    if (this == null || modelId.isNullOrBlank() || this.modelId != modelId) {
        return false
    }
    return version.isNullOrBlank() || modelVersion == version
}
