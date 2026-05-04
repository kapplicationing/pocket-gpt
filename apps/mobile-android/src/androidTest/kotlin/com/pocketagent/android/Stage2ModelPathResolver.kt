package com.pocketagent.android

import android.app.Instrumentation
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream

internal fun resolveStage2ModelPath(rawPath: String): String? {
    val normalized = rawPath.trim()
    if (normalized.isEmpty()) {
        return null
    }
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val fileName = File(normalized).name
    val candidates = buildList {
        add(normalized)
        add(normalized.replace("/sdcard/", "/storage/emulated/0/"))
        add(normalized.replace("/storage/emulated/0/", "/sdcard/"))
        if (fileName.isNotEmpty()) {
            val modelRoots = listOf(
                "/sdcard/Android/media/com.pocketagent.android/models",
                "/storage/emulated/0/Android/media/com.pocketagent.android/models",
                "/sdcard/Download/com.pocketagent.android/models",
                "/storage/emulated/0/Download/com.pocketagent.android/models",
                "/sdcard/Android/data/com.pocketagent.android/cache/stage2-models",
                "/storage/emulated/0/Android/data/com.pocketagent.android/cache/stage2-models",
            )
            modelRoots.forEach { root ->
                add("$root/$fileName")
            }
        }
    }.distinct()
    candidates
        .asSequence()
        .map(::File)
        .firstOrNull(::isReadableRegularFile)
        ?.absolutePath
        ?.let { directPath ->
            Log.i(LOG_TAG, "Directly readable stage2 model path: $directPath")
            return directPath
        }

    val targetContext = instrumentation.targetContext
    val stagingRoot = File(
        targetContext.externalCacheDir ?: targetContext.cacheDir,
        "stage2-models",
    ).apply { mkdirs() }
    val stagedFile = File(stagingRoot, fileName.ifBlank { "stage2-model.gguf" })
    if (isReadableRegularFile(stagedFile)) {
        Log.i(LOG_TAG, "Reusing staged model path: ${stagedFile.absolutePath}")
        return stagedFile.absolutePath
    }
    for (candidate in candidates) {
        val output = runShellCommandWithOutput(
            instrumentation = instrumentation,
            command = buildString {
                append("mkdir -p ").append(shellQuote(stagingRoot.absolutePath))
                append(" && rm -f ").append(shellQuote(stagedFile.absolutePath))
                append(" && cp ")
                append(shellQuote(candidate))
                append(" ")
                append(shellQuote(stagedFile.absolutePath))
                append(" && ls -l ").append(shellQuote(stagedFile.absolutePath))
                append(" && echo __STAGE2_OK__")
            },
        )
        Log.i(
            LOG_TAG,
            "Stage2 candidate copy attempt from $candidate => ${stagedFile.absolutePath}; output=${output.trim()}",
        )
        if (output.contains("__STAGE2_OK__") && isReadableRegularFile(stagedFile)) {
            Log.i(LOG_TAG, "Staged model path ready: ${stagedFile.absolutePath}")
            return stagedFile.absolutePath
        }
    }
    Log.w(LOG_TAG, "Failed to resolve stage2 model path for input=$rawPath candidates=$candidates")
    return null
}

private fun isReadableRegularFile(file: File): Boolean {
    if (!file.exists() || !file.isFile) {
        return false
    }
    return runCatching {
        file.inputStream().use { input -> input.read() }
        true
    }.getOrDefault(false)
}

private fun runShellCommandWithOutput(
    instrumentation: Instrumentation,
    command: String,
): String {
    return runCatching {
        val captured = StringBuilder()
        instrumentation.uiAutomation.executeShellCommand(
            "sh -c ${shellQuote(command)}",
        ).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { stream ->
                val buffer = ByteArray(4096)
                while (true) {
                    val read = stream.read(buffer)
                    if (read == -1) {
                        break
                    }
                    if (read > 0) {
                        captured.append(String(buffer, 0, read))
                    }
                }
            }
        }
        captured.toString()
    }.getOrDefault("")
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

private const val LOG_TAG = "Stage2ModelPathResolver"
