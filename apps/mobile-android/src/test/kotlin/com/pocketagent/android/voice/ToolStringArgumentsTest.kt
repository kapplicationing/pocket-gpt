package com.pocketagent.android.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ToolStringArgumentsTest {
    @Test
    fun `parses flat string argument objects`() {
        assertEquals(
            mapOf(
                "title" to "Wake up",
                "time_iso" to "2026-04-25T08:00:00",
            ),
            parseToolStringArguments(
                """{"title":"Wake up","time_iso":"2026-04-25T08:00:00"}""",
            ),
        )
    }

    @Test
    fun `rejects non object payloads and nested values while preserving primitive coercion`() {
        assertNull(parseToolStringArguments("""["not","an","object"]"""))
        assertNull(parseToolStringArguments("""{"nested":{"value":"x"}}"""))
        assertEquals(
            mapOf("duration_seconds" to "30"),
            parseToolStringArguments("""{"duration_seconds":30}"""),
        )
    }

    @Test
    fun `rejects malformed json`() {
        assertNull(parseToolStringArguments("""{"title":"""))
    }
}
