package com.pocketagent.android.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pocketagent.android.ui.resolveDefaultGetReadyVersion
import com.pocketagent.android.runtime.DefaultModelCatalogEligibilityEvaluator
import com.pocketagent.android.runtime.ModelCatalogEligibilityEvaluator
import com.pocketagent.android.runtime.ModelCatalogEligibilitySnapshot
import com.pocketagent.android.runtime.ModelEligibilitySignalsProvider
import com.pocketagent.android.runtime.ProvisioningAggregateState
import com.pocketagent.android.runtime.ProvisioningGateway
import com.pocketagent.android.runtime.ProvisioningMutationResult
import com.pocketagent.android.runtime.RuntimeDomainException
import com.pocketagent.android.runtime.RuntimeModelImportResult
import com.pocketagent.android.runtime.RuntimeModelLifecycleSnapshot
import com.pocketagent.android.runtime.RuntimeProvisioningSnapshot
import com.pocketagent.android.runtime.errorCodeName
import com.pocketagent.android.runtime.modelmanager.DownloadPreferencesState
import com.pocketagent.android.runtime.modelmanager.DownloadRequestOptions
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.android.ui.state.ModelLoadingState
import com.pocketagent.android.ui.state.toModelLoadingState
import com.pocketagent.runtime.ModelLifecycleErrorCode
import com.pocketagent.runtime.RuntimeLoadedModel
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ModelProvisioningUiState(
    val snapshot: RuntimeProvisioningSnapshot? = null,
    val lifecycle: RuntimeModelLifecycleSnapshot = RuntimeModelLifecycleSnapshot.initial(),
    val downloads: List<DownloadTaskState> = emptyList(),
    val downloadPreferences: DownloadPreferencesState = DownloadPreferencesState(),
    val manifest: ModelDistributionManifest = ModelDistributionManifest(models = emptyList()),
    val manifestLoaded: Boolean = false,
    val eligibility: ModelCatalogEligibilitySnapshot = ModelCatalogEligibilitySnapshot(),
    val isImporting: Boolean = false,
    val statusMessage: String? = null,
    val enqueuingModelIds: Set<String> = emptySet(),
)

data class ModelLibraryUiState(
    val snapshot: RuntimeProvisioningSnapshot,
    val manifest: ModelDistributionManifest,
    val downloads: List<DownloadTaskState>,
    val eligibility: ModelCatalogEligibilitySnapshot = ModelCatalogEligibilitySnapshot(),
    val isImporting: Boolean,
    val isManifestLoaded: Boolean,
    val statusMessage: String?,
    val defaultGetReadyModelId: String?,
    val defaultModelVersion: ModelDistributionVersion?,
    val enqueuingModelIds: Set<String> = emptySet(),
)

data class RuntimeModelUiState(
    val snapshot: RuntimeProvisioningSnapshot,
    val lifecycle: RuntimeModelLifecycleSnapshot,
    val isImporting: Boolean,
    val statusMessage: String?,
)

private data class ModelProvisioningLocalUiState(
    val isImporting: Boolean = false,
    val statusMessage: String? = null,
    val enqueuingModelIds: Set<String> = emptySet(),
)

internal fun ModelProvisioningUiState.toModelLibraryUiState(defaultGetReadyModelId: String?): ModelLibraryUiState? {
    val currentSnapshot = snapshot ?: return null
    return ModelLibraryUiState(
        snapshot = currentSnapshot,
        manifest = manifest,
        downloads = downloads,
        eligibility = eligibility,
        isImporting = isImporting,
        isManifestLoaded = manifestLoaded,
        statusMessage = statusMessage,
        defaultGetReadyModelId = defaultGetReadyModelId,
        defaultModelVersion = resolveDefaultGetReadyVersion(
            manifest = manifest,
            defaultModelId = defaultGetReadyModelId,
        ),
        enqueuingModelIds = enqueuingModelIds,
    )
}

internal fun ModelProvisioningUiState.toRuntimeModelUiState(): RuntimeModelUiState? {
    val currentSnapshot = snapshot ?: return null
    return RuntimeModelUiState(
        snapshot = currentSnapshot,
        lifecycle = lifecycle,
        isImporting = isImporting,
        statusMessage = statusMessage,
    )
}

