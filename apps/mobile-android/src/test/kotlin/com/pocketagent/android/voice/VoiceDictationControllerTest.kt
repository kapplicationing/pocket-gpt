package com.pocketagent.android.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceDictationControllerTest {
    @Test
    fun `permission blocker never starts recognition`() = runTest {
        var engineCreated = false
        val controller = controller(
            scheduler = testScheduler,
            hasPermission = false,
            engineFactory = {
                engineCreated = true
                FakeVoiceEngine()
            },
        )

        controller.toggle(alwaysOnListeningEnabled = false, onTranscriptReady = {})

        assertEquals(VoiceDictationIssue.MICROPHONE_PERMISSION, controller.observe().value.issue)
        assertFalse(engineCreated)
        controller.release()
    }

    @Test
    fun `always on blocker keeps microphone ownership exclusive`() = runTest {
        val controller = controller(scheduler = testScheduler)

        controller.toggle(alwaysOnListeningEnabled = true, onTranscriptReady = {})

        assertEquals(VoiceDictationIssue.ALWAYS_ON_ACTIVE, controller.observe().value.issue)
        controller.release()
    }

    @Test
    fun `graceful finish drains final words and leaves transcript editable`() = runTest {
        val engine = FakeVoiceEngine(partialTranscript = "draft words", finalTranscript = "draft words completed")
        val controller = controller(scheduler = testScheduler, engineFactory = { engine })
        var completedTranscript: String? = null

        controller.toggle(alwaysOnListeningEnabled = false) { completedTranscript = it }
        runCurrent()
        assertEquals(VoiceDictationPhase.LISTENING, controller.observe().value.phase)
        assertEquals("draft words", controller.observe().value.partialTranscript)

        controller.finish()
        assertEquals(VoiceDictationPhase.FINALIZING, controller.observe().value.phase)
        assertTrue(engine.finishRequested)
        runCurrent()

        assertEquals("draft words completed", completedTranscript)
        assertEquals(VoiceDictationPhase.IDLE, controller.observe().value.phase)
        assertFalse(controller.observe().value.isCaptureActive)
        controller.release()
    }

    @Test
    fun `late partial cannot resurrect completed capture`() = runTest {
        val engine = FakeVoiceEngine(
            partialTranscript = "first",
            finalTranscript = "final",
            emitFinalPartial = true,
        )
        val controller = controller(scheduler = testScheduler, engineFactory = { engine })

        controller.toggle(alwaysOnListeningEnabled = false, onTranscriptReady = {})
        runCurrent()
        controller.finish()
        runCurrent()

        assertEquals(VoiceDictationPhase.IDLE, controller.observe().value.phase)
        assertEquals("", controller.observe().value.partialTranscript)
        controller.release()
    }

    @Test
    fun `cancel during readiness check discards stale completion`() = runTest {
        val readiness = CompletableDeferred<Boolean>()
        var engineCreated = false
        val controller = controller(
            scheduler = testScheduler,
            voiceModelsReady = { readiness.await() },
            engineFactory = {
                engineCreated = true
                FakeVoiceEngine()
            },
        )

        controller.toggle(alwaysOnListeningEnabled = false, onTranscriptReady = {})
        runCurrent()
        assertEquals(VoiceDictationPhase.CHECKING, controller.observe().value.phase)

        controller.cancel()
        readiness.complete(true)
        runCurrent()

        assertEquals(VoiceDictationPhase.IDLE, controller.observe().value.phase)
        assertFalse(engineCreated)
        controller.release()
    }

    @Test
    fun `dictation appends without sending or replacing an existing draft`() {
        assertEquals("hello world", appendDictationToDraft("hello", " world "))
        assertEquals("new words", appendDictationToDraft("", " new words "))
        assertEquals("unchanged", appendDictationToDraft("unchanged", "  "))
    }

    @Test
    fun `audio capture lease permits only one microphone owner`() {
        assertTrue(VoiceAudioCaptureLease.tryAcquire())
        try {
            assertFalse(VoiceAudioCaptureLease.tryAcquire())
        } finally {
            VoiceAudioCaptureLease.release()
        }
        assertTrue(VoiceAudioCaptureLease.tryAcquire())
        VoiceAudioCaptureLease.release()
    }

    private fun controller(
        scheduler: TestCoroutineScheduler,
        hasPermission: Boolean = true,
        voiceModelsReady: suspend () -> Boolean = { true },
        engineFactory: () -> OffasVoiceEngine = { FakeVoiceEngine() },
    ): VoiceDictationController {
        val dispatcher = StandardTestDispatcher(scheduler)
        return VoiceDictationController(
            hasRecordAudioPermission = { hasPermission },
            voiceModelsReady = voiceModelsReady,
            engineFactory = engineFactory,
            scope = CoroutineScope(SupervisorJob() + dispatcher),
        )
    }
}

private class FakeVoiceEngine(
    private val partialTranscript: String = "partial",
    private val finalTranscript: String = "final",
    private val emitFinalPartial: Boolean = false,
) : OffasVoiceEngine {
    private val finishSignal = CompletableDeferred<Unit>()
    var finishRequested: Boolean = false
        private set

    override suspend fun awaitWakeAndCommand(
        wakePhrase: String,
        silenceTimeoutSeconds: Int,
        directCapture: Boolean,
        onWakeWord: () -> Unit,
        onStateChanged: (VoiceServiceState) -> Unit,
        onPartialTranscript: (String) -> Unit,
    ): String {
        onPartialTranscript(partialTranscript)
        finishSignal.await()
        if (emitFinalPartial) {
            onPartialTranscript(finalTranscript)
        }
        return finalTranscript
    }

    override fun requestFinish(disposition: VoiceCaptureStopDisposition) {
        finishRequested = true
        finishSignal.complete(Unit)
    }

    override fun release() = Unit
}
