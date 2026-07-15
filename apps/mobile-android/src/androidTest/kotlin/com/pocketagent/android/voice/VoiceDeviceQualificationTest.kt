package com.pocketagent.android.voice

import android.Manifest
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.pocketagent.tools.ToolCall
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceDeviceQualificationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val appContext = instrumentation.targetContext

    @Test
    fun productionModelSetupDownloadsVerifiesAndLoadsOfficialModels() {
        runBlocking {
            withTimeout(TimeUnit.MINUTES.toMillis(5)) {
                VoiceModelInstaller(appContext).install().getOrThrow()
                VoiceModelCatalog.invalidate(appContext)
                val status = VoiceModelCatalog.status(appContext)
                assertTrue("ASR model setup incomplete: ${status.missingPaths}", status.ready)
                assertTrue(
                    "KWS model setup incomplete: ${status.missingWakeWordPaths}",
                    status.dedicatedWakeWordReady,
                )
                VoiceModelCatalog.recognizer(appContext).release()
                val spotter = VoiceModelCatalog.keywordSpotter(appContext)
                    ?: throw AssertionError("Verified wake model could not initialize")
                spotter.release()
                Log.i(TAG, "VOICE_MODEL_SETUP|download=official|hashes=verified|runtime=loaded")
            }
        }
    }

    @Test
    fun realOfflineEnglishTtsSynthesizesToFile() {
        val output = File(appContext.cacheDir, "voice-device-qualification.wav")
        output.delete()
        val speaker = createReadyOfflineEnglishSpeaker()
        val completion = CountDownLatch(1)
        val errorCode = AtomicInteger(NO_TTS_ERROR)
        val utteranceId = "voice-device-file"
        speaker.setOnUtteranceProgressListener(
            completionListener(
                utteranceId = utteranceId,
                completion = completion,
                errorCode = errorCode,
            ),
        )

        try {
            val result = speaker.synthesizeToFile(
                "PocketGPT is proving that this English voice can synthesize on the connected device.",
                Bundle(),
                output,
                utteranceId,
            )
            assertEquals(TextToSpeech.SUCCESS, result)
            assertTrue("TTS file synthesis timed out", completion.await(TTS_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals("TTS synthesis failed", NO_TTS_ERROR, errorCode.get())
            assertTrue("TTS output was missing or empty", output.isFile && output.length() > MIN_TTS_FILE_BYTES)
            Log.i(TAG, "TTS_FILE|voice=${speaker.voice?.name}|bytes=${output.length()}")
        } finally {
            speaker.shutdown()
            output.delete()
        }
    }

    @Test
    fun playbackControllerStopsWithoutLateStateResurrection() {
        val controller = AtomicReference<VoicePlaybackController>()
        instrumentation.runOnMainSync {
            controller.set(VoicePlaybackController(appContext))
            controller.get().toggle(
                messageId = "device-playback",
                markdownText = LONG_PLAYBACK_TEXT,
            )
        }

        try {
            waitUntil("playback to start") {
                val phase = controller.get().observe().value.phase
                phase == VoicePlaybackPhase.SPEAKING || phase == VoicePlaybackPhase.ERROR
            }
            assertEquals(
                VoicePlaybackState(phase = VoicePlaybackPhase.SPEAKING, messageId = "device-playback"),
                controller.get().observe().value,
            )

            instrumentation.runOnMainSync { controller.get().stop() }
            assertEquals(VoicePlaybackPhase.IDLE, controller.get().observe().value.phase)
            SystemClock.sleep(LATE_CALLBACK_GUARD_MS)
            assertEquals(VoicePlaybackPhase.IDLE, controller.get().observe().value.phase)
            Log.i(TAG, "TTS_CONTROLLER|stop=idle|late_callback=ignored")
        } finally {
            instrumentation.runOnMainSync { controller.get().release() }
        }
    }

    @Test
    fun upstreamWaveformDecodesThroughRealSherpaRuntime() {
        val fixtureWave = installAsrFixture()
        val wave = readPcm16Wave(fixtureWave)
        assertEquals(ASR_SAMPLE_RATE, wave.sampleRate)
        val recognizer = VoiceModelCatalog.recognizer(appContext)
        val stream = recognizer.createStream()

        val transcript = try {
            wave.samples.asList().chunked(ASR_CHUNK_SAMPLES).forEach { chunk ->
                stream.acceptWaveform(
                    chunk.map { sample -> sample / 32768.0f }.toFloatArray(),
                    wave.sampleRate,
                )
                recognizer.drainReadyFrames(stream)
            }
            stream.inputFinished()
            recognizer.drainReadyFrames(stream)
            recognizer.getResult(stream).text.trim().lowercase(Locale.US)
        } finally {
            stream.release()
            recognizer.release()
        }

        val expectedHits = FILE_EXPECTED_WORDS.count(transcript::contains)
        Log.i(TAG, "ASR_FILE|transcript=$transcript|expected_hits=$expectedHits")
        assertTrue("Unexpected Sherpa transcript: $transcript", expectedHits >= MIN_FILE_WORD_HITS)
    }

    @Test
    fun dedicatedOffasWakeModelAcceptsOffasAndRejectsUnrelatedSpeech() {
        installKwsFixture()
        val status = VoiceModelCatalog.status(appContext)
        assertTrue("Dedicated wake model was not ready: ${status.missingWakeWordPaths}", status.dedicatedWakeWordReady)
        val speaker = createReadyOfflineEnglishSpeaker()
        val positiveWaveFile = File(appContext.cacheDir, "kws-positive.wav")
        val negativeWaveFile = File(appContext.cacheDir, "kws-negative.wav")
        val spotter = VoiceModelCatalog.keywordSpotter(appContext)
            ?: throw AssertionError("Dedicated wake model could not initialize")

        try {
            synthesizeSpeechToFile(speaker, "Off us", positiveWaveFile, "kws-positive")
            synthesizeSpeechToFile(
                speaker,
                "The weather is calm and the kitchen timer is quiet.",
                negativeWaveFile,
                "kws-negative",
            )
            val positiveDetected = detectKeyword(spotter, readPcm16Wave(positiveWaveFile))
            val negativeDetected = detectKeyword(spotter, readPcm16Wave(negativeWaveFile))
            Log.i(
                TAG,
                "KWS_FILE|profile=offas-v1-score-3.0-threshold-0.1|" +
                    "positive=$positiveDetected|negative=$negativeDetected",
            )
            assertTrue("Dedicated KWS did not detect the device's spoken Offas fixture", positiveDetected)
            assertTrue("Dedicated KWS falsely accepted unrelated speech", !negativeDetected)
        } finally {
            spotter.release()
            speaker.shutdown()
            positiveWaveFile.delete()
            negativeWaveFile.delete()
        }
    }

    @Test
    fun deviceTtsOffasPronunciationIsVisibleToTheAsrModel() {
        val speaker = createReadyOfflineEnglishSpeaker()
        val waveFile = File(appContext.cacheDir, "offas-pronunciation.wav")
        try {
            synthesizeSpeechToFile(speaker, "Offas", waveFile, "offas-pronunciation")
            val transcript = decodeWithAsr(readPcm16Wave(waveFile))
            Log.i(TAG, "WAKE_PRONUNCIATION|spoken=Offas|transcript=$transcript")
            assertTrue("ASR returned no pronunciation evidence for Offas", transcript.isNotBlank())
        } finally {
            speaker.shutdown()
            waveFile.delete()
        }
    }

    @Test
    fun dictationCapturesSpeechAcrossThePhysicalSpeakerAndMicrophone() {
        val fixtureWave = installAsrFixture()
        instrumentation.uiAutomation.grantRuntimePermission(appContext.packageName, Manifest.permission.RECORD_AUDIO)
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        var volumeChanged = false
        val transcript = AtomicReference<String>()
        val transcriptReady = CountDownLatch(1)
        val controller = AtomicReference<VoiceDictationController>()
        var speaker: TextToSpeech? = null

        try {
            instrumentation.runOnMainSync {
                controller.set(VoiceDictationController(appContext))
                controller.get().toggle(alwaysOnListeningEnabled = false) { result ->
                    transcript.set(result)
                    transcriptReady.countDown()
                }
            }
            waitUntil("dictation to start") {
                controller.get().observe().value.phase == VoiceDictationPhase.LISTENING
            }
            SystemClock.sleep(ACOUSTIC_ARMING_MS)

            val acousticSource = InstrumentationRegistry.getArguments().getString(VOICE_ACOUSTIC_SOURCE_ARG)
            when (acousticSource) {
                EXTERNAL_ACOUSTIC_SOURCE -> {
                    Log.i(TAG, "ASR_ACOUSTIC_READY|source=external")
                    SystemClock.sleep(EXTERNAL_CAPTURE_WINDOW_MS)
                }
                DEVICE_FIXTURE_ACOUSTIC_SOURCE -> {
                    val testVolume = testMediaVolume(audioManager)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, testVolume, 0)
                    volumeChanged = true
                    Log.i(TAG, "ASR_ACOUSTIC_READY|source=device_fixture")
                    playFixtureThroughDeviceSpeaker(fixtureWave)
                    SystemClock.sleep(POST_SPEECH_CAPTURE_MS)
                }
                else -> playTtsAcousticFixture(
                    audioManager = audioManager,
                    onSpeakerCreated = { createdSpeaker -> speaker = createdSpeaker },
                    onVolumeChanged = { volumeChanged = true },
                )
            }
            instrumentation.runOnMainSync { controller.get().finish() }
            assertTrue(
                "Dictation did not return a transcript: ${controller.get().observe().value}",
                transcriptReady.await(ASR_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )

            val recognized = transcript.get().orEmpty().lowercase(Locale.US)
            val expectedHits = ACOUSTIC_EXPECTED_WORDS.count(recognized::contains)
            assertTrue("Unexpected acoustic transcript: $recognized", expectedHits >= MIN_ACOUSTIC_WORD_HITS)
            assertEquals(VoiceDictationPhase.IDLE, controller.get().observe().value.phase)
            Log.i(TAG, "ASR_ACOUSTIC|transcript=$recognized|expected_hits=$expectedHits")
        } finally {
            speaker?.stop()
            speaker?.shutdown()
            controller.get()?.let { activeController ->
                instrumentation.runOnMainSync { activeController.release() }
            }
            if (volumeChanged) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            }
        }
    }

    @Test
    fun physicalSpeechBecomesEditableComposerDraftWithoutAutoSend() {
        val fixtureWave = installAsrFixture()
        instrumentation.uiAutomation.grantRuntimePermission(appContext.packageName, Manifest.permission.RECORD_AUDIO)
        val device = UiDevice.getInstance(instrumentation)
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        try {
            device.executeShellCommand(
                "am start -W -n ${appContext.packageName}/.MainActivity",
            )
            assertTrue(
                "Composer did not become ready",
                device.wait(Until.hasObject(By.res(COMPOSER_INPUT_TAG)), UI_TIMEOUT_MS),
            )
            val initialUserMessageCount = device.findObjects(By.res(USER_MESSAGE_BUBBLE_TAG)).size
            device.findObject(By.res(COMPOSER_INPUT_TAG)).text = ""
            device.findObject(By.res(VOICE_DICTATION_BUTTON_TAG)).click()
            assertTrue(
                "Dictation did not enter the listening state",
                device.wait(Until.hasObject(By.desc(STOP_DICTATION_DESCRIPTION)), UI_TIMEOUT_MS),
            )

            SystemClock.sleep(ACOUSTIC_ARMING_MS)
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                testMediaVolume(audioManager),
                0,
            )
            playFixtureThroughDeviceSpeaker(fixtureWave)
            device.findObject(By.desc(STOP_DICTATION_DESCRIPTION))?.click()

            waitUntil("dictation transcript to reach the real composer") {
                device.findObject(By.res(COMPOSER_INPUT_TAG))?.text.orEmpty().isNotBlank()
            }
            val recognized = device.findObject(By.res(COMPOSER_INPUT_TAG)).text
                .orEmpty()
                .lowercase(Locale.US)
            val expectedHits = ACOUSTIC_EXPECTED_WORDS.count(recognized::contains)
            assertTrue("Unexpected composer transcript: $recognized", expectedHits >= MIN_ACOUSTIC_WORD_HITS)
            assertEquals(
                "Dictation must never auto-send",
                initialUserMessageCount,
                device.findObjects(By.res(USER_MESSAGE_BUBBLE_TAG)).size,
            )

            val edited = "$recognized edited"
            device.findObject(By.res(COMPOSER_INPUT_TAG)).text = edited
            waitUntil("composer transcript to remain editable") {
                device.findObject(By.res(COMPOSER_INPUT_TAG))?.text == edited
            }
            assertEquals(
                "Editing the draft must not send it",
                initialUserMessageCount,
                device.findObjects(By.res(USER_MESSAGE_BUBBLE_TAG)).size,
            )
            Log.i(TAG, "ASR_COMPOSER|transcript=$recognized|edited=true|auto_sent=false")
        } finally {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
        }
    }

    @Test
    fun boundedTimerDelegatesToThePhysicalSystemClock() {
        val device = UiDevice.getInstance(instrumentation)
        val argumentsJson =
            """{"duration_seconds":"120","label":"PocketAgent qualification"}"""
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        device.executeShellCommand("am start -W -n ${appContext.packageName}/.MainActivity")
        assertTrue(
            "PocketAgent did not become visible before the Clock handoff",
            device.wait(Until.hasObject(By.pkg(appContext.packageName)), UI_TIMEOUT_MS),
        )
        VoiceSessionVisibility.setVisible(true)

        val result = try {
            VoiceActionExecutionAuthorization.runAuthorized(
                AndroidLocalToolRuntime.TOOL_TIMER_SET,
                argumentsJson,
            ) {
                AndroidLocalToolRuntime(appContext).executeToolCall(
                    ToolCall(
                        name = AndroidLocalToolRuntime.TOOL_TIMER_SET,
                        jsonArgs = argumentsJson,
                    ),
                )
            }
        } finally {
            VoiceSessionVisibility.setVisible(false)
        }

        assertTrue("Clock timer handoff failed: ${result.content}", result.success)
        Log.i(TAG, "CLOCK_TIMER|success=true|receipt=${result.content}")
    }

    private fun playTtsAcousticFixture(
        audioManager: AudioManager,
        onSpeakerCreated: (TextToSpeech) -> Unit,
        onVolumeChanged: () -> Unit,
    ) {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, testMediaVolume(audioManager), 0)
        onVolumeChanged()
        val speaker = createReadyOfflineEnglishSpeaker()
        onSpeakerCreated(speaker)
        val speechDone = CountDownLatch(1)
        val ttsError = AtomicInteger(NO_TTS_ERROR)
        val utteranceId = "voice-device-acoustic-loop"
        speaker.setOnUtteranceProgressListener(
            completionListener(
                utteranceId = utteranceId,
                completion = speechDone,
                errorCode = ttsError,
            ),
        )
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        assertEquals(
            TextToSpeech.SUCCESS,
            speaker.speak(ACOUSTIC_TEST_PHRASE, TextToSpeech.QUEUE_FLUSH, params, utteranceId),
        )
        assertTrue("Device TTS did not finish", speechDone.await(TTS_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals("Device TTS failed", NO_TTS_ERROR, ttsError.get())
        SystemClock.sleep(POST_SPEECH_CAPTURE_MS)
    }

    private fun playFixtureThroughDeviceSpeaker(file: File) {
        val wave = readPcm16Wave(file)
        val repeatedSamples = ShortArray(wave.samples.size * ACOUSTIC_FIXTURE_REPEATS)
        repeat(ACOUSTIC_FIXTURE_REPEATS) { repetition ->
            wave.samples.copyInto(repeatedSamples, destinationOffset = repetition * wave.samples.size)
        }
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
            .setBufferSizeInBytes(repeatedSamples.size * Short.SIZE_BYTES)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            assertEquals(
                repeatedSamples.size,
                track.write(repeatedSamples, 0, repeatedSamples.size, AudioTrack.WRITE_BLOCKING),
            )
            track.setVolume(AudioTrack.getMaxVolume())
            track.play()
            val playbackMs = repeatedSamples.size.toLong() * 1_000L / wave.sampleRate
            SystemClock.sleep(playbackMs + AUDIO_TRACK_DRAIN_MS)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun testMediaVolume(audioManager: AudioManager): Int {
        return (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * TEST_VOLUME_FRACTION)
            .toInt()
            .coerceAtLeast(1)
    }

    private fun createReadyOfflineEnglishSpeaker(): TextToSpeech {
        val candidates = listOf<String?>(null) + availableTtsEnginePackages(appContext)
        candidates.forEach { packageName ->
            val ready = CountDownLatch(1)
            val status = AtomicInteger(Int.MIN_VALUE)
            val listener = TextToSpeech.OnInitListener { initStatus ->
                status.set(initStatus)
                ready.countDown()
            }
            val speaker = packageName?.let { TextToSpeech(appContext, listener, it) }
                ?: TextToSpeech(appContext, listener)
            assertTrue(
                "TTS initialization timed out for ${packageName ?: "default"}",
                ready.await(TTS_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            val voices = speaker.voices.orEmpty().joinToString { voice ->
                "${voice.name}:${voice.locale}:network=${voice.isNetworkConnectionRequired}"
            }
            Log.i(TAG, "TTS_CANDIDATE|engine=${packageName ?: speaker.defaultEngine}|voices=$voices")
            if (status.get() == TextToSpeech.SUCCESS && configureOfflineEnglishVoice(speaker)) {
                val selectedVoice = speaker.voice ?: throw AssertionError("TTS did not expose the selected voice")
                assertTrue(
                    "Selected voice requires a network: ${selectedVoice.name}",
                    !selectedVoice.isNetworkConnectionRequired,
                )
                assertTrue(
                    "Selected voice is not English: ${selectedVoice.locale}",
                    selectedVoice.locale.isEnglishVoiceLocale(),
                )
                Log.i(
                    TAG,
                    "TTS_READY|engine=${packageName ?: speaker.defaultEngine}|" +
                        "voice=${selectedVoice.name}|locale=${selectedVoice.locale}",
                )
                return speaker
            }
            speaker.shutdown()
        }
        throw AssertionError("No installed TTS engine exposes an offline English voice")
    }

    private fun installAsrFixture(): File {
        val fixtureRootPath = InstrumentationRegistry.getArguments().getString(VOICE_FIXTURE_DIR_ARG)
            ?: throw AssertionError("Missing runner arg $VOICE_FIXTURE_DIR_ARG")
        assertTrue(
            "Voice fixture must be staged under /data/local/tmp",
            fixtureRootPath.startsWith(DEVICE_TMP_PREFIX) && SAFE_DEVICE_PATH.matches(fixtureRootPath),
        )
        val source = File(fixtureRootPath, ASR_MODEL_DIR)
        val destination = File(appContext.filesDir, "offas-voice-models/$ASR_MODEL_DIR")
        runShellCommand("run-as ${appContext.packageName} rm -rf files/offas-voice-models/$ASR_MODEL_DIR")
        runShellCommand("run-as ${appContext.packageName} mkdir -p files/offas-voice-models")
        runShellCommand(
            "run-as ${appContext.packageName} cp -R ${source.path} files/offas-voice-models/$ASR_MODEL_DIR",
        )
        assertTrue("Could not copy voice fixture from $source", destination.isDirectory)
        REQUIRED_ASR_FILES.forEach { fileName ->
            assertTrue("Copied voice fixture is missing $fileName", File(destination, fileName).isFile)
        }
        val fixtureWave = File(destination, "test_wavs/0.wav")
        assertTrue("Copied voice fixture is missing test_wavs/0.wav", fixtureWave.isFile)
        Log.i(TAG, "ASR_FIXTURE|source=$source|destination=$destination")
        return fixtureWave
    }

    private fun installKwsFixture() {
        val fixtureRootPath = InstrumentationRegistry.getArguments().getString(VOICE_KWS_FIXTURE_DIR_ARG)
        val destination = File(appContext.filesDir, "offas-voice-models/$KWS_MODEL_DIR")
        if (fixtureRootPath == null) {
            REQUIRED_KWS_FILES.forEach { fileName ->
                assertTrue("Provisioned wake model is missing $fileName", File(destination, fileName).isFile)
            }
            assertTrue(
                "Provisioned wake model must contain only the Offas keyword",
                hasDedicatedOffasKeyword(File(destination, "keywords.txt").readText()),
            )
            Log.i(TAG, "KWS_FIXTURE|source=preprovisioned|destination=$destination")
            return
        }
        assertTrue(
            "Wake fixture must be staged under /data/local/tmp",
            fixtureRootPath.startsWith(DEVICE_TMP_PREFIX) && SAFE_DEVICE_PATH.matches(fixtureRootPath),
        )
        val source = File(fixtureRootPath, KWS_MODEL_DIR)
        runShellCommand("run-as ${appContext.packageName} rm -rf files/offas-voice-models/$KWS_MODEL_DIR")
        runShellCommand("run-as ${appContext.packageName} mkdir -p files/offas-voice-models")
        runShellCommand(
            "run-as ${appContext.packageName} cp -R ${source.path} files/offas-voice-models/$KWS_MODEL_DIR",
        )
        REQUIRED_KWS_FILES.forEach { fileName ->
            assertTrue("Copied wake fixture is missing $fileName", File(destination, fileName).isFile)
        }
        assertTrue(
            "Wake fixture must contain only the Offas keyword",
            hasDedicatedOffasKeyword(File(destination, "keywords.txt").readText()),
        )
        Log.i(TAG, "KWS_FIXTURE|source=$source|destination=$destination")
    }

    private fun synthesizeSpeechToFile(
        speaker: TextToSpeech,
        text: String,
        output: File,
        utteranceId: String,
    ) {
        output.delete()
        val completion = CountDownLatch(1)
        val errorCode = AtomicInteger(NO_TTS_ERROR)
        speaker.setOnUtteranceProgressListener(
            completionListener(utteranceId, completion, errorCode),
        )
        assertEquals(
            TextToSpeech.SUCCESS,
            speaker.synthesizeToFile(text, Bundle(), output, utteranceId),
        )
        assertTrue("TTS synthesis timed out for $utteranceId", completion.await(TTS_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals("TTS synthesis failed for $utteranceId", NO_TTS_ERROR, errorCode.get())
        assertTrue("TTS output was missing for $utteranceId", output.isFile && output.length() > MIN_TTS_FILE_BYTES)
    }

    private fun detectKeyword(
        spotter: com.k2fsa.sherpa.onnx.KeywordSpotter,
        wave: PcmWave,
    ): Boolean {
        val modelWave = wave.resampleTo(ASR_SAMPLE_RATE)
        val stream = spotter.createStream()
        return try {
            var detected = false
            modelWave.samples.asList().chunked(KWS_CHUNK_SAMPLES).forEach { chunk ->
                stream.acceptWaveform(
                    chunk.map { sample -> sample / 32768.0f }.toFloatArray(),
                    modelWave.sampleRate,
                )
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
            stream.inputFinished()
            while (spotter.isReady(stream)) {
                spotter.decode(stream)
                if (spotter.getResult(stream).keyword.isNotBlank()) detected = true
            }
            detected
        } finally {
            stream.release()
        }
    }

    private fun decodeWithAsr(wave: PcmWave): String {
        val recognizer = VoiceModelCatalog.recognizer(appContext)
        val stream = recognizer.createStream()
        return try {
            wave.samples.asList().chunked(ASR_CHUNK_SAMPLES).forEach { chunk ->
                stream.acceptWaveform(
                    chunk.map { sample -> sample / 32768.0f }.toFloatArray(),
                    wave.sampleRate,
                )
                recognizer.drainReadyFrames(stream)
            }
            stream.inputFinished()
            recognizer.drainReadyFrames(stream)
            recognizer.getResult(stream).text.trim().lowercase(Locale.US)
        } finally {
            stream.release()
            recognizer.release()
        }
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
        return PcmWave(sampleRate = targetSampleRate, samples = output)
    }

    private fun runShellCommand(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return FileInputStream(descriptor.fileDescriptor).bufferedReader().use { reader ->
            reader.readText()
        }.also {
            descriptor.close()
        }
    }

    private fun completionListener(
        utteranceId: String,
        completion: CountDownLatch,
        errorCode: AtomicInteger,
    ): UtteranceProgressListener {
        return object : UtteranceProgressListener() {
            override fun onStart(activeId: String?) = Unit

            override fun onDone(activeId: String?) {
                if (activeId == utteranceId) completion.countDown()
            }

            @Deprecated("Called on older Android TTS engines")
            override fun onError(activeId: String?) {
                if (activeId == utteranceId) {
                    errorCode.set(TextToSpeech.ERROR)
                    completion.countDown()
                }
            }

            override fun onError(activeId: String?, code: Int) {
                if (activeId == utteranceId) {
                    errorCode.set(code)
                    completion.countDown()
                }
            }
        }
    }

    private fun readPcm16Wave(file: File): PcmWave {
        val bytes = file.readBytes()
        assertTrue("Invalid RIFF wave fixture", bytes.size > WAVE_HEADER_BYTES && bytes.ascii(0, 4) == "RIFF")
        assertEquals("WAVE", bytes.ascii(8, 4))
        var offset = RIFF_CHUNKS_OFFSET
        var sampleRate: Int? = null
        var channelCount: Int? = null
        var bitsPerSample: Int? = null
        var samples: ShortArray? = null
        while (offset + CHUNK_HEADER_BYTES <= bytes.size) {
            val chunkName = bytes.ascii(offset, 4)
            val chunkSize = bytes.littleEndianInt(offset + 4)
            val payloadOffset = offset + CHUNK_HEADER_BYTES
            if (payloadOffset + chunkSize > bytes.size) break
            when (chunkName) {
                "fmt " -> {
                    channelCount = bytes.littleEndianShort(payloadOffset + 2).toInt()
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
        assertEquals("Only mono voice fixtures are supported", 1, channelCount)
        assertEquals("Only PCM16 voice fixtures are supported", Short.SIZE_BITS, bitsPerSample)
        return PcmWave(
            sampleRate = sampleRate ?: throw AssertionError("Wave fixture has no sample rate"),
            samples = samples ?: throw AssertionError("Wave fixture has no data chunk"),
        )
    }

    private fun waitUntil(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + STATE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(STATE_POLL_MS)
        }
        fail("Timed out waiting for $label")
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

    private fun com.k2fsa.sherpa.onnx.OnlineRecognizer.drainReadyFrames(
        stream: com.k2fsa.sherpa.onnx.OnlineStream,
    ) {
        while (isReady(stream)) decode(stream)
    }

    private data class PcmWave(
        val sampleRate: Int,
        val samples: ShortArray,
    )

    companion object {
        private const val TAG = "VoiceDeviceTest"
        private const val VOICE_FIXTURE_DIR_ARG = "voice_fixture_dir"
        private const val VOICE_KWS_FIXTURE_DIR_ARG = "voice_kws_fixture_dir"
        private const val VOICE_ACOUSTIC_SOURCE_ARG = "voice_acoustic_source"
        private const val COMPOSER_INPUT_TAG = "composer_input"
        private const val VOICE_DICTATION_BUTTON_TAG = "voice_dictation_button"
        private const val USER_MESSAGE_BUBBLE_TAG = "message_bubble_user"
        private const val STOP_DICTATION_DESCRIPTION = "Stop dictation"
        private const val EXTERNAL_ACOUSTIC_SOURCE = "external"
        private const val DEVICE_FIXTURE_ACOUSTIC_SOURCE = "device_fixture"
        private const val DEVICE_TMP_PREFIX = "/data/local/tmp/"
        private const val ASR_MODEL_DIR = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"
        private const val KWS_MODEL_DIR =
            "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
        private const val ASR_SAMPLE_RATE = 16_000
        private const val ASR_CHUNK_SAMPLES = 2_048
        private const val KWS_CHUNK_SAMPLES = 1_600
        private const val KWS_TRAILING_SILENCE_CHUNKS = 10
        private const val TTS_TIMEOUT_SECONDS = 30L
        private const val ASR_TIMEOUT_SECONDS = 15L
        private const val STATE_TIMEOUT_MS = 20_000L
        private const val UI_TIMEOUT_MS = 30_000L
        private const val STATE_POLL_MS = 50L
        private const val LATE_CALLBACK_GUARD_MS = 1_500L
        private const val ACOUSTIC_ARMING_MS = 2_000L
        private const val EXTERNAL_CAPTURE_WINDOW_MS = 12_000L
        private const val POST_SPEECH_CAPTURE_MS = 500L
        private const val AUDIO_TRACK_DRAIN_MS = 500L
        private const val MIN_TTS_FILE_BYTES = 1_024L
        private const val NO_TTS_ERROR = Int.MIN_VALUE
        private const val TEST_VOLUME_FRACTION = 0.8f
        private const val MIN_ACOUSTIC_WORD_HITS = 2
        private const val MIN_FILE_WORD_HITS = 4
        private const val ACOUSTIC_FIXTURE_REPEATS = 2
        private const val WAVE_HEADER_BYTES = 44
        private const val RIFF_CHUNKS_OFFSET = 12
        private const val CHUNK_HEADER_BYTES = 8
        private val REQUIRED_ASR_FILES = listOf(
            "encoder-epoch-99-avg-1.int8.onnx",
            "decoder-epoch-99-avg-1.onnx",
            "joiner-epoch-99-avg-1.int8.onnx",
            "tokens.txt",
        )
        private val REQUIRED_KWS_FILES = listOf(
            "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
            "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
            "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
            "tokens.txt",
            "keywords.txt",
        )
        private val ACOUSTIC_EXPECTED_WORDS = listOf("early", "nightfall", "yellow", "lamps")
        private val FILE_EXPECTED_WORDS = listOf("yellow", "lamps", "light", "squalid", "quarter")
        private val SAFE_DEVICE_PATH = Regex("[A-Za-z0-9._/-]+")
        private const val ACOUSTIC_TEST_PHRASE =
            "After early nightfall, the yellow lamps would light up here and there."
        private val LONG_PLAYBACK_TEXT = buildString {
            repeat(8) {
                append("PocketGPT is reading this long offline response on the connected physical device. ")
            }
        }
    }
}
