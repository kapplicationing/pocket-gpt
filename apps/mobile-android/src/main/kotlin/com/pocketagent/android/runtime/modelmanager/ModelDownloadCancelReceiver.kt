package com.pocketagent.android.runtime.modelmanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pocketagent.android.runtime.AppRuntimeProvisioningBridge

class ModelDownloadCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val taskId = resolveDownloadCancellationTaskId(
            action = intent?.action,
            rawTaskId = intent?.getStringExtra(EXTRA_TASK_ID),
        ) ?: return
        AppRuntimeProvisioningBridge.cancelDownload(context.applicationContext, taskId)
    }

    companion object {
        internal const val ACTION_CANCEL_DOWNLOAD = "com.pocketagent.android.action.CANCEL_MODEL_DOWNLOAD"
        internal const val EXTRA_TASK_ID = "task_id"
    }
}

internal fun resolveDownloadCancellationTaskId(
    action: String?,
    rawTaskId: String?,
): String? {
    if (action != ModelDownloadCancelReceiver.ACTION_CANCEL_DOWNLOAD) {
        return null
    }
    val taskId = rawTaskId.orEmpty().trim()
    return taskId.takeIf { it.isNotEmpty() }
}
