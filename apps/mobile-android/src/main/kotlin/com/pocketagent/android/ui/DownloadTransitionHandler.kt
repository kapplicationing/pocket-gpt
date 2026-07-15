package com.pocketagent.android.ui

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import com.pocketagent.android.R
import com.pocketagent.android.runtime.modelmanager.DownloadArtifactTaskStatus
import com.pocketagent.android.runtime.modelmanager.DownloadFailureReason
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.DownloadTaskStatus
import com.pocketagent.runtime.RuntimeLoadedModel
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

@Composable
@Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList", "UnusedParameter")
internal fun DownloadTransitionHandler(
    downloadsFlow: StateFlow<List<DownloadTaskState>>,
    pendingGetReadyActivation: Pair<String, String>?,
    pendingGetReadyTargetIsInstalled: Boolean,
    pendingMeteredWarningTarget: Pair<String, String>?,
    setupRequestInFlight: Boolean,
    setupFailureMessage: String?,
    loadedModel: RuntimeLoadedModel?,
    activeModelLoadRequest: RuntimeLoadedModel?,
    sendReady: Boolean,
    lastDownloadTransitionRefreshKey: String?,
    readinessRefreshSequence: Long,
    onRefreshSnapshot: () -> Unit,
    onSetStatusMessage: (String) -> Unit,
    onActivateVersion: suspend (String, String) -> Boolean,
    onLoadModel: suspend (String, String) -> RuntimeModelLifecycleCommandResult?,
    onShowBusyModelOperationFeedback: suspend () -> Unit,
    readPendingGetReadyActivation: () -> Pair<String, String>?,
    readLoadedModel: () -> RuntimeLoadedModel?,
    readActiveModelLoadRequest: () -> RuntimeLoadedModel?,
    readPendingMeteredWarningTarget: () -> Pair<String, String>?,
    onTryBeginGetReadySetupRequest: () -> Boolean,
    onFinishGetReadySetupRequest: () -> Unit,
    onClearPendingGetReadyActivation: () -> Unit,
    onSetGetReadySetupFailure: (String?) -> Unit,
    onIncrementReadinessRefreshSequence: () -> Long,
    onRefreshRuntimeReadiness: (String?) -> Unit,
    onSetLastDownloadTransitionRefreshKey: (String) -> Unit,
    onOpenModelSheet: () -> Unit,
    keepPendingGetReadyFailure: Boolean = false,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val previousDownloadStatuses = remember { mutableStateMapOf<String, DownloadTaskStatus>() }
    val downloads by downloadsFlow.collectAsState()
    val currentDownloads by rememberUpdatedState(downloads)
    val latestPendingGetReadyActivation by rememberUpdatedState(readPendingGetReadyActivation)
    val latestLoadedModel by rememberUpdatedState(readLoadedModel)
    val latestActiveModelLoadRequest by rememberUpdatedState(readActiveModelLoadRequest)
    val latestPendingMeteredWarningTarget by rememberUpdatedState(readPendingMeteredWarningTarget)
    val matchingMeteredWarningPending = pendingMeteredWarningTarget == pendingGetReadyActivation
    val currentSetupRequestInFlight by rememberUpdatedState(setupRequestInFlight)
    val currentSetupFailureMessage by rememberUpdatedState(setupFailureMessage)
    val currentLastDownloadTransitionRefreshKey by rememberUpdatedState(lastDownloadTransitionRefreshKey)
    val currentReadinessRefreshSequence by rememberUpdatedState(readinessRefreshSequence)

    fun retainsAutomaticGetReadyOwnership(expectedActivation: Pair<String, String>): Boolean {
        return retainsPendingGetReadyOwnership(
            expectedActivation = expectedActivation,
            currentPendingActivation = latestPendingGetReadyActivation(),
            loadedModel = latestLoadedModel(),
            activeModelLoadRequest = latestActiveModelLoadRequest(),
        )
    }

    fun canIssueAutomaticGetReadyLoad(expectedActivation: Pair<String, String>): Boolean {
        return shouldIssuePendingGetReadyLoad(
            expectedActivation = expectedActivation,
            currentPendingActivation = latestPendingGetReadyActivation(),
            loadedModel = latestLoadedModel(),
            activeModelLoadRequest = latestActiveModelLoadRequest(),
        )
    }

    LaunchedEffect(pendingGetReadyActivation, loadedModel, sendReady) {
        if (shouldCompleteGetReadyActivation(pendingGetReadyActivation, loadedModel, sendReady)) {
            onSetGetReadySetupFailure(null)
            onClearPendingGetReadyActivation()
        }
    }

    LaunchedEffect(
        pendingGetReadyActivation,
        pendingGetReadyTargetIsInstalled,
        matchingMeteredWarningPending,
    ) {
        val pendingActivation = latestPendingGetReadyActivation()
        if (
            !shouldReconcileInstalledGetReadyActivation(
                InstalledGetReadyRecoveryState(
                    pendingActivation = pendingActivation,
                    targetIsInstalled = pendingGetReadyTargetIsInstalled,
                    setupRequestInFlight = currentSetupRequestInFlight,
                    setupFailureMessage = currentSetupFailureMessage,
                    matchingMeteredWarningPending =
                        latestPendingMeteredWarningTarget() == pendingActivation,
                    downloads = currentDownloads,
                ),
            )
        ) {
            return@LaunchedEffect
        }
        if (!onTryBeginGetReadySetupRequest()) {
            return@LaunchedEffect
        }
        try {
            when {
                shouldWaitForPendingGetReadyLoad(
                    pendingActivation = pendingActivation,
                    loadedModel = latestLoadedModel(),
                    activeModelLoadRequest = latestActiveModelLoadRequest(),
                ) -> Unit

                shouldSupersedePendingGetReadyActivation(
                    pendingActivation = pendingActivation,
                    loadedModel = latestLoadedModel(),
                    activeModelLoadRequest = latestActiveModelLoadRequest(),
                ) -> {
                    onSetGetReadySetupFailure(null)
                    onClearPendingGetReadyActivation()
                }

                else -> {
                    val (modelId, version) = checkNotNull(pendingActivation)
                    val expectedActivation = modelId to version
                    if (
                        !canIssueAutomaticGetReadyLoad(expectedActivation) ||
                            latestPendingMeteredWarningTarget() == expectedActivation
                    ) {
                        return@LaunchedEffect
                    }
                    val activated = onActivateVersion(modelId, version)
                    if (!retainsAutomaticGetReadyOwnership(expectedActivation)) {
                        return@LaunchedEffect
                    }
                    if (!activated) {
                        val activationFailure = context.getString(R.string.ui_model_version_activation_failed)
                        onSetStatusMessage(activationFailure)
                        onSetGetReadySetupFailure(activationFailure)
                        if (!keepPendingGetReadyFailure) {
                            onClearPendingGetReadyActivation()
                            onOpenModelSheet()
                        }
                        return@LaunchedEffect
                    }
                    val activationMessage = context.getString(
                        R.string.ui_model_version_activated,
                        modelId,
                        version,
                    )
                    onSetStatusMessage(activationMessage)
                    var loadResult = onLoadModel(modelId, version)
                    if (!retainsAutomaticGetReadyOwnership(expectedActivation)) {
                        return@LaunchedEffect
                    }
                    if (loadResult == null) {
                        delay(GET_READY_LOAD_RETRY_DELAY_MS)
                        if (
                            !canIssueAutomaticGetReadyLoad(expectedActivation) ||
                                latestPendingMeteredWarningTarget() == expectedActivation
                        ) {
                            return@LaunchedEffect
                        }
                        loadResult = onLoadModel(modelId, version)
                        if (!retainsAutomaticGetReadyOwnership(expectedActivation)) {
                            return@LaunchedEffect
                        }
                    }
                    val loadMessage = loadResult?.let { result ->
                        lifecycleStatusMessage(
                            context = context,
                            result = result,
                            fallbackModelId = modelId,
                            fallbackVersion = version,
                        )
                    }
                    loadMessage?.let(onSetStatusMessage)
                    when {
                        loadResult == null -> {
                            val busyMessage = context.getString(R.string.ui_model_operation_already_in_progress)
                            onSetStatusMessage(busyMessage)
                            onSetGetReadySetupFailure(busyMessage)
                            onShowBusyModelOperationFeedback()
                            if (!keepPendingGetReadyFailure) {
                                onClearPendingGetReadyActivation()
                                onOpenModelSheet()
                            }
                        }

                        !loadResult.success -> {
                            onSetGetReadySetupFailure(loadMessage)
                            if (!keepPendingGetReadyFailure) {
                                onClearPendingGetReadyActivation()
                                onOpenModelSheet()
                            }
                        }

                        !loadResult.queued -> onSetGetReadySetupFailure(null)
                    }
                    logProvisioningTransitionForDownloadHandler(
                        phase = "installed_target_recovery",
                        eventId = "get-ready:$modelId@$version",
                        detail = "reconciled_without_download_task",
                    )
                }
            }
        } finally {
            onFinishGetReadySetupRequest()
        }
    }

    LaunchedEffect(downloads) {
        val pendingActivationAtTransition = latestPendingGetReadyActivation()
        val transitioned = selectDownloadTransition(
            downloads = downloads,
            previousStatuses = previousDownloadStatuses,
            pendingGetReadyActivation = pendingActivationAtTransition,
        )
        if (transitioned?.shouldRefreshProvisioningSnapshotOnTransition() == true) {
            onRefreshSnapshot()
        }
        val transitionFeedback = transitioned?.provisioningFeedbackForDownloadTransition(context)
        transitionFeedback?.let(onSetStatusMessage)
        when (transitioned?.status) {
            DownloadTaskStatus.COMPLETED,
            DownloadTaskStatus.INSTALLED_INACTIVE,
            -> haptic.tickConfirm()
            DownloadTaskStatus.FAILED -> haptic.tickLight()
            else -> Unit
        }
        if (
            transitioned?.status == DownloadTaskStatus.COMPLETED ||
            transitioned?.status == DownloadTaskStatus.INSTALLED_INACTIVE
        ) {
            var refreshDetail = transitionFeedback
            var refreshKey = transitioned.taskId + ":" + transitioned.status.name
            val pendingActivation = latestPendingGetReadyActivation()
            if (transitioned.matches(pendingActivation)) {
                when {
                    shouldWaitForPendingGetReadyLoad(
                        pendingActivation = pendingActivation,
                        loadedModel = latestLoadedModel(),
                        activeModelLoadRequest = latestActiveModelLoadRequest(),
                    ) -> Unit

                    shouldSupersedePendingGetReadyActivation(
                        pendingActivation = pendingActivation,
                        loadedModel = latestLoadedModel(),
                        activeModelLoadRequest = latestActiveModelLoadRequest(),
                    ) -> {
                        onSetGetReadySetupFailure(null)
                        onClearPendingGetReadyActivation()
                    }

                    else -> {
                        if (onTryBeginGetReadySetupRequest()) {
                            try {
                                val expectedActivation = transitioned.modelId to transitioned.version
                                if (canIssueAutomaticGetReadyLoad(expectedActivation)) {
                                    val activated = transitioned.status == DownloadTaskStatus.COMPLETED ||
                                        onActivateVersion(transitioned.modelId, transitioned.version)
                                    when {
                                        !retainsAutomaticGetReadyOwnership(expectedActivation) -> Unit

                                        activated && canIssueAutomaticGetReadyLoad(expectedActivation) -> {
                                            val activationMessage = context.getString(
                                                com.pocketagent.android.R.string.ui_model_version_activated,
                                                transitioned.modelId,
                                                transitioned.version,
                                            )
                                            onSetStatusMessage(activationMessage)
                                            refreshDetail = activationMessage
                                            refreshKey += ":activated"
                                            var loadResult = onLoadModel(transitioned.modelId, transitioned.version)
                                            var shouldReportLoadResult =
                                                retainsAutomaticGetReadyOwnership(expectedActivation)
                                            if (loadResult == null && shouldReportLoadResult) {
                                                delay(GET_READY_LOAD_RETRY_DELAY_MS)
                                                if (canIssueAutomaticGetReadyLoad(expectedActivation)) {
                                                    loadResult = onLoadModel(
                                                        transitioned.modelId,
                                                        transitioned.version,
                                                    )
                                                    shouldReportLoadResult =
                                                        retainsAutomaticGetReadyOwnership(expectedActivation)
                                                } else {
                                                    shouldReportLoadResult = false
                                                }
                                            }
                                            if (shouldReportLoadResult) {
                                                val loadFailureMessage = loadResult?.let { result ->
                                                    lifecycleStatusMessage(
                                                        context = context,
                                                        result = result,
                                                        fallbackModelId = transitioned.modelId,
                                                        fallbackVersion = transitioned.version,
                                                    )
                                                }
                                                loadFailureMessage?.let(onSetStatusMessage)
                                                if (loadResult == null) {
                                                    val busyMessage = context.getString(
                                                        R.string.ui_model_operation_already_in_progress,
                                                    )
                                                    onSetStatusMessage(busyMessage)
                                                    onSetGetReadySetupFailure(busyMessage)
                                                    onShowBusyModelOperationFeedback()
                                                    if (!keepPendingGetReadyFailure) {
                                                        onClearPendingGetReadyActivation()
                                                        onOpenModelSheet()
                                                    }
                                                } else if (!loadResult.success) {
                                                    onSetGetReadySetupFailure(loadFailureMessage)
                                                    if (!keepPendingGetReadyFailure) {
                                                        onClearPendingGetReadyActivation()
                                                        onOpenModelSheet()
                                                    }
                                                } else if (!loadResult.queued) {
                                                    onSetGetReadySetupFailure(null)
                                                }
                                                logProvisioningTransitionForDownloadHandler(
                                                    phase = "download_activation",
                                                    eventId = transitioned.taskId,
                                                    detail = "${transitioned.modelId}@${transitioned.version}",
                                                )
                                            }
                                        }

                                        activated -> Unit

                                        else -> {
                                            refreshKey += ":activation_skipped"
                                            val activationFailure = context.getString(
                                                R.string.ui_model_version_activation_failed,
                                            )
                                            onSetStatusMessage(activationFailure)
                                            onSetGetReadySetupFailure(activationFailure)
                                            if (!keepPendingGetReadyFailure) {
                                                onClearPendingGetReadyActivation()
                                                onOpenModelSheet()
                                            }
                                        }
                                    }
                                }
                            } finally {
                                onFinishGetReadySetupRequest()
                            }
                        }
                    }
                }
            }
            val currentRefreshKey = currentLastDownloadTransitionRefreshKey
            if (currentRefreshKey != refreshKey) {
                val nextSequence = onIncrementReadinessRefreshSequence()
                logProvisioningTransitionForDownloadHandler(
                    phase = "readiness_refresh",
                    eventId = "refresh-$nextSequence",
                    detail = "source=download_transition;" +
                        "task=${transitioned.taskId};status=${transitioned.status.name}",
                )
                onRefreshRuntimeReadiness(refreshDetail)
                onSetLastDownloadTransitionRefreshKey(refreshKey)
            } else {
                logProvisioningTransitionForDownloadHandler(
                    phase = "readiness_refresh_coalesced",
                    eventId = "refresh-$currentReadinessRefreshSequence",
                    detail = "task=${transitioned.taskId};status=${transitioned.status.name}",
                )
            }
        }
        if (
            (
                transitioned?.status == DownloadTaskStatus.FAILED ||
                    transitioned?.status == DownloadTaskStatus.CANCELLED
            ) &&
            transitioned.matches(latestPendingGetReadyActivation())
        ) {
            if (!keepPendingGetReadyFailure) {
                onClearPendingGetReadyActivation()
                onOpenModelSheet()
            }
        }
        previousDownloadStatuses.clear()
        downloads.forEach { task ->
            previousDownloadStatuses[task.taskId] = task.status
        }
    }
}

