package com.pocketagent.android.runtime

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class MainThreadGuardTest {
    @AfterTest
    fun tearDown() {
        MainThreadGuard.resetForTests()
    }

    @Test
    fun `debug guard throws when operation runs on main thread`() {
        MainThreadGuard.overrideIsMainThreadForTests { true }

        assertFailsWith<IllegalStateException> {
            MainThreadGuard.assertNotMainThread("test operation")
        }
    }

    @Test
    fun `debug guard allows operation off main thread`() {
        MainThreadGuard.overrideIsMainThreadForTests { false }

        MainThreadGuard.assertNotMainThread("test operation")
    }
}
