package com.pocketagent.android.runtime

import android.net.Uri
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.DownloadPreferencesState
import com.pocketagent.android.runtime.modelmanager.DownloadRequestOptions
import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.core.model.ModelSpecProvider
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface ProvisioningGateway {
    fun currentProvisioningAggregateState(): ProvisioningAggregateState {
        return ProvisioningAggregateState(
            snapshot = currentSnapshot(),
            downloads = observeDownloads().value,
            downloadPreferences = currentDownloadPreferences(),
            lifecycle = currentModelLifecycle(),
        )
    }

    fun observeProvisioningAggregateState(): StateFlow<ProvisioningAggregateState> {
        return MutableStateFlow(currentProvisioningAggregateState())
    }

    suspend fun seedProvisioningAggregateState(): ProvisioningAggregateState {
        val manifest = loadModelDistributionManifest()
        return currentProvisioningAggregateState().copy(
            manifest = manifest,
            manifestLoaded = true,
        )
    }

    fun currentSnapshot(): RuntimeProvisioningSnapshot
    fun observeDownloads(): StateFlow<List<DownloadTaskState>>
    fun observeDownloadPreferences(): StateFlow<DownloadPreferencesState>
    fun currentDownloadPreferences(): DownloadPreferencesState
    fun observeModelLifecycle(): StateFlow<RuntimeModelLifecycleSnapshot>
    fun currentModelLifecycle(): RuntimeModelLifecycleSnapshot
    suspend fun importModelFromUri(modelId: String, sourceUri: Uri): RuntimeModelImportResult
    suspend fun loadModelDistributionManifest(): ModelDistributionManifest
    fun listInstalledVersions(modelId: String): List<ModelVersionDescriptor>
    fun setActiveVersion(modelId: String, version: String): Boolean
    fun clearActiveVersion(modelId: String): Boolean
    fun removeVersion(modelId: String, version: String): Boolean
    suspend fun loadInstalledModel(modelId: String, version: String): RuntimeModelLifecycleCommandResult
    suspend fun loadLastUsedModel(): RuntimeModelLifecycleCommandResult
    suspend fun offloadModel(reason: String): RuntimeModelLifecycleCommandResult
    suspend fun enqueueDownload(version: ModelDistributionVersion, options: DownloadRequestOptions = DownloadRequestOptions()): String
    fun shouldWarnForMeteredLargeDownload(version: ModelDistributionVersion): Boolean
    fun setDownloadWifiOnlyEnabled(enabled: Boolean)
    fun acknowledgeLargeDownloadCellularWarning()
    fun pauseDownload(taskId: String)
    fun resumeDownload(taskId: String)
    fun retryDownload(taskId: String)
    fun cancelDownload(taskId: String)
    fun syncDownloadsFromScheduler()
}

