package com.pocketagent.android.runtime

import android.content.Context
import android.net.Uri
import com.pocketagent.android.runtime.modelmanager.DownloadPreferencesState
import com.pocketagent.android.runtime.modelmanager.DownloadRequestOptions
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.core.model.ModelSpecProvider
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult
import kotlinx.coroutines.flow.StateFlow

internal interface AppRuntimeProvisioningAccess {
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

private object SingletonAppRuntimeProvisioningAccess : AppRuntimeProvisioningAccess {
    override fun modelAdmissionPolicy(context: Context): ModelAdmissionPolicy {
        return DefaultAppRuntimeAccess.modelAdmissionPolicy(context.applicationContext)
    }

    override fun currentProvisioningSnapshot(context: Context): RuntimeProvisioningSnapshot {
        return DefaultAppRuntimeAccess.currentProvisioningSnapshot(context.applicationContext)
    }

    override fun observeDownloads(context: Context): StateFlow<List<DownloadTaskState>> {
        return DefaultAppRuntimeAccess.observeDownloads(context.applicationContext)
    }

    override fun observeDownloadPreferences(context: Context): StateFlow<DownloadPreferencesState> {
        return DefaultAppRuntimeAccess.observeDownloadPreferences(context.applicationContext)
    }

    override fun currentDownloadPreferences(context: Context): DownloadPreferencesState {
        return DefaultAppRuntimeAccess.currentDownloadPreferences(context.applicationContext)
    }

    override fun observeModelLifecycle(context: Context): StateFlow<RuntimeModelLifecycleSnapshot> {
        return DefaultAppRuntimeAccess.observeModelLifecycle(context.applicationContext)
    }

    override fun currentModelLifecycle(context: Context): RuntimeModelLifecycleSnapshot {
        return DefaultAppRuntimeAccess.currentModelLifecycle(context.applicationContext)
    }

    override suspend fun importModelFromUri(
        context: Context,
        modelId: String,
        sourceUri: Uri,
    ): RuntimeModelImportResult {
        return DefaultAppRuntimeAccess.importModelFromUri(
            context = context.applicationContext,
            modelId = modelId,
            sourceUri = sourceUri,
        )
    }

    override suspend fun loadModelDistributionManifest(context: Context): ModelDistributionManifest {
        return DefaultAppRuntimeAccess.loadModelDistributionManifest(context.applicationContext)
    }

    override fun listInstalledVersions(context: Context, modelId: String): List<ModelVersionDescriptor> {
        return DefaultAppRuntimeAccess.listInstalledVersions(
            context = context.applicationContext,
            modelId = modelId,
        )
    }

    override fun setActiveVersion(context: Context, modelId: String, version: String): Boolean {
        return DefaultAppRuntimeAccess.setActiveVersion(
            context = context.applicationContext,
            modelId = modelId,
            version = version,
        )
    }

    override fun clearActiveVersion(context: Context, modelId: String): Boolean {
        return DefaultAppRuntimeAccess.clearActiveVersion(
            context = context.applicationContext,
            modelId = modelId,
        )
    }

    override fun removeVersion(context: Context, modelId: String, version: String): Boolean {
        return DefaultAppRuntimeAccess.removeVersion(
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
        return DefaultAppRuntimeAccess.loadInstalledModel(
            context = context.applicationContext,
            modelId = modelId,
            version = version,
        )
    }

    override suspend fun loadLastUsedModel(context: Context): RuntimeModelLifecycleCommandResult {
        return DefaultAppRuntimeAccess.loadLastUsedModel(context = context.applicationContext)
    }

    override suspend fun offloadModel(context: Context, reason: String): RuntimeModelLifecycleCommandResult {
        return DefaultAppRuntimeAccess.offloadModel(
            context = context.applicationContext,
            reason = reason,
        )
    }

    override suspend fun enqueueDownload(
        context: Context,
        version: ModelDistributionVersion,
        options: DownloadRequestOptions,
    ): String {
        return DefaultAppRuntimeAccess.enqueueDownload(
            context = context.applicationContext,
            version = version,
            options = options,
        )
    }

    override fun shouldWarnForMeteredLargeDownload(context: Context, version: ModelDistributionVersion): Boolean {
        return DefaultAppRuntimeAccess.shouldWarnForMeteredLargeDownload(
            context = context.applicationContext,
            version = version,
        )
    }

    override fun setDownloadWifiOnlyEnabled(context: Context, enabled: Boolean) {
        DefaultAppRuntimeAccess.setDownloadWifiOnlyEnabled(context.applicationContext, enabled)
    }

    override fun acknowledgeLargeDownloadCellularWarning(context: Context) {
        DefaultAppRuntimeAccess.acknowledgeLargeDownloadCellularWarning(context.applicationContext)
    }

    override fun pauseDownload(context: Context, taskId: String) {
        DefaultAppRuntimeAccess.pauseDownload(context.applicationContext, taskId)
    }

    override fun resumeDownload(context: Context, taskId: String) {
        DefaultAppRuntimeAccess.resumeDownload(context.applicationContext, taskId)
    }

    override fun retryDownload(context: Context, taskId: String) {
        DefaultAppRuntimeAccess.retryDownload(context.applicationContext, taskId)
    }

    override fun cancelDownload(context: Context, taskId: String) {
        DefaultAppRuntimeAccess.cancelDownload(context.applicationContext, taskId)
    }

    override fun syncDownloadsFromScheduler(context: Context) {
        DefaultAppRuntimeAccess.syncDownloadsFromScheduler(context.applicationContext)
    }

    override fun modelSpecProvider(context: Context): ModelSpecProvider {
        return DefaultAppRuntimeAccess.modelSpecProvider(context.applicationContext)
    }
}

internal object AppRuntimeProvisioningBridge : AppRuntimeProvisioningAccess {
    @Volatile
    private var access: AppRuntimeProvisioningAccess = SingletonAppRuntimeProvisioningAccess

