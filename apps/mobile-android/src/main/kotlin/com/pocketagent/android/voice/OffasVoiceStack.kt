package com.pocketagent.android.voice

import android.annotation.SuppressLint
import android.Manifest
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.os.BatteryManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.pocketagent.android.MainActivity
import com.pocketagent.android.R
import com.pocketagent.android.runtime.AppOperationTrace
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

internal data class VoiceModelStatus(
    val ready: Boolean,
    val missingPaths: List<String>,
    val dedicatedWakeWordReady: Boolean,
    val missingWakeWordPaths: List<String>,
)

internal object VoiceModelCatalog {
    private const val ROOT_DIR = "offas-voice-models"
    private const val ASR_DIR = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"
    private const val KWS_DIR = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
    private const val KWS_ENCODER_FILE = "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
    private const val KWS_DECODER_FILE = "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
    private const val KWS_JOINER_FILE = "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
    private const val KWS_TOKENS_FILE = "tokens.txt"
    private const val STATUS_CACHE_TTL_MS = 1_000L

    private data class CachedStatus(
        val computedAtEpochMs: Long,
        val status: VoiceModelStatus,
    )

    private val rootCache = ConcurrentHashMap<String, File>()
    private val statusCache = ConcurrentHashMap<String, CachedStatus>()

    fun root(context: Context): File {
        val appContext = context.applicationContext
        return rootCache.getOrPut(appContext.packageName) {
            File(appContext.filesDir, ROOT_DIR)
        }
    }

    fun status(context: Context): VoiceModelStatus {
        val root = root(context)
        val cacheKey = root.absolutePath
        val now = System.currentTimeMillis()
        statusCache[cacheKey]?.let { cached ->
            if (now - cached.computedAtEpochMs <= STATUS_CACHE_TTL_MS) {
                return cached.status
            }
        }
        val missing = mutableListOf<String>()
        requiredAsrFiles(context).filterNot(File::isFile).forEach { missing += it.absolutePath }
        val missingWakeWordPaths = kwsFiles(context)
            .filterNot(File::isFile)
            .map(File::getAbsolutePath)
            .toMutableList()
        val keywords = keywordsFile(context)
        if (!keywords.isFile || !runCatching { hasDedicatedOffasKeyword(keywords.readText()) }.getOrDefault(false)) {
            missingWakeWordPaths += keywords.absolutePath
        }
        if (!VoiceModelInstaller.isProductionInstallCompatible(root)) {
            missing += File(root, VoiceModelInstaller.INSTALL_MARKER_FILE).absolutePath
        }
        return VoiceModelStatus(
            ready = missing.isEmpty(),
            missingPaths = missing,
            dedicatedWakeWordReady = missingWakeWordPaths.isEmpty(),
            missingWakeWordPaths = missingWakeWordPaths.distinct(),
        ).also { status ->
            statusCache[cacheKey] = CachedStatus(
                computedAtEpochMs = now,
                status = status,
            )
        }
    }

    fun invalidate(context: Context) {
        statusCache.remove(root(context).absolutePath)
    }

    fun hasDedicatedWakeWordModel(context: Context): Boolean {
        val keywords = keywordsFile(context)
        return kwsFiles(context).all { it.exists() } &&
            keywords.isFile &&
            runCatching { hasDedicatedOffasKeyword(keywords.readText()) }.getOrDefault(false)
    }

    fun recognizer(context: Context): OnlineRecognizer {
        val modelDir = File(root(context), ASR_DIR)
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(modelDir, "encoder-epoch-99-avg-1.int8.onnx").absolutePath,
                    decoder = File(modelDir, "decoder-epoch-99-avg-1.onnx").absolutePath,
                    joiner = File(modelDir, "joiner-epoch-99-avg-1.int8.onnx").absolutePath,
                ),
                tokens = File(modelDir, "tokens.txt").absolutePath,
                modelType = "zipformer",
                numThreads = 2,
                provider = "cpu",
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        )
        return OnlineRecognizer(config = config)
    }

    fun keywordSpotter(context: Context): KeywordSpotter? {
        if (!hasDedicatedWakeWordModel(context)) {
            return null
        }
        val modelDir = File(root(context), KWS_DIR)
        val config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(modelDir, KWS_ENCODER_FILE).absolutePath,
                    decoder = File(modelDir, KWS_DECODER_FILE).absolutePath,
                    joiner = File(modelDir, KWS_JOINER_FILE).absolutePath,
                ),
                tokens = File(modelDir, KWS_TOKENS_FILE).absolutePath,
                modelType = "zipformer2",
                provider = "cpu",
                numThreads = 1,
            ),
            maxActivePaths = KWS_MAX_ACTIVE_PATHS,
            keywordsFile = keywordsFile(context).absolutePath,
            keywordsScore = KWS_KEYWORDS_SCORE,
            keywordsThreshold = KWS_KEYWORDS_THRESHOLD,
            numTrailingBlanks = KWS_TRAILING_BLANKS,
        )
        return KeywordSpotter(config = config)
    }

    private fun requiredAsrFiles(context: Context): List<File> {
        val dir = File(root(context), ASR_DIR)
        return listOf(
            File(dir, "encoder-epoch-99-avg-1.int8.onnx"),
            File(dir, "decoder-epoch-99-avg-1.onnx"),
            File(dir, "joiner-epoch-99-avg-1.int8.onnx"),
            File(dir, "tokens.txt"),
        )
    }

    private fun kwsFiles(context: Context): List<File> {
        val dir = File(root(context), KWS_DIR)
        return requiredKwsFileNames().map { fileName -> File(dir, fileName) }
    }

    internal fun requiredKwsFileNames(): List<String> {
        return listOf(KWS_ENCODER_FILE, KWS_DECODER_FILE, KWS_JOINER_FILE, KWS_TOKENS_FILE)
    }

    private fun keywordsFile(context: Context): File {
        return File(File(root(context), KWS_DIR), "keywords.txt")
    }
}

internal interface OffasVoiceEngine {
    suspend fun awaitWakeAndCommand(
        wakePhrase: String,
        silenceTimeoutSeconds: Int,
        directCapture: Boolean,
        onWakeWord: () -> Unit,
        onStateChanged: (VoiceServiceState) -> Unit,
        onPartialTranscript: (String) -> Unit = {},
    ): String?

    fun prepareCapture(directCapture: Boolean = true) = Unit

    fun requestFinish(disposition: VoiceCaptureStopDisposition = VoiceCaptureStopDisposition.SUBMIT) = Unit

    fun release()
}

internal fun prepareVoiceEngineForLoop(
    engine: OffasVoiceEngine?,
    directCapture: Boolean,
) {
    engine?.prepareCapture(directCapture)
}

internal enum class VoiceCaptureStopDisposition {
    SUBMIT,
    DISCARD,
}

internal data class OffasTranscriptOutcome(
    val spokenResponse: String,
    val nextServiceState: VoiceServiceState,
)

internal fun resolveOffasTranscriptOutcome(
    toolOutputs: List<String>,
    assistantText: String,
    voiceActivationEnabled: Boolean,
): OffasTranscriptOutcome {
    val spokenResponse = when {
        toolOutputs.isNotEmpty() -> toolOutputs.joinToString(separator = " ")
        assistantText.isNotBlank() -> assistantText
        else -> "Done."
    }
    return OffasTranscriptOutcome(
        spokenResponse = spokenResponse,
        nextServiceState = if (voiceActivationEnabled) VoiceServiceState.LISTENING else VoiceServiceState.DISABLED,
    )
}

internal fun shouldContinueVoiceService(
    nextServiceState: VoiceServiceState,
    voiceActivationEnabled: Boolean,
): Boolean {
    return voiceActivationEnabled && nextServiceState == VoiceServiceState.LISTENING
}

