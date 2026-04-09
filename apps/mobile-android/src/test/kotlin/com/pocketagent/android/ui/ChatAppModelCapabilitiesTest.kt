package com.pocketagent.android.ui

import com.pocketagent.inference.ModelCatalog
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatAppModelCapabilitiesTest {
    @Test
    fun `vision capable models can attach images`() {
        assertTrue(canAttachImagesForModel(ModelCatalog.QWEN_3_5_0_8B_Q4))
    }

    @Test
    fun `text only and null models cannot attach images`() {
        assertFalse(canAttachImagesForModel(ModelCatalog.LLAMA_3_2_1B_Q4_K_M))
        assertFalse(canAttachImagesForModel(null))
    }
}
