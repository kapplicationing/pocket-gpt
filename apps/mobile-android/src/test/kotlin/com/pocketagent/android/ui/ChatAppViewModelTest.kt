package com.pocketagent.android.ui

import androidx.lifecycle.SavedStateHandle
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAppViewModelTest {

    @Test
    fun `transient shell flows can be set and cleared`() {
        val viewModel = ChatAppViewModel(SavedStateHandle())
        val version = ModelDistributionVersion(
            modelId = "demo-model",
            version = "v1",
            downloadUrl = "https://example.invalid/model.bin",
            expectedSha256 = "abc123",
            provenanceIssuer = "test",
            provenanceSignature = "sig",
            runtimeCompatibility = "android",
            fileSizeBytes = 1024L,
        )

        val importRequest = requireNotNull(viewModel.requestModelImport("demo-model"))
        viewModel.setPendingGetReadyActivation("demo-model" to "v1")
        viewModel.setPendingMeteredWarningVersion(version)
        viewModel.setPendingNotificationPermissionVersion(version)
        viewModel.setPendingRoutingModeSwitch("demo-model" to "v1")
        viewModel.setLastDownloadTransitionRefreshKey("task:completed")
        val nextSequence = viewModel.incrementReadinessRefreshSequence()

        assertEquals(importRequest, viewModel.modelImportRequest.value)
        assertEquals("demo-model" to "v1", viewModel.pendingGetReadyActivation.value)
        assertEquals(version, viewModel.pendingMeteredWarningVersion.value)
        assertEquals(version, viewModel.pendingNotificationPermissionVersion.value)
        assertEquals("demo-model" to "v1", viewModel.pendingRoutingModeSwitch.value)
        assertEquals("task:completed", viewModel.lastDownloadTransitionRefreshKey.value)
        assertEquals(1L, nextSequence)
        assertEquals(1L, viewModel.readinessRefreshSequence.value)

        val activeImport = requireNotNull(viewModel.consumeModelImportRequest())
        assertFalse(activeImport.pickerPending)
        viewModel.setPendingGetReadyActivation(null)
        viewModel.setPendingMeteredWarningVersion(null)
        viewModel.setPendingNotificationPermissionVersion(null)
        viewModel.setPendingRoutingModeSwitch(null)
        viewModel.setLastDownloadTransitionRefreshKey(null)

        assertNull(viewModel.modelImportRequest.value)
        assertNull(viewModel.pendingGetReadyActivation.value)
        assertNull(viewModel.pendingMeteredWarningVersion.value)
        assertNull(viewModel.pendingNotificationPermissionVersion.value)
        assertNull(viewModel.pendingRoutingModeSwitch.value)
        assertNull(viewModel.lastDownloadTransitionRefreshKey.value)
    }

    @Test
    fun `model import request is single owner and survives only the picker phase`() {
        val savedStateHandle = SavedStateHandle()
        val original = ChatAppViewModel(savedStateHandle)

        val pending = requireNotNull(original.requestModelImport("demo-model"))
        assertNull(original.requestModelImport("another-model"))

        val restoredDuringPicker = ChatAppViewModel(savedStateHandle)
        assertEquals(pending, restoredDuringPicker.modelImportRequest.value)

        val active = requireNotNull(restoredDuringPicker.consumeModelImportRequest())
        assertFalse(active.pickerPending)
        assertNull(restoredDuringPicker.modelImportRequest.value)

        val restoredAfterImportStarted = ChatAppViewModel(savedStateHandle)
        assertNull(restoredAfterImportStarted.modelImportRequest.value)
    }

    @Test
    fun `pending get ready activation survives recreation and clear is durable`() {
        val savedStateHandle = SavedStateHandle()
        val original = ChatAppViewModel(savedStateHandle)

        original.setPendingGetReadyActivation("demo-model" to "v1")

        val restoredWhilePending = ChatAppViewModel(savedStateHandle)
        assertEquals(
            "demo-model" to "v1",
            restoredWhilePending.pendingGetReadyActivation.value,
        )

        restoredWhilePending.setPendingGetReadyActivation(null)

        val restoredAfterClear = ChatAppViewModel(savedStateHandle)
        assertNull(restoredAfterClear.pendingGetReadyActivation.value)
    }

    @Test
    fun `starting a new get ready attempt clears the previous setup failure`() {
        val viewModel = ChatAppViewModel(SavedStateHandle())
        viewModel.setGetReadySetupFailure("Not enough storage")

        viewModel.setPendingGetReadyActivation("demo-model" to "v1")

        assertNull(viewModel.getReadySetupFailure.value)
    }

    @Test
    fun `get ready setup request admits only one immediate start`() {
        val viewModel = ChatAppViewModel(SavedStateHandle())

        assertTrue(viewModel.tryBeginGetReadySetupRequest())
        assertFalse(viewModel.tryBeginGetReadySetupRequest())

        viewModel.finishGetReadySetupRequest()

        assertTrue(viewModel.tryBeginGetReadySetupRequest())
    }

    @Test
    fun `incomplete restored get ready target is discarded`() {
        val savedStateHandle = SavedStateHandle(
            mapOf("pending_get_ready_model_id" to "demo-model"),
        )

        val viewModel = ChatAppViewModel(savedStateHandle)

        assertNull(viewModel.pendingGetReadyActivation.value)
    }
}
