package com.pocketagent.android.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class VoiceDictationPhase {
    IDLE,
    CHECKING,
    LISTENING,
    FINALIZING,
    ERROR,
}

internal enum class VoiceDictationIssue {
    MICROPHONE_PERMISSION,
    MODELS_MISSING,
    ALWAYS_ON_ACTIVE,
    EMPTY_TRANSCRIPT,
    CAPTURE_FAILED,
    CONTEXT_CHANGED,
}

@Immutable
internal data class VoiceDictationState(
    val phase: VoiceDictationPhase = VoiceDictationPhase.IDLE,
    val partialTranscript: String = "",
    val issue: VoiceDictationIssue? = null,
    val errorEventId: Long = 0L,
) {
    val isCaptureActive: Boolean
        get() = phase == VoiceDictationPhase.CHECKING ||
            phase == VoiceDictationPhase.LISTENING ||
            phase == VoiceDictationPhase.FINALIZING
}

/**
 * Owns a single, foreground dictation capture. It never sends a message: callers receive
 * recognized text and decide how to merge it into the editable composer draft.
 */
internal class VoiceDictationController internal constructor(
    private val hasRecordAudioPermission: () -> Boolean,
    private val voiceModelsReady: suspend () -> Boolean,
    private val engineFactory: () -> OffasVoiceEngine,
    private val scope: CoroutineScope,
) {
    constructor(context: Context) : this(
        hasRecordAudioPermission = {
            ContextCompat.checkSelfPermission(
                context.applicationContext,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        },
        voiceModelsReady = {
            withContext(Dispatchers.IO) {
                VoiceModelCatalog.status(context.applicationContext).ready
            }
        },
        engineFactory = { SherpaOnnxOffasVoiceEngine(context.applicationContext) },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    private val mutableState = kotlinx.coroutines.flow.MutableStateFlow(VoiceDictationState())
    private var captureJob: Job? = null
    private var activeEngine: OffasVoiceEngine? = null
    private var generation = 0L
    private var latestTranscript = ""
    private var onTranscriptReady: ((String) -> Unit)? = null
    private var errorEventSequence = 0L

    fun observe(): kotlinx.coroutines.flow.StateFlow<VoiceDictationState> = mutableState

    fun toggle(
        alwaysOnListeningEnabled: Boolean,
        onTranscriptReady: (String) -> Unit,
    ) {
        when (mutableState.value.phase) {
            VoiceDictationPhase.CHECKING,
            VoiceDictationPhase.LISTENING,
            -> finish()

            VoiceDictationPhase.FINALIZING -> Unit

            VoiceDictationPhase.IDLE,
            VoiceDictationPhase.ERROR,
            -> start(alwaysOnListeningEnabled, onTranscriptReady)
        }
    }

    fun release() {
        cancel()
        scope.cancel()
    }

    fun cancel() {
        generation += 1
        captureJob?.cancel()
        captureJob = null
        onTranscriptReady = null
        latestTranscript = ""
        mutableState.value = VoiceDictationState()
    }

    fun reportContextChanged() {
        publishError(VoiceDictationIssue.CONTEXT_CHANGED)
    }

    fun finish() {
        when (mutableState.value.phase) {
            VoiceDictationPhase.CHECKING -> {
                generation += 1
                captureJob?.cancel()
                captureJob = null
                onTranscriptReady = null
                mutableState.value = VoiceDictationState()
            }
            VoiceDictationPhase.LISTENING -> {
                val engine = activeEngine
                if (engine == null) {
                    publishError(VoiceDictationIssue.CAPTURE_FAILED)
                    return
                }
                mutableState.value = VoiceDictationState(
                    phase = VoiceDictationPhase.FINALIZING,
                    partialTranscript = latestTranscript,
                )
                engine.requestFinish()
            }
            VoiceDictationPhase.IDLE,
            VoiceDictationPhase.FINALIZING,
            VoiceDictationPhase.ERROR,
            -> Unit
        }
    }

    private fun start(
        alwaysOnListeningEnabled: Boolean,
        transcriptReady: (String) -> Unit,
    ) {
        if (captureJob?.isActive == true) return
        val blockingIssue = startBlockingIssue(alwaysOnListeningEnabled)
        if (blockingIssue != null) {
            publishError(blockingIssue)
            return
        }

        val runGeneration = ++generation
        latestTranscript = ""
        onTranscriptReady = transcriptReady
        mutableState.value = VoiceDictationState(phase = VoiceDictationPhase.CHECKING)
        captureJob = scope.launch {
            runCapture(runGeneration)
        }
    }

    private fun startBlockingIssue(alwaysOnListeningEnabled: Boolean): VoiceDictationIssue? = when {
        alwaysOnListeningEnabled -> VoiceDictationIssue.ALWAYS_ON_ACTIVE
        !hasRecordAudioPermission() -> VoiceDictationIssue.MICROPHONE_PERMISSION
        else -> null
    }

    private suspend fun runCapture(runGeneration: Long) {
        var engine: OffasVoiceEngine? = null
        try {
            if (!voiceModelsReady()) {
                publishError(VoiceDictationIssue.MODELS_MISSING)
                return
            }
            if (runGeneration != generation) return

            engine = engineFactory()
            engine.prepareCapture()
            activeEngine = engine
            mutableState.value = VoiceDictationState(phase = VoiceDictationPhase.LISTENING)
            val finalTranscript = engine.awaitWakeAndCommand(
                wakePhrase = "",
                silenceTimeoutSeconds = DICTATION_SILENCE_TIMEOUT_SECONDS,
                directCapture = true,
                onWakeWord = {},
                onStateChanged = {},
                onPartialTranscript = { partial -> publishPartial(runGeneration, partial) },
            )
            if (runGeneration != generation) return
            completeWithTranscript(finalTranscript.orEmpty().ifBlank { latestTranscript })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: RuntimeException) {
            if (runGeneration == generation) {
                publishError(VoiceDictationIssue.CAPTURE_FAILED)
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Default) {
                engine?.release()
            }
            if (activeEngine === engine) {
                activeEngine = null
            }
            if (runGeneration == generation) {
                captureJob = null
            }
        }
    }

    private fun publishPartial(runGeneration: Long, partial: String) {
        val normalized = partial.trim()
        scope.launch {
            val currentPhase = mutableState.value.phase
            val acceptsPartial = currentPhase == VoiceDictationPhase.LISTENING ||
                currentPhase == VoiceDictationPhase.FINALIZING
            if (runGeneration == generation && acceptsPartial && normalized != latestTranscript) {
                latestTranscript = normalized
                mutableState.value = VoiceDictationState(
                    phase = currentPhase,
                    partialTranscript = normalized,
                )
            }
        }
    }

    private fun completeWithTranscript(transcript: String) {
        val normalized = transcript.trim()
        val callback = onTranscriptReady
        onTranscriptReady = null
        latestTranscript = ""
        if (normalized.isBlank()) {
            publishError(VoiceDictationIssue.EMPTY_TRANSCRIPT)
            return
        }
        mutableState.value = VoiceDictationState()
        callback?.invoke(normalized)
    }

    private fun publishError(issue: VoiceDictationIssue) {
        errorEventSequence += 1
        mutableState.value = VoiceDictationState(
            phase = VoiceDictationPhase.ERROR,
            issue = issue,
            errorEventId = errorEventSequence,
        )
    }
}

internal fun appendDictationToDraft(draft: String, transcript: String): String {
    val normalizedTranscript = transcript.trim()
    if (normalizedTranscript.isEmpty()) return draft
    if (draft.isBlank()) return normalizedTranscript
    return draft.trimEnd() + " " + normalizedTranscript
}

private const val DICTATION_SILENCE_TIMEOUT_SECONDS = 3
