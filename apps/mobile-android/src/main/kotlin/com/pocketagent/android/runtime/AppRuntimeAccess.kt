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
import com.pocketagent.core.model.ModelSpecProvider
import com.pocketagent.runtime.MvpRuntimeFacade
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult
import kotlinx.coroutines.flow.StateFlow

internal interface AppRuntimeAccess {
    fun installProductionRuntime(context: Context)
    fun runtimeFacade(context: Context): MvpRuntimeFacade
    fun runtimeTuning(context: Context): AndroidRuntimeTuningStore
    fun modelAdmissionPolicy(context: Context): ModelAdmissionPolicy
    fun currentProvisioningSnapshot(context: Context): RuntimeProvisioningSnapshot
    fun observeDownloads(context: Context): StateFlow<List<DownloadTaskState>>
    fun observeDownloadPreferences(context: Context): StateFlow<DownloadPreferencesState>
    fun currentDownloadPreferences(context: Context): DownloadPreferencesState
    fun observeModelLifecycle(context: Context): StateFlow<RuntimeModelLifecycleSnapshot>
    fun currentModelLifecycle(context: Context): RuntimeModelLifecycleSnapshot
    suspend fun importModelFromUri(
        context: Context,
        modelId: String,
        sourceUri: Uri,
    ): RuntimeModelImportResult
    suspend fun loadModelDistributionManifest(context: Context): ModelDistributionManifest
    fun listInstalledVersions(context: Context, modelId: String): List<ModelVersionDescriptor>
    fun setActiveVersion(context: Context, modelId: String, version: String): Boolean
    fun clearActiveVersion(context: Context, modelId: String): Boolean
    fun removeVersion(context: Context, modelId: String, version: String): Boolean
    suspend fun loadInstalledModel(
        context: Context,
        modelId: String,
        version: String,
    ): RuntimeModelLifecycleCommandResult
    suspend fun loadLastUsedModel(context: Context): RuntimeModelLifecycleCommandResult
    suspend fun offloadModel(context: Context, reason: String): RuntimeModelLifecycleCommandResult
    suspend fun enqueueDownload(
        context: Context,
        version: ModelDistributionVersion,
        options: DownloadRequestOptions = DownloadRequestOptions(),
    ): String
    fun shouldWarnForMeteredLargeDownload(context: Context, version: ModelDistributionVersion): Boolean
    fun setDownloadWifiOnlyEnabled(context: Context, enabled: Boolean)
    fun acknowledgeLargeDownloadCellularWarning(context: Context)
    fun pauseDownload(context: Context, taskId: String)
    fun resumeDownload(context: Context, taskId: String)
    fun retryDownload(context: Context, taskId: String)
    fun cancelDownload(context: Context, taskId: String)
    fun syncDownloadsFromScheduler(context: Context)
    fun modelSpecProvider(context: Context): ModelSpecProvider
}

internal object DefaultAppRuntimeAccess : AppRuntimeAccess {
    private fun delegate(context: Context): AppRuntimeAccess = resolveAppRuntimeAccess(context)

    override fun installProductionRuntime(context: Context) {
        delegate(context).installProductionRuntime(context.applicationContext)
    }

    override fun runtimeFacade(context: Context): MvpRuntimeFacade {
        return delegate(context).runtimeFacade(context.applicationContext)
    }

    override fun runtimeTuning(context: Context): AndroidRuntimeTuningStore {
        return delegate(context).runtimeTuning(context.applicationContext)
    }

    override fun modelAdmissionPolicy(context: Context): ModelAdmissionPolicy {
        return delegate(context).modelAdmissionPolicy(context.applicationContext)
    }

    override fun currentProvisioningSnapshot(context: Context): RuntimeProvisioningSnapshot {
        return delegate(context).currentProvisioningSnapshot(context.applicationContext)
    }

    override fun observeDownloads(context: Context): StateFlow<List<DownloadTaskState>> {
        return delegate(context).observeDownloads(context.applicationContext)
    }

    override fun observeDownloadPreferences(context: Context): StateFlow<DownloadPreferencesState> {
        return delegate(context).observeDownloadPreferences(context.applicationContext)
    }

    override fun currentDownloadPreferences(context: Context): DownloadPreferencesState {
        return delegate(context).currentDownloadPreferences(context.applicationContext)
    }

    override fun observeModelLifecycle(context: Context): StateFlow<RuntimeModelLifecycleSnapshot> {
        return delegate(context).observeModelLifecycle(context.applicationContext)
    }

    override fun currentModelLifecycle(context: Context): RuntimeModelLifecycleSnapshot {
        return delegate(context).currentModelLifecycle(context.applicationContext)
    }

