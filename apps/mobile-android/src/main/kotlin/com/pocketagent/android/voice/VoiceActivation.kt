package com.pocketagent.android.voice

import android.Manifest
import android.app.role.RoleManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val VOICE_ACTIVATION_PREFS_NAME = "pocketagent_voice_activation"
private const val KEY_ENABLED = "enabled"
private const val KEY_WAKE_PHRASE = "wake_phrase"
private const val KEY_SILENCE_TIMEOUT_SECONDS = "silence_timeout_seconds"
private const val KEY_SERVICE_STATE = "service_state"
private const val KEY_LAST_ERROR = "last_error"
private const val KEY_ENABLED_AT_EPOCH_MS = "enabled_at_epoch_ms"

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
    val enabledAtEpochMs: Long = 0L,
)

data class VoiceActivationUiState(
    val settings: VoiceActivationSettings = VoiceActivationSettings(),
    val notificationPermissionGranted: Boolean = false,
    val microphonePermissionGranted: Boolean = false,
    val assistantRoleSupported: Boolean = false,
    val assistantRoleHeld: Boolean = false,
    val batteryOptimizationIgnored: Boolean = false,
    val oemGuide: OemBatteryGuide? = null,
    val modelsReady: Boolean = false,
    val dedicatedWakeWordReady: Boolean = false,
    val modelSetup: VoiceModelSetupState = VoiceModelSetupState(),
    val modelsRootPath: String = "",
    val missingModelPaths: List<String> = emptyList(),
    val missingWakeWordPaths: List<String> = emptyList(),
    val betaContract: VoiceBetaContract = VoiceBetaContract(),
)

enum class VoiceBetaBlockingIssue {
    NOTIFICATION_PERMISSION,
    MICROPHONE_PERMISSION,
    ASSISTANT_NOT_SELECTED,
    MODELS_MISSING,
    WAKE_WORD_MODEL_MISSING,
}

data class VoiceBetaContract(
    val blockingIssue: VoiceBetaBlockingIssue? = null,
    val needsBatteryGuidance: Boolean = false,
    val needsAssistantRole: Boolean = false,
) {
    val canEnableAlwaysOnListening: Boolean
        get() = blockingIssue == null
}

enum class VoiceActivationEnableResult {
    ENABLED,
    DISABLED,
    SETUP_STARTED,
    BLOCKED_NOTIFICATION_PERMISSION,
    BLOCKED_MICROPHONE_PERMISSION,
    BLOCKED_ASSISTANT_NOT_SELECTED,
    BLOCKED_MODELS_MISSING,
    BLOCKED_WAKE_WORD_MODEL_MISSING,
    START_FAILED,
}

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

    fun getLong(key: String, defaultValue: Long): Long

    fun save(settings: VoiceActivationSettings)
}

private class SharedPreferencesVoiceActivationSettingsStorage(
    private val prefs: SharedPreferences,
) : VoiceActivationSettingsStorage {
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = prefs.getBoolean(key, defaultValue)

    override fun getString(key: String, defaultValue: String?): String? = prefs.getString(key, defaultValue)

    override fun getInt(key: String, defaultValue: Int): Int = prefs.getInt(key, defaultValue)

    override fun getLong(key: String, defaultValue: Long): Long = prefs.getLong(key, defaultValue)

    override fun save(settings: VoiceActivationSettings) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putString(KEY_WAKE_PHRASE, settings.wakePhrase)
            .putInt(KEY_SILENCE_TIMEOUT_SECONDS, settings.silenceTimeoutSeconds)
            .putString(KEY_SERVICE_STATE, settings.voiceServiceState.name)
            .putString(KEY_LAST_ERROR, settings.lastError)
            .putLong(KEY_ENABLED_AT_EPOCH_MS, settings.enabledAtEpochMs)
            // Stop/enable intent must survive an immediate process kill; VIS may otherwise
            // resurrect a listener that the user just turned off.
            .commit()
    }
}

