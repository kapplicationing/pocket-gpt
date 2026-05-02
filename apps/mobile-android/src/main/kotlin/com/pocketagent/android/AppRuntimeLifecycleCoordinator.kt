package com.pocketagent.android

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.pocketagent.android.runtime.AppDispatchers
import com.pocketagent.android.runtime.AppOperationTrace
import com.pocketagent.android.runtime.ModelAdmissionAction
import com.pocketagent.android.runtime.ModelAdmissionPolicy
import com.pocketagent.android.runtime.MainThreadGuard
import com.pocketagent.android.runtime.toAdmissionSubject
import com.pocketagent.android.runtime.ModelMemoryEstimator
import com.pocketagent.android.runtime.RuntimeModelLifecycleSnapshot
import com.pocketagent.nativebridge.ModelLifecycleErrorCode
import com.pocketagent.nativebridge.ModelLifecycleEvent
import com.pocketagent.nativebridge.ModelLoadingStage
import com.pocketagent.nativebridge.ModelLifecycleState
import com.pocketagent.runtime.RuntimeLoadedModel
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AppRuntimeLifecycleCoordinator(
    private val graphProvider: (Context) -> AppRuntimeGraph,
    private val currentGraphProvider: () -> AppRuntimeGraph?,
    private val installProductionRuntime: (Context) -> Unit,
    private val memoryEstimateRecorder: (ModelMemoryEstimator.EstimationResult?) -> Unit,
    private val modelAdmissionPolicyProvider: (Context) -> ModelAdmissionPolicy,
    private val dispatchers: AppDispatchers = AppDispatchers.DEFAULT,
) {
    private val lifecycleCommandMutex = Mutex()
    private val lifecycleState = MutableStateFlow(RuntimeModelLifecycleSnapshot.initial())
    private val lock = Any()

    @Volatile
    private var lifecycleEventSubscription: AutoCloseable? = null

    @Volatile
    private var lifecycleActionToken: Long = 0L

    @Volatile
    private var lastReconcileQuickFingerprint: String? = null

    @Volatile
    private var lastReconcileAtMs: Long = 0L

    fun resetForTests() {
        lifecycleEventSubscription?.close()
        lifecycleEventSubscription = null
        lifecycleState.value = RuntimeModelLifecycleSnapshot.initial()
        lifecycleActionToken = 0L
        lastReconcileQuickFingerprint = null
        lastReconcileAtMs = 0L
        memoryEstimateRecorder(null)
    }

    fun observeModelLifecycle(
        context: Context,
        scope: CoroutineScope,
    ): StateFlow<RuntimeModelLifecycleSnapshot> {
        scope.launch(dispatchers.io) {
            reconcileLifecycleState(graphProvider(context))
        }
        return lifecycleState.asStateFlow()
    }

    fun currentModelLifecycle(context: Context): RuntimeModelLifecycleSnapshot {
        MainThreadGuard.assertNotMainThread("AppRuntimeLifecycleCoordinator.currentModelLifecycle")
        reconcileLifecycleState(graphProvider(context))
        return lifecycleState.value
    }

    suspend fun loadInstalledModel(
        context: Context,
        modelId: String,
        version: String,
    ): RuntimeModelLifecycleCommandResult {
        val token = nextLifecycleActionToken()
        val requestedModel = RuntimeLoadedModel(modelId = modelId, modelVersion = version)
        lifecycleState.value = lifecycleState.value.copy(
            state = ModelLifecycleState.LOADING,
            requestedModel = requestedModel,
            errorCode = null,
            errorDetail = null,
            loadingDetail = "Checking model availability...",
            loadingStage = com.pocketagent.nativebridge.ModelLoadingStage.PRECHECK,
            loadingProgress = 0f,
            queuedOffload = false,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        return AppOperationTrace.suspendSection(
            name = "runtime.lifecycle.load_model",
            detail = { "model=$modelId|version=$version" },
        ) {
            lifecycleCommandMutex.withLock {
                if (token != lifecycleActionToken) {
                    val cancelled = cancelledByNewerRequestResult(detail = "load:$modelId@$version")
                    applyLifecycleCommandResult(cancelled, requestedModel = requestedModel)
                    return@withLock cancelled
                }
                val graph = graphProvider(context)
                val installed = graph.provisioningStore
                    .listInstalledVersions(modelId)
                    .firstOrNull { descriptor -> descriptor.version == version }
                if (installed == null) {
                    val missing = RuntimeModelLifecycleCommandResult.rejected(
                        code = ModelLifecycleErrorCode.MODEL_FILE_UNAVAILABLE,
                        detail = "installed_version_not_found:$modelId@$version",
                    )
                    applyLifecycleCommandResult(missing, requestedModel = requestedModel)
                    return@withLock missing
                }
                val file = java.io.File(installed.absolutePath)
                if (!file.exists() || !file.isFile) {
                    val missing = RuntimeModelLifecycleCommandResult.rejected(
                        code = ModelLifecycleErrorCode.MODEL_FILE_UNAVAILABLE,
                        detail = "installed_file_missing:${installed.absolutePath}",
                    )
                    applyLifecycleCommandResult(missing, requestedModel = requestedModel)
                    return@withLock missing
                }
                val admissionDecision = modelAdmissionPolicyProvider(context).evaluate(
                    action = ModelAdmissionAction.LOAD,
                    subject = installed.toAdmissionSubject(),
                )
                if (!admissionDecision.allowed) {
                    val rejected = admissionDecision.asLifecycleRejectedResult()
                    applyLifecycleCommandResult(rejected, requestedModel = requestedModel)
                    return@withLock rejected
                }
                lifecycleState.value = lifecycleState.value.copy(
                    loadingDetail = "Checking available memory...",
                    loadingStage = com.pocketagent.nativebridge.ModelLoadingStage.PRECHECK,
                    loadingProgress = 0f,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
                val memoryEstimate = runCatching {
                    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    val memInfo = ActivityManager.MemoryInfo()
                    activityManager?.getMemoryInfo(memInfo)
                    val availableBytes = memInfo.availMem.takeIf { it > 0L }
                    ModelMemoryEstimator.estimate(
                        modelFilePath = installed.absolutePath,
                        availableMemoryBytes = availableBytes,
                    )
                }.getOrNull()
                memoryEstimateRecorder(memoryEstimate)
                if (memoryEstimate?.fitsInMemory == false) {
                    Log.w(
                        "AppRuntimeDeps",
                        "MEMORY_WARNING|model=$modelId|estimated_mb=%.0f|available_mb=%.0f".format(
                            memoryEstimate.estimatedMb,
                            memoryEstimate.availableMemoryMb ?: 0.0,
                        ),
                    )
                }

                val activated = graph.provisioningStore.setActiveVersion(modelId = modelId, version = version)
                if (!activated) {
                    val failed = RuntimeModelLifecycleCommandResult.rejected(
                        code = ModelLifecycleErrorCode.MODEL_FILE_UNAVAILABLE,
                        detail = "activation_failed:$modelId@$version",
                    )
                    applyLifecycleCommandResult(failed, requestedModel = requestedModel)
                    return@withLock failed
                }
                val previousModelLoaded = lifecycleState.value.loadedModel != null
                if (previousModelLoaded) {
                    lifecycleState.value = lifecycleState.value.copy(
                        loadingDetail = "Releasing previous model...",
                        loadingStage = com.pocketagent.nativebridge.ModelLoadingStage.UNLOADING_PREVIOUS,
                        loadingProgress = 0.05f,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    )
                }
                lifecycleState.value = lifecycleState.value.copy(
                    loadingDetail = "Initializing runtime...",
                    loadingStage = com.pocketagent.nativebridge.ModelLoadingStage.INITIALIZING_RUNTIME,
                    loadingProgress = 0.1f,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
                installProductionRuntime(context)
                lifecycleState.value = lifecycleState.value.copy(
                    loadingDetail = "Loading model...",
                    loadingStage = com.pocketagent.nativebridge.ModelLoadingStage.LOADING_MODEL,
                    loadingProgress = 0.15f,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
                val result = graph.runtimeFacade.loadModel(modelId = modelId, modelVersion = version)
                if (token != lifecycleActionToken) {
                    if (result.success) {
                        graph.runtimeFacade.offloadModel(reason = "cancelled_by_newer_request")
                    }
                    val cancelled = cancelledByNewerRequestResult(detail = "load:$modelId@$version")
                    applyLifecycleCommandResult(cancelled, requestedModel = requestedModel)
                    return@withLock cancelled
                }
                if (result.success) {
                    graph.provisioningStore.recordLastLoadedModel(modelId = modelId, version = version)
                }
                applyLifecycleCommandResult(result, requestedModel = requestedModel)
                result
            }
        }
    }

    suspend fun loadLastUsedModel(context: Context): RuntimeModelLifecycleCommandResult {
        val graph = graphProvider(context)
        val lastUsed = graph.provisioningStore.lastLoadedModel()
            ?: return RuntimeModelLifecycleCommandResult.rejected(
                code = ModelLifecycleErrorCode.MODEL_FILE_UNAVAILABLE,
                detail = "last_loaded_model_missing",
            )
        return loadInstalledModel(
            context = context,
            modelId = lastUsed.modelId,
            version = lastUsed.version,
        )
    }

    suspend fun offloadModel(context: Context, reason: String): RuntimeModelLifecycleCommandResult {
        val token = nextLifecycleActionToken()
        val requestedModel = lifecycleState.value.loadedModel
        lifecycleState.value = lifecycleState.value.copy(
            state = ModelLifecycleState.OFFLOADING,
            requestedModel = requestedModel,
            errorCode = null,
            errorDetail = null,
            queuedOffload = false,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        return AppOperationTrace.suspendSection(
            name = "runtime.lifecycle.offload_model",
            detail = { "reason=$reason" },
        ) {
            lifecycleCommandMutex.withLock {
                if (token != lifecycleActionToken) {
                    val cancelled = cancelledByNewerRequestResult(detail = "offload:$reason")
                    applyLifecycleCommandResult(cancelled, requestedModel = requestedModel)
                    return@withLock cancelled
                }
                val graph = graphProvider(context)
                val result = graph.runtimeFacade.offloadModel(reason = reason)
                applyLifecycleCommandResult(result, requestedModel = requestedModel)
                result
            }
        }
    }

    fun attachLifecycleObserver(graph: AppRuntimeGraph) {
        lifecycleEventSubscription?.close()
        lifecycleEventSubscription = graph.runtimeFacade.observeModelLifecycleEvents(::applyLifecycleEvent)
        graph.runtimeFacade.currentModelLifecycleEvent()?.let(::applyLifecycleEvent)
    }

    fun reconcileLifecycleState(graph: AppRuntimeGraph) {
        if (shouldSkipReconcile(graph)) {
            return
        }
        AppOperationTrace.section(name = "runtime.lifecycle.reconcile") {
            val loaded = graph.runtimeFacade.loadedModel()
            val activeGenerationCount = graph.runtimeFacade.activeGenerationCount()
            val current = lifecycleState.value
            val normalizedLoaded = normalizeLoadedModel(graph = graph, loaded = loaded)
            val lastUsed = graph.provisioningStore.lastLoadedModel()?.let { ref ->
                RuntimeLoadedModel(modelId = ref.modelId, modelVersion = ref.version)
            }
            val preserveCompletedDetail = shouldPreserveCompletedDetail(
                current = current,
                normalizedLoaded = normalizedLoaded,
            )
            val reconciledState = reconcileState(
                current = current,
                normalizedLoaded = normalizedLoaded,
                activeGenerationCount = activeGenerationCount,
            )
            lifecycleState.value = current.copy(
                state = reconciledState,
                loadedModel = normalizedLoaded,
                requestedModel = reconcileRequestedModel(
                    current = current,
                    reconciledState = reconciledState,
                ),
                errorCode = keepFailedStateValue(reconciledState, current.errorCode),
                errorDetail = keepFailedStateValue(reconciledState, current.errorDetail),
                lastUsedModel = lastUsed,
                queuedOffload = current.queuedOffload && activeGenerationCount > 0,
                loadingDetail = reconcileLoadingField(
                    currentValue = current.loadingDetail,
                    preserveCompletedDetail = preserveCompletedDetail,
                    normalizedLoaded = normalizedLoaded,
                ),
                loadingStage = reconcileLoadingField(
                    currentValue = current.loadingStage,
                    preserveCompletedDetail = preserveCompletedDetail,
                    normalizedLoaded = normalizedLoaded,
                ),
                loadingProgress = reconcileLoadingField(
                    currentValue = current.loadingProgress,
                    preserveCompletedDetail = preserveCompletedDetail,
                    normalizedLoaded = normalizedLoaded,
                ),
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        }
    }

    private fun nextLifecycleActionToken(): Long {
        synchronized(lock) {
            lifecycleActionToken += 1L
            return lifecycleActionToken
        }
    }

    private fun cancelledByNewerRequestResult(detail: String): RuntimeModelLifecycleCommandResult {
        return RuntimeModelLifecycleCommandResult.rejected(
            code = ModelLifecycleErrorCode.CANCELLED_BY_NEWER_REQUEST,
            detail = detail,
            loadedModel = lifecycleState.value.loadedModel,
        )
    }

    private fun applyLifecycleCommandResult(
        result: RuntimeModelLifecycleCommandResult,
        requestedModel: RuntimeLoadedModel?,
    ) {
        val graph = currentGraphProvider()
        val resolvedLastUsed = graph?.provisioningStore?.lastLoadedModel()?.let { ref ->
            RuntimeLoadedModel(modelId = ref.modelId, modelVersion = ref.version)
        } ?: lifecycleState.value.lastUsedModel
        val updated = when {
            result.success && result.queued -> lifecycleState.value.copy(
                state = ModelLifecycleState.OFFLOADING,
                loadedModel = result.loadedModel ?: lifecycleState.value.loadedModel,
                requestedModel = requestedModel,
                queuedOffload = true,
                errorCode = null,
                errorDetail = result.detail,
                loadingDetail = null,
                loadingStage = null,
                loadingProgress = null,
                lastUsedModel = resolvedLastUsed,
                updatedAtEpochMs = System.currentTimeMillis(),
            )

            result.success -> lifecycleState.value.copy(
                state = if (result.loadedModel == null) ModelLifecycleState.UNLOADED else ModelLifecycleState.LOADED,
                loadedModel = result.loadedModel,
                requestedModel = null,
                queuedOffload = false,
                errorCode = null,
                errorDetail = result.detail,
                loadingDetail = lifecycleState.value.loadingDetail
                    ?.takeIf { lifecycleState.value.loadingStage == ModelLoadingStage.COMPLETED },
                loadingStage = lifecycleState.value.loadingStage
                    ?.takeIf { lifecycleState.value.loadingStage == ModelLoadingStage.COMPLETED },
                loadingProgress = lifecycleState.value.loadingProgress
                    ?.takeIf { lifecycleState.value.loadingStage == ModelLoadingStage.COMPLETED },
                lastUsedModel = resolvedLastUsed,
                updatedAtEpochMs = System.currentTimeMillis(),
            )

            else -> lifecycleState.value.copy(
                state = ModelLifecycleState.FAILED,
                requestedModel = requestedModel,
                queuedOffload = false,
                errorCode = result.errorCode ?: ModelLifecycleErrorCode.UNKNOWN,
                errorDetail = result.detail,
                loadingStage = null,
                loadingProgress = null,
                lastUsedModel = resolvedLastUsed,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        }
        lifecycleState.value = updated
    }

    private fun applyLifecycleEvent(event: ModelLifecycleEvent) {
        val current = lifecycleState.value
        val eventModel = event.modelId?.let { RuntimeLoadedModel(it, event.modelVersion) }
        val loadedModel = resolveEventLoadedModel(current = current, event = event, eventModel = eventModel)
        val requestedModel = resolveEventRequestedModel(current = current, event = event, eventModel = eventModel)
        lifecycleState.value = current.copy(
            state = event.state,
            loadedModel = loadedModel,
            requestedModel = requestedModel,
            errorCode = resolveEventErrorCode(current = current, event = event),
            errorDetail = resolveEventErrorDetail(current = current, event = event),
            loadingDetail = event.loadingDetail ?: current.loadingDetail,
            loadingStage = event.loadingStage,
            loadingProgress = event.loadingProgress,
            queuedOffload = current.queuedOffload && event.state == ModelLifecycleState.OFFLOADING,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
    }

    private fun shouldSkipReconcile(graph: AppRuntimeGraph): Boolean {
        val quickFp = buildReconcileQuickFingerprint(graph)
        val now = System.currentTimeMillis()
        val shouldSkip = quickFp == lastReconcileQuickFingerprint &&
            now - lastReconcileAtMs < RECONCILE_SKIP_WINDOW_MS
        if (!shouldSkip) {
            lastReconcileQuickFingerprint = quickFp
            lastReconcileAtMs = now
        }
        return shouldSkip
    }

    private fun normalizeLoadedModel(
        graph: AppRuntimeGraph,
        loaded: RuntimeLoadedModel?,
    ): RuntimeLoadedModel? {
        if (loaded == null) {
            return null
        }
        val installedVersions = runCatching {
            graph.provisioningStore.listInstalledVersions(loaded.modelId)
        }.getOrElse {
            emptyList()
        }
        val resolvedVersion = loaded.modelVersion
            ?: installedVersions.firstOrNull { descriptor -> descriptor.isActive }?.version
        if (resolvedVersion == null) {
            graph.runtimeFacade.offloadModel(reason = "reconcile_missing_version")
            return null
        }
        val installed = installedVersions.firstOrNull { descriptor -> descriptor.version == resolvedVersion }
        val fileExists = installed?.absolutePath
            ?.let { path -> java.io.File(path).let { it.exists() && it.isFile } }
            ?: false
        if (!fileExists) {
            graph.runtimeFacade.offloadModel(reason = "reconcile_missing_file")
            return null
        }
        return RuntimeLoadedModel(modelId = loaded.modelId, modelVersion = resolvedVersion)
    }
}

private fun shouldPreserveCompletedDetail(
    current: RuntimeModelLifecycleSnapshot,
    normalizedLoaded: RuntimeLoadedModel?,
): Boolean {
    return normalizedLoaded != null &&
        current.loadingStage == ModelLoadingStage.COMPLETED &&
        !current.loadingDetail.isNullOrBlank()
}

private fun reconcileState(
    current: RuntimeModelLifecycleSnapshot,
    normalizedLoaded: RuntimeLoadedModel?,
    activeGenerationCount: Int,
): ModelLifecycleState {
    if (normalizedLoaded != null) {
        return ModelLifecycleState.LOADED
    }
    if (current.queuedOffload && activeGenerationCount > 0) {
        return ModelLifecycleState.OFFLOADING
    }
    if (shouldKeepCurrentState(current = current, target = ModelLifecycleState.LOADING)) {
        return ModelLifecycleState.LOADING
    }
    if (shouldKeepCurrentState(current = current, target = ModelLifecycleState.OFFLOADING)) {
        return ModelLifecycleState.OFFLOADING
    }
    if (shouldKeepCurrentState(current = current, target = ModelLifecycleState.FAILED)) {
        return ModelLifecycleState.FAILED
    }
    return ModelLifecycleState.UNLOADED
}

private fun shouldKeepCurrentState(
    current: RuntimeModelLifecycleSnapshot,
    target: ModelLifecycleState,
): Boolean {
    return current.state == target && current.requestedModel != null
}

private fun reconcileRequestedModel(
    current: RuntimeModelLifecycleSnapshot,
    reconciledState: ModelLifecycleState,
): RuntimeLoadedModel? {
    return when (reconciledState) {
        ModelLifecycleState.LOADING,
        ModelLifecycleState.OFFLOADING,
        ModelLifecycleState.FAILED,
        -> current.requestedModel

        ModelLifecycleState.LOADED,
        ModelLifecycleState.UNLOADED,
        -> null
    }
}

private fun <T> keepFailedStateValue(reconciledState: ModelLifecycleState, value: T?): T? {
    return if (reconciledState == ModelLifecycleState.FAILED) value else null
}

private fun <T> reconcileLoadingField(
    currentValue: T?,
    preserveCompletedDetail: Boolean,
    normalizedLoaded: RuntimeLoadedModel?,
): T? {
    return when {
        preserveCompletedDetail -> currentValue
        normalizedLoaded != null -> null
        else -> currentValue
    }
}

private fun resolveEventLoadedModel(
    current: RuntimeModelLifecycleSnapshot,
    event: ModelLifecycleEvent,
    eventModel: RuntimeLoadedModel?,
): RuntimeLoadedModel? {
    return when (event.state) {
        ModelLifecycleState.LOADED -> eventModel ?: current.loadedModel
        ModelLifecycleState.UNLOADED -> {
            if (event.modelId == null || current.loadedModel?.modelId == event.modelId) {
                null
            } else {
                current.loadedModel
            }
        }
        else -> current.loadedModel
    }
}

private fun resolveEventRequestedModel(
    current: RuntimeModelLifecycleSnapshot,
    event: ModelLifecycleEvent,
    eventModel: RuntimeLoadedModel?,
): RuntimeLoadedModel? {
    return when (event.state) {
        ModelLifecycleState.LOADING,
        ModelLifecycleState.OFFLOADING,
        ModelLifecycleState.FAILED,
        -> current.requestedModel ?: eventModel

        ModelLifecycleState.LOADED,
        ModelLifecycleState.UNLOADED,
        -> null
    }
}

private fun resolveEventErrorCode(
    current: RuntimeModelLifecycleSnapshot,
    event: ModelLifecycleEvent,
): ModelLifecycleErrorCode? {
    return event.error?.code ?: takeFailedStateValue(
        event = event,
        currentValue = current.errorCode,
    )
}

private fun resolveEventErrorDetail(
    current: RuntimeModelLifecycleSnapshot,
    event: ModelLifecycleEvent,
): String? {
    return event.error?.detail ?: takeFailedStateValue(
        event = event,
        currentValue = current.errorDetail,
    )
}

private fun <T> takeFailedStateValue(event: ModelLifecycleEvent, currentValue: T?): T? {
    return if (event.state == ModelLifecycleState.FAILED) currentValue else null
}

private fun buildReconcileQuickFingerprint(graph: AppRuntimeGraph): String {
    val loaded = graph.runtimeFacade.loadedModel()
    val lastRef = graph.provisioningStore.lastLoadedModel()
    val genCount = graph.runtimeFacade.activeGenerationCount()
    return "${loaded?.modelId}:${loaded?.modelVersion}:${lastRef?.modelId}:${lastRef?.version}:$genCount"
}

private const val RECONCILE_SKIP_WINDOW_MS = 50L
