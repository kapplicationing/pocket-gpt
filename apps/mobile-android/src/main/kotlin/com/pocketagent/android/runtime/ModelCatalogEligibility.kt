package com.pocketagent.android.runtime

import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.inference.ModelCatalog

enum class ModelSupportLevel {
    SUPPORTED,
    UNSUPPORTED,
}

enum class ModelEligibilityReason {
    NONE,
    RUNTIME_COMPATIBILITY_MISMATCH,
    MODEL_NOT_RUNTIME_ENABLED,
}

data class ModelVersionEligibility(
    val supportLevel: ModelSupportLevel,
    val catalogVisible: Boolean,
    val downloadAllowed: Boolean,
    val loadAllowed: Boolean,
    val reason: ModelEligibilityReason = ModelEligibilityReason.NONE,
    val technicalDetail: String? = null,
) {
    companion object {
        fun supported(): ModelVersionEligibility {
            return ModelVersionEligibility(
                supportLevel = ModelSupportLevel.SUPPORTED,
                catalogVisible = true,
                downloadAllowed = true,
                loadAllowed = true,
            )
        }

        fun unsupported(
            reason: ModelEligibilityReason,
            technicalDetail: String,
            catalogVisible: Boolean = false,
        ): ModelVersionEligibility {
            return ModelVersionEligibility(
                supportLevel = ModelSupportLevel.UNSUPPORTED,
                catalogVisible = catalogVisible,
                downloadAllowed = false,
                loadAllowed = false,
                reason = reason,
                technicalDetail = technicalDetail,
            )
        }

    }
}

data class ModelCatalogEligibilitySnapshot(
    val versionEligibilityByKey: Map<String, ModelVersionEligibility> = emptyMap(),
    val signals: ModelEligibilitySignals = ModelEligibilitySignals.assumeSupported(),
) {
    fun eligibilityFor(
        modelId: String,
        version: String,
    ): ModelVersionEligibility {
        return versionEligibilityByKey[modelVersionEligibilityKey(modelId, version)]
            ?: ModelVersionEligibility.supported()
    }
}

data class ModelEligibilitySignals(
    val runtimeCompatibilityTag: String,
    val runtimeSupportsGpuOffload: Boolean,
    val deviceAdvisory: DeviceGpuOffloadAdvisory,
    val gpuProbeResult: GpuProbeResult,
    val runtimeDiagnostics: RuntimeDiagnosticsSnapshot? = null,
) {
    fun backendCapability(family: RuntimeBackendFamily): RuntimeBackendCapability? {
        return runtimeDiagnostics?.backendCapability(family)
    }

    companion object {
        fun assumeSupported(
            runtimeCompatibilityTag: String = "android-arm64-v8a",
        ): ModelEligibilitySignals {
            return ModelEligibilitySignals(
                runtimeCompatibilityTag = runtimeCompatibilityTag,
                runtimeSupportsGpuOffload = true,
                deviceAdvisory = DeviceGpuOffloadAdvisory(),
                gpuProbeResult = GpuProbeResult(
                    status = GpuProbeStatus.QUALIFIED,
                    maxStableGpuLayers = 32,
                    detail = "assume_supported",
                ),
            )
        }
    }
}

interface ModelEligibilitySignalsProvider {
    fun currentSignals(): ModelEligibilitySignals

    companion object {
        val ASSUME_SUPPORTED = object : ModelEligibilitySignalsProvider {
            override fun currentSignals(): ModelEligibilitySignals = ModelEligibilitySignals.assumeSupported()
        }
    }
}