class ModelProvisioningViewModel internal constructor(
    private val gateway: ProvisioningGateway,
    private val eligibilityEvaluator: ModelCatalogEligibilityEvaluator = DefaultModelCatalogEligibilityEvaluator(),
    private val eligibilitySignalsProvider: ModelEligibilitySignalsProvider = ModelEligibilitySignalsProvider.ASSUME_SUPPORTED,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel(), ModelOperationHandler {
    private val aggregateState = MutableStateFlow(gateway.observeProvisioningAggregateState().value)
    private val localUiState = MutableStateFlow(ModelProvisioningLocalUiState())
    private val _modelLoadingState = MutableStateFlow(aggregateState.value.lifecycle.toModelLoadingState())
    private val _uiState = MutableStateFlow(buildUiState())
    val uiState = _uiState.asStateFlow()
    val modelLoadingState = _modelLoadingState.asStateFlow()
    private val modelOperationStateLock = Any()

    @Volatile
    private var lastModelOperationToken: Long = 0L

    @Volatile
    private var lastModelOperationAtMs: Long = 0L

    @Volatile
    private var lastModelOperationKey: String? = null

    init {
        viewModelScope.launch {
            gateway.observeProvisioningAggregateState().collect { aggregate ->
                setAggregateState(aggregate)
            }
        }
        viewModelScope.launch { refreshManifest() }
    }

    fun refreshSnapshot() {
        viewModelScope.launch(ioDispatcher) {
            setAggregateState(gateway.observeProvisioningAggregateState().value)
        }
    }

    suspend fun refreshManifest() {
        val seeded = withContext(ioDispatcher) {
            gateway.seedProvisioningAggregateState()
        }
        setAggregateState(seeded)
    }

    fun setStatusMessage(message: String?) {
        updateLocalUiState { state -> state.copy(statusMessage = message) }
    }

    suspend fun importModelFromUri(modelId: String, sourceUri: Uri): Result<RuntimeModelImportResult> {
        updateLocalUiState { state -> state.copy(isImporting = true) }
        val result = runCatching {
            withContext(ioDispatcher) {
                gateway.importModelFromUri(modelId = modelId, sourceUri = sourceUri)
            }
        }.recoverCatching { error ->
            throw ProvisioningUserFacingException(
                message = userMessageFor(error),
                code = (error as? RuntimeDomainException)?.domainError?.code,
                cause = error,
            )
        }
        updateLocalUiState { state -> state.copy(isImporting = false) }
        return result
    }

    fun listInstalledVersions(modelId: String): List<ModelVersionDescriptor> {
        return gateway.listInstalledVersions(modelId = modelId)
    }

    suspend fun listInstalledVersionsAsync(modelId: String): List<ModelVersionDescriptor> {
        return withContext(ioDispatcher) {
            gateway.listInstalledVersions(modelId = modelId)
        }
    }

    fun setActiveVersion(modelId: String, version: String): ProvisioningMutationResult {
        return gateway.setActiveVersion(modelId = modelId, version = version)
    }

    suspend fun setActiveVersionAsync(modelId: String, version: String): ProvisioningMutationResult {
        return withContext(ioDispatcher) {
            gateway.setActiveVersion(modelId = modelId, version = version)
        }
    }

    fun clearActiveVersion(modelId: String): ProvisioningMutationResult {
        return gateway.clearActiveVersion(modelId = modelId)
    }

    suspend fun clearActiveVersionAsync(modelId: String): ProvisioningMutationResult {
        return withContext(ioDispatcher) {
            gateway.clearActiveVersion(modelId = modelId)
        }
    }

    fun removeVersion(modelId: String, version: String): ProvisioningMutationResult {
        return gateway.removeVersion(modelId = modelId, version = version)
    }

    suspend fun removeVersionAsync(modelId: String, version: String): ProvisioningMutationResult {
        return withContext(ioDispatcher) {
            gateway.removeVersion(modelId = modelId, version = version)
        }
    }

    suspend fun loadInstalledModel(modelId: String, version: String): RuntimeModelLifecycleCommandResult {
        return loadModel(modelId, version)
            ?: RuntimeModelLifecycleCommandResult.rejected(
                code = ModelLifecycleErrorCode.CANCELLED_BY_NEWER_REQUEST,
                detail = "load:$modelId@$version",
            )
    }

    override suspend fun loadModel(
        modelId: String,
        version: String,
    ): RuntimeModelLifecycleCommandResult? {
        val requestKey = "load:$modelId@$version"
        if (shouldDebounceModelOperation(requestKey)) {
            return null
        }
        val current = _modelLoadingState.value
        val requestedModel = RuntimeLoadedModel(modelId = modelId, modelVersion = version)
        val token = nextModelOperationToken()
        applyImmediateModelLoadingState(
            ModelLoadingState.Loading(
                requestedModel = requestedModel,
                loadedModel = current.loadedModel,
                lastUsedModel = current.lastUsedModel,
                progress = 0f,
                stage = "Starting model load...",
                timestampMs = System.currentTimeMillis(),
            ),
        )
        return withContext(ioDispatcher) {
            finalizeModelOperation(
                token = token,
                result = gateway.loadInstalledModel(modelId = modelId, version = version),
                fallbackModelId = modelId,
                fallbackVersion = version,
            )
        }
    }

    override suspend fun loadLastUsedModel(): RuntimeModelLifecycleCommandResult? {
        val lastUsed = _modelLoadingState.value.lastUsedModel
        val requestKey = "load-last-used:${lastUsed?.modelId.orEmpty()}@${lastUsed?.modelVersion.orEmpty()}"
        if (shouldDebounceModelOperation(requestKey)) {
            return null
        }
        val token = nextModelOperationToken()
        applyImmediateModelLoadingState(
            ModelLoadingState.Loading(
                requestedModel = lastUsed,
                loadedModel = _modelLoadingState.value.loadedModel,
                lastUsedModel = lastUsed,
                progress = 0f,
                stage = "Starting model load...",
                timestampMs = System.currentTimeMillis(),
            ),
        )
        return withContext(ioDispatcher) {
            finalizeModelOperation(
                token = token,
                result = gateway.loadLastUsedModel(),
                fallbackModelId = lastUsed?.modelId,
                fallbackVersion = lastUsed?.modelVersion,
            )
        }
    }

    override suspend fun offloadModel(reason: String): RuntimeModelLifecycleCommandResult? {
        val currentModel = _modelLoadingState.value.loadedModel ?: _modelLoadingState.value.lastUsedModel
        val requestKey = "offload:${currentModel?.modelId.orEmpty()}@${currentModel?.modelVersion.orEmpty()}:$reason"
        if (shouldDebounceModelOperation(requestKey)) {
            return null
        }
        val token = nextModelOperationToken()
        applyImmediateModelLoadingState(
            ModelLoadingState.Offloading(
                loadedModel = _modelLoadingState.value.loadedModel,
                lastUsedModel = _modelLoadingState.value.lastUsedModel,
                reason = reason,
                queued = false,
                timestampMs = System.currentTimeMillis(),
            ),
        )
        return withContext(ioDispatcher) {
            finalizeModelOperation(
                token = token,
                result = gateway.offloadModel(reason = reason),
                fallbackModelId = currentModel?.modelId,
                fallbackVersion = currentModel?.modelVersion,
            )
        }
    }

    suspend fun enqueueDownload(
        version: ModelDistributionVersion,
        options: DownloadRequestOptions = DownloadRequestOptions(),
    ): String {
        val key = "${version.modelId}::${version.version}"
        updateLocalUiState { state -> state.copy(enqueuingModelIds = state.enqueuingModelIds + key) }
        return try {
            withContext(ioDispatcher) {
                gateway.enqueueDownload(version = version, options = options)
            }
        } finally {
            updateLocalUiState { state -> state.copy(enqueuingModelIds = state.enqueuingModelIds - key) }
        }
    }

    fun shouldWarnForMeteredLargeDownload(version: ModelDistributionVersion): Boolean {
        return gateway.shouldWarnForMeteredLargeDownload(version)
    }

    fun setDownloadWifiOnlyEnabled(enabled: Boolean) {
        gateway.setDownloadWifiOnlyEnabled(enabled)
    }

    fun acknowledgeLargeDownloadCellularWarning() {
        gateway.acknowledgeLargeDownloadCellularWarning()
    }

    fun pauseDownload(taskId: String) {
        gateway.pauseDownload(taskId)
    }

    fun resumeDownload(taskId: String) {
        gateway.resumeDownload(taskId)
    }

    fun retryDownload(taskId: String) {
        gateway.retryDownload(taskId)
    }

    fun cancelDownload(taskId: String) {
        gateway.cancelDownload(taskId)
    }

    fun refreshDownloads() {
        gateway.syncDownloadsFromScheduler()
    }

    private fun userMessageFor(error: Throwable): String {
        return (error as? RuntimeDomainException)?.domainError?.userMessage
            ?: "Model import failed. Please try again."
    }

    private fun setAggregateState(state: ProvisioningAggregateState) {
        aggregateState.value = state
        _modelLoadingState.value = state.lifecycle.toModelLoadingState()
        _uiState.value = buildUiState()
    }

    private inline fun updateAggregateState(
        transform: (ProvisioningAggregateState) -> ProvisioningAggregateState,
    ) {
        aggregateState.update(transform)
        _uiState.value = buildUiState()
    }

    private inline fun updateLocalUiState(
        transform: (ModelProvisioningLocalUiState) -> ModelProvisioningLocalUiState,
    ) {
        localUiState.update(transform)
        _uiState.value = buildUiState()
    }

    private fun buildUiState(): ModelProvisioningUiState {
        return aggregateState.value.toModelProvisioningUiState(localUiState.value).withEligibility(
            evaluator = eligibilityEvaluator,
            signalsProvider = eligibilitySignalsProvider,
        )
    }

    private fun applyImmediateModelLoadingState(nextState: ModelLoadingState) {
        _modelLoadingState.value = nextState
    }

    private suspend fun finalizeModelOperation(
        token: Long,
        result: RuntimeModelLifecycleCommandResult,
        fallbackModelId: String?,
        fallbackVersion: String?,
    ): RuntimeModelLifecycleCommandResult? {
        val latestToken = synchronized(modelOperationStateLock) { lastModelOperationToken }
        if (token != latestToken) {
            return null
        }
        if (!result.success) {
            applyImmediateModelLoadingState(
                ModelLoadingState.Error(
                    requestedModel = fallbackModelId?.let { RuntimeLoadedModel(it, fallbackVersion) },
                    loadedModel = _modelLoadingState.value.loadedModel,
                    lastUsedModel = _modelLoadingState.value.lastUsedModel,
                    message = lifecycleErrorMessage(
                        result = result,
                        fallbackModelId = fallbackModelId,
                        fallbackVersion = fallbackVersion,
                    ),
                    code = result.errorCodeName(),
                    detail = result.detail,
                    timestampMs = System.currentTimeMillis(),
                ),
            )
        }
        return result
    }

    private fun shouldDebounceModelOperation(requestKey: String): Boolean {
        synchronized(modelOperationStateLock) {
            val now = System.currentTimeMillis()
            val shouldDebounce = lastModelOperationKey == requestKey &&
                now - lastModelOperationAtMs < MODEL_OPERATION_DEBOUNCE_MS
            if (!shouldDebounce) {
                lastModelOperationKey = requestKey
                lastModelOperationAtMs = now
            }
            return shouldDebounce
        }
    }

    private fun nextModelOperationToken(): Long {
        synchronized(modelOperationStateLock) {
            lastModelOperationToken += 1L
            return lastModelOperationToken
        }
    }
}

class ProvisioningUserFacingException(
    message: String,
    val code: String?,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class ModelProvisioningViewModelFactory internal constructor(
    private val gateway: ProvisioningGateway,
    private val eligibilityEvaluator: ModelCatalogEligibilityEvaluator = DefaultModelCatalogEligibilityEvaluator(),
    private val eligibilitySignalsProvider: ModelEligibilitySignalsProvider = ModelEligibilitySignalsProvider.ASSUME_SUPPORTED,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ModelProvisioningViewModel::class.java)) {
            return ModelProvisioningViewModel(
                gateway = gateway,
                eligibilityEvaluator = eligibilityEvaluator,
                eligibilitySignalsProvider = eligibilitySignalsProvider,
            ) as T
        }
        throw IllegalArgumentException("Unsupported ViewModel class: ${modelClass.name}")
    }
}