internal class SherpaOnnxOffasVoiceEngine(
    private val appContext: Context,
) : OffasVoiceEngine {
    private val keywordSpotterDelegate = lazy { VoiceModelCatalog.keywordSpotter(appContext) }
    private val keywordSpotter: KeywordSpotter? by keywordSpotterDelegate
    private val recognizerLock = Any()
    private var recognizerInstance: OnlineRecognizer? = null
    private val recognizer: OnlineRecognizer
        get() = synchronized(recognizerLock) {
            recognizerInstance ?: VoiceModelCatalog.recognizer(appContext).also {
                recognizerInstance = it
                android.util.Log.i(VOICE_RUNTIME_LOG_TAG, "VOICE_ASR|state=loaded")
            }
        }
    @Volatile
    private var stopDisposition: VoiceCaptureStopDisposition? = null

    // Linear guard branches make the capture/resource state machine explicit and fail closed.
    @Suppress("CyclomaticComplexMethod")
    @SuppressLint("MissingPermission")
    override suspend fun awaitWakeAndCommand(
        wakePhrase: String,
        silenceTimeoutSeconds: Int,
        directCapture: Boolean,
        onWakeWord: () -> Unit,
        onStateChanged: (VoiceServiceState) -> Unit,
        onPartialTranscript: (String) -> Unit,
    ): String? = withContext(Dispatchers.Default) {
        check(VoiceAudioCaptureLease.tryAcquire()) {
            "Another PocketGPT voice capture is already active."
        }
        try {
            // VoiceService and composer dictation check RECORD_AUDIO before invoking the engine.
            val minBufferBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferBytes.coerceAtLeast(
                    SAMPLE_RATE * Short.SIZE_BYTES * VOICE_MODEL_STARTUP_BUFFER_SECONDS,
                ),
            )
            var session: WakeCommandSession? = null
            try {
                check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                    "AudioRecord initialization failed."
                }
                audioRecord.startRecording()
                val activeSession = WakeCommandSession(
                    wakePhrase = wakePhrase,
                    silenceTimeoutSeconds = silenceTimeoutSeconds,
                    directCapture = directCapture,
                    onWakeWord = onWakeWord,
                    onStateChanged = onStateChanged,
                    onPartialTranscript = onPartialTranscript,
                ).also { session = it }
                val samples = ShortArray(SHORT_BUFFER_SIZE)
                VoiceCaptureReadiness.setReady(false)
                var captureReadyLogged = false
                stopDisposition?.let { disposition ->
                    return@withContext when (disposition) {
                        VoiceCaptureStopDisposition.SUBMIT ->
                            activeSession.finishCurrentCommand()?.takeIf { it.isNotBlank() }
                        VoiceCaptureStopDisposition.DISCARD -> null
                    }
                }
                activeSession.start()
                while (isActive) {
                    stopDisposition?.let { disposition ->
                        return@withContext when (disposition) {
                            VoiceCaptureStopDisposition.SUBMIT ->
                                activeSession.finishCurrentCommand()?.takeIf { it.isNotBlank() }
                            VoiceCaptureStopDisposition.DISCARD -> null
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        audioRecord.activeRecordingConfiguration?.isClientSilenced == true
                    ) {
                        throw VoiceCaptureSilencedException()
                    }
                    val read = audioRecord.read(samples, 0, samples.size)
                    if (read < 0) {
                        error(audioRecordFailureMessage(read))
                    }
                    if (read == 0) continue
                    if (!captureReadyLogged) {
                        captureReadyLogged = true
                        VoiceCaptureReadiness.setReady(true)
                        android.util.Log.i(
                            VOICE_RUNTIME_LOG_TAG,
                            "VOICE_CAPTURE_READY|silenced=false|mode=${if (directCapture) "direct" else "wake"}",
                        )
                    }
                    val command = activeSession.accept(samples.copyOf(read).toFloatChunk())
                    if (command != null) {
                        return@withContext command.takeIf { it.isNotBlank() }
                    }
                    stopDisposition?.let { disposition ->
                        return@withContext when (disposition) {
                            VoiceCaptureStopDisposition.SUBMIT ->
                                activeSession.finishCurrentCommand()?.takeIf { it.isNotBlank() }
                            VoiceCaptureStopDisposition.DISCARD -> null
                        }
                    }
                }
                null
            } finally {
                VoiceCaptureReadiness.setReady(false)
                runCatching { session?.release() }
                runCatching { audioRecord.stop() }
                audioRecord.release()
            }
        } finally {
            releaseRecognizer()
            VoiceAudioCaptureLease.release()
        }
    }

    override fun prepareCapture(directCapture: Boolean) {
        stopDisposition = null
        if (directCapture) {
            recognizer
        } else {
            // Keep only KWS resident while idle. AudioRecord buffers speech while ASR initializes
            // after a wake hit, avoiding full-ASR memory pressure during all-day listening.
            keywordSpotterDelegate.value
        }
    }

    override fun requestFinish(disposition: VoiceCaptureStopDisposition) {
        stopDisposition = disposition
    }

    override fun release() {
        if (keywordSpotterDelegate.isInitialized()) {
            runCatching { keywordSpotterDelegate.value?.release() }
        }
        releaseRecognizer()
    }

    private fun releaseRecognizer() {
        val activeRecognizer = synchronized(recognizerLock) {
            recognizerInstance.also { recognizerInstance = null }
        }
        if (activeRecognizer != null) {
            runCatching { activeRecognizer.release() }
            android.util.Log.i(VOICE_RUNTIME_LOG_TAG, "VOICE_ASR|state=released")
        }
    }

    private inner class WakeCommandSession(
        private val wakePhrase: String,
        private val silenceTimeoutSeconds: Int,
        private val directCapture: Boolean,
        private val onWakeWord: () -> Unit,
        private val onStateChanged: (VoiceServiceState) -> Unit,
        private val onPartialTranscript: (String) -> Unit,
    ) {
        private val ringBuffer = ArrayDeque<FloatArray>()
        private val wakeSpotter = if (directCapture) null else keywordSpotter
        private val wakeStream = wakeSpotter?.createStream()
        private var commandStream = if (directCapture) recognizer.createStream() else null
        private var capturingCommand = directCapture
        private var lastVoiceAtMs = System.currentTimeMillis()
        private var captureStartedAtMs = lastVoiceAtMs
        private var lastTranscript = ""
        private var lastPublishedState: VoiceServiceState? = null
        private val wakeEnergyGate = WakeEnergyGate(
            rmsThreshold = KWS_GATE_RMS_THRESHOLD,
            trailingSilenceMs = KWS_GATE_TRAILING_MS,
        )

        init {
            check(
                directCapture ||
                    (canUseDedicatedOffasKeyword(wakePhrase) && wakeSpotter != null),
            ) {
                "Always-on listening requires the dedicated Offas wake-word model."
            }
        }

        fun start() {
            if (directCapture) {
                onWakeWord()
                publishState(VoiceServiceState.CAPTURING)
            } else {
                publishState(VoiceServiceState.LISTENING)
            }
        }

        fun accept(chunk: FloatArray): String? {
            appendToRingBuffer(chunk)
            return if (capturingCommand) {
                captureCommand(chunk)
            } else {
                detectWake(chunk)
                null
            }
        }

        fun release() {
            commandStream?.release()
            wakeStream?.release()
        }

        fun finishCurrentCommand(): String? {
            val stream = commandStream ?: return null
            return finishCommandCapture(stream)
        }

        private fun appendToRingBuffer(chunk: FloatArray) {
            ringBuffer.addLast(chunk)
            while (ringBuffer.size > RING_BUFFER_CHUNKS) {
                ringBuffer.removeFirst()
            }
        }

        private fun detectWake(chunk: FloatArray) {
            when (wakeEnergyGate.onChunk(chunk.rms(), SystemClock.elapsedRealtime())) {
                WakeEnergyGateDecision.SKIP -> return
                WakeEnergyGateDecision.OPEN_WITH_PREROLL -> {
                    resetWakeDecoder()
                    android.util.Log.i(VOICE_RUNTIME_LOG_TAG, "KWS_GATE|state=open")
                    for (buffered in ringBuffer) {
                        if (isWakeDetected(buffered)) {
                            startCommandCapture()
                            return
                        }
                    }
                }
                WakeEnergyGateDecision.PROCESS -> {
                    if (isWakeDetected(chunk)) {
                        startCommandCapture()
                    }
                }
                WakeEnergyGateDecision.PROCESS_AND_CLOSE -> {
                    if (isWakeDetected(chunk)) {
                        startCommandCapture()
                        return
                    }
                    resetWakeStream()
                    android.util.Log.i(VOICE_RUNTIME_LOG_TAG, "KWS_GATE|state=closed")
                }
            }
        }

        private fun isWakeDetected(chunk: FloatArray): Boolean {
            val stream = wakeStream ?: return false
            return wakeSpotter?.detectKeyword(chunk, stream) == true
        }

        private fun startCommandCapture() {
            capturingCommand = true
            android.util.Log.i(
                VOICE_RUNTIME_LOG_TAG,
                "KWS_WAKE_DETECTED|profile=offas-v1-score-3.0-threshold-0.1",
            )
            onWakeWord()
            publishState(VoiceServiceState.CAPTURING)
            commandStream = recognizer.createStream()
            ringBuffer.forEach { buffered ->
                commandStream?.acceptWaveform(buffered, SAMPLE_RATE)
            }
            lastVoiceAtMs = System.currentTimeMillis()
            captureStartedAtMs = lastVoiceAtMs
            lastTranscript = ""
        }

        private fun captureCommand(chunk: FloatArray): String? {
            val stream = commandStream ?: return null
            val partial = recognizer.partialText(chunk, stream)
            if (chunk.rms() >= SPEECH_RMS_THRESHOLD || partial.length > lastTranscript.length) {
                lastVoiceAtMs = System.currentTimeMillis()
            }
            if (partial != lastTranscript) {
                onPartialTranscript(partial)
            }
            lastTranscript = partial
            publishState(VoiceServiceState.TRANSCRIBING)
            return if (isSilenceTimeoutReached() || isMaximumCommandDurationReached()) {
                finishCommandCapture(stream)
            } else {
                null
            }
        }

        private fun isSilenceTimeoutReached(): Boolean {
            return System.currentTimeMillis() - lastVoiceAtMs >= silenceTimeoutSeconds * 1000L
        }

        private fun isMaximumCommandDurationReached(): Boolean {
            return System.currentTimeMillis() - captureStartedAtMs >= MAX_COMMAND_DURATION_MS
        }

        private fun publishState(state: VoiceServiceState) {
            if (lastPublishedState != state) {
                lastPublishedState = state
                onStateChanged(state)
            }
        }

        private fun finishCommandCapture(stream: OnlineStream): String {
            stream.inputFinished()
            recognizer.drain(stream)
            val recognizedText = recognizer.getResult(stream).text.trim()
            val finalText = if (directCapture || wakePhrase.isBlank()) {
                recognizedText
            } else {
                removeLeadingWakePhrase(recognizedText, wakePhrase)
            }
            if (finalText.isNotBlank() && finalText != lastTranscript) {
                onPartialTranscript(finalText)
            }
            stream.release()
            if (commandStream === stream) {
                commandStream = null
            }
            resetWakeStream()
            return finalText
        }

        private fun resetWakeStream() {
            resetWakeDecoder()
            wakeEnergyGate.reset()
        }

        private fun resetWakeDecoder() {
            val stream = wakeStream ?: return
            wakeSpotter?.reset(stream)
        }
    }

    private fun KeywordSpotter.detectKeyword(chunk: FloatArray, stream: OnlineStream): Boolean {
        stream.acceptWaveform(chunk, SAMPLE_RATE)
        var hit = false
        while (isReady(stream)) {
            decode(stream)
            if (getResult(stream).keyword.isNotBlank()) {
                hit = true
            }
        }
        return hit
    }

    private fun OnlineRecognizer.partialText(chunk: FloatArray, stream: OnlineStream): String {
        stream.acceptWaveform(chunk, SAMPLE_RATE)
        drain(stream)
        return getResult(stream).text.trim()
    }

    private fun OnlineRecognizer.drain(stream: OnlineStream) {
        while (isReady(stream)) {
            decode(stream)
        }
    }
}

