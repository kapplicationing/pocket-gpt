package com.pocketagent.android.audit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Static source audits enforcing the PocketGPT performance contract.
 *
 * These tests are the cheapest tier of the performance contract — they fire on every
 * `bash scripts/dev/test.sh fast` and prevent the most common regressions that were
 * root-caused during the 2026-05-02 jank investigation:
 *
 *  - Hot-path code touching disk/preferences synchronously
 *  - Compose root code re-collecting the god-object [ChatUiState]
 *  - UI state classes shipping without [@Immutable] or stability-config coverage
 *  - Custom property getters on [@Immutable] data classes (silently break strong skipping)
 *  - Composer text fields using the String overload of OutlinedTextField (forces IME re-sync)
 *  - perf-baseline.sh accidentally measuring the debug build
 *
 * Each new contract rule must come with a test here; see
 * [docs/architecture/android-performance-contract.md](../../../../../../../../../docs/architecture/android-performance-contract.md).
 */
@Suppress("MaxLineLength")
class PerformanceContractAuditTest {
    @Test
    fun `ui files do not construct preference or file-system hot paths`() {
        val offenders = sourceFiles("src/main/kotlin/com/pocketagent/android/ui")
            .flatMap { file ->
                val source = file.readText()
                forbiddenUiHotPathPatterns
                    .filter { pattern -> pattern in source }
                    .map { pattern -> "${file.relativePath()}: $pattern" }
            }

        assertTrue(
            offenders.isEmpty(),
            "UI files must not touch disk or SharedPreferences directly. Offenders: $offenders",
        )
    }

    @Test
    fun `ui files call async provisioning actions instead of sync disk backed methods`() {
        val allowedFiles = setOf(
            "ModelProvisioningViewModel.kt",
            "ModelLibraryActions.kt",
        )
        val offenders = sourceFiles("src/main/kotlin/com/pocketagent/android/ui")
            .filterNot { file -> file.name in allowedFiles }
            .flatMap { file ->
                val source = file.readText()
                forbiddenSyncProvisioningCalls
                    .findAll(source)
                    .map { match -> "${file.relativePath()}: ${match.value}" }
                    .toList()
            }

        assertTrue(
            offenders.isEmpty(),
            "UI event paths must call suspend/Async provisioning APIs so disk, prefs, scheduler, and admission work stay off main. " +
                "Offenders:\n${offenders.joinToString("\n")}",
        )
    }

    @Test
    fun `composer dock observes thinking boolean instead of full active session`() {
        val source = chatAppSourceFile().readText()
        val composerDock = source.substringAfter("private fun ChatComposerDock(")
            .substringBefore("@Composable\nprivate fun SessionDrawerHost")

        assertTrue(
            "activeSessionFlow.collectAsState()" !in composerDock,
            "ChatComposerDock must not observe activeSessionFlow; expose a narrow thinking-enabled flow instead.",
        )
    }

    @Test
    fun `shell compose keeps full ui state collection behind visibility gates`() {
        val source = chatAppSourceFile().readText()
        val rootBody = source.substringAfter("fun PocketAgentApp(")
            .substringBefore("@Composable\nprivate fun ChatComposerDock")

        assertTrue(
            "viewModel.uiState.collectAsState()" !in rootBody &&
                "provisioningViewModel.uiState.collectAsState()" !in rootBody,
            "PocketAgentApp root must observe narrow flows only; full uiState belongs in visibility-gated hosts.",
        )
    }

    @Test
    fun `model library host keeps full provisioning state behind modal visibility gate`() {
        val source = resolveAppSource("src/main/kotlin/com/pocketagent/android/ui/ModelLibrarySheetHost.kt").readText()
        val beforeVisibilityReturn = source.substringBefore("if (activeSurface !is ModalSurface.ModelLibrary)")
        val betweenGateAndCollect = source.substringAfter("if (activeSurface !is ModalSurface.ModelLibrary)")
            .substringBefore("provisioningViewModel.uiState.collectAsState()")

        assertTrue(
            "uiState.collectAsState()" !in beforeVisibilityReturn && "return" in betweenGateAndCollect,
            "ModelLibrarySheetHost may collect full provisioning uiState only after the ModelLibrary visibility gate.",
        )
    }

