package com.pocketagent.android.ui

import android.content.Context
import android.Manifest
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketagent.android.BuildConfig
import com.pocketagent.android.R
import com.pocketagent.android.runtime.MODEL_OFFLOAD_REASON_MANUAL
import com.pocketagent.android.runtime.modelSpecProviderForContext
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.ui.state.ChatGatePrimaryAction
import com.pocketagent.android.ui.state.ModalSurface
import com.pocketagent.android.ui.state.ModelLoadingState
import com.pocketagent.android.ui.state.activeOrRequestedModel
import com.pocketagent.android.ui.state.resolveChatGateState
import com.pocketagent.core.RoutingMode
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.runtime.ModelInteractionRegistry
import com.pocketagent.runtime.ThinkingSupport
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map


@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun PocketAgentApp(
    viewModel: ChatViewModel,
    provisioningViewModel: ModelProvisioningViewModel,
) {
    val state by viewModel.uiState.collectAsState()
    val modelLoadingState by viewModel.modelLoadingState.collectAsState()
    val provisioningState by provisioningViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val downloads = provisioningState.downloads
    val provisioningSnapshot = provisioningState.snapshot ?: return
    val chatGateState = resolveChatGateState(
        runtime = state.runtime,
        provisioningSnapshot = provisioningSnapshot,
        advancedUnlocked = state.advancedUnlocked,
    )
    val headerUiState = deriveChatHeaderUiState(modelLoadingState)
    val activeRuntimeModelLabel = headerUiState.activeRuntimeModelLabel
    val activeModelId = modelLoadingState.loadedModel?.modelId ?: state.runtime.activeModelId
    val canAttachImages = canAttachImagesForModel(activeModelId)
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val currentActiveSurface by rememberUpdatedState(state.activeSurface)
    val interactionRegistry = remember(context) {
        ModelInteractionRegistry(
            specProvider = modelSpecProviderForContext(context),
        )
    }
    val thinkingToggleModelId = modelLoadingState.loadedModel?.modelId ?: state.runtime.activeModelId
    val showThinkingToggle = thinkingToggleModelId?.let { modelId ->
        runCatching {
            interactionRegistry.interactionProfileForModel(modelId).thinkingSupport == ThinkingSupport.THINK_TAGS
        }.getOrDefault(false)
    } == true
    val canLoadLastUsedModel = headerUiState.canLoadLastUsedModel
    val lastUsedModelLabel = headerUiState.lastUsedModelLabel
    val appViewModel: ChatAppViewModel = viewModel()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val sessionDeleteUndoState = rememberSessionDeleteUndoState(
        snackbarHostState = snackbarHostState,
        onCommitDelete = viewModel::deleteSession,
    )
    val isOffline = rememberIsOffline()
    val selectedModelIdForImport by appViewModel.selectedModelIdForImport.collectAsState()
    val pendingGetReadyActivation by appViewModel.pendingGetReadyActivation.collectAsState()
    val pendingMeteredWarningVersion by appViewModel.pendingMeteredWarningVersion.collectAsState()
    val pendingNotificationPermissionVersion by appViewModel.pendingNotificationPermissionVersion.collectAsState()
    val pendingRoutingModeSwitch by appViewModel.pendingRoutingModeSwitch.collectAsState()
    val lastDownloadTransitionRefreshKey by appViewModel.lastDownloadTransitionRefreshKey.collectAsState()
    val readinessRefreshSequence by appViewModel.readinessRefreshSequence.collectAsState()
    val defaultGetReadyModelId = remember { resolveDefaultGetReadyModelId(isDebugBuild = BuildConfig.DEBUG) }
    val modelLibraryState = provisioningState.toModelLibraryUiState(defaultGetReadyModelId) ?: return
    val runtimeModelState = provisioningState.toRuntimeModelUiState() ?: return
    val modelRemoveUndoState = rememberModelRemoveUndoState(
        snackbarHostState = snackbarHostState,
        onCommitRemove = { modelId, version ->
            scope.launch {
                val model = modelLibraryState.snapshot.models
                    .firstOrNull { it.modelId == modelId }
                val targetVersion = model?.installedVersions
                    ?.firstOrNull { it.version == version }
                val removePlan = if (model != null && targetVersion != null) {
                    resolveRemoveVersionPlan(
                        model = model,
                        version = targetVersion,
                        loadedModel = modelLoadingState.loadedModel,
                    )
                } else {
                    null
                }
                if (removePlan?.isBlockedByActiveSelection == true) {
                    provisioningViewModel.setStatusMessage(
                        context.getString(R.string.ui_model_version_remove_failed),
                    )
                    return@launch
                }
                if (removePlan?.requiresOffload == true) {
                    provisioningViewModel.offloadModel(reason = MODEL_OFFLOAD_REASON_MANUAL)
                }
                if (removePlan?.requiresClearingActiveSelection == true) {
                    provisioningViewModel.clearActiveVersionAsync(modelId)
                }
                val removed = provisioningViewModel.removeVersionAsync(modelId, version)
                val statusMessage = if (removed) {
                    context.getString(R.string.ui_model_version_removed, modelId, version)
                } else {
                    context.getString(R.string.ui_model_version_remove_failed)
                }
                if (removed) {
                    viewModel.refreshRuntimeReadiness(statusDetailOverride = statusMessage)
                }
                provisioningViewModel.setStatusMessage(statusMessage)
            }
        },
    )
    val beginDownload: (ModelDistributionVersion) -> Unit = { version ->
        scope.launch {
            startModelDownload(
                context = context,
                version = version,
                enqueueDownload = { selected -> provisioningViewModel.enqueueDownload(selected) },
                onStatus = { message -> provisioningViewModel.setStatusMessage(message) },
            )
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val version = pendingNotificationPermissionVersion
        appViewModel.setPendingNotificationPermissionVersion(null)
        if (version == null) {
            return@rememberLauncherForActivityResult
        }
        if (!granted) {
            provisioningViewModel.setStatusMessage(
                context.getString(R.string.ui_model_download_notifications_disabled),
            )
        }
        beginDownload(version)
    }
    val launchDownloadFlow: (ModelDistributionVersion) -> Unit = { version ->
        when {
            provisioningViewModel.shouldWarnForMeteredLargeDownload(version) -> {
                appViewModel.setPendingMeteredWarningVersion(version)
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED -> {
                appViewModel.setPendingNotificationPermissionVersion(version)
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            else -> beginDownload(version)
        }
    }
    val openModelSheet: () -> Unit = {
        viewModel.showSurface(ModalSurface.ModelLibrary)
    }
    val showBusyModelOperationFeedback: () -> Unit = {
        scope.launch {
            snackbarHostState.showSnackbar(context.getString(R.string.ui_model_operation_already_in_progress))
        }
    }
    val loadModelVersionAction: (String, String, Boolean) -> Unit = { modelId, version, closeOnSuccess ->
        scope.launch {
            val result = viewModel.loadModel(modelId = modelId, version = version)
            if (result == null) {
                showBusyModelOperationFeedback()
                return@launch
            }
            provisioningViewModel.setStatusMessage(
                lifecycleStatusMessage(
                    context = context,
                    result = result,
                    fallbackModelId = modelId,
                    fallbackVersion = version,
                ),
            )
            if (result.success && closeOnSuccess) {
                viewModel.dismissSurface()
            }
        }
    }
    val loadLastUsedModelAction: (Boolean) -> Unit = { closeOnSuccess ->
        scope.launch {
            val result = viewModel.loadLastUsedModel()
            if (result == null) {
                showBusyModelOperationFeedback()
                return@launch
            }
            provisioningViewModel.setStatusMessage(
                lifecycleStatusMessage(
                    context = context,
                    result = result,
                    fallbackModelId = modelLoadingState.lastUsedModel?.modelId,
                    fallbackVersion = modelLoadingState.lastUsedModel?.modelVersion,
                ),
            )
            if (result.success && closeOnSuccess) {
                viewModel.dismissSurface()
            }
        }
    }
    val offloadModelAction: (Boolean) -> Unit = { closeOnSuccess ->
        scope.launch {
            val result = viewModel.offloadModel(reason = MODEL_OFFLOAD_REASON_MANUAL)
            if (result == null) {
                showBusyModelOperationFeedback()
                return@launch
            }
            provisioningViewModel.setStatusMessage(
                lifecycleStatusMessage(
                    context = context,
                    result = result,
                    fallbackModelId = modelLoadingState.activeOrRequestedModel()?.modelId,
                    fallbackVersion = modelLoadingState.activeOrRequestedModel()?.modelVersion,
                ),
            )
            if (result.success && closeOnSuccess) {
                viewModel.dismissSurface()
            }
        }
    }
    val refreshAction: () -> Unit = {
        viewModel.refreshRuntimeReadiness()
        provisioningViewModel.refreshSnapshot()
        scope.launch { provisioningViewModel.refreshManifest() }
        provisioningViewModel.setStatusMessage(
            context.getString(R.string.ui_model_refresh_runtime_feedback),
        )
    }
    val runGetReadyFlow: () -> Unit = {
        scope.launch {
            viewModel.onGetReadyTapped()
            provisioningViewModel.setStatusMessage(context.getString(R.string.ui_get_ready_started_status))
            provisioningViewModel.refreshManifest()
            val manifest = provisioningViewModel.uiState.value.manifest
            val defaultVersion = resolveDefaultGetReadyVersion(
                manifest = manifest,
                defaultModelId = defaultGetReadyModelId,
            )
            if (defaultVersion == null) {
                provisioningViewModel.setStatusMessage(
                    context.getString(R.string.ui_model_downloads_manifest_empty),
                )
                openModelSheet()
                return@launch
            }

            val existingVersion = provisioningViewModel.listInstalledVersionsAsync(
                modelId = defaultVersion.modelId,
            ).firstOrNull { it.version == defaultVersion.version }

            if (existingVersion != null) {
                provisioningViewModel.setActiveVersionAsync(
                    modelId = defaultVersion.modelId,
                    version = defaultVersion.version,
                )
                val loadResult = viewModel.loadModel(
                    modelId = defaultVersion.modelId,
                    version = defaultVersion.version,
                )
                loadResult?.let { result ->
                    provisioningViewModel.setStatusMessage(
                        lifecycleStatusMessage(
                            context = context,
                            result = result,
                            fallbackModelId = defaultVersion.modelId,
                            fallbackVersion = defaultVersion.version,
                        ),
                    )
                } ?: showBusyModelOperationFeedback()
                return@launch
            }

            appViewModel.setPendingGetReadyActivation(defaultVersion.modelId to defaultVersion.version)
            launchDownloadFlow(defaultVersion)
            openModelSheet()
        }
    }
    val onBlockedAction: (ChatGatePrimaryAction) -> Unit = { action ->
        when (action) {
            ChatGatePrimaryAction.GET_READY -> runGetReadyFlow()
            ChatGatePrimaryAction.OPEN_MODEL_SETUP -> openModelSheet()
            ChatGatePrimaryAction.REFRESH_RUNTIME_CHECKS -> refreshAction()
            ChatGatePrimaryAction.NONE -> Unit
        }
    }
    val handleRoutingModeSelected: (RoutingMode) -> Unit = { mode ->
        viewModel.setRoutingMode(mode)
        val targetModelId = com.pocketagent.inference.ModelCatalog.modelIdForRoutingMode(mode)
        val targetVersion = modelLibraryState.snapshot.models
            .firstOrNull { model -> model.modelId == targetModelId }
            ?.installedVersions
            ?.firstOrNull { version -> version.isActive }
            ?.version
            ?: modelLibraryState.snapshot.models
                .firstOrNull { model -> model.modelId == targetModelId }
                ?.installedVersions
                ?.firstOrNull()
                ?.version
        if (
            mode != RoutingMode.AUTO &&
            targetModelId != null &&
            !targetVersion.isNullOrBlank() &&
            modelLoadingState.loadedModel?.modelId != targetModelId
        ) {
            appViewModel.setPendingRoutingModeSwitch(targetModelId to targetVersion)
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val localPath = copyContentUriToLocal(context, uri)
                if (localPath != null) {
                    viewModel.addAttachedImage(localPath)
                } else {
                    snackbarHostState.showSnackbar(
                        message = context.getString(R.string.ui_image_attach_failed),
                    )
                }
            }
        }
    }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val modelId = appViewModel.selectedModelIdForImport.value ?: return@rememberLauncherForActivityResult
        if (uri == null) {
            provisioningViewModel.setStatusMessage(context.getString(R.string.ui_model_import_cancelled))
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            provisioningViewModel.setStatusMessage(context.getString(R.string.ui_model_import_in_progress))
            provisioningViewModel.importModelFromUri(
                modelId = modelId,
                sourceUri = uri,
            ).onSuccess { result ->
                val statusMessage = if (result.isActive) {
                    context.getString(
                        R.string.ui_model_import_success_active,
                        result.modelId,
                        result.version,
                    )
                } else {
                    context.getString(
                        R.string.ui_model_import_success_inactive,
                        result.modelId,
                        result.version,
                    )
                }
                viewModel.refreshRuntimeReadiness(
                    statusDetailOverride = statusMessage,
                )
                provisioningViewModel.setStatusMessage(statusMessage)
            }.onFailure { error ->
                provisioningViewModel.setStatusMessage(
                    context.getString(
                        R.string.ui_model_import_failure,
                        error.message ?: "Unknown import error",
                    ),
                )
            }
        }
    }

    BackHandler(
        enabled = state.activeSurface != ModalSurface.None || drawerState.currentValue == DrawerValue.Open,
    ) {
        when (state.activeSurface) {
            is ModalSurface.Onboarding -> viewModel.skipOnboarding()
            is ModalSurface.SessionDrawer -> viewModel.dismissSurface()
            ModalSurface.None -> scope.launch { drawerState.close() }
            else -> viewModel.dismissSurface()
        }
    }

    DownloadTransitionHandler(
        downloads = downloads,
        pendingGetReadyActivation = pendingGetReadyActivation,
        loadedModelId = modelLoadingState.loadedModel?.modelId,
        lastDownloadTransitionRefreshKey = lastDownloadTransitionRefreshKey,
        readinessRefreshSequence = readinessRefreshSequence,
        onRefreshSnapshot = provisioningViewModel::refreshSnapshot,
        onSetStatusMessage = provisioningViewModel::setStatusMessage,
        onActivateVersion = provisioningViewModel::setActiveVersionAsync,
        onLoadModel = viewModel::loadModel,
        onShowBusyModelOperationFeedback = showBusyModelOperationFeedback,
        onClearPendingGetReadyActivation = { appViewModel.setPendingGetReadyActivation(null) },
        onIncrementReadinessRefreshSequence = appViewModel::incrementReadinessRefreshSequence,
        onRefreshRuntimeReadiness = viewModel::refreshRuntimeReadiness,
        onSetLastDownloadTransitionRefreshKey = appViewModel::setLastDownloadTransitionRefreshKey,
        onOpenModelSheet = openModelSheet,
    )

    LaunchedEffect(state.activeSurface) {
        if (state.activeSurface !is ModalSurface.ModelLibrary) return@LaunchedEffect
        provisioningViewModel.refreshSnapshot()
    }

    LaunchedEffect(state.activeSurface) {
        if (state.activeSurface is ModalSurface.SessionDrawer) {
            drawerState.open()
        } else {
            drawerState.close()
        }
    }

    LaunchedEffect(drawerState) {
        snapshotFlow { drawerState.currentValue }
            .map { it == DrawerValue.Closed }
            .distinctUntilChanged()
            .filter { it }
            .collectLatest {
                if (currentActiveSurface is ModalSurface.SessionDrawer) {
                    viewModel.dismissSurface()
                }
            }
    }

    ModalNavigationDrawer(
        modifier = Modifier.semantics {
            testTagsAsResourceId = true
        },
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.fillMaxHeight()) {
                SessionDrawer(
                    state = state,
                    onCreateSession = {
                        viewModel.createSession()
                        viewModel.dismissSurface()
                    },
                    onSwitchSession = { id ->
                        viewModel.switchSession(id)
                        viewModel.dismissSurface()
                    },
                    onDeleteSession = sessionDeleteUndoState.requestDelete,
                    hiddenSessionIds = sessionDeleteUndoState.hiddenSessionIds,
                )
            }
        },
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                PocketAgentTopBar(
                    activeRuntimeModelLabel = activeRuntimeModelLabel,
                    lastUsedModelLabel = lastUsedModelLabel,
                    modelLibraryState = modelLibraryState,
                    onOpenSessionDrawer = { viewModel.showSurface(ModalSurface.SessionDrawer) },
                    onLoadModelVersion = { modelId, version ->
                        loadModelVersionAction(modelId, version, true)
                    },
                    onOpenModelLibrary = openModelSheet,
                    onOpenAdvancedSettings = { viewModel.showSurface(ModalSurface.AdvancedSettings) },
                )
            },
            bottomBar = {
                ComposerBar(
                    text = state.composer.text,
                    isSending = state.composer.isSending,
                    isCancelling = state.composer.isCancelling,
                    chatGateState = chatGateState,
                    editingMessageId = state.composer.editingMessageId,
                    attachedImages = state.composer.attachedImages,
                    activeSessionId = state.activeSessionId,
                    onTextChanged = viewModel::onComposerChanged,
                    onSend = viewModel::sendMessage,
                    onCancelSend = viewModel::cancelActiveSend,
                    onSubmitEdit = viewModel::submitEdit,
                    onCancelEdit = viewModel::cancelEdit,
                    onAttachImage = {
                        imagePicker.launch("image/*")
                    },
                    canAttachImages = canAttachImages,
                    onRemoveImage = viewModel::removeAttachedImage,
                    onOpenToolDialog = { viewModel.showSurface(ModalSurface.ToolSuggestions) },
                    showThinkingToggle = showThinkingToggle,
                    thinkingEnabled = state.activeSession?.completionSettings?.showThinking == true,
                    onToggleThinking = viewModel::toggleSessionThinking,
                    onOpenCompletionSettings = { viewModel.showSurface(ModalSurface.CompletionSettings) },
                    onBlockedAction = onBlockedAction,
                )
            },
        ) { innerPadding ->
            ChatScreenBody(
                state = state,
                modelLoadingState = modelLoadingState,
                onSuggestedPrompt = viewModel::prefillComposer,
                onOpenModels = openModelSheet,
                canLoadLastUsedModel = canLoadLastUsedModel,
                lastUsedModelLabel = lastUsedModelLabel,
                onLoadLastUsedModel = { loadLastUsedModelAction(false) },
                activeRuntimeModelLabel = activeRuntimeModelLabel,
                onRefresh = refreshAction,
                isOffline = isOffline,
                onOpenToolDialog = { viewModel.showSurface(ModalSurface.ToolSuggestions) },
                onEditMessage = viewModel::editMessage,
                onRegenerateMessage = viewModel::regenerateResponse,
                onCopiedToClipboard = {
                    scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.ui_copied_to_clipboard)) }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
    }

    ModelLibrarySheetHost(
        activeSurface = state.activeSurface,
        modelLibraryState = modelLibraryState,
        runtimeModelState = runtimeModelState,
        modelLoadingState = modelLoadingState,
        routingMode = state.runtime.routingMode,
        modelRemoveUndoState = modelRemoveUndoState,
        viewModel = viewModel,
        provisioningViewModel = provisioningViewModel,
        appViewModel = appViewModel,
        onLaunchImportPicker = {
            modelPicker.launch(arrayOf("*/*"))
        },
        onLaunchDownloadFlow = launchDownloadFlow,
        onLoadModelVersion = loadModelVersionAction,
        onLoadLastUsedModel = loadLastUsedModelAction,
        onOffloadModel = offloadModelAction,
    )

    ModalOrchestrator(
        state = state,
        provisioningState = provisioningState,
        pendingRoutingModeSwitch = pendingRoutingModeSwitch,
        pendingMeteredWarningVersion = pendingMeteredWarningVersion,
        downloads = downloads,
        onDismissSurface = viewModel::dismissSurface,
        onUseToolPrompt = viewModel::prefillComposer,
        onDefaultThinkingEnabledChanged = viewModel::setDefaultThinkingEnabled,
        onRoutingModeSelected = handleRoutingModeSelected,
        onPerformanceProfileSelected = viewModel::setPerformanceProfile,
        onKeepAlivePreferenceSelected = viewModel::setKeepAlivePreference,
        onWifiOnlyDownloadsChanged = provisioningViewModel::setDownloadWifiOnlyEnabled,
        onGpuAccelerationEnabledChanged = viewModel::setGpuAccelerationEnabled,
        onExportDiagnostics = viewModel::exportDiagnostics,
        completionSettings = state.activeSession?.completionSettings ?: com.pocketagent.android.ui.state.CompletionSettings(),
        onCompletionSettingsChanged = viewModel::updateSessionCompletionSettings,
        onDismissRoutingModeSwitch = { appViewModel.setPendingRoutingModeSwitch(null) },
        onConfirmRoutingModeSwitch = { modelId, version ->
            appViewModel.setPendingRoutingModeSwitch(null)
            loadModelVersionAction(modelId, version, false)
        },
        onDismissMeteredDownloadWarning = { appViewModel.setPendingMeteredWarningVersion(null) },
        onConfirmMeteredDownloadWarning = { version ->
            provisioningViewModel.acknowledgeLargeDownloadCellularWarning()
            appViewModel.setPendingMeteredWarningVersion(null)
            launchDownloadFlow(version)
        },
        onOnboardingPageChanged = viewModel::setOnboardingPage,
        onNextOnboardingPage = viewModel::nextOnboardingPage,
        onSkipOnboarding = viewModel::skipOnboarding,
        onFinishOnboarding = viewModel::completeOnboarding,
        onStartOnboardingDownload = runGetReadyFlow,
    )
}

private suspend fun copyContentUriToLocal(context: Context, uri: Uri): String? {
    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val mimeType = context.contentResolver.getType(uri)
            val extension = MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(mimeType)
                ?.lowercase()
                ?: uri.lastPathSegment?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }
                ?: "jpg"
            val imagesDir = java.io.File(context.cacheDir, "attached_images").apply { mkdirs() }
            // Clean up stale attached images older than 1 hour
            val staleThresholdMs = 60 * 60 * 1000L
            imagesDir.listFiles()?.forEach { file ->
                if (System.currentTimeMillis() - file.lastModified() > staleThresholdMs) {
                    file.delete()
                }
            }
            val target = java.io.File(imagesDir, "img_${System.currentTimeMillis()}.$extension")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@runCatching null
            if (target.length() == 0L) {
                target.delete()
                return@runCatching null
            }
            target.absolutePath
        }.getOrNull()
    }
}

internal fun canAttachImagesForModel(modelId: String?): Boolean {
    return modelId?.let(ModelCatalog::isVisionCapable) == true
}
