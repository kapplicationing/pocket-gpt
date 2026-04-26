package com.pocketagent.android.ui

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult
import kotlinx.coroutines.delay

@Composable
internal fun DownloadTransitionHandler(
    downloads: List<DownloadTaskState>,
    pendingGetReadyActivation: Pair<String, String>?,
    loadedModelId: String?,
    lastDownloadTransitionRefreshKey: String?,
    readinessRefreshSequence: Long,
    onRefreshSnapshot: () -> Unit,
    onSetStatusMessage: (String) -> Unit,
    onActivateVersion: suspend (String, String) -> Boolean,
    onLoadModel: suspend (String, String) -> RuntimeModelLifecycleCommandResult?,
    onShowBusyModelOperationFeedback: suspend () -> Unit,
    onClearPendingGetReadyActivation: () -> Unit,
    onIncrementReadinessRefreshSequence: () -> Long,
    onRefreshRuntimeReadiness: (String?) -> Unit,
    onSetLastDownloadTransitionRefreshKey: (String) -> Unit,
    onOpenModelSheet: () -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val previousDownloadStatuses = remember { mutableStateMapOf<String, DownloadTaskStatus>() }
    val currentPendingGetReadyActivation by rememberUpdatedState(pendingGetReadyActivation)
    val currentLoadedModelId by rememberUpdatedState(loadedModelId)
    val currentLastDownloadTransitionRefreshKey by rememberUpdatedState(lastDownloadTransitionRefreshKey)
    val currentReadinessRefreshSequence by rememberUpdatedState(readinessRefreshSequence)

    LaunchedEffect(downloads) {
        onRefreshSnapshot()
        val transitioned = downloads.firstOrNull { task ->
            val previous = previousDownloadStatuses[task.taskId]
            previous != null && previous != task.status
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
            val pendingActivation = currentPendingGetReadyActivation
            if (
                pendingActivation != null &&
                transitioned.modelId == pendingActivation.first &&
                transitioned.version == pendingActivation.second
            ) {
                val activated = onActivateVersion(
                    transitioned.modelId,
                    transitioned.version,
                )
                if (activated) {
                    val activationMessage = context.getString(
                        com.pocketagent.android.R.string.ui_model_version_activated,
                        transitioned.modelId,
                        transitioned.version,
                    )
                    onSetStatusMessage(activationMessage)
                    refreshDetail = activationMessage
                    refreshKey += ":activated"
                    val alreadyLoadedDifferentModel =
                        currentLoadedModelId != null && currentLoadedModelId != transitioned.modelId
                    var shouldClearPendingActivation = true
                    if (!alreadyLoadedDifferentModel) {
                        var loadResult = onLoadModel(transitioned.modelId, transitioned.version)
                        if (loadResult == null) {
                            delay(GET_READY_LOAD_RETRY_DELAY_MS)
                            loadResult = onLoadModel(transitioned.modelId, transitioned.version)
                        }
                        loadResult?.let { result ->
                            onSetStatusMessage(
                                lifecycleStatusMessage(
                                    context = context,
                                    result = result,
                                    fallbackModelId = transitioned.modelId,
                                    fallbackVersion = transitioned.version,
                                ),
                            )
                        } ?: run {
                            shouldClearPendingActivation = false
                            onShowBusyModelOperationFeedback()
                        }
                    } else {
                        onSetStatusMessage(
                            context.getString(R.string.ui_download_complete_different_model_loaded, transitioned.modelId)
                        )
                    }
                    logProvisioningTransitionForDownloadHandler(
                        phase = "download_activation",
                        eventId = transitioned.taskId,
                        detail = "${transitioned.modelId}@${transitioned.version}",
                    )
                    if (shouldClearPendingActivation) {
                        onClearPendingGetReadyActivation()
                    }
                } else {
                    refreshKey += ":activation_skipped"
                    onClearPendingGetReadyActivation()
                }
            }
            val currentRefreshKey = currentLastDownloadTransitionRefreshKey
            if (currentRefreshKey != refreshKey) {
                val nextSequence = onIncrementReadinessRefreshSequence()
                logProvisioningTransitionForDownloadHandler(
                    phase = "readiness_refresh",
                    eventId = "refresh-$nextSequence",
                    detail = "source=download_transition;task=${transitioned.taskId};status=${transitioned.status.name}",
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
        if (transitioned?.status == DownloadTaskStatus.FAILED && currentPendingGetReadyActivation != null) {
            onClearPendingGetReadyActivation()
            onOpenModelSheet()
        }
        previousDownloadStatuses.clear()
        downloads.forEach { task ->
            previousDownloadStatuses[task.taskId] = task.status
        }
    }
}

private const val GET_READY_LOAD_RETRY_DELAY_MS = 750L

private fun HapticFeedback.tickLight() = performHapticFeedback(HapticFeedbackType.TextHandleMove)

private fun HapticFeedback.tickConfirm() = performHapticFeedback(HapticFeedbackType.LongPress)

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

        DownloadTaskStatus.FAILED -> when (failureReason) {
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

        else -> null
    }
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
