package com.pocketagent.android.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@Immutable
internal data class ModelImportRequest(
    val operationId: Long,
    val modelId: String,
    val pickerPending: Boolean,
)

class ChatAppViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val modelImportRequestLock = Any()
    private var lastModelImportOperationId = savedStateHandle[MODEL_IMPORT_SEQUENCE_KEY] ?: 0L
    private val _modelImportRequest = MutableStateFlow(restorePendingModelImportRequest())
    internal val modelImportRequest = _modelImportRequest.asStateFlow()

    internal fun requestModelImport(modelId: String): ModelImportRequest? {
        val normalizedModelId = modelId.trim().takeIf { it.isNotEmpty() } ?: return null
        return synchronized(modelImportRequestLock) {
            if (_modelImportRequest.value != null) {
                return@synchronized null
            }
            lastModelImportOperationId += 1L
            savedStateHandle[MODEL_IMPORT_SEQUENCE_KEY] = lastModelImportOperationId
            savedStateHandle[MODEL_IMPORT_MODEL_ID_KEY] = normalizedModelId
            savedStateHandle[MODEL_IMPORT_OPERATION_ID_KEY] = lastModelImportOperationId
            ModelImportRequest(
                operationId = lastModelImportOperationId,
                modelId = normalizedModelId,
                pickerPending = true,
            ).also { request -> _modelImportRequest.value = request }
        }
    }

    internal fun consumeModelImportRequest(): ModelImportRequest? {
        return synchronized(modelImportRequestLock) {
            val request = _modelImportRequest.value?.takeIf { it.pickerPending }
                ?: return@synchronized null
            clearPersistedPendingModelImport()
            _modelImportRequest.value = null
            request.copy(pickerPending = false)
        }
    }

    private fun restorePendingModelImportRequest(): ModelImportRequest? {
        val modelId = savedStateHandle.get<String>(MODEL_IMPORT_MODEL_ID_KEY)
        val operationId = savedStateHandle.get<Long>(MODEL_IMPORT_OPERATION_ID_KEY)
        if (modelId.isNullOrBlank() || operationId == null) {
            clearPersistedPendingModelImport()
            return null
        }
        return ModelImportRequest(
            operationId = operationId,
            modelId = modelId,
            pickerPending = true,
        )
    }

    private fun clearPersistedPendingModelImport() {
        savedStateHandle.remove<String>(MODEL_IMPORT_MODEL_ID_KEY)
        savedStateHandle.remove<Long>(MODEL_IMPORT_OPERATION_ID_KEY)
    }

    private val _pendingGetReadyActivation = MutableStateFlow<Pair<String, String>?>(null)
    val pendingGetReadyActivation = _pendingGetReadyActivation.asStateFlow()

    fun setPendingGetReadyActivation(value: Pair<String, String>?) {
        _pendingGetReadyActivation.value = value
    }

    private val _pendingMeteredWarningVersion = MutableStateFlow<ModelDistributionVersion?>(null)
    val pendingMeteredWarningVersion = _pendingMeteredWarningVersion.asStateFlow()

    fun setPendingMeteredWarningVersion(version: ModelDistributionVersion?) {
        _pendingMeteredWarningVersion.value = version
    }

    private val _pendingNotificationPermissionVersion = MutableStateFlow<ModelDistributionVersion?>(null)
    val pendingNotificationPermissionVersion = _pendingNotificationPermissionVersion.asStateFlow()

    fun setPendingNotificationPermissionVersion(version: ModelDistributionVersion?) {
        _pendingNotificationPermissionVersion.value = version
    }

    private val _pendingRoutingModeSwitch = MutableStateFlow<Pair<String, String>?>(null)
    val pendingRoutingModeSwitch = _pendingRoutingModeSwitch.asStateFlow()

    fun setPendingRoutingModeSwitch(value: Pair<String, String>?) {
        _pendingRoutingModeSwitch.value = value
    }

    private val _lastDownloadTransitionRefreshKey = MutableStateFlow<String?>(null)
    val lastDownloadTransitionRefreshKey = _lastDownloadTransitionRefreshKey.asStateFlow()

    fun setLastDownloadTransitionRefreshKey(key: String?) {
        _lastDownloadTransitionRefreshKey.value = key
    }

    private val _readinessRefreshSequence = MutableStateFlow(0L)
    val readinessRefreshSequence = _readinessRefreshSequence.asStateFlow()

    fun incrementReadinessRefreshSequence(): Long {
        _readinessRefreshSequence.value += 1L
        return _readinessRefreshSequence.value
    }

    private companion object {
        const val MODEL_IMPORT_MODEL_ID_KEY = "model_import_model_id"
        const val MODEL_IMPORT_OPERATION_ID_KEY = "model_import_operation_id"
        const val MODEL_IMPORT_SEQUENCE_KEY = "model_import_sequence"
    }
}
