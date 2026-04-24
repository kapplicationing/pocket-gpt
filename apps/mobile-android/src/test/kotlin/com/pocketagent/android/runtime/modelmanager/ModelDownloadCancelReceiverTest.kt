package com.pocketagent.android.runtime.modelmanager

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import com.pocketagent.android.runtime.AppRuntimeProvisioningAccess
import com.pocketagent.android.runtime.AppRuntimeProvisioningBridge
import com.pocketagent.android.runtime.ModelAdmissionPolicy
import com.pocketagent.android.runtime.RuntimeModelImportResult
import com.pocketagent.android.runtime.RuntimeModelLifecycleSnapshot
import com.pocketagent.android.runtime.RuntimeProvisioningSnapshot
import com.pocketagent.core.model.ModelSpecProvider
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelDownloadCancelReceiverTest {
    @Test
    fun `provisioning bridge delegates download cancellation to configured access`() {
        val access = RecordingProvisioningAccess()
        val context = TestReceiverContext()

        AppRuntimeProvisioningBridge.swapAccessForTests(access).use {
            AppRuntimeProvisioningBridge.cancelDownload(context, "task-42")
        }

        assertEquals(listOf("task-42"), access.cancelledTaskIds)
    }

    @Test
    fun `receiver helper accepts only cancel action with non blank task id`() {
        assertEquals(
            "task-42",
            resolveDownloadCancellationTaskId(
                action = ModelDownloadCancelReceiver.ACTION_CANCEL_DOWNLOAD,
                rawTaskId = " task-42 ",
            ),
        )
        assertEquals(
            null,
            resolveDownloadCancellationTaskId(
                action = "other.action",
                rawTaskId = "task-42",
            ),
        )
        assertEquals(
            null,
            resolveDownloadCancellationTaskId(
                action = ModelDownloadCancelReceiver.ACTION_CANCEL_DOWNLOAD,
                rawTaskId = "   ",
            ),
        )
    }
}

private class TestReceiverContext : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this
}

private class RecordingProvisioningAccess : AppRuntimeProvisioningAccess {
    val cancelledTaskIds: MutableList<String> = mutableListOf()

    override fun modelAdmissionPolicy(context: Context): ModelAdmissionPolicy {
        throw AssertionError("Not used in receiver test")
    }

    override fun currentProvisioningSnapshot(context: Context): RuntimeProvisioningSnapshot {
        throw AssertionError("Not used in receiver test")
    }

    override fun observeDownloads(context: Context): StateFlow<List<DownloadTaskState>> {
        return MutableStateFlow(emptyList())
    }

    override fun observeDownloadPreferences(context: Context): StateFlow<DownloadPreferencesState> {
        return MutableStateFlow(DownloadPreferencesState())
    }

    override fun currentDownloadPreferences(context: Context): DownloadPreferencesState {
        return DownloadPreferencesState()
    }

    override fun observeModelLifecycle(context: Context): StateFlow<com.pocketagent.android.runtime.RuntimeModelLifecycleSnapshot> {
        return MutableStateFlow(RuntimeModelLifecycleSnapshot.initial())
    }

    override fun currentModelLifecycle(context: Context): com.pocketagent.android.runtime.RuntimeModelLifecycleSnapshot {
        return RuntimeModelLifecycleSnapshot.initial()
    }

    override suspend fun importModelFromUri(
        context: Context,
        modelId: String,
        sourceUri: Uri,
    ): RuntimeModelImportResult {
        throw AssertionError("Not used in receiver test")
    }

    override suspend fun loadModelDistributionManifest(context: Context): ModelDistributionManifest {
        throw AssertionError("Not used in receiver test")
    }

    override fun listInstalledVersions(context: Context, modelId: String): List<ModelVersionDescriptor> {
        return emptyList()
    }

    override fun setActiveVersion(context: Context, modelId: String, version: String): Boolean = false

    override fun clearActiveVersion(context: Context, modelId: String): Boolean = false

    override fun removeVersion(context: Context, modelId: String, version: String): Boolean = false

    override suspend fun loadInstalledModel(
        context: Context,
        modelId: String,
        version: String,
    ): RuntimeModelLifecycleCommandResult {
        throw AssertionError("Not used in receiver test")
    }

    override suspend fun loadLastUsedModel(context: Context): RuntimeModelLifecycleCommandResult {
        throw AssertionError("Not used in receiver test")
    }

    override suspend fun offloadModel(context: Context, reason: String): RuntimeModelLifecycleCommandResult {
        throw AssertionError("Not used in receiver test")
    }

    override suspend fun enqueueDownload(
        context: Context,
        version: ModelDistributionVersion,
        options: DownloadRequestOptions,
    ): String {
        throw AssertionError("Not used in receiver test")
    }

    override fun shouldWarnForMeteredLargeDownload(context: Context, version: ModelDistributionVersion): Boolean = false

    override fun setDownloadWifiOnlyEnabled(context: Context, enabled: Boolean) = Unit

    override fun acknowledgeLargeDownloadCellularWarning(context: Context) = Unit

    override fun pauseDownload(context: Context, taskId: String) = Unit

    override fun resumeDownload(context: Context, taskId: String) = Unit

    override fun retryDownload(context: Context, taskId: String) = Unit

    override fun cancelDownload(context: Context, taskId: String) {
        cancelledTaskIds += taskId
    }

    override fun syncDownloadsFromScheduler(context: Context) = Unit

    override fun modelSpecProvider(context: Context): ModelSpecProvider {
        throw AssertionError("Not used in receiver test")
    }
}
