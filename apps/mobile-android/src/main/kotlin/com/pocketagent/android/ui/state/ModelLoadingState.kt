package com.pocketagent.android.ui.state

import com.pocketagent.android.runtime.RuntimeModelLifecycleSnapshot
import com.pocketagent.runtime.RuntimeLoadedModel

sealed interface ModelLoadingState {
    val loadedModel: RuntimeLoadedModel?
    val lastUsedModel: RuntimeLoadedModel?

    data class Idle(
        override val loadedModel: RuntimeLoadedModel? = null,
        override val lastUsedModel: RuntimeLoadedModel? = null,
        val updatedAtEpochMs: Long = System.currentTimeMillis(),
    ) : ModelLoadingState

    data class Loading(
        val requestedModel: RuntimeLoadedModel?,
        override val loadedModel: RuntimeLoadedModel?,
        override val lastUsedModel: RuntimeLoadedModel?,
        val progress: Float?,
        val stage: String,
        val timestampMs: Long,
    ) : ModelLoadingState

    data class Loaded(
        val model: RuntimeLoadedModel,
        override val lastUsedModel: RuntimeLoadedModel?,
        val detail: String?,
        val readyAtEpochMs: Long,
    ) : ModelLoadingState {
        override val loadedModel: RuntimeLoadedModel = model
    }

    data class Offloading(
        override val loadedModel: RuntimeLoadedModel?,
        override val lastUsedModel: RuntimeLoadedModel?,
        val reason: String?,
        val queued: Boolean,
        val timestampMs: Long,
    ) : ModelLoadingState

    data class Error(
        val requestedModel: RuntimeLoadedModel?,
        override val loadedModel: RuntimeLoadedModel?,
        override val lastUsedModel: RuntimeLoadedModel?,
        val message: String,
        val code: String?,
        val detail: String?,
        val timestampMs: Long,
    ) : ModelLoadingState
}

fun RuntimeModelLifecycleSnapshot.toModelLoadingState(): ModelLoadingState {
    return when (state.name) {
        "UNLOADED" -> ModelLoadingState.Idle(
            loadedModel = loadedModel,
            lastUsedModel = lastUsedModel,
            updatedAtEpochMs = updatedAtEpochMs,
        )

        "LOADING" -> ModelLoadingState.Loading(
            requestedModel = requestedModel,
            loadedModel = loadedModel,
            lastUsedModel = lastUsedModel,
            progress = loadingProgress,
            stage = loadingDetail.orEmpty().ifBlank { "Loading model..." },
            timestampMs = updatedAtEpochMs,
        )

        "LOADED" -> {
            val resolvedModel = loadedModel ?: requestedModel ?: lastUsedModel
                ?: return ModelLoadingState.Idle(
                    loadedModel = null,
                    lastUsedModel = lastUsedModel,
                    updatedAtEpochMs = updatedAtEpochMs,
                )
            ModelLoadingState.Loaded(
                model = resolvedModel,
                lastUsedModel = lastUsedModel ?: resolvedModel,
                detail = loadingDetail
                    ?.trim()
                    ?.takeIf { detail -> detail.isNotEmpty() && detail != DEFAULT_LOADED_DETAIL },
                readyAtEpochMs = updatedAtEpochMs,
            )
        }

        "OFFLOADING" -> ModelLoadingState.Offloading(
            loadedModel = loadedModel,
            lastUsedModel = lastUsedModel,
            reason = errorDetail,
            queued = queuedOffload,
            timestampMs = updatedAtEpochMs,
        )

        "FAILED" -> ModelLoadingState.Error(
            requestedModel = requestedModel,
            loadedModel = loadedModel,
            lastUsedModel = lastUsedModel,
            message = errorDetail.orEmpty().ifBlank { "Model operation failed." },
            code = errorCode?.name,
            detail = errorDetail,
            timestampMs = updatedAtEpochMs,
        )

        else -> ModelLoadingState.Idle(
            loadedModel = loadedModel,
            lastUsedModel = lastUsedModel,
            updatedAtEpochMs = updatedAtEpochMs,
        )
    }
}

private const val DEFAULT_LOADED_DETAIL = "Loaded"

internal fun ModelLoadingState.activeOrRequestedModel(): RuntimeLoadedModel? {
    return when (this) {
        is ModelLoadingState.Idle -> loadedModel
        is ModelLoadingState.Loading -> requestedModel ?: loadedModel ?: lastUsedModel
        is ModelLoadingState.Loaded -> model
        is ModelLoadingState.Offloading -> loadedModel ?: lastUsedModel
        is ModelLoadingState.Error -> requestedModel ?: loadedModel ?: lastUsedModel
    }
}
