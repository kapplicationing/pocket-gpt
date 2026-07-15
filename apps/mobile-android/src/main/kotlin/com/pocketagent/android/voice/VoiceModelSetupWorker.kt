package com.pocketagent.android.voice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pocketagent.android.MainActivity
import com.pocketagent.android.R
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Durable owner for the verified one-time voice pack installation. */
class VoiceModelSetupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    private val appContext = context.applicationContext

    override suspend fun doWork(): Result = coroutineScope {
        val installer = VoiceModelInstaller.process(appContext)
        setForeground(createForegroundInfo(installer.observe().value))
        val progressJob = launch {
            installer.observe().collectLatest { state ->
                setProgress(
                    workDataOf(
                        KEY_PHASE to state.phase.name,
                        KEY_PROGRESS_PERCENT to state.progressPercent,
                    ),
                )
                if (state.phase in ACTIVE_PHASES) {
                    setForeground(createForegroundInfo(state))
                }
            }
        }
        try {
            val installResult = installer.install()
            if (installResult.isFailure) {
                val error = installResult.exceptionOrNull()
                if (shouldRetryVoiceModelSetup(error, runAttemptCount)) {
                    installer.markQueued()
                    return@coroutineScope Result.retry()
                }
                VoiceModelSetupEnableRequest.clearIfCurrent(appContext, setupToken())
                return@coroutineScope Result.failure()
            }
            VoiceModelCatalog.invalidate(appContext)
            var enableResult: VoiceActivationEnableResult? = null
            val enableRequested = VoiceModelSetupEnableRequest.runIfCurrent(appContext, setupToken()) {
                val controller = VoiceActivationController(appContext)
                try {
                    enableResult = controller.setEnabled(true)
                } finally {
                    controller.close()
                }
            }
            if (enableRequested && enableResult != VoiceActivationEnableResult.ENABLED) {
                VoiceActivationSettingsStore.process(appContext).setLastError(
                    "Voice files are ready. Open PocketAgent to finish hands-free setup.",
                )
            }
            Result.success()
        } finally {
            progressJob.cancel()
        }
    }

    private fun setupToken(): Long {
        return inputData.getLong(KEY_SETUP_TOKEN, VoiceModelSetupEnableRequest.NO_TOKEN)
    }

    private fun createForegroundInfo(state: VoiceModelSetupState): ForegroundInfo {
        ensureNotificationChannel(appContext)
        val percent = state.progressPercent.coerceIn(0, 100)
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Setting up hands-free Offas")
            .setContentText(
                when (state.phase) {
                    VoiceModelSetupPhase.INSTALLING -> "Verifying local voice files…"
                    else -> "Downloading local voice files: $percent%"
                },
            )
            .setContentIntent(
                PendingIntent.getActivity(
                    appContext,
                    0,
                    Intent(appContext, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent, state.phase == VoiceModelSetupPhase.QUEUED)
            .addAction(
                android.R.drawable.ic_delete,
                appContext.getString(R.string.ui_cancel_button),
                cancelPendingIntent(appContext),
            )
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        internal const val UNIQUE_WORK_NAME = "voice-model-setup"
        private const val CHANNEL_ID = "voice_model_setup"
        private const val NOTIFICATION_ID = 4402
        private const val KEY_PHASE = "voice_setup_phase"
        private const val KEY_PROGRESS_PERCENT = "voice_setup_progress"
        internal const val KEY_SETUP_TOKEN = "voice_setup_token"
        private val ACTIVE_PHASES = setOf(
            VoiceModelSetupPhase.QUEUED,
            VoiceModelSetupPhase.DOWNLOADING,
            VoiceModelSetupPhase.INSTALLING,
        )

        fun enqueue(context: Context, setupToken: Long) {
            val appContext = context.applicationContext
            VoiceModelInstaller.process(appContext).markQueued()
            val request = OneTimeWorkRequestBuilder<VoiceModelSetupWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_SETUP_TOKEN to setupToken))
                .build()
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun cancel(context: Context) {
            val appContext = context.applicationContext
            WorkManager.getInstance(appContext).cancelUniqueWork(UNIQUE_WORK_NAME)
            VoiceModelSetupEnableRequest.clear(appContext)
            VoiceModelInstaller.process(appContext).markCancelled()
        }

        private fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Hands-free voice setup",
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            }
        }

        private fun cancelPendingIntent(context: Context): PendingIntent {
            return PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, VoiceModelSetupCancelReceiver::class.java)
                    .setAction(VoiceModelSetupCancelReceiver.ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}

class VoiceModelSetupCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_CANCEL) {
            VoiceModelSetupWorker.cancel(context.applicationContext)
        }
    }

    companion object {
        const val ACTION_CANCEL = "com.pocketagent.android.voice.CANCEL_MODEL_SETUP"
    }
}

internal object VoiceModelSetupEnableRequest {
    private const val PREFS_NAME = "pocketagent_voice_model_setup"
    private const val KEY_GENERATION = "enable_request_generation"
    private const val KEY_REQUESTED_TOKEN = "enable_requested_token"
    const val NO_TOKEN = 0L
    private val gates = ConcurrentHashMap<String, VoiceModelSetupEnableTokenGate>()

    fun request(context: Context): Long = gate(context).request()

    fun clear(context: Context) {
        gate(context).clear()
    }

    fun clearIfCurrent(context: Context, token: Long): Boolean {
        return gate(context).clearIfCurrent(token)
    }

    fun hasPendingRequest(context: Context): Boolean = gate(context).hasPendingRequest()

    fun runIfCurrent(context: Context, token: Long, action: () -> Unit): Boolean {
        return gate(context).runIfCurrent(token, action)
    }

    private fun gate(context: Context): VoiceModelSetupEnableTokenGate {
        val appContext = context.applicationContext
        return gates.getOrPut(appContext.packageName) {
            val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            VoiceModelSetupEnableTokenGate(
                generation = { preferences.getLong(KEY_GENERATION, NO_TOKEN) },
                requestedToken = { preferences.getLong(KEY_REQUESTED_TOKEN, NO_TOKEN) },
                saveRequest = { generation, token ->
                    preferences.edit()
                        .putLong(KEY_GENERATION, generation)
                        .putLong(KEY_REQUESTED_TOKEN, token)
                        .commit()
                },
                clearRequest = {
                    preferences.edit().remove(KEY_REQUESTED_TOKEN).commit()
                },
            )
        }
    }
}

internal class VoiceModelSetupEnableTokenGate(
    private val generation: () -> Long,
    private val requestedToken: () -> Long,
    private val saveRequest: (generation: Long, token: Long) -> Unit,
    private val clearRequest: () -> Unit,
) {
    private val lock = Any()

    fun request(): Long = synchronized(lock) {
        val next = generation().let { current ->
            if (current == Long.MAX_VALUE) 1L else (current + 1L).coerceAtLeast(1L)
        }
        saveRequest(next, next)
        next
    }

    fun clear() = synchronized(lock) {
        clearRequest()
    }

    fun clearIfCurrent(token: Long): Boolean = synchronized(lock) {
        if (token == VoiceModelSetupEnableRequest.NO_TOKEN || requestedToken() != token) {
            false
        } else {
            clearRequest()
            true
        }
    }

    fun hasPendingRequest(): Boolean = synchronized(lock) {
        requestedToken() != VoiceModelSetupEnableRequest.NO_TOKEN
    }

    fun runIfCurrent(token: Long, action: () -> Unit): Boolean = synchronized(lock) {
        if (token == VoiceModelSetupEnableRequest.NO_TOKEN || requestedToken() != token) {
            false
        } else {
            try {
                action()
                true
            } finally {
                clearRequest()
            }
        }
    }
}

internal fun shouldRetryVoiceModelSetup(error: Throwable?, runAttemptCount: Int): Boolean {
    if (error == null || runAttemptCount >= MAX_VOICE_MODEL_SETUP_WORK_RETRIES) return false
    val causes = generateSequence(error) { cause -> cause.cause }.toList()
    return causes.any { cause ->
        cause is IOException || HTTP_RETRYABLE_ERROR.containsMatchIn(cause.message.orEmpty())
    }
}

private const val MAX_VOICE_MODEL_SETUP_WORK_RETRIES = 4
private val HTTP_RETRYABLE_ERROR = Regex("HTTP\\s+(?:408|425|429|5\\d\\d)\\b")
