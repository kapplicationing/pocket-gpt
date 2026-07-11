package com.pocketagent.android

import android.content.Context
import android.net.Uri
import android.util.Log
import com.pocketagent.android.runtime.AndroidRuntimeTuningStore
import com.pocketagent.android.runtime.AppOperationTrace
import com.pocketagent.android.runtime.MainThreadGuard
import com.pocketagent.android.runtime.ModelAdmissionAction
import com.pocketagent.android.runtime.ModelAdmissionPolicy
import com.pocketagent.android.runtime.ProvisioningMutationResult
import com.pocketagent.android.runtime.RuntimeDomainError
import com.pocketagent.android.runtime.RuntimeErrorCodes
import com.pocketagent.android.runtime.toAdmissionSubject
import com.pocketagent.android.runtime.ModelMemoryEstimator
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
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult
import com.pocketagent.runtime.RuntimeWarmupSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

object AppRuntimeDependencies {
    private val runtimeInstallFingerprintLock = Any()
    private val runtimeInstallSingleFlight = RuntimeInstallSingleFlight()
    @Volatile
    private var lastRuntimeInstallFingerprint: String? = null
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
        runtimeInstallSingleFlight.runExclusive {
            runtimeFacadeFactory = productionRuntimeFacadeFactory
            warmupOrchestrator.cancelActiveWarmup()
            lifecycleCoordinator.resetForTests()
            graphManager.resetForTests()
            synchronized(runtimeInstallFingerprintLock) {
                lastRuntimeInstallFingerprint = null
            }
        }
    }

    internal fun cancelBackgroundWorkForTests() {
        warmupOrchestrator.cancelActiveWarmup()
    }

    fun installProductionRuntime(context: Context) {
        MainThreadGuard.assertNotMainThread("AppRuntimeDependencies.installProductionRuntime")
        val outcome = runtimeInstallSingleFlight.install(
            shouldInstall = { runtimeFacadeFactory === productionRuntimeFacadeFactory },
            readCandidate = {
                val graph = graphManager.getOrCreateRuntimeGraph(context)
                RuntimeInstallCandidate(
                    fingerprint = runtimeGraphInstallFingerprint(graph),
                    payload = graph,
                )
            },
            readInstalledFingerprint = {
                synchronized(runtimeInstallFingerprintLock) { lastRuntimeInstallFingerprint }
            },
            preflight = { graph -> graph.runtimeFacade.prepareForRuntimeInstall() },
            build = { graph -> AppRuntimeFacadeFactory.buildProductionRuntimeFacade(context, graph) },
            replace = { graph, newFacade ->
                // A real fingerprint change owns warmup cancellation. Same-fingerprint waiters
                // coalesce before this callback and therefore do not restart active warmup work.
                warmupOrchestrator.cancelActiveWarmup()
                graph.runtimeFacade.replaceFromBackground(newFacade)
            },
            finalizePublished = ::finalizePublishedRuntime,
            commitFingerprint = { fingerprint ->
                synchronized(runtimeInstallFingerprintLock) {
                    lastRuntimeInstallFingerprint = fingerprint
                }
            },
            onCoalesced = { graph -> lifecycleCoordinator.reconcileLifecycleState(graph) },
        )
        when (outcome) {
            is RuntimeInstallOutcome.Rejected -> Log.e(
                "AppRuntimeDeps",
                "RUNTIME_SWAP|phase=rejected|code=${outcome.replacement.code}|" +
                    "detail=${outcome.replacement.detail.orEmpty()}",
            )
            RuntimeInstallOutcome.PublishedDelegateStale -> Log.w(
                "AppRuntimeDeps",
                "RUNTIME_SWAP|phase=finalize_skipped|reason=delegate_not_published",
            )
            is RuntimeInstallOutcome.Deferred -> Log.w(
                "AppRuntimeDeps",
                "RUNTIME_SWAP|phase=deferred|reason=retained_cleanup|detail=${outcome.detail}",
            )
            RuntimeInstallOutcome.Coalesced,
            RuntimeInstallOutcome.Installed,
            RuntimeInstallOutcome.Skipped,
            -> Unit
        }
    }

    private fun finalizePublishedRuntime(
        graph: AppRuntimeGraph,
        newFacade: MvpRuntimeFacade,
    ): Boolean {
        return graph.runtimeFacade.runIfPublished(newFacade) {
            graph.runtimeGateway.invalidatePerformanceCaches()
            lifecycleCoordinator.attachLifecycleObserver(graph)
            lifecycleCoordinator.reconcileLifecycleState(graph)
            if (startupWarmupEnabled()) {
                // The async job must acquire the currently published delegate when it executes.
                scheduleWarmupIfSupported(graph.runtimeFacade)
            } else {
                Log.i("AppRuntimeDeps", "WARMUP|startup=disabled")
            }
        }
    }

    private fun startupWarmupEnabled(): Boolean {
        val raw = System.getenv("POCKETGPT_WARMUP_ON_STARTUP")
            ?.trim()
            ?.lowercase()
            ?: return false
        return raw == "1" || raw == "true" || raw == "yes"
    }

    private fun scheduleWarmupIfSupported(warmupSupport: RuntimeWarmupSupport?) {
        warmupOrchestrator.scheduleWarmupIfSupported(warmupSupport)
    }

    fun currentProvisioningSnapshot(context: Context): RuntimeProvisioningSnapshot {
        MainThreadGuard.assertNotMainThread("AppRuntimeDependencies.currentProvisioningSnapshot")
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
    ): List<com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor> {
        MainThreadGuard.assertNotMainThread("AppRuntimeDependencies.listInstalledVersions")
        return graphManager.getOrCreateRuntimeGraph(context).provisioningStore.listInstalledVersions(modelId)
    }

    fun setActiveVersion(
        context: Context,
        modelId: String,
        version: String,
    ): ProvisioningMutationResult {
        MainThreadGuard.assertNotMainThread("AppRuntimeDependencies.setActiveVersion")
        val graph = graphManager.getOrCreateRuntimeGraph(context)
        return AppOperationTrace.section(
            name = "provisioning.set_active_version",
            detail = { "model=$modelId|version=$version" },
        ) {
            val installed = graph.provisioningStore.listInstalledVersions(modelId)
                .firstOrNull { descriptor -> descriptor.version == version }
                ?: return@section ProvisioningMutationResult.NotFound(modelId = modelId, version = version)
            val activationDecision = graph.modelAdmissionPolicy.evaluate(
                action = ModelAdmissionAction.ACTIVATE,
                subject = installed.toAdmissionSubject(),
            )
            if (!activationDecision.allowed) {
                val domainError = activationDecision.asRuntimeDomainException().domainError
                return@section ProvisioningMutationResult.Blocked(domainError)
            }
            val changed = graph.provisioningStore.setActiveVersion(modelId, version)
            if (changed) {
                installProductionRuntime(context)
            }
            lifecycleCoordinator.reconcileLifecycleState(graph)
            if (changed) {
                ProvisioningMutationResult.Applied
            } else {
                ProvisioningMutationResult.NoChange(detail = "set_active_version_no_change")
            }
        }
    }

    fun clearActiveVersion(
        context: Context,
        modelId: String,
    ): ProvisioningMutationResult {
        MainThreadGuard.assertNotMainThread("AppRuntimeDependencies.clearActiveVersion")
        val graph = graphManager.getOrCreateRuntimeGraph(context)
        return AppOperationTrace.section(
            name = "provisioning.clear_active_version",
            detail = { "model=$modelId" },
        ) {
            val changed = graph.provisioningStore.clearActiveVersion(modelId)
            if (changed) {
                installProductionRuntime(context)
            }
            lifecycleCoordinator.reconcileLifecycleState(graph)
            if (changed) {
                ProvisioningMutationResult.Applied
            } else {
                ProvisioningMutationResult.NoChange(detail = "clear_active_version_no_change")
            }
        }
    }

    fun removeVersion(
        context: Context,
        modelId: String,
        version: String,
    ): ProvisioningMutationResult {
        MainThreadGuard.assertNotMainThread("AppRuntimeDependencies.removeVersion")
        val graph = graphManager.getOrCreateRuntimeGraph(context)
        return AppOperationTrace.section(
            name = "provisioning.remove_version",
            detail = { "model=$modelId|version=$version" },
        ) {
            val installedVersions = graph.provisioningStore.listInstalledVersions(modelId)
            if (installedVersions.none { descriptor -> descriptor.version == version }) {
                return@section ProvisioningMutationResult.NotFound(modelId = modelId, version = version)
            }
            if (installedVersions.any { descriptor -> descriptor.version == version && descriptor.isActive }) {
                return@section ProvisioningMutationResult.Blocked(
                    RuntimeDomainError(
                        code = RuntimeErrorCodes.PROVISIONING_REMOVE_ACTIVE_VERSION_BLOCKED,
                        userMessage = "Couldn't remove this model version while it is active.",
                        technicalDetail = "model=$modelId|version=$version|reason=active_selection",
                    ),
                )
            }
            val loaded = graph.runtimeFacade.loadedModel()
            val guardedLoadedVersion = loadedVersionForRemovalGuard(
                loadedModel = loaded,
                installedVersions = installedVersions,
            )
            if (loaded != null && loaded.modelId == modelId && guardedLoadedVersion == version) {
                return@section ProvisioningMutationResult.Blocked(
                    RuntimeDomainError(
                        code = RuntimeErrorCodes.PROVISIONING_REMOVE_ACTIVE_VERSION_BLOCKED,
                        userMessage = "Couldn't remove this model version while it is loaded.",
                        technicalDetail = "model=$modelId|version=$version|reason=loaded",
                    ),
                )
            }
            val removed = graph.provisioningStore.removeVersion(modelId, version)
            if (removed) {
                installProductionRuntime(context)
                graph.modelDownloadManager.refresh()
            }
            lifecycleCoordinator.reconcileLifecycleState(graph)
            if (removed) {
                ProvisioningMutationResult.Applied
            } else {
                ProvisioningMutationResult.NoChange(detail = "remove_version_no_change")
            }
        }
    }

    fun storageSummary(context: Context): StorageSummary {
        MainThreadGuard.assertNotMainThread("AppRuntimeDependencies.storageSummary")
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
        return graphManager.getOrCreateRuntimeGraph(context)
            .modelDownloadManager
            .shouldWarnForMeteredLargeDownload(version)
    }

    fun setDownloadWifiOnlyEnabled(context: Context, enabled: Boolean) {
        graphManager.getOrCreateRuntimeGraph(context).modelDownloadManager.setWifiOnlyEnabled(enabled)
    }

    fun acknowledgeLargeDownloadCellularWarning(context: Context) {
        graphManager.getOrCreateRuntimeGraph(context).modelDownloadManager.acknowledgeLargeDownloadCellularWarning()
    }

    fun observeModelLifecycle(
        context: Context,
        scope: CoroutineScope,
    ): StateFlow<RuntimeModelLifecycleSnapshot> {
        return lifecycleCoordinator.observeModelLifecycle(context, scope)
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

private fun runtimeGraphInstallFingerprint(graph: AppRuntimeGraph): String {
    val manifest = graph.modelManifestProvider.currentManifest()
    val manifestPart = manifest?.models.orEmpty()
        .sortedBy { it.modelId }
        .joinToString("|") { model ->
            model.modelId + ":" + model.versions.joinToString(",") { v ->
                "${v.version}:${v.expectedSha256}"
            }
        }
    val snap = graph.provisioningStore.snapshot()
    val provPart = snap.models.sortedBy { it.modelId }.joinToString("|") { state ->
        state.modelId + ":" + state.activeVersion.orEmpty() + ":" + state.installedVersions
            .sortedBy { it.version }
            .joinToString(",") { "${it.version}:${it.isActive}:${it.sha256}" }
    }
    return "$manifestPart###$provPart"
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
