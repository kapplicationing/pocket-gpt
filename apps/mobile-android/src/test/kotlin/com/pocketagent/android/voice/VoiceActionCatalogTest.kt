package com.pocketagent.android.voice

import android.media.AudioRecord
import com.pocketagent.tools.ToolCallRequest
import com.pocketagent.tools.ToolCallRequestParseResult
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VoiceActionCatalogTest {
    private val now = LocalDateTime.of(2026, 7, 11, 20, 30)

    @Test
    fun `alarm accepts only the next exact minute occurrence`() {
        val result = validate(
            AndroidLocalToolRuntime.TOOL_ALARM_SET,
            """{"title":"Wake up","time_iso":"2026-07-12T07:00:00"}""",
        )

        val valid = assertIs<VoiceActionValidation.Valid>(result)
        assertContains(valid.preview, "tomorrow")
        assertEquals(VoiceActionRisk.SCHEDULED_EFFECT, valid.risk)
    }

    @Test
    fun `alarm rejects arbitrary later dates and sub-minute values`() {
        assertIs<VoiceActionValidation.Invalid>(
            validate(
                AndroidLocalToolRuntime.TOOL_ALARM_SET,
                """{"time_iso":"2026-07-13T07:00:00"}""",
            ),
        )
        assertIs<VoiceActionValidation.Invalid>(
            validate(
                AndroidLocalToolRuntime.TOOL_ALARM_SET,
                """{"time_iso":"2026-07-12T07:00:30"}""",
            ),
        )
    }

    @Test
    fun `strict schemas reject unknown missing nested and out of range values`() {
        val invalidCalls = listOf(
            AndroidLocalToolRuntime.TOOL_TIMER_SET to """{"duration_seconds":"30","extra":"x"}""",
            AndroidLocalToolRuntime.TOOL_TIMER_SET to """{}""",
            AndroidLocalToolRuntime.TOOL_TIMER_SET to """{"duration_seconds":{"value":"30"}}""",
            AndroidLocalToolRuntime.TOOL_TIMER_SET to """{"duration_seconds":"86401"}""",
            AndroidLocalToolRuntime.TOOL_VOLUME_SET to """{"level_percent":"101"}""",
            AndroidLocalToolRuntime.TOOL_FLASHLIGHT_TOGGLE to """{"enabled":"toggle"}""",
            AndroidLocalToolRuntime.TOOL_APP_OPEN to """{"app_name":""}""",
        )

        invalidCalls.forEach { (name, json) ->
            assertIs<VoiceActionValidation.Invalid>(validate(name, json), "$name should reject $json")
        }
    }

    @Test
    fun `prompt is generated with current time zone and bounded schemas`() {
        val prompt = VoiceActionCatalog.buildSystemPrompt(
            now = now,
            zoneId = ZoneId.of("Asia/Bangkok"),
            locale = Locale.US,
        )

        assertContains(prompt, "2026-07-11 20:30:00")
        assertContains(prompt, "Asia/Bangkok")
        assertContains(prompt, "Never emit multiple tools")
        assertFalse(prompt.contains("2026-04-10"))
    }

    @Test
    fun `untrusted invocations confirm while trusted explicit requests stay one shot`() {
        val timer = assertIs<VoiceActionValidation.Valid>(
            validate(AndroidLocalToolRuntime.TOOL_TIMER_SET, """{"duration_seconds":"300"}"""),
        )
        val alarm = assertIs<VoiceActionValidation.Valid>(
            validate(
                AndroidLocalToolRuntime.TOOL_ALARM_SET,
                """{"time_iso":"2026-07-12T07:00:00"}""",
            ),
        )

        assertTrue(
            VoiceActionCatalog.requiresConfirmation(
                VoiceInvocationSource.WAKE_WORD,
                timer,
                "set a five minute timer",
            ),
        )
        assertTrue(
            VoiceActionCatalog.requiresVisibleSession(
                VoiceInvocationSource.ASSISTANT,
                VoiceActionRisk.REVERSIBLE_DEVICE_STATE,
            ),
        )
        assertFalse(
            VoiceActionCatalog.requiresVisibleSession(
                VoiceInvocationSource.WAKE_WORD,
                VoiceActionRisk.REVERSIBLE_DEVICE_STATE,
            ),
        )
        assertFalse(
            VoiceActionCatalog.requiresConfirmation(
                VoiceInvocationSource.ASSISTANT,
                timer,
                "set a five minute timer",
            ),
        )
        assertTrue(
            VoiceActionCatalog.requiresConfirmation(
                source = VoiceInvocationSource.ASSISTANT,
                action = timer,
                transcript = "tell me something unrelated",
                origin = VoiceActionProposalOrigin.MODEL,
            ),
        )
        assertTrue(
            VoiceActionCatalog.requiresConfirmation(
                VoiceInvocationSource.UNTRUSTED_ASSISTANT,
                timer,
                "set a five minute timer",
            ),
        )
        assertTrue(
            VoiceActionCatalog.requiresConfirmation(
                VoiceInvocationSource.ASSISTANT,
                alarm,
                "set an alarm for seven",
            ),
        )
        assertFalse(
            VoiceActionCatalog.requiresConfirmation(
                VoiceInvocationSource.ASSISTANT,
                alarm,
                "set an alarm for seven a m",
            ),
        )
        assertTrue(
            VoiceActionCatalog.requiresConfirmation(
                VoiceInvocationSource.ASSISTANT,
                alarm,
                "wake me at seven, I am exhausted",
            ),
        )
    }

    @Test
    fun `confirmation language is narrow and deterministic`() {
        assertEquals(VoiceConfirmationDecision.CONFIRM, parseVoiceConfirmation("Yes, please!"))
        assertEquals(VoiceConfirmationDecision.CANCEL, parseVoiceConfirmation("Never mind"))
        assertEquals(VoiceConfirmationDecision.UNKNOWN, parseVoiceConfirmation("maybe later"))
        assertEquals(VoiceConfirmationDecision.UNKNOWN, parseVoiceConfirmation("yes and open Maps"))
    }

    @Test
    fun `audio record failures map to stable recovery copy`() {
        assertContains(audioRecordFailureMessage(AudioRecord.ERROR_DEAD_OBJECT), "disconnected")
        assertContains(audioRecordFailureMessage(AudioRecord.ERROR_BAD_VALUE), "buffer")
        assertContains(audioRecordFailureMessage(-999), "-999")
    }

    private fun validate(name: String, json: String): VoiceActionValidation {
        val request = assertIs<ToolCallRequestParseResult.Success>(
            ToolCallRequest.fromLegacy(name, json),
        ).request
        return VoiceActionCatalog.validate(request, now)
    }
}
