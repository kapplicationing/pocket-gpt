package com.pocketagent.android.runtime.modelmanager

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    private val appContext = context.applicationContext
    private val executor = ModelDownloadExecutor(appContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val taskId = inputData.getString(KEY_TASK_ID).orEmpty().trim()
        if (taskId.isEmpty()) {
            return@withContext Result.failure(outputDataOf(""))
        }
        when (executor.execute(
            taskId = taskId,
            host = object : DownloadExecutionHost {
                override suspend fun updateNotification(taskId: String, modelId: String, percent: Int) {
                    setForeground(createForegroundInfo(taskId = taskId, modelId = modelId, percent = percent))
                }

                override fun isStopped(): Boolean = this@ModelDownloadWorker.isStopped

                override fun stopDisposition(): DownloadStopDisposition = modelDownloadWorkerStopDisposition()
            },
            retryAllowed = runAttemptCount < MAX_RETRY_ATTEMPTS,
        )) {
            DownloadExecutionOutcome.SUCCESS -> Result.success(outputDataOf(taskId))
            DownloadExecutionOutcome.RETRY -> Result.retry()
            DownloadExecutionOutcome.FAILURE -> Result.failure(outputDataOf(taskId))
        }
    }

    private fun createForegroundInfo(taskId: String, modelId: String, percent: Int): ForegroundInfo {
        val notification = DownloadNotificationFactory.build(
            context = appContext,
            taskId = taskId,
            modelId = modelId,
            percent = percent,
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                taskId.hashCode(),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(taskId.hashCode(), notification)
        }
    }

    private fun outputDataOf(taskId: String): androidx.work.Data {
        return androidx.work.Data.Builder()
            .putString(KEY_TASK_ID, taskId)
            .build()
    }

    companion object {
        internal const val WORK_TAG = "model-download"
        internal const val DOWNLOAD_DIR = "runtime-model-downloads"
        internal const val KEY_TASK_ID = "task_id"
        private const val MAX_RETRY_ATTEMPTS = 3
    }
}

internal fun modelDownloadWorkerStopDisposition(): DownloadStopDisposition =
    DownloadStopDisposition.RESCHEDULE
