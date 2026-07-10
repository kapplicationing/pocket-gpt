package com.pocketagent.nativebridge

import kotlin.test.Test
import kotlin.test.assertEquals

class OpenClRuntimePolicyTest {
    @Test
    fun `q4 0 version is release supported`() {
        assertEquals(
            OpenClQuantCompatibility.SUPPORTED,
            OpenClRuntimePolicy.releaseQuantCompatibility(
                modelPath = "/tmp/model.gguf",
                modelId = "model",
                modelVersion = "q4_0",
            ),
        )
    }

    @Test
    fun `q6 k filename is release supported`() {
        assertEquals(
            OpenClQuantCompatibility.SUPPORTED,
            OpenClRuntimePolicy.releaseQuantCompatibility(
                modelPath = "/data/models/model-q6_k.gguf",
                modelId = "model",
                modelVersion = null,
            ),
        )
    }

    @Test
    fun `known unsupported quantization is rejected`() {
        assertEquals(
            OpenClQuantCompatibility.UNSUPPORTED,
            OpenClRuntimePolicy.releaseQuantCompatibility(
                modelPath = "/tmp/model.gguf",
                modelId = "model",
                modelVersion = "q8_0",
            ),
        )
    }

    @Test
    fun `unknown quantization remains experimental`() {
        assertEquals(
            OpenClQuantCompatibility.EXPERIMENTAL,
            OpenClRuntimePolicy.releaseQuantCompatibility(
                modelPath = "/tmp/model.gguf",
                modelId = "model",
                modelVersion = null,
            ),
        )
    }
}
