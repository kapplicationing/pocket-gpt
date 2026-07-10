package com.pocketagent.android

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketagent.android.runtime.RuntimeDiagnosticsSnapshotParser
import com.pocketagent.core.ChatResponse
import com.pocketagent.core.RoutingMode
import com.pocketagent.inference.AdaptiveRoutingPolicy
import com.pocketagent.inference.DeviceState
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.inference.RoutingModule
import com.pocketagent.nativebridge.FlashAttnMode
import com.pocketagent.runtime.PerformanceRuntimeConfig
import com.pocketagent.runtime.RuntimePerformanceProfile
import com.pocketagent.runtime.SamplingOverrides
import com.pocketagent.tools.ToolCall
import com.pocketagent.tools.ToolModule
import com.pocketagent.tools.ToolResult
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeStage2BenchmarkInstrumentationTest {
    @Test
    fun runConfiguredScenario() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Skipping Stage-2 native benchmark instrumentation test. Set stage2_enable_benchmark=true to run benchmark suite.",
            parseBooleanArg(args, ARG_ENABLE_BENCHMARK, defaultValue = false),
        )
        val modelId = (args.getString(ARG_MODEL_ID) ?: ModelCatalog.QWEN_3_5_0_8B_Q4).trim()
        require(modelId in SUPPORTED_MODELS) { "Unsupported model id: $modelId" }
        assumeTrue(
            "Skipping Stage-2 native benchmark instrumentation test. Missing model path arguments.",
            hasRequiredModelPaths(args, modelId),
        )
        val scenario = (args.getString(ARG_SCENARIO) ?: "A").trim().uppercase()
        require(scenario in setOf("A", "B")) { "Unsupported scenario: $scenario" }
        val modelPath = requireModelPathForBenchmark(args, modelId)
        val prefixCacheEnabled = parseBooleanArg(args, ARG_PREFIX_CACHE_ENABLED, defaultValue = true)
        val prefixCacheStrict = parseBooleanArg(args, ARG_PREFIX_CACHE_STRICT, defaultValue = false)
        val sessionMode = parseSessionMode(args.getString(ARG_SESSION_MODE))
        val requirePrefixCacheHit = parseBooleanArg(args, ARG_REQUIRE_PREFIX_CACHE_HIT, defaultValue = false)
        require(!requirePrefixCacheHit || (prefixCacheEnabled && sessionMode == BenchmarkSessionMode.SHARED)) {
            "$ARG_REQUIRE_PREFIX_CACHE_HIT requires shared session mode with prefix cache enabled."
        }
        val runtimeOptions = resolveBenchmarkRuntimeOptions(args)
        val runs = (args.getString(ARG_RUNS)?.toIntOrNull() ?: 3).coerceAtLeast(1)
        val minTokens = (args.getString(ARG_MIN_TOKENS)?.toIntOrNull() ?: DEFAULT_MIN_TOKENS)
            .coerceAtLeast(1)
        val warmupMaxTokens = (args.getString(ARG_WARMUP_MAX_TOKENS)?.toIntOrNull() ?: DEFAULT_WARMUP_MAX_TOKENS)
            .coerceAtLeast(0)
        val maxTokens = resolveMaxTokens(
            raw = args.getString(ARG_MAX_TOKENS),
            defaultValue = if (scenario == "A") 128 else 256,
            minTokens = minTokens,
        )

        val container = buildContainer(
            selectedModelId = modelId,
            modelPathsById = mapOf(modelId to modelPath),
            prefixCacheEnabled = prefixCacheEnabled,
            prefixCacheStrict = prefixCacheStrict,
            runtimeOptions = runtimeOptions,
        )
        configureContainerForModel(container = container, modelId = modelId)

        val backend = container.runtimeBackend()
        assertEquals(
            "Expected NATIVE_JNI backend for ENG-13 closure lane",
            com.pocketagent.nativebridge.RuntimeBackend.NATIVE_JNI,
            backend,
        )

        // Prime model load and one short generation so measured first-token values capture steady-state latency.
        if (warmupMaxTokens > 0) {
            runWarmup(
                container = container,
                scenario = scenario,
                runIndex = -1,
                maxTokens = warmupMaxTokens,
                runtimeOptions = runtimeOptions,
            )
        }

        val metricLine = executeScenario(
            container = container,
            backend = backend,
            scenario = scenario,
            modelId = modelId,
            runs = runs,
            maxTokens = maxTokens,
            runtimeOptions = runtimeOptions,
            sessionMode = sessionMode,
            requirePrefixCacheHit = requirePrefixCacheHit,
        )

        Log.i(METRIC_TAG, metricLine)
        println(metricLine)
    }

    @Test
    fun runConfiguredModelSweep() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Skipping Stage-2 native benchmark instrumentation sweep. Set stage2_enable_benchmark=true to run benchmark suite.",
            parseBooleanArg(args, ARG_ENABLE_BENCHMARK, defaultValue = false),
        )
        val modelId = (args.getString(ARG_MODEL_ID) ?: ModelCatalog.QWEN_3_5_0_8B_Q4).trim()
        require(modelId in SUPPORTED_MODELS) { "Unsupported model id: $modelId" }
        assumeTrue(
            "Skipping Stage-2 native benchmark instrumentation sweep. Missing model path arguments.",
            hasRequiredModelPaths(args, modelId),
        )
        val scenarios = parseScenarios(args.getString(ARG_SCENARIOS))
        val modelPath = requireModelPathForBenchmark(args, modelId)
        val prefixCacheEnabled = parseBooleanArg(args, ARG_PREFIX_CACHE_ENABLED, defaultValue = true)
        val prefixCacheStrict = parseBooleanArg(args, ARG_PREFIX_CACHE_STRICT, defaultValue = false)
        val sessionMode = parseSessionMode(args.getString(ARG_SESSION_MODE))
        val requirePrefixCacheHit = parseBooleanArg(args, ARG_REQUIRE_PREFIX_CACHE_HIT, defaultValue = false)
        require(!requirePrefixCacheHit || (prefixCacheEnabled && sessionMode == BenchmarkSessionMode.SHARED)) {
            "$ARG_REQUIRE_PREFIX_CACHE_HIT requires shared session mode with prefix cache enabled."
        }
        val runtimeOptions = resolveBenchmarkRuntimeOptions(args)
        val runs = (args.getString(ARG_RUNS)?.toIntOrNull() ?: 3).coerceAtLeast(1)
        val minTokens = (args.getString(ARG_MIN_TOKENS)?.toIntOrNull() ?: DEFAULT_MIN_TOKENS)
            .coerceAtLeast(1)
        val warmupMaxTokens = (args.getString(ARG_WARMUP_MAX_TOKENS)?.toIntOrNull() ?: DEFAULT_WARMUP_MAX_TOKENS)
            .coerceAtLeast(0)
        val maxTokensA = resolveMaxTokens(
            raw = args.getString(ARG_MAX_TOKENS_A) ?: args.getString(ARG_MAX_TOKENS),
            defaultValue = 128,
            minTokens = minTokens,
        )
        val maxTokensB = resolveMaxTokens(
            raw = args.getString(ARG_MAX_TOKENS_B) ?: args.getString(ARG_MAX_TOKENS),
            defaultValue = 256,
            minTokens = minTokens,
        )

        val container = buildContainer(
            selectedModelId = modelId,
            modelPathsById = mapOf(modelId to modelPath),
            prefixCacheEnabled = prefixCacheEnabled,
            prefixCacheStrict = prefixCacheStrict,
            runtimeOptions = runtimeOptions,
        )
        configureContainerForModel(container = container, modelId = modelId)

        val backend = container.runtimeBackend()
        assertEquals(
            "Expected NATIVE_JNI backend for ENG-13 closure lane",
            com.pocketagent.nativebridge.RuntimeBackend.NATIVE_JNI,
            backend,
        )

        if (warmupMaxTokens > 0) {
            runWarmup(
                container = container,
                scenario = scenarios.first(),
                runIndex = -1,
                maxTokens = warmupMaxTokens,
                runtimeOptions = runtimeOptions,
            )
        }

        scenarios.forEach { scenario ->
            val maxTokens = if (scenario == "A") maxTokensA else maxTokensB
            val metricLine = executeScenario(
                container = container,
                backend = backend,
                scenario = scenario,
                modelId = modelId,
                runs = runs,
                maxTokens = maxTokens,
                runtimeOptions = runtimeOptions,
                sessionMode = sessionMode,
                requirePrefixCacheHit = requirePrefixCacheHit,
            )
            Log.i(METRIC_TAG, metricLine)
            println(metricLine)
        }
    }

    private fun executeScenario(
        container: AndroidMvpContainer,
        backend: com.pocketagent.nativebridge.RuntimeBackend?,
        scenario: String,
        modelId: String,
        runs: Int,
        maxTokens: Int,
        runtimeOptions: BenchmarkRuntimeOptions,
        sessionMode: BenchmarkSessionMode,
        requirePrefixCacheHit: Boolean,
    ): String {
        val firstTokenSamples = mutableListOf<Long>()
        val modelLoadSamples = mutableListOf<Long>()
        val prefillSamples = mutableListOf<Long>()
        val decodeMsSamples = mutableListOf<Long>()
        val decodeSamples = mutableListOf<Double>()
        val tokenSamples = mutableListOf<Int>()
        val residentHitSamples = mutableListOf<Int>()
        val residentHitCountSamples = mutableListOf<Long>()
        val prefixCacheHitSamples = mutableListOf<Int>()
        val prefixCacheReusedTokenSamples = mutableListOf<Int>()
        val prefixCacheHitRateSamples = mutableListOf<Double>()
        val reloadReasons = mutableListOf<String>()
        val sharedSession = if (sessionMode == BenchmarkSessionMode.SHARED) {
            container.createSession()
        } else {
            null
        }

        repeat(runs) { index ->
            var attempt = 0
            var response: ChatResponse? = null
            var streamedTokens: MutableList<String>? = null
            while (attempt < MAX_ATTEMPTS_PER_RUN) {
                attempt += 1
                val session = sharedSession ?: container.createSession()
                val localStreamedTokens = mutableListOf<String>()
                val attemptMaxTokens = benchmarkMaxTokensForAttempt(maxTokens = maxTokens, attempt = attempt)
                val outcome = runCatching {
                    when (sessionMode) {
                        BenchmarkSessionMode.ISOLATED -> {
                            container.sendUserMessage(
                                sessionId = session,
                                userText = buildAttemptPrompt(
                                    scenario = scenario,
                                    runIndex = index,
                                    attempt = attempt,
                                    promptKind = BenchmarkPromptKind.ISOLATED,
                                ),
                                taskType = scenarioTaskType(scenario),
                                deviceState = scenarioDeviceState(scenario),
                                maxTokens = attemptMaxTokens,
                                keepModelLoaded = true,
                                requestId = benchmarkRequestId(
                                    scenario = scenario,
                                    runIndex = index,
                                    attempt = attempt,
                                    promptKind = BenchmarkPromptKind.ISOLATED,
                                ),
                                performanceConfig = runtimeOptions.performanceConfig,
                                onToken = { token -> localStreamedTokens.add(token) },
                            )
                        }

                        BenchmarkSessionMode.SHARED -> {
                            container.sendUserMessage(
                                sessionId = session,
                                userText = buildAttemptPrompt(
                                    scenario = scenario,
                                    runIndex = index,
                                    attempt = attempt,
                                    promptKind = BenchmarkPromptKind.PRIMER,
                                ),
                                taskType = scenarioTaskType(scenario),
                                deviceState = scenarioDeviceState(scenario),
                                maxTokens = followUpPrimerMaxTokens(attemptMaxTokens),
                                keepModelLoaded = true,
                                requestId = benchmarkRequestId(
                                    scenario = scenario,
                                    runIndex = index,
                                    attempt = attempt,
                                    promptKind = BenchmarkPromptKind.PRIMER,
                                ),
                                performanceConfig = runtimeOptions.performanceConfig,
                                samplingOverrides = SamplingOverrides(showThinking = runtimeOptions.showThinking),
                                onToken = {},
                            )
                            container.sendUserMessage(
                                sessionId = session,
                                userText = buildAttemptPrompt(
                                    scenario = scenario,
                                    runIndex = index,
                                    attempt = attempt,
                                    promptKind = BenchmarkPromptKind.FOLLOW_UP,
                                ),
                                taskType = scenarioTaskType(scenario),
                                deviceState = scenarioDeviceState(scenario),
                                maxTokens = attemptMaxTokens,
                                keepModelLoaded = true,
                                requestId = benchmarkRequestId(
                                    scenario = scenario,
                                    runIndex = index,
                                    attempt = attempt,
                                    promptKind = BenchmarkPromptKind.FOLLOW_UP,
                                ),
                                performanceConfig = runtimeOptions.performanceConfig,
                                samplingOverrides = SamplingOverrides(showThinking = runtimeOptions.showThinking),
                                onToken = { token -> localStreamedTokens.add(token) },
                            )
                        }
                    }
                }
                if (outcome.isSuccess) {
                    response = outcome.getOrNull()
                    streamedTokens = localStreamedTokens
                    break
                }
                val failure = outcome.exceptionOrNull()
                if (failure?.message?.contains("Runtime returned no tokens.") == true && attempt < MAX_ATTEMPTS_PER_RUN) {
                    Log.w(
                        METRIC_TAG,
                        "Scenario $scenario run=$index attempt=$attempt produced no visible tokens; retrying with safer token budget.",
                    )
                    continue
                }
                throw failure ?: IllegalStateException("Scenario execution failed with unknown error.")
            }

            val finalResponse = requireNotNull(response) {
                "Scenario $scenario run=$index exhausted retries without a valid response."
            }
            val finalTokens = requireNotNull(streamedTokens) {
                "Scenario $scenario run=$index did not capture streamed tokens."
            }
            assertEquals(
                "Benchmark run used unexpected model. This indicates fallback rather than the requested model path.",
                modelId,
                finalResponse.modelId,
            )

            val observedTokenCount = max(
                finalTokens.count { it.isNotBlank() },
                finalResponse.text.split(Regex("\\s+")).count { it.isNotBlank() },
            ).coerceAtLeast(1)
            val decodeDurationMs = (finalResponse.totalLatencyMs - finalResponse.firstTokenLatencyMs).coerceAtLeast(1L)
            val decodeTps = observedTokenCount / (decodeDurationMs.toDouble() / 1000.0)

            firstTokenSamples.add(finalResponse.firstTokenLatencyMs)
            finalResponse.runtimeStats?.modelLoadMs?.let(modelLoadSamples::add)
            finalResponse.runtimeStats?.prefillMs?.let(prefillSamples::add)
            finalResponse.runtimeStats?.decodeMs?.let(decodeMsSamples::add)
            decodeSamples.add(decodeTps)
            tokenSamples.add(observedTokenCount)
            finalResponse.runtimeStats?.residentHit?.let { residentHitSamples += if (it) 1 else 0 }
            finalResponse.runtimeStats?.residentHitCount?.let(residentHitCountSamples::add)
            finalResponse.runtimeStats?.prefixCacheLastHit?.let { prefixCacheHitSamples += if (it) 1 else 0 }
            finalResponse.runtimeStats?.prefixCacheLastReusedTokens?.let(prefixCacheReusedTokenSamples::add)
            finalResponse.runtimeStats?.prefixCacheHitRate?.let(prefixCacheHitRateSamples::add)
            finalResponse.runtimeStats?.reloadReason
                ?.takeIf { it.isNotBlank() }
                ?.let(reloadReasons::add)
        }
        if (requirePrefixCacheHit) {
            val prefixCacheHits = prefixCacheHitSamples.sum()
            val maxReusedTokens = prefixCacheReusedTokenSamples.maxOrNull() ?: 0
            check(prefixCacheHits > 0) {
                "Expected at least one prefix-cache hit for scenario $scenario with shared-session benchmarking."
            }
            check(maxReusedTokens > 0) {
                "Expected reused prefix-cache tokens for scenario $scenario with shared-session benchmarking."
            }
        }
        val diagnosticsSnapshot = RuntimeDiagnosticsSnapshotParser.parse(container.exportDiagnostics())

        return buildMetricLine(
            backend = backend?.name ?: com.pocketagent.nativebridge.RuntimeBackend.UNAVAILABLE.name,
            scenario = scenario,
            modelId = modelId,
            firstTokenMs = medianLong(firstTokenSamples),
            modelLoadMs = medianLongOrNull(modelLoadSamples),
            prefillMs = medianLongOrNull(prefillSamples),
            decodeMs = medianLongOrNull(decodeMsSamples),
            decodeTps = medianDouble(decodeSamples),
            tokens = medianInt(tokenSamples),
            runs = runs,
            pssKb = currentPssKb(),
            coldFirstTokenMs = firstTokenSamples.firstOrNull() ?: 0L,
            warmFirstTokenMs = firstTokenSamples.lastOrNull() ?: 0L,
            residentHit = medianIntOrNull(residentHitSamples),
            residentHitCount = medianLongOrNull(residentHitCountSamples),
            reloadReason = modeString(reloadReasons),
            prefixCacheHit = medianIntOrNull(prefixCacheHitSamples),
            prefixCacheReusedTokens = medianIntOrNull(prefixCacheReusedTokenSamples),
            prefixCacheHitRate = medianDoubleOrNull(prefixCacheHitRateSamples),
            mmapReadaheadMs = diagnosticsSnapshot.lastMmapReadaheadMs,
            mmapReadaheadBytes = diagnosticsSnapshot.lastMmapReadaheadBytes,
            activeBackend = diagnosticsSnapshot.activeBackend ?: "unknown",
            backendProfile = diagnosticsSnapshot.backendProfile ?: "unknown",
            qualificationState = diagnosticsSnapshot.backendQualificationState.name.lowercase(),
            appliedConfig = runtimeOptions.performanceConfig,
            toolsDisabled = runtimeOptions.disableTools,
            showThinking = runtimeOptions.showThinking,
            sessionMode = sessionMode,
        )
    }

    private fun configureContainerForModel(container: AndroidMvpContainer, modelId: String) {
        val descriptor = ModelCatalog.descriptorFor(modelId)
        val explicitMode = descriptor?.explicitRoutingModes?.firstOrNull()
        container.setRoutingMode(explicitMode ?: RoutingMode.AUTO)
    }

    private fun resolveMaxTokens(raw: String?, defaultValue: Int, minTokens: Int): Int {
        return (raw?.toIntOrNull() ?: defaultValue).coerceAtLeast(minTokens)
    }

    private fun parseScenarios(raw: String?): List<String> {
        val scenarios = raw
            ?.split(",")
            ?.map { it.trim().uppercase() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?: listOf("A", "B")
        require(scenarios.isNotEmpty()) { "At least one scenario must be provided." }
        scenarios.forEach { scenario ->
            require(scenario in setOf("A", "B")) { "Unsupported scenario: $scenario" }
        }
        return scenarios
    }

    private fun buildAttemptPrompt(
        scenario: String,
        runIndex: Int,
        attempt: Int,
        promptKind: BenchmarkPromptKind,
    ): String {
        val base = scenarioPrompt(
            scenario = scenario,
            runIndex = runIndex,
            promptKind = promptKind,
        )
        if (attempt <= 1) {
            return base
        }
        return "$base Reply with at least one word."
    }

    private fun buildContainer(
        selectedModelId: String,
        modelPathsById: Map<String, String>,
        prefixCacheEnabled: Boolean,
        prefixCacheStrict: Boolean,
        runtimeOptions: BenchmarkRuntimeOptions,
    ): AndroidMvpContainer {
        val pathMap = modelPathsById
        val shaByModel = pathMap.mapValues { (_, modelPath) -> sha256HexFromFile(File(modelPath)) }
        val issuerByModel = pathMap.mapValues { "internal-release" }
        val signatureByModel = pathMap.mapValues { (modelId, _) ->
            provenanceSignature(
                issuer = issuerByModel.getValue(modelId),
                modelId = modelId,
                payloadSha = shaByModel.getValue(modelId),
            )
        }
        val payloadByModel = pathMap.mapValues { (_, modelPath) ->
            "sideload:$modelPath".encodeToByteArray()
        }

        return AndroidMvpContainer(
            artifactPayloadByModelId = payloadByModel,
            artifactFilePathByModelId = pathMap,
            artifactSha256ByModelId = shaByModel,
            artifactProvenanceIssuerByModelId = issuerByModel,
            artifactProvenanceSignatureByModelId = signatureByModel,
            routingModule = fixedBenchmarkRoutingModule(selectedModelId),
            prefixCacheEnabled = prefixCacheEnabled,
            prefixCacheStrict = prefixCacheStrict,
            responseCacheTtlSec = 0L,
            responseCacheMaxEntries = 0,
            toolModule = if (runtimeOptions.disableTools) DisabledBenchmarkToolModule else ToolModuleDefaults.default,
        )
    }

    private fun fixedBenchmarkRoutingModule(modelId: String): RoutingModule {
        val fallback = AdaptiveRoutingPolicy()
        return object : RoutingModule {
            override fun selectModel(taskType: String, deviceState: DeviceState): String = modelId

            override fun selectContextBudget(taskType: String, deviceState: DeviceState): Int {
                return fallback.selectContextBudget(taskType, deviceState)
            }
        }
    }

    private fun scenarioPrompt(
        scenario: String,
        runIndex: Int,
        promptKind: BenchmarkPromptKind,
    ): String {
        return when (scenario) {
            "A" -> when (promptKind) {
                BenchmarkPromptKind.ISOLATED,
                BenchmarkPromptKind.PRIMER,
                -> "Answer directly in one plain sentence with no reasoning or tags. Summarize rollout status. run=$runIndex"

                BenchmarkPromptKind.FOLLOW_UP ->
                    "Answer directly with exactly three plain words, no reasoning or tags. Using the same rollout status, rewrite it. run=$runIndex"
            }

            else -> when (promptKind) {
                BenchmarkPromptKind.ISOLATED,
                BenchmarkPromptKind.PRIMER,
                -> "Answer directly with a short list, no reasoning or tags. List top rollout risks with short mitigations. run=$runIndex"

                BenchmarkPromptKind.FOLLOW_UP ->
                    "Answer directly with a short prioritized checklist, no reasoning or tags. Using the same rollout risks, turn them into a checklist. run=$runIndex"
            }
        }
    }

    private fun scenarioTaskType(scenario: String): String {
        return if (scenario == "A") "short_text" else "long_text"
    }

    private fun scenarioDeviceState(scenario: String): DeviceState {
        return when (scenario) {
            "A" -> DeviceState(batteryPercent = 85, thermalLevel = 3, ramClassGb = 8)
            else -> DeviceState(batteryPercent = 78, thermalLevel = 4, ramClassGb = 8)
        }
    }

    private fun runWarmup(
        container: AndroidMvpContainer,
        scenario: String,
        runIndex: Int,
        maxTokens: Int,
        runtimeOptions: BenchmarkRuntimeOptions,
    ) {
        val session = container.createSession()
        runCatching {
            container.sendUserMessage(
                sessionId = session,
                userText = scenarioPrompt(
                    scenario = scenario,
                    runIndex = runIndex,
                    promptKind = BenchmarkPromptKind.ISOLATED,
                ),
                taskType = scenarioTaskType(scenario),
                deviceState = scenarioDeviceState(scenario),
                maxTokens = maxTokens,
                keepModelLoaded = true,
                performanceConfig = runtimeOptions.performanceConfig,
                onToken = {},
            )
        }.onFailure { throwable ->
            Log.w(METRIC_TAG, "Warmup generation failed; continuing with measured run: ${throwable.message}")
        }
    }

    private fun currentPssKb(): Int {
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        return memoryInfo.totalPss.coerceAtLeast(0)
    }

    private fun buildMetricLine(
        backend: String,
        scenario: String,
        modelId: String,
        firstTokenMs: Long,
        modelLoadMs: Long?,
        prefillMs: Long?,
        decodeMs: Long?,
        decodeTps: Double,
        tokens: Int,
        runs: Int,
        pssKb: Int,
        coldFirstTokenMs: Long,
        warmFirstTokenMs: Long,
        residentHit: Int?,
        residentHitCount: Long?,
        reloadReason: String?,
        prefixCacheHit: Int?,
        prefixCacheReusedTokens: Int?,
        prefixCacheHitRate: Double?,
        mmapReadaheadMs: Long?,
        mmapReadaheadBytes: Long?,
        activeBackend: String,
        backendProfile: String,
        qualificationState: String,
        appliedConfig: PerformanceRuntimeConfig,
        toolsDisabled: Boolean,
        showThinking: Boolean,
        sessionMode: BenchmarkSessionMode,
    ): String {
        val warmVsColdDelta = coldFirstTokenMs - warmFirstTokenMs
        return buildString {
            append("STAGE2_METRIC")
            append("|backend=").append(backend)
            append("|scenario=").append(scenario)
            append("|session_mode=").append(sessionMode.wireValue)
            append("|model_id=").append(modelId)
            append("|first_token_ms=").append(firstTokenMs)
            modelLoadMs?.let { append("|model_load_ms=").append(it) }
            prefillMs?.let { append("|prefill_ms=").append(it) }
            decodeMs?.let { append("|decode_ms=").append(it) }
            append("|decode_tps=").append(String.format(Locale.US, "%.4f", decodeTps))
            append("|tokens=").append(tokens)
            append("|runs=").append(runs)
            append("|pss_kb=").append(pssKb)
            append("|cold_first_token_ms=").append(coldFirstTokenMs)
            append("|warm_first_token_ms=").append(warmFirstTokenMs)
            append("|warm_vs_cold_first_token_delta_ms=").append(warmVsColdDelta)
            residentHit?.let { append("|resident_hit=").append(it) }
            residentHitCount?.let { append("|resident_hit_count=").append(it) }
            reloadReason?.let { append("|reload_reason=").append(it) }
            prefixCacheHit?.let { append("|prefix_cache_hit=").append(it) }
            prefixCacheReusedTokens?.let { append("|prefix_cache_reused_tokens=").append(it) }
            prefixCacheHitRate?.let { append("|prefix_cache_hit_rate=").append(String.format(Locale.US, "%.4f", it)) }
            mmapReadaheadMs?.let { append("|mmap_readahead_ms=").append(it) }
            mmapReadaheadBytes?.let { append("|mmap_readahead_bytes=").append(it) }
            append("|active_backend=").append(activeBackend)
            append("|backend_profile=").append(backendProfile)
            append("|qualification_state=").append(qualificationState)
            append("|config_n_ctx=").append(appliedConfig.nCtx)
            append("|config_n_batch=").append(appliedConfig.nBatch)
            append("|config_n_ubatch=").append(appliedConfig.nUbatch)
            append("|config_n_threads=").append(appliedConfig.nThreads)
            append("|config_n_threads_batch=").append(appliedConfig.nThreadsBatch)
            append("|config_flash_attn=").append(appliedConfig.flashAttnMode.name.lowercase())
            append("|config_tools_disabled=").append(if (toolsDisabled) 1 else 0)
            append("|config_show_thinking=").append(if (showThinking) 1 else 0)
        }
    }

    private fun resolveBenchmarkRuntimeOptions(args: android.os.Bundle): BenchmarkRuntimeOptions {
        val profile = when ((args.getString(ARG_PROFILE) ?: "balanced").trim().lowercase()) {
            "battery" -> RuntimePerformanceProfile.BATTERY
            "fast" -> RuntimePerformanceProfile.FAST
            else -> RuntimePerformanceProfile.BALANCED
        }
        val baseConfig = PerformanceRuntimeConfig.forProfile(
            profile = profile,
            availableCpuThreads = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            gpuEnabled = parseBooleanArg(args, ARG_GPU_ENABLED, defaultValue = false),
        )
        val flashAttnMode = when ((args.getString(ARG_FLASH_ATTN_MODE) ?: "default").trim().lowercase()) {
            "auto" -> FlashAttnMode.AUTO
            "on" -> FlashAttnMode.ON
            "off" -> FlashAttnMode.OFF
            else -> baseConfig.flashAttnMode
        }
        return BenchmarkRuntimeOptions(
            performanceConfig = baseConfig.copy(
                nCtx = parseIntArg(args, ARG_N_CTX, baseConfig.nCtx),
                nBatch = parseIntArg(args, ARG_N_BATCH, baseConfig.nBatch),
                nUbatch = parseIntArg(args, ARG_N_UBATCH, baseConfig.nUbatch),
                nThreads = parseIntArg(args, ARG_N_THREADS, baseConfig.nThreads),
                nThreadsBatch = parseIntArg(args, ARG_N_THREADS_BATCH, baseConfig.nThreadsBatch),
                flashAttnMode = flashAttnMode,
            ),
            disableTools = parseBooleanArg(args, ARG_DISABLE_TOOLS, defaultValue = false),
            showThinking = parseBooleanArg(args, ARG_SHOW_THINKING, defaultValue = true),
        )
    }

    private fun parseBooleanArg(
        args: android.os.Bundle,
        key: String,
        defaultValue: Boolean,
    ): Boolean {
        val raw = args.getString(key)?.trim()?.lowercase() ?: return defaultValue
        return when (raw) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> defaultValue
        }
    }

    private fun parseIntArg(
        args: android.os.Bundle,
        key: String,
        defaultValue: Int,
    ): Int {
        return args.getString(key)?.toIntOrNull()?.takeIf { it > 0 } ?: defaultValue
    }

    private fun parseSessionMode(raw: String?): BenchmarkSessionMode {
        return when (raw?.trim()?.lowercase()) {
            BenchmarkSessionMode.SHARED.wireValue -> BenchmarkSessionMode.SHARED
            else -> BenchmarkSessionMode.ISOLATED
        }
    }

    private fun followUpPrimerMaxTokens(maxTokens: Int): Int {
        return maxTokens.coerceAtMost(24).coerceAtLeast(4)
    }

    private fun benchmarkMaxTokensForAttempt(maxTokens: Int, attempt: Int): Int {
        return if (attempt <= 1) {
            maxTokens
        } else {
            maxTokens.coerceAtLeast(MIN_RETRY_MAX_TOKENS)
        }
    }

    private fun benchmarkRequestId(
        scenario: String,
        runIndex: Int,
        attempt: Int,
        promptKind: BenchmarkPromptKind,
    ): String {
        return "bench-${scenario.lowercase()}-$runIndex-$attempt-${promptKind.name.lowercase()}"
    }

    private fun medianLong(values: List<Long>): Long = values.sorted()[values.size / 2]

    private fun medianLongOrNull(values: List<Long>): Long? = values.takeIf { it.isNotEmpty() }?.let(::medianLong)

    private fun medianDouble(values: List<Double>): Double = values.sorted()[values.size / 2]

    private fun medianDoubleOrNull(values: List<Double>): Double? = values.takeIf { it.isNotEmpty() }?.let(::medianDouble)

    private fun medianInt(values: List<Int>): Int = values.sorted()[values.size / 2]

    private fun medianIntOrNull(values: List<Int>): Int? = values.takeIf { it.isNotEmpty() }?.let(::medianInt)

    private fun modeString(values: List<String>): String? {
        return values.takeIf { it.isNotEmpty() }
            ?.groupingBy { it }
            ?.eachCount()
            ?.maxByOrNull { (_, count) -> count }
            ?.key
    }

    private fun requireModelPathForBenchmark(args: android.os.Bundle, modelId: String): String {
        val key = argumentKeyForModel(modelId)
        val value = args.getString(key)?.trim().orEmpty()
        require(value.isNotEmpty()) { "Missing instrumentation argument: $key" }
        val resolved = resolveModelPath(value)
        require(resolved != null) { "Model path does not exist: $value" }
        return resolved
    }

    private fun hasRequiredModelPaths(args: android.os.Bundle, modelId: String): Boolean {
        val key = argumentKeyForModel(modelId)
        val value = args.getString(key)?.trim().orEmpty()
        if (value.isEmpty()) {
            return false
        }
        return resolveModelPath(value) != null
    }

    private fun argumentKeyForModel(modelId: String): String {
        return when (modelId) {
            ModelCatalog.QWEN_3_5_0_8B_Q4 -> ARG_MODEL_PATH_0_8B
            ModelCatalog.QWEN3_1_7B_Q4_K_M -> ARG_MODEL_PATH_1_7B
            ModelCatalog.QWEN3_0_6B_Q4_K_M -> ARG_MODEL_PATH_0_8B
            ModelCatalog.LLAMA_3_2_1B_Q4_K_M -> ARG_MODEL_PATH_1_7B
            else -> ARG_MODEL_PATH_0_8B
        }
    }

    private fun resolveModelPath(value: String): String? {
        val normalized = value.trim()
        if (normalized.isEmpty()) {
            return null
        }
        val candidates = listOf(
            normalized,
            normalized.replace("/sdcard/", "/storage/emulated/0/"),
            normalized.replace("/storage/emulated/0/", "/sdcard/"),
        )
        return candidates
            .asSequence()
            .map { candidate -> File(candidate) }
            .firstOrNull(::isReadableRegularFile)
            ?.absolutePath
    }

    private fun isReadableRegularFile(file: File): Boolean {
        if (!file.exists() || !file.isFile) {
            return false
        }
        return runCatching {
            file.inputStream().use { input -> input.read() }
            true
        }.getOrDefault(false)
    }

    private fun sha256HexFromFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_HASH_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun provenanceSignature(issuer: String, modelId: String, payloadSha: String): String {
        return sha256Hex("$issuer|$modelId|$payloadSha|v1".encodeToByteArray())
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    companion object {
        private const val ARG_SCENARIO = "stage2_scenario"
        private const val ARG_ENABLE_BENCHMARK = "stage2_enable_benchmark"
        private const val ARG_MODEL_ID = "stage2_model_id"
        private const val ARG_PROFILE = "stage2_profile"
        private const val ARG_GPU_ENABLED = "stage2_gpu_enabled"
        private const val ARG_N_CTX = "stage2_n_ctx"
        private const val ARG_N_BATCH = "stage2_n_batch"
        private const val ARG_N_UBATCH = "stage2_n_ubatch"
        private const val ARG_N_THREADS = "stage2_n_threads"
        private const val ARG_N_THREADS_BATCH = "stage2_n_threads_batch"
        private const val ARG_FLASH_ATTN_MODE = "stage2_flash_attn_mode"
        private const val ARG_DISABLE_TOOLS = "stage2_disable_tools"
        private const val ARG_SHOW_THINKING = "stage2_show_thinking"
        private const val ARG_MODEL_PATH_0_8B = "stage2_model_0_8b_path"
        private const val ARG_MODEL_PATH_1_7B = "stage2_model_1_7b_path"
        private const val ARG_MODEL_PATH_SMOLLM3_Q4 = "stage2_model_smol_360m_path"
        private const val ARG_MODEL_PATH_SMOLLM3_DRAFT = "stage2_model_smol_135m_path"
        private const val ARG_RUNS = "stage2_runs"
        private const val ARG_MAX_TOKENS = "stage2_max_tokens"
        private const val ARG_MAX_TOKENS_A = "stage2_max_tokens_a"
        private const val ARG_MAX_TOKENS_B = "stage2_max_tokens_b"
        private const val ARG_SCENARIOS = "stage2_scenarios"
        private const val ARG_MIN_TOKENS = "stage2_min_tokens"
        private const val ARG_WARMUP_MAX_TOKENS = "stage2_warmup_max_tokens"
        private const val ARG_PREFIX_CACHE_ENABLED = "stage2_prefix_cache_enabled"
        private const val ARG_PREFIX_CACHE_STRICT = "stage2_prefix_cache_strict"
        private const val ARG_SESSION_MODE = "stage2_session_mode"
        private const val ARG_REQUIRE_PREFIX_CACHE_HIT = "stage2_require_prefix_cache_hit"
        private const val METRIC_TAG = "STAGE2_METRIC"
        private const val DEFAULT_HASH_BUFFER_SIZE = 1024 * 1024
        private const val DEFAULT_MIN_TOKENS = 16
        private const val DEFAULT_WARMUP_MAX_TOKENS = 8
        private const val MAX_ATTEMPTS_PER_RUN = 3
        private const val MIN_RETRY_MAX_TOKENS = 16
        private val SUPPORTED_MODELS = ModelCatalog.bridgeSupportedModels().toSet()
    }
}

private data class BenchmarkRuntimeOptions(
    val performanceConfig: PerformanceRuntimeConfig,
    val disableTools: Boolean,
    val showThinking: Boolean,
)

private enum class BenchmarkSessionMode(val wireValue: String) {
    ISOLATED("isolated"),
    SHARED("shared"),
}

private enum class BenchmarkPromptKind {
    ISOLATED,
    PRIMER,
    FOLLOW_UP,
}

private object DisabledBenchmarkToolModule : ToolModule {
    override fun listEnabledTools(): List<String> = emptyList()
    override fun validateToolCall(call: ToolCall): Boolean = false
    override fun executeToolCall(call: ToolCall): ToolResult = ToolResult(success = false, content = "disabled")
}

private object ToolModuleDefaults {
    val default: ToolModule = com.pocketagent.tools.SafeLocalToolRuntime()
}