    @Test
    fun `runtime sync dependency wrappers are guarded`() {
        val source = resolveAppSource("src/main/kotlin/com/pocketagent/android/AppRuntimeDependencies.kt").readText()
        val requiredGuards = listOf(
            "AppRuntimeDependencies.currentProvisioningSnapshot",
            "AppRuntimeDependencies.listInstalledVersions",
            "AppRuntimeDependencies.setActiveVersion",
            "AppRuntimeDependencies.clearActiveVersion",
            "AppRuntimeDependencies.removeVersion",
            "AppRuntimeDependencies.storageSummary",
        )
        val missing = requiredGuards.filterNot { guard ->
            "MainThreadGuard.assertNotMainThread(\"$guard\")" in source
        }

        assertTrue(
            missing.isEmpty(),
            "Public sync runtime/provisioning wrappers must fail fast if reached from main. Missing guards: $missing",
        )
    }

    @Test
    fun `provisioning storage summary cache has guard and mutation invalidation`() {
        val source = resolveAppSource(
            "src/main/kotlin/com/pocketagent/android/runtime/AndroidRuntimeProvisioningStore.kt",
        ).readText()
        val storageSummaryBody = source.substringAfter("fun storageSummary()")
            .substringBefore("\n    fun runtimeConfig()")
        val installDownloadedBody = source.substringAfter("fun installDownloadedModel(")
            .substringBefore("\n    fun managedModelDirectory()")
        val removeVersionBody = source.substringAfter("fun removeVersion(modelId: String, version: String)")
            .substringBefore("\n    private fun deleteInstalledArtifactsIfOrphaned")
        val upsertBody = source.substringAfter("private fun upsertInstalledVersion(")
            .substringBefore("\n    private fun invalidateStorageSummaryCache()")

        assertTrue(
            "MainThreadGuard.assertNotMainThread(\"AndroidRuntimeProvisioningStore.storageSummary\")" in storageSummaryBody,
            "storageSummary cache misses can walk model/download files and must be guarded off main.",
        )
        assertTrue(
            "invalidateStorageSummaryCache()" in installDownloadedBody &&
                "invalidateStorageSummaryCache()" in removeVersionBody &&
                "invalidateStorageSummaryCache()" in upsertBody,
            "Model install/import/remove mutations must invalidate storageSummary instead of relying on TTL expiry.",
        )
    }

    @Test
    fun `chat ui state does not expose activeSession as a property getter`() {
        val source = chatUiStateSourceFile().readText()
        // We allow comments containing the phrase, but must not have "val activeSession:" with a getter.
        val hasPropertyGetter = Regex(
            """val\s+activeSession\s*:\s*ChatSessionUiModel\?\s*\n\s*get\s*\(\s*\)""",
        ).containsMatchIn(source)
        assertTrue(
            !hasPropertyGetter,
            "ChatUiState must not declare an `activeSession` property getter. Use the `activeSession()` extension function instead. " +
                "A custom getter on @Immutable runs O(N) on every access and breaks strong-skipping invariants. " +
                "See the 2026-05-02 RCA in docs/architecture/android-performance-contract.md.",
        )
    }

    @Test
    fun `composer text field uses TextFieldValue overload not String overload`() {
        assertHotTextFieldUsesLocalValue(
            file = composerSourceFile(),
            functionName = "ComposerInputRow",
            valueName = "fieldValue",
            label = "composer",
        )
    }

    @Test
    fun `settings and search hot text fields use TextFieldValue locally`() {
        val hotFields = listOf(
            HotTextFieldContract("CompletionSettingsSheet.kt", "CompletionSettingsSheet", "systemPrompt", "system prompt"),
            HotTextFieldContract("SessionDrawer.kt", "SessionDrawer", "searchQueryValue", "session search"),
            HotTextFieldContract("ModelSheet.kt", "ModelSheet", "searchQueryValue", "model search"),
            HotTextFieldContract("ModelSheet.kt", "HuggingFaceAcquisitionSection", "inputFieldValue", "model URL"),
            HotTextFieldContract("ModelSheet.kt", "HuggingFaceSearchSection", "queryFieldValue", "Hugging Face search"),
        )

        val offenders = hotFields.mapNotNull { contract ->
            hotTextFieldViolation(contract)
        }

        assertTrue(
            offenders.isEmpty(),
            "High-frequency settings/search text fields must keep local TextFieldValue state. Offenders:\n${offenders.joinToString("\n")}",
        )
    }

    private fun assertHotTextFieldUsesLocalValue(
        file: File,
        functionName: String,
        valueName: String,
        label: String,
    ) {
        val violation = hotTextFieldViolation(
            HotTextFieldContract(file.name, functionName, valueName, label),
        )
        assertTrue(
            violation == null,
            violation ?: "expected $label to satisfy the local TextFieldValue contract",
        )
    }

