package com.pocketagent.android.runtime

import android.content.Context
import com.pocketagent.nativebridge.LlamaCppRuntimeBridge
import com.pocketagent.nativebridge.NativeJniLlamaCppBridge

private const val ANDROID_RUNTIME_MODE_ENV = "POCKETGPT_ANDROID_RUNTIME_MODE"
private const val ANDROID_RUNTIME_MODE_REMOTE = "remote"
private const val ANDROID_RUNTIME_MODE_IN_PROCESS = "in_process"

fun createDefaultAndroidRuntimeBridge(context: Context): LlamaCppRuntimeBridge {
    return when (resolveAndroidRuntimeMode()) {
        ANDROID_RUNTIME_MODE_IN_PROCESS -> NativeJniLlamaCppBridge()
        else -> RemoteLlamaCppRuntimeBridge(context.applicationContext)
    }
}

fun createDefaultAndroidInferenceModule(
    context: Context,
): com.pocketagent.nativebridge.LlamaCppInferenceModule {
    return com.pocketagent.nativebridge.LlamaCppInferenceModule(
        runtimeBridge = createDefaultAndroidRuntimeBridge(context.applicationContext),
    )
}

internal fun resolveAndroidRuntimeMode(
    environment: Map<String, String> = System.getenv(),
): String {
    val defaultMode = ANDROID_RUNTIME_MODE_IN_PROCESS
    return environment[ANDROID_RUNTIME_MODE_ENV]
        ?.trim()
        ?.lowercase()
        ?.takeIf { it == ANDROID_RUNTIME_MODE_IN_PROCESS || it == ANDROID_RUNTIME_MODE_REMOTE }
        ?: defaultMode
}
