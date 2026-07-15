package com.pocketagent.android.voice

import com.pocketagent.core.ChatToolCall
import java.time.LocalDateTime
import java.util.Locale
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal sealed interface DeterministicVoiceActionParseResult {
    data object NoMatch : DeterministicVoiceActionParseResult

    data class Proposal(val toolCall: ChatToolCall) : DeterministicVoiceActionParseResult

    data class Clarification(
        val message: String,
        val followUp: VoiceActionClarification? = null,
    ) : DeterministicVoiceActionParseResult
}

internal sealed interface VoiceActionClarification {
    data class AlarmPeriod(
        val hour: Int,
        val minute: Int,
        val title: String,
    ) : VoiceActionClarification
}

/** Fast, deterministic coverage for common commands; all proposals still pass the shared action policy. */
internal object DeterministicVoiceActionParser {
    fun parse(
        transcript: String,
        now: LocalDateTime = LocalDateTime.now(),
    ): DeterministicVoiceActionParseResult {
        val normalized = transcript.normalizedSpeech()
        if (normalized.isBlank()) return DeterministicVoiceActionParseResult.NoMatch
        val intentCount = detectedActionIntents(normalized)
        if (intentCount > 1) {
            return DeterministicVoiceActionParseResult.Clarification(
                "Please ask for one phone action at a time.",
            )
        }
        if (intentCount == 1 && containsNegation(normalized)) {
            return DeterministicVoiceActionParseResult.Clarification(
                "I won't run that because the request sounded negative. Please ask again directly.",
            )
        }
        parseFlashlight(normalized)?.let { return it }
        parseVolume(normalized)?.let { return it }
        parseTimer(normalized)?.let { return it }
        parseAlarm(normalized, now)?.let { return it }
        parseOpenApp(normalized)?.let { return it }
        return DeterministicVoiceActionParseResult.NoMatch
    }

    fun completeClarification(
        clarification: VoiceActionClarification,
        transcript: String,
        now: LocalDateTime = LocalDateTime.now(),
    ): DeterministicVoiceActionParseResult {
        return when (clarification) {
            is VoiceActionClarification.AlarmPeriod -> {
                val isPm = when (transcript.normalizedSpeech()) {
                    "am", "a m", "morning", "in the morning" -> false
                    "pm", "p m", "afternoon", "evening", "tonight" -> true
                    else -> {
                        return DeterministicVoiceActionParseResult.Clarification(
                            "Please say only AM or PM.",
                            followUp = clarification,
                        )
                    }
                }
                val hour = when {
                    clarification.hour == 12 && !isPm -> 0
                    clarification.hour != 12 && isPm -> clarification.hour + 12
                    else -> clarification.hour
                }
                val candidate = nextOccurrence(now, hour, clarification.minute)
                proposal(
                    AndroidLocalToolRuntime.TOOL_ALARM_SET,
                    mapOf("time_iso" to candidate.toString(), "title" to clarification.title),
                    "alarm-${clarification.hour}-${clarification.minute}-${if (isPm) "pm" else "am"}",
                )
            }
        }
    }

    private fun parseFlashlight(text: String): DeterministicVoiceActionParseResult? {
        if ("flashlight" !in text && "torch" !in text) return null
        val trailingDirection = Regex(
            "^(?:please )?(?:turn|switch|set) (?:the )?(?:flashlight|torch) (on|off)$",
        ).matchEntire(text)?.groupValues?.get(1)
        val leadingDirection = Regex(
            "^(?:please )?(?:turn|switch) (on|off) (?:the )?(?:flashlight|torch)$",
        ).matchEntire(text)?.groupValues?.get(1)
        val enabled = when (trailingDirection ?: leadingDirection) {
            "on" -> true
            "off" -> false
            else -> return DeterministicVoiceActionParseResult.Clarification(
                "Please say turn the flashlight on or turn the flashlight off.",
            )
        }
        return proposal(
            AndroidLocalToolRuntime.TOOL_FLASHLIGHT_TOGGLE,
            mapOf("enabled" to if (enabled) "on" else "off"),
            text,
        )
    }

    private fun parseVolume(text: String): DeterministicVoiceActionParseResult? {
        if ("volume" !in text) return null
        val matches = Regex(
            "\\b(\\d{1,3}|zero|one|two|three|four|five|six|seven|eight|nine|ten|" +
                "twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|one hundred)" +
                "\\s*(?:percent|%)?\\b",
        ).findAll(text).toList()
        if (matches.isEmpty()) {
            return DeterministicVoiceActionParseResult.Clarification(
                "What media volume percentage should I use?",
            )
        }
        if (matches.map { it.groupValues[1] }.distinct().size > 1) {
            return DeterministicVoiceActionParseResult.Clarification(
                "Say one media volume percentage.",
            )
        }
        val percent = parseSmallNumber(matches.first().groupValues[1])?.toInt()
        if (percent == null || percent !in 0..100) {
            return DeterministicVoiceActionParseResult.Clarification(
                "Media volume must be between 0 and 100 percent.",
            )
        }
        return proposal(
            AndroidLocalToolRuntime.TOOL_VOLUME_SET,
            mapOf("level_percent" to percent.toString()),
            text,
        )
    }

