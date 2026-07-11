package com.pocketagent.android.runtime

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeProvisioningImportCopyTest {
    @Test
    fun `copy cancellation removes partial temp file`() {
        val tempDirectory = Files.createTempDirectory("pocketgpt-import-copy").toFile()
        val tempFile = tempDirectory.resolve("model.tmp")
        val importJob = Job()
        val input = object : ByteArrayInputStream(ByteArray(PROVISIONING_COPY_BUFFER_SIZE_BYTES * 2)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                return super.read(buffer, offset, length).also { read ->
                    if (read > 0) {
                        importJob.cancel()
                    }
                }
            }
        }

        try {
            assertFailsWith<CancellationException> {
                copyModelInputToTempFile(
                    input = input,
                    tempFile = tempFile,
                    digest = MessageDigest.getInstance("SHA-256"),
                    importContext = importJob,
                )
            }
            assertFalse(tempFile.exists())
        } finally {
            tempDirectory.deleteRecursively()
        }
    }

    @Test
    fun `successful copy returns bytes and updates digest`() {
        val tempDirectory = Files.createTempDirectory("pocketgpt-import-copy").toFile()
        val tempFile = tempDirectory.resolve("model.tmp")
        val content = "model-content".encodeToByteArray()
        val digest = MessageDigest.getInstance("SHA-256")

        try {
            val copiedBytes = copyModelInputToTempFile(
                input = ByteArrayInputStream(content),
                tempFile = tempFile,
                digest = digest,
                importContext = Job(),
            )

            assertEquals(content.size.toLong(), copiedBytes)
            assertEquals(content.toList(), tempFile.readBytes().toList())
            assertEquals(
                MessageDigest.getInstance("SHA-256").digest(content).toList(),
                digest.digest().toList(),
            )
        } finally {
            tempDirectory.deleteRecursively()
        }
    }

    @Test
    fun `cancellation closes a blocked source and removes partial temp`() = runBlocking {
        val tempDirectory = Files.createTempDirectory("pocketgpt-import-blocked").toFile()
        val tempFile = tempDirectory.resolve("model.tmp")
        val source = CloseUnblockedInputStream()

        try {
            val copy = async(Dispatchers.IO) {
                copyModelInputToTempFileCancellable(
                    input = source,
                    tempFile = tempFile,
                    digest = MessageDigest.getInstance("SHA-256"),
                )
            }
            assertTrue(source.readStarted.await(5, TimeUnit.SECONDS))

            withTimeout(5_000L) {
                copy.cancelAndJoin()
            }

            assertTrue(source.closed.get())
            assertFalse(tempFile.exists())
        } finally {
            tempDirectory.deleteRecursively()
        }
    }
}

private class CloseUnblockedInputStream : InputStream() {
    val readStarted = CountDownLatch(1)
    val closed = AtomicBoolean(false)
    private val closeSignal = CountDownLatch(1)

    override fun read(): Int {
        readStarted.countDown()
        closeSignal.await()
        throw IOException("closed")
    }

    override fun close() {
        closed.set(true)
        closeSignal.countDown()
    }
}