    private fun hotTextFieldViolation(contract: HotTextFieldContract): String? {
        val file = resolveAppSource("src/main/kotlin/com/pocketagent/android/ui/${contract.fileName}")
        val functionSource = composableFunctionSource(file, contract.functionName)
        val localValue = Regex(
            """(?:var|val)\s+${Regex.escape(contract.valueName)}\s+by\s+remember[\s\S]{0,240}?TextFieldValue\s*\(""",
        ).containsMatchIn(functionSource)
        val exactBinding = Regex(
            """OutlinedTextField\s*\(\s*value\s*=\s*${Regex.escape(contract.valueName)}\s*,""",
        ).containsMatchIn(functionSource)
        return if (localValue && exactBinding) {
            null
        } else {
            "${file.relativePath()}: ${contract.functionName} ${contract.label} must bind its own local " +
                "TextFieldValue `${contract.valueName}` to OutlinedTextField"
        }
    }

    private fun composableFunctionSource(file: File, functionName: String): String {
        val source = file.readText()
        val marker = "fun $functionName("
        val start = source.indexOf(marker)
        assertTrue(start >= 0, "Could not find $marker in ${file.relativePath()}")
        val remainder = source.substring(start)
        val nextComposable = remainder.indexOf("\n@Composable", startIndex = marker.length)
        return if (nextComposable >= 0) remainder.substring(0, nextComposable) else remainder
    }

    @Test
    fun `completion settings system prompt does not commit on every keystroke`() {
        val file = resolveAppSource("src/main/kotlin/com/pocketagent/android/ui/CompletionSettingsSheet.kt")
        val source = file.readText()
        val sheetSource = composableFunctionSource(file, "CompletionSettingsSheet")
        val systemPromptField = sheetSource
            .substringAfter("item(key = \"completion_system_prompt\")")
            .substringBefore("item(key = \"completion_common\")")

        assertTrue(
            "OutlinedTextField(" in systemPromptField &&
                "onValueChange = { systemPrompt = it }" in systemPromptField &&
                "emitUpdate()" !in systemPromptField &&
                "onSettingsChanged(" !in systemPromptField,
            "Completion system prompt typing must stay compose-local; commit on focus loss, Done, reset, dismiss, or slider boundaries.",
        )
        assertTrue(
            "DisposableEffect(Unit)" in source && "commitSystemPromptIfChanged()" in source,
            "Completion system prompt must flush the local TextFieldValue buffer when the sheet is dismissed.",
        )
    }

    @Test
    fun `download transitions do not refresh provisioning snapshot on progress ticks`() {
        val source = resolveAppSource("src/main/kotlin/com/pocketagent/android/ui/DownloadTransitionHandler.kt").readText()
        val effectBody = source.substringAfter("LaunchedEffect(downloads) {")
            .substringBefore("val transitionFeedback")

        assertTrue(
            "shouldRefreshProvisioningSnapshotOnTransition()" in effectBody,
            "DownloadTransitionHandler must gate snapshot refresh on meaningful status transitions, not every downloads emission.",
        )
        assertTrue(
            "onRefreshSnapshot()\n        val transitioned" !in source,
            "DownloadTransitionHandler must not refresh the full provisioning snapshot before checking for a status transition.",
        )
    }

    @Test
    fun `every UI state class is annotated Immutable or appears in stability config`() {
        val stabilityNames = stabilityConfigClasses()
        val offenders = sourceFiles("src/main/kotlin/com/pocketagent/android/ui/state")
            .flatMap { file ->
                val source = file.readText()
                Regex("""(?m)^\s*(?:internal\s+|public\s+)?data class (\w+)""")
                    .findAll(source)
                    .mapNotNull { match ->
                        val name = match.groupValues[1]
                        val matchStart = match.range.first
                        val window = source.substring(maxOf(0, matchStart - 80), matchStart)
                        val isImmutable = "@Immutable" in window || "@Stable" in window
                        val fqName = "com.pocketagent.android.ui.state.$name"
                        if (isImmutable || fqName in stabilityNames) {
                            null
                        } else {
                            "${file.relativePath()}: $name (add @Immutable, or list $fqName in compose-stability.conf)"
                        }
                    }
                    .toList()
            }
        assertTrue(
            offenders.isEmpty(),
            "Every UI state data class must be @Immutable or listed in compose-stability.conf. " +
                "Unannotated state types defeat Compose strong-skipping and cause silent recompose regressions. " +
                "Offenders:\n${offenders.joinToString("\n")}",
        )
    }

