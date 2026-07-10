package com.pocketagent.nativebridge

import kotlin.test.Test
import kotlin.test.assertEquals

class KvCachePresetTest {
    @Test
    fun `all retained presets have unique ordered codes`() {
        val codes = KvCachePreset.entries.map { it.code }

        assertEquals(listOf(0, 1, 2), codes)
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun `fromCode unknown falls back to SAFE`() {
        assertEquals(KvCachePreset.SAFE, KvCachePreset.fromCode(99))
    }
}