internal object OffasRuntime {
    const val ACTION_START = "com.pocketagent.android.voice.START"
    const val ACTION_STOP = "com.pocketagent.android.voice.STOP"
    const val ACTION_CAPTURE_ONCE = "com.pocketagent.android.voice.CAPTURE_ONCE"
    const val ACTION_CANCEL_CAPTURE = "com.pocketagent.android.voice.CANCEL_CAPTURE"
    private const val UNIQUE_WORK_NAME = "offas-listener-watchdog"

    fun start(context: Context) {
        pauseRecovery(context)
        ContextCompat.startForegroundService(
            context,
            Intent(context, OffasListenerService::class.java).setAction(ACTION_START),
        )
    }

    fun stop(context: Context) {
        context.startService(Intent(context, OffasListenerService::class.java).setAction(ACTION_STOP))
        pauseRecovery(context)
    }

    fun pause(context: Context) {
        context.stopService(Intent(context, OffasListenerService::class.java))
        pauseRecovery(context)
    }

    fun captureOnce(
        context: Context,
        invocationSource: VoiceInvocationSource = VoiceInvocationSource.ASSISTANT,
        sessionId: Long = VoiceSessionSignals.newSessionId(),
    ) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, OffasListenerService::class.java)
                .setAction(ACTION_CAPTURE_ONCE)
                .putExtra(EXTRA_INVOCATION_SOURCE, invocationSource.name)
                .putExtra(EXTRA_SESSION_ID, sessionId),
        )
    }

    fun cancelCapture(context: Context, sessionId: Long) {
        context.startService(
            Intent(context, OffasListenerService::class.java)
                .setAction(ACTION_CANCEL_CAPTURE)
                .putExtra(EXTRA_SESSION_ID, sessionId),
        )
    }

    /** Remove recovery artifacts created by older builds. Never resurrect microphone capture. */
    fun pauseRecovery(context: Context) {
        val appContext = context.applicationContext
        WorkManager.getInstance(appContext).cancelUniqueWork(UNIQUE_WORK_NAME)
        val legacyIntent = Intent(LEGACY_WATCHDOG_ACTION).setClassName(
            appContext.packageName,
            LEGACY_WATCHDOG_RECEIVER,
        )
        val legacyPendingIntent = PendingIntent.getBroadcast(
            context,
            WATCHDOG_REQUEST_CODE,
            legacyIntent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (legacyPendingIntent != null) {
            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(legacyPendingIntent)
            legacyPendingIntent.cancel()
        }
    }

    private const val WATCHDOG_REQUEST_CODE = 4037
    private const val LEGACY_WATCHDOG_ACTION = "com.pocketagent.android.voice.WATCHDOG"
    private const val LEGACY_WATCHDOG_RECEIVER = "com.pocketagent.android.voice.OffasWatchdogReceiver"
    private const val EXTRA_INVOCATION_SOURCE = "voice_invocation_source"
    private const val EXTRA_SESSION_ID = "voice_session_id"

    fun captureSource(intent: Intent): VoiceInvocationSource {
        return intent.getStringExtra(EXTRA_INVOCATION_SOURCE)
            ?.let { value -> VoiceInvocationSource.entries.firstOrNull { it.name == value } }
            ?: VoiceInvocationSource.UNTRUSTED_ASSISTANT
    }

    fun captureSessionId(intent: Intent): Long {
        return intent.getLongExtra(EXTRA_SESSION_ID, VoiceSessionSignals.NO_SESSION_ID)
            .takeIf { it != VoiceSessionSignals.NO_SESSION_ID }
            ?: VoiceSessionSignals.newSessionId()
    }

    fun cancelSessionId(intent: Intent): Long {
        return intent.getLongExtra(EXTRA_SESSION_ID, VoiceSessionSignals.NO_SESSION_ID)
    }
}