class DefaultProvisioningGateway(
    private val dependencies: ProvisioningDependencyAccess,
    private val admissionPolicy: ModelAdmissionPolicy? = null,
    coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ProvisioningGateway {
    private val aggregateStore = DefaultProvisioningAggregateStore(
        dependencies = dependencies,
        coroutineScope = coroutineScope,
    )
    private val aggregateState = aggregateStore.observeState()
    private val downloadState = AggregateProjectionStateFlow(aggregateState) { state -> state.downloads }
    private val downloadPreferenceState = AggregateProjectionStateFlow(aggregateState) { state ->
        state.downloadPreferences
    }
    private val lifecycleState = AggregateProjectionStateFlow(aggregateState) { state -> state.lifecycle }

    constructor(appContext: android.content.Context) : this(
        dependencies = AppProvisioningDependencyAccess(
            context = appContext.applicationContext,
            runtimeAccess = AppRuntimeProvisioningBridge,
        ),
        admissionPolicy = AppRuntimeProvisioningBridge.modelAdmissionPolicy(appContext.applicationContext),
    )

    override fun currentProvisioningAggregateState(): ProvisioningAggregateState {
        return aggregateStore.currentState()
    }

    override fun observeProvisioningAggregateState(): StateFlow<ProvisioningAggregateState> {
        return aggregateState
    }

    override suspend fun seedProvisioningAggregateState(): ProvisioningAggregateState {
        return aggregateStore.seed()
    }

    override fun currentSnapshot(): RuntimeProvisioningSnapshot {
        return aggregateStore.refreshSnapshot().snapshot
    }

    override fun observeDownloads(): StateFlow<List<DownloadTaskState>> {
        return downloadState
    }

    override fun observeDownloadPreferences(): StateFlow<DownloadPreferencesState> {
        return downloadPreferenceState
    }

    override fun currentDownloadPreferences(): DownloadPreferencesState {
        return aggregateStore.refreshDownloadPreferences().downloadPreferences
    }

    override fun observeModelLifecycle(): StateFlow<RuntimeModelLifecycleSnapshot> {
        return lifecycleState
    }

    override fun currentModelLifecycle(): RuntimeModelLifecycleSnapshot {
        return aggregateStore.refreshLifecycle().lifecycle
    }

    override suspend fun importModelFromUri(modelId: String, sourceUri: Uri): RuntimeModelImportResult {
        admissionPolicy?.requireAllowed(
            action = ModelAdmissionAction.IMPORT,
            subject = ModelAdmissionSubject(modelId = modelId),
        )
        return dependencies.importModelFromUri(
            modelId = modelId,
            sourceUri = sourceUri,
        ).also {
            aggregateStore.refreshSnapshot()
        }
    }

    override suspend fun loadModelDistributionManifest(): ModelDistributionManifest {
        return seedProvisioningAggregateState().manifest
    }

    override fun listInstalledVersions(modelId: String): List<ModelVersionDescriptor> {
        return dependencies.listInstalledVersions(modelId = modelId)
    }

    override fun setActiveVersion(modelId: String, version: String): Boolean {
        if (admissionPolicy != null) {
            val installed = dependencies.listInstalledVersions(modelId = modelId)
                .firstOrNull { descriptor -> descriptor.version == version }
                ?: return false
            val decision = admissionPolicy.evaluate(
                action = ModelAdmissionAction.ACTIVATE,
                subject = installed.toAdmissionSubject(),
            )
            if (!decision.allowed) {
                return false
            }
        }
        return dependencies.setActiveVersion(modelId = modelId, version = version).also { changed ->
            if (changed) {
                aggregateStore.refreshSnapshot()
            }
        }
    }

    override fun clearActiveVersion(modelId: String): Boolean {
        return dependencies.clearActiveVersion(modelId = modelId).also { changed ->
            if (changed) {
                aggregateStore.refreshSnapshot()
            }
        }
    }

    override fun removeVersion(modelId: String, version: String): Boolean {
        return dependencies.removeVersion(modelId = modelId, version = version).also { removed ->
            if (removed) {
                aggregateStore.refreshSnapshot()
            }
        }
    }

    override suspend fun loadInstalledModel(modelId: String, version: String): RuntimeModelLifecycleCommandResult {
        val installed = dependencies.listInstalledVersions(modelId = modelId)
            .firstOrNull { descriptor -> descriptor.version == version }
        val decision = installed?.let { descriptor ->
            admissionPolicy?.evaluate(
                action = ModelAdmissionAction.LOAD,
                subject = descriptor.toAdmissionSubject(),
            )
        }
        if (decision != null && !decision.allowed) {
            return decision.asLifecycleRejectedResult()
        }
        return dependencies.loadInstalledModel(modelId = modelId, version = version).also {
            aggregateStore.refreshLifecycle()
        }
    }

    override suspend fun loadLastUsedModel(): RuntimeModelLifecycleCommandResult {
        return dependencies.loadLastUsedModel().also {
            aggregateStore.refreshLifecycle()
        }
    }

    override suspend fun offloadModel(reason: String): RuntimeModelLifecycleCommandResult {
        return dependencies.offloadModel(reason = reason).also {
            aggregateStore.refreshLifecycle()
        }
    }

    override suspend fun enqueueDownload(version: ModelDistributionVersion, options: DownloadRequestOptions): String {
        admissionPolicy?.requireAllowed(
            action = ModelAdmissionAction.DOWNLOAD,
            subject = version.toAdmissionSubject(),
        )
        return dependencies.enqueueDownload(version = version, options = options)
    }

    override fun shouldWarnForMeteredLargeDownload(version: ModelDistributionVersion): Boolean {
        return dependencies.shouldWarnForMeteredLargeDownload(version)
    }

    override fun setDownloadWifiOnlyEnabled(enabled: Boolean) {
        dependencies.setDownloadWifiOnlyEnabled(enabled)
        aggregateStore.refreshDownloadPreferences()
    }

    override fun acknowledgeLargeDownloadCellularWarning() {
        dependencies.acknowledgeLargeDownloadCellularWarning()
        aggregateStore.refreshDownloadPreferences()
    }

    override fun pauseDownload(taskId: String) {
        dependencies.pauseDownload(taskId)
    }

    override fun resumeDownload(taskId: String) {
        dependencies.resumeDownload(taskId)
    }

    override fun retryDownload(taskId: String) {
        dependencies.retryDownload(taskId)
    }

    override fun cancelDownload(taskId: String) {
        dependencies.cancelDownload(taskId)
    }

    override fun syncDownloadsFromScheduler() {
        dependencies.syncDownloadsFromScheduler()
    }
}

interface ProvisioningDependencyAccess {
    fun currentProvisioningSnapshot(): RuntimeProvisioningSnapshot
    fun observeDownloads(): StateFlow<List<DownloadTaskState>>
    fun observeDownloadPreferences(): StateFlow<DownloadPreferencesState>
    fun currentDownloadPreferences(): DownloadPreferencesState
    fun observeModelLifecycle(): StateFlow<RuntimeModelLifecycleSnapshot>
    fun currentModelLifecycle(): RuntimeModelLifecycleSnapshot
    suspend fun importModelFromUri(
        modelId: String,
        sourceUri: Uri,
    ): RuntimeModelImportResult
    suspend fun loadModelDistributionManifest(): ModelDistributionManifest
    fun listInstalledVersions(
        modelId: String,
    ): List<ModelVersionDescriptor>
    fun setActiveVersion(modelId: String, version: String): Boolean
    fun clearActiveVersion(modelId: String): Boolean
    fun removeVersion(modelId: String, version: String): Boolean
    suspend fun loadInstalledModel(modelId: String, version: String): RuntimeModelLifecycleCommandResult
    suspend fun loadLastUsedModel(): RuntimeModelLifecycleCommandResult
    suspend fun offloadModel(reason: String): RuntimeModelLifecycleCommandResult
    suspend fun enqueueDownload(version: ModelDistributionVersion, options: DownloadRequestOptions = DownloadRequestOptions()): String
    fun shouldWarnForMeteredLargeDownload(version: ModelDistributionVersion): Boolean
    fun setDownloadWifiOnlyEnabled(enabled: Boolean)
    fun acknowledgeLargeDownloadCellularWarning()
    fun pauseDownload(taskId: String)
    fun resumeDownload(taskId: String)
    fun retryDownload(taskId: String)
    fun cancelDownload(taskId: String)
    fun syncDownloadsFromScheduler()
}

internal class AppProvisioningDependencyAccess(
    private val context: android.content.Context,
    private val runtimeAccess: AppRuntimeProvisioningAccess = AppRuntimeProvisioningBridge,
) : ProvisioningDependencyAccess {
    override fun currentProvisioningSnapshot(): RuntimeProvisioningSnapshot {
        return runtimeAccess.currentProvisioningSnapshot(context)
    }

    override fun observeDownloads(): StateFlow<List<DownloadTaskState>> {
        return runtimeAccess.observeDownloads(context)
    }

    override fun observeDownloadPreferences(): StateFlow<DownloadPreferencesState> {
        return runtimeAccess.observeDownloadPreferences(context)
    }

    override fun currentDownloadPreferences(): DownloadPreferencesState {
        return runtimeAccess.currentDownloadPreferences(context)
    }

    override fun observeModelLifecycle(): StateFlow<RuntimeModelLifecycleSnapshot> {
        return runtimeAccess.observeModelLifecycle(context)
    }

    override fun currentModelLifecycle(): RuntimeModelLifecycleSnapshot {
        return runtimeAccess.currentModelLifecycle(context)
    }

    override suspend fun importModelFromUri(
        modelId: String,
        sourceUri: Uri,
    ): RuntimeModelImportResult {
        return runtimeAccess.importModelFromUri(context = context, modelId = modelId, sourceUri = sourceUri)
    }

    override suspend fun loadModelDistributionManifest(): ModelDistributionManifest {
        return runtimeAccess.loadModelDistributionManifest(context)
    }

    override fun listInstalledVersions(
        modelId: String,
    ): List<ModelVersionDescriptor> {
        return runtimeAccess.listInstalledVersions(context = context, modelId = modelId)
    }

    override fun setActiveVersion(modelId: String, version: String): Boolean {
        return runtimeAccess.setActiveVersion(context = context, modelId = modelId, version = version)
    }

    override fun clearActiveVersion(modelId: String): Boolean {
        return runtimeAccess.clearActiveVersion(context = context, modelId = modelId)
    }

    override fun removeVersion(modelId: String, version: String): Boolean {
        return runtimeAccess.removeVersion(context = context, modelId = modelId, version = version)
    }

    override suspend fun loadInstalledModel(modelId: String, version: String): RuntimeModelLifecycleCommandResult {
        return runtimeAccess.loadInstalledModel(
            context = context,
            modelId = modelId,
            version = version,
        )
    }

    override suspend fun loadLastUsedModel(): RuntimeModelLifecycleCommandResult {
        return runtimeAccess.loadLastUsedModel(context = context)
    }

    override suspend fun offloadModel(reason: String): RuntimeModelLifecycleCommandResult {
        return runtimeAccess.offloadModel(context = context, reason = reason)
    }

    override suspend fun enqueueDownload(version: ModelDistributionVersion, options: DownloadRequestOptions): String {
        return runtimeAccess.enqueueDownload(context = context, version = version, options = options)
    }

    override fun shouldWarnForMeteredLargeDownload(version: ModelDistributionVersion): Boolean {
        return runtimeAccess.shouldWarnForMeteredLargeDownload(context = context, version = version)
    }

    override fun setDownloadWifiOnlyEnabled(enabled: Boolean) {
        runtimeAccess.setDownloadWifiOnlyEnabled(context, enabled)
    }

    override fun acknowledgeLargeDownloadCellularWarning() {
        runtimeAccess.acknowledgeLargeDownloadCellularWarning(context)
    }

    override fun pauseDownload(taskId: String) {
        runtimeAccess.pauseDownload(context, taskId)
    }

    override fun resumeDownload(taskId: String) {
        runtimeAccess.resumeDownload(context, taskId)
    }

    override fun retryDownload(taskId: String) {
        runtimeAccess.retryDownload(context, taskId)
    }

    override fun cancelDownload(taskId: String) {
        runtimeAccess.cancelDownload(context, taskId)
    }

    override fun syncDownloadsFromScheduler() {
        runtimeAccess.syncDownloadsFromScheduler(context)
    }
}

fun modelSpecProviderForContext(context: android.content.Context): ModelSpecProvider {
    return AppRuntimeProvisioningBridge.modelSpecProvider(context.applicationContext)
}
