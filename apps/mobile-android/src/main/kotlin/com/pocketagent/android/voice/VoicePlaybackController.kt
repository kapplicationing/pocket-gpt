package com.pocketagent.android.voice

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.Immutable
import java.util.ArrayDeque
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

internal enum class VoicePlaybackPhase {
    INITIALIZING,
    IDLE,
    SPEAKING,
    ERROR,
}

internal enum class VoicePlaybackIssue {
    NOT_READY,
    LANGUAGE_UNAVAILABLE,
    PLAYBACK_FAILED,
    DICTATION_ACTIVE,
    ALWAYS_ON_ACTIVE,
}

@Immutable
internal data class VoicePlaybackState(
    val phase: VoicePlaybackPhase = VoicePlaybackPhase.IDLE,
    val messageId: String? = null,
    val issue: VoicePlaybackIssue? = null,
    val errorEventId: Long = 0L,
)

/** Owns Android TTS lifecycle and exposes message-level play/stop state to Compose. */
internal class VoicePlaybackController(
    context: Context,
) {
    private data class TtsEngineCandidate(val packageName: String?)

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = kotlinx.coroutines.flow.MutableStateFlow(VoicePlaybackState())
    private var speaker: TextToSpeech? = null
    private var lastUtteranceId: String? = null
    private var activeUtteranceIds: Set<String> = emptySet()
    private var playbackGeneration = 0L
    private var errorEventSequence = 0L
    private var ready = false
    private var initializationIssue: VoicePlaybackIssue? = null
    private var pendingPlayback: Pair<String, String>? = null
    private val pendingEngineCandidates = ArrayDeque<TtsEngineCandidate>()
    private var activeEngineCandidate: TtsEngineCandidate? = null
    private var initializationGeneration = 0L

    fun observe(): kotlinx.coroutines.flow.StateFlow<VoicePlaybackState> = mutableState

    private fun handleInitialization(status: Int) {
        val activeSpeaker = speaker
        if (status != TextToSpeech.SUCCESS || activeSpeaker == null) {
            tryNextEngine(VoicePlaybackIssue.NOT_READY)
            return
        }
        if (!configureOfflineEnglishVoice(activeSpeaker)) {
            tryNextEngine(VoicePlaybackIssue.LANGUAGE_UNAVAILABLE)
            return
        }
        activeSpeaker.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    scope.launch {
                        if (utteranceId == lastUtteranceId) {
                            lastUtteranceId = null
                            activeUtteranceIds = emptySet()
                            mutableState.value = VoicePlaybackState(phase = VoicePlaybackPhase.IDLE)
                        }
                    }
                }

                @Deprecated("Called on older Android TTS engines")
                override fun onError(utteranceId: String?) {
                    handlePlaybackError(utteranceId)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    handlePlaybackError(utteranceId)
                }

                private fun handlePlaybackError(utteranceId: String?) {
                    scope.launch {
                        if (utteranceId in activeUtteranceIds) {
                            speaker?.stop()
                            lastUtteranceId = null
                            activeUtteranceIds = emptySet()
                            publishError(VoicePlaybackIssue.PLAYBACK_FAILED)
                        }
                    }
                }
            },
        )
        ready = true
        initializationIssue = null
        pendingEngineCandidates.clear()
        activeEngineCandidate = null
        mutableState.value = VoicePlaybackState(phase = VoicePlaybackPhase.IDLE)
        val pending = pendingPlayback
        pendingPlayback = null
        pending?.let { (messageId, markdownText) ->
            speak(messageId, markdownText)
        }
    }

    fun toggle(messageId: String, markdownText: String) {
        val current = mutableState.value
        if (current.phase == VoicePlaybackPhase.SPEAKING && current.messageId == messageId) {
            stop()
            return
        }
        if (!ready) {
            pendingPlayback = messageId to markdownText
            initializationIssue = null
            ensureInitialized()
            return
        }
        speak(messageId, markdownText)
    }

    fun stop() {
        playbackGeneration += 1
        pendingPlayback = null
        lastUtteranceId = null
        activeUtteranceIds = emptySet()
        speaker?.stop()
        mutableState.value = VoicePlaybackState(phase = VoicePlaybackPhase.IDLE)
    }

    fun reportDictationActive() {
        publishError(VoicePlaybackIssue.DICTATION_ACTIVE)
    }

    fun reportAlwaysOnActive() {
        publishError(VoicePlaybackIssue.ALWAYS_ON_ACTIVE)
    }

    fun release() {
        stop()
        initializationGeneration += 1
        pendingEngineCandidates.clear()
        activeEngineCandidate = null
        speaker?.shutdown()
        speaker = null
        ready = false
        scope.cancel()
    }

    private fun speak(messageId: String, markdownText: String) {
        val activeSpeaker = speaker
        if (activeSpeaker == null || !ready) {
            publishError(initializationIssue ?: VoicePlaybackIssue.NOT_READY)
            return
        }
        val chunks = speechChunks(markdownText)
        if (chunks.isEmpty()) {
            publishError(VoicePlaybackIssue.PLAYBACK_FAILED)
            return
        }

        activeSpeaker.stop()
        val generation = ++playbackGeneration
        val utteranceIds = chunks.indices.map { index ->
            "read-aloud-$messageId-$generation-$index"
        }
        activeUtteranceIds = utteranceIds.toSet()
        lastUtteranceId = utteranceIds.last()
        mutableState.value = VoicePlaybackState(
            phase = VoicePlaybackPhase.SPEAKING,
            messageId = messageId,
        )
        chunks.forEachIndexed { index, chunk ->
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (activeSpeaker.speak(chunk, queueMode, null, utteranceIds[index]) == TextToSpeech.ERROR) {
                activeSpeaker.stop()
                lastUtteranceId = null
                activeUtteranceIds = emptySet()
                publishError(VoicePlaybackIssue.PLAYBACK_FAILED)
                return
            }
        }
    }

    private fun ensureInitialized() {
        if (speaker != null) return
        mutableState.value = VoicePlaybackState(phase = VoicePlaybackPhase.INITIALIZING)
        pendingEngineCandidates.clear()
        pendingEngineCandidates.add(TtsEngineCandidate(packageName = null))
        availableTtsEnginePackages(appContext).forEach { packageName ->
            pendingEngineCandidates.add(TtsEngineCandidate(packageName))
        }
        initializeNextEngine()
    }

    private fun initializeNextEngine() {
        val candidate = pendingEngineCandidates.pollFirst()
        if (candidate == null) {
            failInitialization(initializationIssue ?: VoicePlaybackIssue.NOT_READY)
            return
        }
        activeEngineCandidate = candidate
        val generation = ++initializationGeneration
        val listener = TextToSpeech.OnInitListener { status ->
            scope.launch {
                // Some test or OEM engines can invoke the callback before the constructor returns.
                yield()
                if (generation == initializationGeneration) {
                    handleInitialization(status)
                }
            }
        }
        speaker = candidate.packageName?.let { packageName ->
            TextToSpeech(appContext, listener, packageName)
        } ?: TextToSpeech(appContext, listener)
    }

    private fun tryNextEngine(issue: VoicePlaybackIssue) {
        initializationIssue = issue
        val failedSpeaker = speaker
        val failedPackage = activeEngineCandidate?.packageName ?: failedSpeaker?.defaultEngine
        failedSpeaker?.shutdown()
        speaker = null
        activeEngineCandidate = null
        if (failedPackage != null) {
            pendingEngineCandidates.removeAll { candidate -> candidate.packageName == failedPackage }
        }
        if (pendingEngineCandidates.isEmpty()) {
            failInitialization(issue)
        } else {
            initializeNextEngine()
        }
    }

    private fun failInitialization(issue: VoicePlaybackIssue) {
        pendingPlayback = null
        ready = false
        initializationIssue = issue
        pendingEngineCandidates.clear()
        activeEngineCandidate = null
        speaker?.shutdown()
        speaker = null
        publishError(issue)
    }

    private fun publishError(issue: VoicePlaybackIssue) {
        errorEventSequence += 1
        mutableState.value = VoicePlaybackState(
            phase = VoicePlaybackPhase.ERROR,
            issue = issue,
            errorEventId = errorEventSequence,
        )
    }
}

