package com.pocketagent.android.audit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class SettingsSheetPerformanceContractTest {
    @Test
    fun `general settings compose keyed lazy sections instead of one eager scrolling column`() {
        val source = settingsSheetSource().readText()
        val generalTab = source.substringAfter("private fun GeneralTabContent(")
            .substringBefore("@Composable\nprivate fun ModelTabContent(")
        val expectedSections = listOf(
            "settings_general_performance",
            "settings_general_downloads",
            "settings_general_keep_alive",
            "settings_general_voice",
            "settings_general_reasoning",
        )

        assertTrue(
            "LazyColumn(" in generalTab && ".verticalScroll(" !in generalTab,
            "General settings must use a real LazyColumn; an eager scrolling Column composes every control on open.",
        )
        assertTrue(
            expectedSections.all { section -> "key = \"$section\"" in generalTab },
            "General settings sections must stay independently keyed so opening the tab does not compose all controls.",
        )
    }

    private fun settingsSheetSource(): File = listOf(
        File("src/main/kotlin/com/pocketagent/android/ui/SettingsSheet.kt"),
        File("apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/SettingsSheet.kt"),
    ).first { it.exists() }
}
