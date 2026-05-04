package com.pocketagent.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput

internal data class JourneySendKickoffResult(
    val started: Boolean,
    val detail: String,
)

internal fun AndroidComposeTestRule<*, *>.submitPromptAndAwaitSendKickoff(
    prompt: String,
    readyTimeoutMs: Long = 5_000L,
    kickoffTimeoutMs: Long = 5_000L,
    trace: ((String) -> Unit)? = null,
): JourneySendKickoffResult {
    onNodeWithTag("composer_input").performTextInput(prompt)
    val ready = runCatching {
        waitUntil(timeoutMillis = readyTimeoutMs) {
            val snapshot = captureJourneySendSnapshot()
            snapshot.sendButtonLabel == "Send" &&
                snapshot.sendButtonEnabled &&
                snapshot.composerText.contains(prompt)
        }
    }.isSuccess
    if (!ready) {
        val snapshot = captureJourneySendSnapshot()
        trace?.invoke("ready_timeout {$snapshot}")
        return JourneySendKickoffResult(
            started = false,
            detail = "send_button_not_ready_for_prompt {$snapshot}",
        )
    }
    val beforeClick = captureJourneySendSnapshot()
    trace?.invoke("ready {$beforeClick}")

    onNodeWithTag("send_button").performClick()
    val kickoff = runCatching {
        waitUntil(timeoutMillis = kickoffTimeoutMs) {
            captureJourneySendSnapshot().hasSendKickoff()
        }
    }.isSuccess
    val afterClick = captureJourneySendSnapshot()
    trace?.invoke("post_click {$afterClick}")
    if (!kickoff) {
        return JourneySendKickoffResult(
            started = false,
            detail = "send_not_kicked_off_after_click {$beforeClick} -> {$afterClick}",
        )
    }
    trace?.invoke("kickoff {$afterClick}")

    return JourneySendKickoffResult(
        started = true,
        detail = "send_kickoff_observed {$afterClick}",
    )
}

internal fun AndroidComposeTestRule<*, *>.captureJourneySendSnapshot(): JourneySendSnapshot {
    waitForIdle()
    val sendButtonNode = runCatching {
        onAllNodesWithTag("send_button").fetchSemanticsNodes().firstOrNull()
    }.getOrNull()
    val composerNode = runCatching {
        onAllNodesWithTag("composer_input").fetchSemanticsNodes().firstOrNull()
    }.getOrNull()

    val sendButtonLabel = sendButtonNode?.config?.let { config ->
        if (SemanticsProperties.Text in config) {
            config[SemanticsProperties.Text].firstOrNull()?.text
        } else {
            null
        }
    }
    val sendButtonEnabled = sendButtonNode?.config?.contains(SemanticsProperties.Disabled) == false
    val composerText = composerNode?.config?.let { config ->
        if (SemanticsProperties.EditableText in config) {
            config[SemanticsProperties.EditableText].text
        } else {
            ""
        }
    }.orEmpty()

    return JourneySendSnapshot(
        sendButtonLabel = sendButtonLabel,
        sendButtonEnabled = sendButtonEnabled,
        composerText = composerText,
        pendingVisible = hasNodeWithTag("message_bubble_assistant_pending"),
        streamingVisible = hasNodeWithTag("message_bubble_assistant_streaming"),
        completedVisible = hasNodeWithTag("message_bubble_assistant_complete"),
        runtimeErrorVisible = hasNodeWithTag("runtime_error_banner"),
    )
}

internal data class JourneySendSnapshot(
    val sendButtonLabel: String?,
    val sendButtonEnabled: Boolean,
    val composerText: String,
    val pendingVisible: Boolean,
    val streamingVisible: Boolean,
    val completedVisible: Boolean,
    val runtimeErrorVisible: Boolean,
) {
    fun hasSendKickoff(): Boolean {
        return sendButtonLabel != "Send" ||
            composerText.isBlank() ||
            pendingVisible ||
            streamingVisible ||
            completedVisible ||
            runtimeErrorVisible
    }

    override fun toString(): String {
        return "label=$sendButtonLabel enabled=$sendButtonEnabled composer='$composerText' " +
            "pending=$pendingVisible streaming=$streamingVisible completed=$completedVisible " +
            "runtimeError=$runtimeErrorVisible"
    }
}

private fun AndroidComposeTestRule<*, *>.hasNodeWithTag(tag: String): Boolean {
    return runCatching {
        onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }.getOrDefault(false)
}
