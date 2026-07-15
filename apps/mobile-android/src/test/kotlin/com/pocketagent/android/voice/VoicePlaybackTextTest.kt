package com.pocketagent.android.voice

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VoicePlaybackTextTest {
    @Test
    fun `speech text removes markdown noise and omits code blocks`() {
        val result = markdownToSpeechText(
            """
                ## Summary
                Read **the [guide](https://example.com)** and `retry`.

                ```kotlin
                println("not spoken")
                ```
            """.trimIndent(),
        )

        assertEquals("Summary Read the guide and retry. Code block omitted.", result)
    }

    @Test
    fun `long responses split into bounded non-empty chunks`() {
        val chunks = speechChunks(
            markdownText = "First sentence. Second sentence is longer. Third sentence.",
            maxChars = 24,
        )

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.isNotBlank() && it.length <= 24 })
        assertEquals(
            "First sentence. Second sentence is longer. Third sentence.",
            chunks.joinToString(" "),
        )
    }

    @Test
    fun `speech chunk size must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            speechChunks("hello", maxChars = 0)
        }
    }

    @Test
    fun `english voice locale accepts standard and vendor iso3 language codes`() {
        assertTrue(Locale.ENGLISH.isEnglishVoiceLocale())
        assertTrue(Locale("eng", "GBR").isEnglishVoiceLocale())
        assertTrue(!Locale.FRENCH.isEnglishVoiceLocale())
    }
}
