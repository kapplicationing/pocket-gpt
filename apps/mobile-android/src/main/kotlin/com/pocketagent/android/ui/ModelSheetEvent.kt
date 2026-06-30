package com.pocketagent.android.ui

import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion

sealed interface ModelSheetEvent {
    data class ImportModel(val modelId: String) : ModelSheetEvent
    data class ResolveHuggingFaceCandidate(val input: String, val targetModelId: String) : ModelSheetEvent
    data object ClearHuggingFaceCandidate : ModelSheetEvent
    data class RemoveRecentHuggingFaceModel(val id: String) : ModelSheetEvent
    data object ClearRecentHuggingFaceModels : ModelSheetEvent
    data class OpenExternalUrl(val url: String) : ModelSheetEvent
    data class DownloadVersion(val version: ModelDistributionVersion) : ModelSheetEvent
    data class PauseDownload(val taskId: String) : ModelSheetEvent
    data class ResumeDownload(val taskId: String) : ModelSheetEvent
    data class RetryDownload(val taskId: String) : ModelSheetEvent
    data class CancelDownload(val taskId: String) : ModelSheetEvent
    data class LoadVersion(val modelId: String, val version: String) : ModelSheetEvent
    data class RetryLoad(val modelId: String, val version: String?) : ModelSheetEvent
    data object LoadLastUsedModel : ModelSheetEvent
    data object OffloadModel : ModelSheetEvent
    data class RequestRemove(val modelId: String, val version: String) : ModelSheetEvent
    data object RefreshAll : ModelSheetEvent
    data object Close : ModelSheetEvent
}