internal fun selectDownloadTransition(
    downloads: List<DownloadTaskState>,
    previousStatuses: Map<String, DownloadTaskStatus>,
    pendingGetReadyActivation: Pair<String, String>?,
): DownloadTaskState? {
    val newestPendingTask = pendingGetReadyActivation?.let { activation ->
        downloads
            .asSequence()
            .filter { task -> task.matches(activation) }
            .maxByOrNull { task -> task.updatedAtEpochMs }
    }
    if (
        newestPendingTask?.terminal == true &&
        previousStatuses[newestPendingTask.taskId] != newestPendingTask.status
    ) {
        return newestPendingTask
    }
    return downloads.firstOrNull { task ->
        val previous = previousStatuses[task.taskId]
        previous != null && previous != task.status
    }
}

internal fun shouldCompleteGetReadyActivation(
    pendingActivation: Pair<String, String>?,
    loadedModel: RuntimeLoadedModel?,
    sendReady: Boolean,
): Boolean {
    return sendReady && loadedModel.matches(pendingActivation)
}

internal fun retainsPendingGetReadyOwnership(
    expectedActivation: Pair<String, String>,
    currentPendingActivation: Pair<String, String>?,
    loadedModel: RuntimeLoadedModel?,
    activeModelLoadRequest: RuntimeLoadedModel?,
): Boolean {
    return currentPendingActivation == expectedActivation &&
        !shouldSupersedePendingGetReadyActivation(
            pendingActivation = expectedActivation,
            loadedModel = loadedModel,
            activeModelLoadRequest = activeModelLoadRequest,
        )
}

