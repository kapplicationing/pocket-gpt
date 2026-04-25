package com.pocketagent.android.runtime

import android.content.Context
import android.net.Uri
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.DownloadPreferencesState
import com.pocketagent.android.runtime.modelmanager.DownloadRequestOptions
import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

internal interface ProvisioningGateway {
    fun observeProvisioningAggregateState(): StateFlow<ProvisioningAggregateState>
    suspend fun seedProvisioningAggregateState(): ProvisioningAggregateState
    suspend fun importModelFromUri(modelId: String, sourceUri: Uri): RuntimeModelImportResult
    fun listInstalledVersions(modelId: String): List<ModelVersionDescriptor>
    fun setActiveVersion(modelId: String, version: String): ProvisioningMutationResult
    fun clearActiveVersion(modelId: String): ProvisioningMutationResult
    fun removeVersion(modelId: String, version: String): ProvisioningMutationResult
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

internal class DefaultProvisioningGateway(
    context: Context,
    coroutineScope: CoroutineScope,
    private val runtimeBindings: ProvisioningRuntimeBindings = appRuntimeProvisioningBindings(context),
) : ProvisioningGateway {
    private val aggregateStore = DefaultProvisioningAggregateStore(
        context = context,
        coroutineScope = coroutineScope,
        runtimeBindings = runtimeBindings,
    )
    private val aggregateState = aggregateStore.observeState()

    override fun observeProvisioningAggregateState(): StateFlow<ProvisioningAggregateState> {
        return aggregateState
    }

    override suspend fun seedProvisioningAggregateState(): ProvisioningAggregateState {
        return aggregateStore.seed()
    }

    override suspend fun importModelFromUri(modelId: String, sourceUri: Uri): RuntimeModelImportResult {
        return runtimeBindings.importModelFromUri(modelId, sourceUri).also {
            aggregateStore.refreshSnapshot()
        }
    }

    override fun listInstalledVersions(modelId: String): List<ModelVersionDescriptor> {
        return runtimeBindings.listInstalledVersions(modelId)
    }

    override fun setActiveVersion(modelId: String, version: String): ProvisioningMutationResult {
        return runtimeBindings.setActiveVersion(modelId, version).also { result ->
            if (result.changed) {
                aggregateStore.refreshSnapshot()
            }
        }
    }

    override fun clearActiveVersion(modelId: String): ProvisioningMutationResult {
        return runtimeBindings.clearActiveVersion(modelId).also { result ->
            if (result.changed) {
                aggregateStore.refreshSnapshot()
            }
        }
    }

    override fun removeVersion(modelId: String, version: String): ProvisioningMutationResult {
        return runtimeBindings.removeVersion(modelId, version).also { result ->
            if (result.changed) {
                aggregateStore.refreshSnapshot()
            }
        }
    }

    override suspend fun loadInstalledModel(modelId: String, version: String): RuntimeModelLifecycleCommandResult {
        return runtimeBindings.loadInstalledModel(modelId, version).also {
            aggregateStore.refreshLifecycle()
        }
    }

    override suspend fun loadLastUsedModel(): RuntimeModelLifecycleCommandResult {
        return runtimeBindings.loadLastUsedModel().also {
            aggregateStore.refreshLifecycle()
        }
    }

    override suspend fun offloadModel(reason: String): RuntimeModelLifecycleCommandResult {
        return runtimeBindings.offloadModel(reason).also {
            aggregateStore.refreshLifecycle()
        }
    }

    override suspend fun enqueueDownload(version: ModelDistributionVersion, options: DownloadRequestOptions): String {
        return runtimeBindings.enqueueDownload(version, options)
    }

    override fun shouldWarnForMeteredLargeDownload(version: ModelDistributionVersion): Boolean {
        return runtimeBindings.shouldWarnForMeteredLargeDownload(version)
    }

    override fun setDownloadWifiOnlyEnabled(enabled: Boolean) {
        runtimeBindings.setDownloadWifiOnlyEnabled(enabled)
        aggregateStore.refreshDownloadPreferences()
    }

    override fun acknowledgeLargeDownloadCellularWarning() {
        runtimeBindings.acknowledgeLargeDownloadCellularWarning()
        aggregateStore.refreshDownloadPreferences()
    }

    override fun pauseDownload(taskId: String) {
        runtimeBindings.pauseDownload(taskId)
    }

    override fun resumeDownload(taskId: String) {
        runtimeBindings.resumeDownload(taskId)
    }

    override fun retryDownload(taskId: String) {
        runtimeBindings.retryDownload(taskId)
    }

    override fun cancelDownload(taskId: String) {
        runtimeBindings.cancelDownload(taskId)
    }

    override fun syncDownloadsFromScheduler() {
        runtimeBindings.syncDownloadsFromScheduler()
        aggregateStore.refreshSnapshot()
    }
}
