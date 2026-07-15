package com.pocketagent.android.voice

import com.pocketagent.tools.ToolCallRequest
import com.pocketagent.tools.ToolCallRequestParseResult
import com.pocketagent.tools.toPrimitiveStringMapOrNull
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale

internal enum class VoiceInvocationSource {
    ASSISTANT,
    LOCKED_ASSISTANT,
    UNTRUSTED_ASSISTANT,
    WAKE_WORD,
}

internal enum class VoiceActionRisk {
    REVERSIBLE_DEVICE_STATE,
    SCHEDULED_EFFECT,
    APP_LAUNCH,
}

internal enum class VoiceActionProposalOrigin {
    DETERMINISTIC,
    MODEL,
}

internal sealed interface VoiceActionValidation {
    data class Valid(
        val request: ToolCallRequest,
        val arguments: Map<String, String>,
        val preview: String,
        val risk: VoiceActionRisk,
    ) : VoiceActionValidation

    data class Invalid(val userMessage: String) : VoiceActionValidation
}

/**
 * The single voice-device-action contract used by prompting, pre-execution policy, and Android execution.
 * A model can propose an action, but only this deterministic boundary can authorize its shape and meaning.
 */
internal object VoiceActionCatalog {
    val supportedToolNames: Set<String> = linkedSetOf(
        AndroidLocalToolRuntime.TOOL_ALARM_SET,
        AndroidLocalToolRuntime.TOOL_TIMER_SET,
        AndroidLocalToolRuntime.TOOL_APP_OPEN,
        AndroidLocalToolRuntime.TOOL_VOLUME_SET,
        AndroidLocalToolRuntime.TOOL_FLASHLIGHT_TOGGLE,
    )

    fun validateLegacy(
        toolName: String,
        jsonArguments: String,
        now: LocalDateTime = LocalDateTime.now(),
    ): VoiceActionValidation {
        return when (val parsed = ToolCallRequest.fromLegacy(toolName, jsonArguments)) {
            is ToolCallRequestParseResult.Success -> validate(parsed.request, now)
            is ToolCallRequestParseResult.InvalidJson ->
                VoiceActionValidation.Invalid("The action arguments were not valid JSON.")
        }
    }

    fun validate(
        request: ToolCallRequest,
        now: LocalDateTime = LocalDateTime.now(),
    ): VoiceActionValidation {
        if (request.name !in supportedToolNames) {
            return VoiceActionValidation.Invalid("That phone action is not supported.")
        }
        val arguments = request.arguments.toPrimitiveStringMapOrNull()
            ?: return VoiceActionValidation.Invalid("Phone actions only accept simple values.")
        return when (request.name) {
            AndroidLocalToolRuntime.TOOL_ALARM_SET -> validateAlarm(request, arguments, now)
            AndroidLocalToolRuntime.TOOL_TIMER_SET -> validateTimer(request, arguments)
            AndroidLocalToolRuntime.TOOL_APP_OPEN -> validateAppOpen(request, arguments)
            AndroidLocalToolRuntime.TOOL_VOLUME_SET -> validateVolume(request, arguments)
            AndroidLocalToolRuntime.TOOL_FLASHLIGHT_TOGGLE -> validateFlashlight(request, arguments)
            else -> VoiceActionValidation.Invalid("That phone action is not supported.")
        }
    }

    fun requiresConfirmation(
        source: VoiceInvocationSource,
        action: VoiceActionValidation.Valid,
        transcript: String,
        origin: VoiceActionProposalOrigin = VoiceActionProposalOrigin.DETERMINISTIC,
    ): Boolean {
        // Shape validation cannot prove that a small model's tool call came from the transcript.
        // Keep model-originated mutations behind an explicit preview and confirmation.
        if (origin == VoiceActionProposalOrigin.MODEL) return true
        // A gesture/button assistant invocation is explicit user intent. A passive wake hit can be false,
        // so every mutation from that source gets a second deterministic spoken confirmation.
        if (source != VoiceInvocationSource.ASSISTANT) return true
        // An ISO value produced by the model is not proof that the user supplied AM/PM. Preview ambiguous
        // alarm interpretations even after an explicit assistant gesture.
        return action.request.name == AndroidLocalToolRuntime.TOOL_ALARM_SET &&
            !transcriptHasExplicitAlarmPeriod(transcript)
    }

    fun requiresVisibleSession(
        source: VoiceInvocationSource,
        risk: VoiceActionRisk,
    ): Boolean {
        if (source != VoiceInvocationSource.WAKE_WORD) return true
        return risk == VoiceActionRisk.SCHEDULED_EFFECT || risk == VoiceActionRisk.APP_LAUNCH
    }