@Suppress("DEPRECATION")
internal fun availableTtsEnginePackages(context: Context): List<String> {
    val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
    return context.packageManager.queryIntentServices(intent, 0)
        .mapNotNull { resolveInfo -> resolveInfo.serviceInfo?.packageName }
        .distinct()
        .sorted()
}

internal fun configureOfflineEnglishVoice(speaker: TextToSpeech): Boolean {
    val offlineEnglishVoice = speaker.voices
        .orEmpty()
        .asSequence()
        .filterNot { voice -> voice.isNetworkConnectionRequired }
        .filter { voice -> voice.locale.isEnglishVoiceLocale() }
        .sortedBy { voice -> voice.name }
        .firstOrNull()
        ?: return false
    return speaker.setVoice(offlineEnglishVoice) != TextToSpeech.ERROR
}

internal fun Locale.isEnglishVoiceLocale(): Boolean {
    return language.equals(Locale.ENGLISH.language, ignoreCase = true) ||
        language.equals(ENGLISH_ISO3_LANGUAGE, ignoreCase = true) ||
        runCatching { isO3Language.equals(ENGLISH_ISO3_LANGUAGE, ignoreCase = true) }.getOrDefault(false)
}

internal fun speechChunks(markdownText: String, maxChars: Int = DEFAULT_SPEECH_CHUNK_CHARS): List<String> {
    require(maxChars > 0) { "maxChars must be positive" }
    val speechText = markdownToSpeechText(markdownText)
    if (speechText.isBlank()) return emptyList()
    if (speechText.length <= maxChars) return listOf(speechText)

    val chunks = mutableListOf<String>()
    var remaining = speechText
    while (remaining.isNotEmpty()) {
        if (remaining.length <= maxChars) {
            chunks += remaining
            break
        }
        val window = remaining.take(maxChars + 1)
        val splitAt = listOf(
            window.lastIndexOf(". "),
            window.lastIndexOf("? "),
            window.lastIndexOf("! "),
            window.lastIndexOf("; "),
            window.lastIndexOf(' '),
        ).maxOrNull()?.takeIf { it in 1 until maxChars } ?: (maxChars - 1)
        chunks += remaining.take(splitAt + 1).trim()
        remaining = remaining.drop(splitAt + 1).trimStart()
    }
    return chunks.filter { it.isNotBlank() }
}

