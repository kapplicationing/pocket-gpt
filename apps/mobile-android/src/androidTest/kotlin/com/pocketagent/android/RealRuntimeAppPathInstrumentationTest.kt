package com.pocketagent.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketagent.android.runtime.RuntimeModelImportResult
import com.pocketagent.core.RoutingMode
import com.pocketagent.inference.DeviceState
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.InteractionContentPart
import com.pocketagent.runtime.InteractionMessage
import com.pocketagent.runtime.InteractionRole
import com.pocketagent.runtime.StreamChatRequestV2
import java.io.File
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealRuntimeAppPathInstrumentationTest {
    @Test
    fun appPathNativeRuntimeFlowProducesResponse() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Skipping real-runtime app-path lane. Set stage2_enable_app_path_test=true for RC app-path validation.",
            parseBooleanArg(args, ARG_ENABLE_APP_PATH_TEST, defaultValue = false),
        )
        val modelPath0_8bRaw = args.getString(ARG_MODEL_PATH_0_8B)?.trim().orEmpty()
        val modelPath2bRaw = args.getString(ARG_MODEL_PATH_1_7B)?.trim().orEmpty()
        assumeTrue(
            "Skipping real-runtime app-path lane. Provide both model paths via instrumentation arguments.",
            modelPath0_8bRaw.isNotEmpty() && modelPath2bRaw.isNotEmpty(),
        )
        val modelPath0_8b = requireFile(modelPath0_8bRaw)
        val modelPath2b = requireFile(modelPath2bRaw)
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext

        AppRuntimeDependencies.resetRuntimeFacadeFactoryForTests()
        val seeded0 = AppRuntimeDependencies.seedModelFromAbsolutePath(
            context = appContext,
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            absolutePath = modelPath0_8b,
        )
        val seeded2 = AppRuntimeDependencies.seedModelFromAbsolutePath(
            context = appContext,
            modelId = ModelCatalog.QWEN3_1_7B_Q4_K_M,
            absolutePath = modelPath2b,
        )
        assertTrue(
            "Failed to activate seeded 0.8B version ${seeded0.version}.",
            AppRuntimeDependencies.setActiveVersion(
                context = appContext,
                modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
                version = seeded0.version,
            ),
        )
        assertTrue(
            "Failed to activate seeded 1.7B version ${seeded2.version}.",
            AppRuntimeDependencies.setActiveVersion(
                context = appContext,
                modelId = ModelCatalog.QWEN3_1_7B_Q4_K_M,
                version = seeded2.version,
            ),
        )
        val snapshot = AppRuntimeDependencies.currentProvisioningSnapshot(appContext)
        val state0 = snapshot.models.first { it.modelId == ModelCatalog.QWEN_3_5_0_8B_Q4 }
        val state2 = snapshot.models.first { it.modelId == ModelCatalog.QWEN3_1_7B_Q4_K_M }
        assertEquals(seeded0.version, state0.activeVersion)
        assertEquals(seeded2.version, state2.activeVersion)
        assertEquals(normalizePath(modelPath0_8b), normalizePath(state0.absolutePath.orEmpty()))
        assertEquals(normalizePath(modelPath2b), normalizePath(state2.absolutePath.orEmpty()))
        AppRuntimeDependencies.installProductionRuntime(appContext)

        val facade = AppRuntimeDependencies.runtimeFacadeFactory()
        val startupChecks = facade.runStartupChecks()
        assertStartupChecksReadyWithOptionalWarnings(
            startupChecks = startupChecks,
            healthyModelIds = setOf(
                ModelCatalog.QWEN_3_5_0_8B_Q4,
                ModelCatalog.QWEN3_1_7B_Q4_K_M,
            ),
            failurePrefix = "App-path startup checks failed",
        )
        assertEquals("NATIVE_JNI", facade.runtimeBackend())

        val sessionId = facade.createSession()
        facade.setRoutingMode(RoutingMode.QWEN_0_8B)
        var completed: ChatStreamEvent.Completed? = null
        facade.streamChat(
            chatRequest(
                sessionId = sessionId,
                userText = "Give one short sentence proving app-path native inference.",
                requestTimeoutMs = 300_000L,
            ),
        ).collect { event ->
            if (event is ChatStreamEvent.Completed) {
                completed = event
            }
        }

        val response = completed?.response
        assertNotNull("Expected completed chat response from real runtime lane.", response)
        assertTrue(response?.text?.isNotBlank() == true)
        assertEquals(ModelCatalog.QWEN_3_5_0_8B_Q4, response?.modelId)
    }

    @Test
    fun appPathExplicitSmolRoutingProducesSmolResponse() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Skipping SmolLM app-path lane. Set stage2_enable_app_path_test=true for RC app-path validation.",
            parseBooleanArg(args, ARG_ENABLE_APP_PATH_TEST, defaultValue = false),
        )
        val modelPath0_8bRaw = args.getString(ARG_MODEL_PATH_0_8B)?.trim().orEmpty()
        assumeTrue(
            "Skipping SmolLM app-path lane. Provide a 0.8B model path via instrumentation arguments.",
            modelPath0_8bRaw.isNotEmpty(),
        )
        val smol3Q4Raw = args.getString(ARG_MODEL_PATH_SMOLLM3_Q4)?.trim().orEmpty()
        val smol3DraftRaw = args.getString(ARG_MODEL_PATH_SMOLLM3_DRAFT)?.trim().orEmpty()
        assumeTrue(
            "Skipping SmolLM app-path lane. Provide at least one SmolLM model path via instrumentation arguments.",
            smol3Q4Raw.isNotEmpty() || smol3DraftRaw.isNotEmpty(),
        )
        val smolTarget = when {
            smol3Q4Raw.isNotEmpty() -> SmolTarget(
                modelId = ModelCatalog.QWEN3_1_7B_Q4_K_M,
                modelPath = requireFile(smol3Q4Raw),
                routingMode = RoutingMode.QWEN3_1_7B,
            )
            else -> SmolTarget(
                modelId = ModelCatalog.QWEN3_1_7B_Q4_K_M,
                modelPath = requireFile(smol3DraftRaw),
                routingMode = RoutingMode.QWEN3_1_7B,
            )
        }
        val modelPath0_8b = requireFile(modelPath0_8bRaw)
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext

        AppRuntimeDependencies.resetRuntimeFacadeFactoryForTests()
        val seeded0 = seedAndActivateModel(
            appContext = appContext,
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            absolutePath = modelPath0_8b,
        )
        val seededSmol = seedAndActivateModel(
            appContext = appContext,
            modelId = smolTarget.modelId,
            absolutePath = smolTarget.modelPath,
        )

        val snapshot = AppRuntimeDependencies.currentProvisioningSnapshot(appContext)
        val state0 = snapshot.models.first { it.modelId == ModelCatalog.QWEN_3_5_0_8B_Q4 }
        val stateSmol = snapshot.models.first { it.modelId == smolTarget.modelId }
        assertEquals(seeded0.version, state0.activeVersion)
        assertEquals(seededSmol.version, stateSmol.activeVersion)
        assertEquals(normalizePath(modelPath0_8b), normalizePath(state0.absolutePath.orEmpty()))
        assertEquals(normalizePath(smolTarget.modelPath), normalizePath(stateSmol.absolutePath.orEmpty()))
        AppRuntimeDependencies.installProductionRuntime(appContext)

        val facade = AppRuntimeDependencies.runtimeFacadeFactory()
        val startupChecks = facade.runStartupChecks()
        assertTrue(
            "SmolLM app-path startup checks contain a backend-unavailable failure: ${startupChecks.joinToString()}",
            startupChecks.none { check ->
                val normalized = check.lowercase()
                normalized.contains("runtime backend is unavailable") ||
                    normalized.contains("runtime backend is adb_fallback")
            },
        )
        assertEquals("NATIVE_JNI", facade.runtimeBackend())

        val sessionId = facade.createSession()
        facade.setRoutingMode(smolTarget.routingMode)
        var completed: ChatStreamEvent.Completed? = null
        facade.streamChat(
            chatRequest(
                sessionId = sessionId,
                userText = "Reply with one short sentence proving explicit SmolLM routing is active.",
                requestTimeoutMs = 300_000L,
                deviceState = DeviceState(batteryPercent = 83, thermalLevel = 3, ramClassGb = 8),
            ),
        ).collect { event ->
            if (event is ChatStreamEvent.Completed) {
                completed = event
            }
        }

        val response = completed?.response
        assertNotNull("Expected completed SmolLM chat response from real runtime lane.", response)
        assertTrue(response?.text?.isNotBlank() == true)
        assertEquals(
            "Expected explicit SmolLM routing to execute the requested SmolLM model, not fallback.",
            smolTarget.modelId,
            response?.modelId,
        )
    }

    @Test
    fun appPathExplicitBonsaiRoutingProducesBonsaiResponse() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Skipping Bonsai app-path lane. Set stage2_enable_app_path_test=true for RC app-path validation.",
            parseBooleanArg(args, ARG_ENABLE_APP_PATH_TEST, defaultValue = false),
        )
        val bonsaiModelId = args.getString(ARG_BONSAI_MODEL_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: ModelCatalog.LLAMA_3_2_1B_Q4_K_M
        val modelPath0_8bRaw = args.getString(ARG_MODEL_PATH_0_8B)?.trim().orEmpty()
        val modelPathBonsaiRaw = args.getString(ARG_MODEL_PATH_BONSAI)?.trim().orEmpty()
        assumeTrue(
            "Skipping Bonsai app-path lane. Provide both a 0.8B baseline path and a Bonsai path via instrumentation arguments.",
            modelPath0_8bRaw.isNotEmpty() && modelPathBonsaiRaw.isNotEmpty(),
        )
        val modelPath0_8b = requireFile(modelPath0_8bRaw)
        val modelPathBonsai = requireFile(modelPathBonsaiRaw)
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext

        AppRuntimeDependencies.resetRuntimeFacadeFactoryForTests()
        val seeded0 = seedAndActivateModel(
            appContext = appContext,
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            absolutePath = modelPath0_8b,
        )
        val seededBonsai = seedAndActivateModel(
            appContext = appContext,
            modelId = bonsaiModelId,
            absolutePath = modelPathBonsai,
        )

        val snapshot = AppRuntimeDependencies.currentProvisioningSnapshot(appContext)
        val state0 = snapshot.models.first { it.modelId == ModelCatalog.QWEN_3_5_0_8B_Q4 }
        val stateBonsai = snapshot.models.first { it.modelId == bonsaiModelId }
        assertEquals(seeded0.version, state0.activeVersion)
        assertEquals(seededBonsai.version, stateBonsai.activeVersion)
        assertEquals(normalizePath(modelPath0_8b), normalizePath(state0.absolutePath.orEmpty()))
        assertEquals(normalizePath(modelPathBonsai), normalizePath(stateBonsai.absolutePath.orEmpty()))
        AppRuntimeDependencies.installProductionRuntime(appContext)

        val facade = AppRuntimeDependencies.runtimeFacadeFactory()
        val startupChecks = facade.runStartupChecks()
        assertTrue(
            "Bonsai app-path startup checks contain a backend-unavailable failure: ${startupChecks.joinToString()}",
            startupChecks.none { check ->
                val normalized = check.lowercase()
                normalized.contains("runtime backend is unavailable") ||
                    normalized.contains("runtime backend is adb_fallback")
            },
        )
        assertEquals("NATIVE_JNI", facade.runtimeBackend())
        val resourceControl = facade as com.pocketagent.runtime.RuntimeResourceControl
        val loadResult = resourceControl.loadModel(
            modelId = bonsaiModelId,
            modelVersion = seededBonsai.version,
        )
        assertTrue(
            "Expected Bonsai app-path load to succeed, but got detail: ${loadResult.detail}",
            loadResult.success,
        )
        assertEquals(
            bonsaiModelId,
            resourceControl.loadedModel()?.modelId,
        )
    }

    private suspend fun seedAndActivateModel(
        appContext: android.content.Context,
        modelId: String,
        absolutePath: String,
    ): RuntimeModelImportResult {
        val seeded = AppRuntimeDependencies.seedModelFromAbsolutePath(
            context = appContext,
            modelId = modelId,
            absolutePath = absolutePath,
        )
        assertTrue(
            "Failed to activate seeded $modelId version ${seeded.version}.",
            AppRuntimeDependencies.setActiveVersion(
                context = appContext,
                modelId = modelId,
                version = seeded.version,
            ),
        )
        return seeded
    }

    private data class SmolTarget(
        val modelId: String,
        val modelPath: String,
        val routingMode: RoutingMode,
    )

    private fun requireFile(value: String): String {
        val resolved = resolveModelPath(value)
        require(resolved != null) { "Model path does not exist: $value" }
        return resolved
    }

    private fun resolveModelPath(value: String): String? {
        val normalized = value.trim()
        if (normalized.isEmpty()) {
            return null
        }
        val candidates = listOf(
            normalized,
            normalized.replace("/sdcard/", "/storage/emulated/0/"),
            normalized.replace("/storage/emulated/0/", "/sdcard/"),
        )
        return candidates
            .asSequence()
            .map { candidate -> File(candidate) }
            .firstOrNull(::isReadableRegularFile)
            ?.absolutePath
    }

    private fun isReadableRegularFile(file: File): Boolean {
        if (!file.exists() || !file.isFile) {
            return false
        }
        return runCatching {
            file.inputStream().use { input -> input.read() }
            true
        }.getOrDefault(false)
    }

    private fun normalizePath(value: String): String {
        val canonical = runCatching { File(value).canonicalPath }.getOrElse { File(value).absolutePath }
        return canonical
            .replace("/sdcard/", "/storage/emulated/0/")
            .replace("/storage/self/primary/", "/storage/emulated/0/")
    }

    private fun parseBooleanArg(
        args: android.os.Bundle,
        key: String,
        defaultValue: Boolean,
    ): Boolean {
        val raw = args.getString(key)?.trim()?.lowercase() ?: return defaultValue
        return when (raw) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> defaultValue
        }
    }

    companion object {
        private const val ARG_ENABLE_APP_PATH_TEST = "stage2_enable_app_path_test"
        private const val ARG_MODEL_PATH_0_8B = "stage2_model_0_8b_path"
        private const val ARG_MODEL_PATH_1_7B = "stage2_model_1_7b_path"
        private const val ARG_MODEL_PATH_BONSAI = "stage2_model_bonsai_path"
        private const val ARG_BONSAI_MODEL_ID = "stage2_model_bonsai_id"
        private const val ARG_MODEL_PATH_SMOLLM3_Q4 = "stage2_model_smol_360m_path"
        private const val ARG_MODEL_PATH_SMOLLM3_DRAFT = "stage2_model_smol_135m_path"
    }
}

private fun chatRequest(
    sessionId: com.pocketagent.core.SessionId,
    userText: String,
    requestTimeoutMs: Long,
    deviceState: DeviceState = DeviceState(batteryPercent = 85, thermalLevel = 3, ramClassGb = 8),
): StreamChatRequestV2 {
    return StreamChatRequestV2(
        sessionId = sessionId,
        messages = listOf(
            InteractionMessage(
                role = InteractionRole.USER,
                parts = listOf(InteractionContentPart.Text(userText)),
            ),
        ),
        taskType = "short_text",
        deviceState = deviceState,
        maxTokens = 32,
        requestTimeoutMs = requestTimeoutMs,
    )
}
