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

    @Test
    fun `completion settings compose keyed lazy sections around the hot text field`() {
        val source = completionSettingsSheetSource().readText()
        val expectedSections = listOf(
            "completion_reset",
            "completion_system_prompt",
            "completion_common",
            "completion_thinking",
            "completion_advanced",
            "completion_done",
        )

        assertTrue(
            "LazyColumn(" in source && ".verticalScroll(" !in source,
            "Completion settings must not redraw an eager scrolling Column while the system prompt changes.",
        )
        assertTrue(
            expectedSections.all { section -> "key = \"$section\"" in source },
            "Completion settings must keep expensive controls in independently keyed lazy sections.",
        )
    }

    private fun settingsSheetSource(): File = listOf(
        File("src/main/kotlin/com/pocketagent/android/ui/SettingsSheet.kt"),
        File("apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/SettingsSheet.kt"),
    ).first { it.exists() }

    private fun completionSettingsSheetSource(): File = listOf(
        File("src/main/kotlin/com/pocketagent/android/ui/CompletionSettingsSheet.kt"),
        File("apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/CompletionSettingsSheet.kt"),
    ).first { it.exists() }
}