    override fun modelAdmissionPolicy(context: Context): ModelAdmissionPolicy = access.modelAdmissionPolicy(context)

    override fun currentProvisioningSnapshot(context: Context): RuntimeProvisioningSnapshot {
        return access.currentProvisioningSnapshot(context)
    }

    override fun observeDownloads(context: Context): StateFlow<List<DownloadTaskState>> {
        return access.observeDownloads(context)
    }

    override fun observeDownloadPreferences(context: Context): StateFlow<DownloadPreferencesState> {
        return access.observeDownloadPreferences(context)
    }

    override fun currentDownloadPreferences(context: Context): DownloadPreferencesState {
        return access.currentDownloadPreferences(context)
    }

    override fun observeModelLifecycle(context: Context): StateFlow<RuntimeModelLifecycleSnapshot> {
        return access.observeModelLifecycle(context)
    }

    override fun currentModelLifecycle(context: Context): RuntimeModelLifecycleSnapshot {
        return access.currentModelLifecycle(context)
    }

    override suspend fun importModelFromUri(
        context: Context,
        modelId: String,
        sourceUri: Uri,
    ): RuntimeModelImportResult {
        return access.importModelFromUri(context, modelId, sourceUri)
    }

    override suspend fun loadModelDistributionManifest(context: Context): ModelDistributionManifest {
        return access.loadModelDistributionManifest(context)
    }

    override fun listInstalledVersions(context: Context, modelId: String): List<ModelVersionDescriptor> {
        return access.listInstalledVersions(context, modelId)
    }

    override fun setActiveVersion(context: Context, modelId: String, version: String): Boolean {
        return access.setActiveVersion(context, modelId, version)
    }

    override fun clearActiveVersion(context: Context, modelId: String): Boolean {
        return access.clearActiveVersion(context, modelId)
    }

    override fun removeVersion(context: Context, modelId: String, version: String): Boolean {
        return access.removeVersion(context, modelId, version)
    }

    override suspend fun loadInstalledModel(
        context: Context,
        modelId: String,
        version: String,
    ): RuntimeModelLifecycleCommandResult {
        return access.loadInstalledModel(context, modelId, version)
    }

    override suspend fun loadLastUsedModel(context: Context): RuntimeModelLifecycleCommandResult {
        return access.loadLastUsedModel(context)
    }

    override suspend fun offloadModel(context: Context, reason: String): RuntimeModelLifecycleCommandResult {
        return access.offloadModel(context, reason)
    }

    override suspend fun enqueueDownload(
        context: Context,
        version: ModelDistributionVersion,
        options: DownloadRequestOptions,
    ): String {
        return access.enqueueDownload(context, version, options)
    }

    override fun shouldWarnForMeteredLargeDownload(context: Context, version: ModelDistributionVersion): Boolean {
        return access.shouldWarnForMeteredLargeDownload(context, version)
    }

    override fun setDownloadWifiOnlyEnabled(context: Context, enabled: Boolean) {
        access.setDownloadWifiOnlyEnabled(context, enabled)
    }

    override fun acknowledgeLargeDownloadCellularWarning(context: Context) {
        access.acknowledgeLargeDownloadCellularWarning(context)
    }

    override fun pauseDownload(context: Context, taskId: String) {
        access.pauseDownload(context, taskId)
    }

    override fun resumeDownload(context: Context, taskId: String) {
        access.resumeDownload(context, taskId)
    }

    override fun retryDownload(context: Context, taskId: String) {
        access.retryDownload(context, taskId)
    }

    override fun cancelDownload(context: Context, taskId: String) {
        access.cancelDownload(context, taskId)
    }

    override fun syncDownloadsFromScheduler(context: Context) {
        access.syncDownloadsFromScheduler(context)
    }

    override fun modelSpecProvider(context: Context): ModelSpecProvider {
        return access.modelSpecProvider(context)
    }

    internal fun swapAccessForTests(testAccess: AppRuntimeProvisioningAccess): AutoCloseable {
        val previous = access
        access = testAccess
        return AutoCloseable {
            access = previous
        }
    }
}
