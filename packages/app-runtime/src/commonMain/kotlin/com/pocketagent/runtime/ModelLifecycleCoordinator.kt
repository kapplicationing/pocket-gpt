package com.pocketagent.runtime

import com.pocketagent.core.RoutingMode
import com.pocketagent.inference.DeviceState
import com.pocketagent.inference.InferenceModule
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.inference.RoutingModule
import com.pocketagent.nativebridge.CachePolicy

internal class ModelLifecycleCoordinator(
    private val inferenceModule: InferenceModule,
    private val routingModule: RoutingModule,
    private val runtimeConfig: RuntimeConfig,
    private val residentModelIdProvider: () -> String? = { null },
    private val explicitModelIdResolver: (RoutingMode) -> String? = ModelCatalog::modelIdForRoutingMode,
    private val onWarning: (String) -> Unit = { },
) {
    fun selectRunnableModelId(
        routingMode: RoutingMode,
        taskType: String,
        deviceState: DeviceState,
    ): String {
        val preferredModelId = selectModelId(
            routingMode = routingMode,
            taskType = taskType,
            deviceState = deviceState,
        )
        val availableModels = inferenceModule.listAvailableModels().toSet()
        if (availableModels.isEmpty() || availableModels.contains(preferredModelId)) {
            return preferredModelId
        }
        val residentModelId = residentModelIdProvider()
        if (residentModelId != null && availableModels.contains(residentModelId)) {
            return residentModelId
        }
        return preferredModelOrder(availableModels).firstOrNull() ?: preferredModelId
    }

    fun preferredModelOrder(availableModels: Set<String>): List<String> {
        return buildList {
            addAll(ModelCatalog.preferredRuntimeOrderModelIds())
            addAll(availableModels.sorted())
        }.distinct().filter { availableModels.contains(it) }
    }

    fun resolveNativeCachePolicy(): CachePolicy {
        if (!runtimeConfig.prefixCacheEnabled) {
            return CachePolicy.OFF
        }
        return if (runtimeConfig.prefixCacheStrict) {
            CachePolicy.PREFIX_KV_REUSE_STRICT
        } else {
            CachePolicy.PREFIX_KV_REUSE
        }
    }

    private fun selectModelId(
        routingMode: RoutingMode,
        taskType: String,
        deviceState: DeviceState,
    ): String {
        if (routingMode == RoutingMode.AUTO) {
            return routingModule.selectModel(taskType, deviceState)
        }
        val explicitModelId = explicitModelIdResolver(routingMode)
        if (explicitModelId != null) {
            return explicitModelId
        }
        onWarning("RoutingMode $routingMode has no catalog binding; falling back to auto-routing")
        return routingModule.selectModel(taskType, deviceState)
    }
}
