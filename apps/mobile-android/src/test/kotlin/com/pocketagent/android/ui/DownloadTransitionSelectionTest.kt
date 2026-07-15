package com.pocketagent.android.ui

import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.DownloadTaskStatus
import com.pocketagent.runtime.RuntimeLoadedModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadTransitionSelectionTest {
    @Test
    fun `restored terminal task resumes pending download and use intent`() {
        val completed = task("task", DownloadTaskStatus.INSTALLED_INACTIVE)

        val selected = selectDownloadTransition(
            downloads = listOf(completed),
            previousStatuses = emptyMap(),
            pendingGetReadyActivation = MODEL_ID to VERSION,
        )

        assertEquals(completed, selected)
    }

    @Test
    fun `terminal history is ignored when no setup intent is pending`() {
        val selected = selectDownloadTransition(
            downloads = listOf(task("task", DownloadTaskStatus.FAILED)),
            previousStatuses = emptyMap(),
            pendingGetReadyActivation = null,
        )

        assertNull(selected)
    }

    @Test
    fun `matching terminal transition beats unrelated status change`() {
        val unrelated = task("active", DownloadTaskStatus.DOWNLOADING).copy(
            modelId = "another-model",
            version = "q8",
        )
        val completedTarget = task("target", DownloadTaskStatus.COMPLETED)

        val selected = selectDownloadTransition(
            downloads = listOf(unrelated, completedTarget),
            previousStatuses = mapOf(
                "active" to DownloadTaskStatus.QUEUED,
                "target" to DownloadTaskStatus.DOWNLOADING,
            ),
            pendingGetReadyActivation = MODEL_ID to VERSION,
        )

        assertEquals(completedTarget, selected)
    }

    @Test
    fun `older terminal history does not beat a newer active retry`() {
        val oldFailure = task(
            taskId = "old",
            status = DownloadTaskStatus.FAILED,
            updatedAtEpochMs = 1L,
        )
        val queuedRetry = task(
            taskId = "retry",
            status = DownloadTaskStatus.QUEUED,
            updatedAtEpochMs = 2L,
        )

        val selected = selectDownloadTransition(
            downloads = listOf(oldFailure, queuedRetry),
            previousStatuses = emptyMap(),
            pendingGetReadyActivation = MODEL_ID to VERSION,
        )

        assertNull(selected)
    }

    @Test
    fun `pending activation completes only when its exact model is send ready`() {
        val pending = MODEL_ID to VERSION

        assertEquals(
            true,
            shouldCompleteGetReadyActivation(
                pendingActivation = pending,
                loadedModel = RuntimeLoadedModel(MODEL_ID, VERSION),
                sendReady = true,
            ),
        )
        assertEquals(
            false,
            shouldCompleteGetReadyActivation(
                pendingActivation = pending,
                loadedModel = RuntimeLoadedModel(MODEL_ID, "other"),
                sendReady = true,
            ),
        )
        assertEquals(
            false,
            shouldCompleteGetReadyActivation(
                pendingActivation = pending,
                loadedModel = RuntimeLoadedModel(MODEL_ID, VERSION),
                sendReady = false,
            ),
        )
    }

    @Test
    fun `existing target load is allowed to finish without another load command`() {
        val pending = MODEL_ID to VERSION

        assertTrue(
            shouldWaitForPendingGetReadyLoad(
                pendingActivation = pending,
                loadedModel = null,
                activeModelLoadRequest = RuntimeLoadedModel(MODEL_ID, VERSION),
            ),
        )
        assertFalse(
            shouldSupersedePendingGetReadyActivation(
                pendingActivation = pending,
                loadedModel = RuntimeLoadedModel(MODEL_ID, VERSION),
                activeModelLoadRequest = null,
            ),
        )
    }

    @Test
    fun `different manually selected model supersedes pending starter load`() {
        assertTrue(
            shouldSupersedePendingGetReadyActivation(
                pendingActivation = MODEL_ID to VERSION,
                loadedModel = RuntimeLoadedModel("manual-model", "q8"),
                activeModelLoadRequest = null,
            ),
        )
    }

    @Test
    fun `automatic load loses ownership when a manual selection clears or replaces its target`() {
        val expected = MODEL_ID to VERSION

        assertTrue(
            shouldIssuePendingGetReadyLoad(
                expectedActivation = expected,
                currentPendingActivation = expected,
                loadedModel = null,
                activeModelLoadRequest = null,
            ),
        )
        assertFalse(
            retainsPendingGetReadyOwnership(
                expectedActivation = expected,
                currentPendingActivation = null,
                loadedModel = null,
                activeModelLoadRequest = null,
            ),
        )
        assertFalse(
            shouldIssuePendingGetReadyLoad(
                expectedActivation = expected,
                currentPendingActivation = expected,
                loadedModel = RuntimeLoadedModel("manual-model", "q8"),
                activeModelLoadRequest = null,
            ),
        )
        assertFalse(
            shouldIssuePendingGetReadyLoad(
                expectedActivation = expected,
                currentPendingActivation = expected,
                loadedModel = null,
                activeModelLoadRequest = RuntimeLoadedModel(MODEL_ID, VERSION),
            ),
        )
    }

    @Test
    fun `installed target without a scheduler task is reconciled after recreation`() {
        assertTrue(
            shouldReconcileInstalledGetReadyActivation(
                InstalledGetReadyRecoveryState(
                    pendingActivation = MODEL_ID to VERSION,
                    targetIsInstalled = true,
                    setupRequestInFlight = false,
                    setupFailureMessage = null,
                    matchingMeteredWarningPending = false,
                    downloads = emptyList(),
                ),
            ),
        )
        assertFalse(
            shouldReconcileInstalledGetReadyActivation(
                InstalledGetReadyRecoveryState(
                    pendingActivation = MODEL_ID to VERSION,
                    targetIsInstalled = true,
                    setupRequestInFlight = true,
                    setupFailureMessage = null,
                    matchingMeteredWarningPending = false,
                    downloads = emptyList(),
                ),
            ),
        )
        assertFalse(
            shouldReconcileInstalledGetReadyActivation(
                InstalledGetReadyRecoveryState(
                    pendingActivation = MODEL_ID to VERSION,
                    targetIsInstalled = false,
                    setupRequestInFlight = false,
                    setupFailureMessage = null,
                    matchingMeteredWarningPending = false,
                    downloads = emptyList(),
                ),
            ),
        )
        assertFalse(
            shouldReconcileInstalledGetReadyActivation(
                InstalledGetReadyRecoveryState(
                    pendingActivation = MODEL_ID to VERSION,
                    targetIsInstalled = true,
                    setupRequestInFlight = false,
                    setupFailureMessage = "Load failed",
                    matchingMeteredWarningPending = false,
                    downloads = emptyList(),
                ),
            ),
        )
        assertFalse(
            shouldReconcileInstalledGetReadyActivation(
                InstalledGetReadyRecoveryState(
                    pendingActivation = MODEL_ID to VERSION,
                    targetIsInstalled = true,
                    setupRequestInFlight = false,
                    setupFailureMessage = null,
                    matchingMeteredWarningPending = false,
                    downloads = listOf(task("existing", DownloadTaskStatus.COMPLETED)),
                ),
            ),
        )
        assertFalse(
            shouldReconcileInstalledGetReadyActivation(
                InstalledGetReadyRecoveryState(
                    pendingActivation = MODEL_ID to VERSION,
                    targetIsInstalled = true,
                    setupRequestInFlight = false,
                    setupFailureMessage = null,
                    matchingMeteredWarningPending = true,
                    downloads = emptyList(),
                ),
            ),
        )
        assertTrue(
            shouldReconcileInstalledGetReadyActivation(
                InstalledGetReadyRecoveryState(
                    pendingActivation = MODEL_ID to VERSION,
                    targetIsInstalled = true,
                    setupRequestInFlight = false,
                    setupFailureMessage = null,
                    matchingMeteredWarningPending = false,
                    downloads = listOf(
                        task("unrelated", DownloadTaskStatus.DOWNLOADING).copy(
                            modelId = "other-model",
                            version = "q8",
                        ),
                    ),
                ),
            ),
        )
    }

    private fun task(
        taskId: String,
        status: DownloadTaskStatus,
        updatedAtEpochMs: Long = 1L,
    ): DownloadTaskState {
        return DownloadTaskState(
            taskId = taskId,
            modelId = MODEL_ID,
            version = VERSION,
            downloadUrl = "https://example.test/model.gguf",
            expectedSha256 = "a".repeat(64),
            provenanceIssuer = "issuer",
            provenanceSignature = "signature",
            runtimeCompatibility = "android-arm64-v8a",
            status = status,
            progressBytes = if (status == DownloadTaskStatus.DOWNLOADING) 40L else 100L,
            totalBytes = 100L,
            updatedAtEpochMs = updatedAtEpochMs,
        )
    }

    private companion object {
        const val MODEL_ID = "starter"
        const val VERSION = "q4"
    }
}
