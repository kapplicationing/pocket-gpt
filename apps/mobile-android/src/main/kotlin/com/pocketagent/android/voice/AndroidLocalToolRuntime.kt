package com.pocketagent.android.voice

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import com.pocketagent.tools.SafeLocalToolRuntime
import com.pocketagent.tools.ToolCall
import com.pocketagent.tools.ToolModule
import com.pocketagent.tools.ToolResult
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AndroidLocalToolRuntime(
    context: Context,
    private val base: ToolModule = SafeLocalToolRuntime(),
) : ToolModule {
    private val appContext = context.applicationContext
    private val parser = Json { ignoreUnknownKeys = false }
    private val customTools = setOf(
        TOOL_ALARM_SET,
        TOOL_TIMER_SET,
        TOOL_APP_OPEN,
        TOOL_VOLUME_SET,
        TOOL_FLASHLIGHT_TOGGLE,
    )

    override fun listEnabledTools(): List<String> {
        return (base.listEnabledTools() + customTools).distinct().sorted()
    }

    override fun validateToolCall(call: ToolCall): Boolean {
        return when (call.name) {
            in customTools -> parseArgs(call.jsonArgs) != null
            else -> base.validateToolCall(call)
        }
    }

    override fun executeToolCall(call: ToolCall): ToolResult {
        if (call.name !in customTools) {
            return base.executeToolCall(call)
        }
        val args = parseArgs(call.jsonArgs) ?: return ToolResult(false, "Invalid tool JSON.")
        return when (call.name) {
            TOOL_ALARM_SET -> setAlarm(
                title = args["title"].orEmpty().ifBlank { "Offas alarm" },
                timeIso = args["time_iso"].orEmpty(),
            )
            TOOL_TIMER_SET -> setTimer(
                label = args["label"].orEmpty().ifBlank { "Offas timer" },
                durationSeconds = args["duration_seconds"].orEmpty(),
            )
            TOOL_APP_OPEN -> openApp(appName = args["app_name"].orEmpty())
            TOOL_VOLUME_SET -> setVolume(levelPercent = args["level_percent"].orEmpty())
            TOOL_FLASHLIGHT_TOGGLE -> setFlashlight(mode = args["enabled"].orEmpty())
            else -> ToolResult(false, "Unknown tool.")
        }
    }

    private fun parseArgs(jsonArgs: String): Map<String, String>? {
        return runCatching {
            parser.parseToJsonElement(jsonArgs).jsonObject.mapValues { (_, value) ->
                value.jsonPrimitive.content
            }
        }.getOrNull()
    }

    private fun setAlarm(title: String, timeIso: String): ToolResult {
        val localTime = try {
            LocalDateTime.parse(timeIso)
        } catch (_: DateTimeParseException) {
            return ToolResult(false, "alarm_set requires ISO local time in time_iso.")
        }
        val triggerAtMs = localTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (triggerAtMs <= System.currentTimeMillis()) {
            return ToolResult(false, "Alarm time must be in the future.")
        }
        val intent = Intent(appContext, OffasAlarmReceiver::class.java).apply {
            action = ACTION_OFFAS_ALARM
            putExtra(EXTRA_LABEL, title)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            triggerAtMs.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val showIntent = PendingIntent.getActivity(
            appContext,
            triggerAtMs.hashCode(),
            appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
                ?: Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerAtMs, showIntent),
            pendingIntent,
        )
        return ToolResult(true, "Alarm set for ${localTime.toLocalTime()} (${title}).")
    }

    private fun setTimer(label: String, durationSeconds: String): ToolResult {
        val seconds = durationSeconds.toLongOrNull()?.coerceAtLeast(1L)
            ?: return ToolResult(false, "timer_set requires duration_seconds as a positive integer string.")
        val triggerAtMs = System.currentTimeMillis() + seconds * 1000L
        val intent = Intent(appContext, OffasAlarmReceiver::class.java).apply {
            action = ACTION_OFFAS_TIMER
            putExtra(EXTRA_LABEL, label)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            triggerAtMs.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
            else ->
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
        }
        return ToolResult(true, "Timer set for ${seconds}s (${label}).")
    }

    private fun openApp(appName: String): ToolResult {
        if (appName.isBlank()) {
            return ToolResult(false, "app_open requires app_name.")
        }
        val packageManager = appContext.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val candidates = packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
        val target = candidates.firstOrNull { resolveInfo ->
            val label = resolveInfo.loadLabel(packageManager)?.toString().orEmpty()
            label.contains(appName, ignoreCase = true)
        } ?: return ToolResult(false, "Could not find an installed app matching '$appName'.")
        val launchIntent = packageManager.getLaunchIntentForPackage(target.activityInfo.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            ?: return ToolResult(false, "App '$appName' is installed but cannot be launched.")
        return runCatching {
            appContext.startActivity(launchIntent)
            ToolResult(true, "Opened ${target.loadLabel(packageManager)}.")
        }.getOrElse { error ->
            ToolResult(false, "Failed to open '$appName': ${error.message ?: "blocked by Android"}")
        }
    }

    private fun setVolume(levelPercent: String): ToolResult {
        val level = levelPercent.toIntOrNull()?.coerceIn(0, 100)
            ?: return ToolResult(false, "volume_set requires level_percent between 0 and 100.")
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val targetVolume = ((maxVolume * level) / 100.0).toInt().coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, AudioManager.FLAG_SHOW_UI)
        return ToolResult(true, "Media volume set to ${level}%.")
    }

    private fun setFlashlight(mode: String): ToolResult {
        val normalized = mode.trim().lowercase()
        val enabled = when (normalized) {
            "on", "true", "1" -> true
            "off", "false", "0" -> false
            else -> return ToolResult(false, "flashlight_toggle requires enabled to be 'on' or 'off'.")
        }
        val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return ToolResult(false, "No flashlight-capable camera found.")
        return runCatching {
            cameraManager.setTorchMode(cameraId, enabled)
            ToolResult(true, if (enabled) "Flashlight turned on." else "Flashlight turned off.")
        }.getOrElse { error ->
            ToolResult(false, "Failed to change flashlight: ${error.message ?: "unknown error"}")
        }
    }

    companion object {
        const val TOOL_ALARM_SET = "alarm_set"
        const val TOOL_TIMER_SET = "timer_set"
        const val TOOL_APP_OPEN = "app_open"
        const val TOOL_VOLUME_SET = "volume_set"
        const val TOOL_FLASHLIGHT_TOGGLE = "flashlight_toggle"

        const val ACTION_OFFAS_ALARM = "com.pocketagent.android.voice.ACTION_OFFAS_ALARM"
        const val ACTION_OFFAS_TIMER = "com.pocketagent.android.voice.ACTION_OFFAS_TIMER"
        const val EXTRA_LABEL = "label"
    }
}
