package com.pocketagent.android.runtime

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.test.Test
import kotlin.test.assertSame

class AppDispatchersTest {
    @Test
    fun `dispatcher holder exposes one injectable IO owner`() {
        val io = StandardTestDispatcher()
        val default = StandardTestDispatcher()
        val dispatchers = AppDispatchers(
            main = default,
            mainImmediate = default,
            default = default,
            io = io,
        )

        assertSame(io, dispatchers.io)
        assertSame(default, dispatchers.default)
    }
}