    fun buildSystemPrompt(
        now: LocalDateTime = LocalDateTime.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val localTime = now.format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", locale))
        return """
            You are Offas, a local Android voice assistant.
            Current local date and time: $localTime. Time zone: ${zoneId.id}.
            The user message is a speech transcript and may omit punctuation.
            Use exactly one tool when the request maps unambiguously to one supported phone action.
            Supported tools and strict string-only arguments:
            - alarm_set {"title":"Wake up","time_iso":"uuuu-MM-dd'T'HH:mm:ss"}; next local occurrence only
            - timer_set {"label":"Tea","duration_seconds":"300"}; 1 to $MAX_TIMER_SECONDS seconds
            - app_open {"app_name":"Maps"}
            - volume_set {"level_percent":"40"}; 0 to 100
            - flashlight_toggle {"enabled":"on"}; on or off
            Never invent missing AM/PM, a date, duration, app name, or action argument. Ask one concise question instead.
            Never emit multiple tools in one turn. If a tool is required, emit only the tool call.
            If no tool is needed, answer in one concise sentence.
        """.trimIndent()
    }

    private fun validateAlarm(
        request: ToolCallRequest,
        arguments: Map<String, String>,
        now: LocalDateTime,
    ): VoiceActionValidation {
        invalidKeys(arguments, required = setOf("time_iso"), optional = setOf("title"))?.let {
            return VoiceActionValidation.Invalid(it)
        }
        val target = try {
            LocalDateTime.parse(arguments.getValue("time_iso"))
        } catch (_: DateTimeParseException) {
            return VoiceActionValidation.Invalid("Say an exact alarm time, including AM or PM when needed.")
        }
        if (!target.isAfter(now)) {
            return VoiceActionValidation.Invalid("The alarm time must be in the future.")
        }
        if (target.second != 0 || target.nano != 0) {
            return VoiceActionValidation.Invalid("Phone alarms use whole minutes.")
        }
        val nextOccurrence = now
            .withHour(target.hour)
            .withMinute(target.minute)
            .withSecond(0)
            .withNano(0)
            .let { candidate -> if (candidate.isAfter(now)) candidate else candidate.plusDays(1) }
        if (target != nextOccurrence) {
            return VoiceActionValidation.Invalid(
                "Phone alarms currently support only the next occurrence of a time, not an arbitrary date.",
            )
        }
        val title = arguments["title"].orEmpty().trim().ifBlank { "PocketAgent alarm" }
        if (title.length > MAX_LABEL_LENGTH) {
            return VoiceActionValidation.Invalid("The alarm label is too long.")
        }
        val relativeDay = if (target.toLocalDate() == now.toLocalDate()) "today" else "tomorrow"
        val formattedTime = target.toLocalTime().format(
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT),
        )
        return VoiceActionValidation.Valid(
            request = request,
            arguments = arguments + ("title" to title),
            preview = "Set an alarm for $relativeDay at $formattedTime titled $title?",
            risk = VoiceActionRisk.SCHEDULED_EFFECT,
        )
    }

    private fun validateTimer(
        request: ToolCallRequest,
        arguments: Map<String, String>,
    ): VoiceActionValidation {
        invalidKeys(arguments, required = setOf("duration_seconds"), optional = setOf("label"))?.let {
            return VoiceActionValidation.Invalid(it)
        }
        val seconds = arguments.getValue("duration_seconds").toLongOrNull()
            ?: return VoiceActionValidation.Invalid("Say a timer duration.")
        if (seconds !in 1..MAX_TIMER_SECONDS.toLong()) {
            return VoiceActionValidation.Invalid("Timer duration must be between 1 second and 24 hours.")
        }
        val label = arguments["label"].orEmpty().trim().ifBlank { "PocketAgent timer" }
        if (label.length > MAX_LABEL_LENGTH) {
            return VoiceActionValidation.Invalid("The timer label is too long.")
        }
        return VoiceActionValidation.Valid(
            request = request,
            arguments = arguments + ("label" to label),
            preview = "Start a ${formatDuration(seconds)} timer titled $label?",
            risk = VoiceActionRisk.SCHEDULED_EFFECT,
        )
    }

    private fun validateAppOpen(
        request: ToolCallRequest,
        arguments: Map<String, String>,
    ): VoiceActionValidation {
        invalidKeys(arguments, required = setOf("app_name"), optional = emptySet())?.let {
            return VoiceActionValidation.Invalid(it)
        }
        val appName = arguments.getValue("app_name").trim()
        if (appName.isEmpty() || appName.length > MAX_APP_NAME_LENGTH) {
            return VoiceActionValidation.Invalid("Say the name of one app to open.")
        }
        return VoiceActionValidation.Valid(
            request = request,
            arguments = arguments + ("app_name" to appName),
            preview = "Open $appName?",
            risk = VoiceActionRisk.APP_LAUNCH,
        )
    }

    private fun validateVolume(
        request: ToolCallRequest,
        arguments: Map<String, String>,
    ): VoiceActionValidation {
        invalidKeys(arguments, required = setOf("level_percent"), optional = emptySet())?.let {
            return VoiceActionValidation.Invalid(it)
        }
        val percent = arguments.getValue("level_percent").toIntOrNull()
        if (percent == null || percent !in 0..100) {
            return VoiceActionValidation.Invalid("Media volume must be between 0 and 100 percent.")
        }
        return VoiceActionValidation.Valid(
            request = request,
            arguments = arguments,
            preview = "Set media volume to $percent percent?",
            risk = VoiceActionRisk.REVERSIBLE_DEVICE_STATE,
        )
    }

    private fun validateFlashlight(
        request: ToolCallRequest,
        arguments: Map<String, String>,
    ): VoiceActionValidation {
        invalidKeys(arguments, required = setOf("enabled"), optional = emptySet())?.let {
            return VoiceActionValidation.Invalid(it)
        }
        val enabled = when (arguments.getValue("enabled").trim().lowercase(Locale.ROOT)) {
            "on", "true" -> true
            "off", "false" -> false
            else -> return VoiceActionValidation.Invalid("Say whether to turn the flashlight on or off.")
        }
        return VoiceActionValidation.Valid(
            request = request,
            arguments = arguments + ("enabled" to if (enabled) "on" else "off"),
            preview = if (enabled) "Turn the flashlight on?" else "Turn the flashlight off?",
            risk = VoiceActionRisk.REVERSIBLE_DEVICE_STATE,
        )
    }

    private fun invalidKeys(
        arguments: Map<String, String>,
        required: Set<String>,
        optional: Set<String>,
    ): String? {
        val missing = required - arguments.keys
        if (missing.isNotEmpty()) {
            return "The action is missing ${missing.sorted().joinToString()}."
        }
        val unknown = arguments.keys - required - optional
        if (unknown.isNotEmpty()) {
            return "The action included unsupported fields: ${unknown.sorted().joinToString()}."
        }
        return null
    }

    private fun formatDuration(seconds: Long): String {
        val hours = seconds / 3_600
        val minutes = (seconds % 3_600) / 60
        val remainingSeconds = seconds % 60
        return buildList {
            if (hours > 0) add("$hours hour${if (hours == 1L) "" else "s"}")
            if (minutes > 0) add("$minutes minute${if (minutes == 1L) "" else "s"}")
            if (remainingSeconds > 0) {
                add("$remainingSeconds second${if (remainingSeconds == 1L) "" else "s"}")
            }
        }.joinToString(" ")
    }

    private fun transcriptHasExplicitAlarmPeriod(transcript: String): Boolean {
        val normalized = transcript.lowercase(Locale.ROOT)
        val hour = "(?:\\d{1,2}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)"
        val period = "(?:a\\s?m|p\\s?m|in the morning|in the afternoon|in the evening|tonight)"
        return Regex("\\b(?:at|for)\\s+$hour(?::[0-5][0-9])?\\s*$period\\b")
            .containsMatchIn(normalized) ||
            Regex("\\b(?:at|for)\\s+(?:noon|midnight)\\b").containsMatchIn(normalized) ||
            Regex("\\b(?:at|for)\\s+(?:1[3-9]|2[0-3]):[0-5][0-9]\\b").containsMatchIn(normalized)
    }

    private const val MAX_TIMER_SECONDS = 86_400
    private const val MAX_LABEL_LENGTH = 80
    private const val MAX_APP_NAME_LENGTH = 80
}

