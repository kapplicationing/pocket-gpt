package com.pocketagent.android

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketagent.android.data.chat.AndroidSessionPersistence
import com.pocketagent.android.data.chat.StoredChatState
import com.pocketagent.android.runtime.DefaultAppForegroundRuntimeServices
import com.pocketagent.android.ui.ChatViewModel
import com.pocketagent.android.ui.ModelProvisioningViewModel
import com.pocketagent.android.ui.PocketAgentApp
import com.pocketagent.android.ui.controllers.ChatStreamCoordinator
import com.pocketagent.android.ui.state.FirstSessionStage
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.inference.ModelRuntimeProfile
import com.pocketagent.runtime.ModelRegistry
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealRuntimeJourneyInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun runCoreJourneyGate() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Skipping real-runtime journey lane. Set stage2_enable_journey_test=true to run.",
            parseBooleanArg(args, ARG_ENABLE_JOURNEY_TEST, defaultValue = false),
        )
        val primaryModelConfig = resolveJourneyPrimaryModelConfig(args)
        val modelPath2bRaw = args.getString(ARG_MODEL_PATH_1_7B)?.trim().orEmpty()
        assumeTrue(
            "Skipping real-runtime journey lane. Provide the primary model path " +
                "via journey_primary_model_path or stage2_model_0_8b_path, plus stage2_model_1_7b_path.",
            primaryModelConfig.modelPathRaw.isNotEmpty() && modelPath2bRaw.isNotEmpty(),
        )
        val primaryModelPath = requireFile(primaryModelConfig.modelPathRaw)
        val modelPath2b = requireFile(modelPath2bRaw)
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val journeyArtifactDir = args.getString(ARG_JOURNEY_ARTIFACT_DIR)?.trim().orEmpty()
        val artifactDir = journeyArtifactDir.takeIf { it.isNotBlank() }?.let { File(it).apply { mkdirs() } }
        val replyTimeoutSeconds = args.getString(ARG_REPLY_TIMEOUT_SECONDS)?.toLongOrNull()?.takeIf { it > 0L } ?: 90L
        val replyTimeoutMs = replyTimeoutSeconds * 1_000L
        val enableUiSendCapture = parseBooleanArg(args, ARG_ENABLE_UI_SEND_CAPTURE, defaultValue = false)
        val sendCapturePrompt = args.getString(ARG_SEND_CAPTURE_PROMPT)?.trim().orEmpty().ifBlank {
            DEFAULT_SEND_CAPTURE_PROMPT
        }
        val traceLines = mutableListOf<String>()
        var sendCaptureArtifact: JourneyUiSendCaptureArtifact? = null
        var diagnosticsText: String? = null
        var diagnosticsJson: String? = null

        fun trace(step: String, detail: String) {
            traceLines += "$step|$detail"
        }

        try {
            AppRuntimeDependencies.resetRuntimeFacadeFactoryForTests()
            trace(
                "config",
                "primary_model=${primaryModelConfig.modelId} primary_model_path_arg=${primaryModelConfig.modelPathArg} " +
                    "send_capture_routing=${primaryModelConfig.sendCaptureRoutingMode}",
            )
            val seededPrimary = AppRuntimeDependencies.seedModelFromAbsolutePath(
                context = appContext,
                modelId = primaryModelConfig.modelId,
                absolutePath = primaryModelPath,
            )
            trace("provision", "seeded ${primaryModelConfig.modelId}")

            val seeded2 = AppRuntimeDependencies.seedModelFromAbsolutePath(
                context = appContext,
                modelId = ModelCatalog.QWEN3_1_7B_Q4_K_M,
                absolutePath = modelPath2b,
            )
            trace("provision", "seeded ${ModelCatalog.QWEN3_1_7B_Q4_K_M}")

            assertTrue(
                "Failed to activate seeded primary model ${primaryModelConfig.modelId} version ${seededPrimary.version}.",
                AppRuntimeDependencies.setActiveVersion(
                    context = appContext,
                    modelId = primaryModelConfig.modelId,
                    version = seededPrimary.version,
                ).changed,
            )
            assertTrue(
                "Failed to activate seeded 1.7B version ${seeded2.version}.",
                AppRuntimeDependencies.setActiveVersion(
                    context = appContext,
                    modelId = ModelCatalog.QWEN3_1_7B_Q4_K_M,
                    version = seeded2.version,
                ).changed,
            )
            val snapshot = AppRuntimeDependencies.currentProvisioningSnapshot(appContext)
            val primaryState = snapshot.models.first { it.modelId == primaryModelConfig.modelId }
            val state2 = snapshot.models.first { it.modelId == ModelCatalog.QWEN3_1_7B_Q4_K_M }
            assertEquals(seededPrimary.version, primaryState.activeVersion)
            assertEquals(seeded2.version, state2.activeVersion)
            assertEquals(normalizePath(primaryModelPath), normalizePath(primaryState.absolutePath.orEmpty()))
            assertEquals(normalizePath(modelPath2b), normalizePath(state2.absolutePath.orEmpty()))
            trace(
                "provision",
                "active_primary=${primaryState.activeVersion} primary_model=${primaryModelConfig.modelId} " +
                    "active_2b=${state2.activeVersion}",
            )

            AppRuntimeDependencies.installProductionRuntime(appContext)
            val facade = AppRuntimeDependencies.runtimeFacadeFactory()

            val startupChecks = facade.runStartupChecks()
            trace("startup", startupChecks.joinToString("; ").ifBlank { "ok" })
            assertStartupChecksReadyWithOptionalWarnings(
                startupChecks = startupChecks,
                healthyModelIds = setOf(
                    primaryModelConfig.modelId,
                    ModelCatalog.QWEN3_1_7B_Q4_K_M,
                ),
                failurePrefix = "Real-runtime startup checks failed",
            )
            assertEquals("NATIVE_JNI", facade.runtimeBackend())

            val sessionId = facade.createSession()
            trace("session", "created ${sessionId.value}")
            assertTrue(sessionId.value.isNotBlank())

            facade.setRoutingMode(RoutingMode.QWEN3_1_7B)
            trace("routing", "set ${RoutingMode.QWEN3_1_7B}")
            assertEquals(RoutingMode.QWEN3_1_7B, facade.getRoutingMode())

            val toolResult = facade.runTool(
                toolName = "calculator",
                jsonArgs = """{"expression":"4*9"}""",
            )
            trace("tool", toolResult.take(80))
            assertTrue(toolResult.isNotBlank())

            val diagnostics = facade.exportDiagnostics()
            diagnosticsText = diagnostics
            diagnosticsJson = facade.exportDiagnosticsJson()
            trace("diagnostics", diagnostics.take(80))
            assertTrue(diagnostics.isNotBlank())

            if (enableUiSendCapture) {
                facade.setRoutingMode(primaryModelConfig.sendCaptureRoutingMode)
                trace(
                    "routing",
                    "set ${primaryModelConfig.sendCaptureRoutingMode} for ui send capture " +
                        "(${primaryModelConfig.modelId})",
                )
                sendCaptureArtifact = runCatching {
                    runUiSendCaptureProbe(
                        appContext = appContext,
                        modelId = primaryModelConfig.modelId,
                        modelVersion = seededPrimary.version,
                        prompt = sendCapturePrompt,
                        replyTimeoutMs = replyTimeoutMs,
                        artifactDir = artifactDir,
                        trace = ::trace,
                    )
                }.getOrElse { error ->
                    trace("ui_send", "error=${error.message ?: error::class.java.simpleName}")
                    JourneyUiSendCaptureArtifact(
                        phase = "error",
                        elapsedMs = 0L,
                        firstTokenMs = null,
                        runtimeStatus = "unknown",
                        backend = facade.runtimeBackend() ?: "unknown",
                        activeModelId = primaryModelConfig.modelId,
                        placeholderVisible = false,
                        failureSignature = error.message ?: error::class.java.simpleName,
                        screenshotPath = null,
                    )
                }
            } else {
                trace("send", "ui send capture disabled")
            }

            // Session continuity surface: restore turns and ensure delete path is healthy.
            facade.restoreSession(
                sessionId = sessionId,
                turns = listOf(
                    Turn(role = "user", content = "remember scenario c context", timestampEpochMs = 1L),
                    Turn(role = "assistant", content = "ack", timestampEpochMs = 2L),
                ),
            )
            trace("continuity", "restored turns into ${sessionId.value}")
            assertTrue(facade.deleteSession(sessionId))
            trace("session", "deleted ${sessionId.value}")
        } finally {
            if (artifactDir != null) {
                File(artifactDir, "journey-context.txt").writeText(
                    traceLines.joinToString(separator = "\n", postfix = "\n"),
                )
                diagnosticsText?.let { File(artifactDir, "journey-diagnostics.txt").writeText(it) }
                diagnosticsJson?.takeIf { it.isNotBlank() }?.let {
                    File(artifactDir, "journey-diagnostics.json").writeText(it)
                }
                sendCaptureArtifact?.let { artifact ->
                    File(artifactDir, JOURNEY_SEND_CAPTURE_FILE).writeText(
                        encodeSendCaptureArtifact(artifact),
                    )
                }
            }
        }
    }

    private suspend fun runUiSendCaptureProbe(
        appContext: android.content.Context,
        modelId: String,
        modelVersion: String,
        prompt: String,
        replyTimeoutMs: Long,
        artifactDir: File?,
        trace: (String, String) -> Unit,
    ): JourneyUiSendCaptureArtifact {
        val preloadResult = AppRuntimeDependencies.loadInstalledModel(
            context = appContext,
            modelId = modelId,
            version = modelVersion,
        )
        trace(
            "ui_bootstrap",
            "preload model=$modelId version=$modelVersion success=${preloadResult.success}",
        )
        val sessionPersistence = AndroidSessionPersistence(appContext)
        sessionPersistence.clearState()
        sessionPersistence.saveState(
            StoredChatState(
                onboardingCompleted = true,
                firstSessionStage = FirstSessionStage.ADVANCED_UNLOCKED.name,
                advancedUnlocked = true,
            ),
        )

        val services = DefaultAppForegroundRuntimeServices(appContext)
        services.warmUp()

        val viewModel = ChatViewModel(
            runtimeFacade = services.runtimeGateway,
            sessionPersistence = sessionPersistence,
            presetBackingStore = services.presetBackingStore,
            runtimeGenerationTimeoutMs = replyTimeoutMs,
            streamCoordinator = ChatStreamCoordinator(terminalWatchdogGraceMs = 0L),
        )
        trace("ui_timeout_budget", "request_timeout_ms=$replyTimeoutMs watchdog_grace_ms=0")
        val provisioningViewModel = ModelProvisioningViewModel(
            gateway = services.provisioningGateway,
            eligibilitySignalsProvider = services.eligibilitySignalsProvider,
        )

        composeRule.setContent {
            MaterialTheme {
                Surface {
                    PocketAgentApp(
                        viewModel = viewModel,
                        provisioningViewModel = provisioningViewModel,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        viewModel.handleCompletedModelOperation(preloadResult)
        viewModel.refreshRuntimeReadiness(statusDetailOverride = "Journey send-capture preload")
        composeRule.waitForIdle()

        bootstrapRuntimeIfNeeded(replyTimeoutMs = replyTimeoutMs, trace = trace)
        val backend = services.runtimeGateway.runtimeBackend() ?: "unknown"
        val activeModelId = resolveActiveModelId(appContext, services) ?: "unknown"
        if (hasNodeWithTag("runtime_error_banner")) {
            trace("ui_ready", "runtime_error_banner visible before send")
            return JourneyUiSendCaptureArtifact(
                phase = "error",
                elapsedMs = 0L,
                firstTokenMs = null,
                runtimeStatus = "Error",
                backend = backend,
                activeModelId = activeModelId,
                placeholderVisible = false,
                failureSignature = "runtime_error_banner_before_send",
                screenshotPath = artifactDir?.let {
                    captureJourneyScreenshot(
                        outputDir = it,
                        fileName = JOURNEY_SEND_CAPTURE_SCREENSHOT,
                    )
                },
            )
        }
        if (!hasSendButtonLabel("Send")) {
            trace("ui_ready", "send button never reached Send state")
            return JourneyUiSendCaptureArtifact(
                phase = "error",
                elapsedMs = 0L,
                firstTokenMs = null,
                runtimeStatus = "unknown",
                backend = backend,
                activeModelId = activeModelId,
                placeholderVisible = false,
                failureSignature = "send_button_not_ready",
                screenshotPath = artifactDir?.let {
                    captureJourneyScreenshot(
                        outputDir = it,
                        fileName = JOURNEY_SEND_CAPTURE_SCREENSHOT,
                    )
                },
            )
        }
        trace("ui_ready", "backend=$backend model=$activeModelId")

        val kickoff = composeRule.submitPromptAndAwaitSendKickoff(
            prompt = prompt,
            readyTimeoutMs = 5_000L,
            kickoffTimeoutMs = 5_000L,
            trace = { detail -> trace("ui_send_probe", detail) },
        )
        if (!kickoff.started) {
            trace("ui_send", kickoff.detail)
            return JourneyUiSendCaptureArtifact(
                phase = "error",
                elapsedMs = 0L,
                firstTokenMs = null,
                runtimeStatus = viewModel.uiState.value.runtime.modelRuntimeStatus.name,
                backend = backend,
                activeModelId = activeModelId,
                placeholderVisible = false,
                failureSignature = kickoff.detail.substringBefore(" {"),
                screenshotPath = artifactDir?.let {
                    captureJourneyScreenshot(
                        outputDir = it,
                        fileName = JOURNEY_SEND_CAPTURE_SCREENSHOT,
                    )
                },
                requestTimeoutMs = replyTimeoutMs,
                modelStatusDetail = viewModel.uiState.value.runtime.modelStatusDetail,
                sendSlowState = viewModel.uiState.value.runtime.sendSlowState,
                uiErrorUserMessage = viewModel.uiState.value.runtime.lastErrorUserMessage,
                uiErrorTechnicalDetail = viewModel.uiState.value.runtime.lastErrorTechnicalDetail,
            )
        }
        trace("ui_send", kickoff.detail)

        val sendStart = System.currentTimeMillis()
        var firstTokenMs: Long? = null
        var phase = "timeout"
        var failureSignature: String? = null

        while (System.currentTimeMillis() - sendStart <= replyTimeoutMs) {
            composeRule.waitForIdle()
            val pendingVisible = hasNodeWithTag("message_bubble_assistant_pending")
            val streamingVisible = hasNodeWithTag("message_bubble_assistant_streaming")
            val completedVisible = hasNodeWithTag("message_bubble_assistant_complete")
            val runtimeErrorVisible = hasNodeWithTag("runtime_error_banner")
            val visibleStreamingText = viewModel.uiState.value.streaming.text.trim()
            val assistantResponseVisible = completedVisible || visibleStreamingText.isNotBlank()
            val placeholderVisible = pendingVisible || (streamingVisible && visibleStreamingText.isBlank())

            if (firstTokenMs == null && assistantResponseVisible) {
                firstTokenMs = System.currentTimeMillis() - sendStart
                trace("ui_first_token", "latency_ms=$firstTokenMs")
            }
            if (completedVisible) {
                phase = "completed"
                break
            }
            if (runtimeErrorVisible) {
                phase = "error"
                val runtime = viewModel.uiState.value.runtime
                failureSignature = runtime.lastErrorTechnicalDetail
                    ?: runtime.lastErrorUserMessage
                    ?: "runtime_error_banner"
                break
            }
            if (placeholderVisible) {
                trace("ui_pending", "visible")
            }
            delay(250)
        }

        if (phase == "timeout" && firstTokenMs != null) {
            phase = "first_token"
        }
        val elapsedMs = System.currentTimeMillis() - sendStart
        composeRule.waitForIdle()
        val finalStreamingVisible = hasNodeWithTag("message_bubble_assistant_streaming")
        val finalPendingVisible = hasNodeWithTag("message_bubble_assistant_pending")
        val finalStreamingText = viewModel.uiState.value.streaming.text.trim()
        val placeholderVisible = finalPendingVisible || (finalStreamingVisible && finalStreamingText.isBlank())
        val finalRuntime = viewModel.uiState.value.runtime
        val screenshotPath = artifactDir?.let {
            captureJourneyScreenshot(
                outputDir = it,
                fileName = JOURNEY_SEND_CAPTURE_SCREENSHOT,
            )
        }
        trace(
            "ui_complete",
            "phase=$phase elapsed_ms=$elapsedMs placeholder=$placeholderVisible " +
                "runtime_status=${finalRuntime.modelRuntimeStatus} detail=${finalRuntime.modelStatusDetail.orEmpty()} " +
                "error=${finalRuntime.lastErrorTechnicalDetail ?: finalRuntime.lastErrorUserMessage.orEmpty()}",
        )
        return JourneyUiSendCaptureArtifact(
            phase = phase,
            elapsedMs = elapsedMs,
            firstTokenMs = firstTokenMs,
            runtimeStatus = finalRuntime.modelRuntimeStatus.name,
            backend = backend,
            activeModelId = activeModelId,
            placeholderVisible = placeholderVisible,
            failureSignature = failureSignature,
            screenshotPath = screenshotPath,
            requestTimeoutMs = replyTimeoutMs,
            modelStatusDetail = finalRuntime.modelStatusDetail,
            sendSlowState = finalRuntime.sendSlowState,
            uiErrorUserMessage = finalRuntime.lastErrorUserMessage,
            uiErrorTechnicalDetail = finalRuntime.lastErrorTechnicalDetail,
        )
    }

    private suspend fun bootstrapRuntimeIfNeeded(
        replyTimeoutMs: Long,
        trace: (String, String) -> Unit,
    ) {
        composeRule.waitUntil(timeoutMillis = 30_000) {
            hasNodeWithTag("composer_input") && hasNodeWithTag("send_button")
        }
        repeat(6) { attempt ->
            composeRule.waitForIdle()
            if (hasSendButtonLabel("Send") || hasNodeWithTag("runtime_error_banner")) {
                return
            }
            when {
                hasSendButtonLabel("Setup") -> {
                    trace("ui_bootstrap", "attempt=$attempt action=setup")
                    composeRule.onNodeWithTag("send_button").performClick()
                }

                hasSendButtonLabel("Refresh") -> {
                    trace("ui_bootstrap", "attempt=$attempt action=refresh_runtime_checks")
                    composeRule.onNodeWithTag("send_button").performClick()
                }

                hasNodeWithText("Load last used", substring = true) -> {
                    trace("ui_bootstrap", "attempt=$attempt action=load_last_used")
                    composeRule.onAllNodesWithText("Load last used", substring = true).onFirst().performClick()
                }

                hasNodeWithText("Load") -> {
                    trace("ui_bootstrap", "attempt=$attempt action=load")
                    composeRule.onAllNodesWithText("Load").onFirst().performClick()
                }

                hasNodeWithText("Close") && hasNodeWithText("Model library") -> {
                    trace("ui_bootstrap", "attempt=$attempt action=close_model_library")
                    composeRule.onAllNodesWithText("Close").onFirst().performClick()
                }
            }
            composeRule.waitUntil(timeoutMillis = replyTimeoutMs) {
                hasSendButtonLabel("Send") ||
                    hasSendButtonLabel("Refresh") ||
                    hasSendButtonLabel("Setup") ||
                    hasNodeWithText("Load last used", substring = true) ||
                    hasNodeWithText("Load") ||
                    hasNodeWithText("Model library") ||
                    hasNodeWithTag("runtime_error_banner")
            }
            delay(250)
        }
    }

    private fun resolveActiveModelId(
        appContext: android.content.Context,
        services: DefaultAppForegroundRuntimeServices,
    ): String? {
        val loadedModel = services.runtimeGateway.loadedModel()?.modelId
        if (!loadedModel.isNullOrBlank()) {
            return loadedModel
        }
        val snapshot = AppRuntimeDependencies.currentProvisioningSnapshot(appContext)
        return snapshot.models.firstOrNull { !it.activeVersion.isNullOrBlank() }?.modelId
    }

    private fun captureJourneyScreenshot(
        outputDir: File,
        fileName: String,
    ): String? {
        return runCatching {
            composeRule.waitForIdle()
            outputDir.mkdirs()
            val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
            val outputFile = File(outputDir, fileName)
            FileOutputStream(outputFile).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            outputFile.name
        }.getOrNull()
    }

    private fun encodeSendCaptureArtifact(artifact: JourneyUiSendCaptureArtifact): String {
        val payload = buildJsonObject {
            put("phase", JsonPrimitive(artifact.phase))
            put("elapsed_ms", JsonPrimitive(artifact.elapsedMs))
            artifact.firstTokenMs?.let { put("first_token_ms", JsonPrimitive(it)) }
            put("runtime_status", JsonPrimitive(artifact.runtimeStatus))
            put("backend", JsonPrimitive(artifact.backend))
            put("active_model_id", JsonPrimitive(artifact.activeModelId))
            put("placeholder_visible", JsonPrimitive(artifact.placeholderVisible))
            artifact.failureSignature?.let { put("failure_signature", JsonPrimitive(it)) }
            artifact.screenshotPath?.let { put("screenshot_path", JsonPrimitive(it)) }
            artifact.requestTimeoutMs?.let { put("request_timeout_ms", JsonPrimitive(it)) }
            artifact.modelStatusDetail?.let { put("model_status_detail", JsonPrimitive(it)) }
            artifact.sendSlowState?.let { put("send_slow_state", JsonPrimitive(it)) }
            artifact.uiErrorUserMessage?.let { put("ui_error_user_message", JsonPrimitive(it)) }
            artifact.uiErrorTechnicalDetail?.let { put("ui_error_technical_detail", JsonPrimitive(it)) }
        }
        return Json.encodeToString(JsonObject.serializer(), payload)
    }

    private fun hasNodeWithTag(tag: String): Boolean {
        return runCatching {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }.getOrDefault(false)
    }

    private fun hasNodeWithText(
        text: String,
        substring: Boolean = false,
    ): Boolean {
        return runCatching {
            composeRule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }.getOrDefault(false)
    }

    private fun hasSendButtonLabel(label: String): Boolean {
        return runCatching {
            composeRule.onAllNodesWithTag("send_button")
                .fetchSemanticsNodes()
                .any { node ->
                    if (SemanticsProperties.Text !in node.config) {
                        false
                    } else {
                        node.config[SemanticsProperties.Text].any { annotated -> annotated.text == label }
                    }
                }
        }.getOrDefault(false)
    }

    private fun requireFile(value: String): String {
        val resolved = resolveStage2ModelPath(value) ?: resolveModelPath(value)
        require(resolved != null) { "Model path does not exist: $value" }
        return resolved
    }

    private fun resolveModelPath(value: String): String? {
        val normalized = value.trim()
        if (normalized.isEmpty()) {
            return null
        }
        val fileName = File(normalized).name
        val candidates = buildList {
            add(normalized)
            add(normalized.replace("/sdcard/", "/storage/emulated/0/"))
            add(normalized.replace("/storage/emulated/0/", "/sdcard/"))
            if (fileName.isNotEmpty()) {
                val modelRoots = listOf(
                    "/sdcard/Android/media/com.pocketagent.android/models",
                    "/storage/emulated/0/Android/media/com.pocketagent.android/models",
                    "/sdcard/Download/com.pocketagent.android/models",
                    "/storage/emulated/0/Download/com.pocketagent.android/models",
                )
                modelRoots.forEach { root ->
                    add("$root/$fileName")
                }
            }
        }.distinct()
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

    private fun resolveJourneyPrimaryModelConfig(
        args: android.os.Bundle,
    ): JourneyPrimaryModelConfig {
        val modelId = resolveJourneyPrimaryModelId(args)
        val explicitPath = args.getString(ARG_PRIMARY_MODEL_PATH)?.trim().orEmpty()
        val legacyPath = args.getString(ARG_MODEL_PATH_0_8B)?.trim().orEmpty()
        val modelPathArg = if (explicitPath.isNotEmpty()) ARG_PRIMARY_MODEL_PATH else ARG_MODEL_PATH_0_8B
        val modelPathRaw = explicitPath.ifBlank { legacyPath }
        return JourneyPrimaryModelConfig(
            modelId = modelId,
            modelPathRaw = modelPathRaw,
            modelPathArg = modelPathArg,
            sendCaptureRoutingMode = resolveJourneySendCaptureRoutingMode(args, modelId),
        )
    }

    private fun resolveJourneyPrimaryModelId(
        args: android.os.Bundle,
    ): String {
        val raw = args.getString(ARG_PRIMARY_MODEL_ID)?.trim().orEmpty()
        if (raw.isBlank()) {
            return DEFAULT_PRIMARY_MODEL_ID
        }
        val normalized = when (raw.uppercase()) {
            MODEL_ID_ALIAS_QWEN_0_8B -> ModelCatalog.QWEN_3_5_0_8B_Q4
            MODEL_ID_ALIAS_QWEN3_0_6B -> ModelCatalog.QWEN3_0_6B_Q4_K_M
            MODEL_ID_ALIAS_QWEN3_1_7B -> ModelCatalog.QWEN3_1_7B_Q4_K_M
            MODEL_ID_ALIAS_LLAMA_3_2_1B -> ModelCatalog.LLAMA_3_2_1B_Q4_K_M
            MODEL_ID_ALIAS_LAUNCH_DEFAULT -> resolveLaunchDefaultJourneyPrimaryModelId()
            else -> raw
        }
        require(ModelCatalog.descriptorFor(normalized) != null) {
            "Unsupported journey primary model id: $raw"
        }
        return normalized
    }

    private fun resolveLaunchDefaultJourneyPrimaryModelId(): String {
        return requireNotNull(
            ModelRegistry.default().defaultGetReadyModelId(profile = ModelRuntimeProfile.PROD),
        ) {
            "No launch-default PROD get-ready model is configured."
        }
    }

    private fun resolveJourneySendCaptureRoutingMode(
        args: android.os.Bundle,
        primaryModelId: String,
    ): RoutingMode {
        val raw = args.getString(ARG_SEND_CAPTURE_ROUTING_MODE)?.trim().orEmpty()
        if (raw.isBlank()) {
            return ModelCatalog.primaryExplicitRoutingMode(primaryModelId) ?: RoutingMode.AUTO
        }
        return runCatching { RoutingMode.valueOf(raw.uppercase()) }
            .getOrElse { error("Unsupported journey send-capture routing mode: $raw") }
    }

    private data class JourneyUiSendCaptureArtifact(
        val phase: String,
        val elapsedMs: Long,
        val firstTokenMs: Long?,
        val runtimeStatus: String,
        val backend: String,
        val activeModelId: String,
        val placeholderVisible: Boolean,
        val failureSignature: String? = null,
        val screenshotPath: String? = null,
        val requestTimeoutMs: Long? = null,
        val modelStatusDetail: String? = null,
        val sendSlowState: String? = null,
        val uiErrorUserMessage: String? = null,
        val uiErrorTechnicalDetail: String? = null,
    )

    private data class JourneyPrimaryModelConfig(
        val modelId: String,
        val modelPathRaw: String,
        val modelPathArg: String,
        val sendCaptureRoutingMode: RoutingMode,
    )

    companion object {
        private const val ARG_ENABLE_JOURNEY_TEST = "stage2_enable_journey_test"
        private const val ARG_MODEL_PATH_0_8B = "stage2_model_0_8b_path"
        private const val ARG_MODEL_PATH_1_7B = "stage2_model_1_7b_path"
        private const val ARG_JOURNEY_ARTIFACT_DIR = "journey_artifact_dir"
        private const val ARG_REPLY_TIMEOUT_SECONDS = "journey_reply_timeout_seconds"
        private const val ARG_ENABLE_UI_SEND_CAPTURE = "journey_enable_ui_send_capture"
        private const val ARG_SEND_CAPTURE_PROMPT = "journey_prompt"
        private const val ARG_PRIMARY_MODEL_ID = "journey_primary_model_id"
        private const val ARG_PRIMARY_MODEL_PATH = "journey_primary_model_path"
        private const val ARG_SEND_CAPTURE_ROUTING_MODE = "journey_send_capture_routing_mode"
        private const val DEFAULT_PRIMARY_MODEL_ID = ModelCatalog.QWEN_3_5_0_8B_Q4
        private const val DEFAULT_SEND_CAPTURE_PROMPT = "Reply with exactly: OK."
        private const val JOURNEY_SEND_CAPTURE_FILE = "journey-send-capture.json"
        private const val JOURNEY_SEND_CAPTURE_SCREENSHOT = "journey-send-capture-final.png"
        private const val MODEL_ID_ALIAS_QWEN_0_8B = "QWEN_3_5_0_8B_Q4"
        private const val MODEL_ID_ALIAS_QWEN3_0_6B = "QWEN3_0_6B_Q4_K_M"
        private const val MODEL_ID_ALIAS_QWEN3_1_7B = "QWEN3_1_7B_Q4_K_M"
        private const val MODEL_ID_ALIAS_LLAMA_3_2_1B = "LLAMA_3_2_1B_Q4_K_M"
        private const val MODEL_ID_ALIAS_LAUNCH_DEFAULT = "LAUNCH_DEFAULT"
    }
}
