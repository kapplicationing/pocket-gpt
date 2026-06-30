@file:OptIn(ExperimentalLayoutApi::class, ExperimentalAnimationApi::class, ExperimentalComposeUiApi::class)

package com.pocketagent.android.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.ui.res.stringResource
import com.pocketagent.android.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.pocketagent.android.runtime.PresetBackingStore
import com.pocketagent.android.runtime.ModelEligibilityReason
import com.pocketagent.android.runtime.ModelSupportLevel
import com.pocketagent.android.runtime.ModelVersionEligibility
import com.pocketagent.android.runtime.ProvisionedModelState
import com.pocketagent.android.runtime.huggingface.HuggingFaceCandidate
import com.pocketagent.android.runtime.huggingface.HuggingFaceRecentModel
import com.pocketagent.android.runtime.huggingface.HuggingFaceSearchFileResult
import com.pocketagent.android.runtime.huggingface.HuggingFaceTargetModel
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.DownloadTaskStatus
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.android.runtime.modelmanager.bundleTotalBytes
import com.pocketagent.android.ui.components.SectionHeader
import com.pocketagent.android.ui.state.ModelLoadingState
import com.pocketagent.android.ui.state.activeOrRequestedModel
import com.pocketagent.android.ui.theme.LocalReduceMotion
import com.pocketagent.android.ui.theme.PocketAgentDimensions
import com.pocketagent.android.ui.theme.rememberHaptic
import com.pocketagent.android.ui.theme.rememberLongPressHaptic
import com.pocketagent.core.ModelPreset
import com.pocketagent.core.RoutingMode
import com.pocketagent.inference.ModelDisplayNames
import com.pocketagent.inference.PresetRoutingResolver
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
internal fun ModelSheet(
    libraryState: ModelLibraryUiState,
    runtimeState: RuntimeModelUiState,
    modelLoadingState: ModelLoadingState,
    routingMode: RoutingMode,
    presetBackingStore: PresetBackingStore,
    hiddenVersionKeys: Set<String> = emptySet(),
    onEvent: (ModelSheetEvent) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var huggingFaceInput by remember { mutableStateOf("") }
    var huggingFaceSearchQuery by remember { mutableStateOf("") }
    var selectedHuggingFaceTargetId by remember { mutableStateOf("") }
    val activeModel = modelLoadingState.activeOrRequestedModel()
    val busy = modelLoadingState is ModelLoadingState.Loading || modelLoadingState is ModelLoadingState.Offloading
    val resolvedHuggingFaceTargetId = selectedHuggingFaceTargetId
        .takeIf { selected -> libraryState.huggingFaceTargets.any { it.modelId == selected } }
        ?: libraryState.huggingFaceTargets.firstOrNull()?.modelId.orEmpty()
    val installedVersions by remember(libraryState, searchQuery, hiddenVersionKeys) {
        derivedStateOf {
            libraryState.snapshot.models.flatMap { model ->
                model.installedVersions.map { version -> model to version }
            }.filter { (model, version) ->
                versionIdentityKey(model.modelId, version.version) !in hiddenVersionKeys &&
                    matchesModelSearch(
                        searchQuery = searchQuery,
                        modelId = model.modelId,
                        displayName = model.displayName,
                        version = version.version,
                    )
            }
        }
    }
    val installedKeys by remember(installedVersions) {
        derivedStateOf {
            installedVersions
                .map { (model, version) -> versionIdentityKey(model.modelId, version.version) }
                .toSet()
        }
    }
    val availableVersions by remember(libraryState, searchQuery, installedKeys) {
        derivedStateOf {
            libraryState.manifest.models.flatMap { model ->
                model.versions.map { version ->
                    AvailableCatalogVersion(
                        displayName = model.displayName,
                        version = version,
                        eligibility = libraryState.eligibility.eligibilityFor(version.modelId, version.version),
                    )
                }
            }.filter { entry ->
                versionIdentityKey(entry.version.modelId, entry.version.version) !in installedKeys &&
                    entry.eligibility.catalogVisible &&
                    matchesModelSearch(
                        searchQuery = searchQuery,
                        modelId = entry.version.modelId,
                        displayName = entry.displayName,
                        version = entry.version.version,
                    )
            }
        }
    }
    val availableVersionKeys by remember(availableVersions) {
        derivedStateOf {
            availableVersions.mapTo(linkedSetOf()) { entry ->
                versionIdentityKey(entry.version.modelId, entry.version.version)
            }
        }
    }
    val downloadQueueTasks by remember(libraryState.downloads, availableVersionKeys) {
        derivedStateOf {
            libraryState.downloads
                .filter { task ->
                    versionIdentityKey(task.modelId, task.version) !in availableVersionKeys &&
                        (!task.terminal ||
                            task.status == DownloadTaskStatus.FAILED ||
                            task.status == DownloadTaskStatus.CANCELLED)
                }
                .sortedByDescending { task -> task.updatedAtEpochMs }
        }
    }
    val downloadTasksByKey by remember(libraryState) {
        derivedStateOf {
            libraryState.downloads.associateBy { task ->
                versionIdentityKey(task.modelId, task.version)
            }
        }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val huggingFaceSectionIndex = 2 + if (libraryState.statusMessage?.isNotBlank() == true) 1 else 0

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(PocketAgentDimensions.sheetHorizontalPadding)
            .testTag("unified_model_sheet")
            .semantics { testTagsAsResourceId = true },
        verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.screenPadding),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { onEvent(ModelSheetEvent.RefreshAll) }) {
                    Text(stringResource(id = R.string.ui_refresh))
                }
            }
        }
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(id = R.string.ui_search_models)) },
            )
        }
        libraryState.statusMessage?.takeIf { message -> message.isNotBlank() }?.let { message ->
            item {
                StatusMessageCard(message = message)
            }
        }
        item(key = HUGGING_FACE_SECTION_KEY) {
            HuggingFaceAcquisitionSection(
                input = huggingFaceInput,
                selectedTargetId = resolvedHuggingFaceTargetId,
                targets = libraryState.huggingFaceTargets,
                state = libraryState.huggingFaceAcquisitionState,
                searchQuery = huggingFaceSearchQuery,
                searchState = libraryState.huggingFaceSearchState,
                recentModels = libraryState.recentHuggingFaceModels,
                availableStorageBytes = libraryState.snapshot.storageSummary.freeBytes,
                onInputChange = { value -> huggingFaceInput = value },
                onSearchQueryChange = { value -> huggingFaceSearchQuery = value },
                onSelectTarget = { targetId -> selectedHuggingFaceTargetId = targetId },
                onCheck = {
                    onEvent(
                        ModelSheetEvent.ResolveHuggingFaceCandidate(
                            input = huggingFaceInput,
                            targetModelId = resolvedHuggingFaceTargetId,
                        ),
                    )
                },
                onClear = { onEvent(ModelSheetEvent.ClearHuggingFaceCandidate) },
                onSearch = {
                    onEvent(ModelSheetEvent.SearchHuggingFaceFiles(huggingFaceSearchQuery))
                },
                onClearSearch = { onEvent(ModelSheetEvent.ClearHuggingFaceSearch) },
                onUseSearchResult = { result ->
                    huggingFaceInput = result.canonicalUrl
                    onEvent(
                        ModelSheetEvent.ResolveHuggingFaceCandidate(
                            input = result.canonicalUrl,
                            targetModelId = resolvedHuggingFaceTargetId,
                        ),
                    )
                },
                onDownloadVersion = { version -> onEvent(ModelSheetEvent.DownloadVersion(version)) },
                onOpenExternalUrl = { url -> onEvent(ModelSheetEvent.OpenExternalUrl(url)) },
                onRemoveRecent = { recent -> onEvent(ModelSheetEvent.RemoveRecentHuggingFaceModel(recent.id)) },
                onClearRecent = { onEvent(ModelSheetEvent.ClearRecentHuggingFaceModels) },
                onRecheckRecent = { recent ->
                    huggingFaceInput = recent.originUrl
                    selectedHuggingFaceTargetId = recent.targetModelId
                    onEvent(
                        ModelSheetEvent.ResolveHuggingFaceCandidate(
                            input = recent.originUrl,
                            targetModelId = recent.targetModelId,
                        ),
                    )
                    scope.launch {
                        listState.animateScrollToItem(huggingFaceSectionIndex)
                    }
                },
            )
        }
        item {
            ActiveModelSection(
                modelLoadingState = modelLoadingState,
                routingMode = routingMode,
                presetBackingStore = presetBackingStore,
                onRetryLoad = { model -> onEvent(ModelSheetEvent.RetryLoad(model.modelId, model.modelVersion)) },
                onLoadLastUsedModel = { onEvent(ModelSheetEvent.LoadLastUsedModel) },
                onOffloadModel = { onEvent(ModelSheetEvent.OffloadModel) },
                onChooseAnother = {
                    scope.launch {
                        val visibleMatch = listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.key == DOWNLOADED_SECTION_KEY }
                        if (visibleMatch != null) {
                            listState.animateScrollToItem(visibleMatch.index)
                        } else {
                            // Item not yet visible — scroll toward the end where
                            // the downloaded-section header lives, then refine.
                            listState.animateScrollToItem(
                                listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1,
                            )
                            val match = listState.layoutInfo.visibleItemsInfo
                                .firstOrNull { it.key == DOWNLOADED_SECTION_KEY }
                            if (match != null) {
                                listState.animateScrollToItem(match.index)
                            }
                        }
                    }
                },
            )
        }
        item { HorizontalDivider() }
        if (downloadQueueTasks.isNotEmpty()) {
            item {
                SectionHeader(
                    title = stringResource(id = R.string.ui_model_download_queue),
                    subtitle = stringResource(id = R.string.ui_model_download_queue_subtitle),
                )
            }
            items(
                downloadQueueTasks,
                key = { task -> "download_queue:${task.taskId}" },
            ) { task ->
                DownloadQueueTaskCard(
                    task = task,
                    onPauseDownload = { taskId -> onEvent(ModelSheetEvent.PauseDownload(taskId)) },
                    onResumeDownload = { taskId -> onEvent(ModelSheetEvent.ResumeDownload(taskId)) },
                    onRetryDownload = { taskId -> onEvent(ModelSheetEvent.RetryDownload(taskId)) },
                    onCancelDownload = { taskId -> onEvent(ModelSheetEvent.CancelDownload(taskId)) },
                )
            }
            item { HorizontalDivider() }
        }
        item(key = DOWNLOADED_SECTION_KEY) {
            SectionHeader(
                title = stringResource(id = R.string.ui_downloaded_models),
                subtitle = stringResource(id = R.string.ui_downloaded_models_subtitle),
            )
        }
        if (installedVersions.isEmpty()) {
            item {
                EmptyStateCard(
                    title = stringResource(id = R.string.ui_no_downloaded_models_title),
                    body = stringResource(id = R.string.ui_no_downloaded_models_body),
                )
            }
        } else {
            items(
                installedVersions,
                key = { (model, version) -> installedVersionItemKey(model.modelId, version.version) },
            ) { (model, version) ->
                DownloadedModelCard(
                    model = model,
                    version = version,
                    eligibility = libraryState.eligibility.eligibilityFor(model.modelId, version.version),
                    activeModel = activeModel,
                    loadedModel = modelLoadingState.loadedModel,
                    busy = busy,
                    onImportModel = { modelId -> onEvent(ModelSheetEvent.ImportModel(modelId)) },
                    onLoadVersion = { modelId, ver -> onEvent(ModelSheetEvent.LoadVersion(modelId, ver)) },
                    onRemoveVersion = { modelId, ver -> onEvent(ModelSheetEvent.RequestRemove(modelId, ver)) },
                )
            }
        }
        item { HorizontalDivider() }
        item {
            SectionHeader(
                title = stringResource(id = R.string.ui_available_models),
                subtitle = stringResource(id = R.string.ui_available_models_subtitle),
            )
        }
        if (!libraryState.isManifestLoaded) {
            items(3) { ShimmerModelCard() }
        } else if (availableVersions.isEmpty()) {
            item {
                EmptyStateCard(
                    title = stringResource(id = R.string.ui_catalog_up_to_date_title),
                    body = stringResource(id = R.string.ui_catalog_up_to_date_body),
                )
            }
        } else {
            items(
                availableVersions,
                key = { entry -> downloadVersionItemKey(entry.version.modelId, entry.version.version) },
            ) { entry ->
                AvailableModelCard(
                    displayName = entry.displayName,
                    version = entry.version,
                    eligibility = entry.eligibility,
                    task = downloadTasksByKey[versionIdentityKey(entry.version.modelId, entry.version.version)],
                    isImporting = runtimeState.isImporting,
                    isEnqueuing = versionIdentityKey(entry.version.modelId, entry.version.version) in libraryState.enqueuingModelIds,
                    onImportModel = { modelId -> onEvent(ModelSheetEvent.ImportModel(modelId)) },
                    onDownloadVersion = { ver -> onEvent(ModelSheetEvent.DownloadVersion(ver)) },
                    onPauseDownload = { taskId -> onEvent(ModelSheetEvent.PauseDownload(taskId)) },
                    onResumeDownload = { taskId -> onEvent(ModelSheetEvent.ResumeDownload(taskId)) },
                    onRetryDownload = { taskId -> onEvent(ModelSheetEvent.RetryDownload(taskId)) },
                    onCancelDownload = { taskId -> onEvent(ModelSheetEvent.CancelDownload(taskId)) },
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = { onEvent(ModelSheetEvent.Close) }) {
                    Text(stringResource(id = R.string.ui_close))
                }
            }
        }
    }
}