internal enum class VoiceConfirmationDecision {
    CONFIRM,
    CANCEL,
    UNKNOWN,
}

internal fun parseVoiceConfirmation(transcript: String): VoiceConfirmationDecision {
    val normalized = transcript
        .trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    return when (normalized) {
        "yes", "yes please", "confirm", "do it", "go ahead", "okay", "ok" ->
            VoiceConfirmationDecision.CONFIRM
        "no", "no thanks", "cancel", "stop", "never mind", "nevermind" ->
            VoiceConfirmationDecision.CANCEL
        else -> VoiceConfirmationDecision.UNKNOWN
    }
}

/** One-shot in-process capability: raw foreground-chat tool calls cannot mutate the phone. */
internal object VoiceActionExecutionAuthorization {
    private data class Permit(val toolName: String, val canonicalArguments: String)

    private val activePermit = ThreadLocal<Permit?>()

    fun <T> runAuthorized(
        toolName: String,
        jsonArguments: String,
        block: () -> T,
    ): T {
        val canonicalArguments = when (val parsed = ToolCallRequest.fromLegacy(toolName, jsonArguments)) {
            is ToolCallRequestParseResult.Success -> parsed.request.arguments.toJsonString()
            is ToolCallRequestParseResult.InvalidJson -> jsonArguments
        }
        check(activePermit.get() == null) { "A voice action authorization is already active." }
        activePermit.set(Permit(toolName, canonicalArguments))
        return try {
            block()
        } finally {
            activePermit.remove()
        }
    }

    fun consume(request: ToolCallRequest): Boolean {
        val expected = Permit(request.name, request.arguments.toJsonString())
        if (activePermit.get() != expected) return false
        activePermit.remove()
        return true
    }
}
