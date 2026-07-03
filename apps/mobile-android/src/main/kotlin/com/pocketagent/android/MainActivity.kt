package com.pocketagent.android

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.pocketagent.android.runtime.AppOperationTrace
import com.pocketagent.android.runtime.huggingface.SharedPreferencesHuggingFaceRecentModelStore
import com.pocketagent.android.runtime.modelmanager.ModelDownloadTaskStateStore
import com.pocketagent.android.runtime.resolveAppForegroundRuntimeServices
import com.pocketagent.android.ui.ChatViewModel
import com.pocketagent.android.ui.ChatViewModelFactory
import com.pocketagent.android.ui.HuggingFaceAcquisitionUiState
import com.pocketagent.android.ui.ModelProvisioningViewModel
import com.pocketagent.android.ui.ModelProvisioningViewModelFactory
import com.pocketagent.android.ui.PocketAgentApp
import com.pocketagent.android.ui.PocketAgentTheme
import com.pocketagent.android.ui.ProvisioningBootstrapScreen
import com.pocketagent.android.ui.controllers.AndroidTelemetryDeviceStateProvider
import com.pocketagent.android.ui.state.ModalSurface
import com.pocketagent.android.data.chat.AndroidSessionPersistence
import com.pocketagent.android.voice.VoiceActivationController
import com.pocketagent.android.voice.OffasListenerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// TODO: Add material3-window-size-class dependency to build.gradle.kts:
//   implementation("androidx.compose.material3:material3-window-size-class:<version>")
// Then uncomment:
// import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass

class MainActivity : ComponentActivity() {
    @Volatile
    private var warmedSessionPersistence: AndroidSessionPersistence? = null
    @Volatile
    private var warmedVoiceController: VoiceActivationController? = null
    private val debugAutomationRequestState = mutableStateOf<DebugAutomationRequest?>(null)
    private val debugModelLibraryReadyTagState = mutableStateOf(false)
    private val debugModelLibraryStatusState = mutableStateOf<String?>(null)

    private val foregroundRuntimeServices by lazy(LazyThreadSafetyMode.NONE) {
        resolveAppForegroundRuntimeServices(applicationContext)
    }

    private val runtimeTuning by lazy(LazyThreadSafetyMode.NONE) {
        foregroundRuntimeServices.runtimeTuning
    }

    private val runtimeGateway by lazy(LazyThreadSafetyMode.NONE) {
        foregroundRuntimeServices.runtimeGateway
    }

    private val viewModel: ChatViewModel by viewModels {
        ChatViewModelFactory(
            runtimeFacade = runtimeGateway,
            sessionPersistence = checkNotNull(warmedSessionPersistence) {
                "Session persistence must be warmed before ChatViewModel creation"
            },
            presetBackingStore = foregroundRuntimeServices.presetBackingStore,
            deviceStateProvider = AndroidTelemetryDeviceStateProvider(applicationContext),
            runtimeTuning = runtimeTuning,
        )
    }

