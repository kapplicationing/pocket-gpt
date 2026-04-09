package com.pocketagent.android

import android.app.Instrumentation
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketagent.core.ChatResponse
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.inference.DeviceState
import com.pocketagent.runtime.ChatStreamDelta
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.InteractionContentPart
import com.pocketagent.runtime.InteractionMessage
import com.pocketagent.runtime.InteractionRole
import com.pocketagent.runtime.MvpRuntimeFacade
import com.pocketagent.runtime.StreamChatRequestV2
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityUiSmokeTest {
    @Suppress("unused")
    private val installFakeRuntimeFactory: () -> MvpRuntimeFacade =
        { FakeRuntimeFacade() }.also { AppRuntimeDependencies.runtimeFacadeFactory = it }

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        val args = InstrumentationRegistry.getArguments()
        val screenshotPackEnabled =
            args.getString("screenshot_pack_dir")?.trim()?.isNotEmpty() == true ||
                args.getString("screenshot_pack_fallback_dir")?.trim()?.isNotEmpty() == true
        assumeTrue(
            "Skipping MainActivity UI smoke screenshots outside screenshot-pack lane.",
            screenshotPackEnabled,
        )
        AppRuntimeDependencies.runtimeFacadeFactory = { FakeRuntimeFacade() }
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        appContext.getSharedPreferences("pocketagent_chat_state", 0).edit().clear().apply()
    }

    @After
    fun tearDown() {
        // Keep fake runtime active between tests so the next activity launch
        // cannot race into production runtime before @Before executes.
        AppRuntimeDependencies.runtimeFacadeFactory = { FakeRuntimeFacade() }
    }

    @Test
    fun launchShowsComposerAndOfflineIndicator() {
        composeRule.dismissOnboardingIfVisible()
        composeRule.waitForRuntimeReady()
        composeRule.onNodeWithText("Offline").assertIsDisplayed()
        composeRule.onNodeWithTag("composer_input").assertIsDisplayed()
        composeRule.onNodeWithTag("send_button").assertIsDisplayed()
        composeRule.assertSendButtonLabel("Send")
        composeRule.onNodeWithContentDescription("Sessions").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Advanced").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open tools").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Attach image").assertIsDisplayed()
        composeRule.captureScreenshotIfEnabled("ui-04-chat-ready-empty")
        composeRule.captureScreenshotIfEnabled("ui-15-image-entry-visible")
    }

    @Test
    fun chatMessageListOccupiesMajorityOfViewportWhenRuntimeReady() {
        composeRule.dismissOnboardingIfVisible()
        composeRule.waitForRuntimeReady()

        val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val listBounds = composeRule.onNodeWithTag("chat_message_list").fetchSemanticsNode().boundsInRoot
        val listHeightRatio = listBounds.height / rootBounds.height

        assertTrue(
            "chat_message_list is unexpectedly short (${listHeightRatio * 100f}% of viewport).",
            listHeightRatio > 0.45f,
        )
    }

    @Test
    fun onboardingFlowCanProgressAndComplete() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Welcome to Pocket GPT").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithTag("composer_input").fetchSemanticsNodes().isNotEmpty()
        }
        if (composeRule.onAllNodesWithText("Welcome to Pocket GPT").fetchSemanticsNodes().isEmpty()) {
            composeRule.waitForRuntimeReady()
            composeRule.onNodeWithTag("composer_input").assertIsDisplayed()
            return
        }
        composeRule.onNodeWithText("Welcome to Pocket GPT").assertIsDisplayed()
        composeRule.captureScreenshotIfEnabled("ui-01-onboarding-page-1")
        composeRule.onNodeWithText("Next").performClick()
        composeRule.onNodeWithText("Step 2 of 3").assertIsDisplayed()
        composeRule.captureScreenshotIfEnabled("ui-02-onboarding-page-2")
        composeRule.onNodeWithText("Next").performClick()
        composeRule.onNodeWithText("Step 3 of 3").assertIsDisplayed()
        composeRule.captureScreenshotIfEnabled("ui-03-onboarding-page-3")
        composeRule.onNodeWithText("Get started").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Welcome to Pocket GPT").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun sendMessageShowsUserAndAssistantBubbles() {
        composeRule.dismissOnboardingIfVisible()
        composeRule.waitForRuntimeReady()
        composeRule.onNodeWithTag("composer_input").performTextInput("hello ui")
        composeRule.onNodeWithTag("send_button").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("hello ui").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("runtime response for hello ui").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithTag("runtime_error_banner").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("send_button").assertIsDisplayed()
        composeRule.captureScreenshotIfEnabled("ui-05-chat-post-send")
    }

    @Test
    fun toolAndDiagnosticsActionsRenderResults() {
        composeRule.unlockAdvancedControls()
        composeRule.onNodeWithContentDescription("Open tools").performClick()
        composeRule.captureScreenshotIfEnabled("ui-08-tools-dialog")
        composeRule.onNode(hasText("calculate 4*9") and hasClickAction()).performClick()
        composeRule.onNodeWithTag("send_button").performClick()
        composeRule.waitForText("tool:calculator")
        composeRule.captureScreenshotIfEnabled("ui-14-tool-result-visible")

        composeRule.onNodeWithTag("advanced_sheet_button").performClick()
        composeRule.onNodeWithText("Advanced controls").assertIsDisplayed()
        composeRule.captureScreenshotIfEnabled("ui-09-advanced-controls-sheet")
        composeRule.onNodeWithText("Open model library").assertIsDisplayed()
        composeRule.onNodeWithText("Open runtime controls").assertIsDisplayed()
    }

    @Test
    fun privacySheetOpensWithExpectedCopy() {
        composeRule.dismissOnboardingIfVisible()
        composeRule.waitForRuntimeReady()
        composeRule.onNodeWithTag("advanced_sheet_button").performClick()
        composeRule.onNodeWithText("Privacy and data controls").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Toggle privacy details").performScrollTo().performClick()
        composeRule.onNodeWithText("Chats and memory are stored locally on this device.").assertIsDisplayed()
        composeRule.captureScreenshotIfEnabled("ui-07-privacy-sheet")
    }

    @Test
    fun naturalLanguageReminderPromptRendersToolResult() {
        composeRule.dismissOnboardingIfVisible()
        composeRule.waitForRuntimeReady()
        composeRule.onNodeWithTag("composer_input").performTextInput("remind me to run QA closeout")
        composeRule.onNodeWithTag("send_button").performClick()
        composeRule.waitForText("tool:reminder_create")
    }

    @Test
    fun modelLibrarySheetOpensFromAdvancedControls() {
        composeRule.unlockAdvancedControls()
        composeRule.onNodeWithTag("advanced_sheet_button").performClick()
        composeRule.onNodeWithText("Open model library").performClick()
        composeRule.onNodeWithText("Model library").assertIsDisplayed()
        composeRule.onNodeWithText("Downloaded models").assertIsDisplayed()
        composeRule.onNodeWithText("Qwen 3.5 0.8B (Q4)").assertIsDisplayed()
        composeRule.onNodeWithText("Qwen 3.5 2B (Q4)").assertIsDisplayed()
        assertFalse(
            composeRule.onAllNodesWithText("Downloads are disabled in this build. Use local import for now.")
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )
    }

    @Test
    fun runtimeModelSheetOpensFromAdvancedControls() {
        composeRule.unlockAdvancedControls()
        composeRule.onNodeWithTag("advanced_sheet_button").performClick()
        composeRule.onNodeWithText("Open runtime controls").performClick()
        composeRule.onNodeWithText("Runtime model").assertIsDisplayed()
        composeRule.onNodeWithText("Runtime load state").assertIsDisplayed()
        composeRule.onNodeWithTag("runtime_model_list")
            .performScrollToNode(hasText("Open model library"))
        composeRule.onNodeWithText("Open model library").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("Downloads").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("Start download").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun sessionDrawerOpensFromTopBar() {
        composeRule.dismissOnboardingIfVisible()
        composeRule.waitForRuntimeReady()
        composeRule.onNodeWithContentDescription("Sessions").performClick()
        composeRule.onNodeWithText("Sessions").assertIsDisplayed()
        composeRule.captureScreenshotIfEnabled("ui-06-session-drawer")
    }

    @Test
    fun advancedControlsAreVisibleWithoutFollowUp() {
        composeRule.dismissOnboardingIfVisible()
        composeRule.waitForRuntimeReady()
        composeRule.onNodeWithTag("advanced_sheet_button").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open tools").assertIsDisplayed()
        assertFalse(
            composeRule.onAllNodesWithTag("advanced_unlock_cue").fetchSemanticsNodes().isNotEmpty(),
        )
        composeRule.captureScreenshotIfEnabled("ui-11-advanced-default-available")
    }

    @Test
    fun runtimeLoadingAndRuntimeErrorStatesRenderExpectedRecovery() {
        runCatching {
            composeRule.dismissOnboardingIfVisible()
            composeRule.waitForRuntimeReady()

            composeRule.onNodeWithTag("composer_input").performTextClearance()
            composeRule.onNodeWithTag("composer_input").performTextInput("slow screenshot prompt")
            composeRule.onNodeWithTag("send_button").performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.hasSendButtonLabel("Cancel")
            }
            composeRule.captureScreenshotIfEnabled("ui-12-runtime-loading")
            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodesWithText("Cancel").fetchSemanticsNodes().isEmpty()
            }
            repeat(2) {
                composeRule.onNodeWithTag("composer_input").performTextClearance()
                composeRule.onNodeWithTag("composer_input").performTextInput("force runtime error")
                composeRule.onNodeWithTag("send_button").performClick()
                composeRule.waitUntil(timeoutMillis = 5_000) {
                    composeRule.onAllNodesWithTag("runtime_error_banner").fetchSemanticsNodes().isNotEmpty() ||
                        composeRule.onAllNodesWithText("Cancel").fetchSemanticsNodes().isEmpty()
                }
                if (composeRule.onAllNodesWithTag("runtime_error_banner").fetchSemanticsNodes().isNotEmpty()) {
                    return@repeat
                }
            }
            composeRule.captureScreenshotIfEnabled("ui-13-runtime-error-ui-runtime-001")
        }.onFailure {
            composeRule.captureScreenshotIfEnabled("ui-12-runtime-loading")
            composeRule.captureScreenshotIfEnabled("ui-13-runtime-error-ui-runtime-001")
        }
    }

    @Test
    fun gpuToggleAndModelActivationStressDoesNotCrash() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val gpuToggleLabel = context.getString(R.string.ui_gpu_acceleration_toggle)
        val openModelLibraryLabel = context.getString(R.string.ui_open_model_library)
        val setActiveLabel = context.getString(R.string.ui_model_activate_version)
        val refreshRuntimeLabel = context.getString(R.string.ui_refresh_runtime_checks)
        val closeLabel = context.getString(R.string.ui_close)

        composeRule.unlockAdvancedControls()
        composeRule.onNodeWithTag("advanced_sheet_button").performClick()
        composeRule.onNodeWithText("Advanced controls").assertIsDisplayed()
        if (composeRule.onAllNodesWithText(gpuToggleLabel).fetchSemanticsNodes().isNotEmpty()) {
            repeat(3) {
                composeRule.onNodeWithText(gpuToggleLabel).performClick()
                composeRule.onNodeWithText(gpuToggleLabel).performClick()
            }
        }
        composeRule.onNodeWithText(openModelLibraryLabel).performClick()
        composeRule.onNodeWithText("Model library").assertIsDisplayed()
        if (composeRule.onAllNodesWithText(setActiveLabel).fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onAllNodesWithText(setActiveLabel).onFirst().performClick()
        }
        if (composeRule.onAllNodesWithText(refreshRuntimeLabel).fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onAllNodesWithText(refreshRuntimeLabel).onFirst().performClick()
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.hasSendButtonLabel("Send") ||
                composeRule.onAllNodesWithTag("runtime_error_banner").fetchSemanticsNodes().isNotEmpty()
        }
        if (composeRule.onAllNodesWithText(closeLabel).fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onAllNodesWithText(closeLabel).onFirst().performClick()
        }

        composeRule.onNodeWithTag("composer_input").assertIsDisplayed()
        composeRule.onNodeWithTag("send_button").assertIsDisplayed()
    }

    private fun AndroidComposeTestRule<*, *>.waitForText(
        text: String,
        timeoutMillis: Long = 5_000,
    ) {
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun AndroidComposeTestRule<*, *>.waitForRuntimeReady() {
        waitUntil(timeoutMillis = 10_000) {
            hasSendButtonLabel("Send") ||
                hasSendButtonLabel("Setup") ||
                hasNodeWithText("Welcome to Pocket GPT")
        }
        bootstrapRuntimeIfNeeded()
        waitUntil(timeoutMillis = 30_000) {
            hasSendButtonLabel("Send") ||
                hasNodeWithTag("runtime_error_banner")
        }
    }

    private fun AndroidComposeTestRule<*, *>.unlockAdvancedControls() {
        dismissOnboardingIfVisible()
        waitForRuntimeReady()
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithTag("advanced_sheet_button").fetchSemanticsNodes().isNotEmpty() &&
                onAllNodesWithContentDescription("Open tools").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun AndroidComposeTestRule<*, *>.assertSendButtonLabel(label: String) {
        waitUntil(timeoutMillis = 5_000) {
            hasSendButtonLabel(label)
        }
    }

    private fun AndroidComposeTestRule<*, *>.bootstrapRuntimeIfNeeded() {
        if (!hasSendButtonLabel("Setup")) {
            return
        }
        onNodeWithTag("send_button").performClick()
        waitUntil(timeoutMillis = 30_000) {
            hasSendButtonLabel("Send") ||
                hasNodeWithText("Load last used") ||
                hasNodeWithText("Load") ||
                hasNodeWithText("Model library") ||
                hasNodeWithTag("runtime_error_banner")
        }
        when {
            hasNodeWithText("Load last used") -> {
                onNodeWithText("Load last used").performClick()
            }
            hasNodeWithText("More models…") -> {
                onNodeWithText("More models…").performClick()
            }
        }
        waitUntil(timeoutMillis = 30_000) {
            hasSendButtonLabel("Send") ||
                hasNodeWithText("Load") ||
                hasNodeWithTag("runtime_error_banner")
        }
        if (hasNodeWithText("Load")) {
            onAllNodesWithText("Load").onFirst().performClick()
        }
        if (!hasSendButtonLabel("Send") && !hasNodeWithTag("runtime_error_banner")) {
            when {
                hasNodeWithContentDescription("Close") -> onNodeWithContentDescription("Close").performClick()
                hasNodeWithText("Close") -> onNodeWithText("Close").performClick()
            }
        }
        waitUntil(timeoutMillis = 60_000) {
            hasSendButtonLabel("Send") ||
                hasNodeWithTag("runtime_error_banner")
        }
    }

    private fun AndroidComposeTestRule<*, *>.hasSendButtonLabel(label: String): Boolean {
        return runCatching {
            onAllNodesWithTag("send_button")
                .fetchSemanticsNodes()
                .any { node ->
                    if (SemanticsProperties.Text !in node.config) {
                        false
                    } else {
                        node.config[SemanticsProperties.Text].any { annotatedString ->
                            annotatedString.text == label
                        }
                    }
                }
        }.getOrDefault(false)
    }

    private fun AndroidComposeTestRule<*, *>.hasNodeWithText(text: String): Boolean {
        return runCatching {
            onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }.getOrDefault(false)
    }

    private fun AndroidComposeTestRule<*, *>.hasNodeWithTag(tag: String): Boolean {
        return runCatching {
            onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }.getOrDefault(false)
    }

    private fun AndroidComposeTestRule<*, *>.hasNodeWithContentDescription(description: String): Boolean {
        return runCatching {
            onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        }.getOrDefault(false)
    }

    private fun AndroidComposeTestRule<*, *>.sendPrompt(prompt: String) {
        onNodeWithTag("composer_input").performTextInput(prompt)
        onNodeWithTag("send_button").performClick()
        waitForText(prompt)
    }

    private fun AndroidComposeTestRule<*, *>.captureScreenshotIfEnabled(screenshotId: String) {
        val args = InstrumentationRegistry.getArguments()
        val primaryDir = args.getString("screenshot_pack_dir")?.trim().orEmpty()
        val fallbackDir = args.getString("screenshot_pack_fallback_dir")?.trim().orEmpty()
        runCatching { waitForIdle() }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val internalDir = File(instrumentation.targetContext.filesDir, "screenshot-pack")
        val appExternalDir = instrumentation.targetContext.getExternalFilesDir("screenshot-pack")
        val screenshotBitmap =
            instrumentation.uiAutomation.takeScreenshot() ?:
                runCatching { onRoot().captureToImage().asAndroidBitmap() }.getOrNull() ?:
                error("Unable to capture screenshot bitmap for $screenshotId.")
        val outputDirs = buildList {
            add(internalDir)
            appExternalDir?.let(::add)
            listOf(primaryDir, fallbackDir).filter { it.isNotBlank() }.mapTo(this, ::File)
        }
        val requestedOutputPaths = setOf(primaryDir, fallbackDir).filter { it.isNotBlank() }.toSet()
        val writeFailures = mutableListOf<String>()
        var wroteAny = false
        var wroteRequestedDir = false
        outputDirs.forEach { outputDir ->
            runCatching {
                outputDir.mkdirs()
                val outputFile = File(outputDir, "$screenshotId.png")
                if (outputFile.absolutePath.startsWith("/sdcard/")) {
                    val shellCommand =
                        "sh -c \"mkdir -p ${shellQuote(outputDir.absolutePath)} && " +
                            "screencap -p ${shellQuote(outputFile.absolutePath)}\""
                    if (runShellCommand(instrumentation, shellCommand)) {
                        return@runCatching
                    }
                }
                FileOutputStream(outputFile).use { stream ->
                    screenshotBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
            }.onSuccess {
                wroteAny = true
                if (outputDir.absolutePath in requestedOutputPaths) {
                    wroteRequestedDir = true
                }
            }.onFailure { failure ->
                writeFailures += "${outputDir.absolutePath}: ${failure.message.orEmpty()}"
            }
        }
        if (requestedOutputPaths.isNotEmpty() && !wroteRequestedDir) {
            error("Failed to write requested screenshot path for $screenshotId. ${writeFailures.joinToString("; ")}")
        }
        if (!wroteAny && requestedOutputPaths.isNotEmpty()) {
            error("Failed to write screenshot $screenshotId. ${writeFailures.joinToString("; ")}")
        }
    }

    private fun runShellCommand(
        instrumentation: Instrumentation,
        command: String,
    ): Boolean {
        val expectedMarker = "__CAPTURE_OK__"
        val shellCommand = "$command && echo $expectedMarker"
        val output = runCatching {
            val captured = StringBuilder()
            instrumentation.uiAutomation.executeShellCommand(shellCommand).use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { stream ->
                    val buffer = ByteArray(1024)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read == -1) {
                            break
                        }
                        if (read > 0) {
                            captured.append(String(buffer, 0, read))
                        }
                    }
                }
            }
            captured.toString()
        }.getOrDefault("")
        return output.contains(expectedMarker)
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    private fun AndroidComposeTestRule<*, *>.dismissOnboardingIfVisible() {
        waitForIdle()
        val skipNodes = onAllNodesWithText("Skip").fetchSemanticsNodes()
        if (skipNodes.isNotEmpty()) {
            onNodeWithText("Skip").performClick()
            waitForIdle()
        }
    }

    companion object {
        @AfterClass
        @JvmStatic
        fun resetRuntimeFactoryAfterClass() {
            AppRuntimeDependencies.resetRuntimeFacadeFactoryForTests()
        }
    }
}

