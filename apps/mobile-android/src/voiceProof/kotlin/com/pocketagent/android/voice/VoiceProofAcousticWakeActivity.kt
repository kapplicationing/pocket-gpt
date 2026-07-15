package com.pocketagent.android.voice

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * Release-semantic acoustic probe compiled only into the isolated voiceProof package.
 *
 * An offline TTS engine first renders a repeatable PCM fixture. AudioTrack then routes that exact
 * fixture through the built-in speaker into the real microphone. A pass requires the production
 * listener to persistently disable itself after recognizing the spoken stop command.
 */
class VoiceProofAcousticWakeActivity : ComponentActivity() {
    private data class TtsEngineCandidate(val packageName: String?)

    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val settingsStore by lazy { VoiceActivationSettingsStore.process(applicationContext) }
    private val fixtureFile by lazy { File(cacheDir, "voice-proof-stop-listening.wav") }
    private var speaker: TextToSpeech? = null
    private val pendingTtsEngines = ArrayDeque<TtsEngineCandidate>()
    private var initializationGeneration = 0L
    private var initializationTimeoutJob: Job? = null
    private var originalMediaVolume: Int? = null
    private val closed = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(LOG_TAG, "VOICE_PROOF_ACOUSTIC|phase=initializing")
        pendingTtsEngines.add(TtsEngineCandidate(packageName = null))
        availableTtsEnginePackages(applicationContext)
            .distinct()
            .forEach { packageName -> pendingTtsEngines.add(TtsEngineCandidate(packageName)) }
        initializeNextSpeechEngine()
    }

    private fun initializeNextSpeechEngine() {
        val candidate = pendingTtsEngines.removeFirstOrNull()
        if (candidate == null) {
            finishProbe("offline_english_unavailable")
            return
        }
        val generation = ++initializationGeneration
        val listener = TextToSpeech.OnInitListener { status ->
            lifecycleScope.launch {
                // Some OEM engines invoke this callback before construction assigns `speaker`.
                yield()
                if (generation == initializationGeneration) {
                    handleSpeechInitialization(status)
                }
            }
        }
        speaker = candidate.packageName?.let { packageName ->
            TextToSpeech(applicationContext, listener, packageName)
        } ?: TextToSpeech(applicationContext, listener)
        initializationTimeoutJob?.cancel()
        initializationTimeoutJob = lifecycleScope.launch {
            delay(TTS_INITIALIZATION_TIMEOUT_MS)
            if (generation == initializationGeneration) {
                tryNextSpeechEngine()
            }
        }
    }

    private fun handleSpeechInitialization(status: Int) {
        initializationTimeoutJob?.cancel()
        initializationTimeoutJob = null
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
                    if (utteranceId != UTTERANCE_ID) return
                    lifecycleScope.launch { runAcousticProbe() }
                }

                @Deprecated("Called on older Android TTS engines")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == UTTERANCE_ID) finishProbe("tts_synthesis_failed")
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId == UTTERANCE_ID) finishProbe("tts_synthesis_failed_$errorCode")
                }
            },
        )
        lifecycleScope.launch {
            if (!waitForListenerReady()) {
                finishProbe("listener_not_ready")
                return@launch
            }
            fixtureFile.delete()
            Log.i(LOG_TAG, "VOICE_PROOF_ACOUSTIC|phase=synthesizing")
            val result = activeSpeaker.synthesizeToFile(
                SPOKEN_PROBE,
                Bundle(),
                fixtureFile,
                UTTERANCE_ID,
            )
            if (result == TextToSpeech.ERROR) {
                finishProbe("tts_synthesis_enqueue_failed")
            }
        }
    }

    override fun onDestroy() {
        initializationGeneration += 1
        initializationTimeoutJob?.cancel()
        initializationTimeoutJob = null
        restoreMediaVolume()
        closeSpeaker()
        fixtureFile.delete()
        super.onDestroy()
    }

    private suspend fun waitForListenerReady(): Boolean {
        repeat(LISTENER_READY_POLLS) {
            val state = settingsStore.state()
            if (state.enabled &&
                state.voiceServiceState == VoiceServiceState.LISTENING &&
                VoiceCaptureReadiness.isReady()
            ) {
                Log.i(LOG_TAG, "VOICE_PROOF_ACOUSTIC|phase=listener_ready")
                return true
            }
            delay(STATE_POLL_MS)
        }
        return false
    }

    private suspend fun runAcousticProbe() {
        val result = runCatching {
            val wave = withContext(Dispatchers.IO) { readPcm16Wave(fixtureFile) }
            val fixtureValid = withContext(Dispatchers.Default) { validateFixture(wave) }
            check(fixtureValid) { "The synthesized fixture did not pass the wake model." }
            originalMediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val targetVolume = (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * TEST_VOLUME_FRACTION)
                .roundToInt()
                .coerceAtLeast(1)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
            withContext(Dispatchers.Default) { playThroughBuiltInSpeaker(wave) }
            waitForDurableStop()
        }.getOrElse { error ->
            Log.e(LOG_TAG, "VOICE_PROOF_ACOUSTIC|phase=failed", error)
            false
        }
        finishProbe(if (result) "passed" else "stop_not_detected")
    }

    private fun validateFixture(wave: PcmWave): Boolean {
        val modelWave = wave.resampleTo(MODEL_SAMPLE_RATE)
        val spotter = VoiceModelCatalog.keywordSpotter(applicationContext)
            ?: error("The wake model is unavailable.")
        val wakeDetected = try {
            val stream = spotter.createStream()
            try {
                var detected = false
                modelWave.samples.asList().chunked(KWS_CHUNK_SAMPLES).forEach { chunk ->
                    stream.acceptWaveform(chunk.toFloatSamples(), modelWave.sampleRate)
                    while (spotter.isReady(stream)) {
                        spotter.decode(stream)
                        if (spotter.getResult(stream).keyword.isNotBlank()) detected = true
                    }
                }
                repeat(KWS_TRAILING_SILENCE_CHUNKS) {
                    stream.acceptWaveform(FloatArray(KWS_CHUNK_SAMPLES), modelWave.sampleRate)
                    while (spotter.isReady(stream)) {
                        spotter.decode(stream)
                        if (spotter.getResult(stream).keyword.isNotBlank()) detected = true
                    }
                }
                detected
            } finally {
                stream.release()
            }
        } finally {
            spotter.release()
        }

        val recognizer = VoiceModelCatalog.recognizer(applicationContext)
        val transcript = try {
            val stream = recognizer.createStream()
            try {
                modelWave.samples.asList().chunked(ASR_CHUNK_SAMPLES).forEach { chunk ->
                    stream.acceptWaveform(chunk.toFloatSamples(), modelWave.sampleRate)
                    while (recognizer.isReady(stream)) recognizer.decode(stream)
                }
                repeat(ASR_TRAILING_SILENCE_CHUNKS) {
                    stream.acceptWaveform(FloatArray(ASR_CHUNK_SAMPLES), modelWave.sampleRate)
                    while (recognizer.isReady(stream)) recognizer.decode(stream)
                }
                stream.inputFinished()
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                recognizer.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        } finally {
            recognizer.release()
        }
        val stopMatch = isHandsFreeStopCommand(removeLeadingWakePhrase(transcript, DEFAULT_WAKE_PHRASE))
        Log.i(
            LOG_TAG,
            "VOICE_PROOF_FIXTURE|kws=$wakeDetected|stop_match=$stopMatch|chars=${transcript.length}",
        )
        return wakeDetected
    }

    private fun List<Short>.toFloatSamples(): FloatArray {
        return FloatArray(size) { index -> this[index] / 32768.0f }
    }

    private fun PcmWave.resampleTo(targetSampleRate: Int): PcmWave {
        if (sampleRate == targetSampleRate) return this
        val targetSize = (samples.size.toLong() * targetSampleRate / sampleRate).toInt()
        val output = ShortArray(targetSize)
        for (index in output.indices) {
            val sourcePosition = index.toDouble() * sampleRate / targetSampleRate
            val leftIndex = sourcePosition.toInt().coerceIn(samples.indices)
            val rightIndex = (leftIndex + 1).coerceAtMost(samples.lastIndex)
            val fraction = sourcePosition - leftIndex
            output[index] = (
                samples[leftIndex] * (1.0 - fraction) + samples[rightIndex] * fraction
                ).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return PcmWave(targetSampleRate, output)
    }

    private fun playThroughBuiltInSpeaker(wave: PcmWave) {
        val leadingSilence = ShortArray((wave.sampleRate * LEADING_SILENCE_MS / 1_000L).toInt())
        val trailingSilence = ShortArray((wave.sampleRate * TRAILING_SILENCE_MS / 1_000L).toInt())
        val samples = leadingSilence + wave.samples + trailingSilence
        val builtInSpeaker = audioManager
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { device -> device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: error("Built-in speaker route is unavailable.")
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(wave.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(samples.size * Short.SIZE_BYTES)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            check(track.setPreferredDevice(builtInSpeaker)) { "Android rejected the built-in speaker preference." }
            check(track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING) == samples.size) {
                "The acoustic fixture was not fully buffered."
            }
            track.setVolume(AudioTrack.getMaxVolume())
            track.play()
            val routedDevice = waitForRoutedDevice(track)
            check(routedDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                "Fixture routed to ${routedDevice?.type ?: "no device"}, not the built-in speaker."
            }
            Log.i(LOG_TAG, "VOICE_PROOF_ACOUSTIC|phase=playing|route=built_in_speaker")
            val playbackMs = samples.size.toLong() * 1_000L / wave.sampleRate
            SystemClock.sleep(playbackMs + AUDIO_TRACK_DRAIN_MS)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun waitForRoutedDevice(track: AudioTrack): AudioDeviceInfo? {
        repeat(ROUTE_POLLS) {
            track.routedDevice?.let { return it }
            SystemClock.sleep(ROUTE_POLL_MS)
        }
        return null
    }

    private suspend fun waitForDurableStop(): Boolean {
        repeat(STOP_RESULT_POLLS) {
            val state = settingsStore.state()
            if (!state.enabled && state.voiceServiceState == VoiceServiceState.DISABLED) {
                delay(POST_STOP_GUARD_MS)
                return !settingsStore.state().enabled && !VoiceCaptureReadiness.isReady()
            }
            delay(STATE_POLL_MS)
        }
        return false
    }

    private fun readPcm16Wave(file: File): PcmWave {
        val bytes = file.readBytes()
        require(bytes.size > WAVE_HEADER_BYTES && bytes.ascii(0, 4) == "RIFF") { "Invalid RIFF fixture." }
        require(bytes.ascii(8, 4) == "WAVE") { "Invalid WAVE fixture." }
        var offset = RIFF_CHUNKS_OFFSET
        var sampleRate: Int? = null
        var channels: Int? = null
        var bitsPerSample: Int? = null
        var samples: ShortArray? = null
        while (offset + CHUNK_HEADER_BYTES <= bytes.size) {
            val chunkName = bytes.ascii(offset, 4)
            val chunkSize = bytes.littleEndianInt(offset + 4)
            val payloadOffset = offset + CHUNK_HEADER_BYTES
            if (chunkSize < 0 || payloadOffset + chunkSize > bytes.size) break
            when (chunkName) {
                "fmt " -> {
                    channels = bytes.littleEndianShort(payloadOffset + 2).toInt()
                    sampleRate = bytes.littleEndianInt(payloadOffset + 4)
                    bitsPerSample = bytes.littleEndianShort(payloadOffset + 14).toInt()
                }
                "data" -> {
                    samples = ShortArray(chunkSize / Short.SIZE_BYTES)
                    ByteBuffer.wrap(bytes, payloadOffset, chunkSize)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer()
                        .get(samples)
                }
            }
            offset = payloadOffset + chunkSize + (chunkSize and 1)
        }
        require(channels == 1) { "Only mono fixtures are supported." }
        require(bitsPerSample == Short.SIZE_BITS) { "Only PCM16 fixtures are supported." }
        return PcmWave(
            sampleRate = requireNotNull(sampleRate) { "Wave fixture has no sample rate." },
            samples = requireNotNull(samples) { "Wave fixture has no data." },
        )
    }

    private fun ByteArray.ascii(offset: Int, length: Int): String {
        return String(this, offset, length, Charsets.US_ASCII)
    }

    private fun ByteArray.littleEndianInt(offset: Int): Int {
        return ByteBuffer.wrap(this, offset, Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun ByteArray.littleEndianShort(offset: Int): Short {
        return ByteBuffer.wrap(this, offset, Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).short
    }

    private fun finishProbe(result: String) {
        if (!closed.compareAndSet(false, true)) return
        Log.i(LOG_TAG, "VOICE_PROOF_ACOUSTIC|result=$result")
        runOnUiThread {
            restoreMediaVolume()
            closeSpeaker()
            finish()
        }
    }

    private fun restoreMediaVolume() {
        originalMediaVolume?.let { volume ->
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0) }
        }
        originalMediaVolume = null
    }

    private fun closeSpeaker() {
        initializationGeneration += 1
        initializationTimeoutJob?.cancel()
        initializationTimeoutJob = null
        speaker?.shutdown()
        speaker = null
    }

    private fun tryNextSpeechEngine() {
        initializationTimeoutJob?.cancel()
        initializationTimeoutJob = null
        speaker?.shutdown()
        speaker = null
        initializeNextSpeechEngine()
    }

    private data class PcmWave(
        val sampleRate: Int,
        val samples: ShortArray,
    )

    private companion object {
        const val LOG_TAG = "PocketAgentVoiceProof"
        const val SPOKEN_PROBE = "Off us. Please stop listening."
        const val UTTERANCE_ID = "voice-proof-acoustic-wake"
        const val TTS_INITIALIZATION_TIMEOUT_MS = 3_000L
        const val LISTENER_READY_POLLS = 240
        const val STOP_RESULT_POLLS = 160
        const val STATE_POLL_MS = 250L
        const val POST_STOP_GUARD_MS = 3_000L
        const val LEADING_SILENCE_MS = 250L
        const val TRAILING_SILENCE_MS = 1_000L
        const val TEST_VOLUME_FRACTION = 0.8f
        const val AUDIO_TRACK_DRAIN_MS = 300L
        const val ROUTE_POLLS = 20
        const val ROUTE_POLL_MS = 50L
        const val WAVE_HEADER_BYTES = 44
        const val RIFF_CHUNKS_OFFSET = 12
        const val CHUNK_HEADER_BYTES = 8
        const val MODEL_SAMPLE_RATE = 16_000
        const val KWS_CHUNK_SAMPLES = 1_600
        const val KWS_TRAILING_SILENCE_CHUNKS = 10
        const val ASR_CHUNK_SAMPLES = 2_048
        const val ASR_TRAILING_SILENCE_CHUNKS = 10
    }
}