class VoiceActivationSettingsStore private constructor(
    private val storage: VoiceActivationSettingsStorage,
    private val stateFlow: MutableStateFlow<VoiceActivationSettings>,
) {
    private val updateLock = Any()

    internal constructor(
        storage: VoiceActivationSettingsStorage,
    ) : this(storage, MutableStateFlow(readSettings(storage)))

    fun state(): VoiceActivationSettings = stateFlow.value

    fun observe(): StateFlow<VoiceActivationSettings> = stateFlow.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        update { current ->
            current.copy(
                enabled = enabled,
                voiceServiceState = if (enabled) VoiceServiceState.STARTING else VoiceServiceState.DISABLED,
                lastError = null,
                enabledAtEpochMs = if (enabled) System.currentTimeMillis() else current.enabledAtEpochMs,
            )
        }
    }

    fun updateServiceState(
        state: VoiceServiceState,
        error: String? = null,
    ) {
        update { current ->
            current.copy(
                voiceServiceState = state,
                lastError = if (state == VoiceServiceState.ERROR && error == null) {
                    current.lastError
                } else {
                    error
                },
            )
        }
    }

    fun setLastError(error: String?) {
        update { current -> current.copy(lastError = error) }
    }

    fun disableWithError(error: String) {
        update { current ->
            current.copy(
                enabled = false,
                voiceServiceState = VoiceServiceState.DISABLED,
                lastError = error,
            )
        }
    }

    private fun update(transform: (VoiceActivationSettings) -> VoiceActivationSettings) {
        synchronized(updateLock) {
            val settings = transform(stateFlow.value)
            storage.save(settings)
            stateFlow.value = settings
        }
    }

    companion object {
        @Volatile
        private var processInstance: VoiceActivationSettingsStore? = null

        internal fun process(context: Context): VoiceActivationSettingsStore {
            return processInstance ?: synchronized(this) {
                processInstance ?: VoiceActivationSettingsStore(
                    createStorage(context.applicationContext),
                ).also { processInstance = it }
            }
        }

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
                voiceServiceState = VoiceServiceState.entries
                    .firstOrNull { it.name == storedState }
                    ?: VoiceServiceState.DISABLED,
                lastError = storage.getString(KEY_LAST_ERROR, null),
                enabledAtEpochMs = storage.getLong(KEY_ENABLED_AT_EPOCH_MS, 0L),
            )
        }
    }
}

