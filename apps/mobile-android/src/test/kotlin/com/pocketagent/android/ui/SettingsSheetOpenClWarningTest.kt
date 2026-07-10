package com.pocketagent.android.ui

import com.pocketagent.android.ui.state.RuntimeUiState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsSheetOpenClWarningTest {
    @Test
    fun `shows warning when opencl profile is selected but runtime is demoted to cpu`() {
        val runtime = RuntimeUiState(
            gpuAccelerationEnabled = true,
            activeBackend = "cpu",
            backendProfile = "opencl",
            compiledBackend = "hexagon,opencl",
            activeModelQuantization = "q4_k_m",
        )

        assertTrue(shouldShowOpenClQuantizationWarning(runtime))
    }

    @Test
    fun `does not show warning for opencl-safe quantization`() {
        val runtime = RuntimeUiState(
            gpuAccelerationEnabled = true,
            activeBackend = "opencl",
            backendProfile = "opencl",
            compiledBackend = "opencl",
            activeModelQuantization = "q4_0",
        )

        assertFalse(shouldShowOpenClQuantizationWarning(runtime))
    }

    @Test
    fun `does not show warning for q8_0 quantization`() {
        val runtime = RuntimeUiState(
            gpuAccelerationEnabled = true,
            activeBackend = "opencl",
            backendProfile = "opencl",
            compiledBackend = "opencl",
            activeModelQuantization = "q8_0",
        )

        assertFalse(shouldShowOpenClQuantizationWarning(runtime))
    }

    @Test
    fun `shows warning for q4 k m quantization`() {
        val runtime = RuntimeUiState(
            gpuAccelerationEnabled = true,
            activeBackend = "opencl",
            backendProfile = "opencl",
            compiledBackend = "opencl",
            activeModelQuantization = "q4_k_m",
        )

        assertTrue(shouldShowOpenClQuantizationWarning(runtime))
    }
}
