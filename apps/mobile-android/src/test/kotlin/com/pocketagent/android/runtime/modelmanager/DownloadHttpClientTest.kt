package com.pocketagent.android.runtime.modelmanager

import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DownloadHttpClientTest {
    @Test
    fun `metadata client has an overall call timeout but the stream client does not`() {
        // Metadata lookups (HF tree/model-info/search, distribution manifest) must be
        // bounded so an unreachable/stalled host cannot hang candidate resolution: the
        // caller's coroutine withTimeout cannot cancel a blocking OkHttp execute(), only
        // callTimeout can. The GGUF stream client must stay unbounded because a multi-GB
        // download legitimately exceeds any fixed call timeout.
        assertTrue(
            DownloadHttpClient.metadata().callTimeoutMillis > 0,
            "metadata() must set an overall callTimeout",
        )
        assertEquals(
            0,
            DownloadHttpClient.base().callTimeoutMillis,
            "base()/stream client must not set a callTimeout",
        )
    }

    @Test
    fun `an overall call timeout aborts a stalled response that read timeout alone would not`() {
        // Behavioural proof of the mechanism the metadata client relies on: with a short
        // callTimeout, a response whose body stalls longer than the callTimeout is aborted,
        // even though no single read has yet exceeded the (longer) readTimeout.
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("{}")
                .setBodyDelay(2, TimeUnit.SECONDS),
        )
        server.start()
        try {
            val boundedClient = DownloadHttpClient.base().newBuilder()
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(300, TimeUnit.MILLISECONDS)
                .build()
            val request = Request.Builder().url(server.url("/health")).build()
            assertFailsWith<IOException> {
                boundedClient.newCall(request).execute().use { it.body?.string() }
            }
        } finally {
            server.shutdown()
        }
    }
}