internal fun shouldIssuePendingGetReadyLoad(
    expectedActivation: Pair<String, String>,
    currentPendingActivation: Pair<String, String>?,
    loadedModel: RuntimeLoadedModel?,
    activeModelLoadRequest: RuntimeLoadedModel?,
): Boolean {
    return retainsPendingGetReadyOwnership(
        expectedActivation = expectedActivation,
        currentPendingActivation = currentPendingActivation,
        loadedModel = loadedModel,
        activeModelLoadRequest = activeModelLoadRequest,
    ) && !shouldWaitForPendingGetReadyLoad(
        pendingActivation = expectedActivation,
        loadedModel = loadedModel,
        activeModelLoadRequest = activeModelLoadRequest,
    )
}

internal data class InstalledGetReadyRecoveryState(
    val pendingActivation: Pair<String, String>?,
    val targetIsInstalled: Boolean,
    val setupRequestInFlight: Boolean,
    val setupFailureMessage: String?,
    val matchingMeteredWarningPending: Boolean,
    val downloads: List<DownloadTaskState>,
)

internal fun shouldReconcileInstalledGetReadyActivation(
    state: InstalledGetReadyRecoveryState,
): Boolean {
    return state.pendingActivation != null &&
        state.targetIsInstalled &&
        !state.setupRequestInFlight &&
        state.setupFailureMessage.isNullOrBlank() &&
        !state.matchingMeteredWarningPending &&
        state.downloads.none { task -> task.matches(state.pendingActivation) }
}

