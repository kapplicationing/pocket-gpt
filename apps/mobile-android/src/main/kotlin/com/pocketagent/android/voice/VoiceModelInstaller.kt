package com.pocketagent.android.voice

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

enum class VoiceModelSetupPhase {
    IDLE,
    QUEUED,
    DOWNLOADING,
    INSTALLING,
    READY,
    FAILED,
}

data class VoiceModelSetupState(
    val phase: VoiceModelSetupPhase = VoiceModelSetupPhase.IDLE,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = VoiceModelInstallManifest.PRODUCTION.totalDownloadBytes,
    val error: String? = null,
) {
    val progressPercent: Int
        get() = if (totalBytes <= 0L) {
            0
        } else {
            ((downloadedBytes.coerceIn(0L, totalBytes) * 100L) / totalBytes).toInt()
        }
}

internal data class VoiceModelFile(
    val directory: String,
    val fileName: String,
    val sha256: String,
    val url: String? = null,
    val downloadBytes: Long = 0L,
    val installedBytes: Long = downloadBytes,
)

internal data class VoiceModelArchive(
    val url: String,
    val sha256: String,
    val downloadBytes: Long,
    val rootDirectory: String,
    val files: List<VoiceModelFile>,
)

internal data class VoiceModelInstallManifest(
    val directFiles: List<VoiceModelFile>,
    val archive: VoiceModelArchive,
    val profileId: String = "custom",
) {
    val totalDownloadBytes: Long
        get() = directFiles.sumOf(VoiceModelFile::downloadBytes) + archive.downloadBytes

    companion object {
        private const val ASR_DIRECTORY = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"
        private const val KWS_DIRECTORY = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
        private const val ASR_BASE_URL =
            "https://huggingface.co/csukuangfj/" +
                "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/resolve/main"

        val PRODUCTION = VoiceModelInstallManifest(
            profileId = PRODUCTION_PROFILE_ID,
            directFiles = listOf(
                VoiceModelFile(
                    directory = ASR_DIRECTORY,
                    fileName = "encoder-epoch-99-avg-1.int8.onnx",
                    sha256 = "3810755ce7c3ab26b42a8bcf39d191308fa27fb0f53358823ba46141d03b7eb3",
                    url = "$ASR_BASE_URL/encoder-epoch-99-avg-1.int8.onnx?download=true",
                    downloadBytes = 42_845_182L,
                ),
                VoiceModelFile(
                    directory = ASR_DIRECTORY,
                    fileName = "decoder-epoch-99-avg-1.onnx",
                    sha256 = "45a7f940ecfb53d89fa270ad11b88b961e53a317203eb24b1c8e95ed208b0f30",
                    url = "$ASR_BASE_URL/decoder-epoch-99-avg-1.onnx?download=true",
                    downloadBytes = 2_092_272L,
                ),
                VoiceModelFile(
                    directory = ASR_DIRECTORY,
                    fileName = "joiner-epoch-99-avg-1.int8.onnx",
                    sha256 = "e085d73b593cf9b0707f370dbd656d58327d3fe36d80d849202ef81df02cb01e",
                    url = "$ASR_BASE_URL/joiner-epoch-99-avg-1.int8.onnx?download=true",
                    downloadBytes = 259_572L,
                ),
                VoiceModelFile(
                    directory = ASR_DIRECTORY,
                    fileName = "tokens.txt",
                    sha256 = "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb",
                    url = "$ASR_BASE_URL/tokens.txt?download=true",
                    downloadBytes = 5_048L,
                ),
            ),
            archive = VoiceModelArchive(
                url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/" +
                    "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2",
                sha256 = "f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a",
                downloadBytes = 17_626_723L,
                rootDirectory = KWS_DIRECTORY,
                files = listOf(
                    VoiceModelFile(
                        directory = KWS_DIRECTORY,
                        fileName = "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                        sha256 = "1e721676515bcd42a186979733981213c66c80db680e1cc582dfedf3be76e678",
                        installedBytes = 4_807_159L,
                    ),
                    VoiceModelFile(
                        directory = KWS_DIRECTORY,
                        fileName = "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                        sha256 = "e40ff43297abe815e8898494c17e71bba2152d9d40fa3eb803f75d0f7533329a",
                        installedBytes = 277_985L,
                    ),
                    VoiceModelFile(
                        directory = KWS_DIRECTORY,
                        fileName = "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                        sha256 = "eae9da0c7e1e6c6a3f4cc42d167899c388f6c6701b94cb96320e4f55df79624c",
                        installedBytes = 163_380L,
                    ),
                    VoiceModelFile(
                        directory = KWS_DIRECTORY,
                        fileName = "tokens.txt",
                        sha256 = "fd2ded4050a55d2b1578870ba8697d02371980217806b7558bd0a5cc60f3ba53",
                        installedBytes = 5_006L,
                    ),
                ),
            ),
        )

        private const val PRODUCTION_PROFILE_ID = "offas-int8-sherpa-8.5.4-v2"
    }
}

