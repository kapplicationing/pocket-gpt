package com.pocketagent.android.voice

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val VOICE_ACTIVATION_PREFS_NAME = "pocketagent_voice_activation"
private const val KEY_ENABLED = "enabled"
private const val KEY_WAKE_PHRASE = "wake_phrase"
private const val KEY_SILENCE_TIMEOUT_SECONDS = "silence_timeout_seconds"
private const val KEY_SERVICE_STATE = "service_state"
private const val KEY_LAST_ERROR = "last_error"

enum class VoiceServiceState {
    DISABLED,
    STARTING,
    LISTENING,
    CAPTURING,
    TRANSCRIBING,
    PROCESSING,
    PAUSED,
    ERROR,
}

data class VoiceActivationSettings(
    val enabled: Boolean = false,
    val wakePhrase: String = DEFAULT_WAKE_PHRASE,
    val silenceTimeoutSeconds: Int = DEFAULT_SILENCE_TIMEOUT_SECONDS,
    val voiceServiceState: VoiceServiceState = VoiceServiceState.DISABLED,
    val lastError: String? = null,
)

data class VoiceActivationUiState(
    val settings: VoiceActivationSettings = VoiceActivationSettings(),
    val assistantRoleSupported: Boolean = false,
    val assistantRoleHeld: Boolean = false,
    val batteryOptimizationIgnored: Boolean = false,
    val oemGuide: OemBatteryGuide? = null,
    val modelsReady: Boolean = false,
    val missingModelPaths: List<String> = emptyList(),
)

enum class OemBatteryGuide(
    val title: String,
    val summary: String,
) {
    SAMSUNG(
        title = "Samsung battery setup",
        summary = "Add Offas to Never sleeping apps in Battery > Background usage limits.",
    ),
    XIAOMI(
        title = "Xiaomi / POCO battery setup",
        summary = "Enable Autostart and set Battery saver to No restrictions.",
    ),
    HUAWEI(
        title = "Huawei battery setup",
        summary = "Mark Offas as manually managed in App launch settings.",
    ),
    OPPO(
        title = "OPPO / Realme battery setup",
        summary = "Allow auto launch and background activity in app battery settings.",
    ),
    GENERIC(
        title = "Battery optimization",
        summary = "Exclude Offas from battery optimizations for reliable background listening.",
    ),
    ;

    companion object {
        fun fromManufacturer(manufacturer: String?): OemBatteryGuide {
            val normalized = manufacturer?.trim()?.lowercase().orEmpty()
            return when {
                normalized.contains("samsung") -> SAMSUNG
                normalized.contains("xiaomi") || normalized.contains("poco") -> XIAOMI
                normalized.contains("huawei") || normalized.contains("honor") -> HUAWEI
                normalized.contains("oppo") || normalized.contains("realme") -> OPPO
                else -> GENERIC
            }
        }
    }
}

internal interface VoiceActivationSettingsStorage {
    fun getBoolean(key: String, defaultValue: Boolean): Boolean

    fun getString(key: String, defaultValue: String?): String?

    fun getInt(key: String, defaultValue: Int): Int

    fun save(settings: VoiceActivationSettings)
}

private class SharedPreferencesVoiceActivationSettingsStorage(
    private val prefs: SharedPreferences,
) : VoiceActivationSettingsStorage {
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = prefs.getBoolean(key, defaultValue)

    override fun getString(key: String, defaultValue: String?): String? = prefs.getString(key, defaultValue)

    override fun getInt(key: String, defaultValue: Int): Int = prefs.getInt(key, defaultValue)

    override fun save(settings: VoiceActivationSettings) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putString(KEY_WAKE_PHRASE, settings.wakePhrase)
            .putInt(KEY_SILENCE_TIMEOUT_SECONDS, settings.silenceTimeoutSeconds)
            .putString(KEY_SERVICE_STATE, settings.voiceServiceState.name)
            .putString(KEY_LAST_ERROR, settings.lastError)
            .apply()
    }
}

