package com.pocketagent.android.runtime

import android.content.Context
import com.pocketagent.android.AppRuntimeDependencies
import com.pocketagent.core.model.ModelSpecProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

internal interface AppForegroundRuntimeServices {
    val runtimeTuning: AndroidRuntimeTuningStore
    val runtimeGateway: ChatRuntimeService
    val provisioningGateway: ProvisioningGateway
    val eligibilitySignalsProvider: ModelEligibilitySignalsProvider
    val presetBackingStore: PresetBackingStore
    val modelSpecProvider: ModelSpecProvider

    suspend fun warmUp()
}

internal class DefaultAppForegroundRuntimeServices(
    context: Context,
) : AppForegroundRuntimeServices {
    private val appContext = context.applicationContext
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val deviceGpuOffloadSupport by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidGpuOffloadSupport(appContext)
    }

    private val gpuOffloadQualifier by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidGpuOffloadQualifier(appContext)
    }

    override val runtimeTuning: AndroidRuntimeTuningStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppRuntimeDependencies.runtimeTuning(appContext)
    }

    override val runtimeGateway: ChatRuntimeService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MvpRuntimeGateway(
            facade = AppRuntimeDependencies.runtimeFacadeFactory(),
            deviceGpuOffloadSupport = deviceGpuOffloadSupport,
            gpuOffloadQualifier = gpuOffloadQualifier,
            runtimeTuning = runtimeTuning,
        )
    }

    override val provisioningGateway: ProvisioningGateway by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DefaultProvisioningGateway(
            context = appContext,
            coroutineScope = serviceScope,
        )
    }

    override val eligibilitySignalsProvider: ModelEligibilitySignalsProvider by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidModelEligibilitySignalsProvider(
            runtimeCompatibilityTag = AndroidRuntimeProvisioningStore(appContext).expectedRuntimeCompatibilityTag(),
            deviceGpuOffloadSupport = deviceGpuOffloadSupport,
            gpuOffloadQualifier = gpuOffloadQualifier,
            runtimeSupportProvider = { runtimeGateway.supportsGpuOffload() },
            runtimeDiagnosticsProvider = { runtimeGateway.runtimeDiagnosticsSnapshot() },
        )
    }

    override val presetBackingStore: PresetBackingStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PresetModelMappingStore(appContext)
    }

    override val modelSpecProvider: ModelSpecProvider by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppRuntimeDependencies.modelSpecProvider(appContext)
    }

    override suspend fun warmUp() = withContext(Dispatchers.IO) {
        runtimeTuning
        deviceGpuOffloadSupport
        gpuOffloadQualifier
        runtimeGateway
        provisioningGateway
        eligibilitySignalsProvider
        presetBackingStore
        modelSpecProvider
        Unit
    }
}