    private fun parseTimer(text: String): DeterministicVoiceActionParseResult? {
        if ("timer" !in text) return null
        val matches = Regex(
            "\\b(\\d+|an?|one|two|three|four|five|six|seven|eight|nine|ten|" +
                "eleven|twelve|thirteen|fourteen|fifteen|twenty|thirty|forty|fifty|sixty)\\s*" +
                "(seconds?|minutes?|hours?)\\b",
        ).findAll(text).toList()
        if (matches.isEmpty()) {
            return DeterministicVoiceActionParseResult.Clarification("How long should the timer run?")
        }
        if (matches.size > 1) {
            return DeterministicVoiceActionParseResult.Clarification("Say one timer duration.")
        }
        val match = matches.single()
        val amount = parseSmallNumber(match.groupValues[1])
            ?: return DeterministicVoiceActionParseResult.Clarification("Say a timer duration using a number.")
        val multiplier = when {
            match.groupValues[2].startsWith("hour") -> 3_600L
            match.groupValues[2].startsWith("minute") -> 60L
            else -> 1L
        }
        val seconds = amount * multiplier
        if (seconds !in 1..86_400) {
            return DeterministicVoiceActionParseResult.Clarification(
                "Timer duration must be between 1 second and 24 hours.",
            )
        }
        val label = extractLabel(text) ?: "PocketAgent timer"
        return proposal(
            AndroidLocalToolRuntime.TOOL_TIMER_SET,
            mapOf("duration_seconds" to seconds.toString(), "label" to label),
            text,
        )
    }

    private fun parseAlarm(
        text: String,
        now: LocalDateTime,
    ): DeterministicVoiceActionParseResult? {
        if ("alarm" !in text) return null
        val explicitMatches = Regex(
            "\\b(?:at|for)\\s+(\\d{1,2}|one|two|three|four|five|six|seven|eight|nine|ten|" +
                "eleven|twelve)(?::(\\d{2}))?\\s*(a\\s?m|p\\s?m)\\b",
        ).findAll(text).toList()
        if (explicitMatches.size > 1) {
            return DeterministicVoiceActionParseResult.Clarification("Say one exact alarm time.")
        }
        if (explicitMatches.isEmpty()) {
            return parseAmbiguousAlarm(text)
        }
        return explicitAlarmProposal(text, explicitMatches.single(), now)
    }

    private fun explicitAlarmProposal(
        text: String,
        timeMatch: MatchResult,
        now: LocalDateTime,
    ): DeterministicVoiceActionParseResult {
        val rawHour = parseSmallNumber(timeMatch.groupValues[1])?.toInt()
        val minute = timeMatch.groupValues[2].ifBlank { "0" }.toIntOrNull()
        if (!isValidClockTime(rawHour, minute)) {
            return DeterministicVoiceActionParseResult.Clarification("That alarm time is not valid.")
        }
        checkNotNull(rawHour)
        checkNotNull(minute)
        val isPm = timeMatch.groupValues[3].replace(" ", "") == "pm"
        val hour = when {
            rawHour == 12 && !isPm -> 0
            rawHour != 12 && isPm -> rawHour + 12
            else -> rawHour
        }
        val candidate = nextOccurrence(now, hour, minute)
        if ("today" in text && candidate.toLocalDate() != now.toLocalDate()) {
            return DeterministicVoiceActionParseResult.Clarification(
                "That time has already passed today. Say tomorrow or choose a later time.",
            )
        }
        if ("tomorrow" in text && candidate.toLocalDate() == now.toLocalDate()) {
            return DeterministicVoiceActionParseResult.Clarification(
                "Android Clock can currently create only the next occurrence of that time.",
            )
        }
        val label = extractLabel(text) ?: "PocketAgent alarm"
        return proposal(
            AndroidLocalToolRuntime.TOOL_ALARM_SET,
            mapOf("time_iso" to candidate.toString(), "title" to label),
            text,
        )
    }

