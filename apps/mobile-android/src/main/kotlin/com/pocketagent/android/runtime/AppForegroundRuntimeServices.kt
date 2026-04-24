package com.pocketagent.android.runtime

import android.content.Context

internal interface AppForegroundRuntimeServices {
    val runtimeTuning: AndroidRuntimeTuningStore
    val runtimeGateway: ChatRuntimeService
    val provisioningGateway: ProvisioningGateway
    val eligibilitySignalsProvider: ModelEligibilitySignalsProvider
    val presetBackingStore: PresetBackingStore
}

internal class DefaultAppForegroundRuntimeServices(
    context: Context,
    private val runtimeAccess: AppRuntimeAccess,
) : AppForegroundRuntimeServices {
    private val appContext = context.applicationContext

    private val deviceGpuOffloadSupport by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidGpuOffloadSupport(appContext)
    }

    private val gpuOffloadQualifier by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidGpuOffloadQualifier(appContext)
    }

    override val runtimeTuning: AndroidRuntimeTuningStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runtimeAccess.runtimeTuning(appContext)
    }

    override val runtimeGateway: ChatRuntimeService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MvpRuntimeGateway(
            facade = runtimeAccess.runtimeFacade(appContext),
            deviceGpuOffloadSupport = deviceGpuOffloadSupport,
            gpuOffloadQualifier = gpuOffloadQualifier,
            runtimeTuning = runtimeTuning,
        )
    }

    override val provisioningGateway: ProvisioningGateway by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DefaultProvisioningGateway(appContext)
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
}
