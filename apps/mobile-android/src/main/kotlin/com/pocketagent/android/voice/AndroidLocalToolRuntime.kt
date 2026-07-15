package com.pocketagent.android.voice

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.provider.AlarmClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.pocketagent.tools.SafeLocalToolRuntime
import com.pocketagent.tools.ToolCall
import com.pocketagent.tools.ToolCallRequest
import com.pocketagent.tools.ToolCallRequestParseResult
import com.pocketagent.tools.ToolModule
import com.pocketagent.tools.ToolResult
import java.time.LocalDateTime
import java.util.Locale

class AndroidLocalToolRuntime private constructor(
    private val deviceActions: AndroidDeviceActions,
    private val base: ToolModule,
) : ToolModule {
    constructor(
        context: Context,
        base: ToolModule = SafeLocalToolRuntime(),
    ) : this(
        deviceActions = DefaultAndroidDeviceActions(context.applicationContext),
        base = base,
    )

    internal constructor(
        deviceActions: AndroidDeviceActions,
        base: ToolModule = SafeLocalToolRuntime(),
        @Suppress("UNUSED_PARAMETER") testBoundary: Unit = Unit,
    ) : this(deviceActions = deviceActions, base = base)

    override fun listEnabledTools(): List<String> {
        return (base.listEnabledTools() + VoiceActionCatalog.supportedToolNames).distinct().sorted()
    }

    override fun validateToolCall(call: ToolCall): Boolean {
        if (call.name !in VoiceActionCatalog.supportedToolNames) {
            return base.validateToolCall(call)
        }
        return VoiceActionCatalog.validateLegacy(call.name, call.jsonArgs) is VoiceActionValidation.Valid
    }

    override fun validateToolRequest(request: ToolCallRequest): Boolean {
        return if (request.name in VoiceActionCatalog.supportedToolNames) {
            VoiceActionCatalog.validate(request) is VoiceActionValidation.Valid
        } else {
            base.validateToolRequest(request)
        }
    }

    override fun executeToolCall(call: ToolCall): ToolResult {
        if (call.name !in VoiceActionCatalog.supportedToolNames) {
            return base.executeToolCall(call)
        }
        return when (val parsed = ToolCallRequest.fromLegacy(call)) {
            is ToolCallRequestParseResult.Success -> executeToolRequest(parsed.request)
            is ToolCallRequestParseResult.InvalidJson -> invalidResult("The action arguments were not valid JSON.")
        }
    }

    override fun executeToolRequest(request: ToolCallRequest): ToolResult {
        if (request.name !in VoiceActionCatalog.supportedToolNames) {
            return base.executeToolRequest(request)
        }
        return when (val validation = VoiceActionCatalog.validate(request)) {
            is VoiceActionValidation.Invalid -> invalidResult(validation.userMessage)
            is VoiceActionValidation.Valid -> {
                if (!VoiceActionExecutionAuthorization.consume(request)) {
                    ToolResult(
                        success = false,
                        content = "This phone action needs an authorized voice action plan.",
                        validationErrorCode = "voice_action_not_authorized",
                        validationErrorDetail = "Raw phone-action execution was blocked.",
                    )
                } else {
                    deviceActions.execute(request.name, validation.arguments)
                }
            }
        }
    }

    private fun invalidResult(message: String): ToolResult {
        return ToolResult(
            success = false,
            content = message,
            validationErrorCode = "invalid_voice_action",
            validationErrorDetail = message,
        )
    }

    companion object {
        const val TOOL_ALARM_SET = "alarm_set"
        const val TOOL_TIMER_SET = "timer_set"
        const val TOOL_APP_OPEN = "app_open"
        const val TOOL_VOLUME_SET = "volume_set"
        const val TOOL_FLASHLIGHT_TOGGLE = "flashlight_toggle"
    }
}

internal interface AndroidDeviceActions {
    fun execute(toolName: String, arguments: Map<String, String>): ToolResult
}