class OffasListenerService : Service() {
    private data class TtsEngineCandidate(val packageName: String?)

    private data class PendingVoiceSpeech(
        val utteranceIds: Set<String>,
        val finalUtteranceId: String,
        val completion: CompletableDeferred<Boolean>,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val offscreenRuntimeClient by lazy(LazyThreadSafetyMode.NONE) {
        createOffscreenRuntimeClient(applicationContext)
    }
    private val settingsStore by lazy { VoiceActivationSettingsStore.process(applicationContext) }
    private val audioFocus by lazy {
        VoiceAudioFocusCoordinator(applicationContext) {
            requestedRuntimePauseReason = VoiceRuntimePauseReason.OTHER_MICROPHONE
            engine?.requestFinish(VoiceCaptureStopDisposition.DISCARD)
            cancelPendingSpeech()
        }
    }
    private val audioManager by lazy {
        applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private val batteryManager by lazy {
        applicationContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    }
    private val powerManager by lazy {
        applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    }
    private val keyguardManager by lazy {
        applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    }
    private var serviceJob: Job? = null
    private var runtimeHealthJob: Job? = null
    private var serviceLoopGeneration = 0L
    private var speaker: TextToSpeech? = null
    private var ttsReady = false
    private val ttsInitialization = CompletableDeferred<Boolean>()
    private val pendingTtsEngines = ArrayDeque<TtsEngineCandidate>()
    private var activeTtsEngine: TtsEngineCandidate? = null
    private var ttsInitializationGeneration = 0L
    private var ttsEngineTimeoutJob: Job? = null
    private var engine: OffasVoiceEngine? = null
    private var speechGeneration = 0L
    private val retryPolicy = VoiceRuntimeRetryPolicy()
    private var stableListeningJob: Job? = null

    @Volatile
    private var requestedRuntimePauseReason: VoiceRuntimePauseReason? = null

    @Volatile
    private var pendingSpeech: PendingVoiceSpeech? = null

    override fun onCreate() {
        super.onCreate()
        initializeSpeechOutput()
        ensureNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            OffasRuntime.ACTION_STOP -> {
                settingsStore.setEnabled(false)
                stopServiceLoop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                START_NOT_STICKY
            }
            OffasRuntime.ACTION_CAPTURE_ONCE -> {
                startServiceLoop(
                    directCapture = true,
                    invocationSource = OffasRuntime.captureSource(intent),
                    sessionId = OffasRuntime.captureSessionId(intent),
                )
                if (settingsStore.state().enabled) START_STICKY else START_NOT_STICKY
            }
            OffasRuntime.ACTION_CANCEL_CAPTURE -> {
                if (!VoiceSessionSignals.matches(
                        VoiceSessionSignals.currentSessionId(),
                        OffasRuntime.cancelSessionId(intent),
                    )
                ) {
                    return if (settingsStore.state().enabled) START_STICKY else START_NOT_STICKY
                }
                offscreenRuntimeClient.cancelPendingInteraction()
                if (settingsStore.state().enabled) {
                    startServiceLoop(
                        directCapture = false,
                        invocationSource = VoiceInvocationSource.WAKE_WORD,
                    )
                    START_STICKY
                } else {
                    stopServiceLoop()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    START_NOT_STICKY
                }
            }
            OffasRuntime.ACTION_START -> {
                if (settingsStore.state().enabled) {
                    startServiceLoop(
                        directCapture = false,
                        invocationSource = VoiceInvocationSource.WAKE_WORD,
                    )
                    START_STICKY
                } else {
                    stopSelf()
                    START_NOT_STICKY
                }
            }
            null -> {
                if (settingsStore.state().enabled) {
                    startServiceLoop(
                        directCapture = false,
                        invocationSource = VoiceInvocationSource.WAKE_WORD,
                    )
                    START_STICKY
                } else {
                    stopSelf()
                    START_NOT_STICKY
                }
            }
            else -> {
                stopSelf()
                START_NOT_STICKY
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopServiceLoop()
        audioFocus.abandon()
        ttsInitializationGeneration += 1
        ttsEngineTimeoutJob?.cancel()
        ttsEngineTimeoutJob = null
        pendingTtsEngines.clear()
        activeTtsEngine = null
        speaker?.shutdown()
        speaker = null
        scope.cancel()
        super.onDestroy()
    }

    private fun initializeSpeechOutput() {
        pendingTtsEngines.clear()
        pendingTtsEngines.add(TtsEngineCandidate(packageName = null))
        availableTtsEnginePackages(applicationContext).forEach { packageName ->
            pendingTtsEngines.add(TtsEngineCandidate(packageName))
        }
        initializeNextSpeechEngine()
    }

    private fun initializeNextSpeechEngine() {
        val candidate = pendingTtsEngines.pollFirst()
        if (candidate == null) {
            completeSpeechInitialization(ready = false)
            return
        }
        activeTtsEngine = candidate
        val generation = ++ttsInitializationGeneration
        val listener = TextToSpeech.OnInitListener { status ->
            scope.launch {
                // Some OEM engines invoke this callback before TextToSpeech construction returns.
                yield()
                if (generation == ttsInitializationGeneration) {
                    handleSpeechInitialization(status)
                }
            }
        }
        speaker = candidate.packageName?.let { packageName ->
            TextToSpeech(applicationContext, listener, packageName)
        } ?: TextToSpeech(applicationContext, listener)
        ttsEngineTimeoutJob?.cancel()
        ttsEngineTimeoutJob = scope.launch {
            delay(TTS_ENGINE_INITIALIZATION_TIMEOUT_MS)
            if (generation == ttsInitializationGeneration && !ttsReady) {
                tryNextSpeechEngine()
            }
        }
    }

    private fun handleSpeechInitialization(status: Int) {
        ttsEngineTimeoutJob?.cancel()
        ttsEngineTimeoutJob = null
        val activeSpeaker = speaker
        if (status != TextToSpeech.SUCCESS || activeSpeaker == null) {
            tryNextSpeechEngine()
            return
        }
        if (!configureOfflineEnglishVoice(activeSpeaker)) {
            tryNextSpeechEngine()
            return
        }
        activeSpeaker.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    val pending = pendingSpeech
                    if (pending != null && utteranceId == pending.finalUtteranceId) {
                        pending.completion.complete(true)
                    }
                }

                @Deprecated("Called on older Android TTS engines")
                override fun onError(utteranceId: String?) {
                    handleSpeechError(utteranceId)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    handleSpeechError(utteranceId)
                }

                private fun handleSpeechError(utteranceId: String?) {
                    val pending = pendingSpeech
                    if (utteranceId in pending?.utteranceIds.orEmpty()) {
                        speaker?.stop()
                        pending?.completion?.complete(false)
                    }
                }
            },
        )
        pendingTtsEngines.clear()
        activeTtsEngine = null
        completeSpeechInitialization(ready = true)
    }

    private fun tryNextSpeechEngine() {
        ttsEngineTimeoutJob?.cancel()
        ttsEngineTimeoutJob = null
        val failedSpeaker = speaker
        val failedPackage = activeTtsEngine?.packageName ?: failedSpeaker?.defaultEngine
        failedSpeaker?.shutdown()
        speaker = null
        activeTtsEngine = null
        if (failedPackage != null) {
            pendingTtsEngines.removeAll { candidate -> candidate.packageName == failedPackage }
        }
        initializeNextSpeechEngine()
    }

    private fun completeSpeechInitialization(ready: Boolean) {
        ttsEngineTimeoutJob?.cancel()
        ttsEngineTimeoutJob = null
        ttsReady = ready
        if (!ready) {
            pendingTtsEngines.clear()
            activeTtsEngine = null
            speaker?.shutdown()
            speaker = null
        }
        if (!ttsInitialization.isCompleted) {
            ttsInitialization.complete(ready)
        }
    }

