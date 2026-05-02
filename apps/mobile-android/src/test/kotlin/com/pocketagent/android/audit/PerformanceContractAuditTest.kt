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
        val source = composerSourceFile().readText()
        val inputRow = source.substringAfter("private fun ComposerInputRow(")
            .substringBefore("\n}\n")
        // Must NOT use the String overload (value = text, onValueChange = onTextChanged).
        val usesStringOverload = Regex(
            """OutlinedTextField\s*\(\s*\n\s*value\s*=\s*text\s*,\s*\n\s*onValueChange\s*=\s*onTextChanged""",
        ).containsMatchIn(inputRow)
        assertTrue(
            !usesStringOverload,
            "ComposerInputRow must use OutlinedTextField with the TextFieldValue overload and a local mutableStateOf. " +
                "The String overload forces a TextFieldValue rebuild (selection + composition span) on every recomposition, " +
                "doubling the per-keystroke recompose cost and stalling the IME. See the 2026-05-02 RCA.",
        )
        assertTrue(
            "TextFieldValue" in inputRow,
            "ComposerInputRow must reference TextFieldValue locally; without it the composer reverts to the slow String overload.",
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

    private fun File.relativePath(): String = invariantSeparatorsPath

    private companion object {
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