class VoiceActivationController internal constructor(
    context: Context,
    private val settingsStore: VoiceActivationSettingsStore,
    private val modelInstaller: VoiceModelInstaller,
) {
    constructor(context: Context) : this(
        context = context,
        settingsStore = VoiceActivationSettingsStore.process(context),
        modelInstaller = VoiceModelInstaller.process(context),
    )

    private val appContext = context.applicationContext
    private val stateFlow = MutableStateFlow(computeState())
    private val settingsObservationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        settingsObservationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            settingsStore.observe().collect { settings ->
                stateFlow.value = computeState(settings)
            }
        }
        settingsObservationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            modelInstaller.observe().collect {
                stateFlow.value = computeState()
            }
        }
    }

    fun observe(): StateFlow<VoiceActivationUiState> = stateFlow.asStateFlow()

    fun refresh() {
        stateFlow.value = computeState()
    }

    fun close() {
        settingsObservationScope.cancel()
    }

    fun setEnabled(enabled: Boolean): VoiceActivationEnableResult {
        if (!enabled) {
            VoiceModelSetupWorker.cancel(appContext)
            settingsStore.setEnabled(false)
            OffasRuntime.stop(appContext)
            refresh()
            return VoiceActivationEnableResult.DISABLED
        }

        val currentState = computeState()
        if (currentState.betaContract.blockingIssue in setOf(
                VoiceBetaBlockingIssue.MODELS_MISSING,
                VoiceBetaBlockingIssue.WAKE_WORD_MODEL_MISSING,
            )
        ) {
            startModelSetupAndEnable()
            return VoiceActivationEnableResult.SETUP_STARTED
        }

        val result = enableVoiceActivation(
            settingsStore = settingsStore,
            betaContract = currentState.betaContract,
            startRuntime = { OffasRuntime.start(appContext) },
        )
        refresh()
        return result
    }

    fun silenceTimeoutSeconds(): Int = settingsStore.state().silenceTimeoutSeconds

    fun openBatteryOptimizationSettingsIntent(): Intent {
        return Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun requestAssistantRoleIntent(): Intent? {
        if (!isAssistantRoleAvailable()) {
            return null
        }
        val roleManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appContext.getSystemService(RoleManager::class.java)
        } else {
            null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            roleManager?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true
        ) {
            return roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
        }
        val voiceInputSettings = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (voiceInputSettings.resolveActivity(appContext.packageManager) != null) {
            voiceInputSettings
        } else {
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun openAppSettingsIntent(): Intent {
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", appContext.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun computeState(
        settings: VoiceActivationSettings = settingsStore.state(),
    ): VoiceActivationUiState {
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryOptimizationIgnored = powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) == true
        val notificationPermissionGranted = areVoiceNotificationsAvailable(appContext)
        val microphonePermissionGranted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        val assistantRoleSupported = isAssistantRoleAvailable()
        val assistantRoleHeld = VoiceInteractionService.isActiveService(
            appContext,
            ComponentName(appContext, PocketAgentVoiceInteractionService::class.java),
        )
        val modelStatus = VoiceModelCatalog.status(appContext)
        val modelSetup = modelInstaller.observe().value.let { setup ->
            if (setup.phase == VoiceModelSetupPhase.IDLE &&
                VoiceModelSetupEnableRequest.hasPendingRequest(appContext)
            ) {
                setup.copy(phase = VoiceModelSetupPhase.QUEUED)
            } else {
                setup
            }
        }
        val betaContract = evaluateVoiceBetaContract(
            notificationPermissionGranted = notificationPermissionGranted,
            microphonePermissionGranted = microphonePermissionGranted,
            assistantRoleSupported = assistantRoleSupported,
            assistantRoleHeld = assistantRoleHeld,
            batteryOptimizationIgnored = batteryOptimizationIgnored,
            modelsReady = modelStatus.ready,
            dedicatedWakeWordReady = modelStatus.dedicatedWakeWordReady,
        )
        return VoiceActivationUiState(
            settings = settings,
            notificationPermissionGranted = notificationPermissionGranted,
            microphonePermissionGranted = microphonePermissionGranted,
            assistantRoleSupported = assistantRoleSupported,
            assistantRoleHeld = assistantRoleHeld,
            batteryOptimizationIgnored = batteryOptimizationIgnored,
            oemGuide = OemBatteryGuide.fromManufacturer(Build.MANUFACTURER),
            modelsReady = modelStatus.ready,
            dedicatedWakeWordReady = modelStatus.dedicatedWakeWordReady,
            modelSetup = modelSetup,
            modelsRootPath = VoiceModelCatalog.root(appContext).absolutePath,
            missingModelPaths = modelStatus.missingPaths,
            missingWakeWordPaths = modelStatus.missingWakeWordPaths,
            betaContract = betaContract,
        )
    }

    private fun isAssistantRoleAvailable(): Boolean {
        val roleAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            appContext.getSystemService(RoleManager::class.java)
                ?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true
        val settingsAvailable = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
            .resolveActivity(appContext.packageManager) != null
        return roleAvailable || settingsAvailable
    }

    private fun startModelSetupAndEnable() {
        val setupToken = VoiceModelSetupEnableRequest.request(appContext)
        VoiceModelSetupWorker.enqueue(appContext, setupToken)
        refresh()
    }
}

