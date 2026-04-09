package com.pocketagent.android.runtime

import android.net.Uri
import com.pocketagent.android.AppRuntimeDependencies
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.DownloadPreferencesState
import com.pocketagent.android.runtime.modelmanager.DownloadRequestOptions
import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.core.model.ModelSpecProvider
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult
import kotlinx.coroutines.flow.StateFlow

interface ProvisioningGateway {
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
) : ProvisioningGateway {
    constructor(appContext: android.content.Context) : this(
        dependencies = AppProvisioningDependencyAccess(appContext.applicationContext),
        admissionPolicy = AppRuntimeDependencies.modelAdmissionPolicy(appContext.applicationContext),
    )

    override fun currentSnapshot(): RuntimeProvisioningSnapshot {
        return dependencies.currentProvisioningSnapshot()
    }

    override fun observeDownloads(): StateFlow<List<DownloadTaskState>> {
        return dependencies.observeDownloads()
    }

    override fun observeDownloadPreferences(): StateFlow<DownloadPreferencesState> {
        return dependencies.observeDownloadPreferences()
    }

    override fun currentDownloadPreferences(): DownloadPreferencesState {
        return dependencies.currentDownloadPreferences()
    }

    override fun observeModelLifecycle(): StateFlow<RuntimeModelLifecycleSnapshot> {
        return dependencies.observeModelLifecycle()
    }

    override fun currentModelLifecycle(): RuntimeModelLifecycleSnapshot {
        return dependencies.currentModelLifecycle()
    }

    override suspend fun importModelFromUri(modelId: String, sourceUri: Uri): RuntimeModelImportResult {
        admissionPolicy?.requireAllowed(
            action = ModelAdmissionAction.IMPORT,
            subject = ModelAdmissionSubject(modelId = modelId),
        )
        return dependencies.importModelFromUri(
            modelId = modelId,
            sourceUri = sourceUri,
        )
    }

    override suspend fun loadModelDistributionManifest(): ModelDistributionManifest {
        return dependencies.loadModelDistributionManifest()
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
        return dependencies.setActiveVersion(modelId = modelId, version = version)
    }

    override fun clearActiveVersion(modelId: String): Boolean {
        return dependencies.clearActiveVersion(modelId = modelId)
    }

    override fun removeVersion(modelId: String, version: String): Boolean {
        return dependencies.removeVersion(modelId = modelId, version = version)
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
        return dependencies.loadInstalledModel(modelId = modelId, version = version)
    }

    override suspend fun loadLastUsedModel(): RuntimeModelLifecycleCommandResult {
        return dependencies.loadLastUsedModel()
    }

    override suspend fun offloadModel(reason: String): RuntimeModelLifecycleCommandResult {
        return dependencies.offloadModel(reason = reason)
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
    }

    override fun acknowledgeLargeDownloadCellularWarning() {
        dependencies.acknowledgeLargeDownloadCellularWarning()
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

class AppProvisioningDependencyAccess(
    private val context: android.content.Context,
) : ProvisioningDependencyAccess {
    override fun currentProvisioningSnapshot(): RuntimeProvisioningSnapshot {
        return AppRuntimeDependencies.currentProvisioningSnapshot(context)
    }

    override fun observeDownloads(): StateFlow<List<DownloadTaskState>> {
        return AppRuntimeDependencies.observeDownloads(context)
    }

    override fun observeDownloadPreferences(): StateFlow<DownloadPreferencesState> {
        return AppRuntimeDependencies.observeDownloadPreferences(context)
    }

    override fun currentDownloadPreferences(): DownloadPreferencesState {
        return AppRuntimeDependencies.currentDownloadPreferences(context)
    }

    override fun observeModelLifecycle(): StateFlow<RuntimeModelLifecycleSnapshot> {
        return AppRuntimeDependencies.observeModelLifecycle(context)
    }

    override fun currentModelLifecycle(): RuntimeModelLifecycleSnapshot {
        return AppRuntimeDependencies.currentModelLifecycle(context)
    }

    override suspend fun importModelFromUri(
        modelId: String,
        sourceUri: Uri,
    ): RuntimeModelImportResult {
        return AppRuntimeDependencies.importModelFromUri(context = context, modelId = modelId, sourceUri = sourceUri)
    }

    override suspend fun loadModelDistributionManifest(): ModelDistributionManifest {
        return AppRuntimeDependencies.loadModelDistributionManifest(context)
    }

    override fun listInstalledVersions(
        modelId: String,
    ): List<ModelVersionDescriptor> {
        return AppRuntimeDependencies.listInstalledVersions(context = context, modelId = modelId)
    }

    override fun setActiveVersion(modelId: String, version: String): Boolean {
        return AppRuntimeDependencies.setActiveVersion(context = context, modelId = modelId, version = version)
    }

    override fun clearActiveVersion(modelId: String): Boolean {
        return AppRuntimeDependencies.clearActiveVersion(context = context, modelId = modelId)
    }

    override fun removeVersion(modelId: String, version: String): Boolean {
        return AppRuntimeDependencies.removeVersion(context = context, modelId = modelId, version = version)
    }

    override suspend fun loadInstalledModel(modelId: String, version: String): RuntimeModelLifecycleCommandResult {
        return AppRuntimeDependencies.loadInstalledModel(
            context = context,
            modelId = modelId,
            version = version,
        )
    }

    override suspend fun loadLastUsedModel(): RuntimeModelLifecycleCommandResult {
        return AppRuntimeDependencies.loadLastUsedModel(context = context)
    }

    override suspend fun offloadModel(reason: String): RuntimeModelLifecycleCommandResult {
        return AppRuntimeDependencies.offloadModel(context = context, reason = reason)
    }

    override suspend fun enqueueDownload(version: ModelDistributionVersion, options: DownloadRequestOptions): String {
        return AppRuntimeDependencies.enqueueDownload(context = context, version = version, options = options)
    }

    override fun shouldWarnForMeteredLargeDownload(version: ModelDistributionVersion): Boolean {
        return AppRuntimeDependencies.shouldWarnForMeteredLargeDownload(context = context, version = version)
    }

    override fun setDownloadWifiOnlyEnabled(enabled: Boolean) {
        AppRuntimeDependencies.setDownloadWifiOnlyEnabled(context, enabled)
    }

    override fun acknowledgeLargeDownloadCellularWarning() {
        AppRuntimeDependencies.acknowledgeLargeDownloadCellularWarning(context)
    }

    override fun pauseDownload(taskId: String) {
        AppRuntimeDependencies.pauseDownload(context, taskId)
    }

    override fun resumeDownload(taskId: String) {
        AppRuntimeDependencies.resumeDownload(context, taskId)
    }

    override fun retryDownload(taskId: String) {
        AppRuntimeDependencies.retryDownload(context, taskId)
    }

    override fun cancelDownload(taskId: String) {
        AppRuntimeDependencies.cancelDownload(context, taskId)
    }

    override fun syncDownloadsFromScheduler() {
        AppRuntimeDependencies.syncDownloadsFromScheduler(context)
    }
}

fun modelSpecProviderForContext(context: android.content.Context): ModelSpecProvider {
    return AppRuntimeDependencies.modelSpecProvider(context.applicationContext)
}
