package com.pocketagent.android.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ChatAppDerivedStateAuditTest {
    @Test
    fun `ChatApp does not use bare derivedStateOf`() {
        val source = chatAppSourceFile().readLines()

        val violations = source
            .mapIndexedNotNull { index, line ->
                val trimmed = line.trim()
                val bareDelegated = trimmed.contains(" by derivedStateOf")
                val bareAssigned = trimmed.contains("= derivedStateOf")
                if (bareDelegated || bareAssigned) index + 1 else null
            }

        assertTrue(
            violations.isEmpty(),
            "Wrap derivedStateOf in remember{} in ChatApp.kt. Bare usages on lines: $violations",
        )
    }

    @Test
    fun `PocketAgentApp root does not collect full provisioning ui state`() {
        val source = chatAppSourceFile().readText()
        val rootBody = source.substringAfter("fun PocketAgentApp(")
            .substringBefore("@Composable\nprivate fun ChatComposerDock")

        assertTrue(
            "provisioningViewModel.uiState.collectAsState()" !in rootBody,
            "PocketAgentApp root must observe narrow provisioning flows; full uiState belongs inside visible hosts.",
        )
    }

    @Test
    fun `PocketAgentApp root does not collect download progress ticks`() {
        val source = chatAppSourceFile().readText()
        val rootBody = source.substringAfter("fun PocketAgentApp(")
            .substringBefore("@Composable\nprivate fun ChatComposerDock")

        assertTrue(
            "downloadsFlow.collectAsState()" !in rootBody,
            "PocketAgentApp root must not collect download progress ticks; keep them inside the transition handler or visible download surfaces.",
        )
        assertTrue(
            "downloadsFlow = provisioningViewModel.downloadsFlow" in rootBody,
            "DownloadTransitionHandler should own download progress collection in its isolated composable scope.",
        )
    }

    @Test
    fun `modal host does not collect broad state for all modal surfaces`() {
        val source = chatAppSourceFile().readText()
        val hostBody = source.substringAfter("private fun ModalOrchestratorHost(")
            .substringBefore("@Composable\n@OptIn(ExperimentalMaterial3Api::class)\n@Suppress(\"LongParameterList\")\nprivate fun AdvancedSettingsModalHost")

        assertTrue(
            "viewModel.uiState.collectAsState()" !in hostBody &&
                "provisioningViewModel.uiState.collectAsState()" !in hostBody &&
                "toModelLibraryUiState(" !in hostBody,
            "ModalOrchestratorHost must route to surface-specific hosts instead of collecting broad chat/provisioning/model-library state for every modal.",
        )
    }

    @Test
    fun `advanced settings modal observes narrow settings flows`() {
        val source = chatAppSourceFile().readText()
        val hostBody = source.substringAfter("private fun AdvancedSettingsModalHost(")
            .substringBefore("@Composable\n@OptIn(ExperimentalMaterial3Api::class)\nprivate fun PresetCustomizationModalHost")

        assertTrue(
            "viewModel.defaultThinkingEnabledFlow.collectAsState()" in hostBody &&
                "provisioningViewModel.downloadPreferencesFlow.collectAsState()" in hostBody,
            "Advanced settings should observe only default thinking and download preferences from ViewModels.",
        )
        assertTrue(
            "viewModel.uiState.collectAsState()" !in hostBody &&
                "provisioningViewModel.uiState.collectAsState()" !in hostBody,
            "Advanced settings must not collect full chat/provisioning state.",
        )
    }

    @Test
    fun `onboarding collects download progress only after visibility gate`() {
        val source = chatAppSourceFile().readText()
        val hostBody = source.substringAfter("private fun OnboardingModalHost(")
            .substringBefore("@Composable\nprivate fun RoutingModeSwitchDialog")
        val beforeVisibilityReturn = hostBody.substringBefore("if (activeSurface !is ModalSurface.Onboarding)")
        val betweenGateAndCollect = hostBody.substringAfter("if (activeSurface !is ModalSurface.Onboarding)")
            .substringBefore("provisioningViewModel.downloadsFlow.collectAsState()")

        assertTrue(
            "downloadsFlow.collectAsState()" !in beforeVisibilityReturn && "return" in betweenGateAndCollect,
            "Onboarding may collect download progress only after the Onboarding visibility gate.",
        )
    }

    @Test
    fun `composer auto focus is disabled while chat gate is blocked`() {
        val source = chatAppSourceFile().readText()
        val dockCall = source.substringAfter("ChatComposerDock(")
            .substringBefore("onAttachImage = chatAppLaunchers.launchImageAttachmentPicker")

        assertTrue(
            "chatGateState.status == ChatGateStatus.READY" in dockCall,
            "Composer auto focus must stay disabled while the first-run setup gate is blocking chat.",
        )
    }

    private fun chatAppSourceFile(): File {
        return listOf(
            File("src/main/kotlin/com/pocketagent/android/ui/ChatApp.kt"),
            File("apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ChatApp.kt"),
        ).first { it.exists() }
    }
}
