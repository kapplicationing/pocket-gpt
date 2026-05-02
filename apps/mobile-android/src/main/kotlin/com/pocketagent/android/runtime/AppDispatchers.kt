package com.pocketagent.android.runtime

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Central dispatcher contract for Android app orchestration.
 *
 * UI-facing code should depend on this small holder instead of importing
 * Dispatchers directly. That keeps thread ownership explicit and makes tests
 * inject one coherent dispatcher set.
 */
class AppDispatchers(
    val main: CoroutineDispatcher = Dispatchers.Main,
    val mainImmediate: CoroutineDispatcher = Dispatchers.Main.immediate,
    val default: CoroutineDispatcher = Dispatchers.Default,
    val io: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        val DEFAULT = AppDispatchers()
    }
}
