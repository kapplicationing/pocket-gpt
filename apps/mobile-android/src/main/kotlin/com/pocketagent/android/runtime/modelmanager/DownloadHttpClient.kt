package com.pocketagent.android.runtime.modelmanager

import android.net.Network
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

internal object DownloadHttpClient {
    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    // Metadata lookups (HF tree/model-info/search JSON, distribution manifest)
    // return small payloads, so an overall callTimeout is safe here and bounds a
    // stalled/unreachable host. The GGUF stream client (base/forNetwork) must NOT
    // use callTimeout, because a multi-GB download legitimately exceeds it.
    //
    // This overall bound is the real defense against a network hang: the caller's
    // coroutine withTimeout cannot cancel a blocking OkHttp execute(), but
    // callTimeout aborts the call from OkHttp's own dispatcher.
    private val metadataClient = baseClient.newBuilder()
        .callTimeout(METADATA_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    fun base(): OkHttpClient = baseClient

    fun metadata(): OkHttpClient = metadataClient

    fun forNetwork(network: Network?): OkHttpClient {
        if (network == null) {
            return baseClient
        }
        return baseClient.newBuilder()
            .socketFactory(network.socketFactory)
            .build()
    }
}

private const val METADATA_CALL_TIMEOUT_SECONDS = 25L
