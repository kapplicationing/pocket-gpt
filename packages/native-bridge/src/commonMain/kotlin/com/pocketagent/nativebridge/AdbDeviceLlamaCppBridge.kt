package com.pocketagent.nativebridge

import com.pocketagent.inference.ModelCatalog

class AdbDeviceLlamaCppBridge(
    private val supportedModels: Set<String> = ModelCatalog.bridgeSupportedModels().toSet(),
    private val commandRunner: CommandRunner = ProcessCommandRunner(),
    private val env: Map<String, String> = System.getenv(),
) : LlamaCppRuntimeBridge {
    private var activeSerial: String? = null
    private var activeModelId: String? = null
    private var runtimeGenerationConfig: RuntimeGenerationConfig = RuntimeGenerationConfig.default()
    @Volatile
    private var loadedModel: LoadedModelInfo? = null

    override fun isReady(): Boolean = resolveSerial() != null

    override fun listAvailableModels(): List<String> = supportedModels.sorted()

    override fun setRuntimeGenerationConfig(config: RuntimeGenerationConfig) {
        runtimeGenerationConfig = config
    }

    override fun getRuntimeGenerationConfig(): RuntimeGenerationConfig = runtimeGenerationConfig

    override fun supportsGpuOffload(): Boolean = false

    override fun loadModel(modelId: String, modelPath: String?): Boolean {
        val validation = ModelCatalog.validateBridgeLoad(
            modelId = modelId,
            modelPath = modelPath,
            supportedModels = supportedModels,
        )
        if (!validation.accepted) {
            return false
        }
        val serial = resolveSerial() ?: return false
        activeSerial = serial
        activeModelId = modelId
        loadedModel = LoadedModelInfo(
            modelId = modelId,
            modelPath = validation.normalizedModelPath,
            modelVersion = null,
        )
        return true
    }

    override fun generate(
        requestId: String,
        prompt: String,
        maxTokens: Int,
        cacheKey: String?,
        cachePolicy: CachePolicy,
        onToken: (String) -> Unit,
    ): GenerationResult {
        val startedMs = System.currentTimeMillis()
        val serial = activeSerial ?: resolveSerial()
        if (serial == null) {
            return currentGenerationResult(
                finishReason = GenerationFinishReason.ERROR,
                tokenCount = 0,
                firstTokenMs = -1L,
                totalMs = (System.currentTimeMillis() - startedMs).coerceAtLeast(0L),
                cancelled = false,
                errorCode = "ADB_NO_SERIAL",
            )
        }
        val model = activeModelId
        if (model == null) {
            return currentGenerationResult(
                finishReason = GenerationFinishReason.ERROR,
                tokenCount = 0,
                firstTokenMs = -1L,
                totalMs = (System.currentTimeMillis() - startedMs).coerceAtLeast(0L),
                cancelled = false,
                errorCode = "ADB_NO_ACTIVE_MODEL",
            )
        }

        val shellOutput = runCatching {
            commandRunner.run(
                listOf(
                    "adb",
                    "-s",
                    serial,
                    "shell",
                    "echo",
                    "ADB_LLAMACPP",
                    "model=$model",
                    "max_tokens=$maxTokens",
                    "cache_policy=${cachePolicy.name}",
                ),
            )
        }.getOrElse {
            return currentGenerationResult(
                finishReason = GenerationFinishReason.ERROR,
                tokenCount = 0,
                firstTokenMs = -1L,
                totalMs = (System.currentTimeMillis() - startedMs).coerceAtLeast(0L),
                cancelled = false,
                errorCode = "ADB_EXEC_EXCEPTION",
            )
        }
        if (shellOutput.exitCode != 0) {
            return currentGenerationResult(
                finishReason = GenerationFinishReason.ERROR,
                tokenCount = 0,
                firstTokenMs = -1L,
                totalMs = (System.currentTimeMillis() - startedMs).coerceAtLeast(0L),
                cancelled = false,
                errorCode = "ADB_EXEC_FAILURE",
            )
        }

        val response = buildString {
            append(shellOutput.stdout.trim())
            append(" ")
            append("prompt_hash=")
            append(prompt.hashCode())
        }.trim()

        var firstTokenMs = -1L
        var tokenCount = 0
        response
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .forEach { token ->
                if (firstTokenMs < 0L) {
                    firstTokenMs = System.currentTimeMillis() - startedMs
                }
                tokenCount += 1
                onToken("$token ")
            }
        return currentGenerationResult(
            finishReason = GenerationFinishReason.COMPLETED,
            tokenCount = tokenCount,
            firstTokenMs = firstTokenMs,
            totalMs = (System.currentTimeMillis() - startedMs).coerceAtLeast(0L),
            cancelled = false,
            errorCode = null,
        )
    }

    override fun unloadModel() {
        activeModelId = null
        loadedModel = null
    }

    override fun offloadModel(reason: String): Boolean {
        unloadModel()
        return true
    }

    override fun cancelGeneration(): Boolean {
        // Adb fallback generation is command-based and currently non-interruptible.
        return false
    }

    override fun cancelGeneration(requestId: String): Boolean = false

    override fun runtimeBackend(): RuntimeBackend {
        return if (isReady()) RuntimeBackend.ADB_FALLBACK else RuntimeBackend.UNAVAILABLE
    }

    override fun getLoadedModel(): LoadedModelInfo? = loadedModel

    private fun resolveSerial(): String? {
        val preferred = env["ADB_SERIAL"]?.trim()?.takeIf { it.isNotEmpty() }
        val result = runCatching {
            commandRunner.run(listOf("adb", "devices", "-l"))
        }.getOrElse { return null }
        if (result.exitCode != 0) {
            return null
        }

        val devices = result.stdout
            .lineSequence()
            .drop(1)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line -> line.split(Regex("\\s+")) }
            .filter { cols -> cols.size >= 2 && cols[1] == "device" }
            .map { cols -> cols[0] }
            .toList()

        if (preferred != null) {
            return devices.firstOrNull { it == preferred }
        }
        return devices.singleOrNull()
    }

    private fun currentGenerationResult(
        finishReason: GenerationFinishReason,
        tokenCount: Int,
        firstTokenMs: Long,
        totalMs: Long,
        cancelled: Boolean,
        errorCode: String? = null,
    ): GenerationResult {
        return GenerationResult(
            finishReason = finishReason,
            tokenCount = tokenCount,
            firstTokenMs = firstTokenMs,
            totalMs = totalMs,
            cancelled = cancelled,
            errorCode = errorCode,
            kvCachePreset = runtimeGenerationConfig.kvCachePreset,
        )
    }
}