internal fun shouldWaitForPendingGetReadyLoad(
    pendingActivation: Pair<String, String>?,
    loadedModel: RuntimeLoadedModel?,
    activeModelLoadRequest: RuntimeLoadedModel?,
): Boolean {
    return activeModelLoadRequest.matches(pendingActivation) || loadedModel.matches(pendingActivation)
}

internal fun shouldSupersedePendingGetReadyActivation(
    pendingActivation: Pair<String, String>?,
    loadedModel: RuntimeLoadedModel?,
    activeModelLoadRequest: RuntimeLoadedModel?,
): Boolean {
    return activeModelLoadRequest.supersedes(pendingActivation) ||
        loadedModel.supersedes(pendingActivation)
}

private const val GET_READY_LOAD_RETRY_DELAY_MS = 750L

private fun HapticFeedback.tickLight() = performHapticFeedback(HapticFeedbackType.TextHandleMove)

private fun HapticFeedback.tickConfirm() = performHapticFeedback(HapticFeedbackType.LongPress)

private fun DownloadTaskState?.matches(activation: Pair<String, String>?): Boolean {
    return activation != null &&
        this?.modelId == activation.first &&
        this?.version == activation.second
}

private fun RuntimeLoadedModel?.matches(activation: Pair<String, String>?): Boolean {
    return activation != null &&
        this?.modelId == activation.first &&
        this?.modelVersion == activation.second
}