class AndroidModelEligibilitySignalsProvider(
    private val runtimeCompatibilityTag: String,
    private val deviceGpuOffloadSupport: DeviceGpuOffloadSupport,
    private val gpuOffloadQualifier: GpuOffloadQualifier,
    private val runtimeSupportProvider: () -> Boolean,
    private val runtimeDiagnosticsProvider: () -> RuntimeDiagnosticsSnapshot? = { null },
) : ModelEligibilitySignalsProvider {
    override fun currentSignals(): ModelEligibilitySignals {
        val advisory = deviceGpuOffloadSupport.advisory()
        val runtimeDiagnostics = runtimeDiagnosticsProvider()
        val runtimeSupported = runtimeDiagnostics?.nativeRuntimeSupported ?: runtimeSupportProvider()
        val probe = gpuOffloadQualifier.evaluate(
            runtimeSupported = runtimeSupported,
            deviceAdvisory = advisory,
        )
        return ModelEligibilitySignals(
            runtimeCompatibilityTag = runtimeCompatibilityTag,
            runtimeSupportsGpuOffload = runtimeSupported,
            deviceAdvisory = advisory,
            gpuProbeResult = probe,
            runtimeDiagnostics = runtimeDiagnostics,
        )
    }
}

data class ModelEligibilityCandidate(
    val modelId: String,
    val version: String? = null,
    val runtimeCompatibility: String = "",
)

interface ModelCatalogEligibilityEvaluator {
    fun evaluate(
        manifest: ModelDistributionManifest,
        snapshot: RuntimeProvisioningSnapshot?,
        signals: ModelEligibilitySignals,
    ): ModelCatalogEligibilitySnapshot

    fun evaluateCandidate(
        candidate: ModelEligibilityCandidate,
        signals: ModelEligibilitySignals,
    ): ModelVersionEligibility
}

class DefaultModelCatalogEligibilityEvaluator : ModelCatalogEligibilityEvaluator {
    override fun evaluate(
        manifest: ModelDistributionManifest,
        snapshot: RuntimeProvisioningSnapshot?,
        signals: ModelEligibilitySignals,
    ): ModelCatalogEligibilitySnapshot {
        val entries = linkedMapOf<String, ModelVersionEligibility>()
        manifest.models.forEach { model ->
            model.versions.forEach { version ->
                entries[modelVersionEligibilityKey(version.modelId, version.version)] = evaluateCandidate(
                    candidate = ModelEligibilityCandidate(
                        modelId = version.modelId,
                        version = version.version,
                        runtimeCompatibility = version.runtimeCompatibility,
                    ),
                    signals = signals,
                )
            }
        }
        snapshot?.models.orEmpty().forEach { model ->
            model.installedVersions.forEach { version ->
                val key = modelVersionEligibilityKey(version.modelId, version.version)
                entries[key] = evaluateCandidate(
                    candidate = ModelEligibilityCandidate(
                        modelId = version.modelId,
                        version = version.version,
                        runtimeCompatibility = version.runtimeCompatibility,
                    ),
                    signals = signals,
                )
            }
        }
        return ModelCatalogEligibilitySnapshot(
            versionEligibilityByKey = entries,
            signals = signals,
        )
    }

    override fun evaluateCandidate(
        candidate: ModelEligibilityCandidate,
        signals: ModelEligibilitySignals,
    ): ModelVersionEligibility {
        val modelId = candidate.modelId
        val version = candidate.version ?: "unknown"
        val runtimeCompatibility = candidate.runtimeCompatibility
        val descriptor = ModelCatalog.descriptorFor(modelId)
        if (descriptor != null && !descriptor.bridgeSupported) {
            return ModelVersionEligibility.unsupported(
                reason = ModelEligibilityReason.MODEL_NOT_RUNTIME_ENABLED,
                technicalDetail = "model=$modelId|bridge_supported=false",
            )
        }
        if (runtimeCompatibility.isNotBlank() && runtimeCompatibility != signals.runtimeCompatibilityTag) {
            return ModelVersionEligibility.unsupported(
                reason = ModelEligibilityReason.RUNTIME_COMPATIBILITY_MISMATCH,
                technicalDetail = "model=$modelId|version=$version|runtime=$runtimeCompatibility|expected=${signals.runtimeCompatibilityTag}",
            )
        }

        return ModelVersionEligibility.supported()
    }
}

internal fun modelVersionEligibilityKey(
    modelId: String,
    version: String,
): String = "$modelId::$version"
