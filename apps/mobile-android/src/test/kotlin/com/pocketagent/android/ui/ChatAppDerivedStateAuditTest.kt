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
    fun `modal host gates full state collection behind visible modal check`() {
        val source = chatAppSourceFile().readText()
        val hostBody = source.substringAfter("private fun ModalOrchestratorHost(")
            .substringBefore("\ninternal fun canAttachImagesForModel")
        val guardIndex = hostBody.indexOf("activeSurface == ModalSurface.None")
        val fullChatStateIndex = hostBody.indexOf("viewModel.uiState.collectAsState()")
        val fullProvisioningStateIndex = hostBody.indexOf("provisioningViewModel.uiState.collectAsState()")

        assertTrue(guardIndex >= 0, "ModalOrchestratorHost must keep its early return guard.")
        assertTrue(
            fullChatStateIndex > guardIndex && fullProvisioningStateIndex > guardIndex,
            "Full chat/provisioning state collection must happen only after ModalOrchestratorHost decides it is visible.",
        )
    }

    private fun chatAppSourceFile(): File {
        return listOf(
            File("src/main/kotlin/com/pocketagent/android/ui/ChatApp.kt"),
            File("apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ChatApp.kt"),
        ).first { it.exists() }
    }
}