private fun RuntimeLoadedModel?.supersedes(activation: Pair<String, String>?): Boolean {
    return this != null && activation != null &&
        (modelId != activation.first || modelVersion != activation.second)
}

private fun DownloadTaskState.provisioningFeedbackForDownloadTransition(context: Context): String? {
    return when (status) {
        DownloadTaskStatus.COMPLETED -> {
            val hasSkippedOptional = artifactStates.any { artifact ->
                !artifact.required && artifact.status == DownloadArtifactTaskStatus.FAILED
            }
            if (hasSkippedOptional) {
                context.getString(R.string.ui_model_download_completed_vision_degraded, modelId, version)
            } else {
                context.getString(R.string.ui_model_download_verified_active, modelId, version)
            }
        }

        DownloadTaskStatus.INSTALLED_INACTIVE -> context.getString(
            R.string.ui_model_download_verified_inactive,
            modelId,
            version,
        )

        DownloadTaskStatus.FAILED -> downloadFailureFeedback(context)

        else -> null
    }
}

private fun DownloadTaskState.downloadFailureFeedback(context: Context): String {
    return when (failureReason) {
        DownloadFailureReason.CHECKSUM_MISMATCH -> context.getString(
            R.string.ui_model_download_failed_checksum,
            modelId,
            version,
        )

        DownloadFailureReason.PROVENANCE_MISMATCH -> context.getString(
            R.string.ui_model_download_failed_provenance,
            modelId,
            version,
        )

        DownloadFailureReason.RUNTIME_INCOMPATIBLE -> context.getString(
            R.string.ui_model_download_failed_runtime_compat,
            modelId,
            version,
        )

        DownloadFailureReason.INSUFFICIENT_STORAGE -> context.getString(
            R.string.ui_model_download_failed_storage,
            modelId,
            version,
        )

        DownloadFailureReason.NETWORK_UNAVAILABLE,
        DownloadFailureReason.NETWORK_ERROR,
        -> context.getString(
            R.string.ui_model_download_failed_network,
            modelId,
            version,
        )

        DownloadFailureReason.TIMEOUT -> context.getString(
            R.string.ui_model_download_failed_timeout,
            modelId,
            version,
        )

        DownloadFailureReason.CANCELLED -> context.getString(
            R.string.ui_model_download_failed_cancelled,
            modelId,
            version,
        )

        DownloadFailureReason.UNKNOWN,
        null,
        -> context.getString(
            R.string.ui_model_download_failed_unknown,
            modelId,
            version,
        )
    }
}

private fun DownloadTaskState.shouldRefreshProvisioningSnapshotOnTransition(): Boolean {
    return status == DownloadTaskStatus.COMPLETED ||
        status == DownloadTaskStatus.INSTALLED_INACTIVE ||
        status == DownloadTaskStatus.FAILED ||
        status == DownloadTaskStatus.CANCELLED
}

private fun logProvisioningTransitionForDownloadHandler(
    phase: String,
    eventId: String,
    detail: String,
) {
    runCatching {
        Log.i("PocketAgentApp", "MODEL_TRANSITION|phase=$phase|event_id=$eventId|detail=$detail")
    }
}
