package com.pocketagent.android.runtime.modelmanager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketagent.android.runtime.AndroidRuntimeProvisioningStore
import com.pocketagent.core.model.ModelSourceKind
import com.pocketagent.core.model.ModelSourceRef
import com.pocketagent.core.model.SourceTrustPolicy
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        resetProvisioningStorage()
        ModelDownloadTaskStateStore.resetForTests()
    }

    @After
    fun tearDown() {
        ModelDownloadTaskStateStore.resetForTests()
        appContext.deleteDatabase("pocketagent_model_downloads.db")
        appContext.getSharedPreferences("pocketagent_model_downloads", 0).edit().clear().commit()
        resetProvisioningStorage()
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

    @Test
    fun concurrentEnqueueOfTheSameVersionReusesOneTask() = runBlocking {
        val manager = ModelDownloadManager(
            context = appContext,
            provisioningStore = AndroidRuntimeProvisioningStore(appContext),
            scheduler = RecordingScheduler(),
        )
        val version = sampleHuggingFaceVersion()

        val taskIds = coroutineScope {
            List(2) {
                async(Dispatchers.Default) {
                    manager.enqueueDownload(version)
                }
            }.awaitAll()
        }

        assertEquals(1, taskIds.toSet().size)
        assertEquals(
            1,
            ModelDownloadTaskStateStore.list(appContext)
                .count { task -> task.modelId == version.modelId && task.version == version.version },
        )
    }

    @Test
    fun executorDownloadsVerifiesAndInstallsDynamicHuggingFaceTaskFromDeviceFixture() = runBlocking {
        val payload = "PocketGPT HF fixture bytes\n".repeat(32).encodeToByteArray()
        val sha256 = sha256Hex(payload)
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/octet-stream")
                .setBody(payload.decodeToString()),
        )
        server.start()
        try {
            val manager = ModelDownloadManager(
                context = appContext,
                provisioningStore = AndroidRuntimeProvisioningStore(appContext),
                scheduler = RecordingScheduler(),
            )
            val version = sampleHuggingFaceVersion(
                downloadUrl = server.url("/fixture/tiny-gguf/resolve/main/tiny.gguf").toString(),
                expectedSha256 = sha256,
                fileSizeBytes = payload.size.toLong(),
            )

            val taskId = manager.enqueueDownload(version)
            val outcome = ModelDownloadExecutor(appContext).execute(
                taskId = taskId,
                host = RecordingDownloadExecutionHost(),
                network = null,
                retryAllowed = false,
            )

            assertEquals(DownloadExecutionOutcome.SUCCESS, outcome)
            val completed = ModelDownloadTaskStateStore.get(appContext, taskId)
            assertNotNull(completed)
            assertTrue(completed!!.status == DownloadTaskStatus.INSTALLED_INACTIVE || completed.status == DownloadTaskStatus.COMPLETED)
            assertTrue(completed.terminal)
            assertTrue(completed.progressBytes > 0L)
            assertEquals(ModelSourceKind.HUGGING_FACE, completed.sourceKind)
            assertEquals(version.sourceRef, completed.sourceRef)
            assertTrue(completed.artifactStates.single().installedAbsolutePath?.let { File(it).isFile } == true)

            val installed = AndroidRuntimeProvisioningStore(appContext)
                .listInstalledVersions(version.modelId)
                .single { it.version == version.version }
            assertEquals(ModelSourceKind.HUGGING_FACE, installed.sourceKind)
            assertEquals(version.sourceRef, installed.sourceRef)
            assertTrue(File(installed.absolutePath).isFile)
        } finally {
            server.shutdown()
        }
    }

    private fun sampleHuggingFaceVersion(
        downloadUrl: String = "https://huggingface.co/fixture/tiny-gguf/resolve/main/tiny.gguf",
        expectedSha256: String = "a".repeat(64),
        fileSizeBytes: Long = 1024L,
    ): ModelDistributionVersion {
        return ModelDistributionVersion(
            modelId = "qwen3-0.6b-q4_k_m",
            version = "hf-tiny-aaaaaaaaaaaa",
            sourceKind = ModelSourceKind.HUGGING_FACE,
            displayName = "fixture/tiny-gguf / tiny.gguf",
            downloadUrl = downloadUrl,
            expectedSha256 = expectedSha256,
            provenanceIssuer = "huggingface:fixture/tiny-gguf",
            provenanceSignature = "",
            verificationPolicy = DownloadVerificationPolicy.INTEGRITY_ONLY,
            runtimeCompatibility = "android-arm64-v8a",
            fileSizeBytes = fileSizeBytes,
            sourceRef = ModelSourceRef(
                kind = ModelSourceKind.HUGGING_FACE,
                originId = "qwen3-0.6b-q4_k_m",
                publisher = "fixture",
                repository = "fixture/tiny-gguf",
                trustPolicy = SourceTrustPolicy.INTEGRITY_ONLY,
                revision = "main",
                originUrl = "https://huggingface.co/fixture/tiny-gguf/resolve/main/tiny.gguf",
            ),
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

    private fun resetProvisioningStorage() {
        appContext.getSharedPreferences("pocketagent_runtime_models", 0).edit().clear().commit()
        File(appContext.filesDir, "runtime-models").deleteRecursively()
        File(appContext.filesDir, "runtime-model-downloads").deleteRecursively()
        File("/sdcard/Android/media/${appContext.packageName}/models").deleteRecursively()
        File("/storage/emulated/0/Android/media/${appContext.packageName}/models").deleteRecursively()
        File("/sdcard/Android/media/${appContext.packageName}/runtime-model-downloads").deleteRecursively()
        File("/storage/emulated/0/Android/media/${appContext.packageName}/runtime-model-downloads").deleteRecursively()
        appContext.getExternalFilesDir(null)?.let { mediaDir ->
            File(mediaDir, "models").deleteRecursively()
            File(mediaDir, "runtime-model-downloads").deleteRecursively()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
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

private class RecordingDownloadExecutionHost : DownloadExecutionHost {
    val notificationUpdates = mutableListOf<Pair<String, Int>>()

    override suspend fun updateNotification(taskId: String, modelId: String, percent: Int) {
        notificationUpdates += taskId to percent
    }

    override fun isStopped(): Boolean = false

    override fun stopDisposition(): DownloadStopDisposition = DownloadStopDisposition.MARK_CANCELLED
}
