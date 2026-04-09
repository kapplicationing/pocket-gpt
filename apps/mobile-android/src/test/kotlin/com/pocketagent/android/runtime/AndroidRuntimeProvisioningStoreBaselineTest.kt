package com.pocketagent.android.runtime

import com.pocketagent.inference.ModelCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidRuntimeProvisioningStoreBaselineTest {
    @Test
    fun `baseline model ids are catalog descriptor driven`() {
        val expected = ModelCatalog.modelDescriptors()
            .filter { descriptor -> descriptor.bridgeSupported || descriptor.startupCandidate }
            .map { descriptor -> descriptor.modelId }
            .toSet()

        assertEquals(expected, AndroidRuntimeProvisioningStore.baselineModelIdsForTesting())
    }

    @Test
    fun `legacy qwen migration keys remain stable`() {
        assertEquals(
            "model_0_8b_path",
            AndroidRuntimeProvisioningStore.legacyPathKeyForTesting(ModelCatalog.QWEN_3_5_0_8B_Q4),
        )
        assertTrue(
            AndroidRuntimeProvisioningStore.legacyPathKeyForTesting(ModelCatalog.QWEN3_0_6B_Q4_K_M)
                ?.startsWith("legacy_path_cat_") == true,
        )
        assertTrue(
            AndroidRuntimeProvisioningStore.legacyPathKeyForTesting(ModelCatalog.QWEN3_1_7B_Q4_K_M)
                ?.startsWith("legacy_path_cat_") == true,
        )
    }
}
