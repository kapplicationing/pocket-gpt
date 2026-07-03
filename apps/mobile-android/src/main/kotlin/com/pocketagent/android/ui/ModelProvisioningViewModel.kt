package com.pocketagent.android.ui

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pocketagent.android.ui.resolveDefaultGetReadyVersion
import com.pocketagent.android.runtime.DefaultModelCatalogEligibilityEvaluator
import com.pocketagent.android.runtime.AppDispatchers
import com.pocketagent.android.runtime.AppOperationTrace
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
import com.pocketagent.android.runtime.huggingface.DefaultHuggingFaceModelAcquisition
import com.pocketagent.android.runtime.huggingface.HuggingFaceAcquisitionException
import com.pocketagent.android.runtime.huggingface.HuggingFaceAcquisitionBlockReason
import com.pocketagent.android.runtime.huggingface.HuggingFaceCandidate
import com.pocketagent.android.runtime.huggingface.HuggingFaceModelAcquisition
import com.pocketagent.android.runtime.huggingface.HuggingFaceRecentModel
import com.pocketagent.android.runtime.huggingface.HuggingFaceRecentModelStore
import com.pocketagent.android.runtime.huggingface.HuggingFaceSearchFileResult
import com.pocketagent.android.runtime.huggingface.HuggingFaceTargetModel
import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.core.model.ModelSourceKind
import com.pocketagent.android.ui.state.ModelLoadingState
import com.pocketagent.android.ui.state.toModelLoadingState
import com.pocketagent.runtime.ModelLifecycleErrorCode
import com.pocketagent.runtime.RuntimeLoadedModel
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@Immutable
sealed interface HuggingFaceAcquisitionUiState {
    data object Idle : HuggingFaceAcquisitionUiState
    data object Resolving : HuggingFaceAcquisitionUiState
    data class Ready(val candidate: HuggingFaceCandidate) : HuggingFaceAcquisitionUiState
    data class Blocked(
        val reason: HuggingFaceAcquisitionBlockReason,
        val message: String,
    ) : HuggingFaceAcquisitionUiState
}

@Immutable
sealed interface HuggingFaceSearchUiState {
    data object Idle : HuggingFaceSearchUiState
    data object Searching : HuggingFaceSearchUiState
    data class Results(
        val query: String,
        val results: List<HuggingFaceSearchFileResult>,
    ) : HuggingFaceSearchUiState
    data class Empty(val query: String) : HuggingFaceSearchUiState
    data class Blocked(val message: String) : HuggingFaceSearchUiState
}

@Immutable
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
    val huggingFaceTargets: List<HuggingFaceTargetModel> = emptyList(),
    val huggingFaceAcquisitionState: HuggingFaceAcquisitionUiState = HuggingFaceAcquisitionUiState.Idle,
    val huggingFaceSearchState: HuggingFaceSearchUiState = HuggingFaceSearchUiState.Idle,
    val recentHuggingFaceModels: List<HuggingFaceRecentModel> = emptyList(),
)

@Immutable
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
    val huggingFaceTargets: List<HuggingFaceTargetModel> = emptyList(),
    val huggingFaceAcquisitionState: HuggingFaceAcquisitionUiState = HuggingFaceAcquisitionUiState.Idle,
    val huggingFaceSearchState: HuggingFaceSearchUiState = HuggingFaceSearchUiState.Idle,
    val recentHuggingFaceModels: List<HuggingFaceRecentModel> = emptyList(),
)