    override suspend fun importModelFromUri(
        context: Context,
        modelId: String,
        sourceUri: Uri,
    ): RuntimeModelImportResult {
        return delegate(context).importModelFromUri(
            context = context.applicationContext,
            modelId = modelId,
            sourceUri = sourceUri,
        )
    }

    override suspend fun loadModelDistributionManifest(context: Context): ModelDistributionManifest {
        return delegate(context).loadModelDistributionManifest(context.applicationContext)
    }

    override fun listInstalledVersions(context: Context, modelId: String): List<ModelVersionDescriptor> {
        return delegate(context).listInstalledVersions(
            context = context.applicationContext,
            modelId = modelId,
        )
    }

    override fun setActiveVersion(context: Context, modelId: String, version: String): Boolean {
        return delegate(context).setActiveVersion(
            context = context.applicationContext,
            modelId = modelId,
            version = version,
        )
    }

    override fun clearActiveVersion(context: Context, modelId: String): Boolean {
        return delegate(context).clearActiveVersion(
            context = context.applicationContext,
            modelId = modelId,
        )
    }

    override fun removeVersion(context: Context, modelId: String, version: String): Boolean {
        return delegate(context).removeVersion(
            context = context.applicationContext,
            modelId = modelId,
            version = version,
        )
    }

    override suspend fun loadInstalledModel(
        context: Context,
        modelId: String,
        version: String,
    ): RuntimeModelLifecycleCommandResult {
        return delegate(context).loadInstalledModel(
            context = context.applicationContext,
            modelId = modelId,
            version = version,
        )
    }

    override suspend fun loadLastUsedModel(context: Context): RuntimeModelLifecycleCommandResult {
        return delegate(context).loadLastUsedModel(context = context.applicationContext)
    }

    override suspend fun offloadModel(context: Context, reason: String): RuntimeModelLifecycleCommandResult {
        return delegate(context).offloadModel(
            context = context.applicationContext,
            reason = reason,
        )
    }

    override suspend fun enqueueDownload(
        context: Context,
        version: ModelDistributionVersion,
        options: DownloadRequestOptions,
    ): String {
        return delegate(context).enqueueDownload(
            context = context.applicationContext,
            version = version,
            options = options,
        )
    }

    override fun shouldWarnForMeteredLargeDownload(context: Context, version: ModelDistributionVersion): Boolean {
        return delegate(context).shouldWarnForMeteredLargeDownload(
            context = context.applicationContext,
            version = version,
        )
    }

    override fun setDownloadWifiOnlyEnabled(context: Context, enabled: Boolean) {
        delegate(context).setDownloadWifiOnlyEnabled(context.applicationContext, enabled)
    }

    override fun acknowledgeLargeDownloadCellularWarning(context: Context) {
        delegate(context).acknowledgeLargeDownloadCellularWarning(context.applicationContext)
    }

    override fun pauseDownload(context: Context, taskId: String) {
        delegate(context).pauseDownload(context.applicationContext, taskId)
    }

    override fun resumeDownload(context: Context, taskId: String) {
        delegate(context).resumeDownload(context.applicationContext, taskId)
    }

    override fun retryDownload(context: Context, taskId: String) {
        delegate(context).retryDownload(context.applicationContext, taskId)
    }

    override fun cancelDownload(context: Context, taskId: String) {
        delegate(context).cancelDownload(context.applicationContext, taskId)
    }

    override fun syncDownloadsFromScheduler(context: Context) {
        delegate(context).syncDownloadsFromScheduler(context.applicationContext)
    }

    override fun modelSpecProvider(context: Context): ModelSpecProvider {
        return delegate(context).modelSpecProvider(context.applicationContext)
    }
}

internal object CompatibilityAppRuntimeAccess : AppRuntimeAccess {
    override fun installProductionRuntime(context: Context) {
        AppRuntimeDependencies.installProductionRuntime(context.applicationContext)
    }

    override fun runtimeFacade(context: Context): MvpRuntimeFacade {
        return AppRuntimeDependencies.runtimeFacadeFactory()
    }

    override fun runtimeTuning(context: Context): AndroidRuntimeTuningStore {
        return AppRuntimeDependencies.runtimeTuning(context.applicationContext)
    }

    override fun modelAdmissionPolicy(context: Context): ModelAdmissionPolicy {
        return AppRuntimeDependencies.modelAdmissionPolicy(context.applicationContext)
    }

    override fun currentProvisioningSnapshot(context: Context): RuntimeProvisioningSnapshot {
        return AppRuntimeDependencies.currentProvisioningSnapshot(context.applicationContext)
    }

    override fun observeDownloads(context: Context): StateFlow<List<DownloadTaskState>> {
        return AppRuntimeDependencies.observeDownloads(context.applicationContext)
    }

