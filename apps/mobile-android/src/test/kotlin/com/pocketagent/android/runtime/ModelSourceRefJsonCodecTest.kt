package com.pocketagent.android.runtime

import com.pocketagent.core.model.ModelSourceKind
import com.pocketagent.core.model.ModelSourceRef
import com.pocketagent.core.model.SourceTrustPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModelSourceRefJsonCodecTest {
    @Test
    fun `source ref round trips all provenance fields`() {
        val sourceRef = ModelSourceRef(
            kind = ModelSourceKind.HUGGING_FACE,
            originId = "qwen3-0.6b-q4_k_m",
            publisher = "owner",
            repository = "owner/repo",
            trustPolicy = SourceTrustPolicy.INTEGRITY_ONLY,
            revision = "main",
            originUrl = "https://huggingface.co/owner/repo/resolve/main/model.gguf",
        )

        val encoded = ModelSourceRefJsonCodec.encode(sourceRef)
        val decoded = ModelSourceRefJsonCodec.decode(encoded)

        assertEquals(sourceRef, decoded)
        assertEquals(sourceRef, ModelSourceRefJsonCodec.decode(encoded.toString()))
    }

    @Test
    fun `source ref decoder treats blank malformed and unknown kind as absent`() {
        assertNull(ModelSourceRefJsonCodec.decode(""))
        assertNull(ModelSourceRefJsonCodec.decode("{not-json"))
        assertNull(ModelSourceRefJsonCodec.decode("""{"kind":"NOPE"}"""))
    }

    @Test
    fun `source ref decoder defaults unknown trust policy`() {
        val decoded = ModelSourceRefJsonCodec.decode(
            """
            {
              "kind": "HUGGING_FACE",
              "repository": "owner/repo",
              "trustPolicy": "NOT_A_POLICY"
            }
            """.trimIndent(),
        )

        assertEquals(SourceTrustPolicy.UNKNOWN, decoded?.trustPolicy)
        assertEquals("owner/repo", decoded?.repository)
    }
}