private class DefaultAndroidDeviceActions(
    private val appContext: Context,
) : AndroidDeviceActions {
    override fun execute(toolName: String, arguments: Map<String, String>): ToolResult {
        val result = when (toolName) {
            AndroidLocalToolRuntime.TOOL_ALARM_SET -> setSystemAlarm(arguments)
            AndroidLocalToolRuntime.TOOL_TIMER_SET -> setSystemTimer(arguments)
            AndroidLocalToolRuntime.TOOL_APP_OPEN -> openApp(arguments.getValue("app_name"))
            AndroidLocalToolRuntime.TOOL_VOLUME_SET -> setVolume(arguments.getValue("level_percent"))
            AndroidLocalToolRuntime.TOOL_FLASHLIGHT_TOGGLE -> setFlashlight(arguments.getValue("enabled"))
            else -> ToolResult(false, "That phone action is not supported.")
        }
        Log.i(
            ACTION_LOG_TAG,
            "VOICE_ACTION_RESULT|tool=$toolName|success=${result.success}|code=${result.validationErrorCode.orEmpty()}",
        )
        return result
    }

    private fun setSystemAlarm(arguments: Map<String, String>): ToolResult {
        val localTime = LocalDateTime.parse(arguments.getValue("time_iso"))
        val title = arguments.getValue("title")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, localTime.hour)
            putExtra(AlarmClock.EXTRA_MINUTES, localTime.minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, title)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchClockIntent(intent) {
            "Asked Clock to set the alarm for ${localTime.toLocalTime()} ($title)."
        }
    }

    private fun setSystemTimer(arguments: Map<String, String>): ToolResult {
        val seconds = arguments.getValue("duration_seconds").toInt()
        val label = arguments.getValue("label")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchClockIntent(intent) {
            "Asked Clock to start a ${formatDurationForReceipt(seconds)} timer ($label)."
        }
    }

    private fun launchClockIntent(intent: Intent, successMessage: () -> String): ToolResult {
        if (ContextCompat.checkSelfPermission(appContext, SET_ALARM_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            return ToolResult(false, "PocketAgent is not allowed to create system alarms and timers.")
        }
        if (intent.resolveActivity(appContext.packageManager) == null) {
            return ToolResult(false, "No compatible Clock app is installed.")
        }
        VoiceSessionVisibility.markExternalActivityStarting()
        return try {
            appContext.startActivity(intent)
            ToolResult(true, successMessage())
        } catch (_: ActivityNotFoundException) {
            VoiceSessionVisibility.cancelExternalActivityStarting()
            ToolResult(false, "No compatible Clock app is installed.")
        } catch (error: SecurityException) {
            VoiceSessionVisibility.cancelExternalActivityStarting()
            actionFailure("Clock blocked this request.", error)
        } catch (error: IllegalStateException) {
            VoiceSessionVisibility.cancelExternalActivityStarting()
            actionFailure("Clock could not handle this request.", error)
        }
    }

    private fun openApp(appName: String): ToolResult {
        val packageManager = appContext.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val candidates = packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
            .mapNotNull { resolveInfo ->
                val label = resolveInfo.loadLabel(packageManager)?.toString()?.trim().orEmpty()
                label.takeIf(String::isNotEmpty)?.let { AppCandidate(label, resolveInfo.activityInfo.packageName) }
            }
            .distinctBy(AppCandidate::packageName)
        val normalizedQuery = normalizeAppName(appName)
        val exactMatches = candidates.filter { normalizeAppName(it.label) == normalizedQuery }
        val matches = if (exactMatches.isNotEmpty()) {
            exactMatches
        } else {
            candidates.filter { candidate ->
                val normalizedLabel = normalizeAppName(candidate.label)
                normalizedLabel.split(' ').any { it == normalizedQuery } ||
                    normalizedLabel.startsWith("$normalizedQuery ") ||
                    normalizedLabel.endsWith(" $normalizedQuery")
            }
        }
        if (matches.isEmpty()) {
            return ToolResult(false, "Could not find an installed app named '$appName'.")
        }
        if (matches.size > 1) {
            val labels = matches.map(AppCandidate::label).distinct().sorted().take(3)
            return ToolResult(
                false,
                "More than one app matches '$appName': ${labels.joinToString()}. Say the exact name.",
            )
        }
        val target = matches.single()
        val launchIntent = packageManager.getLaunchIntentForPackage(target.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            ?: return ToolResult(false, "${target.label} is installed but cannot be opened.")
        VoiceSessionVisibility.markExternalActivityStarting()
        return try {
            appContext.startActivity(launchIntent)
            ToolResult(true, "Opened ${target.label}.")
        } catch (_: ActivityNotFoundException) {
            VoiceSessionVisibility.cancelExternalActivityStarting()
            ToolResult(false, "${target.label} is installed but cannot be opened.")
        } catch (error: SecurityException) {
            VoiceSessionVisibility.cancelExternalActivityStarting()
            actionFailure("Could not open ${target.label}.", error)
        }
    }

    private fun setVolume(levelPercent: String): ToolResult {
        val level = levelPercent.toInt()
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (audioManager.isVolumeFixed) {
            return ToolResult(false, "Media volume is fixed on this device.")
        }
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVolume <= 0) {
            return ToolResult(false, "Media volume is unavailable on this device.")
        }
        val targetVolume = ((maxVolume * level) / 100.0).toInt().coerceIn(0, maxVolume)
        return try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, AudioManager.FLAG_SHOW_UI)
            val actualIndex = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val actualPercent = ((actualIndex * 100.0) / maxVolume).toInt().coerceIn(0, 100)
            ToolResult(true, "Media volume is now $actualPercent percent.")
        } catch (error: SecurityException) {
            actionFailure("Android blocked the volume change.", error)
        } catch (error: IllegalArgumentException) {
            actionFailure("Media volume could not be changed.", error)
        }
    }

    private fun setFlashlight(mode: String): ToolResult {
        if (
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            if (VoiceSessionVisibility.isVisible()) {
                VoiceSessionSignals.requestCameraPermission(appContext)
            }
            return ToolResult(false, "Camera permission is required to change the flashlight.")
        }
        val enabled = mode == "on"
        val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return ToolResult(false, "No flashlight is available on this device.")
        val cameraId = runCatching {
            cameraManager.cameraIdList
                .mapNotNull { id ->
                    runCatching { id to cameraManager.getCameraCharacteristics(id) }.getOrNull()
                }
                .filter { (_, characteristics) ->
                    characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
                .sortedBy { (_, characteristics) ->
                    if (
                        characteristics.get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_BACK
                    ) {
                        0
                    } else {
                        1
                    }
                }
                .firstOrNull()
                ?.first
        }.getOrElse { error ->
            return actionFailure("Flashlight information is unavailable.", error)
        } ?: return ToolResult(false, "No flashlight is available on this device.")
        return try {
            cameraManager.setTorchMode(cameraId, enabled)
            ToolResult(
                true,
                if (enabled) "Requested flashlight on." else "Requested flashlight off.",
            )
        } catch (error: SecurityException) {
            actionFailure("Android blocked the flashlight change.", error)
        } catch (error: CameraAccessException) {
            actionFailure("Flashlight could not be changed.", error)
        } catch (error: IllegalArgumentException) {
            actionFailure("Flashlight could not be changed.", error)
        }
    }

    private data class AppCandidate(val label: String, val packageName: String)

    private companion object {
        private const val SET_ALARM_PERMISSION = "com.android.alarm.permission.SET_ALARM"
        private const val ACTION_LOG_TAG = "PocketAgentVoiceAction"

        private fun normalizeAppName(value: String): String {
            return value.trim()
                .lowercase(Locale.ROOT)
                .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        private fun actionFailure(message: String, error: Throwable): ToolResult {
            Log.w("PocketAgentVoiceAction", message, error)
            return ToolResult(false, message)
        }

        private fun formatDurationForReceipt(seconds: Int): String {
            val hours = seconds / 3_600
            val minutes = (seconds % 3_600) / 60
            val remainingSeconds = seconds % 60
            return buildList {
                if (hours > 0) add("$hours hour${if (hours == 1) "" else "s"}")
                if (minutes > 0) add("$minutes minute${if (minutes == 1) "" else "s"}")
                if (remainingSeconds > 0) {
                    add("$remainingSeconds second${if (remainingSeconds == 1) "" else "s"}")
                }
            }.joinToString(" ")
        }
    }
}
