package com.pocketagent.android.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class LaunchScopeImagePolicyTest {
    @Test
    fun `keeps only the first non blank image path`() {
        assertEquals(
            listOf("/tmp/first.png"),
            launchSafeSingleImagePaths(
                imagePaths = listOf("  /tmp/first.png  ", "/tmp/second.png", ""),
            ),
        )
    }

    @Test
    fun `falls back to legacy image path when the list is empty`() {
        assertEquals(
            listOf("/tmp/legacy.png"),
            launchSafeSingleImagePaths(
                imagePaths = emptyList(),
                legacyImagePath = " /tmp/legacy.png ",
            ),
        )
    }

    @Test
    fun `returns empty when no usable image path exists`() {
        assertEquals(
            emptyList(),
            launchSafeSingleImagePaths(
                imagePaths = listOf("", " "),
                legacyImagePath = " ",
            ),
        )
    }
}
