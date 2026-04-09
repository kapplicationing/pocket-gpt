package com.pocketagent.android

import android.content.Context
import android.net.Uri
import android.util.Log
import com.pocketagent.android.runtime.AndroidRuntimeTuningStore
import com.pocketagent.android.runtime.ModelAdmissionAction
import com.pocketagent.android.runtime.ModelAdmissionPolicy
import com.pocketagent.android.runtime.toAdmissionSubject
import com.pocketagent.android.runtime.ModelMemoryEstimator
import com.pocketagent.android.runtime.createDefaultAndroidInferenceModule
import com.pocketagent.android.runtime.RuntimeModelImportResult
import com.pocketagent.android.runtime.RuntimeModelLifecycleSnapshot
import com.pocketagent.android.runtime.RuntimeProvisioningSnapshot
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.DownloadPreferencesState
import com.pocketagent.android.runtime.modelmanager.DownloadRequestOptions
import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.modelmanager.StorageSummary
import com.pocketagent.core.model.ModelSpecProvider
import com.pocketagent.runtime.CompositeModelSpecProvider
import com.pocketagent.runtime.MvpRuntimeFacade
import com.pocketagent.runtime.RuntimeCompositionRoot
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult
import com.pocketagent.runtime.RuntimeWarmupSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

object AppRuntimeDependencies {
    private val graphManager = AppRuntimeGraphManager()
    private val lifecycleCoordinator = AppRuntimeLifecycleCoordinator(
        graphProvider = { context -> graphManager.getOrCreateRuntimeGraph(context) },
        currentGraphProvider = { graphManager.currentGraphOrNull() },
        installProductionRuntime = { context -> installProductionRuntime(context) },
        memoryEstimateRecorder = { estimate -> lastMemoryEstimate = estimate },
        modelAdmissionPolicyProvider = { context ->
            graphManager.getOrCreateRuntimeGraph(context).modelAdmissionPolicy
        },
    )
    private val warmupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val warmupOrchestrator = RuntimeWarmupOrchestrator(
        scope = warmupScope,
        logger = { message -> runCatching { Log.i("AppRuntimeDeps", message) } },
    )
    private val productionRuntimeFacadeFactory: () -> MvpRuntimeFacade = { graphManager.runtimeFacade() }

    @Volatile
    var lastMemoryEstimate: ModelMemoryEstimator.EstimationResult? = null
        internal set

    @Volatile
    var runtimeFacadeFactory: () -> MvpRuntimeFacade = productionRuntimeFacadeFactory

    fun resetRuntimeFacadeFactoryForTests() {
        runtimeFacadeFactory = productionRuntimeFacadeFactory
        warmupOrchestrator.cancelActiveWarmup()
        lifecycleCoordinator.resetForTests()
        graphManager.resetForTests()
    }

    internal fun cancelBackgroundWorkForTests() {
        warmupOrchestrator.cancelActiveWarmup()
    }

    fun installProductionRuntime(context: Context) {
        if (runtimeFacadeFactory !== productionRuntimeFacadeFactory) {
            return
        }
        val graph = graphManager.getOrCreateRuntimeGraph(context)
        val store = graph.provisioningStore
        val runtimeTuning = graph.runtimeTuning
        val newFacade = RuntimeCompositionRoot.createFacade(
            runtimeConfig = store.runtimeConfig(),
            conversationModule = graph.conversationModule,
            memoryModule = graph.memoryModule,
            inferenceModule = createDefaultAndroidInferenceModule(context.applicationContext),
            modelSpecProvider = CompositeModelSpecProvider(
                providers = listOf(graph.normalizedModelCatalogRegistry),
            ),
            memoryBudgetTracker = runtimeTuning.memoryBudgetTracker,
            recommendedGpuLayers = { modelId, config ->
                runtimeTuning
                    .applyRecommendedConfig(
                        modelIdHint = modelId,
                        baseConfig = config,
                        gpuQualifiedLayers = config.gpuLayers.coerceAtLeast(0),
                    )
                    .gpuLayers
                    .takeIf { it != config.gpuLayers }
            },
            mmProjPathResolver = { modelId ->
                store.resolveMmProjPath(modelId)
            },
        )
        graph.runtimeFacade.replace(newFacade)
        lifecycleCoordinator.attachLifecycleObserver(graph)
        lifecycleCoordinator.reconcileLifecycleState(graph)
        if (startupWarmupEnabled()) {
            scheduleWarmupIfSupported(newFacade)
        } else {
            Log.i("AppRuntimeDeps", "WARMUP|startup=disabled")
        }
    }