    @Test
    fun `perf baseline script refuses to measure debug builds`() {
        val script = perfBaselineScript().readText()
        assertTrue(
            "DEBUGGABLE" in script && "ALLOW_DEBUGGABLE" in script,
            "scripts/dev/perf-baseline.sh must explicitly check for DEBUGGABLE builds and require an opt-in flag. " +
                "Debug builds carry a 30-50% Compose recompose tax that swamps any real signal — measuring them produces " +
                "misleading numbers. See the 2026-05-02 RCA.",
        )
        assertTrue(
            "assembleBenchmark" in script,
            "scripts/dev/perf-baseline.sh must build the `benchmark` variant (assembleBenchmark), not assembleDebug.",
        )
    }

    @Test
    fun `compose hotpath evidence is fresh benchmark scoped and fail closed`() {
        val buildScript = resolveAppSource("build.gradle.kts").readText()
        val reportScript = resolveRepoSource("scripts/dev/compose-report-hotpath.sh").readText()
        val validator = resolveRepoSource("scripts/dev/validate_compose_hotpath_reports.py").readText()

        assertTrue(
            "pocketgpt.composeReportVariant" in buildScript &&
                "compose-reports/\$composeReportVariant" in buildScript &&
                "compose-metrics/\$composeReportVariant" in buildScript,
            "Compose compiler outputs must be isolated by an explicit report variant.",
        )
        assertTrue(
            "compileBenchmarkKotlin" in reportScript &&
                "--rerun-tasks" in reportScript &&
                "validate_compose_hotpath_reports.py" in reportScript,
            "The hotpath helper must regenerate benchmark reports and validate the resulting bundle.",
        )
        val expectedNames = listOf(
            "PocketAgentApp",
            "ComposerInputRow",
            "ModelSheet",
            "SessionDrawer",
            "CompletionSettingsSheet",
        )
        val expectedSkippableNames = listOf(
            "GeneralTabContent",
            "PerformanceSettingsSection",
            "DownloadSettingsSection",
            "KeepAliveSettingsSection",
            "VoiceSettingsSection",
            "ReasoningSettingsSection",
            "CompletionCommonSettingsSection",
            "CompletionThinkingSection",
            "CompletionAdvancedSettingsSection",
        )
        assertTrue(
            expectedNames.all { name -> name in reportScript } &&
                expectedSkippableNames.all { name -> name in reportScript } &&
                "totalComposables" in validator &&
                "stale" in validator &&
                "expected exactly one" in validator &&
                "expected_skippable_composables" in validator &&
                "restartable and skippable" in validator,
            "Compose evidence must reject missing or non-skippable hot boundaries and empty, stale, or incomplete metrics.",
        )
    }

    @Test
    fun `interaction perf evidence records device state and declared workload conditions`() {
        val script = resolveRepoSource("scripts/dev/perf-interaction.sh").readText()
        val gate = resolveRepoSource("scripts/dev/perf-interaction-gate.sh").readText()
        val harness = resolveRepoSource("scripts/dev/android_perf_harness.py").readText()
        val evaluator = resolveRepoSource(
            "scripts/benchmarks/evaluate_android_frame_thresholds.py",
        ).readText()
        val requiredScriptEvidence = listOf(
            "device-properties.txt",
            "display-\${phase}.txt",
            "thermal-\${phase}.txt",
            "package-dump.txt",
            "--runtime-state",
            "--download-state",
            "--voice-state",
            "assert-scenario-final",
            "assert-package-stable",
            "window-focus-after.txt",
            "window-focus-post-gfxinfo.txt",
            "TOTAL_FRAMES",
        )
        val requiredEvaluationFields = listOf(
            "total_frames",
            "refresh_rate_hz",
            "thermal_status_before",
            "compilation_filter",
            "workload_condition_source",
            "declared_runtime_condition",
            "declared_download_condition",
            "declared_voice_condition",
        )

        assertTrue(
            requiredScriptEvidence.all { evidence -> evidence in script },
            "Performance samples must capture external device state and declare workload conditions.",
        )
        assertTrue(
            requiredEvaluationFields.all { field -> field in evaluator },
            "Frame-threshold evaluation must reject incomplete or mixed device/workload evidence.",
        )
        assertTrue(
            "android.widget.EditText" !in script &&
                "PACKAGE_QUALIFIED_ID_PATTERN" in harness &&
                "resource_id == expected" in harness,
            "Interaction selectors must use exact resource IDs without a generic EditText fallback.",
        )
        assertTrue(
            "perf_lock_acquire" in gate &&
                "--lock-token" in gate &&
                "perf_lock_validate" in script &&
                "trap cleanup EXIT" in gate &&
                "trap cleanup EXIT" in script,
            "The three-sample gate must own one device/package lease and each child must validate its token.",
        )
    }

