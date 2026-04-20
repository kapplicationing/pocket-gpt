package com.pocketagent.android.voice

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.pocketagent.android.AndroidMvpContainer
import com.pocketagent.android.MainActivity
import com.pocketagent.android.R
import com.pocketagent.android.runtime.DefaultProvisioningGateway
import com.pocketagent.android.runtime.RuntimeBootstrapper
import com.pocketagent.android.ui.controllers.AndroidTelemetryDeviceStateProvider
import com.pocketagent.runtime.SamplingOverrides
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class VoiceModelStatus(
    val ready: Boolean,
    val missingPaths: List<String>,
)

internal object VoiceModelCatalog {
    private const val ROOT_DIR = "offas-voice-models"
    private const val ASR_DIR = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"
    private const val KWS_DIR = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01-mobile"

    fun root(context: Context): File = File(context.filesDir, ROOT_DIR)

    fun status(context: Context): VoiceModelStatus {
        val missing = mutableListOf<String>()
        requiredAsrFiles(context).filterNot { it.exists() }.forEach { missing += it.absolutePath }
        return VoiceModelStatus(
            ready = missing.isEmpty(),
            missingPaths = missing,
        )
    }

    fun hasDedicatedWakeWordModel(context: Context): Boolean {
        return kwsFiles(context).all { it.exists() } && keywordsFile(context).exists()
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
                    encoder = File(modelDir, "encoder-epoch-12-avg-2-chunk-16-left-64.onnx").absolutePath,
                    decoder = File(modelDir, "decoder-epoch-12-avg-2-chunk-16-left-64.onnx").absolutePath,
                    joiner = File(modelDir, "joiner-epoch-12-avg-2-chunk-16-left-64.onnx").absolutePath,
                ),
                tokens = File(modelDir, "tokens.txt").absolutePath,
                modelType = "zipformer2",
                provider = "cpu",
                numThreads = 1,
            ),
            keywordsFile = keywordsFile(context).absolutePath,
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
        return listOf(
            File(dir, "encoder-epoch-12-avg-2-chunk-16-left-64.onnx"),
            File(dir, "decoder-epoch-12-avg-2-chunk-16-left-64.onnx"),
            File(dir, "joiner-epoch-12-avg-2-chunk-16-left-64.onnx"),
            File(dir, "tokens.txt"),
        )
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
    ): String?

    fun release()
}

