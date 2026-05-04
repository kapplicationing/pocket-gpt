package com.pocketagent.android.runtime

import android.os.Looper
import com.pocketagent.android.BuildConfig

object MainThreadGuard {
    @Volatile
    private var isMainThreadOverride: (() -> Boolean)? = null

    internal fun isMainThread(): Boolean {
        return isMainThreadOverride?.invoke()
            ?: (Looper.getMainLooper().thread === Thread.currentThread())
    }

    fun assertNotMainThread(operationName: String) {
        if (!BuildConfig.DEBUG) {
            return
        }
        check(!isMainThread()) {
            "PocketGPT performance contract violated: $operationName ran on the main thread. " +
                "Move the call to Dispatchers.IO or the app warmup path."
        }
    }

    internal fun overrideIsMainThreadForTests(isMainThread: () -> Boolean) {
        isMainThreadOverride = isMainThread
    }

    internal fun resetForTests() {
        isMainThreadOverride = null
    }
}