    private fun parseAmbiguousAlarm(text: String): DeterministicVoiceActionParseResult {
        val matches = Regex(
            "\\b(?:at|for)\\s+(\\d{1,2}|one|two|three|four|five|six|seven|eight|nine|ten|" +
                "eleven|twelve)(?::(\\d{2}))?\\b",
        ).findAll(text).toList()
        if (matches.size != 1) {
            return DeterministicVoiceActionParseResult.Clarification(
                "What exact alarm time should I use? Please include AM or PM.",
            )
        }
        val match = matches.single()
        val hour = parseSmallNumber(match.groupValues[1])?.toInt()
        val minute = match.groupValues[2].ifBlank { "0" }.toIntOrNull()
        if (!isValidClockTime(hour, minute)) {
            return DeterministicVoiceActionParseResult.Clarification("That alarm time is not valid.")
        }
        checkNotNull(hour)
        checkNotNull(minute)
        return DeterministicVoiceActionParseResult.Clarification(
            message = "Should that alarm be $hour:${minute.toString().padStart(2, '0')} AM or PM?",
            followUp = VoiceActionClarification.AlarmPeriod(
                hour = hour,
                minute = minute,
                title = extractLabel(text) ?: "PocketAgent alarm",
            ),
        )
    }

    private fun parseOpenApp(text: String): DeterministicVoiceActionParseResult? {
        val match = Regex("^(?:please )?(?:open|launch|start)\\s+(?:the )?(.+?)(?: app)?$").matchEntire(text)
            ?: return null
        val appName = match.groupValues[1].trim()
        if (appName.isBlank()) {
            return DeterministicVoiceActionParseResult.Clarification("Which app should I open?")
        }
        return proposal(
            AndroidLocalToolRuntime.TOOL_APP_OPEN,
            mapOf("app_name" to appName),
            text,
        )
    }

    private fun proposal(
        toolName: String,
        arguments: Map<String, String>,
        transcript: String,
    ): DeterministicVoiceActionParseResult.Proposal {
        val json = buildJsonObject {
            arguments.forEach { (name, value) -> put(name, JsonPrimitive(value)) }
        }.toString()
        return DeterministicVoiceActionParseResult.Proposal(
            ChatToolCall(
                id = "voice-local-${toolName}-${transcript.hashCode().toUInt().toString(16)}",
                name = toolName,
                argumentsJson = json,
            ),
        )
    }

    private fun parseSmallNumber(value: String): Long? {
        value.toLongOrNull()?.let { return it }
        return SMALL_NUMBERS[value]
    }

    private fun extractLabel(text: String): String? {
        return Regex("\\b(?:called|named|labelled|labeled)\\s+(.+)$")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }

    private fun detectedActionIntents(text: String): Int {
        val flashlight = "flashlight" in text || "torch" in text
        val volume = "volume" in text
        val timer = "timer" in text
        val alarm = "alarm" in text
        val optionalNegation = "(?:(?:do not|don t|dont|never) )?"
        val app = Regex("^(?:please )?$optionalNegation(?:open|launch)\\s+").containsMatchIn(text) ||
            (!flashlight && !volume && !timer && !alarm &&
                Regex("^(?:please )?$optionalNegation" + "start\\s+").containsMatchIn(text))
        return listOf(flashlight, volume, timer, alarm, app).count { it }
    }

    private fun containsNegation(text: String): Boolean {
        return Regex("\\b(?:do not|don t|dont|never|not|no)\\b").containsMatchIn(text)
    }

    private fun nextOccurrence(now: LocalDateTime, hour: Int, minute: Int): LocalDateTime {
        return now
            .withHour(hour)
            .withMinute(minute)
            .withSecond(0)
            .withNano(0)
            .let { local -> if (local.isAfter(now)) local else local.plusDays(1) }
    }

    private fun isValidClockTime(hour: Int?, minute: Int?): Boolean {
        return hour != null && minute != null && hour in 1..12 && minute in 0..59
    }

    private fun String.normalizedSpeech(): String {
        return lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}: %]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private val SMALL_NUMBERS = mapOf(
        "a" to 1L,
        "an" to 1L,
        "zero" to 0L,
        "one" to 1L,
        "two" to 2L,
        "three" to 3L,
        "four" to 4L,
        "five" to 5L,
        "six" to 6L,
        "seven" to 7L,
        "eight" to 8L,
        "nine" to 9L,
        "ten" to 10L,
        "eleven" to 11L,
        "twelve" to 12L,
        "thirteen" to 13L,
        "fourteen" to 14L,
        "fifteen" to 15L,
        "twenty" to 20L,
        "thirty" to 30L,
        "forty" to 40L,
        "fifty" to 50L,
        "sixty" to 60L,
        "seventy" to 70L,
        "eighty" to 80L,
        "ninety" to 90L,
        "one hundred" to 100L,
    )
}