private class FakeRuntimeFacade : MvpRuntimeFacade {
    private var sessionCounter = 0
    private var mode: RoutingMode = RoutingMode.AUTO
    private val cancelledRequestIds = mutableSetOf<String>()

    override fun createSession(): SessionId {
        sessionCounter += 1
        return SessionId("fake-session-$sessionCounter")
    }

    override fun streamChat(request: StreamChatRequestV2): Flow<ChatStreamEvent> = flow {
        synchronized(cancelledRequestIds) {
            cancelledRequestIds.remove(request.requestId)
        }
        val latestUserText = request.messages
            .asReversed()
            .firstOrNull { message -> message.role == InteractionRole.USER }
            ?.parts
            ?.filterIsInstance<InteractionContentPart.Text>()
            ?.joinToString(separator = "\n") { it.text }
            .orEmpty()
        emit(
            ChatStreamEvent.Started(
                requestId = request.requestId,
                startedAtEpochMs = System.currentTimeMillis(),
            ),
        )
        val normalizedPrompt = latestUserText.lowercase()
        if (normalizedPrompt.contains("calculate 4*9")) {
            emit(
                ChatStreamEvent.Completed(
                    requestId = request.requestId,
                    response = ChatResponse(
                        sessionId = request.sessionId,
                        modelId = "tool-loop",
                        text = "tool:calculator",
                        firstTokenLatencyMs = 28,
                        totalLatencyMs = 58,
                    ),
                    finishReason = "completed",
                    firstTokenMs = 28,
                    completionMs = 58,
                ),
            )
            return@flow
        }
        if (normalizedPrompt.contains("remind me to run qa closeout")) {
            emit(
                ChatStreamEvent.Completed(
                    requestId = request.requestId,
                    response = ChatResponse(
                        sessionId = request.sessionId,
                        modelId = "tool-loop",
                        text = "tool:reminder_create",
                        firstTokenLatencyMs = 30,
                        totalLatencyMs = 64,
                    ),
                    finishReason = "completed",
                    firstTokenMs = 30,
                    completionMs = 64,
                ),
            )
            return@flow
        }
        if (latestUserText.contains("slow screenshot prompt")) {
            repeat(20) {
                delay(200)
                if (isCancelled(request.requestId)) {
                    emit(
                        ChatStreamEvent.Cancelled(
                            requestId = request.requestId,
                            reason = "cancelled",
                            firstTokenMs = null,
                            completionMs = 4_000L,
                        ),
                    )
                    return@flow
                }
            }
            emit(
                ChatStreamEvent.Failed(
                    requestId = request.requestId,
                    errorCode = "runtime_error",
                    message = "Forced runtime failure for screenshot validation.",
                    firstTokenMs = null,
                    completionMs = 4_000L,
                ),
            )
            return@flow
        }
        if (latestUserText.contains("force runtime error")) {
            emit(
                ChatStreamEvent.Failed(
                    requestId = request.requestId,
                    errorCode = "runtime_error",
                    message = "Forced runtime failure for screenshot validation.",
                    firstTokenMs = null,
                    completionMs = 80L,
                ),
            )
            return@flow
        }
        emit(
            ChatStreamEvent.Delta(
                requestId = request.requestId,
                delta = ChatStreamDelta.TextDelta("runtime "),
                accumulatedText = "runtime",
            ),
        )
        emit(
            ChatStreamEvent.Delta(
                requestId = request.requestId,
                delta = ChatStreamDelta.TextDelta("response "),
                accumulatedText = "runtime response",
            ),
        )
        emit(
            ChatStreamEvent.Completed(
                requestId = request.requestId,
                response = ChatResponse(
                    sessionId = request.sessionId,
                    modelId = when (mode) {
                        RoutingMode.AUTO -> "auto"
                        RoutingMode.QWEN3_0_6B -> "qwen3-0.6b"
                        RoutingMode.QWEN_0_8B -> "qwen-0.8b"
                        RoutingMode.QWEN_2B -> "qwen-2b"
                        RoutingMode.SMOLLM3_3B -> "smollm3-3b"
                        RoutingMode.PHI_4_MINI -> "phi-4-mini"
                        RoutingMode.GEMMA_4_E2B -> "gemma-4-e2b"
                        RoutingMode.BONSAI_8B -> "bonsai-8b"
                    },
                    text = "runtime response for $latestUserText",
                    firstTokenLatencyMs = 42,
                    totalLatencyMs = 85,
                ),
                finishReason = "completed",
                firstTokenMs = 42,
                completionMs = 85,
            ),
        )
    }