    private fun startupWarmupEnabled(): Boolean {
        val raw = System.getenv("POCKETGPT_WARMUP_ON_STARTUP")
            ?.trim()
            ?.lowercase()
            ?: return false
        return raw == "1" || raw == "true" || raw == "yes"
    }

    private fun scheduleWarmupIfSupported(facade: MvpRuntimeFacade) {
        warmupOrchestrator.scheduleWarmupIfSupported(facade as? RuntimeWarmupSupport)
    }

    fun currentProvisioningSnapshot(context: Context): RuntimeProvisioningSnapshot {
        return graphManager.getOrCreateRuntimeGraph(context).provisioningStore.snapshot()
    }

    fun runtimeTuning(context: Context): AndroidRuntimeTuningStore {
        return graphManager.runtimeTuning(context)
    }

    fun modelSpecProvider(context: Context): ModelSpecProvider {
        return CompositeModelSpecProvider(
            providers = listOf(graphManager.getOrCreateRuntimeGraph(context).normalizedModelCatalogRegistry),
        )
    }

    fun modelAdmissionPolicy(context: Context): ModelAdmissionPolicy {
        return graphManager.getOrCreateRuntimeGraph(context).modelAdmissionPolicy
    }

    suspend fun importModelFromUri(
        context: Context,
        modelId: String,
        sourceUri: Uri,
    ): RuntimeModelImportResult {
        val graph = graphManager.getOrCreateRuntimeGraph(context)
        graph.modelAdmissionPolicy.requireAllowed(
            action = ModelAdmissionAction.IMPORT,
            subject = com.pocketagent.android.runtime.ModelAdmissionSubject(modelId = modelId),
        )
        val store = graph.provisioningStore
        val result = store.importModel(modelId = modelId, sourceUri = sourceUri)
        installProductionRuntime(context)
        graph.modelDownloadManager.refresh()
        return result
    }

    suspend fun seedModelFromAbsolutePath(
        context: Context,
        modelId: String,
        absolutePath: String,
    ): RuntimeModelImportResult {
        val graph = graphManager.getOrCreateRuntimeGraph(context)
        graph.modelAdmissionPolicy.requireAllowed(
            action = ModelAdmissionAction.IMPORT,
            subject = com.pocketagent.android.runtime.ModelAdmissionSubject(modelId = modelId),
        )
        val store = graph.provisioningStore
        val result = store.seedModelFromAbsolutePath(modelId = modelId, absolutePath = absolutePath)
        installProductionRuntime(context)
        graph.modelDownloadManager.refresh()
        return result
    }

    fun listInstalledVersions(
        context: Context,
        modelId: String,
    ) = graphManager.getOrCreateRuntimeGraph(context).provisioningStore.listInstalledVersions(modelId)

    fun setActiveVersion(
        context: Context,
        modelId: String,
        version: String,
    ): Boolean {
        val graph = graphManager.getOrCreateRuntimeGraph(context)
        val installed = graph.provisioningStore.listInstalledVersions(modelId)
            .firstOrNull { descriptor -> descriptor.version == version }
            ?: return false
        val activationDecision = graph.modelAdmissionPolicy.evaluate(
            action = ModelAdmissionAction.ACTIVATE,
            subject = installed.toAdmissionSubject(),
        )
        if (!activationDecision.allowed) {
            return false
        }
        val changed = graph.provisioningStore.setActiveVersion(modelId, version)
        if (changed) {
            installProductionRuntime(context)
        }
        lifecycleCoordinator.reconcileLifecycleState(graph)
        return changed
    }

    fun clearActiveVersion(
        context: Context,
        modelId: String,
    ): Boolean {
        val graph = graphManager.getOrCreateRuntimeGraph(context)
        val changed = graph.provisioningStore.clearActiveVersion(modelId)
        if (changed) {
            installProductionRuntime(context)
        }
        lifecycleCoordinator.reconcileLifecycleState(graph)
        return changed
    }

    fun removeVersion(
        context: Context,
        modelId: String,
        version: String,
    ): Boolean {
        val graph = graphManager.getOrCreateRuntimeGraph(context)
        val loaded = graph.runtimeFacade.loadedModel()
        val guardedLoadedVersion = loadedVersionForRemovalGuard(
            loadedModel = loaded,
            installedVersions = graph.provisioningStore.listInstalledVersions(modelId),
        )
        if (loaded != null && loaded.modelId == modelId && guardedLoadedVersion == version) {
            return false
        }
        val removed = graph.provisioningStore.removeVersion(modelId, version)
        if (removed) {
            installProductionRuntime(context)
            graph.modelDownloadManager.refresh()
        }
        lifecycleCoordinator.reconcileLifecycleState(graph)
        return removed
    }

