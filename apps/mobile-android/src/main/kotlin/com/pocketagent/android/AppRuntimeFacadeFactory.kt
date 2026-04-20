package com.pocketagent.android

import android.content.Context
import com.pocketagent.android.runtime.createDefaultAndroidInferenceModule
import com.pocketagent.android.voice.AndroidLocalToolRuntime
import com.pocketagent.runtime.CompositeModelSpecProvider
import com.pocketagent.runtime.MvpRuntimeFacade
import com.pocketagent.runtime.RuntimeCompositionRoot

internal object AppRuntimeFacadeFactory {
    fun buildProductionRuntimeFacade(
        context: Context,
        graph: AppRuntimeGraph,
    ): MvpRuntimeFacade {
        val applicationContext = context.applicationContext
        val provisioningStore = graph.provisioningStore
        val runtimeTuning = graph.runtimeTuning
        return RuntimeCompositionRoot.createFacade(
            runtimeConfig = provisioningStore.runtimeConfig(),
            conversationModule = graph.conversationModule,
            memoryModule = graph.memoryModule,
            inferenceModule = createDefaultAndroidInferenceModule(applicationContext),
            modelSpecProvider = CompositeModelSpecProvider(
                providers = listOf(graph.normalizedModelCatalogRegistry),
            ),
            toolModule = AndroidLocalToolRuntime(applicationContext),
            memoryBudgetTracker = runtimeTuning.memoryBudgetTracker,
            recommendedGpuLayers = { modelId, config ->
                runtimeTuning
                    .applyRecommendedConfig(
                        modelIdHint = modelId,
                        baseConfig = config,
                        gpuQualifiedLayers = config.gpuLayers.coerceAtLeast(0),
                    )
                    .gpuLayers
                    .takeIf { it != config.gpuLayers }
            },
            mmProjPathResolver = { modelId ->
                provisioningStore.resolveMmProjPath(modelId)
            },
        )
    }
}