internal interface VoiceModelSetupLogger {
    fun info(message: String)

    fun error(message: String)
}

private object AndroidVoiceModelSetupLogger : VoiceModelSetupLogger {
    override fun info(message: String) {
        Log.i("PocketAgentVoiceSetup", message)
    }

    override fun error(message: String) {
        Log.e("PocketAgentVoiceSetup", message)
    }
}

/** Downloads only pinned upstream artifacts and atomically publishes a verified voice model set. */
internal class VoiceModelInstaller(
    private val installRoot: File,
    private val cacheRoot: File,
    private val client: OkHttpClient = OkHttpClient(),
    private val manifest: VoiceModelInstallManifest = VoiceModelInstallManifest.PRODUCTION,
    private val logger: VoiceModelSetupLogger = AndroidVoiceModelSetupLogger,
) {
    constructor(context: Context) : this(
        installRoot = VoiceModelCatalog.root(context.applicationContext),
        cacheRoot = File(context.applicationContext.cacheDir, "voice-model-setup"),
    )

    private val mutableState = MutableStateFlow(
        VoiceModelSetupState(totalBytes = manifest.totalDownloadBytes),
    )

    fun observe(): StateFlow<VoiceModelSetupState> = mutableState.asStateFlow()

    fun markQueued() {
        mutableState.value = VoiceModelSetupState(
            phase = VoiceModelSetupPhase.QUEUED,
            totalBytes = manifest.totalDownloadBytes,
        )
    }

    fun markCancelled() {
        mutableState.value = VoiceModelSetupState(totalBytes = manifest.totalDownloadBytes)
    }

    suspend fun install(): Result<Unit> = PROCESS_INSTALL_MUTEX.withLock {
        withContext(Dispatchers.IO) {
            logger.info("VOICE_MODEL_SETUP|phase=started|bytes=${manifest.totalDownloadBytes}")
            runCatching { installVerifiedModels() }
                .onSuccess {
                    mutableState.value = VoiceModelSetupState(
                        phase = VoiceModelSetupPhase.READY,
                        downloadedBytes = manifest.totalDownloadBytes,
                        totalBytes = manifest.totalDownloadBytes,
                    )
                    logger.info("VOICE_MODEL_SETUP|phase=ready|integrity=verified")
                }
                .onFailure { error ->
                    if (error is CancellationException) {
                        markCancelled()
                        throw error
                    }
                    mutableState.value = VoiceModelSetupState(
                        phase = VoiceModelSetupPhase.FAILED,
                        totalBytes = manifest.totalDownloadBytes,
                        error = error.message ?: "Voice model setup failed.",
                    )
                    logger.error("VOICE_MODEL_SETUP|phase=failed|reason=${error.javaClass.simpleName}")
                }
        }
    }

    private suspend fun installVerifiedModels() {
        val parent = checkNotNull(installRoot.parentFile) { "Voice model storage is unavailable." }
        parent.mkdirs()
        cacheRoot.mkdirs()
        recoverInterruptedInstall(parent)
        if (installRoot.isDirectory && runCatching { verifyCompleteStaging(installRoot) }.isSuccess) {
            writeInstallMarker(installRoot)
            return
        }
        val nonce = UUID.randomUUID().toString()
        val stagingRoot = File(parent, "${installRoot.name}.installing-$nonce")
        val archiveFile = File(cacheRoot, "kws-$nonce.tar.bz2")
        var completedBytes = 0L
        try {
            manifest.directFiles.forEach { modelFile ->
                val target = safeTarget(stagingRoot, modelFile.directory, modelFile.fileName)
                downloadVerified(modelFile, target, completedBytes)
                completedBytes += modelFile.downloadBytes
            }

            downloadVerifiedArchive(archiveFile, completedBytes)
            completedBytes += manifest.archive.downloadBytes
            mutableState.value = VoiceModelSetupState(
                phase = VoiceModelSetupPhase.INSTALLING,
                downloadedBytes = completedBytes,
                totalBytes = manifest.totalDownloadBytes,
            )
            extractVerifiedArchive(archiveFile, stagingRoot)
            writeDedicatedKeyword(stagingRoot)
            verifyCompleteStaging(stagingRoot)
            writeInstallMarker(stagingRoot)
            publishAtomically(stagingRoot)
        } finally {
            archiveFile.delete()
            if (stagingRoot.exists()) stagingRoot.deleteRecursively()
        }
    }

    private fun recoverInterruptedInstall(parent: File) {
        val backup = File(parent, "${installRoot.name}.previous")
        if (!installRoot.exists() && backup.exists()) {
            check(backup.renameTo(installRoot)) { "Could not restore the previous voice models." }
        } else if (installRoot.exists() && backup.exists()) {
            check(backup.deleteRecursively()) { "Could not remove the stale voice model backup." }
        }
        parent.listFiles()
            .orEmpty()
            .filter { candidate -> candidate.name.startsWith("${installRoot.name}.installing-") }
            .forEach { candidate ->
                check(candidate.deleteRecursively()) { "Could not remove stale voice model staging." }
            }
        cacheRoot.listFiles()
            .orEmpty()
            .filter { candidate ->
                candidate.name.startsWith("kws-") && candidate.name.endsWith(".tar.bz2")
            }
            .forEach { candidate ->
                check(candidate.delete()) { "Could not remove a stale voice model download." }
            }
    }

    private suspend fun downloadVerified(
        modelFile: VoiceModelFile,
        target: File,
        completedBytes: Long,
    ) {
        val url = checkNotNull(modelFile.url) { "Missing source URL for ${modelFile.fileName}." }
        downloadWithRetries(
            url = url,
            target = target,
            expectedBytes = modelFile.downloadBytes,
            expectedHash = modelFile.sha256,
            completedBytes = completedBytes,
        )
    }

    private suspend fun downloadVerifiedArchive(target: File, completedBytes: Long) {
        downloadWithRetries(
            url = manifest.archive.url,
            target = target,
            expectedBytes = manifest.archive.downloadBytes,
            expectedHash = manifest.archive.sha256,
            completedBytes = completedBytes,
        )
    }

    private suspend fun downloadWithRetries(
        url: String,
        target: File,
        expectedBytes: Long,
        expectedHash: String,
        completedBytes: Long,
    ) {
        var lastError: Throwable? = null
        repeat(MAX_DOWNLOAD_ATTEMPTS) { attempt ->
            target.delete()
            val result = runCatching {
                downloadOnce(url, target, expectedBytes, completedBytes)
                check(target.length() == expectedBytes) {
                    "Downloaded ${target.name} has ${target.length()} bytes; expected $expectedBytes."
                }
                check(target.sha256().equals(expectedHash, ignoreCase = true)) {
                    "Downloaded ${target.name} failed its integrity check."
                }
            }
            if (result.isSuccess) return
            lastError = result.exceptionOrNull()
            if (attempt < MAX_DOWNLOAD_ATTEMPTS - 1) delay((attempt + 1L) * RETRY_DELAY_MS)
        }
        throw checkNotNull(lastError)
    }

    private suspend fun downloadOnce(url: String, target: File, expectedBytes: Long, completedBytes: Long) {
        target.parentFile?.mkdirs()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "PocketAgent-Voice-Setup")
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Voice model download failed with HTTP ${response.code}." }
            val body = checkNotNull(response.body) { "Voice model download returned no data." }
            body.byteStream().use { input ->
                writeDownload(input, target, expectedBytes, completedBytes)
            }
        }
    }

    private suspend fun writeDownload(
        input: InputStream,
        target: File,
        expectedBytes: Long,
        completedBytes: Long,
    ) {
        FileOutputStream(target).use { output ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
            var currentBytes = 0L
            var read = input.read(buffer)
            while (read >= 0) {
                currentCoroutineContext().ensureActive()
                if (read > 0) {
                    currentBytes += read
                    check(currentBytes <= expectedBytes) {
                        "Voice model download exceeded its expected size."
                    }
                    output.write(buffer, 0, read)
                    mutableState.value = VoiceModelSetupState(
                        phase = VoiceModelSetupPhase.DOWNLOADING,
                        downloadedBytes = completedBytes + currentBytes,
                        totalBytes = manifest.totalDownloadBytes,
                    )
                }
                read = input.read(buffer)
            }
            output.fd.sync()
        }
    }

    private fun extractVerifiedArchive(archiveFile: File, stagingRoot: File) {
        val expected = manifest.archive.files.associateBy(VoiceModelFile::fileName)
        val extracted = mutableSetOf<String>()
        TarArchiveInputStream(
            BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archiveFile))),
        ).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                if (entry.isFile) {
                    extractExpectedArchiveEntry(tar, entry.name, expected, extracted, stagingRoot)
                }
            }
        }
        check(extracted == expected.keys) {
            "The wake-word package is missing: ${(expected.keys - extracted).sorted().joinToString()}."
        }
    }

    private fun extractExpectedArchiveEntry(
        tar: TarArchiveInputStream,
        entryName: String,
        expected: Map<String, VoiceModelFile>,
        extracted: MutableSet<String>,
        stagingRoot: File,
    ) {
        val normalized = entryName.replace('\\', '/')
        val expectedPrefix = "${manifest.archive.rootDirectory}/"
        if (!normalized.startsWith(expectedPrefix)) return
        val fileName = normalized.removePrefix(expectedPrefix)
        val modelFile = expected[fileName] ?: return
        check('/' !in fileName) { "Nested KWS model entry is not allowed: $normalized" }
        val target = safeTarget(stagingRoot, modelFile.directory, modelFile.fileName)
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { output -> tar.copyTo(output) }
        check(target.sha256().equals(modelFile.sha256, ignoreCase = true)) {
            "Extracted ${modelFile.fileName} failed its integrity check."
        }
        extracted += modelFile.fileName
    }

    private fun writeDedicatedKeyword(stagingRoot: File) {
        val target = safeTarget(stagingRoot, manifest.archive.rootDirectory, "keywords.txt")
        target.parentFile?.mkdirs()
        target.writeText(DEDICATED_KEYWORDS_CONTENT)
    }

    private fun writeInstallMarker(stagingRoot: File) {
        File(stagingRoot, INSTALL_MARKER_FILE).writeText(
            "profile=${manifest.profileId}\n" +
                "runtime=$SHERPA_RUNTIME_VERSION\n",
        )
    }

    private fun verifyCompleteStaging(stagingRoot: File) {
        val allFiles = manifest.directFiles + manifest.archive.files
        allFiles.forEach { modelFile ->
            val target = safeTarget(stagingRoot, modelFile.directory, modelFile.fileName)
            check(target.isFile && target.sha256().equals(modelFile.sha256, ignoreCase = true)) {
                "Voice model verification failed for ${modelFile.fileName}."
            }
        }
        val keywords = safeTarget(stagingRoot, manifest.archive.rootDirectory, "keywords.txt")
        check(hasDedicatedOffasKeyword(keywords.readText())) { "The Offas keyword graph is invalid." }
    }

    private fun publishAtomically(stagingRoot: File) {
        val parent = checkNotNull(installRoot.parentFile)
        val backup = File(parent, "${installRoot.name}.previous")
        if (backup.exists()) backup.deleteRecursively()
        if (installRoot.exists()) {
            check(installRoot.renameTo(backup)) { "Could not preserve the previous voice models." }
        }
        if (!stagingRoot.renameTo(installRoot)) {
            check(!backup.exists() || backup.renameTo(installRoot)) {
                "Could not restore the previous voice models."
            }
            error("Could not publish the verified voice models.")
        }
        if (backup.exists()) backup.deleteRecursively()
    }

    private fun safeTarget(root: File, directory: String, fileName: String): File {
        val target = File(File(root, directory), fileName)
        val rootPath = root.canonicalFile.toPath()
        check(target.canonicalFile.toPath().startsWith(rootPath)) { "Unsafe voice model path." }
        return target
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    companion object {
        @Volatile
        private var processInstance: VoiceModelInstaller? = null
        private val PROCESS_INSTALL_MUTEX = Mutex()

        fun process(context: Context): VoiceModelInstaller {
            return processInstance ?: synchronized(this) {
                processInstance ?: VoiceModelInstaller(context.applicationContext)
                    .also { processInstance = it }
            }
        }

        fun isProductionInstallCompatible(root: File): Boolean {
            if (!root.isDirectory) return false
            val markerValues = File(root, INSTALL_MARKER_FILE)
                .takeIf(File::isFile)
                ?.readLines()
                ?.mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) null else line.take(separator) to line.drop(separator + 1)
                }
                ?.toMap()
                ?: return false
            if (markerValues["profile"] != VoiceModelInstallManifest.PRODUCTION.profileId ||
                markerValues["runtime"] != SHERPA_RUNTIME_VERSION
            ) {
                return false
            }
            val files = VoiceModelInstallManifest.PRODUCTION.let { it.directFiles + it.archive.files }
            return files.all { modelFile ->
                val file = File(File(root, modelFile.directory), modelFile.fileName)
                file.isFile && file.length() == modelFile.installedBytes
            }
        }

        const val MAX_DOWNLOAD_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 1_000L
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        const val DEDICATED_KEYWORDS_CONTENT = "▁OF F ▁US @OFFAS\n"
        const val INSTALL_MARKER_FILE = "install-manifest.properties"
        const val SHERPA_RUNTIME_VERSION = "8.5.4"
    }
}