internal fun areVoiceNotificationsAvailable(context: Context): Boolean {
    val appContext = context.applicationContext
    val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    if (!permissionGranted || !NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
        return false
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
    val manager = appContext.getSystemService(NotificationManager::class.java) ?: return false
    val channel = manager.getNotificationChannel(OffasListenerService.CHANNEL_VOICE_STATUS)
    return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
}

internal fun evaluateVoiceBetaContract(
    microphonePermissionGranted: Boolean,
    assistantRoleSupported: Boolean,
    assistantRoleHeld: Boolean,
    batteryOptimizationIgnored: Boolean,
    modelsReady: Boolean,
    dedicatedWakeWordReady: Boolean = true,
    notificationPermissionGranted: Boolean = true,
): VoiceBetaContract {
    val blockingIssue = when {
        !notificationPermissionGranted -> VoiceBetaBlockingIssue.NOTIFICATION_PERMISSION
        !microphonePermissionGranted -> VoiceBetaBlockingIssue.MICROPHONE_PERMISSION
        assistantRoleSupported && !assistantRoleHeld -> VoiceBetaBlockingIssue.ASSISTANT_NOT_SELECTED
        !modelsReady -> VoiceBetaBlockingIssue.MODELS_MISSING
        !dedicatedWakeWordReady -> VoiceBetaBlockingIssue.WAKE_WORD_MODEL_MISSING
        else -> null
    }
    return VoiceBetaContract(
        blockingIssue = blockingIssue,
        needsBatteryGuidance = !batteryOptimizationIgnored,
        needsAssistantRole = assistantRoleSupported && !assistantRoleHeld,
    )
}

internal fun enableVoiceActivation(
    settingsStore: VoiceActivationSettingsStore,
    betaContract: VoiceBetaContract,
    startRuntime: () -> Unit,
): VoiceActivationEnableResult {
    val blockingIssue = betaContract.blockingIssue
    if (blockingIssue != null) {
        settingsStore.disableWithError(blockingIssue.enableBlockedMessage())
        return blockingIssue.toEnableResult()
    }

    settingsStore.setEnabled(true)
    return runCatching {
        startRuntime()
        VoiceActivationEnableResult.ENABLED
    }.getOrElse { error ->
        settingsStore.disableWithError(voiceActivationStartFailureMessage(error))
        VoiceActivationEnableResult.START_FAILED
    }
}

internal fun voiceActivationStartFailureMessage(error: Throwable): String {
    val detail = error.message?.trim().orEmpty()
    return if (detail.isBlank()) {
        "Hands-free voice could not start. Check microphone access and the local voice setup, then retry."
    } else {
        "Hands-free voice could not start: $detail"
    }
}

private fun VoiceBetaBlockingIssue.toEnableResult(): VoiceActivationEnableResult {
    return when (this) {
        VoiceBetaBlockingIssue.NOTIFICATION_PERMISSION ->
            VoiceActivationEnableResult.BLOCKED_NOTIFICATION_PERMISSION
        VoiceBetaBlockingIssue.MICROPHONE_PERMISSION -> VoiceActivationEnableResult.BLOCKED_MICROPHONE_PERMISSION
        VoiceBetaBlockingIssue.ASSISTANT_NOT_SELECTED ->
            VoiceActivationEnableResult.BLOCKED_ASSISTANT_NOT_SELECTED
        VoiceBetaBlockingIssue.MODELS_MISSING -> VoiceActivationEnableResult.BLOCKED_MODELS_MISSING
        VoiceBetaBlockingIssue.WAKE_WORD_MODEL_MISSING ->
            VoiceActivationEnableResult.BLOCKED_WAKE_WORD_MODEL_MISSING
    }
}

private fun VoiceBetaBlockingIssue.enableBlockedMessage(): String {
    return when (this) {
        VoiceBetaBlockingIssue.NOTIFICATION_PERMISSION ->
            "Notification permission is required so Android can show when always-on listening uses the microphone."
        VoiceBetaBlockingIssue.MICROPHONE_PERMISSION ->
            "Microphone permission is required before hands-free voice can start."
        VoiceBetaBlockingIssue.ASSISTANT_NOT_SELECTED ->
            "Select PocketAgent as Android's digital assistant before turning on hands-free voice."
        VoiceBetaBlockingIssue.MODELS_MISSING ->
            "Hands-free voice needs its local voice files before listening can start."
        VoiceBetaBlockingIssue.WAKE_WORD_MODEL_MISSING ->
            "Always-on listening needs the dedicated Offas wake-word model. " +
                "Speech recognition cannot be used as a fallback."
    }
}

internal const val DEFAULT_WAKE_PHRASE = "Offas"
internal const val DEFAULT_SILENCE_TIMEOUT_SECONDS = 5
