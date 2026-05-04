package com.pocketagent.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JourneySendCaptureHarnessTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun submitPromptAndAwaitSendKickoff_waitsForEnabledSendStateBeforeTap() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    DelayedEnableSendHarness()
                }
            }
        }

        val result = composeRule.submitPromptAndAwaitSendKickoff(
            prompt = "SEND_AFTER_READY_OK",
            readyTimeoutMs = 2_000L,
            kickoffTimeoutMs = 2_000L,
        )

        assertTrue(result.detail, result.started)
        composeRule.onNodeWithTag("message_bubble_assistant_pending").assertIsDisplayed()
    }

    @Test
    fun submitPromptAndAwaitSendKickoff_reportsNoKickoffWhenTapDoesNothing() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    NoKickoffSendHarness()
                }
            }
        }

        val result = composeRule.submitPromptAndAwaitSendKickoff(
            prompt = "SEND_AFTER_READY_OK",
            readyTimeoutMs = 1_000L,
            kickoffTimeoutMs = 750L,
        )

        assertFalse(result.started)
        assertTrue(result.detail, result.detail.startsWith("send_not_kicked_off_after_click"))
    }
}

@Composable
private fun DelayedEnableSendHarness() {
    val scope = rememberCoroutineScope()
    var fieldValue by remember { mutableStateOf(TextFieldValue("")) }
    var committedText by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    Column {
        OutlinedTextField(
            value = fieldValue,
            onValueChange = { newValue ->
                fieldValue = newValue
                sending = false
                scope.launch {
                    delay(300L)
                    committedText = newValue.text
                }
            },
            modifier = Modifier.testTag("composer_input"),
            label = { Text("Message") },
        )
        Button(
            onClick = {
                sending = true
                committedText = ""
                fieldValue = TextFieldValue("")
            },
            enabled = committedText.isNotBlank(),
            modifier = Modifier.testTag("send_button"),
        ) {
            Text(if (sending) "Cancel" else "Send")
        }
        if (sending) {
            Text(
                text = "Pending",
                modifier = Modifier.testTag("message_bubble_assistant_pending"),
            )
        }
    }
}

@Composable
private fun NoKickoffSendHarness() {
    var fieldValue by remember { mutableStateOf(TextFieldValue("")) }

    Column {
        OutlinedTextField(
            value = fieldValue,
            onValueChange = { fieldValue = it },
            modifier = Modifier.testTag("composer_input"),
            label = { Text("Message") },
        )
        Button(
            onClick = {},
            enabled = fieldValue.text.isNotBlank(),
            modifier = Modifier.testTag("send_button"),
        ) {
            Text("Send")
        }
    }
}
