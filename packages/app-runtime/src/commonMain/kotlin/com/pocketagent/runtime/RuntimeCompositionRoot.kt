package com.pocketagent.runtime

import com.pocketagent.core.ConversationModule
import com.pocketagent.core.InMemoryConversationModule
import com.pocketagent.core.model.ModelSpecProvider
import com.pocketagent.inference.InferenceModule
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.memory.FileBackedMemoryModule
import com.pocketagent.memory.MemoryModule

object RuntimeCompositionRoot {
    fun createContainer(
        runtimeConfig: RuntimeConfig = RuntimeConfig.fromEnvironment(),
        conversationModule: ConversationModule = InMemoryConversationModule(),
        memoryModule: MemoryModule = FileBackedMemoryModule.defaultRuntimeModule(),
        inferenceModule: InferenceModule? = null,
        modelSpecProvider: ModelSpecProvider = ModelCatalog,
        memoryBudgetTracker: MemoryBudgetTracker? = null,
        recommendedGpuLayers: (String, PerformanceRuntimeConfig) -> Int? = { _, _ -> null },
        mmProjPathResolver: (String) -> String? = { null },
    ): RuntimeContainer {
        return DefaultRuntimeContainer(
            runtimeConfig = runtimeConfig,
            conversationModule = conversationModule,
            memoryModule = memoryModule,
            inferenceModule = inferenceModule,
            modelSpecProvider = modelSpecProvider,
            memoryBudgetTracker = memoryBudgetTracker,
            recommendedGpuLayers = recommendedGpuLayers,
            mmProjPathResolver = mmProjPathResolver,
        )
    }

    fun createFacade(
        runtimeConfig: RuntimeConfig = RuntimeConfig.fromEnvironment(),
        conversationModule: ConversationModule = InMemoryConversationModule(),
        memoryModule: MemoryModule = FileBackedMemoryModule.defaultRuntimeModule(),
        inferenceModule: InferenceModule? = null,
        modelSpecProvider: ModelSpecProvider = ModelCatalog,
        memoryBudgetTracker: MemoryBudgetTracker? = null,
        recommendedGpuLayers: (String, PerformanceRuntimeConfig) -> Int? = { _, _ -> null },
        mmProjPathResolver: (String) -> String? = { null },
    ): MvpRuntimeFacade {
        return DefaultMvpRuntimeFacade(
            container = createContainer(
                runtimeConfig = runtimeConfig,
                conversationModule = conversationModule,
                memoryModule = memoryModule,
                inferenceModule = inferenceModule,
                modelSpecProvider = modelSpecProvider,
                memoryBudgetTracker = memoryBudgetTracker,
                recommendedGpuLayers = recommendedGpuLayers,
                mmProjPathResolver = mmProjPathResolver,
            ),
        )
    }
}
