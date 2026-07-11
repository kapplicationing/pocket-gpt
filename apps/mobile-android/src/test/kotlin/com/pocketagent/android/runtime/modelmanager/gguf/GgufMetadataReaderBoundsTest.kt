package com.pocketagent.android.runtime.modelmanager.gguf

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.lang.Long.reverseBytes
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GgufMetadataReaderBoundsTest {
    private val reader = GgufMetadataReaderImpl(
        skipKeys = emptySet(),
        arraySummariseThreshold = 64,
    )

    @Test
    fun `rejects metadata entry counts that cannot be processed safely`() {
        val error = assertFailsWith<IOException> {
            reader.readStructuredMetadata(
                ggufHeader(tensorCount = 0, metadataCount = 100_001),
            )
        }

        assertTrue(error.message.orEmpty().contains("metadata entry count"))
    }

    @Test
    fun `rejects negative tensor counts encoded outside supported range`() {
        val error = assertFailsWith<IOException> {
            reader.readStructuredMetadata(
                ggufHeader(tensorCount = -1, metadataCount = 0),
            )
        }

        assertTrue(error.message.orEmpty().contains("tensor count"))
    }

    @Test
    fun `rejects oversized metadata keys before allocating them`() {
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.write(byteArrayOf(0x47, 0x47, 0x55, 0x46))
                data.writeInt(Integer.reverseBytes(3))
                data.writeLong(reverseBytes(0L))
                data.writeLong(reverseBytes(1L))
                data.writeLong(reverseBytes(65_537L))
            }
        }.toByteArray()

        val error = assertFailsWith<IOException> {
            reader.readStructuredMetadata(ByteArrayInputStream(bytes))
        }

        assertTrue(error.message.orEmpty().contains("metadata key length"))
    }

    @Test
    fun `rejects oversized arrays before iterating their declared elements`() {
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.write(byteArrayOf(0x47, 0x47, 0x55, 0x46))
                data.writeInt(Integer.reverseBytes(3))
                data.writeLong(reverseBytes(0L))
                data.writeLong(reverseBytes(1L))
                data.writeLong(reverseBytes(1L))
                data.writeByte('x'.code)
                data.writeInt(Integer.reverseBytes(9))
                data.writeInt(Integer.reverseBytes(0))
                data.writeLong(reverseBytes(10_000_001L))
            }
        }.toByteArray()

        val error = assertFailsWith<IOException> {
            reader.readStructuredMetadata(ByteArrayInputStream(bytes))
        }

        assertTrue(error.message.orEmpty().contains("metadata array length"))
    }

    @Test
    fun `validated model structure rejects metadata-only gguf`() {
        val error = assertFailsWith<IOException> {
            reader.readValidatedModelStructure(
                input = ggufHeader(tensorCount = 0, metadataCount = 0),
                fileSize = 24L,
            )
        }

        assertTrue(error.message.orEmpty().contains("at least one tensor"))
    }

    @Test
    fun `validated model structure rejects truncated tensor data`() {
        val truncated = oneTensorGguf(payloadBytes = 3)

        val error = assertFailsWith<IOException> {
            reader.readValidatedModelStructure(
                input = ByteArrayInputStream(truncated),
                fileSize = truncated.size.toLong(),
            )
        }

        assertTrue(error.message.orEmpty().contains("tensor data is truncated"))
    }

    @Test
    fun `validated model structure accepts complete tensor table and data`() {
        val valid = oneTensorGguf(payloadBytes = 4)

        val metadata = reader.readValidatedModelStructure(
            input = ByteArrayInputStream(valid),
            fileSize = valid.size.toLong(),
        )

        assertTrue(metadata.tensorCount == 1L)
    }

    private fun ggufHeader(tensorCount: Long, metadataCount: Long): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.write(byteArrayOf(0x47, 0x47, 0x55, 0x46))
                data.writeInt(Integer.reverseBytes(3))
                data.writeLong(reverseBytes(tensorCount))
                data.writeLong(reverseBytes(metadataCount))
            }
        }.toByteArray()
        return ByteArrayInputStream(bytes)
    }

    private fun oneTensorGguf(payloadBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.write(byteArrayOf(0x47, 0x47, 0x55, 0x46))
            data.writeInt(Integer.reverseBytes(3))
            data.writeLong(reverseBytes(1L))
            data.writeLong(reverseBytes(0L))
            val tensorName = "weight".encodeToByteArray()
            data.writeLong(reverseBytes(tensorName.size.toLong()))
            data.write(tensorName)
            data.writeInt(Integer.reverseBytes(1))
            data.writeLong(reverseBytes(1L))
            data.writeInt(Integer.reverseBytes(0))
            data.writeLong(reverseBytes(0L))
            while (output.size() % 32 != 0) {
                data.writeByte(0)
            }
            repeat(payloadBytes) { data.writeByte(0) }
        }
        return output.toByteArray()
    }
}
