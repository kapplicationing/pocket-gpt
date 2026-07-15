package com.pocketagent.android.runtime.modelmanager

import kotlin.test.Test
import kotlin.test.assertEquals

class ModelDownloadWorkerStopDispositionTest {
    @Test
    fun `WorkManager stop reschedules instead of marking download cancelled`() {
        assertEquals(
            DownloadStopDisposition.RESCHEDULE,
            modelDownloadWorkerStopDisposition(),
        )
    }
}