    fun storageSummary(context: Context): StorageSummary {
        return graphManager.getOrCreateRuntimeGraph(context).provisioningStore.storageSummary()
    }

    suspend fun loadModelDistributionManifest(context: Context): ModelDistributionManifest {
        return graphManager.getOrCreateRuntimeGraph(context).modelManifestProvider.loadManifest()
    }

    suspend fun enqueueDownload(
        context: Context,
        version: ModelDistributionVersion,
        options: DownloadRequestOptions = DownloadRequestOptions(),
    ): String {
        val graph = graphManager.getOrCreateRuntimeGraph(context)
        graph.modelAdmissionPolicy.requireAllowed(
            action = ModelAdmissionAction.DOWNLOAD,
            subject = version.toAdmissionSubject(),
        )
        return graph.modelDownloadManager.enqueueDownload(version, options)
    }

    fun pauseDownload(context: Context, taskId: String) {
        graphManager.getOrCreateRuntimeGraph(context).modelDownloadManager.pauseDownload(taskId)
    }

    fun resumeDownload(context: Context, taskId: String) {
        graphManager.getOrCreateRuntimeGraph(context).modelDownloadManager.resumeDownload(taskId)
    }

    fun retryDownload(context: Context, taskId: String) {
        graphManager.getOrCreateRuntimeGraph(context).modelDownloadManager.retryDownload(taskId)
    }

    fun cancelDownload(context: Context, taskId: String) {
        graphManager.getOrCreateRuntimeGraph(context).modelDownloadManager.cancelDownload(taskId)
    }

    fun syncDownloadsFromScheduler(context: Context) {
        graphManager.getOrCreateRuntimeGraph(context).modelDownloadManager.syncFromSchedulerState()
    }

    fun observeDownloads(context: Context): StateFlow<List<DownloadTaskState>> {
        return graphManager.getOrCreateRuntimeGraph(context).modelDownloadManager.observeDownloads()
    }

    fun observeDownloadPreferences(context: Context): StateFlow<DownloadPreferencesState> {
        return graphManager.getOrCreateRuntimeGraph(context).modelDownloadManager.observeDownloadPreferences()
    }

    fun currentDownloadPreferences(context: Context): DownloadPreferencesState {
        return graphManager.getOrCreateRuntimeGraph(context).modelDownloadManager.currentDownloadPreferences()
    }

    fun shouldWarnForMeteredLargeDownload(
        context: Context,
        version: ModelDistributionVersion,
    ): Boolean {
        return graphManager.getOrCreateRuntimeGraph(context).modelDownloadManager.shouldWarnForMeteredLargeDownload(version)
    }

    fun setDownloadWifiOnlyEnabled(context: Context, enabled: Boolean) {
        graphManager.getOrCreateRuntimeGraph(context).modelDownloadManager.setWifiOnlyEnabled(enabled)
    }

    fun acknowledgeLargeDownloadCellularWarning(context: Context) {
        graphManager.getOrCreateRuntimeGraph(context).modelDownloadManager.acknowledgeLargeDownloadCellularWarning()
    }

    fun observeModelLifecycle(context: Context): StateFlow<RuntimeModelLifecycleSnapshot> {
        return lifecycleCoordinator.observeModelLifecycle(context)
    }

    fun currentModelLifecycle(context: Context): RuntimeModelLifecycleSnapshot {
        return lifecycleCoordinator.currentModelLifecycle(context)
    }

    suspend fun loadInstalledModel(
        context: Context,
        modelId: String,
        version: String,
    ): RuntimeModelLifecycleCommandResult {
        return lifecycleCoordinator.loadInstalledModel(context, modelId, version)
    }

    suspend fun loadLastUsedModel(context: Context): RuntimeModelLifecycleCommandResult {
        return lifecycleCoordinator.loadLastUsedModel(context)
    }

    suspend fun offloadModel(context: Context, reason: String): RuntimeModelLifecycleCommandResult {
        return lifecycleCoordinator.offloadModel(context, reason)
    }
}

internal fun loadedVersionForRemovalGuard(
    loadedModel: com.pocketagent.runtime.RuntimeLoadedModel?,
    installedVersions: List<com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor>,
): String? {
    if (loadedModel == null) {
        return null
    }
    return loadedModel.modelVersion
        ?: installedVersions.firstOrNull { descriptor -> descriptor.isActive }?.version
}
