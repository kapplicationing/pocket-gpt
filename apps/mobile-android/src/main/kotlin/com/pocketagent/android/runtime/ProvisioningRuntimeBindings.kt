package com.pocketagent.android.runtime

import android.content.Context
import android.net.Uri
import com.pocketagent.android.AppRuntimeDependencies
import com.pocketagent.android.runtime.modelmanager.DownloadPreferencesState
import com.pocketagent.android.runtime.modelmanager.DownloadRequestOptions
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult
import kotlinx.coroutines.flow.StateFlow

internal class ProvisioningRuntimeBindings(
    val currentProvisioningSnapshot: () -> RuntimeProvisioningSnapshot,
    val observeDownloads: () -> StateFlow<List<DownloadTaskState>>,
    val observeDownloadPreferences: () -> StateFlow<DownloadPreferencesState>,
    val currentDownloadPreferences: () -> DownloadPreferencesState,
    val observeModelLifecycle: () -> StateFlow<RuntimeModelLifecycleSnapshot>,
    val currentModelLifecycle: () -> RuntimeModelLifecycleSnapshot,
    val importModelFromUri: suspend (modelId: String, sourceUri: Uri) -> RuntimeModelImportResult,
    val loadModelDistributionManifest: suspend () -> ModelDistributionManifest,
    val listInstalledVersions: (modelId: String) -> List<ModelVersionDescriptor>,
    val setActiveVersion: (modelId: String, version: String) -> ProvisioningMutationResult,
    val clearActiveVersion: (modelId: String) -> ProvisioningMutationResult,
    val removeVersion: (modelId: String, version: String) -> ProvisioningMutationResult,
    val loadInstalledModel: suspend (modelId: String, version: String) -> RuntimeModelLifecycleCommandResult,
    val loadLastUsedModel: suspend () -> RuntimeModelLifecycleCommandResult,
    val offloadModel: suspend (reason: String) -> RuntimeModelLifecycleCommandResult,
    val enqueueDownload: suspend (version: ModelDistributionVersion, options: DownloadRequestOptions) -> String,
    val shouldWarnForMeteredLargeDownload: (version: ModelDistributionVersion) -> Boolean,
    val setDownloadWifiOnlyEnabled: (enabled: Boolean) -> Unit,
    val acknowledgeLargeDownloadCellularWarning: () -> Unit,
    val pauseDownload: (taskId: String) -> Unit,
    val resumeDownload: (taskId: String) -> Unit,
    val retryDownload: (taskId: String) -> Unit,
    val cancelDownload: (taskId: String) -> Unit,
    val syncDownloadsFromScheduler: () -> Unit,
)

internal fun appRuntimeProvisioningBindings(context: Context): ProvisioningRuntimeBindings {
    val appContext = context.applicationContext
    return ProvisioningRuntimeBindings(
        currentProvisioningSnapshot = {
            AppRuntimeDependencies.currentProvisioningSnapshot(appContext)
        },
        observeDownloads = {
            AppRuntimeDependencies.observeDownloads(appContext)
        },
        observeDownloadPreferences = {
            AppRuntimeDependencies.observeDownloadPreferences(appContext)
        },
        currentDownloadPreferences = {
            AppRuntimeDependencies.currentDownloadPreferences(appContext)
        },
        observeModelLifecycle = {
            AppRuntimeDependencies.observeModelLifecycle(appContext)
        },
        currentModelLifecycle = {
            AppRuntimeDependencies.currentModelLifecycle(appContext)
        },
        importModelFromUri = { modelId, sourceUri ->
            AppRuntimeDependencies.importModelFromUri(
                context = appContext,
                modelId = modelId,
                sourceUri = sourceUri,
            )
        },
        loadModelDistributionManifest = {
            AppRuntimeDependencies.loadModelDistributionManifest(appContext)
        },
        listInstalledVersions = { modelId ->
            AppRuntimeDependencies.listInstalledVersions(
                context = appContext,
                modelId = modelId,
            )
        },
        setActiveVersion = { modelId, version ->
            AppRuntimeDependencies.setActiveVersion(
                context = appContext,
                modelId = modelId,
                version = version,
            )
        },
        clearActiveVersion = { modelId ->
            AppRuntimeDependencies.clearActiveVersion(
                context = appContext,
                modelId = modelId,
            )
        },
        removeVersion = { modelId, version ->
            AppRuntimeDependencies.removeVersion(
                context = appContext,
                modelId = modelId,
                version = version,
            )
        },
        loadInstalledModel = { modelId, version ->
            AppRuntimeDependencies.loadInstalledModel(
                context = appContext,
                modelId = modelId,
                version = version,
            )
        },
        loadLastUsedModel = {
            AppRuntimeDependencies.loadLastUsedModel(appContext)
        },
        offloadModel = { reason ->
            AppRuntimeDependencies.offloadModel(
                context = appContext,
                reason = reason,
            )
        },
        enqueueDownload = { version, options ->
            AppRuntimeDependencies.enqueueDownload(
                context = appContext,
                version = version,
                options = options,
            )
        },
        shouldWarnForMeteredLargeDownload = { version ->
            AppRuntimeDependencies.shouldWarnForMeteredLargeDownload(
                context = appContext,
                version = version,
            )
        },
        setDownloadWifiOnlyEnabled = { enabled ->
            AppRuntimeDependencies.setDownloadWifiOnlyEnabled(appContext, enabled)
        },
        acknowledgeLargeDownloadCellularWarning = {
            AppRuntimeDependencies.acknowledgeLargeDownloadCellularWarning(appContext)
        },
        pauseDownload = { taskId ->
            AppRuntimeDependencies.pauseDownload(appContext, taskId)
        },
        resumeDownload = { taskId ->
            AppRuntimeDependencies.resumeDownload(appContext, taskId)
        },
        retryDownload = { taskId ->
            AppRuntimeDependencies.retryDownload(appContext, taskId)
        },
        cancelDownload = { taskId ->
            AppRuntimeDependencies.cancelDownload(appContext, taskId)
        },
        syncDownloadsFromScheduler = {
            AppRuntimeDependencies.syncDownloadsFromScheduler(appContext)
        },
    )
}
