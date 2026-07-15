package com.pocketagent.android.voice

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.content.pm.PackageManager
import android.os.RemoteException
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Android-owned lifecycle anchor for the user-selected PocketAgent assistant. */
class PocketAgentVoiceInteractionService : VoiceInteractionService() {
    private var receiverRegistered = false
    private val showSessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_SHOW_WAKE_SESSION) return
            showSession(
                Bundle().apply {
                    putBoolean(EXTRA_ATTACH_TO_RUNNING_CAPTURE, true)
                    putLong(
                        VoiceSessionSignals.EXTRA_SESSION_ID,
                        intent.getLongExtra(
                            VoiceSessionSignals.EXTRA_SESSION_ID,
                            VoiceSessionSignals.NO_SESSION_ID,
                        ),
                    )
                },
                0,
            )
        }
    }

    override fun onReady() {
        super.onReady()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                showSessionReceiver,
                IntentFilter(ACTION_SHOW_WAKE_SESSION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
        val settingsStore = VoiceActivationSettingsStore.process(applicationContext)
        if (VoiceProcessExitReconciler.disableAfterUserStopIfNeeded(applicationContext, settingsStore)) {
            return
        }
        val settings = settingsStore.state()
        if (settings.enabled && VoiceModelCatalog.status(applicationContext).let {
                it.ready && it.dedicatedWakeWordReady
            }
        ) {
            OffasRuntime.start(applicationContext)
        }
    }

    override fun onShutdown() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(showSessionReceiver) }
            receiverRegistered = false
        }
        OffasRuntime.pause(applicationContext)
        super.onShutdown()
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        showSession(Bundle(), 0)
    }

    companion object {
        private const val ACTION_SHOW_WAKE_SESSION =
            "com.pocketagent.android.voice.SHOW_WAKE_SESSION"
        internal const val EXTRA_ATTACH_TO_RUNNING_CAPTURE =
            "com.pocketagent.android.voice.ATTACH_TO_RUNNING_CAPTURE"

        fun showWakeSession(context: Context, sessionId: Long) {
            context.sendBroadcast(
                Intent(ACTION_SHOW_WAKE_SESSION)
                    .setPackage(context.packageName)
                    .putExtra(VoiceSessionSignals.EXTRA_SESSION_ID, sessionId),
            )
        }
    }
}

class PocketAgentVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return PocketAgentVoiceInteractionSession(applicationContext)
    }
}

