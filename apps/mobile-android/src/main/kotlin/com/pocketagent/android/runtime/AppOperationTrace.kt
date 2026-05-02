package com.pocketagent.android.runtime

import android.os.Trace
import android.util.Log
import com.pocketagent.android.BuildConfig

/**
 * Lightweight operation timing for RCA work.
 *
 * Trace sections show up in Perfetto; structured log lines make logcat captures
 * useful when Perfetto was not running. Keep this at operation boundaries, not
 * inside per-token loops.
 */
object AppOperationTrace {
    fun <T> section(
        name: String,
        logTag: String = "PocketGPTPerf",
        detail: () -> String? = { null },
        block: () -> T,
    ): T {
        val startedAtMs = if (BuildConfig.DEBUG) System.currentTimeMillis() else 0L
        beginTrace(name)
        return try {
            block()
        } finally {
            endTrace()
            if (BuildConfig.DEBUG) {
                val elapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
                val suffix = detail()?.takeIf { it.isNotBlank() }?.let { "|$it" }.orEmpty()
                runCatching { Log.i(logTag, "PERF_OP|name=$name|duration_ms=$elapsedMs$suffix") }
            }
        }
    }

    suspend fun <T> suspendSection(
        name: String,
        logTag: String = "PocketGPTPerf",
        detail: () -> String? = { null },
        block: suspend () -> T,
    ): T {
        val startedAtMs = if (BuildConfig.DEBUG) System.currentTimeMillis() else 0L
        beginTrace(name)
        return try {
            block()
        } finally {
            endTrace()
            if (BuildConfig.DEBUG) {
                val elapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
                val suffix = detail()?.takeIf { it.isNotBlank() }?.let { "|$it" }.orEmpty()
                runCatching { Log.i(logTag, "PERF_OP|name=$name|duration_ms=$elapsedMs$suffix") }
            }
        }
    }

    fun beginTrace(name: String) {
        runCatching { Trace.beginSection(name.take(MAX_TRACE_SECTION_LENGTH)) }
    }

    fun endTrace() {
        runCatching { Trace.endSection() }
    }

    private const val MAX_TRACE_SECTION_LENGTH = 120
}
