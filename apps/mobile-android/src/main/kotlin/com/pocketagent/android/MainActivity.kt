package com.pocketagent.android

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
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
import com.pocketagent.android.runtime.resolveAppForegroundRuntimeServices
import com.pocketagent.android.ui.ChatViewModel
import com.pocketagent.android.ui.ChatViewModelFactory
import com.pocketagent.android.ui.ModelProvisioningViewModel
import com.pocketagent.android.ui.ModelProvisioningViewModelFactory
import com.pocketagent.android.ui.PocketAgentApp
import com.pocketagent.android.ui.PocketAgentTheme
import com.pocketagent.android.ui.ProvisioningBootstrapScreen
import com.pocketagent.android.ui.controllers.AndroidTelemetryDeviceStateProvider
import com.pocketagent.android.data.chat.AndroidSessionPersistence
import com.pocketagent.android.voice.VoiceActivationController
import com.pocketagent.android.voice.OffasListenerService
import kotlinx.coroutines.Dispatchers
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
                        PocketAgentApp(
                            viewModel = viewModel,
                            provisioningViewModel = provisioningViewModel,
                            voiceController = checkNotNull(warmedVoiceController) {
                                "Voice controller must be warmed before composition"
                            },
                        )
                    } else {
                        ProvisioningBootstrapScreen()
                    }
                }
            }
        }
        createNotificationChannels()
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

    companion object {
        const val CHANNEL_MODEL_DOWNLOADS = "model_downloads"
        const val CHANNEL_RUNTIME_STATUS = "runtime_status"
        private const val TRIM_MEMORY_RUNNING_MODERATE_LEVEL = 5
        private const val TRIM_MEMORY_RUNNING_LOW_LEVEL = 10
        private const val TRIM_MEMORY_RUNNING_CRITICAL_LEVEL = 15
        private const val TRIM_MEMORY_UI_HIDDEN_LEVEL = 20
        private const val TRIM_MEMORY_BACKGROUND_LEVEL = 40
        private const val TRIM_MEMORY_COMPLETE_LEVEL = 80
    }
}