private class PocketAgentVoiceInteractionSession(
    context: Context,
) : VoiceInteractionSession(context) {
    override fun onCreateContentView(): View {
        return TextView(context).apply {
            text = "Opening PocketAgent voice…"
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val intent = Intent()
            .setClassName(context.packageName, INTERNAL_ASSIST_ACTIVITY_CLASS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(
                PocketAgentVoiceInteractionService.EXTRA_ATTACH_TO_RUNNING_CAPTURE,
                args?.getBoolean(PocketAgentVoiceInteractionService.EXTRA_ATTACH_TO_RUNNING_CAPTURE) == true,
            )
            .putExtra(
                VoiceSessionSignals.EXTRA_SESSION_ID,
                args?.getLong(
                    VoiceSessionSignals.EXTRA_SESSION_ID,
                    VoiceSessionSignals.NO_SESSION_ID,
                ) ?: VoiceSessionSignals.NO_SESSION_ID,
            )
        runCatching { context.startActivity(intent) }
            .onSuccess { hide() }
            .onFailure {
                setContentView(TextView(context).apply {
                    text = "PocketAgent voice could not open. Try again from the app."
                    textAlignment = View.TEXT_ALIGNMENT_CENTER
                })
            }
    }
}

/** Local recognizer used by Android's assistant plumbing and other SpeechRecognizer clients. */
class PocketAgentRecognitionService : RecognitionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recognitionJob: Job? = null
    private var engine: OffasVoiceEngine? = null
    private var destroying = false
    @Volatile
    private var pendingStopDisposition: VoiceCaptureStopDisposition? = null

    // Android's callback protocol requires ordered fail-fast exits around a single capture job.
    @Suppress("CyclomaticComplexMethod")
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        val callback = listener ?: return
        if (recognitionJob?.isActive == true) {
            notifyRecognitionClient(callback) { error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) }
            return
        }
        pendingStopDisposition = null
        val requestedLanguage = recognizerIntent
            ?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
            ?.replace('_', '-')
        if (requestedLanguage != null &&
            !requestedLanguage.substringBefore('-').equals("en", ignoreCase = true)
        ) {
            notifyRecognitionClient(callback) { error(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) }
            return
        }
        val reportPartials = recognizerIntent
            ?.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false) == true
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifyRecognitionClient(callback) { error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) }
            return
        }
        if (!VoiceModelCatalog.status(applicationContext).ready) {
            notifyRecognitionClient(callback) { error(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) }
            return
        }
        val settingsStore = VoiceActivationSettingsStore.process(applicationContext)
        val resumeHandsFree = settingsStore.state().enabled
        if (resumeHandsFree) {
            OffasRuntime.pause(applicationContext)
        }
        var captureEngine: OffasVoiceEngine? = null
        recognitionJob = scope.launch {
            try {
                var releasePolls = 0
                while (VoiceAudioCaptureLease.isHeld() && releasePolls < CAPTURE_RELEASE_POLLS) {
                    delay(CAPTURE_RELEASE_POLL_MS)
                    releasePolls += 1
                }
                if (VoiceAudioCaptureLease.isHeld()) {
                    notifyRecognitionClient(callback) { error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) }
                    return@launch
                }
                if (finishStoppedBeforeReady(callback)) return@launch
                val activeEngine = SherpaOnnxOffasVoiceEngine(applicationContext).also {
                    captureEngine = it
                    engine = it
                }
                if (finishStoppedBeforeReady(callback)) return@launch
                withContext(Dispatchers.Default) {
                    activeEngine.prepareCapture()
                }
                if (finishStoppedBeforeReady(callback)) return@launch
                pendingStopDisposition?.let(activeEngine::requestFinish)
                if (!notifyRecognitionClient(callback) { readyForSpeech(Bundle()) }) {
                    activeEngine.requestFinish(VoiceCaptureStopDisposition.DISCARD)
                    return@launch
                }
                val transcript = activeEngine.awaitWakeAndCommand(
                    wakePhrase = "",
                    silenceTimeoutSeconds = RECOGNITION_SILENCE_TIMEOUT_SECONDS,
                    directCapture = true,
                    onWakeWord = {
                        if (!notifyRecognitionClient(callback) { beginningOfSpeech() }) {
                            activeEngine.requestFinish(VoiceCaptureStopDisposition.DISCARD)
                        }
                    },
                    onStateChanged = {},
                    onPartialTranscript = { partial ->
                        if (reportPartials && partial.isNotBlank() &&
                            !notifyRecognitionClient(callback) {
                                partialResults(recognitionResults(partial))
                            }
                        ) {
                            activeEngine.requestFinish(VoiceCaptureStopDisposition.DISCARD)
                        }
                    },
                )
                if (transcript.isNullOrBlank()) {
                    notifyRecognitionClient(callback) { error(SpeechRecognizer.ERROR_NO_MATCH) }
                } else {
                    if (notifyRecognitionClient(callback) { endOfSpeech() }) {
                        notifyRecognitionClient(callback) { results(recognitionResults(transcript)) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                notifyRecognitionClient(callback) { error(SpeechRecognizer.ERROR_CLIENT) }
            } finally {
                captureEngine?.release()
                if (engine === captureEngine) {
                    engine = null
                }
                pendingStopDisposition = null
                recognitionJob = null
                if (resumeHandsFree && settingsStore.state().enabled && !destroying) {
                    OffasRuntime.start(applicationContext)
                }
            }
        }
    }

    override fun onCancel(listener: Callback?) {
        pendingStopDisposition = VoiceCaptureStopDisposition.DISCARD
        engine?.requestFinish(VoiceCaptureStopDisposition.DISCARD)
        recognitionJob?.cancel()
    }

    override fun onStopListening(listener: Callback?) {
        pendingStopDisposition = VoiceCaptureStopDisposition.SUBMIT
        engine?.requestFinish(VoiceCaptureStopDisposition.SUBMIT)
    }

    override fun onDestroy() {
        destroying = true
        pendingStopDisposition = VoiceCaptureStopDisposition.DISCARD
        engine?.requestFinish(VoiceCaptureStopDisposition.DISCARD)
        recognitionJob?.cancel()
        // The capture coroutine owns native teardown after AudioRecord and its streams unwind.
        // Releasing Sherpa here races that finally block and can double-free native state.
        scope.cancel()
        super.onDestroy()
    }

    private fun recognitionResults(transcript: String): Bundle {
        return Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(transcript))
            putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(-1f))
        }
    }

    private inline fun notifyRecognitionClient(
        callback: Callback,
        operation: Callback.() -> Unit,
    ): Boolean {
        return try {
            callback.operation()
            true
        } catch (_: RemoteException) {
            false
        }
    }

    private fun finishStoppedBeforeReady(callback: Callback): Boolean {
        return when (pendingStopDisposition) {
            VoiceCaptureStopDisposition.SUBMIT -> {
                notifyRecognitionClient(callback) { error(SpeechRecognizer.ERROR_NO_MATCH) }
                true
            }
            VoiceCaptureStopDisposition.DISCARD -> true
            null -> false
        }
    }

    private companion object {
        const val CAPTURE_RELEASE_POLLS = 20
        const val CAPTURE_RELEASE_POLL_MS = 100L
        const val RECOGNITION_SILENCE_TIMEOUT_SECONDS = 5
    }
}
