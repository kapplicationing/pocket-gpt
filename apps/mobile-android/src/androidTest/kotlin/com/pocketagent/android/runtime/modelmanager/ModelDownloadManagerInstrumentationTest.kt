package com.pocketagent.android.runtime.modelmanager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketagent.android.runtime.AndroidRuntimeProvisioningStore
import com.pocketagent.core.model.ModelSourceKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelDownloadManagerInstrumentationTest {
    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setup() {
        ModelDownloadTaskStateStore.resetForTests()
        appContext.deleteDatabase("pocketagent_model_downloads.db")
        appContext.getSharedPreferences("pocketagent_model_downloads", 0).edit().clear().commit()
        ModelDownloadTaskStateStore.resetForTests()
    }

    @After
    fun tearDown() {
        ModelDownloadTaskStateStore.resetForTests()
        appContext.deleteDatabase("pocketagent_model_downloads.db")
        appContext.getSharedPreferences("pocketagent_model_downloads", 0).edit().clear().commit()
        ModelDownloadTaskStateStore.resetForTests()
    }

    @Test
    fun managerPersistsPauseResumeCancelRetryForDynamicHuggingFaceTask() = runBlocking {
        val scheduler = RecordingScheduler()
        val manager = ModelDownloadManager(
            context = appContext,
            provisioningStore = AndroidRuntimeProvisioningStore(appContext),
            scheduler = scheduler,
        )

        val taskId = manager.enqueueDownload(sampleHuggingFaceVersion())
        eventually { scheduler.scheduledTaskIds.contains(taskId) }

        manager.pauseDownload(taskId)

        assertEquals(DownloadTaskStatus.PAUSED, ModelDownloadTaskStateStore.get(appContext, taskId)?.status)
        assertTrue(scheduler.cancelledTaskIds.contains(taskId))

        manager.resumeDownload(taskId)

        eventually { scheduler.scheduledTaskIds.count { it == taskId } >= 2 }
        assertEquals(DownloadTaskStatus.QUEUED, ModelDownloadTaskStateStore.get(appContext, taskId)?.status)

        manager.cancelDownload(taskId)

        assertEquals(DownloadTaskStatus.CANCELLED, ModelDownloadTaskStateStore.get(appContext, taskId)?.status)
        assertTrue(scheduler.cancelledTaskIds.count { it == taskId } >= 2)

        manager.retryDownload(taskId)

        eventually { scheduler.scheduledTaskIds.count { it == taskId } >= 3 }
        val retried = ModelDownloadTaskStateStore.get(appContext, taskId)
        assertEquals(DownloadTaskStatus.QUEUED, retried?.status)
        assertEquals(DownloadProcessingStage.DOWNLOADING, retried?.processingStage)
        assertEquals(null, retried?.failureReason)
        assertEquals("Retrying", retried?.message)
    }

    private fun sampleHuggingFaceVersion(): ModelDistributionVersion {
        return ModelDistributionVersion(
            modelId = "qwen3-0.6b-q4_k_m",
            version = "hf-tiny-aaaaaaaaaaaa",
            sourceKind = ModelSourceKind.HUGGING_FACE,
            displayName = "fixture/tiny-gguf / tiny.gguf",
            downloadUrl = "https://huggingface.co/fixture/tiny-gguf/resolve/main/tiny.gguf",
            expectedSha256 = "a".repeat(64),
            provenanceIssuer = "huggingface:fixture/tiny-gguf",
            provenanceSignature = "",
            verificationPolicy = DownloadVerificationPolicy.INTEGRITY_ONLY,
            runtimeCompatibility = "android-arm64-v8a",
            fileSizeBytes = 1024L,
        )
    }

    private suspend fun eventually(assertion: () -> Boolean) {
        repeat(20) {
            if (assertion()) {
                return
            }
            kotlinx.coroutines.delay(100L)
        }
        assertTrue(assertion())
    }
}

private class RecordingScheduler : DownloadExecutionScheduler {
    private val taskInfos = MutableStateFlow<List<ScheduledTaskSnapshot>>(emptyList())
    val scheduledTaskIds = mutableListOf<String>()
    val cancelledTaskIds = mutableListOf<String>()

    override fun observeTaskInfos(): Flow<List<ScheduledTaskSnapshot>> = taskInfos

    override suspend fun currentTaskInfos(): List<ScheduledTaskSnapshot> = taskInfos.value

    override fun schedule(task: DownloadTaskState) {
        scheduledTaskIds += task.taskId
        taskInfos.value = taskInfos.value
            .filterNot { snapshot -> snapshot.taskId == task.taskId }
            .plus(ScheduledTaskSnapshot(taskId = task.taskId, status = ScheduledTaskStatus.ENQUEUED))
    }

    override fun cancel(taskId: String) {
        cancelledTaskIds += taskId
        taskInfos.value = taskInfos.value.filterNot { snapshot -> snapshot.taskId == taskId }
    }
}
