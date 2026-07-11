package com.pocketagent.android

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupPolicyManifestConfigTest {
    @Test
    fun `manifest disables cloud backup and excludes supported transfer domains`() {
        val repoRoot = findRepoRoot(File(System.getProperty("user.dir") ?: "."))
        val manifest = File(repoRoot, "apps/mobile-android/src/main/AndroidManifest.xml").readText()
        val extractionRules = File(
            repoRoot,
            "apps/mobile-android/src/main/res/xml/data_extraction_rules.xml",
        ).readText()

        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertFalse(manifest.contains("android:allowBackup=\"true\""))
        assertFalse(manifest.contains("android:fullBackupContent="))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(extractionRules.contains("<cloud-backup>"))
        assertTrue(extractionRules.contains("<device-transfer>"))
        assertFalse(extractionRules.contains("<include"))

        val domains = listOf(
            "root",
            "file",
            "database",
            "sharedpref",
            "external",
            "device_root",
            "device_file",
            "device_database",
            "device_sharedpref",
        )
        domains.forEach { domain ->
            val exclusion = Regex("""<exclude domain="$domain" path="\." />""")
            assertEquals(
                expected = 2,
                actual = exclusion.findAll(extractionRules).count(),
                message = "Expected $domain to be excluded from cloud backup and device transfer",
            )
        }
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
