package com.pocketagent.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.pocketagent.android.runtime.AppDispatchers
import com.pocketagent.android.runtime.AndroidRuntimeProvisioningStore
import com.pocketagent.android.runtime.AndroidRuntimeTuningStore
import com.pocketagent.android.runtime.DefaultModelRuntimeLaunchPlanner
import com.pocketagent.android.runtime.MvpRuntimeGateway
import com.pocketagent.android.runtime.MainThreadGuard
import com.pocketagent.android.runtime.ModelAdmissionAction
import com.pocketagent.android.runtime.ModelAdmissionDecision
import com.pocketagent.android.runtime.ModelAdmissionPolicy
import com.pocketagent.android.runtime.ModelAdmissionSubject
import com.pocketagent.android.runtime.ModelEligibilitySignalsProvider
import com.pocketagent.android.runtime.ModelVersionEligibility
import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifestProvider
import com.pocketagent.android.runtime.modelmanager.ModelDownloadManager
import com.pocketagent.android.runtime.modelspec.DefaultNormalizedModelCatalogRegistry
import com.pocketagent.nativebridge.ModelLifecycleErrorCode
import com.pocketagent.nativebridge.ModelLifecycleEvent
import com.pocketagent.nativebridge.ModelLifecycleState
import com.pocketagent.core.InMemoryConversationModule
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.memory.FileBackedMemoryModule
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.MvpRuntimeFacade
import com.pocketagent.runtime.RuntimeLoadedModel
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult
import com.pocketagent.runtime.RuntimeResourceControl
import com.pocketagent.runtime.StreamChatRequestV2
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class AppRuntimeLifecycleCoordinatorTest {
    @Test
    fun `loadInstalledModel publishes loaded state when runtime omits loaded model identity`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val context = LifecycleCoordinatorTestContext(
            root = File(System.getProperty("java.io.tmpdir"), "pocketgpt-lifecycle-${System.nanoTime()}"),
        )
        val graph = testRuntimeGraph(context)
        val coordinator = AppRuntimeLifecycleCoordinator(
            graphProvider = { graph },
            currentGraphProvider = { graph },
            installProductionRuntime = { },
            memoryEstimateRecorder = { },
            modelAdmissionPolicyProvider = { AllowAllAdmissionPolicy },
            dispatchers = AppDispatchers(io = dispatcher),
        )
        val modelId = "qwen3-0.6b-q4_k_m"
        val version = "q4_k_m"
        val modelFile = File(context.getExternalFilesDir(null), "qwen.gguf").apply {
            parentFile?.mkdirs()
            writeText("fake-gguf")
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        MainThreadGuard.overrideIsMainThreadForTests { false }
        try {
            graph.provisioningStore.seedModelFromAbsolutePath(
                modelId = modelId,
                absolutePath = modelFile.absolutePath,
                version = version,
            )
            graph.runtimeFacade.replace(RuntimeLoadWithoutIdentityFacade())

            val result = coordinator.loadInstalledModel(context = context, modelId = modelId, version = version)
            val lifecycle = coordinator.observeModelLifecycle(context = context, scope = scope)

            val requested = RuntimeLoadedModel(modelId = modelId, modelVersion = version)
            assertEquals(requested, result.loadedModel)
            assertEquals(ModelLifecycleState.LOADED, lifecycle.value.state)
            assertEquals(requested, lifecycle.value.loadedModel)
        } finally {
            scope.cancel()
            MainThreadGuard.resetForTests()
        }
    }

    @Test
    fun `successful load result without loaded model uses requested model`() {
        val requested = RuntimeLoadedModel(modelId = "qwen3-0.6b-q4_k_m", modelVersion = "q4_k_m")

        val normalized = RuntimeModelLifecycleCommandResult
            .applied()
            .withRequestedLoadedModelOnSuccessfulLoad(requested)

        assertEquals(requested, normalized.loadedModel)
    }

    @Test
    fun `successful load result keeps explicit loaded model`() {
        val requested = RuntimeLoadedModel(modelId = "requested", modelVersion = "v1")
        val explicit = RuntimeLoadedModel(modelId = "explicit", modelVersion = "v2")

        val normalized = RuntimeModelLifecycleCommandResult
            .applied(loadedModel = explicit)
            .withRequestedLoadedModelOnSuccessfulLoad(requested)

        assertEquals(explicit, normalized.loadedModel)
    }

    @Test
    fun `queued or rejected load result is not rewritten`() {
        val requested = RuntimeLoadedModel(modelId = "qwen3-0.6b-q4_k_m", modelVersion = "q4_k_m")
        val queued = RuntimeModelLifecycleCommandResult.queued()
        val rejected = RuntimeModelLifecycleCommandResult.rejected(
            code = ModelLifecycleErrorCode.MODEL_FILE_UNAVAILABLE,
            detail = "missing",
        )

        assertSame(queued, queued.withRequestedLoadedModelOnSuccessfulLoad(requested))
        assertSame(rejected, rejected.withRequestedLoadedModelOnSuccessfulLoad(requested))
        assertNull(rejected.withRequestedLoadedModelOnSuccessfulLoad(requested).loadedModel)
    }
}

