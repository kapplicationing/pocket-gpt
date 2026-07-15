package com.pocketagent.android.runtime.modelmanager

import kotlin.test.Test
import kotlin.test.assertEquals

class ModelDownloadExecutorRetryStateTest {
    @Test
    fun `retry scheduled failures remain nonterminal queued work`() {
        assertEquals(
            DownloadTaskStatus.QUEUED,
            downloadFailureStatus(retryScheduled = true),
        )
    }

    @Test
    fun `exhausted failures remain terminal`() {
        assertEquals(
            DownloadTaskStatus.FAILED,
            downloadFailureStatus(retryScheduled = false),
        )
    }
}