class VoiceActivationSettingsStore private constructor(
    private val storage: VoiceActivationSettingsStorage,
    private val stateFlow: MutableStateFlow<VoiceActivationSettings>,
) {
    constructor(
        context: Context,
    ) : this(createStorage(context.applicationContext))

    internal constructor(
        storage: VoiceActivationSettingsStorage,
    ) : this(storage, MutableStateFlow(readSettings(storage)))

    fun state(): VoiceActivationSettings = stateFlow.value

    fun observe(): StateFlow<VoiceActivationSettings> = stateFlow.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        save(read().copy(enabled = enabled, voiceServiceState = if (enabled) VoiceServiceState.STARTING else VoiceServiceState.DISABLED))
    }

    fun updateServiceState(state: VoiceServiceState, error: String? = stateFlow.value.lastError) {
        save(read().copy(voiceServiceState = state, lastError = error))
    }

    fun setLastError(error: String?) {
        save(read().copy(lastError = error))
    }

    private fun save(settings: VoiceActivationSettings) {
        storage.save(settings)
        stateFlow.value = settings
    }

    private fun read(): VoiceActivationSettings {
        return readSettings(storage)
    }

    companion object {
        private fun createStorage(context: Context): VoiceActivationSettingsStorage {
            return SharedPreferencesVoiceActivationSettingsStorage(
                context.getSharedPreferences(VOICE_ACTIVATION_PREFS_NAME, Context.MODE_PRIVATE),
            )
        }

        private fun readSettings(storage: VoiceActivationSettingsStorage): VoiceActivationSettings {
            val storedState = storage.getString(KEY_SERVICE_STATE, VoiceServiceState.DISABLED.name)
            return VoiceActivationSettings(
                enabled = storage.getBoolean(KEY_ENABLED, false),
                wakePhrase = storage.getString(KEY_WAKE_PHRASE, DEFAULT_WAKE_PHRASE) ?: DEFAULT_WAKE_PHRASE,
                silenceTimeoutSeconds = storage.getInt(KEY_SILENCE_TIMEOUT_SECONDS, DEFAULT_SILENCE_TIMEOUT_SECONDS)
                    .coerceIn(3, 10),
                voiceServiceState = VoiceServiceState.entries.firstOrNull { it.name == storedState } ?: VoiceServiceState.DISABLED,
                lastError = storage.getString(KEY_LAST_ERROR, null),
            )
        }
    }
}

class VoiceActivationController(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val settingsStore = VoiceActivationSettingsStore(appContext)
    private val stateFlow = MutableStateFlow(computeState())

    fun observe(): StateFlow<VoiceActivationUiState> = stateFlow.asStateFlow()

    fun refresh() {
        stateFlow.value = computeState()
    }

    fun setEnabled(enabled: Boolean) {
        settingsStore.setEnabled(enabled)
        if (enabled) {
            OffasRuntime.start(appContext)
        } else {
            OffasRuntime.stop(appContext)
        }
        refresh()
    }

    fun silenceTimeoutSeconds(): Int = settingsStore.state().silenceTimeoutSeconds

    fun requestBatteryOptimizationIntent(): Intent {
        return Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${appContext.packageName}"),
        )
    }

    fun requestAssistantRoleIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null
        }
        val roleManager = appContext.getSystemService(RoleManager::class.java) ?: return null
        if (!roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
            return null
        }
        return roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
    }

    fun openAppSettingsIntent(): Intent {
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", appContext.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun computeState(): VoiceActivationUiState {
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryOptimizationIgnored = powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) == true
        val assistantRoleSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val assistantRoleHeld = if (assistantRoleSupported) {
            val roleManager = appContext.getSystemService(RoleManager::class.java)
            roleManager?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        } else {
            false
        }
        val modelStatus = VoiceModelCatalog.status(appContext)
        return VoiceActivationUiState(
            settings = settingsStore.state(),
            assistantRoleSupported = assistantRoleSupported,
            assistantRoleHeld = assistantRoleHeld,
            batteryOptimizationIgnored = batteryOptimizationIgnored,
            oemGuide = OemBatteryGuide.fromManufacturer(Build.MANUFACTURER),
            modelsReady = modelStatus.ready,
            missingModelPaths = modelStatus.missingPaths,
        )
    }
}

class AssistActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OffasRuntime.captureOnce(applicationContext)
        setResult(Activity.RESULT_OK)
        finish()
    }
}

internal const val DEFAULT_WAKE_PHRASE = "Offas"
internal const val DEFAULT_SILENCE_TIMEOUT_SECONDS = 5