private fun testRuntimeGraph(context: Context): AppRuntimeGraph {
    val provisioningStore = AndroidRuntimeProvisioningStore(context)
    val runtimeFacade = HotSwappableRuntimeFacade(RuntimeLoadWithoutIdentityFacade())
    val manifestProvider = ModelDistributionManifestProvider(context = null)
    val registry = DefaultNormalizedModelCatalogRegistry(
        manifestProvider = { manifestProvider.currentManifest() },
        installedVersionsProvider = { modelId -> provisioningStore.listInstalledVersions(modelId) },
        knownModelIdsProvider = {
            provisioningStore.snapshot().models.mapTo(linkedSetOf()) { state -> state.modelId }
        },
    )
    val launchPlanner = DefaultModelRuntimeLaunchPlanner(catalogRegistry = registry)
    return AppRuntimeGraph(
        provisioningStore = provisioningStore,
        modelDownloadManager = uninitializedDownloadManager(),
        modelManifestProvider = manifestProvider,
        runtimeTuning = AndroidRuntimeTuningStore(
            context = context,
            provisioningStore = provisioningStore,
        ),
        conversationModule = InMemoryConversationModule(),
        memoryModule = FileBackedMemoryModule.defaultRuntimeModule(),
        runtimeFacade = runtimeFacade,
        runtimeGateway = MvpRuntimeGateway(facade = runtimeFacade),
        eligibilitySignalsProvider = ModelEligibilitySignalsProvider.ASSUME_SUPPORTED,
        normalizedModelCatalogRegistry = registry,
        runtimeLaunchPlanner = launchPlanner,
        modelAdmissionPolicy = AllowAllAdmissionPolicy,
    )
}

private object AllowAllAdmissionPolicy : ModelAdmissionPolicy {
    override fun evaluate(
        action: ModelAdmissionAction,
        subject: ModelAdmissionSubject,
    ): ModelAdmissionDecision {
        return ModelAdmissionDecision(
            action = action,
            subject = subject,
            eligibility = ModelVersionEligibility.supported(),
        )
    }
}

private fun uninitializedDownloadManager(): ModelDownloadManager {
    // AppRuntimeGraph requires a download manager, but loadInstalledModel does not touch it.
    // Avoid starting WorkManager/SQLite scheduler infrastructure in this focused host unit test.
    val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply {
        isAccessible = true
    }
    val unsafe = unsafeField.get(null)
    val allocateInstance = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
    return allocateInstance.invoke(unsafe, ModelDownloadManager::class.java) as ModelDownloadManager
}

private class RuntimeLoadWithoutIdentityFacade : MvpRuntimeFacade, RuntimeResourceControl {
    private var routingMode: RoutingMode = RoutingMode.AUTO

    override fun createSession(): SessionId = SessionId("session-1")

    override fun streamChat(request: StreamChatRequestV2): Flow<ChatStreamEvent> = emptyFlow()

    override fun cancelGeneration(sessionId: SessionId): Boolean = true

    override fun runTool(toolName: String, jsonArgs: String): String = "tool:$toolName"

    override fun analyzeImage(imagePath: String, prompt: String): String = "image:$imagePath"

    override fun exportDiagnostics(): String = "diag"

    override fun setRoutingMode(mode: RoutingMode) {
        routingMode = mode
    }

    override fun getRoutingMode(): RoutingMode = routingMode

    override fun runStartupChecks(): List<String> = emptyList()

    override fun restoreSession(sessionId: SessionId, turns: List<Turn>) = Unit

    override fun deleteSession(sessionId: SessionId): Boolean = true

    override fun evictResidentModel(reason: String): Boolean = true

    override fun loadModel(modelId: String, modelVersion: String?): RuntimeModelLifecycleCommandResult {
        return RuntimeModelLifecycleCommandResult.applied()
    }

    override fun exportDiagnosticsJson(): String? = null

    override fun currentModelLifecycleEvent(): ModelLifecycleEvent? = null

    override fun observeModelLifecycleEvents(listener: (ModelLifecycleEvent) -> Unit): AutoCloseable {
        return AutoCloseable { }
    }
}

private class LifecycleCoordinatorTestContext(
    private val root: File,
) : ContextWrapper(null) {
    private val prefs = mutableMapOf<String, InMemorySharedPreferences>()

    init {
        root.mkdirs()
    }

    override fun getApplicationContext(): Context = this

    override fun getPackageName(): String = "com.pocketagent.android.test"

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
        return prefs.getOrPut(name ?: "default") { InMemorySharedPreferences() }
    }

    override fun getExternalFilesDir(type: String?): File {
        return File(root, "external/${type ?: "files"}").apply { mkdirs() }
    }

    override fun getCacheDir(): File {
        return File(root, "cache").apply { mkdirs() }
    }

    override fun getFilesDir(): File {
        return File(root, "files").apply { mkdirs() }
    }
}

private class InMemorySharedPreferences : SharedPreferences {
    private val values = linkedMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        @Suppress("UNCHECKED_CAST")
        return (values[key] as? Set<String>)?.toMutableSet() ?: defValues
    }

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor(values)

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private class Editor(
        private val values: MutableMap<String, Any?>,
    ) : SharedPreferences.Editor {
        private val pending = linkedMapOf<String, Any?>()
        private var clearRequested = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
            pending[key.orEmpty()] = value
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
            pending[key.orEmpty()] = values?.toSet()
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
            pending[key.orEmpty()] = value
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
            pending[key.orEmpty()] = value
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
            pending[key.orEmpty()] = value
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
            pending[key.orEmpty()] = value
        }

        override fun remove(key: String?): SharedPreferences.Editor = apply {
            pending[key.orEmpty()] = null
        }

        override fun clear(): SharedPreferences.Editor = apply {
            clearRequested = true
        }

        override fun commit(): Boolean {
            if (clearRequested) {
                values.clear()
            }
            pending.forEach { (key, value) ->
                if (value == null) {
                    values.remove(key)
                } else {
                    values[key] = value
                }
            }
            pending.clear()
            clearRequested = false
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