    override fun observeDownloadPreferences(context: Context): StateFlow<DownloadPreferencesState> {
        return AppRuntimeDependencies.observeDownloadPreferences(context.applicationContext)
    }

    override fun currentDownloadPreferences(context: Context): DownloadPreferencesState {
        return AppRuntimeDependencies.currentDownloadPreferences(context.applicationContext)
    }

    override fun observeModelLifecycle(context: Context): StateFlow<RuntimeModelLifecycleSnapshot> {
        return AppRuntimeDependencies.observeModelLifecycle(context.applicationContext)
    }

    override fun currentModelLifecycle(context: Context): RuntimeModelLifecycleSnapshot {
        return AppRuntimeDependencies.currentModelLifecycle(context.applicationContext)
    }

    override suspend fun importModelFromUri(
        context: Context,
        modelId: String,
        sourceUri: Uri,
    ): RuntimeModelImportResult {
        return AppRuntimeDependencies.importModelFromUri(
            context = context.applicationContext,
            modelId = modelId,
            sourceUri = sourceUri,
        )
    }

    override suspend fun loadModelDistributionManifest(context: Context): ModelDistributionManifest {
        return AppRuntimeDependencies.loadModelDistributionManifest(context.applicationContext)
    }

    override fun listInstalledVersions(context: Context, modelId: String): List<ModelVersionDescriptor> {
        return AppRuntimeDependencies.listInstalledVersions(
            context = context.applicationContext,
            modelId = modelId,
        )
    }

    override fun setActiveVersion(context: Context, modelId: String, version: String): Boolean {
        return AppRuntimeDependencies.setActiveVersion(
            context = context.applicationContext,
            modelId = modelId,
            version = version,
        )
    }

    override fun clearActiveVersion(context: Context, modelId: String): Boolean {
        return AppRuntimeDependencies.clearActiveVersion(
            context = context.applicationContext,
            modelId = modelId,
        )
    }

    override fun removeVersion(context: Context, modelId: String, version: String): Boolean {
        return AppRuntimeDependencies.removeVersion(
            context = context.applicationContext,
            modelId = modelId,
            version = version,
        )
    }

    override suspend fun loadInstalledModel(
        context: Context,
        modelId: String,
        version: String,
    ): RuntimeModelLifecycleCommandResult {
        return AppRuntimeDependencies.loadInstalledModel(
            context = context.applicationContext,
            modelId = modelId,
            version = version,
        )
    }

    override suspend fun loadLastUsedModel(context: Context): RuntimeModelLifecycleCommandResult {
        return AppRuntimeDependencies.loadLastUsedModel(context = context.applicationContext)
    }

    override suspend fun offloadModel(context: Context, reason: String): RuntimeModelLifecycleCommandResult {
        return AppRuntimeDependencies.offloadModel(
            context = context.applicationContext,
            reason = reason,
        )
    }

    override suspend fun enqueueDownload(
        context: Context,
        version: ModelDistributionVersion,
        options: DownloadRequestOptions,
    ): String {
        return AppRuntimeDependencies.enqueueDownload(
            context = context.applicationContext,
            version = version,
            options = options,
        )
    }

    override fun shouldWarnForMeteredLargeDownload(context: Context, version: ModelDistributionVersion): Boolean {
        return AppRuntimeDependencies.shouldWarnForMeteredLargeDownload(
            context = context.applicationContext,
            version = version,
        )
    }

    override fun setDownloadWifiOnlyEnabled(context: Context, enabled: Boolean) {
        AppRuntimeDependencies.setDownloadWifiOnlyEnabled(context.applicationContext, enabled)
    }

    override fun acknowledgeLargeDownloadCellularWarning(context: Context) {
        AppRuntimeDependencies.acknowledgeLargeDownloadCellularWarning(context.applicationContext)
    }

    override fun pauseDownload(context: Context, taskId: String) {
        AppRuntimeDependencies.pauseDownload(context.applicationContext, taskId)
    }

    override fun resumeDownload(context: Context, taskId: String) {
        AppRuntimeDependencies.resumeDownload(context.applicationContext, taskId)
    }

    override fun retryDownload(context: Context, taskId: String) {
        AppRuntimeDependencies.retryDownload(context.applicationContext, taskId)
    }

    override fun cancelDownload(context: Context, taskId: String) {
        AppRuntimeDependencies.cancelDownload(context.applicationContext, taskId)
    }

    override fun syncDownloadsFromScheduler(context: Context) {
        AppRuntimeDependencies.syncDownloadsFromScheduler(context.applicationContext)
    }

    override fun modelSpecProvider(context: Context): ModelSpecProvider {
        return AppRuntimeDependencies.modelSpecProvider(context.applicationContext)
    }
}