internal class SherpaOnnxOffasVoiceEngine(
    private val appContext: Context,
) : OffasVoiceEngine {
    private val recognizer by lazy { VoiceModelCatalog.recognizer(appContext) }
    private val keywordSpotter by lazy { VoiceModelCatalog.keywordSpotter(appContext) }

    override suspend fun awaitWakeAndCommand(
        wakePhrase: String,
        silenceTimeoutSeconds: Int,
        directCapture: Boolean,
        onWakeWord: () -> Unit,
        onStateChanged: (VoiceServiceState) -> Unit,
    ): String? = withContext(Dispatchers.Default) {
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
            minBufferBytes.coerceAtLeast(SHORT_BUFFER_SIZE * 4),
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            throw IllegalStateException("AudioRecord initialization failed.")
        }
        val samples = ShortArray(SHORT_BUFFER_SIZE)
        val ringBuffer = ArrayDeque<FloatArray>()
        val detectWakeWithKws = keywordSpotter != null && !directCapture
        val wakeSpotter = keywordSpotter
        val wakeStream = if (detectWakeWithKws) wakeSpotter?.createStream() else recognizer.createStream()
        var commandStream = if (directCapture) recognizer.createStream() else null
        var capturingCommand = directCapture
        var lastVoiceAtMs = System.currentTimeMillis()
        var lastTranscript = ""
        audioRecord.startRecording()
        try {
            if (directCapture) {
                onWakeWord()
                onStateChanged(VoiceServiceState.CAPTURING)
            } else {
                onStateChanged(VoiceServiceState.LISTENING)
            }
            while (isActive) {
                val read = audioRecord.read(samples, 0, samples.size)
                if (read <= 0) {
                    continue
                }
                val chunk = samples.copyOf(read).toFloatChunk()
                ringBuffer.addLast(chunk)
                while (ringBuffer.size > RING_BUFFER_CHUNKS) {
                    ringBuffer.removeFirst()
                }
                if (!capturingCommand) {
                    val wakeDetected = if (detectWakeWithKws && wakeSpotter != null && wakeStream != null) {
                        wakeStream.acceptWaveform(chunk, SAMPLE_RATE)
                        var hit = false
                        while (wakeSpotter.isReady(wakeStream)) {
                            wakeSpotter.decode(wakeStream)
                            val result = wakeSpotter.getResult(wakeStream)
                            if (result.keyword.isNotBlank()) {
                                hit = true
                            }
                        }
                        hit
                    } else {
                        val recognizerStream = wakeStream ?: continue
                        recognizerStream.acceptWaveform(chunk, SAMPLE_RATE)
                        while (recognizer.isReady(recognizerStream)) {
                            recognizer.decode(recognizerStream)
                        }
                        val partial = recognizer.getResult(recognizerStream).text.trim()
                        partial.lowercase(Locale.US).contains(wakePhrase.lowercase(Locale.US))
                    }
                    if (!wakeDetected) {
                        continue
                    }
                    capturingCommand = true
                    onWakeWord()
                    onStateChanged(VoiceServiceState.CAPTURING)
                    commandStream = recognizer.createStream()
                    ringBuffer.forEach { buffered ->
                        commandStream?.acceptWaveform(buffered, SAMPLE_RATE)
                    }
                    lastVoiceAtMs = System.currentTimeMillis()
                    lastTranscript = ""
                    continue
                }

                val activeCommandStream = commandStream ?: continue
                activeCommandStream.acceptWaveform(chunk, SAMPLE_RATE)
                while (recognizer.isReady(activeCommandStream)) {
                    recognizer.decode(activeCommandStream)
                }
                val partial = recognizer.getResult(activeCommandStream).text.trim()
                val rms = chunk.rms()
                if (rms >= SPEECH_RMS_THRESHOLD || partial.length > lastTranscript.length) {
                    lastVoiceAtMs = System.currentTimeMillis()
                }
                lastTranscript = partial
                onStateChanged(VoiceServiceState.TRANSCRIBING)
                val silentForMs = System.currentTimeMillis() - lastVoiceAtMs
                if (silentForMs < silenceTimeoutSeconds * 1000L) {
                    continue
                }
                activeCommandStream.inputFinished()
                while (recognizer.isReady(activeCommandStream)) {
                    recognizer.decode(activeCommandStream)
                }
                val finalText = recognizer.getResult(activeCommandStream).text
                    .replace(wakePhrase, "", ignoreCase = true)
                    .trim()
                activeCommandStream.release()
                if (wakeStream != null && !detectWakeWithKws) {
                    recognizer.reset(wakeStream)
                } else if (wakeSpotter != null && wakeStream != null) {
                    wakeSpotter?.reset(wakeStream)
                }
                return@withContext finalText.takeIf { it.isNotBlank() }
            }
            null
        } finally {
            runCatching { audioRecord.stop() }
            audioRecord.release()
            commandStream?.release()
            wakeStream?.release()
        }
    }

    override fun release() {
        runCatching { keywordSpotter?.release() }
        runCatching { recognizer.release() }
    }
}

internal object OffasRuntime {
    const val ACTION_START = "com.pocketagent.android.voice.START"
    const val ACTION_STOP = "com.pocketagent.android.voice.STOP"
    const val ACTION_CAPTURE_ONCE = "com.pocketagent.android.voice.CAPTURE_ONCE"
    const val ACTION_WATCHDOG = "com.pocketagent.android.voice.WATCHDOG"

    private const val UNIQUE_WORK_NAME = "offas-listener-watchdog"

    fun start(context: Context) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, OffasListenerService::class.java).setAction(ACTION_START),
        )
        enqueueWatchdog(context)
    }

    fun stop(context: Context) {
        context.startService(Intent(context, OffasListenerService::class.java).setAction(ACTION_STOP))
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        cancelRestartAlarm(context)
    }

    fun captureOnce(context: Context) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, OffasListenerService::class.java).setAction(ACTION_CAPTURE_ONCE),
        )
        enqueueWatchdog(context)
    }

    fun enqueueWatchdog(context: Context) {
        val request = PeriodicWorkRequestBuilder<OffasWatchdogWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun scheduleRestartAlarm(context: Context) {
        val intent = Intent(context, OffasWatchdogReceiver::class.java).setAction(ACTION_WATCHDOG)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            WATCHDOG_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAtMs = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(20)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
        }
    }

    fun cancelRestartAlarm(context: Context) {
        val intent = Intent(context, OffasWatchdogReceiver::class.java).setAction(ACTION_WATCHDOG)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            WATCHDOG_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent)
    }

    private const val WATCHDOG_REQUEST_CODE = 4037
}

class OffasWatchdogWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {
    override fun doWork(): Result {
        val settings = VoiceActivationSettingsStore(applicationContext).state()
        if (!settings.enabled) {
            return Result.success()
        }
        OffasRuntime.start(applicationContext)
        return Result.success()
    }
}

class OffasWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != OffasRuntime.ACTION_WATCHDOG) {
            return
        }
        val settings = VoiceActivationSettingsStore(context.applicationContext).state()
        if (!settings.enabled) {
            return
        }
        OffasRuntime.start(context.applicationContext)
    }
}

class OffasAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val label = intent?.getStringExtra(AndroidLocalToolRuntime.EXTRA_LABEL).orEmpty().ifBlank { "Offas reminder" }
        val action = intent?.action ?: return
        val channelId = OffasListenerService.CHANNEL_VOICE_STATUS
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(manager)
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (action == AndroidLocalToolRuntime.ACTION_OFFAS_TIMER) "Offas timer finished" else "Offas alarm")
            .setContentText(label)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager.notify(label.hashCode(), notification)
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getNotificationChannel(OffasListenerService.CHANNEL_VOICE_STATUS) == null
        ) {
            manager.createNotificationChannel(
                NotificationChannel(
                    OffasListenerService.CHANNEL_VOICE_STATUS,
                    "Offas Voice",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
    }
}

class OffasListenerService : Service(), TextToSpeech.OnInitListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settingsStore by lazy { VoiceActivationSettingsStore(applicationContext) }
    private var serviceJob: Job? = null
    private var speaker: TextToSpeech? = null
    private var ttsReady = false
    private var engine: OffasVoiceEngine? = null

    override fun onCreate() {
        super.onCreate()
        speaker = TextToSpeech(applicationContext, this)
        ensureNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            OffasRuntime.ACTION_STOP -> {
                stopServiceLoop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            OffasRuntime.ACTION_CAPTURE_ONCE -> startServiceLoop(directCapture = true)
            else -> startServiceLoop(directCapture = false)
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        OffasRuntime.scheduleRestartAlarm(applicationContext)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopServiceLoop()
        speaker?.stop()
        speaker?.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            speaker?.language = Locale.US
        }
    }

    private fun startServiceLoop(directCapture: Boolean) {
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            settingsStore.updateServiceState(VoiceServiceState.ERROR, "Microphone permission is missing.")
            startForeground(NOTIFICATION_ID, buildNotification("Microphone permission required"))
            return
        }
        startForeground(NOTIFICATION_ID, buildNotification("Offas is starting"))
        serviceJob?.cancel()
        serviceJob = scope.launch {
            withContext(Dispatchers.IO) {
                RuntimeBootstrapper.installProductionRuntime(applicationContext)
            }
            val modelStatus = VoiceModelCatalog.status(applicationContext)
            if (!modelStatus.ready) {
                settingsStore.updateServiceState(
                    VoiceServiceState.ERROR,
                    "Voice models missing: ${modelStatus.missingPaths.joinToString()}",
                )
                updateNotification("Install Offas voice models to enable background listening.")
                return@launch
            }
            engine = engine ?: SherpaOnnxOffasVoiceEngine(applicationContext)
            OffasRuntime.scheduleRestartAlarm(applicationContext)
            settingsStore.updateServiceState(VoiceServiceState.STARTING)
            val preloadJob = async(Dispatchers.IO) { }
            val transcript = engine?.awaitWakeAndCommand(
                wakePhrase = settingsStore.state().wakePhrase,
                silenceTimeoutSeconds = settingsStore.state().silenceTimeoutSeconds,
                directCapture = directCapture,
                onWakeWord = {
                    updateNotification("Listening for your command…")
                    settingsStore.updateServiceState(VoiceServiceState.CAPTURING)
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            val lastUsed = DefaultProvisioningGateway(applicationContext).loadLastUsedModel()
                            if (!lastUsed.success) {
                                Unit
                            }
                        }
                    }
                },
                onStateChanged = { state ->
                    settingsStore.updateServiceState(state)
                    when (state) {
                        VoiceServiceState.LISTENING -> updateNotification("Offas is listening")
                        VoiceServiceState.CAPTURING -> updateNotification("Listening for your command…")
                        VoiceServiceState.TRANSCRIBING -> updateNotification("Transcribing…")
                        VoiceServiceState.PROCESSING -> updateNotification("Processing command…")
                        VoiceServiceState.ERROR -> updateNotification(settingsStore.state().lastError ?: "Voice error")
                        else -> Unit
                    }
                },
            )
            preloadJob.await()
            if (transcript.isNullOrBlank()) {
                settingsStore.updateServiceState(if (settingsStore.state().enabled) VoiceServiceState.LISTENING else VoiceServiceState.DISABLED)
                updateNotification("Offas is listening")
                if (settingsStore.state().enabled && !directCapture) {
                    startServiceLoop(directCapture = false)
                } else {
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                }
                return@launch
            }
            handleTranscript(transcript, directCapture = directCapture)
        }
    }

    private suspend fun handleTranscript(transcript: String, directCapture: Boolean) {
        settingsStore.updateServiceState(VoiceServiceState.PROCESSING)
        updateNotification("Processing: $transcript")
        val response = withContext(Dispatchers.IO) {
            val container = AndroidMvpContainer(appContext = applicationContext)
            val sessionId = container.createSession()
            val deviceState = AndroidTelemetryDeviceStateProvider(applicationContext).current()
            val systemPrompt = buildVoiceSystemPrompt()
            val assistantResponse = container.sendUserMessage(
                sessionId = sessionId,
                userText = transcript,
                taskType = "short_text",
                deviceState = deviceState,
                maxTokens = 160,
                keepModelLoaded = true,
                requestId = "voice-${System.currentTimeMillis()}",
                samplingOverrides = SamplingOverrides(
                    systemPrompt = systemPrompt,
                    temperature = 0.2f,
                    topP = 0.9f,
                    topK = 30,
                    maxTokens = 160,
                ),
                onToken = {},
            )
            val toolOutputs = assistantResponse.toolCalls.map { toolCall ->
                container.runTool(toolCall.name, toolCall.argumentsJson)
            }
            when {
                toolOutputs.isNotEmpty() -> toolOutputs.joinToString(separator = " ") { it }
                assistantResponse.text.isNotBlank() -> assistantResponse.text
                else -> "Done."
            }
        }
        settingsStore.updateServiceState(if (settingsStore.state().enabled) VoiceServiceState.LISTENING else VoiceServiceState.DISABLED)
        settingsStore.setLastError(null)
        speak(response)
        updateNotification(response)
        if (settingsStore.state().enabled && !directCapture) {
            startServiceLoop(directCapture = false)
        } else {
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private fun stopServiceLoop() {
        settingsStore.updateServiceState(
            if (settingsStore.state().enabled) VoiceServiceState.PAUSED else VoiceServiceState.DISABLED,
            settingsStore.state().lastError,
        )
        serviceJob?.cancel()
        serviceJob = null
        engine?.release()
        engine = null
        OffasRuntime.cancelRestartAlarm(applicationContext)
    }

    private fun speak(text: String) {
        if (!ttsReady) {
            return
        }
        speaker?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "offas-response")
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun buildNotification(message: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_VOICE_STATUS)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.ui_voice_activation_title))
            .setContentText(message)
            .setContentIntent(openIntent)
            .setOngoing(true)
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

    private fun buildVoiceSystemPrompt(): String {
        return """
            You are Offas, a local Android voice assistant.
            The user message is a speech transcript and may omit punctuation.
            Prefer tool calls whenever a request maps to an available device action.
            Supported tools and string-only arguments:
            - alarm_set {"title":"Wake up","time_iso":"2026-04-10T07:00:00"}
            - timer_set {"label":"Tea","duration_seconds":"300"}
            - app_open {"app_name":"Maps"}
            - volume_set {"level_percent":"40"}
            - flashlight_toggle {"enabled":"on"}
            If a tool is required, emit only tool calls.
            If no tool is needed, answer in one concise sentence.
        """.trimIndent()
    }

    companion object {
        const val CHANNEL_VOICE_STATUS = "voice_status"
        private const val NOTIFICATION_ID = 4401
    }
}

private const val SAMPLE_RATE = 16_000
private const val SHORT_BUFFER_SIZE = 2048
private const val RING_BUFFER_CHUNKS = 24
private const val SPEECH_RMS_THRESHOLD = 0.010f

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
