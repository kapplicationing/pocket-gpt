package com.pocketagent.inference

class AdaptiveRoutingPolicy : RoutingModule {
    override fun selectModel(taskType: String, deviceState: DeviceState): String {
        val task = taskType.trim().lowercase()
        val allCandidates = taskScopedCandidates(task)
        val candidates = allCandidates
            .filter { descriptor -> deviceState.ramClassGb >= descriptor.minRamGb }
            .ifEmpty { allCandidates }
        if (candidates.isEmpty()) {
            return ModelCatalog.QWEN3_0_6B_Q4_K_M
        }
        val pressure = resourcePressure(deviceState)
        if (pressure >= 2) {
            return selectBySpeed(candidates).modelId
        }
        if (pressure == 1) {
            val nonDebugCandidates = candidates.filterNot { descriptor -> descriptor.tier == ModelTier.DEBUG }
            return selectBySpeed(nonDebugCandidates.ifEmpty { candidates }).modelId
        }
        if (wantsQuality(taskType, deviceState)) {
            return selectByQuality(candidates).modelId
        }
        return selectBalanced(candidates).modelId
    }

    override fun selectContextBudget(taskType: String, deviceState: DeviceState): Int {
        val base = when (taskType) {
            "long_text" -> 8192
            "image" -> 4096
            else -> 4096
        }
        return when {
            deviceState.thermalLevel >= 7 -> 2048
            deviceState.batteryPercent < 20 -> 2048
            else -> base
        }
    }

    private fun resourcePressure(deviceState: DeviceState): Int {
        if (deviceState.thermalLevel >= 7 || deviceState.batteryPercent < 20) {
            return 2
        }
        if (deviceState.thermalLevel >= 5 || deviceState.batteryPercent < 35 || deviceState.ramClassGb < 8) {
            return 1
        }
        return 0
    }

    private fun wantsQuality(taskType: String, deviceState: DeviceState): Boolean {
        return when (taskType.trim().lowercase()) {
            "long_text", "reasoning" -> deviceState.ramClassGb >= 8
            "image" -> true
            else -> deviceState.ramClassGb >= 10 && deviceState.batteryPercent >= 50 && deviceState.thermalLevel <= 4
        }
    }

    private fun taskScopedCandidates(taskType: String): List<ModelDescriptor> {
        val candidates = ModelCatalog.autoRoutingCandidates(taskType)
        if (taskType == "image") {
            return candidates
        }
        val textFirstCandidates = candidates.filterNot { descriptor ->
            ModelCatalog.isVisionCapable(descriptor.modelId)
        }
        return textFirstCandidates.ifEmpty { candidates }
    }

    private fun selectBySpeed(candidates: List<ModelDescriptor>): ModelDescriptor {
        return candidates.sortedWith(
            compareByDescending<ModelDescriptor> { descriptor -> descriptor.speedRank }
                .thenByDescending { descriptor -> descriptor.qualityRank }
                .thenBy { descriptor -> descriptor.fallbackPriority },
        ).first()
    }

    private fun selectByQuality(candidates: List<ModelDescriptor>): ModelDescriptor {
        return candidates.sortedWith(
            compareByDescending<ModelDescriptor> { descriptor -> descriptor.qualityRank }
                .thenByDescending { descriptor -> descriptor.speedRank }
                .thenBy { descriptor -> descriptor.fallbackPriority },
        ).first()
    }

    private fun selectBalanced(candidates: List<ModelDescriptor>): ModelDescriptor {
        return candidates.minWith(
            compareBy<ModelDescriptor> { descriptor -> descriptor.fallbackPriority }
                .thenByDescending { descriptor -> descriptor.qualityRank }
                .thenByDescending { descriptor -> descriptor.speedRank },
        )
    }
}
