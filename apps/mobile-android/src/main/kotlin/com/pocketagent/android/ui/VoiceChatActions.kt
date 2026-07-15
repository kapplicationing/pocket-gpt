package com.pocketagent.android.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pocketagent.android.R
import com.pocketagent.android.voice.VoiceDictationController
import com.pocketagent.android.voice.VoiceDictationIssue
import com.pocketagent.android.voice.VoicePlaybackController
import com.pocketagent.android.voice.VoicePlaybackIssue
import java.util.concurrent.atomic.AtomicReference

@Stable
internal class VoiceChatActions internal constructor(
    val dictationController: VoiceDictationController,
    val playbackController: VoicePlaybackController,
    private val hasRecordAudioPermission: () -> Boolean,
    private val requestRecordAudioPermission: () -> Unit,
    private val alwaysOnListeningEnabled: () -> Boolean,
    private val activeSessionId: () -> String?,
    private val onTranscriptReady: (String) -> Unit,
) {
    fun toggleDictation() {
        if (dictationController.observe().value.isCaptureActive) {
            dictationController.finish()
            return
        }
        if (!hasRecordAudioPermission()) {
            requestRecordAudioPermission()
            return
        }
        playbackController.stop()
        startDictation()
    }

    fun onRecordAudioPermissionResult(granted: Boolean) {
        if (granted) {
            playbackController.stop()
        }
        startDictation()
    }

    fun togglePlayback(messageId: String, markdownText: String) {
        if (alwaysOnListeningEnabled()) {
            playbackController.reportAlwaysOnActive()
            return
        }
        if (dictationController.observe().value.isCaptureActive) {
            playbackController.reportDictationActive()
            return
        }
        playbackController.toggle(messageId, markdownText)
    }

    fun pause() {
        dictationController.cancel()
        playbackController.stop()
    }

    fun release() {
        dictationController.release()
        playbackController.release()
    }

    private fun startDictation() {
        val captureSessionId = activeSessionId()
        if (captureSessionId == null) {
            dictationController.reportContextChanged()
            return
        }
        dictationController.toggle(
            alwaysOnListeningEnabled = alwaysOnListeningEnabled(),
            onTranscriptReady = { transcript ->
                if (activeSessionId() == captureSessionId) {
                    onTranscriptReady(transcript)
                } else {
                    dictationController.reportContextChanged()
                }
            },
        )
    }
}

@Composable
internal fun rememberVoiceChatActions(
    context: Context,
    alwaysOnListeningEnabled: Boolean,
    activeSessionId: () -> String?,
    onTranscriptReady: (String) -> Unit,
): VoiceChatActions {
    val currentAlwaysOnListeningEnabled = rememberUpdatedState(alwaysOnListeningEnabled)
    val currentActiveSessionId = rememberUpdatedState(activeSessionId)
    val currentOnTranscriptReady = rememberUpdatedState(onTranscriptReady)
    val dictationController = remember(context) {
        VoiceDictationController(context.applicationContext)
    }
    val playbackController = remember(context) {
        VoicePlaybackController(context.applicationContext)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val actionsReference = remember { AtomicReference<VoiceChatActions?>(null) }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        actionsReference.get()?.onRecordAudioPermissionResult(granted)
    }
    val actions = remember(context, dictationController, playbackController, microphonePermissionLauncher) {
        VoiceChatActions(
            dictationController = dictationController,
            playbackController = playbackController,
            hasRecordAudioPermission = {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
            },
            requestRecordAudioPermission = {
                microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            alwaysOnListeningEnabled = { currentAlwaysOnListeningEnabled.value },
            activeSessionId = { currentActiveSessionId.value() },
            onTranscriptReady = { currentOnTranscriptReady.value(it) },
        )
    }
    SideEffect {
        actionsReference.set(actions)
    }
    DisposableEffect(actions) {
        onDispose {
            actionsReference.compareAndSet(actions, null)
            actions.release()
        }
    }
    DisposableEffect(actions, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                actions.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    return actions
}

@StringRes
internal fun dictationIssueMessage(issue: VoiceDictationIssue): Int = when (issue) {
    VoiceDictationIssue.MICROPHONE_PERMISSION -> R.string.ui_dictation_microphone_required
    VoiceDictationIssue.MODELS_MISSING -> R.string.ui_dictation_models_missing
    VoiceDictationIssue.ALWAYS_ON_ACTIVE -> R.string.ui_dictation_always_on_active
    VoiceDictationIssue.EMPTY_TRANSCRIPT -> R.string.ui_dictation_empty
    VoiceDictationIssue.CAPTURE_FAILED -> R.string.ui_dictation_failed
    VoiceDictationIssue.CONTEXT_CHANGED -> R.string.ui_dictation_context_changed
}

@StringRes
internal fun playbackIssueMessage(issue: VoicePlaybackIssue): Int = when (issue) {
    VoicePlaybackIssue.NOT_READY -> R.string.ui_read_aloud_not_ready
    VoicePlaybackIssue.LANGUAGE_UNAVAILABLE -> R.string.ui_read_aloud_language_unavailable
    VoicePlaybackIssue.PLAYBACK_FAILED -> R.string.ui_read_aloud_failed
    VoicePlaybackIssue.DICTATION_ACTIVE -> R.string.ui_read_aloud_dictation_active
    VoicePlaybackIssue.ALWAYS_ON_ACTIVE -> R.string.ui_read_aloud_always_on_active
}
