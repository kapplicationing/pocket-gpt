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
        val profileHelper = resolveRepoSource("scripts/dev/android-benchmark-profile.sh").readText()
        val buildScript = resolveAppSource("build.gradle.kts").readText()
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
        assertTrue(
            "applicationIdSuffix = \".benchmark\"" in buildScript &&
                "POCKETGPT_BENCHMARK_PACKAGE=\"com.pocketagent.android.benchmark\"" in profileHelper &&
                "pocketgpt_require_isolated_benchmark_package" in script &&
                "perf_lock_acquire" in script &&
                "pocketgpt_uninstall_isolated_benchmark" in script &&
                "\$PACKAGE/.MainActivity" !in script &&
                script.indexOf("pocketgpt_require_isolated_benchmark_package") <
                script.indexOf("if [[ \"\$DO_BUILD\""),
            "Benchmark measurement must use the isolated package, full activity class, one device lease, and owned cleanup.",
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
        val profileHelper = resolveRepoSource("scripts/dev/android-benchmark-profile.sh").readText()
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
            "assert-appended-probe",
            "assert-restored-text",
            "assert-package-stable",
            "settings-prompt-append-proof.json",
            "settings-prompt-restoration-proof.json",
            "final-ui-selectors.json",
            "window-focus-\${phase}.txt",
            "window-focus-\${phase}.json",
            "window-focus-after.txt",
            "window-focus-post-gfxinfo.txt",
            "TOTAL_FRAMES",
        )
        val requiredEvaluationFields = listOf(
            "total_frames",
            "refresh_rate_hz_before",
            "refresh_rate_hz_after",
            "thermal_status_before",
            "compilation_filter",
            "baseline_profile_packaged",
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
        assertTrue(
            "-n \"\$receiver_component\"" in profileHelper &&
                "--expected 10" in profileHelper &&
                "--expected 1" in profileHelper &&
                "-f -m speed-profile" in profileHelper &&
                "pocketgpt_uninstall_isolated_benchmark" in gate &&
                "pocketgpt_require_isolated_benchmark_package \"\$PACKAGE\"" in script &&
                script.indexOf("pocketgpt_require_isolated_benchmark_package") <
                script.indexOf("if [[ -z \"\$OUT_DIR\" ]]") &&
                "\$PACKAGE/.MainActivity" !in script,
            "Acceptance evidence must explicitly activate the packaged profile and clean up only the isolated benchmark app.",
        )
    }

    @Test
    fun rawBenchmarkTapsUseExactUiAutomatorCenters() {
        val interaction = resolveRepoSource("scripts/dev/perf-interaction.sh").readText()
        val typing = resolveRepoSource("scripts/dev/perf-baseline.sh").readText()
        val harness = resolveRepoSource("scripts/dev/android_perf_harness.py").readText()
        val tapTag = interaction.substringAfter("tap_tag() {").substringBefore("\n}")

        assertTrue(
            "translated-center" !in harness &&
                "translated-center" !in interaction &&
                "translated-center" !in typing &&
                "TAP_GEOMETRY_WINDOW" !in interaction &&
                "TAP_GEOMETRY_WINDOW" !in typing &&
                "tag_center_from_dump \"\$tag\"" in tapTag &&
                "tag_center_from_dump \"onboarding_skip\"" in interaction &&
                "tag_center_from_dump \"onboarding_skip\"" in typing &&
                "tag_center_from_dump \"composer_input\"" in typing &&
                "find_node_center" in harness &&
                "composer_center_from_dump" !in typing,
            "Raw benchmark taps must use exact screen-global UIAutomator node centers without app-viewport translation.",
        )
    }

    @Test
    fun freshInstallSetupDismissesOnboardingBeforeAcceptingShell() {
        val setupFunctions = listOf(
            resolveRepoSource("scripts/dev/perf-interaction.sh").readText(),
            resolveRepoSource("scripts/dev/perf-baseline.sh").readText(),
        ).map { source ->
            source.substringAfter("prepare_fresh_install_state() {")
                .substringBefore("\n}\n")
        }
        val orderedSteps = listOf(
            "tag_center_from_dump \"onboarding_skip\"",
            "adb_shell input tap",
            "onboarding_dismissed=true",
            "sleep 1",
            "continue",
            "tag_center_from_dump \"session_drawer_button\"",
            "first-visible-activity-setup.json",
            "return 0",
        )

        assertTrue(
            setupFunctions.all { setup ->
                val offsets = orderedSteps.map(setup::indexOf)
                offsets.all { offset -> offset >= 0 } &&
                    offsets.zipWithNext().all { (current, next) -> current < next }
            },
            "Fresh-install setup must dismiss and leave onboarding before a later dump can prove the underlying shell and record readiness.",
        )
    }

    @Test
    fun requiredUiDumpProofsRetryBoundedlyAndStayRedacted() {
        val script = resolveRepoSource("scripts/dev/perf-interaction.sh").readText()
        val retry = script.substringAfter("require_ui_dump() {")
            .substringBefore("\n}\n")
        val requiredContract = listOf(
            "precondition-clear-before|precondition-clear-after",
            "scenario-clear-before|scenario-clear-after",
            "settings-original|scenario-final|settings-restoration",
            "invalid required UI dump phase",
            "for attempt in 1 2 3",
            "if (( attempt < 3 ))",
            "ui-dump-\$phase-attempts.jsonl",
            ": >\"\$evidence_file\" || return 1",
            "\"attempt\":%d,\"status\":\"success\"",
            "\"attempt\":%d,\"status\":\"failed\"",
            "require_ui_dump \"\$phase_prefix-before\"",
            "require_ui_dump \"\$phase_prefix-after\"",
            "require_ui_dump \"settings-original\"",
            "require_ui_dump \"scenario-final\"",
            "require_ui_dump \"settings-restoration\"",
        )

        assertTrue(
            requiredContract.all { evidence -> evidence in script } &&
                retry.indexOf("for attempt in 1 2 3") < retry.lastIndexOf("return 1") &&
                "cp \"\$LOCAL_UI_XML\"" !in retry &&
                "cat \"\$LOCAL_UI_XML\"" !in retry,
            "Mandatory UI proof dumps must retry exactly three times, record only phase/attempt/status metadata, validate phases, and fail closed without retaining raw UI.",
        )
    }

    @Test
    fun `native benchmark CI proves application id native library and baseline profile`() {
        val workflow = resolveRepoSource(".github/workflows/ci.yml").readText()
        val filters = workflow.substringAfter("filters: |")
            .substringBefore("lifecycle-risk:")
        val androidRuntimeFilter = filters.substringAfter("android_runtime:")
            .substringBefore("android_instrumented:")
        val job = workflow.substringAfter("native-build-package-check:")
            .substringBefore("android-instrumented-smoke:")

        assertTrue(
            "apps/mobile-android-baselineprofile/**" in androidRuntimeFilter &&
                "assembleBenchmark" in job &&
                job.split("assembleBenchmark").size == 2 &&
                "lib/arm64-v8a/libpocket_llama.so" in job &&
                "assert-application-id" in job &&
                "com.pocketagent.android.benchmark" in job &&
                "verify_android_baseline_profile.py" in job &&
                "compileBenchmarkArtProfile/baseline-prof.txt" in job &&
                "baseline-profile.sh verify" !in job,
            "Baseline Profile producer changes must trigger the existing native benchmark build, which is verified in place for isolation, native packaging, and merged profile rules.",
        )
    }

    @Test
    fun `settings performance probe is reversible fail closed and redacted`() {
        val script = resolveRepoSource("scripts/dev/perf-interaction.sh").readText()
        val chatApp = chatAppSourceFile().readText()
        val baselineProfileGenerator = resolveRepoSource(
            "apps/mobile-android-baselineprofile/src/main/kotlin/" +
                "com/pocketagent/android/baselineprofile/BaselineProfileGenerator.kt",
        ).readText()
        val cleanupContract = script.substringAfter("cleanup() {")
            .substringBefore("trap cleanup EXIT")
        val settingsJourney = script.substringAfter("adb_shell dumpsys gfxinfo \"\$PACKAGE\" reset")
            .substringAfter("settings-nav)")
            .substringBefore("model-sheet)")
        val measuredCloseout = script.substringAfter("RAW_DUMP=\"\$OUT_DIR/gfxinfo.txt\"")
            .substringBefore("JANKY=\"")
        val transitionHandshakes = listOf(
            "tap_tag \"advanced_sheet_button\"",
            "wait_tag \"advanced_settings_sheet\"",
            "adb_shell input swipe",
            "adb_shell input keyevent KEYCODE_BACK",
            "wait_for_app_foreground \"settings-after-advanced-back\"",
            "wait_tag \"completion_settings_button\"",
            "tap_tag \"completion_settings_button\"",
        )
        val handshakeOffsets = transitionHandshakes.map(settingsJourney::indexOf)

        val cleanupEvidence = listOf(
            "SETTINGS_PROMPT_MUTATION_PENDING",
            "SETTINGS_PROMPT_RESTORATION_VERIFIED",
            "am force-stop",
        )
        val privacyEvidence = listOf(
            "SETTINGS_PROMPT_BEFORE_XML",
            "SETTINGS_PROMPT_RESTORED_XML",
            "restore_settings_prompt",
            "redact-ui",
        )
        assertTrue(
            cleanupEvidence.all { evidence -> evidence in cleanupContract } &&
                privacyEvidence.all { evidence -> evidence in script } &&
                "final-ui.xml" !in script &&
                "last-ui.xml" !in script,
            "The settings probe must restore exactly, fail closed, and retain only redacted evidence.",
        )
        assertTrue(
            "clear_text_field" !in settingsJourney &&
                "SETTINGS_PROMPT_BEFORE_XML" in settingsJourney &&
                "KEYCODE_MOVE_END" in settingsJourney &&
                settingsJourney.indexOf("SETTINGS_PROMPT_MUTATION_PENDING=1") <
                settingsJourney.indexOf("type_text_slowly"),
            "Settings measurement must append only after capturing the original and arming fail-closed cleanup.",
        )
        assertTrue(
            "modifier = Modifier.testTag(\"advanced_settings_sheet\")" in chatApp &&
                "openAndCloseSurface(\"advanced_sheet_button\", \"advanced_settings_sheet\")" in
                baselineProfileGenerator &&
                "By.text(" !in baselineProfileGenerator,
            "Advanced settings automation must wait on a locale-neutral modal resource, not visible text.",
        )
        assertTrue(
            handshakeOffsets.all { offset -> offset >= 0 } &&
                handshakeOffsets.zipWithNext().all { (current, next) -> current < next } &&
                "window-focus-\${phase}.txt" in script &&
                "window-focus-\${phase}.json" in script,
            "The settings probe must prove modal open, app foreground after dismissal, and the next target before tapping it.",
        )
        assertTrue(
            measuredCloseout.indexOf("gfxinfo_dump") < measuredCloseout.indexOf("restore_settings_prompt"),
            "Settings restoration must run only after the measured gfxinfo snapshot is closed.",
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
