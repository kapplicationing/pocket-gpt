package com.pocketagent.android

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupPolicyManifestConfigTest {
    @Test
    fun `manifest disables platform backup for local user and runtime data`() {
        val repoRoot = findRepoRoot(File(System.getProperty("user.dir") ?: "."))
        val manifest = File(repoRoot, "apps/mobile-android/src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertFalse(manifest.contains("android:allowBackup=\"true\""))
        assertFalse(manifest.contains("android:fullBackupContent="))
        assertFalse(manifest.contains("android:dataExtractionRules="))
    }

    private fun findRepoRoot(start: File): File {
        var cursor: File? = start.absoluteFile
        while (cursor != null) {
            val hasSettings = File(cursor, "settings.gradle.kts").exists()
            val hasAppModule = File(cursor, "apps/mobile-android").exists()
            if (hasSettings && hasAppModule) {
                return cursor
            }
            cursor = cursor.parentFile
        }
        error("Failed to resolve repository root from ${start.absolutePath}")
    }
}