    @Test
    fun `streaming hot path never copies the full response for each delta`() {
        val facade = resolveRepoSource(
            "packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/MvpRuntimeFacade.kt",
        ).readText()
        val deltaContract = facade.substringAfter("data class Delta(")
            .substringBefore(") : ChatStreamEvent")
        val streamChat = facade.substringAfter("override fun streamChat(")
            .substringBefore("override fun cancelGeneration(")
        val executor = resolveRepoSource(
            "packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/InferenceExecutor.kt",
        ).readText()
        val reducer = resolveAppSource(
            "src/main/kotlin/com/pocketagent/android/ui/state/StreamStateReducer.kt",
        ).readText()
        val reduceDelta = reducer.substringAfter("private fun reduceDelta(")
            .substringBefore("fun onFailure(")

        assertTrue(
            "accumulatedText" !in deltaContract,
            "ChatStreamEvent.Delta must carry delta-only text; a full accumulated prefix makes streaming O(n^2).",
        )
        assertTrue(
            "textBuilder.toString()" !in streamChat,
            "DefaultMvpRuntimeFacade must not materialize the full response for every emitted token.",
        )
        assertTrue(
            "val projected = streamedText.toString()" !in executor,
            "InferenceExecutor stop matching must inspect a bounded suffix without copying the full response per token.",
        )
        assertTrue(
            "textAccumulator.append(delta.text)" in reduceDelta && "snapshotText()" !in reduceDelta,
            "StreamStateReducer must append deltas to its amortized buffer without materializing the response per event.",
        )
    }

    private fun stabilityConfigClasses(): Set<String> {
        val file = listOf(
            File("compose-stability.conf"),
            File("apps/mobile-android/compose-stability.conf"),
        ).first { it.exists() }
        return file.readLines()
            .map { it.substringBefore("//").trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun sourceFiles(relativeRoot: String): List<File> {
        val root = listOf(
            File(relativeRoot),
            File("apps/mobile-android/$relativeRoot"),
        ).first { it.exists() }
        return root.walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .toList()
    }

    private fun chatAppSourceFile(): File =
        resolveAppSource("src/main/kotlin/com/pocketagent/android/ui/ChatApp.kt")

    private fun composerSourceFile(): File =
        resolveAppSource("src/main/kotlin/com/pocketagent/android/ui/ChatComposerBar.kt")

    private fun chatUiStateSourceFile(): File =
        resolveAppSource("src/main/kotlin/com/pocketagent/android/ui/state/ChatUiState.kt")

    private fun perfBaselineScript(): File = listOf(
        File("scripts/dev/perf-baseline.sh"),
        File("../../scripts/dev/perf-baseline.sh"),
        File("../../../scripts/dev/perf-baseline.sh"),
    ).firstOrNull { it.exists() }
        ?: error(
            "Could not locate scripts/dev/perf-baseline.sh from working directory ${File(".").canonicalPath}",
        )

    private fun resolveAppSource(relativePath: String): File = listOf(
        File(relativePath),
        File("apps/mobile-android/$relativePath"),
    ).first { it.exists() }

    private fun resolveRepoSource(relativePath: String): File = listOf(
        File(relativePath),
        File("../$relativePath"),
        File("../../$relativePath"),
        File("../../../$relativePath"),
    ).firstOrNull { it.exists() }
        ?: error("Could not locate $relativePath from working directory ${File(".").canonicalPath}")

    private fun File.relativePath(): String = invariantSeparatorsPath

    private companion object {
        data class HotTextFieldContract(
            val fileName: String,
            val functionName: String,
            val valueName: String,
            val label: String,
        )

        val forbiddenUiHotPathPatterns = listOf(
            "getSharedPreferences(",
            ".readText(",
            ".writeText(",
            "getExternalFilesDir(",
            "runBlocking",
        )
        val forbiddenSyncProvisioningCalls = Regex(
            """(?:provisioningViewModel|gateway|AppRuntimeDependencies)\.(?:listInstalledVersions|setActiveVersion|clearActiveVersion|removeVersion|shouldWarnForMeteredLargeDownload|setDownloadWifiOnlyEnabled|acknowledgeLargeDownloadCellularWarning|pauseDownload|resumeDownload|retryDownload|cancelDownload|refreshDownloads)\s*\(""",
        )
    }
}