internal const val DOWNLOADED_SECTION_KEY = "downloaded_section_header"
internal const val HUGGING_FACE_SECTION_KEY = "hugging_face_acquisition_section"
private const val HF_SEARCH_VISIBLE_RESULT_LIMIT = 5

@Composable
private fun HuggingFaceAcquisitionSection(
    input: String,
    selectedTargetId: String,
    targets: List<HuggingFaceTargetModel>,
    state: HuggingFaceAcquisitionUiState,
    searchQuery: String,
    searchState: HuggingFaceSearchUiState,
    recentModels: List<HuggingFaceRecentModel>,
    availableStorageBytes: Long?,
    onInputChange: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSelectTarget: (String) -> Unit,
    onCheck: () -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onUseSearchResult: (HuggingFaceSearchFileResult) -> Unit,
    onDownloadVersion: (ModelDistributionVersion) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onRemoveRecent: (HuggingFaceRecentModel) -> Unit,
    onClearRecent: () -> Unit,
    onRecheckRecent: (HuggingFaceRecentModel) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("model_library_add_hugging_face"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(PocketAgentDimensions.cardPadding),
            verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
        ) {
            Text(
                text = stringResource(id = R.string.ui_hf_add_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(id = R.string.ui_hf_add_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("model_library_hf_url_input"),
                singleLine = true,
                placeholder = { Text(stringResource(id = R.string.ui_hf_url_placeholder)) },
            )
            if (targets.isNotEmpty()) {
                Text(
                    text = stringResource(id = R.string.ui_hf_target_model_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    modifier = Modifier.testTag("model_library_hf_target_model"),
                    horizontalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
                    verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
                ) {
                    targets.forEach { target ->
                        val selected = target.modelId == selectedTargetId
                        if (selected) {
                            Button(onClick = { onSelectTarget(target.modelId) }) {
                                Text(target.displayName)
                            }
                        } else {
                            OutlinedButton(onClick = { onSelectTarget(target.modelId) }) {
                                Text(target.displayName)
                            }
                        }
                    }
                }
            }
            val resolving = state is HuggingFaceAcquisitionUiState.Resolving
            val checkDisabledReason = when {
                input.isBlank() -> stringResource(id = R.string.ui_hf_disabled_missing_url)
                selectedTargetId.isBlank() -> stringResource(id = R.string.ui_hf_disabled_missing_target)
                resolving -> stringResource(id = R.string.ui_hf_checking)
                else -> null
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
                verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
            ) {
                Button(
                    onClick = onCheck,
                    enabled = checkDisabledReason == null,
                    modifier = Modifier
                        .testTag("model_library_hf_check_url")
                        .then(
                            if (checkDisabledReason != null) {
                                Modifier.semantics { stateDescription = checkDisabledReason }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    Text(stringResource(id = if (resolving) R.string.ui_hf_checking else R.string.ui_hf_check_url))
                }
                if (state !is HuggingFaceAcquisitionUiState.Idle) {
                    TextButton(onClick = onClear) {
                        Text(stringResource(id = R.string.ui_clear))
                    }
                }
            }
            HorizontalDivider()
            HuggingFaceSearchSection(
                query = searchQuery,
                selectedTargetId = selectedTargetId,
                state = searchState,
                onQueryChange = onSearchQueryChange,
                onSearch = onSearch,
                onClearSearch = onClearSearch,
                onUseResult = onUseSearchResult,
                onOpenExternalUrl = onOpenExternalUrl,
            )
            when (state) {
                HuggingFaceAcquisitionUiState.Idle -> Unit
                HuggingFaceAcquisitionUiState.Resolving -> {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                is HuggingFaceAcquisitionUiState.Blocked -> {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .testTag("model_library_hf_error")
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                is HuggingFaceAcquisitionUiState.Ready -> {
                    HuggingFaceCandidateCard(
                        candidate = state.candidate,
                        availableStorageBytes = availableStorageBytes,
                        queueing = false,
                        onDownloadVersion = onDownloadVersion,
                        onOpenExternalUrl = onOpenExternalUrl,
                    )
                }
                is HuggingFaceAcquisitionUiState.Enqueueing -> {
                    HuggingFaceCandidateCard(
                        candidate = state.candidate,
                        availableStorageBytes = availableStorageBytes,
                        queueing = true,
                        onDownloadVersion = onDownloadVersion,
                        onOpenExternalUrl = onOpenExternalUrl,
                    )
                }
            }
            if (recentModels.isNotEmpty()) {
                HorizontalDivider()
                HuggingFaceRecentModelsSection(
                    recentModels = recentModels,
                    onRemoveRecent = onRemoveRecent,
                    onClearRecent = onClearRecent,
                    onRecheckRecent = onRecheckRecent,
                    onOpenExternalUrl = onOpenExternalUrl,
                )
            }
        }
    }
}

@Composable
private fun HuggingFaceSearchSection(
    query: String,
    selectedTargetId: String,
    state: HuggingFaceSearchUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onUseResult: (HuggingFaceSearchFileResult) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("model_library_hf_search"),
        verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
    ) {
        Text(
            text = stringResource(id = R.string.ui_hf_search_title),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(id = R.string.ui_hf_search_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("model_library_hf_search_input"),
            singleLine = true,
            placeholder = { Text(stringResource(id = R.string.ui_hf_search_placeholder)) },
        )
        val searching = state is HuggingFaceSearchUiState.Searching
        val searchDisabledReason = when {
            query.isBlank() -> stringResource(id = R.string.ui_hf_search_disabled_missing_query)
            searching -> stringResource(id = R.string.ui_hf_searching)
            else -> null
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
            verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
        ) {
            Button(
                onClick = onSearch,
                enabled = searchDisabledReason == null,
                modifier = Modifier
                    .testTag("model_library_hf_search_button")
                    .then(
                        if (searchDisabledReason != null) {
                            Modifier.semantics { stateDescription = searchDisabledReason }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                Text(stringResource(id = if (searching) R.string.ui_hf_searching else R.string.ui_hf_search_button))
            }
            if (state !is HuggingFaceSearchUiState.Idle) {
                TextButton(
                    onClick = onClearSearch,
                    modifier = Modifier.testTag("model_library_hf_search_clear"),
                ) {
                    Text(stringResource(id = R.string.ui_clear))
                }
            }
        }
        when (state) {
            HuggingFaceSearchUiState.Idle -> Unit
            HuggingFaceSearchUiState.Searching -> {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            is HuggingFaceSearchUiState.Blocked -> {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .testTag("model_library_hf_search_error")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            is HuggingFaceSearchUiState.Empty -> {
                Text(
                    text = stringResource(id = R.string.ui_hf_search_empty, state.query),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .testTag("model_library_hf_search_empty")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            is HuggingFaceSearchUiState.Results -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("model_library_hf_search_results"),
                    verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
                ) {
                    Text(
                        text = stringResource(id = R.string.ui_hf_search_results_title, state.results.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.results
                        .take(HF_SEARCH_VISIBLE_RESULT_LIMIT)
                        .groupBy { result -> result.reference.repoId }
                        .forEach { (repoId, repoResults) ->
                            HuggingFaceSearchRepoGroup(
                                repoId = repoId,
                                results = repoResults,
                                selectedTargetId = selectedTargetId,
                                onUseResult = onUseResult,
                                onOpenExternalUrl = onOpenExternalUrl,
                            )
                        }
                }
            }
        }
    }
}

@Composable
private fun HuggingFaceSearchRepoGroup(
    repoId: String,
    results: List<HuggingFaceSearchFileResult>,
    selectedTargetId: String,
    onUseResult: (HuggingFaceSearchFileResult) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("model_library_hf_search_repo_group"),
        verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
    ) {
        Text(
            text = stringResource(id = R.string.ui_hf_search_repo_group, repoId),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        results.forEach { result ->
            HuggingFaceSearchResultRow(
                result = result,
                selectedTargetId = selectedTargetId,
                onUseResult = onUseResult,
                onOpenExternalUrl = onOpenExternalUrl,
            )
        }
    }
}

@Composable
private fun HuggingFaceSearchResultRow(
    result: HuggingFaceSearchFileResult,
    selectedTargetId: String,
    onUseResult: (HuggingFaceSearchFileResult) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val context = LocalContext.current
    val fileName = result.reference.filePath.substringAfterLast('/')
    val quantization = quantizationHintFor(fileName)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("model_library_hf_search_result"),
        verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing / 2),
    ) {
        Text(
            text = result.displayName,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(id = R.string.ui_hf_search_file_path, result.reference.filePath),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        quantization?.let { label ->
            Text(
                text = stringResource(id = R.string.ui_hf_search_quantization, label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("model_library_hf_search_quantization"),
            )
        }
        Text(
            text = result.canonicalUrl,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val metricParts = listOfNotNull(
            result.downloads?.let { count ->
                stringResource(id = R.string.ui_hf_search_downloads, count.formatCount())
            },
            result.likes?.let { count ->
                stringResource(id = R.string.ui_hf_search_likes, count.formatCount())
            },
            result.license?.takeIf { license -> license.isNotBlank() }?.let { license ->
                stringResource(id = R.string.ui_hf_candidate_license, license)
            },
        )
        if (metricParts.isNotEmpty()) {
            Text(
                text = metricParts.joinToString(separator = " | "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val blockedReason = when {
            result.private -> stringResource(id = R.string.ui_hf_search_private_unsupported)
            result.gated -> stringResource(id = R.string.ui_hf_search_gated_unsupported)
            selectedTargetId.isBlank() -> stringResource(id = R.string.ui_hf_disabled_missing_target)
            else -> null
        }
        blockedReason?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.labelSmall,
                color = if (result.private || result.gated) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
            verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
        ) {
            OutlinedButton(
                onClick = { onUseResult(result) },
                enabled = blockedReason == null,
                modifier = Modifier
                    .testTag("model_library_hf_search_use_file")
                    .then(
                        if (blockedReason != null) {
                            Modifier.semantics {
                                stateDescription = blockedReason
                                contentDescription = context.getString(R.string.ui_hf_search_use_file)
                            }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                Text(stringResource(id = R.string.ui_hf_search_use_file))
            }
            TextButton(
                onClick = { onOpenExternalUrl(result.modelCardUrl) },
                modifier = Modifier.testTag("model_library_hf_search_open_model_card"),
            ) {
                Text(stringResource(id = R.string.ui_hf_open_model_card))
            }
            TextButton(
                onClick = { onOpenExternalUrl(result.canonicalUrl) },
                modifier = Modifier.testTag("model_library_hf_search_open_file"),
            ) {
                Text(stringResource(id = R.string.ui_hf_open_file))
            }
        }
    }
}

@Composable
private fun HuggingFaceRecentModelsSection(
    recentModels: List<HuggingFaceRecentModel>,
    onRemoveRecent: (HuggingFaceRecentModel) -> Unit,
    onClearRecent: () -> Unit,
    onRecheckRecent: (HuggingFaceRecentModel) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("model_library_hf_recent"),
        verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
    ) {
        Text(
            text = stringResource(id = R.string.ui_hf_recent_title),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(id = R.string.ui_hf_recent_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = onClearRecent,
            modifier = Modifier.testTag("model_library_hf_recent_clear"),
        ) {
            Text(stringResource(id = R.string.ui_hf_recent_clear))
        }
        recentModels.take(4).forEach { recent ->
            HuggingFaceRecentModelRow(
                recent = recent,
                onRemoveRecent = onRemoveRecent,
                onRecheckRecent = onRecheckRecent,
                onOpenExternalUrl = onOpenExternalUrl,
            )
        }
    }
}

@Composable
private fun HuggingFaceRecentModelRow(
    recent: HuggingFaceRecentModel,
    onRemoveRecent: (HuggingFaceRecentModel) -> Unit,
    onRecheckRecent: (HuggingFaceRecentModel) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val context = LocalContext.current
    val modelCardUrl = "https://huggingface.co/${recent.repoId}"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("model_library_hf_recent_row"),
        verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing / 2),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing / 2),
        ) {
            Text(
                text = recent.displayName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(id = R.string.ui_hf_candidate_target, recent.targetDisplayName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    id = R.string.ui_model_download_expected_size,
                    Formatter.formatShortFileSize(context, recent.sizeBytes),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(id = R.string.ui_hf_candidate_sha, recent.sha256.take(12)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            recent.license?.takeIf { license -> license.isNotBlank() }?.let { license ->
                Text(
                    text = stringResource(id = R.string.ui_hf_candidate_license, license),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("model_library_hf_recent_license"),
                )
            }
            recent.licenseUrl?.takeIf { url -> url.isNotBlank() }?.let { licenseUrl ->
                TextButton(
                    onClick = { onOpenExternalUrl(licenseUrl) },
                    modifier = Modifier.testTag("model_library_hf_recent_open_license"),
                ) {
                    Text(stringResource(id = R.string.ui_hf_open_license))
                }
            }
            Text(
                text = stringResource(
                    id = R.string.ui_hf_recent_checked,
                    recent.validatedAtEpochMs.relativeTimeLabel(),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    id = R.string.ui_hf_recent_queued,
                    recent.lastDownloadEnqueuedAtEpochMs.relativeTimeLabel(),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
            verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
        ) {
            OutlinedButton(
                onClick = { onRecheckRecent(recent) },
                modifier = Modifier.testTag("model_library_hf_recent_recheck"),
            ) {
                Text(stringResource(id = R.string.ui_hf_recent_recheck))
            }
            TextButton(
                onClick = { onOpenExternalUrl(modelCardUrl) },
                modifier = Modifier.testTag("model_library_hf_recent_open_model_card"),
            ) {
                Text(stringResource(id = R.string.ui_hf_open_model_card))
            }
            TextButton(
                onClick = { onRemoveRecent(recent) },
                modifier = Modifier.testTag("model_library_hf_recent_remove"),
            ) {
                Text(stringResource(id = R.string.ui_remove))
            }
        }
    }
}

private fun Long.relativeTimeLabel(): String {
    val now = System.currentTimeMillis()
    return DateUtils.getRelativeTimeSpanString(
        this,
        now,
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
}

private fun Long.formatCount(): String {
    return NumberFormat.getIntegerInstance().format(this)
}

private fun quantizationHintFor(fileName: String): String? {
    return HF_SEARCH_QUANTIZATION_REGEX
        .find(fileName)
        ?.groupValues
        ?.getOrNull(1)
        ?.uppercase(Locale.ROOT)
}

@Composable
private fun HuggingFaceCandidateCard(
    candidate: HuggingFaceCandidate,
    availableStorageBytes: Long?,
    queueing: Boolean,
    onDownloadVersion: (ModelDistributionVersion) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val context = LocalContext.current
    val modelCardUrl = "https://huggingface.co/${candidate.reference.repoId}"
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("model_library_hf_candidate_card"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(PocketAgentDimensions.cardPadding),
            verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing / 2),
        ) {
            Text(candidate.displayName, style = MaterialTheme.typography.labelLarge)
            Text(
                text = stringResource(id = R.string.ui_hf_candidate_target, candidate.target.displayName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(id = R.string.ui_hf_candidate_model_card, modelCardUrl),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = { onOpenExternalUrl(modelCardUrl) },
                modifier = Modifier.testTag("model_library_hf_open_model_card"),
            ) {
                Text(stringResource(id = R.string.ui_hf_open_model_card))
            }
            Text(
                text = stringResource(
                    id = R.string.ui_hf_candidate_source,
                    candidate.reference.repoId,
                    candidate.reference.revision,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            candidate.version.promptProfileId?.let { promptProfileId ->
                Text(
                    text = stringResource(id = R.string.ui_hf_candidate_prompt_profile, promptProfileId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(
                    id = R.string.ui_model_download_expected_size,
                    Formatter.formatShortFileSize(context, candidate.sizeBytes),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HuggingFaceCandidateStorageLine(
                candidateSizeBytes = candidate.sizeBytes,
                availableStorageBytes = availableStorageBytes,
            )
            Text(
                text = stringResource(id = R.string.ui_hf_candidate_checksum_status, candidate.sha256.take(12)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            candidate.license?.takeIf { license -> license.isNotBlank() }?.let { license ->
                Text(
                    text = stringResource(id = R.string.ui_hf_candidate_license, license),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("model_library_hf_license"),
                )
            }
            candidate.licenseUrl?.takeIf { url -> url.isNotBlank() }?.let { licenseUrl ->
                TextButton(
                    onClick = { onOpenExternalUrl(licenseUrl) },
                    modifier = Modifier.testTag("model_library_hf_open_license"),
                ) {
                    Text(stringResource(id = R.string.ui_hf_open_license))
                }
            }
            Text(
                text = stringResource(id = R.string.ui_hf_candidate_compatibility),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { onDownloadVersion(candidate.version) },
                enabled = !queueing,
                modifier = Modifier
                    .testTag("model_library_hf_queue_download")
                    .then(
                        if (queueing) {
                            Modifier.semantics {
                                stateDescription = context.getString(R.string.ui_hf_candidate_queue_disabled)
                            }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                Text(stringResource(id = if (queueing) R.string.ui_model_download_queuing else R.string.ui_hf_queue_download))
            }
        }
    }
}

private val HF_SEARCH_QUANTIZATION_REGEX = Regex(
    pattern = "(?:^|[-_.])((?:IQ\\d_[A-Z0-9_]+)|(?:Q\\d(?:_[A-Z0-9]+)+)|F16|F32)(?:[-_.]|$)",
    option = RegexOption.IGNORE_CASE,
)

@Composable
private fun HuggingFaceCandidateStorageLine(
    candidateSizeBytes: Long,
    availableStorageBytes: Long?,
) {
    val context = LocalContext.current
    val freeBytes = availableStorageBytes?.takeIf { it >= 0L } ?: return
    val remainingBytes = freeBytes - candidateSizeBytes
    val storageText = if (remainingBytes >= 0L) {
        stringResource(
            id = R.string.ui_hf_candidate_storage_after,
            Formatter.formatShortFileSize(context, remainingBytes),
        )
    } else {
        stringResource(
            id = R.string.ui_hf_candidate_storage_warning,
            Formatter.formatShortFileSize(context, candidateSizeBytes),
            Formatter.formatShortFileSize(context, freeBytes),
        )
    }
    Text(
        text = storageText,
        style = MaterialTheme.typography.labelSmall,
        color = if (remainingBytes >= 0L) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.error
        },
        modifier = Modifier.testTag("model_library_hf_storage_impact"),
    )
}

@Composable
private fun StatusMessageCard(message: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("model_sheet_status_message")
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PocketAgentDimensions.cardPadding),
            verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing / 2),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ActiveModelSection(
    modelLoadingState: ModelLoadingState,
    routingMode: RoutingMode,
    presetBackingStore: PresetBackingStore,
    onRetryLoad: (com.pocketagent.runtime.RuntimeLoadedModel) -> Unit,
    onLoadLastUsedModel: () -> Unit,
    onOffloadModel: () -> Unit,
    onChooseAnother: () -> Unit,
) {
    val revision by presetBackingStore.revisionFlow().collectAsState()
    val preset = remember(revision, routingMode) {
        presetBackingStore.presetMatchingRoutingMode(routingMode)
    }
    val preferenceHeadline = when {
        routingMode == RoutingMode.AUTO || preset == ModelPreset.AUTO ->
            stringResource(
                id = R.string.ui_routing_mode_label,
                stringResource(id = R.string.ui_preset_auto),
            )
        preset == ModelPreset.QUICK ->
            stringResource(
                id = R.string.ui_routing_mode_label,
                stringResource(id = R.string.ui_preset_quick),
            )
        preset == ModelPreset.BALANCED ->
            stringResource(
                id = R.string.ui_routing_mode_label,
                stringResource(id = R.string.ui_preset_balanced_chat),
            )
        preset == ModelPreset.VISION ->
            stringResource(
                id = R.string.ui_routing_mode_label,
                stringResource(id = R.string.ui_preset_vision),
            )
        else ->
            stringResource(id = R.string.ui_routing_mode_label, routingMode.name)
    }
    val backingDisplayName = remember(revision, preset) {
        when (preset) {
            ModelPreset.QUICK, ModelPreset.BALANCED, ModelPreset.VISION -> {
                val backingId = PresetRoutingResolver.effectiveBackingModelId(
                    preset,
                    presetBackingStore.customBackingModelId(preset),
                )
                backingId?.takeIf { it.isNotBlank() }?.let { id -> ModelDisplayNames.displayNameFor(id) }
            }
            else -> null
        }
    }
    val currentModel = modelLoadingState.activeOrRequestedModel()
    val canLoadLastUsed = modelLoadingState.loadedModel == null &&
        modelLoadingState.lastUsedModel != null &&
        modelLoadingState !is ModelLoadingState.Error &&
        modelLoadingState !is ModelLoadingState.Loading &&
        modelLoadingState !is ModelLoadingState.Offloading
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(PocketAgentDimensions.cardPadding),
            verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
        ) {
            Text(stringResource(id = R.string.ui_active_model), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            StatusRow(
                color = modelLoadingState.statusColor(),
                label = modelLoadingState.statusHeadline(),
                pulsing = modelLoadingState is ModelLoadingState.Loading || modelLoadingState is ModelLoadingState.Offloading,
            )
            Text(
                text = currentModel?.let { loaded ->
                    buildString {
                        append(loaded.modelId)
                        loaded.modelVersion?.takeIf { it.isNotBlank() }?.let { version ->
                            append(" • ")
                            append(version)
                        }
                    }
                } ?: stringResource(id = R.string.ui_nothing_loaded),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = preferenceHeadline,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            backingDisplayName?.let { label ->
                Text(
                    text = stringResource(id = R.string.ui_preset_using_model, label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (modelLoadingState) {
                is ModelLoadingState.Loading -> {
                    val progress = modelLoadingState.progress
                    if (progress != null && progress > 0f) {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        text = modelLoadingState.stage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is ModelLoadingState.Offloading -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = stringResource(id = if (modelLoadingState.queued) R.string.ui_unload_queued else R.string.ui_releasing_runtime_memory),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is ModelLoadingState.Error -> {
                    Text(
                        text = modelLoadingState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
                        verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
                    ) {
                        currentModel?.let { retryModel ->
                            OutlinedButton(onClick = { onRetryLoad(retryModel) }) {
                                Text(stringResource(id = R.string.ui_model_runtime_retry_load))
                            }
                        }
                        TextButton(
                            onClick = onChooseAnother,
                            modifier = Modifier.testTag("choose_another_model"),
                        ) {
                            Text(stringResource(id = R.string.ui_choose_another_model))
                        }
                    }
                }

                is ModelLoadingState.Loaded -> {
                    modelLoadingState.detail?.takeIf { detail -> detail.isNotBlank() }?.let { detail ->
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> Unit
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
                verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
            ) {
                if (canLoadLastUsed) {
                    OutlinedButton(onClick = onLoadLastUsedModel) {
                        Text(stringResource(id = R.string.ui_load_last_used))
                    }
                }
                if (modelLoadingState.loadedModel != null) {
                    OutlinedButton(onClick = onOffloadModel) {
                        Text(stringResource(id = R.string.ui_unload))
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadedModelCard(
    model: ProvisionedModelState,
    version: ModelVersionDescriptor,
    eligibility: ModelVersionEligibility,
    activeModel: com.pocketagent.runtime.RuntimeLoadedModel?,
    loadedModel: com.pocketagent.runtime.RuntimeLoadedModel?,
    busy: Boolean,
    onImportModel: (String) -> Unit,
    onLoadVersion: (String, String) -> Unit,
    onRemoveVersion: (String, String) -> Unit,
) {
    val haptic = rememberHaptic()
    val hapticConfirm = rememberLongPressHaptic()
    val badge = resolveDownloadedModelBadge(
        model = model,
        version = version,
        activeModel = activeModel,
        loadedModel = loadedModel,
    )
    val isLoaded = badge == DownloadedModelBadge.LOADED
    val statusColor = when (badge) {
        DownloadedModelBadge.LOADED -> MaterialTheme.colorScheme.primary
        DownloadedModelBadge.SWITCHING -> MaterialTheme.colorScheme.tertiary
        DownloadedModelBadge.READY -> MaterialTheme.colorScheme.outline
    }
    val loadDisabledReason = when {
        !eligibility.loadAllowed -> eligibilityMessage(eligibility)
        isLoaded -> stringResource(id = R.string.ui_load_button_disabled_already_loaded)
        busy -> stringResource(id = R.string.ui_load_button_disabled_busy)
        else -> null
    }
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(PocketAgentDimensions.cardPadding),
            verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing / 2),
                ) {
                    Text(model.displayName, style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = stringResource(
                            id = R.string.ui_model_installed_version_row,
                            model.modelId,
                            version.version,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusRow(
                    color = statusColor,
                    label = when (badge) {
                        DownloadedModelBadge.LOADED -> stringResource(id = R.string.ui_loaded)
                        DownloadedModelBadge.SWITCHING -> stringResource(id = R.string.ui_switching)
                        DownloadedModelBadge.READY -> stringResource(id = R.string.ui_ready)
                    },
                    pulsing = badge == DownloadedModelBadge.SWITCHING,
                )
            }
            eligibilityMessage(eligibility)?.takeIf { message ->
                eligibility.experimental || !eligibility.loadAllowed
            }?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (eligibility.experimental) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
                verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
            ) {
                Button(
                    onClick = { haptic(); onLoadVersion(model.modelId, version.version) },
                    enabled = !busy && !isLoaded && eligibility.loadAllowed,
                    modifier = modelLibraryLoadButtonModifier(
                        modelId = model.modelId,
                        version = version.version,
                    )
                        .then(
                            if (loadDisabledReason != null) {
                                Modifier.semantics { stateDescription = loadDisabledReason }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    Text(stringResource(id = if (isLoaded) R.string.ui_loaded else R.string.ui_load))
                }
                OutlinedButton(onClick = { haptic(); onImportModel(model.modelId) }) {
                    Text(stringResource(id = if (model.isProvisioned) R.string.ui_replace_file else R.string.ui_import))
                }
            }
            HorizontalDivider()
            OutlinedButton(
                onClick = { hapticConfirm(); onRemoveVersion(model.modelId, version.version) },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.testTag("remove_button_${model.modelId}_${version.version}"),
            ) {
                Text(stringResource(id = R.string.ui_remove))
            }
        }
    }
}

@Composable
private fun AvailableModelCard(
    displayName: String,
    version: ModelDistributionVersion,
    eligibility: ModelVersionEligibility,
    task: DownloadTaskState?,
    isImporting: Boolean,
    isEnqueuing: Boolean,
    onImportModel: (String) -> Unit,
    onDownloadVersion: (ModelDistributionVersion) -> Unit,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
) {
    val context = LocalContext.current
    val reducedMotion = LocalReduceMotion.current
    val haptic = rememberHaptic()
    val hapticConfirm = rememberLongPressHaptic()
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(PocketAgentDimensions.cardPadding),
            verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(displayName, style = MaterialTheme.typography.labelLarge)
                if (eligibility.supportLevel == ModelSupportLevel.EXPERIMENTAL) {
                    StatusRow(
                        color = MaterialTheme.colorScheme.tertiary,
                        label = stringResource(id = R.string.ui_experimental),
                    )
                }
            }
            Text(
                text = stringResource(
                    id = R.string.ui_model_download_version_label,
                    version.modelId,
                    version.version,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    id = R.string.ui_model_download_expected_size,
                    Formatter.formatShortFileSize(context, version.bundleTotalBytes().coerceAtLeast(0L)),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            eligibilityMessage(eligibility)?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (eligibility.experimental) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            // Download progress section — animates in/out when a task appears or disappears
            AnimatedVisibility(
                visible = task != null,
                enter = if (reducedMotion) fadeIn(snap()) else fadeIn(tween(PocketAgentDimensions.animNormal)) + expandVertically(),
                exit = if (reducedMotion) fadeOut(snap()) else fadeOut(tween(PocketAgentDimensions.animFast)) + shrinkVertically(),
            ) {
                task?.let { activeTask ->
                    val rawProgress = (activeTask.progressPercent / 100f).coerceIn(0f, 1f)
                    val animatedProgress by animateFloatAsState(
                        targetValue = rawProgress,
                        animationSpec = if (reducedMotion) snap() else tween(PocketAgentDimensions.animNormal),
                        label = "download_progress_${activeTask.taskId}",
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing)) {
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    liveRegion = LiveRegionMode.Polite
                                },
                        )
                        Text(
                            text = stringResource(
                                id = R.string.ui_model_download_state,
                                activeTask.readableStateNameLocalized(),
                                activeTask.progressPercent,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // Transfer speed + ETA (shown when available)
                        activeTask.transferSummary()?.let { speedSummary ->
                            Text(
                                text = speedSummary,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        activeTask.stageWarningChips()
                        if (activeTask.status == DownloadTaskStatus.FAILED || activeTask.status == DownloadTaskStatus.CANCELLED) {
                            Text(
                                text = activeTask.failureReasonMessage(version),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
            // Action buttons — animate between download-idle / queuing / active / paused / failed states
            AnimatedContent(
                targetState = Pair(task?.status, isEnqueuing),
                transitionSpec = {
                    if (reducedMotion) {
                        fadeIn(snap()) togetherWith fadeOut(snap())
                    } else {
                        fadeIn(tween(PocketAgentDimensions.animFast)) togetherWith
                            fadeOut(tween(PocketAgentDimensions.animFast))
                    }
                },
                label = "download_action_buttons",
            ) { (taskStatus, enqueuing) ->
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
                    verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
                ) {
                    val downloadDisabledReason = if (eligibility.downloadAllowed) null else eligibilityMessage(eligibility)
                    when (taskStatus) {
                        DownloadTaskStatus.DOWNLOADING,
                        DownloadTaskStatus.QUEUED,
                        DownloadTaskStatus.VERIFYING,
                        -> {
                            OutlinedButton(onClick = { haptic(); task?.taskId?.let(onPauseDownload) }) {
                                Text(stringResource(id = R.string.ui_pause))
                            }
                            OutlinedButton(onClick = { hapticConfirm(); task?.taskId?.let(onCancelDownload) }) {
                                Text(stringResource(id = R.string.ui_cancel_button))
                            }
                        }

                        DownloadTaskStatus.PAUSED -> {
                            Button(onClick = { haptic(); task?.taskId?.let(onResumeDownload) }) {
                                Text(stringResource(id = R.string.ui_resume))
                            }
                            OutlinedButton(onClick = { hapticConfirm(); task?.taskId?.let(onCancelDownload) }) {
                                Text(stringResource(id = R.string.ui_cancel_button))
                            }
                        }

                        DownloadTaskStatus.FAILED,
                        DownloadTaskStatus.CANCELLED,
                        -> {
                            Button(onClick = { haptic(); task?.taskId?.let(onRetryDownload) }) {
                                Text(stringResource(id = R.string.ui_retry))
                            }
                        }

                        else -> {
                            if (enqueuing) {
                                Button(onClick = {}, enabled = false) {
                                    Text(stringResource(id = R.string.ui_model_download_queuing))
                                }
                            } else {
                                Button(
                                    onClick = { haptic(); onDownloadVersion(version) },
                                    enabled = eligibility.downloadAllowed,
                                    modifier = modelLibraryDownloadButtonModifier(
                                        modelId = version.modelId,
                                        version = version.version,
                                    )
                                        .then(
                                            if (downloadDisabledReason != null) {
                                                Modifier.semantics { stateDescription = downloadDisabledReason }
                                            } else {
                                                Modifier
                                            },
                                        ),
                                ) {
                                    Text(stringResource(id = R.string.ui_download))
                                }
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = { haptic(); onImportModel(version.modelId) },
                        enabled = !isImporting && eligibility.downloadAllowed,
                    ) {
                        Text(stringResource(id = R.string.ui_import))
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadQueueTaskCard(
    task: DownloadTaskState,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
) {
    val reducedMotion = LocalReduceMotion.current
    val haptic = rememberHaptic()
    val hapticConfirm = rememberLongPressHaptic()
    val rawProgress = (task.progressPercent / 100f).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = rawProgress,
        animationSpec = if (reducedMotion) snap() else tween(PocketAgentDimensions.animNormal),
        label = "download_queue_progress_${task.taskId}",
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("model_library_download_queue"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(PocketAgentDimensions.cardPadding),
            verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing / 2),
                ) {
                    Text(
                        text = task.displayName ?: task.modelId,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = stringResource(id = R.string.ui_model_download_version_label, task.modelId, task.version),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusRow(
                    color = when (task.status) {
                        DownloadTaskStatus.FAILED,
                        DownloadTaskStatus.CANCELLED,
                        -> MaterialTheme.colorScheme.error
                        DownloadTaskStatus.PAUSED -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    },
                    label = task.readableStateNameLocalized(),
                    pulsing = task.status == DownloadTaskStatus.DOWNLOADING ||
                        task.status == DownloadTaskStatus.QUEUED ||
                        task.status == DownloadTaskStatus.VERIFYING,
                )
            }
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(
                text = stringResource(
                    id = R.string.ui_model_download_state,
                    task.readableStateNameLocalized(),
                    task.progressPercent,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            task.transferSummary()?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            task.stageWarningChips()
            if (task.status == DownloadTaskStatus.FAILED || task.status == DownloadTaskStatus.CANCELLED) {
                Text(
                    text = task.message ?: stringResource(id = R.string.ui_model_download_failed_unknown, task.modelId, task.version),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
                verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
            ) {
                when (task.status) {
                    DownloadTaskStatus.DOWNLOADING,
                    DownloadTaskStatus.QUEUED,
                    DownloadTaskStatus.VERIFYING,
                    -> {
                        OutlinedButton(
                            onClick = { haptic(); onPauseDownload(task.taskId) },
                            modifier = Modifier.testTag("model_library_download_queue_pause"),
                        ) {
                            Text(stringResource(id = R.string.ui_pause))
                        }
                        OutlinedButton(
                            onClick = { hapticConfirm(); onCancelDownload(task.taskId) },
                            modifier = Modifier.testTag("model_library_download_queue_cancel"),
                        ) {
                            Text(stringResource(id = R.string.ui_cancel_button))
                        }
                    }
                    DownloadTaskStatus.PAUSED -> {
                        Button(
                            onClick = { haptic(); onResumeDownload(task.taskId) },
                            modifier = Modifier.testTag("model_library_download_queue_resume"),
                        ) {
                            Text(stringResource(id = R.string.ui_resume))
                        }
                        OutlinedButton(
                            onClick = { hapticConfirm(); onCancelDownload(task.taskId) },
                            modifier = Modifier.testTag("model_library_download_queue_cancel"),
                        ) {
                            Text(stringResource(id = R.string.ui_cancel_button))
                        }
                    }
                    DownloadTaskStatus.FAILED,
                    DownloadTaskStatus.CANCELLED,
                    -> {
                        Button(
                            onClick = { haptic(); onRetryDownload(task.taskId) },
                            modifier = Modifier.testTag("model_library_download_queue_retry"),
                        ) {
                            Text(stringResource(id = R.string.ui_retry))
                        }
                    }
                    DownloadTaskStatus.COMPLETED,
                    DownloadTaskStatus.INSTALLED_INACTIVE,
                    -> Unit
                }
            }
        }
    }
}

@Composable
private fun EmptyStateCard(
    title: String,
    body: String,
) {
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(PocketAgentDimensions.cardPadding),
            verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusRow(
    color: Color,
    label: String,
    pulsing: Boolean = false,
) {
    val statusDescription = stringResource(
        id = R.string.cd_model_status_indicator,
        label,
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(
            color = color,
            statusDescription = statusDescription,
            pulsing = pulsing,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

@Composable
private fun StatusDot(color: Color, statusDescription: String, pulsing: Boolean = false) {
    val reducedMotion = LocalReduceMotion.current
    val infiniteTransition = rememberInfiniteTransition(label = "status_dot_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(PocketAgentDimensions.animSlow, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "status_dot_alpha",
    )
    val alpha = if (pulsing && !reducedMotion) pulseAlpha else 1f
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(PocketAgentDimensions.statusDotSize)
            .clip(MaterialTheme.shapes.small)
            .background(color.copy(alpha = alpha))
            .semantics {
                contentDescription = statusDescription
            },
    )
}

@Composable
private fun eligibilityMessage(eligibility: ModelVersionEligibility): String? {
    return when (eligibility.reason) {
        ModelEligibilityReason.NONE -> null
        ModelEligibilityReason.RUNTIME_COMPATIBILITY_MISMATCH ->
            stringResource(id = R.string.ui_model_eligibility_runtime_mismatch)
        ModelEligibilityReason.MODEL_NOT_RUNTIME_ENABLED ->
            stringResource(id = R.string.ui_model_eligibility_runtime_disabled)
        ModelEligibilityReason.DEVICE_GPU_CLASS_UNSUPPORTED ->
            stringResource(id = R.string.ui_model_eligibility_gpu_device_unsupported)
        ModelEligibilityReason.GPU_RUNTIME_UNAVAILABLE ->
            stringResource(id = R.string.ui_model_eligibility_gpu_runtime_unavailable)
        ModelEligibilityReason.GPU_QUALIFICATION_PENDING ->
            stringResource(id = R.string.ui_model_eligibility_gpu_qualification_pending)
        ModelEligibilityReason.GPU_QUALIFICATION_FAILED ->
            stringResource(id = R.string.ui_model_eligibility_gpu_qualification_failed)
    }
}

private fun matchesModelSearch(
    searchQuery: String,
    modelId: String,
    displayName: String,
    version: String,
): Boolean {
    if (searchQuery.isBlank()) {
        return true
    }
    return modelId.contains(searchQuery, ignoreCase = true) ||
        displayName.contains(searchQuery, ignoreCase = true) ||
        version.contains(searchQuery, ignoreCase = true)
}

private fun versionIdentityKey(modelId: String, version: String): String = "$modelId::$version"

internal fun modelLibraryLoadButtonTag(modelId: String, version: String): String =
    "model_library_load_${modelId}_${version}"

internal fun modelLibraryDownloadButtonTag(modelId: String, version: String): String =
    "model_library_download_${modelId}_${version}"

private fun modelLibraryLoadButtonModifier(modelId: String, version: String): Modifier {
    return if (isLaunchDefaultModelVersion(modelId, version)) {
        Modifier.testTag("model_library_load_qwen3-0.6b-q4_k_m_q4_k_m")
    } else {
        Modifier.testTag(modelLibraryLoadButtonTag(modelId, version))
    }
}

private fun modelLibraryDownloadButtonModifier(modelId: String, version: String): Modifier {
    return if (isLaunchDefaultModelVersion(modelId, version)) {
        Modifier.testTag("model_library_download_qwen3-0.6b-q4_k_m_q4_k_m")
    } else {
        Modifier.testTag(modelLibraryDownloadButtonTag(modelId, version))
    }
}

private fun isLaunchDefaultModelVersion(modelId: String, version: String): Boolean {
    return modelId == "qwen3-0.6b-q4_k_m" && version == "q4_k_m"
}

private data class AvailableCatalogVersion(
    val displayName: String,
    val version: ModelDistributionVersion,
    val eligibility: ModelVersionEligibility,
)

@Composable
internal fun ModelLoadingState.statusHeadline(): String {
    return when (this) {
        is ModelLoadingState.Idle -> stringResource(id = R.string.ui_model_runtime_state_unloaded)
        is ModelLoadingState.Loading -> stage
        is ModelLoadingState.Loaded -> stringResource(id = R.string.ui_model_runtime_state_loaded)
        is ModelLoadingState.Offloading -> stringResource(id = R.string.ui_model_runtime_state_offloading)
        is ModelLoadingState.Error -> stringResource(id = R.string.ui_model_runtime_state_failed)
    }
}

@Composable
internal fun ModelLoadingState.statusColor(): Color {
    return when (this) {
        is ModelLoadingState.Idle -> MaterialTheme.colorScheme.outline
        is ModelLoadingState.Loading -> MaterialTheme.colorScheme.tertiary
        is ModelLoadingState.Loaded -> MaterialTheme.colorScheme.primary
        is ModelLoadingState.Offloading -> MaterialTheme.colorScheme.secondary
        is ModelLoadingState.Error -> MaterialTheme.colorScheme.error
    }
}
