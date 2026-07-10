package com.pocketagent.android.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuntimeDiagnosticsSnapshotParserTest {
    @Test
    fun `parser extracts backend profile and compiled backends from diagnostics`() {
        val diagnostics = """
            diag=ok
            GPU_OFFLOAD|runtime_supported=true|device_feature_advisory_supported=true|probe_status=QUALIFIED|probe_layers=8|probe_reason=none|probe_source=runtime_plus_probe|probe_detail=ok
            GPU_PROBE|status=QUALIFIED|max_layers=8|reason=none|detail=ok|cache_key=abc|qualification_state=probe_qualified|compiled_backends=hexagon,opencl|registered_backend_count=2|registered_backends=cpu,opencl|opencl_icd_source=android_vendor_lib|opencl_icd_filenames=/vendor/lib64/libOpenCL.so|discovered_backends=opencl|active_backend=opencl|flash_attn_feature_state=guarded|quantized_kv_feature_state=guarded|native_backend_payload={"compiled_backend":"hexagon,opencl","backend_profile":"opencl","active_backend":"opencl","runtime_supported":true,"strict_accelerator_fail_fast":true,"auto_backend_cpu_fallback":false,"registered_backend_count":2,"registered_backends":"cpu,opencl","opencl_icd_source":"android_vendor_lib","opencl_icd_filenames":"/vendor/lib64/libOpenCL.so","opencl_device_version":"2.0","opencl_adreno_generation":7,"opencl_device_count":1,"hexagon_device_count":0,"requested_model_quantization":"q4_0","active_model_quantization":"q4_0","model_memory_mode":"hybrid","prefix_cache_mode":"prefill_only","flash_attn_guard_reason":"opencl_backend","quantized_kv_guard_reason":"opencl_backend","last_mmap_readahead_label":"target","last_mmap_readahead_bytes":396705472,"last_mmap_readahead_result":0,"last_mmap_readahead_ms":187,"mmap_readahead_count":1}
        """.trimIndent()

        val snapshot = RuntimeDiagnosticsSnapshotParser.parse(diagnostics)

        assertEquals("opencl", snapshot.backendProfile)
        assertEquals("hexagon,opencl", snapshot.compiledBackend)
        assertEquals(listOf("hexagon", "opencl"), snapshot.compiledBackends)
        assertEquals(2, snapshot.registeredBackendCount)
        assertEquals(listOf("cpu", "opencl"), snapshot.registeredBackends)
        assertEquals("/vendor/lib64/libOpenCL.so", snapshot.openclIcdFilenames)
        assertEquals("android_vendor_lib", snapshot.openclIcdSource)
        assertEquals(listOf("opencl"), snapshot.discoveredBackends)
        assertEquals("opencl", snapshot.activeBackend)
        assertEquals(BackendQualificationState.PROBE_QUALIFIED, snapshot.backendQualificationState)
        assertEquals(true, snapshot.nativeRuntimeSupported)
        assertEquals(true, snapshot.strictAcceleratorFailFast)
        assertEquals(false, snapshot.autoBackendCpuFallback)
        assertEquals("2.0", snapshot.openclDeviceVersion)
        assertEquals(7, snapshot.openclAdrenoGeneration)
        assertEquals("q4_0", snapshot.requestedModelQuantization)
        assertEquals("q4_0", snapshot.activeModelQuantization)
        assertEquals("hybrid", snapshot.modelMemoryMode)
        assertEquals("prefill_only", snapshot.prefixCacheMode)
        assertEquals("target", snapshot.lastMmapReadaheadLabel)
        assertEquals(396705472L, snapshot.lastMmapReadaheadBytes)
        assertEquals(0, snapshot.lastMmapReadaheadResult)
        assertEquals(187L, snapshot.lastMmapReadaheadMs)
        assertEquals(1L, snapshot.mmapReadaheadCount)
        assertEquals(BackendFeatureQualificationState.GUARDED, snapshot.flashAttnQualificationState)
        assertEquals(BackendFeatureQualificationState.GUARDED, snapshot.quantizedKvQualificationState)
        assertEquals("opencl_backend", snapshot.flashAttnGuardReason)
        assertEquals("opencl_backend", snapshot.quantizedKvGuardReason)
        assertEquals(3, snapshot.backendCapabilities.size)
        assertTrue(snapshot.backendCapabilities.any { capability ->
            capability.backend == "opencl" &&
                capability.family == RuntimeBackendFamily.OPENCL &&
                capability.compiled &&
                capability.registered &&
                capability.discovered == true &&
                capability.active &&
                capability.deviceCount == 1 &&
                capability.runtimeAvailable == true &&
                capability.qualified == true
        })
        assertTrue(snapshot.backendCapabilities.any { capability ->
            capability.backend == "hexagon" &&
                capability.family == RuntimeBackendFamily.HEXAGON &&
                capability.compiled &&
                !capability.registered &&
                capability.discovered == false &&
                !capability.active &&
                capability.deviceCount == 0 &&
                capability.runtimeAvailable == false
        })
        assertEquals(true, snapshot.backendCapability(RuntimeBackendFamily.OPENCL)?.qualified)
        assertEquals(true, snapshot.backendCapability(RuntimeBackendFamily.CPU)?.registered)
    }

    @Test
    fun `parser tolerates missing diagnostics lines`() {
        val snapshot = RuntimeDiagnosticsSnapshotParser.parse("diag=ok")

        assertNull(snapshot.backendProfile)
        assertNull(snapshot.compiledBackend)
        assertNull(snapshot.nativeRuntimeSupported)
        assertNull(snapshot.strictAcceleratorFailFast)
    }

    @Test
    fun `parser resolves runtime unsupported and unavailable feature states`() {
        val diagnostics = """
            diag=ok
            GPU_OFFLOAD|runtime_supported=false|probe_status=FAILED|probe_reason=RUNTIME_UNSUPPORTED|probe_detail=unsupported
            GPU_PROBE|status=FAILED|max_layers=0|reason=RUNTIME_UNSUPPORTED|detail=unsupported|cache_key=abc|active_backend=cpu|native_backend_payload={"compiled_backend":"cpu","active_backend":"cpu","runtime_supported":false}
        """.trimIndent()

        val snapshot = RuntimeDiagnosticsSnapshotParser.parse(diagnostics)

        assertEquals(BackendQualificationState.RUNTIME_UNSUPPORTED, snapshot.backendQualificationState)
        assertEquals(BackendFeatureQualificationState.UNAVAILABLE, snapshot.flashAttnQualificationState)
        assertEquals(BackendFeatureQualificationState.UNAVAILABLE, snapshot.quantizedKvQualificationState)
        assertEquals(listOf("cpu"), snapshot.compiledBackends)
        assertEquals("cpu", snapshot.activeBackend)
        assertEquals(1, snapshot.backendCapabilities.size)
        assertTrue(snapshot.backendCapabilities.single().active)
    }
}
