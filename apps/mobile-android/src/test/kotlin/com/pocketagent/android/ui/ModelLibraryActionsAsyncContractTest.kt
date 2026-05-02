package com.pocketagent.android.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ModelLibraryActionsAsyncContractTest {
    @Test
    fun `download controls are suspend actions`() {
        val source = modelLibraryActionsSourceFile().readText()
        val requiredSignatures = listOf(
            "suspend fun pauseDownload(",
            "suspend fun resumeDownload(",
            "suspend fun retryDownload(",
            "suspend fun cancelDownload(",
        )

        val missing = requiredSignatures.filterNot(source::contains)

        assertTrue(
            missing.isEmpty(),
            "Model library download controls must remain suspend so Compose event handlers launch them off the click path. Missing: $missing",
        )
    }

    @Test
    fun `download controls use async provisioning view model methods`() {
        val source = modelLibraryActionsSourceFile().readText()
        val requiredCalls = listOf(
            "provisioningViewModel.pauseDownloadAsync(",
            "provisioningViewModel.resumeDownloadAsync(",
            "provisioningViewModel.retryDownloadAsync(",
            "provisioningViewModel.cancelDownloadAsync(",
        )

        val missing = requiredCalls.filterNot(source::contains)

        assertTrue(
            missing.isEmpty(),
            "Model library download controls must call IO-backed ViewModel APIs. Missing: $missing",
        )
    }

    private fun modelLibraryActionsSourceFile(): File {
        return listOf(
            File("src/main/kotlin/com/pocketagent/android/ui/ModelLibraryActions.kt"),
            File("apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ModelLibraryActions.kt"),
        ).first { it.exists() }
    }
}