    private fun startServiceLoop(
        directCapture: Boolean,
        invocationSource: VoiceInvocationSource,
        sessionId: Long = VoiceSessionSignals.NO_SESSION_ID,
    ) {
        if (!directCapture && !hasNotificationPermission()) {
            startForeground(NOTIFICATION_ID, buildNotification("Notification permission required"))
            handleBlockingPrerequisiteFailure(
                error = "Notification permission is required for always-on microphone controls.",
                notification = "Notification permission required",
            )
            return
        }
        if (!hasRecordAudioPermission()) {
            startForeground(NOTIFICATION_ID, buildNotification("Microphone permission required"))
            handleBlockingPrerequisiteFailure(
                error = "Microphone permission is missing.",
                notification = "Microphone permission required",
            )
            return
        }
        startForeground(NOTIFICATION_ID, buildNotification("Offas is starting"))
        val generation = ++serviceLoopGeneration
        runtimeHealthJob?.cancel()
        runtimeHealthJob = null
        requestedRuntimePauseReason = null
        val previousJob = serviceJob
        engine?.requestFinish(VoiceCaptureStopDisposition.DISCARD)
        cancelPendingSpeech()
        if (previousJob != null && !previousJob.isCompleted) {
            previousJob.cancel()
            previousJob.invokeOnCompletion {
                scope.launch {
                    launchServiceLoop(generation, directCapture, invocationSource, sessionId)
                }
            }
        } else {
            launchServiceLoop(generation, directCapture, invocationSource, sessionId)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun launchServiceLoop(
        generation: Long,
        directCapture: Boolean,
        invocationSource: VoiceInvocationSource,
        sessionId: Long,
    ) {
        if (generation != serviceLoopGeneration) {
            return
        }
        if (shouldClearPendingInteractionForSessionStart(
                currentSessionId = VoiceSessionSignals.currentSessionId(),
                nextSessionId = sessionId,
            )
        ) {
            offscreenRuntimeClient.cancelPendingInteraction()
        }
        VoiceSessionSignals.setCurrentSessionId(sessionId)
        serviceJob = scope.launch {
            try {
                runServiceLoop(directCapture, invocationSource)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: RuntimeException) {
                handleUnexpectedFailure(error)
            }
        }
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasNotificationPermission(): Boolean {
        return areVoiceNotificationsAvailable(applicationContext)
    }

    // Prerequisite, capture, pause, and recovery guards intentionally share one lifecycle owner.
    @Suppress("CyclomaticComplexMethod")
    private suspend fun runServiceLoop(
        directCapture: Boolean,
        invocationSource: VoiceInvocationSource,
    ) {
        if (!directCapture) {
            audioFocus.abandon()
            currentRuntimePauseReason()?.let { reason ->
                scheduleRuntimePause(reason)
                return
            }
        }
        val speechOutputReady = withTimeoutOrNull(TTS_INITIALIZATION_TIMEOUT_MS) {
            ttsInitialization.await()
        } == true
        if (!speechOutputReady) {
            handleBlockingPrerequisiteFailure(
                error = "Install an offline English text-to-speech voice so Offas can answer aloud.",
                notification = "Offline speech output required",
            )
            return
        }
        val modelStatus = AppOperationTrace.suspendSection(name = "voice.catalog_status") {
            VoiceModelCatalog.status(applicationContext)
        }
        if (!modelStatus.ready) {
            handleBlockingPrerequisiteFailure(
                error = "Voice models missing: ${modelStatus.missingPaths.joinToString()}",
                notification = "Install Offas voice files before enabling hands-free voice.",
            )
            return
        }
        val wakePhrase = settingsStore.state().wakePhrase
        if (!directCapture &&
            (!modelStatus.dedicatedWakeWordReady || !canUseDedicatedOffasKeyword(wakePhrase))
        ) {
            handleBlockingPrerequisiteFailure(
                error = "Dedicated Offas wake-word model is missing or incompatible.",
                notification = "Install the dedicated wake-word model for hands-free listening.",
            )
            return
        }
        engine = engine ?: AppOperationTrace.section(name = "voice.engine_init") {
            SherpaOnnxOffasVoiceEngine(applicationContext)
        }
        withContext(Dispatchers.Default) {
            AppOperationTrace.section(
                name = if (directCapture) "voice.asr_prewarm" else "voice.kws_prewarm",
            ) {
                // A replacement loop first asks the previous capture to stop. Preparing every new
                // loop clears that disposition; otherwise Talk now can inherit DISCARD and exit
                // before reading a single microphone frame.
                prepareVoiceEngineForLoop(engine, directCapture)
            }
        }
        settingsStore.updateServiceState(VoiceServiceState.STARTING)
        if (!directCapture) {
            startRuntimeHealthMonitor()
        }
        val transcript = try {
            engine?.awaitWakeAndCommand(
                wakePhrase = wakePhrase,
                silenceTimeoutSeconds = settingsStore.state().silenceTimeoutSeconds,
                directCapture = directCapture,
                onWakeWord = { handleWakeWord(showSession = !directCapture) },
                onStateChanged = ::handleVoiceEngineStateChanged,
                onPartialTranscript = { partial ->
                    if (VoiceSessionVisibility.isVisible()) {
                        VoiceSessionSignals.publish(
                            applicationContext,
                            status = "Listening…",
                            detail = visibleVoiceSessionDetail(
                                invocationSource = invocationSource,
                                deviceLocked = keyguardManager.isDeviceLocked,
                                unlockedDetail = partial.ifBlank { "Speak now." },
                                lockedDetail = "Voice input is hidden while the phone is locked.",
                            ),
                        )
                    }
                },
            )
        } finally {
            runtimeHealthJob?.cancel()
            runtimeHealthJob = null
            // Capture focus is only needed while the microphone turn is live. Holding exclusive
            // focus through local inference would unnecessarily block calls and other assistants.
            audioFocus.abandon()
        }
        requestedRuntimePauseReason?.let { reason ->
            requestedRuntimePauseReason = null
            scheduleRuntimePause(reason)
            return
        }
        if (transcript.isNullOrBlank()) {
            handleEmptyTranscript()
            return
        }
        val effectiveSource = if (keyguardManager.isDeviceLocked) {
            VoiceInvocationSource.LOCKED_ASSISTANT
        } else {
            invocationSource
        }
        handleTranscript(transcript, effectiveSource)
    }

    private fun handleWakeWord(showSession: Boolean) {
        if (!audioFocus.requestCapture()) {
            settingsStore.setLastError("Audio focus is unavailable. Pause calls or other voice apps and try again.")
            requestedRuntimePauseReason = VoiceRuntimePauseReason.OTHER_MICROPHONE
            engine?.requestFinish(VoiceCaptureStopDisposition.DISCARD)
            return
        }
        if (showSession) {
            val sessionId = VoiceSessionSignals.newSessionId()
            offscreenRuntimeClient.cancelPendingInteraction()
            VoiceSessionSignals.setCurrentSessionId(sessionId)
            PocketAgentVoiceInteractionService.showWakeSession(applicationContext, sessionId)
        }
        updateNotification("Listening for your command…")
        settingsStore.updateServiceState(VoiceServiceState.CAPTURING)
        VoiceSessionSignals.publish(
            applicationContext,
            status = "Listening…",
            detail = "Speak now.",
        )
    }

    private fun handleVoiceEngineStateChanged(state: VoiceServiceState) {
        settingsStore.updateServiceState(state)
        stableListeningJob?.cancel()
        stableListeningJob = null
        when (state) {
            VoiceServiceState.LISTENING -> {
                updateNotification("Offas is listening")
                stableListeningJob = scope.launch {
                    delay(VOICE_STABLE_LISTENING_RESET_MS)
                    retryPolicy.reset()
                }
            }
            VoiceServiceState.CAPTURING -> updateNotification("Listening for your command…")
            VoiceServiceState.TRANSCRIBING -> updateNotification("Transcribing…")
            VoiceServiceState.PROCESSING -> updateNotification("Processing command…")
            VoiceServiceState.ERROR -> updateNotification(settingsStore.state().lastError ?: "Voice error")
            else -> Unit
        }
    }

    private fun handleEmptyTranscript() {
        offscreenRuntimeClient.cancelPendingInteraction()
        val nextState = stateAfterEmptyTranscript()
        settingsStore.updateServiceState(nextState)
        updateNotification("Offas is listening")
        if (shouldContinueVoiceService(nextState, settingsStore.state().enabled)) {
            startServiceLoop(
                directCapture = false,
                invocationSource = VoiceInvocationSource.WAKE_WORD,
            )
        } else {
            VoiceSessionSignals.publish(
                applicationContext,
                status = "No speech heard",
                detail = "Try again when you're ready.",
                final = true,
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stateAfterEmptyTranscript(): VoiceServiceState {
        return if (settingsStore.state().enabled) VoiceServiceState.LISTENING else VoiceServiceState.DISABLED
    }

    private suspend fun handleTranscript(
        transcript: String,
        invocationSource: VoiceInvocationSource,
    ) {
        if (isHandsFreeStopCommand(transcript)) {
            android.util.Log.i(VOICE_RUNTIME_LOG_TAG, "VOICE_COMMAND_MATCH|command=stop_listening")
            settingsStore.setEnabled(false)
            offscreenRuntimeClient.cancelPendingInteraction()
            val response = "Hands-free Offas is off."
            updateNotification(response)
            VoiceSessionSignals.publish(
                applicationContext,
                status = "Hands-free off",
                detail = response,
                final = true,
            )
            speakAndAwait(response)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        settingsStore.updateServiceState(VoiceServiceState.PROCESSING)
        updateNotification("Processing your request…")
        VoiceSessionSignals.publish(
            applicationContext,
            status = "Thinking…",
            detail = visibleVoiceSessionDetail(
                invocationSource = invocationSource,
                deviceLocked = keyguardManager.isDeviceLocked,
                unlockedDetail = transcript,
                lockedDetail = "Voice input is hidden while the phone is locked.",
            ),
        )
        val outcome = withContext(Dispatchers.IO) {
            val turnResult = offscreenRuntimeClient.runVoiceTurn(
                transcript = transcript,
                systemPrompt = VoiceActionCatalog.buildSystemPrompt(),
                invocationSource = invocationSource,
                voiceSessionId = VoiceSessionSignals.currentSessionId(),
            )
            turnResult to resolveOffasTranscriptOutcome(
                    toolOutputs = turnResult.toolOutputs,
                    assistantText = turnResult.assistantText,
                    voiceActivationEnabled = settingsStore.state().enabled,
                )
        }
        val (turnResult, resolvedOutcome) = outcome
        retryPolicy.reset()
        android.util.Log.i(
            VOICE_RUNTIME_LOG_TAG,
            "VOICE_TURN_RESULT|source=${invocationSource.name}|" +
                "action_outputs=${turnResult.toolOutputs.size}|follow_up=${turnResult.requiresFollowUpCapture}",
        )
        settingsStore.setLastError(null)
        updateNotification(resolvedOutcome.spokenResponse)
        VoiceSessionSignals.publish(
            applicationContext,
            status = if (turnResult.requiresFollowUpCapture) "Confirmation needed" else "Speaking…",
            detail = visibleVoiceSessionDetail(
                invocationSource = invocationSource,
                deviceLocked = keyguardManager.isDeviceLocked,
                unlockedDetail = resolvedOutcome.spokenResponse,
                lockedDetail = if (turnResult.requiresFollowUpCapture) {
                    "A phone action is waiting for spoken confirmation."
                } else {
                    "The response is being spoken aloud."
                },
            ),
        )
        val previewSpoken = speakAndAwait(resolvedOutcome.spokenResponse)
        if (!previewSpoken) {
            offscreenRuntimeClient.cancelPendingInteraction()
            val pauseReason = requestedRuntimePauseReason
            when (voiceSpeechFailureRecovery(pauseReason)) {
                VoiceSpeechFailureRecovery.PAUSE -> {
                    requestedRuntimePauseReason = null
                    scheduleRuntimePause(checkNotNull(pauseReason))
                }
                VoiceSpeechFailureRecovery.DISABLE -> handleSpeechOutputFailure(invocationSource)
            }
            return
        }
        if (turnResult.requiresFollowUpCapture) {
            offscreenRuntimeClient.startConfirmationWindow()
            settingsStore.updateServiceState(VoiceServiceState.CAPTURING)
            startServiceLoop(
                directCapture = true,
                invocationSource = invocationSource,
                sessionId = VoiceSessionSignals.currentSessionId(),
            )
            return
        }
        settingsStore.updateServiceState(resolvedOutcome.nextServiceState)
        VoiceSessionSignals.publish(
            applicationContext,
            status = "Done",
            detail = visibleVoiceSessionDetail(
                invocationSource = invocationSource,
                deviceLocked = keyguardManager.isDeviceLocked,
                unlockedDetail = resolvedOutcome.spokenResponse,
                lockedDetail = "The response was spoken aloud.",
            ),
            final = true,
        )
        if (shouldContinueVoiceService(resolvedOutcome.nextServiceState, settingsStore.state().enabled)) {
            delay(VOICE_SELF_ECHO_COOLDOWN_MS)
            startServiceLoop(
                directCapture = false,
                invocationSource = VoiceInvocationSource.WAKE_WORD,
            )
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun handleSpeechOutputFailure(invocationSource: VoiceInvocationSource) {
        val message =
            "Hands-free stopped because Offas could not speak its response. " +
                "Check offline speech output and retry."
        settingsStore.disableWithError(message)
        updateNotification("Speech output needs attention")
        VoiceSessionSignals.publish(
            applicationContext,
            status = "Speech output unavailable",
            detail = message,
            final = true,
        )
        android.util.Log.w(
            VOICE_RUNTIME_LOG_TAG,
            "VOICE_OUTPUT_FAILED|source=${invocationSource.name}",
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopServiceLoop() {
        serviceLoopGeneration += 1
        val currentState = settingsStore.state()
        val nextState = when {
            currentState.voiceServiceState == VoiceServiceState.ERROR -> VoiceServiceState.ERROR
            currentState.enabled -> VoiceServiceState.PAUSED
            else -> VoiceServiceState.DISABLED
        }
        settingsStore.updateServiceState(nextState, currentState.lastError)
        offscreenRuntimeClient.cancelPendingInteraction()
        val previousJob = serviceJob
        serviceJob = null
        engine?.requestFinish(VoiceCaptureStopDisposition.DISCARD)
        previousJob?.cancel()
        stableListeningJob?.cancel()
        stableListeningJob = null
        runtimeHealthJob?.cancel()
        runtimeHealthJob = null
        requestedRuntimePauseReason = null
        cancelPendingSpeech()
        val engineToRelease = engine
        engine = null
        if (previousJob != null && !previousJob.isCompleted) {
            previousJob.invokeOnCompletion {
                engineToRelease?.release()
            }
        } else {
            engineToRelease?.release()
        }
        audioFocus.abandon()
        OffasRuntime.pauseRecovery(applicationContext)
    }

    private suspend fun speakAndAwait(text: String): Boolean {
        val activeSpeaker = speaker
        if (!ttsReady || activeSpeaker == null) {
            return false
        }
        val chunks = speechChunks(text)
        if (chunks.isEmpty()) {
            return false
        }
        currentRuntimePauseReason()?.let { reason ->
            requestedRuntimePauseReason = reason
            return false
        }
        if (!audioFocus.requestPlayback()) {
            requestedRuntimePauseReason = VoiceRuntimePauseReason.OTHER_MICROPHONE
            return false
        }

        cancelPendingSpeech()
        val generation = ++speechGeneration
        val utteranceIds = chunks.indices.map { index -> "offas-response-$generation-$index" }
        val pending = PendingVoiceSpeech(
            utteranceIds = utteranceIds.toSet(),
            finalUtteranceId = utteranceIds.last(),
            completion = CompletableDeferred(),
        )
        pendingSpeech = pending
        for ((index, chunk) in chunks.withIndex()) {
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (activeSpeaker.speak(chunk, queueMode, null, utteranceIds[index]) == TextToSpeech.ERROR) {
                activeSpeaker.stop()
                pending.completion.complete(false)
                break
            }
        }

        val completed = try {
            withTimeoutOrNull(speechTimeoutMillis(text)) {
                pending.completion.await()
            } ?: false
        } finally {
            if (pendingSpeech === pending) {
                pendingSpeech = null
            }
        }
        if (!completed) {
            activeSpeaker.stop()
        }
        audioFocus.abandon()
        return completed
    }

    private fun cancelPendingSpeech() {
        pendingSpeech?.completion?.complete(false)
        pendingSpeech = null
        speaker?.stop()
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun handleBlockingPrerequisiteFailure(error: String, notification: String) {
        settingsStore.disableWithError(error)
        updateNotification(notification)
        VoiceSessionSignals.publish(
            applicationContext,
            status = "Voice setup needed",
            detail = error,
            final = true,
        )
        OffasRuntime.pauseRecovery(applicationContext)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun handleUnexpectedFailure(error: Throwable) {
        cancelPendingSpeech()
        offscreenRuntimeClient.cancelPendingInteraction()
        val message = error.message?.takeIf { it.isNotBlank() } ?: "Unexpected voice runtime failure."
        VoiceSessionSignals.publish(
            applicationContext,
            status = "Voice interrupted",
            detail = message,
            final = true,
        )
        val engineToRelease = engine
        engine = null
        engineToRelease?.release()
        if (error is VoiceCaptureSilencedException && settingsStore.state().enabled) {
            scheduleRuntimePause(VoiceRuntimePauseReason.OTHER_MICROPHONE)
            return
        }
        val retryDelayMs = retryPolicy.nextDelayMillis(SystemClock.elapsedRealtime())
        if (settingsStore.state().enabled && retryDelayMs != null) {
            settingsStore.updateServiceState(VoiceServiceState.STARTING, message)
            updateNotification("Offas is recovering…")
            scope.launch {
                delay(retryDelayMs)
                if (settingsStore.state().enabled) {
                    startServiceLoop(
                        directCapture = false,
                        invocationSource = VoiceInvocationSource.WAKE_WORD,
                    )
                }
            }
        } else {
            settingsStore.disableWithError("Hands-free stopped after repeated failures: $message")
            updateNotification("Hands-free needs attention")
            VoiceSessionSignals.publish(
                applicationContext,
                status = "Hands-free stopped",
                detail = message,
                final = true,
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun currentRuntimePauseReason(): VoiceRuntimePauseReason? {
        if (!areVoiceNotificationsAvailable(applicationContext)) {
            return VoiceRuntimePauseReason.NOTIFICATION_CONTROLS_HIDDEN
        }
        val batteryPercent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager.currentThermalStatus
        } else {
            PowerManager.THERMAL_STATUS_NONE
        }
        return voiceRuntimePauseReason(
            audioMode = audioManager.mode,
            batteryPercent = batteryPercent,
            charging = batteryManager.isCharging,
            thermalStatus = thermalStatus,
        )
    }

    private fun startRuntimeHealthMonitor() {
        runtimeHealthJob?.cancel()
        runtimeHealthJob = scope.launch {
            while (settingsStore.state().enabled) {
                delay(VOICE_RUNTIME_HEALTH_INTERVAL_MS)
                val reason = currentRuntimePauseReason() ?: continue
                requestedRuntimePauseReason = reason
                engine?.requestFinish(VoiceCaptureStopDisposition.DISCARD)
                return@launch
            }
        }
    }

    private fun scheduleRuntimePause(reason: VoiceRuntimePauseReason) {
        offscreenRuntimeClient.cancelPendingInteraction()
        settingsStore.updateServiceState(VoiceServiceState.PAUSED, reason.message)
        updateNotification(reason.message)
        VoiceSessionSignals.publish(
            applicationContext,
            status = "Voice paused",
            detail = reason.message,
            final = true,
        )
        scope.launch {
            delay(reason.retryDelayMs)
            if (settingsStore.state().enabled) {
                startServiceLoop(
                    directCapture = false,
                    invocationSource = VoiceInvocationSource.WAKE_WORD,
                )
            }
        }
    }

    private fun buildNotification(message: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, OffasListenerService::class.java).setAction(OffasRuntime.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val talkIntent = PendingIntent.getActivity(
            this,
            3,
            Intent().setClassName(packageName, INTERNAL_ASSIST_ACTIVITY_CLASS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_VOICE_STATUS)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.ui_voice_activation_title))
            .setContentText(message)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_btn_speak_now, "Talk now", talkIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop listening", stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_VOICE_STATUS) != null) {
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_VOICE_STATUS,
                "Offas Voice",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Status updates for Offas background voice listening"
            },
        )
    }

    companion object {
        const val CHANNEL_VOICE_STATUS = "voice_status"
        private const val NOTIFICATION_ID = 4401
        private const val SPEECH_TIMEOUT_BASE_MS = 30_000L
        private const val SPEECH_TIMEOUT_PER_CHARACTER_MS = 100L
        private const val SPEECH_TIMEOUT_MAX_MS = 600_000L
        private const val TTS_INITIALIZATION_TIMEOUT_MS = 10_000L
        private const val TTS_ENGINE_INITIALIZATION_TIMEOUT_MS = 3_000L
        private const val VOICE_RUNTIME_HEALTH_INTERVAL_MS = 15_000L

        private fun speechTimeoutMillis(text: String): Long {
            return (
                SPEECH_TIMEOUT_BASE_MS +
                    text.length.toLong() * SPEECH_TIMEOUT_PER_CHARACTER_MS
                ).coerceAtMost(SPEECH_TIMEOUT_MAX_MS)
        }
    }
}

internal fun removeLeadingWakePhrase(transcript: String, wakePhrase: String): String {
    if (wakePhrase.isBlank()) return transcript.trim()
    val alternatives = buildList {
        add(Regex.escape(wakePhrase.trim()))
        if (wakePhrase.trim().equals("Offas", ignoreCase = true)) {
            add("off\\s+us")
            add("of\\s+us")
            add("office")
            add("offers")
        }
    }
    val leadingWakePhrase = Regex(
        pattern = "^\\s*(?:${alternatives.joinToString("|")})(?:[\\s,.:;!?-]+|$)",
        option = RegexOption.IGNORE_CASE,
    )
    return transcript.replaceFirst(leadingWakePhrase, "").trim()
}

internal fun shouldClearPendingInteractionForSessionStart(
    currentSessionId: Long,
    nextSessionId: Long,
): Boolean {
    return nextSessionId != VoiceSessionSignals.NO_SESSION_ID && nextSessionId != currentSessionId
}

internal fun visibleVoiceSessionDetail(
    invocationSource: VoiceInvocationSource,
    deviceLocked: Boolean,
    unlockedDetail: String,
    lockedDetail: String,
): String {
    return if (deviceLocked || invocationSource == VoiceInvocationSource.LOCKED_ASSISTANT) {
        lockedDetail
    } else {
        unlockedDetail
    }
}

internal fun isHandsFreeStopCommand(transcript: String): Boolean {
    val normalized = transcript
        .trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("[,.!?]+$"), "")
        .trim()
    return normalized.matches(
        Regex(
            "(?:please\\s+)?(?:stop\\s+listening|" +
                "(?:turn\\s+off|disable)\\s+(?:hands[- ]?free(?:\\s+offas)?|always[- ]?on(?:\\s+listening)?|offas))" +
                "(?:\\s+please)?",
        ),
    )
}

internal object VoiceAudioCaptureLease {
    private val held = AtomicBoolean(false)

    fun tryAcquire(): Boolean = held.compareAndSet(false, true)

    fun isHeld(): Boolean = held.get()

    fun release() {
        held.set(false)
    }
}

internal object VoiceCaptureReadiness {
    private val ready = AtomicBoolean(false)

    fun isReady(): Boolean = ready.get()

    fun setReady(value: Boolean) {
        ready.set(value)
    }
}

internal enum class WakeEnergyGateDecision {
    SKIP,
    OPEN_WITH_PREROLL,
    PROCESS,
    PROCESS_AND_CLOSE,
}

/**
 * Keeps the native keyword decoder asleep during steady ambient silence while preserving
 * pre-roll and trailing context around speech-like audio. The microphone remains open, so
 * Android's visible privacy indicator and user Stop controls continue to reflect reality.
 */
internal class WakeEnergyGate(
    private val rmsThreshold: Float,
    private val trailingSilenceMs: Long,
) {
    private var open = false
    private var lastVoicedAtMs = 0L

    fun onChunk(rms: Float, nowMs: Long): WakeEnergyGateDecision {
        val voiced = rms >= rmsThreshold
        if (!open) {
            if (!voiced) return WakeEnergyGateDecision.SKIP
            open = true
            lastVoicedAtMs = nowMs
            return WakeEnergyGateDecision.OPEN_WITH_PREROLL
        }
        if (voiced) {
            lastVoicedAtMs = nowMs
            return WakeEnergyGateDecision.PROCESS
        }
        return if (nowMs - lastVoicedAtMs >= trailingSilenceMs) {
            open = false
            WakeEnergyGateDecision.PROCESS_AND_CLOSE
        } else {
            WakeEnergyGateDecision.PROCESS
        }
    }

    fun reset() {
        open = false
        lastVoicedAtMs = 0L
    }
}

internal class VoiceAudioFocusCoordinator(
    context: Context,
    private val onFocusLost: () -> Unit,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var activeRequest: AudioFocusRequest? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        ) {
            onFocusLost()
        }
    }

    fun requestCapture(): Boolean {
        return request(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
    }

    fun requestPlayback(): Boolean {
        return request(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
    }

    fun abandon() {
        activeRequest?.let(audioManager::abandonAudioFocusRequest)
        activeRequest = null
    }

    private fun request(gain: Int): Boolean {
        abandon()
        val request = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener)
            .setWillPauseWhenDucked(true)
            .build()
        return if (audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            activeRequest = request
            true
        } else {
            false
        }
    }
}

internal fun hasDedicatedOffasKeyword(keywordsContent: String): Boolean {
    val keywordLines = keywordsContent.lineSequence()
        .map(String::trim)
        .filter { line -> line.isNotEmpty() && !line.startsWith('#') }
        .map { line -> line.replace(Regex("\\s+"), " ") }
        .toList()
    return keywordLines == listOf(DEDICATED_OFFAS_KEYWORD_TOKENS)
}

internal fun canUseDedicatedOffasKeyword(wakePhrase: String): Boolean {
    return wakePhrase.trim().equals(DEFAULT_WAKE_PHRASE, ignoreCase = true)
}

internal fun audioRecordFailureMessage(readResult: Int): String {
    return when (readResult) {
        AudioRecord.ERROR_DEAD_OBJECT -> "The microphone disconnected during voice capture."
        AudioRecord.ERROR_INVALID_OPERATION -> "The microphone entered an invalid recording state."
        AudioRecord.ERROR_BAD_VALUE -> "Android rejected the voice audio buffer."
        else -> "Voice audio capture failed with code $readResult."
    }
}

internal class VoiceRuntimeRetryPolicy(
    private val maxFailures: Int = 5,
    private val failureWindowMs: Long = 120_000L,
) {
    private var failureCount = 0
    private var firstFailureAtMs = 0L

    fun nextDelayMillis(nowMs: Long): Long? {
        if (firstFailureAtMs == 0L || nowMs - firstFailureAtMs > failureWindowMs) {
            firstFailureAtMs = nowMs
            failureCount = 0
        }
        failureCount += 1
        if (failureCount > maxFailures) return null
        return RETRY_DELAYS_MS[(failureCount - 1).coerceAtMost(RETRY_DELAYS_MS.lastIndex)]
    }

    fun reset() {
        failureCount = 0
        firstFailureAtMs = 0L
    }

    private companion object {
        val RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)
    }
}

internal enum class VoiceRuntimePauseReason(
    val message: String,
    val retryDelayMs: Long,
) {
    PHONE_CALL("Paused during a call", 5_000L),
    OTHER_MICROPHONE("Paused while another app uses the microphone", 5_000L),
    NOTIFICATION_CONTROLS_HIDDEN("Paused until Offas notifications are visible", 30_000L),
    CRITICAL_BATTERY("Paused below 5% battery", 60_000L),
    THERMAL("Paused while the phone cools down", 30_000L),
}

internal enum class VoiceSpeechFailureRecovery {
    PAUSE,
    DISABLE,
}

internal fun voiceSpeechFailureRecovery(
    pauseReason: VoiceRuntimePauseReason?,
): VoiceSpeechFailureRecovery {
    return if (pauseReason == null) {
        VoiceSpeechFailureRecovery.DISABLE
    } else {
        VoiceSpeechFailureRecovery.PAUSE
    }
}

internal fun voiceRuntimePauseReason(
    audioMode: Int,
    batteryPercent: Int,
    charging: Boolean,
    thermalStatus: Int,
): VoiceRuntimePauseReason? {
    return when {
        audioMode == AudioManager.MODE_IN_CALL ||
            audioMode == AudioManager.MODE_IN_COMMUNICATION ||
            audioMode == AudioManager.MODE_RINGTONE -> VoiceRuntimePauseReason.PHONE_CALL
        thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> VoiceRuntimePauseReason.THERMAL
        batteryPercent in 0..5 && !charging -> VoiceRuntimePauseReason.CRITICAL_BATTERY
        else -> null
    }
}

private class VoiceCaptureSilencedException : RuntimeException(
    "Android temporarily silenced PocketAgent's microphone.",
)

private const val SAMPLE_RATE = 16_000
private const val SHORT_BUFFER_SIZE = 2048
private const val VOICE_MODEL_STARTUP_BUFFER_SECONDS = 4
private const val RING_BUFFER_CHUNKS = 6
private const val SPEECH_RMS_THRESHOLD = 0.010f
private const val MAX_COMMAND_DURATION_MS = 20_000L
private const val VOICE_STABLE_LISTENING_RESET_MS = 300_000L
private const val VOICE_SELF_ECHO_COOLDOWN_MS = 750L
private const val KWS_MAX_ACTIVE_PATHS = 4
private const val KWS_KEYWORDS_SCORE = 3.0f
private const val KWS_KEYWORDS_THRESHOLD = 0.1f
private const val KWS_TRAILING_BLANKS = 2
private const val KWS_GATE_RMS_THRESHOLD = 0.003f
private const val KWS_GATE_TRAILING_MS = 1_200L
private const val DEDICATED_OFFAS_KEYWORD_TOKENS = "▁OF F ▁US @OFFAS"
private const val VOICE_RUNTIME_LOG_TAG = "PocketAgentVoice"

internal fun ShortArray.toFloatChunk(): FloatArray {
    val result = FloatArray(size)
    for (index in indices) {
        result[index] = this[index] / 32768.0f
    }
    return result
}

internal fun FloatArray.rms(): Float {
    if (isEmpty()) {
        return 0f
    }
    var sum = 0.0
    for (value in this) {
        sum += value * value
    }
    return kotlin.math.sqrt(sum / size).toFloat()
}
