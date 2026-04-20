package com.pocketagent.android.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceActivationTest {
    @Test
    fun `maps samsung manufacturer to samsung guide`() {
        assertEquals(OemBatteryGuide.SAMSUNG, OemBatteryGuide.fromManufacturer("Samsung"))
    }

    @Test
    fun `maps poco manufacturer to xiaomi guide`() {
        assertEquals(OemBatteryGuide.XIAOMI, OemBatteryGuide.fromManufacturer("POCO"))
    }

    @Test
    fun `falls back to generic guide`() {
        assertEquals(OemBatteryGuide.GENERIC, OemBatteryGuide.fromManufacturer("Google"))
    }

    @Test
    fun `normalizes pcm samples into float chunk`() {
        val chunk = shortArrayOf(0, 16384, -16384, 32767).toFloatChunk()
        assertEquals(0f, chunk[0])
        assertTrue(chunk[1] > 0.49f && chunk[1] < 0.51f)
        assertTrue(chunk[2] < -0.49f && chunk[2] > -0.51f)
        assertTrue(chunk[3] > 0.99f)
    }

    @Test
    fun `computes rms for audio chunk`() {
        val rms = floatArrayOf(1f, -1f, 1f, -1f).rms()
        assertEquals(1f, rms)
    }
}
