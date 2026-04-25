package com.pocketagent.android.runtime.modelmanager

import kotlin.test.Test
import kotlin.test.assertEquals

class ModelDownloadCancelReceiverTest {
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