internal fun markdownToSpeechText(markdownText: String): String {
    return markdownText
        .replace(FENCED_CODE_BLOCK, " Code block omitted. ")
        .replace(MARKDOWN_IMAGE, "$1")
        .replace(MARKDOWN_LINK, "$1")
        .replace(INLINE_CODE, "$1")
        .replace(MARKDOWN_PREFIX, "")
        .replace(MARKDOWN_EMPHASIS, "")
        .replace(WHITESPACE, " ")
        .trim()
}

private val FENCED_CODE_BLOCK = Regex("```[\\s\\S]*?```")
private val MARKDOWN_IMAGE = Regex("""!\[([^]]*)]\([^)]*\)""")
private val MARKDOWN_LINK = Regex("""\[([^]]+)]\([^)]*\)""")
private val INLINE_CODE = Regex("`([^`]+)`")
private val MARKDOWN_PREFIX = Regex("(?m)^\\s{0,3}(?:#{1,6}\\s+|[-*+]\\s+|>\\s*)")
private val MARKDOWN_EMPHASIS = Regex("[*_~]")
private val WHITESPACE = Regex("\\s+")
private const val DEFAULT_SPEECH_CHUNK_CHARS = 3_500
private const val ENGLISH_ISO3_LANGUAGE = "eng"
