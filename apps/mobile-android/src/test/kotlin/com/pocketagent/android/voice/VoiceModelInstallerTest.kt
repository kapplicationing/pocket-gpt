package com.pocketagent.android.voice

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream

class VoiceModelInstallerTest {
    @Test
    fun `durable setup exposes queued and cancelled states`() {
        val content = "model".encodeToByteArray()
        val archive = tarBzip("kws/encoder.onnx", content)
        val parent = createTempDirectory("voice-installer-state").toFile()
        val installer = VoiceModelInstaller(
            installRoot = parent.resolve("models"),
            cacheRoot = parent.resolve("cache"),
            manifest = manifest(
                directUrl = "https://example.invalid/asr",
                archiveUrl = "https://example.invalid/kws",
                directContent = content,
                archiveContent = archive,
                kwsContent = content,
            ),
            logger = SilentVoiceModelSetupLogger,
        )

        installer.markQueued()
        assertEquals(VoiceModelSetupPhase.QUEUED, installer.observe().value.phase)

        installer.markCancelled()
        assertEquals(VoiceModelSetupPhase.IDLE, installer.observe().value.phase)
    }

    @Test
    fun `downloads verifies and atomically publishes voice models`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val directContent = "small asr model".encodeToByteArray()
            val kwsContent = "small kws model".encodeToByteArray()
            val archiveContent = tarBzip("kws/encoder.onnx", kwsContent)
            server.enqueue(bytesResponse(directContent))
            server.enqueue(bytesResponse(archiveContent))
            val manifest = manifest(
                directUrl = server.url("/asr").toString(),
                archiveUrl = server.url("/kws").toString(),
                directContent = directContent,
                archiveContent = archiveContent,
                kwsContent = kwsContent,
            )
            val parent = createTempDirectory("voice-installer").toFile()
            val installRoot = parent.resolve("offas-voice-models").apply {
                mkdirs()
                resolve("old-marker").writeText("old")
            }
            val installer = VoiceModelInstaller(
                installRoot = installRoot,
                cacheRoot = parent.resolve("cache"),
                client = serverClient(),
                manifest = manifest,
                logger = SilentVoiceModelSetupLogger,
            )

            installer.install().getOrThrow()

            assertEquals("small asr model", installRoot.resolve("asr/model.onnx").readText())
            assertEquals("small kws model", installRoot.resolve("kws/encoder.onnx").readText())
            assertTrue(hasDedicatedOffasKeyword(installRoot.resolve("kws/keywords.txt").readText()))
            assertTrue(installRoot.resolve(VoiceModelInstaller.INSTALL_MARKER_FILE).isFile)
            assertFalse(installRoot.resolve("old-marker").exists())
            assertEquals(VoiceModelSetupPhase.READY, installer.observe().value.phase)
            assertEquals(100, installer.observe().value.progressPercent)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `integrity failure preserves the previously installed models`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val corrupt = "corrupt".encodeToByteArray()
            repeat(3) { server.enqueue(bytesResponse(corrupt)) }
            val kwsContent = "kws".encodeToByteArray()
            val archiveContent = tarBzip("kws/encoder.onnx", kwsContent)
            val expectedDirect = "expected".encodeToByteArray()
            val manifest = manifest(
                directUrl = server.url("/asr").toString(),
                archiveUrl = server.url("/kws").toString(),
                directContent = expectedDirect,
                archiveContent = archiveContent,
                kwsContent = kwsContent,
                directDownloadBytes = corrupt.size.toLong(),
            )
            val parent = createTempDirectory("voice-installer-failure").toFile()
            val installRoot = parent.resolve("offas-voice-models").apply {
                mkdirs()
                resolve("old-marker").writeText("keep")
            }
            val staleStaging = parent.resolve("offas-voice-models.installing-interrupted").apply { mkdirs() }
            val staleArchive = parent.resolve("cache/kws-interrupted.tar.bz2").apply {
                requireNotNull(parentFile).mkdirs()
                writeText("partial")
            }
            val installer = VoiceModelInstaller(
                installRoot = installRoot,
                cacheRoot = parent.resolve("cache"),
                client = serverClient(),
                manifest = manifest,
                logger = SilentVoiceModelSetupLogger,
            )

            assertTrue(installer.install().isFailure)

            assertEquals("keep", installRoot.resolve("old-marker").readText())
            assertFalse(staleStaging.exists())
            assertFalse(staleArchive.exists())
            assertEquals(VoiceModelSetupPhase.FAILED, installer.observe().value.phase)
            assertTrue(installer.observe().value.error.orEmpty().contains("integrity"))
        } finally {
            server.shutdown()
        }
    }

    private fun manifest(
        directUrl: String,
        archiveUrl: String,
        directContent: ByteArray,
        archiveContent: ByteArray,
        kwsContent: ByteArray,
        directDownloadBytes: Long = directContent.size.toLong(),
    ): VoiceModelInstallManifest {
        return VoiceModelInstallManifest(
            directFiles = listOf(
                VoiceModelFile(
                    directory = "asr",
                    fileName = "model.onnx",
                    sha256 = directContent.sha256(),
                    url = directUrl,
                    downloadBytes = directDownloadBytes,
                ),
            ),
            archive = VoiceModelArchive(
                url = archiveUrl,
                sha256 = archiveContent.sha256(),
                downloadBytes = archiveContent.size.toLong(),
                rootDirectory = "kws",
                files = listOf(
                    VoiceModelFile(
                        directory = "kws",
                        fileName = "encoder.onnx",
                        sha256 = kwsContent.sha256(),
                    ),
                ),
            ),
        )
    }

    private fun serverClient() = okhttp3.OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .build()

    private fun bytesResponse(content: ByteArray): MockResponse {
        return MockResponse().setResponseCode(200).setBody(Buffer().write(content))
    }

    private fun tarBzip(path: String, content: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        BZip2CompressorOutputStream(bytes).use { bzip ->
            TarArchiveOutputStream(bzip).use { tar ->
                val entry = TarArchiveEntry(path).apply { size = content.size.toLong() }
                tar.putArchiveEntry(entry)
                tar.write(content)
                tar.closeArchiveEntry()
                tar.finish()
            }
        }
        return bytes.toByteArray()
    }

    private fun ByteArray.sha256(): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

private object SilentVoiceModelSetupLogger : VoiceModelSetupLogger {
    override fun info(message: String) = Unit

    override fun error(message: String) = Unit
}