    private val provisioningViewModel: ModelProvisioningViewModel by viewModels {
        ModelProvisioningViewModelFactory(
            gateway = foregroundRuntimeServices.provisioningGateway,
            eligibilitySignalsProvider = foregroundRuntimeServices.eligibilitySignalsProvider,
            huggingFaceRecentModelStore = SharedPreferencesHuggingFaceRecentModelStore(applicationContext),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        debugAutomationRequestState.value = parseDebugAutomationRequest(intent)
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build(),
            )
        }
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // TODO: Once material3-window-size-class dependency is added, compute and pass down:
            // val windowSizeClass = calculateWindowSizeClass(this)
            // Pass windowSizeClass to PocketAgentApp for adaptive layouts.
            PocketAgentTheme {
                Surface {
                    var runtimeReady by remember { mutableStateOf(false) }
                    LaunchedEffect(foregroundRuntimeServices) {
                        withContext(Dispatchers.IO) {
                            AppOperationTrace.suspendSection(name = "startup.foreground_runtime_warmup") {
                                foregroundRuntimeServices.warmUp()
                                warmedSessionPersistence = AndroidSessionPersistence(applicationContext)
                                warmedVoiceController = VoiceActivationController(applicationContext)
                            }
                        }
                        runtimeReady = true
                    }
                    if (runtimeReady) {
                        LaunchedEffect(Unit) {
                            viewModel.refreshRuntimeReadiness()
                        }
                        val debugAutomationRequest = debugAutomationRequestState.value
                        LaunchedEffect(runtimeReady, debugAutomationRequest) {
                            handleDebugAutomationRequest(debugAutomationRequest)
                        }
                        PocketAgentApp(
                            viewModel = viewModel,
                            provisioningViewModel = provisioningViewModel,
                            voiceController = checkNotNull(warmedVoiceController) {
                                "Voice controller must be warmed before composition"
                            },
                            debugModelLibraryReadyTagEnabled = debugModelLibraryReadyTagState.value,
                            debugModelLibraryStatus = debugModelLibraryStatusState.value,
                        )
                    } else {
                        ProvisioningBootstrapScreen()
                    }
                }
            }
        }
        createNotificationChannels()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        debugAutomationRequestState.value = parseDebugAutomationRequest(intent)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            val downloadChannel = NotificationChannel(
                CHANNEL_MODEL_DOWNLOADS,
                "Model Downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Progress and status of model downloads"
            }
            val runtimeChannel = NotificationChannel(
                CHANNEL_RUNTIME_STATUS,
                "Runtime Status",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Model loading and runtime status updates"
            }
            val voiceChannel = NotificationChannel(
                OffasListenerService.CHANNEL_VOICE_STATUS,
                "Offas Voice",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Background voice activation and command status"
            }
            manager.createNotificationChannels(listOf(downloadChannel, runtimeChannel, voiceChannel))
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch(Dispatchers.IO) {
            runtimeGateway.onAppForeground()
            runtimeGateway.touchKeepAlive()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        lifecycleScope.launch(Dispatchers.IO) {
            val evicted = when {
                level >= TRIM_MEMORY_COMPLETE_LEVEL -> runtimeGateway.evictResidentModel("trim_complete")
                level >= TRIM_MEMORY_BACKGROUND_LEVEL -> runtimeGateway.evictResidentModel("trim_background")
                level >= TRIM_MEMORY_RUNNING_CRITICAL_LEVEL -> runtimeGateway.evictResidentModel("trim_critical")
                else -> {
                    when {
                        level >= TRIM_MEMORY_RUNNING_LOW_LEVEL -> runtimeGateway.shortenKeepAlive(15_000L)
                        level >= TRIM_MEMORY_RUNNING_MODERATE_LEVEL -> runtimeGateway.shortenKeepAlive(60_000L)
                        level >= TRIM_MEMORY_UI_HIDDEN_LEVEL -> runtimeGateway.shortenKeepAlive(120_000L)
                    }
                    false
                }
            }
            if (evicted) {
                recordAvailableMemoryBudget()
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        lifecycleScope.launch(Dispatchers.IO) {
            runtimeGateway.evictResidentModel(reason = "low_memory")
            recordAvailableMemoryBudget()
        }
    }

    override fun onStop() {
        lifecycleScope.launch(Dispatchers.IO) {
            runtimeGateway.onAppBackground()
            recordAvailableMemoryBudget()
        }
        super.onStop()
    }

    private fun recordAvailableMemoryBudget() {
        runCatching {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            val availMb = memInfo.availMem.toDouble() / (1024.0 * 1024.0)
            runtimeTuning.memoryBudgetTracker.recordAvailableMemoryAfterRelease(availMb)
        }
    }

    private suspend fun handleDebugAutomationRequest(request: DebugAutomationRequest?) {
        if (!BuildConfig.DEBUG || request == null) {
            return
        }
        Log.i(DEBUG_AUTOMATION_LOG_TAG, "handling request=$request")
        debugModelLibraryReadyTagState.value = false
        debugModelLibraryStatusState.value = "debug_pending"
        withContext(Dispatchers.IO) {
            if (request.clearDownloads) {
                ModelDownloadTaskStateStore.resetForTests()
                applicationContext.deleteDatabase(DOWNLOAD_TASK_DATABASE_NAME)
                applicationContext
                    .getSharedPreferences(DOWNLOAD_TASK_LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit()
                ModelDownloadTaskStateStore.resetForTests()
            }
            if (request.clearRecentHuggingFace) {
                SharedPreferencesHuggingFaceRecentModelStore(applicationContext).clear()
            }
        }
        viewModel.uiState
            .filter { it.bootstrapCompleted }
            .first()
        Log.i(DEBUG_AUTOMATION_LOG_TAG, "chat bootstrap complete")
        if (request.skipOnboarding) {
            viewModel.completeOnboarding()
            Log.i(DEBUG_AUTOMATION_LOG_TAG, "onboarding completed through ChatViewModel")
        }
        if (request.openSurface == DEBUG_OPEN_SURFACE_MODEL_LIBRARY) {
            provisioningViewModel.uiState
                .filter { it.snapshot != null }
                .first()
            viewModel.showSurface(ModalSurface.ModelLibrary)
            debugModelLibraryReadyTagState.value = true
            debugModelLibraryStatusState.value = "debug_model_library_open"
            Log.i(DEBUG_AUTOMATION_LOG_TAG, "model library requested")
        }
        val huggingFaceResolveUrl = request.huggingFaceResolveUrl?.trim().orEmpty()
        if (huggingFaceResolveUrl.isNotBlank()) {
            val targetModelId = request.huggingFaceTargetModelId?.trim()?.takeIf { it.isNotBlank() }
                ?: provisioningViewModel.uiState.value.huggingFaceTargets.firstOrNull()?.modelId
            if (targetModelId == null) {
                debugModelLibraryStatusState.value = "hf_no_target"
                Log.w(
                    DEBUG_AUTOMATION_LOG_TAG,
                    "skipping HF debug resolve because no target models are available",
                )
            } else {
                debugModelLibraryStatusState.value = "hf_resolving:$targetModelId"
                Log.i(
                    DEBUG_AUTOMATION_LOG_TAG,
                    "resolving HF debug candidate url=$huggingFaceResolveUrl target=$targetModelId",
                )
                provisioningViewModel.resolveHuggingFaceCandidate(
                    input = huggingFaceResolveUrl,
                    targetModelId = targetModelId,
                )
                val acquisitionState = provisioningViewModel.uiState.value.huggingFaceAcquisitionState
                debugModelLibraryStatusState.value = when (acquisitionState) {
                    is HuggingFaceAcquisitionUiState.Ready -> "hf_ready"
                    is HuggingFaceAcquisitionUiState.Blocked -> {
                        "hf_blocked:${acquisitionState.reason}"
                    }
                    HuggingFaceAcquisitionUiState.Idle -> "hf_idle_after_resolve"
                    HuggingFaceAcquisitionUiState.Resolving -> "hf_still_resolving"
                }
            }
        } else {
            debugModelLibraryStatusState.value = "hf_no_url"
        }
        debugAutomationRequestState.value = null
    }

    private fun parseDebugAutomationRequest(intent: Intent?): DebugAutomationRequest? {
        if (!BuildConfig.DEBUG || intent == null) {
            return null
        }
        val requestedSurface = intent.getStringExtra(EXTRA_DEBUG_OPEN_SURFACE)
        val hasDebugLaunchRequest = intent.action == ACTION_DEBUG_OPEN_MODEL_LIBRARY ||
            requestedSurface == DEBUG_OPEN_SURFACE_MODEL_LIBRARY
        if (!hasDebugLaunchRequest) {
            return null
        }
        val request = DebugAutomationRequest(
            skipOnboarding = if (intent.hasExtra(EXTRA_DEBUG_SKIP_ONBOARDING)) {
                intent.getBooleanExtra(EXTRA_DEBUG_SKIP_ONBOARDING, true)
            } else {
                true
            },
            openSurface = requestedSurface ?: DEBUG_OPEN_SURFACE_MODEL_LIBRARY,
            clearDownloads = intent.getBooleanExtra(EXTRA_DEBUG_CLEAR_DOWNLOADS, false),
            clearRecentHuggingFace = intent.getBooleanExtra(EXTRA_DEBUG_CLEAR_RECENT_HF, false),
            huggingFaceResolveUrl = intent.getStringExtra(EXTRA_DEBUG_HF_RESOLVE_URL),
            huggingFaceTargetModelId = intent.getStringExtra(EXTRA_DEBUG_HF_TARGET_MODEL_ID),
        )
        Log.i(DEBUG_AUTOMATION_LOG_TAG, "parsed request=$request")
        return request
    }

    companion object {
        const val CHANNEL_MODEL_DOWNLOADS = "model_downloads"
        const val CHANNEL_RUNTIME_STATUS = "runtime_status"
        const val ACTION_DEBUG_OPEN_MODEL_LIBRARY = "com.pocketagent.android.DEBUG_OPEN_MODEL_LIBRARY"
        const val EXTRA_DEBUG_SKIP_ONBOARDING = "pocketagent.debug.skip_onboarding"
        const val EXTRA_DEBUG_OPEN_SURFACE = "pocketagent.debug.open_surface"
        const val EXTRA_DEBUG_CLEAR_DOWNLOADS = "pocketagent.debug.clear_downloads"
        const val EXTRA_DEBUG_CLEAR_RECENT_HF = "pocketagent.debug.clear_recent_hf"
        const val EXTRA_DEBUG_HF_RESOLVE_URL = "pocketagent.debug.hf_resolve_url"
        const val EXTRA_DEBUG_HF_TARGET_MODEL_ID = "pocketagent.debug.hf_target_model_id"
        const val DEBUG_OPEN_SURFACE_MODEL_LIBRARY = "model_library"
        private const val DOWNLOAD_TASK_DATABASE_NAME = "pocketagent_model_downloads.db"
        private const val DOWNLOAD_TASK_LEGACY_PREFS_NAME = "pocketagent_model_downloads"
        private const val DEBUG_AUTOMATION_LOG_TAG = "PocketGptDebugAutomation"
        private const val TRIM_MEMORY_RUNNING_MODERATE_LEVEL = 5
        private const val TRIM_MEMORY_RUNNING_LOW_LEVEL = 10
        private const val TRIM_MEMORY_RUNNING_CRITICAL_LEVEL = 15
        private const val TRIM_MEMORY_UI_HIDDEN_LEVEL = 20
        private const val TRIM_MEMORY_BACKGROUND_LEVEL = 40
        private const val TRIM_MEMORY_COMPLETE_LEVEL = 80
    }
}

private data class DebugAutomationRequest(
    val skipOnboarding: Boolean,
    val openSurface: String?,
    val clearDownloads: Boolean,
    val clearRecentHuggingFace: Boolean,
    val huggingFaceResolveUrl: String?,
    val huggingFaceTargetModelId: String?,
)
