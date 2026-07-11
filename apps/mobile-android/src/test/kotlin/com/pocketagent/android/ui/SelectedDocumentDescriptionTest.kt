package com.pocketagent.android.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelectedDocumentDescriptionTest {
    @Test
    fun `document path uses its final trimmed segment`() {
        assertEquals(
            "Qwen 3.gguf",
            normalizedDocumentDescription(
                documentId = "primary:Download/Qwen 3.gguf  ",
                fallbackPathSegment = "fallback.gguf",
            ),
        )
    }

    @Test
    fun `missing or blank document path falls back to last path segment`() {
        assertEquals(
            "fallback.gguf",
            normalizedDocumentDescription(
                documentId = "primary:Download/   ",
                fallbackPathSegment = "  fallback.gguf  ",
            ),
        )
        assertEquals(
            "fallback.gguf",
            normalizedDocumentDescription(
                documentId = null,
                fallbackPathSegment = "fallback.gguf",
            ),
        )
    }

    @Test
    fun `unsafe document characters are removed and code points are bounded`() {
        val description = normalizedDocumentDescription(
            documentId = "primary:Download/Selected\u202E\nModel " + "😀".repeat(140) + ".gguf",
            fallbackPathSegment = "msf:12345",
        )
        val resolvedDescription = requireNotNull(description)

        assertEquals(120, resolvedDescription.codePointCount(0, resolvedDescription.length))
        assertTrue(resolvedDescription.startsWith("Selected Model "))
        assertFalse(resolvedDescription.contains('\u202E'))
        assertFalse(resolvedDescription.contains('\n'))
        assertTrue(resolvedDescription.endsWith("….gguf"))
    }

    @Test
    fun `opaque ids and legitimate colons are not mistaken for volume prefixes`() {
        assertEquals(
            "provider:report:v1.gguf",
            normalizedDocumentDescription(
                documentId = "provider:report:v1.gguf",
                fallbackPathSegment = null,
            ),
        )
        assertEquals(
            "release:final.gguf",
            normalizedDocumentDescription(
                documentId = "primary:release:final.gguf",
                fallbackPathSegment = null,
            ),
        )
    }

    @Test
    fun `unicode spaces collapse to one safe display separator`() {
        assertEquals(
            "Selected Model.gguf",
            normalizedDocumentDescription(
                documentId = "primary:Selected\u00A0\u2003Model.gguf",
                fallbackPathSegment = null,
            ),
        )
    }

    @Test
    fun `opaque provider identifiers do not become filenames`() {
        assertNull(
            normalizedDocumentDescription(
                documentId = "msf:12345",
                fallbackPathSegment = "msf:12345",
            ),
        )
    }

    @Test
    fun `blank candidates normalize to null`() {
        assertNull(
            normalizedDocumentDescription(
                documentId = "   ",
                fallbackPathSegment = "  ",
            ),
        )
    }
}
