package com.pocketagent.android.runtime

import com.pocketagent.android.BuildConfig
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class MainThreadGuardTest {
    @AfterTest
    fun tearDown() {
        MainThreadGuard.resetForTests()
    }

    @Test
    fun `guard enforcement matches build type on main thread`() {
        MainThreadGuard.overrideIsMainThreadForTests { true }

        if (BuildConfig.DEBUG) {
            assertFailsWith<IllegalStateException> {
                MainThreadGuard.assertNotMainThread("test operation")
            }
        } else {
            MainThreadGuard.assertNotMainThread("test operation")
        }
    }

    @Test
    fun `debug guard allows operation off main thread`() {
        MainThreadGuard.overrideIsMainThreadForTests { false }

        MainThreadGuard.assertNotMainThread("test operation")
    }
}
