package com.pocketagent.runtime

import com.pocketagent.inference.InferenceModule
import com.pocketagent.inference.InferenceRequest
import com.pocketagent.nativebridge.BridgeError
import com.pocketagent.nativebridge.ManagedRuntimePort
import com.pocketagent.nativebridge.ModelLifecycleEvent
import com.pocketagent.nativebridge.ModelLifecycleState
import com.pocketagent.nativebridge.RuntimeBackend
import com.pocketagent.nativebridge.RuntimeInferencePorts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeResidencyManagerTest {
    @Test
    fun `ensureLoaded tracks resident slot and explicit unload clears it`() {
        val inferenceModule = RecordingInferenceModule()
        val manager = RuntimeResidencyManager(inferenceModule, nowMs = advancingClock())

        assertTrue(manager.ensureLoaded(modelId = "model-a", slotId = "slot-1", keepAliveMs = 1_000L))
        assertEquals(1, inferenceModule.loadCalls)
        assertEquals("model-a", manager.listResident().single().modelId)
        assertEquals("slot-1", manager.listResident().single().slotId)

        assertTrue(manager.unload(reason = "manual"))
        assertEquals(1, inferenceModule.unloadCalls)
        assertTrue(manager.listResident().isEmpty())
    }

    @Test
    fun `critical trim memory unloads resident model`() {
        val inferenceModule = RecordingInferenceModule()
        val manager = RuntimeResidencyManager(inferenceModule, nowMs = advancingClock())

        manager.ensureLoaded(modelId = "model-a", slotId = "slot-1", keepAliveMs = 120_000L)
        assertTrue(manager.onTrimMemory(level = 15))

        assertEquals(1, inferenceModule.unloadCalls)
        assertTrue(manager.listResident().isEmpty())
    }

    @Test
    fun `critical trim memory queues unload while generation is active`() {
        val inferenceModule = RecordingInferenceModule()
        val manager = RuntimeResidencyManager(inferenceModule, nowMs = advancingClock())

        manager.ensureLoaded(modelId = "model-a", slotId = "slot-1", keepAliveMs = 120_000L)
        manager.onGenerationStarted()

        assertTrue(manager.onTrimMemory(level = 15))
        assertEquals(0, inferenceModule.unloadCalls)
        assertEquals(1, manager.queueDepth())
        assertEquals("slot-1", manager.listResident().single().slotId)

        manager.onGenerationFinished(slotId = "slot-1", keepAliveMs = 120_000L)

        assertEquals(1, inferenceModule.unloadCalls)
        assertTrue(manager.listResident().isEmpty())
    }

    @Test
    fun `moderate trim memory shortens keep alive without unloading`() {
        val inferenceModule = RecordingInferenceModule()
        val manager = RuntimeResidencyManager(inferenceModule, nowMs = advancingClock())

        manager.ensureLoaded(modelId = "model-a", slotId = "slot-1", keepAliveMs = 300_000L)

        assertTrue(manager.onTrimMemory(level = 5))

        assertEquals(0, inferenceModule.unloadCalls)
        val resident = manager.listResident().single()
        assertEquals(60_000L, resident.keepAliveMs)
        assertFalse(resident.expiresAtEpochMs <= resident.lastTouchedAtEpochMs)
    }

    @Test
    fun `generation lifecycle updates queue depth and preserves slot residency`() {
        val inferenceModule = RecordingInferenceModule()
        val manager = RuntimeResidencyManager(inferenceModule, nowMs = advancingClock())

        manager.ensureLoaded(modelId = "model-a", slotId = "slot-1", keepAliveMs = 1_000L)
        manager.onGenerationStarted()
        assertEquals(1, manager.queueDepth())

        manager.onGenerationFinished(slotId = "slot-1", keepAliveMs = 2_000L)

        assertEquals(0, manager.queueDepth())
        assertEquals("slot-1", manager.listResident().single().slotId)
    }

    @Test
    fun `offload requested during generation is queued until generation completes`() {
        val inferenceModule = RecordingInferenceModule()
        val manager = RuntimeResidencyManager(inferenceModule, nowMs = advancingClock())

        manager.ensureLoaded(modelId = "model-a", slotId = "slot-1", keepAliveMs = 5_000L)
        manager.onGenerationStarted()
        assertEquals(RuntimeUnloadDisposition.QUEUED, manager.requestUnload(reason = "manual"))
        assertEquals(0, inferenceModule.unloadCalls)
        assertEquals(1, manager.queueDepth())

        manager.onGenerationFinished(slotId = "slot-1", keepAliveMs = 5_000L)

        assertEquals(1, inferenceModule.unloadCalls)
        assertTrue(manager.listResident().isEmpty())
        assertEquals(0, manager.queueDepth())
    }

    @Test
    fun `ensureLoaded forwards model version to managed runtime when available`() {
        val inferenceModule = RecordingInferenceModule()
        val managedRuntime = RecordingManagedRuntime()
        val manager = RuntimeResidencyManager(
            inferenceModule = inferenceModule,
            runtimeInferencePorts = RuntimeInferencePorts(managedRuntime = managedRuntime),
            nowMs = advancingClock(),
        )
        val identity = SessionCacheIdentity(
            cacheKey = "cache-1",
            modelId = "model-a",
            modelVersion = "ud_iq2_xxs",
            modelPathHash = "path-hash",
            loadFingerprint = "load-fingerprint",
        )

        assertTrue(
            manager.ensureLoaded(
                modelId = "model-a",
                slotId = "slot-1",
                keepAliveMs = 1_000L,
                sessionCacheIdentity = identity,
                strictGpuOffload = true,
            ),
        )

        assertEquals(0, inferenceModule.loadCalls)
        assertEquals(1, managedRuntime.loadCalls)
        assertEquals("model-a", managedRuntime.lastModelId)
        assertEquals("ud_iq2_xxs", managedRuntime.lastModelVersion)
        assertEquals(true, managedRuntime.lastStrictGpuOffload)
        assertEquals(identity, manager.listResident().single().sessionCacheIdentity)
    }
}