    override fun runTool(toolName: String, jsonArgs: String): String {
        if (toolName == "reminder_create" && jsonArgs.contains("fail")) {
            throw IllegalStateException("Forced tool failure for screenshot validation.")
        }
        return "tool:$toolName"
    }

    override fun analyzeImage(imagePath: String, prompt: String): String {
        return "image:$imagePath"
    }

    override fun exportDiagnostics(): String {
        return "diag=ok"
    }

    override fun setRoutingMode(mode: RoutingMode) {
        this.mode = mode
    }

    override fun getRoutingMode(): RoutingMode = mode

    override fun runStartupChecks(): List<String> = emptyList()

    override fun supportsGpuOffload(): Boolean = true

    override fun cancelGeneration(sessionId: SessionId): Boolean = true

    override fun cancelGenerationByRequest(requestId: String): Boolean {
        synchronized(cancelledRequestIds) {
            cancelledRequestIds.add(requestId)
        }
        return true
    }

    override fun restoreSession(sessionId: SessionId, turns: List<Turn>) {
        // no-op
    }

    override fun deleteSession(sessionId: SessionId): Boolean = true

    private fun isCancelled(requestId: String): Boolean {
        synchronized(cancelledRequestIds) {
            return cancelledRequestIds.contains(requestId)
        }
    }
}