private fun ModelProvisioningUiState.withEligibility(
    evaluator: ModelCatalogEligibilityEvaluator,
    signalsProvider: ModelEligibilitySignalsProvider,
): ModelProvisioningUiState {
    return copy(
        eligibility = evaluator.evaluate(
            manifest = manifest,
            snapshot = snapshot,
            signals = signalsProvider.currentSignals(),
        ),
    )
}

private fun ProvisioningAggregateState.toModelProvisioningUiState(
    local: ModelProvisioningLocalUiState,
): ModelProvisioningUiState {
    return ModelProvisioningUiState(
        snapshot = snapshot,
        lifecycle = lifecycle,
        downloads = downloads,
        downloadPreferences = downloadPreferences,
        manifest = manifest,
        manifestLoaded = manifestLoaded,
        isImporting = local.isImporting,
        statusMessage = local.statusMessage,
        enqueuingModelIds = local.enqueuingModelIds,
    )
}

internal fun provisioningMutationFailureMessage(
    result: ProvisioningMutationResult,
    fallbackMessage: String,
): String {
    return when (result) {
        ProvisioningMutationResult.Applied -> fallbackMessage
        is ProvisioningMutationResult.Blocked -> result.error.userMessage
        is ProvisioningMutationResult.NoChange -> fallbackMessage
        is ProvisioningMutationResult.NotFound -> fallbackMessage
    }
}
