package com.pocketagent.android.voice

import com.pocketagent.tools.ToolCallRequest
import com.pocketagent.tools.ToolCallRequestParseResult
import com.pocketagent.tools.toPrimitiveStringMapOrNull
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DeterministicVoiceActionParserTest {
    private val now = LocalDateTime.of(2026, 7, 11, 20, 30, 45)

    @Test
    fun `core bounded commands produce strict typed proposals without a model`() {
        assertProposal(
            "start a timer for five minutes called tea",
            AndroidLocalToolRuntime.TOOL_TIMER_SET,
            mapOf("duration_seconds" to "300", "label" to "tea"),
        )
        assertProposal(
            "set media volume to forty percent",
            AndroidLocalToolRuntime.TOOL_VOLUME_SET,
            mapOf("level_percent" to "40"),
        )
        assertProposal(
            "turn the flashlight off",
            AndroidLocalToolRuntime.TOOL_FLASHLIGHT_TOGGLE,
            mapOf("enabled" to "off"),
        )
        assertProposal(
            "open the clock app",
            AndroidLocalToolRuntime.TOOL_APP_OPEN,
            mapOf("app_name" to "clock"),
        )
    }

    @Test
    fun `explicit alarm becomes next occurrence and preserves label`() {
        val proposal = proposal("set an alarm for 7 am called work")
        assertEquals(AndroidLocalToolRuntime.TOOL_ALARM_SET, proposal.toolCall.name)
        val arguments = arguments(proposal)
        assertEquals("2026-07-12T07:00", arguments["time_iso"])
        assertEquals("work", arguments["title"])
        assertIs<VoiceActionValidation.Valid>(
            VoiceActionCatalog.validateLegacy(
                proposal.toolCall.name,
                proposal.toolCall.argumentsJson,
                now,
            ),
        )
    }

    @Test
    fun `ambiguous and out of range commands ask instead of guessing`() {
        assertTrue(
            clarification("set an alarm for seven").contains("AM or PM", ignoreCase = true),
        )
        assertTrue(
            clarification("set volume to 120 percent").contains("between 0 and 100"),
        )
        assertTrue(
            clarification("start a timer").contains("How long"),
        )
    }

    @Test
    fun `negated contradictory and multi action speech never produces a proposal`() {
        val unsafe = listOf(
            "don't turn the flashlight on",
            "don't open Maps",
            "I do not want a five minute timer",
            "turn the flashlight off and set volume to twenty",
            "turn the flashlight on and off",
            "set volume to twenty or forty percent",
            "start a timer for five minutes or ten minutes",
        )

        unsafe.forEach { transcript ->
            assertIs<DeterministicVoiceActionParseResult.Clarification>(
                DeterministicVoiceActionParser.parse(transcript, now),
                transcript,
            )
        }
    }

    @Test
    fun `explicit today never silently rolls a past alarm into tomorrow`() {
        val result = DeterministicVoiceActionParser.parse(
            "set an alarm today for 7 am",
            now,
        )

        val clarification = assertIs<DeterministicVoiceActionParseResult.Clarification>(result)
        assertTrue(clarification.message.contains("passed today"))
    }

    @Test
    fun `ambiguous alarm keeps a typed slot and completes from an exact period reply`() {
        val clarification = assertIs<DeterministicVoiceActionParseResult.Clarification>(
            DeterministicVoiceActionParser.parse("set an alarm for seven called work", now),
        )
        val followUp = assertIs<VoiceActionClarification.AlarmPeriod>(clarification.followUp)

        val completed = assertIs<DeterministicVoiceActionParseResult.Proposal>(
            DeterministicVoiceActionParser.completeClarification(followUp, "AM", now),
        )

        assertEquals("2026-07-12T07:00", arguments(completed)["time_iso"])
        assertEquals("work", arguments(completed)["title"])
        assertIs<DeterministicVoiceActionParseResult.Clarification>(
            DeterministicVoiceActionParser.completeClarification(followUp, "I am tired", now),
        )
    }

    @Test
    fun `ordinary conversation stays on model fallback path`() {
        assertEquals(
            DeterministicVoiceActionParseResult.NoMatch,
            DeterministicVoiceActionParser.parse("why is the sky blue", now),
        )
    }

    private fun assertProposal(
        transcript: String,
        expectedTool: String,
        expectedArguments: Map<String, String>,
    ) {
        val proposal = proposal(transcript)
        assertEquals(expectedTool, proposal.toolCall.name)
        assertEquals(expectedArguments, arguments(proposal))
    }

    private fun proposal(transcript: String): DeterministicVoiceActionParseResult.Proposal {
        return assertIs<DeterministicVoiceActionParseResult.Proposal>(
            DeterministicVoiceActionParser.parse(transcript, now),
        )
    }

    private fun clarification(transcript: String): String {
        return assertIs<DeterministicVoiceActionParseResult.Clarification>(
            DeterministicVoiceActionParser.parse(transcript, now),
        ).message
    }

    private fun arguments(proposal: DeterministicVoiceActionParseResult.Proposal): Map<String, String> {
        val request = assertIs<ToolCallRequestParseResult.Success>(
            ToolCallRequest.fromLegacy(
                proposal.toolCall.name,
                proposal.toolCall.argumentsJson,
            ),
        ).request
        return checkNotNull(request.arguments.toPrimitiveStringMapOrNull())
    }
}
