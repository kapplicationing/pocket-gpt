package com.pocketagent.android.voice

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import kotlin.math.abs

internal data class VoiceProcessExit(
    val reason: Int,
    val timestampEpochMs: Long,
    val description: String? = null,
)

internal fun shouldRespectUserRequestedStop(
    settings: VoiceActivationSettings,
    exit: VoiceProcessExit?,
    packageLastUpdateEpochMs: Long = 0L,
): Boolean {
    if (!settings.enabled || exit == null) return false
    return exit.reason == ApplicationExitInfo.REASON_USER_REQUESTED &&
        exit.timestampEpochMs > settings.enabledAtEpochMs &&
        !exit.matchesPackageReplacement(packageLastUpdateEpochMs)
}

private fun VoiceProcessExit.matchesPackageReplacement(packageLastUpdateEpochMs: Long): Boolean {
    if (reason == ApplicationExitInfo.REASON_PACKAGE_UPDATED) return true
    if (description?.contains("installPackage", ignoreCase = true) == true) return true
    return packageLastUpdateEpochMs > 0L &&
        abs(timestampEpochMs - packageLastUpdateEpochMs) <= PACKAGE_REPLACEMENT_EXIT_TOLERANCE_MS
}

internal object VoiceProcessExitReconciler {
    fun disableAfterUserStopIfNeeded(
        context: Context,
        settingsStore: VoiceActivationSettingsStore,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return false
        val packageLastUpdateEpochMs = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        val settings = settingsStore.state()
        val hasUnresolvedUserStop = activityManager
            .getHistoricalProcessExitReasons(context.packageName, 0, EXIT_HISTORY_LIMIT)
            .asSequence()
            .filter { info -> info.processName == context.packageName }
            .filter { info -> info.reason == ApplicationExitInfo.REASON_USER_REQUESTED }
            .map { info ->
                VoiceProcessExit(
                    reason = info.reason,
                    timestampEpochMs = info.timestamp,
                    description = info.description,
                )
            }
            .any { exit ->
                shouldRespectUserRequestedStop(
                    settings = settings,
                    exit = exit,
                    packageLastUpdateEpochMs = packageLastUpdateEpochMs,
                )
            }
        if (!hasUnresolvedUserStop) {
            return false
        }
        settingsStore.disableWithError(
            "Hands-free stayed off because you stopped PocketAgent from Android's Active apps screen.",
        )
        return true
    }
}

private const val PACKAGE_REPLACEMENT_EXIT_TOLERANCE_MS = 2_000L
private const val EXIT_HISTORY_LIMIT = 20
