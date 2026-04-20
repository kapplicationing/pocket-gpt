package com.pocketagent.android.ui.controllers

import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.StartupProbeState
import com.pocketagent.android.ui.state.UiError
import com.pocketagent.android.ui.state.UiErrorMapper
import com.pocketagent.inference.ModelRuntimeProfile
import com.pocketagent.runtime.ModelRegistry

data class StartupReadinessDecision(
    val startupProbeState: StartupProbeState,
    val modelRuntimeStatus: ModelRuntimeStatus,
    val modelStatusDetail: String,
    val startupWarnings: List<String>,
    val startupError: UiError?,
)

class StartupReadinessCoordinator(
    private val modelRegistry: ModelRegistry = ModelRegistry.default(),
    private val runtimeProfile: ModelRuntimeProfile = ModelRuntimeProfile.PROD,
) {
    fun decide(
        startupChecks: List<String>,
        runtimeBackend: String?,
        statusDetailOverride: String?,
    ): StartupReadinessDecision {
        val summary = summarizeStartupChecks(startupChecks)
        if (startupChecks.isEmpty()) {
            return StartupReadinessDecision(
                startupProbeState = StartupProbeState.READY,
                modelRuntimeStatus = ModelRuntimeStatus.READY,
                modelStatusDetail = statusDetailOverride ?: readyStatusDetail(runtimeBackend),
                startupWarnings = emptyList(),
                startupError = null,
            )
        }

        if (isStartupTimeoutOnlyFailure(summary)) {
            return StartupReadinessDecision(
                startupProbeState = StartupProbeState.BLOCKED_TIMEOUT,
                modelRuntimeStatus = ModelRuntimeStatus.NOT_READY,
                modelStatusDetail = "Startup checks timed out. Runtime readiness is unknown; refresh checks before sending.",
                startupWarnings = emptyList(),
                startupError = UiErrorMapper.startupFailure(startupChecks),
            )
        }

        if (isOptionalModelOnlyStartupFailure(summary)) {
            return StartupReadinessDecision(
                startupProbeState = StartupProbeState.READY,
                modelRuntimeStatus = ModelRuntimeStatus.READY,
                modelStatusDetail = optionalModelStatusDetail(summary),
                startupWarnings = startupChecks,
                startupError = null,
            )
        }

        return StartupReadinessDecision(
            startupProbeState = StartupProbeState.BLOCKED,
            modelRuntimeStatus = resolveModelStatusFromStartupChecks(summary, startupChecks.isEmpty()),
            modelStatusDetail = startupChecks.firstOrNull() ?: "Runtime startup checks failed.",
            startupWarnings = emptyList(),
            startupError = UiErrorMapper.startupFailure(startupChecks),
        )
    }

    private fun readyStatusDetail(runtimeBackend: String?): String {
        return if (runtimeBackend.isNullOrBlank()) {
            "Runtime model ready"
        } else {
            "Runtime model ready ($runtimeBackend)"
        }
    }

    private fun isStartupTimeoutOnlyFailure(summary: StartupCheckSummary): Boolean {
        return summary.timeoutOnly
    }

    private fun isOptionalModelOnlyStartupFailure(summary: StartupCheckSummary): Boolean {
        return summary.optionalOnly
    }

    private fun optionalModelStatusDetail(summary: StartupCheckSummary): String {
        val missing = summary.optionalModelIds
        val startupModelCount = modelRegistry.startupPolicy(profile = runtimeProfile).candidateModelIds.size.coerceAtLeast(1)
        val readyCount = (startupModelCount - missing.size).coerceAtLeast(1)
        return if (missing.isEmpty()) {
            "Runtime ready. Optional models are still being provisioned."
        } else {
            val preview = missing.sorted().take(3)
            val overflowCount = (missing.size - preview.size).coerceAtLeast(0)
            val suffix = if (overflowCount > 0) ", +$overflowCount more" else ""
            "$readyCount model ready, ${missing.size} optional model unavailable (${preview.joinToString(", ")}$suffix)."
        }
    }

    private fun resolveModelStatusFromStartupChecks(summary: StartupCheckSummary, startupChecksEmpty: Boolean): ModelRuntimeStatus {
        if (startupChecksEmpty || summary.optionalModelIds.isNotEmpty()) {
            return ModelRuntimeStatus.READY
        }
        return if (summary.hasBlockingSignals) {
            ModelRuntimeStatus.NOT_READY
        } else {
            ModelRuntimeStatus.ERROR
        }
    }

    private fun summarizeStartupChecks(startupChecks: List<String>): StartupCheckSummary {
        if (startupChecks.isEmpty()) {
            return StartupCheckSummary()
        }
        val normalizedChecks = startupChecks.map { it.lowercase() }
        val optionalOnly = normalizedChecks.all { check ->
            check.contains(OPTIONAL_MODEL_WARNING_MARKER)
        }
        val optionalModelIds = buildSet {
            startupChecks.forEachIndexed { index, check ->
                if (!normalizedChecks[index].contains(OPTIONAL_MODEL_WARNING_MARKER)) {
                    return@forEachIndexed
                }
                OPTIONAL_MODEL_ID_REGEX.find(check)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)?.let(::add)
            }
        }
        val timeoutOnly = normalizedChecks.all { check ->
            check.contains(STARTUP_TIMEOUT_MARKER) || check.contains(TIMED_OUT_MARKER)
        }
        val hasBlockingSignals = normalizedChecks.any { check ->
            check.contains(MISSING_RUNTIME_MODEL_MARKER) ||
                check.contains(ARTIFACT_VERIFICATION_FAILED_MARKER) ||
                check.contains(MODEL_ARTIFACT_CONFIG_MISSING_MARKER)
        }
        return StartupCheckSummary(
            timeoutOnly = timeoutOnly,
            optionalOnly = optionalOnly,
            optionalModelIds = optionalModelIds,
            hasBlockingSignals = hasBlockingSignals,
        )
    }

    private companion object {
        const val OPTIONAL_MODEL_WARNING_MARKER = "optional runtime model unavailable"
        const val STARTUP_TIMEOUT_MARKER = "startup checks timed out"
        const val TIMED_OUT_MARKER = "timed out"
        const val MISSING_RUNTIME_MODEL_MARKER = "missing runtime model"
        const val ARTIFACT_VERIFICATION_FAILED_MARKER = "artifact verification failed"
        const val MODEL_ARTIFACT_CONFIG_MISSING_MARKER = "model_artifact_config_missing"
        val OPTIONAL_MODEL_ID_REGEX = Regex(
            pattern = """optional runtime model unavailable:\s*([a-z0-9._-]+)""",
            option = RegexOption.IGNORE_CASE,
        )
    }
}

private data class StartupCheckSummary(
    val timeoutOnly: Boolean = false,
    val optionalOnly: Boolean = false,
    val optionalModelIds: Set<String> = emptySet(),
    val hasBlockingSignals: Boolean = false,
)