@Immutable
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
    val huggingFaceAcquisitionState: HuggingFaceAcquisitionUiState = HuggingFaceAcquisitionUiState.Idle,
    val huggingFaceSearchState: HuggingFaceSearchUiState = HuggingFaceSearchUiState.Idle,
    val recentHuggingFaceModels: List<HuggingFaceRecentModel> = emptyList(),
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
        huggingFaceTargets = huggingFaceTargets,
        huggingFaceAcquisitionState = huggingFaceAcquisitionState,
        huggingFaceSearchState = huggingFaceSearchState,
        recentHuggingFaceModels = recentHuggingFaceModels,
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
    private val dispatchers: AppDispatchers = AppDispatchers.DEFAULT,
    private val ioDispatcher: CoroutineDispatcher = dispatchers.io,
    private val huggingFaceModelAcquisition: HuggingFaceModelAcquisition = DefaultHuggingFaceModelAcquisition(),
    private val huggingFaceRecentModelStore: HuggingFaceRecentModelStore = HuggingFaceRecentModelStore.None,
) : ViewModel(), ModelOperationHandler {
    private val aggregateState = MutableStateFlow(gateway.observeProvisioningAggregateState().value)
    private val localUiState = MutableStateFlow(ModelProvisioningLocalUiState())
    private val _modelLoadingState = MutableStateFlow(aggregateState.value.lifecycle.toModelLoadingState())
    private val _uiState = MutableStateFlow(
        aggregateState.value.toModelProvisioningUiState(
            local = localUiState.value,
            huggingFaceTargets = huggingFaceModelAcquisition.supportedTargets(),
        ),
    )
    val uiState = _uiState.asStateFlow()
    val modelLoadingState = _modelLoadingState.asStateFlow()
    val provisioningSnapshotFlow = _uiState
        .map { it.snapshot }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, _uiState.value.snapshot)
    val downloadsFlow = _uiState
        .map { it.downloads }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, _uiState.value.downloads)
    private val modelOperationStateLock = Any()

    @Volatile
    private var lastModelOperationToken: Long = 0L

    @Volatile
    private var lastModelOperationAtMs: Long = 0L

    @Volatile
    private var lastModelOperationKey: String? = null

    init {
        viewModelScope.launch(ioDispatcher) {
            _uiState.value = buildUiState()
            gateway.observeProvisioningAggregateState().collect { aggregate ->
                setAggregateState(aggregate)
            }
        }
        viewModelScope.launch(ioDispatcher) { refreshManifest() }
        viewModelScope.launch(ioDispatcher) { refreshRecentHuggingFaceModels() }
    }

    fun refreshSnapshot() {
        viewModelScope.launch(ioDispatcher) {
            setAggregateState(gateway.observeProvisioningAggregateState().value)
        }
    }

    suspend fun refreshManifest() {
        withContext(ioDispatcher) {
            val seeded = gateway.seedProvisioningAggregateState()
            setAggregateState(seeded)
        }
    }

    fun setStatusMessage(message: String?) {
        updateLocalUiState { state -> state.copy(statusMessage = message) }
    }

    suspend fun resolveHuggingFaceCandidate(
        input: String,
        targetModelId: String,
    ) {
        updateLocalUiState { state ->
            state.copy(huggingFaceAcquisitionState = HuggingFaceAcquisitionUiState.Resolving)
        }
        val nextState = runCatching {
            withTimeout(HUGGING_FACE_CANDIDATE_TIMEOUT_MS) {
                withContext(ioDispatcher) {
                    huggingFaceModelAcquisition.resolveCandidate(
                        input = input,
                        targetModelId = targetModelId,
                    )
                }
            }
        }.fold(
            onSuccess = { candidate -> HuggingFaceAcquisitionUiState.Ready(candidate) },
            onFailure = { error ->
                if (error is HuggingFaceAcquisitionException) {
                    HuggingFaceAcquisitionUiState.Blocked(
                        reason = error.reason,
                        message = error.userMessage,
                    )
                } else {
                    HuggingFaceAcquisitionUiState.Blocked(
                        reason = HuggingFaceAcquisitionBlockReason.NETWORK_ERROR,
                        message = error.message ?: "Could not check the Hugging Face file.",
                    )
                }
            },
        )
        updateLocalUiState { state -> state.copy(huggingFaceAcquisitionState = nextState) }
    }

    fun clearHuggingFaceCandidate() {
        updateLocalUiState { state ->
            state.copy(huggingFaceAcquisitionState = HuggingFaceAcquisitionUiState.Idle)
        }
    }

    suspend fun searchHuggingFaceFiles(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            updateLocalUiState { state ->
                state.copy(huggingFaceSearchState = HuggingFaceSearchUiState.Idle)
            }
            return
        }
        updateLocalUiState { state ->
            state.copy(huggingFaceSearchState = HuggingFaceSearchUiState.Searching)
        }
        val nextState = runCatching {
            withContext(ioDispatcher) {
                huggingFaceModelAcquisition.searchFiles(query = trimmed)
            }
        }.fold(
            onSuccess = { results ->
                if (results.isEmpty()) {
                    HuggingFaceSearchUiState.Empty(query = trimmed)
                } else {
                    HuggingFaceSearchUiState.Results(
                        query = trimmed,
                        results = results,
                    )
                }
            },
            onFailure = { error ->
                val message = if (error is HuggingFaceAcquisitionException) {
                    error.userMessage
                } else {
                    error.message ?: "Could not search Hugging Face. Check the network, then try again."
                }
                HuggingFaceSearchUiState.Blocked(message = message)
            },
        )
        updateLocalUiState { state -> state.copy(huggingFaceSearchState = nextState) }
    }

    fun clearHuggingFaceSearch() {
        updateLocalUiState { state ->
            state.copy(huggingFaceSearchState = HuggingFaceSearchUiState.Idle)
        }
    }

    suspend fun removeRecentHuggingFaceModel(id: String) {
        withContext(ioDispatcher) {
            huggingFaceRecentModelStore.remove(id)
        }
        refreshRecentHuggingFaceModels()
    }

    suspend fun clearRecentHuggingFaceModels() {
        withContext(ioDispatcher) {
            huggingFaceRecentModelStore.clear()
        }
        refreshRecentHuggingFaceModels()
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

    @Deprecated("UI paths must use listInstalledVersionsAsync so disk-backed reads stay on the IO dispatcher.")
    fun listInstalledVersions(modelId: String): List<ModelVersionDescriptor> {
        return gateway.listInstalledVersions(modelId = modelId)
    }

    suspend fun listInstalledVersionsAsync(modelId: String): List<ModelVersionDescriptor> {
        return withContext(ioDispatcher) {
            gateway.listInstalledVersions(modelId = modelId)
        }
    }

    @Deprecated("UI paths must use setActiveVersionAsync so provisioning mutations stay on the IO dispatcher.")
    fun setActiveVersion(modelId: String, version: String): ProvisioningMutationResult {
        return gateway.setActiveVersion(modelId = modelId, version = version)
    }

    suspend fun setActiveVersionAsync(modelId: String, version: String): ProvisioningMutationResult {
        return withContext(ioDispatcher) {
            gateway.setActiveVersion(modelId = modelId, version = version)
        }
    }

    @Deprecated("UI paths must use clearActiveVersionAsync so provisioning mutations stay on the IO dispatcher.")
    fun clearActiveVersion(modelId: String): ProvisioningMutationResult {
        return gateway.clearActiveVersion(modelId = modelId)
    }

    suspend fun clearActiveVersionAsync(modelId: String): ProvisioningMutationResult {
        return withContext(ioDispatcher) {
            gateway.clearActiveVersion(modelId = modelId)
        }
    }

    @Deprecated("UI paths must use removeVersionAsync so provisioning mutations stay on the IO dispatcher.")
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
            AppOperationTrace.suspendSection(
                name = "model.load",
                detail = { "model=$modelId|version=$version" },
            ) {
                finalizeModelOperation(
                    token = token,
                    result = gateway.loadInstalledModel(modelId = modelId, version = version),
                    fallbackModelId = modelId,
                    fallbackVersion = version,
                )
            }
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
            AppOperationTrace.suspendSection(
                name = "model.load_last_used",
                detail = { "model=${lastUsed?.modelId.orEmpty()}|version=${lastUsed?.modelVersion.orEmpty()}" },
            ) {
                finalizeModelOperation(
                    token = token,
                    result = gateway.loadLastUsedModel(),
                    fallbackModelId = lastUsed?.modelId,
                    fallbackVersion = lastUsed?.modelVersion,
                )
            }
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
            AppOperationTrace.suspendSection(
                name = "model.offload",
                detail = { "reason=$reason|model=${currentModel?.modelId.orEmpty()}" },
            ) {
                finalizeModelOperation(
                    token = token,
                    result = gateway.offloadModel(reason = reason),
                    fallbackModelId = currentModel?.modelId,
                    fallbackVersion = currentModel?.modelVersion,
                )
            }
        }
    }

    suspend fun enqueueDownload(
        version: ModelDistributionVersion,
        options: DownloadRequestOptions = DownloadRequestOptions(),
    ): String {
        val key = "${version.modelId}::${version.version}"
        val hfCandidate = (localUiState.value.huggingFaceAcquisitionState as? HuggingFaceAcquisitionUiState.Ready)
            ?.candidate
            ?.takeIf { candidate ->
                version.sourceKind == ModelSourceKind.HUGGING_FACE &&
                    candidate.version.modelId == version.modelId &&
                    candidate.version.version == version.version
            }
        updateLocalUiState { state -> state.copy(enqueuingModelIds = state.enqueuingModelIds + key) }
        return try {
            val taskId = withContext(ioDispatcher) {
                gateway.enqueueDownload(version = version, options = options)
            }
            if (hfCandidate != null) {
                withContext(ioDispatcher) {
                    huggingFaceRecentModelStore.upsert(
                        candidate = hfCandidate,
                        enqueuedAtEpochMs = System.currentTimeMillis(),
                    )
                }
                refreshRecentHuggingFaceModels()
            }
            taskId
        } finally {
            updateLocalUiState { state ->
                state.copy(
                    enqueuingModelIds = state.enqueuingModelIds - key,
                )
            }
        }
    }

    @Deprecated("UI paths must use shouldWarnForMeteredLargeDownloadAsync so preference/network-backed checks stay on IO.")
    fun shouldWarnForMeteredLargeDownload(version: ModelDistributionVersion): Boolean {
        return gateway.shouldWarnForMeteredLargeDownload(version)
    }

    suspend fun shouldWarnForMeteredLargeDownloadAsync(version: ModelDistributionVersion): Boolean {
        return withContext(ioDispatcher) {
            gateway.shouldWarnForMeteredLargeDownload(version)
        }
    }

    @Deprecated("UI paths must use setDownloadWifiOnlyEnabledAsync so preference writes stay on IO.")
    fun setDownloadWifiOnlyEnabled(enabled: Boolean) {
        gateway.setDownloadWifiOnlyEnabled(enabled)
    }

    suspend fun setDownloadWifiOnlyEnabledAsync(enabled: Boolean) {
        withContext(ioDispatcher) {
            gateway.setDownloadWifiOnlyEnabled(enabled)
        }
        refreshSnapshot()
    }

    @Deprecated("UI paths must use acknowledgeLargeDownloadCellularWarningAsync so preference writes stay on IO.")
    fun acknowledgeLargeDownloadCellularWarning() {
        gateway.acknowledgeLargeDownloadCellularWarning()
    }

    suspend fun acknowledgeLargeDownloadCellularWarningAsync() {
        withContext(ioDispatcher) {
            gateway.acknowledgeLargeDownloadCellularWarning()
        }
        refreshSnapshot()
    }

    @Deprecated("UI paths must use pauseDownloadAsync so scheduler-backed work stays on IO.")
    fun pauseDownload(taskId: String) {
        gateway.pauseDownload(taskId)
    }

    suspend fun pauseDownloadAsync(taskId: String) {
        withContext(ioDispatcher) {
            gateway.pauseDownload(taskId)
        }
    }

    @Deprecated("UI paths must use resumeDownloadAsync so scheduler-backed work stays on IO.")
    fun resumeDownload(taskId: String) {
        gateway.resumeDownload(taskId)
    }

    suspend fun resumeDownloadAsync(taskId: String) {
        withContext(ioDispatcher) {
            gateway.resumeDownload(taskId)
        }
    }

    @Deprecated("UI paths must use retryDownloadAsync so scheduler-backed work stays on IO.")
    fun retryDownload(taskId: String) {
        gateway.retryDownload(taskId)
    }

    suspend fun retryDownloadAsync(taskId: String) {
        withContext(ioDispatcher) {
            gateway.retryDownload(taskId)
        }
    }

    @Deprecated("UI paths must use cancelDownloadAsync so scheduler-backed work stays on IO.")
    fun cancelDownload(taskId: String) {
        gateway.cancelDownload(taskId)
    }

    suspend fun cancelDownloadAsync(taskId: String) {
        withContext(ioDispatcher) {
            gateway.cancelDownload(taskId)
        }
    }

    @Deprecated("UI paths must use refreshDownloadsAsync so scheduler-backed work stays on IO.")
    fun refreshDownloads() {
        gateway.syncDownloadsFromScheduler()
    }

    suspend fun refreshDownloadsAsync() {
        withContext(ioDispatcher) {
            gateway.syncDownloadsFromScheduler()
        }
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

    private fun updateAggregateState(
        transform: (ProvisioningAggregateState) -> ProvisioningAggregateState,
    ) {
        aggregateState.update(transform)
        publishUiStateFromIo()
    }

    private fun updateLocalUiState(
        transform: (ModelProvisioningLocalUiState) -> ModelProvisioningLocalUiState,
    ) {
        localUiState.update(transform)
        _uiState.value = buildUiStateWithoutEligibility(eligibility = _uiState.value.eligibility)
        publishUiStateFromIo()
    }

    private fun publishUiStateFromIo() {
        viewModelScope.launch(ioDispatcher) {
            _uiState.value = buildUiState()
        }
    }

    private fun buildUiState(): ModelProvisioningUiState {
        return aggregateState.value.toModelProvisioningUiState(
            local = localUiState.value,
            huggingFaceTargets = huggingFaceModelAcquisition.supportedTargets(),
        ).withEligibility(
            evaluator = eligibilityEvaluator,
            signalsProvider = eligibilitySignalsProvider,
        )
    }

    private fun buildUiStateWithoutEligibility(
        eligibility: ModelCatalogEligibilitySnapshot = ModelCatalogEligibilitySnapshot(),
    ): ModelProvisioningUiState {
        return aggregateState.value.toModelProvisioningUiState(
            local = localUiState.value,
            huggingFaceTargets = huggingFaceModelAcquisition.supportedTargets(),
        ).copy(
            eligibility = eligibility,
        )
    }

    private fun refreshRecentHuggingFaceModels() {
        val recentModels = huggingFaceRecentModelStore.list()
        updateLocalUiState { state -> state.copy(recentHuggingFaceModels = recentModels) }
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
    private val dispatchers: AppDispatchers = AppDispatchers.DEFAULT,
    private val huggingFaceModelAcquisition: HuggingFaceModelAcquisition = DefaultHuggingFaceModelAcquisition(),
    private val huggingFaceRecentModelStore: HuggingFaceRecentModelStore = HuggingFaceRecentModelStore.None,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ModelProvisioningViewModel::class.java)) {
            return ModelProvisioningViewModel(
                gateway = gateway,
                eligibilityEvaluator = eligibilityEvaluator,
                eligibilitySignalsProvider = eligibilitySignalsProvider,
                dispatchers = dispatchers,
                huggingFaceModelAcquisition = huggingFaceModelAcquisition,
                huggingFaceRecentModelStore = huggingFaceRecentModelStore,
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
    huggingFaceTargets: List<HuggingFaceTargetModel>,
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
        huggingFaceTargets = huggingFaceTargets,
        huggingFaceAcquisitionState = local.huggingFaceAcquisitionState,
        recentHuggingFaceModels = local.recentHuggingFaceModels,
        huggingFaceSearchState = local.huggingFaceSearchState,
    )
}

private const val HUGGING_FACE_CANDIDATE_TIMEOUT_MS = 20_000L

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