private class RecordingInferenceModule : InferenceModule {
    var loadCalls: Int = 0
    var unloadCalls: Int = 0

    override fun listAvailableModels(): List<String> = listOf("model-a")

    override fun loadModel(modelId: String): Boolean {
        loadCalls += 1
        return true
    }

    override fun generateStream(request: InferenceRequest, onToken: (String) -> Unit) = Unit

    override fun unloadModel() {
        unloadCalls += 1
    }
}

private class RecordingManagedRuntime : ManagedRuntimePort {
    var loadCalls: Int = 0
    var lastModelId: String? = null
    var lastModelVersion: String? = null
    var lastStrictGpuOffload: Boolean = false

    override fun loadModel(modelId: String, modelVersion: String?, strictGpuOffload: Boolean): Boolean {
        loadCalls += 1
        lastModelId = modelId
        lastModelVersion = modelVersion
        lastStrictGpuOffload = strictGpuOffload
        return true
    }

    override fun setRuntimeGenerationConfig(config: com.pocketagent.nativebridge.RuntimeGenerationConfig) = Unit

    override fun supportsGpuOffload(): Boolean = true

    override fun runtimeBackend(): RuntimeBackend = RuntimeBackend.NATIVE_JNI

    override fun lastBridgeError(): BridgeError? = null

    override fun currentModelLifecycleState(): ModelLifecycleEvent {
        return ModelLifecycleEvent(state = ModelLifecycleState.UNLOADED)
    }

    override fun observeModelLifecycleState(listener: (ModelLifecycleEvent) -> Unit): AutoCloseable {
        listener(currentModelLifecycleState())
        return AutoCloseable { }
    }

    override fun currentRssMb(): Double? = null

    override fun isRuntimeReleased(): Boolean = true
}

private fun advancingClock(): () -> Long {
    var tick = 1_000L
    return {
        tick += 10L
        tick
    }
}
